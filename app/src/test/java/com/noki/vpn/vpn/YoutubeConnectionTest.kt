package com.noki.vpn.vpn

import com.noki.vpn.data.*
import java.security.Provider
import java.security.Security
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YoutubeConnectionTest {
    @Test fun promotedEndpointRevokesOldGrantsAndRecreatesAtMostTwicePerStart() = runBlocking {
        val creates = AtomicInteger()
        val renewals = AtomicInteger()
        val fixture = Fixture(responseCode = { if (it.url.encodedPath.endsWith("/renew")) 409 else 200 }) { request ->
            if (request.url.encodedPath.endsWith("/renew")) {
                renewals.incrementAndGet()
                JSONObject().put("detail", "youtube_wireguard_identity_changed")
            } else {
                val number = creates.incrementAndGet()
                youtubeAccessFixture(expiresAt = System.currentTimeMillis() + 450).put("grant_id",
                    "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
            }
        }
        try {
            fixture.connection.start(settings())
            waitUntil { creates.get() == 3 && renewals.get() == 3 }
            delay(500)
            assertEquals(3, creates.get())
            assertNull(fixture.connection.configFor(settings()))
            val requests = fixture.requests.toList()
            assertEquals(2, requests.count { it.method == "DELETE" })
            val admissions = requests.filter { it.url.encodedPath == "/v1/vpn/youtube-access" }
            assertEquals(3, admissions.map { request -> JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("request_id") }.toSet().size)
            for (index in 1..2) {
                val revoke = requests.indexOfFirst { it.method == "DELETE" && it.url.encodedPath.endsWith(index.toString().padStart(12, '0')) }
                assertTrue(revoke in 0 until requests.indexOf(admissions[index]))
            }
        } finally { fixture.close() }
    }
    @Test fun newGrantDiscoversAllowedNodesOnceAndRenewKeepsItsSelection() = runBlocking {
        val renewals = AtomicInteger()
        val node = "55555555-5555-4555-8555-555555555555"
        val invalid = "66666666-6666-4666-8666-666666666666"
        val catalog = JSONObject().put("nodes", org.json.JSONArray().put(JSONObject()
            .put("id", node).put("name", "YT").put("country_code", "NL").put("selection_weight", 35)
            .put("probe_ip", "203.0.113.8").put("probe_port", JSONObject.NULL)).put(JSONObject()
            .put("id", invalid).put("name", "YT2").put("country_code", "DE")))
        val fixture = Fixture(nodes = catalog, measure = { nodes ->
            assertEquals(listOf(node, invalid), nodes.map { it.id })
            assertNull(nodes.first().probePort)
            assertEquals("203.0.113.8", nodes.first().probeIp)
            mapOf(node to 24, invalid to 3001, "not-authorized" to 5)
        }) { request ->
            if (request.url.encodedPath.endsWith("/renew")) renewals.incrementAndGet()
            youtubeAccessFixture(expiresAt = System.currentTimeMillis() + if (renewals.get() == 0) 900 else 120_000)
        }
        try {
            fixture.connection.start(settings())
            waitUntil { fixture.connection.configFor(settings()) != null }
            waitUntil { renewals.get() > 0 }
            val discovery = fixture.requests.filter { it.url.encodedPath == "/v1/vpn/youtube-nodes" }
            assertEquals("Only a new grant performs selection discovery", 1, discovery.size)
            assertEquals(settings().backendDeviceId, discovery.single().url.queryParameter("device_id"))
            assertEquals("device-key", discovery.single().header("X-Device-Key"))
            assertNull(discovery.single().header("X-Youtube-Transport"))
            val create = fixture.requests.single { it.url.encodedPath == "/v1/vpn/youtube-access" }
            val samples = JSONObject(okio.Buffer().also { create.body!!.writeTo(it) }.readUtf8()).getJSONArray("node_rtt_samples")
            assertEquals(1, samples.length())
            assertEquals(node, samples.getJSONObject(0).getString("node_id"))
            assertEquals(24, samples.getJSONObject(0).getInt("rtt_ms"))
            fixture.requests.filter { it.url.encodedPath.endsWith("/renew") }.forEach { request ->
                assertFalse(JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()).has("node_rtt_samples"))
            }
        } finally { fixture.close() }
    }

    @Test fun oldServerWithoutDiscoveryStillCreatesTheExistingWireGuardAdmission() = runBlocking {
        val fixture = Fixture(catalogStatus = 404) { youtubeAccessFixture() }
        try {
            fixture.connection.start(settings())
            waitUntil { fixture.connection.configFor(settings()) != null }
            assertEquals(1, fixture.requests.count { it.url.encodedPath == "/v1/vpn/youtube-nodes" })
            val create = fixture.requests.single { it.url.encodedPath == "/v1/vpn/youtube-access" }
            assertFalse(JSONObject(okio.Buffer().also { create.body!!.writeTo(it) }.readUtf8()).has("node_rtt_samples"))
        } finally { fixture.close() }
    }

    private fun settings(): StoredSettings = DefaultStoredSettingsFactory.create(sdkInt = 35, isFreshInstall = false).let {
        it.copy(isAuthenticated = true, backendAccessToken = "access", backendDeviceId = "11111111-1111-4111-8111-111111111111",
            backendDeviceKey = "device-key", userProfile = it.userProfile.copy(backendUserId = "account", selectedPlanTier = "plus"),
            advancedSettings = it.advancedSettings.copy(youtubeNoAdsEnabled = true))
    }

    @Before fun installKeyStore() {
        Security.addProvider(object : Provider("YoutubeAdmissionTest", 1.0, "Test-only Android keystore") {
            init { put("KeyStore.AndroidKeyStore", AppVpnServiceCommandTest.InMemoryAndroidKeyStore::class.java.name) }
        })
        WireGuardKeyStore(
            java.io.File(RuntimeEnvironment.getApplication().noBackupFilesDir, "ru-wireguard-device-key-v1.bin"),
            { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 1 }, "AES") }, {},
            { WireGuardKeyPair(ByteArray(32) { 8 }.apply { this[31] = 72 }, ByteArray(32) { 7 }) },
        ).getOrCreate().privateKey.fill(0)
    }
    @After fun removeKeyStore() { Security.removeProvider("YoutubeAdmissionTest") }

    @Test fun availabilityPreservesTheExistingPaidAccountAndToggleContract() {
        val current = settings()
        val target = YoutubeConnection.targetFor(current)
        assertNotNull(target)
        assertNull(YoutubeConnection.targetFor(current.copy(isAuthenticated = false)))
        assertNull(YoutubeConnection.targetFor(current.copy(backendDeviceId = "")))
        assertNull(YoutubeConnection.targetFor(current.copy(advancedSettings = current.advancedSettings.copy(youtubeNoAdsEnabled = false))))
        assertNull(YoutubeConnection.targetFor(current.copy(userProfile = current.userProfile.copy(selectedPlanTier = "free"))))
        for ((kind, transport) in listOf("nokinv" to "nokinv", "naive" to "naive", "hysteria" to "hysteria",
                "vless" to "tcp", "vless" to "xhttp")) {
            assertEquals(target, YoutubeConnection.targetFor(current.copy(
                profile = current.profile.copy(proxyType = kind, transport = transport, endpointCode = "other", uuid = "rotated"),
                advancedSettings = current.advancedSettings.copy(ruRelayEnabled = true, multiHop = MultiHopSettings(enabled = true)))))
        }
        assertNotEquals(target, YoutubeConnection.targetFor(current.copy(backendDeviceKey = "new-key")))
    }


    @Test fun readyUsesTheSharedRegisteredWireGuardKeyAndIdenticalRenewDoesNotRepublish() = runBlocking {
        val renewals = AtomicInteger()
        val fixture = Fixture { request ->
            if (request.url.encodedPath.endsWith("/renew")) renewals.incrementAndGet()
            youtubeAccessFixture(expiresAt = System.currentTimeMillis() + if (renewals.get() == 0) 900 else 120_000)
        }
        try {
            fixture.connection.start(settings())
            waitUntil { fixture.connection.configFor(settings()) != null }
            val config = fixture.connection.configFor(settings())!!
            assertEquals("203.0.113.5:51820", config.wireguard.endpoint)
            assertTrue(config.privateKey.any { it != 0.toByte() })
            val registration = fixture.requests.single { it.url.encodedPath == "/v1/vpn/wireguard-keys" }
            val registered = JSONObject(okio.Buffer().also { registration.body!!.writeTo(it) }.readUtf8())
            assertEquals(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }), registered.getString("public_key"))
            assertFalse(registered.has("private_key"))
            assertEquals("signature", registered.getString("device_signature"))
            val admitted = JSONObject(okio.Buffer().also {
                fixture.requests.single { it.url.encodedPath == "/v1/vpn/youtube-access" }.body!!.writeTo(it)
            }.readUtf8())
            assertNotEquals(registered.getString("device_nonce"), admitted.getString("device_nonce"))
            assertEquals("33333333-3333-4333-8333-333333333333", admitted.getString("public_key_id"))
            waitUntil { renewals.get() > 0 }
            delay(50)
            assertEquals(config, fixture.connection.configFor(settings()))
            assertEquals(1, fixture.changes.get())
            fixture.connection.start(settings().copy(profile = settings().profile.copy(endpointCode = "other", transport = "xhttp")))
            assertEquals(1, fixture.requests.count { it.url.encodedPath == "/v1/vpn/youtube-access" })
            fixture.connection.stop()
            waitUntil { fixture.requests.any { it.method == "DELETE" } }
            assertNull(fixture.connection.configFor(settings()))
            assertTrue(config.privateKey.all { it == 0.toByte() })
        } finally { fixture.close() }
    }

    @Test fun transientRenewFailureExpiresOnlyTheYoutubeConfiguration() = runBlocking {
        val fixture = Fixture { request ->
            if (request.url.encodedPath.endsWith("/renew")) throw java.io.IOException("offline")
            youtubeAccessFixture(expiresAt = System.currentTimeMillis() + 450)
        }
        try {
            fixture.connection.start(settings())
            waitUntil { fixture.connection.configFor(settings()) != null }
            waitUntil { fixture.changes.get() == 2 }
            assertNull(fixture.connection.configFor(settings()))
        } finally { fixture.close() }
    }

    @Test fun expiredCoroutineWaitingForTheMonitorCannotClearARenewedLease() = runBlocking {
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "youtube-expiry-race").apply { isDaemon = true; worker.set(this) }
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val changes = AtomicInteger()
        val connection = YoutubeConnection(context, scope, SettingsRepository(context),
            { error("unexpected admission") }, { changes.incrementAndGet() })
        val grant = BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture())
        val config = YoutubeConnection.runtimeConfig(grant, checkNotNull(grant.publicKeyId), ByteArray(32) { 8 })
        val publish = YoutubeConnection::class.java.getDeclaredMethod("publish", Long::class.javaPrimitiveType,
            YoutubeConnectionConfig::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        val expiry = YoutubeConnection::class.java.getDeclaredField("expiry").apply { isAccessible = true }
        YoutubeConnection::class.java.getDeclaredField("target").apply { isAccessible = true }
            .set(connection, YoutubeConnection.targetFor(settings()))
        try {
            val oldExpiry = synchronized(connection) {
                publish.invoke(connection, 0L, config, System.currentTimeMillis() + 100)
                val previous = expiry.get(connection) as Job
                val timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.get()?.state != Thread.State.BLOCKED && System.nanoTime() < timeout) Thread.sleep(1)
                assertEquals("expiry must have left delay and be waiting for the connection monitor",
                    Thread.State.BLOCKED, worker.get()?.state)
                publish.invoke(connection, 0L, config, grant.expiresAtEpochMillis)
                previous
            }
            withTimeout(5_000) { oldExpiry.join() }
            assertEquals(config, connection.configFor(settings()))
            assertEquals(1, changes.get())
        } finally { connection.stop(); scope.cancel(); dispatcher.close() }
    }

    @Test fun renewCannotReplaceTheRegisteredWireGuardKeyIdentity() = runBlocking {
        val fixture = Fixture { request -> youtubeAccessFixture(expiresAt = System.currentTimeMillis() + 900).apply {
            if (request.url.encodedPath.endsWith("/renew")) put("public_key_id", "44444444-4444-4444-8444-444444444444")
        } }
        try {
            fixture.connection.start(settings())
            waitUntil { fixture.connection.configFor(settings()) != null }
            waitUntil { fixture.changes.get() == 2 }
            assertNull(fixture.connection.configFor(settings()))
        } finally { fixture.close() }
    }

    @Test fun readyForAnotherRegisteredKeyIsNeverPublished() = runBlocking {
        val fixture = Fixture { youtubeAccessFixture().put("public_key_id", "44444444-4444-4444-8444-444444444444") }
        try {
            fixture.connection.start(settings())
            waitUntil { fixture.requests.any { it.url.encodedPath == "/v1/vpn/youtube-access" } }
            delay(50)
            assertNull(fixture.connection.configFor(settings()))
            assertEquals(0, fixture.changes.get())
        } finally { fixture.close() }
    }

    @Test fun pendingPollAndBlockedAdmissionNeverPublishAnUnreadyProfile() = runBlocking {
        val fixture = Fixture { request -> youtubeAccessFixture(if (request.method == "GET") "blocked" else "pending") }
        try {
            fixture.connection.start(settings())
            waitUntil { fixture.requests.any { it.method == "GET" && it.url.encodedPath.startsWith("/v1/vpn/youtube-access/") } }
            assertNull(fixture.connection.configFor(settings()))
            assertEquals(0, fixture.changes.get())
            fixture.connection.stop()
            waitUntil { fixture.requests.any { it.method == "DELETE" } }
        } finally { fixture.close() }
    }

    @Test fun lateCreateResponseCannotResurrectACancelledAdmission() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            youtubeAccessFixture()
        }
        try {
            fixture.connection.start(settings())
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.connection.stop()
            release.countDown()
            delay(100)
            assertNull(fixture.connection.configFor(settings()))
            assertEquals(0, fixture.changes.get())
        } finally { release.countDown(); fixture.close() }
    }

    @Test fun renewalAndRetryTimingAreBoundedByTheGrantTtl() {
        assertEquals(80_000L, YoutubeConnection.renewDelayMillis(120_000, 0))
        assertEquals(1L, YoutubeConnection.renewDelayMillis(1, 2))
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..5).map(YoutubeConnection::transientRetryDelayMillis))
        val blocked = BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture("blocked", 0))
        assertTrue(YoutubeConnection.needsFreshAdmission(blocked, 1))
        assertFalse(YoutubeConnection.needsFreshAdmission(blocked.copy(expiresAtEpochMillis = 2), 1))
    }

    @Test fun equivalentWireGuardMaterialDoesNotRepublishBecauseTheKeyArrayWasReloaded() {
        val grant = BackendYoutubeAccessJsonParser.parse(youtubeAccessFixture())
        val first = YoutubeConnection.runtimeConfig(grant, checkNotNull(grant.publicKeyId), ByteArray(32) { 8 })
        val renewed = YoutubeConnection.runtimeConfig(grant.copy(generation = 2), checkNotNull(grant.publicKeyId), ByteArray(32) { 8 })
        assertTrue(YoutubeConnection.sameConfiguration(first, renewed))
        assertFalse(YoutubeConnection.sameConfiguration(first, null))
        assertFalse(YoutubeConnection.sameConfiguration(first, renewed.copy(wireguard = renewed.wireguard.copy(mtu = 1400))))
        assertThrows(IllegalArgumentException::class.java) {
            YoutubeConnection.runtimeConfig(grant, "44444444-4444-4444-8444-444444444444", first.privateKey)
        }
    }

    @Test fun challengeTransactionUnlocksAfterFailureAndCancellation() = runBlocking {
        assertTrue(runCatching { DeviceIdentity.withChallengeTransaction { error("consume failed") } }.isFailure)
        val entered = CompletableDeferred<Unit>()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            DeviceIdentity.withChallengeTransaction { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        cancelled.cancelAndJoin()
        assertEquals("released", withTimeout(1_000) { DeviceIdentity.withChallengeTransaction { "released" } })
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(5)
    }

    private inner class Fixture(
        nodes: JSONObject = JSONObject().put("nodes", org.json.JSONArray()),
        catalogStatus: Int = 200,
        measure: suspend (List<VpnServer>) -> Map<String, Int> = { emptyMap() },
        responseCode: (Request) -> Int = { 200 },
        response: (Request) -> JSONObject,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = CopyOnWriteArrayList<Request>()
        val changes = AtomicInteger()
        private val nonces = AtomicInteger()
        private val context = RuntimeEnvironment.getApplication()
        private val repository = SettingsRepository(context).also { repo -> repo.updateSettings { settings() } }
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val payload = when {
                request.url.encodedPath == "/v1/vpn/youtube-nodes" -> nodes
                request.url.encodedPath == "/v1/devices/challenge" -> JSONObject().put("device_id", settings().backendDeviceId)
                    .put("nonce", "nonce-${nonces.incrementAndGet()}")
                request.url.encodedPath == "/v1/vpn/wireguard-keys" -> JSONObject()
                    .put("id", "33333333-3333-4333-8333-333333333333")
                    .put("public_key", JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()).getString("public_key"))
                request.method == "DELETE" -> JSONObject()
                else -> response(request)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.encodedPath == "/v1/vpn/youtube-nodes") catalogStatus else responseCode(request)).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val connection = YoutubeConnection(context, scope, repository,
            { BackendApiClient(client, "https://api.example", challengeSigner = { "signature" }) }, { changes.incrementAndGet() }, measure)
        fun close() { connection.stop(); scope.cancel() }
    }
}
