namespace CallCaptions.Core;

public enum CaptionErrorKind
{
    General,
    Transient,
    Authentication,
    Permission,
    Quota,
    Configuration
}

public sealed class CaptionError : Exception
{
    public CaptionErrorKind Kind { get; }
    public bool IsRetryable => Kind == CaptionErrorKind.Transient;

    public CaptionError(string message, CaptionErrorKind kind = CaptionErrorKind.General, Exception? innerException = null)
        : base(message, innerException)
    {
        Kind = kind;
    }
}

public static class ServiceErrorClassifier
{
    private static readonly string[] QuotaKeywords =
    [
        "quota", "billing", "balance", "credit", "insufficient", "payment",
        "rate limit", "rate_limit", "ratelimit", "resource_exhausted",
        "too many requests", "limit exceeded", "quota exceeded"
    ];

    private static readonly string[] AuthKeywords =
    [
        "api key", "api_key", "apikey", "unauthenticated", "unauthorized",
        "invalid key", "invalid_api_key", "invalid token", "authentication",
        "auth failed", "expired key", "bad credentials"
    ];

    private static readonly string[] PermissionKeywords =
    [
        "permission", "forbidden", "not allowed", "access denied", "denied",
        "allowlist", "whitelist", "not enabled", "suspended", "revoked",
        "not authorized", "unsupported location", "region"
    ];

    public static CaptionError Classify(
        Provider provider,
        int? httpStatus = null,
        int? closeCode = null,
        string? closeReason = null,
        int? errorCode = null,
        string? errorStatus = null,
        string? errorMessage = null)
    {
        string reason = string.Join(" ",
            new[] { errorMessage, closeReason }
                .Select(s => s?.Trim())
                .Where(s => !string.IsNullOrEmpty(s)))
            .ToLowerInvariant();

        if (httpStatus is >= 400)
        {
            string tag = $"（HTTP {httpStatus}）";
            switch (httpStatus.Value)
            {
                case 401:
                    return AuthError(provider, tag);
                case 402 or 429:
                    return QuotaError(provider, tag);
                case 403:
                    return MatchesQuota(reason) ? QuotaError(provider, tag) : PermissionError(provider, tag);
                case 408 or (>= 500 and <= 599):
                    return TransientError(provider, tag);
                default:
                    return ClassifyByReason(provider, reason, tag) ?? ConfigError(provider, tag);
            }
        }

        string? normalizedStatus = string.IsNullOrWhiteSpace(errorStatus)
            ? null
            : errorStatus.Trim().ToUpperInvariant();

        if (errorCode.HasValue || normalizedStatus is not null)
        {
            string tag = errorCode.HasValue
                ? $"（{errorCode.Value}）"
                : (normalizedStatus is not null ? $"（{normalizedStatus}）" : string.Empty);

            if (normalizedStatus == "UNAUTHENTICATED" || errorCode is 401 or 16)
                return AuthError(provider, tag);

            if (normalizedStatus == "RESOURCE_EXHAUSTED" || errorCode is 402 or 429 or 8)
                return QuotaError(provider, tag);

            if (normalizedStatus == "PERMISSION_DENIED" || errorCode is 403 or 7)
                return MatchesQuota(reason) ? QuotaError(provider, tag) : PermissionError(provider, tag);

            bool isTransientRpc = normalizedStatus is "UNAVAILABLE" or "DEADLINE_EXCEEDED" or "INTERNAL" or "ABORTED";
            bool isTransientCode = errorCode is 408 or (>= 500 and <= 599) or 4 or 13 or 14;
            if (isTransientRpc || isTransientCode)
                return ClassifyByReason(provider, reason, tag) ?? TransientError(provider, tag);

            return ClassifyByReason(provider, reason, tag) ?? ConfigError(provider, tag);
        }

        if (closeCode is not null and not 0)
        {
            int code = closeCode.Value;
            string tag = $"（WS {code}）";
            switch (code)
            {
                case 1008:
                    return ClassifyByReason(provider, reason, tag) ?? PermissionError(provider, tag);
                case 4001 or 4401:
                    return AuthError(provider, tag);
                case 4003 or 4403:
                    return MatchesQuota(reason) ? QuotaError(provider, tag) : PermissionError(provider, tag);
                case 4002 or 4029 or 4402 or 4429:
                    return QuotaError(provider, tag);
                case 1002 or 1003 or 1007 or 1009 or 1010 or 4000 or 4004 or 4400 or 4404:
                    return ClassifyByReason(provider, reason, tag) ?? ConfigError(provider, tag);
                case 1000 or 1001 or 1005 or 1006 or 1011 or 1012 or 1013 or 1014 or 1015 or 4008 or (>= 4500 and <= 4599):
                    return ClassifyByReason(provider, reason, tag) ?? TransientError(provider, tag);
                case >= 4000 and <= 4999:
                    return ClassifyByReason(provider, reason, tag) ?? ConfigError(provider, tag);
                default:
                    return ClassifyByReason(provider, reason, tag) ?? TransientError(provider, tag);
            }
        }

        return ClassifyByReason(provider, reason, string.Empty) ?? TransientError(provider, string.Empty);
    }

    private static CaptionError? ClassifyByReason(Provider provider, string reason, string tag)
    {
        if (string.IsNullOrEmpty(reason)) return null;
        if (MatchesQuota(reason)) return QuotaError(provider, tag);
        if (MatchesAuth(reason)) return AuthError(provider, tag);
        if (MatchesPermission(reason)) return PermissionError(provider, tag);
        return null;
    }

    private static bool MatchesQuota(string reason) => QuotaKeywords.Any(reason.Contains);
    private static bool MatchesAuth(string reason) => AuthKeywords.Any(reason.Contains);
    private static bool MatchesPermission(string reason) => PermissionKeywords.Any(reason.Contains);

    private static CaptionError AuthError(Provider provider, string tag) =>
        new($"{provider.DisplayName()} 认证失败{tag}。请在「密钥」中检查 API 密钥是否正确或已过期。", CaptionErrorKind.Authentication);

    private static CaptionError PermissionError(Provider provider, string tag) => provider switch
    {
        Provider.Soniox => new($"Soniox 权限不足{tag}。请检查 API 密钥和账户权限。", CaptionErrorKind.Permission),
        Provider.Gemini => new($"Gemini 权限不足{tag}。请检查 API 密钥和 Live Translate 模型权限。", CaptionErrorKind.Permission),
        _ => new($"权限不足{tag}。", CaptionErrorKind.Permission)
    };

    private static CaptionError QuotaError(Provider provider, string tag) => provider switch
    {
        Provider.Soniox => new($"Soniox 额度或余额不足{tag}。请检查账户余额和使用额度。", CaptionErrorKind.Quota),
        Provider.Gemini => new($"Gemini 额度或配额不足{tag}。请检查账户计费状态与 API 配额。", CaptionErrorKind.Quota),
        _ => new($"额度不足{tag}。", CaptionErrorKind.Quota)
    };

    private static CaptionError ConfigError(Provider provider, string tag) => provider switch
    {
        Provider.Soniox => new($"Soniox 请求被拒绝{tag}。请检查密钥与服务状态。", CaptionErrorKind.Configuration),
        Provider.Gemini => new($"Gemini 请求被拒绝{tag}。请检查密钥与 Live Translate 模型权限。", CaptionErrorKind.Configuration),
        _ => new($"请求被拒绝{tag}。", CaptionErrorKind.Configuration)
    };

    private static CaptionError TransientError(Provider provider, string tag) =>
        new($"{provider.DisplayName()} 连接暂时中断{tag}。请检查网络后重试。", CaptionErrorKind.Transient);
}

public sealed record ReconnectDecision(int Attempt, int MaxAttempts, double Delay);

public sealed class ReconnectPolicy
{
    public int MaxAttempts { get; }
    public IReadOnlyList<double> Delays { get; }
    public double StableDuration { get; }

    public int ConsecutiveFailures { get; private set; }
    public double? ConnectedSince { get; private set; }

    public ReconnectPolicy(
        int maxAttempts = 4,
        IReadOnlyList<double>? delays = null,
        double stableDuration = 15.0)
    {
        MaxAttempts = Math.Max(0, maxAttempts);
        Delays = delays ?? [0.5, 1.0, 2.0, 4.0];
        StableDuration = Math.Max(0, stableDuration);
    }

    public void Reset()
    {
        ConsecutiveFailures = 0;
        ConnectedSince = null;
    }

    // Do not reset retry count immediately on open; wait until the connection remains stable.
    public void MarkConnected(double now)
    {
        ConnectedSince = now;
    }

    public void NoteHealthy(double now)
    {
        if (ConnectedSince is double since && now - since >= StableDuration)
        {
            ConsecutiveFailures = 0;
        }
    }

    public ReconnectDecision? RecordTransientFailure(double now)
    {
        if (ConnectedSince is double since && now - since >= StableDuration)
        {
            ConsecutiveFailures = 0;
        }
        ConnectedSince = null;
        if (ConsecutiveFailures >= MaxAttempts) return null;

        int index = Math.Min(ConsecutiveFailures, Math.Max(0, Delays.Count - 1));
        double delay = Delays.Count == 0 ? 0 : Delays[index];
        ConsecutiveFailures++;
        return new ReconnectDecision(ConsecutiveFailures, MaxAttempts, delay);
    }
}
