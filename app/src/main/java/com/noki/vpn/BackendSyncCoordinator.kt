package com.noki.vpn

import com.noki.vpn.data.withClientLatencies

import com.noki.vpn.data.BackendBootstrapLoader
import com.noki.vpn.data.BackendException
import com.noki.vpn.data.BackendDevice
import com.noki.vpn.data.BackendLocation
import com.noki.vpn.data.BackendPlan
import com.noki.vpn.data.BootstrapStateMapper
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.clientLatencyTargetKey
import com.noki.vpn.data.RuRelayCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal data class BackendSyncRequest(
    val token: String,
    val baseState: AppUiState,
    val currentDeviceId: String,
    val currentDeviceKey: String,
    val previousDeviceAccessRole: String,
    val clientLatencyByTarget: Map<String, Int>,
)

internal data class BackendSyncResult(
    val patch: BackendSyncPatch,
    val backendDevices: List<BackendDevice>,
    val backendLocations: List<BackendLocation>,
    val backendPlans: List<BackendPlan>,
)

internal class CurrentDeviceAccessRevokedException : IllegalStateException("current_device_access_revoked")

internal class BackendSyncCoordinator(
    private val bootstrapLoader: BackendBootstrapLoader,
    private val androidUpdateLoader: AndroidUpdateStateLoader,
    private val profileAvatarLoader: ProfileAvatarLoader,
    private val ruCatalogLoader: suspend (String, String, String?) -> RuRelayCatalog,
) {
    suspend fun syncState(request: BackendSyncRequest): BackendSyncResult = coroutineScope {
        val bootstrap = bootstrapLoader.bootstrap(
            token = request.token,
            deviceId = request.currentDeviceId.ifBlank { null },
            deviceKey = request.currentDeviceKey.ifBlank { null },
        )
        val currentDevice = bootstrap.devices.firstOrNull { device ->
            if (request.currentDeviceId.isNotBlank()) {
                device.id == request.currentDeviceId
            } else {
                request.currentDeviceKey.isNotBlank() && device.deviceKey == request.currentDeviceKey
            }
        }
        if (currentDevice?.isActive != true) throw CurrentDeviceAccessRevokedException()
        val ruForeignNodes = async {
            if (!request.baseState.advancedSettings.ruRelayEnabled) return@async emptyList()
            try {
                ruCatalogLoader(request.token, currentDevice.id,
                    request.currentDeviceKey.ifBlank { currentDevice.deviceKey }).foreignNodes
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is BackendException && (error.statusCode == 401 || error.statusCode == 403)) throw error
                // Optional route discovery must not discard the authenticated base inventory.
                emptyList()
            }
        }
        val language = request.baseState.personalizationSettings.language
        val mapped = BootstrapStateMapper.mapBootstrap(
            bootstrap = bootstrap,
            language = language,
            currentUserProfile = request.baseState.userProfile,
            currentProfile = request.baseState.profile,
            advancedSettings = request.baseState.advancedSettings,
            endpointOptions = request.baseState.endpointOptions,
            currentDeviceId = request.currentDeviceId,
            currentDeviceKey = request.currentDeviceKey,
            previousDeviceAccessRole = request.previousDeviceAccessRole,
            clientLatencyByTarget = request.clientLatencyByTarget,
        )
        val androidUpdate = async {
            androidUpdateLoader.loadStateWithToken(
                token = request.token,
                fallbackState = request.baseState.androidUpdate,
                language = language,
            )
        }
        val userProfile = async {
            mapped.userProfile.copy(
                avatarUri = profileAvatarLoader.cachedBackendAvatarUri(
                    token = request.token,
                    backendAvatarUrl = bootstrap.user.avatarUrl,
                    fallbackAvatarUri = request.baseState.userProfile.avatarUri,
                ),
            )
        }
        val patch = BackendSyncPatch(
            userProfile = userProfile.await(),
            devices = mapped.devices,
            locations = mapped.locations,
            plans = mapped.plans,
            profile = mapped.profile,
            currentDeviceAccessRole = mapped.currentDeviceAccessRole,
            androidUpdate = androidUpdate.await(),
            ruForeignNodes = ruForeignNodes.await(),
        )

        BackendSyncResult(
            patch = patch,
            backendDevices = bootstrap.devices,
            backendLocations = bootstrap.locations,
            backendPlans = bootstrap.plans,
        )
    }

    companion object {
        fun withCachedClientLatencies(
            state: AppUiState,
            clientLatencyByTarget: Map<String, Int>,
        ): AppUiState {
            return state.copy(
                locations = state.locations.map { location ->
                    location.withClientLatencies(clientLatencyByTarget)
                },
                ruForeignNodes = state.ruForeignNodes.map { node -> node.copy(
                    latencyMs = clientLatencyByTarget[clientLatencyTargetKey(node)]) },
            )
        }

        fun hasMissingClientLatency(
            locations: List<ServerLocation>,
            clientLatencyByTarget: Map<String, Int>,
        ): Boolean {
            return locations.any { location ->
                if (!location.isOnline) return@any false
                if (location.servers.isNotEmpty()) return@any location.servers.any {
                    it.isOnline && (it.probePort != null || it.probeIp != null) && clientLatencyTargetKey(it) !in clientLatencyByTarget
                }
                val targetKey = clientLatencyTargetKey(location) ?: return@any false
                !clientLatencyByTarget.containsKey(targetKey)
            }
        }
    }
}
