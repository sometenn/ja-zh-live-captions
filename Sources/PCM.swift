import AVFoundation

struct AudioChunk {
    let capturedAt: TimeInterval
    let data: Data
    let peak: Int

    init(capturedAt: TimeInterval = ProcessInfo.processInfo.systemUptime, data: Data, peak: Int) {
        self.capturedAt = capturedAt
        self.data = data
        self.peak = peak
    }
}

// Bounded FIFO PCM buffer enforcing both maximum age and maximum byte capacity.
struct AudioBufferQueue {
    static let defaultMaxBytes = 160_000 // 5.0 seconds of 16 kHz, 16-bit mono PCM
    static let defaultMaxAge: TimeInterval = 5.0

    let maxBytes: Int
    let maxAge: TimeInterval

    private var storage: [AudioChunk] = []
    private var head = 0
    private(set) var totalBytes = 0
    private(set) var droppedCount = 0
    private(set) var droppedBytes = 0

    var count: Int { storage.count - head }
    var isEmpty: Bool { count == 0 }

    init(maxBytes: Int = defaultMaxBytes, maxAge: TimeInterval = defaultMaxAge) {
        self.maxBytes = max(0, maxBytes)
        self.maxAge = max(0, maxAge)
    }

    mutating func pruneExpired(now: TimeInterval) {
        while head < storage.count && now - storage[head].capturedAt > maxAge {
            let evicted = storage[head]
            head += 1
            totalBytes -= evicted.data.count
            droppedCount += 1
            droppedBytes += evicted.data.count
        }
        compactIfNeeded()
    }

    mutating func enqueue(_ chunk: AudioChunk, now: TimeInterval) {
        guard !chunk.data.isEmpty else { return }
        pruneExpired(now: now)
        if now - chunk.capturedAt > maxAge || chunk.data.count > maxBytes {
            droppedCount += 1
            droppedBytes += chunk.data.count
            return
        }
        while head < storage.count && totalBytes + chunk.data.count > maxBytes {
            let evicted = storage[head]
            head += 1
            totalBytes -= evicted.data.count
            droppedCount += 1
            droppedBytes += evicted.data.count
        }
        compactIfNeeded()
        storage.append(chunk)
        totalBytes += chunk.data.count
    }

    mutating func dequeue(now: TimeInterval) -> AudioChunk? {
        pruneExpired(now: now)
        guard head < storage.count else { return nil }
        let next = storage[head]
        head += 1
        totalBytes -= next.data.count
        compactIfNeeded()
        return next
    }

    mutating func clear() {
        storage.removeAll(keepingCapacity: false)
        head = 0
        totalBytes = 0
    }

    private mutating func compactIfNeeded() {
        if head == storage.count {
            storage.removeAll(keepingCapacity: true)
            head = 0
        } else if head >= 64 && head * 2 >= storage.count {
            storage.removeFirst(head)
            head = 0
        }
    }
}

// Used on one serial capture queue. Keep the resampler across buffers.
final class PCMEncoder {
    private let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000,
                                       channels: 1, interleaved: true)!
    private var converter: AVAudioConverter?

    func encode(_ sample: CMSampleBuffer) throws -> AudioChunk? {
        let count = CMSampleBufferGetNumSamples(sample)
        guard count > 0, CMSampleBufferDataIsReady(sample) else { return nil }
        guard let description = CMSampleBufferGetFormatDescription(sample),
              let stream = CMAudioFormatDescriptionGetStreamBasicDescription(description),
              let format = AVAudioFormat(streamDescription: stream),
              let source = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count)) else {
            throw CaptionError("无法读取系统音频格式。")
        }
        source.frameLength = AVAudioFrameCount(count)
        guard CMSampleBufferCopyPCMDataIntoAudioBufferList(sample, at: 0, frameCount: Int32(count),
                                                          into: source.mutableAudioBufferList) == noErr else {
            throw CaptionError("无法读取系统音频采样。")
        }
        return try encode(source)
    }

    func encode(_ source: AVAudioPCMBuffer) throws -> AudioChunk? {
        let format = source.format
        let count = source.frameLength
        guard count > 0, format.sampleRate > 0 else { return nil }
        if converter?.inputFormat != format {
            converter = AVAudioConverter(from: format, to: target)
            converter?.primeMethod = .none
        }
        let capacity = AVAudioFrameCount(ceil(Double(count) * target.sampleRate / format.sampleRate)) + 32
        guard let converter, let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else {
            throw CaptionError("无法转换系统音频格式。")
        }
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, state in
            if supplied { state.pointee = .noDataNow; return nil }
            supplied = true
            state.pointee = .haveData
            return source
        }
        if status == .error { throw error ?? CaptionError("系统音频转换失败。") as NSError }
        guard output.frameLength > 0, let pointer = output.int16ChannelData?[0] else { return nil }
        let samples = UnsafeBufferPointer(start: pointer, count: Int(output.frameLength))
        let peak = samples.reduce(0) { max($0, abs(Int($1))) }
        return AudioChunk(data: Data(buffer: samples), peak: peak)
    }
}
