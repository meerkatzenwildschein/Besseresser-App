package de.gurum.besseresser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DomainLogicAndFingerprintTest {

    @Test
    fun testDayFingerprintDeterminismAndSorting() {
        val entry1 = HistoryEntry(id = 10L, erkannt = "Salat")
        val entry2 = HistoryEntry(id = 20L, erkannt = "Suppe")

        val fp1 = dayFingerprint(listOf(entry1, entry2))
        val fp2 = dayFingerprint(listOf(entry2, entry1))

        assertEquals("Fingerprint must be deterministic regardless of input list order", fp1, fp2)
    }

    @Test
    fun testDayFingerprintChangesOnErkanntCorrection() {
        val entry1 = HistoryEntry(id = 10L, erkannt = "Salat")
        val entry2 = HistoryEntry(id = 20L, erkannt = "Suppe")

        val fpOriginal = dayFingerprint(listOf(entry1, entry2))

        val entry2Corrected = entry2.copy(erkannt = "Gulaschsuppe")
        val fpModified = dayFingerprint(listOf(entry1, entry2Corrected))

        assertNotEquals("Fingerprint must change when recognized food text changes", fpOriginal, fpModified)
    }

    @Test
    fun testDayFingerprintChangesOnAddedOrRemovedEntry() {
        val entry1 = HistoryEntry(id = 10L, erkannt = "Salat")
        val entry2 = HistoryEntry(id = 20L, erkannt = "Suppe")
        val entry3 = HistoryEntry(id = 30L, erkannt = "Kaffee")

        val fpTwo = dayFingerprint(listOf(entry1, entry2))
        val fpThree = dayFingerprint(listOf(entry1, entry2, entry3))

        assertNotEquals("Fingerprint must change when an entry is added", fpTwo, fpThree)
    }

    @Test
    fun testIsFazitOutdatedWithMatchingEntries() {
        val entry1 = HistoryEntry(id = 10L, erkannt = "Salat")
        val entry2 = HistoryEntry(id = 20L, erkannt = "Suppe")
        val entries = listOf(entry1, entry2)
        val fp = dayFingerprint(entries)

        val fazit = DayFazit(
            dayKey = "20261106",
            ampel = TrafficLight.GREEN,
            text = "Super",
            entryIds = listOf(10L, 20L),
            fingerprint = fp
        )

        assertFalse("Fazit with matching entry IDs and fingerprint should NOT be outdated", isFazitOutdated(fazit, entries))
    }

    @Test
    fun testIsFazitOutdatedWithModifiedFood() {
        val entry1 = HistoryEntry(id = 10L, erkannt = "Salat")
        val entry2 = HistoryEntry(id = 20L, erkannt = "Suppe")
        val originalEntries = listOf(entry1, entry2)
        val fp = dayFingerprint(originalEntries)

        val fazit = DayFazit(
            dayKey = "20261106",
            ampel = TrafficLight.GREEN,
            text = "Super",
            entryIds = listOf(10L, 20L),
            fingerprint = fp
        )

        val modifiedEntries = listOf(entry1, entry2.copy(erkannt = "Minestrone"))
        assertTrue("Fazit must be outdated when food recognition text was edited", isFazitOutdated(fazit, modifiedEntries))
    }

    @Test
    fun testIsFazitOutdatedWithDifferentEntryIds() {
        val entry1 = HistoryEntry(id = 10L, erkannt = "Salat")
        val fazit = DayFazit(
            dayKey = "20261106",
            ampel = TrafficLight.GREEN,
            text = "Super",
            entryIds = listOf(10L),
            fingerprint = dayFingerprint(listOf(entry1))
        )

        val entry2 = HistoryEntry(id = 20L, erkannt = "Snack")
        assertTrue("Fazit must be outdated when entry IDs differ", isFazitOutdated(fazit, listOf(entry1, entry2)))
    }

    @Test
    fun testDayKeyFormatting() {
        val date = LocalDate.of(2026, 11, 6)
        val keyFromDate = dayKeyOf(date)
        assertEquals("20261106", keyFromDate)

        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val keyFromMillis = dayKeyOf(millis)
        assertEquals("20261106", keyFromMillis)
    }

    @Test
    fun testWeekKeyAlignment() {
        // Monday 2026-11-02 to Sunday 2026-11-08 belong to the same week key "20261102"
        val monday = LocalDate.of(2026, 11, 2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val wednesday = LocalDate.of(2026, 11, 4).atTime(14, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sunday = LocalDate.of(2026, 11, 8).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        assertEquals("20261102", weekKeyOf(monday))
        assertEquals("20261102", weekKeyOf(wednesday))
        assertEquals("20261102", weekKeyOf(sunday))

        // Next Monday 2026-11-09 has new week key "20261109"
        val nextMonday = LocalDate.of(2026, 11, 9).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("20261109", weekKeyOf(nextMonday))
    }

    @Test
    fun testProfileAgeCalculation() {
        val currentYear = LocalDate.now().year
        val profile = Profile(birthDate = "${currentYear - 30}-01-01")
        assertEquals(30, profile.ageYears)

        val emptyProfile = Profile()
        assertNull(emptyProfile.ageYears)
        assertTrue(emptyProfile.isEmpty)

        val invalidDateProfile = Profile(birthDate = "not-a-date")
        assertNull(invalidDateProfile.ageYears)
    }

    @Test
    fun testHistoryEntryScoreCalculation() {
        // Empty goals
        val entryEmpty = HistoryEntry()
        assertEquals(-1, entryEmpty.score)

        // Only Unknown
        val entryUnknown = HistoryEntry(goalResults = listOf(GoalResult("Z1", TrafficLight.UNKNOWN, "")))
        assertEquals(-1, entryUnknown.score)

        // All Green
        val entryGreen = HistoryEntry(goalResults = listOf(
            GoalResult("Z1", TrafficLight.GREEN, ""),
            GoalResult("Z2", TrafficLight.GREEN, "")
        ))
        assertEquals(100, entryGreen.score)

        // All Yellow
        val entryYellow = HistoryEntry(goalResults = listOf(
            GoalResult("Z1", TrafficLight.YELLOW, ""),
            GoalResult("Z2", TrafficLight.YELLOW, "")
        ))
        assertEquals(50, entryYellow.score)

        // All Red
        val entryRed = HistoryEntry(goalResults = listOf(
            GoalResult("Z1", TrafficLight.RED, "")
        ))
        assertEquals(0, entryRed.score)

        // Mixed: 1 Green + 1 Yellow = 75
        val entryGreenYellow = HistoryEntry(goalResults = listOf(
            GoalResult("Z1", TrafficLight.GREEN, ""),
            GoalResult("Z2", TrafficLight.YELLOW, "")
        ))
        assertEquals(75, entryGreenYellow.score)

        // Mixed: 1 Green + 1 Red = 50
        val entryGreenRed = HistoryEntry(goalResults = listOf(
            GoalResult("Z1", TrafficLight.GREEN, ""),
            GoalResult("Z2", TrafficLight.RED, "")
        ))
        assertEquals(50, entryGreenRed.score)

        // Mixed: 1 Green + 1 Yellow + 1 Red = 50
        val entryThree = HistoryEntry(goalResults = listOf(
            GoalResult("Z1", TrafficLight.GREEN, ""),
            GoalResult("Z2", TrafficLight.YELLOW, ""),
            GoalResult("Z3", TrafficLight.RED, "")
        ))
        assertEquals(50, entryThree.score)

        // Mixed: 1 Green + 1 Unknown = 100 (unknown is excluded from average)
        val entryGreenUnknown = HistoryEntry(goalResults = listOf(
            GoalResult("Z1", TrafficLight.GREEN, ""),
            GoalResult("Z2", TrafficLight.UNKNOWN, "")
        ))
        assertEquals(100, entryGreenUnknown.score)
    }

    @Test
    fun testGroupByDayDescendingOrder() {
        val day1Millis = LocalDate.of(2026, 11, 4).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val day2MillisA = LocalDate.of(2026, 11, 6).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val day2MillisB = LocalDate.of(2026, 11, 6).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val e1 = HistoryEntry(id = 1L, dateMillis = day1Millis, erkannt = "Suppe")
        val e2a = HistoryEntry(id = 2L, dateMillis = day2MillisA, erkannt = "Abendbrot")
        val e2b = HistoryEntry(id = 3L, dateMillis = day2MillisB, erkannt = "Frühstück")

        val groups = groupByDay(listOf(e1, e2a, e2b))
        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2026, 11, 6), groups[0].day)
        assertEquals(2, groups[0].entries.size)
        assertEquals(LocalDate.of(2026, 11, 4), groups[1].day)
        assertEquals(1, groups[1].entries.size)
    }

    @Test
    fun testZielePreviewFormatting() {
        val emptyEntry = HistoryEntry(goalResults = emptyList())
        assertEquals("Keine Ziele", zielePreview(emptyEntry, "Keine Ziele", "+0"))

        val oneGoal = HistoryEntry(goalResults = listOf(GoalResult("Blutdruck senken", TrafficLight.GREEN, "")))
        assertEquals("Blutdruck senken", zielePreview(oneGoal, "Keine Ziele", "+0"))

        val twoGoals = HistoryEntry(goalResults = listOf(
            GoalResult("Blutdruck senken", TrafficLight.GREEN, ""),
            GoalResult("Weniger Zucker", TrafficLight.YELLOW, "")
        ))
        assertEquals("Blutdruck senken, Weniger Zucker", zielePreview(twoGoals, "Keine Ziele", "+0"))

        val fourGoals = HistoryEntry(goalResults = listOf(
            GoalResult("Blutdruck senken", TrafficLight.GREEN, ""),
            GoalResult("Weniger Zucker", TrafficLight.YELLOW, ""),
            GoalResult("Gewicht verlieren", TrafficLight.GREEN, ""),
            GoalResult("Gesünder ernähren", TrafficLight.RED, "")
        ))
        assertEquals("Blutdruck senken, Weniger Zucker +2", zielePreview(fourGoals, "Keine Ziele", "+2"))
    }

    @Test
    fun testScoreColorThresholds() {
        assertEquals(TrafficLight.UNKNOWN.color(), scoreColor(-1))
        assertEquals(TrafficLight.RED.color(), scoreColor(0))
        assertEquals(TrafficLight.RED.color(), scoreColor(49))
        assertEquals(TrafficLight.YELLOW.color(), scoreColor(50))
        assertEquals(TrafficLight.YELLOW.color(), scoreColor(79))
        assertEquals(TrafficLight.GREEN.color(), scoreColor(80))
        assertEquals(TrafficLight.GREEN.color(), scoreColor(100))
    }

    @Test
    fun testHandleAutoDayFazitTriggerLogic() {
        DayFazitSession.attempted.clear()
        val date = LocalDate.of(2026, 11, 5)
        val key = dayKeyOf(date)
        val entry = HistoryEntry(id = 10L, erkannt = "Salat")
        val group = DayGroup(day = date, entries = listOf(entry))

        var triggeredCount = 0

        // 1. Before 18:00 (autoFazitTime = false) -> does NOT trigger
        handleAutoDayFazit(
            group = group,
            fazitMap = emptyMap(),
            loadingFazits = emptyList(),
            autoFazitTime = false,
            onGenerateFazit = { _, _ -> triggeredCount++ }
        )
        assertEquals(0, triggeredCount)

        // 2. While already loading -> does NOT trigger
        handleAutoDayFazit(
            group = group,
            fazitMap = emptyMap(),
            loadingFazits = listOf(key),
            autoFazitTime = true,
            onGenerateFazit = { _, _ -> triggeredCount++ }
        )
        assertEquals(0, triggeredCount)

        // 3. First time after 18:00 with missing fazit -> triggers once and records in session
        handleAutoDayFazit(
            group = group,
            fazitMap = emptyMap(),
            loadingFazits = emptyList(),
            autoFazitTime = true,
            onGenerateFazit = { _, _ -> triggeredCount++ }
        )
        assertEquals(1, triggeredCount)
        assertTrue(key in DayFazitSession.attempted)

        // 4. Second call in same session with still missing fazit -> does NOT re-trigger
        handleAutoDayFazit(
            group = group,
            fazitMap = emptyMap(),
            loadingFazits = emptyList(),
            autoFazitTime = true,
            onGenerateFazit = { _, _ -> triggeredCount++ }
        )
        assertEquals(1, triggeredCount)

        // 5. Existing fazit that became outdated (new meal added) -> DOES re-trigger
        val outdatedFazit = DayFazit(dayKey = key, ampel = TrafficLight.GREEN, text = "Alt", entryIds = listOf(999L))
        handleAutoDayFazit(
            group = group,
            fazitMap = mapOf(key to outdatedFazit),
            loadingFazits = emptyList(),
            autoFazitTime = true,
            onGenerateFazit = { _, _ -> triggeredCount++ }
        )
        assertEquals(2, triggeredCount)
    }
}
