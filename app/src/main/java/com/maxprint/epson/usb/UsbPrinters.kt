package com.maxprint.epson.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.maxprint.epson.transport.DeviceId1284
import com.maxprint.epson.transport.UsbBulkStreams
import com.maxprint.epson.util.L
import java.io.Closeable
import java.io.IOException

/** A printer interface we can actually move bytes over. */
data class PrinterInterface(
    val iface: UsbInterface,
    val epIn: UsbEndpoint?,
    val epOut: UsbEndpoint,
    val protocol: Int
) {
    /** USB printer class protocol 4 means the interface tunnels HTTP, i.e. IPP-USB. */
    val isIppUsb: Boolean get() = protocol == PROTO_IPP_USB

    val isBidirectional: Boolean get() = epIn != null

    companion object {
        const val PROTO_UNIDIRECTIONAL = 1
        const val PROTO_BIDIRECTIONAL = 2
        const val PROTO_1284_4 = 3
        const val PROTO_IPP_USB = 4
    }
}

/** An open, claimed printer interface. Closing it releases the interface and the device. */
class UsbSession(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val printerInterface: PrinterInterface,
    val streams: UsbBulkStreams,
    val deviceId: DeviceId1284?
) : Closeable {

    override fun close() {
        try {
            connection.releaseInterface(printerInterface.iface)
        } catch (t: Throwable) {
            L.d("releaseInterface: ${t.message}")
        }
        try {
            connection.close()
        } catch (t: Throwable) {
            L.d("connection.close: ${t.message}")
        }
    }
}

object UsbPrinters {

    const val EPSON_VENDOR_ID = 0x04B8

    /** Every attached device that exposes something we can print to. */
    fun listPrinters(manager: UsbManager): List<UsbDevice> =
        manager.deviceList.values.filter { findInterfaces(it).isNotEmpty() }

    fun isEpson(device: UsbDevice): Boolean = device.vendorId == EPSON_VENDOR_ID

    /**
     * All usable printer interfaces on a device, best first: IPP-USB beats raw bulk,
     * and a bidirectional interface beats a write-only one because it lets us read
     * status back.
     */
    fun findInterfaces(device: UsbDevice): List<PrinterInterface> {
        val found = ArrayList<PrinterInterface>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
            val pi = toPrinterInterface(iface) ?: continue
            found += pi
        }

        if (found.isEmpty() && device.vendorId == EPSON_VENDOR_ID) {
            // A handful of Epson units hide the printer endpoints behind a vendor-specific
            // class. If we see a vendor interface with a bulk OUT endpoint, try it anyway.
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC) continue
                val pi = toPrinterInterface(iface) ?: continue
                found += pi.copy(protocol = PrinterInterface.PROTO_BIDIRECTIONAL)
            }
        }

        return found.sortedWith(
            compareByDescending<PrinterInterface> { it.isIppUsb }
                .thenByDescending { it.isBidirectional }
        )
    }

    private fun toPrinterInterface(iface: UsbInterface): PrinterInterface? {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
        }
        val out = epOut ?: return null
        return PrinterInterface(iface, epIn, out, iface.interfaceProtocol)
    }

    /**
     * Claim a printer interface and wrap it in streams.
     *
     * @param wantIppUsb prefer the IPP-USB interface when the device has one. Pass false to
     *        force the raw bulk path (used for ESC/P-R on older hardware).
     */
    @Throws(IOException::class)
    fun open(manager: UsbManager, device: UsbDevice, wantIppUsb: Boolean = true): UsbSession {
        if (!manager.hasPermission(device)) {
            throw IOException("No USB permission for ${device.deviceName}")
        }
        val candidates = findInterfaces(device)
        if (candidates.isEmpty()) throw IOException("No printer interface on ${device.deviceName}")

        val chosen = if (wantIppUsb) candidates.first()
        else candidates.firstOrNull { !it.isIppUsb } ?: candidates.first()

        val conn = manager.openDevice(device)
            ?: throw IOException("openDevice failed for ${device.deviceName}")

        // force=true detaches any kernel driver that grabbed the interface first.
        if (!conn.claimInterface(chosen.iface, true)) {
            conn.close()
            throw IOException("claimInterface failed on interface ${chosen.iface.id}")
        }

        val epIn = chosen.epIn
        val deviceId = try {
            DeviceId1284.read(conn, chosen.iface)
        } catch (t: Throwable) {
            L.d("device id read failed: ${t.message}")
            null
        }

        if (epIn == null) {
            // Unidirectional interface: fake an IN endpoint is impossible, so IPP is out and
            // only a fire-and-forget raw stream is available.
            if (chosen.isIppUsb) {
                conn.releaseInterface(chosen.iface)
                conn.close()
                throw IOException("IPP-USB interface without an IN endpoint")
            }
        }

        val streams = UsbBulkStreams(
            conn = conn,
            epIn = epIn ?: chosen.epOut, // never read from, guarded by isBidirectional
            epOut = chosen.epOut
        )
        L.i(
            "Opened USB printer ${device.vendorId.toString(16)}:${device.productId.toString(16)} " +
                "iface=${chosen.iface.id} proto=${chosen.protocol} ippusb=${chosen.isIppUsb}"
        )
        return UsbSession(device, conn, chosen, streams, deviceId)
    }
}
