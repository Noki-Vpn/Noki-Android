package com.noki.vpn.vpn

import android.content.Context
import android.net.LocalServerSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.data.StoredSettings
import java.io.File
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

data class YoutubeConnectionConfig(val port: Int) {
    init { require(port in 1..65535) { "youtube_port_invalid" } }
}

internal class YoutubeEndpoint(val port: Int, val descriptor: FileDescriptor,
                               private val closeDescriptor: (FileDescriptor) -> Unit) : AutoCloseable {
    override fun close() = closeDescriptor(descriptor)
}

internal interface YoutubeRuntime {
    suspend fun start()
    suspend fun awaitExit(): Int
    fun stop()
}

/** Owns one reserved loopback endpoint and child; AppVpnService retains the core and TUN. */
internal class YoutubeConnection(
    private val scope: CoroutineScope,
    private val networkIdentity: () -> String?,
    private val endpointFactory: () -> YoutubeEndpoint,
    private val runtimeFactory: (YoutubeEndpoint, String) -> YoutubeRuntime,
    private val onFailure: (Throwable) -> Unit,
    private val retryMillis: Long = 2_000,
) {
    private var endpoint: YoutubeEndpoint? = null
    private var target: String? = null
    private var network: String? = null
    private var job: Job? = null
    private var runtime: YoutubeRuntime? = null
    private var sequence = 0L
    private var rejectedTarget: String? = null

    @Synchronized fun configFor(settings: StoredSettings): YoutubeConnectionConfig? {
        val requested = targetFor(settings)
        if (requested == null) { stop(); return null }
        if (requested == rejectedTarget) return null
        if (requested != target) {
            stop()
            target = requested
        }
        val selected = endpoint ?: try { endpointFactory().also { endpoint = it } } catch (error: Exception) {
            rejectedTarget = requested
            onFailure(error)
            return null
        }
        return YoutubeConnectionConfig(selected.port)
    }

    @Synchronized fun start(settings: StoredSettings) {
        if (configFor(settings) == null) return
        val nextNetwork = networkIdentity()
        if (network == nextNetwork && job?.isActive == true) return
        stopRuntime()
        network = nextNetwork
        val owner = sequence
        val selected = checkNotNull(endpoint)
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                var child: YoutubeRuntime? = null
                try {
                    synchronized(this@YoutubeConnection) {
                        if (owner != sequence) return@launch
                        val signature = networkIdentity()
                        if (signature != null) {
                            runtime?.stop()
                            runtime = null
                            network = signature
                            child = runtimeFactory(selected, signature)
                            runtime = child
                        }
                    }
                    child?.let {
                        withTimeout(5_000) { it.start() }
                        error("youtube_byedpi_exited_${it.awaitExit()}")
                    }
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    onFailure(cancelled)
                } catch (error: Exception) {
                    onFailure(error)
                } finally {
                    synchronized(this@YoutubeConnection) {
                        child?.let { current ->
                            if (runCatching { current.stop() }.onFailure(onFailure).isSuccess && runtime === current) runtime = null
                        }
                    }
                }
                delay(retryMillis)
            }
        }
    }

    @Synchronized fun stop() {
        stopRuntime()
        endpoint?.let { runCatching { it.close() }.onFailure(onFailure) }
        endpoint = null
        target = null
        network = null
        rejectedTarget = null
    }

    private fun stopRuntime() {
        sequence++
        job?.cancel()
        job = null
        runtime?.let { current ->
            if (runCatching { current.stop() }.onFailure(onFailure).isSuccess) runtime = null
        }
    }

    companion object {
        internal fun targetFor(settings: StoredSettings): String? {
            if (!settings.isAuthenticated || settings.userProfile.isFreePlan ||
                !settings.advancedSettings.youtubeNoAdsEnabled || settings.backendDeviceId.isBlank()) return null
            return listOf(settings.userProfile.backendUserId, settings.backendDeviceId, settings.backendDeviceKey).joinToString("|")
        }

        fun android(context: Context, scope: CoroutineScope, underlay: AndroidUnderlyingNetworkSource,
                    protectSocket: (Int) -> Boolean): YoutubeConnection {
            val repository = SettingsRepository(context)
            fun log(message: String, details: String? = null, error: Throwable? = null) {
                runCatching { repository.recordAppLog("vpn", level = if (error == null) "info" else "error",
                    message = message, details = details, errorType = error?.javaClass?.simpleName) }
            }
            return YoutubeConnection(scope, { underlay.currentSnapshot()?.signature }, {
                val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)
                try {
                    Os.bind(fd, InetAddress.getByName("127.0.0.1"), 0)
                    Os.listen(fd, 128)
                    YoutubeEndpoint((Os.getsockname(fd) as InetSocketAddress).port, fd, Os::close)
                } catch (error: Exception) {
                    Os.close(fd)
                    throw error
                }
            }, { endpoint, signature ->
                val snapshot = checkNotNull(underlay.currentSnapshot()).also {
                    check(it.signature == signature) { "youtube_underlay_changed" }
                }
                val network = checkNotNull(snapshot.network) { "youtube_underlay_unavailable" }
                ByeDpiProcess(File(context.applicationInfo.nativeLibraryDir, "libnoki_byedpi.so"), endpoint,
                    bindSocket = { fd ->
                        check(underlay.currentSnapshot()?.signature == signature) { "youtube_underlay_changed" }
                        ParcelFileDescriptor.dup(fd).use { socket ->
                            check(protectSocket(socket.fd)) { "youtube_socket_protect_failed" }
                            network.bindSocket(socket.fileDescriptor)
                        }
                        check(underlay.currentSnapshot()?.signature == signature) { "youtube_underlay_changed" }
                    },
                    onProfile = { log("youtube_byedpi_ready", it) },
                    onDiagnostic = { log("youtube_byedpi_native", it) })
            }, { log("youtube_byedpi_failed", details = it.message, error = it) })
        }
    }
}

private class ByeDpiProcess(
    private val executable: File,
    private val endpoint: YoutubeEndpoint,
    private val bindSocket: (FileDescriptor) -> Unit,
    private val onProfile: (String) -> Unit,
    private val onDiagnostic: (String) -> Unit,
) : YoutubeRuntime {
    private val ready = CompletableDeferred<Unit>()
    private val exited = CompletableDeferred<Int>()
    private val stopped = AtomicBoolean(false)
    private var process: Process? = null
    private var server: LocalServerSocket? = null
    private var protector: Thread? = null
    private var output: Thread? = null

    override suspend fun start() { launch(); ready.await() }

    @Synchronized private fun launch() {
        check(!stopped.get()) { "youtube_byedpi_stopped" }
        check(process == null && executable.canExecute()) { "youtube_byedpi_binary_unavailable" }
        val name = "noki-byedpi-${UUID.randomUUID()}"
        val listener = LocalServerSocket(name)
        server = listener
        protector = Thread({
            try {
                while (!stopped.get()) listener.accept().use { socket ->
                    socket.soTimeout = 2_000
                    var descriptors: Array<FileDescriptor>? = null
                    val accepted = runCatching {
                        check(!stopped.get()) { "youtube_byedpi_stopped" }
                        check(socket.peerCredentials.uid == android.os.Process.myUid()) { "youtube_protect_peer_invalid" }
                        val request = socket.inputStream.read()
                        check(request >= 0) { "youtube_protect_request_empty" }
                        descriptors = socket.ancillaryFileDescriptors
                        if (request == 2) {
                            check(descriptors.isNullOrEmpty()) { "youtube_listener_request_invalid" }
                            socket.setFileDescriptorsForSend(arrayOf(endpoint.descriptor))
                        } else {
                            check(request == '1'.code) { "youtube_protect_request_invalid" }
                            bindSocket(checkNotNull(descriptors).single())
                        }
                        check(!stopped.get()) { "youtube_byedpi_stopped" }
                    }.isSuccess
                    try {
                        socket.outputStream.write(if (accepted) 0 else 1)
                        socket.outputStream.flush()
                    } finally {
                        runCatching { socket.setFileDescriptorsForSend(null) }
                        descriptors?.forEach { runCatching { Os.close(it) } }
                    }
                }
            } catch (error: Exception) {
                if (!stopped.get()) {
                    ready.completeExceptionally(error)
                    process?.destroy()
                }
            }
        }, "noki-byedpi-protect").apply { isDaemon = true; start() }
        val child = ProcessBuilder(executable.absolutePath, "--ip", "127.0.0.1", "--port", endpoint.port.toString(),
            "--max-conn", "4096", "--conn-ip", "0.0.0.0", "--no-udp", "--no-domain",
            "--proto", "tls,http", "--protect-path", "@$name", "--noki-listen-path", "@$name",
            "--noki-android-profile", "--noki-parent", android.os.Process.myPid().toString()).redirectErrorStream(true).start()
        process = child
        output = Thread({
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("NOKI_READY ")) {
                            val match = Regex("NOKI_READY port=([0-9]+) profile=(linux-md5sig|android-disorder-md5sig-unavailable)")
                                .matchEntire(line) ?: error("youtube_byedpi_readiness_invalid")
                            check(match.groupValues[1].toInt() == endpoint.port) { "youtube_byedpi_port_invalid" }
                            onProfile(match.groupValues[2])
                            ready.complete(Unit)
                        } else if (line.isNotBlank()) onDiagnostic(line.take(512))
                    }
                }
                val code = child.waitFor()
                exited.complete(code)
                ready.completeExceptionally(IllegalStateException("youtube_byedpi_exited_$code"))
            } catch (error: Exception) {
                ready.completeExceptionally(error)
                exited.completeExceptionally(error)
                child.destroy()
            }
        }, "noki-byedpi-output").apply { isDaemon = true; start() }
    }

    override suspend fun awaitExit(): Int = exited.await()

    @Synchronized override fun stop() {
        stopped.set(true)
        runCatching { server?.close() }
        process?.let { child ->
            child.destroy()
            if (!child.waitFor(1, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                check(child.waitFor(1, TimeUnit.SECONDS)) { "youtube_byedpi_stop_timeout" }
            }
            runCatching { child.inputStream.close() }
            runCatching { child.outputStream.close() }
        }
        for (thread in listOfNotNull(protector, output)) if (thread !== Thread.currentThread()) thread.join(500)
        ready.cancel()
        exited.cancel()
    }
}
