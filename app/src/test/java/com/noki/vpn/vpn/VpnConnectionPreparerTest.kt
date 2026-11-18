package com.noki.vpn.vpn

import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnSessionSelection
import com.noki.vpn.data.VpnStartCoordinator
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnConnectionPreparerTest {
    @Test fun `new automatic or country start refreshes an otherwise usable persisted node`() = runBlocking {
        for (mode in listOf(com.noki.vpn.data.ServerSelectionMode.AUTO, com.noki.vpn.data.ServerSelectionMode.COUNTRY)) {
            val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true,
                userProfile = cachedSettings().userProfile.copy(serverSelectionMode = mode))
            var requests = 0
            val preparer = VpnConnectionPreparer(PreparerStore(settings), { EndpointRankingPolicy.NetworkKind.WIFI },
                resolveStart = { _, _, _, _ -> requests++; VpnStartCoordinator.StartDecision.Failure(BackendException("fresh_selection", 400)) },
                refreshAccessToken = { error("No auth failure") })
            val result = preparer.prepare(VpnPreparationStrategy.CachedFirst)
            assertTrue("$mode must reconsider the persisted node on a new start", result is VpnConnectionPreparer.Outcome.Failure)
            assertEquals(1, requests)
        }
    }

    @Test fun `prepared grants are single use and bound to TTL physical network account and settings`() = runBlocking {
        val settings = cachedSettings().copy(isAuthenticated = true, backendAccessToken = "prepared-test")
        val session = com.noki.vpn.data.BackendVpnSession(
            true, "auto", "lv", "Latvia", "lv-1", "vpn.example", 443, "vpn.example", "vless", "xhttp", "stream-up",
            "reality", null, null, "/noki", null, false, false, false, "public", "abcd", "test",
            settings.profile.uuid, null, null, emptyList())
        val device = com.noki.vpn.data.BackendDevice(id = "device", deviceKey = "key", deviceName = "Phone",
            platform = "android", accessRole = "owner", isActive = true, lastSeenAt = null)
        var calls = 0
        var clock = 1000L
        var network = "wifi/prepared-test"
        val coordinator = VpnStartCoordinator(com.noki.vpn.data.VpnStartCoordinator.VpnSessionPreparer { _, input, _, _ ->
            calls++
            com.noki.vpn.data.VpnSessionCoordinator.Result(input, session, device,
                com.noki.vpn.data.EndpointSelector.EndpointSelectionResult(input.profile, "lv-1", ""))
        }, networkSignature = { network }, nowMillis = { clock })
        suspend fun prepare() = coordinator.prepareInAdvance("prepared-test", settings, emptyList())
        suspend fun connect(input: StoredSettings = settings, token: String = "prepared-test") = coordinator.resolveStart(token, input, emptyList(), false)
        prepare(); prepare(); assertEquals(1, calls)
        val refreshedUi = settings.copy(
            userProfile = settings.userProfile.copy(trafficUsedGb = 12.0, sharedTrafficUsedGb = 1.0,
                trialTrafficRemainingGb = 2.0, avatarUri = "https://example.invalid/avatar"),
            personalizationSettings = settings.personalizationSettings.copy(vpnStatusNotificationEnabled = false),
        )
        val ready = connect(refreshedUi) as VpnStartCoordinator.StartDecision.FreshSession
        assertEquals(refreshedUi.userProfile.trafficUsedGb, ready.result.settings.userProfile.trafficUsedGb)
        assertEquals(refreshedUi.personalizationSettings, ready.result.settings.personalizationSettings)
        assertEquals(1, calls)
        connect(); assertEquals(2, calls)
        prepare(); clock += 60_001; connect(); assertEquals(4, calls)
        prepare(); network = "mobile/prepared-test"; connect(); assertEquals(6, calls)
        prepare(); connect(token = "another-account-token"); assertEquals(8, calls)
        prepare(); connect(settings.copy(advancedSettings = settings.advancedSettings.copy(killSwitchEnabled = true)))
        assertEquals(9, calls)
    }

    @Test fun `connect awaits matching in flight preparation once instead of restarting the challenge`() = runBlocking {
        val settings = cachedSettings().copy(isAuthenticated = true, backendAccessToken = "in-flight-test")
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = VpnStartCoordinator(VpnStartCoordinator.VpnSessionPreparer { _, input, _, _ ->
            calls++
            release.await()
            val session = com.noki.vpn.data.BackendVpnSession(
                true, "auto", "lv", "Latvia", "lv-1", "vpn.example", 443, "vpn.example", "vless", "xhttp", "stream-up",
                "reality", null, null, "/noki", null, false, false, false, "public", "abcd", "test",
                input.profile.uuid, null, null, emptyList())
            val device = com.noki.vpn.data.BackendDevice(id = "device", deviceKey = "key", deviceName = "Phone",
                platform = "android", accessRole = "owner", isActive = true, lastSeenAt = null)
            com.noki.vpn.data.VpnSessionCoordinator.Result(input, session, device,
                com.noki.vpn.data.EndpointSelector.EndpointSelectionResult(input.profile, "lv-1", ""))
        }, networkSignature = { "wifi/in-flight" })
        val warmup = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.prepareInAdvance("in-flight-test", settings, emptyList())
        }
        val cancelledConnect = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.resolveStart("in-flight-test", settings, emptyList(), false)
        }
        cancelledConnect.cancelAndJoin()
        assertTrue(warmup.isActive)
        val connect = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.resolveStart("in-flight-test", settings, emptyList(), false)
        }
        release.complete(Unit)
        warmup.await()
        assertTrue(connect.await() is VpnStartCoordinator.StartDecision.FreshSession)
        assertEquals(1, calls)
    }
    @Test
    fun `required client update never retries or starts the usable cached profile`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        assertTrue(RuntimeProfilePolicy.isCachedProfileUsable(settings))
        val rejection = BackendException("client_update_required", 426)
        var prepares = 0
        val coordinator = VpnStartCoordinator(VpnStartCoordinator.VpnSessionPreparer { _, _, _, _ ->
            prepares++
            throw rejection
        })
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { token, baseline, allowCachedFallback, selection ->
                coordinator.resolveStart(token, baseline, emptyList(), allowCachedFallback, selection)
            },
            refreshAccessToken = { error("required update must not refresh auth") },
            sleep = { error("required update must not retry") },
            retryCount = 2,
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.FreshWithCachedFallback,
            onCachedFallback = { error("required update must not start cached profile") },
        ) as VpnConnectionPreparer.Outcome.Failure

        assertEquals(rejection, result.error)
        assertEquals(1, prepares)
    }

    @Test
    fun `RU mode never requests a fresh Xray session`() = runBlocking {
        val baseline = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        val ru = baseline.copy(advancedSettings = baseline.advancedSettings.copy(ruRelayEnabled = true))
        var invoked = false
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(ru),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ -> invoked = true; error("Xray session must not be requested") },
            refreshAccessToken = { error("refresh must not run") },
        )

        val result = preparer.prepare(VpnPreparationStrategy.FreshOnly)

        assertTrue(result is VpnConnectionPreparer.Outcome.Failure)
        assertEquals(false, invoked)
    }

    @Test
    fun `RU mode never returns cached Xray and fails closed before WG runtime exists`() = runBlocking {
        val baseline = cachedSettings().copy(backendAccessToken = "token")
        val ru = baseline.copy(advancedSettings = baseline.advancedSettings.copy(ruRelayEnabled = true))
        var invoked = false
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(ru),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ -> invoked = true; error("Xray session must not be requested") },
            refreshAccessToken = { error("refresh must not run") },
        )

        val result = preparer.prepare(VpnPreparationStrategy.CachedFirst)
        assertTrue(result is VpnConnectionPreparer.Outcome.Failure)
        assertEquals(false, invoked)
    }
    @Test
    fun `blocking backend call is bounded by the total preparation deadline`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        var resolveCount = 0
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                resolveCount += 1
                awaitCancellation()
            },
            refreshAccessToken = { error("refresh must not run") },
            retryCount = 2,
            deadlineMillis = 100L,
        )

        val result = withTimeout(1_000L) {
            preparer.prepare(
                strategy = VpnPreparationStrategy.FreshOnly,
            )
        }
        assertTrue(result is VpnConnectionPreparer.Outcome.Failure)
        assertTrue((result as VpnConnectionPreparer.Outcome.Failure).error is SocketTimeoutException)
        assertEquals(1, resolveCount)
    }

    @Test
    fun `cached prepare returns immutable metadata without mutating active owner state`() = runBlocking {
        val settings = cachedSettings()
        val store = PreparerStore(settings)
        var activeNetworkKind = EndpointRankingPolicy.NetworkKind.CELLULAR
        var activeWarmupMarker: String? = "existing-warmup"
        val preparer = VpnConnectionPreparer(
            store = store,
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ -> error("fresh preparation must not run") },
            refreshAccessToken = { error("refresh must not run") },
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.CachedFirst,
        ) as VpnConnectionPreparer.Outcome.Success

        assertEquals(settings, result.session.preparationBaseline)
        assertEquals(settings, result.session.candidateSettings)
        assertEquals(EndpointRankingPolicy.NetworkKind.WIFI, result.session.selectedNetworkKind)
        assertNull(result.session.pendingWarmupSession)
        assertEquals(EndpointRankingPolicy.NetworkKind.CELLULAR, activeNetworkKind)
        assertEquals("existing-warmup", activeWarmupMarker)
    }

    @Test
    fun `401 refresh retries with reloaded token without applying metadata`() = runBlocking {
        val initial = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val refreshed = initial.copy(backendAccessToken = "new-token")
        val store = PreparerStore(initial)
        val observedTokens = mutableListOf<String>()
        val preparer = VpnConnectionPreparer(
            store = store,
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { token, _, _, _ ->
                observedTokens += token
                if (token == "old-token") {
                    VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
                } else {
                    VpnStartCoordinator.StartDecision.CachedFallback(BackendException("offline", 503))
                }
            },
            refreshAccessToken = {
                store.replace(refreshed)
                "new-token"
            },
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.FreshWithCachedFallback,
        ) as VpnConnectionPreparer.Outcome.Success

        assertEquals(listOf("old-token", "new-token"), observedTokens)
        assertEquals(refreshed, result.session.preparationBaseline)
        assertEquals(EndpointRankingPolicy.NetworkKind.WIFI, result.session.selectedNetworkKind)
        assertNull(result.session.pendingWarmupSession)
    }

    @Test
    fun `successful nullable refresh remains distinct from preparation timeout`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
            },
            refreshAccessToken = { null },
            deadlineMillis = 1_000L,
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
        ) as VpnConnectionPreparer.Outcome.Failure

        assertTrue(result.error is BackendException)
        assertEquals(401, (result.error as BackendException).statusCode)
    }

    @Test
    fun `refresh shares the total preparation deadline`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
            },
            refreshAccessToken = { awaitCancellation() },
            deadlineMillis = 100L,
        )

        val result = withTimeout(1_000L) {
            preparer.prepare(
                strategy = VpnPreparationStrategy.FreshOnly,
            )
        } as VpnConnectionPreparer.Outcome.Failure

        assertTrue(result.error is SocketTimeoutException)
    }

    @Test(expected = CancellationException::class)
    fun `parent cancellation during refresh propagates`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "old-token", isAuthenticated = true)
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                VpnStartCoordinator.StartDecision.Failure(BackendException("expired", 401))
            },
            refreshAccessToken = { throw CancellationException("parent cancelled") },
            deadlineMillis = 1_000L,
        )

        preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
        )
        Unit
    }

    @Test
    fun `transient fresh failures retry within owned retry budget`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        val attempts = mutableListOf<Int>()
        val sleeps = mutableListOf<Long>()
        var resolveCount = 0
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, _ ->
                resolveCount += 1
                VpnStartCoordinator.StartDecision.Failure(BackendException("temporary", 503))
            },
            refreshAccessToken = { null },
            retryCount = 2,
            deadlineMillis = 60_000L,
            nowMillis = { 1_000L },
            sleep = { sleeps += it },
            retryJitter = { it },
            onRetry = { attempt, _ -> attempts += attempt },
        )

        val result = preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
        )

        assertEquals(VpnConnectionPreparer.Outcome.Failure::class, result::class)
        assertEquals(3, resolveCount)
        assertEquals(listOf(1, 2), attempts)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `recovery target is forwarded unchanged`() = runBlocking {
        val settings = cachedSettings().copy(backendAccessToken = "token", isAuthenticated = true)
        val requested = VpnSessionSelection(
            countryCode = "LV",
            excludeLocationCode = "lv-2",
        )
        var observed: VpnSessionSelection? = null
        val preparer = VpnConnectionPreparer(
            store = PreparerStore(settings),
            currentNetworkKind = { EndpointRankingPolicy.NetworkKind.WIFI },
            resolveStart = { _, _, _, selection ->
                observed = selection
                VpnStartCoordinator.StartDecision.Failure(BackendException("rejected", 400))
            },
            refreshAccessToken = { null },
        )

        preparer.prepare(
            strategy = VpnPreparationStrategy.FreshOnly,
            sessionSelection = requested,
        )

        assertEquals(requested, observed)
    }

    private fun cachedSettings(): StoredSettings {
        val baseline = DefaultStoredSettingsFactory.create()
        val endpoint = VpnEndpointOption(code = "lv-1", locationCode = "lv", security = "reality",
            transport = "xhttp", transportMode = "stream-up")
        return baseline.copy(
            advancedSettings = baseline.advancedSettings.copy(ruRelayEnabled = false),
            userProfile = baseline.userProfile.copy(serverSelectionMode = com.noki.vpn.data.ServerSelectionMode.SERVER, selectedNodeId = "selected-node"),
            profile = baseline.profile.copy(
                endpointCode = endpoint.code,
                transport = endpoint.transport,
                transportMode = endpoint.transportMode,
                host = "vpn.example",
                uuid = "00000000-0000-4000-8000-000000000001",
                serverName = "vpn.example",
                publicKey = "public-key",
                shortId = "abcd",
                path = "/noki",
            ),
            endpointOptions = listOf(endpoint),
        )
    }
}

private class PreparerStore(
    private var settings: StoredSettings,
) : AtomicStoredSettingsStore {
    override fun load(): StoredSettings = settings

    override fun <R> updateAndReturn(transform: (StoredSettings) -> Pair<StoredSettings, R>): R {
        val (updated, result) = transform(settings)
        settings = updated
        return result
    }

    fun replace(next: StoredSettings) {
        settings = next
    }
}
