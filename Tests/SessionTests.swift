import Foundation

@MainActor
final class AsyncGate {
    private var continuation: CheckedContinuation<Void, Error>?
    private var pendingResult: Result<Void, Error>?

    func wait() async throws {
        if let result = pendingResult {
            pendingResult = nil
            try result.get()
            return
        }
        try Task.checkCancellation()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                if Task.isCancelled {
                    cont.resume(throwing: CancellationError())
                } else if let result = pendingResult {
                    pendingResult = nil
                    cont.resume(with: result)
                } else {
                    continuation = cont
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.cancel()
            }
        }
    }

    func succeed() { finish(.success(())) }
    func fail(_ error: Error) { finish(.failure(error)) }
    func cancel() { finish(.failure(CancellationError())) }

    private func finish(_ result: Result<Void, Error>) {
        if let cont = continuation {
            continuation = nil
            cont.resume(with: result)
        } else if pendingResult == nil {
            pendingResult = result
        }
    }
}

@MainActor
final class FakeCapture: AudioCapture {
    let audio: AsyncThrowingStream<AudioChunk, Error>
    private let continuation: AsyncThrowingStream<AudioChunk, Error>.Continuation
    var started: (() -> Void)?
    private(set) var presented = false
    private(set) var stopped = false
    var stopGate: AsyncGate?

    init() {
        let channel = AsyncThrowingStream<AudioChunk, Error>.makeStream()
        audio = channel.stream
        continuation = channel.continuation
    }

    func present() { presented = true }
    func emit(_ chunk: AudioChunk) { continuation.yield(chunk) }
    func triggerStart() { started?() }
    func revoke(_ error: Error = CaptionError("系统已结束声音共享。请回到应用重新开始。")) {
        continuation.finish(throwing: error)
    }

    func stop() async {
        stopped = true
        continuation.finish()
        if let stopGate { try? await stopGate.wait() }
    }
}

@MainActor
final class FakeService: CaptionService {
    let provider: Provider
    let key: String
    var startGate: AsyncGate?
    var sendGateForIndex: [Int: AsyncGate] = [:]
    var startError: Error?
    private(set) var startCalled = false
    private(set) var closed = false
    private(set) var sent: [Data] = []
    private var receiveHandler: (([Token]) -> Void)?
    private var failedHandler: ((Error) -> Void)?

    init(provider: Provider, key: String) {
        self.provider = provider
        self.key = key
    }

    func start(receive: @escaping ([Token]) -> Void, failed: @escaping (Error) -> Void) async throws {
        startCalled = true
        receiveHandler = receive
        failedHandler = failed
        if let startGate { try await startGate.wait() }
        if let startError { throw startError }
    }

    func send(_ data: Data) async throws {
        let idx = sent.count
        if let gate = sendGateForIndex[idx] {
            try await gate.wait()
        }
        if closed {
            throw CaptionError("Socket closed", kind: .transient)
        }
        sent.append(data)
    }

    func deliver(_ tokens: [Token]) { receiveHandler?(tokens) }
    func disconnect(_ error: Error) { failedHandler?(error) }

    func close() {
        closed = true
        startGate?.cancel()
        for gate in sendGateForIndex.values { gate.cancel() }
    }
}

@MainActor
final class FakeClock {
    var now: TimeInterval
    init(_ start: TimeInterval = 100.0) { self.now = start }
    func advance(_ delta: TimeInterval) { now += delta }
}

@MainActor
final class FakeSleeper {
    private(set) var delays: [TimeInterval] = []
    var holdNext = false
    private(set) var activeGate: AsyncGate?

    func sleep(_ seconds: TimeInterval) async throws {
        delays.append(seconds)
        if holdNext {
            let gate = AsyncGate()
            activeGate = gate
            defer {
                if activeGate === gate { activeGate = nil }
            }
            try await gate.wait()
        }
    }
}

@MainActor
func waitUntil(turns: Int = 300, _ condition: () -> Bool) async -> Bool {
    for _ in 0..<turns {
        if condition() { return true }
        await Task.yield()
    }
    return condition()
}

func require(_ condition: Bool, _ message: @autoclosure () -> String = "", file: StaticString = #file, line: UInt = #line) {
    precondition(condition, message(), file: file, line: line)
}

@MainActor
func flush(_ turns: Int = 20) async {
    for _ in 0..<turns { await Task.yield() }
}

func makeDefaults(_ name: String) -> UserDefaults {
    let suite = "CallCaptions.SessionTests.\(name).\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return defaults
}

func pcm(_ id: UInt8, count: Int = 4) -> Data { Data(repeating: id, count: count) }
func tok(_ text: String, _ final: Bool, _ translated: Bool = false) -> Token {
    Token(text: text, is_final: final, translation_status: translated ? "translation" : "original")
}

@MainActor
func testEagerStartPreStartAudioOrderedDrainNoDuplicates() async {
    let clock = FakeClock(100.0)
    let defaults = makeDefaults("eager")
    let overlay = Overlay()
    var captures: [FakeCapture] = []
    var services: [FakeService] = []
    let startGate = AsyncGate()
    let sendGate = AsyncGate()

    let session = Session(
        defaults: defaults,
        overlay: overlay,
        makeCapture: { _ in
            let cap = FakeCapture()
            captures.append(cap)
            return cap
        },
        makeService: { provider, key in
            let svc = FakeService(provider: provider, key: key)
            svc.startGate = startGate
            svc.sendGateForIndex[4] = sendGate
            services.append(svc)
            return svc
        },
        readKey: { _ in "test-key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { _ in },
        enableTicker: false
    )

    session.begin(.translate)
    precondition(session.stage == .picking)
    precondition(services.isEmpty)

    // Pre-start quiet audio raced BEFORE capture.started() fires
    let cap = captures[0]
    cap.emit(AudioChunk(capturedAt: 100.1, data: pcm(1), peak: 0))
    cap.emit(AudioChunk(capturedAt: 100.2, data: pcm(2), peak: 5))
    await flush()
    precondition(services.isEmpty, "Must not open network before capture starts")
    precondition(session.queuedAudioCount == 2)

    // Authorize & start capture -> provider connects immediately even with quiet audio
    cap.triggerStart()
    require(await waitUntil { services.count == 1 && services[0].startCalled })
    precondition(session.stage == .listening)
    precondition(session.connectionState == .connecting)
    precondition(session.status == "正在连接 Soniox")

    // More quiet audio arrives during handshake
    cap.emit(AudioChunk(capturedAt: 100.3, data: pcm(3), peak: 0))
    cap.emit(AudioChunk(capturedAt: 100.4, data: pcm(4), peak: 0))
    await flush()
    precondition(services[0].sent.isEmpty)
    precondition(session.queuedAudioCount == 4)

    // Complete handshake -> drains [1, 2, 3, 4] in exact FIFO order
    startGate.succeed()
    require(await waitUntil { services[0].sent.count == 4 })
    precondition(services[0].sent == [pcm(1), pcm(2), pcm(3), pcm(4)])
    precondition(session.connectionState == .connected)

    // While send #5 is in-flight, #6 arrives -> strictly ordered, zero duplicates
    cap.emit(AudioChunk(capturedAt: 100.5, data: pcm(5), peak: 100))
    cap.emit(AudioChunk(capturedAt: 100.6, data: pcm(6), peak: 100))
    await flush()
    precondition(services[0].sent.count == 4)
    sendGate.succeed()
    require(await waitUntil { services[0].sent.count == 6 })
    precondition(services[0].sent == [pcm(1), pcm(2), pcm(3), pcm(4), pcm(5), pcm(6)])

    session.finish()
    require(await waitUntil { session.stage == .idle })
    precondition(services[0].closed && cap.stopped)
}

@MainActor
func testBoundedQueueDuringHandshake() async {
    let clock = FakeClock(200.0)
    let defaults = makeDefaults("bounded")
    let overlay = Overlay()
    var capture: FakeCapture!
    var service: FakeService!
    let startGate = AsyncGate()

    let session = Session(
        defaults: defaults,
        overlay: overlay,
        makeCapture: { _ in
            capture = FakeCapture()
            return capture
        },
        makeService: { provider, key in
            service = FakeService(provider: provider, key: key)
            service.startGate = startGate
            return service
        },
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { _ in },
        queueMaxBytes: 8,
        queueMaxAge: 1.5,
        enableTicker: false
    )

    session.begin(.translate)
    capture.triggerStart()
    require(await waitUntil { service != nil && service.startCalled })

    capture.emit(AudioChunk(capturedAt: 200.0, data: pcm(1, count: 4), peak: 0))
    await flush()
    clock.advance(0.5)
    capture.emit(AudioChunk(capturedAt: 200.5, data: pcm(2, count: 4), peak: 0))
    await flush()
    clock.advance(0.5)
    // Exceeds 8-byte cap -> evicts chunk 1
    capture.emit(AudioChunk(capturedAt: 201.0, data: pcm(3, count: 4), peak: 0))
    await flush()
    precondition(session.queuedAudioBytes == 8)

    // Advance clock so chunk 2 (200.5) expires (> 1.5s old at 202.2), leaving only chunk 3
    clock.advance(1.2)
    startGate.succeed()
    require(await waitUntil { service.sent.count == 1 })
    await flush()
    precondition(service.sent == [pcm(3, count: 4)])

    session.finish()
    require(await waitUntil { session.stage == .idle })
}

@MainActor
func testLocalProbeNeverOpensNetworkAndRendersProbeCorrectly() async {
    let clock = FakeClock(300.0)
    let defaults = makeDefaults("probe")
    let overlay = Overlay()
    var capture: FakeCapture!
    var sources: [AudioSource] = []
    var serviceCreations = 0

    let session = Session(
        defaults: defaults,
        overlay: overlay,
        makeCapture: { source in
            sources.append(source)
            capture = FakeCapture()
            return capture
        },
        makeService: { provider, key in
            serviceCreations += 1
            return FakeService(provider: provider, key: key)
        },
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { _ in },
        enableTicker: false
    )

    // Even in Chinese-only mode, probe MUST render probe status + meter and never open network
    session.selectDisplayMode(.chineseOnly)
    session.begin(.probe)
    capture.triggerStart()
    await flush()
    capture.emit(AudioChunk(capturedAt: 300.1, data: pcm(1), peak: 16000))
    await flush()
    session.tick(force: true)
    session.select(.gemini)
    await flush()

    precondition(serviceCreations == 0, "Local probe must never open any network connection")
    if case .probe(let probeStatus, let meter) = overlay.lastFrame?.style {
        precondition(probeStatus == "已检测到机内声音")
        precondition(meter.contains("▰") && meter.count == 10)
    } else {
        preconditionFailure("Expected probe frame even when displayMode is chineseOnly")
    }

    session.finish()
    require(await waitUntil { session.stage == .idle })

    session.selectSource(.microphone)
    session.begin(.probe)
    session.selectSource(.system)
    capture.triggerStart()
    session.selectSource(.system)
    capture.emit(AudioChunk(capturedAt: 300.2, data: pcm(1), peak: 12000))
    await flush()
    session.tick(force: true)
    precondition(session.source == .microphone && session.status == "已检测到麦克风声音")
    precondition(sources == [.system, .microphone] && serviceCreations == 0)
    session.finish()
    require(await waitUntil { session.stage == .idle })
    precondition(Session(defaults: defaults, readKey: { _ in "" }).source == .microphone)
    session.selectSource(.system)
    precondition(session.source == .system)
}

@MainActor
func testDisplayModePersistenceAndLiveSwitching() async {
    let clock = FakeClock(400.0)
    let defaults = makeDefaults("displayMode")
    let overlay1 = Overlay()
    var capture: FakeCapture!
    var service: FakeService!

    let session1 = Session(
        defaults: defaults,
        overlay: overlay1,
        makeCapture: { _ in capture = FakeCapture(); return capture },
        makeService: { provider, key in service = FakeService(provider: provider, key: key); return service },
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { _ in },
        enableTicker: false
    )
    precondition(session1.displayMode == .bilingual, "Default must be bilingual")
    precondition(overlay1.lastFrame == CaptionPresentation.idleFrame(mode: .bilingual))

    session1.begin(.translate)
    capture.triggerStart()
    require(await waitUntil { session1.connectionState == .connected })
    overlay1.start()
    precondition(overlay1.active)

    service.deliver([tok("こんにちは", true), tok("你好", true, true)])
    await flush()
    precondition(overlay1.lastFrame?.style == .bilingual(top: "こんにちは", bottom: "你好"))

    // Switch to large Chinese-only WHILE ACTIVE without interrupting capture, service, or PiP
    session1.selectDisplayMode(.chineseOnly)
    precondition(session1.displayMode == .chineseOnly)
    precondition(session1.stage == .listening && !service.closed && overlay1.active)
    precondition(overlay1.lastFrame == OverlayFrame(banner: nil, style: .chineseOnly(text: "你好", isPlaceholder: false)))

    // Switch back to bilingual while active, then persist Chinese-only for restart check
    session1.selectDisplayMode(.bilingual)
    precondition(overlay1.lastFrame?.style == .bilingual(top: "こんにちは", bottom: "你好"))
    session1.selectDisplayMode(.chineseOnly)

    session1.finish()
    require(await waitUntil { session1.stage == .idle })

    // New Session loads persisted Chinese-only mode and draws Chinese-only idle preview
    let overlay2 = Overlay()
    let session2 = Session(
        defaults: defaults,
        overlay: overlay2,
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { _ in },
        enableTicker: false
    )
    precondition(session2.displayMode == .chineseOnly)
    precondition(overlay2.lastFrame == CaptionPresentation.idleFrame(mode: .chineseOnly))
}

@MainActor
func testTransientReconnectPreservesCapturePiPCommittedAndClearsProvisional() async {
    let clock = FakeClock(500.0)
    let defaults = makeDefaults("reconnect")
    let overlay = Overlay()
    let sleeper = FakeSleeper()
    sleeper.holdNext = true
    var capture: FakeCapture!
    var services: [FakeService] = []

    let session = Session(
        defaults: defaults,
        overlay: overlay,
        makeCapture: { _ in capture = FakeCapture(); return capture },
        makeService: { provider, key in
            let svc = FakeService(provider: provider, key: key)
            services.append(svc)
            return svc
        },
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { try await sleeper.sleep($0) },
        reconnectPolicy: ReconnectPolicy(maxAttempts: 3, delays: [0.5, 1.0, 2.0], stableDuration: 15.0),
        enableTicker: false
    )

    session.begin(.translate)
    capture.triggerStart()
    require(await waitUntil { session.connectionState == .connected })
    overlay.start()
    precondition(overlay.active)

    // Deliver committed + provisional text
    services[0].deliver([
        tok("今日は晴れ。", true),
        tok("明日は", false),
        tok("今天是晴天。", true, true),
        tok("明天", false, true)
    ])
    await flush()
    precondition(session.currentCaptions.japanese == "今日は晴れ。明日は")
    precondition(session.currentCaptions.chinese == "今天是晴天。明天")

    // Trigger transient disconnect (WS 1006)
    clock.advance(2.0)
    services[0].disconnect(ServiceErrorClassifier.classify(provider: .soniox, closeCode: 1006))
    require(await waitUntil { session.isReconnecting && sleeper.activeGate != nil })

    // Verify capture & PiP kept, committed text kept, provisional removed, status banner visible over captions
    precondition(session.stage == .listening && !capture.stopped)
    precondition(overlay.active, "PiP must stay active during transient reconnect")
    precondition(session.currentCaptions.japanese == "今日は晴れ。")
    precondition(session.currentCaptions.chinese == "今天是晴天。")
    precondition(overlay.lastFrame?.banner?.tone == .warning)
    precondition(overlay.lastFrame?.banner?.text.contains("重连") == true)
    precondition(overlay.lastFrame?.style == .bilingual(top: "今日は晴れ。", bottom: "今天是晴天。"))

    // Switch to Chinese-only during reconnect -> warning banner STILL visible over committed Chinese text
    session.selectDisplayMode(.chineseOnly)
    precondition(overlay.lastFrame?.banner?.tone == .warning)
    precondition(overlay.lastFrame?.style == .chineseOnly(text: "今天是晴天。", isPlaceholder: false))

    // Audio arriving during reconnect is buffered and drained after reconnect succeeds
    capture.emit(AudioChunk(capturedAt: 502.1, data: pcm(7), peak: 200))
    capture.emit(AudioChunk(capturedAt: 502.2, data: pcm(8), peak: 200))
    await flush()
    precondition(session.queuedAudioCount == 2)

    sleeper.holdNext = false
    sleeper.activeGate?.succeed()
    require(await waitUntil { services.count == 2 && session.connectionState == .connected })
    require(await waitUntil { services[1].sent == [pcm(7), pcm(8)] })
    precondition(overlay.lastFrame?.banner == nil)
    precondition(overlay.active)

    session.finish()
    require(await waitUntil { session.stage == .idle })
}

@MainActor
func testFlappingServerDoesNotResetRetriesOnOpenAndStabilityWindowResets() async {
    let clock = FakeClock(600.0)
    let defaults = makeDefaults("flapping")
    let overlay = Overlay()
    let sleeper = FakeSleeper()
    var captures: [FakeCapture] = []
    var services: [FakeService] = []

    let session = Session(
        defaults: defaults,
        overlay: overlay,
        makeCapture: { _ in let c = FakeCapture(); captures.append(c); return c },
        makeService: { provider, key in
            let s = FakeService(provider: provider, key: key)
            services.append(s)
            return s
        },
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { try await sleeper.sleep($0) },
        reconnectPolicy: ReconnectPolicy(maxAttempts: 2, delays: [0.5, 1.0], stableDuration: 15.0),
        enableTicker: false
    )

    // Part A: server opens every time, then disconnects after 1s -> MUST stop after 2 retries (3 connections total)
    session.begin(.translate)
    captures[0].triggerStart()
    require(await waitUntil { services.count == 1 && session.connectionState == .connected })
    overlay.start()

    clock.advance(1.0)
    services[0].disconnect(CaptionError("drop 1", kind: .transient))
    require(await waitUntil { services.count == 2 && session.connectionState == .connected })

    clock.advance(1.0)
    services[1].disconnect(CaptionError("drop 2", kind: .transient))
    require(await waitUntil { services.count == 3 && session.connectionState == .connected })

    clock.advance(1.0)
    services[2].disconnect(CaptionError("drop 3", kind: .transient))
    require(await waitUntil { session.stage == .idle })
    precondition(services.count == 3, "Must not loop indefinitely when server keeps dropping right after open")
    precondition(sleeper.delays == [0.5, 1.0])
    precondition(!overlay.active && captures[0].stopped)
    precondition(session.status.contains("重连失败"))

    // Part B: restart and stay connected >= 15s -> retry budget resets cleanly
    session.begin(.translate)
    captures[1].triggerStart()
    require(await waitUntil { services.count == 4 && session.connectionState == .connected })
    clock.advance(1.0)
    services[3].disconnect(CaptionError("blip 1", kind: .transient))
    require(await waitUntil { services.count == 5 && session.connectionState == .connected })
    clock.advance(20.0) // >= 15s stable
    services[4].disconnect(CaptionError("blip 2", kind: .transient))
    require(await waitUntil { services.count == 6 && session.connectionState == .connected })
    precondition(sleeper.delays.last == 0.5, "Stable connection must reset backoff to attempt 1")

    session.finish()
    require(await waitUntil { session.stage == .idle })
}

@MainActor
func testNonRetryableAuthAndQuotaCloseCodesFailImmediately() async {
    let clock = FakeClock(700.0)
    let defaults = makeDefaults("nonretry")
    let overlay = Overlay()
    var captures: [FakeCapture] = []
    var services: [FakeService] = []

    let session = Session(
        defaults: defaults,
        overlay: overlay,
        makeCapture: { _ in let c = FakeCapture(); captures.append(c); return c },
        makeService: { provider, key in
            let s = FakeService(provider: provider, key: key)
            services.append(s)
            return s
        },
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { _ in preconditionFailure("Must not sleep/retry on non-retryable error") },
        enableTicker: false
    )

    // Gemini WS 1008 policy/permission failure -> 0 retries
    session.select(.gemini)
    session.begin(.translate)
    captures[0].triggerStart()
    require(await waitUntil { services.count == 1 && session.connectionState == .connected })
    services[0].disconnect(ServiceErrorClassifier.classify(provider: .gemini, closeCode: 1008))
    require(await waitUntil { session.stage == .idle })
    precondition(services.count == 1)
    precondition(session.status.contains("WS 1008") && session.status.contains("Live Translate"))

    // Soniox quota error (WS 4029) during start -> 0 retries
    session.select(.soniox)
    session.begin(.translate)
    captures[1].triggerStart()
    require(await waitUntil { services.count == 2 && session.connectionState == .connected })
    services[1].disconnect(ServiceErrorClassifier.classify(provider: .soniox, closeCode: 4029))
    require(await waitUntil { session.stage == .idle })
    precondition(services.count == 2)
    precondition(session.status.contains("WS 4029") && session.status.contains("额度"))
}

@MainActor
func testStopRestartProviderSwitchAndRevokeRaces() async {
    let clock = FakeClock(800.0)
    let defaults = makeDefaults("races")
    let overlay = Overlay()
    let sleeper = FakeSleeper()
    var captures: [FakeCapture] = []
    var services: [FakeService] = []
    var nextStartGate: AsyncGate? = AsyncGate()

    let session = Session(
        defaults: defaults,
        overlay: overlay,
        makeCapture: { _ in let c = FakeCapture(); captures.append(c); return c },
        makeService: { provider, key in
            let s = FakeService(provider: provider, key: key)
            s.startGate = nextStartGate
            services.append(s)
            return s
        },
        readKey: { _ in "key" },
        writeKey: { _, _ in },
        clock: { clock.now },
        sleep: { try await sleeper.sleep($0) },
        enableTicker: false
    )

    // Race 1: Stop during handshake + immediate restart -> old service closed, late callbacks ignored
    let gate1 = nextStartGate!
    session.begin(.translate)
    captures[0].triggerStart()
    require(await waitUntil { services.count == 1 && services[0].startCalled })
    session.finish()
    require(await waitUntil { session.stage == .idle })
    precondition(services[0].closed)
    gate1.succeed() // late resolution from old handshake
    services[0].deliver([tok("stale", true)])
    await flush()
    precondition(session.stage == .idle && session.currentCaptions.japanese.isEmpty)

    // Race 2: Provider switch while reconnecting -> cancels old reconnect, clears captions & queue, keeps capture & PiP
    nextStartGate = nil
    sleeper.holdNext = true
    session.begin(.translate)
    captures[1].triggerStart()
    require(await waitUntil { services.count == 2 && session.connectionState == .connected })
    overlay.start()
    services[1].deliver([tok("古い", true), tok("旧译文", true, true)])
    await flush()
    services[1].disconnect(CaptionError("transient", kind: .transient))
    require(await waitUntil { session.isReconnecting && sleeper.activeGate != nil })
    captures[1].emit(AudioChunk(capturedAt: 800.1, data: pcm(9), peak: 100))
    await flush()
    precondition(session.queuedAudioCount == 1)

    clock.advance(0.5)
    session.select(.gemini)
    require(await waitUntil { services.count == 3 && services[2].provider == .gemini && session.connectionState == .connected })
    precondition(session.currentCaptions.japanese.isEmpty && session.currentCaptions.chinese.isEmpty)
    precondition(services[2].sent.isEmpty, "Old pre-switch audio must not be sent to new provider")
    precondition(overlay.active && !captures[1].stopped)
    // Late callback from old Soniox service must be ignored
    services[1].deliver([tok("late", true), tok("延迟", true, true)])
    await flush()
    precondition(session.currentCaptions.japanese.isEmpty)

    let longText = String(repeating: "日本語", count: 100)
    services[2].deliver([tok(longText, true), tok("<end>", true), tok("修訂", true, true), tok("草稿", false)])
    let expected = "日语原文\n古い" + longText + "\n\n简体中文译文\n旧译文修订\n"
    precondition(session.transcriptText() == expected)

    // Race 3: Capture revocation while waiting in reconnect backoff -> stops cleanly, never opens new connection
    let delaysBeforeRevoke = sleeper.delays.count
    services[2].disconnect(CaptionError("transient", kind: .transient))
    require(await waitUntil { session.isReconnecting && sleeper.delays.count == delaysBeforeRevoke + 1 && sleeper.activeGate != nil })
    let revokeGate = sleeper.activeGate
    let countBeforeRevoke = services.count
    captures[1].revoke()
    require(await waitUntil { session.stage == .idle })
    revokeGate?.succeed()
    await flush()
    precondition(services.count == countBeforeRevoke, "Capture revocation must cancel reconnect without opening new connections")
    precondition(session.status.contains("系统已结束声音共享"))
    precondition(session.hasTranscript && session.transcriptText() == expected)
    session.begin(.probe)
    session.finish()
    require(await waitUntil { session.stage == .idle })
    precondition(session.transcriptText() == expected)
    session.begin(.translate)
    captures.last!.triggerStart()
    require(await waitUntil { session.connectionState == .connected })
    services.last!.deliver([tok("次", true)])
    precondition(session.transcriptText() == "日语原文\n次\n\n简体中文译文\n\n")
    session.finish()
    require(await waitUntil { session.stage == .idle })
    precondition(!overlay.active)
}

@main
enum SessionTestRunner {
    static func main() async {
        await runAll()
    }

    @MainActor
    private static func runAll() async {
        await testEagerStartPreStartAudioOrderedDrainNoDuplicates()
        await testBoundedQueueDuringHandshake()
        await testLocalProbeNeverOpensNetworkAndRendersProbeCorrectly()
        await testDisplayModePersistenceAndLiveSwitching()
        await testTransientReconnectPreservesCapturePiPCommittedAndClearsProvisional()
        await testFlappingServerDoesNotResetRetriesOnOpenAndStabilityWindowResets()
        await testNonRetryableAuthAndQuotaCloseCodesFailImmediately()
        await testStopRestartProviderSwitchAndRevokeRaces()
        print("Passed: Session eager start, pre-start race, ordered drain, bounded queue, local probe, display mode persistence/live toggle, transient reconnect, stability window, non-retryable close codes, stop/restart/switch/revoke races.")
    }
}
