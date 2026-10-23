package com.rtbishop.look4sat.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.domain.repository.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="en-w411dp-h891dp-port")
class RadioProfilesDialogTest {
    @get:Rule val compose=createComposeRule()
    private val caps=RemoteRadioCapabilities(10,92,10,45,1500,18000000,1300000000,15,9,
        true,true,true,true,false,false,transactionalProfileStore=true)

    @Test fun connectionAndReconnectRefreshTheOpenDialogWithoutReopening() {
        val radio=mutableStateOf(RadioTrackingState())
        val actions=mutableListOf<SettingsAction>()
        compose.setContent { MaterialTheme {
            RadioProfilesDialog(RadioProfileManagerState(),radio.value,null,actions::add,{})
        } }
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(actions.isEmpty())
            radio.value=RadioTrackingState(remoteRadio=RemoteRadioState(connected=true,session=1,capabilities=caps))
        }
        compose.onNodeWithText("Refresh").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(listOf(SettingsAction.RefreshRadioProfiles),actions)
            radio.value=radio.value.copy(remoteRadio=radio.value.remoteRadio!!.copy(session=2))
        }
        compose.runOnIdle { assertEquals(2,actions.count {it==SettingsAction.RefreshRadioProfiles}) }
    }

    @Test fun disconnectionDoesNotHideTheActualRefreshFailure() {
        val error="Radio disconnected; operation cancelled"
        compose.setContent { MaterialTheme {
            RadioProfilesDialog(RadioProfileManagerState(error=error),RadioTrackingState(),null,{},{})
        } }
        compose.onNodeWithText(error).assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
    }

    @Test fun connectionWithoutProfileCapabilityIsNotMisrepresentedAsSupported() {
        val actions=mutableListOf<SettingsAction>()
        compose.setContent { MaterialTheme {
            RadioProfilesDialog(RadioProfileManagerState(),RadioTrackingState(remoteRadio=
                RemoteRadioState(connected=true,capabilities=caps.copy(transactionalProfileStore=false))),
                null,actions::add,{})
        } }
        compose.onNodeWithText("Refresh").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }
}
