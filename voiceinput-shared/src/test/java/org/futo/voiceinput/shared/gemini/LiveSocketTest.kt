package org.futo.voiceinput.shared.gemini

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSocketTest {

    @Test
    fun `text frame round-trips mask`() {
        val frame = LiveSocket.encodeTextFrame("hi")
        assertEquals(0x81.toByte(), frame[0])
        assertEquals((0x80 or 2).toByte(), frame[1])
        val mask = frame.copyOfRange(2, 6)
        val masked = frame.copyOfRange(6, 8)
        val plain = ByteArray(2) { i -> (masked[i].toInt() xor mask[i % 4].toInt()).toByte() }
        assertEquals("hi", String(plain, Charsets.UTF_8))
    }

    @Test
    fun `close payload carries code and reason`() {
        val payload = LiveSocket.closePayload(1001, "bye")
        assertEquals(1001, ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF))
        assertEquals("bye", String(payload, 2, payload.size - 2, Charsets.UTF_8))
    }

    @Test
    fun `handshake errors map safely`() {
        assertTrue(LiveSocket.mapHandshakeError("HTTP/1.1 401 Unauthorized").startsWith("Check API key"))
        assertEquals("Rate limited — retry", LiveSocket.mapHandshakeError("HTTP/1.1 429 blah"))
        assertTrue(LiveSocket.mapHandshakeError("garbage").startsWith("Network error"))
    }
}
