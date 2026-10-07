package com.moc.corridaboa

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

internal object OverlayManager {
    const val GOOD = 1
    const val MAYBE = 2
    const val BAD = 3
    const val WARNING = 4
    private var windowManager: WindowManager? = null
    private var view: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val removeTask = Runnable { remove() }

    fun show(service: AccessibilityService, message: String, status: Int) {
        handler.post {
            remove()
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val color = when (status) {
                GOOD -> Color.rgb(0, 190, 100)
                MAYBE -> Color.rgb(190, 145, 0)
                BAD -> Color.rgb(190, 45, 45)
                else -> Color.rgb(70, 85, 105)
            }
            val card = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 22f
                setStroke(2, Color.WHITE)
            }
            val label = TextView(service).apply {
                text = message
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(28, 22, 28, 22)
                background = card
                elevation = 12f
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
                y = (70 * service.resources.displayMetrics.density).toInt()
                val side = (12 * service.resources.displayMetrics.density).toInt()
                x = side
            }
            try {
                wm.addView(label, params)
                windowManager = wm
                view = label
                handler.removeCallbacks(removeTask)
                handler.postDelayed(removeTask, 18_000)
            } catch (_: Exception) {
                remove()
            }
        }
    }

    fun hide(service: AccessibilityService) { handler.post { remove() } }

    private fun remove() {
        handler.removeCallbacks(removeTask)
        try { view?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        view = null
        windowManager = null
    }
}
