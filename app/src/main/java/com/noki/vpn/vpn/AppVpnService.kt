package com.noki.vpn.vpn

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.noki.vpn.AppNotificationPoller
import com.noki.vpn.AppLogUploadCoordinator
import com.noki.vpn.NokiQuickSettingsTileService
import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.AuthTokenRefresher
import com.noki.vpn.data.AdvancedSettings
import com.noki.vpn.data.AndroidDeviceInfo
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppDiagnosticLogPolicy
import com.noki.vpn.data.BackendApiClient
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendRetryPolicy
import com.noki.vpn.data.BackendRuWireGuardSession
import com.noki.vpn.data.BackendVpnSession
import com.noki.vpn.data.DeviceTrafficMonitor
import com.noki.vpn.data.DeviceIdentity
import com.noki.vpn.data.WireGuardKeyStore
import com.noki.vpn.data.EndpointHealth
import com.noki.vpn.data.EndpointHealthEvent
import com.noki.vpn.data.EndpointHealthEventReporter
import com.noki.vpn.data.EndpointHealthEventType
import com.noki.vpn.data.EndpointHealthEvents
import com.noki.vpn.data.EndpointRankingPolicy
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.EndpointSelector
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.EndpointTransportPolicy
import com.noki.vpn.data.PendingLogoutRevocationWorker
import com.noki.vpn.data.PendingRuWireGuardRevoke
import com.noki.vpn.data.hasSameAuthSessionAs
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.TemporaryVpnLease
import com.noki.vpn.data.TemporaryVpnLeasePolicy
import com.noki.vpn.data.TemporaryVpnSessionCoordinator
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.VpnIncidentReport
import com.noki.vpn.data.VpnSessionSelection
import com.noki.vpn.data.VpnStartCoordinator
import com.noki.vpn.data.VpnProfileValidator
import com.noki.vpn.data.VpnProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AppVpnService : VpnService() {
    private lateinit var underlyingNetworkSource: AndroidUnderlyingNetworkSource
    private val backendApi = BackendApiClient()
    private val notificationHandler = Handler(Looper.getMainLooper())
    private lateinit var delayedTaskScheduler: DelayedTaskScheduler
    private lateinit var notificationController: VpnNotificationController
    private lateinit var notificationFactory: VpnNotificationFactory
    private lateinit var screenStateMonitor: VpnScreenStateMonitor
    private var connectedAtMillis: Long? = null
    private var tunnel: TunHandle?
        get() = connectionOrchestrator.currentTunnel()
        set(value) = connectionOrchestrator.replaceTunnel(value)
    private var currentState: VpnConnectionState
        get() = connectionOrchestrator.currentState()
        set(value) = connectionOrchestrator.updateState(value)
    private lateinit var statsCoordinator: VpnStatsCoordinator
    private var runtimeCoreSequence: Long = 0L
    private val readinessMutex = Mutex()
    private val wakeProbeInFlight = AtomicBoolean(false)
    private lateinit var warmupController: VpnWarmupController<BackendVpnSession>
    private lateinit var connectionPreparer: VpnConnectionPreparer
    private lateinit var temporaryVpnCoordinator: TemporaryVpnSessionCoordinator
    private lateinit var settingsStore: AtomicStoredSettingsStore
    private lateinit var connectionOrchestrator: VpnConnectionOrchestrator
    private lateinit var ruTunFactory: AndroidTunInterfaceFactory
    @Volatile private var ruSession: BackendRuWireGuardSession.Ready? = null
    private val ruStatusInFlight = AtomicBoolean(false)
    @Volatile private var lastRuStatusCheckElapsedMs = 0L
    private var backgroundJob: Job = SupervisorJob()
    private var backgroundScope: CoroutineScope = CoroutineScope(backgroundJob + Dispatchers.IO)
    private val destroyCleanupJob: Job = SupervisorJob()
    private val destroyCleanupScope: CoroutineScope = CoroutineScope(destroyCleanupJob + Dispatchers.IO)
    @Volatile
    private var pendingStartOptions: VpnServiceStartCommandPolicy.StartOptions? = null
    private var activeEndpointNetworkKind: EndpointRankingPolicy.NetworkKind = EndpointRankingPolicy.NetworkKind.OTHER
    private val activeUnderlyingNetworkSignature: String?
        get() = connectionOrchestrator.currentUnderlaySignature()
    private lateinit var networkMonitor: VpnNetworkMonitor
    private var lastUnderlyingAvailability: UnderlyingNetworkAvailability? = null
    private var notificationServerLabel: String = "Латвия"
    private var activeSettings: StoredSettings?
        get() = connectionOrchestrator.currentSettings()
        set(value) = connectionOrchestrator.updateSettings(value)
    private lateinit var endpointHealthController: VpnEndpointHealthController
    private lateinit var watchdogController: VpnWatchdogController
    private var lockdownRecoveryActive: Boolean = false
    private var currentRuntimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT
    private var activeTemporaryLease: TemporaryVpnLease? = null
    private val lockdownRecoveryOwner = Any()
    private val selectionReprepareOwner = Any()
    private val runtimeDomainReapplyOwner = Any()
    private val temporaryLeaseExpiryOwner = Any()
    private val transientRecoveryOwner = Any()
    private val youtubeConnection by lazy {
        YoutubeConnection.android(applicationContext, backgroundScope, underlyingNetworkSource, ::protect)
    }
    @Volatile private var transientRecoveryAttempt = 0
    @Volatile private var pendingNetworkRecovery: (() -> Unit)? = null
    private val lockdownRecoveryTask = {
        if (currentState == VpnConnectionState.FAILED && tunnel != null) {
            reconnectFailedClosedVpn(allowCachedFallback = true)
        }
    }
    private val selectionReprepareTask = {
        if (currentState == VpnConnectionState.CONNECTED && tunnel != null) {
            if (connectionOrchestrator.transitionSnapshot().active) {
                scheduleFreshSelectionPrepare()
            } else {
                refreshConnectedVpn(allowCachedFallback = true)
            }
        }
    }
    private val runtimeDomainReapplyTask = {
        if (currentState == VpnConnectionState.CONNECTED && tunnel != null) {
            applyRuntimeSettings()
        }
    }
    override fun onCreate() {
        super.onCreate()
        updateLiveRuntimeState(VpnConnectionState.DISCONNECTED, null)
        delayedTaskScheduler = HandlerDelayedTaskScheduler(notificationHandler)
        notificationFactory = VpnNotificationFactory(this, NOTIFICATION_ID)
        warmupController = VpnWarmupController(
            scheduler = delayedTaskScheduler,
            delayMillis = BACKGROUND_ENDPOINT_WARMUP_DELAY_MS,
        )
        notificationController = VpnNotificationController(
            scheduler = delayedTaskScheduler,
            speedUpdateIntervalMillis = NOTIFICATION_SPEED_UPDATE_INTERVAL_MS,
            pollIntervalMillis = APP_NOTIFICATION_POLL_INTERVAL_MS,
            updateActiveNotification = ::updateActiveNotification,
            pollNotifications = ::pollAppNotifications,
        )
        screenStateMonitor = VpnScreenStateMonitor(this, ::handleScreenStateChanged)
        val tunFactory = AndroidTunInterfaceFactory(this)
        ruTunFactory = tunFactory
        underlyingNetworkSource = AndroidUnderlyingNetworkSource(this)
        networkMonitor = VpnNetworkMonitor.android(
            context = this,
            source = underlyingNetworkSource,
            scheduler = delayedTaskScheduler,
            debounceMillis = NETWORK_CHANGE_DEBOUNCE_MS,
        )
        val connectionRepository = SettingsRepository(this)
        val startCoordinator = VpnStartCoordinator(
            context = applicationContext,
            repository = connectionRepository,
            startupTcpPrecheckTimeoutMs = STARTUP_TCP_PRECHECK_TIMEOUT_MS,
            onDiagnostic = { details ->
                connectionRepository.recordAppLog(
                    category = "vpn",
                    message = "profile_prepare_diagnostic",
                    details = details,
                )
            },
        )
        connectionPreparer = VpnConnectionPreparer(
            store = connectionRepository,
            currentNetworkKind = {
                underlyingNetworkSource.currentSnapshot()?.kind
                    ?: EndpointRankingPolicy.NetworkKind.OTHER
            },
            resolveStart = { token, settings, fallback, sessionSelection ->
                startCoordinator.resolveStart(
                    token = token,
                    settings = settings,
                    knownDevices = emptyList(),
                    allowCachedFallback = fallback,
                    sessionSelection = sessionSelection,
                )
            },
            refreshAccessToken = {
                try {
                    AuthTokenRefresher(
                        connectionRepository,
                        backendApi,
                        onRevocationPending = {
                            PendingLogoutRevocationWorker.enqueue(applicationContext)
                        },
                    ).refreshStoredTokens()?.accessToken
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            },
            retryCount = FRESH_SESSION_RETRY_COUNT,
            deadlineMillis = FRESH_SESSION_DEADLINE_MS,
            nowMillis = SystemClock::elapsedRealtime,
        )
        temporaryVpnCoordinator = TemporaryVpnSessionCoordinator(
            store = connectionRepository,
            api = backendApi,
            publicKeyProvider = DeviceIdentity::publicKeyBase64,
            deviceKeyProvider = { DeviceIdentity.stableDeviceKey(applicationContext) },
            deviceNameProvider = AndroidDeviceInfo::deviceName,
            platformProvider = { "android" },
            challengeSigner = DeviceIdentity::signChallenge,
            profileSelector = { session ->
                EndpointSelector.profileForSession(
                    context = applicationContext,
                    session = session,
                    settings = AdvancedSettings(),
                    directOnly = false,
                )
            },
        )
        settingsStore = connectionRepository
        statsCoordinator = VpnStatsCoordinator(
            repository = connectionRepository,
            scope = backgroundScope,
            scheduler = delayedTaskScheduler,
            measureConnectedLatencyMs = {
                if (currentState != VpnConnectionState.CONNECTED) null else {
                    measureRuntimeReadiness(recovery = false)
                        ?.coerceIn(1L, Int.MAX_VALUE.toLong())
                        ?.toInt()
                }
            },
            onLatencySample = { locationCode, latencyMs ->
                broadcastState(
                    state = currentState,
                    latencyLocationCode = locationCode,
                    latencyMs = latencyMs,
                )
            },
        )
        endpointHealthController = VpnEndpointHealthController(
            scheduler = delayedTaskScheduler,
            intervalMillis = EndpointHealthEvents.HEARTBEAT_INTERVAL_MS,
            launchHeartbeat = ::launchEndpointHealthHeartbeat,
        )
        watchdogController = VpnWatchdogController(
            scheduler = delayedTaskScheduler,
            nowMillis = SystemClock::elapsedRealtime,
            launchProbe = ::launchWatchdogProbe,
            isLockdown = {
                shouldBlockDirectTraffic()
            },
            evidenceFreshMillis = DATA_PATH_EVIDENCE_FRESH_MS,
        )
        val connectedSidecars = OwnedVpnConnectedSidecars(
            onStart = { owner, settings -> startConnectedSidecars(owner, settings) },
            onStop = { owner -> stopConnectedSidecars(owner) },
        )
        val controller = VpnCoreController(applicationContext, protectSocket = { protect(it) }) {
            startService(Intent(this, AppVpnService::class.java).setAction(ACTION_STOP))
        }
        connectionOrchestrator = VpnConnectionOrchestrator(
            core = controller,
            tunFactory = tunFactory,
            sidecars = connectedSidecars,
        )
        notificationController.setScreenOn(screenStateMonitor.isInteractive())
        screenStateMonitor.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SettingsRepository(this).recordAppLog(
                category = "vpn",
                message = "vpn_system_mode",
                details = "always_on=$isAlwaysOn; lockdown=$isLockdownEnabled",
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val temporaryStopDecision = VpnServiceStartCommandPolicy.temporaryStopDecision(
            action = intent?.action,
            runtimeMode = currentRuntimeMode,
        )
        if (intent?.action != ACTION_START_TEMPORARY && intent?.action != ACTION_STOP_AND_REVOKE_TEMPORARY) {
            backgroundScope.launch { temporaryVpnCoordinator.retryPendingRevoke() }
        }
        when (temporaryStopDecision) {
            VpnServiceStartCommandPolicy.TemporaryStopDecision.StopAndRevoke -> {
                stopVpn(startId, revokeTemporaryLease = true)
                return START_STICKY
            }
            VpnServiceStartCommandPolicy.TemporaryStopDecision.RevokeOnly -> {
                revokeTemporaryLeaseWithoutStoppingAccount(startId)
                return START_STICKY
            }
            VpnServiceStartCommandPolicy.TemporaryStopDecision.NotRequested -> Unit
        }
        when (intent?.action) {
            ACTION_STOP -> stopVpn(startId)
            ACTION_RESTART -> manualRestartVpn()
            ACTION_QUERY_STATE -> {
                broadcastCurrentState()
                if (currentState == VpnConnectionState.CONNECTED &&
                    intent.getBooleanExtra(EXTRA_REFRESH_LATENCY, false)
                ) statsCoordinator.refreshLatency()
            }
            ACTION_APPLY_SETTINGS -> applyRuntimeSettings()
            else -> {
                val options = VpnServiceStartCommandPolicy.startOptions(
                    isNullIntent = intent == null,
                    action = intent?.action,
                    refreshSessionExtra = intent?.getBooleanExtra(EXTRA_REFRESH_SESSION, false) == true,
                )
                val repository = SettingsRepository(this)
                val resumeTemporaryLease = intent == null &&
                    !repository.load().isAuthenticated &&
                    TemporaryVpnLeasePolicy.isUsable(
                        repository.loadTemporaryVpnLease(),
                    )
                if (options.runtimeMode == VpnRuntimeMode.AUTH_TEMP || resumeTemporaryLease) {
                    startTemporaryVpn()
                } else {
                    startVpn(
                        strategy = options.strategy,
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        screenStateMonitor.stop()
        stopNetworkChangeMonitor()
        pendingStartOptions = null
        cancelDelayedVpnCallbacks()
        val repository = SettingsRepository(this)
        connectionOrchestrator.shutdown(
            scope = destroyCleanupScope,
            releaseResources = {
                releaseVpnResources(
                    repository = repository,
                    finalState = VpnConnectionState.DISCONNECTED,
                    stopService = false,
                )
            },
            cancelBackgroundWork = backgroundJob::cancel,
        ).invokeOnCompletion {
            destroyCleanupJob.cancel()
        }
        // Deferred core cleanup may overlap a new service instance in the same process.
        youtubeConnection.stop()
        super.onDestroy()
    }

    private fun admitStart(options: VpnServiceStartCommandPolicy.StartOptions): Boolean {
        val transition = connectionOrchestrator.transitionSnapshot()
        if (transition.active) {
            if (VpnServiceStartCommandPolicy.activeStartDecision(transition.operation) ==
                VpnServiceStartCommandPolicy.ActiveStartDecision.QueueAfterCleanup
            ) {
                pendingStartOptions = options
            }
            broadcastState(currentState)
            return false
        }
        pendingStartOptions = null
        return true
    }

    private fun startVpn(
        strategy: VpnPreparationStrategy,
    ) {
        if (!admitStart(VpnServiceStartCommandPolicy.StartOptions(strategy))) return
        if (tunnel != null && currentState == VpnConnectionState.CONNECTED) {
            if (strategy.forceRefreshSession) {
                refreshConnectedVpn(allowCachedFallback = strategy.allowCachedFallback)
                return
            }
            broadcastCurrentState()
            updateActiveNotification()
            return
        }

        currentRuntimeMode = VpnRuntimeMode.ACCOUNT
        activeTemporaryLease = null
        announceConnecting(VpnRuntimeMode.ACCOUNT)
        val repository = SettingsRepository(this)
        repository.recordAppLog("vpn", message = "service_connect_start")
        launchConnectionTransition(repository) { generationId ->
            performFreshStart(
                repository = repository,
                strategy = strategy,
                generationId = generationId,
            )
        }
    }

    private fun startTemporaryVpn() {
        if (!admitStart(VpnServiceStartCommandPolicy.StartOptions(
                strategy = VpnPreparationStrategy.FreshOnly,
                runtimeMode = VpnRuntimeMode.AUTH_TEMP,
            ))) return
        if (tunnel != null && currentState == VpnConnectionState.CONNECTED) {
            if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                broadcastCurrentState()
                updateActiveNotification()
            }
            return
        }

        currentRuntimeMode = VpnRuntimeMode.AUTH_TEMP
        announceConnecting(VpnRuntimeMode.AUTH_TEMP)
        val repository = SettingsRepository(this)
        repository.recordAppLog("vpn", message = "temporary_vpn_connect_start")
        launchConnectionTransition(repository) { generationId ->
            performTemporaryStart(repository, generationId)
        }
    }

    private suspend fun performTemporaryStart(
        repository: SettingsRepository,
        generationId: Long,
    ) {
        GeositeAsset.ensureBundledFallback(applicationContext)
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) return
        val lease = try {
            temporaryVpnCoordinator.prepare()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reason = if (error is BackendException && error.statusCode == 429) {
                "temporary_vpn_limit"
            } else {
                "temporary_vpn_prepare_error"
            }
            failStart(repository, reason, error)
            return
        }
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) return
        val baseline = repository.load()
        val settings = baseline.copy(
            profile = lease.profile,
            filterMode = AppFilterMode.ALL_APPS,
            selectedPackages = emptySet(),
            advancedSettings = AdvancedSettings(),
            endpointOptions = emptyList(),
            userProfile = baseline.userProfile.copy(selectedServerCode = lease.locationCode),
            isAuthenticated = false,
            backendAccessToken = null,
            backendRefreshToken = null,
            backendRefreshRequestId = null,
            backendAccessTokenExpiresInSeconds = null,
            backendRefreshExpiresAt = null,
        )
        activeTemporaryLease = lease
        startTunnel(
            repository = repository,
            settings = settings,
            preparationBaseline = baseline,
            preparedMetadata = null,
            generationId = generationId,
            runtimeMode = VpnRuntimeMode.AUTH_TEMP,
            persistSettings = false,
        )
    }

    private fun announceConnecting(runtimeMode: VpnRuntimeMode = currentRuntimeMode) {
        currentRuntimeMode = runtimeMode
        broadcastState(VpnConnectionState.CONNECTING)
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = "Подключение",
                text = "Запуск VPN...",
                showActions = true,
            ),
        )
    }

    private suspend fun performFreshStart(
        repository: SettingsRepository,
        strategy: VpnPreparationStrategy,
        generationId: Long,
    ) {
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) return
        if (repository.load().advancedSettings.ruRelayEnabled) {
            GeositeAsset.ensureBundledFallback(applicationContext)
            performRuWireGuardStart(repository, generationId)
            return
        }
        val prepared = coroutineScope {
            val geosite = async(Dispatchers.Default) { GeositeAsset.ensureBundledFallback(applicationContext) }
            val connection = prepareConnectionSettings(
                repository = repository,
                strategy = strategy,
                destructiveOnFailure = true,
            )
            geosite.await()
            connection
        } ?: return
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) return
        val cachedStart = !strategy.forceRefreshSession && prepared.pendingWarmupSession == null
        if (cachedStart) {
            repository.recordAppLog(
                category = "vpn",
                message = "handover_cached_start",
                details = "endpoint=${prepared.candidateSettings.profile.endpointCode}",
                serverCountry = notificationServerLabel,
            )
        }
        startTunnel(
            repository = repository,
            settings = normalizeInstalledPackages(prepared.candidateSettings),
            preparationBaseline = prepared.preparationBaseline,
            preparedMetadata = prepared,
            generationId = generationId,
        )
        if (cachedStart && currentState == VpnConnectionState.CONNECTED &&
            connectionOrchestrator.isCurrent(generationId)
        ) {
            repository.recordAppLog(
                category = "vpn",
                message = "handover_cached_success",
                details = "endpoint=${prepared.candidateSettings.profile.endpointCode}",
                serverCountry = notificationServerLabel,
                connectionSuccess = true,
            )
        }
    }

    private suspend fun performRuWireGuardStart(
        repository: SettingsRepository,
        generationId: Long,
    ) {
        var baseline = repository.load()
        val prepared = try {
            val physicalNetwork = underlyingNetworkSource.currentObservation().candidate?.network
                ?: throw IllegalStateException("underlay_unavailable")
            val preparationApi = backendApi.onNetwork(physicalNetwork)
            val coordinator = RuWireGuardConnectionCoordinator(
                applicationContext, repository, preparationApi, physicalNetwork,
            )
            try {
                coordinator.prepare(baseline)
            } catch (expired: BackendException) {
                if (expired.statusCode != 401) throw expired
                val refreshed = AuthTokenRefresher(repository, preparationApi,
                    onRevocationPending = { PendingLogoutRevocationWorker.enqueue(applicationContext) },
                ).refreshStoredTokens()
                check(refreshed != null) { "auth_required" }
                baseline = repository.load()
                coordinator.prepare(baseline)
            }
        } catch (cancelled: CancellationException) {
            runCatching {
                if (repository.recoverProvisionalRuWireGuardRevokes(
                    activeSessionId = ruSession?.identity?.sessionId, preparationActive = false,
                )) PendingRuWireGuardRevokeWorker.enqueue(applicationContext)
            }
            throw cancelled
        } catch (error: Throwable) {
            if (connectionOrchestrator.isCurrent(generationId)) {
                failStart(repository, error.message ?: "ru_wireguard_prepare_failed", error)
            }
            return
        }
        var activated = false
        try {
            if (!connectionOrchestrator.isCurrent(generationId)) return
            connectionOrchestrator.withLifecycleLock {
                if (!connectionOrchestrator.isCurrent(generationId)) return@withLifecycleLock
                if (VpnSettingsTransactionPolicy.candidateBecameStale(baseline, repository.load())) {
                    failStart(repository, "selection_changed")
                    return@withLifecycleLock
                }
                val settings = normalizeInstalledPackages(repository.load())
                val underlay = underlyingNetworkSource.currentSnapshot()
                val established = try {
                    ruTunFactory.establishWireGuard(settings, underlay, prepared.session.wireguard)
                } catch (error: TunInterfaceConfigurationException) {
                    failStart(repository, error.reason, error)
                    return@withLifecycleLock
                }
                if (established == null) {
                    failStart(repository, "ru_wireguard_interface_error")
                    return@withLifecycleLock
                }
                connectionOrchestrator.stopCore()
                val previous = tunnel
                tunnel = established
                if (previous !== established) previous?.close()
                connectionOrchestrator.updateUnderlay(underlay)
                try {
                    val config = VpnConfigFactory.buildWireGuard(prepared.session.wireguard,
                        prepared.privateKey, settings.advancedSettings, GeositeAsset.readSnapshot(applicationContext),
                        youtubeConnection = youtubeConnection.configFor(settings))
                    if (!connectionOrchestrator.startCore(config)) {
                        failStart(repository, "ru_wireguard_runtime_start_failed")
                        return@withLifecycleLock
                    }
                    val readiness = measureRuntimeReadiness(recovery = false)
                    if (!connectionOrchestrator.isCurrent(generationId)) return@withLifecycleLock
                    if (!VpnReadinessPolicy.accept(readiness)) {
                        failStart(repository, "ru_wireguard_exit_probe_failed")
                        return@withLifecycleLock
                    }
                    val commit = commitSettingsTransaction(
                        preparationBaseline = baseline,
                        candidate = settings.copy(userProfile = settings.userProfile.copy(
                            selectedServerCode = prepared.exitNode.id,
                            actualCountryCode = prepared.exitNode.countryCode,
                        )),
                        result = VpnSettingsTransactionPolicy.Result.Accepted,
                    )
                    if (!commit.acceptedForActivation) {
                        failStart(repository, "selection_changed")
                        return@withLifecycleLock
                    }
                    val connectedSettings = commit.runtime
                    currentRuntimeMode = VpnRuntimeMode.ACCOUNT
                    ruSession = prepared.session
                    activeEndpointNetworkKind = underlay?.kind ?: EndpointRankingPolicy.NetworkKind.OTHER
                    notificationServerLabel = VpnServiceLogContext.serverLabel(
                        prepared.exitNode.countryCode, prepared.exitNode.name,
                        connectedSettings.personalizationSettings.language,
                    )
                    connectedAtMillis = System.currentTimeMillis()
                    val owner = connectionOrchestrator.activateConnected(
                        generationId = generationId,
                        coreId = ++runtimeCoreSequence,
                        tunnel = established,
                        settings = connectedSettings,
                        underlay = underlay,
                    ) ?: return@withLifecycleLock
                    activated = true
                    statsCoordinator.recordInitialLatency(connectedSettings, owner, readiness)
                    broadcastState(VpnConnectionState.CONNECTED)
                    runCatching { repository.clearProvisionalRuWireGuardRevoke(
                        prepared.session.identity.sessionId,
                        checkNotNull(prepared.session.identity.revokeToken),
                    ) }
                        .onFailure { error -> repository.recordAppLog("vpn", level = "error",
                            message = "ru_wireguard_handoff_clear_failed", errorType = error.javaClass.simpleName) }
                    startForeground(NOTIFICATION_ID, createActiveNotification())
                    repository.recordAppLog("vpn", message = "ru_wireguard_connected",
                        details = "exit_node=${prepared.exitNode.id}", connectionSuccess = true)
                } finally {
                    if (!activated) {
                        connectionOrchestrator.stopCore()
                        ruSession = null
                        if (tunnel === established && !shouldBlockDirectTraffic()) {
                            tunnel = null
                            established.close()
                        }
                    }
                }
            }
        } finally {
            try {
                if (!activated) stageRuWireGuardRevoke(repository, prepared.session)
            } finally {
                prepared.privateKey.fill(0)
            }
        }
    }

    private fun launchConnectionTransition(
        repository: SettingsRepository,
        block: suspend (Long) -> Unit,
    ) {
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.CONNECTION,
            onError = { generationId, error ->
                handleTransitionException(repository, generationId, error)
            },
            block = block,
        )
    }

    private suspend fun handleTransitionException(
        repository: SettingsRepository,
        generationId: Long,
        error: Throwable,
    ) {
        if (!connectionOrchestrator.isCurrent(generationId)) return
        connectionOrchestrator.withLifecycleLock {
            if (!connectionOrchestrator.isCurrent(generationId)) return@withLifecycleLock
            SafeLog.e(TAG, "VPN lifecycle transition failed", error)
            if (ConnectedWatchdogPolicy.transitionExceptionDecision(isActiveLockdownRecovery()) ==
                ConnectedWatchdogPolicy.TransitionExceptionDecision.KeepTruthfulLockdownFailure &&
                tunnel != null
            ) {
                connectionOrchestrator.stopCore()
                enterTruthfulLockdownFailure(repository, "transition_error")
                return@withLifecycleLock
            }
            failStart(repository, "transition_error", error)
        }
    }

    private suspend fun prepareConnectionSettings(
        repository: SettingsRepository,
        strategy: VpnPreparationStrategy,
        destructiveOnFailure: Boolean,
        sessionSelection: VpnSessionSelection? = null,
        incidentId: String? = null,
        onFailure: (Throwable) -> Unit = {},
    ): PreparedVpnSession? {
        val startedAtMs = SystemClock.elapsedRealtime()
        return when (val outcome = connectionPreparer.prepare(
            strategy = strategy,
            sessionSelection = sessionSelection,
            onRetry = { attempt, error ->
                repository.recordAppLog(
                    category = "vpn",
                    message = "fresh_session_retry",
                    details = incidentDetails(
                        incidentId,
                        "attempt=$attempt; error_type=${prepareFailureReason(error)}",
                    ),
                )
            },
            onCachedFallback = { error ->
                repository.recordAppLog(
                    category = "vpn",
                    level = "error",
                    message = "connect_fail",
                    details = incidentDetails(incidentId, error.message),
                    errorType = prepareFailureReason(error),
                    apiResponseTimeMs = elapsedSince(startedAtMs),
                    connectionSuccess = false,
                )
                repository.recordAppLog(
                    category = "vpn",
                    message = "cached_profile_start",
                    details = "fresh_session_error=${prepareFailureReason(error)}",
                    endpointRating = endpointRatingSnapshot(repository, repository.load()),
                )
            },
        )) {
            is VpnConnectionPreparer.Outcome.Success -> outcome.session.also { prepared ->
                prepared.pendingWarmupSession?.let { result ->
                    repository.recordAppLog(
                        category = "vpn",
                        message = "endpoint_selected",
                        details = "endpoint=${result.selection.endpointCode}",
                        endpointRating = result.selection.endpointRating,
                    )
                    repository.recordAppLog(
                        category = "vpn",
                        message = "vpn_session_created",
                        apiResponseTimeMs = elapsedSince(startedAtMs),
                        endpointRating = repository.endpointRatingSnapshot(
                            result.settings.endpointOptions.map { it.code },
                            result.selection.networkKind,
                        ),
                    )
                }
            }
            is VpnConnectionPreparer.Outcome.Failure -> {
                val error = outcome.error
                onFailure(error)
                if (error.message != "auth_required" && error.message != "unusable_profile") {
                    repository.recordAppLog(
                        category = "vpn",
                        level = "error",
                        message = "connect_fail",
                        details = incidentDetails(incidentId, error.message),
                        errorType = prepareFailureReason(error),
                        apiResponseTimeMs = elapsedSince(startedAtMs),
                        connectionSuccess = false,
                    )
                }
                if (error.message == "unusable_profile") {
                    Log.e(TAG, "Backend returned unusable VPN profile")
                }
                handlePrepareFailure(
                    repository = repository,
                    reason = prepareFailureReason(error),
                    error = error,
                    destructiveOnFailure = destructiveOnFailure,
                )
                null
            }
        }
    }

    private suspend fun acceptPreparedMetadata(
        repository: SettingsRepository,
        prepared: PreparedVpnSession,
    ) {
        activeEndpointNetworkKind = prepared.selectedNetworkKind
        prepared.pendingWarmupSession?.let { result ->
            recordStartupTcpPrecheck(repository, result.selection, result.settings)
            warmupController.setPending(result.session)
        }
    }

    private fun handlePrepareFailure(
        repository: SettingsRepository,
        reason: String,
        error: Throwable?,
        destructiveOnFailure: Boolean,
    ) {
        if (reason == "client_update_required") {
            failStart(repository, reason, error)
            return
        }
        if (destructiveOnFailure) {
            if (currentRuntimeMode == VpnRuntimeMode.ACCOUNT &&
                error != null && BackendRetryPolicy.isTransient(error)
            ) {
                failClosedAfterReplacementFailure(repository, repository.load(), reason)
                return
            }
            failStart(repository, reason, error, retryBlockedTunnel = false)
            return
        }
        repository.recordAppLog(
            category = "vpn",
            level = "error",
            message = "refresh_prepare_failed",
            details = error?.message,
            errorType = reason,
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
        )
        val desired = repository.load()
        if (desired.advancedSettings.multiHop.enabled &&
            (activeSettings?.advancedSettings?.multiHop != desired.advancedSettings.multiHop ||
                activeSettings?.let { VpnProfileValidator.isUsable(it) } != true)
        ) {
            failClosedAfterReplacementFailure(repository, desired, reason)
            return
        }
        if (currentState == VpnConnectionState.CONNECTED && tunnel != null) {
            broadcastState(VpnConnectionState.CONNECTED)
            updateActiveNotification()
        } else if (currentState == VpnConnectionState.FAILED && tunnel != null) {
            if (error != null && !BackendRetryPolicy.isTransient(error)) {
                failStart(repository, reason, error, retryBlockedTunnel = false)
                return
            }
            if (error != null && BackendRetryPolicy.isTransient(error)) {
                failClosedAfterReplacementFailure(repository, activeSettings ?: repository.load(), reason)
                return
            }
            if (ConnectedWatchdogPolicy.retryFailureDecision(
                    isLockdown = isActiveLockdownRecovery(),
                ) == ConnectedWatchdogPolicy.RetryFailureDecision.KeepTruthfulFailedAndReschedule
            ) {
                enterTruthfulLockdownFailure(repository, reason)
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(
                        title = "VPN error",
                        text = "VPN engine failed; traffic remains blocked until reconnect or disconnect.",
                        showActions = true,
                    ),
                )
                broadcastState(VpnConnectionState.FAILED, reason)
            }
        }
    }

    private fun elapsedSince(startedAtMs: Long): Long {
        return (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
    }

    private fun normalizeInstalledPackages(settings: StoredSettings): StoredSettings {
        val routingRules = AppVpnRoutingPolicy.rules(
            appPackageName = packageName,
            filterMode = settings.filterMode,
            selectedPackages = settings.selectedPackages,
            isInstalled = { candidate ->
                runCatching { packageManager.getApplicationInfo(candidate, 0) }.isSuccess
            },
        )
        return if (routingRules.removedPackages.isEmpty()) {
            settings
        } else {
            settings.copy(selectedPackages = settings.selectedPackages - routingRules.removedPackages)
        }
    }

    private fun commitSettingsTransaction(
        preparationBaseline: StoredSettings,
        candidate: StoredSettings,
        result: VpnSettingsTransactionPolicy.Result,
    ): VpnSettingsTransactionPolicy.RuntimeCommitOutcome {
        val outcome = settingsStore.updateAndReturn { persisted ->
            VpnSettingsTransactionPolicy.commitRuntimeCandidate(
                previousRuntime = activeSettings ?: preparationBaseline,
                preparationBaseline = preparationBaseline,
                candidate = candidate,
                result = result,
                persisted = persisted,
            ).let { it.persisted to it }
        }
        activeSettings = outcome.runtime
        if (outcome.requiresFreshPrepare) scheduleFreshSelectionPrepare()
        return outcome
    }

    private fun scheduleFreshSelectionPrepare() {
        delayedTaskScheduler.schedule(
            owner = selectionReprepareOwner,
            delayMillis = RUNTIME_SETTINGS_RETRY_DELAY_MS,
            task = selectionReprepareTask,
        )
    }

    private fun restoreRuntimeDomainSettings(
        repository: SettingsRepository,
        previousSettings: StoredSettings,
        candidateSettings: StoredSettings,
    ) {
        val outcome = repository.updateAndReturn { persisted ->
            VpnSettingsTransactionPolicy.rollbackRuntimeDomains(
                previous = previousSettings,
                candidate = candidateSettings,
                persisted = persisted,
            ).let { it.persisted to it }
        }
        activeSettings = outcome.runtime
        if (outcome.requiresReapply) scheduleRuntimeDomainReapply()
    }

    private fun scheduleRuntimeDomainReapply() {
        delayedTaskScheduler.schedule(
            owner = runtimeDomainReapplyOwner,
            delayMillis = RUNTIME_SETTINGS_RETRY_DELAY_MS,
            task = runtimeDomainReapplyTask,
        )
    }

    private fun cancelDelayedVpnCallbacks() {
        pendingNetworkRecovery = null
        delayedTaskScheduler.cancel(selectionReprepareOwner)
        delayedTaskScheduler.cancel(runtimeDomainReapplyOwner)
        delayedTaskScheduler.cancel(lockdownRecoveryOwner)
        delayedTaskScheduler.cancel(temporaryLeaseExpiryOwner)
        delayedTaskScheduler.cancel(transientRecoveryOwner)
        transientRecoveryAttempt = 0
        lockdownRecoveryActive = false
    }

    private fun scheduleTransientRecovery(
        repository: SettingsRepository,
        settings: StoredSettings,
        incidentId: String?,
    ) {
        val attempt = transientRecoveryAttempt
        val delayMillis = ConnectedWatchdogPolicy.transientRetryDelayMillis(attempt)
        transientRecoveryAttempt = (attempt + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        repository.recordAppLog(
            category = "vpn",
            message = "control_plane_retry_scheduled",
            details = incidentDetails(
                incidentId,
                "attempt=${attempt + 1}; delay_ms=$delayMillis; network_kind=${activeEndpointNetworkKind.name.lowercase(Locale.ROOT)}",
            ),
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
        )
        scheduleAccountRecovery(transientRecoveryOwner, delayMillis) {
            val startDecision = ConnectedWatchdogPolicy.transientStartDecision(attempt)
            when {
                currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP -> Unit
                currentState == VpnConnectionState.CONNECTED && tunnel != null -> recoverConnectedVpn(settings)
                currentState == VpnConnectionState.FAILED && tunnel != null -> {
                    reconnectFailedClosedVpn(
                        allowCachedFallback = startDecision.allowCachedFallback,
                    )
                }
                currentState == VpnConnectionState.FAILED || currentState == VpnConnectionState.DISCONNECTED -> {
                    startVpn(
                        strategy = startDecision,
                    )
                }
            }
        }
    }

    private fun scheduleAccountRecovery(owner: Any, delayMillis: Long, task: () -> Unit) {
        val generationId = connectionOrchestrator.currentGenerationId()
        val authSession = settingsStore.load()
        delayedTaskScheduler.cancel(transientRecoveryOwner)
        delayedTaskScheduler.cancel(lockdownRecoveryOwner)
        pendingNetworkRecovery = null
        // Lockdown sidecars have been paused; keep observing the physical network.
        startNetworkChangeMonitor(activeEndpointNetworkKind)
        fun resume() {
            pendingNetworkRecovery = null
            if (!connectionOrchestrator.isCurrent(generationId) ||
                currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP ||
                !settingsStore.load().hasSameAuthSessionAs(authSession)
            ) return
            if (underlyingNetworkSource.currentSnapshot() == null) {
                pendingNetworkRecovery = ::resume
                return
            }
            task()
        }
        delayedTaskScheduler.schedule(owner, delayMillis, ::resume)
    }

    private fun resetTransientRecovery() {
        pendingNetworkRecovery = null
        delayedTaskScheduler.cancel(transientRecoveryOwner)
        transientRecoveryAttempt = 0
    }

    private suspend fun startTunnel(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        preparedMetadata: PreparedVpnSession?,
        generationId: Long,
        runtimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
        persistSettings: Boolean = true,
    ) = connectionOrchestrator.withLifecycleLock {
        if (!connectionOrchestrator.isCurrent(generationId) ||
            currentState != VpnConnectionState.CONNECTING
        ) {
            return@withLifecycleLock
        }
        if (runtimeMode == VpnRuntimeMode.ACCOUNT &&
            VpnSettingsTransactionPolicy.candidateBecameStale(preparationBaseline, settingsStore.load())) {
            failStart(repository, "selection_changed")
            return@withLifecycleLock
        }
        currentRuntimeMode = runtimeMode
        preparedMetadata?.let { acceptPreparedMetadata(repository, it) }
        notificationServerLabel = runtimeServerLabel(settings)
        val underlyingSnapshot = underlyingNetworkSource.currentSnapshot()
        recordDiagnostic(
            repository = repository,
            message = "tunnel_start",
            details = buildString {
                append("generation=").append(generationId)
                append("; runtime_mode=").append(runtimeMode.name.lowercase(Locale.ROOT))
                append("; endpoint=").append(settings.profile.endpointCode)
                append("; proxy_type=").append(settings.profile.proxyType)
                append("; transport=").append(settings.profile.transport)
                append("; transport_mode=").append(settings.profile.transportMode)
                append("; security=").append(settings.profile.security)
                append("; port=").append(settings.profile.port)
                append("; network_kind=").append(activeEndpointNetworkKind.name.lowercase(Locale.ROOT))
                append("; underlay=").append(underlyingSnapshot?.details.orEmpty())
            },
        )


        val established = try {
            connectionOrchestrator.establishTunnel(settings, underlyingSnapshot)
        } catch (error: TunInterfaceConfigurationException) {
            SafeLog.e(TAG, "Failed to apply per-app VPN rules", error)
            failStart(repository, error.reason, error)
            return@withLifecycleLock
        }
        if (established == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            failStart(repository, "interface_error")
            return@withLifecycleLock
        }
        val previousTunnel = tunnel
        tunnel = established
        if (previousTunnel !== established) previousTunnel?.close()
        if (!connectionOrchestrator.isCurrent(generationId)) {
            if (!shouldBlockDirectTraffic()) {
                tunnel = null
                runCatching { established.close() }
            }
            return@withLifecycleLock
        }
        connectionOrchestrator.updateUnderlay(underlyingSnapshot)
        recordDiagnostic(
            repository = repository,
            message = "tun_established",
            details = "generation=$generationId; underlay=${underlyingSnapshot?.details.orEmpty()}",
        )
        underlyingSnapshot?.let { snapshot ->
            repository.recordAppLog(
                category = "vpn",
                message = "vpn_underlying_networks",
                details = snapshot.details,
                serverCountry = notificationServerLabel,
            )
        }
        var fallbackSession = preparedMetadata?.pendingWarmupSession?.session?.takeIf {
            runtimeMode == VpnRuntimeMode.ACCOUNT &&
                settings.advancedSettings.endpointSelectionMode == EndpointSelectionMode.AUTO
        }
        val excludedCodes = preparedMetadata?.pendingWarmupSession?.selection
            ?.precheckFailedEndpointCodes.orEmpty().toMutableSet()
        val excludedNodeIds = mutableSetOf<String>()
        val attemptedTransportGroups = mutableSetOf<String>()
        var excludeNokiNv = false
        var attemptSettings = settings
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!connectionOrchestrator.isCurrent(generationId)) return@withLifecycleLock
            if (runtimeMode == VpnRuntimeMode.ACCOUNT &&
                VpnSettingsTransactionPolicy.candidateBecameStale(preparationBaseline, settingsStore.load())) {
                failStart(repository, "selection_changed")
                return@withLifecycleLock
            }
            excludedCodes += attemptSettings.profile.endpointCode
            attemptedTransportGroups += EndpointGroupPolicy.groupKey(attemptSettings.profile)
            val config = VpnConfigFactory.build(
                attemptSettings.profile,
                attemptSettings.advancedSettings,
                GeositeAsset.readSnapshot(applicationContext),
                youtubeConnection = youtubeConnection.configFor(attemptSettings),
            )
            val started = connectionOrchestrator.startCore(config)
            recordDiagnostic(
                repository = repository,
                message = "vpn_core_start_result",
                details = "generation=$generationId; endpoint=${attemptSettings.profile.endpointCode}; started=$started; config_chars=${config.length}",
                level = if (started) "info" else "error",
            )
            if (!connectionOrchestrator.isCurrent(generationId)) {
                connectionOrchestrator.stopCore()
                if (!shouldBlockDirectTraffic()) {
                    if (tunnel === established) tunnel = null
                    runCatching { established.close() }
                }
                return@withLifecycleLock
            }
            var readinessCompleted = false
            val readinessLatencyMs = try {
                (if (started) measureRuntimeReadiness(recovery = true,
                    fastStartup = !attemptSettings.advancedSettings.ruRelayEnabled &&
                        !attemptSettings.advancedSettings.multiHop.enabled) else null)
                    .also { readinessCompleted = true }
            } finally {
                if (!readinessCompleted) {
                    connectionOrchestrator.stopCore()
                    if (!shouldBlockDirectTraffic()) {
                        if (tunnel === established) tunnel = null
                        runCatching { established.close() }
                    }
                }
            }
            if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
                connectionOrchestrator.stopCore()
                if (!shouldBlockDirectTraffic()) {
                    if (tunnel === established) tunnel = null
                    runCatching { established.close() }
                }
                return@withLifecycleLock
            }
            val failureReason = VpnReadinessPolicy.failureReason(started, readinessLatencyMs)?.code
            if (failureReason == null) {
                finishTunnelConnected(
                    repository = repository,
                    settings = attemptSettings,
                    preparationBaseline = preparationBaseline,
                    generationId = generationId,
                    resetConnectedAt = true,
                    readinessLatencyMs = readinessLatencyMs,
                    persistSettings = persistSettings,
                    runtimeMode = runtimeMode,
                )
                return@withLifecycleLock
            }

            Log.e(TAG, "VPN core connection failed: $failureReason")
            connectionOrchestrator.stopCore()
            if (runtimeMode != VpnRuntimeMode.ACCOUNT) {
                failStart(repository, failureReason, category = "vpn_core")
                return@withLifecycleLock
            }
            val health = repository.recordEndpointResult(
                endpointCode = attemptSettings.profile.endpointCode,
                success = false,
                networkKind = activeEndpointNetworkKind,
            )
            val failedSettings = attemptSettings
            val failedNetworkKind = activeEndpointNetworkKind
            backgroundScope.launch {
                recordEndpointHealthEvent(
                    repository = repository,
                    settings = failedSettings,
                    endpointCode = failedSettings.profile.endpointCode,
                    networkKind = failedNetworkKind,
                    eventType = EndpointHealthEventType.CONNECT_FAIL,
                    success = false,
                    slow = false,
                    health = health,
                )
            }
            if (settings.advancedSettings.effectiveProtocol == VpnProtocol.AUTO &&
                attemptSettings.profile.proxyType.equals("nokinv", true)) excludeNokiNv = true
            val remaining = fallbackSession?.endpointCandidates.orEmpty()
                .filterNot { it.code in excludedCodes || excludeNokiNv && it.proxyType.equals("nokinv", true) }
            val untriedTransports = remaining.filter { EndpointGroupPolicy.groupKey(it) !in attemptedTransportGroups }
            val nextSelection = if (remaining.isNotEmpty() && fallbackSession != null) {
                EndpointSelector.selectionForSession(
                    context = this@AppVpnService,
                    session = fallbackSession.copy(endpointCandidates = untriedTransports.ifEmpty { remaining }),
                    settings = settings.advancedSettings,
                    endpointHealth = repository.loadEndpointHealth(activeEndpointNetworkKind),
                    rotationIndex = repository::nextEndpointRotationIndex,
                    networkKind = activeEndpointNetworkKind,
                )
            } else null
            val nextSettings = nextSelection?.let { settings.copy(profile = it.profile) }
            if (nextSettings != null &&
                nextSettings.profile.endpointCode !in excludedCodes &&
                VpnProfileValidator.isUsable(nextSettings)
            ) {
                repository.recordAppLog(
                    category = "vpn",
                    message = "startup_endpoint_fallback",
                    details = "from=${attemptSettings.profile.endpointCode}; to=${nextSettings.profile.endpointCode}; reason=$failureReason",
                )
                attemptSettings = nextSettings
                continue
            }
            if (settings.advancedSettings.multiHop.enabled) {
                failClosedAfterReplacementFailure(repository, attemptSettings, failureReason)
                return@withLifecycleLock
            }
            val failedNodeId = fallbackSession?.endpointCandidates?.firstOrNull { it.code == attemptSettings.profile.endpointCode }?.nodeId
            if (failedNodeId != null) excludedNodeIds += failedNodeId
            if (failedNodeId != null && settings.userProfile.serverSelectionMode != com.noki.vpn.data.ServerSelectionMode.SERVER) {
                var nextNodeFailure: Throwable? = null
                val nextNode = prepareConnectionSettings(
                    repository = repository,
                    strategy = VpnPreparationStrategy.FreshOnly,
                    destructiveOnFailure = false,
                    sessionSelection = VpnSessionSelection(
                        countryCode = settings.userProfile.selectedCountryCode,
                        excludedNodeIds = excludedNodeIds.toSet(),
                        excludeNokiNv = excludeNokiNv,
                    ),
                    onFailure = { nextNodeFailure = it },
                )
                if (!connectionOrchestrator.isCurrent(generationId)) return@withLifecycleLock
                if (nextNode == null && nextNodeFailure?.let(::prepareFailureReason) == "client_update_required") {
                    return@withLifecycleLock
                }
                if (nextNode != null) {
                    attemptSettings = normalizeInstalledPackages(nextNode.candidateSettings)
                    fallbackSession = nextNode.pendingWarmupSession?.session
                    excludedCodes.clear()
                    attemptedTransportGroups.clear()
                    excludedCodes += nextNode.pendingWarmupSession?.selection?.precheckFailedEndpointCodes.orEmpty()
                    acceptPreparedMetadata(repository, nextNode)
                    notificationServerLabel = runtimeServerLabel(attemptSettings)
                    continue
                }
            }
            failClosedAfterReplacementFailure(
                repository = repository,
                settings = attemptSettings,
                reason = failureReason,
            )
            return@withLifecycleLock
        }
    }

    private fun refreshConnectedVpn(allowCachedFallback: Boolean) {
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            restartTemporaryVpn()
            return
        }
        if (ruSession != null || settingsStore.load().advancedSettings.ruRelayEnabled) {
            restartVpn(VpnPreparationStrategy.FreshOnly)
            return
        }
        val activeTunnel = tunnel
        if (activeTunnel == null || currentState != VpnConnectionState.CONNECTED) {
            startVpn(strategy = if (allowCachedFallback) VpnPreparationStrategy.FreshWithCachedFallback else VpnPreparationStrategy.FreshOnly,)
            return
        }
        if (connectionOrchestrator.transitionSnapshot().active) {
            broadcastCurrentState()
            return
        }
        val repository = SettingsRepository(this)
        val previousSettings = activeSettings ?: endpointHealthController.currentSettings() ?: repository.load()
        val previousNetworkKind = activeEndpointNetworkKind
        repository.recordAppLog("vpn", message = "service_refresh_start")
        launchConnectionTransition(repository) { generationId ->
            val prepared = prepareConnectionSettings(
                repository = repository,
                strategy = if (allowCachedFallback) VpnPreparationStrategy.FreshWithCachedFallback else VpnPreparationStrategy.FreshOnly,
                destructiveOnFailure = false,
            ) ?: return@launchConnectionTransition
            val settings = normalizeInstalledPackages(prepared.candidateSettings)
            if (!connectionOrchestrator.isCurrent(generationId)) return@launchConnectionTransition
            replaceCoreOnExistingTunnel(
                repository = repository,
                settings = settings,
                preparationBaseline = prepared.preparationBaseline,
                previousSettings = previousSettings,
                previousNetworkKind = previousNetworkKind,
                activeTunnel = activeTunnel,
                generationId = generationId,
                preparedMetadata = prepared,
            )
        }
    }

    private fun recoverConnectedVpn(failedSettings: StoredSettings) {
        val activeTunnel = tunnel ?: return
        if (currentState != VpnConnectionState.CONNECTED ||
            connectionOrchestrator.transitionSnapshot().active
        ) {
            return
        }
        val repository = SettingsRepository(this)
        val previousSettings = activeSettings ?: endpointHealthController.currentSettings() ?: failedSettings
        if (!ConnectedWatchdogPolicy.automaticRecoveryAllowed(previousSettings.userProfile.serverSelectionMode)) return
        val previousNetworkKind = activeEndpointNetworkKind
        val countryCode = previousSettings.userProfile.selectedCountryCode
        val failedLocationCode = previousSettings.userProfile.selectedServerCode
        val targets = ConnectedWatchdogPolicy.recoveryTargets(failedLocationCode)
        val failedNodeIds = mutableSetOf<String>()
        previousSettings.endpointOptions.firstOrNull { it.code == previousSettings.profile.endpointCode }
            ?.nodeId?.takeIf(String::isNotBlank)?.let(failedNodeIds::add)
        val incidentId = UUID.randomUUID().toString()
        launchConnectionTransition(repository) { generationId ->
            repository.recordAppLog(
                category = "vpn",
                message = "watchdog_cached_attempt",
                details = "incident_id=$incidentId; attempt=1; country=$countryCode; location=$failedLocationCode",
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
            )
            var cachedPrepareFailure: Throwable? = null
            val cachedPrepared = prepareConnectionSettings(
                repository = repository,
                strategy = VpnPreparationStrategy.CachedFirst,
                destructiveOnFailure = false,
                incidentId = incidentId,
                onFailure = { cachedPrepareFailure = it },
            )
            if (cachedPrepared == null && cachedPrepareFailure?.let(::prepareFailureReason) == "client_update_required") {
                return@launchConnectionTransition
            }
            if (cachedPrepared == null && cachedPrepareFailure?.let(BackendRetryPolicy::isTransient) == true) {
                scheduleTransientRecovery(repository, previousSettings, incidentId)
                return@launchConnectionTransition
            }
            if (cachedPrepared != null) {
                val recovered = replaceCoreOnExistingTunnel(
                    repository = repository,
                    settings = normalizeInstalledPackages(cachedPrepared.candidateSettings),
                    preparationBaseline = cachedPrepared.preparationBaseline,
                    previousSettings = previousSettings,
                    previousNetworkKind = previousNetworkKind,
                    activeTunnel = activeTunnel,
                    generationId = generationId,
                    preparedMetadata = cachedPrepared,
                    rollbackOnFailure = false,
                )
                if (recovered) {
                    completeWatchdogRecovery(
                        repository = repository,
                        prepared = cachedPrepared,
                        incidentId = incidentId,
                        countryCode = countryCode,
                        failedLocationCode = failedLocationCode,
                        attempt = 1,
                        cached = true,
                    )
                    return@launchConnectionTransition
                }
            }
            val attempts = targets.asSequence().flatMap { target ->
                List(target.attempts) { target }
            }
            for ((index, target) in attempts.withIndex()) {
                val attempt = index + 2
                repository.recordAppLog(
                    category = "vpn",
                    message = "watchdog_recovery_attempt",
                    details = "incident_id=$incidentId; attempt=$attempt; country=$countryCode; location=${target.locationCode.orEmpty()}; exclude=${target.excludeLocationCode.orEmpty()}",
                    serverCountry = notificationServerLabel,
                    connectionSuccess = false,
                )
                var prepareFailure: Throwable? = null
                val prepared = prepareConnectionSettings(
                    repository = repository,
                    strategy = VpnPreparationStrategy.FreshOnly,
                    destructiveOnFailure = false,
                    sessionSelection = VpnSessionSelection(
                        countryCode = countryCode,
                        locationCode = target.locationCode,
                        excludeLocationCode = target.excludeLocationCode.takeIf {
                            failedNodeIds.isEmpty() && previousSettings.userProfile.serverSelectionMode == com.noki.vpn.data.ServerSelectionMode.AUTO
                        },
                        excludedNodeIds = if (target.excludeLocationCode != null) failedNodeIds.toSet() else emptySet(),
                    ),
                    incidentId = incidentId,
                    onFailure = { prepareFailure = it },
                )
                if (prepared == null) {
                    if (prepareFailure?.let(::prepareFailureReason) == "client_update_required") {
                        return@launchConnectionTransition
                    }
                    if (prepareFailure?.let(BackendRetryPolicy::isTransient) == true) {
                        if (currentState == VpnConnectionState.CONNECTED) {
                            scheduleTransientRecovery(repository, previousSettings, incidentId)
                        }
                        return@launchConnectionTransition
                    }
                    continue
                }
                if (!connectionOrchestrator.isCurrent(generationId)) return@launchConnectionTransition
                val recovered = replaceCoreOnExistingTunnel(
                    repository = repository,
                    settings = normalizeInstalledPackages(prepared.candidateSettings),
                    preparationBaseline = prepared.preparationBaseline,
                    previousSettings = previousSettings,
                    previousNetworkKind = previousNetworkKind,
                    activeTunnel = activeTunnel,
                    generationId = generationId,
                    preparedMetadata = prepared,
                    rollbackOnFailure = false,
                )
                if (recovered) {
                    completeWatchdogRecovery(
                        repository = repository,
                        prepared = prepared,
                        incidentId = incidentId,
                        countryCode = countryCode,
                        failedLocationCode = failedLocationCode,
                        attempt = attempt,
                        cached = false,
                    )
                    return@launchConnectionTransition
                }
                prepared.candidateSettings.let { candidate ->
                    candidate.endpointOptions.firstOrNull { it.code == candidate.profile.endpointCode }
                        ?.nodeId?.takeIf(String::isNotBlank)?.let(failedNodeIds::add)
                }
            }
            repository.recordAppLog(
                category = "vpn",
                level = "error",
                message = "watchdog_recovery_exhausted",
                details = "incident_id=$incidentId; attempts=${targets.sumOf { it.attempts } + 1}; location=$failedLocationCode",
                errorType = "runtime_readiness_probe_failed",
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
            )
            enqueueVpnIncident(
                repository = repository,
                settings = previousSettings,
                incident = VpnIncidentReport(
                    id = incidentId,
                    reason = "runtime_readiness_probe_failed",
                    countryCode = countryCode,
                    locationCode = failedLocationCode,
                    recoveryAttempts = targets.sumOf { it.attempts } + 1,
                    outcome = "failed",
                    occurredAt = Instant.now().toString(),
                ),
            )
            connectionOrchestrator.withLifecycleLock {
                if (connectionOrchestrator.isCurrent(generationId) && tunnel === activeTunnel) {
                    failClosedAfterReplacementFailure(
                        repository = repository,
                        settings = previousSettings,
                        reason = "watchdog_recovery_attempts_exhausted",
                    )
                }
            }
        }
    }

    private suspend fun completeWatchdogRecovery(
        repository: SettingsRepository,
        prepared: PreparedVpnSession,
        incidentId: String,
        countryCode: String,
        failedLocationCode: String,
        attempt: Int,
        cached: Boolean,
    ) {
        resetTransientRecovery()
        repository.recordAppLog(
            category = "vpn",
            message = if (cached) "watchdog_cached_success" else "watchdog_recovery_success",
            details = "incident_id=$incidentId; attempt=$attempt; location=${prepared.candidateSettings.userProfile.selectedServerCode}",
            serverCountry = notificationServerLabel,
            connectionSuccess = true,
        )
        enqueueVpnIncident(
            repository = repository,
            settings = prepared.candidateSettings,
            incident = VpnIncidentReport(
                id = incidentId,
                reason = "runtime_readiness_probe_failed",
                countryCode = countryCode,
                locationCode = failedLocationCode,
                recoveryAttempts = attempt,
                outcome = "recovered",
                occurredAt = Instant.now().toString(),
            ),
        )
        uploadPendingVpnIncidents(repository, prepared.candidateSettings)
    }

    private fun enqueueVpnIncident(
        repository: SettingsRepository,
        settings: StoredSettings,
        incident: VpnIncidentReport,
    ) {
        repository.enqueueVpnIncident(incident, settings)
    }

    private suspend fun uploadPendingVpnIncidents(
        repository: SettingsRepository,
        settings: StoredSettings,
    ) {
        if (!AppDiagnosticLogPolicy.shouldUploadAutomatically(settings.advancedSettings)) return
        val token = settings.backendAccessToken?.takeIf(String::isNotBlank) ?: return
        val coordinator = AppLogUploadCoordinator(
            exportLogs = repository::exportAppLogs,
            shouldUploadAutomatically = { false },
            markAutomaticallyUploaded = {},
            upload = { request ->
                val current = repository.load()
                if (!current.hasSameAuthSessionAs(settings) ||
                    !AppDiagnosticLogPolicy.shouldUploadAutomatically(current.advancedSettings)
                ) throw CancellationException("Diagnostic upload no longer permitted")
                backendApi.uploadAppLogs(
                    token = request.token,
                    deviceId = request.deviceId,
                    deviceKey = request.deviceKey,
                    deviceName = request.deviceName,
                    logsText = request.logsText,
                    incident = request.incident,
                )
            },
        )
        val context = AppLogUploadCoordinator.DeviceContext(
            token = token,
            deviceId = settings.backendDeviceId.takeIf(String::isNotBlank),
            deviceKey = settings.backendDeviceKey.takeIf(String::isNotBlank),
            deviceName = AndroidDeviceInfo.deviceName(),
        )
        for (incident in repository.loadPendingVpnIncidents()) {
            val uploaded = try {
                coordinator.uploadIncident(context, incident)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!uploaded) return
            repository.removePendingVpnIncident(incident.id)
        }
    }

    private fun applyRuntimeSettings() {
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            broadcastCurrentState()
            return
        }
        val activeTunnel = tunnel
        if (activeTunnel == null || currentState != VpnConnectionState.CONNECTED) {
            broadcastCurrentState()
            return
        }
        if (connectionOrchestrator.transitionSnapshot().active) {
            scheduleRuntimeDomainReapply()
            return
        }
        val repository = SettingsRepository(this)
        val persisted = repository.load()
        val previousSettings = activeSettings ?: endpointHealthController.currentSettings() ?: persisted
        val candidate = previousSettings.copy(
            advancedSettings = previousSettings.advancedSettings.copy(
                alwaysOnDomains = persisted.advancedSettings.alwaysOnDomains,
                bypassDomains = persisted.advancedSettings.bypassDomains,
            ),
        )
        if (candidate.advancedSettings == previousSettings.advancedSettings) {
            broadcastCurrentState()
            return
        }
        val previousNetworkKind = activeEndpointNetworkKind
        launchConnectionTransition(repository) { generationId ->
            replaceCoreOnExistingTunnel(
                repository = repository,
                settings = candidate,
                preparationBaseline = previousSettings,
                previousSettings = previousSettings,
                previousNetworkKind = previousNetworkKind,
                activeTunnel = activeTunnel,
                generationId = generationId,
                runtimeSettingsOnly = true,
            )
        }
    }

    private suspend fun replaceCoreOnExistingTunnel(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        previousSettings: StoredSettings,
        previousNetworkKind: EndpointRankingPolicy.NetworkKind,
        activeTunnel: TunHandle,
        generationId: Long,
        preparedMetadata: PreparedVpnSession? = null,
        runtimeSettingsOnly: Boolean = false,
        rollbackOnFailure: Boolean = true,
        underlay: UnderlyingNetworkSnapshot? = null,
        canActivate: () -> Boolean = { true },
    ): Boolean = connectionOrchestrator.withLifecycleLock {
        if (!canActivate() || !connectionOrchestrator.isCurrent(generationId) ||
            tunnel !== activeTunnel ||
            (currentState != VpnConnectionState.CONNECTED &&
                (rollbackOnFailure || currentState != VpnConnectionState.FAILED))
        ) {
            return@withLifecycleLock false
        }
        val previousUnderlay = connectionOrchestrator.currentUnderlay()
        if (preparedMetadata != null) {
            acceptPreparedMetadata(repository, preparedMetadata)
        }

        connectionOrchestrator.pauseConnectedSidecars()
        val nextConfig = buildActiveRuntimeConfig(settings)

        broadcastState(VpnConnectionState.CONNECTING, "runtime_replacement_in_progress")
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = "VPN переподключается",
                text = "Проверяем новое подключение. Трафик через VPN временно заблокирован.",
                showActions = true,
            ),
        )
        connectionOrchestrator.stopCore()
        val nextStarted = connectionOrchestrator.startCore(nextConfig)
        var nextReadinessCompleted = false
        val nextReadiness = try {
            (if (nextStarted) measureRuntimeReadiness(recovery = true) else null)
                .also { nextReadinessCompleted = true }
        } finally {
            if (!nextReadinessCompleted) connectionOrchestrator.stopCore()
        }
        if (!canActivate() || !connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
            connectionOrchestrator.stopCore()
            return@withLifecycleLock false
        }
        if (nextStarted && VpnReadinessPolicy.accept(nextReadiness)) {
            if (!canActivate() || !connectionOrchestrator.isCurrent(generationId)) {
                connectionOrchestrator.stopCore()
                return@withLifecycleLock false
            }
            val connectedSettings = if (runtimeSettingsOnly) {
                val outcome = repository.updateAndReturn { persisted ->
                    VpnSettingsTransactionPolicy.acceptRuntimeDomains(
                        candidate = settings,
                        persisted = persisted,
                    ).let { it.persisted to it }
                }
                outcome.also {
                    activeSettings = outcome.runtime
                    if (outcome.requiresReapply) scheduleRuntimeDomainReapply()
                }.runtime
            } else {
                settings
            }
            finishTunnelConnected(
                repository,
                connectedSettings,
                preparationBaseline = preparationBaseline,
                generationId = generationId,
                resetConnectedAt = true,
                readinessLatencyMs = nextReadiness,
                persistSettings = !runtimeSettingsOnly,
                activationKind = ConnectedActivationKind.EXISTING_TUN,
                underlay = underlay ?: previousUnderlay,
            )
            return@withLifecycleLock true
        }

        val failedHealth = repository.recordEndpointResult(
            endpointCode = settings.profile.endpointCode,
            success = false,
            networkKind = activeEndpointNetworkKind,
        )
        recordEndpointHealthEvent(
            repository = repository,
            settings = settings,
            endpointCode = settings.profile.endpointCode,
            networkKind = activeEndpointNetworkKind,
            eventType = EndpointHealthEventType.CONNECT_FAIL,
            success = false,
            slow = false,
            health = failedHealth,
        )

        val desiredSelectionChanged = VpnSettingsTransactionPolicy.candidateBecameStale(
            previousSettings, repository.load(),
        )
        if (!canActivate() || !rollbackOnFailure || desiredSelectionChanged ||
            (previousSettings.advancedSettings.multiHop.enabled && !VpnProfileValidator.isUsable(previousSettings))) {
            broadcastState(
                VpnConnectionState.FAILED,
                checkNotNull(VpnReadinessPolicy.failureReason(nextStarted, nextReadiness)).code,
            )
            connectionOrchestrator.stopCore()
            warmupController.clear()
            activeEndpointNetworkKind = previousNetworkKind
            notificationServerLabel = runtimeServerLabel(previousSettings)
            return@withLifecycleLock false
        }

        activeEndpointNetworkKind = previousNetworkKind
        notificationServerLabel = runtimeServerLabel(previousSettings)
        connectionOrchestrator.stopCore()
        val previousConfig = buildActiveRuntimeConfig(previousSettings)
        val previousStarted = connectionOrchestrator.startCore(previousConfig)
        var previousReadinessCompleted = false
        val previousReadiness = try {
            (if (previousStarted) measureRuntimeReadiness(recovery = true) else null)
                .also { previousReadinessCompleted = true }
        } finally {
            if (!previousReadinessCompleted) connectionOrchestrator.stopCore()
        }
        if (!canActivate() || !connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
            connectionOrchestrator.stopCore()
            return@withLifecycleLock false
        }
        if (previousStarted && VpnReadinessPolicy.accept(previousReadiness)) {
            if (!canActivate() || !connectionOrchestrator.isCurrent(generationId)) {
                connectionOrchestrator.stopCore()
                return@withLifecycleLock false
            }
            warmupController.clear()
            if (runtimeSettingsOnly) {
                restoreRuntimeDomainSettings(repository, previousSettings, settings)
            } else {
                commitSettingsTransaction(
                    preparationBaseline = preparationBaseline,
                    candidate = settings,
                    result = VpnSettingsTransactionPolicy.Result.RolledBack,
                )
            }
            val owner = checkNotNull(
                connectionOrchestrator.activateConnected(
                    generationId = generationId,
                    coreId = ++runtimeCoreSequence,
                    tunnel = checkNotNull(tunnel),
                    settings = previousSettings,
                    underlay = previousUnderlay,
                    kind = ConnectedActivationKind.ROLLBACK,
                ),
            )
            statsCoordinator.recordInitialLatency(previousSettings, owner, previousReadiness)
            broadcastState(VpnConnectionState.CONNECTED)
            startForeground(NOTIFICATION_ID, createActiveNotification())
            repository.recordAppLog(
                category = "vpn",
                level = "error",
                message = "service_refresh_rollback",
                details = "failed_endpoint=${settings.profile.endpointCode}; restored_endpoint=${previousSettings.profile.endpointCode}",
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
                endpointRating = endpointRatingSnapshot(repository, settings),
            )
            return@withLifecycleLock true
        }

        if (runtimeSettingsOnly) {
            restoreRuntimeDomainSettings(repository, previousSettings, settings)
        }
        val failureReason = checkNotNull(
            VpnReadinessPolicy.failureReason(previousStarted, previousReadiness),
        ).code
        failClosedAfterReplacementFailure(
            repository = repository,
            settings = settings,
            reason = failureReason,
        )
        false
    }

    private fun buildActiveRuntimeConfig(settings: StoredSettings): String {
        val youtube = youtubeConnection.configFor(settings)
        val session = ruSession ?: return VpnConfigFactory.build(settings.profile, settings.advancedSettings,
            GeositeAsset.readSnapshot(applicationContext), youtubeConnection = youtube)
        val key = WireGuardKeyStore.android(applicationContext).getOrCreate()
        return try {
            VpnConfigFactory.buildWireGuard(session.wireguard, key.privateKey,
                settings.advancedSettings, GeositeAsset.readSnapshot(applicationContext), youtubeConnection = youtube)
        } finally {
            key.privateKey.fill(0)
        }
    }

    private suspend fun finishTunnelConnected(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        generationId: Long,
        resetConnectedAt: Boolean,
        readinessLatencyMs: Long?,
        persistSettings: Boolean = true,
        activationKind: ConnectedActivationKind = ConnectedActivationKind.NEW_TUN,
        runtimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
        underlay: UnderlyingNetworkSnapshot? = connectionOrchestrator.currentUnderlay(),
    ) {
        currentRuntimeMode = runtimeMode
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) resetTransientRecovery()
        val connectedSettings = if (persistSettings) {
            val commit = commitSettingsTransaction(
                preparationBaseline = preparationBaseline,
                candidate = settings,
                result = VpnSettingsTransactionPolicy.Result.Accepted,
            )
            if (!commit.acceptedForActivation) {
                connectionOrchestrator.stopCore()
                failStart(repository, "selection_changed")
                return
            }
            commit.runtime
        } else {
            activeSettings = settings
            settings
        }
        notificationServerLabel = runtimeServerLabel(connectedSettings)
        if (resetConnectedAt || connectedAtMillis == null) {
            connectedAtMillis = System.currentTimeMillis()
        }
        val owner = checkNotNull(
            connectionOrchestrator.activateConnected(
                generationId = generationId,
                coreId = ++runtimeCoreSequence,
                tunnel = checkNotNull(tunnel),
                settings = connectedSettings,
                underlay = underlay,
                kind = activationKind,
            ),
        )
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) {
            statsCoordinator.recordInitialLatency(connectedSettings, owner, readinessLatencyMs)
        }
        broadcastState(VpnConnectionState.CONNECTED)
        startForeground(NOTIFICATION_ID, createActiveNotification())
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) {
            val health = repository.recordEndpointResult(
                endpointCode = connectedSettings.profile.endpointCode,
                success = true,
                latencyMs = readinessLatencyMs,
                networkKind = activeEndpointNetworkKind,
            )
            recordEndpointHealthEvent(
                repository = repository,
                settings = connectedSettings,
                endpointCode = connectedSettings.profile.endpointCode,
                networkKind = activeEndpointNetworkKind,
                eventType = EndpointHealthEventType.CONNECT_SUCCESS,
                success = true,
                slow = false,
                health = health,
            )
        }
        repository.recordAppLog(
            category = "vpn",
            message = if (runtimeMode == VpnRuntimeMode.AUTH_TEMP) {
                "temporary_vpn_connect_success"
            } else {
                "service_connect_success"
            },
            details = "server=${connectedSettings.userProfile.selectedServerCode}",
            serverCountry = notificationServerLabel,
            connectionSuccess = true,
            endpointRating = endpointRatingSnapshot(repository, connectedSettings),
        )
        if (runtimeMode == VpnRuntimeMode.ACCOUNT) {
            backgroundScope.launch {
                uploadPendingVpnIncidents(repository, connectedSettings)
            }
        }
    }

    private suspend fun recordStartupTcpPrecheck(
        repository: SettingsRepository,
        selection: EndpointSelector.EndpointSelectionResult,
        settings: StoredSettings,
    ) {
        val failedCodes = selection.precheckFailedEndpointCodes
        if (failedCodes.isEmpty()) return
        if (selection.precheckShouldPenalizeFailures) {
            failedCodes.forEach { endpointCode ->
                val health = repository.recordEndpointResult(
                    endpointCode = endpointCode,
                    success = false,
                    networkKind = selection.networkKind,
                )
                recordEndpointHealthEvent(
                    repository = repository,
                    settings = settings,
                    endpointCode = endpointCode,
                    networkKind = selection.networkKind,
                    eventType = EndpointHealthEventType.STARTUP_PRECHECK_FAIL,
                    success = false,
                    slow = false,
                    health = health,
                )
            }
        } else {
            failedCodes.forEach { endpointCode ->
                recordEndpointHealthEvent(
                    repository = repository,
                    settings = settings,
                    endpointCode = endpointCode,
                    networkKind = selection.networkKind,
                    eventType = EndpointHealthEventType.STARTUP_PRECHECK_FAIL,
                    success = false,
                    slow = false,
                    health = repository.loadEndpointHealth(selection.networkKind)[endpointCode],
                )
            }
        }
        val serverLabel = VpnServiceLogContext.serverLabel(settings)
        repository.recordAppLog(
            category = "vpn",
            level = "error",
            message = VpnCoreIssue.PROXY_TCP_TIMEOUT.logMessage,
            details = "source=startup_precheck; failed_count=${failedCodes.size}; selected=${selection.endpointCode}; penalty=${selection.precheckShouldPenalizeFailures}",
            errorType = VpnCoreIssue.PROXY_TCP_TIMEOUT.logMessage,
            serverCountry = serverLabel,
            connectionSuccess = false,
            endpointRating = repository.endpointRatingSnapshot(failedCodes + selection.endpointCode, selection.networkKind),
        )
        repository.recordAppLog(
            category = "vpn",
            level = if (selection.precheckShouldPenalizeFailures) "info" else "warning",
            message = if (selection.precheckShouldPenalizeFailures) {
                "endpoint_startup_tcp_precheck_fallback"
            } else {
                "endpoint_startup_tcp_precheck_unreachable"
            },
            details = "failed_tcp=${failedCodes.joinToString(",")}; selected=${selection.endpointCode}; penalty=${selection.precheckShouldPenalizeFailures}",
            serverCountry = serverLabel,
            endpointRating = repository.endpointRatingSnapshot(failedCodes + selection.endpointCode, selection.networkKind),
        )
    }

    private suspend fun recordEndpointHealthEvent(
        repository: SettingsRepository,
        settings: StoredSettings,
        endpointCode: String,
        networkKind: EndpointRankingPolicy.NetworkKind,
        eventType: EndpointHealthEventType,
        success: Boolean,
        slow: Boolean,
        health: EndpointHealth?,
    ) {
        EndpointHealthEventReporter(repository).recordEvent(
            settings = settings,
            event = EndpointHealthEvent(
                endpointCode = endpointCode,
                networkKind = networkKind,
                eventType = eventType,
                success = success,
                slow = slow,
                scoreBucket = EndpointHealthEvents.scoreBucket(health),
            ),
        )
    }

    private fun launchEndpointHealthHeartbeat(
        owner: RuntimeOwner,
        settings: StoredSettings,
    ): CancelableTask {
        val job = backgroundScope.launch {
            if (currentState != VpnConnectionState.CONNECTED ||
                !endpointHealthController.accepts(owner)
            ) {
                return@launch
            }
            val repository = SettingsRepository(this@AppVpnService)
            if (watchdogController.hasFreshVpnEvidence() &&
                endpointHealthController.accepts(owner)
            ) {
                EndpointHealthEventReporter(repository).recordHeartbeatIfDue(
                    settings = settings,
                    endpointCode = settings.profile.endpointCode,
                    networkKind = activeEndpointNetworkKind,
                    health = repository.loadEndpointHealth(activeEndpointNetworkKind)[settings.profile.endpointCode],
                )
            }
        }
        return CancelableTask(job::cancel)
    }

    private fun startConnectedWatchdog(settings: StoredSettings, owner: RuntimeOwner) {
        stopConnectedWatchdog()
        lockdownRecoveryActive = false
        val activeTunnel = tunnel ?: return
        watchdogController.start(
            owner = ConnectedWatchdogPolicy.Owner(
                generationId = owner.generationId,
                tunnelIdentity = System.identityHashCode(activeTunnel),
                coreIdentity = owner.coreId,
            ),
            settings = settings,
            initialVpnEvidence = true,
        )
    }

    private fun stopConnectedWatchdog() {
        watchdogController.stop(null)
        delayedTaskScheduler.cancel(lockdownRecoveryOwner)
    }

    private fun launchWatchdogProbe(
        owner: ConnectedWatchdogPolicy.Owner,
        settings: StoredSettings,
    ): CancelableTask? {
        val networkSignature = underlyingNetworkSource.currentSnapshot()?.signature ?: return null
        if (currentState != VpnConnectionState.CONNECTED ||
            tunnel == null ||
            activeUnderlyingNetworkSignature != networkSignature ||
            lastUnderlyingAvailability == UnderlyingNetworkAvailability.None ||
            lastUnderlyingAvailability == UnderlyingNetworkAvailability.Unvalidated ||
            connectionOrchestrator.transitionSnapshot().active ||
            watchdogController.currentSettings()?.profile?.endpointCode != settings.profile.endpointCode ||
            !watchdogController.accepts(owner)
        ) {
            return null
        }
        val job = backgroundScope.launch {
            val latencyMs = measureRuntimeReadiness(recovery = false, watchdog = true)
            if (!currentCoroutineContext().isActive) return@launch
            connectionOrchestrator.withLifecycleLock {
                if (!currentCoroutineContext().isActive ||
                    !connectionOrchestrator.isCurrent(owner.generationId) ||
                    tunnel?.let(System::identityHashCode) != owner.tunnelIdentity ||
                    !watchdogController.accepts(owner)
                ) {
                    return@withLifecycleLock
                }
                // The physical network can change before its debounced callback arrives.
                if (underlyingNetworkSource.currentSnapshot()?.signature != networkSignature) {
                    watchdogController.resetNetworkEvidence()
                    return@withLifecycleLock
                }
                val outcome = watchdogController.completeProbe(owner, latencyMs)
                if (outcome == WatchdogProbeOutcome.Ignored) return@withLifecycleLock
                if (outcome == WatchdogProbeOutcome.Healthy) {
                    latencyMs?.let { latency ->
                        SettingsRepository(this@AppVpnService).recordEndpointResult(
                            endpointCode = settings.profile.endpointCode,
                            success = true,
                            latencyMs = latency,
                            networkKind = activeEndpointNetworkKind,
                        )
                    }
                }
                when (val action = (outcome as? WatchdogProbeOutcome.Unhealthy)?.action ?: WatchdogAction.None) {
                    WatchdogAction.None -> Unit
                    WatchdogAction.Refresh -> recoverConnectedVpn(settings)
                    WatchdogAction.ReleaseOutsideLockdown -> {
                        connectionOrchestrator.invalidate()
                        connectionOrchestrator.stopCore()
                        failClosedAfterReplacementFailure(
                            repository = SettingsRepository(this@AppVpnService),
                            settings = settings,
                            reason = ConnectedWatchdogPolicy.failureReason(isLockdown = false),
                        )
                    }
                    is WatchdogAction.EnterLockdownFailure -> {
                        val repository = SettingsRepository(this@AppVpnService)
                        connectionOrchestrator.stopCore()
                        watchdogController.stop(owner)
                        enterTruthfulLockdownFailure(
                            repository = repository,
                            reason = ConnectedWatchdogPolicy.failureReason(isLockdown = true),
                            retryDelayMillis = action.retryDelayMillis,
                        )
                    }
                }
            }
        }
        return CancelableTask(job::cancel)
    }

    private fun isActiveLockdownRecovery(): Boolean {
        return lockdownRecoveryActive && shouldBlockDirectTraffic()
    }

    private fun shouldBlockDirectTraffic(): Boolean =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled) ||
            (currentRuntimeMode == VpnRuntimeMode.ACCOUNT &&
                SettingsRepository(this).load().advancedSettings.killSwitchEnabled)

    private fun enterTruthfulLockdownFailure(
        repository: SettingsRepository,
        reason: String,
        retryDelayMillis: Long? = watchdogController.lockdownRetryDelayMillis(),
    ) {
        connectionOrchestrator.pauseConnectedSidecars()
        lockdownRecoveryActive = retryDelayMillis != null
        repository.recordAppLog(
            category = "vpn",
            level = "error",
            message = reason,
            details = "direct_traffic_blocked=true",
            errorType = reason,
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
        )
        broadcastState(VpnConnectionState.FAILED, reason)
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = "VPN недоступен",
                text = if (retryDelayMillis != null) {
                    "Трафик VPN заблокирован. Ожидаем повторного подключения."
                } else {
                    "VPN остановлен из-за ошибки. Трафик заблокирован до подключения или отключения."
                },
                showActions = true,
            ),
        )
        if (retryDelayMillis != null) {
            scheduleAccountRecovery(
                owner = lockdownRecoveryOwner,
                delayMillis = retryDelayMillis,
                task = lockdownRecoveryTask,
            )
        }
    }

    private suspend fun measureRuntimeReadiness(recovery: Boolean, fastStartup: Boolean = false, watchdog: Boolean = false): Long? = readinessMutex.withLock {
        val repository = SettingsRepository(this)
        val connectedTargets = VpnProbePlanPolicy.connectedTargets(recovery)
        val totalTimeout = when {
            watchdog -> VpnProbePlanPolicy.WATCHDOG_TOTAL_TIMEOUT_MILLIS
            fastStartup -> VpnProbePlanPolicy.STARTUP_TOTAL_TIMEOUT_MILLIS
            else -> VpnProbePlanPolicy.TOTAL_TIMEOUT_MILLIS
        }
        val startedAt = SystemClock.elapsedRealtime()
        val probe = connectionOrchestrator.prepareReadinessCandidates(connectedTargets.map { it.url }, totalTimeout, watchdog)
        val result = try {
            withTimeoutOrNull(totalTimeout) {
                EndpointProbeRunner.awaitNativeProbe(measure = probe.measure, cancel = probe.cancel)
            } ?: VpnProbeResult(null, VpnCoreIssue.PROXY_TCP_TIMEOUT)
        } finally {
            probe.cancel()
        }
        currentCoroutineContext().ensureActive()
        recordDiagnostic(
            repository = repository,
            message = if (watchdog) "watchdog_probe" else "readiness_probe",
            details = readinessDiagnosticDetails(
                targetKey = connectedTargets.joinToString(",") { it.key },
                result = result,
                recovery = recovery,
                fallback = false,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            ),
            level = if (VpnReadinessPolicy.accept(result.delayMs)) "info" else "error",
        )
        result.delayMs.takeIf(VpnReadinessPolicy::accept)
    }

    private fun readinessDiagnosticDetails(
        targetKey: String,
        result: VpnProbeResult,
        recovery: Boolean,
        fallback: Boolean,
        elapsedMs: Long,
    ): String = buildString {
        append("target=").append(targetKey)
        append("; recovery=").append(recovery)
        append("; fallback=").append(fallback)
        append("; delay_ms=").append(result.delayMs ?: -1L)
        append("; elapsed_ms=").append(elapsedMs)
        append("; issue=").append(result.issue?.logMessage.orEmpty())
        append("; state=").append(currentState.name.lowercase(Locale.ROOT))
        append("; endpoint=").append(activeSettings?.profile?.endpointCode.orEmpty())
        append("; network_kind=").append(activeEndpointNetworkKind.name.lowercase(Locale.ROOT))
        append("; underlay=").append(underlyingNetworkSource.currentSnapshot()?.details.orEmpty())
    }

    private fun launchBackgroundEndpointWarmup(
        repository: SettingsRepository,
        session: BackendVpnSession,
        settings: StoredSettings,
        owner: RuntimeOwner,
    ): CancelableTask? {
        if (settings.advancedSettings.multiHop.enabled) return null
        if (settings.advancedSettings.endpointSelectionMode != EndpointSelectionMode.AUTO) return null
        val endpointCode = settings.profile.endpointCode
        val underlaySignature = connectionOrchestrator.currentUnderlaySignature() ?: return null
        val networkKind = activeEndpointNetworkKind
        val isCurrent = {
            val snapshot = connectionOrchestrator.snapshot()
            connectionOrchestrator.isCurrent(owner.generationId) && snapshot.owner == owner &&
                snapshot.state == VpnConnectionState.CONNECTED &&
                snapshot.activeSettings?.hasSameAuthSessionAs(settings) == true &&
                snapshot.activeSettings.profile.endpointCode == endpointCode &&
                snapshot.underlay?.signature == underlaySignature &&
                underlyingNetworkSource.currentSnapshot()?.signature == underlaySignature
        }
        val job = backgroundScope.launch {
            EndpointProbeRunner(this@AppVpnService, repository).probeAutoCandidates(
                session = session,
                settings = settings,
                isCurrent = isCurrent,
                networkKind = networkKind,
            )
        }
        return CancelableTask(job::cancel)
    }

    private fun handleScreenStateChanged(screenOn: Boolean) {
        notificationController.setScreenOn(screenOn)
        if (!screenOn || currentState != VpnConnectionState.CONNECTED) return
        networkMonitor.refresh()
        checkConnectionAfterWake()
        if (ruSession != null) backgroundScope.launch { checkRuAuthorization(force = true) }
    }

    private fun checkConnectionAfterWake() {
        val generation = connectionOrchestrator.currentGenerationId()
        val activeTunnel = tunnel ?: return
        val network = underlyingNetworkSource.currentSnapshot() ?: return
        fun admitted(): Boolean = connectionOrchestrator.isCurrent(generation) && tunnel === activeTunnel &&
            currentState == VpnConnectionState.CONNECTED && !connectionOrchestrator.transitionSnapshot().active &&
            activeUnderlyingNetworkSignature == network.signature &&
            underlyingNetworkSource.currentSnapshot()?.signature == network.signature
        if (!admitted() || !wakeProbeInFlight.compareAndSet(false, true)) return
        backgroundScope.launch {
            try {
                val resumed = connectionOrchestrator.withLifecycleLock {
                    if (!admitted()) false else {
                        connectionOrchestrator.wake()
                        watchdogController.resetNetworkEvidence(preserveHealthyEvidence = true)
                        true
                    }
                }
                if (!resumed) return@launch
                val owner = watchdogController.currentOwner()
                val latency = measureRuntimeReadiness(recovery = false, watchdog = true)
                connectionOrchestrator.withLifecycleLock {
                    if (!admitted() || (owner != null && !watchdogController.accepts(owner))) return@withLifecycleLock
                    if (VpnReadinessPolicy.accept(latency)) watchdogController.recordEvidence(WatchdogEvidence.VPN_PROBE)
                    // A failed HTTP target does not prove existing application sockets are broken.
                    SettingsRepository(this@AppVpnService).recordAppLog("vpn", message = "vpn_wake_probe",
                        details = "healthy=${VpnReadinessPolicy.accept(latency)}; runtime_preserved=true")
                }
            } finally {
                wakeProbeInFlight.set(false)
            }
        }
    }

    private fun startNetworkChangeMonitor(initialKind: EndpointRankingPolicy.NetworkKind) {
        stopNetworkChangeMonitor()
        activeEndpointNetworkKind = initialKind
        lastUnderlyingAvailability = null
        networkMonitor.start(::handleUnderlyingNetworkObservation)
    }

    private fun handleUnderlyingNetworkObservation(
        observation: UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>,
    ) {
        if (currentState == VpnConnectionState.CONNECTED && currentRuntimeMode == VpnRuntimeMode.ACCOUNT) {
            activeSettings?.let(youtubeConnection::start)
        }
        val previousAvailability = lastUnderlyingAvailability
        lastUnderlyingAvailability = observation.availability
        if (previousAvailability != observation.availability &&
            observation.availability != UnderlyingNetworkAvailability.Validated
        ) {
            SettingsRepository(this).recordAppLog(
                category = "vpn",
                message = if (observation.availability == UnderlyingNetworkAvailability.None) {
                    "underlay_none"
                } else {
                    "underlay_waiting_validation"
                },
                details = observation.candidate?.details,
                serverCountry = notificationServerLabel,
                connectionSuccess = false,
            )
        }
        if (observation.availability != UnderlyingNetworkAvailability.Validated &&
            currentState == VpnConnectionState.CONNECTED
        ) {
            // Network loss pauses payload, not the authorized runtime or Android TUN.
            connectionOrchestrator.updateUnderlay(null)
            watchdogController.resetNetworkEvidence()
            delayedTaskScheduler.cancel(transientRecoveryOwner)
            return
        }
        if (observation.availability == UnderlyingNetworkAvailability.Validated) {
            val recovery = pendingNetworkRecovery
            if (recovery != null) {
                recovery()
                return
            }
            observation.candidate?.let(::handleUnderlyingNetworkSnapshot)
        }
    }

    private fun handleUnderlyingNetworkSnapshot(nextUnderlyingSnapshot: UnderlyingNetworkSnapshot) {
        if (currentState != VpnConnectionState.CONNECTED) return
        val previousKind = activeEndpointNetworkKind
        val nextKind = nextUnderlyingSnapshot.kind
        val previousSignature = activeUnderlyingNetworkSignature
        val plan = VpnHandoverPolicy.plan(
            hasTunnel = tunnel != null,
            activeSignature = previousSignature,
            nextSignature = nextUnderlyingSnapshot.signature,
        )
        if (plan.action == VpnHandoverPolicy.Action.NoAction) {
            connectionOrchestrator.updateUnderlay(nextUnderlyingSnapshot)
            return
        }
        activeEndpointNetworkKind = nextKind
        SettingsRepository(this).recordAppLog(
            category = "vpn",
            level = "info",
            message = "endpoint_network_changed",
            details = buildString {
                append("from=${previousKind.name.lowercase(Locale.ROOT)}; ")
                append("to=${nextKind.name.lowercase(Locale.ROOT)}")
                append("; underlying_changed=true")
                append("; vpn_metered=${nextUnderlyingSnapshot.vpnShouldBeMetered}")
                append("; underlay=${nextUnderlyingSnapshot.details}")
            },
            serverCountry = notificationServerLabel,
        )
        when (plan.action) {
            VpnHandoverPolicy.Action.NoAction -> Unit
            VpnHandoverPolicy.Action.FreshStart -> {
                if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                    restartTemporaryVpn()
                } else {
                    startVpn(
                        strategy = plan.strategy,
                    )
                }
            }
            VpnHandoverPolicy.Action.RebindNetwork -> {
                watchdogController.resetNetworkEvidence()
                rebindUnderlyingNetwork(nextUnderlyingSnapshot)
            }
        }
    }

    private fun rebindUnderlyingNetwork(next: UnderlyingNetworkSnapshot) {
        val generation = connectionOrchestrator.currentGenerationId()
        val activeTunnel = tunnel ?: return
        val sessionId = ruSession?.identity?.sessionId
        val mode = currentRuntimeMode
        fun admitted(): Boolean = connectionOrchestrator.isCurrent(generation) &&
            tunnel === activeTunnel && currentRuntimeMode == mode && ruSession?.identity?.sessionId == sessionId &&
            currentState == VpnConnectionState.CONNECTED &&
            underlyingNetworkSource.currentSnapshot()?.signature == next.signature
        backgroundScope.launch {
            connectionOrchestrator.withLifecycleLock {
                if (!admitted() || activeUnderlyingNetworkSignature == next.signature) return@withLifecycleLock
                val reopened = connectionOrchestrator.rebindNetwork()
                if (reopened) {
                    if (!admitted()) return@withLifecycleLock
                    connectionOrchestrator.updateUnderlay(next)
                    activeEndpointNetworkKind = next.kind
                    delayedTaskScheduler.cancel(transientRecoveryOwner)
                    if (sessionId != null) backgroundScope.launch { checkRuAuthorization(force = true) }
                    SettingsRepository(this@AppVpnService).recordAppLog("vpn",
                        message = if (sessionId != null) "ru_wireguard_underlay_rebound" else "underlay_rebound",
                        details = "tun_preserved=true; runtime_preserved=true")
                } else if (admitted()) {
                    // Retry only a failed bind; HTTP readiness is owned by the watchdog.
                    delayedTaskScheduler.schedule(transientRecoveryOwner, 2_000L) {
                        if (admitted()) rebindUnderlyingNetwork(next)
                    }
                }
            }
        }
    }

    private fun stopNetworkChangeMonitor() {
        networkMonitor.stop()
        lastUnderlyingAvailability = null
    }

    private fun prepareFailureReason(error: Throwable): String {
        return when {
            error is BackendException && error.statusCode == 426 &&
                error.message == "client_update_required" -> "client_update_required"
            error is BackendException && error.message.contains("traffic", ignoreCase = true) -> "traffic_limit"
            error is BackendException && error.message.contains("device", ignoreCase = true) -> "device_limit"
            error.message?.contains("auth", ignoreCase = true) == true -> "auth_required"
            else -> "profile_prepare_error"
        }
    }

    private fun incidentDetails(incidentId: String?, details: String?): String? =
        when {
            incidentId.isNullOrBlank() -> details
            details.isNullOrBlank() -> "incident_id=$incidentId"
            else -> "incident_id=$incidentId; $details"
        }

    private fun failStart(
        repository: SettingsRepository,
        reason: String,
        error: Throwable? = null,
        category: String = "vpn",
        endpointRating: String? = null,
        retryBlockedTunnel: Boolean = true,
    ) {
        youtubeConnection.stop()
        ruSession?.let { stageRuWireGuardRevoke(repository, it) }
        ruSession = null
        cancelDelayedVpnCallbacks()
        recordDiagnostic(
            repository = repository,
            message = "connection_failed",
            details = buildString {
                append("reason=").append(reason)
                append("; error=").append(error?.javaClass?.name.orEmpty())
                append(":").append(error?.message.orEmpty())
                append("; runtime_mode=").append(currentRuntimeMode.name.lowercase(Locale.ROOT))
                append("; state=").append(currentState.name.lowercase(Locale.ROOT))
                append("; endpoint=").append(activeSettings?.profile?.endpointCode.orEmpty())
                append("; network_kind=").append(activeEndpointNetworkKind.name.lowercase(Locale.ROOT))
                append("; underlay=").append(underlyingNetworkSource.currentSnapshot()?.details.orEmpty())
            },
            level = "error",
        )
        repository.recordAppLog(
            category = category,
            level = "error",
            message = reason,
            details = error?.message,
            errorType = reason,
            serverCountry = notificationServerLabel,
            connectionSuccess = false,
            endpointRating = endpointRating,
        )
        warmupController.clear()
        if (tunnel != null && shouldBlockDirectTraffic()) {
            connectionOrchestrator.stopCore()
            enterTruthfulLockdownFailure(
                repository, reason,
                retryDelayMillis = if (retryBlockedTunnel && reason != "client_update_required") {
                    watchdogController.lockdownRetryDelayMillis()
                } else null,
            )
            return
        }
        connectionOrchestrator.releaseResourcesWhileOwned(VpnConnectionState.FAILED)
        connectedAtMillis = null
        repository.clearVpnRuntimeState()
        broadcastState(VpnConnectionState.FAILED, reason)
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            destroyCleanupScope.launch {
                val pending = try {
                    temporaryVpnCoordinator.stageStoredLeaseForRevoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (stageError: Exception) {
                    repository.recordAppLog(
                        category = "vpn",
                        level = "error",
                        message = "temporary_vpn_revoke_stage_failed",
                        details = stageError.message,
                        errorType = "temporary_vpn_revoke_stage_failed",
                    )
                    return@launch
                }
                notificationHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                if (pending != null) {
                    try {
                        temporaryVpnCoordinator.retryPendingRevoke()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Unit
                    }
                }
            }
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun recordDiagnostic(
        repository: SettingsRepository,
        message: String,
        details: String,
        level: String = "info",
    ) {
        repository.recordAppLog(
            category = "diagnostic",
            level = level,
            message = message,
            details = details,
            serverCountry = notificationServerLabel,
        )
    }

    private fun failClosedAfterReplacementFailure(
        repository: SettingsRepository,
        settings: StoredSettings,
        reason: String,
    ) {
        youtubeConnection.stop()
        ruSession?.let { stageRuWireGuardRevoke(repository, it) }
        ruSession = null
        val isLockdown = tunnel != null && shouldBlockDirectTraffic()
        when (ConnectedWatchdogPolicy.exhaustedRecoveryDecision(isLockdown)) {
            ConnectedWatchdogPolicy.ExhaustedRecoveryDecision.ReportLockdownBlockedAndRetry -> {
                connectionOrchestrator.stopCore()
                enterTruthfulLockdownFailure(repository, reason)
            }
            ConnectedWatchdogPolicy.ExhaustedRecoveryDecision.ReleaseTunnel -> {
                repository.recordAppLog(
                    category = "vpn_core",
                    level = "error",
                    message = "transient_tunnel_released",
                    details = reason,
                    errorType = reason,
                    serverCountry = notificationServerLabel,
                    connectionSuccess = false,
                    endpointRating = endpointRatingSnapshot(repository, settings),
                )
                connectionOrchestrator.releaseResourcesWhileOwned(VpnConnectionState.FAILED)
                connectedAtMillis = null
                broadcastState(VpnConnectionState.FAILED, reason)
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(
                        title = "VPN переподключается",
                        text = "VPN ожидает доступную сеть.",
                        showActions = true,
                    ),
                )
                startNetworkChangeMonitor(activeEndpointNetworkKind)
                scheduleTransientRecovery(repository, settings, incidentId = null)
            }
        }
    }

    private fun revokeTemporaryLeaseWithoutStoppingAccount(startId: Int) {
        backgroundScope.launch {
            try {
                temporaryVpnCoordinator.revokeStoredLease()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Unit
            }
            notificationHandler.post {
                val transitionActive = connectionOrchestrator.transitionSnapshot().active
                if (tunnel == null && !transitionActive) {
                    stopSelfResult(startId)
                }
            }
        }
    }

    private fun stopVpn(
        startId: Int,
        revokeTemporaryLease: Boolean = currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP,
    ) {
        val repository = SettingsRepository(this)
        val stoppedRuntimeMode = currentRuntimeMode
        repository.recordAppLog("vpn", message = "service_disconnect")
        pendingStartOptions = null
        cancelDelayedVpnCallbacks()
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.STOP,
            awaitPrevious = true,
            onOwnedCompletion = { generationId ->
                if (revokeTemporaryLease) {
                    backgroundScope.launch { temporaryVpnCoordinator.retryPendingRevoke() }
                }
                notificationHandler.post {
                    // START/STOP delivery and pending consumption share the main
                    // thread. A newer command or destroy invalidates this cleanup.
                    if (!connectionOrchestrator.isCurrent(generationId)) return@post
                    val pending = pendingStartOptions.also { pendingStartOptions = null }
                    if (pending != null) {
                        if (pending.runtimeMode == VpnRuntimeMode.AUTH_TEMP) {
                            startTemporaryVpn()
                        } else {
                            startVpn(
                                strategy = pending.strategy,
                            )
                        }
                    } else {
                        stopSelfResult(startId)
                    }
                }
            },
            onError = { _, error -> throw error },
        ) {
            connectionOrchestrator.withLifecycleLock {
                releaseVpnResources(
                    repository = repository,
                    finalState = VpnConnectionState.DISCONNECTED,
                    stopService = false,
                    reason = "user_stop",
                )
            }
            if (revokeTemporaryLease) {
                temporaryVpnCoordinator.stageStoredLeaseForRevoke()
                activeTemporaryLease = null
            }
            if (stoppedRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                currentRuntimeMode = VpnRuntimeMode.ACCOUNT
            }
        }
    }

    private fun stageRuWireGuardRevoke(
        repository: SettingsRepository,
        session: BackendRuWireGuardSession.Ready,
    ) {
        try {
            repository.stageRuWireGuardRevoke(PendingRuWireGuardRevoke(
                sessionId = session.identity.sessionId,
                revokeToken = checkNotNull(session.identity.revokeToken),
            ))
            PendingRuWireGuardRevokeWorker.enqueue(applicationContext)
        } catch (error: Exception) {
            repository.recordAppLog("vpn", level = "error", message = "ru_wireguard_revoke_stage_failed",
                errorType = error.javaClass.simpleName)
        }
    }

    private fun releaseVpnResources(
        repository: SettingsRepository,
        finalState: VpnConnectionState?,
        stopService: Boolean,
        removeForeground: Boolean = true,
        reason: String? = null,
    ) {
        youtubeConnection.stop()
        ruSession?.let { stageRuWireGuardRevoke(repository, it) }
        ruSession = null
        cancelDelayedVpnCallbacks()
        warmupController.clear()
        connectionOrchestrator.releaseResourcesWhileOwned(
            finalState,
            retainTunnel = finalState == null && shouldBlockDirectTraffic(),
        )
        connectedAtMillis = null
        repository.clearVpnRuntimeState()
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        finalState?.let { broadcastState(it, reason) }
        if (stopService) stopSelf()
    }

    private fun manualRestartVpn() {
        SettingsRepository(this).recordAppLog("vpn", message = "service_manual_restart")
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            restartTemporaryVpn()
            return
        }
        restartVpn(strategy = VpnPreparationStrategy.FreshWithCachedFallback,)
    }

    private fun reconnectFailedClosedVpn(allowCachedFallback: Boolean) {
        if (settingsStore.load().advancedSettings.ruRelayEnabled) {
            restartVpn(VpnPreparationStrategy.FreshOnly)
            return
        }
        val activeTunnel = tunnel
        if (activeTunnel == null || currentState != VpnConnectionState.FAILED) {
            restartVpn(strategy = if (allowCachedFallback) VpnPreparationStrategy.CachedFirst else VpnPreparationStrategy.CachedFirstWithoutFallback,)
            return
        }
        if (connectionOrchestrator.transitionSnapshot().active) {
            broadcastCurrentState()
            return
        }
        val repository = SettingsRepository(this)
        repository.recordAppLog("vpn", message = "service_failed_closed_reconnect_start")
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = "VPN reconnecting",
                text = "Preparing a fresh VPN engine while traffic remains blocked.",
                showActions = true,
            ),
        )
        launchConnectionTransition(repository) { generationId ->
            val prepared = prepareConnectionSettings(
                repository = repository,
                strategy = if (allowCachedFallback) VpnPreparationStrategy.FreshWithCachedFallback else VpnPreparationStrategy.FreshOnly,
                destructiveOnFailure = false,
            ) ?: return@launchConnectionTransition
            val settings = normalizeInstalledPackages(prepared.candidateSettings)
            if (!connectionOrchestrator.isCurrent(generationId)) return@launchConnectionTransition
            startCoreOnFailedClosedTunnel(
                repository = repository,
                settings = settings,
                preparationBaseline = prepared.preparationBaseline,
                activeTunnel = activeTunnel,
                generationId = generationId,
                preparedMetadata = prepared,
            )
        }
    }

    private suspend fun startCoreOnFailedClosedTunnel(
        repository: SettingsRepository,
        settings: StoredSettings,
        preparationBaseline: StoredSettings,
        activeTunnel: TunHandle,
        generationId: Long,
        preparedMetadata: PreparedVpnSession,
    ) = connectionOrchestrator.withLifecycleLock {
        if (!connectionOrchestrator.isCurrent(generationId) ||
            tunnel !== activeTunnel ||
            currentState != VpnConnectionState.FAILED
        ) {
            return@withLifecycleLock
        }
        acceptPreparedMetadata(repository, preparedMetadata)

        val config = VpnConfigFactory.build(
            settings.profile,
            settings.advancedSettings,
            GeositeAsset.readSnapshot(applicationContext),
            youtubeConnection = youtubeConnection.configFor(settings),
        )
        val started = connectionOrchestrator.startCore(config)
        var readinessCompleted = false
        val readinessLatencyMs = try {
            (if (started) measureRuntimeReadiness(recovery = true) else null)
                .also { readinessCompleted = true }
        } finally {
            if (!readinessCompleted) connectionOrchestrator.stopCore()
        }
        if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
            connectionOrchestrator.stopCore()
            return@withLifecycleLock
        }
        if (started && VpnReadinessPolicy.accept(readinessLatencyMs)) {
            if (!connectionOrchestrator.isCurrent(generationId)) {
                connectionOrchestrator.stopCore()
                return@withLifecycleLock
            }
            finishTunnelConnected(
                repository,
                settings,
                preparationBaseline = preparationBaseline,
                generationId = generationId,
                resetConnectedAt = true,
                readinessLatencyMs = readinessLatencyMs,
                activationKind = ConnectedActivationKind.FAILED_CLOSED_RECOVERY,
            )
            return@withLifecycleLock
        }
        val failureReason = checkNotNull(
            VpnReadinessPolicy.failureReason(started, readinessLatencyMs),
        ).code

        val failedHealth = repository.recordEndpointResult(
            endpointCode = settings.profile.endpointCode,
            success = false,
            networkKind = activeEndpointNetworkKind,
        )
        recordEndpointHealthEvent(
            repository = repository,
            settings = settings,
            endpointCode = settings.profile.endpointCode,
            networkKind = activeEndpointNetworkKind,
            eventType = EndpointHealthEventType.CONNECT_FAIL,
            success = false,
            slow = false,
            health = failedHealth,
        )
        if (ConnectedWatchdogPolicy.retryFailureDecision(
                isLockdown = isActiveLockdownRecovery(),
            ) == ConnectedWatchdogPolicy.RetryFailureDecision.KeepTruthfulFailedAndReschedule
        ) {
            connectionOrchestrator.stopCore()
            enterTruthfulLockdownFailure(
                repository,
                failureReason,
            )
            return@withLifecycleLock
        }
        failClosedAfterReplacementFailure(
            repository = repository,
            settings = settings,
            reason = failureReason,
        )
    }

    private fun restartVpn(
        strategy: VpnPreparationStrategy,
    ) {
        val repository = SettingsRepository(this)
        pendingStartOptions = null
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.RESTART,
            awaitPrevious = true,
            onError = { generationId, error ->
                handleTransitionException(repository, generationId, error)
            },
        ) { generationId ->
            connectionOrchestrator.withLifecycleLock {
                releaseVpnResources(
                    repository = repository,
                    finalState = null,
                    stopService = false,
                    removeForeground = false,
                )
            }
            if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
                return@launchTransition
            }
            announceConnecting()
            repository.recordAppLog("vpn", message = "service_restart_start")
            performFreshStart(
                repository = repository,
                strategy = strategy,
                generationId = generationId,
            )
        }
    }

    private fun restartTemporaryVpn() {
        val repository = SettingsRepository(this)
        pendingStartOptions = null
        connectionOrchestrator.launchTransition(
            scope = backgroundScope,
            operation = VpnConnectionOperation.RESTART,
            awaitPrevious = true,
            onError = { generationId, error ->
                handleTransitionException(repository, generationId, error)
            },
        ) { generationId ->
            connectionOrchestrator.withLifecycleLock {
                releaseVpnResources(
                    repository = repository,
                    finalState = null,
                    stopService = false,
                    removeForeground = false,
                )
            }
            if (!connectionOrchestrator.isCurrent(generationId) || !currentCoroutineContext().isActive) {
                return@launchTransition
            }
            announceConnecting(VpnRuntimeMode.AUTH_TEMP)
            repository.recordAppLog("vpn", message = "temporary_vpn_restart_start")
            performTemporaryStart(repository, generationId)
        }
    }

    private fun createActiveNotification(): Notification {
        return notificationFactory.createActive(notificationServerLabel)
    }

    private fun runtimeServerLabel(settings: StoredSettings): String =
        if (ruSession != null) notificationServerLabel else VpnServiceLogContext.serverLabel(settings)

    private fun updateActiveNotification() {
        if (connectionOrchestrator.nokiNvCarrier() != liveRuntimeState().nokiNvCarrier) broadcastState(currentState)
        notificationFactory.updateActive(notificationServerLabel)
    }

    private fun startNotificationTicker() {
        notificationController.start()
    }

    private fun stopNotificationTicker() {
        notificationController.stop()
    }

    private fun pollAppNotifications(): CancelableTask? {
        if (currentState != VpnConnectionState.CONNECTED || currentRuntimeMode != VpnRuntimeMode.ACCOUNT) return null
        val job = backgroundScope.launch {
            if (ruSession != null) checkRuAuthorization(force = false)
            if (currentState != VpnConnectionState.CONNECTED) return@launch
            val repository = SettingsRepository(this@AppVpnService)
            val authApi = underlyingNetworkSource.currentSnapshot()?.network?.let(backendApi::onNetwork) ?: backendApi
            val refresher = AuthTokenRefresher(
                repository,
                authApi,
                onRevocationPending = { PendingLogoutRevocationWorker.enqueue(applicationContext) },
            )
            try {
                // FCM can skip notification HTTP polling, but account refresh must continue.
                refresher.refreshExpiringStoredTokens()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                repository.recordAppLog("auth", level = "warn", message = "background_token_refresh_failed",
                    errorType = error.javaClass.simpleName)
                return@launch
            }
            val settings = repository.load()
            val token = settings.backendAccessToken?.takeIf { it.isNotBlank() }
            if (token == null || !settings.isAuthenticated) return@launch
            try {
                AppNotificationPoller.pollOnce(
                    context = this@AppVpnService,
                    repository = repository,
                    backendApi = backendApi,
                    token = token,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error !is BackendException || error.statusCode != 401) return@launch
                val refreshedToken = try {
                    refresher.refreshStoredTokens()?.accessToken
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                } ?: return@launch
                try {
                    AppNotificationPoller.pollOnce(
                        context = this@AppVpnService,
                        repository = repository,
                        backendApi = backendApi,
                        token = refreshedToken,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    Unit
                }
            }
        }
        return CancelableTask(job::cancel)
    }

    private fun startConnectedSidecars(owner: RuntimeOwner, settings: StoredSettings) {
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            startNotificationTicker()
            startNetworkChangeMonitor(activeEndpointNetworkKind)
            scheduleTemporaryLeaseExpiry()
            return
        }
        val repository = SettingsRepository(this)
        youtubeConnection.start(settings)
        statsCoordinator.start(settings, owner)
        DeviceTrafficMonitor.start(sessionId = checkNotNull(connectedAtMillis))
        startNotificationTicker()
        startNetworkChangeMonitor(activeEndpointNetworkKind)
        if (ruSession != null) {
            lastRuStatusCheckElapsedMs = 0L
            return
        }
        endpointHealthController.start(owner, settings)
        startConnectedWatchdog(settings, owner)
        warmupController.start(owner) { session ->
            launchBackgroundEndpointWarmup(
                repository = repository,
                session = session,
                settings = settings,
                owner = owner,
            )
        }
    }

    private suspend fun checkRuAuthorization(force: Boolean) {
        val session = ruSession ?: return
        if (currentState != VpnConnectionState.CONNECTED || currentRuntimeMode != VpnRuntimeMode.ACCOUNT || tunnel == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastRuStatusCheckElapsedMs != 0L && now - lastRuStatusCheckElapsedMs < RU_STATUS_INTERVAL_MS) return
        if (!ruStatusInFlight.compareAndSet(false, true)) return
        lastRuStatusCheckElapsedMs = now
        try {
            val repository = SettingsRepository(this)
            val settings = repository.load()
            val baseline = activeSettings ?: return
            if (!ruAuthorizationUnchanged(baseline, settings)) {
                endRuAuthorization(session, "ru_wireguard_authorization_changed")
                return
            }
            val api = underlyingNetworkSource.currentSnapshot()?.network?.let(backendApi::onNetwork) ?: backendApi
            var token = settings.backendAccessToken ?: run {
                endRuAuthorization(session, "ru_wireguard_authorization_changed")
                return
            }
            val response = try {
                withTimeoutOrNull(RU_STATUS_TIMEOUT_MS) {
                    api.pollRuWireGuardSession(token, settings.backendDeviceId, settings.backendDeviceKey, session.identity)
                } ?: return
            } catch (error: BackendException) {
                if (error.statusCode != 401) throw error
                val refreshed = try {
                    AuthTokenRefresher(repository, api,
                        onRevocationPending = { PendingLogoutRevocationWorker.enqueue(applicationContext) },
                    ).refreshStoredTokens()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refreshError: Exception) {
                    if (BackendRetryPolicy.isTransient(refreshError)) return
                    null
                }
                token = refreshed?.accessToken ?: run {
                    endRuAuthorization(session, "ru_wireguard_authorization_revoked")
                    return
                }
                withTimeoutOrNull(RU_STATUS_TIMEOUT_MS) {
                    api.pollRuWireGuardSession(token, settings.backendDeviceId, settings.backendDeviceKey, session.identity)
                } ?: return
            }
            when (response) {
                is BackendRuWireGuardSession.Pending ->
                    endRuAuthorization(session, "ru_wireguard_status_regressed")
                is BackendRuWireGuardSession.Ready -> if (response.wireguard != session.wireguard)
                    endRuAuthorization(session, "ru_wireguard_session_changed")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (prepareFailureReason(error) == "client_update_required") {
                endRuAuthorization(session, "client_update_required")
            } else if (error is BackendException && error.statusCode in setOf(401, 403, 404, 409)) {
                endRuAuthorization(session, "ru_wireguard_authorization_revoked")
            } else if (!BackendRetryPolicy.isTransient(error)) {
                endRuAuthorization(session, "ru_wireguard_status_invalid")
            }
        } finally {
            ruStatusInFlight.set(false)
        }
    }

    private fun ruAuthorizationUnchanged(baseline: StoredSettings, latest: StoredSettings): Boolean =
        latest.isAuthenticated && latest.advancedSettings.ruRelayEnabled &&
            latest.userProfile.backendUserId == baseline.userProfile.backendUserId &&
            latest.backendDeviceId == baseline.backendDeviceId && latest.backendDeviceKey == baseline.backendDeviceKey &&
            !VpnSettingsTransactionPolicy.candidateBecameStale(baseline, latest)

    private suspend fun endRuAuthorization(session: BackendRuWireGuardSession.Ready, reason: String) {
        connectionOrchestrator.withLifecycleLock {
            if (ruSession === session) {
                val repository = SettingsRepository(this)
                failStart(repository, reason, retryBlockedTunnel = false)
            }
        }
    }

    private fun stopConnectedSidecars(owner: RuntimeOwner?) {
        delayedTaskScheduler.cancel(temporaryLeaseExpiryOwner)
        if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
            stopNetworkChangeMonitor()
            stopNotificationTicker()
            return
        }
        if (!statsCoordinator.acceptsStop(owner)) return
        statsCoordinator.stop(owner)
        stopNetworkChangeMonitor()
        endpointHealthController.stop(owner)
        stopConnectedWatchdog()
        stopNotificationTicker()
        DeviceTrafficMonitor.stop()
        warmupController.stop(owner)
    }

    private fun scheduleTemporaryLeaseExpiry() {
        val lease = activeTemporaryLease ?: return
        val delayMillis = (lease.expiresAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        delayedTaskScheduler.schedule(
            owner = temporaryLeaseExpiryOwner,
            delayMillis = delayMillis,
            task = {
                if (currentRuntimeMode == VpnRuntimeMode.AUTH_TEMP) {
                    startService(stopIntent(this))
                }
            },
        )
    }

    private fun endpointRatingSnapshot(
        repository: SettingsRepository,
        settings: StoredSettings,
    ): String {
        val codes = settings.endpointOptions.map { it.code }.ifEmpty { listOf(settings.profile.endpointCode) }
        return repository.endpointRatingSnapshot(codes, activeEndpointNetworkKind)
    }

    private fun createNotification(
        title: String,
        text: String,
        showActions: Boolean,
    ): Notification = notificationFactory.create(title, text, showActions)

    private fun broadcastCurrentState() {
        if (tunnel == null && currentState == VpnConnectionState.DISCONNECTED &&
            transientRecoveryAttempt == 0 &&
            !connectionOrchestrator.transitionSnapshot().active && pendingStartOptions == null
        ) {
            connectedAtMillis = null
            statsCoordinator.reset()
            SettingsRepository(this).clearVpnRuntimeState()
            broadcastState(VpnConnectionState.DISCONNECTED)
            stopSelf()
            return
        }
        broadcastState(currentState)
    }

    private fun broadcastState(
        state: VpnConnectionState,
        reason: String? = null,
        latencyLocationCode: String? = null,
        latencyMs: Int? = null,
    ) {
        val publicState = if (state == VpnConnectionState.CONNECTED && tunnel == null) {
            VpnConnectionState.DISCONNECTED
        } else {
            state
        }
        val publicConnectedAtMillis = connectedAtMillis.takeIf { publicState == VpnConnectionState.CONNECTED }
        currentState = publicState
        updateLiveRuntimeState(publicState, publicConnectedAtMillis, currentRuntimeMode,
            ruSession?.identity?.sessionId, latencyMs, connectionOrchestrator.nokiNvCarrier())
        val intent = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_STATE, publicState.name)
            .putExtra(EXTRA_REASON, reason.orEmpty())
            .putExtra(EXTRA_CONNECTED_AT, publicConnectedAtMillis ?: 0L)
            .putExtra(EXTRA_RUNTIME_MODE, currentRuntimeMode.name)
            .putExtra(EXTRA_NOKINV_CARRIER, liveRuntimeState().nokiNvCarrier)
        latencyLocationCode?.takeIf { it.isNotBlank() }?.let { code ->
            intent.putExtra(EXTRA_LATENCY_LOCATION_CODE, code)
        }
        liveRuntimeState().latencyMs?.let { value ->
            intent.putExtra(EXTRA_LATENCY_MS, value)
        }
        sendBroadcast(
            intent,
        )
        NokiQuickSettingsTileService.requestTileRefresh(this)
    }

    data class LiveRuntimeState(
        val state: VpnConnectionState,
        val connectedAtMillis: Long?,
        val runtimeMode: VpnRuntimeMode,
        val ruSessionId: String? = null,
        val latencyMs: Int? = null,
        val nokiNvCarrier: String = "",
    )

    companion object {
        private const val TAG = "NokiVpnService"
        private const val ACTION_START = "com.noki.vpn.START"
        private const val ACTION_START_TEMPORARY = "com.noki.vpn.START_TEMPORARY"
        private const val ACTION_STOP = "com.noki.vpn.STOP"
        private const val ACTION_STOP_AND_REVOKE_TEMPORARY =
            "com.noki.vpn.STOP_AND_REVOKE_AUTH_TEMP"
        private const val ACTION_RESTART = "com.noki.vpn.RESTART"
        private const val ACTION_QUERY_STATE = "com.noki.vpn.QUERY_STATE"
        private const val EXTRA_REFRESH_LATENCY = "refresh_latency"
        private const val ACTION_APPLY_SETTINGS = "com.noki.vpn.APPLY_SETTINGS"
        const val ACTION_STATE_CHANGED = "com.noki.vpn.STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_REASON = "reason"
        const val EXTRA_CONNECTED_AT = "connected_at"
        const val EXTRA_RUNTIME_MODE = "runtime_mode"
        const val EXTRA_LATENCY_LOCATION_CODE = "latency_location_code"
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_NOKINV_CARRIER = "nokinv_carrier"
        private const val EXTRA_REFRESH_SESSION = "refresh_session"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_SPEED_UPDATE_INTERVAL_MS = 1_000L
        private const val DATA_PATH_EVIDENCE_FRESH_MS = 5 * 60_000L
        private const val APP_NOTIFICATION_POLL_INTERVAL_MS = 60_000L
        private const val RU_STATUS_INTERVAL_MS = 5 * 60_000L
        private const val RU_STATUS_TIMEOUT_MS = 15_000L
        private const val STARTUP_TCP_PRECHECK_TIMEOUT_MS = 800
        private const val BACKGROUND_ENDPOINT_WARMUP_DELAY_MS = 18_000L
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 2_500L
        private const val RUNTIME_SETTINGS_RETRY_DELAY_MS = 500L
        private const val FRESH_SESSION_RETRY_COUNT = 2
        private const val FRESH_SESSION_DEADLINE_MS = 20_000L
        @Volatile
        private var liveRuntimeState = LiveRuntimeState(
            VpnConnectionState.DISCONNECTED,
            null,
            VpnRuntimeMode.ACCOUNT,
        )

        fun liveRuntimeState(): LiveRuntimeState = liveRuntimeState

        private fun updateLiveRuntimeState(
            state: VpnConnectionState,
            connectedAtMillis: Long?,
            runtimeMode: VpnRuntimeMode = VpnRuntimeMode.ACCOUNT,
            ruSessionId: String? = null,
            latencyMs: Int? = null,
            nokiNvCarrier: String = "",
        ) {
            val previous = liveRuntimeState
            liveRuntimeState = LiveRuntimeState(
                state = state,
                connectedAtMillis = connectedAtMillis.takeIf { state == VpnConnectionState.CONNECTED },
                runtimeMode = runtimeMode,
                ruSessionId = ruSessionId.takeIf { state == VpnConnectionState.CONNECTED },
                nokiNvCarrier = nokiNvCarrier.takeIf { state == VpnConnectionState.CONNECTED }.orEmpty(),
                latencyMs = if (state == VpnConnectionState.CONNECTED) {
                    latencyMs?.takeIf { it > 0 } ?: previous.latencyMs.takeIf {
                        previous.state == state && previous.connectedAtMillis == connectedAtMillis &&
                            previous.runtimeMode == runtimeMode && previous.ruSessionId == ruSessionId
                    }
                } else null,
            )
        }

        fun startIntent(
            context: Context,
            refreshSession: Boolean = false,
        ): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_REFRESH_SESSION, refreshSession)
        }

        fun temporaryStartIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_START_TEMPORARY
        }

        fun stopIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_STOP
        }

        fun stopAndRevokeTemporaryIntent(context: Context): Intent =
            Intent(context, AppVpnService::class.java).apply {
                action = ACTION_STOP_AND_REVOKE_TEMPORARY
            }

        fun restartIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_RESTART
        }

        fun queryStateIntent(context: Context, refreshLatency: Boolean = false): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_QUERY_STATE
            putExtra(EXTRA_REFRESH_LATENCY, refreshLatency)
        }

        fun applySettingsIntent(context: Context): Intent = Intent(context, AppVpnService::class.java).apply {
            action = ACTION_APPLY_SETTINGS
        }
    }
}
