package com.driveapex.audio

import android.content.Context
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
 * vehicle while the radio is running, and it is what the app has always used.
 *
 * The stream numbers are resolved by name where the platform has one, because a
 * constant's value is the platform's business and BYD adds its own. The literal
 * is the fallback for a head unit whose AudioManager does not expose the field,
 * and for STREAM_NAVI it is 14 -- the value read off this vehicle.
 */
enum class AudioOutputChannel(
    val label: String,
    val detail: String,
    private val field: String,
    private val fallback: Int
) {
    NAVIGATION(
        "NAVIGATION",
        "Plays over the radio, ducks it. The verified route on this car.",
        "STREAM_NAVI", 14
    ),
    MEDIA(
        "MEDIA",
        "Shares the music channel. The radio or Bluetooth can take it over.",
        "STREAM_MUSIC", AudioManager.STREAM_MUSIC
    ),
    ALARM(
        "ALARM",
        "Cuts through everything, and follows the alarm volume.",
        "STREAM_ALARM", AudioManager.STREAM_ALARM
    ),
    SYSTEM(
        "SYSTEM",
        "The channel the car's own beeps use.",
        "STREAM_SYSTEM", AudioManager.STREAM_SYSTEM
    ),
    PHONE(
        "PHONE",
        "The call channel. Loud on its own, silent during a call.",
        "STREAM_VOICE_CALL", AudioManager.STREAM_VOICE_CALL
    );

    /** The platform's own number for this channel, by name where it has one. */
    fun streamType(): Int = runCatching {
        AudioManager::class.java.getField(field).getInt(null)
    }.getOrDefault(fallback)

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
