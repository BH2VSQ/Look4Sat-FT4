package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.IRadioController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal data class PresetTuningResult(val rxHz: Long? = null, val txHz: Long? = null, val success: Boolean = true)

/** Legacy Yaesu CAT cannot address an inactive VFO. Never blindly toggle an unknown A/B state. */
internal fun presetHalfDuplexLeg(observed: Long, rxHz: Long?, txHz: Long?): Boolean? {
    val rxDistance = rxHz?.let { abs(observed - it) } ?: Long.MAX_VALUE
    val txDistance = txHz?.let { abs(observed - it) } ?: Long.MAX_VALUE
    if (minOf(rxDistance, txDistance) > 1_000_000L) return null
    if (rxHz != null && txHz != null && abs(rxDistance - txDistance) < 10_000L) return null
    return rxDistance < txDistance
}

/** Band access on IC-820 changes CI-V access only, not RF MAIN/SUB transmit roles. */
internal suspend fun tunePresetHalfDuplex(
    radio: IRadioController, ic820: Boolean, rxHz: Long?, txHz: Long?
): PresetTuningResult {
    if (!ic820) {
        val observed = radio.readFrequencyAndMode()?.first ?: return PresetTuningResult(success = false)
        return when (presetHalfDuplexLeg(observed, rxHz, txHz)) {
            true -> if (radio.setFrequency(checkNotNull(rxHz))) PresetTuningResult(rxHz = rxHz)
                else PresetTuningResult(success = false)
            false -> if (radio.setFrequency(checkNotNull(txHz))) PresetTuningResult(txHz = txHz)
                else PresetTuningResult(success = false)
            null -> PresetTuningResult(success = false)
        }
    }
    var appliedTx: Long? = null
    var appliedRx: Long? = null
    var rxSelected = rxHz == null
    try {
        if (txHz != null) {
            if (!radio.setBand(txHz) || !radio.setFrequency(txHz)) return PresetTuningResult(success = false)
            appliedTx = txHz
        }
        if (rxHz != null) {
            rxSelected = radio.setBand(rxHz)
            if (!rxSelected || !radio.setFrequency(rxHz)) return PresetTuningResult(txHz = appliedTx, success = false)
            appliedRx = rxHz
        }
    } finally {
        if (!rxSelected && rxHz != null) withContext(NonCancellable) {
            withTimeoutOrNull(1_500L) { radio.setBand(rxHz) }
        }
    }
    return PresetTuningResult(appliedRx, appliedTx)
}
