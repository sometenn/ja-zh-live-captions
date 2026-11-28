using System.Text.Json;
using System.Text.Json.Serialization;

namespace CallCaptions.Core;

public enum CaptionInk
{
    White,
    Mint,
    Amber
}

public static class CaptionInkExtensions
{
    public static string Title(this CaptionInk ink) => ink switch
    {
        CaptionInk.White => "白色",
        CaptionInk.Mint => "薄荷",
        CaptionInk.Amber => "暖黄",
        _ => "白色"
    };
}

public sealed class CaptionAppearanceSettings
{
    public string FontFamily { get; set; } = string.Empty;
    public double SourceSize { get; set; } = 18.0;
    public double TranslationSize { get; set; } = 30.0;
    public CaptionInk Ink { get; set; } = CaptionInk.White;
    public int MainMaxLines { get; set; } = 4;
    public int MaxLines { get; set; } = 1;
    public double Opacity { get; set; } = 92.0;

    public void RestoreDefaults()
    {
        FontFamily = string.Empty;
        SourceSize = 18.0;
        TranslationSize = 30.0;
        Ink = CaptionInk.White;
        MainMaxLines = 4;
        MaxLines = 1;
        Opacity = 92.0;
    }
}

public sealed class OverlayBounds
{
    public double? Left { get; set; }
    public double? Top { get; set; }
    public double Width { get; set; } = 620;
    public double Height { get; set; } = 170;
}

public sealed class AppSettingsData
{
    public Provider Provider { get; set; } = Provider.Soniox;
    public AudioSource Source { get; set; } = AudioSource.System;
    public CaptionDisplayMode DisplayMode { get; set; } = CaptionDisplayMode.Bilingual;
    public CaptionAppearanceSettings Appearance { get; set; } = new();
    public OverlayBounds OverlayBounds { get; set; } = new();
}

public interface ISettingsStore
{
    AppSettingsData Load();
    void Save(AppSettingsData data);
}

public sealed class JsonSettingsStore : ISettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() }
    };

    private readonly string _filePath;

    public JsonSettingsStore(string? directory = null)
    {
        string dir = directory ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CallCaptions");
        _filePath = Path.Combine(dir, "settings.json");
    }

    public AppSettingsData Load()
    {
        try
        {
            if (!File.Exists(_filePath)) return new AppSettingsData();
            string json = File.ReadAllText(_filePath);
            return JsonSerializer.Deserialize<AppSettingsData>(json, JsonOptions) ?? new AppSettingsData();
        }
        catch
        {
            return new AppSettingsData();
        }
    }

    public void Save(AppSettingsData data)
    {
        try
        {
            string? dir = Path.GetDirectoryName(_filePath);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            string json = JsonSerializer.Serialize(data, JsonOptions);
            File.WriteAllText(_filePath, json);
        }
        catch
        {
            // Ignore settings persistence failures.
        }
    }
}
