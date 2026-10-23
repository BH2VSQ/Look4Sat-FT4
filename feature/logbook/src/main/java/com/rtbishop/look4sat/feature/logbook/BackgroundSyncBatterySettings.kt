package com.rtbishop.look4sat.feature.logbook

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

internal object BackgroundSyncBatterySettings {
    private const val PREFERENCES = "background_sync_permissions"
    private const val PROMPT_SEEN = "battery_prompt_seen"

    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun shouldExplain(context: Context): Boolean = !unrestricted(context) &&
        !context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(PROMPT_SEEN, false)

    fun acknowledge(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(PROMPT_SEEN, true).apply()
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun fallbackIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
