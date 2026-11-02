package de.gurum.besseresser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

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
    val recognized: String = ""
)

/** Kommunikation mit der OpenAI-kompatiblen API: Foto-Bewertung, Modell-Liste, Ziele-Aufteilung. */
object ApiClient {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- gemeinsame Call-Bausteine

    /** Reiner Post an /chat/completions; wirft ChatHttpException (mit Code + Roh-Body) bei Fehler,
     *  liefert sonst den Antwort-Body als String. */
    private fun postChat(url: String, apiKey: String, body: JSONObject): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw ChatHttpException(code, text)
        return text
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
        messages: JSONArray
    ): JSONObject = JSONObject().apply {
        put("model", model.trim())
        put("temperature", temperature)
        // Budget großzügig: Reasoning-Modelle „denken“ vom selben Konto —
        // zu knapp → finish=length mit LEEREM content (klassischer stiller Fehler)
        put("max_tokens", maxTokens)
        if (baseUrl.contains("openrouter", true) || baseUrl.contains("deepseek", true) ||
            model.contains("deepseek", true)
        ) {
            put("reasoning", JSONObject().put("exclude", true))
        }
        put("messages", messages)
    }

    /** Toleranter JSON-Parser für KI-Antworten: Code-Fences strippen, {…}-Block suchen, closeJson-Reparatur. */
    private fun tolerantJson(content: String): JSONObject {
        val cleaned = content.replace("```json", "", true).replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalStateException("KI-Antwort ohne JSON")
        val jsonStr = cleaned.substring(start, end + 1)
        return try { JSONObject(jsonStr) } catch (e: Exception) { JSONObject(closeJson(jsonStr)) }
    }

    // ---------------------------------------------------------------- Foto bewerten

    fun analyze(
        ctx: Context,
        photoUri: Uri,
        settings: Settings,
        promptBundle: PromptBundle,
        onResult: (Evaluation) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                // Foto laden & verkleinern
                val resolver = ctx.contentResolver
                val bitmap = BitmapFactory.decodeStream(resolver.openInputStream(photoUri))
                    ?: throw IllegalStateException("Foto konnte nicht gelesen werden.")
                val scaled = scaleDown(bitmap, 1280)
                val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    .toByteArray()
                val dataUri = "data:image/jpeg;base64," +
                    Base64.encodeToString(jpeg, Base64.NO_WRAP)
                if (scaled !== bitmap) scaled.recycle()

                val prompt = buildString {
                    appendLine(promptBundle.intro)
                    settings.goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
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
                    appendLine(promptBundle.responseLanguage)
                }

                val body = chatBody(
                    model = settings.model,
                    baseUrl = settings.baseUrl,
                    temperature = 0.3,
                    maxTokens = 4096,
                    messages = JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "text").put("text", prompt))
                                    .put(
                                        JSONObject().put("type", "image_url").put(
                                            "image_url",
                                            JSONObject().put("url", dataUri)
                                        )
                                    )
                            )
                        }
                    )
                )

                val url = settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
                val text = postChat(url, settings.apiKey, body)

                // Antwort parsen
                val resp = JSONObject(text)
                val rawContent = extractContent(resp)

                if (rawContent.isNullOrBlank()) {
                    val modelName = try { resp.optString("model").takeIf { it.isNotBlank() } ?: "unbekannt" } catch (e: Exception) { "unbekannt" }
                    val finish = try {
                        resp.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")
                    } catch (e: Exception) { null }
                    var hint = when {
                        finish == "length" &&
                            (resp.optString("model").contains("deepseek", true) ||
                                resp.optString("model").contains("reason", true)) ->
                            "Das Reasoning des Modells hat das Token-Budget verbraucht, bevor ein Antworttext kam. Erneut versuchen – die App setzt für den nächsten Versuch ein deutlich höheres Budget."
                        finish == "length" ->
                            "Die Antwort wurde durch das Token-Limit abgeschnitten. Erneut versuchen oder ein weniger aufwendiges Modell wählen."
                        else -> "Evtl. ungewöhnliches Antwortformat – bitte ein anderes Modell versuchen."
                    }
                    throw IllegalStateException(
                        "Die KI hat keinen Text geliefert (Modell: $modelName). $hint " +
                        "Roh-Antwort-Auszug: ${text.take(400).ifBlank { "(leer)" }}"
                    )
                }

                val results = parseEvaluation(rawContent, settings.goals)
                main.post { onResult(results) }
            } catch (e: Exception) {
                main.post { onError("${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Text-only Tagesfazit: Ziele + optional Nutzerprofil-Kontext + Mahlzeiten-Zeilen
     *  (Zeit · Erkanntes mit Menge · Anmerkung) → JSON {ampel, text}.
     *  Kein Foto, kein Bilder-Modell nötig; benutzt denselben /chat/completions-Endpunkt. */
    fun dayFazit(
        settings: Settings,
        goals: List<String>,
        promptContext: String?,          // z. B. Nutzerprofil-Zeile (optional)
        mealLines: List<String>,
        promptIntro: String,
        promptRules: String,
        promptLanguage: String,
        onOk: (ampel: String, text: String) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val prompt = buildString {
                    appendLine(promptIntro)
                    goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
                    promptContext?.takeIf { it.isNotBlank() }?.let {
                        appendLine("")
                        appendLine(it)
                    }
                    appendLine("")
                    mealLines.forEach { appendLine(it) }
                    appendLine("")
                    appendLine(promptRules)
                    appendLine("")
                    // JSON-Schluessel (ampel/text) fest verdrahtet, Sprache via promptLanguage
                    appendLine("""{"ampel":"gruen|gelb|rot","text":"<2-3 sentences: day summary in the app language, directly to the point>"}""")
                    appendLine(promptLanguage)
                }
                val body = chatBody(
                    model = settings.model,
                    baseUrl = settings.baseUrl,
                    temperature = 0.3,
                    maxTokens = 3000,
                    messages = JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt)
                    )
                )

                val url = settings.baseUrl.trim().trimEnd('/') + "/chat/completions"
                val respText = postChat(url, settings.apiKey, body)

                val respObj = JSONObject(respText)
                val rawContent = extractContent(respObj)
                if (rawContent.isNullOrBlank()) {
                    // Diagnose statt Rate: Modell + finish_reason + Rohauszug in den Fehler,
                    // damit der Toast sagt, WARUM leer kam (length / content_filter / ...)
                    val finish = try {
                        respObj.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason")
                    } catch (e: Exception) { null }
                    val modelName = try { respObj.optString("model").takeIf { it.isNotBlank() } ?: "?" } catch (e: Exception) { "?" }
                    val hint = when (finish) {
                        "length" -> "Das Token-Budget wurde verbraucht, bevor ein Antworttext kam – Modell erneut versuchen."
                        "content_filter" -> "Der Anbieter hat die Antwort gefiltert (Inhalt blockiert). Versuch es erneut oder mit anderem Modell."
                        else -> "Antwortformat unbekannt für dieses Modell."
                    }
                    throw IllegalStateException(
                        "Die KI hat keinen Text geliefert (Modell: $modelName, finish: $finish). $hint Roh: ${respText.take(300)}"
                    )
                }

                // tolerantes JSON-Parsing (Code-Fences, closeJson)
                val obj = tolerantJson(rawContent)
                val ampel = normalizeAmpel(obj.optString("ampel", obj.optString("rating", "")))
                val fazitText = obj.optString("text", obj.optString("fazit", obj.optString("summary", "")))
                // Ampel "unbekannt" ist okay (grauer Chip) – nur ganz ohne Text ist ein echter Fehler.
                if (fazitText.isBlank()) throw IllegalStateException("KI-Antwort ohne Fazit-Text (Roh: ${rawContent.take(200)})")
                main.post { onOk(ampel, fazitText.trim()) }
            } catch (e: Exception) {
                main.post { onError("${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Ziele-Freitext von der KI in einzelne prägnante Ziele zerlegen lassen.
     *  onError bekommt einen mehrzeiligen Debug-Report inkl. Rohantwort. */
    fun splitGoals(
        baseUrl: String,
        apiKey: String,
        model: String,
        rawGoals: String,
        splitIntro: String,
        splitRules: String,
        onOk: (List<String>) -> Unit,
        onError: (debugReport: String) -> Unit
    ) {
        val requestUrl = baseUrl.trim().trimEnd('/') + "/chat/completions"
        executor.execute {
            try {
                val prompt = buildString {
                    appendLine(splitIntro)
                    appendLine(splitRules)
                    appendLine("Reply ONLY with a JSON object (no text before or after):")
                    appendLine("""{"ziele":["Goal 1","Goal 2"]}""")
                    appendLine()
                    appendLine(rawGoals)
                }
                val req = chatBody(
                    model = model,
                    baseUrl = baseUrl,
                    temperature = 0.1,
                    maxTokens = 1600,
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                )
                val respBody = postChat(requestUrl, apiKey, req)

                val content = JSONObject(respBody).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                val obj = tolerantJson(content)
                val rawArr = obj.optJSONArray("ziele")
                    ?: obj.optJSONArray("goals")
                    ?: obj.optJSONArray("ziel_liste")
                    ?: run {
                        // Fallback: irgendein String-Array im Objekt
                        obj.keys().asSequence().mapNotNull { obj.optJSONArray(it) }.firstOrNull {
                            arr -> arr.length() > 0 && arr.optString(0).isNotBlank()
                        } ?: JSONArray()
                    }
                val goals = (0 until rawArr.length())
                    .mapNotNull { rawArr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
                if (goals.isEmpty()) throw IllegalStateException("KI lieferte keine Ziele")
                main.post { onOk(goals) }
            } catch (e: Exception) {
                val debug = buildString {
                    appendLine("Anfrage:  POST $requestUrl (Modell: $model)")
                    appendLine("Status:   HTTP ${(e as? ChatHttpException)?.code ?: "?"}")
                    appendLine("Fehler:   ${e.message ?: e.javaClass.simpleName}")
                    appendLine()
                    appendLine("Rohantwort (erste 800 Zeichen):")
                    appendLine((e as? ChatHttpException)?.bodyText.orEmpty().take(800).ifBlank { "(leer bzw. Netzfehler)" })
                }
                main.post { onError(debug) }
            }
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
     *  onError bekommt einen mehrzeiligen Debug-Report inkl. Rohantwort. */
    fun fetchModels(
        baseUrl: String,
        apiKey: String,
        onOk: (ModelsResult) -> Unit,
        onError: (debugReport: String) -> Unit
    ) {
        val requestUrl = baseUrl.trim().trimEnd('/') + "/models"
        var httpCode = -1
        var body = ""
        executor.execute {
            try {
                val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                }
                httpCode = conn.responseCode
                val stream = if (httpCode in 200..299) conn.inputStream else conn.errorStream
                body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (httpCode !in 200..299) throw IllegalStateException("HTTP $httpCode")

                // Antwort robust auswerten: {"data":[…]}, {"models":[…]} oder direkt [ … ]
                val trimmed = body.trim()
                val arr: JSONArray = when {
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    trimmed.startsWith("{") -> {
                        val root = JSONObject(trimmed)
                        root.optJSONArray("data")
                            ?: root.optJSONArray("models")
                            ?: root.optJSONArray("model_list")
                            ?: JSONArray()
                    }
                    else -> JSONArray()
                }
                if (arr.length() == 0) {
                    throw IllegalStateException("Antwort enthält keine Modell-Liste")
                }
                val entries = (0 until arr.length()).mapNotNull { index ->
                    val obj = arr.optJSONObject(index) ?: return@mapNotNull null
                    val id = obj.optString("id").takeIf { it.isNotBlank() }
                        ?: obj.optString("name").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    // Exakte Info, falls der Provider sie liefert (z. B. OpenRouter)
                    val modalities = obj.optJSONObject("architecture")?.optJSONArray("input_modalities")
                    val supportsImage = modalities != null && (0 until modalities.length()).any {
                        modalities.optString(it).equals("image", ignoreCase = true)
                    }
                    id to supportsImage
                }
                if (entries.isEmpty()) {
                    throw IllegalStateException("Modell-Liste enthielt ${arr.length()} Einträge, aber keine nutzbare Modell-IDs")
                }
                main.post { onOk(filterVisionModels(entries)) }
            } catch (e: Exception) {
                val debug = buildString {
                    appendLine("Anfrage:  GET $requestUrl")
                    appendLine("Status:   HTTP $httpCode")
                    appendLine("Fehler:   ${e.message ?: e.javaClass.simpleName}")
                    appendLine()
                    appendLine("Rohantwort (erste 800 Zeichen):")
                    appendLine(body.take(800).ifBlank { "(leer)" })
                }
                main.post { onError(debug) }
            }
        }
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
    private fun extractContent(resp: JSONObject): String? {
        return try {
            val choices = resp.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null

            // 1) Standard: choices[0].message.content (String)
            val msg = first.optJSONObject("message")
            val direct = msg?.opt("content")
            when (direct) {
                is String -> if (direct.isNotBlank()) direct else null
                is JSONArray -> {
                    // Manche Anbieter liefern ein Parts-Array: [{"type":"text","text":"..."}]
                    val parts = StringBuilder()
                    for (i in 0 until direct.length()) {
                        val p = direct.optJSONObject(i) ?: continue
                        val t = p.optString("text")
                        if (t.isNotBlank()) parts.append(t)
                    }
                    parts.toString().takeIf { it.isNotBlank() }
                }
                else -> null
            }
                ?: msg?.optString("reasoning_content")?.takeIf { it.isNotBlank() }
                ?: first.optString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
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
        // JSON aus der Antwort extrahieren (auch trotz Code-Fences)
        val cleaned = content
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalStateException("Die KI hat kein JSON geliefert: ${cleaned.take(300)}")
        }
        val json = cleaned.substring(start, end + 1)
        // Abgehackte Antworten reparieren: fehlende schließende Klammern/Anführungszeichen ergänzen
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            JSONObject(closeJson(json))
        }
        val arr = findResultArray(obj)
        val list = mutableListOf<GoalResult>()
        for ((goalIndex, goal) in goals.withIndex()) {
            val m = findItemForGoal(arr, goal, goals.size, goalIndex)
            if (m != null) {
                list.add(
                    GoalResult(
                        goal = goal,
                        ampel = normalizeAmpel(pick(m, AMP_KEYS)),
                        begruendung = pick(m, BEG_KEYS)
                    )
                )
            } else {
                list.add(GoalResult(goal, "unbekannt", "Keine Bewertung erhalten."))
            }
        }
        val summary = obj.optString("zusatz", obj.optString("summary", ""))
        val erkannt = stripErkanntIntro(
            obj.optString("erkannt", obj.optString("detected", obj.optString("food", obj.optString("meal", ""))))
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

    /** Findet das Ergebnis-Array, auch bei anderer Verschachtelung/Feldnamen. */
    private fun findResultArray(node: Any?, depth: Int = 0): JSONArray {
        if (node == null || depth > 4) return JSONArray()
        if (node is JSONObject) {
            for (name in listOf("bewertung", "bewertungen", "results", "ampeln", "ratings", "ziele", "goals")) {
                node.optJSONArray(name)?.takeIf { it.length() > 0 && it.optJSONObject(0) != null }?.let { return it }
            }
            node.keys().forEach { k ->
                val found = findResultArray(node.opt(k), depth + 1)
                if (found.length() > 0) return found
            }
        } else if (node is JSONArray) {
            if (node.length() > 0 && node.optJSONObject(0) != null) return node
        }
        return JSONArray()
    }

    /** Feld per Name holen, case-insensitive, auch mit Unter-/Bindestrichen. */
    private fun pick(item: JSONObject, keys: List<String>): String {
        for (k in item.keys()) {
            val norm = k.lowercase().replace("_", "").replace("-", "")
            if (keys.contains(norm)) {
                val v = item.opt(k)?.toString() ?: ""
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    /** Kleinschreibung, Umlaute aufgeloest, nur Buchstaben/Ziffern. */
    private fun normalizeText(s: String): String = s.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .filter { it.isLetterOrDigit() }

    private fun findItemForGoal(arr: JSONArray, goal: String, goalCount: Int, goalIndex: Int): JSONObject? {
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val nGoal = normalizeText(goal)
        var best: JSONObject? = null
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

    private fun normalizeAmpel(s: String): String {
        if (s.isBlank()) return "unbekannt"
        val has = { needle: String -> s.contains(needle, ignoreCase = true) }
        if (has("grün") || has("gruen") || s.contains("🟢") || s.contains("✅") || has("green")) return "gruen"
        if (has("gelb") || s.contains("🟡") || s.contains("🟠") || has("yellow") || has("orange") || has("amber")) return "gelb"
        if (has("rot") || s.contains("🔴") || has("red")) return "rot"
        val n = normalizeText(s)
        return when {
            n.contains("gruen") || n.contains("green") -> "gruen"
            n.contains("gelb") || n.contains("yellow") || n.contains("orange") || n.contains("amber") -> "gelb"
            n.contains("rot") && !n.contains("prot") -> "rot"
            n.contains("red") -> "rot"
            else -> "unbekannt"
        }
    }
}
