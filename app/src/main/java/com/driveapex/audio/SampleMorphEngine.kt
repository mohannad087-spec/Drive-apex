package com.driveapex.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

/**
 * Sample-morph control layer for the next-generation acoustic renderer.
 *
 * It does not bundle OEM recordings. It defines the deterministic state machine
 * that will drive original/licensed PCM samples once they are added to res/raw/audio.
 */
class SampleMorphEngine {
    data class State(
        val primarySlot: Int,
        val secondarySlot: Int,
        val primaryWeight: Float,
        val secondaryWeight: Float,
        val pitch: Float,
        val bodyGain: Float,
        val inverterGain: Float,
        val airGain: Float,
        val transientGain: Float
    )

    private var previousRpm = 900f
    private var previousLoad = 0f
    private var phase = 0.0

    fun update(rpm: Float, load: Float, speedKph: Float, scene: AudioScene): State {
        val safeRpm = rpm.coerceIn(700f, 7_000f)
        val safeLoad = load.coerceIn(0f, 1.5f)
        val normalizedRpm = ((safeRpm - 700f) / 6300f).coerceIn(0f, 1f)
        val rpmPosition = normalizedRpm * 5f
        val lower = rpmPosition.toInt().coerceIn(0, 4)
        val upper = (lower + 1).coerceAtMost(5)
        val frac = (rpmPosition - lower).coerceIn(0f, 1f)

        val rate = abs(safeRpm - previousRpm)
        val loadDelta = abs(safeLoad - previousLoad)
        val motion = ((rate / 1200f) + (loadDelta * 2.0f)).coerceIn(0f, 1f)
        phase += 0.012 + motion * 0.03
        if (phase > 2.0 * PI) phase -= 2.0 * PI

        val sceneBody = when (scene) {
            AudioScene.IDLE -> 0.82f
            AudioScene.COAST -> 0.78f
            AudioScene.ACCELERATION -> 0.92f
            AudioScene.HARD_ACCELERATION -> 1.02f
            AudioScene.REGENERATION -> 0.88f
            AudioScene.LAUNCH -> 1.10f
            AudioScene.HIGH_SPEED -> 0.98f
        }

        val speedPresence = (speedKph / 160f).coerceIn(0f, 1f)
        val bodyGain = (0.58f + safeLoad * 0.30f) * sceneBody
        val inverterGain = (0.018f + safeLoad * 0.04f) * (0.55f + speedPresence * 0.35f)
        val airGain = speedPresence * if (scene == AudioScene.COAST) 0.75f else 0.58f
        val transientGain = (0.15f + motion * 0.60f).coerceIn(0.15f, 0.75f)

        val microDetune = (cos(phase) * 0.012).toFloat()
        val pitch = (0.86f + normalizedRpm * 0.34f + microDetune).coerceIn(0.84f, 1.22f)

        previousRpm = safeRpm
        previousLoad = safeLoad

        return State(
            primarySlot = lower,
            secondarySlot = upper,
            primaryWeight = 1f - frac,
            secondaryWeight = frac,
            pitch = pitch,
            bodyGain = bodyGain,
            inverterGain = inverterGain,
            airGain = airGain,
            transientGain = transientGain
        )
    }

    fun reset() {
        previousRpm = 900f
        previousLoad = 0f
        phase = 0.0
    }
}
