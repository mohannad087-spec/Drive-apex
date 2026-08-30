package android.hardware.bydauto;

/**
 * Compile-time stub for BYD HAL feature identifiers.
 *
 * The vehicle runtime supplies the real class from bmmcamera.jar because the
 * privileged daemon is launched with that jar before the APK on its classpath.
 * Only feature IDs actually verified against a working DiPlus/Overdrive
 * collector path belong here; see docs/BYD_LIVE_INTEGRATION.md.
 */
public class BYDAutoFeatureIds {
    public static final int ENGINE_FRONT_MOTOR_SPEED = 1141899272;
}
