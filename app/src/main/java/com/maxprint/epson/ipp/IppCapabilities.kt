package com.maxprint.epson.ipp

import com.maxprint.epson.model.PrinterCaps
import com.maxprint.epson.model.PrinterState

/** Turns a Get-Printer-Attributes response into our transport-agnostic capability model. */
object IppCapabilities {

    fun from(reply: IppResponse): PrinterCaps {
        val formats = reply.strings("document-format-supported")
            .ifEmpty { listOfNotNull(reply.string("document-format-default")) }

        val media = reply.strings("media-supported")
            .ifEmpty { reply.strings("media-ready") }
            .ifEmpty { listOf("iso_a4_210x297mm", "na_letter_8.5x11in") }

        val resolutions = reply["printer-resolution-supported"]?.resolutions()
            ?.map { it.dpiX to it.dpiY }
            ?.distinct()
            ?.ifEmpty { null }
            ?: listOf(300 to 300, 600 to 600)

        val rasterResolutions = reply["pwg-raster-document-resolution-supported"]?.resolutions()
            ?.map { it.dpiX to it.dpiY }
            ?.distinct()
            .orEmpty()

        val colorModes = reply.strings("print-color-mode-supported")
            .ifEmpty { listOf("color", "monochrome") }

        return PrinterCaps(
            documentFormats = formats.ifEmpty { listOf("application/octet-stream") },
            mediaSupported = media,
            defaultMedia = reply.string("media-default"),
            resolutions = resolutions,
            defaultResolution = reply["printer-resolution-default"]?.resolutions()
                ?.firstOrNull()?.let { it.dpiX to it.dpiY },
            colorModes = colorModes,
            sidesSupported = reply.strings("sides-supported").ifEmpty { listOf("one-sided") },
            rasterTypes = reply.strings("pwg-raster-document-type-supported")
                .ifEmpty { listOf("srgb_8", "sgray_8") },
            rasterResolutions = rasterResolutions,
            state = mapState(reply.int("printer-state"), reply["printer-is-accepting-jobs"]?.firstBool()),
            stateReasons = reply.strings("printer-state-reasons"),
            makeAndModel = reply.string("printer-make-and-model") ?: reply.string("printer-name"),
            marginsMils = margins(reply)
        )
    }

    /** `printer-state`: 3 idle, 4 processing, 5 stopped. */
    private fun mapState(state: Int?, accepting: Boolean?): PrinterState = when {
        accepting == false -> PrinterState.STOPPED
        state == 4 -> PrinterState.BUSY
        state == 5 -> PrinterState.STOPPED
        state == 3 -> PrinterState.IDLE
        else -> PrinterState.IDLE
    }

    /**
     * IPP reports margins in hundredths of a millimetre; the Android print framework wants
     * mils (thousandths of an inch).
     */
    private fun margins(reply: IppResponse): PrinterCaps.Margins {
        fun mils(name: String, fallback: Int): Int {
            val hundredthsMm = reply[name]?.values
                ?.filterIsInstance<IppValue.Num>()
                ?.minByOrNull { it.value }
                ?.value ?: return fallback
            return (hundredthsMm / 100.0 / 25.4 * 1000).toInt()
        }
        return PrinterCaps.Margins(
            left = mils("media-left-margin-supported", 120),
            top = mils("media-top-margin-supported", 120),
            right = mils("media-right-margin-supported", 120),
            bottom = mils("media-bottom-margin-supported", 120)
        )
    }
}
