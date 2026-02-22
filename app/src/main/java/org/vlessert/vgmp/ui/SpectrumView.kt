package org.vlessert.vgmp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = 0xFF00FF66.toInt() // VGMP Accent (greenish)
        style = Paint.Style.FILL
    }
    private var fftData: ByteArray? = null

    fun updateFFT(fft: ByteArray) {
        fftData = fft
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = fftData ?: return
        if (data.isEmpty()) return

        val n = data.size / 2
        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = width / n

        for (i in 0 until n) {
            val rfe = data[2 * i].toInt()
            val im = data[2 * i + 1].toInt()
            val magnitude = Math.sqrt((rfe * rfe + im * im).toDouble()).toFloat()
            // Normalize magnitude (empirical scaling)
            val normalized = (magnitude / 128f) * height
            val left = i * barWidth
            val top = height - normalized.coerceAtMost(height)
            canvas.drawRect(left, top, left + barWidth - 1, height, paint)
        }
    }
}
