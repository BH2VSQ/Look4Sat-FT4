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

import com.rtbishop.look4sat.core.presentation.RadarPassMapDestination

/**
 * Navigate by entry in the visible Passes list, NOT by satellite NORAD ID.
 * Matching by (catNum, AOS) lets us retain this pass's exact LOS even when
 * another satellite (or another pass of the same satellite) is adjacent.
 */
internal fun adjacentRadarPass(
    orderedPasses: List<RadarPassMapDestination>,
    current: RadarPassMapDestination,
    direction: Int
): RadarPassMapDestination? {
    require(direction == -1 || direction == 1) { "Direction must be -1 or 1" }
    val index = orderedPasses.indexOfFirst {
        it.catNum == current.catNum && it.aosTime == current.aosTime
    }
    return if (index < 0) null else orderedPasses.getOrNull(index + direction)
}
