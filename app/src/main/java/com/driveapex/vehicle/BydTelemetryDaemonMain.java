package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Java-only shell-UID entry point for BYD telemetry.
 *
 * This class is intentionally independent of Kotlin, AndroidX and app helpers.
 * It is referenced directly by DriveApexApplication so D8 keeps it in the
 * primary dex, allowing app_process to load it from the installed APK.
 */
public final class BydTelemetryDaemonMain {
    private static final String PACKAGE_NAME = "com.driveapex";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18765;
    private static final long POLL_MS = 50L;

    private BydTelemetryDaemonMain() {
    }

    public static void main(String[] args) {
        try {
            Context context = createPackageContext();
            ReflectDevice speed = new ReflectDevice(
                    "android.hardware.bydauto.speed.BYDAutoSpeedDevice", context);
            ReflectDevice motor = new ReflectDevice(
                    "android.hardware.bydauto.motor.BYDAutoMotorDevice", context);
            ReflectDevice engine = new ReflectDevice(
                    "android.hardware.bydauto.engine.BYDAutoEngineDevice", context);

            try (ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName(HOST))) {
                System.out.println("DriveApex BYD daemon ready on " + HOST + ":" + PORT);
                while (!server.isClosed()) {
                    Socket client;
                    try {
                        client = server.accept();
                    } catch (Exception acceptError) {
                        System.err.println("accept failed: " + message(acceptError));
                        if (server.isClosed()) break;
                        continue;
                    }
                    Thread worker = new Thread(() -> serveClient(client, speed, motor, engine),
                            "driveapex-byd-client");
                    worker.setDaemon(true);
                    worker.start();
                }
            }
        } catch (Throwable t) {
            System.err.println("DriveApex BYD daemon startup failed: " + message(t));
            t.printStackTrace(System.err);
            throw t;
        }
    }

    private static void serveClient(
            Socket socket,
            ReflectDevice speed,
            ReflectDevice motor,
            ReflectDevice engine) {
        try (Socket ignored = socket;
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), "UTF-8"))) {
            while (!socket.isClosed()) {
                long timestamp = System.currentTimeMillis();
                double speedKph = valueOrZero(speed.readNumber("getCurrentSpeed"));
                double throttlePct = valueOrZero(speed.readNumber("getAccelerateDeepness"));
                double brakePct = valueOrZero(speed.readNumber("getBrakeDeepness"));

                // Primary RPM source from the verified target logs; engine speed
                // remains a firmware-dependent fallback.
                Double motorRpm = motor.readNumber("getMotorSpeed");
                Double engineRpm = engine.readNumber("getEngineSpeed");
                double rpm = firstNonNegativeFinite(motorRpm, engineRpm, 0.0);

                writer.write(Long.toString(timestamp));
                writer.write(',');
                writer.write(Double.toString(rpm));
                writer.write(',');
                writer.write(Double.toString(speedKph));
                writer.write(',');
                writer.write(Double.toString(throttlePct));
                writer.write(',');
                writer.write(Double.toString(brakePct));
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
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object thread;
        try {
            thread = current.invoke(null);
        } catch (Throwable ignored) {
            thread = null;
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
        BydPermissionContext(Context base) {
            super(base);
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkSelfPermission(String permission) {
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

        private Object ensure() {
            Object cached = device;
            if (cached != null) return cached;

            synchronized (this) {
                if (device != null) return device;
                try {
                    Class<?> clazz = Class.forName(className);
                    Object created = invokeFactory(clazz);
                    if (created == null) {
                        throw new IllegalStateException("No usable factory/instance for " + className);
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
                    if (!method.getName().equals(name)) continue;
                    if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                    Class<?>[] params = method.getParameterTypes();
                    try {
                        method.setAccessible(true);
                        if (params.length == 0) return method.invoke(null);
                        if (params.length == 1 && params[0].isAssignableFrom(context.getClass())) {
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
                java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor(Context.class);
                ctor.setAccessible(true);
                return ctor.newInstance(context);
            } catch (Throwable ignored) {
            }

            try {
                java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
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
                return null;
            } catch (Throwable t) {
                System.err.println(className + "." + methodName + " failed: " + message(t));
                return null;
            }
        }

        private Method findNoArgMethod(Class<?> clazz, String name) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterTypes().length == 0) {
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
