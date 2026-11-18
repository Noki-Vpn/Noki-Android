package com.noki.vpn

import com.noki.vpn.data.AccentPalette
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.GlassMode
import com.noki.vpn.data.HomeLayoutVariant
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.InMemoryAtomicStoredSettingsStore
import com.noki.vpn.data.SecuritySettings
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VlessProfile
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.vpn.VpnSettingsTransactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMutationCoordinatorTest {
    @Test
    fun gamingIntentClearsCachedProfilesAndInvalidatesPreparationEvenAfterTogglingBack() {
        val baseline = authenticatedSettings().copy(
            profile = VlessProfile(endpointCode = "old", uuid = "old"),
            endpointOptions = listOf(VpnEndpointOption(code = "old")),
        )
        val coordinator = SettingsMutationCoordinator(InMemoryAtomicStoredSettingsStore(baseline))
        val enabled = coordinator.persistUiFields(AdvancedSettingsStateReducer.setGamingModeEnabled(stateFrom(baseline), true))
        assertEquals("", enabled.profile.endpointCode)
        assertEquals(emptyList<VpnEndpointOption>(), enabled.endpointOptions)
        assertEquals(true, VpnSettingsTransactionPolicy.candidateBecameStale(baseline, enabled))
        val disabled = coordinator.persistUiFields(AdvancedSettingsStateReducer.setGamingModeEnabled(stateFrom(enabled), false))
        assertEquals(baseline.advancedSettings, disabled.advancedSettings)
        assertEquals(baseline.ruRelaySelectionRevision + 2L, disabled.ruRelaySelectionRevision)
        assertEquals(true, VpnSettingsTransactionPolicy.candidateBecameStale(baseline, disabled))
    }

    @Test
    fun countryNodeAndAutoChangesKeepTheExplicitTransportChoice() {
        val advanced = AdvancedSettings(protocol = VpnProtocol.NOKINV,
            endpointSelectionMode = EndpointSelectionMode.MANUAL, manualEndpointCode = "lv-nokinv",
            manualEndpointGroupKey = "lv-2|tls|nokinv||100")
        val store = InMemoryAtomicStoredSettingsStore(authenticatedSettings().copy(advancedSettings = advanced))
        val coordinator = SettingsMutationCoordinator(store)
        for (mode in com.noki.vpn.data.ServerSelectionMode.entries) {
            val saved = coordinator.persistServerSelection("PL", mode, "pl-1")
            assertEquals(VpnProtocol.NOKINV, saved.advancedSettings.protocol)
            assertEquals(EndpointSelectionMode.MANUAL, saved.advancedSettings.endpointSelectionMode)
            assertEquals("tls|nokinv|quic", saved.advancedSettings.manualEndpointGroupKey)
            assertEquals("", saved.advancedSettings.manualEndpointCode)
        }
    }

    @Test
    fun ruOffOnOffInvalidatesAnInFlightCandidateDespiteMatchingFinalSelector() {
        val baseline = authenticatedSettings()
        val store = InMemoryAtomicStoredSettingsStore(baseline)
        val coordinator = SettingsMutationCoordinator(store)
        val enabled = coordinator.persistUiFields(stateFrom(baseline).copy(
            advancedSettings = baseline.advancedSettings.copy(ruRelayEnabled = true),
        ))
        val disabled = coordinator.persistUiFields(stateFrom(enabled).copy(
            advancedSettings = enabled.advancedSettings.copy(ruRelayEnabled = false),
        ))

        assertEquals(baseline.advancedSettings.ruRelaySelection, disabled.advancedSettings.ruRelaySelection)
        assertEquals(baseline.ruRelaySelectionRevision + 2, disabled.ruRelaySelectionRevision)
        assertEquals(true, VpnSettingsTransactionPolicy.candidateBecameStale(baseline, disabled))
    }

    @Test
    fun multiHopIntentChangeClearsOldRuntimeAndKeepsDirectPreference() {
        val latest = authenticatedSettings().copy(
            profile = VlessProfile(endpointCode = "direct", uuid = "old-runtime"),
            endpointOptions = listOf(VpnEndpointOption(code = "direct")),
        )
        val store = InMemoryAtomicStoredSettingsStore(latest)
        val selection = MultiHopSettings(true,
            HopSelection(com.noki.vpn.data.ServerSelectionMode.COUNTRY, countryCode = "RU"),
            HopSelection(com.noki.vpn.data.ServerSelectionMode.COUNTRY, countryCode = "LV"))
        val state = stateFrom(latest).copy(advancedSettings = latest.advancedSettings.copy(multiHop = selection))
        val saved = SettingsMutationCoordinator(store).persistUiFields(state)
        assertEquals(selection, saved.advancedSettings.multiHop)
        assertEquals("", saved.profile.uuid)
        assertEquals(emptyList<VpnEndpointOption>(), saved.endpointOptions)
        assertEquals(latest.userProfile.selectedCountryCode, saved.userProfile.selectedCountryCode)
    }

    @Test
    fun ruModeAwayAndBackInvalidatesTheEarlierCandidateWithTheSameFinalSelection() {
        val chain = MultiHopSettings(true,
            HopSelection(com.noki.vpn.data.ServerSelectionMode.SERVER,
                nodeId = "11111111-1111-4111-8111-111111111111"),
            HopSelection(com.noki.vpn.data.ServerSelectionMode.SERVER,
                nodeId = "22222222-2222-4222-8222-222222222222"))
        val defaults = authenticatedSettings()
        val baseline = defaults.copy(advancedSettings = defaults.advancedSettings.copy(
            ruRelayEnabled = true, multiHop = chain.copy(enabled = false)))
        val coordinator = SettingsMutationCoordinator(InMemoryAtomicStoredSettingsStore(baseline))
        val changed = coordinator.persistUiFields(stateFrom(baseline).copy(
            advancedSettings = baseline.advancedSettings.copy(ruRelayEnabled = false),
        ))
        val restored = coordinator.persistUiFields(stateFrom(changed).copy(
            advancedSettings = baseline.advancedSettings.copy(multiHop = chain),
        ))
        assertEquals(chain.copy(enabled = false), restored.advancedSettings.multiHop)
        assertEquals(baseline.ruRelaySelectionRevision + 2, restored.ruRelaySelectionRevision)
        assertEquals(true, VpnSettingsTransactionPolicy.candidateBecameStale(baseline, restored))
    }

    @Test
    fun nodeAndAutoSelectionInvalidateRuntimeWithoutErasingManualCountry() {
        val latest = authenticatedSettings().copy(
            userProfile = UserProfile(selectedCountryCode = "LV"),
            profile = VlessProfile(uuid = "runtime", endpointCode = "old", serverName = "old-sni"),
        )
        val coordinator = SettingsMutationCoordinator(InMemoryAtomicStoredSettingsStore(latest))
        val manual = coordinator.persistServerSelection("de", com.noki.vpn.data.ServerSelectionMode.SERVER, "node-a")
        assertEquals("DE", manual.userProfile.selectedCountryCode)
        assertEquals("node-a", manual.userProfile.selectedNodeId)
        assertEquals("", manual.profile.serverName)
        val automatic = coordinator.persistServerSelection("", com.noki.vpn.data.ServerSelectionMode.AUTO)
        assertEquals("DE", automatic.userProfile.selectedCountryCode)
        assertEquals("", automatic.userProfile.selectedNodeId)
        assertEquals(com.noki.vpn.data.ServerSelectionMode.AUTO, automatic.userProfile.serverSelectionMode)
    }

    @Test
    fun profileChangingSettingsMutationsDeriveFromLatestProfile() {
        val latest = authenticatedSettings().copy(
            profile = VlessProfile(
                endpointCode = "lv-endpoint",
                transport = "tcp",
                host = "latest.example",
                uuid = "latest-runtime-uuid",
                security = "reality",
                serverName = "latest-sni",
            ),
            endpointOptions = listOf(VpnEndpointOption(code = "lv-endpoint", locationCode = "lv")),
            backendAccessToken = "latest-access",
            backendRefreshToken = "latest-refresh",
            backendDeviceId = "latest-device",
        )
        val store = InMemoryAtomicStoredSettingsStore(latest)
        val coordinator = SettingsMutationCoordinator(store)
        val staleState = stateFrom(authenticatedSettings())

        val protocolState = AdvancedSettingsStateReducer.changeProtocol(staleState, VpnProtocol.TLS)
        val protocolSaved = coordinator.persistProtocolChange(protocolState, VpnProtocol.TLS)
        assertEquals("", protocolSaved.profile.endpointCode)
        assertEquals("", protocolSaved.profile.uuid)
        assertOwnershipPreserved("protocol", latest, protocolSaved)

        store.replace(latest)
        val autoState = AdvancedSettingsStateReducer.setAutoEndpointSelection(staleState, true)
        val autoSaved = coordinator.persistAutoEndpointSelection(autoState)
        assertEquals("lv-endpoint", autoSaved.profile.endpointCode)
        assertEquals("", autoSaved.profile.uuid)
        assertEquals("latest-sni", autoSaved.profile.serverName)
        assertOwnershipPreserved("auto endpoint", latest, autoSaved)

        store.replace(latest)
        val manualOption = VpnEndpointOption(
            code = "de-manual",
            locationCode = "de",
            host = "manual.example",
            port = 8443,
            transport = "tcp",
            security = "tls",
        )
        val manualState = AdvancedSettingsStateReducer.selectManualEndpoint(staleState, manualOption)
        val manualSaved = coordinator.persistManualEndpointSelection(manualState, manualOption)
        assertEquals("de-manual", manualSaved.profile.endpointCode)
        assertEquals("manual.example", manualSaved.profile.host)
        assertEquals("8443", manualSaved.profile.port)
        assertEquals("", manualSaved.profile.uuid)
        assertOwnershipPreserved("manual endpoint", latest, manualSaved)
    }

    @Test
    fun serverSelectionDerivesRuntimeProfileFromLatestStoreAtomically() {
        val latest = authenticatedSettings().copy(
            profile = VlessProfile(endpointCode = "lv-endpoint", uuid = "latest-runtime-uuid"),
            userProfile = UserProfile(selectedCountryCode = "LV", selectedServerCode = "lv-1"),
            endpointOptions = listOf(VpnEndpointOption(code = "different-endpoint", locationCode = "lv")),
            backendAccessToken = "latest-access",
            backendRefreshToken = "latest-refresh",
            backendDeviceId = "latest-device",
        )
        val store = InMemoryAtomicStoredSettingsStore(latest)
        val coordinator = SettingsMutationCoordinator(store)

        val saved = coordinator.persistServerSelection("de")

        assertEquals("DE", saved.userProfile.selectedCountryCode)
        assertEquals("", saved.userProfile.selectedServerCode)
        assertEquals(emptyList<VpnEndpointOption>(), saved.endpointOptions)
        assertEquals("", saved.profile.endpointCode)
        assertEquals("", saved.profile.uuid)
        assertEquals("latest-access", saved.backendAccessToken)
        assertEquals("latest-refresh", saved.backendRefreshToken)
        assertEquals("latest-device", saved.backendDeviceId)
    }

    @Test
    fun serviceRotationThenLanguageMutationCannotRestoreOldTokens() {
        val initial = authenticatedSettings()
        val store = InMemoryAtomicStoredSettingsStore(initial)
        val coordinator = SettingsMutationCoordinator(store)
        store.updateSettings { latest ->
            latest.copy(
                backendAccessToken = "rotated-access",
                backendRefreshToken = "rotated-refresh",
                backendAccessTokenExpiresInSeconds = 3_600,
                backendRefreshExpiresAt = "rotated-expiry",
            )
        }

        coordinator.persistUiFields(
            stateFrom(initial).copy(
                personalizationSettings = initial.personalizationSettings.copy(language = AppLanguage.RU),
            ),
        )

        val saved = store.load()
        assertEquals(AppLanguage.RU, saved.personalizationSettings.language)
        assertEquals("rotated-access", saved.backendAccessToken)
        assertEquals("rotated-refresh", saved.backendRefreshToken)
        assertEquals(3_600L, saved.backendAccessTokenExpiresInSeconds)
        assertEquals("rotated-expiry", saved.backendRefreshExpiresAt)
    }

    @Test
    fun everyUiSettingsMutationPreservesLatestProfileSessionDeviceAndAuthFields() {
        val initial = authenticatedSettings()
        val latest = initial.copy(
            profile = VlessProfile(endpointCode = "service-profile", uuid = "service-uuid"),
            userProfile = UserProfile(username = "latest-user", email = "latest@example.com"),
            endpointOptions = listOf(VpnEndpointOption(code = "latest-endpoint")),
            isAuthenticated = true,
            backendAccessToken = "latest-access",
            backendRefreshToken = "latest-refresh",
            backendAccessTokenExpiresInSeconds = 7_200,
            backendRefreshExpiresAt = "latest-expiry",
            backendDeviceKey = "latest-device-key",
            backendDeviceId = "latest-device-id",
            backendDeviceAccessRole = "invited",
        )
        val store = InMemoryAtomicStoredSettingsStore(initial)
        val coordinator = SettingsMutationCoordinator(store)
        val base = stateFrom(initial)
        val mutations = listOf(
            "filter mode" to base.copy(filterMode = AppFilterMode.ONLY_SELECTED),
            "selected packages" to base.copy(selectedPackages = setOf("com.example.app")),
            "language" to base.copy(
                personalizationSettings = base.personalizationSettings.copy(language = AppLanguage.RU),
            ),
            "accent" to base.copy(
                personalizationSettings = base.personalizationSettings.copy(accentPalette = AccentPalette.CYAN),
            ),
            "home layout" to base.copy(
                personalizationSettings = base.personalizationSettings.copy(homeLayoutVariant = HomeLayoutVariant.MAIN_V2),
            ),
            "simple mode" to base.copy(
                personalizationSettings = base.personalizationSettings.copy(glassMode = GlassMode.SIMPLE),
            ),
            "security" to base.copy(
                securitySettings = SecuritySettings(
                    biometricEnabled = false,
                    loginAlertsEnabled = false,
                    protectNewDevices = false,
                ),
            ),
            "advanced" to base.copy(
                advancedSettings = AdvancedSettings(
                    protocol = VpnProtocol.TLS,
                    endpointSelectionMode = EndpointSelectionMode.MANUAL,
                    manualEndpointCode = "manual-endpoint",
                    manualEndpointGroupKey = "manual-group",
                    connectionLogsEnabled = false,
                    errorLogsEnabled = false,
                    anonymousLogsEnabled = false,
                    alwaysOnDomains = listOf("always.example"),
                    bypassDomains = listOf("bypass.example"),
                ),
            ),
        )

        mutations.forEach { (name, state) ->
            store.replace(latest)

            val saved = coordinator.persistUiFields(state)

            assertEquals("$name profile", latest.profile, saved.profile)
            assertEquals("$name user profile", latest.userProfile, saved.userProfile)
            assertEquals("$name endpoint options", latest.endpointOptions, saved.endpointOptions)
            assertEquals("$name authenticated", latest.isAuthenticated, saved.isAuthenticated)
            assertEquals("$name access token", latest.backendAccessToken, saved.backendAccessToken)
            assertEquals("$name refresh token", latest.backendRefreshToken, saved.backendRefreshToken)
            assertEquals("$name access expiry", latest.backendAccessTokenExpiresInSeconds, saved.backendAccessTokenExpiresInSeconds)
            assertEquals("$name refresh expiry", latest.backendRefreshExpiresAt, saved.backendRefreshExpiresAt)
            assertEquals("$name device key", latest.backendDeviceKey, saved.backendDeviceKey)
            assertEquals("$name device id", latest.backendDeviceId, saved.backendDeviceId)
            assertEquals("$name device role", latest.backendDeviceAccessRole, saved.backendDeviceAccessRole)
            assertEquals("$name filter mode persisted", state.filterMode, saved.filterMode)
            assertEquals("$name packages persisted", state.selectedPackages, saved.selectedPackages)
            assertEquals("$name personalization persisted", state.personalizationSettings, saved.personalizationSettings)
            assertEquals("$name security persisted", state.securitySettings, saved.securitySettings)
            assertEquals("$name advanced persisted", state.advancedSettings, saved.advancedSettings)
        }
    }

    private fun authenticatedSettings(): StoredSettings {
        val defaults = DefaultStoredSettingsFactory.create()
        return defaults.copy(
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false),
            profile = VlessProfile(endpointCode = "old-profile", uuid = "old-uuid"),
            userProfile = UserProfile(username = "old-user", email = "old@example.com"),
            endpointOptions = listOf(VpnEndpointOption(code = "old-endpoint")),
            isAuthenticated = true,
            backendAccessToken = "old-access",
            backendRefreshToken = "old-refresh",
            backendAccessTokenExpiresInSeconds = 60,
            backendRefreshExpiresAt = "old-expiry",
            backendDeviceKey = "old-device-key",
            backendDeviceId = "old-device-id",
            backendDeviceAccessRole = "owner",
        )
    }

    private fun stateFrom(settings: StoredSettings): AppUiState =
        AppUiState(
            profile = settings.profile,
            filterMode = settings.filterMode,
            selectedPackages = settings.selectedPackages,
            userProfile = settings.userProfile,
            personalizationSettings = settings.personalizationSettings,
            securitySettings = settings.securitySettings,
            advancedSettings = settings.advancedSettings,
            endpointOptions = settings.endpointOptions,
            isAuthenticated = settings.isAuthenticated,
        )

    private fun assertOwnershipPreserved(
        name: String,
        expected: StoredSettings,
        actual: StoredSettings,
    ) {
        assertEquals("$name access", expected.backendAccessToken, actual.backendAccessToken)
        assertEquals("$name refresh", expected.backendRefreshToken, actual.backendRefreshToken)
        assertEquals("$name device", expected.backendDeviceId, actual.backendDeviceId)
    }
}
