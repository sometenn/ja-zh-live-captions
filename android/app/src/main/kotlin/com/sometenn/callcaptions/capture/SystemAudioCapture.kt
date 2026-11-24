package com.sometenn.callcaptions.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sometenn.callcaptions.core.AudioCapture
import com.sometenn.callcaptions.core.AudioChunk
import com.sometenn.callcaptions.core.CaptionError
import com.sometenn.callcaptions.core.PcmEncoder
import com.sometenn.callcaptions.service.CaptionForegroundService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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
 * Android 10+ system playback audio capture backed by [MediaProjection] +
 * [AudioPlaybackCaptureConfiguration] + [CaptionForegroundService] (`mediaProjection`).
 *
 * Follows Android 14+ lifecycle rules strictly:
 * 1. Obtain MediaProjection consent from Activity.
 * 2. Start ForegroundService with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` and await ready.
 * 3. Call `getMediaProjection`, register [MediaProjection.Callback], then start [AudioRecord].
 */
class SystemAudioCapture(
    private val context: Context,
    private val consentCoordinator: MediaProjectionConsentCoordinator,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Double = { System.nanoTime() / 1_000_000_000.0 },
) : AudioCapture {

    private val appContext = context.applicationContext
    private val channel = Channel<AudioChunk>(
        capacity = 600,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val audio: Flow<AudioChunk> = channel.receiveAsFlow()
    override var started: (() -> Unit)? = null

    private val captureScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var readJob: Job? = null

    override fun present() {
        if (closed.get()) return
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPermission) {
            channel.close(CaptionError("机内声音采集需要录音权限，请先允许录音权限。"))
            return
        }

        val consentDeferred = consentCoordinator.requestConsent()
        readJob = captureScope.launch {
            try {
                val consent = consentDeferred.await()
                if (closed.get()) return@launch
                if (consent == null) {
                    throw CaptionError("已取消机内声音共享授权。")
                }

                CaptionForegroundService.startAndAwaitReady(
                    context = appContext,
                    captureType = CaptionForegroundService.CaptureType.MEDIA_PROJECTION,
                )
                if (closed.get()) return@launch

                val projectionManager = appContext.getSystemService(MediaProjectionManager::class.java)
                    ?: throw CaptionError("此设备不支持系统声音共享（MediaProjection）。")
                val projection = projectionManager.getMediaProjection(consent.resultCode, consent.data)
                    ?: throw CaptionError("无法创建系统声音共享会话，请重新授权。")
                mediaProjection = projection

                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (!closed.get()) {
                            channel.close(CaptionError("系统已结束声音共享。请回到应用重新开始。"))
                        }
                    }
                }
                projectionCallback = callback
                projection.registerCallback(callback, Handler(Looper.getMainLooper()))

                val record = createPlaybackAudioRecord(projection)
                    ?: throw CaptionError("无法初始化机内声音采集，请确认系统支持 AudioPlaybackCapture。")
                if (closed.get()) {
                    record.release()
                    return@launch
                }
                audioRecord = record
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw CaptionError("无法启动机内声音采集，请重试。")
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
                            throw CaptionError("机内声音读取中断（错误码 $read）。请重新开始。")
                        }
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CaptionError) {
                if (!closed.get()) {
                    channel.close(e)
                }
            } catch (e: SecurityException) {
                if (!closed.get()) {
                    channel.close(CaptionError("系统拒绝了机内声音共享权限，请重新授权。"))
                }
            } catch (_: Throwable) {
                if (!closed.get()) {
                    channel.close(CaptionError("机内声音共享发生异常，请重试。"))
                }
            } finally {
                releaseResources()
                CaptionForegroundService.stop(appContext)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createPlaybackAudioRecord(projection: MediaProjection): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufferSize = maxOf(minBuf * 2, CHUNK_SAMPLES * 2 * 4)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return try {
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun releaseResources() {
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (_: Throwable) {
                // Ignore teardown errors.
            }
            try {
                record.release()
            } catch (_: Throwable) {
                // Ignore teardown errors.
            }
        }

        val projection = mediaProjection
        val callback = projectionCallback
        mediaProjection = null
        projectionCallback = null
        if (projection != null) {
            if (callback != null) {
                try {
                    projection.unregisterCallback(callback)
                } catch (_: Throwable) {
                    // Ignore teardown errors.
                }
            }
            try {
                projection.stop()
            } catch (_: Throwable) {
                // Ignore teardown errors.
            }
        }
    }

    override suspend fun stop() {
        if (!closed.compareAndSet(false, true)) return
        consentCoordinator.cancelPending()
        val job = readJob
        readJob = null
        job?.cancel()
        releaseResources()
        CaptionForegroundService.stop(appContext)
        channel.close()
        captureScope.cancel()
        job?.join()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SAMPLES = 320 // 20 ms @ 16 kHz mono
    }
}
