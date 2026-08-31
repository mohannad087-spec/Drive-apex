package com.driveapex.vehicle

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Read-only bridge to the DiPlus local HTTP telemetry service.
 *
 * DiPlus exposes the vehicle value through:
 *   /api/getVal?name=前电机转速&status=true
 *
 * The Chinese key means "front motor speed" and the service returns JSON with a
 * numeric `val` field. This is intentionally independent of BYD HAL feature IDs.
 */
object DiPlusMotorSpeedReader {
    private const val PORT = 8988
    private const val PATH = "/api/getVal?name=%E5%89%8D%E7%94%B5%E6%9C%BA%E8%BD%AC%E9%80%9F&status=true"
    private const val CONNECT_TIMEOUT_MS = 800
    private const val READ_TIMEOUT_MS = 2000
    private const val MAX_RPM = 25000.0
    private const val MAX_RESPONSE_CHARS = 8192

    // Verified against the vehicle: the service answers
    //   HTTP/1.1 200 OK  Server: DiplusApi
    //   {"success":true,"val":"-2571"}
    // so `val` is a quoted string, not a bare number, and the reading is negative
    // (DiPlus stores -value.intValue for this feature). The previous pattern
    // required a bare number and the range check rejected anything below zero, so
    // this reader could never have returned a value.
    private val numberPattern = Pattern.compile("\"val\"\\s*:\\s*\"?([-+]?\\d+(?:\\.\\d+)?)\"?")

    @Volatile private var lastHost: String? = null
    @Volatile private var lastDetail: String = "not attempted"

    /**
     * The address that answered, or per-host failure reasons. "No answer" on its
     * own is useless: ConnectException means nothing is listening there,
     * SocketTimeoutException means the service is there but slower than the read
     * timeout, and the two call for opposite fixes.
     */
    fun lastPath(): String = lastHost ?: lastDetail

    fun readFrontMotorRpm(): Double? {
        val failures = mutableListOf<String>()
        for (host in candidateHosts()) {
            val outcome = readFrom(host)
            val raw = outcome.getOrNull()
            if (raw == null) {
                val why = outcome.exceptionOrNull()?.let {
                    it.javaClass.simpleName + (it.message?.let { m -> " ($m)" } ?: "")
                } ?: "no val field in response"
                failures += "$host: $why"
                continue
            }
            if (!raw.isFinite()) { failures += "$host: non-finite value"; continue }
            val rpm = kotlin.math.abs(raw)
            if (rpm > MAX_RPM) { failures += "$host: out of range ($rpm)"; continue }
            lastHost = host
            lastDetail = host
            return rpm
        }
        lastHost = null
        lastDetail = failures.joinToString("; ").ifBlank { "no candidate addresses" }
        return null
    }

    private fun readFrom(host: String): Result<Double?> = runCatching {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            val request = buildString {
                append("GET ").append(PATH).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(":").append(PORT).append("\r\n")
                append("Connection: close\r\n")
                append("Accept: application/json\r\n\r\n")
            }
            out.write(request.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            // Match as the response arrives instead of reading to EOF. Waiting for the
            // server to close would throw SocketTimeoutException on any server that
            // ignores `Connection: close` -- discarding a value already in hand.
            // Headers cannot contain `"val":`, so scanning everything is safe and also
            // survives chunked transfer encoding.
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val received = StringBuilder()
            var value: Double? = null
            while (value == null && received.length < MAX_RESPONSE_CHARS) {
                val line = reader.readLine() ?: break
                received.append(line).append('\n')
                val match = numberPattern.matcher(received)
                if (match.find()) value = match.group(1)?.toDoubleOrNull()
            }
            value
        }
    }

    private fun candidateHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        hosts += "127.0.0.1"
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) hosts += address.hostAddress
                }
            }
        }
        return hosts.toList()
    }
}
