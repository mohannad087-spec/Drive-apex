package com.driveapex.audio

/**
 * Driver-facing adjustments layered on top of a character.
 *
 * The characters themselves stay fixed in code. Everything here is a multiplier
 * applied over one of them, which keeps a tuned result reproducible -- a preset
 * plus eight numbers -- and means a bad setting is always one reset away from
 * the original voice.
 *
 * The controls are grouped the way a driver hears the sound rather than the way
 * it is synthesised: nobody wants a fader per partial, but everybody knows
 * whether they want more rumble or less edge. Each band multiplies the gains of
 * the orders that fall inside it.
 */
data class CharacterTuning(
    /** Orders below 4: the weight you feel more than hear. */
    val bass: Float = 1f,
    /** Orders 4 to 12: where the engine's identity sits. */
    val body: Float = 1f,
    /** Orders above 12 plus the whine: brightness and rasp. */
    val edge: Float = 1f,
    /** Road and wind bed. */
    val road: Float = 1f,
    /** Waveshaper drive: clean at 0, aggressive at 2. */
    val grit: Float = 1f,
    /** Output trim. */
    val volume: Float = 1f,
    /** Scales the upshift point, so shifts come earlier or later. */
    val shiftRpm: Float = 1f,
    /** How hard the note drops on a shift. */
    val shiftKick: Float = 1f
) {
    fun isDefault(): Boolean = this == DEFAULT

    companion object {
        val DEFAULT = CharacterTuning()

        /** Ranges the tuning screen offers, and what each one means to a driver. */
        val CONTROLS: List<Control> = listOf(
            Control("BASS", "Low rumble and weight", 0f, 2f) { t, v -> t.copy(bass = v) },
            Control("BODY", "The engine's core voice", 0f, 2f) { t, v -> t.copy(body = v) },
            Control("EDGE", "Brightness and rasp", 0f, 2f) { t, v -> t.copy(edge = v) },
            Control("ROAD", "Road and wind noise", 0f, 2f) { t, v -> t.copy(road = v) },
            Control("GRIT", "Drive and distortion", 0f, 2f) { t, v -> t.copy(grit = v) },
            Control("VOLUME", "Overall level", 0f, 1.6f) { t, v -> t.copy(volume = v) },
            Control("SHIFT POINT", "Earlier or later upshifts", 0.6f, 1.5f) { t, v -> t.copy(shiftRpm = v) },
            Control("SHIFT KICK", "How hard a shift hits", 0f, 1.6f) { t, v -> t.copy(shiftKick = v) }
        )

        data class Control(
            val label: String,
            val hint: String,
            val min: Float,
            val max: Float,
            val apply: (CharacterTuning, Float) -> CharacterTuning
        )

        fun valueOf(tuning: CharacterTuning, label: String): Float = when (label) {
            "BASS" -> tuning.bass
            "BODY" -> tuning.body
            "EDGE" -> tuning.edge
            "ROAD" -> tuning.road
            "GRIT" -> tuning.grit
            "VOLUME" -> tuning.volume
            "SHIFT POINT" -> tuning.shiftRpm
            else -> tuning.shiftKick
        }
    }
}

/**
 * Produces the character actually rendered: this one with the tuning applied.
 *
 * The id is preserved so the renderer can tell that a live tuning change is
 * still the same engine and keep the gearbox mid-shift rather than dropping
 * back to first every time a slider moves.
 */
fun EngineCharacter.tunedWith(t: CharacterTuning): EngineCharacter {
    if (t.isDefault()) return this
    fun bandFor(order: Float): Float = when {
        order < 4f -> t.bass
        order <= 12f -> t.body
        else -> t.edge
    }
    return copy(
        orders = orders.map { o -> o.copy(gain = o.gain * bandFor(o.order)) },
        noise = noise.copy(
            baseGain = noise.baseGain * t.road,
            speedGain = noise.speedGain * t.road,
            loadGain = noise.loadGain * t.road
        ),
        whine = whine?.copy(gain = whine.gain * t.edge),
        drive = (drive * t.grit).coerceAtLeast(0.05f),
        level = level * t.volume,
        gearbox = gearbox?.copy(
            upshiftRpm = gearbox.upshiftRpm * t.shiftRpm,
            downshiftRpm = gearbox.downshiftRpm * t.shiftRpm,
            shiftCutDepth = (gearbox.shiftCutDepth * t.shiftKick).coerceIn(0f, 0.95f)
        )
    )
}
