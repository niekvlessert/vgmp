package org.vlessert.vgmp.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.vlessert.vgmp.R

class ChannelSpectrumView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val NUM_BANDS = 16
    private val buffer = FloatArray(NUM_BANDS)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x22FFFFFF
    }
    private val spacing = 1.5f

    private val greenColor = ContextCompat.getColor(context, R.color.vgmp_meter_green)
    private val yellowColor = ContextCompat.getColor(context, R.color.vgmp_meter_yellow)
    private val redColor = ContextCompat.getColor(context, R.color.vgmp_meter_red)

    // Cached gradient — rebuilt only when height changes
    private var lastHeight = 0f
    private var gradient: LinearGradient? = null

    fun setSpectrum(levels: FloatArray, offset: Int) {
        for (i in 0 until NUM_BANDS) {
            val l = levels[offset + i].coerceIn(0f, 1f)
            if (l < buffer[i]) {
                buffer[i] = buffer[i] * 0.82f + l * 0.18f  // smooth decay
            } else {
                buffer[i] = l  // instant attack
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Rebuild gradient if height changed
        if (h != lastHeight) {
            // gradient goes from red (top = 0) to yellow (mid) to green (bottom = h)
            gradient = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(redColor, yellowColor, greenColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            lastHeight = h
        }

        barPaint.shader = gradient

        val bandWidth = (w - (spacing * (NUM_BANDS - 1))) / NUM_BANDS
        if (bandWidth <= 0) return

        for (i in 0 until NUM_BANDS) {
            val left = i * (bandWidth + spacing)
            val right = left + bandWidth

            // Draw dim background track
            canvas.drawRect(left, 0f, right, h, dimPaint)

            // Draw active bar from bottom up
            val barHeight = buffer[i] * h
            val top = h - barHeight
            if (barHeight > 0) {
                canvas.drawRect(left, top, right, h, barPaint)
            }
        }
    }
}
