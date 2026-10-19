package com.sendspindroid.playback

import com.sendspindroid.sendspin.*
import com.sendspindroid.sendspin.crypto.*
import com.sendspindroid.sendspin.decoder.AudioDecoderFactory
import com.sendspindroid.sendspin.transport.SendSpinTransport
import io.mockk.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CompatHostInteropTest {
    @Test(timeout = 30000) fun actualHostPairsPromotesAndDeliversTimestampedAudioWithoutOutput() = exchange(false)
    @Test(timeout = 30000) fun encryptedUnpairedStreamIsRejected() = exchange(true)

    private fun exchange(deny: Boolean) {
        val python = System.getenv("SENDSPIN_INTEROP_PYTHON")
        val script = System.getenv("SENDSPIN_INTEROP_SCRIPT")
        assumeTrue(python != null && script != null)
        val process = ProcessBuilder(python!!, script!!, if (deny) "host-denied" else "host").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val writer = process.outputStream.bufferedWriter()
        val records = mutableListOf<PskRecord>()
        val host = mockk<SendSpinHost>(relaxed = true)
        val trust = mockk<TrustStore>(relaxed = true)
        val config = mockk<PairingConfigStore>()
        every { config.load() } returns PairingConfig(ByteArray(32) { 7 }, true, false, false, "")
        every { host.clientIdentity() } returns ClientIdentity(ByteArray(32) { 4 })
        every { host.trustStore() } returns trust
        every { host.pairingConfigStore() } returns config
        every { host.preferredCodec() } returns "pcm"
        every { host.softwareVersion } returns "offline-test"
        every { trust.listRecords() } answers { records.toList() }
        every { trust.addRecord(any(), any()) } answers {
            val record = PskRecord(PskId.derive(firstArg()), firstArg(), secondArg())
            records.add(record); TrustStore.AddRecordResult.Ok(record)
        }
        val audio = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val delayApplied = CountDownLatch(1)
        every { host.setOutputDelayMs(150) } answers { delayApplied.countDown(); Unit }
        val callback = mockk<SendSpin.Callback>(relaxed = true)
        every { callback.onAudioChunk(2000000L, any()) } answers { audio.countDown(); Unit }
        every { callback.onDisconnected(any()) } answers { disconnected.countDown(); Unit }
        val socket = object : SendSpinTransport {
            @Volatile var observer: SendSpinTransport.Listener? = null
            override val state = com.sendspindroid.sendspin.transport.TransportState.Connected
            private fun sendFrame(kind: String, data: String): Boolean = synchronized(writer) {
                writer.write(buildJsonObject { put("kind", kind); put("data", data) }.toString());writer.newLine();writer.flush();true
            }
            override fun connect() {
                observer?.onConnected()
                Thread {
                    try {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            val frame = Json.parseToJsonElement(line).jsonObject
                            when (frame["kind"]?.jsonPrimitive?.content) {
                                "text" -> { val text = frame.getValue("data").jsonPrimitive.content; observer?.onMessage(text, text.toByteArray()) }
                                "binary" -> observer?.onMessage(checkNotNull(Base64Url.decodeOrNull(frame.getValue("data").jsonPrimitive.content)))
                                "done" -> complete.countDown()
                            }
                        }
                    } catch (_: Exception) { complete.countDown() }
                }.apply { isDaemon = true; start() }
            }
            override fun send(text: String) = sendFrame("text", text)
            override fun send(bytes: ByteArray) = sendFrame("binary", Base64Url.encode(bytes))
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
            override fun setListener(listener: SendSpinTransport.Listener?) { observer = listener }
        }
        mockkObject(AudioDecoderFactory)
        every { AudioDecoderFactory.isCodecSupported(any()) } answers { firstArg<String>() == "pcm" }
        val client = SendSpin("offline host", callback, host) { _, _ -> socket }
        try {
            client.connect("example.invalid:8927")
            assertTrue("Python peer completed", complete.await(15, TimeUnit.SECONDS))
            if (deny) {
                assertTrue("Unauthorized stream closes connection", disconnected.await(5, TimeUnit.SECONDS))
                assertEquals(0, records.size)
                verify(exactly = 0) { callback.onStreamStart(any(), any(), any(), any(), any()) }
                verify(exactly = 0) { callback.onAudioChunk(any(), any()) }
                assertEquals(0, process.waitFor())
                return
            }
            assertTrue("Timestamped audio reached callback", audio.await(5, TimeUnit.SECONDS))
            assertTrue("Server static-delay command applied", delayApplied.await(5, TimeUnit.SECONDS))
            assertEquals(0, process.waitFor())
            assertEquals(1, records.size)
            assertEquals(ClientIdentity(ByteArray(32) { 3 }).clientId, records.single().serverId)
            verify(exactly = 1) { callback.onStreamStart("pcm", 48000, 2, 16, null) }
            verify(exactly = 1) { callback.onAudioChunk(2000000L, match { it.contentEquals(byteArrayOf(0,1,2,3)) }) }
        } finally { client.destroy(); process.destroyForcibly(); unmockkObject(AudioDecoderFactory) }
    }
}
