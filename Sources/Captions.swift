import Foundation

struct Token: Decodable {
    let text: String
    let is_final: Bool
    let translation_status: String?
}

struct Reply: Decodable {
    let tokens: [Token]?
    let finished: Bool?
    let error_code: Int?
    let error_message: String?
}

enum CaptionDisplayMode: String, CaseIterable, Equatable {
    case bilingual = "bilingual"
    case chineseOnly = "chineseOnly"

    var title: String {
        switch self {
        case .bilingual: return "双语字幕"
        case .chineseOnly: return "仅大号中文"
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .bilingual: return "双语字幕预览"
        case .chineseOnly: return "大号中文字幕预览"
        }
    }
}

// Soniox sends confirmed text once and replaces the whole provisional suffix.
struct Captions: Equatable {
    #if MAC_CAPTIONS
    static let recentLimit = 900
    #else
    static let recentLimit = 180
    #endif
    private var original = ""
    private var translated = ""
    private(set) var japanese = ""
    private(set) var chinese = ""

    var hasCommitted: Bool { !original.isEmpty || !translated.isEmpty }

    mutating func apply(_ tokens: [Token]) {
        var draftOriginal = ""
        var draftTranslation = ""
        for token in tokens where !["<end>", "<fin>"].contains(token.text) {
            if token.translation_status == "translation" {
                if token.is_final { translated += token.text } else { draftTranslation += token.text }
            } else {
                if token.is_final { original += token.text } else { draftOriginal += token.text }
            }
        }
        original = String(original.suffix(Self.recentLimit))
        translated = String(translated.suffix(Self.recentLimit))
        japanese = String((original + draftOriginal).suffix(Self.recentLimit))
        chinese = Self.simplify(String((translated + draftTranslation).suffix(Self.recentLimit)))
    }

    // Remove unconfirmed provisional suffixes while keeping committed text intact.
    mutating func clearProvisional() {
        japanese = original
        chinese = Self.simplify(translated)
    }

    static func simplify(_ text: String) -> String {
        text.applyingTransform(StringTransform("Traditional-Simplified"), reverse: false) ?? text
    }
}

struct OverlayBanner: Equatable {
    enum Tone: Equatable { case info, warning }
    let text: String
    let tone: Tone
}

struct OverlayFrame: Equatable {
    enum Style: Equatable {
        case bilingual(top: String, bottom: String)
        case chineseOnly(text: String, isPlaceholder: Bool)
        case probe(status: String, meter: String)
    }

    let banner: OverlayBanner?
    let style: Style
}

enum CaptionPresentation {
    static func idleFrame(mode: CaptionDisplayMode) -> OverlayFrame {
        switch mode {
        case .bilingual:
            return OverlayFrame(banner: nil, style: .bilingual(top: "日语原文", bottom: "简体中文译文"))
        case .chineseOnly:
            return OverlayFrame(banner: nil, style: .chineseOnly(text: "简体中文译文", isPlaceholder: true))
        }
    }

    static func probeFrame(status: String, level: Int) -> OverlayFrame {
        let clamped = max(0, min(10, level))
        let meter = String(repeating: "▰", count: clamped) + String(repeating: "▱", count: 10 - clamped)
        return OverlayFrame(banner: nil, style: .probe(status: status, meter: meter))
    }

    static func translateFrame(displayMode: CaptionDisplayMode,
                               captions: Captions,
                               status: String,
                               isReconnecting: Bool,
                               isConnecting: Bool,
                               isAudioStalled: Bool) -> OverlayFrame {
        let hasJapanese = !captions.japanese.isEmpty
        let hasChinese = !captions.chinese.isEmpty
        let warningBanner: OverlayBanner?
        if isReconnecting {
            warningBanner = OverlayBanner(text: status, tone: .warning)
        } else if isAudioStalled && (hasJapanese || hasChinese) {
            warningBanner = OverlayBanner(text: status, tone: .warning)
        } else {
            warningBanner = nil
        }

        switch displayMode {
        case .bilingual:
            if let warningBanner {
                let top = hasJapanese ? captions.japanese : ""
                let bottom = hasChinese ? captions.chinese : "等待中文译文…"
                return OverlayFrame(banner: warningBanner, style: .bilingual(top: top, bottom: bottom))
            }
            let top = hasJapanese ? captions.japanese : status
            let bottom = hasChinese ? captions.chinese : "等待中文译文…"
            return OverlayFrame(banner: nil, style: .bilingual(top: top, bottom: bottom))

        case .chineseOnly:
            if hasChinese {
                return OverlayFrame(
                    banner: warningBanner,
                    style: .chineseOnly(text: captions.chinese, isPlaceholder: false)
                )
            }
            if let warningBanner {
                return OverlayFrame(
                    banner: warningBanner,
                    style: .chineseOnly(text: "等待中文译文…", isPlaceholder: true)
                )
            }
            if isConnecting || isAudioStalled || status != "正在翻译" {
                return OverlayFrame(
                    banner: OverlayBanner(text: status, tone: isAudioStalled ? .warning : .info),
                    style: .chineseOnly(text: "等待中文译文…", isPlaceholder: true)
                )
            }
            return OverlayFrame(
                banner: nil,
                style: .chineseOnly(text: "等待中文译文…", isPlaceholder: true)
            )
        }
    }
}

enum CaptionTextLayout {
    private static let sentenceEnders: Set<Character> = ["。", "！", "？", "!", "?", "…", "\n"]
    private static let clauseBreakers: Set<Character> = ["，", "、", "；", "：", ",", ";", ":", "—", "–", "・", "/", " "]
    private static let openingDelimiters: Set<Character> = ["「", "『", "（", "(", "“", "【", "《"]
    private static let closingDelimiters: Set<Character> = ["」", "』", "）", ")", "”", "’", "】", "》"]
    private static let danglingLeading: Set<Character> = [
        "。", "，", "、", "！", "？", "；", "：", ",", ".", "!", "?", ";", ":",
        "」", "』", "）", ")", "”", "’", "】", "》", " ", "…"
    ]

    static func normalize(_ raw: String) -> String {
        var result = ""
        result.reserveCapacity(raw.count)
        var pendingSpace = false
        for ch in raw {
            if ch.isWhitespace || ch.isNewline {
                if !result.isEmpty { pendingSpace = true }
            } else {
                if pendingSpace {
                    result.append(" ")
                    pendingSpace = false
                }
                result.append(ch)
            }
        }
        return result
    }

    static func fitRecentPhrase(_ raw: String, maxWindow: Int = 160, fits: (String) -> Bool) -> String {
        let normalized = normalize(raw)
        guard !normalized.isEmpty else { return "" }

        let chars = Array(normalized)
        let window: [Character] = chars.count > maxWindow ? Array(chars.suffix(maxWindow)) : chars
        let full = String(window)
        if fits(full) { return full }

        let rawStart = longestFittingStart(in: window, prefix: "", fits: fits)
        let rawLength = window.count - rawStart
        let minPreferredLength = rawLength <= 4 ? max(1, rawLength / 2) : max(4, Int(Double(rawLength) * 0.45))

        func cleanStart(from index: Int) -> Int {
            var idx = index
            while idx < window.count && danglingLeading.contains(window[idx]) {
                idx += 1
            }
            return idx
        }

        var sentenceStarts: [Int] = []
        var clauseStarts: [Int] = []
        for i in 0..<window.count {
            let ch = window[i]
            if sentenceEnders.contains(ch) {
                var next = i + 1
                while next < window.count && closingDelimiters.contains(window[next]) {
                    next += 1
                }
                let cleaned = cleanStart(from: next)
                if cleaned < window.count && sentenceStarts.last != cleaned {
                    sentenceStarts.append(cleaned)
                }
            } else if clauseBreakers.contains(ch) {
                let cleaned = cleanStart(from: i + 1)
                if cleaned < window.count && clauseStarts.last != cleaned {
                    clauseStarts.append(cleaned)
                }
            } else if openingDelimiters.contains(ch), i > 0 {
                if clauseStarts.last != i {
                    clauseStarts.append(i)
                }
            }
        }

        for start in sentenceStarts {
            guard start >= rawStart, window.count - start >= minPreferredLength else { continue }
            let candidate = String(window[start...])
            if fits(candidate) { return candidate }
        }

        for start in clauseStarts {
            guard start >= rawStart, window.count - start >= minPreferredLength else { continue }
            let candidate = String(window[start...])
            if fits(candidate) { return candidate }
        }

        var fallbackStart = longestFittingStart(in: window, prefix: "…", fits: fits)
        fallbackStart = adjustForASCIIWordBoundary(in: window, start: fallbackStart)
        fallbackStart = cleanStart(from: fallbackStart)
        if fallbackStart < window.count {
            let withEllipsis = "…" + String(window[fallbackStart...])
            if fits(withEllipsis) { return withEllipsis }
            let plain = String(window[fallbackStart...])
            if fits(plain) { return plain }
        }

        let safeStart = min(rawStart, window.count - 1)
        return String(window[safeStart...])
    }

    private static func isASCIIWordChar(_ ch: Character) -> Bool {
        guard ch.isASCII, ch.unicodeScalars.count == 1, let scalar = ch.unicodeScalars.first else { return false }
        return (scalar.value >= 48 && scalar.value <= 57)
            || (scalar.value >= 65 && scalar.value <= 90)
            || (scalar.value >= 97 && scalar.value <= 122)
    }

    private static func adjustForASCIIWordBoundary(in chars: [Character], start: Int) -> Int {
        guard start > 0, start < chars.count else { return start }
        if isASCIIWordChar(chars[start - 1]) && isASCIIWordChar(chars[start]) {
            var idx = start
            while idx < chars.count && isASCIIWordChar(chars[idx]) {
                idx += 1
            }
            if chars.count - idx >= 4 {
                return idx
            }
        }
        return start
    }

    private static func longestFittingStart(in chars: [Character], prefix: String, fits: (String) -> Bool) -> Int {
        guard !chars.isEmpty else { return 0 }
        var low = 0
        var high = chars.count - 1
        var best = chars.count - 1
        while low <= high {
            let mid = (low + high) / 2
            let candidate = prefix + String(chars[mid...])
            if fits(candidate) {
                best = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return best
    }
}
