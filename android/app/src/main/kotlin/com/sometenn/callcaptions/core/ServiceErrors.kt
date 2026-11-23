package com.sometenn.callcaptions.core

class CaptionError(
    override val message: String,
    val kind: Kind = Kind.GENERAL,
) : Exception(message) {
    enum class Kind {
        GENERAL,
        TRANSIENT,
        AUTHENTICATION,
        PERMISSION,
        QUOTA,
        CONFIGURATION,
    }

    val isRetryable: Boolean get() = kind == Kind.TRANSIENT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptionError) return false
        return message == other.message && kind == other.kind
    }

    override fun hashCode(): Int = 31 * message.hashCode() + kind.hashCode()
}

object ServiceErrorClassifier {
    private val quotaKeywords = listOf(
        "quota", "billing", "balance", "credit", "insufficient", "payment",
        "rate limit", "rate_limit", "ratelimit", "resource_exhausted",
        "too many requests", "limit exceeded", "quota exceeded",
    )
    private val authKeywords = listOf(
        "api key", "api_key", "apikey", "unauthenticated", "unauthorized",
        "invalid key", "invalid_api_key", "invalid token", "authentication",
        "auth failed", "expired key", "bad credentials",
    )
    private val permissionKeywords = listOf(
        "permission", "forbidden", "not allowed", "access denied", "denied",
        "allowlist", "whitelist", "not enabled", "suspended", "revoked",
        "not authorized", "unsupported location", "region",
    )

    fun classify(
        provider: Provider,
        httpStatus: Int? = null,
        closeCode: Int? = null,
        closeReason: String? = null,
        errorCode: Int? = null,
        errorStatus: String? = null,
        errorMessage: String? = null,
    ): CaptionError {
        val reason = listOfNotNull(errorMessage, closeReason)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase()

        if (httpStatus != null && httpStatus >= 400) {
            val tag = "（HTTP $httpStatus）"
            return when (httpStatus) {
                401 -> authError(provider, tag)
                402, 429 -> quotaError(provider, tag)
                403 -> if (matchesQuota(reason)) quotaError(provider, tag) else permissionError(provider, tag)
                408, in 500..599 -> transientError(provider, tag)
                else -> classifyByReason(provider, reason, tag) ?: configError(provider, tag)
            }
        }

        val normalizedStatus = errorStatus?.trim()?.uppercase()
        if (errorCode != null || !normalizedStatus.isNullOrEmpty()) {
            val tag = when {
                errorCode != null -> "（$errorCode）"
                !normalizedStatus.isNullOrEmpty() -> "（$normalizedStatus）"
                else -> ""
            }
            if (normalizedStatus == "UNAUTHENTICATED" || errorCode == 401 || errorCode == 16) {
                return authError(provider, tag)
            }
            if (normalizedStatus == "RESOURCE_EXHAUSTED" || errorCode == 402 || errorCode == 429 || errorCode == 8) {
                return quotaError(provider, tag)
            }
            if (normalizedStatus == "PERMISSION_DENIED" || errorCode == 403 || errorCode == 7) {
                return if (matchesQuota(reason)) quotaError(provider, tag) else permissionError(provider, tag)
            }
            val isTransientRpc = normalizedStatus in setOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "ABORTED")
            val isTransientCode = errorCode != null && (errorCode == 408 || errorCode in 500..599 || errorCode in setOf(4, 13, 14))
            if (isTransientRpc || isTransientCode) {
                return classifyByReason(provider, reason, tag) ?: transientError(provider, tag)
            }
            return classifyByReason(provider, reason, tag) ?: configError(provider, tag)
        }

        if (closeCode != null && closeCode != 0) {
            val tag = "（WS $closeCode）"
            return when (closeCode) {
                1008 -> classifyByReason(provider, reason, tag) ?: permissionError(provider, tag)
                4001, 4401 -> authError(provider, tag)
                4003, 4403 -> if (matchesQuota(reason)) quotaError(provider, tag) else permissionError(provider, tag)
                4002, 4029, 4402, 4429 -> quotaError(provider, tag)
                1002, 1003, 1007, 1009, 1010, 4000, 4004, 4400, 4404 ->
                    classifyByReason(provider, reason, tag) ?: configError(provider, tag)
                1000, 1001, 1005, 1006, 1011, 1012, 1013, 1014, 1015, 4008, in 4500..4599 ->
                    classifyByReason(provider, reason, tag) ?: transientError(provider, tag)
                in 4000..4999 ->
                    classifyByReason(provider, reason, tag) ?: configError(provider, tag)
                else ->
                    classifyByReason(provider, reason, tag) ?: transientError(provider, tag)
            }
        }

        return classifyByReason(provider, reason, "") ?: transientError(provider, "")
    }

    private fun classifyByReason(provider: Provider, reason: String, tag: String): CaptionError? {
        if (reason.isEmpty()) return null
        if (matchesQuota(reason)) return quotaError(provider, tag)
        if (matchesAuth(reason)) return authError(provider, tag)
        if (matchesPermission(reason)) return permissionError(provider, tag)
        return null
    }

    private fun matchesQuota(reason: String): Boolean = quotaKeywords.any { reason.contains(it) }
    private fun matchesAuth(reason: String): Boolean = authKeywords.any { reason.contains(it) }
    private fun matchesPermission(reason: String): Boolean = permissionKeywords.any { reason.contains(it) }

    private fun authError(provider: Provider, tag: String): CaptionError =
        CaptionError(
            "${provider.rawValue} 认证失败$tag。请在「密钥」中检查 API 密钥是否正确或已过期。",
            kind = CaptionError.Kind.AUTHENTICATION,
        )

    private fun permissionError(provider: Provider, tag: String): CaptionError = when (provider) {
        Provider.SONIOX -> CaptionError(
            "Soniox 权限不足$tag。请检查 API 密钥和账户权限。",
            kind = CaptionError.Kind.PERMISSION,
        )
        Provider.GEMINI -> CaptionError(
            "Gemini 权限不足$tag。请检查 API 密钥和 Live Translate 模型权限。",
            kind = CaptionError.Kind.PERMISSION,
        )
    }

    private fun quotaError(provider: Provider, tag: String): CaptionError = when (provider) {
        Provider.SONIOX -> CaptionError(
            "Soniox 额度或余额不足$tag。请检查账户余额和使用额度。",
            kind = CaptionError.Kind.QUOTA,
        )
        Provider.GEMINI -> CaptionError(
            "Gemini 额度或配额不足$tag。请检查账户计费状态与 API 配额。",
            kind = CaptionError.Kind.QUOTA,
        )
    }

    private fun configError(provider: Provider, tag: String): CaptionError = when (provider) {
        Provider.SONIOX -> CaptionError(
            "Soniox 请求被拒绝$tag。请检查密钥与服务状态。",
            kind = CaptionError.Kind.CONFIGURATION,
        )
        Provider.GEMINI -> CaptionError(
            "Gemini 请求被拒绝$tag。请检查密钥与 Live Translate 模型权限。",
            kind = CaptionError.Kind.CONFIGURATION,
        )
    }

    private fun transientError(provider: Provider, tag: String): CaptionError =
        CaptionError(
            "${provider.rawValue} 连接暂时中断$tag。请检查网络后重试。",
            kind = CaptionError.Kind.TRANSIENT,
        )
}

class ReconnectPolicy(
    maxAttempts: Int = 4,
    val delays: List<Double> = listOf(0.5, 1.0, 2.0, 4.0),
    stableDuration: Double = 15.0,
) {
    data class Decision(
        val attempt: Int,
        val maxAttempts: Int,
        val delay: Double,
    )

    val maxAttempts: Int = maxOf(0, maxAttempts)
    val stableDuration: Double = maxOf(0.0, stableDuration)

    var consecutiveFailures: Int = 0
        private set
    var connectedSince: Double? = null
        private set

    fun reset() {
        consecutiveFailures = 0
        connectedSince = null
    }

    /**
     * Do not reset retry count immediately on open; wait until the connection remains stable.
     */
    fun markConnected(at: Double) {
        connectedSince = at
    }

    fun noteHealthy(at: Double) {
        val since = connectedSince ?: return
        if (at - since >= stableDuration) {
            consecutiveFailures = 0
        }
    }

    fun recordTransientFailure(at: Double): Decision? {
        val since = connectedSince
        if (since != null && at - since >= stableDuration) {
            consecutiveFailures = 0
        }
        connectedSince = null
        if (consecutiveFailures >= maxAttempts) return null
        val index = minOf(consecutiveFailures, maxOf(0, delays.size - 1))
        val delay = if (delays.isEmpty()) 0.0 else delays[index]
        consecutiveFailures += 1
        return Decision(attempt = consecutiveFailures, maxAttempts = maxAttempts, delay = delay)
    }
}
