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
        activeProbe?.cancel()
        activeProbe = null
        activeOwner = null
        settings = null
        failureCount = 0
        lastHealthyMillis = null
        lastVpnEvidenceMillis = null
    }

    @Synchronized
    fun forceProbe() {
        val owner = activeOwner ?: return
        val snapshot = settings ?: return
        launchOwnedProbe(owner, snapshot)
    }

    @Synchronized
    fun resetNetworkEvidence(preserveHealthyEvidence: Boolean = false) {
        activeProbe?.cancel()
        activeProbe = null
        failureCount = 0
        if (!preserveHealthyEvidence) {
            lastHealthyMillis = null
            lastVpnEvidenceMillis = null
        }
        val owner = activeOwner ?: return
        val updated = owner.copy(networkRevision = owner.networkRevision + 1)
        activeOwner = updated
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
        activeProbe = null
        val healthy = VpnReadinessPolicy.accept(latencyMs)
        if (healthy) {
            failureCount = 0
            recordEvidence(WatchdogEvidence.VPN_PROBE)
            return WatchdogProbeOutcome.Healthy
        }
        if (!ConnectedWatchdogPolicy.automaticRecoveryAllowed(checkNotNull(settings).userProfile.serverSelectionMode))
            return WatchdogProbeOutcome.Unhealthy(WatchdogAction.None)
        return when (ConnectedWatchdogPolicy.probeDecision(false, failureCount)) {
            ConnectedWatchdogPolicy.ProbeDecision.Healthy -> error("Failed probe cannot be healthy")
            ConnectedWatchdogPolicy.ProbeDecision.Retry -> {
                failureCount += 1
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

    private fun scheduleNext(owner: ConnectedWatchdogPolicy.Owner) {
        scheduler.schedule(scheduleOwner, ConnectedWatchdogPolicy.CHECK_INTERVAL_MS) {
            synchronized(this) {
                if (activeOwner != owner) return@schedule
                val snapshot = settings ?: return@schedule
                val lastHealthy = lastHealthyMillis
                if (lastHealthy == null ||
                    nowMillis() - lastHealthy >= ConnectedWatchdogPolicy.PROBE_AFTER_IDLE_MS
                ) {
                    launchOwnedProbe(owner, snapshot)
                }
                if (activeOwner == owner) scheduleNext(owner)
            }
        }
    }

    private fun launchOwnedProbe(
        owner: ConnectedWatchdogPolicy.Owner,
        settings: StoredSettings,
    ) {
        if (activeProbe != null || activeOwner != owner) return
        val launching = CancelableTask {}
        activeProbe = launching
        val launched = launchProbe(owner, settings)
        if (activeProbe === launching) activeProbe = launched
    }
}
