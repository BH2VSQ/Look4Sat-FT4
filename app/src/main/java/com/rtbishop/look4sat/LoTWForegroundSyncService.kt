/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.LoTWPhase
import com.rtbishop.look4sat.core.domain.repository.LoTWProgress
import com.rtbishop.look4sat.core.domain.repository.LoTWResult
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.applyLoTWGridResult
import com.rtbishop.look4sat.core.domain.repository.lotwIncrementalSince
import com.rtbishop.look4sat.core.domain.repository.lotwSyncToday
import com.rtbishop.look4sat.core.domain.repository.resolveLoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.shouldAutoSyncLoTW
import com.rtbishop.look4sat.core.domain.logbook.toConfirmedRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The OS-visible status-bar entry is backed by a data-sync foreground service.
 * It owns the LoTW network request so dismissing the Activity does not cancel it.
 * No callsign or password ever enters Intent extras or notification text.
 */
class LoTWForegroundSyncService : Service() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LoTW synchronization", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (syncJob?.isActive == true) return START_NOT_STICKY

        // Android requires the initial foreground notification immediately,
        // before the first network or database operation.
        val starting = notification("Preparing LoTW synchronization", null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, starting)
        }
        syncJob = work.launch {
            try {
                synchronize()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                report("LoTW synchronization failed", null)
                android.util.Log.e("LoTWForegroundSync", "Synchronization failed", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                // A second onStartCommand while the first job was active can
                // increment startId without starting a new job. stopSelfResult
                // on the original id would leave a stranded Service.
                stopSelf()
            }
        }
        // Do not restart an interrupted LoTW download without user intent:
        // a resumed request could violate the report rate limit.
        return START_NOT_STICKY
    }

    private suspend fun synchronize() {
        val container = (application as IContainerProvider).getMainContainer()
        val settings = container.settingsRepo
        if (settings.wavelogUploadSettings.value.isReady) return
        val account = settings.lotwSettings.value
        val now = System.currentTimeMillis()
        if (!shouldAutoSyncLoTW(
                account.isConfigured,
                settings.otherSettings.value.stateOfAutoLotwSync,
                settings.getLastLotwSyncDate(),
                lotwSyncToday(now)
            )) return
        val callsign = account.callsign.trim().uppercase()
        val mode = resolveLoTWSyncMode(settings.getLastLotwSyncCallsign(), callsign, null)
        val since = if (mode == LoTWSyncMode.Incremental) lotwIncrementalSince(settings) else ""
        report("Connecting to LoTW", null)
        val result = container.lotwRepo.fetchConfirmedGridQsos(
            callsign, account.password, since
        ) { progress ->
            // HTTP download callbacks can occur at high frequency.
            if (SystemClock.elapsedRealtime() - lastNotificationAt >= 500 ||
                progress.phase == LoTWPhase.Connecting) {
                lastNotificationAt = SystemClock.elapsedRealtime()
                report(
                    if (progress.phase == LoTWPhase.Downloading) "Downloading LoTW confirmations"
                    else "Connecting to LoTW", progress
                )
            }
        }
        if (result is LoTWResult.Success) {
            report("Saving synchronized contacts", null)
            val confirmed = result.qsos.values.flatten().map { it.toConfirmedRecord(callsign) }
            // The regular merge leaves existing QSOs alone. Transactional full
            // replacement is a separate upstream contribution.
            container.qsoRepository.mergeLoTW(confirmed)
            applyLoTWGridResult(settings, result, mode, callsign, now)
            report("LoTW synchronization complete", null)
        } else {
            report("LoTW synchronization unavailable", null)
        }
    }

    private fun report(message: String, progress: LoTWProgress?) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, progress))
    }

    private fun notification(message: String, progress: LoTWProgress?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Look4Sat · LoTW")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (progress != null && progress.expectedBytes > 0) {
                    setProgress(100, (progress.fraction * 100).toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    override fun onDestroy() {
        work.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "lotw_background_sync"
        const val NOTIFICATION_ID = 7422
    }
}
