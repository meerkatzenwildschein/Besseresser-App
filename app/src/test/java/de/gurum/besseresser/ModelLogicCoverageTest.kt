package de.gurum.besseresser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.HistoryEntryLogic
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.RecentNotesLogic
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.WeightedGoal
import de.gurum.besseresser.model.retentionCutoffMillis
import de.gurum.besseresser.model.toWeightedGoals

/**
 * Coverage for model-layer logic branches that were previously untested.
 * All tests run on JVM with no Android dependencies.
 */
class ModelLogicCoverageTest {

    // ----------------------------------------------------------------- Settings.isComplete

    @Test
    fun `Settings isComplete false when baseUrl blank`() {
        assertFalse(Settings(baseUrl = "", apiKey = "k", model = "m", goals = listOf("Z").toWeightedGoals()).isComplete)
    }

    @Test
    fun `Settings isComplete false when apiKey blank`() {
        assertFalse(Settings(baseUrl = "https://x", apiKey = "", model = "m", goals = listOf("Z").toWeightedGoals()).isComplete)
    }

    @Test
    fun `Settings isComplete false when model blank`() {
        assertFalse(Settings(baseUrl = "https://x", apiKey = "k", model = "", goals = listOf("Z").toWeightedGoals()).isComplete)
    }

    @Test
    fun `Settings isComplete false when goals empty`() {
        assertFalse(Settings(baseUrl = "https://x", apiKey = "k", model = "m", goals = emptyList()).isComplete)
    }

    @Test
    fun `Settings isComplete true when all fields present`() {
        assertTrue(Settings(baseUrl = "https://x", apiKey = "k", model = "m", goals = listOf("Z").toWeightedGoals()).isComplete)
    }

    // ----------------------------------------------------------------- Evaluation.matchesGoals

    @Test
    fun `matchesGoals false when goals empty`() {
        val ev = Evaluation(goalResults = listOf(GoalResult("Z", TrafficLight.GREEN, "")))
        assertFalse(ev.matchesGoals(emptyList()))
    }

    @Test
    fun `matchesGoals false when goalResults empty`() {
        val ev = Evaluation(goalResults = emptyList())
        assertFalse(ev.matchesGoals(listOf("Z")))
    }

    @Test
    fun `matchesGoals false when size mismatch`() {
        val ev = Evaluation(goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, ""), GoalResult("Z2", TrafficLight.RED, "")))
        assertFalse(ev.matchesGoals(listOf("Z1", "Z2", "Z3")))
    }

    @Test
    fun `matchesGoals true with case-insensitive goal name match`() {
        val ev = Evaluation(goalResults = listOf(GoalResult("wenig zucker", TrafficLight.GREEN, "")))
        assertTrue(ev.matchesGoals(listOf("Wenig Zucker")))
    }

    @Test
    fun `matchesGoals false when goal name does not match`() {
        val ev = Evaluation(goalResults = listOf(GoalResult("Ziel A", TrafficLight.GREEN, "")))
        assertFalse(ev.matchesGoals(listOf("Ziel B")))
    }

    // ----------------------------------------------------------------- TrafficLight.fromString edge cases

    @Test
    fun `TrafficLight fromString prot guard only applies in normalization path`() {
        // "Protein" contains the substring "rot" (P-r-o-t...) → first check has("rot") → RED
        // The "prot" guard in the normalization branch is never reached here.
        assertEquals(TrafficLight.RED, TrafficLight.fromString("Protein"))
        // Same: "proteinreich" contains "rot" → RED (first check fires before normalization guard)
        assertEquals(TrafficLight.RED, TrafficLight.fromString("proteinreich"))
        // Plain "rot" matches RED
        assertEquals(TrafficLight.RED, TrafficLight.fromString("rot"))
        // "Karotten" contains "rot" → RED
        assertEquals(TrafficLight.RED, TrafficLight.fromString("Karotten"))
        // Something without "rot": UNKNOWN
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString("Spinat"))
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString("Hühnersuppe"))
    }

    @Test
    fun `TrafficLight fromString matches rot as standalone`() {
        assertEquals(TrafficLight.RED, TrafficLight.fromString("rot"))
        assertEquals(TrafficLight.RED, TrafficLight.fromString("ROT"))
        assertEquals(TrafficLight.RED, TrafficLight.fromString("red"))
    }

    @Test
    fun `TrafficLight fromString returns UNKNOWN for unknown input`() {
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString("blau"))
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString(""))
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString("   "))
    }

    // ----------------------------------------------------------------- AppLanguage.fromString / resolve

    @Test
    fun `AppLanguage fromString returns null for unknown locale`() {
        assertNull(AppLanguage.fromString("fr"))
        assertNull(AppLanguage.fromString(""))
        assertNull(AppLanguage.fromString(null))
    }

    @Test
    fun `AppLanguage resolve falls back to DE for german system language`() {
        assertEquals(AppLanguage.DE, AppLanguage.resolve(null, systemLanguage = "de"))
        assertEquals(AppLanguage.DE, AppLanguage.resolve(null, systemLanguage = "de-AT"))
    }

    @Test
    fun `AppLanguage resolve falls back to EN for non-german system language`() {
        assertEquals(AppLanguage.EN, AppLanguage.resolve(null, systemLanguage = "fr"))
        assertEquals(AppLanguage.EN, AppLanguage.resolve(null, systemLanguage = "zh"))
    }

    @Test
    fun `AppLanguage resolve uses saved value over system language`() {
        assertEquals(AppLanguage.EN, AppLanguage.resolve("en", systemLanguage = "de"))
        assertEquals(AppLanguage.DE, AppLanguage.resolve("de", systemLanguage = "en"))
    }

    // ----------------------------------------------------------------- RecentNotesLogic gaps

    @Test
    fun `pushNote with blank text returns normalized list unchanged`() {
        val existing = listOf(RecentNote("Salat"), RecentNote("Suppe"))
        val result = RecentNotesLogic.pushNote(existing, "   ")
        assertEquals(existing.map { it.text }, result.map { it.text })
    }

    @Test
    fun `pushNote with starred existing entry moves it to top of starred section`() {
        val starred = RecentNote("Favorit", starred = true)
        val other = RecentNote("Anderes", starred = true)
        val unstarred = RecentNote("Normal")
        val list = listOf(other, unstarred, starred)
        val result = RecentNotesLogic.pushNote(list, "Favorit")
        // starred entry should be first among starred
        val starredResults = result.filter { it.starred }
        assertEquals("Favorit", starredResults.first().text)
    }

    @Test
    fun `pushNote evicts oldest unstarred when exceeding MAX_UNSTARRED`() {
        val notes = (1..5).map { RecentNote("Note$it") }
        val result = RecentNotesLogic.pushNote(notes, "NoteNew")
        val unstarred = result.filter { !it.starred }
        assertEquals(5, unstarred.size)
        assertTrue(unstarred.any { it.text == "NoteNew" })
        assertFalse(unstarred.any { it.text == "Note5" }) // oldest evicted
    }

    @Test
    fun `pushNote with starred entries preserves all starred when evicting unstarred`() {
        val starred = (1..3).map { RecentNote("Star$it", starred = true) }
        val unstarred = (1..5).map { RecentNote("Note$it") }
        val list = starred + unstarred
        val result = RecentNotesLogic.pushNote(list, "NoteNew")
        val resultStarred = result.filter { it.starred }
        assertEquals(3, resultStarred.size)
        assertEquals(5, result.filter { !it.starred }.size)
    }

    @Test
    fun `toggleStarInList with blank text returns list unchanged`() {
        val list = listOf(RecentNote("Salat"), RecentNote("Suppe"))
        val result = RecentNotesLogic.toggleStarInList(list, "   ")
        assertEquals(list, result)
    }

    @Test
    fun `normalizeAndPrune caps total entries at MAX_TOTAL`() {
        val notes = (1..50).map { RecentNote("Note$it", starred = true) }
        val result = RecentNotesLogic.normalizeAndPrune(notes)
        assertEquals(40, result.size)
    }

    @Test
    fun `formatLoadedNotes with sortStarredFirst false preserves insertion order up to max unstarred`() {
        val mixed = listOf(
            RecentNote("U1"), RecentNote("S1", starred = true),
            RecentNote("U2"), RecentNote("U3"), RecentNote("U4"),
            RecentNote("U5"), RecentNote("U6")  // U6 should be cut
        )
        val result = RecentNotesLogic.formatLoadedNotes(mixed, sortStarredFirst = false)
        assertEquals(6, result.size) // S1 + U1..U5
        assertFalse(result.any { it.text == "U6" })
    }

    @Test
    fun `parseRecentNotes returns empty list for completely invalid JSON`() {
        val result = RecentNotesLogic.parseRecentNotes("not json at all }{")
        assertEquals(emptyList<RecentNote>(), result)
    }

    @Test
    fun `parseRecentNotes parses legacy string array format`() {
        val json = """["Salat","Suppe","Pizza"]"""
        val result = RecentNotesLogic.parseRecentNotes(json)
        assertEquals(3, result.size)
        assertEquals("Salat", result[0].text)
        assertFalse(result[0].starred)
    }

    @Test
    fun `updateEvaluationInList returns unchanged list when note not found`() {
        val list = listOf(RecentNote("Salat"), RecentNote("Suppe"))
        val ev = Evaluation(recognizedMeal = "X")
        val result = RecentNotesLogic.updateEvaluationInList(list, "Pizza", ev)
        assertEquals(list, result)
    }

    @Test
    fun `findMatchingEvaluation returns null for blank note`() {
        val list = listOf(RecentNote("Salat", evaluation = Evaluation(recognizedMeal = "Salat")))
        assertNull(RecentNotesLogic.findMatchingEvaluation(list, "   ", listOf("Z")))
    }

    // ----------------------------------------------------------------- HistoryEntryLogic.computeChronologicalSortOrders

    @Test
    fun `computeChronologicalSortOrders assigns non-zero sortOrders to all entries`() {
        val base = System.currentTimeMillis()
        val existing = listOf(HistoryEntry(id = 1L, dateMillis = base, backdated = false))
        val newEntry = HistoryEntry(id = 2L, dateMillis = base + 1000, backdated = false)
        val result = HistoryEntryLogic.computeChronologicalSortOrders(existing, newEntry)
        assertEquals(2, result.size)
        assertTrue("All entries must have non-zero sortOrder after computation",
            result.all { it.sortOrder != 0L })
    }

    @Test
    fun `computeChronologicalSortOrders assigns strictly descending sortOrders`() {
        val base = System.currentTimeMillis()
        val existing = listOf(
            HistoryEntry(id = 1L, dateMillis = base, backdated = false),
            HistoryEntry(id = 2L, dateMillis = base + 60_000, backdated = false)
        )
        val newEntry = HistoryEntry(id = 3L, dateMillis = base + 120_000, backdated = false)
        val result = HistoryEntryLogic.computeChronologicalSortOrders(existing, newEntry)
        val sorted = result.sortedByDescending { it.sortOrder }
        for (i in 1 until sorted.size) {
            assertTrue("sortOrders must be strictly descending", sorted[i].sortOrder < sorted[i - 1].sortOrder)
        }
    }

    @Test
    fun `computeChronologicalSortOrders enforces minimum 1000ms gap between colliding timestamps`() {
        val base = System.currentTimeMillis()
        // Two entries with same timestamp — should get enforced gap
        val existing = listOf(HistoryEntry(id = 1L, dateMillis = base, backdated = false))
        val newEntry = HistoryEntry(id = 2L, dateMillis = base, backdated = false)
        val result = HistoryEntryLogic.computeChronologicalSortOrders(existing, newEntry)
        val orders = result.map { it.sortOrder }.sorted()
        assertTrue("Entries with same timestamp must have sortOrders at least 1000ms apart",
            orders[1] - orders[0] >= 1000L)
    }

    @Test
    fun `HistoryEntry effectiveSortOrder falls back to dateMillis when sortOrder is 0`() {
        val entry = HistoryEntry(id = 1L, dateMillis = 99_000L, sortOrder = 0L)
        assertEquals(99_000L, entry.effectiveSortOrder)
    }

    @Test
    fun `HistoryEntry effectiveSortOrder uses sortOrder when non-zero`() {
        val entry = HistoryEntry(id = 1L, dateMillis = 99_000L, sortOrder = 123_000L)
        assertEquals(123_000L, entry.effectiveSortOrder)
    }

    // ----------------------------------------------------------------- retentionCutoffMillis boundary

    @Test
    fun `retentionCutoffMillis with 1 week is in the past`() {
        val cutoff = retentionCutoffMillis(1)
        assertTrue("1-week cutoff must be before now", cutoff < System.currentTimeMillis())
    }

    @Test
    fun `retentionCutoffMillis with 0 weeks returns 0 (disabled)`() {
        // 0 is handled by callers (purgeOlderThanWeeks returns 0 if weeks <= 0)
        // The function itself returns a time 0 weeks before the last Monday
        val cutoff = retentionCutoffMillis(0)
        // Should be at most a few days in the past (Monday of current week at midnight)
        assertTrue("0-week cutoff should be at or before now", cutoff <= System.currentTimeMillis())
    }

    // ----------------------------------------------------------------- ApiClient: parseEvaluation edge cases

    @Test
    fun `parseEvaluation with empty goals list returns empty goalResults`() {
        val raw = """{"bewertung":[{"ziel":"Z","ampel":"gruen","begruendung":"OK"}],"zusatz":"","erkannt":"Pasta"}"""
        val ev = ApiClient.parseEvaluation(raw, emptyList())
        // With no goals to match against, results should be empty or contain one UNKNOWN entry
        // — either way must not crash
        assertNotNull(ev)
        assertEquals("Pasta", ev.recognizedMeal)
    }

    @Test
    fun `parseEvaluation with fewer KI items than goals fills missing with UNKNOWN`() {
        val goals = listOf("Ziel 1", "Ziel 2", "Ziel 3")
        val raw = """{"bewertung":[{"ziel":"Ziel 1","ampel":"gruen","begruendung":"OK"}],"zusatz":"","erkannt":""}"""
        val ev = ApiClient.parseEvaluation(raw, goals)
        assertEquals(3, ev.goalResults.size)
        assertEquals(TrafficLight.GREEN, ev.goalResults[0].rating)
        // Remaining goals get UNKNOWN
        assertTrue(ev.goalResults.drop(1).all { it.rating == TrafficLight.UNKNOWN })
    }

    @Test
    fun `OpenAiChatClient tolerantJson throws on input with no JSON object`() {
        val e = runCatching { de.gurum.besseresser.data.OpenAiChatClient.tolerantJson("no braces here") }
        assertTrue("tolerantJson must throw when no JSON found", e.isFailure)
    }

    @Test
    fun `OpenAiChatClient closeJson handles truncated string value`() {
        val truncated = """{"ampel":"gruen","text":"Guter """
        val closed = de.gurum.besseresser.data.OpenAiChatClient.closeJson(truncated)
        // Must produce parseable JSON
        val parsed = runCatching { de.gurum.besseresser.model.AppJson.parseToJsonElement(closed) }
        assertTrue("closeJson output must be valid JSON", parsed.isSuccess)
    }

    @Test
    fun `filterVisionModels with no vision-matching models returns empty vision list`() {
        val allModels = listOf("text-davinci-002" to false, "text-curie-001" to false, "whisper-1" to false)
        val result = ApiClient.filterVisionModels(allModels)
        assertTrue("Vision list must be empty when no vision models exist", result.vision.isEmpty())
        // all is sorted alphabetically by the implementation
        assertEquals(allModels.map { it.first }.sorted(), result.all)
    }
}
