package org.futo.voiceinput.shared.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun finalJson(text: String) =
    """{"serverContent":{"inputTranscription":{"text":"$text"}}}"""

private fun interimJson(text: String) =
    """{"serverContent":{"interimInputTranscription":{"text":"$text"}}}"""

private const val TURN_COMPLETE_JSON = """{"serverContent":{"turnComplete":true}}"""
private const val SETUP_COMPLETE_JSON = """{"setupComplete":{}}"""

private class FakeStreamSocket : StreamSocket {
    val sent = mutableListOf<String>()
    var sendResult = true
    var connectThrows: Throwable? = null
    var closeCount = 0
    var closeHook: ((Int, String) -> Unit)? = null

    override fun connect() {
        connectThrows?.let { throw it }
    }

    override fun send(text: String): Boolean {
        if (sendResult) sent.add(text)
        return sendResult
    }

    override fun close(code: Int, reason: String) {
        closeCount++
        closeHook?.invoke(code, reason)
    }
}

private fun sentKind(json: String): String {
    val root = JSONObject(json)
    if (root.has("setup")) return "setup"
    val rt = root.getJSONObject("realtimeInput")
    return when {
        rt.has("activityStart") -> "activityStart"
        rt.has("activityEnd") -> "activityEnd"
        rt.has("audioStreamEnd") -> "audioEnd"
        rt.has("audio") -> "audio"
        else -> "unknown"
    }
}

private fun decodeAudio(json: String): ByteArray {
    val data = JSONObject(json)
        .getJSONObject("realtimeInput")
        .getJSONObject("audio")
        .getString("data")
    return java.util.Base64.getDecoder().decode(data)
}

private class Harness(val smartMode: Boolean = false, val apiKey: String = "test-key") {
    val socket = FakeStreamSocket()
    val finalChunks = mutableListOf<String>()
    val errors = mutableListOf<String>()
    var createdSockets = 0
    lateinit var onText: (String) -> Unit
    lateinit var onClosed: (Int, String) -> Unit
    lateinit var onFailure: (Throwable) -> Unit

    val session = LiveStreamingSession(
        apiKey = apiKey,
        smartMode = smartMode,
        baseUrl = LiveProtocol.DEFAULT_BASE_URL,
        onFinalChunk = { finalChunks.add(it) },
        onSessionError = { errors.add(it) },
        socketFactory = StreamSocketFactory { _, _, t, c, f ->
            createdSockets++
            onText = t
            onClosed = c
            onFailure = f
            socket.apply { closeHook = c }
        },
        dispatcher = Dispatchers.Unconfined,
    )

    fun open() = session.openAsync()
    fun serverText(json: String) = onText(json)
    fun setupAck() = serverText(SETUP_COMPLETE_JSON)
    fun sentKinds(): List<String> = socket.sent.map(::sentKind)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveStreamingSessionTest {

    @Test
    fun `open sends setup pinning fixed model`() {
        val h = Harness()
        h.open()

        assertEquals(1, h.createdSockets)
        val setup = JSONObject(h.socket.sent[0]).getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
    }

    @Test
    fun `smart mode setup requests SMART`() {
        val h = Harness(smartMode = true)
        h.open()

        val setup = JSONObject(h.socket.sent[0]).getJSONObject("setup")
        assertEquals("SMART", setup.getJSONObject("inputAudioTranscription").getString("mode"))
    }

    @Test
    fun `blank key fails fast without opening socket`() {
        val h = Harness(apiKey = "   ")
        h.open()

        assertEquals(0, h.createdSockets)
        assertEquals(listOf("No API key — open Settings"), h.errors)
    }

    @Test
    fun `interim never fires chunks while final accumulates`() {
        val h = Harness()
        h.open()
        h.setupAck()

        h.serverText(interimJson("hel"))
        h.serverText(finalJson("hello "))
        h.serverText(interimJson("hello wo"))
        h.serverText(finalJson("hello world"))
        h.serverText(TURN_COMPLETE_JSON)

        // Interim never fires onFinalChunk; cumulative final resend collapses.
        assertEquals(listOf("hello ", "world"), h.finalChunks)
        val text = runBlocking { h.session.awaitFinal() }
        assertEquals("hello world", text)
    }

    @Test
    fun `pending queue flushes on setupComplete in order`() {
        val h = Harness()
        h.open()
        h.session.sendPcm(byteArrayOf(1, 2, 3))
        h.session.sendPcm(byteArrayOf(4, 5, 6))

        assertEquals(listOf("setup"), h.sentKinds())

        h.setupAck()

        assertEquals(listOf("setup", "activityStart", "audio", "audio"), h.sentKinds())
        val audios = h.socket.sent.drop(2).map(::decodeAudio)
        assertArrayEquals(byteArrayOf(1, 2, 3), audios[0])
        assertArrayEquals(byteArrayOf(4, 5, 6), audios[1])
    }

    @Test
    fun `pending queue capped at max chunks`() {
        val h = Harness()
        h.open()
        repeat(350) { h.session.sendPcm(byteArrayOf(it.toByte())) }
        h.setupAck()

        assertEquals(LiveStreamingSession.MAX_PENDING_CHUNKS, h.sentKinds().count { it == "audio" })
    }

    @Test
    fun `awaitFinal timeout returns buffered finals`() {
        val h = Harness()
        h.open()
        h.setupAck()
        h.serverText(finalJson("partial final"))

        val text = runBlocking { h.session.awaitFinal(timeoutMs = 100) }

        assertEquals("partial final", text)
    }

    @Test
    fun `awaitFinal timeout with silence returns null`() {
        val h = Harness()
        h.open()
        h.setupAck()

        val text = runBlocking { h.session.awaitFinal(timeoutMs = 50) }

        assertNull(text)
    }

    @Test
    fun `interim-only turn commits fallback on timeout`() {
        val h = Harness()
        h.open()
        h.setupAck()
        h.serverText(interimJson("hel"))
        h.serverText(interimJson("hello wo"))

        // No chunk fired for interim, but the latest interim commits.
        assertTrue(h.finalChunks.isEmpty())
        val text = runBlocking { h.session.awaitFinal(timeoutMs = 50) }
        assertEquals("hello wo", text)
    }

    @Test
    fun `final wins over interim fallback on turn complete`() {
        val h = Harness()
        h.open()
        h.setupAck()
        h.serverText(interimJson("hel"))
        h.serverText(finalJson("hello"))
        h.serverText(TURN_COMPLETE_JSON)

        val text = runBlocking { h.session.awaitFinal() }
        assertEquals("hello", text)
    }

    @Test
    fun `finish sends end markers after audio`() {
        val h = Harness()
        h.open()
        h.setupAck()
        h.session.sendPcm(byteArrayOf(7))
        h.session.finish()

        assertEquals(
            listOf("setup", "activityStart", "audio", "activityEnd", "audioEnd"),
            h.sentKinds(),
        )
    }

    @Test
    fun `stop before setup defers markers behind buffered audio`() {
        val h = Harness()
        h.open()
        h.session.sendPcm(byteArrayOf(9))
        h.session.finish()

        // No markers before the server ack: they wait for the flush.
        assertEquals(listOf("setup"), h.sentKinds())

        h.setupAck()

        assertEquals(
            listOf("setup", "activityStart", "audio", "activityEnd", "audioEnd"),
            h.sentKinds(),
        )
    }

    @Test
    fun `post-finish audio dropped keeping marker order`() {
        val h = Harness()
        h.open()
        h.setupAck()
        h.session.sendPcm(byteArrayOf(1))
        h.session.finish()
        h.session.sendPcm(byteArrayOf(2))

        assertEquals(
            listOf("setup", "activityStart", "audio", "activityEnd", "audioEnd"),
            h.sentKinds(),
        )
    }

    @Test
    fun `socket failure surfaces error once and awaitFinal throws`() {
        val h = Harness()
        h.open()
        h.setupAck()
        h.onFailure(RuntimeException("boom"))
        h.onFailure(RuntimeException("boom again"))

        assertEquals(1, h.errors.size)
        assertTrue(h.errors[0].contains("boom"))
        try {
            runBlocking { h.session.awaitFinal() }
            fail("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test
    fun `close is idempotent and cancels pending await`() {
        val h = Harness()
        h.open()
        h.setupAck()
        h.session.close()
        h.session.close()

        assertEquals(1, h.socket.closeCount)
        try {
            runBlocking { h.session.awaitFinal() }
            fail("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertEquals("cancelled", e.message)
        }
    }
}
