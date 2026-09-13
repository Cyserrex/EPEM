package com.maxprint.epson.transport

import com.maxprint.epson.usb.UsbSession
import java.io.OutputStream

/**
 * IPP over USB (USB printer class, subclass 1, protocol 4).
 *
 * The printer exposes a plain HTTP/1.1 server on its bulk endpoints, so once the bytes
 * are flowing this is exactly the same protocol as the Wi-Fi path — which is why the
 * layers above this one do not branch on transport at all.
 */
class UsbIppChannel(
    private val session: UsbSession,
    private val closeSession: Boolean = true
) : HttpChannel {

    override val authority: String get() = "localhost:60000"

    override fun post(
        path: String,
        contentType: String,
        contentLength: Long,
        body: (OutputStream) -> Unit
    ): HttpReply {
        // HTTP framing (Content-Length or chunked) already tells the printer where the
        // request ends, so no zero length packet is needed to terminate the transfer.
        return HttpOverStream.post(
            input = session.streams.input,
            output = session.streams.output,
            host = authority,
            path = path,
            contentType = contentType,
            contentLength = contentLength,
            body = body
        )
    }

    override fun close() {
        if (closeSession) session.close()
    }
}
