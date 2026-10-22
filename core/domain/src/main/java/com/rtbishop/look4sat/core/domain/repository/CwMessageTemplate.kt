package com.rtbishop.look4sat.core.domain.repository

import java.util.Locale

data class CwTemplateValues(
    val callsign: String = "",
    val myCallsign: String = "",
    val sentReport: String = "599",
    val myGrid: String = ""
)

class CwTemplateException(val variable: String, val missing: Boolean) :
    IllegalArgumentException(if (missing) "Missing $variable" else "Unknown CW variable {$variable}")

object CwMessageTemplate {
    private val token = Regex("\\{([^{}]*)}")
    val variables = listOf("CALL", "MYCALL", "RST", "GRID")

    fun normalize(text: String): String {
        val normalized = text.uppercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty()) return ""
        require(normalized.length <= 4096) { "CW preset exceeds 4096 characters" }
        val literal = token.replace(normalized) { match ->
            val name = match.groupValues[1]
            if (name !in variables) throw CwTemplateException(name, missing = false)
            "A"
        }
        CwTransmitText.normalize(literal)
        return normalized
    }

    fun hasVariables(text: String): Boolean = token.containsMatchIn(text)

    fun expand(text: String, values: CwTemplateValues): String {
        val fields = mapOf("CALL" to values.callsign, "MYCALL" to values.myCallsign,
            "RST" to values.sentReport, "GRID" to values.myGrid)
        val expanded = token.replace(normalize(text)) { match ->
            val name = match.groupValues[1]
            val value = fields.getValue(name).trim()
            if (value.isEmpty()) throw CwTemplateException(name, missing = true)
            CwTransmitText.normalize(value)
        }
        return if (expanded.isBlank()) "" else CwTransmitText.normalize(expanded)
    }
}
