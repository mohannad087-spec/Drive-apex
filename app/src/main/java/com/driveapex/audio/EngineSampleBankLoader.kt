package com.driveapex.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.driveapex.diag.DriveApexLog
import kotlin.math.floor
import kotlin.math.min
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
    /** A guard against loading a whole album into RAM by accident. */
    private const val MAX_SECONDS_PER_LAYER = 20

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
                val file = entry.optString("file").ifBlank { continue }
                val decoded = decodeMono(context, "$DIR/$file", sampleRate)
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

    /**
     * Decodes an asset to mono float samples at the engine's rate.
     *
     * Stereo is folded down rather than kept: a repitched stereo pair drifts
     * apart as the rate changes, and the width in this engine comes from the
     * mix, not from the source file.
     */
    private fun decodeMono(context: Context, path: String, targetRate: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            context.assets.openFd(path).use { fd ->
                extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; format = candidate; break
                }
            }
            val source = format ?: return null
            extractor.selectTrack(track)

            val mime = source.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val sourceRate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val cap = MAX_SECONDS_PER_LAYER * sourceRate

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(source, null, null, 0)
                start()
            }
            val decoder = codec
            val info = MediaCodec.BufferInfo()
            val mono = ArrayList<Float>(min(cap, 4 * sourceRate))
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val buffer = decoder.getOutputBuffer(out)!!
                    val shorts = ShortArray(info.size / 2)
                    buffer.position(info.offset)
                    buffer.asShortBuffer().get(shorts)
                    var i = 0
                    while (i + channels <= shorts.size && mono.size < cap) {
                        var acc = 0f
                        for (c in 0 until channels) acc += shorts[i + c] / 32768f
                        mono += acc / channels
                        i += channels
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (mono.size >= cap) break
                } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Decoder has nothing left to give and nothing left to take.
                    break
                }
            }
            if (mono.isEmpty()) return null
            resample(mono.toFloatArray(), sourceRate, targetRate)
        } catch (t: Throwable) {
            DriveApexLog.e("samples", "decode failed for $path", t)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Rate conversion, done once at load.
     *
     * Cubic rather than linear for the same reason the player is: linear
     * interpolation is a low-pass, and applying one to every recording on the
     * way in dulls the whole bank before it is ever played.
     */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = from.toDouble() / to
        val length = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(length)
        for (i in out.indices) {
            val position = i * ratio
            val index = floor(position).toInt()
            val f = position - index
            val a = input[(index - 1).coerceIn(0, input.lastIndex)].toDouble()
            val b = input[index.coerceIn(0, input.lastIndex)].toDouble()
            val c = input[(index + 1).coerceIn(0, input.lastIndex)].toDouble()
            val d = input[(index + 2).coerceIn(0, input.lastIndex)].toDouble()
            out[i] = (0.5 * ((2.0 * b) + (-a + c) * f +
                (2.0 * a - 5.0 * b + 4.0 * c - d) * f * f +
                (-a + 3.0 * b - 3.0 * c + d) * f * f * f)).toFloat()
        }
        return out
    }
}
