package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Java-only shell-UID entry point for BYD telemetry. */
public final class BydTelemetryDaemonMain {
    private static final String PACKAGE_NAME = "com.driveapex";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18765;
    private static final long POLL_MS = 50L;

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
        HandlerThread halThread = null;
        try {
            Context context = createPackageContext();
            ReflectDevice speed = new ReflectDevice(
                    "android.hardware.bydauto.speed.BYDAutoSpeedDevice", context);
            ReflectDevice motor = new ReflectDevice(
                    "android.hardware.bydauto.motor.BYDAutoMotorDevice", context);
            ReflectDevice engine = new ReflectDevice(
                    "android.hardware.bydauto.engine.BYDAutoEngineDevice", context);
            TelemetrySnapshot snapshot = new TelemetrySnapshot();

            halThread = new HandlerThread("driveapex-byd-hal");
            halThread.start();
            Handler halHandler = new Handler(halThread.getLooper());
            CountDownLatch initialized = new CountDownLatch(1);
            halHandler.post(() -> {
                boolean speedReady = speed.initialize();
                boolean motorReady = motor.initialize();
                boolean engineReady = engine.initialize();
                System.out.println("BYD HAL init speed=" + speedReady
                        + " motor=" + motorReady + " engine=" + engineReady);
                initialized.countDown();
                halHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        sample(speed, motor, engine, snapshot);
                        halHandler.postDelayed(this, POLL_MS);
                    }
                });
            });
            initialized.await(3, TimeUnit.SECONDS);

            try (ServerSocket server = new ServerSocket(
                    PORT, 4, InetAddress.getByName(HOST))) {
                System.out.println("DriveApex BYD daemon ready on " + HOST + ":" + PORT);
                while (!server.isClosed()) {
                    try {
                        final Socket client = server.accept();
                        Thread worker = new Thread(
                                () -> serveClient(client, snapshot), "driveapex-byd-client");
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
            if (halThread != null) {
                halThread.quitSafely();
            }
        }
    }

    private static void sample(
            ReflectDevice speed,
            ReflectDevice motor,
            ReflectDevice engine,
            TelemetrySnapshot snapshot) {
        Double speedKph = speed.readNumber("getCurrentSpeed");
        Double throttlePct = speed.readNumber("getAccelerateDeepness");
        Double brakePct = speed.readNumber("getBrakeDeepness");
        Double motorRpm = motor.readNumber("getMotorSpeed");
        Double engineRpm = engine.readNumber("getEngineSpeed");
        boolean valid = speedKph != null
                || throttlePct != null
                || brakePct != null
                || motorRpm != null
                || engineRpm != null;
        if (!valid) {
            snapshot.valid = false;
            return;
        }

        snapshot.speedKph = valueOrZero(speedKph);
        snapshot.throttlePct = valueOrZero(throttlePct);
        snapshot.brakePct = valueOrZero(brakePct);
        snapshot.rpm = firstNonNegativeFinite(motorRpm, engineRpm, 0.0);
        snapshot.timestamp = System.currentTimeMillis();
        snapshot.valid = true;
    }

    private static void serveClient(Socket socket, TelemetrySnapshot snapshot) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            while (!socket.isClosed()) {
                if (!snapshot.valid || snapshot.timestamp <= 0L) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                writer.write(Long.toString(snapshot.timestamp));
                writer.write(',');
                writer.write(Double.toString(snapshot.rpm));
                writer.write(',');
                writer.write(Double.toString(snapshot.speedKph));
                writer.write(',');
                writer.write(Double.toString(snapshot.throttlePct));
                writer.write(',');
                writer.write(Double.toString(snapshot.brakePct));
                writer.write(",BYD_DAEMON");
                writer.newLine();
                writer.flush();
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            System.err.println("client failed: " + message(t));
        }
    }

    private static double valueOrZero(Double value) {
        return value != null && Double.isFinite(value) ? value : 0.0;
    }

    private static double firstNonNegativeFinite(Double first, Double second, double fallback) {
        if (first != null && Double.isFinite(first) && first >= 0.0) return first;
        if (second != null && Double.isFinite(second) && second >= 0.0) return second;
        return fallback;
    }

    private static Context createPackageContext() throws Exception {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object thread = null;
        try {
            Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            thread = ctor.newInstance();
        } catch (Throwable constructorFailure) {
            System.err.println("ActivityThread ctor path failed: " + message(constructorFailure));
        }
        if (thread == null) {
            Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            thread = current.invoke(null);
        }
        if (thread == null) {
            Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            thread = systemMain.invoke(null);
        }
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context systemContext = (Context) getSystemContext.invoke(thread);
        Context packageContext = systemContext.createPackageContext(
                PACKAGE_NAME,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        return new BydPermissionContext(packageContext);
    }

    private static final class BydPermissionContext extends ContextWrapper {
        BydPermissionContext(Context base) { super(base); }
        @Override public int checkCallingOrSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }
    }

    private static final class ReflectDevice {
        private final String className;
        private final Context context;
        private volatile Object device;

        ReflectDevice(String className, Context context) {
            this.className = className;
            this.context = context;
        }

        boolean initialize() {
            return ensure() != null;
        }

        private Object ensure() {
            Object cached = device;
            if (cached != null) return cached;
            synchronized (this) {
                if (device != null) return device;
                try {
                    Class<?> clazz = Class.forName(className);
                    Object created = invokeFactory(clazz);
                    if (created == null) {
                        throw new IllegalStateException(
                                "No usable factory/instance for " + className);
                    }
                    device = created;
                    return created;
                } catch (Throwable t) {
                    System.err.println(className + " init failed: " + message(t));
                    return null;
                }
            }
        }

        private Object invokeFactory(Class<?> clazz) throws Exception {
            String[] names = {"getInstance", "getSingleton", "create", "getDevice", "get"};
            for (String name : names) {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (!method.getName().equals(name)
                            || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                        continue;
                    }
                    Class<?>[] params = method.getParameterTypes();
                    try {
                        method.setAccessible(true);
                        if (params.length == 0) return method.invoke(null);
                        if (params.length == 1
                                && params[0].isAssignableFrom(context.getClass())) {
                            return method.invoke(null, context);
                        }
                        if (params.length == 1 && params[0].isAssignableFrom(Context.class)) {
                            return method.invoke(null, context);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            String[] fields = {"INSTANCE", "instance", "sInstance", "mInstance"};
            for (String fieldName : fields) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
            try {
                Constructor<?> ctor = clazz.getDeclaredConstructor(Context.class);
                ctor.setAccessible(true);
                return ctor.newInstance(context);
            } catch (Throwable ignored) {
            }
            try {
                Constructor<?> ctor = clazz.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (Throwable ignored) {
            }
            return null;
        }

        Double readNumber(String methodName) {
            Object d = ensure();
            if (d == null) return null;
            try {
                Method method = findNoArgMethod(d.getClass(), methodName);
                if (method == null) return null;
                method.setAccessible(true);
                Object result = method.invoke(d);
                if (result instanceof Number) return ((Number) result).doubleValue();
                if (result instanceof String) return Double.parseDouble((String) result);
            } catch (Throwable t) {
                System.err.println(className + "." + methodName
                        + " failed: " + message(t));
            }
            return null;
        }

        private Method findNoArgMethod(Class<?> clazz, String name) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals(name)
                            && method.getParameterTypes().length == 0) {
                        return method;
                    }
                }
            }
            return null;
        }
    }

    private static String message(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty()
                ? current.getClass().getSimpleName()
                : current.getClass().getSimpleName() + ": " + message;
    }
}
