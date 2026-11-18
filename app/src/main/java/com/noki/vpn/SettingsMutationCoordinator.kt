package com.noki.vpn

import com.noki.vpn.data.AtomicStoredSettingsStore
import com.noki.vpn.data.RuntimeProfilePolicy
import com.noki.vpn.data.StoredSettings
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.EndpointGroupPolicy
import com.noki.vpn.data.VpnEndpointOption
import com.noki.vpn.data.VpnProtocol
import java.util.Locale

internal sealed interface SettingsEffect {
    data object None : SettingsEffect
    data object ApplyRuntimeSettings : SettingsEffect
    data object StartAutomaticLogUpload : SettingsEffect
}

internal data class SettingsMutationResult(
    val settings: StoredSettings,
    val effect: SettingsEffect,
)

internal class SettingsMutationCoordinator(
    private val store: AtomicStoredSettingsStore,
) {
    fun persistUiFields(state: AppUiState): StoredSettings {
        return store.updateSettings { latest ->
            latest.withUiFields(state)
        }
    }

    fun persistUiFields(
        state: AppUiState,
        effect: SettingsEffect,
    ): SettingsMutationResult = SettingsMutationResult(
        settings = persistUiFields(state),
        effect = effect,
    )

    fun persistServerSelection(
        countryCode: String,
        mode: ServerSelectionMode = ServerSelectionMode.COUNTRY,
        nodeId: String = "",
    ): StoredSettings {
        val selectedCode = countryCode.trim().uppercase(Locale.ROOT)
        return store.updateSettings { latest ->
            latest.copy(
                profile = RuntimeProfilePolicy.profileAfterServerSelection(latest.profile),
                endpointOptions = emptyList(),
                advancedSettings = latest.advancedSettings.copy(
                    manualEndpointCode = "",
                    manualEndpointGroupKey = EndpointGroupPolicy.resolveManualGroupKey(
                        latest.advancedSettings, latest.endpointOptions, latest.profile),
                ),
                userProfile = latest.userProfile.copy(
                    selectedCountryCode = if (mode == ServerSelectionMode.AUTO) latest.userProfile.selectedCountryCode else selectedCode,
                    serverSelectionMode = mode,
                    selectedNodeId = if (mode == ServerSelectionMode.SERVER) nodeId.trim() else "",
                    selectedServerCode = "",
                    actualCountryCode = "",
                ),
            )
        }
    }

    fun persistProtocolChange(
        state: AppUiState,
        protocol: VpnProtocol,
    ): StoredSettings {
        return store.updateSettings { latest ->
            val updated = latest.withUiFields(state)
            updated.copy(
                profile = RuntimeProfilePolicy.profileAfterProtocolChange(updated.profile, protocol),
            )
        }
    }

    fun persistAutoEndpointSelection(state: AppUiState): StoredSettings {
        return store.updateSettings { latest ->
            val updated = latest.withUiFields(state)
            updated.copy(profile = updated.profile.copy(uuid = ""))
        }
    }

    fun persistManualEndpointSelection(
        state: AppUiState,
        option: VpnEndpointOption,
    ): StoredSettings {
        return store.updateSettings { latest ->
            val updated = latest.withUiFields(state)
            updated.copy(
                profile = RuntimeProfilePolicy.profileForManualEndpoint(updated.profile, option),
            )
        }
    }

    private fun StoredSettings.withUiFields(state: AppUiState): StoredSettings {
        val nextAdvanced = RuntimeProfilePolicy.normalizeRouteModes(state.advancedSettings)
        val routeChanged = advancedSettings.ruRelaySelection != nextAdvanced.ruRelaySelection ||
            advancedSettings.multiHop != nextAdvanced.multiHop ||
            advancedSettings.gamingModeEnabled != nextAdvanced.gamingModeEnabled
        val updated = copy(
            filterMode = state.filterMode,
            selectedPackages = state.selectedPackages,
            personalizationSettings = state.personalizationSettings,
            securitySettings = state.securitySettings,
            advancedSettings = nextAdvanced,
            ruRelaySelectionRevision = if (routeChanged) {
                ruRelaySelectionRevision + 1L
            } else {
                ruRelaySelectionRevision
            },
        )
        return if (routeChanged) updated.copy(
            profile = RuntimeProfilePolicy.profileAfterServerSelection(profile),
            endpointOptions = emptyList(),
        ) else updated
    }
}
