package com.driveapex.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * The moving bar display under the dial.
 *
 * It is fed a level, not audio: taking a real spectrum off the render thread
 * would mean a lock between the audio path and the UI, and the point of this is
 * to show the driver that the engine is answering the pedal, which a level says
 * as well as a spectrum would.
 *
 * Bars shift left one place per frame, so what the eye follows is the shape the
 * driving made rather than an animation running on its own.
 */
class WaveformView(context: Context) : View(context) {

    private val bars = FloatArray(64)
    private var head = 0
    private var level = 0f
    private var phase = 0f
    private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            postDelayed(this, 60L)
        }
    }

    /** 0 for silence, 1 for full. Anything above 1 is clamped. */
    fun setLevel(value: Float) { level = value.coerceIn(0f, 1f) }

    fun start() {
        if (running) return
        running = true
        post(tick)
    }

    fun stop() {
        running = false
        removeCallbacks(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /**
     * One new bar.
     *
     * The height is the level with a little shaped movement on top: a flat bar
     * at a steady throttle reads as a frozen display rather than a running
     * engine, and two sines an octave apart give it life without pretending to
     * be a measurement of anything.
     */
    private fun step() {
        phase += 0.45f
        val wobble = 0.55f + 0.45f * abs(sin(phase.toDouble()) * 0.7 + sin(phase * 2.1).toDouble() * 0.3).toFloat()
        bars[head] = (level * wobble).coerceIn(0f, 1f)
        head = (head + 1) % bars.size
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (paint.shader == null) {
            paint.shader = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                intArrayOf(0xFF1D9BF0.toInt(), 0xFF7C5CFF.toInt(), 0xFFE05CC8.toInt()),
                null, Shader.TileMode.CLAMP
            )
        }
        val slot = width.toFloat() / bars.size
        val barWidth = slot * 0.55f
        val midY = height / 2f
        val maxHeight = height * 0.46f
        for (i in bars.indices) {
            val value = bars[(head + i) % bars.size]
            val h = (maxHeight * value).coerceAtLeast(dp(1f))
            val x = i * slot + (slot - barWidth) / 2f
            canvas.drawRoundRect(x, midY - h, x + barWidth, midY + h, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
