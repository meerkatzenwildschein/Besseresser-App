package de.gurum.besseresser.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP-Fehlerobjekt mit HTTP-Statuscode und Antwort-Body für strukturierte Fehler- und Debug-Reports.
 */
class ChatHttpException(val code: Int, val bodyText: String) :
    RuntimeException("HTTP $code: ${bodyText.take(300)}")

/**
 * Schnittstelle für OpenAI-kompatible Chat-Completions-Anfragen.
 */
interface ChatCompletionClient {
    val boostedBudgetModels: MutableSet<String>
    fun markModelNeedsBoostedBudget(model: String)
    fun hasBoostedBudget(model: String): Boolean
    fun clearBoostedBudgets()
    suspend fun postChat(
        url: String,
        apiKey: String,
        body: JsonObject,
        client: OkHttpClient? = null
    ): String
    fun chatBody(
        model: String,
        baseUrl: String,
        temperature: Double,
        maxTokens: Int,
        messages: JsonArray,
        highBudgetRetry: Boolean = false
    ): JsonObject
    fun isLengthTruncatedWithoutContent(resp: JsonObject): Boolean
    fun tolerantJson(content: String): JsonObject
    fun extractContent(resp: JsonObject): String?
    fun parseMessageContent(contentElement: JsonElement?): String?
    fun closeJson(text: String): String
}

/**
 * Asynchrone Coroutine-Extension für OkHttp-Anfragen.
 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) {
                continuation.resumeWithException(e)
            }
        }
    })
}

/**
 * Standard-Implementierung des ChatCompletionClient mit asynchronem OkHttp-Netzwerkzugriff.
 */
class DefaultChatCompletionClient(
    private val defaultClient: OkHttpClient = OpenAiChatClient.defaultOkHttpClient,
    private val dispatchers: AppDispatchers = AppDispatchers()
) : ChatCompletionClient {

    override val boostedBudgetModels: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    override fun clearBoostedBudgets() {
        boostedBudgetModels.clear()
    }

    override fun markModelNeedsBoostedBudget(model: String) {
        val key = model.trim().lowercase()
        if (key.isNotBlank()) {
            boostedBudgetModels.add(key)
        }
    }

    override fun hasBoostedBudget(model: String): Boolean =
        model.trim().lowercase() in boostedBudgetModels

    override suspend fun postChat(
        url: String,
        apiKey: String,
        body: JsonObject,
        client: OkHttpClient?
    ): String = withContext(dispatchers.io) {
        NetworkSecurity.requirePermittedEndpoint(url)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .build()

        val effectiveClient = client ?: defaultClient
        val response = effectiveClient.newCall(request).awaitResponse()
        response.use { resp ->
            val code = resp.code
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ChatHttpException(code, text)
            }
            text
        }
    }

    override fun chatBody(
        model: String,
        baseUrl: String,
        temperature: Double,
        maxTokens: Int,
        messages: JsonArray,
        highBudgetRetry: Boolean
    ): JsonObject = buildJsonObject {
        val trimmedModel = model.trim()
        val boosted = highBudgetRetry || hasBoostedBudget(trimmedModel)
        val effectiveMaxTokens = if (boosted) maxOf(maxTokens, OpenAiChatClient.BOOSTED_MAX_TOKENS) else maxTokens

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

    override fun isLengthTruncatedWithoutContent(resp: JsonObject): Boolean {
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

    override fun tolerantJson(content: String): JsonObject = LlmResponseParser.tolerantJson(content)

    override fun extractContent(resp: JsonObject): String? = LlmResponseParser.extractContent(resp)

    override fun parseMessageContent(contentElement: JsonElement?): String? = LlmResponseParser.parseMessageContent(contentElement)

    override fun closeJson(text: String): String = LlmResponseParser.closeJson(text)
}

/**
 * Singleton- und Delegate-Objekt für OpenAI-kompatible /chat/completions Endpunkte.
 */
object OpenAiChatClient : ChatCompletionClient by DefaultChatCompletionClient() {

    const val BOOSTED_MAX_TOKENS = 16384

    val defaultOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
