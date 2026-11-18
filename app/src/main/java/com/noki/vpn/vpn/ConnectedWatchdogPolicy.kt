package com.noki.vpn.vpn

import com.noki.vpn.data.ServerSelectionMode

object ConnectedWatchdogPolicy {
    fun automaticRecoveryAllowed(selectionMode: ServerSelectionMode): Boolean = selectionMode != ServerSelectionMode.SERVER

    enum class TransitionExceptionDecision {
        UseGeneralFailure,
        KeepTruthfulLockdownFailure,
    }

    fun transitionExceptionDecision(activeLockdownRecovery: Boolean): TransitionExceptionDecision =
        if (activeLockdownRecovery) {
            TransitionExceptionDecision.KeepTruthfulLockdownFailure
        } else {
            TransitionExceptionDecision.UseGeneralFailure
        }

    data class Owner(
        val generationId: Long,
        val tunnelIdentity: Int,
        val coreIdentity: Long,
        val networkRevision: Long = 0L,
    )

    fun canApplyResult(
        captured: Owner,
        current: Owner?,
    ): Boolean = captured == current

    const val CHECK_INTERVAL_MS = 60_000L
    const val WAKE_FAILURE_RETRY_MS = 2_000L
    const val PROBE_AFTER_IDLE_MS = 120_000L
    const val RECOVERY_WINDOW_MS = 10 * 60_000L
    const val MAX_RECOVERIES_IN_WINDOW = 3

    enum class TrafficDecision {
        Healthy,
        Probe,
    }

    enum class ProbeDecision {
        Healthy,
        Retry,
        Recover,
    }

    enum class RecoveryDecision {
        Recover,
        FailClosed,
    }

    data class RecoveryTarget(
        val locationCode: String? = null,
        val excludeLocationCode: String? = null,
        val attempts: Int = 1,
    )

    enum class PreparationFailureDecision {
        RetryWithoutConsumingEndpointAttempt,
        CountEndpointAttempt,
        PermanentFailure,
    }

    fun preparationFailureDecision(
        isTransient: Boolean,
        preparedSessionAvailable: Boolean,
    ): PreparationFailureDecision = when {
        preparedSessionAvailable -> PreparationFailureDecision.CountEndpointAttempt
        isTransient -> PreparationFailureDecision.RetryWithoutConsumingEndpointAttempt
        else -> PreparationFailureDecision.PermanentFailure
    }

    fun transientRetryDelayMillis(
        attempt: Int,
        jitterFraction: Double = kotlin.random.Random.nextDouble(),
    ): Long {
        require(jitterFraction in 0.0..1.0)
        val ceiling = (15_000L shl attempt.coerceIn(0, 5)).coerceAtMost(300_000L)
        // Equal jitter keeps a nonzero delay even during a widespread outage.
        return ceiling / 2 + (ceiling / 2 * jitterFraction).toLong()
    }

    fun transientStartDecision(attempt: Int): VpnPreparationStrategy =
        if (attempt <= 0) VpnPreparationStrategy.CachedFirst else VpnPreparationStrategy.FreshOnly

    fun recoveryTargets(currentLocationCode: String): List<RecoveryTarget> = listOf(
        RecoveryTarget(locationCode = currentLocationCode),
        RecoveryTarget(excludeLocationCode = currentLocationCode),
    )

    enum class ExhaustedRecoveryDecision {
        ReleaseTunnel,
        ReportLockdownBlockedAndRetry,
    }

    enum class RetryFailureDecision {
        UseGeneralFailure,
        KeepTruthfulFailedAndReschedule,
    }

    fun retryFailureDecision(
        isLockdown: Boolean,
    ): RetryFailureDecision = when {
        !isLockdown -> RetryFailureDecision.UseGeneralFailure
        else -> RetryFailureDecision.KeepTruthfulFailedAndReschedule
    }

    fun exhaustedRecoveryDecision(isLockdown: Boolean): ExhaustedRecoveryDecision =
        if (isLockdown) {
            ExhaustedRecoveryDecision.ReportLockdownBlockedAndRetry
        } else {
            ExhaustedRecoveryDecision.ReleaseTunnel
        }

    fun failureReason(isLockdown: Boolean): String = if (isLockdown) {
        "watchdog_recovery_exhausted_lockdown"
    } else {
        "watchdog_recovery_exhausted"
    }

    fun lockdownRetryDelayMillis(recoveryTimes: List<Long>, nowMillis: Long): Long {
        val oldestRecovery = recoveryTimes.minOrNull() ?: return RECOVERY_WINDOW_MS
        return (oldestRecovery + RECOVERY_WINDOW_MS - nowMillis).coerceAtLeast(CHECK_INTERVAL_MS)
    }

    fun probeScheduleDecision(
        millisSinceHealthy: Long,
    ): TrafficDecision {
        return if (millisSinceHealthy < PROBE_AFTER_IDLE_MS) {
            TrafficDecision.Healthy
        } else {
            TrafficDecision.Probe
        }
    }

    fun probeDecision(
        success: Boolean,
        previousFailures: Int,
    ): ProbeDecision {
        return when {
            success -> ProbeDecision.Healthy
            previousFailures + 1 < 2 -> ProbeDecision.Retry
            else -> ProbeDecision.Recover
        }
    }

    fun recoveryDecision(
        recoveryTimes: List<Long>,
        nowMillis: Long,
    ): RecoveryDecision {
        val recentRecoveries = recoveryTimes.count { timestamp ->
            nowMillis - timestamp in 0 until RECOVERY_WINDOW_MS
        }
        return if (recentRecoveries >= MAX_RECOVERIES_IN_WINDOW) {
            RecoveryDecision.FailClosed
        } else {
            RecoveryDecision.Recover
        }
    }
}
