package com.rtbishop.look4sat.feature.radar

import java.util.Locale
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner

data class CwConsoleDraft(
    val message: String = "",
    val myCallsign: String? = null,
    val sentReport: String = "599"
)

data class CwConsoleState(val visible: Boolean = false, val draft: CwConsoleDraft = CwConsoleDraft())

internal fun RadioControlSubState.shouldStopConsoleCw(): Boolean = when (transmitOwner) {
    RadioTransmitOwner.CW -> true
    RadioTransmitOwner.NONE -> remoteRadio?.txSource == 2
    else -> false
}

internal fun cwConsolePresetColumns(widthDp: Float, fontScale: Float): Int =
    if (widthDp < 340f || fontScale > 1.2f) 2 else 4

internal fun cwConsoleHeaderStacked(widthDp: Float, fontScale: Float): Boolean =
    widthDp < 300f || fontScale > 1.5f

internal data class CwCallsignCandidate(val start: Int, val end: Int, val callsign: String)

internal fun cwCallsignCandidates(text: String): List<CwCallsignCandidate> =
    Regex("[A-Za-z0-9]+(?:/[A-Za-z0-9]+)*").findAll(text).mapNotNull { match ->
        val call = match.value.uppercase(Locale.ROOT)
        val base = call.split('/').maxByOrNull(String::length).orEmpty()
        if (base.length !in 3..12 || base.none(Char::isLetter) || base.none(Char::isDigit) ||
            Regex("[A-R]{2}[0-9]{2}(?:[A-X]{2})?", RegexOption.IGNORE_CASE).matches(base) ||
            !base.last().isLetter()) null
        else CwCallsignCandidate(match.range.first, match.range.last + 1, call)
    }.toList()
