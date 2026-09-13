package com.maxprint.epson.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.maxprint.epson.util.L
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Asks the system for access to a USB device and waits for the user's answer. */
object UsbPermission {

    private const val ACTION = "com.maxprint.epson.USB_PERMISSION"

    fun has(context: Context, device: UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.hasPermission(device)
    }

    /** Fire and forget: shows the system dialog, result arrives on the broadcast. */
    fun request(context: Context, device: UsbDevice) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) return
        manager.requestPermission(device, pendingIntent(context))
    }

    /**
     * Blocking variant used from the print service's worker thread: the job cannot proceed
     * until the user decides, and there is nothing useful to do in the meantime.
     *
     * Must not be called on the main thread.
     */
    fun requestBlocking(context: Context, device: UsbDevice, timeoutSeconds: Long = 60): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) return true

        val latch = CountDownLatch(1)
        var granted = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION) return
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }

        val filter = IntentFilter(ACTION)
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return try {
            manager.requestPermission(device, pendingIntent(context))
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
            granted || manager.hasPermission(device)
        } finally {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
                .onFailure { L.d("unregisterReceiver: ${it.message}") }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        // The USB framework writes the device and the grant flag into this intent, so it
        // has to stay mutable on Android 12 and newer.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context.applicationContext, 0, intent, flags)
    }
}
