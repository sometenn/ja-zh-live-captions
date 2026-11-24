using System.Threading.Channels;

namespace CallCaptions.Core;

public enum Provider
{
    Soniox,
    Gemini
}

public static class ProviderExtensions
{
    public static string DisplayName(this Provider provider) => provider switch
    {
        Provider.Soniox => "Soniox",
        Provider.Gemini => "Gemini",
        _ => provider.ToString()
    };

    public static ICaptionService Connect(this Provider provider, string key) => provider switch
    {
        Provider.Soniox => new SonioxService(key),
        Provider.Gemini => new GeminiService(key),
        _ => throw new ArgumentOutOfRangeException(nameof(provider))
    };
}

public enum AudioSource
{
    System,
    Microphone
}

public static class AudioSourceExtensions
{
    public static string Title(this AudioSource source) => source switch
    {
        AudioSource.System => "机内声音",
        AudioSource.Microphone => "麦克风",
        _ => source.ToString()
    };
}

public interface ICaptionService
{
    Task StartAsync(Action<IReadOnlyList<Token>> receive, Action<Exception> failed, CancellationToken cancellationToken = default);
    Task SendAsync(byte[] data, CancellationToken cancellationToken = default);
    void Close();
}

public interface IAudioCapture
{
    ChannelReader<AudioChunk> Audio { get; }
    Action? Started { get; set; }
    void Present();
    Task StopAsync();
}

public interface IOverlayController
{
    void Prepare();
    void Draw(OverlayFrame frame);
    void Stop();
}
