package com.noki.vpn.vpn

import com.noki.vpn.data.StoredSettings

internal sealed interface WatchdogAction {
    data object None : WatchdogAction

    data object Refresh : WatchdogAction

    data class EnterLockdownFailure(val retryDelayMillis: Long) : WatchdogAction

    data object ReleaseOutsideLockdown : WatchdogAction
}

internal enum class WatchdogEvidence {
    VPN_PROBE,
    UID_TRAFFIC,
}

internal sealed interface WatchdogProbeOutcome {
    data object Ignored : WatchdogProbeOutcome
    data object Healthy : WatchdogProbeOutcome
    data class Unhealthy(val action: WatchdogAction) : WatchdogProbeOutcome
}

internal class VpnWatchdogController(
    private val scheduler: DelayedTaskScheduler,
    private val nowMillis: () -> Long,
    private val launchProbe: (ConnectedWatchdogPolicy.Owner, StoredSettings) -> CancelableTask?,
    private val isLockdown: () -> Boolean,
    private val evidenceFreshMillis: Long = 5 * 60_000L,
) {
    private val scheduleOwner = Any()
    private var activeOwner: ConnectedWatchdogPolicy.Owner? = null
    private var settings: StoredSettings? = null
    private var activeProbe: CancelableTask? = null
    private var wakeProbeOwner: ConnectedWatchdogPolicy.Owner? = null
    private var failureCount = 0
    private var lastHealthyMillis: Long? = null
    private var lastVpnEvidenceMillis: Long? = null
    private val recoveryTimes = ArrayDeque<Long>()

    @Synchronized
    fun start(
        owner: ConnectedWatchdogPolicy.Owner,
        settings: StoredSettings,
        initialVpnEvidence: Boolean,
    ) {
        stop(null)
        activeOwner = owner
        this.settings = settings
        failureCount = 0
        if (initialVpnEvidence) recordEvidence(WatchdogEvidence.VPN_PROBE)
        scheduleNext(owner)
    }

    @Synchronized
    fun stop(owner: ConnectedWatchdogPolicy.Owner?) {
        val current = activeOwner
        if (owner != null && current != owner) return
        scheduler.cancel(scheduleOwner)
        val probe = activeProbe
        activeProbe = null
        wakeProbeOwner = null
        activeOwner = null
        settings = null
        failureCount = 0
        lastHealthyMillis = null
        lastVpnEvidenceMillis = null
        probe?.cancel()
    }

    @Synchronized
    fun forceProbe() {
        val owner = activeOwner ?: return
        val snapshot = settings ?: return
        launchOwnedProbe(owner, snapshot)
    }

    @Synchronized
    fun onWake() {
        val owner = activeOwner ?: return
        val snapshot = settings ?: return
        val probe = activeProbe
        val updated = owner.copy(networkRevision = owner.networkRevision + 1)
        activeOwner = updated
        activeProbe = null
        wakeProbeOwner = null
        probe?.cancel()
        scheduler.cancel(scheduleOwner)
        scheduleNext(updated)
        launchOwnedProbe(updated, snapshot, fromWake = true)
    }

    @Synchronized
    fun resetNetworkEvidence(preserveHealthyEvidence: Boolean = false) {
        val probe = activeProbe
        activeProbe = null
        wakeProbeOwner = null
        val owner = activeOwner
        val updated = owner?.copy(networkRevision = owner.networkRevision + 1)
        activeOwner = updated
        probe?.cancel()
        failureCount = 0
        if (!preserveHealthyEvidence) {
            lastHealthyMillis = null
            lastVpnEvidenceMillis = null
        }
        if (updated == null) return
        scheduler.cancel(scheduleOwner)
        scheduleNext(updated)
    }

    @Synchronized
    fun completeProbe(
        owner: ConnectedWatchdogPolicy.Owner,
        latencyMs: Long?,
    ): WatchdogProbeOutcome {
        if (activeOwner != owner || activeProbe == null) {
            return WatchdogProbeOutcome.Ignored
        }
        val fromWake = wakeProbeOwner == owner
        activeProbe = null
        wakeProbeOwner = null
        val healthy = VpnReadinessPolicy.accept(latencyMs)
        if (healthy) {
            failureCount = 0
            recordEvidence(WatchdogEvidence.VPN_PROBE)
            return WatchdogProbeOutcome.Healthy
        }
        return when (ConnectedWatchdogPolicy.probeDecision(false, failureCount)) {
            ConnectedWatchdogPolicy.ProbeDecision.Healthy -> error("Failed probe cannot be healthy")
            ConnectedWatchdogPolicy.ProbeDecision.Retry -> {
                failureCount += 1
                if (fromWake) scheduleNext(owner, ConnectedWatchdogPolicy.WAKE_FAILURE_RETRY_MS, forceProbe = true)
                WatchdogProbeOutcome.Unhealthy(WatchdogAction.None)
            }
            ConnectedWatchdogPolicy.ProbeDecision.Recover -> {
                failureCount += 1
                val now = nowMillis()
                while (recoveryTimes.isNotEmpty() &&
                    now - recoveryTimes.first() >= ConnectedWatchdogPolicy.RECOVERY_WINDOW_MS
                ) {
                    recoveryTimes.removeFirst()
                }
                val action = when (ConnectedWatchdogPolicy.recoveryDecision(recoveryTimes, now)) {
                    ConnectedWatchdogPolicy.RecoveryDecision.Recover -> {
                        recoveryTimes.addLast(now)
                        WatchdogAction.Refresh
                    }
                    ConnectedWatchdogPolicy.RecoveryDecision.FailClosed -> {
                        if (isLockdown()) {
                            WatchdogAction.EnterLockdownFailure(
                                ConnectedWatchdogPolicy.lockdownRetryDelayMillis(recoveryTimes, now),
                            )
                        } else {
                            WatchdogAction.ReleaseOutsideLockdown
                        }
                    }
                }
                WatchdogProbeOutcome.Unhealthy(action)
            }
        }
    }

    @Synchronized
    fun recordEvidence(evidence: WatchdogEvidence) {
        if (evidence != WatchdogEvidence.VPN_PROBE || activeOwner == null) return
        val now = nowMillis()
        lastHealthyMillis = now
        lastVpnEvidenceMillis = now
        failureCount = 0
    }

    @Synchronized
    fun hasFreshVpnEvidence(): Boolean {
        val evidenceAt = lastVpnEvidenceMillis ?: return false
        return nowMillis() - evidenceAt in 0..evidenceFreshMillis
    }

    @Synchronized
    fun accepts(owner: ConnectedWatchdogPolicy.Owner): Boolean = activeOwner == owner

    @Synchronized
    fun currentOwner(): ConnectedWatchdogPolicy.Owner? = activeOwner

    @Synchronized
    fun currentSettings(): StoredSettings? = settings

    @Synchronized
    fun lockdownRetryDelayMillis(): Long {
        return ConnectedWatchdogPolicy.lockdownRetryDelayMillis(recoveryTimes, nowMillis())
    }

    private fun scheduleNext(
        owner: ConnectedWatchdogPolicy.Owner,
        delayMillis: Long = ConnectedWatchdogPolicy.CHECK_INTERVAL_MS,
        forceProbe: Boolean = false,
    ) {
        scheduler.schedule(scheduleOwner, delayMillis) {
            synchronized(this) {
                if (activeOwner != owner) return@schedule
                val snapshot = settings ?: return@schedule
                // Arm ordinary cadence first: synchronous completion may replace it with confirmation.
                scheduleNext(owner)
                val lastHealthy = lastHealthyMillis
                if (forceProbe || lastHealthy == null ||
                    nowMillis() - lastHealthy >= ConnectedWatchdogPolicy.PROBE_AFTER_IDLE_MS
                ) {
                    launchOwnedProbe(owner, snapshot)
                }
            }
        }
    }

    private fun launchOwnedProbe(
        owner: ConnectedWatchdogPolicy.Owner,
        settings: StoredSettings,
        fromWake: Boolean = false,
    ) {
        if (activeProbe != null || activeOwner != owner) return
        val launching = CancelableTask {}
        activeProbe = launching
        wakeProbeOwner = owner.takeIf { fromWake }
        val launched = launchProbe(owner, settings)
        if (activeProbe === launching) {
            activeProbe = launched
            if (launched == null) wakeProbeOwner = null
        }
    }
}
