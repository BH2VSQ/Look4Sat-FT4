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

import com.rtbishop.look4sat.core.domain.logbook.satelliteIdentity
import com.rtbishop.look4sat.core.domain.model.SatRadio

/**
 * The transceiver a contact on this satellite is logged through: the FM voice repeater when the
 * bird carries one (the radar Log tab logs through it too), else the first entry publishing both
 * bands. The second case is what gives a linear satellite — AO-7, RS-44 — a starting pair; the
 * operator edits the band when the bird happens to be worked the other way round.
 *
 * Dead entries are not skipped: a stale alive flag (AO-7 while its battery is flat) must not stop
 * the prefill, and the operator can always overwrite what gets filled in.
 */
fun List<SatRadio>.nominalTransceiver(): SatRadio? =
    voiceRepeater() ?: firstOrNull { it.uplinkHz() != null && it.downlinkHz() != null }

/**
 * Catalog number of the entry whose identity matches [name], looked up in a catnum -> name map
 * holding the tracker spellings ("SAUDISAT 1C" is where SO-50 lives).
 */
fun Map<Int, String>.catNumForName(name: String): Int? {
    val wanted = satelliteIdentity(name)
    if (wanted.isBlank()) return null
    return entries.firstOrNull { satelliteIdentity(it.value) == wanted }?.key
}
