package com.noki.vpn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.Locale

interface VpnSessionStore {
    fun ensureBackendDeviceKey(existing: String): String
    fun loadEndpointHealth(): Map<String, EndpointHealth>
    fun loadEndpointHealth(networkKind: EndpointRankingPolicy.NetworkKind): Map<String, EndpointHealth> =
        loadEndpointHealth()
    fun nextEndpointRotationIndex(rotationKey: String): Int
}

class VpnSessionCoordinator(
    private val context: Context?,
    private val repository: VpnSessionStore,
    private val backendApi: VpnSessionApi = BackendApiClient(),
    private val deviceNameProvider: () -> String = AndroidDeviceInfo::deviceName,
    private val publicKeyProvider: () -> String = DeviceIdentity::publicKeyBase64,
    private val challengeSigner: (String) -> String = DeviceIdentity::signChallenge,
    private val deviceClaimsProvider: (Context) -> List<String> = DeviceIdentity::deviceClaims,
    private val startupTcpPrecheckTimeoutMs: Int = DEFAULT_STARTUP_TCP_PRECHECK_TIMEOUT_MS,
    private val startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)? = null,
    private val endpointSelectionProvider: ((
        session: BackendVpnSession,
        settings: AdvancedSettings,
        endpointHealth: Map<String, EndpointHealth>,
        rotationIndex: (String) -> Int,
        startupTcpPrecheck: ((BackendEndpointCandidate) -> Boolean)?,
    ) -> EndpointSelector.EndpointSelectionResult)? = null,
    private val onDiagnostic: (String) -> Unit = {},
    private val measureClientLatencies: suspend (List<ServerLocation>) -> Map<String, Int> = { locations ->
        if (context == null) emptyMap() else ClientLatencySampler.startup(context).measure(locations)
    },
) {
    private var sessionApi: VpnSessionApi = backendApi
    data class Result(
        val settings: StoredSettings,
        val session: BackendVpnSession,
        val currentDevice: BackendDevice,
        val selection: EndpointSelector.EndpointSelectionResult,
    )

    data class EndpointOptionsResult(
        val session: BackendVpnSession,
        val endpointOptions: List<VpnEndpointOption>,
        val currentDevice: BackendDevice,
        val backendDeviceKey: String,
        val backendDeviceId: String,
        val backendDeviceAccessRole: String,
    )

    suspend fun prepare(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice> = emptyList(),
        sessionSelection: VpnSessionSelection = VpnSessionSelection(
            countryCode = settings.userProfile.selectedCountryCode,
        ),
    ): Result = withContext(Dispatchers.IO) {
        require(!settings.advancedSettings.ruRelayEnabled) {
            "ru_relay_requires_wireguard_coordinator"
        }
        val network = context?.let { com.noki.vpn.vpn.AndroidUnderlyingNetworkSource(it).currentSnapshot()?.network }
        sessionApi = if (backendApi is BackendApiClient && network != null) backendApi.onNetwork(network) else backendApi
        if (settings.advancedSettings.multiHop.enabled) {
            val route = settings.advancedSettings.multiHop
            for (hop in listOf(route.entry, route.exit)) {
                requireNotNull(hop) { "multihop_selector_missing" }
                when (hop.kind) {
                    ServerSelectionMode.SERVER -> canonicalRuUuid(requireNotNull(hop.nodeId) { "multihop_selector_missing" })
                    ServerSelectionMode.COUNTRY -> require(hop.countryCode?.matches(Regex("[A-Za-z]{2}")) == true) { "multihop_selector_invalid" }
                    ServerSelectionMode.AUTO -> throw IllegalArgumentException("multihop_selector_invalid")
                }
            }
            if (route.entry?.kind == ServerSelectionMode.SERVER && route.exit?.kind == ServerSelectionMode.SERVER) route.explicitNodeIds()
            val catalog = backendStep("server_catalog") {
                sessionApi.serverLocations(token, settings.backendDeviceId.ifBlank { null }, settings.backendDeviceKey.ifBlank { null })
            }
            val locations = BootstrapStateMapper.mapLocations(catalog, settings.personalizationSettings.language, emptyMap())
            val nodes = locations.flatMap { it.servers }.filter { it.isOnline && it.weight > 0 && it.id !in sessionSelection.excludedNodeIds }
            fun matches(node: VpnServer, selection: HopSelection?): Boolean = when (selection?.kind) {
                ServerSelectionMode.SERVER -> node.id == selection.nodeId
                ServerSelectionMode.COUNTRY -> node.countryCode.equals(selection.countryCode, true)
                else -> false
            }
            val exits = nodes.filter { it.multiHopExitAvailable && matches(it, route.exit) }
            val entries = nodes.filter { it.multiHopEntryAvailable && matches(it, route.entry) && exits.any { exit -> exit.id != it.id } }
            val automaticNodes = ((if (route.entry?.kind == ServerSelectionMode.COUNTRY) entries else emptyList()) +
                (if (route.exit?.kind == ServerSelectionMode.COUNTRY) exits else emptyList())).distinctBy { it.id }
            val samples = if (automaticNodes.size < 2) emptyMap() else {
                val ids = automaticNodes.map { it.id }.toSet()
                measureClientLatencies(locations.mapNotNull { location ->
                    location.servers.filter { it.id in ids }.takeIf { it.isNotEmpty() }?.let { location.copy(servers = it) }
                })
            }
            fun measured(nodes: List<VpnServer>) = nodes.map { it.copy(latencyMs = clientLatencyTargetKey(it)?.let(samples::get)) }
            val automatic = UserProfile(serverSelectionMode = ServerSelectionMode.AUTO)
            val entry = EndpointRankingPolicy.rankServers(measured(entries), automatic).firstOrNull()
                ?: throw BackendException("entry_route_unavailable", 503)
            val exit = EndpointRankingPolicy.rankServers(measured(exits.filter { it.id != entry.id }), automatic).first()
            val selected = route.copy(entry = HopSelection(ServerSelectionMode.SERVER, nodeId = entry.id),
                exit = HopSelection(ServerSelectionMode.SERVER, nodeId = exit.id))
            val prepared = prepareAtNode(token, settings, knownDevices, VpnSessionSelection(countryCode = "", nodeId = entry.id,
                excludeNokiNv = true), selected)
            if (!VpnProfileValidator.isUsable(prepared.settings)) throw UnusableFreshProfileException()
            return@withContext prepared.copy(settings = prepared.settings.copy(
                advancedSettings = prepared.settings.advancedSettings.copy(multiHop = route),
                userProfile = prepared.settings.userProfile.copy(selectedServerCode = entry.locationCode, actualCountryCode = exit.countryCode),
            ))
        }
        val explicitNode = sessionSelection.nodeId ?: settings.userProfile.selectedNodeId.takeIf {
            settings.userProfile.serverSelectionMode == ServerSelectionMode.SERVER && it.isNotBlank()
        }
        if (explicitNode != null && explicitNode !in sessionSelection.excludedNodeIds) {
            try {
                val prepared = prepareAtNode(token, settings, knownDevices, sessionSelection.copy(countryCode = "", nodeId = explicitNode))
                if (!VpnProfileValidator.isUsable(prepared.settings)) throw UnusableFreshProfileException()
                return@withContext prepared
            } catch (error: BackendException) {
                if (error.statusCode !in setOf(404, 503)) throw error
            }
        }
        val catalog = backendStep("server_catalog") {
            sessionApi.serverLocations(token, settings.backendDeviceId.ifBlank { null }, settings.backendDeviceKey.ifBlank { null })
        }
        val locations = BootstrapStateMapper.mapLocations(catalog, settings.personalizationSettings.language, emptyMap())
        val scope = settings.userProfile.let { profile ->
            when {
                sessionSelection.nodeId != null -> profile.copy(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = sessionSelection.nodeId)
                profile.serverSelectionMode == ServerSelectionMode.COUNTRY -> profile.copy(selectedCountryCode = sessionSelection.countryCode)
                else -> profile
            }
        }
        val visibleNodes = locations.flatMap { it.servers }.filter {
            it.id !in sessionSelection.excludedNodeIds && it.locationCode != sessionSelection.excludeLocationCode
        }
        val scopedNodes = visibleNodes.filter {
            sessionSelection.locationCode == null || it.locationCode == sessionSelection.locationCode
        }
        val eligibleNodes = EndpointRankingPolicy.rankServers(scopedNodes, scope)
        if (eligibleNodes.isEmpty()) throw BackendException("No available servers", 503)
        val samples = if (eligibleNodes.size == 1) emptyMap() else {
            val eligibleIds = eligibleNodes.map { it.id }.toSet()
            measureClientLatencies(locations.mapNotNull { location ->
                val servers = location.servers.filter { it.id in eligibleIds }
                if (servers.isEmpty()) null else location.copy(servers = servers)
            })
        }
        val nodes = if (samples.isEmpty()) eligibleNodes else EndpointRankingPolicy.rankServers(
            eligibleNodes.map { node -> node.copy(latencyMs = clientLatencyTargetKey(node)?.let(samples::get)) },
            scope,
        )
        var lastFailure: Exception = BackendException("No available servers", 503)
        for (node in nodes) {
            try {
                val result = prepareAtNode(token, settings, knownDevices, sessionSelection.copy(
                    countryCode = node.countryCode, locationCode = node.locationCode, nodeId = node.id,
                ))
                if (!VpnProfileValidator.isUsable(result.settings) && node != nodes.last()) continue
                return@withContext result.copy(settings = result.settings.copy(userProfile = result.settings.userProfile.copy(
                    actualCountryCode = node.countryCode,
                )))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: BackendException) {
                if (error.statusCode !in setOf(404, 503)) throw error
                lastFailure = error
            } catch (error: UnusableFreshProfileException) {
                lastFailure = error
            }
        }
        throw lastFailure
    }

    private suspend fun prepareAtNode(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        sessionSelection: VpnSessionSelection,
        localRoute: MultiHopSettings? = null,
    ): Result = withContext(Dispatchers.IO) {
        val deviceSession = createDeviceSession(
            token = token,
            settings = settings,
            knownDevices = knownDevices,
            profileCode = if (settings.advancedSettings.multiHop.enabled) "auto"
                else settings.advancedSettings.effectiveProtocol.name.lowercase(Locale.ROOT),
            sessionSelection = sessionSelection,
        )

        val session = if (localRoute != null) {
            if (!deviceSession.session.entryRoute) throw BackendException("entry_route_unavailable", 503)
            deviceSession.session.copy(multiHop = BackendMultiHopSession.atEntry(localRoute))
        } else deviceSession.session
        val networkKind = context?.let(EndpointSelector::currentNetworkKind)
            ?: EndpointRankingPolicy.NetworkKind.OTHER
        val multiHop = settings.advancedSettings.multiHop.enabled
        val selectionSession = if (sessionSelection.excludeNokiNv || session.entryRoute) session.copy(
            endpointCandidates = session.endpointCandidates.filterNot { it.proxyType.equals("nokinv", true) },
        ).also { if (it.endpointCandidates.isEmpty()) throw UnusableFreshProfileException() } else session
        val endpointHealth = if (multiHop) emptyMap() else repository.loadEndpointHealth(networkKind)
        val tcpPrecheck = if (multiHop) null else startupTcpPrecheck ?: ::defaultStartupTcpPrecheck
        val selection = endpointSelectionProvider?.invoke(
            selectionSession,
            settings.advancedSettings.copy(multiHop = localRoute ?: settings.advancedSettings.multiHop),
            endpointHealth,
            repository::nextEndpointRotationIndex,
            tcpPrecheck,
        ) ?: EndpointSelector.selectionForSession(
            context = requireContext(),
            session = selectionSession,
            settings = settings.advancedSettings.copy(multiHop = localRoute ?: settings.advancedSettings.multiHop),
            endpointHealth = endpointHealth,
            rotationIndex = repository::nextEndpointRotationIndex,
            startupTcpPrecheck = tcpPrecheck,
            networkKind = networkKind,
            onDiagnostic = onDiagnostic,
        )
        val endpointOptions = EndpointSelector.optionsFromSession(selectionSession)
        val advancedSettings = EndpointGroupPolicy.settingsAfterSelection(
            settings = settings.advancedSettings.copy(multiHop = localRoute ?: settings.advancedSettings.multiHop),
            endpointCode = selection.endpointCode,
            endpointOptions = endpointOptions,
        )
        val updatedSettings = settings.copy(
            profile = selection.profile,
            advancedSettings = advancedSettings,
            endpointOptions = endpointOptions,
            userProfile = settings.userProfile.copy(
                selectedPlanCode = PlanCode.fromBackend(deviceSession.session.planCode, settings.userProfile.selectedPlanCode),
                selectedPlanCodeRaw = deviceSession.session.planCode?.ifBlank { null }
                    ?: settings.userProfile.selectedPlanCodeRaw,
                selectedServerCode = deviceSession.session.locationCode,
            ),
            backendDeviceKey = deviceSession.currentDevice.deviceKey,
            backendDeviceId = deviceSession.currentDevice.id,
            backendDeviceAccessRole = deviceSession.accessRole,
        )
        onDiagnostic("stage=validation; reason=${VpnProfileValidator.rejectionReason(updatedSettings) ?: "ok"}; endpoint=${selection.endpointCode}; candidates=${endpointOptions.size}")
        Result(
            settings = updatedSettings,
            session = selectionSession,
            currentDevice = deviceSession.currentDevice,
            selection = selection,
        )
    }

    suspend fun endpointOptions(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice> = emptyList(),
    ): EndpointOptionsResult = withContext(Dispatchers.IO) {
        val prepared = prepare(
            token = token,
            settings = settings.copy(
                advancedSettings = settings.advancedSettings.copy(protocol = VpnProtocol.AUTO,
                    endpointSelectionMode = EndpointSelectionMode.AUTO, manualEndpointCode = "", manualEndpointGroupKey = ""),
            ),
            knownDevices = knownDevices,
            sessionSelection = VpnSessionSelection(
                countryCode = settings.userProfile.selectedCountryCode,
            ),
        )
        EndpointOptionsResult(
            session = prepared.session,
            endpointOptions = EndpointSelector.optionsFromSession(prepared.session),
            currentDevice = prepared.currentDevice,
            backendDeviceKey = prepared.currentDevice.deviceKey,
            backendDeviceId = prepared.currentDevice.id,
            backendDeviceAccessRole = prepared.settings.backendDeviceAccessRole,
        )
    }

    private data class DeviceSession(
        val session: BackendVpnSession,
        val currentDevice: BackendDevice,
        val accessRole: String,
    )

    private suspend fun createDeviceSession(
        token: String,
        settings: StoredSettings,
        knownDevices: List<BackendDevice>,
        profileCode: String,
        sessionSelection: VpnSessionSelection,
    ): DeviceSession {
        val safeDeviceKey = repository.ensureBackendDeviceKey(settings.backendDeviceKey)
        var deviceId = settings.backendDeviceId
        var accessRole = settings.backendDeviceAccessRole.ifBlank { "owner" }

        var currentDevice = if (deviceId.isNotBlank() && safeDeviceKey.isNotBlank()) {
            knownDevices.firstOrNull { device ->
                device.id == deviceId || device.deviceKey == safeDeviceKey
            } ?: BackendDevice(
                id = deviceId,
                deviceKey = safeDeviceKey,
                deviceName = deviceNameProvider(),
                platform = "android",
                accessRole = accessRole,
                isActive = true,
                lastSeenAt = null,
            )
        } else {
            registerDevice(token, safeDeviceKey, deviceId.ifBlank { null })
        }

        val session = DeviceIdentity.withChallengeTransaction {
            val challenge = try {
                backendStep("device_challenge") { sessionApi.createDeviceChallenge(token, currentDevice.id) }
            } catch (error: BackendException) {
                if (error.statusCode !in RECOVERABLE_DEVICE_STATUS_CODES) throw error
                currentDevice = registerDevice(token, safeDeviceKey, null)
                backendStep("device_challenge") { sessionApi.createDeviceChallenge(token, currentDevice.id) }
            }
            deviceId = currentDevice.id
            accessRole = currentDevice.accessRole.ifBlank { accessRole }

            val signature = backendStep("challenge_sign") { challengeSigner(challenge.nonce) }
            backendStep("session_create") { sessionApi.createVpnSession(
                token = token,
                deviceId = deviceId,
                deviceKey = currentDevice.deviceKey,
                deviceNonce = challenge.nonce,
                deviceSignature = signature,
                countryCode = sessionSelection.countryCode.takeIf { it.isNotBlank() && !settings.advancedSettings.multiHop.enabled },
                locationCode = sessionSelection.locationCode.takeUnless { settings.advancedSettings.multiHop.enabled },
                excludeLocationCode = sessionSelection.excludeLocationCode.takeUnless { settings.advancedSettings.multiHop.enabled },
                profileCode = profileCode,
                nodeId = sessionSelection.nodeId,
                multiHop = null,
            ) }
        }
        if (!session.canConnect) {
            throw BackendException("vpn_access_denied", 403)
        }
        return DeviceSession(
            session = session,
            currentDevice = currentDevice,
            accessRole = accessRole,
        )
    }

    private suspend fun registerDevice(
        token: String,
        deviceKey: String,
        deviceId: String?,
    ): BackendDevice {
        return backendStep("device_register") { sessionApi.registerDevice(
            token = token,
            deviceKey = deviceKey,
            deviceId = deviceId,
            deviceName = deviceNameProvider(),
            publicKey = publicKeyProvider(),
            deviceClaims = deviceClaimsProvider(requireContext()),
            platform = "android",
        ) }
    }

    private suspend fun <T> backendStep(stage: String, block: suspend () -> T): T {
        val started = System.nanoTime()
        onDiagnostic("stage=$stage; result=start")
        return try {
            block().also { onDiagnostic("stage=$stage; result=ok; duration_ms=${(System.nanoTime() - started) / 1_000_000}") }
        } catch (error: BackendException) {
            val reason = if (error.message.equals("Device challenge expired", ignoreCase = true)) {
                "challenge_expired"
            } else "backend_error"
            onDiagnostic("stage=$stage; result=error; http_status=${error.statusCode}; reason=$reason")
            throw error
        } catch (error: CancellationException) {
            onDiagnostic("stage=$stage; result=cancelled_or_timeout")
            throw error
        } catch (error: Exception) {
            onDiagnostic("stage=$stage; result=error; reason=transport_or_local_error")
            throw error
        }
    }

    private fun requireContext(): Context {
        return checkNotNull(context) { "Android context is required for this VPN session operation" }
    }

    private fun defaultStartupTcpPrecheck(candidate: BackendEndpointCandidate): Boolean {
        val network = context?.let { com.noki.vpn.vpn.AndroidUnderlyingNetworkSource(it).currentSnapshot()?.network }
        return DeviceLatency.measureTcpConnectMs(
            rawHost = candidate.connectionHost(),
            port = candidate.entryPort,
            timeoutMs = startupTcpPrecheckTimeoutMs,
            onDiagnostic = { result ->
                onDiagnostic("stage=tcp_precheck; endpoint=${candidate.code}; target=${candidate.connectionHost().take(255)}; port=${candidate.entryPort}; timeout_ms=$startupTcpPrecheckTimeoutMs; $result")
            },
            socketFactory = { network?.socketFactory?.createSocket() ?: java.net.Socket() },
            lookup = { host -> network?.getAllByName(host) ?: java.net.InetAddress.getAllByName(host) },
        ) != null
    }

    private companion object {
        private const val DEFAULT_STARTUP_TCP_PRECHECK_TIMEOUT_MS = 800
        private val RECOVERABLE_DEVICE_STATUS_CODES = setOf(400, 403, 404, 409)
    }
}
