package net.markandrew.kiosk.music.receiver

import org.junit.Assert.*
import org.junit.Test

class ReceiverEndpointTest {
    @Test fun explicitEndpoint() {
        assertEquals(ReceiverEndpoint("server.local:8927", "/sendspin"), ReceiverEndpoint.parse("ws://server.local:8927/sendspin"))
    }
    @Test fun ipv6Endpoint() {
        assertEquals("[::1]:8927", ReceiverEndpoint.parse("ws://[::1]:8927/sendspin").address)
    }
    @Test fun rejectsAmbiguousOrCredentialEndpoints() {
        for (value in listOf("", "server.local", "http://host:8927/sendspin", "wss://host:8927/sendspin",
            "ws://host/sendspin", "ws://host:0/sendspin", "ws://host:8927/", "ws://user:password@host:8927/sendspin",
            "ws://host:8927/sendspin?token=test", "ws://host:8927/sendspin#x")) {
            try { ReceiverEndpoint.parse(value); fail("Accepted an invalid endpoint") }
            catch (_: IllegalArgumentException) {} catch (_: java.net.URISyntaxException) {}
        }
    }
}
