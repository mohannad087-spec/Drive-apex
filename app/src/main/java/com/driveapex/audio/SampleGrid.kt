package com.driveapex.audio

import kotlin.math.abs

/** Selects nearby samples and returns smooth crossfade weights. */
class SampleGrid(private val samples: List<SampleDefinition>) {
    data class WeightedSample(val sample: SampleDefinition, val weight: Float, val pitchRatio: Float)

    fun select(rpm: Float, load: Float, speedKph: Float): List<WeightedSample> {
        if (samples.isEmpty()) return emptyList()

        val eligible = samples.filter {
            speedKph in it.minSpeedKph..it.maxSpeedKph
        }

        val candidates = if (eligible.isNotEmpty()) eligible else samples
        val scored = candidates.map { sample ->
            val rpmDistance = abs(rpm - sample.centerRpm) / 500f
            val loadDistance = abs(load - sample.centerLoad) * 2.5f
            val score = 1f / (1f + rpmDistance + loadDistance)
            val pitchRatio = (rpm / sample.pitchReferenceRpm).coerceIn(0.65f, 1.55f)
            WeightedSample(sample, score * sample.gain, pitchRatio)
        }

        val total = scored.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        return scored
            .map { it.copy(weight = (it.weight / total).coerceIn(0f, 1f)) }
            .sortedByDescending { it.weight }
            .take(3)
    }
}
