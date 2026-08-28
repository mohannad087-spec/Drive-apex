package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/** Read-only BYD feature scanner. Intended to run under shell UID via app_process. */
public final class BydFeatureScannerMain {
    private static final String DEFAULT_PACKAGE = "com.driveapex";
    private static final String HOST = "127.0.0.1";
    private static final int START_ID = 4088;
    private static final int END_ID = 4120;

    private BydFeatureScannerMain() {}

    public static void main(String[] args) {
        try {
            String packageName = DEFAULT_PACKAGE;
            int userId = 0;
            if (args != null) for (String arg : args) {
                if (arg != null && arg.startsWith("--package=")) packageName = arg.substring(10).trim();
                if (arg != null && arg.startsWith("--requested-user-id=")) {
                    try { userId = Integer.parseInt(arg.substring(21).trim()); } catch (Throwable ignored) {}
                }
            }
            Context system = createSystemContext();
            Context pkg = tryPackageContext(system, packageName, userId);
            Context context = new BydPermissionContext(pkg == null ? system : pkg, packageName);
            scan("SPEED", "android.hardware.bydauto.speed.BYDAutoSpeedDevice", context);
            scan("MOTOR", "android.hardware.bydauto.motor.BYDAutoMotorDevice", context);
            scan("ENGINE", "android.hardware.bydauto.engine.BYDAutoEngineDevice", context);
        } catch (Throwable t) {
            System.out.println("ERROR|" + message(t));
        }
    }

    private static void scan(String label, String className, Context context) {
        try {
            Class<?> clazz = Class.forName(className);
            Object device = getInstance(clazz, context);
            if (device == null) { System.out.println("DEVICE|" + label + "|UNAVAILABLE"); return; }
            System.out.println("DEVICE|" + label + "|" + device.getClass().getName());
            for (int id = START_ID; id <= END_ID; id++) {
                for (Class<?> type : new Class<?>[]{Integer.TYPE, Float.TYPE, Double.TYPE, Long.TYPE}) {
                    Object value = readFeature(device, id, type);
                    if (value instanceof Number) {
                        double number = ((Number) value).doubleValue();
                        if (Double.isFinite(number)) {
                            System.out.println(String.format(Locale.US, "FEATURE|%s|%d|%s|%.6f", label, id, type.getSimpleName(), number));
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("DEVICE_ERROR|" + label + "|" + message(t));
        }
    }

    private static Object getInstance(Class<?> clazz, Context context) {
        try {
            Method m = clazz.getDeclaredMethod("getInstance", Context.class);
            m.setAccessible(true);
            return m.invoke(null, context);
        } catch (Throwable ignored) {}
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            String n = m.getName();
            if (!(n.equals("getInstance") || n.equals("get") || n.equals("getDevice"))) continue;
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

    private static Object readFeature(Object device, int id, Class<?> type) {
        try {
            Class<?> c = device.getClass();
            for (Method m : c.getMethods()) {
                if (!m.getName().equals("get")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == int[].class && p[1] == Class.class) {
                    return unwrap(m.invoke(device, new int[]{id}, type));
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("get")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == int[].class && p[1] == Class.class) {
                    m.setAccessible(true);
                    return unwrap(m.invoke(device, new int[]{id}, type));
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object unwrap(Object value) {
        if (value == null) return null;
        if (value.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(value);
            return n == 0 ? null : java.lang.reflect.Array.get(value, 0);
        }
        return value;
    }

    private static Context createSystemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain"); systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext"); getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static Context tryPackageContext(Context system, String packageName, int userId) {
        try {
            Class<?> uh = Class.forName("android.os.UserHandle");
            Object handle = uh.getMethod("of", int.class).invoke(null, userId);
            Method m = Context.class.getMethod("createPackageContextAsUser", String.class, int.class, uh);
            return (Context) m.invoke(system, packageName, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY, handle);
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
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName() + ":" + String.valueOf(current.getMessage());
    }
}
