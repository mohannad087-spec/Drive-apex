package com.driveapex.audio

import android.content.Context
import com.driveapex.diag.DriveApexLog
import org.json.JSONObject

/**
 * Loads engine sample banks from the APK's assets.
 *
 * A bank is a JSON manifest under assets/enginebanks/ naming the recordings and
 * where each sits on the rpm grid:
 *
 *     {
 *       "id": "sport_petrol",
 *       "name": "Petrol Sport",
 *       "level": 0.9,
 *       "layers": [
 *         { "file": "sport_petrol/off_2000.ogg", "rpm": 2000, "load": 0,
 *           "loopStartMs": 120, "loopEndMs": 980 },
 *         { "file": "sport_petrol/on_2000.ogg",  "rpm": 2000, "load": 1 }
 *       ]
 *     }
 *
 * loopStartMs/loopEndMs may be omitted, in which case the whole file loops --
 * fine for a recording already trimmed to a seamless loop, and a click once per
 * pass for anything else.
 *
 * Files go through the platform decoder, so anything the device plays works:
 * ogg, m4a, mp3, wav. They are folded to mono and resampled to the engine's
 * rate at load, because playback rate here means rpm and nothing else may be
 * allowed to shift the pitch.
 */
object EngineSampleBankLoader {

    private const val DIR = "enginebanks"

    /** Every bank that parses and has enough recordings to play. Never throws. */
    fun loadAll(context: Context, sampleRate: Int): List<EngineSampleBank> {
        val names = runCatching { context.assets.list(DIR)?.toList().orEmpty() }
            .getOrElse {
                DriveApexLog.i("samples", "no $DIR asset directory")
                return emptyList()
            }
        val manifests = names.filter { it.endsWith(".json", ignoreCase = true) }
        if (manifests.isEmpty()) {
            DriveApexLog.i("samples", "no bank manifests in $DIR")
            return emptyList()
        }
        return manifests.mapNotNull { load(context, "$DIR/$it", sampleRate) }
    }

    fun load(context: Context, manifestPath: String, sampleRate: Int): EngineSampleBank? =
        runCatching {
            val text = context.assets.open(manifestPath).use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(text)
            val id = json.optString("id").ifBlank { manifestPath.substringAfterLast('/') }
            val entries = json.optJSONArray("layers") ?: return@runCatching null

            val layers = ArrayList<EngineSampleBank.Layer>(entries.length())
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val file = entry.optString("file")
                if (file.isBlank()) continue
                val decoded = AudioAssetDecoder.decodeMono(context, "$DIR/$file", sampleRate)
                if (decoded == null) {
                    DriveApexLog.i("samples", "$id: could not decode $file")
                    continue
                }
                val perMs = sampleRate / 1000.0
                val start = (entry.optDouble("loopStartMs", 0.0) * perMs).toInt()
                    .coerceIn(0, decoded.size)
                val end = entry.optDouble("loopEndMs", -1.0)
                    .let { if (it <= 0.0) decoded.size else (it * perMs).toInt() }
                    .coerceIn(start, decoded.size)
                val layer = EngineSampleBank.Layer(
                    rpm = entry.optDouble("rpm", 0.0).toFloat(),
                    pcm = decoded,
                    loopStart = start,
                    loopEnd = end,
                    load = entry.optDouble("load", 0.0).toFloat().coerceIn(0f, 1f)
                )
                if (layer.isUsable()) layers += layer
                else DriveApexLog.i("samples", "$id: $file unusable (rpm=${layer.rpm} loop=${layer.loopLength})")
            }

            val bank = EngineSampleBank(
                id = id,
                name = json.optString("name").ifBlank { id },
                layers = layers,
                level = json.optDouble("level", 1.0).toFloat()
            )
            if (!bank.isPlayable()) {
                // Two rpm points at one throttle end is the minimum that can be
                // crossfaded; one layer alone would be stretched across the
                // whole rev range and sound like a siren.
                DriveApexLog.i("samples", "$id: only ${layers.size} usable layers, needs 2 at one load")
                return@runCatching null
            }
            DriveApexLog.i("samples", "loaded bank $id with ${layers.size} layers")
            bank
        }.getOrElse {
            DriveApexLog.e("samples", "bank $manifestPath failed to load", it)
            null
        }

}
