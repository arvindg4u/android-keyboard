package org.futo.voiceinput.shared.gemini

import android.util.Base64
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

object LiveProtocol {
    const val GEMINI_LIVE_MODEL = "gemini-3.5-transcribe-live"
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    const val LIVE_TIMEOUT_MS = 75_000L
    const val LIVE_GRACE_MS = 8_000L
    const val LIVE_CHUNK_BYTES = 3200
    const val LIVE_PCM_MIME = "audio/pcm;rate=16000"

    const val LIVE_RPC_PATH =
        "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val LIVE_DEFAULT_HOST = "generativelanguage.googleapis.com"

    fun buildLiveSetupJson(smartMode: Boolean): String =
        JSONObject()
            .put(
                "setup",
                JSONObject()
                    .put("model", "models/$GEMINI_LIVE_MODEL")
                    .put(
                        "generationConfig",
                        JSONObject().put("responseModalities", JSONArray().put("TEXT")),
                    )
                    .put(
                        "realtimeInputConfig",
                        JSONObject().put(
                            "automaticActivityDetection",
                            JSONObject().put("disabled", true),
                        ),
                    )
                    .put(
                        "inputAudioTranscription",
                        JSONObject()
                            .put("languageCodes", JSONArray())
                            .put("mode", if (smartMode) "SMART" else "VERBATIM"),
                    ),
            )
            .toString()

    fun buildLiveActivityStartJson(): String =
        JSONObject()
            .put("realtimeInput", JSONObject().put("activityStart", JSONObject()))
            .toString()

    fun buildLiveActivityEndJson(): String =
        JSONObject()
            .put("realtimeInput", JSONObject().put("activityEnd", JSONObject()))
            .toString()

    fun buildLiveAudioMessage(pcmChunk: ByteArray): String {
        val data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
        return JSONObject()
            .put(
                "realtimeInput",
                JSONObject()
                    .put(
                        "audio",
                        JSONObject()
                            .put("mimeType", LIVE_PCM_MIME)
                            .put("data", data),
                    ),
            )
            .toString()
    }

    fun buildLiveAudioEndJson(): String =
        JSONObject()
            .put("realtimeInput", JSONObject().put("audioStreamEnd", true))
            .toString()

    fun parseLiveInputTranscripts(serverJson: String): List<String> {
        val root = try {
            JSONObject(serverJson)
        } catch (e: Exception) {
            return emptyList()
        }
        val serverContent = root.optJSONObject("serverContent")
            ?: root.optJSONObject("server_content")
            ?: return emptyList()
        val transcription = serverContent.optJSONObject("inputTranscription")
            ?: serverContent.optJSONObject("input_transcription")
        val interim = serverContent.optJSONObject("interimInputTranscription")
            ?: serverContent.optJSONObject("interim_input_transcription")
        val text = transcription?.optString("text").orEmpty()
            .ifEmpty { interim?.optString("text").orEmpty() }
        if (text.isEmpty()) return emptyList()
        return listOf(text)
    }

    fun hasLiveFinalTranscript(serverJson: String): Boolean {
        val root = try {
            JSONObject(serverJson)
        } catch (e: Exception) {
            return false
        }
        val serverContent = root.optJSONObject("serverContent")
            ?: root.optJSONObject("server_content")
            ?: return false
        val transcription = serverContent.optJSONObject("inputTranscription")
            ?: serverContent.optJSONObject("input_transcription")
            ?: return false
        return transcription.optString("text").orEmpty().isNotEmpty()
    }

    fun isLiveSetupComplete(serverJson: String): Boolean {
        val root = try {
            JSONObject(serverJson)
        } catch (e: Exception) {
            return false
        }
        return root.has("setupComplete") || root.has("setup_complete")
    }

    fun isLiveTurnComplete(serverJson: String): Boolean {
        val root = try {
            JSONObject(serverJson)
        } catch (e: Exception) {
            return false
        }
        val serverContent = root.optJSONObject("serverContent")
            ?: root.optJSONObject("server_content")
            ?: return false
        if (serverContent.optBoolean("turnComplete", false)) return true
        return serverContent.optBoolean("generationComplete", false)
    }

    fun liveHost(baseUrl: String): String {
        val parsedHost = try {
            URL(baseUrl).host.orEmpty()
        } catch (e: Exception) {
            ""
        }
        return parsedHost.ifEmpty { LIVE_DEFAULT_HOST }
    }

    fun livePath(baseUrl: String, key: String): String =
        "$LIVE_RPC_PATH?key=${URLEncoder.encode(key, "UTF-8")}"

    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return DEFAULT_BASE_URL
        url = url.trimEnd('/')
        if (url.endsWith("/chat/completions")) {
            url = url.removeSuffix("/chat/completions").trimEnd('/')
        }
        if (url.isEmpty()) return DEFAULT_BASE_URL
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "https") return DEFAULT_BASE_URL
        return url
    }

    fun normalizeKey(raw: String): String = raw.trim()

    fun mapHttpError(code: Int, body: String): String {
        val base = when (code) {
            401 -> "Check API key in settings"
            404, 405 -> "Model not found or no access — check model"
            429 -> "Rate limited — retry"
            else -> "Server error (HTTP $code)"
        }
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().trim()
        } catch (e: Exception) {
            ""
        }
        if (detail.isEmpty()) return base
        return "$base — ${detail.take(200)}"
    }

    fun floatSamplesToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            out[i * 2] = (v.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((v.toInt() ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Direct Short -> LE PCM16 bytes without the float round-trip. Used by
     * the TRUE STREAMING recorder path so live chunks match the one-shot
     * [floatSamplesToPcm16] bytes exactly (float32 x/32767 then LE int16).
     * [length] bounds the valid prefix of [samples] (recorder reuses buffer).
     */
    fun shortSamplesToPcm16(samples: ShortArray, length: Int): ByteArray {
        val n = length.coerceIn(0, samples.size)
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }
}
