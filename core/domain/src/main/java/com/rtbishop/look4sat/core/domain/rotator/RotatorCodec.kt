/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * Rotator protocol command formats and parsing behavior are adapted from
 * OrbitDeckiOS. Copyright (c) 2025 Paul Stoetzer, N8HM. Licensed under the
 * MIT License. See THIRD_PARTY_NOTICES.md.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.rotator

import java.util.Locale
import kotlin.math.roundToInt

object RotatorCodec {

    fun point(
        protocol: RotatorProtocol,
        position: RotatorPosition,
        customTemplate: String = "",
        spidAzimuthResolution: Int = 1,
        spidElevationResolution: Int = 1
    ): ByteArray {
        require(position.azimuthDegrees.isFinite() && position.elevationDegrees.isFinite()) {
            "Rotator position must be finite"
        }
        val azimuth = position.azimuthDegrees
        val elevation = position.elevationDegrees
        return when (protocol) {
            RotatorProtocol.GS232 -> {
                val az = normalizeGs232Azimuth(azimuth).roundToInt()
                val el = elevation.coerceIn(0.0, 180.0).roundToInt()
                ascii(String.format(Locale.US, "W%03d %03d\r", az, el))
            }
            RotatorProtocol.EASYCOMM_I -> {
                val az = normalize360(azimuth)
                val el = elevation.coerceIn(0.0, 180.0)
                ascii(String.format(Locale.US, "AZ%.1f EL%.1f UP000 XXX DN000 XXX\n", az, el))
            }
            RotatorProtocol.EASYCOMM_II, RotatorProtocol.EASYCOMM_III -> {
                val az = normalize360(azimuth)
                val el = elevation.coerceIn(0.0, 180.0)
                ascii(String.format(Locale.US, "AZ%.1f EL%.1f\n", az, el))
            }
            RotatorProtocol.SPID_ROT2PROG -> {
                val az = normalize360(azimuth)
                val el = elevation.coerceIn(0.0, 180.0)
                spidFrame(command = SPID_SET, azimuth = az, elevation = el,
                    azimuthResolution = spidAzimuthResolution, elevationResolution = spidElevationResolution)
            }
            RotatorProtocol.SAEBRTRACK -> {
                val az = normalize360(azimuth).roundToInt()
                val el = elevation.coerceIn(0.0, 180.0).roundToInt()
                ascii(String.format(Locale.US, "AZ%03dEL%03d\n", az, el))
            }
            RotatorProtocol.ROTCTLD -> {
                val el = elevation.coerceAtLeast(0.0)
                ascii(String.format(Locale.US, "P %.1f %.1f\n", azimuth, el))
            }
            RotatorProtocol.PST_ROTATOR -> {
                val az = normalize360(azimuth)
                val el = elevation.coerceAtLeast(0.0)
                ascii(
                    String.format(
                        Locale.US,
                        "<PST><AZIMUTH>%.1f</AZIMUTH><ELEVATION>%.1f</ELEVATION></PST>",
                        az,
                        el
                    )
                )
            }
            RotatorProtocol.OZ9AAR_URC -> {
                val az = normalize360(azimuth)
                val el = elevation.coerceAtLeast(0.0)
                ascii(String.format(Locale.US, "{\"GOTO\":[%.1f,%.1f]}", az, el))
            }
            RotatorProtocol.CUSTOM_TEMPLATE -> ascii(
                customTemplate
                    .replace("\$AZ", compactDecimal(azimuth))
                    .replace("\$EL", compactDecimal(elevation.coerceAtLeast(0.0)))
                    .unescapeControlCharacters()
            )
        }
    }

    fun stop(protocol: RotatorProtocol, customTemplate: String = ""): ByteArray? = when (protocol) {
        RotatorProtocol.GS232 -> ascii("S\r")
        RotatorProtocol.EASYCOMM_I,
        RotatorProtocol.EASYCOMM_II,
        RotatorProtocol.EASYCOMM_III -> ascii("SA SE \n")
        RotatorProtocol.SPID_ROT2PROG -> spidFrame(command = SPID_STOP)
        RotatorProtocol.SAEBRTRACK, RotatorProtocol.OZ9AAR_URC -> null
        RotatorProtocol.ROTCTLD -> ascii("S\n")
        RotatorProtocol.PST_ROTATOR -> ascii("<PST><STOP>1</STOP></PST>")
        RotatorProtocol.CUSTOM_TEMPLATE -> customTemplate.takeIf(String::isNotBlank)
            ?.unescapeControlCharacters()
            ?.let(::ascii)
    }

    fun positionQuery(protocol: RotatorProtocol, customTemplate: String = ""): ByteArray? = when (protocol) {
        RotatorProtocol.GS232 -> ascii("C2\r")
        RotatorProtocol.EASYCOMM_I -> null
        RotatorProtocol.EASYCOMM_II,
        RotatorProtocol.EASYCOMM_III -> ascii("AZ\n")
        RotatorProtocol.SPID_ROT2PROG -> spidFrame(command = SPID_QUERY)
        RotatorProtocol.SAEBRTRACK, RotatorProtocol.PST_ROTATOR -> null
        RotatorProtocol.ROTCTLD -> ascii("p\n")
        RotatorProtocol.OZ9AAR_URC -> ascii("{\"POLL\"}")
        RotatorProtocol.CUSTOM_TEMPLATE -> customTemplate.takeIf(String::isNotBlank)
            ?.unescapeControlCharacters()
            ?.let(::ascii)
    }

    fun parsePosition(protocol: RotatorProtocol, bytes: ByteArray, customResponseTemplate: String = ""): RotatorPosition? {
        if (bytes.isEmpty()) return null
        return when (protocol) {
            RotatorProtocol.SPID_ROT2PROG -> parseSpidPosition(bytes)
            RotatorProtocol.GS232 -> parseGs232Position(bytes.decodeToString())
            RotatorProtocol.EASYCOMM_I,
            RotatorProtocol.EASYCOMM_II,
            RotatorProtocol.EASYCOMM_III -> parseEasyCommPosition(bytes.decodeToString())
            RotatorProtocol.SAEBRTRACK -> parseSaebrtrackPosition(bytes.decodeToString())
            RotatorProtocol.ROTCTLD -> parseRotctldPosition(bytes.decodeToString())
            RotatorProtocol.PST_ROTATOR -> parsePstRotatorPosition(bytes.decodeToString())
            RotatorProtocol.OZ9AAR_URC -> parseUrcPosition(bytes.decodeToString())
            RotatorProtocol.CUSTOM_TEMPLATE -> parseCustomPosition(bytes.decodeToString(), customResponseTemplate)
        }?.takeIf { it.azimuthDegrees.isFinite() && it.elevationDegrees.isFinite() &&
            it.azimuthDegrees in -360.0..450.0 && it.elevationDegrees in -90.0..180.0 }
    }

    fun parseEasyCommAxis(text: String, azimuth: Boolean): Double? {
        val prefix = if (azimuth) "AZ" else "EL"
        return Regex("^$prefix\\s*=?\\s*$NUMBER$", RegexOption.IGNORE_CASE)
            .matchEntire(text.trim())?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf {
                it.isFinite() && if (azimuth) it in -360.0..450.0 else it in -90.0..180.0
            }
    }

    fun validResponseTemplate(template: String): Boolean = template.length <= 256 &&
        Regex("\\\$AZ").findAll(template).count() == 1 && Regex("\\\$EL").findAll(template).count() == 1

    private fun parseCustomPosition(text: String, template: String): RotatorPosition? {
        if (!validResponseTemplate(template)) return null
        val format = template.unescapeControlCharacters()
        val tokens = Regex("\\\$AZ|\\\$EL").findAll(format).toList()
        var offset = 0
        val pattern = buildString {
            for (token in tokens) {
                append(Regex.escape(format.substring(offset, token.range.first)))
                append(NUMBER)
                offset = token.range.last + 1
            }
            append(Regex.escape(format.substring(offset)))
        }
        val values = Regex(pattern).find(text)?.groupValues ?: return null
        return if (tokens[0].value == "\$AZ") position(values[1], values[2]) else position(values[2], values[1])
    }

    private fun parseGs232Position(text: String): RotatorPosition? {
        GS232_B_PATTERN.find(text)?.let { match ->
            return position(match.groupValues[1], match.groupValues[2])
        }
        GS232_A_PATTERN.find(text)?.let { match ->
            val az = signedNumber(match.groupValues[1], match.groupValues[2])
            val el = signedNumber(match.groupValues[3], match.groupValues[4])
            return RotatorPosition(az, el)
        }
        return null
    }

    private fun parseEasyCommPosition(text: String): RotatorPosition? =
        EASYCOMM_PATTERN.find(text)?.let { position(it.groupValues[1], it.groupValues[2]) }

    private fun parseSaebrtrackPosition(text: String): RotatorPosition? =
        SAEBRTRACK_PATTERN.find(text)?.let { position(it.groupValues[1], it.groupValues[2]) }

    private fun parseRotctldPosition(text: String): RotatorPosition? {
        if (ROTCTLD_ERROR_PATTERN.containsMatchIn(text)) return null
        val values = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(String::toDoubleOrNull)
            .take(2)
            .toList()
        return if (values.size == 2) RotatorPosition(values[0], values[1]) else null
    }

    private fun parsePstRotatorPosition(text: String): RotatorPosition? =
        PST_PATTERN.find(text)?.let { position(it.groupValues[1], it.groupValues[2]) }

    private fun parseUrcPosition(text: String): RotatorPosition? {
        val az = URC_AZ_PATTERN.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val el = URC_EL_PATTERN.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return RotatorPosition(az, el)
    }

    private fun parseSpidPosition(bytes: ByteArray): RotatorPosition? {
        for (start in 0..bytes.size-SPID_REPLY_SIZE) {
            if (unsigned(bytes[start])!=SPID_START || unsigned(bytes[start+11])!=SPID_END) continue
            if ((1..4).any { unsigned(bytes[start+it]) !in 0..9 } ||
                (6..9).any { unsigned(bytes[start+it]) !in 0..9 }) continue
            val azimuthResolution = unsigned(bytes[start+5])
            val elevationResolution = unsigned(bytes[start+10])
            if (azimuthResolution !in 1..100 || elevationResolution !in 1..100) continue
            return RotatorPosition(spidDigits(bytes,start+1)/10.0-360.0,
                spidDigits(bytes,start+6)/10.0-360.0)
        }
        return null
    }

    fun spidResolutions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < SPID_REPLY_SIZE) return null
        for (start in 0..bytes.size-SPID_REPLY_SIZE) {
            val frame = bytes.copyOfRange(start,start+SPID_REPLY_SIZE)
            if (parseSpidPosition(frame) != null) return unsigned(frame[5]) to unsigned(frame[10])
        }
        return null
    }

    private fun spidFrame(
        command: Int,
        azimuth: Double = 0.0,
        elevation: Double = 0.0,
        azimuthResolution: Int = 1,
        elevationResolution: Int = 1
    ): ByteArray {
        val azimuthDigits = if (command == SPID_SET) spidDigits(azimuth,azimuthResolution) else IntArray(4)
        val elevationDigits = if (command == SPID_SET) spidDigits(elevation,elevationResolution) else IntArray(4)
        return byteArrayOf(
            SPID_START.toByte(),
            azimuthDigits[0].toByte(),
            azimuthDigits[1].toByte(),
            azimuthDigits[2].toByte(),
            azimuthDigits[3].toByte(),
            (if (command == SPID_SET) azimuthResolution else 0).toByte(),
            elevationDigits[0].toByte(),
            elevationDigits[1].toByte(),
            elevationDigits[2].toByte(),
            elevationDigits[3].toByte(),
            (if (command == SPID_SET) elevationResolution else 0).toByte(),
            command.toByte(),
            SPID_END.toByte()
        )
    }

    private fun spidDigits(degrees: Double, resolution: Int): IntArray {
        require(resolution in 1..100) { "Invalid SPID resolution" }
        val value = ((degrees + 360.0) * resolution).roundToInt()
        require(value in 0..9999) { "SPID pulse count is outside the four-digit range" }
        return intArrayOf(
            0x30 + value / 1000 % 10,
            0x30 + value / 100 % 10,
            0x30 + value / 10 % 10,
            0x30 + value % 10
        )
    }

    private fun spidDigits(bytes: ByteArray, offset: Int): Int =
        unsigned(bytes[offset]) * 1000 +
            unsigned(bytes[offset + 1]) * 100 +
            unsigned(bytes[offset + 2]) * 10 +
            unsigned(bytes[offset + 3])

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    private fun position(azimuth: String, elevation: String): RotatorPosition? {
        val az = azimuth.toDoubleOrNull() ?: return null
        val el = elevation.toDoubleOrNull() ?: return null
        return RotatorPosition(az, el)
    }

    private fun signedNumber(sign: String, digits: String): Double {
        val value = digits.toDouble()
        return if (sign == "-") -value else value
    }

    private fun normalizeGs232Azimuth(azimuth: Double): Double = when {
        azimuth < 0.0 -> normalize360(azimuth)
        azimuth > 450.0 -> 450.0
        else -> azimuth
    }

    private fun normalize360(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    private fun compactDecimal(value: Double): String {
        val rounded = String.format(Locale.US, "%.2f", value)
        return rounded.trimEnd('0').trimEnd('.')
    }

    private fun String.unescapeControlCharacters(): String =
        replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t")

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private val NUMBER = "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))"
    private val GS232_B_PATTERN = Regex("AZ\\s*=\\s*$NUMBER\\s*EL\\s*=\\s*$NUMBER", RegexOption.IGNORE_CASE)
    private val GS232_A_PATTERN = Regex("([+-])0?(\\d{3})([+-])0?(\\d{3})")
    private val EASYCOMM_PATTERN = Regex("AZ\\s*=?\\s*$NUMBER\\s+EL\\s*=?\\s*$NUMBER", RegexOption.IGNORE_CASE)
    private val SAEBRTRACK_PATTERN = Regex("AZ\\s*$NUMBER\\s*EL\\s*$NUMBER", RegexOption.IGNORE_CASE)
    private val ROTCTLD_ERROR_PATTERN = Regex("RPRT\\s+-[0-9]+", RegexOption.IGNORE_CASE)
    private val PST_PATTERN = Regex("AZ:\\s*$NUMBER.*?EL:\\s*$NUMBER", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val URC_AZ_PATTERN = Regex("\"(?:AZ|az)\"\\s*:\\s*$NUMBER")
    private val URC_EL_PATTERN = Regex("\"(?:EL|el)\"\\s*:\\s*$NUMBER")

    private const val SPID_START = 0x57
    private const val SPID_END = 0x20
    private const val SPID_SET = 0x2F
    private const val SPID_QUERY = 0x1F
    private const val SPID_STOP = 0x0F
    private const val SPID_REPLY_SIZE = 12
    private const val SPID_FRAME_SIZE = 13
}
