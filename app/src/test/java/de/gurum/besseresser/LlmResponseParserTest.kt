package de.gurum.besseresser

import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.data.LlmResponseParser
import de.gurum.besseresser.model.TrafficLight
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmResponseParserTest {

    @Test
    fun testTolerantJsonAndCloseJson() {
        val incomplete = """{"ampel":"gruen","text":"Sehr gesund"""
        val closed = LlmResponseParser.closeJson(incomplete)
        assertEquals("""{"ampel":"gruen","text":"Sehr gesund"}""", closed)

        val markdownJson = "```json\n{\"ampel\": \"gruen\", \"text\": \"Sehr gesund\"}\n```"
        val parsed = LlmResponseParser.tolerantJson(markdownJson)
        assertEquals("gruen", parsed["ampel"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Sehr gesund", parsed["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun testStripRecognizedMealIntro() {
        assertEquals("Pizza Margherita", LlmResponseParser.stripRecognizedMealIntro("Auf dem Foto ist zu sehen: Pizza Margherita"))
        assertEquals("Salat", LlmResponseParser.stripRecognizedMealIntro("the photo shows: Salat"))
        assertEquals("Pasta", LlmResponseParser.stripRecognizedMealIntro("Pasta"))
    }

    @Test
    fun testParseEvaluationMatching() {
        val json = """
            {
               "bewertung": [
                  {"ziel": "Zucker sparen", "ampel": "gruen", "begruendung": "Kein Zuckerzusatz"},
                  {"ziel": "Mehr Protein", "ampel": "gelb", "begruendung": "Etwas wenig Protein"}
               ],
               "zusatz": "Insgesamt ausgewogen",
               "erkannt": "Haferflocken mit Beeren"
            }
        """.trimIndent()

        val eval = LlmResponseParser.parseEvaluation(json, listOf("Zucker sparen", "Mehr Protein", "Viel Wasser"))
        assertEquals(3, eval.goalResults.size)
        assertEquals(TrafficLight.GREEN, eval.goalResults[0].rating)
        assertEquals("Kein Zuckerzusatz", eval.goalResults[0].reason)
        assertEquals(TrafficLight.YELLOW, eval.goalResults[1].rating)
        assertEquals(TrafficLight.UNKNOWN, eval.goalResults[2].rating)
        assertEquals("Insgesamt ausgewogen", eval.summary)
        assertEquals("Haferflocken mit Beeren", eval.recognizedMeal)
    }

    @Test
    fun testParseModelsAndFilterVision() {
        val modelsJson = """
            {
                "data": [
                    {"id": "gpt-4o", "architecture": {"input_modalities": ["text", "image"]}},
                    {"id": "gpt-4-turbo", "architecture": {"input_modalities": ["text"]}},
                    {"id": "deepseek-chat", "architecture": {"input_modalities": ["text"]}}
                ]
            }
        """.trimIndent()

        val arr = LlmResponseParser.parseModelsArray(modelsJson)
        val entries = LlmResponseParser.parseModelEntries(arr)
        val res = LlmResponseParser.filterVisionModels(entries)

        assertEquals(listOf("gpt-4o"), res.vision)
        assertEquals(listOf("deepseek-chat", "gpt-4-turbo", "gpt-4o"), res.all)
    }
}
