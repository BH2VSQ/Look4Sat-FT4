package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.repository.CwTransmitPresets
import com.rtbishop.look4sat.core.domain.repository.CwMessageTemplate
import com.rtbishop.look4sat.core.domain.repository.HandsetMacroStatus
import kotlinx.coroutines.withTimeoutOrNull

internal object HandsetMacroSync {
    fun normalize(presets: List<String>): List<String> {
        require(presets.size == 4) { "Four CW presets are required" }
        return presets.mapIndexed { index, text ->
            CwTransmitPresets.normalize(text).also {
                require(!CwMessageTemplate.hasVariables(it)) { "F${index + 1}: handset presets require plain text" }
                require(it.length <= 92) { "F${index + 1} exceeds the handset limit of 92 characters" }
            }
        }
    }

    suspend fun cancel(command: suspend (ByteArray) -> ByteArray?): Boolean = try {
        withTimeoutOrNull(2000) { command(byteArrayOf(4)) != null } ?: false
    } catch (_: Exception) { false }

    suspend fun stage(presets: List<String>, transaction: Long, slotCount: Int = 8,
        command: suspend (ByteArray) -> ByteArray?): HandsetMacroStatus {
        require(transaction in 1..0xffffffffL) { "Invalid CW preset transaction" }
        require(slotCount == 4 || slotCount == 8) { "Unsupported handset preset capacity" }
        val texts = normalize(presets).toMutableList()
        suspend fun exchange(data: ByteArray): HandsetMacroStatus =
            checkNotNull(command(data)?.let(L4sProtocol::macroStatus)) { "CW preset synchronization failed; retry" }
        val status = exchange(byteArrayOf(0, -1))
        check(!status.readOnly) { "Handset macro storage is read-only" }
        check(!status.busy) { "Handset macro storage is busy" }
        // Older firmware commits eight slots atomically. Preserve its unused last four.
        for (slot in 4 until slotCount) {
            val saved = exchange(byteArrayOf(0, slot.toByte()))
            check(saved.slot == slot && !saved.busy && saved.generation == status.generation) { "Handset presets changed; retry" }
            texts += saved.text
        }
        val begin = ByteArray(9).also {
            it[0] = 1; L4sProtocol.put32(it, 1, status.generation); L4sProtocol.put32(it, 5, transaction)
        }
        check(exchange(begin).phase == 1) { "Handset did not accept the CW preset batch" }
        texts.forEachIndexed { index, text ->
            check(exchange(byteArrayOf(2, index.toByte(), text.length.toByte()) + text.toByteArray(Charsets.US_ASCII)).phase == 1) {
                "Handset rejected F${index + 1}"
            }
        }
        return exchange(byteArrayOf(3)).also {
            check(it.waitingForConfirmation) { "Handset did not accept the complete CW preset batch" }
        }
    }
}
