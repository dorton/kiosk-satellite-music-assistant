package com.sendspindroid.sendspin

import android.os.Build
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.sendspin.crypto.*
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.pairing.PairingOutcome
import com.sendspindroid.sendspin.protocol.*
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import com.sendspindroid.sendspin.transport.SendSpinTransport
import com.sendspindroid.sendspin.transport.WebSocketTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors

/** Host adapter for the pinned pre-rc1 encrypted protocol. No legacy plaintext path or retry. */
class SendSpin(private val deviceName: String, private val callback: Callback, private val host: SendSpinHost,
    private val newTransport: (String, String) -> SendSpinTransport = { address, path -> WebSocketTransport(address, path) }) :
    SendSpinProtocolHandler("SendSpinCompat") {
    interface Callback {
        fun onStateChanged(state: String)
        fun onGroupUpdate(groupId: String, groupName: String, playbackState: String)
        fun onMetadataUpdate(metadata: TrackMetadata, positionMs: Long)
        fun onArtwork(imageData: ByteArray)
        fun onArtworkCleared()
        fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?)
        fun onStreamClear()
        fun onStreamEnd()
        fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray)
        fun onVolumeChanged(volume: Int)
        fun onMutedChanged(muted: Boolean)
        fun onSyncOffsetApplied(offsetMs: Double, source: String)
        fun onNetworkChanged()
        fun onSyncMuteChanged(muted: Boolean) {}
        fun onUnpaired(serverId: String?) {}
        fun onAdmissionStateChanged(state: AdmissionState) {}
        fun onPlayerRoleChanged(active: Boolean) {}
        fun onDisconnected(reconnect: Boolean) {}
    }

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "SendspinCompat").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = MutableStateFlow<TransportState>(TransportState.Idle)
    val connectionState = state.asStateFlow()
    private val controller = MutableStateFlow<ControllerState?>(null)
    val controllerState = controller.asStateFlow()
    private val timeFilter = SendspinTimeFilter().apply { setOutputDelayMs(host.outputDelayMs().toDouble()) }
    @Volatile private var transport: SendSpinTransport? = null
    private var driver: SendSpinHandshakeDriver? = null
    private var facts: SendSpinHandshakeDriver.Event.TransportReady? = null
    private var currentHash: ByteArray? = null
    private var matched: Psk? = null
    private var timeout: Job? = null
    private var watchdog: Job? = null
    private var teardown: Job? = null
    private var receivedAt = System.nanoTime()
    @Volatile private var closing = false
    private var destroying = false
    val isConnected get() = state.value is TransportState.Ready
    val isPaired get() = isEncrypted && matched?.category == PskCategory.LONG_TERM
    /** Read-only authenticated handshake identity for exact-server auto-registration. */
    val connectedServerId get() = if (isConnected) facts?.serverInit?.serverId else null

    init { initTimeSyncManager(timeFilter) }
    private fun candidates() = PskCandidateSet.of(PskCandidates.build(host.trustStore().listRecords(), host.pairingConfigStore().load())).getOrThrow()

    fun connect(address: String, path: String = "/sendspin") {
        check(transport == null && !closing && !destroying)
        state.value = TransportState.Connecting
        val socket = newTransport(address, path)
        transport = socket
        socket.setListener(object : SendSpinTransport.Listener {
            private fun accept(block: () -> Unit) { scope.launch { if (transport === socket && !closing) block() } }
            override fun onConnected() { accept {
                receivedAt = System.nanoTime()
                val handshake = SendSpinHandshakeDriver(host.clientIdentity(), candidates(), onEvent = ::handshakeEvent)
                driver = handshake; handshake.start()
                timeout = scope.launch { delay(30_000); if (!isConnected) fail(FailureReason.HandshakeFailed) }
            } }
            override fun onMessage(text: String) { accept { fail(FailureReason.ProtocolError) } }
            override fun onMessage(text: String, rawUtf8: ByteArray) { accept {
                receivedAt = System.nanoTime()
                if (isEncrypted) fail(FailureReason.ProtocolError) else driver?.onCleartextFrame(rawUtf8)
            } }
            override fun onMessage(bytes: ByteArray) { accept {
                receivedAt = System.nanoTime()
                if (isEncrypted) handleBinaryMessage(bytes) else fail(FailureReason.HandshakeFailed)
            } }
            override fun onClosing(code: Int, reason: String) {}
            override fun onClosed(code: Int, reason: String) { accept { fail(FailureReason.TransientNetwork) } }
            override fun onFailure(error: Throwable, isRecoverable: Boolean) { accept { fail(FailureReason.TransientNetwork) } }
        })
        socket.connect()
    }

    private fun handshakeEvent(event: SendSpinHandshakeDriver.Event) {
        when (event) {
            is SendSpinHandshakeDriver.Event.SendCleartext -> if (transport?.send(event.text) != true) fail(FailureReason.TransientNetwork)
            is SendSpinHandshakeDriver.Event.Fail -> fail(if (event.reason == NoiseHandshakeException.Cause.ServerLacksEncryption)
                FailureReason.ServerLacksEncryption else FailureReason.HandshakeFailed)
            is SendSpinHandshakeDriver.Event.TransportReady -> {
                facts = event; matched = event.matchedPsk; currentHash = event.transport.handshakeHash
                if (matched?.category == PskCategory.LONG_TERM) host.trustStore().markUsed(event.matchedPsk.pskId)
                installEncryptedTransport(event.transport)
                sendClientHello()
            }
        }
    }

    private fun fail(reason: FailureReason) {
        if (closing || state.value is TransportState.Failed) return
        state.value = TransportState.Failed(reason)
        timeout?.cancel(); watchdog?.cancel(); stopTimeSync()
        val socket = transport; transport = null
        socket?.setListener(null); socket?.close(1002, "connection ended"); socket?.destroy()
        clearEncryptedTransport(); controller.value = null
        callback.onDisconnected(false)
    }

    fun disconnect() {
        if (closing) return
        closing = true; timeout?.cancel(); watchdog?.cancel(); stopTimeSync()
        val socket = transport; transport = null; socket?.setListener(null)
        state.value = TransportState.Idle; controller.value = null
        teardown = scope.launch {
            try {
                // Keep the socket available to the encoder until goodbye is queued.
                if (socket != null && isEncrypted && handshakeComplete) {
                    farewellSocket = socket
                    withTimeoutOrNull(500) { sendProtocolMessageAwaiting(MessageBuilder.buildGoodbye(GoodbyeReason.USER_REQUEST)) }
                    farewellSocket = null
                }
                socket?.closeAfterFlush(1000, "user_request")
                delay(300)
            } finally { farewellSocket = null; socket?.destroy(); clearEncryptedTransport() }
        }
    }
    private var farewellSocket: SendSpinTransport? = null
    fun destroy() {
        if (destroying) return
        destroying = true; disconnect()
        scope.launch { teardown?.join(); scope.cancel(); dispatcher.close() }
    }

    // Base class retains a legacy mode for upstream tests. This host forbids its use.
    override fun sendTextMessage(text: String) { fail(FailureReason.ProtocolError) }
    override fun sendBinaryFrame(bytes: ByteArray) {
        if ((transport ?: farewellSocket)?.send(bytes) != true && !closing) fail(FailureReason.TransientNetwork)
    }
    override fun getCoroutineScope() = scope
    override fun getTimeFilter() = timeFilter
    override fun isLowMemoryMode() = host.lowMemoryMode
    override fun getClientId() = host.clientIdentity().clientId
    override fun getDeviceName() = deviceName
    override fun getManufacturer() = Build.MANUFACTURER ?: "Unknown"
    override fun getSoftwareVersion() = host.softwareVersion
    override fun persistOutputDelayMs(delayMs: Int) = host.setOutputDelayMs(delayMs)
    override fun getSupportedFormats() = MessageBuilder.buildSupportedFormats(host.preferredCodec(),
        { AudioDecoderFactory.isCodecSupported(it) }, listOf(16))
    override fun getTrustLevel() = if (matched?.category == PskCategory.LONG_TERM) MessageBuilder.TRUST_USER else MessageBuilder.TRUST_NONE
    override fun isUnpairedAccessEnabled() = false
    override fun matchedPskCategory() = matched?.category ?: PskCategory.SENTINEL
    override fun matchedPsk() = matched
    override fun offeredPairMethods() = setOf("pairing_psk")
    override fun getSupportedPairMethods() = listOf(MessageBuilder.PairMethodDescriptor.PAIRING_PSK)
    override fun pairingConfigStore() = host.pairingConfigStore()
    override fun trustStore() = host.trustStore()
    override fun currentServerId() = facts?.serverInit?.serverId
    override fun currentHandshakeHash() = currentHash
    override fun negotiatedCipherSuite() = SendSpinHandshakeDriver.DEFAULT_SUITE
    override fun onPaired(serverId: String) { host.onPairingOutcome(PairingOutcome.Paired("Sendspin server")) }
    override fun onUnpaired(pskId: String, serverId: String?) { callback.onUnpaired(serverId) }
    override fun onPairAbort(reason: String) { host.onPairingOutcome(PairingOutcome.Aborted("pairing ended", false, "Sendspin server")) }
    override fun closeConnectionAfterFlush() { disconnect() }
    override fun onProtocolFailure(reason: String) { fail(FailureReason.ProtocolError) }
    override fun onRehandshakeMessage(payload: JsonObject?) {
        val server = facts ?: return fail(FailureReason.ProtocolError)
        val hash = currentHash ?: return fail(FailureReason.ProtocolError)
        val outcome = RehandshakeDriver(host.clientIdentity(), candidates(), server.serverInit.serverId,
            server.serverInit.serverStaticKey, SendSpinHandshakeDriver.DEFAULT_SUITE, hash)
            .handle(payload?.get("data")?.jsonPrimitive?.contentOrNull)
        if (outcome !is RehandshakeDriver.Outcome.Reply) return fail(FailureReason.HandshakeFailed)
        sendAndSwapKeys(outcome.replyJson, outcome.transport.asNoiseCrypto()) {
            currentHash = outcome.transport.handshakeHash; matched = outcome.matched
            if (outcome.matched.category == PskCategory.LONG_TERM) host.trustStore().markUsed(outcome.matched.pskId)
            resetForRehandshake()
            sendClientHello()
        }
    }
    override fun onHandshakeComplete(serverName: String, serverId: String) {
        timeout?.cancel(); state.value = TransportState.Ready
        watchdog?.cancel(); watchdog = scope.launch {
            while (isActive) { delay(3_000)
                if (activeRoles.contains("player@v1") && System.nanoTime() - receivedAt > 20_000_000_000L)
                    fail(FailureReason.TransientNetwork)
            }
        }
    }
    fun canSendCommand(command: String) = isConnected && isEncrypted && activeRoles.contains("controller@v1") &&
        controller.value?.supportedCommands?.contains(command) == true
    override fun onControllerStateUpdate(state: ControllerState) { controller.value = state }
    override fun onAdmissionStateChanged(state: AdmissionState) {
        callback.onAdmissionStateChanged(state); callback.onPlayerRoleChanged(activeRoles.contains("player@v1"))
    }
    override fun onMetadataUpdate(metadata: TrackMetadata) { callback.onMetadataUpdate(metadata, metadata.positionMs) }
    override fun onPlaybackStateChanged(state: String) { callback.onStateChanged(state) }
    override fun onVolumeCommand(volume: Int) { callback.onVolumeChanged(volume) }
    override fun onMuteCommand(muted: Boolean) { callback.onMutedChanged(muted) }
    override fun onGroupUpdate(info: GroupInfo) { callback.onGroupUpdate(info.groupId, info.groupName, info.playbackState) }
    private fun playbackAuthorized() = isEncrypted && handshakeComplete &&
        matched?.category == PskCategory.LONG_TERM && activeRoles.contains("player@v1")
    override fun onStreamStart(config: StreamConfig) {
        if (!playbackAuthorized()) return fail(FailureReason.ProtocolError)
        callback.onStreamStart(config.codec, config.sampleRate, config.channels, config.bitDepth, config.codecHeader)
    }
    override fun onStreamClear() { callback.onStreamClear() }
    override fun onStreamEnd() { callback.onStreamEnd() }
    override fun onAudioChunk(timestampMicros: Long, audioData: ByteArray) {
        if (!playbackAuthorized()) return fail(FailureReason.ProtocolError)
        callback.onAudioChunk(timestampMicros, audioData)
    }
    override fun onArtwork(channel: Int, payload: ByteArray) { if (payload.isEmpty()) callback.onArtworkCleared() else callback.onArtwork(payload) }
    override fun onSyncOffsetApplied(offsetMs: Double, source: String) { callback.onSyncOffsetApplied(offsetMs, source) }
    override fun onSyncMuteChanged(muted: Boolean) { callback.onSyncMuteChanged(muted) }
}
