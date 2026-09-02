package com.driveapex.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/**
 * The car in the corner of the cockpit, drawn as a silhouette.
 *
 * The design has a photograph of the car there. A photograph is not something
 * this app can ship -- it would be someone else's image of someone else's car --
 * so the shape is drawn instead: a crossover profile in the cockpit's own blue,
 * lit along the top edge, sitting on a light streak.
 *
 * It is deliberately a silhouette rather than an attempt at a likeness. A rough
 * drawing pretending to be a photograph looks worse than a clean shape that is
 * obviously a shape.
 */
class CarView(context: Context) : View(context) {

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF7FD4FF.toInt()
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66102438
    }
    private val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0A0F16.toInt()
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2E4E6B.toInt()
    }
    private val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val path = Path()
    private val glassPath = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Everything is expressed as a fraction of the box, so the drawing keeps
        // its proportions at any size the layout gives it.
        val left = w * 0.06f
        val right = w * 0.94f
        val ground = h * 0.80f
        val roof = h * 0.30f
        val beltline = h * 0.55f

        body.shader = LinearGradient(
            0f, roof, 0f, ground,
            intArrayOf(0xFF2E7FC4.toInt(), 0xFF14507F.toInt(), 0xFF0D3453.toInt()),
            null, Shader.TileMode.CLAMP
        )
        streak.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(0x001D9BF0, 0xAA1D9BF0.toInt(), 0x001D9BF0),
            null, Shader.TileMode.CLAMP
        )
        edge.strokeWidth = h * 0.018f
        rim.strokeWidth = h * 0.022f

        // Light under the car, which is what puts it on a surface.
        canvas.drawRoundRect(left, ground, right, ground + h * 0.035f, h * 0.02f, h * 0.02f, streak)

        // Body: nose, bonnet, screen, roof, tailgate, back down to the sill.
        path.reset()
        path.moveTo(left, ground)
        path.lineTo(left + w * 0.02f, beltline + h * 0.05f)
        path.cubicTo(
            left + w * 0.06f, beltline - h * 0.02f,
            left + w * 0.16f, beltline - h * 0.06f,
            left + w * 0.24f, beltline - h * 0.08f
        )
        path.cubicTo(
            left + w * 0.32f, roof + h * 0.02f,
            left + w * 0.42f, roof - h * 0.03f,
            left + w * 0.54f, roof - h * 0.02f
        )
        path.cubicTo(
            left + w * 0.66f, roof - h * 0.01f,
            left + w * 0.74f, roof + h * 0.06f,
            left + w * 0.80f, beltline - h * 0.02f
        )
        path.lineTo(right, beltline + h * 0.06f)
        path.lineTo(right, ground)
        path.close()
        canvas.drawPath(path, body)
        canvas.drawPath(path, edge)

        // Glass, one shape for the whole side.
        glassPath.reset()
        glassPath.moveTo(left + w * 0.26f, beltline - h * 0.06f)
        glassPath.cubicTo(
            left + w * 0.34f, roof + h * 0.04f,
            left + w * 0.44f, roof + h * 0.02f,
            left + w * 0.54f, roof + h * 0.02f
        )
        glassPath.cubicTo(
            left + w * 0.64f, roof + h * 0.03f,
            left + w * 0.70f, roof + h * 0.08f,
            left + w * 0.76f, beltline - h * 0.03f
        )
        glassPath.close()
        canvas.drawPath(glassPath, glass)

        // Wheels, set into the body rather than hung off it.
        val radius = h * 0.16f
        val front = left + w * 0.24f
        val rear = left + w * 0.74f
        canvas.drawCircle(front, ground - radius * 0.25f, radius, wheel)
        canvas.drawCircle(rear, ground - radius * 0.25f, radius, wheel)
        canvas.drawCircle(front, ground - radius * 0.25f, radius * 0.72f, rim)
        canvas.drawCircle(rear, ground - radius * 0.25f, radius * 0.72f, rim)
    }
}
