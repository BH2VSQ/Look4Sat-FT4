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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.predict

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One [OrbitalObject] keeps its whole prediction workspace in mutable fields (the position and
 * velocity vectors, `julUTC`, `orbitalPos`), and [OrbitalObject.getPosition],
 * [OrbitalObject.getElevation] and [OrbitalObject.getFullPosition] all reset that workspace before
 * computing into it. Two callers predicting the same satellite therefore trample each other: the
 * call that resumes after the other one's reset finishes with the untouched default and reports a
 * sub-satellite point of exactly (0, 0) — longitude 0, latitude 0, which the map then drew as
 * track lines fanning out of the Gulf of Guinea.
 *
 * This is not theoretical: the map, radar and mutual ViewModels plus the tracking service each
 * predict the selected satellite from their own coroutine, and the map itself predicts it from the
 * tick loop, the scrubbed-satellite step and the off-path refresh worker.
 */
class OrbitalObjectConcurrencyTest {

    private fun iss(): OrbitalObject = NearEarthObject(
        OrbitalData(
            name = "ISS (ZARYA)",
            epoch = 24069.23963816,
            meanmo = 15.49756209,
            eccn = 0.0005741,
            incl = 51.6418,
            raan = 90.7424,
            argper = 343.9724,
            meanan = 92.8274,
            catnum = 25544,
            bstar = 0.00025016
        )
    )

    /**
     * An untouched default is the only way to get exactly 0.0/0.0: a propagated sub-satellite point
     * would need both `atan2` and the longitude reduction to land on exact double zeros at once.
     */
    private fun isUntouchedDefault(pos: OrbitalPos) = pos.latitude == 0.0 && pos.longitude == 0.0

    @Test
    fun `predictions of one satellite never return a half-reset position`() {
        val sat = iss()
        val station = GeoPos(23.13, 113.26)
        val base = 1710028800000L
        val corrupted = AtomicInteger()
        val go = CountDownLatch(1)
        val threads = listOf(
            Thread {
                go.await()
                repeat(400) { i ->
                    if (isUntouchedDefault(sat.getPosition(station, base + i * 1_000L))) {
                        corrupted.incrementAndGet()
                    }
                }
            },
            Thread {
                go.await()
                repeat(400) { i ->
                    if (isUntouchedDefault(sat.getFullPosition(station, base + i * 2_000L))) {
                        corrupted.incrementAndGet()
                    }
                }
            },
            Thread {
                go.await()
                repeat(400) { i -> sat.getElevation(station, base + i * 3_000L) }
            }
        )
        threads.forEach { it.start() }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals(
            "concurrent predictions on one satellite returned the reset default",
            0,
            corrupted.get()
        )
    }
}
