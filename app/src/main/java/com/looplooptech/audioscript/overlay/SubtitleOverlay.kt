package com.looplooptech.audioscript.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.looplooptech.audioscript.core.Hub
import com.looplooptech.audioscript.core.Segment
import com.looplooptech.audioscript.core.SettingsData
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮双语字幕窗（对应桌面端的 NSPanel 悬浮窗）。
 *
 * Android 侧用 TYPE_APPLICATION_OVERLAY 实现：始终置顶、不抢焦点，
 * 锁定时加 FLAG_NOT_TOUCHABLE，触摸直接穿透到底下正在播视频的 App。
 */
class SubtitleOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val screenHeight = context.resources.displayMetrics.heightPixels
    private val screenWidth = context.resources.displayMetrics.widthPixels

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val rows = mutableListOf<Row>()
    private var attached = false
    private var locked = false

    private class Row(val container: LinearLayout, val source: TextView, val translation: TextView)

    fun render(segments: List<Segment>, cfg: SettingsData) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val container = ensureAttached(cfg) ?: return

        applyLock(cfg.overlayLocked)
        applyStyle(cfg)

        val items = segments.takeLast(cfg.maxLines.coerceAtLeast(1))
        while (rows.size < items.size) rows.add(createRow(container))

        items.forEachIndexed { index, segment ->
            val row = rows[index]
            row.container.visibility = View.VISIBLE
            row.source.text = segment.text
            row.source.alpha = if (segment.isFinal) 0.72f else 0.5f
            val translation = segment.translation
            if (translation.isNullOrBlank()) {
                row.translation.visibility = View.GONE
            } else {
                row.translation.visibility = View.VISIBLE
                row.translation.text = translation
                row.translation.alpha = if (segment.isFinal) 1f else 0.6f
            }
        }
        for (index in items.size until rows.size) {
            rows[index].container.visibility = View.GONE
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (!attached) return
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        rows.clear()
        root = null
        params = null
        attached = false
    }

    private fun ensureAttached(cfg: SettingsData): LinearLayout? {
        root?.let { if (attached) return it }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val layoutParams = WindowManager.LayoutParams(
            (screenWidth - dp(20)).coerceAtLeast(dp(200)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = if (cfg.overlayY >= 0) cfg.overlayY else (screenHeight * 0.72f).roundToInt()
        }

        attachDragHandler(container, layoutParams)

        locked = false
        return try {
            windowManager.addView(container, layoutParams)
            root = container
            params = layoutParams
            attached = true
            container
        } catch (t: Throwable) {
            Hub.setNotice("悬浮字幕无法显示：${t.message ?: "缺少悬浮窗权限"}")
            null
        }
    }

    private fun createRow(parent: LinearLayout): Row {
        val source = TextView(context).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
            setLineSpacing(0f, 1.15f)
        }
        val translation = TextView(context).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT
            setShadowLayer(6f, 0f, 1f, 0xCC000000.toInt())
            setLineSpacing(0f, 1.15f)
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(source)
            addView(translation)
        }
        parent.addView(wrapper)
        return Row(wrapper, source, translation)
    }

    private fun applyStyle(cfg: SettingsData) {
        val container = root ?: return
        val background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb((cfg.opacity * 255 / 100).coerceIn(0, 255), 0, 0, 0))
            if (cfg.glow) {
                setStroke(dp(2), Color.parseColor("#3186FF"))
            }
        }
        container.background = background

        val sourceSize = (cfg.fontSize * 0.8f).coerceAtLeast(10f).toFloat()
        for (row in rows) {
            row.source.setTextSize(TypedValue.COMPLEX_UNIT_SP, sourceSize)
            row.translation.setTextSize(TypedValue.COMPLEX_UNIT_SP, cfg.fontSize.toFloat())
            row.translation.setTextColor(Color.WHITE)
        }
    }

    private fun applyLock(shouldLock: Boolean) {
        if (shouldLock == locked) return
        locked = shouldLock
        val layoutParams = params ?: return
        if (shouldLock) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { root?.let { windowManager.updateViewLayout(it, layoutParams) } }
    }

    private fun attachDragHandler(container: LinearLayout, layoutParams: WindowManager.LayoutParams) {
        var startY = 0f
        var originY = 0
        var dragged = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    originY = layoutParams.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - startY
                    if (abs(delta) > dp(4)) dragged = true
                    layoutParams.y = (originY + delta).roundToInt()
                        .coerceIn(0, (screenHeight - dp(80)).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(container, layoutParams) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) Hub.updateOverlayPosition(layoutParams.y)
                    true
                }

                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()
}
