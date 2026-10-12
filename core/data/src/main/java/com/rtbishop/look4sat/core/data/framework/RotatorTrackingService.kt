/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * The service structure and pointing lifecycle are informed by OrbitDeckiOS.
 * Copyright (c) 2025 Paul Stoetzer, N8HM. Licensed under the MIT License.
 * See THIRD_PARTY_NOTICES.md.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSeverity
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticSource
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IRotatorTrackingService
import com.rtbishop.look4sat.core.domain.rotator.RotatorConnectionState
import com.rtbishop.look4sat.core.domain.rotator.RotatorLook
import com.rtbishop.look4sat.core.domain.rotator.RotatorPassContext
import com.rtbishop.look4sat.core.domain.rotator.RotatorPointingDecision
import com.rtbishop.look4sat.core.domain.rotator.RotatorPointingInput
import com.rtbishop.look4sat.core.domain.rotator.RotatorPointingPolicy
import com.rtbishop.look4sat.core.domain.rotator.RotatorPosition
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingPhase
import com.rtbishop.look4sat.core.domain.rotator.RotatorTrackingState
import com.rtbishop.look4sat.core.domain.rotator.RotatorCommandStatus
import com.rtbishop.look4sat.core.domain.rotator.RotatorFeedbackStatus
import kotlin.math.abs
import kotlin.math.max
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

class RotatorTrackingService(
    private val appScope: CoroutineScope,
    private val orbitSource: RotatorOrbitSource,
    private val nowMillis: () -> Long,
    private val settingsProvider: () -> RotatorSettings,
    private val transportFactory: (RotatorSettings) -> ControlTransport,
    private val stationPosition: () -> GeoPos,
    private val magneticDeclinationDegrees: (GeoPos, Long) -> Double = { _, _ -> 0.0 },
    responseAccumulator: ControlResponseAccumulator = ControlResponseAccumulator(),
    private val reconnectDelayMillis: Long = DEFAULT_RECONNECT_DELAY_MILLIS,
    private val maxReconnectAttempts: Int = DEFAULT_RECONNECT_ATTEMPTS,
    private val positionQueryIntervalMillis: Long = DEFAULT_POSITION_QUERY_INTERVAL_MILLIS,
    private val diagnostics: IControlDiagnostics = NoOpControlDiagnostics
) : IRotatorTrackingService {
    private val _state = MutableStateFlow(RotatorTrackingState())
    override val state: StateFlow<RotatorTrackingState> = _state

    private val connectionMutex = Mutex()
    private val sequence = AtomicLong(0L)
    private val commandActor = RotatorCommandActor(appScope, ::handleCommandOutcome)
    private val protocolClient = RotatorProtocolClient(responseAccumulator)
    private val connectionGeneration = AtomicLong()
    private val feedbackWake = Channel<Unit>(Channel.CONFLATED)
    private val trackingWake = Channel<Unit>(Channel.CONFLATED)
    private var feedbackJob: Job? = null
    private var rejectedCommands = 0

    @Volatile
    private var transport: ControlTransport? = null

    @Volatile
    private var activePass: OrbitalPass? = null

    @Volatile
    private var lastWirePosition: RotatorPosition? = null

    @Volatile
    private var lastSendAtMillis: Long? = null

    private var trackingJob: Job? = null
    private var reconnectJob: Job? = null
    private var wantConnection = false
    private var lastQueryAtMillis: Long? = null
    private var azimuth450Precommitted = false

    init {
        require(reconnectDelayMillis >= 0L)
        require(maxReconnectAttempts >= 0)
        require(positionQueryIntervalMillis >= 0L)
    }

    override suspend fun connect() {
        _state.update { it.copy(trackingBlocked = false) }
        val settings = settingsProvider().normalized()
        diagnose("connect/start", "protocol=${settings.protocol} transport=${settings.transport}")
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        connectionMutex.withLock {
            wantConnection = true
            commandActor.cancelPendingTargets()
            connectLocked(reconnecting = false)
        }
    }

    override suspend fun disconnect(park: Boolean) {
        diagnose("disconnect/start", "parkRequested=$park")
        wantConnection = false
        feedbackJob?.cancelAndJoin()
        feedbackJob = null
        reconnectJob?.cancelAndJoin()
        reconnectJob = null
        trackingJob?.cancelAndJoin()
        trackingJob = null
        activePass = null
        commandActor.cancelPendingTargets()
        val settings = settingsProvider().normalized()
        if ((park || settings.parkOnDisconnect) && transport?.isConnected == true) {
            runCatching { parkInternal(settings) }
        }
        connectionMutex.withLock {
            connectionGeneration.incrementAndGet()
            transport?.disconnect()
            transport = null
        }
        resetCommandHistory()
        _state.value = RotatorTrackingState(
            connectionState = RotatorConnectionState.DISCONNECTED,
            protocol = settings.protocol,
            transport = settings.transport
        )
        diagnose("disconnect/result", "rotator disconnected")
    }

    override fun startTracking(pass: OrbitalPass) {
        diagnose("tracking/start", "satellite=${pass.catNum}")
        activePass = pass
        commandActor.cancelPendingTargets()
        rejectedCommands = 0
        val settings = settingsProvider().normalized()
        _state.update {
            it.copy(
                isTrackingRequested = true,
                trackingBlocked = false,
                trackingPhase = RotatorTrackingPhase.HOLDING,
                satelliteCatalogNumber = pass.catNum,
                satelliteName = pass.name,
                passAosTimeMillis = pass.aosTime,
                protocol = settings.protocol,
                transport = settings.transport,
                errorMessage = if (it.connectionState == RotatorConnectionState.CONNECTED) it.errorMessage else "Connect the rotator before tracking"
            )
        }
        trackingJob?.cancel()
        trackingJob = appScope.launch { runTrackingLoop(pass) }
    }

    override fun stopTracking(park: Boolean) {
        diagnose("tracking/stop", "parkRequested=$park")
        activePass = null
        trackingJob?.cancel()
        trackingJob = null
        commandActor.cancelPendingTargets()
        azimuth450Precommitted = false
        _state.update {
            it.copy(
                isTrackingRequested = false,
                trackingPhase = RotatorTrackingPhase.IDLE,
                satelliteCatalogNumber = null,
                satelliteName = ""
            )
        }
        if (park) appScope.launch { park() }
    }

    override suspend fun point(position: RotatorPosition) {
        val settings = settingsProvider().normalized()
        val decision = RotatorPointingDecision.Point(
            phase = RotatorTrackingPhase.HOLDING,
            truePosition = position,
            wirePosition = position,
            nextAzimuth450Precommitted = false
        )
        val commandSequence = sequence.incrementAndGet()
        val generation = connectionGeneration.get()
        commandActor.executeImmediate(commandSequence, invalidateTargets = true) {
            sendPosition(settings, decision, commandSequence, generation)
        }
    }

    override suspend fun park() {
        parkInternal(settingsProvider().normalized())
    }

    override suspend fun emergencyStop() {
        diagnose("emergency-stop/start", "canceling movement and tracking", ControlDiagnosticSeverity.WARNING)
        activePass = null
        _state.update { it.copy(isTrackingRequested = false, trackingBlocked = true,
            commandedPosition = null, commandedWirePosition = null, confirmedCommandSequence = null) }
        trackingJob?.cancelAndJoin()
        trackingJob = null
        azimuth450Precommitted = false
        val settings = settingsProvider().normalized()
        val frame = com.rtbishop.look4sat.core.domain.rotator.RotatorCodec.stop(
            settings.protocol,
            settings.customStopTemplate
        )
        val commandSequence = sequence.incrementAndGet()
        val sent = try { commandActor.executeStop(commandSequence) {
            val link = transport
            if (frame == null) throw RotatorProtocolFailure(RotatorFailureKind.INVALID_REPLY,"No stop command for ${settings.protocol}")
            if (link == null) false else protocolClient.stop(link, settings)
        } } catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { false }
        _state.update {
            it.copy(
                trackingPhase = RotatorTrackingPhase.HOLDING,
                isTrackingRequested = false,
                satelliteCatalogNumber = null,
                satelliteName = "",
                lastCommandSequence = commandSequence,
                lastCommandAtMillis = nowMillis(),
                commandStatus = if(frame == null) RotatorCommandStatus.UNCONFIRMED else if(sent && settings.protocol == RotatorProtocol.ROTCTLD) RotatorCommandStatus.ACCEPTED
                    else if(sent) RotatorCommandStatus.UNCONFIRMED else RotatorCommandStatus.TIMED_OUT,
                errorMessage = when {
                    !sent -> "Rotator stop command failed"
                    frame == null -> "${settings.protocol} does not provide a stop command"
                    else -> null
                }
            )
        }
        diagnose(
            "emergency-stop/result",
            if (sent) "stop command handled" else "stop command failed",
            if (sent) ControlDiagnosticSeverity.WARNING else ControlDiagnosticSeverity.ERROR
        )
    }

    private suspend fun connectLocked(reconnecting: Boolean): Boolean {
        connectionGeneration.incrementAndGet()
        feedbackJob?.cancel()
        feedbackJob = null
        val settings = settingsProvider().normalized()
        if (!settings.isConfigured) {
            diagnose("connect/validation", "rotator settings are incomplete", ControlDiagnosticSeverity.ERROR)
            transport = null
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    protocol = settings.protocol,
                    transport = settings.transport,
                    errorMessage = "Rotator settings are incomplete"
                )
            }
            return false
        }
        _state.update {
            it.copy(
                connectionState = if (reconnecting) {
                    RotatorConnectionState.RECONNECTING
                } else {
                    RotatorConnectionState.CONNECTING
                },
                protocol = settings.protocol,
                transport = settings.transport,
                errorMessage = null
            )
        }
        transport?.disconnect()
        val candidate = runCatching { transportFactory(settings) }.getOrElse { error ->
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    errorMessage = error.message ?: "Could not create rotator transport"
                )
            }
            return false
        }
        transport = candidate
        val connected = runCatching { candidate.connect() }.getOrDefault(false)
        if (connected) {
            resetCommandHistory()
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.CONNECTED,
                    trackingPhase = if (activePass == null) {
                        RotatorTrackingPhase.IDLE
                    } else {
                        RotatorTrackingPhase.HOLDING
                    },
                    commandedPosition = null,
                    commandedWirePosition = null,
                    reportedPosition = null,
                    reportedAtMillis = null,
                    acceptedCommandSequence = null,
                    confirmedCommandSequence = null,
                    commandStatus = RotatorCommandStatus.IDLE,
                    feedbackStatus = if (feedbackEnabled(settings)) RotatorFeedbackStatus.UNKNOWN else RotatorFeedbackStatus.UNSUPPORTED,
                    errorMessage = null
                )
            }
            diagnose("connect/result", "connected protocol=${settings.protocol} transport=${settings.transport}")
            startFeedbackMonitor(candidate, connectionGeneration.get())
        } else {
            candidate.disconnect()
            if (transport === candidate) transport = null
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    errorMessage = "Could not connect to rotator"
                )
            }
            diagnose("connect/result", "connection failed", ControlDiagnosticSeverity.ERROR)
        }
        return connected
    }

    private suspend fun runTrackingLoop(pass: OrbitalPass) {
        val initialSettings = settingsProvider().normalized()
        val requiresFlip = if (initialSettings.flipOverheadPasses) {
            runCatching {
                orbitSource.passRequiresFlip(pass, initialSettings.azimuthOffsetDegrees)
            }.getOrDefault(false)
        } else {
            false
        }
        while (currentCoroutineContext().isActive && activePass === pass) {
            val settings = settingsProvider().normalized()
            if (_state.value.connectionState == RotatorConnectionState.CONNECTED) {
                try {
                    runTrackingCycle(pass, settings, requiresFlip)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    _state.update {
                        it.copy(
                            trackingPhase = RotatorTrackingPhase.ERROR,
                            errorMessage = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
            } else {
                _state.update { it.copy(trackingPhase = RotatorTrackingPhase.HOLDING) }
            }
            withTimeoutOrNull(settings.updateIntervalMillis) { trackingWake.receive() }
        }
    }

    private suspend fun runTrackingCycle(
        pass: OrbitalPass,
        settings: RotatorSettings,
        requiresFlip: Boolean
    ) {
        val now = nowMillis()
        expireFeedback(settings, now)
        if (feedbackEnabled(settings) && _state.value.feedbackStatus != RotatorFeedbackStatus.FRESH) {
            _state.update { it.copy(trackingPhase = RotatorTrackingPhase.HOLDING) }
            return
        }
        val current = orbitSource.look(pass, now)
        val lead = settings.trackingLeadSeconds.takeIf { it > 0 }?.let {
            orbitSource.look(pass, now + it * MILLIS_PER_SECOND)
        }
        val azimuthAhead = if (
            settings.azimuthLookAheadSeconds > 0 &&
            settings.azimuthRange == com.rtbishop.look4sat.core.domain.rotator.RotatorAzimuthRange.ZERO_TO_450
        ) {
            orbitSource.look(pass, now + settings.azimuthLookAheadSeconds * MILLIS_PER_SECOND)
        } else {
            null
        }
        val decision = RotatorPointingPolicy.decide(
            settings,
            RotatorPointingInput(
                nowMillis = now,
                currentLook = current,
                trackingLeadLook = lead,
                azimuthLookAhead = azimuthAhead,
                nextPass = RotatorPassContext(
                    aosTimeMillis = pass.aosTime,
                    losTimeMillis = pass.losTime,
                    aosAzimuthDegrees = pass.aosAzimuth,
                    requiresFlip = requiresFlip
                ),
                azimuth450Precommitted = azimuth450Precommitted,
                magneticDeclinationDegrees = magneticDeclinationDegrees(stationPosition(), now)
            )
        )
        azimuth450Precommitted = decision.nextAzimuth450Precommitted
        when (decision) {
            is RotatorPointingDecision.Hold -> {
                if (_state.value.trackingPhase != RotatorTrackingPhase.HOLDING) {
                    diagnose("tracking/phase", "HOLDING reason=${decision.reason}")
                }
                _state.update { it.copy(trackingPhase = RotatorTrackingPhase.HOLDING) }
            }
            is RotatorPointingDecision.Point -> {
                if (_state.value.trackingPhase != decision.phase) {
                    diagnose("tracking/phase", decision.phase.name)
                }
                _state.update { it.copy(trackingPhase = if(decision.phase == RotatorTrackingPhase.PARKING &&
                    lastWirePosition == decision.wirePosition && it.commandStatus == RotatorCommandStatus.AT_TARGET)
                    RotatorTrackingPhase.PARKED else decision.phase) }
                if (
                    RotatorPointingPolicy.shouldSend(
                        previousPosition = lastWirePosition,
                        targetPosition = decision.wirePosition,
                        settings = settings,
                        nowMillis = now,
                        lastSendAtMillis = lastSendAtMillis
                    )
                ) {
                    val commandSequence = sequence.incrementAndGet()
                    val generation = connectionGeneration.get()
                    commandActor.submitTarget(commandSequence) {
                        sendPosition(settings, decision, commandSequence, generation)
                    }
                }
            }
        }
    }

    private suspend fun sendPosition(
        settings: RotatorSettings, decision: RotatorPointingDecision.Point, commandSequence: Long, generation: Long
    ): Boolean {
        if (generation != connectionGeneration.get()) return true
        val link = transport ?: return false
        expireFeedback(settings, nowMillis())
        if (feedbackEnabled(settings) && _state.value.feedbackStatus != RotatorFeedbackStatus.FRESH)
            throw RotatorProtocolFailure(RotatorFailureKind.INVALID_REPLY, "Position feedback is unavailable; movement held")
        val accepted = try { protocolClient.point(link, settings, decision.wirePosition) {
            _state.update { if(generation != connectionGeneration.get() || transport !== link) it else it.copy(trackingPhase=decision.phase, commandedPosition=decision.truePosition,
                commandedWirePosition=decision.wirePosition, commandStatus=RotatorCommandStatus.SENT,
                confirmedCommandSequence=null, lastCommandSequence=commandSequence, lastCommandAtMillis=nowMillis()) }
        } } catch(error:Exception) { if(generation!=connectionGeneration.get())return true else throw error }
        if (generation != connectionGeneration.get() || transport !== link) return true
        lastWirePosition = decision.wirePosition
        lastSendAtMillis = nowMillis()
        rejectedCommands = 0
        _state.update { it.copy(commandStatus=when {
            accepted -> RotatorCommandStatus.ACCEPTED
            feedbackEnabled(settings) -> RotatorCommandStatus.SENT
            else -> RotatorCommandStatus.UNCONFIRMED
        }, acceptedCommandSequence=if(accepted) commandSequence else null, errorMessage=null) }
        lastQueryAtMillis = null
        feedbackWake.trySend(Unit)
        diagnose("command/sent", "sequence=$commandSequence accepted=$accepted feedback=${feedbackEnabled(settings)}")
        return true
    }

    private suspend fun parkInternal(settings: RotatorSettings) {
        val decision = RotatorPointingPolicy.decide(
            settings.copy(enabled = true, parkOnLos = true),
            RotatorPointingInput(
                nowMillis = nowMillis(),
                currentLook = null,
                magneticDeclinationDegrees = magneticDeclinationDegrees(stationPosition(), nowMillis())
            )
        ) as? RotatorPointingDecision.Point ?: return
        _state.update { it.copy(trackingPhase = RotatorTrackingPhase.PARKING) }
        val commandSequence = sequence.incrementAndGet()
        val generation = connectionGeneration.get()
        commandActor.executeImmediate(commandSequence, invalidateTargets = true) {
            sendPosition(settings, decision, commandSequence, generation)
        }
        azimuth450Precommitted = false
    }

    private fun feedbackEnabled(settings: RotatorSettings) =
        positionQueryIntervalMillis > 0 && settings.supportsPositionFeedback

    private fun startFeedbackMonitor(link: ControlTransport, generation: Long) {
        feedbackJob = appScope.launch {
            while (isActive && transport === link && connectionGeneration.get() == generation) {
                val settings = settingsProvider().normalized()
                if (!feedbackEnabled(settings)) return@launch
                expireFeedback(settings, nowMillis())
                maybeQueryPosition(settings, nowMillis(), generation)
                withTimeoutOrNull(positionQueryIntervalMillis.coerceAtLeast(200L)) { feedbackWake.receive() }
            }
        }
    }

    private fun expireFeedback(settings: RotatorSettings, now: Long) {
        val state = _state.value
        val receivedAt = state.reportedAtMillis ?: return
        val limit = max(settings.sampleTimeoutMillis, positionQueryIntervalMillis*2+500L)
        if (now-receivedAt > limit && state.feedbackStatus == RotatorFeedbackStatus.FRESH)
            markFeedbackMissing("Rotator position feedback is stale")
    }

    private fun markFeedbackMissing(message: String) {
        _state.update { it.copy(feedbackStatus=RotatorFeedbackStatus.STALE, confirmedCommandSequence=null,
            commandStatus=if(it.commandStatus in setOf(RotatorCommandStatus.FEEDBACK, RotatorCommandStatus.AT_TARGET))
                if(it.acceptedCommandSequence==it.lastCommandSequence) RotatorCommandStatus.ACCEPTED else RotatorCommandStatus.SENT
                else it.commandStatus,
            trackingPhase=if(it.isTrackingRequested) RotatorTrackingPhase.HOLDING else
                if(it.trackingPhase==RotatorTrackingPhase.PARKED) RotatorTrackingPhase.PARKING else it.trackingPhase,
            errorMessage=message) }
    }

    private suspend fun maybeQueryPosition(settings: RotatorSettings, now: Long, generation: Long) {
        if (lastQueryAtMillis?.let { now-it < positionQueryIntervalMillis } == true) return
        lastQueryAtMillis = now
        val querySequence = sequence.incrementAndGet()
        try {
            commandActor.executeQuery(querySequence) {
                if (connectionGeneration.get()!=generation) return@executeQuery true
                val link = transport ?: return@executeQuery false
                val commandSequence = _state.value.lastCommandSequence
                val position = try { protocolClient.position(link, settings) }
                    catch(error:Exception) { if(connectionGeneration.get()!=generation)return@executeQuery true else throw error }
                if(connectionGeneration.get()!=generation || transport !== link) return@executeQuery true
                publishPosition(position, settings, commandSequence, generation)
                diagnose("position/received", "az=${position.azimuthDegrees} el=${position.elevationDegrees} command=$commandSequence")
                true
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { /* Command outcomes publish failures, including missing feedback. */ }
    }

    private fun publishPosition(position: RotatorPosition, settings: RotatorSettings, sequence: Long, generation: Long) {
        val shouldWake = _state.value.feedbackStatus != RotatorFeedbackStatus.FRESH
        _state.update { current ->
            if (generation != connectionGeneration.get()) return@update current
            val target = current.commandedWirePosition
            val confirms = target!=null && sequence==current.lastCommandSequence &&
                current.commandStatus !in setOf(RotatorCommandStatus.REJECTED,RotatorCommandStatus.TIMED_OUT)
            val delta = target?.let { abs(position.azimuthDegrees-it.azimuthDegrees) } ?: Double.MAX_VALUE
            val azError = if(settings.azimuthRange==com.rtbishop.look4sat.core.domain.rotator.RotatorAzimuthRange.ZERO_TO_360)
                minOf(delta, abs(360.0-delta)) else delta
            val tolerance = max(1.0, settings.deadbandDegrees)
            val atTarget = confirms && azError<=tolerance && abs(position.elevationDegrees-target.elevationDegrees)<=tolerance
            current.copy(reportedPosition=position, reportedAtMillis=nowMillis(), feedbackStatus=RotatorFeedbackStatus.FRESH,
                confirmedCommandSequence=if(confirms) sequence else current.confirmedCommandSequence,
                commandStatus=if(atTarget) RotatorCommandStatus.AT_TARGET else if(confirms) RotatorCommandStatus.FEEDBACK else current.commandStatus,
                trackingPhase=if(atTarget && current.trackingPhase==RotatorTrackingPhase.PARKING) RotatorTrackingPhase.PARKED
                    else if(!atTarget && current.trackingPhase==RotatorTrackingPhase.PARKED) RotatorTrackingPhase.PARKING else current.trackingPhase,
                errorMessage=if(current.commandStatus in setOf(RotatorCommandStatus.REJECTED,RotatorCommandStatus.TIMED_OUT))
                    current.errorMessage else null)
        }
        if (shouldWake) trackingWake.trySend(Unit)
    }

    private fun handleCommandOutcome(outcome: RotatorCommandOutcome) {
        if (outcome.superseded) return
        _state.update {
            it.copy(
                errorMessage = if (outcome.success) it.errorMessage else outcome.errorMessage
            )
        }
        if (!outcome.success && outcome.kind == RotatorCommandKind.QUERY)
            markFeedbackMissing(outcome.errorMessage ?: "Position feedback unavailable")
        if (!outcome.success && outcome.kind in setOf(RotatorCommandKind.TARGET, RotatorCommandKind.IMMEDIATE)) {
            lastWirePosition = null
            _state.update { it.copy(commandStatus=if(outcome.reconnectRecommended) RotatorCommandStatus.TIMED_OUT else RotatorCommandStatus.REJECTED,
                confirmedCommandSequence=null, trackingPhase=RotatorTrackingPhase.ERROR) }
            if (!outcome.reconnectRecommended && ++rejectedCommands >= 3) {
                activePass = null
                trackingJob?.cancel()
                _state.update { it.copy(isTrackingRequested=false, trackingBlocked=true, errorMessage="Rotator rejected 3 commands; tracking paused") }
            }
        }
        if (!outcome.success && outcome.reconnectRecommended && wantConnection) scheduleReconnect(outcome.errorMessage)
        if (!outcome.success) {
            diagnose(
                "command/failure",
                outcome.errorMessage ?: "rotator command failed",
                ControlDiagnosticSeverity.ERROR
            )
        }
    }

    private fun scheduleReconnect(reason: String?) {
        if (!wantConnection || reconnectJob?.isActive == true || maxReconnectAttempts == 0) return
        reconnectJob = appScope.launch {
            diagnose(
                "reconnect/scheduled",
                reason ?: "write failed",
                ControlDiagnosticSeverity.WARNING
            )
            commandActor.cancelPendingTargets()
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.RECONNECTING,
                    trackingPhase = RotatorTrackingPhase.HOLDING,
                    feedbackStatus = RotatorFeedbackStatus.STALE,
                    confirmedCommandSequence = null,
                    errorMessage = reason
                )
            }
            connectionMutex.withLock {
                connectionGeneration.incrementAndGet()
                feedbackJob?.cancel()
                transport?.disconnect()
                transport = null
            }
            repeat(maxReconnectAttempts) { attempt ->
                delay(reconnectDelayMillis)
                if (!wantConnection) return@launch
                diagnose("reconnect/attempt", "attempt=${attempt + 1}", ControlDiagnosticSeverity.WARNING)
                val connected = connectionMutex.withLock { connectLocked(reconnecting = true) }
                if (connected) {
                    diagnose("reconnect/result", "reconnected after attempt=${attempt + 1}")
                    return@launch
                }
            }
            _state.update {
                it.copy(
                    connectionState = RotatorConnectionState.ERROR,
                    trackingPhase = RotatorTrackingPhase.ERROR,
                    errorMessage = "Rotator reconnect attempts exhausted"
                )
            }
            diagnose("reconnect/result", "attempts exhausted", ControlDiagnosticSeverity.ERROR)
        }
    }

    private fun diagnose(
        stage: String,
        message: String,
        severity: ControlDiagnosticSeverity = ControlDiagnosticSeverity.INFO
    ) {
        diagnostics.record(ControlDiagnosticSource.ROTATOR, stage, message, severity)
    }

    private fun resetCommandHistory() {
        lastWirePosition = null
        lastSendAtMillis = null
        lastQueryAtMillis = null
        azimuth450Precommitted = false
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val DEFAULT_RECONNECT_DELAY_MILLIS = 2_000L
        const val DEFAULT_RECONNECT_ATTEMPTS = 3
        const val DEFAULT_POSITION_QUERY_INTERVAL_MILLIS = 2_000L
    }
}
