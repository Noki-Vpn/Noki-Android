package com.noki.vpn.vpn

internal interface VpnCoreRuntime {
    fun start(config: String, tunFd: Int): Boolean

    fun rebindNetwork(): Boolean

    fun wake()

    fun stop()

    fun cancelMeasureDelay()

    fun measureDelay(targetUrl: String, timeoutMillis: Long): VpnProbeResult

    fun prepareReadinessCandidates(targetUrls: List<String>, timeoutMillis: Long, watchdog: Boolean = false): VpnReadinessProbe

    fun nokiNvCarrier(): String
}

internal class VpnReadinessProbe(
    val measure: () -> VpnProbeResult,
    val cancel: () -> Unit,
)

internal data class VpnProbeResult(
    val delayMs: Long?,
    val issue: VpnCoreIssue? = null,
)
