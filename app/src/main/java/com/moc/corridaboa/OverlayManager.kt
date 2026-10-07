package com.moc.corridaboa

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

internal object OverlayManager {
    const val GOOD = 1
    const val MAYBE = 2
    const val BAD = 3
    const val WARNING = 4

    private const val POS_X = "bubble_position_x"
    private const val POS_Y = "bubble_position_y"
    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private var resultView: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val removeResultTask = Runnable { removeResult() }

    fun showFloatingButton(service: AccessibilityService, onTap: () -> Unit) {
        if (!Settings.canDrawOverlays(service)) return
        handler.post {
            if (bubbleView != null) return@post
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val diameter = dp(service, 60)
            val prefs = service.getSharedPreferences(Prefs.FILE, 0)
            val maxX = (service.resources.displayMetrics.widthPixels - diameter).coerceAtLeast(0)
            val maxY = (service.resources.displayMetrics.heightPixels - diameter).coerceAtLeast(0)
            val params = WindowManager.LayoutParams(
                diameter,
                diameter,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = prefs.getInt(POS_X, (maxX - dp(service, 8)).coerceAtLeast(0)).coerceIn(0, maxX)
                y = prefs.getInt(POS_Y, dp(service, 300)).coerceIn(0, maxY)
            }
            val circle = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(21, 232, 167), Color.rgb(0, 155, 117))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(service, 2), Color.argb(220, 231, 255, 247))
            }
            val bubble = TextView(service).apply {
                text = "R\$"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = circle
                elevation = dp(service, 10).toFloat()
                contentDescription = "RotaLume: toque para reler a oferta; arraste para mover"
                includeFontPadding = false
            }
            val touchSlop = ViewConfiguration.get(service).scaledTouchSlop.toFloat()
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            bubble.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop.toDouble()) moved = true
                        if (moved) {
                            params.x = (startX + dx).toInt().coerceIn(0, maxX)
                            params.y = (startY + dy).toInt().coerceIn(0, maxY)
                            try { wm.updateViewLayout(bubble, params) } catch (_: Exception) { }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs.edit().putInt(POS_X, params.x).putInt(POS_Y, params.y).apply()
                        } else {
                            onTap()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
            try {
                wm.addView(bubble, params)
                windowManager = wm
                bubbleView = bubble
            } catch (_: Exception) {
                bubbleView = null
            }
        }
    }

    fun show(service: AccessibilityService, message: String, status: Int) {
        handler.post {
            removeResult()
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val accent = when (status) {
                GOOD -> Color.rgb(37, 225, 151)
                MAYBE -> Color.rgb(255, 200, 72)
                BAD -> Color.rgb(255, 105, 105)
                else -> Color.rgb(117, 176, 255)
            }
            val background = GradientDrawable().apply {
                setColor(Color.rgb(26, 35, 58))
                cornerRadius = dp(service, 23).toFloat()
                setStroke(dp(service, 2), accent)
            }
            val card = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(service, 18), dp(service, 14), dp(service, 18), dp(service, 14))
                this.background = background
                elevation = dp(service, 12).toFloat()
            }
            val lines = message.lines()
            val title = TextView(service).apply {
                text = "ROTALUME  •  ${lines.firstOrNull().orEmpty()}"
                textSize = 16f
                setTextColor(accent)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(service, 7))
            }
            val details = TextView(service).apply {
                text = lines.drop(1).joinToString("\n").ifBlank { message }
                textSize = 13f
                setTextColor(Color.rgb(241, 245, 255))
                setLineSpacing(dp(service, 2).toFloat(), 1.04f)
                contentDescription = message
            }
            card.addView(title)
            card.addView(details)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val margin = dp(service, 12)
                x = margin
                y = dp(service, 68)
                width = service.resources.displayMetrics.widthPixels - 2 * margin
            }
            try {
                wm.addView(card, params)
                windowManager = wm
                resultView = card
                handler.removeCallbacks(removeResultTask)
                handler.postDelayed(removeResultTask, 18_000)
            } catch (_: Exception) {
                removeResult()
            }
        }
    }

    fun hide(service: AccessibilityService) {
        handler.post {
            removeResult()
            try { bubbleView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
            bubbleView = null
            windowManager = null
        }
    }

    private fun removeResult() {
        handler.removeCallbacks(removeResultTask)
        try { resultView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        resultView = null
    }

    private fun dp(service: AccessibilityService, value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
