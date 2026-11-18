package com.noki.vpn.vpn

import android.content.Context
import com.noki.vpn.data.*
import kotlinx.coroutines.*
import java.util.UUID

data class YoutubeConnectionConfig(val wireguard: BackendRuWireGuardConfig, val privateKey: ByteArray)

/** Owns only the additional admission; AppVpnService keeps the single runtime and TUN. */
internal class YoutubeConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val api: () -> BackendApiClient,
    private val onConfigurationChanged: () -> Unit,
    private val measureNodes: suspend (List<VpnServer>) -> Map<String, Int> = { nodes ->
        ClientLatencySampler.startup(context).measureNodes(nodes, { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp })
    },
) {
    @Volatile private var configuration: YoutubeConnectionConfig? = null
    @Volatile private var validUntil = 0L
    @Volatile private var target: String? = null
    private var job: Job? = null
    private var expiry: Job? = null
    @Volatile private var sequence = 0L
    private var failedTarget: String? = null

    fun configFor(settings: StoredSettings): YoutubeConnectionConfig? =
        targetFor(settings)?.let { requested ->
            configuration.takeIf { target == requested && validUntil > System.currentTimeMillis() }
        }

    @Synchronized fun start(settings: StoredSettings) {
        val next = targetFor(settings)
        if (next != null && next == failedTarget) return
        if (next == target && job?.isActive == true) return
        stop()
        if (next == null) return
        target = next
        val owner = sequence
        job = scope.launch {
            try {
                run(owner, next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publish(owner, null)
                runCatching { repository.recordAppLog("vpn", level = "error", message = "youtube_prepare_failed",
                    errorType = error.javaClass.simpleName) }
            }
        }
    }

    @Synchronized fun stop() {
        sequence++
        failedTarget = null
        target = null
        job?.cancel()
        job = null
        expiry?.cancel()
        expiry = null
        configuration = null
        validUntil = 0L
    }

    @Synchronized fun rejectConfiguration() {
        val failed = target
        stop()
        failedTarget = failed
    }

    @Synchronized private fun publish(owner: Long, value: YoutubeConnectionConfig?, expiresAt: Long = 0L) {
        if (owner != sequence) return
        val changed = !sameConfiguration(configuration, value)
        configuration = value
        validUntil = expiresAt
        expiry?.cancel()
        expiry = value?.let {
            scope.launch {
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                // A cancelled expiry may already be waiting for this monitor when the lease renews.
                synchronized(this@YoutubeConnection) {
                    if (validUntil == expiresAt) publish(owner, null)
                }
            }
        }
        if (changed) onConfigurationChanged()
    }

    private suspend fun run(owner: Long, expectedTarget: String) {
        var grant: BackendYoutubeAccess? = null
        val pair = WireGuardKeyStore.android(context).getOrCreate()
        try {
            var keyId: String? = null
            var requestId = UUID.randomUUID().toString()
            var transientFailures = 0
            var selectionSamples: List<RuRelayRttSample>? = null
            while (currentCoroutineContext().isActive && owner == sequence) {
                try {
                    val settings = repository.load()
                    check(targetFor(settings) == expectedTarget) { "youtube_selection_changed" }
                    if (grant?.let { it.expiresAtEpochMillis <= System.currentTimeMillis() } == true) {
                        grant = null
                        selectionSamples = null
                        requestId = UUID.randomUUID().toString()
                    }
                    val previous = grant
                    val response = authorized { client, token, current ->
                        check(targetFor(current) == expectedTarget) { "youtube_selection_changed" }
                        if (previous == null) {
                            if (selectionSamples == null) {
                                val nodes = try { client.youtubeNodes(token, current.backendDeviceId, current.backendDeviceKey) }
                                    catch (error: BackendException) { if (error.statusCode == 404) emptyList() else throw error }
                                val samples = measureNodes(nodes)
                                selectionSamples = nodes.distinctBy { it.id }.mapNotNull { node ->
                                    samples[node.id]?.takeIf { it in 1..3000 }?.let { RuRelayRttSample(node.id, it) }
                                }.take(64)
                            }
                            if (keyId == null) keyId = client.registerRuWireGuardKey(token, current.backendDeviceId,
                                current.backendDeviceKey, pair.publicKeyBase64()).id
                            client.createYoutubeAccess(token, requestId, current.backendDeviceId, current.backendDeviceKey,
                                checkNotNull(keyId), selectionSamples.orEmpty())
                        } else if (previous.status == "ready") {
                            client.renewYoutubeAccess(token, current.backendDeviceId, current.backendDeviceKey, previous)
                        } else {
                            client.pollYoutubeAccess(token, current.backendDeviceId, current.backendDeviceKey, previous.grantId)
                        }
                    }
                    if (previous != null) require(response.grantId == previous.grantId && response.mode == previous.mode &&
                        response.generation in previous.generation..(previous.generation +
                            if (previous.status == "ready" || response.status == "blocked") 1 else 0)) {
                        "youtube_access_identity_changed"
                    }
                    grant = response
                    transientFailures = 0
                    val current = repository.load()
                    check(targetFor(current) == expectedTarget) { "youtube_selection_changed" }
                    if (response.status == "blocked") {
                        publish(owner, null)
                        if (needsFreshAdmission(response, System.currentTimeMillis())) {
                            grant = null
                            selectionSamples = null
                            requestId = UUID.randomUUID().toString()
                            delay(2_000)
                            continue
                        }
                        awaitCancellation()
                    }
                    if (response.status == "ready") {
                        publish(owner, runtimeConfig(response, checkNotNull(keyId), pair.privateKey), response.expiresAtEpochMillis)
                        delay(renewDelayMillis(response.expiresAtEpochMillis, System.currentTimeMillis()))
                    } else {
                        delay(2_000)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!BackendRetryPolicy.isTransient(error)) {
                        publish(owner, null)
                        repository.recordAppLog("vpn", level = "error", message = "youtube_access_blocked",
                            errorType = error.javaClass.simpleName)
                        awaitCancellation()
                    }
                    delay(transientRetryDelayMillis(transientFailures))
                    transientFailures = (transientFailures + 1).coerceAtMost(4)
                }
            }
        } finally {
            publish(owner, null)
            pair.privateKey.fill(0)
            // shortcut: offline revokes expire on the server within 120s; persist retries if the TTL grows.
            grant?.let { last -> withContext(NonCancellable) {
                withTimeoutOrNull(6_000) { runCatching { api().revokeYoutubeAccess(last.grantId, last.revokeToken) } }
            } }
        }
    }

    private suspend fun <T> authorized(action: suspend (BackendApiClient, String, StoredSettings) -> T): T {
        val client = api()
        var current = repository.load()
        try {
            return action(client, checkNotNull(current.backendAccessToken), current)
        } catch (error: BackendException) {
            if (error.statusCode != 401) throw error
            checkNotNull(AuthTokenRefresher(repository, client,
                onRevocationPending = { PendingLogoutRevocationWorker.enqueue(context) }).refreshStoredTokens()) {
                "auth_required"
            }
            current = repository.load()
            return action(client, checkNotNull(current.backendAccessToken), current)
        }
    }

    companion object {
        internal fun targetFor(settings: StoredSettings): String? {
            if (!settings.isAuthenticated || settings.userProfile.isFreePlan ||
                !settings.advancedSettings.youtubeNoAdsEnabled || settings.backendDeviceId.isBlank()) return null
            return listOf(settings.userProfile.backendUserId, settings.backendDeviceId, settings.backendDeviceKey).joinToString("|")
        }

        internal fun transientRetryDelayMillis(failures: Int): Long =
            (2_000L shl failures.coerceIn(0, 4)).coerceAtMost(30_000L)

        internal fun needsFreshAdmission(grant: BackendYoutubeAccess, now: Long): Boolean =
            grant.status == "blocked" && grant.expiresAtEpochMillis <= now

        internal fun renewDelayMillis(expiresAt: Long, now: Long): Long =
            ((expiresAt - now).coerceAtLeast(0L) * 2 / 3).coerceAtLeast(1L)

        internal fun sameConfiguration(previous: YoutubeConnectionConfig?, next: YoutubeConnectionConfig?): Boolean =
            previous?.wireguard == next?.wireguard

        internal fun runtimeConfig(grant: BackendYoutubeAccess, keyId: String, privateKey: ByteArray): YoutubeConnectionConfig {
            require(grant.status == "ready" && grant.mode == "wireguard") { "youtube_access_not_ready" }
            require(grant.publicKeyId == keyId) { "youtube_access_key_mismatch" }
            return YoutubeConnectionConfig(checkNotNull(grant.wireguard), privateKey)
        }
    }
}
