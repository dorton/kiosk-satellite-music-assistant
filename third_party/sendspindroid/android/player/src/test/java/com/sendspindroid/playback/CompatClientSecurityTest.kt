package com.sendspindroid.playback

import com.sendspindroid.coordinator.TransportState
import com.sendspindroid.coordinator.FailureReason
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SendSpinHost
import com.sendspindroid.sendspin.crypto.*
import com.sendspindroid.sendspin.transport.SendSpinTransport
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CompatClientSecurityTest {
    @Test fun legacyServerCannotTriggerPlaintextFallbackOrRetry() {
        val config = mockk<PairingConfigStore>()
        every { config.load() } returns PairingConfig(ByteArray(32) { 7 }, true, false, false, "")
        val trust = mockk<TrustStore>()
        every { trust.listRecords() } returns emptyList()
        val host = mockk<SendSpinHost>()
        every { host.outputDelayMs() } returns 0
        every { host.lowMemoryMode } returns false
        every { host.clientIdentity() } returns ClientIdentity(ByteArray(32) { 4 })
        every { host.trustStore() } returns trust
        every { host.pairingConfigStore() } returns config
        val finished = CountDownLatch(1)
        val callback = mockk<SendSpin.Callback>(relaxed = true)
        every { callback.onDisconnected(any()) } answers { finished.countDown(); Unit }
        val sent = mutableListOf<String>()
        var attempts = 0
        val socket = object : SendSpinTransport {
            var observer: SendSpinTransport.Listener? = null
            override val state = com.sendspindroid.sendspin.transport.TransportState.Connected
            override fun connect() {
                observer?.onConnected()
                val raw = "{\"type\":\"server/hello\",\"payload\":{\"name\":\"legacy\"}}"
                observer?.onMessage(raw, raw.toByteArray())
            }
            override fun send(text: String): Boolean { sent.add(text); return true }
            override fun send(bytes: ByteArray) = true
            override fun close(code: Int, reason: String) {}
            override fun destroy() {}
            override fun setListener(listener: SendSpinTransport.Listener?) { observer = listener }
        }
        val client = SendSpin("test", callback, host) { _, _ -> attempts++; socket }
        try {
            assertEquals(0, attempts)
            client.connect("example.invalid:8927")
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(FailureReason.ServerLacksEncryption, (client.connectionState.value as TransportState.Failed).reason)
            assertEquals(1, attempts)
            assertEquals(1, sent.size)
            assertTrue(sent.single().contains("client/init"))
            verify(exactly = 0) { callback.onStreamStart(any(), any(), any(), any(), any()) }
        } finally { client.destroy() }
    }
}
