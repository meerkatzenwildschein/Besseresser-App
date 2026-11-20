package de.gurum.besseresser

import de.gurum.besseresser.data.FazitManager
import de.gurum.besseresser.data.FazitPromptBundle
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.dayFingerprint
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FazitManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun testValidateWeekFazitValidAndOutdated() = runTest(testDispatcher) {
        val fakeApi = FakeApiService()
        val fakeHistory = FakeHistoryRepository()
        val fakeSettings = FakeSettingsRepository()
        val manager = FazitManager(fakeApi, fakeHistory, fakeSettings)

        val entry = HistoryEntry(id = 1L, dateMillis = 1000L, recognizedMeal = "Salat")
        val entries = listOf(entry)
        val validFazit = DayFazit(
            dayKey = "2026_W40",
            rating = TrafficLight.GREEN,
            text = "Super",
            fingerprint = dayFingerprint(entries)
        )

        val resultValid = manager.validateWeekFazit("2026_W40", validFazit, entries)
        assertEquals(validFazit, resultValid)

        val outdatedFazit = validFazit.copy(fingerprint = "different")
        val resultOutdated = manager.validateWeekFazit("2026_W40", outdatedFazit, entries)
        assertNull(resultOutdated)
    }

    @Test
    fun testComputeDayFazit() = runTest(testDispatcher) {
        val fakeApi = FakeApiService()
        val fakeHistory = FakeHistoryRepository()
        val fakeSettings = FakeSettingsRepository()
        val manager = FazitManager(fakeApi, fakeHistory, fakeSettings)

        val entry = HistoryEntry(id = 10L, dateMillis = System.currentTimeMillis(), recognizedMeal = "Suppe")
        val day = LocalDate.of(2026, 11, 20)
        val res = manager.computeDayFazit(
            day = day,
            entriesOfDay = listOf(entry),
            settings = Settings(baseUrl = "https://ai.example.com", apiKey = "test", model = "gpt-4o"),
            promptContext = null,
            mealDescriptions = listOf("Suppe"),
            prompts = FazitPromptBundle("intro", "rules", "de", "honesty")
        )

        assertTrue(res.isSuccess)
        val fazit = res.getOrThrow()
        assertEquals("20261120", fazit.dayKey)
        assertNotNull(fakeHistory.loadDayFazit("20261120"))
    }
}
