using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace CallCaptions.Core;

public enum SessionMode
{
    Probe,
    Translate
}

public enum SessionStage
{
    Idle,
    Picking,
    Listening,
    Stopping
}

public abstract record ConnectionState
{
    public sealed record Disconnected : ConnectionState;
    public sealed record Connecting : ConnectionState;
    public sealed record Connected : ConnectionState;
    public sealed record Reconnecting(int Attempt, int MaxAttempts) : ConnectionState;

    public static readonly ConnectionState DisconnectedInstance = new Disconnected();
    public static readonly ConnectionState ConnectingInstance = new Connecting();
    public static readonly ConnectionState ConnectedInstance = new Connected();
}

public sealed class Session : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    public IOverlayController Overlay { get; }
    public ISettingsStore SettingsStore { get; }
    public AppSettingsData Settings { get; }

    private SessionStage _stage = SessionStage.Idle;
    private SessionMode _mode = SessionMode.Probe;
    private string _status = "准备就绪";
    private int _level;
    private bool _hasKey;
    private AudioSource _source;
    private Provider _provider;
    private CaptionDisplayMode _displayMode;
    private string _switchNotice = string.Empty;
    private ConnectionState _connectionState = ConnectionState.DisconnectedInstance;
    private OverlayFrame _frame;

    public SessionStage Stage
    {
        get => _stage;
        private set => SetField(ref _stage, value);
    }

    public SessionMode Mode
    {
        get => _mode;
        private set => SetField(ref _mode, value);
    }

    public string Status
    {
        get => _status;
        private set => SetField(ref _status, value);
    }

    public int Level
    {
        get => _level;
        private set => SetField(ref _level, value);
    }

    public bool HasKey
    {
        get => _hasKey;
        private set => SetField(ref _hasKey, value);
    }

    public AudioSource Source
    {
        get => _source;
        private set => SetField(ref _source, value);
    }

    public Provider Provider
    {
        get => _provider;
        private set => SetField(ref _provider, value);
    }

    public CaptionDisplayMode DisplayMode
    {
        get => _displayMode;
        private set => SetField(ref _displayMode, value);
    }

    public string SwitchNotice
    {
        get => _switchNotice;
        private set => SetField(ref _switchNotice, value);
    }

    public ConnectionState ConnectionState
    {
        get => _connectionState;
        private set
        {
            if (SetField(ref _connectionState, value))
            {
                OnPropertyChanged(nameof(IsReconnecting));
            }
        }
    }

    public OverlayFrame Frame
    {
        get => _frame;
        private set => SetField(ref _frame, value);
    }

    public bool IsReconnecting => ConnectionState is ConnectionState.Reconnecting;
    public int QueuedAudioCount => _pcmQueue.Count;
    public int QueuedAudioBytes => _pcmQueue.TotalBytes;
    public Captions CurrentCaptions => _captions.Clone();

    private readonly Func<AudioSource, IAudioCapture> _makeCapture;
    private readonly Func<Provider, string, ICaptionService> _makeService;
    private readonly Func<Provider, string> _readKey;
    private readonly Action<string, Provider> _writeKey;
    private readonly Func<double> _clock;
    private readonly Func<double, CancellationToken, Task> _sleep;
    private readonly Action<Action> _dispatch;
    private readonly bool _enableTicker;

    private IAudioCapture? _capture;
    private ICaptionService? _client;
    private string _activeKey = string.Empty;
    private double _acceptAudioSince;
    private ulong _sessionId;
    private ulong _connectionId;
    private CancellationTokenSource? _workerCts;
    private CancellationTokenSource? _tickerCts;
    private CancellationTokenSource? _connectCts;
    private CancellationTokenSource? _drainCts;
    private CancellationTokenSource? _reconnectCts;
    private Task? _drainTask;
    private readonly AudioBufferQueue _pcmQueue;
    private readonly ReconnectPolicy _reconnectPolicy;
    private readonly Captions _captions = new();
    private int _latestPeak;
    private double? _lastSignal;
    private double? _lastBuffer;
    private double _listeningSince;
    private double _lastDraw;

    private double Now => _clock();

    public Session(
        ISettingsStore? settingsStore = null,
        IOverlayController? overlay = null,
        Func<AudioSource, IAudioCapture>? makeCapture = null,
        Func<Provider, string, ICaptionService>? makeService = null,
        Func<Provider, string>? readKey = null,
        Action<string, Provider>? writeKey = null,
        Func<double>? clock = null,
        Func<double, CancellationToken, Task>? sleep = null,
        Action<Action>? dispatch = null,
        int queueMaxBytes = AudioBufferQueue.DefaultMaxBytes,
        double queueMaxAge = AudioBufferQueue.DefaultMaxAge,
        ReconnectPolicy? reconnectPolicy = null,
        bool enableTicker = true)
    {
        SettingsStore = settingsStore ?? new JsonSettingsStore();
        Settings = SettingsStore.Load();
        Overlay = overlay ?? new NullOverlayController();
        _makeCapture = makeCapture ?? (_ => throw new InvalidOperationException("未配置音频采集工厂。"));
        _makeService = makeService ?? ((provider, key) => provider.Connect(key));

        var defaultVault = new DpapiVault();
        _readKey = readKey ?? defaultVault.Read;
        _writeKey = writeKey ?? defaultVault.Save;
        _clock = clock ?? (() => MonotonicClock.UptimeSeconds);
        _sleep = sleep ?? ((seconds, ct) =>
        {
            int ms = Math.Max(0, (int)Math.Round(seconds * 1000.0));
            return Task.Delay(ms, ct);
        });

        if (dispatch is not null)
        {
            _dispatch = dispatch;
        }
        else if (SynchronizationContext.Current is { } syncCtx)
        {
            _dispatch = action => syncCtx.Post(_ => action(), null);
        }
        else
        {
            var scheduler = new ConcurrentExclusiveSchedulerPair().ExclusiveScheduler;
            _dispatch = action => Task.Factory.StartNew(action, CancellationToken.None, TaskCreationOptions.None, scheduler);
        }

        _pcmQueue = new AudioBufferQueue(queueMaxBytes, queueMaxAge);
        _reconnectPolicy = reconnectPolicy ?? new ReconnectPolicy();
        _enableTicker = enableTicker;

        _provider = Settings.Provider;
        _source = Settings.Source;
        _displayMode = Settings.DisplayMode;
        _frame = CaptionPresentation.IdleFrame(_displayMode);

        try
        {
            _hasKey = !string.IsNullOrEmpty(_readKey(_provider));
        }
        catch (Exception ex)
        {
            _status = ex.Message;
        }

        Overlay.Draw(_frame);
    }

    public void SelectSource(AudioSource next)
    {
        if (Stage != SessionStage.Idle) return;
        Source = next;
        Settings.Source = next;
        SettingsStore.Save(Settings);
    }

    public void SelectDisplayMode(CaptionDisplayMode next)
    {
        if (next == DisplayMode || Stage == SessionStage.Stopping) return;
        DisplayMode = next;
        Settings.DisplayMode = next;
        SettingsStore.Save(Settings);

        if (Stage == SessionStage.Listening)
        {
            Refresh(force: true);
        }
        else
        {
            DrawIdlePreview();
        }
    }

    public void RefreshPreviewIfIdle()
    {
        if (Stage != SessionStage.Idle) return;
        DrawIdlePreview();
    }

    public void SaveSettings() => SettingsStore.Save(Settings);

    public void SelectProvider(Provider next)
    {
        if (next == Provider || Stage is not (SessionStage.Idle or SessionStage.Listening)) return;

        try
        {
            string key = _readKey(next);
            if (Stage == SessionStage.Listening && Mode == SessionMode.Translate)
            {
                if (string.IsNullOrEmpty(key))
                {
                    SwitchNotice = $"请先结束收音，为 {next.DisplayName()} 设置密钥。当前服务仍为 {Provider.DisplayName()}。";
                    return;
                }

                Provider = next;
                HasKey = true;
                SwitchNotice = string.Empty;
                Settings.Provider = next;
                SettingsStore.Save(Settings);

                _activeKey = key;
                _acceptAudioSince = Now;
                ResetProviderConnection();
                _pcmQueue.Clear();
                _captions.Reset();
                _reconnectPolicy.Reset();
                _lastSignal = null;

                _connectionId++;
                ulong nextCid = _connectionId;
                ConnectionState = ConnectionState.ConnectingInstance;
                Refresh(force: true);
                StartConnection(_sessionId, nextCid, isReconnect: false);
                return;
            }

            Provider = next;
            HasKey = !string.IsNullOrEmpty(key);
            SwitchNotice = string.Empty;
            Settings.Provider = next;
            SettingsStore.Save(Settings);

            if (Stage == SessionStage.Listening)
            {
                Refresh(force: true);
            }
        }
        catch (Exception ex)
        {
            SwitchNotice = ex.Message;
        }
    }

    public void SaveKey(string value)
    {
        string trimmed = (value ?? string.Empty).Trim();
        _writeKey(trimmed, Provider);
        HasKey = !string.IsNullOrEmpty(trimmed);
    }

    public void Begin(SessionMode requested)
    {
        if (Stage != SessionStage.Idle) return;

        string key = string.Empty;
        if (requested == SessionMode.Translate)
        {
            try
            {
                key = _readKey(Provider);
            }
            catch (Exception ex)
            {
                Status = ex.Message;
                return;
            }

            if (string.IsNullOrEmpty(key))
            {
                Status = $"请先填写 {Provider.DisplayName()} API 密钥。";
                return;
            }
        }

        _sessionId++;
        ulong sid = _sessionId;
        _connectionId++;
        _activeKey = key;
        _acceptAudioSince = Now;
        SwitchNotice = string.Empty;
        Mode = requested;
        _captions.Reset();
        _pcmQueue.Clear();
        _reconnectPolicy.Reset();
        ConnectionState = ConnectionState.DisconnectedInstance;
        _latestPeak = 0;
        Level = 0;
        _lastSignal = null;
        _lastBuffer = null;
        Stage = SessionStage.Picking;
        Status = Source == AudioSource.System ? "正在开启机内声音" : "正在开启麦克风";

        IAudioCapture input = _makeCapture(Source);
        _capture = input;

        input.Started = () => _dispatch(() =>
        {
            if (_sessionId != sid || !ReferenceEquals(_capture, input) || Stage != SessionStage.Picking) return;

            Stage = SessionStage.Listening;
            _listeningSince = Now;
            if (Source == AudioSource.System) Overlay.Prepare();

            if (requested == SessionMode.Translate)
            {
                _connectionId++;
                ulong cid = _connectionId;
                ConnectionState = ConnectionState.ConnectingInstance;
                StartConnection(sid, cid, isReconnect: false);
            }

            Refresh(force: true);

            if (_enableTicker)
            {
                _tickerCts?.Cancel();
                var tCts = new CancellationTokenSource();
                _tickerCts = tCts;
                _ = Task.Run(async () =>
                {
                    try
                    {
                        while (!tCts.IsCancellationRequested)
                        {
                            await Task.Delay(160, tCts.Token).ConfigureAwait(false);
                            if (!tCts.IsCancellationRequested)
                            {
                                _dispatch(() =>
                                {
                                    if (!tCts.IsCancellationRequested && _sessionId == sid) Refresh();
                                });
                            }
                        }
                    }
                    catch (OperationCanceledException)
                    {
                    }
                });
            }
        });

        _workerCts?.Cancel();
        var wCts = new CancellationTokenSource();
        _workerCts = wCts;

        _ = Task.Run(async () =>
        {
            try
            {
                await foreach (var chunk in input.Audio.ReadAllAsync(wCts.Token).ConfigureAwait(false))
                {
                    wCts.Token.ThrowIfCancellationRequested();
                    _dispatch(() =>
                    {
                        if (wCts.IsCancellationRequested || _sessionId != sid || !ReferenceEquals(_capture, input) || Stage == SessionStage.Stopping)
                            return;

                        if (chunk.CapturedAt < _acceptAudioSince) return;

                        _lastBuffer = Now;
                        _latestPeak = Math.Max(_latestPeak, chunk.Peak);
                        if (chunk.Peak >= 32) _lastSignal = Now;

                        if (requested == SessionMode.Translate)
                        {
                            _pcmQueue.Enqueue(chunk, Now);
                            if (Stage == SessionStage.Listening && ConnectionState is ConnectionState.Connected)
                            {
                                ScheduleDrain();
                            }
                        }
                    });
                }

                if (!wCts.IsCancellationRequested)
                {
                    _dispatch(() =>
                    {
                        if (!wCts.IsCancellationRequested && _sessionId == sid && ReferenceEquals(_capture, input)
                            && Stage is not (SessionStage.Stopping or SessionStage.Idle))
                        {
                            Finish(Source == AudioSource.System ? "声音采集已结束。" : "麦克风收音已结束。");
                        }
                    });
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                if (!wCts.IsCancellationRequested)
                {
                    _dispatch(() =>
                    {
                        if (!wCts.IsCancellationRequested && _sessionId == sid && ReferenceEquals(_capture, input))
                        {
                            Finish(ex.Message);
                        }
                    });
                }
            }
        });

        try
        {
            input.Present();
        }
        catch (Exception ex)
        {
            Finish(ex.Message);
        }
    }

    public void Tick(bool force = false) => Refresh(force);

    private void StartConnection(ulong sid, ulong cid, bool isReconnect)
    {
        if (_sessionId != sid || _connectionId != cid || Stage != SessionStage.Listening || Mode != SessionMode.Translate)
            return;

        ICaptionService service = _makeService(Provider, _activeKey);
        _client = service;
        if (!isReconnect)
        {
            ConnectionState = ConnectionState.ConnectingInstance;
        }

        _connectCts?.Cancel();
        var cCts = new CancellationTokenSource();
        _connectCts = cCts;

        _ = Task.Run(async () =>
        {
            try
            {
                await service.StartAsync(
                    receive: tokens => _dispatch(() =>
                    {
                        if (_sessionId != sid || _connectionId != cid || !ReferenceEquals(_client, service) || Stage != SessionStage.Listening)
                            return;
                        _captions.Apply(tokens);
                        Refresh();
                    }),
                    failed: error => _dispatch(() =>
                    {
                        if (_sessionId != sid || _connectionId != cid || !ReferenceEquals(_client, service))
                            return;
                        HandleConnectionFailure(error, cid, sid);
                    }),
                    cancellationToken: cCts.Token).ConfigureAwait(false);

                _dispatch(() =>
                {
                    if (cCts.IsCancellationRequested || _sessionId != sid || _connectionId != cid
                        || !ReferenceEquals(_client, service) || Stage != SessionStage.Listening)
                    {
                        service.Close();
                        return;
                    }

                    _connectCts = null;
                    ConnectionState = ConnectionState.ConnectedInstance;
                    _reconnectPolicy.MarkConnected(Now);
                    Refresh(force: true);
                    ScheduleDrain();
                });
            }
            catch (OperationCanceledException)
            {
                service.Close();
            }
            catch (Exception ex)
            {
                _dispatch(() =>
                {
                    if (cCts.IsCancellationRequested) return;
                    if (_sessionId != sid || _connectionId != cid || !ReferenceEquals(_client, service)) return;
                    _connectCts = null;
                    HandleConnectionFailure(ex, cid, sid);
                });
            }
        });
    }

    private void ScheduleDrain()
    {
        if (Stage != SessionStage.Listening
            || Mode != SessionMode.Translate
            || ConnectionState is not ConnectionState.Connected
            || _drainTask is not null
            || _pcmQueue.IsEmpty
            || _client is not { } service)
        {
            return;
        }

        ulong sid = _sessionId;
        ulong cid = _connectionId;
        var dCts = new CancellationTokenSource();
        _drainCts = dCts;

        _drainTask = Task.Run(async () =>
        {
            try
            {
                while (!dCts.IsCancellationRequested)
                {
                    var tcs = new TaskCompletionSource<AudioChunk?>(TaskCreationOptions.RunContinuationsAsynchronously);
                    _dispatch(() =>
                    {
                        if (_sessionId == sid && _connectionId == cid
                            && Stage == SessionStage.Listening && Mode == SessionMode.Translate
                            && ConnectionState is ConnectionState.Connected
                            && ReferenceEquals(_client, service))
                        {
                            tcs.TrySetResult(_pcmQueue.Dequeue(Now));
                        }
                        else
                        {
                            tcs.TrySetResult(null);
                        }
                    });

                    AudioChunk? chunk = await tcs.Task.ConfigureAwait(false);
                    if (chunk is null) break;

                    await service.SendAsync(chunk.Data, dCts.Token).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                if (!dCts.IsCancellationRequested)
                {
                    _dispatch(() => HandleConnectionFailure(ex, cid, sid));
                }
            }
            finally
            {
                _dispatch(() =>
                {
                    if (_sessionId == sid && _connectionId == cid)
                    {
                        _drainTask = null;
                        if (Stage == SessionStage.Listening && Mode == SessionMode.Translate
                            && ConnectionState is ConnectionState.Connected && !_pcmQueue.IsEmpty)
                        {
                            ScheduleDrain();
                        }
                    }
                });
            }
        });
    }

    private void HandleConnectionFailure(Exception error, ulong cid, ulong sid)
    {
        if (_sessionId != sid || _connectionId != cid || Stage != SessionStage.Listening || Mode != SessionMode.Translate)
            return;

        _connectionId++;
        ulong nextCid = _connectionId;
        ResetProviderConnection();

        bool retryable = error is CaptionError ce
            ? ce.IsRetryable
            : error is HttpRequestException or TimeoutException;

        if (!retryable)
        {
            Finish(error.Message);
            return;
        }

        _captions.ClearProvisional();

        ReconnectDecision? decision = _reconnectPolicy.RecordTransientFailure(Now);
        if (decision is null)
        {
            Finish($"重连失败（已达上限）：{error.Message}");
            return;
        }

        ConnectionState = new ConnectionState.Reconnecting(decision.Attempt, decision.MaxAttempts);
        Refresh(force: true);

        var rCts = new CancellationTokenSource();
        _reconnectCts = rCts;

        _ = Task.Run(async () =>
        {
            try
            {
                if (decision.Delay > 0)
                {
                    await _sleep(decision.Delay, rCts.Token).ConfigureAwait(false);
                }
                rCts.Token.ThrowIfCancellationRequested();
            }
            catch (OperationCanceledException)
            {
                return;
            }

            _dispatch(() =>
            {
                if (rCts.IsCancellationRequested || _sessionId != sid || _connectionId != nextCid
                    || Stage != SessionStage.Listening || Mode != SessionMode.Translate)
                    return;
                _reconnectCts = null;
                StartConnection(sid, nextCid, isReconnect: true);
            });
        });
    }

    private void ResetProviderConnection()
    {
        _connectCts?.Cancel();
        _connectCts = null;
        _drainCts?.Cancel();
        _drainCts = null;
        _drainTask = null;
        _reconnectCts?.Cancel();
        _reconnectCts = null;
        _client?.Close();
        _client = null;
    }

    private void Refresh(bool force = false)
    {
        if (Stage != SessionStage.Listening) return;

        double timestamp = Now;
        double sampleAge = timestamp - (_lastBuffer ?? _listeningSince);
        bool audioStalled = sampleAge > 4.0;
        bool quiet = !_lastSignal.HasValue || (timestamp - _lastSignal.Value > 3.0);

        switch (Mode)
        {
            case SessionMode.Probe:
                if (audioStalled) Status = Source == AudioSource.System ? "尚未收到系统音频帧" : "尚未收到麦克风声音";
                else if (quiet) Status = Source == AudioSource.System ? "等待对方声音" : "等待麦克风声音";
                else Status = Source == AudioSource.System ? "已检测到机内声音" : "已检测到麦克风声音";
                break;

            case SessionMode.Translate:
                switch (ConnectionState)
                {
                    case ConnectionState.Reconnecting rec:
                        Status = $"连接中断，正在重连 {Provider.DisplayName()}（{rec.Attempt}/{rec.MaxAttempts}）";
                        break;
                    case ConnectionState.Connecting:
                        Status = $"正在连接 {Provider.DisplayName()}";
                        break;
                    case ConnectionState.Connected:
                        _reconnectPolicy.NoteHealthy(timestamp);
                        if (audioStalled) Status = Source == AudioSource.System ? "尚未收到系统音频帧" : "尚未收到麦克风声音";
                        else if (quiet) Status = Source == AudioSource.System ? "等待对方声音" : "等待麦克风声音";
                        else Status = "正在翻译";
                        break;
                    case ConnectionState.Disconnected:
                        Status = "准备翻译";
                        break;
                }
                break;
        }

        int measured = _latestPeak > 0
            ? Math.Clamp((int)((20.0 * Math.Log10(_latestPeak / 32768.0) + 60.0) / 6.0) + 1, 0, 10)
            : 0;
        Level = Math.Max(measured, Level - 1);
        _latestPeak = 0;

        OverlayFrame nextFrame = Mode == SessionMode.Probe
            ? CaptionPresentation.ProbeFrame(Status, Level)
            : CaptionPresentation.TranslateFrame(
                DisplayMode,
                _captions,
                Status,
                IsReconnecting,
                ConnectionState is ConnectionState.Connecting,
                audioStalled);

        bool changed = !Equals(nextFrame, Frame);
        if (changed) Frame = nextFrame;
        if (changed || force || timestamp - _lastDraw >= 1.0)
        {
            Overlay.Draw(nextFrame);
            _lastDraw = timestamp;
        }
    }

    private void DrawIdlePreview()
    {
        var idle = CaptionPresentation.IdleFrame(DisplayMode);
        Frame = idle;
        Overlay.Draw(idle);
    }

    public void Finish(string message = "已结束")
    {
        if (Stage is SessionStage.Idle or SessionStage.Stopping) return;

        Stage = SessionStage.Stopping;
        Status = Source == AudioSource.System ? "正在结束收音" : "正在结束收音";
        _sessionId++;
        ulong finishSessionId = _sessionId;
        _connectionId++;

        _tickerCts?.Cancel();
        _tickerCts = null;
        _workerCts?.Cancel();
        _workerCts = null;

        ResetProviderConnection();
        _pcmQueue.Clear();
        _reconnectPolicy.Reset();
        ConnectionState = ConnectionState.DisconnectedInstance;
        _activeKey = string.Empty;
        SwitchNotice = string.Empty;
        Overlay.Stop();
        _captions.Reset();
        _latestPeak = 0;
        Level = 0;
        _lastSignal = null;
        _lastBuffer = null;
        DrawIdlePreview();

        IAudioCapture? input = _capture;
        _ = Task.Run(async () =>
        {
            if (input is not null)
            {
                try
                {
                    await input.StopAsync().ConfigureAwait(false);
                }
                catch
                {
                    // Ignore stop errors.
                }
            }

            _dispatch(() =>
            {
                if (_sessionId != finishSessionId) return;
                _capture = null;
                Status = message;
                Stage = SessionStage.Idle;
                DrawIdlePreview();
            });
        });
    }

    private bool SetField<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        OnPropertyChanged(propertyName);
        return true;
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));

    private sealed class NullOverlayController : IOverlayController
    {
        public void Prepare() { }
        public void Draw(OverlayFrame frame) { }
        public void Stop() { }
    }
}
