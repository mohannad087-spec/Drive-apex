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
    )

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
        /**
         * Four gears, shifting every 1500 motor rpm.
         *
         * Ratios are chosen so the shift lands where it was asked for rather than
         * where a ratio happens to put it: virtual rpm is motor rpm times the
         * ratio, and the box shifts when that reaches upshiftRpm, so a shift at
         * motor rpm M needs the ratio upshiftRpm / M. With 6500 as the shift
         * point that gives 1500, 3000 and 4500 exactly, and fourth carries on
         * without shifting again.
         *
         * downshiftRpm sits below the lowest rpm any upshift drops to (3250, in
         * second), so the box cannot shift up and immediately back down.
         *
         * Nothing above this line is touched: the orders, resonances, noise bed,
         * drive and level that make this voice are exactly as they were.
         */
        gearbox = EngineCharacter.Gearbox(
            ratios = listOf(6500f / 1500f, 6500f / 3000f, 6500f / 4500f, 6500f / 6000f),
            upshiftRpm = 6500f,
            downshiftRpm = 2800f,
            idleRpm = 800f,
            limiterRpm = 7000f,
            shiftCutMs = 130,
            shiftCutDepth = 0.55f,
            shiftLockoutMs = 420
        )
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
        // The same four gears, shifting every 1500 motor rpm.
        gearbox = EngineCharacter.Gearbox(
            ratios = listOf(6500f / 1500f, 6500f / 3000f, 6500f / 4500f, 6500f / 6000f),
            upshiftRpm = 6500f, downshiftRpm = 2800f,
            idleRpm = 800f, limiterRpm = 7000f,
            shiftCutMs = 130, shiftCutDepth = 0.55f, shiftLockoutMs = 420
        )
    )



    /**
     * The two the driver kept. Everything else that was here -- two electric
     * voices, a V12 and two more measured characters -- was rejected on the
     * vehicle and is gone rather than left behind a button nobody presses.
     */
    val all = listOf(iceSport, measuredPetrol)
    val default = iceSport
}
