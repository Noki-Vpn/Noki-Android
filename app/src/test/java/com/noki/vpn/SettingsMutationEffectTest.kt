package com.noki.vpn

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.vpn.AppVpnService
import com.noki.vpn.vpn.AppVpnServiceCommandTest.InMemoryAndroidKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.security.Provider
import java.security.Security
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsMutationEffectTest {
    @Test fun realRepositoryKeepsTheRestoredGeositeToggleAcrossEncryptedReloads() {
        val app = RuntimeEnvironment.getApplication()
        val codec = com.noki.vpn.data.StoredSettingsCodec { DefaultStoredSettingsFactory.create() }
        val preset = com.noki.vpn.data.DomainRulePolicy.RUSSIAN_RESOURCES_RULE
        val original = DefaultStoredSettingsFactory.create().let { it.copy(
            advancedSettings = it.advancedSettings.copy(ruRelayEnabled = false, bypassDomains = listOf("domain:custom.example"))) }
        val raw = org.json.JSONObject(codec.encode(original)).apply { remove("geositeRuleVersion") }.toString()
        val preferences = app.getSharedPreferences(com.noki.vpn.data.SETTINGS_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putString("settings_json", raw).commit()
        val first = com.noki.vpn.data.SettingsRepository(app).load()
        org.junit.Assert.assertTrue(preset in first.advancedSettings.bypassDomains)
        org.junit.Assert.assertFalse(first.advancedSettings.ruRelayEnabled)
        org.junit.Assert.assertFalse(preferences.contains("settings_json"))
        org.junit.Assert.assertTrue(preferences.contains(com.noki.vpn.data.SETTINGS_ENCRYPTED_KEY))
        val off = AdvancedSettingsStateReducer.removeBypassDomain(AppUiState(advancedSettings = first.advancedSettings), preset)
        val savedOff = com.noki.vpn.data.SettingsRepository(app).updateSettings { it.copy(advancedSettings = off.advancedSettings) }
        repeat(3) { assertEquals(savedOff, com.noki.vpn.data.SettingsRepository(app).load()) }
        val on = AdvancedSettingsStateReducer.addBypassDomain(off, preset)
        val savedOn = com.noki.vpn.data.SettingsRepository(app).updateSettings { it.copy(advancedSettings = on.advancedSettings) }
        assertEquals(savedOn, com.noki.vpn.data.SettingsRepository(app).load())
        org.junit.Assert.assertTrue(preset in savedOn.advancedSettings.bypassDomains)
        org.junit.Assert.assertFalse(savedOn.advancedSettings.ruRelayEnabled)
    }

    @Test fun realRepositoryPersistsTheUpgradeThenKeepsAnExplicitDisableAcrossReloads() {
        val app = RuntimeEnvironment.getApplication()
        val codec = com.noki.vpn.data.StoredSettingsCodec { DefaultStoredSettingsFactory.create() }
        val pair = com.noki.vpn.data.MultiHopSettings(true,
            com.noki.vpn.data.HopSelection(com.noki.vpn.data.ServerSelectionMode.SERVER, nodeId = "entry"),
            com.noki.vpn.data.HopSelection(com.noki.vpn.data.ServerSelectionMode.SERVER, nodeId = "exit"))
        val original = DefaultStoredSettingsFactory.create().let { it.copy(
            userProfile = it.userProfile.copy(selectedPlanTier = "plus"),
            advancedSettings = it.advancedSettings.copy(ruRelayEnabled = false, multiHop = pair, youtubeNoAdsEnabled = true)) }
        val raw = org.json.JSONObject(codec.encode(original)).put("ruRelayDefaultsVersion", 3).toString()
        val preferences = app.getSharedPreferences(com.noki.vpn.data.SETTINGS_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().putString("settings_json", raw).commit()
        val first = com.noki.vpn.data.SettingsRepository(app).load()
        org.junit.Assert.assertTrue(first.advancedSettings.ruRelayEnabled)
        assertEquals(pair.copy(enabled = false), first.advancedSettings.multiHop)
        org.junit.Assert.assertTrue(first.advancedSettings.youtubeNoAdsEnabled)
        org.junit.Assert.assertFalse(preferences.contains("settings_json"))
        org.junit.Assert.assertTrue(preferences.contains(com.noki.vpn.data.SETTINGS_ENCRYPTED_KEY))
        assertEquals(first, com.noki.vpn.data.SettingsRepository(app).load())
        val disabled = com.noki.vpn.data.SettingsRepository(app).updateSettings {
            it.copy(advancedSettings = it.advancedSettings.copy(ruRelayEnabled = false))
        }
        repeat(3) { assertEquals(disabled, com.noki.vpn.data.SettingsRepository(app).load()) }
        org.junit.Assert.assertFalse(disabled.advancedSettings.ruRelayEnabled)
    }
    @Test fun ruPresentationMeasuresEachForeignNodeInsteadOfTheEntryOrYoutubeTunnel() = kotlinx.coroutines.runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val runtime = AppUiRuntime(app, SavedStateHandle(), CoroutineScope(Job().apply { cancel() }))
        val nodes = listOf(com.noki.vpn.data.RuForeignNode("first", "First", "FR", probeHost = "first.example", probePort = 25001),
            com.noki.vpn.data.RuForeignNode("second", "Second", "NL", probeHost = "second.example", probePort = 25002))
        val called = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sampler = com.noki.vpn.data.ClientLatencySampler(tcpConnect = { host, port, _ ->
            called += "$host:$port"
            if (host == "first.example") 21 else 48
        }, tcpAttempts = 1)
        AppUiRuntime::class.java.getDeclaredField("clientLatencySampler").apply { isAccessible = true }.set(runtime, sampler)
        for (youtube in listOf(false, true)) {
            runtime.uiState = AppUiState(ruForeignNodes = nodes, advancedSettings = com.noki.vpn.data.AdvancedSettings(
                ruRelayEnabled = true, youtubeNoAdsEnabled = youtube))
            val samples = runtime.measureClientLatencies(emptyList())
            val updated = BackendSyncCoordinator.withCachedClientLatencies(runtime.uiState, samples)
            assertEquals(listOf(21, 48), updated.ruForeignNodes.map { it.latencyMs })
        }
        assertEquals(setOf("first.example:25001", "second.example:25002"), called.toSet())
    }
    @Before
    fun provideAndroidKeyStore() {
        Security.addProvider(object : Provider("NokiSettingsEffectTest", 1.0, "Test-only Android keystore") {
            init { put("KeyStore.AndroidKeyStore", InMemoryAndroidKeyStore::class.java.name) }
        })
    }

    @After
    fun removeAndroidKeyStore() { Security.removeProvider("NokiSettingsEffectTest") }

    @Test
    fun `persisted mutation returns requested runtime effect`() {
        val coordinator = SettingsMutationCoordinator(EffectSettingsStore())

        val result = coordinator.persistUiFields(AppUiState(), SettingsEffect.ApplyRuntimeSettings)

        assertEquals(SettingsEffect.ApplyRuntimeSettings, result.effect)
    }

    @Test
    fun changedAppFilterModePersistsBeforeRestartingAnActiveVpnOnlyOnce() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = AppUiRuntime(app, SavedStateHandle(), CoroutineScope(Job().apply { cancel() }))
        for (connection in listOf(VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING)) {
            for (before in AppFilterMode.entries) for (after in AppFilterMode.entries.filter { it != before }) {
                runtime.uiState = AppUiState(connectionState = connection, filterMode = before,
                    selectedPackages = setOf("example.app"))
                runtime.updateFilterMode(after)
                assertEquals(after, runtime.repository.load().filterMode)
                assertEquals(setOf("example.app"), runtime.repository.load().selectedPackages)
                assertEquals(AppVpnService.restartIntent(app).action, shadowOf(app).nextStartedService?.action)
                runtime.updateFilterMode(after)
                assertNull(shadowOf(app).nextStartedService)
            }
        }
    }

    @Test
    fun changedAppFilterModeWhileOfflineDoesNotConnectOrReplaceAnEmptySelection() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = AppUiRuntime(app, SavedStateHandle(), CoroutineScope(Job().apply { cancel() }))
        for (connection in listOf(VpnConnectionState.DISCONNECTED, VpnConnectionState.FAILED)) {
            runtime.uiState = AppUiState(connectionState = connection, filterMode = AppFilterMode.ALL_APPS)
            runtime.updateFilterMode(AppFilterMode.ONLY_SELECTED)
            assertEquals(AppFilterMode.ONLY_SELECTED, runtime.repository.load().filterMode)
            assertEquals(emptySet<String>(), runtime.repository.load().selectedPackages)
            assertNull(shadowOf(app).nextStartedService)
        }
    }
}

private class EffectSettingsStore : AtomicStoredSettingsStore {
    private var value = DefaultStoredSettingsFactory.create()
    override fun load(): StoredSettings = value
    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
        val (updated, result) = transform(value)
        value = updated
        return result
    }
}
