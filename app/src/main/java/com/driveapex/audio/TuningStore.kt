package com.driveapex.audio

import android.content.Context

/** Per-character tuning, kept so a setting found on the road survives a restart. */
class TuningStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("driveapex_tuning", Context.MODE_PRIVATE)

    fun load(characterId: String): CharacterTuning {
        val d = CharacterTuning.DEFAULT
        return CharacterTuning(
            bass = get(characterId, "bass", d.bass),
            body = get(characterId, "body", d.body),
            edge = get(characterId, "edge", d.edge),
            road = get(characterId, "road", d.road),
            grit = get(characterId, "grit", d.grit),
            volume = get(characterId, "volume", d.volume),
            shiftRpm = get(characterId, "shiftRpm", d.shiftRpm),
            shiftKick = get(characterId, "shiftKick", d.shiftKick)
        )
    }

    fun save(characterId: String, t: CharacterTuning) {
        prefs.edit()
            .putFloat(key(characterId, "bass"), t.bass)
            .putFloat(key(characterId, "body"), t.body)
            .putFloat(key(characterId, "edge"), t.edge)
            .putFloat(key(characterId, "road"), t.road)
            .putFloat(key(characterId, "grit"), t.grit)
            .putFloat(key(characterId, "volume"), t.volume)
            .putFloat(key(characterId, "shiftRpm"), t.shiftRpm)
            .putFloat(key(characterId, "shiftKick"), t.shiftKick)
            .apply()
    }

    fun clear(characterId: String) {
        val e = prefs.edit()
        listOf("bass", "body", "edge", "road", "grit", "volume", "shiftRpm", "shiftKick")
            .forEach { e.remove(key(characterId, it)) }
        e.apply()
    }

    private fun get(id: String, name: String, fallback: Float) =
        runCatching { prefs.getFloat(key(id, name), fallback) }.getOrDefault(fallback)

    private fun key(id: String, name: String) = "$id.$name"
}
