package com.driveapex.audio

import kotlin.math.abs

/** Determines crossfade duration from how different the incoming voice is from the active voice. */
object SampleCrossfadePolicy {
    fun durationMs(current: SampleDefinition?, incoming: SampleDefinition?): Int {
        if (current == null || incoming == null) return 120
        val rpmDistance = abs(current.centerRpm - incoming.centerRpm) / 4200f
        val loadDistance = abs(current.centerLoad - incoming.centerLoad)
        val distance = (rpmDistance * 0.72f + loadDistance * 0.28f).coerceIn(0f, 1f)
        return (55f + distance * 135f).toInt().coerceIn(55, 190)
    }
}
