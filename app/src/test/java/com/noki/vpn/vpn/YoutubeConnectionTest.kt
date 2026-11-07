package com.noki.vpn.vpn

import com.noki.vpn.data.*
import java.io.FileDescriptor
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class YoutubeConnectionTest {
    private fun settings(): StoredSettings = DefaultStoredSettingsFactory.create(sdkInt = 35, isFreshInstall = false).let {
        it.copy(isAuthenticated = true, backendDeviceId = "11111111-1111-4111-8111-111111111111",
            backendDeviceKey = "device-key", userProfile = it.userProfile.copy(backendUserId = "account", selectedPlanTier = "plus"),
            advancedSettings = it.advancedSettings.copy(youtubeNoAdsEnabled = true))
    }

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

    @Test fun endpointExistsBeforeChildReadinessAndSurvivesChildFailure() = runBlocking {
        val fixture = Fixture()
        try {
            val endpoint = fixture.connection.configFor(settings())
            assertEquals(30_001, endpoint!!.port)
            assertTrue(fixture.children.isEmpty())
            fixture.connection.start(settings())
            assertEquals(endpoint, fixture.connection.configFor(settings()))
            fixture.children.single().ready.complete(Unit)
            fixture.children.single().exit.complete(3)
            withTimeout(2_000) { while (fixture.children.size < 2) delay(1) }
            assertEquals(endpoint, fixture.connection.configFor(settings()))
            assertEquals(0, fixture.closedEndpoints)
            assertEquals(1, fixture.children.first().stops)
            assertTrue(fixture.failures.any { it.message == "youtube_byedpi_exited_3" })
        } finally { fixture.close() }
    }

    @Test fun handoverClosesTheOldChildBeforeStartingTheNewOneOnTheSamePort() {
        val fixture = Fixture()
        try {
            val endpoint = fixture.connection.configFor(settings())
            fixture.connection.start(settings())
            val wifi = fixture.children.single()
            fixture.connection.start(settings())
            assertEquals(1, fixture.children.size)
            fixture.network = "cellular"
            fixture.connection.start(settings())
            assertTrue(wifi.stops >= 1)
            assertEquals(2, fixture.children.size)
            assertEquals(listOf("wifi", "cellular"), fixture.signatures)
            assertEquals(endpoint, fixture.connection.configFor(settings()))
            assertEquals(1, fixture.maxActive)
        } finally { fixture.close() }
    }

    @Test fun networkLossStopsTheChildAndKeepsItsReservedEndpoint() {
        val fixture = Fixture()
        try {
            val endpoint = fixture.connection.configFor(settings())
            fixture.connection.start(settings())
            val old = fixture.children.single()
            fixture.network = null
            fixture.connection.start(settings())
            assertTrue(old.stops >= 1)
            assertEquals(1, fixture.children.size)
            assertEquals(endpoint, fixture.connection.configFor(settings()))
            fixture.network = "new-wifi"
            fixture.connection.start(settings())
            assertEquals(2, fixture.children.size)
            assertEquals(endpoint, fixture.connection.configFor(settings()))
        } finally { fixture.close() }
    }

    @Test fun disableReleasesEndpointAndStaleReadinessCannotResurrectIt() {
        val fixture = Fixture()
        try {
            fixture.connection.start(settings())
            val stale = fixture.children.single()
            fixture.connection.stop()
            stale.ready.complete(Unit)
            assertTrue(stale.stops >= 1)
            assertEquals(1, fixture.closedEndpoints)
            assertNull(fixture.connection.configFor(settings().copy(isAuthenticated = false)))
            assertEquals(30_002, fixture.connection.configFor(settings())!!.port)
        } finally { fixture.close() }
    }

    @Test fun endpointFailureCannotStartAnUnroutedChildUntilAnExplicitNewSelection() {
        val fixture = Fixture()
        try {
            fixture.endpointFails = true
            assertNull(fixture.connection.configFor(settings()))
            fixture.endpointFails = false
            fixture.connection.start(settings())
            assertTrue(fixture.children.isEmpty())
            val changed = settings().copy(backendDeviceKey = "rotated")
            assertNotNull(fixture.connection.configFor(changed))
        } finally { fixture.close() }
    }

    @Test fun failedStopCannotSpawnASecondProcess() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.connection.start(settings())
            fixture.children.single().stopFails = true
            fixture.network = "cellular"
            fixture.connection.start(settings())
            delay(20)
            assertEquals(1, fixture.children.size)
            assertEquals(1, fixture.maxActive)
        } finally {
            fixture.children.forEach { it.stopFails = false }
            fixture.close()
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

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var network: String? = "wifi"
        var closedEndpoints = 0
        var nextPort = 30_000
        var endpointFails = false
        var active = 0
        var maxActive = 0
        val children = mutableListOf<Child>()
        val signatures = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val connection = YoutubeConnection(scope, { network }, {
            check(!endpointFails) { "endpoint unavailable" }
            YoutubeEndpoint(++nextPort, FileDescriptor()) { closedEndpoints++ }
        }, { _, signature ->
            signatures += signature
            Child {
                active--
            }.also { children += it; active++; maxActive = maxOf(active, maxActive) }
        }, { failures += it }, retryMillis = 5)
        fun close() { connection.stop(); scope.cancel() }
    }

    private class Child(private val onStop: () -> Unit) : YoutubeRuntime {
        val ready = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Int>()
        var stops = 0
        var stopFails = false
        override suspend fun start() { ready.await() }
        override suspend fun awaitExit(): Int = exit.await()
        override fun stop() {
            check(!stopFails) { "stop failed" }
            if (stops++ == 0) onStop()
        }
    }
}
