package com.rtbishop.look4sat.feature.logbook

import android.Manifest
import android.app.Application
import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="en-w411dp-h891dp-port")
class BackgroundSyncStartTest {
    @get:Rule val compose=createComposeRule()
    private val context=ApplicationProvider.getApplicationContext<Application>()
    private val actions=mutableListOf<LogbookAction>()

    private fun show(unrestricted:Boolean) {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName,unrestricted)
        compose.setContent { MaterialTheme {
            LoTWUploadDialog(LogbookState(lotwSettings=LoTWSettings("BA7OPF","test-password")),actions::add)
        } }
    }

    @Test fun optimizedBatteryDoesNotBlockOrPromptBeforeFullSync() {
        show(false)
        compose.onNodeWithText("Full sync").performScrollTo().performClick()
        compose.onNodeWithText("Allow background sync").assertDoesNotExist()
        compose.onNodeWithText("Battery settings").assertDoesNotExist()
        compose.runOnIdle {
            val intent=shadowOf(context).nextStartedService
            assertNotNull(intent)
            assertEquals("Full",intent.getStringExtra(LogbookBackgroundSyncController.EXTRA_MODE))
            assertEquals("LOTW",intent.getStringExtra(LogbookBackgroundSyncController.EXTRA_PROVIDER))
            assertEquals(listOf(LogbookAction.DismissLoTWAfterBackgroundStart),actions)
            assertFalse(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
        }
    }

    @Test fun alreadyAllowedSyncDoesNotShowTheBatteryPrompt() {
        show(true)
        compose.onNodeWithText("Full sync").performScrollTo().performClick()
        compose.onNodeWithText("Allow background sync").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(LogbookAction.DismissLoTWAfterBackgroundStart),actions) }
    }
}
