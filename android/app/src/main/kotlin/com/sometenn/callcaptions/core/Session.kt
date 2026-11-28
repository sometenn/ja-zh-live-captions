package com.sometenn.callcaptions.core

import java.io.IOException
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SessionState(
    val stage: Session.Stage,
    val mode: Session.Mode,
    val status: String,
    val level: Int,
    val hasKey: Boolean,
    val source: AudioSource,
    val provider: Provider,
    val displayMode: CaptionDisplayMode,
    val switchNotice: String,
    val connectionState: Session.ConnectionState,
    val frame: OverlayFrame,
    val hasTranscript: Boolean = false,
)

/**
 * Core live-caption session controller ported 1:1 from iOS 0.3.6 `Sources/Session.swift`.
 * Must be driven from a single-threaded/Main [scope].
 */
class Session(
    private val scope: CoroutineScope,
    private val prefs: PreferencesStore = InMemoryPreferencesStore(),
    val overlay: OverlaySink = RecordingOverlaySink(),
    private val makeCapture: (AudioSource) -> AudioCapture = { StubAudioCapture() },
    private val makeService: (Provider, String) -> CaptionService = { provider, key -> provider.connect(key) },
    private val readKey: (Provider) -> String = { "" },
    private val writeKey: (String, Provider) -> Unit = { _, _ -> },
    private val clock: () -> Double = { System.nanoTime() / 1_000_000_000.0 },
    private val sleep: suspend (Double) -> Unit = { seconds ->
        val ms = maxOf(0L, (seconds * 1000.0).roundToLong())
        delay(ms)
    },
    queueMaxBytes: Int = AudioBufferQueue.DEFAULT_MAX_BYTES,
    queueMaxAge: Double = AudioBufferQueue.DEFAULT_MAX_AGE,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val enableTicker: Boolean = true,
) {
    enum class Mode { PROBE, TRANSLATE }
    enum class Stage { IDLE, PICKING, LISTENING, STOPPING }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState()
    }

    companion object {
        private const val SOURCE_KEY = "audioInputSource"
        private const val PROVIDER_KEY = "translationProvider"
        private const val DISPLAY_MODE_KEY = "captionDisplayMode"
    }

    var stage: Stage = Stage.IDLE
        private set
    var mode: Mode = Mode.PROBE
        private set
    var status: String = "准备就绪"
        private set
    var level: Int = 0
        private set
    var hasKey: Boolean = false
        private set
    var source: AudioSource
        private set
    var provider: Provider
        private set
    var displayMode: CaptionDisplayMode
        private set
    var switchNotice: String = ""
        private set
    var connectionState: ConnectionState = ConnectionState.Disconnected
        private set
    var frame: OverlayFrame
        private set

    val isReconnecting: Boolean
        get() = connectionState is ConnectionState.Reconnecting

    val queuedAudioCount: Int get() = pcmQueue.count
    val queuedAudioBytes: Int get() = pcmQueue.totalBytes
    val currentCaptions: Captions get() = captions.snapshot()

    private val _state: MutableStateFlow<SessionState>
    val state: StateFlow<SessionState> get() = _state.asStateFlow()

    private var capture: AudioCapture? = null
    private var client: CaptionService? = null
    private var activeKey: String = ""
    private var acceptAudioSince: Double = 0.0
    private var sessionID: Long = 0L
    private var connectionID: Long = 0L
    private var worker: Job? = null
    private var ticker: Job? = null
    private var connectTask: Job? = null
    private var drainTask: Job? = null
    private var reconnectTask: Job? = null
    private val pcmQueue = AudioBufferQueue(maxBytes = queueMaxBytes, maxAge = queueMaxAge)
    private var transcriptSessionID: Long? = null
    private val originalRecord = StringBuilder()
    private val translationRecord = StringBuilder()

    fun transcriptText(): String = "日语原文\n$originalRecord\n\n简体中文译文\n" +
        IcuChineseSimplifier.simplify(translationRecord.toString()) + "\n"

    private fun record(tokens: List<Token>, sid: Long) {
        for (token in tokens) {
            if (!token.isFinal || token.text.isEmpty() || token.text == "<end>" || token.text == "<fin>") continue
            if (transcriptSessionID != sid) {
                originalRecord.clear()
                translationRecord.clear()
                transcriptSessionID = sid
            }
            val target = if (token.translationStatus == "translation") translationRecord else originalRecord
            target.append(token.text)
        }
    }

    private var captions = Captions()
    private var latestPeak: Int = 0
    private var lastSignal: Double? = null
    private var lastBuffer: Double? = null
    private var listeningSince: Double = 0.0
    private var lastDraw: Double = 0.0

    private val now: Double get() = clock()

    init {
        val savedProvider = Provider.fromRawValue(prefs.getString(PROVIDER_KEY))
        val savedDisplayMode = CaptionDisplayMode.fromRawValue(prefs.getString(DISPLAY_MODE_KEY))
        source = AudioSource.fromRawValue(prefs.getString(SOURCE_KEY))
        provider = savedProvider
        displayMode = savedDisplayMode
        val initialFrame = CaptionPresentation.idleFrame(savedDisplayMode)
        frame = initialFrame

        try {
            hasKey = readKey(savedProvider).isNotEmpty()
        } catch (e: Throwable) {
            status = e.message ?: "读取密钥失败"
        }

        _state = MutableStateFlow(snapshotState())
        overlay.draw(initialFrame)
    }

    fun selectSource(next: AudioSource) {
        if (stage != Stage.IDLE) return
        source = next
        prefs.putString(SOURCE_KEY, next.rawValue)
        publishState()
    }

    fun selectDisplayMode(next: CaptionDisplayMode) {
        if (next == displayMode || stage == Stage.STOPPING) return
        displayMode = next
        prefs.putString(DISPLAY_MODE_KEY, next.rawValue)
        if (stage == Stage.LISTENING) {
            refresh(force = true)
        } else {
            drawIdlePreview()
        }
        publishState()
    }

    fun refreshPreviewIfIdle() {
        if (stage != Stage.IDLE) return
        drawIdlePreview()
        publishState()
    }

    fun select(next: Provider) {
        if (next == provider || (stage != Stage.IDLE && stage != Stage.LISTENING)) return
        try {
            val key = readKey(next)
            if (stage == Stage.LISTENING && mode == Mode.TRANSLATE) {
                if (key.isEmpty()) {
                    switchNotice = "请先结束收音，为 ${next.rawValue} 设置密钥。当前服务仍为 ${provider.rawValue}。"
                    publishState()
                    return
                }
                provider = next
                hasKey = true
                switchNotice = ""
                prefs.putString(PROVIDER_KEY, next.rawValue)
                activeKey = key
                acceptAudioSince = now
                resetProviderConnection()
                pcmQueue.clear()
                captions = Captions()
                reconnectPolicy.reset()
                lastSignal = null
                connectionID += 1
                val nextCid = connectionID
                connectionState = ConnectionState.Connecting
                refresh(force = true)
                startConnection(sid = sessionID, cid = nextCid, isReconnect = false)
                publishState()
                return
            }
            provider = next
            hasKey = key.isNotEmpty()
            switchNotice = ""
            prefs.putString(PROVIDER_KEY, next.rawValue)
            if (stage == Stage.LISTENING) {
                refresh(force = true)
            }
            publishState()
        } catch (e: Throwable) {
            switchNotice = e.message ?: "切换服务失败"
            publishState()
        }
    }

    fun saveKey(value: String) {
        val trimmed = value.trim()
        writeKey(trimmed, provider)
        hasKey = trimmed.isNotEmpty()
        publishState()
    }

    fun begin(requested: Mode) {
        if (stage != Stage.IDLE) return
        var key = ""
        if (requested == Mode.TRANSLATE) {
            try {
                key = readKey(provider)
            } catch (e: Throwable) {
                status = e.message ?: "读取密钥失败"
                publishState()
                return
            }
            if (key.isEmpty()) {
                status = "请先填写 ${provider.rawValue} API 密钥。"
                publishState()
                return
            }
        }
        sessionID += 1
        val sid = sessionID
        connectionID += 1
        activeKey = key
        acceptAudioSince = now
        switchNotice = ""
        mode = requested
        captions = Captions()
        pcmQueue.clear()
        reconnectPolicy.reset()
        connectionState = ConnectionState.Disconnected
        latestPeak = 0
        level = 0
        lastSignal = null
        lastBuffer = null
        stage = Stage.PICKING
        status = if (source == AudioSource.SYSTEM) "请在系统窗口中选择共享整个屏幕" else "正在开启麦克风"
        publishState()

        val input = makeCapture(source)
        capture = input
        input.started = {
            if (sessionID == sid && capture === input && stage == Stage.PICKING) {
                stage = Stage.LISTENING
                listeningSince = now
                if (source == AudioSource.SYSTEM) {
                    overlay.prepare()
                }
                if (requested == Mode.TRANSLATE) {
                    connectionID += 1
                    val cid = connectionID
                    connectionState = ConnectionState.Connecting
                    startConnection(sid = sid, cid = cid, isReconnect = false)
                }
                refresh(force = true)
                if (enableTicker) {
                    ticker = scope.launch {
                        while (isActive) {
                            delay(160)
                            if (isActive) refresh()
                        }
                    }
                }
                publishState()
            }
        }

        worker = scope.launch {
            try {
                input.audio.collect { chunk ->
                    if (!isActive) throw CancellationException()
                    if (sessionID != sid || capture !== input || stage == Stage.STOPPING) return@collect
                    if (chunk.capturedAt < acceptAudioSince) return@collect
                    lastBuffer = now
                    latestPeak = maxOf(latestPeak, chunk.peak)
                    if (chunk.peak >= 32) {
                        lastSignal = now
                    }
                    if (requested == Mode.TRANSLATE) {
                        pcmQueue.enqueue(chunk, now = now)
                        if (stage == Stage.LISTENING && connectionState == ConnectionState.Connected) {
                            scheduleDrain()
                        }
                    }
                }
                if (isActive && sessionID == sid && capture === input && stage != Stage.STOPPING && stage != Stage.IDLE) {
                    finish(if (source == AudioSource.SYSTEM) "声音共享已结束。" else "麦克风收音已结束。")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isActive && sessionID == sid && capture === input) {
                    finish(e.message ?: "音频采集发生错误。")
                }
            }
        }
        input.present()
    }

    fun tick(force: Boolean = false) {
        refresh(force = force)
    }

    private fun startConnection(sid: Long, cid: Long, isReconnect: Boolean) {
        if (sessionID != sid || connectionID != cid || stage != Stage.LISTENING || mode != Mode.TRANSLATE) return
        val service = makeService(provider, activeKey)
        client = service
        if (!isReconnect) {
            connectionState = ConnectionState.Connecting
        }
        publishState()

        connectTask = scope.launch {
            try {
                service.start(
                    receive = { tokens ->
                        if (sessionID == sid && connectionID == cid && client === service && stage == Stage.LISTENING) {
                            record(tokens, sid)
                            captions.apply(tokens)
                            refresh()
                        }
                    },
                    failed = { error ->
                        if (sessionID == sid && connectionID == cid && client === service) {
                            handleConnectionFailure(error, cid = cid, sid = sid)
                        }
                    },
                )
                if (!isActive || sessionID != sid || connectionID != cid || client !== service || stage != Stage.LISTENING) {
                    service.close()
                    return@launch
                }
                connectTask = null
                connectionState = ConnectionState.Connected
                reconnectPolicy.markConnected(at = now)
                refresh(force = true)
                scheduleDrain()
                publishState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (sessionID != sid || connectionID != cid || client !== service) {
                    service.close()
                    return@launch
                }
                connectTask = null
                handleConnectionFailure(e, cid = cid, sid = sid)
            }
        }
    }

    private fun scheduleDrain() {
        if (stage != Stage.LISTENING ||
            mode != Mode.TRANSLATE ||
            connectionState != ConnectionState.Connected ||
            drainTask != null ||
            pcmQueue.isEmpty
        ) {
            return
        }
        val service = client ?: return
        val sid = sessionID
        val cid = connectionID
        drainTask = scope.launch {
            try {
                while (isActive) {
                    if (sessionID != sid ||
                        connectionID != cid ||
                        stage != Stage.LISTENING ||
                        mode != Mode.TRANSLATE ||
                        connectionState != ConnectionState.Connected ||
                        client !== service
                    ) {
                        return@launch
                    }
                    val chunk = pcmQueue.dequeue(now = now) ?: return@launch
                    try {
                        service.send(chunk.data)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (isActive) {
                            handleConnectionFailure(e, cid = cid, sid = sid)
                        }
                        return@launch
                    }
                }
            } finally {
                if (sessionID == sid && connectionID == cid) {
                    drainTask = null
                }
            }
        }
    }

    private fun handleConnectionFailure(error: Throwable, cid: Long, sid: Long) {
        if (sessionID != sid || connectionID != cid || stage != Stage.LISTENING || mode != Mode.TRANSLATE) return
        connectionID += 1
        val nextCid = connectionID
        resetProviderConnection()

        val retryable = when (error) {
            is CaptionError -> error.isRetryable
            is IOException -> true
            else -> false
        }

        if (!retryable) {
            finish(error.message ?: "连接失败。")
            return
        }

        captions.clearProvisional()

        val decision = reconnectPolicy.recordTransientFailure(at = now)
        if (decision == null) {
            finish("重连失败（已达上限）：${error.message ?: "连接中断"}")
            return
        }

        connectionState = ConnectionState.Reconnecting(attempt = decision.attempt, maxAttempts = decision.maxAttempts)
        refresh(force = true)
        publishState()

        reconnectTask = scope.launch {
            try {
                if (decision.delay > 0.0) {
                    sleep(decision.delay)
                }
                if (!isActive) return@launch
            } catch (_: CancellationException) {
                return@launch
            } catch (_: Throwable) {
                return@launch
            }
            if (sessionID != sid || connectionID != nextCid || stage != Stage.LISTENING || mode != Mode.TRANSLATE) {
                return@launch
            }
            reconnectTask = null
            startConnection(sid = sid, cid = nextCid, isReconnect = true)
        }
    }

    private fun resetProviderConnection() {
        connectTask?.cancel()
        connectTask = null
        drainTask?.cancel()
        drainTask = null
        reconnectTask?.cancel()
        reconnectTask = null
        client?.close()
        client = null
    }

    private fun refresh(force: Boolean = false) {
        if (stage != Stage.LISTENING) return
        val timestamp = now
        val sampleAge = timestamp - (lastBuffer ?: listeningSince)
        val audioStalled = sampleAge > 4.0
        val quiet = lastSignal?.let { timestamp - it > 3.0 } ?: true

        when (mode) {
            Mode.PROBE -> {
                status = when {
                    audioStalled -> if (source == AudioSource.SYSTEM) "尚未收到系统音频帧" else "尚未收到麦克风声音"
                    quiet -> if (source == AudioSource.SYSTEM) "等待对方声音" else "等待麦克风声音"
                    else -> if (source == AudioSource.SYSTEM) "已检测到机内声音" else "已检测到麦克风声音"
                }
            }
            Mode.TRANSLATE -> {
                status = when (val conn = connectionState) {
                    is ConnectionState.Reconnecting ->
                        "连接中断，正在重连 ${provider.rawValue}（${conn.attempt}/${conn.maxAttempts}）"
                    ConnectionState.Connecting ->
                        "正在连接 ${provider.rawValue}"
                    ConnectionState.Connected -> {
                        reconnectPolicy.noteHealthy(at = timestamp)
                        when {
                            audioStalled -> if (source == AudioSource.SYSTEM) "尚未收到系统音频帧" else "尚未收到麦克风声音"
                            quiet -> if (source == AudioSource.SYSTEM) "等待对方声音" else "等待麦克风声音"
                            else -> "正在翻译"
                        }
                    }
                    ConnectionState.Disconnected -> "准备翻译"
                }
            }
        }

        val measured = if (latestPeak > 0) {
            (((20.0 * log10(latestPeak.toDouble() / 32768.0) + 60.0) / 6.0).toInt() + 1).coerceIn(0, 10)
        } else {
            0
        }
        level = maxOf(measured, level - 1)
        latestPeak = 0

        val nextFrame = if (mode == Mode.PROBE) {
            CaptionPresentation.probeFrame(status = status, level = level)
        } else {
            CaptionPresentation.translateFrame(
                displayMode = displayMode,
                captions = captions,
                status = status,
                isReconnecting = isReconnecting,
                isConnecting = connectionState == ConnectionState.Connecting,
                isAudioStalled = audioStalled,
            )
        }
        val changed = nextFrame != frame
        if (changed) frame = nextFrame
        if (changed || force || timestamp - lastDraw >= 1.0) {
            overlay.draw(nextFrame)
            lastDraw = timestamp
        }
        publishState()
    }

    private fun drawIdlePreview() {
        val idle = CaptionPresentation.idleFrame(displayMode)
        frame = idle
        overlay.draw(idle)
    }

    fun finish(message: String = "已结束") {
        if (stage == Stage.IDLE || stage == Stage.STOPPING) return
        stage = Stage.STOPPING
        status = if (source == AudioSource.SYSTEM) "正在结束共享" else "正在结束收音"
        sessionID += 1
        val finishSessionID = sessionID
        connectionID += 1
        ticker?.cancel()
        ticker = null
        worker?.cancel()
        worker = null
        resetProviderConnection()
        pcmQueue.clear()
        reconnectPolicy.reset()
        connectionState = ConnectionState.Disconnected
        activeKey = ""
        switchNotice = ""
        overlay.stop()
        captions = Captions()
        latestPeak = 0
        level = 0
        lastSignal = null
        lastBuffer = null
        drawIdlePreview()
        publishState()

        val input = capture
        scope.launch {
            input?.stop()
            if (sessionID != finishSessionID) return@launch
            capture = null
            status = message
            stage = Stage.IDLE
            drawIdlePreview()
            publishState()
        }
    }

    private fun snapshotState(): SessionState = SessionState(
        stage = stage,
        mode = mode,
        status = status,
        level = level,
        hasKey = hasKey,
        source = source,
        provider = provider,
        displayMode = displayMode,
        switchNotice = switchNotice,
        connectionState = connectionState,
        frame = frame,
        hasTranscript = transcriptSessionID != null,
    )

    private fun publishState() {
        _state.value = snapshotState()
    }
}
