package com.driveapex.vehicle;

import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;

/**
 * Front-motor speed listener matching the DiPlus/Overdrive event path.
 *
 * The verified feature is delivered through the engine device's data-event
 * callback. Do not use onEngineSpeedChanged() as the EV front-motor source;
 * on BEV/PHEV platforms that callback can legitimately remain zero.
 *
 * The connected BYD framework contains onDataEventChanged(int, BYDAutoEventValue)
 * even when the local compile stub does not declare it, so this method is kept
 * as a normal public method rather than an @Override annotation.
 */
public final class BydFrontMotorSpeedListener extends AbsBYDAutoEngineListener {
    public interface Sink {
        void onFrontMotorSpeed(int rpm);
    }

    private static final int FRONT_MOTOR_SPEED_FALLBACK = 1141899272;
    private final Sink sink;
    private final int frontMotorFeatureId;

    public BydFrontMotorSpeedListener(Sink sink) {
        this(sink, FRONT_MOTOR_SPEED_FALLBACK);
    }

    public BydFrontMotorSpeedListener(Sink sink, int frontMotorFeatureId) {
        this.sink = sink;
        this.frontMotorFeatureId = frontMotorFeatureId;
    }

    /** Exact DiPlus/Overdrive front-motor event path. */
    public void onDataEventChanged(int featureId, BYDAutoEventValue value) {
        if (featureId != frontMotorFeatureId || value == null || sink == null) return;
        sink.onFrontMotorSpeed(value.intValue);
    }

    /** Not the EV front-motor source. */
    @Override
    public void onEngineSpeedChanged(int value) {
        // Intentionally ignored for Front Motor Speed.
    }
}
