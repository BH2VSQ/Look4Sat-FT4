package com.rtbishop.look4sat.core.domain.repository

enum class FrequencyPairResult { UNSUPPORTED, APPLIED, HELD, FAILED }
enum class RadioTransmitOwner { NONE, VOICE, CW, FT4 }
enum class CwTransmitState { IDLE, QUEUED, SENDING, DONE, CANCELLED, FAILED }

data class RemoteRadioCapabilities(
    val frequencyStepHz: Long,
    val maxCwText: Int,
    val minimumWpm: Int,
    val maximumWpm: Int,
    val watchdogMillis: Int,
    val minimumRxHz: Long,
    val maximumRxHz: Long,
    val rxModeMask: Int,
    val txModeMask: Int,
    val atomicPair: Boolean,
    val voicePtt: Boolean,
    val cwText: Boolean,
    val ctcss: Boolean,
    val liveFmRetune: Boolean,
    val liveCwRetune: Boolean,
    val trackingDisplay: Boolean = false
)

data class RadioTrackingDisplay(val satelliteName: String, val azimuthDegrees: Double, val elevationDegrees: Double)

data class RemoteRadioState(
    val connected: Boolean = false,
    val firmware: String = "",
    val capabilities: RemoteRadioCapabilities? = null,
    val session: Long = 0,
    val rxFrequencyHz: Long? = null,
    val txFrequencyHz: Long? = null,
    val appliedRevision: Long = 0,
    val pendingRevision: Long = 0,
    val rxMode: String = "FM",
    val txMode: String = "FM",
    val txEnabled: Boolean = false,
    val txSource: Int = 0,
    val cwState: CwTransmitState = CwTransmitState.IDLE,
    val cwPosition: Int = 0,
    val cwLength: Int = 0,
    val error: String? = null
)

object CwTransmitText {
    private const val ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 /?.,=-+(&"
    fun normalize(text: String): String = text.uppercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), " ").trim().also { value ->
            require(value.isNotEmpty() && value.length <= 4096) { "CW text must contain 1–4096 characters" }
            require(value.all { it in ALLOWED }) { "Unsupported CW character (use A–Z, 0–9, space, / ? . , = - + ( &)" }
        }

    fun chunks(text: String, maximum: Int): List<String> {
        require(maximum >= 1)
        var remaining = normalize(text)
        require(remaining.split(' ').all { it.length <= maximum }) { "A CW word exceeds the firmware text buffer" }
        val chunks = mutableListOf<String>()
        while (remaining.length > maximum) {
            val boundary = remaining.lastIndexOf(' ', maximum).takeIf { it > 0 } ?: maximum
            chunks += remaining.take(boundary)
            remaining = remaining.drop(boundary).trimStart()
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return chunks
    }
}
