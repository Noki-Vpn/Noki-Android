package com.noki.vpn.data

import androidx.compose.runtime.Immutable
import java.util.Locale

enum class AppLanguage(val tag: String) {
    RU("ru"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            return entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: EN
        }

        fun fromLocale(locale: Locale): AppLanguage {
            return if (locale.language.equals("ru", ignoreCase = true)) RU else EN
        }
    }
}

enum class AccentPalette(val key: String, val argb: Long) {
    GREEN("green", 0xFF7AE7C7),
    CYAN("cyan", 0xFF6EE7F2),
    BLUE("blue", 0xFF8CC8FF),
    AMBER("amber", 0xFFF5B942),
    CORAL("coral", 0xFFFF8E7D);

    companion object {
        fun fromKey(key: String?): AccentPalette {
            return entries.firstOrNull { it.key == key } ?: GREEN
        }
    }
}

enum class HomeLayoutVariant {
    MAIN,
    MAIN_V2,
}

enum class GlassMode(
    val liveGlassEnabled: Boolean,
    val simpleTransitions: Boolean,
) {
    SIMPLE(liveGlassEnabled = false, simpleTransitions = true),
    FULL(liveGlassEnabled = true, simpleTransitions = false),
}

enum class InstallOnboardingStep {
    CONSENT,
    ROUTING,
    APPEARANCE,
    COMPLETE,
}

enum class VpnProtocol {
    AUTO,
    TLS,
    REALITY,
    NOKINV,
    NAIVE,
    WIREGUARD;

    companion object {
        fun fromBackendCode(code: String?, proxyType: String? = null): VpnProtocol = when {
            code.equals("naive", ignoreCase = true) || proxyType.equals("naive", ignoreCase = true) -> NAIVE
            code.equals("nokinv", ignoreCase = true) || proxyType.equals("nokinv", ignoreCase = true) -> NOKINV
            code.equals("tls", ignoreCase = true) -> TLS
            code.equals("reality", ignoreCase = true) -> REALITY
            else -> AUTO
        }
    }
}

enum class EndpointSelectionMode {
    AUTO,
    MANUAL,
}

enum class ServerSelectionMode { AUTO, COUNTRY, SERVER }

@Immutable
data class HopSelection(
    val kind: ServerSelectionMode,
    val countryCode: String? = null,
    val nodeId: String? = null,
)

@Immutable
data class MultiHopSettings(
    val enabled: Boolean = false,
    val entry: HopSelection? = null,
    val exit: HopSelection? = null,
) {
    fun explicitNodeIds(): Pair<String, String>? {
        if (!enabled) return null
        fun node(selection: HopSelection?): String {
            val selected = requireNotNull(selection) { "multihop_selector_missing" }
            require(selected.kind == ServerSelectionMode.SERVER) { "multihop_selector_invalid" }
            return canonicalRuUuid(requireNotNull(selected.nodeId) { "multihop_selector_missing" })
        }
        val entryId = node(entry)
        val exitId = node(exit)
        require(entryId != exitId) { "ru_route_identity_invalid" }
        return entryId to exitId
    }
}

sealed interface RuRelaySelection {
    data object Off : RuRelaySelection
    data object Auto : RuRelaySelection
    data class Node(val nodeId: String) : RuRelaySelection
}

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED,
}

enum class AppFilterMode {
    ALL_APPS,
    ONLY_SELECTED,
    ALL_EXCEPT_SELECTED,
}

enum class BillingCycle {
    MONTHLY,
    YEARLY,
}

enum class PlanCode(val code: String) {
    FREE("free"),
    PLUS("plus"),
    PRO("pro"),
    PREMIUM("premium");

    companion object {
        fun fromCode(code: String?): PlanCode {
            return entries.firstOrNull { it.code == code } ?: FREE
        }

        fun fromBackend(
            code: String?,
            fallback: PlanCode = FREE,
        ): PlanCode {
            val normalized = code?.lowercase(Locale.ROOT) ?: return fallback
            return when {
                normalized.startsWith("premium") -> PREMIUM
                normalized.startsWith("pro") -> PRO
                normalized.startsWith("plus") -> PLUS
                normalized.startsWith("standard") -> PLUS
                normalized.startsWith("free") -> FREE
                else -> fallback
            }
        }
    }
}

@Immutable
data class AppInfo(
    val appName: String,
    val packageName: String,
    val isSystemApp: Boolean = false,
)

data class VlessProfile(
    val remark: String = "Noki VPN",
    val endpointCode: String = "",
    val proxyType: String = "vless",
    val transport: String = "tcp",
    val transportMode: String = "",
    val host: String = "",
    val port: String = "443",
    val uuid: String = "",
    val vpnUsername: String = "",
    val nokinvToken: String = "",
    val nokinvCredentialGeneration: Int = 0,
    val flow: String = "",
    val security: String = "reality",
    val fingerprint: String = "chrome",
    val serverName: String = "",
    val requestHost: String = "",
    val path: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val enableMux: Boolean = false,
    val randomUserAgent: Boolean = false,
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "/",
    val multiHop: BackendMultiHopSession? = null,
    val entryRoute: Boolean = false,
    val entryRouteExpiresAt: Long? = null,
) {
    internal fun entryRouteExit(): String? = if (entryRoute) multiHop?.exitNodeId.orEmpty() else null
}

data class VpnEndpointOption(
    val code: String = "",
    val nodeId: String? = null,
    val label: String = "",
    val locationCode: String = "",
    val locationName: String = "",
    val host: String = "",
    val port: Int = 443,
    val proxyType: String = "vless",
    val transport: String = "tcp",
    val transportMode: String = "",
    val security: String = "tls",
    val canaryOnly: Boolean = false,
    val priority: Int = 100,
    val endpointGroupKey: String = "",
    val groupSize: Int = 1,
    val groupPorts: List<Int> = emptyList(),
)

data class EndpointHealth(
    val score: Int = 70,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val cooldownUntilMillis: Long = 0L,
    val lastUpdatedAtMillis: Long = 0L,
    val latencyEwmaMs: Long? = null,
    val latencyUpdatedAtMillis: Long = 0L,
)

@Immutable
data class UserProfile(
    val backendUserId: String = "",
    val username: String = "",
    val email: String = "",
    val avatarUri: String? = null,
    val hasRealEmail: Boolean = true,
    val hasPassword: Boolean = true,
    val telegramLinked: Boolean = false,
    val selectedPlanCode: PlanCode = PlanCode.FREE,
    val selectedPlanCodeRaw: String = PlanCode.FREE.code,
    val selectedPlanName: String? = null,
    val selectedPlanTier: String? = null,
    val selectedPlanBadgeColor: String? = null,
    val selectedCountryCode: String = "LV",
    val serverSelectionMode: ServerSelectionMode = ServerSelectionMode.COUNTRY,
    val selectedNodeId: String = "",
    val actualCountryCode: String = "",
    val selectedServerCode: String = "lv",
    val trafficUsedGb: Double? = null,
    val trafficLimitGb: Double? = null,
    val subscriptionExpiresAt: String? = null,
    val subscriptionStatus: String = "active",
    val primaryDeviceLimit: Int? = null,
    val sharedTrafficUsedGb: Double? = null,
    val sharedTrafficLimitGb: Double? = null,
    val sharedPeriodStart: String? = null,
    val sharedPeriodEnd: String? = null,
    val sharedFreeOnly: Boolean = false,
    val trialTrafficRemainingGb: Double? = null,
    val freeLocationsOnly: Boolean = false,
    val credentialsSetupRequired: Boolean = false,
) {
    val isFreePlan: Boolean
        get() {
            selectedPlanTier?.trim()?.takeIf(String::isNotBlank)?.let {
                return it.equals("free", ignoreCase = true)
            }
            val code = selectedPlanCodeRaw.trim().ifBlank { selectedPlanCode.code }
            return code.equals("free", ignoreCase = true) ||
                code.startsWith("free_", ignoreCase = true) || code.startsWith("free-", ignoreCase = true)
        }
}

data class VpnIncidentReport(
    val id: String,
    val reason: String,
    val countryCode: String,
    val locationCode: String,
    val recoveryAttempts: Int,
    val outcome: String,
    val occurredAt: String,
)

@Immutable
data class PersonalizationSettings(
    val language: AppLanguage = AppLanguage.EN,
    val accentPalette: AccentPalette = AccentPalette.GREEN,
    val homeLayoutVariant: HomeLayoutVariant = HomeLayoutVariant.MAIN,
    val glassMode: GlassMode = GlassMode.FULL,
    val vpnStatusNotificationEnabled: Boolean = true,
    val customBackground: String? = null,
)

@Immutable
data class SecuritySettings(
    val biometricEnabled: Boolean = true,
    val loginAlertsEnabled: Boolean = true,
    val protectNewDevices: Boolean = true,
)

data class AdvancedSettings(
    val protocol: VpnProtocol = VpnProtocol.AUTO,
    val gamingModeEnabled: Boolean = false,
    val ruRelayEnabled: Boolean = false,
    val endpointSelectionMode: EndpointSelectionMode = EndpointSelectionMode.AUTO,
    val manualEndpointCode: String = "",
    val manualEndpointGroupKey: String = "",
    val connectionLogsEnabled: Boolean = true,
    val errorLogsEnabled: Boolean = true,
    val anonymousLogsEnabled: Boolean = false,
    val youtubeNoAdsEnabled: Boolean = false,
    val killSwitchEnabled: Boolean = false,
    val alwaysOnDomains: List<String> = emptyList(),
    val bypassDomains: List<String> = emptyList(),
    val multiHop: MultiHopSettings = MultiHopSettings(),
) {
    val ruRelaySelection: RuRelaySelection
        get() = if (ruRelayEnabled) RuRelaySelection.Auto else RuRelaySelection.Off

    val effectiveProtocol: VpnProtocol
        get() {
            if (ruRelayEnabled) return VpnProtocol.WIREGUARD
            if (gamingModeEnabled) return VpnProtocol.NOKINV
            require(protocol != VpnProtocol.WIREGUARD) { "wireguard_requires_ru_relay" }
            return protocol
        }
}

data class StoredSettings(
    val profile: VlessProfile,
    val filterMode: AppFilterMode,
    val selectedPackages: Set<String>,
    val userProfile: UserProfile,
    val personalizationSettings: PersonalizationSettings,
    val securitySettings: SecuritySettings,
    val advancedSettings: AdvancedSettings,
    val endpointOptions: List<VpnEndpointOption> = emptyList(),
    val isAuthenticated: Boolean,
    val backendAccessToken: String? = null,
    val backendRefreshToken: String? = null,
    val backendRefreshRequestId: String? = null,
    val backendAccessTokenExpiresInSeconds: Long? = null,
    val backendRefreshExpiresAt: String? = null,
    val backendDeviceKey: String = "",
    val backendDeviceId: String = "",
    val backendDeviceAccessRole: String = "owner",
    val ruRelaySelectionRevision: Long = 0L,
)

@Immutable
data class DeviceSession(
    val id: String,
    val title: String,
    val subtitle: String,
    val isCurrent: Boolean,
    val isOnline: Boolean,
    val isActive: Boolean = true,
    val accessRole: String = "owner",
    val trafficPool: String = "primary",
)

@Immutable
data class ServerLocation(
    val code: String,
    val countryCode: String = "",
    val country: String,
    val city: String,
    val host: String,
    val capacityMbps: Int? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val latencyMs: Int? = null,
    val loadPercent: Int? = null,
    val isOnline: Boolean,
    val servers: List<VpnServer> = emptyList(),
)

@Immutable
data class VpnServer(
    val id: String,
    val name: String,
    val countryCode: String,
    val locationCode: String,
    val host: String,
    val probePort: Int?,
    val isOnline: Boolean,
    val capacityMbps: Int? = null,
    val loadPercent: Int? = null,
    val metricsAt: String? = null,
    val weight: Int = 1,
    val latencyMs: Int? = null,
    val multiHopEntryAvailable: Boolean = false,
    val multiHopExitAvailable: Boolean = false,
    val probeHost: String? = null,
    val probeIp: String? = null,
)

@Immutable
data class UsageBar(
    val label: String,
    val value: Float,
    val highlight: Boolean = false,
)

@Immutable
data class DailyStats(
    val date: String,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val onlineSeconds: Long = 0L,
    val sessions: Int = 0,
    val pingSumMs: Long = 0L,
    val pingSamples: Int = 0,
) {
    val totalBytes: Long
        get() = (rxBytes + txBytes).coerceAtLeast(0L)
}

data class AppLogEntry(
    val timestamp: String,
    val level: String,
    val category: String,
    val message: String,
    val details: String? = null,
    val appVersion: String? = null,
    val androidVersion: String? = null,
    val deviceModel: String? = null,
    val errorType: String? = null,
    val serverCountry: String? = null,
    val apiResponseTimeMs: Long? = null,
    val connectionSuccess: Boolean? = null,
    val endpointRating: String? = null,
)

data class PlanSummary(
    val code: String,
    val tier: String,
    val title: String,
    val devices: Int,
    val trafficLimitGb: Double?,
    val trafficLabel: String,
    val monthlyPriceRub: Int,
    val yearlyMonthlyPriceRub: Int?,
    val badgeColor: String? = null,
    val headline: String? = null,
    val features: List<String>,
    val isRecommended: Boolean = false,
)
