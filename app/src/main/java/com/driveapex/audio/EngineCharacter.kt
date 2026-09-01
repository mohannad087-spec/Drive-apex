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
        val q: Float
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
     * What the vehicle actually sounds like, made audible.
     *
     * Quiet fundamental, energy concentrated in high magnetic orders, a real
     * inverter whistle in the kHz, and a prominent road bed. Orders 24 and 48
     * are the usual stator-slot family on a permanent-magnet traction motor;
     * they are a starting point to tune by ear, not a measurement of this
     * specific motor.
     */
    val evRealistic = EngineCharacter(
        id = "ev_real",
        name = "EV Realistic",
        orders = listOf(
            EngineCharacter.Order(order = 1f, gain = 0.10f, loadGain = 1.4f),
            EngineCharacter.Order(order = 2f, gain = 0.06f, loadGain = 1.6f, stereo = -0.2f),
            EngineCharacter.Order(order = 12f, gain = 0.13f, loadGain = 2.0f, fadeInRpm = 400f),
            EngineCharacter.Order(order = 24f, gain = 0.20f, loadGain = 2.4f, fadeInRpm = 300f, stereo = 0.25f),
            EngineCharacter.Order(order = 36f, gain = 0.09f, loadGain = 2.2f, fadeInRpm = 600f, stereo = -0.3f),
            EngineCharacter.Order(order = 48f, gain = 0.07f, loadGain = 2.0f, fadeInRpm = 800f, stereo = 0.35f)
        ),
        resonances = listOf(
            EngineCharacter.Resonance(hz = 180f, q = 1.1f, gain = 0.55f),
            EngineCharacter.Resonance(hz = 720f, q = 1.6f, gain = 0.40f),
            EngineCharacter.Resonance(hz = 2400f, q = 2.2f, gain = 0.30f)
        ),
        noise = EngineCharacter.NoiseBed(
            baseGain = 0.035f, speedGain = 0.22f, loadGain = 0.05f,
            baseHz = 220f, hzPerKph = 7.5f, q = 0.7f
        ),
        whine = EngineCharacter.Whine(order = 72f, gain = 0.055f, loadGain = 2.2f, minHz = 1500f),
        drive = 0.55f,
        level = 0.85f
    )

    /**
     * The designed electric-GT voice: still electric, but arranged to be
     * musical. Stronger low orders for weight, a singing mid, and a whine kept
     * as colour rather than as the subject.
     */
    val evSport = EngineCharacter(
        id = "ev_sport",
        name = "EV Sport",
        orders = listOf(
            EngineCharacter.Order(order = 0.5f, gain = 0.16f, loadGain = 1.5f),
            EngineCharacter.Order(order = 1f, gain = 0.22f, loadGain = 1.6f),
            EngineCharacter.Order(order = 2f, gain = 0.15f, loadGain = 1.8f, stereo = -0.25f),
            EngineCharacter.Order(order = 3f, gain = 0.10f, loadGain = 2.0f, stereo = 0.25f),
            EngineCharacter.Order(order = 6f, gain = 0.09f, loadGain = 2.2f, fadeInRpm = 300f),
            EngineCharacter.Order(order = 12f, gain = 0.08f, loadGain = 2.2f, fadeInRpm = 500f, stereo = 0.3f),
            EngineCharacter.Order(order = 24f, gain = 0.06f, loadGain = 2.0f, fadeInRpm = 900f, stereo = -0.35f)
        ),
        resonances = listOf(
            EngineCharacter.Resonance(hz = 120f, q = 0.9f, gain = 0.70f),
            EngineCharacter.Resonance(hz = 450f, q = 1.4f, gain = 0.50f),
            EngineCharacter.Resonance(hz = 1800f, q = 2.0f, gain = 0.35f)
        ),
        noise = EngineCharacter.NoiseBed(
            baseGain = 0.030f, speedGain = 0.16f, loadGain = 0.06f,
            baseHz = 260f, hzPerKph = 6.0f, q = 0.8f
        ),
        whine = EngineCharacter.Whine(order = 42f, gain = 0.040f, loadGain = 2.0f, minHz = 1200f),
        drive = 0.85f,
        level = 0.92f
    )

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
     * AMG V12, geared.
     *
     * A twelve-cylinder four-stroke fires six times per crank revolution, so
     * order 6 is the voice and 3/9/12/18 are what make it dense rather than
     * hollow. The low half-orders carry the idle rumble a big twelve has and a
     * four-cylinder does not.
     *
     * Ratios step by 0.7, with the shift point at 7000 virtual rpm. First gear
     * multiplies by 3.5, so the first upshift lands at 2000 motor rpm as asked,
     * and each later one follows from the spacing: roughly 2860, 4080, 5830,
     * 8330 and 11900 motor rpm. Every shift drops the note back to about 4900.
     */
    val mercedesV12 = EngineCharacter(
        id = "amg_v12",
        name = "Mercedes AMG V12",
        orders = listOf(
            EngineCharacter.Order(order = 0.5f, gain = 0.13f, loadGain = 1.7f),
            EngineCharacter.Order(order = 1.5f, gain = 0.11f, loadGain = 1.8f, stereo = -0.15f),
            EngineCharacter.Order(order = 3f, gain = 0.17f, loadGain = 2.1f),
            EngineCharacter.Order(order = 4.5f, gain = 0.09f, loadGain = 2.2f, stereo = 0.2f),
            EngineCharacter.Order(order = 6f, gain = 0.26f, loadGain = 2.6f),
            EngineCharacter.Order(order = 9f, gain = 0.11f, loadGain = 2.5f, stereo = -0.25f),
            EngineCharacter.Order(order = 12f, gain = 0.15f, loadGain = 2.6f, stereo = 0.25f),
            EngineCharacter.Order(order = 18f, gain = 0.08f, loadGain = 2.4f, fadeInRpm = 900f),
            EngineCharacter.Order(order = 24f, gain = 0.05f, loadGain = 2.2f, fadeInRpm = 1400f, stereo = -0.3f)
        ),
        resonances = listOf(
            EngineCharacter.Resonance(hz = 85f, q = 0.75f, gain = 0.90f),
            EngineCharacter.Resonance(hz = 300f, q = 1.2f, gain = 0.60f),
            EngineCharacter.Resonance(hz = 900f, q = 1.7f, gain = 0.38f),
            EngineCharacter.Resonance(hz = 2600f, q = 2.4f, gain = 0.20f)
        ),
        noise = EngineCharacter.NoiseBed(
            // The bed rises with road speed, and at 0.15 with a centre sweeping
            // 5.5Hz per km/h it became a wash of high bandpassed noise -- heard
            // as hiss growing with speed rather than as a cabin. Halved, swept
            // less far, and narrowed so it reads as road rather than as static.
            baseGain = 0.050f, speedGain = 0.075f, loadGain = 0.16f,
            baseHz = 280f, hzPerKph = 3.0f, q = 0.9f
        ),
        whine = null,
        drive = 1.9f,
        level = 0.95f,
        gearbox = EngineCharacter.Gearbox(
            ratios = listOf(3.5f, 2.45f, 1.715f, 1.20f, 0.84f, 0.588f, 0.412f),
            upshiftRpm = 7000f,
            downshiftRpm = 2600f,
            idleRpm = 700f,
            limiterRpm = 7300f,
            shiftCutMs = 140,
            shiftCutDepth = 0.6f,
            shiftLockoutMs = 450
        )
    )

    val all = listOf(evRealistic, evSport, iceSport, mercedesV12)
    val default = evRealistic
}
