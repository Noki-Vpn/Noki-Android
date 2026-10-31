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
