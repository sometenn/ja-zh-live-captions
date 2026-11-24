package com.sometenn.callcaptions.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline deterministic checks mirroring iOS `Tests/main.swift`:
 * incremental captions, provisional clear, bounded PCM buffering, phrase wrapping,
 * presentation states, reconnect policy, provider error classification, PCM resampling,
 * and protocol JSON helpers.
 */
class CoreEngineTest {

    private fun token(text: String, final: Boolean, translated: Boolean = false): Token =
        Token(
            text = text,
            isFinal = final,
            translationStatus = if (translated) "translation" else "original",
        )

    @Test
    fun captionsPartialRevisionSimplificationAndClearProvisional() {
        val captions = Captions()
        captions.apply(listOf(token("こん", false)))
        assertEquals("こん", captions.japanese)

        captions.apply(listOf(token("今日", true), token("は雨", false), token("今", false, true)))
        assertEquals("今日は雨", captions.japanese)
        assertEquals("今", captions.chinese)

        captions.apply(listOf(token("は晴れ", true), token("今天是晴天", true, true)))
        assertEquals("今日は晴れ", captions.japanese)
        assertEquals("今天是晴天", captions.chinese)

        captions.apply(listOf(token("<end>", true), token("。", true)))
        captions.apply(listOf(token("。學習", true, true)))
        assertEquals("今天是晴天。学习", captions.chinese)
        assertEquals("今日は晴れ。", captions.japanese)

        captions.apply(listOf(token("取消", false), token("修改", false, true)))
        assertEquals("今日は晴れ。取消", captions.japanese)
        assertEquals("今天是晴天。学习修改", captions.chinese)

        captions.clearProvisional()
        assertEquals("今日は晴れ。", captions.japanese)
        assertEquals("今天是晴天。学习", captions.chinese)
        assertTrue(captions.hasCommitted)

        captions.apply(listOf(token("。", false)))
        captions.apply(emptyList())
        assertEquals("今天是晴天。学习", captions.chinese)
        assertEquals("今日は晴れ。", captions.japanese)

        val onlyProvisional = Captions()
        onlyProvisional.apply(listOf(token("もしもし", false), token("喂", false, true)))
        assertFalse(onlyProvisional.hasCommitted)
        onlyProvisional.clearProvisional()
        assertTrue(onlyProvisional.japanese.isEmpty())
        assertTrue(onlyProvisional.chinese.isEmpty())
    }

    @Test
    fun boundedPcmQueueFifoByteCapAgeEvictionOversizedAndClear() {
        val queue = AudioBufferQueue(maxBytes = 12, maxAge = 2.0)
        queue.enqueue(AudioChunk(capturedAt = 10.0, data = ByteArray(0), peak = 0), now = 10.0)
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.totalBytes)

        queue.enqueue(AudioChunk(capturedAt = 10.0, data = ByteArray(4) { 1 }, peak = 0), now = 10.0)
        queue.enqueue(AudioChunk(capturedAt = 10.5, data = ByteArray(4) { 2 }, peak = 0), now = 10.5)
        queue.enqueue(AudioChunk(capturedAt = 11.0, data = ByteArray(4) { 3 }, peak = 0), now = 11.0)
        assertEquals(3, queue.count)
        assertEquals(12, queue.totalBytes)

        // Exceeds maxBytes -> evicts oldest (1)
        queue.enqueue(AudioChunk(capturedAt = 11.2, data = ByteArray(4) { 4 }, peak = 0), now = 11.2)
        assertEquals(3, queue.count)
        assertEquals(12, queue.totalBytes)
        assertEquals(1, queue.droppedCount)
        assertEquals(4, queue.droppedBytes)

        // Oversized single chunk -> dropped without exceeding maxBytes
        queue.enqueue(AudioChunk(capturedAt = 11.3, data = ByteArray(20) { 9 }, peak = 0), now = 11.3)
        assertEquals(3, queue.count)
        assertEquals(12, queue.totalBytes)

        // Age eviction on dequeue: at t = 12.6, chunk at 10.5 is 2.1s old (> 2.0s) and pruned
        val d1 = queue.dequeue(now = 12.6)
        val d2 = queue.dequeue(now = 12.6)
        val d3 = queue.dequeue(now = 12.6)
        assertTrue(d1!!.data.contentEquals(ByteArray(4) { 3 }))
        assertTrue(d2!!.data.contentEquals(ByteArray(4) { 4 }))
        assertNull(d3)
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.totalBytes)

        queue.enqueue(AudioChunk(capturedAt = 20.0, data = ByteArray(6) { 5 }, peak = 10), now = 20.0)
        queue.clear()
        assertTrue(queue.isEmpty)
        assertEquals(0, queue.totalBytes)
    }

    @Test
    fun captionTextLayoutReadableRecentPhraseWrapping() {
        fun charLimit(maxChars: Int): (String) -> Boolean = { it.length <= maxChars }

        assertEquals(
            "こんにちは 世界",
            CaptionTextLayout.fitRecentPhrase("  こんにちは\n  世界  ", fits = charLimit(20)),
        )

        val twoSentences = "いらっしゃいませ。本日のご予約内容を確認いたします。"
        assertEquals(
            "本日のご予約内容を確認いたします。",
            CaptionTextLayout.fitRecentPhrase(twoSentences, fits = charLimit(18)),
        )

        val clauseFallback = "非常感谢您的耐心等待，我们现在帮您确认库存。好的"
        assertEquals(
            "我们现在帮您确认库存。好的",
            CaptionTextLayout.fitRecentPhrase(clauseFallback, fits = charLimit(16)),
        )

        val unbroken = "这是一段没有任何标点符号的连续中文语音识别输出内容测试"
        val fittedUnbroken = CaptionTextLayout.fitRecentPhrase(unbroken, fits = charLimit(12))
        assertTrue(fittedUnbroken.startsWith("…"))
        assertTrue(fittedUnbroken.length <= 12)
        assertTrue(fittedUnbroken.endsWith("输出内容测试"))

        val asciiMixed = "请打开Safari浏览器确认VirtualStore预约状态确认"
        val fittedAscii = CaptionTextLayout.fitRecentPhrase(asciiMixed, fits = charLimit(16))
        assertTrue(fittedAscii.length <= 16)
        assertFalse(fittedAscii.contains("ualStore"))

        val dangling = "前文内容很长很长，，，。最终译文在这里"
        assertEquals(
            "最终译文在这里",
            CaptionTextLayout.fitRecentPhrase(dangling, fits = charLimit(10)),
        )
    }

    @Test
    fun captionPresentationIdleProbeAndTranslateStates() {
        assertEquals(
            OverlayFrame(banner = null, style = OverlayFrame.Style.Bilingual(top = "日语原文", bottom = "简体中文译文")),
            CaptionPresentation.idleFrame(CaptionDisplayMode.BILINGUAL),
        )
        assertEquals(
            OverlayFrame(banner = null, style = OverlayFrame.Style.ChineseOnly(text = "简体中文译文", isPlaceholder = true)),
            CaptionPresentation.idleFrame(CaptionDisplayMode.CHINESE_ONLY),
        )
        assertEquals(
            OverlayFrame(banner = null, style = OverlayFrame.Style.Probe(status = "已检测到机内声音", meter = "▰▰▰▰▱▱▱▱▱▱")),
            CaptionPresentation.probeFrame(status = "已检测到机内声音", level = 4),
        )

        val captions = Captions()
        captions.apply(listOf(token("今日は晴れ。", true), token("今天是晴天。學習", true, true)))

        val reconBilingual = CaptionPresentation.translateFrame(
            displayMode = CaptionDisplayMode.BILINGUAL,
            captions = captions,
            status = "连接中断，正在重连 Soniox（1/4）",
            isReconnecting = true,
            isConnecting = false,
            isAudioStalled = false,
        )
        assertEquals(
            OverlayBanner(text = "连接中断，正在重连 Soniox（1/4）", tone = OverlayBanner.Tone.WARNING),
            reconBilingual.banner,
        )
        assertEquals(
            OverlayFrame.Style.Bilingual(top = "今日は晴れ。", bottom = "今天是晴天。学习"),
            reconBilingual.style,
        )

        val reconChinese = CaptionPresentation.translateFrame(
            displayMode = CaptionDisplayMode.CHINESE_ONLY,
            captions = captions,
            status = "连接中断，正在重连 Gemini（2/4）",
            isReconnecting = true,
            isConnecting = false,
            isAudioStalled = false,
        )
        assertEquals(
            OverlayBanner(text = "连接中断，正在重连 Gemini（2/4）", tone = OverlayBanner.Tone.WARNING),
            reconChinese.banner,
        )
        assertEquals(
            OverlayFrame.Style.ChineseOnly(text = "今天是晴天。学习", isPlaceholder = false),
            reconChinese.style,
        )

        val healthyChinese = CaptionPresentation.translateFrame(
            displayMode = CaptionDisplayMode.CHINESE_ONLY,
            captions = captions,
            status = "正在翻译",
            isReconnecting = false,
            isConnecting = false,
            isAudioStalled = false,
        )
        assertNull(healthyChinese.banner)
        assertEquals(
            OverlayFrame.Style.ChineseOnly(text = "今天是晴天。学习", isPlaceholder = false),
            healthyChinese.style,
        )

        val connectingChinese = CaptionPresentation.translateFrame(
            displayMode = CaptionDisplayMode.CHINESE_ONLY,
            captions = Captions(),
            status = "正在连接 Gemini",
            isReconnecting = false,
            isConnecting = true,
            isAudioStalled = false,
        )
        assertEquals(
            OverlayBanner(text = "正在连接 Gemini", tone = OverlayBanner.Tone.INFO),
            connectingChinese.banner,
        )
        assertEquals(
            OverlayFrame.Style.ChineseOnly(text = "等待中文译文…", isPlaceholder = true),
            connectingChinese.style,
        )
    }

    @Test
    fun reconnectPolicyBoundedRetriesAndStabilityWindow() {
        val policy = ReconnectPolicy(maxAttempts = 3, delays = listOf(0.5, 1.0, 2.0), stableDuration = 15.0)
        policy.markConnected(at = 10.0)
        assertEquals(ReconnectPolicy.Decision(1, 3, 0.5), policy.recordTransientFailure(at = 11.0))

        policy.markConnected(at = 12.0)
        assertEquals(ReconnectPolicy.Decision(2, 3, 1.0), policy.recordTransientFailure(at = 13.0))

        policy.markConnected(at = 14.5)
        assertEquals(ReconnectPolicy.Decision(3, 3, 2.0), policy.recordTransientFailure(at = 15.5))

        policy.markConnected(at = 18.0)
        assertNull(policy.recordTransientFailure(at = 19.0))

        policy.reset()
        policy.markConnected(at = 100.0)
        policy.recordTransientFailure(at = 101.0)
        policy.markConnected(at = 102.0)
        assertEquals(ReconnectPolicy.Decision(1, 3, 0.5), policy.recordTransientFailure(at = 120.0))
    }

    @Test
    fun serviceErrorClassifierActionableErrorsAndCloseCodes() {
        val sonioxAuth = ServiceErrorClassifier.classify(provider = Provider.SONIOX, httpStatus = 401)
        assertEquals(CaptionError.Kind.AUTHENTICATION, sonioxAuth.kind)
        assertFalse(sonioxAuth.isRetryable)
        assertTrue(sonioxAuth.message.contains("HTTP 401"))

        val sonioxQuota = ServiceErrorClassifier.classify(provider = Provider.SONIOX, errorCode = 429)
        assertEquals(CaptionError.Kind.QUOTA, sonioxQuota.kind)
        assertFalse(sonioxQuota.isRetryable)
        assertTrue(sonioxQuota.message.contains("429"))

        val sonioxWsQuota = ServiceErrorClassifier.classify(provider = Provider.SONIOX, closeCode = 4029)
        assertEquals(CaptionError.Kind.QUOTA, sonioxWsQuota.kind)
        assertFalse(sonioxWsQuota.isRetryable)
        assertTrue(sonioxWsQuota.message.contains("WS 4029"))

        val geminiPolicy = ServiceErrorClassifier.classify(provider = Provider.GEMINI, closeCode = 1008)
        assertEquals(CaptionError.Kind.PERMISSION, geminiPolicy.kind)
        assertFalse(geminiPolicy.isRetryable)
        assertTrue(geminiPolicy.message.contains("WS 1008") && geminiPolicy.message.contains("Live Translate"))

        val geminiPolicyQuota = ServiceErrorClassifier.classify(
            provider = Provider.GEMINI,
            closeCode = 1008,
            closeReason = "Quota exceeded for project",
        )
        assertEquals(CaptionError.Kind.QUOTA, geminiPolicyQuota.kind)
        assertFalse(geminiPolicyQuota.isRetryable)

        val geminiPolicyAuth = ServiceErrorClassifier.classify(
            provider = Provider.GEMINI,
            closeCode = 1008,
            closeReason = "API key not valid",
        )
        assertEquals(CaptionError.Kind.AUTHENTICATION, geminiPolicyAuth.kind)
        assertFalse(geminiPolicyAuth.isRetryable)

        val geminiRpcQuota = ServiceErrorClassifier.classify(
            provider = Provider.GEMINI,
            errorCode = 8,
            errorStatus = "RESOURCE_EXHAUSTED",
        )
        assertEquals(CaptionError.Kind.QUOTA, geminiRpcQuota.kind)
        assertFalse(geminiRpcQuota.isRetryable)

        val geminiWsTransient = ServiceErrorClassifier.classify(provider = Provider.GEMINI, closeCode = 1006)
        assertEquals(CaptionError.Kind.TRANSIENT, geminiWsTransient.kind)
        assertTrue(geminiWsTransient.isRetryable)

        val geminiWs1011Quota = ServiceErrorClassifier.classify(
            provider = Provider.GEMINI,
            closeCode = 1011,
            closeReason = "RESOURCE_EXHAUSTED: billing disabled",
        )
        assertEquals(CaptionError.Kind.QUOTA, geminiWs1011Quota.kind)
        assertFalse(geminiWs1011Quota.isRetryable)

        val sonioxWs1001 = ServiceErrorClassifier.classify(provider = Provider.SONIOX, closeCode = 1001)
        assertEquals(CaptionError.Kind.TRANSIENT, sonioxWs1001.kind)
        assertTrue(sonioxWs1001.isRetryable)

        val sonioxWs4001 = ServiceErrorClassifier.classify(provider = Provider.SONIOX, closeCode = 4001)
        assertEquals(CaptionError.Kind.AUTHENTICATION, sonioxWs4001.kind)
        assertFalse(sonioxWs4001.isRetryable)
    }

    @Test
    fun pcmEncoderResamplingAndPeakDetection() {
        val encoder = PcmEncoder()
        for ((rate, channels) in listOf(16_000 to 1, 48_000 to 2, 44_100 to 2)) {
            val frames = rate / 50
            val signal = FloatArray(frames * channels)
            val silent = FloatArray(frames * channels)
            for (frame in 0 until frames) {
                val sample = 0.5f * sin(2.0 * PI * 500.0 * frame / rate).toFloat()
                for (ch in 0 until channels) {
                    signal[frame * channels + ch] = sample
                }
            }
            var totalBytes = 0
            for (i in 0 until 20) {
                val chunk = encoder.encodeFloat(
                    interleaved = signal,
                    frameCount = frames,
                    sampleRate = rate,
                    channels = channels,
                    capturedAt = 1.0 + i * 0.02,
                )
                if (chunk != null) {
                    totalBytes += chunk.data.size
                    assertTrue(
                        "Unexpected peak ${chunk.peak} for rate=$rate ch=$channels",
                        chunk.peak in 14_000..19_000,
                    )
                }
            }
            assertTrue(
                "Unexpected byte count $totalBytes for rate=$rate ch=$channels",
                abs(totalBytes - 20 * 320 * 2) <= 128,
            )
            encoder.encodeFloat(silent, frames, rate, channels, capturedAt = 2.0)
            val silentChunk = encoder.encodeFloat(silent, frames, rate, channels, capturedAt = 2.02)
            assertEquals(0, silentChunk!!.peak)
        }
    }

    @Test
    fun providerJsonPayloadsAndVaultHelpers() {
        val sonioxConfig = Soniox.buildConfigJson()
        assertTrue(sonioxConfig.contains("\"model\":\"stt-rt-v6\""))
        assertTrue(sonioxConfig.contains("\"target_language\":\"zh\""))

        val reply = Soniox.parseReply("""{"tokens":[{"text":"こんにちは","is_final":true,"translation_status":"original"}],"finished":false}""")
        assertEquals(1, reply.tokens?.size)
        assertEquals("こんにちは", reply.tokens?.first()?.text)
        assertEquals(false, reply.finished)

        val geminiSetup = Gemini.buildSetupJson()
        assertTrue(geminiSetup.contains("models/gemini-3.5-live-translate-preview"))
        assertTrue(geminiSetup.contains("\"targetLanguageCode\":\"zh-Hans\""))

        val geminiAudio = Gemini.buildAudioPayloadJson(byteArrayOf(1, 2, 3, 4))
        assertTrue(geminiAudio.contains("\"mimeType\":\"audio/pcm;rate=16000\""))
        assertTrue(geminiAudio.contains("\"data\":\"AQIDBA==\""))

        val vault = InMemoryKeyVault()
        assertEquals("", vault.read(Provider.SONIOX))
        vault.save("sk-test", Provider.SONIOX)
        assertEquals("sk-test", vault.read(Provider.SONIOX))
        vault.save("", Provider.SONIOX)
        assertEquals("", vault.read(Provider.SONIOX))
    }
}
