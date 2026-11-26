package com.sometenn.callcaptions.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.sometenn.callcaptions.core.CaptionTextLayout
import com.sometenn.callcaptions.core.InMemoryPreferencesStore
import com.sometenn.callcaptions.core.OverlayAppearance
import com.sometenn.callcaptions.core.OverlayBanner
import com.sometenn.callcaptions.core.OverlayFrame
import com.sometenn.callcaptions.core.OverlaySink
import com.sometenn.callcaptions.core.PreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class OverlayUiState(
    val active: Boolean = false,
    val notice: String = "",
    val lastFrame: OverlayFrame? = null,
    val appearance: OverlayAppearance = OverlayAppearance.DEFAULT,
)

/**
 * Draggable system-level floating caption window backed by [WindowManager]
 * (`SYSTEM_ALERT_WINDOW`). Supports live font size, window width, line count,
 * and background opacity customization. Closing the overlay hides the floating
 * window without stopping an active capture/translation session.
 */
class AndroidOverlayController(
    private val context: Context,
    private val prefs: PreferencesStore = InMemoryPreferencesStore(),
) : OverlaySink {

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val _uiState = MutableStateFlow(
        OverlayUiState(appearance = OverlayAppearance.load(prefs)),
    )
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    override val active: Boolean get() = _uiState.value.active
    override val lastFrame: OverlayFrame? get() = _uiState.value.lastFrame
    val appearance: OverlayAppearance get() = _uiState.value.appearance

    private var rootView: LinearLayout? = null
    private var backgroundDrawable: GradientDrawable? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bannerView: TextView? = null
    private var topView: TextView? = null
    private var bottomView: TextView? = null
    private var largeChineseView: TextView? = null
    private var probeStatusView: TextView? = null
    private var probeMeterView: TextView? = null

    private var savedX = prefs.getString("overlayWindowX")?.toIntOrNull() ?: 0
    private var savedY = prefs.getString("overlayWindowY")?.toIntOrNull()

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    fun updateAppearance(next: OverlayAppearance) {
        val clean = next.sanitized()
        if (clean == _uiState.value.appearance) return
        clean.save(prefs)
        _uiState.value = _uiState.value.copy(appearance = clean)
        applyAppearanceToLiveOverlay(clean)
    }

    fun resetAppearance() {
        updateAppearance(OverlayAppearance.DEFAULT)
    }

    override fun prepare() {
        clearNotice()
    }

    override fun start() {
        if (rootView != null) return
        if (!canDrawOverlays()) {
            _uiState.value = _uiState.value.copy(
                active = false,
                notice = "请先在系统设置中允许「显示在其他应用上层」权限。",
            )
            return
        }

        try {
            val currentAppearance = _uiState.value.appearance
            val params = WindowManager.LayoutParams(
                computeWidthPx(currentAppearance),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val metrics = appContext.resources.displayMetrics
                val edge = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
                x = savedX.coerceIn(-edge, edge)
                y = (savedY ?: dp(96)).coerceIn(0, (metrics.heightPixels - dp(80)).coerceAtLeast(0))
            }

            val view = buildOverlayView(params, currentAppearance)
            windowManager.addView(view, params)
            rootView = view
            layoutParams = params
            _uiState.value = _uiState.value.copy(active = true, notice = "")
            _uiState.value.lastFrame?.let { renderFrame(it) }
        } catch (e: Throwable) {
            removeViewSafely()
            _uiState.value = _uiState.value.copy(
                active = false,
                notice = "无法开启悬浮字幕：${e.message ?: "请检查悬浮窗权限"}",
            )
        }
    }

    override fun draw(frame: OverlayFrame) {
        _uiState.value = _uiState.value.copy(lastFrame = frame)
        if (rootView != null) {
            renderFrame(frame)
        }
    }

    override fun stop() {
        removeViewSafely()
        _uiState.value = _uiState.value.copy(active = false)
    }

    fun clearNotice() {
        if (_uiState.value.notice.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(notice = "")
        }
    }

    private fun computeWidthPx(appearance: OverlayAppearance): Int {
        val screenWidth = appContext.resources.displayMetrics.widthPixels
        return (screenWidth * (appearance.widthPercent / 100f)).roundToInt().coerceAtLeast(dp(200))
    }

    private fun applyAppearanceToLiveOverlay(appearance: OverlayAppearance) {
        val view = rootView ?: return
        val params = layoutParams ?: return

        backgroundDrawable?.setColor(Color.argb(appearance.backgroundAlphaByte, 11, 33, 36))

        topView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.topTextSizeSp)
            typeface = Typeface.create(appearance.face.family, Typeface.NORMAL)
            maxLines = appearance.maxLines
        }
        bottomView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.bottomTextSizeSp)
            typeface = Typeface.create(appearance.face.family, Typeface.BOLD)
            setTextColor(appearance.ink.argb)
            maxLines = appearance.maxLines
        }
        largeChineseView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.chineseOnlyTextSizeSp)
            typeface = Typeface.create(appearance.face.family, Typeface.BOLD)
            setTextColor(appearance.ink.argb)
            maxLines = appearance.chineseOnlyMaxLines
        }

        params.width = computeWidthPx(appearance)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Throwable) {
            // Ignore if view was detached concurrently.
        }

        _uiState.value.lastFrame?.let { renderFrame(it) }
    }

    private fun removeViewSafely() {
        val view = rootView
        rootView = null
        backgroundDrawable = null
        layoutParams = null
        bannerView = null
        topView = null
        bottomView = null
        largeChineseView = null
        probeStatusView = null
        probeMeterView = null
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
                // View may already be detached.
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(
        params: WindowManager.LayoutParams,
        appearance: OverlayAppearance,
    ): LinearLayout {
        val bg = GradientDrawable().apply {
            setColor(Color.argb(appearance.backgroundAlphaByte, 11, 33, 36))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.argb(72, 255, 255, 255))
        }
        backgroundDrawable = bg

        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = bg
            elevation = dp(6).toFloat()
        }

        val headerRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val banner = TextView(appContext).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dragHint = TextView(appContext).apply {
            text = "⋮⋮ 拖动字幕"
            setTextColor(Color.argb(140, 210, 224, 240))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(appContext).apply {
            text = "✕"
            setTextColor(Color.argb(200, 220, 230, 242))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), dp(2), dp(2), dp(2))
            contentDescription = "收起悬浮字幕"
            setOnClickListener { stop() }
        }

        headerRow.addView(banner)
        headerRow.addView(dragHint)
        headerRow.addView(closeBtn)
        container.addView(headerRow)

        val topText = TextView(appContext).apply {
            setTextColor(Color.argb(210, 216, 228, 242))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.topTextSizeSp)
            typeface = Typeface.create(appearance.face.family, Typeface.NORMAL)
            maxLines = appearance.maxLines
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(4), 0, dp(2))
        }

        val bottomText = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.bottomTextSizeSp)
            typeface = Typeface.create(appearance.face.family, Typeface.BOLD)
            setTextColor(appearance.ink.argb)
            maxLines = appearance.maxLines
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(2), 0, 0)
        }

        val largeChinese = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.chineseOnlyTextSizeSp)
            typeface = Typeface.create(appearance.face.family, Typeface.BOLD)
            setTextColor(appearance.ink.argb)
            maxLines = appearance.chineseOnlyMaxLines
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }

        val probeStatus = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.GONE
        }

        val probeMeter = TextView(appContext).apply {
            setTextColor(Color.rgb(74, 222, 128))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
        }

        container.addView(topText)
        container.addView(bottomText)
        container.addView(largeChinese)
        container.addView(probeStatus)
        container.addView(probeMeter)

        bannerView = banner
        topView = topText
        bottomView = bottomText
        largeChineseView = largeChinese
        probeStatusView = probeStatus
        probeMeterView = probeMeter

        banner.tag = dragHint

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = appContext.resources.displayMetrics
                    val edge = ((metrics.widthPixels - params.width) / 2).coerceAtLeast(0)
                    params.x = (initialX + (event.rawX - initialTouchX).roundToInt()).coerceIn(-edge, edge)
                    params.y = (initialY + (event.rawY - initialTouchY).roundToInt())
                        .coerceIn(0, (metrics.heightPixels - container.height).coerceAtLeast(0))
                    savedX = params.x
                    savedY = params.y
                    if (rootView != null) {
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (_: Throwable) {
                            // Ignore if detached mid-drag.
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.putString("overlayWindowX", params.x.toString())
                    prefs.putString("overlayWindowY", params.y.toString())
                    true
                }
                else -> false
            }
        }

        return container
    }

    private fun renderFrame(frame: OverlayFrame) {
        val banner = bannerView ?: return
        val dragHint = banner.tag as? TextView
        val top = topView ?: return
        val bottom = bottomView ?: return
        val large = largeChineseView ?: return
        val pStatus = probeStatusView ?: return
        val pMeter = probeMeterView ?: return
        val currentAppearance = _uiState.value.appearance

        if (frame.banner != null) {
            banner.text = frame.banner.text
            banner.setTextColor(
                when (frame.banner.tone) {
                    OverlayBanner.Tone.WARNING -> Color.rgb(251, 146, 60)
                    OverlayBanner.Tone.INFO -> Color.rgb(147, 197, 253)
                },
            )
            banner.visibility = View.VISIBLE
            dragHint?.visibility = View.GONE
        } else {
            banner.visibility = View.GONE
            dragHint?.visibility = View.VISIBLE
        }

        when (val style = frame.style) {
            is OverlayFrame.Style.Bilingual -> {
                val topBudget = currentAppearance.charBudget(isChineseOnly = false, isTopJapanese = true)
                val bottomBudget = currentAppearance.charBudget(isChineseOnly = false, isTopJapanese = false)
                top.text = CaptionTextLayout.fitRecentPhrase(style.top) { it.length <= topBudget }
                bottom.text = CaptionTextLayout.fitRecentPhrase(style.bottom) { it.length <= bottomBudget }
                top.visibility = if (style.top.isEmpty()) View.GONE else View.VISIBLE
                bottom.visibility = View.VISIBLE
                large.visibility = View.GONE
                pStatus.visibility = View.GONE
                pMeter.visibility = View.GONE
            }
            is OverlayFrame.Style.ChineseOnly -> {
                val budget = currentAppearance.charBudget(isChineseOnly = true)
                large.text = CaptionTextLayout.fitRecentPhrase(style.text) { it.length <= budget }
                large.setTextColor(
                    if (style.isPlaceholder) Color.argb(170, 210, 224, 240) else currentAppearance.ink.argb,
                )
                top.visibility = View.GONE
                bottom.visibility = View.GONE
                large.visibility = View.VISIBLE
                pStatus.visibility = View.GONE
                pMeter.visibility = View.GONE
            }
            is OverlayFrame.Style.Probe -> {
                pStatus.text = style.status
                pMeter.text = style.meter
                top.visibility = View.GONE
                bottom.visibility = View.GONE
                large.visibility = View.GONE
                pStatus.visibility = View.VISIBLE
                pMeter.visibility = View.VISIBLE
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).roundToInt()
}
