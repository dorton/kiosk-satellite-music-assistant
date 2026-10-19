package net.markandrew.kiosk.music.receiver

import android.content.Context
import android.net.nsd.*
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** Bounded mDNS browse; serialize resolutions for Android 11's NSD implementation. */
class ServerDiscovery(context: Context) {
    data class Server(val key: String, val label: String, val endpoint: String)
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val results = linkedMapOf<String, Server>()
    fun scan(done: (List<Server>) -> Unit) {
        stop()
        val ticket = generation
        var resolving = false
        fun resolveNext() {
            if (ticket != generation || resolving || pending.isEmpty()) return
            resolving = true
            val service = pending.removeFirst()
            @Suppress("DEPRECATION")
            try { nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { main.post {
                    if (ticket == generation) { resolving = false; resolveNext() }
                } }
                override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                    if (ticket != generation) return@post
                    try {
                        val host = checkNotNull(info.host).hostAddress!!.substringBefore('%')
                        require(info.port in 1..65535)
                        val path = info.attributes["path"]?.toString(Charsets.UTF_8) ?: "/sendspin"
                        require(path == "/sendspin")
                        val address = if (host.contains(':')) "[$host]" else host
                        val endpoint = "ws://$address:${info.port}$path"
                        ReceiverEndpoint.parse(endpoint)
                        val label = (info.attributes["name"]?.toString(Charsets.UTF_8) ?: info.serviceName)
                            .replace(Regex("[\\p{Cntrl}]"), " ").take(120)
                        results[info.serviceName] = Server(info.serviceName, label, endpoint)
                    } catch (_: Exception) { /* Ignore malformed advertisements, never log TXT/URLs. */ }
                    resolving = false; resolveNext()
                } }
            }) } catch (_: RuntimeException) { resolving = false; resolveNext() }
        }
        val browse = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) {}
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) { main.post { if (ticket == generation) results.remove(info.serviceName) } }
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (ticket == generation && results.size + pending.size < 32 &&
                    !results.containsKey(info.serviceName) && pending.none { it.serviceName == info.serviceName }) {
                    pending.add(info); resolveNext()
                }
            } }
        }
        listener = browse
        try { nsd.discoverServices("_sendspin-server._tcp.", NsdManager.PROTOCOL_DNS_SD, browse) }
        catch (_: RuntimeException) { /* Bounded empty result; retry later. */ }
        main.postDelayed({ if (ticket == generation) {
            val found = results.values.toList()
            stop(); done(found)
        } }, 5000)
    }
    fun stop() {
        generation++; main.removeCallbacksAndMessages(null)
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: RuntimeException) {} }
        listener = null; pending.clear(); results.clear()
    }
}
