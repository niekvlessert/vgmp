package org.vlessert.vgmp.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class KaleidoscopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var spectrumData: FloatArray? = null
    private var rotationAngle = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var path = Path()
    
    init {
        // Make the view clickable to receive touch events
        isClickable = true
    }
    
    // Smoothing
    private val smoothingFactor = 0.7f
    private var smoothedMagnitudes: FloatArray? = null
    
    // Kaleidoscope segments
    private val segments = 12
    
    // Colors for the spectrum
    private val colors = intArrayOf(
        Color.parseColor("#FF0066"),  // Magenta
        Color.parseColor("#FF6600"),  // Orange
        Color.parseColor("#FFCC00"),  // Yellow
        Color.parseColor("#66FF00"),  // Lime
        Color.parseColor("#00FFCC"),  // Cyan
        Color.parseColor("#0066FF"),  // Blue
        Color.parseColor("#6600FF"),  // Purple
        Color.parseColor("#FF00CC"),  // Pink
        Color.parseColor("#FF3366"),  // Red-pink
        Color.parseColor("#FF9933"),  // Orange
        Color.parseColor("#CCFF00"),  // Yellow-green
        Color.parseColor("#00FF99")   // Green-cyan
    )
    
    private val gradientColors = intArrayOf(
        Color.parseColor("#FF00FF"),
        Color.parseColor("#00FFFF"),
        Color.parseColor("#FFFF00"),
        Color.parseColor("#FF00FF")
    )

    fun updateFFT(magnitudes: FloatArray) {
        spectrumData = magnitudes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val magnitudes = spectrumData ?: return
        if (magnitudes.isEmpty()) return
        
        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = min(width, height) / 2f
        
        // Clear with transparent background
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        
        // Process magnitudes into bins
        val binCount = 64
        val binned = FloatArray(binCount)
        val n = magnitudes.size
        
        for (i in 0 until binCount) {
            val startIdx = (i * n / binCount).coerceIn(0, n - 1)
            val endIdx = ((i + 1) * n / binCount).coerceIn(0, n)
            var sum = 0f
            var count = 0
            for (j in startIdx until endIdx) {
                sum += magnitudes[j]
                count++
            }
            binned[i] = if (count > 0) sum / count else 0f
        }
        
        // Smoothing
        if (smoothedMagnitudes == null || smoothedMagnitudes!!.size != binCount) {
            smoothedMagnitudes = binned
        } else {
            for (i in 0 until binCount) {
                smoothedMagnitudes!![i] = smoothedMagnitudes!![i] * smoothingFactor + binned[i] * (1.0f - smoothingFactor)
            }
        }
        
        // Rotate slowly
        rotationAngle += 0.5f
        if (rotationAngle >= 360f) rotationAngle -= 360f
        
        canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)
        
        // Draw kaleidoscope segments
        val angleStep = 360f / segments
        
        for (seg in 0 until segments) {
            val baseAngle = seg * angleStep
            
            canvas.save()
            canvas.rotate(baseAngle, centerX, centerY)
            
            // Draw mirrored segment
            for (mirror in 0..1) {
                if (mirror == 1) {
                    canvas.scale(1f, -1f, centerX, centerY)
                }
                
                // Draw radial bars
                for (i in 0 until binCount / 2) {
                    val magnitude = smoothedMagnitudes!![i]
                    val normalizedMag = (magnitude / 128f).coerceIn(0f, 1f)
                    
                    if (normalizedMag < 0.05f) continue
                    
                    val angle1 = (i * angleStep / (binCount / 2))
                    val angle2 = ((i + 1) * angleStep / (binCount / 2))
                    val innerRadius = maxRadius * 0.2f
                    val outerRadius = innerRadius + (maxRadius - innerRadius) * normalizedMag
                    
                    // Create gradient color based on position
                    val colorIndex = (i + seg) % colors.size
                    val alpha = (200 + 55 * normalizedMag).toInt().coerceIn(0, 255)
                    paint.color = colors[colorIndex]
                    paint.alpha = alpha
                    
                    // Draw arc segment
                    path.reset()
                    path.moveTo(
                        centerX + innerRadius * cos(Math.toRadians(angle1.toDouble())).toFloat(),
                        centerY + innerRadius * sin(Math.toRadians(angle1.toDouble())).toFloat()
                    )
                    path.lineTo(
                        centerX + outerRadius * cos(Math.toRadians(angle1.toDouble())).toFloat(),
                        centerY + outerRadius * sin(Math.toRadians(angle1.toDouble())).toFloat()
                    )
                    path.lineTo(
                        centerX + outerRadius * cos(Math.toRadians(angle2.toDouble())).toFloat(),
                        centerY + outerRadius * sin(Math.toRadians(angle2.toDouble())).toFloat()
                    )
                    path.lineTo(
                        centerX + innerRadius * cos(Math.toRadians(angle2.toDouble())).toFloat(),
                        centerY + innerRadius * sin(Math.toRadians(angle2.toDouble())).toFloat()
                    )
                    path.close()
                    
                    canvas.drawPath(path, paint)
                }
            }
            
            canvas.restore()
        }
        
        canvas.restore()
        
        // Draw center circle with glow effect
        val centerRadius = maxRadius * 0.15f
        val avgMag = smoothedMagnitudes!!.average().toFloat()
        val pulseRadius = centerRadius + centerRadius * 0.3f * (avgMag / 128f).coerceIn(0f, 1f)
        
        // Glow effect
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FF00FF")
            alpha = 100
            maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(centerX, centerY, pulseRadius, glowPaint)
        
        // Inner circle
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                centerX, centerY, centerRadius,
                intArrayOf(Color.parseColor("#FF00FF"), Color.parseColor("#00FFFF")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(centerX, centerY, centerRadius, innerPaint)
    }
}
