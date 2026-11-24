package com.sometenn.callcaptions.core

import kotlin.math.roundToInt

/**
 * User-customizable caption appearance settings shared by the in-app preview
 * and the draggable system floating overlay window.
 */
data class OverlayAppearance(
    val fontScale: Float = DEFAULT_FONT_SCALE,
    val widthPercent: Int = DEFAULT_WIDTH_PERCENT,
    val maxLines: Int = DEFAULT_MAX_LINES,
    val backgroundAlphaPercent: Int = DEFAULT_BG_ALPHA_PERCENT,
) {
    fun sanitized(): OverlayAppearance = copy(
        fontScale = (fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) * 20f).roundToInt() / 20f,
        widthPercent = widthPercent.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT),
        maxLines = maxLines.coerceIn(MIN_LINES, MAX_LINES),
        backgroundAlphaPercent = backgroundAlphaPercent.coerceIn(MIN_BG_ALPHA_PERCENT, MAX_BG_ALPHA_PERCENT),
    )

    val fontScalePercent: Int get() = (fontScale * 100f).roundToInt()

    val topTextSizeSp: Float get() = 14f * fontScale
    val bottomTextSizeSp: Float get() = 18f * fontScale
    val chineseOnlyTextSizeSp: Float get() = 22f * fontScale
    val chineseOnlyMaxLines: Int get() = (maxLines + 1).coerceAtMost(5)

    val backgroundAlphaByte: Int
        get() = (backgroundAlphaPercent * 255 / 100f).roundToInt().coerceIn(0, 255)

    val backgroundAlphaFloat: Float
        get() = (backgroundAlphaPercent / 100f).coerceIn(0f, 1f)

    /**
     * Estimates a comfortable character budget for [CaptionTextLayout.fitRecentPhrase]
     * based on current overlay width, font scale, and line count.
     */
    fun charBudget(isChineseOnly: Boolean, isTopJapanese: Boolean = false): Int {
        val widthFactor = widthPercent / 92f
        val basePerLine = when {
            isChineseOnly -> 14f
            isTopJapanese -> 21f
            else -> 18f
        }
        val lines = if (isChineseOnly) chineseOnlyMaxLines else maxLines
        return ((basePerLine * widthFactor / fontScale) * lines).roundToInt().coerceIn(10, 140)
    }

    fun save(prefs: PreferencesStore) {
        val clean = sanitized()
        prefs.putString(KEY_FONT_SCALE, clean.fontScale.toString())
        prefs.putString(KEY_WIDTH_PERCENT, clean.widthPercent.toString())
        prefs.putString(KEY_MAX_LINES, clean.maxLines.toString())
        prefs.putString(KEY_BG_ALPHA_PERCENT, clean.backgroundAlphaPercent.toString())
    }

    companion object {
        const val MIN_FONT_SCALE = 0.75f
        const val MAX_FONT_SCALE = 1.60f
        const val DEFAULT_FONT_SCALE = 1.00f

        const val MIN_WIDTH_PERCENT = 60
        const val MAX_WIDTH_PERCENT = 100
        const val DEFAULT_WIDTH_PERCENT = 92

        const val MIN_LINES = 1
        const val MAX_LINES = 4
        const val DEFAULT_MAX_LINES = 2

        const val MIN_BG_ALPHA_PERCENT = 35
        const val MAX_BG_ALPHA_PERCENT = 100
        const val DEFAULT_BG_ALPHA_PERCENT = 90

        private const val KEY_FONT_SCALE = "overlayFontScale"
        private const val KEY_WIDTH_PERCENT = "overlayWidthPercent"
        private const val KEY_MAX_LINES = "overlayMaxLines"
        private const val KEY_BG_ALPHA_PERCENT = "overlayBgAlphaPercent"

        val DEFAULT = OverlayAppearance()

        fun load(prefs: PreferencesStore): OverlayAppearance {
            val fontScale = prefs.getString(KEY_FONT_SCALE)?.toFloatOrNull() ?: DEFAULT_FONT_SCALE
            val widthPercent = prefs.getString(KEY_WIDTH_PERCENT)?.toIntOrNull() ?: DEFAULT_WIDTH_PERCENT
            val maxLines = prefs.getString(KEY_MAX_LINES)?.toIntOrNull() ?: DEFAULT_MAX_LINES
            val bgAlpha = prefs.getString(KEY_BG_ALPHA_PERCENT)?.toIntOrNull() ?: DEFAULT_BG_ALPHA_PERCENT
            return OverlayAppearance(
                fontScale = fontScale,
                widthPercent = widthPercent,
                maxLines = maxLines,
                backgroundAlphaPercent = bgAlpha,
            ).sanitized()
        }
    }
}
