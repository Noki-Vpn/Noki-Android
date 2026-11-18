package com.noki.vpn

import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.ui.siteRuleValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AdvancedSettingsStateReducerTest {
    @Test
    fun gamingOverridesRuAndManualChoiceWithoutReplacingItAndKeepsMultiHop() {
        val saved = AdvancedSettings(protocol = VpnProtocol.REALITY, ruRelayEnabled = false,
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointCode = "xhttp",
            manualEndpointGroupKey = "reality|xhttp|stream-up", multiHop = MultiHopSettings(enabled = true))
        val current = AppUiState(advancedSettings = saved,
            profile = VlessProfile(endpointCode = "old", uuid = "old"))
        val gaming = AdvancedSettingsStateReducer.setGamingModeEnabled(current, true)
        assertEquals(VpnProtocol.NOKINV, gaming.advancedSettings.effectiveProtocol)
        assertEquals(saved.copy(gamingModeEnabled = true, ruRelayEnabled = false), gaming.advancedSettings)
        assertEquals("", gaming.profile.endpointCode)
        val direct = AdvancedSettingsStateReducer.setMultiHop(gaming, MultiHopSettings())
        assertEquals(VpnProtocol.NOKINV, direct.advancedSettings.effectiveProtocol)
        val restored = AdvancedSettingsStateReducer.setGamingModeEnabled(direct, false)
        assertEquals(saved.copy(ruRelayEnabled = false, multiHop = MultiHopSettings()), restored.advancedSettings)
        assertEquals(VpnProtocol.REALITY, restored.advancedSettings.effectiveProtocol)
        val ru = AdvancedSettingsStateReducer.setRuRelayEnabled(gaming, true)
        assertEquals(false, ru.advancedSettings.gamingModeEnabled)
        assertEquals(VpnProtocol.WIREGUARD, ru.advancedSettings.effectiveProtocol)
    }

    @Test
    fun youtubeCannotBeEnabledOnFreeIncludingTheTrialButRemainsAvailableOnPaidPlans() {
        val current = AppUiState(advancedSettings = AdvancedSettings(youtubeNoAdsEnabled = true))
        for (raw in listOf("free", "free-plus", "free_monthly")) {
            val free = current.copy(userProfile = UserProfile(selectedPlanCodeRaw = raw, trialTrafficRemainingGb = 3.0))
            assertEquals(false, AdvancedSettingsStateReducer.setYoutubeNoAdsEnabled(free, true).advancedSettings.youtubeNoAdsEnabled)
        }
        val paid = current.copy(userProfile = UserProfile(selectedPlanCodeRaw = "plus-monthly", selectedPlanTier = "plus"))
        assertEquals(true, AdvancedSettingsStateReducer.setYoutubeNoAdsEnabled(paid, true).advancedSettings.youtubeNoAdsEnabled)
    }

    @Test
    fun ruRelayDisablesMultiHopButKeepsItsPairForManualReenable() {
        val manual = MultiHopSettings(enabled = true,
            entry = HopSelection(ServerSelectionMode.SERVER, nodeId = "foreign-entry"),
            exit = HopSelection(ServerSelectionMode.SERVER, nodeId = "foreign-exit"))
        val current = AppUiState(
            profile = VlessProfile(endpointCode = "old-nat", uuid = "old-runtime-user"),
            advancedSettings = AdvancedSettings(
                protocol = VpnProtocol.REALITY,
                multiHop = manual,
            ),
        )

        assertEquals(VpnProtocol.REALITY, current.advancedSettings.effectiveProtocol)
        val enabled = AdvancedSettingsStateReducer.setRuRelayEnabled(current, true)
        assertEquals(VpnProtocol.WIREGUARD, enabled.advancedSettings.effectiveProtocol)
        assertEquals(VpnProtocol.REALITY, enabled.advancedSettings.protocol)
        assertEquals(manual.copy(enabled = false), enabled.advancedSettings.multiHop)
        assertEquals("", enabled.profile.uuid)
        assertEquals("", enabled.profile.endpointCode)

        val disabled = AdvancedSettingsStateReducer.setRuRelayEnabled(enabled, false)
        assertEquals(VpnProtocol.REALITY, disabled.advancedSettings.effectiveProtocol)
        assertEquals(manual.copy(enabled = false), disabled.advancedSettings.multiHop)
        assertEquals(manual, AdvancedSettingsStateReducer.setMultiHop(disabled, manual).advancedSettings.multiHop)
        assertEquals("", disabled.profile.uuid)
    }

    @Test
    fun explicitMultiHopPreservesRuChoiceAndClearsThePreviousRuntimeWithoutChangingDirectPreferences() {
        val current = AppUiState(
            profile = VlessProfile(endpointCode = "direct-tcp", uuid = "direct-user"),
            advancedSettings = AdvancedSettings(
                protocol = VpnProtocol.TLS,
                endpointSelectionMode = EndpointSelectionMode.MANUAL,
                manualEndpointCode = "direct-tcp",
                manualEndpointGroupKey = "saved-group",
                alwaysOnDomains = listOf("domain:secure.example"),
            ),
        )
        val selected = MultiHopSettings(enabled = true,
            entry = HopSelection(ServerSelectionMode.SERVER, nodeId = "foreign-entry"),
            exit = HopSelection(ServerSelectionMode.SERVER, nodeId = "foreign-exit"))

        val next = AdvancedSettingsStateReducer.setMultiHop(current, selected)

        assertEquals(false, next.advancedSettings.ruRelayEnabled)
        assertEquals(selected, next.advancedSettings.multiHop)
        assertEquals(VpnProtocol.TLS, next.advancedSettings.protocol)
        assertEquals("direct-tcp", next.advancedSettings.manualEndpointCode)
        assertEquals("saved-group", next.advancedSettings.manualEndpointGroupKey)
        assertEquals(current.advancedSettings.alwaysOnDomains, next.advancedSettings.alwaysOnDomains)
        assertEquals("", next.profile.uuid)
        val ru = current.copy(advancedSettings = current.advancedSettings.copy(ruRelayEnabled = true))
        assertSame(ru, AdvancedSettingsStateReducer.setMultiHop(ru, selected))
    }

    @Test
    fun ruRelayAlwaysUsesAutomaticSelectionAndKeepsNormalPreference() {
        val initial = AppUiState(
            advancedSettings = AdvancedSettings(protocol = VpnProtocol.REALITY),
            userProfile = UserProfile(serverSelectionMode = ServerSelectionMode.SERVER,
                selectedNodeId = "exit-a"),
        )
        val ru = AdvancedSettingsStateReducer.setRuRelayEnabled(initial, true)
        assertEquals(com.noki.vpn.data.RuRelaySelection.Auto, ru.advancedSettings.ruRelaySelection)
        assertEquals(VpnProtocol.WIREGUARD, ru.advancedSettings.effectiveProtocol)
        assertEquals(initial.userProfile, ru.userProfile)
        assertEquals(VpnProtocol.REALITY, ru.advancedSettings.protocol)
        assertEquals(false, AdvancedSettingsStateReducer.setRuRelayEnabled(ru, false).advancedSettings.ruRelayEnabled)
    }

    @Test
    fun packageSelectionTogglesMembership() {
        val current = AppUiState(selectedPackages = setOf("com.a"))

        val added = AdvancedSettingsStateReducer.togglePackageSelection(current, "com.b")
        val removed = AdvancedSettingsStateReducer.togglePackageSelection(added, "com.a")

        assertEquals(setOf("com.a", "com.b"), added.selectedPackages)
        assertEquals(setOf("com.b"), removed.selectedPackages)
    }

    @Test
    fun packageSelectionCanBeCleared() {
        val current = AppUiState(selectedPackages = setOf("com.a", "com.b"))

        val cleared = AdvancedSettingsStateReducer.clearPackageSelection(current)

        assertEquals(emptySet<String>(), cleared.selectedPackages)
    }

    @Test
    fun domainRulesTrimDeduplicateAndIgnoreBlankValues() {
        val current = AppUiState(
            alwaysOnInput = " pending.example ",
            bypassInput = " bypass.example ",
            advancedSettings = AdvancedSettings(
                alwaysOnDomains = listOf("a.example"),
                bypassDomains = listOf("b.example"),
            ),
        )

        val withAlwaysOn = AdvancedSettingsStateReducer.addAlwaysOnDomain(current, current.alwaysOnInput)
        val withDuplicateAlwaysOn = AdvancedSettingsStateReducer.addAlwaysOnDomain(withAlwaysOn, "a.example")
        val withBypass = AdvancedSettingsStateReducer.addBypassDomain(withDuplicateAlwaysOn, current.bypassInput)
        val blankIgnored = AdvancedSettingsStateReducer.addBypassDomain(withBypass, "   ")

        assertEquals(listOf("domain:a.example", "domain:pending.example"), withAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals("", withAlwaysOn.alwaysOnInput)
        assertEquals(listOf("domain:a.example", "domain:pending.example"), withDuplicateAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals(listOf("domain:b.example", "domain:bypass.example"), withBypass.advancedSettings.bypassDomains)
        assertEquals("", withBypass.bypassInput)
        assertSame(withBypass, blankIgnored)
    }

    @Test
    fun updatingDomainRulesTrimsDeduplicatesAndKeepsStateOnBlank() {
        val current = AppUiState(
            advancedSettings = AdvancedSettings(
                alwaysOnDomains = listOf("a.example", "b.example"),
                bypassDomains = listOf("x.example", "y.example"),
            ),
        )

        val updatedAlwaysOn = AdvancedSettingsStateReducer.updateAlwaysOnDomain(current, "b.example", " a.example ")
        val removedAlwaysOn = AdvancedSettingsStateReducer.removeAlwaysOnDomain(updatedAlwaysOn, "a.example")
        val updatedBypass = AdvancedSettingsStateReducer.updateBypassDomain(current, "y.example", " z.example ")
        val removedBypass = AdvancedSettingsStateReducer.removeBypassDomain(updatedBypass, "x.example")
        val blankIgnored = AdvancedSettingsStateReducer.updateBypassDomain(removedBypass, "z.example", " ")

        assertEquals(listOf("domain:a.example"), updatedAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals(emptyList<String>(), removedAlwaysOn.advancedSettings.alwaysOnDomains)
        assertEquals(listOf("domain:x.example", "domain:z.example"), updatedBypass.advancedSettings.bypassDomains)
        assertEquals(listOf("domain:z.example"), removedBypass.advancedSettings.bypassDomains)
        assertSame(removedBypass, blankIgnored)
    }

    @Test
    fun domainEditorExplainsInvalidAndDuplicateValues() {
        val domains = listOf("domain:example.com")

        assertEquals("Enter a domain", siteRuleValidationError("", domains, null, AppLanguage.EN))
        assertEquals("Enter a valid domain", siteRuleValidationError("bad host", domains, null, AppLanguage.EN))
        assertEquals(
            "This domain is already in the list",
            siteRuleValidationError("example.com", domains, null, AppLanguage.EN),
        )
        assertNull(siteRuleValidationError("valid.example", domains, null, AppLanguage.EN))
        assertNull(
            siteRuleValidationError(
                "example.com",
                domains,
                "domain:example.com",
                AppLanguage.EN,
            ),
        )
    }

    @Test
    fun loggingTogglesOnlyChangeRequestedFields() {
        val current = AppUiState(
            advancedSettings = AdvancedSettings(
                connectionLogsEnabled = true,
                errorLogsEnabled = false,
                anonymousLogsEnabled = true,
            ),
        )

        val allOn = AdvancedSettingsStateReducer.setAllLogging(current, true)
        val allOnWithoutAutoSend = AdvancedSettingsStateReducer.setAllLogging(
            current.copy(
                advancedSettings = current.advancedSettings.copy(anonymousLogsEnabled = false),
            ),
            true,
        )
        val anonymousOff = AdvancedSettingsStateReducer.setAnonymousLogs(current, false)

        assertEquals(true, allOn.advancedSettings.connectionLogsEnabled)
        assertEquals(true, allOn.advancedSettings.errorLogsEnabled)
        assertEquals(true, allOn.advancedSettings.anonymousLogsEnabled)
        assertEquals(false, allOnWithoutAutoSend.advancedSettings.anonymousLogsEnabled)
        assertEquals(false, anonymousOff.advancedSettings.anonymousLogsEnabled)
    }

    @Test
    fun autoEndpointSelectionSwitchesBetweenAutoAndManualState() {
        val current = AppUiState(
            profile = VlessProfile(endpointCode = "xhttp-a", uuid = "runtime-user"),
            advancedSettings = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.AUTO),
            endpointOptions = listOf(
                endpointOption("xhttp-a", security = "reality", transport = "xhttp", mode = "stream-up"),
                endpointOption("hy-a", security = "tls", transport = "hysteria", mode = null).copy(proxyType = "hysteria"),
            ),
        )

        val manual = AdvancedSettingsStateReducer.setAutoEndpointSelection(current, false)
        val auto = AdvancedSettingsStateReducer.setAutoEndpointSelection(manual, true)

        assertEquals(EndpointSelectionMode.MANUAL, manual.advancedSettings.endpointSelectionMode)
        assertEquals(VpnProtocol.REALITY, manual.advancedSettings.protocol)
        assertEquals("xhttp-a", manual.advancedSettings.manualEndpointCode)
        assertEquals("reality|xhttp|stream-up", manual.advancedSettings.manualEndpointGroupKey)
        assertEquals("", manual.profile.uuid)
        assertEquals(EndpointSelectionMode.AUTO, auto.advancedSettings.endpointSelectionMode)
        assertEquals(VpnProtocol.AUTO, auto.advancedSettings.protocol)
        assertEquals("", auto.advancedSettings.manualEndpointCode)
        assertEquals("", auto.advancedSettings.manualEndpointGroupKey)
    }

    @Test
    fun switchingOffAutoDoesNotSelectARetiredCachedEndpoint() {
        val supported = endpointOption("xhttp", "reality", "xhttp", "stream-up")
        val retired = supported.copy(code = "packet", transport = "xhttp", transportMode = "packet-up")
        val current = AppUiState(profile = VlessProfile(endpointCode = retired.code),
            endpointOptions = listOf(retired, supported))
        val manual = AdvancedSettingsStateReducer.setAutoEndpointSelection(current, false)
        assertEquals(supported.code, manual.advancedSettings.manualEndpointCode)
        assertEquals(VpnProtocol.REALITY, manual.advancedSettings.protocol)
    }

    @Test
    fun manualEndpointSelectionUpdatesSettingsAndClearsRuntimeCredentials() {
        val current = AppUiState(
            profile = VlessProfile(endpointCode = "old", uuid = "runtime-user"),
            advancedSettings = AdvancedSettings(endpointSelectionMode = EndpointSelectionMode.AUTO),
        )
        val option = endpointOption("hy-a", security = "tls", transport = "hysteria", mode = null).copy(proxyType = "hysteria")

        val selected = AdvancedSettingsStateReducer.selectManualEndpoint(current, option)

        assertEquals(EndpointSelectionMode.MANUAL, selected.advancedSettings.endpointSelectionMode)
        assertEquals(VpnProtocol.TLS, selected.advancedSettings.protocol)
        assertEquals("hy-a", selected.advancedSettings.manualEndpointCode)
        assertEquals("tls|hysteria|", selected.advancedSettings.manualEndpointGroupKey)
        assertEquals("hy-a", selected.profile.endpointCode)
        assertEquals("", selected.profile.uuid)
    }

    @Test
    fun filterModeAndSecurityTogglesArePureStateUpdates() {
        val current = AppUiState()

        val onlySelected = AdvancedSettingsStateReducer.setFilterMode(current, AppFilterMode.ONLY_SELECTED)
        val biometricOff = AdvancedSettingsStateReducer.setBiometric(current, false)
        val loginAlertsOff = AdvancedSettingsStateReducer.setLoginAlerts(current, false)
        val protectNewDevicesOff = AdvancedSettingsStateReducer.setProtectNewDevices(current, false)

        assertEquals(AppFilterMode.ONLY_SELECTED, onlySelected.filterMode)
        assertEquals(false, biometricOff.securitySettings.biometricEnabled)
        assertEquals(false, loginAlertsOff.securitySettings.loginAlertsEnabled)
        assertEquals(false, protectNewDevicesOff.securitySettings.protectNewDevices)
    }

    private fun endpointOption(
        code: String,
        security: String,
        transport: String,
        mode: String?,
    ): VpnEndpointOption =
        VpnEndpointOption(
            code = code,
            locationCode = "lv1",
            host = "$code.example.com",
            port = 443,
            proxyType = "vless",
            transport = transport,
            transportMode = mode.orEmpty(),
            security = security,
        )
}
