package com.driveapex.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import kotlin.math.roundToInt

/**
 * The look of every button in the app: coloured, raised, and visibly pressed.
 *
 * A flat rectangle that changes nothing when touched is the wrong control for a
 * car. The driver is looking at the road, pressing by feel and glance, and needs
 * to know from the corner of their eye that the press landed -- so a button here
 * is built like a physical key.
 *
 * The three dimensions are made of drawables rather than elevation, because
 * elevation shadows are drawn outside the view and get clipped by the scrolling
 * parents this UI is full of. Instead each button is two rounded rectangles: a
 * dark one that is the side of the key, and a gradient-lit face sitting on top
 * of it, inset from the bottom by the key's travel. Pressing moves the face down
 * onto the base -- the same few pixels, taken off the top instead -- and darkens
 * its gradient, so the key visibly sinks and the light stops catching it.
 *
 * The gradient itself is what reads as three-dimensional: lighter at the top
 * where a light above the dashboard would strike it, its own colour in the
 * middle, darker at the bottom. Inverted while pressed, which is what a real
 * key does when it tilts away from the light.
 */
object ApexButtons {

    /** How far a key travels, in dp. Small: this is a key, not a lift. */
    private const val TRAVEL_DP = 5

    /**
     * A raised key in the given colour.
     *
     * @param accent the colour of the face. Everything else -- the lit top, the
     *   shaded bottom, the side of the key, the pressed face -- is derived from
     *   it, so a caller picks one colour and gets a consistent key.
     */
    fun raised(context: Context, accent: Int, radiusDp: Int = 14): Drawable {
        val radius = dp(context, radiusDp).toFloat()
        val travel = dp(context, TRAVEL_DP)
        val hair = dp(context, 1)

        val side = solid(shade(accent, 0.40f), radius)

        val face = gradient(
            intArrayOf(shade(accent, 1.42f), accent, shade(accent, 0.82f)), radius
        ).apply { setStroke(hair, shade(accent, 1.7f)) }

        val pressedFace = gradient(
            intArrayOf(shade(accent, 0.72f), shade(accent, 0.86f)), radius
        ).apply { setStroke(hair, shade(accent, 1.0f)) }

        val selectedFace = gradient(
            intArrayOf(shade(accent, 1.65f), shade(accent, 1.15f), accent), radius
        ).apply { setStroke(dp(context, 2), lighten(accent, 0.55f)) }

        return StateListDrawable().apply {
            // Order matters: the first matching state wins, so pressed has to be
            // asked about before selected or a selected key would never look
            // pressed.
            addState(intArrayOf(android.R.attr.state_pressed), key(side, pressedFace, travel, true))
            addState(intArrayOf(android.R.attr.state_selected), key(side, selectedFace, travel, false))
            addState(intArrayOf(), key(side, face, travel, false))
        }
    }

    /**
     * A key the driver can leave switched on -- a chosen voice or drive mode.
     *
     * Same key, but muted until selected: an unselected one shows the panel
     * colour with only a hint of its accent, so a row of them reads as one
     * choice made rather than six lit buttons competing.
     */
    fun selectable(context: Context, accent: Int, panel: Int, radiusDp: Int = 16): Drawable {
        val radius = dp(context, radiusDp).toFloat()
        val travel = dp(context, TRAVEL_DP)
        val resting = blend(panel, accent, 0.16f)

        val side = solid(shade(accent, 0.35f), radius)
        val face = gradient(
            intArrayOf(shade(resting, 1.35f), resting, shade(resting, 0.85f)), radius
        ).apply { setStroke(dp(context, 1), shade(accent, 0.9f)) }
        val pressedFace = gradient(
            intArrayOf(shade(resting, 0.8f), shade(resting, 0.95f)), radius
        ).apply { setStroke(dp(context, 1), accent) }
        val selectedFace = gradient(
            intArrayOf(shade(accent, 1.5f), accent, shade(accent, 0.9f)), radius
        ).apply { setStroke(dp(context, 2), lighten(accent, 0.6f)) }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), key(side, pressedFace, travel, true))
            addState(intArrayOf(android.R.attr.state_selected), key(side, selectedFace, travel, false))
            addState(intArrayOf(), key(side, face, travel, false))
        }
    }

    /**
     * Keeps a key's padding steady as it moves.
     *
     * The face is inset by the travel at one end or the other, so without this
     * the label would sit a few pixels higher when pressed and the whole button
     * would appear to twitch rather than to depress. Call once, after the
     * background is set.
     */
    fun padForTravel(view: View, horizontalDp: Int) {
        val h = dp(view.context, horizontalDp)
        val travel = dp(view.context, TRAVEL_DP)
        view.setPadding(h, travel / 2, h, travel / 2 + travel)
    }

    /** The side of the key with its face on top, offset by the travel. */
    private fun key(side: Drawable, face: Drawable, travel: Int, pressed: Boolean): Drawable =
        LayerDrawable(arrayOf(side, face)).apply {
            if (pressed) setLayerInset(1, 0, travel, 0, 0) else setLayerInset(1, 0, 0, 0, travel)
        }

    private fun solid(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    private fun gradient(colors: IntArray, radius: Float) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
        }

    /** Multiplies brightness, keeping the hue. Above 1 lightens, below 1 darkens. */
    private fun shade(color: Int, factor: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * factor).roundToInt().coerceIn(0, 255),
        (Color.green(color) * factor).roundToInt().coerceIn(0, 255),
        (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
    )

    /** Towards white, which is what a highlight is; shade alone cannot reach it. */
    private fun lighten(color: Int, amount: Float) = blend(color, Color.WHITE, amount)

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        return Color.argb(
            255,
            (Color.red(from) * (1 - a) + Color.red(to) * a).roundToInt(),
            (Color.green(from) * (1 - a) + Color.green(to) * a).roundToInt(),
            (Color.blue(from) * (1 - a) + Color.blue(to) * a).roundToInt()
        )
    }

    /**
     * Black or white, whichever the eye can actually read on this colour.
     *
     * Fixed white text disappears on the amber accent and fixed black text
     * disappears on the panel colours, and this app has both on the same screen.
     */
    fun textOn(background: Int): Int {
        val luminance = (0.299 * Color.red(background) + 0.587 * Color.green(background) +
            0.114 * Color.blue(background)) / 255.0
        return if (luminance > 0.55) 0xFF07090C.toInt() else Color.WHITE
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).roundToInt()
}
