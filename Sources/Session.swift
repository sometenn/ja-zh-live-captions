import Combine
import Foundation

@MainActor
final class Session: ObservableObject {
    enum Mode { case probe, translate }
    enum Stage { case idle, picking, listening, stopping }
    enum ConnectionState: Equatable {
        case disconnected
        case connecting
        case connected
        case reconnecting(attempt: Int, maxAttempts: Int)
    }

    typealias CaptureFactory = @MainActor (AudioSource) -> any AudioCapture
    typealias ServiceFactory = @MainActor (Provider, String) -> any CaptionService
    typealias KeyReader = (Provider) throws -> String
    typealias KeyWriter = (String, Provider) throws -> Void
    typealias Clock = () -> TimeInterval
    typealias Sleeper = (TimeInterval) async throws -> Void

    private static let sourceKey = "audioInputSource"
    private static let providerKey = "translationProvider"
    private static let displayModeKey = "captionDisplayMode"

    let overlay: Overlay
    @Published private(set) var stage = Stage.idle
    @Published private(set) var mode = Mode.probe
    @Published private(set) var status = "准备就绪"
    @Published private(set) var level = 0
    @Published private(set) var hasKey = false
    @Published private(set) var source: AudioSource
    @Published private(set) var provider: Provider
    @Published private(set) var displayMode: CaptionDisplayMode
    @Published private(set) var switchNotice = ""
    @Published private(set) var connectionState = ConnectionState.disconnected
    @Published private(set) var frame: OverlayFrame

    var isReconnecting: Bool {
        if case .reconnecting = connectionState { return true }
        return false
    }

    var queuedAudioCount: Int { pcmQueue.count }
    var queuedAudioBytes: Int { pcmQueue.totalBytes }
    var currentCaptions: Captions { captions }

    private let defaults: UserDefaults
    private let makeCapture: CaptureFactory
    private let makeService: ServiceFactory
    private let readKey: KeyReader
    private let writeKey: KeyWriter
    private let clock: Clock
    private let sleep: Sleeper
    private let enableTicker: Bool

    private var capture: (any AudioCapture)?
    private var client: (any CaptionService)?
    private var activeKey = ""
    private var acceptAudioSince = 0.0
    private var sessionID: UInt64 = 0
    private var connectionID: UInt64 = 0
    private var worker: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var connectTask: Task<Void, Never>?
    private var drainTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var pcmQueue: AudioBufferQueue
    private var reconnectPolicy: ReconnectPolicy
    @Published private(set) var hasTranscript = false
    private var transcriptSessionID: UInt64?
    private var originalRecord = ""
    private var translationRecord = ""

    func transcriptText() -> String {
        let chinese = Captions.simplify(translationRecord)
        return "日语原文\n" + originalRecord + "\n\n简体中文译文\n" + chinese + "\n"
    }

    private func record(_ tokens: [Token], sessionID: UInt64) {
        for token in tokens where token.is_final && !token.text.isEmpty && token.text != "<end>" && token.text != "<fin>" {
            if transcriptSessionID != sessionID {
                originalRecord = ""
                translationRecord = ""
                transcriptSessionID = sessionID
            }
            if token.translation_status == "translation" { translationRecord += token.text }
            else { originalRecord += token.text }
            if !hasTranscript { hasTranscript = true }
        }
    }

    private var captions = Captions()
    private var latestPeak = 0
    private var lastSignal: TimeInterval?
    private var lastBuffer: TimeInterval?
    private var listeningSince = 0.0
    private var lastDraw = 0.0
    private var now: TimeInterval { clock() }

    init(defaults: UserDefaults = .standard,
         overlay: Overlay? = nil,
         makeCapture: CaptureFactory? = nil,
         makeService: ServiceFactory? = nil,
         readKey: KeyReader? = nil,
         writeKey: KeyWriter? = nil,
         clock: Clock? = nil,
         sleep: Sleeper? = nil,
         queueMaxBytes: Int = AudioBufferQueue.defaultMaxBytes,
         queueMaxAge: TimeInterval = AudioBufferQueue.defaultMaxAge,
         reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
         enableTicker: Bool = true) {
        self.defaults = defaults
        self.overlay = overlay ?? Overlay()
        self.makeCapture = makeCapture ?? { $0.makeCapture() }
        self.makeService = makeService ?? { provider, key in provider.connect(key: key) }
        self.readKey = readKey ?? { try Vault.read($0) }
        self.writeKey = writeKey ?? { try Vault.save($0, for: $1) }
        self.clock = clock ?? { ProcessInfo.processInfo.systemUptime }
        self.sleep = sleep ?? { seconds in
            let ms = max(0, Int((seconds * 1000).rounded()))
            try await Task.sleep(for: .milliseconds(ms))
        }
        self.pcmQueue = AudioBufferQueue(maxBytes: queueMaxBytes, maxAge: queueMaxAge)
        self.reconnectPolicy = reconnectPolicy
        self.enableTicker = enableTicker

        let savedProvider = Provider(rawValue: defaults.string(forKey: Self.providerKey) ?? "") ?? .soniox
        let savedDisplayMode = CaptionDisplayMode(rawValue: defaults.string(forKey: Self.displayModeKey) ?? "") ?? .bilingual
        self.source = AudioSource(rawValue: defaults.string(forKey: Self.sourceKey) ?? "") ?? .system
        self.provider = savedProvider
        self.displayMode = savedDisplayMode
        let initialFrame = CaptionPresentation.idleFrame(mode: savedDisplayMode)
        self.frame = initialFrame

        do { hasKey = try !self.readKey(savedProvider).isEmpty }
        catch { status = error.localizedDescription }
        self.overlay.draw(initialFrame)
    }

    func selectSource(_ next: AudioSource) {
        guard stage == .idle else { return }
        source = next
        defaults.set(next.rawValue, forKey: Self.sourceKey)
    }

    func selectDisplayMode(_ next: CaptionDisplayMode) {
        guard next != displayMode, stage != .stopping else { return }
        displayMode = next
        defaults.set(next.rawValue, forKey: Self.displayModeKey)
        if stage == .listening {
            refresh(force: true)
        } else {
            drawIdlePreview()
        }
    }

    func refreshPreviewIfIdle() {
        guard stage == .idle else { return }
        drawIdlePreview()
    }

    func select(_ next: Provider) {
        guard next != provider, stage == .idle || stage == .listening else { return }
        do {
            let key = try readKey(next)
            if stage == .listening && mode == .translate {
                guard !key.isEmpty else {
                    switchNotice = "请先结束收音，为 " + next.rawValue + " 设置密钥。当前服务仍为 " + provider.rawValue + "。"
                    return
                }
                provider = next
                hasKey = true
                switchNotice = ""
                defaults.set(next.rawValue, forKey: Self.providerKey)
                activeKey = key
                acceptAudioSince = now
                resetProviderConnection()
                pcmQueue.clear()
                captions = Captions()
                reconnectPolicy.reset()
                lastSignal = nil
                connectionID += 1
                let nextCID = connectionID
                connectionState = .connecting
                refresh(force: true)
                startConnection(sessionID: sessionID, connectionID: nextCID, isReconnect: false)
                return
            }
            provider = next
            hasKey = !key.isEmpty
            switchNotice = ""
            defaults.set(next.rawValue, forKey: Self.providerKey)
            if stage == .listening {
                refresh(force: true)
            }
        } catch { switchNotice = error.localizedDescription }
    }

    func saveKey(_ value: String) throws {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        try writeKey(trimmed, provider)
        hasKey = !trimmed.isEmpty
    }

    func begin(_ requested: Mode) {
        guard stage == .idle else { return }
        var key = ""
        if requested == .translate {
            do { key = try readKey(provider) } catch { status = error.localizedDescription; return }
            guard !key.isEmpty else { status = "请先填写 " + provider.rawValue + " API 密钥。"; return }
        }
        sessionID += 1
        let sid = sessionID
        connectionID += 1
        activeKey = key
        acceptAudioSince = now
        switchNotice = ""
        mode = requested
        captions = Captions()
        pcmQueue.clear()
        reconnectPolicy.reset()
        connectionState = .disconnected
        latestPeak = 0
        level = 0
        lastSignal = nil
        lastBuffer = nil
        stage = .picking
        #if MAC_CAPTIONS
        status = source == .system ? "请选择要收音的应用或屏幕" : "正在开启麦克风"
        #else
        status = source == .system ? "请在系统窗口中选择共享整个屏幕" : "正在开启麦克风"

        #endif
        let input = makeCapture(source)
        capture = input
        input.started = { [weak self, weak input] in
            guard let self, let input, self.sessionID == sid, self.capture === input, self.stage == .picking else { return }
            self.stage = .listening
            self.listeningSince = self.now
            if self.source == .system { self.overlay.prepare() }
            if requested == .translate {
                self.connectionID += 1
                let cid = self.connectionID
                self.connectionState = .connecting
                self.startConnection(sessionID: sid, connectionID: cid, isReconnect: false)
            }
            self.refresh(force: true)
            if self.enableTicker {
                self.ticker = Task { @MainActor [weak self] in
                    while !Task.isCancelled {
                        try? await Task.sleep(for: .milliseconds(160))
                        if !Task.isCancelled { self?.refresh() }
                    }
                }
            }
        }
        worker = Task { @MainActor [weak self] in
            guard self != nil else { return }
            do {
                for try await chunk in input.audio {
                    try Task.checkCancellation()
                    guard let self, self.sessionID == sid, self.capture === input, self.stage != .stopping else { return }
                    guard chunk.capturedAt >= self.acceptAudioSince else { continue }
                    self.lastBuffer = self.now
                    self.latestPeak = max(self.latestPeak, chunk.peak)
                    if chunk.peak >= 32 { self.lastSignal = self.now }
                    if requested == .translate {
                        self.pcmQueue.enqueue(chunk, now: self.now)
                        if self.stage == .listening && self.connectionState == .connected {
                            self.scheduleDrain()
                        }
                    }
                }
                if !Task.isCancelled, let self, self.sessionID == sid, self.capture === input, self.stage != .stopping, self.stage != .idle {
                    self.finish(self.source == .system ? "声音共享已结束。" : "麦克风收音已结束。")
                }
            } catch {
                if !Task.isCancelled, let self, self.sessionID == sid, self.capture === input {
                    self.finish(error.localizedDescription)
                }
            }
        }
        input.present()
    }

    func tick(force: Bool = false) {
        refresh(force: force)
    }

    private func startConnection(sessionID sid: UInt64, connectionID cid: UInt64, isReconnect: Bool) {
        guard self.sessionID == sid,
              self.connectionID == cid,
              stage == .listening,
              mode == .translate else { return }
        let service = makeService(provider, activeKey)
        client = service
        if !isReconnect {
            connectionState = .connecting
        }
        connectTask = Task { @MainActor [weak self] in
            do {
                try await service.start(receive: { [weak self, weak service] tokens in
                    guard let self,
                          let service,
                          self.sessionID == sid,
                          self.connectionID == cid,
                          self.client === service,
                          self.stage == .listening else { return }
                    self.record(tokens, sessionID: sid)
                    self.captions.apply(tokens)
                    self.refresh()
                }, failed: { [weak self, weak service] error in
                    guard let self,
                          let service,
                          self.sessionID == sid,
                          self.connectionID == cid,
                          self.client === service else { return }
                    self.handleConnectionFailure(error, connectionID: cid, sessionID: sid)
                })
                guard let self,
                      !Task.isCancelled,
                      self.sessionID == sid,
                      self.connectionID == cid,
                      self.client === service,
                      self.stage == .listening else {
                    service.close()
                    return
                }
                self.connectTask = nil
                self.connectionState = .connected
                self.reconnectPolicy.markConnected(at: self.now)
                self.refresh(force: true)
                self.scheduleDrain()
            } catch {
                guard let self else { service.close(); return }
                if error is CancellationError || Task.isCancelled { return }
                guard self.sessionID == sid, self.connectionID == cid, self.client === service else { return }
                self.connectTask = nil
                self.handleConnectionFailure(error, connectionID: cid, sessionID: sid)
            }
        }
    }

    private func scheduleDrain() {
        guard stage == .listening,
              mode == .translate,
              connectionState == .connected,
              drainTask == nil,
              !pcmQueue.isEmpty,
              let service = client else { return }
        let sid = sessionID
        let cid = connectionID
        drainTask = Task { @MainActor [weak self] in
            defer {
                if let self, self.sessionID == sid, self.connectionID == cid {
                    self.drainTask = nil
                }
            }
            while !Task.isCancelled {
                let chunk: AudioChunk
                if let self,
                   self.sessionID == sid,
                   self.connectionID == cid,
                   self.stage == .listening,
                   self.mode == .translate,
                   self.connectionState == .connected,
                   self.client === service {
                    guard let next = self.pcmQueue.dequeue(now: self.now) else { return }
                    chunk = next
                } else {
                    return
                }
                do {
                    try await service.send(chunk.data)
                } catch {
                    if !Task.isCancelled {
                        self?.handleConnectionFailure(error, connectionID: cid, sessionID: sid)
                    }
                    return
                }
            }
        }
    }

    private func handleConnectionFailure(_ error: Error, connectionID cid: UInt64, sessionID sid: UInt64) {
        guard self.sessionID == sid,
              self.connectionID == cid,
              stage == .listening,
              mode == .translate else { return }
        connectionID += 1
        let nextCID = connectionID
        resetProviderConnection()

        let retryable: Bool
        if let captionError = error as? CaptionError {
            retryable = captionError.isRetryable
        } else if error is URLError {
            retryable = true
        } else {
            retryable = false
        }

        guard retryable else {
            finish(error.localizedDescription)
            return
        }

        captions.clearProvisional()

        guard let decision = reconnectPolicy.recordTransientFailure(at: now) else {
            finish("重连失败（已达上限）：\(error.localizedDescription)")
            return
        }

        connectionState = .reconnecting(attempt: decision.attempt, maxAttempts: decision.maxAttempts)
        refresh(force: true)

        reconnectTask = Task { @MainActor [weak self] in
            do {
                if decision.delay > 0 {
                    guard let sleep = self?.sleep else { return }
                    try await sleep(decision.delay)
                }
                try Task.checkCancellation()
            } catch {
                return
            }
            guard let self,
                  self.sessionID == sid,
                  self.connectionID == nextCID,
                  self.stage == .listening,
                  self.mode == .translate else { return }
            self.reconnectTask = nil
            self.startConnection(sessionID: sid, connectionID: nextCID, isReconnect: true)
        }
    }

    private func resetProviderConnection() {
        connectTask?.cancel()
        connectTask = nil
        drainTask?.cancel()
        drainTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        client?.close()
        client = nil
    }

    private func refresh(force: Bool = false) {
        guard stage == .listening else { return }
        let timestamp = now
        let sampleAge = timestamp - (lastBuffer ?? listeningSince)
        let audioStalled = sampleAge > 4
        let quiet = lastSignal.map { timestamp - $0 > 3 } ?? true

        switch mode {
        case .probe:
            if audioStalled { status = source == .system ? "尚未收到系统音频帧" : "尚未收到麦克风声音" }
            else if quiet { status = source == .system ? "等待对方声音" : "等待麦克风声音" }
            else { status = source == .system ? "已检测到机内声音" : "已检测到麦克风声音" }
        case .translate:
            switch connectionState {
            case .reconnecting(let attempt, let maxAttempts):
                status = "连接中断，正在重连 \(provider.rawValue)（\(attempt)/\(maxAttempts)）"
            case .connecting:
                status = "正在连接 \(provider.rawValue)"
            case .connected:
                reconnectPolicy.noteHealthy(at: timestamp)
                if audioStalled { status = source == .system ? "尚未收到系统音频帧" : "尚未收到麦克风声音" }
                else if quiet { status = source == .system ? "等待对方声音" : "等待麦克风声音" }
                else { status = "正在翻译" }
            case .disconnected:
                status = "准备翻译"
            }
        }

        let measured = latestPeak > 0 ? max(0, min(10, Int((20 * log10(Double(latestPeak) / 32768) + 60) / 6) + 1)) : 0
        level = max(measured, level - 1)
        latestPeak = 0

        let nextFrame: OverlayFrame
        if mode == .probe {
            nextFrame = CaptionPresentation.probeFrame(status: status, level: level)
        } else {
            nextFrame = CaptionPresentation.translateFrame(
                displayMode: displayMode,
                captions: captions,
                status: status,
                isReconnecting: isReconnecting,
                isConnecting: connectionState == .connecting,
                isAudioStalled: audioStalled
            )
        }
        let changed = nextFrame != frame
        if changed { frame = nextFrame }
        if changed || force || timestamp - lastDraw >= 1 {
            overlay.draw(nextFrame)
            lastDraw = timestamp
        }
    }

    private func drawIdlePreview() {
        let idle = CaptionPresentation.idleFrame(mode: displayMode)
        frame = idle
        overlay.draw(idle)
    }

    func finish(_ message: String = "已结束") {
        guard stage != .idle, stage != .stopping else { return }
        stage = .stopping
        status = source == .system ? "正在结束共享" : "正在结束收音"
        sessionID += 1
        let finishSessionID = sessionID
        connectionID += 1
        ticker?.cancel()
        ticker = nil
        worker?.cancel()
        worker = nil
        resetProviderConnection()
        pcmQueue.clear()
        reconnectPolicy.reset()
        connectionState = .disconnected
        activeKey = ""
        switchNotice = ""
        overlay.stop()
        captions = Captions()
        latestPeak = 0
        level = 0
        lastSignal = nil
        lastBuffer = nil
        drawIdlePreview()
        let input = capture
        Task { @MainActor [weak self] in
            await input?.stop()
            guard let self, self.sessionID == finishSessionID else { return }
            self.capture = nil
            self.status = message
            self.stage = .idle
            self.drawIdlePreview()
        }
    }
}
