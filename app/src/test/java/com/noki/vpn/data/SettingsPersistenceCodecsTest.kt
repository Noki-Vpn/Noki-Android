package com.noki.vpn.data

import com.noki.vpn.BuildConfig
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsPersistenceCodecsTest {
    @Test fun geositeUpgradePreservesEffectiveOnOnceAndTheFollowingOffWithoutResettingRu() {
        val original = defaults.copy(ruRelaySelectionRevision = 17,
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false,
                bypassDomains = listOf("domain:custom.example")))
        var raw = org.json.JSONObject(settingsCodec.encode(original)).apply { remove("geositeRuleVersion") }.toString()
        var migrations = 0
        val migrated = settingsCodec.decode(raw) { migrations++; raw = settingsCodec.encode(it) }
        assertEquals(1, migrations)
        assertEquals(listOf("domain:custom.example", DomainRulePolicy.RUSSIAN_RESOURCES_RULE), migrated.advancedSettings.bypassDomains)
        assertFalse(migrated.advancedSettings.ruRelayEnabled)
        assertEquals(17L, migrated.ruRelaySelectionRevision)
        assertEquals(1, org.json.JSONObject(raw).getInt("geositeRuleVersion"))
        val disabled = migrated.copy(advancedSettings = migrated.advancedSettings.copy(bypassDomains = listOf("domain:custom.example")))
        repeat(3) {
            raw = settingsCodec.encode(disabled)
            assertEquals(disabled, settingsCodec.decode(raw) { error("Saved OFF must survive restart") })
        }
        val enabled = disabled.copy(advancedSettings = DomainRulePolicy.addBypass(disabled.advancedSettings, DomainRulePolicy.RUSSIAN_RESOURCES_RULE))
        assertEquals(enabled, settingsCodec.decode(settingsCodec.encode(enabled)))
    }

    @Test fun geositeMigrationRetainsAPositiveLegacyExplicitVpnChoice() {
        val explicit = defaults.copy(advancedSettings = defaults.advancedSettings.copy(
            alwaysOnDomains = listOf(DomainRulePolicy.RUSSIAN_RESOURCES_RULE), bypassDomains = emptyList()))
        val raw = org.json.JSONObject(settingsCodec.encode(explicit)).apply { remove("geositeRuleVersion") }.toString()
        val restored = settingsCodec.decode(raw)
        assertEquals(explicit.advancedSettings.alwaysOnDomains, restored.advancedSettings.alwaysOnDomains)
        assertTrue(restored.advancedSettings.bypassDomains.isEmpty())
    }

    @Test fun upgradeEnablesRuOnceEvenForAnExplicitFalseAndPreservesDisabledHopSelectors() {
        val pair = MultiHopSettings(true, HopSelection(ServerSelectionMode.SERVER, nodeId = "entry"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "exit"))
        val original = defaults.copy(ruRelaySelectionRevision = 17,
            isAuthenticated = true, backendAccessToken = "retained-access",
            userProfile = defaults.userProfile.copy(selectedPlanTier = "plus"),
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false, multiHop = pair,
                gamingModeEnabled = true, youtubeNoAdsEnabled = true))
        var raw = org.json.JSONObject(settingsCodec.encode(original)).put("ruRelayDefaultsVersion", 3).toString()
        val migrated = settingsCodec.decode(raw) { raw = settingsCodec.encode(it) }
        assertTrue(migrated.advancedSettings.ruRelayEnabled)
        assertFalse(migrated.advancedSettings.multiHop.enabled)
        assertFalse(migrated.advancedSettings.gamingModeEnabled)
        assertEquals(pair.copy(enabled = false), migrated.advancedSettings.multiHop)
        assertTrue(migrated.advancedSettings.youtubeNoAdsEnabled)
        assertEquals("retained-access", migrated.backendAccessToken)
        assertEquals(18L, migrated.ruRelaySelectionRevision)
        assertEquals(4, org.json.JSONObject(raw).getInt("ruRelayDefaultsVersion"))
        val disabled = migrated.copy(advancedSettings = migrated.advancedSettings.copy(ruRelayEnabled = false))
        val saved = settingsCodec.encode(disabled)
        repeat(3) { assertEquals(disabled, settingsCodec.decode(saved) { error("Already migrated user choice must not reset") }) }
    }

    @Test fun freshReleaseDefaultsToRuRelay() {
        assertTrue(DefaultStoredSettingsFactory.create(isFreshInstall = true).advancedSettings.ruRelayEnabled)
        assertTrue(settingsCodec.decode(null).advancedSettings.ruRelayEnabled)
    }
    private val defaults = DefaultStoredSettingsFactory.create(Locale.US)
    private val settingsCodec = StoredSettingsCodec { defaults }

    @Test fun naiveUsernameAndOpaquePasswordRoundTripInExistingSettings() {
        val profile = defaults.profile.copy(proxyType = "naive", transport = "naive", security = "tls",
            vpnUsername = "test-user", uuid = "opaque-password")
        val stored = defaults.copy(profile = profile,
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false, protocol = VpnProtocol.NAIVE))
        assertEquals(profile, settingsCodec.decode(settingsCodec.encode(stored)).profile)
        assertEquals("", settingsCodec.decode("{}").profile.vpnUsername)
    }

    @Test
    fun explicitProtocolRestoresManualModeAfterAnOlderServerSelectionReset() {
        val advanced = AdvancedSettings(protocol = VpnProtocol.NOKINV,
            endpointSelectionMode = EndpointSelectionMode.AUTO, manualEndpointGroupKey = "lv-2|tls|nokinv||100")
        val restored = settingsCodec.decode(settingsCodec.encode(defaults.copy(advancedSettings = advanced)))
        assertEquals(VpnProtocol.NOKINV, restored.advancedSettings.protocol)
        assertEquals(EndpointSelectionMode.MANUAL, restored.advancedSettings.endpointSelectionMode)
        assertEquals(advanced.manualEndpointGroupKey, restored.advancedSettings.manualEndpointGroupKey)
    }

    @Test
    fun `cached Free YouTube setting is disabled before any primary route is configured`() {
        for (advanced in listOf(
            AdvancedSettings(ruRelayEnabled = false, youtubeNoAdsEnabled = true),
            AdvancedSettings(ruRelayEnabled = true, youtubeNoAdsEnabled = true),
            AdvancedSettings(ruRelayEnabled = true, youtubeNoAdsEnabled = true, multiHop = MultiHopSettings(true,
                HopSelection(ServerSelectionMode.SERVER, nodeId = "entry"), HopSelection(ServerSelectionMode.SERVER, nodeId = "exit"))),
        )) {
            val saved = defaults.copy(advancedSettings = advanced,
                userProfile = defaults.userProfile.copy(selectedPlanCodeRaw = "free-plus", trialTrafficRemainingGb = 3.0))
            val legacy = org.json.JSONObject(settingsCodec.encode(saved)).put("youtubeDirectDpiEnabled", true).toString()
            val restored = settingsCodec.decode(legacy)
            assertFalse(restored.advancedSettings.youtubeNoAdsEnabled)
            assertFalse(org.json.JSONObject(settingsCodec.encode(saved)).getBoolean("youtubeDirectDpiEnabled"))
            assertEquals(RuntimeProfilePolicy.normalizeRouteModes(advanced).copy(youtubeNoAdsEnabled = false),
                restored.advancedSettings)
        }
    }

    @Test
    fun `YouTube choice preserves the historical storage key without runtime credentials`() {
        assertFalse(settingsCodec.decode(null).advancedSettings.youtubeNoAdsEnabled)
        assertFalse(settingsCodec.decode("{}").advancedSettings.youtubeNoAdsEnabled)
        assertTrue(settingsCodec.decode("""{"youtubeDirectDpiEnabled":true,"selectedPlanTier":"plus"}""").advancedSettings.youtubeNoAdsEnabled)
        val saved = defaults.copy(
            userProfile = defaults.userProfile.copy(selectedPlanTier = "plus"),
            advancedSettings = defaults.advancedSettings.copy(youtubeNoAdsEnabled = true))
        val encoded = settingsCodec.encode(saved)
        assertEquals(saved, settingsCodec.decode(encoded))
        assertTrue(org.json.JSONObject(encoded).getBoolean("youtubeDirectDpiEnabled"))
        assertFalse(org.json.JSONObject(encoded).has("youtubeNoAdsEnabled"))
    }

    @Test
    fun `malformed stored YouTube credentials clear runtime while preserving auth and user choice`() {
        val saved = defaults.copy(
            profile = defaults.profile.copy(endpointCode = "direct-tls", host = "vpn.example.test", security = "tls",
                serverName = "cdn.example.test", uuid = "22222222-2222-4222-8222-222222222222"),
            isAuthenticated = true, backendAccessToken = "access", backendRefreshToken = "refresh",
            userProfile = defaults.userProfile.copy(username = "retained-user", selectedPlanTier = "plus"),
            endpointOptions = listOf(VpnEndpointOption(code = "direct-tls")),
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false, youtubeNoAdsEnabled = true),
        )
        val damagedPayloads = listOf(org.json.JSONObject().put("uuid", "obsolete-shared-secret"), "invalid").map { legacy ->
            org.json.JSONObject(settingsCodec.encode(saved)).put("youtubeCascade", legacy).toString()
        }
        for (raw in damagedPayloads) {
            val restored = settingsCodec.decode(raw)
            assertEquals(saved.copy(profile = RuntimeProfilePolicy.profileAfterServerSelection(saved.profile)), restored)
            assertTrue(restored.isAuthenticated)
            assertTrue(restored.advancedSettings.youtubeNoAdsEnabled)
            assertEquals("access", restored.backendAccessToken)
            assertEquals("refresh", restored.backendRefreshToken)
            assertEquals("", restored.profile.uuid)
            assertFalse(org.json.JSONObject(settingsCodec.encode(restored)).has("youtubeCascade"))
            assertFalse(RuntimeProfilePolicy.isCachedProfileUsable(restored))
        }
        assertEquals(defaults, settingsCodec.decode(settingsCodec.encode(defaults)))
    }

    @Test
    fun `YouTube admission does not prevent cached primary VPN startup`() {
        val profile = VlessProfile(host = "vpn.example.test", endpointCode = "direct-tls", security = "tls",
            proxyType = "hysteria", transport = "hysteria",
            serverName = "cdn.example.test", uuid = "22222222-2222-4222-8222-222222222222")
        val settings = defaults.copy(profile = profile, endpointOptions = listOf(VpnEndpointOption(code = profile.endpointCode)),
            advancedSettings = AdvancedSettings(ruRelayEnabled = false, youtubeNoAdsEnabled = true))
        assertTrue(RuntimeProfilePolicy.isCachedProfileUsable(settings))
        assertTrue(RuntimeProfilePolicy.isCachedProfileUsable(settings.copy(
            advancedSettings = settings.advancedSettings.copy(youtubeNoAdsEnabled = false))))
    }

    @Test
    fun `pending credentials survive process storage while legacy accounts default complete`() {
        val pending = defaults.copy(
            isAuthenticated = true,
            userProfile = defaults.userProfile.copy(credentialsSetupRequired = true),
        )
        val restored = settingsCodec.decode(settingsCodec.encode(pending))

        assertTrue(restored.isAuthenticated)
        assertTrue(restored.userProfile.credentialsSetupRequired)
        assertFalse(settingsCodec.decode("{}").userProfile.credentialsSetupRequired)
        assertFalse(settingsCodec.decode(settingsCodec.encode(pending.copy(
            userProfile = pending.userProfile.copy(credentialsSetupRequired = false),
        ))).userProfile.credentialsSetupRequired)
    }

    @Test
    fun `RU relay build default preserves explicit choice across storage`() {
        assertEquals(BuildConfig.RU_RELAY_DEFAULT_ENABLED, settingsCodec.decode(null).advancedSettings.ruRelayEnabled)
        assertEquals(BuildConfig.RU_RELAY_DEFAULT_ENABLED, settingsCodec.decode("{}").advancedSettings.ruRelayEnabled)
        assertEquals(BuildConfig.RU_RELAY_DEFAULT_ENABLED,
            DefaultStoredSettingsFactory.create(isFreshInstall = false).advancedSettings.ruRelayEnabled)
        val settings = defaults.copy(advancedSettings = defaults.advancedSettings.copy(
            protocol = VpnProtocol.REALITY,
            ruRelayEnabled = true,
        ))
        val restored = settingsCodec.decode(settingsCodec.encode(settings)).advancedSettings
        assertTrue(restored.ruRelayEnabled)
        assertEquals(VpnProtocol.REALITY, restored.protocol)
        assertEquals(VpnProtocol.WIREGUARD, restored.effectiveProtocol)
        assertEquals(VpnProtocol.REALITY, restored.copy(ruRelayEnabled = false).effectiveProtocol)
        val disabled = settings.copy(advancedSettings = settings.advancedSettings.copy(ruRelayEnabled = false))
        assertFalse(settingsCodec.decode(settingsCodec.encode(disabled)).advancedSettings.ruRelayEnabled)
        val damaged = settingsCodec.decode("""{"protocol":"WIREGUARD","ruRelayEnabled":false,"ruRelayDefaultsVersion":1}""")
        assertTrue(damaged.advancedSettings.ruRelayEnabled)
        assertEquals(VpnProtocol.WIREGUARD, damaged.advancedSettings.effectiveProtocol)
    }

    @Test
    fun manualTransportCannotFallBackThroughALegacySessionWithoutOptions() {
        val settings = defaults.copy(
            profile = defaults.profile.copy(security = "reality", transport = "tcp"),
            endpointOptions = emptyList(),
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false,
                protocol = VpnProtocol.REALITY, endpointSelectionMode = EndpointSelectionMode.MANUAL,
                manualEndpointGroupKey = "lv2|reality|xhttp|stream-up|100"),
        )
        assertEquals("manual_group_mismatch", VpnProfileValidator.rejectionReason(settings))
    }

    @Test
    fun `release upgrade enables RU relay once without resetting account or routing preferences`() {
        val saved = defaults.copy(
            userProfile = defaults.userProfile.copy(selectedPlanTier = "plus"),
            isAuthenticated = true, backendAccessToken = "access", backendRefreshToken = "refresh",
            backendDeviceId = "device-a", ruRelaySelectionRevision = 17L,
            profile = defaults.profile.copy(endpointCode = "direct-tls", host = "vpn.example.test",
                security = "tls", uuid = "22222222-2222-4222-8222-222222222222"),
            selectedPackages = setOf("example.app"),
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false,
                protocol = VpnProtocol.REALITY, endpointSelectionMode = EndpointSelectionMode.MANUAL,
                youtubeNoAdsEnabled = true,
                bypassDomains = listOf("domain:example.test")),
        )
        val legacy = org.json.JSONObject(settingsCodec.encode(saved)).apply {
            put("ruRelayDefaultsVersion", 2)
        }.toString()
        val migrated = settingsCodec.decode(legacy)
        val expected = saved.copy(
            advancedSettings = saved.advancedSettings.copy(ruRelayEnabled = true),
            profile = RuntimeProfilePolicy.profileAfterServerSelection(saved.profile),
            ruRelaySelectionRevision = 18L,
        )

        assertEquals(expected, migrated)
        val persisted = settingsCodec.encode(migrated)
        assertEquals(4, org.json.JSONObject(persisted).getInt("ruRelayDefaultsVersion"))
        assertEquals(migrated, settingsCodec.decode(persisted))

        val disabled = migrated.copy(advancedSettings = migrated.advancedSettings.copy(ruRelayEnabled = false))
        assertEquals(disabled, settingsCodec.decode(settingsCodec.encode(disabled)))
        assertEquals(expected, settingsCodec.decode(org.json.JSONObject(legacy)
            .put("ruRelayDefaultsVersion", 1).toString()))
        var stored = legacy
        assertThrows(IllegalStateException::class.java) {
            settingsCodec.decode(stored) { error("commit failed") }
        }
        assertEquals(legacy, stored)
        settingsCodec.decode(stored) { stored = settingsCodec.encode(it) }
        assertEquals(expected, settingsCodec.decode(stored) { error("migration must run once") })
    }

    @Test
    fun gamingChoiceSurvivesStorageWhileKeepingTheNormalManualProtocol() {
        val advanced = AdvancedSettings(protocol = VpnProtocol.REALITY, gamingModeEnabled = true,
            endpointSelectionMode = EndpointSelectionMode.MANUAL,
            manualEndpointCode = "xhttp", manualEndpointGroupKey = "reality|xhttp|stream-up")
        val restored = settingsCodec.decode(settingsCodec.encode(defaults.copy(advancedSettings = advanced)))
        assertEquals(advanced, restored.advancedSettings)
        assertEquals(VpnProtocol.NOKINV, restored.advancedSettings.effectiveProtocol)
        assertEquals(VpnProtocol.REALITY, restored.advancedSettings.copy(gamingModeEnabled = false).effectiveProtocol)
        assertTrue(settingsCodec.decode(null).advancedSettings.ruRelayEnabled)
        assertFalse(settingsCodec.decode(null).advancedSettings.gamingModeEnabled)
    }

    @Test
    fun `country only MultiHop selection cannot become an explicit RU chain`() {
        val restored = settingsCodec.decode("""{"multiHopSettings":{"enabled":true,
            "entry":{"kind":"COUNTRY","countryCode":"RU"},
            "exit":{"kind":"SERVER","nodeId":"node-b"}}}""")
        assertEquals(BuildConfig.RU_RELAY_DEFAULT_ENABLED, restored.advancedSettings.ruRelayEnabled)
        assertFalse(restored.advancedSettings.multiHop.enabled)
        assertNull(restored.advancedSettings.multiHop.entry)
        assertEquals("node-b", restored.advancedSettings.multiHop.exit?.nodeId)
        assertNull(restored.advancedSettings.multiHop.explicitNodeIds())
        assertEquals(BuildConfig.RU_RELAY_DEFAULT_ENABLED,
            settingsCodec.decode("""{"multiHopSettings":{"enabled":false}}""")
                .advancedSettings.ruRelayEnabled)
    }

    @Test
    fun `legacy manual RU preference is ignored after upgrade`() {
        val saved = defaults.copy(ruRelaySelectionRevision = 17L, advancedSettings = defaults.advancedSettings.copy(
            ruRelayEnabled = true,
        ))
        val legacy = org.json.JSONObject(settingsCodec.encode(saved))
            .put("ruRelayAutoPath", false).put("manualRuRelayNodeId", "relay-a").toString()
        val restored = settingsCodec.decode(legacy)
        assertTrue(restored.advancedSettings.ruRelayEnabled)
        assertEquals(RuRelaySelection.Auto, restored.advancedSettings.ruRelaySelection)
        assertEquals(17L, restored.ruRelaySelectionRevision)
        assertFalse(settingsCodec.encode(restored).contains("manualRuRelayNodeId"))
    }

    @Test
    fun `legacy RU conflict keeps the saved pair disabled and persists the migration`() {
        val selection = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "11111111-1111-4111-8111-111111111111"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "22222222-2222-4222-8222-222222222222"))
        val stored = defaults.copy(
            advancedSettings = defaults.advancedSettings.copy(multiHop = selection, ruRelayEnabled = true,
                protocol = VpnProtocol.REALITY, manualEndpointGroupKey = "saved-direct-group"),
        )
        val normalized = settingsCodec.encode(stored)
        assertEquals(selection.copy(enabled = false), settingsCodec.decode(normalized).advancedSettings.multiHop)
        val legacy = org.json.JSONObject(normalized)
        legacy.getJSONObject("multiHopSettings").put("enabled", true)
        var saved: StoredSettings? = null
        val restored = settingsCodec.decode(legacy.toString()) { saved = it }
        assertEquals(selection.copy(enabled = false), restored.advancedSettings.multiHop)
        assertEquals(restored, saved)
        assertTrue(restored.advancedSettings.ruRelayEnabled)
        assertEquals(VpnProtocol.REALITY, restored.advancedSettings.protocol)
        assertEquals("saved-direct-group", restored.advancedSettings.manualEndpointGroupKey)
    }

    @Test
    fun `kill switch is opt in and survives settings round trip`() {
        assertFalse(settingsCodec.decode(null).advancedSettings.killSwitchEnabled)
        assertFalse(settingsCodec.decode("{}").advancedSettings.killSwitchEnabled)
        val enabled = defaults.copy(advancedSettings = defaults.advancedSettings.copy(killSwitchEnabled = true))
        assertTrue(settingsCodec.decode(settingsCodec.encode(enabled)).advancedSettings.killSwitchEnabled)
    }

    @Test
    fun `no RU pair identity survives storage but cannot bypass entry or survive selection change`() {
        val selection = MultiHopSettings(true,
            HopSelection(ServerSelectionMode.SERVER, nodeId = "11111111-1111-4111-8111-111111111111"),
            HopSelection(ServerSelectionMode.SERVER, nodeId = "22222222-2222-4222-8222-222222222222"))
        val route = BackendMultiHopSession(selection, selection.entry!!.nodeId!!, selection.exit!!.nodeId!!,
            "8.8.8.8", "a".repeat(64))
        val stored = defaults.copy(profile = VlessProfile(host = route.entryIp, port = "20000", endpointCode = "exit-tls",
            uuid = "00000000-0000-0000-0000-000000000099", security = "tls", serverName = "exit.example", multiHop = route),
            advancedSettings = AdvancedSettings(ruRelayEnabled = false, multiHop = selection))
        val restored = settingsCodec.decode(settingsCodec.encode(stored))
        assertEquals(stored.profile, restored.profile)
        assertTrue(VpnProfileValidator.isUsable(restored))
        assertFalse(RuntimeProfilePolicy.isCachedProfileUsable(restored))
        assertFalse(VpnProfileValidator.isUsable(restored.copy(advancedSettings = restored.advancedSettings.copy(multiHop = MultiHopSettings()))))
        assertFalse(VpnProfileValidator.isUsable(restored.copy(advancedSettings = restored.advancedSettings.copy(multiHop = selection.copy(
            entry = HopSelection(ServerSelectionMode.SERVER, nodeId = "33333333-3333-4333-8333-333333333333"))))))
        assertNull(RuntimeProfilePolicy.profileAfterServerSelection(restored.profile).multiHop)
    }

    @Test
    fun `fresh selection is auto while legacy storage retains its country`() {
        assertEquals(ServerSelectionMode.AUTO, settingsCodec.decode(null).userProfile.serverSelectionMode)
        val migrated = settingsCodec.decode("""{"selectedCountryCode":"DE"}""")
        assertEquals(ServerSelectionMode.COUNTRY, migrated.userProfile.serverSelectionMode)
        assertEquals("DE", migrated.userProfile.selectedCountryCode)
    }

    @Test
    fun `manual node and actual connection round trip independently`() {
        val profile = defaults.userProfile.copy(
            serverSelectionMode = ServerSelectionMode.SERVER,
            selectedCountryCode = "DE",
            selectedNodeId = "node-a",
            selectedServerCode = "de-frankfurt",
            actualCountryCode = "DE",
        )
        assertEquals(profile, settingsCodec.decode(settingsCodec.encode(defaults.copy(userProfile = profile))).userProfile)
    }

    @Test
    fun `empty storage gets Russian direct routing defaults`() {
        val fresh = settingsCodec.decode(null)

        assertEquals(AppFilterMode.ALL_EXCEPT_SELECTED, fresh.filterMode)
        val legacy = setOf(
                "ru.rostel",
                "ru.sberbankmobile",
                "com.idamob.tinkoff.android",
                "ru.alfabank.mobile.android",
                "ru.vtb24.mobilebanking.android",
                "ru.gazprombank.android.mobilebank.app",
                "ru.nspk.mirpay",
                "ru.yandex.searchplugin",
                "ru.yandex.yandexmaps",
                "ru.yandex.taxi",
                "ru.beru.android",
                "ru.yandex.music",
                "ru.kinopoisk",
                "ru.dublgis.dgismobile",
                "ru.ozon.app.android",
                "com.wildberries.ru",
                "com.avito.android",
                "com.vkontakte.android",
                "ru.ok.android",
                "ru.mail.mailapp",
                "ru.rutube.app",
                "ru.sbcs.store",
            )
        assertTrue(fresh.selectedPackages.containsAll(legacy))
        assertTrue(fresh.selectedPackages.containsAll(setOf("ru.oneme.app", "com.vk.vkvideo", "ru.mts.mymts", "com.logistic.sdek")))
        val legacyPayload = org.json.JSONObject(settingsCodec.encode(fresh.copy(selectedPackages = legacy)))
            .apply { remove("russianDirectDefaultsVersion") }.toString()
        assertEquals(fresh.selectedPackages, settingsCodec.decode(legacyPayload).selectedPackages)
        assertEquals(legacy, settingsCodec.decode(settingsCodec.encode(fresh.copy(selectedPackages = legacy))).selectedPackages)
        val custom = setOf("com.example.app")
        assertEquals(custom, settingsCodec.decode(settingsCodec.encode(fresh.copy(selectedPackages = custom))).selectedPackages)
        assertEquals(emptySet<String>(), settingsCodec.decode(settingsCodec.encode(fresh.copy(selectedPackages = emptySet()))).selectedPackages)
        assertEquals(
            listOf("geosite:category-ru"),
            fresh.advancedSettings.bypassDomains,
        )
    }

    @Test
    fun `stored settings current payload round trips`() {
        val expected = defaults.copy(
            selectedPackages = setOf("org.example.two", "org.example.one"),
            isAuthenticated = true,
            backendAccessToken = "access",
            backendRefreshRequestId = "861087a2-a3e8-4e92-b89a-18377e6212ee",
            advancedSettings = defaults.advancedSettings.copy(
                alwaysOnDomains = listOf("domain:example.com"),
                bypassDomains = listOf("domain:internal.example"),
            ),
        )

        assertEquals(expected, settingsCodec.decode(settingsCodec.encode(expected)))
    }

    @Test
    fun `stored settings persists country preference without concrete server`() {
        val encoded = settingsCodec.encode(
            defaults.copy(
                userProfile = defaults.userProfile.copy(
                    selectedCountryCode = "DE",
                    selectedServerCode = "de-2",
                ),
            ),
        )
        val decoded = settingsCodec.decode(encoded)

        assertEquals("DE", decoded.userProfile.selectedCountryCode)
        assertEquals("de-2", decoded.userProfile.selectedServerCode)
        assertTrue(encoded.contains("\"selectedServerCode\""))
    }

    @Test
    fun `stored settings glass mode round trips and replaces legacy key`() {
        GlassMode.entries.forEach { mode ->
            val encoded = settingsCodec.encode(
                defaults.copy(
                    personalizationSettings = defaults.personalizationSettings.copy(glassMode = mode),
                ),
            )

            assertEquals(mode, settingsCodec.decode(encoded).personalizationSettings.glassMode)
            assertFalse(encoded.contains("simpleModeEnabled"))
        }
    }

    @Test
    fun `stored settings migrates legacy simple mode`() {
        assertEquals(
            GlassMode.FULL,
            settingsCodec.decode("""{"simpleModeEnabled":true}""")
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            settingsCodec.decode("""{"glassMode":"SIMPLE_ANIMATION"}""")
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            settingsCodec.decode("""{"glassMode":"broken","simpleModeEnabled":true}""")
                .personalizationSettings.glassMode,
        )
    }

    @Test
    fun `fresh install defaults to simple only below full glass api`() {
        assertTrue(isFreshPackageInstall(firstInstallTime = 100L, lastUpdateTime = 100L))
        assertFalse(isFreshPackageInstall(firstInstallTime = 100L, lastUpdateTime = 200L))
        assertEquals(
            GlassMode.SIMPLE,
            DefaultStoredSettingsFactory.create(sdkInt = 32, isFreshInstall = true)
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            DefaultStoredSettingsFactory.create(sdkInt = 33, isFreshInstall = true)
                .personalizationSettings.glassMode,
        )
        assertEquals(
            GlassMode.FULL,
            DefaultStoredSettingsFactory.create(sdkInt = 32, isFreshInstall = false)
                .personalizationSettings.glassMode,
        )
    }

    @Test
    fun `stored settings legacy payload gets defaults and normalization`() {
        val decoded = settingsCodec.decode(
            """{"username":"legacy","alwaysOnDomains":["example.com","domain:example.com"]}""",
        )

        assertEquals("legacy", decoded.userProfile.username)
        assertEquals(listOf("domain:example.com"), decoded.advancedSettings.alwaysOnDomains)
        assertEquals("owner", decoded.backendDeviceAccessRole)
        assertEquals(AppFilterMode.ALL_APPS, decoded.filterMode)
        assertTrue(decoded.selectedPackages.isEmpty())
        assertEquals(listOf(DomainRulePolicy.RUSSIAN_RESOURCES_RULE), decoded.advancedSettings.bypassDomains)
    }

    @Test
    fun `stored settings corrupt payload falls back to supplied defaults`() {
        assertEquals(defaults, settingsCodec.decode("{not-json"))
    }

    @Test
    fun `daily stats codec clamps values drops corrupt and prunes expired days`() {
        val today = LocalDate.of(2026, 7, 12)
        val raw = """[
            {"date":"2026-07-12","rxBytes":-4,"sessions":-2},
            {"date":"2024-01-01","rxBytes":8},
            {"date":"not-a-date","rxBytes":9}
        ]""".trimIndent()

        assertEquals(listOf(DailyStats(date = "2026-07-12")), DailyStatsStore.decode(raw, today))
        assertTrue(DailyStatsStore.decode("broken", today).isEmpty())
    }

    @Test
    fun `app logs codec round trips and prunes invalid timestamps`() {
        val now = Instant.parse("2026-07-12T00:00:00Z")
        val current = AppLogEntry(
            timestamp = "2026-07-11T00:00:00Z",
            level = "info",
            category = "vpn",
            message = "connected",
            connectionSuccess = true,
        )
        val expired = current.copy(timestamp = "2025-01-01T00:00:00Z")
        val recent = current.copy(timestamp = Instant.now().minusSeconds(60).toString())

        assertEquals(listOf(current), AppLogStore.prune(listOf(expired, current), now))
        assertEquals(listOf(recent), AppLogStore.decode(AppLogStore.encode(listOf(recent))))
    }

    @Test
    fun `endpoint health codec round trips sanitized state and ignores blank key`() {
        val decoded = EndpointHealthStore.decode(
            """[{"code":"","score":20},{"code":"lv","score":130,"failureCount":-2}]""",
        )

        assertEquals(100, decoded.getValue("lv").score)
        assertEquals(0, decoded.getValue("lv").failureCount)
        assertEquals(decoded, EndpointHealthStore.decode(EndpointHealthStore.encode(decoded)))
    }

    @Test
    fun `temporary vpn lease codec round trips credentials without normal settings`() {
        val expected = TemporaryVpnLease(
            sessionId = "5d330ebf-6204-4773-99c2-f219f808b056",
            controlToken = "opaque-control-token",
            expiresAtEpochMillis = 1_784_000_000_000L,
            trafficLimitBytes = 100L * 1024L * 1024L,
            locationCode = "lv",
            locationName = "Латвия",
            profile = VlessProfile(
                remark = "Noki Латвия",
                endpointCode = "auth-temp-lv",
                host = "vpn.example.test",
                port = "443",
                uuid = "b3e85e7a-c160-4dd0-9b26-86f32b9aa5d6",
                security = "reality",
                serverName = "cdn.example.test",
                publicKey = "server-public-key",
                shortId = "0123456789abcdef",
            ),
        )

        assertEquals(expected, TemporaryVpnLeaseCodec.decode(TemporaryVpnLeaseCodec.encode(expected)))
        assertNull(TemporaryVpnLeaseCodec.decode("{not-json"))
    }

    @Test
    fun `temporary vpn lease requires usable profile and remaining lifetime`() {
        val now = 10_000L
        val lease = TemporaryVpnLease(
            sessionId = "session",
            controlToken = "control",
            expiresAtEpochMillis = now + 10_000L,
            trafficLimitBytes = 100L,
            locationCode = "lv",
            locationName = "Латвия",
            profile = defaults.profile.copy(
                host = "vpn.example.test",
                uuid = "b3e85e7a-c160-4dd0-9b26-86f32b9aa5d6",
                serverName = "cdn.example.test",
                publicKey = "server-public-key",
                shortId = "0123456789abcdef",
            ),
        )

        assertTrue(TemporaryVpnLeasePolicy.isUsable(lease, now, minimumRemainingMillis = 5_000L))
        assertFalse(TemporaryVpnLeasePolicy.isUsable(lease, now + 6_000L, minimumRemainingMillis = 5_000L))
        assertFalse(TemporaryVpnLeasePolicy.isUsable(lease.copy(profile = VlessProfile()), now))
        assertFalse(
            TemporaryVpnLeasePolicy.isUsable(
                lease.copy(trafficLimitBytes = 100L * 1024L * 1024L + 1L),
                now,
            ),
        )
        assertFalse(
            TemporaryVpnLeasePolicy.isUsable(
                lease.copy(expiresAtEpochMillis = now + 10L * 60L * 1_000L + 30_001L),
                now,
            ),
        )
    }

    @Test
    fun `temporary vpn pending revoke codec keeps only control data`() {
        val expected = TemporaryVpnPendingRevoke(
            sessionId = "5d330ebf-6204-4773-99c2-f219f808b056",
            controlToken = "opaque-control-token",
            expiresAtEpochMillis = 1_784_000_000_000L,
        )

        assertEquals(
            expected,
            TemporaryVpnPendingRevokeCodec.decode(TemporaryVpnPendingRevokeCodec.encode(expected)),
        )
        assertNull(TemporaryVpnPendingRevokeCodec.decode("{not-json"))
    }

    @Test
    fun `vpn incident queue keeps latest ten reports`() {
        val incidents = (1..12).map { index ->
            VpnIncidentReport(
                id = "incident-$index",
                reason = "readiness_failed",
                countryCode = "LV",
                locationCode = "lv-2",
                recoveryAttempts = 3,
                outcome = "failed",
                occurredAt = "2026-07-27T10:00:00Z",
            )
        }

        val decoded = VpnIncidentStore.decode(VpnIncidentStore.encode(incidents))

        assertEquals(10, decoded.size)
        assertEquals("incident-3", decoded.first().id)
        assertEquals("incident-12", decoded.last().id)
    }
}
