package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.CwTransmitText
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class L4sController(
    private val transport: RadioTransport,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics,
    private val newSession: () -> Long = { (SecureRandom().nextInt().toLong() and 0xffffffffL).coerceAtLeast(1) }
) : IRadioController {
    private val state=MutableStateFlow(RemoteRadioState())
    override val remoteState: StateFlow<RemoteRadioState> = state
    override val supportsAtomicPair = true
    @Volatile override var isConnected = false
        private set
    private val ioMutex=Mutex()
    private var decoder=L4sProtocol.Decoder()
    private var session=0L
    private var sequence=0
    private var revision=0L
    private var desiredRx: Long?=null
    private var desiredTx: Long?=null
    private var selectedRx=true
    private var armed=false
    private var requestedPair: Pair<Long,Long?>?=null
    private var toneTenths=0
    private var heartbeat: Job?=null

    override suspend fun connect(): Boolean = withContext(dispatcher) {
        if(isConnected) return@withContext true
        transport.setCdcDtrOnConnect(true)
        diagnose("l4s/transport", "Opening native CDC with DTR=1 RTS=0")
        if(!transport.connect()) {
            state.update { it.copy(connected=false,error="Could not open USB CDC; check device selection and USB permission") }
            diagnose("l4s/transport",state.value.error!!,ControlDiagnosticSeverity.ERROR)
            return@withContext false
        }
        try {
            decoder=L4sProtocol.Decoder();sequence=0;revision=0;armed=false;requestedPair=null
            session=newSession() and 0xffffffffL
            require(session!=0L)
            val hello=request(L4sProtocol.HELLO, wireSession=0) ?: return@withContext failedConnect()
            val (firmware,caps)=L4sProtocol.capabilities(hello.data) ?: return@withContext failedConnect()
            if(hello.result!=0) return@withContext failedConnect()
            state.value=RemoteRadioState(firmware=firmware,capabilities=caps)
            diagnose("l4s/hello","firmware=$firmware capabilities=${hello.data.size} bytes")
            if(!ok(request(L4sProtocol.OPEN))) return@withContext failedConnect()
            desiredRx=state.value.rxFrequencyHz; desiredTx=null; selectedRx=true
            isConnected=true;state.update { it.copy(connected=true,error=null) }
            diagnose("l4s/open","session opened; TX remains off")
            heartbeat=scope.launch(dispatcher) {
                try {
                    while(isConnected) {
                        delay(400)
                        if(!ok(request(L4sProtocol.KEEPALIVE))) {
                            isConnected=false
                            state.update { it.copy(connected=false,error=it.error ?: "L4S heartbeat lost; reconnect") }
                            transport.disconnect()
                        }
                    }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) {
                    isConnected=false
                    state.update { it.copy(connected=false,error=error.message ?: "L4S connection lost") }
                    runCatching { transport.disconnect() }
                }
            }
            true
        } catch(cancelled: CancellationException) { failedConnect();throw cancelled }
        catch(error: Exception) { state.update { it.copy(error=error.message) };failedConnect() }
    }

    private suspend fun failedConnect(): Boolean {
        transport.disconnect();isConnected=false
        state.update { it.copy(connected=false,error=it.error ?: "No compatible L4S v1 firmware") }
        return false
    }

    override suspend fun disconnect() = withContext(dispatcher+NonCancellable) {
        heartbeat?.cancel();heartbeat=null
        if(transport.isConnected) {
            request(L4sProtocol.STOP)
            request(L4sProtocol.CLOSE)
        }
        transport.disconnect();isConnected=false;armed=false;requestedPair=null
        state.update { it.copy(connected=false) }
    }

    private suspend fun request(command: Int, data: ByteArray=byteArrayOf(), wireSession: Long=session): L4sProtocol.Reply? =
        ioMutex.withLock {
            if(!transport.isConnected) return@withLock null
            val seq=sequence.also { sequence=(sequence+1) and 0xffff }
            val wire=L4sProtocol.frame(L4sProtocol.body(command,seq,wireSession,data))
            var receivedBytes=0
            val label=when(command) { L4sProtocol.HELLO -> "HELLO"; L4sProtocol.OPEN -> "OPEN"; else -> "0x${command.toString(16)}" }
            repeat(2) { attempt ->
                if(command!=L4sProtocol.KEEPALIVE) diagnose("l4s/request","cmd=$label seq=$seq attempt=${attempt+1} bytes=${wire.size}")
                if(!transport.write(wire)) {
                    state.update { it.copy(error="L4S $label USB write failed") }
                    diagnose("l4s/write",state.value.error!!,ControlDiagnosticSeverity.ERROR)
                    return@withLock null
                }
                val response=withTimeoutOrNull(600) {
                    var match: L4sProtocol.Reply?=null
                    while(match==null) {
                        val chunk=transport.readAvailable(1024)
                        receivedBytes+=chunk.size
                        decoder.feed(chunk).forEach { plain ->
                            val reply=L4sProtocol.reply(plain)
                            if(reply!=null && reply.command==command && reply.sequence==seq &&
                                (reply.session==wireSession || command==L4sProtocol.HELLO ||
                                    reply.session==0L && (reply.result==3 || command==L4sProtocol.CLOSE))) match=reply
                        }
                        if(match==null) delay(5)
                    }
                    match
                }
                if(response!=null) {
                    if(command!=L4sProtocol.KEEPALIVE) diagnose("l4s/reply","cmd=$label seq=$seq result=${response.result} rxBytes=$receivedBytes")
                    if(command!=L4sProtocol.HELLO) {
                        val snapshot=L4sProtocol.snapshot(response,state.value)
                        if(snapshot==null) { state.update { old -> old.copy(error="Malformed L4S state reply") };return@withLock null }
                        state.value=snapshot
                    }
                    if(response.result>1) state.update { old -> old.copy(error=L4sProtocol.error(response.result)) }
                    return@withLock response
                }
            }
            val hint=if(command==L4sProtocol.HELLO) "; check L4S firmware, CDC/DTR and USB key mode" else ""
            state.update { it.copy(error="L4S $label timed out (RX $receivedBytes bytes)$hint") }
            diagnose("l4s/timeout",state.value.error!!,ControlDiagnosticSeverity.ERROR)
            null
        }

    private fun diagnose(stage:String,message:String,severity:ControlDiagnosticSeverity=ControlDiagnosticSeverity.INFO) {
        diagnostics.record(ControlDiagnosticSource.RADIO,stage,message,severity)
    }

    private fun ok(reply: L4sProtocol.Reply?): Boolean = reply?.result==L4sProtocol.APPLIED
    private fun quantize(hz: Long): Long {
        require(hz in 1..0xfffffffaL) { "L4S frequency outside unsigned 32-bit Hz range" }
        return (hz+5)/10*10
    }

    override suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?): FrequencyPairResult = withContext(dispatcher) {
        if(!isConnected) return@withContext FrequencyPairResult.FAILED
        if(state.value.txSource!=0 || state.value.cwState in setOf(
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.QUEUED,
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.SENDING)) {
            state.update { it.copy(error="TX/RX frequencies are held during transmission") }
            return@withContext FrequencyPairResult.FAILED
        }
        val pair=quantize(rxHz) to txHz?.let(::quantize)
        desiredRx=pair.first;desiredTx=pair.second
        val current=state.value
        if(requestedPair==pair && armed) {
            if(current.rxFrequencyHz==pair.first && current.txFrequencyHz==pair.second && current.txEnabled==(pair.second!=null))
                return@withContext FrequencyPairResult.APPLIED
        }
        revision=(maxOf(revision,current.appliedRevision,current.pendingRevision)+1) and 0xffffffffL
        if(revision==0L) revision=1
        val data=ByteArray(13)
        L4sProtocol.put32(data,0,pair.first);L4sProtocol.put32(data,4,pair.second ?: 0)
        data[8]=if(pair.second!=null) 1 else 0;L4sProtocol.put32(data,9,revision)
        val reply=request(L4sProtocol.PAIR,data)
        when(reply?.result) {
            L4sProtocol.APPLIED -> {
                val verified=state.value
                if(verified.appliedRevision!=revision || verified.rxFrequencyHz!=pair.first ||
                    verified.txFrequencyHz!=pair.second || verified.txEnabled!=(pair.second!=null)) return@withContext FrequencyPairResult.FAILED
                requestedPair=pair;armed=true;FrequencyPairResult.APPLIED
            }
            else -> FrequencyPairResult.FAILED
        }
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean = if(selectedRx) {
        setWorkingFrequency(frequencyHz)
    } else setTxVfoFrequency(frequencyHz)
    override suspend fun setWorkingFrequency(frequencyHz: Long) =
        setTrackingFrequencies(frequencyHz,desiredTx)==FrequencyPairResult.APPLIED
    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean =
        desiredRx?.let { setTrackingFrequencies(it,frequencyHz)==FrequencyPairResult.APPLIED } ?: false
    override suspend fun setVfo(vfoA: Boolean): Boolean { selectedRx=vfoA;return isConnected }
    override suspend fun setBand(frequencyHz: Long): Boolean = state.value.capabilities?.let {
        frequencyHz in it.minimumRxHz..it.maximumRxHz
    } ?: false
    override suspend fun setSplitMode(enabled: Boolean): Boolean = if(enabled) isConnected else pttOff()

    override suspend fun setMode(mode: String): Boolean = if(selectedRx) setSplitModes(mode,null) else setSplitModes(null,mode)
    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean = withContext(dispatcher) {
        val rx=L4sProtocol.modeNumber(rxMode ?: state.value.rxMode) ?: return@withContext false
        val tx=L4sProtocol.modeNumber(txMode ?: state.value.txMode) ?: return@withContext false
        val caps=state.value.capabilities ?: return@withContext false
        if(caps.rxModeMask and (1 shl rx)==0 || caps.txModeMask and (1 shl tx)==0) {
            state.update { it.copy(error="L4S supports FM/CW transmit; AM/USB are receive-only") };return@withContext false
        }
        val narrow=if(listOf(rxMode,txMode).any { it?.uppercase(java.util.Locale.ROOT) in listOf("NFM","FM-N") }) 1 else 0
        ok(request(L4sProtocol.MODE,byteArrayOf(rx.toByte(),tx.toByte(),narrow.toByte()))) &&
            state.value.rxMode==L4sProtocol.modeName(rx) && state.value.txMode==L4sProtocol.modeName(tx)
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean {
        if(!toneHz.isFinite() || toneHz !in 0.0..999.9) return false
        toneTenths=kotlin.math.round(toneHz*10).toInt()
        return sendTone(toneTenths)
    }
    override suspend fun setCtcssMode(enabled: Boolean) = sendTone(if(enabled) toneTenths else 0)
    override suspend fun configureTxCtcss(toneHz: Double?): Boolean =
        if(toneHz==null) sendTone(0) else setCtcssTone(toneHz)
    private suspend fun sendTone(tenths: Int): Boolean = withContext(dispatcher) {
        if(state.value.capabilities?.ctcss!=true) return@withContext tenths==0
        ok(request(L4sProtocol.TONE,ByteArray(2).also { L4sProtocol.put16(it,0,tenths) }))
    }

    override suspend fun readFrequencyAndMode(): Pair<Long,String>? = withContext(dispatcher) {
        if(!ok(request(L4sProtocol.STATUS))) return@withContext null
        if(selectedRx) state.value.rxFrequencyHz?.let { it to state.value.rxMode }
        else state.value.txFrequencyHz?.let { it to state.value.txMode }
    }
    override suspend fun readWorkingFrequency(): Long? = withContext(dispatcher) {
        if(ok(request(L4sProtocol.STATUS))) state.value.rxFrequencyHz else null
    }
    override suspend fun readTxVfoFrequency(): Long? = withContext(dispatcher) {
        if(ok(request(L4sProtocol.STATUS))) state.value.txFrequencyHz else null
    }

    private suspend fun preparePair(): Boolean {
        val rx=desiredRx ?: return false
        if(desiredTx==null) return false
        if(armed && requestedPair == (rx to desiredTx) && state.value.rxFrequencyHz==rx &&
            state.value.txFrequencyHz==desiredTx && state.value.pendingRevision==0L) return true
        if(!armed) requestedPair=null
        return setTrackingFrequencies(rx,desiredTx)==FrequencyPairResult.APPLIED
    }

    override suspend fun pttOn(): Boolean = withContext(dispatcher) {
        if(!isConnected || state.value.capabilities?.voicePtt!=true || !preparePair()) return@withContext false
        val data=ByteArray(5);data[0]=1;L4sProtocol.put32(data,1,state.value.appliedRevision)
        ok(request(L4sProtocol.PTT,data)) && state.value.txSource==1
    }
    override suspend fun pttOff(): Boolean = withContext(dispatcher) {
        armed=false;requestedPair=null
        ok(request(L4sProtocol.STOP)) && state.value.txSource==0
    }
    override suspend fun startCw(text: String, wpm: Int): Boolean = withContext(dispatcher) {
        val caps=state.value.capabilities ?: return@withContext false
        val normalized=CwTransmitText.normalize(text)
        if(!isConnected || !caps.cwText || normalized.length>caps.maxCwText ||
            wpm !in caps.minimumWpm..caps.maximumWpm || !preparePair()) return@withContext false
        val data=byteArrayOf(wpm.toByte(),normalized.length.toByte())+normalized.toByteArray(Charsets.US_ASCII)
        request(L4sProtocol.CW,data)?.result==L4sProtocol.ACCEPTED && state.value.cwState in
            setOf(com.rtbishop.look4sat.core.domain.repository.CwTransmitState.QUEUED,
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.SENDING,
                com.rtbishop.look4sat.core.domain.repository.CwTransmitState.DONE)
    }
}
