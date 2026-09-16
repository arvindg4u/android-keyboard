package org.futo.voiceinput.shared.gemini

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Transport abstraction over the Live WebSocket so streaming sessions are
 * unit-testable without network. The production adapter wraps [LiveSocket];
 * tests inject fakes. The API key travels only in the handshake path and is
 * never logged.
 */
interface StreamSocket {
    @Throws(Exception::class)
    fun connect()
    fun send(text: String): Boolean
    fun close(code: Int = 1000, reason: String = "")
}

fun interface StreamSocketFactory {
    fun create(
        host: String,
        path: String,
        onText: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
        onFailure: (Throwable) -> Unit
    ): StreamSocket
}

/** Production [StreamSocket] backed by the raw-TLS [LiveSocket]. */
internal class LiveSocketAdapter(private val delegate: LiveSocket) : StreamSocket {
    override fun connect() = delegate.connect()
    override fun send(text: String): Boolean = delegate.send(text)
    override fun close(code: Int, reason: String) = delegate.close(code, reason)
}

/**
 * Overlap-aware accumulator for FINAL transcript fragments only. The server
 * re-sends cumulative text, so each append returns only the not-yet-seen
 * delta: cumulative resends collapse, disjoint fragments append, exact echoes
 * yield "". Interim hypotheses never reach this class.
 */
internal class FinalAccumulator {
    private val text = StringBuilder()

    fun append(incoming: String): String {
        if (incoming.isEmpty()) return ""
        val current = text.toString()
        val max = minOf(current.length, incoming.length)
        var overlap = 0
        for (k in max downTo 1) {
            if (current.endsWith(incoming.substring(0, k))) {
                overlap = k
                break
            }
        }
        val delta = incoming.substring(overlap)
        text.append(delta)
        return delta
    }

    fun snapshot(): String = text.toString()
}

/**
 * TRUE STREAMING Live session: opened at mic-tap via [openAsync], fed live
 * PCM via [sendPcm] as recorder buffers arrive, ended with [finish] on Stop,
 * committed via [awaitFinal].
 *
 * Final-only commit: server interim hypotheses are parsed but discarded —
 * never displayed, never accumulated. Only authoritative
 * input_transcription finals append (overlap-aware) into the commit text.
 *
 * Ordering / silence-gate rules (single [lock] serializes everything):
 * - Pre-setup PCM is buffered in arrival order (capped at
 *   [MAX_PENDING_CHUNKS]) and flushed on setupComplete behind activityStart.
 * - [sendPcm] after [finish] is dropped, so no chunk can slip past the
 *   end-markers on a racing recorder thread.
 * - Post-terminal server messages (after fail/close) are dropped, so a stale
 *   reordered interim can never clobber committed finals.
 * - Terminal signals settle [outcome] at most once; [onSessionError] fires at
 *   most once; [close] is idempotent.
 *
 * @param onFinalChunk invoked per new final delta (listener must never throw
 * into us; exceptions are swallowed).
 * @param onSessionError invoked at most once with a user-safe, key-free
 * message when the session fails.
 */
class LiveStreamingSession internal constructor(
    apiKey: String,
    private val smartMode: Boolean,
    private val baseUrl: String,
    private val onFinalChunk: (String) -> Unit,
    private val onSessionError: (String) -> Unit,
    private val socketFactory: StreamSocketFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val AWAIT_FINAL_TIMEOUT_MS = 8_000L
        const val MAX_PENDING_CHUNKS = 300
    }

    private val key: String = LiveProtocol.normalizeKey(apiKey)
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ioDispatcher = dispatcher

    private var socket: StreamSocket? = null
    private var open = false
    private var setupComplete = false
    private val pending = ArrayDeque<ByteArray>()
    private var finished = false
    private var endMarkersSent = false
    private var failed = false
    private var closed = false
    private val finals = FinalAccumulator()
    private val outcome = CompletableDeferred<String?>()

    /**
     * Connects in the background: connect() does DNS + TCP + TLS and must
     * never run on the calling (mic-tap) thread. Early [sendPcm] audio is
     * buffered until setupComplete flushes it.
     */
    fun openAsync() {
        if (key.isEmpty()) {
            // Single-key fail-fast: never open a socket without credentials.
            fail("No API key — open Settings")
            return
        }
        val raw: StreamSocket
        synchronized(lock) {
            if (closed || failed || socket != null) return
            raw = socketFactory.create(
                LiveProtocol.liveHost(baseUrl),
                LiveProtocol.livePath(baseUrl, key),
                ::onSocketText,
                { _, _ -> onSocketClosed() },
                ::onSocketFailure,
            )
            socket = raw
        }
        scope.launch {
            try {
                withContext(ioDispatcher) { raw.connect() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(mapSocketError(e), e)
                return@launch
            }
            var setupOk = false
            var terminal = false
            synchronized(lock) {
                terminal = failed || closed
                if (!terminal) {
                    setupOk = safeSend(raw, LiveProtocol.buildLiveSetupJson(smartMode))
                    if (setupOk) open = true
                }
            }
            if (terminal) return@launch
            if (!setupOk) fail("Network error — check connection")
        }
    }

    /**
     * Streams one raw PCM chunk (any length). Pre-setup audio is buffered
     * (copied — the recorder reuses its buffer); post-[finish] audio is
     * dropped so chunk/marker order is fixed.
     */
    fun sendPcm(pcmChunk: ByteArray) {
        val direct: StreamSocket?
        synchronized(lock) {
            if (failed || closed || finished) return
            if (!open || !setupComplete) {
                if (pending.size < MAX_PENDING_CHUNKS) pending.addLast(pcmChunk.copyOf())
                return
            }
            direct = socket
        }
        val target = direct ?: return
        if (!safeSend(target, LiveProtocol.buildLiveAudioMessage(pcmChunk))) {
            fail("Network error — check connection")
        }
    }

    /**
     * Sends the turn-end markers on Stop. Does NOT close: the owner awaits
     * [awaitFinal] then [close]s after reading the commit snapshot. When the
     * socket is not up yet, the setupComplete flush emits the markers after
     * draining buffered audio.
     */
    fun finish() {
        val target: StreamSocket?
        val sendNow: Boolean
        synchronized(lock) {
            if (failed || closed || finished) return
            finished = true
            target = socket
            // Pre-setup Stop defers the markers to the setupComplete flush so
            // they land after activityStart + buffered audio, never before.
            sendNow = setupComplete && !endMarkersSent
            if (sendNow) endMarkersSent = true
        }
        if (target == null) return
        if (sendNow) sendEndMarkers(target)
    }

    /**
     * Suspends until the server finalizes (turnComplete or close-with-text)
     * or [timeoutMs] elapses. Returns the accumulated FINAL text, or null
     * when nothing final arrived. Timeout falls back to whatever final text
     * arrived — never throws for silence. Socket failure / [close] complete
     * exceptionally so the error surfaces instead of hanging; cancellation
     * propagates unconverted.
     */
    suspend fun awaitFinal(timeoutMs: Long = AWAIT_FINAL_TIMEOUT_MS): String? {
        return try {
            withTimeout(timeoutMs) { outcome.await() }
        } catch (e: TimeoutCancellationException) {
            snapshotOrNull()
        }
    }

    /**
     * Tears the session down. Idempotent. A still-pending [awaitFinal] fails
     * fast with TranscribeException("cancelled"); an already-settled outcome
     * is untouched, so close-after-commit is pure cleanup.
     */
    fun close() {
        val target: StreamSocket?
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            target = socket
            socket = null
        }
        scope.cancel()
        outcome.completeExceptionally(TranscribeException("cancelled"))
        if (target != null) {
            try {
                target.close(1000, "cancel")
            } catch (_: Exception) {
            }
        }
    }

    private fun onSocketText(text: String) {
        var delta = ""
        var endOfTurn = false
        var setupAck = false
        synchronized(lock) {
            if (failed || closed) return
            if (!setupComplete && LiveProtocol.isLiveSetupComplete(text)) {
                setupComplete = true
                setupAck = true
            } else {
                val fragments = LiveProtocol.parseLiveInputTranscripts(text)
                if (fragments.isNotEmpty() && LiveProtocol.hasLiveFinalTranscript(text)) {
                    delta = finals.append(fragments.joinToString(""))
                }
                // Interim fragments are parsed but deliberately discarded:
                // never displayed, never accumulated for commit.
                if (LiveProtocol.isLiveTurnComplete(text)) endOfTurn = true
            }
        }
        if (setupAck) {
            flushAfterSetup()
            return
        }
        if (delta.isNotEmpty()) {
            try {
                onFinalChunk(delta)
            } catch (_: Exception) {
            }
        }
        if (endOfTurn) outcome.complete(snapshotOrNull())
    }

    private fun flushAfterSetup() {
        val drain: List<ByteArray>
        val sendEnd: Boolean
        synchronized(lock) {
            if (failed || closed) return
            drain = pending.toList()
            pending.clear()
            sendEnd = finished && !endMarkersSent
            if (sendEnd) endMarkersSent = true
        }
        val target = synchronized(lock) { socket }
        if (target == null) {
            fail("Network error — check connection")
            return
        }
        if (!safeSend(target, LiveProtocol.buildLiveActivityStartJson())) {
            fail("Network error — check connection")
            return
        }
        for (chunk in drain) {
            synchronized(lock) {
                if (failed || closed) return
            }
            if (!safeSend(target, LiveProtocol.buildLiveAudioMessage(chunk))) {
                fail("Network error — check connection")
                return
            }
        }
        if (sendEnd) sendEndMarkers(target)
    }

    private fun sendEndMarkers(target: StreamSocket) {
        val okEnd = safeSend(target, LiveProtocol.buildLiveActivityEndJson())
        val okAudioEnd = safeSend(target, LiveProtocol.buildLiveAudioEndJson())
        if (!okEnd || !okAudioEnd) fail("Network error — check connection")
    }

    private fun onSocketClosed() {
        // Server-initiated close: commit whatever final text arrived.
        // Echoes of our own close() after fail()/close() are ignored — the
        // outcome is already settled (or settling) and must not be clobbered
        // with a null snapshot.
        val ours = synchronized(lock) { failed || closed }
        if (ours) return
        outcome.complete(snapshotOrNull())
    }

    private fun onSocketFailure(t: Throwable) {
        fail(mapSocketError(t), t)
    }

    private fun fail(message: String, cause: Throwable? = null) {
        val target: StreamSocket?
        var notify = false
        synchronized(lock) {
            if (!failed) {
                failed = true
                notify = true
            }
            pending.clear()
            target = socket
            socket = null
        }
        scope.cancel()
        if (target != null) {
            try {
                target.close(1001, "error")
            } catch (_: Exception) {
            }
        }
        outcome.completeExceptionally(TranscribeException(message, cause))
        if (notify) {
            try {
                onSessionError(message)
            } catch (_: Exception) {
            }
        }
    }

    private fun snapshotOrNull(): String? {
        val text = synchronized(lock) { finals.snapshot().trim() }
        return text.ifEmpty { null }
    }

    private fun safeSend(target: StreamSocket, text: String): Boolean {
        return try {
            target.send(text)
        } catch (_: Exception) {
            false
        }
    }

    private fun mapSocketError(t: Throwable): String {
        val message = t.message.orEmpty()
        return when {
            message.startsWith("Check API key") ||
                message.startsWith("Server error (HTTP") ||
                message.startsWith("Rate limited") ||
                message.startsWith("Model not found") -> message
            message.startsWith("Network error") ||
                message.startsWith("Timed out") ||
                message == "cancelled" -> message
            message.isBlank() -> "Network error — check connection"
            // Key-free by construction: the key lives only in the handshake
            // path, and raw error text is prefixed, never interpolated raw.
            else -> "Network error — $message"
        }
    }
}
