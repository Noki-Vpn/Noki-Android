package com.noki.vpn.vpn

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noki.vpn.data.AndroidDeviceInfo
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendRuWireGuardSession
import com.noki.vpn.data.RuEntryCredential
import com.noki.vpn.data.RuWireGuardSessionJsonParser
import com.noki.vpn.data.DeviceIdentity
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.PendingRuWireGuardRevoke
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.WireGuardKeyStore
import com.noki.vpn.data.ClientLatencySampler
import com.noki.vpn.data.EndpointRankingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal data class PreparedRuWireGuardConnection(
    val credential: RuEntryCredential,
    val privateKey: ByteArray,
    val exitNode: RuForeignNode,
    val enrolled: Boolean = false,
)

/** Enroll the device at the selected RU entry. The running tunnel selects the exit. */
internal class RuWireGuardConnectionCoordinator(
    private val context: Context,
    private val repository: SettingsRepository,
    private val api: BackendApiClient,
    private val sampler: ClientLatencySampler = ClientLatencySampler.startup(context),
    private val challengeSigner: (String) -> String = DeviceIdentity::signChallenge,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    suspend fun prepare(settings: StoredSettings, excludedPairs: Set<Pair<String, String>> = emptySet()): PreparedRuWireGuardConnection = withTimeoutOrNull(30_000L) {
        require(settings.advancedSettings.ruRelayEnabled) {
            "ru_wireguard_selection_invalid"
        }
        val token = settings.backendAccessToken?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("auth_required")
        val deviceKey = repository.ensureBackendDeviceKey(settings.backendDeviceKey)
        val device = if (settings.backendDeviceId.isNotBlank()) {
            settings.backendDeviceId
        } else {
            val registered = api.registerDevice(
                token = token,
                deviceKey = deviceKey,
                deviceId = null,
                deviceName = AndroidDeviceInfo.deviceName(),
                publicKey = DeviceIdentity.publicKeyBase64(),
                deviceClaims = DeviceIdentity.deviceClaims(context),
                platform = "android",
            )
            val saved = repository.updateAndReturn { current ->
                if (current.backendAccessToken != token || !current.advancedSettings.ruRelayEnabled) {
                    current to false
                } else {
                    current.copy(backendDeviceId = registered.id, backendDeviceKey = registered.deviceKey) to true
                }
            }
            check(saved) { "selection_changed" }
            registered.id
        }
        val pair = WireGuardKeyStore.android(context).getOrCreate()
        try {
            val publicKey = pair.publicKeyBase64()
            val userId = settings.userProfile.backendUserId
            val cached = repository.readRuEntry(userId, device, publicKey)
            val cachedCredential = cached?.let { RuWireGuardSessionJsonParser.parseEntryCredential(it) }
                ?.takeIf { it.expiresAtEpochMillis > System.currentTimeMillis() }
            val savedExits = cached?.getJSONArray("exits")?.let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    RuForeignNode(item.getString("id"), item.getString("name"), item.getString("country_code"))
                }
            }.orEmpty()
            // A lease is a credential, not a permanent AUTO choice. Refresh eligibility first.
            val inventory = try { api.ruRelayCatalog(token, device, deviceKey) } catch (error: Exception) {
                val transient = error is java.io.IOException || error is BackendException && error.statusCode in setOf(502, 503, 504)
                val fallback = eligibleExits(settings, savedExits).filter { cachedCredential?.relayNodeId to it.id !in excludedPairs }
                if (!transient || cachedCredential == null || fallback.isEmpty()) throw error
                // Offline fallback is bounded by the existing credential's expiry and the gateway ACK.
                val exit = EndpointRankingPolicy.rankNodes(fallback, { it.id }, { null }, { 1 }, { null }, random = random).first()
                return@withTimeoutOrNull PreparedRuWireGuardConnection(cachedCredential, pair.privateKey, exit)
            }
            val eligible = eligibleExits(settings, inventory.foreignNodes)
            val exitSamples = if (eligible.size > 1) sampler.measureNodes(eligible,
                { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp }) else emptyMap()
            val exits = EndpointRankingPolicy.rankNodes(eligible, { it.id }, { exitSamples[it.id] },
                { it.selectionWeight }, { it.metricsAt }, random = random)
            var lastFailure: Exception = BackendException("ru_relay_pair_unavailable", 503)
            var keyId: String? = null
            for (exit in exits) {
                val catalog = try { api.ruRelayCatalog(token, device, deviceKey, exit.id) } catch (error: BackendException) {
                    when (error.statusCode) {
                        404 -> inventory // Older entry-route servers expose only the global inventory.
                        503 -> { lastFailure = error; continue }
                        else -> throw error
                    }
                }
                val eligibleRelays = catalog.relays.filter { it.available && it.pairReady && it.id to exit.id !in excludedPairs }
                val relaySamples = if (eligibleRelays.size > 1) sampler.measureNodes(eligibleRelays,
                    { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp }) else emptyMap()
                val relays = EndpointRankingPolicy.rankNodes(eligibleRelays, { it.id }, { relaySamples[it.id] },
                    { it.selectionWeight }, { it.metricsAt }, random = random)
                for (relay in relays) {
                    try {
                        val credential = if (cachedCredential?.relayNodeId == relay.id) cachedCredential else {
                            if (keyId == null) keyId = api.registerRuWireGuardKey(token, device, deviceKey, publicKey).id
                            DeviceIdentity.withChallengeTransaction {
                                val proof = api.createDeviceChallenge(token, device)
                                api.enrollRuEntry(token, device, deviceKey, proof.nonce, challengeSigner(proof.nonce), checkNotNull(keyId), relay.id)
                            }
                        }
                        check(credential.expiresAtEpochMillis > System.currentTimeMillis()) { "ru_entry_expired" }
                        repository.saveRuEntry(userId, device, publicKey, credential, inventory.foreignNodes)
                        return@withTimeoutOrNull PreparedRuWireGuardConnection(credential, pair.privateKey, exit, enrolled = credential !== cachedCredential)
                    } catch (error: BackendException) {
                        if (error.statusCode != 503) throw error
                        lastFailure = error
                    }
                }
            }
            throw lastFailure
        } catch (error: Throwable) {
            pair.privateKey.fill(0)
            throw error
        }
    } ?: throw IllegalStateException("ru_wireguard_session_timeout")

    private fun eligibleExits(settings: StoredSettings, exits: List<RuForeignNode>): List<RuForeignNode> = exits.filter { exit ->
        val profile = settings.userProfile
        when (profile.serverSelectionMode) {
            ServerSelectionMode.AUTO -> true
            ServerSelectionMode.COUNTRY -> exit.countryCode.equals(profile.selectedCountryCode, true)
            ServerSelectionMode.SERVER -> exit.id == profile.selectedNodeId
        }
    }

}

internal suspend fun awaitRuWireGuardReady(
    initial: BackendRuWireGuardSession,
    poll: suspend () -> BackendRuWireGuardSession,
    pause: suspend () -> Unit = { delay(2_000) },
): BackendRuWireGuardSession.Ready {
    var response = initial
    repeat(15) {
        if (response is BackendRuWireGuardSession.Ready) return response
        pause()
        response = poll()
        check(response.identity.hasSameAuthorization(initial.identity)) { "ru_wireguard_session_changed" }
    }
    return response as? BackendRuWireGuardSession.Ready
        ?: throw IllegalStateException("ru_wireguard_session_timeout")
}

class PendingRuWireGuardRevokeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val repository = SettingsRepository(applicationContext)
        val pending = repository.loadPendingRuWireGuardRevokes().filterNot { it.provisional }
        if (pending.isEmpty()) return Result.success()
        val physical = AndroidUnderlyingNetworkSource(applicationContext).currentObservation().candidate?.network
            ?: return Result.retry()
        val api = BackendApiClient().onNetwork(physical)
        var failed = false
        for (entry in pending) {
            if (revokePendingRuWireGuardSession(api, entry)) repository.clearPendingRuWireGuardRevoke(entry.sessionId)
            else failed = true
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingRuWireGuardRevokeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "pending_ru_wireguard_revoke", ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}

internal suspend fun revokePendingRuWireGuardSession(
    api: BackendApiClient,
    pending: PendingRuWireGuardRevoke,
): Boolean = try {
    api.revokeRuWireGuardSession(pending.sessionId, pending.revokeToken)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: BackendException) {
    error.statusCode == 404
} catch (_: Exception) {
    false
}
