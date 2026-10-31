package com.noki.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class VpnNetworkMonitor(
    private val source: () -> UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>,
    private val scheduler: DelayedTaskScheduler,
    private val debounceMillis: Long,
    private val register: ((() -> Unit) -> AutoCloseable?),
) {
    private val lifecycleLock = ReentrantLock()
    private val callbacksIdle = lifecycleLock.newCondition()
    private val debounceOwner = Any()
    private val validationRecheckOwner = Any()
    private val callbackDepthByThread = IdentityHashMap<Thread, Int>()
    private var registration: AutoCloseable? = null
    private var listener: ((UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>) -> Unit)? = null
    private var validationRecheckAttempt = 0
    private var nextEpoch = 0L
    private var startedEpoch = 0L
    private var activeCallbacks = 0

    fun start(onObservation: (UnderlyingNetworkObservation<UnderlyingNetworkSnapshot>) -> Unit) {
        stop()
        val epoch = lifecycleLock.withLock {
            nextEpoch = if (nextEpoch == Long.MAX_VALUE) 1L else nextEpoch + 1L
            startedEpoch = nextEpoch
            listener = onObservation
            validationRecheckAttempt = 0
            startedEpoch
        }
        val newRegistration = register { scheduleSnapshot(epoch) }
        val staleRegistration = lifecycleLock.withLock {
            if (isCurrent(epoch)) {
                registration = newRegistration
                null
            } else {
                newRegistration
            }
        }
        staleRegistration?.close()
    }

    fun stop() {
        val activeRegistration = lifecycleLock.withLock {
            startedEpoch = 0L
            val currentRegistration = registration
            registration = null
            listener = null
            validationRecheckAttempt = 0
            currentRegistration
        }
        activeRegistration?.close()
        scheduler.cancel(debounceOwner)
        scheduler.cancel(validationRecheckOwner)
        awaitOtherCallbacks()
        scheduler.cancel(debounceOwner)
        scheduler.cancel(validationRecheckOwner)
    }

    fun refresh() {
        val epoch = lifecycleLock.withLock { startedEpoch }
        if (epoch == 0L) return
        scheduler.cancel(debounceOwner)
        scheduler.cancel(validationRecheckOwner)
        emitCurrentObservation(epoch)
    }

    private fun scheduleSnapshot(epoch: Long) {
        if (!beginCallback(epoch)) return
        try {
            val shouldSchedule = lifecycleLock.withLock {
                if (!isCurrent(epoch)) return@withLock false
                validationRecheckAttempt = 0
                true
            }
            if (!shouldSchedule) return
            scheduler.cancel(validationRecheckOwner)
            scheduler.schedule(debounceOwner, debounceMillis) {
                emitCurrentObservation(epoch)
            }
        } finally {
            endCallback()
        }
    }

    private fun emitCurrentObservation(epoch: Long) {
        if (!beginCallback(epoch)) return
        try {
            val observation = source()
            val currentListener = lifecycleLock.withLock {
                listener.takeIf { isCurrent(epoch) }
            } ?: return
            currentListener(observation)

            val recheckDelayMillis = lifecycleLock.withLock {
                if (!isCurrent(epoch)) return@withLock null
                if (observation.availability == UnderlyingNetworkAvailability.Unvalidated) {
                    validationRecheckDelayMillis(validationRecheckAttempt).also {
                        validationRecheckAttempt += 1
                    }
                } else {
                    validationRecheckAttempt = 0
                    null
                }
            }
            if (recheckDelayMillis != null) {
                scheduler.schedule(validationRecheckOwner, recheckDelayMillis) {
                    emitCurrentObservation(epoch)
                }
            } else {
                scheduler.cancel(validationRecheckOwner)
            }
        } finally {
            endCallback()
        }
    }

    private fun beginCallback(epoch: Long): Boolean = lifecycleLock.withLock {
        if (!isCurrent(epoch)) return@withLock false
        val thread = Thread.currentThread()
        activeCallbacks += 1
        callbackDepthByThread[thread] = (callbackDepthByThread[thread] ?: 0) + 1
        true
    }

    private fun endCallback() {
        lifecycleLock.withLock {
            val thread = Thread.currentThread()
            val depth = checkNotNull(callbackDepthByThread[thread]) - 1
            if (depth == 0) {
                callbackDepthByThread.remove(thread)
            } else {
                callbackDepthByThread[thread] = depth
            }
            activeCallbacks -= 1
            callbacksIdle.signalAll()
        }
    }

    private fun awaitOtherCallbacks() {
        lifecycleLock.withLock {
            val currentThreadDepth = callbackDepthByThread[Thread.currentThread()] ?: 0
            while (activeCallbacks > currentThreadDepth) {
                callbacksIdle.awaitUninterruptibly()
            }
        }
    }

    private fun isCurrent(epoch: Long): Boolean = startedEpoch == epoch && epoch != 0L

    companion object {
        internal fun validationRecheckDelayMillis(attempt: Int): Long = when (attempt) {
            0 -> 1_000L
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            else -> 15_000L
        }

        fun android(
            context: Context,
            source: AndroidUnderlyingNetworkSource,
            scheduler: DelayedTaskScheduler,
            debounceMillis: Long,
        ): VpnNetworkMonitor {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            return VpnNetworkMonitor(
                source = source::currentObservation,
                scheduler = scheduler,
                debounceMillis = debounceMillis,
                register = { onChanged -> manager?.registerChangeCallback(onChanged) },
            )
        }

        private fun ConnectivityManager.registerChangeCallback(onChanged: () -> Unit): AutoCloseable? {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onChanged()

                override fun onLost(network: Network) = onChanged()

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChanged()

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = onChanged()
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            return runCatching {
                registerNetworkCallback(request, callback)
                AutoCloseable {
                    runCatching { unregisterNetworkCallback(callback) }
                }
            }.onFailure { error ->
                SafeLog.w("NokiVpnNetworkMonitor", "Failed to register network change callback", error)
            }.getOrNull()
        }
    }
}
