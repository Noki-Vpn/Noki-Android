package com.noki.vpn.vpn

import com.noki.vpn.data.EndpointRankingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VpnNetworkMonitorTest {
    @Test
    fun `wake refresh reads immediately and cancels the older debounce`() {
        val scheduler = RecordingScheduler()
        var callback: (() -> Unit)? = null
        val emitted = mutableListOf<UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>>()
        val monitor = VpnNetworkMonitor(
            source = { observation(UnderlyingNetworkAvailability.Validated, "awake") },
            scheduler = scheduler, debounceMillis = 25L,
            register = { callback = it; null },
        )
        monitor.start(emitted::add)
        callback?.invoke()
        monitor.refresh()
        assertEquals(listOf("awake"), emitted.map { it.candidate?.signature })
        scheduler.runPending()
        assertEquals(1, emitted.size)
        monitor.stop()
        monitor.refresh()
        assertEquals(1, emitted.size)
    }

    @Test
    fun `network events coalesce to one latest immutable snapshot and stop cancels ownership`() {
        val scheduler = RecordingScheduler()
        var registeredCallback: (() -> Unit)? = null
        var registrationClosed = false
        var snapshot = snapshot(signature = "wifi-old")
        val emitted = mutableListOf<UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>>()
        val monitor = VpnNetworkMonitor(
            source = { observation(UnderlyingNetworkAvailability.Validated, snapshot.signature) },
            scheduler = scheduler,
            debounceMillis = 25L,
            register = { callback ->
                registeredCallback = callback
                AutoCloseable { registrationClosed = true }
            },
        )

        monitor.start(emitted::add)
        registeredCallback?.invoke()
        snapshot = snapshot(signature = "wifi-new")
        registeredCallback?.invoke()
        scheduler.runPending()

        assertEquals(listOf("wifi-new"), emitted.mapNotNull { it.candidate?.signature })

        registeredCallback?.invoke()
        monitor.stop()
        scheduler.runPending()

        assertEquals(1, emitted.size)
        assertTrue(registrationClosed)
    }

    @Test
    fun `unvalidated network is rechecked until validated without another Android callback`() {
        val scheduler = RecordingScheduler()
        var registeredCallback: (() -> Unit)? = null
        val observations = ArrayDeque(
            listOf(
                observation(UnderlyingNetworkAvailability.Unvalidated, "cell-pending"),
                observation(UnderlyingNetworkAvailability.Validated, "cell-ready"),
            ),
        )
        val emitted = mutableListOf<UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>>()
        val monitor = VpnNetworkMonitor(
            source = { observations.removeFirst() },
            scheduler = scheduler,
            debounceMillis = 25L,
            register = { callback ->
                registeredCallback = callback
                AutoCloseable { }
            },
        )

        monitor.start(emitted::add)
        registeredCallback?.invoke()
        scheduler.runPending()
        assertEquals(listOf(UnderlyingNetworkAvailability.Unvalidated), emitted.map { it.availability })
        assertEquals(listOf(1_000L), scheduler.scheduledDelays)

        scheduler.runPending()

        assertEquals(
            listOf(UnderlyingNetworkAvailability.Unvalidated, UnderlyingNetworkAvailability.Validated),
            emitted.map { it.availability },
        )
        assertEquals("cell-ready", emitted.last().candidate?.signature)
    }

    @Test
    fun `validation recheck backs off and stop cancels it`() {
        val scheduler = RecordingScheduler()
        var registeredCallback: (() -> Unit)? = null
        val monitor = VpnNetworkMonitor(
            source = { observation(UnderlyingNetworkAvailability.Unvalidated, "cell-pending") },
            scheduler = scheduler,
            debounceMillis = 25L,
            register = { callback ->
                registeredCallback = callback
                AutoCloseable { }
            },
        )

        monitor.start { }
        registeredCallback?.invoke()
        repeat(6) { scheduler.runPending() }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), scheduler.scheduledDelays)

        monitor.stop()
        scheduler.runPending()
        assertEquals(6, scheduler.scheduledDelays.size)
    }

    @Test
    fun `callback released after stop does not read emit or reschedule`() {
        val scheduler = RecordingScheduler()
        var registeredCallback: (() -> Unit)? = null
        val sourceReads = AtomicInteger()
        val emitted = mutableListOf<UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>>()
        val monitor = VpnNetworkMonitor(
            source = {
                sourceReads.incrementAndGet()
                observation(UnderlyingNetworkAvailability.Unvalidated, "cell-pending")
            },
            scheduler = scheduler,
            debounceMillis = 25L,
            register = { callback ->
                registeredCallback = callback
                AutoCloseable { }
            },
        )
        val callbackPaused = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)

        monitor.start(emitted::add)
        val callbackThread = Thread {
            callbackPaused.countDown()
            releaseCallback.await()
            requireNotNull(registeredCallback).invoke()
        }
        callbackThread.start()
        assertTrue(callbackPaused.await(1, TimeUnit.SECONDS))

        monitor.stop()
        releaseCallback.countDown()
        callbackThread.join(1_000L)
        assertTrue(!callbackThread.isAlive)
        scheduler.runPending()

        assertEquals(0, sourceReads.get())
        assertEquals(emptyList<UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>>(), emitted)
        assertEquals(0, scheduler.scheduleCount)
    }

    @Test
    fun `stop invalidates an observation already blocked in the source`() {
        val scheduler = RecordingScheduler()
        var registeredCallback: (() -> Unit)? = null
        val sourceStarted = CountDownLatch(1)
        val releaseSource = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val emitted = mutableListOf<UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>>()
        val monitor = VpnNetworkMonitor(
            source = {
                sourceStarted.countDown()
                releaseSource.await()
                observation(UnderlyingNetworkAvailability.Unvalidated, "cell-stale")
            },
            scheduler = scheduler,
            debounceMillis = 25L,
            register = { callback ->
                registeredCallback = callback
                AutoCloseable { }
            },
        )

        monitor.start(emitted::add)
        registeredCallback?.invoke()
        val observationThread = Thread { scheduler.runPending() }
        observationThread.start()
        assertTrue(sourceStarted.await(1, TimeUnit.SECONDS))

        val stopThread = Thread {
            stopStarted.countDown()
            monitor.stop()
            stopReturned.countDown()
        }
        stopThread.start()
        assertTrue(stopStarted.await(1, TimeUnit.SECONDS))
        val returnedBeforeSourceFinished = stopReturned.await(100, TimeUnit.MILLISECONDS)

        releaseSource.countDown()
        observationThread.join(1_000L)
        stopThread.join(1_000L)

        assertTrue(!returnedBeforeSourceFinished)
        assertTrue(!observationThread.isAlive)
        assertTrue(!stopThread.isAlive)
        assertEquals(emptyList<UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>>(), emitted)
        scheduler.runPending()
        assertEquals(1, scheduler.scheduleCount)
    }

    private fun snapshot(signature: String) = UnderlyingNetworkSnapshot(
        kind = EndpointRankingPolicy.NetworkKind.WIFI,
        signature = signature,
        vpnShouldBeMetered = false,
        details = signature,
    )

    private fun observation(
        availability: UnderlyingNetworkAvailability,
        signature: String,
    ) = UnderlyingNetworkObservation(
        availability = availability,
        candidate = snapshot(signature),
    )
}

private class RecordingScheduler : DelayedTaskScheduler {
    private val pending = mutableMapOf<Any, () -> Unit>()
    val scheduledDelays = mutableListOf<Long>()
    var scheduleCount = 0

    override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) {
        scheduleCount += 1
        pending[owner] = task
        if (delayMillis != 25L) scheduledDelays += delayMillis
    }

    override fun cancel(owner: Any) {
        pending.remove(owner)
    }

    fun runPending() {
        val tasks = pending.values.toList()
        pending.clear()
        tasks.forEach { it() }
    }
}
