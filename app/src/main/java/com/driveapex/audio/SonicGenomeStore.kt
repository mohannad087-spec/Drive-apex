package com.driveapex.audio

import android.content.Context

/** Small local persistence layer for the driver's Sonic Genome. */
class SonicGenomeStore(context: Context) {
    private val prefs = context.getSharedPreferences("driveapex_sonic_genome", Context.MODE_PRIVATE)

    fun load(): SonicGenome = SonicGenome(
        aggression = prefs.getFloat("aggression", 0.45f),
        smoothness = prefs.getFloat("smoothness", 0.60f),
        regenAffinity = prefs.getFloat("regenAffinity", 0.35f),
        highSpeedAffinity = prefs.getFloat("highSpeedAffinity", 0.30f),
        launchAffinity = prefs.getFloat("launchAffinity", 0.25f),
        observations = prefs.getLong("observations", 0L)
    )

    fun save(genome: SonicGenome) {
        prefs.edit()
            .putFloat("aggression", genome.aggression)
            .putFloat("smoothness", genome.smoothness)
            .putFloat("regenAffinity", genome.regenAffinity)
            .putFloat("highSpeedAffinity", genome.highSpeedAffinity)
            .putFloat("launchAffinity", genome.launchAffinity)
            .putLong("observations", genome.observations)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
