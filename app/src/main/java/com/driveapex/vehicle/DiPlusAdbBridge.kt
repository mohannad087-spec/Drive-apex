package com.driveapex.vehicle

import android.content.Context
import android.os.SystemClock
import com.driveapex.update.VehicleAdbConnection

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
 * It is a fallback, not the primary: each poll is an ADB round trip, so the
 * value is refreshed on a background thread and the caller only ever reads a
 * cached number. Nothing here blocks the live telemetry loop.
 */
class DiPlusAdbBridge(context: Context) {
    private val appContext = context.applicationContext

    @Volatile private var rpm: Int? = null
    @Volatile private var updatedAtMs = 0L
    @Volatile private var status: String = "not started"
    @Volatile private var running = false
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
    }

    /** One fetch, for the diagnostics screen, without starting the poll loop. */
    fun readOnce(): Int? {
        poll()
        return rpm
    }

    private fun poll() {
        val dadb = runCatching { VehicleAdbConnection(appContext).connect() }.getOrNull()
        if (dadb == null) {
            status = "ADB not available (${VehicleAdbConnection.lastError() ?: "not authorized"})"
            return
        }
        val result = runCatching { dadb.shell(CURL_COMMAND).output }
        val body = result.getOrNull()
        if (body == null) {
            status = "shell failed: ${result.exceptionOrNull()?.javaClass?.simpleName}"
            return
        }
        val value = parseRpm(body)
        if (value == null) {
            status = "no val in response: ${body.trim().take(120)}"
            return
        }
        rpm = value
        updatedAtMs = SystemClock.elapsedRealtime()
        status = "ok ($value RPM via ADB shell)"
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val STALE_AFTER_MS = 3_000L
        private const val PATH =
            "/api/getVal?name=%E5%89%8D%E7%94%B5%E6%9C%BA%E8%BD%AC%E9%80%9F&status=true"
        private const val CURL_COMMAND = "curl -s -m 3 'http://127.0.0.1:8988$PATH'"

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
