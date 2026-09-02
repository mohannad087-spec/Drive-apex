package com.driveapex.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.driveapex.diag.DriveApexLog
import kotlin.math.floor
import kotlin.math.min

/**
 * Turns an audio asset into mono float samples at the engine's own rate.
 *
 * Shared by both things that read recordings -- the sample banks and the grain
 * sources -- because a second copy of a MediaCodec loop is a second place for
 * the same bug to live. Anything the device can play works: ogg, m4a, mp3, wav.
 */
object AudioAssetDecoder {

    /**
     * A guard against loading a whole album into RAM by accident.
     *
     * Thirty seconds covers both callers: a bank layer is a loop of a second or
     * two, and a grain source is a ten to twenty second rev.
     */
    private const val MAX_SECONDS = 30

    /**
     * Decodes an asset to mono float samples at the engine's rate.
     *
     * Stereo is folded down rather than kept: a repitched stereo pair drifts
     * apart as the rate changes, and the width in this engine comes from the
     * mix, not from the source file.
     */
    fun decodeMono(context: Context, path: String, targetRate: Int): FloatArray? {
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
            val cap = MAX_SECONDS * sourceRate

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
