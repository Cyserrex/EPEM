package com.maxprint.epson.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import com.maxprint.epson.util.L

/**
 * IEEE-1284 device identification string, fetched with the USB printer class
 * GET_DEVICE_ID request. This is how we learn whether a given Epson wants ESC/P-R,
 * and what to call it in the print dialog.
 *
 * Typical payload:
 * MFG:EPSON;CMD:ESCPL2,BDC,D4,D4PX,ESCPR1,END4;MDL:L3150 Series;CLS:PRINTER;
 */
data class DeviceId1284(
    val raw: String,
    val fields: Map<String, String>
) {
    val manufacturer: String?
        get() = fields["MANUFACTURER"] ?: fields["MFG"]

    val model: String?
        get() = fields["MODEL"] ?: fields["MDL"]

    val commandSet: List<String>
        get() = (fields["COMMAND SET"] ?: fields["CMD"])
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** ESCPR1 / ESCPR2 in the CMD list means the printer takes an ESC/P-R raster job. */
    val supportsEscpr: Boolean
        get() = commandSet.any { it.startsWith("ESCPR", ignoreCase = true) }

    val isEpson: Boolean
        get() = manufacturer?.contains("epson", ignoreCase = true) == true

    fun displayName(): String {
        val mfg = manufacturer?.trim().orEmpty()
        val mdl = model?.trim().orEmpty()
        return when {
            mdl.isEmpty() -> mfg.ifEmpty { "USB printer" }
            mfg.isEmpty() || mdl.startsWith(mfg, ignoreCase = true) -> mdl
            else -> "$mfg $mdl"
        }
    }

    companion object {
        private const val GET_DEVICE_ID = 0

        /** IN | class | recipient=interface */
        private const val REQ_TYPE_IN_CLASS_INTERFACE =
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01

        fun read(conn: UsbDeviceConnection, iface: UsbInterface, configIndex: Int = 0): DeviceId1284? {
            val buf = ByteArray(1024)
            val wIndex = (iface.id shl 8) or iface.alternateSetting
            val n = conn.controlTransfer(
                REQ_TYPE_IN_CLASS_INTERFACE,
                GET_DEVICE_ID,
                configIndex,
                wIndex,
                buf,
                buf.size,
                3_000
            )
            if (n < 2) {
                L.d("GET_DEVICE_ID returned $n")
                return null
            }
            // The first two bytes are a big-endian length that includes themselves.
            var declared = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            if (declared < 2 || declared > n) declared = n
            val text = String(buf, 2, declared - 2, Charsets.US_ASCII).trim()
            L.d("1284 device id: $text")
            return parse(text)
        }

        fun parse(text: String): DeviceId1284 {
            val fields = LinkedHashMap<String, String>()
            for (entry in text.split(';')) {
                if (!entry.contains(':')) continue
                val key = entry.substringBefore(':').trim().uppercase()
                if (key.isEmpty()) continue
                fields[key] = entry.substringAfter(':').trim()
            }
            return DeviceId1284(text, fields)
        }
    }
}
