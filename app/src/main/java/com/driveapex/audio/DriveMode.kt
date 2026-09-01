package com.driveapex.audio

/**
 * Eco, Normal or Sport, and what each one does to the voice.
 *
 * The gear ratios never change -- they are the mechanical part, and a car does
 * not grow different gears when you press a button. What changes is when the box
 * decides to use them, which is exactly what a real drive mode changes.
 *
 * Because a shift happens when motor rpm times the gear ratio reaches
 * `upshiftRpm`, lowering that figure moves every shift to a lower motor speed at
 * once. On the eight-speed set the three modes come out as:
 *
 *     ECO     shifts at motor  808 1615 2423 3231 4038 4846 5654
 *     NORMAL  shifts at motor 1000 2000 3000 4000 5000 6000 7000
 *     SPORT   shifts at motor 1250 2500 3750 5000 6250 7500 8750
 *
 * Eco upshifts early and hangs on to the tall gear; Sport holds each one to the
 * top of its range. That is the whole difference, and it is the right one.
 */
enum class DriveMode(
    val label: String,
    /** Virtual rpm an upshift triggers at. */
    val upshiftRpm: Float,
    /** Virtual rpm a downshift triggers at; must stay below the lowest an upshift drops to. */
    val downshiftRpm: Float,
    /**
     * Output trim. Eco is quieter and Sport is louder, but only by a little:
     * these multiply the character's own level and are not meant to be a
     * different voice, just a different temper.
     */
    val levelScale: Float,
    /** How hard the torque cut bites on a shift. Sport snaps, Eco slurs. */
    val shiftCutScale: Float
) {
    ECO("ECO", upshiftRpm = 4200f, downshiftRpm = 2000f, levelScale = 0.85f, shiftCutScale = 0.7f),
    NORMAL("NORMAL", upshiftRpm = 5200f, downshiftRpm = 2400f, levelScale = 1.0f, shiftCutScale = 1.0f),
    SPORT("SPORT", upshiftRpm = 6500f, downshiftRpm = 2800f, levelScale = 1.12f, shiftCutScale = 1.25f);

    /**
     * The character's gearbox with this mode's shift points in it.
     *
     * Downshift is additionally held below the lowest rpm any upshift drops to,
     * so a mode can never be configured into hunting between two gears: on the
     * eight-speed set that floor is 3250, and 2800 is the highest any mode asks
     * for anyway.
     */
    fun applyTo(gearbox: EngineCharacter.Gearbox): EngineCharacter.Gearbox {
        val lowestAfterUpshift = lowestRpmAfterUpshift(gearbox.ratios, upshiftRpm)
        return gearbox.copy(
            upshiftRpm = upshiftRpm,
            downshiftRpm = downshiftRpm.coerceAtMost(lowestAfterUpshift * 0.85f),
            shiftCutDepth = (gearbox.shiftCutDepth * shiftCutScale).coerceIn(0f, 0.9f)
        )
    }

    companion object {
        /**
         * Where the box lands after the harshest upshift, in virtual rpm.
         *
         * Shifting up at `upshift` in gear n means the motor is at
         * upshift / ratio[n], and the next gear immediately reads that same
         * motor speed through its own ratio.
         */
        private fun lowestRpmAfterUpshift(ratios: List<Float>, upshift: Float): Float {
            var lowest = Float.MAX_VALUE
            for (i in 0 until ratios.lastIndex) {
                if (ratios[i] <= 0f) continue
                val motor = upshift / ratios[i]
                lowest = minOf(lowest, motor * ratios[i + 1])
            }
            return if (lowest == Float.MAX_VALUE) upshift else lowest
        }

        fun fromName(name: String?): DriveMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}
