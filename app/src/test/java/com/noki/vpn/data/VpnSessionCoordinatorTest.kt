package com.noki.vpn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VpnSessionCoordinatorTest {
    @Test fun `manual Romania Naive prepares a fresh Naive session with TLS and its own credentials`() = runBlocking {
        val endpoint = org.json.JSONObject().put("code", "ro-naive").put("node_id", "node-ro")
            .put("entry_host", "ro.example.test").put("entry_port", 9443).put("server_name", "ro.example.test")
            .put("location_code", "ro-1").put("location_name", "Romania")
            .put("proxy_type", "naive").put("transport", "naive").put("security", "tls")
        val payload = contractFixture("multihop_session_v2.json").apply {
            remove("multihop")
            put("route_mode", "direct").put("location_code", "ro-1").put("location_name", "Romania")
            put("profile_code", "naive").put("endpoint_code", "ro-naive").put("entry_host", "ro.example.test")
            put("connect_ip", "ro.example.test").put("entry_port", 9443).put("server_name", "ro.example.test")
            put("proxy_type", "naive").put("transport", "naive").put("transport_mode", org.json.JSONObject.NULL)
            put("security", "tls").put("vpn_username", "fixture-user").put("vpn_secret", "fixture-password")
            put("public_key", org.json.JSONObject.NULL).put("short_id", org.json.JSONObject.NULL).put("flow", "")
            put("endpoint_candidates", org.json.JSONArray().put(endpoint))
        }
        val session = BackendVpnSessionJsonParser.parse(payload)
        val option = EndpointGroupPolicy.manualOptions(EndpointSelector.optionsFromSession(session)).single()
        val advanced = AdvancedSettings(ruRelayEnabled = false,
            protocol = VpnProtocol.fromBackendCode(option.security, option.proxyType),
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointCode = option.code,
            manualEndpointGroupKey = EndpointGroupPolicy.groupKey(option))
        val settings = DefaultStoredSettingsFactory.create().copy(backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = advanced, userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER,
                selectedNodeId = "node-ro", selectedCountryCode = "RO"))
        val api = FakeVpnSessionApi(pairSession = session)
        val coordinator = VpnSessionCoordinator(context = RuntimeEnvironment.getApplication(),
            repository = FakeVpnSessionStore(), backendApi = api, challengeSigner = { "signature" }, startupTcpPrecheck = { true })
        val start = VpnStartCoordinator(VpnStartCoordinator.VpnSessionPreparer { token, stored, devices, selection ->
            coordinator.prepare(token, stored, devices, selection)
        })
        val decision = start.resolveStart("token", settings, emptyList(), allowCachedFallback = false)
        assertTrue("Fresh preparation failed: $decision", decision is VpnStartCoordinator.StartDecision.FreshSession)
        val prepared = (decision as VpnStartCoordinator.StartDecision.FreshSession).result.settings
        assertEquals("naive", api.requestedProfileCode)
        assertNull(VpnProfileValidator.rejectionReason(prepared))
        assertEquals(VpnProtocol.NAIVE, prepared.advancedSettings.protocol)
        assertEquals("tls|naive|", prepared.advancedSettings.manualEndpointGroupKey)
        assertEquals("fixture-user", prepared.profile.vpnUsername)
        assertEquals("fixture-password", prepared.profile.uuid)
        val outbound = org.json.JSONObject(com.noki.vpn.vpn.VpnConfigFactory.buildProbe(prepared.profile))
            .getJSONArray("outbounds").getJSONObject(0)
        assertEquals("naive", outbound.getString("type"))
        assertEquals(9443, outbound.getInt("server_port"))
    }
    @Test
    fun `MultiHop Auto uses the ordinary entry and excludes NokiNV from the local route`() = runBlocking {
        val legacy = BackendVpnSessionJsonParser.parse(contractFixture("multihop_session_v2.json"))
        val route = checkNotNull(legacy.multiHop)
        val entry = VpnServer(route.entryNodeId, "Entry", "DE", "de-2", "entry.example", null, true, multiHopEntryAvailable = true)
        val exit = VpnServer(route.exitNodeId, "Exit", "LV", "lv2", "exit.example", null, true, multiHopExitAvailable = true)
        val vless = legacy.endpointCandidates.first { it.transport == "xhttp" }.copy(
            nodeId = entry.id, entryHost = entry.host, connectIp = "203.0.113.10", entryPort = 443,
            locationCode = entry.locationCode,
        )
        val nokinv = vless.copy(code = "entry-nokinv", proxyType = "nokinv", security = "tls", transport = "nokinv",
            transportMode = null, publicKey = null, shortId = null, flow = null)
        val session = legacy.copy(multiHop = null, entryRoute = true, locationCode = entry.locationCode,
            endpointCandidates = listOf(vless, nokinv),
            nokinvToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 5 }),
            nokinvCredentialGeneration = 1)
        val api = FakeVpnSessionApi(pairSession = session, extraServers = listOf(entry, exit))
        val coordinator = VpnSessionCoordinator(context = RuntimeEnvironment.getApplication(),
            repository = FakeVpnSessionStore(), backendApi = api, challengeSigner = { "signature" })
        val advanced = AdvancedSettings(ruRelayEnabled = false, multiHop = route.selection)
        val settings = DefaultStoredSettingsFactory.create().copy(backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = advanced)
        val result = coordinator.prepare("token", settings)
        assertNull(VpnProfileValidator.rejectionReason(result.settings))
        assertEquals(entry.id, api.nodeId)
        assertNull(api.multiHopRequest)
        assertEquals(listOf(vless.code), result.session.endpointCandidates.map { it.code })
        assertEquals(exit.id, result.settings.profile.entryRouteExit())
        assertEquals("", result.settings.profile.nokinvToken)
        val config = org.json.JSONObject(com.noki.vpn.vpn.VpnConfigFactory.build(result.settings.profile, advanced,
            com.noki.vpn.vpn.GeositeAssetTest.snapshot("CATEGORY-RU" to listOf(
                com.noki.vpn.vpn.GeositeAssetTest.domain(2, "russian.example")))))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("203.0.113.10", outbound.getString("server"))
        assertEquals(443, outbound.getInt("server_port"))
        val unsupported = advanced.copy(protocol = VpnProtocol.NOKINV, endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = EndpointGroupPolicy.groupKey(nokinv))
        assertTrue(runCatching { coordinator.prepare("token", settings.copy(advancedSettings = unsupported)) }.isFailure)
    }

    @Test
    fun `session admission still rejects a denied account without access preflight`() = runBlocking {
        val api = FakeVpnSessionApi(sessionError = BackendException("subscription_inactive", 403))
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
            challengeSigner = { "signature" },
            startupTcpPrecheck = { error("Denied sessions cannot probe transports") },
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = "node-de-2"),
        )
        val error = runCatching { coordinator.prepare("token", settings) }.exceptionOrNull()
        assertTrue(error is BackendException && error.statusCode == 403)
        assertEquals(0, api.accessRequests)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `single eligible node starts without waiting for catalog latency`() = runBlocking {
        val api = FakeVpnSessionApi()
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
            deviceNameProvider = { "Phone" }, challengeSigner = { "signature" }, startupTcpPrecheck = { true },
            measureClientLatencies = { error("A single eligible node needs no latency ranking") },
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = "node-de-2"),
        )
        coordinator.prepare("token", settings)
        assertEquals("node-de-2", api.nodeId)
        assertNull(api.countryCode)
        assertEquals(0, api.catalogRequests)
        assertEquals(0, api.accessRequests)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `latency ranking measures only permitted country candidates`() = runBlocking {
        val api = FakeVpnSessionApi(extraServers = listOf(
            VpnServer("fast", "Fast", "DE", "de-2", "fast.example", 443, true),
            VpnServer("excluded", "Excluded", "DE", "de-2", "excluded.example", 443, true),
            VpnServer("offline", "Offline", "DE", "de-2", "offline.example", 443, false),
            VpnServer("disabled", "Disabled", "DE", "de-2", "disabled.example", 443, true, weight = 0),
        ))
        val measured = mutableListOf<String>()
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
            deviceNameProvider = { "Phone" }, challengeSigner = { "signature" }, startupTcpPrecheck = { true },
            measureClientLatencies = { locations ->
                val servers = locations.flatMap { it.servers }
                measured += servers.map { it.id }
                servers.associate { checkNotNull(clientLatencyTargetKey(it)) to if (it.id == "fast") 10 else 200 }
            },
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = "DE"),
        )
        coordinator.prepare("token", settings, sessionSelection = VpnSessionSelection(
            countryCode = "DE", excludedNodeIds = setOf("excluded"),
        ))
        assertEquals(setOf("node-de-2", "fast"), measured.toSet())
        assertEquals("fast", api.requestedNodeIds.first())
        assertEquals(0, api.accessRequests)
    }

    @Test
    fun `hidden saved server never falls back to auto`() = runBlocking {
        val api = FakeVpnSessionApi()
        val measured = mutableListOf<String>()
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
            deviceNameProvider = { "Phone" }, challengeSigner = { "signature" }, startupTcpPrecheck = { true },
            measureClientLatencies = { locations ->
                val servers = locations.flatMap { it.servers }
                measured += servers.map { it.id }
                servers.associate { checkNotNull(clientLatencyTargetKey(it)) to if (it.id == "node-lv2") 10 else 200 }
            },
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = "hidden-node"),
        )
        val error = runCatching { coordinator.prepare("token", settings) }.exceptionOrNull()
        assertTrue(error is BackendException && error.statusCode == 503)
        assertTrue(measured.isEmpty())
        assertEquals(listOf("hidden-node"), api.requestedNodeIds)
        assertEquals(ServerSelectionMode.SERVER, settings.userProfile.serverSelectionMode)
        assertEquals("hidden-node", settings.userProfile.selectedNodeId)
    }

    @Test
    fun `unavailable country or excluded country location never selects another country`() = runBlocking {
        for ((country, excludedLocation) in listOf("FI" to null, "DE" to "de-2")) {
            val api = FakeVpnSessionApi()
            val settings = DefaultStoredSettingsFactory.create().copy(
                backendDeviceId = "device-id", backendDeviceKey = "device-key",
                advancedSettings = AdvancedSettings(ruRelayEnabled = false),
                userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.COUNTRY, selectedCountryCode = country),
            )
            val coordinator = VpnSessionCoordinator(
                context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
                challengeSigner = { "signature" }, measureClientLatencies = { error("No permitted nodes to measure") },
            )
            val error = runCatching { coordinator.prepare("token", settings,
                sessionSelection = VpnSessionSelection(countryCode = country, excludeLocationCode = excludedLocation)) }.exceptionOrNull()
            assertTrue(error is BackendException && error.statusCode == 503)
            assertTrue(api.requestedNodeIds.isEmpty())
            assertEquals(country, settings.userProfile.selectedCountryCode)
        }
    }

    @Test
    fun `ordinary coordinator refuses invalid pair and RU before any backend preparation`() = runBlocking {
        val selection = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.COUNTRY, countryCode = "RU"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "exit-node"))
        val api = FakeVpnSessionApi(sessionError = BackendException("route pending", 409))
        var prechecks = 0
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
            deviceNameProvider = { "Phone" }, challengeSigner = { "signature" },
            startupTcpPrecheck = { prechecks++; true },
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(multiHop = selection),
        )
        assertTrue(runCatching { coordinator.prepare("token", settings) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { coordinator.prepare("token", settings.copy(advancedSettings =
            AdvancedSettings(ruRelayEnabled = true))) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, api.catalogRequests)
        assertEquals(0, api.accessRequests)
        assertEquals(0, prechecks)
        assertNull(api.multiHopRequest)
        assertNull(api.countryCode)
        assertEquals(0, api.requests.size)
        Unit
    }

    @Test fun `persisted country MultiHop samples only allowed country nodes in one batch and preserves the explicit exit`() = runBlocking {
        val slow = "11111111-1111-4111-8111-111111111111"
        val fast = "22222222-2222-4222-8222-222222222222"
        val exit = "33333333-3333-4333-8333-333333333333"
        val route = MultiHopSettings(true, HopSelection(ServerSelectionMode.COUNTRY, countryCode = "DE"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = exit))
        val api = FakeVpnSessionApi(sessionError = BackendException("pending", 503), extraServers = listOf(
            VpnServer(slow, "Slow", "DE", "de-2", "slow.example", 25001, true, multiHopEntryAvailable = true),
            VpnServer(fast, "Fast", "DE", "de-2", "fast.example", 25002, true, multiHopEntryAvailable = true),
            VpnServer(exit, "Exit", "LV", "lv2", "exit.example", null, true, multiHopExitAvailable = true),
            VpnServer("44444444-4444-4444-8444-444444444444", "Foreign", "FR", "lv2", "foreign.example", 25003, true, multiHopEntryAvailable = true)))
        var batches = 0
        val coordinator = VpnSessionCoordinator(context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(),
            backendApi = api, challengeSigner = { "signature" }, measureClientLatencies = { locations ->
                batches++
                val nodes = locations.flatMap { it.servers }
                assertEquals(setOf(slow, fast), nodes.map { it.id }.toSet())
                nodes.associate { checkNotNull(clientLatencyTargetKey(it)) to if (it.id == fast) 20 else 180 }
            })
        val settings = DefaultStoredSettingsFactory.create().copy(backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(ruRelayEnabled = false, multiHop = route))
        assertTrue(runCatching { coordinator.prepare("token", settings) }.exceptionOrNull() is BackendException)
        assertEquals(1, batches)
        assertEquals(fast, api.nodeId)
        assertEquals(route, settings.advancedSettings.multiHop)
    }

    @Test
    fun `ordinary MultiHop provisions the selected entry without sending an exit to the backend`() = runBlocking {
        val selection = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "00000000-0000-0000-0000-000000000001"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "00000000-0000-0000-0000-000000000002"))
        val entryId = checkNotNull(selection.entry?.nodeId)
        val exitId = checkNotNull(selection.exit?.nodeId)
        val api = FakeVpnSessionApi(sessionError = BackendException("route pending", 503),
            extraServers = listOf(
                VpnServer(entryId, "Entry", "DE", "de-2", "entry.example", null, true, multiHopEntryAvailable = true),
                VpnServer(exitId, "Exit", "LV", "lv2", "exit.example", null, true, multiHopExitAvailable = true),
            ))
        var prechecks = 0
        val coordinator = VpnSessionCoordinator(context = RuntimeEnvironment.getApplication(),
            repository = FakeVpnSessionStore(), backendApi = api,
            challengeSigner = { "signature" }, startupTcpPrecheck = { prechecks++; true })
        val settings = DefaultStoredSettingsFactory.create().copy(backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(ruRelayEnabled = false, multiHop = selection, protocol = VpnProtocol.TLS))
        assertTrue(runCatching { coordinator.prepare("token", settings) }.exceptionOrNull() is BackendException)
        assertEquals(2, api.catalogRequests)
        assertEquals(0, prechecks)
        assertNull(api.multiHopRequest)
        assertNull(api.countryCode)
        assertNull(api.locationCode)
        assertEquals(entryId, api.nodeId)
        assertEquals(1, api.requests.size)
        Unit
    }

    @Test
    fun `no RU pair honors manual protocol and never falls back to a different transport`() = runBlocking {
        val legacy = BackendVpnSessionJsonParser.parse(contractFixture("multihop_session_v2.json"))
        val route = checkNotNull(legacy.multiHop)
        val entry = VpnServer(route.entryNodeId, "Entry", "DE", "de-2", "entry.example", null, true, multiHopEntryAvailable = true)
        val exit = VpnServer(route.exitNodeId, "Exit", "LV", "lv2", "exit.example", null, true, multiHopExitAvailable = true)
        val session = legacy.copy(multiHop = null, entryRoute = true, locationCode = entry.locationCode,
            endpointCandidates = legacy.endpointCandidates.map {
                it.copy(nodeId = entry.id, entryHost = "entry.example", connectIp = "203.0.113.10", entryPort = 443, locationCode = entry.locationCode)
            })
        for (candidate in session.endpointCandidates.filter { it.transport == "xhttp" || it.proxyType == "hysteria" }) {
            val protocol = VpnProtocol.fromBackendCode(candidate.security, candidate.proxyType)
            val api = FakeVpnSessionApi(pairSession = session, extraServers = listOf(entry, exit))
            val coordinator = VpnSessionCoordinator(context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(),
                backendApi = api, challengeSigner = { "signature" })
            val settings = DefaultStoredSettingsFactory.create().copy(backendDeviceId = "device-id", backendDeviceKey = "device-key",
                advancedSettings = AdvancedSettings(ruRelayEnabled = false, multiHop = route.selection,
                    protocol = protocol, endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointCode = "old-direct",
                    manualEndpointGroupKey = EndpointGroupPolicy.groupKey(candidate)))
            val result = coordinator.prepare("token", settings)
            assertEquals(candidate.code, result.settings.profile.endpointCode)
            assertTrue(VpnProfileValidator.isUsable(result.settings.profile, result.settings.advancedSettings))
            assertEquals(route.exitNodeId, result.settings.profile.entryRouteExit())
            assertEquals("443", result.settings.profile.port)
            assertEquals(settings.advancedSettings.manualEndpointGroupKey, result.settings.advancedSettings.manualEndpointGroupKey)
            assertEquals(candidate.code, result.settings.advancedSettings.manualEndpointCode)
            assertEquals(protocol, result.settings.advancedSettings.effectiveProtocol)
            assertEquals(protocol, result.settings.advancedSettings.copy(multiHop = MultiHopSettings()).effectiveProtocol)
            assertEquals(VpnProtocol.WIREGUARD, result.settings.advancedSettings.copy(ruRelayEnabled = true).effectiveProtocol)
            assertEquals(2, api.catalogRequests)
            val unsupported = settings.copy(advancedSettings = settings.advancedSettings.copy(protocol = VpnProtocol.WIREGUARD))
            assertTrue(runCatching { coordinator.prepare("token", unsupported) }.isFailure)
            val absent = settings.copy(advancedSettings = settings.advancedSettings.copy(manualEndpointGroupKey = "tls|nokinv|tcp"))
            assertTrue(runCatching { coordinator.prepare("token", absent) }.isFailure)
        }
        Unit
    }

    @Test
    fun `manual catalog requests all protocols without changing selected settings`() = runBlocking {
        val candidates = listOf(
            tcpCandidate("reality").copy(transport = "xhttp", transportMode = "stream-up", flow = null),
            tcpCandidate("nokinv").copy(proxyType = "nokinv", transport = "nokinv", security = "tls",
                publicKey = null, shortId = null, flow = null),
            hysteriaCandidate("hy2"),
        )
        val api = FakeVpnSessionApi(candidates = candidates)
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
            deviceNameProvider = { "Phone" }, challengeSigner = { "signature" }, startupTcpPrecheck = { true },
        )
        for (protocol in listOf(VpnProtocol.REALITY, VpnProtocol.TLS, VpnProtocol.NOKINV)) {
            val settings = DefaultStoredSettingsFactory.create().copy(
                backendDeviceId = "device-id", backendDeviceKey = "device-key",
                advancedSettings = AdvancedSettings(protocol = protocol, endpointSelectionMode = EndpointSelectionMode.MANUAL,
                    manualEndpointCode = "another-node", manualEndpointGroupKey = "lv-other|tls|nokinv||100"),
            )
            val result = coordinator.endpointOptions("token", settings)
            assertEquals("auto", api.requestedProfileCode)
            assertEquals(setOf("reality", "nokinv", "hy2"), result.endpointOptions.map { it.code }.toSet())
            assertEquals(protocol, settings.advancedSettings.protocol)
        }
    }

    @Test
    fun `recovery excludes NokiNV while retaining saved Auto selection`() = runBlocking {
        val candidates = listOf(
            tcpCandidate("nokinv").copy(proxyType = "nokinv", transport = "nokinv", security = "tls",
                publicKey = null, shortId = null, flow = null),
            hysteriaCandidate("hy2"),
        ).map { it.copy(nodeId = "node-de-2", locationCode = "de-2", locationName = "Germany") }
        val api = FakeVpnSessionApi(candidates = candidates)
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(), repository = FakeVpnSessionStore(), backendApi = api,
            challengeSigner = { "signature" }, startupTcpPrecheck = { error("Hy2 must not probe TCP") },
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id", backendDeviceKey = "device-key",
            advancedSettings = AdvancedSettings(protocol = VpnProtocol.AUTO),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = "node-de-2"),
        )

        val result = coordinator.prepare("token", settings,
            sessionSelection = VpnSessionSelection(countryCode = "DE", nodeId = "node-de-2", excludeNokiNv = true))

        assertEquals("hy2", result.settings.profile.endpointCode)
        assertEquals("hysteria", result.settings.profile.proxyType)
        assertTrue(VpnProfileValidator.isUsable(result.settings))
        assertEquals(settings.advancedSettings, result.settings.advancedSettings)
        assertEquals("auto", api.requestedProfileCode)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `failed TCP selection reports filters and empty profile before validation`() = runBlocking {
        val events = mutableListOf<String>()
        val candidates = listOf(
            tcpCandidate("eligible").copy(transport = "xhttp", transportMode = "stream-up", flow = null),
            tcpCandidate("no-host").copy(transport = "xhttp", transportMode = "stream-up", flow = null, entryHost = ""),
            tcpCandidate("insecure").copy(transport = "xhttp", transportMode = "stream-up", flow = null, allowInsecure = true),
            tcpCandidate("canary").copy(transport = "xhttp", transportMode = "stream-up", flow = null, canaryOnly = true),
        )
        val coordinator = VpnSessionCoordinator(
            context = RuntimeEnvironment.getApplication(),
            repository = FakeVpnSessionStore(),
            backendApi = FakeVpnSessionApi(candidates = candidates),
            deviceNameProvider = { "Phone" },
            challengeSigner = { "sensitive-signature" },
            startupTcpPrecheck = { false },
            onDiagnostic = events::add,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id",
            backendDeviceKey = "sensitive-device-key",
            advancedSettings = AdvancedSettings(protocol = VpnProtocol.AUTO),
        )
        val result = coordinator.prepare("sensitive-token", settings)
        assertTrue(result.settings.profile.host.isBlank())
        assertTrue(events.any { it.contains("received=4; with_host=3; security_allowed=2; protocol_allowed=2; eligible=1") })
        assertTrue(events.any { it.contains("stage=selection; selected=; tcp_failed=1; legacy=false") })
        assertTrue(events.any { it.contains("stage=validation; reason=missing_host") })
        assertTrue(events.none { it.contains("sensitive-") })
    }

    @Test
    fun `preparation logs exact API stage without challenge or credentials`() = runBlocking {
        val events = mutableListOf<String>()
        val api = FakeVpnSessionApi(sessionError = BackendException("Device challenge expired", 403))
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            deviceNameProvider = { "Phone" },
            challengeSigner = { "sensitive-signature" },
            onDiagnostic = events::add,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            backendDeviceId = "device-id",
            backendDeviceKey = "sensitive-device-key",
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
        )
        val error = runCatching { coordinator.prepare("sensitive-token", settings) }.exceptionOrNull()
        assertTrue(error is BackendException)
        assertTrue(events.any { it.contains("stage=session_create") && it.contains("http_status=403") && it.contains("reason=challenge_expired") })
        assertTrue(events.none { it.contains("sensitive-") || it.contains("nonce") })
    }

    @Test
    fun `validator exposes reason without changing boolean contract`() {
        val profile = VlessProfile(host = "node.example", port = "443", proxyType = "hysteria", transport = "hysteria",
            security = "tls", serverName = "node.example", uuid = "e912e725-2dc7-4b44-85d2-7fbc31a48b5e")
        val settings = DefaultStoredSettingsFactory.create().copy(
            profile = profile,
            advancedSettings = AdvancedSettings(protocol = VpnProtocol.TLS),
        )
        assertNull(VpnProfileValidator.rejectionReason(settings))
        assertTrue(VpnProfileValidator.isUsable(settings))
        assertEquals("missing_host", VpnProfileValidator.rejectionReason(settings.copy(profile = profile.copy(host = ""))))
        assertEquals("invalid_credential", VpnProfileValidator.rejectionReason(settings.copy(profile = profile.copy(uuid = "do-not-log"))))
        assertEquals("protocol_mismatch", VpnProfileValidator.rejectionReason(settings.copy(advancedSettings = AdvancedSettings(protocol = VpnProtocol.REALITY))))
    }

    @Test
    fun `fresh prepare sends country and keeps concrete server runtime only`() = runBlocking {
        val api = FakeVpnSessionApi()
        val device = BackendDevice(
            id = "device-id",
            deviceKey = "device-key",
            deviceName = "Phone",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            userProfile = UserProfile(
                selectedCountryCode = "DE",
                selectedServerCode = "lv-1",
            ),
            backendDeviceId = device.id,
            backendDeviceKey = device.deviceKey,
        )
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            challengeSigner = { "signature" },
            endpointSelectionProvider = { _, _, _, _, _ ->
                EndpointSelector.EndpointSelectionResult(
                    profile = VlessProfile(endpointCode = "de-endpoint"),
                    endpointCode = "de-endpoint",
                    endpointRating = "healthy",
                )
            },
        )

        val result = coordinator.prepare("token", settings, knownDevices = listOf(device))

        assertEquals("DE", api.countryCode)
        assertEquals("de-2", api.locationCode)
        assertEquals("node-de-2", api.nodeId)
        assertEquals("DE", result.settings.userProfile.selectedCountryCode)
        assertEquals("de-2", result.settings.userProfile.selectedServerCode)
    }

    @Test
    fun `country request always sends catalog node and concrete location`() = runBlocking {
        val api = FakeVpnSessionApi(rejectCountryRequest = true)
        val device = BackendDevice(
            id = "device-id",
            deviceKey = "device-key",
            deviceName = "Phone",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            userProfile = UserProfile(
                selectedCountryCode = "LV",
                selectedServerCode = "LV",
            ),
            backendDeviceId = device.id,
            backendDeviceKey = device.deviceKey,
        )
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            challengeSigner = { "signature" },
            endpointSelectionProvider = { _, _, _, _, _ ->
                EndpointSelector.EndpointSelectionResult(
                    profile = VlessProfile(endpointCode = "de-endpoint"),
                    endpointCode = "de-endpoint",
                    endpointRating = "healthy",
                )
            },
        )

        coordinator.prepare("token", settings, knownDevices = listOf(device))

        assertEquals(listOf("LV" to "lv2"), api.requests)
    }

    @Test
    fun `saved country selects its permitted catalog node`() = runBlocking {
        val api = FakeVpnSessionApi()
        val device = BackendDevice(
            id = "device-id",
            deviceKey = "device-key",
            deviceName = "Phone",
            platform = "android",
            accessRole = "owner",
            isActive = true,
            lastSeenAt = null,
        )
        val settings = DefaultStoredSettingsFactory.create().copy(
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            userProfile = UserProfile(
                selectedCountryCode = "LV",
                selectedServerCode = "lv2",
            ),
            backendDeviceId = device.id,
            backendDeviceKey = device.deviceKey,
        )
        val coordinator = VpnSessionCoordinator(
            context = null,
            repository = FakeVpnSessionStore(),
            backendApi = api,
            challengeSigner = { "signature" },
            endpointSelectionProvider = { _, _, _, _, _ ->
                EndpointSelector.EndpointSelectionResult(
                    profile = VlessProfile(endpointCode = "de-endpoint"),
                    endpointCode = "de-endpoint",
                    endpointRating = "healthy",
                )
            },
        )

        coordinator.prepare("token", settings, knownDevices = listOf(device))

        assertEquals(listOf("LV" to "lv2"), api.requests)
    }

    private class FakeVpnSessionStore : VpnSessionStore {
        override fun ensureBackendDeviceKey(existing: String): String = existing
        override fun loadEndpointHealth(): Map<String, EndpointHealth> = emptyMap()
        override fun nextEndpointRotationIndex(rotationKey: String): Int = 0
    }

    private class FakeVpnSessionApi(
        private val rejectCountryRequest: Boolean = false,
        private val sessionError: BackendException? = null,
        private val candidates: List<BackendEndpointCandidate> = emptyList(),
        private val pairSession: BackendVpnSession? = null,
        private val extraServers: List<VpnServer> = emptyList(),
    ) : VpnSessionApi {
        override suspend fun createRuWireGuardSession(
            token: String,
            request: RuWireGuardSessionRequest,
        ): BackendRuWireGuardSession = error("RU WireGuard has its own coordinator")
        var catalogRequests = 0
        var accessRequests = 0
        override suspend fun serverLocations(token: String, deviceId: String?, deviceKey: String?): List<BackendLocation> =
            listOf("DE" to "de-2", "LV" to "lv2").map { (country, location) ->
                catalogRequests++
                BackendLocation(location, location, country, null, null, "node.example", country, true,
                    null, null, null, null, null,
                    servers = listOf(VpnServer("node-$location", country, country, location, "node.example", null, true)) +
                        extraServers.filter { it.locationCode == location })
            }
        var countryCode: String? = null
        var locationCode: String? = null
        var nodeId: String? = null
        var requestedProfileCode: String? = null
        var multiHopRequest: MultiHopSettings? = null
        val requests = mutableListOf<Pair<String?, String?>>()
        val requestedNodeIds = mutableListOf<String?>()

        override suspend fun vpnAccess(
            token: String,
            deviceId: String?,
            deviceKey: String?,
        ): BackendVpnAccess {
            accessRequests++
            return BackendVpnAccess(canConnect = true, reason = null, planCode = null)
        }

        override suspend fun registerDevice(
            token: String,
            deviceKey: String?,
            deviceId: String?,
            deviceName: String,
            publicKey: String,
            deviceClaims: List<String>,
            platform: String,
        ): BackendDevice = error("not used")

        override suspend fun createDeviceChallenge(
            token: String,
            deviceId: String,
        ) = BackendDeviceChallenge(deviceId = deviceId, nonce = "nonce", expiresAt = null)

        override suspend fun createVpnSession(
            token: String,
            deviceId: String,
            deviceKey: String?,
            deviceNonce: String,
            deviceSignature: String,
            countryCode: String?,
            locationCode: String?,
            excludeLocationCode: String?,
            profileCode: String,
            nodeId: String?,
            multiHop: MultiHopSettings?,
        ): BackendVpnSession {
            this.countryCode = countryCode
            this.locationCode = locationCode
            this.nodeId = nodeId
            requestedNodeIds += nodeId
            requestedProfileCode = profileCode
            multiHopRequest = multiHop
            requests += countryCode to locationCode
            sessionError?.let { throw it }
            pairSession?.let { return it }
            if (nodeId != null && nodeId !in setOf("node-de-2", "node-lv2") && extraServers.none { it.id == nodeId }) {
                throw BackendException("Server not found", 404)
            }
            if (rejectCountryRequest && countryCode != null && locationCode == null) {
                throw BackendException("Location not found", 503)
            }
            return BackendVpnSession(
                canConnect = true,
                profileCode = "reality",
                locationCode = "de-2",
                locationName = "Germany",
                endpointCode = "de-endpoint",
                entryHost = "de.example.com",
                entryPort = 443,
                serverName = "de.example.com",
                proxyType = "vless",
                transport = "xhttp",
                transportMode = "stream-up",
                security = "reality",
                fingerprint = null,
                requestHost = null,
                path = "/noki",
                alpn = null,
                allowInsecure = false,
                enableMux = false,
                randomUserAgent = false,
                publicKey = "public",
                shortId = "abcd",
                vpnUsername = "owner",
                vpnSecret = "00000000-0000-4000-8000-000000000001",
                flow = null,
                planCode = null,
                endpointCandidates = candidates.filter { profileCode == "auto" || it.security == profileCode },
            )
        }
    }
}
