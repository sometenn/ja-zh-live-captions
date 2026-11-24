using System.Runtime.InteropServices;
using System.Threading.Channels;
using CallCaptions.Core;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace CallCaptions.App.Audio;

public sealed class WindowsAudioCapture : IAudioCapture
{
    private static readonly byte[] SilentFrame20ms = new byte[640]; // 320 samples * 2 bytes (16 kHz mono 16-bit)

    private readonly AudioSource _source;
    private readonly Channel<AudioChunk> _channel = Channel.CreateUnbounded<AudioChunk>(
        new UnboundedChannelOptions { SingleWriter = false, SingleReader = true });
    private readonly PcmResampler _resampler = new();
    private readonly object _gate = new();
    private readonly TaskCompletionSource _stoppedTcs = new(TaskCreationOptions.RunContinuationsAsynchronously);

    private IWaveIn? _capture;
    private System.Threading.Timer? _keepaliveTimer;
    private double _lastDataAt;
    private bool _startedInvoked;
    private bool _stopped;

    public ChannelReader<AudioChunk> Audio => _channel.Reader;
    public Action? Started { get; set; }

    public WindowsAudioCapture(AudioSource source)
    {
        _source = source;
    }

    public void Present()
    {
        try
        {
            using var enumerator = new MMDeviceEnumerator();
            if (_source == AudioSource.System)
            {
                MMDevice device;
                try
                {
                    device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                }
                catch (COMException ex)
                {
                    throw new CaptionError("未找到可用的系统音频输出设备。", CaptionErrorKind.Configuration, ex);
                }

                var loopback = new WasapiLoopbackCapture(device);
                _capture = loopback;
            }
            else
            {
                MMDevice device;
                try
                {
                    device = enumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Communications);
                }
                catch (COMException)
                {
                    try
                    {
                        device = enumerator.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Multimedia);
                    }
                    catch (COMException ex)
                    {
                        throw new CaptionError("未找到可用的麦克风设备，请检查系统隐私和录音设备设置。", CaptionErrorKind.Permission, ex);
                    }
                }

                var mic = new WasapiCapture(device, useEventSync: true, audioBufferMillisecondsLength: 20);
                _capture = mic;
            }

            _capture.DataAvailable += OnDataAvailable;
            _capture.RecordingStopped += OnRecordingStopped;
            _lastDataAt = MonotonicClock.UptimeSeconds;
            _capture.StartRecording();

            _startedInvoked = true;
            Started?.Invoke();

            if (_source == AudioSource.System)
            {
                // WASAPI Loopback stops emitting DataAvailable when playback is silent.
                // Emit a lightweight 20 ms silent frame periodically so status stays accurate
                // ("等待对方声音") and WebSocket sessions stay alive across silence.
                _keepaliveTimer = new System.Threading.Timer(OnKeepaliveTick, null, 150, 100);
            }
        }
        catch (CaptionError)
        {
            CleanupOnStartFailure();
            throw;
        }
        catch (Exception ex)
        {
            CleanupOnStartFailure();
            string message = _source == AudioSource.System
                ? $"无法开启机内声音采集：{ex.Message}"
                : $"无法开启麦克风采集：{ex.Message}";
            throw new CaptionError(message, CaptionErrorKind.Permission, ex);
        }
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        if (_stopped || _capture is null || e.BytesRecorded <= 0) return;

        double now = MonotonicClock.UptimeSeconds;
        AudioChunk? chunk;
        lock (_gate)
        {
            if (_stopped) return;
            _lastDataAt = now;
            chunk = _resampler.Encode(e.Buffer, e.BytesRecorded, _capture.WaveFormat, now);
        }

        if (chunk is not null)
        {
            _channel.Writer.TryWrite(chunk);
        }
    }

    private void OnKeepaliveTick(object? state)
    {
        if (_stopped || !_startedInvoked) return;
        double now = MonotonicClock.UptimeSeconds;
        lock (_gate)
        {
            if (_stopped || now - _lastDataAt < 0.14) return;
            _lastDataAt = now;
        }

        _channel.Writer.TryWrite(new AudioChunk(now, SilentFrame20ms, 0));
    }

    private void OnRecordingStopped(object? sender, StoppedEventArgs e)
    {
        lock (_gate)
        {
            _stopped = true;
        }
        _keepaliveTimer?.Dispose();
        _keepaliveTimer = null;

        if (e.Exception is not null)
        {
            string msg = _source == AudioSource.System
                ? $"系统音频采集中断：{e.Exception.Message}"
                : $"麦克风采集中断：{e.Exception.Message}";
            _channel.Writer.TryComplete(new CaptionError(msg, CaptionErrorKind.General, e.Exception));
        }
        else
        {
            _channel.Writer.TryComplete();
        }

        _stoppedTcs.TrySetResult();
    }

    public async Task StopAsync()
    {
        IWaveIn? capture;
        lock (_gate)
        {
            if (_stopped) return;
            _stopped = true;
            capture = _capture;
        }

        _keepaliveTimer?.Dispose();
        _keepaliveTimer = null;

        if (capture is not null)
        {
            try
            {
                capture.StopRecording();
            }
            catch
            {
                _channel.Writer.TryComplete();
                _stoppedTcs.TrySetResult();
            }

            await Task.WhenAny(_stoppedTcs.Task, Task.Delay(1000)).ConfigureAwait(false);
            capture.DataAvailable -= OnDataAvailable;
            capture.RecordingStopped -= OnRecordingStopped;
            capture.Dispose();
            _capture = null;
        }
        else
        {
            _channel.Writer.TryComplete();
        }
    }

    private void CleanupOnStartFailure()
    {
        lock (_gate)
        {
            _stopped = true;
        }
        _keepaliveTimer?.Dispose();
        _keepaliveTimer = null;
        if (_capture is not null)
        {
            _capture.DataAvailable -= OnDataAvailable;
            _capture.RecordingStopped -= OnRecordingStopped;
            _capture.Dispose();
            _capture = null;
        }
        _channel.Writer.TryComplete();
    }
}
