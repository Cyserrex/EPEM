package com.maxprint.epson.pdl

import java.io.OutputStream

/**
 * Writes an ESC/P-R print job.
 *
 * Wire format and command sequence follow Epson's LGPL reference driver
 * `epson-inkjet-printer-escpr` 1.8.8 — see [EscPr] for the citations. The job shape is:
 *
 * ```
 *   startJob()                 EJL preamble, REMOTE1 block, enter ESC/P-R, setq/seti/setj
 *     startPage()              sttp
 *       writeLine() x H        one dsnd per raster line
 *     endPage(more)            endp
 *     ... more pages ...
 *   endJob()                   endj
 * ```
 *
 * Pixels are **always 24-bit RGB**, even for a monochrome job: the reference driver keeps
 * `bpp = 3` and signals mono through the colour mode byte in `setq`, leaving the printer
 * to do the conversion. Sending 8-bit grey instead would be misread as RGB and print as
 * colour noise.
 */
class EscPrWriter(private val out: OutputStream) {

    /** Geometry and quality for one job. All dimensions are dots at [resolution]. */
    data class JobSetup(
        val resolution: EscPr.InputResolution,
        val paperWidthDots: Int,
        val paperHeightDots: Int,
        val printableWidthDots: Int,
        val printableHeightDots: Int,
        val topMarginDots: Int,
        val leftMarginDots: Int,
        val color: Boolean,
        val quality: Int = EscPr.QUALITY_NORMAL,
        val mediaType: Int = EscPr.MEDIA_TYPE_PLAIN,
        /** 0 none, 1 long edge, 2 short edge. */
        val duplex: Int = 0,
        val brightness: Int = 0,
        val contrast: Int = 0,
        val saturation: Int = 0
    )

    /** Bytes per pixel on the wire. Always three: see the class comment. */
    val bytesPerPixel: Int = 3

    private var job: JobSetup? = null

    fun startJob(setup: JobSetup) {
        job = setup

        out.write(EscPr.EXIT_PACKET_MODE)
        out.write(EscPr.INIT_PRINTER)

        out.write(EscPr.ENTER_REMOTE_MODE)
        out.write(EscPr.REMOTE_JS)
        out.write(EscPr.REMOTE_JH)
        out.write(EscPr.remoteHd())
        out.write(EscPr.REMOTE_PP_AUTO)
        out.write(EscPr.remoteQuiet1(EscPr.QUIET_OFF))
        out.write(EscPr.remoteQuiet2(EscPr.QUIET_OFF))
        if (setup.duplex != 0) out.write(EscPr.REMOTE_DP)
        out.write(EscPr.EXIT_REMOTE_MODE)

        out.write(EscPr.ENTER_ESCPR_MODE)

        command(EscPr.CLASS_QUALITY, EscPr.CMD_SET_QUALITY, qualityParams(setup))
        command(EscPr.CLASS_QUALITY, EscPr.CMD_SET_IMAGE, imageParams(setup))
        command(EscPr.CLASS_JOB, EscPr.CMD_SET_JOB, jobParams(setup))
    }

    fun startPage() {
        command(EscPr.CLASS_PAGE, EscPr.CMD_START_PAGE, ByteArray(0))
    }

    /**
     * Sends one raster line of the printable area.
     *
     * @param y     row index within the printable area, 0 at the top
     * @param rgb   pixel data, `pixels * 3` bytes starting at [offset]
     * @param pixels number of pixels in the line; must not exceed the printable width
     */
    fun writeLine(y: Int, rgb: ByteArray, offset: Int, pixels: Int) {
        val setup = job ?: error("startJob() first")
        val width = minOf(pixels, setup.printableWidthDots)
        if (width <= 0) return

        val encoded = EscPr.runLengthEncode(rgb, offset, width, bytesPerPixel)

        val params = ByteArray(EscPr.SEND_DATA_HEADER + encoded.size)
        putShortBe(params, 0, 0)                 // x offset within the printable area
        putShortBe(params, 2, y)                 // y offset
        params[4] = if (encoded.compressed) EscPr.COMPRESSION_RLE.toByte()
        else EscPr.COMPRESSION_NONE.toByte()
        putShortBe(params, 5, encoded.size)      // raster byte count
        System.arraycopy(encoded.bytes, 0, params, EscPr.SEND_DATA_HEADER, encoded.size)

        command(EscPr.CLASS_DATA, EscPr.CMD_SEND_DATA, params)
    }

    fun endPage(morePagesFollow: Boolean) {
        command(
            EscPr.CLASS_PAGE,
            EscPr.CMD_END_PAGE,
            byteArrayOf(if (morePagesFollow) EscPr.PAGE_MORE_FOLLOW.toByte() else EscPr.PAGE_LAST.toByte())
        )
    }

    fun endJob() {
        command(EscPr.CLASS_JOB, EscPr.CMD_END_JOB, ByteArray(0))
        out.flush()
    }

    // --- Parameter builders ------------------------------------------------------

    /** `MakeQualityCmd()`: nine bytes. */
    private fun qualityParams(s: JobSetup): ByteArray {
        val p = ByteArray(9)
        p[0] = s.mediaType.toByte()
        p[1] = s.quality.toByte()
        p[2] = (if (s.color) EscPr.COLOR_MODE_COLOR else EscPr.COLOR_MODE_MONOCHROME).toByte()
        p[3] = s.brightness.toByte()
        p[4] = s.contrast.toByte()
        p[5] = s.saturation.toByte()
        p[6] = EscPr.COLOR_PLANE_FULL_COLOR.toByte()
        putShortBe(p, 7, 0) // palette size, unused for full colour
        return p
    }

    /** Image processing mode: ten zero bytes, the last carrying the binding position. */
    private fun imageParams(s: JobSetup): ByteArray {
        val p = ByteArray(10)
        p[9] = when (s.duplex) {
            1 -> 2 // long edge
            2 -> 1 // short edge
            else -> 0
        }
        return p
    }

    /** `MakeJobCmd()`: twenty-two bytes, every field big endian. */
    private fun jobParams(s: JobSetup): ByteArray {
        val p = ByteArray(22)
        putIntBe(p, 0, s.paperWidthDots)
        putIntBe(p, 4, s.paperHeightDots)
        putShortBe(p, 8, s.topMarginDots)
        putShortBe(p, 10, s.leftMarginDots)
        putIntBe(p, 12, s.printableWidthDots)
        putIntBe(p, 16, s.printableHeightDots)
        p[20] = s.resolution.code.toByte()
        p[21] = EscPr.DIRECTION_BIDIRECTIONAL.toByte()
        return p
    }

    // --- Framing -----------------------------------------------------------------

    private fun command(classChar: Char, name: String, params: ByteArray) {
        require(name.length == 4) { "ESC/P-R command names are four characters" }
        out.write(EscPr.ESC)
        out.write(classChar.code)
        // Command length is little endian; the parameters inside it are big endian.
        out.write(params.size and 0xFF)
        out.write((params.size ushr 8) and 0xFF)
        out.write((params.size ushr 16) and 0xFF)
        out.write((params.size ushr 24) and 0xFF)
        out.write(name.toByteArray(Charsets.US_ASCII))
        if (params.isNotEmpty()) out.write(params)
    }

    private fun putShortBe(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun putIntBe(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
