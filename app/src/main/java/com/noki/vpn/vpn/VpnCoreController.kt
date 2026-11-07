package com.noki.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.noki.vpn.data.SettingsRepository
import io.nekohasekai.libbox.*
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class VpnCoreController(
    private val context: Context,
    private val protectSocket: ((Int) -> Boolean)? = null,
    private val onStopRequested: () -> Unit = {},
) : VpnCoreRuntime {
    private val underlay = AndroidUnderlyingNetworkSource(context)
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var server: CommandServer? = null
    @Volatile private var tunFd = -1
    @Volatile private var interfaceListener: InterfaceUpdateListener? = null
    @Volatile private var defaultInterface: Pair<String, Int>? = null

    @Synchronized
    override fun start(config: String, tunFd: Int): Boolean = try {
        setup(context)
        // startOrReloadService validates and constructs the same core once.
        stop()
        this.tunFd = tunFd
        val core = Libbox.newCommandServer(handler, platform)
        server = core
        core.startOrReloadService(config, OverrideOptions())
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
        val core = server ?: return
        server = null
        runCatching {
            core.cancelMeasureDelay()
            try { core.closeService() } finally { core.close() }
        }.onFailure { SafeLog.e(TAG, "Failed to stop VPN core", it) }
        tunFd = -1
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
            if (interfaceName == "lo") {
                check(protectSocket?.invoke(fd) != false) { "vpn_socket_protect_failed" }
                return
            }
            check(connectivity.getLinkProperties(physicalNetwork())?.interfaceName == interfaceName) {
                "underlay_interface_changed"
            }
            bindSocket(fd)
        }
        // libbox duplicates this FD internally; AppVpnService retains the original.
        override fun openTun(options: TunOptions): Int = tunFd.also { check(it >= 0) { "vpn_tun_unavailable" } }
        override fun useProcFS() = false
        override fun findConnectionOwner(protocol: Int, source: String, sourcePort: Int, destination: String,
            destinationPort: Int): ConnectionOwner = error("native_process_rules_not_supported")
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

    private val handler = object : CommandServerHandler {
        override fun serviceStop() = onStopRequested()
        override fun serviceReload() = error("vpn_reload_owned_by_service")
        override fun getSystemProxyStatus() = SystemProxyStatus()
        override fun setSystemProxyEnabled(enabled: Boolean) = error("system_proxy_not_supported")
        override fun triggerNativeCrash() = error("native_crash_not_supported")
        override fun writeDebugMessage(message: String) = Unit
        override fun connectSSHAgent(): Int = error("native_ssh_not_supported")
    }

    companion object {
        private const val TAG = "NokiVpnCore"
        private var setupComplete = false

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
