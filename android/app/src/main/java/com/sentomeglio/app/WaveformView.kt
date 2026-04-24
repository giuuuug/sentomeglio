package com.sentomeglio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val barCount = 28
    private val barHeights = FloatArray(barCount) { 4f }
    private var targetHeights = FloatArray(barCount) { 4f }
    private var active = false
    private var phase = 0.0

    private val handler = Handler(Looper.getMainLooper())
    private val animRunnable = object : Runnable {
        override fun run() {
            if (active) {
                phase += 0.15
                for (i in 0 until barCount) {
                    val base = abs(sin(phase + i * 0.6)) * 0.5 + 0.1
                    val variation = abs(sin(phase * 1.3 + i * 0.4)) * 0.4
                    targetHeights[i] = ((base + variation) * height * 0.85f).toFloat().coerceAtLeast(6f)
                    barHeights[i] = barHeights[i] * 0.6f + targetHeights[i] * 0.4f
                }
                invalidate()
                handler.postDelayed(this, 60)
            } else {
                for (i in 0 until barCount) {
                    barHeights[i] = barHeights[i] * 0.85f + 4f * 0.15f
                }
                invalidate()
            }
        }
    }

    fun setActive(isActive: Boolean) {
        if (active == isActive) return
        active = isActive
        if (isActive) handler.post(animRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        val color = ContextCompat.getColor(context, R.color.colorPrimary)
        paint.color = if (active) color else (color and 0x00FFFFFF or 0x33000000)

        val totalBars = barCount
        val barWidth = (width.toFloat() / totalBars) * 0.6f
        val gap = (width.toFloat() / totalBars) * 0.4f
        val cy = height / 2f

        for (i in 0 until totalBars) {
            val x = i * (barWidth + gap)
            val h = barHeights[i]
            val top = cy - h / 2f
            val bottom = cy + h / 2f
            val radius = barWidth / 2f
            canvas.drawRoundRect(x, top, x + barWidth, bottom, radius, radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(animRunnable)
    }
}
