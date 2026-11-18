package com.noki.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.InetAddresses
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import com.noki.vpn.data.SettingsRepository
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

internal class VpnCoreController(
    private val context: Context,
    private val protectSocket: ((Int) -> Boolean)? = null,
    private val onStopRequested: () -> Unit = {},
) : VpnCoreRuntime {
    private val underlay = AndroidUnderlyingNetworkSource(context)
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var server: CommandServer? = null
    private var ruDiagnosticLog: RuNativeDiagnosticLog? = null
    @Volatile private var tunFd = -1
    @Volatile private var allowedUids: Set<Int>? = null
    @Volatile private var disallowedUids: Set<Int> = emptySet()
    @Volatile private var interfaceListener: InterfaceUpdateListener? = null
    @Volatile private var defaultInterface: Pair<String, Int>? = null

    @Synchronized
    override fun start(config: String, tunFd: Int, routingRules: AppVpnRoutingRules.Ready?): Boolean = try {
        setup(context)
        // startOrReloadService validates and constructs the same core once.
        stop()
        this.tunFd = tunFd
        val guardedRules = routingRules?.takeIf { it.requiresOwnerCheck }
        val options = OverrideOptions()
        if (guardedRules != null) {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { AppVpnRoutingPolicy.OWNER_CHECK_UNAVAILABLE_REASON }
            val packages = context.packageManager
            allowedUids = guardedRules.allowedPackages.takeIf { it.isNotEmpty() }
                ?.map { packages.getPackageUid(it, 0) }?.toSet()
            disallowedUids = guardedRules.disallowedPackages.map { packages.getPackageUid(it, 0) }.toSet()
            if (guardedRules.allowedPackages.isNotEmpty()) options.includePackage = strings(guardedRules.allowedPackages.toList())
            if (guardedRules.disallowedPackages.isNotEmpty()) options.excludePackage = strings(guardedRules.disallowedPackages.toList())
        }
        val logging = SettingsRepository(context).load().advancedSettings
        val diagnostics = if (logging.ruRelayEnabled && (logging.connectionLogsEnabled || logging.errorLogsEnabled)) {
            RuNativeDiagnosticLog("${System.currentTimeMillis()}_${diagnosticSequence.incrementAndGet()}")
        } else null
        ruDiagnosticLog = diagnostics
        val core = Libbox.newCommandServer(commandHandler(diagnostics), platform)
        server = core
        core.startOrReloadService(config, options)
        true
    } catch (error: Throwable) {
        stop()
        SafeLog.e(TAG, "Failed to start VPN core", error)
        runCatching {
            SettingsRepository(context).recordAppLog(
                category = "vpn_core", level = "error", message = "vpn_core_start_failed",
                details = error.message, errorType = error::class.java.simpleName,
            )
        }
        false
    }

    @Synchronized
    override fun stop() {
        val core = server
        val diagnostics = ruDiagnosticLog
        server = null
        ruDiagnosticLog = null
        if (core != null) runCatching {
            core.cancelMeasureDelay()
            try { core.closeService() } finally { core.close() }
        }.onFailure { SafeLog.e(TAG, "Failed to stop VPN core", it) }
        flushRuDiagnostics(diagnostics, close = true)
        tunFd = -1
        allowedUids = null
        disallowedUids = emptySet()
        defaultInterface = null
    }

    override fun cancelMeasureDelay() {
        server?.cancelMeasureDelay()
    }

    @Synchronized
    override fun nokiNvCarrier(): String = server?.getNokiNVCarrier().orEmpty()

    override fun measureDelay(targetUrl: String, timeoutMillis: Long): VpnProbeResult {
        val core = server
        return measureProbe { checkNotNull(core) { "vpn_core_not_running" }.measureDelay("proxy", targetUrl, timeoutMillis) }
    }

    override fun prepareReadinessCandidates(targetUrls: List<String>, timeoutMillis: Long, watchdog: Boolean): VpnReadinessProbe {
        val core = server
        return try {
            checkNotNull(core) { "vpn_core_not_running" }
            val measurement = if (watchdog) core.prepareWatchdogCandidates("proxy", strings(targetUrls), timeoutMillis)
                else core.prepareReadinessCandidates("proxy", strings(targetUrls), timeoutMillis)
            VpnReadinessProbe(
                measure = { measureProbe { measurement.run() } },
                cancel = measurement::cancel,
            )
        } catch (error: Throwable) {
            val result = measureProbe { throw error }
            VpnReadinessProbe(measure = { result }, cancel = {})
        }
    }

    @Synchronized
    override fun prepareEntryRoute(exitNodeId: String, generation: Long, enrollment: Boolean): VpnReadinessProbe = try {
        val diagnostics = ruDiagnosticLog
        val selection = checkNotNull(server) { "vpn_core_not_running" }.prepareEntryRoute(exitNodeId, generation, enrollment)
        VpnReadinessProbe(measure = {
            try { measureProbe { selection.run() } } finally { flushRuDiagnostics(diagnostics) }
        }, cancel = selection::cancel)
    } catch (error: Throwable) {
        val result = measureProbe { throw error }
        VpnReadinessProbe(measure = { result }, cancel = {})
    }

    private fun measureProbe(measure: () -> Long): VpnProbeResult = try {
        VpnProbeResult(measure().takeIf { it > 0L })
    } catch (error: Throwable) {
        val issue = VpnCoreIssue.fromThrowable(error)
        SafeLog.w(TAG, "VPN readiness failed; issue=${issue?.logMessage ?: "unknown"}", error)
        runCatching {
            SettingsRepository(context).recordAppLog(
                category = "vpn_core", level = "error", message = "vpn_core_readiness_failed",
                details = error.message, errorType = error::class.java.simpleName,
            )
        }
        VpnProbeResult(null, issue)
    }

    @Synchronized
    override fun rebindNetwork(): Boolean = try {
        checkNotNull(server).also { core ->
            physicalNetwork()
            core.wake()
            // A changed Android interface resets sockets through the native monitor.
            if (!updateInterface()) core.resetNetwork()
        }
        true
    } catch (error: Throwable) {
        SafeLog.w(TAG, "Failed to reopen VPN sockets", error)
        false
    }

    @Synchronized
    override fun wake() {
        runCatching { server?.wake() }
            .onFailure { SafeLog.w(TAG, "Failed to wake VPN core", it) }
    }

    private fun physicalNetwork(): Network =
        checkNotNull(underlay.currentObservation().candidate?.network) { "underlay_unavailable" }

    private fun updateInterface(): Boolean {
        val network = underlay.currentObservation().candidate?.network
        val name = network?.let(connectivity::getLinkProperties)?.interfaceName
        val index = name?.let { JavaNetworkInterface.getByName(it)?.index }
        val metered = network?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val listener = checkNotNull(interfaceListener) { "vpn_interface_monitor_unavailable" }
        val next = name.orEmpty() to (index ?: -1)
        val changed = defaultInterface != next
        listener.updateDefaultInterface(next.first, next.second, metered, false)
        defaultInterface = next
        return changed
    }

    private fun bindSocket(fd: Int) {
        val network = physicalNetwork()
        check(protectSocket?.invoke(fd) != false) { "vpn_socket_protect_failed" }
        ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
    }

    private val platform = object : PlatformInterface {
        override fun localDNSTransport(): LocalDNSTransport = localDns
        override fun usePlatformAutoDetectInterfaceControl() = true
        override fun autoDetectInterfaceControl(fd: Int) = bindSocket(fd)
        override fun bindInterfaceControl(fd: Int, interfaceName: String) {
            check(connectivity.getLinkProperties(physicalNetwork())?.interfaceName == interfaceName) {
                "underlay_interface_changed"
            }
            bindSocket(fd)
        }
        // libbox duplicates this FD internally; AppVpnService retains the original.
        override fun openTun(options: TunOptions): Int = tunFd.also { check(it >= 0) { "vpn_tun_unavailable" } }
        override fun useProcFS() = false
        override fun findConnectionOwner(protocol: Int, source: String, sourcePort: Int, destination: String,
            destinationPort: Int): ConnectionOwner {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val uid = connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddresses.parseNumericAddress(source), sourcePort),
                InetSocketAddress(InetAddresses.parseNumericAddress(destination), destinationPort),
            )
            check(uid >= 0 && uid != Process.myUid() && uid !in disallowedUids && allowedUids?.contains(uid) != false) {
                "vpn_connection_owner_unavailable"
            }
            return ConnectionOwner().apply { userId = uid }
        }
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            interfaceListener = listener
            updateInterface()
        }
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            interfaceListener = null
        }
        override fun getInterfaces(): NetworkInterfaceIterator {
            val network = underlay.currentObservation().candidate?.network
            val links = network?.let(connectivity::getLinkProperties)
            val caps = network?.let(connectivity::getNetworkCapabilities)
            val systemInterface = links?.interfaceName?.let(JavaNetworkInterface::getByName)
            val values = if (systemInterface == null || caps == null) emptyList() else listOf(
                io.nekohasekai.libbox.NetworkInterface().apply {
                    name = systemInterface.name
                    index = systemInterface.index
                    mtu = systemInterface.mtu
                    addresses = strings(links.linkAddresses.map { it.toString() })
                    dnsServer = strings(links.dnsServers.mapNotNull { it.hostAddress })
                    gateway = strings(links.routes.filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.hostAddress })
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                    type = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                })
            val iterator = values.iterator()
            return object : NetworkInterfaceIterator {
                override fun hasNext() = iterator.hasNext()
                override fun next() = iterator.next()
            }
        }
        override fun underNetworkExtension() = false
        override fun includeAllNetworks() = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() = Unit
        override fun sendNotification(notification: Notification) = Unit
        override fun cancelNotification(identifier: String, typeId: Int) = Unit
        override fun startNeighborMonitor(listener: NeighborUpdateListener) = error("native_neighbor_rules_not_supported")
        override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
        override fun registerMyInterface(name: String) = Unit
        override fun usePlatformShell() = false
        override fun checkPlatformShell() = error("native_shell_not_supported")
        override fun openShellSession(user: PlatformUser?, command: String?, environment: StringIterator?, term: String?,
            rows: Int, columns: Int): ShellSession = error("native_shell_not_supported")
        override fun lookupUser(username: String): PlatformUser = error("native_shell_not_supported")
        override fun lookupSFTPServer(): String = error("native_sftp_not_supported")
        override fun readSystemSSHHostKey(): String = error("native_ssh_not_supported")
        override fun tailscaleHostname() = ""
        override fun usePlatformBridge() = false
        override fun createBridge(options: BridgeOptions): BridgeSession = error("native_bridge_not_supported")
    }

    private val localDns = object : LocalDNSTransport {
        override fun raw() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        override fun exchange(context: ExchangeContext, message: ByteArray) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("raw_dns_not_supported")
            val network = physicalNetwork()
            awaitDnsQuery(onCancel = { cancel -> context.onCancel { cancel() } },
                onCancelled = { context.errnoCode(OsConstants.ECANCELED) }) { cancellation, complete ->
                DnsResolver.getInstance().rawQuery(network, message, DnsResolver.FLAG_NO_RETRY,
                    Executor { it.run() }, cancellation, object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            // Preserve the complete DNS response, including NXDOMAIN/NODATA and non-address records.
                            complete { context.rawSuccess(answer) }
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            complete { context.errnoCode(OsConstants.EIO) }
                        }
                    })
            }
        }
        override fun lookup(context: ExchangeContext, family: String, domain: String) {
            // The native adapter selects Lookup only below API 29; newer Android uses rawQuery above.
            val network = physicalNetwork()
            // API 26–28 cannot cancel platform DNS; the native context stops waiting.
            context.success(network.getAllByName(domain).filter {
                if (family == "ip4") it is Inet4Address else it !is Inet4Address
            }.mapNotNull { it.hostAddress }.joinToString("\n"))
        }
    }

    private fun flushRuDiagnostics(diagnostics: RuNativeDiagnosticLog?, close: Boolean = false) {
        val blocks = diagnostics?.drain(close).orEmpty()
        if (blocks.isEmpty()) return
        // Keep preference IO off the native packet callback and reconnect lifecycle lock.
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            runCatching {
                val repository = SettingsRepository(context)
                val logging = repository.load().advancedSettings
                if (!logging.connectionLogsEnabled && !logging.errorLogsEnabled) return@runCatching
                for (details in blocks) repository.recordAppLog(
                    category = "vpn_core", message = "ru_native_diagnostic", details = details,
                )
            }.onFailure { SafeLog.w(TAG, "Failed to store RU diagnostics", it) }
        }
    }

    private fun commandHandler(diagnostics: RuNativeDiagnosticLog?) = object : CommandServerHandler {
        override fun serviceStop() = onStopRequested()
        override fun serviceReload() = error("vpn_reload_owned_by_service")
        override fun getSystemProxyStatus() = SystemProxyStatus()
        override fun setSystemProxyEnabled(enabled: Boolean) = error("system_proxy_not_supported")
        override fun triggerNativeCrash() = error("native_crash_not_supported")
        override fun writeDebugMessage(message: String) { diagnostics?.append(message) }
        override fun connectSSHAgent(): Int = error("native_ssh_not_supported")
    }

    companion object {
        private const val TAG = "NokiVpnCore"
        private var setupComplete = false
        private val diagnosticSequence = AtomicLong()

        internal fun awaitDnsQuery(onCancel: (() -> Unit) -> Unit, onCancelled: () -> Unit,
                                   query: (CancellationSignal, (() -> Unit) -> Unit) -> Unit) {
            val done = CountDownLatch(1)
            val finished = AtomicBoolean(false)
            val cancellation = CancellationSignal()
            val complete: (() -> Unit) -> Unit = { action ->
                if (finished.compareAndSet(false, true)) {
                    try { action() } finally { done.countDown() }
                }
            }
            onCancel {
                // Win completion before cancelling Android: cancellation can synchronously invoke onError.
                complete { try { cancellation.cancel() } finally { onCancelled() } }
            }
            try {
                if (!finished.get()) query(cancellation, complete)
                done.await()
            } finally {
                // A thrown query/interrupt must not leave an outstanding resolver callback alive.
                complete { cancellation.cancel() }
            }
        }

        @Synchronized
        private fun setup(context: Context) {
            if (setupComplete) return
            val working = File(context.noBackupFilesDir, "vpn-core").apply { check(isDirectory || mkdirs()) }
            Libbox.setup(SetupOptions().apply {
                basePath = working.absolutePath
                workingPath = working.absolutePath
                tempPath = context.cacheDir.absolutePath
                fixAndroidStack = true
                logMaxLines = 32L
                oomKillerDisabled = true
                crashReportSource = "noki"
            })
            setupComplete = true
        }

        private fun strings(values: List<String>): StringIterator {
            val iterator = values.iterator()
            return object : StringIterator {
                override fun len() = values.size
                override fun hasNext() = iterator.hasNext()
                override fun next() = iterator.next()
            }
        }
    }
}

internal class RuNativeDiagnosticLog(private val attemptId: String) {
    private val lock = Any()
    private val events = ArrayList<String>()
    private var closed = false
    private var discarded = 0

    fun append(message: String) {
        val event = sanitize(message) ?: return
        synchronized(lock) {
            if (closed) return
            if (events.size < 144) events += event else discarded++
        }
    }

    fun drain(close: Boolean = false): List<String> = synchronized(lock) {
        if (close) closed = true
        if (events.isEmpty()) return@synchronized emptyList()
        val header = "revision=ru_rx_v1_20261115; attempt=$attemptId"
        val lines = events.toList() + if (discarded > 0) listOf("discarded=$discarded") else emptyList()
        events.clear()
        discarded = 0
        val blocks = ArrayList<String>()
        var block = header
        for (line in lines) {
            if (block.length + 1 + line.length > 1800) {
                blocks += block
                block = header
            }
            block += "\n$line"
        }
        blocks += block
        blocks
    }

    private fun sanitize(message: String): String? {
        if (!message.startsWith(PREFIX) || message.length > 1024) return null
        val fields = LinkedHashMap<String, String>()
        for (token in message.removePrefix(PREFIX).split(' ')) {
            val match = TOKEN.matchEntire(token) ?: return null
            val (key, value) = match.destructured
            if (key !in FIELD_ORDER || fields.put(key, value) != null) return null
            val safe = when (key) {
                "event" -> value in EVENTS
                "reason" -> value in REASONS
                "sender", "receiver" -> INDEX.matches(value)
                "ip" -> value == "4" || value == "6"
                "ok", "checksum_ok" -> value == "0" || value == "1"
                else -> NUMBER.matches(value) && value.toULongOrNull() != null
            }
            if (!safe) return null
        }
        if (!fields.keys.containsAll(listOf("event", "seq", "ms", "utc_ms"))) return null
        return FIELD_ORDER.mapNotNull { key -> fields[key]?.let { "$key=$it" } }.joinToString(" ")
    }

    private companion object {
        const val PREFIX = "noki_ru_diag_v1 "
        val TOKEN = Regex("([a-z_]+)=([a-z0-9_]+)")
        val NUMBER = Regex("[0-9]{1,20}")
        val INDEX = Regex("[0-9a-f]{12}")
        val FIELD_ORDER = listOf("event", "seq", "ms", "utc_ms", "gen", "len", "type", "sender", "receiver",
            "counter", "ip", "proto", "flags", "tcp_seq", "tcp_ack", "ok", "checksum_ok", "errno", "reason", "rx_bytes", "tx_bytes", "handshake")
        val EVENTS = setOf("start", "bind_open", "socket_open", "udp_rx", "udp_tx", "connect_error", "read_error",
            "write_error", "bind_close", "inner_rx", "inner_tx", "stack_rx", "stack_deliver", "tcp_dial_done", "wg_state", "peer_stats", "close")
        val REASONS = setOf("handshake_response", "handshake_initiation", "keepalive", "invalid_mac", "invalid_response",
            "invalid_initiation", "disallowed_source", "invalid_ip", "unknown_type", "keypair_error", "tun_write_error", "receive_error")
    }
}
