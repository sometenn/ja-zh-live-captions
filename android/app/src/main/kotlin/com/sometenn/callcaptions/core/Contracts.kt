package com.sometenn.callcaptions.core

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class AudioSource(
    val rawValue: String,
    val title: String,
) {
    SYSTEM("system", "机内声音"),
    MICROPHONE("microphone", "麦克风");

    companion object {
        fun fromRawValue(raw: String?): AudioSource =
            entries.firstOrNull { it.rawValue == raw } ?: SYSTEM
    }
}

interface AudioCapture {
    val audio: Flow<AudioChunk>
    var started: (() -> Unit)?
    fun present()
    suspend fun stop()
}

/**
 * Placeholder capture used in Stage 1 before Stage 2 (microphone) and Stage 3 (MediaProjection) are implemented.
 */
class StubAudioCapture(
    private val message: String = "音频采集将在后续阶段接入。",
) : AudioCapture {
    override val audio: Flow<AudioChunk> = flow {
        throw CaptionError(message)
    }
    override var started: (() -> Unit)? = null
    override fun present() = Unit
    override suspend fun stop() = Unit
}

interface OverlaySink {
    val active: Boolean
    val lastFrame: OverlayFrame?
    fun prepare()
    fun start()
    fun draw(frame: OverlayFrame)
    fun stop()
}

class RecordingOverlaySink : OverlaySink {
    override var active: Boolean = false
        private set
    override var lastFrame: OverlayFrame? = null
        private set
    var prepared: Boolean = false
        private set

    override fun prepare() {
        prepared = true
    }

    override fun start() {
        active = true
    }

    override fun draw(frame: OverlayFrame) {
        lastFrame = frame
    }

    override fun stop() {
        active = false
    }
}

interface PreferencesStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class InMemoryPreferencesStore : PreferencesStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

class AndroidPreferencesStore(
    private val prefs: SharedPreferences,
) : PreferencesStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
