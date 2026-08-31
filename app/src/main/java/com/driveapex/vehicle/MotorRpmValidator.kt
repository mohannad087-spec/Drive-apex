package com.driveapex.vehicle

import kotlin.math.abs

/**
 * Rejects motor-speed readings the drivetrain cannot physically have produced.
 *
 * The reading was observed dropping from about 3000 to 0 and back within a
 * single sample. That is not a measurement, it is a dropout: the motor drives
 * the wheels through one fixed reduction, so its speed is rigidly proportional
 * to road speed and it cannot reach zero while the car is moving, nor change by
 * thousands of rpm in tens of milliseconds.
 *
 * Two independent checks, either of which is enough to reject a sample:
 *
 *  - Rate. Bounded by traction, not by the motor: even a hard launch or a
 *    threshold stop moves road speed a few km/h in 50ms, which is a few hundred
 *    rpm at this vehicle's ratio. The limits here are far looser than that and
 *    still reject an instantaneous collapse.
 *  - Consistency with road speed. A near-zero reading while the car is clearly
 *    moving is impossible with a single-speed reducer, whatever the rate.
 *
 * A rejected sample holds the previous value rather than substituting zero,
 * because "no valid reading this instant" is not "the motor stopped". If a
 * reading the filter disagrees with persists, it is accepted anyway: the filter
 * exists to remove dropouts, not to override the vehicle indefinitely.
 */
class MotorRpmValidator(
    private val maxRisePerSecond: Float = 20_000f,
    private val maxFallPerSecond: Float = 9_000f,
    private val movingSpeedKph: Float = 6f,
    private val persistenceMs: Long = 500L,
    private val holdMs: Long = 1_500L
) {
    @Volatile private var held: Float? = null
    private var heldAtMs = 0L
    private var disputedSince = 0L
    private var disputedValue = 0f

    var rejected: Long = 0L
        private set

    fun reset() {
        held = null
        heldAtMs = 0L
        disputedSince = 0L
        rejected = 0L
    }

    /** The value to use, or null when there is nothing trustworthy to report. */
    fun accept(candidate: Float?, speedKph: Float, nowMs: Long): Float? {
        val previous = held
        if (candidate == null || !candidate.isFinite()) {
            return previous?.takeIf { nowMs - heldAtMs <= holdMs }
        }
        if (previous == null) {
            held = candidate
            heldAtMs = nowMs
            return candidate
        }

        val dt = (nowMs - heldAtMs).coerceAtLeast(1L)
        val budget = (if (candidate > previous) maxRisePerSecond else maxFallPerSecond) * dt / 1000f
        // Road speed corroborates a falling reading when the car is at rest, so
        // settling to zero at a standstill is not a dropout and must not be
        // rate-limited: the two signals agree, which is the whole test.
        val stationary = speedKph <= movingSpeedKph && candidate < previous
        val impossibleRate = !stationary && abs(candidate - previous) > budget
        // With one fixed reduction ratio there is no gear the motor can be idling in
        // while the wheels turn, so this is decisive on its own.
        val impossibleStop = candidate < 200f && speedKph > movingSpeedKph

        if (!impossibleRate && !impossibleStop) {
            held = candidate
            heldAtMs = nowMs
            disputedSince = 0L
            return candidate
        }

        // Keep rejecting only while the disagreement looks like a glitch. A
        // reading that holds its ground is the vehicle telling us something the
        // model got wrong, and it wins.
        if (disputedSince == 0L || abs(candidate - disputedValue) > 400f) {
            disputedSince = nowMs
            disputedValue = candidate
        } else if (nowMs - disputedSince >= persistenceMs) {
            held = candidate
            heldAtMs = nowMs
            disputedSince = 0L
            return candidate
        }

        rejected++
        return previous.takeIf { nowMs - heldAtMs <= holdMs }
    }
}
