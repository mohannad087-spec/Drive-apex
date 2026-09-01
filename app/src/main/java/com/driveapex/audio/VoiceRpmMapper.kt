package com.driveapex.audio

import kotlin.math.exp

/**
 * Turns motor rpm and pedal into the rpm a voice should sound.
 *
 * Two things happen here. The gearbox maps the motor's single continuous sweep
 * onto a geared engine's stepped rev range, and at a standstill -- where the
 * motor is not turning at all and the geared rpm is pinned at idle -- the
 * throttle drives the note directly instead.
 *
 * It lives on its own because both voices need it: a bank of real recordings
 * has to shift gears and blip at rest exactly as the synthesised one does, and
 * the alternative was the same fifteen lines in two places, drifting apart.
 */
class VoiceRpmMapper {

    /** @param shifted 1 for an upshift, -1 for a downshift, 0 for neither. */
    data class Result(val rpm: Float, val gear: Int, val shifted: Int, val cutGain: Float)

    private var gearbox: VirtualGearbox? = null
    private var neutralRev = 800f

    fun retune(spec: EngineCharacter.Gearbox?) {
        val box = gearbox
        when {
            spec == null -> gearbox = null
            box == null -> gearbox = VirtualGearbox(spec)
            // Retune rather than replace, so changing a shift point mid-drive
            // does not drop the engine back into first.
            else -> box.retune(spec)
        }
    }

    fun currentGear(): Int = gearbox?.let { it.currentGear() + 1 } ?: 0

    fun map(motorRpm: Float, throttle: Float, speedKph: Float, frames: Int, sampleRate: Int, nowMs: Long): Result {
        val box = gearbox ?: return Result(motorRpm, 0, 0, 1f)

        val state = box.update(motorRpm, nowMs)

        // Free rev at a standstill: asymmetric, because an engine is. Equal
        // rates up and down is most of what makes a synthesised blip sound like
        // a slider being dragged.
        val idle = box.idleRpm()
        val revTarget = idle + throttle.coerceIn(0f, 1f) * (box.revCeiling() - idle)
        val bufferMs = frames * 1000f / sampleRate
        val tau = if (revTarget > neutralRev) REV_RISE_MS else REV_FALL_MS
        neutralRev += (revTarget - neutralRev) * (1f - exp(-bufferMs / tau))

        // Hand over by road speed rather than switching at a threshold: at rest
        // the two agree at idle, and by walking pace the gearbox has it all.
        val handover = ((speedKph - ROLLING_KPH) / HANDOVER_KPH).coerceIn(0f, 1f)
        val rpm = neutralRev * (1f - handover) + state.virtualRpm * handover

        return Result(rpm, state.gear, state.shifted, box.cutGain(nowMs))
    }

    companion object {
        const val REV_RISE_MS = 260f
        const val REV_FALL_MS = 700f
        /** Below this the car counts as stopped and the pedal owns the note. */
        const val ROLLING_KPH = 1.5f
        /** Over this much more speed, the gearbox takes it back completely. */
        const val HANDOVER_KPH = 6f
    }
}
