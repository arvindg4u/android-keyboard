# Gemini Live Voice Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Gemini Live (one-shot WebSocket) transcription engine to FUTO Keyboard's mic, beside offline Whisper, behind an explicit engine toggle.

**Architecture:** New `TranscriptionRunner` interface in `voiceinput-shared` at the `MultiModelRunner` call site. `WhisperRunner` wraps existing logic unchanged; `GeminiLiveRunner` ports Voice IME's one-shot Live path (`liveTranscribe`/`liveAttempt`, raw-TLS `LiveSocket`, live-only protocol subset). `VoiceInputActionWindow` picks the runner from the engine setting. Recording, VAD, UI, sanitizer, and commit path are untouched.

**Tech Stack:** Kotlin · `voiceinput-shared` Android library (minSdk 24) · raw TLS socket (`SSLSocketFactory`) · `org.json` · `android.util.Base64` · EncryptedSharedPreferences (`androidx.security:security-crypto:1.1.0-alpha06`, already in Gradle cache) · FUTO DataStore settings + Compose settings pages · JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-16-voice-gemini-live-design.md`

## Global Constraints

- minSdk 24 — no `java.util.Base64` (API 26+); use `android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)` everywhere.
- No OkHttp in FUTO — `LiveSocket` stays raw-TLS + stdlib (`SSLSocketFactory`, `org.json.JSONObject/JSONArray`, `java.net.URLEncoder`).
- Fixed model only: `gemini-3.5-transcribe-live`. No model picker, no model allowlist changes.
- Single API key. No rotation: first 429 or any error fails fast with the user-safe message.
- Default engine is Offline. Gemini socket opens only when engine=Gemini AND a key exists; otherwise silent Whisper fallback.
- Keys/audio never logged. All user-visible errors come from the fixed message set in the spec §3.
- No REST `generateContent`, no Interactions API, no `LiveSession` streaming, no clipboard-history port.
- Follow existing FUTO settings patterns: `SettingsKey` in `VoiceInputSettingKeys.kt`, `userSettingToggleDataStore` / `Tip` in `VoiceInput.kt`, strings in `java/res/values/strings-uix.xml` named `voice_input_settings_*`.

---

### Task 1: `TranscriptionRunner` interface + `WhisperRunner` adapter (Whisper path unchanged)

**Files:**
- Create: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/TranscriptionRunner.kt`
- Create: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/WhisperRunner.kt`
- Modify: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/AudioRecognizer.kt` (constructor takes `TranscriptionRunner`; `runModel()` delegates)
- Modify: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/RecognizerView.kt` (pass-through of the runner into `AudioRecognizer`)
- Modify: `java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt` (construct `WhisperRunner` at the `RecognizerView` call site)

**Interfaces:**
- Consumes: existing `MultiModelRunner(modelManager)`, `MultiModelRunConfiguration`, `DecodingConfiguration`, `ModelInferenceCallback` — signatures unchanged.
- Produces: `interface TranscriptionRunner { suspend fun transcribe(samples: FloatArray, runConfig: MultiModelRunConfiguration, decodingConfig: DecodingConfiguration, callback: ModelInferenceCallback): String }` and `class WhisperRunner(private val modelManager: ModelManager) : TranscriptionRunner`.

- [ ] **Step 1: Create `TranscriptionRunner.kt`**

```kotlin
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
```

- [ ] **Step 2: Create `WhisperRunner.kt`**

```kotlin
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
```

- [ ] **Step 3: Modify `AudioRecognizer.kt`**

Change the constructor to accept `runner: TranscriptionRunner` (replacing direct `MultiModelRunner` construction). Exact edits:
  - Add imports for `TranscriptionRunner` (and drop the now-unused `MultiModelRunner` import if present).
  - Constructor becomes `(context, lifecycleScope, modelManager: ModelManager, runner: TranscriptionRunner, listener, settings)`; remove field `private val modelRunner = MultiModelRunner(modelManager)`.
  - `preloadModels()` calls `runner.preload(settings.modelRunConfiguration)`.
  - `reset()` calls `runner.cancelAll()` (replacing `modelRunner.cancelAll()`).
  - In `runModel()`, replace `modelRunner.run(floatArray, ...)` with `runner.transcribe(floatArray, settings.modelRunConfiguration, settings.decodingConfiguration, runnerCallback)`.

- [ ] **Step 4: Modify `RecognizerView.kt`** — add constructor param `runner: TranscriptionRunner`, pass it into the `AudioRecognizer(...)` construction at line ~240.

- [ ] **Step 5: Modify `VoiceInputAction.kt`** — at the `RecognizerView(...)` construction (line ~175), pass `runner = WhisperRunner(state.modelManager)`. Behavior is now byte-identical to before.

- [ ] **Step 6: Build to verify nothing broke**

Run: `cd /teamspace/studios/this_studio/android-keyboard && ./gradlew :voiceinput-shared:assembleDebug` (module name per `settings.gradle` — verify with `cat settings.gradle`; if the module is named differently, use that name).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/ voiceinput-shared/src/main/java/org/futo/voiceinput/shared/AudioRecognizer.kt voiceinput-shared/src/main/java/org/futo/voiceinput/shared/RecognizerView.kt java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt
git commit -m "refactor: TranscriptionRunner interface with WhisperRunner adapter

No behavior change; Gemini runner plugs in next."
```

---

### Task 2: `LiveProtocol.kt` — live-only message builders/parsers (JVM-tested)

**Files:**
- Create: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/LiveProtocol.kt`
- Create: `voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/LiveProtocolTest.kt` (new `src/test` dir; add `testImplementation 'junit:junit:4.13.2'` to `voiceinput-shared/build.gradle`)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `object LiveProtocol` with exact members:
  - `const val GEMINI_LIVE_MODEL = "gemini-3.5-transcribe-live"`, `LIVE_TIMEOUT_MS = 75_000L`, `LIVE_GRACE_MS = 8_000L`, `LIVE_CHUNK_BYTES = 3200`, `LIVE_PCM_MIME = "audio/pcm;rate=16000"`, `LIVE_RPC_PATH = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"`, `LIVE_DEFAULT_HOST = "generativelanguage.googleapis.com"`, `DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"`.
  - `fun buildLiveSetupJson(smartMode: Boolean): String` (model fixed — no model param).
  - `fun buildLiveActivityStartJson(): String`, `fun buildLiveActivityEndJson(): String`, `fun buildLiveAudioMessage(pcmChunk: ByteArray): String`, `fun buildLiveAudioEndJson(): String`.
  - `fun parseLiveInputTranscripts(serverJson: String): List<String>`, `fun hasLiveFinalTranscript(serverJson: String): Boolean`, `fun isLiveSetupComplete(serverJson: String): Boolean`, `fun isLiveTurnComplete(serverJson: String): Boolean`.
  - `fun liveHost(baseUrl: String): String`, `fun livePath(baseUrl: String, key: String): String`.
  - `fun normalizeBaseUrl(raw: String): String` (fail-closed non-https → default).
  - `fun mapHttpError(code: Int, body: String): String`.
  - `fun floatSamplesToPcm16(samples: FloatArray): ByteArray`.
  - `fun normalizeKey(raw: String): String` (trim; single key, no rotation).

- [ ] **Step 1: Add test dependency to `voiceinput-shared/build.gradle`**

In `dependencies { ... }` add `testImplementation 'junit:junit:4.13.2'`.

- [ ] **Step 2: Write the failing tests (`LiveProtocolTest.kt`)**

```kotlin
package org.futo.voiceinput.shared.gemini

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

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
        assertEquals(0, pcm[0]); assertEquals(0, pcm[1])
        assertEquals(0xFF.toByte(), pcm[2]); assertEquals(0x7F.toByte(), pcm[3])
        assertEquals(0xFF.toByte(), pcm[6]); assertEquals(0x7F.toByte(), pcm[7])
    }

    @Test
    fun `http errors map to user-safe messages`() {
        assertTrue(LiveProtocol.mapHttpError(401, "").startsWith("Check API key"))
        assertTrue(LiveProtocol.mapHttpError(429, "").startsWith("Rate limited"))
        assertTrue(LiveProtocol.mapHttpError(500, "").startsWith("Server error"))
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd /teamspace/studios/this_studio/android-keyboard && ./gradlew :voiceinput-shared:testDebugUnitTest --tests "org.futo.voiceinput.shared.gemini.LiveProtocolTest"`
Expected: FAIL (no `LiveProtocol` object yet). If the module name differs per `settings.gradle`, substitute it.

- [ ] **Step 4: Write `LiveProtocol.kt`** — port the live-only subset from Voice IME's `GeminiTransports.kt` (builders, parsers, `liveHost`, `livePath`, `normalize`, `mapHttpError`) with these exact adaptations:
  - `buildLiveSetupJson(smartMode: Boolean)` — model hardcoded to `GEMINI_LIVE_MODEL`; identical JSON shape otherwise.
  - `buildLiveAudioMessage` uses `android.util.Base64.encodeToString(pcmChunk, android.util.Base64.NO_WRAP)`.
  - `livePath` uses `java.net.URLEncoder.encode(key, "UTF-8")` (available API 24).
  - `floatSamplesToPcm16`: `(sample.coerceIn(-1f, 1f) * 32767).toInt().toShort()`, little-endian bytes.
  - Skip `requiresLive`, `isInteractionsModel`, REST/Interactions builders, `normalizeKeys` rotation helper.

- [ ] **Step 5: Run tests to verify they pass**

Run: same command as Step 3.
Expected: all 11 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add voiceinput-shared/build.gradle voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/LiveProtocol.kt voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/LiveProtocolTest.kt
git commit -m "feat: LiveProtocol builders/parsers for Gemini one-shot live

Fixed model gemini-3.5-transcribe-live; android.util.Base64 for minSdk 24."
```

---

### Task 3: `LiveSocket.kt` verbatim port + frame-codec tests

**Files:**
- Create: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/LiveSocket.kt`
- Create: `voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/LiveSocketTest.kt`

**Interfaces:**
- Consumes: `LiveProtocol.liveHost/livePath` for host/path construction (done by runner, not the socket).
- Produces: `internal class LiveSocket(host: String, path: String, onText: (String) -> Unit, onClosed: (code: Int, reason: String) -> Unit, onFailure: (Throwable) -> Unit, log: (String, String) -> Unit)` with `fun connect()`, `fun send(text: String): Boolean`, `fun close(code: Int = 1000, reason: String = "")`, `class LiveSocketException(message: String, cause: Throwable? = null)` — same signatures as Voice IME.

- [ ] **Step 1: Write the failing frame-codec tests**

```kotlin
package org.futo.voiceinput.shared.gemini

import org.junit.Assert.*
import org.junit.Test

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
```

The companion helpers (`encodeTextFrame`, `closePayload`, `mapHandshakeError`) stay `internal` — JVM unit tests in the same module see `internal` via Gradle's default test friend-path. If the toolchain complains, test through `send()` behavior only; do not widen to public API.

- [ ] **Step 2: Run to verify failure** — same gradle test command with `--tests "...LiveSocketTest"`. Expected: FAIL (no `LiveSocket`).

- [ ] **Step 3: Port `LiveSocket.kt`** — copy Voice IME's `LiveSocket.kt` verbatim into package `org.futo.voiceinput.shared.gemini`, changing only the package line, plus one fix: `connect()` builds its handshake key with `java.util.Base64` (API 26+) — replace with `android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)`.

- [ ] **Step 4: Run tests** — both `LiveSocketTest` and `LiveProtocolTest` PASS.

- [ ] **Step 5: Commit**

```bash
git add voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/LiveSocket.kt voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/LiveSocketTest.kt
git commit -m "feat: port LiveSocket raw-TLS client for Gemini live

Verbatim port; handshake key uses android.util.Base64 for minSdk 24."
```

---

### Task 4: `GeminiSettings.kt` — encrypted single-key storage (JVM-tested normalize)

**Files:**
- Create: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/GeminiSettings.kt`
- Create: `voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/GeminiSettingsTest.kt`
- Modify: `voiceinput-shared/build.gradle` — add `implementation 'androidx.security:security-crypto:1.1.0-alpha06'` (version already in Gradle cache; verified during planning).

**Interfaces:**
- Consumes: `LiveProtocol.DEFAULT_BASE_URL`.
- Produces: `class GeminiSettings(context: Context)` with `var apiKey: String` (trimmed), `var smartMode: Boolean` (default false = VERBATIM), `val baseUrl: String` (fixed to default — NOT user-configurable in v1, no setter), `fun hasKey(): Boolean`; companion `fun normalizeKey(raw: String): String`, `fun hasKey(raw: String): Boolean` pure helpers.

- [ ] **Step 1: Add security-crypto dependency** to `voiceinput-shared/build.gradle` `dependencies`.

- [ ] **Step 2: Write the failing normalize/key tests**

```kotlin
package org.futo.voiceinput.shared.gemini

import org.junit.Assert.*
import org.junit.Test

class GeminiSettingsTest {

    @Test
    fun `blank key means no key`() {
        assertFalse(GeminiSettings.hasKey("   "))
        assertTrue(GeminiSettings.hasKey("AIza-secret"))
    }

    @Test
    fun `key normalization trims whitespace and newlines`() {
        assertEquals("AIza-secret", GeminiSettings.normalizeKey("  AIza-secret\n"))
    }
}
```

(`hasKey`/`normalizeKey` are pure companion helpers so they run on JVM without Android; the EncryptedSharedPreferences wiring itself is verified by build + manual flows.)

- [ ] **Step 3: Run to verify failure** — `--tests "...GeminiSettingsTest"`. Expected: FAIL.

- [ ] **Step 4: Write `GeminiSettings.kt`** — port the `SettingsStore` pattern: `EncryptedSharedPreferences` (prefs file `"gemini_voice_settings"`, `MasterKey AES256_GCM`, `AES256_SIV` keys / `AES256_GCM` values), single `api_key` string, `smart_mode` boolean default false. Base URL hardcoded (no pref). Companion holds the pure helpers.

- [ ] **Step 5: Run tests** — PASS. Full module build: `./gradlew :voiceinput-shared:assembleDebug` PASS.

- [ ] **Step 6: Commit**

```bash
git add voiceinput-shared/build.gradle voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/GeminiSettings.kt voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/GeminiSettingsTest.kt
git commit -m "feat: encrypted single-key Gemini settings storage"
```

---

### Task 5: `GeminiLiveRunner.kt` — one-shot live transcription (no network in tests)

**Files:**
- Create: `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/GeminiLiveRunner.kt`
- Create: `voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/GeminiLiveRunnerTest.kt`

**Interfaces:**
- Consumes: `TranscriptionRunner` (Task 1), `LiveProtocol.*` (Task 2), `LiveSocket` (Task 3).
- Produces: `class GeminiLiveRunner(private val apiKey: String, private val smartMode: Boolean, private val baseUrl: String = LiveProtocol.DEFAULT_BASE_URL, private val socketFactory: (host: String, path: String, onText: (String) -> Unit, onClosed: (code: Int, reason: String) -> Unit, onFailure: (Throwable) -> Unit) -> LiveSocket = { h, p, t, c, f -> LiveSocket(h, p, t, c, f) { _, _ -> } }) : TranscriptionRunner` with `override suspend fun transcribe(...)`, `override suspend fun preload(...)` (no-op), `override fun cancelAll()` (closes in-flight socket with 1001).

Design lock: **runner takes plain values, the window owns the Context** (reads `GeminiSettings(context)`, passes values in). This keeps the runner JVM-testable with zero Android.

- [ ] **Step 1: Write failing tests (runBlocking — coroutines-core comes via lifecycle deps)**

```kotlin
package org.futo.voiceinput.shared.gemini

import kotlinx.coroutines.runBlocking
import org.futo.voiceinput.shared.types.InferenceState
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelInferenceCallback
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import org.junit.Assert.*
import org.junit.Test

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
            runner.transcribe(FloatArray(1600), FakeConfigs.run(), FakeConfigs.decoding(), fakeCallback())
            fail("expected TranscribeException")
        } catch (e: TranscribeException) {
            assertTrue(e.message!!.contains("API key"))
        }
        assertFalse(opened)
    }
}
```

`FakeConfigs` problem: `MultiModelRunConfiguration` needs a `ModelLoader` and `DecodingConfiguration` needs real values. The runner ignores both (fixed model, languages unused one-shot) — so pass `mock`-free dummies: construct `MultiModelRunConfiguration(primaryModel = DummyLoader, languageSpecificModels = emptyMap())` where `DummyLoader` is a minimal `ModelLoader` stub (throw on `loadGGML` — never called), and `DecodingConfiguration(glossary = emptyList(), languages = emptySet(), suppressSymbols = false)`. `ModelLoader` is an interface with `name: Int`, `exists`, `getRequiredDownloadList`, `loadGGML`, `key` — implement inline in the test with `TODO()` bodies except `exists() = true`. Write these stubs in the test file; do not create production fakes.

- [ ] **Step 2: Run to verify failure** — `--tests "...GeminiLiveRunnerTest"`. Expected: FAIL.

- [ ] **Step 3: Write `GeminiLiveRunner.kt`** — port `liveAttempt` + `LiveAttemptCallbacks` + `awaitLiveOutcome` (with `LIVE_TIMEOUT_MS`/`LIVE_GRACE_MS` closer) + `sendLiveAudio` (setup → activityStart → 3200 B chunks → activityEnd + audioEnd), minus `Rotate` (single key: any 429/401/other → `TranscribeException(mapHttpError(...))` immediately). Flow:
  - `transcribe()`: `callback.updateStatus(InferenceState.LoadingModel)`; blank key → `TranscribeException("No API key — open Settings")`; `callback.updateStatus(InferenceState.Encoding)`; PCM via `LiveProtocol.floatSamplesToPcm16`; socket via factory; `callback.updateStatus(InferenceState.DecodingStarted)`; interim replaces / final appends into a `StringBuilder` (fragments delivered to `callback.partialResult` as the running hypothesis); turn-complete/close commits; timeout-with-interim returns interim, timeout-empty throws `TranscribeException("Timed out — try again")`; cancellation propagates (close socket, rethrow, never convert).
  - `cancelAll()`: close in-flight socket (1001, "cancel").
  - `preload()`: no-op.

- [ ] **Step 4: Run tests** — PASS. Also re-run full module tests.

- [ ] **Step 5: Commit**

```bash
git add voiceinput-shared/src/main/java/org/futo/voiceinput/shared/gemini/GeminiLiveRunner.kt voiceinput-shared/src/test/java/org/futo/voiceinput/shared/gemini/GeminiLiveRunnerTest.kt
git commit -m "feat: GeminiLiveRunner one-shot live transcription

Single key, fail-fast errors, cancellable socket."
```

---

### Task 6: Manifest + engine settings + Smart/Verbatim toggle + runner selection

**Files:**
- Modify: `java/AndroidManifest.xml` (add INTERNET with comment)
- Modify: `java/src/org/futo/inputmethod/latin/uix/VoiceInputSettingKeys.kt` (add `VOICE_ENGINE`)
- Modify: `java/res/values/strings-uix.xml` (new strings)
- Modify: `java/src/org/futo/inputmethod/latin/uix/settings/pages/VoiceInput.kt` (engine dropdown + key field + smart toggle + disclosure)
- Modify: `java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt` (runner selection)

**Interfaces:**
- Consumes: `GeminiSettings` (Task 4), `GeminiLiveRunner`/`WhisperRunner` (Tasks 1, 5).
- Produces: engine pref read at window creation; no new public API.

- [ ] **Step 1: Manifest — add after the RECORD_AUDIO line**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<!-- Required only by the opt-in Gemini voice engine (Settings → Voice Input → Engine: Gemini). Offline Whisper makes no network calls. -->
<uses-permission android:name="android.permission.INTERNET"/>
```

(INTERNET is a normal permission: auto-granted, no runtime prompt.)

- [ ] **Step 2: Setting keys — append to `VoiceInputSettingKeys.kt`**

```kotlin
val VOICE_ENGINE = SettingsKey(
    key = stringPreferencesKey("voice_engine"),
    default = "offline"
)
// Values: "offline" | "gemini". Smart mode lives in GeminiSettings
// (encrypted store), not DataStore.
```

Needs `import androidx.datastore.preferences.core.stringPreferencesKey`.

- [ ] **Step 3: Strings — append near the voice block in `strings-uix.xml`**

```xml
<string name="voice_input_settings_engine">Voice engine</string>
<string name="voice_input_settings_engine_subtitle">Offline transcribes on-device. Gemini sends audio to Google.</string>
<string name="voice_input_settings_engine_offline">Offline (built-in)</string>
<string name="voice_input_settings_engine_gemini">Gemini Live</string>
<string name="voice_input_settings_gemini_key">Gemini API key</string>
<string name="voice_input_settings_gemini_key_subtitle">Single key, stored encrypted on this device</string>
<string name="voice_input_settings_gemini_smart">Smart transcription</string>
<string name="voice_input_settings_gemini_smart_subtitle">Cleaned-up output. Off gives verbatim transcripts.</string>
<string name="voice_input_settings_gemini_disclosure">When Gemini is selected, microphone audio is sent to Google for transcription. Offline mode never leaves the device.</string>
```

- [ ] **Step 4: Settings page — `VoiceInput.kt`**

Add after the backend-system item (keep `visibilityCheckNotSystemVoiceInput` on all new items, matching the page):
  - Engine selector: reuse the `DropDownPickerSettingItem` pattern from the backend-system item with two options (Offline/Gemini) bound to `VOICE_ENGINE` via `useDataStore`.
  - `Tip(...)` with the disclosure string, visible only when engine == gemini.
  - API key field: `UserSettings.kt` has no text-input helper (`userSetting*` helpers cover toggles/navigation/decoration only) — add the key field as a `UserSetting` with a custom `component` composable holding an `OutlinedTextField` bound to `GeminiSettings(context).apiKey` (read once via `remember`, write on Done). Keep it local to this page; do not build a reusable text-field framework.
  - Smart toggle: a `UserSetting` with a custom component toggle bound to `GeminiSettings(context).smartMode` (stays in the encrypted store, NOT DataStore).

- [ ] **Step 5: Runner selection — `VoiceInputAction.kt`**

In `VoiceInputActionWindow` at `RecognizerView` construction: read `context.getSetting(VOICE_ENGINE)`; if `"gemini"` AND `GeminiSettings(context).hasKey()` → `GeminiLiveRunner(apiKey = settings.apiKey, smartMode = settings.smartMode)`; else `WhisperRunner(state.modelManager)`. Pass the chosen runner into `RecognizerView`. Also surface the active engine in the window (e.g. subtitle) so the mic shows which engine listens.

- [ ] **Step 6: Build** — `./gradlew assembleUnstableDebug` PASS (full app, verifies manifest + settings + wiring).

- [ ] **Step 7: Commit**

```bash
git add java/AndroidManifest.xml java/src/org/futo/inputmethod/latin/uix/VoiceInputSettingKeys.kt java/res/values/strings-uix.xml java/src/org/futo/inputmethod/latin/uix/settings/pages/VoiceInput.kt java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt
git commit -m "feat: engine toggle + Gemini key/smart settings + runner selection

INTERNET permission for opt-in Gemini only; default stays offline."
```

---

### Task 7: Regression + manual verification

**Files:** none (verification only).

- [ ] **Step 1: Unit suites green**

Run: `cd /teamspace/studios/this_studio/android-keyboard && ./gradlew :voiceinput-shared:testDebugUnitTest`
Expected: all PASS (LiveProtocol, LiveSocket, GeminiSettings, GeminiLiveRunner suites).

- [ ] **Step 2: Existing Whisper suites green**

Run: `./gradlew testDebugUnitTest` (app module suites: `InputLogicTests*`, `BinaryDictionaryTests`, …).
Expected: PASS — runner refactor changed no Whisper behavior.

- [ ] **Step 3: Lint**

Run: `./gradlew lint`
Expected: no new warnings in touched files.

- [ ] **Step 4: Manual flows** (device/emulator, real key once configured)
  1. Fresh install, no key: mic works offline exactly as before; settings show Engine=Offline.
  2. Engine=Gemini, no key: mic silently uses Whisper (fallback).
  3. Engine=Gemini + key: dictate → transcript commits through sanitizer; mic window shows Gemini.
  4. Bad key: "Check API key in settings" surfaced, no crash.
  5. Airplane mode: "Network error — check connection".
  6. Cancel mid-upload: cancel sound, no partial commit.
  7. Smart ON vs OFF: cleaned vs verbatim output on the same utterance.

- [ ] **Step 5: Record results** — nothing to commit (verification only); note outcomes in the task tracker.

---

## Self-review

- **Spec coverage:** §1 architecture → Task 1+5; §2 files → Tasks 1–4+6 (REST/Interactions/LiveSession/Test-tone exclusions honored; `GeminiSettings` holds key+smart only, base URL fixed); §3 data flow → Task 5 (interim-replace/final-append, grace closer, timeout-with-interim) + Task 6 runner selection + cancel path; §4 settings → Task 6 (engine toggle default offline, encrypted store, disclosure, single key, VERBATIM default) + Smart/Verbatim toggle (user-added requirement, wired to setup `mode`); §5 testing → Task 7 + per-task JVM tests. Single fixed model `gemini-3.5-transcribe-live` pinned in Task 2 (`GEMINI_LIVE_MODEL`, no picker). Gotchas: INTERNET (Task 6 Step 1), `android.util.Base64` (Tasks 2–3, incl. handshake key).
- **Placeholders:** none — every step has exact file paths, code, commands, expected outcomes. Where a name must be verified (`:voiceinput-shared` module name), the step says how.
- **Type consistency:** `TranscriptionRunner.transcribe(samples, runConfig, decodingConfig, callback)` used identically in Tasks 1/5/6; `GeminiLiveRunner(apiKey, smartMode, baseUrl, socketFactory)` plain-value constructor consistent between Tasks 4–6 (window owns Context — locked in Task 5); `preload`/`cancelAll` on the interface per Task 1; `InferenceState` values reused from existing enum.
