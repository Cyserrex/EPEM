package com.maxprint.epson.transport

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.maxprint.epson.model.LinkType
import com.maxprint.epson.model.PrinterTarget
import com.maxprint.epson.usb.UsbPrinters
import com.maxprint.epson.usb.UsbSession
import com.maxprint.epson.util.L
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A byte pipe to a printer that speaks a page description language directly. */
interface RawLink : Closeable {
    val output: OutputStream
    val input: InputStream?

    /** Called once the whole job has been written. */
    fun finish()
}

/** Opens transports from a [PrinterTarget]. The one place USB and TCP details live. */
object Connections {

    @Throws(IOException::class)
    fun findUsbDevice(context: Context, target: PrinterTarget): UsbDevice {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.firstOrNull {
            PrinterTarget.usbKey(it.vendorId, it.productId) == target.address &&
                (target.detail.isEmpty() || serialOf(it, manager) == target.detail)
        }
        // Fall back to vid:pid only — the serial is not readable without permission on
        // some API levels, so a match on the pair is good enough in practice.
            ?: manager.deviceList.values.firstOrNull {
                PrinterTarget.usbKey(it.vendorId, it.productId) == target.address
            }
            ?: throw IOException("USB printer ${target.address} is not connected")
    }

    fun serialOf(device: UsbDevice, manager: UsbManager): String = try {
        if (manager.hasPermission(device)) device.serialNumber.orEmpty() else ""
    } catch (t: Throwable) {
        ""
    }

    @Throws(IOException::class)
    fun openUsbSession(context: Context, target: PrinterTarget): UsbSession {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findUsbDevice(context, target)
        if (!manager.hasPermission(device)) {
            throw UsbPermissionNeededException(device)
        }
        return UsbPrinters.open(manager, device, wantIppUsb = target.link == LinkType.USB_IPP)
    }

    /** An HTTP channel for IPP, over the network or over the cable. */
    @Throws(IOException::class)
    fun openIppChannel(context: Context, target: PrinterTarget): HttpChannel = when (target.link) {
        LinkType.USB_IPP -> UsbIppChannel(openUsbSession(context, target))
        LinkType.NET_IPP -> NetworkHttpChannel(
            host = target.host,
            port = target.port,
            useTls = target.port == 443
        )

        else -> throw IOException("${target.link} is not an IPP transport")
    }

    /** A raw PDL pipe, over the network or over the cable. */
    @Throws(IOException::class)
    fun openRawLink(context: Context, target: PrinterTarget): RawLink = when (target.link) {
        LinkType.USB_RAW, LinkType.USB_IPP -> {
            // Even an IPP-USB capable device exposes a raw interface; ESC/P-R goes there.
            val session = openUsbSession(context, target)
            object : RawLink {
                override val output: OutputStream = session.streams.output
                override val input: InputStream? =
                    if (session.printerInterface.isBidirectional) session.streams.input else null

                override fun finish() {
                    output.flush()
                    session.streams.sendZeroLengthPacket()
                }

                override fun close() = session.close()
            }
        }

        LinkType.NET_RAW, LinkType.NET_IPP -> {
            val port = if (target.link == LinkType.NET_RAW) target.port else 9100
            val socket = Socket()
            socket.connect(InetSocketAddress(target.host, port), 8_000)
            socket.soTimeout = 60_000
            socket.tcpNoDelay = true
            object : RawLink {
                override val output: OutputStream = socket.getOutputStream()
                override val input: InputStream = socket.getInputStream()

                override fun finish() {
                    output.flush()
                    try {
                        socket.shutdownOutput()
                    } catch (t: Throwable) {
                        L.d("shutdownOutput: ${t.message}")
                    }
                }

                override fun close() = socket.close()
            }
        }
    }
}

/** Raised when a USB printer is reachable but the user has not granted access yet. */
class UsbPermissionNeededException(val device: UsbDevice) :
    IOException("USB permission required for ${device.deviceName}")
