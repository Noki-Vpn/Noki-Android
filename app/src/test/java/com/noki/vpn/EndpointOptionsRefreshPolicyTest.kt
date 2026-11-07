package com.noki.vpn

import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.VpnSessionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointOptionsRefreshPolicyTest {
    @Test
    fun `automatic country still allows choosing a loaded protocol`() {
        val option = VpnEndpointOption(code = "nv", host = "node.example", proxyType = "nokinv",
            security = "tls", transport = "nokinv")
        val state = AppUiState(userProfile = UserProfile(selectedCountryCode = ""),
            endpointOptions = listOf(option), endpointOptionsCountryCode = "")
        val choices = manualEndpointOptionsForCurrentCountry(state)
        assertEquals(1, choices.size)
        assertEquals("quic", choices.single().transportMode)
        assertTrue(choices.all { isManualEndpointSelectable(it, state.endpointOptions, "", "") })
        assertFalse(isManualEndpointSelectable(choices.first(), state.endpointOptions, null, ""))
    }

    @Test
    fun `MultiHop options belong to the selected pair and normalize the retired carrier`() {
        val selection = com.noki.vpn.data.MultiHopSettings(true,
            com.noki.vpn.data.HopSelection(com.noki.vpn.data.ServerSelectionMode.SERVER, nodeId = "entry"),
            com.noki.vpn.data.HopSelection(com.noki.vpn.data.ServerSelectionMode.SERVER, nodeId = "exit"))
        val option = VpnEndpointOption(code = "nv", host = "192.0.2.1", proxyType = "nokinv",
            security = "tls", transport = "nokinv", port = 20001)
        val state = AppUiState(advancedSettings = com.noki.vpn.data.AdvancedSettings(
            protocol = VpnProtocol.NOKINV, endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointGroupKey = "tls|nokinv|tcp", multiHop = selection))
        val direct = endpointResult(listOf(option))
        val routed = direct.copy(session = direct.session.copy(multiHop = com.noki.vpn.data.BackendMultiHopSession(
            selection, "entry", "exit", "192.0.2.1", "a".repeat(64))))
        val result = EndpointOptionsStateReducer.applyEndpointOptions(state, routed, emptyList())!!.state
        assertEquals("tls|nokinv|quic", result.advancedSettings.manualEndpointGroupKey)
        assertEquals("quic", result.profile.transportMode)
        assertNull(result.profile.multiHop)
        assertEquals("", result.profile.nokinvToken)
        assertNull(EndpointOptionsStateReducer.applyEndpointOptions(state, direct, emptyList()))
        assertNull(EndpointOptionsStateReducer.applyEndpointOptions(AppUiState(), routed, emptyList()))
        assertNull(EndpointOptionsStateReducer.applyEndpointOptions(state.copy(advancedSettings = state.advancedSettings.copy(
            multiHop = selection.copy(entry = selection.exit, exit = selection.entry))), routed, emptyList()))
    }

    @Test
    fun transportIdentitySurvivesNodePriorityAndPortChanges() {
        val lv = VpnEndpointOption(code = "lv-nv", locationCode = "lv-2", host = "lv.example",
            proxyType = "nokinv", security = "tls", transport = "nokinv", priority = 100, port = 10443,
            endpointGroupKey = "lv-2|tls|nokinv||100")
        val pl = lv.copy(code = "pl-nv", locationCode = "pl-1", host = "pl.example", priority = 200,
            port = 11443, endpointGroupKey = "pl-1|tls|nokinv||200")
        assertEquals("tls|nokinv|quic", EndpointGroupPolicy.groupKey(lv))
        assertEquals(EndpointGroupPolicy.groupKey(lv), EndpointGroupPolicy.groupKey(pl))
        val selected = com.noki.vpn.data.AdvancedSettings(protocol = VpnProtocol.NOKINV,
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointGroupKey = lv.endpointGroupKey)
        assertEquals("tls|nokinv|quic", EndpointGroupPolicy.resolveManualGroupKey(
            selected, listOf(pl), com.noki.vpn.data.VlessProfile()))
        val refreshed = EndpointOptionsStateReducer.applyEndpointOptions(AppUiState(advancedSettings = selected),
            endpointResult(listOf(pl.copy(code = "first-tls", proxyType = "vless", transport = "tcp"), pl)), emptyList())!!.state
        assertEquals(VpnProtocol.NOKINV, refreshed.advancedSettings.protocol)
        assertEquals(pl.code, refreshed.advancedSettings.manualEndpointCode)
        val absent = EndpointOptionsStateReducer.applyEndpointOptions(refreshed,
            endpointResult(listOf(pl.copy(code = "tls-only", proxyType = "vless", transport = "tcp"))), emptyList())!!.state
        assertEquals(selected.protocol, absent.advancedSettings.protocol)
        assertEquals(refreshed.advancedSettings.manualEndpointGroupKey, absent.advancedSettings.manualEndpointGroupKey)
        assertEquals("", absent.profile.endpointCode)
    }

    private fun endpointResult(options: List<VpnEndpointOption>): VpnSessionCoordinator.EndpointOptionsResult {
        val device = BackendDevice("device-id", "device-key", "phone", null, "android", "owner", true, null)
        return VpnSessionCoordinator.EndpointOptionsResult(BackendVpnSession(
            canConnect = true, profileCode = "auto", locationCode = "pl-1", locationName = "Poland",
            endpointCode = "first", entryHost = "node.example", entryPort = 443, serverName = "node.example",
            proxyType = "vless", transport = "tcp", transportMode = null, security = "tls", fingerprint = null,
            requestHost = null, path = null, alpn = null, allowInsecure = false, enableMux = false,
            randomUserAgent = false, publicKey = null, shortId = null, vpnUsername = "id", vpnSecret = "secret",
            flow = null, planCode = null, endpointCandidates = emptyList()), options, device, device.deviceKey, device.id, device.accessRole)
    }

    @Test
    fun `manual picker exposes QUIC NokiNV and supported direct transports`() {
        val reality = VpnEndpointOption(code = "vision", locationCode = "lv", host = "example.test",
            proxyType = "vless", security = "reality", transport = "tcp")
        val stream = reality.copy(code = "stream", transport = "xhttp", transportMode = "stream-up")
        val tls = reality.copy(code = "tls", security = "tls")
        val hy2 = reality.copy(code = "hy2", proxyType = "hysteria", security = "tls", transport = "hysteria")
        val nokinv = tls.copy(code = "nokinv", proxyType = "nokinv", transport = "nokinv")
        val rejected = listOf(tls, nokinv.copy(code = "nv-tcp", transportMode = "tcp"),
            stream.copy(code = "packet", transportMode = "packet-up"),
            reality.copy(code = "upgrade", transport = "httpupgrade"),
            reality.copy(code = "unknown", transport = "new-protocol"),
            stream.copy(code = "missing-mode", transportMode = ""))

        assertEquals(setOf("nokinv", "stream", "hy2", "vision"),
            EndpointGroupPolicy.manualOptions(listOf(nokinv, stream, hy2, reality) + rejected).map { it.code }.toSet())
        assertEquals(4, EndpointGroupPolicy.manualOptions(listOf(nokinv, stream, hy2, reality) + rejected).size)
    }

    @Test
    fun `cached endpoint options are reused only for the same country`() {
        assertFalse(
            shouldRefreshEndpointOptions(
                force = false,
                optionsCount = 2,
                loadedCountryCode = "PL",
                selectedCountryCode = "PL",
            ),
        )
        assertTrue(
            shouldRefreshEndpointOptions(
                force = false,
                optionsCount = 2,
                loadedCountryCode = "PL",
                selectedCountryCode = "LV",
            ),
        )
        assertTrue(
            shouldRefreshEndpointOptions(
                force = true,
                optionsCount = 2,
                loadedCountryCode = "PL",
                selectedCountryCode = "PL",
            ),
        )
    }

    @Test
    fun `manual endpoint is selectable only from the list loaded for current country`() {
        val stalePolish = VpnEndpointOption(
            code = "pl-1",
            locationCode = "pl-1",
            host = "192.0.2.10",
        )
        val currentLatvian = VpnEndpointOption(
            code = "lv-1",
            locationCode = "lv-1",
            host = "192.0.2.20",
            security = "reality",
            transport = "xhttp",
            transportMode = "stream-up",
            port = 443,
        )
        val currentLatvianAlternatePort = currentLatvian.copy(code = "lv-2", port = 8443)
        val groupedLatvian = EndpointGroupPolicy.manualOptions(
            listOf(currentLatvian, currentLatvianAlternatePort),
        ).single()

        assertFalse(
            isManualEndpointSelectable(
                option = stalePolish,
                endpointOptions = listOf(stalePolish),
                loadedCountryCode = "PL",
                selectedCountryCode = "LV",
            ),
        )
        assertFalse(
            isManualEndpointSelectable(
                option = stalePolish,
                endpointOptions = listOf(currentLatvian),
                loadedCountryCode = "LV",
                selectedCountryCode = "LV",
            ),
        )
        assertTrue(
            isManualEndpointSelectable(
                option = groupedLatvian,
                endpointOptions = listOf(currentLatvian, currentLatvianAlternatePort),
                loadedCountryCode = "LV",
                selectedCountryCode = "LV",
            ),
        )
    }

    @Test
    fun `manual endpoint list is hidden until its country identity is proven`() {
        val option = VpnEndpointOption(
            code = "pl-1",
            locationCode = "pl-1",
            host = "192.0.2.10",
            security = "reality",
            transport = "xhttp",
            transportMode = "stream-up",
        )
        val cachedWithoutIdentity = AppUiState(
            userProfile = com.noki.vpn.data.UserProfile(selectedCountryCode = "PL"),
            endpointOptions = listOf(option),
            endpointOptionsCountryCode = null,
        )
        val proven = cachedWithoutIdentity.copy(endpointOptionsCountryCode = "PL")

        assertTrue(manualEndpointOptionsForCurrentCountry(cachedWithoutIdentity).isEmpty())
        assertTrue(manualEndpointOptionsForCurrentCountry(proven).isNotEmpty())
    }

    @Test
    fun `late normal endpoint result cannot replace Reality preference after route mode changes`() {
        val reality = VpnEndpointOption(code = "lv-reality", locationCode = "lv", security = "reality")
        val tls = VpnEndpointOption(code = "lv-tls", locationCode = "lv", security = "tls")
        val advanced = com.noki.vpn.data.AdvancedSettings(
            protocol = VpnProtocol.REALITY,
            endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointCode = reality.code,
            ruRelayEnabled = true,
        )
        val state = AppUiState(
            advancedSettings = advanced,
            userProfile = UserProfile(selectedCountryCode = "LV"),
            endpointOptions = listOf(reality),
            endpointOptionsCountryCode = "LV",
        )
        val device = BackendDevice("device-id", "device-key", "phone", null, "android", "owner", true, null)
        val result = VpnSessionCoordinator.EndpointOptionsResult(
            session = BackendVpnSession(
                canConnect = true, profileCode = "test", locationCode = "lv", locationName = "Latvia",
                endpointCode = tls.code, entryHost = "example.test", entryPort = 443,
                serverName = "example.test", proxyType = "vless", transport = "tcp",
                transportMode = null, security = "tls", fingerprint = null, requestHost = null,
                path = null, alpn = null, allowInsecure = false, enableMux = false,
                randomUserAgent = false, publicKey = null, shortId = null,
                vpnUsername = "id", vpnSecret = "secret", flow = null, planCode = null,
                endpointCandidates = emptyList(),
            ),
            endpointOptions = listOf(tls), currentDevice = device,
            backendDeviceKey = device.deviceKey, backendDeviceId = device.id,
            backendDeviceAccessRole = device.accessRole,
        )

        assertNull(EndpointOptionsStateReducer.applyEndpointOptions(state, result, listOf(device)))
        assertEquals(VpnProtocol.REALITY, state.advancedSettings.protocol)
        assertEquals(reality.code, state.advancedSettings.manualEndpointCode)
        assertFalse(isManualEndpointSelectable(tls, listOf(tls), "LV", "LV", ruRelayEnabled = true))
        val multiHopState = state.copy(advancedSettings = advanced.copy(
            ruRelayEnabled = false,
            multiHop = com.noki.vpn.data.MultiHopSettings(enabled = true),
        ))
        assertNull(EndpointOptionsStateReducer.applyEndpointOptions(multiHopState, result, listOf(device)))
    }
}
