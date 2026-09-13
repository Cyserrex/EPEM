package com.maxprint.epson.model

import android.print.PrintAttributes.MediaSize

/**
 * Translation between PWG "self describing media names" (what IPP speaks) and the
 * [MediaSize] constants the Android print dialog speaks.
 */
object MediaSizes {

    private val byPwg: Map<String, MediaSize> = mapOf(
        "iso_a3_297x420mm" to MediaSize.ISO_A3,
        "iso_a4_210x297mm" to MediaSize.ISO_A4,
        "iso_a5_148x210mm" to MediaSize.ISO_A5,
        "iso_a6_105x148mm" to MediaSize.ISO_A6,
        "iso_b5_176x250mm" to MediaSize.ISO_B5,
        "jis_b5_182x257mm" to MediaSize.JIS_B5,
        "jis_b4_257x364mm" to MediaSize.JIS_B4,
        "na_letter_8.5x11in" to MediaSize.NA_LETTER,
        "na_legal_8.5x14in" to MediaSize.NA_LEGAL,
        "na_ledger_11x17in" to MediaSize.NA_TABLOID,
        "na_executive_7.25x10.5in" to MediaSize.NA_GOVT_LETTER,
        "na_index-3x5_3x5in" to MediaSize.NA_INDEX_3X5,
        "na_index-4x6_4x6in" to MediaSize.NA_INDEX_4X6,
        "na_index-5x8_5x8in" to MediaSize.NA_INDEX_5X8,
        "na_foolscap_8.5x13in" to MediaSize.NA_FOOLSCAP,
        "jpn_hagaki_100x148mm" to MediaSize.JPN_HAGAKI,
        "jpn_oufuku_148x200mm" to MediaSize.JPN_OUFUKU
    )

    private val byAndroidId: Map<String, String> =
        byPwg.entries.associate { (pwg, size) -> size.id to pwg }

    fun toAndroid(pwg: String): MediaSize? = byPwg[normalise(pwg)] ?: parseSelfDescribing(pwg)

    fun toPwg(size: MediaSize): String =
        byAndroidId[size.id] ?: synthesise(size)

    private fun normalise(pwg: String) = pwg.trim().lowercase()

    /**
     * PWG names encode their own dimensions (`prefix_name_WxHunit`), so an unknown size is
     * still usable — we just build a MediaSize out of the numbers.
     */
    private fun parseSelfDescribing(pwg: String): MediaSize? {
        val dims = pwg.substringAfterLast('_')
        val m = Regex("^([0-9.]+)x([0-9.]+)(mm|in)$").find(dims) ?: return null
        val w = m.groupValues[1].toDoubleOrNull() ?: return null
        val h = m.groupValues[2].toDoubleOrNull() ?: return null
        val mils = if (m.groupValues[3] == "mm") 1000.0 / 25.4 else 1000.0
        return MediaSize(pwg, pwg, (w * mils).toInt(), (h * mils).toInt())
    }

    private fun synthesise(size: MediaSize): String {
        // MediaSize dimensions are in mils (1/1000 inch); PWG wants a 2-decimal mm figure.
        val wMm = size.widthMils * 25.4 / 1000.0
        val hMm = size.heightMils * 25.4 / 1000.0
        return String.format("custom_%s_%.2fx%.2fmm", size.id.lowercase(), wMm, hMm)
    }

    /** Page box in device dots for a given media size and resolution. */
    fun dotsFor(size: MediaSize, dpiX: Int, dpiY: Int): Pair<Int, Int> {
        val w = (size.widthMils.toLong() * dpiX / 1000L).toInt()
        val h = (size.heightMils.toLong() * dpiY / 1000L).toInt()
        return w to h
    }
}
