/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.map

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import kotlin.math.abs

/**
 * Split a ground track at the anti-meridian without leaving gaps or drawing a
 * segment across the whole map. Both neighboring parts end/start at equivalent
 * ±180° coordinates with the SAME interpolated latitude.
 *
 * Points are already normalized to [-180°, 180°] by the satellite map model.
 */
internal fun splitGroundTrackAtDateLine(points: List<GeoPos>): List<List<GeoPos>> {
    if (points.isEmpty()) return emptyList()

    val segments = ArrayList<List<GeoPos>>()
    var segment = ArrayList<GeoPos>()
    var previous: GeoPos? = null

    for (point in points) {
        val last = previous
        if (last != null && abs(point.longitude - last.longitude) > 180.0) {
            val boundary = if (last.longitude > 0.0) 180.0 else -180.0
            // Unwrap the new longitude to the same world copy as the previous point.
            val unwrapped = point.longitude + if (boundary > 0.0) 360.0 else -360.0
            val fraction = ((boundary - last.longitude) / (unwrapped - last.longitude))
                .coerceIn(0.0, 1.0)
            val latitude = last.latitude + (point.latitude - last.latitude) * fraction

            segment.add(GeoPos(latitude, boundary))
            segments.add(segment)
            segment = ArrayList()
            segment.add(GeoPos(latitude, -boundary))
        }
        segment.add(point)
        previous = point
    }
    segments.add(segment)
    return segments
}
