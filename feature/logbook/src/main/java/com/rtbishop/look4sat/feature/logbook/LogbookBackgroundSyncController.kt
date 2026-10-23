/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.logbook

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.model.LogbookSyncProvider
import com.rtbishop.look4sat.core.domain.repository.LoTWProgress
import com.rtbishop.look4sat.core.domain.repository.LoTWSyncMode
import com.rtbishop.look4sat.core.domain.repository.WavelogSyncMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogbookBackgroundSyncStatus(
    val isRunning: Boolean = false,
    val provider: LogbookSyncProvider? = null,
    val lotwMode: LoTWSyncMode? = null,
    val wavelogMode: WavelogSyncMode? = null,
    val progress: LoTWProgress? = null,
    /** Total QSO count supplied by the current sync report, retained while saving. */
    val notificationQsoCount: Long = 0L,
    val stageMessage: String? = null,
    val message: String? = null,
    val succeeded: Boolean? = null,
    val lotwDownloadSummary: LoTWDownloadSummary? = null,
    val lotwDownloadError: LoTWDownloadError? = null,
    val workedGridsCount: Int? = null
)

/**
 * In-process status shared by the foreground service and the logbook UI.
 * The service owns the operation; this flow only mirrors its state for screens
 * that happen to be visible while the operation continues in the background.
 */
object LogbookBackgroundSyncController {
    const val ACTION_START = "com.rtbishop.look4sat.action.START_LOGBOOK_SYNC"
    const val EXTRA_PROVIDER = "sync_provider"
    const val EXTRA_MODE = "sync_mode"
    const val EXTRA_CALLSIGN = "sync_callsign"
    const val EXTRA_PASSWORD = "sync_password"
    const val SERVICE_CLASS_NAME = "com.rtbishop.look4sat.LogbookSyncService"

    private val mutableStatus = MutableStateFlow(LogbookBackgroundSyncStatus())
    val status: StateFlow<LogbookBackgroundSyncStatus> = mutableStatus.asStateFlow()

    fun publish(status: LogbookBackgroundSyncStatus) {
        mutableStatus.value = status
    }

    fun update(transform: (LogbookBackgroundSyncStatus) -> LogbookBackgroundSyncStatus) {
        mutableStatus.update(transform)
    }

    fun start(
        context: Context,
        provider: LogbookSyncProvider,
        mode: String,
        callsign: String = "",
        password: String = ""
    ) {
        val intent = Intent(ACTION_START).setClassName(context, SERVICE_CLASS_NAME).apply {
            putExtra(EXTRA_PROVIDER, provider.name)
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_CALLSIGN, callsign)
            putExtra(EXTRA_PASSWORD, password)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
