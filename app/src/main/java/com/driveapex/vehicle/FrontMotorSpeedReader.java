package com.driveapex.vehicle;

import android.content.Context;
import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.BYDAutoFeatureIds;
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;
import android.hardware.bydauto.engine.BYDAutoEngineDevice;

/**
 * Compile-time-typed reader for the verified BYD "front motor speed" engine
 * feature (BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED).
 *
 * This class compiles against the stubs under android.hardware.bydauto.* and
 * is meant to run inside the privileged BYD telemetry daemon process, where
 * the real bmmcamera.jar classes are on the classpath ahead of the APK and
 * shadow these stubs at runtime (see AbsBYDAutoEngineListener). It is a
 * type-safe alternative to the reflection-based readers in
 * BydDiPlusEngineTelemetryDaemonMain/DirectBydTelemetryReader for callers
 * that can link directly against the vendor jar.
 *
 * A disassembly of com.van.diplus (docs/BYD_LIVE_INTEGRATION.md) confirms
 * the HAL's actual dispatch entry point for a registered feature is
 * onDataEventChanged(int type, BYDAutoEventValue value), not
 * onEngineSpeedChanged(int) directly, so that is what this listener relies
 * on. onEngineSpeedChanged is also overridden as a defensive fallback.
 */
public final class FrontMotorSpeedReader {
    private static final int MAX_RPM = 25_000;
    private static final int FRONT_MOTOR_FEATURE_ID = BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED;

    private final BYDAutoEngineDevice device;
    private final AbsBYDAutoEngineListener listener = new AbsBYDAutoEngineListener() {
        @Override
        public void onDataEventChanged(int type, BYDAutoEventValue value) {
            if (type == FRONT_MOTOR_FEATURE_ID && value != null) accept(value.intValue);
        }

        @Override
        public void onEngineSpeedChanged(int value) {
            accept(value);
        }
    };

    private void accept(int value) {
        int abs = Math.abs(value);
        if (isPlausible(abs)) {
            lastRpm = abs;
            lastUpdateMs = System.currentTimeMillis();
        }
    }

    private volatile int lastRpm = -1;
    private volatile long lastUpdateMs = 0L;
    private volatile boolean registered = false;

    public FrontMotorSpeedReader(Context context) {
        this.device = BYDAutoEngineDevice.getInstance(context);
    }

    /** Registers the engine listener for the front motor speed feature. Safe to call once. */
    public boolean start() {
        if (device == null || registered) return registered;
        device.registerListener(listener, new int[]{BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED});
        registered = true;
        return true;
    }

    public void stop() {
        if (device == null || !registered) return;
        device.unregisterListener(listener);
        registered = false;
    }

    /** Latest verified front motor RPM, or null if no plausible sample has been received yet. */
    public Integer getFrontMotorRpm() {
        return lastRpm < 0 ? null : lastRpm;
    }

    public long lastUpdateMs() {
        return lastUpdateMs;
    }

    private static boolean isPlausible(int value) {
        return value >= 0 && value <= MAX_RPM
                && value != 8191 && value != 16383 && value != 32767 && value != 65535;
    }
}
