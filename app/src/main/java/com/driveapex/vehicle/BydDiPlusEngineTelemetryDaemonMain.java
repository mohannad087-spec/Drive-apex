package com.driveapex.vehicle;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.engine.AbsBYDAutoEngineListener;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DiPlus-compatible BYD engine telemetry daemon.
 *
 * The important difference from the old path is the registration form:
 * DiPlus registers an AbsBYDAutoEngineListener with registerListener(listener, featureIds)
 * after filtering the requested feature IDs through BYDAutoDeviceFeaturesMap.
 * This class reproduces that path instead of polling getMotorSpeed().
 *
 * A full decompile of com.van.diplus (docs/BYD_LIVE_INTEGRATION.md) confirms
 * the HAL calls the listener's onDataEventChanged(int type, BYDAutoEventValue
 * value) for a registered feature, not onEngineSpeedChanged(int) directly.
 * For BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED specifically, DiPlus's own
 * listener handles it entirely inside onDataEventChanged (storing
 * -value.intValue) and never calls onEngineSpeedChanged for it -- that method
 * fires only for a separate, unrelated feature ID. FrontMotorListener below
 * mirrors that: it keys off onDataEventChanged and the registered feature ID
 * only.
 */
public final class BydDiPlusEngineTelemetryDaemonMain {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18765;
    private static final int SCAN_PORT = 18766;
    private static final int DIPLUS_API_PORT = 8988;
    private static final String DIPLUS_API_PATH =
            "/api/getVal?name=%E5%89%8D%E7%94%B5%E6%9C%BA%E8%BD%AC%E9%80%9F&status=true";
    private static final Pattern DIPLUS_VALUE =
            Pattern.compile("\"val\"\\s*:\\s*\"?([-+]?\\d+(?:\\.\\d+)?)\"?");
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
        volatile long eventCount;
        volatile boolean diPlusApiOk;
        /** Why the DiPlus read is or is not working, in a form the app can log. */
        volatile String diPlusStatus = "STARTING";

        /**
         * Every distinct event type this listener has actually been handed, with its last
         * value. On this vehicle the front motor feature ID had never been observed
         * arriving at all, so recording what really does arrive is the only way to tell
         * a wrong feature ID apart from a subscription that is never delivered.
         */
        final java.util.Map<Integer, Integer> observed =
                java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, Integer>());

        void record(int type, int value) {
            eventCount++;
            if (observed.size() < 128 || observed.containsKey(type)) observed.put(type, value);
        }

        String observedSummary() {
            StringBuilder sb = new StringBuilder();
            synchronized (observed) {
                for (java.util.Map.Entry<Integer, Integer> e : observed.entrySet()) {
                    if (sb.length() > 0) sb.append(';');
                    sb.append(String.format("0x%08x=%d", e.getKey(), e.getValue()));
                }
            }
            return sb.length() == 0 ? "NONE" : sb.toString();
        }
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
                        + String.format(" feature=%d (0x%08x)", snapshot.featureId, snapshot.featureId));
                ready.countDown();
                handler.post(new Runnable() {
                    private long lastReport;
                    @Override public void run() {
                        sampleBase(speed, snapshot);
                        // Periodic summary of what the engine device is actually
                        // delivering, so a wrong feature ID and a dead subscription
                        // can be told apart straight from the daemon log.
                        long now = System.currentTimeMillis();
                        if (now - lastReport >= 5000L) {
                            lastReport = now;
                            System.out.println("DIPLUS BYD events=" + snapshot.eventCount
                                    + " rpm=" + snapshot.frontRpm
                                    + " types=" + snapshot.observedSummary());
                        }
                        handler.postDelayed(this, 500L);
                    }
                });
            });
            ready.await(3, TimeUnit.SECONDS);

            startDiPlusApiReader(snapshot);

            scanServer = listenOn(SCAN_PORT, 2);
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

            try (ServerSocket server = listenOn(PORT, 4)) {
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

    /**
     * Binds a listening socket with the address reusable.
     *
     * The app replaces this daemon whenever it ships a new build, so a bind here
     * follows a kill of the previous one by about a second. Without SO_REUSEADDR
     * the old socket lingering in TIME_WAIT is enough to make that bind fail, and
     * a daemon that cannot bind is a session with no vehicle data at all.
     */
    private static ServerSocket listenOn(int port, int backlog) throws Exception {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(HOST), port), backlog);
        return socket;
    }

    /**
     * Reads front motor speed from the DiPlus local API, from inside this
     * process.
     *
     * This daemon runs under the shell UID, and the shell UID is the identity
     * whose request to 127.0.0.1:8988 the service answers -- the app's own UID
     * gets a TCP reset for the identical request. So the read belongs here.
     *
     * It also removes the reason the app degraded over time. Reading the same
     * value from the app meant an ADB shell command per sample, which is a new
     * process on the head unit per sample; at twenty samples a second, with a
     * curl loop of its own alongside it, that is roughly forty process launches
     * every second for as long as the app runs. Here the value costs one socket
     * per sample inside a process that is already running, and reaches the app
     * over the single connection this daemon already serves. DiPlus stays smooth
     * with far more live sensors precisely because it does nothing like the
     * former arrangement.
     */
    private static void startDiPlusApiReader(Snapshot snapshot) {
        Thread reader = new Thread(() -> {
            int consecutiveFailures = 0;
            while (true) {
                // Once the helper is delivering, stand down. Left running, this
                // loop would keep failing, keep overwriting the status the helper
                // just set, and flap the app log between the two every 50ms.
                if (diPlusHelperOk) {
                    try { Thread.sleep(5000L); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                Integer rpm = readDiPlusFrontMotorRpm();
                if (rpm != null) {
                    consecutiveFailures = 0;
                    snapshot.frontRpm = rpm;
                    snapshot.timestamp = System.currentTimeMillis();
                    snapshot.valid = true;
                    snapshot.diPlusApiOk = true;
                    snapshot.diPlusStatus = "OK via " + diPlusHost;
                } else {
                    consecutiveFailures++;
                    snapshot.diPlusApiOk = false;
                    snapshot.diPlusStatus = "FAIL " + diPlusLastError;
                    if (consecutiveFailures == 1 || consecutiveFailures % 100 == 0) {
                        System.out.println("DIPLUS API read failed x" + consecutiveFailures
                                + ": " + diPlusLastError);
                    }
                    // A socket this process opens and a socket curl opens are the
                    // same UID but not necessarily the same SELinux domain, and a
                    // shell curl to this service is known to work on this unit. So
                    // if the direct read stays refused, fall back to one long-lived
                    // helper that polls and prints. One process for the life of the
                    // daemon -- not the one-per-sample that made the unit stutter.
                    if (consecutiveFailures == 20) startDiPlusHelper(snapshot);
                }
                try {
                    // Back off when the service is not answering rather than
                    // spinning on a socket that is refusing us.
                    Thread.sleep(consecutiveFailures > 5 ? 1000L : 50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "driveapex-diplus-api");
        reader.setDaemon(true);
        reader.start();
        System.out.println("DIPLUS API reader started (shell UID, in-process)");
    }

    private static volatile Process diPlusHelper;
    private static volatile boolean diPlusHelperOk;
    private static volatile int diPlusHelperStarts;

    /**
     * Starts the fallback reader: a single shell loop that fetches the value and
     * prints it, whose stdout this daemon consumes.
     *
     * Bounded to a few attempts. A helper that cannot run at all -- no curl on
     * the unit, for one -- must not be respawned forever.
     */
    private static synchronized void startDiPlusHelper(Snapshot snapshot) {
        Process existing = diPlusHelper;
        if (existing != null && existing.isAlive()) return;
        if (diPlusHelperStarts >= 3) return;
        diPlusHelperStarts++;
        try {
            String url = "http://127.0.0.1:" + DIPLUS_API_PORT + DIPLUS_API_PATH;
            // The loop must give up. An unconditional `while true; do curl` forks
            // a process every 50ms for as long as the daemon lives when curl is
            // missing or the service is refusing -- twenty a second, which is the
            // exact load this whole design exists to avoid. So: on a failure it
            // slows to one a second, and after fifteen it exits, which the pump
            // below sees as end of stream and reports.
            String script =
                    "f=0; while true; do "
                    + "v=$(curl -s --max-time 2 '" + url + "'); "
                    + "if [ -n \"$v\" ]; then printf '%s\\n' \"$v\"; f=0; sleep 0.05; "
                    + "else f=$((f+1)); if [ $f -ge 15 ]; then exit 1; fi; sleep 1; fi; "
                    + "done";
            Process process = new ProcessBuilder("sh", "-c", script)
                    .redirectErrorStream(true)
                    .start();
            diPlusHelper = process;
            snapshot.diPlusStatus = "HELPER started (attempt " + diPlusHelperStarts + ")";
            System.out.println("DIPLUS helper started, attempt " + diPlusHelperStarts);

            Thread pump = new Thread(() -> {
                boolean produced = false;
                try (BufferedReader out = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        Matcher match = DIPLUS_VALUE.matcher(line);
                        if (!match.find()) continue;
                        Integer rpm = parseRpm(match.group(1));
                        if (rpm == null) continue;
                        produced = true;
                        diPlusHelperOk = true;
                        snapshot.frontRpm = rpm;
                        snapshot.timestamp = System.currentTimeMillis();
                        snapshot.valid = true;
                        snapshot.diPlusApiOk = true;
                        snapshot.diPlusStatus = "OK via helper";
                    }
                } catch (Throwable t) {
                    System.err.println("DIPLUS helper pump: " + message(t));
                }
                diPlusHelperOk = false;
                if (!produced) snapshot.diPlusStatus = "HELPER produced nothing";
                System.out.println("DIPLUS helper ended, produced=" + produced);
            }, "driveapex-diplus-helper");
            pump.setDaemon(true);
            pump.start();
        } catch (Throwable t) {
            snapshot.diPlusStatus = "HELPER failed " + message(t);
            System.out.println("DIPLUS helper failed: " + message(t));
        }
    }

    /**
     * The host that last answered, tried first from then on.
     *
     * The service listens on tcp6 :::8988. On a kernel with bindv6only set, an
     * IPv4 connect to that never lands, so a single hardcoded 127.0.0.1 can fail
     * on a unit where the service is running perfectly. Both families are tried.
     */
    private static final String[] DIPLUS_HOSTS = { "127.0.0.1", "::1" };
    private static volatile String diPlusHost = null;
    private static volatile String diPlusLastError = "none";

    /** Returns null on any failure, recording why in diPlusLastError. */
    private static Integer readDiPlusFrontMotorRpm() {
        String preferred = diPlusHost;
        if (preferred != null) {
            Integer value = readDiPlusFrom(preferred);
            if (value != null) return value;
            diPlusHost = null;
        }
        for (String host : DIPLUS_HOSTS) {
            if (host.equals(preferred)) continue;
            Integer value = readDiPlusFrom(host);
            if (value != null) {
                diPlusHost = host;
                return value;
            }
        }
        return null;
    }

    private static Integer readDiPlusFrom(String host) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, DIPLUS_API_PORT), 500);
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            String request = "GET " + DIPLUS_API_PATH + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + DIPLUS_API_PORT + "\r\n"
                    + "User-Agent: DriveApex\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes("US-ASCII"));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            StringBuilder received = new StringBuilder();
            String line;
            // Match as it arrives: a server that answers and then resets instead
            // of closing cleanly would otherwise have its response discarded.
            while (received.length() < 8192 && (line = reader.readLine()) != null) {
                received.append(line).append('\n');
                Matcher match = DIPLUS_VALUE.matcher(received);
                if (match.find()) return parseRpm(match.group(1));
            }
            Matcher match = DIPLUS_VALUE.matcher(received);
            if (match.find()) return parseRpm(match.group(1));
            // An answer with no value in it is a different fault from no answer,
            // and saying which is the whole point of keeping this text.
            diPlusLastError = received.length() == 0
                    ? host + " EMPTY_RESPONSE"
                    : host + " NO_VALUE_IN_RESPONSE";
            return null;
        } catch (Throwable t) {
            diPlusLastError = host + " " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
            return null;
        } finally {
            if (socket != null) try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static Integer parseRpm(String text) {
        try {
            double raw = Math.abs(Double.parseDouble(text));
            if (!(raw >= 0) || raw > 25000) return null;
            return (int) raw;
        } catch (Throwable t) {
            return null;
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
                writer.write(Double.toString(snapshot.brake)); writer.write(",BYD_DIPLUS_ENGINE_FEATURE_LISTENER,");
                // Seventh field. Older readers stop at six, so this is additive.
                writer.write(snapshot.diPlusStatus.replace(',', ' ').replace('\n', ' '));
                writer.write(',');
                // Eighth field: how the HAL listener registered, and whether it has
                // ever been called. "events" is deliberately a yes/no rather than a
                // count -- the app logs this line when it changes, and a counter
                // would change twenty times a second.
                writer.write((snapshot.registration + ":events=" + (snapshot.eventCount > 0 ? "YES" : "NO"))
                        .replace(',', ' ').replace('\n', ' '));
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
                    + String.format(" (0x%08x)", snapshot.featureId)
                    + ",listener=" + snapshot.listenerRegistered + "\n");
            writer.write("HIT,ENGINE,REGISTRATION," + snapshot.registration + ",rpm=" + snapshot.frontRpm + "\n");
            writer.write("HIT,ENGINE,EVENTS,count=" + snapshot.eventCount
                    + ",types=" + snapshot.observedSummary() + "\n");
            writer.write("SCAN_DONE,3\n");
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

    /**
     * Every feature ID this device declares, read the way DiPlus reads it.
     *
     * DiPlus calls getFeatureIdsFromDevice(deviceType) -- one argument, a Set
     * back -- and intersects it with the IDs it wants. This looked for a
     * two-argument (Set, int) overload instead, found none, and silently
     * returned the raw requested ID as though it had filtered it.
     *
     * The whole declared set is registered rather than just the front motor ID.
     * It is a superset of what DiPlus subscribes to, so nothing is lost, and it
     * settles by observation whether the front motor ID is even among the IDs
     * this device delivers -- which no amount of registering only that one ID
     * could ever tell us apart from a subscription that simply never fires.
     */
    private static int[] deviceFeatureIds(int deviceType, int requestedId) {
        try {
            Class<?> map = Class.forName(FEATURE_MAP);
            for (Method m : map.getDeclaredMethods()) {
                if (!"getFeatureIdsFromDevice".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != int.class) continue;
                m.setAccessible(true);
                Object result = m.invoke(null, deviceType);
                int[] ids = toIntArray(result);
                if (ids.length > 0) return ids;
            }
            System.err.println("feature map: no getFeatureIdsFromDevice(int)");
        } catch (Throwable t) {
            System.err.println("feature map failed: " + message(t));
        }
        return new int[]{requestedId};
    }

    /** getFeatureIdsFromDevice returns a Set on this HAL; accept an int[] too. */
    private static int[] toIntArray(Object result) {
        if (result instanceof int[]) return (int[]) result;
        if (result instanceof java.util.Collection) {
            java.util.Collection<?> values = (java.util.Collection<?>) result;
            int[] ids = new int[values.size()];
            int n = 0;
            for (Object value : values) {
                if (value instanceof Number) ids[n++] = ((Number) value).intValue();
            }
            if (n == ids.length) return ids;
            int[] trimmed = new int[n];
            System.arraycopy(ids, 0, trimmed, 0, n);
            return trimmed;
        }
        return new int[0];
    }

    private static boolean contains(int[] ids, int value) {
        for (int id : ids) if (id == value) return true;
        return false;
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
                try {
                    Method typeMethod = d.getClass().getMethod("getDevicetype");
                    snapshot.deviceType = ((Number) typeMethod.invoke(d)).intValue();
                } catch (Throwable ignored) {}
                RpmSink sink = value -> {
                    if (value < 0) value = Math.abs(value);
                    if (value <= MAX_RPM && value != 8191 && value != 16383 && value != 32767 && value != 65535) {
                        snapshot.frontRpm = value;
                        snapshot.timestamp = System.currentTimeMillis();
                        snapshot.valid = true;
                    }
                };
                FrontMotorListener listener = new FrontMotorListener(snapshot.featureId, sink, snapshot);

                // DiPlus's generic registration helper can do either form, but its
                // ENGINE call site passes a null feature array:
                //
                //   005e: const/4 v8, 0
                //   0148: invoke-static {v3, v8, v6, v7}, C   // v8 -> C's array arg
                //   0018: if-eqz v4, 002d  -> registerListener(listener)
                //
                // so the engine listener is registered with the ONE-argument form.
                // That is what this uses. The two-argument form stays as a fallback,
                // and both have now been measured on the vehicle: one-argument and
                // two-argument with all 30 declared IDs, front motor among them,
                // each deliver zero events. The registration form is not the
                // blocker -- the permissions are.
                Method oneArg = findRegisterListener(d.getClass(), listener.getClass(), false);
                if (oneArg != null) {
                    try {
                        oneArg.setAccessible(true);
                        oneArg.invoke(d, listener);
                        snapshot.registration = "registerListener(listener):unfiltered";
                        return true;
                    } catch (Throwable t) {
                        System.err.println("one-arg registerListener failed: " + message(t));
                    }
                }

                Method twoArg = findRegisterListener(d.getClass(), listener.getClass(), true);
                if (twoArg != null && android.os.Build.VERSION.SDK_INT >= 29) {
                    int[] featureIds = deviceFeatureIds(snapshot.deviceType, snapshot.featureId);
                    try {
                        twoArg.setAccessible(true);
                        twoArg.invoke(d, listener, featureIds);
                        snapshot.registration = "registerListener(listener,ids):count=" + featureIds.length
                                + ":hasFrontMotor=" + contains(featureIds, snapshot.featureId);
                        return true;
                    } catch (Throwable t) {
                        System.err.println("two-arg registerListener failed: " + message(t));
                    }
                }

                snapshot.registration = "NO_REGISTER_LISTENER_OVERLOAD";
                return false;
            } catch (Throwable t) {
                snapshot.registration = "ERROR:" + message(t);
                return false;
            }
        }
    }

    /** Finds registerListener(listener) or registerListener(listener, int[]) on the device or a superclass. */
    private static Method findRegisterListener(Class<?> deviceClass, Class<?> listenerClass, boolean withFeatureIds) {
        int arity = withFeatureIds ? 2 : 1;
        for (Method m : deviceClass.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!"registerListener".equals(m.getName()) || p.length != arity) continue;
            if (withFeatureIds && p[1] != int[].class) continue;
            if (p[0].isAssignableFrom(listenerClass)) return m;
        }
        for (Class<?> c = deviceClass; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!"registerListener".equals(m.getName()) || p.length != arity) continue;
                if (withFeatureIds && p[1] != int[].class) continue;
                if (p[0].isAssignableFrom(listenerClass)) return m;
            }
        }
        return null;
    }

    private interface RpmSink { void accept(int rpm); }

    private static final class FrontMotorListener extends AbsBYDAutoEngineListener {
        private final int expectedFeatureId;
        private final RpmSink sink;
        private final Snapshot snapshot;
        FrontMotorListener(int expectedFeatureId, RpmSink sink, Snapshot snapshot) {
            this.expectedFeatureId = expectedFeatureId;
            this.sink = sink;
            this.snapshot = snapshot;
        }
        // DiPlus's own onDataEventChanged for ENGINE_FRONT_MOTOR_SPEED stores
        // -value.intValue directly and never calls onEngineSpeedChanged; that
        // method fires for a distinct, unrelated feature ID (generic "engine
        // speed"), so it must NOT be treated as a front-motor-speed fallback
        // here -- doing so would risk mixing in an unrelated signal. Math.abs
        // in the sink already makes DiPlus's sign convention irrelevant.
        //
        // Registration is unfiltered (see registerDiPlusFrontMotorListener), so
        // every engine-device event lands here. Record them all: a vehicle log
        // capture showed the front motor feature ID never being delivered at
        // all, and only the list of IDs that really do arrive can tell a wrong
        // ID apart from a subscription that was silently filtered to nothing.
        @Override public void onDataEventChanged(int type, BYDAutoEventValue value) {
            if (value == null) return;
            snapshot.record(type, value.intValue);
            System.out.println(String.format("EVENT type=0x%08x value=%d", type, value.intValue));
            if (type == expectedFeatureId) sink.accept(value.intValue);
        }
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
