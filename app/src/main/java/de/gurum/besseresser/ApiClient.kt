package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** KI-Anweisungstexte, lokalisiert vom Aufrufer erzeugt (App-/Systemsprache). */
data class PromptBundle(
    val intro: String,
    val formatIntro: String,
    val ampelMeaning: String,
    val exactlyOne: String,
    val responseLanguage: String,
    // Formatierte Nutzer-Anmerkung (leer = keine); wird nur bei nicht-leerem Text in den Prompt übernommen
    val noteHeader: String = "",
    // Anweisungs-Satz für das "erkannt"-Feld (was ist auf dem Foto zu sehen)
    val recognized: String = "",
    // „Sei ehrlich"-Zeile gegen Halluzination
    val honesty: String = ""
)

/** KI-Anweisungstexte für Tages- und Wochenfazits. */
data class FazitPromptBundle(
    val promptIntro: String,
    val promptRules: String,
    val promptLanguage: String,
    val promptHonesty: String = ""
)

/** Parameter für die KI-gestützte Aufteilung von Zielen. */
data class SplitGoalsRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val rawGoals: String,
    val splitIntro: String,
    val splitRules: String
)

/** Ergebnis eines Tages- oder Wochenfazits von der KI. */
data class DayFazitResult(
    val ampel: TrafficLight,
    val text: String
)

/** Spezifische Ausnahme für API-Fehler inkl. optionalem Debug-Report für die UI. */
class ApiException(
    message: String,
    val debugReport: String? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** Schnittstelle für Netzwerkoperationen mit der OpenAI-kompatiblen KI-Schnittstelle. */
interface BesseresserApiService {
    suspend fun analyze(
        ctx: Context,
        photoUri: Uri,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation>

    suspend fun analyzeText(
        ctx: Context,
        settings: Settings,
        userDescription: String
    ): Result<Evaluation>

    suspend fun dayFazit(
        settings: Settings,
        goals: List<String>,
        promptContext: String?,
        mealLines: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazitResult>

    suspend fun splitGoals(
        request: SplitGoalsRequest
    ): Result<List<String>>

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String
    ): Result<ApiClient.ModelsResult>
}

/** Kommunikation mit der OpenAI-kompatiblen API: Foto-Bewertung, Modell-Liste, Ziele-Aufteilung. */
object ApiClient : BesseresserApiService {
    private const val CHAT_COMPLETIONS_PATH = "/chat/completions"

    // ---------------------------------------------------------------- gemeinsame Call-Bausteine

    /** Reiner Post an /chat/completions; wirft ChatHttpException (mit Code + Roh-Body) bei Fehler,
     *  liefert sonst den Antwort-Body als String. */
    private fun postChat(url: String, apiKey: String, body: JsonObject): String {
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

    /** HTTP-Fehlerobjekt mit Code + Roh-Body für Debug-Reports/Toasts. */
    private class ChatHttpException(val code: Int, val bodyText: String) :
        RuntimeException("HTTP $code: ${bodyText.take(300)}")

    /** Gemeinsamer JSON-Request-Kern: Modell/Temperatur/Budget + Reasoning-Aus für OpenRouter/DeepSeek. */
    private fun chatBody(
        model: String,
        baseUrl: String,
        temperature: Double,
        maxTokens: Int,
        messages: JsonArray
    ): JsonObject = buildJsonObject {
        put("model", model.trim())
        put("temperature", temperature)
        // Budget großzügig: Reasoning-Modelle „denken“ vom selben Konto —
        // zu knapp → finish=length mit LEEREM content (klassischer stiller Fehler)
        put("max_tokens", maxTokens)
        if (baseUrl.contains("openrouter", true) || baseUrl.contains("deepseek", true) ||
            model.contains("deepseek", true)
        ) {
            putJsonObject("reasoning") {
                put("exclude", true)
            }
        }
        put("messages", messages)
    }

    /** Toleranter JSON-Parser für KI-Antworten: Code-Fences strippen, {…}-Block suchen, closeJson-Reparatur. */
    private fun tolerantJson(content: String): JsonObject {
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

    // ---------------------------------------------------------------- Foto bewerten

    override suspend fun analyze(
        ctx: Context,
        photoUri: Uri,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation> = withContext(Dispatchers.IO) {
        runCatching {
            val dataUri = prepareImageDataUri(ctx, photoUri)
            val prompt = createAnalyzePrompt(settings.goals, promptBundle)
            val body = buildAnalyzeBody(settings, prompt, dataUri)
            val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
            val text = postChat(url, settings.apiKey, body)

            // Antwort parsen
            val resp = AppJson.parseToJsonElement(text).jsonObject
            val rawContent = extractContent(resp)
            if (rawContent.isNullOrBlank()) {
                throw buildAnalyzeMissingContentException(resp, text)
            }

            parseEvaluation(rawContent, settings.goals)
        }
    }

    private fun prepareImageDataUri(ctx: Context, photoUri: Uri): String {
        val resolver = ctx.contentResolver
        val bitmap = resolver.openInputStream(photoUri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
        val scaled = scaleDown(bitmap, 1280)
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val dataUri = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        if (scaled !== bitmap) scaled.recycle()
        return dataUri
    }

    private fun createAnalyzePrompt(goals: List<String>, promptBundle: PromptBundle): String = buildString {
        appendLine(promptBundle.intro)
        goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
        if (promptBundle.noteHeader.isNotBlank()) {
            appendLine("")
            appendLine(promptBundle.noteHeader)
        }
        appendLine("")
        // JSON-Schluessel (bewertung/ziel/ampel/begruendung/zusatz/erkannt) sind bewusst
        // fest verdrahtet – der Parser kennt diese Namen; nur die Anweisungssprache variiert.
        appendLine(promptBundle.formatIntro)
        appendLine("""{"bewertung":[{"ziel":"<exaktes Ziel>","ampel":"gruen|gelb|rot","begruendung":"<short reason>"}],"zusatz":"<one-sentence overall impression>","erkannt":"<one short sentence: food with estimated quantities, e.g. '100 g pasta with sauce, one apple'>"}""")
        appendLine(promptBundle.ampelMeaning)
        appendLine(promptBundle.exactlyOne)
        if (promptBundle.recognized.isNotBlank()) appendLine(promptBundle.recognized)
        if (promptBundle.honesty.isNotBlank()) appendLine(promptBundle.honesty)
        appendLine(promptBundle.responseLanguage)
    }

    private fun buildAnalyzeBody(settings: Settings, prompt: String, dataUri: String): JsonObject =
        chatBody(
            model = settings.model,
            baseUrl = settings.baseUrl,
            temperature = 0.3,
            maxTokens = 4096,
            messages = buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", prompt)
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", dataUri)
                                }
                            })
                        }
                    }
                )
            }
        )

    private fun buildAnalyzeMissingContentException(resp: JsonObject, text: String): IllegalStateException {
        val modelName = try { resp["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "unbekannt" } catch (e: Exception) { "unbekannt" }
        val finish = try {
            val choices = resp["choices"]?.jsonArray
            choices?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) { null }
        val hint = when {
            finish == "length" &&
                (modelName.contains("deepseek", true) || modelName.contains("reason", true)) ->
                "Das Reasoning des Modells hat das Token-Budget verbraucht, bevor ein Antworttext kam. Erneut versuchen – die App setzt für den nächsten Versuch ein deutlich höheres Budget."
            finish == "length" ->
                "Die Antwort wurde durch das Token-Limit abgeschnitten. Erneut versuchen oder ein weniger aufwendiges Modell wählen."
            else -> "Evtl. ungewöhnliches Antwortformat – bitte ein anderes Modell versuchen."
        }
        return IllegalStateException(
            "Die KI hat keinen Text geliefert (Modell: $modelName). $hint " +
                "Roh-Antwort-Auszug: ${text.take(400).ifBlank { "(leer)" }}"
        )
    }

    /** Text-only Tagesfazit: Ziele + optional Nutzerprofil-Kontext + Mahlzeiten-Zeilen
     *  (Zeit · Erkanntes mit Menge · Anmerkung) → JSON {ampel, text}.
     *  Kein Foto, kein Bilder-Modell nötig; benutzt denselben /chat/completions-Endpunkt. */
    override suspend fun dayFazit(
        settings: Settings,
        goals: List<String>,
        promptContext: String?,          // z. B. Nutzerprofil-Zeile (optional)
        mealLines: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazitResult> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = createDayFazitPrompt(goals, promptContext, mealLines, prompts)
            val body = chatBody(
                model = settings.model,
                baseUrl = settings.baseUrl,
                temperature = 0.3,
                maxTokens = 3000,
                messages = buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )

            val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
            val respText = postChat(url, settings.apiKey, body)

            val respObj = AppJson.parseToJsonElement(respText).jsonObject
            val rawContent = extractContent(respObj)
            if (rawContent.isNullOrBlank()) {
                throw buildDayFazitMissingContentException(respObj, respText)
            }

            // tolerantes JSON-Parsing (Code-Fences, closeJson)
            val obj = tolerantJson(rawContent)
            val ampel = TrafficLight.fromString(pickString(obj, listOf("ampel", "rating", "status", "farbe")))
            val fazitText = pickString(obj, listOf("text", "fazit", "summary"))
            // Ampel UNKNOWN ist okay (grauer Chip) – nur ganz ohne Text ist ein echter Fehler.
            check(fazitText.isNotBlank()) { "KI-Antwort ohne Fazit-Text (Roh: ${rawContent.take(200)})" }
            DayFazitResult(ampel, fazitText.trim())
        }
    }

    private fun createDayFazitPrompt(
        goals: List<String>,
        promptContext: String?,
        mealLines: List<String>,
        prompts: FazitPromptBundle
    ): String = buildString {
        appendLine(prompts.promptIntro)
        goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
        promptContext?.takeIf { it.isNotBlank() }?.let {
            appendLine("")
            appendLine(it)
        }
        appendLine("")
        mealLines.forEach { appendLine(it) }
        appendLine("")
        appendLine(prompts.promptRules)
        if (prompts.promptHonesty.isNotBlank()) appendLine(prompts.promptHonesty)
        appendLine("")
        // JSON-Schluessel (ampel/text) fest verdrahtet, Sprache via promptLanguage
        appendLine("""{"ampel":"gruen|gelb|rot","text":"<2-3 sentences: day summary in the app language, directly to the point>"}""")
        appendLine(prompts.promptLanguage)
    }

    private fun buildDayFazitMissingContentException(respObj: JsonObject, respText: String): IllegalStateException {
        val finish = try {
            val choices = respObj["choices"]?.jsonArray
            choices?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) { null }
        val modelName = try { respObj["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "?" } catch (e: Exception) { "?" }
        val hint = when (finish) {
            "length" -> "Das Token-Budget wurde verbraucht, bevor ein Antworttext kam – Modell erneut versuchen."
            "content_filter" -> "Der Anbieter hat die Antwort gefiltert (Inhalt blockiert). Versuch es erneut oder mit anderem Modell."
            else -> "Antwortformat unbekannt für dieses Modell."
        }
        return IllegalStateException(
            "Die KI hat keinen Text geliefert (Modell: $modelName, finish: $finish). $hint Roh: ${respText.take(300)}"
        )
    }

    /** Text-only Analyse (Nacherfassung OHNE Foto): Beschreibung der Mahlzeit statt Foto.
     *  Gleicher JSON-Kontrakt wie analyze, aber ohne Bild */
    override suspend fun analyzeText(
        ctx: Context,
        settings: Settings,
        userDescription: String
    ): Result<Evaluation> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = buildString {
                appendLine(ctx.getString(R.string.prompt_eval_intro_textonly))
                settings.goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
                appendLine("")
                appendLine(ctx.getString(R.string.prompt_textonly_context, userDescription.trim()))
                appendLine("")
                appendLine(ctx.getString(R.string.prompt_eval_format))
                appendLine("""{"bewertung":[{"ziel":"<exaktes Ziel>","ampel":"gruen|gelb|rot","begruendung":"<short reason>"}],"zusatz":"<one-sentence overall impression>","erkannt":"<one short sentence: what the description says the meal was, with quantities>"}""")
                appendLine(ctx.getString(R.string.prompt_eval_ampel_meaning))
                appendLine(ctx.getString(R.string.prompt_eval_exactly_one))
                appendLine(ctx.getString(R.string.prompt_honesty))
                appendLine(ctx.getString(R.string.prompt_eval_language))
            }
            val body = chatBody(
                model = settings.model,
                baseUrl = settings.baseUrl,
                temperature = 0.3,
                maxTokens = 4096,
                messages = buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )

            val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
            val text = postChat(url, settings.apiKey, body)

            val resp = AppJson.parseToJsonElement(text).jsonObject
            val rawContent = extractContent(resp)
            check(!rawContent.isNullOrBlank()) { "Die KI hat keinen Text geliefert. Roh-Antwort: ${text.take(300)}" }

            parseEvaluation(rawContent, settings.goals)
        }
    }

    // ---------------------------------------------------------------- Ziele aufteilen (KI-Assistenz)

    /** KI teilt Freitext („kein Zucker, 2l Wasser, Intervallfasten") in saubere Einzel-Ziele.
     *  Im Fehlerfall enthält die Exception einen mehrzeiligen Debug-Report inkl. Rohantwort. */
    override suspend fun splitGoals(
        request: SplitGoalsRequest
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val requestUrl = request.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
        try {
            val prompt = buildString {
                appendLine(request.splitIntro)
                appendLine(request.splitRules)
                appendLine("Reply ONLY with a JSON object (no text before or after):")
                appendLine("""{"ziele":["Goal 1","Goal 2"]}""")
                appendLine()
                appendLine(request.rawGoals)
            }
            val req = chatBody(
                model = request.model,
                baseUrl = request.baseUrl,
                temperature = 0.1,
                maxTokens = 1600,
                messages = buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            )
            val respBody = postChat(requestUrl, request.apiKey, req)

            val content = extractContent(AppJson.parseToJsonElement(respBody).jsonObject)
                ?: throw IllegalStateException("Kein Antwortinhalt")
            val obj = tolerantJson(content)
            val rawArr = obj["ziele"]?.takeIf { it is JsonArray }?.jsonArray
                ?: obj["goals"]?.takeIf { it is JsonArray }?.jsonArray
                ?: obj["ziel_liste"]?.takeIf { it is JsonArray }?.jsonArray
                ?: run {
                    // Fallback: irgendein String-Array im Objekt
                    obj.values.asSequence().mapNotNull { it as? JsonArray }.firstOrNull { arr ->
                        arr.isNotEmpty() && arr[0].jsonPrimitive.contentOrNull?.isNotBlank() == true
                    } ?: JsonArray(emptyList())
                }
            val goals = rawArr.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
            check(goals.isNotEmpty()) { "KI lieferte keine Ziele" }
            Result.success(goals)
        } catch (e: Exception) {
            val debug = buildString {
                appendLine("Anfrage:  POST $requestUrl (Modell: ${request.model})")
                appendLine("Status:   HTTP ${(e as? ChatHttpException)?.code ?: "?"}")
                appendLine("Fehler:   ${e.message ?: e.javaClass.simpleName}")
                appendLine()
                appendLine("Rohantwort (erste 800 Zeichen):")
                appendLine((e as? ChatHttpException)?.bodyText.orEmpty().take(800).ifBlank { "(leer bzw. Netzfehler)" })
            }
            Result.failure(ApiException(e.message ?: e.javaClass.simpleName, debug, e))
        }
    }

    /** Ziele-Freitext lokal an Satzzeichen teilen (Rueckfall ohne KI). */
    fun splitGoalsLocally(rawGoals: String): List<String> =
        rawGoals.split(Regex("[.,;:!]\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 2 }

    // ---------------------------------------------------------------- Modell-Liste

    /** Ergebnis von /models: vision-gefilterte Modell-IDs + alle Modell-IDs. */
    data class ModelsResult(val vision: List<String>, val all: List<String>)

    private val VISION_PATTERNS = listOf(
        "gpt-4o", "gpt-4.1", "gpt-4-vision", "chatgpt-4o", "o1", "o3", "o4",
        "claude-3", "claude-4", "gemini", "llava", "pixtral", "vision",
        "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen2.5vl", "-vl", "vl-",
        "glm-4v", "internvl", "deepseek-vl", "minicpm-v", "moondream", "smolvlm"
    )

    /** Fragt {baseUrl}/models ab und filtert auf vision-taugliche Modelle.
     *  Im Fehlerfall enthält die Exception einen mehrzeiligen Debug-Report inkl. Rohantwort. */
    override suspend fun fetchModels(
        baseUrl: String,
        apiKey: String
    ): Result<ModelsResult> = withContext(Dispatchers.IO) {
        val requestUrl = baseUrl.trim().trimEnd('/') + "/models"
        var httpCode = -1
        var body = ""
        try {
            val conn = (URI.create(requestUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            try {
                httpCode = conn.responseCode
                val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
                body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
            check(httpCode in 200..299) { "HTTP $httpCode" }

            // Antwort robust auswerten: {"data":[…]}, {"models":[…]} oder direkt [ … ]
            val arr = parseModelsArray(body)
            check(arr.isNotEmpty()) { "Antwort enthält keine Modell-Liste" }

            val entries = parseModelEntries(arr)
            check(entries.isNotEmpty()) {
                "Modell-Liste enthielt ${arr.size} Einträge, aber keine nutzbare Modell-IDs"
            }
            Result.success(filterVisionModels(entries))
        } catch (e: Exception) {
            val debug = buildString {
                appendLine("Anfrage:  GET $requestUrl")
                appendLine("Status:   HTTP $httpCode")
                appendLine("Fehler:   ${e.message ?: e.javaClass.simpleName}")
                appendLine()
                appendLine("Rohantwort (erste 800 Zeichen):")
                appendLine(body.take(800).ifBlank { "(leer)" })
            }
            Result.failure(ApiException(e.message ?: e.javaClass.simpleName, debug, e))
        }
    }

    private fun parseModelsArray(body: String): JsonArray {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("[") -> try { AppJson.parseToJsonElement(trimmed).jsonArray } catch (e: Exception) { JsonArray(emptyList()) }
            trimmed.startsWith("{") -> {
                val root = try { AppJson.parseToJsonElement(trimmed).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) }
                root["data"]?.takeIf { it is JsonArray }?.jsonArray
                    ?: root["models"]?.takeIf { it is JsonArray }?.jsonArray
                    ?: root["model_list"]?.takeIf { it is JsonArray }?.jsonArray
                    ?: JsonArray(emptyList())
            }
            else -> JsonArray(emptyList())
        }
    }

    private fun parseModelEntries(arr: JsonArray): List<Pair<String, Boolean>> =
        arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            // Exakte Info, falls der Provider sie liefert (z. B. OpenRouter)
            val modalities = (obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray
            val supportsImage = modalities != null && modalities.any {
                it.jsonPrimitive.contentOrNull?.equals("image", ignoreCase = true) == true
            }
            id to supportsImage
        }

    private fun filterVisionModels(entries: List<Pair<String, Boolean>>): ModelsResult {
        val all = entries.map { it.first }.sorted()
        val flagged = entries.filter { it.second }.map { it.first }.sorted()
        if (flagged.isNotEmpty()) return ModelsResult(flagged, all)
        // Keine Modalitaets-Info vom Provider → Namensheuristik
        val guessed = all.filter { id -> VISION_PATTERNS.any { id.lowercase().contains(it) } }
        if (guessed.isNotEmpty()) return ModelsResult(guessed, all)
        // Nichts erkennbar → vollstaendige Liste, ungefiltert
        return ModelsResult(emptyList(), all)
    }

    // ---------------------------------------------------------------- Parsing-Helfer

    /** Holt den Antworttext tolerant aus verschiedenen Provider-Formaten. */
    private fun extractContent(resp: JsonObject): String? {
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

    private fun parseMessageContent(contentElement: JsonElement?): String? = when (contentElement) {
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

    /** Schließt abgeschnittene JSON-Antworten tolerant: offene Strings schließen,
     *  hängende Kommas entfernen und fehlende schließende Klammern ergänzen. */
    private fun closeJson(text: String): String {
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

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val maxOrig = maxOf(bmp.width, bmp.height)
        if (maxOrig <= maxSide) return bmp
        val factor = maxSide.toFloat() / maxOrig
        return Bitmap.createScaledBitmap(bmp, (bmp.width * factor).toInt(), (bmp.height * factor).toInt(), true)
    }

    private fun parseEvaluation(content: String, goals: List<String>): Evaluation {
        val obj = tolerantJson(content)
        val arr = findResultArray(obj)
        val list = mutableListOf<GoalResult>()
        for ((goalIndex, goal) in goals.withIndex()) {
            val m = findItemForGoal(arr, goal, goals.size, goalIndex)
            if (m != null) {
                list.add(
                    GoalResult(
                        goal = goal,
                        ampel = TrafficLight.fromString(pick(m, AMP_KEYS)),
                        begruendung = pick(m, BEG_KEYS)
                    )
                )
            } else {
                list.add(GoalResult(goal, TrafficLight.UNKNOWN, "Keine Bewertung erhalten."))
            }
        }
        val summary = pickString(obj, listOf("zusatz", "summary"))
        val erkannt = stripErkanntIntro(
            pickString(obj, listOf("erkannt", "detected", "food", "meal"))
        )
        return Evaluation(list, summary, erkannt)
    }

    private val AMP_KEYS = listOf("ampel", "ampelfarbe", "farbe", "color", "status", "rating", "trafficlight")
    private val ZIEL_KEYS = listOf("ziel", "goal", "zielname", "name", "thema", "title")
    private val BEG_KEYS = listOf("begruendung", "reason", "erklaerung", "beschreibung", "kommentar", "text", "note")

    // Typische Vorspann-Floskeln im "erkannt"-Feld. Der Prompt verbietet sie bereits;
    // der Stripper fängt Restmodelle ab, die sich trotzdem dran halten (de + en).
    private val ERKANNT_INTRO_PATTERNS = listOf(
        Regex("^zu sehen (sind|ist)\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^zu sehen\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^zu erkennen\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^erkennbar\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^auf dem (foto|bild) (ist|sind) (zu sehen|erkennbar|zu erkennen|erkennend)\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^auf dem (foto|bild) (ist|sind)\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^auf dem (foto|bild)\\b[:,:\\s]*", RegexOption.IGNORE_CASE),
        Regex("^man (sieht|erkenn[t])\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^das (foto|bild) zeigt\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^the (photo|image) shows\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^(on|in) the (photo|image)\\b[:,\\s]*(i can see|there (is|are|appears?)|visible)?[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^(visible|recognizable) (on|in) the (photo|image)\\b[:,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^i (can )?(see|recognize)\\b[:,\\s]*", RegexOption.IGNORE_CASE)
    )

    /** Vorspann-Floskeln vor dem Essen-Text entfernen; Ergebnis kapitalisieren, aber nie leeren. */
    private fun stripErkanntIntro(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in ERKANNT_INTRO_PATTERNS) {
                val stripped = p.replaceFirst(t, "")
                if (stripped.isNotBlank() && stripped != t) {
                    t = stripped.trim()
                    changed = true
                }
            }
        }
        if (t.isBlank()) return raw.trim()
        return t.replaceFirstChar { it.uppercase() }
    }

    private val RESULT_ARRAY_KEYS = listOf("bewertung", "bewertungen", "results", "ampeln", "ratings", "ziele", "goals")

    private fun findDirectResultArray(obj: JsonObject): JsonArray? {
        for (name in RESULT_ARRAY_KEYS) {
            val arr = obj[name] as? JsonArray
            if (arr != null && arr.isNotEmpty() && arr[0] is JsonObject) {
                return arr
            }
        }
        return null
    }

    /** Findet das Ergebnis-Array, auch bei anderer Verschachtelung/Feldnamen. */
    private fun findResultArray(node: JsonElement?, depth: Int = 0): JsonArray {
        if (node == null || depth > 4) return JsonArray(emptyList())
        if (node is JsonArray && node.isNotEmpty() && node[0] is JsonObject) {
            return node
        }
        if (node is JsonObject) {
            findDirectResultArray(node)?.let { return it }
            for (child in node.values) {
                val found = findResultArray(child, depth + 1)
                if (found.isNotEmpty()) return found
            }
        }
        return JsonArray(emptyList())
    }

    /** Feld per Name holen, case-insensitive, auch mit Unter-/Bindestrichen. */
    private fun pick(item: JsonObject, keys: List<String>): String {
        for ((k, v) in item) {
            val norm = k.lowercase().replace("_", "").replace("-", "")
            if (keys.contains(norm)) {
                val s = v.jsonPrimitive.contentOrNull ?: v.toString()
                if (s.isNotBlank()) return s
            }
        }
        return ""
    }

    private fun pickString(item: JsonObject, keys: List<String>): String {
        for (k in keys) {
            val v = item[k]?.jsonPrimitive?.contentOrNull
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    /** Kleinschreibung, Umlaute aufgeloest, nur Buchstaben/Ziffern. */
    private fun normalizeText(s: String): String = s.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .filter { it.isLetterOrDigit() }

    private fun findItemForGoal(arr: JsonArray, goal: String, goalCount: Int, goalIndex: Int): JsonObject? {
        val items = arr.mapNotNull { it as? JsonObject }
        val nGoal = normalizeText(goal)
        var best: JsonObject? = null
        var bestScore = -1
        for (item in items) {
            val z = normalizeText(pick(item, ZIEL_KEYS))
            if (z.isEmpty()) continue
            val score = when {
                z == nGoal -> 1000 + z.length
                z.contains(nGoal) || nGoal.contains(z) -> z.length
                else -> -1
            }
            if (score > bestScore) { bestScore = score; best = item }
        }
        if (best != null) return best
        // Weniger gut: gleiche Anzahl => Zuordnung per Reihenfolge
        if (items.size == goalCount && goalIndex in items.indices) return items[goalIndex]
        return null
    }
}
