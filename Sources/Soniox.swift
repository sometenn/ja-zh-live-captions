import Foundation

struct CaptionError: LocalizedError, Equatable {
    enum Kind: Equatable {
        case general
        case transient
        case authentication
        case permission
        case quota
        case configuration
    }

    let message: String
    let kind: Kind

    init(_ message: String, kind: Kind = .general) {
        self.message = message
        self.kind = kind
    }

    var isRetryable: Bool { kind == .transient }
    var errorDescription: String? { message }
}

enum ServiceErrorClassifier {
    private static let quotaKeywords = [
        "quota", "billing", "balance", "credit", "insufficient", "payment",
        "rate limit", "rate_limit", "ratelimit", "resource_exhausted",
        "too many requests", "limit exceeded", "quota exceeded"
    ]
    private static let authKeywords = [
        "api key", "api_key", "apikey", "unauthenticated", "unauthorized",
        "invalid key", "invalid_api_key", "invalid token", "authentication",
        "auth failed", "expired key", "bad credentials"
    ]
    private static let permissionKeywords = [
        "permission", "forbidden", "not allowed", "access denied", "denied",
        "allowlist", "whitelist", "not enabled", "suspended", "revoked",
        "not authorized", "unsupported location", "region"
    ]

    static func classify(provider: Provider,
                         httpStatus: Int? = nil,
                         closeCode: Int? = nil,
                         closeReason: String? = nil,
                         errorCode: Int? = nil,
                         errorStatus: String? = nil,
                         errorMessage: String? = nil) -> CaptionError {
        let reason = [errorMessage, closeReason]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
            .lowercased()

        if let httpStatus, httpStatus >= 400 {
            let tag = "（HTTP \(httpStatus)）"
            switch httpStatus {
            case 401:
                return authError(provider, tag: tag)
            case 402, 429:
                return quotaError(provider, tag: tag)
            case 403:
                return matchesQuota(reason) ? quotaError(provider, tag: tag) : permissionError(provider, tag: tag)
            case 408, 500...599:
                return transientError(provider, tag: tag)
            default:
                if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
                return configError(provider, tag: tag)
            }
        }

        let normalizedStatus = errorStatus?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if errorCode != nil || (normalizedStatus.map { !$0.isEmpty } ?? false) {
            let tag = errorCode.map { "（\($0)）" } ?? (normalizedStatus.map { "（\($0)）" } ?? "")
            if normalizedStatus == "UNAUTHENTICATED" || errorCode == 401 || errorCode == 16 {
                return authError(provider, tag: tag)
            }
            if normalizedStatus == "RESOURCE_EXHAUSTED" || errorCode == 402 || errorCode == 429 || errorCode == 8 {
                return quotaError(provider, tag: tag)
            }
            if normalizedStatus == "PERMISSION_DENIED" || errorCode == 403 || errorCode == 7 {
                return matchesQuota(reason) ? quotaError(provider, tag: tag) : permissionError(provider, tag: tag)
            }
            let isTransientRPC = normalizedStatus.map {
                ["UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "ABORTED"].contains($0)
            } ?? false
            let isTransientCode = errorCode.map {
                $0 == 408 || (500...599).contains($0) || [4, 13, 14].contains($0)
            } ?? false
            if isTransientRPC || isTransientCode {
                if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
                return transientError(provider, tag: tag)
            }
            if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
            return configError(provider, tag: tag)
        }

        if let closeCode, closeCode != 0 {
            let tag = "（WS \(closeCode)）"
            switch closeCode {
            case 1008:
                if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
                return permissionError(provider, tag: tag)
            case 4001, 4401:
                return authError(provider, tag: tag)
            case 4003, 4403:
                return matchesQuota(reason) ? quotaError(provider, tag: tag) : permissionError(provider, tag: tag)
            case 4002, 4029, 4402, 4429:
                return quotaError(provider, tag: tag)
            case 1002, 1003, 1007, 1009, 1010, 4000, 4004, 4400, 4404:
                if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
                return configError(provider, tag: tag)
            case 1000, 1001, 1005, 1006, 1011, 1012, 1013, 1014, 1015, 4008, 4500...4599:
                if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
                return transientError(provider, tag: tag)
            case 4000...4999:
                if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
                return configError(provider, tag: tag)
            default:
                if let byReason = classifyByReason(provider, reason: reason, tag: tag) { return byReason }
                return transientError(provider, tag: tag)
            }
        }

        if let byReason = classifyByReason(provider, reason: reason, tag: "") {
            return byReason
        }
        return transientError(provider, tag: "")
    }

    private static func classifyByReason(_ provider: Provider, reason: String, tag: String) -> CaptionError? {
        guard !reason.isEmpty else { return nil }
        if matchesQuota(reason) { return quotaError(provider, tag: tag) }
        if matchesAuth(reason) { return authError(provider, tag: tag) }
        if matchesPermission(reason) { return permissionError(provider, tag: tag) }
        return nil
    }

    private static func matchesQuota(_ reason: String) -> Bool {
        quotaKeywords.contains { reason.contains($0) }
    }

    private static func matchesAuth(_ reason: String) -> Bool {
        authKeywords.contains { reason.contains($0) }
    }

    private static func matchesPermission(_ reason: String) -> Bool {
        permissionKeywords.contains { reason.contains($0) }
    }

    private static func authError(_ provider: Provider, tag: String) -> CaptionError {
        CaptionError("\(provider.rawValue) 认证失败\(tag)。请在「密钥」中检查 API 密钥是否正确或已过期。", kind: .authentication)
    }

    private static func permissionError(_ provider: Provider, tag: String) -> CaptionError {
        switch provider {
        case .soniox:
            return CaptionError("Soniox 权限不足\(tag)。请检查 API 密钥和账户权限。", kind: .permission)
        case .gemini:
            return CaptionError("Gemini 权限不足\(tag)。请检查 API 密钥和 Live Translate 模型权限。", kind: .permission)
        }
    }

    private static func quotaError(_ provider: Provider, tag: String) -> CaptionError {
        switch provider {
        case .soniox:
            return CaptionError("Soniox 额度或余额不足\(tag)。请检查账户余额和使用额度。", kind: .quota)
        case .gemini:
            return CaptionError("Gemini 额度或配额不足\(tag)。请检查账户计费状态与 API 配额。", kind: .quota)
        }
    }

    private static func configError(_ provider: Provider, tag: String) -> CaptionError {
        switch provider {
        case .soniox:
            return CaptionError("Soniox 请求被拒绝\(tag)。请检查密钥与服务状态。", kind: .configuration)
        case .gemini:
            return CaptionError("Gemini 请求被拒绝\(tag)。请检查密钥与 Live Translate 模型权限。", kind: .configuration)
        }
    }

    private static func transientError(_ provider: Provider, tag: String) -> CaptionError {
        CaptionError("\(provider.rawValue) 连接暂时中断\(tag)。请检查网络后重试。", kind: .transient)
    }
}

struct ReconnectPolicy: Equatable {
    struct Decision: Equatable {
        let attempt: Int
        let maxAttempts: Int
        let delay: TimeInterval
    }

    let maxAttempts: Int
    let delays: [TimeInterval]
    let stableDuration: TimeInterval

    private(set) var consecutiveFailures = 0
    private(set) var connectedSince: TimeInterval?

    init(maxAttempts: Int = 4,
         delays: [TimeInterval] = [0.5, 1.0, 2.0, 4.0],
         stableDuration: TimeInterval = 15.0) {
        self.maxAttempts = max(0, maxAttempts)
        self.delays = delays
        self.stableDuration = max(0, stableDuration)
    }

    mutating func reset() {
        consecutiveFailures = 0
        connectedSince = nil
    }

    // Do not reset retry count immediately on open; wait until the connection remains stable.
    mutating func markConnected(at now: TimeInterval) {
        connectedSince = now
    }

    mutating func noteHealthy(at now: TimeInterval) {
        guard let since = connectedSince, now - since >= stableDuration else { return }
        consecutiveFailures = 0
    }

    mutating func recordTransientFailure(at now: TimeInterval) -> Decision? {
        if let since = connectedSince, now - since >= stableDuration {
            consecutiveFailures = 0
        }
        connectedSince = nil
        guard consecutiveFailures < maxAttempts else { return nil }
        let index = min(consecutiveFailures, max(0, delays.count - 1))
        let delay = delays.isEmpty ? 0 : delays[index]
        consecutiveFailures += 1
        return Decision(attempt: consecutiveFailures, maxAttempts: maxAttempts, delay: delay)
    }
}

// The configuration and original/translation routing follow My Translator's MIT client.
final class Soniox: CaptionService {
    private let session = URLSession(configuration: .ephemeral)
    private let socket: URLSessionWebSocketTask
    private var reader: Task<Void, Never>?

    init(key: String, endpoint: URL = URL(string: "wss://stt-rt.soniox.com/transcribe-websocket")!) {
        var request = URLRequest(url: endpoint)
        request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
        socket = session.webSocketTask(with: request)
    }

    @MainActor
    func start(receive: @escaping ([Token]) -> Void, failed: @escaping (Error) -> Void) async throws {
        let config: [String: Any] = [
            "model": "stt-rt-v6", "audio_format": "pcm_s16le",
            "sample_rate": 16000, "num_channels": 1, "language_hints": ["ja"],
            "enable_endpoint_detection": true,
            "translation": ["type": "one_way", "target_language": "zh"]
        ]
        let json = try JSONSerialization.data(withJSONObject: config)
        socket.resume()
        do {
            try await socket.send(.string(String(decoding: json, as: UTF8.self)))
            try Task.checkCancellation()
        } catch {
            throw explain(error)
        }
        reader = Task { [weak self, socket] in
            do {
                while !Task.isCancelled {
                    let message = try await socket.receive()
                    let data: Data
                    switch message {
                    case .data(let bytes): data = bytes
                    case .string(let text): data = Data(text.utf8)
                    @unknown default: continue
                    }
                    let reply = try JSONDecoder().decode(Reply.self, from: data)
                    if reply.error_code != nil || (reply.error_message.map { !$0.isEmpty } ?? false) {
                        throw ServiceErrorClassifier.classify(
                            provider: .soniox,
                            errorCode: reply.error_code,
                            errorMessage: reply.error_message
                        )
                    }
                    if let tokens = reply.tokens { receive(tokens) }
                    if reply.finished == true {
                        throw CaptionError("Soniox 会话已结束。", kind: .transient)
                    }
                }
            } catch {
                if !Task.isCancelled, let self {
                    failed(self.explain(error))
                }
            }
        }
    }

    private func explain(_ error: Error) -> Error {
        if error is CaptionError || error is CancellationError { return error }
        let httpStatus = (socket.response as? HTTPURLResponse)?.statusCode
        let closeCode = socket.closeCode.rawValue
        let closeReason = socket.closeReason.flatMap { String(data: $0, encoding: .utf8) }
        return ServiceErrorClassifier.classify(
            provider: .soniox,
            httpStatus: httpStatus,
            closeCode: closeCode == 0 ? nil : closeCode,
            closeReason: closeReason
        )
    }

    func send(_ data: Data) async throws {
        do {
            try await socket.send(.data(data))
        } catch {
            throw explain(error)
        }
    }

    func close() {
        reader?.cancel()
        reader = nil
        socket.cancel(with: .goingAway, reason: nil)
        session.invalidateAndCancel()
    }
}
