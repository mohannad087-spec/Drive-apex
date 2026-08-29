package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Read-only BYD HAL forensic scanner. */
public final class BydFeatureScannerMain {
    private static final String DEFAULT_PACKAGE = "com.driveapex";
    // Reference IDs from the connected OverDrive/DiPlus source.
    private static final int FRONT_MOTOR_SPEED = 1141899272;
    private static final int REAR_MOTOR_SPEED = 621805576;
    private static final int FRONT_MOTOR_TORQUE = 1141899288;
    private static final int ENGINE_SPEED_ALT = 282066952;

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
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            Context system = createSystemContext();
            Context requested = tryPackageContext(system, packageName, userId);
            Context context = new BydPermissionContext(
                    requested == null ? system : requested, packageName);

            scanManager(context);
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
            probeFeature(label, "FRONT_MOTOR_SPEED", FRONT_MOTOR_SPEED, device);
            probeFeature(label, "REAR_MOTOR_SPEED", REAR_MOTOR_SPEED, device);
            probeFeature(label, "FRONT_MOTOR_TORQUE", FRONT_MOTOR_TORQUE, device);
            if ("ENGINE".equals(label)) {
                probeFeature(label, "ENGINE_SPEED_ALT", ENGINE_SPEED_ALT, device);
            }

            for (Method method : allMethods(clazz)) {
                if (method.getParameterTypes().length != 0) continue;
                if (!method.getName().matches("(?i).*(motor|speed|rpm|engine).*")) continue;
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(device);
                    Double n = extractNumeric(value, 0);
                    if (n != null && isFinite(n)) {
                        System.out.println(String.format(Locale.US,
                                "GETTER|%s|%s|%.6f", label, method.getName(), n.doubleValue()));
                    } else {
                        System.out.println("GETTER_RESULT|" + label + "|" + method.getName()
                                + "|" + describe(value));
                    }
                } catch (Throwable t) {
                    System.out.println("GETTER_ERROR|" + label + "|" + method.getName()
                            + "|" + message(t));
                }
            }
        } catch (Throwable t) {
            System.out.println("DEVICE_ERROR|" + label + "|" + message(t));
        }
    }

    private static void probeFeature(String label, String name, int id, Object device) {
        boolean hit = false;

        Method arrayGet = findMethod(device.getClass(), "get", int[].class, Class.class);
        if (arrayGet != null) {
            Class<?>[] types = {
                    Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE, Short.TYPE, Byte.TYPE
            };
            String[] names = {"int", "long", "float", "double", "short", "byte"};
            for (int i = 0; i < types.length; i++) {
                try {
                    arrayGet.setAccessible(true);
                    Object raw = arrayGet.invoke(device, new int[]{id}, types[i]);
                    System.out.println("ARRAY_GET|" + label + "|" + name + "|" + id
                            + "|" + names[i] + "|" + render(raw));
                    if (isValidNumeric(raw)) hit = true;
                } catch (Throwable t) {
                    System.out.println("ARRAY_GET_ERROR|" + label + "|" + name + "|" + id
                            + "|" + names[i] + "|" + message(t));
                }
            }
        }

        Method twoInt = findMethod(device.getClass(), "get", Integer.TYPE, Integer.TYPE);
        if (twoInt != null) {
            for (int parameter : new int[]{0, 1, 2, 1012, 1013, 1014}) {
                try {
                    twoInt.setAccessible(true);
                    Object raw = twoInt.invoke(device, id, parameter);
                    System.out.println("GET_2INT|" + label + "|" + name + "|" + id
                            + "|param=" + parameter + "|" + render(raw));
                    if (isValidNumeric(raw)) hit = true;
                } catch (Throwable t) {
                    System.out.println("GET_2INT_ERROR|" + label + "|" + name + "|" + id
                            + "|param=" + parameter + "|" + message(t));
                }
            }
        }

        for (String getter : new String[]{"getInt", "getLong", "getFloat", "getDouble", "getBuffer"}) {
            hit |= probeOneInt(label, name, id, device, getter);
        }

        if (!hit) {
            System.out.println("FEATURE_MISS|" + label + "|" + name + "|" + id);
        }
    }

    private static boolean probeOneInt(String label, String name, int id,
                                       Object device, String methodName) {
        Method method = findMethod(device.getClass(), methodName, Integer.TYPE, Integer.TYPE);
        int mode = 2;
        if (method == null) {
            method = findMethod(device.getClass(), methodName, Integer.TYPE);
            mode = 1;
        }
        if (method == null) return false;

        boolean hit = false;
        int[][] args = mode == 2
                ? new int[][]{{id, 0}, {id, 1}, {id, 1012}, {id, 1013}, {id, 1014}}
                : new int[][]{{id}};
        for (int[] a : args) {
            try {
                method.setAccessible(true);
                Object raw = mode == 2
                        ? method.invoke(device, a[0], a[1])
                        : method.invoke(device, a[0]);
                System.out.println("EXPLICIT_GET|" + label + "|" + name + "|" + methodName
                        + "|" + java.util.Arrays.toString(a) + "|" + render(raw));
                if (isValidNumeric(raw)) hit = true;
            } catch (Throwable t) {
                System.out.println("EXPLICIT_GET_ERROR|" + label + "|" + name + "|" + methodName
                        + "|" + java.util.Arrays.toString(a) + "|" + message(t));
            }
        }
        return hit;
    }

    private static void scanManager(Context context) {
        try {
            Class<?> managerClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager");
            Object manager = getInstance(managerClass, context);
            if (manager == null) {
                System.out.println("MANAGER|UNAVAILABLE");
                return;
            }
            System.out.println("MANAGER|" + manager.getClass().getName());
            Set<Integer> deviceTypes = new LinkedHashSet<>();
            for (Field field : allFields(managerClass)) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Integer.TYPE) continue;
                String n = field.getName().toUpperCase(Locale.US);
                if (!(n.contains("ENGINE") || n.contains("MOTOR") || n.contains("VEHICLE") || n.contains("SPEED"))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    deviceTypes.add(field.getInt(null));
                } catch (Throwable ignored) {
                }
            }
            if (deviceTypes.isEmpty()) {
                for (int v = 1000; v <= 1015; v++) deviceTypes.add(v);
            }
            System.out.println("MANAGER_DEVICE_TYPES|" + deviceTypes);
            for (int type : deviceTypes) {
                probeManager(manager, type, FRONT_MOTOR_SPEED, "FRONT_MOTOR_SPEED");
                probeManager(manager, type, REAR_MOTOR_SPEED, "REAR_MOTOR_SPEED");
                probeManager(manager, type, ENGINE_SPEED_ALT, "ENGINE_SPEED_ALT");
            }
        } catch (Throwable t) {
            System.out.println("MANAGER_ERROR|" + message(t));
        }
    }

    private static void probeManager(Object manager, int deviceType, int featureId, String name) {
        for (String methodName : new String[]{"getInt", "getLong", "getFloat", "getDouble", "getBuffer"}) {
            Method method = findMethod(manager.getClass(), methodName, Integer.TYPE, Integer.TYPE);
            if (method == null) continue;
            try {
                method.setAccessible(true);
                Object raw = method.invoke(manager, deviceType, featureId);
                System.out.println("MANAGER_GET|" + deviceType + "|" + name + "|"
                        + methodName + "|" + render(raw));
            } catch (Throwable t) {
                System.out.println("MANAGER_GET_ERROR|" + deviceType + "|" + name + "|"
                        + methodName + "|" + message(t));
            }
        }
    }

    private static boolean isValidNumeric(Object raw) {
        Double n = extractNumeric(raw, 0);
        return n != null && isFinite(n) && !isSentinel(n.doubleValue());
    }

    private static String render(Object raw) {
        if (raw == null) return "null";
        Double n = extractNumeric(raw, 0);
        if (n != null && isFinite(n) && !isSentinel(n.doubleValue())) {
            return String.format(Locale.US, "%.6f", n.doubleValue());
        }
        return describe(raw);
    }

    private static String describe(Object raw) {
        if (raw == null) return "null";
        Class<?> cls = raw.getClass();
        if (cls.isArray()) {
            int length = Array.getLength(raw);
            StringBuilder b = new StringBuilder(cls.getComponentType().getSimpleName())
                    .append("[").append(length).append("]");
            int limit = Math.min(length, 8);
            for (int i = 0; i < limit; i++) {
                b.append(i == 0 ? "{" : ",").append(String.valueOf(Array.get(raw, i)));
            }
            if (length > limit) b.append(",...");
            return b.append('}').toString();
        }
        return cls.getName();
    }

    private static boolean isFinite(Double value) {
        if (value == null) return false;
        double d = value.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static boolean isSentinel(double value) {
        return value == -10011.0 || value == -2147482645.0 || value == 8191.0
                || value == -8191.0 || value == 32767.0 || value == -32768.0
                || value == 65535.0 || value == -65535.0;
    }

    private static Double extractNumeric(Object value, int depth) {
        if (value == null || depth > 6) return null;
        if (value instanceof Number) {
            Double n = ((Number) value).doubleValue();
            return isFinite(n) ? n : null;
        }
        if (value instanceof String) {
            try {
                Double n = Double.valueOf((String) value);
                return isFinite(n) ? n : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            return length == 0 ? null : extractNumeric(Array.get(value, 0), depth + 1);
        }

        for (String name : new String[]{"getValue", "getIntValue", "getLongValue",
                "getFloatValue", "getDoubleValue", "getData"}) {
            Method method = findNoArg(value.getClass(), name);
            if (method == null) continue;
            try {
                method.setAccessible(true);
                Double n = extractNumeric(method.invoke(value), depth + 1);
                if (n != null) return n;
            } catch (Throwable ignored) {
            }
        }

        for (String name : new String[]{"value", "mValue", "intValue", "mIntValue", "data", "mData"}) {
            Field field = findField(value.getClass(), name);
            if (field == null) continue;
            try {
                field.setAccessible(true);
                Double n = extractNumeric(field.get(value), depth + 1);
                if (n != null) return n;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try {
            return clazz.getMethod(name, params);
        } catch (Throwable ignored) {
        }
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {
            }
        }
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name)
                    && java.util.Arrays.equals(method.getParameterTypes(), params)) {
                return method;
            }
        }
        return null;
    }

    private static Method findNoArg(Class<?> clazz, String name) {
        return findMethod(clazz, name);
    }

    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method[] allMethods(Class<?> clazz) {
        java.util.LinkedHashMap<String, Method> methods = new java.util.LinkedHashMap<>();
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                methods.put(method.toGenericString(), method);
            }
        }
        return methods.values().toArray(new Method[0]);
    }

    private static Field[] allFields(Class<?> clazz) {
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) fields.add(field);
        }
        return fields.toArray(new Field[0]);
    }

    private static Object getInstance(Class<?> clazz, Context context) {
        try {
            Method method = clazz.getMethod("getInstance", Context.class);
            method.setAccessible(true);
            return method.invoke(null, context);
        } catch (Throwable ignored) {
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (!(method.getName().equals("getInstance") || method.getName().equals("get")
                    || method.getName().equals("getDevice"))) continue;
            try {
                method.setAccessible(true);
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 0) return method.invoke(null);
                if (p.length == 1 && p[0].isAssignableFrom(context.getClass())) {
                    return method.invoke(null, context);
                }
                if (p.length == 1 && p[0] == Context.class) {
                    return method.invoke(null, context);
                }
            } catch (Throwable ignored) {
            }
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
            return (Context) method.invoke(system, packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, handle);
        } catch (Throwable ignored) {
            try {
                return system.createPackageContext(packageName,
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
