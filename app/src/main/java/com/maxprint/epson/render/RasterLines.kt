package com.maxprint.epson.render

import android.graphics.Bitmap

/**
 * Converts an ARGB band into the packed byte layout both PWG Raster and ESC/P-R expect:
 * chunky 24-bit RGB, or 8-bit grey when the job is monochrome.
 */
class RasterLines(private val width: Int, private val color: Boolean) {

    val bytesPerPixel: Int = if (color) 3 else 1
    val bytesPerLine: Int = width * bytesPerPixel

    private val pixelRow = IntArray(width)
    private val lineBuf = ByteArray(bytesPerLine)

    /** Extracts one row of [bitmap] (band-relative) into a reusable byte buffer. */
    fun row(bitmap: Bitmap, y: Int): ByteArray {
        bitmap.getPixels(pixelRow, 0, width, 0, y, width, 1)
        pack(pixelRow, lineBuf)
        return lineBuf
    }

    /** Extracts a whole band into one contiguous buffer, top row first. */
    fun band(bitmap: Bitmap): ByteArray {
        val rows = bitmap.height
        val out = ByteArray(rows * bytesPerLine)
        for (y in 0 until rows) {
            bitmap.getPixels(pixelRow, 0, width, 0, y, width, 1)
            pack(pixelRow, out, y * bytesPerLine)
        }
        return out
    }

    private fun pack(src: IntArray, dst: ByteArray, offset: Int = 0) {
        var o = offset
        if (color) {
            for (p in src) {
                dst[o++] = ((p shr 16) and 0xFF).toByte()
                dst[o++] = ((p shr 8) and 0xFF).toByte()
                dst[o++] = (p and 0xFF).toByte()
            }
        } else {
            for (p in src) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Rec. 601 luma, integer arithmetic: keeps a full page conversion cheap.
                dst[o++] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
        }
    }
}
