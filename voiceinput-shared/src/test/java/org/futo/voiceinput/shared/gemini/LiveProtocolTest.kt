package org.futo.voiceinput.shared.gemini

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveProtocolTest {

    @Test
    fun `setup json pins fixed model and disables auto VAD`() {
        val setup = JSONObject(LiveProtocol.buildLiveSetupJson(smartMode = false)).getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        val modalities = setup.getJSONObject("generationConfig").getJSONArray("responseModalities")
        assertEquals("TEXT", modalities.getString(0))
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertTrue(vad.getBoolean("disabled"))
        val transcription = setup.getJSONObject("inputAudioTranscription")
        assertEquals("VERBATIM", transcription.getString("mode"))
    }

    @Test
    fun `setup json smart mode requests SMART`() {
        val setup = JSONObject(LiveProtocol.buildLiveSetupJson(smartMode = true)).getJSONObject("setup")
        assertEquals("SMART", setup.getJSONObject("inputAudioTranscription").getString("mode"))
    }

    @Test
    fun `audio message uses android base64 and pcm mime`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val msg = JSONObject(LiveProtocol.buildLiveAudioMessage(pcm)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", msg.getString("mimeType"))
        val decoded = java.util.Base64.getDecoder().decode(msg.getString("data"))
        assertArrayEquals(pcm, decoded)
    }

    @Test
    fun `final transcript wins over interim`() {
        val json = """{"serverContent":{"inputTranscription":{"text":"hello"},"interimInputTranscription":{"text":"hell"}}}"""
        assertEquals(listOf("hello"), LiveProtocol.parseLiveInputTranscripts(json))
        assertTrue(LiveProtocol.hasLiveFinalTranscript(json))
    }

    @Test
    fun `interim only yields preview and no final`() {
        val json = """{"serverContent":{"interimInputTranscription":{"text":"hel"}}}"""
        assertEquals(listOf("hel"), LiveProtocol.parseLiveInputTranscripts(json))
        assertFalse(LiveProtocol.hasLiveFinalTranscript(json))
    }

    @Test
    fun `snake_case variants parse`() {
        val json = """{"server_content":{"input_transcription":{"text":"hi"},"turnComplete":true}}"""
        assertEquals(listOf("hi"), LiveProtocol.parseLiveInputTranscripts(json))
        assertTrue(LiveProtocol.isLiveTurnComplete(json))
    }

    @Test
    fun `invalid json yields empty never throws`() {
        assertEquals(emptyList<String>(), LiveProtocol.parseLiveInputTranscripts("not json"))
        assertFalse(LiveProtocol.hasLiveFinalTranscript("not json"))
        assertFalse(LiveProtocol.isLiveSetupComplete("not json"))
        assertFalse(LiveProtocol.isLiveTurnComplete("not json"))
    }

    @Test
    fun `setup complete detected`() {
        assertTrue(LiveProtocol.isLiveSetupComplete("""{"setupComplete":{}}"""))
    }

    @Test
    fun `non https base url resets to default`() {
        assertEquals(LiveProtocol.DEFAULT_BASE_URL, LiveProtocol.normalizeBaseUrl("http://evil.example/x"))
        assertEquals(LiveProtocol.DEFAULT_BASE_URL, LiveProtocol.normalizeBaseUrl(""))
        assertEquals("https://proxy.example/a", LiveProtocol.normalizeBaseUrl("https://proxy.example/a/"))
    }

    @Test
    fun `float to pcm16 clips and scales`() {
        val pcm = LiveProtocol.floatSamplesToPcm16(floatArrayOf(0f, 1f, -1f, 2f, -2f))
        assertEquals(10, pcm.size)
        assertEquals(0.toByte(), pcm[0]); assertEquals(0.toByte(), pcm[1])
        assertEquals(0xFF.toByte(), pcm[2]); assertEquals(0x7F.toByte(), pcm[3])
        assertEquals(0xFF.toByte(), pcm[6]); assertEquals(0x7F.toByte(), pcm[7])
    }

    @Test
    fun `http errors map to user-safe messages`() {
        assertTrue(LiveProtocol.mapHttpError(401, "").startsWith("Check API key"))
        assertTrue(LiveProtocol.mapHttpError(429, "").startsWith("Rate limited"))
        assertTrue(LiveProtocol.mapHttpError(500, "").startsWith("Server error"))
    }

    @Test
    fun `short to pcm16 matches float path bytes`() {
        // 0, max, min-symmetric (-32767, since -32768 clips on the float path),
        // arbitrary: LE bytes must equal float-converted output.
        val shorts = shortArrayOf(0, 32767, -32767, 1000, -1000)
        val floats = floatArrayOf(0f, 1f, -1f, 1000f / 32767f, -1000f / 32767f)
        assertArrayEquals(
            LiveProtocol.floatSamplesToPcm16(floats),
            LiveProtocol.shortSamplesToPcm16(shorts, shorts.size),
        )
    }

    @Test
    fun `short to pcm16 respects length prefix`() {
        val shorts = shortArrayOf(1, 2, 3, 4)
        assertArrayEquals(
            LiveProtocol.shortSamplesToPcm16(shorts, 2),
            LiveProtocol.shortSamplesToPcm16(shorts.copyOfRange(0, 2), 2),
        )
        assertEquals(0, LiveProtocol.shortSamplesToPcm16(shorts, 0).size)
        assertEquals(0, LiveProtocol.shortSamplesToPcm16(shorts, -5).size)
    }
}
