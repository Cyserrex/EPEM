package com.maxprint.epson.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.maxprint.epson.model.LinkType
import com.maxprint.epson.model.PrinterTarget
import com.maxprint.epson.transport.DeviceId1284
import com.maxprint.epson.util.L

/**
 * Enumerates printers on the OTG port and turns them into [PrinterTarget]s.
 *
 * Without USB permission we can still see the vendor and product ids, so a device shows up
 * in the print dialog immediately and the permission prompt only appears when the user
 * actually picks it. With permission we additionally read the IEEE-1284 device id, which
 * gives a proper model name and tells us whether the printer wants ESC/P-R.
 */
object UsbDiscovery {

    fun scan(context: Context, onlyEpson: Boolean): List<PrinterTarget> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbPrinters.listPrinters(manager)
            .filter { !onlyEpson || UsbPrinters.isEpson(it) }
            .mapNotNull { describe(manager, it) }
    }

    fun describe(manager: UsbManager, device: UsbDevice): PrinterTarget? {
        val interfaces = UsbPrinters.findInterfaces(device)
        if (interfaces.isEmpty()) return null
        val best = interfaces.first()

        var deviceId: DeviceId1284? = null
        var serial = ""
        if (manager.hasPermission(device)) {
            // Opening the device is cheap and gives us a real model name for the list.
            runCatching {
                UsbPrinters.open(manager, device, wantIppUsb = best.isIppUsb).use { session ->
                    deviceId = session.deviceId
                    serial = runCatching { device.serialNumber.orEmpty() }.getOrDefault("")
                }
            }.onFailure { L.d("probe of ${device.deviceName} failed: ${it.message}") }
        }

        val link = if (best.isIppUsb) LinkType.USB_IPP else LinkType.USB_RAW
        val name = deviceId?.displayName()
            ?: device.productNameCompat()
            ?: "USB printer %04X:%04X".format(device.vendorId, device.productId)

        return PrinterTarget(
            link = link,
            address = PrinterTarget.usbKey(device.vendorId, device.productId),
            detail = serial,
            displayName = if (link == LinkType.USB_IPP) name else "$name (USB)",
            manufacturer = deviceId?.manufacturer
                ?: if (UsbPrinters.isEpson(device)) "EPSON" else null,
            model = deviceId?.model,
            commandSet = deviceId?.commandSet ?: emptyList()
        )
    }

    private fun UsbDevice.productNameCompat(): String? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            runCatching { productName }.getOrNull()
        } else null
}
