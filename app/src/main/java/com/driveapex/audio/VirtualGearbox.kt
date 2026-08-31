package com.driveapex.audio

/**
 * Turns the motor's single continuous rev range into the stepped rev range of a
 * geared engine.
 *
 * The vehicle has no gearbox: one reduction ratio, so the motor climbs to about
 * 15000 rpm in a straight line. A combustion voice mapped straight onto that is
 * one long sweep, which is the main reason it reads as synthetic no matter how
 * good the timbre is. The characteristic sound of an engine is the sawtooth --
 * rise, cut, drop, rise again.
 *
 * So each gear multiplies motor rpm by its own ratio to give a virtual engine
 * rpm. When that virtual rpm reaches the shift point the next gear takes over,
 * and because its ratio is lower the virtual rpm falls immediately. Ratios step
 * by a constant factor, so every gear shifts at the same virtual rpm and drops
 * to the same place -- the way a real gearset is spaced.
 */
class VirtualGearbox(spec: EngineCharacter.Gearbox) {

    // retune() runs on the UI thread while update() runs on the audio thread, so
    // the new shift points have to be visible across that boundary.
    @Volatile private var spec: EngineCharacter.Gearbox = spec

    /** Current gear, virtual engine rpm, and whether a shift happened this tick. */
    data class State(val gear: Int, val virtualRpm: Float, val shifted: Int)

    private var gear = 0
    private var lastShiftAtMs = 0L

    fun reset() {
        gear = 0
        lastShiftAtMs = 0L
    }

    fun update(motorRpm: Float, nowMs: Long): State {
        val ratios = spec.ratios
        if (ratios.isEmpty()) return State(0, motorRpm, 0)

        var shifted = 0
        val settled = nowMs - lastShiftAtMs >= spec.shiftLockoutMs
        val current = motorRpm * ratios[gear]

        // A lockout after each shift stops the box hunting between two gears when
        // the rpm sits right on a threshold.
        if (settled) {
            if (current > spec.upshiftRpm && gear < ratios.lastIndex) {
                gear++
                shifted = 1
                lastShiftAtMs = nowMs
            } else if (current < spec.downshiftRpm && gear > 0) {
                gear--
                shifted = -1
                lastShiftAtMs = nowMs
            }
        }

        val virtual = (motorRpm * ratios[gear]).coerceIn(spec.idleRpm, spec.limiterRpm)
        return State(gear, virtual, shifted)
    }

    /**
     * How much to duck the output right now, as a multiplier.
     *
     * An upshift is heard as much as a momentary loss of drive as it is a
     * mechanical noise: torque is interrupted, the note drops out, then comes
     * back in the new gear. Without this a shift is just a pitch jump.
     */
    fun cutGain(nowMs: Long): Float {
        if (lastShiftAtMs == 0L) return 1f
        val since = nowMs - lastShiftAtMs
        if (since >= spec.shiftCutMs) return 1f
        val phase = since.toFloat() / spec.shiftCutMs
        // Dip fast, recover smoothly.
        val depth = spec.shiftCutDepth.coerceIn(0f, 0.95f)
        return 1f - depth * (1f - phase) * (1f - phase * 0.35f)
    }

    /**
     * Adopts new shift points without disturbing the gear currently engaged, so
     * moving a tuning slider mid-drive changes the next shift rather than
     * dumping the engine back to first.
     */
    fun retune(value: EngineCharacter.Gearbox) {
        spec = value
        gear = gear.coerceIn(0, value.ratios.lastIndex.coerceAtLeast(0))
    }

    fun currentGear(): Int = gear
}
