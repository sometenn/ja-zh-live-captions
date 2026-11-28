using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Serialization;

namespace CallCaptions.Core;

public sealed record Token(
    [property: JsonPropertyName("text")] string Text,
    [property: JsonPropertyName("is_final")] bool IsFinal,
    [property: JsonPropertyName("translation_status")] string? TranslationStatus = null
);

public sealed record Reply(
    [property: JsonPropertyName("tokens")] List<Token>? Tokens,
    [property: JsonPropertyName("finished")] bool? Finished,
    [property: JsonPropertyName("error_code")] int? ErrorCode,
    [property: JsonPropertyName("error_message")] string? ErrorMessage
);

public enum CaptionDisplayMode
{
    Bilingual,
    ChineseOnly
}

public static class CaptionDisplayModeExtensions
{
    public static string Title(this CaptionDisplayMode mode) => mode switch
    {
        CaptionDisplayMode.Bilingual => "双语字幕",
        CaptionDisplayMode.ChineseOnly => "仅大号中文",
        _ => "双语字幕"
    };

    public static string AccessibilityLabel(this CaptionDisplayMode mode) => mode switch
    {
        CaptionDisplayMode.Bilingual => "双语字幕预览",
        CaptionDisplayMode.ChineseOnly => "大号中文字幕预览",
        _ => "双语字幕预览"
    };
}

// Soniox sends confirmed text once and replaces the whole provisional suffix.
public sealed class Captions : IEquatable<Captions>
{
    public const int RecentLimit = 900;
    private const uint LcmapSimplifiedChinese = 0x02000000;

    private string _original = string.Empty;
    private string _translated = string.Empty;

    public string Japanese { get; private set; } = string.Empty;
    public string Chinese { get; private set; } = string.Empty;
    public bool HasCommitted => _original.Length > 0 || _translated.Length > 0;

    public void Apply(IEnumerable<Token> tokens)
    {
        var draftOriginal = new StringBuilder();
        var draftTranslation = new StringBuilder();
        var origSb = new StringBuilder(_original);
        var transSb = new StringBuilder(_translated);

        foreach (var token in tokens)
        {
            if (token.Text is "<end>" or "<fin>")
            {
                continue;
            }

            if (token.TranslationStatus == "translation")
            {
                if (token.IsFinal) transSb.Append(token.Text);
                else draftTranslation.Append(token.Text);
            }
            else
            {
                if (token.IsFinal) origSb.Append(token.Text);
                else draftOriginal.Append(token.Text);
            }
        }

        _original = Suffix(origSb.ToString(), RecentLimit);
        _translated = Suffix(transSb.ToString(), RecentLimit);
        Japanese = Suffix(_original + draftOriginal, RecentLimit);
        Chinese = Simplify(Suffix(_translated + draftTranslation, RecentLimit));
    }

    // Remove unconfirmed provisional suffixes while keeping committed text intact.
    public void ClearProvisional()
    {
        Japanese = _original;
        Chinese = Simplify(_translated);
    }

    public void Reset()
    {
        _original = string.Empty;
        _translated = string.Empty;
        Japanese = string.Empty;
        Chinese = string.Empty;
    }

    public Captions Clone() => new()
    {
        _original = _original,
        _translated = _translated,
        Japanese = Japanese,
        Chinese = Chinese
    };

    public bool Equals(Captions? other)
    {
        if (other is null) return false;
        return _original == other._original
            && _translated == other._translated
            && Japanese == other.Japanese
            && Chinese == other.Chinese;
    }

    public override bool Equals(object? obj) => Equals(obj as Captions);
    public override int GetHashCode() => HashCode.Combine(_original, _translated, Japanese, Chinese);

    private static string Suffix(string value, int maxLength)
    {
        if (value.Length <= maxLength) return value;
        return value.Substring(value.Length - maxLength, maxLength);
    }

    public static string Simplify(string text)
    {
        if (string.IsNullOrEmpty(text)) return text;
        int required = LCMapStringEx("zh-CN", LcmapSimplifiedChinese, text, text.Length, null, 0, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
        if (required <= 0) return text;
        var buffer = new StringBuilder(required);
        int written = LCMapStringEx("zh-CN", LcmapSimplifiedChinese, text, text.Length, buffer, required, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
        return written > 0 ? buffer.ToString(0, written) : text;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int LCMapStringEx(
        string lpLocaleName,
        uint dwMapFlags,
        string lpSrcStr,
        int cchSrc,
        StringBuilder? lpDestStr,
        int cchDest,
        IntPtr lpVersionInformation,
        IntPtr lpReserved,
        IntPtr sortHandle);
}

public enum OverlayBannerTone
{
    Info,
    Warning
}

public sealed record OverlayBanner(string Text, OverlayBannerTone Tone);

public abstract record OverlayStyle
{
    public sealed record Bilingual(string Top, string Bottom) : OverlayStyle;
    public sealed record ChineseOnly(string Text, bool IsPlaceholder) : OverlayStyle;
    public sealed record Probe(string Status, string Meter) : OverlayStyle;
}

public sealed record OverlayFrame(OverlayBanner? Banner, OverlayStyle Style);

public static class CaptionPresentation
{
    public static OverlayFrame IdleFrame(CaptionDisplayMode mode) => mode switch
    {
        CaptionDisplayMode.Bilingual => new OverlayFrame(null, new OverlayStyle.Bilingual("日语原文", "简体中文译文")),
        CaptionDisplayMode.ChineseOnly => new OverlayFrame(null, new OverlayStyle.ChineseOnly("简体中文译文", true)),
        _ => new OverlayFrame(null, new OverlayStyle.Bilingual("日语原文", "简体中文译文"))
    };

    public static OverlayFrame ProbeFrame(string status, int level)
    {
        int clamped = Math.Clamp(level, 0, 10);
        string meter = new string('▰', clamped) + new string('▱', 10 - clamped);
        return new OverlayFrame(null, new OverlayStyle.Probe(status, meter));
    }

    public static OverlayFrame TranslateFrame(
        CaptionDisplayMode displayMode,
        Captions captions,
        string status,
        bool isReconnecting,
        bool isConnecting,
        bool isAudioStalled)
    {
        bool hasJapanese = !string.IsNullOrEmpty(captions.Japanese);
        bool hasChinese = !string.IsNullOrEmpty(captions.Chinese);

        OverlayBanner? warningBanner = null;
        if (isReconnecting || (isAudioStalled && (hasJapanese || hasChinese)))
        {
            warningBanner = new OverlayBanner(status, OverlayBannerTone.Warning);
        }

        switch (displayMode)
        {
            case CaptionDisplayMode.Bilingual:
            {
                if (warningBanner is not null)
                {
                    string top = hasJapanese ? captions.Japanese : string.Empty;
                    string bottom = hasChinese ? captions.Chinese : "等待中文译文…";
                    return new OverlayFrame(warningBanner, new OverlayStyle.Bilingual(top, bottom));
                }

                string idleTop = hasJapanese ? captions.Japanese : status;
                string idleBottom = hasChinese ? captions.Chinese : "等待中文译文…";
                return new OverlayFrame(null, new OverlayStyle.Bilingual(idleTop, idleBottom));
            }
            case CaptionDisplayMode.ChineseOnly:
            {
                if (hasChinese)
                {
                    return new OverlayFrame(warningBanner, new OverlayStyle.ChineseOnly(captions.Chinese, false));
                }

                if (warningBanner is not null)
                {
                    return new OverlayFrame(warningBanner, new OverlayStyle.ChineseOnly("等待中文译文…", true));
                }

                if (isConnecting || isAudioStalled || status != "正在翻译")
                {
                    return new OverlayFrame(
                        new OverlayBanner(status, isAudioStalled ? OverlayBannerTone.Warning : OverlayBannerTone.Info),
                        new OverlayStyle.ChineseOnly("等待中文译文…", true));
                }

                return new OverlayFrame(null, new OverlayStyle.ChineseOnly("等待中文译文…", true));
            }
            default:
                return IdleFrame(displayMode);
        }
    }
}

public static class CaptionTextLayout
{
    private static readonly HashSet<char> SentenceEnders = ['。', '！', '？', '!', '?', '…', '\n'];
    private static readonly HashSet<char> ClauseBreakers = ['，', '、', '；', '：', ',', ';', ':', '—', '–', '・', '/', ' '];
    private static readonly HashSet<char> OpeningDelimiters = ['「', '『', '（', '(', '“', '【', '《'];
    private static readonly HashSet<char> ClosingDelimiters = ['」', '』', '）', ')', '”', '’', '】', '》'];
    private static readonly HashSet<char> DanglingLeading =
    [
        '。', '，', '、', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':',
        '」', '』', '）', ')', '”', '’', '】', '》', ' ', '…'
    ];

    public static string Normalize(string raw)
    {
        if (string.IsNullOrEmpty(raw)) return string.Empty;
        var result = new StringBuilder(raw.Length);
        bool pendingSpace = false;
        foreach (char ch in raw)
        {
            if (char.IsWhiteSpace(ch))
            {
                if (result.Length > 0) pendingSpace = true;
            }
            else
            {
                if (pendingSpace)
                {
                    result.Append(' ');
                    pendingSpace = false;
                }
                result.Append(ch);
            }
        }
        return result.ToString();
    }

    public static string FitRecentPhrase(string raw, Func<string, bool> fits, int maxWindow = 160)
    {
        string normalized = Normalize(raw);
        if (string.IsNullOrEmpty(normalized)) return string.Empty;

        string window = normalized.Length > maxWindow
            ? normalized.Substring(normalized.Length - maxWindow, maxWindow)
            : normalized;

        if (fits(window)) return window;

        int rawStart = LongestFittingStart(window, string.Empty, fits);
        int rawLength = window.Length - rawStart;
        int minPreferredLength = rawLength <= 4
            ? Math.Max(1, rawLength / 2)
            : Math.Max(4, (int)(rawLength * 0.45));

        int CleanStart(int index)
        {
            int idx = index;
            while (idx < window.Length && DanglingLeading.Contains(window[idx]))
            {
                idx++;
            }
            return idx;
        }

        var sentenceStarts = new List<int>();
        var clauseStarts = new List<int>();

        for (int i = 0; i < window.Length; i++)
        {
            char ch = window[i];
            if (SentenceEnders.Contains(ch))
            {
                int next = i + 1;
                while (next < window.Length && ClosingDelimiters.Contains(window[next]))
                {
                    next++;
                }
                int cleaned = CleanStart(next);
                if (cleaned < window.Length && (sentenceStarts.Count == 0 || sentenceStarts[^1] != cleaned))
                {
                    sentenceStarts.Add(cleaned);
                }
            }
            else if (ClauseBreakers.Contains(ch))
            {
                int cleaned = CleanStart(i + 1);
                if (cleaned < window.Length && (clauseStarts.Count == 0 || clauseStarts[^1] != cleaned))
                {
                    clauseStarts.Add(cleaned);
                }
            }
            else if (OpeningDelimiters.Contains(ch) && i > 0)
            {
                if (clauseStarts.Count == 0 || clauseStarts[^1] != i)
                {
                    clauseStarts.Add(i);
                }
            }
        }

        foreach (int start in sentenceStarts)
        {
            if (start < rawStart || window.Length - start < minPreferredLength) continue;
            string candidate = window.Substring(start);
            if (fits(candidate)) return candidate;
        }

        foreach (int start in clauseStarts)
        {
            if (start < rawStart || window.Length - start < minPreferredLength) continue;
            string candidate = window.Substring(start);
            if (fits(candidate)) return candidate;
        }

        int fallbackStart = LongestFittingStart(window, "…", fits);
        fallbackStart = AdjustForAsciiWordBoundary(window, fallbackStart);
        fallbackStart = CleanStart(fallbackStart);
        if (fallbackStart < window.Length)
        {
            string withEllipsis = "…" + window.Substring(fallbackStart);
            if (fits(withEllipsis)) return withEllipsis;
            string plain = window.Substring(fallbackStart);
            if (fits(plain)) return plain;
        }

        int safeStart = Math.Min(rawStart, window.Length - 1);
        return window.Substring(safeStart);
    }

    private static bool IsAsciiWordChar(char ch) =>
        ch is (>= '0' and <= '9') or (>= 'A' and <= 'Z') or (>= 'a' and <= 'z');

    private static int AdjustForAsciiWordBoundary(string chars, int start)
    {
        if (start <= 0 || start >= chars.Length) return start;
        if (IsAsciiWordChar(chars[start - 1]) && IsAsciiWordChar(chars[start]))
        {
            int idx = start;
            while (idx < chars.Length && IsAsciiWordChar(chars[idx]))
            {
                idx++;
            }
            if (chars.Length - idx >= 4) return idx;
        }
        return start;
    }

    private static int LongestFittingStart(string chars, string prefix, Func<string, bool> fits)
    {
        if (chars.Length == 0) return 0;
        int low = 0;
        int high = chars.Length - 1;
        int best = chars.Length - 1;
        while (low <= high)
        {
            int mid = (low + high) / 2;
            string candidate = prefix + chars.Substring(mid);
            if (fits(candidate))
            {
                best = mid;
                high = mid - 1;
            }
            else
            {
                low = mid + 1;
            }
        }
        return best;
    }
}
