package com.driveapex

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.driveapex.audio.EngineSoundEngine
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val engine = EngineSoundEngine()
    private lateinit var rpmLabel: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text = "DRIVE APEX"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 18)
        }
        root.addView(title)

        rpmLabel = TextView(this).apply {
            text = formatRpm(900f)
            textSize = 42f
            setTextColor(Color.rgb(255, 179, 0))
        }
        root.addView(rpmLabel)

        val rpmBar = SeekBar(this).apply {
            max = 6300
            progress = 200
        }
        root.addView(rpmBar, LinearLayout.LayoutParams(-1, 80))

        startButton = Button(this).apply {
            text = "START ENGINE SOUND"
            setOnClickListener {
                engine.start()
                engine.setRpm(currentRpm(rpmBar))
                text = "ENGINE SOUND RUNNING"
            }
        }
        root.addView(startButton)

        val stopButton = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                engine.stop()
                startButton.text = "START ENGINE SOUND"
            }
        }
        root.addView(stopButton)

        val status = TextView(this).apply {
            text = "Prototype: synthetic dynamic sound\nVehicle CAN/BYD data integration comes next."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 24, 0, 0)
        }
        root.addView(status)

        rpmBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rpm = 700f + progress.toFloat()
                rpmLabel.text = formatRpm(rpm)
                engine.setRpm(rpm)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        setContentView(root)
    }

    private fun currentRpm(bar: SeekBar): Float = 700f + bar.progress.toFloat()

    private fun formatRpm(rpm: Float): String =
        String.format(Locale.US, "%,.0f RPM", rpm)

    override fun onStop() {
        engine.stop()
        super.onStop()
    }
}
