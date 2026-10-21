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

import java.util.Locale

/**
 * Offline WAIPA province lookup using the SAME 38 provincial polygons as the
 * map overlay. A QSO's Maidenhead locator represents its operating position,
 * unlike an Indonesian home callsign prefix.
 *
 * 6-char locators identify a small cell: sample its center and neighbouring
 * points, refusing province crossings. For legacy 4-char-only cached QSOs,
 * credit a province only when ALL samples agree; otherwise the award logic
 * can fall back to the conservative IIHG callsign allocation table.
 *
 * Never use the touch hit-test's enlarged targets / archipelago envelopes for
 * geolocation: they are UI conveniences, not administrative boundaries.
 */
internal class WaipaGridResolver(regions: List<AwardRegion>) : (String) -> String? {
    private data class BoundedRegion(
        val region: AwardRegion,
        val minLon: Double,
        val maxLon: Double,
        val minLat: Double,
        val maxLat: Double
    )

    private data class GridCell(
        val lon: Double,
        val lat: Double,
        val width: Double,
        val height: Double,
        val precise: Boolean
    )

    private val bounded = regions.mapNotNull { region ->
        val points = region.rings.flatten()
        if (points.isEmpty()) null else BoundedRegion(
            region,
            points.minOf { it[0] }, points.maxOf { it[0] },
            points.minOf { it[1] }, points.maxOf { it[1] }
        )
    }
    private val cache = mutableMapOf<String, String?>()

    override fun invoke(grid: String): String? {
        val normalized = grid.trim().uppercase(Locale.US)
        if (cache.containsKey(normalized)) return cache[normalized]
        val matched = decode(normalized)?.let(::province)
        cache[normalized] = matched
        return matched
    }

    private fun decode(grid: String): GridCell? {
        if (grid.length != 4 && grid.length != 6) return null
        if (grid[0] !in 'A'..'R' || grid[1] !in 'A'..'R' ||
            grid[2] !in '0'..'9' || grid[3] !in '0'..'9'
        ) return null
        var lon = -180.0 + (grid[0] - 'A') * 20 + (grid[2] - '0') * 2
        var lat = -90.0 + (grid[1] - 'A') * 10 + (grid[3] - '0')
        if (grid.length == 4) return GridCell(lon, lat, 2.0, 1.0, precise = false)
        if (grid[4] !in 'A'..'X' || grid[5] !in 'A'..'X') return null
        lon += (grid[4] - 'A') / 12.0
        lat += (grid[5] - 'A') / 24.0
        return GridCell(lon, lat, 1.0 / 12.0, 1.0 / 24.0, precise = true)
    }

    private fun province(cell: GridCell): String? {
        val fractions = listOf(0.1, 0.5, 0.9)
        val hits = fractions.flatMap { yy ->
            fractions.map { xx ->
                val lon = cell.lon + cell.width * xx
                val lat = cell.lat + cell.height * yy
                bounded.asSequence()
                    .filter { lon in it.minLon..it.maxLon && lat in it.minLat..it.maxLat }
                    .filter { AwardHitTest.contains(it.region, lat, lon) }
                    .map { it.region.code }.toSet()
            }
        }
        val possible = hits.flatten().toSet()
        if (possible.size != 1) return null
        val code = possible.single()
        if (cell.precise) {
            // Center-in-land is best. On island shores, at least 3 of 9
            // samples inside the same region allow a coastal-grid credit.
            if (code !in hits[4] && hits.count { code in it } < 3) return null
        } else {
            // Never assign a 2-degree-wide grid merely by its center.
            if (!hits.all { it.size == 1 && code in it }) return null
        }
        return code
    }
}
