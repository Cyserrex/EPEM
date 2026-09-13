package com.maxprint.epson.usb

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import com.maxprint.epson.util.L

/**
 * Invisible activity the system launches when a printer is plugged into the OTG port.
 *
 * Being launched from the USB_DEVICE_ATTACHED intent filter is what makes Android grant
 * access to that device without a prompt, and what makes the "always open for this device"
 * checkbox available — so plugging a printer in once is enough to print from then on.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device == null) {
            finish()
            return
        }

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val interfaces = UsbPrinters.findInterfaces(device)
        if (interfaces.isEmpty()) {
            L.d("Attached device ${device.deviceName} has no printer interface")
            finish()
            return
        }

        if (!manager.hasPermission(device)) {
            UsbPermission.request(this, device)
        } else {
            val kind = if (interfaces.first().isIppUsb) "IPP-USB" else "ESC/P-R"
            Toast.makeText(
                this,
                "${device.productNameOrId()} ready over USB ($kind)",
                Toast.LENGTH_SHORT
            ).show()
        }
        finish()
    }

    private fun UsbDevice.productNameOrId(): String =
        runCatching { productName }.getOrNull()
            ?: "Printer %04X:%04X".format(vendorId, productId)
}
