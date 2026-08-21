package com.driveapex

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.ETronInspiredSoundProfile
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.vehicle.SimulatorVehicleDataProvider
import java.util.Locale

class MainActivity : Activity() {
    private val engine = LayeredSoundEngine(ETronInspiredSoundProfile.layers)
    private val vehicle = SimulatorVehicleDataProvider()
    private val controller = EngineSoundController(engine)

    private lateinit var rpmValue: TextView
    private lateinit var telemetry: TextView
    private lateinit var sceneValue: TextView
    private lateinit var startButton: Button
    private lateinit var rpmBar: SeekBar
    private lateinit var throttleBar: SeekBar
    private lateinit var speedBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 28)
            setBackgroundColor(Color.rgb(8, 10, 13))
        }
        scroll.addView(root)

        root.addView(label("DRIVE APEX", 28f, Color.WHITE).apply {
            setPadding(0, 0, 0, 4)
        })
        root.addView(label("PHONE TEST LAB", 13f, Color.LTGRAY).apply {
            setPadding(0, 0, 0, 20)
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20, 22, 20, 22)
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        sceneValue = label("SCENE: IDLE", 14f, Color.rgb(255, 179, 0))
        statusCard.addView(sceneValue)
        rpmValue = label("700 RPM", 52f, Color.WHITE)
        statusCard.addView(rpmValue)
        telemetry = label("0 km/h  •  Throttle 0%", 14f, Color.LTGRAY)
        statusCard.addView(telemetry)
        root.addView(statusCard, marginParams(bottom = 18))

        root.addView(label("RPM", 14f, Color.LTGRAY))
        rpmBar = seek(max = 6300, progress = 200)
        root.addView(rpmBar, marginParams(bottom = 14))

        root.addView(label("THROTTLE", 14f, Color.LTGRAY))
        throttleBar = seek(max = 100, progress = 10)
        root.addView(throttleBar, marginParams(bottom = 14))

        root.addView(label("SPEED  km/h", 14f, Color.LTGRAY))
        speedBar = seek(max = 240, progress = 0)
        root.addView(speedBar, marginParams(bottom = 16))

        root.addView(label("QUICK SCENES", 14f, Color.LTGRAY))
        val scenes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "IDLE" to { setControls(900, 5, 0) },
            "PULL" to { setControls(3000, 65, 45) },
            "BOOST" to { setControls(5200, 95, 120) },
            "COAST" to { setControls(2200, 0, 80) },
            "REGEN" to { setControls(1600, 0, 40) }
        ).forEach { (title, action) ->
            scenes.addView(Button(this).apply {
                text = title
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, 56).apply {
                weight = 1f
                marginEnd = 8
            })
        }
        root.addView(scenes, marginParams(bottom = 18))

        root.addView(label("SOUND CHARACTER", 14f, Color.LTGRAY))
        val profiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profiles.addView(Button(this).apply {
            text = "EV GT"
            setOnClickListener { engine.setLayers(ETronInspiredSoundProfile.layers) }
        }, LinearLayout.LayoutParams(0, 60).apply { weight = 1f; marginEnd = 8 })
        profiles.addView(Button(this).apply {
            text = "APEX"
            setOnClickListener { engine.setLayers(ETronInspiredSoundProfile.layers) }
        }, LinearLayout.LayoutParams(0, 60).apply { weight = 1f })
        root.addView(profiles, marginParams(bottom = 16))

        startButton = Button(this).apply {
            text = "START DRIVE SOUND"
            setOnClickListener {
                engine.start()
                syncAudio()
                text = "DRIVE SOUND RUNNING"
            }
        }
        root.addView(startButton, marginParams(bottom = 8))

        root.addView(Button(this).apply {
            text = "STOP / SAFE"
            setOnClickListener {
                engine.stop()
                startButton.text = "START DRIVE SOUND"
                sceneValue.text = "SCENE: SAFE / STOPPED"
            }
        })

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) syncAudio()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        rpmBar.setOnSeekBarChangeListener(listener)
        throttleBar.setOnSeekBarChangeListener(listener)
        speedBar.setOnSeekBarChangeListener(listener)

        syncAudio()
        setContentView(scroll)
    }

    private fun syncAudio() {
        val rpm = 700 + rpmBar.progress
        val throttle = throttleBar.progress / 100f
        val speed = speedBar.progress.toFloat()
        vehicle.setRpm(rpm.toFloat())
        vehicle.setThrottle(throttle)
        vehicle.setSpeed(speed)
        val data = vehicle.current()
        controller.apply(data)

        rpmValue.text = String.format(Locale.US, "%,d RPM", rpm)
        telemetry.text = String.format(Locale.US, "%,.0f km/h  •  Throttle %d%%", speed, (throttle * 100).toInt())
        sceneValue.text = when {
            throttle > 0.80f && rpm > 4500 -> "SCENE: BOOST"
            throttle > 0.25f -> "SCENE: ACCELERATION"
            speed > 1f -> "SCENE: COAST"
            else -> "SCENE: IDLE"
        }
    }

    private fun setControls(rpm: Int, throttle: Int, speed: Int) {
        rpmBar.progress = rpm - 700
        throttleBar.progress = throttle
        speedBar.progress = speed
        syncAudio()
    }

    private fun label(text: String, size: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun seek(max: Int, progress: Int): SeekBar = SeekBar(this).apply {
        this.max = max
        this.progress = progress
        minHeight = 52
    }

    private fun marginParams(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.bottomMargin = bottom
        }

    override fun onStop() {
        engine.stop()
        super.onStop()
    }
}
