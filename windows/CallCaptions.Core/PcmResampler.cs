using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace CallCaptions.Core;

// Converts arbitrary PCM / IEEE float audio streams into 16 kHz, 16-bit mono PCM chunks.
// Keep one instance per capture session so the resampler remains stateful across buffers.
public sealed class PcmResampler
{
    public const int TargetSampleRate = 16000;
    public const int TargetChannels = 1;

    private WaveFormat? _inputFormat;
    private BufferedWaveProvider? _inputBuffer;
    private ISampleProvider? _pipeline;
    private float[] _sampleScratch = new float[2048];

    public AudioChunk? Encode(byte[] buffer, int bytesRecorded, WaveFormat format, double? capturedAt = null)
    {
        if (bytesRecorded <= 0 || format.SampleRate <= 0 || format.Channels <= 0)
        {
            return null;
        }

        EnsurePipeline(format);
        _inputBuffer!.AddSamples(buffer, 0, bytesRecorded);

        int estimatedSamples = (int)Math.Ceiling(bytesRecorded / (double)Math.Max(1, format.BlockAlign)
            * TargetSampleRate / format.SampleRate) + 64;
        if (_sampleScratch.Length < estimatedSamples)
        {
            _sampleScratch = new float[estimatedSamples];
        }

        int totalSamples = 0;
        using var pcmStream = new MemoryStream();
        int peak = 0;

        while (true)
        {
            int read = _pipeline!.Read(_sampleScratch, 0, _sampleScratch.Length);
            if (read <= 0) break;

            totalSamples += read;
            for (int i = 0; i < read; i++)
            {
                float clamped = Math.Clamp(_sampleScratch[i], -1.0f, 1.0f);
                int sample16 = (int)Math.Round(clamped * 32767.0f);
                short s = (short)Math.Clamp(sample16, short.MinValue, short.MaxValue);
                int abs = s == short.MinValue ? 32768 : Math.Abs((int)s);
                if (abs > peak) peak = abs;

                pcmStream.WriteByte((byte)(s & 0xFF));
                pcmStream.WriteByte((byte)((s >> 8) & 0xFF));
            }
        }

        if (totalSamples == 0) return null;

        byte[] data = pcmStream.ToArray();
        return capturedAt.HasValue
            ? new AudioChunk(capturedAt.Value, data, peak)
            : new AudioChunk(data, peak);
    }

    private void EnsurePipeline(WaveFormat format)
    {
        if (_inputFormat is not null && FormatsEqual(_inputFormat, format))
        {
            return;
        }

        _inputFormat = format;
        _inputBuffer = new BufferedWaveProvider(format)
        {
            DiscardOnBufferOverflow = true,
            ReadFully = false
        };

        ISampleProvider samples = _inputBuffer.ToSampleProvider();
        if (format.Channels > 1)
        {
            samples = new DownmixToMonoSampleProvider(samples);
        }

        if (format.SampleRate != TargetSampleRate)
        {
            samples = new WdlResamplingSampleProvider(samples, TargetSampleRate);
        }

        _pipeline = samples;
    }

    private static bool FormatsEqual(WaveFormat a, WaveFormat b) =>
        a.Encoding == b.Encoding
        && a.SampleRate == b.SampleRate
        && a.Channels == b.Channels
        && a.BitsPerSample == b.BitsPerSample;

    private sealed class DownmixToMonoSampleProvider : ISampleProvider
    {
        private readonly ISampleProvider _source;
        private readonly int _channels;
        private float[] _sourceBuffer = new float[2048];

        public WaveFormat WaveFormat { get; }

        public DownmixToMonoSampleProvider(ISampleProvider source)
        {
            _source = source;
            _channels = source.WaveFormat.Channels;
            WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(source.WaveFormat.SampleRate, 1);
        }

        public int Read(float[] buffer, int offset, int count)
        {
            int needed = count * _channels;
            if (_sourceBuffer.Length < needed)
            {
                _sourceBuffer = new float[needed];
            }

            int read = _source.Read(_sourceBuffer, 0, needed);
            int frames = read / _channels;
            for (int f = 0; f < frames; f++)
            {
                float sum = 0;
                int baseIdx = f * _channels;
                for (int ch = 0; ch < _channels; ch++)
                {
                    sum += _sourceBuffer[baseIdx + ch];
                }
                buffer[offset + f] = sum / _channels;
            }
            return frames;
        }
    }
}
