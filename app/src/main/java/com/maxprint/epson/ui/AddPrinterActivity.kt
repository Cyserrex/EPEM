package com.maxprint.epson.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.maxprint.epson.databinding.ActivityAddPrinterBinding
import com.maxprint.epson.model.LinkType
import com.maxprint.epson.model.PrinterTarget
import com.maxprint.epson.util.Prefs

/**
 * "Add printer" target declared in printservice.xml.
 *
 * mDNS does not cross subnets and some networks block multicast outright, so a printer
 * reachable only by address still has to be addable by hand.
 */
class AddPrinterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddPrinterBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddPrinterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnSave.setOnClickListener { save() }
        showExisting()
    }

    private fun save() {
        val host = binding.editHost.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter an address", Toast.LENGTH_SHORT).show()
            return
        }
        val port = binding.editPort.text.toString().trim().toIntOrNull() ?: 631
        val name = binding.editName.text.toString().trim().ifEmpty { "Printer at $host" }

        // Port 9100 is the raw JetDirect convention; anything else we treat as IPP.
        val link = if (port == 9100) LinkType.NET_RAW else LinkType.NET_IPP
        val target = PrinterTarget(
            link = link,
            address = "$host:$port",
            detail = if (link == LinkType.NET_IPP) "/ipp/print" else "",
            displayName = name,
            manufacturer = "EPSON"
        )

        prefs.addManual(target)
        Toast.makeText(this, "Saved. It will appear in the printer list.", Toast.LENGTH_SHORT).show()
        showExisting()
        binding.editHost.text.clear()
        binding.editName.text.clear()
    }

    private fun showExisting() {
        val entries = prefs.manualTargets.mapNotNull { PrinterTarget.decode(it) }
        binding.manualList.text = if (entries.isEmpty()) {
            "None yet."
        } else {
            entries.joinToString("\n") { "${it.displayName}  ${it.address}${it.detail}" }
        }
    }
}
