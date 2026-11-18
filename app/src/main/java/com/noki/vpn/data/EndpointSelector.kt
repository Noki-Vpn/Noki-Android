package com.noki.vpn.data

import android.content.Context
import com.noki.vpn.vpn.AndroidUnderlyingNetworkSource

object EndpointSelector {
    data class EndpointSelectionResult(
        val profile: VlessProfile,
        val endpointCode: String,
        val endpointRating: String,
        val precheckFailedEndpointCodes: List<String> = emptyList(),
        val precheckShouldPenalizeFailures: Boolean = false,
        val networkKind: EndpointRankingPolicy.NetworkKind = EndpointRankingPolicy.NetworkKind.OTHER,
    )

    fun profileForSession(
        context: Context,
        session: BackendVpnSession,
        settings: AdvancedSettings,
        directOnly: Boolean = true,
    ): VlessProfile {
        return selectionForSession(
            context = context,
            session = session,
            settings = settings,
            endpointHealth = emptyMap(),
            rotationIndex = { 0 },
            directOnly = directOnly,
        ).profile
    }

    fun selectionForSession(
        context: Context,
        session: BackendVpnSession,
        settings: AdvancedSettings,
        endpointHealth: Map<String, EndpointHealth>,
        rotationIndex: (String) -> Int,
        startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)? = null,
        networkKind: EndpointRankingPolicy.NetworkKind? = null,
        onDiagnostic: (String) -> Unit = {},
        directOnly: Boolean = true,
    ): EndpointSelectionResult {
        require(!settings.ruRelayEnabled) { "ru_route_requires_ru_coordinator" }
        require(session.multiHop?.selection == settings.multiHop.takeIf { it.enabled }) { "multihop_session_mismatch" }
        val selection = selectCandidate(
            context = context,
            session = session,
            settings = settings,
            endpointHealth = endpointHealth,
            rotationIndex = rotationIndex,
            startupTcpPrecheck = startupTcpPrecheck,
            networkKind = networkKind,
            onDiagnostic = onDiagnostic,
            directOnly = directOnly,
        )
        val candidate = selection?.candidate
        onDiagnostic("stage=selection; selected=${candidate?.code.orEmpty()}; tcp_failed=${selection?.precheckFailedEndpointCodes?.size ?: 0}; legacy=${selection == null}")
        val profile = when {
            candidate != null -> profileFromCandidate(session, candidate)
            selection != null -> VlessProfile()
            else -> profileFromLegacySession(session)
        }
        val ratingCodes = session.endpointCandidates
            .filter { !it.canaryOnly }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .map { it.code }
            .ifEmpty { listOf(profile.endpointCode) }
        return EndpointSelectionResult(
            profile = profile,
            endpointCode = profile.endpointCode,
            endpointRating = EndpointRankingPolicy.ratingSnapshot(ratingCodes, endpointHealth),
            precheckFailedEndpointCodes = selection?.precheckFailedEndpointCodes.orEmpty(),
            precheckShouldPenalizeFailures = selection?.precheckShouldPenalizeFailures ?: false,
            networkKind = selection?.networkKind ?: networkKind ?: currentNetworkKind(context),
        )
    }

    private data class CandidateSelection(
        val candidate: BackendEndpointCandidate?,
        val precheckFailedEndpointCodes: List<String> = emptyList(),
        val precheckShouldPenalizeFailures: Boolean = false,
        val networkKind: EndpointRankingPolicy.NetworkKind,
    )

    private fun selectCandidate(
        context: Context,
        session: BackendVpnSession,
        settings: AdvancedSettings,
        endpointHealth: Map<String, EndpointHealth>,
        rotationIndex: (String) -> Int,
        startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)?,
        networkKind: EndpointRankingPolicy.NetworkKind?,
        onDiagnostic: (String) -> Unit,
        directOnly: Boolean,
    ): CandidateSelection? {
        val withHost = session.endpointCandidates
            .filter { it.entryHost.isNotBlank() }
        val secure = withHost
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .filter { !directOnly || settings.multiHop.enabled || EndpointTransportPolicy.isDirectCandidate(it) }
        val matchingProtocol = secure.filter { matchesProtocol(it, settings.effectiveProtocol) }
        val candidates = matchingProtocol.filter { !it.canaryOnly }
        onDiagnostic("stage=candidates; received=${session.endpointCandidates.size}; with_host=${withHost.size}; security_allowed=${secure.size}; protocol_allowed=${matchingProtocol.size}; eligible=${candidates.size}; protocol=${settings.protocol}; mode=${settings.endpointSelectionMode}")
        if (candidates.isEmpty()) {
            require(!settings.multiHop.enabled) { "multihop_transport_unavailable" }
            return if (session.endpointCandidates.isEmpty()) null else CandidateSelection(
                candidate = null, networkKind = networkKind ?: currentNetworkKind(context),
            )
        }
        val network = networkKind ?: currentNetworkKind(context)
        if (settings.endpointSelectionMode == EndpointSelectionMode.MANUAL &&
            !settings.gamingModeEnabled) {
            val manualCandidates = EndpointGroupPolicy.manualCandidates(candidates, settings)
            onDiagnostic("stage=manual_group; candidates=${manualCandidates.size}")
            if (startupTcpPrecheck != null) {
                val preflight = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
                    candidates = manualCandidates,
                    health = endpointHealth,
                    networkKind = network,
                    nowMillis = System.currentTimeMillis(),
                    rotationIndex = rotationIndex,
                    canReach = startupTcpPrecheck,
                )
                return CandidateSelection(
                    candidate = preflight.selected,
                    precheckFailedEndpointCodes = preflight.failedTcpCodes,
                    precheckShouldPenalizeFailures = preflight.shouldPenalizeFailedTcp,
                    networkKind = network,
                )
            }
            return CandidateSelection(
                EndpointGroupPolicy.selectManualCandidate(
                    candidates = manualCandidates,
                    settings = settings,
                    health = endpointHealth,
                    nowMillis = System.currentTimeMillis(),
                    rotationIndex = rotationIndex,
                )?.candidate,
                networkKind = network,
            )
        }
        if (startupTcpPrecheck != null) {
            val preflight = EndpointStartupPreflightPolicy.selectWithTcpPrecheck(
                candidates = candidates,
                health = endpointHealth,
                networkKind = network,
                nowMillis = System.currentTimeMillis(),
                rotationIndex = rotationIndex,
                canReach = startupTcpPrecheck,
            )
            return CandidateSelection(
                candidate = preflight.selected,
                precheckFailedEndpointCodes = preflight.failedTcpCodes,
                precheckShouldPenalizeFailures = preflight.shouldPenalizeFailedTcp,
                networkKind = network,
            )
        }
        return CandidateSelection(EndpointRankingPolicy.select(
            candidates = candidates,
            health = endpointHealth,
            networkKind = network,
            nowMillis = System.currentTimeMillis(),
            rotationIndex = rotationIndex,
        )?.candidate, networkKind = network)
    }

    internal fun matchesProtocol(candidate: BackendEndpointCandidate, protocol: VpnProtocol): Boolean =
        when (protocol) {
            VpnProtocol.AUTO -> true
            VpnProtocol.NOKINV -> candidate.proxyType.equals("nokinv", ignoreCase = true)
            VpnProtocol.NAIVE -> candidate.proxyType.equals("naive", ignoreCase = true)
            VpnProtocol.TLS -> !candidate.proxyType.equals("nokinv", ignoreCase = true) && candidate.security.equals("tls", ignoreCase = true)
            VpnProtocol.REALITY -> candidate.security.equals("reality", ignoreCase = true)
            VpnProtocol.WIREGUARD -> false
        }

    fun currentNetworkKind(context: Context): EndpointRankingPolicy.NetworkKind {
        return currentUnderlyingNetworkKind(context)
    }

    fun currentUnderlyingNetworkKind(context: Context): EndpointRankingPolicy.NetworkKind {
        return AndroidUnderlyingNetworkSource(context).currentSnapshot()?.kind
            ?: EndpointRankingPolicy.NetworkKind.OTHER
    }

    fun profileFromCandidate(
        session: BackendVpnSession,
        candidate: BackendEndpointCandidate,
    ): VlessProfile {
        session.multiHop?.let { route ->
            val valid = if (route.version == 3) session.entryRoute && candidate.nodeId == route.entryNodeId
                else candidate.nodeId == route.exitNodeId && candidate.entryHost == route.entryIp &&
                    candidate.connectIp == route.entryIp && candidate.entryPort in 20000..29999
            require(valid) { if (route.version == 3) "multihop_entry_mismatch" else "multihop_exit_mismatch" }
        }
        return profileFromCandidate(candidate.copy(proxyType = candidate.proxyType.ifBlank { session.proxyType }),
            session.vpnSecret, session.vpnUsername)
            .copy(remark = "Noki ${candidate.locationName.ifBlank { session.locationName }}", multiHop = session.multiHop, entryRoute = session.entryRoute, entryRouteExpiresAt = session.entryRouteExpiresAt,
                nokinvToken = if (candidate.proxyType == "nokinv") session.nokinvToken else "",
                nokinvCredentialGeneration = if (candidate.proxyType == "nokinv") session.nokinvCredentialGeneration else 0)
    }

    internal fun profileFromCandidate(candidate: BackendEndpointCandidate, vpnSecret: String, vpnUsername: String = ""): VlessProfile =
        VlessProfile(
            remark = "Noki ${candidate.locationName}",
            endpointCode = candidate.code,
            proxyType = candidate.proxyType,
            transport = candidate.normalizedTransport(),
            transportMode = if (candidate.proxyType == "nokinv") candidate.normalizedTransportMode() else candidate.transportMode.orEmpty(),
            host = candidate.connectionHost(),
            port = candidate.entryPort.toString(),
            uuid = vpnSecret,
            vpnUsername = if (candidate.proxyType == "naive") vpnUsername else "",
            flow = candidate.flow.orEmpty(),
            security = candidate.security,
            fingerprint = candidate.fingerprint ?: "chrome",
            serverName = candidate.serverName,
            requestHost = candidate.requestHost.orEmpty(),
            path = candidate.path.orEmpty(),
            alpn = candidate.alpn.orEmpty(),
            allowInsecure = false,
            enableMux = candidate.enableMux,
            randomUserAgent = candidate.randomUserAgent,
            publicKey = candidate.publicKey.orEmpty(),
            shortId = candidate.shortId.orEmpty(),
            spiderX = "/",
        )

    private fun profileFromLegacySession(session: BackendVpnSession): VlessProfile =
        VlessProfile(
            remark = "Noki ${session.locationName}",
            endpointCode = session.endpointCode.orEmpty(),
            proxyType = session.proxyType.ifBlank { "vless" },
            transport = session.transport.ifBlank { "tcp" }.lowercase(),
            transportMode = session.transportMode.orEmpty().ifBlank { if (session.proxyType == "nokinv") "quic" else "" },
            nokinvToken = session.nokinvToken,
            nokinvCredentialGeneration = session.nokinvCredentialGeneration,
            host = session.connectIp.orEmpty().ifBlank { session.entryHost },
            port = session.entryPort.toString(),
            uuid = session.vpnSecret,
            vpnUsername = if (session.proxyType == "naive") session.vpnUsername else "",
            flow = session.flow.orEmpty(),
            security = session.security,
            fingerprint = session.fingerprint ?: "chrome",
            serverName = session.serverName,
            requestHost = session.requestHost.orEmpty(),
            path = session.path.orEmpty(),
            alpn = session.alpn.orEmpty(),
            allowInsecure = false,
            enableMux = session.enableMux,
            randomUserAgent = session.randomUserAgent,
            publicKey = session.publicKey.orEmpty(),
            shortId = session.shortId.orEmpty(),
            spiderX = "/",
        )

    fun optionsFromSession(session: BackendVpnSession): List<VpnEndpointOption> {
        return session.endpointCandidates
            .filter { !it.canaryOnly }
            .filter(EndpointSecurityPolicy::isAllowedCandidate)
            .filter { session.multiHop != null || EndpointTransportPolicy.isDirectCandidate(it) }
            .map { candidate ->
            val option = VpnEndpointOption(
                code = candidate.code,
                nodeId = candidate.nodeId,
                label = candidate.label,
                locationCode = candidate.locationCode,
                locationName = candidate.locationName,
                host = candidate.entryHost,
                port = candidate.entryPort,
                proxyType = candidate.proxyType,
                transport = candidate.normalizedTransport(),
                transportMode = candidate.transportMode.orEmpty(),
                security = candidate.security,
                canaryOnly = candidate.canaryOnly,
                priority = candidate.priority,
            )
            option.copy(endpointGroupKey = EndpointGroupPolicy.groupKey(option))
        }
    }
}
