package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Read-only BYD motor-speed forensic scanner. */
public final class BydFeatureScannerMain {
    private static final String DEFAULT_PACKAGE = "com.driveapex";
    // Verified from the Overdrive/DiPlus reference implementation.
    private static final int FRONT_MOTOR_SPEED = 1141899272;
    private static final int REAR_MOTOR_SPEED = 621805576;
    private static final int FRONT_MOTOR_TORQUE = 1141899288;
    private static final int RANGE_START = 4080;
    private static final int RANGE_END = 4120;

    private BydFeatureScannerMain() {}

    public static void main(String[] args) {
        try {
            String packageName = DEFAULT_PACKAGE;
            int userId = 0;
            if (args != null) {
                for (String arg : args) {
                    if (arg != null && arg.startsWith("--package=")) {
                        packageName = arg.substring("--package=".length()).trim();
                    } else if (arg != null && arg.startsWith("--requested-user-id=")) {
                        try {
                            userId = Integer.parseInt(arg.substring("--requested-user-id=".length()).trim());
                        } catch (Throwable ignored) {}
                    }
                }
            }

            Context system = createSystemContext();
            Context pkg = tryPackageContext(system, packageName, userId);
            Context context = new BydPermissionContext(pkg == null ? system : pkg, packageName);

            scanDevice("ENGINE", "android.hardware.bydauto.engine.BYDAutoEngineDevice", context);
            scanDevice("MOTOR", "android.hardware.bydauto.motor.BYDAutoMotorDevice", context);
            scanDevice("SPEED", "android.hardware.bydauto.speed.BYDAutoSpeedDevice", context);
        } catch (Throwable t) {
            System.out.println("ERROR|" + message(t));
        }
    }

    private static void scanDevice(String label, String className, Context context) {
        try {
            Class<?> clazz = Class.forName(className);
            Object device = getInstance(clazz, context);
            if (device == null) {
                System.out.println("DEVICE|" + label + "|UNAVAILABLE");
                return;
            }
            System.out.println("DEVICE|" + label + "|" + device.getClass().getName());

            // Exact reference IDs first.
            reportFeature(label, "ENGINE_FRONT_MOTOR_SPEED", FRONT_MOTOR_SPEED, device);
            reportFeature(label, "ENGINE_REAR_MOTOR_SPEED", REAR_MOTOR_SPEED, device);
            reportFeature(label, "ENGINE_FRONT_MOTOR_TORQUE", FRONT_MOTOR_TORQUE, device);

            // Read-only narrow feature window used by the existing scanner.
            for (int id = RANGE_START; id <= RANGE_END; id++) {
                reportFeature(label, "FEATURE_" + id, id, device);
            }

            // Finally enumerate numeric no-arg getters whose names indicate speed/motor/RPM.
            for (Method m : allMethods(clazz)) {
                if (!m.getName().matches("(?i).*(motor|speed|rpm).*")) continue;
                if (m.getParameterTypes().length != 0) continue;
                try {
                    m.setAccessible(true);
                    Object value = m.invoke(device);
                    Double n = extractNumeric(value, 0);
                    if (n != null && Double.isFinite(n)) {
                        System.out.println(String.format(Locale.US,
                                "GETTER|%s|%s|%.6f", label, m.getName(), n));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            System.out.println("DEVICE_ERROR|" + label + "|" + message(t));
        }
    }

    private static void reportFeature(String label, String name, int id, Object device) {
        Class<?>[] types = {
                Integer.TYPE,
                Long.TYPE,
                Float.TYPE,
                Double.TYPE,
                Short.TYPE
        };
        String[] names = {"int", "long", "float", "double", "short"};

        for (int i = 0; i < types.length; i++) {
            try {
                Object raw = readFeature(device, id, types[i]);
                Double value = extractNumeric(raw, 0);
                if (value != null && Double.isFinite(value) && !isSentinel(value)) {
                    System.out.println(String.format(Locale.US,
                            "FEATURE|%s|%s|%d|%s|%.6f|%s",
                            label,
                            name,
                            id,
                            names[i],
                            value,
                            raw.getClass().getName()));
                    return;
                }
            } catch (Throwable ignored) {}
        }

        System.out.println(String.format(Locale.US,
                "FEATURE_MISS|%s|%s|%d", label, name, id));
    }

    private static boolean isSentinel(double value) {
        return value == -10011.0
                || value == -2147482645.0
                || value == 8191.0
                || value == -8191.0
                || value == 32767.0
                || value == -32768.0
                || value == 65535.0;
    }

    private static Object readFeature(Object device, int id, Class<?> type) throws Exception {
        Method get = findGenericGet(device.getClass());
        if (get == null) return null;
        get.setAccessible(true);
        return get.invoke(device, new int[]{id}, type);
    }

    private static Method findGenericGet(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("get")
                        && p.length == 2
                        && p[0] == int[].class
                        && p[1] == Class.class) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }

        for (Method m : clazz.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("get")
                    && p.length == 2
                    && p[0] == int[].class
                    && p[1] == Class.class) {
                return m;
            }
        }
        return null;
    }

    /** Handles primitive arrays and common BYD event-value wrappers. */
    private static Double extractNumeric(Object value, int depth) {
        if (value == null || depth > 6) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();

        Class<?> clazz = value.getClass();
        if (clazz.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            return length == 0
                    ? null
                    : extractNumeric(java.lang.reflect.Array.get(value, 0), depth + 1);
        }

        String[] methods = {
                "getValue", "getIntValue", "getLongValue",
                "getFloatValue", "getDoubleValue", "getData"
        };
        for (String name : methods) {
            Method m = findNoArg(clazz, name);
            if (m == null) continue;
            try {
                m.setAccessible(true);
                Double result = extractNumeric(m.invoke(value), depth + 1);
                if (result != null) return result;
            } catch (Throwable ignored) {}
        }

        String[] fields = {
                "value", "mValue", "intValue", "mIntValue",
                "data", "mData"
        };
        for (String name : fields) {
            Field f = findField(clazz, name);
            if (f == null) continue;
            try {
                f.setAccessible(true);
                Double result = extractNumeric(f.get(value), depth + 1);
                if (result != null) return result;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findNoArg(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == 0) return m;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method[] allMethods(Class<?> clazz) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                methods.put(method.toGenericString(), method);
            }
        }
        return methods.values().toArray(new Method[0]);
    }

    private static Object getInstance(Class<?> clazz, Context context) {
        try {
            Method m = clazz.getDeclaredMethod("getInstance", Context.class);
            m.setAccessible(true);
            return m.invoke(null, context);
        } catch (Throwable ignored) {}

        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            String name = m.getName();
            if (!(name.equals("getInstance") || name.equals("get") || name.equals("getDevice"))) continue;
            try {
                m.setAccessible(true);
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0) return m.invoke(null);
                if (p.length == 1
                        && (p[0] == Context.class || p[0].isAssignableFrom(context.getClass()))) {
                    return m.invoke(null, context);
                }
            } catch (Throwable ignored) {}
        }
        return null;
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

    private static Context tryPackageContext(Context system, String packageName, int userId) {
        try {
            Class<?> userHandle = Class.forName("android.os.UserHandle");
            Object handle = userHandle.getMethod("of", int.class).invoke(null, userId);
            Method method = Context.class.getMethod(
                    "createPackageContextAsUser", String.class, int.class, userHandle);
            return (Context) method.invoke(
                    system,
                    packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY,
                    handle);
        } catch (Throwable ignored) {
            try {
                return system.createPackageContext(
                        packageName,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static final class BydPermissionContext extends ContextWrapper {
        private final String opPackage;

        BydPermissionContext(Context base, String opPackage) {
            super(base);
            this.opPackage = opPackage;
        }

        @Override public Context getApplicationContext() { return this; }
        @Override public int checkCallingOrSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public String getOpPackageName() { return opPackage; }
    }

    private static String message(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ":" + String.valueOf(current.getMessage());
    }
}
