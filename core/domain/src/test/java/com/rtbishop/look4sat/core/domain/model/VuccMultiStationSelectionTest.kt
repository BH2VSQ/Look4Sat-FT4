/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VuccMultiStationSelectionTest {
    private fun qso(call: String, timestamp: Long, vararg own: String): GridQso =
        GridQso(
            call = call, epochMs = timestamp, satName = "RS-44",
            mode = "CW", bandUp = "2M", bandDown = "70CM",
            myGrids = own.toSet(), myGrid = own.firstOrNull()
        )

    @Test
    fun multiSelectionUnionsCoveringScopesWithoutDuplicateQsos() {
        val single62 = qso("A", 20, "OL62")
        val single63 = qso("B", 30, "OL63")
        val boundary = qso("C", 10, "OL62", "OL63")
        val elsewhere = qso("D", 40, "PM01")
        val all = listOf(single62, single63, boundary, elsewhere)
        assertEquals(all, all.scopedToStations(emptySet()))
        assertEquals(listOf(single62, boundary), all.scopedToStations(setOf("OL62")))
        assertEquals(listOf(boundary), all.scopedToStations(setOf("OL62,OL63")))
        assertEquals(listOf(single62, single63, boundary), all.scopedToStations(setOf("OL62", "OL63")))
        assertEquals(listOf(boundary), all.scopedToStations(setOf("OL62,OL63", "OL62,OL63")))
    }

    @Test
    fun earliestCallsUseOnlySelectedStationQsos() {
        val qsos = mapOf(
            "NM84" to listOf(
                qso("WRONG", 1, "PM01"),
                qso("LATEST", 30, "OL62"),
                qso("FIRST", 10, "OL63"),
                qso("BOUNDARY", 20, "OL62", "OL63")
            )
        )
        assertEquals(mapOf("NM84" to "WRONG"), qsos.firstCallsByGrid(emptySet<String>()))
        assertEquals(mapOf("NM84" to "FIRST"), qsos.firstCallsByGrid(setOf("OL62", "OL63")))
        assertEquals(mapOf("NM84" to "BOUNDARY"), qsos.firstCallsByGrid(setOf("OL62,OL63")))
        assertEquals(emptyMap<String, String>(), qsos.firstCallsByGrid(setOf("OM60")))
    }
}
