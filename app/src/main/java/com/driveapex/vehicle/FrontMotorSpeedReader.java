package com.driveapex.vehicle;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.BYDAutoFeatureIds;
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;
import android.hardware.bydauto.engine.BYDAutoEngineDevice;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * Registration uses the one-argument registerListener(listener). DiPlus's
 * generic helper can do either form, but its engine call site passes a null
 * feature array, which selects that one -- read from its bytecode rather than
 * assumed. The two-argument form, with the device's whole declared feature set,
 * stays as a fallback.
 *
 * Both have now been measured on the vehicle: one-argument, and two-argument
 * with all 30 declared IDs and the front motor among them, each deliver zero
 * events. The registration form is not what is blocking this path.
 */
public final class FrontMotorSpeedReader {
    private static final int MAX_RPM = 25_000;
    private static final int FRONT_MOTOR_FEATURE_ID = BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED;

    private final Context context;

    private volatile BYDAutoEngineDevice device;
    private volatile String deviceOrigin = "not resolved";
    private HandlerThread halThread;

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
        Context appContext = context.getApplicationContext();
        // getApplicationContext(), exactly as DiPlus does -- no wrapper that fakes
        // checkPermission(), which cannot grant anything the OS enforces.
        this.context = appContext != null ? appContext : context;
    }

    /**
     * Whether BYDAutoEngineDevice came from the head-unit framework or from this
     * APK's own stub. Boot-classpath classes report a null class loader; APK
     * classes report the app's PathClassLoader. If it is the stub, no in-process
     * read can ever work, whatever permissions are held.
     */
    private static String describeOrigin() {
        ClassLoader loader = BYDAutoEngineDevice.class.getClassLoader();
        // On Android a boot-classpath class reports a BootClassLoader instance,
        // NOT null -- only the JVM returns null there. Treating non-null as "this
        // APK's stub" reported the real vehicle HAL as a local stub.
        if (loader == null || loader == Object.class.getClassLoader()) return "framework";
        return "LOCAL STUB (" + loader.getClass().getSimpleName() + ")";
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

    /**
     * Registers the engine listener from a thread that has a Looper.
     *
     * This is the difference between this reader and every daemon in this
     * codebase, and it is a plausible explanation for the symptom that has held
     * everything up: registration reports success and not one event is ever
     * delivered. Android callback registrations routinely capture
     * Looper.myLooper() at registration time and dispatch through it; register
     * from a bare Thread, where it is null, and there is nowhere to dispatch to,
     * so the listener sits silent rather than failing loudly.
     *
     * Every call site here was such a thread -- the telemetry loop's own thread
     * and the diagnostics probe's -- while the shell-UID daemons, which do
     * prepare a Looper, were the only code that ever got this right. DiPlus
     * registers from its application thread, which has one.
     *
     * getInstance() is also called on that thread, since a HAL that captures a
     * Looper is as likely to do it there as at registration.
     */
    public boolean start() {
        if (registered) return true;
        HandlerThread thread = new HandlerThread("driveapex-byd-hal");
        thread.start();
        halThread = thread;
        final CountDownLatch done = new CountDownLatch(1);
        new Handler(thread.getLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    registerOnLooperThread();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return registered;
    }

    private void registerOnLooperThread() {
        try {
            device = BYDAutoEngineDevice.getInstance(context);
            deviceOrigin = describeOrigin();
        } catch (Throwable t) {
            deviceOrigin = "getInstance failed: " + t.getClass().getSimpleName();
        }
        if (device == null) {
            registration = "no device (" + deviceOrigin + ")";
            return;
        }
        Method oneArg = findUnfilteredRegister();
        if (oneArg != null) {
            try {
                oneArg.invoke(device, listener);
                registered = true;
                registration = "registerListener(listener) unfiltered on Looper thread";
                return;
            } catch (Throwable t) {
                registration = "unfiltered failed: " + rootCause(t);
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            int[] ids = declaredFeatureIds();
            try {
                device.registerListener(listener, ids);
                registered = true;
                registration = "registerListener(listener, ids) count=" + ids.length
                        + " hasFrontMotor=" + contains(ids, FRONT_MOTOR_FEATURE_ID);
            } catch (Throwable t) {
                registration = "filtered failed: " + rootCause(t);
            }
        }
    }

    /**
     * The device's declared feature IDs, read the way DiPlus reads them:
     * BYDAutoDeviceFeaturesMap.getFeatureIdsFromDevice(deviceType), one
     * argument, a Set back. Falls back to the front motor ID alone.
     */
    private int[] declaredFeatureIds() {
        try {
            int deviceType = (Integer) device.getClass()
                    .getMethod("getDevicetype").invoke(device);
            Class<?> map = Class.forName("android.hardware.bydauto.BYDAutoDeviceFeaturesMap");
            for (Method m : map.getDeclaredMethods()) {
                if (!"getFeatureIdsFromDevice".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != int.class) continue;
                m.setAccessible(true);
                Object result = m.invoke(null, deviceType);
                if (result instanceof java.util.Collection) {
                    java.util.Collection<?> values = (java.util.Collection<?>) result;
                    int[] ids = new int[values.size()];
                    int n = 0;
                    for (Object value : values) {
                        if (value instanceof Number) ids[n++] = ((Number) value).intValue();
                    }
                    if (n > 0) {
                        int[] trimmed = new int[n];
                        System.arraycopy(ids, 0, trimmed, 0, n);
                        return trimmed;
                    }
                } else if (result instanceof int[] && ((int[]) result).length > 0) {
                    return (int[]) result;
                }
            }
        } catch (Throwable ignored) {
            // Falls through to the single requested ID below.
        }
        return new int[]{FRONT_MOTOR_FEATURE_ID};
    }

    private static boolean contains(int[] ids, int value) {
        for (int id : ids) if (id == value) return true;
        return false;
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
        if (device != null && registered) {
            try {
                device.unregisterListener(listener);
            } catch (Throwable ignored) {
            }
        }
        registered = false;
        HandlerThread thread = halThread;
        if (thread != null) {
            thread.quitSafely();
            halThread = null;
        }
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
        out.append("\ndevice methods: ").append(deviceMethodNames());
        return out.toString();
    }

    /**
     * Every method the real engine device class exposes.
     *
     * DiPlus calls something on the device with the listener BEFORE registering
     * it -- in its bytecode, C() runs `selector.e(device, listener)` over an
     * array of selectors and only then calls registerListener. The class that
     * implements e() is not in the disassembly we have, so what it calls is
     * unknown; the device's own method list is the one place on the vehicle that
     * can say what is even available to call. Registration succeeds for us and
     * no event of any type ever arrives, so a missing enable or subscribe step
     * is what is left, and this is how to find its name rather than guess it.
     */
    private String deviceMethodNames() {
        if (device == null) return "no device";
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (Class<?> c = device.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().startsWith("access$")) continue;
                names.add(m.getName() + "/" + m.getParameterTypes().length);
            }
        }
        return names.isEmpty() ? "none" : String.join(" ", names);
    }

    private static boolean isPlausible(int value) {
        return value >= 0 && value <= MAX_RPM
                && value != 8191 && value != 16383 && value != 32767 && value != 65535;
    }
}
