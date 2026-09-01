package com.driveapex.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager

/**
 * Which of the head unit's audio channels the engine comes out of.
 *
 * This matters more here than it would on a phone. The head unit mixes its
 * channels by its own rules: navigation ducks the radio and plays over it,
 * media is the radio and gets replaced by it, and an alarm cuts through
 * everything. Which one the engine should use is a question about how the
 * driver wants it to behave against the rest of the car, and only they can
 * answer it -- so it is a setting rather than a decision made here.
 *
 * Navigation stays the default because it is the one verified to play on this
 * vehicle while the radio is running.
 *
 * Two things beyond the stream number decide whether a channel is actually
 * heard, and the first version of this setting had neither, which is why only
 * navigation worked:
 *
 *  - **Attributes.** A track built from a bare legacy stream number is routed by
 *    a compatibility mapping. Navigation is fine that way -- 14 is BYD's own
 *    number and the mapping is theirs -- but on an automotive HAL every other
 *    channel is routed by AudioAttributes, and a track that declares none can
 *    land on an output that goes nowhere. So every channel except navigation is
 *    built from its usage instead of its number.
 *  - **Focus.** The radio holds the media channel. Playing onto it without
 *    asking for audio focus leaves the engine mixed under whatever is already
 *    there, or dropped. Navigation needs none, because ducking is what that
 *    channel is for.
 *
 * The stream number is still carried, because the volume of a channel is asked
 * for by number and a channel whose volume the driver has set to zero is silent
 * no matter how correctly the track is built.
 */
enum class AudioOutputChannel(
    val label: String,
    val detail: String,
    private val field: String,
    private val fallback: Int,
    /** AudioAttributes usage, or null to build the track from the stream number. */
    val usage: Int?,
    /** Audio focus to hold while playing, or null to ask for none. */
    val focusGain: Int?
) {
    NAVIGATION(
        "NAVIGATION",
        "Plays over the radio and ducks it. The verified route on this car.",
        "STREAM_NAVI", 14,
        usage = null,
        focusGain = null
    ),
    MEDIA(
        "MEDIA",
        "Shares the music channel, and takes it from the radio while it plays.",
        "STREAM_MUSIC", AudioManager.STREAM_MUSIC,
        usage = AudioAttributes.USAGE_MEDIA,
        focusGain = AudioManager.AUDIOFOCUS_GAIN
    ),
    ALARM(
        "ALARM",
        "Cuts through everything, and follows the alarm volume.",
        "STREAM_ALARM", AudioManager.STREAM_ALARM,
        usage = AudioAttributes.USAGE_ALARM,
        focusGain = AudioManager.AUDIOFOCUS_GAIN
    ),
    SYSTEM(
        "SYSTEM",
        "The channel the car's own beeps use.",
        "STREAM_SYSTEM", AudioManager.STREAM_SYSTEM,
        usage = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
        focusGain = null
    ),
    PHONE(
        "PHONE",
        "The call channel. Loud on its own, silent during a call.",
        "STREAM_VOICE_CALL", AudioManager.STREAM_VOICE_CALL,
        usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    );

    /** The platform's own number for this channel, by name where it has one. */
    fun streamType(): Int = runCatching {
        AudioManager::class.java.getField(field).getInt(null)
    }.getOrDefault(fallback)

    /**
     * How this channel's volume is set on the car right now, as current/max.
     *
     * A channel at zero is the commonest reason a correctly built track is
     * never heard, and it is invisible from inside the app unless something
     * asks. Read-only: nothing here changes a volume.
     */
    fun volume(context: Context): Pair<Int, Int> = runCatching {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = streamType()
        manager.getStreamVolume(stream) to manager.getStreamMaxVolume(stream)
    }.getOrDefault(-1 to -1)

    companion object {
        val DEFAULT = NAVIGATION

        private const val PREFS = "driveapex_audio"
        private const val KEY = "output_channel"

        fun load(context: Context): AudioOutputChannel {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
            return entries.firstOrNull { it.name == stored } ?: DEFAULT
        }

        fun save(context: Context, channel: AudioOutputChannel) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, channel.name).apply()
        }
    }
}
