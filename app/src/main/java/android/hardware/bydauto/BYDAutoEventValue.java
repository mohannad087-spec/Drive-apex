package android.hardware.bydauto;

/**
 * Compile-time stub for the generic BYD HAL feature-value wrapper.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 * BYDAutoEngineDevice#get(int[], Class) can return this wrapper instead of a
 * raw Number, so callers must unwrap it via intValue() rather than casting.
 */
public class BYDAutoEventValue {
    protected int value;

    public int getValue() {
        return value;
    }

    public int intValue() {
        return value;
    }
}
