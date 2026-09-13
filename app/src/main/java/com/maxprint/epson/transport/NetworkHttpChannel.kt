package com.maxprint.epson.transport

import com.maxprint.epson.util.L
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** IPP over TCP, i.e. what the stock Wi-Fi plugin does. */
class NetworkHttpChannel(
    private val host: String,
    private val port: Int,
    private val useTls: Boolean = false,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 60_000
) : HttpChannel {

    override val authority: String get() = "$host:$port"

    override fun post(
        path: String,
        contentType: String,
        contentLength: Long,
        body: (OutputStream) -> Unit
    ): HttpReply {
        val scheme = if (useTls) "https" else "http"
        val url = URL("$scheme://$host:$port$path")
        val conn = url.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) applySelfSignedTrust(conn)

        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.doInput = true
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("Accept", "application/ipp")
        conn.setRequestProperty("User-Agent", "EpsonPrintEnablerMax/1.0")

        if (contentLength >= 0) {
            conn.setFixedLengthStreamingMode(contentLength)
        } else {
            conn.setChunkedStreamingMode(32 * 1024)
        }

        conn.outputStream.use { body(it) }

        val status = conn.responseCode
        val headers = LinkedHashMap<String, String>()
        conn.headerFields.forEach { (k, v) ->
            if (k != null && v.isNotEmpty()) headers[k.lowercase()] = v.first()
        }
        val stream = if (status in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return HttpReply(status, conn.responseMessage ?: "", headers, stream) { conn.disconnect() }
    }

    override fun close() = Unit

    /**
     * Printers ship self-signed certificates that no device trust store knows about.
     * IPPS here is about getting the bytes off the local link, not about authenticating a
     * public server, so a printer-specific trust-all context is the pragmatic choice —
     * the same one CUPS makes for local IPPS printers.
     */
    private fun applySelfSignedTrust(conn: HttpsURLConnection) {
        try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            })
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, java.security.SecureRandom())
            conn.sslSocketFactory = ctx.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        } catch (t: Throwable) {
            L.w("Could not relax TLS for printer $host", t)
        }
    }
}
