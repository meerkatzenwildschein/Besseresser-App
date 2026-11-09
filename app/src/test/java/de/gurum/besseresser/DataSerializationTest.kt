package de.gurum.besseresser

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSerializationTest {

    @Test
    fun testTrafficLightFromString() {
        assertEquals(TrafficLight.GREEN, TrafficLight.fromString("gruen"))
        assertEquals(TrafficLight.GREEN, TrafficLight.fromString("grün"))
        assertEquals(TrafficLight.GREEN, TrafficLight.fromString("Green"))
        assertEquals(TrafficLight.GREEN, TrafficLight.fromString("🟢"))
        assertEquals(TrafficLight.GREEN, TrafficLight.fromString("✅"))

        assertEquals(TrafficLight.YELLOW, TrafficLight.fromString("gelb"))
        assertEquals(TrafficLight.YELLOW, TrafficLight.fromString("yellow"))
        assertEquals(TrafficLight.YELLOW, TrafficLight.fromString("orange"))
        assertEquals(TrafficLight.YELLOW, TrafficLight.fromString("amber"))
        assertEquals(TrafficLight.YELLOW, TrafficLight.fromString("🟡"))
        assertEquals(TrafficLight.YELLOW, TrafficLight.fromString("🟠"))

        assertEquals(TrafficLight.RED, TrafficLight.fromString("rot"))
        assertEquals(TrafficLight.RED, TrafficLight.fromString("red"))
        assertEquals(TrafficLight.RED, TrafficLight.fromString("🔴"))

        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString("unbekannt"))
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString(""))
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString(null))
        assertEquals(TrafficLight.UNKNOWN, TrafficLight.fromString("xyz random"))
    }

    @Test
    fun testGenderFromString() {
        assertEquals(Gender.MALE, Gender.fromString("male"))
        assertEquals(Gender.MALE, Gender.fromString("MALE"))
        assertEquals(Gender.FEMALE, Gender.fromString("female"))
        assertEquals(Gender.DIVERSE, Gender.fromString("diverse"))
        assertEquals(Gender.NONE, Gender.fromString(""))
        assertEquals(Gender.NONE, Gender.fromString(null))
        assertEquals(Gender.NONE, Gender.fromString("other"))
    }

    @Test
    fun testGoalResultSerialization() {
        val result = GoalResult(
            goal = "2l Wasser trinken",
            ampel = TrafficLight.GREEN,
            begruendung = "Genug Wasser getrunken."
        )

        val json = AppJson.encodeToString(result)
        assertTrue(json.contains("\"ziel\":\"2l Wasser trinken\""))
        assertTrue(json.contains("\"ampel\":\"gruen\""))
        assertTrue(json.contains("\"begruendung\":\"Genug Wasser getrunken.\""))

        val decoded = AppJson.decodeFromString<GoalResult>(json)
        assertEquals(result, decoded)
    }

    @Test
    fun testGoalResultDeserializationTolerant() {
        // KI returns english or alternate words in ampel field
        val json = """{"ziel":"Gemüse","ampel":"green","begruendung":"Viel Salat gegessen"}"""
        val decoded = AppJson.decodeFromString<GoalResult>(json)
        assertEquals("Gemüse", decoded.goal)
        assertEquals(TrafficLight.GREEN, decoded.ampel)
        assertEquals("Viel Salat gegessen", decoded.begruendung)
    }

    @Test
    fun testHistoryEntrySerializationAndScore() {
        val entry = HistoryEntry(
            id = 123456789L,
            dateMillis = 1730000000000L,
            photoFile = "123456789.jpg",
            goalResults = listOf(
                GoalResult("Ziel 1", TrafficLight.GREEN, "Top"),
                GoalResult("Ziel 2", TrafficLight.YELLOW, "Mittel"),
                GoalResult("Ziel 3", TrafficLight.RED, "Nicht erreicht"),
                GoalResult("Ziel 4", TrafficLight.UNKNOWN, "Nicht bewertbar")
            ),
            summary = "Gesamteindruck gut",
            note = "Mittagessen",
            erkannt = "Pasta mit Tomatensoße",
            backdated = false
        )

        // Score: known are GREEN (100), YELLOW (50), RED (0) -> (100 + 50 + 0) / 3 = 50
        assertEquals(50, entry.score)

        val json = AppJson.encodeToString(entry)
        assertTrue(json.contains("\"id\":123456789"))
        assertTrue(json.contains("\"datum\":1730000000000"))
        assertTrue(json.contains("\"foto\":\"123456789.jpg\""))
        assertTrue(json.contains("\"zusatz\":\"Gesamteindruck gut\""))
        assertTrue(json.contains("\"anmerkung\":\"Mittagessen\""))
        assertTrue(json.contains("\"erkannt\":\"Pasta mit Tomatensoße\""))

        val decoded = AppJson.decodeFromString<HistoryEntry>(json)
        assertEquals(entry, decoded)
        assertEquals(50, decoded.score)
    }

    @Test
    fun testHistoryEntryAllUnknownScore() {
        val entry = HistoryEntry(
            id = 1L,
            dateMillis = 1000L,
            photoFile = "",
            goalResults = listOf(
                GoalResult("Ziel 1", TrafficLight.UNKNOWN, "Unbekannt")
            ),
            summary = ""
        )
        assertEquals(-1, entry.score)
    }

    @Test
    fun testDayFazitSerialization() {
        val fazit = DayFazit(
            dayKey = "20261106",
            ampel = TrafficLight.GREEN,
            text = "Ein sehr gesunder Tag!",
            entryIds = listOf(101L, 102L),
            createdAt = 1730900000000L,
            fingerprint = "abc123hash"
        )

        val json = AppJson.encodeToString(fazit)
        assertTrue(json.contains("\"tag\":\"20261106\""))
        assertTrue(json.contains("\"ampel\":\"gruen\""))
        assertTrue(json.contains("\"text\":\"Ein sehr gesunder Tag!\""))
        assertTrue(json.contains("\"eintrage\":[101,102]"))
        assertTrue(json.contains("\"erstellt\":1730900000000"))
        assertTrue(json.contains("\"fp\":\"abc123hash\""))

        val decoded = AppJson.decodeFromString<DayFazit>(json)
        assertEquals(fazit, decoded)
    }

    @Test
    fun testRecentNoteSerialization() {
        val notes = listOf(
            RecentNote("Müsli mit Beeren", starred = true),
            RecentNote("Kaffee schwarz", starred = false)
        )

        val json = AppJson.encodeToString(notes)
        assertTrue(json.contains("\"t\":\"Müsli mit Beeren\""))
        assertTrue(json.contains("\"s\":true"))

        val decoded = AppJson.decodeFromString<List<RecentNote>>(json)
        assertEquals(notes, decoded)
    }

    @Test
    fun testRecentNotesParsingRobustness() {
        // 1. Legacy format: plain string array
        val legacy = """["Apfel", "Birne"]"""
        val parsedLegacy = RecentNotesStore.parseRecentNotes(legacy)
        assertEquals(2, parsedLegacy.size)
        assertEquals("Apfel", parsedLegacy[0].text)
        assertFalse(parsedLegacy[0].starred)

        // 2. Integer boolean: 1 = true, 0 = false
        val intBool = """[{"t":"Suppe","s":1},{"t":"Brot","s":0}]"""
        val parsedInt = RecentNotesStore.parseRecentNotes(intBool)
        assertEquals(2, parsedInt.size)
        assertEquals("Suppe", parsedInt[0].text)
        assertTrue(parsedInt[0].starred)
        assertEquals("Brot", parsedInt[1].text)
        assertFalse(parsedInt[1].starred)

        // 3. String boolean: "true"
        val strBool = """[{"t":"Tee","s":"true"}]"""
        val parsedStr = RecentNotesStore.parseRecentNotes(strBool)
        assertEquals(1, parsedStr.size)
        assertTrue(parsedStr[0].starred)

        // 4. Full property names: text / starred
        val fullNames = """[{"text":"Salat","starred":true}]"""
        val parsedFull = RecentNotesStore.parseRecentNotes(fullNames)
        assertEquals(1, parsedFull.size)
        assertEquals("Salat", parsedFull[0].text)
        assertTrue(parsedFull[0].starred)
    }

    @Test
    fun testProfileModel() {
        val emptyProfile = Profile()
        assertTrue(emptyProfile.isEmpty)
        assertEquals(Gender.NONE, emptyProfile.gender)

        val filledProfile = Profile(
            birthDate = "1990-05-15",
            weightKg = 75.5,
            heightCm = 180,
            notes = "Keine Allergien",
            gender = Gender.MALE
        )
        assertFalse(filledProfile.isEmpty)
        assertEquals(Gender.MALE, filledProfile.gender)
    }

    @Test
    fun testAppBackupRoundTripAndBase64Photos() {
        val samplePhotoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        val encodedPhoto = BackupStore.encodePhotoBase64(samplePhotoBytes)

        val backup = AppBackup(
            version = 1,
            exportedAtMillis = 1730900000000L,
            settings = Settings(
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = "sk-or-v1-secret-key",
                model = "google/gemini-2.5-flash",
                goals = listOf("Weniger Zucker", "Mehr Gemüse"),
                goalsWereSplit = true
            ),
            profile = Profile(
                birthDate = "1985-03-10",
                weightKg = 78.5,
                heightCm = 182,
                notes = "Bluthochdruck",
                gender = Gender.MALE
            ),
            recentNotes = listOf(
                RecentNote("Haferflocken mit Beeren", starred = true),
                RecentNote("Espresso", starred = false)
            ),
            historyEntries = listOf(
                BackupHistoryEntry(
                    entry = HistoryEntry(
                        id = 1001L,
                        dateMillis = 1730890000000L,
                        photoFile = "1001.jpg",
                        goalResults = listOf(
                            GoalResult("Weniger Zucker", TrafficLight.GREEN, "Kein zugesetzter Zucker")
                        ),
                        summary = "Gesundes Frühstück",
                        note = "Haferflocken mit Beeren",
                        erkannt = "250 g Haferflocken mit Beeren",
                        backdated = false
                    ),
                    photoBase64 = encodedPhoto
                )
            ),
            dayFazits = listOf(
                DayFazit(
                    dayKey = "20261106",
                    ampel = TrafficLight.GREEN,
                    text = "Sehr guter Tag",
                    entryIds = listOf(1001L),
                    createdAt = 1730895000000L,
                    fingerprint = "fp_day"
                )
            ),
            weekFazits = listOf(
                DayFazit(
                    dayKey = "20261102",
                    ampel = TrafficLight.YELLOW,
                    text = "Solide Woche",
                    entryIds = listOf(1001L),
                    createdAt = 1730896000000L,
                    fingerprint = "fp_week"
                )
            )
        )

        val json = BackupStore.serializeBackup(backup)
        assertTrue(json.contains("sk-or-v1-secret-key"))
        assertTrue(json.contains(encodedPhoto))

        val restored = BackupStore.deserializeBackup(json)
        assertEquals(backup, restored)

        val decodedBytes = BackupStore.decodePhotoBase64(restored.historyEntries.first().photoBase64!!)
        org.junit.Assert.assertArrayEquals(samplePhotoBytes, decodedBytes)
    }

    @Test
    fun testBackupMergeOverwritesDuplicates() {
        val existingSettings = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "old-key",
            model = "gpt-4o-mini",
            goals = listOf("Altes Ziel"),
            goalsWereSplit = false
        )
        val importedSettings = Settings(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "new-imported-key",
            model = "google/gemini-2.5-flash",
            goals = listOf("Neues Ziel 1", "Neues Ziel 2"),
            goalsWereSplit = true
        )
        val mergedSettings = BackupStore.mergeSettings(existingSettings, importedSettings)
        assertEquals(importedSettings, mergedSettings)

        val existingProfile = Profile(birthDate = "1980-01-01", weightKg = 90.0, heightCm = 175, notes = "Alt", gender = Gender.FEMALE)
        val importedProfile = Profile(birthDate = "1990-05-20", weightKg = 74.0, heightCm = 180, notes = "Neu", gender = Gender.MALE)
        val mergedProfile = BackupStore.mergeProfile(existingProfile, importedProfile)
        assertEquals(importedProfile, mergedProfile)

        val existingNotes = listOf(
            RecentNote("Salat", starred = false),
            RecentNote("Nur lokal", starred = true)
        )
        val importedNotes = listOf(
            RecentNote("Salat", starred = true),
            RecentNote("Müsli", starred = false)
        )
        val mergedNotes = BackupStore.mergeRecentNotes(existingNotes, importedNotes)
        assertEquals(3, mergedNotes.size)
        // Imported "Salat" overwrote existing unstarred "Salat" with starred = true
        assertEquals(RecentNote("Salat", starred = true), mergedNotes[0])
        assertEquals(RecentNote("Müsli", starred = false), mergedNotes[1])
        assertEquals(RecentNote("Nur lokal", starred = true), mergedNotes[2])
    }

    @Test
    fun testThemeModeAndLanguageParsingAndSerialization() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromString(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromString("system"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromString("light"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromString("DARK"))

        assertEquals(AppLanguage.DE, AppLanguage.resolve("de", "en"))
        assertEquals(AppLanguage.EN, AppLanguage.resolve("en", "de"))
        assertEquals(AppLanguage.DE, AppLanguage.resolve(null, "de-DE"))
        assertEquals(AppLanguage.EN, AppLanguage.resolve(null, "en-US"))
        assertEquals(AppLanguage.EN, AppLanguage.resolve(null, "fr"))

        val backup = AppBackup(
            themeMode = AppThemeMode.DARK,
            language = AppLanguage.DE
        )
        val json = BackupStore.serializeBackup(backup)
        val restored = BackupStore.deserializeBackup(json)
        assertEquals(AppThemeMode.DARK, restored.themeMode)
        assertEquals(AppLanguage.DE, restored.language)
    }
}
