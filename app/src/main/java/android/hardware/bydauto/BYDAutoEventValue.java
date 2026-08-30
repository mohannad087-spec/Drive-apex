package android.hardware.bydauto;

/**
 * Compile-time stub for the generic BYD HAL feature-value wrapper.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 * Field names/types are confirmed from a disassembly of com.van.diplus (see
 * docs/BYD_LIVE_INTEGRATION.md): the real class exposes plain public fields
 * `intValue` and `doubleValue`, read and written directly (no getters), so
 * callers must not cast this to Number.
 */
public class BYDAutoEventValue {
    public int intValue;
    public double doubleValue;
}
