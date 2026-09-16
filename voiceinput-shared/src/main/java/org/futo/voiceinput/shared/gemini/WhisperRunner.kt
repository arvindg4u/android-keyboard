package org.futo.voiceinput.shared.gemini

import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunner

class WhisperRunner(modelManager: ModelManager) : TranscriptionRunner {
    private val delegate = MultiModelRunner(modelManager)

    override suspend fun transcribe(
        samples: FloatArray,
        runConfig: MultiModelRunConfiguration,
        decodingConfig: DecodingConfiguration,
        callback: ModelInferenceCallback
    ): String = delegate.run(samples, runConfig, decodingConfig, callback)

    override suspend fun preload(runConfig: MultiModelRunConfiguration) = delegate.preload(runConfig)

    override fun cancelAll() = delegate.cancelAll()
}
