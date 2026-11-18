package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.AppUiState
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.UserProfile
import com.noki.vpn.data.VpnConnectionState
import com.noki.vpn.data.usesRuForeignNodeCatalog
import com.noki.vpn.R
import com.styropyr0.prismal.sources.PrismalGlassLayer
import kotlin.math.max
import java.util.Locale

internal fun homeSelectedLocation(
    locations: List<ServerLocation>,
    profile: UserProfile,
): ServerLocation? {
    fun byCode(code: String?): ServerLocation? {
        if (code.isNullOrBlank()) return null
        return locations.firstOrNull { location ->
            location.code.equals(code, ignoreCase = true) ||
                location.countryCode.equals(code, ignoreCase = true)
        }
    }

    return when (profile.serverSelectionMode) {
        ServerSelectionMode.SERVER ->
            locations.firstOrNull { location ->
                location.servers.any { server -> server.id == profile.selectedNodeId }
            } ?: byCode(profile.selectedServerCode)
                ?: byCode(profile.selectedCountryCode)
        ServerSelectionMode.AUTO ->
            byCode(profile.selectedServerCode)
                ?: byCode(profile.actualCountryCode)
                ?: byCode(profile.selectedCountryCode)
        ServerSelectionMode.COUNTRY ->
            byCode(profile.selectedCountryCode)
                ?: byCode(profile.selectedServerCode)
    }
}

internal fun homeMultiHopExitCountryCode(state: AppUiState): String? {
    if (state.advancedSettings.ruRelayEnabled || !state.advancedSettings.multiHop.enabled) return null
    fun nodeCountry(nodeId: String?): String? {
        if (nodeId.isNullOrBlank()) return null
        val code = state.locations.asSequence().flatMap { it.servers.asSequence() }.firstOrNull {
            it.id == nodeId || it.locationCode.equals(nodeId, true)
        }?.countryCode
        return code?.let(::countryMarkerCode)
    }
    val exit = state.advancedSettings.multiHop.exit
    val configuredCountry = when (exit?.kind) {
        ServerSelectionMode.COUNTRY -> exit.countryCode?.let(::countryMarkerCode)
        ServerSelectionMode.SERVER -> nodeCountry(exit.nodeId)
        else -> null
    }
    if (state.connectionState != VpnConnectionState.CONNECTED) return configuredCountry
    val runtimeCode = state.userProfile.selectedServerCode
    // Runtime UI sync is asynchronous; a previous Auto id cannot confirm another exit.
    val matchesExit = when (exit?.kind) {
        ServerSelectionMode.SERVER -> runtimeCode == exit.nodeId || state.locations.any { location ->
            location.servers.any { it.id == exit.nodeId && it.locationCode.equals(runtimeCode, true) }
        }
        ServerSelectionMode.COUNTRY -> nodeCountry(runtimeCode) == configuredCountry
        else -> false
    }
    return nodeCountry(runtimeCode)?.takeIf { matchesExit && it == configuredCountry }
}

internal fun homeServerSubtitle(state: AppUiState): String? {
    val language = state.personalizationSettings.language
    val ruRelay = state.advancedSettings.ruRelayEnabled
    val usesRuCatalog = usesRuForeignNodeCatalog(ruRelay, state.ruForeignNodes)
    val selectedLocation = homeSelectedLocation(state.locations, state.userProfile)
    val unavailable = tr(language, "Сервер недоступен", "Server unavailable")
    fun countryName(code: String?): String? = code?.takeIf { it.isNotBlank() }
        ?.let { Locale("", it).getDisplayCountry(Locale(language.tag)) }
    fun nodeName(id: String?): String? = if (usesRuCatalog) {
        state.ruForeignNodes.firstOrNull { it.id == id }?.name
    } else state.locations.flatMap { it.servers }.firstOrNull { it.id == id }?.name
    fun hopLabel(selection: HopSelection?): String = when (selection?.kind) {
        ServerSelectionMode.COUNTRY -> state.locations.firstOrNull {
            it.countryCode.equals(selection.countryCode, true)
        }?.country ?: selection.countryCode.orEmpty()
        ServerSelectionMode.SERVER -> nodeName(selection.nodeId) ?: unavailable
        else -> tr(language, "Не выбран", "Not selected")
    }
    val multiHop = state.advancedSettings.multiHop.takeIf { !ruRelay && it.enabled }
    if (multiHop == null && state.userProfile.serverSelectionMode == ServerSelectionMode.AUTO &&
        state.connectionState != VpnConnectionState.CONNECTED) {
        return if (ruRelay) tr(language, "RU-реле", "RU relay") else null
    }
    val destination = when {
        ruRelay && state.connectionState == VpnConnectionState.CONNECTED ->
            state.ruForeignNodes.firstOrNull { it.id == state.userProfile.selectedServerCode }?.name ?: unavailable
        multiHop != null -> hopLabel(multiHop.exit)
        state.userProfile.serverSelectionMode == ServerSelectionMode.AUTO ->
            if (ruRelay) tr(language, "Автовыбор", "Automatic") else selectedLocation?.country
        state.userProfile.serverSelectionMode == ServerSelectionMode.SERVER ->
            if (ruRelay) nodeName(state.userProfile.selectedNodeId) ?: unavailable
            else selectedLocation?.servers?.firstOrNull {
                it.id == state.userProfile.selectedNodeId ||
                    it.locationCode.equals(state.userProfile.selectedServerCode, true)
            }?.name
        ruRelay -> countryName(state.userProfile.selectedCountryCode) ?: unavailable
        else -> null
    }
    val route = if (multiHop != null) "${hopLabel(multiHop.entry)} → $destination" else destination
    return if (ruRelay) "${tr(language, "RU-реле", "RU relay")} → $route" else route
}

@Composable
internal fun HomeBackground(
    liveGlassEnabled: Boolean,
    showCenterShade: Boolean = true,
) {
    val backgroundModifier = Modifier
        .fillMaxSize()
        .background(homeBackgroundColor(liveGlassEnabled))
    if (!shouldDrawHomeBackgroundEffects(liveGlassEnabled)) {
        Box(modifier = backgroundModifier) {
            Image(
                painter = painterResource(R.drawable.simple_topography),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Box(
        modifier = backgroundModifier
            .drawWithCache {
                val w = size.width
                val h = size.height
                val wide = max(w, h)
                val baseGradient = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to HomeBgBase,
                        0.58f to HomeBgBase,
                        0.84f to Color(0xFF072832),
                        1.00f to Color(0xFF063D4A),
                    ),
                )
                val lowerBlob = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF087F8D).copy(alpha = 0.52f),
                        Color(0xFF075F70).copy(alpha = 0.34f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.50f, h * 1.08f),
                    radius = wide * 0.54f,
                )
                val leftAccentBlob = Brush.radialGradient(
                    colors = listOf(
                        HomeAccentPrimary.copy(alpha = 0.14f),
                        Color(0xFF0A6B78).copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.28f, h * 1.14f),
                    radius = wide * 0.32f,
                )
                val rightDeepBlob = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0A5968).copy(alpha = 0.22f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.78f, h * 1.12f),
                    radius = wide * 0.36f,
                )
                val centerShade = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.00f),
                        Color.Black.copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                    center = Offset(w * 0.50f, h * 0.47f),
                    radius = wide * 0.68f,
                )
                val topBottomShade = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Black.copy(alpha = 0.16f),
                        0.36f to Color.Transparent,
                        1.00f to Color.Black.copy(alpha = 0.04f),
                    ),
                )
                onDrawBehind {
                    drawRect(brush = baseGradient)
                    drawRect(brush = lowerBlob)
                    drawRect(brush = leftAccentBlob)
                    drawRect(brush = rightDeepBlob)
                    if (showCenterShade) {
                        drawRect(brush = centerShade)
                    }
                    drawRect(brush = topBottomShade)
                }
            },
    )
}

@Composable
internal fun HomeLocationCard(
    modifier: Modifier,
    scale: Float,
    language: AppLanguage,
    expanded: Boolean,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onToggle: () -> Unit,
    country: String,
    subtitle: String? = null,
    countryCode: String? = null,
    automatic: Boolean = false,
) {
    val headerHeight = designDp(NokiUiKitPolicy.homeLocationHeightDp, scale)
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "serverArrowRotation",
    )
    HomeSettingsGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(headerHeight),
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(designDp(NokiUiKitPolicy.homeLocationPaddingDp, scale)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(designDp(20f, scale)),
        ) {
            if (automatic) {
                AutomaticServerMarker(scale, NokiUiKitPolicy.homeLocationFlagSizeDp)
            } else if (countryCode != null) {
                CountryFlagMarker(
                    countryCode = countryCode,
                    scale = scale,
                    sizeDp = NokiUiKitPolicy.homeLocationFlagSizeDp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(designDp(NokiUiKitPolicy.homeLocationFlagSizeDp, scale))
                        .clip(RoundedCornerShape(percent = 50))
                        .background(HomeStroke.copy(alpha = 0.28f)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = country,
                color = HomeTextPrimary,
                fontFamily = ManropeFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = designSp(24f, scale),
                letterSpacing = 0.24.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true)),
              )
              if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = HomeTextSecondary,
                    fontFamily = ManropeFontFamily,
                    fontSize = designSp(13f, scale),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
              }
            }
            HomeDropdownArrowButton(
                modifier = Modifier
                    .size(designDp(50f, scale)),
                scale = scale,
                arrowRotation = arrowRotation,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
            )
        }
    }
}
