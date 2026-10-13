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
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NominalTransceiverTest {

    @Test
    fun `the voice repeater wins over a linear entry listed first`() {
        val radios = listOf(
            radio("lin", "Mode U/V Linear", uplink = 435_100_000L, downlink = 145_950_000L, mode = "USB"),
            radio("fm", "Mode V/U FM Voice CTCSS 67.0 Hz", uplink = 145_850_000L, downlink = 436_795_000L)
        )
        assertEquals("fm", radios.nominalTransceiver()?.uuid)
    }

    @Test
    fun `a linear bird falls back to the first entry with both bands`() {
        val radios = listOf(
            radio("beacon", "Beacon", uplink = null, downlink = 145_950_000L, mode = "CW"),
            radio("vu", "Mode V/U Linear", uplink = 145_950_000L, downlink = 435_600_000L, mode = "USB")
        )
        assertEquals("vu", radios.nominalTransceiver()?.uuid)
        assertEquals(145_950_000L, radios.nominalTransceiver()?.uplinkHz())
        assertEquals(435_600_000L, radios.nominalTransceiver()?.downlinkHz())
    }

    @Test
    fun `a published passband yields its middle`() {
        val radios = listOf(
            radio(
                "vu", "Mode V/U Linear", uplink = 435_000_000L, downlink = 145_900_000L,
                uplinkHigh = 435_300_000L, downlinkHigh = 146_000_000L, mode = "USB"
            )
        )
        assertEquals(435_150_000L, radios.nominalTransceiver()?.uplinkHz())
        assertEquals(145_950_000L, radios.nominalTransceiver()?.downlinkHz())
    }

    @Test
    fun `a stale alive flag does not stop the prefill`() {
        val radios = listOf(
            radio("ao7", "Mode U/V Linear", uplink = 432_150_000L, downlink = 145_950_000L, isAlive = false, mode = "USB")
        )
        assertEquals("ao7", radios.nominalTransceiver()?.uuid)
    }

    @Test
    fun `no transceiver with both bands yields null`() {
        val radios = listOf(
            radio("beacon", "Beacon", uplink = null, downlink = 145_950_000L, mode = "CW"),
            radio("vu", "Mode V/U Linear", uplink = null, downlink = 435_600_000L, mode = "USB")
        )
        assertNull(radios.nominalTransceiver())
    }

    @Test
    fun `the catnum lookup matches through the alias table`() {
        val names = mapOf(
            27607 to "SAUDISAT 1C",
            7530 to "AO-7 (AMSAT-OSCAR 7)",
            25544 to "ISS (ZARYA)"
        )
        assertEquals(27607, names.catNumForName("SO-50"))
        assertEquals(7530, names.catNumForName("AO-7"))
        assertEquals(25544, names.catNumForName("ARISS"))
        assertNull(names.catNumForName("NOT A SATELLITE"))
    }

    private fun radio(
        uuid: String,
        info: String,
        uplink: Long?,
        downlink: Long?,
        isAlive: Boolean = true,
        uplinkHigh: Long? = null,
        downlinkHigh: Long? = null,
        mode: String = "FM"
    ) = SatRadio(
        uuid = uuid,
        info = info,
        isAlive = isAlive,
        downlinkLow = downlink,
        downlinkHigh = downlinkHigh,
        downlinkMode = mode,
        uplinkLow = uplink,
        uplinkHigh = uplinkHigh,
        uplinkMode = mode,
        isInverted = false,
        catnum = 1
    )
}
