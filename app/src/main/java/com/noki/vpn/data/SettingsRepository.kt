package com.noki.vpn.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import com.noki.vpn.FcmRegistrationStatePolicy
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

internal const val SETTINGS_PREFERENCES_NAME = "noki_settings"
internal const val SETTINGS_ENCRYPTED_KEY = "settings_json_encrypted_v2"

internal object SettingsAtomicUpdate {
    fun <T, R> transform(
        lock: Any,
        load: () -> T,
        transform: (T) -> Pair<T, R>,
        save: (T) -> Unit,
    ): R {
        return synchronized(lock) {
            val (updated, result) = transform(load())
            save(updated)
            result
        }
    }
}

internal fun isFreshPackageInstall(
    firstInstallTime: Long,
    lastUpdateTime: Long,
): Boolean = firstInstallTime > 0L && firstInstallTime == lastUpdateTime

private data class AuthSettingsTransition(
    val settings: StoredSettings,
    val refreshTokenToRevoke: String? = null,
    val refreshCommitted: Boolean = false,
)

class SettingsRepository(private val context: Context) : VpnSessionStore, EndpointHealthEventStore,
    AtomicAuthSettingsStore, TemporaryVpnLeaseStore, PendingLogoutRevocationStore {
    private val preferences = context.getSharedPreferences(SETTINGS_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val settingsCodec = StoredSettingsCodec(::defaultSettings)
    private val cipher = SettingsCipher()
    private val markersStore = SettingsMarkersStore(preferences, ::appVersionCode)

    internal fun ensureSupportGuestToken(): String = synchronized(SETTINGS_LOCK) {
        val key = "support_guest_token_encrypted_v1"
        preferences.getString(key, null)?.let(cipher::decrypt)
            ?.takeIf { it.matches(Regex("support-guest\\.[0-9a-f]{64}")) }
            ?: ("support-guest." + ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }).also { token ->
                check(preferences.edit().putString(key, cipher.encrypt(token)).commit()) { "support_guest_persist_failed" }
            }
    }

    fun loadInstallOnboardingStep() = markersStore.loadInstallOnboardingStep()

    fun saveInstallOnboardingStep(step: InstallOnboardingStep) = markersStore.saveInstallOnboardingStep(step)

    fun clearVpnRuntimeState() = markersStore.clearVpnRuntimeState()

    fun claimFreeTrialNotification(userId: String) = markersStore.claimFreeTrialNotification(userId)

    fun claimFreeTrialDialog(userId: String) = markersStore.claimFreeTrialDialog(userId)

    override fun loadTemporaryVpnLease(): TemporaryVpnLease? = synchronized(TEMPORARY_VPN_LEASE_LOCK) {
        preferences.getString(KEY_TEMPORARY_VPN_LEASE_ENCRYPTED, null)
            ?.let(cipher::decrypt)
            ?.let(TemporaryVpnLeaseCodec::decode)
    }

    override fun saveTemporaryVpnLease(lease: TemporaryVpnLease) {
        synchronized(TEMPORARY_VPN_LEASE_LOCK) {
            val persisted = preferences.edit()
                .putString(
                    KEY_TEMPORARY_VPN_LEASE_ENCRYPTED,
                    cipher.encrypt(TemporaryVpnLeaseCodec.encode(lease)),
                )
                .commit()
            check(persisted) { "temporary_vpn_lease_persist_failed" }
        }
    }

    override fun clearTemporaryVpnLease() {
        synchronized(TEMPORARY_VPN_LEASE_LOCK) {
            val persisted = preferences.edit()
                .remove(KEY_TEMPORARY_VPN_LEASE_ENCRYPTED)
                .commit()
            check(persisted) { "temporary_vpn_lease_clear_failed" }
        }
    }

    override fun loadTemporaryVpnPendingRevoke(): TemporaryVpnPendingRevoke? =
        synchronized(TEMPORARY_VPN_LEASE_LOCK) {
            preferences.getString(KEY_TEMPORARY_VPN_PENDING_REVOKE_ENCRYPTED, null)
                ?.let(cipher::decrypt)
                ?.let(TemporaryVpnPendingRevokeCodec::decode)
        }

    override fun markTemporaryVpnLeasePendingRevoke(lease: TemporaryVpnLease) {
        val pending = TemporaryVpnPendingRevoke(
            sessionId = lease.sessionId,
            controlToken = lease.controlToken,
            expiresAtEpochMillis = lease.expiresAtEpochMillis,
        )
        synchronized(TEMPORARY_VPN_LEASE_LOCK) {
            val persisted = preferences.edit()
                .putString(
                    KEY_TEMPORARY_VPN_PENDING_REVOKE_ENCRYPTED,
                    cipher.encrypt(TemporaryVpnPendingRevokeCodec.encode(pending)),
                )
                .remove(KEY_TEMPORARY_VPN_LEASE_ENCRYPTED)
                .commit()
            check(persisted) { "temporary_vpn_pending_revoke_persist_failed" }
        }
    }

    override fun clearTemporaryVpnPendingRevoke() {
        synchronized(TEMPORARY_VPN_LEASE_LOCK) {
            val persisted = preferences.edit()
                .remove(KEY_TEMPORARY_VPN_PENDING_REVOKE_ENCRYPTED)
                .commit()
            check(persisted) { "temporary_vpn_pending_revoke_clear_failed" }
        }
    }

    fun nextEntryRouteGeneration(): Long = synchronized(RU_WIREGUARD_REVOKE_LOCK) {
        val generation = maxOf(System.currentTimeMillis(), preferences.getLong("entry_route_generation", 0L) + 1L)
        check(preferences.edit().putLong("entry_route_generation", generation).commit()) { "entry_route_generation_persist_failed" }
        generation
    }

    internal fun readRuEntry(userId: String, deviceId: String, publicKey: String): JSONObject? {
        val encrypted = preferences.getString("ru_entry_credential", null) ?: return null
        return runCatching { JSONObject(checkNotNull(cipher.decrypt(encrypted))) }.getOrNull()?.takeIf {
            it.optString("user_id") == userId && it.optString("device_id") == deviceId && it.optString("public_key") == publicKey
        }
    }

    internal fun saveRuEntry(userId: String, deviceId: String, publicKey: String, credential: RuEntryCredential, exits: List<RuForeignNode>) {
        val json = RuWireGuardSessionJsonParser.encodeEntryCredential(credential)
            .put("user_id", userId).put("device_id", deviceId).put("public_key", publicKey)
            .put("exits", JSONArray(exits.map { exit -> JSONObject().put("id", exit.id).put("name", exit.name).put("country_code", exit.countryCode) }))
        check(preferences.edit().putString("ru_entry_credential", cipher.encrypt(json.toString())).commit()) {
            "ru_entry_credential_persist_failed"
        }
    }

    fun loadPendingRuWireGuardRevokes(): List<PendingRuWireGuardRevoke> =
        synchronized(RU_WIREGUARD_REVOKE_LOCK) { loadPendingRuWireGuardRevokesLocked() }

    private fun loadPendingRuWireGuardRevokesLocked(): List<PendingRuWireGuardRevoke> {
        val encrypted = preferences.getString(KEY_RU_WIREGUARD_PENDING_REVOKE, null) ?: return emptyList()
        val json = JSONArray(checkNotNull(cipher.decrypt(encrypted)) { "ru_revoke_decrypt_failed" })
        return (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            PendingRuWireGuardRevoke(
                sessionId = item.getString("session_id"),
                revokeToken = item.getString("revoke_token"),
                provisional = item.optBoolean("provisional", false),
            )
        }
    }

    private fun savePendingRuWireGuardRevokesLocked(items: List<PendingRuWireGuardRevoke>) {
        val editor = preferences.edit()
        if (items.isEmpty()) editor.remove(KEY_RU_WIREGUARD_PENDING_REVOKE) else {
            val json = JSONArray()
            items.forEach { pending -> json.put(JSONObject()
                .put("session_id", pending.sessionId)
                .put("revoke_token", pending.revokeToken)
                .put("provisional", pending.provisional)) }
            editor.putString(KEY_RU_WIREGUARD_PENDING_REVOKE, cipher.encrypt(json.toString()))
        }
        check(editor.commit()) { "ru_revoke_persist_failed" }
    }

    fun stageRuWireGuardRevoke(pending: PendingRuWireGuardRevoke) {
        synchronized(RU_WIREGUARD_REVOKE_LOCK) {
            val existing = loadPendingRuWireGuardRevokesLocked()
            val previous = existing.firstOrNull { it.sessionId == pending.sessionId }
            check(previous == null || previous.revokeToken == pending.revokeToken) {
                "ru_revoke_token_changed"
            }
            if (previous == pending || (previous != null && !previous.provisional)) return@synchronized
            savePendingRuWireGuardRevokesLocked(existing.filterNot { it.sessionId == pending.sessionId } + pending)
        }
    }

    fun recoverProvisionalRuWireGuardRevokes(
        activeSessionId: String?,
        preparationActive: Boolean,
    ): Boolean = synchronized(RU_WIREGUARD_REVOKE_LOCK) {
        val existing = loadPendingRuWireGuardRevokesLocked()
        val recovered = existing.map { pending ->
            if (pending.provisional && !preparationActive && pending.sessionId != activeSessionId)
                pending.copy(provisional = false) else pending
        }
        if (recovered != existing) savePendingRuWireGuardRevokesLocked(recovered)
        recovered.any { !it.provisional }
    }

    fun clearPendingRuWireGuardRevoke(sessionId: String) {
        synchronized(RU_WIREGUARD_REVOKE_LOCK) {
            val existing = loadPendingRuWireGuardRevokesLocked()
            if (existing.any { it.sessionId == sessionId })
                savePendingRuWireGuardRevokesLocked(existing.filterNot { it.sessionId == sessionId })
        }
    }

    fun clearProvisionalRuWireGuardRevoke(sessionId: String, revokeToken: String) {
        synchronized(RU_WIREGUARD_REVOKE_LOCK) {
            val existing = loadPendingRuWireGuardRevokesLocked()
            if (existing.none { it.sessionId == sessionId && it.revokeToken == revokeToken && it.provisional })
                return@synchronized
            savePendingRuWireGuardRevokesLocked(existing.filterNot {
                it.sessionId == sessionId && it.revokeToken == revokeToken && it.provisional
            })
        }
    }

    override fun loadPendingLogoutRevocations(): List<String> =
        synchronized(PENDING_LOGOUT_REVOCATION_LOCK) {
            loadPendingLogoutRevocationsLocked()
        }

    override fun removePendingLogoutRevocation(refreshToken: String) {
        synchronized(PENDING_LOGOUT_REVOCATION_LOCK) {
            val current = loadPendingLogoutRevocationsLocked()
            val remaining = current.filterNot { it == refreshToken }
            if (remaining == current) return@synchronized
            val editor = preferences.edit()
            if (remaining.isEmpty()) {
                editor.remove(KEY_PENDING_LOGOUT_REVOCATIONS_ENCRYPTED)
            } else {
                editor.putString(
                    KEY_PENDING_LOGOUT_REVOCATIONS_ENCRYPTED,
                    cipher.encrypt(PendingLogoutRevocationCodec.encode(remaining)),
                )
            }
            check(editor.commit()) { "pending_logout_revocation_clear_failed" }
        }
    }

    private fun loadPendingLogoutRevocationsLocked(): List<String> =
        PendingLogoutRevocationCodec.decode(
            preferences.getString(KEY_PENDING_LOGOUT_REVOCATIONS_ENCRYPTED, null)
                ?.let(cipher::decrypt),
        )

    fun loadDailyStats(): List<DailyStats> {
        return synchronized(STATS_LOCK) {
            loadDailyStatsLocked()
        }
    }

    fun recordDailyStatsSessionStart(
        date: String,
        pingMs: Int? = null,
    ): List<DailyStats> {
        return mutateDailyStats { stats ->
            val current = stats[date] ?: DailyStats(date = date)
            stats[date] = current.copy(
                sessions = current.sessions + 1,
                pingSumMs = current.pingSumMs + (pingMs?.coerceAtLeast(0) ?: 0),
                pingSamples = current.pingSamples + if (pingMs != null) 1 else 0,
            )
        }
    }

    fun addDailyStatsDelta(
        date: String,
        rxBytes: Long,
        txBytes: Long,
        onlineSeconds: Long,
    ): List<DailyStats> {
        if (rxBytes <= 0L && txBytes <= 0L && onlineSeconds <= 0L) {
            return loadDailyStats()
        }
        return mutateDailyStats { stats ->
            val current = stats[date] ?: DailyStats(date = date)
            stats[date] = current.copy(
                rxBytes = (current.rxBytes + rxBytes.coerceAtLeast(0L)).coerceAtLeast(0L),
                txBytes = (current.txBytes + txBytes.coerceAtLeast(0L)).coerceAtLeast(0L),
                onlineSeconds = (current.onlineSeconds + onlineSeconds.coerceAtLeast(0L)).coerceAtLeast(0L),
            )
        }
    }

    fun addDailyStatsPingSample(
        date: String,
        pingMs: Int,
    ): List<DailyStats> {
        val safePing = pingMs.coerceAtLeast(0)
        return mutateDailyStats { stats ->
            val current = stats[date] ?: DailyStats(date = date)
            stats[date] = current.copy(
                pingSumMs = current.pingSumMs + safePing,
                pingSamples = current.pingSamples + 1,
            )
        }
    }

    fun recordAppLog(
        category: String,
        level: String = "info",
        message: String,
        details: String? = null,
        errorType: String? = null,
        serverCountry: String? = null,
        apiResponseTimeMs: Long? = null,
        connectionSuccess: Boolean? = null,
        endpointRating: String? = null,
    ) {
        if (com.noki.vpn.BuildConfig.DIAGNOSTIC_LOGGING) {
            // Only structured event codes; never emit details, credentials or destinations.
            val eventCode = message.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,95}")) }
            if (eventCode != null) {
                android.util.Log.i("NokiDiagnostic", "event=$eventCode; api_ms=${apiResponseTimeMs ?: -1}")
            }
        }
        if (!AppDiagnosticLogPolicy.shouldStoreAppLog(load().advancedSettings)) return
        synchronized(LOGS_LOCK) {
            val logs = loadAppLogsLocked().toMutableList()
            val safeLevel = level.take(32).ifBlank { "info" }
            logs += AppLogEntry(
                timestamp = Instant.now().toString(),
                level = safeLevel,
                category = category.take(64).ifBlank { "app" },
                message = AppDiagnosticRedactor.redact(message).take(512),
                details = AppDiagnosticRedactor.redactNullable(details)?.take(2048),
                appVersion = appVersionLabel(),
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                deviceModel = deviceModelLabel(),
                errorType = AppDiagnosticRedactor.redactNullable(errorType)?.take(128)
                    ?: AppDiagnosticRedactor.redact(message).takeIf { safeLevel.equals("error", ignoreCase = true) }?.take(128),
                serverCountry = AppDiagnosticRedactor.redactNullable(serverCountry)?.take(128),
                apiResponseTimeMs = apiResponseTimeMs?.coerceAtLeast(0L),
                connectionSuccess = connectionSuccess,
                endpointRating = AppDiagnosticRedactor.redactNullable(endpointRating)?.take(4096),
            )
            saveAppLogsLocked(pruneAppLogs(logs))
        }
    }

    fun loadAppLogs(): List<AppLogEntry> {
        return synchronized(LOGS_LOCK) {
            loadAppLogsLocked()
        }
    }

    fun enqueueVpnIncident(incident: VpnIncidentReport, expected: StoredSettings = load()) = synchronized(SETTINGS_LOCK) {
        val current = loadSettingsLocked()
        if (current.hasSameAuthSessionAs(expected) &&
            AppDiagnosticLogPolicy.shouldUploadAutomatically(current.advancedSettings)
        ) saveVpnIncidentsLocked(loadVpnIncidentsLocked().filterNot { it.id == incident.id } + incident)
    }

    fun loadPendingVpnIncidents(): List<VpnIncidentReport> = synchronized(SETTINGS_LOCK) {
        loadVpnIncidentsLocked()
    }

    fun removePendingVpnIncident(incidentId: String) = synchronized(SETTINGS_LOCK) {
        saveVpnIncidentsLocked(loadVpnIncidentsLocked().filterNot { it.id == incidentId })
    }

    fun clearAppLogs() {
        synchronized(LOGS_LOCK) {
            preferences.edit {
                remove(KEY_APP_LOGS_ENCRYPTED)
                remove(KEY_APP_LOGS)
            }
        }
    }

    fun exportAppLogs(): String = AppLogStore.export(loadAppLogs())

    override fun loadEndpointHealth(): Map<String, EndpointHealth> {
        return synchronized(ENDPOINT_HEALTH_LOCK) {
            EndpointHealthScope.forNetwork(
                raw = loadEndpointHealthLocked(),
                networkKind = EndpointRankingPolicy.NetworkKind.OTHER,
            )
        }
    }

    override fun loadEndpointHealth(networkKind: EndpointRankingPolicy.NetworkKind): Map<String, EndpointHealth> {
        return synchronized(ENDPOINT_HEALTH_LOCK) {
            EndpointHealthScope.forNetwork(
                raw = loadEndpointHealthLocked(),
                networkKind = networkKind,
            )
        }
    }

    fun recordEndpointResult(
        endpointCode: String,
        success: Boolean,
        slow: Boolean = false,
        latencyMs: Long? = null,
        networkKind: EndpointRankingPolicy.NetworkKind = EndpointRankingPolicy.NetworkKind.OTHER,
    ): EndpointHealth? {
        val code = endpointCode.trim()
        if (code.isBlank()) return null
        var updated: EndpointHealth? = null
        synchronized(ENDPOINT_HEALTH_LOCK) {
            val health = loadEndpointHealthLocked().toMutableMap()
            val now = System.currentTimeMillis()
            val key = EndpointHealthScope.key(networkKind, code)
            val previous = health[key] ?: EndpointHealthScope.forNetwork(health, networkKind)[code]
                ?: EndpointHealth()
            updated = EndpointRankingPolicy.updateAfterResult(
                previous = previous,
                success = success,
                nowMillis = now,
                slow = slow,
                latencyMs = latencyMs,
            )
            health[key] = updated
            saveEndpointHealthLocked(health)
        }
        return updated
    }

    fun endpointRatingSnapshot(
        endpointCodes: List<String>,
        networkKind: EndpointRankingPolicy.NetworkKind = EndpointRankingPolicy.NetworkKind.OTHER,
    ): String {
        return EndpointRankingPolicy.ratingSnapshot(endpointCodes, loadEndpointHealth(networkKind))
    }

    override fun nextEndpointRotationIndex(rotationKey: String): Int {
        val safeKey = rotationKey.trim().ifBlank { "default" }
        return synchronized(ENDPOINT_ROTATION_LOCK) {
            val key = KEY_ENDPOINT_ROTATION_PREFIX + Base64.encodeToString(
                safeKey.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
            val current = preferences.getInt(key, 0)
            val next = if (current == Int.MAX_VALUE) 0 else current + 1
            preferences.edit { putInt(key, next) }
            current
        }
    }

    fun shouldUploadAppLogsAutomatically(): Boolean =
        AppDiagnosticLogPolicy.shouldUploadAutomatically(load().advancedSettings) &&
            markersStore.shouldUploadAppLogsAutomatically()

    fun markAppLogsAutomaticallyUploaded() =
        markersStore.markAppLogsAutomaticallyUploaded()

    fun loadSeenAppNotificationIds(): Set<String> =
        markersStore.loadSeenAppNotificationIds()

    fun markAppNotificationsSeen(ids: Collection<String>) =
        markersStore.markAppNotificationsSeen(ids)

    internal fun loadAppNotificationHistoryState(
        settings: StoredSettings = load(),
    ): StoredAppNotificationHistory =
        synchronized(APP_NOTIFICATION_HISTORY_LOCK) {
            val key = appNotificationHistoryKey(settings)
                ?: return@synchronized StoredAppNotificationHistory()
            AppNotificationHistoryStore.decodeState(
                preferences.getString(key, null)?.let(cipher::decrypt),
            )
        }

    fun acceptAppNotification(
        notification: BackendAppNotification,
        settings: StoredSettings = load(),
        onAccepted: () -> Unit,
    ): Boolean =
        synchronized(SETTINGS_LOCK) {
            if (!AppNotificationHistoryStore.canPersistForSession(
                    isAuthenticated = settings.isAuthenticated,
                    accessToken = settings.backendAccessToken,
                    userId = settings.userProfile.backendUserId,
                    deviceId = settings.backendDeviceId,
                )
            ) {
                return@synchronized false
            }
            val key = appNotificationHistoryKey(settings) ?: return@synchronized false
            val currentSettings = loadSettingsLocked()
            if (!AppNotificationHistoryStore.canPersistForSession(
                    isAuthenticated = currentSettings.isAuthenticated,
                    accessToken = currentSettings.backendAccessToken,
                    userId = currentSettings.userProfile.backendUserId,
                    deviceId = currentSettings.backendDeviceId,
                ) || appNotificationHistoryKey(currentSettings) != key
            ) {
                return@synchronized false
            }
            synchronized(APP_NOTIFICATION_HISTORY_LOCK) {
                val current = AppNotificationHistoryStore.decodeState(
                    preferences.getString(key, null)?.let(cipher::decrypt),
                )
                val updated = AppNotificationHistoryStore.mergeState(current, notification)
                preferences.edit {
                    putString(
                        key,
                        cipher.encrypt(AppNotificationHistoryStore.encodeState(updated)),
                    )
                }
                onAccepted()
            }
            true
        }

    internal fun openAppNotificationHistory(
        settings: StoredSettings = load(),
    ): StoredAppNotificationHistory = mutateAppNotificationHistory(settings) { current ->
        AppNotificationHistoryStore.markRead(current)
    }

    internal fun deleteAppNotification(
        notificationId: String,
        settings: StoredSettings = load(),
    ): StoredAppNotificationHistory = mutateAppNotificationHistory(settings) { current ->
        AppNotificationHistoryStore.remove(current, notificationId)
    }

    private fun mutateAppNotificationHistory(
        settings: StoredSettings,
        transform: (StoredAppNotificationHistory) -> StoredAppNotificationHistory,
    ): StoredAppNotificationHistory = synchronized(SETTINGS_LOCK) {
        val key = appNotificationHistoryKey(settings)
            ?: return@synchronized StoredAppNotificationHistory()
        val currentSettings = loadSettingsLocked()
        if (appNotificationHistoryKey(currentSettings) != key) {
            return@synchronized StoredAppNotificationHistory()
        }
        synchronized(APP_NOTIFICATION_HISTORY_LOCK) {
            val current = AppNotificationHistoryStore.decodeState(
                preferences.getString(key, null)?.let(cipher::decrypt),
            )
            val updated = transform(current)
            preferences.edit {
                putString(
                    key,
                    cipher.encrypt(AppNotificationHistoryStore.encodeState(updated)),
                )
            }
            updated
        }
    }

    fun clearAppNotificationHistory(settings: StoredSettings = load()) =
        synchronized(APP_NOTIFICATION_HISTORY_LOCK) {
            appNotificationHistoryKey(settings)?.let { key ->
                preferences.edit { remove(key) }
            }
        }

    private fun appNotificationHistoryKey(settings: StoredSettings): String? {
        val deviceId = settings.backendDeviceId.trim().takeIf(String::isNotBlank) ?: return null
        val accountIdentity = settings.userProfile.backendUserId.trim()
            .takeIf(String::isNotBlank) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$accountIdentity\u0000$deviceId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return KEY_APP_NOTIFICATION_HISTORY_ENCRYPTED_PREFIX + digest
    }

    fun loadLastRegisteredFcmTokenHash(): String =
        markersStore.loadLastRegisteredFcmTokenHash()

    fun loadLastRegisteredFcmDeviceId(): String =
        markersStore.loadLastRegisteredFcmDeviceId()

    fun isFcmPushRegistered(): Boolean {
        val currentDeviceId = load().backendDeviceId
        return FcmRegistrationStatePolicy.isRegisteredForCurrentDevice(
            tokenHash = loadLastRegisteredFcmTokenHash(),
            registeredDeviceId = loadLastRegisteredFcmDeviceId(),
            currentDeviceId = currentDeviceId,
        )
    }

    fun isAndroidUpdateAvailable(): Boolean =
        markersStore.isAndroidUpdateAvailable()

    fun markAndroidUpdateAvailable() =
        markersStore.markAndroidUpdateAvailable()

    fun clearAndroidUpdateAvailable() =
        markersStore.clearAndroidUpdateAvailable()

    fun currentAppVersionCode(): Long = appVersionCode()

    fun currentAppVersionName(): String = appVersionName()

    fun clearLastRegisteredFcmTokenHash() =
        markersStore.clearLastRegisteredFcmTokenHash()

    fun saveLastRegisteredFcmTokenHash(tokenHash: String, deviceId: String) =
        markersStore.saveLastRegisteredFcmTokenHash(tokenHash, deviceId)

    override fun loadEndpointHealthEventQueue(): List<EndpointHealthEvent> {
        return synchronized(SETTINGS_LOCK) {
            loadEndpointHealthEventQueueLocked()
        }
    }

    override fun saveEndpointHealthEventQueue(events: List<EndpointHealthEvent>) {
        synchronized(SETTINGS_LOCK) {
            saveEndpointHealthEventQueueLocked(events)
        }
    }

    override fun updateEndpointHealthEventQueue(
        transform: (List<EndpointHealthEvent>) -> List<EndpointHealthEvent>,
    ) {
        synchronized(SETTINGS_LOCK) {
            saveEndpointHealthEventQueueLocked(transform(loadEndpointHealthEventQueueLocked()))
        }
    }

    override fun loadEndpointHealthLastHeartbeatAtMillis(): Long =
        preferences.getLong(KEY_ENDPOINT_HEALTH_LAST_HEARTBEAT_AT, 0L)

    override fun saveEndpointHealthLastHeartbeatAtMillis(value: Long) {
        preferences.edit {
            putLong(KEY_ENDPOINT_HEALTH_LAST_HEARTBEAT_AT, value.coerceAtLeast(0L))
        }
    }

    override fun load(): StoredSettings = synchronized(SETTINGS_LOCK) {
        loadSettingsLocked()
    }

    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
        return SettingsAtomicUpdate.transform(
            lock = SETTINGS_LOCK,
            load = ::loadSettingsLocked,
            transform = transform,
            save = ::saveSettingsLocked,
        )
    }

    override fun clearAuthAndStageRefreshToken(): StoredSettings =
        persistAuthTransition { current ->
            AuthSettingsTransition(
                settings = current.copy(
                    isAuthenticated = false,
                    backendAccessToken = null,
                    backendRefreshToken = null,
                    backendRefreshRequestId = null,
                    backendAccessTokenExpiresInSeconds = null,
                    backendRefreshExpiresAt = null,
                ),
                refreshTokenToRevoke = current.backendRefreshToken,
            )
        }.settings

    override fun commitRefreshedAuth(
        expectedSession: StoredSettings,
        tokens: BackendAuthTokens,
        refreshToken: String,
    ): AuthRefreshCommitResult {
        val transition = persistAuthTransition { current ->
            if (current.hasSameAuthSessionAs(expectedSession)) {
                AuthSettingsTransition(
                    settings = current.copy(
                        backendAccessToken = tokens.accessToken,
                        backendRefreshToken = refreshToken,
                        backendRefreshRequestId = null,
                        backendAccessTokenExpiresInSeconds = tokens.expiresInSeconds,
                        backendRefreshExpiresAt = tokens.refreshExpiresAt,
                    ),
                    refreshCommitted = true,
                )
            } else {
                AuthSettingsTransition(
                    settings = current,
                    refreshTokenToRevoke = refreshToken,
                )
            }
        }
        return AuthRefreshCommitResult(transition.settings, transition.refreshCommitted)
    }

    override fun replaceAuthAndStagePreviousRefreshToken(tokens: BackendAuthTokens): AuthSessionCommitResult {
        val refreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() }
        val transition = persistAuthTransition { current ->
            AuthSettingsTransition(
                settings = current.copy(
                    isAuthenticated = true,
                    userProfile = current.userProfile.copy(credentialsSetupRequired = tokens.credentialsSetupRequired),
                    backendAccessToken = tokens.accessToken,
                    backendRefreshToken = refreshToken,
                    backendRefreshRequestId = null,
                    backendAccessTokenExpiresInSeconds = tokens.expiresInSeconds,
                    backendRefreshExpiresAt = tokens.refreshExpiresAt,
                ),
                refreshTokenToRevoke = current.backendRefreshToken?.takeUnless { it == refreshToken },
            )
        }
        return AuthSessionCommitResult(
            settings = transition.settings,
            previousRefreshTokenStaged = !transition.refreshTokenToRevoke.isNullOrBlank(),
        )
    }

    private fun persistAuthTransition(
        transform: (StoredSettings) -> AuthSettingsTransition,
    ): AuthSettingsTransition = synchronized(PENDING_LOGOUT_REVOCATION_LOCK) {
        synchronized(SETTINGS_LOCK) {
            val previous = loadSettingsLocked()
            val transition = transform(previous)
            val refreshToken = transition.refreshTokenToRevoke
                ?.trim()
                ?.takeIf(String::isNotBlank)
            val pending = (loadPendingLogoutRevocationsLocked() + listOfNotNull(refreshToken)).distinct()
            val editor = preferences.edit()
                .putString(
                    SETTINGS_ENCRYPTED_KEY,
                    cipher.encrypt(settingsCodec.encode(transition.settings)),
                )
                .remove(KEY_SETTINGS)
            if (pending.isEmpty()) {
                editor.remove(KEY_PENDING_LOGOUT_REVOCATIONS_ENCRYPTED)
            } else {
                editor.putString(
                    KEY_PENDING_LOGOUT_REVOCATIONS_ENCRYPTED,
                    cipher.encrypt(PendingLogoutRevocationCodec.encode(pending)),
                )
            }
            if (!transition.refreshCommitted && !previous.hasSameAuthSessionAs(transition.settings)) {
                clearAutomaticTelemetry(editor)
            }
            check(editor.commit()) { "auth_settings_transition_persist_failed" }
            transition
        }
    }

    private fun loadSettingsLocked(): StoredSettings =
        settingsCodec.decode(loadSettingsJson()) { migrated ->
            check(preferences.edit()
                .putString(SETTINGS_ENCRYPTED_KEY, cipher.encrypt(settingsCodec.encode(migrated)))
                .remove(KEY_SETTINGS)
                .commit()) { "settings_migration_persist_failed" }
        }

    private fun saveSettingsLocked(settings: StoredSettings) {
        val previous = loadSettingsLocked()
        preferences.edit {
            if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(settings.advancedSettings) ||
                !previous.hasSameAuthSessionAs(settings)
            ) clearAutomaticTelemetry(this)
            putString(SETTINGS_ENCRYPTED_KEY, cipher.encrypt(settingsCodec.encode(settings)))
            remove(KEY_SETTINGS)
        }
    }

    private fun clearAutomaticTelemetry(editor: android.content.SharedPreferences.Editor) {
        editor.remove(KEY_ENDPOINT_HEALTH_EVENTS_ENCRYPTED)
            .remove(KEY_ENDPOINT_HEALTH_LAST_HEARTBEAT_AT)
            .remove(KEY_VPN_INCIDENTS_ENCRYPTED)
    }

    fun loadInstalledApps(): List<AppInfo> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filterNot { it.packageName == context.packageName }
            .map {
                AppInfo(
                    appName = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    isSystemApp = it.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                        it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                )
            }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun defaultSettings(): StoredSettings {
        val hasStoredSettings = preferences.contains(SETTINGS_ENCRYPTED_KEY) ||
            preferences.contains(KEY_SETTINGS)
        val isFreshInstall = !hasStoredSettings && runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()?.let { packageInfo ->
            isFreshPackageInstall(
                firstInstallTime = packageInfo.firstInstallTime,
                lastUpdateTime = packageInfo.lastUpdateTime,
            )
        } == true
        return DefaultStoredSettingsFactory.create(
            sdkInt = Build.VERSION.SDK_INT,
            isFreshInstall = isFreshInstall,
        )
    }

    override fun ensureBackendDeviceKey(existing: String): String {
        if (DeviceIdentity.isStableDeviceKey(existing)) return existing
        return DeviceIdentity.stableDeviceKey(context)
    }

    private fun loadSettingsJson(): String? {
        preferences.getString(SETTINGS_ENCRYPTED_KEY, null)?.let { encrypted ->
            cipher.decrypt(encrypted)?.let { return it }
        }
        return preferences.getString(KEY_SETTINGS, null)?.also { legacy ->
            if (legacy.isNotBlank()) {
                preferences.edit {
                    putString(SETTINGS_ENCRYPTED_KEY, cipher.encrypt(legacy))
                    remove(KEY_SETTINGS)
                }
            }
        }
    }

    private fun mutateDailyStats(
        mutation: (MutableMap<String, DailyStats>) -> Unit,
    ): List<DailyStats> {
        return synchronized(STATS_LOCK) {
            val stats = loadDailyStatsLocked().associateBy { it.date }.toMutableMap()
            mutation(stats)
            val result = pruneDailyStats(stats.values.toList())
            saveDailyStatsLocked(result)
            result
        }
    }

    private fun loadDailyStatsLocked(): List<DailyStats> {
        val raw = preferences.getString(KEY_DAILY_STATS_ENCRYPTED, null)
            ?.let(cipher::decrypt)
            ?: preferences.getString(KEY_DAILY_STATS, null)
        return DailyStatsStore.decode(raw)
    }

    private fun saveDailyStatsLocked(stats: List<DailyStats>) {
        preferences.edit {
            putString(KEY_DAILY_STATS_ENCRYPTED, cipher.encrypt(DailyStatsStore.encode(stats)))
            remove(KEY_DAILY_STATS)
        }
    }

    private fun loadAppLogsLocked(): List<AppLogEntry> {
        val source = AppLogStorageMigrationPolicy.selectRawSource(
            encryptedRaw = preferences.getString(KEY_APP_LOGS_ENCRYPTED, null)?.let(cipher::decrypt),
            legacyRaw = preferences.getString(KEY_APP_LOGS, null),
        )
        if (source is AppLogStorageMigrationPolicy.RawSource.Empty) return emptyList()
        val raw = when (source) {
            is AppLogStorageMigrationPolicy.RawSource.Encrypted -> source.raw
            is AppLogStorageMigrationPolicy.RawSource.Legacy -> source.raw
            AppLogStorageMigrationPolicy.RawSource.Empty -> return emptyList()
        }
        val logs = AppLogStore.decode(raw)
        if (source is AppLogStorageMigrationPolicy.RawSource.Legacy) {
            return logs.map(::redactAppLogEntry).also(::migrateLegacyAppLogsLocked)
        }
        return logs
    }

    private fun redactAppLogEntry(entry: AppLogEntry): AppLogEntry =
        entry.copy(
            message = AppDiagnosticRedactor.redact(entry.message),
            details = AppDiagnosticRedactor.redactNullable(entry.details),
            errorType = AppDiagnosticRedactor.redactNullable(entry.errorType),
            serverCountry = AppDiagnosticRedactor.redactNullable(entry.serverCountry),
            endpointRating = AppDiagnosticRedactor.redactNullable(entry.endpointRating),
        )

    private fun migrateLegacyAppLogsLocked(logs: List<AppLogEntry>) {
        if (logs.isEmpty()) {
            preferences.edit { remove(KEY_APP_LOGS) }
            return
        }
        saveAppLogsLocked(logs)
    }

    private fun saveAppLogsLocked(logs: List<AppLogEntry>) {
        preferences.edit {
            putString(KEY_APP_LOGS_ENCRYPTED, cipher.encrypt(AppLogStore.encode(logs)))
            remove(KEY_APP_LOGS)
        }
    }

    private fun loadVpnIncidentsLocked(): List<VpnIncidentReport> =
        VpnIncidentStore.decode(
            preferences.getString(KEY_VPN_INCIDENTS_ENCRYPTED, null)?.let(cipher::decrypt),
        )

    private fun saveVpnIncidentsLocked(incidents: List<VpnIncidentReport>) {
        preferences.edit {
            if (incidents.isEmpty()) {
                remove(KEY_VPN_INCIDENTS_ENCRYPTED)
            } else {
                putString(KEY_VPN_INCIDENTS_ENCRYPTED, cipher.encrypt(VpnIncidentStore.encode(incidents)))
            }
        }
    }

    private fun loadEndpointHealthLocked(): Map<String, EndpointHealth> =
        EndpointHealthStore.decode(
            preferences.getString(KEY_ENDPOINT_HEALTH_ENCRYPTED, null)?.let(cipher::decrypt),
        )

    private fun saveEndpointHealthLocked(health: Map<String, EndpointHealth>) {
        preferences.edit {
            putString(KEY_ENDPOINT_HEALTH_ENCRYPTED, cipher.encrypt(EndpointHealthStore.encode(health)))
        }
    }

    private fun loadEndpointHealthEventQueueLocked(): List<EndpointHealthEvent> {
        val raw = preferences.getString(KEY_ENDPOINT_HEALTH_EVENTS_ENCRYPTED, null)
            ?.let(cipher::decrypt)
        return EndpointHealthEvents.decodeQueue(raw)
    }

    private fun saveEndpointHealthEventQueueLocked(events: List<EndpointHealthEvent>) {
        preferences.edit {
            putString(KEY_ENDPOINT_HEALTH_EVENTS_ENCRYPTED, cipher.encrypt(EndpointHealthEvents.encodeQueue(events)))
        }
    }

    private fun pruneDailyStats(stats: List<DailyStats>): List<DailyStats> =
        DailyStatsStore.prune(stats)

    private fun pruneAppLogs(logs: List<AppLogEntry>): List<AppLogEntry> =
        AppLogStore.prune(logs)

    private fun appVersionLabel(): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName ?: "unknown"} (${appVersionCode()})"
        }.getOrDefault("unknown")
    }

    private fun appVersionName(): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun appVersionCode(): Long {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
    }

    private fun deviceModelLabel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "unknown" }
    }

    private companion object {
        val SETTINGS_LOCK = Any()
        val STATS_LOCK = Any()
        val LOGS_LOCK = Any()
        val ENDPOINT_HEALTH_LOCK = Any()
        val ENDPOINT_ROTATION_LOCK = Any()
        val TEMPORARY_VPN_LEASE_LOCK = Any()
        val RU_WIREGUARD_REVOKE_LOCK = Any()
        val PENDING_LOGOUT_REVOCATION_LOCK = Any()
        val APP_NOTIFICATION_HISTORY_LOCK = Any()
        const val KEY_SETTINGS = "settings_json"
        const val KEY_DAILY_STATS_ENCRYPTED = "daily_stats_json_encrypted_v1"
        const val KEY_DAILY_STATS = "daily_stats_json"
        const val KEY_APP_LOGS_ENCRYPTED = "app_logs_json_encrypted_v1"
        const val KEY_APP_LOGS = "app_logs_json"
        const val KEY_VPN_INCIDENTS_ENCRYPTED = "vpn_incidents_json_encrypted_v1"
        const val KEY_ENDPOINT_HEALTH_ENCRYPTED = "endpoint_health_json_encrypted_v1"
        const val KEY_ENDPOINT_HEALTH_EVENTS_ENCRYPTED = "endpoint_health_events_json_encrypted_v1"
        const val KEY_ENDPOINT_HEALTH_LAST_HEARTBEAT_AT = "endpoint_health_last_heartbeat_at"
        const val KEY_ENDPOINT_ROTATION_PREFIX = "endpoint_rotation_"
        const val KEY_TEMPORARY_VPN_LEASE_ENCRYPTED = "temporary_vpn_lease_encrypted_v1"
        const val KEY_TEMPORARY_VPN_PENDING_REVOKE_ENCRYPTED =
            "temporary_vpn_pending_revoke_encrypted_v1"
        const val KEY_RU_WIREGUARD_PENDING_REVOKE = "ru_wireguard_pending_revoke_encrypted_v1"
        const val KEY_PENDING_LOGOUT_REVOCATIONS_ENCRYPTED =
            "pending_logout_revocations_encrypted_v1"
        const val KEY_APP_NOTIFICATION_HISTORY_ENCRYPTED_PREFIX =
            "app_notification_history_encrypted_v2_"
    }
}
