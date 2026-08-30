package android.hardware.bydauto.engine;

/**
 * Compile-time stub for the DiLink-3 engine listener.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 */
public class AbsBYDAutoEngineListener {
    public void onEngineSpeedChanged(int value) {}
    public void onEngineCoolantLevelChanged(int state) {}
    public void onOilLevelChanged(int value) {}
}
