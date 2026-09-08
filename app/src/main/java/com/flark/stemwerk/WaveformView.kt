package com.flark.stemwerk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Actual PCM peaks at a fixed full-scale amplitude, comparable between tracks. */
class WaveformView(context: Context) : View(context) {
    var peaks = FloatArray(0)
        set(value) { field = value; invalidate() }
    var position = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var color: Int = Color.rgb(99, 194, 240)
        set(value) { field = value; invalidate() }
    var onSeek: ((Float) -> Unit)? = null
    var message = "Building waveform…"
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(Color.rgb(26, 26, 31))
        contentDescription = "Audio waveform. Tap to seek."
        isClickable = true
        minimumHeight = (160 * resources.displayMetrics.density).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mid = height / 2f
        paint.color = Color.rgb(60, 60, 65)
        paint.strokeWidth = resources.displayMetrics.density
        canvas.drawLine(0f, mid, width.toFloat(), mid, paint)
        if (peaks.isEmpty()) {
            paint.color = Color.LTGRAY
            paint.textSize = 14 * resources.displayMetrics.scaledDensity
            canvas.drawText(message, 12 * resources.displayMetrics.density, mid - 12, paint)
            return
        }
        val step = width.toFloat() / peaks.size
        paint.strokeWidth = step.coerceAtLeast(1f)
        paint.color = color
        peaks.forEachIndexed { i, peak ->
            val amplitude = (peak.coerceIn(0f, 1f) * (mid - 8)).coerceAtLeast(0.5f)
            val x = (i + 0.5f) * step
            canvas.drawLine(x, mid - amplitude, x, mid + amplitude, paint)
        }
        paint.color = 0x334488FF
        canvas.drawRect(0f, 0f, position * width, height.toFloat(), paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 2 * resources.displayMetrics.density
        canvas.drawLine(position * width, 0f, position * width, height.toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width == 0) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onSeek?.invoke((event.x / width).coerceIn(0f, 1f))
                true
            }
            MotionEvent.ACTION_UP -> {
                onSeek?.invoke((event.x / width).coerceIn(0f, 1f))
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

