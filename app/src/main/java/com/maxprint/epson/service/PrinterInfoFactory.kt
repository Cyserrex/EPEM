package com.maxprint.epson.service

import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import com.maxprint.epson.model.MediaSizes
import com.maxprint.epson.model.PrinterCaps
import com.maxprint.epson.model.PrinterState
import com.maxprint.epson.model.PrinterTarget

/** Builds the [PrinterInfo] objects the Android print dialog renders. */
object PrinterInfoFactory {

    fun basic(printerId: PrinterId, target: PrinterTarget): PrinterInfo =
        PrinterInfo.Builder(printerId, target.displayName, PrinterInfo.STATUS_IDLE)
            .setDescription(describe(target))
            .build()

    fun withCapabilities(
        printerId: PrinterId,
        target: PrinterTarget,
        caps: PrinterCaps
    ): PrinterInfo {
        val builder = PrinterCapabilitiesInfo.Builder(printerId)

        // Media sizes. The framework requires at least one and exactly one default.
        val sizes = caps.mediaSupported
            .mapNotNull { pwg -> MediaSizes.toAndroid(pwg)?.let { pwg to it } }
            .distinctBy { it.second.id }
            .ifEmpty { listOf("iso_a4_210x297mm" to PrintAttributes.MediaSize.ISO_A4) }

        // Exactly one entry must be flagged as the default, so decide which index that is
        // before adding anything -- adding the same size twice would duplicate it in the UI.
        val defaultPwg = caps.defaultMedia
        val mediaDefaultIndex = sizes
            .indexOfFirst { defaultPwg != null && it.first.equals(defaultPwg, ignoreCase = true) }
            .let { if (it >= 0) it else 0 }
        sizes.forEachIndexed { i, (_, size) -> builder.addMediaSize(size, i == mediaDefaultIndex) }

        // Resolutions.
        val resolutions = caps.resolutions.distinct().ifEmpty { listOf(300 to 300) }
        val resDefaultIndex = caps.defaultResolution
            ?.let { resolutions.indexOf(it) }
            ?.takeIf { it >= 0 }
            ?: 0
        resolutions.forEachIndexed { i, (x, y) ->
            builder.addResolution(
                PrintAttributes.Resolution("${x}x$y", "$x x $y dpi", x, y),
                i == resDefaultIndex
            )
        }

        // Colour.
        val colorModes = if (caps.supportsColor) {
            PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME
        } else {
            PrintAttributes.COLOR_MODE_MONOCHROME
        }
        val defaultColor = if (caps.supportsColor) {
            PrintAttributes.COLOR_MODE_COLOR
        } else {
            PrintAttributes.COLOR_MODE_MONOCHROME
        }
        builder.setColorModes(colorModes, defaultColor)

        // Duplex, where the platform exposes it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            var modes = PrintAttributes.DUPLEX_MODE_NONE
            if (caps.supportsDuplex) {
                modes = modes or PrintAttributes.DUPLEX_MODE_LONG_EDGE or
                    PrintAttributes.DUPLEX_MODE_SHORT_EDGE
            }
            builder.setDuplexModes(modes, PrintAttributes.DUPLEX_MODE_NONE)
        }

        builder.setMinMargins(
            PrintAttributes.Margins(
                caps.marginsMils.left,
                caps.marginsMils.top,
                caps.marginsMils.right,
                caps.marginsMils.bottom
            )
        )

        val status = when (caps.state) {
            PrinterState.BUSY -> PrinterInfo.STATUS_BUSY
            PrinterState.STOPPED, PrinterState.UNAVAILABLE -> PrinterInfo.STATUS_UNAVAILABLE
            else -> PrinterInfo.STATUS_IDLE
        }

        return PrinterInfo.Builder(printerId, target.displayName, status)
            .setDescription(caps.makeAndModel ?: describe(target))
            .setCapabilities(builder.build())
            .build()
    }

    fun unavailable(printerId: PrinterId, target: PrinterTarget): PrinterInfo =
        PrinterInfo.Builder(printerId, target.displayName, PrinterInfo.STATUS_UNAVAILABLE)
            .setDescription(describe(target))
            .build()

    private fun describe(target: PrinterTarget): String = when (target.link) {
        com.maxprint.epson.model.LinkType.USB_IPP -> "USB OTG, IPP"
        com.maxprint.epson.model.LinkType.USB_RAW -> "USB OTG, ESC/P-R"
        com.maxprint.epson.model.LinkType.NET_IPP -> "Wi-Fi, IPP, ${target.host}"
        com.maxprint.epson.model.LinkType.NET_RAW -> "Wi-Fi, port ${target.port}, ${target.host}"
    }
}
