# Voice Engine: Gemini Live (one-shot) alongside offline Whisper — Design

Date: 2026-09-16
Status: approved (5/5 sections)

## Goal

Give FUTO Keyboard's mic a second transcription engine: the Live WebSocket
transport from the Voice IME Android project (`com.voiceime`), used one-shot
(record → stop → transcribe). Offline Whisper stays the default and the
fallback. Same mic, same window, engine swaps underneath.

## Locked decisions

- **Scope:** fallback, not removal. `voiceinput-shared` + JNI whisper bridge +
  model catalog stay untouched. Gemini is opt-in.
- **Selection:** single mic + explicit engine toggle (Offline default / Gemini),
  mic window shows which engine is active.
- **Transport:** Live WebSocket only. REST `generateContent` and Interactions
  paths are out of scope.
- **Usage:** one-shot live (port of `liveTranscribe`/`liveAttempt`), not
  `LiveSession` streaming. No interim hypotheses during recording; the final
  fires once through the existing partial-result callback.
- **Integration:** `TranscriptionRunner` interface at the `MultiModelRunner`
  call site (Approach 1). No parallel recorder, no delegation to the Voice IME
  app.
- **Keys:** single API key. No rotation (dropped `rotationStart` /
  `Rotate` outcome: first 429 or any error fails fast).
- **Gotcha fixes (baked in):** add `INTERNET` to `java/AndroidManifest.xml`
  (normal permission, auto-granted); replace every `java.util.Base64`
  (API 26+) with `android.util.Base64.NO_WRAP` (minSdk 24). No OkHttp —
  `LiveSocket` is raw TLS + stdlib (`SSLSocketFactory`, `org.json`,
  `URLEncoder` are all API-24-safe).

## 1. Architecture

New `TranscriptionRunner` interface in `voiceinput-shared` at the exact point
where `AudioRecognizer.runModel()` calls `MultiModelRunner.run()` today:

- `WhisperRunner` — existing `MultiModelRunner` logic, unchanged behavior
  (offline default).
- `GeminiLiveRunner` — one-shot Live: finished `FloatArray` → 16 kHz PCM →
  open `LiveSocket` → setup + activity-start + PCM chunks + end markers →
  await final → return `String`.

`AudioRecognizer` depends only on the interface. `VoiceInputActionWindow`
picks the implementation from the engine setting, falling back to Whisper
when Gemini has no key. Recording, VAD, `RecognizerView`,
`ModelOutputSanitizer`, and the commit path are unchanged.

## 2. Components & files

New files in `voiceinput-shared/.../shared/gemini/`, ported from
`com.voiceime` with the Base64 fix:

| File | Source | Notes |
|---|---|---|
| `TranscriptionRunner.kt` | new | `suspend fun transcribe(samples: FloatArray, ...): String` + `TranscribeException` |
| `WhisperRunner.kt` | wraps `MultiModelRunner` | offline adapter (~30 lines) |
| `GeminiLiveRunner.kt` | `LlmClient.liveTranscribe` + `liveAttempt` + `LiveAttemptCallbacks` + `awaitLiveOutcome` + `sendLiveAudio` + `LiveOutcome` (minus `Rotate`) | REST/Interactions **not** ported |
| `LiveSocket.kt` | verbatim port | raw-TLS RFC 6455 client, unchanged logic |
| `LiveProtocol.kt` | live-only subset of `GeminiTransports.kt` | setup/activity/audio builders + parsers, `liveHost`, `livePath`, `LIVE_*` consts, `normalizeKeys`, `mapHttpError` |
| `GeminiSettings.kt` | subset of `SettingsStore` | key + base URL + smart mode (model fixed, no picker), own EncryptedSharedPreferences file |

Modified: `AudioRecognizer.kt` (takes `TranscriptionRunner`; float→PCM16
helper for the Gemini path), `java/AndroidManifest.xml` (INTERNET + comment),
`VoiceInputAction.kt` (engine switch + no-key Whisper fallback).

Not ported: `VoiceImeService`, `AudioRecorder`, `WaveformView`, clipboard
history, `LiveSession` streaming, Test-tone button.

## 3. Data flow (Gemini selected)

1. Mic tap → record exactly as today (VAD, 16 kHz float buffer; no model
   preload for Gemini).
2. Stop → `runModel()` → `GeminiLiveRunner.transcribe(floatSamples)` on
   `Dispatchers.Default`.
3. Float→PCM16 → TLS connect (`Dispatchers.IO`) → setup JSON (model, TEXT
   modality, manual VAD disabled, VERBATIM/SMART) → `activityStart` → PCM
   chunks (3200 B) → `activityEnd` + `audioStreamEnd` → await outcome bounded
   by `LIVE_TIMEOUT_MS` with `LIVE_GRACE_MS` closer. Interim fragments replace
   running text; finals append.
4. Transcript → existing `runnerCallback` (final fires once) →
   `ModelOutputSanitizer.sanitize` → `inputTransaction.commit`.
5. Cancel/close → close in-flight socket (`code 1001`) → existing cancelled
   path.

User-safe errors (keys/audio never logged): no key → silent Whisper fallback
at selection; 401 → "Check API key in settings"; 429 → "Rate limited — retry";
network → "Network error — check connection"; timeout with interim text →
commit what was heard; timeout with nothing → "Timed out — try again".

## 4. Settings & keys

- `VOICE_ENGINE` DataStore pref (`Offline` default / `Gemini`), dropdown on
  the existing `VoiceInput.kt` settings page. Default = today's behavior, no
  migration.
- `GeminiSettings.kt`: single key, base URL (default
  `https://generativelanguage.googleapis.com/v1beta`, fail-closed https).
  Model is fixed to `gemini-3.5-transcribe-live` (no model picker — the only
  supported Live model). Smart mode (default VERBATIM). Own
  EncryptedSharedPreferences file.
- Settings UI on the VoiceInput page with an explicit "sends audio to Google
  for transcription" disclosure under the engine toggle.
- Model decision (closed 2026-09-16): fixed to `gemini-3.5-transcribe-live`,
  the live model exercised end-to-end in the Voice IME live-setup tests. The
  routing heuristic still matches `-live` markers, but no other model is
  exposed or supported.

## 5. Testing

- New `voiceinput-shared/src/test` JVM tests (+ JUnit4 dep; module has none
  today): setup-JSON shape, chunk base64 round-trip, transcript parsers
  (final-wins, interim-fallback, snake_case), turn/setup-complete detection,
  float→PCM16 bounds, URL normalize fail-closed, error mapping.
- `LiveSocket`: loopback handshake test if cheap, else compile gate + manual.
- Whisper regression: existing `tests/src` suites stay green.
- Manual: dictate→commit, bad key hint, airplane-mode error, cancel mid-upload,
  timeout-with-interim commit, offline default untouched (no socket opened).
