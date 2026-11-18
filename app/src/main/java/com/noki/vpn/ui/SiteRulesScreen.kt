package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.noki.vpn.AppUiState
import com.noki.vpn.MainViewModel
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DomainRulePolicy

enum class SiteRulesMode {
    ALWAYS_ON,
    BYPASS,
}

internal fun siteRuleValidationError(
    value: String,
    domains: List<String>,
    editingDomain: String?,
    language: AppLanguage,
): String? {
    if (value.isBlank()) {
        return tr(language, "Введите домен", "Enter a domain")
    }
    val normalized = DomainRulePolicy.normalize(value)
        ?: return tr(language, "Введите корректный домен", "Enter a valid domain")
    val editingNormalized = editingDomain?.let(DomainRulePolicy::normalize)
    val duplicate = normalized != editingNormalized &&
        domains.any { DomainRulePolicy.normalize(it) == normalized }
    return if (duplicate) {
        tr(language, "Этот домен уже добавлен в список", "This domain is already in the list")
    } else {
        null
    }
}

private val SiteRulesBgBase = Color(0xFF07111A)
private val SiteRulesBgSoft = Color(0xFF132635)
private val SiteRulesTextPrimary = Color(0xFFF4FBFF)
private val SiteRulesTextSecondary = Color(0xFF9FB6C5)
private val SiteRulesTextMuted = Color(0xFF6E8797)
private val SiteRulesAccent = Color(0xFF7AE7C7)
private val SiteRulesDanger = Color(0xFFFF646B)
private val SiteRulesStroke = Color(0xFF29404E)
private val SiteRulesNoFontPaddingTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

@Composable
fun SiteRulesScreen(
    state: AppUiState,
    viewModel: MainViewModel,
    mode: SiteRulesMode,
    sharedBackdrop: PrismalGlassLayer? = null,
    liveGlassEnabled: Boolean = true,
    showBackground: Boolean = true,
) {
    val language = state.personalizationSettings.language
    val domains = when (mode) {
        SiteRulesMode.ALWAYS_ON -> state.advancedSettings.alwaysOnDomains
        SiteRulesMode.BYPASS -> state.advancedSettings.bypassDomains
    }
    var input by rememberSaveable { mutableStateOf("") }
    var editingDomain by rememberSaveable { mutableStateOf<String?>(null) }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(mode) {
        input = ""
        editingDomain = null
        inputError = null
    }

    CompositionLocalProvider(LocalTextStyle provides SiteRulesNoFontPaddingTextStyle) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showBackground) Modifier.background(SiteRulesBgBase) else Modifier)
                .statusBarsPadding()
                .imePadding(),
        ) {
            val metrics = nokiAdaptiveMetrics(maxWidth)
            val headerTop = metrics.dp(58f)
            val headerTitleLineHeight = metrics.dp(28.8f)
            val headerGap = metrics.dp(3f)
            val headerSubtitleLineHeight = metrics.dp(14.4f)
            val headerToContentGap = metrics.dp(20f)
            val contentTop = headerTop + headerTitleLineHeight + headerGap + headerSubtitleLineHeight + headerToContentGap
            val contentHeight = (maxHeight - contentTop - metrics.dp(110f)).coerceAtLeast(0.dp)

            if (showBackground) {
                HomeBackground(liveGlassEnabled = liveGlassEnabled)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                SiteRulesText(
                    text = titleForMode(language, mode),
                    fontSize = 24f,
                    lineHeight = 28.8f,
                    color = SiteRulesTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .offset(x = metrics.contentStart + metrics.dp(18f), y = headerTop)
                        .width(metrics.dp(284f)),
                )
                SiteRulesText(
                    text = subtitleForMode(language, mode),
                    fontSize = 12f,
                    lineHeight = 14.4f,
                    color = SiteRulesTextSecondary,
                    maxLines = 2,
                    modifier = Modifier
                        .offset(x = metrics.contentStart + metrics.dp(18f), y = headerTop + headerTitleLineHeight + headerGap)
                        .width(metrics.dp(284f)),
                )

                Column(
                    modifier = Modifier
                        .offset(x = metrics.contentStart, y = contentTop)
                        .width(metrics.contentWidth)
                        .height(contentHeight),
                    verticalArrangement = Arrangement.spacedBy(metrics.dp(12f)),
                ) {
                    SiteRulesEditorCard(
                        value = input,
                        editing = editingDomain != null,
                        error = inputError,
                        language = language,
                        backdrop = sharedBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        onValueChange = {
                            input = it
                            inputError = null
                        },
                        onCancel = {
                            input = ""
                            editingDomain = null
                            inputError = null
                        },
                        onSubmit = {
                            val oldDomain = editingDomain
                            val error = siteRuleValidationError(
                                value = input,
                                domains = domains,
                                editingDomain = oldDomain,
                                language = language,
                            )
                            if (error != null) {
                                inputError = error
                            } else {
                                if (oldDomain == null) {
                                    when (mode) {
                                        SiteRulesMode.ALWAYS_ON -> viewModel.addAlwaysOnDomain(input)
                                        SiteRulesMode.BYPASS -> viewModel.addBypassDomain(input)
                                    }
                                } else {
                                    when (mode) {
                                        SiteRulesMode.ALWAYS_ON -> viewModel.updateAlwaysOnDomain(oldDomain, input)
                                        SiteRulesMode.BYPASS -> viewModel.updateBypassDomain(oldDomain, input)
                                    }
                                }
                                input = ""
                                editingDomain = null
                                inputError = null
                            }
                        },
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(metrics.dp(9f)),
                    ) {
                        if (domains.isEmpty() && mode != SiteRulesMode.BYPASS) {
                            item(contentType = "site-rule-empty") {
                                SiteRulesEmptyCard(
                                    language = language,
                                    backdrop = sharedBackdrop,
                                    liveGlassEnabled = liveGlassEnabled,
                                )
                            }
                        }
                        if (mode == SiteRulesMode.BYPASS) {
                            item(key = DomainRulePolicy.RUSSIAN_RESOURCES_RULE, contentType = "site-rule-row") {
                                SiteRuleRow(
                                    domain = DomainRulePolicy.RUSSIAN_RESOURCES_RULE,
                                    language = language,
                                    backdrop = sharedBackdrop,
                                    liveGlassEnabled = liveGlassEnabled,
                                    russianResourcesEnabled = DomainRulePolicy.RUSSIAN_RESOURCES_RULE in domains,
                                    onEdit = {},
                                    onDelete = {
                                        if (DomainRulePolicy.RUSSIAN_RESOURCES_RULE in domains) {
                                            viewModel.removeBypassDomain(DomainRulePolicy.RUSSIAN_RESOURCES_RULE)
                                        } else {
                                            viewModel.addBypassDomain(DomainRulePolicy.RUSSIAN_RESOURCES_RULE)
                                        }
                                    },
                                )
                            }
                        }
                        items(
                            items = domains.filterNot { mode == SiteRulesMode.BYPASS && it == DomainRulePolicy.RUSSIAN_RESOURCES_RULE },
                            key = { it },
                            contentType = { "site-rule-row" },
                        ) { domain ->
                            SiteRuleRow(
                                domain = domain,
                                language = language,
                                backdrop = sharedBackdrop,
                                liveGlassEnabled = liveGlassEnabled,
                                onEdit = {
                                    input = domain
                                    editingDomain = domain
                                    inputError = null
                                },
                                onDelete = {
                                    when (mode) {
                                        SiteRulesMode.ALWAYS_ON -> viewModel.removeAlwaysOnDomain(domain)
                                        SiteRulesMode.BYPASS -> viewModel.removeBypassDomain(domain)
                                    }
                                    if (editingDomain == domain) {
                                        input = ""
                                        editingDomain = null
                                        inputError = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteRulesEditorCard(
    value: String,
    editing: Boolean,
    error: String?,
    language: AppLanguage,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    SiteRulesCard(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(
                when {
                    error != null && editing -> metrics.dp(190f)
                    error != null -> metrics.dp(176f)
                    editing -> metrics.dp(164f)
                    else -> metrics.dp(150f)
                },
            ),
    ) { panelBackdrop ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(metrics.dp(14f)),
            verticalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
        ) {
            SiteRulesText(
                text = if (editing) {
                    tr(language, "Редактировать домен", "Edit domain")
                } else {
                    tr(language, "Добавить домен", "Add domain")
                },
                fontSize = 16f,
                lineHeight = 19.2f,
                color = SiteRulesTextPrimary,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.fillMaxWidth(),
            )
            SiteRulesInput(
                value = value,
                onValueChange = onValueChange,
                placeholder = tr(language, "example.com", "example.com"),
            )
            error?.let {
                SiteRulesText(
                    text = it,
                    fontSize = 11.5f,
                    lineHeight = 13.8f,
                    color = SiteRulesDanger,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(metrics.dp(9f)),
            ) {
                if (editing) {
                    AdvancedSmallButton(
                        text = tr(language, "Отмена", "Cancel"),
                        modifier = Modifier
                            .weight(1f)
                            .height(metrics.dp(NokiUiKitPolicy.settingsInlineActionHeightDp)),
                        fontSize = 13f,
                        cornerRadius = metrics.dp(18f),
                        backdrop = panelBackdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        glassEnabled = true,
                        onClick = onCancel,
                    )
                }
                AdvancedSmallButton(
                    text = tr(language, "Сохранить", "Save"),
                    modifier = Modifier
                        .weight(1f)
                        .height(metrics.dp(NokiUiKitPolicy.settingsInlineActionHeightDp)),
                    fontSize = 13f,
                    cornerRadius = metrics.dp(18f),
                    backdrop = panelBackdrop,
                    liveGlassEnabled = liveGlassEnabled,
                    glassEnabled = true,
                    onClick = onSubmit,
                )
            }
        }
    }
}

@Composable
internal fun SiteRuleRow(
    domain: String,
    language: AppLanguage,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    russianResourcesEnabled: Boolean? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val russianResources = russianResourcesEnabled != null && domain == DomainRulePolicy.RUSSIAN_RESOURCES_RULE
    SiteRulesCard(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(64f)),
    ) { _ ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.dp(14f)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdvancedFittingText(
                text = if (russianResources) tr(language, "Российские ресурсы", "Russian resources") else domain,
                fontSize = 15f,
                lineHeight = 18f,
                minFontSize = 10f,
                color = SiteRulesTextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(end = metrics.dp(8f)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(metrics.dp(7f))) {
                if (russianResources) {
                    SiteRulesButton(
                        text = if (russianResourcesEnabled == true) "on" else "off",
                        danger = russianResourcesEnabled != true,
                        active = russianResourcesEnabled == true,
                        modifier = Modifier.width(metrics.dp(54f)).height(metrics.dp(40f)),
                        onClick = onDelete,
                    )
                } else {
                    SiteRulesButton(
                        text = tr(language, "Изм.", "Edit"),
                        modifier = Modifier.width(metrics.dp(46f)).height(metrics.dp(40f)),
                        onClick = onEdit,
                    )
                    SiteRulesButton(
                        text = tr(language, "Удалить", "Delete"),
                        danger = true,
                        modifier = Modifier.width(metrics.dp(65f)).height(metrics.dp(40f)),
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun SiteRulesEmptyCard(
    language: AppLanguage,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    SiteRulesCard(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(76f)),
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(metrics.dp(15f)),
            verticalArrangement = Arrangement.spacedBy(metrics.dp(3f), Alignment.CenterVertically),
        ) {
            SiteRulesText(
                text = tr(language, "Правил пока нет", "No rules yet"),
                fontSize = 14f,
                lineHeight = 16.8f,
                color = SiteRulesTextPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            SiteRulesText(
                text = tr(language, "Добавьте домен выше, чтобы он попал в список.", "Add a domain above to create a rule."),
                fontSize = 10.5f,
                lineHeight = 12.6f,
                color = SiteRulesTextSecondary,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SiteRulesInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(15f))
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        cursorBrush = SolidColor(SiteRulesAccent),
        textStyle = TextStyle(
            color = SiteRulesTextPrimary,
            fontFamily = ManropeFontFamily,
            fontSize = metrics.sp(12f),
            lineHeight = metrics.sp(14.5f),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(38f))
            .clip(shape)
            .background(SiteRulesBgSoft, shape)
            .padding(horizontal = metrics.dp(14f)),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isBlank()) {
                    SiteRulesText(
                        text = placeholder,
                        fontSize = 14f,
                        lineHeight = 16.8f,
                        color = SiteRulesTextMuted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SiteRulesCard(
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    content: @Composable (PrismalGlassLayer) -> Unit,
) {
    val panelBackdrop = rememberPrismalGlassLayer()
    AdvancedPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        nestedGlassSource = panelBackdrop,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            content(panelBackdrop)
        }
    }
}

@Composable
private fun SiteRulesButton(
    text: String,
    modifier: Modifier,
    danger: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 520f,
        ),
        label = "SiteRulesButtonScale",
    )
    val shape = RoundedCornerShape(metrics.dp(14f))
    val color = when {
        danger -> SiteRulesDanger
        active -> SiteRulesAccent
        else -> SiteRulesBgSoft
    }
    val backgroundAlpha = if (danger) 0.16f else if (active) 0.16f else 1f
    val textColor = if (danger) SiteRulesDanger else if (active) SiteRulesAccent else SiteRulesTextPrimary
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(color.copy(alpha = backgroundAlpha), shape)
            .border(BorderStroke(1.dp, color.copy(alpha = 0.55f)), shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SiteRulesText(
            text = text,
            fontSize = 13f,
            lineHeight = 15.6f,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SiteRulesText(
    text: String,
    fontSize: Float,
    lineHeight: Float,
    color: Color,
    modifier: Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val contentScale = nokiAdaptiveMetrics(configuration.screenWidthDp.dp).contentScale
    Text(
        text = text,
        color = color,
        fontFamily = ManropeFontFamily,
        fontWeight = fontWeight,
        fontSize = (fontSize * contentScale / density.fontScale).sp,
        lineHeight = (lineHeight * contentScale / density.fontScale).sp,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = modifier,
    )
}

private fun titleForMode(language: AppLanguage, mode: SiteRulesMode): String {
    return when (mode) {
        SiteRulesMode.ALWAYS_ON -> tr(language, "Всегда включать", "Always on")
        SiteRulesMode.BYPASS -> tr(language, "Всегда выключать", "Always off")
    }
}

private fun subtitleForMode(language: AppLanguage, mode: SiteRulesMode): String {
    return when (mode) {
        SiteRulesMode.ALWAYS_ON -> tr(language, "Эти домены всегда будут идти через VPN", "These domains always use VPN")
        SiteRulesMode.BYPASS -> tr(language, "Эти домены всегда будут идти без VPN", "These domains always bypass VPN")
    }
}
