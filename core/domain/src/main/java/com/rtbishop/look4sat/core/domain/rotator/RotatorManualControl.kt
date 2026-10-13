package com.rtbishop.look4sat.core.domain.rotator

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

object RotatorManualControl {
    fun valid(position: RotatorPosition, range: RotatorAzimuthRange): Boolean =
        position.azimuthDegrees.isFinite() && position.elevationDegrees.isFinite() &&
            position.elevationDegrees in 0.0..180.0 && when (range) {
                RotatorAzimuthRange.ZERO_TO_360 -> position.azimuthDegrees in 0.0..360.0
                RotatorAzimuthRange.MINUS_180_TO_180 -> position.azimuthDegrees in -180.0..180.0
                RotatorAzimuthRange.ZERO_TO_450 -> position.azimuthDegrees in 0.0..450.0
            }

    /** Normalized plot coordinates: north=(0,-1), east=(1,0), zenith=(0,0). */
    fun fromPlot(x: Double, y: Double, range: RotatorAzimuthRange, previousAzimuth: Double): RotatorPosition? {
        if (!x.isFinite() || !y.isFinite() || !previousAzimuth.isFinite()) return null
        val radius = hypot(x, y)
        if (radius > 1.05) return null
        var az = if (radius < 0.01) previousAzimuth else (Math.toDegrees(atan2(x, -y)) + 360.0) % 360.0
        az = when (range) {
            RotatorAzimuthRange.MINUS_180_TO_180 -> (az + 180.0) % 360.0 - 180.0
            RotatorAzimuthRange.ZERO_TO_450 -> if (az <= 90.0 && previousAzimuth > az + 180.0) az + 360.0 else az
            RotatorAzimuthRange.ZERO_TO_360 -> az % 360.0
        }
        return RotatorPosition(round(az * 10.0) / 10.0, round((1.0 - radius.coerceAtMost(1.0)) * 900.0) / 10.0)
    }
}
