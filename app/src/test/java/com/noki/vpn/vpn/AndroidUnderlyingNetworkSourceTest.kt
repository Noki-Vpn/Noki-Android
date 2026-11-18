package com.noki.vpn.vpn

import com.noki.vpn.data.EndpointRankingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUnderlyingNetworkSourceTest {
    @Test fun foreignNodeIcmpUsesThePhysicalWifiInterfaceInsteadOfTheActiveVpn() = kotlinx.coroutines.runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        fun network(id: Int, type: Int, transport: Int, name: String): android.net.Network {
            val network = org.robolectric.shadows.ShadowNetwork.newInstance(id)
            val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED,
                type, 0, true, true)
            val caps = org.robolectric.util.ReflectionHelpers.callConstructor(android.net.NetworkCapabilities::class.java).apply {
                javaClass.getDeclaredMethod("addTransportType", Int::class.javaPrimitiveType).invoke(this, transport)
                for (capability in listOf(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET,
                    android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                    android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                    javaClass.getDeclaredMethod("addCapability", Int::class.javaPrimitiveType).invoke(this, capability)
                }
                javaClass.getDeclaredMethod(if (transport != android.net.NetworkCapabilities.TRANSPORT_VPN) "addCapability" else "removeCapability",
                    Int::class.javaPrimitiveType).invoke(this, android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            shadow.addNetwork(network, info)
            shadow.setNetworkCapabilities(network, caps)
            shadow.setLinkProperties(network, android.net.LinkProperties().apply { interfaceName = name })
            shadow.setActiveNetworkInfo(info)
            return network
        }
        val wifi = network(101, android.net.ConnectivityManager.TYPE_WIFI, android.net.NetworkCapabilities.TRANSPORT_WIFI, "wlan0")
        network(102, android.net.ConnectivityManager.TYPE_VPN, android.net.NetworkCapabilities.TRANSPORT_VPN, "tun9")
        assertEquals(wifi, AndroidUnderlyingNetworkSource(context).currentSnapshot()?.network)
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val sampler = com.noki.vpn.data.ClientLatencySampler(context = context, boundIcmpPing = { ip, name ->
            calls += ip to name
            if (ip.endsWith(".1")) 21 else 48
        })
        val nodes = listOf(com.noki.vpn.data.RuForeignNode("one", "FR", "FR", probeIp = "203.0.113.1"),
            com.noki.vpn.data.RuForeignNode("two", "NL", "NL", probeIp = "203.0.113.2"))
        assertEquals(mapOf("one" to 21, "two" to 48), sampler.measureNodes(nodes,
            { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp }))
        assertEquals(setOf("203.0.113.1" to "wlan0", "203.0.113.2" to "wlan0"), calls.toSet())
        shadow.removeNetwork(wifi)
        assertEquals(emptyMap<String, Int>(), sampler.measureNodes(nodes,
            { it.id }, { it.probeHost }, { it.probePort }, { it.probeIp }))
        assertEquals(2, calls.size)
    }
    @Test
    fun `connection signature tracks addresses and routes but ignores DNS and their ordering`() {
        val network = org.robolectric.shadows.ShadowNetwork.newInstance(101)
        fun links(addresses: List<String>, gateway: String = "192.0.2.1") = android.net.LinkProperties().apply {
            interfaceName = "wlan0"
            val addAddress = javaClass.getDeclaredMethod("addLinkAddress", android.net.LinkAddress::class.java)
            addresses.forEach {
                val address = org.robolectric.util.ReflectionHelpers.callConstructor(android.net.LinkAddress::class.java,
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, it))
                addAddress.invoke(this, address)
            }
            val route = org.robolectric.util.ReflectionHelpers.callConstructor(android.net.RouteInfo::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.net.IpPrefix::class.java,
                    android.net.IpPrefix(java.net.InetAddress.getByName("0.0.0.0"), 0)),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.net.InetAddress::class.java, java.net.InetAddress.getByName(gateway)),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, "wlan0"))
            javaClass.getDeclaredMethod("addRoute", android.net.RouteInfo::class.java).invoke(this, route)
        }
        fun signature(value: android.net.LinkProperties) = AndroidUnderlyingNetworkSource.connectionSignature(
            network, EndpointRankingPolicy.NetworkKind.WIFI, value)
        val addresses = listOf("192.0.2.2/24", "2001:db8::2/64")
        val original = signature(links(addresses))
        val reordered = links(addresses.reversed()).apply {
            javaClass.getDeclaredMethod("addDnsServer", java.net.InetAddress::class.java)
                .invoke(this, java.net.InetAddress.getByName("192.0.2.53"))
        }
        assertEquals(original, signature(reordered))
        assertNotEquals(original, signature(links(listOf("192.0.2.3/24", "2001:db8::2/64"))))
        assertNotEquals(original, signature(links(addresses, "192.0.2.254")))
    }

    @Test
    fun `Android link property callbacks reach the debounced monitor`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val scheduler = HandlerDelayedTaskScheduler(android.os.Handler(android.os.Looper.getMainLooper()))
        val monitor = VpnNetworkMonitor.android(context, AndroidUnderlyingNetworkSource(context), scheduler, 25L)
        var observations = 0
        monitor.start { observations++ }
        try {
            val callback = shadowOf(manager).networkCallbacks.single()
            val network = org.robolectric.shadows.ShadowNetwork.newInstance(101)
            callback.onLinkPropertiesChanged(network, android.net.LinkProperties())
            callback.onLinkPropertiesChanged(network, android.net.LinkProperties())
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(25))
            assertEquals(1, observations)
        } finally { monitor.stop() }
    }

    @Test
    fun `dual Wi-Fi and cellular capabilities use Wi-Fi for ranking and handover`() {
        assertEquals(
            EndpointRankingPolicy.NetworkKind.WIFI,
            AndroidUnderlyingNetworkSource.networkKind(
                hasWifiTransport = true,
                hasCellularTransport = true,
            ),
        )
    }
}
