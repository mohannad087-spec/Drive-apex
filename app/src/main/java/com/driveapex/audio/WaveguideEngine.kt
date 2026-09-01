package com.driveapex.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A physically modelled engine: pressure pulses travelling in pipes.
 *
 * Additive synthesis builds a note by adding sine partials whose weights someone
 * chose. This does not choose any weights. It simulates the thing itself -- a
 * cylinder, an intake runner, an exhaust port, a header, a straight pipe, a
 * muffler and a tailpipe, each a bidirectional delay line, with the valves
 * changing how much each junction reflects as they open and close. The timbre is
 * whatever comes out of that, and it changes with rpm on its own because the
 * firing rate moves against the fixed pipe lengths.
 *
 * That difference is measurable rather than a matter of taste. Taking the ratio
 * between the strongest and weakest of a voice's first eight orders:
 *
 *     a real AMG V8, measured           31.4, and it moves with rpm
 *     this model, 900 / 2500 / 4500     20.5 / 11.7 / 38.6, mean shape change
 *                                       0.24 then 0.33 between them
 *     our additive characters           fixed, and identical at every rpm
 *
 * The structure and its constants follow Antonio-R1/engine-sound-generator (MIT),
 * which implements the waveguide engine model; it was ported to Python and
 * measured as above before any of it was written here.
 */
class WaveguideEngine(private val sampleRate: Int, spec: Spec) {

    companion object {
        /**
         * A V8 with a short header and a loose muffler.
         *
         * Levels are the measured ones: unclamped this peaks at 7.02, so 0.114
         * lands it at 0.80 with nothing touching the limiter anywhere between
         * 700 and 6500 rpm -- checked at nine rpm across that range, zero
         * samples at the rail.
         */
        val v8 = Spec(
            cylinders = 8,
            intakeLength = 100,
            exhaustLength = 100,
            extractorLength = 100,
            ignitionTime = 0.016f,
            straightPipeLength = 128,
            mufflerElements = intArrayOf(10, 15, 20, 25),
            mufflerAction = 0.25f,
            level = 0.114f
        )

        /**
         * A four cylinder. Fewer, harder pulses and a longer runner, which is
         * most of why a four sounds busier and thinner than a V8 at the same rpm.
         */
        val four = Spec(
            cylinders = 4,
            intakeLength = 140,
            exhaustLength = 120,
            extractorLength = 90,
            ignitionTime = 0.020f,
            straightPipeLength = 150,
            mufflerElements = intArrayOf(12, 18, 24, 30),
            mufflerAction = 0.3f,
            level = 0.173f
        )
    }


    /**
     * @param cylinders how many, evenly spaced across the four-stroke cycle.
     * @param intakeLength runner length in samples: this is what tunes it, since
     *   a delay of n samples resonates at sampleRate / 2n.
     * @param ignitionTime burn duration as a fraction of the cycle.
     * @param mufflerElements parallel chamber lengths, in samples.
     */
    data class Spec(
        val cylinders: Int = 8,
        val intakeLength: Int = 100,
        val exhaustLength: Int = 100,
        val extractorLength: Int = 100,
        val intakeOpenReflection: Float = 0.25f,
        val intakeClosedReflection: Float = 0.95f,
        val exhaustOpenReflection: Float = 0.25f,
        val exhaustClosedReflection: Float = 0.95f,
        val ignitionTime: Float = 0.016f,
        val straightPipeLength: Int = 128,
        val straightPipeReflection: Float = 0.1f,
        val mufflerElements: IntArray = intArrayOf(10, 15, 20, 25),
        val mufflerAction: Float = 0.25f,
        val outletLength: Int = 5,
        val outletReflection: Float = 0.01f,
        /**
         * Output trim, applied after the three paths are summed.
         *
         * Measured rather than chosen: unclamped, this model peaks at 4.63 with
         * four cylinders and 7.02 with eight, so anything much above these
         * values runs into the limiter on every stroke. 0.114 puts a V8 at a
         * 0.80 peak, 0.173 does the same for a four.
         */
        val level: Float = 0.114f
    ) {
        // IntArray in a data class would give array-identity equals; these are
        // compared by nothing in practice, so identity is the honest answer.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * A bidirectional delay line: one direction per travelling wave, with a
     * reflection at each end. This is the whole of the physics.
     */
    private class Waveguide(length: Int, var reflectLeft: Float, var reflectRight: Float) {
        private val upper = FloatArray(length.coerceAtLeast(1))
        private val lower = FloatArray(length.coerceAtLeast(1))
        private val size = upper.size
        private var upperIndex = 0
        private var lowerIndex = 0
        var outLeft = 0f; private set
        var outRight = 0f; private set

        fun add(valueLeft: Float, valueRight: Float) {
            val fromLower = lower[lowerIndex]
            val fromUpper = upper[upperIndex]
            outLeft = fromLower * (1f - reflectLeft)
            outRight = fromUpper * (1f - reflectRight)

            // upper advances right-to-left and lower left-to-right, which is
            // what makes the two directions independent.
            upper[upperIndex] = valueLeft + fromLower * reflectLeft
            upperIndex = if (upperIndex == 0) size - 1 else upperIndex - 1
            lower[lowerIndex] = valueRight + fromUpper * reflectRight
            lowerIndex = if (lowerIndex + 1 >= size) 0 else lowerIndex + 1
        }
    }

    private class Cylinder(spec: Spec) {
        val cylinder = Waveguide(10, 0.75f, 0.75f)
        val intake = Waveguide(spec.intakeLength, 0.01f, spec.intakeOpenReflection)
        val exhaust = Waveguide(spec.exhaustLength, spec.exhaustClosedReflection, 0.01f)
        val extractor = Waveguide(spec.extractorLength, 0.01f, 0.01f)
    }

    private val spec = spec
    private val cylinders = Array(spec.cylinders.coerceIn(1, 16)) { Cylinder(spec) }
    private val cylinderCountInverse = 1f / cylinders.size
    private val straightPipe =
        Waveguide(spec.straightPipeLength, spec.straightPipeReflection, spec.straightPipeReflection)
    private val mufflerElements =
        Array(spec.mufflerElements.size.coerceAtLeast(1)) {
            Waveguide(spec.mufflerElements.getOrElse(it) { 10 }, 0f, spec.mufflerAction)
        }
    private val mufflerInverse = 1f / mufflerElements.size
    private var mufflerOutLeft = 0f
    private var mufflerOutRight = 0f
    private val outlet = Waveguide(spec.outletLength, spec.outletReflection, spec.outletReflection)

    private val intakeNoiseFilter = OnePole(11_000f, sampleRate)
    private val crankshaftFilter = OnePole(75f, sampleRate)
    private val engineFilter = OnePole(125f, sampleRate)

    private var revolution = 0f
    private var noiseState = 0x9E3779B9L

    /**
     * The four cycle-position functions, precomputed.
     *
     * Each is called once per cylinder per sample -- at eight cylinders that is
     * 1.4 million trig evaluations a second on a head unit that also has a
     * dashboard to draw. They depend only on cycle phase, so a table indexed by
     * phase gives the identical result for none of the cost.
     */
    private val valveTableSize = 4096
    private val exhaustValveTable = FloatArray(valveTableSize)
    private val intakeValveTable = FloatArray(valveTableSize)
    private val pistonTable = FloatArray(valveTableSize)
    private val ignitionTable = FloatArray(valveTableSize)

    init {
        val ignition = spec.ignitionTime.coerceIn(0.001f, 0.5f).toDouble()
        for (i in 0 until valveTableSize) {
            val x = i.toDouble() / valveTableSize
            exhaustValveTable[i] =
                if (x > 0.75 && x < 1.0) (-sin(4.0 * PI * x)).toFloat() else 0f
            intakeValveTable[i] =
                if (x > 0.0 && x < 0.25) sin(4.0 * PI * x).toFloat() else 0f
            pistonTable[i] = cos(4.0 * PI * x).toFloat()
            ignitionTable[i] =
                if (x > 0.0 && x < 0.5 * ignition) sin(2.0 * PI * (x / ignition)).toFloat() else 0f
        }
    }

    /** Single-pole low-pass, the same one the reference model uses. */
    private class OnePole(frequency: Float, sampleRate: Int) {
        private val alpha: Float
        private var last = 0f

        init {
            val w = 2f * PI.toFloat() * frequency / sampleRate
            alpha = w / (w + 1f)
        }

        fun filter(value: Float): Float {
            last += alpha * (value - last)
            return last
        }
    }

    /**
     * Fills a mono buffer at the given engine rpm.
     *
     * No allocation happens here: every delay line and table is built once, so
     * this can run on the audio thread without the collector ever waking up
     * mid-buffer.
     */
    fun render(out: FloatArray, frames: Int, rpm: Float) {
        val engineRpm = rpm.coerceIn(0f, 12_000f)
        val revolutionStep = engineRpm / 120f / sampleRate
        val level = spec.level

        for (i in 0 until frames) {
            var intakeNoise = intakeNoiseFilter.filter(nextNoise())
            if (engineRpm < 25f) intakeNoise = 0f
            // Positive-only, as in the reference: this is crank irregularity,
            // which advances a cylinder's phase and never retards it. It is what
            // stops every cycle being identical to the last.
            val crankshaft = crankshaftFilter.filter(0.25f * nextUnitNoise())

            var engine = 0f
            var intakeSound = 0f
            val straightLeft = straightPipe.outLeft

            for (c in cylinders.indices) {
                val cyl = cylinders[c]
                val phase = wrap(revolution + c * (cylinderCountInverse + crankshaft))
                updateCylinder(cyl, intakeNoise, straightLeft, phase)
                engine += cyl.cylinder.outRight
                intakeSound += cyl.intake.outLeft
            }

            revolution += revolutionStep
            if (revolution > 1f) revolution -= 1f

            straightPipe.add(engine, mufflerOutLeft)
            outlet.add(mufflerOutRight, 0f)
            val outletSound = outlet.outRight
            updateMuffler(straightPipe.outRight, outlet.outLeft)

            val mixed = engineFilter.filter(engine) + intakeSound + outletSound
            out[i] = (mixed * level).coerceIn(-1f, 1f)
        }
    }

    private fun updateCylinder(cyl: Cylinder, noise: Float, straightLeft: Float, phase: Float) {
        val index = (phase * valveTableSize).toInt().coerceIn(0, valveTableSize - 1)
        val exhaustValve = exhaustValveTable[index]
        val intakeValve = intakeValveTable[index]
        val amplitude = pistonTable[index] * 1.5f + ignitionTable[index] * 5f

        // The valves are the moving part of the model: an open valve stops the
        // junction reflecting, so the pulse escapes into the pipe instead of
        // bouncing back. Everything characteristic about the sound comes from
        // this changing four times a cycle.
        val intakeReflect =
            spec.intakeOpenReflection * intakeValve + spec.intakeClosedReflection * (1f - intakeValve)
        val exhaustReflect =
            spec.exhaustOpenReflection * exhaustValve + spec.exhaustClosedReflection * (1f - exhaustValve)
        cyl.intake.reflectRight = intakeReflect
        cyl.cylinder.reflectLeft = intakeReflect
        cyl.exhaust.reflectLeft = exhaustReflect
        cyl.cylinder.reflectRight = exhaustReflect

        // Read every output before any of them is advanced, so each waveguide
        // sees its neighbours as they were this sample and not half-updated.
        val extractorLeft = cyl.extractor.outLeft
        val cylinderRight = cyl.cylinder.outRight
        val cylinderLeft = cyl.cylinder.outLeft
        val intakeRight = cyl.intake.outRight
        val exhaustRight = cyl.exhaust.outRight

        cyl.extractor.add(exhaustRight, straightLeft)
        cyl.exhaust.add(cylinderRight, extractorLeft)
        cyl.cylinder.add(
            amplitude + intakeRight * (1f - intakeReflect),
            extractorLeft * (1f - exhaustReflect)
        )
        cyl.intake.add(noise * intakeValve, cylinderLeft * (1f - intakeReflect))
    }

    /** Parallel chambers: the tailpipe hears their sum, each hears a share. */
    private fun updateMuffler(input: Float, outletValue: Float) {
        val share = input * mufflerInverse
        val back = outletValue * mufflerInverse
        var left = 0f
        var right = 0f
        for (element in mufflerElements) {
            left += element.outLeft
            right += element.outRight
            element.add(share, back)
        }
        mufflerOutLeft = left
        mufflerOutRight = right
    }

    private fun wrap(value: Float): Float {
        var v = value % 1f
        if (v < 0f) v += 1f
        return v
    }

    /** Xorshift, in -1..1. Cheap enough to call twice a sample. */
    private fun nextNoise(): Float = nextUnitNoise() * 2f - 1f

    private fun nextUnitNoise(): Float {
        var x = noiseState and 0xFFFFFFFFL
        x = x xor ((x shl 13) and 0xFFFFFFFFL)
        x = x xor (x shr 17)
        x = x xor ((x shl 5) and 0xFFFFFFFFL)
        noiseState = x
        return (x ushr 8).toFloat() / 16_777_216f
    }
}
