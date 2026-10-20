package com.rtbishop.look4sat.feature.map

/** Maps a normalized thumb value to an absolute instant on a single radar pass. */
internal fun timeOnRadarPass(aosTime: Long, losTime: Long, fraction: Float): Long {
    require(losTime > aosTime) { "LOS must be later than AOS" }
    if (fraction <= 0f) return aosTime
    if (fraction >= 1f) return losTime
    return aosTime + ((losTime - aosTime).toDouble() * fraction.toDouble()).toLong()
}
