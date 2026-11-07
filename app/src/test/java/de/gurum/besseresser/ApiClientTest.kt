package de.gurum.besseresser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {

    @Test
    fun testSplitGoalsLocally() {
        val raw = "Kein Zucker, 2l Wasser trinken; Mehr Gemüse essen. Intervallfasten!"
        val split = ApiClient.splitGoalsLocally(raw)
        assertEquals(4, split.size)
        assertEquals("Kein Zucker", split[0])
        assertEquals("2l Wasser trinken", split[1])
        assertEquals("Mehr Gemüse essen", split[2])
        assertEquals("Intervallfasten", split[3])
    }

    @Test
    fun testSplitGoalsLocallyEmptyOrShort() {
        val raw = " , ; ! ab "
        val split = ApiClient.splitGoalsLocally(raw)
        assertTrue(split.isEmpty())
    }

    @Test
    fun testDayFazitResult() {
        val res = DayFazitResult(TrafficLight.GREEN, "Gute Mahlzeiten heute!")
        assertEquals(TrafficLight.GREEN, res.ampel)
        assertEquals("Gute Mahlzeiten heute!", res.text)
    }

    @Test
    fun testApiExceptionProperties() {
        val cause = IllegalArgumentException("Root cause")
        val exception = ApiException(
            message = "HTTP 500",
            debugReport = "Anfrage: POST /chat/completions\nStatus: HTTP 500",
            cause = cause
        )
        assertEquals("HTTP 500", exception.message)
        assertEquals("Anfrage: POST /chat/completions\nStatus: HTTP 500", exception.debugReport)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun testPromptBuilderCreateAnalyzePrompt() {
        val goals = listOf("Kein Zucker", "2l Wasser")
        val bundle = PromptBundle(
            intro = "Bewerte Mahlzeit",
            formatIntro = "Format:",
            ampelMeaning = "gruen=gut",
            exactlyOne = "Genau eine Ampel",
            responseLanguage = "Sprache: Deutsch",
            recognized = "Erkannt:",
            honesty = "Ehrlich",
            noteHeader = "Notiz:"
        )
        val prompt = PromptBuilder.createAnalyzePrompt(goals, bundle)
        assertTrue(prompt.contains("Bewerte Mahlzeit"))
        assertTrue(prompt.contains("1. Kein Zucker"))
        assertTrue(prompt.contains("2. 2l Wasser"))
        assertTrue(prompt.contains("Notiz:"))
        assertTrue(prompt.contains("Sprache: Deutsch"))
    }

    @Test
    fun testPromptBuilderCreateDayFazitPrompt() {
        val goals = listOf("Blutdruck senken", "Weniger Zucker")
        val mealLines = listOf("08:00 · Haferflocken", "12:30 · Gemüse-Curry")
        val bundle = FazitPromptBundle(
            promptIntro = "Bewerte den Tag:",
            promptRules = "Kleine Snacks fair gewichten.",
            promptLanguage = "Antworte auf Deutsch.",
            promptHonesty = "Nichts erfinden."
        )
        val promptWithCtx = PromptBuilder.createDayFazitPrompt(
            goals = goals,
            promptContext = "Nutzerprofil: 35 Jahre · 75 kg",
            mealLines = mealLines,
            prompts = bundle
        )
        assertTrue(promptWithCtx.contains("1. Blutdruck senken"))
        assertTrue(promptWithCtx.contains("2. Weniger Zucker"))
        assertTrue(promptWithCtx.contains("Nutzerprofil: 35 Jahre · 75 kg"))
        assertTrue(promptWithCtx.contains("08:00 · Haferflocken"))
        assertTrue(promptWithCtx.contains("12:30 · Gemüse-Curry"))
        assertTrue(promptWithCtx.contains("Kleine Snacks fair gewichten."))
        assertTrue(promptWithCtx.contains("Nichts erfinden."))
        assertTrue(promptWithCtx.contains("Antworte auf Deutsch."))

        val promptWithoutCtx = PromptBuilder.createDayFazitPrompt(
            goals = goals,
            promptContext = null,
            mealLines = mealLines,
            prompts = bundle
        )
        assertFalse(promptWithoutCtx.contains("Nutzerprofil"))
    }

    @Test
    fun testPromptBuilderCreateSplitGoalsPrompt() {
        val req = SplitGoalsRequest(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-123",
            model = "gpt-4o-mini",
            rawGoals = "Weniger Salz, mehr Proteine",
            splitIntro = "Teile Ziele auf:",
            splitRules = "Max 8 Wörter"
        )
        val prompt = PromptBuilder.createSplitGoalsPrompt(req)
        assertTrue(prompt.contains("Teile Ziele auf:"))
        assertTrue(prompt.contains("Max 8 Wörter"))
        assertTrue(prompt.contains("Weniger Salz, mehr Proteine"))
        assertTrue(prompt.contains("""{"ziele":["Goal 1","Goal 2"]}"""))
    }

    @Test
    fun testOpenAiChatClientCloseJson() {
        val incomplete = """{"ampel":"gruen","text":"Guter Tag"""
        val closed = OpenAiChatClient.closeJson(incomplete)
        assertEquals("""{"ampel":"gruen","text":"Guter Tag"}""", closed)

        // Nested arrays & objects + trailing comma
        val nestedIncomplete = """{"bewertung":[{"ziel":"Z1","ampel":"gelb"},"""
        val closedNested = OpenAiChatClient.closeJson(nestedIncomplete)
        assertEquals("""{"bewertung":[{"ziel":"Z1","ampel":"gelb"}]}""", closedNested)

        // Escaped quotes inside string
        val escapedIncomplete = """{"text":"Er sagte \"Hallo"""
        val closedEscaped = OpenAiChatClient.closeJson(escapedIncomplete)
        assertEquals("""{"text":"Er sagte \"Hallo"}""", closedEscaped)
    }

    @Test
    fun testOpenAiChatClientTolerantJson() {
        val markdownJson = "Hier ist die Antwort:\n```json\n{\"ampel\":\"rot\",\"text\":\"Zu viel Zucker\"}\n```\nDanke!"
        val obj = OpenAiChatClient.tolerantJson(markdownJson)
        assertEquals("rot", obj["ampel"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Zu viel Zucker", obj["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun testOpenAiChatClientExtractContentFormats() {
        // 1. Standard string content
        val standardResp = AppJson.parseToJsonElement(
            """{"choices":[{"message":{"content":"Hallo Welt"}}]}"""
        ).jsonObject
        assertEquals("Hallo Welt", OpenAiChatClient.extractContent(standardResp))

        // 2. Parts array content
        val partsResp = AppJson.parseToJsonElement(
            """{"choices":[{"message":{"content":[{"type":"text","text":"Teil 1 "},{"type":"text","text":"Teil 2"}]}}]}"""
        ).jsonObject
        assertEquals("Teil 1 Teil 2", OpenAiChatClient.extractContent(partsResp))

        // 3. Fallback to reasoning_content or OpenRouter's reasoning when content is empty/null
        val reasoningResp = AppJson.parseToJsonElement(
            """{"choices":[{"message":{"content":"","reasoning_content":"Gedanke"}}]}"""
        ).jsonObject
        assertEquals("Gedanke", OpenAiChatClient.extractContent(reasoningResp))

        val openRouterReasoningResp = AppJson.parseToJsonElement(
            """{"choices":[{"message":{"content":null,"reasoning":"{\"ampel\":\"gruen\"}"}}]}"""
        ).jsonObject
        assertEquals("""{"ampel":"gruen"}""", OpenAiChatClient.extractContent(openRouterReasoningResp))

        // 4. Fallback to choices[0].text
        val legacyTextResp = AppJson.parseToJsonElement(
            """{"choices":[{"text":"Direkter Text"}]}"""
        ).jsonObject
        assertEquals("Direkter Text", OpenAiChatClient.extractContent(legacyTextResp))

        // 5. Empty or missing choices
        val emptyResp = AppJson.parseToJsonElement("""{"choices":[]}""").jsonObject
        assertNull(OpenAiChatClient.extractContent(emptyResp))
    }

    @Test
    fun testOpenAiChatClientChatBodyReasoningAndBoostedBudget() {
        OpenAiChatClient.boostedBudgetModels.clear()
        val msgs = buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "Hi") })
        }

        // Standard OpenAI -> no reasoning block
        val openaiBody = OpenAiChatClient.chatBody("gpt-4o-mini", "https://api.openai.com/v1", 0.2, 4096, msgs)
        assertFalse(openaiBody.containsKey("reasoning"))
        assertEquals(4096, openaiBody["max_tokens"]?.jsonPrimitive?.intOrNull)

        // OpenRouter -> reasoning.effort = "low", reasoning.exclude = true
        val openRouterBody = OpenAiChatClient.chatBody("deepseek/deepseek-v4.1-flash", "https://openrouter.ai/api/v1", 0.2, 4096, msgs)
        assertTrue(openRouterBody.containsKey("reasoning"))
        assertEquals("low", openRouterBody["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull)
        assertEquals(true, openRouterBody["reasoning"]?.jsonObject?.get("exclude")?.jsonPrimitive?.booleanOrNull)

        // Detect length truncation without content (exact OpenRouter Together failure case)
        val truncatedResp = AppJson.parseToJsonElement(
            """{"id":"gen-1","model":"deepseek/deepseek-v4.1-flash","choices":[{"index":0,"finish_reason":"length","native_finish_reason":"length","message":{"role":"assistant","content":null}}]}"""
        ).jsonObject
        assertTrue(OpenAiChatClient.isLengthTruncatedWithoutContent(truncatedResp))

        // Once marked for boosted budget, subsequent calls use 16384 tokens automatically
        OpenAiChatClient.markModelNeedsBoostedBudget("deepseek/deepseek-v4.1-flash")
        assertTrue(OpenAiChatClient.hasBoostedBudget("deepseek/deepseek-v4.1-flash"))

        val boostedBody = OpenAiChatClient.chatBody("deepseek/deepseek-v4.1-flash", "https://openrouter.ai/api/v1", 0.2, 4096, msgs, highBudgetRetry = true)
        assertEquals(OpenAiChatClient.BOOSTED_MAX_TOKENS, boostedBody["max_tokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, boostedBody["reasoning"]?.jsonObject?.get("exclude")?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun testStripErkanntIntroPatterns() {
        assertEquals(
            "200 g Spaghetti Bolognese",
            ApiClient.stripErkanntIntro("Auf dem Foto ist zu sehen: 200 g Spaghetti Bolognese")
        )
        assertEquals(
            "Ein bunter Salat mit Hähnchen",
            ApiClient.stripErkanntIntro("Zu sehen sind ein bunter Salat mit Hähnchen")
        )
        assertEquals(
            "150 g Lachsfilet mit Reis",
            ApiClient.stripErkanntIntro("Das Bild zeigt 150 g Lachsfilet mit Reis")
        )
        assertEquals(
            "One bowl of oatmeal with berries",
            ApiClient.stripErkanntIntro("The photo shows: one bowl of oatmeal with berries")
        )
        assertEquals(
            "Two slices of wholegrain bread",
            ApiClient.stripErkanntIntro("In the image i can see two slices of wholegrain bread")
        )
    }

    @Test
    fun testParseEvaluationStandardAndAliases() {
        val goals = listOf("Blutdruck senken", "Gesünder ernähren", "Weniger Zucker")

        // Standard German schema
        val rawStandard = """
            {
              "bewertung": [
                {"ziel": "Blutdruck senken", "ampel": "gruen", "begruendung": "Wenig Salz"},
                {"ziel": "Gesuender ernaehren", "ampel": "gelb", "begruendung": "Viel Fett"},
                {"ziel": "Weniger Zucker", "ampel": "rot", "begruendung": "Süßes Dessert"}
              ],
              "zusatz": "Insgesamt solide.",
              "erkannt": "Auf dem Foto ist zu sehen: 250 g Gemüsepfanne"
            }
        """.trimIndent()

        val ev = ApiClient.parseEvaluation(rawStandard, goals)
        assertEquals(3, ev.goalResults.size)
        assertEquals(TrafficLight.GREEN, ev.goalResults[0].ampel)
        // Matched despite umlaut difference ("Gesünder ernähren" vs "Gesuender ernaehren")
        assertEquals(TrafficLight.YELLOW, ev.goalResults[1].ampel)
        assertEquals("Viel Fett", ev.goalResults[1].begruendung)
        assertEquals(TrafficLight.RED, ev.goalResults[2].ampel)
        assertEquals("Insgesamt solide.", ev.summary)
        assertEquals("250 g Gemüsepfanne", ev.erkannt)

        // English alias schema + positional fallback when goal names don't match
        val rawAliases = """
            {
              "results": [
                {"goal": "Goal #1", "rating": "green", "reason": "Low sodium"},
                {"goal": "Goal #2", "rating": "yellow", "reason": "Okay"},
                {"goal": "Goal #3", "rating": "red", "reason": "Sweet"}
              ],
              "summary": "Balanced meal",
              "detected": "The image shows 100 g salad"
            }
        """.trimIndent()

        val evAlias = ApiClient.parseEvaluation(rawAliases, goals)
        assertEquals(3, evAlias.goalResults.size)
        assertEquals(TrafficLight.GREEN, evAlias.goalResults[0].ampel)
        assertEquals("Low sodium", evAlias.goalResults[0].begruendung)
        assertEquals(TrafficLight.YELLOW, evAlias.goalResults[1].ampel)
        assertEquals(TrafficLight.RED, evAlias.goalResults[2].ampel)
        assertEquals("Balanced meal", evAlias.summary)
        assertEquals("100 g salad", evAlias.erkannt)
    }

    @Test
    fun testParseModelsAndFilterVision() {
        // 1. OpenRouter format with explicit architecture.input_modalities
        val openRouterModelsJson = """
            {
              "data": [
                {"id": "meta/llama-3-8b", "architecture": {"input_modalities": ["text"]}},
                {"id": "custom/multimodal-x", "architecture": {"input_modalities": ["text", "image"]}}
              ]
            }
        """.trimIndent()
        val arr1 = ApiClient.parseModelsArray(openRouterModelsJson)
        val entries1 = ApiClient.parseModelEntries(arr1)
        val res1 = ApiClient.filterVisionModels(entries1)
        assertEquals(listOf("custom/multimodal-x"), res1.vision)
        assertEquals(listOf("custom/multimodal-x", "meta/llama-3-8b"), res1.all)

        // 2. Standard OpenAI format without modalities -> heuristic pattern matching
        val openAiModelsJson = """
            {
              "data": [
                {"id": "gpt-3.5-turbo"},
                {"id": "gpt-4o-mini"},
                {"id": "claude-3-5-sonnet"},
                {"id": "text-embedding-3-small"}
              ]
            }
        """.trimIndent()
        val arr2 = ApiClient.parseModelsArray(openAiModelsJson)
        val entries2 = ApiClient.parseModelEntries(arr2)
        val res2 = ApiClient.filterVisionModels(entries2)
        assertEquals(listOf("claude-3-5-sonnet", "gpt-4o-mini"), res2.vision)
        assertEquals(4, res2.all.size)
    }

    @Test
    fun testRunWithOneRetryBehavior() {
        // 1. First attempt succeeds -> called once, no retry
        var calls1 = 0
        val res1 = ApiClient.runWithOneRetry { isRetry ->
            calls1++
            assertFalse(isRetry)
            "OK"
        }
        assertTrue(res1.isSuccess)
        assertEquals("OK", res1.getOrNull())
        assertEquals(1, calls1)

        // 2. First attempt fails, second attempt succeeds -> called twice, returns success without error
        var calls2 = 0
        val res2 = ApiClient.runWithOneRetry { isRetry ->
            calls2++
            if (!isRetry) throw IllegalStateException("First attempt failed")
            "Recovered on retry"
        }
        assertTrue(res2.isSuccess)
        assertEquals("Recovered on retry", res2.getOrNull())
        assertEquals(2, calls2)

        // 3. Both attempts fail -> called twice, returns failure from second attempt
        var calls3 = 0
        val res3 = ApiClient.runWithOneRetry<String> { isRetry ->
            calls3++
            throw IllegalStateException(if (isRetry) "Second attempt failed" else "First attempt failed")
        }
        assertTrue(res3.isFailure)
        assertEquals("Second attempt failed", res3.exceptionOrNull()?.message)
        assertEquals(2, calls3)
    }
}
