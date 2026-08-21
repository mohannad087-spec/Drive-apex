package com.driveapex.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Produces a stable stereo image for interior/exterior presentation.
 * This is a control model; the final renderer can apply the gains to PCM buses.
 */
data class StereoBusMix(
    val left: Float,
    val right: Float,
    val center: Float,
    val interior: Float,
    val exterior: Float
)

class SpatialMixModel {
    fun evaluate(
        speedKph: Float,
        throttle: Float,
        cabinFocus: Float,
        aggressive: Float
    ): StereoBusMix {
        val speed = (speedKph / 240f).coerceIn(0f, 1f)
        val pedal = throttle.coerceIn(0f, 1f)
        val attack = (pedal * 0.55f + speed * 0.25f + aggressive * 0.20f).coerceIn(0f, 1f)
        val width = (0.08f + speed * 0.42f + aggressive * 0.18f).coerceIn(0.08f, 0.72f)
        val pan = sin(speed * Math.PI.toFloat() * 0.85f) * width
        val left = (0.5f - pan * 0.5f).coerceIn(0f, 1f)
        val right = (0.5f + pan * 0.5f).coerceIn(0f, 1f)
        val center = (1f - abs(pan)) * (0.72f + attack * 0.18f)
        val interior = (0.40f + cabinFocus * 0.60f) * (0.78f + attack * 0.22f)
        val exterior = (1f - cabinFocus * 0.45f) * (0.62f + speed * 0.38f)
        return StereoBusMix(left, right, center, interior.coerceIn(0f, 1f), exterior.coerceIn(0f, 1f))
    }
}
