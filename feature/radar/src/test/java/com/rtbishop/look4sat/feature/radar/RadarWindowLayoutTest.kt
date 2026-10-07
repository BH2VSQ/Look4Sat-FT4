/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarWindowLayoutTest {

    @Test
    fun toggleIsLimitedToNarrowSmallWindows() {
        assertTrue(isSmallRadarWindow(maxWidth = 411.dp, maxHeight = 426.dp))
        assertTrue(isSmallRadarWindow(maxWidth = 411.dp, maxHeight = 676.dp))
        assertTrue(isSmallRadarWindow(maxWidth = 393.dp, maxHeight = 560.dp))

        assertFalse(isSmallRadarWindow(maxWidth = 411.dp, maxHeight = 867.dp))
        assertFalse(isSmallRadarWindow(maxWidth = 393.dp, maxHeight = 804.dp))
        assertFalse(isSmallRadarWindow(maxWidth = 700.dp, maxHeight = 400.dp))
    }

    @Test
    fun largeRadarPrioritizesRadarPaneOnlyInSmallWindows() {
        assertEquals(
            RadarPaneWeights(radar = 1.6f, pager = 0.6f),
            radarPaneWeights(isSmallWindow = true, useLargeRadar = true)
        )
        assertEquals(
            RadarPaneWeights(radar = 1f, pager = 1f),
            radarPaneWeights(isSmallWindow = true, useLargeRadar = false)
        )
        assertEquals(
            RadarPaneWeights(radar = 1f, pager = 1f),
            radarPaneWeights(isSmallWindow = false, useLargeRadar = true)
        )
    }
}
