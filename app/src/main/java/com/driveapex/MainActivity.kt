package com.driveapex

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.driveapex.audio.ApexSoundProfile
import com.driveapex.audio.ETronInspiredSoundProfile
import com.driveapex.audio.EngineSoundController
import com.driveapex.audio.LayeredSoundEngine
import com.driveapex.audio.SonicGenomeSession
import com.driveapex.update.BydAdbSetup
import com.driveapex.update.UpdateManager
import com.driveapex.vehicle.BydTelemetryDiagnostics
import com.driveapex.vehicle.LiveTelemetry
import com.driveapex.vehicle.SimulatorVehicleDataProvider
import com.driveapex.vehicle.TelemetrySource
import com.driveapex.vehicle.UdpTelemetryReceiver
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val engine = LayeredSoundEngine(ETronInspiredSoundProfile.layers)
    private val vehicle = SimulatorVehicleDataProvider()
    private val controller = EngineSoundController(engine)
    private lateinit var genomeSession: SonicGenomeSession
    private lateinit var updateManager: UpdateManager
    private lateinit var telemetryReceiver: UdpTelemetryReceiver
    private lateinit var ui: DriveApexUiShell.Views
    private val handler = Handler(Looper.getMainLooper())
    private var liveMode = false

    private lateinit var rpmValue: TextView
    private lateinit var telemetry: TextView
    private lateinit var sceneValue: TextView
    private lateinit var sourceValue: TextView
    private lateinit var liveStatus: TextView
    private lateinit var speedValue: TextView
    private lateinit var throttleValue: TextView
    private lateinit var brakeValue: TextView
    private lateinit var regenValue: TextView
    private lateinit var signatureValue: TextView
    private lateinit var genomeValue: TextView
    private lateinit var eventValue: TextView
    private lateinit var startButton: Button
    private lateinit var modeButton: Button
    private lateinit var motorSpeedBar: SeekBar
    private lateinit var throttleBar: SeekBar
    private lateinit var speedBar: SeekBar

    private val livePoller = object : Runnable {
        override fun run() {
            if (!liveMode) return
            updateLiveStatus()
            telemetryReceiver.latest()?.let { live ->
                applyTelemetry(live)
                controller.apply(live.data)
                val motorSpeed = live.data.rpm.coerceIn(0f, 25000f).roundToInt()
                rpmValue.text = "${motorSpeed} MOTOR SPEED"
                motorSpeedBar.progress = motorSpeed
            } ?: showNoVehicleData()
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        genomeSession = SonicGenomeSession(this)
        updateManager = UpdateManager(this)
        telemetryReceiver = UdpTelemetryReceiver(context = this)

        ui = DriveApexUiShell(
            activity = this,
            onLiveToggle = ::toggleTelemetryMode,
            onQuickScene = ::handleQuickScene,
            onProfile = ::selectProfile,
            onStart = ::startDriveSound,
            onStop = ::stopDriveSound,
            onAdbSetup = { runAdbSetup(true) },
            onDiagnostics = ::showBydDiagnostics,
            onResetGenome = ::resetSonicGenome,
            onCheckUpdate = { updateManager.checkManually() },
        ).build()

        rpmValue = ui.rpmValue
        telemetry = ui.telemetry
        sceneValue = ui.sceneValue
        sourceValue = ui.sourceValue
        liveStatus = ui.liveStatus
        speedValue = ui.speedValue
        throttleValue = ui.throttleValue
        brakeValue = ui.brakeValue
        regenValue = ui.regenValue
        signatureValue = ui.signatureValue
        genomeValue = ui.genomeValue
        eventValue = ui.eventValue
        startButton = ui.startButton
        modeButton = ui.modeButton
        motorSpeedBar = ui.motorSpeedBar
        throttleBar = ui.throttleBar
        speedBar = ui.speedBar

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !liveMode) syncSimulator()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        motorSpeedBar.setOnSeekBarChangeListener(listener)
        throttleBar.setOnSeekBarChangeListener(listener)
        speedBar.setOnSeekBarChangeListener(listener)

        syncSimulator()
        handler.postDelayed({ BydAdbSetup.prepare(this, false) }, 500L)
        handler.postDelayed({ updateManager.checkSilently() }, 1500L)
        handler.postDelayed({
            if (isBydVehicleRuntime() && !liveMode) toggleTelemetryMode()
        }, 2200L)
    }

    private fun isBydVehicleRuntime(): Boolean = runCatching {
        Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice")
        Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice")
        true
    }.getOrDefault(false)

    private fun handleQuickScene(name: String) {
        when (name) {
            "IDLE" -> setControls(900, 5, 0, 0, 0)
            "PULL" -> setControls(3000, 65, 45, 0, 0)
            "BOOST" -> setControls(5200, 95, 120, 0, 0)
            "COAST" -> setControls(2200, 0, 80, 0, 0)
            "REGEN" -> setControls(1600, 0, 40, 15, 100)
        }
    }

    private fun selectProfile(name: String) {
        when (name) {
            "EV GT" -> engine.setLayers(ETronInspiredSoundProfile.layers)
            "APEX" -> engine.setLayers(ApexSoundProfile.layers)
        }
    }

    private fun startDriveSound() {
        engine.start()
        if (!liveMode) syncSimulator()
        startButton.text = "DRIVE SOUND RUNNING"
    }

    private fun stopDriveSound() {
        genomeSession.finishDrive()
        engine.stop()
        startButton.text = "START DRIVE SOUND"
        sceneValue.text = "SAFE / STOPPED"
        eventValue.text = "EVENTS L:0 A:0 O:0 R:0 B:0 S:0"
    }

    private fun resetSonicGenome() {
        genomeSession.reset()
        if (!liveMode) syncSimulator()
    }

    private fun runAdbSetup(forceOpen: Boolean) {
        val result = BydAdbSetup.prepare(this, forceOpen)
        val message = when (result) {
            BydAdbSetup.Result.ALREADY_AVAILABLE -> "ADB port 5555 is available."
            BydAdbSetup.Result.SETTINGS_OPENED -> "BYD ADB settings opened. Enable wireless ADB."
            BydAdbSetup.Result.SETTINGS_UNAVAILABLE -> "BYD ADB settings activity was not found."
        }
        liveStatus.text = "ADB: ${result.name}"
        AlertDialog.Builder(this)
            .setTitle("BYD ADB setup")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBydDiagnostics() {
        Thread {
            val report = BydTelemetryDiagnostics.probe(this)
            val message = BydTelemetryDiagnostics.format(report)
            handler.post {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle("BYD telemetry probe")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun toggleTelemetryMode() {
        liveMode = !liveMode
        if (liveMode) {
            telemetryReceiver.start()
            modeButton.text = "LIVE"
            sourceValue.text = "LIVE VEHICLE"
            sourceValue.setTextColor(GREEN)
            motorSpeedBar.isEnabled = false
            throttleBar.isEnabled = false
            speedBar.isEnabled = false
            updateLiveStatus()
            handler.post(livePoller)
        } else {
            telemetryReceiver.stop()
            handler.removeCallbacks(livePoller)
            modeButton.text = "TEST"
            sourceValue.text = "SIMULATOR"
            sourceValue.setTextColor(MUTED)
            liveStatus.text = "LIVE READY  •  VEHICLE DATA STANDBY"
            liveStatus.setTextColor(SOFT)
            speedValue.text = "--"
            throttleValue.text = "--"
            brakeValue.text = "--"
            regenValue.text = "--"
            motorSpeedBar.isEnabled = true
            throttleBar.isEnabled = true
            speedBar.isEnabled = true
            syncSimulator()
        }
    }

    private fun updateLiveStatus() {
        val d = telemetryReceiver.diagnostics()
        val age = if (d.ageMs == Long.MAX_VALUE) "--" else "${d.ageMs}ms"
        val valid = d.packetCount > 0 && d.ageMs <= 1500L
        liveStatus.text = String.format(
            Locale.US,
            "LIVE %s  •  PKT %d  •  AGE %s",
            if (valid) "CONNECTED" else "WAITING",
            d.packetCount,
            age,
        )
        liveStatus.setTextColor(if (valid) GREEN else AMBER)
    }

    private fun showNoVehicleData() {
        if (!liveMode) return
        telemetry.text = "Waiting for verified vehicle telemetry"
        sceneValue.text = "LIVE WAIT"
        speedValue.text = "--"
        throttleValue.text = "--"
        brakeValue.text = "--"
        regenValue.text = "--"
    }

    private fun applyTelemetry(packet: LiveTelemetry) {
        val data = packet.data
        val scene = controller.apply(data)
        val genome = genomeSession.update(data)
        val signature = genome.toSignature()
        val events = controller.events()

        if (liveMode) {
            motorSpeedBar.progress = data.rpm.roundToInt().coerceIn(0, motorSpeedBar.max)
            throttleBar.progress = (data.throttle * 100f).roundToInt().coerceIn(0, throttleBar.max)
            speedBar.progress = data.speedKph.roundToInt().coerceIn(0, speedBar.max)
        }

        rpmValue.text = formatMotorSpeed(data.rpm)
        telemetry.text = String.format(
            Locale.US,
            "%.0f km/h   •   Throttle %d%%   •   Brake %d%%   •   Regen %d%%",
            data.speedKph,
            (data.throttle * 100).roundToInt(),
            (data.brake * 100).roundToInt(),
            (data.regen * 100).roundToInt(),
        )
        speedValue.text = String.format(Locale.US, "%.0f", data.speedKph)
        throttleValue.text = String.format(Locale.US, "%d", (data.throttle * 100).roundToInt())
        brakeValue.text = String.format(Locale.US, "%d", (data.brake * 100).roundToInt())
        regenValue.text = String.format(Locale.US, "%d", (data.regen * 100).roundToInt())
        sceneValue.text = scene.name.replace('_', ' ')
        sourceValue.text = if (liveMode && packet.source != TelemetrySource.SIMULATOR) "LIVE VEHICLE" else "SIMULATOR"
        sourceValue.setTextColor(if (liveMode) GREEN else MUTED)
        signatureValue.text = String.format(
            Locale.US,
            "%s   •   AGG %d%%   •   SMOOTH %d%%",
            signature.label(),
            (signature.aggression * 100).roundToInt(),
            (signature.smoothness * 100).roundToInt(),
        )
        genomeValue.text = String.format(
            Locale.US,
            "GENOME: %s  •   OBS %d",
            signature.label(),
            genome.observations.coerceAtMost(999_999L),
        )
        eventValue.text = String.format(
            Locale.US,
            "EVENTS L:%d A:%d O:%d R:%d B:%d S:%d",
            (events.launch * 100).roundToInt(),
            (events.accelerationHit * 100).roundToInt(),
            (events.liftOff * 100).roundToInt(),
            (events.regenerationHit * 100).roundToInt(),
            (events.brakeHit * 100).roundToInt(),
            (events.speedRush * 100).roundToInt(),
        )
    }

    private fun syncSimulator() {
        vehicle.setRpm(motorSpeedBar.progress.toFloat())
        vehicle.setThrottle(throttleBar.progress / 100f)
        vehicle.setSpeed(speedBar.progress.toFloat())
        vehicle.setBrake(0f)
        vehicle.setRegen(0f)
        applyTelemetry(LiveTelemetry(vehicle.current(), TelemetrySource.SIMULATOR))
    }

    private fun setControls(rpm: Int, throttle: Int, speed: Int, brake: Int, regen: Int) {
        motorSpeedBar.progress = rpm.coerceIn(0, motorSpeedBar.max)
        throttleBar.progress = throttle.coerceIn(0, throttleBar.max)
        speedBar.progress = speed.coerceIn(0, speedBar.max)
        vehicle.setBrake((brake / 100f).coerceIn(0f, 1f))
        vehicle.setRegen((regen / 100f).coerceIn(0f, 1f))
        if (!liveMode) applyTelemetry(LiveTelemetry(vehicle.current(), TelemetrySource.SIMULATOR))
    }

    private fun formatMotorSpeed(motorSpeed: Float) = String.format(Locale.US, "%,.0f MOTOR SPEED", motorSpeed)

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.onResume()
    }

    override fun onStop() {
        handler.removeCallbacks(livePoller)
        genomeSession.finishDrive()
        telemetryReceiver.stop()
        engine.stop()
        super.onStop()
    }

    companion object {
        private const val GREEN = 0xFF35D07F.toInt()
        private const val AMBER = 0xFFFFB74D.toInt()
        private const val SOFT = 0xFFE5EAF0.toInt()
        private const val MUTED = 0xFF8995A3.toInt()
    }
}
