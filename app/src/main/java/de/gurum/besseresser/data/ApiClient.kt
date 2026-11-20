package de.gurum.besseresser.data
import android.content.Context
import android.net.Uri
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
import okhttp3.Request
import de.gurum.besseresser.R

/** KI-Anweisungstexte, lokalisiert vom Aufrufer erzeugt (App-/Systemsprache). */
import de.gurum.besseresser.model.AppJson
import de.gurum.besseresser.model.calendarWeekOf
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight

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
    val rating: TrafficLight,
    val text: String
)

/** Spezifische Ausnahme für API-Fehler inkl. optionalem Debug-Report für die UI. */
class ApiException(
    message: String,
    val debugReport: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/** Schnittstelle für Netzwerkoperationen mit der OpenAI-kompatiblen KI-Schnittstelle.
 *  Bild- und Prompt-Aufbereitung liegt beim Aufrufer (kein Context nötig). */
interface BesseresserApiService {
    suspend fun analyze(
        imageDataUri: String,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation>

    suspend fun analyzeText(
        prompt: String,
        settings: Settings
    ): Result<Evaluation>

    suspend fun dayFazit(
        settings: Settings,
        goals: List<String>,
        promptContext: String?,
        mealDescriptions: List<String>,
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
class ApiClient(
    private val dispatchers: AppDispatchers = AppDispatchers()
) : BesseresserApiService {
    companion object {
        private const val CHAT_COMPLETIONS_PATH = "/chat/completions"

        /**
         * Führt eine KI-Operation aus und wiederholt sie bei jeglichem Fehler (Netzwerk, HTTP-Fehler,
         * leere Antwort, Token-Limit, JSON-Parsing) direkt EINMAL automatisch (`isRetry = true`).
         * Erst wenn auch der zweite Versuch scheitert, wird der Fehler an die UI gemeldet.
         */
        internal inline fun <T> runWithOneRetry(block: (isRetry: Boolean) -> T): Result<T> {
            val firstAttempt = runCatching { block(false) }
            if (firstAttempt.isSuccess) return firstAttempt
            return runCatching { block(true) }
        }

        /** Ziele-Freitext lokal an Satzzeichen teilen (Rückfall ohne KI). */
        fun splitGoalsLocally(rawGoals: String): List<String> =
            rawGoals.split(Regex("[.,;:!]\\s*"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 2 }

        internal fun filterVisionModels(entries: List<Pair<String, Boolean>>): ModelsResult =
            LlmResponseParser.filterVisionModels(entries)

        internal fun parseModelsArray(body: String): JsonArray =
            LlmResponseParser.parseModelsArray(body)

        internal fun parseModelEntries(arr: JsonArray): List<Pair<String, Boolean>> =
            LlmResponseParser.parseModelEntries(arr)

        internal fun parseEvaluation(content: String, goals: List<String>): Evaluation =
            LlmResponseParser.parseEvaluation(content, goals)

        internal fun stripRecognizedMealIntro(raw: String): String =
            LlmResponseParser.stripRecognizedMealIntro(raw)
    }

    // ---------------------------------------------------------------- Foto bewerten

    override suspend fun analyze(
        imageDataUri: String,
        settings: Settings,
        promptBundle: PromptBundle
    ): Result<Evaluation> = withContext(dispatchers.io) {
        val prompt = PromptBuilder.createAnalyzePrompt(settings.goals, promptBundle)
        val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH

        runWithOneRetry { isRetry ->
            val text = OpenAiChatClient.postChat(
                url,
                settings.apiKey,
                buildAnalyzeBody(settings, prompt, imageDataUri, highBudgetRetry = isRetry)
            )
            val resp = AppJson.parseToJsonElement(text).jsonObject
            val rawContent = OpenAiChatClient.extractContent(resp)

            if (rawContent.isNullOrBlank()) {
                if (OpenAiChatClient.isLengthTruncatedWithoutContent(resp)) {
                    OpenAiChatClient.markModelNeedsBoostedBudget(settings.model)
                }
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
        mealDescriptions: List<String>,
        prompts: FazitPromptBundle
    ): Result<DayFazitResult> = withContext(dispatchers.io) {
        val prompt = PromptBuilder.createDayFazitPrompt(goals, promptContext, mealDescriptions, prompts)
        val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        }

        runWithOneRetry { isRetry ->
            val respText = OpenAiChatClient.postChat(
                url,
                settings.apiKey,
                OpenAiChatClient.chatBody(
                    model = settings.model,
                    baseUrl = settings.baseUrl,
                    temperature = 0.3,
                    maxTokens = 3000,
                    messages = messages,
                    highBudgetRetry = isRetry
                )
            )
            val respObj = AppJson.parseToJsonElement(respText).jsonObject
            val rawContent = OpenAiChatClient.extractContent(respObj)

            if (rawContent.isNullOrBlank()) {
                if (OpenAiChatClient.isLengthTruncatedWithoutContent(respObj)) {
                    OpenAiChatClient.markModelNeedsBoostedBudget(settings.model)
                }
                throw buildDayFazitMissingContentException(respObj, respText)
            }

            val obj = OpenAiChatClient.tolerantJson(rawContent)
            val rating = TrafficLight.fromString(LlmResponseParser.pickString(obj, listOf("ampel", "rating", "status", "farbe")))
            val fazitText = LlmResponseParser.pickString(obj, listOf("text", "fazit", "summary"))
            check(fazitText.isNotBlank()) { "KI-Antwort ohne Fazit-Text (Roh: ${rawContent.take(200)})" }
            DayFazitResult(rating, fazitText.trim())
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
        prompt: String,
        settings: Settings
    ): Result<Evaluation> = withContext(dispatchers.io) {
        val url = settings.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        }

        runWithOneRetry { isRetry ->
            val text = OpenAiChatClient.postChat(
                url,
                settings.apiKey,
                OpenAiChatClient.chatBody(
                    model = settings.model,
                    baseUrl = settings.baseUrl,
                    temperature = 0.3,
                    maxTokens = 4096,
                    messages = messages,
                    highBudgetRetry = isRetry
                )
            )
            val resp = AppJson.parseToJsonElement(text).jsonObject
            val rawContent = OpenAiChatClient.extractContent(resp)

            if (rawContent.isNullOrBlank()) {
                if (OpenAiChatClient.isLengthTruncatedWithoutContent(resp)) {
                    OpenAiChatClient.markModelNeedsBoostedBudget(settings.model)
                }
                throw IllegalStateException("Die KI hat keinen Text geliefert. Roh-Antwort: ${text.take(300)}")
            }

            parseEvaluation(rawContent, settings.goals)
        }
    }

    // ---------------------------------------------------------------- Ziele aufteilen (KI-Assistenz)

    override suspend fun splitGoals(
        request: SplitGoalsRequest
    ): Result<List<String>> = withContext(dispatchers.io) {
        val requestUrl = request.baseUrl.trim().trimEnd('/') + CHAT_COMPLETIONS_PATH
        val prompt = PromptBuilder.createSplitGoalsPrompt(request)
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        }

        val attemptResult = runWithOneRetry { isRetry ->
            val req = OpenAiChatClient.chatBody(
                model = request.model,
                baseUrl = request.baseUrl,
                temperature = 0.1,
                maxTokens = 1600,
                messages = messages,
                highBudgetRetry = isRetry
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
            goals
        }

        attemptResult.fold(
            onSuccess = { Result.success(it) },
            onFailure = { e ->
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
        )
    }

    // ---------------------------------------------------------------- Modell-Liste

    /** Ergebnis von /models: vision-gefilterte Modell-IDs + alle Modell-IDs. */
    data class ModelsResult(val vision: List<String>, val all: List<String>)

    /** Fragt {baseUrl}/models ab und filtert auf vision-taugliche Modelle. */
    override suspend fun fetchModels(
        baseUrl: String,
        apiKey: String
    ): Result<ModelsResult> = withContext(dispatchers.io) {
        val requestUrl = baseUrl.trim().trimEnd('/') + "/models"
        var httpCode = -1
        var body = ""
        try {
            NetworkSecurity.requirePermittedEndpoint(requestUrl)
            val request = Request.Builder()
                .url(requestUrl)
                .get()
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .build()

            OpenAiChatClient.defaultOkHttpClient.newCall(request).execute().use { response ->
                httpCode = response.code
                body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP $httpCode")
                }
            }

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
}
