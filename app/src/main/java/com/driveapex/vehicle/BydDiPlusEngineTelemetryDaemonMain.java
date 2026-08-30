package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DiPlus-compatible BYD engine telemetry daemon.
 *
 * The important difference from the old path is the registration form:
 * DiPlus registers an AbsBYDAutoEngineListener with registerListener(listener, featureIds)
 * after filtering the requested feature IDs through BYDAutoDeviceFeaturesMap.
 * This class reproduces that path instead of polling getMotorSpeed().
 *
 * A disassembly of com.van.diplus (docs/BYD_LIVE_INTEGRATION.md) confirms the
 * HAL calls the listener's onDataEventChanged(int type, BYDAutoEventValue value)
 * for a registered feature, not onEngineSpeedChanged(int) directly -- DiPlus's
 * own listener only reaches onEngineSpeedChanged by forwarding into it itself
 * from inside onDataEventChanged. FrontMotorListener below overrides both so
 * it still catches the value regardless of which entry point this vehicle's
 * HAL build actually invokes.
 */
public final class BydDiPlusEngineTelemetryDaemonMain {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18765;
    private static final int SCAN_PORT = 18766;
    private static final String ENGINE_DEVICE = "android.hardware.bydauto.engine.BYDAutoEngineDevice";
    private static final String FEATURE_IDS = "android.hardware.bydauto.BYDAutoFeatureIds";
    private static final String FEATURE_MAP = "android.hardware.bydauto.BYDAutoDeviceFeaturesMap";
    private static final int FALLBACK_FRONT_MOTOR_SPEED = 1141899272;
    private static final int MAX_RPM = 25000;

    private BydDiPlusEngineTelemetryDaemonMain() {}

    private static final class Snapshot {
        volatile long timestamp;
        volatile int frontRpm;
        volatile double speed;
        volatile double throttle;
        volatile double brake;
        volatile boolean valid;
        volatile boolean listenerRegistered;
        volatile int deviceType = -1;
        volatile int featureId = FALLBACK_FRONT_MOTOR_SPEED;
        volatile String registration = "NOT_REGISTERED";
    }

    public static void main(String[] args) {
        HandlerThreadCompat thread = null;
        ServerSocket scanServer = null;
        try {
            LaunchArgs launch = LaunchArgs.parse(args);
            Context[] contexts = createContexts(launch.packageName, launch.userId);
            ReflectDevice engine = new ReflectDevice(ENGINE_DEVICE, contexts);
            ReflectDevice speed = new ReflectDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", contexts);
            Snapshot snapshot = new Snapshot();
            snapshot.featureId = resolveFeatureId("ENGINE_FRONT_MOTOR_SPEED", FALLBACK_FRONT_MOTOR_SPEED);

            thread = new HandlerThreadCompat("driveapex-diplus-byd-hal");
            thread.start();
            android.os.Handler handler = new android.os.Handler(thread.getLooper());
            CountDownLatch ready = new CountDownLatch(1);
            handler.post(() -> {
                boolean engineReady = engine.initialize();
                boolean speedReady = speed.initialize();
                snapshot.listenerRegistered = engine.registerDiPlusFrontMotorListener(snapshot);
                sampleBase(speed, snapshot);
                System.out.println("DIPLUS BYD init engine=" + engineReady
                        + " speed=" + speedReady
                        + " listener=" + snapshot.listenerRegistered
                        + " registration=" + snapshot.registration
                        + " deviceType=" + snapshot.deviceType
                        + " feature=" + snapshot.featureId);
                ready.countDown();
                handler.post(new Runnable() {
                    @Override public void run() {
                        sampleBase(speed, snapshot);
                        handler.postDelayed(this, 500L);
                    }
                });
            });
            ready.await(3, TimeUnit.SECONDS);

            scanServer = new ServerSocket(SCAN_PORT, 2, InetAddress.getByName(HOST));
            ServerSocket finalScanServer = scanServer;
            Thread scanThread = new Thread(() -> {
                while (!finalScanServer.isClosed()) {
                    try {
                        Socket client = finalScanServer.accept();
                        Thread worker = new Thread(() -> serveScan(client, snapshot), "driveapex-diplus-scan");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Throwable t) {
                        if (!finalScanServer.isClosed()) System.err.println("scan server: " + message(t));
                    }
                }
            }, "driveapex-diplus-scan-server");
            scanThread.setDaemon(true);
            scanThread.start();

            try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName(HOST))) {
                System.out.println("DriveApex DiPlus engine daemon ready on " + HOST + ":" + PORT);
                while (!server.isClosed()) {
                    Socket client = server.accept();
                    Thread worker = new Thread(() -> serveClient(client, snapshot), "driveapex-diplus-client");
                    worker.setDaemon(true);
                    worker.start();
                }
            }
        } catch (Throwable t) {
            System.err.println("DriveApex DiPlus daemon failed: " + message(t));
            t.printStackTrace(System.err);
        } finally {
            if (scanServer != null) try { scanServer.close(); } catch (Throwable ignored) {}
            if (thread != null) thread.quitSafely();
        }
    }

    private static void sampleBase(ReflectDevice speed, Snapshot snapshot) {
        Double kph = speed.readNumber("getCurrentSpeed");
        Double throttle = speed.readNumber("getAccelerateDeepness");
        Double brake = speed.readNumber("getBrakeDeepness");
        if (kph != null && kph >= 0 && kph <= 400) snapshot.speed = kph;
        if (throttle != null && throttle >= 0 && throttle <= 100) snapshot.throttle = throttle;
        if (brake != null && brake >= 0 && brake <= 100) snapshot.brake = brake;
        snapshot.timestamp = System.currentTimeMillis();
        snapshot.valid = snapshot.listenerRegistered || kph != null || throttle != null || brake != null;
    }

    private static void serveClient(Socket socket, Snapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            while (!socket.isClosed()) {
                if (!snapshot.valid) { Thread.sleep(50L); continue; }
                writer.write(Long.toString(snapshot.timestamp)); writer.write(',');
                writer.write(Integer.toString(snapshot.frontRpm)); writer.write(',');
                writer.write(Double.toString(snapshot.speed)); writer.write(',');
                writer.write(Double.toString(snapshot.throttle)); writer.write(',');
                writer.write(Double.toString(snapshot.brake)); writer.write(",BYD_DIPLUS_ENGINE_FEATURE_LISTENER");
                writer.newLine(); writer.flush();
                Thread.sleep(50L);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Throwable t) { System.err.println("client: " + message(t)); }
    }

    private static void serveScan(Socket socket, Snapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            writer.write("SCAN_READY\n");
            writer.write("HIT,ENGINE,ENGINE_FRONT_MOTOR_SPEED,feature=" + snapshot.featureId
                    + ",listener=" + snapshot.listenerRegistered + "\n");
            writer.write("HIT,ENGINE,REGISTRATION," + snapshot.registration + ",rpm=" + snapshot.frontRpm + "\n");
            writer.write("SCAN_DONE,2\n");
            writer.flush();
        } catch (Throwable ignored) {}
    }

    private static int resolveFeatureId(String name, int fallback) {
        try {
            Class<?> ids = Class.forName(FEATURE_IDS);
            Field field = ids.getField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable t) {
            System.err.println("feature resolve " + name + ": " + message(t));
            return fallback;
        }
    }

    private static int[] filterFeatureIdsForDevice(int deviceType, int requestedId) {
        try {
            Class<?> map = Class.forName(FEATURE_MAP);
            Method target = null;
            for (Method m : map.getDeclaredMethods()) {
                if (!"getFeatureIdsFromDevice".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && Set.class.isAssignableFrom(p[0]) && p[1] == int.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) return new int[]{requestedId};
            target.setAccessible(true);
            Set<Integer> requested = new HashSet<>();
            requested.add(requestedId);
            Object result = target.invoke(null, requested, deviceType);
            if (result instanceof int[]) {
                int[] ids = (int[]) result;
                if (ids.length > 0) return ids;
            }
        } catch (Throwable t) {
            System.err.println("feature filter failed: " + message(t));
        }
        return new int[]{requestedId};
    }

    private static Context[] createContexts(String requestedPackage, int userId) throws Exception {
        Context system = createSystemContext();
        return new Context[]{
                wrap(system, "com.android.shell"),
                wrap(tryPackageContext(system, requestedPackage, userId), requestedPackage),
                wrap(tryPackageContext(system, "com.overdrive.app", userId), "com.overdrive.app"),
                wrap(tryPackageContext(system, "com.byd.avc", userId), "com.byd.avc"),
                wrap(system, "android")
        };
    }

    private static Context tryPackageContext(Context system, String packageName, int userId) {
        if (packageName == null || packageName.isEmpty()) return null;
        try {
            Class<?> userHandle = Class.forName("android.os.UserHandle");
            Object handle = userHandle.getMethod("of", int.class).invoke(null, userId);
            Method method = Context.class.getMethod("createPackageContextAsUser", String.class, int.class, userHandle);
            return (Context) method.invoke(system, packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, handle);
        } catch (Throwable ignored) {
            try { return system.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY); }
            catch (Throwable ignoredAgain) { return null; }
        }
    }

    private static Context wrap(Context context, String packageName) {
        return context == null ? null : new BydPermissionContext(context, packageName);
    }

    private static Context createSystemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method main = activityThread.getDeclaredMethod("systemMain"); main.setAccessible(true);
        Object thread = main.invoke(null);
        Method get = activityThread.getDeclaredMethod("getSystemContext"); get.setAccessible(true);
        return (Context) get.invoke(thread);
    }

    private static final class BydPermissionContext extends ContextWrapper {
        private final String opPackage;
        BydPermissionContext(Context base, String packageName) { super(base); opPackage = packageName == null ? "com.driveapex" : packageName; }
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
            if (device != null) return device;
            synchronized (this) {
                if (device != null) return device;
                try {
                    Class<?> clazz = Class.forName(className);
                    for (Context context : contexts) {
                        if (context == null) continue;
                        try {
                            Method get = clazz.getDeclaredMethod("getInstance", Context.class);
                            get.setAccessible(true);
                            Object value = get.invoke(null, context);
                            if (value != null && clazz.isInstance(value)) { device = value; return value; }
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) { System.err.println(className + " init: " + message(t)); }
                return null;
            }
        }
        Double readNumber(String name) {
            Object d = ensure(); if (d == null) return null;
            try {
                Method m = d.getClass().getMethod(name); m.setAccessible(true);
                Object value = m.invoke(d);
                return value instanceof Number ? ((Number) value).doubleValue() : null;
            } catch (Throwable ignored) { return null; }
        }
        boolean registerDiPlusFrontMotorListener(Snapshot snapshot) {
            Object d = ensure(); if (d == null) return false;
            try {
                Method typeMethod = d.getClass().getMethod("getDevicetype");
                snapshot.deviceType = ((Number) typeMethod.invoke(d)).intValue();
                int[] featureIds = filterFeatureIdsForDevice(snapshot.deviceType, snapshot.featureId);
                RpmSink sink = value -> {
                    if (value < 0) value = Math.abs(value);
                    if (value <= MAX_RPM && value != 8191 && value != 16383 && value != 32767 && value != 65535) {
                        snapshot.frontRpm = value;
                        snapshot.timestamp = System.currentTimeMillis();
                        snapshot.valid = true;
                    }
                };
                FrontMotorListener listener = new FrontMotorListener(snapshot.featureId, sink);

                Method twoArg = null;
                for (Method m : d.getClass().getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!"registerListener".equals(m.getName()) || p.length != 2 || p[1] != int[].class) continue;
                    if (p[0].isAssignableFrom(listener.getClass())) { twoArg = m; break; }
                }
                if (twoArg == null) {
                    for (Class<?> c = d.getClass(); c != null && twoArg == null; c = c.getSuperclass()) {
                        for (Method m : c.getDeclaredMethods()) {
                            Class<?>[] p = m.getParameterTypes();
                            if ("registerListener".equals(m.getName()) && p.length == 2 && p[1] == int[].class && p[0].isAssignableFrom(listener.getClass())) {
                                twoArg = m; break;
                            }
                        }
                    }
                }
                if (twoArg == null) {
                    snapshot.registration = "NO_REGISTER_LISTENER_FEATURE_OVERLOAD";
                    return false;
                }
                twoArg.setAccessible(true);
                twoArg.invoke(d, listener, featureIds);
                snapshot.registration = twoArg.getName() + ":featureCount=" + featureIds.length + ":first=" + (featureIds.length == 0 ? -1 : featureIds[0]);
                return true;
            } catch (Throwable t) {
                snapshot.registration = "ERROR:" + message(t);
                return false;
            }
        }
    }

    private interface RpmSink { void accept(int rpm); }

    private static final class FrontMotorListener extends AbsBYDAutoEngineListener {
        private final int expectedFeatureId;
        private final RpmSink sink;
        FrontMotorListener(int expectedFeatureId, RpmSink sink) {
            this.expectedFeatureId = expectedFeatureId;
            this.sink = sink;
        }
        // Primary HAL entry point confirmed by disassembly; DiPlus itself
        // only reaches onEngineSpeedChanged via a manual call from here.
        @Override public void onDataEventChanged(int type, BYDAutoEventValue value) {
            if (type == expectedFeatureId && value != null) sink.accept(value.intValue);
        }
        // Kept as a defensive fallback in case this HAL build calls it directly.
        @Override public void onEngineSpeedChanged(int value) { sink.accept(value); }
    }

    private static final class LaunchArgs {
        final String packageName; final int userId;
        LaunchArgs(String packageName, int userId) { this.packageName = packageName; this.userId = userId; }
        static LaunchArgs parse(String[] args) {
            String pkg = "com.driveapex"; int user = 0;
            if (args != null) for (String arg : args) {
                if (arg != null && arg.startsWith("--package=")) pkg = arg.substring(10).trim();
                if (arg != null && arg.startsWith("--requested-user-id=")) {
                    try { user = Integer.parseInt(arg.substring(21).trim()); } catch (Throwable ignored) {}
                }
            }
            return new LaunchArgs(pkg, user);
        }
    }

    private static final class HandlerThreadCompat {
        private final android.os.HandlerThread thread;
        HandlerThreadCompat(String name) { thread = new android.os.HandlerThread(name); }
        void start() { thread.start(); }
        android.os.Looper getLooper() { return thread.getLooper(); }
        void quitSafely() { thread.quitSafely(); }
    }

    private static String message(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getName() : current.getClass().getName() + ": " + current.getMessage();
    }
}
