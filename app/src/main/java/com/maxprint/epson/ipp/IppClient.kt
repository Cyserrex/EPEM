package com.maxprint.epson.ipp

import com.maxprint.epson.transport.HttpChannel
import com.maxprint.epson.util.L
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Job settings translated from the Android print dialog into IPP attribute values. */
data class IppJobOptions(
    val jobName: String = "Android print job",
    val copies: Int = 1,
    /** PWG media keyword, e.g. `iso_a4_210x297mm`. */
    val media: String? = null,
    /** `color`, `monochrome` or `auto`. */
    val colorMode: String? = null,
    /** `one-sided`, `two-sided-long-edge`, `two-sided-short-edge`. */
    val sides: String? = null,
    val resolution: Pair<Int, Int>? = null,
    /** 3 = draft, 4 = normal, 5 = high. */
    val printQuality: Int? = null,
    val userName: String = "android"
)

/**
 * IPP client. It has no idea whether [channel] is a TCP socket or a USB cable, which is
 * exactly the point: the Wi-Fi and USB OTG paths share every line below this one.
 */
class IppClient(
    private val channel: HttpChannel,
    private val printerUri: String,
    private val resourcePath: String = "/ipp/print"
) {
    private val requestIds = AtomicInteger(1)

    @Throws(IOException::class)
    fun getPrinterAttributes(): IppResponse {
        val body = IppRequestBuilder(IppOp.GET_PRINTER_ATTRIBUTES, requestIds.getAndIncrement())
            .operationHeader(printerUri)
            .attr(
                IppTag.KEYWORD, "requested-attributes", listOf(
                    "printer-name",
                    "printer-make-and-model",
                    "printer-state",
                    "printer-state-reasons",
                    "printer-is-accepting-jobs",
                    "document-format-supported",
                    "document-format-default",
                    "media-supported",
                    "media-default",
                    "media-ready",
                    "printer-resolution-supported",
                    "printer-resolution-default",
                    "print-color-mode-supported",
                    "sides-supported",
                    "print-quality-supported",
                    "copies-supported",
                    "pwg-raster-document-resolution-supported",
                    "pwg-raster-document-type-supported",
                    "pwg-raster-document-sheet-back",
                    "urf-supported",
                    "media-col-database",
                    "media-bottom-margin-supported",
                    "media-top-margin-supported",
                    "media-left-margin-supported",
                    "media-right-margin-supported",
                    "ipp-versions-supported"
                )
            )
            .build()

        return exchange(body, contentLength = body.size.toLong(), document = null)
    }

    /**
     * Print-Job: the document rides along in the same POST, right after the IPP header.
     *
     * @param documentLength -1 when it cannot be known in advance (a raster stream we are
     *        generating on the fly), which makes the request chunked.
     */
    @Throws(IOException::class)
    fun printJob(
        documentFormat: String,
        options: IppJobOptions,
        documentLength: Long,
        document: (OutputStream) -> Unit
    ): IppResponse {
        val b = IppRequestBuilder(IppOp.PRINT_JOB, requestIds.getAndIncrement())
            .operationHeader(printerUri, options.userName)
            .attr(IppTag.MIME_MEDIA_TYPE, "document-format", documentFormat)
            .attr(IppTag.NAME, "job-name", options.jobName.take(255))
            .attrBool("ipp-attribute-fidelity", false)

        b.group(IppTag.JOB_ATTRS)
        if (options.copies > 1) b.attrInt("copies", options.copies)
        options.media?.let { b.attr(IppTag.KEYWORD, "media", it) }
        options.colorMode?.let { b.attr(IppTag.KEYWORD, "print-color-mode", it) }
        options.sides?.let { b.attr(IppTag.KEYWORD, "sides", it) }
        options.resolution?.let { b.attrResolution("printer-resolution", it.first, it.second) }
        options.printQuality?.let { b.attrInt("print-quality", it, IppTag.ENUM) }

        val header = b.build()
        val total = if (documentLength >= 0) header.size + documentLength else -1L
        return exchange(header, total, document)
    }

    @Throws(IOException::class)
    fun getJobState(jobId: Int): Pair<Int, List<String>> {
        val body = IppRequestBuilder(IppOp.GET_JOB_ATTRIBUTES, requestIds.getAndIncrement())
            .operationHeader(printerUri)
            .attrInt("job-id", jobId)
            .attr(IppTag.KEYWORD, "requested-attributes", listOf("job-state", "job-state-reasons"))
            .build()
        val reply = exchange(body, body.size.toLong(), null)
        val state = reply.int("job-state") ?: IppJobState.PROCESSING
        return state to reply.strings("job-state-reasons")
    }

    @Throws(IOException::class)
    fun cancelJob(jobId: Int): IppResponse {
        val body = IppRequestBuilder(IppOp.CANCEL_JOB, requestIds.getAndIncrement())
            .operationHeader(printerUri)
            .attrInt("job-id", jobId)
            .build()
        return exchange(body, body.size.toLong(), null)
    }

    private fun exchange(
        header: ByteArray,
        contentLength: Long,
        document: ((OutputStream) -> Unit)?
    ): IppResponse {
        L.hex("IPP request", header)
        val reply = channel.post(
            path = resourcePath,
            contentType = "application/ipp",
            contentLength = contentLength
        ) { out ->
            out.write(header)
            document?.invoke(out)
            out.flush()
        }

        reply.use { r ->
            if (!r.isSuccess) {
                throw IOException("Printer replied HTTP ${r.status} ${r.reason}")
            }
            val parsed = IppParser.parse(r.body)
            L.d("IPP <- ${parsed.statusText} (${parsed.attributes.size} attributes)")
            return parsed
        }
    }
}
