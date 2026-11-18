package com.noki.vpn

import android.os.SystemClock
import com.noki.vpn.data.AuthRefreshRejectedException
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.RuForeignNode
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.clientLatencyTargetKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun AppUiRuntime.applyAndPersist(newState: AppUiState) {
    applyAndPersist(newState, SettingsEffect.None)
}

internal fun AppUiRuntime.applyAndPersist(newState: AppUiState, effect: SettingsEffect) {
    val permitted = AdvancedSettingsStateReducer.setYoutubeNoAdsEnabled(newState, newState.advancedSettings.youtubeNoAdsEnabled)
    val disableYoutube = uiState.advancedSettings.youtubeNoAdsEnabled &&
        !permitted.advancedSettings.youtubeNoAdsEnabled && permitted.userProfile.isFreePlan
    val stateToApply = withFreeTrafficLimitNotice(permitted)
    val prepareChanged = uiState.userProfile != stateToApply.userProfile || uiState.advancedSettings != stateToApply.advancedSettings
    uiState = stateToApply
    val result = settingsMutationCoordinator.persistUiFields(stateToApply, effect)
    when (result.effect) {
        SettingsEffect.None -> Unit
        SettingsEffect.ApplyRuntimeSettings -> vpnCommands.applyRuntimeSettings()
        SettingsEffect.StartAutomaticLogUpload -> maybeUploadLogsAutomatically()
    }
    if (disableYoutube && (stateToApply.connectionState == VpnConnectionState.CONNECTED ||
        stateToApply.connectionState == VpnConnectionState.CONNECTING)) vpnCommands.restart()
    if (prepareChanged || !stateToApply.isAuthenticated) prepareVpnInAdvance()
}

internal fun AppUiRuntime.persistBackendState(state: AppUiState) {
    repository.updateSettings { latest ->
        latest.copy(
            profile = state.profile,
            userProfile = state.userProfile,
            endpointOptions = state.endpointOptions,
            backendDeviceKey = backendDeviceKey,
            backendDeviceId = backendDeviceId,
            backendDeviceAccessRole = backendDeviceAccessRole.ifBlank { "owner" },
        )
    }
}

internal fun AppUiRuntime.persistDeviceSession() {
    repository.updateSettings { latest ->
        latest.copy(
            backendDeviceKey = backendDeviceKey,
            backendDeviceId = backendDeviceId,
            backendDeviceAccessRole = backendDeviceAccessRole.ifBlank { "owner" },
        )
    }
}

internal fun AppUiRuntime.persistLogoutState(state: AppUiState) {
    repository.updateSettings { latest ->
        latest.copy(
            profile = state.profile,
            userProfile = state.userProfile,
            backendDeviceKey = backendDeviceKey,
            backendDeviceId = backendDeviceId,
            backendDeviceAccessRole = backendDeviceAccessRole.ifBlank { "owner" },
        )
    }
}

internal fun AppUiRuntime.launchBackendRefresh(
    trigger: BackendRefreshTrigger,
    showNetworkFailureInline: Boolean = false,
    refreshClientLatency: Boolean = false,
) {
    if (avatarMutationJob != null) return
    if (trigger == BackendRefreshTrigger.UserRefresh && uiState.isRefreshingData) return
    if (trigger == BackendRefreshTrigger.Stats && uiState.isRefreshingData) return
    if (
        backendRefreshJob?.isActive == true &&
        !BackendRefreshArbitrationPolicy.shouldStart(backendRefreshTrigger, trigger)
    ) {
        return
    }
    val attempt = authSessionCoordinator.attempt() ?: return
    if (trigger == BackendRefreshTrigger.UserRefresh) {
        uiState = uiState.copy(isRefreshingData = true)
    } else if (trigger == BackendRefreshTrigger.Initial && uiState.isRefreshingData) {
        uiState = uiState.copy(isRefreshingData = false)
    }
    backendRefreshJob?.cancel()
    val owner = backendRefreshRequestTracker.next(attempt.epoch)
    backendRefreshTrigger = trigger
    val requestState = uiState
    val requestDeviceId = backendDeviceId
    val requestDeviceKey = backendDeviceKey
    val requestDeviceAccessRole = backendDeviceAccessRole
    val requestClientLatencies = clientLatencyByTarget
    val requestAndroidUpdateRevision = androidUpdateRevision
    backendRefreshJob = scope.launch {
        val apiStartedAt = SystemClock.elapsedRealtime()
        try {
            val result = authSessionCoordinator.run(attempt) { token ->
                backendSyncCoordinator.syncState(
                    BackendSyncRequest(
                        token = token,
                        baseState = requestState,
                        currentDeviceId = requestDeviceId,
                        currentDeviceKey = requestDeviceKey,
                        previousDeviceAccessRole = requestDeviceAccessRole,
                        clientLatencyByTarget = requestClientLatencies,
                    ),
                )
            }
            if (!isCurrentBackendRefresh(owner, attempt)) return@launch
            applyBackendSyncResult(
                result = result,
                trigger = trigger,
                refreshClientLatency = refreshClientLatency,
                apiStartedAt = apiStartedAt,
                preserveAndroidUpdate = requestAndroidUpdateRevision != androidUpdateRevision,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isCurrentBackendRefresh(owner, attempt)) return@launch
            handleBackendRefreshFailure(
                error = error,
                trigger = trigger,
                showNetworkFailureInline = showNetworkFailureInline,
                apiStartedAt = apiStartedAt,
            )
        } finally {
            if (backendRefreshRequestTracker.isCurrent(owner, attempt.epoch)) {
                backendRefreshJob = null
                backendRefreshTrigger = null
            }
        }
    }
}

internal fun AppUiRuntime.applyBackendSyncResult(
    result: BackendSyncResult,
    trigger: BackendRefreshTrigger,
    refreshClientLatency: Boolean,
    apiStartedAt: Long,
    preserveAndroidUpdate: Boolean,
) {
    applyBackendSyncMetadata(result)
    val merged = BackendSyncCoordinator.withCachedClientLatencies(
        state = BackendSyncStateReducer.apply(
            latest = uiState,
            patch = result.patch,
            preserveAndroidUpdate = preserveAndroidUpdate,
        ),
        clientLatencyByTarget = clientLatencyByTarget,
    )
    when (trigger) {
        BackendRefreshTrigger.Stats -> {
            val statsState = merged.copy(dailyStats = repository.loadDailyStats())
            if (statsState.userProfile.isFreePlan && statsState.advancedSettings.youtubeNoAdsEnabled) {
                persistBackendState(statsState)
                applyAndPersist(statsState)
            } else uiState = withFreeTrafficLimitNotice(statsState)
            maybeUploadLogsAutomatically()
            if (hasMissingClientLatency(merged.locations)) {
                refreshClientLatenciesAsync(merged.locations)
            }
        }

        BackendRefreshTrigger.UserRefresh -> {
            recordAppLog("backend", message = "bootstrap_success", apiResponseTimeMs = elapsedSince(apiStartedAt))
            persistBackendState(merged)
            applyAndPersist(merged.copy(isRefreshingData = false))
            startBackendPostSyncSideEffects(merged, refreshClientLatency)
        }

        BackendRefreshTrigger.Initial -> {
            recordAppLog("backend", message = "bootstrap_success", apiResponseTimeMs = elapsedSince(apiStartedAt))
            persistBackendState(merged)
            applyAndPersist(merged)
            startBackendPostSyncSideEffects(merged, refreshClientLatency = true)
        }
    }
}

internal fun AppUiRuntime.startBackendPostSyncSideEffects(
    state: AppUiState,
    refreshClientLatency: Boolean,
) {
    startAppNotificationPolling()
    syncFcmTokenIfAvailable()
    prepareVpnInAdvance()
    maybeUploadLogsAutomatically()
    if (refreshClientLatency || hasMissingClientLatency(state.locations)) {
        refreshClientLatenciesAsync(state.locations)
    }
}

internal fun AppUiRuntime.handleBackendRefreshFailure(
    error: Throwable,
    trigger: BackendRefreshTrigger,
    showNetworkFailureInline: Boolean,
    apiStartedAt: Long,
) {
    if (trigger != BackendRefreshTrigger.Stats) {
        recordAppLog(
            category = "backend",
            level = "error",
            message = "bootstrap_fail",
            details = error.message,
            errorType = AppErrorMapper.readableErrorType(error),
            apiResponseTimeMs = elapsedSince(apiStartedAt),
        )
    }
    if (error is AuthRefreshRejectedException || error is CurrentDeviceAccessRevokedException) {
        logout()
        return
    }
    if (trigger == BackendRefreshTrigger.UserRefresh) {
        uiState = uiState.copy(
            isRefreshingData = false,
            inlineMessage = if (showNetworkFailureInline) {
                AppErrorMapper.readableNetworkError(uiState.personalizationSettings.language, error)
            } else {
                uiState.inlineMessage
            },
        )
    }
}

internal fun AppUiRuntime.isCurrentBackendRefresh(
    owner: BackendSyncOwner,
    attempt: AuthSessionAttempt,
): Boolean {
    if (!authSessionCoordinator.isCurrent(attempt)) return false
    return backendRefreshRequestTracker.isCurrent(owner, attempt.epoch)
}

internal fun AppUiRuntime.cancelBackendRefresh() {
    backendRefreshRequestTracker.invalidate()
    backendRefreshJob?.cancel()
    backendRefreshJob = null
    backendRefreshTrigger = null
}

internal suspend fun AppUiRuntime.syncFromBackendState(
    token: String,
    baseState: AppUiState,
): AppUiState {
    val attempt = authSessionCoordinator.attempt()?.takeIf { it.accessToken == token }
    val result = try {
        backendSyncCoordinator.syncState(
            BackendSyncRequest(
                token = token,
                baseState = baseState,
                currentDeviceId = backendDeviceId,
                currentDeviceKey = backendDeviceKey,
                previousDeviceAccessRole = backendDeviceAccessRole,
                clientLatencyByTarget = clientLatencyByTarget,
            ),
        )
    } catch (revoked: CurrentDeviceAccessRevokedException) {
        if (attempt != null && authSessionCoordinator.isCurrent(attempt)) logout()
        throw revoked
    }
    applyBackendSyncMetadata(result)
    return BackendSyncStateReducer.apply(baseState, result.patch)
}

internal fun AppUiRuntime.applyBackendSyncMetadata(result: BackendSyncResult) {
    lastServerStatsRefreshElapsedMs = SystemClock.elapsedRealtime()
    syncedLocations = result.backendLocations
    syncedDevices = result.backendDevices
    syncedPlans = result.backendPlans
    backendDeviceAccessRole = result.patch.currentDeviceAccessRole
}

internal fun clientLatencyRequestTarget(
    locations: List<ServerLocation>,
    ruRelayEnabled: Boolean = false,
    foreignNodes: List<RuForeignNode> = emptyList(),
): List<String> {
    if (ruRelayEnabled) {
        return foreignNodes.map(::clientLatencyTargetKey).distinct().sorted()
    }
    return locations
        .asSequence()
        .filter { it.isOnline }
        .flatMap { location ->
            if (location.servers.isEmpty()) sequenceOf(clientLatencyTargetKey(location))
            else location.servers.asSequence().filter {
                it.isOnline
            }.map { server ->
                clientLatencyTargetKey(server)
            }
        }
        .filterNotNull()
        .sorted()
        .toList()
}

internal fun AppUiRuntime.refreshClientLatenciesAsync(
    locations: List<ServerLocation>,
    refreshCached: Boolean = false,
) {
    val attempt = authSessionCoordinator.attempt() ?: return
    val networkSignature = clientLatencySampler.networkSignature()
    val ruRelayEnabled = uiState.advancedSettings.ruRelayEnabled
    val foreignNodes = uiState.ruForeignNodes
    val target = clientLatencyRequestTarget(locations, ruRelayEnabled, foreignNodes)
    if (target.isEmpty()) return
    val activeJob = clientLatencyRefreshJob
    if (activeJob != null) {
        val sameNetwork = clientLatencyRefreshNetworkSignature == networkSignature
        if (clientLatencyRefreshTarget == target && sameNetwork && !refreshCached) return
        if (!sameNetwork || clientLatencyRefreshTarget != target || refreshCached) {
            clientLatencyByTarget = emptyMap()
            uiState = BackendSyncCoordinator.withCachedClientLatencies(uiState, emptyMap())
        }
        clientLatencyRefreshJob = null
        clientLatencyRefreshTarget = null
        activeJob.cancel()
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("Client latency coroutine has no Job")
        try {
            val measured = measureClientLatencies(locations, ruRelayEnabled, foreignNodes)
            if (
                clientLatencySampler.networkSignature() != networkSignature ||
                clientLatencyRefreshJob !== ownerJob ||
                !ownerJob.isActive ||
                !authSessionCoordinator.isCurrent(attempt) ||
                clientLatencyRequestTarget(uiState.locations, uiState.advancedSettings.ruRelayEnabled,
                    uiState.ruForeignNodes) != target
            ) {
                return@launch
            }
            clientLatencyByTarget = measured
            uiState = BackendSyncCoordinator.withCachedClientLatencies(uiState, measured)
            // Keep presentation samples fresh and owned by this physical network.
            val expiresAt = SystemClock.elapsedRealtime() + 30_000L
            while (SystemClock.elapsedRealtime() < expiresAt &&
                clientLatencySampler.networkSignature() == networkSignature
            ) kotlinx.coroutines.delay(1_000L)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (
                clientLatencyRefreshJob === ownerJob &&
                ownerJob.isActive &&
                authSessionCoordinator.isCurrent(attempt)
            ) {
                recordAppLog(
                    category = "backend",
                    level = "error",
                    message = "client_latency_refresh_failed",
                    details = error.message,
                    errorType = AppErrorMapper.readableErrorType(error),
                )
            }
        } finally {
            if (clientLatencyRefreshJob === ownerJob) {
                if (authSessionCoordinator.isCurrent(attempt)) {
                    clientLatencyByTarget = emptyMap()
                    uiState = BackendSyncCoordinator.withCachedClientLatencies(uiState, emptyMap())
                }
                clientLatencyRefreshJob = null
                clientLatencyRefreshTarget = null
                clientLatencyRefreshNetworkSignature = null
            }
        }
    }
    clientLatencyRefreshTarget = target
    clientLatencyRefreshNetworkSignature = networkSignature
    clientLatencyRefreshJob = job
    job.start()
}

internal fun AppUiRuntime.hasMissingClientLatency(locations: List<ServerLocation>): Boolean {
    if (uiState.advancedSettings.ruRelayEnabled) return uiState.ruForeignNodes.any {
        clientLatencyTargetKey(it) !in clientLatencyByTarget
    }
    return BackendSyncCoordinator.hasMissingClientLatency(locations, clientLatencyByTarget)
}

internal suspend fun AppUiRuntime.measureClientLatencies(
    locations: List<ServerLocation>,
    ruRelayEnabled: Boolean = uiState.advancedSettings.ruRelayEnabled,
    foreignNodes: List<RuForeignNode> = uiState.ruForeignNodes,
): Map<String, Int> {
    if (!ruRelayEnabled) return clientLatencySampler.measure(locations)
    return clientLatencySampler.measureNodes(foreignNodes, ::clientLatencyTargetKey,
        { it.probeHost }, { it.probePort }, { it.probeIp })
}
