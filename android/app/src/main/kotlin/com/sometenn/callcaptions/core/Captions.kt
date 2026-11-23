package com.sometenn.callcaptions.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Token(
    val text: String,
    @SerialName("is_final") val isFinal: Boolean = false,
    @SerialName("translation_status") val translationStatus: String? = null,
)

@Serializable
data class Reply(
    val tokens: List<Token>? = null,
    val finished: Boolean? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

enum class CaptionDisplayMode(
    val rawValue: String,
    val title: String,
    val accessibilityLabel: String,
) {
    BILINGUAL("bilingual", "双语字幕", "双语字幕预览"),
    CHINESE_ONLY("chineseOnly", "仅大号中文", "大号中文字幕预览");

    companion object {
        fun fromRawValue(raw: String?): CaptionDisplayMode =
            entries.firstOrNull { it.rawValue == raw } ?: BILINGUAL
    }
}

fun interface ChineseSimplifier {
    fun simplify(text: String): String
}

object IcuChineseSimplifier : ChineseSimplifier {
    private val delegate: (String) -> String = createDelegate()

    override fun simplify(text: String): String =
        if (text.isEmpty()) text else delegate(text)

    private fun createDelegate(): (String) -> String {
        for (className in listOf(
            "android.icu.text.Transliterator",
            "com.ibm.icu.text.Transliterator",
        )) {
            try {
                val clazz = Class.forName(className)
                val getInstance = clazz.getMethod("getInstance", String::class.java)
                val instance = getInstance.invoke(null, "Traditional-Simplified")
                val transliterate = clazz.getMethod("transliterate", String::class.java)
                // Probe once so Android stub classes in JVM unit tests fall through cleanly.
                val probe = transliterate.invoke(instance, "學習") as? String
                if (probe == "学习") {
                    return { input -> (transliterate.invoke(instance, input) as? String) ?: input }
                }
            } catch (_: Throwable) {
                // Fall through to next provider.
            }
        }
        return { it }
    }
}

/**
 * Soniox sends confirmed text once and replaces the whole provisional suffix.
 */
data class Captions(
    private var original: String = "",
    private var translated: String = "",
    private val simplifier: ChineseSimplifier = IcuChineseSimplifier,
) {
    var japanese: String = original
        private set
    var chinese: String = simplifier.simplify(translated)
        private set

    val hasCommitted: Boolean
        get() = original.isNotEmpty() || translated.isNotEmpty()

    fun apply(tokens: List<Token>) {
        val origBuilder = StringBuilder(original)
        val transBuilder = StringBuilder(translated)
        val draftOriginal = StringBuilder()
        val draftTranslation = StringBuilder()

        for (token in tokens) {
            if (token.text == "<end>" || token.text == "<fin>") continue
            if (token.translationStatus == "translation") {
                if (token.isFinal) transBuilder.append(token.text) else draftTranslation.append(token.text)
            } else {
                if (token.isFinal) origBuilder.append(token.text) else draftOriginal.append(token.text)
            }
        }

        original = origBuilder.toString().takeLast(180)
        translated = transBuilder.toString().takeLast(180)
        japanese = (original + draftOriginal.toString()).takeLast(180)
        chinese = simplifier.simplify((translated + draftTranslation.toString()).takeLast(180))
    }

    /**
     * Remove unconfirmed provisional suffixes while keeping committed text intact.
     */
    fun clearProvisional() {
        japanese = original
        chinese = simplifier.simplify(translated)
    }

    fun snapshot(): Captions {
        val copy = Captions(original = original, translated = translated, simplifier = simplifier)
        copy.japanese = japanese
        copy.chinese = chinese
        return copy
    }
}

data class OverlayBanner(
    val text: String,
    val tone: Tone,
) {
    enum class Tone { INFO, WARNING }
}

data class OverlayFrame(
    val banner: OverlayBanner?,
    val style: Style,
) {
    sealed class Style {
        data class Bilingual(val top: String, val bottom: String) : Style()
        data class ChineseOnly(val text: String, val isPlaceholder: Boolean) : Style()
        data class Probe(val status: String, val meter: String) : Style()
    }
}

object CaptionPresentation {
    fun idleFrame(mode: CaptionDisplayMode): OverlayFrame = when (mode) {
        CaptionDisplayMode.BILINGUAL -> OverlayFrame(
            banner = null,
            style = OverlayFrame.Style.Bilingual(top = "日语原文", bottom = "简体中文译文"),
        )
        CaptionDisplayMode.CHINESE_ONLY -> OverlayFrame(
            banner = null,
            style = OverlayFrame.Style.ChineseOnly(text = "简体中文译文", isPlaceholder = true),
        )
    }

    fun probeFrame(status: String, level: Int): OverlayFrame {
        val clamped = level.coerceIn(0, 10)
        val meter = "▰".repeat(clamped) + "▱".repeat(10 - clamped)
        return OverlayFrame(banner = null, style = OverlayFrame.Style.Probe(status = status, meter = meter))
    }

    fun translateFrame(
        displayMode: CaptionDisplayMode,
        captions: Captions,
        status: String,
        isReconnecting: Boolean,
        isConnecting: Boolean,
        isAudioStalled: Boolean,
    ): OverlayFrame {
        val hasJapanese = captions.japanese.isNotEmpty()
        val hasChinese = captions.chinese.isNotEmpty()
        val warningBanner: OverlayBanner? = when {
            isReconnecting -> OverlayBanner(text = status, tone = OverlayBanner.Tone.WARNING)
            isAudioStalled && (hasJapanese || hasChinese) -> OverlayBanner(text = status, tone = OverlayBanner.Tone.WARNING)
            else -> null
        }

        return when (displayMode) {
            CaptionDisplayMode.BILINGUAL -> {
                if (warningBanner != null) {
                    val top = if (hasJapanese) captions.japanese else ""
                    val bottom = if (hasChinese) captions.chinese else "等待中文译文…"
                    OverlayFrame(banner = warningBanner, style = OverlayFrame.Style.Bilingual(top = top, bottom = bottom))
                } else {
                    val top = if (hasJapanese) captions.japanese else status
                    val bottom = if (hasChinese) captions.chinese else "等待中文译文…"
                    OverlayFrame(banner = null, style = OverlayFrame.Style.Bilingual(top = top, bottom = bottom))
                }
            }

            CaptionDisplayMode.CHINESE_ONLY -> {
                when {
                    hasChinese -> OverlayFrame(
                        banner = warningBanner,
                        style = OverlayFrame.Style.ChineseOnly(text = captions.chinese, isPlaceholder = false),
                    )
                    warningBanner != null -> OverlayFrame(
                        banner = warningBanner,
                        style = OverlayFrame.Style.ChineseOnly(text = "等待中文译文…", isPlaceholder = true),
                    )
                    isConnecting || isAudioStalled || status != "正在翻译" -> OverlayFrame(
                        banner = OverlayBanner(
                            text = status,
                            tone = if (isAudioStalled) OverlayBanner.Tone.WARNING else OverlayBanner.Tone.INFO,
                        ),
                        style = OverlayFrame.Style.ChineseOnly(text = "等待中文译文…", isPlaceholder = true),
                    )
                    else -> OverlayFrame(
                        banner = null,
                        style = OverlayFrame.Style.ChineseOnly(text = "等待中文译文…", isPlaceholder = true),
                    )
                }
            }
        }
    }
}

object CaptionTextLayout {
    private val sentenceEnders = setOf('。', '！', '？', '!', '?', '…', '\n')
    private val clauseBreakers = setOf('，', '、', '；', '：', ',', ';', ':', '—', '–', '・', '/', ' ')
    private val openingDelimiters = setOf('「', '『', '（', '(', '“', '【', '《')
    private val closingDelimiters = setOf('」', '』', '）', ')', '”', '’', '】', '》')
    private val danglingLeading = setOf(
        '。', '，', '、', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':',
        '」', '』', '）', ')', '”', '’', '】', '》', ' ', '…',
    )

    fun normalize(raw: String): String {
        val result = StringBuilder(raw.length)
        var pendingSpace = false
        for (ch in raw) {
            if (ch.isWhitespace() || ch == '\n' || ch == '\r') {
                if (result.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    result.append(' ')
                    pendingSpace = false
                }
                result.append(ch)
            }
        }
        return result.toString()
    }

    fun fitRecentPhrase(raw: String, maxWindow: Int = 160, fits: (String) -> Boolean): String {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return ""

        val window = if (normalized.length > maxWindow) normalized.takeLast(maxWindow) else normalized
        if (fits(window)) return window

        val rawStart = longestFittingStart(window, prefix = "", fits = fits)
        val rawLength = window.length - rawStart
        val minPreferredLength = if (rawLength <= 4) {
            maxOf(1, rawLength / 2)
        } else {
            maxOf(4, (rawLength * 0.45).toInt())
        }

        fun cleanStart(index: Int): Int {
            var idx = index
            while (idx < window.length && window[idx] in danglingLeading) {
                idx += 1
            }
            return idx
        }

        val sentenceStarts = mutableListOf<Int>()
        val clauseStarts = mutableListOf<Int>()
        for (i in window.indices) {
            val ch = window[i]
            when {
                ch in sentenceEnders -> {
                    var next = i + 1
                    while (next < window.length && window[next] in closingDelimiters) {
                        next += 1
                    }
                    val cleaned = cleanStart(next)
                    if (cleaned < window.length && sentenceStarts.lastOrNull() != cleaned) {
                        sentenceStarts.add(cleaned)
                    }
                }
                ch in clauseBreakers -> {
                    val cleaned = cleanStart(i + 1)
                    if (cleaned < window.length && clauseStarts.lastOrNull() != cleaned) {
                        clauseStarts.add(cleaned)
                    }
                }
                ch in openingDelimiters && i > 0 -> {
                    if (clauseStarts.lastOrNull() != i) {
                        clauseStarts.add(i)
                    }
                }
            }
        }

        for (start in sentenceStarts) {
            if (window.length - start < minPreferredLength) continue
            val candidate = window.substring(start)
            if (fits(candidate)) return candidate
        }

        for (start in clauseStarts) {
            if (window.length - start < minPreferredLength) continue
            val candidate = window.substring(start)
            if (fits(candidate)) return candidate
        }

        var fallbackStart = longestFittingStart(window, prefix = "…", fits = fits)
        fallbackStart = adjustForAsciiWordBoundary(window, fallbackStart)
        fallbackStart = cleanStart(fallbackStart)
        if (fallbackStart < window.length) {
            val withEllipsis = "…" + window.substring(fallbackStart)
            if (fits(withEllipsis)) return withEllipsis
            val plain = window.substring(fallbackStart)
            if (fits(plain)) return plain
        }

        val safeStart = minOf(rawStart, window.length - 1)
        return window.substring(safeStart)
    }

    private fun isAsciiWordChar(ch: Char): Boolean {
        val code = ch.code
        return (code in 48..57) || (code in 65..90) || (code in 97..122)
    }

    private fun adjustForAsciiWordBoundary(text: String, start: Int): Int {
        if (start <= 0 || start >= text.length) return start
        if (isAsciiWordChar(text[start - 1]) && isAsciiWordChar(text[start])) {
            var idx = start
            while (idx < text.length && isAsciiWordChar(text[idx])) {
                idx += 1
            }
            if (text.length - idx >= 4) {
                return idx
            }
        }
        return start
    }

    private fun longestFittingStart(text: String, prefix: String, fits: (String) -> Boolean): Int {
        if (text.isEmpty()) return 0
        var low = 0
        var high = text.length - 1
        var best = text.length - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = prefix + text.substring(mid)
            if (fits(candidate)) {
                best = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return best
    }
}
