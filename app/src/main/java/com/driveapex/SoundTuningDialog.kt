package com.driveapex

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.CharacterTuning
import com.driveapex.audio.EngineCharacter
import com.driveapex.audio.TuningStore
import java.util.Locale

/**
 * Live tuning for the active engine character.
 *
 * Every change takes effect on the next audio buffer, so this is meant to be
 * used while the car is moving: the driver hears the result immediately instead
 * of describing it and waiting for a new build. That loop was the real
 * bottleneck on sound work -- an edit, a CI build, a release, an install and a
 * drive for each adjustment, against a person who can simply hear whether it is
 * right.
 *
 * Settings are stored per character, so each voice keeps its own tuning.
 */
object SoundTuningDialog {

    fun show(
        activity: Activity,
        character: EngineCharacter,
        store: TuningStore,
        onChange: (CharacterTuning) -> Unit
    ) {
        var tuning = store.load(character.id)
        val hasGearbox = character.gearbox != null

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 22), dp(activity, 16), dp(activity, 22), dp(activity, 8))
        }

        content.addView(label(activity, character.name, 15f, Color.WHITE, true))
        content.addView(
            label(activity, "Changes apply immediately. Safe to adjust while driving.",
                10f, 0xFF8995A3.toInt(), false)
                .apply { setPadding(0, dp(activity, 4), 0, dp(activity, 12)) }
        )

        CharacterTuning.CONTROLS.forEach { control ->
            // The two shift controls mean nothing without a gearbox.
            val gearOnly = control.label == "SHIFT POINT" || control.label == "SHIFT KICK"
            if (gearOnly && !hasGearbox) return@forEach

            val readout = label(activity, "", 11f, 0xFF1D9BF0.toInt(), true).apply {
                gravity = Gravity.END
            }
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    label(activity, control.label, 11f, 0xFFE5EAF0.toInt(), true),
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                addView(readout)
            }
            content.addView(header)
            content.addView(
                label(activity, control.hint, 9f, 0xFF8995A3.toInt(), false)
                    .apply { setPadding(0, dp(activity, 1), 0, dp(activity, 2)) }
            )

            val steps = 100
            val start = CharacterTuning.valueOf(tuning, control.label)
            readout.text = format(start)
            val bar = SeekBar(activity).apply {
                max = steps
                progress = ((start - control.min) / (control.max - control.min) * steps)
                    .toInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = control.min + (control.max - control.min) * value / steps
                        readout.text = format(v)
                        tuning = control.apply(tuning, v)
                        onChange(tuning)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit

                    // Persist on release rather than on every pixel of travel.
                    override fun onStopTrackingTouch(sb: SeekBar?) = store.save(character.id, tuning)
                })
            }
            content.addView(bar, LinearLayout.LayoutParams(-1, dp(activity, 44)).apply {
                bottomMargin = dp(activity, 10)
            })
        }

        AlertDialog.Builder(activity)
            .setTitle("SOUND TUNING")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("DONE") { _, _ -> store.save(character.id, tuning) }
            .setNeutralButton("RESET") { _, _ ->
                store.clear(character.id)
                onChange(CharacterTuning.DEFAULT)
            }
            .show()
    }

    private fun format(value: Float): String =
        String.format(Locale.US, if (value >= 1f) "%.2f" else "%.2f", value)

    private fun label(a: Activity, text: String, size: Float, color: Int, bold: Boolean) =
        TextView(a).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun dp(a: Activity, value: Int) = (value * a.resources.displayMetrics.density).toInt()
}
