package com.noki.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.noki.vpn.data.EndpointRankingPolicy
import java.util.Locale

internal class AndroidUnderlyingNetworkSource(
    context: Context,
) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun currentSnapshot(): UnderlyingNetworkSnapshot? {
        val observation = currentObservation()
        return observation.candidate?.takeIf {
            observation.availability == UnderlyingNetworkAvailability.Validated
        }
    }

    fun currentObservation(): UnderlyingNetworkObservation<UnderlyingNetworkSnapshot> {
        val connectivityManager = manager
            ?: return UnderlyingNetworkObservation(UnderlyingNetworkAvailability.None)
        val activeNetwork = connectivityManager.activeNetwork
        val selection = UnderlyingNetworkPolicy.observe(
            connectivityManager.allNetworks.mapNotNull { network ->
                connectivityManager.toCandidate(network, isActive = network == activeNetwork)
            },
        )
        val selected = selection.candidate
            ?: return UnderlyingNetworkObservation(selection.availability)
        val vpnShouldBeMetered = !selected.isNotMetered
        val capabilities = connectivityManager.getNetworkCapabilities(selected.value)
        val linkProperties = connectivityManager.getLinkProperties(selected.value)
        return UnderlyingNetworkObservation(
            availability = selection.availability,
            candidate = UnderlyingNetworkSnapshot(
                network = selected.value,
                kind = selected.kind,
                signature = connectionSignature(selected.value, selected.kind, linkProperties),
                vpnShouldBeMetered = vpnShouldBeMetered,
                details = buildString {
                    append("vpn_metered=$vpnShouldBeMetered; networks=")
                    append(selected.kind.name.lowercase(Locale.ROOT))
                    append("(validated=${selected.isValidated},not_metered=${selected.isNotMetered})")
                    append("; mtu=").append(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) linkProperties?.mtu ?: 0 else 0,
                    )
                    append("; dns_count=").append(linkProperties?.dnsServers?.size ?: 0)
                    append("; routes=").append(linkProperties?.routes?.size ?: 0)
                    append("; down_kbps=").append(capabilities?.linkDownstreamBandwidthKbps ?: 0)
                    append("; up_kbps=").append(capabilities?.linkUpstreamBandwidthKbps ?: 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        append("; private_dns=").append(!linkProperties?.privateDnsServerName.isNullOrBlank())
                    }
                },
            ),
        )
    }

    private fun ConnectivityManager.toCandidate(
        network: Network,
        isActive: Boolean,
    ): UnderlyingNetworkCandidate<Network>? {
        val capabilities = getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return UnderlyingNetworkCandidate(
            value = network,
            kind = networkKind(
                hasWifiTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                hasCellularTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ),
            isActive = isActive,
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isNotSuspended = NetworkCapabilityPolicy.isNotSuspended(
                sdkInt = Build.VERSION.SDK_INT,
                capabilityPresent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            ),
            isNotMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    companion object {
        internal fun connectionSignature(
            network: Network,
            kind: EndpointRankingPolicy.NetworkKind,
            links: LinkProperties?,
        ): String = listOf(
            network.toString(), kind.name, links?.interfaceName.orEmpty(),
            links?.linkAddresses.orEmpty().map { it.toString() }.sorted().joinToString(","),
            links?.routes.orEmpty().map { it.toString() }.sorted().joinToString(","),
        ).joinToString(":")

        internal fun networkKind(
            hasWifiTransport: Boolean,
            hasCellularTransport: Boolean,
        ): EndpointRankingPolicy.NetworkKind = when {
            hasWifiTransport -> EndpointRankingPolicy.NetworkKind.WIFI
            hasCellularTransport -> EndpointRankingPolicy.NetworkKind.CELLULAR
            else -> EndpointRankingPolicy.NetworkKind.OTHER
        }
    }
}
