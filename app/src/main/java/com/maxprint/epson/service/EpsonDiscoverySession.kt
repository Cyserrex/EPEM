package com.maxprint.epson.service

import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession
import com.maxprint.epson.discovery.NetworkDiscovery
import com.maxprint.epson.ipp.IppCapabilities
import com.maxprint.epson.ipp.IppClient
import com.maxprint.epson.model.LinkType
import com.maxprint.epson.model.PrinterCaps
import com.maxprint.epson.model.PrinterTarget
import com.maxprint.epson.transport.Connections
import com.maxprint.epson.usb.UsbDiscovery
import com.maxprint.epson.util.L
import com.maxprint.epson.util.Prefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Feeds printers into the Android print dialog.
 *
 * USB is scanned synchronously (it is a list of attached devices, so it is instant) and
 * the network is watched with mDNS. Capabilities are only fetched once the user actually
 * highlights a printer, because probing every discovered device would be slow and would
 * pop a USB permission dialog for printers the user never chose.
 */
class EpsonDiscoverySession(
    private val service: EpsonPrintService,
    private val prefs: Prefs,
    private val executor: ExecutorService
) : PrinterDiscoverySession() {

    private val known = ConcurrentHashMap<String, PrinterTarget>()
    private val caps = ConcurrentHashMap<String, PrinterCaps>()
    private var network: NetworkDiscovery? = null

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        L.d("discovery started")
        prefs.applyLogLevel()

        refreshUsb()
        refreshManual()

        network = NetworkDiscovery(
            context = service,
            onFound = { target ->
                if (!prefs.onlyEpson || looksEpson(target)) {
                    service.runOnMain { add(target) }
                }
            },
            onLost = { name ->
                service.runOnMain { removeByDisplayName(name) }
            }
        ).also { it.start() }
    }

    override fun onStopPrinterDiscovery() {
        L.d("discovery stopped")
        network?.stop()
        network = null
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        // Scanning USB can open devices, so it never runs on the main thread.
        val ids = printerIds.toList()
        executor.execute {
            val usbNow = UsbDiscovery.scan(service, prefs.onlyEpson)
            val updates = ArrayList<PrinterInfo>()
            for (id in ids) {
                val target = PrinterTarget.decode(id.localId) ?: continue
                // A printer that is no longer attached must be reported as unavailable,
                // otherwise the dialog keeps offering it.
                if (target.isUsb && usbNow.none { it.address == target.address }) {
                    updates += PrinterInfoFactory.unavailable(id, target)
                } else {
                    updates += PrinterInfoFactory.basic(id, target)
                }
            }
            service.runOnMain {
                if (!isDestroyed && updates.isNotEmpty()) addPrinters(updates)
            }
        }
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        val target = PrinterTarget.decode(printerId.localId) ?: return
        L.d("tracking ${target.displayName}")

        executor.execute {
            val resolved = probe(target)
            service.runOnMain {
                if (isDestroyed) return@runOnMain
                caps[printerId.localId] = resolved
                addPrinters(listOf(PrinterInfoFactory.withCapabilities(printerId, target, resolved)))
            }
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit

    override fun onDestroy() {
        network?.stop()
        network = null
    }

    fun refreshUsb() {
        executor.execute {
            val targets = UsbDiscovery.scan(service, prefs.onlyEpson)
            service.runOnMain {
                if (isDestroyed) return@runOnMain
                targets.forEach { add(it) }
            }
        }
    }

    private fun refreshManual() {
        prefs.manualTargets
            .mapNotNull { PrinterTarget.decode(it) }
            .forEach { add(it) }
    }

    private fun add(target: PrinterTarget) {
        val id = service.generatePrinterId(target.encode())
        known[target.encode()] = target
        val info = caps[target.encode()]
            ?.let { PrinterInfoFactory.withCapabilities(id, target, it) }
            ?: PrinterInfoFactory.basic(id, target)
        addPrinters(listOf(info))
    }

    private fun removeByDisplayName(serviceName: String) {
        val gone = known.values.filter { it.displayName == serviceName }
        if (gone.isEmpty()) return
        removePrinters(gone.map { service.generatePrinterId(it.encode()) })
        gone.forEach { known.remove(it.encode()) }
    }

    private fun looksEpson(target: PrinterTarget): Boolean =
        listOfNotNull(target.manufacturer, target.model, target.displayName)
            .any { it.contains("epson", ignoreCase = true) }

    /**
     * Ask the printer what it can do. IPP printers answer for themselves; raw ones cannot
     * be interrogated, so they get the ESC/P-R defaults.
     */
    private fun probe(target: PrinterTarget): PrinterCaps {
        if (target.link == LinkType.NET_RAW || target.link == LinkType.USB_RAW) {
            return PrinterCaps.escprDefaults(target.model ?: target.displayName)
        }
        return try {
            Connections.openIppChannel(service, target).use { channel ->
                val client = IppClient(channel, target.ippUri(), target.resourcePath)
                IppCapabilities.from(client.getPrinterAttributes())
            }
        } catch (t: Throwable) {
            L.w("capability probe failed for ${target.displayName}", t)
            // An IPP-USB printer we cannot reach yet (no permission, cable busy) still has
            // to appear with something usable, so fall back rather than hiding it.
            PrinterCaps.escprDefaults(target.model ?: target.displayName)
        }
    }
}
