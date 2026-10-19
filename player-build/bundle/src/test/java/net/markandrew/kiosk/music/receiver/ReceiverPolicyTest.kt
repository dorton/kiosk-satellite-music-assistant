package net.markandrew.kiosk.music.receiver

import org.junit.Assert.*
import org.junit.Test

class ReceiverPolicyTest {
    @Test fun oneServerSelectedAutomatically() { assertEquals("one", ReceiverPolicy.select(listOf("one"), "")) }
    @Test fun multipleNeedChoice() { assertNull(ReceiverPolicy.select(listOf("one", "two"), "")) }
    @Test fun rememberedWinsAmongMultiple() { assertEquals("two", ReceiverPolicy.select(listOf("one", "two"), "two")) }
    @Test fun missingRememberedDoesNotSwitch() { assertNull(ReceiverPolicy.select(listOf("one"), "two")) }
    @Test fun emptyWaits() { assertNull(ReceiverPolicy.select(emptyList(), "")) }
    @Test fun backoffBounded() {
        assertEquals(2000L, ReceiverPolicy.retryDelay(1))
        assertEquals(4000L, ReceiverPolicy.retryDelay(2))
        assertEquals(60000L, ReceiverPolicy.retryDelay(100))
    }
}
