package com.joexblair.bosevolume

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * Finds Bose Music speakers on the LAN. They advertise `_bose-passport._tcp` over mDNS with the
 * device GUID in the `GUID` TXT record (the GUID goes in every WebSocket request header).
 */
class SpeakerDiscovery(context: Context, private val onFound: (SpeakerConfig) -> Unit) {
    private val app = context.applicationContext
    private val nsd: NsdManager = requireNotNull(app.getSystemService(NsdManager::class.java))
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        stop()
        multicastLock = requireNotNull(app.getSystemService(WifiManager::class.java))
            .createMulticastLock("bose-discovery").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { queue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        multicastLock?.release()
        multicastLock = null
        queue.clear()
    }

    // Older NsdManager versions only allow one resolve at a time.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                val guid = info.attributes["GUID"]?.toString(Charsets.UTF_8)
                main.post {
                    if (host != null) onFound(SpeakerConfig(host, guid, info.serviceName))
                    resolving = false
                    resolveNext()
                }
            }

            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                main.post { resolving = false; resolveNext() }
            }
        })
    }

    companion object {
        const val SERVICE_TYPE = "_bose-passport._tcp"
    }
}
