package com.sendspindroid.sendspin.protocol

import com.sendspindroid.sendspin.crypto.*
import com.sendspindroid.sendspin.pairing.*
import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Actual Python library peer over pipes: no live Music Assistant connection. */
class Aiosendspin911InteropTest {
    @Test(timeout = 30000) fun playerStatusMessagesValidateAgainstRealServerModel() {
        val python = System.getenv("SENDSPIN_INTEROP_PYTHON")
        val script = System.getenv("SENDSPIN_INTEROP_SCRIPT")
        assumeTrue(python != null && script != null)
        val process = ProcessBuilder(python!!, script!!, "validate-state")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            process.outputStream.bufferedWriter().use { output ->
                for (available in listOf(false, true)) for (active in listOf(false, true)) {
                    output.write(MessageBuilder.buildPlayerState(100, false, available, playerRoleActive = active))
                    output.newLine()
                }
            }
            assertEquals("validated", process.inputStream.bufferedReader().readLine())
            assertEquals(0, process.waitFor())
        } finally { process.destroyForcibly() }
    }
    @Test(timeout = 30000) fun sentinelEncryptedHandshakeAndFraming() = exchange(false)
    @Test(timeout = 30000) fun tokenPairingPromotionAndFraming() = exchange(true)

    private fun exchange(pairing: Boolean) = runBlocking {
        val python = System.getenv("SENDSPIN_INTEROP_PYTHON")
        val script = System.getenv("SENDSPIN_INTEROP_SCRIPT")
        assumeTrue("Interop environment not configured", python != null && script != null)
        val process = ProcessBuilder(python!!, script!!, if (pairing) "pairing" else "sentinel")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val input = process.inputStream.bufferedReader()
            val output = process.outputStream.bufferedWriter()
            fun send(kind: String, data: String) {
                output.write(buildJsonObject { put("kind", kind); put("data", data) }.toString())
                output.newLine(); output.flush()
            }
            fun receive(): JsonObject = Json.parseToJsonElement(input.readLine() ?: error("Interop peer closed")).jsonObject
            val identity = ClientIdentity(ByteArray(32) { 4 })
            val pairingKey = ByteArray(32) { 7 }
            val initial = if (pairing) PskCandidateSet.of(listOf(Psk(pairingKey, PskCategory.PAIRING))).getOrThrow()
                else PskCandidateSet.sentinelOnly()
            var ready: SendSpinHandshakeDriver.Event.TransportReady? = null
            val driver = SendSpinHandshakeDriver(identity, initial, onEvent = {
                when (it) {
                    is SendSpinHandshakeDriver.Event.SendCleartext -> send("text", it.text)
                    is SendSpinHandshakeDriver.Event.TransportReady -> ready = it
                    is SendSpinHandshakeDriver.Event.Fail -> error("Encrypted handshake failed: ${it.reason}")
                }
            })
            driver.start()
            repeat(2) { driver.onCleartextFrame(receive().getValue("data").jsonPrimitive.content.encodeToByteArray()) }
            val established = checkNotNull(ready)
            val codec = NoiseWireCodec(established.transport)
            fun frame(): NoiseWireCodec.Decoded {
                val envelope = receive()
                check(envelope.getValue("kind").jsonPrimitive.content == "binary")
                return codec.decode(checkNotNull(Base64Url.decodeOrNull(envelope.getValue("data").jsonPrimitive.content)))
            }
            suspend fun sendJson(text: String) { codec.encodeJson(text).forEach { send("binary", Base64Url.encode(it)) } }
            if (pairing) {
                val activate = frame() as NoiseWireCodec.Decoded.Json
                assertEquals("server/activate", Json.parseToJsonElement(activate.text).jsonObject["type"]?.jsonPrimitive?.content)
                val flow = PairingPskFlow()
                val request = flow.onEvent(PairingEvent.PairingActivation("pairing_psk", established.matchedPsk.category))
                    .filterIsInstance<PairingAction.SendPairFinalize>().single()
                sendJson(buildJsonObject { put("type", "client/pair-finalize"); put("payload", buildJsonObject {
                    put("long_term_psk", Base64Url.encode(request.longTermPsk))
                }) }.toString())
                val ack = frame() as NoiseWireCodec.Decoded.Json
                assertEquals("server/pair-finalize", Json.parseToJsonElement(ack.text).jsonObject["type"]?.jsonPrimitive?.content)
                val persisted = flow.onEvent(PairingEvent.ServerPairFinalize).filterIsInstance<PairingAction.PersistRecord>().single()
                val candidate = Psk(persisted.psk, PskCategory.LONG_TERM, established.serverInit.serverId)
                val hs1 = Json.parseToJsonElement((frame() as NoiseWireCodec.Decoded.Json).text).jsonObject
                val promotion = RehandshakeDriver(identity, PskCandidateSet.of(listOf(candidate)).getOrThrow(),
                    established.serverInit.serverId, established.serverInit.serverStaticKey,
                    SendSpinHandshakeDriver.DEFAULT_SUITE, established.transport.handshakeHash)
                    .handle(hs1["payload"]?.jsonObject?.get("data")?.jsonPrimitive?.content) as RehandshakeDriver.Outcome.Reply
                codec.encodeAndSwap(SendSpinProtocol.BinaryType.JSON, promotion.replyJson.encodeToByteArray(),
                    promotion.transport.asNoiseCrypto()).forEach { send("binary", Base64Url.encode(it)) }
                assertEquals(PskCategory.LONG_TERM, promotion.matched.category)
            }
            assertEquals("server/hello", Json.parseToJsonElement((frame() as NoiseWireCodec.Decoded.Json).text)
                .jsonObject["type"]?.jsonPrimitive?.content)
            val probe = buildJsonObject { put("type", "client/test"); put("payload", buildJsonObject { put("probe", "x".repeat(100000)) }) }.toString()
            sendJson(probe)
            var decoded: NoiseWireCodec.Decoded
            do { decoded = frame() } while (decoded is NoiseWireCodec.Decoded.Buffered)
            assertEquals(probe, (decoded as NoiseWireCodec.Decoded.Json).text)
            do { decoded = frame() } while (decoded is NoiseWireCodec.Decoded.Buffered)
            val audio = decoded as NoiseWireCodec.Decoded.Typed
            assertEquals(4, audio.type)
            assertArrayEquals(ByteArray(102400) { (it % 256).toByte() }, audio.body)
            assertTrue(receive().getValue("ok").jsonPrimitive.boolean)
            assertEquals(0, process.waitFor())
        } finally { process.destroyForcibly() }
    }
}
