package com.noki.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.HopSelection
import com.noki.vpn.data.MultiHopSettings
import com.noki.vpn.data.ServerLocation
import com.noki.vpn.data.VpnServer
import com.noki.vpn.data.ServerSelectionMode

internal fun multiHopUnavailableText(language: AppLanguage): String = tr(language,
    "Выключите RU-реле, чтобы включить MultiHop.", "Turn off RU relay to enable MultiHop.")

internal fun multiHopNodes(state: AppUiState, entryRole: Boolean): List<VpnServer> =
    state.locations.flatMap(ServerLocation::servers).filter {
        it.isOnline && if (entryRole) it.multiHopEntryAvailable else it.multiHopExitAvailable
    }

internal fun multiHopSelectionError(settings: MultiHopSettings, nodes: List<VpnServer>, exits: List<VpnServer> = nodes): String? {
    if (!settings.enabled) return null
    val entry = settings.entry ?: return "choose_entry"
    val exit = settings.exit ?: return "choose_exit"
    if (entry.kind != ServerSelectionMode.SERVER) return "choose_entry"
    if (exit.kind != ServerSelectionMode.SERVER) return "choose_exit"
    fun candidates(selection: HopSelection, available: List<VpnServer>): Set<String> = available
        .filter { it.isOnline }
        .filter { it.id == selection.nodeId }
        .map(VpnServer::id)
        .toSet()
    val entries = candidates(entry, nodes)
    val exitIds = candidates(exit, exits)
    if (entries.isEmpty()) return "entry_unavailable"
    if (exitIds.isEmpty()) return "exit_unavailable"
    if (entries.none { itEntry -> exitIds.any { itExit -> itEntry != itExit } }) return "same_node"
    return null
}

@Composable
fun MultiHopScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    sharedBackdrop: PrismalGlassLayer,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
) {
    val language = state.personalizationSettings.language
    var draft by remember(state.advancedSettings.multiHop) { mutableStateOf(state.advancedSettings.multiHop) }
    var attemptedSave by remember { mutableStateOf(false) }
    var openedPicker by remember { mutableStateOf<String?>(null) }
    val available = !state.advancedSettings.ruRelayEnabled
    LaunchedEffect(available, state.isAuthenticated) {
        if (available && state.isAuthenticated) viewModel.refreshServers()
        if (!available) openedPicker = null
    }
    val entries = remember(state.locations) {
        multiHopNodes(state, entryRole = true)
    }
    val exits = remember(state.locations) {
        multiHopNodes(state, entryRole = false)
    }
    val error = multiHopSelectionError(draft, entries, exits)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = nokiAdaptiveMetrics(maxWidth)
        if (showBackground) {
            Box(Modifier.fillMaxSize().prismalGlassLayer(sharedBackdrop)) {
                HomeBackground(liveGlassEnabled = liveGlassEnabled)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = metrics.contentStart)
                .padding(top = metrics.dp(58f), bottom = metrics.dp(150f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.dp(18f)),
        ) {
            val contentModifier = Modifier.widthIn(max = metrics.dp(370f)).fillMaxWidth()
            if (!available) {
                AdvancedText(
                    text = multiHopUnavailableText(language),
                    fontSize = 16f, lineHeight = 20f, color = AdvancedTextSecondary,
                    modifier = contentModifier,
                )
                return@Column
            }
            AdvancedToggleCard(
                enabled = draft.enabled,
                title = tr(language, "Включить MultiHop", "Enable MultiHop"),
                backdrop = sharedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                modifier = contentModifier.height(metrics.dp(NokiUiKitPolicy.advancedProtocolToggleCardHeightDp))
                    .semantics { contentDescription = "MultiHop" },
                onEnabledChanged = { draft = draft.copy(enabled = it) },
            )
            if (draft.enabled) {
                HopPicker(
                    title = tr(language, "Входной сервер", "Entry server"), selection = draft.entry, nodes = entries,
                    language = language,
                    onExpand = { openedPicker = if (openedPicker == "entry") null else "entry" },
                    modifier = contentModifier,
                )
                HopPicker(
                    title = tr(language, "Выходной сервер", "Exit server"), selection = draft.exit, nodes = exits,
                    language = language,
                    onExpand = { openedPicker = if (openedPicker == "exit") null else "exit" },
                    modifier = contentModifier,
                )
                if (attemptedSave && error != null) AdvancedText(
                    text = when (error) {
                        "choose_entry" -> tr(language, "Выберите вход", "Choose an entry")
                        "choose_exit" -> tr(language, "Выберите выход", "Choose an exit")
                        "entry_unavailable" -> tr(language, "Входная нода недоступна", "Entry node is unavailable")
                        "exit_unavailable" -> tr(language, "Выходная нода недоступна", "Exit node is unavailable")
                        else -> tr(language, "Нужны две разные ноды", "Choose two different nodes")
                    }, color = MaterialTheme.colorScheme.error,
                    fontSize = 12f, lineHeight = 16f, maxLines = 2,
                    modifier = contentModifier.padding(horizontal = metrics.dp(18f)),
                )
            }
            Spacer(Modifier.height(metrics.dp(4f)))
            AdvancedSmallButton(
                text = tr(language, "Сохранить", "Save"),
                fontSize = 16f,
                modifier = contentModifier.height(metrics.dp(52f)),
                cornerRadius = metrics.dp(24f),
                backdrop = sharedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                glassEnabled = true,
                onClick = {
                    attemptedSave = true
                    if (error == null) {
                        viewModel.setMultiHop(draft)
                        viewModel.goBack()
                    }
                },
            )
            AdvancedSmallButton(
                text = tr(language, "Отмена", "Cancel"),
                fontSize = 16f,
                modifier = contentModifier.height(metrics.dp(52f)),
                cornerRadius = metrics.dp(24f),
                backdrop = sharedBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                glassEnabled = true,
                onClick = viewModel::goBack,
            )
        }
        if (available && openedPicker != null) MultiHopServerDialog(
            title = if (openedPicker == "entry") tr(language, "Входной сервер", "Entry server") else tr(language, "Выходной сервер", "Exit server"),
            selection = if (openedPicker == "entry") draft.entry else draft.exit,
            nodes = if (openedPicker == "entry") entries else exits,
            excludedNodeId = (if (openedPicker == "entry") draft.exit else draft.entry)
                ?.takeIf { it.kind == ServerSelectionMode.SERVER }?.nodeId,
            language = language,
            onDismiss = { openedPicker = null },
            onSelect = { selected ->
                draft = if (openedPicker == "entry") draft.copy(entry = selected) else draft.copy(exit = selected)
                openedPicker = null
            },
        )
    }
}

@Composable
private fun HopPicker(
    title: String,
    selection: HopSelection?,
    nodes: List<VpnServer>,
    language: AppLanguage,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val selectedServer = remember(selection, nodes) {
        nodes.firstOrNull { selection?.kind == ServerSelectionMode.SERVER && it.id == selection.nodeId }
    }
    val selectedLabel = selectedServer?.name ?: tr(language, "Выбрать", "Choose")
    Column(
        modifier = modifier.clip(RoundedCornerShape(metrics.dp(24f)))
            .background(HomeBgLighter).padding(metrics.dp(16f)),
        verticalArrangement = Arrangement.spacedBy(metrics.dp(14f)),
    ) {
        AdvancedText(text = title, color = AdvancedTextPrimary, fontWeight = FontWeight.Medium,
            fontSize = 14f, lineHeight = 18f,
            modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().homeServerItemSurface(RoundedCornerShape(metrics.dp(20f)))) {
            HomeServerMenuItem(
                location = null, language = language, scale = metrics.contentScale,
                title = selectedLabel, countryCode = selectedServer?.countryCode,
                latencyMs = selectedServer?.latencyMs,
                contentDescription = "$title: $selectedLabel",
                onClick = onExpand,
            )
        }
    }
}

@Composable
private fun MultiHopServerDialog(
    title: String,
    selection: HopSelection?,
    nodes: List<VpnServer>,
    excludedNodeId: String?,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onSelect: (HopSelection) -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    var expandedCountry by remember { mutableStateOf<String?>(null) }
    val menuLocations = remember(nodes, excludedNodeId, language) {
        serverMenuLocations(nodes = nodes, language = language, excludedNodeId = excludedNodeId)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(SettingsBgBase)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .padding(horizontal = metrics.dp(20f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = metrics.dp(370f)).fillMaxWidth()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp.dp - metrics.dp(100f)).coerceAtLeast(180.dp))
                    .clip(RoundedCornerShape(metrics.dp(24f)))
                    .background(SettingsBgSoft)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .padding(vertical = metrics.dp(16f))
                    .semantics { contentDescription = "MultiHop server picker: $title" },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = metrics.dp(18f), end = metrics.dp(8f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AdvancedText(
                        text = title, color = AdvancedTextPrimary, fontWeight = FontWeight.Medium,
                        fontSize = 18f, lineHeight = 22f, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close,
                            contentDescription = tr(language, "Закрыть выбор сервера", "Close server picker"),
                            tint = AdvancedTextPrimary)
                    }
                }
                if (menuLocations.isEmpty()) AdvancedText(
                    text = tr(language, "Нет доступных нод", "No available nodes"),
                    fontSize = 12f, lineHeight = 16f, color = AdvancedTextSecondary,
                    modifier = Modifier.padding(metrics.dp(18f)),
                )
                HomeServerCountryList(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.dp(14f)),
                    scale = metrics.contentScale, language = language, entries = menuLocations,
                    selectionMode = selection?.kind, selectedCountryCode = selection?.countryCode,
                    selectedNodeId = selection?.nodeId, expandedCountry = expandedCountry,
                    onExpandedCountryChange = { expandedCountry = it },
                    onNodeSelected = { onSelect(HopSelection(ServerSelectionMode.SERVER, nodeId = it)) },
                )
            }
        }
    }
}
