package org.futo.voiceinput.shared.gemini

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveRunner(
    private val apiKey: String,
    private val smartMode: Boolean,
    private val baseUrl: String = LiveProtocol.DEFAULT_BASE_URL,
    private val socketFactory: (
        host: String,
        path: String,
        onText: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
        onFailure: (Throwable) -> Unit
    ) -> LiveSocket = { host, path, onText, onClosed, onFailure ->
        LiveSocket(host, path, onText, onClosed, onFailure) { _, _ -> }
    }
) : TranscriptionRunner {

    @Volatile
    private var inFlight: LiveSocket? = null

    override suspend fun transcribe(
        samples: FloatArray,
        runConfig: MultiModelRunConfiguration,
        decodingConfig: DecodingConfiguration,
        callback: ModelInferenceCallback
    ): String = coroutineScope {
        callback.updateStatus(InferenceState.LoadingModel)
        val key = LiveProtocol.normalizeKey(apiKey)
        if (key.isEmpty()) throw TranscribeException("No API key — open Settings")

        callback.updateStatus(InferenceState.Encoding)
        val pcm = withContext(Dispatchers.Default) {
            LiveProtocol.floatSamplesToPcm16(samples)
        }

        callback.updateStatus(InferenceState.DecodingStarted)
        liveAttempt(pcm, key, callback)
    }

    override suspend fun preload(runConfig: MultiModelRunConfiguration) {
    }

    override fun cancelAll() {
        inFlight?.close(1001, "cancel")
        inFlight = null
    }

    private sealed interface LiveOutcome {
        data class Done(val text: String) : LiveOutcome
        data class Fail(val message: String, val cause: Throwable? = null) : LiveOutcome
    }

    private suspend fun CoroutineScope.liveAttempt(
        pcm: ByteArray,
        key: String,
        callback: ModelInferenceCallback
    ): String {
        val host = LiveProtocol.liveHost(baseUrl)
        val path = LiveProtocol.livePath(baseUrl, key)
        val deferred = CompletableDeferred<LiveOutcome>()
        val transcript = StringBuilder()
        val lastTranscriptMs = AtomicLong(0L)

        val raw = socketFactory(
            host,
            path,
            { text -> onSocketText(text, transcript, lastTranscriptMs, deferred, callback) },
            { _, _ -> completeDone(deferred, transcript) },
            { t -> deferred.complete(mapFailure(t)) }
        )
        inFlight = raw
        try {
            withContext(Dispatchers.IO) { raw.connect() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveSocketException) {
            throw TranscribeException(if (e.message.isNullOrEmpty()) "Network error — check connection" else e.message!!, e)
        } catch (e: Exception) {
            throw TranscribeException("Network error — check connection", e)
        }
        try {
            withContext(Dispatchers.IO) {
                sendLiveAudio(raw, pcm, deferred)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deferred.complete(LiveOutcome.Fail("Network error — check connection", e))
        }
        try {
            val graceJob = launchGraceCloser(raw, deferred, lastTranscriptMs)
            try {
                return try {
                    withTimeout(LiveProtocol.LIVE_TIMEOUT_MS) { deferred.await() }.let { outcome ->
                        when (outcome) {
                            is LiveOutcome.Done -> outcome.text
                            is LiveOutcome.Fail -> throw TranscribeException(outcome.message, outcome.cause)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    val interim = transcript.toString().trim()
                    raw.close(1000, "timeout")
                    if (interim.isNotEmpty()) {
                        interim
                    } else {
                        throw TranscribeException("Timed out — try again", e)
                    }
                } catch (e: TranscribeException) {
                    throw e
                } catch (e: CancellationException) {
                    try {
                        raw.close(1000, "cancel")
                    } catch (_: Exception) {
                    }
                    throw e
                }
            } finally {
                graceJob.cancel()
                try {
                    raw.close(1000, "")
                } catch (e: Exception) {
                }
            }
        } finally {
            if (inFlight === raw) inFlight = null
        }
    }

    private fun onSocketText(
        text: String,
        transcript: StringBuilder,
        lastTranscriptMs: AtomicLong,
        deferred: CompletableDeferred<LiveOutcome>,
        callback: ModelInferenceCallback
    ) {
        // Final-only mode (user decision): interim hypotheses are ignored;
        // only authoritative input_transcription finals accumulate.
        val fragments = LiveProtocol.parseLiveInputTranscripts(text)
        if (fragments.isNotEmpty() && LiveProtocol.hasLiveFinalTranscript(text)) {
            for (fragment in fragments) transcript.append(fragment)
            lastTranscriptMs.set(System.currentTimeMillis())
            try {
                callback.partialResult(transcript.toString())
            } catch (e: Exception) {
            }
        }
        if (LiveProtocol.isLiveTurnComplete(text)) completeDone(deferred, transcript)
    }

    private fun completeDone(
        deferred: CompletableDeferred<LiveOutcome>,
        transcript: StringBuilder
    ) {
        // P1: empty transcript (short tap, silence) completes as empty text
        // like Whisper's blank path, instead of Fail("empty") which crashed
        // modelJob via uncaught TranscribeException.
        deferred.complete(LiveOutcome.Done(transcript.toString().trim()))
    }

    private fun mapFailure(t: Throwable): LiveOutcome {
        val message = t.message.orEmpty()
        return when {
            message.startsWith("Check API key") ||
                message.startsWith("Server error (HTTP") ||
                message.startsWith("Rate limited") ||
                message.startsWith("Model not found") -> LiveOutcome.Fail(message, t)
            message.startsWith("Network error") ||
                message.startsWith("Timed out") ||
                message == "empty" ||
                message == "cancelled" -> LiveOutcome.Fail(message, t)
            message.isBlank() -> LiveOutcome.Fail("Network error — check connection", t)
            else -> LiveOutcome.Fail("Network error — $message", t)
        }
    }

    private fun CoroutineScope.launchGraceCloser(
        raw: LiveSocket,
        deferred: CompletableDeferred<LiveOutcome>,
        lastTranscriptMs: AtomicLong
    ): Job = launch {
        while (true) {
            delay(LiveProtocol.LIVE_GRACE_MS)
            val last = lastTranscriptMs.get()
            if (last > 0L && System.currentTimeMillis() - last >= LiveProtocol.LIVE_GRACE_MS &&
                !deferred.isCompleted
            ) {
                try {
                    raw.close(1000, "grace")
                } catch (e: Exception) {
                }
                return@launch
            }
        }
    }

    private fun sendLiveAudio(
        raw: LiveSocket,
        pcm: ByteArray,
        deferred: CompletableDeferred<LiveOutcome>
    ) {
        if (!raw.send(LiveProtocol.buildLiveSetupJson(smartMode))) {
            deferred.complete(LiveOutcome.Fail("Network error — check connection"))
            return
        }
        if (!raw.send(LiveProtocol.buildLiveActivityStartJson())) {
            deferred.complete(LiveOutcome.Fail("Network error — check connection"))
            return
        }
        var offset = 0
        while (offset < pcm.size) {
            if (deferred.isCompleted) break
            val end = minOf(offset + LiveProtocol.LIVE_CHUNK_BYTES, pcm.size)
            if (!raw.send(LiveProtocol.buildLiveAudioMessage(pcm.copyOfRange(offset, end)))) {
                deferred.complete(LiveOutcome.Fail("Network error — check connection"))
                break
            }
            offset = end
        }
        if (!deferred.isCompleted) raw.send(LiveProtocol.buildLiveActivityEndJson())
        if (!deferred.isCompleted) raw.send(LiveProtocol.buildLiveAudioEndJson())
    }
}
