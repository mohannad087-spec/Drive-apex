package com.driveapex.audio

import com.driveapex.vehicle.VehicleData

/** Immutable render decision passed to the eventual PCM sample renderer. */
data class SampleRenderPlan(
    val body: List<SampleGrid.WeightedSample>,
    val regen: List<SampleGrid.WeightedSample>,
    val launch: List<SampleGrid.WeightedSample>,
    val interiorGain: Float,
    val exteriorGain: Float
)

class SampleRenderPlanner(
    private val bank: List<SampleDefinition> = SampleBankManifest.premiumGt
) {
    private val grid = SampleGrid(bank)

    fun plan(data: VehicleData): SampleRenderPlan {
        val load = data.normalizedThrottle()
        val regen = data.normalizedRegen()
        val body = grid.select(data.rpm, load, data.speedKph)
        val regenSamples = if (regen > 0.02f) {
            grid.select(data.rpm, regen, data.speedKph)
                .filter { it.sample.id == "regen" }
        } else emptyList()
        val launchSamples = if (data.speedKph < 80f && load > 0.78f) {
            grid.select(data.rpm, load, data.speedKph)
                .filter { it.sample.transient && it.sample.id == "launch" }
        } else emptyList()

        val exterior = (0.45f + data.speedKph / 420f).coerceIn(0.45f, 1f)
        val interior = (0.90f - data.speedKph / 600f).coerceIn(0.45f, 0.90f)
        return SampleRenderPlan(body, regenSamples, launchSamples, interior, exterior)
    }
}
