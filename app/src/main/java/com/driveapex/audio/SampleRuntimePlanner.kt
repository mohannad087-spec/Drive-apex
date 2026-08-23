package com.driveapex.audio

import com.driveapex.vehicle.VehicleData
import kotlin.math.abs
import kotlin.math.exp

/**
 * Chooses and weights recorded sample voices continuously from vehicle telemetry.
 * It is intentionally I/O agnostic: decoding and PCM output stay in the renderer.
 */
class SampleRuntimePlanner(private val samples: List<SampleDefinition>) {
    data class Plan(
        val voices: List<VoicePlan>,
        val crossfadeMs: Int = 80
    )

    data class VoicePlan(
        val sample: SampleDefinition,
        val gain: Float,
        val pitchRatio: Float
    )

    fun plan(data: VehicleData, maxVoices: Int = 3): Plan {
        if (samples.isEmpty()) return Plan(emptyList())

        val candidates = samples.asSequence()
            .filter { data.speedKph in it.minSpeedKph..it.maxSpeedKph }
            .map { sample ->
                val rpmError = abs(data.rpm - sample.centerRpm) / 4200f
                val load = data.normalizedThrottle()
                val loadError = abs(load - sample.centerLoad)
                val distance = rpmError * 0.72f + loadError * 0.28f
                sample to exp(-distance * 5.2f)
            }
            .sortedByDescending { it.second }
            .take(maxVoices.coerceAtLeast(1))
            .toList()

        val total = candidates.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        val voices = candidates.map { (sample, weight) ->
            val normalized = (weight / total).coerceIn(0f, 1f)
            val pitch = (data.rpm / sample.pitchReferenceRpm.coerceAtLeast(1f))
                .coerceIn(0.55f, 1.85f)
            VoicePlan(sample, normalized * sample.gain, pitch)
        }
        return Plan(voices)
    }
}
