import Foundation

enum AudioSource: String, CaseIterable {
    case system, microphone

    var title: String { self == .system ? "机内声音" : "麦克风" }

    @MainActor func makeCapture() -> any AudioCapture {
        #if os(iOS)
        if self == .microphone { return MicrophoneCapture() }
        #endif
        return Capture()
    }
}

@MainActor
protocol AudioCapture: AnyObject {
    var audio: AsyncThrowingStream<AudioChunk, Error> { get }
    var started: (() -> Void)? { get set }
    func present()
    func stop() async
}

#if targetEnvironment(simulator) || !os(iOS)
@MainActor
final class Capture: AudioCapture {
    let audio: AsyncThrowingStream<AudioChunk, Error>
    private let output: AsyncThrowingStream<AudioChunk, Error>.Continuation
    var started: (() -> Void)?

    init() {
        let channel = AsyncThrowingStream<AudioChunk, Error>.makeStream()
        audio = channel.stream
        output = channel.continuation
    }

    func present() { output.finish(throwing: CaptionError("声音共享需要在 iPhone 真机上测试。")) }
    func stop() async { output.finish() }
}
#else
import ScreenCaptureKit

private final class AudioOutput: NSObject, SCStreamOutput, SCStreamDelegate {
    private let encoder = PCMEncoder()
    let output: AsyncThrowingStream<AudioChunk, Error>.Continuation

    init(_ output: AsyncThrowingStream<AudioChunk, Error>.Continuation) { self.output = output }

    func stream(_ stream: SCStream, didOutputSampleBuffer sample: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .audio else { return }
        do {
            guard let chunk = try encoder.encode(sample) else { return }
            _ = output.yield(chunk)
        } catch { output.finish(throwing: error) }
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        output.finish(throwing: CaptionError("系统已结束声音共享。请回到应用重新开始。"))
    }
}

@MainActor
final class Capture: NSObject, AudioCapture, SCContentSharingPickerObserver {
    let audio: AsyncThrowingStream<AudioChunk, Error>
    private let sink: AudioOutput
    private let queue = DispatchQueue(label: "CallCaptions.audio", qos: .userInitiated)
    private let picker = SCContentSharingPicker.shared
    private var stream: SCStream?
    private var startup: Task<Void, Never>?
    private var closed = false
    var started: (() -> Void)?

    override init() {
        let channel = AsyncThrowingStream<AudioChunk, Error>.makeStream(bufferingPolicy: .bufferingNewest(600))
        audio = channel.stream
        sink = AudioOutput(channel.continuation)
        super.init()
    }

    func present() {
        var config = SCContentSharingPickerConfiguration()
        #if os(iOS) && !targetEnvironment(macCatalyst)
        config.showsMicrophoneControl = false
        #endif
        picker.defaultConfiguration = config
        picker.add(self)
        picker.isActive = true
        picker.present()
    }

    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didUpdateWith filter: SCContentFilter,
                                          for stream: SCStream?) {
        Task { @MainActor in
            guard !closed else { return }
            if let current = self.stream {
                if current === stream {
                    sink.output.finish(throwing: CaptionError("共享范围已变更，请重新开始。"))
                }
                return
            }
            let config = SCStreamConfiguration()
            config.capturesAudio = true
            config.sampleRate = 16000
            config.channelCount = 1
            config.excludesCurrentProcessAudio = true
            let newStream = SCStream(filter: filter, configuration: config, delegate: sink)
            self.stream = newStream
            startup = Task {
                do {
                    try newStream.addStreamOutput(sink, type: .audio, sampleHandlerQueue: queue)
                    try await newStream.startCapture()
                    if !closed { started?() }
                } catch { sink.output.finish(throwing: error) }
            }
        }
    }

    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {
        Task { @MainActor in
            guard !closed else { return }
            if self.stream == nil { sink.output.finish(throwing: CaptionError("已取消共享。")) }
        }
    }

    nonisolated func contentSharingPickerStartDidFailWithError(_ error: Error) {
        Task { @MainActor in
            guard !closed else { return }
            sink.output.finish(throwing: error)
        }
    }

    func stop() async {
        closed = true
        picker.remove(self)
        picker.isActive = false
        sink.output.finish()
        await startup?.value
        if let stream { try? await stream.stopCapture() }
        stream = nil
    }
}
#endif

#if os(iOS)
import AVFAudio

// The audio tap runs outside the UI actor. Serialize access to its resampler.
private final class MicrophoneOutput: @unchecked Sendable {
    private let encoder = PCMEncoder()
    private let lock = NSLock()
    let continuation: AsyncThrowingStream<AudioChunk, Error>.Continuation

    init(_ continuation: AsyncThrowingStream<AudioChunk, Error>.Continuation) {
        self.continuation = continuation
    }

    func receive(_ buffer: AVReadOnlyAudioPCMBuffer) {
        do {
            if let chunk = try lock.withLock({ try encoder.encode(AVAudioPCMBuffer(copying: buffer)) }) {
                continuation.yield(chunk)
            }
        } catch { continuation.finish(throwing: error) }
    }
}

@MainActor
private final class MicrophoneCapture: AudioCapture {
    let audio: AsyncThrowingStream<AudioChunk, Error>
    private let output: MicrophoneOutput
    private let engine = AVAudioEngine()
    private let session = AVAudioSession.sharedInstance()
    private var observers: [NSObjectProtocol] = []
    private var startup: Task<Void, Never>?
    private var activated = false
    private var tapped = false
    var started: (() -> Void)?

    init() {
        let channel = AsyncThrowingStream<AudioChunk, Error>.makeStream(bufferingPolicy: .bufferingNewest(100))
        audio = channel.stream
        output = MicrophoneOutput(channel.continuation)
    }

    func present() {
        startup = Task { @MainActor in
            let allowed = await AVAudioApplication.requestRecordPermission()
            guard !Task.isCancelled else { return }
            do {
                guard allowed else {
                    throw CaptionError("请在 iPhone「设置」中允许「日语实时字幕」使用麦克风。")
                }
                try session.setCategory(.playAndRecord, mode: .default,
                                        options: [.mixWithOthers, .defaultToSpeaker, .allowBluetoothHFP])
                try session.setActive(true)
                activated = true
                let node = engine.inputNode
                let format = node.outputFormat(forBus: 0)
                guard format.channelCount > 0, format.sampleRate > 0 else {
                    throw CaptionError("没有可用的麦克风，请检查音频输入设备。")
                }
                try node.installAudioTap(onBus: 0, bufferSize: 1024, format: nil) { [output] buffer, _ in
                    output.receive(buffer)
                }
                tapped = true
                try engine.start()
                let center = NotificationCenter.default
                observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification,
                                                    object: session, queue: nil) { [output] note in
                    if note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue {
                        output.continuation.finish(throwing: CaptionError("麦克风收音被打断，请在麦克风可用后重新开始。"))
                    }
                })
                observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange,
                                                    object: engine, queue: nil) { [output] _ in
                    output.continuation.finish(throwing: CaptionError("音频设备已变更，请重新开始麦克风收音。"))
                })
                started?()
            } catch {
                let message = (error as NSError).code == AVAudioSession.ErrorCode.insufficientPriority.rawValue
                    ? "麦克风正被通话占用。可选择机内声音，或在通话结束后重试。"
                    : error.localizedDescription
                output.continuation.finish(throwing: CaptionError(message))
            }
        }
    }

    func stop() async {
        startup?.cancel()
        startup = nil
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
        if tapped {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
            tapped = false
        }
        output.continuation.finish()
        if activated {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            activated = false
        }
    }
}
#endif
