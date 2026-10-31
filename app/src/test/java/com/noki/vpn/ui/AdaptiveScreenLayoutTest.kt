package com.noki.vpn.ui

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer
import com.noki.vpn.AppUiState
import com.noki.vpn.AppDestination
import com.noki.vpn.AuthStep
import com.noki.vpn.MainViewModel
import com.noki.vpn.applyAndPersist
import com.noki.vpn.applyBackendSyncResult
import com.noki.vpn.withFreeTrafficLimitNotice
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DeviceSession
import com.noki.vpn.data.PersonalizationSettings
import com.noki.vpn.data.ServerSelectionMode
import com.noki.vpn.data.SettingsRepository
import com.noki.vpn.vpn.AppVpnServiceCommandTest.InMemoryAndroidKeyStore
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.ImageFetchResult
import coil3.decode.DataSource
import coil3.request.Options
import java.io.File
import java.security.Provider
import java.security.Security
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w512dp-h1108dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveScreenLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ComponentActivity

    @Test
    fun mandatoryUpdateAllowsLoginAfterLogoutAndReturnsAfterAuthentication() {
        val viewModel = layoutViewModel()
        var state by androidx.compose.runtime.mutableStateOf(com.noki.vpn.LogoutStateReducer.reduce(
            russianState().copy(isAuthenticated = true, androidUpdate = com.noki.vpn.AndroidUpdateUiState(isRequired = true)),
        ))
        render {
            androidx.compose.material3.Text("Войти")
            AppDialogHost(state, viewModel, onOpenVpnSettings = {})
        }.use {
            assertTrue(state.androidUpdate.requiresUpdate)
            compose.onNodeWithText("Войти").assertIsDisplayed()
            compose.onNodeWithText("Необходимо обновить приложение").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(isAuthenticated = true) }
            compose.onNodeWithText("Необходимо обновить приложение").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun mandatoryUpdateHasOneActionIgnoresDismissalAndReusesDownloadProgress() {
        var updates = 0
        var updateState by androidx.compose.runtime.mutableStateOf(com.noki.vpn.AndroidUpdateUiState(
            isRequired = true,
            currentVersionName = "2.2.1",
            update = com.noki.vpn.AndroidUpdateInfo(315, "2.2.2", architecture = "arm64-v8a", apkUrl = "https://example.invalid/update.apk"),
        ))
        render { MandatoryAndroidUpdateDialog(updateState, AppLanguage.RU) { updates++ } }.use {
            compose.onNodeWithText("Необходимо обновить приложение").assertIsDisplayed()
            compose.onAllNodes(hasClickAction()).assertCountEquals(1)
            saveImage("mandatory-update", includeDialogs = true)
            compose.runOnIdle {
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
                dialog.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
                val outside = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
                dialog.onTouchEvent(outside)
                outside.recycle()
                assertTrue(dialog.isShowing)
            }
            compose.onNodeWithText("Обновить").performClick()
            compose.waitForIdle()
            assertEquals(1, updates)
            compose.runOnIdle { updateState = updateState.copy(isDownloading = true) }
            compose.onNodeWithContentDescription("Скачивание обновления").assertIsNotEnabled()
            compose.onAllNodes(androidx.compose.ui.test.hasProgressBarRangeInfo(
                androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate,
            )).assertCountEquals(1)
            assertEquals(1, updates)
            saveImage("mandatory-update-downloading", includeDialogs = true)
            compose.runOnIdle { updateState = updateState.copy(isDownloading = false, isReadyToInstall = true) }
            compose.onNodeWithText("Установить обновление").assertIsDisplayed().performClick()
            compose.waitForIdle()
            assertEquals(2, updates)
        }
    }

    @Before
    fun provideInMemoryAndroidKeyStore() {
        android.provider.Settings.Secure.putString(
            RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
            "noki-layout-test",
        )
        Security.addProvider(object : Provider("NokiAdaptiveScreenTest", 1.0, "Test-only Android keystore") {
            init { put("KeyStore.AndroidKeyStore", InMemoryAndroidKeyStore::class.java.name) }
        })
    }

    @After
    fun removeInMemoryAndroidKeyStore() {
        Security.removeProvider("NokiAdaptiveScreenTest")
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun activityPreservesCurrentDisplayDensity() {
        val expected = RuntimeEnvironment.getApplication().resources.configuration
        val controller = Robolectric.buildActivity(com.noki.vpn.MainActivity::class.java)
        val actual = controller.get().resources.configuration
        assertEquals(expected.densityDpi, actual.densityDpi)
        assertEquals(expected.screenWidthDp, actual.screenWidthDp)
        assertEquals(expected.screenHeightDp, actual.screenHeightDp)
        assertEquals(expected.smallestScreenWidthDp, actual.smallestScreenWidthDp)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun plusCardUsesTheSpaceAboveAndShowsAllFeaturesAndItsFullFooter() {
        val viewModel = layoutViewModel()
        val footer = "Для одного человека и нескольких личных устройств"
        val features = listOf("без ограничений трафика", "скорость до 150 Мбит/с", "3 устройства", "приватный DNS",
            "блокировка рекламы", "Группа с неограниченным количеством устройств")
        val plan = com.noki.vpn.data.PlanSummary("plus_yearly", "plus", "Plus", 3, null, "Без ограничений", 3000, 250,
            headline = footer, features = features)
        render {
            PlansScreen(russianState().copy(plans = listOf(plan)), viewModel, liveGlassEnabled = false,
                showBackground = false, onCheckoutVisibilityChanged = {})
        }.use {
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithText("Plus").fetchSemanticsNode().boundsInRoot
            assertTrue("The card must use the empty space above it", title.top < root.height * 0.15f)
            features.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(footer).assertIsDisplayed().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.hasVisualOverflow)
            assertEquals(footer.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            compose.onNodeWithText("Подключить").assertIsDisplayed()
            saveImage("plus-card-full")
        }
    }

    @Test
    fun freeTrialHidesYoutubeToggleWhilePaidPlanKeepsIt() {
        val viewModel = layoutViewModel()
        val free = russianState().let { it.copy(userProfile = it.userProfile.copy(selectedPlanCodeRaw = "free-plus",
            trialTrafficRemainingGb = 3.0), advancedSettings = it.advancedSettings.copy(youtubeNoAdsEnabled = true)) }
        render { AdvancedSettingsScreen(free, viewModel, rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false) }.use {
            compose.onNodeWithText("YouTube без рекламы").assertDoesNotExist()
        }
        render { AdvancedSettingsScreen(free.copy(userProfile = free.userProfile.copy(selectedPlanTier = "plus")), viewModel,
            rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false) }.use {
            compose.onNodeWithText("YouTube без рекламы").assertExists()
        }
    }

    @Test
    fun freePlanDowngradeDisablesCachedYoutubeAndRestartsOnlyOnceFromStats() {
        val application = RuntimeEnvironment.getApplication()
        val viewModel = layoutViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(viewModel, "runtime")
        runtime.uiState = russianState().let { it.copy(isAuthenticated = true,
            connectionState = com.noki.vpn.data.VpnConnectionState.CONNECTED,
            advancedSettings = it.advancedSettings.copy(youtubeNoAdsEnabled = true),
            userProfile = it.userProfile.copy(backendUserId = "downgraded", selectedPlanTier = "plus")) }
        val paid = runtime.uiState
        val free = paid.userProfile.copy(selectedPlanTier = "free", trialTrafficRemainingGb = 3.0)
        val result = com.noki.vpn.BackendSyncResult(
            com.noki.vpn.BackendSyncPatch(free, emptyList(), emptyList(), emptyList(), paid.profile, "owner", paid.androidUpdate),
            emptyList(), emptyList(), emptyList())
        val services = org.robolectric.Shadows.shadowOf(application)
        while (services.nextStartedService != null) Unit
        runtime.applyBackendSyncResult(result, com.noki.vpn.BackendRefreshTrigger.Stats, false, 0L, false)
        assertEquals(com.noki.vpn.vpn.AppVpnService.restartIntent(application).action, services.nextStartedService?.action)
        assertFalse(runtime.repository.load().advancedSettings.youtubeNoAdsEnabled)
        assertEquals("free", runtime.repository.load().userProfile.selectedPlanTier)
        runtime.applyBackendSyncResult(result, com.noki.vpn.BackendRefreshTrigger.Stats, false, 0L, false)
        assertFalse(runtime.uiState.advancedSettings.youtubeNoAdsEnabled)
        assertEquals(null, services.nextStartedService)
    }

    @Test
    fun trialExhaustionFromStatsNotifiesOncePerAccountAndKeepsFreeVpn() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications = application.getSystemService(android.app.NotificationManager::class.java)
        val viewModel = layoutViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(viewModel, "runtime")
        val connected = russianState().copy(
            isAuthenticated = true, isReady = true, installOnboardingStep = com.noki.vpn.data.InstallOnboardingStep.COMPLETE,
            screenStack = listOf(AppDestination.HOME),
            connectionState = com.noki.vpn.data.VpnConnectionState.CONNECTED, connectedAtMillis = 123L,
        )
        val exhausted = connected.userProfile.copy(backendUserId = "trial-account", selectedPlanCodeRaw = "free-plus",
            trialTrafficRemainingGb = 0.0, freeLocationsOnly = true)
        runtime.uiState = connected.copy(userProfile = exhausted.copy(trialTrafficRemainingGb = 1.0))
        val services = org.robolectric.Shadows.shadowOf(application)
        while (services.nextStartedService != null) Unit
        val result = com.noki.vpn.BackendSyncResult(
            com.noki.vpn.BackendSyncPatch(exhausted, emptyList(), emptyList(), emptyList(), connected.profile,
                "owner", connected.androidUpdate), emptyList(), emptyList(), emptyList(),
        )
        runtime.applyBackendSyncResult(result, com.noki.vpn.BackendRefreshTrigger.Stats, false, 0L, false)
        assertEquals(com.noki.vpn.AppDialog.FreeTrafficLimitReached, runtime.uiState.dialog)
        assertEquals(com.noki.vpn.data.VpnConnectionState.CONNECTED, runtime.uiState.connectionState)
        assertEquals(123L, runtime.uiState.connectedAtMillis)
        assertEquals(null, services.nextStartedService)
        val notice = org.robolectric.Shadows.shadowOf(notifications).allNotifications.single()
        assertEquals("Пробный трафик закончился", notice.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Вы использовали пробный трафик. Можно остаться на Free или выбрать подписку с полным доступом.",
            notice.extras.getString(android.app.Notification.EXTRA_TEXT))
        render { AppDialogHost(viewModel.uiState, viewModel, {}) }.use {
            compose.onNodeWithText("Остаться на Free").performClick()
            assertEquals(null, runtime.uiState.dialog)
        }
        notifications.cancelAll()
        val recreated = layoutViewModel()
        val restored = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(recreated, "runtime")
        restored.applyAndPersist(connected.copy(userProfile = exhausted))
        assertEquals(null, restored.uiState.dialog)
        assertTrue(org.robolectric.Shadows.shadowOf(notifications).allNotifications.isEmpty())
        restored.applyAndPersist(connected.copy(userProfile = exhausted.copy(backendUserId = "another-trial-account")))
        assertEquals(com.noki.vpn.AppDialog.FreeTrafficLimitReached, restored.uiState.dialog)
        recreated.confirmDialog()
        assertEquals(AppDestination.PLANS, restored.uiState.currentDestination)
    }

    @Test
    fun trialNoticeIgnoresPaidSharedQuotaAndMissingTrialDataAndRespectsNotificationPermission() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = layoutViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(viewModel, "runtime")
        val base = russianState().copy(isAuthenticated = true)
        for (profile in listOf(
            base.userProfile.copy(backendUserId = "paid", selectedPlanCodeRaw = "plus-monthly", selectedPlanTier = "plus",
                trialTrafficRemainingGb = 0.0, sharedFreeOnly = true, freeLocationsOnly = true),
            base.userProfile.copy(backendUserId = "unknown", trialTrafficRemainingGb = null, freeLocationsOnly = true),
            base.userProfile.copy(backendUserId = "remaining", trialTrafficRemainingGb = 1.0),
            base.userProfile.copy(backendUserId = "rounded", trialTrafficRemainingGb = 0.0, freeLocationsOnly = false),
        )) assertEquals(null, runtime.withFreeTrafficLimitNotice(base.copy(userProfile = profile)).dialog)
        val exhausted = base.copy(userProfile = base.userProfile.copy(backendUserId = "no-permission",
            trialTrafficRemainingGb = 0.0, freeLocationsOnly = true))
        assertEquals(com.noki.vpn.AppDialog.FreeTrafficLimitReached, runtime.withFreeTrafficLimitNotice(exhausted).dialog)
        val notifications = application.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(org.robolectric.Shadows.shadowOf(notifications).allNotifications.isEmpty())
    }

    @Test
    fun homeRefreshUpdatesTheServerSheetAndHomeFromTheSameProbeEvenDuringBackendRefresh() = kotlinx.coroutines.runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val viewModel = layoutViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(viewModel, "runtime")
        val workers = kotlinx.coroutines.SupervisorJob()
        org.robolectric.util.ReflectionHelpers.setField(runtime, "scope", kotlinx.coroutines.CoroutineScope(coroutineContext + workers))
        runtime.authSessionCoordinator.restore(runtime.repository.load().copy(backendAccessToken = "test-token"))
        val delay = java.util.concurrent.atomic.AtomicInteger(39)
        val probes = java.util.concurrent.atomic.AtomicInteger()
        org.robolectric.util.ReflectionHelpers.setField(runtime, "clientLatencySampler", com.noki.vpn.data.ClientLatencySampler(
            tcpConnect = { _, _, _ -> probes.incrementAndGet(); delay.get() },
        ))
        val shadow = org.robolectric.Shadows.shadowOf(application)
        while (shadow.nextStartedService != null) Unit
        val node = com.noki.vpn.data.VpnServer("active", "Active", "LV", "lv2", "probe.example", 443, true)
        runtime.uiState = russianState().let { it.copy(
            advancedSettings = it.advancedSettings.copy(ruRelayEnabled = false),
            connectionState = com.noki.vpn.data.VpnConnectionState.CONNECTED,
            connectionLatencyMs = 999,
            locations = listOf(com.noki.vpn.data.ServerLocation("lv2", "LV", "Latvia", "Riga", node.host,
                isOnline = true, servers = listOf(node))),
            userProfile = it.userProfile.copy(selectedServerCode = "lv2"),
            isRefreshingData = true,
        ) }
        try {
            for (sample in listOf(39, 75)) {
                delay.set(sample)
                refreshVisibleScreenData(AppDestination.HOME,
                    { latency -> viewModel.refreshAllData(refreshClientLatency = latency) }, {})
                kotlinx.coroutines.withTimeout(5_000) {
                    while (runtime.uiState.locations.single().servers.single().latencyMs != sample) kotlinx.coroutines.yield()
                }
                val row = serverMenuLocations(runtime.uiState.locations).single().servers.single()
                assertEquals(sample, row.latencyMs)
                assertEquals(row.latencyMs.toString(), currentMetrics(runtime.uiState, HomeDeviceTrafficSnapshot()).latency)
                assertEquals(null, shadow.nextStartedService)
            }
            assertEquals(6, probes.get())
        } finally {
            workers.cancel()
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun avatarGalleryLabelFitsItsMenu() = render {
        PersonalizationAvatarMenu(AppLanguage.RU, null, false, true, {}, {}, Modifier)
    }.use {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Выбрать из галереи", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        saveImage("avatar-menu")
        val layout = layouts.single()
        assertTrue("Gallery label must not be ellipsized", !layout.isLineEllipsized(0))
        assertEquals("Every character must remain visible", layout.layoutInput.text.length, layout.getLineEnd(0, visibleEnd = true))
    }

    @Test
    fun statisticsDayShowsTrafficAndGroupIsCentered() = render {
        StatsScreen(russianState(), liveGlassEnabled = false, showBackground = false)
    }.use {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val today = compose.onNodeWithText("Сегодня").fetchSemanticsNode().boundsInRoot
        val year = compose.onNodeWithText("Год").fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, (today.left + year.right) / 2f, 12f)
        compose.onNodeWithContentDescription("Пн: 0 МБ").performClick()
        compose.onNodeWithText("Пн\n0 МБ").assertIsDisplayed()
        Unit
    }

    @Test
    fun serverSheetListScrollsWithoutClosing() {
        val countries = (1..30).map { index ->
            com.noki.vpn.data.ServerLocation("c$index", "LV", "Страна $index", "Riga", "example.test", isOnline = true)
        }
        var collapsed = false
        render {
            val backdrop = rememberPrismalGlassLayer()
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().prismalGlassLayer(backdrop).background(HomeBgBase))
                HomeServerDropdownOverlay(Modifier.fillMaxSize(), true, 1f, AppLanguage.RU,
                    countries, { collapsed = true }, { _, _ -> },
                    com.noki.vpn.data.UserProfile())
            }
        }.use {
            val list = compose.onNode(androidx.compose.ui.test.hasScrollAction(), useUnmergedTree = true)
            val initialScroll = list.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
            ].value()
            list.performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
            compose.waitForIdle()
            val finalScroll = list.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
            ].value()
            assertTrue("Swipe inside the list must scroll its countries", finalScroll > initialScroll)
            assertFalse("Scrolling countries must not close the sheet", collapsed)
        }
    }

    @Test
    fun serverSheetClosesFromHandle() {
        var collapsed = false
        render {
            HomeServerDropdownOverlay(Modifier.fillMaxSize(), true, 1f, AppLanguage.RU,
                emptyList(), { collapsed = true }, { _, _ -> },
                com.noki.vpn.data.UserProfile())
        }.use {
            compose.onNodeWithText("Автовыбор").assertIsDisplayed()
            val handleInset = NokiUiKitPolicy.homeServerDropdownHandleTouchHeightDp *
                activity.resources.displayMetrics.density / 2f
            compose.mainClock.autoAdvance = false
            try {
                compose.onRoot().performTouchInput { swipeUp(startY = height - handleInset, endY = height * 0.6f) }
                compose.runOnIdle {
                    assertTrue("The close request must not wait for a second animation", collapsed)
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
        }
    }

    @Test
    fun countryExpansionKeepsNodeSelectionAndCollapseWorking() {
        val server = com.noki.vpn.data.VpnServer("lv-1", "Latvia 1", "LV", "lv", "example.test", 443, true)
        val country = com.noki.vpn.data.ServerLocation("lv", "LV", "Латвия", "Riga", "example.test", isOnline = true, servers = listOf(server))
        var selected: Pair<String, com.noki.vpn.data.ServerSelectionMode>? = null
        render {
            HomeServerDropdownOverlay(Modifier.fillMaxSize(), true, 1f, AppLanguage.RU,
                listOf(country), {}, { id, mode -> selected = id to mode },
                com.noki.vpn.data.UserProfile())
        }.use {
            compose.onNodeWithText("Latvia 1").assertDoesNotExist()
            compose.onNodeWithContentDescription("Серверы: Латвия").performClick()
            compose.onNodeWithText("Latvia 1").assertIsDisplayed().performClick()
            assertEquals("lv-1" to com.noki.vpn.data.ServerSelectionMode.SERVER, selected)
            compose.onNodeWithContentDescription("Серверы: Латвия").performClick()
            compose.onNodeWithText("Latvia 1").assertDoesNotExist()
            compose.onNodeWithText("Латвия").performClick()
            assertEquals("lv" to com.noki.vpn.data.ServerSelectionMode.COUNTRY, selected)
            saveImage("server-country-collapsed")
        }
    }

    @Test
    fun serverListEnds24DpAboveHandleAtDifferentScales() {
        for (scale in listOf(1f, 1.4f)) {
            val countries = (1..10).map { index ->
                com.noki.vpn.data.ServerLocation("c$index", "LV", "Страна $index", "Riga", "example.test", isOnline = true)
            }
            render {
                HomeServerDropdownOverlay(Modifier.fillMaxSize(), true, scale, AppLanguage.RU,
                    countries, {}, { _, _ -> }, com.noki.vpn.data.UserProfile())
            }.use {
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val list = compose.onNode(androidx.compose.ui.test.hasScrollAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val density = activity.resources.displayMetrics.density
                val handleTop = root.bottom - (NokiUiKitPolicy.homeServerDropdownHandleBottomPaddingDp +
                    NokiUiKitPolicy.homeServerDropdownHandleHeightDp) * scale * density
                assertEquals("Visible list must reach the handle with a 24dp gap", 24f, (handleTop - list.bottom) / density, 1f)
                saveImage("server-list-extent-$scale")
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun yearlyPriceAndDiscountFitNarrowCard() = render {
        Box(Modifier.width(320.dp).padding(20.dp)) {
            PlanPriceLine(
                com.noki.vpn.data.PlanSummary("pro_yearly", "pro", "Pro", 6, 4000.0, "4 ТБ", 5100, 425, features = emptyList()),
                com.noki.vpn.data.BillingCycle.YEARLY, AppLanguage.RU, 1f,
            )
        }
    }.use {
        val price = compose.onNodeWithText("5100 ₽ / год").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        price.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Annual price must fit on one line", 1, layouts.single().lineCount)
        val discount = compose.onNodeWithText("-15%").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(discount.left >= price.fetchSemanticsNode().boundsInRoot.right)
        saveImage("annual-price-narrow")
    }

    @Test
    fun securityAboutSheetShowsCurrentVersionAndLinks() = render {
        SecurityAboutSheet(AppLanguage.RU, nokiAdaptiveMetrics(512.dp), {})
    }.use {
        compose.onNodeWithText("версия ${com.noki.vpn.BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("Условия использования").assertIsDisplayed()
        compose.onNodeWithText("Политика конфиденциальности").assertIsDisplayed()
        compose.onNodeWithContentDescription("Telegram").assertIsDisplayed()
        compose.onNodeWithContentDescription("Сайт Noki").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsActions.Expand,
        )).assertDoesNotExist()
        saveImage("security-about", includeDialogs = true)
    }

    @Test
    fun homeRuRouteAppearsInTheServerCardAndProtocolStaysAuto() {
        val viewModel = layoutViewModel()
        for (glass in com.noki.vpn.data.GlassMode.entries) {
            val state = russianState().copy(
                advancedSettings = com.noki.vpn.data.AdvancedSettings(ruRelayEnabled = true),
                connectionState = com.noki.vpn.data.VpnConnectionState.CONNECTED,
                personalizationSettings = russianState().personalizationSettings.copy(glassMode = glass),
                userProfile = com.noki.vpn.data.UserProfile(selectedServerCode = "exit", actualCountryCode = "NL"),
                ruForeignNodes = listOf(com.noki.vpn.data.RuForeignNode("exit", "Нидерланды1", "NL")),
            )
            render {
                HomeScreen(state, viewModel, {}, {}, rememberPrismalGlassLayer(),
                    liveGlassEnabled = false, showBackground = false, showBottomNavigation = false)
            }.use {
                compose.onNodeWithText("RU-реле → Нидерланды1").assertIsDisplayed()
                compose.onNodeWithText("Auto").assertIsDisplayed()
                compose.onNodeWithText("WireGuard · RU-реле").assertDoesNotExist()
                saveImage("home-ru-route-${glass.name.lowercase()}")
            }
        }
    }

    @Test
    fun tallHomeKeepsControlsAboveNavigation() = renderHome().use {
        val status = compose.onNodeWithText("Не подключено").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Connection controls must stay near the bottom navigation", status.center.y > root.height * 0.8f)
        saveImage("home-wide")
    }

    @Test
    fun widePersonalizationUsesAvailableWidth() = checkSettingsWidth("Персонализация", "personalization-wide") {
        PersonalizationScreen(
            state = russianState(), onLanguageChanged = {}, onGlassModeChanged = {},
            onVpnStatusNotificationChanged = {},
            onPickAvatarClicked = {}, onDeleteAvatarClicked = {}, onAvatarEditDenied = {},
            sharedBackdrop = rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false,
        )
    }

    @Test
    fun wideSupportUsesAvailableWidth() = checkSettingsWidth("Поддержка", "support-wide") {
        SupportScreen(russianState(), rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun supportOffersExactlyThreeChannelsAndOpensNativeChat() {
        var opened = false
        render {
            SupportScreen(russianState().copy(isAuthenticated = true), rememberPrismalGlassLayer(),
                liveGlassEnabled = false, showBackground = false, onOpenChat = { opened = true })
        }.use {
            compose.onNodeWithText("Онлайн-чат").assertIsDisplayed()
            compose.onNodeWithText("@NokiSupport").assertIsDisplayed()
            compose.onNodeWithText("noki_support@ykino.tech").assertIsDisplayed()
            compose.onNodeWithText("Поддержка").assertIsDisplayed()
            compose.onNodeWithText("Давайте поможем").assertDoesNotExist()
            compose.onNodeWithText("Напишите прямо здесь. История — в вашем аккаунте.").assertDoesNotExist()
            assertEquals(3, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
            val first = compose.onNodeWithText("Онлайн-чат").fetchSemanticsNode().boundsInRoot
            val last = compose.onNodeWithText("noki_support@ykino.tech").fetchSemanticsNode().boundsInRoot
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue((first.top + last.bottom) / 2f in screen.height * 0.4f..screen.height * 0.6f)
            compose.onNodeWithText("Онлайн-чат").performClick()
            assertTrue(opened)
            saveImage("support-phone")
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun supportChatShowsHistoryComposerAndRetryWithoutOperatorPresenceClaim() {
        var sent = false
        var retried = false
        val messages = listOf(
            com.noki.vpn.data.BackendSupportMessage(1, "Не удаётся подключиться", "incoming", 1790000000),
            com.noki.vpn.data.BackendSupportMessage(2, "Здравствуйте! Уточните, пожалуйста, что происходит при подключении.", "outgoing", 1790000060),
            com.noki.vpn.data.BackendSupportMessage(3, "Да", "outgoing", 1790000120),
        )
        render {
            SupportScreen(russianState().copy(isAuthenticated = true,
                supportChat = com.noki.vpn.SupportChatState(isOpen = true, hasLoaded = true,
                    messages = messages, draft = "Подключение останавливается", error = "Нет соединения с сервером")),
                rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false,
                onRefreshChat = { if (!it) retried = true }, onSendMessage = { sent = true })
        }.use {
            compose.onNodeWithText("Поддержка Noki").assertIsDisplayed()
            compose.onNodeWithText(messages.last().content).assertIsDisplayed()
            compose.onNodeWithText("Подключение останавливается").assertIsDisplayed()
            compose.onNodeWithText("Повторить").performClick()
            assertTrue(retried)
            compose.onNodeWithContentDescription("Отправить сообщение").performClick()
            assertTrue(sent)
            compose.onNodeWithText("Онлайн").assertDoesNotExist()
            compose.onNodeWithText("Команда Noki").assertDoesNotExist()
            compose.onNodeWithText("История сохранена в аккаунте").assertDoesNotExist()
            assertEquals(2, compose.onAllNodesWithText("Noki").fetchSemanticsNodes().size)
            saveImage("support-chat-phone")
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi")
    fun supportChatOpensAtLatestMessageAndCopyMenuDismisses() {
        val messages = (1L..40L).map { com.noki.vpn.data.BackendSupportMessage(it, "Message $it", "incoming", 1790000000 + it,
            status = if (it == 40L) "read" else "sent") }
        render {
            SupportScreen(russianState().copy(isAuthenticated = true,
                supportChat = com.noki.vpn.SupportChatState(isOpen = true, hasLoaded = true, messages = messages)),
                rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false)
        }.use {
            compose.onNodeWithText("Message 40").assertIsDisplayed()
            compose.onNodeWithText("Message 1").assertDoesNotExist()
            compose.onNodeWithContentDescription("Прочитано поддержкой").assertIsDisplayed()
            compose.onNodeWithText("Message 40").performTouchInput { longClick() }
            compose.onNodeWithText("Копировать").assertIsDisplayed().performClick()
            compose.onNodeWithText("Копировать").assertDoesNotExist()
            saveImage("support-chat-compact-latest")
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-420dpi", shadows = [com.noki.vpn.SupportFileProviderFixture::class])
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    fun supportChatDisplaysMediaAndFilesAndAllowsSendingWithoutCaption() {
        var picked = false
        var sent = false
        var removed = false
        var opened: Long? = null
        var saved: Long? = null
        val image = com.noki.vpn.SupportChatFiles.destination(RuntimeEnvironment.getApplication())
        val bitmap = android.graphics.Bitmap.createBitmap(180, 120, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(58, 112, 103))
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        // Test-only fetcher bypasses Android/Linux descriptor paths on the Windows Robolectric host.
        SingletonImageLoader.setUnsafe {
            ImageLoader.Builder(RuntimeEnvironment.getApplication()).components {
                add(object : Fetcher.Factory<coil3.Uri> {
                    override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
                        if (data.scheme != "content") null else object : Fetcher {
                            override suspend fun fetch(): FetchResult = ImageFetchResult(bitmap.asImage(), false, DataSource.DISK)
                        }
                })
            }.build()
        }
        val photo = com.noki.vpn.data.BackendSupportAttachment(10, 2, "image", "photo.png", image.length(), "image/png")
        val file = com.noki.vpn.data.BackendSupportAttachment(11, 3, "file", "instructions.pdf", 2048, "application/pdf")
        val messages = listOf(com.noki.vpn.data.BackendSupportMessage(2, "Проверьте настройки", "outgoing", 1790000060, listOf(photo)),
            com.noki.vpn.data.BackendSupportMessage(3, "", "incoming", 1790000120, listOf(file)))
        render {
            SupportScreen(russianState().copy(isAuthenticated = true, supportChat = com.noki.vpn.SupportChatState(isOpen = true,
                hasLoaded = true, messages = messages, attachmentFiles = mapOf(10L to image.path),
                pendingAttachments = listOf(com.noki.vpn.data.SupportAttachmentDraft(image.path, "diagnostic.txt", "text/plain", 0)))),
                rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false,
                onPickAttachment = { picked = true }, onRemoveAttachment = { removed = true }, onSendMessage = { sent = true },
                onOpenAttachment = { opened = it.id }, onSaveAttachment = { saved = it.id })
        }.use {
            compose.onNodeWithText("instructions.pdf").assertIsDisplayed()
            saveImage("support-chat-media-phone")
            compose.onNodeWithContentDescription("photo.png").assertIsDisplayed()
            compose.onNodeWithContentDescription("Сохранить instructions.pdf").performClick()
            assertEquals(11L, saved)
            compose.onAllNodesWithText("Открыть")[0].performClick()
            assertEquals(11L, opened)
            compose.onNodeWithContentDescription("Прикрепить медиа или файл").performClick(); assertTrue(picked)
            compose.onNodeWithContentDescription("Отправить сообщение").performClick(); assertTrue(sent)
            compose.onNodeWithContentDescription("Убрать вложение").performClick(); assertTrue(removed)
            saveImage("support-chat-media-phone")
        }
    }

    @Test
    fun wideSecurityUsesAvailableWidth() {
        val viewModel = layoutViewModel()
        checkSettingsWidth("Безопасность", "security-wide") {
            SecurityScreen(russianState(), viewModel, {}, rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false)
        }
    }

    @Test
    fun wideAdvancedSettingsUsesAvailableWidth() {
        val viewModel = layoutViewModel()
        checkSettingsWidth("Расширенные настройки", "advanced-wide") {
            AdvancedSettingsScreen(russianState(), viewModel, rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false)
        }
    }

    @Test
    fun ordinaryFreeHidesPaidConnectionControlsButFreePlusKeepsThem() {
        val viewModel = layoutViewModel()
        for (tier in listOf("free", "free-plus", "plus")) {
            val state = russianState().let { it.copy(userProfile = it.userProfile.copy(
                selectedPlanCodeRaw = tier, selectedPlanTier = tier,
            )) }
            render {
                AdvancedSettingsScreen(state, viewModel, rememberPrismalGlassLayer(),
                    liveGlassEnabled = false, showBackground = false)
            }.use {
                for (title in listOf("YouTube без рекламы", "MultiHop", "RU-реле", "Игровой режим")) {
                    if (tier == "free") compose.onNodeWithText(title).assertDoesNotExist()
                    else compose.onNodeWithText(title).assertExists()
                }
                compose.onNodeWithText("Автовыбор протокола").assertExists()
                compose.onNodeWithText("Kill-switch").assertExists()
                saveImage("advanced-settings-$tier")
            }
        }
    }

    @Test
    fun ruRelayModeHidesProtocolAndManualRelayControls() {
        val viewModel = layoutViewModel()
        val normal = russianState().let { it.copy(userProfile = it.userProfile.copy(selectedPlanTier = "plus")) }
        render {
            AdvancedSettingsScreen(normal, viewModel, rememberPrismalGlassLayer(),
                liveGlassEnabled = false, showBackground = false)
        }.use {
            compose.onNodeWithText("MultiHop").assertExists()
            compose.onNodeWithText("Автовыбор протокола").assertExists()
            compose.onNodeWithText("Автопуть").assertDoesNotExist()
        }
        val ru = normal.copy(advancedSettings = normal.advancedSettings.copy(ruRelayEnabled = true))
        render {
            AdvancedSettingsScreen(ru, viewModel, rememberPrismalGlassLayer(),
                liveGlassEnabled = false, showBackground = false)
        }.use {
            compose.onNodeWithText("MultiHop").assertExists()
            compose.onNodeWithText("Автовыбор протокола").assertDoesNotExist()
            compose.onNodeWithText("Автопуть").assertDoesNotExist()
            compose.onNodeWithText("Выбрать сервер реле").assertDoesNotExist()
            compose.onNodeWithText("RU-реле").assertExists()
            compose.onNodeWithText("WireGuard").assertDoesNotExist()
            compose.onNodeWithText("Подключение через российское реле по WireGuard").assertDoesNotExist()
            compose.onNodeWithText("RU-реле выбирается автоматически с учётом задержки и веса").assertDoesNotExist()
        }
    }

    @Test
    fun multiHopWithoutRuKeepsProtocolControlsVisible() {
        val viewModel = layoutViewModel()
        val normal = russianState().let { it.copy(userProfile = it.userProfile.copy(selectedPlanTier = "plus")) }
        val pair = normal.copy(advancedSettings = normal.advancedSettings.copy(
            multiHop = com.noki.vpn.data.MultiHopSettings(enabled = true),
            ruRelayEnabled = false, protocol = com.noki.vpn.data.VpnProtocol.NOKINV,
            endpointSelectionMode = com.noki.vpn.data.EndpointSelectionMode.MANUAL,
        ))
        render {
            AdvancedSettingsScreen(pair, viewModel, rememberPrismalGlassLayer(),
                liveGlassEnabled = false, showBackground = false)
        }.use {
            compose.onNodeWithText("Автовыбор протокола").assertIsDisplayed()
            compose.onNodeWithText("Сменить").assertIsDisplayed()
            saveImage("multihop-manual-protocol")
        }
    }

    @Test
    fun gamingToggleFollowsRuRelayAndRemainsReachableWhenEnabled() {
        val viewModel = layoutViewModel()
        val normal = russianState().let { it.copy(userProfile = it.userProfile.copy(selectedPlanTier = "plus")) }
        var state by androidx.compose.runtime.mutableStateOf(normal)
        render {
            AdvancedSettingsScreen(state, viewModel, rememberPrismalGlassLayer(),
                liveGlassEnabled = false, showBackground = false)
        }.use {
            compose.onNodeWithText("Автовыбор протокола").assertIsDisplayed()
            compose.onNodeWithText("Игровой режим").performScrollTo().assertIsDisplayed()
            val gaming = compose.onNodeWithText("Игровой режим").fetchSemanticsNode().boundsInRoot
            val relay = compose.onNodeWithText("RU-реле").fetchSemanticsNode().boundsInRoot
            assertTrue(gaming.top > relay.bottom)
            compose.runOnIdle { state = normal.copy(advancedSettings = normal.advancedSettings.copy(gamingModeEnabled = true)) }
            compose.onNodeWithText("Игровой режим").assertIsDisplayed()
            compose.onNodeWithText("Автовыбор протокола").assertDoesNotExist()
            compose.onNodeWithText("Сменить").assertDoesNotExist()
            compose.runOnIdle { state = normal }
            compose.onNodeWithText("Автовыбор протокола").performScrollTo().assertIsDisplayed()
        }
        renderHome(normal).use {
            compose.onNodeWithText("Игровой режим").assertDoesNotExist()
        }
    }

    @Test
    fun manualProtocolSelectionRestartsAnActiveVpnAfterPersisting() {
        val application = RuntimeEnvironment.getApplication()
        val viewModel = layoutViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(viewModel, "runtime")
        val shadow = org.robolectric.Shadows.shadowOf(application)
        while (shadow.nextStartedService != null) Unit
        val option = com.noki.vpn.data.EndpointGroupPolicy.manualOptions(listOf(
            com.noki.vpn.data.VpnEndpointOption(code = "reality-test", host = "node.example", security = "reality",
                transport = "xhttp", transportMode = "stream-up"))).single()
        for (connection in listOf(com.noki.vpn.data.VpnConnectionState.CONNECTED,
            com.noki.vpn.data.VpnConnectionState.CONNECTING, com.noki.vpn.data.VpnConnectionState.DISCONNECTED)) {
            runtime.uiState = russianState().let { it.copy(connectionState = connection,
                endpointOptions = listOf(option), endpointOptionsCountryCode = it.userProfile.selectedCountryCode,
                advancedSettings = it.advancedSettings.copy(ruRelayEnabled = false, gamingModeEnabled = false)) }
            viewModel.selectManualEndpoint(option)
            assertEquals(option.code, runtime.repository.load().advancedSettings.manualEndpointCode)
            val command = shadow.nextStartedService
            assertEquals(if (connection == com.noki.vpn.data.VpnConnectionState.DISCONNECTED) null
                else com.noki.vpn.vpn.AppVpnService.restartIntent(application).action, command?.action)
            assertEquals(null, shadow.nextStartedService)
            viewModel.changeProtocol(com.noki.vpn.data.VpnProtocol.TLS)
            assertEquals(com.noki.vpn.data.VpnProtocol.TLS, runtime.repository.load().advancedSettings.protocol)
            assertEquals(if (connection == com.noki.vpn.data.VpnConnectionState.DISCONNECTED) null
                else com.noki.vpn.vpn.AppVpnService.restartIntent(application).action, shadow.nextStartedService?.action)
            assertEquals(null, shadow.nextStartedService)
        }
    }

    @Test
    fun notificationPreferenceIsAnActionWithoutToggle() {
        render {
            PersonalizationScreen(russianState(), {}, {}, {}, {}, {}, {},
                rememberPrismalGlassLayer(), liveGlassEnabled = false, showBackground = false)
        }.use {
            val row = compose.onNode(hasText("Статус в уведомлении") and hasClickAction())
            row.performScrollTo().assertIsDisplayed().performClick()
            val intent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
            compose.onNodeWithContentDescription("Системные настройки").assertExists()
            saveImage("personalization-notification-action")
        }
    }

    private fun russianState() = com.noki.vpn.SettingsPreparedStatePolicy.withPreparedSettingsState(
        AppUiState(personalizationSettings = PersonalizationSettings(language = AppLanguage.RU)),
    )

    @Test
    fun multiHopSharedControlsSaveDistinctNodesWithRuWithoutConfirmation() = saveMultiHopPair(ruRelayEnabled = true)

    @Test
    fun multiHopSharedControlsSaveDistinctNodesWithoutEnablingRu() = saveMultiHopPair(ruRelayEnabled = false)

    private fun saveMultiHopPair(ruRelayEnabled: Boolean) {
        val viewModel = layoutViewModel()
        viewModel.setMultiHop(com.noki.vpn.data.MultiHopSettings())
        viewModel.setRuRelayEnabled(ruRelayEnabled)
        val normal = russianState()
        val entryNodeId = "11111111-1111-4111-8111-111111111111"
        val exitNodeId = "22222222-2222-4222-8222-222222222222"
        val state = normal.copy(
            advancedSettings = normal.advancedSettings.copy(ruRelayEnabled = ruRelayEnabled),
            locations = listOf(com.noki.vpn.data.ServerLocation("lv", "LV", "Латвия", "", "", isOnline = true,
                servers = listOf(entryNodeId to "Нода a", exitNodeId to "Нода b",
                    "33333333-3333-4333-8333-333333333333" to "Нода c").map { (id, name) ->
                    com.noki.vpn.data.VpnServer(id, name, if (name == "Нода c") "AT" else "LV", "lv", "", null, true,
                        latencyMs = if (name == "Нода a") 25 else null,
                        multiHopEntryAvailable = true, multiHopExitAvailable = true)
                })),
            ruForeignNodes = listOf(
                com.noki.vpn.data.RuForeignNode(entryNodeId, "Нода a", "LV", latencyMs = 25),
                com.noki.vpn.data.RuForeignNode(exitNodeId, "Нода b", "LV"),
                com.noki.vpn.data.RuForeignNode("33333333-3333-4333-8333-333333333333", "Нода c", "AT"),
            ),
        )
        render {
            MultiHopScreen(state, viewModel, rememberPrismalGlassLayer(),
                liveGlassEnabled = false, showBackground = false)
        }.use {
            compose.onNodeWithText("Входной сервер").assertDoesNotExist()
            compose.onNode(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Switch,
            )).performClick()
            compose.onNodeWithText("Нода a").assertDoesNotExist()
            compose.onNodeWithContentDescription("Входной сервер: Выбрать").assertExists()
            compose.onNodeWithContentDescription("Выходной сервер: Выбрать").assertExists()
            compose.onNodeWithText("Сохранить").performScrollTo().performClick()
            compose.onNodeWithText("Выберите вход").assertExists()
            compose.runOnIdle { assertFalse(viewModel.uiState.advancedSettings.multiHop.enabled) }
            compose.onNodeWithContentDescription("Входной сервер: Выбрать").performScrollTo().performClick()
            compose.onNodeWithContentDescription("MultiHop server picker: Входной сервер").assertIsDisplayed()
            compose.onNodeWithText("25 мс").assertIsDisplayed()
            compose.onNodeWithText("Латвия").assertIsDisplayed()
            compose.onNodeWithText("Нода a").assertDoesNotExist()
            compose.onNodeWithText("Нода c").assertDoesNotExist()
            assertTrue("Countries are grouped before node selection", compose.onNodeWithText("Австрия")
                .fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("Латвия")
                .fetchSemanticsNode().boundsInRoot.top)
            saveImage("multihop-server-list-${if (ruRelayEnabled) "ru" else "no-ru"}", includeDialogs = true)
            compose.onNodeWithContentDescription("Закрыть выбор сервера").performClick()
            compose.onNodeWithContentDescription("MultiHop server picker: Входной сервер").assertDoesNotExist()
            compose.onNodeWithText("Нода a").assertDoesNotExist()
            compose.onNodeWithContentDescription("Входной сервер: Выбрать").performClick()
            compose.onNodeWithContentDescription("Серверы: Латвия").performClick()
            compose.onNodeWithText("Нода a").performClick()
            compose.onNodeWithContentDescription("MultiHop server picker: Входной сервер").assertDoesNotExist()
            compose.onNodeWithContentDescription("Выходной сервер: Выбрать").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Серверы: Латвия").performClick()
            assertEquals(1, compose.onAllNodesWithText("Нода a").fetchSemanticsNodes().size)
            compose.onNodeWithText("Нода b").performClick()
            compose.onNodeWithContentDescription("Входной сервер: Нода a").assertExists()
            compose.onNodeWithContentDescription("Выходной сервер: Нода b").assertExists()
            saveImage("multihop-simple-${if (ruRelayEnabled) "ru" else "no-ru"}")
            compose.onNodeWithText("Сохранить").performScrollTo().performClick()
            compose.onNodeWithText("Выбрать ручной MultiHop?").assertDoesNotExist()
            compose.onNodeWithText("RU-реле будет выключено.").assertDoesNotExist()
            compose.runOnIdle {
                val persisted = SettingsRepository(RuntimeEnvironment.getApplication()).load()
                assertEquals(ruRelayEnabled, persisted.advancedSettings.ruRelayEnabled)
                assertTrue(persisted.advancedSettings.multiHop.enabled)
                assertEquals(entryNodeId, persisted.advancedSettings.multiHop.entry?.nodeId)
                assertEquals(exitNodeId, persisted.advancedSettings.multiHop.exit?.nodeId)
            }
        }
    }

    @Test
    fun emailLoginFieldsScaleWithTheirWidth() {
        val viewModel = layoutViewModel()
        render {
            LoginScreen(russianState().copy(authStep = AuthStep.EMAIL_LOGIN), viewModel, false, {}, {}, {}, {})
        }.use {
            val button = compose.onNode(hasText("Войти") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Login button must keep its design aspect ratio", button.height / button.width > 0.145f)
            saveImage("login-wide")
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h800dp-xxhdpi")
    fun loginMovesFieldsAndActionsTogetherAboveKeyboard() {
        val viewModel = layoutViewModel()
        render {
            LoginScreen(russianState().copy(authStep = AuthStep.EMAIL_LOGIN), viewModel, false, {}, {}, {}, {})
        }.use {
            val email = compose.onNode(hasSetTextAction() and hasText("E-mail", substring = true))
            val action = compose.onNode(hasText("Войти") and hasClickAction())
            val emailBefore = email.fetchSemanticsNode().boundsInRoot
            val actionBefore = action.fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val imeHeight = 900
            fun dispatchIme(bottom: Int) = compose.runOnIdle {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(
                    activity.window.decorView,
                    androidx.core.view.WindowInsetsCompat.Builder()
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, bottom))
                        .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), bottom > 0)
                        .build(),
                )
            }
            dispatchIme(imeHeight)
            compose.waitForIdle()
            val emailAfter = email.fetchSemanticsNode().boundsInRoot
            val actionAfter = action.fetchSemanticsNode().boundsInRoot
            assertTrue("The whole form must rise", emailAfter.top < emailBefore.top)
            assertEquals(emailBefore.top - emailAfter.top, actionBefore.top - actionAfter.top, 1f)
            assertTrue("Login action must clear the keyboard", actionAfter.bottom < root.bottom - imeHeight)
            saveImage("login-keyboard")
            dispatchIme(0)
            compose.waitForIdle()
            assertEquals(emailBefore.top, email.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
    }

    @Test
    fun settingsRowsScaleWithTheirWidth() {
        val viewModel = layoutViewModel()
        render {
            SettingsScreen(russianState(), viewModel, rememberPrismalGlassLayer(), liveGlassEnabled = false,
                showBackground = false, showBottomNavigation = false)
        }.use {
            val row = compose.onNode(hasText("Устройства") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Settings row must keep its design aspect ratio", row.height / row.width > 0.21f)
            saveImage("settings-wide")
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun narrowSettingsRetainOriginalMenuScale() {
        val viewModel = layoutViewModel()
        render {
            SettingsScreen(russianState(), viewModel, rememberPrismalGlassLayer(), liveGlassEnabled = false,
                showBackground = false, showBottomNavigation = false)
        }.use {
            val row = compose.onNode(hasText("Устройства") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertEquals(80f * activity.resources.displayMetrics.density, row.height, 1f)
            val textLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Устройства", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            assertEquals(16f, textLayouts.single().layoutInput.style.fontSize.value, 0.01f)
        }
    }

    @Test fun registrationEmailScales() = checkRegistration(AuthStep.REGISTRATION_EMAIL, "Получить код", "registration-email")
    @Test fun registrationCodeScales() = checkRegistration(AuthStep.REGISTRATION_CODE, "Продолжить", "registration-code")
    @Test fun registrationProfileScales() = checkRegistration(AuthStep.REGISTRATION_PROFILE, "Продолжить", "registration-profile")
    @Test fun registrationPasswordScales() = checkRegistration(AuthStep.REGISTRATION_PASSWORD, "Создать аккаунт", "registration-password")

    private fun checkRegistration(step: AuthStep, button: String, image: String) {
        val viewModel = layoutViewModel()
        render { RegistrationScreen(russianState().copy(authStep = step), viewModel, false) }.use {
            checkActionAspect(button)
            saveImage(image)
        }
    }

    @Test fun inviteCodeScales() {
        val viewModel = layoutViewModel()
        render { InviteDeviceScreen(russianState(), viewModel, false) }.use {
            checkActionAspect("Подключить")
            saveImage("invite-code-wide")
        }
    }

    @Test fun changeEmailScales() = checkCredential(com.noki.vpn.AccountSecurityActionState.Email(codeSent = true), "Сменить e-mail", "change-email-wide")
    @Test fun changePasswordScales() = checkCredential(com.noki.vpn.AccountSecurityActionState.Password(), "Задать пароль", "change-password-wide")

    private fun checkCredential(action: com.noki.vpn.AccountSecurityActionState, button: String, image: String) {
        val viewModel = layoutViewModel()
        val state = russianState().copy(accountSecurityState = com.noki.vpn.AccountSecurityUiState(action))
        render { AccountCredentialChangeScreen(state, viewModel, false) }.use {
            checkActionAspect(button)
            saveImage(image)
        }
    }

    private fun checkActionAspect(text: String) {
        val bounds = compose.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Action must scale with its width", bounds.height / bounds.width > 0.145f)
        compose.onNode(hasText(text) and hasClickAction()).assertIsDisplayed()
    }

    @Test fun applicationsScale() {
        val viewModel = layoutViewModel()
        render { AppFilterScreen(russianState(), viewModel, rememberPrismalGlassLayer(), false, false) }.use {
            checkActionAspect("Применить")
            saveImage("apps-wide")
        }
    }

    @Test fun alwaysOnRulesScale() = checkRules(SiteRulesMode.ALWAYS_ON, "rules-always-wide")
    @Test fun bypassRulesScale() = checkRules(SiteRulesMode.BYPASS, "rules-bypass-wide")
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun russianResourcesRuleFitsAndShowsOnState() {
        val viewModel = layoutViewModel()
        val state = russianState().let { current ->
            current.copy(advancedSettings = current.advancedSettings.copy(
                bypassDomains = listOf(com.noki.vpn.data.DomainRulePolicy.RUSSIAN_RESOURCES_RULE),
            ))
        }
        render { SiteRulesScreen(state, viewModel, SiteRulesMode.BYPASS,
            liveGlassEnabled = false, showBackground = false) }.use {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Российские ресурсы", useUnmergedTree = true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Russian resources label must fit", layouts.single().isLineEllipsized(0))
            compose.onNodeWithText("on").assertIsDisplayed()
            compose.onNodeWithText("Изм.").assertDoesNotExist()
            saveImage("rules-bypass-on-narrow")
        }
    }
    private fun checkRules(mode: SiteRulesMode, image: String) {
        val viewModel = layoutViewModel()
        render { SiteRulesScreen(russianState(), viewModel, mode, liveGlassEnabled = false, showBackground = false) }.use {
            val action = compose.onNode(hasText("Сохранить") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Rule editor button must scale", action.height > 140f)
            saveImage(image)
        }
    }

    @Test fun statisticsScale() = render { StatsScreen(russianState(), false, false) }.use {
        val header = compose.onNodeWithText("Статистика").fetchSemanticsNode().boundsInRoot
        assertTrue("Statistics title must match the shared scale", header.height > 95f)
        val day = compose.onNodeWithText("Пн").fetchSemanticsNode().boundsInRoot
        val metric = compose.onNodeWithText("Онлайн").fetchSemanticsNode().boundsInRoot
        assertTrue("Chart must follow metrics without an expanding spacer: ${day.top - metric.bottom}px", day.top - metric.bottom < 700f)
        saveImage("stats-wide")
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortPasswordChangeCanScrollToAction() {
        val state = russianState().copy(accountSecurityState = com.noki.vpn.AccountSecurityUiState(
            com.noki.vpn.AccountSecurityActionState.Password()))
        val viewModel = layoutViewModel()
        render { AccountCredentialChangeScreen(state, viewModel, false) }.use {
            compose.onNodeWithText("Задать пароль").performScrollTo().assertIsDisplayed()
            saveImage("change-password-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortRecoveryCanScrollToAction() {
        val state = russianState().let { it.copy(passwordRecoveryForm = it.passwordRecoveryForm.copy(passwordStepVisible = true)) }
        val viewModel = layoutViewModel()
        render { PasswordRecoveryScreen(state, viewModel, false) }.use {
            compose.onNodeWithText("Изменить пароль").performScrollTo().assertIsDisplayed()
            saveImage("recovery-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortRegistrationCanScrollToBack() {
        val viewModel = layoutViewModel()
        render { RegistrationScreen(russianState().copy(authStep = AuthStep.REGISTRATION_PASSWORD), viewModel, false) }.use {
            compose.onNodeWithText("Назад").performScrollTo().assertIsDisplayed()
            saveImage("registration-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h500dp-xxhdpi")
    fun shortLoginCanScrollToCodeLogin() {
        val viewModel = layoutViewModel()
        render { LoginScreen(russianState().copy(authStep = AuthStep.EMAIL_LOGIN), viewModel, false, {}, {}, {}, {}) }.use {
            compose.onNodeWithText("Войти по коду").performScrollTo().assertIsDisplayed()
            saveImage("login-short")
        }
    }

    @Test
    @Config(qualifiers = "w512dp-h800dp-xxhdpi")
    fun shortStatisticsKeepsChartVisible() = render { StatsScreen(russianState(), false, false) }.use {
        compose.onNodeWithText("Пн").performScrollTo().assertIsDisplayed()
        val day = compose.onNodeWithText("Пн").fetchSemanticsNode().boundsInRoot
        val metric = compose.onNodeWithText("Онлайн").fetchSemanticsNode().boundsInRoot
        assertTrue("Chart needs its full height below the metric grid", day.top - metric.bottom > 200f)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Chart must remain above shared navigation", day.bottom < root.bottom - 300f)
        saveImage("stats-short")
    }

    @Test fun avatarCropScalesAndKeepsRotationWorking() {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "layout-avatar.png")
        val bitmap = android.graphics.Bitmap.createBitmap(300, 400, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(122, 231, 199))
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var quarterTurns = -1
        render {
            AvatarCropScreen(file.toURI().toString(), AppLanguage.RU, false, null, {},
                { _, _, _, _, _, _, rotation -> quarterTurns = rotation })
        }.use {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Загрузка...")).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Повернуть вправо на 90°").performClick()
            val action = compose.onNode(hasText("Сохранить") and hasClickAction())
            assertTrue("Crop action must scale", action.fetchSemanticsNode().boundsInRoot.height > 150f)
            saveImage("avatar-crop-wide")
            action.performClick()
            assertEquals(1, quarterTurns)
        }
        file.delete()
    }

    @Test fun checkoutPaymentMethodsAreExclusive() {
        var appliedPromo = ""
        val plan = com.noki.vpn.data.PlanSummary("pro_yearly", "pro", "Pro", 6, 4000.0, "4 ТБ", 5100, 425,
            features = listOf("4 ТБ", "6 устройств", "Скорость до 200 Мбит/с", "Приватный DNS", "Блокировка рекламы"))
        render {
            var payment by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.noki.vpn.PaymentCheckoutState(
                config = com.noki.vpn.data.BackendPaymentConfig(true, true, listOf(
                    com.noki.vpn.data.BackendPaymentMethod(null, "card", "Карта", true),
                    com.noki.vpn.data.BackendPaymentMethod(2, "sbp", "СБП", true),
                    com.noki.vpn.data.BackendPaymentMethod(13, "crypto", "Криптовалюта", true))), methodCode = "card")) }
            PlanCheckoutScreen(plan, "Free", false, com.noki.vpn.data.BillingCycle.YEARLY, AppLanguage.RU, {}, null, false,
                470f / 370f, Modifier.padding(horizontal = 21.dp).width(470.dp).verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 24.dp),
                payment, { appliedPromo = it }, {}, { payment = payment.copy(methodCode = it) }, {}, {}, {}, {})
        }.use {
            val heading = compose.onNodeWithText("Текущий тариф").fetchSemanticsNode().boundsInRoot
            assertTrue("Plan transition must be at the top", heading.top < 320f)
            saveImage("checkout-top-wide")
            compose.onNodeWithText("5100 ₽").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("-15%").assertIsDisplayed()
            compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("NOKI20")
            val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
            val apply = compose.onNodeWithText("Применить").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Promo action stays inside the input row", input.center.y, apply.center.y, 4f)
            compose.onNodeWithText("Применить").performClick()
            compose.waitUntil(2000) { appliedPromo == "NOKI20" }
            saveImage("checkout-promo-wide")
            compose.onNodeWithText("Карта").performScrollTo().assertIsSelected()
            compose.onNodeWithText("СБП").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("Карта").assertIsNotSelected()
            compose.onNodeWithText("Криптовалюта").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithText("СБП").assertIsNotSelected()
            compose.onNodeWithText("Оплатить", substring = true).performScrollTo().assertIsDisplayed()
            saveImage("checkout-methods-wide")
        }
    }

    private fun checkSettingsWidth(title: String, imageName: String, content: @Composable () -> Unit) = render(content).use {
        val header = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Settings header must match the wide content column", header.width > root.width * 0.8f)
        saveImage(imageName)
    }

    @Test
    @Config(qualifiers = "w512dp-h800dp-xxhdpi")
    fun wideShortHomeKeepsConnectionControlsReachable() = checkShortHome("home-short-512")

    @Test
    @Config(qualifiers = "w480dp-h800dp-xxhdpi")
    fun mediumShortHomeKeepsConnectionControlsReachable() = checkShortHome("home-short-480")

    private fun checkShortHome(imageName: String) = renderHome().use {
        compose.onNodeWithText("Не подключено").performScrollTo().assertIsDisplayed()
        saveImage(imageName)
    }

    private fun renderHome(state: AppUiState = AppUiState(
        personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
    )): AutoCloseable {
        val viewModel = layoutViewModel()
        return render {
            HomeScreen(
                state = state,
                viewModel = viewModel, onConnectClicked = {}, onDisconnectClicked = {},
                sharedBackdrop = rememberPrismalGlassLayer(), liveGlassEnabled = false,
                showBackground = false,
                // The shared navigation's RuntimeShader requires hardware rendering.
                showBottomNavigation = false,
            )
        }
    }

    @Test
    fun currentDeviceUsesAccessiblePencil() {
        val viewModel = layoutViewModel()
        render {
            DevicesScreen(
                state = AppUiState(
                    personalizationSettings = PersonalizationSettings(language = AppLanguage.RU),
                    devices = listOf(
                        DeviceSession("current", "Google Pixel 6", "Android", true, true),
                        DeviceSession("fixture", "Noki — тестовое устройство", "Android", false, false),
                    ),
                ),
                viewModel = viewModel, sharedBackdrop = rememberPrismalGlassLayer(),
                showBackground = false, liveGlassEnabled = false,
            )
        }.use {
            compose.onNodeWithContentDescription("Переименовать").assertIsDisplayed()
            compose.onNodeWithText("Переименовать").assertDoesNotExist()
            val heading = compose.onNodeWithText("Текущее устройство").fetchSemanticsNode().boundsInRoot
            val card = compose.onNode(hasText("Google Pixel 6") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Current-device heading must be outside and above the card", heading.bottom < card.top)
            compose.onNodeWithText("Основные устройства").assertDoesNotExist()
            compose.onNodeWithText("В доп. устройства").assertDoesNotExist()
            assertEquals(1, compose.onAllNodes(hasText("Noki устройства")).fetchSemanticsNodes().size)
            compose.onNodeWithText("Доп. устройства").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Удалить все устройства кроме текущего").performScrollTo().assertIsDisplayed()
            saveImage("devices-wide")
        }
    }

    @Test
    fun currentDeviceModalOffersPoolTransfer() {
        val viewModel = layoutViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(viewModel, "runtime")
        runtime.uiState = russianState().copy(
            devices = listOf(DeviceSession("current", "Phone", "Android", true, true)),
            dialog = com.noki.vpn.AppDialog.RenameDevice("current"),
        )
        render {
            DevicesScreen(
                state = viewModel.uiState,
                viewModel = viewModel, sharedBackdrop = rememberPrismalGlassLayer(),
                showBackground = false, liveGlassEnabled = false,
            )
        }.use {
            compose.onNode(hasText("В доп. устройства") and hasClickAction()).assertIsDisplayed().performClick()
            compose.onNodeWithText("Перенести устройство?").assertIsDisplayed()
            assertEquals(com.noki.vpn.AppDialog.MoveDevice("current", "shared"), viewModel.uiState.dialog)
            assertEquals("primary", viewModel.uiState.devices.single().trafficPool)
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithText("Сохранить").assertIsDisplayed()
            assertEquals(com.noki.vpn.AppDialog.RenameDevice("current"), viewModel.uiState.dialog)
            compose.onNodeWithText("В доп. устройства").performClick()
            compose.onNodeWithText("Перенести").performClick()
            val failed = viewModel.uiState.dialog as com.noki.vpn.AppDialog.MoveDevice
            assertFalse(failed.isLoading)
            assertTrue(!failed.error.isNullOrBlank())
            compose.onNodeWithText(failed.error!!).assertIsDisplayed()
            assertEquals("primary", viewModel.uiState.devices.single().trafficPool)
            saveImage("current-device-modal")
        }
    }

    @Test
    fun incyMoveUsesConfirmationAndCancelRestoresItsManagementDialog() {
        val viewModel = layoutViewModel()
        val runtime = org.robolectric.util.ReflectionHelpers.getField<com.noki.vpn.AppUiRuntime>(viewModel, "runtime")
        runtime.uiState = russianState().let { it.copy(incyDevices = it.incyDevices.copy(
            devices = listOf(com.noki.vpn.data.BackendIncyDevice("incy", "Laptop", trafficPool = "shared")),
            selectedDeviceId = "incy", nameInput = "Laptop", isManageDialogVisible = true,
        )) }
        render {
            DevicesScreen(viewModel.uiState, viewModel, sharedBackdrop = rememberPrismalGlassLayer(),
                showBackground = false, liveGlassEnabled = false)
        }.use {
            compose.onNodeWithText("В основные устройства").performClick()
            compose.onNodeWithText("Перенести устройство?").assertIsDisplayed()
            assertEquals(com.noki.vpn.AppDialog.MoveDevice("incy", "primary", isIncy = true), viewModel.uiState.dialog)
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithText("В основные устройства").assertIsDisplayed()
            assertEquals(null, viewModel.uiState.dialog)
            assertEquals("shared", viewModel.uiState.incyDevices.devices.single().trafficPool)
        }
    }

    @Test
    fun moveConfirmationDisablesBothActionsDuringRequest() {
        render {
            DevicesConfirmDialog("Перенести устройство?", "Phone", "Перенос…", "Отмена", null, false, 1f,
                {}, {}, isLoading = true)
        }.use {
            compose.onNodeWithText("Перенос…").assertIsNotEnabled()
            compose.onNodeWithText("Отмена").assertIsNotEnabled()
        }
    }

    @Test
    fun additionalDevicesShowSharedTrafficAndNoCreationActions() {
        val viewModel = layoutViewModel()
        val state = russianState().let { it.copy(
            devices = listOf(
                DeviceSession("current", "Shared phone", "Android", true, true, trafficPool = "shared"),
                DeviceSession("primary", "Primary phone", "Android", false, true),
            ),
            incyDevices = it.incyDevices.copy(devices = listOf(
                com.noki.vpn.data.BackendIncyDevice("shared-incy", "Shared INCY", trafficPool = "shared"),
            )),
            userProfile = it.userProfile.copy(sharedTrafficUsedGb = 1.0, sharedTrafficLimitGb = 2.0,
                sharedPeriodEnd = "2026-11-01T00:00:00+03:00", sharedFreeOnly = true),
        ) }
        render {
            DevicesScreen(state, viewModel, showAdditional = true,
                sharedBackdrop = rememberPrismalGlassLayer(), showBackground = false, liveGlassEnabled = false)
        }.use {
            compose.onNodeWithText("Устройств: 2").assertIsDisplayed()
            compose.onNodeWithText("Общий трафик").assertIsDisplayed()
            compose.onNodeWithText("1 ГБ / 2 ГБ").assertIsDisplayed()
            compose.onNodeWithText("Квота обновится", substring = true).assertDoesNotExist()
            compose.onNodeWithText("На все доп. устройства", substring = true).assertDoesNotExist()
            compose.onNodeWithText("До обновления квоты", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Noki устройства").assertIsDisplayed()
            compose.onNodeWithText("Primary phone").assertDoesNotExist()
            compose.onNodeWithText("Shared phone").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Shared INCY").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Добавить устройство").assertDoesNotExist()
            compose.onNodeWithText("Создать код для подключения устройства").assertDoesNotExist()
            saveImage("additional-devices")
        }
    }

    private fun layoutViewModel() = MainViewModel(RuntimeEnvironment.getApplication(), SavedStateHandle()).also {
        // Layout uses the supplied state; keep account bootstrap/network work out of this UI fixture.
        it.viewModelScope.cancel()
    }

    @Test
    fun automaticServerCardShowsDestinationOnlyWithConnection() {
        for (ruRelay in listOf(false, true)) {
            val base = russianState().let { it.copy(
                advancedSettings = it.advancedSettings.copy(ruRelayEnabled = ruRelay),
                locations = listOf(com.noki.vpn.data.ServerLocation("lv", "LV", "Латвия", "Riga", "lv.example", isOnline = true)),
                ruForeignNodes = listOf(com.noki.vpn.data.RuForeignNode("lv2", "Латвия 2", "LV")),
                userProfile = it.userProfile.copy(serverSelectionMode = ServerSelectionMode.AUTO,
                    actualCountryCode = "LV", selectedServerCode = if (ruRelay) "lv2" else "lv"),
            ) }
            for (connected in listOf(false, true)) {
                val state = base.copy(connectionState = if (connected) com.noki.vpn.data.VpnConnectionState.CONNECTED
                    else com.noki.vpn.data.VpnConnectionState.DISCONNECTED)
                renderHome(state).use {
                    compose.onNodeWithText("Автовыбор").assertIsDisplayed()
                    if (connected) {
                        compose.onNodeWithText(if (ruRelay) "RU-реле → Латвия 2" else "Латвия").assertIsDisplayed()
                    } else {
                        compose.onNodeWithText("Латвия", substring = true).assertDoesNotExist()
                    }
                    saveImage("automatic-server-card-$ruRelay-$connected")
                }
            }
        }
    }

    @Test
    fun tallProfileStartsAtTop() = render {
        AccountScreen(
            state = AppUiState(personalizationSettings = PersonalizationSettings(language = AppLanguage.RU)),
            sharedBackdrop = null, liveGlassEnabled = false,
            onPersonalizationClicked = {}, onPlansClicked = {}, onDevicesClicked = {}, onSupportClicked = {},
            onSecurityClicked = {}, onNotificationsClicked = {}, onNotificationDeleted = {},
            onPaymentHistoryClicked = {}, onApplyPromoCode = {}, onClearPromoCode = {},
            onDeleteAccountClicked = {}, onAccessDenied = {}, onDismissDialog = {}, onConfirmDialog = {},
        )
    }.use {
        val header = compose.onNodeWithText("Профиль").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Profile must begin in the top tenth of the screen", header.top < root.height / 10)
        compose.onNodeWithText("Поддержка").performScrollTo()
        val devices = compose.onNodeWithText("Устройства").fetchSemanticsNode().boundsInRoot
        val support = compose.onNodeWithText("Поддержка").fetchSemanticsNode().boundsInRoot
        assertTrue("Devices must precede support", devices.bottom < support.top)
        saveImage("profile-wide")
    }

    @Test
    fun deviceActionsHaveEqualBoundsAndRenameRemainsClickable() {
        var renamed = false
        var moved = false
        render {
            DeleteDeviceDialog(
                device = DeviceSession("fixture", "Noki — тестовое устройство", "Android", false, false),
                language = AppLanguage.RU, scale = 470f / 370f,
                backdrop = null, liveGlassEnabled = false,
                onDismiss = {}, onFullAccessChanged = { _, _ -> }, onMove = { moved = true },
                onRename = { renamed = true }, onConfirm = {},
            )
        }.use {
            val rename = compose.onNode(hasText("Переименовать") and hasClickAction())
            val delete = compose.onNode(hasText("Удалить устройство") and hasClickAction())
            val renameBounds = rename.fetchSemanticsNode().boundsInRoot
            val deleteBounds = delete.fetchSemanticsNode().boundsInRoot
            assertEquals(deleteBounds.width, renameBounds.width, 1f)
            assertEquals(deleteBounds.height, renameBounds.height, 1f)
            assertEquals(deleteBounds.left, renameBounds.left, 1f)
            assertTrue("Device actions need a distinct gap", deleteBounds.top - renameBounds.bottom >= renameBounds.height * 0.30f)
            saveImage("device-dialog-wide")
            compose.onNode(hasText("В доп. устройства") and hasClickAction()).performClick()
            assertTrue(moved)
            rename.performClick()
            assertTrue(renamed)
        }
    }

    private fun render(content: @Composable () -> Unit): AutoCloseable {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity = controller.get()
        activity.setContent {
            NokiTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF051018))) { content() }
            }
        }
        compose.waitForIdle()
        return AutoCloseable { controller.pause().stop().destroy() }
    }

    private fun saveImage(name: String, includeDialogs: Boolean = false) {
        val directory = File("build-compose-2026/reports/ui-layout").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            compose.runOnIdle {
                val view = if (includeDialogs) {
                    org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(
                        activity.windowManager,
                    ).views.last()
                } else activity.window.decorView
                val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }
        }
    }
}
