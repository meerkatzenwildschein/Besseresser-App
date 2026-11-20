package de.gurum.besseresser.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * HTTP-Fehlerobjekt mit HTTP-Statuscode und Antwort-Body für strukturierte Fehler- und Debug-Reports.
 */
class ChatHttpException(val code: Int, val bodyText: String) :
    RuntimeException("HTTP $code: ${bodyText.take(300)}")

/**
 * Low-Level-HTTP-Client für OpenAI-kompatible /chat/completions Endpunkte mit Connection Pooling und HTTP/2.
 */
object OpenAiChatClient {

    const val BOOSTED_MAX_TOKENS = 16384

    val defaultOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Merkt sich Modelle, deren Reasoning das Standard-Token-Budget gesprengt hat,
     *  damit Folgeaufrufe (und der automatische Retry) sofort mit erhöhtem Budget starten. */
    internal val boostedBudgetModels: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    fun markModelNeedsBoostedBudget(model: String) {
        val key = model.trim().lowercase()
        if (key.isNotBlank()) {
            boostedBudgetModels.add(key)
        }
    }

    fun hasBoostedBudget(model: String): Boolean =
        model.trim().lowercase() in boostedBudgetModels

    /**
     * Führt einen POST-Request an den angegebenen Chat-Completions-Endpunkt aus.
     */
    fun postChat(
        url: String,
        apiKey: String,
        body: JsonObject,
        client: OkHttpClient = defaultOkHttpClient
    ): String {
        NetworkSecurity.requirePermittedEndpoint(url)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .build()

        client.newCall(request).execute().use { response ->
            val code = response.code
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ChatHttpException(code, text)
            }
            return text
        }
    }

    /**
     * Erstellt den JSON-Payload für Chat-Completions inklusive Modell, Temperatur, Budget und Reasoning-Steuerung.
     * Wichtig für OpenRouter / Hybrid-Modelle (wie deepseek-v4.1-flash):
     * Ein bloßes `{"exclude": true}` ohne `"effort": "low"` aktiviert bei OpenRouter volles Reasoning
     * und frisst das `max_tokens`-Budget komplett auf, während die Ausgabe unterdrückt wird (`content: null`).
     */
    fun chatBody(
        model: String,
        baseUrl: String,
        temperature: Double,
        maxTokens: Int,
        messages: JsonArray,
        highBudgetRetry: Boolean = false
    ): JsonObject = buildJsonObject {
        val trimmedModel = model.trim()
        val boosted = highBudgetRetry || hasBoostedBudget(trimmedModel)
        val effectiveMaxTokens = if (boosted) maxOf(maxTokens, BOOSTED_MAX_TOKENS) else maxTokens

        put("model", trimmedModel)
        put("temperature", temperature)
        put("max_tokens", effectiveMaxTokens)
        if (baseUrl.contains("openrouter", true) || baseUrl.contains("deepseek", true) ||
            trimmedModel.contains("deepseek", true)
        ) {
            putJsonObject("reasoning") {
                put("effort", "low")
                put("exclude", !highBudgetRetry)
            }
        }
        put("messages", messages)
    }

    /**
     * Prüft, ob die KI-Antwort wegen des Token-Limits (`finish_reason == "length"`) abgebrochen wurde,
     * bevor überhaupt Antworttext in `content` erzeugt werden konnte.
     */
    fun isLengthTruncatedWithoutContent(resp: JsonObject): Boolean {
        if (!extractContent(resp).isNullOrBlank()) return false
        return try {
            val first = (resp["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
            val finish = first["finish_reason"]?.jsonPrimitive?.contentOrNull
            val nativeFinish = first["native_finish_reason"]?.jsonPrimitive?.contentOrNull
            finish.equals("length", ignoreCase = true) || nativeFinish.equals("length", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Toleranter JSON-Parser für KI-Antworten: Code-Fences strippen, {…}-Block suchen, closeJson-Reparatur.
     */
    fun tolerantJson(content: String): JsonObject = LlmResponseParser.tolerantJson(content)

    /**
     * Holt den Antworttext tolerant aus verschiedenen Provider-Formaten.
     */
    fun extractContent(resp: JsonObject): String? = LlmResponseParser.extractContent(resp)

    /**
     * Extrahiert den Text aus unterschiedlichen `content`-Strukturen (Primitive oder Parts-Array).
     */
    fun parseMessageContent(contentElement: JsonElement?): String? = LlmResponseParser.parseMessageContent(contentElement)

    /**
     * Schließt abgeschnittene JSON-Antworten tolerant: offene Strings schließen,
     * hängende Kommas entfernen und fehlende schließende Klammern ergänzen.
     */
    fun closeJson(text: String): String = LlmResponseParser.closeJson(text)
}
