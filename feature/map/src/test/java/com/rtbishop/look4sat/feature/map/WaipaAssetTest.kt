/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.map

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All 38 current Indonesian provinces have separate drawable map regions. */
class WaipaAssetTest {
    private fun regions(): List<JSONObject> {
        val relative = "src/main/assets/awards/waipa.json"
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { dir ->
                listOf(File(dir, relative), File(dir, "feature/map/$relative"))
                    .firstOrNull { it.isFile }
            }.firstOrNull() ?: error("Missing waipa.json")
        val json = JSONObject(file.readText())
        assertEquals("waipa", json.getString("type"))
        val entries = json.getJSONArray("regions")
        return (0 until entries.length()).map { entries.getJSONObject(it) }
    }

    private fun polygon(region: JSONObject): AwardRegion {
        val input = region.getJSONArray("rings")
        val rings = (0 until input.length()).map { i ->
            val points = input.getJSONArray(i)
            (0 until points.length()).map { j ->
                val pt = points.getJSONArray(j)
                doubleArrayOf(pt.getDouble(0), pt.getDouble(1))
            }
        }
        return AwardRegion(
            code = region.getString("code"),
            name = region.getString("name"),
            nameEn = region.getString("name_en"),
            labelLon = region.getDouble("label_lon"),
            labelLat = region.getDouble("label_lat"),
            rings = rings
        )
    }

    @Test
    fun `all 38 Indonesian province codes have unique drawable and named regions`() {
        val list = regions()
        val codes = list.map { it.getString("code") }
        val official = setOf(
            "AC", "BA", "BB", "BE", "BT", "GO", "JA", "JB", "JI", "JK",
            "JT", "KB", "KI", "KR", "KS", "KT", "KU", "LA", "MA", "MU",
            "NB", "NT", "PA", "PB", "PD", "PP", "PS", "PT", "RI", "SA", "SB",
            "SG", "SN", "SR", "SS", "ST", "SU", "YO"
        )
        assertEquals(38, list.size)
        assertEquals(official, codes.toSet())
        assertEquals(38, codes.distinct().size)
        assertTrue(codes.contains("PD")) // Southwest Papua is its own province
        for (region in list) {
            assertTrue(region.getString("name").isNotBlank())
            assertTrue(region.getString("name_en").isNotBlank())
            val rings = region.getJSONArray("rings")
            assertTrue(rings.length() > 0)
            for (i in 0 until rings.length()) {
                val ring = rings.getJSONArray(i)
                assertTrue(ring.length() >= 3)
                for (j in 0 until ring.length()) {
                    val p = ring.getJSONArray(j)
                    assertTrue(p.getDouble(0) in -180.0..180.0)
                    assertTrue(p.getDouble(1) in -90.0..90.0)
                }
            }
        }
    }

    @Test
    fun `key geographic locations fall in their respective provinces`() {
        val byCode = regions().associateBy { it.getString("code") }
        fun check(code: String, lon: Double, lat: Double) {
            val region = polygon(byCode.getValue(code))
            assertTrue("$code misses ($lat, $lon)", AwardHitTest.contains(region, lat, lon))
        }
        check("AC", 95.324, 5.548)     // Banda Aceh
        check("JK", 106.845, -6.214)   // Jakarta
        check("BA", 115.211, -8.650)   // Denpasar
        check("YO", 110.369, -7.800)   // Yogyakarta
        check("PB", 134.083, -0.860)   // Manokwari (West Papua)
        check("PD", 131.252, -0.876)   // Sorong (Southwest Papua)
        assertFalse("Sorong must not count as West Papua", AwardHitTest.contains(
            polygon(byCode.getValue("PB")), -0.876, 131.252
        ))
    }
}
