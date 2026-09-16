package org.futo.voiceinput.shared.gemini

import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration

class TranscribeException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

interface TranscriptionRunner {
    @Throws(Exception::class)
    suspend fun transcribe(
        samples: FloatArray,
        runConfig: MultiModelRunConfiguration,
        decodingConfig: DecodingConfiguration,
        callback: ModelInferenceCallback
    ): String

    suspend fun preload(runConfig: MultiModelRunConfiguration) {}
    fun cancelAll()
}
