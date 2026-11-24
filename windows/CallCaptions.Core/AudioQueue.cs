using System.Diagnostics;

namespace CallCaptions.Core;

public static class MonotonicClock
{
    public static double UptimeSeconds => Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency;
}

public sealed record AudioChunk(double CapturedAt, byte[] Data, int Peak)
{
    public AudioChunk(byte[] data, int peak)
        : this(MonotonicClock.UptimeSeconds, data, peak)
    {
    }
}

// Bounded FIFO PCM buffer enforcing both maximum age and maximum byte capacity.
public sealed class AudioBufferQueue
{
    public const int DefaultMaxBytes = 160_000; // 5.0 seconds of 16 kHz, 16-bit mono PCM
    public const double DefaultMaxAge = 5.0;

    public int MaxBytes { get; }
    public double MaxAge { get; }

    private readonly List<AudioChunk> _storage = new();
    private int _head;

    public int TotalBytes { get; private set; }
    public int DroppedCount { get; private set; }
    public int DroppedBytes { get; private set; }

    public int Count => _storage.Count - _head;
    public bool IsEmpty => Count == 0;

    public AudioBufferQueue(int maxBytes = DefaultMaxBytes, double maxAge = DefaultMaxAge)
    {
        MaxBytes = Math.Max(0, maxBytes);
        MaxAge = Math.Max(0, maxAge);
    }

    public void PruneExpired(double now)
    {
        while (_head < _storage.Count && now - _storage[_head].CapturedAt > MaxAge)
        {
            var evicted = _storage[_head];
            _head++;
            TotalBytes -= evicted.Data.Length;
            DroppedCount++;
            DroppedBytes += evicted.Data.Length;
        }
        CompactIfNeeded();
    }

    public void Enqueue(AudioChunk chunk, double now)
    {
        if (chunk.Data.Length == 0) return;

        PruneExpired(now);
        if (now - chunk.CapturedAt > MaxAge || chunk.Data.Length > MaxBytes)
        {
            DroppedCount++;
            DroppedBytes += chunk.Data.Length;
            return;
        }

        while (_head < _storage.Count && TotalBytes + chunk.Data.Length > MaxBytes)
        {
            var evicted = _storage[_head];
            _head++;
            TotalBytes -= evicted.Data.Length;
            DroppedCount++;
            DroppedBytes += evicted.Data.Length;
        }

        CompactIfNeeded();
        _storage.Add(chunk);
        TotalBytes += chunk.Data.Length;
    }

    public AudioChunk? Dequeue(double now)
    {
        PruneExpired(now);
        if (_head >= _storage.Count) return null;

        var next = _storage[_head];
        _head++;
        TotalBytes -= next.Data.Length;
        CompactIfNeeded();
        return next;
    }

    public void Clear()
    {
        _storage.Clear();
        _head = 0;
        TotalBytes = 0;
    }

    private void CompactIfNeeded()
    {
        if (_head == _storage.Count)
        {
            _storage.Clear();
            _head = 0;
        }
        else if (_head >= 64 && _head * 2 >= _storage.Count)
        {
            _storage.RemoveRange(0, _head);
            _head = 0;
        }
    }
}
