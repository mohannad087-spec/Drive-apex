package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shell-UID BYD telemetry daemon.
 *
 * Front Motor Speed follows the path found in the supplied DiPlus APK and its
 * matching open-source implementation: BYDAutoEngineDevice -> registerListener
 * (android.hardware.IBYDAutoListener, int[]) -> onDataEventChanged(featureId,
 * BYDAutoEventValue) -> intValue. The direct getMotorSpeed() getter remains only
 * as a fallback when the event stream has not produced a value yet.
 */
public final class BydLiveTelemetryDaemonMain {
    private static final String DEFAULT_PACKAGE = "com.driveapex";
    private static final String PREFERRED_PACKAGE = "com.overdrive.app";
    private static final String FALLBACK_PACKAGE = "com.byd.avc";
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18765;
    private static final int SCAN_PORT = 18766;
    private static final long WATCHDOG_MS = 500L;
    private static final double MAX_RPM = 25000.0;

    // DiPlus/TripStats confirmed feature fallback; runtime resolution wins when present.
    private static final int FRONT_RPM_FALLBACK = 1141899272;
    private static final int REAR_RPM_FALLBACK = 621805576;

    private BydLiveTelemetryDaemonMain() {}

    private static final class TelemetrySnapshot {
        volatile long timestamp;
        volatile double rpm;
        volatile double speedKph;
        volatile double throttlePct;
        volatile double brakePct;
        volatile boolean valid;
        volatile long eventCount;
        volatile int frontFeatureId = FRONT_RPM_FALLBACK;
        volatile int rearFeatureId = REAR_RPM_FALLBACK;
        volatile long frontRpmAt;
        volatile int frontRpm;
        volatile int rearRpm;
        volatile boolean frontListenerRegistered;
        volatile String frontListenerMethod = "NOT_REGISTERED";
    }

    public static void main(String[] args) {
        HandlerThreadCompat halThread = null;
        ServerSocket scanServer = null;
        try {
            LaunchArgs launchArgs = LaunchArgs.parse(args);
            Context[] contexts = createContexts(launchArgs.packageName, launchArgs.userId);
            ReflectDevice speed = new ReflectDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", contexts);
            ReflectDevice motor = new ReflectDevice("android.hardware.bydauto.motor.BYDAutoMotorDevice", contexts);
            ReflectDevice engine = new ReflectDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", contexts);
            TelemetrySnapshot snapshot = new TelemetrySnapshot();
            snapshot.frontFeatureId = resolveFeatureId("ENGINE_FRONT_MOTOR_SPEED", FRONT_RPM_FALLBACK);
            snapshot.rearFeatureId = resolveFeatureId("ENGINE_REAR_MOTOR_SPEED", REAR_RPM_FALLBACK);

            halThread = new HandlerThreadCompat("driveapex-byd-hal");
            halThread.start();
            android.os.Handler handler = new android.os.Handler(halThread.getLooper());
            CountDownLatch initialized = new CountDownLatch(1);
            handler.post(() -> {
                boolean speedReady = speed.initialize();
                boolean motorReady = motor.initialize();
                boolean engineReady = engine.initialize();
                boolean listenerRegistered = engine.registerDiPlusEngineListener(snapshot);
                snapshot.frontListenerRegistered = listenerRegistered;
                sampleBase(speed, motor, engine, snapshot);

                System.out.println("BYD LIVE init speed=" + speedReady
                        + " motor=" + motorReady
                        + " engine=" + engineReady
                        + " frontListener=" + listenerRegistered
                        + " method=" + snapshot.frontListenerMethod
                        + " frontId=" + snapshot.frontFeatureId
                        + " rearId=" + snapshot.rearFeatureId
                        + " package=" + launchArgs.packageName
                        + " user=" + launchArgs.userId);
                initialized.countDown();

                handler.post(new Runnable() {
                    @Override public void run() {
                        sampleBase(speed, motor, engine, snapshot);
                        handler.postDelayed(this, WATCHDOG_MS);
                    }
                });
            });
            initialized.await(3, TimeUnit.SECONDS);

            scanServer = new ServerSocket(SCAN_PORT, 2, InetAddress.getByName(HOST));
            final ServerSocket finalScanServer = scanServer;
            Thread scanThread = new Thread(() -> {
                while (!finalScanServer.isClosed()) {
                    try {
                        Socket client = finalScanServer.accept();
                        Thread worker = new Thread(() -> serveScan(client, snapshot), "driveapex-byd-scan");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Throwable t) {
                        if (!finalScanServer.isClosed()) {
                            System.err.println("sensor scan server failed: " + message(t));
                        }
                    }
                }
            }, "driveapex-byd-scan-server");
            scanThread.setDaemon(true);
            scanThread.start();

            try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName(HOST))) {
                System.out.println("DriveApex BYD live daemon ready on " + HOST + ":" + PORT + " scan=" + SCAN_PORT);
                while (!server.isClosed()) {
                    Socket client = server.accept();
                    Thread worker = new Thread(() -> serveClient(client, snapshot), "driveapex-byd-client");
                    worker.setDaemon(true);
                    worker.start();
                }
            }
        } catch (Throwable t) {
            System.err.println("DriveApex BYD live daemon failed: " + message(t));
            t.printStackTrace(System.err);
        } finally {
            if (scanServer != null) try { scanServer.close(); } catch (Throwable ignored) {}
            if (halThread != null) halThread.quitSafely();
        }
    }

    private static void sampleBase(ReflectDevice speed, ReflectDevice motor, ReflectDevice engine, TelemetrySnapshot snapshot) {
        Double speedKph = sanitizeSpeed(speed.readNumber("getCurrentSpeed"));
        Double throttlePct = sanitizePedal(speed.readNumber("getAccelerateDeepness"));
        Double brakePct = sanitizePedal(speed.readNumber("getBrakeDeepness"));
        if (speedKph != null) snapshot.speedKph = speedKph;
        if (throttlePct != null) snapshot.throttlePct = throttlePct;
        if (brakePct != null) snapshot.brakePct = brakePct;

        long now = System.currentTimeMillis();
        // Do not overwrite a recent DiPlus-style front RPM event with the stale getter value.
        if (snapshot.frontRpmAt > 0L && now - snapshot.frontRpmAt <= 1500L) {
            snapshot.rpm = snapshot.frontRpm;
        } else {
            Double fallback = sanitizeRpm(motor.readNumber("getMotorSpeed"));
            if (fallback == null) fallback = sanitizeRpm(engine.readNumber("getEngineSpeed"));
            if (fallback != null) snapshot.rpm = fallback;
        }
        snapshot.timestamp = now;
        snapshot.valid = speedKph != null || throttlePct != null || brakePct != null || snapshot.frontRpmAt > 0L;
        snapshot.eventCount++;
    }

    private static void serveClient(Socket socket, TelemetrySnapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            while (!socket.isClosed()) {
                if (!snapshot.valid || snapshot.timestamp <= 0L) {
                    Thread.sleep(50L);
                    continue;
                }
                writer.write(Long.toString(snapshot.timestamp)); writer.write(',');
                writer.write(Double.toString(snapshot.rpm)); writer.write(',');
                writer.write(Double.toString(snapshot.speedKph)); writer.write(',');
                writer.write(Double.toString(snapshot.throttlePct)); writer.write(',');
                writer.write(Double.toString(snapshot.brakePct)); writer.write(",BYD_DIPLUS_ENGINE_EVENT");
                writer.newLine(); writer.flush();
                Thread.sleep(50L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("client failed: " + message(t));
        }
    }

    private static void serveScan(Socket socket, TelemetrySnapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            writer.write("SCAN_READY\n");
            writer.write("HIT,ENGINE,ENGINE_FRONT_MOTOR_SPEED,listener="
                    + snapshot.frontListenerRegistered + "," + snapshot.frontListenerMethod + "\n");
            writer.write(String.format(Locale.US,
                    "HIT,ENGINE,FRONT_MOTOR_SPEED_LIVE,feature=%d,rpm=%d\n",
                    snapshot.frontFeatureId, snapshot.frontRpm));
            writer.write(String.format(Locale.US,
                    "HIT,ENGINE,REAR_MOTOR_SPEED_LIVE,feature=%d,rpm=%d\n",
                    snapshot.rearFeatureId, snapshot.rearRpm));
            writer.write("SCAN_DONE,3\n");
            writer.flush();
        } catch (Throwable t) {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    /** Exact DiPlus engine event mechanism: IBYDAutoListener + feature-id array. */
    private static boolean registerDiPlusEngineListener(ReflectDevice engine, TelemetrySnapshot snapshot) {
        Object device = engine.ensureDevice();
        if (device == null) return false;
        try {
            Class<?> listenerType = Class.forName("android.hardware.IBYDAutoListener");
            final String registerName = "registerListener";
            Method target = null;
            for (Class<?> c = device.getClass(); c != null && target == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!registerName.equals(m.getName()) || p.length != 2 || p[1] != int[].class) continue;
                    if (p[0].isAssignableFrom(listenerType) || listenerType.isAssignableFrom(p[0])) {
                        target = m;
                        break;
                    }
                }
            }
            if (target == null) {
                for (Method m : device.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!registerName.equals(m.getName()) || p.length != 2 || p[1] != int[].class) continue;
                    if (p[0].isAssignableFrom(listenerType) || listenerType.isAssignableFrom(p[0])) {
                        target = m;
                        break;
                    }
                }
            }
            if (target == null) return false;

            final int frontId = snapshot.frontFeatureId;
            final int rearId = snapshot.rearFeatureId;
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("onDataEventChanged".equals(name) && args != null && args.length >= 2) {
                    Integer featureId = args[0] instanceof Integer ? (Integer) args[0] : null;
                    Object event = args[1];
                    if (featureId != null && event != null) {
                        Integer intValue = readIntFieldOrGetter(event, "intValue");
                        Double doubleValue = readDoubleFieldOrGetter(event, "doubleValue");
                        int rpm;
                        if (intValue != null) rpm = intValue;
                        else if (doubleValue != null) rpm = (int) Math.round(doubleValue);
                        else rpm = Integer.MIN_VALUE;
                        if (rpm != Integer.MIN_VALUE) {
                            int magnitude = Math.abs(rpm);
                            boolean valid = magnitude <= 30000 && magnitude != 8191 && magnitude != 16383
                                    && magnitude != 32767 && magnitude != 65535;
                            if (valid && featureId == frontId) {
                                snapshot.frontRpm = magnitude;
                                snapshot.rpm = magnitude;
                                snapshot.frontRpmAt = System.currentTimeMillis();
                                snapshot.timestamp = snapshot.frontRpmAt;
                                snapshot.valid = true;
                                snapshot.eventCount++;
                            } else if (valid && featureId == rearId) {
                                snapshot.rearRpm = magnitude;
                                snapshot.eventCount++;
                            }
                        }
                    }
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) return false;
                if (returnType == int.class) return 0;
                if (returnType == long.class) return 0L;
                return null;
            };

            Object proxy = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, handler);
            target.setAccessible(true);
            // DiPlus/TripStats behavior: empty array primes all engine events; explicit front/rear IDs
            // are then registered so firmware variants that require IDs are also covered.
            boolean subscribedAll = false;
            try {
                target.invoke(device, proxy, new int[0]);
                subscribedAll = true;
            } catch (Throwable ignored) {}
            try {
                target.invoke(device, proxy, new int[]{frontId, rearId});
            } catch (Throwable explicitError) {
                if (!subscribedAll) throw explicitError;
            }
            snapshot.frontListenerMethod = target.getName() + "(" + target.getParameterTypes()[0].getName() + ",int[])";
            return true;
        } catch (Throwable t) {
            System.err.println("DiPlus engine event listener failed: " + message(t));
            snapshot.frontListenerMethod = "ERROR:" + message(t);
            return false;
        }
    }

    private static Integer readIntFieldOrGetter(Object event, String name) {
        try {
            Field field = event.getClass().getField(name);
            field.setAccessible(true);
            Object value = field.get(event);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable ignored) {}
        try {
            Method method = event.getClass().getMethod(name);
            method.setAccessible(true);
            Object value = method.invoke(event);
            return value instanceof Number ? ((Number) value).intValue() : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Double readDoubleFieldOrGetter(Object event, String name) {
        try {
            Field field = event.getClass().getField(name);
            field.setAccessible(true);
            Object value = field.get(event);
            return value instanceof Number ? ((Number) value).doubleValue() : null;
        } catch (Throwable ignored) {}
        try {
            Method method = event.getClass().getMethod(name);
            method.setAccessible(true);
            Object value = method.invoke(event);
            return value instanceof Number ? ((Number) value).doubleValue() : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Double sanitizeRpm(Double value) {
        if (value == null || !Double.isFinite(value)) return null;
        if (value == 8191.0 || value == -8191.0 || value == 16383.0 || value == -16383.0
                || value == 32767.0 || value == -32768.0 || value == 65535.0 || value == -65535.0) return null;
        return value >= 0.0 && value <= MAX_RPM ? value : null;
    }

    private static Double sanitizeSpeed(Double value) {
        if (value == null || !Double.isFinite(value)) return null;
        if (value == 8191.0 || value == 32767.0 || value == 65535.0 || value < 0.0 || value > 400.0) return null;
        return value;
    }

    private static Double sanitizePedal(Double value) {
        if (value == null || !Double.isFinite(value)) return null;
        if (value == -10011.0 || value == 8191.0 || value == 32767.0 || value == 65535.0) return null;
        return value >= 0.0 && value <= 100.0 ? value : null;
    }

    private static int resolveFeatureId(String fieldName, int fallback) {
        try {
            Class<?> ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            Field field = ids.getField(fieldName);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Context[] createContexts(String requestedPackage, int userId) throws Exception {
        Context system = createSystemContext();
        Context requested = tryPackageContext(system, requestedPackage, userId);
        Context shell = wrap(system, SHELL_PACKAGE);
        Context preferred = tryPackageContext(system, PREFERRED_PACKAGE, userId);
        Context byd = tryPackageContext(system, FALLBACK_PACKAGE, userId);
        Context app = tryPackageContext(system, DEFAULT_PACKAGE, userId);
        return new Context[]{
                shell,
                wrap(requested, requestedPackage),
                preferred == null ? null : wrap(preferred, PREFERRED_PACKAGE),
                byd == null ? null : wrap(byd, FALLBACK_PACKAGE),
                app == null ? null : wrap(app, DEFAULT_PACKAGE)
        };
    }

    private static Context tryPackageContext(Context system, String packageName, int userId) {
        if (packageName == null || packageName.trim().isEmpty()) return null;
        try {
            Class<?> userHandleClass = Class.forName("android.os.UserHandle");
            Object userHandle = userHandleClass.getMethod("of", int.class).invoke(null, userId);
            Method m = Context.class.getMethod("createPackageContextAsUser", String.class, int.class, userHandleClass);
            return (Context) m.invoke(system, packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
        } catch (Throwable ignored) {
            try {
                return system.createPackageContext(packageName,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static Context wrap(Context context, String packageName) {
        return context == null ? null : new BydPermissionContext(context, packageName);
    }

    private static Context createSystemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static final class BydPermissionContext extends ContextWrapper {
        private final String opPackage;
        BydPermissionContext(Context base, String opPackage) {
            super(base);
            this.opPackage = opPackage == null || opPackage.isEmpty() ? DEFAULT_PACKAGE : opPackage;
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkCallingOrSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkPermission(String permission, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public String getOpPackageName() { return opPackage; }
    }

    private static final class ReflectDevice {
        private final String className;
        private final Context[] contexts;
        private volatile Object device;
        ReflectDevice(String className, Context[] contexts) {
            this.className = className;
            this.contexts = contexts;
        }
        Object ensureDevice() {
            Object cached = device;
            if (cached != null) return cached;
            synchronized (this) {
                if (device != null) return device;
                try {
                    Class<?> clazz = Class.forName(className);
                    for (Context context : contexts) {
                        if (context == null) continue;
                        Object created = invokeGetInstance(clazz, context);
                        if (created == null) created = invokeFactory(clazz, context);
                        if (created != null && clazz.isInstance(created)) {
                            device = created;
                            System.out.println("BYD device " + className + " context=" + context.getPackageName());
                            return created;
                        }
                    }
                } catch (Throwable t) {
                    System.err.println(className + " init failed: " + message(t));
                }
                return null;
            }
        }
        boolean initialize() { return ensureDevice() != null; }

        private Object invokeGetInstance(Class<?> clazz, Context context) {
            try {
                Method m = clazz.getDeclaredMethod("getInstance", Context.class);
                m.setAccessible(true);
                return m.invoke(null, context);
            } catch (Throwable ignored) {
                return null;
            }
        }
        private Object invokeFactory(Class<?> clazz, Context context) {
            String[] names = {"getInstance", "getSingleton", "create", "getDevice", "get"};
            for (String name : names) for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())) continue;
                try {
                    method.setAccessible(true);
                    Class<?>[] p = method.getParameterTypes();
                    if (p.length == 0) return method.invoke(null);
                    if (p.length == 1 && p[0].isAssignableFrom(context.getClass())) return method.invoke(null, context);
                    if (p.length == 1 && p[0] == Context.class) return method.invoke(null, context);
                } catch (Throwable ignored) {}
            }
            String[] fields = {"INSTANCE", "instance", "sInstance", "mInstance"};
            for (String fieldName : fields) try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null && clazz.isInstance(value)) return value;
            } catch (Throwable ignored) {}
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) try {
                ctor.setAccessible(true);
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 0) return ctor.newInstance();
                if (p.length == 1 && p[0].isAssignableFrom(context.getClass())) return ctor.newInstance(context);
                if (p.length == 1 && p[0] == Context.class) return ctor.newInstance(context);
            } catch (Throwable ignored) {}
            return null;
        }
        Double readNumber(String methodName) {
            Object d = ensureDevice();
            if (d == null) return null;
            try {
                Method method = findNoArg(d.getClass(), methodName);
                if (method == null) return null;
                method.setAccessible(true);
                Object result = method.invoke(d);
                if (result instanceof Number) return ((Number) result).doubleValue();
                if (result instanceof String) return Double.parseDouble((String) result);
            } catch (Throwable t) {
                System.err.println(className + "." + methodName + " failed: " + message(t));
            }
            return null;
        }
        private Method findNoArg(Class<?> clazz, String name) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                try { Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m; } catch (Throwable ignored) {}
            }
            try { return clazz.getMethod(name); } catch (Throwable ignored) { return null; }
        }
    }

    private static final class LaunchArgs {
        final String packageName;
        final int userId;
        private LaunchArgs(String packageName, int userId) { this.packageName = packageName; this.userId = userId; }
        static LaunchArgs parse(String[] args) {
            String packageName = null;
            int userId = 0;
            if (args != null) for (String arg : args) {
                if (arg != null && arg.startsWith("--package=")) packageName = arg.substring("--package=".length()).trim();
                else if (arg != null && arg.startsWith("--requested-user-id=")) {
                    try { userId = Integer.parseInt(arg.substring("--requested-user-id=".length()).trim()); } catch (Throwable ignored) {}
                }
            }
            if (packageName == null || packageName.isEmpty()) packageName = DEFAULT_PACKAGE;
            return new LaunchArgs(packageName, userId);
        }
    }

    private static String message(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String m = current.getMessage();
        return (m == null || m.isEmpty()) ? current.getClass().getName() : current.getClass().getName() + ": " + m;
    }

    private static final class HandlerThreadCompat {
        private final android.os.HandlerThread delegate;
        HandlerThreadCompat(String name) { delegate = new android.os.HandlerThread(name); }
        void start() { delegate.start(); }
        android.os.Looper getLooper() { return delegate.getLooper(); }
        void quitSafely() { delegate.quitSafely(); }
    }
}
