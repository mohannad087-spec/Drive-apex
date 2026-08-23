package com.driveapex.audio

import android.content.Context
import com.driveapex.vehicle.VehicleData

/** Coordinates per-drive learning with the persistent Sonic Genome. */
class SonicGenomeSession(context: Context) {
    private val store = SonicGenomeStore(context)
    private val liveSignature = DriverSonicSignature()
    private var genome = store.load()
    private var updatesSinceSave = 0

    fun update(data: VehicleData): SonicGenome {
        val live = liveSignature.update(data)
        genome = genome.blend(live, if (genome.maturity < 0.1f) 0.06f else 0.02f)
        updatesSinceSave++
        if (updatesSinceSave >= 25) {
            store.save(genome)
            updatesSinceSave = 0
        }
        return genome
    }

    fun current(): SonicGenome = genome

    fun reset() {
        genome = SonicGenome()
        updatesSinceSave = 0
        store.save(genome)
    }

    fun finishDrive() {
        store.save(genome)
        updatesSinceSave = 0
    }
}
