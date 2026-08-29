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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shell-UID BYD telemetry daemon.
 *
 * The important RPM path is the Engine device event stream. On the same DiLink-3
 * family used by working BYD telemetry apps, front/rear motor speed arrive through
 * onDataEventChanged(featureId, BYDAutoEventValue). The generic getMotorSpeed()
 * getter can remain zero/stale, so it is only a fallback here.
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

    // Confirmed by the working BYD telemetry reference. Resolve from the SDK when available.
    private static final int FRONT_RPM_FALLBACK = 1141899272;
    private static final int REAR_RPM_FALLBACK = 621805576;
    private static final int FRONT_TORQUE_FALLBACK = 1141899288;

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
        volatile int frontRpm;
        volatile int rearRpm;
        volatile long frontRpmAt;
        volatile long rearRpmAt;
    }

    private interface DataChangeTrigger { void onDataChanged(); }

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

                boolean engineEvents = engine.registerEngineEvents(snapshot, () -> sampleBase(speed, motor, engine, snapshot));
                boolean speedListener = speed.registerListener(() -> sampleBase(speed, motor, engine, snapshot));
                sampleBase(speed, motor, engine, snapshot);

                System.out.println("BYD LIVE init speed=" + speedReady + " motor=" + motorReady + " engine=" + engineReady
                        + " engineEvents=" + engineEvents + " speedListener=" + speedListener
                        + " frontId=" + snapshot.frontFeatureId + " rearId=" + snapshot.rearFeatureId
                        + " package=" + launchArgs.packageName + " user=" + launchArgs.userId);
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
            ServerSocket finalScanServer = scanServer;
            Thread scanThread = new Thread(() -> {
                while (!finalScanServer.isClosed()) {
                    try {
                        Socket client = finalScanServer.accept();
                        Thread worker = new Thread(() -> serveScan(client, snapshot), "driveapex-byd-scan");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Throwable t) {
                        if (!finalScanServer.isClosed()) System.err.println("sensor scan server failed: " + message(t));
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
        if (snapshot.frontRpmAt <= 0L || now - snapshot.frontRpmAt > 1500L) {
            Double fallback = sanitizeRpm(motor.readNumber("getMotorSpeed"));
            if (fallback == null) fallback = sanitizeRpm(engine.readNumber("getEngineSpeed"));
            if (fallback != null) snapshot.rpm = fallback;
        } else {
            snapshot.rpm = snapshot.frontRpm;
        }

        snapshot.timestamp = now;
        snapshot.valid = speedKph != null || throttlePct != null || brakePct != null || snapshot.frontRpmAt > 0L;
        snapshot.eventCount++;
    }

    private static void serveClient(Socket socket, TelemetrySnapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            while (!socket.isClosed()) {
                if (!snapshot.valid || snapshot.timestamp <= 0L) { Thread.sleep(50L); continue; }
                writer.write(Long.toString(snapshot.timestamp)); writer.write(',');
                writer.write(Double.toString(snapshot.rpm)); writer.write(',');
                writer.write(Double.toString(snapshot.speedKph)); writer.write(',');
                writer.write(Double.toString(snapshot.throttlePct)); writer.write(',');
                writer.write(Double.toString(snapshot.brakePct)); writer.write(",BYD_ENGINE_EVENTS");
                writer.newLine(); writer.flush(); Thread.sleep(50L);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (Throwable t) { System.err.println("client failed: " + message(t)); }
    }

    private static void serveScan(Socket socket, TelemetrySnapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            writer.write("SCAN_READY\n");
            writer.write("ENGINE_FRONT_MOTOR_SPEED_ID," + snapshot.frontFeatureId + "\n");
            writer.write("ENGINE_REAR_MOTOR_SPEED_ID," + snapshot.rearFeatureId + "\n");
            writer.write("ENGINE_FRONT_MOTOR_TORQUE_ID," + FRONT_TORQUE_FALLBACK + "\n");
            writer.flush();
            long end = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                writer.write(String.format(java.util.Locale.US,
                        "LIVE_RPM,frontId=%d,front=%d,frontAgeMs=%d,rearId=%d,rear=%d,rearAgeMs=%d\n",
                        snapshot.frontFeatureId, snapshot.frontRpm,
                        snapshot.frontRpmAt == 0L ? -1L : now - snapshot.frontRpmAt,
                        snapshot.rearFeatureId, snapshot.rearRpm,
                        snapshot.rearRpmAt == 0L ? -1L : now - snapshot.rearRpmAt));
                writer.flush();
                Thread.sleep(250L);
            }
            writer.write("SCAN_DONE\n"); writer.flush();
        } catch (Throwable t) {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static Double sanitizeRpm(Double value) {
        if (value == null || !Double.isFinite(value)) return null;
        if (value == 8191.0 || value == -8191.0 || value == 32767.0 || value == -32768.0
                || value == 65535.0 || value == -65535.0) return null;
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
        return new Context[]{shell, wrap(requested, requestedPackage), preferred == null ? null : wrap(preferred, PREFERRED_PACKAGE), byd == null ? null : wrap(byd, FALLBACK_PACKAGE), app == null ? null : wrap(app, DEFAULT_PACKAGE)};
    }

    private static Context tryPackageContext(Context system, String packageName, int userId) {
        if (packageName == null || packageName.trim().isEmpty()) return null;
        try {
            Class<?> userHandleClass = Class.forName("android.os.UserHandle");
            Object userHandle = userHandleClass.getMethod("of", int.class).invoke(null, userId);
            Method m = Context.class.getMethod("createPackageContextAsUser", String.class, int.class, userHandleClass);
            return (Context) m.invoke(system, packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
        } catch (Throwable ignored) {
            try { return system.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY); }
            catch (Throwable ignoredAgain) { return null; }
        }
    }

    private static Context wrap(Context context, String packageName) { return context == null ? null : new BydPermissionContext(context, packageName); }

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
        BydPermissionContext(Context base, String opPackage) { super(base); this.opPackage = opPackage == null || opPackage.isEmpty() ? DEFAULT_PACKAGE : opPackage; }
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
        ReflectDevice(String className, Context[] contexts) { this.className = className; this.contexts = contexts; }

        boolean initialize() { return ensure() != null; }

        private Object ensure() {
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

        private Object invokeGetInstance(Class<?> clazz, Context context) {
            try {
                Method m = clazz.getDeclaredMethod("getInstance", Context.class);
                m.setAccessible(true);
                return m.invoke(null, context);
            } catch (Throwable ignored) { return null; }
        }

        private Object invokeFactory(Class<?> clazz, Context context) {
            String[] names = {"getInstance", "getSingleton", "create", "getDevice", "get"};
            for (String name : names) {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())) continue;
                    try {
                        method.setAccessible(true);
                        Class<?>[] p = method.getParameterTypes();
                        if (p.length == 0) return method.invoke(null);
                        if (p.length == 1 && p[0].isAssignableFrom(context.getClass())) return method.invoke(null, context);
                        if (p.length == 1 && p[0] == Context.class) return method.invoke(null, context);
                    } catch (Throwable ignored) {}
                }
            }
            String[] fields = {"INSTANCE", "instance", "sInstance", "mInstance"};
            for (String fieldName : fields) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null && clazz.isInstance(value)) return value;
                } catch (Throwable ignored) {}
            }
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 0) return ctor.newInstance();
                    if (p.length == 1 && p[0].isAssignableFrom(context.getClass())) return ctor.newInstance(context);
                    if (p.length == 1 && p[0] == Context.class) return ctor.newInstance(context);
                } catch (Throwable ignored) {}
            }
            return null;
        }

        Double readNumber(String methodName) {
            Object d = ensure();
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

        boolean registerListener(DataChangeTrigger trigger) {
            Object d = ensure();
            if (d == null) return false;
            try {
                Method register = findRegisterOneArg(d.getClass());
                if (register == null) return false;
                Class<?> listenerType = register.getParameterTypes()[0];
                if (!listenerType.isInterface()) return false;
                Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        try {
                            if (method.getName().endsWith("Changed") || method.getName().contains("Event")) trigger.onDataChanged();
                        } catch (Throwable ignored) {}
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        return null;
                    }
                });
                register.setAccessible(true);
                register.invoke(d, listener);
                return true;
            } catch (Throwable t) {
                System.err.println(className + " listener failed: " + message(t));
                return false;
            }
        }

        boolean registerEngineEvents(TelemetrySnapshot snapshot, DataChangeTrigger trigger) {
            Object d = ensure();
            if (d == null) return false;
            try {
                Method register = findRegisterTwoArg(d.getClass());
                if (register == null) return false;
                Class<?> listenerType = register.getParameterTypes()[0];
                if (!listenerType.isInterface()) return false;

                Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        try {
                            if ("onDataEventChanged".equals(method.getName()) && args != null && args.length >= 2) {
                                Integer featureId = asInt(args[0]);
                                Object event = args[1];
                                Double value = eventNumber(event);
                                if (featureId != null && value != null && Double.isFinite(value)) {
                                    int v = (int) Math.round(value);
                                    int frontId = snapshot.frontFeatureId;
                                    int rearId = snapshot.rearFeatureId;
                                    if (featureId == frontId && validRpm(v)) {
                                        snapshot.frontRpm = Math.abs(v);
                                        snapshot.frontRpmAt = System.currentTimeMillis();
                                        snapshot.rpm = snapshot.frontRpm;
                                        snapshot.valid = true;
                                        snapshot.timestamp = snapshot.frontRpmAt;
                                    } else if (featureId == rearId && validRpm(v)) {
                                        snapshot.rearRpm = Math.abs(v);
                                        snapshot.rearRpmAt = System.currentTimeMillis();
                                    }
                                }
                                trigger.onDataChanged();
                            }
                        } catch (Throwable t) {
                            System.err.println("BYD engine event failed: " + message(t));
                        }
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        return null;
                    }
                });

                int[] explicit = new int[]{snapshot.frontFeatureId, snapshot.rearFeatureId};
                // Match the proven BYD implementation: subscribe-all first, then explicit IDs.
                try { register.invoke(d, listener, new int[0]); } catch (Throwable ignored) {}
                register.invoke(d, listener, explicit);
                return true;
            } catch (Throwable t) {
                System.err.println(className + " engine event registration failed: " + message(t));
                return false;
            }
        }

        private Method findRegisterOneArg(Class<?> clazz) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass())
                for (Method m : c.getDeclaredMethods())
                    if ("registerListener".equals(m.getName()) && m.getParameterTypes().length == 1) return m;
            for (Method m : clazz.getMethods()) if ("registerListener".equals(m.getName()) && m.getParameterTypes().length == 1) return m;
            return null;
        }

        private Method findRegisterTwoArg(Class<?> clazz) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if ("registerListener".equals(m.getName()) && p.length == 2 && p[1] == int[].class) return m;
                }
            }
            for (Method m : clazz.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if ("registerListener".equals(m.getName()) && p.length == 2 && p[1] == int[].class) return m;
            }
            return null;
        }

        private Method findNoArg(Class<?> clazz, String name) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                try { return c.getDeclaredMethod(name); } catch (Throwable ignored) {}
            }
            try { return clazz.getMethod(name); } catch (Throwable ignored) { return null; }
        }
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return null;
        try { return Integer.parseInt(String.valueOf(value)); } catch (Throwable ignored) { return null; }
    }

    private static boolean validRpm(int value) {
        int magnitude = Math.abs(value);
        return magnitude <= MAX_RPM && magnitude != 8191 && magnitude != 16383 && magnitude != 32767 && magnitude != 65535;
    }

    private static Double eventNumber(Object event) {
        if (event == null) return null;
        if (event instanceof Number) return ((Number) event).doubleValue();
        for (String name : new String[]{"intValue", "getIntValue", "longValue", "getLongValue", "doubleValue", "getDoubleValue", "floatValue", "getFloatValue", "value", "getValue", "data", "getData"}) {
            try {
                Field f = event.getClass().getField(name);
                f.setAccessible(true);
                Object v = f.get(event);
                Double n = asDouble(v);
                if (n != null) return n;
            } catch (Throwable ignored) {}
            try {
                Method m = event.getClass().getMethod(name);
                m.setAccessible(true);
                Double n = asDouble(m.invoke(event));
                if (n != null) return n;
            } catch (Throwable ignored) {}
        }
        for (Class<?> c = event.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (!(n.contains("value") || n.contains("data"))) continue;
                try {
                    f.setAccessible(true);
                    Double x = asDouble(f.get(event));
                    if (x != null) return x;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return null;
        try { return Double.parseDouble(String.valueOf(value)); } catch (Throwable ignored) { return null; }
    }

    private static String message(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String m = current.getMessage();
        return (m == null || m.isEmpty()) ? current.getClass().getName() : current.getClass().getName() + ": " + m;
    }

    private static final class LaunchArgs {
        final String packageName; final int userId;
        private LaunchArgs(String packageName, int userId) { this.packageName = packageName; this.userId = userId; }
        static LaunchArgs parse(String[] args) {
            String packageName = null; int userId = 0;
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

    private static final class HandlerThreadCompat {
        private final android.os.HandlerThread delegate;
        HandlerThreadCompat(String name) { delegate = new android.os.HandlerThread(name); }
        void start() { delegate.start(); }
        android.os.Looper getLooper() { return delegate.getLooper(); }
        void quitSafely() { delegate.quitSafely(); }
    }
}
