package com.maxprint.epson.pdl

import java.io.OutputStream

/**
 * PWG Raster Format writer (PWG 5102.4).
 *
 * A PWG raster stream is the magic word `RaS2` followed, per page, by a fixed 1796 byte
 * header and then the page's rows, run-length encoded. Every IPP Everywhere printer must
 * accept it, which makes it the safe universal fallback when a printer will not take PDF.
 *
 * Layout of the page header is the serialised CUPS `cups_page_header2_t`, big endian.
 */
class PwgRasterWriter(private val out: OutputStream) {

    companion object {
        private const val HEADER_SIZE = 1796

        // Byte offsets inside the page header.
        private const val OFF_MEDIA_CLASS = 0
        private const val OFF_MEDIA_COLOR = 64
        private const val OFF_MEDIA_TYPE = 128
        private const val OFF_OUTPUT_TYPE = 192
        private const val OFF_CUT_MEDIA = 268
        private const val OFF_DUPLEX = 272
        private const val OFF_HW_RESOLUTION = 276
        private const val OFF_INSERT_SHEET = 300
        private const val OFF_JOG = 304
        private const val OFF_LEADING_EDGE = 308
        private const val OFF_MEDIA_POSITION = 324
        private const val OFF_MEDIA_WEIGHT = 328
        private const val OFF_NUM_COPIES = 340
        private const val OFF_ORIENTATION = 344
        private const val OFF_PAGE_SIZE = 352
        private const val OFF_TUMBLE = 368
        private const val OFF_CUPS_WIDTH = 372
        private const val OFF_CUPS_HEIGHT = 376
        private const val OFF_BITS_PER_COLOR = 384
        private const val OFF_BITS_PER_PIXEL = 388
        private const val OFF_BYTES_PER_LINE = 392
        private const val OFF_COLOR_ORDER = 396
        private const val OFF_COLOR_SPACE = 400
        private const val OFF_NUM_COLORS = 420
        private const val OFF_CUPS_INTEGER = 452
        private const val OFF_PAGE_SIZE_NAME = 1732

        // cupsColorSpace values used by PWG raster.
        const val COLORSPACE_SGRAY = 18
        const val COLORSPACE_SRGB = 19

        /** Maximum pixels one RLE run can cover. */
        private const val MAX_RUN = 128

        /** Maximum times one line can be repeated in a single line group. */
        private const val MAX_LINE_REPEAT = 256
    }

    data class PageSetup(
        val widthPx: Int,
        val heightPx: Int,
        val dpiX: Int,
        val dpiY: Int,
        /** Page box in points, used by the printer to place the image. */
        val pageWidthPt: Int,
        val pageHeightPt: Int,
        val color: Boolean,
        val pwgMediaName: String,
        val copies: Int = 1,
        /** 0 one-sided, 1 two-sided-long-edge, 2 two-sided-short-edge. */
        val duplexMode: Int = 0,
        val mediaType: String = "stationery",
        val mediaSource: String = "auto"
    ) {
        val bitsPerPixel: Int get() = if (color) 24 else 8
        val bytesPerPixel: Int get() = if (color) 3 else 1
        val bytesPerLine: Int get() = widthPx * bytesPerPixel
    }

    private var setup: PageSetup? = null
    private var pendingLine: ByteArray? = null
    private var pendingRepeat = 0
    private var linesWritten = 0

    fun writeStreamHeader() {
        out.write("RaS2".toByteArray(Charsets.US_ASCII))
    }

    fun startPage(s: PageSetup) {
        setup = s
        pendingLine = null
        pendingRepeat = 0
        linesWritten = 0

        val h = ByteArray(HEADER_SIZE)
        putString(h, OFF_MEDIA_CLASS, "PwgRaster")
        putString(h, OFF_MEDIA_COLOR, "")
        putString(h, OFF_MEDIA_TYPE, s.mediaType)
        putString(h, OFF_OUTPUT_TYPE, if (s.color) "Photo" else "Text")
        putString(h, OFF_PAGE_SIZE_NAME, s.pwgMediaName)

        putInt(h, OFF_CUT_MEDIA, 0)
        putInt(h, OFF_DUPLEX, if (s.duplexMode != 0) 1 else 0)
        putInt(h, OFF_HW_RESOLUTION, s.dpiX)
        putInt(h, OFF_HW_RESOLUTION + 4, s.dpiY)
        putInt(h, OFF_INSERT_SHEET, 0)
        putInt(h, OFF_JOG, 0)
        putInt(h, OFF_LEADING_EDGE, 0)
        putInt(h, OFF_MEDIA_POSITION, 0)
        putInt(h, OFF_MEDIA_WEIGHT, 0)
        putInt(h, OFF_NUM_COPIES, s.copies)
        putInt(h, OFF_ORIENTATION, 0)
        putInt(h, OFF_PAGE_SIZE, s.pageWidthPt)
        putInt(h, OFF_PAGE_SIZE + 4, s.pageHeightPt)
        putInt(h, OFF_TUMBLE, if (s.duplexMode == 2) 1 else 0)
        putInt(h, OFF_CUPS_WIDTH, s.widthPx)
        putInt(h, OFF_CUPS_HEIGHT, s.heightPx)
        putInt(h, OFF_BITS_PER_COLOR, 8)
        putInt(h, OFF_BITS_PER_PIXEL, s.bitsPerPixel)
        putInt(h, OFF_BYTES_PER_LINE, s.bytesPerLine)
        putInt(h, OFF_COLOR_ORDER, 0) // chunky
        putInt(h, OFF_COLOR_SPACE, if (s.color) COLORSPACE_SRGB else COLORSPACE_SGRAY)
        putInt(h, OFF_NUM_COLORS, if (s.color) 3 else 1)

        // cupsInteger[0..5] carry the PWG specific fields.
        putInt(h, OFF_CUPS_INTEGER + 0, 1)            // CrossFeedTransform
        putInt(h, OFF_CUPS_INTEGER + 4, 1)            // FeedTransform
        putInt(h, OFF_CUPS_INTEGER + 8, 0)            // ImageBoxLeft
        putInt(h, OFF_CUPS_INTEGER + 12, 0)           // ImageBoxTop
        putInt(h, OFF_CUPS_INTEGER + 16, s.widthPx)   // ImageBoxRight
        putInt(h, OFF_CUPS_INTEGER + 20, s.heightPx)  // ImageBoxBottom
        putInt(h, OFF_CUPS_INTEGER + 28, 4)           // PrintQuality: 4 = normal

        out.write(h)
    }

    /** Feed one raster line, already in the page's colour space. */
    fun writeLine(line: ByteArray) {
        val s = setup ?: error("startPage() first")
        require(line.size >= s.bytesPerLine) { "short raster line" }

        val prev = pendingLine
        if (prev != null && pendingRepeat < MAX_LINE_REPEAT &&
            regionEquals(prev, line, s.bytesPerLine)
        ) {
            pendingRepeat++
            return
        }
        flushPendingLine()
        pendingLine = line.copyOf(s.bytesPerLine)
        pendingRepeat = 1
    }

    fun endPage() {
        flushPendingLine()
        val s = setup ?: return
        // Pad short pages with blank lines so the printer does not wait for more data.
        if (linesWritten < s.heightPx) {
            val blank = ByteArray(s.bytesPerLine) { 0xFF.toByte() }
            while (linesWritten < s.heightPx) {
                val remaining = s.heightPx - linesWritten
                emitLine(blank, minOf(remaining, MAX_LINE_REPEAT))
            }
        }
        setup = null
    }

    fun flush() = out.flush()

    private fun flushPendingLine() {
        val line = pendingLine ?: return
        emitLine(line, pendingRepeat)
        pendingLine = null
        pendingRepeat = 0
    }

    private fun emitLine(line: ByteArray, repeat: Int) {
        val s = setup ?: return
        val bpp = s.bytesPerPixel
        val width = s.widthPx

        out.write(repeat - 1) // line repeat count, biased by one

        var x = 0
        while (x < width) {
            val runLength = countRepeat(line, x, width, bpp)
            val literal = if (runLength > 1) 0 else countLiteral(line, x, width, bpp)
            // A literal run of one pixel has no encoding: 257-1 is 256, which does not fit
            // in the count byte. Single pixels go out as a repeat of one instead.
            if (runLength > 1 || literal < 2) {
                val repeats = maxOf(runLength, 1)
                out.write(repeats - 1) // 0..127 means "this pixel, repeated count+1 times"
                out.write(line, x * bpp, bpp)
                x += repeats
            } else {
                out.write(257 - literal) // 129..255 means "literal run of 257-count pixels"
                out.write(line, x * bpp, literal * bpp)
                x += literal
            }
        }
        linesWritten += repeat
    }

    private fun countRepeat(line: ByteArray, start: Int, width: Int, bpp: Int): Int {
        var n = 1
        while (start + n < width && n < MAX_RUN && pixelEquals(line, start, start + n, bpp)) n++
        return n
    }

    private fun countLiteral(line: ByteArray, start: Int, width: Int, bpp: Int): Int {
        var n = 1
        while (start + n < width && n < MAX_RUN) {
            // Stop before a run of three or more identical pixels; encoding those as a
            // repeat is always smaller than continuing the literal.
            if (start + n + 1 < width &&
                pixelEquals(line, start + n, start + n + 1, bpp) &&
                start + n + 2 < width &&
                pixelEquals(line, start + n, start + n + 2, bpp)
            ) break
            n++
        }
        return n
    }

    private fun pixelEquals(line: ByteArray, a: Int, b: Int, bpp: Int): Boolean {
        val oa = a * bpp
        val ob = b * bpp
        for (i in 0 until bpp) if (line[oa + i] != line[ob + i]) return false
        return true
    }

    private fun regionEquals(a: ByteArray, b: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (a[i] != b[i]) return false
        return true
    }

    private fun putInt(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v ushr 24).toByte()
        buf[off + 1] = (v ushr 16).toByte()
        buf[off + 2] = (v ushr 8).toByte()
        buf[off + 3] = v.toByte()
    }

    private fun putString(buf: ByteArray, off: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val n = minOf(bytes.size, 63)
        System.arraycopy(bytes, 0, buf, off, n)
        buf[off + n] = 0
    }
}
