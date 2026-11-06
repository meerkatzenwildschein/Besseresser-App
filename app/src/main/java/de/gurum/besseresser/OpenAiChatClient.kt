package de.gurum.besseresser

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
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections

/**
 * HTTP-Fehlerobjekt mit HTTP-Statuscode und Antwort-Body für strukturierte Fehler- und Debug-Reports.
 */
class ChatHttpException(val code: Int, val bodyText: String) :
    RuntimeException("HTTP $code: ${bodyText.take(300)}")

/**
 * Low-Level-HTTP-Client für OpenAI-kompatible /chat/completions Endpunkte.
 */
object OpenAiChatClient {

    const val BOOSTED_MAX_TOKENS = 16384

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
     * Führt einen synchronen POST-Request an den angegebenen Chat-Completions-Endpunkt aus.
     */
    fun postChat(url: String, apiKey: String, body: JsonObject): String {
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ChatHttpException(code, text)
            return text
        } finally {
            conn.disconnect()
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
    fun tolerantJson(content: String): JsonObject {
        val cleaned = content.replace("```json", "", ignoreCase = true).replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        check(start >= 0 && end > start) { "KI-Antwort ohne JSON" }
        val jsonStr = cleaned.substring(start, end + 1)
        return try {
            AppJson.parseToJsonElement(jsonStr).jsonObject
        } catch (e: Exception) {
            AppJson.parseToJsonElement(closeJson(jsonStr)).jsonObject
        }
    }

    /**
     * Holt den Antworttext tolerant aus verschiedenen Provider-Formaten.
     */
    fun extractContent(resp: JsonObject): String? {
        return try {
            val choices = resp["choices"] as? JsonArray ?: return null
            val first = choices.firstOrNull() as? JsonObject ?: return null

            // 1) Standard: choices[0].message.content (String oder Parts-Array)
            val msg = first["message"] as? JsonObject
            val contentFromMsg = parseMessageContent(msg?.get("content"))
            if (!contentFromMsg.isNullOrBlank()) {
                return contentFromMsg
            }
            val reasoning = msg?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: msg?.get("reasoning")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            if (!reasoning.isNullOrBlank()) {
                return reasoning
            }
            val text = first["text"]?.jsonPrimitive?.contentOrNull
            if (!text.isNullOrBlank()) {
                return text
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extrahiert den Text aus unterschiedlichen `content`-Strukturen (Primitive oder Parts-Array).
     */
    fun parseMessageContent(contentElement: JsonElement?): String? = when (contentElement) {
        is JsonPrimitive -> contentElement.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> {
            // Manche Anbieter liefern ein Parts-Array: [{"type":"text","text":"..."}]
            val parts = StringBuilder()
            for (item in contentElement) {
                val p = item as? JsonObject ?: continue
                val t = p["text"]?.jsonPrimitive?.contentOrNull
                if (!t.isNullOrBlank()) parts.append(t)
            }
            parts.toString().takeIf { it.isNotBlank() }
        }
        else -> null
    }

    /**
     * Schließt abgeschnittene JSON-Antworten tolerant: offene Strings schließen,
     * hängende Kommas entfernen und fehlende schließende Klammern ergänzen.
     */
    fun closeJson(text: String): String {
        var t = text.trimEnd()
        var inString = false
        var escaped = false
        val stack = ArrayDeque<Char>()
        t.forEach { c ->
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && (c == '{' || c == '[') -> stack.addLast(if (c == '{') '}' else ']')
                !inString && (c == '}' || c == ']') -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        t = t.trimEnd().trimEnd(',')
        if (inString) t += "\""
        while (stack.isNotEmpty()) t += stack.removeLast()
        return t
    }
}
