package com.rtbishop.look4sat.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarPassResetTimeTest {
    @Test
    fun resetDuringPassReturnsCurrentTime() {
        assertEquals(1_500L, radarPassResetTime(nowMs = 1_500L, aosTime = 1_000L, losTime = 2_000L))
    }

    @Test
    fun resetBeforePassReturnsAos() {
        assertEquals(1_000L, radarPassResetTime(nowMs = 500L, aosTime = 1_000L, losTime = 2_000L))
    }

    @Test
    fun resetAfterPassReturnsAos() {
        assertEquals(1_000L, radarPassResetTime(nowMs = 2_500L, aosTime = 1_000L, losTime = 2_000L))
    }

    @Test
    fun resetAtEitherEndpointKeepsCurrentTime() {
        assertEquals(1_000L, radarPassResetTime(nowMs = 1_000L, aosTime = 1_000L, losTime = 2_000L))
        assertEquals(2_000L, radarPassResetTime(nowMs = 2_000L, aosTime = 1_000L, losTime = 2_000L))
    }
}
