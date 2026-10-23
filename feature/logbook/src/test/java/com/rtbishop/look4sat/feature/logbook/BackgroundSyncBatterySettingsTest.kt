package com.rtbishop.look4sat.feature.logbook

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BackgroundSyncBatterySettingsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun firstOptimizedSyncExplainsTheChoiceButDoesNotTreatSkippingAsPermission() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName,false)
        assertTrue(BackgroundSyncBatterySettings.shouldExplain(context))
        BackgroundSyncBatterySettings.acknowledge(context)
        assertFalse(BackgroundSyncBatterySettings.shouldExplain(context))
        assertFalse(BackgroundSyncBatterySettings.unrestricted(context))
    }
    @Test fun alreadyUnrestrictedUsersAreNotPrompted() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName,true)
        assertTrue(BackgroundSyncBatterySettings.unrestricted(context))
        assertFalse(BackgroundSyncBatterySettings.shouldExplain(context))
    }
    @Test fun systemChoiceAndFallbackUseTheInstalledPackageWithoutRequestingDirectExemption() {
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,BackgroundSyncBatterySettings.settingsIntent().action)
        val fallback=BackgroundSyncBatterySettings.fallbackIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,fallback.action)
        assertEquals("package:${context.packageName}",fallback.data.toString())
    }
}
