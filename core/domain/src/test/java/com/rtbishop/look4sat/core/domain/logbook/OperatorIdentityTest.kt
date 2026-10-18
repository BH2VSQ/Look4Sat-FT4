/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Own identity precedence: the LoTW certificate first (it is the signing identity), the selected
 * Wavelog station profile as the fallback for a Wavelog-only setup, blank when neither exists.
 */
class OperatorIdentityTest {

    @Test
    fun callsign_certificateWinsOverTheWavelogStation() {
        assertEquals("BA7OPF", operatorCallsign("ba7opf", "BH3HCF"))
    }

    @Test
    fun callsign_fallsBackToTheWavelogStationWithoutACertificate() {
        assertEquals("BH3HCF", operatorCallsign("", "bh3hcf"))
        assertEquals("BH3HCF", operatorCallsign("   ", "BH3HCF"))
    }

    @Test
    fun callsign_isBlankWhenNeitherSourceKnowsOne() {
        assertEquals("", operatorCallsign("", ""))
    }

    @Test
    fun grids_stationLocationWins() {
        assertEquals(listOf("OL62TI"), operatorGrids("OL62TI", "OL62TJ,OL62TK"))
    }

    @Test
    fun grids_fallBackToTheWavelogStationProfile() {
        // Wavelog keeps several gridsquares comma-separated (a location on a line or a corner);
        // they come back trimmed and uppercase.
        assertEquals(listOf("OL62TI", "OL62TJ"), operatorGrids("", " ol62ti , OL62TJ "))
    }

    @Test
    fun grids_areEmptyWhenNeitherSourceKnowsOne() {
        assertTrue(operatorGrids("", "").isEmpty())
    }
}
