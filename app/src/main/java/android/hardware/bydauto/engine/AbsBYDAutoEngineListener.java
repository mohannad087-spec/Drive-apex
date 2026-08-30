package android.hardware.bydauto.engine;

import android.hardware.bydauto.BYDAutoEventValue;

/**
 * Compile-time stub for the DiLink-3 engine listener.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 *
 * A disassembly of com.van.diplus (see docs/BYD_LIVE_INTEGRATION.md) confirms
 * the HAL's actual dispatch entry point for a registered feature (front motor
 * speed included) is onDataEventChanged(int type, BYDAutoEventValue value),
 * where `type` is the registered feature ID. onEngineSpeedChanged is not
 * called by the HAL directly for that feature in that trace; DiPlus's own
 * listener forwards into it manually. A listener that only overrides
 * onEngineSpeedChanged can silently receive nothing, so callers should
 * override onDataEventChanged.
 */
public class AbsBYDAutoEngineListener {
    public void onDataEventChanged(int type, BYDAutoEventValue value) {}
    public void onEngineSpeedChanged(int value) {}
    public void onEngineCoolantLevelChanged(int state) {}
    public void onOilLevelChanged(int value) {}
}
