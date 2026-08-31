package com.driveapex.vehicle

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Read-only client for the DiPlus local telemetry service.
 *
 * On the target vehicle the service is a native process, not the DiPlus Java
 * app:
 *
 *   tcp6  0  0  :::8988  :::*  LISTEN  1456//aps_diplus
 *
 * Two things follow from that netstat line, and both were getting this reader
 * nowhere:
 *
 *  - The socket is bound in the IPv6 namespace. This reader only ever collected
 *    Inet4Address candidates, so `::1` -- the address most likely to work from
 *    on-device -- was never tried.
 *  - The failures were `SocketException (Connection reset)`, not
 *    `ConnectException`. The server accepts the TCP connection and then tears it
 *    down, so it is running and reachable and is rejecting the request itself.
 *    A reset is not a reason to give up on the host; it is a reason to send a
 *    different request shape.
 *
 * So each candidate address is tried with two request forms: a curl-shaped
 * HTTP/1.1 request first (curl against this service is known to work), then a
 * minimal HTTP/1.0 one, which small embedded servers handle when nothing else
 * works. Whatever bytes arrived are parsed even if the socket then errors --
 * a server that answers and closes with RST instead of FIN would otherwise
 * throw away a complete response.
 */
object DiPlusMotorSpeedReader {
    private const val PORT = 8988
    private const val PATH = "/api/getVal?name=%E5%89%8D%E7%94%B5%E6%9C%BA%E8%BD%AC%E9%80%9F&status=true"
    private const val CONNECT_TIMEOUT_MS = 800
    private const val READ_TIMEOUT_MS = 2000
    private const val MAX_RPM = 25000.0
    private const val MAX_RESPONSE_CHARS = 8192

    // Verified against the vehicle: HTTP/1.1 200 OK, Server: DiplusApi,
    // {"success":true,"val":"-2571"} -- `val` is a quoted string and the reading
    // is negative, so the pattern accepts optional quotes and a sign.
    private val numberPattern = Pattern.compile("\"val\"\\s*:\\s*\"?([-+]?\\d+(?:\\.\\d+)?)\"?")

    @Volatile private var lastHost: String? = null
    @Volatile private var lastDetail: String = "not attempted"

    /** The address and request form that answered, or why each candidate failed. */
    fun lastPath(): String = lastHost ?: lastDetail

    fun readFrontMotorRpm(): Double? {
        val failures = mutableListOf<String>()
        for (host in candidateHosts()) {
            for (form in RequestForm.values()) {
                val outcome = readFrom(host, form)
                val raw = outcome.getOrNull()
                if (raw == null) {
                    val why = outcome.exceptionOrNull()
                        ?.let { it.javaClass.simpleName + (it.message?.let { m -> " ($m)" } ?: "") }
                        ?: "no val field in response"
                    failures += "$host/${form.label}: $why"
                    continue
                }
                if (!raw.isFinite()) { failures += "$host/${form.label}: non-finite"; continue }
                val rpm = kotlin.math.abs(raw)
                if (rpm > MAX_RPM) { failures += "$host/${form.label}: out of range ($rpm)"; continue }
                lastHost = "$host (${form.label})"
                return rpm
            }
        }
        lastHost = null
        lastDetail = failures.joinToString("; ").ifBlank { "no candidate addresses" }
        return null
    }

    private enum class RequestForm(val label: String) { CURL_STYLE("http11"), MINIMAL("http10") }

    private fun buildRequest(host: String, form: RequestForm): String {
        // An IPv6 literal must be bracketed in a Host header.
        val authority = if (host.contains(':')) "[$host]:$PORT" else "$host:$PORT"
        return when (form) {
            // Deliberately the same header set curl sends, since curl against this
            // service is the one request known to have worked on this vehicle.
            RequestForm.CURL_STYLE -> buildString {
                append("GET ").append(PATH).append(" HTTP/1.1\r\n")
                append("Host: ").append(authority).append("\r\n")
                append("User-Agent: DriveApex\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n\r\n")
            }
            // HTTP/1.0 with no extra headers: the server closes when done by
            // definition, and there is nothing left for a picky parser to reject.
            RequestForm.MINIMAL -> "GET $PATH HTTP/1.0\r\n\r\n"
        }
    }

    private fun readFrom(host: String, form: RequestForm): Result<Double?> {
        val received = StringBuilder()
        val outcome = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().apply {
                    write(buildRequest(host, form).toByteArray(StandardCharsets.US_ASCII))
                    flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                while (received.length < MAX_RESPONSE_CHARS) {
                    val line = reader.readLine() ?: break
                    received.append(line).append('\n')
                    if (numberPattern.matcher(received).find()) break
                }
            }
        }
        // Parse whatever arrived regardless of how the socket ended. A server that
        // answers in full and then resets instead of closing cleanly would
        // otherwise have its response discarded.
        val match = numberPattern.matcher(received)
        if (match.find()) return Result.success(match.group(1)?.toDoubleOrNull())
        return outcome.fold({ Result.success<Double?>(null) }, { Result.failure(it) })
    }

    /**
     * Loopback first (both families -- the service is bound in the IPv6
     * namespace), then every address this device actually holds. IPv6 is included
     * because that is where the listening socket lives.
     */
    private fun candidateHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        hosts += "127.0.0.1"
        hosts += "::1"
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val ipv6 = mutableListOf<String>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue
                    val text = address.hostAddress?.substringBefore('%') ?: continue
                    if (address is Inet6Address) ipv6 += text else hosts += text
                }
            }
            hosts += ipv6
        }
        return hosts.toList()
    }
}
