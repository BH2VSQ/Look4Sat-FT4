package com.rtbishop.look4sat.feature.mutual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadarMatchSelectionTest {
    private fun pass(start: Long, end: Long) = MutualPass(
        catNum = 100,
        name = "SO-50",
        startTime = start,
        endTime = end,
        maxElevationA = 40.0,
        maxElevationB = 35.0,
        elevationSamples = emptyList()
    )

    @Test
    fun selectsEarliestPassThatHasNotEnded() {
        val passes = listOf(pass(100, 200), pass(900, 1_100), pass(1_200, 1_400))
        assertEquals(900L, nextUpcomingMutualPass(passes, nowMs = 1_000L)?.startTime)
    }

    @Test
    fun skipsEndedPassesAndReturnsNullWhenAllHaveEnded() {
        assertNull(nextUpcomingMutualPass(listOf(pass(100, 200), pass(300, 400)), nowMs = 500L))
    }

    @Test
    fun emptyResultHasNoSelection() {
        assertNull(nextUpcomingMutualPass(emptyList(), nowMs = 0L))
    }
}
