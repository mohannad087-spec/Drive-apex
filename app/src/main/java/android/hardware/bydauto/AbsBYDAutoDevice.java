package android.hardware.bydauto;

/**
 * Compile-time stub for the BYD HAL device base class.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 */
public abstract class AbsBYDAutoDevice {
    public int getDevicetype() {
        return -1;
    }

    public <T> T get(int[] featureIds, Class<T> type) {
        return null;
    }
}
