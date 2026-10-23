package com.rtbishop.look4sat.core.domain.repository

import org.junit.Assert.*
import org.junit.Test

class CwMessageTemplateTest {
    private val values = CwTemplateValues("BG5JSU/P", "BA7OPF", "579", "PM01AB")

    @Test fun normalizesTemplatesWithoutChangingLegacyText() {
        assertEquals("CQ {CALL} DE {MYCALL}", CwTransmitPresets.normalize(" cq  {call} de {mycall} "))
        assertEquals("CQ DE BA7OPF K", CwTransmitPresets.normalize("cq de ba7opf k"))
        assertEquals("", CwTransmitPresets.normalize("  "))
    }

    @Test fun expandsAllFourFieldsAndPortableCallsigns() {
        assertEquals("BG5JSU/P DE BA7OPF UR 579 PM01AB K",
            CwMessageTemplate.expand("{CALL} DE {MYCALL} UR {RST} {GRID} K", values))
    }

    @Test fun repeatedVariablesAndAdjacentPunctuationWork() {
        assertEquals("BG5JSU/P? BG5JSU/P", CwMessageTemplate.expand("{CALL}? {CALL}", values))
    }

    @Test fun onlyVariablesUsedByThisPresetAreRequired() {
        assertEquals("599", CwMessageTemplate.expand("{RST}", CwTemplateValues()))
        assertEquals("CQ CQ", CwMessageTemplate.expand("CQ CQ", CwTemplateValues()))
    }

    @Test fun missingAndUnknownVariablesAreIdentifiable() {
        val missing = assertThrows(CwTemplateException::class.java) {
            CwMessageTemplate.expand("{CALL} DE {MYCALL}", CwTemplateValues(myCallsign = "BA7OPF"))
        }
        assertEquals("CALL", missing.variable)
        assertTrue(missing.missing)
        val unknown = assertThrows(CwTemplateException::class.java) { CwMessageTemplate.normalize("{SAT}") }
        assertEquals("SAT", unknown.variable)
        assertFalse(unknown.missing)
    }

    @Test fun malformedBracesInvalidCharactersAndOversizedTemplatesAreRejected() {
        listOf("{CALL", "CALL}", "{{CALL}}", "你好", "A".repeat(4097)).forEach {
            assertThrows(IllegalArgumentException::class.java) { CwMessageTemplate.normalize(it) }
        }
    }

    @Test fun expansionIsASnapshotAndNeverMutatesTheSavedTemplate() {
        val preset = "{CALL} DE {MYCALL}"
        val draft = CwMessageTemplate.expand(preset, values)
        assertEquals("BG5JSU/P DE BA7OPF", draft)
        assertEquals("{CALL} DE {MYCALL}", preset)
        assertEquals("F4HWN DE BA7OPF", CwMessageTemplate.expand(preset, values.copy(callsign = "F4HWN")))
        assertEquals("BG5JSU/P DE BA7OPF", draft)
    }

    @Test fun unexpandedTemplatesNeverPassTheRadioTextValidator() {
        assertThrows(IllegalArgumentException::class.java) { CwTransmitText.chunks("{CALL} DE {MYCALL}", 92) }
        assertEquals(listOf("BG5JSU/P DE BA7OPF"), CwTransmitText.chunks(CwMessageTemplate.expand("{CALL} DE {MYCALL}", values), 92))
    }

    @Test fun expandedValuesAreAlsoValidatedAsRadioText() {
        assertThrows(IllegalArgumentException::class.java) {
            CwMessageTemplate.expand("{CALL}", values.copy(callsign = "{MYCALL}"))
        }
    }
}
