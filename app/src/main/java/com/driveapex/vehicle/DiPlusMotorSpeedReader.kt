package com.driveapex.vehicle

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
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

    private val numberPattern = Pattern.compile("\\\"val\\\"\\s*:\\s*([-+]?\\d+(?:\\.\\d+)?)")

    fun readFrontMotorRpm(): Double? {
        for (host in candidateHosts()) {
            val value = readFrom(host)
            if (value != null && value.isFinite() && value in 0.0..MAX_RPM) return value
        }
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

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val body = StringBuilder()
            var inBody = false
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (inBody) {
                    body.append(line)
                } else if (line.isEmpty()) {
                    inBody = true
                }
            }
            val match = numberPattern.matcher(body.toString())
            if (!match.find()) null else match.group(1)?.toDoubleOrNull()
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
