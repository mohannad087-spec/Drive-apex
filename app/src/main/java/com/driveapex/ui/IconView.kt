package com.driveapex.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The line icons in the rail and the tab bar, drawn rather than shipped.
 *
 * Emoji would have been one line of code and would have looked like emoji; a
 * vector drawable set is a pile of XML for five shapes. These are five short
 * paths that scale to any size and take their colour from the state they are in,
 * which is all this UI asks of an icon.
 */
class IconView(context: Context, private val glyph: Glyph) : View(context) {

    enum class Glyph { HOME, SOUND, MODES, EXTERNAL, SETTINGS, POWER, LIVE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    var tint: Int = 0xFF8695A6.toInt()
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = size * 0.32f
        paint.color = tint
        fill.color = tint
        paint.strokeWidth = size * 0.075f
        path.reset()

        when (glyph) {
            Glyph.HOME -> {
                path.moveTo(cx - r, cy)
                path.lineTo(cx, cy - r)
                path.lineTo(cx + r, cy)
                path.moveTo(cx - r * 0.72f, cy)
                path.lineTo(cx - r * 0.72f, cy + r * 0.85f)
                path.lineTo(cx + r * 0.72f, cy + r * 0.85f)
                path.lineTo(cx + r * 0.72f, cy)
                canvas.drawPath(path, paint)
            }
            Glyph.SOUND -> {
                // A note: stem, flag, and a filled head.
                path.moveTo(cx - r * 0.25f, cy + r * 0.55f)
                path.lineTo(cx - r * 0.25f, cy - r * 0.8f)
                path.lineTo(cx + r * 0.75f, cy - r * 1.05f)
                path.lineTo(cx + r * 0.75f, cy + r * 0.3f)
                canvas.drawPath(path, paint)
                canvas.drawCircle(cx - r * 0.6f, cy + r * 0.6f, r * 0.36f, fill)
                canvas.drawCircle(cx + r * 0.4f, cy + r * 0.35f, r * 0.36f, fill)
            }
            Glyph.MODES -> {
                // Three sliders, each with its knob in a different place.
                val gaps = floatArrayOf(-0.62f, 0f, 0.62f)
                val knobs = floatArrayOf(-0.3f, 0.35f, -0.05f)
                for (i in gaps.indices) {
                    val y = cy + r * gaps[i]
                    canvas.drawLine(cx - r, y, cx + r, y, paint)
                    canvas.drawCircle(cx + r * knobs[i], y, size * 0.075f, fill)
                }
            }
            Glyph.EXTERNAL -> {
                // Speaker cone with two arcs coming off it.
                path.moveTo(cx - r * 0.35f, cy - r * 0.4f)
                path.lineTo(cx - r * 0.85f, cy - r * 0.4f)
                path.lineTo(cx - r * 0.85f, cy + r * 0.4f)
                path.lineTo(cx - r * 0.35f, cy + r * 0.4f)
                path.lineTo(cx + r * 0.05f, cy + r * 0.85f)
                path.lineTo(cx + r * 0.05f, cy - r * 0.85f)
                path.close()
                canvas.drawPath(path, paint)
                for (i in 1..2) {
                    val radius = r * (0.35f + 0.35f * i)
                    oval.set(cx + r * 0.1f - radius, cy - radius, cx + r * 0.1f + radius, cy + radius)
                    canvas.drawArc(oval, -55f, 110f, false, paint)
                }
            }
            Glyph.SETTINGS -> {
                canvas.drawCircle(cx, cy, r * 0.45f, paint)
                for (i in 0 until 8) {
                    val angle = Math.toRadians(i * 45.0)
                    val inner = r * 0.72f
                    val outer = r * 1.0f
                    canvas.drawLine(
                        cx + (inner * cos(angle)).toFloat(), cy + (inner * sin(angle)).toFloat(),
                        cx + (outer * cos(angle)).toFloat(), cy + (outer * sin(angle)).toFloat(),
                        paint
                    )
                }
            }
            Glyph.POWER -> {
                oval.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(oval, -60f, 300f, false, paint)
                canvas.drawLine(cx, cy - r * 1.05f, cx, cy - r * 0.1f, paint)
            }
            Glyph.LIVE -> {
                canvas.drawCircle(cx, cy, r * 0.42f, fill)
                oval.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(oval, -50f, 100f, false, paint)
                canvas.drawArc(oval, 130f, 100f, false, paint)
            }
        }
    }
}
