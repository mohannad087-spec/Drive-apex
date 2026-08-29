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

/**
 * Read-only BYD HAL forensic scanner.
 *
 * It intentionally does not use the narrow 4080..4120 range.  It probes the exact
 * motor-speed feature through every read signature exposed by the connected SDK and
 * also inspects BYDAutoDeviceManager device-type constants without issuing writes.
 */
public final class BydFeatureScannerMain {
    private static final String DEFAULT_PACKAGE = "com.driveapex";
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
                    if (arg != null && arg.startsWith("--package=")) packageName = arg.substring(10).trim();
                    else if (arg != null && arg.startsWith("--requested-user-id=")) {
                        try { userId = Integer.parseInt(arg.substring(21).trim()); } catch (Throwable ignored) {}
                    }
                }
            }
            Context system = createSystemContext();
            Context requested = tryPackageContext(system, packageName, userId);
            Context context = new BydPermissionContext(requested == null ? system : requested, packageName);

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
            System.out.println("TARGET|FRONT_MOTOR_SPEED|" + FRONT_MOTOR_SPEED);
            probeFeature(label, "FRONT_MOTOR_SPEED", FRONT_MOTOR_SPEED, device);
            probeFeature(label, "REAR_MOTOR_SPEED", REAR_MOTOR_SPEED, device);
            probeFeature(label, "FRONT_MOTOR_TORQUE", FRONT_MOTOR_TORQUE, device);
            if (label.equals("ENGINE")) probeFeature(label, "ENGINE_SPEED_ALT", ENGINE_SPEED_ALT, device);

            for (Method method : allMethods(clazz)) {
                if (method.getParameterTypes().length != 0) continue;
                if (!method.getName().matches("(?i).*(motor|speed|rpm|engine).*")) continue;
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(device);
                    Double n = extractNumeric(value, 0);
                    if (n != null && n.isFinite()) {
                        System.out.println(String.format(Locale.US, "GETTER|%s|%s|%.6f", label, method.getName(), n));
                    } else {
                        System.out.println("GETTER_RESULT|" + label + "|" + method.getName() + "|" + describe(value));
                    }
                } catch (Throwable t) {
                    System.out.println("GETTER_ERROR|" + label + "|" + method.getName() + "|" + message(t));
                }
            }
        } catch (Throwable t) {
            System.out.println("DEVICE_ERROR|" + label + "|" + message(t));
        }
    }

    private static void probeFeature(String label, String name, int id, Object device) {
        boolean hit = false;

        // OEM/reference signature: get(int[], Class) using primitive classes.
        Method arrayGet = findMethod(device.getClass(), "get", int[].class, Class.class);
        if (arrayGet != null) {
            Class<?>[] types = {Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE, Short.TYPE, Byte.TYPE};
            String[] names = {"int", "long", "float", "double", "short", "byte"};
            for (int i = 0; i < types.length; i++) {
                try {
                    arrayGet.setAccessible(true);
                    Object raw = arrayGet.invoke(device, new int[]{id}, types[i]);
                    String rendered = renderNumeric(raw);
                    System.out.println("ARRAY_GET|" + label + "|" + name + "|" + id + "|" + names[i] + "|" + rendered);
                    if (isValidNumeric(raw)) hit = true;
                } catch (Throwable t) {
                    System.out.println("ARRAY_GET_ERROR|" + label + "|" + name + "|" + id + "|" + names[i] + "|" + message(t));
                }
            }
        } else {
            System.out.println("ARRAY_GET_UNAVAILABLE|" + label + "|" + name + "|" + id);
        }

        // Connected SDK fallback: get(int featureId, int parameter).
        Method twoInt = findMethod(device.getClass(), "get", Integer.TYPE, Integer.TYPE);
        if (twoInt != null) {
            for (int parameter : new int[]{0, 1, 2, 1012, 1013, 1014}) {
                try {
                    twoInt.setAccessible(true);
                    Object raw = twoInt.invoke(device, id, parameter);
                    String rendered = renderNumeric(raw);
                    System.out.println("GET_2INT|" + label + "|" + name + "|" + id + "|param=" + parameter + "|" + rendered);
                    if (isValidNumeric(raw)) hit = true;
                } catch (Throwable t) {
                    System.out.println("GET_2INT_ERROR|" + label + "|" + name + "|" + id + "|param=" + parameter + "|" + message(t));
                }
            }
        } else {
            System.out.println("GET_2INT_UNAVAILABLE|" + label + "|" + name + "|" + id);
        }

        // Explicit primitive getters. These are used by the BYD framework on some builds.
        probeOneInt(label, name, id, device, "getInt");
        probeOneInt(label, name, id, device, "getLong");
        probeOneInt(label, name, id, device, "getFloat");
        probeOneInt(label, name, id, device, "getDouble");
        probeOneInt(label, name, id, device, "getBuffer");
        if (!hit) System.out.println("FEATURE_MISS|" + label + "|" + name + "|" + id);
    }

    private static void probeOneInt(String label, String name, int id, Object device, String methodName) {
        Method method = findMethod(device.getClass(), methodName, Integer.TYPE, Integer.TYPE);
        if (method == null) method = findMethod(device.getClass(), methodName, Integer.TYPE);
        if (method == null) {
            System.out.println("METHOD_UNAVAILABLE|" + label + "|" + methodName);
            return;
        }
        int[][] args = method.getParameterTypes().length == 2
                ? new int[][]{{id, 0}, {id, 1}, {id, 1012}, {id, 1013}, {id, 1014}}
                : new int[][]{{id}};
        for (int[] a : args) {
            try {
                method.setAccessible(true);
                Object raw = a.length == 2 ? method.invoke(device, a[0], a[1]) : method.invoke(device, a[0]);
                System.out.println("EXPLICIT_GET|" + label + "|" + name + "|" + methodName + "|" + java.util.Arrays.toString(a) + "|" + renderNumeric(raw));
            } catch (Throwable t) {
                System.out.println("EXPLICIT_GET_ERROR|" + label + "|" + name + "|" + methodName + "|" + java.util.Arrays.toString(a) + "|" + message(t));
            }
        }
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
                if (!(n.contains("ENGINE") || n.contains("MOTOR") || n.contains("VEHICLE") || n.contains("SPEED"))) continue;
                try { field.setAccessible(true); deviceTypes.add(field.getInt(null)); } catch (Throwable ignored) {}
            }
            if (deviceTypes.isEmpty()) {
                // Introspection fallback: only common BYD device-family constants, never a write.
                for (int v : new int[]{1000,1001,1002,1003,1004,1005,1006,1007,1008,1009,1010,1011,1012,1013,1014,1015}) deviceTypes.add(v);
            }
            System.out.println("MANAGER_DEVICE_TYPES|" + deviceTypes);
            for (int type : deviceTypes) {
                probeManagerRead(manager, type, FRONT_MOTOR_SPEED, "FRONT_MOTOR_SPEED");
                probeManagerRead(manager, type, REAR_MOTOR_SPEED, "REAR_MOTOR_SPEED");
                probeManagerRead(manager, type, ENGINE_SPEED_ALT, "ENGINE_SPEED_ALT");
            }
        } catch (Throwable t) {
            System.out.println("MANAGER_ERROR|" + message(t));
        }
    }

    private static void probeManagerRead(Object manager, int deviceType, int featureId, String name) {
        for (String methodName : new String[]{"getInt", "getLong", "getFloat", "getDouble", "getBuffer"}) {
            Method method = findMethod(manager.getClass(), methodName, Integer.TYPE, Integer.TYPE);
            if (method == null) continue;
            try {
                method.setAccessible(true);
                Object raw = method.invoke(manager, deviceType, featureId);
                System.out.println("MANAGER_GET|" + deviceType + "|" + name + "|" + methodName + "|" + renderNumeric(raw));
            } catch (Throwable t) {
                System.out.println("MANAGER_GET_ERROR|" + deviceType + "|" + name + "|" + methodName + "|" + message(t));
            }
        }
    }

    private static boolean isValidNumeric(Object raw) {
        Double n = extractNumeric(raw, 0);
        if (n == null || !n.isFinite()) return false;
        return !isSentinel(n);
    }

    private static String renderNumeric(Object raw) {
        if (raw == null) return "null";
        Double n = extractNumeric(raw, 0);
        if (n != null && n.isFinite() && !isSentinel(n)) return String.format(Locale.US, "%.6f", n);
        return describe(raw);
    }

    private static String describe(Object raw) {
        if (raw == null) return "null";
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            StringBuilder b = new StringBuilder(raw.getClass().getComponentType().getSimpleName()).append("[").append(length).append("]");
            int limit = Math.min(length, 8);
            for (int i = 0; i < limit; i++) b.append(i == 0 ? "{" : ",").append(String.valueOf(Array.get(raw, i)));
            return b.append(length > limit ? ",...}" : "}").toString();
        }
        return raw.getClass().getName();
    }

    private static boolean isSentinel(double value) {
        return value == -10011.0 || value == -2147482645.0 || value == 8191.0 || value == -8191.0
                || value == 32767.0 || value == -32768.0 || value == 65535.0 || value == -65535.0;
    }

    private static Double extractNumeric(Object value, int depth) {
        if (value == null || depth > 6) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try { return Double.parseDouble((String) value); } catch (Throwable ignored) { return null; }
        }
        Class<?> clazz = value.getClass();
        if (clazz.isArray()) {
            int length = Array.getLength(value);
            return length == 0 ? null : extractNumeric(Array.get(value, 0), depth + 1);
        }
        for (String name : new String[]{"getValue","getIntValue","getLongValue","getFloatValue","getDoubleValue","getData"}) {
            Method m = findNoArg(clazz, name);
            if (m == null) continue;
            try {
                m.setAccessible(true);
                Double n = extractNumeric(m.invoke(value), depth + 1);
                if (n != null) return n;
            } catch (Throwable ignored) {}
        }
        for (String name : new String[]{"value","mValue","intValue","mIntValue","data","mData"}) {
            Field f = findField(clazz, name);
            if (f == null) continue;
            try {
                f.setAccessible(true);
                Double n = extractNumeric(f.get(value), depth + 1);
                if (n != null) return n;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        try { return clazz.getMethod(name, params); } catch (Throwable ignored) {}
        Class<?> current = clazz;
        while (current != null) {
            try { return current.getDeclaredMethod(name, params); } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        for (Method method : clazz.getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (java.util.Arrays.equals(method.getParameterTypes(), params)) return method;
        }
        return null;
    }

    private static Method findNoArg(Class<?> clazz, String name) {
        return findMethod(clazz, name);
    }

    private static Method[] allMethods(Class<?> clazz) {
        java.util.LinkedHashMap<String, Method> methods = new java.util.LinkedHashMap<>();
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) methods.put(method.toGenericString(), method);
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
            Method m = clazz.getMethod("getInstance", Context.class);
            m.setAccessible(true);
            return m.invoke(null, context);
        } catch (Throwable ignored) {}
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!(m.getName().equals("getInstance") || m.getName().equals("get") || m.getName().equals("getDevice"))) continue;
            try {
                m.setAccessible(true);
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0) return m.invoke(null);
                if (p.length == 1 && p[0].isAssignableFrom(context.getClass())) return m.invoke(null, context);
                if (p.length == 1 && p[0] == Context.class) return m.invoke(null, context);
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
            Method method = Context.class.getMethod("createPackageContextAsUser", String.class, int.class, userHandle);
            return (Context) method.invoke(system, packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, handle);
        } catch (Throwable ignored) {
            try { return system.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY); }
            catch (Throwable ignoredAgain) { return null; }
        }
    }

    private static final class BydPermissionContext extends ContextWrapper {
        private final String opPackage;
        BydPermissionContext(Context base, String opPackage) { super(base); this.opPackage = opPackage; }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkCallingOrSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkPermission(String permission, int pid, int uid) { return PackageManager.PERMISSION_GRANTED; }
        @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public String getOpPackageName() { return opPackage; }
    }

    private static String message(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName() + ":" + String.valueOf(current.getMessage());
    }
}
