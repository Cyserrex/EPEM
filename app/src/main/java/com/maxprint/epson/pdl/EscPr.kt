package com.maxprint.epson.pdl

import java.io.ByteArrayOutputStream

/**
 * ESC/P-R wire format constants and its run length encoder.
 *
 * These are not guesswork: they were read off Epson's own LGPL reference implementation,
 * `epson-inkjet-printer-escpr` 1.8.8 (`lib/epson-escpr-api.c`), which is the driver that
 * supports the EcoTank L-series among many others. Byte layouts below cite the function
 * in that file that produces them.
 *
 * ## Command framing
 *
 * ```
 *   0x1B  <class byte>  <parameter length: 4 bytes LITTLE endian>  <name: 4 ASCII>  <params>
 * ```
 *
 * Ten bytes of header (`ESCPR_HEADER_LENGTH`). Note the mixed endianness that makes this
 * format easy to get wrong: the *command length* is little endian, while every *parameter*
 * inside the payload is big endian.
 */
object EscPr {

    // --- Framing -----------------------------------------------------------------

    const val ESC = 0x1B

    /** `ESC q ... "setq"` — print quality. Nine parameter bytes. */
    const val CLASS_QUALITY = 'q'

    /** `ESC j ... "setj"` — job geometry. Twenty-two parameter bytes. */
    const val CLASS_JOB = 'j'

    /** `ESC p ... "sttp"` / `"endp"` — page boundaries. */
    const val CLASS_PAGE = 'p'

    /** `ESC d ... "dsnd"` — one raster line. */
    const val CLASS_DATA = 'd'

    const val CMD_SET_QUALITY = "setq"
    const val CMD_SET_IMAGE = "seti"
    const val CMD_SET_JOB = "setj"
    const val CMD_START_PAGE = "sttp"
    const val CMD_END_PAGE = "endp"
    const val CMD_END_JOB = "endj"
    const val CMD_SEND_DATA = "dsnd"

    /** Bytes of `dsnd` parameters that precede the pixels (`ESCPR_SEND_DATA_LENGTH`). */
    const val SEND_DATA_HEADER = 7

    // --- Escape sequences --------------------------------------------------------

    /** Three NULs, then EJL, which drops the printer out of packet mode. */
    val EXIT_PACKET_MODE: ByteArray = byteArrayOf(
        0x00, 0x00, 0x00,
        0x1B, 0x01
    ) + "@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII)

    /** `ESC @` */
    val INIT_PRINTER: ByteArray = byteArrayOf(0x1B, 0x40)

    /** `ESC ( R 08 00 00 "REMOTE1"` */
    val ENTER_REMOTE_MODE: ByteArray =
        byteArrayOf(0x1B, 0x28, 0x52, 0x08, 0x00, 0x00) + "REMOTE1".toByteArray(Charsets.US_ASCII)

    /** `ESC 00 00 00` */
    val EXIT_REMOTE_MODE: ByteArray = byteArrayOf(0x1B, 0x00, 0x00, 0x00)

    /** `ESC ( R 06 00 00 "ESCPR"` — switches the interpreter into ESC/P-R. */
    val ENTER_ESCPR_MODE: ByteArray =
        byteArrayOf(0x1B, 0x28, 0x52, 0x06, 0x00, 0x00) + "ESCPR".toByteArray(Charsets.US_ASCII)

    // Remote mode commands sent inside the REMOTE1 block, in the driver's own order.
    val REMOTE_JS: ByteArray = byteArrayOf(0x4A, 0x53, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00)

    val REMOTE_JH: ByteArray = byteArrayOf(
        0x4A, 0x48, 0x0E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    ) + "ESCPRLib".toByteArray(Charsets.US_ASCII)

    /** `HD` — host platform. 0x04 is the driver's value for Linux, which Android is. */
    fun remoteHd(platform: Int = PLATFORM_LINUX): ByteArray =
        byteArrayOf(0x48, 0x44, 0x03, 0x00, 0x00, 0x03, platform.toByte())

    /** `PP` — paper source. 0x01 0xFF asks the printer to choose. */
    val REMOTE_PP_AUTO: ByteArray =
        byteArrayOf(0x50, 0x50, 0x03, 0x00, 0x00, 0x01, 0xFF.toByte())

    /** `US` — quiet mode, two variants the driver always sends as a pair. */
    fun remoteQuiet1(mode: Int) = byteArrayOf(0x55, 0x53, 0x03, 0x00, 0x00, 0x05, mode.toByte())

    fun remoteQuiet2(mode: Int) = byteArrayOf(0x55, 0x53, 0x03, 0x00, 0x00, 0x08, mode.toByte())

    /** `DP` — enable the duplexer. */
    val REMOTE_DP: ByteArray = byteArrayOf(0x44, 0x50, 0x02, 0x00, 0x00, 0x02)

    const val PLATFORM_LINUX = 0x04
    const val QUIET_OFF = 0x00
    const val QUIET_PRINTER_SETTING = 0xFF

    // --- Parameter values --------------------------------------------------------

    /** `setq` byte 2, and the reason a mono job still ships 24-bit RGB pixels. */
    const val COLOR_MODE_COLOR = 0
    const val COLOR_MODE_MONOCHROME = 1

    /** `setq` byte 6. Full colour means three bytes per pixel. */
    const val COLOR_PLANE_FULL_COLOR = 0

    /** `setq` byte 1. */
    const val QUALITY_DRAFT = 0
    const val QUALITY_NORMAL = 1
    const val QUALITY_HIGH = 2

    /** `setq` byte 0. */
    const val MEDIA_TYPE_PLAIN = 0

    /** `setj` byte 21. Bidirectional is the fast default. */
    const val DIRECTION_BIDIRECTIONAL = 0
    const val DIRECTION_UNIDIRECTIONAL = 1

    /** `dsnd` compression byte. */
    const val COMPRESSION_NONE = 0
    const val COMPRESSION_RLE = 1

    /** `endp` parameter. */
    const val PAGE_LAST = 0
    const val PAGE_MORE_FOLLOW = 1

    /**
     * Input resolutions ESC/P-R accepts, with the code that goes into `setj` byte 20.
     *
     * The 360 and 300 families have different paper geometry tables in the driver, hence
     * the separate [marginDots] baselines: 42 dots at 360 dpi and 35 at 300 dpi, both of
     * which work out to the same 3 mm hardware margin.
     */
    enum class InputResolution(val dpi: Int, val code: Int, val marginDots: Int) {
        DPI_360(360, 0x00, 42),
        DPI_720(720, 0x01, 84),
        DPI_300(300, 0x02, 35),
        DPI_600(600, 0x03, 70);

        companion object {
            /**
             * 360 dpi is the only resolution every ESC/P-R printer is guaranteed to accept
             * (the driver seeds `supportedMedia.resolution` with it before parsing the
             * printer's reply), so anything we cannot match exactly falls back to it.
             */
            fun nearest(dpi: Int): InputResolution = when {
                dpi >= 660 -> DPI_720
                dpi >= 480 -> DPI_600
                dpi >= 330 -> DPI_360
                dpi >= 150 -> DPI_300
                else -> DPI_360
            }
        }
    }

    // --- Page geometry -----------------------------------------------------------

    /** Paper and printable area for one page, in dots at the job's input resolution. */
    data class PageGeometry(
        val paperWidthDots: Int,
        val paperHeightDots: Int,
        val printableWidthDots: Int,
        val printableHeightDots: Int,
        val marginDots: Int
    ) {
        val isUsable: Boolean get() = printableWidthDots > 0 && printableHeightDots > 0
    }

    /**
     * Converts a media size in mils (thousandths of an inch, which is how the Android print
     * framework measures paper) into ESC/P-R dots.
     *
     * Truncating rather than rounding is deliberate: it reproduces the driver's own media
     * table exactly, e.g. A4 at 360 dpi comes out 2976 x 4209 dots with a 2892 x 4125
     * printable area, which is the `EPS_MSID_A4` row in `epsMediaSize[]`.
     */
    fun geometryFor(widthMils: Int, heightMils: Int, resolution: InputResolution): PageGeometry {
        val w = (widthMils.toLong() * resolution.dpi / 1000L).toInt()
        val h = (heightMils.toLong() * resolution.dpi / 1000L).toInt()
        val m = resolution.marginDots
        return PageGeometry(w, h, w - 2 * m, h - 2 * m, m)
    }

    // --- Run length encoding -----------------------------------------------------

    /** Result of [runLengthEncode]: the bytes, and whether compression was worth it. */
    class Encoded(val bytes: ByteArray, val size: Int, val compressed: Boolean)

    /**
     * Encodes one raster line, operating on whole pixels of [bytesPerPixel] bytes.
     *
     * Count byte semantics, per `RunLengthEncode()` in the reference driver:
     *  * `0x00..0x7F` — a literal run of `count + 1` pixels follows
     *  * `0x80..0xFF` — one pixel follows, repeated `257 - count` times (so 2..129)
     *
     * If the encoded form would come out larger than the raw line, the driver abandons
     * compression and sends the line verbatim; we do the same, because the printer picks
     * its interpretation from the `dsnd` compression byte either way.
     */
    fun runLengthEncode(
        src: ByteArray,
        srcOffset: Int,
        pixels: Int,
        bytesPerPixel: Int
    ): Encoded {
        val rawSize = pixels * bytesPerPixel
        val out = ByteArrayOutputStream(rawSize + 16)
        var i = 0

        while (i < pixels) {
            if (i + 1 < pixels && pixelEquals(src, srcOffset, i, i + 1, bytesPerPixel)) {
                var run = 2
                while (i + run < pixels && run < 0x81 &&
                    pixelEquals(src, srcOffset, i + run - 1, i + run, bytesPerPixel)
                ) run++

                if (out.size() + 1 + bytesPerPixel > rawSize) break
                out.write(257 - run)
                out.write(src, srcOffset + i * bytesPerPixel, bytesPerPixel)
                i += run
            } else {
                var run = 1
                while (i + run + 1 < pixels && run < 0x80 &&
                    !pixelEquals(src, srcOffset, i + run, i + run + 1, bytesPerPixel)
                ) run++

                if (out.size() + 1 + run * bytesPerPixel > rawSize) break
                out.write(run - 1)
                out.write(src, srcOffset + i * bytesPerPixel, run * bytesPerPixel)
                i += run
            }
        }

        return if (i >= pixels) {
            Encoded(out.toByteArray(), out.size(), true)
        } else {
            Encoded(src.copyOfRange(srcOffset, srcOffset + rawSize), rawSize, false)
        }
    }

    private fun pixelEquals(b: ByteArray, base: Int, a: Int, c: Int, bpp: Int): Boolean {
        val oa = base + a * bpp
        val oc = base + c * bpp
        for (k in 0 until bpp) if (b[oa + k] != b[oc + k]) return false
        return true
    }
}
