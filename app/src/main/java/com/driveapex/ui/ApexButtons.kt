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
 * where a light above the dashboard would strike it, darker at the bottom.
 * Inverted while pressed, which is what a real key does when it tilts away from
 * the light.
 *
 * The colour is deliberately quiet. The first version filled each face with its
 * accent at full saturation and the screen came out looking like a party rather
 * than an instrument: five bright keys in a row all shouting equally. So a key
 * is graphite with its accent mixed into it a little, an accent edge, and an
 * accent-tinted label -- the colour identifies the key without lighting up the
 * cabin. The chosen card in a row goes up to a third of its accent, which is
 * plenty to spot at a glance next to four that are barely tinted at all.
 */
object ApexButtons {

    /** How far a key travels, in dp. Small: this is a key, not a lift. */
    private const val TRAVEL_DP = 5

    /** The graphite every key is made of, before its accent is mixed in. */
    private const val FACE = 0xFF161C23.toInt()

    /** How much accent a key carries: a tint, not a fill. */
    private const val RESTING_TINT = 0.14f
    private const val SELECTED_TINT = 0.34f

    /**
     * A raised key that carries the given accent without wearing it.
     *
     * @param accent the colour that identifies this key. It shows in the edge,
     *   in the label the caller draws with [textOn], and as a tint in the face.
     */
    fun raised(context: Context, accent: Int, radiusDp: Int = 14): Drawable {
        val radius = dp(context, radiusDp).toFloat()
        val travel = dp(context, TRAVEL_DP)
        val hair = dp(context, 1)

        val resting = blend(FACE, accent, RESTING_TINT)
        val side = solid(shade(resting, 0.45f), radius)

        val face = gradient(
            intArrayOf(shade(resting, 1.35f), resting, shade(resting, 0.88f)), radius
        ).apply { setStroke(hair, blend(accent, FACE, 0.45f)) }

        val pressedFace = gradient(
            intArrayOf(shade(resting, 0.78f), shade(resting, 0.92f)), radius
        ).apply { setStroke(hair, blend(accent, FACE, 0.2f)) }

        val selectedFace = gradient(
            intArrayOf(
                shade(blend(FACE, accent, SELECTED_TINT), 1.25f),
                blend(FACE, accent, SELECTED_TINT)
            ), radius
        ).apply { setStroke(dp(context, 2), accent) }

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
     * Identical machinery to [raised]; it exists as its own call because a card
     * is bigger and sits on a panel rather than on the page, so it takes the
     * panel colour as its base and rests a shade quieter.
     */
    fun selectable(context: Context, accent: Int, panel: Int, radiusDp: Int = 16): Drawable {
        val radius = dp(context, radiusDp).toFloat()
        val travel = dp(context, TRAVEL_DP)
        val resting = blend(panel, accent, 0.10f)
        val selected = blend(panel, accent, SELECTED_TINT)

        val side = solid(shade(resting, 0.45f), radius)
        val face = gradient(
            intArrayOf(shade(resting, 1.3f), resting, shade(resting, 0.9f)), radius
        ).apply { setStroke(dp(context, 1), blend(accent, panel, 0.55f)) }
        val pressedFace = gradient(
            intArrayOf(shade(resting, 0.78f), shade(resting, 0.92f)), radius
        ).apply { setStroke(dp(context, 1), blend(accent, panel, 0.35f)) }
        val selectedFace = gradient(
            intArrayOf(shade(selected, 1.22f), selected, shade(selected, 0.92f)), radius
        ).apply { setStroke(dp(context, 2), accent) }

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
     * The label colour for a key of this accent.
     *
     * Every face is dark now, so this is the accent lifted towards white far
     * enough to read cleanly at a glance while still saying which key it is. A
     * dark accent like the slate one would be unreadable at its own value, so
     * the lift is larger the darker the accent starts.
     */
    fun textOn(accent: Int): Int {
        val luminance = (0.299 * Color.red(accent) + 0.587 * Color.green(accent) +
            0.114 * Color.blue(accent)) / 255.0
        val lift = if (luminance < 0.35) 0.62f else 0.34f
        return blend(accent, Color.WHITE, lift)
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).roundToInt()
}
