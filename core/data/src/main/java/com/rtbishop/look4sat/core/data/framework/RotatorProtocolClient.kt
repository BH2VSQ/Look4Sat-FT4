package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.rotator.RotatorCodec
import com.rtbishop.look4sat.core.domain.rotator.RotatorPosition
import com.rtbishop.look4sat.core.domain.rotator.RotatorProtocol
import com.rtbishop.look4sat.core.domain.rotator.RotatorSettings
import com.rtbishop.look4sat.core.domain.rotator.RotatorTransport
import java.io.IOException

internal enum class RotatorFailureKind { REJECTED, TIMEOUT, INVALID_REPLY }
internal class RotatorProtocolFailure(
    val kind: RotatorFailureKind,
    message: String,
    val reconnect: Boolean = false
) : IOException(message)

/** The command actor owns serialization. This client never equates a write with an ACK. */
internal class RotatorProtocolClient(private val reader: ControlResponseAccumulator) {
    suspend fun point(link: ControlTransport, settings: RotatorSettings, target: RotatorPosition, onSent: () -> Unit): Boolean {
        if (settings.protocol == RotatorProtocol.ROTCTLD) discardBuffered(link)
        write(link, RotatorCodec.point(settings.protocol, target, settings.customPointTemplate))
        onSent()
        if (settings.protocol != RotatorProtocol.ROTCTLD) return false
        readAck(link)
        return true
    }

    suspend fun stop(link: ControlTransport, settings: RotatorSettings): Boolean {
        val frame = RotatorCodec.stop(settings.protocol, settings.customStopTemplate) ?: return false
        // STOP is written first, even if an earlier response is still buffered.
        if (settings.protocol == RotatorProtocol.ROTCTLD) {
            try { discardBuffered(link) } catch (_: IOException) { /* Never suppress STOP on noisy input. */ }
        }
        write(link, frame)
        if (settings.protocol == RotatorProtocol.ROTCTLD) readAck(link)
        return true
    }

    suspend fun position(link: ControlTransport, settings: RotatorSettings): RotatorPosition {
        if (settings.protocol in setOf(RotatorProtocol.EASYCOMM_II, RotatorProtocol.EASYCOMM_III)) {
            discardBuffered(link)
            write(link, "AZ\n".encodeToByteArray())
            val az = axis(link, true)
            write(link, "EL\n".encodeToByteArray())
            return RotatorPosition(az, axis(link, false))
        }
        val query = RotatorCodec.positionQuery(settings.protocol, settings.customQueryTemplate)
        if (query != null) {
            discardBuffered(link)
            write(link, query)
        }
        val response = reader.readUntil(link, MAX_BYTES, TIMEOUT) { bytes ->
            frameComplete(settings, bytes) && (negativeReply(bytes) != null ||
                RotatorCodec.parsePosition(settings.protocol, bytes, settings.customResponseTemplate) != null)
        }
        checkLink(link)
        if (settings.protocol == RotatorProtocol.ROTCTLD) rejectNegative(response)
        if (!frameComplete(settings, response)) failReply(response)
        return RotatorCodec.parsePosition(settings.protocol, response, settings.customResponseTemplate)
            ?: failReply(response)
    }

    private suspend fun axis(link: ControlTransport, azimuth: Boolean): Double {
        val response = reader.readUntil(link, MAX_BYTES, TIMEOUT) { bytes ->
            terminated(bytes) && parseAxisReply(bytes, azimuth) != null
        }
        checkLink(link)
        if (!terminated(response)) failReply(response)
        return parseAxisReply(response, azimuth) ?: failReply(response)
    }

    private fun parseAxisReply(bytes: ByteArray, azimuth: Boolean) = bytes.decodeToString().lineSequence()
        .mapNotNull { RotatorCodec.parseEasyCommAxis(it, azimuth) }.lastOrNull()

    private suspend fun readAck(link: ControlTransport) {
        val response = reader.readUntil(link, MAX_BYTES, TIMEOUT) { bytes ->
            terminated(bytes) && bytes.decodeToString().lineSequence().any { ACK.matches(it.trim()) }
        }
        checkLink(link)
        val code = response.decodeToString().lineSequence().map(String::trim)
            .mapNotNull { ACK.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull()
        if (code == null) throw RotatorProtocolFailure(RotatorFailureKind.TIMEOUT, "Rotator command ACK timed out", reconnect=true)
        if (code != 0) throw RotatorProtocolFailure(RotatorFailureKind.REJECTED, "Rotator rejected command: RPRT $code")
    }

    private fun frameComplete(settings: RotatorSettings, bytes: ByteArray): Boolean = when {
        settings.protocol == RotatorProtocol.SPID_ROT2PROG -> RotatorCodec.parsePosition(settings.protocol, bytes) != null
        settings.transport == RotatorTransport.UDP -> bytes.isNotEmpty()
        settings.protocol == RotatorProtocol.OZ9AAR_URC -> bytes.decodeToString().trimEnd().endsWith("}")
        else -> terminated(bytes)
    }

    private fun terminated(bytes: ByteArray) = bytes.lastOrNull() == '\n'.code.toByte() || bytes.lastOrNull() == '\r'.code.toByte()
    private fun negativeReply(bytes: ByteArray) = bytes.decodeToString().lineSequence().map(String::trim)
        .mapNotNull { ACK.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.firstOrNull { it != 0 }
    private fun rejectNegative(bytes: ByteArray) {
        negativeReply(bytes)?.let { throw RotatorProtocolFailure(RotatorFailureKind.REJECTED, "Rotator query rejected: RPRT $it") }
    }
    private fun failReply(bytes: ByteArray): Nothing = throw RotatorProtocolFailure(
        if (bytes.isEmpty()) RotatorFailureKind.TIMEOUT else RotatorFailureKind.INVALID_REPLY,
        if (bytes.isEmpty()) "Rotator position feedback timed out" else "Invalid or incomplete rotator position feedback")

    private suspend fun discardBuffered(link: ControlTransport) {
        repeat(16) { if (link.readAvailable(256).isEmpty()) return }
        throw IOException("Rotator input could not be synchronized")
    }
    private suspend fun write(link: ControlTransport, bytes: ByteArray) {
        if (!link.isConnected || !link.write(bytes)) throw IOException("Rotator transport write failed")
    }
    private fun checkLink(link: ControlTransport) {
        if (!link.isConnected) throw IOException("Rotator transport disconnected")
    }
    private companion object {
        const val MAX_BYTES = 4096
        const val TIMEOUT = 500L
        val ACK = Regex("RPRT\\s+(-?\\d+)")
    }
}
