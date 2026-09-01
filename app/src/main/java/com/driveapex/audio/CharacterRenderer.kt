package com.driveapex.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Renders an EngineCharacter to PCM.
 *
 * Written to fix four defects measured in the previous renderer, each of which
 * was wrong regardless of the character being voiced:
 *
 *  - The inverter whine came out at 16.8 Hz instead of 8.8 kHz. Its phase
 *    accumulator held cycles and was then multiplied by 2*pi*f/1000, so the
 *    frequency it produced was the accumulator's own rate times f/1000 -- the
 *    intended tone divided by about 525, well below hearing.
 *  - Every transient "hit" was built the same way and landed at 2-5 Hz, so the
 *    launch, lift-off and brake events were slow wobbles rather than sounds.
 *  - That accumulator wrapped at 1.0 while being multiplied by a non-integer,
 *    so the phase jumped by a fraction of a cycle roughly twice a second: an
 *    audible click.
 *  - Road and wind noise was 0.5% of the mix behind a 126 Hz lowpass, which is
 *    inaudible, and it is the element that makes a cabin sound like motion.
 *
 * Frequency is also interpolated per sample. Previously it was computed once
 * per 1536-sample buffer, so pitch moved in 29 steps a second instead of
 * gliding, which is audible on any quick change of rpm.
 */
class CharacterRenderer(private val sampleRate: Int = 44_100) {

    /**
     * Everything a render pass needs, swapped as one object.
     *
     * The pieces have to change together. Rebuilding them in place crashed the
     * audio thread: setCharacter cleared the filter list and refilled it, and a
     * render landing in that gap read index 0 of an empty list. The tuning
     * screen calls setCharacter on every slider movement, from the UI thread,
     * against a render running continuously on its own -- so the window was
     * being hit in ordinary use.
     *
     * A new voice is built complete and published with a single volatile write;
     * the render reads the reference once and works from that. No locks on the
     * audio path, and no way to observe a half-built state.
     */
    private class Voice(
        val character: EngineCharacter,
        val phases: DoubleArray,
        val filters: Array<Biquad>,
        val gearbox: VirtualGearbox?
    )

    @Volatile private var voice: Voice = newVoice(EngineCharacters.default, null)

    private var whinePhase = 0.0
    private var noiseState = 0x4D595DF4L

    private val noiseFilter = Biquad()
    private val hitFilter = Biquad()

    // Smoothed control values. The renderer glides toward the latest telemetry
    // rather than stepping to it once per buffer.
    private var rpmNow = 700.0
    private var loadNow = 0.10
    private var speedNow = 0.0

    private var hitEnvelope = 0.0
    private var hitTone = 0.0
    private var previousEventSum = 0.0

    @Volatile private var gear = 0
    /** Elapsed time counted from rendered frames, so shift timing does not
     *  depend on the wall clock and stays identical across runs. */
    private var elapsedMs = 0L

    fun setCharacter(value: EngineCharacter) {
        voice = newVoice(value, voice)
    }

    /** Current virtual gear, 1-based, or 0 when the character has no gearbox. */
    fun currentGear(): Int = if (voice.gearbox == null) 0 else gear + 1

    fun currentCharacter(): EngineCharacter = voice.character

    private fun newVoice(value: EngineCharacter, previous: Voice?): Voice {
        // Rebuilding the gearbox resets to first gear, which would drop the
        // engine back to idle every time a tuning slider moves. Keep the running
        // box when this is the same engine with the same ratios.
        val spec = value.gearbox
        val running = previous?.gearbox
        val box = when {
            spec == null -> null
            running != null && spec.ratios == previous.character.gearbox?.ratios ->
                running.also { it.retune(spec) }
            else -> VirtualGearbox(spec)
        }
        if (box !== running) gear = 0

        // Carry oscillator phases across so a tuning change is not a click.
        val phases = DoubleArray(value.orders.size)
        previous?.phases?.let { old ->
            for (i in phases.indices) if (i < old.size) phases[i] = old[i]
        }
        return Voice(value, phases, Array(value.resonances.size) { Biquad() }, box)
    }

    /**
     * Fills one stereo buffer.
     *
     * Targets are the newest telemetry; the renderer approaches them sample by
     * sample so a change of rpm is a glide and never a step.
     */
    fun render(
        pcm: ShortArray,
        frames: Int,
        rpmTarget: Float,
        loadTarget: Float,
        speedTarget: Float,
        scene: AudioScene,
        events: AcousticEventComposer.Events
    ) {
        // Read the shared state exactly once. Everything below works from this
        // snapshot, so a swap mid-buffer changes the next pass, never this one.
        val v = voice
        val c = v.character
        val phases = v.phases
        val bodyFilters = v.filters

        val sceneGain = sceneGain(scene)
        val nyquist = sampleRate * 0.5

        // The gearbox turns the motor's single sweep into a virtual engine's
        // stepped rev range, so from here on `rpm` means virtual engine rpm.
        elapsedMs += (frames * 1000L) / sampleRate
        var shiftCut = 1.0
        var voiceRpm = rpmTarget
        v.gearbox?.let { box ->
            val state = box.update(rpmTarget, elapsedMs)
            voiceRpm = state.virtualRpm
            gear = state.gear
            if (state.shifted != 0) {
                // Jump rather than glide: the pitch change belongs inside the
                // torque cut, where it is covered, not smeared across it.
                rpmNow = state.virtualRpm.toDouble()
                hitEnvelope = (hitEnvelope + 0.75).coerceAtMost(1.0)
                hitTone = if (state.shifted > 0) 190.0 else 260.0
            }
            shiftCut = box.cutGain(elapsedMs).toDouble()
        }

        // Trigger a transient when the event sum rises. Events themselves are
        // level-like, so the rising edge is what marks a hit.
        val eventSum = (events.launch * 1.0 + events.accelerationHit * 0.7 +
            events.liftOff * 0.55 + events.regenerationHit * 0.7 + events.brakeHit * 0.6).toDouble()
        val rise = (eventSum - previousEventSum).coerceAtLeast(0.0)
        previousEventSum = eventSum
        if (rise > 0.02) {
            hitEnvelope = (hitEnvelope + rise).coerceAtMost(1.0)
            hitTone = 140.0 + rpmNow * 0.05 + events.brakeHit * 900.0
        }

        // Resonance and noise coefficients move slowly; once per buffer is
        // plenty and keeps the per-sample cost down.
        c.resonances.forEachIndexed { i, r ->
            if (i < bodyFilters.size) bodyFilters[i].bandpass(r.hz.toDouble(), r.q.toDouble(), sampleRate)
        }
        val noiseHz = (c.noise.baseHz + c.noise.hzPerKph * speedNow)
            .coerceIn(80.0, nyquist * 0.85)
        noiseFilter.bandpass(noiseHz, c.noise.q.toDouble(), sampleRate)
        hitFilter.bandpass(hitTone.coerceIn(90.0, nyquist * 0.8), 1.4, sampleRate)

        val rpmStep = (voiceRpm - rpmNow) / frames
        val loadStep = (loadTarget - loadNow) / frames
        val speedStep = (speedTarget - speedNow) / frames

        for (i in 0 until frames) {
            rpmNow += rpmStep
            loadNow += loadStep
            speedNow += speedStep

            val rotationHz = (rpmNow / 60.0).coerceAtLeast(0.5)
            var left = 0.0
            var right = 0.0

            c.orders.forEachIndexed { index, order ->
                val hz = rotationHz * order.order

                // Advance the phase for every order on every sample, whatever its
                // gain. A silent partial still has to keep its place in the cycle.
                // This used to sit below the two early returns, so an order that
                // faded out stopped advancing, and when it faded back in it
                // re-entered at a phase unrelated to where a continuous sine would
                // have been -- a step in the waveform, which is a click. Orders
                // fade in and out constantly as the RPM moves, and a stream of
                // those clicks is heard as a rasp over the engine note.
                phases[index] += 2.0 * PI * hz / sampleRate
                if (phases[index] >= 2.0 * PI) phases[index] -= 2.0 * PI

                // Fade a partial out before it reaches Nyquist rather than
                // letting it alias into an unrelated pitch.
                val antiAlias = when {
                    hz >= nyquist * 0.92 -> 0.0
                    hz >= nyquist * 0.70 -> (nyquist * 0.92 - hz) / (nyquist * 0.22)
                    else -> 1.0
                }
                if (antiAlias <= 0.0) return@forEachIndexed

                val band = rpmBand(rpmNow, order.fadeInRpm.toDouble(), order.fadeOutRpm.toDouble())
                if (band <= 0.0) return@forEachIndexed

                val loadFactor = 1.0 + (order.loadGain - 1.0) * loadNow.coerceIn(0.0, 1.0)
                val amp = order.gain * loadFactor * band * antiAlias
                val wave = sin(phases[index]) * amp
                val pan = order.stereo.toDouble().coerceIn(-1.0, 1.0)
                left += wave * (1.0 - pan * 0.5)
                right += wave * (1.0 + pan * 0.5)
            }

            // Body: the tonal sum through peaking resonances is the difference
            // between a tone and something with a shape around it.
            var bodyL = left
            var bodyR = right
            c.resonances.forEachIndexed { i, r ->
                val f = bodyFilters[i]
                bodyL += f.processL(left) * r.gain
                bodyR += f.processR(right) * r.gain
            }

            noiseState = noiseStep(noiseState)
            val white = (noiseState and 0xFFFFL) / 32767.5 - 1.0
            val bed = noiseFilter.processL(white) *
                (c.noise.baseGain + c.noise.speedGain * (speedNow / 160.0) +
                    c.noise.loadGain * loadNow)

            val whineValue = c.whine?.let { w ->
                val hz = (rotationHz * w.order).coerceIn(w.minHz.toDouble(), w.maxHz.toDouble())
                if (hz >= nyquist * 0.92) 0.0 else {
                    whinePhase += 2.0 * PI * hz / sampleRate
                    if (whinePhase >= 2.0 * PI) whinePhase -= 2.0 * PI
                    val g = w.gain * (1.0 + (w.loadGain - 1.0) * loadNow.coerceIn(0.0, 1.0))
                    sin(whinePhase) * g
                }
            } ?: 0.0

            // Transient: a decaying noise burst through a resonant filter, which
            // is what a hit actually is. The previous version summed slow sines
            // and produced nothing audible.
            var hit = 0.0
            if (hitEnvelope > 0.0001) {
                hit = hitFilter.processR(white) * hitEnvelope * 0.5
                hitEnvelope *= 0.9992
            }

            val mix = c.level * sceneGain * shiftCut
            val outL = (bodyL + bed + whineValue + hit) * mix
            val outR = (bodyR + bed * 0.92 + whineValue * 0.95 + hit * 1.05) * mix

            pcm[i * 2] = clip(outL, c.drive)
            pcm[i * 2 + 1] = clip(outR, c.drive)
        }
    }

    private fun clip(value: Double, drive: Float): Short {
        val shaped = tanh(value * (0.8 + drive))
        return (shaped * 32_600.0).coerceIn(-32_768.0, 32_767.0).toInt().toShort()
    }

    private fun sceneGain(scene: AudioScene): Double = when (scene) {
        AudioScene.IDLE -> 0.62
        AudioScene.COAST -> 0.72
        AudioScene.ACCELERATION -> 0.95
        AudioScene.HARD_ACCELERATION -> 1.08
        AudioScene.REGENERATION -> 0.80
        AudioScene.LAUNCH -> 1.12
        AudioScene.HIGH_SPEED -> 1.00
    }

    /** Smoothstep fade so a partial never switches on or off abruptly. */
    private fun rpmBand(rpm: Double, fadeIn: Double, fadeOut: Double): Double {
        if (rpm >= fadeOut) return 0.0
        if (fadeIn <= 0.0) return 1.0
        if (rpm >= fadeIn) return 1.0
        val t = (rpm / fadeIn).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun noiseStep(state: Long): Long {
        var x = state and 0xFFFFFFFFL
        x = x xor ((x shl 13) and 0xFFFFFFFFL)
        x = x xor (x shr 17)
        x = x xor ((x shl 5) and 0xFFFFFFFFL)
        return x and 0xFFFFFFFFL
    }

    /**
     * Direct-form-1 biquad with independent left and right state, so one filter
     * object can carry both channels without them bleeding into each other.
     */
    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var xl1 = 0.0; private var xl2 = 0.0; private var yl1 = 0.0; private var yl2 = 0.0
        private var xr1 = 0.0; private var xr2 = 0.0; private var yr1 = 0.0; private var yr2 = 0.0

        /**
         * Constant-skirt bandpass. The resonances are summed back onto the dry
         * signal rather than replacing it, so a band lift is exactly what is
         * wanted here and no separate peaking form is needed.
         */
        fun bandpass(hz: Double, q: Double, sampleRate: Int) {
            val w = 2.0 * PI * hz / sampleRate
            val alpha = sin(w) / (2.0 * q.coerceAtLeast(0.05))
            val a0 = 1.0 + alpha
            b0 = alpha / a0; b1 = 0.0; b2 = -alpha / a0
            a1 = -2.0 * cos(w) / a0; a2 = (1.0 - alpha) / a0
        }

        fun processL(x: Double): Double {
            val y = b0 * x + b1 * xl1 + b2 * xl2 - a1 * yl1 - a2 * yl2
            xl2 = xl1; xl1 = x; yl2 = yl1; yl1 = y
            return if (y.isFinite()) y else 0.0
        }

        fun processR(x: Double): Double {
            val y = b0 * x + b1 * xr1 + b2 * xr2 - a1 * yr1 - a2 * yr2
            xr2 = xr1; xr1 = x; yr2 = yr1; yr1 = y
            return if (y.isFinite()) y else 0.0
        }
    }
}
