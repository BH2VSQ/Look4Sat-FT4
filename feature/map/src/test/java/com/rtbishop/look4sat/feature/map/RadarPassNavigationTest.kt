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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RadarPassNavigationTest {
    private val first = RadarPassMapDestination(catNum = 100, aosTime = 10_000, losTime = 11_000)
    private val second = RadarPassMapDestination(catNum = 200, aosTime = 12_000, losTime = 13_000)
    private val third = RadarPassMapDestination(catNum = 100, aosTime = 14_000, losTime = 15_000)
    private val passes = listOf(first, second, third)

    @Test fun nextPassCanChangeSatellite() {
        assertEquals(second, adjacentRadarPass(passes, first, 1))
        assertEquals(third, adjacentRadarPass(passes, second, 1))
    }

    @Test fun previousPassCanChangeSatellite() {
        assertEquals(second, adjacentRadarPass(passes, third, -1))
        assertEquals(first, adjacentRadarPass(passes, second, -1))
    }

    @Test fun currentPassIsMatchedBySatelliteAndAos() {
        val oldLosFromSamePass = second.copy(losTime = second.losTime + 100)
        assertEquals(third, adjacentRadarPass(passes, oldLosFromSamePass, 1))
    }

    @Test fun listEdgesHaveNoAdjacentPass() {
        assertNull(adjacentRadarPass(passes, first, -1))
        assertNull(adjacentRadarPass(passes, third, 1))
        assertNull(adjacentRadarPass(emptyList(), second, 1))
    }

    @Test fun invalidDirectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            adjacentRadarPass(passes, second, 0)
        }
    }
}
