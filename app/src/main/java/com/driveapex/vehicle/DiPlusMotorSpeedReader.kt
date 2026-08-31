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
    private const val CONNECT_TIMEOUT_MS = 120
    private const val READ_TIMEOUT_MS = 250
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
    @Volatile private var lastTried: String = ""

    /** The address that last answered, or the list tried if none did. */
    fun lastPath(): String = lastHost ?: "none of [$lastTried]"

    fun readFrontMotorRpm(): Double? {
        val hosts = candidateHosts()
        lastTried = hosts.joinToString()
        for (host in hosts) {
            val raw = readFrom(host) ?: continue
            if (!raw.isFinite()) continue
            val rpm = kotlin.math.abs(raw)
            if (rpm <= MAX_RPM) {
                lastHost = host
                return rpm
            }
        }
        lastHost = null
        return null
    }

    private fun readFrom(host: String): Double? = runCatching {
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
    }.getOrNull()

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
