using CallCaptions.Core;
using NAudio.Wave;
using Xunit;

namespace CallCaptions.Tests;

public sealed class CoreEngineTests
{
    private static Token Tok(string text, bool isFinal, bool translated = false) =>
        new(text, isFinal, translated ? "translation" : "original");

    [Fact]
    public void Captions_HandlesProvisionalRevisionEndpointsSimplifiedAndClear()
    {
        var captions = new Captions();
        captions.Apply([Tok("こん", false)]);
        Assert.Equal("こん", captions.Japanese);

        captions.Apply([Tok("今日", true), Tok("は雨", false), Tok("今", false, true)]);
        Assert.Equal("今日は雨", captions.Japanese);
        Assert.Equal("今", captions.Chinese);

        captions.Apply([Tok("は晴れ", true), Tok("今天是晴天", true, true)]);
        Assert.Equal("今日は晴れ", captions.Japanese);
        Assert.Equal("今天是晴天", captions.Chinese);

        captions.Apply([Tok("<end>", true), Tok("。", true)]);
        captions.Apply([Tok("。學習", true, true)]);
        Assert.Equal("今天是晴天。学习", captions.Chinese);
        Assert.Equal("今日は晴れ。", captions.Japanese);

        captions.Apply([Tok("取消", false), Tok("修改", false, true)]);
        Assert.Equal("今日は晴れ。取消", captions.Japanese);
        Assert.Equal("今天是晴天。学习修改", captions.Chinese);

        captions.ClearProvisional();
        Assert.Equal("今日は晴れ。", captions.Japanese);
        Assert.Equal("今天是晴天。学习", captions.Chinese);
        Assert.True(captions.HasCommitted);

        captions.Apply([Tok("。", false)]);
        captions.Apply([]);
        Assert.Equal("今天是晴天。学习", captions.Chinese);
        Assert.Equal("今日は晴れ。", captions.Japanese);

        var onlyProvisional = new Captions();
        onlyProvisional.Apply([Tok("もしもし", false), Tok("喂", false, true)]);
        Assert.False(onlyProvisional.HasCommitted);
        onlyProvisional.ClearProvisional();
        Assert.Empty(onlyProvisional.Japanese);
        Assert.Empty(onlyProvisional.Chinese);
    }

    [Fact]
    public void AudioBufferQueue_EnforcesFifoByteCapAgeEvictionAndClear()
    {
        var queue = new AudioBufferQueue(maxBytes: 12, maxAge: 2.0);
        queue.Enqueue(new AudioChunk(10.0, [], 0), now: 10.0);
        Assert.True(queue.IsEmpty);
        Assert.Equal(0, queue.TotalBytes);

        queue.Enqueue(new AudioChunk(10.0, [1, 1, 1, 1], 0), now: 10.0);
        queue.Enqueue(new AudioChunk(10.5, [2, 2, 2, 2], 0), now: 10.5);
        queue.Enqueue(new AudioChunk(11.0, [3, 3, 3, 3], 0), now: 11.0);
        Assert.Equal(3, queue.Count);
        Assert.Equal(12, queue.TotalBytes);

        // Exceeds maxBytes -> evicts oldest (1)
        queue.Enqueue(new AudioChunk(11.2, [4, 4, 4, 4], 0), now: 11.2);
        Assert.Equal(3, queue.Count);
        Assert.Equal(12, queue.TotalBytes);
        Assert.Equal(1, queue.DroppedCount);
        Assert.Equal(4, queue.DroppedBytes);

        // Oversized single chunk -> dropped without exceeding maxBytes
        queue.Enqueue(new AudioChunk(11.3, new byte[20], 0), now: 11.3);
        Assert.Equal(3, queue.Count);
        Assert.Equal(12, queue.TotalBytes);

        // Age eviction on dequeue: at t = 12.6, 10.5 is 2.1s old (> 2.0s) and pruned
        var d1 = queue.Dequeue(now: 12.6);
        var d2 = queue.Dequeue(now: 12.6);
        var d3 = queue.Dequeue(now: 12.6);
        Assert.Equal([3, 3, 3, 3], d1?.Data);
        Assert.Equal([4, 4, 4, 4], d2?.Data);
        Assert.Null(d3);
        Assert.True(queue.IsEmpty);
        Assert.Equal(0, queue.TotalBytes);

        queue.Enqueue(new AudioChunk(20.0, new byte[6], 10), now: 20.0);
        queue.Clear();
        Assert.True(queue.IsEmpty);
        Assert.Equal(0, queue.TotalBytes);
    }

    [Fact]
    public void CaptionTextLayout_FitsRecentPhraseCleanly()
    {
        Func<string, bool> CharLimit(int max) => s => s.Length <= max;

        Assert.Equal("こんにちは 世界", CaptionTextLayout.FitRecentPhrase("  こんにちは\n  世界  ", CharLimit(20)));

        string twoSentences = "いらっしゃいませ。本日のご予約内容を確認いたします。";
        Assert.Equal("本日のご予約内容を確認いたします。", CaptionTextLayout.FitRecentPhrase(twoSentences, CharLimit(18)));

        string clauseFallback = "非常感谢您的耐心等待，我们现在帮您确认库存。好的";
        Assert.Equal("我们现在帮您确认库存。好的", CaptionTextLayout.FitRecentPhrase(clauseFallback, CharLimit(14)));

        string asciiSentence = "Please check Soniox configuration first.";
        string fittedAscii = CaptionTextLayout.FitRecentPhrase(asciiSentence, CharLimit(18));
        Assert.DoesNotContain("onfiguration", fittedAscii);
        Assert.StartsWith("…", fittedAscii);
    }

    [Fact]
    public void ServiceErrorClassifier_AndReconnectPolicy_BehaveDeterministically()
    {
        var geminiPolicyAuth = ServiceErrorClassifier.Classify(Provider.Gemini, closeCode: 1008, closeReason: "API key not valid");
        Assert.Equal(CaptionErrorKind.Authentication, geminiPolicyAuth.Kind);
        Assert.False(geminiPolicyAuth.IsRetryable);

        var geminiRpcQuota = ServiceErrorClassifier.Classify(Provider.Gemini, errorCode: 8, errorStatus: "RESOURCE_EXHAUSTED");
        Assert.Equal(CaptionErrorKind.Quota, geminiRpcQuota.Kind);
        Assert.False(geminiRpcQuota.IsRetryable);

        var geminiWsTransient = ServiceErrorClassifier.Classify(Provider.Gemini, closeCode: 1006);
        Assert.Equal(CaptionErrorKind.Transient, geminiWsTransient.Kind);
        Assert.True(geminiWsTransient.IsRetryable);

        var geminiWs1011Quota = ServiceErrorClassifier.Classify(Provider.Gemini, closeCode: 1011, closeReason: "RESOURCE_EXHAUSTED: billing disabled");
        Assert.Equal(CaptionErrorKind.Quota, geminiWs1011Quota.Kind);
        Assert.False(geminiWs1011Quota.IsRetryable);

        var sonioxWs1001 = ServiceErrorClassifier.Classify(Provider.Soniox, closeCode: 1001);
        Assert.Equal(CaptionErrorKind.Transient, sonioxWs1001.Kind);
        Assert.True(sonioxWs1001.IsRetryable);

        var sonioxWs4001 = ServiceErrorClassifier.Classify(Provider.Soniox, closeCode: 4001);
        Assert.Equal(CaptionErrorKind.Authentication, sonioxWs4001.Kind);
        Assert.False(sonioxWs4001.IsRetryable);

        var policy = new ReconnectPolicy(maxAttempts: 2, delays: [0.5, 1.0], stableDuration: 15.0);
        policy.MarkConnected(10.0);
        Assert.Equal(new ReconnectDecision(1, 2, 0.5), policy.RecordTransientFailure(12.0));
        policy.MarkConnected(13.0);
        Assert.Equal(new ReconnectDecision(2, 2, 1.0), policy.RecordTransientFailure(20.0));
        Assert.Null(policy.RecordTransientFailure(21.0));

        policy.MarkConnected(30.0);
        policy.NoteHealthy(46.0);
        Assert.Equal(0, policy.ConsecutiveFailures);
    }

    [Fact]
    public void PcmResampler_ConvertsMonoAndStereoTo16kHzMono16Bit()
    {
        var encoder = new PcmResampler();

        foreach (var (rate, channels) in new[] { (16000, 1), (48000, 2), (44100, 2) })
        {
            byte[] signal = BuildFloatSineBuffer(rate, channels, 0.5f);
            byte[] silent = BuildFloatSineBuffer(rate, channels, 0.0f);
            var format = WaveFormat.CreateIeeeFloatWaveFormat(rate, channels);

            int totalBytes = 0;
            for (int i = 0; i < 20; i++)
            {
                var chunk = encoder.Encode(signal, signal.Length, format);
                if (chunk is not null)
                {
                    totalBytes += chunk.Data.Length;
                    Assert.InRange(chunk.Peak, 14000, 19000);
                }
            }

            Assert.True(Math.Abs(totalBytes - 20 * 320 * 2) <= 256, $"Unexpected byte count {totalBytes} for {rate}Hz/{channels}ch");

            // Flush any resampler tail and verify silence drops peak to ~0
            _ = encoder.Encode(silent, silent.Length, format);
            var quiet = encoder.Encode(silent, silent.Length, format);
            Assert.NotNull(quiet);
            Assert.InRange(quiet!.Peak, 0, 16);
        }
    }

    [Fact]
    public void DpapiVault_AndSettingsStore_RoundTripInTempDirectory()
    {
        string tempDir = Path.Combine(Path.GetTempPath(), "CallCaptionsTests_" + Guid.NewGuid().ToString("N"));
        try
        {
            var vault = new DpapiVault(tempDir);
            Assert.Equal(string.Empty, vault.Read(Provider.Soniox));

            vault.Save("secret-soniox-123", Provider.Soniox);
            vault.Save("secret-gemini-456", Provider.Gemini);
            Assert.Equal("secret-soniox-123", vault.Read(Provider.Soniox));
            Assert.Equal("secret-gemini-456", vault.Read(Provider.Gemini));

            vault.Save(string.Empty, Provider.Soniox);
            Assert.Equal(string.Empty, vault.Read(Provider.Soniox));

            var store = new JsonSettingsStore(tempDir);
            var data = new AppSettingsData
            {
                Provider = Provider.Gemini,
                Source = AudioSource.Microphone,
                DisplayMode = CaptionDisplayMode.ChineseOnly,
                Appearance = new CaptionAppearanceSettings
                {
                    FontFamily = "Microsoft YaHei UI",
                    SourceSize = 20,
                    TranslationSize = 36,
                    Ink = CaptionInk.Mint,
                    Opacity = 80
                }
            };
            store.Save(data);

            var loaded = store.Load();
            Assert.Equal(Provider.Gemini, loaded.Provider);
            Assert.Equal(AudioSource.Microphone, loaded.Source);
            Assert.Equal(CaptionDisplayMode.ChineseOnly, loaded.DisplayMode);
            Assert.Equal(CaptionInk.Mint, loaded.Appearance.Ink);
            Assert.Equal(36, loaded.Appearance.TranslationSize);
        }
        finally
        {
            if (Directory.Exists(tempDir)) Directory.Delete(tempDir, recursive: true);
        }
    }

    private static byte[] BuildFloatSineBuffer(int rate, int channels, float amplitude)
    {
        int frames = rate / 50; // 20 ms
        var bytes = new byte[frames * channels * sizeof(float)];
        int offset = 0;
        for (int frame = 0; frame < frames; frame++)
        {
            float sample = amplitude * (float)Math.Sin(2.0 * Math.PI * 500.0 * frame / rate);
            byte[] raw = BitConverter.GetBytes(sample);
            for (int ch = 0; ch < channels; ch++)
            {
                Buffer.BlockCopy(raw, 0, bytes, offset, sizeof(float));
                offset += sizeof(float);
            }
        }
        return bytes;
    }
}
