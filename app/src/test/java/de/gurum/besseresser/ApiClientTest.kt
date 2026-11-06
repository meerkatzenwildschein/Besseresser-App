package de.gurum.besseresser

import org.junit.Assert.assertEquals
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
        val raw = " , ; ! "
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
    fun testOpenAiChatClientCloseJson() {
        val incomplete = """{"ampel":"gruen","text":"Guter Tag"""
        val closed = OpenAiChatClient.closeJson(incomplete)
        assertEquals("""{"ampel":"gruen","text":"Guter Tag"}""", closed)
    }

    @Test
    fun testOpenAiChatClientTolerantJson() {
        val markdownJson = "Hier ist die Antwort:\n```json\n{\"ampel\":\"rot\",\"text\":\"Zu viel Zucker\"}\n```\nDanke!"
        val obj = OpenAiChatClient.tolerantJson(markdownJson)
        assertEquals("rot", obj["ampel"]?.let { kotlinx.serialization.json.jsonPrimitive.contentOrNull })
        assertEquals("Zu viel Zucker", obj["text"]?.let { kotlinx.serialization.json.jsonPrimitive.contentOrNull })
    }
}
