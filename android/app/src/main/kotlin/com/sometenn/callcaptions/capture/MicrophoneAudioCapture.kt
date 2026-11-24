package com.sometenn.callcaptions.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.sometenn.callcaptions.core.AudioCapture
import com.sometenn.callcaptions.core.AudioChunk
import com.sometenn.callcaptions.core.CaptionError
import com.sometenn.callcaptions.core.PcmEncoder
import com.sometenn.callcaptions.service.CaptionForegroundService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android microphone capture backed by [AudioRecord] + [CaptionForegroundService].
 * Emits 16 kHz, 16-bit signed little-endian mono PCM chunks (~20 ms per chunk).
 */
class MicrophoneAudioCapture(
    private val context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Double = { System.nanoTime() / 1_000_000_000.0 },
) : AudioCapture {

    private val channel = Channel<AudioChunk>(
        capacity = 600,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val audio: Flow<AudioChunk> = channel.receiveAsFlow()
    override var started: (() -> Unit)? = null

    private val captureScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var readJob: Job? = null

    override fun present() {
        if (closed.get()) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            channel.close(CaptionError("请先允许麦克风权限。"))
            return
        }

        readJob = captureScope.launch {
            try {
                CaptionForegroundService.start(context)
                val record = createAudioRecord()
                    ?: throw CaptionError("无法初始化麦克风录音设备，请检查是否被其他应用占用。")
                if (closed.get()) {
                    record.release()
                    return@launch
                }
                audioRecord = record
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw CaptionError("无法启动麦克风收音，请重试。")
                }

                withContext(mainDispatcher) {
                    if (!closed.get()) started?.invoke()
                }

                val encoder = PcmEncoder(targetSampleRate = SAMPLE_RATE)
                val buffer = ShortArray(CHUNK_SAMPLES)
                while (isActive && !closed.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val chunk = encoder.encodePcm16(
                            interleaved = buffer,
                            frameCount = read,
                            sampleRate = SAMPLE_RATE,
                            channels = 1,
                            capturedAt = clock(),
                        )
                        if (chunk != null) {
                            channel.trySend(chunk)
                        }
                    } else if (read < 0) {
                        if (!closed.get()) {
                            throw CaptionError("麦克风读取中断（错误码 $read）。请重新开始。")
                        }
                        break
                    }
                }
            } catch (e: CaptionError) {
                if (!closed.get()) {
                    channel.close(e)
                }
            } catch (_: Throwable) {
                if (!closed.get()) {
                    channel.close(CaptionError("麦克风收音发生异常，请重试。"))
                }
            } finally {
                releaseRecord()
                CaptionForegroundService.stop(context)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufferSize = maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 4)

        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (audioSource in candidates) {
            try {
                val record = AudioRecord(
                    audioSource,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return record
                }
                record.release()
            } catch (_: Throwable) {
                // Try fallback source.
            }
        }
        return null
    }

    private fun releaseRecord() {
        val record = audioRecord ?: return
        audioRecord = null
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Throwable) {
            // Ignore stop errors during teardown.
        }
        try {
            record.release()
        } catch (_: Throwable) {
            // Ignore release errors during teardown.
        }
    }

    override suspend fun stop() {
        if (!closed.compareAndSet(false, true)) return
        val job = readJob
        readJob = null
        job?.cancel()
        releaseRecord()
        CaptionForegroundService.stop(context)
        channel.close()
        captureScope.cancel()
        job?.join()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 320 // 20 ms @ 16 kHz mono
    }
}
