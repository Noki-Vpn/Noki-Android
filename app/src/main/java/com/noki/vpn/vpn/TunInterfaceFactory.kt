package com.noki.vpn.vpn

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.BackendRuWireGuardConfig

internal interface TunHandle : AutoCloseable {
    val fd: Int
    val routingRules: AppVpnRoutingRules.Ready? get() = null
}

internal interface TunInterfaceFactory {
    fun establish(settings: StoredSettings, underlay: UnderlyingNetworkSnapshot?): TunHandle?
}

internal class TunInterfaceConfigurationException(
    val reason: String,
    cause: Throwable? = null,
) : Exception(reason, cause)

internal class AndroidTunInterfaceFactory(
    private val service: VpnService,
) : TunInterfaceFactory {
    override fun establish(settings: StoredSettings, underlay: UnderlyingNetworkSnapshot?): TunHandle? {
        val builder = service.Builder()
            .setSession(settings.profile.remark.ifBlank { "Noki Vpn" })
            .setMtu(VpnTunnelPolicy.MTU)
            .addAddress("10.10.0.2", 32)
            .addDnsServer(VpnTunnelPolicy.DNS_SERVER)
            .addRoute("0.0.0.0", 0)
        // IPv4-only TUN: Android blocks the unconfigured IPv6 family without underlay bypass.
        return establishWithRules(builder, settings, underlay)
    }

    fun establishWireGuard(
        settings: StoredSettings,
        underlay: UnderlyingNetworkSnapshot?,
        config: BackendRuWireGuardConfig,
    ): TunHandle? {
        val builder = service.Builder()
            .setSession("Noki WireGuard · RU relay")
            .setMtu(config.mtu)
            .addAddress(config.address.substringBefore('/'), 32)
            .addRoute("0.0.0.0", 0)
        // RU WireGuard carries IPv4 only. Leave IPv6 blocked by Android;
        // advertising it makes browsers reach a dead path after TCP accept.
        // A public DNS address here can trigger a browser's same-provider DoH upgrade.
        builder.addDnsServer(VpnTunnelPolicy.DNS_SERVER)
        return establishWithRules(builder, settings, underlay)
    }

    private fun establishWithRules(
        builder: VpnService.Builder,
        settings: StoredSettings,
        underlay: UnderlyingNetworkSnapshot?,
    ): TunHandle? {
        builder.applyDefaultUnderlyingNetworkMetadata(underlay)

        val routingRules = AppVpnRoutingPolicy.rules(
            appPackageName = service.packageName,
            filterMode = settings.filterMode,
            selectedPackages = settings.selectedPackages,
            isInstalled = { candidate ->
                runCatching { service.packageManager.getApplicationInfo(candidate, 0) }.isSuccess
            },
        )
        if (routingRules is AppVpnRoutingRules.Rejected) {
            throw TunInterfaceConfigurationException(routingRules.failureReason)
        }
        check(routingRules is AppVpnRoutingRules.Ready)
        if (routingRules.requiresOwnerCheck && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw TunInterfaceConfigurationException(AppVpnRoutingPolicy.OWNER_CHECK_UNAVAILABLE_REASON)
        }
        try {
            routingRules.allowedPackages.forEach(builder::addAllowedApplication)
            routingRules.disallowedPackages.forEach(builder::addDisallowedApplication)
        } catch (error: PackageManager.NameNotFoundException) {
            throw TunInterfaceConfigurationException("rules_error", error)
        }
        return builder.establish()?.let { ParcelFileDescriptorTunHandle(it, routingRules) }
    }

    private fun VpnService.Builder.applyDefaultUnderlyingNetworkMetadata(
        snapshot: UnderlyingNetworkSnapshot?,
    ) {
        if (snapshot == null) return
        setUnderlyingNetworks(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setMetered(snapshot.vpnShouldBeMetered)
        }
    }
}

private class ParcelFileDescriptorTunHandle(
    private val descriptor: ParcelFileDescriptor,
    override val routingRules: AppVpnRoutingRules.Ready,
) : TunHandle {
    override val fd: Int
        get() = descriptor.fd

    override fun close() {
        descriptor.close()
    }
}
