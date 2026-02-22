package org.vlessert.vgmp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint().apply {
        color = 0xFF00FF66.toInt()
        style = Paint.Style.FILL
    }
    private val smoothingFactor = 0.8f
    private var smoothedMagnitudes: FloatArray? = null
    private var spectrumData: FloatArray? = null
    private var lastLogTime = 0L

    fun updateFFT(magnitudes: FloatArray) {
        spectrumData = magnitudes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val magnitudes = spectrumData ?: return
        if (magnitudes.isEmpty()) return

        val n = magnitudes.size

        // Logarithmic binning: Map 128 points to ~32 bins
        val binCount = 32
        val binned = FloatArray(binCount)
        for (i in 0 until binCount) {
            // Map bin index to FFT index exponentially
            val startIdx = (Math.pow(2.0, i / (binCount / 7.0)) - 1.0).toInt().coerceIn(0, n - 1)
            val endIdx = (Math.pow(2.0, (i + 1) / (binCount / 7.0)) - 1.0).toInt().coerceIn(startIdx + 1, n)
            
            var maxMag = 0f
            for (j in startIdx until endIdx) {
                if (j < n) {
                    val mag = magnitudes[j]
                    if (mag > maxMag) maxMag = mag
                }
            }
            binned[i] = maxMag
        }

        // Smoothing
        if (smoothedMagnitudes == null || smoothedMagnitudes!!.size != binCount) {
            smoothedMagnitudes = binned
        } else {
            for (i in 0 until binCount) {
                smoothedMagnitudes!![i] = smoothedMagnitudes!![i] * smoothingFactor + binned[i] * (1.0f - smoothingFactor)
            }
        }

        val width = width.toFloat()
        val height = height.toFloat()
        
        // Draw symmetric: center outward
        val totalBars = binCount * 2
        val barWidth = width / totalBars
        val spacing = 2f

        for (i in 0 until binCount) {
            val magnitude = smoothedMagnitudes!![i]
            val barHeight = (magnitude / 64f) * height // Fixed scaling
            val clampedHeight = barHeight.coerceAtMost(height)
            
            val top = height - clampedHeight
            
            // Left half (mirrored)
            val lLeft = (binCount - 1 - i) * barWidth
            canvas.drawRect(lLeft + spacing, top, lLeft + barWidth - spacing, height, barPaint)
            
            // Right half
            val rLeft = (binCount + i) * barWidth
            canvas.drawRect(rLeft + spacing, top, rLeft + barWidth - spacing, height, barPaint)
        }
    }
}
