package com.sentomeglio.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class SpectrogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private var numFreqs = 0
    private var numTimeFrames = 100 // Visual history length
    
    // Circular buffer logic for bitmap columns
    private var currentFrameIdx = 0
    
    private val paint = Paint()
    private val srcRect = Rect()
    private val dstRect = Rect()
    
    // Spectrogram mapping
    private val minDb = -90f
    private val maxDb = -15f

    fun init(nFft: Int) {
        this.numFreqs = nFft / 2 + 1
        bitmap = Bitmap.createBitmap(numTimeFrames, numFreqs, Bitmap.Config.ARGB_8888)
        currentFrameIdx = 0
    }

    fun updateSpectrogram(dbValues: FloatArray) {
        if (bitmap == null || dbValues.size < numFreqs) return
        
        val colors = IntArray(numFreqs)
        for (i in 0 until numFreqs) {
            val db = dbValues[i]
            val norm = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            
            // Simple Magma-like colormap (dark purple to bright yellow/white)
            val r = (norm * 255).toInt()
            val g = ((norm * norm) * 255).toInt()
            val b = ((norm * norm * norm) * 255).toInt()
            
            // Y-axis is inverted (0 Hz at bottom)
            colors[numFreqs - 1 - i] = Color.rgb(r, g, b)
        }
        
        // Write the column
        bitmap?.setPixels(colors, 0, 1, currentFrameIdx, 0, 1, numFreqs)
        
        currentFrameIdx = (currentFrameIdx + 1) % numTimeFrames
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        
        val w = width
        val h = height
        
        // Draw the circular buffer logic
        // Part 1: currentFrameIdx to end
        srcRect.set(currentFrameIdx, 0, numTimeFrames, numFreqs)
        val w1 = (w * (numTimeFrames - currentFrameIdx)) / numTimeFrames
        dstRect.set(0, 0, w1, h)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        
        // Part 2: 0 to currentFrameIdx
        if (currentFrameIdx > 0) {
            srcRect.set(0, 0, currentFrameIdx, numFreqs)
            dstRect.set(w1, 0, w, h)
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        }
    }
}