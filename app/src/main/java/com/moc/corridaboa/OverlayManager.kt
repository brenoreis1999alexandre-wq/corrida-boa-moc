package com.moc.corridaboa

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal object OverlayManager {
    const val GOOD = 1
    const val MAYBE = 2
    const val BAD = 3
    const val WARNING = 4

    private var windowManager: WindowManager? = null
    private var floatingButton: LinearLayout? = null
    private var resultCard: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val removeResultTask = Runnable { removeResult() }

    fun showFloatingButton(service: AccessibilityService, onClick: () -> Unit) {
        if (!Settings.canDrawOverlays(service)) return
        handler.post {
            if (floatingButton != null) return@post
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val pillBackground = GradientDrawable().apply {
                setColor(Color.rgb(11, 24, 25))
                cornerRadius = dp(service, 28).toFloat()
                setStroke(dp(service, 2), Color.rgb(0, 214, 121))
            }
            val pill = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(service, 9), dp(service, 7), dp(service, 16), dp(service, 7))
                background = pillBackground
                elevation = dp(service, 10).toFloat()
                contentDescription = "RotaLume — ler corrida"
                isClickable = true
                isFocusable = false
                setOnClickListener { onClick() }
            }
            val icon = ImageView(service).apply {
                setImageResource(R.drawable.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            pill.addView(icon, LinearLayout.LayoutParams(dp(service, 34), dp(service, 34)))
            val text = TextView(service).apply {
                this.text = "LER CORRIDA"
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(service, 9), 0, 0, 0)
            }
            pill.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(service, 10)
                y = dp(service, 420)
            }
            try {
                wm.addView(pill, params)
                windowManager = wm
                floatingButton = pill
            } catch (_: Exception) {
                floatingButton = null
            }
        }
    }

    fun show(service: AccessibilityService, message: String, status: Int) {
        handler.post {
            removeResult()
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val color = when (status) {
                GOOD -> Color.rgb(0, 160, 88)
                MAYBE -> Color.rgb(170, 125, 0)
                BAD -> Color.rgb(175, 42, 42)
                else -> Color.rgb(58, 72, 92)
            }
            val background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(service, 22).toFloat()
                setStroke(dp(service, 2), Color.WHITE)
            }
            val label = TextView(service).apply {
                text = message
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(service, 20), dp(service, 15), dp(service, 20), dp(service, 15))
                this.background = background
                elevation = dp(service, 12).toFloat()
                contentDescription = message
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(service, 64)
            }
            try {
                wm.addView(label, params)
                windowManager = wm
                resultCard = label
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
            try { floatingButton?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
            floatingButton = null
            windowManager = null
        }
    }

    private fun removeResult() {
        handler.removeCallbacks(removeResultTask)
        try { resultCard?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        resultCard = null
    }

    private fun dp(service: AccessibilityService, value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()
}
