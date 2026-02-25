package org.vlessert.vgmp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SpectrumBarsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        alpha = 160
    }

    private var spectrumData: FloatArray? = null
    private var smoothed: FloatArray? = null
    private var peaks: FloatArray? = null
    private var lastDrawMs = 0L

    private val binCount = 48
    private val barGap = 6f
    private val corner = 10f

    private var barRect = RectF()
    private var gradient: LinearGradient? = null

    fun updateFFT(magnitudes: FloatArray) {
        spectrumData = magnitudes
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                0xFF00F0FF.toInt(),
                0xFF00FF99.toInt(),
                0xFFFFC857.toInt(),
                0xFFFF3D7F.toInt()
            ),
            floatArrayOf(0f, 0.45f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )
        barPaint.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val magnitudes = spectrumData ?: return
        if (magnitudes.isEmpty()) return

        val now = System.currentTimeMillis()
        val dt = if (lastDrawMs == 0L) 16f else (now - lastDrawMs).coerceAtMost(50).toFloat()
        lastDrawMs = now

        val n = magnitudes.size
        val binned = FloatArray(binCount)

        // Log-ish binning by segmenting FFT into increasing ranges
        for (i in 0 until binCount) {
            val start = ((i * i).toFloat() / (binCount * binCount) * n).toInt().coerceIn(0, n - 1)
            val end = (((i + 1) * (i + 1)).toFloat() / (binCount * binCount) * n)
                .toInt().coerceIn(start + 1, n)
            var maxMag = 0f
            for (j in start until end) {
                val v = magnitudes[j]
                if (v > maxMag) maxMag = v
            }
            binned[i] = maxMag
        }

        if (smoothed == null || smoothed!!.size != binCount) {
            smoothed = binned.copyOf()
            peaks = binned.copyOf()
        } else {
            val attack = 0.55f
            val release = 0.12f
            val peakFall = 0.6f * (dt / 16f)
            for (i in 0 until binCount) {
                val target = binned[i]
                val current = smoothed!![i]
                smoothed!![i] = if (target > current) {
                    current + (target - current) * attack
                } else {
                    current + (target - current) * release
                }
                peaks!![i] = max(peaks!![i] - peakFall, smoothed!![i])
            }
        }

        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = (width - barGap * (binCount + 1)) / binCount

        for (i in 0 until binCount) {
            val magnitude = smoothed!![i]
            val normalized = (magnitude / 96f).coerceIn(0f, 1f)
            val barHeight = max(4f, normalized * height)
            val left = barGap + i * (barWidth + barGap)
            val top = height - barHeight

            barRect.set(left, top, left + barWidth, height)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)

            // Peak cap
            val peakHeight = min(height, height - (peaks!![i] / 96f).coerceIn(0f, 1f) * height)
            barRect.set(left, peakHeight - 6f, left + barWidth, peakHeight)
            canvas.drawRoundRect(barRect, 4f, 4f, peakPaint)
        }
    }
}
