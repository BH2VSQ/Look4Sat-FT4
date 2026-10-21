/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.model

import java.util.Locale

/**
 * Selected Indonesian IOTA groups whose constituent islands belong to ONE
 * current province (38-province administrative scheme). Do not assign a
 * whole island shared by provinces: OC-021 (Java), OC-034 (New Guinea),
 * OC-088 (Borneo), OC-143 (Sumatra), OC-146 (Sulawesi) etc.
 *
 * References (island-group names, NOT province names):
 *  https://iihg.net/yb-iota-ref-prefix/
 *  https://www.iota-world.org/islands-on-the-air/iota-groups-islands
 *
 * IOTA is last-mile geolocation help, NOT a substitute for STATE or an
 * exact locator. Where modern boundaries or island group coverage are
 * uncertain, deliberately leave the IOTA unmapped.
 */
object WaipaIotaLookup {
    private val provinceByIota: Map<String, String> = mapOf(
        // Java's province-specific offshore islands
        "OC-177" to "JK", // Seribu Islands, Jakarta
        "OC-186" to "JT", // Karimunjawa, Central Java
        "OC-197" to "JI", // Bawean, East Java
        "OC-217" to "JI", // Kangean, East Java
        "OC-250" to "JI", // Masalembu, East Java

        // Sumatra and nearby islands
        "OC-144" to "BB", // Bangka / Belitung
        "OC-204" to "BE", // Enggano, Bengkulu
        "OC-075" to "KR", // Riau Islands
        "OC-106" to "KR", // Natuna Besar
        "OC-107" to "KR", // Lingga
        "OC-108" to "KR", // Anambas
        "OC-109" to "KR", // Natuna Selatan
        "OC-122" to "KR", // Tambelan
        "OC-215" to "SB", // Mentawai, West Sumatra
        "OC-161" to "SU", // Nias/Batu, North Sumatra
        "OC-270" to "AC", // Simeulue / Banyak, Aceh

        // Kalimantan islands, excludes OC-166 (covers multiple provinces)
        "OC-252" to "KB", // western coastal islands, West Kalimantan
        "OC-269" to "KB", // Karimata, West Kalimantan
        "OC-268" to "KS", // Laut Kecil Islands, South Kalimantan

        // Sulawesi islands
        "OC-208" to "ST", // Banggai Islands, Central Sulawesi
        "OC-213" to "ST", // Togian, Central Sulawesi
        "OC-209" to "SA", // Talaud, North Sulawesi
        "OC-210" to "SA", // Sangihe, North Sulawesi
        "OC-219" to "SG", // Tukangbesi/Wakatobi, Southeast Sulawesi

        // Maluku islands
        "OC-070" to "MA", // Seram
        "OC-157" to "MA", // Banda
        "OC-221" to "MA", // Kai
        "OC-224" to "MA", // Tanimbar
        "OC-246" to "MA", // Leti / Sermata
        "OC-249" to "MA", // Aru
        "OC-271" to "MA", // Babar
        "OC-272" to "MA", // Barat Daya
        "OC-273" to "MA", // Gorong / Watubela
        "OC-274" to "MA", // Lucipara / Penyu
        "OC-076" to "MU", // Sula
        "OC-145" to "MU", // Halmahera
        "OC-222" to "MU", // Obi

        // Lesser Sundas
        "OC-022" to "BA", // Bali
        "OC-150" to "NB", // Lombok/Sumbawa
        "OC-151" to "NT", // Flores/Alor/Sumba/etc.
        "OC-241" to "NT"  // West Timor's offshore islands
    )

    fun province(iota: String?): String? {
        val code = iota?.trim()?.uppercase(Locale.US) ?: return null
        return provinceByIota[code]
    }
}
