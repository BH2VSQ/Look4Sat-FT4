package com.rtbishop.look4sat.core.domain.logbook

import com.rtbishop.look4sat.core.domain.model.AwardCalculator
import com.rtbishop.look4sat.core.domain.model.AwardProgress
import com.rtbishop.look4sat.core.domain.model.GridQso
import java.util.Locale

data class ConfirmedGridStore(
    val qsosByGrid: Map<String, List<GridQso>>,
    val awards: List<AwardProgress>
)

fun confirmedGridStore(records: List<QsoRecord>): ConfirmedGridStore {
    val byGrid = linkedMapOf<String, MutableList<GridQso>>()
    val all = records.filter { it.lotwConfirmed && it.isSatellite && it.status == QsoStatus.COMPLETE }.map { record ->
        val theirGrids = (listOf(record.theirGrid) + record.theirVuccGrids)
            .map { it.trim().uppercase(Locale.US).take(4) }
            .filter { it.matches(Regex("[A-R]{2}[0-9]{2}")) }
            .distinct()
        val myGrids = (listOf(record.myGrid) + record.vuccGrids)
            .map { it.trim().uppercase(Locale.US).take(4) }
            .filter { it.matches(Regex("[A-R]{2}[0-9]{2}")) }
            .toSet()
        val qso = GridQso(
            call = record.theirCallsign,
            epochMs = record.startUtcMillis,
            satName = record.satelliteName,
            mode = record.displayMode,
            bandUp = record.band.ifBlank { frequencyBand(record.txFrequencyHz) },
            bandDown = record.rxBand.ifBlank { frequencyBand(record.rxFrequencyHz) },
            dxcc = record.dxcc,
            country = record.country.ifBlank { null },
            cqz = record.cqZone,
            state = record.region.ifBlank { null },
            myGrid = myGrids.firstOrNull(),
            myGrids = myGrids,
            theirGrids = theirGrids,
            iota = record.iota.ifBlank { null },
            myIota = record.myIota.ifBlank { null },
            theirFineGrids = record.theirFullGrids.ifEmpty { listOf(record.theirGrid) }
                .filter { it.length >= 6 },
            myFineGrids = record.myFullGrids.ifEmpty { listOf(record.myGrid) }
                .filter { it.length >= 6 }
        )
        theirGrids.forEach { grid -> byGrid.getOrPut(grid) { mutableListOf() }.add(qso) }
        qso
    }
    // DXCC / zones / regions still count when a confirmed satellite QSO has no locator.
    return ConfirmedGridStore(byGrid, AwardCalculator.calculate(byGrid, all = all))
}
