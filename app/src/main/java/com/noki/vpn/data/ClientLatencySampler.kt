package com.noki.vpn.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import android.content.Context
import android.net.ConnectivityManager
import com.noki.vpn.vpn.AndroidUnderlyingNetworkSource

internal fun clientLatencyTargetKey(location: ServerLocation): String? =
    clientLatencyTargetKey(location.code, location.host)

internal fun clientLatencyTargetKey(server: VpnServer): String? =
    clientLatencyTargetKey(server.id, clientLatencyProbeIdentity(server.probeHost ?: server.host, server.probePort, server.probeIp))

internal fun clientLatencyTargetKey(node: RuForeignNode): String =
    "ru:${node.id}\u0000${clientLatencyProbeIdentity(node.probeHost, node.probePort, node.probeIp)}"

private fun clientLatencyProbeIdentity(host: String?, port: Int?, ip: String?): String =
    "${host.orEmpty().trim().lowercase(Locale.ROOT)}:${port?.toString().orEmpty()}|${ip.orEmpty().trim().lowercase(Locale.ROOT)}"

internal fun ServerLocation.withClientLatencies(samples: Map<String, Int>): ServerLocation {
    val measured = servers.map { server -> server.copy(latencyMs = clientLatencyTargetKey(server)?.let(samples::get)) }
    return copy(
        servers = measured,
        latencyMs = if (measured.isNotEmpty()) measured.filter { it.isOnline }.mapNotNull { it.latencyMs }.minOrNull()
            else clientLatencyTargetKey(this)?.let(samples::get),
    )
}

internal fun clientLatencyTargetKey(codeValue: String, hostValue: String): String? {
    val code = codeValue.trim().uppercase(Locale.ROOT)
    val host = hostValue.trim().lowercase(Locale.ROOT)
    if (code.isBlank() || host.isBlank()) return null
    return "$code\u0000$host"
}

class ClientLatencySampler(
    private val tcpConnect: (host: String, port: Int, timeoutMs: Int) -> Int? = DeviceLatency::measureTcpConnectMs,
    private val icmpPing: (host: String, count: Int, timeoutSeconds: Int) -> Int? = DeviceLatency::measureIcmpPingMs,
    private val locationTimeoutMillis: Long = 8_000L,
    private val context: Context? = null,
    private val tcpAttempts: Int = LATENCY_TCP_ATTEMPTS,
    private val boundIcmpPing: (String, String) -> Int? = { ip, name -> DeviceLatency.measureIcmpOnInterface(ip, name) },
) {
    private data class ProbeTarget(val key: String?, val host: String?, val port: Int?, val ip: String? = null)
    fun networkSignature(): String = context?.let { AndroidUnderlyingNetworkSource(it).currentSnapshot()?.signature } ?: "none"

    suspend fun measure(
        locations: List<ServerLocation>,
    ): Map<String, Int> {
        val targets = locations.asSequence().filter { it.isOnline }.flatMap { location ->
            location.servers.asSequence().filter { it.isOnline }.map {
                ProbeTarget(clientLatencyTargetKey(it), it.probeHost ?: it.host, it.probePort, it.probeIp)
            }
        }.distinctBy { it.key }.toList()
        return measureTargets(targets, allowIcmpFallback = true)
    }

    suspend fun <T> measureNodes(nodes: List<T>, id: (T) -> String,
        host: (T) -> String?, port: (T) -> Int?, ip: (T) -> String?): Map<String, Int> =
        measureTargets(nodes.distinctBy(id).take(64).map { ProbeTarget(id(it), host(it), port(it), ip(it)) }, false)

    private suspend fun measureTargets(
        targets: List<ProbeTarget>, allowIcmpFallback: Boolean,
    ): Map<String, Int> = coroutineScope {
        val snapshot = context?.let { AndroidUnderlyingNetworkSource(it).currentSnapshot() }
        val network = snapshot?.network
        if (context != null && network == null) return@coroutineScope emptyMap()
        val interfaceName = if (context != null && network != null) {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).getLinkProperties(network)?.interfaceName
        } else null
        val probeSlots = Semaphore(DeviceLatency.MAX_CONCURRENT_DNS_LOOKUPS)
        targets.asSequence()
            .distinct()
            .mapNotNull { (key, rawHost, port, probeIp) ->
                val targetKey = key ?: return@mapNotNull null
                val host = rawHost?.trim().orEmpty()
                async {
                    val latency = withTimeoutOrNull(locationTimeoutMillis.coerceAtLeast(1L)) {
                        probeSlots.withPermit {
                            runInterruptible(Dispatchers.IO) {
                                if (port != null && port in 1..65535 && host.isNotBlank()) {
                                    val samples = List(tcpAttempts.coerceIn(1, LATENCY_TCP_ATTEMPTS)) {
                                        if (network == null) tcpConnect(host, port, LATENCY_TCP_TIMEOUT_MS)
                                        else DeviceLatency.measureTcpConnectMs(host, port, LATENCY_TCP_TIMEOUT_MS,
                                            onDiagnostic = {}, socketFactory = { network.socketFactory.createSocket() },
                                            lookup = network::getAllByName)
                                    }.filterNotNull().sorted()
                                    samples.getOrNull(samples.size / 2)
                                        ?: if (context == null && allowIcmpFallback) {
                                            icmpPing(host, LATENCY_ICMP_COUNT, LATENCY_ICMP_TIMEOUT_SECONDS)
                                        } else null
                                } else if (probeIp != null && interfaceName != null) {
                                    boundIcmpPing(probeIp, interfaceName)
                                } else null
                            }
                        }
                    }
                    latency?.takeIf { it in 1..3000 }?.let { targetKey to it }
                }
            }
            .toList()
            .awaitAll()
            .filterNotNull()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, samples) -> samples.min() }
            .takeIf { context == null || AndroidUnderlyingNetworkSource(context).currentSnapshot()?.signature == snapshot?.signature }
            .orEmpty()
    }

    companion object {
        fun startup(context: Context): ClientLatencySampler =
            ClientLatencySampler(context = context, locationTimeoutMillis = 750L, tcpAttempts = 1)

        private const val LATENCY_TCP_ATTEMPTS = 3
        private const val LATENCY_TCP_TIMEOUT_MS = 650
        private const val LATENCY_ICMP_COUNT = 1
        private const val LATENCY_ICMP_TIMEOUT_SECONDS = 1
    }
}
