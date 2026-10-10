package com.rtbishop.look4sat.core.domain.repository

import org.junit.Assert.*
import org.junit.Test

class CwTransmitTextTest {
    @Test fun normalizeAndSplitAtWordBoundaries() {
        assertEquals("CQ DE BA7OPF",CwTransmitText.normalize("cq\n de  ba7opf"))
        assertEquals(listOf("CQ DE","BA7OPF"),CwTransmitText.chunks("cq de ba7opf",6))
    }
    @Test fun rejectUnknownMorseCharactersAndEmptyMessages() {
        assertThrows(IllegalArgumentException::class.java) { CwTransmitText.normalize("你好") }
        assertThrows(IllegalArgumentException::class.java) { CwTransmitText.normalize(" \n ") }
    }
    @Test fun boundThePhoneQueue() {
        assertThrows(IllegalArgumentException::class.java) { CwTransmitText.normalize("A".repeat(4097)) }
    }
}
