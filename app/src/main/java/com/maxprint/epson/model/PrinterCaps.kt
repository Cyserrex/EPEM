package com.maxprint.epson.model

/**
 * What a printer told us it can do. Populated from IPP Get-Printer-Attributes when the
 * printer speaks IPP, otherwise filled with conservative Epson defaults.
 */
data class PrinterCaps(
    val documentFormats: List<String> = listOf("application/octet-stream"),
    /** PWG media keywords, e.g. `iso_a4_210x297mm`. */
    val mediaSupported: List<String> = listOf("iso_a4_210x297mm", "na_letter_8.5x11in"),
    val defaultMedia: String? = null,
    /** Pairs of (x dpi, y dpi). */
    val resolutions: List<Pair<Int, Int>> = listOf(300 to 300, 600 to 600),
    val defaultResolution: Pair<Int, Int>? = null,
    val colorModes: List<String> = listOf("color", "monochrome"),
    val sidesSupported: List<String> = listOf("one-sided"),
    /** `pwg-raster-document-type-supported`, e.g. `srgb_8`, `sgray_8`. */
    val rasterTypes: List<String> = listOf("srgb_8", "sgray_8"),
    val rasterResolutions: List<Pair<Int, Int>> = emptyList(),
    val state: PrinterState = PrinterState.IDLE,
    val stateReasons: List<String> = emptyList(),
    val makeAndModel: String? = null,
    /** Hardware margins in hundredths of a millimetre, as the print framework wants them. */
    val marginsMils: Margins = Margins(120, 120, 120, 120)
) {
    val supportsPdf: Boolean
        get() = documentFormats.any { it.equals("application/pdf", true) }

    val supportsPwgRaster: Boolean
        get() = documentFormats.any { it.equals("image/pwg-raster", true) }

    val supportsUrf: Boolean
        get() = documentFormats.any { it.equals("image/urf", true) }

    val supportsColor: Boolean get() = colorModes.any { it.startsWith("color") || it == "auto" }

    val supportsDuplex: Boolean get() = sidesSupported.any { it.startsWith("two-sided") }

    data class Margins(val left: Int, val top: Int, val right: Int, val bottom: Int)

    companion object {
        /** Fallback used for raw (ESC/P-R) printers that cannot be interrogated. */
        fun escprDefaults(name: String?) = PrinterCaps(
            documentFormats = listOf("application/vnd.epson.escpr"),
            mediaSupported = listOf(
                "iso_a4_210x297mm",
                "na_letter_8.5x11in",
                "iso_a5_148x210mm",
                "iso_a6_105x148mm",
                "na_legal_8.5x14in",
                "jpn_hagaki_100x148mm",
                "na_index-4x6_4x6in"
            ),
            defaultMedia = "iso_a4_210x297mm",
            resolutions = listOf(360 to 360, 720 to 720),
            defaultResolution = 360 to 360,
            colorModes = listOf("color", "monochrome"),
            makeAndModel = name
        )
    }
}

enum class PrinterState { IDLE, BUSY, STOPPED, UNAVAILABLE }
