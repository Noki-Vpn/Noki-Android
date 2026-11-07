package com.noki.vpn.vpn

import android.os.Looper
import android.util.AtomicFile
import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendRuWireGuardConfig
import com.noki.vpn.data.BackendRuWireGuardIdentity
import com.noki.vpn.data.BackendRuWireGuardSession
import com.noki.vpn.data.RuRelaySelection
import com.noki.vpn.data.EndpointSelector
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.TemporaryVpnSessionCoordinator
import com.noki.vpn.data.VpnStartCoordinator
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.hysteriaCandidate
import com.noki.vpn.data.tcpCandidate
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AppVpnServiceCommandTest {
    @Test
    fun requiredClientUpdateFailsBeforeCreatingARuntimeAndBroadcastsUpdateReason() = runBlocking {
        val fixture = Fixture(this)
        fixture.orchestrator.withLifecycleLock {
            fixture.orchestrator.releaseResourcesWhileOwned(VpnConnectionState.DISCONNECTED)
        }
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.useOfflinePreparation(BackendException("client_update_required", 426))

        fixture.startAccount()
        fixture.orchestrator.activeTransitionJob()!!.join()

        val failure = shadowOf(fixture.service).broadcastIntents.last {
            it.action == AppVpnService.ACTION_STATE_CHANGED &&
                it.getStringExtra(AppVpnService.EXTRA_STATE) == VpnConnectionState.FAILED.name
        }
        assertEquals("client_update_required", failure.getStringExtra(AppVpnService.EXTRA_REASON))
        assertEquals(emptyList<String>(), fixture.startedEndpoints)
        assertEquals(0, fixture.tunnelsCreated)
        assertEquals(0, fixture.recoveryAttempt())
    }

    @Test
    fun queryStateOnColdServiceDoesNotInitializeTheRoutingAsset() {
        val controller = Robolectric.buildService(AppVpnService::class.java)
        val service = controller.get()
        AtomicFile(GeositeAsset.file(service)).delete()
        try {
            controller.create()
            service.onStartCommand(AppVpnService.queryStateIntent(service), 0, 1)

            assertFalse(GeositeAsset.file(service).exists())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun coldAccountStartInstallsTheRoutingAssetDuringSessionPreparation() = runBlocking {
        val fixture = Fixture(this)
        fixture.orchestrator.withLifecycleLock {
            fixture.orchestrator.releaseResourcesWhileOwned(VpnConnectionState.DISCONNECTED)
        }
        AtomicFile(GeositeAsset.file(fixture.service)).delete()
        fixture.useOfflinePreparation()

        fixture.startAccount()
        fixture.orchestrator.activeTransitionJob()!!.join()

        val bundled = fixture.service.assets.open("geosite.dat").use { it.readBytes() }
        assertEquals(GeositeAsset.sha256(bundled), GeositeAsset.currentDigest(fixture.service))
        assertEquals(emptyList<String>(), fixture.startedEndpoints)
        assertEquals(0, fixture.tunnelsCreated)
    }

    @Test
    fun cancelledColdBootstrapCannotFailOrCloseANewerConnection() = runBlocking {
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "geosite-cancel-test")
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val fixture = Fixture(scope)
            val preparationStarted = java.util.concurrent.CountDownLatch(1)
            val releasePreparation = java.util.concurrent.CountDownLatch(1)
            fixture.useOfflinePreparation {
                preparationStarted.countDown()
                check(releasePreparation.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            fixture.orchestrator.withLifecycleLock {
                fixture.orchestrator.releaseResourcesWhileOwned(VpnConnectionState.DISCONNECTED)
            }
            AtomicFile(GeositeAsset.file(fixture.service)).delete()
            var newerTunnelClosed = false
            val newerTunnel = object : TunHandle {
                override val fd = 9
                override fun close() { newerTunnelClosed = true }
            }
            val cancelledStart: Job
            val newerGeneration: Long
            synchronized(GeositeAsset) {
                fixture.startAccount()
                cancelledStart = fixture.orchestrator.activeTransitionJob()!!
                assertTrue(preparationStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                cancelledStart.cancel()
                newerGeneration = fixture.orchestrator.invalidate()
                fixture.orchestrator.replaceTunnel(newerTunnel)
                fixture.orchestrator.updateState(VpnConnectionState.CONNECTED)
                releasePreparation.countDown()
            }
            cancelledStart.join()

            assertEquals(newerGeneration, fixture.orchestrator.currentGenerationId())
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
            assertEquals(newerTunnel, fixture.orchestrator.currentTunnel())
            assertFalse(newerTunnelClosed)
            assertFalse(shadowOf(fixture.service).isStoppedBySelf)
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun temporaryRestartAssetFailureUsesOwnedFailureCleanup() = runBlocking {
        val uncaught = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            uncaught.add(error)
        })
        val fixture = Fixture(scope)
        fixture.useTemporaryPreparation()
        fixture.set("destroyCleanupScope", scope)
        val directory = fixture.service.noBackupFilesDir
        val backup = java.io.File(directory.parentFile, directory.name + "-geosite-test")
        check(directory.renameTo(backup))
        try {
            directory.writeText("blocked test asset directory")
            fixture.invoke("restartTemporaryVpn")
            fixture.orchestrator.activeTransitionJob()!!.join()
            scope.coroutineContext[Job]!!.children.forEach { it.join() }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertNull(fixture.orchestrator.currentTunnel())
            assertEquals(emptyList<String>(), fixture.startedEndpoints)
            org.junit.Assert.assertTrue(shadowOf(fixture.service).isStoppedBySelf)
        } finally {
            scope.cancel()
            check(directory.delete())
            check(backup.renameTo(directory))
        }
    }

    @Test
    fun ruNotificationKeepsConfirmedExitAcrossSharedSettingsActivation() = runBlocking {
        val fixture = Fixture(this)
        fixture.useRuSession(ruSession(System.currentTimeMillis() + 3_600_000L)) { 503 to "{}" }
        val settings = fixture.savedSettings().let { it.copy(profile = it.profile.copy(remark = "Noki Латвия6")) }
        fixture.set("notificationServerLabel", "Латвия7")
        fixture.finishConnectedSettings(settings)
        val notification = AppVpnService::class.java.getDeclaredMethod("createActiveNotification")
            .apply { isAccessible = true }.invoke(fixture.service) as android.app.Notification
        org.junit.Assert.assertTrue(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            .toString().startsWith("Латвия7   "))
        assertEquals("Noki Латвия6", fixture.orchestrator.currentSettings()?.profile?.remark)
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()?.join()
        assertNull(fixture.ruSession())
    }

    @Test
    fun ruNetworkLossAndReturnRebindTheSameRuntimeAndTunnel() = runBlocking {
        val fixture = Fixture(this)
        val session = ruSession(System.currentTimeMillis() + 3_600_000L)
        fixture.useRuSession(session) { error("Network handover must not prepare or renew a session") }
        val tunnel = fixture.orchestrator.currentTunnel()
        val generation = fixture.orchestrator.currentGenerationId()
        fixture.orchestrator.updateUnderlay(UnderlyingNetworkSnapshot(
            EndpointRankingPolicy.NetworkKind.CELLULAR, "old-cellular", true, "old"))
        try {
            fixture.observeNetwork(UnderlyingNetworkAvailability.None)
            yield()
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertNull(fixture.orchestrator.currentUnderlay())
            assertEquals(0, fixture.runtimeStops)
            assertEquals(0, fixture.runtimeRebinds)
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            yield()
            assertEquals(1, fixture.runtimeRebinds)
            fixture.orchestrator.withLifecycleLock {}
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(generation, fixture.orchestrator.currentGenerationId())
            assertEquals(session, fixture.ruSession())
            assertEquals(0, fixture.runtimeStops)
            assertEquals(0, fixture.tunnelsCreated)
            assertEquals(emptyList<String>(), fixture.startedEndpoints)
            org.junit.Assert.assertNotNull(fixture.orchestrator.currentUnderlay())
        } finally { fixture.invoke("cancelDelayedVpnCallbacks") }
    }

    @Test
    fun ruFailedSocketRebindRetriesWithoutRestartingTheCore() = runBlocking {
        val fixture = Fixture(this)
        fixture.useRuSession(ruSession(System.currentTimeMillis() + 3_600_000L)) {
            error("Socket recovery must not prepare or renew a session")
        }
        val tunnel = fixture.orchestrator.currentTunnel()
        fixture.runtimeRebindSucceeds = false
        try {
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            yield()
            assertEquals(1, fixture.runtimeRebinds)
            assertNull(fixture.orchestrator.currentUnderlay())
            assertEquals(0, fixture.runtimeStops)
            fixture.runtimeRebindSucceeds = true
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            yield()
            assertEquals(2, fixture.runtimeRebinds)
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(0, fixture.runtimeStops)
            assertEquals(0, fixture.tunnelsCreated)
            assertEquals(emptyList<String>(), fixture.startedEndpoints)
        } finally { fixture.invoke("cancelDelayedVpnCallbacks") }
    }

    @Test
    fun ruSocketRebindDoesNotRepeatWhenHttpReadinessFails() = runBlocking {
        val fixture = Fixture(this)
        val session = ruSession()
        fixture.useRuSession(session) { 503 to "{}" }
        fixture.workingEndpoint = null
        var probes = 0
        fixture.onProbe = { probes++ }
        val tunnel = fixture.orchestrator.currentTunnel()
        val generation = fixture.orchestrator.currentGenerationId()
        try {
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            yield()
            assertEquals(1, fixture.runtimeRebinds)
            fixture.orchestrator.withLifecycleLock {}
            assertEquals(0, probes)
            org.junit.Assert.assertNotNull(fixture.orchestrator.currentUnderlay())
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(generation, fixture.orchestrator.currentGenerationId())
            assertEquals(session, fixture.ruSession())
            assertEquals(true, fixture.runtimeRunning)
            assertEquals(0, fixture.runtimeStops)
            assertEquals(emptyList<String>(), fixture.startedEndpoints)

            fixture.workingEndpoint = "ru"
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            yield()
            assertEquals(1, fixture.runtimeRebinds)
            fixture.orchestrator.withLifecycleLock {}
            org.junit.Assert.assertNotNull(fixture.orchestrator.currentUnderlay())
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(generation, fixture.orchestrator.currentGenerationId())
            assertEquals(session, fixture.ruSession())
            assertEquals(true, fixture.runtimeRunning)
            assertEquals(0, fixture.runtimeStops)
            assertEquals(0, fixture.tunnelsCreated)
            assertEquals(emptyList<String>(), fixture.startedEndpoints)
        } finally { fixture.invoke("cancelDelayedVpnCallbacks") }
    }

    @Test
    fun restartFailureReportsFailedStateWithoutEscapingTheServiceScope() = runBlocking {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.Dispatchers.Unconfined +
            kotlinx.coroutines.CoroutineExceptionHandler { _, error -> escaped += error })
        val fixture = Fixture(scope)
        var closes = 0
        fixture.orchestrator.replaceTunnel(object : TunHandle {
            override val fd = 7
            override fun close() {
                if (closes++ == 0) throw java.io.IOException("TUN close failed")
            }
        })
        try {
            fixture.invoke("restartVpn", VpnPreparationStrategy.FreshOnly)
            fixture.orchestrator.activeTransitionJob()?.join()
            assertEquals(emptyList<Throwable>(), escaped)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertNull(fixture.orchestrator.currentTunnel())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun persistentRuStatusPreservesWorkingTunnelAndNeverRenews() = runBlocking {
        val fixture = Fixture(this)
        val session = ruSession()
        val tunnel = fixture.orchestrator.currentTunnel()
        val requests = mutableListOf<okhttp3.Request>()
        fixture.useRuSession(session) { request ->
            requests += request
            200 to ruSessionJson(session)
        }
        fixture.checkRuStatus()
        assertEquals(listOf("GET"), requests.map { it.method })
        assertEquals("/v1/vpn/sessions/${session.identity.sessionId}", requests.single().url.encodedPath)
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals(0, fixture.runtimeStops)
    }

    @Test
    fun persistentRuRevocationFailsClosedButTransientStatusDoesNot() = runBlocking {
        for (status in listOf(503, 409)) {
            val fixture = Fixture(this)
            fixture.useRuSession(ruSession()) { status to "{\"detail\":\"test\"}" }
            fixture.checkRuStatus()
            if (status == 503) {
                assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
                assertEquals(0, fixture.runtimeStops)
            } else {
                assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
                assertNull(fixture.ruSession())
                assertEquals(1, fixture.runtimeStops)
            }
        }
    }

    @Test
    fun persistentRuUpdateRequirementRevokesAdmissionAndBroadcastsUpdateReason() = runBlocking {
        val fixture = Fixture(this)
        val session = ruSession()
        fixture.useRuSession(session) { 426 to "{\"detail\":\"client_update_required\"}" }

        fixture.checkRuStatus()

        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertNull(fixture.ruSession())
        assertEquals(1, fixture.runtimeStops)
        assertNull(fixture.orchestrator.currentTunnel())
        val failure = shadowOf(fixture.service).broadcastIntents.last {
            it.action == AppVpnService.ACTION_STATE_CHANGED &&
                it.getStringExtra(AppVpnService.EXTRA_STATE) == VpnConnectionState.FAILED.name
        }
        assertEquals("client_update_required", failure.getStringExtra(AppVpnService.EXTRA_REASON))
    }

    @Test
    fun persistentRuReadyCannotRegressToPending() = runBlocking {
        val fixture = Fixture(this)
        val ready = ruSession()
        val pending = ready.copy(identity = ready.identity.copy(
            leaseExpiresAtEpochMillis = System.currentTimeMillis() + 60_000L))
        fixture.useRuSession(ready) { 202 to ruSessionJson(pending, ready = false) }
        fixture.checkRuStatus()
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertNull(fixture.ruSession())
    }

    @Test
    fun persistentRuStopStagesExactSessionRevokeBeforeNextStart() = runBlocking {
        val fixture = Fixture(this)
        val first = ruSession()
        fixture.useRuSession(first) { 200 to ruSessionJson(first) }
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()?.join()
        val pending = SettingsRepository(fixture.service).loadPendingRuWireGuardRevokes().singleOrNull()
        assertEquals(first.identity.sessionId, pending?.sessionId)
        assertNull(fixture.ruSession())
        assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
    }

    @Test
    fun persistentRuRevokeQueueRetainsEverySessionAcrossAccountChanges() {
        val repository = SettingsRepository(Fixture(CoroutineScope(kotlinx.coroutines.SupervisorJob())).service)
        val first = com.noki.vpn.data.PendingRuWireGuardRevoke(
            "11111111-1111-4111-8111-111111111111", "revoke-token-1")
        val second = first.copy(sessionId = "22222222-2222-4222-8222-222222222222",
            revokeToken = "revoke-token-2")
        repository.stageRuWireGuardRevoke(first)
        repository.stageRuWireGuardRevoke(second)
        assertEquals(listOf(first, second), repository.loadPendingRuWireGuardRevokes())
        repository.clearPendingRuWireGuardRevoke(second.sessionId)
        assertEquals(listOf(first), repository.loadPendingRuWireGuardRevokes())
    }

    @Test
    fun persistentRuActivationMarkerSurvivesCrashButDoesNotRevokeLivePreparation() {
        val repository = SettingsRepository(Fixture(CoroutineScope(kotlinx.coroutines.SupervisorJob())).service)
        val session = com.noki.vpn.data.PendingRuWireGuardRevoke(
            "11111111-1111-4111-8111-111111111111", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            provisional = true)
        repository.stageRuWireGuardRevoke(session)
        assertFalse(repository.recoverProvisionalRuWireGuardRevokes(null, preparationActive = true))
        assertEquals(session, repository.loadPendingRuWireGuardRevokes().single())
        assertFalse(repository.recoverProvisionalRuWireGuardRevokes(session.sessionId, preparationActive = false))
        assertEquals(session, repository.loadPendingRuWireGuardRevokes().single())
        assertEquals(true, repository.recoverProvisionalRuWireGuardRevokes(null, preparationActive = false))
        assertEquals(session.copy(provisional = false), repository.loadPendingRuWireGuardRevokes().single())
        repository.stageRuWireGuardRevoke(session)
        repository.clearProvisionalRuWireGuardRevoke(session.sessionId, session.revokeToken)
        assertEquals(session.copy(provisional = false), repository.loadPendingRuWireGuardRevokes().single())
        repository.clearPendingRuWireGuardRevoke(session.sessionId)
        repository.stageRuWireGuardRevoke(session)
        repository.clearProvisionalRuWireGuardRevoke(session.sessionId, session.revokeToken)
        assertEquals(emptyList<com.noki.vpn.data.PendingRuWireGuardRevoke>(),
            repository.loadPendingRuWireGuardRevokes())
    }

    @Test
    fun persistentRuReadyOnLastPollIsAccepted() = runBlocking {
        val ready = ruSession()
        val pending = BackendRuWireGuardSession.Pending(ready.identity.copy(
            leaseExpiresAtEpochMillis = System.currentTimeMillis() + 60_000L))
        var polls = 0
        assertEquals(ready, awaitRuWireGuardReady(pending,
            poll = { if (++polls == 15) ready else pending }, pause = {}))
        assertEquals(15, polls)
    }

    @Test
    fun persistentRuRevokeOnlyTreatsConfirmedOrCleanedSessionAsTerminal() = runBlocking {
        val entry = com.noki.vpn.data.PendingRuWireGuardRevoke(
            "11111111-1111-4111-8111-111111111111", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        for ((code, terminal) in listOf(200 to true, 403 to false, 404 to true, 503 to false)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            assertEquals("HTTP $code", terminal,
                revokePendingRuWireGuardSession(BackendApiClient(client, "https://api.example"), entry))
        }
    }

    private fun ruSession(expiry: Long? = null) = BackendRuWireGuardSession.Ready(
        BackendRuWireGuardIdentity("11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222", 1,
            "33333333-3333-4333-8333-333333333333", RuRelaySelection.Auto,
            "44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555",
            1, "66666666-6666-4666-8666-666666666666", 1, null, expiry,
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
        BackendRuWireGuardConfig("192.0.2.1:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            "10.87.0.2/32", listOf("1.1.1.1"), 1280, listOf("0.0.0.0/0")),
    )

    private fun ruSessionJson(session: BackendRuWireGuardSession.Ready, ready: Boolean = true): String {
        val id = session.identity
        val config = session.wireguard
        return JSONObject().put("engine", "wireguard").put("contract_version", 2).put("route_mode", "ru_wg_nebula")
            .put("status", if (ready) "ready" else "pending").put("request_id", id.requestId)
            .put("session_id", id.sessionId).put("generation", id.generation).put("public_key_id", id.publicKeyId)
            .put("relay_selection", JSONObject().put("kind", "auto")).put("relay_node_id", id.relayNodeId)
            .put("exit_node_id", id.exitNodeId)
            .put("route_version", id.routeVersion).put("route_id", id.routeId)
            .put("route_generation", id.routeGeneration).put("entry_node_id", id.entryNodeId ?: JSONObject.NULL)
            .put("revoke_token", id.revokeToken)
            .put("lease_expires_at", if (ready) JSONObject.NULL else
                java.time.Instant.ofEpochMilli(requireNotNull(id.leaseExpiresAtEpochMillis)).toString())
            .apply { if (ready) {
                put("wireguard", JSONObject().put("endpoint", config.endpoint)
                    .put("server_public_key", config.serverPublicKey).put("address", config.address)
                    .put("dns", org.json.JSONArray(config.dns)).put("mtu", config.mtu)
                    .put("allowed_ips", org.json.JSONArray(config.allowedIps)))
            } }.toString()
    }

    @Test
    fun candidateWarmupRespectsProtocolAndManualSelection() = runBlocking {
        val context = Fixture(this).service
        val repository = SettingsRepository(context)
        val candidate = hysteriaCandidate("tls").copy(path = null)
        val session = BackendVpnSession(
            canConnect = true, profileCode = "auto", locationCode = "lv1", locationName = "Latvia",
            endpointCode = candidate.code, entryHost = candidate.entryHost, entryPort = candidate.entryPort,
            serverName = candidate.serverName, proxyType = candidate.proxyType, transport = candidate.transport,
            transportMode = candidate.transportMode, security = candidate.security, fingerprint = null,
            requestHost = null, path = null, alpn = null, allowInsecure = false, enableMux = false,
            randomUserAgent = false, publicKey = null, shortId = null, vpnUsername = "test",
            vpnSecret = "test-secret", flow = null, planCode = null,
            endpointCandidates = listOf(candidate, tcpCandidate("reality").copy(
                transport = "xhttp", transportMode = "stream-up", path = "/noki", flow = null,
            )),
        )
        for (mode in EndpointSelectionMode.entries) {
            for (protocol in com.noki.vpn.data.VpnProtocol.entries) {
                val settings = repository.updateSettings {
                    it.copy(isAuthenticated = true, backendAccessToken = "probe-token",
                        advancedSettings = it.advancedSettings.copy(
                            endpointSelectionMode = mode, protocol = protocol))
                }
                val calls = java.util.concurrent.atomic.AtomicInteger()
                val runner = EndpointProbeRunner(
                    context = context, repository = repository,
                    measureTcpDelay = { _, _, _ -> 10 },
                    measureNativeDelay = { _, _ -> calls.incrementAndGet(); 20L },
                )
                val result = runCatching {
                    runner.probeAutoCandidates(session, settings, isCurrent = { true },
                        networkKind = EndpointRankingPolicy.NetworkKind.WIFI, staggerMillis = 0L)
                }
                if (mode == EndpointSelectionMode.AUTO && protocol == com.noki.vpn.data.VpnProtocol.WIREGUARD) {
                    assertEquals(IllegalArgumentException::class.java, result.exceptionOrNull()?.javaClass)
                    assertEquals("wireguard_requires_ru_relay", result.exceptionOrNull()?.message)
                    assertEquals(0, calls.get())
                    continue
                }
                val outcomes = result.getOrThrow()
                val expected = if (mode == EndpointSelectionMode.MANUAL) emptySet() else when (protocol) {
                    com.noki.vpn.data.VpnProtocol.AUTO -> setOf("tls", "reality")
                    com.noki.vpn.data.VpnProtocol.TLS -> setOf("tls")
                    com.noki.vpn.data.VpnProtocol.REALITY -> setOf("reality")
                    com.noki.vpn.data.VpnProtocol.NOKINV -> emptySet()
                    com.noki.vpn.data.VpnProtocol.NAIVE -> emptySet()
                    com.noki.vpn.data.VpnProtocol.WIREGUARD -> emptySet()
                }
                assertEquals("$mode/$protocol", expected, outcomes.map { it.endpointCode }.toSet())
                assertEquals(expected.size, calls.get())
            }
        }
    }

    @Test
    fun candidateDeadlineRetainsCompletedAlternativeAndCancelsStalledProbe() = runBlocking {
        val started = mutableListOf<String>()
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var stalledCancelled = false
        val began = System.nanoTime()
        val result = EndpointProbeRunner.collectCandidateProbes(
            listOf(hysteriaCandidate("stalled"), hysteriaCandidate("healthy"), hysteriaCandidate("excluded")),
            deadlineMillis = 500L,
            staggerMillis = 50L,
        ) { candidate ->
            started += candidate.code
            if (candidate.code == "stalled") {
                firstStarted.complete(Unit)
                try { awaitCancellation() } finally { stalledCancelled = true }
            } else {
                assertEquals(true, firstStarted.isCompleted)
                org.junit.Assert.assertTrue((System.nanoTime() - began) / 1_000_000 >= 40L)
                EndpointProbeOutcome(candidate.code, false, null, 20L, null, 1, 1, true, false)
            }
        }
        assertEquals(listOf("stalled", "healthy"), started)
        assertEquals(listOf("healthy"), result.map { it.endpointCode })
        assertEquals(true, stalledCancelled)
        try {
            EndpointProbeRunner.collectCandidateProbes(listOf(hysteriaCandidate("cancelled")), 500L, 0L) {
                throw kotlinx.coroutines.CancellationException("session changed")
            }
            org.junit.Assert.fail("Context cancellation must not become a completed or failed candidate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test
    fun nativeProbeCancellationKeepsWorkerSlotsUntilCallsActuallyReturn() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.CountDownLatch(2)
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val queuedStarted = java.util.concurrent.CountDownLatch(1)
        var delivered = 0
        val workers = (1..2).map {
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                EndpointProbeRunner.awaitNativeProbe(
                    measure = {
                        started.countDown()
                        try {
                            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            20L
                        } finally {
                            exited.countDown()
                        }
                    },
                    cancel = { cancelled.incrementAndGet() },
                )
                delivered++
            }
        }
        try {
            org.junit.Assert.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            workers.forEach { it.cancel() }
            assertEquals(2, cancelled.get())
            val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                EndpointProbeRunner.awaitNativeProbe(
                    measure = { queuedStarted.countDown(); 30L },
                    cancel = { cancelled.incrementAndGet() },
                )
                delivered++
            }
            assertFalse(queuedStarted.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
            queued.cancel()
            assertEquals(3, cancelled.get())
            release.countDown()
            org.junit.Assert.assertTrue(exited.await(5, java.util.concurrent.TimeUnit.SECONDS))
            kotlinx.coroutines.withTimeout(5_000) {
                workers.forEach { it.join() }
                queued.join()
                assertEquals(40L, EndpointProbeRunner.awaitNativeProbe({ 40L }, {}))
            }
            assertEquals(1L, queuedStarted.count)
            assertEquals(0, delivered)
        } finally {
            release.countDown()
            workers.forEach { it.cancel() }
        }
    }

    @Test
    fun candidateProbeRejectsNativeResultsAfterContextInvalidation() = runBlocking {
        val context = Fixture(this).service
        val repository = SettingsRepository(context)
        val candidate = hysteriaCandidate("probe").copy(path = null)
        val session = BackendVpnSession(
            canConnect = true, profileCode = "auto", locationCode = "lv1", locationName = "Latvia",
            endpointCode = candidate.code, entryHost = candidate.entryHost, entryPort = candidate.entryPort,
            serverName = candidate.serverName, proxyType = candidate.proxyType, transport = candidate.transport,
            transportMode = candidate.transportMode, security = candidate.security, fingerprint = null,
            requestHost = null, path = null, alpn = null, allowInsecure = false, enableMux = false,
            randomUserAgent = false, publicKey = null, shortId = null, vpnUsername = "test",
            vpnSecret = "test-secret", flow = null, planCode = null, endpointCandidates = listOf(candidate),
        )
        for (change in listOf("cancel", "generation", "network", "logout", "native-cancel", "api-down", "valid")) {
            val settings = repository.updateSettings {
                it.copy(isAuthenticated = true, backendAccessToken = "probe-token-$change",
                    advancedSettings = it.advancedSettings.copy(endpointSelectionMode = EndpointSelectionMode.AUTO,
                        anonymousLogsEnabled = true, connectionLogsEnabled = true))
            }
            var generation = 1L
            var network = "network-a"
            var uploads = 0
            val before = repository.loadEndpointHealth(EndpointRankingPolicy.NetworkKind.WIFI)
            lateinit var job: kotlinx.coroutines.Job
            val runner = EndpointProbeRunner(
                context = context,
                repository = repository,
                endpointHealthReporter = com.noki.vpn.data.EndpointHealthEventReporter(
                    store = repository, upload = { _, _ -> uploads++ }, loadSettings = repository::load),
                measureTcpDelay = { host, _, _ ->
                    if (change == "api-down" && host == com.noki.vpn.data.NokiBackendConfig.backendProbeHost) null else 10
                },
                measureNativeDelay = { _, _ ->
                    when (change) {
                        "cancel" -> job.cancel()
                        "generation" -> generation++
                        "network" -> network = "network-b"
                        "logout" -> repository.clearAuthAndStageRefreshToken()
                        "native-cancel" -> throw kotlinx.coroutines.CancellationException("native cancelled")
                    }
                    20L
                },
            )
            job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                runner.probeAutoCandidates(session, settings,
                    isCurrent = { generation == 1L && network == "network-a" },
                    networkKind = EndpointRankingPolicy.NetworkKind.WIFI)
            }
            job.start()
            kotlinx.coroutines.withTimeout(5_000) { job.join() }
            val healthy = change == "valid" || change == "api-down"
            assertEquals(change, !healthy, job.isCancelled)
            if (healthy) {
                assertEquals(1, uploads)
                assertEquals((before["probe"]?.successCount ?: 0) + 1, repository.loadEndpointHealth(EndpointRankingPolicy.NetworkKind.WIFI)["probe"]?.successCount)
            } else {
                assertEquals(change, before, repository.loadEndpointHealth(EndpointRankingPolicy.NetworkKind.WIFI))
                assertEquals(change, 0, uploads)
                assertEquals(emptyList<com.noki.vpn.data.EndpointHealthEvent>(), repository.loadEndpointHealthEventQueue())
            }
        }
    }

    @Test
    fun coreAssetRefreshReplacesEqualLengthDataAndPreservesOldFileOnFailure() {
        val context = Fixture(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)).service
        val asset = context.assets.open("geosite.dat").use { it.readBytes() }
        val target = java.io.File(context.noBackupFilesDir, "geosite.dat")
        target.writeBytes(ByteArray(asset.size))
        GeositeAsset.ensureBundledFallback(context)
        org.junit.Assert.assertArrayEquals(asset, target.readBytes())
        val updated = GeositeAssetTest.asset(
            "CATEGORY-RU" to listOf(GeositeAssetTest.domain(2, "ru.example")),
            "ADDED-CATEGORY" to listOf(GeositeAssetTest.domain(2, "added.example")),
        )
        org.junit.Assert.assertTrue(GeositeAsset.isValid(updated))
        GeositeAsset.install(context, updated)
        GeositeAsset.ensureBundledFallback(context)
        org.junit.Assert.assertArrayEquals(updated, target.readBytes())
        try {
            GeositeAsset.install(context, byteArrayOf(0x0a, 0x7f))
            org.junit.Assert.fail("Malformed geosite must fail")
        } catch (_: IllegalArgumentException) {
            org.junit.Assert.assertArrayEquals(updated, target.readBytes())
        }
    }

    @Test
    fun diagnosticQueuesAreClearedOnOptOutAndLogoutWithoutDeletingLocalLogs() = runBlocking {
        val repository = SettingsRepository(Fixture(this).service)
        for (logout in listOf(false, true)) {
            val settings = repository.updateSettings {
                it.copy(isAuthenticated = true, backendAccessToken = "token",
                    advancedSettings = it.advancedSettings.copy(connectionLogsEnabled = true, anonymousLogsEnabled = true))
            }
            repository.recordAppLog("test", message = "local-only")
            repository.enqueueVpnIncident(com.noki.vpn.data.VpnIncidentReport(
                id = "incident", reason = "timeout", countryCode = "lv", locationCode = "lv1",
                recoveryAttempts = 1, outcome = "failed", occurredAt = "2026-09-19T00:00:00Z",
            ), settings)
            repository.saveEndpointHealthEventQueue(listOf(com.noki.vpn.data.EndpointHealthEvent(
                "endpoint", EndpointRankingPolicy.NetworkKind.WIFI,
                com.noki.vpn.data.EndpointHealthEventType.CONNECT_FAIL, false, false,
                com.noki.vpn.data.EndpointScoreBucket.BAD,
            )))
            if (logout) repository.clearAuthAndStageRefreshToken() else repository.updateSettings {
                it.copy(advancedSettings = it.advancedSettings.copy(anonymousLogsEnabled = false))
            }
            assertEquals(emptyList<com.noki.vpn.data.EndpointHealthEvent>(), repository.loadEndpointHealthEventQueue())
            assertEquals(emptyList<com.noki.vpn.data.VpnIncidentReport>(), repository.loadPendingVpnIncidents())
            org.junit.Assert.assertTrue(repository.exportAppLogs().contains("local-only"))
        }
    }

    @Test
    fun killSwitchRetainsTunnelAcrossFailuresAndRestartUntilExplicitStop() = runBlocking {
        val fixture = Fixture(this)
        fixture.enableKillSwitch()
        fixture.useOfflinePreparation()
        val tunnel = fixture.orchestrator.currentTunnel()
        fixture.prepareFailure(java.io.IOException("network lost"))
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertFalse(shadowOf(fixture.service).isStoppedBySelf)

        fixture.prepareFailure(IllegalStateException("auth_required"))
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        fixture.invoke("restartVpn", VpnPreparationStrategy.FreshOnly)
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())

        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertNull(fixture.orchestrator.currentTunnel())
        assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
    }

    @Test
    fun killSwitchKeepsFailedStartupAndInvalidatedReadinessBlocked() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = null)
        fixture.enableKillSwitch()
        fixture.startPrepared()
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        org.junit.Assert.assertNotNull(fixture.orchestrator.currentTunnel())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()

        fixture.onProbe = { fixture.orchestrator.invalidate() }
        fixture.startPrepared()
        org.junit.Assert.assertNotNull(fixture.orchestrator.currentTunnel())
        fixture.stop(2)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun connectedLatencyIsReplayedOnStateQueriesAndClearedOnStop() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        val publish = AppVpnService::class.java.getDeclaredMethod("broadcastState",
            VpnConnectionState::class.java, String::class.java, String::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }
        publish.invoke(fixture.service, VpnConnectionState.CONNECTED, "", "lv1", 30)
        assertEquals(30, AppVpnService.liveRuntimeState().latencyMs)
        fixture.invoke("broadcastCurrentState")
        val query = shadowOf(fixture.service).broadcastIntents.last {
            it.action == AppVpnService.ACTION_STATE_CHANGED
        }
        assertEquals(30, query.getIntExtra(AppVpnService.EXTRA_LATENCY_MS, 0))

        fixture.set("connectedAtMillis", checkNotNull(AppVpnService.liveRuntimeState().connectedAtMillis) + 1L)
        fixture.invoke("broadcastCurrentState")
        assertNull(AppVpnService.liveRuntimeState().latencyMs)
        publish.invoke(fixture.service, VpnConnectionState.CONNECTED, "", "lv1", 87)

        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertNull(AppVpnService.liveRuntimeState().latencyMs)
        val stopped = shadowOf(fixture.service).broadcastIntents.last {
            it.action == AppVpnService.ACTION_STATE_CHANGED
        }
        assertEquals(0, stopped.getIntExtra(AppVpnService.EXTRA_LATENCY_MS, 0))
    }

    @Test
    fun nativeCarrierFollowsTheExistingTickAndClearsOnResetAndStopWithoutLosingGamingLabel() = runBlocking {
        val fixture = Fixture(this)
        fixture.set("connectedAtMillis", 1234L)
        val profile = com.noki.vpn.data.VlessProfile(endpointCode = "nv", proxyType = "nokinv", transport = "nokinv")
        fun label(gaming: Boolean = false): String {
            val live = AppVpnService.liveRuntimeState()
            return com.noki.vpn.ui.protocolCardLabel(com.noki.vpn.AppUiState(
                profile = profile, connectionState = live.state, nokiNvCarrier = live.nokiNvCarrier,
                advancedSettings = com.noki.vpn.data.AdvancedSettings(gamingModeEnabled = gaming),
                personalizationSettings = com.noki.vpn.data.PersonalizationSettings(language = com.noki.vpn.data.AppLanguage.RU),
            ), autoEndpointSelection = true)
        }
        for (carrier in listOf("quic")) {
            fixture.runtimeCarrier = carrier
            fixture.invoke("updateActiveNotification")
            assertEquals(carrier, AppVpnService.liveRuntimeState().nokiNvCarrier)
            val broadcast = shadowOf(fixture.service).broadcastIntents.last {
                it.action == AppVpnService.ACTION_STATE_CHANGED
            }
            assertEquals(carrier, broadcast.getStringExtra(AppVpnService.EXTRA_NOKINV_CARRIER))
            assertEquals("NokiNV", label())
            assertEquals("NokiNV / gaming", label(gaming = true))
        }
        fixture.runtimeCarrier = ""
        fixture.invoke("updateActiveNotification")
        assertEquals("", AppVpnService.liveRuntimeState().nokiNvCarrier)
        assertEquals("NokiNV", label())
        fixture.runtimeCarrier = "quic"
        fixture.invoke("updateActiveNotification")
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertEquals("", AppVpnService.liveRuntimeState().nokiNvCarrier)
        assertEquals("Auto (NokiNV)", label())
        val stopped = shadowOf(fixture.service).broadcastIntents.last {
            it.action == AppVpnService.ACTION_STATE_CHANGED
        }
        assertEquals("", stopped.getStringExtra(AppVpnService.EXTRA_NOKINV_CARRIER))
    }

    @Test
    fun startupReadinessPassesAllTargetsInOneNativeDeadline() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = null)
        fixture.runtimeRunning = true
        fixture.startedEndpoints += "tcp1"
        fixture.onProbe = {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1_800L))
            fixture.workingEndpoint = "tcp1"
        }
        val start = android.os.SystemClock.elapsedRealtime()
        assertEquals(30L, fixture.measureReadiness(fastStartup = true))
        assertEquals(listOf(3_000L), fixture.probeTimeouts)
        assertEquals(listOf(VpnProbePlanPolicy.connectedTargets(false).map { it.url }), fixture.readinessCandidates)
        assertEquals(listOf(false), fixture.watchdogProbes)
        org.junit.Assert.assertTrue(android.os.SystemClock.elapsedRealtime() - start <= VpnProbePlanPolicy.STARTUP_TOTAL_TIMEOUT_MILLIS)
    }

    @Test
    fun healthyCachedStartDoesNotScheduleASelectionRefresh(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        fixture.useOfflinePreparation(onPrepare = { requests.incrementAndGet() })
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()?.join()
        fixture.startAccount(VpnPreparationStrategy.CachedFirst)
        fixture.orchestrator.activeTransitionJob()?.join()
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        val owner = fixture.orchestrator.snapshot().owner
        val starts = fixture.startedEndpoints.size
        val stops = fixture.runtimeStops
        try {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            yield()
            fixture.orchestrator.activeTransitionJob()?.join()
            assertEquals("A healthy cached session must not create a fresh VPN session", 0, requests.get())
            assertEquals(owner, fixture.orchestrator.snapshot().owner)
            assertEquals(starts, fixture.startedEndpoints.size)
            assertEquals(stops, fixture.runtimeStops)
        } finally {
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()?.join()
        }
    }

    @Test
    fun watchdogUsesLivenessWithoutReplacingHealthyRuntime(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        val settings = checkNotNull(fixture.orchestrator.currentSettings())
        val owner = checkNotNull(fixture.orchestrator.snapshot().owner)
        val tunnel = fixture.orchestrator.currentTunnel()
        val starts = fixture.startedEndpoints.size
        val stops = fixture.runtimeStops
        val watchdog = VpnWatchdogController(
            HandlerDelayedTaskScheduler(android.os.Handler(Looper.getMainLooper())),
            { 0L }, fixture::launchWatchdogProbe, { false })
        fixture.set("watchdogController", watchdog)
        watchdog.start(ConnectedWatchdogPolicy.Owner(owner.generationId,
            System.identityHashCode(tunnel), owner.coreId), settings, initialVpnEvidence = false)
        fixture.watchdogProbes.clear()
        try {
            watchdog.forceProbe()
            kotlinx.coroutines.withTimeout(5_000) { while (!watchdog.hasFreshVpnEvidence()) yield() }
            assertEquals(listOf(true), fixture.watchdogProbes)
            assertEquals(4_000L, fixture.probeTimeouts.last())
            assertEquals(VpnProbePlanPolicy.connectedTargets(false).map { it.url }, fixture.readinessCandidates.last())
            assertEquals(starts, fixture.startedEndpoints.size)
            assertEquals(stops, fixture.runtimeStops)
            org.junit.Assert.assertSame(tunnel, fixture.orchestrator.currentTunnel())
        } finally {
            watchdog.stop(null)
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()?.join()
        }
    }

    @Test
    fun requestedLatencyWithoutDirectLocationPublishesFreshSamplesAndCoalescesConcurrentRefreshes() = runBlocking {
        val fixture = Fixture(this)
        val repository = SettingsRepository(fixture.service)
        val settings = repository.load().let {
            it.copy(userProfile = it.userProfile.copy(selectedServerCode = ""),
                advancedSettings = it.advancedSettings.copy(ruRelayEnabled = true))
        }
        var measured = 40
        var probes = 0
        val samples = mutableListOf<Int>()
        val coordinator = VpnStatsCoordinator(
            repository = repository,
            scope = this,
            scheduler = object : DelayedTaskScheduler {
                override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) = Unit
                override fun cancel(owner: Any) = Unit
            },
            measureConnectedLatencyMs = { probes++; yield(); measured },
            onLatencySample = { _, latency -> samples += latency },
        )
        coordinator.start(settings, RuntimeOwner(1L, 1L))
        coordinator.recordInitialLatency(settings, RuntimeOwner(1L, 1L), 25L)
        assertEquals(listOf(25), samples)
        coordinator.refreshLatency()
        coordinator.refreshLatency()
        repeat(3) { yield() }
        assertEquals(listOf(25, 40), samples)
        assertEquals(1, probes)
        measured = 75
        coordinator.refreshLatency()
        repeat(3) { yield() }
        assertEquals(listOf(25, 40, 75), samples)
        coordinator.refreshLatency()
        coordinator.reset()
        repeat(3) { yield() }
        assertEquals(listOf(25, 40, 75), samples)
    }

    @Test
    fun watchdogWaitsForStatsReadinessWithoutCountingNativeContentionAsFailure(): Unit = runBlocking {
        val workers = kotlinx.coroutines.SupervisorJob()
        val scope = CoroutineScope(workers + kotlinx.coroutines.Dispatchers.IO)
        val fixture = Fixture(scope, probeFallback = true, workingEndpoint = "tcp1")
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.startPrepared()
        val settings = checkNotNull(fixture.orchestrator.currentSettings())
        val runtimeOwner = checkNotNull(fixture.orchestrator.snapshot().owner)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val samples = Collections.synchronizedList(mutableListOf<Int>())
        val stats = VpnStatsCoordinator(SettingsRepository(fixture.service), scope,
            HandlerDelayedTaskScheduler(android.os.Handler(Looper.getMainLooper())),
            { fixture.measureReadiness()?.toInt() }, { _, latency -> samples += latency })
        val watchdog = VpnWatchdogController(
            HandlerDelayedTaskScheduler(android.os.Handler(Looper.getMainLooper())),
            android.os.SystemClock::elapsedRealtime, fixture::launchWatchdogProbe, { false })
        fixture.set("watchdogController", watchdog)
        fixture.onProbe = {
            if (entered.count > 0L) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        try {
            stats.start(settings, runtimeOwner)
            stats.refreshLatency()
            org.junit.Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            watchdog.start(ConnectedWatchdogPolicy.Owner(runtimeOwner.generationId,
                System.identityHashCode(fixture.orchestrator.currentTunnel()), runtimeOwner.coreId),
                settings, initialVpnEvidence = false)
            watchdog.forceProbe()
            fixture.busyProbeObserved.await(1, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            kotlinx.coroutines.withTimeout(5_000) { workers.children.toList().forEach { it.join() } }
            assertEquals(listOf(30), samples)
            org.junit.Assert.assertTrue("watchdog must observe the healthy VPN after the stats probe", watchdog.hasFreshVpnEvidence())
            assertEquals(0, fixture.busyProbes.get())
            assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        } finally {
            release.countDown()
            stats.reset()
            watchdog.stop(null)
            workers.cancel()
        }
    }

    @Test
    fun cancelledReadinessWaiterDoesNotCancelStatsOrConsumeTheRebindBudget(): Unit = runBlocking {
        val workers = kotlinx.coroutines.SupervisorJob()
        val scope = CoroutineScope(workers + kotlinx.coroutines.Dispatchers.IO)
        val fixture = Fixture(scope)
        fixture.useRuSession(ruSession()) { 503 to "{}" }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fixture.onProbe = {
            if (entered.count > 0L) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val active = scope.async { fixture.measureReadiness() }
        try {
            org.junit.Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { fixture.measureReadiness() }
            queued.cancel()
            queued.join()
            org.junit.Assert.assertTrue("the cancelled waiter must still have been waiting for the probe owner", queued.isCancelled)
            assertEquals(0, fixture.probeCancels.get())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            fixture.busyProbeObserved.await(1, java.util.concurrent.TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(11))
            release.countDown()
            assertEquals(30L, active.await())
            kotlinx.coroutines.withTimeout(5_000) { workers.children.toList().forEach { it.join() } }
            org.junit.Assert.assertNotNull("queued rebind must receive a fresh readiness budget", fixture.orchestrator.currentUnderlay())
            assertEquals(0, fixture.busyProbes.get())
            assertEquals(0, fixture.probeCancels.get())
            assertEquals(1, fixture.runtimeRebinds)
        } finally {
            release.countDown()
            fixture.invoke("cancelDelayedVpnCallbacks")
            workers.cancel()
        }
    }

    @Test
    fun directNetworkHandoverKeepsTunnelCoreOwnerAndTimerWithoutPreparingAgain(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        fixture.useOfflinePreparation(java.io.IOException("API must not be used")) { error("unexpected API preparation") }
        val tunnel = fixture.orchestrator.currentTunnel()
        val owner = fixture.orchestrator.snapshot().owner
        val generation = fixture.orchestrator.currentGenerationId()
        val starts = fixture.startedEndpoints.size
        val stops = fixture.runtimeStops
        fixture.orchestrator.updateUnderlay(UnderlyingNetworkSnapshot(
            EndpointRankingPolicy.NetworkKind.CELLULAR, "previous-cellular", true, "previous"))
        fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
        yield()
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(owner, fixture.orchestrator.snapshot().owner)
        assertEquals(generation, fixture.orchestrator.currentGenerationId())
        assertEquals(starts, fixture.startedEndpoints.size)
        assertEquals(stops, fixture.runtimeStops)
        assertEquals(1, fixture.runtimeRebinds)
        org.junit.Assert.assertNotNull(fixture.orchestrator.currentUnderlay())
        fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
        yield()
        assertEquals(1, fixture.runtimeRebinds)
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()?.join()
    }

    @Test
    fun screenWakeChecksTheSameRuntimeOnceWithoutRestartingOrChangingSelection(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1", realSidecars = true)
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        val before = fixture.orchestrator.snapshot()
        val tunnel = fixture.orchestrator.currentTunnel()
        val starts = fixture.startedEndpoints.toList()
        val stops = fixture.runtimeStops
        try {
            fixture.invoke("handleScreenStateChanged", true)
            fixture.invoke("handleScreenStateChanged", true)
            fixture.awaitWakeProbe()
            assertEquals(1, fixture.runtimeWakes)
            assertEquals(0, fixture.runtimeRebinds)
            assertEquals(starts, fixture.startedEndpoints)
            assertEquals(stops, fixture.runtimeStops)
            assertEquals(before.owner, fixture.orchestrator.snapshot().owner)
            assertEquals(before.activeSettings, fixture.orchestrator.currentSettings())
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(true, fixture.watchdogProbes.last())
            assertEquals(VpnProbePlanPolicy.WATCHDOG_TOTAL_TIMEOUT_MILLIS, fixture.probeTimeouts.last())
        } finally { fixture.stop(1); fixture.orchestrator.activeTransitionJob()?.join() }
    }

    @Test
    fun failedWakeHttpProbesDoNotResetSocketsOrSelectAnotherServer(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1", realSidecars = true)
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        val before = fixture.orchestrator.snapshot()
        val starts = fixture.startedEndpoints.toList()
        val stops = fixture.runtimeStops
        fixture.workingEndpoint = null
        try {
            repeat(3) {
                fixture.invoke("handleScreenStateChanged", false)
                fixture.invoke("handleScreenStateChanged", true)
                fixture.awaitWakeProbe()
            }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
            yield()
            assertEquals(3, fixture.runtimeWakes)
            assertEquals(0, fixture.runtimeRebinds)
            assertEquals(starts, fixture.startedEndpoints)
            assertEquals(stops, fixture.runtimeStops)
            assertEquals(before.owner, fixture.orchestrator.snapshot().owner)
            assertEquals(before.activeSettings, fixture.orchestrator.currentSettings())
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        } finally { fixture.stop(1); fixture.orchestrator.activeTransitionJob()?.join() }
    }

    @Test
    fun staleWakeProbeCannotApplyEvidenceAfterGenerationChanges(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1", realSidecars = true)
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        fixture.onProbe = { fixture.orchestrator.invalidate() }
        val watchdog = AppVpnService::class.java.getDeclaredField("watchdogController")
            .apply { isAccessible = true }.get(fixture.service) as VpnWatchdogController
        watchdog.resetNetworkEvidence()
        try {
            fixture.invoke("handleScreenStateChanged", true)
            fixture.awaitWakeProbe()
            assertFalse(watchdog.hasFreshVpnEvidence())
            assertEquals(0, fixture.runtimeRebinds)
        } finally { fixture.stop(1); fixture.orchestrator.activeTransitionJob()?.join() }
    }

    @Test
    fun screenAndUnlockBroadcastsProduceOneWakeEvent() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(power).setIsInteractive(false)
        val events = mutableListOf<Boolean>()
        val monitor = VpnScreenStateMonitor(context, events::add)
        monitor.start()
        try {
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_SCREEN_ON))
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true), events)
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF))
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(power).setIsInteractive(true)
            context.sendBroadcast(android.content.Intent(android.os.PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_USER_PRESENT))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(true, false, true), events)
        } finally { monitor.stop() }
    }

    @Test
    fun networkLostDuringWatchdogProbeBeforeCallbackCannotStartRecovery(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        fixture.useOfflinePreparation(java.io.IOException("network lost"))
        val settings = checkNotNull(fixture.orchestrator.currentSettings())
        val runtimeOwner = checkNotNull(fixture.orchestrator.snapshot().owner)
        val generation = fixture.orchestrator.currentGenerationId()
        val stops = fixture.runtimeStops
        val watchdog = VpnWatchdogController(
            HandlerDelayedTaskScheduler(android.os.Handler(Looper.getMainLooper())),
            { 0L }, fixture::launchWatchdogProbe, { false })
        fixture.set("watchdogController", watchdog)
        watchdog.start(ConnectedWatchdogPolicy.Owner(generation,
            System.identityHashCode(fixture.orchestrator.currentTunnel()), runtimeOwner.coreId),
            settings, initialVpnEvidence = false)
        fixture.workingEndpoint = null
        try {
            watchdog.forceProbe()
            yield()
            fixture.onProbe = { fixture.setNetworkAvailability(UnderlyingNetworkAvailability.None) }
            watchdog.forceProbe()
            yield()
            assertEquals("underlay loss must not start endpoint recovery before the debounced callback",
                generation, fixture.orchestrator.currentGenerationId())
            assertEquals(stops, fixture.runtimeStops)
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        } finally {
            watchdog.stop(null)
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()?.join()
        }
    }

    @Test
    fun directFailedSocketRebindRetriesWithoutReplacingTheRuntime(): Unit = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared(EndpointSelectionMode.MANUAL)
        val tunnel = fixture.orchestrator.currentTunnel()
        val starts = fixture.startedEndpoints.size
        val stops = fixture.runtimeStops
        fixture.orchestrator.updateUnderlay(null)
        fixture.runtimeRebindSucceeds = false
        try {
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            yield()
            assertEquals(1, fixture.runtimeRebinds)
            assertNull(fixture.orchestrator.currentUnderlay())
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
            fixture.runtimeRebindSucceeds = true
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            yield()
            assertEquals(2, fixture.runtimeRebinds)
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(starts, fixture.startedEndpoints.size)
            assertEquals(stops, fixture.runtimeStops)
            fixture.orchestrator.withLifecycleLock {}
            org.junit.Assert.assertNotNull(fixture.orchestrator.currentUnderlay())
        } finally {
            fixture.invoke("cancelDelayedVpnCallbacks")
        }
    }

    @Test
    fun transientPreparationFailuresKeepRecoveryAliveAndBackOff() = runBlocking {
        val fixture = Fixture(this)
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.useOfflinePreparation()
        fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)
        fixture.prepareFailure(java.io.IOException("network lost"))
        repeat(3) { attempt ->
            if (attempt > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(
                    java.time.Duration.ofMillis(ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt - 1, jitterFraction = 1.0)),
                )
                fixture.orchestrator.activeTransitionJob()!!.join()
            }

            assertFalse("network loss must not stop automatic recovery", shadowOf(fixture.service).isStoppedBySelf)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertNull("failed VPN must release ordinary internet", fixture.orchestrator.currentTunnel())
            assertEquals(attempt + 1, fixture.recoveryAttempt())
            fixture.invoke("broadcastCurrentState")
            assertFalse("QUERY_STATE must preserve the retry", shadowOf(fixture.service).isStoppedBySelf)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        }
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
        assertEquals(0, fixture.recoveryAttempt())
        assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
    }

    @Test
    fun offlineRecoveryWaitsForValidationThenResumesOnlyOnce() = runBlocking {
        for (killSwitch in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.useOfflinePreparation()
            if (killSwitch) fixture.enableKillSwitch()
            fixture.setNetworkAvailability(UnderlyingNetworkAvailability.None)
            fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)
            fixture.prepareFailure(java.io.IOException("offline"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
            val attempt = fixture.recoveryAttempt()
            assertNull(fixture.orchestrator.activeTransitionJob())
            org.junit.Assert.assertNotNull(fixture.pendingNetworkRecovery())
            assertEquals(killSwitch, fixture.orchestrator.currentTunnel() != null)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Unvalidated)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
            assertEquals(attempt, fixture.recoveryAttempt())
            assertNull(fixture.orchestrator.activeTransitionJob())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            val recoveryJob = checkNotNull(fixture.orchestrator.activeTransitionJob())
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            assertEquals(recoveryJob, fixture.orchestrator.activeTransitionJob())
            recoveryJob.join()
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertNull(fixture.pendingNetworkRecovery())
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
        }
    }

    @Test
    fun stoppedOrSupersededOfflineRecoveryCannotRestartOnNetworkReturn() = runBlocking {
        for (invalidation in listOf("stop", "generation", "logout")) {
            val fixture = Fixture(this)
            fixture.useOfflinePreparation()
            fixture.setNetworkAvailability(UnderlyingNetworkAvailability.None)
            fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)
            fixture.prepareFailure(java.io.IOException("offline"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
            org.junit.Assert.assertNotNull(fixture.pendingNetworkRecovery())
            when (invalidation) {
                "stop" -> {
                    fixture.stop(1)
                    fixture.orchestrator.activeTransitionJob()!!.join()
                }
                "generation" -> fixture.orchestrator.beginTransition()
                "logout" -> fixture.clearStoredAuth()
            }
            val attempts = fixture.recoveryAttempt()
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(6))
            assertNull(invalidation, fixture.orchestrator.activeTransitionJob())
            assertNull(invalidation, fixture.pendingNetworkRecovery())
            assertEquals(invalidation, attempts, fixture.recoveryAttempt())
        }
    }

    @Test
    fun permanentPreparationFailureStopsRecovery() = runBlocking {
        val fixture = Fixture(this)
        fixture.set("transientRecoveryAttempt", 2)
        fixture.prepareFailure(IllegalStateException("auth_required"))

        org.junit.Assert.assertTrue(shadowOf(fixture.service).isStoppedBySelf)
        assertEquals(0, fixture.recoveryAttempt())
        assertNull(fixture.orchestrator.currentTunnel())
    }

    @Test
    fun permanentPreparationFailureKeepsKillSwitchWithoutRetry() = runBlocking {
        for (destructive in listOf(false, true)) {
            val fixture = Fixture(this)
            fixture.useOfflinePreparation()
            fixture.enableKillSwitch()
            fixture.orchestrator.updateState(VpnConnectionState.FAILED)
            val tunnel = fixture.orchestrator.currentTunnel()
            fixture.prepareFailure(IllegalStateException("auth_required"), destructive)
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertNull(fixture.orchestrator.activeTransitionJob())
            assertNull(fixture.pendingNetworkRecovery())
            assertEquals(0, fixture.recoveryAttempt())
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
        }
    }

    @Test
    fun nonDestructiveRefreshFailureKeepsConnectedTunnel() = runBlocking {
        val fixture = Fixture(this)
        val tunnel = fixture.orchestrator.currentTunnel()
        fixture.prepareFailure(java.io.IOException("network lost"), destructive = false)

        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(0, fixture.recoveryAttempt())
    }

    @Test
    fun requiredClientUpdateEndsConnectedRefreshWithoutLoggingOut() = runBlocking {
        for (killSwitch in listOf(false, true)) {
            val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
            fixture.startPrepared()
            fixture.useOfflinePreparation(BackendException("client_update_required", 426))
            fixture.saveSettings(fixture.savedSettings().let {
                it.copy(isAuthenticated = true, backendAccessToken = "access", backendRefreshToken = "refresh",
                    advancedSettings = it.advancedSettings.copy(killSwitchEnabled = killSwitch))
            })
            val tunnel = fixture.orchestrator.currentTunnel()
            val starts = fixture.startedEndpoints.size

            fixture.invoke("refreshConnectedVpn", true)
            fixture.orchestrator.activeTransitionJob()!!.join()

            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertEquals(false, fixture.runtimeRunning)
            assertEquals(if (killSwitch) tunnel else null, fixture.orchestrator.currentTunnel())
            assertEquals(starts, fixture.startedEndpoints.size)
            assertEquals(0, fixture.recoveryAttempt())
            assertEquals(true, fixture.savedSettings().isAuthenticated)
            assertEquals("access", fixture.savedSettings().backendAccessToken)
            assertEquals("refresh", fixture.savedSettings().backendRefreshToken)
            assertEquals("client_update_required", shadowOf(fixture.service).broadcastIntents.last {
                it.action == AppVpnService.ACTION_STATE_CHANGED
            }.getStringExtra(AppVpnService.EXTRA_REASON))
        }
    }

    @Test
    fun requiredClientUpdateStopsRecoveryAtTheFirstRejectedPreparation() = runBlocking {
        for ((killSwitch, useCachedProfile) in listOf(false to true, true to true, false to false, true to false)) {
            val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
            fixture.startPrepared()
            var prepares = 0
            var startsAtRejection = -1
            fixture.useOfflinePreparation(BackendException("client_update_required", 426)) {
                prepares++
                startsAtRejection = fixture.startedEndpoints.size
            }
            fixture.saveSettings(fixture.savedSettings().let {
                it.copy(isAuthenticated = true, backendAccessToken = "access", backendRefreshToken = "refresh",
                    advancedSettings = it.advancedSettings.copy(killSwitchEnabled = killSwitch))
            })
            val settings = checkNotNull(fixture.orchestrator.currentSettings())
            if (!useCachedProfile) {
                fixture.saveSettings(fixture.savedSettings().let { it.copy(profile = it.profile.copy(uuid = "")) })
                fixture.orchestrator.updateSettings(settings)
            }
            assertEquals(useCachedProfile, RuntimeProfilePolicy.isCachedProfileUsable(fixture.savedSettings()))
            val tunnel = fixture.orchestrator.currentTunnel()
            fixture.workingEndpoint = null

            fixture.invoke("recoverConnectedVpn", settings)
            fixture.orchestrator.activeTransitionJob()!!.join()

            assertEquals(1, prepares)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertEquals(false, fixture.runtimeRunning)
            assertEquals(if (killSwitch) tunnel else null, fixture.orchestrator.currentTunnel())
            assertEquals(startsAtRejection, fixture.startedEndpoints.size)
            assertEquals(0, fixture.recoveryAttempt())
            assertEquals(true, fixture.savedSettings().isAuthenticated)
            assertEquals("access", fixture.savedSettings().backendAccessToken)
            assertEquals("refresh", fixture.savedSettings().backendRefreshToken)
            assertEquals("client_update_required", shadowOf(fixture.service).broadcastIntents.last {
                it.action == AppVpnService.ACTION_STATE_CHANGED
            }.getStringExtra(AppVpnService.EXTRA_REASON))
        }
    }

    @Test
    fun requiredClientUpdateStopsNextNodeFallbackWithoutReplacingItsReason() = runBlocking {
        for (killSwitch in listOf(false, true)) {
            val fixture = Fixture(this, probeFallback = true, workingEndpoint = null)
            var prepares = 0
            var startsAtRejection = -1
            fixture.useOfflinePreparation(BackendException("client_update_required", 426)) {
                prepares++
                startsAtRejection = fixture.startedEndpoints.size
            }
            fixture.saveSettings(fixture.savedSettings().let {
                it.copy(isAuthenticated = true, backendAccessToken = "access", backendRefreshToken = "refresh",
                    advancedSettings = it.advancedSettings.copy(killSwitchEnabled = killSwitch))
            })

            fixture.startPrepared(nodeId = "failed-node")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))

            assertEquals(1, prepares)
            assertEquals(5, startsAtRejection)
            assertEquals(startsAtRejection, fixture.startedEndpoints.size)
            assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
            assertEquals(false, fixture.runtimeRunning)
            assertEquals(killSwitch, fixture.orchestrator.currentTunnel() != null)
            assertNull(fixture.orchestrator.activeTransitionJob())
            assertNull(fixture.pendingNetworkRecovery())
            assertEquals(0, fixture.recoveryAttempt())
            assertEquals(true, fixture.savedSettings().isAuthenticated)
            assertEquals("access", fixture.savedSettings().backendAccessToken)
            assertEquals("refresh", fixture.savedSettings().backendRefreshToken)
            assertEquals("client_update_required", shadowOf(fixture.service).broadcastIntents.last {
                it.action == AppVpnService.ACTION_STATE_CHANGED
            }.getStringExtra(AppVpnService.EXTRA_REASON))
        }
    }

    @Test
    fun requiredClientUpdateKeepsRuKillSwitchWithoutSchedulingRetry() = runBlocking {
        val fixture = Fixture(this)
        fixture.useRuSession(ruSession()) { error("Required update must not retry the RU session") }
        fixture.enableKillSwitch()
        val tunnel = fixture.orchestrator.currentTunnel()

        AppVpnService::class.java.getDeclaredMethod(
            "failStart", SettingsRepository::class.java, String::class.java, Throwable::class.java,
            String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(
            fixture.service, SettingsRepository(fixture.service), "client_update_required",
            BackendException("client_update_required", 426), "vpn", null, true,
        )

        assertEquals(false, AppVpnService::class.java.getDeclaredField("lockdownRecoveryActive")
            .apply { isAccessible = true }.getBoolean(fixture.service))
        fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(1))
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(false, fixture.runtimeRunning)
        assertNull(fixture.ruSession())
        assertNull(fixture.orchestrator.activeTransitionJob())
        assertNull(fixture.pendingNetworkRecovery())
        assertEquals(0, fixture.recoveryAttempt())
        assertEquals(true, fixture.savedSettings().isAuthenticated)
        assertEquals("lease-access", fixture.savedSettings().backendAccessToken)
        assertEquals("client_update_required", shadowOf(fixture.service).broadcastIntents.last {
            it.action == AppVpnService.ACTION_STATE_CHANGED
        }.getStringExtra(AppVpnService.EXTRA_REASON))
    }

    @Test
    fun failedRuntimeCandidatesReachHysteriaWithinOneStart() = runBlocking {
        val fixture = Fixture(this, probeFallback = true)
        fixture.startPrepared()

        assertEquals(listOf("tcp1", "hy2"), fixture.startedEndpoints)
        assertEquals("hy2", fixture.startedEndpoints.last())
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals("hy2", fixture.orchestrator.currentSettings()?.profile?.endpointCode)
        assertEquals("hy2", fixture.savedSettings().profile.endpointCode)
        assertEquals(1, fixture.tunnelsCreated)
        assertTrue(fixture.probeTimeouts.isNotEmpty())
        assertTrue(fixture.probeTimeouts.all { it == VpnProbePlanPolicy.STARTUP_TOTAL_TIMEOUT_MILLIS })
    }

    @Test
    fun losingValidatedUnderlayKeepsTunnelUntilNetworkReturnsOrUserStops() = runBlocking {
        for (killSwitch in listOf(false, true)) {
            val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
            if (killSwitch) fixture.enableKillSwitch()
            fixture.startPrepared(EndpointSelectionMode.MANUAL)
            val tunnel = fixture.orchestrator.currentTunnel()
            val owner = fixture.orchestrator.snapshot().owner
            val stops = fixture.runtimeStops
            fixture.observeNetwork(UnderlyingNetworkAvailability.None)
            yield()
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(owner, fixture.orchestrator.snapshot().owner)
            assertEquals(stops, fixture.runtimeStops)
            assertNull(fixture.orchestrator.currentUnderlay())
            assertFalse(shadowOf(fixture.service).isStoppedBySelf)
            fixture.observeNetwork(UnderlyingNetworkAvailability.Unvalidated)
            yield()
            assertEquals(0, fixture.runtimeRebinds)
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            yield()
            assertEquals(1, fixture.runtimeRebinds)
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
            fixture.observeNetwork(UnderlyingNetworkAvailability.Validated)
            assertEquals(VpnConnectionState.DISCONNECTED, fixture.orchestrator.currentState())
        }
    }

    @Test
    fun healthyReplacementReportsConnectingUntilReadinessIsProven() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        var observed = false
        fixture.onProbe = {
            observed = true
            assertEquals(VpnConnectionState.CONNECTING, fixture.orchestrator.currentState())
        }
        org.junit.Assert.assertTrue(fixture.replaceConnected())
        org.junit.Assert.assertTrue(observed)
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals(true, fixture.runtimeRunning)
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun cancelledInitialReadinessStopsRuntimeAndKeepsOnlyPolicyOwnedTunnel() = runBlocking {
        for (killSwitch in listOf(false, true)) {
            val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
            if (killSwitch) fixture.enableKillSwitch()
            lateinit var transition: kotlinx.coroutines.Job
            fixture.onProbe = { transition.cancel() }
            transition = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { fixture.startPrepared() }
            transition.start()
            transition.join()
            assertEquals(true, transition.isCancelled)
            assertEquals(false, fixture.runtimeRunning)
            assertEquals(killSwitch, fixture.orchestrator.currentTunnel() != null)
            assertNull(fixture.orchestrator.snapshot().owner)
            // A superseding transition may be a no-op; it must not be needed for cleanup.
            fixture.orchestrator.launchTransition(this, VpnConnectionOperation.CONNECTION,
                onError = { _, error -> throw error }) { }.join()
            assertEquals(false, fixture.runtimeRunning)
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
        }
    }

    @Test
    fun cancelledReplacementReadinessStopsCandidateWithoutClosingExistingTunnel() = runBlocking {
        for (rollback in listOf(false, true)) {
            val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
            fixture.startPrepared()
            // This rollback belongs to the unchanged selection; the fixture's fake
            // SettingsStore and real SettingsRepository must start from that same intent.
            SettingsRepository(fixture.service).updateSettings { checkNotNull(fixture.orchestrator.currentSettings()) }
            val tunnel = fixture.orchestrator.currentTunnel()
            lateinit var transition: kotlinx.coroutines.Job
            if (rollback) fixture.workingEndpoint = null
            fixture.onProbe = {
                if (!rollback || fixture.startedEndpoints.size == 3) transition.cancel()
            }
            transition = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                fixture.replaceConnected(rollbackOnFailure = rollback)
            }
            transition.start()
            transition.join()
            assertEquals("rollback=$rollback; starts=${fixture.startedEndpoints}; stale=" +
                VpnSettingsTransactionPolicy.candidateBecameStale(checkNotNull(fixture.orchestrator.currentSettings()),
                    SettingsRepository(fixture.service).load()), true, transition.isCancelled)
            assertEquals(false, fixture.runtimeRunning)
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertNull(fixture.orchestrator.snapshot().owner)
            fixture.orchestrator.launchTransition(this, VpnConnectionOperation.CONNECTION,
                onError = { _, error -> throw error }) { }.join()
            assertEquals(false, fixture.runtimeRunning)
            fixture.stop(1)
            fixture.orchestrator.activeTransitionJob()!!.join()
        }
    }

    @Test
    fun serviceDestroyStopsLocalYoutubeBeforeDeferredCoreCleanup() = runBlocking {
        val fixture = Fixture(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var stops = 0
        var endpointsClosed = 0
        val local = YoutubeConnection(scope, { "wifi" }, {
            YoutubeEndpoint(30_001, java.io.FileDescriptor()) { endpointsClosed++ }
        }, { _, _ -> object : YoutubeRuntime {
            override suspend fun start() = Unit
            override suspend fun awaitExit(): Int = awaitCancellation()
            override fun stop() { stops++ }
        } }, { throw AssertionError(it) })
        val defaults = DefaultStoredSettingsFactory.create()
        local.start(defaults.copy(isAuthenticated = true, backendDeviceId = "device",
            userProfile = defaults.userProfile.copy(selectedPlanTier = "plus"),
            advancedSettings = defaults.advancedSettings.copy(youtubeNoAdsEnabled = true)))
        fixture.set("youtubeConnection\$delegate", lazyOf(local))
        fixture.set("screenStateMonitor", VpnScreenStateMonitor(fixture.service) { })
        fixture.set("destroyCleanupScope", scope)
        fixture.orchestrator.lifecycleMutex.lock()
        try {
            fixture.service.onDestroy()

            assertTrue(stops > 0)
            assertEquals(1, endpointsClosed)
            assertNotNull("core cleanup is still waiting for its lifecycle lock", fixture.orchestrator.currentTunnel())
        } finally {
            fixture.orchestrator.lifecycleMutex.unlock()
            scope.coroutineContext[Job]!!.children.forEach { it.join() }
            local.stop()
            scope.cancel()
        }
    }

    @Test
    fun localYoutubeFailurePreservesTheRunningPrimaryCoreAndTunnel() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val exit = kotlinx.coroutines.CompletableDeferred<Int>()
        val failures = mutableListOf<Throwable>()
        val local = YoutubeConnection(scope, { "wifi" }, {
            YoutubeEndpoint(30_001, java.io.FileDescriptor()) { }
        }, { _, _ -> object : YoutubeRuntime {
            override suspend fun start() = Unit
            override suspend fun awaitExit(): Int = exit.await()
            override fun stop() = Unit
        } }, { failures += it })
        fixture.set("youtubeConnection\$delegate", lazyOf(local))
        val defaults = DefaultStoredSettingsFactory.create()
        fixture.saveSettings(defaults.copy(isAuthenticated = true, backendDeviceId = "device", backendDeviceKey = "key",
            userProfile = defaults.userProfile.copy(selectedPlanTier = "plus"),
            advancedSettings = defaults.advancedSettings.copy(ruRelayEnabled = false, youtubeNoAdsEnabled = true)))
        fixture.onConnected = { _, settings -> local.start(settings) }
        try {
            fixture.startPrepared()
            val tunnel = fixture.orchestrator.currentTunnel()
            val starts = fixture.startedConfigs.size
            val stops = fixture.runtimeStops
            val endpoint = local.configFor(checkNotNull(fixture.orchestrator.currentSettings()))
            assertTrue(fixture.startedConfigs.last().getJSONArray("outbounds").toString().contains("youtube-local"))

            exit.complete(4)
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(failures.any { it.message == "youtube_byedpi_exited_4" })
            assertEquals(endpoint, local.configFor(checkNotNull(fixture.orchestrator.currentSettings())))
            assertEquals(starts, fixture.startedConfigs.size)
            assertEquals(stops, fixture.runtimeStops)
            assertEquals(tunnel, fixture.orchestrator.currentTunnel())
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
            assertTrue(fixture.runtimeRunning)
        } finally {
            local.stop()
            scope.cancel()
        }
    }

    @Test
    fun cancelledFailedClosedReadinessStopsRuntimeAndRetainsBlockedTunnel() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.enableKillSwitch()
        val tunnel = fixture.orchestrator.currentTunnel()
        lateinit var transition: kotlinx.coroutines.Job
        fixture.onProbe = { transition.cancel() }
        transition = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            fixture.startPrepared(failedClosed = true)
        }
        transition.start()
        transition.join()
        assertEquals(true, transition.isCancelled)
        assertEquals(false, fixture.runtimeRunning)
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertNull(fixture.orchestrator.snapshot().owner)
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun revokedAdmissionDuringReplacementCannotBecomeConnected() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        val tunnel = fixture.orchestrator.currentTunnel()
        var admitted = true
        fixture.onProbe = { admitted = false }
        assertFalse(fixture.replaceConnected { admitted })
        assertEquals(VpnConnectionState.CONNECTING, fixture.orchestrator.currentState())
        assertNull(fixture.orchestrator.snapshot().owner)
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun failedReplacementThenBackendTimeoutReportsFailureAndKeepsRetry() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        fixture.workingEndpoint = null
        assertFalse(fixture.replaceConnected())
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        fixture.setNetworkAvailability(UnderlyingNetworkAvailability.Validated)
        fixture.useOfflinePreparation()
        fixture.prepareFailure(java.io.IOException("backend timeout"), destructive = false)
        fixture.invoke("broadcastCurrentState")
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertFalse(shadowOf(fixture.service).isStoppedBySelf)
        assertEquals(1, fixture.recoveryAttempt())
        assertNull(fixture.orchestrator.currentTunnel())
        shadowOf(Looper.getMainLooper()).idleFor(
            java.time.Duration.ofMillis(ConnectedWatchdogPolicy.transientRetryDelayMillis(0, jitterFraction = 1.0)),
        )
        fixture.orchestrator.activeTransitionJob()!!.join()
        assertEquals(2, fixture.recoveryAttempt())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun failedReplacementRetainsTunnelForNextHealthyCandidate() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()
        val tunnel = fixture.orchestrator.currentTunnel()
        fixture.workingEndpoint = null
        assertFalse(fixture.replaceConnected())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        fixture.workingEndpoint = "tcp1"
        org.junit.Assert.assertTrue(fixture.replaceConnected())
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
        assertEquals(tunnel, fixture.orchestrator.currentTunnel())
        fixture.stop(1)
        fixture.orchestrator.activeTransitionJob()!!.join()
    }

    @Test
    fun failedAutoStartExhaustsCandidatesOnceBeforeReportingFailure() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = null)
        fixture.startPrepared()

        assertEquals(setOf("tcp1", "tcp2", "tcp3", "tcp4", "hy2"), fixture.startedEndpoints.toSet())
        assertEquals(5, fixture.startedEndpoints.size)
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertNull(fixture.orchestrator.currentTunnel())
        assertFalse(fixture.savedSettings().profile.endpointCode == "hy2")
    }

    @Test
    fun manualStartDoesNotSwitchToAnotherEngineAfterRuntimeFailure() = runBlocking {
        val fixture = Fixture(this, probeFallback = true)
        fixture.startPrepared(mode = EndpointSelectionMode.MANUAL)

        assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
    }

    @Test
    fun successfulFirstCandidateDoesNotStartFallbacks() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.startPrepared()

        assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
    }

    @Test
    fun invalidatedStartDoesNotTryAnotherCandidate() = runBlocking {
        val fixture = Fixture(this, probeFallback = true)
        fixture.onProbe = { fixture.orchestrator.invalidate() }
        fixture.startPrepared()

        assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        assertNull(fixture.orchestrator.currentTunnel())
        assertNull(fixture.orchestrator.currentSettings())
    }

    @Test
    fun ruSelectionChangedDuringCoreReadinessCannotBecomeConnected() = runBlocking {
        val fixture = Fixture(this, probeFallback = true, workingEndpoint = "tcp1")
        fixture.onProbe = { fixture.enableRuAfterPrepare() }

        fixture.startPrepared()

        assertEquals(listOf("tcp1"), fixture.startedEndpoints)
        assertEquals(VpnConnectionState.FAILED, fixture.orchestrator.currentState())
        assertEquals(true, fixture.savedSettings().advancedSettings.ruRelayEnabled)
    }

    @Before
    fun provideInMemoryAndroidKeyStore() {
        Security.addProvider(object : Provider("NokiVpnCommandTest", 1.0, "Test-only Android keystore") {
            init {
                put("KeyStore.AndroidKeyStore", InMemoryAndroidKeyStore::class.java.name)
            }
        })
    }

    @Test
    fun connectedBackgroundRefreshesExpiringAuthWhenFcmSkipsNotificationRequests() = runBlocking {
        val fixture = Fixture(this)
        val repository = SettingsRepository(fixture.service)
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val payload = encoder.encodeToString(
            "{\"exp\":${System.currentTimeMillis() / 1_000L + 120L}}".toByteArray(),
        )
        repository.updateSettings {
            it.copy(isAuthenticated = true, backendAccessToken = "header.$payload.signature",
                backendRefreshToken = "refresh-before", backendDeviceId = "device-1",
                userProfile = it.userProfile.copy(backendUserId = "user-1"))
        }
        repository.saveLastRegisteredFcmTokenHash("push-hash", "device-1")
        assertTrue(repository.isFcmPushRegistered())
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val requestIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.encodedPath
            val requestBody = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            requestIds += JSONObject(requestBody).getString("request_id")
            val failed = requests.size == 1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (failed) 503 else 200).message("test")
                .body((if (failed) """{"detail":"temporarily_unavailable"}"""
                    else """{"access_token":"access-after","refresh_token":"refresh-after","expires_in":3600}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        fixture.set("backendApi", BackendApiClient(client, "https://api.example"))
        val backgroundJob = SupervisorJob()
        fixture.set("backgroundScope", CoroutineScope(coroutineContext + backgroundJob))
        try {
            fixture.invoke("pollAppNotifications")
            backgroundJob.children.toList().forEach { it.join() }

            assertTrue(repository.load().isAuthenticated)
            assertEquals("refresh-before", repository.load().backendRefreshToken)
            val pendingId = repository.load().backendRefreshRequestId
            assertNotNull(pendingId)
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
            assertEquals(0, fixture.runtimeStops)

            fixture.invoke("pollAppNotifications")
            backgroundJob.children.toList().forEach { it.join() }

            assertEquals(listOf("/v1/auth/refresh", "/v1/auth/refresh"), requests)
            assertEquals(listOf(pendingId, pendingId), requestIds)
            assertEquals("access-after", repository.load().backendAccessToken)
            assertEquals("refresh-after", repository.load().backendRefreshToken)
            assertEquals(VpnConnectionState.CONNECTED, fixture.orchestrator.currentState())
            assertEquals(0, fixture.runtimeStops)
        } finally {
            backgroundJob.cancel()
        }
    }

    @After
    fun removeInMemoryAndroidKeyStore() {
        Security.removeProvider("NokiVpnCommandTest")
    }

    @Test
    fun startAfterTunnelReleaseKeepsServiceAliveUntilStopCleanupCompletes() = runBlocking {
        val fixture = Fixture(this)
        val stop = fixture.orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.STOP,
            onError = { _, error -> throw error },
        ) { awaitCancellation() }
        fixture.orchestrator.withLifecycleLock {
            fixture.orchestrator.releaseResourcesWhileOwned(VpnConnectionState.DISCONNECTED)
        }
        try {
            fixture.startAccount()

            assertFalse("queued START still needs this service", shadowOf(fixture.service).isStoppedBySelf)
            assertEquals(VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly), fixture.pendingStart())
        } finally {
            stop.cancel()
        }
    }

    @Test
    fun accountStartWhileConnectedStopIsCleaningUpIsRetained() = runBlocking {
        val fixture = Fixture(this)
        val stop = fixture.orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.STOP,
            onError = { _, error -> throw error },
        ) { awaitCancellation() }
        try {
            fixture.startAccount()

            assertEquals(
                VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly),
                fixture.pendingStart(),
            )
        } finally {
            stop.cancel()
        }
    }

    @Test
    fun temporaryStartWhileConnectedStopIsCleaningUpIsRetained() = runBlocking {
        val fixture = Fixture(this)
        val stop = fixture.orchestrator.launchTransition(
            scope = this,
            operation = VpnConnectionOperation.STOP,
            onError = { _, error -> throw error },
        ) { awaitCancellation() }
        try {
            fixture.invoke("startTemporaryVpn")

            assertEquals(
                VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly, VpnRuntimeMode.AUTH_TEMP),
                fixture.pendingStart(),
            )
        } finally {
            stop.cancel()
        }
    }

    @Test
    fun postedStopCompletionCannotRestoreStartCancelledByNewerStop() = runBlocking {
        val fixture = Fixture(this)
        fixture.stop(1)
        // The START was already admitted while cleanup was in progress. Keep this
        // regression independent of the separate connected-start admission bug.
        fixture.set("pendingStartOptions", VpnServiceStartCommandPolicy.StartOptions(VpnPreparationStrategy.FreshOnly))
        fixture.orchestrator.activeTransitionJob()!!.join()

        fixture.orchestrator.lifecycleMutex.lock()
        try {
            fixture.stop(2)
            val newerStop = fixture.orchestrator.activeTransitionJob()!!
            yield()
            fixture.orchestrator.updateState(VpnConnectionState.CONNECTING)

            shadowOf(Looper.getMainLooper()).idle()

            assertNull("the newest STOP must not acquire the older queued START", fixture.pendingStart())
            assertEquals(newerStop, fixture.orchestrator.activeTransitionJob())
        } finally {
            fixture.orchestrator.lifecycleMutex.unlock()
            fixture.orchestrator.activeTransitionJob()?.join()
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        private val probeFallback: Boolean = false,
        var workingEndpoint: String? = "hy2",
        private val realSidecars: Boolean = false,
    ) {
        val service = Robolectric.buildService(AppVpnService::class.java).get()
        val startedEndpoints = mutableListOf<String>()
        val startedConfigs = mutableListOf<JSONObject>()
        val probeTimeouts = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val readinessCandidates = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val watchdogProbes = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        var runtimeRunning = false
        var runtimeCarrier = ""
        var runtimeStops = 0
        var runtimeRebinds = 0
        var runtimeWakes = 0
        var runtimeRebindSucceeds = true
        var tunnelsCreated = 0
        var onProbe: () -> Unit = {}
        var onConnected: (RuntimeOwner, StoredSettings) -> Unit = { _, _ -> }
        val busyProbes = java.util.concurrent.atomic.AtomicInteger()
        val probeCancels = java.util.concurrent.atomic.AtomicInteger()
        val busyProbeObserved = java.util.concurrent.CountDownLatch(3)
        private val probeActive = java.util.concurrent.atomic.AtomicBoolean()
        private val store = object : AtomicStoredSettingsStore {
            private var value = DefaultStoredSettingsFactory.create().let {
                it.copy(advancedSettings = it.advancedSettings.copy(ruRelayEnabled = false))
            }
            override fun load(): StoredSettings = value
            override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
                val (updated, result) = transform(value)
                value = updated
                return result
            }
        }
        private val scheduler = HandlerDelayedTaskScheduler(android.os.Handler(Looper.getMainLooper()))
        private val preparer = VpnConnectionPreparer(
            store = store,
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.OTHER },
            resolveStart = { _, _, _, _ -> error("unexpected backend call") },
            refreshAccessToken = { error("unexpected token refresh") },
        )
        val orchestrator = VpnConnectionOrchestrator(
            core = object : VpnCoreRuntime {
                override fun nokiNvCarrier(): String = runtimeCarrier
                override fun start(config: String, tunFd: Int): Boolean {
                    check(probeFallback)
                    val parsed = JSONObject(config)
                    startedConfigs += parsed
                    val outbound = parsed.getJSONArray("outbounds").getJSONObject(0)
                    startedEndpoints += if (outbound.getString("type") == "hysteria2") "hy2"
                        else outbound.getString("server").substringBefore('.')
                    runtimeRunning = true
                    return true
                }
                override fun stop() { runtimeStops++; runtimeRunning = false }
                override fun wake() { runtimeWakes++ }
                override fun rebindNetwork(): Boolean {
                    runtimeRebinds++
                    return runtimeRebindSucceeds
                }
                override fun cancelMeasureDelay() { probeCancels.incrementAndGet() }
                override fun prepareReadinessCandidates(targetUrls: List<String>, timeoutMillis: Long, watchdog: Boolean): VpnReadinessProbe {
                    readinessCandidates += targetUrls
                    watchdogProbes += watchdog
                    val completed = java.util.concurrent.atomic.AtomicBoolean()
                    val cancelled = java.util.concurrent.atomic.AtomicBoolean()
                    return VpnReadinessProbe(
                        measure = {
                            if (cancelled.get()) VpnProbeResult(null) else try {
                                measureDelay(targetUrls.first(), timeoutMillis)
                            } finally { completed.set(true) }
                        },
                        cancel = {
                            if (!completed.get() && cancelled.compareAndSet(false, true)) probeCancels.incrementAndGet()
                        },
                    )
                }
                override fun measureDelay(targetUrl: String, timeoutMillis: Long): VpnProbeResult {
                    probeTimeouts += timeoutMillis
                    if (!probeActive.compareAndSet(false, true)) {
                        busyProbes.incrementAndGet()
                        busyProbeObserved.countDown()
                        return VpnProbeResult(null)
                    }
                    try {
                        onProbe()
                        val endpoint = if (ruSession() != null) "ru" else startedEndpoints.lastOrNull()
                        return VpnProbeResult(delayMs = if (runtimeRunning && endpoint != null &&
                            endpoint == workingEndpoint) 30L else null)
                    } finally { probeActive.set(false) }
                }
            },
            tunFactory = object : TunInterfaceFactory {
                override fun establish(settings: StoredSettings, underlay: UnderlyingNetworkSnapshot?): TunHandle {
                    check(probeFallback)
                    tunnelsCreated++
                    return object : TunHandle {
                        override val fd = 7
                        override fun close() = Unit
                    }
                }
            },
            sidecars = OwnedVpnConnectedSidecars(
                onStart = { owner, settings ->
                    onConnected(owner, settings)
                    if (realSidecars) invoke("startConnectedSidecars", owner, settings)
                },
                onStop = { owner ->
                    if (realSidecars && owner != null) invoke("stopConnectedSidecars", owner)
                },
            ),
        )

        init {
            GeositeAsset.ensureBundledFallback(service)
            SettingsRepository(service).updateSettings {
                it.copy(advancedSettings = it.advancedSettings.copy(ruRelayEnabled = false))
            }
            // Do not run onCreate: Android/native adapters are the external
            // boundary; lifecycle arbitration and command handling remain real.
            set("connectionOrchestrator", orchestrator)
            set("connectionPreparer", preparer)
            set("backgroundScope", scope)
            set("delayedTaskScheduler", scheduler)
            set("watchdogController", VpnWatchdogController(
                scheduler = scheduler,
                nowMillis = { 0L },
                launchProbe = { _, _ -> null },
                isLockdown = { false },
                evidenceFreshMillis = 1L,
            ))
            set("warmupController", VpnWarmupController<BackendVpnSession>(scheduler, 1L))
            set("statsCoordinator", VpnStatsCoordinator(SettingsRepository(service), scope, scheduler, { null }, { _, _ -> }))
            set("underlyingNetworkSource", AndroidUnderlyingNetworkSource(service))
            set("notificationFactory", VpnNotificationFactory(service, 1001))
            if (realSidecars) {
                set("connectedAtMillis", System.currentTimeMillis())
                set("notificationController", VpnNotificationController(
                    scheduler, 3_600_000L, 3_600_000L, {}, { null },
                ))
                set("endpointHealthController", VpnEndpointHealthController(
                    scheduler, 3_600_000L, { _, _ -> null },
                ))
            }
            set("settingsStore", store)
            set("networkMonitor", VpnNetworkMonitor(
                source = { AndroidUnderlyingNetworkSource(service).currentObservation() },
                scheduler = scheduler,
                debounceMillis = 1L,
                register = { null },
            ))
            orchestrator.updateState(VpnConnectionState.CONNECTED)
            orchestrator.replaceTunnel(object : TunHandle {
                override val fd = 7
                override fun close() = Unit
            })
        }

        fun startAccount(strategy: VpnPreparationStrategy = VpnPreparationStrategy.FreshOnly) = invoke("startVpn", strategy)

        fun enableKillSwitch() {
            store.updateSettings { it.copy(advancedSettings = it.advancedSettings.copy(killSwitchEnabled = true)) }
            SettingsRepository(service).updateSettings {
                it.copy(advancedSettings = it.advancedSettings.copy(killSwitchEnabled = true))
            }
        }

        fun useOfflinePreparation(
            error: Throwable = java.io.IOException("network lost"),
            onPrepare: () -> Unit = {},
        ) {
            store.updateSettings { it.copy(backendAccessToken = "test-token") }
            set("connectionPreparer", VpnConnectionPreparer(
                store = store,
                currentNetworkKind = { EndpointRankingPolicy.NetworkKind.OTHER },
                resolveStart = { _, _, _, _ ->
                    onPrepare()
                    VpnStartCoordinator.StartDecision.Failure(error)
                },
                refreshAccessToken = { error("unexpected token refresh") },
                retryCount = 0,
            ))
        }

        fun useTemporaryPreparation() {
            val client = OkHttpClient.Builder().addInterceptor { error("unexpected temporary API call") }.build()
            set("temporaryVpnCoordinator", TemporaryVpnSessionCoordinator(
                store = SettingsRepository(service),
                api = BackendApiClient(client, "https://api.example"),
                publicKeyProvider = { "test-public-key" },
                deviceKeyProvider = { "test-device-key" },
                deviceNameProvider = { "test-device" },
                platformProvider = { "android" },
                challengeSigner = { "test-signature" },
                profileSelector = { error("unexpected temporary profile") },
            ))
        }

        fun useRuSession(session: BackendRuWireGuardSession.Ready, response: (okhttp3.Request) -> Pair<Int, String>) {
            val settings = SettingsRepository(service).updateSettings {
                it.copy(isAuthenticated = true, backendAccessToken = "lease-access",
                    backendDeviceId = "66666666-6666-4666-8666-666666666666", backendDeviceKey = "dev_" + "1".repeat(64),
                    userProfile = it.userProfile.copy(backendUserId = "account"),
                    advancedSettings = it.advancedSettings.copy(ruRelayEnabled = true))
            }
            store.updateSettings { settings }
            orchestrator.updateSettings(settings)
            set("ruSession", session)
            runtimeRunning = true
            workingEndpoint = "ru"
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val (status, body) = response(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            set("backendApi", BackendApiClient(client, "https://api.example"))
        }

        fun ruSession(): BackendRuWireGuardSession.Ready? = AppVpnService::class.java.getDeclaredField("ruSession")
            .apply { isAccessible = true }.get(service) as? BackendRuWireGuardSession.Ready

        suspend fun checkRuStatus() = suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val method = AppVpnService::class.java.declaredMethods.single { it.name == "checkRuAuthorization" }
                .apply { isAccessible = true }
            val result = method.invoke(service, true, continuation)
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
        }

        fun prepareFailure(error: Throwable, destructive: Boolean = true) {
            AppVpnService::class.java.getDeclaredMethod(
                "handlePrepareFailure", SettingsRepository::class.java, String::class.java,
                Throwable::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(
                service, SettingsRepository(service), "profile_prepare_error", error, destructive,
            )
        }

        fun recoveryAttempt(): Int = AppVpnService::class.java.getDeclaredField("transientRecoveryAttempt")
            .apply { isAccessible = true }.getInt(service)

        fun pendingNetworkRecovery(): Any? = AppVpnService::class.java.getDeclaredField("pendingNetworkRecovery")
            .apply { isAccessible = true }.get(service)

        fun clearStoredAuth() {
            store.updateSettings { it.copy(isAuthenticated = false, backendAccessToken = null, backendRefreshToken = null) }
        }

        fun setNetworkAvailability(availability: UnderlyingNetworkAvailability) {
            val manager = service.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val shadow = shadowOf(manager)
            shadow.clearAllNetworks()
            if (availability == UnderlyingNetworkAvailability.None) return
            val network = org.robolectric.shadows.ShadowNetwork.newInstance(101)
            val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI,
                0, true, true,
            )
            val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).apply {
                addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                if (availability == UnderlyingNetworkAvailability.Validated) {
                    addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }
            shadow.addNetwork(network, info)
            shadow.setNetworkCapabilities(network, capabilities)
        }

        fun observeNetwork(availability: UnderlyingNetworkAvailability) {
            setNetworkAvailability(availability)
            invoke("handleUnderlyingNetworkObservation", AndroidUnderlyingNetworkSource(service).currentObservation())
        }

        fun savedSettings(): StoredSettings = store.load()

        suspend fun awaitWakeProbe() {
            val inFlight = AppVpnService::class.java.getDeclaredField("wakeProbeInFlight")
                .apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicBoolean
            kotlinx.coroutines.withTimeout(5_000L) { while (inFlight.get()) yield() }
        }

        fun saveSettings(settings: StoredSettings) {
            store.updateSettings { settings }
            SettingsRepository(service).updateSettings { settings }
            orchestrator.updateSettings(settings)
        }

        suspend fun measureReadiness(fastStartup: Boolean = false): Long? = suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                AppVpnService::class.java.declaredMethods.single { it.name == "measureRuntimeReadiness" }
                    .apply { isAccessible = true }.invoke(service, false, fastStartup, false, continuation)
            } catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
        }

        fun launchWatchdogProbe(owner: ConnectedWatchdogPolicy.Owner, settings: StoredSettings): CancelableTask? =
            AppVpnService::class.java.getDeclaredMethod("launchWatchdogProbe",
                ConnectedWatchdogPolicy.Owner::class.java, StoredSettings::class.java)
                .apply { isAccessible = true }.invoke(service, owner, settings) as? CancelableTask

        suspend fun finishConnectedSettings(settings: StoredSettings) = orchestrator.withLifecycleLock {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val result = AppVpnService::class.java.declaredMethods.single { it.name == "finishTunnelConnected" }
                    .apply { isAccessible = true }.invoke(service, SettingsRepository(service), settings, settings,
                        orchestrator.currentGenerationId(), false, 30L, false,
                        ConnectedActivationKind.EXISTING_TUN, VpnRuntimeMode.ACCOUNT,
                        orchestrator.currentUnderlay(), continuation)
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
        }

        fun enableRuAfterPrepare() {
            store.updateSettings { current -> current.copy(
                advancedSettings = current.advancedSettings.copy(ruRelayEnabled = true),
                ruRelaySelectionRevision = current.ruRelaySelectionRevision + 1L,
            ) }
        }

        suspend fun replaceConnected(rollbackOnFailure: Boolean = false,
                                     canActivate: () -> Boolean = { true }): Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
            val settings = checkNotNull(orchestrator.currentSettings())
            try {
                AppVpnService::class.java.declaredMethods.single { it.name == "replaceCoreOnExistingTunnel" }
                    .apply { isAccessible = true }.invoke(
                        service, SettingsRepository(service), settings, settings, settings,
                        EndpointRankingPolicy.NetworkKind.CELLULAR, orchestrator.currentTunnel(),
                        orchestrator.beginTransition(), null, false, rollbackOnFailure, null, canActivate, continuation,
                    )
            } catch (error: java.lang.reflect.InvocationTargetException) {
                throw error.targetException
            }
        }

        suspend fun startPrepared(
            mode: EndpointSelectionMode = EndpointSelectionMode.AUTO,
            failedClosed: Boolean = false,
            nodeId: String? = null,
        ) {
            val candidates = (1..4).map { tcpCandidate("tcp$it").copy(
                transport = "xhttp", transportMode = "stream-up", path = "/noki", flow = null, nodeId = nodeId,
            ) } +
                hysteriaCandidate("hy2").copy(path = null, nodeId = nodeId)
            val session = BackendVpnSession(
                canConnect = true, profileCode = "auto", locationCode = "lv1", locationName = "Latvia",
                endpointCode = "tcp1", entryHost = "tcp1.example.com", entryPort = 443,
                serverName = "www.lu.lv", proxyType = "vless", transport = "xhttp", transportMode = "stream-up",
                security = "reality", fingerprint = "chrome", requestHost = null, path = "/noki", alpn = null,
                allowInsecure = false, enableMux = false, randomUserAgent = false,
                publicKey = "public", shortId = "short", vpnUsername = "test",
                vpnSecret = "e912e725-2dc7-4b44-85d2-7fbc31a48b5e", flow = null,
                planCode = null, endpointCandidates = candidates,
            )
            val baseline = store.load()
            val profile = EndpointSelector.profileFromCandidate(session, candidates.first())
            val settings = baseline.copy(
                profile = profile,
                userProfile = baseline.userProfile.copy(selectedServerCode = "lv1"),
                endpointOptions = EndpointSelector.optionsFromSession(session),
                advancedSettings = baseline.advancedSettings.copy(endpointSelectionMode = mode),
            )
            val prepared = PreparedVpnSession(
                baseline, settings, EndpointRankingPolicy.NetworkKind.CELLULAR,
                VpnStartCoordinator.Result(
                    settings, session,
                    BackendDevice(id = "device", deviceKey = "key", deviceName = "Phone",
                        platform = "android", accessRole = "owner", isActive = true, lastSeenAt = null),
                    EndpointSelector.EndpointSelectionResult(profile, profile.endpointCode, ""), emptyList(),
                ),
            )
            if (!failedClosed) orchestrator.replaceTunnel(null)
            orchestrator.updateState(if (failedClosed) VpnConnectionState.FAILED else VpnConnectionState.CONNECTING)
            val generation = orchestrator.beginTransition()
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = AppVpnService::class.java.declaredMethods.single {
                    it.name == if (failedClosed) "startCoreOnFailedClosedTunnel" else "startTunnel"
                }.apply { isAccessible = true }
                val result = try {
                    if (failedClosed) method.invoke(service, SettingsRepository(service), settings, baseline,
                        orchestrator.currentTunnel(), generation, prepared, continuation)
                    else method.invoke(service, SettingsRepository(service), settings, baseline,
                        prepared, generation, VpnRuntimeMode.ACCOUNT, true, continuation)
                } catch (error: java.lang.reflect.InvocationTargetException) {
                    throw error.targetException
                }
                if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else Unit
            }
        }
        fun stop(startId: Int) = invoke("stopVpn", startId, false)

        fun pendingStart(): Any? = AppVpnService::class.java.getDeclaredField("pendingStartOptions")
            .apply { isAccessible = true }.get(service)

        fun set(name: String, value: Any) {
            AppVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }

        fun invoke(name: String, vararg args: Any) {
            val types = args.map { when (it) { is Boolean -> Boolean::class.javaPrimitiveType; is Int -> Int::class.javaPrimitiveType; else -> it.javaClass } }
            AppVpnService::class.java.getDeclaredMethod(name, *types.toTypedArray())
                .apply { isAccessible = true }.invoke(service, *args)
        }
    }

    // AndroidKeyStore is unavailable on the host JVM. Keep SettingsRepository
    // and AES/GCM real; replace only the platform key storage boundary.
    class InMemoryAndroidKeyStore : KeyStoreSpi() {
        private val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        override fun engineGetEntry(alias: String?, protection: KeyStore.ProtectionParameter?) = KeyStore.SecretKeyEntry(key)
        override fun engineGetKey(alias: String?, password: CharArray?): Key = key
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
        override fun engineContainsAlias(alias: String?) = alias == "noki_settings_aes"
        override fun engineAliases() = Collections.enumeration(listOf("noki_settings_aes"))
        override fun engineSize() = 1
        override fun engineIsKeyEntry(alias: String?) = engineContainsAlias(alias)
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificateAlias(certificate: Certificate?): String? = null
        override fun engineGetCreationDate(alias: String?) = Date(0)
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<Certificate>?) = error("not needed")
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<Certificate>?) = error("not needed")
        override fun engineSetCertificateEntry(alias: String?, certificate: Certificate?) = error("not needed")
        override fun engineDeleteEntry(alias: String?) = error("not needed")
        override fun engineStore(stream: OutputStream?, password: CharArray?) = error("not needed")
    }
}
