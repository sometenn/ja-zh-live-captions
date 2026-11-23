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
import com.sometenn.callcaptions.core.OverlayBanner
import com.sometenn.callcaptions.core.OverlayFrame
import com.sometenn.callcaptions.core.OverlaySink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class OverlayUiState(
    val active: Boolean = false,
    val notice: String = "",
    val lastFrame: OverlayFrame? = null,
)

/**
 * Draggable system-level floating caption window backed by [WindowManager]
 * (`SYSTEM_ALERT_WINDOW`). Closing the overlay hides the floating window without
 * stopping an active capture/translation session.
 */
class AndroidOverlayController(
    private val context: Context,
) : OverlaySink {

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    override val active: Boolean get() = _uiState.value.active
    override val lastFrame: OverlayFrame? get() = _uiState.value.lastFrame

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var bannerView: TextView? = null
    private var topView: TextView? = null
    private var bottomView: TextView? = null
    private var largeChineseView: TextView? = null
    private var probeStatusView: TextView? = null
    private var probeMeterView: TextView? = null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

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
            val metrics = appContext.resources.displayMetrics
            val widthPx = (metrics.widthPixels * 0.92f).roundToInt().coerceAtMost(dp(440))
            val params = WindowManager.LayoutParams(
                widthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(96)
            }

            val view = buildOverlayView(params)
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

    private fun removeViewSafely() {
        val view = rootView
        rootView = null
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
    private fun buildOverlayView(params: WindowManager.LayoutParams): View {
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 11, 19, 28))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.argb(72, 255, 255, 255))
            }
            elevation = dp(6).toFloat()
        }

        // Top bar: banner + close button
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(4), 0, dp(2))
        }

        val bottomText = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(2), 0, 0)
        }

        val largeChinese = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
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

        // Keep dragHint synced when banner is shown/hidden
        banner.tag = dragHint

        // Drag support
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
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = (initialY + (event.rawY - initialTouchY).roundToInt()).coerceAtLeast(0)
                    if (rootView != null) {
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (_: Throwable) {
                            // Ignore if detached mid-drag.
                        }
                    }
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
                top.text = CaptionTextLayout.fitRecentPhrase(style.top) { it.length <= 42 }
                bottom.text = CaptionTextLayout.fitRecentPhrase(style.bottom) { it.length <= 36 }
                top.visibility = if (style.top.isEmpty()) View.GONE else View.VISIBLE
                bottom.visibility = View.VISIBLE
                large.visibility = View.GONE
                pStatus.visibility = View.GONE
                pMeter.visibility = View.GONE
            }
            is OverlayFrame.Style.ChineseOnly -> {
                large.text = CaptionTextLayout.fitRecentPhrase(style.text) { it.length <= 42 }
                large.setTextColor(
                    if (style.isPlaceholder) Color.argb(170, 210, 224, 240) else Color.WHITE,
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
