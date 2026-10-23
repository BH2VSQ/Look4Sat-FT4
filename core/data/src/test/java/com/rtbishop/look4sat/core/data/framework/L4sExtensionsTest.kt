package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.NativeCwOptions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class L4sExtensionsTest {
    @Test fun cleanupFailureCannotEscapeAsAnUncaughtSyncJobException() = runTest {
        assertFalse(HandsetMacroSync.cancel { throw java.io.IOException("USB disconnected during cancel") })
        assertFalse(HandsetMacroSync.cancel { null })
        assertFalse(HandsetMacroSync.cancel { kotlinx.coroutines.awaitCancellation() })
    }

    @Test fun fourSlotUpdatesPreserveTheUnusedLegacySlotsBeforeAtomicEightSlotCommit() = runTest {
        val writes = mutableListOf<ByteArray>()
        val result = HandsetMacroSync.stage(List(4) { "NEW${it + 1}" }, 45, 8) { data ->
            if (data[0] == 0.toByte() && (data[1].toInt() and 255) in 4..7)
                macroReply(0, data[1].toInt(), "OLD${data[1] + 1}")
            else {
                writes += data
                when (data[0].toInt()) { 3 -> macroReply(2); 0 -> macroReply(0); else -> macroReply(1) }
            }
        }
        assertTrue(result.waitingForConfirmation)
        val slots = writes.filter { it[0] == 2.toByte() }
        assertEquals(8, slots.size)
        for (i in 0..7) {
            assertEquals(if (i < 4) "NEW${i + 1}" else "OLD${i + 1}", slots[i].copyOfRange(3, slots[i].size).toString(Charsets.US_ASCII))
        }
    }

    @Test fun fourSlotCapabilityIsIndependentOfTheFirmwareName() {
        val hello = ByteArray(42)
        "L4S1".toByteArray().copyInto(hello); hello[4] = 3
        L4sProtocol.put32(hello,5,1 or 512 or 2048 or 16384)
        L4sProtocol.put16(hello,9,10); hello[11]=92; hello[12]=10; hello[13]=45
        L4sProtocol.put16(hello,14,1500)
        "v6.2.0-l4s-dev3".toByteArray().copyInto(hello,24)
        val caps=checkNotNull(L4sProtocol.capabilities(hello)).second
        assertTrue(caps.transactionalProfileStore)
        assertTrue(caps.handsetMacros)
        assertEquals(4,caps.handsetMacroSlots)
        L4sProtocol.put32(hello,5,1 or 512 or 2048)
        assertEquals(8,checkNotNull(L4sProtocol.capabilities(hello)).second.handsetMacroSlots)
    }
    @Test fun extendedRepliesSurviveFragmentationAndBadCrc() {
        for(command in L4sProtocol.CW_OPTIONS..L4sProtocol.PASS_INFO) {
            val body=L4sProtocol.body(command or 0x8000,12,7,byteArrayOf(1))
            val wire=L4sProtocol.frame(body)
            val decoder=L4sProtocol.Decoder()
            val replies=(wire+wire).flatMap {decoder.feed(byteArrayOf(it))}.mapNotNull(L4sProtocol::reply)
            assertEquals(2,replies.size);assertEquals(command,replies.first().command)
            val bad=wire.copyOf().also {it[15]=(it[15].toInt() xor 1).toByte()}
            assertEquals(1,L4sProtocol.Decoder().feed(bad+wire).size)
        }
    }

    @Test fun cwOptionsRejectInvalidLimitsAndRetainAllFields() {
        val bytes=byteArrayOf(1,25,70,4,(-56).toByte(),3,12,1)
        val value=checkNotNull(L4sProtocol.cwOptions(bytes))
        assertEquals(NativeCwOptions(25,70,4,200,3,12,true),value)
        for((index,bad) in listOf(0 to 2,1 to 9,2 to 71,3 to 7,4 to 0,5 to 4,6 to 4,7 to 2))
            assertNull(L4sProtocol.cwOptions(bytes.copyOf().also {it[index]=bad.toByte()}))
        assertNull(L4sProtocol.cwOptions(bytes+0))
    }

    @Test fun handsetMacroParserHandlesEightSlotsAndBusyStatusWithoutInventingPresets() {
        for(slot in 0..7) {
            val text="F${slot+1} "+"A".repeat(89)
            val value=checkNotNull(L4sProtocol.macroStatus(macroReply(8,slot,text)))
            assertEquals(text,value.text);assertFalse(value.busy)
        }
        val busy=checkNotNull(L4sProtocol.macroStatus(macroReply(2)))
        assertTrue(busy.waitingForConfirmation);assertEquals(255,busy.slot);assertEquals("",busy.text)
        assertNull(L4sProtocol.macroStatus(macroReply(8,8,"CQ")))
        assertNull(L4sProtocol.macroStatus(macroReply(8,0,"!")))
        assertNull(L4sProtocol.macroStatus(macroReply(8,0,"CQ")+0))
    }

    @Test fun entirePresetBatchIsValidatedBeforeAnyRadioWrite() = runTest {
        var calls=0
        for(presets in listOf(List(3){"CQ"},List(4){if(it==3)"A".repeat(93) else "CQ"},List(4){"!"})) {
            val result=runCatching {HandsetMacroSync.stage(presets,1) {calls++;macroReply(0)}}
            assertTrue(result.isFailure);assertEquals(0,calls)
        }
    }

    @Test fun variablePresetsRejectTheWholeBatchBeforeEvenReadingHandsetStatus() = runTest {
        for (slot in 0..3) {
            var calls = 0
            val result = runCatching {
                HandsetMacroSync.stage(List(4) { if (it == slot) "{CALL} DE {MYCALL}" else "CQ" }, 1) {
                    calls++
                    macroReply(0)
                }
            }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("F${slot + 1}"))
            assertEquals(0, calls)
        }
    }

    @Test fun fullBatchUsesGenerationAndFourSlotsThenWaitsForHandsetConfirmation() = runTest {
        val calls=mutableListOf<ByteArray>()
        val texts=List(4){"CQ F${it+1}"}
        val result=HandsetMacroSync.stage(texts,123,4) { data ->
            calls+=data
            when(data[0].toInt()) {0->macroReply(0);3->macroReply(2);else->macroReply(1)}
        }
        assertTrue(result.waitingForConfirmation);assertEquals(7,calls.size)
        assertEquals(7L,L4sProtocol.u32(calls[1],1));assertEquals(123L,L4sProtocol.u32(calls[1],5))
        for(index in 0..3) {
            val data=calls[index+2]
            assertEquals(index,data[1].toInt());assertEquals(texts[index],data.copyOfRange(3,data.size).toString(Charsets.US_ASCII))
        }
        assertEquals(3,calls.last()[0].toInt())
        assertTrue(calls.none {it[0].toInt() in setOf(4,5)})
    }

    @Test fun busyOrReadOnlyStorageCannotStartAnUpload() = runTest {
        for(status in listOf(macroReply(2),macroReply(0).also {it[8]=1})) {
            var calls=0
            assertTrue(runCatching {HandsetMacroSync.stage(List(4){"CQ"},1) {calls++;status}}.isFailure)
            assertEquals(1,calls)
        }
    }

    private fun macroReply(phase: Int,slot: Int=255,text: String="") = ByteArray(10+text.length).also {
        it[0]=1;it[1]=slot.toByte();it[2]=phase.toByte();L4sProtocol.put32(it,4,7)
        it[9]=text.length.toByte();text.toByteArray(Charsets.US_ASCII).copyInto(it,10)
    }
}
