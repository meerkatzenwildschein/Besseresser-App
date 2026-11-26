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
                  {"ziel": "Mehr Protein", "ampel": "gelb", "begruendung": "Etwas wenig Protein", "tipp": "Ergänze 100g Hähnchenbrust oder Tofu."},
                  {"ziel": "Blutdruck senken", "ampel": "rot", "begruendung": "Zu viel Salz", "hinweis": "Sojasauce durch natriumarme Variante ersetzen."}
               ],
               "zusatz": "Insgesamt ausgewogen",
               "erkannt": "Haferflocken mit Beeren"
            }
        """.trimIndent()

        val eval = LlmResponseParser.parseEvaluation(json, listOf("Zucker sparen", "Mehr Protein", "Blutdruck senken", "Viel Wasser"))
        assertEquals(4, eval.goalResults.size)
        assertEquals(TrafficLight.GREEN, eval.goalResults[0].rating)
        assertEquals("Kein Zuckerzusatz", eval.goalResults[0].reason)
        assertEquals(null, eval.goalResults[0].tipp)

        assertEquals(TrafficLight.YELLOW, eval.goalResults[1].rating)
        assertEquals("Etwas wenig Protein", eval.goalResults[1].reason)
        assertEquals("Ergänze 100g Hähnchenbrust oder Tofu.", eval.goalResults[1].tipp)

        assertEquals(TrafficLight.RED, eval.goalResults[2].rating)
        assertEquals("Zu viel Salz", eval.goalResults[2].reason)
        assertEquals("Sojasauce durch natriumarme Variante ersetzen.", eval.goalResults[2].tipp)

        assertEquals(TrafficLight.UNKNOWN, eval.goalResults[3].rating)
        assertEquals(null, eval.goalResults[3].tipp)

        assertEquals("Insgesamt ausgewogen", eval.summary)
        assertEquals("Haferflocken mit Beeren", eval.recognizedMeal)
    }

    @Test
    fun testParseEvaluationTippAliasesAndFallbacks() {
        val json = """
            {
               "bewertung": [
                  {"ziel": "Ziel 1", "ampel": "gelb", "begruendung": "Grund 1", "suggestion": "Tipp via suggestion"},
                  {"ziel": "Ziel 2", "ampel": "rot", "begruendung": "Grund 2", "optimierung": "Tipp via optimierung"},
                  {"ziel": "Ziel 3", "ampel": "gelb", "begruendung": "Grund 3", "empfehlung": "Tipp via empfehlung"},
                  {"ziel": "Ziel 4", "ampel": "rot", "begruendung": "Grund 4", "advice": "Tipp via advice"},
                  {"ziel": "Ziel 5", "ampel": "gelb", "begruendung": "Grund 5", "verbesserung": "Tipp via verbesserung"},
                  {"ziel": "Ziel 6", "ampel": "gelb", "begruendung": "Grund 6", "tipp": "   "}
               ]
            }
        """.trimIndent()

        val eval = LlmResponseParser.parseEvaluation(json, listOf("Ziel 1", "Ziel 2", "Ziel 3", "Ziel 4", "Ziel 5", "Ziel 6"))
        assertEquals("Tipp via suggestion", eval.goalResults[0].tipp)
        assertEquals("Tipp via optimierung", eval.goalResults[1].tipp)
        assertEquals("Tipp via empfehlung", eval.goalResults[2].tipp)
        assertEquals("Tipp via advice", eval.goalResults[3].tipp)
        assertEquals("Tipp via verbesserung", eval.goalResults[4].tipp)
        assertNull(eval.goalResults[5].tipp)
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
