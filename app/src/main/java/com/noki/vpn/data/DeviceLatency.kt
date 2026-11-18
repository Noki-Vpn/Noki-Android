package com.noki.vpn.data

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

object DeviceLatency {
    internal const val MAX_CONCURRENT_DNS_LOOKUPS = 4

    fun measureTcpConnectMs(rawHost: String, port: Int, timeoutMs: Int = 1800): Int? {
        return measureTcpConnectMs(rawHost, port, timeoutMs, onDiagnostic = {})
    }

    internal fun measureTcpConnectMs(
        rawHost: String,
        port: Int,
        timeoutMs: Int,
        onDiagnostic: (String) -> Unit,
        socketFactory: () -> Socket = ::Socket,
        lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ): Int? {
        val started = System.nanoTime()
        fun report(result: String) = onDiagnostic(
            "result=$result; elapsed_ms=${(System.nanoTime() - started) / 1_000_000L}",
        )
        val host = normalizeHost(rawHost) ?: run { report("invalid_host"); return null }
        val safePort = port.coerceIn(1, 65535)
        val safeTimeout = timeoutMs.coerceIn(250, 5000)
        var dnsFailure = "dns_no_address"
        val address = resolveAddress(host, safeTimeout, onFailure = { dnsFailure = it }, lookup = lookup)
            ?: run { report(dnsFailure); return null }
        val resolutionMs = (System.nanoTime() - started) / 1_000_000L
        if (resolutionMs >= safeTimeout) { report("dns_timeout"); return null }
        return try {
            socketFactory().use { socket ->
                socket.connect(
                    InetSocketAddress(address, safePort),
                    (safeTimeout - resolutionMs).toInt().coerceAtLeast(1),
                )
            }
            ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1).also { report("connected") }
        } catch (error: IOException) {
            report(tcpFailureReason(error))
            null
        } catch (_: SecurityException) {
            report("permission_denied")
            null
        } catch (_: IllegalArgumentException) {
            report("invalid_address")
            null
        }
    }

    internal fun tcpFailureReason(error: IOException): String = when (error) {
        is SocketTimeoutException -> "tcp_timeout"
        is NoRouteToHostException -> "no_route"
        is ConnectException -> if (error.message.orEmpty().contains("refused", ignoreCase = true)) {
            "connection_refused"
        } else "connect_error"
        else -> "socket_error"
    }

    fun measureIcmpPingMs(rawHost: String, count: Int = 3, timeoutSeconds: Int = 2): Int? {
        val host = normalizeHost(rawHost) ?: return null
        val safeTimeout = timeoutSeconds.coerceIn(1, 5)
        val address = resolveAddress(host, safeTimeout * 1_000) ?: return null
        val ipAddress = address.hostAddress ?: return null
        val output = runPing(ipAddress, count, safeTimeout) ?: return null
        return parsePingAverageMs(output)
    }

    internal fun measureIcmpOnInterface(
        rawIp: String,
        interfaceName: String,
        runCommand: (List<String>, Long) -> String? = ::runPingProcess,
    ): Int? {
        val ip = rawIp.trim()
        val octets = ip.split('.')
        val ipv4 = octets.size == 4 && octets.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
        }
        val ipv6 = ':' in ip && ip.all { it in "0123456789abcdefABCDEF:." } &&
            runCatching { InetAddress.getByName(ip) is java.net.Inet6Address }.getOrDefault(false)
        if ((!ipv4 && !ipv6) || interfaceName.isBlank() || interfaceName.any { it.isWhitespace() }) return null
        return runPing(ip, 1, 1, interfaceName, runCommand)?.let(::parsePingAverageMs)
    }

    fun normalizeHost(rawHost: String): String? {
        var host = rawHost.trim()
        if (host.isBlank()) return null
        host = host.substringAfter("://", host)
        host = host.substringBefore("/")
        host = host.substringBefore("?")
        host = host.substringBefore("#")
        host = if (host.startsWith("[") && host.contains("]")) {
            host.substringAfter("[").substringBefore("]")
        } else if (host.count { it == ':' } == 1) {
            host.substringBefore(":")
        } else {
            host
        }
        return host.takeIf { it.isNotBlank() }
    }

    internal fun resolveAddress(
        host: String,
        timeoutMs: Int,
        onFailure: (String) -> Unit = {},
        lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ): InetAddress? {
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { part ->
                part.isNotEmpty() && part.all { it in '0'..'9' } &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }) {
            return InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
        }
        val safeTimeoutMs = timeoutMs.coerceIn(1, 5_000)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safeTimeoutMs.toLong())
        val slotAcquired = try {
            dnsLookupSlots.tryAcquire(safeTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            onFailure("dns_interrupted")
            return null
        }
        if (!slotAcquired) { onFailure("dns_queue_timeout"); return null }

        val lookupTask = DnsLookupTask(
            lookup = { lookup(host) },
            onSlotReleased = dnsLookupSlots::release,
        )
        try {
            dnsExecutor.execute(lookupTask)
        } catch (_: RejectedExecutionException) {
            lookupTask.releaseSlotOnce()
            onFailure("dns_executor_rejected")
            return null
        } catch (_: SecurityException) {
            lookupTask.releaseSlotOnce()
            onFailure("dns_permission_denied")
            return null
        }
        return try {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) { onFailure("dns_timeout"); return null }
            val addresses = lookupTask.get(remainingNanos, TimeUnit.NANOSECONDS)
            addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        } catch (_: TimeoutException) {
            onFailure("dns_timeout")
            null
        } catch (_: ExecutionException) {
            onFailure("dns_error")
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            onFailure("dns_interrupted")
            null
        } finally {
            val cancelled = lookupTask.cancel(true)
            if (cancelled && dnsExecutor.remove(lookupTask)) {
                lookupTask.releaseSlotOnce()
            }
        }
    }

    private fun runPing(address: String, count: Int, timeoutSeconds: Int,
        interfaceName: String? = null, runCommand: (List<String>, Long) -> String? = ::runPingProcess): String? {
        val safeCount = count.coerceIn(1, 5)
        val safeTimeout = timeoutSeconds.coerceIn(1, 5)
        return runPingCommand("/system/bin/ping", address, safeCount, safeTimeout, interfaceName, runCommand)
            ?: runPingCommand("ping", address, safeCount, safeTimeout, interfaceName, runCommand)
    }

    private fun runPingCommand(
        command: String,
        address: String,
        count: Int,
        timeoutSeconds: Int,
        interfaceName: String?,
        runCommand: (List<String>, Long) -> String?,
    ): String? = runCommand(buildList {
        add(command)
        if (interfaceName != null) { add("-I"); add(interfaceName) }
        addAll(listOf("-c", count.toString(), "-W", timeoutSeconds.toString(), address))
    }, (count * timeoutSeconds + 3).toLong())

    private fun runPingProcess(arguments: List<String>, timeoutSeconds: Long): String? {
        var process: Process? = null
        return try {
            process = ProcessBuilder(arguments)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished || process.exitValue() != 0) return null
            val output = process.inputStream.bufferedReader().use { reader ->
                buildString {
                    val buffer = CharArray(1024)
                    while (length < MAX_PING_OUTPUT_CHARS) {
                        val read = reader.read(buffer, 0, minOf(buffer.size, MAX_PING_OUTPUT_CHARS - length))
                        if (read < 0) break
                        append(buffer, 0, read)
                    }
                }
            }
            output.takeIf { it.isNotBlank() }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
        }
    }

    private fun parsePingAverageMs(output: String): Int? {
        val normalized = output.replace(',', '.')
        Regex("""(?:rtt|round-trip).*?=\s*([\d.]+)/([\d.]+)/([\d.]+)""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(2)
            ?.toDoubleOrNull()
            ?.let { return it.roundToInt().coerceAtLeast(1) }

        val samples = Regex("""time[=<]([\d.]+)\s*ms""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toDoubleOrNull() }
            .toList()
        if (samples.isEmpty()) return null
        return samples.sorted()[samples.size / 2].roundToInt().coerceAtLeast(1)
    }

    private const val MAX_PING_OUTPUT_CHARS = 8 * 1024
    private val dnsLookupSlots = Semaphore(MAX_CONCURRENT_DNS_LOOKUPS, true)
    private val dnsExecutor = ThreadPoolExecutor(
        MAX_CONCURRENT_DNS_LOOKUPS,
        MAX_CONCURRENT_DNS_LOOKUPS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_CONCURRENT_DNS_LOOKUPS),
    ) { runnable ->
        Thread(runnable, "noki-dns").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    private class DnsLookupTask(
        lookup: () -> Array<InetAddress>,
        private val onSlotReleased: () -> Unit,
    ) : FutureTask<Array<InetAddress>>(lookup) {
        private val slotReleased = AtomicBoolean(false)

        override fun run() {
            try {
                super.run()
            } finally {
                releaseSlotOnce()
            }
        }

        fun releaseSlotOnce() {
            if (slotReleased.compareAndSet(false, true)) onSlotReleased()
        }
    }
}
