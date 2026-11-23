package com.sometenn.callcaptions.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class AudioChunk(
    val capturedAt: Double,
    val data: ByteArray,
    val peak: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return capturedAt == other.capturedAt && peak == other.peak && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = capturedAt.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + peak
        return result
    }
}

/**
 * Bounded FIFO PCM buffer enforcing both maximum age and maximum byte capacity.
 */
class AudioBufferQueue(
    maxBytes: Int = DEFAULT_MAX_BYTES,
    maxAge: Double = DEFAULT_MAX_AGE,
) {
    companion object {
        const val DEFAULT_MAX_BYTES = 160_000 // 5.0 seconds of 16 kHz, 16-bit mono PCM
        const val DEFAULT_MAX_AGE = 5.0
    }

    val maxBytes: Int = maxOf(0, maxBytes)
    val maxAge: Double = maxOf(0.0, maxAge)

    private val storage = ArrayDeque<AudioChunk>()

    var totalBytes: Int = 0
        private set
    var droppedCount: Int = 0
        private set
    var droppedBytes: Int = 0
        private set

    val count: Int get() = storage.size
    val isEmpty: Boolean get() = storage.isEmpty()

    fun pruneExpired(now: Double) {
        while (storage.isNotEmpty() && now - storage.first().capturedAt > maxAge) {
            val evicted = storage.removeFirst()
            totalBytes -= evicted.data.size
            droppedCount += 1
            droppedBytes += evicted.data.size
        }
    }

    fun enqueue(chunk: AudioChunk, now: Double) {
        if (chunk.data.isEmpty()) return
        pruneExpired(now)
        if (now - chunk.capturedAt > maxAge || chunk.data.size > maxBytes) {
            droppedCount += 1
            droppedBytes += chunk.data.size
            return
        }
        while (storage.isNotEmpty() && totalBytes + chunk.data.size > maxBytes) {
            val evicted = storage.removeFirst()
            totalBytes -= evicted.data.size
            droppedCount += 1
            droppedBytes += evicted.data.size
        }
        storage.addLast(chunk)
        totalBytes += chunk.data.size
    }

    fun dequeue(now: Double): AudioChunk? {
        pruneExpired(now)
        if (storage.isEmpty()) return null
        val next = storage.removeFirst()
        totalBytes -= next.data.size
        return next
    }

    fun clear() {
        storage.clear()
        totalBytes = 0
    }
}

/**
 * Stateful linear resampler & downmixer converting arbitrary PCM buffers to
 * 16 kHz, 16-bit signed little-endian mono PCM.
 * Intended to be called serially on one capture thread/dispatcher.
 */
class PcmEncoder(
    private val targetSampleRate: Int = 16_000,
) {
    private var lastSampleRate: Int = 0
    private var lastChannelCount: Int = 0
    private var nextInputPosition: Double = 0.0
    private var previousTailSample: Float = 0f
    private var hasPreviousTail: Boolean = false

    fun reset() {
        lastSampleRate = 0
        lastChannelCount = 0
        nextInputPosition = 0.0
        previousTailSample = 0f
        hasPreviousTail = false
    }

    fun encodeFloat(
        interleaved: FloatArray,
        frameCount: Int,
        sampleRate: Int,
        channels: Int,
        capturedAt: Double,
    ): AudioChunk? {
        if (frameCount <= 0 || sampleRate <= 0 || channels <= 0) return null
        val requiredLength = frameCount * channels
        if (interleaved.size < requiredLength) {
            throw CaptionError("无法读取系统音频采样。")
        }
        val mono = FloatArray(frameCount)
        if (channels == 1) {
            System.arraycopy(interleaved, 0, mono, 0, frameCount)
        } else {
            val invChannels = 1f / channels
            for (i in 0 until frameCount) {
                var sum = 0f
                val base = i * channels
                for (ch in 0 until channels) {
                    sum += interleaved[base + ch]
                }
                mono[i] = sum * invChannels
            }
        }
        return encodeMono(mono, sampleRate, channels, capturedAt)
    }

    fun encodePcm16(
        interleaved: ShortArray,
        frameCount: Int,
        sampleRate: Int,
        channels: Int,
        capturedAt: Double,
    ): AudioChunk? {
        if (frameCount <= 0 || sampleRate <= 0 || channels <= 0) return null
        val requiredLength = frameCount * channels
        if (interleaved.size < requiredLength) {
            throw CaptionError("无法读取系统音频采样。")
        }

        if (sampleRate == targetSampleRate && channels == 1) {
            if (lastSampleRate != sampleRate || lastChannelCount != channels) {
                reset()
                lastSampleRate = sampleRate
                lastChannelCount = channels
            }
            val bytes = ByteArray(frameCount * 2)
            var peak = 0
            for (i in 0 until frameCount) {
                val sample = interleaved[i].toInt()
                val magnitude = abs(sample)
                if (magnitude > peak) peak = magnitude
                bytes[i * 2] = (sample and 0xFF).toByte()
                bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            return AudioChunk(capturedAt = capturedAt, data = bytes, peak = peak)
        }

        val mono = FloatArray(frameCount)
        val scale = 1f / 32768f
        if (channels == 1) {
            for (i in 0 until frameCount) {
                mono[i] = interleaved[i] * scale
            }
        } else {
            val invChannels = scale / channels
            for (i in 0 until frameCount) {
                var sum = 0
                val base = i * channels
                for (ch in 0 until channels) {
                    sum += interleaved[base + ch].toInt()
                }
                mono[i] = sum * invChannels
            }
        }
        return encodeMono(mono, sampleRate, channels, capturedAt)
    }

    private fun encodeMono(
        mono: FloatArray,
        sampleRate: Int,
        channels: Int,
        capturedAt: Double,
    ): AudioChunk? {
        if (mono.isEmpty()) return null
        if (lastSampleRate != sampleRate || lastChannelCount != channels) {
            reset()
            lastSampleRate = sampleRate
            lastChannelCount = channels
        }

        val step = sampleRate.toDouble() / targetSampleRate.toDouble()
        val maxOutputFrames = ceil((mono.size - nextInputPosition) / step).toInt().coerceAtLeast(0) + 2
        val outputSamples = ShortArray(maxOutputFrames)
        var outCount = 0
        var peak = 0
        var pos = nextInputPosition

        while (pos < mono.size) {
            val sampleFloat = if (pos < 0.0) {
                val frac = (pos + 1.0).toFloat()
                val prev = if (hasPreviousTail) previousTailSample else mono[0]
                prev + (mono[0] - prev) * frac
            } else {
                val idx = pos.toInt()
                val frac = (pos - idx).toFloat()
                val s0 = mono[idx]
                val s1 = if (idx + 1 < mono.size) mono[idx + 1] else s0
                s0 + (s1 - s0) * frac
            }

            val clamped = (sampleFloat * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val magnitude = abs(clamped)
            if (magnitude > peak) peak = magnitude
            outputSamples[outCount++] = clamped.toShort()
            pos += step
        }

        nextInputPosition = pos - mono.size
        previousTailSample = mono[mono.size - 1]
        hasPreviousTail = true

        if (outCount == 0) return null
        val bytes = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            val s = outputSamples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return AudioChunk(capturedAt = capturedAt, data = bytes, peak = peak)
    }
}
