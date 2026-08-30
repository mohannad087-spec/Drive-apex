package com.driveapex.vehicle;

import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;

/**
 * Concrete DiLink-3 engine listener matching the callback used by DiPlus.
 * The runtime class is supplied by the vehicle framework; this class only
 * forwards the typed front-engine/motor speed callback into DriveApex.
 */
public final class BydFrontMotorSpeedListener extends AbsBYDAutoEngineListener {
    public interface Sink {
        void onFrontMotorSpeed(int rpm);
    }

    private final Sink sink;

    public BydFrontMotorSpeedListener(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void onEngineSpeedChanged(int value) {
        if (sink != null) sink.onFrontMotorSpeed(value);
    }
}
