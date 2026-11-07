package de.gurum.besseresser

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URI

/** KI-Anweisungstexte, lokalisiert vom Aufrufer erzeugt (App-/Systemsprache). */
data class PromptBundle(
    val intro: String,
    val formatIntro: String,
    val ampelMeaning: String,
    val exactlyOne: String,
    val responseLanguage: String,
    val recognized: String = "",
    val honesty: String = "",
    val noteHeader: String = ""
)

/** KI-Anweisungstexte für Tages- und Wochenfazits. */
data class FazitPromptBundle(
    val promptIntro: String,
    val promptRules: String,
    val promptLanguage: String,
    val promptHonesty: String = ""
) {
    companion object {
        /** Lokalisierte Prompt-Bausteine für ein Tagesfazit. */
        fun forDay(ctx: Context): FazitPromptBundle = FazitPromptBundle(
            promptIntro = ctx.getString(R.string.prompt_dayfazit_intro),
            promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
            promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
            promptHonesty = ctx.getString(R.string.prompt_honesty)
        )

        /** Lokalisierte Prompt-Bausteine für ein Wochenfazit (inkl. Kalenderwoche). */
        fun forWeek(ctx: Context, calendarWeek: Int = calendarWeekOf()): FazitPromptBundle = FazitPromptBundle(
            promptIntro = ctx.getString(R.string.prompt_weekfazit_intro, calendarWeek),
            promptRules = ctx.getString(R.string.prompt_dayfazit_rules),
            promptLanguage = ctx.getString(R.string.prompt_dayfazit_language),
            promptHonesty = ctx.getString(R.string.prompt_honesty)
        )
    }
}

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
) : Exception(message, cause)

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

    // ---------------------------------------------------------------- Foto bewerten

    override suspend fun analyze(
        ctx: Context,
        photoUri: Uri,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation> = withContext(Dispatchers.IO) {
        runCatching {
            val dataUri = ImagePreprocessor.prepareImageDataUri(ctx, photoUri)
            val prompt = PromptBuilder.createAnalyzePrompt(settings.goals, promptBundle)
            val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH

            var text = OpenAiChatClient.postChat(
                url,
                settings.apiKey,
                buildAnalyzeBody(settings, prompt, dataUri, highBudgetRetry = false)
            )
            var resp = AppJson.parseToJsonElement(text).jsonObject
            var rawContent = OpenAiChatClient.extractContent(resp)

            if (rawContent.isNullOrBlank() && OpenAiChatClient.isLengthTruncatedWithoutContent(resp)) {
                OpenAiChatClient.markModelNeedsBoostedBudget(settings.model)
                text = OpenAiChatClient.postChat(
                    url,
                    settings.apiKey,
                    buildAnalyzeBody(settings, prompt, dataUri, highBudgetRetry = true)
                )
                resp = AppJson.parseToJsonElement(text).jsonObject
                rawContent = OpenAiChatClient.extractContent(resp)
            }

            if (rawContent.isNullOrBlank()) {
                throw buildAnalyzeMissingContentException(resp, text)
            }

            parseEvaluation(rawContent, settings.goals)
        }
    }

    private fun buildAnalyzeBody(
        settings: Settings,
        prompt: String,
        dataUri: String,
        highBudgetRetry: Boolean = false
    ): JsonObject =
        OpenAiChatClient.chatBody(
            model = settings.model,
            baseUrl = settings.baseUrl,
            temperature = 0.3,
            maxTokens = 4096,
            highBudgetRetry = highBudgetRetry,
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

    // ---------------------------------------------------------------- Tagesfazit

    override suspend fun dayFazit(
        settings: Settings,
        goals: List<String>,
        promptContext: String?,
        mealLines: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazitResult> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = PromptBuilder.createDayFazitPrompt(goals, promptContext, mealLines, prompts)
            val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
            val messages = buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            var respText = OpenAiChatClient.postChat(
                url,
                settings.apiKey,
                OpenAiChatClient.chatBody(
                    model = settings.model,
                    baseUrl = settings.baseUrl,
                    temperature = 0.3,
                    maxTokens = 3000,
                    messages = messages,
                    highBudgetRetry = false
                )
            )
            var respObj = AppJson.parseToJsonElement(respText).jsonObject
            var rawContent = OpenAiChatClient.extractContent(respObj)

            if (rawContent.isNullOrBlank() && OpenAiChatClient.isLengthTruncatedWithoutContent(respObj)) {
                OpenAiChatClient.markModelNeedsBoostedBudget(settings.model)
                respText = OpenAiChatClient.postChat(
                    url,
                    settings.apiKey,
                    OpenAiChatClient.chatBody(
                        model = settings.model,
                        baseUrl = settings.baseUrl,
                        temperature = 0.3,
                        maxTokens = 3000,
                        messages = messages,
                        highBudgetRetry = true
                    )
                )
                respObj = AppJson.parseToJsonElement(respText).jsonObject
                rawContent = OpenAiChatClient.extractContent(respObj)
            }

            if (rawContent.isNullOrBlank()) {
                throw buildDayFazitMissingContentException(respObj, respText)
            }

            val obj = OpenAiChatClient.tolerantJson(rawContent)
            val ampel = TrafficLight.fromString(pickString(obj, listOf("ampel", "rating", "status", "farbe")))
            val fazitText = pickString(obj, listOf("text", "fazit", "summary"))
            check(fazitText.isNotBlank()) { "KI-Antwort ohne Fazit-Text (Roh: ${rawContent.take(200)})" }
            DayFazitResult(ampel, fazitText.trim())
        }
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

    // ---------------------------------------------------------------- Text-only Analyse

    override suspend fun analyzeText(
        ctx: Context,
        settings: Settings,
        userDescription: String
    ): Result<Evaluation> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = PromptBuilder.createAnalyzeTextPrompt(ctx, settings.goals, userDescription)
            val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
            val messages = buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            var text = OpenAiChatClient.postChat(
                url,
                settings.apiKey,
                OpenAiChatClient.chatBody(
                    model = settings.model,
                    baseUrl = settings.baseUrl,
                    temperature = 0.3,
                    maxTokens = 4096,
                    messages = messages,
                    highBudgetRetry = false
                )
            )
            var resp = AppJson.parseToJsonElement(text).jsonObject
            var rawContent = OpenAiChatClient.extractContent(resp)

            if (rawContent.isNullOrBlank() && OpenAiChatClient.isLengthTruncatedWithoutContent(resp)) {
                OpenAiChatClient.markModelNeedsBoostedBudget(settings.model)
                text = OpenAiChatClient.postChat(
                    url,
                    settings.apiKey,
                    OpenAiChatClient.chatBody(
                        model = settings.model,
                        baseUrl = settings.baseUrl,
                        temperature = 0.3,
                        maxTokens = 4096,
                        messages = messages,
                        highBudgetRetry = true
                    )
                )
                resp = AppJson.parseToJsonElement(text).jsonObject
                rawContent = OpenAiChatClient.extractContent(resp)
            }

            check(!rawContent.isNullOrBlank()) { "Die KI hat keinen Text geliefert. Roh-Antwort: ${text.take(300)}" }

            parseEvaluation(rawContent, settings.goals)
        }
    }

    // ---------------------------------------------------------------- Ziele aufteilen (KI-Assistenz)

    override suspend fun splitGoals(
        request: SplitGoalsRequest
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val requestUrl = request.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
        try {
            val prompt = PromptBuilder.createSplitGoalsPrompt(request)
            val req = OpenAiChatClient.chatBody(
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
            val respBody = OpenAiChatClient.postChat(requestUrl, request.apiKey, req)

            val content = OpenAiChatClient.extractContent(AppJson.parseToJsonElement(respBody).jsonObject)
                ?: throw IllegalStateException("Kein Antwortinhalt")
            val obj = OpenAiChatClient.tolerantJson(content)
            val rawArr = obj["ziele"]?.takeIf { it is JsonArray }?.jsonArray
                ?: obj["goals"]?.takeIf { it is JsonArray }?.jsonArray
                ?: obj["ziel_liste"]?.takeIf { it is JsonArray }?.jsonArray
                ?: run {
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

    /** Ziele-Freitext lokal an Satzzeichen teilen (Rückfall ohne KI). */
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

    /** Fragt {baseUrl}/models ab und filtert auf vision-taugliche Modelle. */
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

    internal fun parseModelsArray(body: String): JsonArray {
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

    internal fun parseModelEntries(arr: JsonArray): List<Pair<String, Boolean>> =
        arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val modalities = (obj["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray
            val supportsImage = modalities != null && modalities.any {
                it.jsonPrimitive.contentOrNull?.equals("image", ignoreCase = true) == true
            }
            id to supportsImage
        }

    internal fun filterVisionModels(entries: List<Pair<String, Boolean>>): ModelsResult {
        val all = entries.map { it.first }.sorted()
        val flagged = entries.filter { it.second }.map { it.first }.sorted()
        if (flagged.isNotEmpty()) return ModelsResult(flagged, all)
        val guessed = all.filter { id -> VISION_PATTERNS.any { id.lowercase().contains(it) } }
        if (guessed.isNotEmpty()) return ModelsResult(guessed, all)
        return ModelsResult(emptyList(), all)
    }

    // ---------------------------------------------------------------- Evaluation Parsing-Helfer

    internal fun parseEvaluation(content: String, goals: List<String>): Evaluation {
        val obj = OpenAiChatClient.tolerantJson(content)
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

    internal fun stripErkanntIntro(raw: String): String {
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
        if (items.size == goalCount && goalIndex in items.indices) return items[goalIndex]
        return null
    }
}
