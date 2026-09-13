package com.maxprint.epson

import com.maxprint.epson.pdl.EscPr
import com.maxprint.epson.pdl.EscPrWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Checks the ESC/P-R output against values taken from Epson's own reference driver
 * (`epson-inkjet-printer-escpr` 1.8.8), which is what the EcoTank L-series expects.
 */
class EscPrTest {

    /**
     * `epsMediaSize[]` in the driver lists A4 as 2976 x 4209 dots with a 2892 x 4125
     * printable area at 360 dpi, and `epsMediaSize300[]` lists 2480 x 3507 / 2410 x 3437
     * at 300 dpi. Our arithmetic has to land on exactly those numbers.
     */
    @Test
    fun a4GeometryMatchesTheDriverMediaTable() {
        val a4WidthMils = 8268   // 210 mm
        val a4HeightMils = 11693 // 297 mm

        val at360 = EscPr.geometryFor(a4WidthMils, a4HeightMils, EscPr.InputResolution.DPI_360)
        assertEquals(2976, at360.paperWidthDots)
        assertEquals(4209, at360.paperHeightDots)
        assertEquals(2892, at360.printableWidthDots)
        assertEquals(4125, at360.printableHeightDots)
        assertEquals(42, at360.marginDots)

        val at300 = EscPr.geometryFor(a4WidthMils, a4HeightMils, EscPr.InputResolution.DPI_300)
        assertEquals(2480, at300.paperWidthDots)
        assertEquals(3507, at300.paperHeightDots)
        assertEquals(2410, at300.printableWidthDots)
        assertEquals(3437, at300.printableHeightDots)
        assertEquals(35, at300.marginDots)
    }

    /**
     * The format's one real trap: the command length is little endian while every
     * parameter inside the payload is big endian.
     */
    @Test
    fun commandFramingIsTenBytesWithLittleEndianLength() {
        val out = ByteArrayOutputStream()
        val writer = EscPrWriter(out)
        writer.startJob(setup())
        val bytes = out.toByteArray()

        val setj = indexOf(bytes, "setj".toByteArray(Charsets.US_ASCII))
        assertTrue("setj command missing", setj > 0)

        // Walk back over the 4 byte length to the ESC and the class byte.
        val header = setj - 6
        assertEquals(0x1B, bytes[header].toInt() and 0xFF)
        assertEquals('j'.code, bytes[header + 1].toInt() and 0xFF)
        assertEquals(0x16, bytes[header + 2].toInt() and 0xFF) // 22, low byte first
        assertEquals(0x00, bytes[header + 3].toInt() and 0xFF)
        assertEquals(0x00, bytes[header + 4].toInt() and 0xFF)
        assertEquals(0x00, bytes[header + 5].toInt() and 0xFF)

        // setj parameters: paper width and height as 4 byte big endian dot counts.
        val p = setj + 4
        assertEquals(2976, readIntBe(bytes, p))
        assertEquals(4209, readIntBe(bytes, p + 4))
        assertEquals(42, readShortBe(bytes, p + 8))   // top margin
        assertEquals(42, readShortBe(bytes, p + 10))  // left margin
        assertEquals(2892, readIntBe(bytes, p + 12))
        assertEquals(4125, readIntBe(bytes, p + 16))
        assertEquals(0x00, bytes[p + 20].toInt())     // 360 dpi input resolution code
    }

    @Test
    fun jobStartsWithTheEjlPreambleAndEntersEscpr() {
        val out = ByteArrayOutputStream()
        EscPrWriter(out).startJob(setup())
        val bytes = out.toByteArray()

        assertArrayEquals(
            EscPr.EXIT_PACKET_MODE,
            bytes.copyOfRange(0, EscPr.EXIT_PACKET_MODE.size)
        )
        assertTrue(indexOf(bytes, EscPr.ENTER_REMOTE_MODE) > 0)
        assertTrue(indexOf(bytes, EscPr.EXIT_REMOTE_MODE) > 0)
        assertTrue(indexOf(bytes, EscPr.ENTER_ESCPR_MODE) > 0)
        // ESC/P-R mode must come after the remote block closes.
        assertTrue(indexOf(bytes, EscPr.ENTER_ESCPR_MODE) > indexOf(bytes, EscPr.EXIT_REMOTE_MODE))
    }

    @Test
    fun runLengthEncodingRoundTrips() {
        val pixels = 700
        val bpp = 3
        val line = ByteArray(pixels * bpp)
        for (x in 0 until pixels) {
            val o = x * bpp
            when {
                // A long flat run, longer than the 129 pixel cap.
                x < 400 -> {
                    line[o] = 0xFF.toByte(); line[o + 1] = 0xFF.toByte(); line[o + 2] = 0xFF.toByte()
                }
                // Then noise that cannot be compressed.
                x < pixels - 1 -> {
                    line[o] = (x * 7).toByte()
                    line[o + 1] = (x * 13 + 5).toByte()
                    line[o + 2] = (x * 31 + 11).toByte()
                }
                // And a single odd pixel at the very end.
                else -> {
                    line[o] = 1; line[o + 1] = 2; line[o + 2] = 3
                }
            }
        }

        val encoded = EscPr.runLengthEncode(line, 0, pixels, bpp)
        val decoded = if (encoded.compressed) {
            decodeRle(encoded.bytes, encoded.size, pixels, bpp)
        } else {
            encoded.bytes.copyOf(encoded.size)
        }
        assertArrayEquals(line, decoded)
    }

    @Test
    fun incompressibleDataFallsBackToRawAndStaysTheSameSize() {
        val pixels = 256
        val bpp = 3
        val line = ByteArray(pixels * bpp)
        // Every pixel distinct, so any RLE attempt would grow the line.
        for (x in 0 until pixels) {
            line[x * bpp] = x.toByte()
            line[x * bpp + 1] = (255 - x).toByte()
            line[x * bpp + 2] = (x * 3).toByte()
        }

        val encoded = EscPr.runLengthEncode(line, 0, pixels, bpp)
        assertTrue("raw fallback must not exceed the original", encoded.size <= pixels * bpp)
        if (!encoded.compressed) {
            assertArrayEquals(line, encoded.bytes.copyOf(encoded.size))
        }
    }

    // --- helpers -----------------------------------------------------------------

    private fun setup() = EscPrWriter.JobSetup(
        resolution = EscPr.InputResolution.DPI_360,
        paperWidthDots = 2976,
        paperHeightDots = 4209,
        printableWidthDots = 2892,
        printableHeightDots = 4125,
        topMarginDots = 42,
        leftMarginDots = 42,
        color = true
    )

    /** Reference decoder for the driver's `RunLengthEncode()`. */
    private fun decodeRle(src: ByteArray, size: Int, pixels: Int, bpp: Int): ByteArray {
        val out = ByteArray(pixels * bpp)
        var i = 0
        var x = 0
        while (i < size && x < pixels) {
            val count = src[i++].toInt() and 0xFF
            if (count <= 0x7F) {
                val literals = count + 1
                System.arraycopy(src, i, out, x * bpp, literals * bpp)
                i += literals * bpp
                x += literals
            } else {
                val repeats = 257 - count
                for (k in 0 until repeats) {
                    System.arraycopy(src, i, out, (x + k) * bpp, bpp)
                }
                i += bpp
                x += repeats
            }
        }
        assertEquals("decoder consumed a different pixel count", pixels, x)
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun readIntBe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun readShortBe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
}
