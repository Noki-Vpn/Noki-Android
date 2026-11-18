package com.noki.vpn.ui

import com.noki.vpn.AppUiState
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTextPolicyTest {
    @Test fun connectedNokiNVUsesTheProtocolPickerNamesWhileGamingKeepsItsPickerLabel() {
        for (language in AppLanguage.entries) {
            for ((carrier, mode) in listOf("quic" to "quic")) {
                val endpoint = VpnEndpointOption(code = "nv", host = "nv.example.com", label = "custom label",
                    proxyType = "nokinv", transport = "nokinv", transportMode = mode)
                val expected = EndpointGroupPolicy.manualOptions(listOf(endpoint)).single().label
                val state = AppUiState(connectionState = VpnConnectionState.CONNECTED,
                    nokiNvCarrier = carrier, endpointOptions = listOf(endpoint),
                    personalizationSettings = PersonalizationSettings(language = language),
                    advancedSettings = AdvancedSettings(ruRelayEnabled = false,
                        manualEndpointGroupKey = EndpointGroupPolicy.groupKey(endpoint)),
                    profile = VlessProfile(endpointCode = endpoint.code, proxyType = "nokinv", transport = "nokinv", transportMode = mode))
                for (auto in listOf(false, true)) assertEquals(expected, protocolCardLabel(state, auto))
                val gaming = state.copy(advancedSettings = state.advancedSettings.copy(gamingModeEnabled = true))
                for (connection in VpnConnectionState.entries) for (auto in listOf(false, true)) {
                    assertEquals("NokiNV / gaming", protocolCardLabel(gaming.copy(
                        connectionState = connection), auto))
                }
                assertEquals("NokiNV / gaming", protocolCardLabel(gaming.copy(nokiNvCarrier = "mixed"), true))
            }
        }
    }

    @Test
    fun autoProtocolLabelFollowsRuntimeFallbackInsteadOfPreviousManualChoice() {
        val nv = VpnEndpointOption(code = "nv", host = "nv.example.com", label = "NokiNV / Auto",
            proxyType = "nokinv", transport = "nokinv")
        val hy = VpnEndpointOption(code = "hy", host = "hy.example.com", label = "Hysteria2 / UDP",
            proxyType = "hysteria", transport = "hysteria")
        val state = AppUiState(
            endpointOptions = listOf(nv, hy),
            advancedSettings = AdvancedSettings(ruRelayEnabled = false,
                manualEndpointGroupKey = EndpointGroupPolicy.groupKey(nv)),
            profile = VlessProfile(endpointCode = hy.code, proxyType = hy.proxyType,
                transport = hy.transport, security = hy.security),
        )
        assertEquals("Auto (Hysteria2)", protocolCardLabel(state, autoEndpointSelection = true))
        assertEquals("NokiNV", protocolCardLabel(state, autoEndpointSelection = false))
        assertEquals("Auto (NokiNV)", protocolCardLabel(state.copy(
            profile = state.profile.copy(endpointCode = "")), autoEndpointSelection = true))
    }

    @Test
    fun multiHopCountryMenuGroupsRealNodesAndExcludesPeerBeforeGrouping() {
        val first = VpnServer("lv-1", "Latvia 1", "LV", "lv1", "probe.example", 443, true, latencyMs = 25)
        val second = first.copy(id = "lv-2", name = "Latvia 2", latencyMs = 45)
        val austria = first.copy(id = "at-1", name = "Austria 1", countryCode = "AT", latencyMs = null)
        val menu = serverMenuLocations(nodes = listOf(first, second, first, austria,
            austria.copy(id = "offline", isOnline = false)), language = AppLanguage.RU, excludedNodeId = first.id)
        assertEquals(listOf("AT", "LV"), menu.map { it.countryCode })
        assertEquals(listOf("Австрия", "Латвия"), menu.map { it.country })
        assertEquals(listOf(austria, second), menu.flatMap { it.servers })
        assertEquals(listOf(null, 45), menu.map { it.latencyMs })
        assertTrue(menu.all { it.location == null && it.foreignNodes.isEmpty() })
        assertTrue(serverMenuLocations(nodes = listOf(first), language = AppLanguage.RU,
            excludedNodeId = first.id).isEmpty())
    }

    @Test
    fun missingRuDiscoveryKeepsRealBootstrapCountriesAndNodesVisible() {
        val node = VpnServer("lv-2", "Latvia 2", "LV", "lv2", "probe.example", 443, true)
        val catalog = listOf(location(code = "LV").copy(servers = listOf(node)))
        val menu = serverMenuLocations(catalog, emptyList(), ruRelayEnabled = true)
        assertEquals(listOf("LV"), menu.map { it.key })
        assertEquals(listOf(node), menu.single().location?.servers)
        assertTrue(menu.single().foreignNodes.isEmpty())
    }

    @Test
    fun `RU protocol label stays Auto in either language and endpoint mode`() {
        for (language in AppLanguage.entries) {
            val state = AppUiState(
                advancedSettings = AdvancedSettings(ruRelayEnabled = true),
                personalizationSettings = PersonalizationSettings(language = language),
            )
            assertEquals("Auto", protocolCardLabel(state, autoEndpointSelection = true))
            assertEquals("Auto", protocolCardLabel(state, autoEndpointSelection = false))
        }
    }

    @Test
    fun ruServerCardShowsSelectionBeforeConnectionAndOnlyConfirmedExitWhileConnected() {
        val state = AppUiState(
            advancedSettings = AdvancedSettings(ruRelayEnabled = true),
            personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
            ruForeignNodes = listOf(RuForeignNode("lv", "Латвия1", "LV"), RuForeignNode("nl", "Нидерланды1", "NL")),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = "lv",
                selectedCountryCode = "LV", selectedServerCode = "nl", actualCountryCode = "NL"),
        )
        assertEquals("RU-реле → Латвия1", homeServerSubtitle(state))
        assertEquals("RU-реле → Латвия", homeServerSubtitle(state.copy(userProfile = state.userProfile.copy(
            serverSelectionMode = ServerSelectionMode.COUNTRY))))
        assertEquals("RU-реле", homeServerSubtitle(state.copy(userProfile = state.userProfile.copy(
            serverSelectionMode = ServerSelectionMode.AUTO))))
        for (mode in ServerSelectionMode.entries) {
            val connected = state.copy(connectionState = VpnConnectionState.CONNECTED,
                userProfile = state.userProfile.copy(serverSelectionMode = mode))
            assertEquals("RU-реле → Нидерланды1", homeServerSubtitle(connected))
            assertEquals("RU-реле → Сервер недоступен", homeServerSubtitle(connected.copy(
                userProfile = connected.userProfile.copy(selectedServerCode = "missing"))))
        }
        assertEquals("RU relay → Латвия1", homeServerSubtitle(state.copy(
            personalizationSettings = PersonalizationSettings(language = AppLanguage.EN))))
    }

    @Test
    fun autoSelectionHidesThePreviousDestinationUntilConnected() {
        val state = AppUiState(locations = listOf(location()),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.AUTO,
                selectedServerCode = "lv", actualCountryCode = "LV", selectedCountryCode = "LV"))
        for (connection in listOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.CONNECTING, VpnConnectionState.FAILED)) {
            assertNull(homeServerSubtitle(state.copy(connectionState = connection)))
        }
        assertEquals(state.locations.single().country,
            homeServerSubtitle(state.copy(connectionState = VpnConnectionState.CONNECTED)))
    }

    @Test
    fun ruServerCardIgnoresAnOldMultiHopChainAndUsesTheConfirmedExit() {
        val state = AppUiState(
            advancedSettings = AdvancedSettings(ruRelayEnabled = true, multiHop = MultiHopSettings(
                enabled = true, entry = HopSelection(ServerSelectionMode.SERVER, nodeId = "entry"),
                exit = HopSelection(ServerSelectionMode.SERVER, nodeId = "exit"))),
            personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
            ruForeignNodes = listOf(RuForeignNode("entry", "Латвия1", "LV"), RuForeignNode("exit", "Нидерланды1", "NL")),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.AUTO, selectedServerCode = "exit"),
        )
        assertEquals("RU-реле", homeServerSubtitle(state))
        assertEquals("RU-реле → Нидерланды1", homeServerSubtitle(state.copy(
            connectionState = VpnConnectionState.CONNECTED)))
        val direct = state.copy(advancedSettings = state.advancedSettings.copy(ruRelayEnabled = false),
            locations = listOf(location().copy(servers = listOf(
                VpnServer("entry", "Латвия1", "LV", "lv", "lv.example", 443, true),
                VpnServer("exit", "Нидерланды1", "NL", "nl", "nl.example", 443, true)))))
        assertEquals("Латвия1 → Нидерланды1", homeServerSubtitle(direct))
    }

    @Test
    fun multiHopFlagUsesConfiguredOrConfirmedExitAndNeverPreviousAutoCountry() {
        val nodes = listOf(
            VpnServer("entry", "Latvia", "LV", "lv", "lv.example", 443, true),
            VpnServer("exit", "Netherlands", "NL", "nl", "nl.example", 443, true),
            VpnServer("runtime", "Austria", "AT", "at", "at.example", 443, true),
            VpnServer("old", "France", "FR", "fr", "fr.example", 443, true),
        )
        for (ruRelay in listOf(false, true)) {
            val state = AppUiState(
                personalizationSettings = PersonalizationSettings(language = AppLanguage.EN),
                advancedSettings = AdvancedSettings(ruRelayEnabled = ruRelay, multiHop = MultiHopSettings(
                    enabled = true, entry = HopSelection(ServerSelectionMode.SERVER, nodeId = "entry"),
                    exit = HopSelection(ServerSelectionMode.SERVER, nodeId = "exit"))),
                locations = nodes.map { location(code = it.locationCode, countryCode = it.countryCode).copy(servers = listOf(it)) },
                ruForeignNodes = nodes.map { RuForeignNode(it.id, it.name, it.countryCode) },
                userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.AUTO,
                    selectedServerCode = "old", actualCountryCode = "FR", selectedCountryCode = "FR"),
            )
            if (ruRelay) {
                assertNull(homeMultiHopExitCountryCode(state))
                continue
            }
            for (connection in listOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.CONNECTING, VpnConnectionState.FAILED)) {
                assertEquals("NL", homeMultiHopExitCountryCode(state.copy(connectionState = connection)))
            }
            val connected = state.copy(connectionState = VpnConnectionState.CONNECTED,
                userProfile = state.userProfile.copy(selectedServerCode = "exit"))
            assertEquals("NL", homeMultiHopExitCountryCode(connected))
            assertNull(homeMultiHopExitCountryCode(state.copy(connectionState = VpnConnectionState.CONNECTED)))
            assertNull(homeMultiHopExitCountryCode(connected.copy(userProfile =
                state.userProfile.copy(selectedServerCode = "runtime"))))
            assertEquals("NL", homeMultiHopExitCountryCode(connected.copy(userProfile =
                state.userProfile.copy(selectedServerCode = "nl"))))
            assertNull(homeMultiHopExitCountryCode(connected.copy(userProfile =
                state.userProfile.copy(selectedServerCode = "missing"))))
            assertNull(homeMultiHopExitCountryCode(state.copy(advancedSettings = state.advancedSettings.copy(
                multiHop = state.advancedSettings.multiHop.copy(exit = HopSelection(ServerSelectionMode.SERVER, nodeId = "missing"))))))
            assertEquals("NL", homeMultiHopExitCountryCode(state.copy(advancedSettings = state.advancedSettings.copy(
                multiHop = state.advancedSettings.multiHop.copy(exit = HopSelection(ServerSelectionMode.COUNTRY, countryCode = " nl "))))))
        }
    }

    @Test
    fun `duration formatter preserves minute hour and day forms`() {
        assertEquals("00:00", formatConnectionDuration(-1L))
        assertEquals("01:02", formatConnectionDuration(62_000L))
        assertEquals("01:01:01", formatConnectionDuration(3_661_000L))
        assertEquals("01:01:01:01", formatConnectionDuration(90_061_000L))
    }

    @Test
    fun `country marker accepts only normalized two-letter country code`() {
        assertEquals("LV", countryMarkerCode(location(countryCode = " lv ")))
        assertNull(countryMarkerCode(location(countryCode = "LVA")))
        assertNull(countryMarkerCode(location(countryCode = "1v")))
    }

    @Test
    fun `country flag resource names support arbitrary ISO alpha two countries`() {
        mapOf(
            "lv" to "flag_lv",
            "FR" to "flag_fr",
            "jp" to "flag_jp",
            "BR" to "flag_br",
            "za" to "flag_za",
        ).forEach { (code, expectedResourceName) ->
            assertEquals(expectedResourceName, countryFlagResourceName(location(countryCode = code)))
        }
        assertNull(countryFlagResourceName(location(countryCode = "LVA")))
        assertNull(countryFlagResourceName(location(countryCode = "1v")))
    }

    @Test
    fun `server menu keeps source order and code identity`() {
        val first = location(code = "lv", latencyMs = 25)
        val second = location(code = "de")

        val menu = serverMenuLocations(listOf(first, second))

        assertEquals(listOf("lv", "de"), menu.map { it.key })
        assertEquals(listOf(first, second), menu.map { it.location })
        assertEquals(listOf(25, null), menu.map { it.latencyMs })
    }

    @Test
    fun `RU menu groups real overlay nodes without direct locations`() {
        val nodes = listOf(RuForeignNode("nl", "NL overlay", "NL"),
            RuForeignNode("lv", "LV dual", "LV", latencyMs = 25),
            RuForeignNode("lv2", "LV second", "LV", latencyMs = 60))
        val menu = serverMenuLocations(emptyList(), nodes, ruRelayEnabled = true, language = AppLanguage.EN)
        assertEquals(listOf("LV", "NL"), menu.map { it.key })
        assertEquals(listOf(null, null), menu.map { it.location })
        assertEquals(setOf("nl", "lv", "lv2"), menu.flatMap { it.foreignNodes }.map { it.id }.toSet())
        assertEquals(listOf(25, null), menu.map { it.latencyMs })
        assertEquals(emptyList<HomeServerMenuEntry>(), serverMenuLocations(emptyList(), nodes))
    }

    @Test
    fun `home location resolves selected node when country and location codes differ`() {
        val selectedServer = VpnServer(
            id = "lv4",
            name = "Latvia 4",
            countryCode = "LV",
            locationCode = "lv4",
            host = "lv4.example",
            probePort = 443,
            isOnline = true,
        )
        val location = location(code = "lv4").copy(servers = listOf(selectedServer))
        val profile = UserProfile(
            serverSelectionMode = ServerSelectionMode.SERVER,
            selectedCountryCode = "LV",
            selectedNodeId = "lv4",
        )

        assertEquals(location, homeSelectedLocation(listOf(location), profile))
    }

    @Test
    fun `metrics expose traffic only while connected but keep server health`() {
        val node = VpnServer("node2", "Latvia 2", "LV", "lv2", "lv2.example", 443, true, latencyMs = 39)
        val server = location(latencyMs = 24, loadPercent = 31).copy(servers = listOf(node))
        val state = AppUiState(
            locations = listOf(server),
            userProfile = UserProfile(selectedServerCode = "lv2"),
            connectionState = VpnConnectionState.CONNECTING,
        )
        val traffic = HomeDeviceTrafficSnapshot(downloadMbps = "2.5", uploadMbps = "1", sessionBytes = 1_048_576L)

        assertEquals(
            HomeMetricsSnapshot(download = null, upload = null, latency = "24", sessionBytes = null),
            currentMetrics(state, traffic),
        )
        assertEquals(
            HomeMetricsSnapshot(download = "2.5", upload = "1", latency = "39", sessionBytes = 1_048_576L),
            currentMetrics(state.copy(connectionState = VpnConnectionState.CONNECTED, connectionLatencyMs = 87), traffic),
        )
        for (mode in ServerSelectionMode.entries) {
            val connected = state.copy(
                connectionState = VpnConnectionState.CONNECTED,
                connectionLatencyMs = 87,
                userProfile = state.userProfile.copy(serverSelectionMode = mode, selectedNodeId = node.id),
            )
            assertEquals("39", currentMetrics(connected, traffic).latency)
            assertNull(currentMetrics(connected.copy(locations = listOf(server.copy(
                servers = listOf(node.copy(latencyMs = null)),
            ))), traffic).latency)
            assertNull(currentMetrics(connected.copy(userProfile = connected.userProfile.copy(
                selectedServerCode = "missing",
            )), traffic).latency)
            assertEquals("39", currentMetrics(connected.copy(connectionLatencyMs = null), traffic).latency)
        }
        val manual = state.copy(userProfile = state.userProfile.copy(
            serverSelectionMode = ServerSelectionMode.SERVER, selectedNodeId = node.id,
        ))
        assertEquals("39", currentMetrics(manual, traffic).latency)
        assertEquals("39", currentMetrics(manual.copy(connectionState = VpnConnectionState.DISCONNECTED), traffic).latency)
    }

    @Test
    fun `connected RU metrics match the exact exit row in the server sheet`() {
        val direct = location(latencyMs = 5)
        val foreign = RuForeignNode("exit", "Latvia", "LV", latencyMs = 12)
        val other = foreign.copy(id = "other", name = "Other", latencyMs = 3)
        val state = AppUiState(
            advancedSettings = AdvancedSettings(ruRelayEnabled = true),
            connectionState = VpnConnectionState.CONNECTED,
            connectionLatencyMs = 87,
            locations = listOf(direct),
            ruForeignNodes = listOf(other, foreign),
            userProfile = UserProfile(selectedServerCode = foreign.id, selectedNodeId = other.id),
        )
        for (mode in ServerSelectionMode.entries) {
            val connected = state.copy(userProfile = state.userProfile.copy(serverSelectionMode = mode))
            val sheet = serverMenuLocations(connected.locations, connected.ruForeignNodes, true)
            val row = sheet.flatMap { it.foreignNodes }.single { it.id == foreign.id }
            assertEquals(row.latencyMs.toString(), currentMetrics(connected, HomeDeviceTrafficSnapshot()).latency)
            assertEquals("12", currentMetrics(connected.copy(locations = emptyList(), connectionLatencyMs = null),
                HomeDeviceTrafficSnapshot()).latency)
            assertNull(currentMetrics(connected.copy(ruForeignNodes = listOf(other)), HomeDeviceTrafficSnapshot()).latency)
            val refreshed = com.noki.vpn.BackendSyncCoordinator.withCachedClientLatencies(connected,
                mapOf(com.noki.vpn.data.clientLatencyTargetKey(foreign) to 52,
                    com.noki.vpn.data.clientLatencyTargetKey(other) to 2))
            assertEquals("52", currentMetrics(refreshed, HomeDeviceTrafficSnapshot()).latency)
        }
        assertNull(currentMetrics(state.copy(connectionState = VpnConnectionState.DISCONNECTED,
            ruForeignNodes = emptyList()), HomeDeviceTrafficSnapshot()).latency)
    }

    @Test
    fun `connected direct metrics use active endpoint node instead of the first country server`() {
        val first = VpnServer("first", "First", "LV", "shared", "first.example", 443, true, latencyMs = 3)
        val active = first.copy(id = "active", name = "Active", host = "active.example", latencyMs = 41)
        val state = AppUiState(
            advancedSettings = AdvancedSettings(ruRelayEnabled = false),
            connectionState = VpnConnectionState.CONNECTED,
            connectionLatencyMs = 999,
            profile = com.noki.vpn.data.VlessProfile(endpointCode = "endpoint"),
            endpointOptions = listOf(com.noki.vpn.data.VpnEndpointOption(code = "endpoint", nodeId = active.id)),
            locations = listOf(location(code = "shared", latencyMs = 3).copy(servers = listOf(first, active))),
            userProfile = UserProfile(selectedServerCode = "shared", selectedNodeId = first.id),
        )
        for (mode in ServerSelectionMode.entries) {
            val connected = state.copy(userProfile = state.userProfile.copy(serverSelectionMode = mode))
            val row = serverMenuLocations(connected.locations).flatMap { it.servers }.single { it.id == active.id }
            assertEquals(row.latencyMs.toString(), currentMetrics(connected, HomeDeviceTrafficSnapshot()).latency)
            assertNull(currentMetrics(connected.copy(locations = listOf(state.locations.single().copy(
                servers = listOf(first)))), HomeDeviceTrafficSnapshot()).latency)
            assertNull(currentMetrics(connected.copy(endpointOptions = emptyList()), HomeDeviceTrafficSnapshot()).latency)
        }
    }

    private fun location(
        code: String = "lv",
        countryCode: String = "LV",
        latencyMs: Int? = null,
        loadPercent: Int? = null,
    ) = ServerLocation(
        code = code,
        countryCode = countryCode,
        country = "Latvia",
        city = "Riga",
        host = "vpn.example",
        latencyMs = latencyMs,
        loadPercent = loadPercent,
        isOnline = true,
    )
}
