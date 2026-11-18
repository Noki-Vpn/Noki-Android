package com.noki.vpn.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.noki.vpn.AdvancedSettingsStateReducer
import com.noki.vpn.AppUiState
import com.noki.vpn.data.AppLanguage
import com.noki.vpn.data.DefaultStoredSettingsFactory
import com.noki.vpn.data.DomainRulePolicy
import com.noki.vpn.data.StoredSettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteRulesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun restoredPresetButtonSavesOnOffOn() {
        val codec = StoredSettingsCodec { DefaultStoredSettingsFactory.create() }
        var raw = codec.encode(DefaultStoredSettingsFactory.create())
        var stored by mutableStateOf(codec.decode(raw))
        val preset = DomainRulePolicy.RUSSIAN_RESOURCES_RULE
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            val enabled = preset in stored.advancedSettings.bypassDomains
            NokiTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFF07111A)).padding(21.dp)) {
                    SiteRuleRow(preset, AppLanguage.RU, null, false, enabled, {}, {
                        val state = AppUiState(advancedSettings = stored.advancedSettings)
                        val next = if (enabled) AdvancedSettingsStateReducer.removeBypassDomain(state, preset)
                            else AdvancedSettingsStateReducer.addBypassDomain(state, preset)
                        raw = codec.encode(stored.copy(advancedSettings = next.advancedSettings))
                        stored = codec.decode(raw)
                    })
                }
            }
        }
        compose.onNodeWithText("on").performClick()
        compose.onNodeWithText("off").assertIsDisplayed()
        assertFalse(preset in codec.decode(raw).advancedSettings.bypassDomains)
        compose.runOnIdle { stored = codec.decode(raw) }
        compose.onNodeWithText("off").assertIsDisplayed()
        compose.onNodeWithText("off").performClick()
        compose.onNodeWithText("on").assertIsDisplayed()
        assertEquals(listOf(preset), codec.decode(raw).advancedSettings.bypassDomains)
        controller.pause().stop().destroy()
    }
}
