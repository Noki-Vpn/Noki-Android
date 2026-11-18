package com.noki.vpn.data

import java.util.Locale

object EndpointRankingPolicy {
    /** Rank each physical server once, before selecting its transport endpoints. */
    fun rankServers(
        servers: List<VpnServer>,
        profile: UserProfile,
        nowMillis: Long = System.currentTimeMillis(),
        random: () -> Double = { kotlin.random.Random.nextDouble() },
    ): List<VpnServer> {
        val eligible = servers.distinctBy { it.id }.filter { server ->
            server.isOnline && server.weight > 0 && when (profile.serverSelectionMode) {
                ServerSelectionMode.AUTO -> true
                ServerSelectionMode.COUNTRY -> server.countryCode.equals(profile.selectedCountryCode, true)
                ServerSelectionMode.SERVER -> server.id == profile.selectedNodeId
            }
        }
        return rankNodes(eligible, { it.id }, { it.latencyMs }, { it.weight }, { it.metricsAt }, nowMillis, random)
    }

    /** One selection policy for authorised physical nodes, regardless of the route using them. */
    fun <T> rankNodes(
        nodes: List<T>,
        id: (T) -> String,
        latencyMs: (T) -> Int?,
        selectionWeight: (T) -> Int,
        metricsAt: (T) -> String?,
        nowMillis: Long = System.currentTimeMillis(),
        random: () -> Double = { kotlin.random.Random.nextDouble() },
    ): List<T> {
        val remaining = nodes.distinctBy(id).filter { selectionWeight(it) > 0 }.toMutableList()
        return buildList {
            while (remaining.isNotEmpty()) {
                val fastest = remaining.mapNotNull(latencyMs).minOrNull()
                val pool = if (fastest == null) remaining.toList() else remaining.filter {
                    latencyMs(it)?.let { ping -> ping <= fastest + maxOf(20, fastest / 4) } == true
                }
                val weights = pool.map { server ->
                    val metricsTime = metricsAt(server)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                    // Unknown/stale telemetry is neutral, never an invented empty server.
                    if (metricsTime != null && nowMillis - metricsTime in 0..120_000L) {
                        selectionWeight(server).toDouble().coerceAtLeast(1.0)
                    } else 1.0
                }
                var ticket = random().coerceIn(0.0, 0.999999999) * weights.sum()
                var chosen = pool.last()
                for (index in pool.indices) {
                    ticket -= weights[index]
                    if (ticket < 0) { chosen = pool[index]; break }
                }
                add(chosen)
                remaining.remove(chosen)
            }
        }
    }

    private const val DEFAULT_SCORE = 70
    private const val SUCCESS_SCORE_DELTA = 8
    private const val FAILURE_SCORE_DELTA = 25
    private const val SLOW_SCORE_DELTA = 15
    private const val COOLDOWN_MILLIS = 10 * 60 * 1000L
    private const val LATENCY_FRESH_MILLIS = 6 * 60 * 60 * 1000L
    private const val SCORE_RECOVERY_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    private const val SCORE_RECOVERY_STEP = 8

    enum class NetworkKind {
        WIFI,
        CELLULAR,
        OTHER,
    }

    data class Selection(
        val candidate: BackendEndpointCandidate,
        val endpointClass: String,
        val rotationKey: String,
    )

    fun select(
        candidates: List<BackendEndpointCandidate>,
        health: Map<String, EndpointHealth>,
        networkKind: NetworkKind,
        nowMillis: Long,
        rotationIndex: (String) -> Int,
        excludedCodes: Set<String> = emptySet(),
        preferredCode: String? = null,
    ): Selection? {
        val ranked = rankCandidates(candidates, health, networkKind, nowMillis, excludedCodes)
        val best = ranked.firstOrNull() ?: return null
        val comparator = candidateSelectionComparator(health, nowMillis, networkKind)
        val bestTier = ranked.takeWhile { comparator.compare(best, it) == 0 }
        val endpointClass = classify(best)
        val rotationKey = rotationKeyFor(endpointClass, bestTier)
        val candidate = bestTier.firstOrNull { it.code == preferredCode }
            ?: bestTier[rotationIndex(rotationKey).floorMod(bestTier.size)]
        return Selection(candidate, endpointClass, rotationKey)
    }

    fun rankCandidates(
        candidates: List<BackendEndpointCandidate>,
        health: Map<String, EndpointHealth>,
        networkKind: NetworkKind,
        nowMillis: Long,
        excludedCodes: Set<String> = emptySet(),
    ): List<BackendEndpointCandidate> {
        val eligibleWithoutCooldown = eligibleCandidates(
            candidates = candidates,
            health = health,
            nowMillis = nowMillis,
            excludedCodes = excludedCodes,
        )
        val eligible = eligibleWithoutCooldown.ifEmpty {
            eligibleCandidates(
                candidates = candidates,
                health = health,
                nowMillis = nowMillis,
                excludedCodes = excludedCodes,
                ignoreCooldown = true,
            )
        }
        if (eligible.isEmpty()) return emptyList()

        val supportedClasses = preferredClasses(networkKind)
        return eligible.filter { classify(it) in supportedClasses }
            .sortedWith(candidateSelectionComparator(health, nowMillis, networkKind).thenBy { it.code })
    }

    fun selectWarmupCandidates(
        rankedCandidates: List<BackendEndpointCandidate>,
        health: Map<String, EndpointHealth>,
        maxCandidates: Int,
    ): List<BackendEndpointCandidate> {
        val limit = maxCandidates.coerceAtLeast(0)
        val best = rankedCandidates.firstOrNull() ?: return emptyList()
        if (limit == 0) return emptyList()
        if (limit == 1) return listOf(best)

        val explorationCandidate = rankedCandidates
            .drop(1)
            .minByOrNull { candidate ->
                health[candidate.code]?.lastUpdatedAtMillis ?: Long.MIN_VALUE
            }
        return buildList {
            add(best)
            explorationCandidate?.let(::add)
            if (size < limit) {
                rankedCandidates.asSequence()
                    .filterNot { candidate -> any { it.code == candidate.code } }
                    .take(limit - size)
                    .forEach(::add)
            }
        }
    }

    fun updateAfterResult(
        previous: EndpointHealth = EndpointHealth(),
        success: Boolean,
        nowMillis: Long,
        slow: Boolean = false,
        latencyMs: Long? = null,
    ): EndpointHealth {
        val delta = when {
            success -> SUCCESS_SCORE_DELTA
            slow -> -SLOW_SCORE_DELTA
            else -> -FAILURE_SCORE_DELTA
        }
        val nextScore = (effectiveScore(previous, nowMillis) + delta).coerceIn(0, 100)
        val shouldCooldown = !success
        val normalizedLatency = latencyMs?.takeIf { success && it > 0L }
        val nextLatency = normalizedLatency?.let { sample ->
            freshLatency(previous, nowMillis)?.let { old -> ((old * 3L) + sample) / 4L } ?: sample
        } ?: previous.latencyEwmaMs
        return previous.copy(
            score = nextScore,
            successCount = if (success) previous.successCount + 1 else previous.successCount,
            failureCount = if (success) previous.failureCount else previous.failureCount + 1,
            cooldownUntilMillis = if (shouldCooldown) nowMillis + COOLDOWN_MILLIS else 0L,
            lastUpdatedAtMillis = nowMillis,
            latencyEwmaMs = nextLatency,
            latencyUpdatedAtMillis = if (normalizedLatency != null) nowMillis else previous.latencyUpdatedAtMillis,
        )
    }

    fun ratingSnapshot(
        codes: List<String>,
        health: Map<String, EndpointHealth>,
    ): String {
        return codes
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(";") { code ->
                val score = health[code]?.score ?: DEFAULT_SCORE
                "$code=$score"
            }
    }

    private fun preferredClasses(
        networkKind: NetworkKind,
    ): List<String> {
        return when (networkKind) {
            NetworkKind.WIFI -> listOf(CLASS_REALITY_TCP, CLASS_REALITY_XHTTP_STREAM, CLASS_REALITY_XHTTP_PACKET, CLASS_TLS_TCP, CLASS_HYSTERIA, CLASS_NAIVE, CLASS_NOKINV)
            NetworkKind.CELLULAR -> listOf(CLASS_REALITY_XHTTP_STREAM, CLASS_REALITY_XHTTP_PACKET, CLASS_REALITY_TCP, CLASS_TLS_TCP, CLASS_HYSTERIA, CLASS_NAIVE, CLASS_NOKINV)
            NetworkKind.OTHER -> listOf(
                CLASS_REALITY_XHTTP_STREAM,
                CLASS_REALITY_XHTTP_PACKET,
                CLASS_REALITY_TCP,
                CLASS_TLS_TCP,
                CLASS_HYSTERIA,
                CLASS_NAIVE,
                CLASS_NOKINV,
            )
        }
    }

    private fun classify(candidate: BackendEndpointCandidate): String {
        val security = candidate.security.lowercase(Locale.ROOT)
        val transport = candidate.normalizedTransport()
        val mode = candidate.normalizedTransportMode()
        return when {
            candidate.proxyType.equals("naive", ignoreCase = true) -> CLASS_NAIVE
            candidate.proxyType.equals("nokinv", ignoreCase = true) -> CLASS_NOKINV
            security == "reality" && transport == "tcp" -> CLASS_REALITY_TCP
            security == "reality" && transport == "xhttp" && mode == "stream-up" -> CLASS_REALITY_XHTTP_STREAM
            security == "reality" && transport == "xhttp" && mode == "packet-up" -> CLASS_REALITY_XHTTP_PACKET
            security == "tls" && transport == "tcp" -> CLASS_TLS_TCP
            EndpointTransportPolicy.isHysteria(candidate) -> CLASS_HYSTERIA
            else -> CLASS_OTHER
        }
    }

    private fun eligibleCandidates(
        candidates: List<BackendEndpointCandidate>,
        health: Map<String, EndpointHealth>,
        nowMillis: Long,
        excludedCodes: Set<String> = emptySet(),
        ignoreCooldown: Boolean = false,
    ): List<BackendEndpointCandidate> {
        return candidates
            .filter { !it.canaryOnly }
            .filter { it.entryHost.isNotBlank() }
            .filter { it.code !in excludedCodes }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .filter { candidate ->
                val state = health[candidate.code]
                ignoreCooldown || state == null || state.cooldownUntilMillis <= nowMillis
            }
    }

    private fun candidateSelectionComparator(
        health: Map<String, EndpointHealth>,
        nowMillis: Long,
        networkKind: NetworkKind,
    ): Comparator<BackendEndpointCandidate> {
        val classOrder = preferredClasses(networkKind)
        return compareByDescending<BackendEndpointCandidate> { effectiveScore(health[it.code], nowMillis) }
            .thenBy { freshLatency(health[it.code], nowMillis) ?: Long.MAX_VALUE }
            .thenBy { classOrder.indexOf(classify(it)) }
            .thenBy { it.priority }
            .thenByDescending { it.weight }
    }

    private fun effectiveScore(
        health: EndpointHealth?,
        nowMillis: Long,
    ): Int {
        val score = health?.score ?: return DEFAULT_SCORE
        val updatedAt = health.lastUpdatedAtMillis
        if (updatedAt <= 0L || nowMillis <= updatedAt || score == DEFAULT_SCORE) return score
        val elapsedIntervals = (nowMillis - updatedAt) / SCORE_RECOVERY_INTERVAL_MILLIS
        if (elapsedIntervals <= 0L) return score
        val recovery = (elapsedIntervals * SCORE_RECOVERY_STEP).coerceAtMost(100L).toInt()
        return if (score < DEFAULT_SCORE) {
            (score + recovery).coerceAtMost(DEFAULT_SCORE)
        } else {
            (score - recovery).coerceAtLeast(DEFAULT_SCORE)
        }
    }

    private fun freshLatency(
        health: EndpointHealth?,
        nowMillis: Long,
    ): Long? {
        val latency = health?.latencyEwmaMs ?: return null
        val age = nowMillis - health.latencyUpdatedAtMillis
        return latency.takeIf { age in 0..LATENCY_FRESH_MILLIS }
    }

    private fun rotationKeyFor(
        endpointClass: String,
        candidates: List<BackendEndpointCandidate>,
    ): String {
        val location = candidates.firstOrNull()?.locationCode?.ifBlank { "all" } ?: "all"
        val priority = candidates.minOfOrNull { it.priority } ?: 0
        return "$location:$endpointClass:$priority"
    }

    private fun Int.floorMod(divisor: Int): Int {
        if (divisor <= 0) return 0
        val value = this % divisor
        return if (value < 0) value + divisor else value
    }

    private const val CLASS_REALITY_TCP = "reality-tcp"
    private const val CLASS_REALITY_XHTTP_STREAM = "reality-xhttp-stream"
    private const val CLASS_REALITY_XHTTP_PACKET = "reality-xhttp-packet"
    private const val CLASS_TLS_TCP = "tls-tcp"
    private const val CLASS_HYSTERIA = "hysteria"
    private const val CLASS_NOKINV = "nokinv"
    private const val CLASS_NAIVE = "naive"
    private const val CLASS_OTHER = "other"
}

internal fun BackendEndpointCandidate.normalizedTransport(): String {
    return when (transport.lowercase(Locale.ROOT)) {
        "raw" -> "tcp"
        "hysteria2", "hy2" -> "hysteria"
        else -> transport.lowercase(Locale.ROOT).ifBlank { "tcp" }
    }
}

internal fun BackendEndpointCandidate.normalizedTransportMode(): String {
    val mode = transportMode.orEmpty().lowercase(Locale.ROOT).trim()
    if (mode.isNotBlank()) return mode
    return when (normalizedTransport()) {
        "xhttp" -> "stream-up"
        "nokinv" -> "quic"
        else -> ""
    }
}
