package com.maxprint.epson.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.maxprint.epson.util.L
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

/**
 * Adapts a pair of USB bulk endpoints to ordinary Java streams so the IPP and PDL code
 * above it does not need to care whether it is talking to a socket or to an OTG cable.
 */
class UsbBulkStreams(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val ioTimeoutMs: Int = 15_000
) {
    /**
     * bulkTransfer historically caps out around 16 KiB per call on several OEM kernels,
     * so we never hand it more than that regardless of what the caller writes.
     */
    private val chunk = 16 * 1024

    val input: InputStream = object : InputStream() {
        private val buf = ByteArray(maxOf(epIn.maxPacketSize, 4096))
        private var pos = 0
        private var len = 0

        private fun fill(): Boolean {
            val deadline = System.currentTimeMillis() + ioTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                val n = conn.bulkTransfer(epIn, buf, buf.size, 1_000)
                if (n > 0) {
                    pos = 0
                    len = n
                    return true
                }
                // n == 0 is a zero length packet and n < 0 means "nothing yet";
                // in both cases keep polling until our own deadline expires.
            }
            return false
        }

        override fun read(): Int {
            if (pos >= len && !fill()) throw IOException("USB read timeout")
            return buf[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, count: Int): Int {
            if (count == 0) return 0
            if (pos >= len && !fill()) throw IOException("USB read timeout")
            val n = min(count, len - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = len - pos
    }

    val output: OutputStream = object : OutputStream() {
        private val one = ByteArray(1)

        override fun write(b: Int) {
            one[0] = b.toByte()
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, count: Int) {
            var written = 0
            while (written < count) {
                val n = min(chunk, count - written)
                // Several ROMs still ship a bulkTransfer that ignores the offset overload,
                // so copy the slice unless it already starts at zero.
                val slice = if (off + written == 0 && n == b.size) b
                else b.copyOfRange(off + written, off + written + n)
                val sent = conn.bulkTransfer(epOut, slice, n, ioTimeoutMs)
                if (sent < 0) throw IOException("USB write failed after $written of $count bytes")
                if (sent == 0) throw IOException("USB write stalled")
                written += sent
            }
        }

        override fun flush() {
            // Nothing buffered on our side; the kernel owns the URB queue.
        }
    }

    /** Some printers expect a zero length packet to mark the end of a bulk transfer. */
    fun sendZeroLengthPacket() {
        try {
            conn.bulkTransfer(epOut, ByteArray(0), 0, 500)
        } catch (t: Throwable) {
            L.d("ZLP failed (harmless): ${t.message}")
        }
    }
}
