package com.maxprint.epson.util

import android.util.Log as ALog

/** One switchable logger so verbose tracing can be toggled from settings. */
object L {
    const val TAG = "EpsonMax"

    @Volatile
    var verbose: Boolean = false

    fun d(msg: String) {
        if (verbose) ALog.d(TAG, msg)
    }

    fun i(msg: String) = ALog.i(TAG, msg).let { }

    fun w(msg: String, t: Throwable? = null) {
        if (t != null) ALog.w(TAG, msg, t) else ALog.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) ALog.e(TAG, msg, t) else ALog.e(TAG, msg)
    }

    fun hex(prefix: String, bytes: ByteArray, max: Int = 64) {
        if (!verbose) return
        val n = minOf(max, bytes.size)
        val sb = StringBuilder(prefix).append(" [").append(bytes.size).append("] ")
        for (i in 0 until n) sb.append(String.format("%02X ", bytes[i]))
        if (bytes.size > n) sb.append("...")
        ALog.d(TAG, sb.toString())
    }
}
