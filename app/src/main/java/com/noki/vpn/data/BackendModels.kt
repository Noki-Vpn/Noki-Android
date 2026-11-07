package com.noki.vpn.data

import okio.ByteString.Companion.decodeBase64

class BackendException(
    override val message: String,
    val statusCode: Int,
    val retryAfterMillis: Long? = null,
) : IllegalStateException(message)

class AuthRefreshRejectedException(
    val rejection: BackendException,
) : IllegalStateException("auth_refresh_rejected", rejection)

data class BackendPaymentMethod(val id: Int?, val code: String, val label: String, val enabled: Boolean)
data class BackendPromo(val kind: String, val value: Int, val code: String?, val amountRub: Int?)

data class BackendPaymentConfig(
    val configured: Boolean,
    val checkoutEnabled: Boolean,
    val methods: List<BackendPaymentMethod>,
)

data class BackendPayment(
    val publicId: String,
    val planCode: String,
    val status: String,
    val amountRub: Int,
    val paymentUrl: String?,
    val createdAt: String? = null,
    val description: String? = null,
)

data class BackendUser(
    val id: String,
    val username: String,
    val email: String,
    val avatarUrl: String?,
    val isActive: Boolean,
    val isAdmin: Boolean,
    val hasRealEmail: Boolean = true,
    val hasPassword: Boolean = true,
    val telegramLinked: Boolean = false,
    val credentialsSetupRequired: Boolean = false,
)

data class BackendAccountPasswordChange(
    val user: BackendUser,
    val tokens: BackendAuthTokens?,
)

data class BackendSubscription(
    val status: String,
    val planCode: String?,
    val expiresAt: String?,
    val trafficUsedGb: Double?,
    val trafficLimitGb: Double?,
    val planName: String? = null,
    val planTier: String? = null,
    val planBadgeColor: String? = null,
    val primaryDeviceLimit: Int? = null,
    val sharedTrafficUsedGb: Double? = null,
    val sharedTrafficLimitGb: Double? = null,
    val sharedPeriodStart: String? = null,
    val sharedPeriodEnd: String? = null,
    val sharedFreeOnly: Boolean = false,
    val trialTrafficRemainingGb: Double? = null,
    val freeLocationsOnly: Boolean = false,
)

data class BackendVpnAccess(
    val canConnect: Boolean,
    val reason: String?,
    val planCode: String?,
)

data class BackendAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "bearer",
    val expiresInSeconds: Long? = null,
    val refreshExpiresAt: String? = null,
    val credentialsSetupRequired: Boolean = false,
)

data class BackendDevice(
    val id: String,
    val deviceKey: String,
    val deviceName: String,
    val customName: String? = null,
    val platform: String,
    val accessRole: String,
    val isActive: Boolean,
    val lastSeenAt: String?,
    val trafficPool: String = "primary",
)

data class BackendIncyDevice(
    val id: String,
    val name: String,
    val status: String = "waiting",
    val isSlotActive: Boolean = true,
    val hwidFingerprint: String? = null,
    val deviceOs: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val appVersion: String? = null,
    val generation: Int = 1,
    val endpointCount: Int = 0,
    val trafficBytes: Long = 0,
    val boundAt: String? = null,
    val lastRequestedAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val trafficPool: String = "primary",
)

data class BackendIncyDeviceCreate(
    val device: BackendIncyDevice,
    val importLink: String,
    val v2raynSubscriptionUrl: String? = null,
)

@JvmInline
value class IncyImportLink private constructor(val value: String) {
    companion object {
        private const val PREFIX = "incy://crypt1/"
        private const val MAX_LENGTH = 8_192
        private val PAYLOAD = Regex("^[A-Za-z0-9_-]+$")

        fun parse(raw: String): IncyImportLink {
            val value = raw.trim()
            require(value.length in (PREFIX.length + 1)..MAX_LENGTH) { "invalid_incy_import_link" }
            require(value.startsWith(PREFIX)) { "invalid_incy_import_link" }
            val payload = value.removePrefix(PREFIX)
            require(PAYLOAD.matches(payload)) { "invalid_incy_import_link" }
            val decoded = payload.decodeBase64()
            require(decoded != null && decoded.size >= 29) { "invalid_incy_import_link" }
            return IncyImportLink(value)
        }
    }
}

@JvmInline
value class V2raynSubscriptionUrl private constructor(val value: String) {
    companion object {
        private const val MAX_LENGTH = 8_192

        fun parse(raw: String): V2raynSubscriptionUrl {
            val value = raw.trim()
            require(value.length in 1..MAX_LENGTH) { "invalid_v2rayn_subscription_url" }
            val uri = java.net.URI(value)
            require(
                uri.scheme == "https" &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.fragment == null &&
                    uri.path.contains("/v2rayn/subscriptions/")
            ) { "invalid_v2rayn_subscription_url" }
            return V2raynSubscriptionUrl(value)
        }
    }
}

data class IncyConnectionLinks(
    val importLink: IncyImportLink,
    val v2raynSubscriptionUrl: V2raynSubscriptionUrl? = null,
)

data class BackendDeviceInvite(
    val inviteCode: String,
    val expiresAt: String?,
)

data class BackendInviteAcceptPayload(
    val tokens: BackendAuthTokens,
    val device: BackendDevice,
)

data class BackendDeviceChallenge(
    val deviceId: String,
    val nonce: String,
    val expiresAt: String?,
)

data class BackendLocation(
    val id: String,
    val code: String,
    val name: String,
    val nameRu: String?,
    val nameEn: String?,
    val entryHost: String,
    val countryCode: String,
    val isOnline: Boolean,
    val capacityMbps: Int?,
    val downloadMbps: Double?,
    val uploadMbps: Double?,
    val latencyMs: Int?,
    val loadPercent: Int?,
    val servers: List<VpnServer> = emptyList(),
)

data class BackendPlan(
    val code: String,
    val name: String,
    val tier: String,
    val billingPeriodMonths: Int,
    val priceRub: Int,
    val monthlyEquivalentRub: Int,
    val deviceLimit: Int,
    val trafficLimitGb: Double?,
    val speedProfile: String,
    val features: List<String>,
    val headline: String?,
    val badgeColor: String?,
    val isActive: Boolean,
    val sortOrder: Int,
    val sharedTrafficLimitGb: Double? = null,
)

data class BackendAppNotification(
    val id: String,
    val title: String,
    val message: String,
    val createdAt: String,
    val action: String? = null,
)

data class BackendAndroidUpdate(
    val updateAvailable: Boolean,
    val versionCode: Long?,
    val versionName: String?,
    val releaseNotes: String?,
    val isForced: Boolean,
    val architecture: String?,
    val apkUrl: String?,
    val apkSha256: String?,
    val apkSizeBytes: Long?,
)

data class BackendVpnSession(
    val canConnect: Boolean,
    val profileCode: String,
    val locationCode: String,
    val locationName: String,
    val endpointCode: String?,
    val entryHost: String,
    val entryPort: Int,
    val serverName: String,
    val proxyType: String,
    val transport: String,
    val transportMode: String?,
    val security: String,
    val fingerprint: String?,
    val requestHost: String?,
    val path: String?,
    val alpn: String?,
    val allowInsecure: Boolean,
    val enableMux: Boolean,
    val randomUserAgent: Boolean,
    val publicKey: String?,
    val shortId: String?,
    val vpnUsername: String,
    val vpnSecret: String,
    val flow: String?,
    val planCode: String?,
    val endpointCandidates: List<BackendEndpointCandidate>,
    val connectIp: String? = null,
    val multiHop: BackendMultiHopSession? = null,
    val nokinvToken: String = "",
    val nokinvCredentialGeneration: Int = 0,
)

data class BackendMultiHopSession(
    val selection: MultiHopSettings,
    val entryNodeId: String,
    val exitNodeId: String,
    val entryIp: String,
    val policyHash: String,
    val version: Int = 2,
)

data class RuWireGuardSessionExpectation(
    val requestId: String,
    val publicKeyId: String,
    val exitNodeId: String,
    val relaySelection: RuRelaySelection,
    val entryNodeId: String? = null,
    val routeVersion: Int = 1,
)

data class RuWireGuardSessionRequest(
    val deviceId: String,
    val deviceKey: String?,
    val deviceNonce: String,
    val deviceSignature: String,
    val requestId: String,
    val publicKeyId: String,
    val exitNodeId: String,
    val relaySelection: RuRelaySelection,
    val relayRttSamples: List<RuRelayRttSample> = emptyList(),
    val entryNodeId: String? = null,
    val routeVersion: Int = 1,
)

data class RuRelayRttSample(val nodeId: String, val rttMs: Int)

data class BackendRuWireGuardKey(
    val id: String,
    val publicKey: String,
)

data class BackendRuWireGuardIdentity(
    val requestId: String,
    val sessionId: String,
    val generation: Int,
    val publicKeyId: String,
    val relaySelection: RuRelaySelection,
    val relayNodeId: String,
    val exitNodeId: String,
    val routeVersion: Int,
    val routeId: String,
    val routeGeneration: Int,
    val entryNodeId: String?,
    val leaseExpiresAtEpochMillis: Long?,
    val revokeToken: String? = null,
) {
    fun hasSameAuthorization(other: BackendRuWireGuardIdentity): Boolean =
        copy(leaseExpiresAtEpochMillis = null) == other.copy(leaseExpiresAtEpochMillis = null)
}

data class PendingRuWireGuardRevoke(
    val sessionId: String,
    val revokeToken: String,
    val provisional: Boolean = false,
)

data class BackendRuWireGuardConfig(
    val endpoint: String,
    val serverPublicKey: String,
    val address: String,
    val dns: List<String>,
    val mtu: Int,
    val allowedIps: List<String>,
)

sealed interface BackendRuWireGuardSession {
    val identity: BackendRuWireGuardIdentity

    data class Pending(override val identity: BackendRuWireGuardIdentity) : BackendRuWireGuardSession
    data class Ready(
        override val identity: BackendRuWireGuardIdentity,
        val wireguard: BackendRuWireGuardConfig,
    ) : BackendRuWireGuardSession
}

data class BackendTemporaryVpnChallenge(
    val nonce: String,
    val expiresInSeconds: Long,
)

data class BackendTemporaryVpnSession(
    val mode: String,
    val sessionId: String,
    val controlToken: String,
    val trafficLimitBytes: Long,
    val expiresAtEpochMillis: Long,
    val vpnSession: BackendVpnSession,
)

data class BackendEndpointCandidate(
    val code: String,
    val nodeId: String?,
    val label: String,
    val locationCode: String,
    val locationName: String,
    val entryHost: String,
    val entryPort: Int,
    val serverName: String,
    val proxyType: String,
    val transport: String,
    val transportMode: String?,
    val security: String,
    val fingerprint: String?,
    val requestHost: String?,
    val path: String?,
    val alpn: String?,
    val allowInsecure: Boolean,
    val enableMux: Boolean,
    val randomUserAgent: Boolean,
    val publicKey: String?,
    val shortId: String?,
    val flow: String?,
    val priority: Int,
    val weight: Int,
    val canaryOnly: Boolean,
    val tags: List<String>,
    val connectIp: String? = null,
)

internal fun BackendEndpointCandidate.connectionHost(): String =
    connectIp.orEmpty().ifBlank { entryHost }

data class BootstrapPayload(
    val user: BackendUser,
    val subscription: BackendSubscription,
    val plans: List<BackendPlan>,
    val locations: List<BackendLocation>,
    val devices: List<BackendDevice>,
    val paymentsReady: Boolean,
)
