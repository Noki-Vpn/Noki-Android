package com.noki.vpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.noki.vpn.data.AppFilterMode
import com.noki.vpn.data.AppLanguage

@Composable
internal fun AdvancedFilterCard(
    mode: AppFilterMode,
    language: AppLanguage,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onModeChanged: (AppFilterMode) -> Unit,
    onConfigure: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val panelBackdrop = rememberPrismalGlassLayer()
    val options = listOf(
        AppFilterMode.ALL_APPS to tr(language, "Все приложения", "All apps"),
        AppFilterMode.ONLY_SELECTED to tr(language, "Только отмеченные", "Selected only"),
        AppFilterMode.ALL_EXCEPT_SELECTED to tr(language, "Кроме отмеченных", "Except selected"),
    )
    AdvancedPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        nestedGlassSource = panelBackdrop,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.dp(20f), vertical = metrics.dp(20f)),
            verticalArrangement = Arrangement.spacedBy(metrics.dp(15f)),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(metrics.dp(2f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AdvancedText(
                    text = tr(language, "Фильтр по приложениям", "App filter"),
                    fontSize = 17f,
                    lineHeight = 22f,
                    color = AdvancedTextPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                AdvancedText(
                    text = tr(
                        language,
                        "Маршрутизация vpn трафика по приложениям",
                        "Route VPN traffic by applications",
                    ),
                    fontSize = 12f,
                    lineHeight = 14.4f,
                    color = AdvancedTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(metrics.dp(NokiUiKitPolicy.advancedFilterOptionGapDp)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEach { (optionMode, label) ->
                    AdvancedFilterModeOption(
                        label = label,
                        selected = mode == optionMode,
                        onClick = { onModeChanged(optionMode) },
                    )
                }
            }
            AdvancedSmallButton(
                text = tr(language, "Выбор приложений", "Choose apps"),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.dp(NokiUiKitPolicy.settingsInlineActionHeightDp)),
                fontSize = 13f,
                cornerRadius = metrics.dp(18f),
                backdrop = panelBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                glassEnabled = true,
                onClick = onConfigure,
            )
        }
    }
}

@Composable
internal fun AdvancedFilterModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(NokiUiKitPolicy.advancedFilterOptionRadiusDp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.dp(NokiUiKitPolicy.advancedFilterOptionHeightDp))
            .clip(shape)
            .background(AdvancedBgLighter.copy(alpha = 0.75f), shape)
            .border(BorderStroke(1.dp, AdvancedStroke.copy(alpha = 0.45f)), shape)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = metrics.dp(20f)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdvancedText(
            text = label,
            fontSize = 16f,
            lineHeight = 20f,
            color = AdvancedTextPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        AdvancedRadioIndicator(selected = selected)
    }
}

@Composable
internal fun AdvancedRadioIndicator(selected: Boolean) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val shape = RoundedCornerShape(metrics.dp(40f))
    Box(
        modifier = Modifier
            .width(metrics.dp(24f))
            .height(metrics.dp(24f))
            .clip(shape)
            .background(AdvancedBgSoft.copy(alpha = 0.44f), shape)
            .border(
                BorderStroke(
                    1.dp,
                    if (selected) AdvancedAccentPrimary.copy(alpha = 0.72f) else AdvancedStroke.copy(alpha = 0.9f),
                ),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .width(metrics.dp(14f))
                    .height(metrics.dp(14f))
                    .clip(shape)
                    .background(AdvancedAccentPrimary, shape),
            )
        }
    }
}

@Composable
internal fun AdvancedDomainRuleWideCard(
    title: String,
    count: Int,
    detail: String,
    language: AppLanguage,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    summary: String? = null,
    onConfigure: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val panelBackdrop = rememberPrismalGlassLayer()
    AdvancedPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        nestedGlassSource = panelBackdrop,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.dp(20f)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = metrics.dp(12f)),
            ) {
                AdvancedText(
                    text = title,
                    fontSize = 13f,
                    lineHeight = 15.6f,
                    color = AdvancedTextPrimary,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (summary != null) AdvancedText(
                    text = summary,
                    fontSize = 13f,
                    lineHeight = 14f,
                    color = AdvancedTextPrimary,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                ) else Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(metrics.dp(4f)),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AdvancedText(
                        text = count.toString(),
                        fontSize = 13f,
                        lineHeight = 14f,
                        color = AdvancedTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier,
                    )
                    AdvancedText(
                        text = "${domainLabel(language, count)} $detail",
                        fontSize = 10f,
                        lineHeight = 12f,
                        color = AdvancedTextSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            AdvancedSmallButton(
                text = tr(language, "Настроить", "Set up"),
                modifier = Modifier
                    .width(metrics.dp(90f))
                    .height(metrics.dp(36f)),
                backdrop = panelBackdrop,
                liveGlassEnabled = liveGlassEnabled,
                glassEnabled = true,
                onClick = onConfigure,
            )
        }
    }
}

internal fun domainLabel(language: AppLanguage, count: Int): String {
    if (language != AppLanguage.RU) {
        return if (count == 1) "domain" else "domains"
    }
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod10 == 1 && mod100 != 11 -> "домен"
        mod10 in 2..4 && mod100 !in 12..14 -> "домена"
        else -> "доменов"
    }
}

internal fun customRuleSummary(language: AppLanguage, count: Int): String =
    tr(language, "Правила пользователя: $count", "Custom rules: $count")
