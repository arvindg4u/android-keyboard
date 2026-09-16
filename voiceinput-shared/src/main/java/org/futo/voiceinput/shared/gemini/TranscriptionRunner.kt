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

/**
 * Streaming entry point for Live transcription. Implementations open a
 * [LiveStreamingSession] (socket opened at mic-tap, PCM fed live) without
 * touching the one-shot [TranscriptionRunner.transcribe] path used by Whisper.
 *
 * Parameterless by design: the runner carries its own credentials (apiKey,
 * smartMode, baseUrl from construction) and the session callbacks are wired
 * by the owner ([AudioRecognizer]) at start time via the session constructor.
 * Callers pass callbacks through [startStream] so a single runner instance
 * can serve successive utterances with per-utterance listeners.
 */
interface StreamRunner {
    fun startStream(
        onFinalChunk: (String) -> Unit = {},
        onSessionError: (String) -> Unit = {},
    ): LiveStreamingSession
}
