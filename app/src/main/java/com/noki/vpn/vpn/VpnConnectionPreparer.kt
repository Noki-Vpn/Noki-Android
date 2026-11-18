package com.noki.vpn.vpn

import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendRetryPolicy
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnProfileValidator
import com.noki.vpn.data.VpnProtocol
import com.noki.vpn.data.VpnSessionSelection
import com.noki.vpn.data.VpnStartCoordinator
import java.net.SocketTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

enum class VpnPreparationStrategy(val forceRefreshSession: Boolean, val allowCachedFallback: Boolean) {
    CachedFirst(false, true),
    CachedFirstWithoutFallback(false, false),
    FreshOnly(true, false),
    FreshWithCachedFallback(true, true),
}

internal data class PreparedVpnSession(
    val preparationBaseline: StoredSettings,
    val candidateSettings: StoredSettings,
    val selectedNetworkKind: EndpointRankingPolicy.NetworkKind,
    val pendingWarmupSession: VpnStartCoordinator.Result?,
)

private data class TokenRefreshResult(val token: String?)

internal class VpnConnectionPreparer(
    private val store: AtomicStoredSettingsStore,
    private val currentNetworkKind: () -> EndpointRankingPolicy.NetworkKind,
    private val resolveStart: suspend (
        String,
        StoredSettings,
        Boolean,
        VpnSessionSelection,
    ) -> VpnStartCoordinator.StartDecision,
    private val refreshAccessToken: suspend () -> String?,
    private val retryCount: Int = 2,
    private val deadlineMillis: Long = 20_000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val retryJitter: (Long) -> Long = { base ->
        val spread = (base / 4L).coerceAtLeast(1L)
        base - Random.nextLong(0L, spread + 1L)
    },
    private val onRetry: (Int, Throwable) -> Unit = { _, _ -> },
    private val onCachedFallback: (Throwable) -> Unit = {},
) {
    sealed interface Outcome {
        data class Success(val session: PreparedVpnSession) : Outcome

        data class Failure(val error: Throwable) : Outcome
    }

    suspend fun prepare(
        strategy: VpnPreparationStrategy,
        sessionSelection: VpnSessionSelection? = null,
        onRetry: (Int, Throwable) -> Unit = this.onRetry,
        onCachedFallback: (Throwable) -> Unit = this.onCachedFallback,
    ): Outcome {
        var localSettings = store.load()
        if (localSettings.advancedSettings.ruRelayEnabled) {
            return Outcome.Failure(IllegalStateException("ru_wireguard_session_unavailable"))
        }
        val prepareAutoCandidates = localSettings.advancedSettings.effectiveProtocol == VpnProtocol.AUTO &&
            localSettings.endpointOptions.any { it.proxyType == "nokinv" }
        val automaticNode = localSettings.userProfile.serverSelectionMode != com.noki.vpn.data.ServerSelectionMode.SERVER
        if (!strategy.forceRefreshSession && !prepareAutoCandidates && !automaticNode &&
            RuntimeProfilePolicy.isCachedProfileUsable(localSettings)) {
            return Outcome.Success(
                PreparedVpnSession(
                    preparationBaseline = localSettings,
                    candidateSettings = localSettings,
                    selectedNetworkKind = currentNetworkKind(),
                    pendingWarmupSession = null,
                ),
            )
        }

        var token = localSettings.backendAccessToken?.takeIf(String::isNotBlank)
            ?: return Outcome.Failure(IllegalStateException("auth_required"))
        val requestedSelection = sessionSelection ?: VpnSessionSelection(
            countryCode = localSettings.userProfile.selectedCountryCode,
        )
        val deadlineAtMillis = nowMillis() + deadlineMillis.coerceAtLeast(1L)
        var decision = resolveWithRetries(
            token,
            localSettings,
            strategy.allowCachedFallback,
            requestedSelection,
            onRetry,
            deadlineAtMillis,
        )
        if (decision is VpnStartCoordinator.StartDecision.Failure &&
            decision.error is BackendException &&
            decision.error.statusCode == 401
        ) {
            val remainingMillis = deadlineAtMillis - nowMillis()
            val refreshResult = if (remainingMillis > 0L) {
                withTimeoutOrNull(remainingMillis) {
                    TokenRefreshResult(refreshAccessToken())
                }
            } else {
                null
            }
            if (refreshResult == null) {
                decision = preparationTimeout()
            } else if (refreshResult.token != null) {
                token = refreshResult.token
                localSettings = store.load()
                decision = resolveWithRetries(
                    token,
                    localSettings,
                    strategy.allowCachedFallback,
                    requestedSelection,
                    onRetry,
                    deadlineAtMillis,
                )
            }
        }

        val prepared = when (decision) {
            is VpnStartCoordinator.StartDecision.FreshSession -> {
                PreparedVpnSession(
                    preparationBaseline = localSettings,
                    candidateSettings = decision.result.settings,
                    selectedNetworkKind = decision.result.selection.networkKind,
                    pendingWarmupSession = decision.result,
                )
            }
            is VpnStartCoordinator.StartDecision.CachedFallback -> {
                onCachedFallback(decision.error)
                PreparedVpnSession(
                    preparationBaseline = localSettings,
                    candidateSettings = localSettings,
                    selectedNetworkKind = currentNetworkKind(),
                    pendingWarmupSession = null,
                )
            }
            is VpnStartCoordinator.StartDecision.Failure -> return Outcome.Failure(decision.error)
        }
        if (!VpnProfileValidator.isUsable(prepared.candidateSettings)) {
            return Outcome.Failure(IllegalStateException("unusable_profile"))
        }
        return Outcome.Success(prepared)
    }

    private suspend fun resolveWithRetries(
        token: String,
        settings: StoredSettings,
        allowCachedFallback: Boolean,
        sessionSelection: VpnSessionSelection,
        onRetry: (Int, Throwable) -> Unit,
        deadlineAtMillis: Long,
    ): VpnStartCoordinator.StartDecision {
        var attempt = 0
        while (true) {
            val remainingMillis = deadlineAtMillis - nowMillis()
            if (remainingMillis <= 0L) return preparationTimeout()
            val decision = withTimeoutOrNull(remainingMillis) {
                resolveStart(token, settings, allowCachedFallback, sessionSelection)
            } ?: return preparationTimeout()
            if (allowCachedFallback || decision !is VpnStartCoordinator.StartDecision.Failure) {
                return decision
            }
            val error = decision.error
            if (!BackendRetryPolicy.isTransient(error) || attempt >= retryCount) return decision
            val retryDelayMillis = BackendRetryPolicy.delayMillis(
                error = error,
                attempt = attempt,
                jitter = retryJitter,
            )
            if (nowMillis() + retryDelayMillis > deadlineAtMillis) return decision
            attempt += 1
            onRetry(attempt, error)
            sleep(retryDelayMillis)
        }
    }

    private fun preparationTimeout(): VpnStartCoordinator.StartDecision.Failure =
        VpnStartCoordinator.StartDecision.Failure(SocketTimeoutException("vpn_preparation_timeout"))
}
