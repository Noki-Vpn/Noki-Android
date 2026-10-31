package com.noki.vpn.vpn

import com.noki.vpn.data.DefaultStoredSettingsFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnWatchdogControllerTest {
    @Test
    fun `wake invalidates old probes but does not discard or refresh healthy evidence`() {
        var now = 0L
        var cancelled = 0
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(), nowMillis = { now },
            launchProbe = { _, _ -> CancelableTask { cancelled++ } },
            isLockdown = { false }, evidenceFreshMillis = 10_000L,
        )
        val owner = watchdogOwner(1L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)
        controller.forceProbe()
        now = 5_000L
        controller.resetNetworkEvidence(preserveHealthyEvidence = true)
        assertEquals(1, cancelled)
        assertEquals(WatchdogProbeOutcome.Ignored, controller.completeProbe(owner, null))
        assertTrue(controller.hasFreshVpnEvidence())
        now = 10_001L
        assertFalse(controller.hasFreshVpnEvidence())
    }

    @Test
    fun `manual server never restarts or releases a failing tunnel`() {
        for (lockdown in listOf(false, true)) {
            val controller = VpnWatchdogController(
                scheduler = WatchdogScheduler(), nowMillis = { 1_000L },
                launchProbe = { _, _ -> CancelableTask {} }, isLockdown = { lockdown },
            )
            val owner = watchdogOwner(20L)
            val settings = DefaultStoredSettingsFactory.create().let { it.copy(
                userProfile = it.userProfile.copy(serverSelectionMode = com.noki.vpn.data.ServerSelectionMode.SERVER),
            ) }
            controller.start(owner, settings, initialVpnEvidence = false)
            repeat(10) { assertEquals(WatchdogAction.None, failedProbe(controller, owner)) }
            assertEquals(owner, controller.currentOwner())
            controller.forceProbe()
            assertEquals(WatchdogProbeOutcome.Healthy, controller.completeProbe(owner, 30L))
        }
    }

    @Test
    fun `network handover cancels old evidence without losing the runtime owner`() {
        var cancelled = 0
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(), nowMillis = { 1_000L },
            launchProbe = { _, _ -> CancelableTask { cancelled++ } }, isLockdown = { false },
        )
        val owner = watchdogOwner(7L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)
        controller.forceProbe()
        controller.resetNetworkEvidence()
        assertEquals(1, cancelled)
        val updated = owner.copy(networkRevision = 1L)
        assertEquals(updated, controller.currentOwner())
        assertFalse(controller.accepts(owner))
        assertFalse(controller.hasFreshVpnEvidence())
        assertEquals(WatchdogProbeOutcome.Ignored, controller.completeProbe(owner, null))
        assertEquals(WatchdogAction.None, failedProbe(controller, updated))
        controller.recordEvidence(WatchdogEvidence.VPN_PROBE)
        assertTrue(controller.hasFreshVpnEvidence())
    }

    @Test
    fun `completed recovery decision from the old network cannot replace the rebound runtime`() {
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(), nowMillis = { 1_000L },
            launchProbe = { _, _ -> CancelableTask {} }, isLockdown = { false },
        )
        val owner = watchdogOwner(8L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)
        assertEquals(WatchdogAction.None, failedProbe(controller, owner))
        assertEquals(WatchdogAction.Refresh, failedProbe(controller, owner))
        controller.resetNetworkEvidence()
        assertFalse(controller.accepts(owner))
        val current = checkNotNull(controller.currentOwner())
        assertEquals(owner.generationId, current.generationId)
        assertEquals(owner.coreIdentity, current.coreIdentity)
        assertEquals(owner.tunnelIdentity, current.tunnelIdentity)
        assertEquals(WatchdogAction.None, failedProbe(controller, current))
    }

    @Test
    fun `forced and scheduled requests coalesce to one in-flight VPN probe`() {
        var now = 0L
        val scheduler = WatchdogScheduler()
        val launched = mutableListOf<ConnectedWatchdogPolicy.Owner>()
        val controller = VpnWatchdogController(
            scheduler = scheduler,
            nowMillis = { now },
            launchProbe = { owner, _ ->
                launched += owner
                CancelableTask {}
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 1L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)

        now = ConnectedWatchdogPolicy.PROBE_AFTER_IDLE_MS
        scheduler.runPending()
        controller.forceProbe()

        assertEquals(listOf(owner), launched)

        controller.completeProbe(owner, latencyMs = 25L)
        controller.forceProbe()

        assertEquals(listOf(owner, owner), launched)
    }

    @Test
    fun `replacement rejects stale probe completion`() {
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
        )
        val first = watchdogOwner(core = 1L)
        val second = watchdogOwner(core = 2L)
        controller.start(first, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)
        controller.forceProbe()
        controller.start(second, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)

        val stale = controller.completeProbe(first, latencyMs = null)

        assertEquals(WatchdogProbeOutcome.Ignored, stale)
    }

    @Test
    fun `UID traffic cannot replace VPN probe evidence`() {
        var now = 1_000L
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { now },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
            evidenceFreshMillis = 5_000L,
        )
        val owner = watchdogOwner(core = 3L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = false)

        controller.recordEvidence(WatchdogEvidence.UID_TRAFFIC)
        assertFalse(controller.hasFreshVpnEvidence())

        controller.recordEvidence(WatchdogEvidence.VPN_PROBE)
        assertTrue(controller.hasFreshVpnEvidence())

        now += 5_001L
        assertFalse(controller.hasFreshVpnEvidence())
    }

    @Test
    fun `recovery budget produces typed lifecycle actions`() {
        var now = 1_000L
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { now },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 4L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)

        repeat(ConnectedWatchdogPolicy.MAX_RECOVERIES_IN_WINDOW) {
            assertEquals(WatchdogAction.None, failedProbe(controller, owner))
            assertEquals(WatchdogAction.Refresh, failedProbe(controller, owner))
            now += 1L
            controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)
        }

        assertEquals(WatchdogAction.None, failedProbe(controller, owner))
        assertEquals(WatchdogAction.ReleaseOutsideLockdown, failedProbe(controller, owner))
    }

    @Test
    fun `completion without an in-flight probe is rejected`() {
        val controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { _, _ -> CancelableTask {} },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 5L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)

        assertEquals(WatchdogProbeOutcome.Ignored, controller.completeProbe(owner, latencyMs = 25L))
    }

    @Test
    fun `probe may complete before launcher returns its cancellation handle`() {
        lateinit var controller: VpnWatchdogController
        lateinit var completion: WatchdogProbeOutcome
        controller = VpnWatchdogController(
            scheduler = WatchdogScheduler(),
            nowMillis = { 1_000L },
            launchProbe = { owner, _ ->
                completion = controller.completeProbe(owner, latencyMs = 25L)
                CancelableTask {}
            },
            isLockdown = { false },
        )
        val owner = watchdogOwner(core = 6L)
        controller.start(owner, DefaultStoredSettingsFactory.create(), initialVpnEvidence = true)

        controller.forceProbe()

        assertEquals(WatchdogProbeOutcome.Healthy, completion)
    }

    private fun failedProbe(
        controller: VpnWatchdogController,
        owner: ConnectedWatchdogPolicy.Owner,
    ): WatchdogAction {
        controller.forceProbe()
        return (controller.completeProbe(owner, latencyMs = null) as WatchdogProbeOutcome.Unhealthy).action
    }

    private fun watchdogOwner(core: Long) = ConnectedWatchdogPolicy.Owner(
        generationId = core,
        tunnelIdentity = core.toInt(),
        coreIdentity = core,
    )
}

private class WatchdogScheduler : DelayedTaskScheduler {
    private val pending = mutableMapOf<Any, () -> Unit>()

    override fun schedule(owner: Any, delayMillis: Long, task: () -> Unit) {
        pending[owner] = task
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
