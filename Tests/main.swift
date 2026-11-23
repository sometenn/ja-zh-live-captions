import AVFoundation

// Offline deterministic checks for audio encoding, bounded PCM buffering, incremental captions,
// phrase wrapping, presentation states, reconnect policy, and provider error classification.
func token(_ text: String, _ final: Bool, _ translated: Bool = false) -> Token {
    Token(text: text, is_final: final, translation_status: translated ? "translation" : "original")
}

// 1. Captions: partial revision, independent translation, endpoint markers, Simplified Chinese, clearProvisional
var captions = Captions()
captions.apply([token("こん", false)])
precondition(captions.japanese == "こん")
captions.apply([token("今日", true), token("は雨", false), token("今", false, true)])
precondition(captions.japanese == "今日は雨" && captions.chinese == "今")
captions.apply([token("は晴れ", true), token("今天是晴天", true, true)])
precondition(captions.japanese == "今日は晴れ" && captions.chinese == "今天是晴天")
captions.apply([token("<end>", true), token("。", true)])
captions.apply([token("。學習", true, true)])
precondition(captions.chinese == "今天是晴天。学习" && captions.japanese == "今日は晴れ。")
captions.apply([token("取消", false), token("修改", false, true)])
precondition(captions.japanese == "今日は晴れ。取消" && captions.chinese == "今天是晴天。学习修改")
captions.clearProvisional()
precondition(captions.japanese == "今日は晴れ。" && captions.chinese == "今天是晴天。学习")
precondition(captions.hasCommitted)
captions.apply([token("。", false)])
captions.apply([])
precondition(captions.chinese == "今天是晴天。学习" && captions.japanese == "今日は晴れ。")

var onlyProvisional = Captions()
onlyProvisional.apply([token("もしもし", false), token("喂", false, true)])
precondition(!onlyProvisional.hasCommitted)
onlyProvisional.clearProvisional()
precondition(onlyProvisional.japanese.isEmpty && onlyProvisional.chinese.isEmpty)

// 2. Bounded PCM Queue: FIFO order, byte cap, age eviction, oversized & empty chunks, clear
var queue = AudioBufferQueue(maxBytes: 12, maxAge: 2.0)
queue.enqueue(AudioChunk(capturedAt: 10.0, data: Data(), peak: 0), now: 10.0)
precondition(queue.isEmpty && queue.totalBytes == 0)
queue.enqueue(AudioChunk(capturedAt: 10.0, data: Data(repeating: 1, count: 4), peak: 0), now: 10.0)
queue.enqueue(AudioChunk(capturedAt: 10.5, data: Data(repeating: 2, count: 4), peak: 0), now: 10.5)
queue.enqueue(AudioChunk(capturedAt: 11.0, data: Data(repeating: 3, count: 4), peak: 0), now: 11.0)
precondition(queue.count == 3 && queue.totalBytes == 12)
// Exceeds maxBytes -> evicts oldest (1)
queue.enqueue(AudioChunk(capturedAt: 11.2, data: Data(repeating: 4, count: 4), peak: 0), now: 11.2)
precondition(queue.count == 3 && queue.totalBytes == 12 && queue.droppedCount == 1 && queue.droppedBytes == 4)
// Oversized single chunk -> dropped without exceeding maxBytes
queue.enqueue(AudioChunk(capturedAt: 11.3, data: Data(repeating: 9, count: 20), peak: 0), now: 11.3)
precondition(queue.count == 3 && queue.totalBytes == 12)
// Age eviction on dequeue: at t = 12.6, chunk at 10.5 is 2.1s old (> 2.0s) and pruned; 11.0 and 11.2 remain in order
let d1 = queue.dequeue(now: 12.6)
let d2 = queue.dequeue(now: 12.6)
let d3 = queue.dequeue(now: 12.6)
precondition(d1?.data == Data(repeating: 3, count: 4))
precondition(d2?.data == Data(repeating: 4, count: 4))
precondition(d3 == nil && queue.isEmpty && queue.totalBytes == 0)
queue.enqueue(AudioChunk(capturedAt: 20.0, data: Data(repeating: 5, count: 6), peak: 10), now: 20.0)
queue.clear()
precondition(queue.isEmpty && queue.totalBytes == 0)

// 3. Readable recent phrase wrapping
func charLimit(_ maxChars: Int) -> (String) -> Bool {
    { $0.count <= maxChars }
}
precondition(CaptionTextLayout.fitRecentPhrase("  こんにちは\n  世界  ", fits: charLimit(20)) == "こんにちは 世界")
// Sentence boundary preferred when remaining sentence is substantial
let twoSentences = "いらっしゃいませ。本日のご予約内容を確認いたします。"
precondition(CaptionTextLayout.fitRecentPhrase(twoSentences, fits: charLimit(18)) == "本日のご予約内容を確認いたします。")
// Clause boundary used when new sentence just started with 2 chars
let clauseFallback = "非常感谢您的耐心等待，我们现在帮您确认库存。好的"
precondition(CaptionTextLayout.fitRecentPhrase(clauseFallback, fits: charLimit(16)) == "我们现在帮您确认库存。好的")
// Unbroken text without punctuation adds ellipsis and fits cleanly
let unbroken = "这是一段没有任何标点符号的连续中文语音识别输出内容测试"
let fittedUnbroken = CaptionTextLayout.fitRecentPhrase(unbroken, fits: charLimit(12))
precondition(fittedUnbroken.hasPrefix("…") && fittedUnbroken.count <= 12 && fittedUnbroken.hasSuffix("输出内容测试"))
// Does not split an embedded ASCII word mid-token when cutting
let asciiMixed = "请打开Safari浏览器确认VirtualStore预约状态确认"
let fittedAscii = CaptionTextLayout.fitRecentPhrase(asciiMixed, fits: charLimit(16))
precondition(fittedAscii.count <= 16 && !fittedAscii.contains("ualStore"))
// Never starts with dangling punctuation
let dangling = "前文内容很长很长，，，。最终译文在这里"
let fittedDangling = CaptionTextLayout.fitRecentPhrase(dangling, fits: charLimit(10))
precondition(fittedDangling == "最终译文在这里")

// 4. Presentation: bilingual default, Chinese-only, reconnecting status over captions, probe mode
precondition(CaptionPresentation.idleFrame(mode: .bilingual) == OverlayFrame(banner: nil, style: .bilingual(top: "日语原文", bottom: "简体中文译文")))
precondition(CaptionPresentation.idleFrame(mode: .chineseOnly) == OverlayFrame(banner: nil, style: .chineseOnly(text: "简体中文译文", isPlaceholder: true)))
let probe = CaptionPresentation.probeFrame(status: "已检测到机内声音", level: 4)
precondition(probe == OverlayFrame(banner: nil, style: .probe(status: "已检测到机内声音", meter: "▰▰▰▰▱▱▱▱▱▱")))

let reconBilingual = CaptionPresentation.translateFrame(
    displayMode: .bilingual,
    captions: captions,
    status: "连接中断，正在重连 Soniox（1/4）",
    isReconnecting: true,
    isConnecting: false,
    isAudioStalled: false
)
precondition(reconBilingual.banner == OverlayBanner(text: "连接中断，正在重连 Soniox（1/4）", tone: .warning))
precondition(reconBilingual.style == .bilingual(top: "今日は晴れ。", bottom: "今天是晴天。学习"))

let reconChinese = CaptionPresentation.translateFrame(
    displayMode: .chineseOnly,
    captions: captions,
    status: "连接中断，正在重连 Gemini（2/4）",
    isReconnecting: true,
    isConnecting: false,
    isAudioStalled: false
)
precondition(reconChinese.banner == OverlayBanner(text: "连接中断，正在重连 Gemini（2/4）", tone: .warning))
precondition(reconChinese.style == .chineseOnly(text: "今天是晴天。学习", isPlaceholder: false))

let healthyChinese = CaptionPresentation.translateFrame(
    displayMode: .chineseOnly,
    captions: captions,
    status: "正在翻译",
    isReconnecting: false,
    isConnecting: false,
    isAudioStalled: false
)
precondition(healthyChinese.banner == nil && healthyChinese.style == .chineseOnly(text: "今天是晴天。学习", isPlaceholder: false))

let connectingChinese = CaptionPresentation.translateFrame(
    displayMode: .chineseOnly,
    captions: Captions(),
    status: "正在连接 Gemini",
    isReconnecting: false,
    isConnecting: true,
    isAudioStalled: false
)
precondition(connectingChinese.banner == OverlayBanner(text: "正在连接 Gemini", tone: .info))
precondition(connectingChinese.style == .chineseOnly(text: "等待中文译文…", isPlaceholder: true))

// 5. ReconnectPolicy: bounded retries + no immediate reset on open for flapping server
var policy = ReconnectPolicy(maxAttempts: 3, delays: [0.5, 1.0, 2.0], stableDuration: 15.0)
policy.markConnected(at: 10.0)
let r1 = policy.recordTransientFailure(at: 11.0)
precondition(r1 == ReconnectPolicy.Decision(attempt: 1, maxAttempts: 3, delay: 0.5))
// Server opens again, then drops after 1s -> MUST NOT reset retry count
policy.markConnected(at: 12.0)
let r2 = policy.recordTransientFailure(at: 13.0)
precondition(r2 == ReconnectPolicy.Decision(attempt: 2, maxAttempts: 3, delay: 1.0))
policy.markConnected(at: 14.5)
let r3 = policy.recordTransientFailure(at: 15.5)
precondition(r3 == ReconnectPolicy.Decision(attempt: 3, maxAttempts: 3, delay: 2.0))
policy.markConnected(at: 18.0)
precondition(policy.recordTransientFailure(at: 19.0) == nil)
// Stability window DOES reset retry budget after staying open >= stableDuration
policy.reset()
policy.markConnected(at: 100.0)
_ = policy.recordTransientFailure(at: 101.0)
policy.markConnected(at: 102.0)
let afterStable = policy.recordTransientFailure(at: 120.0) // 18s >= 15s
precondition(afterStable == ReconnectPolicy.Decision(attempt: 1, maxAttempts: 3, delay: 0.5))

// 6. ServiceErrorClassifier: actionable auth/permission/quota/close-code classification
let sonioxAuth = ServiceErrorClassifier.classify(provider: .soniox, httpStatus: 401)
precondition(sonioxAuth.kind == .authentication && !sonioxAuth.isRetryable && sonioxAuth.message.contains("HTTP 401"))
let sonioxQuota = ServiceErrorClassifier.classify(provider: .soniox, errorCode: 429)
precondition(sonioxQuota.kind == .quota && !sonioxQuota.isRetryable && sonioxQuota.message.contains("429"))
let sonioxWsQuota = ServiceErrorClassifier.classify(provider: .soniox, closeCode: 4029)
precondition(sonioxWsQuota.kind == .quota && !sonioxWsQuota.isRetryable && sonioxWsQuota.message.contains("WS 4029"))
let geminiPolicy = ServiceErrorClassifier.classify(provider: .gemini, closeCode: 1008)
precondition(geminiPolicy.kind == .permission && !geminiPolicy.isRetryable && geminiPolicy.message.contains("WS 1008") && geminiPolicy.message.contains("Live Translate"))
let geminiPolicyQuota = ServiceErrorClassifier.classify(provider: .gemini, closeCode: 1008, closeReason: "Quota exceeded for project")
precondition(geminiPolicyQuota.kind == .quota && !geminiPolicyQuota.isRetryable)
let geminiPolicyAuth = ServiceErrorClassifier.classify(provider: .gemini, closeCode: 1008, closeReason: "API key not valid")
precondition(geminiPolicyAuth.kind == .authentication && !geminiPolicyAuth.isRetryable)
let geminiRpcQuota = ServiceErrorClassifier.classify(provider: .gemini, errorCode: 8, errorStatus: "RESOURCE_EXHAUSTED")
precondition(geminiRpcQuota.kind == .quota && !geminiRpcQuota.isRetryable)
let geminiWsTransient = ServiceErrorClassifier.classify(provider: .gemini, closeCode: 1006)
precondition(geminiWsTransient.kind == .transient && geminiWsTransient.isRetryable)
let geminiWs1011Quota = ServiceErrorClassifier.classify(provider: .gemini, closeCode: 1011, closeReason: "RESOURCE_EXHAUSTED: billing disabled")
precondition(geminiWs1011Quota.kind == .quota && !geminiWs1011Quota.isRetryable)
let sonioxWs1001 = ServiceErrorClassifier.classify(provider: .soniox, closeCode: 1001)
precondition(sonioxWs1001.kind == .transient && sonioxWs1001.isRetryable)
let sonioxWs4001 = ServiceErrorClassifier.classify(provider: .soniox, closeCode: 4001)
precondition(sonioxWs4001.kind == .authentication && !sonioxWs4001.isRetryable)

// 7. Audio PCM encoder (existing check preserved)
func audioSample(rate: Double, channels: AVAudioChannelCount, amplitude: Float) -> CMSampleBuffer {
    let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: channels)!
    let frames = Int(rate / 50)
    let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames))!
    pcm.frameLength = AVAudioFrameCount(frames)
    for ch in 0..<Int(channels) {
        for frame in 0..<frames {
            pcm.floatChannelData![ch][frame] = amplitude * Float(sin(2 * .pi * 500 * Double(frame) / rate))
        }
    }
    var out: CMSampleBuffer?
    precondition(CMAudioSampleBufferCreateWithPacketDescriptions(allocator: nil, dataBuffer: nil, dataReady: false,
        makeDataReadyCallback: nil, refcon: nil, formatDescription: format.formatDescription,
        sampleCount: frames, presentationTimeStamp: .zero, packetDescriptions: nil, sampleBufferOut: &out) == noErr)
    let sample = out!
    precondition(CMSampleBufferSetDataBufferFromAudioBufferList(sample, blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
        flags: 0, bufferList: pcm.audioBufferList) == noErr)
    precondition(CMSampleBufferSetDataReady(sample) == noErr)
    return sample
}
let encoder = PCMEncoder()
for (rate, channels) in [(16000.0, 1), (48000.0, 2), (44100.0, 2)] {
    let signal = audioSample(rate: rate, channels: AVAudioChannelCount(channels), amplitude: 0.5)
    let silent = audioSample(rate: rate, channels: AVAudioChannelCount(channels), amplitude: 0)
    var total = 0
    for _ in 0..<20 {
        if let chunk = try encoder.encode(signal) {
            total += chunk.data.count
            precondition((14000...19000).contains(chunk.peak))
        }
    }
    precondition(abs(total - 20 * 320 * 2) <= 128)
    _ = try encoder.encode(silent)
    precondition(try! encoder.encode(silent)!.peak == 0)
}
print("Passed: captions, provisional clear, bounded PCM queue, phrase wrapping, presentation, reconnect policy, error/WS classification, audio conversion.")
