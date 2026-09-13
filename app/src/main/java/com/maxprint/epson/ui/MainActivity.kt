package com.maxprint.epson.ui

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.maxprint.epson.databinding.ActivityMainBinding
import com.maxprint.epson.model.LinkType
import com.maxprint.epson.usb.UsbDiscovery
import com.maxprint.epson.usb.UsbPermission
import com.maxprint.epson.usb.UsbPrinters
import com.maxprint.epson.util.Prefs

/**
 * A launcher screen whose real job is to tell the user whether the plugin is switched on
 * and whether the cable is working — the printing itself happens from other apps.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnPrintSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
            } catch (t: Throwable) {
                Toast.makeText(this, "Open Settings, Connected devices, Printing", Toast.LENGTH_LONG)
                    .show()
            }
        }

        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnGrant.setOnClickListener { grantAll() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        prefs.applyLogLevel()
        // Describing a device opens it and issues a control transfer, so keep it off the
        // UI thread even though this screen is only diagnostics.
        Thread {
            val text = buildUsbReport()
            runOnUiThread { if (!isFinishing) binding.usbList.text = text }
        }.start()
        showNetworkSection()
    }

    private fun buildUsbReport(): CharSequence {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = UsbPrinters.listPrinters(manager)

        return if (devices.isEmpty()) {
            getString(com.maxprint.epson.R.string.no_usb_printers)
        } else {
            devices.joinToString("\n\n") { device ->
                val target = UsbDiscovery.describe(manager, device)
                val interfaces = UsbPrinters.findInterfaces(device)
                buildString {
                    append(target?.displayName ?: device.deviceName).append('\n')
                    append("  id       %04X:%04X".format(device.vendorId, device.productId)).append('\n')
                    append("  mode     ")
                    append(
                        when (target?.link) {
                            LinkType.USB_IPP -> "IPP over USB"
                            LinkType.USB_RAW -> "raw bulk, ESC/P-R"
                            else -> "unknown"
                        }
                    ).append('\n')
                    append("  access   ")
                    append(if (manager.hasPermission(device)) "granted" else "not granted yet").append('\n')
                    append("  ifaces   ").append(interfaces.size)
                    val cmd = target?.commandSet.orEmpty()
                    if (cmd.isNotEmpty()) append("\n  1284 CMD ").append(cmd.joinToString(","))
                }
            }
        }
    }

    private fun showNetworkSection() {
        val manual = prefs.manualTargets.mapNotNull { com.maxprint.epson.model.PrinterTarget.decode(it) }
        binding.netList.text = if (manual.isEmpty()) {
            "Network printers are discovered automatically when you open the print dialog.\n" +
                "Add one by hand from the print dialog, Add printer."
        } else {
            manual.joinToString("\n") { "${it.displayName}  ${it.address}${it.detail}" }
        }
    }

    private fun grantAll() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val pending = UsbPrinters.listPrinters(manager).filterNot { manager.hasPermission(it) }
        if (pending.isEmpty()) {
            Toast.makeText(this, "All connected printers are already accessible", Toast.LENGTH_SHORT)
                .show()
            return
        }
        // One dialog at a time; the next appears after the user answers this one.
        UsbPermission.request(this, pending.first())
    }
}
