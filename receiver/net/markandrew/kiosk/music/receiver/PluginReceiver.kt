package net.markandrew.kiosk.music.receiver

import android.content.Context
import android.media.*
import android.os.*
import com.sendspindroid.model.PlaybackStateType
import com.sendspindroid.playback.SendSpinEngine
import com.sendspindroid.sendspin.*
import com.sendspindroid.sendspin.crypto.*
import com.sendspindroid.sendspin.pairing.*
import com.sendspindroid.sendspin.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher

/** Session-owned receiver, not an Android Service. Never touches Kiosk's player or settings. */
class PluginReceiver(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val dispatcher = main.asCoroutineDispatcher("MusicAssistantPlugin")
    @Volatile private var epoch = 0L
    private var scope: CoroutineScope? = null
    @Volatile private var client: SendSpin? = null
    private var engine: SendSpinEngine? = null
    @Volatile private var store: ReceiverStore? = null
    private var focus: AudioFocusRequest? = null
    private var focusSerial = 0L
    private val gain = net.markandrew.kiosk.music.AudioGainPolicy()
    private var volumeObserver: Runnable? = null
    private var linked = false
    private var volumeGate = true
    private var volume: Int? = null
    private var volumeServer = ""
    private var volumeEndpoint = ""
    private var volumeRevision = 0L
    @Volatile private var volumeAdvertisement = ""
    fun nativeVolumeDiagnostic(): String = NativeVolumeCapabilities.describe(context, volumeAdvertisement)
    private var preparedEndpoint = ""
    private var preparedServer = ""
    private var preparedLevel: Int? = null
    fun trustedVolumeServer(): String {
        val storage = store ?: ReceiverStore(context)
        val records = storage.listRecords()
        val previous = storage.text("musicVolumeServer").ifEmpty { storage.text("autoPairServer") }
        return records.singleOrNull { it.serverId == previous }?.serverId
            ?: records.singleOrNull()?.serverId ?: ""
    }
    fun seedMusicVolume(endpoint: String, server: String, level: Int) {
        if (running || server.isEmpty() || level !in 0..100) return
        preparedEndpoint = endpoint; preparedServer = server; preparedLevel = level
    }
    fun observeVolume(observer: Runnable) { volumeObserver = observer; linked = true; volumeGate = true; gain.volume(1f); applyGain() }
    fun linkedVolumeGate(hold: Boolean) { volumeGate = hold; applyGain() }
    /** Local player state only, never controller/group volume. Encrypted admitted player required. */
    fun reportMasterVolume(level: Int): Boolean = reportMasterVolume(level, false)
    fun reportMasterVolume(level: Int, unmute: Boolean): Boolean {
        val c = client ?: return false
        if (!running || !c.isConnected || !c.isPaired || !playerActive || level !in 0..100) return false
        return try {
            // The inherited protocol API truncates percent after a floating conversion.
            // Avoid 29 / 100 * 100 becoming 28.999... and reporting one step too low.
            c.setVolume((level + 0.000001) / 100.0)
            if (unmute && level > 0) {
                c.setMuted(false)
                muted = false
                engine?.setMuted(SyncAudioPlayer.MuteReason.PLAYER, false)
            }
            rememberVolume(level); publish(); true
        } catch (_: RuntimeException) { false }
    }
    private fun rememberVolume(level: Int) {
        val c = client ?: return
        if (!c.isConnected || !c.isPaired || !playerActive) return
        volume = level.coerceIn(0, 100); volumeServer = c.connectedServerId ?: ""; volumeRevision++
        // One atomic encrypted update, bound to the transport server and endpoint, not a device name.
        try { store?.saveMusicVolume(volumeServer, volumeEndpoint, volume!!) } catch (_: Exception) { status = "Music volume cache could not be saved" }
    }
    fun duckPercent(percent: Int) { gain.percent(percent); applyGain() }
    private val groupVoice = mutableSetOf<String>()
    fun voiceActivity(source: String, active: Boolean) { if (active) groupVoice.add(source) else groupVoice.remove(source); if (active) clearGroupVolumeRoute(); gain.interaction(source, active); applyGain() }
    private fun applyGain() {
        engine?.setTrackGain(if (linked && volumeGate) 0f else gain.gain())
        engine?.setMuted(SyncAudioPlayer.MuteReason.INTERRUPTION, gain.interrupted())
        if (gain.interrupted()) releaseWakeLock()
        else if (running && playback == "playing") takeWakeLock()
    }
    private var wake: PowerManager.WakeLock? = null
    private var session: android.media.session.MediaSession? = null
    private var groupVolumeProvider: android.media.VolumeProvider? = null
    fun groupVolumeRouted(): Boolean = groupVolumeProvider != null
    /** Android routes keys to this remote group, not through a local master-volume delta. */
    fun routeGroupVolume(level: Int, adjust: java.util.function.IntConsumer, set: java.util.function.IntConsumer): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val media = session ?: return false
        if (!running || !playerActive || playback != "playing" || groupVoice.isNotEmpty() || client?.isPaired != true) return false
        val existing = groupVolumeProvider
        if (existing != null) { existing.setCurrentVolume(level.coerceIn(0, 100)); return true }
        val provider = createGroupVolumeProvider(level, adjust, set)
        return try { media.setPlaybackToRemote(provider); groupVolumeProvider = provider; true }
        catch (_: RuntimeException) { false }
    }
    private fun createGroupVolumeProvider(level: Int, adjust: java.util.function.IntConsumer, set: java.util.function.IntConsumer): android.media.VolumeProvider =
        object : android.media.VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, level.coerceIn(0, 100)) {
            override fun onAdjustVolume(direction: Int) { main.post { if (groupVolumeProvider === this && running && playerActive && playback == "playing" && groupVoice.isEmpty()) adjust.accept(direction) } }
            override fun onSetVolumeTo(value: Int) { main.post { if (groupVolumeProvider === this && running && playerActive && playback == "playing" && groupVoice.isEmpty()) set.accept(value.coerceIn(0, 100)) } }
        }
    fun clearGroupVolumeRoute() {
        if (groupVolumeProvider == null) return
        groupVolumeProvider = null
        session?.setPlaybackToLocal(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
    }
    @Volatile private var running = false
    @Volatile private var muted = false
    private var failed = false
    private var retryable = false
    private var playerActive = false
    private var status = "Stopped"
    private var playback = "stopped"
    private var serverPlaybackKnown = false
    private var groupPlaybackKnown = false
    private var title = ""
    private var artist = ""
    @Volatile private var snapshot: Map<String, Any> = mapOf("status" to "Stopped", "running" to false)
    fun state(): Map<String, Any> = snapshot
    private var persistentId = ""
    @Synchronized fun persistentClientId(): String {
        if (persistentId.isEmpty()) persistentId = (store ?: ReceiverStore(context)).identity().clientId
        return persistentId
    }

    @Synchronized fun start(endpoint: String, name: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (running) return
        val parsed = ReceiverEndpoint.parse(endpoint)
        require(name.isNotBlank() && name.length <= 80)
        val ticket = ++epoch
        fun deliver(action: () -> Unit) { main.post { if (running && epoch == ticket) action() } }
        try {
            val storage = ReceiverStore(context).also { store = it }
            volumeEndpoint = endpoint
            volume = storage.musicVolume(endpoint)
            volumeServer = storage.text("musicVolumeServer")
            if (preparedEndpoint == endpoint && preparedLevel != null) {
                volume = preparedLevel; volumeServer = preparedServer
            }
            preparedLevel = null; preparedEndpoint = ""; preparedServer = ""
            volumeRevision++; volumeGate = true
            playback = "stopped"; serverPlaybackKnown = false; groupPlaybackKnown = false
            persistentId = storage.identity().clientId
            val jobs = CoroutineScope(SupervisorJob() + dispatcher).also { scope = it }
            failed = false; retryable = false
            volumeAdvertisement = ""
            running = true; status = "Connecting"; publish()
            val host = object : SendSpinHost {
                override val softwareVersion: String
                    get() = net.markandrew.kiosk.music.VolumeCapability.advertise(
                        "Music Assistant Plugin 1.0.2", NativeVolumeCapabilities.read(context)
                    ).also { volumeAdvertisement = it }
                override val lowMemoryMode = true
                override val highPowerMode = false
                override val serverColors = false
                override fun preferredCodec() = "pcm"
                override fun outputDelayMs() = storage.number("delay")
                override fun setOutputDelayMs(delayMs: Int) = storage.setNumber("delay", delayMs)
                override fun clientIdentity() = storage.identity()
                override fun trustStore(): TrustStore = storage
                override fun pairingConfigStore(): PairingConfigStore = storage
                override fun pairingCodeFailures() = storage.number("pairingFailures")
                override fun setPairingCodeFailures(value: Int) = storage.setNumber("pairingFailures", value)
                override fun rememberServerName(serverId: String, name: String) {}
                override fun forgetServerName(serverId: String) {}
                override fun onPairingOutcome(outcome: PairingOutcome) { deliver {
                    status = if (outcome is PairingOutcome.Paired) "Paired — awaiting activation" else "Pairing ended — check MA"
                    publish()
                } }
            }
            val audioEngine = SendSpinEngine(object : SendSpinEngine.Listener {
                override fun onStreamStarted() { deliver { takeFocus(); if (!gain.interrupted()) takeWakeLock() } }
                override fun onPlayerChanged(player: SyncAudioPlayer?) {}
                override fun onPlayerStateChanged(state: com.sendspindroid.sendspin.PlaybackState) { deliver {
                    // Stream-engine evidence is a fallback only, never an override of a
                    // server pause/stop. The transport already authorises every audio chunk.
                    if (!serverPlaybackKnown && state == com.sendspindroid.sendspin.PlaybackState.PLAYING &&
                        playerActive && client?.isConnected == true && client?.isPaired == true) {
                        acceptPlaybackState("playing", fromEngine = true)
                    } else publish()
                } }
            }, lowMemoryMode = { true }, playerMuted = { muted }, mainDispatcher = dispatcher)
            engine = audioEngine
            applyGain()
            val callbacks = createCallbacks(audioEngine, ticket)
            session = android.media.session.MediaSession(context, "MusicAssistantPlugin").apply {
                setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPlay() { control("play") }
                    override fun onPause() { control("pause") }
                    override fun onSkipToNext() { control("next") }
                    override fun onSkipToPrevious() { control("previous") }
                    override fun onStop() { stop() }
                }, main)
                isActive = true
            }
            val c = SendSpin(name.trim(), callbacks, host).also { client = it }
            // Never advertise an arbitrary 100% at registration. An unknown previous level
            // starts at zero and remains gated until a verified MA command supplies a level.
            c.setInitialVolume(volume ?: 0)
            engine?.client = c
            jobs.launch { c.connectionState.collect { transport ->
                if (!running || epoch != ticket) return@collect
                if (transport is com.sendspindroid.coordinator.TransportState.Failed) {
                    failed = true
                    retryable = transport.reason is com.sendspindroid.coordinator.FailureReason.TransientNetwork
                }
                status = when (transport) {
                    is com.sendspindroid.coordinator.TransportState.Ready -> "Connected"
                    is com.sendspindroid.coordinator.TransportState.Failed -> if (retryable) "Network connection lost" else "Secure connection failed — check server compatibility / trust"
                    else -> if (failed) status else "Connecting / pairing"
                }; publish()
            } }
            jobs.launch { c.controllerState.collect { if (running && epoch == ticket) publish() } }
            c.connect(parsed.address, parsed.path)
            jobs.launch {
                delay(45_000)
                if (!c.isConnected && running && epoch == ticket) {
                    c.disconnect(); engine?.releasePlayer(); releaseFocus()
                    failed = true; retryable = true; status = "Network connection timed out"; publish()
                }
            }
        } catch (_: Exception) {
            stop(); failed = true; retryable = false; status = "Receiver could not start — check endpoint and secure storage"; publish()
        }
    }

    private fun acceptPlaybackState(state: String, group: Boolean = false, fromEngine: Boolean = false) {
        if (!playerActive) return
        val next = when (state) { "playing", "paused", "stopped" -> state; "idle" -> "stopped"; else -> return }
        if (!group && !fromEngine && groupPlaybackKnown) return
        if (group) groupPlaybackKnown = true
        if (!fromEngine) serverPlaybackKnown = true
        playback = next
        if (!fromEngine) engine?.applyServerPlayback(when (next) {
            "playing" -> PlaybackStateType.PLAYING
            "paused" -> PlaybackStateType.PAUSED
            else -> PlaybackStateType.STOPPED
        })
        if (next == "playing") { takeFocus(); if (!gain.interrupted()) takeWakeLock() } else releaseFocus()
        publish()
    }

    /** The actual production transport callback, also exercised directly by receiver regressions. */
    private fun createCallbacks(audioEngine: SendSpinEngine, ticket: Long): SendSpin.Callback {
        fun deliver(action: () -> Unit) { main.post { if (running && epoch == ticket) action() } }
        return object : SendSpin.Callback {
                override fun onStateChanged(state: String) { deliver {
                    acceptPlaybackState(state)
                } }
                override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) { deliver {
                    if (groupId.isNotBlank()) acceptPlaybackState(playbackState, group = true)
                } }
                override fun onMetadataUpdate(metadata: TrackMetadata, positionMs: Long) { deliver {
                    title = metadata.title ?: ""; artist = metadata.artist ?: ""
                    session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, metadata.durationMs).build())
                    publish()
                } }
                override fun onArtwork(imageData: ByteArray) {}
                override fun onArtworkCleared() {}
                override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
                    if (running && epoch == ticket) audioEngine.onStreamStart(codec, sampleRate, channels, bitDepth, codecHeader)
                }
                override fun onStreamClear() { if (running && epoch == ticket) audioEngine.onStreamClear() }
                override fun onStreamEnd() { if (running && epoch == ticket) audioEngine.onStreamEnd(); deliver { releaseFocus() } }
                override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) { if (running && epoch == ticket) audioEngine.onAudioChunk(serverTimeMicros, audioData) }
                override fun onVolumeChanged(volume: Int) { deliver {
                    rememberVolume(volume)
                    if (!linked) gain.volume(volume.coerceIn(0, 100) / 100f)
                    applyGain(); publish()
                } }
                override fun onMutedChanged(muted: Boolean) { deliver {
                    this@PluginReceiver.muted = muted; audioEngine.setMuted(SyncAudioPlayer.MuteReason.PLAYER, muted)
                    publish()
                } }
                override fun onSyncOffsetApplied(offsetMs: Double, source: String) {}
                override fun onNetworkChanged() { deliver { audioEngine.onNetworkChanged() } }
                override fun onSyncMuteChanged(muted: Boolean) { deliver { audioEngine.onSyncMuteChanged(muted) } }
                override fun onAdmissionStateChanged(state: AdmissionState) { deliver { status = state.toString(); publish() } }
                override fun onPlayerRoleChanged(active: Boolean) { deliver {
                    playerActive = active
                    if (!active) { engine?.endStream(); engine?.releasePlayer(); releaseFocus(); playback = "stopped"; serverPlaybackKnown = false; groupPlaybackKnown = false }
                    publish()
                } }
                override fun onUnpaired(serverId: String?) { deliver { status = "Unpaired by server"; publish() } }
                override fun onDisconnected(reconnect: Boolean) { deliver {
                    volumeAdvertisement = ""
                    engine?.endStream(); engine?.releasePlayer(); releaseFocus()
                    playerActive = false; playback = "stopped"; serverPlaybackKnown = false; groupPlaybackKnown = false; status = "Disconnected"; publish()
                } }
            }
    }

    /** Only displayed by an explicit user action; never saved in plugin settings or logs. */
    fun pairingToken(): String {
        check(running)
        val storage = checkNotNull(store)
        return PairingToken.encode(storage.identity().publicKey, storage.load().pairingPsk)
    }
    /** Reserve once in encrypted storage BEFORE any MA pairing mutation. Survives reconnect/restart. */
    @Synchronized fun claimAutoPair(clientId: String, serverId: String): Boolean {
        val c = client ?: return false
        val storage = store ?: return false
        if (!running || !c.isConnected || c.isPaired || serverId.isEmpty() ||
            c.connectedServerId != serverId || storage.identity().clientId != clientId ||
            storage.text("autoPairServer") == serverId) return false
        storage.setText("autoPairServer", serverId)
        return true
    }
    @Synchronized fun privateToken(clientId: String, serverId: String): String {
        val c = checkNotNull(client)
        check(running && c.isConnected && !c.isPaired && c.connectedServerId == serverId)
        check(store?.identity()?.clientId == clientId)
        return pairingToken()
    }
    @Synchronized fun resetPairingAttempt() { store?.setText("autoPairServer", "") }
    fun control(command: String): Boolean {
        if (command !in setOf("play", "pause", "next", "previous")) return false
        val c = client ?: return false
        if (!running || !c.isConnected || !c.canSendCommand(command)) return false
        c.sendCommand(command); return true
    }
    private fun takeFocus() {
        if (focus != null) return
        val ticket = epoch
        val focusTicket = ++focusSerial
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            // Receive CAN_DUCK ourselves, avoiding Android attenuation on top of our gain.
            .setWillPauseWhenDucked(true)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                if (!running || epoch != ticket || focusSerial != focusTicket) return@setOnAudioFocusChangeListener
                gain.focus(change); applyGain()
            }, main).build()
        focus = request
        gain.focus(if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        applyGain()
    }
    private fun takeWakeLock() {
        if (wake?.isHeld == true) return
        wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "musicassistantplugin:audio").apply { acquire(30 * 60 * 1000L) }
        val ticket = epoch
        main.postDelayed({ if (running && epoch == ticket && playback == "playing") {
            releaseWakeLock(); takeWakeLock()
        } }, 15 * 60 * 1000L)
    }
    private fun releaseWakeLock() { wake?.let { if (it.isHeld) it.release() }; wake = null }
    private fun releaseFocus() {
        focusSerial++
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null; releaseWakeLock()
        gain.focus(AudioManager.AUDIOFOCUS_GAIN)
    }
    private fun publish() {
        if (playback != "playing" || !playerActive) clearGroupVolumeRoute()
        snapshot = mapOf("running" to running, "status" to status, "title" to title, "artist" to artist,
            "playback" to playback, "connected" to (client?.isConnected == true),
            "paired" to (client?.isPaired == true), "clientId" to (store?.identity()?.clientId ?: ""),
            "serverId" to (client?.connectedServerId ?: ""), "serial" to epoch,
            "failed" to failed, "retryable" to retryable, "active" to playerActive,
            "volume" to (if (volumeServer == client?.connectedServerId) volume ?: -1 else -1),
            "volumeRevision" to volumeRevision, "muted" to muted)
        volumeObserver?.run()
        val commands = listOf("play", "pause", "next", "previous").filter { client?.canSendCommand(it) == true }
        var actions = android.media.session.PlaybackState.ACTION_STOP
        if ("play" in commands) actions = actions or android.media.session.PlaybackState.ACTION_PLAY
        if ("pause" in commands) actions = actions or android.media.session.PlaybackState.ACTION_PAUSE
        if ("next" in commands) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
        if ("previous" in commands) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session?.setPlaybackState(android.media.session.PlaybackState.Builder().setActions(actions).setState(
            if (playback == "playing") android.media.session.PlaybackState.STATE_PLAYING
            else if (playback == "paused") android.media.session.PlaybackState.STATE_PAUSED
            else android.media.session.PlaybackState.STATE_STOPPED,
            android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
    }
    @Synchronized fun stop() {
        volumeAdvertisement = ""
        groupVolumeProvider = null
        // Kiosk calls plugin.stop on its session worker. Audio-engine teardown belongs on main.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            running = false; epoch++; main.post { stop() }; return
        }
        running = false; epoch++
        val old = client; client = null
        old?.disconnect(); old?.destroy()
        engine?.release(); engine = null
        scope?.cancel(); scope = null
        main.removeCallbacksAndMessages(null)
        releaseFocus(); session?.release(); session = null
        title = ""; artist = ""; playback = "stopped"; muted = false; status = "Stopped"; store = null
        failed = false; retryable = false; playerActive = false; serverPlaybackKnown = false; groupPlaybackKnown = false; publish()
    }
}
