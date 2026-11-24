package com.sometenn.callcaptions.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Full async regression suite mirroring iOS `Tests/SessionTests.swift`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    private class AsyncGate {
        private val deferred = CompletableDeferred<Unit>()
        suspend fun await() = deferred.await()
        fun succeed() { deferred.complete(Unit) }
        fun fail(error: Throwable) { deferred.completeExceptionally(error) }
        fun cancel() { deferred.cancel(CancellationException()) }
    }

    private class FakeCapture : AudioCapture {
        private val channel = Channel<AudioChunk>(Channel.UNLIMITED)
        override val audio: Flow<AudioChunk> = channel.receiveAsFlow()
        override var started: (() -> Unit)? = null
        var presented: Boolean = false
            private set
        var stopped: Boolean = false
            private set
        var stopGate: AsyncGate? = null

        override fun present() { presented = true }
        fun emit(chunk: AudioChunk) { channel.trySend(chunk) }
        fun triggerStart() { started?.invoke() }
        fun revoke(error: Throwable = CaptionError("系统已结束声音共享。请回到应用重新开始。")) {
            channel.close(error)
        }

        override suspend fun stop() {
            stopped = true
            channel.close()
            try {
                stopGate?.await()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private class FakeService(
        val provider: Provider,
        val key: String,
    ) : CaptionService {
        var startGate: AsyncGate? = null
        val sendGateForIndex = mutableMapOf<Int, AsyncGate>()
        var startError: Throwable? = null
        var startCalled: Boolean = false
            private set
        var closed: Boolean = false
            private set
        val sent = mutableListOf<ByteArray>()
        private var receiveHandler: ((List<Token>) -> Unit)? = null
        private var failedHandler: ((Throwable) -> Unit)? = null

        override suspend fun start(receive: (List<Token>) -> Unit, failed: (Throwable) -> Unit) {
            startCalled = true
            receiveHandler = receive
            failedHandler = failed
            startGate?.await()
            startError?.let { throw it }
        }

        override suspend fun send(data: ByteArray) {
            val idx = sent.size
            sendGateForIndex[idx]?.await()
            if (closed) {
                throw CaptionError("Socket closed", kind = CaptionError.Kind.TRANSIENT)
            }
            sent.add(data)
        }

        fun deliver(tokens: List<Token>) { receiveHandler?.invoke(tokens) }
        fun disconnect(error: Throwable) { failedHandler?.invoke(error) }

        override fun close() {
            closed = true
            startGate?.cancel()
            sendGateForIndex.values.forEach { it.cancel() }
        }
    }

    private class FakeClock(var now: Double = 100.0) {
        fun advance(delta: Double) { now += delta }
    }

    private class FakeSleeper {
        val delays = mutableListOf<Double>()
        var holdNext: Boolean = false
        var activeGate: AsyncGate? = null
            private set

        suspend fun sleep(seconds: Double) {
            delays.add(seconds)
            if (holdNext) {
                val gate = AsyncGate()
                activeGate = gate
                try {
                    gate.await()
                } finally {
                    if (activeGate === gate) activeGate = null
                }
            }
        }
    }

    private fun pcm(id: Int, count: Int = 4): ByteArray = ByteArray(count) { id.toByte() }
    private fun tok(text: String, final: Boolean, translated: Boolean = false): Token =
        Token(text = text, isFinal = final, translationStatus = if (translated) "translation" else "original")

    private fun assertByteArrayListEquals(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("Mismatch at index $i", expected[i].contentEquals(actual[i]))
        }
    }

    @Test
    fun eagerStartPreStartAudioOrderedDrainNoDuplicates() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(100.0)
        val prefs = InMemoryPreferencesStore()
        val overlay = RecordingOverlaySink()
        val captures = mutableListOf<FakeCapture>()
        val services = mutableListOf<FakeService>()
        val startGate = AsyncGate()
        val sendGate = AsyncGate()

        val session = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay,
            makeCapture = { FakeCapture().also { captures.add(it) } },
            makeService = { provider, key ->
                FakeService(provider, key).also {
                    it.startGate = startGate
                    it.sendGateForIndex[4] = sendGate
                    services.add(it)
                }
            },
            readKey = { "test-key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { },
            enableTicker = false,
        )

        session.begin(Session.Mode.TRANSLATE)
        assertEquals(Session.Stage.PICKING, session.stage)
        assertTrue(services.isEmpty())

        // Pre-start quiet audio raced BEFORE capture.started() fires
        val cap = captures[0]
        cap.emit(AudioChunk(capturedAt = 100.1, data = pcm(1), peak = 0))
        cap.emit(AudioChunk(capturedAt = 100.2, data = pcm(2), peak = 5))
        runCurrent()
        assertTrue("Must not open network before capture starts", services.isEmpty())
        assertEquals(2, session.queuedAudioCount)

        // Authorize & start capture -> provider connects immediately even with quiet audio
        cap.triggerStart()
        runCurrent()
        assertEquals(1, services.size)
        assertTrue(services[0].startCalled)
        assertEquals(Session.Stage.LISTENING, session.stage)
        assertEquals(Session.ConnectionState.Connecting, session.connectionState)
        assertEquals("正在连接 Soniox", session.status)

        // More quiet audio arrives during handshake
        cap.emit(AudioChunk(capturedAt = 100.3, data = pcm(3), peak = 0))
        cap.emit(AudioChunk(capturedAt = 100.4, data = pcm(4), peak = 0))
        runCurrent()
        assertTrue(services[0].sent.isEmpty())
        assertEquals(4, session.queuedAudioCount)

        // Complete handshake -> drains [1, 2, 3, 4] in exact FIFO order
        startGate.succeed()
        runCurrent()
        assertByteArrayListEquals(listOf(pcm(1), pcm(2), pcm(3), pcm(4)), services[0].sent)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)

        // While send #5 is in-flight, #6 arrives -> strictly ordered, zero duplicates
        cap.emit(AudioChunk(capturedAt = 100.5, data = pcm(5), peak = 100))
        cap.emit(AudioChunk(capturedAt = 100.6, data = pcm(6), peak = 100))
        runCurrent()
        assertEquals(4, services[0].sent.size)
        sendGate.succeed()
        runCurrent()
        assertByteArrayListEquals(listOf(pcm(1), pcm(2), pcm(3), pcm(4), pcm(5), pcm(6)), services[0].sent)

        session.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        assertTrue(services[0].closed && cap.stopped)
    }

    @Test
    fun boundedQueueDuringHandshake() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(200.0)
        val prefs = InMemoryPreferencesStore()
        val overlay = RecordingOverlaySink()
        lateinit var capture: FakeCapture
        lateinit var service: FakeService
        val startGate = AsyncGate()

        val session = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay,
            makeCapture = { FakeCapture().also { capture = it } },
            makeService = { provider, key ->
                FakeService(provider, key).also {
                    it.startGate = startGate
                    service = it
                }
            },
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { },
            queueMaxBytes = 8,
            queueMaxAge = 1.5,
            enableTicker = false,
        )

        session.begin(Session.Mode.TRANSLATE)
        capture.triggerStart()
        runCurrent()
        assertTrue(service.startCalled)

        capture.emit(AudioChunk(capturedAt = 200.0, data = pcm(1, count = 4), peak = 0))
        runCurrent()
        clock.advance(0.5)
        capture.emit(AudioChunk(capturedAt = 200.5, data = pcm(2, count = 4), peak = 0))
        runCurrent()
        clock.advance(0.5)
        // Exceeds 8-byte cap -> evicts chunk 1
        capture.emit(AudioChunk(capturedAt = 201.0, data = pcm(3, count = 4), peak = 0))
        runCurrent()
        assertEquals(8, session.queuedAudioBytes)

        // Advance clock so chunk 2 (200.5) expires (> 1.5s old at 202.2), leaving only chunk 3
        clock.advance(1.2)
        startGate.succeed()
        runCurrent()
        assertByteArrayListEquals(listOf(pcm(3, count = 4)), service.sent)

        session.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
    }

    @Test
    fun localProbeNeverOpensNetworkAndRendersProbeCorrectly() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(300.0)
        val prefs = InMemoryPreferencesStore()
        val overlay = RecordingOverlaySink()
        lateinit var capture: FakeCapture
        val sources = mutableListOf<AudioSource>()
        var serviceCreations = 0

        val session = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay,
            makeCapture = { src ->
                sources.add(src)
                FakeCapture().also { capture = it }
            },
            makeService = { provider, key ->
                serviceCreations += 1
                FakeService(provider, key)
            },
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { },
            enableTicker = false,
        )

        // Even in Chinese-only mode, probe MUST render probe status + meter and never open network
        session.selectDisplayMode(CaptionDisplayMode.CHINESE_ONLY)
        session.begin(Session.Mode.PROBE)
        capture.triggerStart()
        runCurrent()
        capture.emit(AudioChunk(capturedAt = 300.1, data = pcm(1), peak = 16000))
        runCurrent()
        session.tick(force = true)
        session.select(Provider.GEMINI)
        runCurrent()

        assertEquals(0, serviceCreations)
        val style = overlay.lastFrame?.style
        assertTrue(style is OverlayFrame.Style.Probe)
        val probeStyle = style as OverlayFrame.Style.Probe
        assertEquals("已检测到机内声音", probeStyle.status)
        assertTrue(probeStyle.meter.contains("▰") && probeStyle.meter.length == 10)

        session.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)

        session.selectSource(AudioSource.MICROPHONE)
        session.begin(Session.Mode.PROBE)
        session.selectSource(AudioSource.SYSTEM) // ignored while picking
        capture.triggerStart()
        session.selectSource(AudioSource.SYSTEM) // ignored while listening
        capture.emit(AudioChunk(capturedAt = 300.2, data = pcm(1), peak = 12000))
        runCurrent()
        session.tick(force = true)
        assertEquals(AudioSource.MICROPHONE, session.source)
        assertEquals("已检测到麦克风声音", session.status)
        assertEquals(listOf(AudioSource.SYSTEM, AudioSource.MICROPHONE), sources)
        assertEquals(0, serviceCreations)

        session.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        assertEquals(AudioSource.MICROPHONE, Session(scope = this, prefs = prefs, enableTicker = false).source)
        session.selectSource(AudioSource.SYSTEM)
        assertEquals(AudioSource.SYSTEM, session.source)
    }

    @Test
    fun displayModePersistenceAndLiveSwitching() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(400.0)
        val prefs = InMemoryPreferencesStore()
        val overlay1 = RecordingOverlaySink()
        lateinit var capture: FakeCapture
        lateinit var service: FakeService

        val session1 = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay1,
            makeCapture = { FakeCapture().also { capture = it } },
            makeService = { provider, key -> FakeService(provider, key).also { service = it } },
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { },
            enableTicker = false,
        )
        assertEquals(CaptionDisplayMode.BILINGUAL, session1.displayMode)
        assertEquals(CaptionPresentation.idleFrame(CaptionDisplayMode.BILINGUAL), overlay1.lastFrame)

        session1.begin(Session.Mode.TRANSLATE)
        capture.triggerStart()
        runCurrent()
        assertEquals(Session.ConnectionState.Connected, session1.connectionState)
        overlay1.start()
        assertTrue(overlay1.active)

        service.deliver(listOf(tok("こんにちは", true), tok("你好", true, true)))
        runCurrent()
        assertEquals(OverlayFrame.Style.Bilingual(top = "こんにちは", bottom = "你好"), overlay1.lastFrame?.style)

        // Switch to large Chinese-only WHILE ACTIVE without interrupting capture, service, or overlay
        session1.selectDisplayMode(CaptionDisplayMode.CHINESE_ONLY)
        assertEquals(CaptionDisplayMode.CHINESE_ONLY, session1.displayMode)
        assertEquals(Session.Stage.LISTENING, session1.stage)
        assertFalse(service.closed)
        assertTrue(overlay1.active)
        assertEquals(
            OverlayFrame(banner = null, style = OverlayFrame.Style.ChineseOnly(text = "你好", isPlaceholder = false)),
            overlay1.lastFrame,
        )

        // Switch back to bilingual while active, then persist Chinese-only for restart check
        session1.selectDisplayMode(CaptionDisplayMode.BILINGUAL)
        assertEquals(OverlayFrame.Style.Bilingual(top = "こんにちは", bottom = "你好"), overlay1.lastFrame?.style)
        session1.selectDisplayMode(CaptionDisplayMode.CHINESE_ONLY)

        session1.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session1.stage)

        // New Session loads persisted Chinese-only mode and draws Chinese-only idle preview
        val overlay2 = RecordingOverlaySink()
        val session2 = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay2,
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { },
            enableTicker = false,
        )
        assertEquals(CaptionDisplayMode.CHINESE_ONLY, session2.displayMode)
        assertEquals(CaptionPresentation.idleFrame(CaptionDisplayMode.CHINESE_ONLY), overlay2.lastFrame)
    }

    @Test
    fun transientReconnectPreservesCaptureOverlayCommittedAndClearsProvisional() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(500.0)
        val prefs = InMemoryPreferencesStore()
        val overlay = RecordingOverlaySink()
        val sleeper = FakeSleeper().apply { holdNext = true }
        lateinit var capture: FakeCapture
        val services = mutableListOf<FakeService>()

        val session = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay,
            makeCapture = { FakeCapture().also { capture = it } },
            makeService = { provider, key -> FakeService(provider, key).also { services.add(it) } },
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { sleeper.sleep(it) },
            reconnectPolicy = ReconnectPolicy(maxAttempts = 3, delays = listOf(0.5, 1.0, 2.0), stableDuration = 15.0),
            enableTicker = false,
        )

        session.begin(Session.Mode.TRANSLATE)
        capture.triggerStart()
        runCurrent()
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        overlay.start()
        assertTrue(overlay.active)

        services[0].deliver(
            listOf(
                tok("今日は晴れ。", true),
                tok("明日は", false),
                tok("今天是晴天。", true, true),
                tok("明天", false, true),
            ),
        )
        runCurrent()
        assertEquals("今日は晴れ。明日は", session.currentCaptions.japanese)
        assertEquals("今天是晴天。明天", session.currentCaptions.chinese)

        clock.advance(2.0)
        services[0].disconnect(ServiceErrorClassifier.classify(provider = Provider.SONIOX, closeCode = 1006))
        runCurrent()
        assertTrue(session.isReconnecting)
        assertNotNull(sleeper.activeGate)

        assertEquals(Session.Stage.LISTENING, session.stage)
        assertFalse(capture.stopped)
        assertTrue(overlay.active)
        assertEquals("今日は晴れ。", session.currentCaptions.japanese)
        assertEquals("今天是晴天。", session.currentCaptions.chinese)
        assertEquals(OverlayBanner.Tone.WARNING, overlay.lastFrame?.banner?.tone)
        assertTrue(overlay.lastFrame?.banner?.text?.contains("重连") == true)
        assertEquals(OverlayFrame.Style.Bilingual(top = "今日は晴れ。", bottom = "今天是晴天。"), overlay.lastFrame?.style)

        // Switch to Chinese-only during reconnect -> warning banner STILL visible over committed Chinese text
        session.selectDisplayMode(CaptionDisplayMode.CHINESE_ONLY)
        assertEquals(OverlayBanner.Tone.WARNING, overlay.lastFrame?.banner?.tone)
        assertEquals(OverlayFrame.Style.ChineseOnly(text = "今天是晴天。", isPlaceholder = false), overlay.lastFrame?.style)

        // Audio arriving during reconnect is buffered and drained after reconnect succeeds
        capture.emit(AudioChunk(capturedAt = 502.1, data = pcm(7), peak = 200))
        capture.emit(AudioChunk(capturedAt = 502.2, data = pcm(8), peak = 200))
        runCurrent()
        assertEquals(2, session.queuedAudioCount)

        sleeper.holdNext = false
        sleeper.activeGate?.succeed()
        runCurrent()
        assertEquals(2, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        assertByteArrayListEquals(listOf(pcm(7), pcm(8)), services[1].sent)
        assertNull(overlay.lastFrame?.banner)
        assertTrue(overlay.active)

        session.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
    }

    @Test
    fun flappingServerDoesNotResetRetriesOnOpenAndStabilityWindowResets() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(600.0)
        val prefs = InMemoryPreferencesStore()
        val overlay = RecordingOverlaySink()
        val sleeper = FakeSleeper()
        val captures = mutableListOf<FakeCapture>()
        val services = mutableListOf<FakeService>()

        val session = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay,
            makeCapture = { FakeCapture().also { captures.add(it) } },
            makeService = { provider, key -> FakeService(provider, key).also { services.add(it) } },
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { sleeper.sleep(it) },
            reconnectPolicy = ReconnectPolicy(maxAttempts = 2, delays = listOf(0.5, 1.0), stableDuration = 15.0),
            enableTicker = false,
        )

        // Part A: server opens every time, then disconnects after 1s -> MUST stop after 2 retries (3 connections total)
        session.begin(Session.Mode.TRANSLATE)
        captures[0].triggerStart()
        runCurrent()
        assertEquals(1, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        overlay.start()

        clock.advance(1.0)
        services[0].disconnect(CaptionError("drop 1", kind = CaptionError.Kind.TRANSIENT))
        runCurrent()
        assertEquals(2, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)

        clock.advance(1.0)
        services[1].disconnect(CaptionError("drop 2", kind = CaptionError.Kind.TRANSIENT))
        runCurrent()
        assertEquals(3, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)

        clock.advance(1.0)
        services[2].disconnect(CaptionError("drop 3", kind = CaptionError.Kind.TRANSIENT))
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        assertEquals(3, services.size)
        assertEquals(listOf(0.5, 1.0), sleeper.delays)
        assertFalse(overlay.active)
        assertTrue(captures[0].stopped)
        assertTrue(session.status.contains("重连失败"))

        // Part B: restart and stay connected >= 15s -> retry budget resets cleanly
        session.begin(Session.Mode.TRANSLATE)
        captures[1].triggerStart()
        runCurrent()
        assertEquals(4, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)

        clock.advance(1.0)
        services[3].disconnect(CaptionError("blip 1", kind = CaptionError.Kind.TRANSIENT))
        runCurrent()
        assertEquals(5, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)

        clock.advance(20.0) // >= 15s stable
        services[4].disconnect(CaptionError("blip 2", kind = CaptionError.Kind.TRANSIENT))
        runCurrent()
        assertEquals(6, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        assertEquals(0.5, sleeper.delays.last(), 0.0001)

        session.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
    }

    @Test
    fun nonRetryableAuthAndQuotaCloseCodesFailImmediately() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(700.0)
        val prefs = InMemoryPreferencesStore()
        val overlay = RecordingOverlaySink()
        val captures = mutableListOf<FakeCapture>()
        val services = mutableListOf<FakeService>()

        val session = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay,
            makeCapture = { FakeCapture().also { captures.add(it) } },
            makeService = { provider, key -> FakeService(provider, key).also { services.add(it) } },
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { fail("Must not sleep/retry on non-retryable error") },
            enableTicker = false,
        )

        // Gemini WS 1008 policy/permission failure -> 0 retries
        session.select(Provider.GEMINI)
        session.begin(Session.Mode.TRANSLATE)
        captures[0].triggerStart()
        runCurrent()
        assertEquals(1, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        services[0].disconnect(ServiceErrorClassifier.classify(provider = Provider.GEMINI, closeCode = 1008))
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        assertEquals(1, services.size)
        assertTrue(session.status.contains("WS 1008") && session.status.contains("Live Translate"))

        // Soniox quota error (WS 4029) -> 0 retries
        session.select(Provider.SONIOX)
        session.begin(Session.Mode.TRANSLATE)
        captures[1].triggerStart()
        runCurrent()
        assertEquals(2, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        services[1].disconnect(ServiceErrorClassifier.classify(provider = Provider.SONIOX, closeCode = 4029))
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        assertEquals(2, services.size)
        assertTrue(session.status.contains("WS 4029") && session.status.contains("额度"))
    }

    @Test
    fun stopRestartProviderSwitchAndRevokeRaces() = runTest(StandardTestDispatcher()) {
        val clock = FakeClock(800.0)
        val prefs = InMemoryPreferencesStore()
        val overlay = RecordingOverlaySink()
        val sleeper = FakeSleeper()
        val captures = mutableListOf<FakeCapture>()
        val services = mutableListOf<FakeService>()
        var nextStartGate: AsyncGate? = AsyncGate()

        val session = Session(
            scope = this,
            prefs = prefs,
            overlay = overlay,
            makeCapture = { FakeCapture().also { captures.add(it) } },
            makeService = { provider, key ->
                FakeService(provider, key).also {
                    it.startGate = nextStartGate
                    services.add(it)
                }
            },
            readKey = { "key" },
            writeKey = { _, _ -> },
            clock = { clock.now },
            sleep = { sleeper.sleep(it) },
            enableTicker = false,
        )

        // Race 1: Stop during handshake + immediate restart -> old service closed, late callbacks ignored
        val gate1 = nextStartGate!!
        session.begin(Session.Mode.TRANSLATE)
        captures[0].triggerStart()
        runCurrent()
        assertEquals(1, services.size)
        assertTrue(services[0].startCalled)
        session.finish()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        assertTrue(services[0].closed)
        gate1.succeed()
        services[0].deliver(listOf(tok("stale", true)))
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        assertTrue(session.currentCaptions.japanese.isEmpty())

        // Race 2: Provider switch while reconnecting -> cancels old reconnect, clears captions & queue, keeps capture & overlay
        nextStartGate = null
        sleeper.holdNext = true
        session.begin(Session.Mode.TRANSLATE)
        captures[1].triggerStart()
        runCurrent()
        assertEquals(2, services.size)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        overlay.start()
        services[1].deliver(listOf(tok("古い", true), tok("旧译文", true, true)))
        runCurrent()
        services[1].disconnect(CaptionError("transient", kind = CaptionError.Kind.TRANSIENT))
        runCurrent()
        assertTrue(session.isReconnecting)
        assertNotNull(sleeper.activeGate)
        captures[1].emit(AudioChunk(capturedAt = 800.1, data = pcm(9), peak = 100))
        runCurrent()
        assertEquals(1, session.queuedAudioCount)

        clock.advance(0.5)
        session.select(Provider.GEMINI)
        runCurrent()
        assertEquals(3, services.size)
        assertEquals(Provider.GEMINI, services[2].provider)
        assertEquals(Session.ConnectionState.Connected, session.connectionState)
        assertTrue(session.currentCaptions.japanese.isEmpty() && session.currentCaptions.chinese.isEmpty())
        assertTrue(services[2].sent.isEmpty())
        assertTrue(overlay.active && !captures[1].stopped)

        // Late callback from old Soniox service must be ignored
        services[1].deliver(listOf(tok("late", true), tok("延迟", true, true)))
        runCurrent()
        assertTrue(session.currentCaptions.japanese.isEmpty())

        // Race 3: Capture revocation while waiting in reconnect backoff -> stops cleanly, never opens new connection
        val delaysBeforeRevoke = sleeper.delays.count()
        services[2].disconnect(CaptionError("transient", kind = CaptionError.Kind.TRANSIENT))
        runCurrent()
        assertTrue(session.isReconnecting)
        assertEquals(delaysBeforeRevoke + 1, sleeper.delays.size)
        val revokeGate = sleeper.activeGate
        assertNotNull(revokeGate)
        val countBeforeRevoke = services.size
        captures[1].revoke()
        runCurrent()
        assertEquals(Session.Stage.IDLE, session.stage)
        revokeGate?.succeed()
        runCurrent()
        assertEquals(countBeforeRevoke, services.size)
        assertTrue(session.status.contains("系统已结束声音共享"))
        assertFalse(overlay.active)
    }
}
