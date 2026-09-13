package com.maxprint.epson.transport

import com.maxprint.epson.util.L
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A deliberately small HTTP/1.1 client that speaks over any byte stream pair.
 *
 * We need this because IPP-over-USB is literally HTTP on top of two bulk endpoints:
 * there is no socket for HttpURLConnection to wrap, so the protocol has to be written
 * out by hand. Only the pieces IPP uses are implemented: POST, Content-Length or chunked
 * request bodies, 100-continue skipping, Content-Length or chunked responses.
 */
object HttpOverStream {

    private const val CRLF = "\r\n"

    fun post(
        input: InputStream,
        output: OutputStream,
        host: String,
        path: String,
        contentType: String,
        contentLength: Long,
        body: (OutputStream) -> Unit
    ): HttpReply {
        val head = StringBuilder()
        head.append("POST ").append(path).append(" HTTP/1.1").append(CRLF)
        head.append("Host: ").append(host).append(CRLF)
        head.append("User-Agent: EpsonPrintEnablerMax/1.0").append(CRLF)
        head.append("Content-Type: ").append(contentType).append(CRLF)
        head.append("Accept: application/ipp").append(CRLF)
        head.append("Connection: keep-alive").append(CRLF)
        if (contentLength >= 0) {
            head.append("Content-Length: ").append(contentLength).append(CRLF)
        } else {
            head.append("Transfer-Encoding: chunked").append(CRLF)
        }
        head.append(CRLF)

        val out = BufferedOutputStream(output, 16 * 1024)
        out.write(head.toString().toByteArray(Charsets.US_ASCII))

        if (contentLength >= 0) {
            body(out)
        } else {
            val chunked = ChunkedOutputStream(out)
            body(chunked)
            chunked.finish()
        }
        out.flush()

        return readReply(input)
    }

    private fun readReply(input: InputStream): HttpReply {
        while (true) {
            val statusLine = readLine(input)
                ?: throw IOException("Printer closed the connection before replying")
            if (statusLine.isEmpty()) continue

            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
                throw IOException("Malformed HTTP status line: $statusLine")
            }
            val status = parts[1].toIntOrNull() ?: throw IOException("Bad status: $statusLine")
            val reason = parts.getOrElse(2) { "" }

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                headers[name] = line.substringAfter(':').trim()
            }

            // 1xx are interim; the real reply follows on the same connection.
            if (status in 100..199) {
                L.d("HTTP interim $status, waiting for the real response")
                continue
            }

            val stream: InputStream = when {
                headers["transfer-encoding"]?.contains("chunked", true) == true ->
                    ChunkedInputStream(input)

                headers["content-length"] != null ->
                    FixedLengthInputStream(input, headers["content-length"]!!.toLong())

                else -> input
            }
            return HttpReply(status, reason, headers, stream)
        }
    }

    /** Reads one CRLF-terminated line without over-reading into the body. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val c = input.read()
            if (c == -1) return if (buf.size() == 0) null else buf.toString("US-ASCII")
            if (c == '\n'.code) {
                var s = buf.toString("US-ASCII")
                if (s.endsWith("\r")) s = s.dropLast(1)
                return s
            }
            buf.write(c)
        }
    }

    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        private val one = ByteArray(1)

        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return // a zero length chunk would terminate the body
            out.write(Integer.toHexString(len).toByteArray(Charsets.US_ASCII))
            out.write(CRLF_BYTES)
            out.write(b, off, len)
            out.write(CRLF_BYTES)
        }

        fun finish() {
            out.write("0$CRLF$CRLF".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        companion object {
            private val CRLF_BYTES = CRLF.toByteArray(Charsets.US_ASCII)
        }
    }

    private class FixedLengthInputStream(
        private val src: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val c = src.read()
            if (c >= 0) remaining--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, src.available().toLong()).toInt()
    }

    private class ChunkedInputStream(private val src: InputStream) : InputStream() {
        private var remaining = 0L
        private var finished = false

        private fun nextChunk(): Boolean {
            if (finished) return false
            if (remaining > 0) return true
            // A chunk after the first is preceded by the CRLF that closed the previous one.
            var line = readLine(src) ?: return false.also { finished = true }
            if (line.isEmpty()) line = readLine(src) ?: return false.also { finished = true }
            val size = line.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Bad chunk size: $line")
            if (size == 0L) {
                finished = true
                // Consume trailers up to the blank line.
                while (true) {
                    val t = readLine(src) ?: break
                    if (t.isEmpty()) break
                }
                return false
            }
            remaining = size
            return true
        }

        override fun read(): Int {
            if (!nextChunk()) return -1
            val c = src.read()
            if (c >= 0) remaining--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!nextChunk()) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }
}
