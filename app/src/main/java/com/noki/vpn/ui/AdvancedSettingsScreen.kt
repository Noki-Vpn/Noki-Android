package com.noki.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.manualEndpointOptionsForCurrentCountry
import com.noki.vpn.data.EndpointSelectionMode
import kotlinx.coroutines.delay

private val AdvancedBgBase = Color(0xFF07111A)
internal val AdvancedBgLighter = Color(0xFF0D1B2A)
internal val AdvancedBgSoft = Color(0xFF132635)
internal val AdvancedTextPrimary = Color(0xFFF4FBFF)
internal val AdvancedTextSecondary = Color(0xFF9FB6C5)
internal val AdvancedAccentPrimary = Color(0xFF7AE7C7)
internal val AdvancedStroke = Color(0xFF29404E)
private val AdvancedNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun AdvancedSettingsScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    sharedBackdrop: PrismalGlassLayer,
    cardBackdrop: PrismalGlassLayer? = null,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
    onAppFilterClicked: () -> Unit = {},
    onAlwaysOnRulesClicked: () -> Unit = {},
    onBypassRulesClicked: () -> Unit = {},
    onMultiHopClicked: () -> Unit = {},
) {
    CompositionLocalProvider(LocalTextStyle provides AdvancedNoFontPaddingTextStyle) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(AdvancedBgBase) else Modifier),
        ) {
            val backdrop = if (showBackground || liveGlassEnabled) sharedBackdrop else null
            val surfaceBackdrop = cardBackdrop ?: backdrop
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val context = LocalContext.current
            val language = state.personalizationSettings.language
            val autoEndpointSelection = state.advancedSettings.endpointSelectionMode == EndpointSelectionMode.AUTO
            val alwaysOnCount = state.advancedSettings.alwaysOnDomains.size
            val bypassCount = state.advancedSettings.bypassDomains.size
            var showProtocolSheet by rememberSaveable { mutableStateOf(false) }
            var showProtocolNotice by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(state.advancedSettings.ruRelayEnabled, state.advancedSettings.gamingModeEnabled) {
                if (state.advancedSettings.ruRelayEnabled ||
                    state.advancedSettings.gamingModeEnabled) showProtocolSheet = false
            }

            LaunchedEffect(showProtocolNotice) {
                if (showProtocolNotice) {
                    delay(2200)
                    showProtocolNotice = false
                }
            }
            LaunchedEffect(state.isAuthenticated, state.userProfile.selectedCountryCode,
                state.userProfile.selectedNodeId, state.userProfile.serverSelectionMode,
                state.advancedSettings.multiHop, state.advancedSettings.ruRelayEnabled,
                state.advancedSettings.gamingModeEnabled) {
                if (state.isAuthenticated && !state.advancedSettings.ruRelayEnabled &&
                    !state.advancedSettings.gamingModeEnabled) {
                    viewModel.refreshEndpointOptions(context)
                }
            }

            if (showBackground) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (backdrop != null) Modifier.prismalGlassLayer(backdrop) else Modifier),
                ) {
                    HomeBackground(liveGlassEnabled = liveGlassEnabled)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = metrics.contentStart)
                        .padding(top = metrics.dp(58f), bottom = metrics.dp(150f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                AdvancedText(
                    text = tr(language, "Расширенные настройки", "Advanced settings"),
                    fontSize = 24f,
                    lineHeight = 28.8f,
                    color = AdvancedTextPrimary,
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth()
                        .padding(horizontal = metrics.dp(18f)),
                )
                AdvancedText(
                    text = tr(
                        language,
                        "Протоколы, защита и правила маршрутизации",
                        "Protocols, protection and routing rules",
                    ),
                    fontSize = 12f,
                    lineHeight = 14.4f,
                    color = AdvancedTextSecondary,
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth()
                        .padding(horizontal = metrics.dp(18f)),
                )

                Spacer(modifier = Modifier.height(metrics.dp(20f)))

                if (!state.advancedSettings.ruRelayEnabled && !state.advancedSettings.gamingModeEnabled) {
                    AdvancedToggleCard(
                        enabled = autoEndpointSelection,
                        title = tr(language, "Автовыбор протокола", "Auto protocol selection"),
                        backdrop = surfaceBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .widthIn(max = metrics.dp(370f))
                            .fillMaxWidth()
                            .height(metrics.dp(NokiUiKitPolicy.advancedProtocolToggleCardHeightDp)),
                        onEnabledChanged = { enabled ->
                            viewModel.toggleAutoEndpointSelection(enabled)
                            if (!enabled) {
                                viewModel.refreshEndpointOptions(context, force = true)
                            }
                        },
                    )
                }

                if (!autoEndpointSelection &&
                    !state.advancedSettings.ruRelayEnabled && !state.advancedSettings.gamingModeEnabled) {
                    Spacer(modifier = Modifier.height(metrics.dp(25f)))

                    AdvancedManualProtocolCard(
                        selectedEndpoint = protocolCardLabel(state, autoEndpointSelection),
                        language = language,
                        backdrop = surfaceBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier
                            .widthIn(max = metrics.dp(370f))
                            .fillMaxWidth()
                            .height(metrics.dp(NokiUiKitPolicy.advancedManualProtocolCardHeightDp)),
                        onChangeProtocol = {
                            if (!autoEndpointSelection) {
                                viewModel.refreshEndpointOptions(context, force = true)
                                showProtocolSheet = true
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(metrics.dp(25f)))

                AdvancedFilterCard(
                    mode = state.filterMode,
                    language = language,
                    backdrop = surfaceBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth(),
                    onModeChanged = viewModel::updateFilterMode,
                    onConfigure = onAppFilterClicked,
                )

                Spacer(modifier = Modifier.height(metrics.dp(25f)))

                AdvancedText(
                    text = tr(language, "Умные правила для сайтов", "Smart site rules"),
                    fontSize = 18f,
                    lineHeight = 21.6f,
                    color = AdvancedTextPrimary,
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth()
                        .padding(horizontal = metrics.dp(20f)),
                )
                AdvancedText(
                    text = tr(
                        language,
                        "Домены для которых всегда включен или выключен VPN",
                        "Domains where VPN is always on or off",
                    ),
                    fontSize = 10.5f,
                    lineHeight = 12.6f,
                    color = AdvancedTextSecondary.copy(alpha = 0.8f),
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth()
                        .padding(horizontal = metrics.dp(20f)),
                )

                Spacer(modifier = Modifier.height(metrics.dp(15f)))

                Column(
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(12f)),
                ) {
                AdvancedDomainRuleWideCard(
                    title = tr(language, "Всегда включать", "Always on"),
                    count = alwaysOnCount,
                    summary = if (state.advancedSettings.alwaysOnDomains.any { it.startsWith("geosite:") || it.startsWith("regexp:") })
                        customRuleSummary(language, alwaysOnCount) else null,
                    detail = tr(language, "всегда с VPN", "always with VPN"),
                    language = language,
                    backdrop = surfaceBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metrics.dp(76f)),
                    onConfigure = onAlwaysOnRulesClicked,
                )

                AdvancedDomainRuleWideCard(
                    title = tr(language, "Всегда выключать", "Always off"),
                    count = bypassCount,
                    detail = tr(language, "всегда без ВПН", "always without VPN"),
                    language = language,
                    backdrop = surfaceBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(metrics.dp(76f)),
                    onConfigure = onBypassRulesClicked,
                )
                }
                if (!state.userProfile.isFreePlan) {
                    Spacer(modifier = Modifier.height(metrics.dp(25f)))
                    AdvancedToggleCard(
                        enabled = state.advancedSettings.youtubeNoAdsEnabled,
                        title = tr(language, "YouTube без рекламы", "YouTube without ads"),
                        backdrop = surfaceBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        modifier = Modifier.widthIn(max = metrics.dp(370f)).fillMaxWidth()
                            .height(metrics.dp(NokiUiKitPolicy.advancedProtocolToggleCardHeightDp)),
                        onEnabledChanged = viewModel::setYoutubeNoAdsEnabled,
                    )
                }

                Spacer(modifier = Modifier.height(metrics.dp(25f)))
                AdvancedToggleCard(
                    enabled = state.advancedSettings.killSwitchEnabled,
                    title = "Kill-switch",
                    backdrop = surfaceBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier
                        .widthIn(max = metrics.dp(370f))
                        .fillMaxWidth()
                        .height(metrics.dp(NokiUiKitPolicy.advancedProtocolToggleCardHeightDp)),
                    onEnabledChanged = viewModel::setKillSwitchEnabled,
                )

                if (!state.userProfile.isFreePlan) {
                Spacer(modifier = Modifier.height(metrics.dp(25f)))
                AdvancedPanelSurface(
                    backdrop = surfaceBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier.widthIn(max = metrics.dp(370f)).fillMaxWidth()
                        .height(metrics.dp(NokiUiKitPolicy.advancedProtocolToggleCardHeightDp))
                        .clickable(enabled = !state.advancedSettings.ruRelayEnabled, onClick = onMultiHopClicked),
                ) {
                    AdvancedText(
                        text = "MultiHop",
                        fontSize = 16f,
                        lineHeight = 20f,
                        color = if (state.advancedSettings.ruRelayEnabled) AdvancedTextSecondary else AdvancedTextPrimary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.dp(20f)),
                    )
                }
                if (state.advancedSettings.ruRelayEnabled || state.advancedSettings.multiHop.enabled) {
                    AdvancedText(
                        text = if (state.advancedSettings.ruRelayEnabled) multiHopUnavailableText(language) else tr(language,
                            "MultiHop работает через выбранный вход и выход.",
                            "MultiHop uses the selected entry and exit."),
                        fontSize = 12f, lineHeight = 16f, color = AdvancedTextSecondary,
                        modifier = Modifier.widthIn(max = metrics.dp(370f)).fillMaxWidth()
                            .padding(horizontal = metrics.dp(18f), vertical = metrics.dp(8f)),
                    )
                }
                Spacer(modifier = Modifier.height(metrics.dp(25f)))
                AdvancedToggleCard(
                    enabled = state.advancedSettings.ruRelayEnabled,
                    title = tr(language, "RU-реле", "RU relay"),
                    backdrop = surfaceBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier.widthIn(max = metrics.dp(370f)).fillMaxWidth()
                        .height(metrics.dp(NokiUiKitPolicy.advancedProtocolToggleCardHeightDp)),
                    onEnabledChanged = viewModel::setRuRelayEnabled,
                )
                Spacer(modifier = Modifier.height(metrics.dp(25f)))
                AdvancedToggleCard(
                    enabled = state.advancedSettings.gamingModeEnabled,
                    title = tr(language, "Игровой режим", "Gaming mode"),
                    backdrop = surfaceBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    modifier = Modifier.widthIn(max = metrics.dp(370f)).fillMaxWidth()
                        .height(metrics.dp(NokiUiKitPolicy.advancedProtocolToggleCardHeightDp)),
                    onEnabledChanged = viewModel::setGamingModeEnabled,
                )
                }

                }

                if (showProtocolNotice) {
                    AdvancedProtocolNotice(
                        text = tr(
                            language,
                            "Протокол выбран",
                            "Protocol selected",
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = metrics.dp(150f))
                            .width(metrics.dp(328f))
                            .height(metrics.dp(58f)),
                    )
                }

                if (showProtocolSheet && !state.advancedSettings.ruRelayEnabled) {
                    val manualOptions = manualEndpointOptionsForCurrentCountry(state)
                    AdvancedProtocolSheet(
                        selectedProtocol = state.advancedSettings.protocol,
                        endpointOptions = manualOptions,
                        selectedEndpointGroupKey = selectedEndpointGroupKey(state),
                        language = language,
                        backdrop = surfaceBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        bottomNavigationClearance = metrics.dp(80f) + metrics.dp(10f),
                        onDismiss = { showProtocolSheet = false },
                        onProtocolSelected = { selected ->
                            showProtocolSheet = false
                            if (selected != null) {
                                viewModel.selectManualEndpoint(selected)
                                showProtocolNotice = true
                            }
                        },
                    )
                }
            }
        }
    }
}
