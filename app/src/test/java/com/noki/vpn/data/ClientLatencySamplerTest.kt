package com.noki.vpn.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class ClientLatencySamplerTest {
    @org.junit.Test fun icmpSelectionBindsTheRealPhysicalInterfaceAndRequiresAnAnswer() {
        val commands = mutableListOf<List<String>>()
        val measured = DeviceLatency.measureIcmpOnInterface("203.0.113.7", "wlan0") { args, _ ->
            commands += args
            if (args.containsAll(listOf("-I", "wlan0", "203.0.113.7")))
                "rtt min/avg/max/mdev = 21.0/23.5/26.0/1.0 ms" else null
        }
        org.junit.Assert.assertEquals(24, measured)
        org.junit.Assert.assertEquals(listOf("/system/bin/ping", "-I", "wlan0", "-c", "1", "-W", "1", "203.0.113.7"), commands.single())
        org.junit.Assert.assertNull(DeviceLatency.measureIcmpOnInterface("203.0.113.7", "rmnet_data0") { args, _ ->
            org.junit.Assert.assertEquals("rmnet_data0", args[2]); null
        })
        org.junit.Assert.assertNull(DeviceLatency.measureIcmpOnInterface("host.example", "wlan0") { _, _ -> error("DNS is not a physical ICMP target") })
    }
    @Test fun unknownListenerNeverInventsHttpsPortForSelectionOrPresentation() = runBlocking {
        val calls = AtomicInteger()
        val sampler = ClientLatencySampler(tcpConnect = { _, _, _ -> calls.incrementAndGet(); 12 },
            icmpPing = { _, _, _ -> error("No authorised ICMP target") })
        assertEquals(emptyMap<String, Int>(), sampler.measure(listOf(location().copy(servers = emptyList()))))
        assertEquals(0, calls.get())
    }

    @Test
    fun numericAddressBypassesEvenAnExhaustedDnsQueue() {
        val started = CountDownLatch(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        val release = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        try {
            val blocked = (1..DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS).map { index ->
                callers.submit<InetAddress?> {
                    DeviceLatency.resolveAddress("blocked-$index.example", 1_000) {
                        started.countDown()
                        check(release.await(2, TimeUnit.SECONDS))
                        arrayOf(InetAddress.getLoopbackAddress())
                    }
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val address = DeviceLatency.resolveAddress("104.252.30.180", 50) {
                error("A numeric address must never enter the DNS executor")
            }
            assertEquals("104.252.30.180", address?.hostAddress)
            release.countDown()
            blocked.forEach { it.get(1, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            callers.shutdownNow()
        }
    }

    @Test
    fun startupRankingUsesOneSampleAndKeepsTheUiMedianUnchanged() = runBlocking {
        val calls = AtomicInteger()
        fun sampler(attempts: Int = 3) = ClientLatencySampler(
            tcpConnect = { _, _, _ -> calls.incrementAndGet(); 25 },
            icmpPing = { _, _, _ -> error("TCP was reachable") },
            tcpAttempts = attempts,
        )
        sampler(1).measure(listOf(location()))
        assertEquals(1, calls.get())
        calls.set(0)
        sampler().measure(listOf(location()))
        assertEquals(3, calls.get())
    }

    @Test
    fun foreignNodePingNeverInventsPortsOrFallsBackToUnboundIcmp() = runBlocking {
        val nodes = listOf(RuForeignNode("node", "Overlay", "LV", probeHost = "unreachable", probePort = 30001),
            RuForeignNode("wg", "WG", "NL", probeIp = "203.0.113.8"))
        val probes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sampler = ClientLatencySampler(
            tcpConnect = { host, port, _ ->
                synchronized(probes) { probes += "$host:$port" }
                null
            },
            icmpPing = { _, _, _ -> error("A node probe must never fall back to an unbound process") },
            tcpAttempts = 1,
        )
        assertTrue(sampler.measureNodes(nodes, { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp }).isEmpty())
        assertEquals(listOf("unreachable:30001"), probes)
    }

    @Test
    fun countryPingUsesMinimumNodeMedianAndNeverTcpProbesUdpOnlyServer() = runBlocking {
        val probes = mutableListOf<Int>()
        val nodes = listOf(
            VpnServer("one", "One", "DE", "de1", "one.example", 8443, true),
            VpnServer("two", "Two", "DE", "de2", "two.example", 9443, true),
            VpnServer("udp", "UDP", "DE", "de2", "udp.example", null, true),
        )
        val country = location().copy(servers = nodes)
        val sampler = ClientLatencySampler(
            tcpConnect = { _, port, _ -> synchronized(probes) { probes += port }; if (port == 8443) 45 else 20 },
            icmpPing = { _, _, _ -> error("ICMP fallback not expected") },
        )
        val measured = country.withClientLatencies(sampler.measure(listOf(country)))
        assertEquals(20, measured.latencyMs)
        assertNull(measured.servers.last().latencyMs)
        assertEquals(setOf(8443, 9443), probes.toSet())
        assertNull(measured.withClientLatencies(emptyMap()).latencyMs)
    }
    @Test
    fun tcpDiagnosticsDistinguishSuccessRefusalAndInvalidHost() {
        val events = mutableListOf<String>()
        val server = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = server.localPort
        try {
            assertTrue(DeviceLatency.measureTcpConnectMs("127.0.0.1", port, 800, events::add) != null)
            assertTrue(events.last().contains("result=connected"))
        } finally {
            server.close()
        }
        assertNull(DeviceLatency.measureTcpConnectMs("127.0.0.1", port, 800, events::add))
        assertTrue(events.last().contains("result=connection_refused"))
        assertNull(DeviceLatency.measureTcpConnectMs("", port, 800, events::add))
        assertTrue(events.last().contains("result=invalid_host"))
    }

    @Test
    fun dnsDiagnosticsDistinguishFailureFromTimeoutWithoutExceptionText() {
        val events = mutableListOf<String>()
        assertNull(DeviceLatency.resolveAddress("test.invalid", 100, onFailure = events::add) {
            throw java.net.UnknownHostException("secret-host-should-not-appear")
        })
        assertEquals(listOf("dns_error"), events)
        events.clear()
        assertNull(DeviceLatency.resolveAddress("test.invalid", 50, onFailure = events::add) {
            Thread.sleep(10_000)
            emptyArray()
        })
        assertEquals(listOf("dns_timeout"), events)
    }

    @Test
    fun tcpDiagnosticReasonsNeverIncludeRawSocketErrors() {
        assertEquals("tcp_timeout", DeviceLatency.tcpFailureReason(java.net.SocketTimeoutException("secret")))
        assertEquals("no_route", DeviceLatency.tcpFailureReason(java.net.NoRouteToHostException("secret")))
        assertEquals("socket_error", DeviceLatency.tcpFailureReason(java.net.SocketException("secret")))
        assertEquals("connect_error", DeviceLatency.tcpFailureReason(java.net.ConnectException("secret")))
    }

    @Test
    fun stalledProbeCannotBlockTheLocationBatch() = runBlocking {
        val sampler = ClientLatencySampler(
            tcpConnect = { _, _, _ ->
                Thread.sleep(10_000L)
                null
            },
            icmpPing = { _, _, _ -> null },
            locationTimeoutMillis = 50L,
        )
        var result = emptyMap<String, Int>()

        val elapsed = measureTimeMillis {
            result = withTimeout(1_000L) { sampler.measure(listOf(location())) }
        }

        assertTrue(result.isEmpty())
        assertTrue("Probe batch exceeded its deadline: ${elapsed}ms", elapsed < 1_000L)
    }

    @Test
    fun dnsLookupHasItsOwnBoundedExecutorDeadline() {
        var address: java.net.InetAddress? = null

        val elapsed = measureTimeMillis {
            address = DeviceLatency.resolveAddress(
                host = "blocked.example",
                timeoutMs = 50,
                lookup = {
                    Thread.sleep(10_000L)
                    emptyArray()
                },
            )
        }

        assertNull(address)
        assertTrue("DNS lookup exceeded its deadline: ${elapsed}ms", elapsed < 1_000L)
    }

    @Test
    fun healthyLocationsWaitForBoundedProbeSlotsInsteadOfBeingDropped() = runBlocking {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val sampler = ClientLatencySampler(
            tcpConnect = { _, _, _ ->
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    Thread.sleep(20L)
                    10
                } finally {
                    active.decrementAndGet()
                }
            },
            icmpPing = { _, _, _ -> null },
            locationTimeoutMillis = 1_000L,
        )
        val locations = (1..8).map { index -> location(index) }

        val result = sampler.measure(locations)

        assertTrue(result.size == locations.size)
        assertTrue(maximumActive.get() <= DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
    }

    @Test
    fun queuedStalledLocationsShareOneBatchDeadline() = runBlocking {
        val sampler = ClientLatencySampler(
            tcpConnect = { _, _, _ ->
                Thread.sleep(10_000L)
                null
            },
            icmpPing = { _, _, _ -> null },
            locationTimeoutMillis = 100L,
        )
        val locations = (1..20).map { index -> location(index) }
        var result = emptyMap<String, Int>()

        val elapsed = measureTimeMillis {
            result = sampler.measure(locations)
        }

        assertTrue(result.isEmpty())
        assertTrue("Queued probes multiplied the batch deadline: ${elapsed}ms", elapsed < 300L)
    }

    @Test
    fun vpnPrecheckWaitsForGlobalDnsSlotInsteadOfBeingRejected() {
        val address = InetAddress.getByName("127.0.0.1")
        val blockingLookupsStarted = CountDownLatch(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        val releaseBlockingLookups = CountDownLatch(1)
        val startupLookupStarted = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS + 1)
        try {
            val blocking = (1..DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS).map {
                callers.submit<InetAddress?> {
                    DeviceLatency.resolveAddress("latency-$it.example", 1_000) {
                        blockingLookupsStarted.countDown()
                        check(releaseBlockingLookups.await(1, TimeUnit.SECONDS))
                        arrayOf(address)
                    }
                }
            }
            assertTrue(blockingLookupsStarted.await(1, TimeUnit.SECONDS))
            val startup = callers.submit<InetAddress?> {
                DeviceLatency.resolveAddress("startup.example", 1_000) {
                    startupLookupStarted.countDown()
                    arrayOf(address)
                }
            }

            assertFalse(startupLookupStarted.await(100, TimeUnit.MILLISECONDS))
            assertFalse("Startup lookup was rejected instead of waiting", startup.isDone)
            releaseBlockingLookups.countDown()

            assertEquals(address, startup.get(1, TimeUnit.SECONDS))
            blocking.forEach { result -> assertEquals(address, result.get(1, TimeUnit.SECONDS)) }
        } finally {
            releaseBlockingLookups.countDown()
            callers.shutdownNow()
        }
    }

    @Test
    fun timedOutDnsLookupKeepsItsSlotUntilTheResolverActuallyStops() {
        val address = InetAddress.getByName("127.0.0.1")
        val blockingLookupsStarted = CountDownLatch(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        val blockingLookupsFinished = CountDownLatch(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        val releaseBlockingLookups = CountDownLatch(1)
        val startupLookupStarted = CountDownLatch(1)
        val callers = Executors.newFixedThreadPool(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS + 1)
        try {
            val blocking = (1..DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS).map {
                callers.submit<InetAddress?> {
                    DeviceLatency.resolveAddress("stalled-$it.example", 100) {
                        blockingLookupsStarted.countDown()
                        try {
                            while (releaseBlockingLookups.count > 0L) {
                                try {
                                    releaseBlockingLookups.await(25, TimeUnit.MILLISECONDS)
                                } catch (_: InterruptedException) {
                                    // Model a platform resolver that ignores interruption.
                                }
                            }
                            arrayOf(address)
                        } finally {
                            blockingLookupsFinished.countDown()
                        }
                    }
                }
            }
            assertTrue(blockingLookupsStarted.await(1, TimeUnit.SECONDS))
            blocking.forEach { result -> assertNull(result.get(1, TimeUnit.SECONDS)) }

            val startup = callers.submit<InetAddress?> {
                DeviceLatency.resolveAddress("startup.example", 500) {
                    startupLookupStarted.countDown()
                    arrayOf(address)
                }
            }

            assertFalse("Startup lookup returned before its admission deadline", startup.isDone)
            assertFalse(startupLookupStarted.await(100, TimeUnit.MILLISECONDS))
            assertFalse("Startup lookup was rejected instead of waiting", startup.isDone)
            assertNull(startup.get(1, TimeUnit.SECONDS))
            assertEquals(1L, startupLookupStarted.count)
        } finally {
            releaseBlockingLookups.countDown()
            assertTrue(blockingLookupsFinished.await(1, TimeUnit.SECONDS))
            callers.shutdownNow()
        }
    }

    private fun location(index: Int = 1) = ServerLocation(
        code = "pl-$index",
        country = "Poland",
        city = "Warsaw",
        host = "pl-$index.example",
        isOnline = true,
        servers = listOf(VpnServer("node-$index", "Node", "PL", "pl-$index", "pl-$index.example", 443, true)),
    )
}
