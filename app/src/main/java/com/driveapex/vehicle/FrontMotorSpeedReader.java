package com.driveapex.vehicle;

import android.content.Context;
import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.BYDAutoFeatureIds;
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;
import android.hardware.bydauto.engine.BYDAutoEngineDevice;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-process reader for the BYD "front motor speed" engine feature, registered
 * exactly the way DiPlus registers its own: from the app's own process, with an
 * ordinary application Context, no ADB and no daemon.
 *
 * This class compiles against the stubs under android.hardware.bydauto.*. On a
 * real head unit the framework classes of the same name sit on the boot
 * classpath and win parent-first delegation, so the stubs are shadowed and the
 * anonymous listener below subclasses the real AbsBYDAutoEngineListener. If the
 * head unit has no such framework classes, the stub is used instead,
 * getInstance() returns null, and {@link #diagnostics()} says so rather than
 * failing silently -- which is how this path went unmeasured for so long.
 *
 * Registration deliberately prefers the single-argument
 * registerListener(listener), which is what DiPlus uses. The two-argument form
 * filters the requested IDs through BYDAutoDeviceFeaturesMap and returns the
 * intersection with the device's declared feature set; if
 * ENGINE_FRONT_MOTOR_SPEED is not in that set the array comes back empty and
 * the listener is registered for nothing. The stub only declares the
 * two-argument form, so the preferred overload is resolved reflectively
 * against whatever class is actually loaded.
 */
public final class FrontMotorSpeedReader {
    private static final int MAX_RPM = 25_000;
    private static final int FRONT_MOTOR_FEATURE_ID = BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED;

    private final BYDAutoEngineDevice device;
    private final String deviceOrigin;

    private volatile int lastRpm = -1;
    private volatile long lastUpdateMs = 0L;
    private volatile boolean registered = false;
    private volatile String registration = "not attempted";
    private volatile int eventCount = 0;

    /** Every event type seen, with its most recent raw value. */
    private final Map<Integer, Integer> observed = new LinkedHashMap<>();

    private final AbsBYDAutoEngineListener listener = new AbsBYDAutoEngineListener() {
        @Override
        public void onDataEventChanged(int type, BYDAutoEventValue value) {
            if (value == null) return;
            record(type, value.intValue);
            if (type == FRONT_MOTOR_FEATURE_ID) accept(value.intValue);
        }
    };

    public FrontMotorSpeedReader(Context context) {
        BYDAutoEngineDevice resolved = null;
        String origin;
        try {
            // getApplicationContext(), exactly as DiPlus does -- no wrapper that
            // fakes checkPermission(), which cannot grant anything the OS enforces.
            Context appContext = context.getApplicationContext();
            resolved = BYDAutoEngineDevice.getInstance(appContext != null ? appContext : context);
            origin = describeOrigin();
        } catch (Throwable t) {
            origin = "getInstance failed: " + t.getClass().getSimpleName();
        }
        this.device = resolved;
        this.deviceOrigin = origin;
    }

    /**
     * Whether BYDAutoEngineDevice came from the head-unit framework or from this
     * APK's own stub. Boot-classpath classes report a null class loader; APK
     * classes report the app's PathClassLoader. If it is the stub, no in-process
     * read can ever work, whatever permissions are held.
     */
    private static String describeOrigin() {
        ClassLoader loader = BYDAutoEngineDevice.class.getClassLoader();
        return loader == null ? "framework" : "LOCAL STUB (" + loader.getClass().getSimpleName() + ")";
    }

    private void record(int type, int value) {
        eventCount++;
        synchronized (observed) {
            if (observed.size() < 64 || observed.containsKey(type)) observed.put(type, value);
        }
    }

    private void accept(int value) {
        int abs = Math.abs(value);
        if (isPlausible(abs)) {
            lastRpm = abs;
            lastUpdateMs = System.currentTimeMillis();
        }
    }

    /** Registers the engine listener. Safe to call more than once. */
    public boolean start() {
        if (registered) return true;
        if (device == null) {
            registration = "no device (" + deviceOrigin + ")";
            return false;
        }
        Method oneArg = findUnfilteredRegister();
        if (oneArg != null) {
            try {
                oneArg.invoke(device, listener);
                registered = true;
                registration = "registerListener(listener) unfiltered";
                return true;
            } catch (Throwable t) {
                registration = "unfiltered failed: " + rootCause(t);
            }
        }
        try {
            device.registerListener(listener, new int[]{FRONT_MOTOR_FEATURE_ID});
            registered = true;
            registration = registration.startsWith("unfiltered failed")
                    ? registration + "; fell back to filtered"
                    : "registerListener(listener, ids) filtered";
            return true;
        } catch (Throwable t) {
            registration = "filtered failed: " + rootCause(t);
            return false;
        }
    }

    /** The single-argument registerListener, if the loaded class declares one. */
    private Method findUnfilteredRegister() {
        for (Method m : device.getClass().getMethods()) {
            if (!"registerListener".equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1 && params[0].isInstance(listener)) return m;
        }
        return null;
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    public void stop() {
        if (device == null || !registered) return;
        try {
            device.unregisterListener(listener);
        } catch (Throwable ignored) {
        }
        registered = false;
    }

    /** Latest verified front motor RPM, or null if no plausible sample has arrived. */
    public Integer getFrontMotorRpm() {
        return lastRpm < 0 ? null : lastRpm;
    }

    public long lastUpdateMs() {
        return lastUpdateMs;
    }

    /**
     * What actually happened, for the diagnostics screen. Distinguishes the four
     * outcomes that all previously looked identical on screen: the HAL class is
     * this APK's stub; getInstance() returned null; the listener registered but
     * no event ever arrived; events arrive but none carry the front motor ID.
     */
    public String diagnostics() {
        StringBuilder out = new StringBuilder();
        out.append("device: ").append(device == null ? "null" : "ok").append(" (").append(deviceOrigin).append(")");
        out.append("\nregistration: ").append(registration);
        out.append("\nevents: ").append(eventCount);
        synchronized (observed) {
            if (observed.isEmpty()) {
                out.append("\ntypes: NONE");
            } else {
                out.append("\ntypes:");
                for (Map.Entry<Integer, Integer> e : observed.entrySet()) {
                    out.append(String.format(" 0x%08x=%d", e.getKey(), e.getValue()));
                }
            }
        }
        out.append(String.format("\nlooking for: 0x%08x", FRONT_MOTOR_FEATURE_ID));
        return out.toString();
    }

    private static boolean isPlausible(int value) {
        return value >= 0 && value <= MAX_RPM
                && value != 8191 && value != 16383 && value != 32767 && value != 65535;
    }
}
