package com.maxprint.epson.service

import android.os.Handler
import android.os.Looper
import android.print.PrintJobInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.maxprint.epson.job.JobCancelledException
import com.maxprint.epson.job.PrintPipeline
import com.maxprint.epson.model.PrinterTarget
import com.maxprint.epson.transport.UsbPermissionNeededException
import com.maxprint.epson.usb.UsbPermission
import com.maxprint.epson.util.L
import com.maxprint.epson.util.Prefs
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The print service Android binds to.
 *
 * Everything the framework calls happens on the main thread and [PrintJob] is not thread
 * safe, so the pattern throughout is: hop to a worker to do the I/O, hop back to the main
 * thread to touch the job.
 */
class EpsonPrintService : PrintService() {

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "epson-print-worker").apply { isDaemon = true }
    }

    private lateinit var prefs: Prefs
    private lateinit var pipeline: PrintPipeline

    /** Jobs the user asked to cancel while we were still streaming them. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.applyLogLevel()
        pipeline = PrintPipeline(this, prefs)
        L.i("Epson Print Enabler MAX service created")
    }

    override fun onDestroy() {
        workers.shutdownNow()
        super.onDestroy()
    }

    override fun onConnected() {
        prefs.applyLogLevel()
        L.d("print service connected")
    }

    override fun onDisconnected() = L.d("print service disconnected")

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession =
        EpsonDiscoverySession(this, prefs, workers)

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val id = printJob.id.toString()
        L.d("cancel requested for $id")
        cancelled.add(id)
        // The worker notices the flag and unwinds; cancel the framework side immediately so
        // the notification does not sit there spinning.
        if (printJob.isQueued || printJob.isStarted) printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val jobId = printJob.id.toString()
        val info: PrintJobInfo = printJob.info
        val target = PrinterTarget.decode(info.printerId?.localId.orEmpty())

        if (target == null) {
            printJob.fail("Unknown printer")
            return
        }

        if (!printJob.isStarted) printJob.start()

        val document = printJob.document
        val source = document.data
        if (source == null) {
            printJob.fail("No document data")
            return
        }

        val spool = File(cacheDir, "job-$jobId.pdf".replace('/', '_'))
        val copies = info.copies.coerceAtLeast(1)
        val attributes = info.attributes
        val jobName = info.label ?: "Android print job"

        workers.execute {
            try {
                // PdfRenderer needs a seekable file, and the descriptor we get is only
                // guaranteed to be readable once, so spool it first.
                FileInputStream(source.fileDescriptor).use { input ->
                    spool.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                runCatching { source.close() }

                if (target.isUsb) ensureUsbPermission(target)

                pipeline.print(
                    target = target,
                    pdf = spool,
                    attributes = attributes,
                    copies = copies,
                    jobName = jobName,
                    cancellation = { cancelled.contains(jobId) },
                    progress = { message -> L.d("[$jobId] $message") }
                )

                main.post {
                    cancelled.remove(jobId)
                    if (printJob.isStarted) printJob.complete()
                }
            } catch (e: JobCancelledException) {
                L.i("job $jobId cancelled")
                main.post {
                    cancelled.remove(jobId)
                    if (!printJob.isCancelled) printJob.cancel()
                }
            } catch (e: Throwable) {
                L.e("job $jobId failed", e)
                val message = friendlyError(e)
                main.post {
                    cancelled.remove(jobId)
                    if (!printJob.isCancelled) printJob.fail(message)
                }
            } finally {
                spool.delete()
            }
        }
    }

    /** Prompts for USB access and blocks the worker until the user answers. */
    @Throws(IOException::class)
    private fun ensureUsbPermission(target: PrinterTarget) {
        val device = com.maxprint.epson.transport.Connections.findUsbDevice(this, target)
        if (UsbPermission.has(this, device)) return
        if (!UsbPermission.requestBlocking(this, device)) {
            throw UsbPermissionNeededException(device)
        }
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is UsbPermissionNeededException ->
            "USB access was not granted. Unplug and replug the printer, then allow access."

        is IOException -> t.message ?: "Could not reach the printer"
        else -> t.message ?: t.javaClass.simpleName
    }

    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
