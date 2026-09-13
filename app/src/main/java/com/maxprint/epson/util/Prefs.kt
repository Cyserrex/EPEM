package com.maxprint.epson.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.maxprint.epson.model.PrinterTarget

/** Thin typed wrapper over the default SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val onlyEpson: Boolean get() = sp.getBoolean(KEY_ONLY_EPSON, true)
    val preferPdf: Boolean get() = sp.getBoolean(KEY_PREFER_PDF, true)
    val debugLog: Boolean get() = sp.getBoolean(KEY_DEBUG, false)

    val renderDpi: Int
        get() = sp.getString(KEY_DPI, "300")?.toIntOrNull()?.coerceIn(72, 1200) ?: 300

    /** Manually added printers, stored as encoded [PrinterTarget] ids. */
    var manualTargets: List<String>
        get() = sp.getStringSet(KEY_MANUAL, emptySet())!!.sorted()
        set(value) = sp.edit().putStringSet(KEY_MANUAL, value.toSet()).apply()

    fun addManual(target: PrinterTarget) {
        manualTargets = (manualTargets + target.encode()).distinct()
    }

    fun removeManual(encoded: String) {
        manualTargets = manualTargets - encoded
    }

    fun applyLogLevel() {
        L.verbose = debugLog
    }

    companion object {
        const val KEY_ONLY_EPSON = "only_epson"
        const val KEY_PREFER_PDF = "prefer_pdf"
        const val KEY_DEBUG = "debug_log"
        const val KEY_DPI = "render_dpi"
        const val KEY_MANUAL = "manual_targets"
    }
}
