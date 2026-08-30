package com.driveapex.vehicle;

import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;

/**
 * Concrete DiLink-3 engine listener matching the callback path used by DiPlus.
 *
 * DiPlus does not use onEngineSpeedChanged() as the front motor RPM source.
 * It receives onDataEventChanged(featureId, eventValue), matches the front-motor
 * feature, and reads BYDAutoEventValue.intValue.
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

    @Override
    public void onDataEventChanged(int featureId, BYDAutoEventValue value) {
        if (featureId != frontMotorFeatureId || value == null || sink == null) return;
        sink.onFrontMotorSpeed(value.intValue);
    }

    /**
     * EV motor speed is delivered through the engine data-event callback above.
     * Do not use this callback as Front Motor Speed; on EVs it can legitimately be 0.
     */
    @Override
    public void onEngineSpeedChanged(int value) {
        // Intentionally ignored. Front Motor Speed comes from onDataEventChanged().
    }
}
