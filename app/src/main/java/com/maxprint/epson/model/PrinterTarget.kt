package com.maxprint.epson.model

import android.net.Uri

enum class LinkType(val tag: String) {
    /** USB printer class interface, protocol 4: HTTP/IPP tunnelled over bulk endpoints. */
    USB_IPP("uipp"),

    /** USB printer class interface, protocol 1/2/3: raw PDL over bulk endpoints. */
    USB_RAW("uraw"),

    /** Network IPP / IPPS (AirPrint, IPP Everywhere). */
    NET_IPP("nipp"),

    /** Network raw socket, normally port 9100 (JetDirect / pdl-datastream). */
    NET_RAW("nraw");

    companion object {
        fun from(tag: String): LinkType? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Everything needed to reach one printer, encodable into the `localId` of an Android
 * [android.print.PrinterId].
 *
 * The print framework can hand a printer id back to us after our process was killed, so
 * the id has to be self-contained — we cannot rely on an in-memory discovery cache.
 *
 * Encoded form:
 *   `uipp|04b8:1122|SERIAL|Display name`
 *   `nipp|192.168.1.50:631|/ipp/print|Display name`
 */
data class PrinterTarget(
    val link: LinkType,
    /** `vvvv:pppp` for USB, `host:port` for network. */
    val address: String,
    /** USB serial number (may be empty) or the IPP resource path. */
    val detail: String,
    val displayName: String,
    val manufacturer: String? = null,
    val model: String? = null,
    /** IEEE-1284 `CMD:` entries, when we managed to read the device id. */
    val commandSet: List<String> = emptyList()
) {
    val isUsb: Boolean get() = link == LinkType.USB_IPP || link == LinkType.USB_RAW

    val vendorId: Int
        get() = if (isUsb) address.substringBefore(':').toInt(16) else 0

    val productId: Int
        get() = if (isUsb) address.substringAfter(':').toInt(16) else 0

    val host: String get() = address.substringBeforeLast(':')

    val port: Int get() = address.substringAfterLast(':').toIntOrNull() ?: 631

    val resourcePath: String get() = if (detail.startsWith("/")) detail else "/ipp/print"

    /** The `printer-uri` an IPP request must carry. */
    fun ippUri(): String = when (link) {
        LinkType.NET_IPP -> {
            val scheme = if (port == 443 || port == 631 && detail.startsWith("/ipps")) "ipp" else "ipp"
            "$scheme://$host:$port$resourcePath"
        }
        // RFC 8011 still wants a syntactically valid printer-uri over IPP-USB; the
        // de-facto convention (and what CUPS' ippusb backend sends) is localhost.
        LinkType.USB_IPP -> "ipp://localhost:60000$resourcePath"
        else -> "ipp://$address$resourcePath"
    }

    fun encode(): String = listOf(
        link.tag,
        address,
        detail,
        displayName,
        manufacturer.orEmpty(),
        model.orEmpty(),
        commandSet.joinToString(",")
    ).joinToString("|") { Uri.encode(it) }

    companion object {
        fun decode(encoded: String): PrinterTarget? {
            val parts = encoded.split('|').map { Uri.decode(it) }
            if (parts.size < 4) return null
            val link = LinkType.from(parts[0]) ?: return null
            return PrinterTarget(
                link = link,
                address = parts[1],
                detail = parts[2],
                displayName = parts[3],
                manufacturer = parts.getOrNull(4)?.ifEmpty { null },
                model = parts.getOrNull(5)?.ifEmpty { null },
                commandSet = parts.getOrNull(6)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            )
        }

        fun usbKey(vendorId: Int, productId: Int): String =
            String.format("%04x:%04x", vendorId, productId)
    }
}
