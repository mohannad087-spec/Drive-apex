package com.driveapex.audio

/**
 * Parametric description of one engine character.
 *
 * Everything the renderer needs lives here, so switching between an authentic EV
 * and a combustion-style voice is a data change rather than a different code
 * path. The two are genuinely different instruments -- an EV's tone sits on high
 * magnetic orders with a quiet fundamental, a combustion engine on low firing
 * orders with a loud one -- but both are the same synthesis: a set of partials
 * at multiples of shaft rotation, a filtered noise bed, body resonances, and
 * transient hits.
 */
data class EngineCharacter(
    val id: String,
    val name: String,
    /** Tonal partials, expressed as multiples of shaft rotation (rpm / 60 Hz). */
    val orders: List<Order>,
    /** Body resonances the whole tonal mix is passed through. */
    val resonances: List<Resonance>,
    /** Road and wind bed. This is what makes the result sit in a cabin. */
    val noise: NoiseBed,
    /** High inverter/turbo whistle, or null for none. */
    val whine: Whine?,
    /** Waveshaper drive. Combustion wants far more of this than an EV. */
    val drive: Float,
    /** Overall output trim. */
    val level: Float,
    /**
     * Virtual gearbox, or null to drive the voice straight from motor rpm.
     * Only a geared engine has the rise-cut-drop shape; without this a
     * combustion voice is one continuous sweep to the motor's own redline.
     */
    val gearbox: Gearbox? = null
) {
    /**
     * How much louder the tonal sum gets at full load, weighted by each order's
     * share of it.
     *
     * loadGain is meant to change the balance between partials under throttle --
     * that is what makes an engine open up. But it multiplies their level too,
     * and with these values the sum is 2.28x louder at full load, which drove
     * the waveshaper from a musical saturation into a squared-off one. Measured
     * on the V12 at 3000 rpm: 33% of samples pinned at full load against 0.6%
     * at rest, which is the "excellent parked, artificial once moving" the
     * driver reported. The renderer divides by this so load changes the timbre
     * and leaves the level where it was.
     */
    val loadNormalisation: Float = orders.sumOf { it.gain.toDouble() }
        .let { total -> if (total <= 0.0) 1f
            else (orders.sumOf { it.gain.toDouble() * it.loadGain } / total).toFloat() }

    /**
     * One partial. `order` is relative to shaft rotation: 1.0 is one cycle per
     * revolution, 0.5 is the half-order of a four-stroke's firing, 24.0 is a
     * typical magnetic order of a permanent-magnet motor.
     */
    data class Order(
        val order: Float,
        val gain: Float,
        /** How much the partial grows with load, as a multiplier at full load. */
        val loadGain: Float = 1f,
        /** Fades in across this rpm window so partials do not switch on abruptly. */
        val fadeInRpm: Float = 0f,
        val fadeOutRpm: Float = 25_000f,
        val stereo: Float = 0f
    )

    /** A peaking resonance: what gives the sound a body rather than a tone. */
    data class Resonance(val hz: Float, val q: Float, val gain: Float)

    /**
     * Filtered noise standing in for road, tyre and wind. Its centre frequency
     * and level both rise with speed, which is most of what makes a cabin
     * recording sound like motion rather than a synthesiser.
     */
    data class NoiseBed(
        val baseGain: Float,
        val speedGain: Float,
        val loadGain: Float,
        val baseHz: Float,
        val hzPerKph: Float,
        val q: Float,
        /**
         * How many times the bed passes through its bandpass.
         *
         * One biquad rolls off at 6dB per octave, which leaves a great deal of
         * the white noise above the band still audible: measured at a 300Hz
         * centre with q 0.9, 8.33% of the bed's energy sits above 2kHz, and
         * that is heard as hiss over the engine rather than as road. A second
         * pass takes it to 0.18%.
         *
         * Default 1, so every character tuned before this is unchanged.
         */
        val cascade: Int = 1
    )

    /**
     * Ratios are applied to motor rpm to give a virtual engine rpm, so they
     * step down by a constant factor: every gear then reaches the shift point
     * and drops back to the same place, which is how a real gearset is spaced.
     */
    data class Gearbox(
        val ratios: List<Float>,
        val upshiftRpm: Float,
        val downshiftRpm: Float,
        val idleRpm: Float,
        val limiterRpm: Float,
        val shiftCutMs: Int = 130,
        val shiftCutDepth: Float = 0.55f,
        val shiftLockoutMs: Int = 420
    ) {
        companion object {
            /** Where a shift is asked for, and where the ratios are worked back from. */
            private const val SHIFT_AT = 6500f

            /**
             * A gearbox whose shifts land on the motor speeds given.
             *
             * Virtual rpm is motor rpm times the ratio and the box shifts when
             * that reaches upshiftRpm, so a shift at motor speed M needs the
             * ratio upshiftRpm / M. Writing the shift points down and deriving
             * the ratios is the way round that cannot be got wrong; choosing
             * ratios and hoping the shifts land somewhere sensible is the way
             * that put every shift in the wrong place the first time.
             *
             * The last entry is not a shift point but the motor speed top gear
             * reaches the shift rpm at -- 10500, because this motor passes
             * 10000 on the road and a voice pinned on a limiter for the last
             * third of the range is the thing we were fixing.
             */
            private fun boxFor(shiftPoints: List<Float>) = Gearbox(
                ratios = shiftPoints.map { SHIFT_AT / it },
                upshiftRpm = SHIFT_AT,
                // Below the lowest rpm any upshift drops to, so the box cannot
                // shift up and immediately back down.
                downshiftRpm = 2800f,
                idleRpm = 800f,
                limiterRpm = 7200f,
                shiftCutMs = 130,
                shiftCutDepth = 0.55f,
                shiftLockoutMs = 420
            )

            /** Eight gears, a shift every 1250 motor rpm. */
            fun eightSpeed() = boxFor(
                listOf(1250f, 2500f, 3750f, 5000f, 6250f, 7500f, 8750f, 10500f)
            )

            /**
             * Six gears, a shift every 1750 motor rpm.
             *
             * What the driver asked for on the voices built from their own
             * recordings. Fewer gears over the same motor range means each one
             * is held longer, which is the difference heard rather than the
             * count itself.
             */
            fun sixSpeed() = boxFor(
                listOf(1750f, 3500f, 5250f, 7000f, 8750f, 10500f)
            )
        }
    }

    /**
     * A high tone tracking rpm: inverter switching on an EV, turbo on an ICE.
     * Its order must not coincide with one already in `orders`, or it merely
     * makes that partial louder instead of adding an element of its own.
     */
    data class Whine(
        val order: Float,
        val gain: Float,
        val loadGain: Float,
        val minHz: Float = 400f,
        val maxHz: Float = 15_000f
    )
}

object EngineCharacters {


    /**
     * Combustion voice mapped onto motor rpm.
     *
     * Half-orders are what make an engine sound like an engine: a four-stroke
     * fires every second revolution, so a V8's dominant order is 4 and its
     * character comes from the 0.5/1.5/2.5 family underneath. Heavier drive for
     * the rasp, and a strong low resonance standing in for the exhaust.
     */
    val iceSport = EngineCharacter(
        id = "ice_sport",
        name = "Combustion Sport",
        orders = listOf(
            EngineCharacter.Order(order = 0.5f, gain = 0.20f, loadGain = 1.8f),
            EngineCharacter.Order(order = 1f, gain = 0.14f, loadGain = 1.7f, stereo = -0.2f),
            EngineCharacter.Order(order = 1.5f, gain = 0.17f, loadGain = 2.0f),
            EngineCharacter.Order(order = 2f, gain = 0.12f, loadGain = 2.0f, stereo = 0.2f),
            EngineCharacter.Order(order = 3f, gain = 0.13f, loadGain = 2.3f),
            EngineCharacter.Order(order = 4f, gain = 0.16f, loadGain = 2.6f, stereo = -0.3f),
            EngineCharacter.Order(order = 6f, gain = 0.10f, loadGain = 2.6f, stereo = 0.3f),
            EngineCharacter.Order(order = 8f, gain = 0.07f, loadGain = 2.4f, fadeInRpm = 400f)
        ),
        resonances = listOf(
            EngineCharacter.Resonance(hz = 90f, q = 0.8f, gain = 0.85f),
            EngineCharacter.Resonance(hz = 330f, q = 1.3f, gain = 0.55f),
            EngineCharacter.Resonance(hz = 1100f, q = 1.8f, gain = 0.35f)
        ),
        noise = EngineCharacter.NoiseBed(
            baseGain = 0.055f, speedGain = 0.14f, loadGain = 0.14f,
            baseHz = 300f, hzPerKph = 5.0f, q = 0.6f
        ),
        whine = null,
        drive = 1.6f,
        level = 0.95f,
        // Eight gears, shifting every 1250 motor rpm. Unchanged: the same set
        // this voice has had, now written once and shared.
        gearbox = EngineCharacter.Gearbox.eightSpeed()
    )


    /**
     * Petrol, with its harmonic balance measured off a real engine.
     *
     * The recordings we could get are too short and rev too fast to loop -- a
     * slice steady enough to loop drifts 27% in pitch inside itself -- so they
     * are used here as a measurement instead of as playback material, which two
     * seconds is plenty for.
     *
     * What the measurement showed is the difference between this and
     * Combustion Sport, and it is not subtle:
     *
     *     recording   0.5:0.14  1:1.00  1.5:0.88  2:0.69  3:0.35  4:0.54  6:0.35  8:0.12
     *     ice_sport   0.5:0.20  1:0.14  1.5:0.17  2:0.12  3:0.13  4:0.16  6:0.10  8:0.07
     *
     * A real engine has one dominant order with the rest falling away beneath
     * it. Ours was nearly flat -- every partial at roughly the same weight --
     * and a flat harmonic stack is what a synthesiser sounds like rather than
     * what an engine sounds like. The gains below are that measured shape,
     * scaled so the sum matches ice_sport's and the two can be compared at the
     * same loudness.
     *
     * Resonances are the broad spectral peaks from the same recording, less the
     * one at 82Hz, which is the engine's own fundamental rather than a body.
     *
     * ice_sport itself is untouched, as asked.
     */
    val measuredPetrol = EngineCharacter(
        id = "measured_petrol",
        name = "Measured Petrol",
        orders = listOf(
            EngineCharacter.Order(order = 0.5f, gain = 0.038f, loadGain = 1.8f),
            EngineCharacter.Order(order = 1f, gain = 0.268f, loadGain = 1.7f, stereo = -0.2f),
            EngineCharacter.Order(order = 1.5f, gain = 0.236f, loadGain = 2.0f),
            EngineCharacter.Order(order = 2f, gain = 0.185f, loadGain = 2.0f, stereo = 0.2f),
            EngineCharacter.Order(order = 3f, gain = 0.094f, loadGain = 2.3f),
            EngineCharacter.Order(order = 4f, gain = 0.145f, loadGain = 2.4f, stereo = -0.3f),
            EngineCharacter.Order(order = 6f, gain = 0.094f, loadGain = 2.5f, stereo = 0.3f),
            EngineCharacter.Order(order = 8f, gain = 0.032f, loadGain = 2.3f, fadeInRpm = 400f)
        ),
        resonances = listOf(
            EngineCharacter.Resonance(hz = 230f, q = 1.1f, gain = 0.70f),
            EngineCharacter.Resonance(hz = 390f, q = 1.3f, gain = 0.55f),
            EngineCharacter.Resonance(hz = 900f, q = 1.7f, gain = 0.34f),
            EngineCharacter.Resonance(hz = 1050f, q = 2.0f, gain = 0.26f)
        ),
        // Two passes, and only partly compensated for the level the second one
        // costs: 0.062 against the 0.070 that would match. The hiss reported on
        // this character was the bed's high end, and it is wanted quieter as
        // well as cleaner.
        noise = EngineCharacter.NoiseBed(
            baseGain = 0.062f, speedGain = 0.075f, loadGain = 0.12f,
            baseHz = 300f, hzPerKph = 3.0f, q = 0.9f, cascade = 2
        ),
        whine = null,
        drive = 1.6f,
        level = 0.95f,
        // The same eight gears.
        gearbox = EngineCharacter.Gearbox.eightSpeed()
    )



    /**
     * Corvette V8, measured off the driver's own two recordings.
     *
     * The recipe is the one that produced Measured Petrol -- the voice they call
     * excellent -- and not one number here was chosen by ear.
     * tools/measure_engine_profile.py tracks the firing frequency by
     * autocorrelation, keeps only the windows that are steady and loud, and
     * reads each order's level out of those windows relative to the
     * fundamental. Both clips were measured that way, with the lowest third of
     * each dropped (on the '74 that is the starter motor, which is not the
     * engine):
     *
     *     '74 small block  0.5:0.12  1:1.00  1.5:0.22  2:0.49  3:0.17  4:0.10  6:0.03  8:0.02
     *     C6 big cam       0.5:0.02  1:1.00  1.5:0.21  2:0.13  3:0.15  4:0.14  6:0.04  8:0.04
     *
     * They agree on what makes this engine what it is -- a dominant fundamental
     * with almost nothing on the half order, the opposite of a flat stack -- and
     * differ on the second order, which is the difference between a close mic on
     * a revving engine and an exhaust recording. The gains below are the
     * geometric mean of the two, scaled so they sum to the same 1.09 as the
     * other two characters, so all three compare at one loudness and the
     * waveshaper sees the same headroom it already does.
     *
     * The one place the measurement is not used as it stands is the
     * fundamental, and the reason is the speakers.
     *
     * Measured, this engine puts 0.584 of its amplitude on order 1. Through the
     * usable rev range that order sits between 25 and 120Hz, which door
     * speakers in a car largely do not reproduce -- so more than half the
     * amplitude budget was being spent on something nobody in the car can hear,
     * and the voice came out 23% quieter in the band that reaches the driver
     * than Measured Petrol, the one they call excellent.
     *
     * So the fundamental is cut to 0.320 and what it gives up is handed to the
     * orders above it in the proportions the recording measured. The engine's
     * character is untouched -- order 1 is still by far the loudest, and the
     * half order still almost absent, which is what a V8 is -- while the energy
     * above 100Hz now matches Measured Petrol's to within 2%. The sum stays at
     * 1.09, so the waveshaper sees exactly the headroom it did.
     *
     * Resonances are the fixed spectral peaks: what survives averaging a
     * spectrum while the engine revs, since a harmonic smears across the sweep
     * and a body does not. Measured at 371, 497 and 1128Hz. The tool also found
     * a strong one at 3176Hz and it is deliberately not used -- that band is
     * exactly where the hiss complaint on this app has always lived. The q
     * figures come from the family the other measured character uses: the width
     * measurement pinned itself at the clamp on every peak, so it was not
     * trustworthy enough to ship, while the frequencies were unambiguous.
     */
    val corvetteV8 = EngineCharacter(
        id = "corvette_v8",
        name = "Corvette V8",
        orders = listOf(
            EngineCharacter.Order(order = 0.5f, gain = 0.031f, loadGain = 1.8f),
            EngineCharacter.Order(order = 1f, gain = 0.320f, loadGain = 1.7f, stereo = -0.2f),
            EngineCharacter.Order(order = 1.5f, gain = 0.194f, loadGain = 2.0f),
            EngineCharacter.Order(order = 2f, gain = 0.233f, loadGain = 2.0f, stereo = 0.2f),
            EngineCharacter.Order(order = 3f, gain = 0.143f, loadGain = 2.3f),
            EngineCharacter.Order(order = 4f, gain = 0.106f, loadGain = 2.4f, stereo = -0.3f),
            EngineCharacter.Order(order = 6f, gain = 0.033f, loadGain = 2.5f, stereo = 0.3f),
            EngineCharacter.Order(order = 8f, gain = 0.030f, loadGain = 2.3f, fadeInRpm = 400f)
        ),
        resonances = listOf(
            EngineCharacter.Resonance(hz = 371f, q = 1.2f, gain = 0.62f),
            EngineCharacter.Resonance(hz = 497f, q = 1.4f, gain = 0.45f),
            EngineCharacter.Resonance(hz = 1128f, q = 1.7f, gain = 0.30f)
        ),
        // The cleaned-up bed from Measured Petrol, two passes and all: the hiss
        // fix is a property of the bed, not of the character it was found on.
        noise = EngineCharacter.NoiseBed(
            baseGain = 0.062f, speedGain = 0.075f, loadGain = 0.12f,
            baseHz = 300f, hzPerKph = 3.0f, q = 0.9f, cascade = 2
        ),
        whine = null,
        drive = 1.6f,
        level = 0.95f,
        // Six gears, as asked for on the voices built from the driver's own
        // recordings: a shift every 1750 motor rpm instead of every 1250.
        gearbox = EngineCharacter.Gearbox.sixSpeed()
    )

    /**
     * The two the driver kept, and the Corvette built from their recordings.
     * Everything else that was here -- two electric voices, a V12 and two more
     * measured characters -- was rejected on the vehicle and is gone rather
     * than left behind a button nobody presses.
     */
    val all = listOf(iceSport, measuredPetrol, corvetteV8)
    val default = iceSport
}
