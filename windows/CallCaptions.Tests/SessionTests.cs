using System.Collections.Concurrent;
using System.Threading.Channels;
using CallCaptions.Core;
using Xunit;

namespace CallCaptions.Tests;

public sealed class SessionTests
{
    private sealed class AsyncGate
    {
        private readonly TaskCompletionSource _tcs = new(TaskCreationOptions.RunContinuationsAsynchronously);

        public Task WaitAsync(CancellationToken ct = default) => _tcs.Task.WaitAsync(ct);
        public void Succeed() => _tcs.TrySetResult();
        public void Fail(Exception ex) => _tcs.TrySetException(ex);
        public void Cancel() => _tcs.TrySetCanceled();
    }

    private sealed class TestPump
    {
        private readonly ConcurrentQueue<Action> _queue = new();

        public Action<Action> Dispatcher => action => _queue.Enqueue(action);

        public void Drain()
        {
            while (_queue.TryDequeue(out var action))
            {
                action();
            }
        }

        public async Task FlushAsync(int rounds = 8)
        {
            for (int i = 0; i < rounds; i++)
            {
                Drain();
                await Task.Delay(5);
                Drain();
            }
        }

        public async Task<bool> WaitUntilAsync(Func<bool> condition, int timeoutMs = 3000)
        {
            var deadline = Environment.TickCount64 + timeoutMs;
            while (Environment.TickCount64 < deadline)
            {
                Drain();
                if (condition()) return true;
                await Task.Delay(5);
            }
            Drain();
            return condition();
        }
    }

    private sealed class FakeCapture : IAudioCapture
    {
        private readonly Channel<AudioChunk> _channel = Channel.CreateUnbounded<AudioChunk>();
        public ChannelReader<AudioChunk> Audio => _channel.Reader;
        public Action? Started { get; set; }
        public bool Presented { get; private set; }
        public bool Stopped { get; private set; }
        public AsyncGate? StopGate { get; set; }

        public void Present() => Presented = true;
        public void Emit(AudioChunk chunk) => _channel.Writer.TryWrite(chunk);
        public void TriggerStart() => Started?.Invoke();

        public void Revoke(Exception? ex = null) =>
            _channel.Writer.TryComplete(ex ?? new CaptionError("系统已结束声音采集。请回到应用重新开始。"));

        public async Task StopAsync()
        {
            Stopped = true;
            _channel.Writer.TryComplete();
            if (StopGate is not null)
            {
                try { await StopGate.WaitAsync(); } catch { }
            }
        }
    }

    private sealed class FakeService : ICaptionService
    {
        public Provider Provider { get; }
        public string Key { get; }
        public AsyncGate? StartGate { get; set; }
        public Dictionary<int, AsyncGate> SendGateForIndex { get; } = new();
        public Exception? StartError { get; set; }
        public bool StartCalled { get; private set; }
        public bool Closed { get; private set; }
        public List<byte[]> Sent { get; } = new();

        private Action<IReadOnlyList<Token>>? _receive;
        private Action<Exception>? _failed;

        public FakeService(Provider provider, string key)
        {
            Provider = provider;
            Key = key;
        }

        public async Task StartAsync(Action<IReadOnlyList<Token>> receive, Action<Exception> failed, CancellationToken cancellationToken = default)
        {
            StartCalled = true;
            _receive = receive;
            _failed = failed;
            if (StartGate is not null) await StartGate.WaitAsync(cancellationToken);
            if (StartError is not null) throw StartError;
        }

        public async Task SendAsync(byte[] data, CancellationToken cancellationToken = default)
        {
            int idx = Sent.Count;
            if (SendGateForIndex.TryGetValue(idx, out var gate))
            {
                await gate.WaitAsync(cancellationToken);
            }
            if (Closed) throw new CaptionError("Socket closed", CaptionErrorKind.Transient);
            Sent.Add(data);
        }

        public void Deliver(IReadOnlyList<Token> tokens) => _receive?.Invoke(tokens);
        public void Disconnect(Exception error) => _failed?.Invoke(error);

        public void Close()
        {
            Closed = true;
            StartGate?.Cancel();
            foreach (var gate in SendGateForIndex.Values) gate.Cancel();
        }
    }

    private sealed class FakeOverlay : IOverlayController
    {
        public bool Prepared { get; private set; }
        public bool Stopped { get; private set; }
        public OverlayFrame? LastFrame { get; private set; }

        public void Prepare() => Prepared = true;
        public void Draw(OverlayFrame frame) => LastFrame = frame;
        public void Stop() => Stopped = true;
    }

    private sealed class MemorySettingsStore : ISettingsStore
    {
        public AppSettingsData Data { get; private set; } = new();
        public AppSettingsData Load() => Data;
        public void Save(AppSettingsData data) => Data = data;
    }

    private sealed class FakeClock
    {
        public double Now { get; private set; }
        public FakeClock(double start = 100.0) => Now = start;
        public void Advance(double delta) => Now += delta;
    }

    private sealed class FakeSleeper
    {
        public List<double> Delays { get; } = new();
        public bool HoldNext { get; set; }
        public AsyncGate? ActiveGate { get; private set; }

        public async Task SleepAsync(double seconds, CancellationToken ct)
        {
            Delays.Add(seconds);
            if (HoldNext)
            {
                var gate = new AsyncGate();
                ActiveGate = gate;
                try
                {
                    await gate.WaitAsync(ct);
                }
                finally
                {
                    if (ReferenceEquals(ActiveGate, gate)) ActiveGate = null;
                }
            }
        }
    }

    private static byte[] Pcm(byte id, int count = 4) => Enumerable.Repeat(id, count).ToArray();
    private static Token Tok(string text, bool isFinal, bool translated = false) =>
        new(text, isFinal, translated ? "translation" : "original");

    [Fact]
    public async Task EagerStart_PreStartAudio_OrderedDrain_NoDuplicates()
    {
        var pump = new TestPump();
        var clock = new FakeClock(100.0);
        var settings = new MemorySettingsStore();
        var overlay = new FakeOverlay();
        var captures = new List<FakeCapture>();
        var services = new List<FakeService>();
        var startGate = new AsyncGate();
        var sendGate = new AsyncGate();

        var session = new Session(
            settingsStore: settings,
            overlay: overlay,
            makeCapture: _ => { var c = new FakeCapture(); captures.Add(c); return c; },
            makeService: (p, k) =>
            {
                var s = new FakeService(p, k) { StartGate = startGate };
                s.SendGateForIndex[4] = sendGate;
                services.Add(s);
                return s;
            },
            readKey: _ => "test-key",
            writeKey: (_, _) => { },
            clock: () => clock.Now,
            sleep: (_, _) => Task.CompletedTask,
            dispatch: pump.Dispatcher,
            enableTicker: false);

        session.Begin(SessionMode.Translate);
        Assert.Equal(SessionStage.Picking, session.Stage);
        Assert.Empty(services);

        // Pre-start quiet audio arrives before Started()
        var cap = captures[0];
        cap.Emit(new AudioChunk(100.1, Pcm(1), 0));
        cap.Emit(new AudioChunk(100.2, Pcm(2), 5));
        await pump.FlushAsync();
        Assert.Empty(services);
        Assert.Equal(2, session.QueuedAudioCount);

        // Start capture -> connects immediately
        cap.TriggerStart();
        Assert.True(await pump.WaitUntilAsync(() => services.Count == 1 && services[0].StartCalled));
        Assert.Equal(SessionStage.Listening, session.Stage);
        Assert.Equal(ConnectionState.ConnectingInstance, session.ConnectionState);
        Assert.Equal("正在连接 Soniox", session.Status);

        // More audio during handshake
        cap.Emit(new AudioChunk(100.3, Pcm(3), 0));
        cap.Emit(new AudioChunk(100.4, Pcm(4), 0));
        await pump.FlushAsync();
        Assert.Empty(services[0].Sent);
        Assert.Equal(4, session.QueuedAudioCount);

        // Complete handshake -> drains [1,2,3,4] in FIFO order
        startGate.Succeed();
        Assert.True(await pump.WaitUntilAsync(() => services[0].Sent.Count == 4));
        Assert.Equal([Pcm(1), Pcm(2), Pcm(3), Pcm(4)], services[0].Sent);
        Assert.Equal(ConnectionState.ConnectedInstance, session.ConnectionState);

        // While send #5 is in flight, #6 arrives -> strictly ordered
        cap.Emit(new AudioChunk(100.5, Pcm(5), 100));
        cap.Emit(new AudioChunk(100.6, Pcm(6), 100));
        await pump.FlushAsync();
        Assert.Equal(4, services[0].Sent.Count);
        sendGate.Succeed();
        Assert.True(await pump.WaitUntilAsync(() => services[0].Sent.Count == 6));
        Assert.Equal([Pcm(1), Pcm(2), Pcm(3), Pcm(4), Pcm(5), Pcm(6)], services[0].Sent);

        session.Finish();
        Assert.True(await pump.WaitUntilAsync(() => session.Stage == SessionStage.Idle));
        Assert.True(services[0].Closed && cap.Stopped);
    }

    [Fact]
    public async Task LocalProbe_NeverOpensNetwork_AndRendersMeter()
    {
        var pump = new TestPump();
        var clock = new FakeClock(300.0);
        var captures = new List<FakeCapture>();
        int serviceCalls = 0;

        var session = new Session(
            settingsStore: new MemorySettingsStore(),
            overlay: new FakeOverlay(),
            makeCapture: _ => { var c = new FakeCapture(); captures.Add(c); return c; },
            makeService: (p, k) => { serviceCalls++; return new FakeService(p, k); },
            readKey: _ => string.Empty,
            writeKey: (_, _) => { },
            clock: () => clock.Now,
            sleep: (_, _) => Task.CompletedTask,
            dispatch: pump.Dispatcher,
            enableTicker: false);

        session.Begin(SessionMode.Probe);
        captures[0].TriggerStart();
        Assert.True(await pump.WaitUntilAsync(() => session.Stage == SessionStage.Listening));
        Assert.Equal(0, serviceCalls);
        Assert.Equal("等待对方声音", session.Status);

        captures[0].Emit(new AudioChunk(300.5, Pcm(1), 8000));
        await pump.FlushAsync();
        clock.Advance(0.5);
        session.Tick(force: true);
        Assert.Equal("已检测到机内声音", session.Status);
        var probeStyle = Assert.IsType<OverlayStyle.Probe>(session.Frame.Style);
        Assert.Contains('▰', probeStyle.Meter);

        session.Finish();
        Assert.True(await pump.WaitUntilAsync(() => session.Stage == SessionStage.Idle));
        Assert.Equal(0, serviceCalls);
    }

    [Fact]
    public async Task DisplayMode_PersistsAndSwitchesLive()
    {
        var pump = new TestPump();
        var clock = new FakeClock(400.0);
        var settings = new MemorySettingsStore();
        var captures = new List<FakeCapture>();
        var services = new List<FakeService>();

        var session = new Session(
            settingsStore: settings,
            overlay: new FakeOverlay(),
            makeCapture: _ => { var c = new FakeCapture(); captures.Add(c); return c; },
            makeService: (p, k) => { var s = new FakeService(p, k); services.Add(s); return s; },
            readKey: _ => "test-key",
            writeKey: (_, _) => { },
            clock: () => clock.Now,
            sleep: (_, _) => Task.CompletedTask,
            dispatch: pump.Dispatcher,
            enableTicker: false);

        Assert.Equal(CaptionDisplayMode.Bilingual, session.DisplayMode);
        session.SelectDisplayMode(CaptionDisplayMode.ChineseOnly);
        Assert.Equal(CaptionDisplayMode.ChineseOnly, settings.Data.DisplayMode);
        Assert.IsType<OverlayStyle.ChineseOnly>(session.Frame.Style);

        session.Begin(SessionMode.Translate);
        captures[0].TriggerStart();
        Assert.True(await pump.WaitUntilAsync(() => services.Count == 1 && session.ConnectionState is ConnectionState.Connected));

        services[0].Deliver([Tok("こんにちは", true), Tok("你好", true, true)]);
        Assert.True(await pump.WaitUntilAsync(() => session.CurrentCaptions.Chinese == "你好"));
        var zhStyle = Assert.IsType<OverlayStyle.ChineseOnly>(session.Frame.Style);
        Assert.Equal("你好", zhStyle.Text);

        session.SelectDisplayMode(CaptionDisplayMode.Bilingual);
        var biStyle = Assert.IsType<OverlayStyle.Bilingual>(session.Frame.Style);
        Assert.Equal("こんにちは", biStyle.Top);
        Assert.Equal("你好", biStyle.Bottom);

        session.Finish();
        Assert.True(await pump.WaitUntilAsync(() => session.Stage == SessionStage.Idle));
    }

    [Fact]
    public async Task TransientReconnect_PreservesCommitted_ClearsProvisional_AndDrainsQueue()
    {
        var pump = new TestPump();
        var clock = new FakeClock(500.0);
        var sleeper = new FakeSleeper { HoldNext = true };
        var captures = new List<FakeCapture>();
        var services = new List<FakeService>();

        var session = new Session(
            settingsStore: new MemorySettingsStore(),
            overlay: new FakeOverlay(),
            makeCapture: _ => { var c = new FakeCapture(); captures.Add(c); return c; },
            makeService: (p, k) => { var s = new FakeService(p, k); services.Add(s); return s; },
            readKey: _ => "test-key",
            writeKey: (_, _) => { },
            clock: () => clock.Now,
            sleep: sleeper.SleepAsync,
            dispatch: pump.Dispatcher,
            reconnectPolicy: new ReconnectPolicy(maxAttempts: 2, delays: [0.5, 1.0], stableDuration: 15.0),
            enableTicker: false);

        session.Begin(SessionMode.Translate);
        captures[0].TriggerStart();
        Assert.True(await pump.WaitUntilAsync(() => services.Count == 1 && session.ConnectionState is ConnectionState.Connected));

        services[0].Deliver([
            Tok("今日は", true),
            Tok("今日は", true, true),
            Tok("仮", false),
            Tok("草稿", false, true)
        ]);
        Assert.True(await pump.WaitUntilAsync(() => session.CurrentCaptions.Japanese == "今日は仮"));

        services[0].Disconnect(new CaptionError("网络波动", CaptionErrorKind.Transient));
        Assert.True(await pump.WaitUntilAsync(() => session.IsReconnecting));

        Assert.Equal("今日は", session.CurrentCaptions.Japanese);
        Assert.Equal("今日は", session.CurrentCaptions.Chinese);
        Assert.NotNull(session.Frame.Banner);
        Assert.Equal(OverlayBannerTone.Warning, session.Frame.Banner!.Tone);

        // Audio captured while reconnecting gets buffered and drained on reconnect
        captures[0].Emit(new AudioChunk(500.5, Pcm(7), 200));
        await pump.FlushAsync();
        Assert.Equal(1, session.QueuedAudioCount);

        sleeper.HoldNext = false;
        sleeper.ActiveGate!.Succeed();
        Assert.True(await pump.WaitUntilAsync(() => services.Count == 2 && session.ConnectionState is ConnectionState.Connected));
        Assert.True(await pump.WaitUntilAsync(() => services[1].Sent.Count == 1));
        Assert.Equal(Pcm(7), services[1].Sent[0]);

        session.Finish();
        Assert.True(await pump.WaitUntilAsync(() => session.Stage == SessionStage.Idle));
    }

    [Fact]
    public async Task NonRetryableError_FailsImmediatelyWithoutReconnect()
    {
        var pump = new TestPump();
        var clock = new FakeClock(600.0);
        var sleeper = new FakeSleeper();
        var captures = new List<FakeCapture>();
        var services = new List<FakeService>();

        var session = new Session(
            settingsStore: new MemorySettingsStore(),
            overlay: new FakeOverlay(),
            makeCapture: _ => { var c = new FakeCapture(); captures.Add(c); return c; },
            makeService: (p, k) => { var s = new FakeService(p, k); services.Add(s); return s; },
            readKey: _ => "bad-key",
            writeKey: (_, _) => { },
            clock: () => clock.Now,
            sleep: sleeper.SleepAsync,
            dispatch: pump.Dispatcher,
            enableTicker: false);

        session.Begin(SessionMode.Translate);
        captures[0].TriggerStart();
        Assert.True(await pump.WaitUntilAsync(() => services.Count == 1 && session.ConnectionState is ConnectionState.Connected));

        services[0].Disconnect(ServiceErrorClassifier.Classify(Provider.Soniox, closeCode: 4001));
        Assert.True(await pump.WaitUntilAsync(() => session.Stage == SessionStage.Idle));
        Assert.Empty(sleeper.Delays);
        Assert.Contains("认证失败", session.Status);
    }

    [Fact]
    public async Task LiveProviderSwitch_AndCaptureRevoke_HandleRacesCleanly()
    {
        var pump = new TestPump();
        var clock = new FakeClock(700.0);
        var sleeper = new FakeSleeper { HoldNext = true };
        var captures = new List<FakeCapture>();
        var services = new List<FakeService>();

        var session = new Session(
            settingsStore: new MemorySettingsStore(),
            overlay: new FakeOverlay(),
            makeCapture: _ => { var c = new FakeCapture(); captures.Add(c); return c; },
            makeService: (p, k) => { var s = new FakeService(p, k); services.Add(s); return s; },
            readKey: p => p == Provider.Soniox ? "soniox-key" : "gemini-key",
            writeKey: (_, _) => { },
            clock: () => clock.Now,
            sleep: sleeper.SleepAsync,
            dispatch: pump.Dispatcher,
            enableTicker: false);

        session.Begin(SessionMode.Translate);
        captures[0].TriggerStart();
        Assert.True(await pump.WaitUntilAsync(() => services.Count == 1 && session.ConnectionState is ConnectionState.Connected));

        var longText = new string('語', 240);
        services[0].Deliver([Tok(longText, true), Tok("<end>", true), Tok("暂定", false)]);
        await pump.FlushAsync();
        // Switch to Gemini mid-session
        session.SelectProvider(Provider.Gemini);
        Assert.True(await pump.WaitUntilAsync(() => services.Count == 2 && session.ConnectionState is ConnectionState.Connected));
        Assert.True(services[0].Closed);
        Assert.Equal(Provider.Gemini, session.Provider);

        services[1].Deliver([Tok("翻譯", true, true)]);
        await pump.FlushAsync();
        // Trigger reconnect sleep then revoke capture -> must cancel reconnect cleanly
        services[1].Disconnect(new CaptionError("中断", CaptionErrorKind.Transient));
        Assert.True(await pump.WaitUntilAsync(() => session.IsReconnecting));
        int countBeforeRevoke = services.Count;

        captures[0].Revoke();
        Assert.True(await pump.WaitUntilAsync(() => session.Stage == SessionStage.Idle));
        await pump.FlushAsync();
        Assert.Equal(countBeforeRevoke, services.Count);
        Assert.Contains("系统已结束声音采集", session.Status);
        Assert.True(session.HasTranscript);
        Assert.Equal($"日语原文\r\n{longText}\r\n\r\n简体中文译文\r\n翻译\r\n", session.TranscriptText());
    }
}
