package com.maxprint.epson.job

import android.content.Context
import android.print.PrintAttributes
import com.maxprint.epson.ipp.IppCapabilities
import com.maxprint.epson.ipp.IppClient
import com.maxprint.epson.ipp.IppJobOptions
import com.maxprint.epson.ipp.IppJobState
import com.maxprint.epson.model.LinkType
import com.maxprint.epson.model.MediaSizes
import com.maxprint.epson.model.PrinterCaps
import com.maxprint.epson.model.PrinterTarget
import com.maxprint.epson.pdl.EscPr
import com.maxprint.epson.pdl.EscPrWriter
import com.maxprint.epson.pdl.PwgRasterWriter
import com.maxprint.epson.render.PdfRasterizer
import com.maxprint.epson.render.RasterLines
import com.maxprint.epson.transport.Connections
import com.maxprint.epson.util.L
import com.maxprint.epson.util.Prefs
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Raised when the user cancelled the job while we were streaming it. */
class JobCancelledException : IOException("Job cancelled")

/**
 * Turns the PDF the print framework gives us into bytes a specific printer accepts, and
 * pushes them down whichever transport that printer lives behind.
 *
 * Format selection, best first:
 *   1. PDF straight through  — sharpest and fastest, when `document-format-supported` says so
 *   2. PWG Raster            — mandatory for IPP Everywhere, so it always works over IPP
 *   3. ESC/P-R               — raw bulk USB and port 9100, i.e. everything older
 */
class PrintPipeline(
    private val context: Context,
    private val prefs: Prefs
) {

    fun interface Progress {
        fun onProgress(message: String)
    }

    fun interface Cancellation {
        fun isCancelled(): Boolean
    }

    @Throws(IOException::class)
    fun print(
        target: PrinterTarget,
        pdf: File,
        attributes: PrintAttributes,
        copies: Int,
        jobName: String,
        cancellation: Cancellation,
        progress: Progress
    ) {
        when (target.link) {
            LinkType.NET_IPP, LinkType.USB_IPP ->
                printViaIpp(target, pdf, attributes, copies, jobName, cancellation, progress)

            LinkType.NET_RAW, LinkType.USB_RAW ->
                printViaEscPr(target, pdf, attributes, copies, cancellation, progress)
        }
    }

    // ---------------------------------------------------------------- IPP

    private fun printViaIpp(
        target: PrinterTarget,
        pdf: File,
        attributes: PrintAttributes,
        copies: Int,
        jobName: String,
        cancellation: Cancellation,
        progress: Progress
    ) {
        Connections.openIppChannel(context, target).use { channel ->
            val client = IppClient(channel, target.ippUri(), target.resourcePath)

            progress.onProgress("Querying printer")
            val caps = try {
                IppCapabilities.from(client.getPrinterAttributes())
            } catch (t: IOException) {
                L.w("Get-Printer-Attributes failed, assuming IPP Everywhere defaults", t)
                PrinterCaps()
            }

            val mediaSize = attributes.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
            val colorWanted = attributes.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME
            val color = colorWanted && caps.supportsColor

            val options = IppJobOptions(
                jobName = jobName,
                copies = copies.coerceAtLeast(1),
                media = pickMedia(caps, mediaSize),
                colorMode = if (color) "color" else "monochrome",
                sides = pickSides(caps, attributes),
                resolution = null, // let the printer pick unless we rasterise ourselves
                printQuality = 4
            )

            val reply = when {
                prefs.preferPdf && caps.supportsPdf -> {
                    progress.onProgress("Sending PDF")
                    client.printJob("application/pdf", options, pdf.length()) { out ->
                        streamFile(pdf, out, cancellation)
                    }
                }

                caps.supportsPwgRaster || caps.supportsUrf -> {
                    val dpi = pickRasterDpi(caps)
                    progress.onProgress("Rasterising at ${dpi.first} dpi")
                    val rasterOptions = options.copy(resolution = dpi)
                    // The raster stream length is unknown until it is produced, so this
                    // request goes out chunked.
                    client.printJob("image/pwg-raster", rasterOptions, -1L) { out ->
                        writePwgRaster(pdf, mediaSize, dpi, color, copies, attributes, out, cancellation, progress)
                    }
                }

                else -> {
                    // No format we recognise. PDF is still the likeliest to work.
                    progress.onProgress("Sending PDF (format not advertised)")
                    client.printJob("application/pdf", options, pdf.length()) { out ->
                        streamFile(pdf, out, cancellation)
                    }
                }
            }

            if (!reply.isSuccess) {
                throw IOException("Printer rejected the job: ${reply.statusText}")
            }

            val jobId = reply.int("job-id")
            if (jobId != null) {
                progress.onProgress("Printing")
                awaitJob(client, jobId, cancellation, progress)
            }
        }
    }

    private fun awaitJob(
        client: IppClient,
        jobId: Int,
        cancellation: Cancellation,
        progress: Progress
    ) {
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            if (cancellation.isCancelled()) {
                runCatching { client.cancelJob(jobId) }
                throw JobCancelledException()
            }
            val (state, reasons) = try {
                client.getJobState(jobId)
            } catch (t: IOException) {
                // Many printers drop the connection once the job is spooled. That is not
                // an error: the pages are already on their way.
                L.d("job poll ended: ${t.message}")
                return
            }
            if (IppJobState.isTerminal(state)) {
                if (state == IppJobState.ABORTED) {
                    throw IOException("Printer aborted the job: ${reasons.joinToString()}")
                }
                if (state == IppJobState.CANCELED) throw JobCancelledException()
                return
            }
            if (reasons.isNotEmpty()) progress.onProgress(reasons.first())
            Thread.sleep(1_500)
        }
    }

    // ------------------------------------------------------------ PWG raster

    private fun writePwgRaster(
        pdf: File,
        mediaSize: PrintAttributes.MediaSize,
        dpi: Pair<Int, Int>,
        color: Boolean,
        copies: Int,
        attributes: PrintAttributes,
        sink: OutputStream,
        cancellation: Cancellation,
        progress: Progress
    ) {
        val out = BufferedOutputStream(sink, 64 * 1024)
        val writer = PwgRasterWriter(out)
        writer.writeStreamHeader()

        val (widthPx, heightPx) = MediaSizes.dotsFor(mediaSize, dpi.first, dpi.second)
        val pageWidthPt = mediaSize.widthMils * 72 / 1000
        val pageHeightPt = mediaSize.heightMils * 72 / 1000
        val lines = RasterLines(widthPx, color)

        PdfRasterizer(pdf).use { rasterizer ->
            for (page in 0 until rasterizer.pageCount) {
                if (cancellation.isCancelled()) throw JobCancelledException()
                progress.onProgress("Page ${page + 1} of ${rasterizer.pageCount}")

                writer.startPage(
                    PwgRasterWriter.PageSetup(
                        widthPx = widthPx,
                        heightPx = heightPx,
                        dpiX = dpi.first,
                        dpiY = dpi.second,
                        pageWidthPt = pageWidthPt,
                        pageHeightPt = pageHeightPt,
                        color = color,
                        pwgMediaName = MediaSizes.toPwg(mediaSize),
                        copies = copies.coerceAtLeast(1),
                        duplexMode = duplexCode(attributes)
                    )
                )

                rasterizer.renderPage(
                    index = page,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    bandRows = PdfRasterizer.bandRowsFor(widthPx)
                ) { _, bitmap ->
                    if (cancellation.isCancelled()) throw JobCancelledException()
                    for (y in 0 until bitmap.height) {
                        writer.writeLine(lines.row(bitmap, y))
                    }
                }

                writer.endPage()
            }
        }
        writer.flush()
        out.flush()
    }

    // ---------------------------------------------------------------- ESC/P-R

    private fun printViaEscPr(
        target: PrinterTarget,
        pdf: File,
        attributes: PrintAttributes,
        copies: Int,
        cancellation: Cancellation,
        progress: Progress
    ) {
        val mediaSize = attributes.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
        val color = attributes.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME
        val resolution = EscPr.InputResolution.nearest(prefs.renderDpi)

        // ESC/P-R geometry is in dots at the input resolution, and the printable area is
        // the paper minus the fixed 3 mm hardware margin on every side.
        val geometry = EscPr.geometryFor(mediaSize.widthMils, mediaSize.heightMils, resolution)
        if (!geometry.isUsable) {
            throw IOException("Paper size is smaller than the printer's margins")
        }
        val paperW = geometry.paperWidthDots
        val paperH = geometry.paperHeightDots
        val printableW = geometry.printableWidthDots
        val printableH = geometry.printableHeightDots
        val margin = geometry.marginDots

        val duplex = duplexCode(attributes)
        val totalCopies = copies.coerceAtLeast(1)

        Connections.openRawLink(context, target).use { link ->
            val out = BufferedOutputStream(link.output, 64 * 1024)
            val writer = EscPrWriter(out)

            writer.startJob(
                EscPrWriter.JobSetup(
                    resolution = resolution,
                    paperWidthDots = paperW,
                    paperHeightDots = paperH,
                    printableWidthDots = printableW,
                    printableHeightDots = printableH,
                    topMarginDots = margin,
                    leftMarginDots = margin,
                    color = color,
                    quality = if (resolution.dpi >= 600) EscPr.QUALITY_HIGH else EscPr.QUALITY_NORMAL,
                    duplex = duplex
                )
            )

            // Pixels always go out as 24-bit RGB; a mono job is signalled in setq instead.
            val lines = RasterLines(paperW, color = true)

            PdfRasterizer(pdf).use { rasterizer ->
                val pages = rasterizer.pageCount
                for (copy in 0 until totalCopies) {
                    for (page in 0 until pages) {
                        if (cancellation.isCancelled()) throw JobCancelledException()
                        progress.onProgress("Page ${page + 1} of $pages")

                        writer.startPage()
                        // The page is rendered at full paper size and the margins are
                        // cropped off, so content lands where the print dialog laid it out.
                        rasterizer.renderPage(
                            index = page,
                            widthPx = paperW,
                            heightPx = paperH,
                            bandRows = PdfRasterizer.bandRowsFor(paperW)
                        ) { top, bitmap ->
                            if (cancellation.isCancelled()) throw JobCancelledException()
                            for (y in 0 until bitmap.height) {
                                val row = top + y
                                if (row < margin || row >= margin + printableH) continue
                                val line = lines.row(bitmap, y)
                                writer.writeLine(row - margin, line, margin * 3, printableW)
                            }
                        }

                        val isLast = copy == totalCopies - 1 && page == pages - 1
                        writer.endPage(morePagesFollow = !isLast)
                    }
                }
            }

            writer.endJob()
            out.flush()
            link.finish()
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun streamFile(file: File, out: OutputStream, cancellation: Cancellation) {
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancellation.isCancelled()) throw JobCancelledException()
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }

    private fun pickMedia(caps: PrinterCaps, size: PrintAttributes.MediaSize): String? {
        val wanted = MediaSizes.toPwg(size)
        return caps.mediaSupported.firstOrNull { it.equals(wanted, ignoreCase = true) }
            ?: caps.defaultMedia
    }

    private fun pickSides(caps: PrinterCaps, attributes: PrintAttributes): String? {
        val code = duplexCode(attributes)
        if (code == 0 || !caps.supportsDuplex) return "one-sided"
        val wanted = if (code == 1) "two-sided-long-edge" else "two-sided-short-edge"
        return caps.sidesSupported.firstOrNull { it == wanted } ?: "one-sided"
    }

    private fun duplexCode(attributes: PrintAttributes): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            when (attributes.duplexMode) {
                PrintAttributes.DUPLEX_MODE_LONG_EDGE -> 1
                PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> 2
                else -> 0
            }
        } else 0

    /**
     * Raster DPI: honour the user's preference, but only if the printer lists it as a
     * supported raster resolution, otherwise take the closest one it does list.
     */
    private fun pickRasterDpi(caps: PrinterCaps): Pair<Int, Int> {
        val wanted = prefs.renderDpi
        val candidates = caps.rasterResolutions.ifEmpty { caps.resolutions }
        if (candidates.isEmpty()) return wanted to wanted
        return candidates.minByOrNull { kotlin.math.abs(it.first - wanted) } ?: (wanted to wanted)
    }
}
