package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock
import com.driveapex.update.VehicleAdbConnection
import dadb.Dadb

/**
 * Front motor RPM from the DiPlus local API, fetched by running curl through the
 * ADB connection instead of opening a socket from this process.
 *
 * This exists because of a measured asymmetry on the vehicle. The same GET to
 * 127.0.0.1:8988 returns the value when it comes from the shell UID:
 *
 *   {"success":true,"val":"-2538"}
 *
 * and is met with a TCP reset when it comes from this app -- on IPv4 loopback,
 * IPv6 loopback and the LAN address alike, with two different request shapes.
 * The service is therefore up and locally reachable, and it is this app's
 * process that something is refusing. Until that is fixed at the source, going
 * through the shell UID is the path already proven to work on this hardware.
 *
 * This is the primary read path on this vehicle, not a fallback, so it is built
 * to keep up: a loop started once on the vehicle keeps a file topped up with the
 * latest reading and each poll is a cheap `cat`, rather than launching curl over
 * ADB every cycle. Refreshing happens on a background thread and callers only
 * ever read a cached number, so nothing here blocks the live telemetry loop.
 */
class DiPlusAdbBridge(context: Context) {
    private val appContext = context.applicationContext

    @Volatile private var rpm: Int? = null
    @Volatile private var updatedAtMs = 0L
    @Volatile private var status: String = "not started"
    @Volatile private var running = false
    @Volatile private var lastConnectAttemptMs = 0L
    @Volatile private var writerStarted = false
    @Volatile private var lastWriterCheckMs = 0L
    @Volatile private var fractionalSleep: Boolean? = null
    private var worker: Thread? = null

    /** Latest value, or null if none has arrived or the last one went stale. */
    fun latestRpm(): Int? {
        val value = rpm ?: return null
        return if (SystemClock.elapsedRealtime() - updatedAtMs <= STALE_AFTER_MS) value else null
    }

    fun status(): String = status

    fun start() {
        if (running) return
        running = true
        worker = Thread({
            while (running) {
                val startedAt = SystemClock.elapsedRealtime()
                poll()
                // Sleep the remainder of the period, not a full period on top of
                // the ADB round trip -- that turned a 100ms poll into ~140ms and
                // was a third of the end-to-end lag on its own.
                val remaining = POLL_INTERVAL_MS - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) {
                    try {
                        Thread.sleep(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }
        }, "DriveApex-DiPlusAdb").apply { isDaemon = true }
        worker?.start()
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        runCatching { stopWriter() }
    }

    /** One fetch, for the diagnostics screen, without starting the poll loop. */
    fun readOnce(): Int? {
        poll()
        return rpm
    }

    private fun poll() {
        val dadb = liveConnection()
        if (dadb == null) {
            status = "ADB not available (${VehicleAdbConnection.lastError() ?: "not authorized"})"
            return
        }
        ensureWriter(dadb)

        val cached = runCatching { VehicleAdbConnection.shell(dadb, READ_COMMAND).output }.getOrNull()
        val fromFile = cached?.let { parseRpm(it) }
        if (fromFile != null) {
            publish(fromFile, "file")
            return
        }

        // Read directly this cycle when the file has nothing yet. This must not
        // touch the writer's state: doing so previously turned one unparsed read
        // into a writer respawn on every 50ms poll.
        val direct = runCatching { VehicleAdbConnection.shell(dadb, CURL_COMMAND).output }.getOrNull()
        val fromCurl = direct?.let { parseRpm(it) }
        if (fromCurl != null) {
            publish(fromCurl, "curl")
            return
        }
        status = "no value (file: ${cached?.trim()?.take(50)} / curl: ${direct?.trim()?.take(50)})"
    }

    private fun publish(value: Int, via: String) {
        rpm = value
        updatedAtMs = SystemClock.elapsedRealtime()
        status = "ok ($value RPM via $via)"
    }

    /**
     * Keeps exactly one writer alive on the vehicle.
     *
     * The previous version restarted it whenever a read failed to parse, and the
     * fallback path cleared the started flag on every successful direct read --
     * so a single unparsed sample put it into a loop that launched a fresh
     * writer twenty times a second, each one polling the service on its own
     * timer and writing the same file. The reading was fine at first and then
     * degraded as they piled up, which is exactly what was reported.
     *
     * Three things prevent that now. The writer records its pid, and `kill -0`
     * on it is the liveness test, so no spawn happens while one is running --
     * this needs neither pgrep nor pkill, which a head unit may not have.
     * Restart attempts are rate-limited regardless of what the check says. And
     * the writer counts its own iterations and exits, so even a leaked one dies
     * on its own instead of running until the next reboot.
     */
    private fun ensureWriter(dadb: Dadb) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWriterCheckMs < WRITER_CHECK_MS) return
        lastWriterCheckMs = now

        val alive = runCatching {
            VehicleAdbConnection.shell(dadb, "kill -0 \"${'$'}(cat $PID_FILE 2>/dev/null)\" 2>/dev/null && echo ALIVE")
                .output.contains("ALIVE")
        }.getOrDefault(false)
        if (alive) {
            writerStarted = true
            return
        }

        // Probe fractional sleep once per connection, not per restart: it costs a
        // blocking shell round trip on a lock every other reader shares.
        if (fractionalSleep == null) {
            fractionalSleep = runCatching {
                VehicleAdbConnection.shell(dadb, "sleep 0.05 2>/dev/null && echo FRAC_OK").output.contains("FRAC_OK")
            }.getOrDefault(false)
        }
        val interval = if (fractionalSleep == true) "0.05" else "1"
        val iterations = if (fractionalSleep == true) WRITER_LIFETIME_MS / 50 else WRITER_LIFETIME_MS / 1000

        val d = "\$"
        val loop = "echo ${d}${d} > $PID_FILE; i=0; " +
            "while [ ${d}i -lt $iterations ]; do " +
            "curl -s -m 2 \"http://127.0.0.1:8988$PATH_QUERY\" > $OUT_FILE.tmp 2>/dev/null " +
            "&& mv $OUT_FILE.tmp $OUT_FILE; " +
            "i=${d}((i+1)); sleep $interval; done; rm -f $PID_FILE"
        runCatching {
            VehicleAdbConnection.shell(dadb, "nohup sh -c '$loop' >/dev/null 2>&1 &")
            writerStarted = true
        }.onFailure { status = "writer start failed: ${it.javaClass.simpleName}" }
    }

    private fun stopWriter() {
        val dadb = VehicleAdbConnection(appContext).existing() ?: return
        runCatching {
            VehicleAdbConnection.shell(dadb, "kill \"${'$'}(cat $PID_FILE 2>/dev/null)\" 2>/dev/null; rm -f $PID_FILE")
        }
        writerStarted = false
        lastWriterCheckMs = 0L
    }

    companion object {
        private const val POLL_INTERVAL_MS = 50L
        private const val RECONNECT_INTERVAL_MS = 5_000L
        private const val STALE_AFTER_MS = 3_000L
        private const val PATH_QUERY =
            "/api/getVal?name=%E5%89%8D%E7%94%B5%E6%9C%BA%E8%BD%AC%E9%80%9F&status=true"
        private const val OUT_FILE = "/data/local/tmp/driveapex_front_motor"
        private const val PID_FILE = "/data/local/tmp/driveapex_rpm_writer.pid"
        private const val WRITER_CHECK_MS = 3_000L
        private const val WRITER_LIFETIME_MS = 60_000
        private const val READ_COMMAND = "cat $OUT_FILE 2>/dev/null"
        private const val CURL_COMMAND = "curl -s -m 3 \"http://127.0.0.1:8988$PATH_QUERY\""

        /** Shared with DiPlusMotorSpeedReader: `val` is a quoted, signed number. */
        private val pattern = java.util.regex.Pattern.compile(
            "\"val\"\\s*:\\s*\"?([-+]?\\d+(?:\\.\\d+)?)\"?"
        )

        fun parseRpm(body: String): Int? {
            val match = pattern.matcher(body)
            if (!match.find()) return null
            val raw = match.group(1)?.toDoubleOrNull() ?: return null
            if (!raw.isFinite()) return null
            val rpm = kotlin.math.abs(raw)
            return if (rpm <= 25_000.0) rpm.toInt() else null
        }
    }
}
