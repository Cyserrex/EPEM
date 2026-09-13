package com.maxprint.epson.transport

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Something that can POST a body and give back a response. Implemented over TCP and over USB. */
interface HttpChannel : Closeable {

    /** Host header value, and the authority used when we have to build a printer-uri. */
    val authority: String

    /**
     * @param contentLength -1 when the body length is not known up front, in which case the
     *        implementation must use chunked transfer encoding.
     */
    fun post(
        path: String,
        contentType: String,
        contentLength: Long,
        body: (OutputStream) -> Unit
    ): HttpReply
}

class HttpReply(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: InputStream,
    private val onClose: () -> Unit = {}
) : Closeable {
    val isSuccess: Boolean get() = status in 200..299

    fun header(name: String): String? = headers[name.lowercase()]

    override fun close() = onClose()
}
