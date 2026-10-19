package net.markandrew.kiosk.music.receiver

import android.content.Context
import android.net.*
import android.os.*
import java.util.function.Consumer

/** Desired enable state owns discovery, transport-only reconnects and stable server selection. */
class ReceiverManager(context: Context, private val saveServer: Consumer<String>) {
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val discovery = ServerDiscovery(context)
    val receiver = PluginReceiver(context)
    @Volatile private var enabled = false
    private var endpoint = ""
    private var name = "Music Assistant Plugin"
    private var selected = ""
    private var epoch = 0L
    private var scanning = false
    private var preparing = false
    private var volumePreparation: java.util.function.BiConsumer<String, Runnable>? = null
    fun prepareVolume(preparer: java.util.function.BiConsumer<String, Runnable>) { volumePreparation = preparer }
    private var blocked = false
    private var nextAttempt = 0L
    private var failures = 0
    private var address = ""
    private var message = "Disabled"
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var choices: Map<String, ServerDiscovery.Server> = emptyMap()
    @Volatile private var state: Map<String, Any> = mapOf("status" to "Disabled")
    fun state(): Map<String, Any> = state
    fun choices(): Map<String, String> = choices.mapValues { it.value.label }
    fun configure(enable: Boolean, serverEndpoint: String, playerName: String, remembered: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (enabled == enable && endpoint == serverEndpoint && name == playerName && selected == remembered) return
        // Late naming must not tear down playback. Next natural connection uses the same identity/trust.
        if (enabled == enable && endpoint == serverEndpoint && selected == remembered) { name = playerName; return }
        stop()
        enabled = enable; endpoint = serverEndpoint.trim(); name = playerName; selected = remembered
        blocked = false; failures = 0; nextAttempt = 0; address = ""
        if (!enable) return
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { if (enabled && !blocked) nextAttempt = 0 } }
        }.also { try { connectivity.registerDefaultNetworkCallback(it) } catch (_: RuntimeException) {} }
        tick()
    }
    fun choose(key: String) {
        if (!enabled || !choices.containsKey(key)) return
        receiver.stop()
        selected = key; address = ""; nextAttempt = 0; blocked = false
        saveServer.accept(key)
    }
    fun retry() { if (enabled) { receiver.stop(); blocked = false; failures = 0; nextAttempt = 0 } }
    private fun tick() {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val observed = receiver.state()
        if (observed["connected"] == true) { failures = 0; message = observed["status"].toString() }
        else if (observed["failed"] == true) {
            if (observed["retryable"] != true) { blocked = true; message = observed["status"].toString() }
            else {
                receiver.stop(); failures++
                nextAttempt = now + ReceiverPolicy.retryDelay(failures)
                address = ""; message = "Network lost — reconnecting automatically"
            }
        } else if (observed["running"] == true) message = observed["status"].toString()
        if (!blocked && receiver.state()["running"] != true && now >= nextAttempt && !scanning && !preparing) {
            if (connectivity.activeNetwork == null) message = "Waiting for network"
            else if (endpoint.isNotBlank()) connect(endpoint)
            else if (address.isNotBlank()) connect(address)
            else browse()
        }
        state = mapOf("status" to message, "enabled" to enabled, "blocked" to blocked)
        main.postDelayed({ tick() }, 1000)
    }
    private fun browse() {
        scanning = true; message = "Discovering Music Assistant…"
        val ticket = epoch
        discovery.scan { servers ->
            if (!enabled || epoch != ticket) return@scan
            scanning = false; choices = servers.associateBy { it.key }
            val key = ReceiverPolicy.select(servers.map { it.key }, selected)
            val result = servers.singleOrNull { it.key == key }
            if (result != null) {
                if (selected.isBlank()) { selected = result.key; saveServer.accept(selected) }
                address = result.endpoint; nextAttempt = 0
            } else {
                message = if (selected.isNotBlank()) "Waiting for the remembered server"
                    else if (servers.size > 1) "Multiple servers — choose one in Receiver setup"
                    else "No Sendspin server found — retrying discovery"
                nextAttempt = SystemClock.elapsedRealtime() + 15_000
            }
        }
    }
    private fun connect(value: String) {
        val ticket = epoch
        preparing = true; message = "Reading previous music volume…"
        var answered = false
        val start = Runnable { main.post {
            if (ticket != epoch || !enabled || answered) return@post
            answered = true; preparing = false
            try { receiver.start(value, name); message = "Connecting securely…" }
            catch (_: RuntimeException) { blocked = true; message = "Check server endpoint and player name" }
        } }
        // A read timeout may use a verified cache or silence, never a full-volume default.
        main.postDelayed({ start.run() }, 12000)
        volumePreparation?.accept(value, start) ?: start.run()
    }
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { enabled = false; main.post { stop() }; return }
        enabled = false; epoch++; preparing = false; main.removeCallbacksAndMessages(null)
        discovery.stop(); scanning = false
        callback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: RuntimeException) {} }; callback = null
        receiver.stop(); choices = emptyMap(); message = "Disabled"; state = mapOf("status" to message, "enabled" to false)
    }
}
