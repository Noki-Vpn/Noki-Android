package com.noki.vpn.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.noki.vpn.R

@Composable
internal fun SecurityAndroidVersionBlock(
    updateState: com.noki.vpn.AndroidUpdateUiState,
    language: com.noki.vpn.data.AppLanguage,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    required: Boolean = false,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val update = updateState.update
    val isBusy = updateState.isChecking || updateState.isDownloading
    val currentVersion = updateState.currentVersionName.ifBlank { "unknown" }
    val title = when {
        required -> tr(language, "Необходимо обновить приложение", "App update required")
        updateState.isDownloading -> tr(language, "Скачивание обновления", "Downloading update")
        updateState.isChecking -> tr(language, "Проверка обновлений", "Checking for updates")
        update != null -> tr(language, "Обновить приложение", "Update app")
        else -> tr(language, "Установлена актуальная версия", "Latest version installed")
    }
    val subtitle = securityAndroidUpdateSubtitle(
        language = language,
        currentVersion = currentVersion,
        updateVersion = update?.versionName,
        error = updateState.error,
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(metrics.dp(10f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = metrics.dp(48f))
                .padding(horizontal = if (required) 0.dp else metrics.dp(8f)),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = metrics.dp(12f)),
                verticalArrangement = Arrangement.spacedBy(metrics.dp(5f)),
            ) {
                SecurityText(
                    text = title,
                    fontSize = 18f,
                    lineHeight = 21.6f,
                    letterSpacing = 0f,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecurityText(
                    text = subtitle,
                    color = SecurityTextSecondary.copy(alpha = 0.8f),
                    fontSize = 11.5f,
                    lineHeight = 13.8f,
                    letterSpacing = 0f,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isBusy && update == null && !required) {
                CircularProgressIndicator(
                    modifier = Modifier.size(metrics.dp(24f)),
                    color = SecurityAccent,
                    strokeWidth = metrics.dp(2f),
                    trackColor = SecurityStroke.copy(alpha = 0.42f),
                )
            }
        }
        if (update != null || required) {
            val actionText = when {
                updateState.isReadyToInstall ->
                    tr(language, "Установить обновление", "Install update")
                required -> tr(language, "Обновить", "Update")
                updateState.isDownloading ->
                    tr(language, "Скачивание", "Downloading")
                else -> tr(language, "Обновить приложение", "Update app")
            }
            if (required) AuthPrimaryButton(
                text = actionText,
                loading = isBusy,
                modifier = Modifier.fillMaxWidth().height(metrics.dp(52f)).semantics {
                    role = Role.Button
                    if (isBusy) contentDescription = if (updateState.isDownloading)
                        tr(language, "Скачивание обновления", "Downloading update")
                    else tr(language, "Проверка обновлений", "Checking for updates")
                },
                cornerRadius = metrics.dp(16f),
                useCommonButtonTextStyle = false,
                onClick = onClick,
            ) else SecurityGlassActionButton(
                text = actionText,
                enabled = !isBusy,
                showProgress = updateState.isDownloading,
                backdrop = backdrop,
                liveGlassEnabled = liveGlassEnabled,
                accentGradientFill = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.dp(60f)),
                onClick = onClick,
            )
        }
    }
}

internal fun securityAndroidUpdateSubtitle(
    language: com.noki.vpn.data.AppLanguage,
    currentVersion: String,
    updateVersion: String?,
    error: String?,
): String {
    val prefix = tr(language, "Текущая версия", "Current version") + " $currentVersion"
    return when {
        error != null -> "$prefix · $error"
        updateVersion != null -> "$prefix → $updateVersion"
        else -> prefix
    }
}

@Composable
internal fun SecurityTermsIcon(modifier: Modifier = Modifier) {
    val documentPath = remember { PathParser().parsePathString(SecurityTermsDocumentPath).toPath() }
    val checkPath = remember { PathParser().parsePathString(SecurityTermsCheckPath).toPath() }
    Canvas(modifier = modifier) {
        scale(
            scaleX = size.width / 25f,
            scaleY = size.height / 25f,
            pivot = Offset.Zero,
        ) {
            drawPath(path = documentPath, color = SecurityTextPrimary)
            drawPath(path = checkPath, color = SecurityTextPrimary)
        }
    }
}

@Composable
internal fun SecurityPasswordIcon(modifier: Modifier = Modifier) {
    val lockPath = remember { PathParser().parsePathString(SecurityPasswordLockPath).toPath() }
    Canvas(modifier = modifier) {
        scale(
            scaleX = size.width / 896f,
            scaleY = size.height / 896f,
            pivot = Offset.Zero,
        ) {
            translate(left = -64f, top = -64f) {
                drawPath(path = lockPath, color = SecurityTextPrimary)
            }
        }
    }
}

internal enum class SecurityActionSurfaceStyle {
    Action,
    Panel,
}

internal enum class SecurityActionIcon {
    None,
    Email,
    Password,
    Profile,
    Terms,
    Telegram,
}

@Composable
internal fun SecurityTelegramLinkedRow(
    text: String,
    deleteDescription: String,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    onDeleteClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    SecurityPanelSurface(
        backdrop = backdrop,
        liveGlassEnabled = liveGlassEnabled,
        modifier = modifier.graphicsLayer { alpha = 0.44f },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecurityText(
                text = text,
                color = SecurityTextPrimary,
                fontSize = 16f,
                lineHeight = 19.2f,
                letterSpacing = 0f,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = metrics.dp(20f), end = metrics.dp(12f)),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(metrics.dp(34f))
                    .clip(RoundedCornerShape(percent = 50))
                    .background(SecurityStroke.copy(alpha = 0.9f)),
            )
            Box(
                modifier = Modifier
                    .width(metrics.dp(58f))
                    .fillMaxSize()
                    .semantics {
                        role = Role.Button
                        contentDescription = deleteDescription
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDeleteClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.LinkOff,
                    contentDescription = null,
                    tint = NokiError,
                    modifier = Modifier.size(metrics.dp(24f)),
                )
            }
        }
    }
}

@Composable
internal fun SecurityGlassActionButton(
    text: String,
    enabled: Boolean,
    backdrop: PrismalGlassLayer?,
    liveGlassEnabled: Boolean,
    modifier: Modifier,
    showProgress: Boolean = false,
    icon: SecurityActionIcon = SecurityActionIcon.None,
    animateBeforeClick: Boolean = false,
    accentGradientFill: Boolean = false,
    surfaceStyle: SecurityActionSurfaceStyle = SecurityActionSurfaceStyle.Action,
    onClick: () -> Unit,
) {
    val metrics = nokiAdaptiveMetrics(LocalConfiguration.current.screenWidthDp.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "SecurityGlassActionButtonScale",
    )
    val clickAnimator = rememberAuthButtonClickAnimator()
    val resolvedScale = if (animateBeforeClick) clickAnimator.scale.value else pressScale
    val shape = RoundedCornerShape(metrics.dp(24f))
    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = if (enabled || showProgress) 1f else 0.44f
            }
            .then(
                when (surfaceStyle) {
                    SecurityActionSurfaceStyle.Action -> Modifier.nokiSettingsActionGlassSurface(
                        shape = shape,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        surfaceColor = SecurityBgLighter.copy(alpha = 0.75f),
                        blurAndLensEnabled = false,
                        layerBlock = {
                            scaleX = resolvedScale
                            scaleY = resolvedScale
                        },
                    )

                    SecurityActionSurfaceStyle.Panel -> Modifier.nokiSettingsPanelGlassSurface(
                        shape = shape,
                        backdrop = backdrop,
                        liveGlassEnabled = liveGlassEnabled,
                        scale = metrics.contentScale,
                        elevationDp = 8f,
                        shadowAlpha = 0.25f,
                        surfaceColor = SecurityBgLighter.copy(alpha = 0.80f),
                        blurAndLensEnabled = false,
                        layerBlock = {
                            scaleX = resolvedScale
                            scaleY = resolvedScale
                        },
                    )
                },
            )
            .then(
                if (accentGradientFill) {
                    Modifier.background(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to SecurityAccent.copy(alpha = 0f),
                                0.45f to SecurityAccent.copy(alpha = 0f),
                                0.62f to SecurityAccent.copy(alpha = 0.015f),
                                0.76f to SecurityAccent.copy(alpha = 0.045f),
                                0.88f to SecurityAccent.copy(alpha = 0.10f),
                                1f to SecurityAccent.copy(alpha = 0.24f),
                            ),
                        ),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (animateBeforeClick) {
                        clickAnimator.runClickAnimation(enabled = enabled, then = onClick)
                    } else {
                        onClick()
                    }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.dp(20f)),
            horizontalArrangement = Arrangement.spacedBy(
                if (icon != SecurityActionIcon.None) metrics.dp(15f) else metrics.dp(10f),
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (icon) {
                SecurityActionIcon.Email -> Image(
                    painter = painterResource(R.drawable.security_email_icon),
                    contentDescription = null,
                    modifier = Modifier.size(metrics.dp(21f)),
                )

                SecurityActionIcon.Password -> SecurityPasswordIcon(modifier = Modifier.size(metrics.dp(21f)))
                SecurityActionIcon.Profile -> Box(
                    modifier = Modifier.size(metrics.dp(21f)),
                    contentAlignment = Alignment.Center,
                ) {
                    FigmaSvgAsset(
                        resId = R.raw.account_nav_profile,
                        viewportWidth = 27,
                        viewportHeight = 30,
                        modifier = Modifier
                            .width(metrics.dp(19f))
                            .height(metrics.dp(21f)),
                    )
                }

                SecurityActionIcon.Terms -> SecurityTermsIcon(modifier = Modifier.size(metrics.dp(21f)))
                SecurityActionIcon.Telegram -> Icon(
                    painter = painterResource(R.drawable.login_telegram_icon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(metrics.dp(21f)),
                )
                SecurityActionIcon.None -> Unit
            }
            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(metrics.dp(18f)),
                    color = SecurityAccent,
                    strokeWidth = metrics.dp(2f),
                    trackColor = SecurityStroke.copy(alpha = 0.42f),
                )
            }
            SecurityText(
                text = text,
                color = SecurityTextPrimary,
                fontSize = 16f,
                lineHeight = 19.2f,
                letterSpacing = 0f,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
