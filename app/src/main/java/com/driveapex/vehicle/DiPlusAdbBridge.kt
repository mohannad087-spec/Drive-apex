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
                poll()
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
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
            writerStarted = false
            return
        }
        if (!writerStarted) startWriter(dadb)
        val result = runCatching { VehicleAdbConnection.shell(dadb, READ_COMMAND).output }
        val cached = result.getOrNull()
        if (cached == null) {
            status = "shell failed: ${result.exceptionOrNull()?.javaClass?.simpleName}"
            writerStarted = false
            return
        }
        // Fall back to fetching directly this cycle if the file is not being kept
        // up. The writer needs nohup, pkill and a shell that sleeps in fractions,
        // and none of those are guaranteed on a head unit -- without this the
        // whole path would silently produce nothing if any of them were missing.
        var value = parseRpm(cached)
        if (value == null) {
            val direct = runCatching { VehicleAdbConnection.shell(dadb, CURL_COMMAND).output }.getOrNull()
            value = direct?.let { parseRpm(it) }
            if (value != null) {
                writerStarted = false
                status = "ok ($value RPM via ADB curl; writer not producing)"
                rpm = value
                updatedAtMs = SystemClock.elapsedRealtime()
                return
            }
            status = "no value (file: ${cached.trim().take(60)} / curl: ${direct?.trim()?.take(60)})"
            return
        }
        rpm = value
        updatedAtMs = SystemClock.elapsedRealtime()
        status = "ok ($value RPM via ADB shell)"
    }

    /**
     * The established ADB connection, reconnecting at most once every few
     * seconds when there is none.
     *
     * connect() must not be called per poll: even on its cached path it
     * round-trips an `echo` and then re-runs one `pm grant` per declared
     * permission, so polling through it would issue a dozen ADB commands every
     * cycle and swamp the connection this app depends on for everything.
     */
    private fun liveConnection(): Dadb? {
        VehicleAdbConnection(appContext).existing()?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (lastConnectAttemptMs != 0L && now - lastConnectAttemptMs < RECONNECT_INTERVAL_MS) return null
        lastConnectAttemptMs = now
        return runCatching { VehicleAdbConnection(appContext).connect() }.getOrNull()
    }

    /**
     * Starts a small loop on the vehicle that keeps a file topped up with the
     * latest reading, so a poll is a cheap `cat` instead of spawning curl over
     * ADB every time. That is what makes the value feel live rather than
     * arriving a quarter-second late behind a process launch.
     *
     * The write is to a temp file then renamed, so a reader never sees a
     * half-written body. Fractional sleep is probed first: if this shell cannot
     * do it, a busy loop would spin the vehicle's CPU, so it falls back to a
     * one-second cadence instead.
     */
    private fun startWriter(dadb: Dadb) {
        runCatching {
            // Clear any previous writer before starting one, so a reconnect or an
            // app restart can never leave two loops polling the service.
            VehicleAdbConnection.shell(dadb, "pkill -f $WRITER_MARKER 2>/dev/null || true")
            val fractional = runCatching {
                VehicleAdbConnection.shell(dadb, "sleep 0.1 2>/dev/null && echo FRAC_OK").output.contains("FRAC_OK")
            }.getOrDefault(false)
            val interval = if (fractional) "0.1" else "1"
            VehicleAdbConnection.shell(
                dadb,
                "nohup sh -c 'while true; do curl -s -m 2 \"http://127.0.0.1:8988$PATH_QUERY\" " +
                    "> $OUT_FILE.tmp 2>/dev/null && mv $OUT_FILE.tmp $OUT_FILE; " +
                    "echo $WRITER_MARKER > /dev/null; sleep $interval; done' >/dev/null 2>&1 &"
            )
            writerStarted = true
            if (!fractional) status = "writer running at 1s (shell has no fractional sleep)"
        }.onFailure { status = "writer start failed: ${it.javaClass.simpleName}" }
    }

    private fun stopWriter() {
        val dadb = VehicleAdbConnection(appContext).existing() ?: return
        runCatching { VehicleAdbConnection.shell(dadb, "pkill -f $WRITER_MARKER 2>/dev/null || true") }
        writerStarted = false
    }

    companion object {
        private const val POLL_INTERVAL_MS = 100L
        private const val RECONNECT_INTERVAL_MS = 5_000L
        private const val STALE_AFTER_MS = 3_000L
        private const val PATH_QUERY =
            "/api/getVal?name=%E5%89%8D%E7%94%B5%E6%9C%BA%E8%BD%AC%E9%80%9F&status=true"
        private const val OUT_FILE = "/data/local/tmp/driveapex_front_motor"
        private const val WRITER_MARKER = "driveapex_rpm_writer"
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
