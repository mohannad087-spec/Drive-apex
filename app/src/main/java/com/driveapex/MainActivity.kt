package com.driveapex

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.EngineSoundEngine
import com.driveapex.vehicle.SimulatorVehicleDataProvider
import java.util.Locale

class MainActivity : Activity() {
    private val engine = EngineSoundEngine()
    private val vehicle = SimulatorVehicleDataProvider()
    private val controller = EngineSoundController(engine)
    private lateinit var rpmLabel: TextView
    private lateinit var telemetryLabel: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
            setBackgroundColor(Color.BLACK)
        }

        root.addView(TextView(this).apply {
            text = "DRIVE APEX"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 18)
        })

        rpmLabel = TextView(this).apply {
            textSize = 42f
            setTextColor(Color.rgb(255, 179, 0))
        }
        root.addView(rpmLabel)

        val rpmBar = SeekBar(this).apply {
            max = 6300
            progress = 200
        }
        root.addView(rpmBar, LinearLayout.LayoutParams(-1, 80))

        val throttleBar = SeekBar(this).apply {
            max = 100
            progress = 15
        }
        root.addView(TextView(this).apply {
            text = "THROTTLE"
            setTextColor(Color.LTGRAY)
        })
        root.addView(throttleBar, LinearLayout.LayoutParams(-1, 80))

        val speedBar = SeekBar(this).apply {
            max = 240
            progress = 0
        }
        root.addView(TextView(this).apply {
            text = "SPEED (km/h)"
            setTextColor(Color.LTGRAY)
        })
        root.addView(speedBar, LinearLayout.LayoutParams(-1, 80))

        telemetryLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 18)
        }
        root.addView(telemetryLabel)

        startButton = Button(this).apply {
            text = "START ENGINE SOUND"
            setOnClickListener {
                engine.start()
                syncAudio(rpmBar, throttleBar, speedBar)
                text = "ENGINE SOUND RUNNING"
            }
        }
        root.addView(startButton)

        root.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener {
                engine.stop()
                startButton.text = "START ENGINE SOUND"
            }
        })

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                syncAudio(rpmBar, throttleBar, speedBar)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        rpmBar.setOnSeekBarChangeListener(listener)
        throttleBar.setOnSeekBarChangeListener(listener)
        speedBar.setOnSeekBarChangeListener(listener)

        syncAudio(rpmBar, throttleBar, speedBar)
        setContentView(root)
    }

    private fun syncAudio(rpmBar: SeekBar, throttleBar: SeekBar, speedBar: SeekBar) {
        vehicle.setRpm(700f + rpmBar.progress)
        vehicle.setThrottle(throttleBar.progress / 100f)
        vehicle.setSpeed(speedBar.progress.toFloat())
        val data = vehicle.current()
        controller.apply(data)
        rpmLabel.text = formatRpm(data.rpm)
        telemetryLabel.text = String.format(
            Locale.US,
            "RPM %,.0f   |   %,.0f km/h   |   Throttle %d%%",
            data.rpm,
            data.speedKph,
            (data.throttle * 100).toInt()
        )
    }

    private fun formatRpm(rpm: Float): String = String.format(Locale.US, "%,.0f RPM", rpm)

    override fun onStop() {
        engine.stop()
        super.onStop()
    }
}
