package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Cross-module, process-local state for both manual and automatic LoTW report
 * downloads. No credentials, callsign or other private account information
 * is retained here or included in the foreground-service Intent.
 */
data class LoTWForegroundStatus(
    val running: Boolean = false,
    val mode: LoTWSyncMode? = null,
    val progress: LoTWProgress? = null,
    val message: String? = null
)

object LoTWForegroundSyncStatus {
    private val mutable = MutableStateFlow(LoTWForegroundStatus())
    val state: StateFlow<LoTWForegroundStatus> = mutable
    fun publish(status: LoTWForegroundStatus) { mutable.value = status }
}

/** Implemented by the application, keeping feature/settings independent of app. */
interface ILoTWForegroundSyncLauncher {
    fun startManualLoTWSync(account: LoTWSettings, mode: LoTWSyncMode): Boolean
    fun cancelManualLoTWSync()
}
