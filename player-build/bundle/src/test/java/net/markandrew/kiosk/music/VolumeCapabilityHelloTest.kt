package net.markandrew.kiosk.music

import com.sendspindroid.sendspin.protocol.message.MessageBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [30])
class VolumeCapabilityHelloTest {
    private fun hello(software: String): JSONObject = JSONObject(MessageBuilder.buildClientHello(
        clientId = null, deviceName = "Custom kiosk", bufferCapacity = 1024,
        manufacturer = "Fixture", supportedFormats = listOf(MessageBuilder.FormatEntry("pcm", 48000, 2, 16)),
        lowMemoryMode = true, softwareVersion = software,
        trustLevel = MessageBuilder.TRUST_USER, unpairedAccessEnabled = false
    ))
    @Test fun realHelloCarriesConventionOnlyInDescriptiveSoftwareField() {
        val version = "Music Assistant Plugin 0.9.52-dev"
        val plain = hello(version)
        val tagged = hello(VolumeCapability.advertise(version, VolumeCapability.range(0, 15)))
        val payload = tagged.getJSONObject("payload")
        assertFalse(payload.has("client_id"))
        assertEquals("Custom kiosk", payload.getString("name"))
        val device = payload.getJSONObject("device_info")
        val range = VolumeCapability.fromPlayer(JSONObject().put("device_info", device))
        assertNotNull(range)
        assertEquals(15, range!!.max)
        device.put("software_version", version)
        assertEquals(plain.toString(), tagged.toString())
    }
}
