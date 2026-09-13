package com.maxprint.epson.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.maxprint.epson.model.LinkType
import com.maxprint.epson.model.PrinterTarget
import com.maxprint.epson.util.L
import java.util.ArrayDeque

/**
 * DNS-SD discovery of printers on the local network — the same mechanism AirPrint and the
 * stock Epson plugin use.
 *
 * `NsdManager` only resolves one service at a time on many Android versions, so resolution
 * requests are queued and drained one by one instead of fired in parallel.
 */
class NetworkDiscovery(
    private val context: Context,
    private val onFound: (PrinterTarget) -> Unit,
    private val onLost: (String) -> Unit
) {
    companion object {
        private const val TYPE_IPP = "_ipp._tcp."
        private const val TYPE_IPPS = "_ipps._tcp."
        private const val TYPE_RAW = "_pdl-datastream._tcp."
        private val SERVICE_TYPES = listOf(TYPE_IPP, TYPE_IPPS, TYPE_RAW)
    }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolveQueue = ArrayDeque<Pair<String, NsdServiceInfo>>()
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun start() {
        acquireMulticastLock()
        for (type in SERVICE_TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = L.d("mDNS started $serviceType")

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    L.w("mDNS start failed for $serviceType ($errorCode)")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    L.w("mDNS stop failed for $serviceType ($errorCode)")
                }

                override fun onDiscoveryStopped(serviceType: String) = L.d("mDNS stopped $serviceType")

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    enqueue(type, serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    onLost(serviceInfo.serviceName)
                }
            }
            listeners += listener
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (t: Throwable) {
                L.w("discoverServices($type) failed", t)
            }
        }
    }

    @Synchronized
    fun stop() {
        listeners.forEach {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (t: Throwable) {
                L.d("stopServiceDiscovery: ${t.message}")
            }
        }
        listeners.clear()
        resolveQueue.clear()
        resolving = false
        releaseMulticastLock()
    }

    @Synchronized
    private fun enqueue(type: String, info: NsdServiceInfo) {
        resolveQueue.add(type to info)
        drain()
    }

    @Synchronized
    private fun drain() {
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        val (type, info) = next
        try {
            nsd.resolveService(info, resolveListener(type))
        } catch (t: Throwable) {
            L.w("resolveService failed", t)
            finishResolve()
        }
    }

    @Synchronized
    private fun finishResolve() {
        resolving = false
        drain()
    }

    private fun resolveListener(type: String) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            L.d("resolve failed ${serviceInfo.serviceName} ($errorCode)")
            finishResolve()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            try {
                toTarget(type, serviceInfo)?.let(onFound)
            } catch (t: Throwable) {
                L.w("resolve handling failed", t)
            }
            finishResolve()
        }
    }

    private fun toTarget(type: String, info: NsdServiceInfo): PrinterTarget? {
        val host = info.host?.hostAddress ?: return null
        val port = info.port
        if (port <= 0) return null

        val txt = readTxt(info)
        val resource = txt["rp"]?.let { if (it.startsWith("/")) it else "/$it" } ?: "/ipp/print"
        val makeModel = txt["ty"] ?: txt["product"]?.trim('(', ')') ?: info.serviceName

        return when (type) {
            TYPE_IPP, TYPE_IPPS -> PrinterTarget(
                link = LinkType.NET_IPP,
                address = "$host:$port",
                detail = resource,
                displayName = info.serviceName.ifBlank { makeModel },
                manufacturer = txt["usb_MFG"] ?: txt["MFG"],
                model = txt["usb_MDL"] ?: makeModel
            )

            TYPE_RAW -> PrinterTarget(
                link = LinkType.NET_RAW,
                address = "$host:$port",
                detail = "",
                displayName = info.serviceName.ifBlank { makeModel },
                manufacturer = txt["usb_MFG"] ?: txt["MFG"],
                model = txt["usb_MDL"] ?: makeModel
            )

            else -> null
        }
    }

    /** TXT records carry the printer's make, model and IPP resource path. */
    private fun readTxt(info: NsdServiceInfo): Map<String, String> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
            return emptyMap()
        }
        return try {
            info.attributes.entries.associate { (k, v) ->
                k to (v?.toString(Charsets.UTF_8) ?: "")
            }
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("EpsonMaxDiscovery").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (t: Throwable) {
            // Without the lock mDNS still works on most modern devices; it just gets
            // less reliable on some Wi-Fi chipsets that filter multicast in power save.
            L.d("multicast lock unavailable: ${t.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            L.d("multicast unlock: ${t.message}")
        }
        multicastLock = null
    }
}
