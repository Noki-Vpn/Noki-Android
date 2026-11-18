package com.noki.vpn.vpn

import android.app.Application
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.BackendRuWireGuardConfig
import com.noki.vpn.data.DefaultStoredSettingsFactory
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [VpnTunnelPolicyTest.TunnelAllocationShadow::class])
class VpnTunnelPolicyTest {
    @Test
    @Config(sdk = [35])
    fun bothTunBuildersKeepIpv6BlockedWithoutBypassWhileAdvertisingIpv4() {
        val service = Robolectric.buildService(VpnService::class.java).create().get()
        val settings = DefaultStoredSettingsFactory.create().copy(
            filterMode = AppFilterMode.ALL_APPS, selectedPackages = emptySet())
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820",
            java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1"), 1280, listOf("0.0.0.0/0"))
        val underlay = UnderlyingNetworkSnapshot(com.noki.vpn.data.EndpointRankingPolicy.NetworkKind.WIFI,
            "test-network", false, "test-network", org.robolectric.shadows.ShadowNetwork.newInstance(71))
        for (snapshot in listOf(null, underlay)) for (ru in listOf(false, true)) {
            val factory = AndroidTunInterfaceFactory(service)
            if (ru) factory.establishWireGuard(settings, snapshot, wg) else factory.establish(settings, snapshot)
            val config = requireNotNull(TunnelAllocationShadow.capturedConfig)
            val addresses = TunnelAllocationShadow.advertisedAddresses
            val routes = TunnelAllocationShadow.advertisedRoutes
            val dns = ReflectionHelpers.getField<List<String>>(config, "dnsServers")
            assertTrue(addresses.all { it.address is java.net.Inet4Address })
            assertEquals(listOf(if (ru) "10.240.1.2/32" else "10.10.0.2/32"), addresses.map { it.toString() })
            assertEquals(listOf("0.0.0.0/0"), routes.map { it.destination.toString() })
            assertEquals(listOf("198.18.0.1"), dns)
            assertFalse(ReflectionHelpers.getField<Boolean>(config, "allowIPv6"))
            assertFalse(ReflectionHelpers.getField<Boolean>(config, "allowBypass"))
            assertEquals(if (ru) 1280 else 1500, ReflectionHelpers.getField<Int>(config, "mtu"))
        }
    }

    @Test
    fun vpnAdvertisesReservedDnsInsteadOfPublicProvider() {
        val address = InetAddress.getByName(VpnTunnelPolicy.DNS_SERVER).address

        assertTrue(
            "VPN DNS must stay inside RFC 2544 benchmark space",
            address.size == 4 &&
                address[0].toInt() and 0xff == 198 &&
                address[1].toInt() and 0xfe == 18,
        )
    }

    @Test
    fun wireGuardAndroidBuilderAdvertisesOnlyTheReservedHijackAddress() {
        val service = Robolectric.buildService(VpnService::class.java).create().get()
        val settings = DefaultStoredSettingsFactory.create().copy(
            filterMode = AppFilterMode.ALL_APPS, selectedPackages = emptySet(),
        )
        val wg = BackendRuWireGuardConfig("203.0.113.10:51820", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 2 }),
            "10.240.1.2/32", listOf("1.1.1.1", "8.8.8.8"), 1280, listOf("0.0.0.0/0"))
        TunnelAllocationShadow.advertisedDns = emptyList()

        AndroidTunInterfaceFactory(service).establishWireGuard(settings, null, wg)

        assertEquals(listOf("198.18.0.1"), TunnelAllocationShadow.advertisedDns)
    }

    @Test
    fun platformDnsPreservesRawRecordsOnlyOnSupportedAndroidVersions() {
        val controller = VpnCoreController(RuntimeEnvironment.getApplication())
        val dns = ReflectionHelpers.getField<io.nekohasekai.libbox.LocalDNSTransport>(controller, "localDns")
        val original = Build.VERSION.SDK_INT
        try {
            for (sdk in listOf(28, 29, 34)) {
                ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
                assertEquals("Android API $sdk", sdk >= 29, dns.raw())
            }
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", original)
        }
    }

    @Test
    fun platformDnsCancellationWinsBeforeSynchronousAndLateCallbacks() {
        var cancel: () -> Unit = {}
        val results = mutableListOf<String>()
        VpnCoreController.awaitDnsQuery(onCancel = { cancel = it }, onCancelled = { results += "cancelled" }) { signal, complete ->
            signal.setOnCancelListener { complete { results += "synchronous error" } }
            cancel()
            assertTrue(signal.isCanceled)
            complete { results += "late answer" }
        }
        assertEquals(listOf("cancelled"), results)
        var dispatched = false
        VpnCoreController.awaitDnsQuery(onCancel = { it() }, onCancelled = {}) { _, _ -> dispatched = true }
        assertFalse(dispatched)
    }

    @Test
    fun platformDnsAnswerCompletesOnceBeforeLaterCancellation() {
        var cancel: () -> Unit = {}
        val results = mutableListOf<String>()
        VpnCoreController.awaitDnsQuery(onCancel = { cancel = it }, onCancelled = { results += "cancelled" }) { _, complete ->
            complete { results += "answer" }
            complete { results += "duplicate answer" }
            cancel()
        }
        assertEquals(listOf("answer"), results)
    }

    @Implements(VpnService.Builder::class)
    class TunnelAllocationShadow {
        @RealObject
        private lateinit var builder: VpnService.Builder

        // The JVM lacks Android's InetAddress.parseNumericAddress String overload path.
        @Implementation
        fun addAddress(address: String, prefix: Int): VpnService.Builder =
            builder.addAddress(InetAddress.getByName(address), prefix)

        @Implementation
        fun addRoute(address: String, prefix: Int): VpnService.Builder =
            builder.addRoute(InetAddress.getByName(address), prefix)

        @Implementation
        fun addDnsServer(address: String): VpnService.Builder =
            builder.addDnsServer(InetAddress.getByName(address))

        @Implementation
        fun addDisallowedApplication(packageName: String): VpnService.Builder = builder

        // Read the real Builder DNS configuration at the OS tunnel-allocation boundary.
        @Implementation
        fun establish(): ParcelFileDescriptor? {
            val config = ReflectionHelpers.getField<Any>(builder, "mConfig")
            capturedConfig = config
            advertisedAddresses = ReflectionHelpers.getField<List<android.net.LinkAddress>>(builder, "mAddresses").toList()
            advertisedRoutes = ReflectionHelpers.getField<List<android.net.RouteInfo>>(builder, "mRoutes").toList()
            advertisedDns = ReflectionHelpers.getField<List<String>?>(config, "dnsServers").orEmpty().toList()
            return null
        }

        companion object {
            var advertisedDns: List<String> = emptyList()
            var capturedConfig: Any? = null
            var advertisedAddresses: List<android.net.LinkAddress> = emptyList()
            var advertisedRoutes: List<android.net.RouteInfo> = emptyList()
        }
    }
}
