package com.driveapex.vehicle

/** Source abstraction so BYD/DiLink integration can be added without changing the audio engine. */
interface VehicleDataProvider {
    fun current(): VehicleData
}
