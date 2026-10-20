package com.rtbishop.look4sat.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RadarPassMapTimelineTest {
    @Test fun sliderEndpointsMatchAosAndLosExactly() {
        assertEquals(1_000L, timeOnRadarPass(1_000L, 601_000L, 0f))
        assertEquals(601_000L, timeOnRadarPass(1_000L, 601_000L, 1f))
        assertEquals(1_000L, timeOnRadarPass(1_000L, 601_000L, -0.5f))
        assertEquals(601_000L, timeOnRadarPass(1_000L, 601_000L, 1.5f))
    }

    @Test fun midPassUsesTheEntirePassDuration() {
        assertEquals(301_000L, timeOnRadarPass(1_000L, 601_000L, 0.5f))
    }

    @Test fun invalidPassBoundsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            timeOnRadarPass(900L, 900L, 0.5f)
        }
    }
}
