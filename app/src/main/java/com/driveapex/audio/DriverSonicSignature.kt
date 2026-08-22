package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Learns a lightweight sonic fingerprint from the driver's recent behaviour.
 * It is intentionally deterministic and bounded; no cloud service is required.
 */
class DriverSonicSignature {
    private var aggression = 0.45f
    private var smoothness = 0.60f
    private var regenAffinity = 0.35f
    private var highSpeedAffinity = 0.30f
    private var launchAffinity = 0.25f
    private var previousSpeed = 0f
    private var previousThrottle = 0f

    fun update(data: VehicleData): Signature {
        val speed = data.normalizedSpeed()
        val throttle = data.normalizedThrottle()
        val brake = data.normalizedBrake()
        val regen = data.normalizedRegen()
        val deltaSpeed = speed - previousSpeed
        val pedalDelta = abs(throttle - previousThrottle)

        aggression = ema(aggression, min(1f, throttle * 0.7f + max(0f, deltaSpeed) / 80f), 0.045f)
        smoothness = ema(smoothness, 1f - min(1f, pedalDelta * 1.8f + brake * 0.35f), 0.035f)
        regenAffinity = ema(regenAffinity, regen, 0.04f)
        highSpeedAffinity = ema(highSpeedAffinity, min(1f, speed / 180f), 0.02f)
        launchAffinity = ema(
            launchAffinity,
            if (speed < 15f && throttle > 0.8f) 1f else 0f,
            0.06f
        )

        previousSpeed = speed
        previousThrottle = throttle

        return Signature(
            aggression = aggression,
            smoothness = smoothness,
            regenAffinity = regenAffinity,
            highSpeedAffinity = highSpeedAffinity,
            launchAffinity = launchAffinity
        )
    }

    private fun ema(current: Float, target: Float, alpha: Float): Float =
        (current + (target - current) * alpha).coerceIn(0f, 1f)

    data class Signature(
        val aggression: Float,
        val smoothness: Float,
        val regenAffinity: Float,
        val highSpeedAffinity: Float,
        val launchAffinity: Float
    ) {
        fun label(): String = when {
            aggression > 0.78f && launchAffinity > 0.52f -> "HYPER"
            aggression > 0.64f -> "ATTACK"
            smoothness > 0.78f && regenAffinity > 0.52f -> "SILK"
            highSpeedAffinity > 0.68f -> "GT"
            else -> "BALANCED"
        }

        fun toDna(base: SoundDna = SoundDnaPresets.balanced): SoundDna = base.copy(
            aggression = (base.aggression * 0.55f + aggression * 0.45f).coerceIn(0f, 1f),
            futuristic = (base.futuristic * 0.72f + highSpeedAffinity * 0.28f).coerceIn(0f, 1f),
            mechanicalBody = (base.mechanicalBody * 0.75f + (1f - smoothness) * 0.25f).coerceIn(0f, 1f),
            inverterPresence = (base.inverterPresence * 0.65f + aggression * 0.20f + highSpeedAffinity * 0.15f).coerceIn(0f, 1f),
            lowEnd = (base.lowEnd * 0.70f + aggression * 0.20f + launchAffinity * 0.10f).coerceIn(0f, 1f),
            highFrequency = (base.highFrequency * 0.70f + highSpeedAffinity * 0.15f + aggression * 0.15f).coerceIn(0f, 1f),
            cabinFocus = (base.cabinFocus * 0.80f + smoothness * 0.20f).coerceIn(0f, 1f)
        )
    }
}
