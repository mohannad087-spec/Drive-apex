package android.hardware.bydauto.engine;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

/**
 * Compile-time stub for the DiLink-3 engine device.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 */
public class BYDAutoEngineDevice extends AbsBYDAutoDevice {
    public static BYDAutoEngineDevice getInstance(Context context) {
        return null;
    }

    public void registerListener(AbsBYDAutoEngineListener listener, int[] featureIds) {}

    public void unregisterListener(AbsBYDAutoEngineListener listener) {}
}
