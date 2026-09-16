package org.futo.voiceinput.shared.gemini

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.shared.ggml.WhisperGGML
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private object DummyLoader : ModelLoader {
    override val name: Int = 0
    override fun exists(context: Context): Boolean = true
    override fun getRequiredDownloadList(context: Context): List<String> = emptyList()
    override fun loadGGML(context: Context): WhisperGGML = throw UnsupportedOperationException()
    override fun key(context: Context): Any = "dummy"
}

private fun dummyRunConfig() = MultiModelRunConfiguration(
    primaryModel = DummyLoader,
    languageSpecificModels = emptyMap()
)

private fun dummyDecoding() = DecodingConfiguration(
    glossary = emptyList(),
    languages = emptySet(),
    suppressSymbols = false
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiLiveRunnerTest {

    private fun fakeCallback() = object : ModelInferenceCallback {
        val states = mutableListOf<InferenceState>()
        var partial = ""
        override fun updateStatus(state: InferenceState) { states.add(state) }
        override fun languageDetected(language: Language) {}
        override fun partialResult(string: String) { partial = string }
    }

    @Test
    fun `blank key throws key error without opening socket`() = runBlocking {
        var opened = false
        val runner = GeminiLiveRunner(apiKey = "  ", smartMode = false) { _, _, _, _, _ ->
            opened = true
            throw AssertionError("must not open socket without key")
        }
        try {
            runner.transcribe(FloatArray(1600), dummyRunConfig(), dummyDecoding(), fakeCallback())
            fail("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertTrue(e.message!!.contains("API key"))
        }
        assertFalse(opened)
    }
}
