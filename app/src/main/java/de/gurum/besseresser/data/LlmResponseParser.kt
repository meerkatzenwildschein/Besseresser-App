package de.gurum.besseresser.data

import de.gurum.besseresser.model.AppJson
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.TrafficLight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser und Normalisierer für KI- und LLM-Antworten (JSON-Reparatur, Provider-Extraktion, Fallback-Matching).
 */
object LlmResponseParser {

    private val VISION_PATTERNS = listOf(
        "gpt-4o", "gpt-4.1", "gpt-4-vision", "chatgpt-4o", "o1", "o3", "o4",
        "claude-3", "claude-4", "gemini", "llava", "pixtral", "vision",
        "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen2.5vl", "-vl", "vl-",
        "glm-4v", "internvl", "deepseek-vl", "minicpm-v", "moondream", "smolvlm"
    )

    private val AMP_KEYS = listOf("ampel", "ampelfarbe", "farbe", "color", "status", "rating", "trafficlight")
    private val ZIEL_KEYS = listOf("ziel", "goal", "zielname", "name", "thema", "title")
    private val BEG_KEYS = listOf("begruendung", "reason", "erklaerung", "beschreibung", "kommentar", "text", "note")
    private val TIPP_KEYS = listOf("tipp", "tip", "hinweis", "optimierung", "suggestion", "advice", "verbesserung", "action", "empfehlung")
    private val RESULT_ARRAY_KEYS = listOf("bewertung", "bewertungen", "results", "ampeln", "ratings", "ziele", "goals")

    private val RECOGNIZED_MEAL_INTRO_PATTERNS = listOf(
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

    fun filterVisionModels(entries: List<Pair<String, Boolean>>): ApiClient.ModelsResult {
        val all = entries.map { it.first }.sorted()
        val flagged = entries.filter { it.second }.map { it.first }.sorted()
        if (flagged.isNotEmpty()) return ApiClient.ModelsResult(flagged, all)
        val guessed = all.filter { id -> VISION_PATTERNS.any { id.lowercase().contains(it) } }
        if (guessed.isNotEmpty()) return ApiClient.ModelsResult(guessed, all)
        return ApiClient.ModelsResult(emptyList(), all)
    }

    fun parseModelsArray(body: String): JsonArray {
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

    fun parseModelEntries(arr: JsonArray): List<Pair<String, Boolean>> =
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

    fun parseEvaluation(content: String, goals: List<String>): Evaluation {
        val obj = tolerantJson(content)
        val arr = findResultArray(obj)
        val list = mutableListOf<GoalResult>()
        for ((goalIndex, goal) in goals.withIndex()) {
            val m = findItemForGoal(arr, goal, goals.size, goalIndex)
            if (m != null) {
                val tipp = pick(m, TIPP_KEYS).takeIf { it.isNotBlank() }
                list.add(
                    GoalResult(
                        goal = goal,
                        rating = TrafficLight.fromString(pick(m, AMP_KEYS)),
                        reason = pick(m, BEG_KEYS),
                        tipp = tipp
                    )
                )
            } else {
                list.add(GoalResult(goal, TrafficLight.UNKNOWN, "Keine Bewertung erhalten."))
            }
        }
        val summary = pickString(obj, listOf("zusatz", "summary"))
        val recognizedMeal = stripRecognizedMealIntro(
            pickString(obj, listOf("erkannt", "recognizedMeal", "detected", "food", "meal"))
        )
        return Evaluation(list, summary, recognizedMeal)
    }

    fun stripRecognizedMealIntro(raw: String): String {
        var t = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in RECOGNIZED_MEAL_INTRO_PATTERNS) {
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

    private fun findDirectResultArray(obj: JsonObject): JsonArray? {
        for (name in RESULT_ARRAY_KEYS) {
            val arr = obj[name] as? JsonArray
            if (arr != null && arr.isNotEmpty() && arr[0] is JsonObject) {
                return arr
            }
        }
        return null
    }

    fun findResultArray(node: JsonElement?, depth: Int = 0): JsonArray {
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

    fun pick(item: JsonObject, keys: List<String>): String {
        for ((k, v) in item) {
            val norm = k.lowercase().replace("_", "").replace("-", "")
            if (keys.contains(norm)) {
                val s = v.jsonPrimitive.contentOrNull ?: v.toString()
                if (s.isNotBlank()) return s
            }
        }
        return ""
    }

    fun pickString(item: JsonObject, keys: List<String>): String {
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
