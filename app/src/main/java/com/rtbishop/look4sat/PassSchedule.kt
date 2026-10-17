/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.elevationColor
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val PASS_SCHEDULE_PREFS = "pass_schedule_preferences"
private const val PASS_SCHEDULE_CHANNEL = "satellite_pass_schedule"
private const val PASS_SCHEDULE_WINDOW_HOURS = 72
private val PASS_REMINDER_PRESETS = listOf(5, 10, 15, 30, 60)

private data class PassScheduleConfig(
    val satelliteIds: Set<Int> = emptySet(),
    val minimumElevation: Int = 10,
    val notifyAtStart: Boolean = false,
    val notifyBefore: Boolean = false,
    val reminderMinutes: Int = 10
)

private data class ScheduledPass(
    val catNum: Int,
    val name: String,
    val aosTime: Long,
    val losTime: Long,
    val maxElevation: Double
)

private object PassScheduleStore {
    private fun prefs(context: Context) = context.getSharedPreferences(PASS_SCHEDULE_PREFS, Context.MODE_PRIVATE)

    fun readConfig(context: Context): PassScheduleConfig {
        val prefs = prefs(context)
        return PassScheduleConfig(
            satelliteIds = prefs.getStringSet("satellite_ids", emptySet()).orEmpty()
                .mapNotNull(String::toIntOrNull).toSet(),
            minimumElevation = prefs.getInt("minimum_elevation", 10).coerceIn(0, 90),
            notifyAtStart = prefs.getBoolean("notify_at_start", false),
            notifyBefore = prefs.getBoolean("notify_before", false) && !prefs.getBoolean("notify_at_start", false),
            reminderMinutes = prefs.getInt("reminder_minutes", 10).let { stored ->
                stored.takeIf { it in PASS_REMINDER_PRESETS } ?: 10
            }
        )
    }

    fun saveConfig(context: Context, config: PassScheduleConfig) {
        prefs(context).edit()
            .putStringSet("satellite_ids", config.satelliteIds.map { it.toString() }.toSet())
            .putInt("minimum_elevation", config.minimumElevation.coerceIn(0, 90))
            .putBoolean("notify_at_start", config.notifyAtStart)
            .putBoolean("notify_before", config.notifyBefore)
            .putInt("reminder_minutes", config.reminderMinutes.takeIf { it in PASS_REMINDER_PRESETS } ?: 10)
            .apply()
    }

    fun readEvents(context: Context): List<ScheduledPass> = runCatching {
        val array = JSONArray(prefs(context).getString("events", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val catNum = item.optInt("catNum", 0)
            val aos = item.optLong("aosTime", 0L)
            if (catNum <= 0 || aos <= 0L) return@mapNotNull null
            ScheduledPass(
                catNum = catNum,
                name = item.optString("name", "Satellite $catNum"),
                aosTime = aos,
                losTime = item.optLong("losTime", 0L),
                maxElevation = item.optDouble("maxElevation", 0.0)
            )
        }
    }.getOrDefault(emptyList())

    fun saveEvents(context: Context, events: List<ScheduledPass>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject().apply {
                put("catNum", event.catNum)
                put("name", event.name)
                put("aosTime", event.aosTime)
                put("losTime", event.losTime)
                put("maxElevation", event.maxElevation)
            })
        }
        prefs(context).edit().putString("events", array.toString()).apply()
    }
}

private object PassScheduleNotificationScheduler {
    const val ACTION_ALERT = "com.rtbishop.look4sat.PASS_SCHEDULE_ALERT"
    const val ACTION_REFRESH = "com.rtbishop.look4sat.PASS_SCHEDULE_REFRESH"
    const val EXTRA_CAT_NUM = "cat_num"
    const val EXTRA_SATELLITE_NAME = "satellite_name"
    const val EXTRA_AOS_TIME = "aos_time"
    const val EXTRA_KIND = "kind"
    const val EXTRA_MAX_ELEVATION = "max_elevation"
    const val KIND_START = "start"
    const val KIND_BEFORE = "before"

    fun replacePasses(context: Context, config: PassScheduleConfig, passes: List<OrbitalPass>) {
        replaceEvents(context, config, passes.map { pass ->
            ScheduledPass(pass.catNum, pass.name, pass.aosTime, pass.losTime, pass.maxElevation)
        })
    }

    fun replaceEvents(context: Context, config: PassScheduleConfig, events: List<ScheduledPass>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val oldEvents = PassScheduleStore.readEvents(context)
        oldEvents.forEach { event ->
            cancelEventAlarm(context, alarmManager, event, KIND_START)
            cancelEventAlarm(context, alarmManager, event, KIND_BEFORE)
        }
        cancelRefresh(context, alarmManager)
        val futureEvents = events.filter { it.losTime > System.currentTimeMillis() }
            .distinctBy { it.catNum to it.aosTime }
            .sortedBy { it.aosTime }
        PassScheduleStore.saveEvents(context, futureEvents)
        val now = System.currentTimeMillis()
        futureEvents.forEach { event ->
            if (config.notifyAtStart && event.aosTime > now) {
                scheduleEvent(context, alarmManager, event, KIND_START, event.aosTime)
            }
            val beforeTime = event.aosTime - config.reminderMinutes * 60_000L
            if (config.notifyBefore && beforeTime > now) {
                scheduleEvent(context, alarmManager, event, KIND_BEFORE, beforeTime)
            }
        }
        if (config.satelliteIds.isNotEmpty()) {
            val refreshIntent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
                action = ACTION_REFRESH
                data = Uri.parse("look4sat://pass-schedule/refresh")
            }
            setAlarm(context, alarmManager, System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
                PendingIntent.getBroadcast(context, 0, refreshIntent, pendingFlags()))
        }
    }

    fun restoreAfterReboot(context: Context) {
        replaceEvents(context, PassScheduleStore.readConfig(context), PassScheduleStore.readEvents(context))
    }

    fun showAlert(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureNotificationChannel(context)
        val name = intent.getStringExtra(EXTRA_SATELLITE_NAME).orEmpty().ifBlank { "Satellite" }
        val aosTime = intent.getLongExtra(EXTRA_AOS_TIME, 0L)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(aosTime))
        val title = if (kind == KIND_BEFORE) {
            context.getString(R.string.pass_schedule_notification_before_title)
        } else {
            context.getString(R.string.pass_schedule_notification_start_title)
        }
        val content = if (kind == KIND_BEFORE) {
            context.getString(R.string.pass_schedule_notification_before_text, name, time)
        } else {
            context.getString(R.string.pass_schedule_notification_start_text, name, time)
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags()
        )
        val notification = NotificationCompat.Builder(context, PASS_SCHEDULE_CHANNEL)
            .setSmallIcon(R.drawable.ic_satellite_alt)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val id = (intent.getIntExtra(EXTRA_CAT_NUM, 0) * 31 + aosTime.hashCode()) * 31 + kind.hashCode()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun scheduleRefreshRetry(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_REFRESH
            data = Uri.parse("look4sat://pass-schedule/refresh")
        }
        setAlarm(context, manager, System.currentTimeMillis() + 6 * 60 * 60 * 1000L,
            PendingIntent.getBroadcast(context, 0, intent, pendingFlags()))
    }

    private fun scheduleEvent(
        context: Context,
        manager: AlarmManager,
        event: ScheduledPass,
        kind: String,
        triggerAt: Long
    ) {
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_ALERT
            data = Uri.parse("look4sat://pass-schedule/${event.catNum}/${event.aosTime}/$kind")
            putExtra(EXTRA_CAT_NUM, event.catNum)
            putExtra(EXTRA_SATELLITE_NAME, event.name)
            putExtra(EXTRA_AOS_TIME, event.aosTime)
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_MAX_ELEVATION, event.maxElevation)
        }
        setAlarm(context, manager, triggerAt,
            PendingIntent.getBroadcast(context, 0, intent, pendingFlags()))
    }

    private fun cancelEventAlarm(context: Context, manager: AlarmManager, event: ScheduledPass, kind: String) {
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_ALERT
            data = Uri.parse("look4sat://pass-schedule/${event.catNum}/${event.aosTime}/$kind")
        }
        PendingIntent.getBroadcast(context, 0, intent, pendingFlags())?.let(manager::cancel)
    }

    private fun cancelRefresh(context: Context, manager: AlarmManager) {
        val intent = Intent(context, PassScheduleAlarmReceiver::class.java).apply {
            action = ACTION_REFRESH
            data = Uri.parse("look4sat://pass-schedule/refresh")
        }
        PendingIntent.getBroadcast(context, 0, intent, pendingFlags())?.let(manager::cancel)
    }

    private fun setAlarm(context: Context, manager: AlarmManager, triggerAt: Long, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun pendingFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
            val channel = android.app.NotificationChannel(
                PASS_SCHEDULE_CHANNEL,
                context.getString(R.string.pass_schedule_notification_channel),
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.pass_schedule_notification_channel_description) }
            manager.createNotificationChannel(channel)
        }
    }
}

class PassScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PassScheduleNotificationScheduler.ACTION_ALERT ->
                PassScheduleNotificationScheduler.showAlert(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> PassScheduleNotificationScheduler.restoreAfterReboot(context)
            PassScheduleNotificationScheduler.ACTION_REFRESH -> refreshSchedule(context)
        }
    }

    private fun refreshSchedule(context: Context) {
        val pending: PendingResult = goAsync()
        val provider = context.applicationContext as? IContainerProvider
        val container = provider?.getMainContainer()
        if (container == null) {
            pending.finish()
            PassScheduleNotificationScheduler.scheduleRefreshRetry(context)
            return
        }
        container.appScope.launch {
            try {
                val config = PassScheduleStore.readConfig(context)
                if (config.satelliteIds.isEmpty()) {
                    PassScheduleNotificationScheduler.replaceEvents(context, config, emptyList())
                    return@launch
                }
                withTimeoutOrNull(20_000L) {
                    container.satelliteRepo.satellites.first { it.isNotEmpty() }
                }
                val passes = container.satelliteRepo.getSchedulePasses(
                    catNums = config.satelliteIds,
                    startTime = System.currentTimeMillis(),
                    hoursAhead = PASS_SCHEDULE_WINDOW_HOURS,
                    minElevation = config.minimumElevation.toDouble()
                )
                PassScheduleNotificationScheduler.replacePasses(context, config, passes)
            } catch (_: Exception) {
                PassScheduleNotificationScheduler.scheduleRefreshRetry(context)
            } finally {
                pending.finish()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassScheduleDestination(navigateUp: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val satellites by container.satelliteRepo.satellites.collectAsStateWithLifecycle()
    val currentlyTrackedIds by container.settingsRepo.selectedIds.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    val isUtc = otherSettings.stateOfUtc
    var config by remember(context) { mutableStateOf(PassScheduleStore.readConfig(context)) }
    var passes by remember { mutableStateOf<List<OrbitalPass>>(emptyList()) }
    var isCalculating by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val showTrackingSettings = remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun updateConfig(next: PassScheduleConfig, requestNotificationPermissionIfNeeded: Boolean = false) {
        config = next
        PassScheduleStore.saveConfig(context, next)
        if (requestNotificationPermissionIfNeeded) requestNotificationPermission()
    }

    BackHandler(enabled = showTrackingSettings.value) { showTrackingSettings.value = false }

    LaunchedEffect(
        satellites,
        currentlyTrackedIds,
        config.satelliteIds,
        config.minimumElevation,
        config.notifyAtStart,
        config.notifyBefore,
        config.reminderMinutes
    ) {
        // The repository is populated asynchronously after app startup. Wait for it
        // when the tracking selection is non-empty, rather than clearing a saved schedule.
        if (satellites.isEmpty() && currentlyTrackedIds.isNotEmpty()) return@LaunchedEffect

        // The schedule can only contain satellites that are currently selected for tracking.
        val availableIds = currentlyTrackedIds.toSet()
        val validSelection = config.satelliteIds.intersect(availableIds)
        if (validSelection != config.satelliteIds) {
            val cleanedConfig = config.copy(satelliteIds = validSelection)
            config = cleanedConfig
            PassScheduleStore.saveConfig(context, cleanedConfig)
            return@LaunchedEffect
        }

        isCalculating = true
        try {
            val calculatedPasses = if (config.satelliteIds.isEmpty()) {
                emptyList()
            } else {
                container.satelliteRepo.getSchedulePasses(
                    catNums = config.satelliteIds,
                    startTime = System.currentTimeMillis(),
                    hoursAhead = PASS_SCHEDULE_WINDOW_HOURS,
                    minElevation = config.minimumElevation.toDouble()
                )
            }
            passes = calculatedPasses
            PassScheduleStore.saveConfig(context, config)
            // Only replace alarms after the corresponding schedule calculation completes.
            PassScheduleNotificationScheduler.replacePasses(context, config, calculatedPasses)
        } finally {
            isCalculating = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (showTrackingSettings.value) R.string.pass_schedule_tracking_settings
                            else R.string.pass_schedule_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showTrackingSettings.value) showTrackingSettings.value = false else navigateUp()
                    }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.radar_back))
                    }
                },
                actions = {
                    if (!showTrackingSettings.value) {
                        TextButton(onClick = { showTrackingSettings.value = true }) {
                            Text(stringResource(R.string.pass_schedule_tracking_settings))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (showTrackingSettings.value) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.pass_schedule_tracking_hint), style = MaterialTheme.typography.bodyMedium)
                if (satellites.isEmpty()) {
                    Text(stringResource(R.string.pass_schedule_no_tracked_satellites), color = MaterialTheme.colorScheme.error)
                } else {
                    satellites.forEach { satellite ->
                        val catNum = satellite.data.catnum
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                val selected = config.satelliteIds.toMutableSet()
                                if (!selected.add(catNum)) selected.remove(catNum)
                                updateConfig(config.copy(satelliteIds = selected))
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = catNum in config.satelliteIds,
                                onCheckedChange = { checked ->
                                    val selected = config.satelliteIds.toMutableSet()
                                    if (checked) selected.add(catNum) else selected.remove(catNum)
                                    updateConfig(config.copy(satelliteIds = selected))
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(satellite.data.name, fontWeight = FontWeight.Medium)
                                Text("NORAD $catNum", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.pass_schedule_minimum_elevation, config.minimumElevation),
                    style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = config.minimumElevation.toFloat(),
                    onValueChange = { updateConfig(config.copy(minimumElevation = it.toInt().coerceIn(0, 90))) },
                    valueRange = 0f..90f,
                    steps = 89
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0°", style = MaterialTheme.typography.labelSmall)
                    Text("90°", style = MaterialTheme.typography.labelSmall)
                }

                HorizontalDivider()
                Text(stringResource(R.string.pass_schedule_notifications), style = MaterialTheme.typography.titleMedium)
                val selectedReminder = when {
                    config.notifyAtStart -> 0
                    config.notifyBefore -> config.reminderMinutes
                    else -> -1
                }
                val reminderRows = listOf(listOf(0, 5, 10), listOf(15, 30, 60))
                reminderRows.forEach { rowMinutes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowMinutes.forEach { minutes ->
                            val selected = selectedReminder == minutes
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = when {
                                        selected -> config.copy(notifyAtStart = false, notifyBefore = false)
                                        minutes == 0 -> config.copy(notifyAtStart = true, notifyBefore = false)
                                        else -> config.copy(
                                            notifyAtStart = false,
                                            notifyBefore = true,
                                            reminderMinutes = minutes
                                        )
                                    }
                                    updateConfig(next, requestNotificationPermissionIfNeeded = !selected)
                                },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        if (minutes == 0) stringResource(R.string.pass_schedule_notify_at_start)
                                        else stringResource(R.string.pass_schedule_minutes_short, minutes),
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alarmManager = context.getSystemService(AlarmManager::class.java)
                    if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                        TextButton(onClick = {
                            val request = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(request) }
                        }) {
                            Text(stringResource(R.string.pass_schedule_enable_exact_alarm))
                        }
                    }
                }
                Button(onClick = { showTrackingSettings.value = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pass_schedule_done))
                }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.pass_schedule_range), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.pass_schedule_summary, config.satelliteIds.size, passes.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (isCalculating) Text(stringResource(R.string.pass_schedule_calculating),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (config.satelliteIds.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.pass_schedule_empty_selection), fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.pass_schedule_empty_selection_hint), style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { showTrackingSettings.value = true }) {
                                    Text(stringResource(R.string.pass_schedule_tracking_settings))
                                }
                            }
                        }
                    }
                } else if (passes.isEmpty() && !isCalculating) {
                    item {
                        Text(stringResource(R.string.pass_schedule_no_passes),
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(passes, key = { "${it.catNum}-${it.aosTime}" }) { pass ->
                    PassScheduleItem(pass = pass, now = currentTime, isUtc = isUtc)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
@Composable
private fun PassScheduleItem(pass: OrbitalPass, now: Long, isUtc: Boolean) {
    val timeZone = remember(isUtc) {
        if (isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    }
    val timeFormat = remember(isUtc) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).also { it.timeZone = timeZone }
    }
    val aosTimeStr = remember(pass.aosTime, isUtc) { timeFormat.format(Date(pass.aosTime)) }
    val losTimeStr = remember(pass.losTime, isUtc) { timeFormat.format(Date(pass.losTime)) }
    val durationStr = remember(pass.aosTime, pass.losTime) {
        val seconds = (pass.losTime - pass.aosTime) / 1000L
        "${seconds / 60}m ${seconds % 60}s"
    }
    val remainingSeconds = ((pass.aosTime - now) / 1000L).coerceAtLeast(0L)
    val countdown = if (pass.aosTime <= now) {
        stringResource(R.string.pass_schedule_started)
    } else {
        String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            remainingSeconds / 3600L,
            (remainingSeconds % 3600L) / 60L,
            remainingSeconds % 60L
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pass.name,
                    modifier = Modifier.weight(1f).padding(end = 6.dp).infiniteMarquee(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )
                val elevColor = elevationColor(pass.maxElevation)
                Icon(
                    painter = painterResource(R.drawable.ic_elevation),
                    contentDescription = null,
                    tint = elevColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "${pass.maxElevation}°", color = elevColor)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pass.isDeepSpace) {
                        Text(
                            text = stringResource(R.string.pass_deep_space),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Text(text = durationStr, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "${pass.altitude} km", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.pass_aosLos, pass.aosAzimuth.toInt(), pass.losAzimuth.toInt()),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                val defaultTime = "   - - : - -   "
                Text(
                    text = if (pass.isDeepSpace) defaultTime else aosTimeStr,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(
                    progress = { if (pass.isDeepSpace) 100f else pass.progress },
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth(0.75f)
                )
                Text(
                    text = if (pass.isDeepSpace) defaultTime else losTimeStr,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(R.string.pass_schedule_countdown, countdown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.background)
    }
}
