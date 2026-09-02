package com.driveapex.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The cockpit dial: a 270-degree rev counter with the road speed inside it.
 *
 * Drawn rather than assembled from widgets, because everything here is an arc
 * or a number on a circle and there is no layout that expresses that. It is one
 * View that takes three numbers and paints them.
 *
 * The sweep starts at the lower left and ends at the lower right, which is where
 * a car's rev counter starts and ends; the gap at the bottom is deliberate, and
 * is where a real instrument puts its hub.
 */
class GaugeView(context: Context) : View(context) {

    private var rpm = 0f
    private var maxRpm = 8000f
    private var redlineRpm = 6500f
    private var speedKph = 0f
    private var unit = "km/h"
    private var caption = ""

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF16202B.toInt()
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bounds = RectF()

    /** @param maxRpm the top of the dial; @param redlineRpm where it turns red. */
    fun setRange(maxRpm: Float, redlineRpm: Float) {
        this.maxRpm = maxRpm.coerceAtLeast(1000f)
        this.redlineRpm = redlineRpm.coerceIn(1000f, this.maxRpm)
        invalidate()
    }

    fun setValues(rpm: Float, speedKph: Float, caption: String) {
        this.rpm = rpm.coerceAtLeast(0f)
        this.speedKph = speedKph.coerceAtLeast(0f)
        this.caption = caption
        invalidate()
    }

    fun setUnit(unit: String) { this.unit = unit; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(26)
        if (radius <= 0f) return

        val stroke = radius * 0.11f
        ring.strokeWidth = stroke
        track.strokeWidth = stroke
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(bounds, START_ANGLE, SWEEP, false, track)

        // The needle is the arc itself. A sweep gradient rotated onto the start
        // of the dial keeps the colour tied to the rev, not to the screen: blue
        // low down, warming through the middle, red at the top of the range.
        ring.shader = SweepGradient(
            cx, cy,
            intArrayOf(0xFF1D9BF0.toInt(), 0xFF22B8CF.toInt(), 0xFF7C5CFF.toInt(),
                0xFFFF5252.toInt(), 0xFF1D9BF0.toInt()),
            floatArrayOf(0f, 0.30f, 0.55f, SWEEP / 360f, 1f)
        ).also { shader ->
            val matrix = android.graphics.Matrix()
            matrix.setRotate(START_ANGLE, cx, cy)
            shader.setLocalMatrix(matrix)
        }
        val fraction = (rpm / maxRpm).coerceIn(0f, 1f)
        canvas.drawArc(bounds, START_ANGLE, SWEEP * fraction, false, ring)

        // Ticks and their numbers, one per thousand rpm.
        val steps = (maxRpm / 1000f).toInt().coerceIn(4, 12)
        text.textSize = radius * 0.13f
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val angle = Math.toRadians((START_ANGLE + SWEEP * t).toDouble())
            val past = t * maxRpm >= redlineRpm
            tick.color = if (past) 0xFFFF5252.toInt() else 0xFF3A4756.toInt()
            tick.strokeWidth = dp(2)
            val inner = radius - stroke * 0.9f
            val outer = radius - stroke * 0.2f
            canvas.drawLine(
                cx + (inner * cos(angle)).toFloat(), cy + (inner * sin(angle)).toFloat(),
                cx + (outer * cos(angle)).toFloat(), cy + (outer * sin(angle)).toFloat(),
                tick
            )
            val labelRadius = radius - stroke * 1.9f
            text.color = if (past) 0xFFFF7B7B.toInt() else 0xFF93A2B2.toInt()
            canvas.drawText(
                i.toString(),
                cx + (labelRadius * cos(angle)).toFloat(),
                cy + (labelRadius * sin(angle)).toFloat() + text.textSize * 0.35f,
                text
            )
        }

        // The speed, which is what a driver actually reads, in the middle.
        text.color = Color.WHITE
        text.textSize = radius * 0.62f
        canvas.drawText(speedKph.toInt().toString(), cx, cy + text.textSize * 0.30f, text)

        text.color = 0xFF93A2B2.toInt()
        text.textSize = radius * 0.15f
        canvas.drawText(unit, cx, cy + radius * 0.44f, text)

        text.color = 0xFF1D9BF0.toInt()
        text.textSize = radius * 0.17f
        canvas.drawText(caption, cx, cy + radius * 0.70f, text)
    }

    private fun dp(value: Int) = value * resources.displayMetrics.density

    private companion object {
        /** Lower left, sweeping over the top to the lower right. */
        const val START_ANGLE = 135f
        const val SWEEP = 270f
    }
}
