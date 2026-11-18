package com.noki.vpn.data

import java.util.Locale
import java.util.UUID

object VpnProfileValidator {
    fun isUsable(
        settings: StoredSettings,
    ): Boolean = rejectionReason(settings) == null

    fun rejectionReason(settings: StoredSettings): String? {
        return rejectionReason(
            profile = settings.profile,
            advancedSettings = settings.advancedSettings,
            selectedLocationCode = settings.userProfile.selectedServerCode,
            endpointOptions = settings.endpointOptions,
            directOnly = true,
        )
    }

    fun isUsable(
        profile: VlessProfile,
        advancedSettings: AdvancedSettings,
        selectedLocationCode: String = "",
        endpointOptions: List<VpnEndpointOption> = emptyList(),
        directOnly: Boolean = true,
    ): Boolean = rejectionReason(profile, advancedSettings, selectedLocationCode, endpointOptions, directOnly) == null

    private fun rejectionReason(
        profile: VlessProfile,
        advancedSettings: AdvancedSettings,
        selectedLocationCode: String,
        endpointOptions: List<VpnEndpointOption>,
        directOnly: Boolean,
    ): String? {
        if (advancedSettings.ruRelayEnabled) {
            return "ru_relay_requires_wireguard_coordinator"
        }
        val multiHop = profile.multiHop
        if (advancedSettings.multiHop.enabled) {
            if (multiHop == null || (multiHop.version != 3 && multiHop.selection != advancedSettings.multiHop)) {
                return "multihop_runtime_missing"
            }
            val hops = runCatching { multiHop.selection.explicitNodeIds() }.getOrNull()
            if (hops != (multiHop.entryNodeId to multiHop.exitNodeId)) return "multihop_policy_invalid"
            if (multiHop.version == 3) {
                if (!profile.entryRoute || profile.proxyType.equals("nokinv", true)) return "entry_route_unavailable"
            } else {
                if (multiHop.version != 2 || !multiHop.policyHash.matches(Regex("[0-9a-f]{64}"))) return "multihop_policy_invalid"
                if (profile.host != multiHop.entryIp || profile.host.isBlank() || profile.port.toIntOrNull() !in 20000..29999) {
                    return "multihop_entry_invalid"
                }
            }
        } else if (multiHop != null) return "multihop_runtime_unexpected"
        val expectedSecurity = advancedSettings.effectiveProtocol.name.lowercase(Locale.ROOT)
        val normalizedSecurity = profile.security.lowercase(Locale.ROOT)
        val nokinv = profile.proxyType == "nokinv"
        val naive = profile.proxyType == "naive"
        if (if (expectedSecurity == "naive") !naive else if (expectedSecurity == "nokinv") !nokinv else (nokinv && expectedSecurity != "auto") ||
            (expectedSecurity != "auto" && normalizedSecurity != expectedSecurity)) return "protocol_mismatch"

        if (!advancedSettings.gamingModeEnabled &&
            advancedSettings.endpointSelectionMode == EndpointSelectionMode.MANUAL) {
            val manualGroupKey = EndpointGroupPolicy.resolveManualGroupKey(
                settings = advancedSettings,
                endpointOptions = endpointOptions,
                profile = profile,
            )
            if (manualGroupKey.isNotBlank()) {
                val endpointOption = endpointOptions.firstOrNull { it.code == profile.endpointCode }
                val profileGroupKey = if (nokinv) EndpointGroupPolicy.groupKey(profile) else endpointOption
                    ?.let(EndpointGroupPolicy::groupKey)
                    ?: EndpointGroupPolicy.groupKey(profile)
                if (profileGroupKey != manualGroupKey) return "manual_group_mismatch"
                if (nokinv && endpointOption != null && EndpointGroupPolicy.manualOptions(listOf(endpointOption))
                        .none { EndpointGroupPolicy.groupKey(it) == profileGroupKey }) return "manual_group_mismatch"
            } else {
                val manualCode = advancedSettings.manualEndpointCode.trim()
                if (manualCode.isNotBlank() && profile.endpointCode != manualCode) return "manual_endpoint_mismatch"
            }
        }

        val endpointOption = endpointOptions.firstOrNull { it.code == profile.endpointCode }
        val selectedCode = selectedLocationCode.trim()
        if (endpointOption != null &&
            selectedCode.isNotBlank() &&
            endpointOption.locationCode.isNotBlank() &&
            !endpointOption.locationCode.equals(selectedCode, ignoreCase = true)
        ) {
            return "location_mismatch"
        }

        if (profile.host.isBlank()) return "missing_host"
        if (profile.port.isBlank()) return "missing_port"
        if (directOnly && !advancedSettings.multiHop.enabled && !EndpointTransportPolicy.isDirectProfile(profile)) {
            return "direct_transport_unsupported"
        }
        if (nokinv) {
            val token = profile.nokinvToken
            val decoded = runCatching { java.util.Base64.getUrlDecoder().decode(token) }.getOrNull()
            if (decoded?.size != 32 || java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != token ||
                profile.nokinvCredentialGeneration < 1) return "invalid_credential"
        } else if (naive) {
            if (profile.vpnUsername.isBlank() || profile.uuid.isBlank()) return "invalid_credential"
        } else if (runCatching { UUID.fromString(profile.uuid) }.isFailure) return "invalid_credential"
        return if (EndpointSecurityPolicy.isAllowedProfile(profile)) null else "security_policy_rejected"
    }
}
