package com.driveapex.audio

import android.content.Context
import com.driveapex.diag.DriveApexLog
import org.json.JSONObject

/**
 * Loads grain sources from the APK's assets.
 *
 * A source is two files under assets/grains/ with the same name: the recording
 * itself, and a JSON map of the firing frequency through it:
 *
 *     corvette.ogg
 *     corvette.json   { "id": "corvette", "name": "Corvette V8", "load": 1,
 *                       "hopMs": 20, "lowHz": 38.9, "highHz": 146.7,
 *                       "hz": [41.2, 41.6, 42.1, ...] }
 *
 * Both are produced by tools/build_grain_source.py from an ordinary recording
 * of an engine revving. Nothing here measures anything: the analysis is done
 * once, off the car, where it can be checked.
 */
object GrainSourceLoader {

    private const val DIR = "grains"

    /** Every source that parses and has enough audio to play. Never throws. */
    fun loadAll(context: Context, sampleRate: Int): List<GrainSource> {
        val names = runCatching { context.assets.list(DIR)?.toList().orEmpty() }
            .getOrElse {
                DriveApexLog.i("grains", "no $DIR asset directory")
                return emptyList()
            }
        val manifests = names.filter { it.endsWith(".json", ignoreCase = true) }
        if (manifests.isEmpty()) {
            DriveApexLog.i("grains", "no grain manifests in $DIR")
            return emptyList()
        }
        return manifests.mapNotNull { load(context, it, sampleRate) }
    }

    private fun load(context: Context, manifest: String, sampleRate: Int): GrainSource? =
        runCatching {
            val text = context.assets.open("$DIR/$manifest").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val json = JSONObject(text)
            val id = json.optString("id").ifBlank { manifest.removeSuffix(".json") }
            val map = json.optJSONArray("hz") ?: return@runCatching null
            val hz = FloatArray(map.length()) { map.optDouble(it, 0.0).toFloat() }
            val loudness = json.optJSONArray("rms")
            // A source built before the loudness map existed still plays; every
            // hop then counts as loud enough, which is what it used to do.
            val rms = FloatArray(hz.size) { i ->
                loudness?.optDouble(i, 1.0)?.toFloat() ?: 1f
            }
            if (hz.isEmpty()) {
                DriveApexLog.i("grains", "$id: empty frequency map")
                return@runCatching null
            }

            val audio = AudioAssetDecoder.decodeMono(context, "$DIR/$id.ogg", sampleRate)
                ?: AudioAssetDecoder.decodeMono(context, "$DIR/$id.m4a", sampleRate)
                ?: AudioAssetDecoder.decodeMono(context, "$DIR/$id.wav", sampleRate)
            if (audio == null) {
                DriveApexLog.i("grains", "$id: no playable audio beside the map")
                return@runCatching null
            }

            val source = GrainSource(
                id = id,
                name = json.optString("name").ifBlank { id },
                credit = json.optString("credit"),
                pedalLanes = json.optBoolean("pedalLanes", false),
                load = json.optDouble("load", 1.0).toFloat(),
                pcm = audio,
                sampleRate = sampleRate,
                hz = hz,
                rms = rms,
                hopMs = json.optDouble("hopMs", 20.0).toFloat(),
                lowHz = json.optDouble("lowHz", 0.0).toFloat(),
                highHz = json.optDouble("highHz", 0.0).toFloat()
            )
            if (!source.isPlayable()) {
                DriveApexLog.i("grains", "$id: not playable (${audio.size} samples, " +
                    "${source.lowHz}-${source.highHz}Hz)")
                return@runCatching null
            }
            DriveApexLog.i("grains",
                "loaded $id: ${audio.size / sampleRate}s, ${source.lowHz}-${source.highHz}Hz, " +
                    "covers ${"%.2f".format(source.coverage())}x")
            source
        }.getOrElse {
            DriveApexLog.e("grains", "grain source $manifest failed to load", it)
            null
        }
}
