package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.rotator.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RotatorProtocolClientTest {
    @Test fun rotctldRequiresZeroAckAndReportsNegativeAck()=runTest {
        for(code in listOf(0,-1,-11)) {
            val link=ReplyLink {"RPRT $code\n"}
            val client=client()
            val result=runCatching {client.point(link,RotatorSettings(),RotatorPosition(100.0,30.0)) {}}
            if(code==0)assertEquals(true,result.getOrThrow())
            else assertEquals(RotatorFailureKind.REJECTED,(result.exceptionOrNull() as RotatorProtocolFailure).kind)
        }
    }

    @Test fun absentAckDoesNotCountAsAcceptance()=runTest {
        val failure=runCatching {client().point(ReplyLink {""},RotatorSettings(),RotatorPosition(100.0,30.0)) {}}.exceptionOrNull()
        assertEquals(RotatorFailureKind.TIMEOUT,(failure as RotatorProtocolFailure).kind)
        assertTrue(failure.reconnect)
    }

    @Test fun customQueryAndBoundedResponseTemplateAreActuallyUsed()=runTest {
        val link=ReplyLink {"POS EL=20.0 AZ=90.0\r\n"}
        val settings=RotatorSettings(protocol=RotatorProtocol.CUSTOM_TEMPLATE,
            customQueryTemplate="?\\n",customResponseTemplate="POS EL=\$EL AZ=\$AZ\\r\\n")
        assertTrue(settings.supportsPositionFeedback)
        assertEquals(RotatorPosition(90.0,20.0),client().position(link,settings))
        assertEquals("?\n",link.commands.single())
    }

    @Test fun easycommQueriesAxesSeparatelyAndIgnoresQueryEchoes()=runTest {
        val link=ReplyLink {command->if(command=="AZ\n")"AZ\nAZ90.0\n" else "EL\nEL20.0\n"}
        assertEquals(RotatorPosition(90.0,20.0),client().position(link,RotatorSettings(protocol=RotatorProtocol.EASYCOMM_II)))
        assertEquals(listOf("AZ\n","EL\n"),link.commands)
    }

    @Test fun slowFragmentsAreNotSplitByAnEightyMillisecondQuietGap()=runTest {
        var time=0L
        val pending=ArrayDeque<Byte>()
        var readyAt=Long.MAX_VALUE
        val link=object:ControlTransport {
            override val isConnected=true
            override suspend fun connect()=true
            override suspend fun disconnect()=Unit
            override suspend fun write(bytes:ByteArray):Boolean {
                pending.addAll("90.0\n".encodeToByteArray().toList());readyAt=time+160;return true
            }
            override suspend fun readAvailable(maxBytes:Int):ByteArray {
                if(pending.isNotEmpty())return ByteArray(pending.size){pending.removeFirst()}
                if(time>=readyAt){readyAt=Long.MAX_VALUE;return "20.0\n".encodeToByteArray()}
                return byteArrayOf()
            }
        }
        val client=RotatorProtocolClient(ControlResponseAccumulator({time},{time+=it}))
        assertEquals(RotatorPosition(90.0,20.0),client.position(link,RotatorSettings()))
        assertTrue(time>=160)
    }

    private fun client():RotatorProtocolClient {
        var time=0L
        return RotatorProtocolClient(ControlResponseAccumulator({time},{time+=it}))
    }
    private class ReplyLink(private val reply:(String)->String):ControlTransport {
        override val isConnected=true
        val commands=mutableListOf<String>()
        private val input=ArrayDeque<Byte>()
        override suspend fun connect()=true
        override suspend fun disconnect()=Unit
        override suspend fun write(bytes:ByteArray):Boolean {
            val command=bytes.decodeToString();commands+=command;input.addAll(reply(command).encodeToByteArray().toList());return true
        }
        override suspend fun readAvailable(maxBytes:Int)=ByteArray(minOf(maxBytes,2,input.size)){input.removeFirst()}
    }
}
