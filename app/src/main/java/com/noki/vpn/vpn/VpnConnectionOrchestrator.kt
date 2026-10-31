package com.noki.vpn.vpn

import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.VpnConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ConnectedActivationKind {
    NEW_TUN,
    EXISTING_TUN,
    ROLLBACK,
    FAILED_CLOSED_RECOVERY,
}

internal enum class VpnConnectionOperation {
    CONNECTION,
    STOP,
    RESTART,
}

internal data class VpnTransitionSnapshot(
    val active: Boolean,
    val operation: VpnConnectionOperation?,
)

internal data class VpnConnectionSnapshot(
    val state: VpnConnectionState,
    val activeSettings: StoredSettings?,
    val underlay: UnderlyingNetworkSnapshot?,
    val owner: RuntimeOwner?,
    val activationKind: ConnectedActivationKind?,
    val hasTunnel: Boolean,
)

internal class VpnConnectionOrchestrator(
    private val core: VpnCoreRuntime,
    private val tunFactory: TunInterfaceFactory,
    private val sidecars: VpnConnectedSidecars,
) {
    @Volatile private var tunnel: TunHandle? = null
    @Volatile private var currentState = VpnConnectionState.DISCONNECTED
    @Volatile private var activeSettings: StoredSettings? = null
    @Volatile private var activeUnderlay: UnderlyingNetworkSnapshot? = null
    @Volatile private var activeOwner: RuntimeOwner? = null
    @Volatile private var activeActivationKind: ConnectedActivationKind? = null
    private val lifecycleGeneration = VpnLifecycleGeneration()
    internal val lifecycleMutex = Mutex()
    private val transitionLaunchLock = Any()
    private var transitionJob: Job? = null
    private var transitionOperation: VpnConnectionOperation? = null

    fun currentGenerationId(): Long = lifecycleGeneration.currentId()

    @Synchronized
    fun beginTransition(): Long = lifecycleGeneration.begin()

    fun launchTransition(
        scope: CoroutineScope,
        operation: VpnConnectionOperation,
        awaitPrevious: Boolean = false,
        onOwnedCompletion: (Long) -> Unit = {},
        onError: suspend (Long, Throwable) -> Unit,
        block: suspend (Long) -> Unit,
    ): Job {
        val (job, generationId) = synchronized(transitionLaunchLock) {
            val generationId = lifecycleGeneration.begin()
            val previous = synchronized(this) {
                transitionJob.also { it?.cancel() }
            }
            if (previous != null) cancelReadinessProbe()
            val replacement = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (awaitPrevious) previous?.cancelAndJoin()
                    block(generationId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onError(generationId, error)
                }
            }
            synchronized(this) {
                transitionJob = replacement
                transitionOperation = operation
            }
            replacement to generationId
        }
        job.invokeOnCompletion {
            val owned = synchronized(this) {
                if (transitionJob === job) {
                    transitionJob = null
                    transitionOperation = null
                    true
                } else {
                    false
                }
            }
            if (owned && lifecycleGeneration.isCurrent(generationId)) onOwnedCompletion(generationId)
        }
        job.start()
        return job
    }

    @Synchronized
    fun transitionSnapshot(): VpnTransitionSnapshot = VpnTransitionSnapshot(
        active = transitionJob?.isActive == true,
        operation = transitionOperation,
    )

    @Synchronized
    fun activeTransitionJob(): Job? = transitionJob

    fun shutdown(
        scope: CoroutineScope,
        releaseResources: () -> Unit,
        cancelBackgroundWork: () -> Unit,
    ): Job {
        invalidate()
        cancelReadinessProbe()
        val activeTransition = activeTransitionJob()
        return scope.launch {
            try {
                activeTransition?.cancelAndJoin()
                withLifecycleLock { releaseResources() }
            } finally {
                cancelBackgroundWork()
            }
        }
    }

    @Synchronized
    fun currentTunnel(): TunHandle? = tunnel

    @Synchronized
    fun replaceTunnel(tunnel: TunHandle?) {
        this.tunnel = tunnel
    }

    @Synchronized
    fun currentState(): VpnConnectionState = currentState

    @Synchronized
    fun updateState(state: VpnConnectionState) {
        currentState = state
    }

    @Synchronized
    fun currentSettings(): StoredSettings? = activeSettings

    @Synchronized
    fun updateSettings(settings: StoredSettings?) {
        activeSettings = settings
    }

    @Synchronized
    fun currentUnderlaySignature(): String? = activeUnderlay?.signature

    @Synchronized
    fun currentUnderlay(): UnderlyingNetworkSnapshot? = activeUnderlay

    @Synchronized
    fun updateUnderlay(underlay: UnderlyingNetworkSnapshot?) {
        activeUnderlay = underlay
    }

    fun establishTunnel(
        settings: StoredSettings,
        underlay: UnderlyingNetworkSnapshot?,
    ): TunHandle? = tunFactory.establish(settings, underlay)

    @Synchronized
    fun startCore(config: String): Boolean {
        val activeTunnel = tunnel ?: return false
        return core.start(config, activeTunnel.fd)
    }

    fun stopCore() {
        core.stop()
    }

    @Synchronized
    fun nokiNvCarrier(): String = if (currentState == VpnConnectionState.CONNECTED) core.nokiNvCarrier() else ""

    fun rebindNetwork(): Boolean = core.rebindNetwork()

    fun wake() = core.wake()

    fun cancelReadinessProbe() {
        core.cancelMeasureDelay()
    }

    fun prepareReadinessCandidates(targetUrls: List<String>, timeoutMillis: Long, watchdog: Boolean = false): VpnReadinessProbe =
        core.prepareReadinessCandidates(targetUrls, timeoutMillis, watchdog)

    fun pauseConnectedSidecars() {
        activeOwner?.let(sidecars::stop)
        activeOwner = null
        activeActivationKind = null
    }

    @Synchronized
    fun isCurrent(generationId: Long): Boolean = lifecycleGeneration.isCurrent(generationId)

    @Synchronized
    fun invalidate(): Long = lifecycleGeneration.invalidate()

    suspend fun activateConnected(
        generationId: Long,
        coreId: Long,
        tunnel: TunHandle,
        settings: StoredSettings,
        underlay: UnderlyingNetworkSnapshot?,
        kind: ConnectedActivationKind = ConnectedActivationKind.NEW_TUN,
    ): RuntimeOwner? {
        check(lifecycleMutex.holdsLock(checkNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])))
        if (!lifecycleGeneration.isCurrent(generationId)) return null
        val previousOwner = activeOwner
        if (previousOwner != null) sidecars.stop(previousOwner)
        this.tunnel = tunnel
        activeSettings = settings
        activeUnderlay = underlay
        activeOwner = RuntimeOwner(generationId, coreId)
        activeActivationKind = kind
        currentState = VpnConnectionState.CONNECTED
        sidecars.start(checkNotNull(activeOwner), settings)
        return activeOwner
    }

    suspend fun <T> withLifecycleLock(block: suspend () -> T): T =
        lifecycleMutex.withLock(checkNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])) { block() }

    suspend fun releaseOwnedResources(finalState: VpnConnectionState) {
        lifecycleGeneration.invalidate()
        val job = synchronized(this) {
            transitionJob.also {
                transitionJob = null
                transitionOperation = null
            }
        }
        cancelReadinessProbe()
        job?.cancelAndJoin()
        lifecycleMutex.withLock {
            releaseResourcesWhileOwned(finalState)
        }
    }

    fun releaseResourcesWhileOwned(finalState: VpnConnectionState?, retainTunnel: Boolean = false) {
        pauseConnectedSidecars()
        core.stop()
        if (!retainTunnel) {
            tunnel?.close()
            tunnel = null
            activeSettings = null
            activeUnderlay = null
        }
        if (finalState != null) currentState = finalState
    }

    @Synchronized
    fun snapshot(): VpnConnectionSnapshot = VpnConnectionSnapshot(
        state = currentState,
        activeSettings = activeSettings,
        underlay = activeUnderlay,
        owner = activeOwner,
        activationKind = activeActivationKind,
        hasTunnel = tunnel != null,
    )

}
