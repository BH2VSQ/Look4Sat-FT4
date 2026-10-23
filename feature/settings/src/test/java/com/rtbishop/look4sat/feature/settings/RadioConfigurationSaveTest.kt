package com.rtbishop.look4sat.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.presentation.MainTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioConfigurationSaveTest {
    @get:Rule val compose = createComposeRule()
    private val saved = mutableListOf<RadioControlSettings>()
    private var dismissed = false
    private val initial = RadioControlSettings(false, RadioControlSettings.MODEL_ICOM_IC820, "", "", "", "", 1200)
    private fun show(settings: RadioControlSettings) {
        compose.setContent { MainTheme { RadioControlDialog(settings, { dismissed = true }, saved::add) } }
    }

    @Test fun badTcpConfigurationOffersModifyOrIgnoreAndSaveInsteadOfSilentlyRejectingAccept() {
        show(initial.copy(enabled = true, radioModel = RadioControlSettings.MODEL_ICOM_IC820,
            catTransport = "TCP", txRadioAddress = "bad-host", civAddress = 0x42))
        compose.onNodeWithText("Accept").assertIsEnabled().performClick()
        compose.onNodeWithText("Radio configuration error").assertIsDisplayed()
        compose.onNodeWithText("Modify").performClick()
        compose.runOnIdle { assertTrue(saved.isEmpty()); assertFalse(dismissed) }
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithText("Ignore and save").performClick()
        compose.runOnIdle { assertEquals("bad-host", saved.single().txRadioAddress); assertTrue(dismissed) }
    }

    @Test fun disabledCatSavesDespiteMissingUsbDevice() {
        show(initial.copy(enabled = false, radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1,
            catTransport = "USB"))
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle { assertFalse(saved.single().enabled); assertTrue(dismissed) }
    }

    @Test fun ic820AndLegacyYaesuExposeSingleRadioModeWithoutClaimingAutomaticSplitCapability() {
        show(initial.copy(enabled = true, radioModel = RadioControlSettings.MODEL_ICOM_IC820,
            catTransport = "USB", splitMode = true))
        compose.onNodeWithText("Single-radio control").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Preset the radio’s TX/RX mode on the radio. Software does not enable split or satellite mode.")
            .assertIsDisplayed()
        compose.onNodeWithText("Satellite profiles").assertDoesNotExist()
    }
}
