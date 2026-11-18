package com.noki.vpn.data

import org.json.JSONArray
import org.json.JSONObject

internal class StoredSettingsCodec(
    private val defaults: () -> StoredSettings,
) {
    fun decode(raw: String?, persistMigration: ((StoredSettings) -> Unit)? = null): StoredSettings {
        if (raw.isNullOrBlank()) return defaults()
        val json = runCatching { JSONObject(raw) }.getOrElse { return defaults() }
        val migrateRuDefault = json.optInt("ruRelayDefaultsVersion") < 4
        val migrateGeositeRule = json.optInt("geositeRuleVersion") < 1
        var routeSelectionMigrated = false
        val migrated = runCatching {
            val decoded = decode(json).let { settings ->
                if (!migrateRuDefault) settings else settings.copy(
                    advancedSettings = RuntimeProfilePolicy.normalizeRouteModes(settings.advancedSettings.copy(
                        ruRelayEnabled = true, gamingModeEnabled = false)),
                    profile = RuntimeProfilePolicy.profileAfterServerSelection(settings.profile),
                    endpointOptions = emptyList(),
                    ruRelaySelectionRevision = settings.ruRelaySelectionRevision + 1L,
                )
            }.let { settings ->
                val advanced = settings.advancedSettings
                val explicitLegacyVpn = DomainRulePolicy.RUSSIAN_RESOURCES_RULE in advanced.alwaysOnDomains &&
                    DomainRulePolicy.RUSSIAN_RESOURCES_RULE !in advanced.bypassDomains
                // Older payloads cannot distinguish a removed preset from the
                // releases that forced it on. Preserve effective ON once, unless
                // an explicit opposite rule positively records the user's choice.
                if (!migrateGeositeRule || explicitLegacyVpn) settings else settings.copy(
                    advancedSettings = DomainRulePolicy.addBypass(advanced, DomainRulePolicy.RUSSIAN_RESOURCES_RULE))
            }
            val selected = RuntimeProfilePolicy.normalizeRouteSelection(decoded)
            routeSelectionMigrated = selected != decoded
            val permitted = selected.copy(advancedSettings = RuntimeProfilePolicy.normalizeAdvancedSettings(selected.advancedSettings, selected.userProfile))
            val stored = if (json.has("youtubeCascade") && !json.isNull("youtubeCascade")) permitted.copy(
                profile = RuntimeProfilePolicy.profileAfterServerSelection(permitted.profile),
            ) else permitted
            if (json.optInt("russianDirectDefaultsVersion") >= 1) stored else stored.copy(selectedPackages = DefaultStoredSettingsFactory.updatedDefaultPackages(
                stored.selectedPackages, stored.filterMode,
            ))
        }.getOrElse { return defaults() }
        if (migrateRuDefault || migrateGeositeRule || routeSelectionMigrated) persistMigration?.invoke(migrated)
        return migrated
    }

    fun encode(settings: StoredSettings): String = encodeNormalized(RuntimeProfilePolicy.normalizeRouteSelection(settings))

    private fun encodeNormalized(settings: StoredSettings): String = JSONObject()
        .put("russianDirectDefaultsVersion", 1)
        .put("ruRelayDefaultsVersion", 4)
        .put("geositeRuleVersion", 1)
        .put("remark", settings.profile.remark)
        .put("endpointCode", settings.profile.endpointCode)
        .put("proxyType", settings.profile.proxyType)
        .put("transport", settings.profile.transport)
        .put("transportMode", settings.profile.transportMode)
        .put("host", settings.profile.host)
        .put("port", settings.profile.port)
        .put("uuid", settings.profile.uuid)
        .put("vpnUsername", settings.profile.vpnUsername)
        .put("nokinvToken", settings.profile.nokinvToken)
        .put("nokinvCredentialGeneration", settings.profile.nokinvCredentialGeneration)
        .put("flow", settings.profile.flow)
        .put("security", settings.profile.security)
        .put("fingerprint", settings.profile.fingerprint)
        .put("serverName", settings.profile.serverName)
        .put("requestHost", settings.profile.requestHost)
        .put("path", settings.profile.path)
        .put("alpn", settings.profile.alpn)
        .put("allowInsecure", settings.profile.allowInsecure)
        .put("enableMux", settings.profile.enableMux)
        .put("randomUserAgent", settings.profile.randomUserAgent)
        .put("publicKey", settings.profile.publicKey)
        .put("shortId", settings.profile.shortId)
        .put("spiderX", settings.profile.spiderX)
        .put("entryRoute", settings.profile.entryRoute)
        .put("entryRouteExpiresAt", settings.profile.entryRouteExpiresAt)
        .put("multiHopRuntime", settings.profile.multiHop?.let { route -> JSONObject()
            .put("version", route.version).put("selection", encodeMultiHopSettings(route.selection))
            .put("entryNodeId", route.entryNodeId).put("exitNodeId", route.exitNodeId)
            .put("entryIp", route.entryIp).put("policyHash", route.policyHash) })
        .put("filterMode", settings.filterMode.name)
        .put("selectedPackages", JSONArray(settings.selectedPackages.sorted()))
        .put("backendUserId", settings.userProfile.backendUserId)
        .put("username", settings.userProfile.username)
        .put("email", settings.userProfile.email)
        .put("avatarUri", settings.userProfile.avatarUri ?: "")
        .put("hasRealEmail", settings.userProfile.hasRealEmail)
        .put("hasPassword", settings.userProfile.hasPassword)
        .put("telegramLinked", settings.userProfile.telegramLinked)
        .put("credentialsSetupRequired", settings.userProfile.credentialsSetupRequired)
        .put("selectedPlanCode", settings.userProfile.selectedPlanCode.code)
        .put("selectedPlanCodeRaw", settings.userProfile.selectedPlanCodeRaw)
        .put("selectedPlanName", settings.userProfile.selectedPlanName ?: "")
        .put("selectedPlanTier", settings.userProfile.selectedPlanTier ?: "")
        .put("selectedPlanBadgeColor", settings.userProfile.selectedPlanBadgeColor ?: "")
        .put("selectedCountryCode", settings.userProfile.selectedCountryCode)
        .put("serverSelectionMode", settings.userProfile.serverSelectionMode.name)
        .put("selectedNodeId", settings.userProfile.selectedNodeId)
        .put("selectedServerCode", settings.userProfile.selectedServerCode)
        .put("actualCountryCode", settings.userProfile.actualCountryCode)
        .put("trafficUsedGb", settings.userProfile.trafficUsedGb)
        .put("trafficLimitGb", settings.userProfile.trafficLimitGb)
        .put("primaryDeviceLimit", settings.userProfile.primaryDeviceLimit)
        .put("sharedTrafficUsedGb", settings.userProfile.sharedTrafficUsedGb)
        .put("sharedTrafficLimitGb", settings.userProfile.sharedTrafficLimitGb)
        .put("sharedPeriodStart", settings.userProfile.sharedPeriodStart)
        .put("sharedPeriodEnd", settings.userProfile.sharedPeriodEnd)
        .put("sharedFreeOnly", settings.userProfile.sharedFreeOnly)
        .put("trialTrafficRemainingGb", settings.userProfile.trialTrafficRemainingGb)
        .put("freeLocationsOnly", settings.userProfile.freeLocationsOnly)
        .put("subscriptionExpiresAt", settings.userProfile.subscriptionExpiresAt ?: "")
        .put("subscriptionStatus", settings.userProfile.subscriptionStatus)
        .put("language", settings.personalizationSettings.language.tag)
        .put("accentPalette", settings.personalizationSettings.accentPalette.key)
        .put("homeLayoutVariant", settings.personalizationSettings.homeLayoutVariant.name)
        .put("glassMode", settings.personalizationSettings.glassMode.name)
        .put("vpnStatusNotificationEnabled", settings.personalizationSettings.vpnStatusNotificationEnabled)
        .put("customBackground", settings.personalizationSettings.customBackground ?: "")
        .put("biometricEnabled", settings.securitySettings.biometricEnabled)
        .put("loginAlertsEnabled", settings.securitySettings.loginAlertsEnabled)
        .put("protectNewDevices", settings.securitySettings.protectNewDevices)
        .put("protocol", settings.advancedSettings.protocol.name)
        .put("gamingModeEnabled", settings.advancedSettings.gamingModeEnabled)
        .put("ruRelayEnabled", settings.advancedSettings.ruRelayEnabled)
        .put("ruRelaySelectionRevision", settings.ruRelaySelectionRevision)
        .put("endpointSelectionMode", settings.advancedSettings.endpointSelectionMode.name)
        .put("manualEndpointCode", settings.advancedSettings.manualEndpointCode)
        .put("manualEndpointGroupKey", settings.advancedSettings.manualEndpointGroupKey)
        .put("connectionLogsEnabled", settings.advancedSettings.connectionLogsEnabled)
        .put("errorLogsEnabled", settings.advancedSettings.errorLogsEnabled)
        .put("anonymousLogsEnabled", settings.advancedSettings.anonymousLogsEnabled)
        .put("anonymousLogConsentVersion", 1)
        .put("youtubeDirectDpiEnabled", RuntimeProfilePolicy.normalizeAdvancedSettings(settings.advancedSettings, settings.userProfile).youtubeNoAdsEnabled)
        .put("multiHopSettings", encodeMultiHopSettings(settings.advancedSettings.multiHop))
        .put("killSwitchEnabled", settings.advancedSettings.killSwitchEnabled)
        .put("alwaysOnDomains", JSONArray(settings.advancedSettings.alwaysOnDomains))
        .put("bypassDomains", JSONArray(settings.advancedSettings.bypassDomains))
        .put("endpointOptions", encodeEndpointOptions(settings.endpointOptions))
        .put("isAuthenticated", settings.isAuthenticated)
        .put("backendAccessToken", settings.backendAccessToken ?: "")
        .put("backendRefreshToken", settings.backendRefreshToken ?: "")
        .put("backendRefreshRequestId", settings.backendRefreshRequestId ?: "")
        .put("backendAccessTokenExpiresInSeconds", settings.backendAccessTokenExpiresInSeconds ?: JSONObject.NULL)
        .put("backendRefreshExpiresAt", settings.backendRefreshExpiresAt ?: "")
        .put("backendDeviceKey", settings.backendDeviceKey)
        .put("backendDeviceId", settings.backendDeviceId)
        .put("backendDeviceAccessRole", settings.backendDeviceAccessRole)
        .toString()

    private fun decode(json: JSONObject) = StoredSettings(
        profile = VlessProfile(
            remark = json.optString("remark", "Noki VPN"),
            endpointCode = json.optString("endpointCode"),
            proxyType = json.optString("proxyType", "vless"),
            transport = json.optString("transport", "tcp"),
            transportMode = json.optString("transportMode"),
            host = json.optString("host"),
            port = json.optString("port", "443"),
            uuid = json.optString("uuid"),
            vpnUsername = json.optString("vpnUsername"),
            nokinvToken = json.optString("nokinvToken", ""),
            nokinvCredentialGeneration = json.optInt("nokinvCredentialGeneration", 0),
            flow = json.optString("flow"),
            security = json.optString("security", "reality"),
            fingerprint = json.optString("fingerprint", "chrome"),
            serverName = json.optString("serverName"),
            requestHost = json.optString("requestHost"),
            path = json.optString("path"),
            alpn = json.optString("alpn"),
            allowInsecure = json.optBoolean("allowInsecure", false),
            enableMux = json.optBoolean("enableMux", false),
            randomUserAgent = json.optBoolean("randomUserAgent", false),
            publicKey = json.optString("publicKey"),
            shortId = json.optString("shortId"),
            spiderX = json.optString("spiderX", "/"),
            entryRoute = json.optBoolean("entryRoute"),
            entryRouteExpiresAt = json.optLong("entryRouteExpiresAt").takeIf { it > 0 },
            multiHop = json.optJSONObject("multiHopRuntime")?.let { route -> BackendMultiHopSession(
                selection = decodeMultiHopSettings(route.getJSONObject("selection")),
                entryNodeId = route.getString("entryNodeId"), exitNodeId = route.getString("exitNodeId"),
                entryIp = route.optString("entryIp"), policyHash = route.getString("policyHash"),
                version = route.getInt("version"),
            ) },
        ),
        filterMode = runCatching {
            AppFilterMode.valueOf(json.optString("filterMode", AppFilterMode.ALL_APPS.name))
        }.getOrElse { AppFilterMode.ALL_APPS },
        selectedPackages = json.optJSONArray("selectedPackages").toStringSet(),
        userProfile = UserProfile(
            backendUserId = json.optString("backendUserId"),
            username = json.optString("username"),
            email = json.optString("email"),
            avatarUri = json.optString("avatarUri").takeIf { it.isNotBlank() },
            hasRealEmail = json.optBoolean("hasRealEmail", true),
            hasPassword = json.optBoolean("hasPassword", true),
            telegramLinked = json.optBoolean("telegramLinked", false),
            credentialsSetupRequired = json.optBoolean("credentialsSetupRequired", false),
            selectedPlanCode = PlanCode.fromCode(json.optString("selectedPlanCode", PlanCode.FREE.code)),
            selectedPlanCodeRaw = json.optString(
                "selectedPlanCodeRaw",
                json.optString("selectedPlanCode", PlanCode.FREE.code),
            ).ifBlank { PlanCode.FREE.code },
            selectedPlanName = json.optString("selectedPlanName").takeIf { it.isNotBlank() },
            selectedPlanTier = json.optString("selectedPlanTier").takeIf { it.isNotBlank() },
            selectedPlanBadgeColor = json.optString("selectedPlanBadgeColor").takeIf { it.isNotBlank() },
            selectedCountryCode = json.optString("selectedCountryCode"),
            serverSelectionMode = runCatching {
                ServerSelectionMode.valueOf(json.optString("serverSelectionMode", ServerSelectionMode.COUNTRY.name))
            }.getOrDefault(ServerSelectionMode.COUNTRY),
            selectedNodeId = json.optString("selectedNodeId"),
            actualCountryCode = json.optString("actualCountryCode"),
            selectedServerCode = json.optString("selectedServerCode", "lv"),
            trafficUsedGb = json.optNullableDouble("trafficUsedGb"),
            trafficLimitGb = json.optNullableDouble("trafficLimitGb"),
            primaryDeviceLimit = if (json.has("primaryDeviceLimit") && !json.isNull("primaryDeviceLimit")) json.optInt("primaryDeviceLimit") else null,
            sharedTrafficUsedGb = json.optNullableDouble("sharedTrafficUsedGb"),
            sharedTrafficLimitGb = json.optNullableDouble("sharedTrafficLimitGb"),
            sharedPeriodStart = json.optString("sharedPeriodStart").takeIf { it.isNotBlank() },
            sharedPeriodEnd = json.optString("sharedPeriodEnd").takeIf { it.isNotBlank() },
            sharedFreeOnly = json.optBoolean("sharedFreeOnly", false),
            trialTrafficRemainingGb = json.optNullableDouble("trialTrafficRemainingGb"),
            freeLocationsOnly = json.optBoolean("freeLocationsOnly", false),
            subscriptionExpiresAt = json.optString("subscriptionExpiresAt").takeIf { it.isNotBlank() },
            subscriptionStatus = json.optString("subscriptionStatus", "active"),
        ),
        personalizationSettings = PersonalizationSettings(
            customBackground = json.optString("customBackground").takeIf(PersonalizationBackgroundStore::isValidName),
            vpnStatusNotificationEnabled = json.optBoolean("vpnStatusNotificationEnabled", true),
            language = AppLanguage.fromTag(json.optString("language")),
            accentPalette = AccentPalette.fromKey(json.optString("accentPalette")),
            homeLayoutVariant = runCatching {
                HomeLayoutVariant.valueOf(json.optString("homeLayoutVariant", HomeLayoutVariant.MAIN.name))
            }.getOrElse { HomeLayoutVariant.MAIN },
            glassMode = if (json.has("glassMode")) {
                when (json.optString("glassMode")) {
                    GlassMode.SIMPLE.name -> GlassMode.SIMPLE
                    GlassMode.FULL.name, "SIMPLE_ANIMATION" -> GlassMode.FULL
                    else -> GlassMode.FULL
                }
            } else if (json.optBoolean("simpleModeEnabled", false)) {
                GlassMode.FULL
            } else {
                GlassMode.FULL
            },
        ),
        securitySettings = SecuritySettings(
            biometricEnabled = json.optBoolean("biometricEnabled", true),
            loginAlertsEnabled = json.optBoolean("loginAlertsEnabled", true),
            protectNewDevices = json.optBoolean("protectNewDevices", true),
        ),
        advancedSettings = AdvancedSettings(
            gamingModeEnabled = json.optBoolean("gamingModeEnabled", false),
            protocol = runCatching {
                VpnProtocol.valueOf(json.optString("protocol", VpnProtocol.AUTO.name))
            }.getOrElse { VpnProtocol.AUTO }.takeUnless { it == VpnProtocol.WIREGUARD } ?: VpnProtocol.AUTO,
            ruRelayEnabled = json.optBoolean("ruRelayEnabled", defaults().advancedSettings.ruRelayEnabled),
            endpointSelectionMode = runCatching {
                EndpointSelectionMode.valueOf(
                    json.optString("endpointSelectionMode", EndpointSelectionMode.AUTO.name),
                )
            }.getOrElse { EndpointSelectionMode.AUTO },
            manualEndpointCode = json.optString("manualEndpointCode"),
            manualEndpointGroupKey = json.optString("manualEndpointGroupKey"),
            connectionLogsEnabled = json.optBoolean("connectionLogsEnabled", true),
            errorLogsEnabled = json.optBoolean("errorLogsEnabled", true),
            anonymousLogsEnabled =
                json.optInt("anonymousLogConsentVersion", 0) >= 1 &&
                    json.optBoolean("anonymousLogsEnabled", false),
            multiHop = decodeMultiHopSettings(json.optJSONObject("multiHopSettings")),
            youtubeNoAdsEnabled = json.optBoolean("youtubeDirectDpiEnabled", false),
            killSwitchEnabled = json.optBoolean("killSwitchEnabled", false),
            alwaysOnDomains = DefaultStoredSettingsFactory.normalizeAlwaysOnDomains(
                json.optJSONArray("alwaysOnDomains").toStringList(),
            ),
            bypassDomains = DefaultStoredSettingsFactory.normalizeBypassDomains(
                json.optJSONArray("bypassDomains").toStringList(),
            ),
        ),
        endpointOptions = json.optJSONArray("endpointOptions").toEndpointOptions(),
        isAuthenticated = json.optBoolean("isAuthenticated", false),
        backendAccessToken = json.optString("backendAccessToken").takeIf { it.isNotBlank() },
        backendRefreshToken = json.optString("backendRefreshToken").takeIf { it.isNotBlank() },
        backendRefreshRequestId = json.optString("backendRefreshRequestId").takeIf { it.isNotBlank() },
        backendAccessTokenExpiresInSeconds = json.optLongOrNull("backendAccessTokenExpiresInSeconds"),
        backendRefreshExpiresAt = json.optString("backendRefreshExpiresAt").takeIf { it.isNotBlank() },
        backendDeviceKey = json.optString("backendDeviceKey"),
        backendDeviceId = json.optString("backendDeviceId"),
        backendDeviceAccessRole = json.optString("backendDeviceAccessRole", "owner").ifBlank { "owner" },
        ruRelaySelectionRevision = json.optLong("ruRelaySelectionRevision", 0L).coerceAtLeast(0L),
    )

    private fun encodeMultiHopSettings(settings: MultiHopSettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled)
        .put("entry", settings.entry?.let(::encodeHop) ?: JSONObject.NULL)
        .put("exit", settings.exit?.let(::encodeHop) ?: JSONObject.NULL)

    private fun encodeHop(hop: HopSelection): JSONObject = JSONObject()
        .put("kind", hop.kind.name)
        .put("countryCode", hop.countryCode)
        .put("nodeId", hop.nodeId)

    private fun decodeMultiHopSettings(json: JSONObject?): MultiHopSettings {
        if (json == null) return MultiHopSettings()
        fun hop(value: JSONObject?): HopSelection? {
            if (value == null) return null
            val kind = runCatching { ServerSelectionMode.valueOf(value.getString("kind")) }.getOrNull()
                ?: return null
            if (kind != ServerSelectionMode.SERVER) return null
            return HopSelection(kind, value.optString("countryCode").takeIf(String::isNotBlank),
                value.optString("nodeId").takeIf(String::isNotBlank))
        }
        return MultiHopSettings(json.optBoolean("enabled", false),
            hop(json.optJSONObject("entry")), hop(json.optJSONObject("exit")))
    }

    private fun encodeEndpointOptions(options: List<VpnEndpointOption>) = JSONArray().apply {
        options.forEach { option ->
            put(JSONObject()
                .put("code", option.code)
                .put("nodeId", option.nodeId ?: "")
                .put("label", option.label)
                .put("locationCode", option.locationCode)
                .put("locationName", option.locationName)
                .put("host", option.host)
                .put("port", option.port)
                .put("proxyType", option.proxyType)
                .put("transport", option.transport)
                .put("transportMode", option.transportMode)
                .put("security", option.security)
                .put("canaryOnly", option.canaryOnly)
                .put("priority", option.priority)
                .put("endpointGroupKey", option.endpointGroupKey)
                .put("groupSize", option.groupSize)
                .put("groupPorts", JSONArray(option.groupPorts)))
        }
    }
}

private fun JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.toEndpointOptions(): List<VpnEndpointOption> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val json = optJSONObject(index) ?: continue
            val code = json.optString("code").takeIf { it.isNotBlank() } ?: continue
            add(VpnEndpointOption(
                code = code,
                nodeId = json.optString("nodeId").takeIf { it.isNotBlank() },
                label = json.optString("label"),
                locationCode = json.optString("locationCode"),
                locationName = json.optString("locationName"),
                host = json.optString("host"),
                port = json.optInt("port", 443),
                proxyType = json.optString("proxyType", "vless"),
                transport = json.optString("transport", "tcp"),
                transportMode = json.optString("transportMode"),
                security = json.optString("security", "tls"),
                canaryOnly = json.optBoolean("canaryOnly", false),
                priority = json.optInt("priority", 100),
                endpointGroupKey = json.optString("endpointGroupKey"),
                groupSize = json.optInt("groupSize", 1).coerceAtLeast(1),
                groupPorts = json.optJSONArray("groupPorts").toIntList(),
            ))
        }
    }
}

private fun JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optInt(index, -1)
            if (value > 0) add(value)
        }
    }
}

private fun JSONObject.optNullableDouble(name: String): Double? =
    if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (!has(name) || isNull(name)) null else runCatching { getLong(name) }.getOrNull()
