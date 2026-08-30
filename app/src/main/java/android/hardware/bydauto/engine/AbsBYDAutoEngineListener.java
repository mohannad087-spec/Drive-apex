package android.hardware.bydauto.engine;

import android.hardware.bydauto.BYDAutoEventValue;

/**
 * Compile-time stub for the DiLink-3 engine listener.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 *
 * A full decompile of com.van.diplus (see docs/BYD_LIVE_INTEGRATION.md)
 * confirms the HAL's actual dispatch entry point for a registered feature is
 * onDataEventChanged(int type, BYDAutoEventValue value), where `type` is the
 * registered feature ID. For BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED
 * specifically, DiPlus's own listener handles it entirely inside
 * onDataEventChanged and never calls onEngineSpeedChanged for it.
 * onEngineSpeedChanged instead fires for a separate, unrelated feature ID
 * (DiPlus's own generic "engine speed", fed from getEngineSpeed()) -- it is
 * not a fallback path for front motor speed and should not be treated as
 * one. A listener that only overrides onEngineSpeedChanged can silently
 * receive nothing for front motor speed, so callers must override
 * onDataEventChanged instead.
 */
public class AbsBYDAutoEngineListener {
    public void onDataEventChanged(int type, BYDAutoEventValue value) {}
    public void onEngineSpeedChanged(int value) {}
    public void onEngineCoolantLevelChanged(int state) {}
    public void onOilLevelChanged(int value) {}
}
