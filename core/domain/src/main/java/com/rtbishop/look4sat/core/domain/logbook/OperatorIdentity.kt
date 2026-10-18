/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.domain.logbook

import java.util.Locale

/**
 * Own (operator) identity written into the records this app logs.
 *
 * An imported LoTW certificate is the authoritative source — it is the signing identity — so
 * wherever one exists its callsign wins. A Wavelog-only setup has no certificate at all, and
 * without the fallback every locally logged record would keep an empty own callsign: the ADIF
 * export would lose `STATION_CALLSIGN`, the posted summary its "TNX de …" line, the map's
 * [com.rtbishop.look4sat.core.domain.model.GridQso] its operator callsign, and — because the
 * dedupe key contains the own callsign — a contact pulled back from Wavelog (where the server
 * stamps the station profile's callsign) would no longer match its local row and would appear
 * twice in the logbook.
 */
fun operatorCallsign(certificateCallsign: String, wavelogCallsign: String): String =
    certificateCallsign.ifBlank { wavelogCallsign }.trim().uppercase(Locale.US)

/**
 * Station grids a record would be logged under, in the same precedence order: the LoTW station
 * location first, the selected Wavelog station profile as the fallback. Either side may hold
 * several gridsquares comma-separated (a location on a line or a corner), so the result is the
 * split list — the caller picks the primary one where a single grid is needed.
 */
fun operatorGrids(lotwStationGrid: String, wavelogStationGrid: String): List<String> =
    lotwStationGrid.ifBlank { wavelogStationGrid }
        .split(',')
        .map { it.trim().uppercase(Locale.US) }
        .filter(String::isNotEmpty)
