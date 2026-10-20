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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundTrackGeometryTest {

    @Test
    fun eastwardCrossingEndsAndBeginsAtMatchingDateLinePoints() {
        val start = GeoPos(10.0, 179.25)
        val end = GeoPos(14.0, -179.75)
        val sections = splitGroundTrackAtDateLine(listOf(start, end))

        assertEquals(2, sections.size)
        // Crossing fraction = 0.75 / (0.75 + 0.25) = 0.75.
        assertEquals(listOf(start, GeoPos(13.0, 180.0)), sections[0])
        assertEquals(listOf(GeoPos(13.0, -180.0), end), sections[1])
    }

    @Test
    fun westwardCrossingAlsoJoinsBothSides() {
        val start = GeoPos(10.0, -179.25)
        val end = GeoPos(14.0, 179.75)
        val sections = splitGroundTrackAtDateLine(listOf(start, end))

        assertEquals(2, sections.size)
        assertEquals(listOf(start, GeoPos(13.0, -180.0)), sections[0])
        assertEquals(listOf(GeoPos(13.0, 180.0), end), sections[1])
    }

    @Test
    fun multipleCrossingsRetainAllSourceSamples() {
        val points = listOf(
            GeoPos(20.0, 179.0),
            GeoPos(21.0, -179.0),
            GeoPos(22.0, -178.0),
            GeoPos(23.0, 179.0)
        )
        val sections = splitGroundTrackAtDateLine(points)

        assertEquals(3, sections.size)
        assertEquals(points[0], sections[0][0])
        assertEquals(points[1], sections[1][1])
        assertEquals(points[2], sections[1][2])
        assertEquals(points[3], sections[2][1])
        for (index in 0 until sections.lastIndex) {
            val left = sections[index].last()
            val right = sections[index + 1].first()
            assertEquals(180.0, absLongitude(left.longitude), 0.0)
            assertEquals(180.0, absLongitude(right.longitude), 0.0)
            assertEquals(-left.longitude, right.longitude, 0.0)
            assertEquals(left.latitude, right.latitude, 0.0)
        }
    }

    @Test
    fun pointsNearDateLineWithoutCrossingStayTogether() {
        val points = listOf(
            GeoPos(0.0, 177.0),
            GeoPos(1.0, 179.8),
            GeoPos(2.0, 179.95)
        )
        assertEquals(listOf(points), splitGroundTrackAtDateLine(points))
    }

    @Test
    fun emptyAndSingleSampleAreSafe() {
        assertTrue(splitGroundTrackAtDateLine(emptyList()).isEmpty())
        val one = GeoPos(0.0, -180.0)
        assertEquals(listOf(listOf(one)), splitGroundTrackAtDateLine(listOf(one)))
    }

    private fun absLongitude(longitude: Double) = kotlin.math.abs(longitude)
}
