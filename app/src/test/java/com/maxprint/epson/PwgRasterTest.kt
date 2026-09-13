package com.maxprint.epson

import com.maxprint.epson.pdl.PwgRasterWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Decodes what [PwgRasterWriter] produced and compares it with the pixels that went in.
 * The run length encoding has two easy ways to go wrong — a single literal pixel and a
 * run of exactly 128 — so both are exercised here.
 */
class PwgRasterTest {

    private val width = 300
    private val rows = 5

    @Test
    fun headerIsExactlyOneThousandSevenHundredNinetySixBytes() {
        val out = ByteArrayOutputStream()
        val writer = PwgRasterWriter(out)
        writer.writeStreamHeader()
        writer.startPage(setup())
        assertEquals(4 + 1796, out.size())
    }

    @Test
    fun pixelsSurviveTheRoundTrip() {
        val expected = Array(rows) { row -> syntheticRow(row) }

        val out = ByteArrayOutputStream()
        val writer = PwgRasterWriter(out)
        writer.writeStreamHeader()
        writer.startPage(setup())
        expected.forEach { writer.writeLine(it) }
        writer.endPage()
        writer.flush()

        val bytes = out.toByteArray()
        assertEquals("RaS2", String(bytes, 0, 4, Charsets.US_ASCII))

        val decoded = decode(bytes, offset = 4 + 1796, bytesPerPixel = 3)
        assertEquals(rows, decoded.size)
        for (row in 0 until rows) {
            assertArrayEquals("row $row", expected[row], decoded[row])
        }
    }

    private fun setup() = PwgRasterWriter.PageSetup(
        widthPx = width,
        heightPx = rows,
        dpiX = 300,
        dpiY = 300,
        pageWidthPt = 595,
        pageHeightPt = 842,
        color = true,
        pwgMediaName = "iso_a4_210x297mm"
    )

    /**
     * A deliberately awkward row: a long flat run, then alternating pixels that cannot be
     * compressed, then a single odd pixel at the very end of the line.
     */
    private fun syntheticRow(row: Int): ByteArray {
        val line = ByteArray(width * 3)
        for (x in 0 until width) {
            val o = x * 3
            when {
                x < 150 -> {
                    line[o] = 0xFF.toByte(); line[o + 1] = 0xFF.toByte(); line[o + 2] = 0xFF.toByte()
                }

                x == width - 1 -> {
                    line[o] = 0x01; line[o + 1] = 0x02; line[o + 2] = 0x03
                }

                else -> {
                    line[o] = (x * 7 + row).toByte()
                    line[o + 1] = (x * 13 + row).toByte()
                    line[o + 2] = (x * 29 + row).toByte()
                }
            }
        }
        return line
    }

    /** Reference decoder for the CUPS/PWG line encoding. */
    private fun decode(bytes: ByteArray, offset: Int, bytesPerPixel: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = offset
        while (i < bytes.size && out.size < rows) {
            val lineRepeat = (bytes[i++].toInt() and 0xFF) + 1
            val line = ByteArray(width * bytesPerPixel)
            var x = 0
            while (x < width) {
                val count = bytes[i++].toInt() and 0xFF
                if (count <= 127) {
                    val repeats = count + 1
                    repeat(repeats) {
                        System.arraycopy(bytes, i, line, x * bytesPerPixel, bytesPerPixel)
                        x++
                    }
                    i += bytesPerPixel
                } else {
                    val literals = 257 - count
                    System.arraycopy(bytes, i, line, x * bytesPerPixel, literals * bytesPerPixel)
                    i += literals * bytesPerPixel
                    x += literals
                }
            }
            repeat(lineRepeat) { if (out.size < rows) out.add(line) }
        }
        return out
    }
}
