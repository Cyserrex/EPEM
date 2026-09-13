package com.maxprint.epson.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.maxprint.epson.util.L
import java.io.Closeable
import java.io.File

/**
 * Renders the PDF that the Android print framework hands us into horizontal bands.
 *
 * Banding is not an optimisation here, it is a requirement: an A4 page at 600 dpi is
 * 4960 x 7016 pixels, which is 139 MB as ARGB_8888 and would OOM on most phones. Both
 * raster formats we emit are row-oriented, so we can render a band, ship it, and free it.
 */
class PdfRasterizer(pdfFile: File) : Closeable {

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)

    private val renderer = PdfRenderer(pfd)

    val pageCount: Int get() = renderer.pageCount

    /** Page size in PostScript points (1/72 inch), which is what PdfRenderer reports. */
    fun pageSizePoints(index: Int): Pair<Int, Int> {
        renderer.openPage(index).use { return it.width to it.height }
    }

    /**
     * @param widthPx   width of the printable area in device dots
     * @param heightPx  height of the printable area in device dots
     * @param bandRows  how many rows to render at once; the callback owns the bitmap only
     *                  for the duration of the call, after which it is recycled
     */
    fun renderPage(
        index: Int,
        widthPx: Int,
        heightPx: Int,
        bandRows: Int = 256,
        onBand: (topRow: Int, bitmap: Bitmap) -> Unit
    ) {
        renderer.openPage(index).use { page ->
            // PdfRenderer measures in points; scale so the page fills the device page box.
            val scaleX = widthPx.toFloat() / page.width
            val scaleY = heightPx.toFloat() / page.height

            var top = 0
            var bitmap: Bitmap? = null
            try {
                while (top < heightPx) {
                    val rows = minOf(bandRows, heightPx - top)
                    if (bitmap == null || bitmap.height != rows) {
                        bitmap?.recycle()
                        bitmap = Bitmap.createBitmap(widthPx, rows, Bitmap.Config.ARGB_8888)
                    }
                    val bmp = bitmap!!
                    // PdfRenderer composites onto whatever is already there, so start white:
                    // paper is white, and unrendered margins must not come out black.
                    bmp.eraseColor(Color.WHITE)

                    val m = Matrix()
                    m.setScale(scaleX, scaleY)
                    m.postTranslate(0f, -top.toFloat())
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    onBand(top, bmp)
                    top += rows
                }
            } finally {
                bitmap?.recycle()
            }
        }
    }

    override fun close() {
        try {
            renderer.close()
        } catch (t: Throwable) {
            L.d("PdfRenderer.close: ${t.message}")
        }
        try {
            pfd.close()
        } catch (t: Throwable) {
            L.d("pfd.close: ${t.message}")
        }
    }

    companion object {
        /**
         * Rows per band, chosen so one band stays around 8 MB regardless of page width.
         */
        fun bandRowsFor(widthPx: Int): Int {
            val bytesPerRow = widthPx.toLong() * 4
            if (bytesPerRow <= 0) return 256
            return (8L * 1024 * 1024 / bytesPerRow).toInt().coerceIn(16, 512)
        }
    }
}
