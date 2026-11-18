package com.noki.vpn

import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.EndpointSelectionMode
import com.noki.vpn.data.EndpointSecurityPolicy
import com.noki.vpn.data.EndpointTransportPolicy
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol

object AdvancedSettingsStateReducer {
    fun setYoutubeNoAdsEnabled(current: AppUiState, enabled: Boolean): AppUiState =
        current.copy(advancedSettings = RuntimeProfilePolicy.normalizeAdvancedSettings(
            current.advancedSettings.copy(youtubeNoAdsEnabled = enabled), current.userProfile,
        ))

    fun setRuRelayEnabled(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        val advanced = current.advancedSettings
        val nextAdvanced = RuntimeProfilePolicy.normalizeRouteModes(
            advanced.copy(ruRelayEnabled = advanced.ruRelayEnabled || enabled),
        ).copy(
            ruRelayEnabled = enabled,
            gamingModeEnabled = advanced.gamingModeEnabled && !enabled,
        )
        return current.copy(
            advancedSettings = nextAdvanced,
            profile = if (nextAdvanced == advanced) current.profile
                else RuntimeProfilePolicy.profileAfterServerSelection(current.profile),
        )
    }

    fun setMultiHop(current: AppUiState, settings: MultiHopSettings): AppUiState {
        if (current.advancedSettings.ruRelayEnabled) return current
        val nextAdvanced = current.advancedSettings.copy(
            multiHop = settings,
        )
        return current.copy(
            advancedSettings = nextAdvanced,
            profile = if (nextAdvanced == current.advancedSettings) current.profile
                else RuntimeProfilePolicy.profileAfterServerSelection(current.profile),
        )
    }

    fun setGamingModeEnabled(current: AppUiState, enabled: Boolean): AppUiState {
        val advanced = current.advancedSettings
        val nextAdvanced = RuntimeProfilePolicy.normalizeRouteModes(advanced).copy(
            gamingModeEnabled = enabled,
            ruRelayEnabled = advanced.ruRelayEnabled && !enabled,
        )
        return current.copy(
            advancedSettings = nextAdvanced,
            profile = if (nextAdvanced == advanced) current.profile
                else RuntimeProfilePolicy.profileAfterServerSelection(current.profile),
        )
    }

    fun setFilterMode(
        current: AppUiState,
        mode: AppFilterMode,
    ): AppUiState = current.copy(filterMode = mode)

    fun togglePackageSelection(
        current: AppUiState,
        packageName: String,
    ): AppUiState {
        val updated = current.selectedPackages.toMutableSet().apply {
            if (!add(packageName)) {
                remove(packageName)
            }
        }
        return current.copy(selectedPackages = updated)
    }

    fun clearPackageSelection(current: AppUiState): AppUiState =
        current.copy(selectedPackages = emptySet())

    fun setBiometric(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        return current.copy(
            securitySettings = current.securitySettings.copy(biometricEnabled = enabled),
        )
    }

    fun setLoginAlerts(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        return current.copy(
            securitySettings = current.securitySettings.copy(loginAlertsEnabled = enabled),
        )
    }

    fun setProtectNewDevices(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        return current.copy(
            securitySettings = current.securitySettings.copy(protectNewDevices = enabled),
        )
    }

    fun changeProtocol(
        current: AppUiState,
        protocol: VpnProtocol,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(protocol = protocol,
                endpointSelectionMode = if (protocol == VpnProtocol.AUTO) EndpointSelectionMode.AUTO else EndpointSelectionMode.MANUAL,
                manualEndpointCode = "", manualEndpointGroupKey = ""),
            profile = RuntimeProfilePolicy.profileAfterProtocolChange(current.profile, protocol),
        )
    }

    fun setAutoEndpointSelection(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        val nextAdvanced = if (enabled) {
            current.advancedSettings.copy(
                protocol = VpnProtocol.AUTO,
                endpointSelectionMode = EndpointSelectionMode.AUTO,
                manualEndpointCode = "",
                manualEndpointGroupKey = "",
            )
        } else {
            val options = current.endpointOptions.filter { !it.canaryOnly && EndpointSecurityPolicy.isAllowedOption(it) &&
                EndpointTransportPolicy.isDirectOption(it) }
            val selected = options.firstOrNull { it.code == current.profile.endpointCode }
                ?: options.firstOrNull()
            current.advancedSettings.copy(
                protocol = VpnProtocol.fromBackendCode(selected?.security, selected?.proxyType),
                endpointSelectionMode = EndpointSelectionMode.MANUAL,
                manualEndpointCode = selected?.code.orEmpty(),
                manualEndpointGroupKey = selected?.let(EndpointGroupPolicy::groupKey).orEmpty(),
            )
        }
        return current.copy(
            advancedSettings = nextAdvanced,
            profile = RuntimeProfilePolicy.profileAfterServerSelection(current.profile),
        )
    }

    fun selectManualEndpoint(
        current: AppUiState,
        option: VpnEndpointOption,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(
                protocol = VpnProtocol.fromBackendCode(option.security, option.proxyType),
                endpointSelectionMode = EndpointSelectionMode.MANUAL,
                manualEndpointCode = option.code,
                manualEndpointGroupKey = EndpointGroupPolicy.groupKey(option),
            ),
            profile = RuntimeProfilePolicy.profileForManualEndpoint(current.profile, option),
        )
    }

    fun setAllLogging(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(
                connectionLogsEnabled = enabled,
                errorLogsEnabled = enabled,
            ),
        )
    }

    fun setAnonymousLogs(
        current: AppUiState,
        enabled: Boolean,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(anonymousLogsEnabled = enabled),
        )
    }

    fun setAlwaysOnInput(
        current: AppUiState,
        value: String,
    ): AppUiState = current.copy(alwaysOnInput = value)

    fun setBypassInput(
        current: AppUiState,
        value: String,
    ): AppUiState = current.copy(bypassInput = value)

    fun addAlwaysOnDomain(
        current: AppUiState,
        value: String,
    ): AppUiState {
        val nextSettings = DomainRulePolicy.addAlways(current.advancedSettings, value)
        if (nextSettings == current.advancedSettings) return current
        return current.copy(
            advancedSettings = nextSettings,
            alwaysOnInput = "",
        )
    }

    fun addBypassDomain(
        current: AppUiState,
        value: String,
    ): AppUiState {
        val nextSettings = DomainRulePolicy.addBypass(current.advancedSettings, value)
        if (nextSettings == current.advancedSettings) return current
        return current.copy(
            advancedSettings = nextSettings,
            bypassInput = "",
        )
    }

    fun updateAlwaysOnDomain(
        current: AppUiState,
        oldDomain: String,
        newDomain: String,
    ): AppUiState {
        val withoutOld = current.advancedSettings.copy(
            alwaysOnDomains = current.advancedSettings.alwaysOnDomains.filterNot {
                DomainRulePolicy.normalize(it) == DomainRulePolicy.normalize(oldDomain)
            },
        )
        val nextSettings = DomainRulePolicy.addAlways(withoutOld, newDomain)
        return if (nextSettings == withoutOld) current else current.copy(advancedSettings = nextSettings)
    }

    fun removeAlwaysOnDomain(
        current: AppUiState,
        domain: String,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(
                alwaysOnDomains = current.advancedSettings.alwaysOnDomains.filterNot {
                    DomainRulePolicy.normalize(it) == DomainRulePolicy.normalize(domain)
                },
            ),
        )
    }

    fun removeBypassDomain(
        current: AppUiState,
        domain: String,
    ): AppUiState {
        return current.copy(
            advancedSettings = current.advancedSettings.copy(
                bypassDomains = current.advancedSettings.bypassDomains.filterNot {
                    DomainRulePolicy.normalize(it) == DomainRulePolicy.normalize(domain)
                },
            ),
        )
    }

    fun updateBypassDomain(
        current: AppUiState,
        oldDomain: String,
        newDomain: String,
    ): AppUiState {
        val withoutOld = current.advancedSettings.copy(
            bypassDomains = current.advancedSettings.bypassDomains.filterNot {
                DomainRulePolicy.normalize(it) == DomainRulePolicy.normalize(oldDomain)
            },
        )
        val nextSettings = DomainRulePolicy.addBypass(withoutOld, newDomain)
        return if (nextSettings == withoutOld) current else current.copy(advancedSettings = nextSettings)
    }
}
