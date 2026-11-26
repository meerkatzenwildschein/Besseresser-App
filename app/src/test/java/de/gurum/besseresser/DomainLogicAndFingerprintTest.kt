package de.gurum.besseresser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import de.gurum.besseresser.ui.buildShareText
import de.gurum.besseresser.ui.buildHistoryUiItems
import de.gurum.besseresser.ui.color
import de.gurum.besseresser.ui.formatDate
import de.gurum.besseresser.ui.formatDateTime
import de.gurum.besseresser.ui.formatTime
import de.gurum.besseresser.ui.HistoryUiItem
import de.gurum.besseresser.model.calendarWeekOf
import de.gurum.besseresser.model.calendarWeekOfKey
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.DayFazitSession
import de.gurum.besseresser.model.dayFingerprint
import de.gurum.besseresser.ui.DayGroup
import de.gurum.besseresser.model.dayKeyOf
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.ui.groupByDay
import de.gurum.besseresser.ui.handleAutoDayFazit
import de.gurum.besseresser.ui.handleAutoWeekFazitForKey
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.HistoryEntryLogic
import de.gurum.besseresser.ui.isFazitOutdated
import de.gurum.besseresser.ui.isWeekFazitDue
import de.gurum.besseresser.ui.latestDueWeekKey
import de.gurum.besseresser.ui.scoreColor
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.weekKeyOf
import de.gurum.besseresser.ui.zielePreview
import de.gurum.besseresser.model.Profile

class DomainLogicAndFingerprintTest {

    @Test
    fun testDayFingerprintDeterminismAndSorting() {
        val entry1 = HistoryEntry(id = 10L, recognizedMeal = "Salat")
        val entry2 = HistoryEntry(id = 20L, recognizedMeal = "Suppe")

        val fp1 = dayFingerprint(listOf(entry1, entry2))
        val fp2 = dayFingerprint(listOf(entry2, entry1))

        assertEquals("Fingerprint must be deterministic regardless of input list order", fp1, fp2)
    }

    @Test
    fun testDayFingerprintChangesOnErkanntCorrection() {
        val entry1 = HistoryEntry(id = 10L, recognizedMeal = "Salat")
        val entry2 = HistoryEntry(id = 20L, recognizedMeal = "Suppe")

        val fpOriginal = dayFingerprint(listOf(entry1, entry2))

        val entry2Corrected = entry2.copy(recognizedMeal = "Gulaschsuppe")
        val fpModified = dayFingerprint(listOf(entry1, entry2Corrected))

        assertNotEquals("Fingerprint must change when recognized food text changes", fpOriginal, fpModified)
    }

    @Test
    fun testDayFingerprintChangesOnGoalRatingChange() {
        val entry1 = HistoryEntry(
            id = 10L,
            recognizedMeal = "Salat",
            goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Gut"))
        )
        val fpOriginal = dayFingerprint(listOf(entry1))

        val entry1ReEvaluated = entry1.copy(
            goalResults = listOf(GoalResult("Z1", TrafficLight.YELLOW, "Mittel"))
        )
        val fpModified = dayFingerprint(listOf(entry1ReEvaluated))

        assertNotEquals("Fingerprint must change when goal ratings change on re-evaluation", fpOriginal, fpModified)
    }

    @Test
    fun testDayFingerprintChangesOnNoteChange() {
        val entry1 = HistoryEntry(id = 10L, recognizedMeal = "Salat", note = "Frisch")
        val fpOriginal = dayFingerprint(listOf(entry1))

        val entry1NoteChanged = entry1.copy(note = "Mit Dressing")
        val fpModified = dayFingerprint(listOf(entry1NoteChanged))

        assertNotEquals("Fingerprint must change when user note changes", fpOriginal, fpModified)
    }

    @Test
    fun testDayFingerprintChangesOnAddedOrRemovedEntry() {
        val entry1 = HistoryEntry(id = 10L, recognizedMeal = "Salat")
        val entry2 = HistoryEntry(id = 20L, recognizedMeal = "Suppe")
        val entry3 = HistoryEntry(id = 30L, recognizedMeal = "Kaffee")

        val fpTwo = dayFingerprint(listOf(entry1, entry2))
        val fpThree = dayFingerprint(listOf(entry1, entry2, entry3))

        assertNotEquals("Fingerprint must change when an entry is added", fpTwo, fpThree)
    }

    @Test
    fun testIsFazitOutdatedWithMatchingEntries() {
        val entry1 = HistoryEntry(id = 10L, recognizedMeal = "Salat")
        val entry2 = HistoryEntry(id = 20L, recognizedMeal = "Suppe")
        val entries = listOf(entry1, entry2)
        val fp = dayFingerprint(entries)

        val fazit = DayFazit(
            dayKey = "20261106",
            rating = TrafficLight.GREEN,
            text = "Super",
            entryIds = listOf(10L, 20L),
            fingerprint = fp
        )

        assertFalse("Fazit with matching entry IDs and fingerprint should NOT be outdated", isFazitOutdated(fazit, entries))
    }

    @Test
    fun testIsFazitOutdatedWithModifiedFood() {
        val entry1 = HistoryEntry(id = 10L, recognizedMeal = "Salat")
        val entry2 = HistoryEntry(id = 20L, recognizedMeal = "Suppe")
        val originalEntries = listOf(entry1, entry2)
        val fp = dayFingerprint(originalEntries)

        val fazit = DayFazit(
            dayKey = "20261106",
            rating = TrafficLight.GREEN,
            text = "Super",
            entryIds = listOf(10L, 20L),
            fingerprint = fp
        )

        val modifiedEntries = listOf(entry1, entry2.copy(recognizedMeal = "Minestrone"))
        assertTrue("Fazit must be outdated when food recognition text was edited", isFazitOutdated(fazit, modifiedEntries))
    }

    @Test
    fun testIsFazitOutdatedWithDifferentEntryIds() {
        val entry1 = HistoryEntry(id = 10L, recognizedMeal = "Salat")
        val fazit = DayFazit(
            dayKey = "20261106",
            rating = TrafficLight.GREEN,
            text = "Super",
            entryIds = listOf(10L),
            fingerprint = dayFingerprint(listOf(entry1))
        )

        val entry2 = HistoryEntry(id = 20L, recognizedMeal = "Snack")
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

        // Calendar week calculation (ISO-8601)
        assertEquals(45, calendarWeekOf(LocalDate.of(2026, 11, 2)))
        assertEquals(45, calendarWeekOf(LocalDate.of(2026, 11, 8)))
        assertEquals(46, calendarWeekOf(LocalDate.of(2026, 11, 9)))
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

        val e1 = HistoryEntry(id = 1L, dateMillis = day1Millis, recognizedMeal = "Suppe")
        val e2a = HistoryEntry(id = 2L, dateMillis = day2MillisA, recognizedMeal = "Abendbrot")
        val e2b = HistoryEntry(id = 3L, dateMillis = day2MillisB, recognizedMeal = "Frühstück")

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
        val entry = HistoryEntry(id = 10L, recognizedMeal = "Salat")
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
        val outdatedFazit = DayFazit(dayKey = key, rating = TrafficLight.GREEN, text = "Alt", entryIds = listOf(999L))
        handleAutoDayFazit(
            group = group,
            fazitMap = mapOf(key to outdatedFazit),
            loadingFazits = emptyList(),
            autoFazitTime = true,
            onGenerateFazit = { _, _ -> triggeredCount++ }
        )
        assertEquals(2, triggeredCount)

        // 6. Calling again in the same session without entry changes -> does NOT re-trigger
        handleAutoDayFazit(
            group = group,
            fazitMap = mapOf(key to outdatedFazit),
            loadingFazits = emptyList(),
            autoFazitTime = true,
            onGenerateFazit = { _, _ -> triggeredCount++ }
        )
        assertEquals(2, triggeredCount)
    }

    @Test
    fun testDuplicateEntryCopiesAllFieldsMarksBackdatedAndInvalidatesTargetDayFazit() {
        val originalDay = LocalDate.of(2026, 11, 2)
        val originalMillis = originalDay.atTime(12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val source = HistoryEntry(
            id = 1001L,
            dateMillis = originalMillis,
            photoFile = "1001.jpg",
            goalResults = listOf(
                GoalResult("Blutdruck senken", TrafficLight.GREEN, "Wenig Salz"),
                GoalResult("Weniger Zucker", TrafficLight.YELLOW, "Etwas Obstzucker")
            ),
            summary = "Sehr ausgewogenes Mittagessen",
            note = "Selbstgekocht ohne Zusatzstoffe",
            recognizedMeal = "250 g Gemüse-Curry mit Reis",
            backdated = false
        )

        val targetDay = LocalDate.of(2026, 11, 6)
        val existingTargetEntry = HistoryEntry(
            id = 2001L,
            dateMillis = targetDay.atTime(8, 15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "150 g Skyr mit Beeren"
        )
        // Target day had a valid Fazit before duplication
        val targetDayFazit = DayFazit(
            dayKey = dayKeyOf(targetDay),
            rating = TrafficLight.GREEN,
            text = "Bisher ein guter Tag",
            entryIds = listOf(existingTargetEntry.id),
            fingerprint = dayFingerprint(listOf(existingTargetEntry))
        )
        assertFalse(isFazitOutdated(targetDayFazit, listOf(existingTargetEntry)))

        // Duplicate source entry to targetDay with explicit targetTime
        val targetTime = java.time.LocalTime.of(18, 45)
        val duplicated = HistoryEntryLogic.createDuplicatedEntry(
            source = source,
            targetDay = targetDay,
            targetTime = targetTime,
            newId = 3005L,
            newPhotoFile = "3005.jpg"
        )

        // All content fields are copied 1:1
        assertEquals(3005L, duplicated.id)
        assertEquals("3005.jpg", duplicated.photoFile)
        assertEquals(source.goalResults, duplicated.goalResults)
        assertEquals(source.summary, duplicated.summary)
        assertEquals(source.note, duplicated.note)
        assertEquals(source.recognizedMeal, duplicated.recognizedMeal)
        assertEquals(source.score, duplicated.score)
        // Has an explicit user-chosen time on targetDay -> backdated is false
        assertFalse(duplicated.backdated)
        assertEquals(dayKeyOf(targetDay), dayKeyOf(duplicated.dateMillis))
        val expectedMillis = targetDay.atTime(18, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expectedMillis, duplicated.dateMillis)
        assertEquals(expectedMillis, duplicated.sortOrder)

        // Target day's existing Fazit is now automatically tracked as outdated/stale
        val entriesAfterDuplication = listOf(existingTargetEntry, duplicated)
        assertTrue(isFazitOutdated(targetDayFazit, entriesAfterDuplication))

        // Even if the target day had a legacy backdated (23:59) or manually reordered entry,
        // computeChronologicalSortOrders places the new entry at the right position for its time (18:45):
        val legacyBackdatedEntry = HistoryEntry(
            id = 2002L,
            dateMillis = targetDay.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "Alter nacherfasster Eintrag",
            backdated = true,
            sortOrder = targetDay.atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        val synced = HistoryEntryLogic.computeChronologicalSortOrders(
            existingDayEntries = listOf(legacyBackdatedEntry, existingTargetEntry),
            newEntry = duplicated
        )
        val grouped = groupByDay(synced).single().entries
        // 18:45 duplicated meal is placed above 08:15 (existingTargetEntry)
        val idxDuplicated = grouped.indexOfFirst { it.id == duplicated.id }
        val idxMorning = grouped.indexOfFirst { it.id == existingTargetEntry.id }
        assertTrue(idxDuplicated < idxMorning)
    }

    @Test
    fun testBuildShareTextFormatting() {
        val both = buildShareText(
            recognizedMeal = "250 g Gemüse-Curry mit Reis",
            summary = "Sehr ausgewogenes Mittagessen."
        )
        assertEquals(
            "🍽️ 250 g Gemüse-Curry mit Reis\n\n🧠 Sehr ausgewogenes Mittagessen.",
            both
        )

        val onlyDetected = buildShareText(
            recognizedMeal = "100 g Haferflocken",
            summary = "   "
        )
        assertEquals("🍽️ 100 g Haferflocken", onlyDetected)

        val onlySummary = buildShareText(
            recognizedMeal = "",
            summary = "Gute Wahl."
        )
        assertEquals("🧠 Gute Wahl.", onlySummary)

        val neither = buildShareText(recognizedMeal = "", summary = "")
        assertEquals("", neither)
    }

    @Test
    fun testWeekFazitDueOnSundayEveningAndMonday() {
        val lastWeekKey = "20261102" // KW 45: Mon 02.11.2026 .. Sun 08.11.2026
        val newWeekKey = "20261109"  // KW 46: Mon 09.11.2026 .. Sun 15.11.2026

        assertEquals(45, calendarWeekOfKey(lastWeekKey))
        assertEquals(46, calendarWeekOfKey(newWeekKey))

        // On Sunday 08.11.2026 before 18:00 -> NOT due yet
        assertFalse(isWeekFazitDue(lastWeekKey, LocalDate.of(2026, 11, 8), 17))
        // On Sunday 08.11.2026 at 18:00 -> DUE
        assertTrue(isWeekFazitDue(lastWeekKey, LocalDate.of(2026, 11, 8), 18))

        // On Monday 09.11.2026 at 08:00 in the morning:
        // Last week (KW 45) MUST still be due and visible!
        assertTrue(isWeekFazitDue(lastWeekKey, LocalDate.of(2026, 11, 9), 8))
        // The new week (KW 46) that just started on Monday is NOT due yet
        assertFalse(isWeekFazitDue(newWeekKey, LocalDate.of(2026, 11, 9), 8))

        val saturdayEntry = HistoryEntry(
            id = 1L,
            dateMillis = LocalDate.of(2026, 11, 7).atTime(13, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "Linsensuppe"
        )
        val mondayMorningEntry = HistoryEntry(
            id = 2L,
            dateMillis = LocalDate.of(2026, 11, 9).atTime(8, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "Müsli"
        )

        // On Monday morning, latestDueWeekKey picks last week ("20261102"), even if Monday already has an entry
        val dueKeyOnMonday = latestDueWeekKey(
            entries = listOf(saturdayEntry, mondayMorningEntry),
            nowDate = LocalDate.of(2026, 11, 9),
            nowHour = 9
        )
        assertEquals(lastWeekKey, dueKeyOnMonday)

        // Auto-trigger on Monday for the completed week if not generated on Sunday evening
        DayFazitSession.attemptedWeekly.clear()
        val generatedKeys = mutableListOf<String>()
        handleAutoWeekFazitForKey(
            weekKey = dueKeyOnMonday!!,
            weekFazitMap = emptyMap(),
            loadingWeeks = emptySet(),
            onGenerateWeekFazit = { generatedKeys.add(it) }
        )
        assertEquals(listOf(lastWeekKey), generatedKeys)
    }

    @Test
    fun testDateTimeFormattingHelpers() {
        val dt = LocalDate.of(2026, 11, 19).atTime(14, 30)
        val millis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        assertEquals("14:30", formatTime(millis))
        assertEquals("19.11.2026", formatDate(millis))
        assertEquals("19.11.2026 14:30", formatDateTime(millis))
    }

    @Test
    fun testBuildHistoryUiItemsPureConstructionAndOrdering() {
        val today = LocalDate.of(2026, 11, 9) // Monday KW 46
        val sundayLastWeek = LocalDate.of(2026, 11, 8) // Sunday KW 45

        // Empty entries: produces only today's DayHeaderItem (empty)
        val emptyUiItems = buildHistoryUiItems(emptyList(), today = today, nowHour = 10)
        assertEquals(1, emptyUiItems.size)
        val emptyHeader = emptyUiItems[0] as HistoryUiItem.DayHeaderItem
        assertEquals(today, emptyHeader.day)
        assertFalse(emptyHeader.hasEntries)
        assertEquals("day-20261109", emptyHeader.key)

        // Entries across last week (KW 45) and today (KW 46)
        val eSun1 = HistoryEntry(
            id = 101L,
            dateMillis = sundayLastWeek.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "Sonntagsbraten"
        )
        val eSun2 = HistoryEntry(
            id = 102L,
            dateMillis = sundayLastWeek.atTime(19, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "Abendbrot"
        )
        val eMon1 = HistoryEntry(
            id = 201L,
            dateMillis = today.atTime(8, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "Müsli"
        )

        val items = buildHistoryUiItems(listOf(eSun1, eSun2, eMon1), today = today, nowHour = 10)

        // Expected sequence:
        // 1. Today DayHeaderItem (Monday KW 46 - not due yet for week fazit)
        // 2. Monday MealEntryItem (eMon1)
        // 3. KW 45 WeekFazitItem (due because KW 45 ended and we are on Monday)
        // 4. Sunday DayHeaderItem (Sunday KW 45)
        // 5. Sunday MealEntryItem (eSun2 - 19:00 sorted above 12:00)
        // 6. Sunday MealEntryItem (eSun1 - 12:00)

        assertEquals(6, items.size)
        assertTrue(items[0] is HistoryUiItem.DayHeaderItem)
        assertEquals(today, (items[0] as HistoryUiItem.DayHeaderItem).day)

        assertTrue(items[1] is HistoryUiItem.MealEntryItem)
        assertEquals(201L, (items[1] as HistoryUiItem.MealEntryItem).entry.id)
        assertEquals(0, (items[1] as HistoryUiItem.MealEntryItem).indexOnDay)
        assertEquals(1, (items[1] as HistoryUiItem.MealEntryItem).totalCountOnDay)

        assertTrue(items[2] is HistoryUiItem.WeekFazitItem)
        assertEquals("20261102", (items[2] as HistoryUiItem.WeekFazitItem).weekKey)
        assertEquals(45, (items[2] as HistoryUiItem.WeekFazitItem).calendarWeek)
        assertEquals("week-20261102", items[2].key)

        assertTrue(items[3] is HistoryUiItem.DayHeaderItem)
        assertEquals(sundayLastWeek, (items[3] as HistoryUiItem.DayHeaderItem).day)
        assertTrue((items[3] as HistoryUiItem.DayHeaderItem).hasEntries)

        assertTrue(items[4] is HistoryUiItem.MealEntryItem)
        assertEquals(102L, (items[4] as HistoryUiItem.MealEntryItem).entry.id)
        assertEquals(0, (items[4] as HistoryUiItem.MealEntryItem).indexOnDay)
        assertEquals(2, (items[4] as HistoryUiItem.MealEntryItem).totalCountOnDay)

        assertTrue(items[5] is HistoryUiItem.MealEntryItem)
        assertEquals(101L, (items[5] as HistoryUiItem.MealEntryItem).entry.id)
        assertEquals(1, (items[5] as HistoryUiItem.MealEntryItem).indexOnDay)
        assertEquals(2, (items[5] as HistoryUiItem.MealEntryItem).totalCountOnDay)
    }

    @Test
    fun testBuildHistoryUiItemsPrecalculatesStaleFazit() {
        val today = LocalDate.of(2026, 11, 9)
        val entry1 = HistoryEntry(
            id = 1L,
            dateMillis = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            recognizedMeal = "Salat"
        )
        val fpMatching = dayFingerprint(listOf(entry1))

        val matchingFazit = DayFazit(
            dayKey = "20261109",
            text = "Guter Tag",
            entryIds = listOf(1L),
            fingerprint = fpMatching
        )

        val itemsMatching = buildHistoryUiItems(
            entries = listOf(entry1),
            today = today,
            fazitMap = mapOf("20261109" to matchingFazit)
        )
        val headerMatching = itemsMatching.filterIsInstance<HistoryUiItem.DayHeaderItem>().first()
        assertFalse("Matching fazit should not be stale", headerMatching.staleFazit)

        val staleFazitWrongIds = DayFazit(
            dayKey = "20261109",
            text = "Guter Tag",
            entryIds = listOf(999L),
            fingerprint = fpMatching
        )
        val itemsStaleIds = buildHistoryUiItems(
            entries = listOf(entry1),
            today = today,
            fazitMap = mapOf("20261109" to staleFazitWrongIds)
        )
        val headerStaleIds = itemsStaleIds.filterIsInstance<HistoryUiItem.DayHeaderItem>().first()
        assertTrue("Fazit with mismatched entry IDs must be marked stale", headerStaleIds.staleFazit)

        val staleFazitWrongFp = DayFazit(
            dayKey = "20261109",
            text = "Guter Tag",
            entryIds = listOf(1L),
            fingerprint = "different-hash"
        )
        val itemsStaleFp = buildHistoryUiItems(
            entries = listOf(entry1),
            today = today,
            fazitMap = mapOf("20261109" to staleFazitWrongFp)
        )
        val headerStaleFp = itemsStaleFp.filterIsInstance<HistoryUiItem.DayHeaderItem>().first()
        assertTrue("Fazit with mismatched fingerprint must be marked stale", headerStaleFp.staleFazit)
    }

    @Test
    fun testUnratedEntriesExcludedFromFazitAndFingerprint() {
        val today = LocalDate.of(2026, 11, 9)
        val millis = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ratedEntry = HistoryEntry(
            id = 1L,
            dateMillis = millis,
            recognizedMeal = "Vollkornbrot",
            goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "Gut"))
        )
        val unratedEntry = HistoryEntry(
            id = 2L,
            dateMillis = millis + 1000L,
            note = "Nur Foto ohne Internet",
            recognizedMeal = "",
            goalResults = emptyList(),
            summary = ""
        )

        assertTrue(ratedEntry.isRated)
        assertFalse(unratedEntry.isRated)

        // Fingerprint must ignore unrated entries
        val fpOnlyRated = dayFingerprint(listOf(ratedEntry))
        val fpWithUnrated = dayFingerprint(listOf(ratedEntry, unratedEntry))
        assertEquals("Unrated entry must not affect dayFingerprint", fpOnlyRated, fpWithUnrated)

        // Existing day fazit must NOT become outdated when an unrated entry is added
        val fazit = DayFazit(
            dayKey = "20261109",
            rating = TrafficLight.GREEN,
            text = "Super Tag",
            entryIds = listOf(1L),
            fingerprint = fpOnlyRated
        )
        assertFalse(isFazitOutdated(fazit, listOf(ratedEntry, unratedEntry)))

        // A day with ONLY unrated entries has hasEntries=false on DayHeaderItem (no day fazit chip)
        // but still renders the MealEntryItem in the history list
        val itemsOnlyUnrated = buildHistoryUiItems(
            entries = listOf(unratedEntry),
            today = today
        )
        val dayHeader = itemsOnlyUnrated.filterIsInstance<HistoryUiItem.DayHeaderItem>().first()
        assertFalse("Day with only unrated entries should not offer day fazit", dayHeader.hasEntries)
        assertTrue(dayHeader.entries.isEmpty())
        val mealItems = itemsOnlyUnrated.filterIsInstance<HistoryUiItem.MealEntryItem>()
        assertEquals(1, mealItems.size)
        assertEquals(2L, mealItems.first().entry.id)
    }
}
