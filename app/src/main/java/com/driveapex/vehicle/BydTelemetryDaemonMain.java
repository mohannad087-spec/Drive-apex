package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Shell-UID entry point for BYD telemetry using the same package-context model as DiPlus. */
public final class BydTelemetryDaemonMain {
    private static final String DEFAULT_PACKAGE = "com.driveapex";
    private static final String PREFERRED_PACKAGE = "com.overdrive.app";
    private static final String FALLBACK_PACKAGE = "com.byd.avc";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18765;
    private static final long POLL_MS = 50L;
    private static final double MAX_RPM = 25000.0;

    private BydTelemetryDaemonMain() {}

    private static final class TelemetrySnapshot {
        volatile long timestamp;
        volatile double rpm;
        volatile double speedKph;
        volatile double throttlePct;
        volatile double brakePct;
        volatile boolean valid;
    }

    public static void main(String[] args) {
        HandlerThreadCompat halThread = null;
        try {
            LaunchArgs launchArgs = LaunchArgs.parse(args);
            Context[] contexts = createContexts(launchArgs.packageName, launchArgs.userId);

            ReflectDevice speed = new ReflectDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", contexts);
            ReflectDevice motor = new ReflectDevice("android.hardware.bydauto.motor.BYDAutoMotorDevice", contexts);
            ReflectDevice engine = new ReflectDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", contexts);
            TelemetrySnapshot snapshot = new TelemetrySnapshot();

            halThread = new HandlerThreadCompat("driveapex-byd-hal");
            halThread.start();
            android.os.Handler halHandler = new android.os.Handler(halThread.getLooper());
            CountDownLatch initialized = new CountDownLatch(1);
            halHandler.post(() -> {
                boolean speedReady = speed.initialize();
                boolean motorReady = motor.initialize();
                boolean engineReady = engine.initialize();
                System.out.println("BYD HAL init speed=" + speedReady + " motor=" + motorReady
                        + " engine=" + engineReady + " package=" + launchArgs.packageName
                        + " user=" + launchArgs.userId);
                initialized.countDown();
                halHandler.post(new Runnable() {
                    @Override public void run() {
                        sample(speed, motor, engine, snapshot);
                        halHandler.postDelayed(this, POLL_MS);
                    }
                });
            });
            initialized.await(3, TimeUnit.SECONDS);

            try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName(HOST))) {
                System.out.println("DriveApex BYD daemon ready on " + HOST + ":" + PORT);
                while (!server.isClosed()) {
                    try {
                        final Socket client = server.accept();
                        Thread worker = new Thread(() -> serveClient(client, snapshot), "driveapex-byd-client");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Exception acceptError) {
                        System.err.println("accept failed: " + message(acceptError));
                        if (server.isClosed()) break;
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("DriveApex BYD daemon startup failed: " + message(t));
            t.printStackTrace(System.err);
        } finally {
            if (halThread != null) halThread.quitSafely();
        }
    }

    private static void sample(ReflectDevice speed, ReflectDevice motor, ReflectDevice engine, TelemetrySnapshot snapshot) {
        Double speedKph = sanitizeSpeed(speed.readNumber("getCurrentSpeed"));
        Double throttlePct = sanitizePedal(speed.readNumber("getAccelerateDeepness"));
        Double brakePct = sanitizePedal(speed.readNumber("getBrakeDeepness"));
        Double motorRpm = sanitizeRpm(motor.readNumber("getMotorSpeed"));
        Double engineRpm = sanitizeRpm(engine.readNumber("getEngineSpeed"));
        boolean valid = speedKph != null || throttlePct != null || brakePct != null || motorRpm != null || engineRpm != null;
        if (!valid) { snapshot.valid = false; return; }
        snapshot.speedKph = valueOrZero(speedKph);
        snapshot.throttlePct = valueOrZero(throttlePct);
        snapshot.brakePct = valueOrZero(brakePct);
        snapshot.rpm = firstValid(motorRpm, engineRpm, 0.0);
        snapshot.timestamp = System.currentTimeMillis();
        snapshot.valid = true;
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

    private static void serveClient(Socket socket, TelemetrySnapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            while (!socket.isClosed()) {
                if (!snapshot.valid || snapshot.timestamp <= 0L) { Thread.sleep(POLL_MS); continue; }
                writer.write(Long.toString(snapshot.timestamp)); writer.write(',');
                writer.write(Double.toString(snapshot.rpm)); writer.write(',');
                writer.write(Double.toString(snapshot.speedKph)); writer.write(',');
                writer.write(Double.toString(snapshot.throttlePct)); writer.write(',');
                writer.write(Double.toString(snapshot.brakePct)); writer.write(",BYD_DAEMON");
                writer.newLine(); writer.flush(); Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (Throwable t) { System.err.println("client failed: " + message(t)); }
    }

    private static double valueOrZero(Double value) { return value != null && Double.isFinite(value) ? value : 0.0; }
    private static double firstValid(Double first, Double second, double fallback) {
        if (first != null && Double.isFinite(first) && first >= 0.0 && first <= MAX_RPM) return first;
        if (second != null && Double.isFinite(second) && second >= 0.0 && second <= MAX_RPM) return second;
        return fallback;
    }

    private static Context[] createContexts(String requestedPackage, int userId) throws Exception {
        Context systemContext = createSystemContext();
        Context requested = tryPackageContext(systemContext, requestedPackage, userId);
        Context preferred = tryPackageContext(systemContext, PREFERRED_PACKAGE, userId);
        Context byd = tryPackageContext(systemContext, FALLBACK_PACKAGE, userId);
        Context app = tryPackageContext(systemContext, DEFAULT_PACKAGE, userId);
        return new Context[] {
                wrap(requested, requestedPackage),
                wrap(preferred, PREFERRED_PACKAGE),
                wrap(byd, FALLBACK_PACKAGE),
                wrap(app, DEFAULT_PACKAGE),
                wrap(systemContext, "android")
        };
    }

    private static Context tryPackageContext(Context systemContext, String packageName, int userId) {
        if (packageName == null || packageName.trim().isEmpty()) return null;
        try {
            Class<?> userHandleClass = Class.forName("android.os.UserHandle");
            Object userHandle = userHandleClass.getMethod("of", int.class).invoke(null, userId);
            Method m = Context.class.getMethod("createPackageContextAsUser", String.class, int.class, userHandleClass);
            Context ctx = (Context) m.invoke(systemContext, packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, userHandle);
            System.out.println("BYD candidate package context: " + packageName + " user=" + userId);
            return ctx;
        } catch (Throwable ignored) {
            try {
                Context ctx = systemContext.createPackageContext(packageName,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                System.out.println("BYD legacy package context: " + packageName);
                return ctx;
            } catch (Throwable t) {
                System.err.println("BYD package context unavailable " + packageName + ": " + message(t));
                return null;
            }
        }
    }

    private static Context wrap(Context context, String packageName) {
        return context == null ? null : new BydPermissionContext(context, packageName);
    }

    private static Context createSystemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static final class BydPermissionContext extends ContextWrapper {
        private final String opPackage;
        BydPermissionContext(Context base, String opPackage) {
            super(base);
            this.opPackage = opPackage == null || opPackage.isEmpty() ? safePackageName(base) : opPackage;
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkCallingOrSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkPermission(String permission, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public String getOpPackageName() { return opPackage; }
        private static String safePackageName(Context base) {
            try { return base == null ? DEFAULT_PACKAGE : base.getPackageName(); }
            catch (Throwable ignored) { return DEFAULT_PACKAGE; }
        }
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

    private static final class ReflectDevice {
        private final String className; private final Context[] contexts; private volatile Object device;
        ReflectDevice(String className, Context[] contexts) { this.className = className; this.contexts = contexts; }
        boolean initialize() { return ensure() != null; }
        private Object ensure() {
            Object cached = device; if (cached != null) return cached;
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
                            System.out.println(className + " receiver=" + created.getClass().getName() + " context=" + context.getPackageName());
                            return created;
                        }
                    }
                    throw new IllegalStateException("No usable instance for " + className);
                } catch (Throwable t) { System.err.println(className + " init failed: " + message(t)); return null; }
            }
        }
        private Object invokeGetInstance(Class<?> clazz, Context context) {
            try {
                Method m = clazz.getDeclaredMethod("getInstance", Context.class); m.setAccessible(true); return m.invoke(null, context);
            } catch (Throwable t) { System.err.println(className + ".getInstance(Context) failed: " + message(t)); return null; }
        }
        private Object invokeFactory(Class<?> clazz, Context context) {
            String[] names = {"getInstance", "getSingleton", "create", "getDevice", "get"};
            for (String name : names) for (Method method : clazz.getDeclaredMethods()) {
                if (!method.getName().equals(name) || !Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] params = method.getParameterTypes();
                try {
                    method.setAccessible(true);
                    if (params.length == 0) return method.invoke(null);
                    if (params.length == 1 && params[0].isAssignableFrom(context.getClass())) return method.invoke(null, context);
                    if (params.length == 1 && params[0] == Context.class) return method.invoke(null, context);
                } catch (Throwable t) { System.err.println(className + "." + name + " failed: " + message(t)); }
            }
            String[] fields = {"INSTANCE", "instance", "sInstance", "mInstance"};
            for (String fieldName : fields) try {
                Field field = clazz.getDeclaredField(fieldName); field.setAccessible(true); Object value = field.get(null);
                if (value != null && clazz.isInstance(value)) return value;
            } catch (Throwable ignored) {}
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) try {
                ctor.setAccessible(true); Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 0) return ctor.newInstance();
                if (p.length == 1 && p[0].isAssignableFrom(context.getClass())) return ctor.newInstance(context);
                if (p.length == 1 && p[0] == Context.class) return ctor.newInstance(context);
            } catch (Throwable t) { System.err.println(className + " constructor failed: " + message(t)); }
            return null;
        }
        Double readNumber(String methodName) {
            Object d = ensure(); if (d == null) return null;
            try {
                Method method = findNoArgMethod(d.getClass(), methodName); if (method == null) return null;
                method.setAccessible(true); Object result = method.invoke(d);
                if (result instanceof Number) return ((Number) result).doubleValue();
                if (result instanceof String) return Double.parseDouble((String) result);
            } catch (Throwable t) { System.err.println(className + "." + methodName + " failed: " + message(t)); }
            return null;
        }
        private Method findNoArgMethod(Class<?> clazz, String name) {
            Class<?> current = clazz;
            while (current != null) { try { Method m = current.getDeclaredMethod(name); m.setAccessible(true); return m; } catch (Throwable ignored) {} current = current.getSuperclass(); }
            try { return clazz.getMethod(name); } catch (Throwable ignored) { return null; }
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
