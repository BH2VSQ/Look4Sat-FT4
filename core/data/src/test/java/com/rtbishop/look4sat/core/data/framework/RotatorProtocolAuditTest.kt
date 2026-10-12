package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.rotator.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Diagnostic simulations document current behavior, including missing confirmation safeguards. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class RotatorProtocolAuditTest(private val protocol: RotatorProtocol) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun protocols(): List<Array<Any>> = RotatorProtocol.entries.map { arrayOf<Any>(it) }
    }

    @Test fun connectedTrackingAutomaticallySendsTheExpectedPointCommand() = runTest {
        val link = AuditRotatorTransport(protocol, repliesEnabled=true)
        val service = fixture(link)
        service.connect(); service.startTracking(auditPass()); runCurrent()
        assertTrue(protocol.name, link.writes.any { it.contentEquals(expectedPoint(protocol)) })
        assertEquals(RotatorTrackingPhase.TRACKING, service.state.value.trackingPhase)
        assertEquals(RotatorPosition(100.0,30.0), service.state.value.commandedPosition)
        service.disconnect()
    }

    @Test fun positionFeedbackIsPublishedOnlyOnImplementedReadPaths() = runTest {
        val link = AuditRotatorTransport(protocol, repliesEnabled=true)
        val service = fixture(link)
        service.connect(); service.startTracking(auditPass()); runCurrent()
        val implemented = protocol.supportsPositionQuery || protocol==RotatorProtocol.PST_ROTATOR
        if(implemented) assertEquals(RotatorPosition(90.0,20.0), service.state.value.reportedPosition)
        else {
            assertNull(service.state.value.reportedPosition)
            assertEquals(0,link.readCalls)
        }
        service.disconnect()
    }

    @Test fun missingFeedbackHoldsMovementAndOneWayProtocolsStayExplicitlyUnconfirmed() = runTest {
        val link = AuditRotatorTransport(protocol, repliesEnabled=false)
        val service = fixture(link)
        service.connect(); service.startTracking(auditPass()); runCurrent()
        assertEquals(RotatorConnectionState.CONNECTED,service.state.value.connectionState)
        if(protocol.supportsPositionQuery || protocol==RotatorProtocol.PST_ROTATOR) {
            assertEquals(RotatorTrackingPhase.HOLDING,service.state.value.trackingPhase)
            assertNull(service.state.value.commandedPosition)
            assertNotNull(service.state.value.errorMessage)
            assertEquals(RotatorFeedbackStatus.STALE,service.state.value.feedbackStatus)
        } else {
            assertNotNull(service.state.value.commandedPosition)
            assertEquals(RotatorCommandStatus.UNCONFIRMED,service.state.value.commandStatus)
        }
        assertNull(service.state.value.reportedPosition)
        service.disconnect()
    }

    @Test fun startTrackingAloneDoesNotConnectOrSend() = runTest {
        val link = AuditRotatorTransport(protocol, repliesEnabled=true)
        val service = fixture(link)
        service.startTracking(auditPass()); runCurrent(); advanceTimeBy(2100); runCurrent()
        assertEquals(0,link.connectCalls)
        assertTrue(link.writes.isEmpty())
        assertEquals(RotatorTrackingPhase.HOLDING,service.state.value.trackingPhase)
        service.disconnect()
    }

    @Test fun changedOrbitAutomaticallySendsANewTargetAndStopEndsTheLoop() = runTest {
        val link = AuditRotatorTransport(protocol, repliesEnabled=true)
        var azimuth=100.0
        val service = fixture(link) { azimuth }
        service.connect(); service.startTracking(auditPass()); runCurrent()
        val count=link.pointWrites
        azimuth=110.0;advanceTimeBy(1100);runCurrent()
        assertTrue(link.pointWrites>count)
        service.stopTracking(false);runCurrent()
        val stoppedCount=link.pointWrites
        advanceTimeBy(2100);runCurrent()
        assertEquals(stoppedCount,link.pointWrites)
        service.disconnect()
    }

    private fun TestScope.fixture(link: AuditRotatorTransport, azimuth: () -> Double={100.0}): RotatorTrackingService {
        var ioTime=0L
        val settings=RotatorSettings(enabled=true,protocol=protocol,
            transport=if(protocol==RotatorProtocol.PST_ROTATOR)RotatorTransport.UDP else RotatorTransport.TCP,
            host="127.0.0.1",port=4533,parkOnLos=false,
            customPointTemplate="G \$AZ \$EL\\r\\n",customQueryTemplate="?\\n")
        return RotatorTrackingService(backgroundScope,object:RotatorOrbitSource {
            override suspend fun look(pass:OrbitalPass,timeMillis:Long)=RotatorLook(azimuth(),30.0,timeMillis)
            override suspend fun passRequiresFlip(pass:OrbitalPass,azimuthOffsetDegrees:Double)=false
        },{50000L+testScheduler.currentTime},{settings},{link},{GeoPos(0.0,0.0)},
            responseAccumulator=ControlResponseAccumulator({ioTime},{ioTime+=it}),positionQueryIntervalMillis=1)
    }
}

private fun auditPass()=OrbitalPass(aosTime=0,losTime=200000,orbitalObject=OrbitalData(
    name="AUDIT",epoch=24_001.0,meanmo=15.0,eccn=0.001,incl=51.6,raan=0.0,argper=0.0,
    meanan=0.0,catnum=99001,bstar=0.0).getObject())

private fun expectedPoint(protocol:RotatorProtocol):ByteArray = when(protocol) {
    RotatorProtocol.GS232 -> "W100 030\r".encodeToByteArray()
    RotatorProtocol.EASYCOMM_I -> "AZ100 EL30\r".encodeToByteArray()
    RotatorProtocol.EASYCOMM_II,RotatorProtocol.EASYCOMM_III -> "AZ100.0 EL30.0\r".encodeToByteArray()
    RotatorProtocol.SPID_ROT2PROG -> byteArrayOf(0x57,0,4,6,0,1,0,3,9,0,1,0x2f,0x20)
    RotatorProtocol.SAEBRTRACK -> "AZ100EL030\n".encodeToByteArray()
    RotatorProtocol.ROTCTLD -> "P 100.0 30.0\n".encodeToByteArray()
    RotatorProtocol.PST_ROTATOR -> "<PST><AZIMUTH>100.0</AZIMUTH><ELEVATION>30.0</ELEVATION></PST>".encodeToByteArray()
    RotatorProtocol.OZ9AAR_URC -> "{\"GOTO\":[100.0,30.0]}".encodeToByteArray()
    RotatorProtocol.CUSTOM_TEMPLATE -> "G 100 30\r\n".encodeToByteArray()
}

private fun query(protocol:RotatorProtocol):ByteArray?=when(protocol) {
    RotatorProtocol.GS232 -> "C2\r".encodeToByteArray()
    RotatorProtocol.EASYCOMM_II,RotatorProtocol.EASYCOMM_III -> "AZ\n".encodeToByteArray()
    RotatorProtocol.SPID_ROT2PROG -> byteArrayOf(0x57,0,0,0,0,1,0,0,0,0,1,0x1f,0x20)
    RotatorProtocol.ROTCTLD -> "p\n".encodeToByteArray()
    RotatorProtocol.OZ9AAR_URC -> "{\"POLL\"}".encodeToByteArray()
    else -> null
}

private class AuditRotatorTransport(private val protocol:RotatorProtocol,var repliesEnabled:Boolean,
    private val pointReply:ByteArray?=null):ControlTransport {
    override var isConnected=false
    var connectCalls=0
    var readCalls=0
    var pointWrites=0
    var reportedPosition=RotatorPosition(90.0,20.0)
    val writes=mutableListOf<ByteArray>()
    private val pending=ArrayDeque<Byte>()
    private fun enqueueReply() {
        if(!repliesEnabled)return
        val reply=when(protocol) {
            RotatorProtocol.GS232 -> "AZ=090 EL=020\r".encodeToByteArray()
            RotatorProtocol.EASYCOMM_I,RotatorProtocol.EASYCOMM_II,RotatorProtocol.EASYCOMM_III -> "AZ90.0 EL20.0\r".encodeToByteArray()
            RotatorProtocol.SPID_ROT2PROG -> byteArrayOf(0x57,0,4,5,0,1,0,3,8,0,1,0x20)
            RotatorProtocol.ROTCTLD -> "${reportedPosition.azimuthDegrees}\n${reportedPosition.elevationDegrees}\n".encodeToByteArray()
            RotatorProtocol.PST_ROTATOR -> "AZ:90.0 EL:20.0".encodeToByteArray()
            RotatorProtocol.OZ9AAR_URC -> "{\"AZ\":90.0,\"EL\":20.0}".encodeToByteArray()
            else -> byteArrayOf()
        }
        pending.addAll(reply.toList())
    }
    override suspend fun connect():Boolean {connectCalls++;isConnected=true;if(protocol==RotatorProtocol.PST_ROTATOR)enqueueReply();return true}
    override suspend fun disconnect(){isConnected=false;pending.clear()}
    override suspend fun write(bytes:ByteArray):Boolean {
        if(!isConnected)return false
        writes+=bytes.copyOf()
        val text=bytes.decodeToString()
        if(protocol in setOf(RotatorProtocol.EASYCOMM_II,RotatorProtocol.EASYCOMM_III) && text in setOf("AZ\n","EL\n")) {
            if(repliesEnabled)pending.addAll((if(text=="AZ\n")"AZ90.0\n" else "EL20.0\n").encodeToByteArray().toList())
        } else if(bytes.contentEquals(query(protocol)))enqueueReply()
        else {
            pointWrites++
            val reply=pointReply ?: if(protocol==RotatorProtocol.ROTCTLD)"RPRT 0\n".encodeToByteArray() else null
            reply?.let { pending.addAll(it.toList()) }
        }
        return true
    }
    override suspend fun readAvailable(maxBytes:Int):ByteArray {
        readCalls++
        if(protocol==RotatorProtocol.PST_ROTATOR && pending.isEmpty())enqueueReply()
        return ByteArray(minOf(maxBytes,if(protocol==RotatorProtocol.PST_ROTATOR)pending.size else 3,pending.size)){pending.removeFirst()}
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RotatorConfirmationAuditTest {
    @Test fun reconnectDoesNotReuseOldPositionOrMoveWithoutFreshFeedback()=runTest {
        val link=AuditRotatorTransport(RotatorProtocol.ROTCTLD,true)
        val service=fixture(link)
        service.connect();service.startTracking(auditPass());runCurrent()
        assertNotNull(service.state.value.reportedPosition)
        val points=link.pointWrites
        link.repliesEnabled=false
        service.connect();runCurrent()
        assertNull(service.state.value.reportedPosition)
        assertNull(service.state.value.confirmedCommandSequence)
        assertEquals(points,link.pointWrites)
        service.disconnect()
    }
    @Test fun parkingRequiresFreshMatchingPositionAndStaleFeedbackRevokesParkedStatus()=runTest {
        val link=AuditRotatorTransport(RotatorProtocol.ROTCTLD,true)
        val service=fixture(link)
        service.connect();runCurrent();service.park();runCurrent()
        assertEquals(RotatorTrackingPhase.PARKING,service.state.value.trackingPhase)
        assertNotEquals(RotatorCommandStatus.AT_TARGET,service.state.value.commandStatus)
        link.reportedPosition=RotatorPosition(0.0,0.0)
        advanceTimeBy(250);runCurrent()
        assertEquals(RotatorTrackingPhase.PARKED,service.state.value.trackingPhase)
        assertEquals(RotatorCommandStatus.AT_TARGET,service.state.value.commandStatus)
        assertEquals(service.state.value.lastCommandSequence,service.state.value.confirmedCommandSequence)
        link.repliesEnabled=false;advanceTimeBy(250);runCurrent()
        assertEquals(RotatorTrackingPhase.PARKING,service.state.value.trackingPhase)
        assertNull(service.state.value.confirmedCommandSequence)
        service.disconnect()
    }
    @Test fun rotctldRejectionIsShownAndThreeRejectedAttemptsPauseTracking()=runTest {
        val link=AuditRotatorTransport(RotatorProtocol.ROTCTLD,true,"RPRT -1\n".encodeToByteArray())
        val service=fixture(link)
        service.connect();service.startTracking(auditPass());runCurrent()
        advanceTimeBy(2100);runCurrent()
        assertEquals(3,link.writes.count {it.decodeToString().startsWith("P ")})
        assertEquals(RotatorTrackingPhase.ERROR,service.state.value.trackingPhase)
        assertEquals(RotatorConnectionState.CONNECTED,service.state.value.connectionState)
        assertEquals(RotatorCommandStatus.REJECTED,service.state.value.commandStatus)
        assertFalse(service.state.value.isTrackingRequested)
        assertTrue(service.state.value.trackingBlocked)
        assertNotNull(service.state.value.errorMessage)
        service.disconnect()
    }

    @Test fun lostFeedbackMarksOldPositionStaleAndHoldsMovement()=runTest {
        val link=AuditRotatorTransport(RotatorProtocol.ROTCTLD,true)
        val service=fixture(link)
        service.connect();service.startTracking(auditPass());runCurrent()
        val oldPosition=service.state.value.reportedPosition
        assertNotNull(oldPosition)
        link.repliesEnabled=false
        advanceTimeBy(5100);runCurrent()
        assertEquals(oldPosition,service.state.value.reportedPosition)
        assertNotNull(service.state.value.errorMessage)
        assertEquals(RotatorFeedbackStatus.STALE,service.state.value.feedbackStatus)
        assertEquals(RotatorTrackingPhase.HOLDING,service.state.value.trackingPhase)
        service.disconnect()
    }

    @Test fun spidPointEchoIsNeverAcceptedAsPositionFeedback() {
        val echo=expectedPoint(RotatorProtocol.SPID_ROT2PROG)
        assertNull(RotatorCodec.parsePosition(RotatorProtocol.SPID_ROT2PROG,echo))
    }

    private fun TestScope.fixture(link:ControlTransport):RotatorTrackingService {
        var ioTime=0L
        return RotatorTrackingService(backgroundScope,object:RotatorOrbitSource {
            override suspend fun look(pass:OrbitalPass,timeMillis:Long)=RotatorLook(100.0,30.0,timeMillis)
            override suspend fun passRequiresFlip(pass:OrbitalPass,azimuthOffsetDegrees:Double)=false
        },{50000+testScheduler.currentTime},{RotatorSettings(enabled=true,parkOnLos=false)},{link},{GeoPos(0.0,0.0)},
            responseAccumulator=ControlResponseAccumulator({ioTime},{ioTime+=it}),positionQueryIntervalMillis=1)
    }
}
