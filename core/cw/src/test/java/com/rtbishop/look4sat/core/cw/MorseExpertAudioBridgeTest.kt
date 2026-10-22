package com.rtbishop.look4sat.core.cw

import com.rtbishop.look4sat.core.domain.audio.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MorseExpertAudioBridgeTest {
    private class Hub(private val chunks: List<TimestampedAudioChunk>) : IAudioHub {
        override val state = MutableStateFlow<AudioHubState>(AudioHubState.Idle)
        override val inputDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList())
        var delivered = 0
        override fun audioFlow(consumer: AudioConsumer, delivery: AudioDelivery): Flow<TimestampedAudioChunk> {
            assertEquals(AudioConsumer.MORSE_EXPERT, consumer)
            assertEquals(AudioDelivery.RELIABLE, delivery)
            delivered++
            return chunks.asFlow()
        }
        override suspend fun stopAll() = Unit
        override suspend fun selectInputDevice(deviceKey: String?) = Unit
    }

    private fun audio(rate: Int, sizes: List<Int>, jitter: Boolean): List<TimestampedAudioChunk> {
        val samples = FloatArray(rate) { sin(2 * PI * 700 * it / rate).toFloat() }
        val result = mutableListOf<TimestampedAudioChunk>()
        var offset = 0
        while (offset < samples.size) {
            val count = sizes[result.size % sizes.size].coerceAtMost(samples.size - offset)
            val timestamp = if (jitter) (result.size / 5).toLong() * 100_000_000 else offset.toLong() * 1_000_000_000 / rate
            result += TimestampedAudioChunk(samples.copyOfRange(offset, offset + count), rate, offset.toLong(), timestamp, AudioSampleFormat.PCM_FLOAT)
            offset += count
        }
        return result
    }

    private fun decode(chunks: List<TimestampedAudioChunk>): FloatArray = runBlocking {
        val hub = Hub(chunks)
        val output = mutableListOf<Float>()
        MorseExpertAudioBridge(hub) { output.addAll(it.toList()) }.collect()
        assertEquals(1, hub.delivered)
        output.toFloatArray()
    }

    @Test fun arbitraryCaptureChunksAndJitterDoNotLoseOrDuplicateResampledAudio() {
        for (rate in listOf(8000, 44100, 48000)) {
            val whole = decode(audio(rate, listOf(rate), false))
            val fragmented = decode(audio(rate, listOf(1, 63, 97, 1000, 17, 513), true))
            assertTrue(whole.size in 7990..8000)
            assertArrayEquals(whole, fragmented, 0.000001f)
        }
    }

    @Test fun capturedToneStaysIdenticalWhenCallbackWallClockChanges() {
        val smooth = decode(audio(48000, listOf(960), false))
        val bursty = decode(audio(48000, listOf(960), true))
        assertArrayEquals(smooth, bursty, 0f)
    }
}
