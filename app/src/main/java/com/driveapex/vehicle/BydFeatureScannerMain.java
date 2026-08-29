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
    private static final int FRONT_MOTOR_SPEED = 1141899272; // Overdrive/DiPlus verified
    private static final int REAR_MOTOR_SPEED = 621805576;
    private static final int FRONT_MOTOR_TORQUE = 1141899288;

    private BydFeatureScannerMain() {}

    public static void main(String[] args) {
        try {
            String packageName = DEFAULT_PACKAGE;
            int userId = 0;
            if (args != null) for (String arg : args) {
                if (arg != null && arg.startsWith("--package=")) packageName = arg.substring("--package=".length()).trim();
                if (arg != null && arg.startsWith("--requested-user-id=")) {
                    try { userId = Integer.parseInt(arg.substring("--requested-user-id=".length()).trim()); } catch (Throwable ignored) {}
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
            if (device == null) { System.out.println("DEVICE|" + label + "|UNAVAILABLE"); return; }
            System.out.println("DEVICE|" + label + "|" + device.getClass().getName());

            // First: the exact IDs proven by the reference Overdrive/DiPlus collector.
            Map<String,Integer> exact = new LinkedHashMap<>();
            exact.put("ENGINE_FRONT_MOTOR_SPEED", FRONT_MOTOR_SPEED);
            exact.put("ENGINE_REAR_MOTOR_SPEED", REAR_MOTOR_SPEED);
            exact.put("ENGINE_FRONT_MOTOR_TORQUE", FRONT_MOTOR_TORQUE);
            for (Map.Entry<String,Integer> e : exact.entrySet()) reportFeature(label, e.getKey(), e.getValue(), device);

            // Second: every motor/engine/speed constant actually compiled into DriveApex.
            try {
                for (Field f : BydFeatureIds.class.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
                    String name = f.getName().toUpperCase(Locale.US);
                    if (!(name.contains("MOTOR") || name.contains("ENGINE_SPEED") || name.contains("SPEED"))) continue;
                    f.setAccessible(true);
                    int id = f.getInt(null);
                    if (id == Integer.MIN_VALUE) continue;
                    if (id == FRONT_MOTOR_SPEED || id == REAR_MOTOR_SPEED || id == FRONT_MOTOR_TORQUE) continue;
                    reportFeature(label, f.getName(), id, device);
                }
            } catch (Throwable t) {
                System.out.println("CONSTANT_SCAN_ERROR|" + label + "|" + message(t));
            }

            // Third: numeric no-arg getters containing motor/speed/rpm in their name.
            for (Method m : allMethods(clazz)) {
                if (!m.getName().matches("(?i).*(motor|speed|rpm).*")) continue;
                if (m.getParameterTypes().length != 0) continue;
                try {
                    m.setAccessible(true);
                    Object value = m.invoke(device);
                    Double n = extractNumeric(value, 0);
                    if (n != null && Double.isFinite(n)) {
                        System.out.println(String.format(Locale.US, "GETTER|%s|%s|%.6f", label, m.getName(), n));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            System.out.println("DEVICE_ERROR|" + label + "|" + message(t));
        }
    }

    private static void reportFeature(String label, String name, int id, Object device) {
        Class<?>[] types = {Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE, Short.TYPE};
        String[] names = {"int", "long", "float", "double", "short"};
        for (int i = 0; i < types.length; i++) {
            try {
                Object raw = readFeature(device, id, types[i]);
                Double n = extractNumeric(raw, 0);
                if (n != null && Double.isFinite(n) && !isSentinel(n)) {
                    System.out.println(String.format(Locale.US, "FEATURE|%s|%s|%d|%s|%.6f|%s", label, name, id, names[i], n, raw == null ? "null" : raw.getClass().getName()));
                    return;
                }
            } catch (Throwable ignored) {}
        }
        System.out.println(String.format(Locale.US, "FEATURE_MISS|%s|%s|%d", label, name, id));
    }

    private static boolean isSentinel(double n) {
        return n == -10011.0 || n == -2147482645.0 || n == 8191.0 || n == -8191.0 ||
                n == 32767.0 || n == -32768.0 || n == 65535.0;
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
                if (m.getName().equals("get") && p.length == 2 && p[0] == int[].class && p[1] == Class.class) return m;
            }
            current = current.getSuperclass();
        }
        for (Method m : clazz.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("get") && p.length == 2 && p[0] == int[].class && p[1] == Class.class) return m;
        }
        return null;
    }

    /** Handles primitive/boxed arrays and common BYD event-value wrappers. */
    private static Double extractNumeric(Object value, int depth) {
        if (value == null || depth > 5) return null;
        if (value instanceof Number) return ((Number)value).doubleValue();
        Class<?> c = value.getClass();
        if (c.isArray()) {
            int n = java.lang.reflect.Array.getLength(value);
            return n == 0 ? null : extractNumeric(java.lang.reflect.Array.get(value, 0), depth + 1);
        }
        String[] methods = {"getValue", "getIntValue", "getLongValue", "getFloatValue", "getDoubleValue", "getData"};
        for (String name : methods) {
            Method m = findNoArg(c, name);
            if (m == null) continue;
            try { m.setAccessible(true); Double n = extractNumeric(m.invoke(value), depth + 1); if (n != null) return n; } catch (Throwable ignored) {}
        }
        String[] fields = {"value", "mValue", "intValue", "mIntValue", "data", "mData"};
        for (String name : fields) {
            Field f = findField(c, name);
            if (f == null) continue;
            try { f.setAccessible(true); Double n = extractNumeric(f.get(value), depth + 1); if (n != null) return n; } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Method findNoArg(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == 0) return m;
            c = c.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method[] allMethods(Class<?> clazz) {
        Map<String,Method> map = new LinkedHashMap<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods()) map.put(m.toGenericString(), m);
        return map.values().toArray(new Method[0]);
    }

    private static Object getInstance(Class<?> clazz, Context context) {
        try { Method m = clazz.getDeclaredMethod("getInstance", Context.class); m.setAccessible(true); return m.invoke(null, context); }
        catch (Throwable ignored) {}
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!(m.getName().equals("getInstance") || m.getName().equals("get") || m.getName().equals("getDevice"))) continue;
            try {
                m.setAccessible(true); Class<?>[] p = m.getParameterTypes();
                if (p.length == 0) return m.invoke(null);
                if (p.length == 1 && (p[0] == Context.class || p[0].isAssignableFrom(context.getClass()))) return m.invoke(null, context);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Context createSystemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain"); systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext"); getSystemContext.setAccessible(true);
        return (Context)getSystemContext.invoke(thread);
    }

    private static Context tryPackageContext(Context system, String packageName, int userId) {
        try {
            Class<?> uh = Class.forName("android.os.UserHandle");
            Object handle = uh.getMethod("of", int.class).invoke(null, userId);
            Method m = Context.class.getMethod("createPackageContextAsUser", String.class, int.class, uh);
            return (Context)m.invoke(system, packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, handle);
        } catch (Throwable ignored) {
            try { return system.createPackageContext(packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY); }
            catch (Throwable ignored2) { return null; }
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
        Throwable current = t; while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName() + ":" + String.valueOf(current.getMessage());
    }
}
