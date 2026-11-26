package de.gurum.besseresser

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import de.gurum.besseresser.data.AppBackup
import de.gurum.besseresser.model.AppJson
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode
import de.gurum.besseresser.data.BackupHistoryEntry
import de.gurum.besseresser.data.BackupLogic
import de.gurum.besseresser.model.DayFazit
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.Gender
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.RecentNotesLogic
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.WeightedGoal
import de.gurum.besseresser.model.toGoalTextList
import de.gurum.besseresser.model.toWeightedGoals
import de.gurum.besseresser.data.SettingsRepository

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
            rating = TrafficLight.GREEN,
            reason = "Genug Wasser getrunken."
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
        assertEquals(TrafficLight.GREEN, decoded.rating)
        assertEquals("Viel Salat gegessen", decoded.reason)
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
            recognizedMeal = "Pasta mit Tomatensoße",
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
            rating = TrafficLight.GREEN,
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
        val sampleEval = Evaluation(
            goalResults = listOf(GoalResult("Weniger Zucker", TrafficLight.GREEN, "Kein Zuckerzusatz")),
            summary = "Top Frühstück",
            recognizedMeal = "Müsli mit Beeren"
        )
        val notes = listOf(
            RecentNote("Müsli mit Beeren", starred = true, evaluation = sampleEval),
            RecentNote("Kaffee schwarz", starred = false)
        )

        val json = AppJson.encodeToString(notes)
        assertTrue(json.contains("\"t\":\"Müsli mit Beeren\""))
        assertTrue(json.contains("\"s\":true"))
        assertTrue(json.contains("\"ev\":"))
        assertTrue(json.contains("\"Top Frühstück\""))

        val decoded = AppJson.decodeFromString<List<RecentNote>>(json)
        assertEquals(notes, decoded)
        assertEquals(sampleEval, decoded[0].evaluation)
    }

    @Test
    fun testRecentNotesCachingEvictionAndMatching() {
        val goals = listOf("Ziel 1", "Ziel 2")
        fun makeEval(name: String) = Evaluation(
            goalResults = listOf(
                GoalResult("Ziel 1", TrafficLight.GREEN, "Ok $name"),
                GoalResult("Ziel 2", TrafficLight.YELLOW, "Mittel $name")
            ),
            summary = "Fazit $name",
            recognizedMeal = "recognizedMeal $name"
        )

        var list = emptyList<RecentNote>()
        // Push 6 unstarred notes with evaluations -> oldest ("Essen 1") and its evaluation JSON must be removed!
        for (i in 1..6) {
            list = RecentNotesLogic.pushNote(list, "Essen $i", makeEval("Essen $i"))
        }

        assertEquals(5, list.size)
        assertFalse(list.any { it.text == "Essen 1" })
        val serialized = AppJson.encodeToString(list)
        assertFalse(serialized.contains("recognizedMeal Essen 1"))
        assertTrue(serialized.contains("recognizedMeal Essen 6"))

        // Pushing an existing note without passing evaluation preserves its cached evaluation
        list = RecentNotesLogic.pushNote(list, "Essen 2")
        assertEquals("Essen 2", list.first().text)
        assertEquals("recognizedMeal Essen 2", list.first().evaluation?.recognizedMeal)

        // Exact match returns cached evaluation when goals match
        val matched = RecentNotesLogic.findMatchingEvaluation(list, "  Essen 2  ", goals)
        assertEquals(makeEval("Essen 2"), matched)

        // Modified text or different casing does not match (exact match required)
        assertEquals(null, RecentNotesLogic.findMatchingEvaluation(list, "Essen 2 extra", goals))
        assertEquals(null, RecentNotesLogic.findMatchingEvaluation(list, "essen 2", goals))

        // Outdated/changed goals -> returns null so AI is queried again
        assertEquals(null, RecentNotesLogic.findMatchingEvaluation(list, "Essen 2", listOf("Ziel 1", "Neues Ziel")))
        assertEquals(null, RecentNotesLogic.findMatchingEvaluation(list, "Essen 2", listOf("Ziel 1")))

        // Updating evaluation in-place (e.g. after recognizedMeal correction) keeps order and updates evaluation
        val correctedEval = makeEval("Essen 2").copy(recognizedMeal = "Korrigiertes Essen 2")
        list = RecentNotesLogic.updateEvaluationInList(list, "Essen 2", correctedEval)
        assertEquals("Korrigiertes Essen 2", RecentNotesLogic.findMatchingEvaluation(list, "Essen 2", goals)?.recognizedMeal)
    }

    @Test
    fun testRecentNotesParsingRobustness() {
        // 1. Legacy format: plain string array
        val legacy = """["Apfel", "Birne"]"""
        val parsedLegacy = RecentNotesLogic.parseRecentNotes(legacy)
        assertEquals(2, parsedLegacy.size)
        assertEquals("Apfel", parsedLegacy[0].text)
        assertFalse(parsedLegacy[0].starred)

        // 2. Integer boolean: 1 = true, 0 = false
        val intBool = """[{"t":"Suppe","s":1},{"t":"Brot","s":0}]"""
        val parsedInt = RecentNotesLogic.parseRecentNotes(intBool)
        assertEquals(2, parsedInt.size)
        assertEquals("Suppe", parsedInt[0].text)
        assertTrue(parsedInt[0].starred)
        assertEquals("Brot", parsedInt[1].text)
        assertFalse(parsedInt[1].starred)

        // 3. String boolean: "true"
        val strBool = """[{"t":"Tee","s":"true"}]"""
        val parsedStr = RecentNotesLogic.parseRecentNotes(strBool)
        assertEquals(1, parsedStr.size)
        assertTrue(parsedStr[0].starred)

        // 4. Full property names: text / starred
        val fullNames = """[{"text":"Salat","starred":true}]"""
        val parsedFull = RecentNotesLogic.parseRecentNotes(fullNames)
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
        val encodedPhoto = BackupLogic.encodePhotoBase64(samplePhotoBytes)

        val backup = AppBackup(
            version = 1,
            exportedAtMillis = 1730900000000L,
            settings = Settings(
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = "sk-or-v1-secret-key",
                model = "google/gemini-2.5-flash",
                goals = listOf("Weniger Zucker", "Mehr Gemüse").toWeightedGoals(),
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
                        recognizedMeal = "250 g Haferflocken mit Beeren",
                        backdated = false
                    ),
                    photoBase64 = encodedPhoto
                )
            ),
            dayFazits = listOf(
                DayFazit(
                    dayKey = "20261106",
                    rating = TrafficLight.GREEN,
                    text = "Sehr guter Tag",
                    entryIds = listOf(1001L),
                    createdAt = 1730895000000L,
                    fingerprint = "fp_day"
                )
            ),
            weekFazits = listOf(
                DayFazit(
                    dayKey = "20261102",
                    rating = TrafficLight.YELLOW,
                    text = "Solide Woche",
                    entryIds = listOf(1001L),
                    createdAt = 1730896000000L,
                    fingerprint = "fp_week"
                )
            )
        )

        val json = BackupLogic.serializeBackup(backup)
        assertTrue(json.contains("sk-or-v1-secret-key"))
        assertTrue(json.contains(encodedPhoto))

        val restored = BackupLogic.deserializeBackup(json)
        assertEquals(backup, restored)

        val decodedBytes = BackupLogic.decodePhotoBase64(restored.historyEntries.first().photoBase64!!)
        org.junit.Assert.assertArrayEquals(samplePhotoBytes, decodedBytes)
    }

    @Test
    fun testWeightedGoalModelAndClamping() {
        assertEquals(1, WeightedGoal.MIN_WEIGHT)
        assertEquals(5, WeightedGoal.MAX_WEIGHT)
        assertEquals(3, WeightedGoal.DEFAULT_WEIGHT)

        val defaultGoal = WeightedGoal("Trink mehr Wasser")
        assertEquals("Trink mehr Wasser", defaultGoal.text)
        assertEquals(3, defaultGoal.weight)

        val normalGoal = WeightedGoal("Normal", 4)
        assertEquals(4, normalGoal.weight)

        // Validation test
        try {
            WeightedGoal("Invalid", 0)
            org.junit.Assert.fail("Expected IllegalArgumentException for weight 0")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        try {
            WeightedGoal("Invalid", 6)
            org.junit.Assert.fail("Expected IllegalArgumentException for weight 6")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        val json = AppJson.encodeToString(WeightedGoal.serializer(), normalGoal)
        val decoded = AppJson.decodeFromString(WeightedGoal.serializer(), json)
        assertEquals(normalGoal, decoded)
    }

    @Test
    fun testWeightedGoalConvertersAndSettings() {
        val strings = listOf("Ziel A", "Ziel B", "Ziel C")
        val weighted = strings.toWeightedGoals(4)
        assertEquals(3, weighted.size)
        assertEquals("Ziel A", weighted[0].text)
        assertEquals(4, weighted[0].weight)

        val texts = weighted.toGoalTextList()
        assertEquals(strings, texts)

        val settings = Settings(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "key",
            model = "gemini",
            goals = listOf(WeightedGoal("Z1", 1), WeightedGoal("Z2", 5)),
            goalsWereSplit = true
        )
        assertEquals(listOf("Z1", "Z2"), settings.toGoalTextList())

        // Test JSON roundtrip of Settings with WeightedGoalSerializer
        val json = AppJson.encodeToString(Settings.serializer(), settings)
        val decoded = AppJson.decodeFromString(Settings.serializer(), json)
        assertEquals(settings, decoded)
        assertEquals(1, decoded.goals[0].weight)
        assertEquals(5, decoded.goals[1].weight)
    }

    @Test
    fun testSettingsRepositoryEncodingAndDecoding() {
        val goals = listOf(
            WeightedGoal("Weniger Zucker", 5),
            WeightedGoal("Mehr Protein: Eier & Quark", 4),
            WeightedGoal("3:::Drei Doppelpunkte im Text\nmit Zeilenumbruch", 2),
            WeightedGoal("Standardziel", 3)
        )

        val encoded = SettingsRepository.encodeGoalsToString(goals)
        assertTrue(encoded.startsWith("["))
        val decoded = SettingsRepository.decodeGoalsFromString(encoded)

        assertEquals(goals.size, decoded.size)
        assertEquals(goals, decoded)

        // Test decoding legacy unweighted plain text format
        val legacyText = "Kein Fastfood\nMindestens 2 Liter Wasser\n"
        val decodedLegacy = SettingsRepository.decodeGoalsFromString(legacyText)
        assertEquals(2, decodedLegacy.size)
        assertEquals("Kein Fastfood", decodedLegacy[0].text)
        assertEquals(3, decodedLegacy[0].weight)
        assertEquals("Mindestens 2 Liter Wasser", decodedLegacy[1].text)
        assertEquals(3, decodedLegacy[1].weight)

        // Test legacy text format (treated literally with weight 3)
        val legacyTextWithColons = "Altes Ziel ohne Gewicht\nVerhältnis 1:::2\nNeues Ziel: Protein\n"
        val decodedColons = SettingsRepository.decodeGoalsFromString(legacyTextWithColons)
        assertEquals(3, decodedColons.size)
        assertEquals(WeightedGoal("Altes Ziel ohne Gewicht", 3), decodedColons[0])
        assertEquals(WeightedGoal("Verhältnis 1:::2", 3), decodedColons[1])
        assertEquals(WeightedGoal("Neues Ziel: Protein", 3), decodedColons[2])

        // Empty and blank strings handling
        assertTrue(SettingsRepository.decodeGoalsFromString("").isEmpty())
        assertTrue(SettingsRepository.decodeGoalsFromString("   \n\n  ").isEmpty())
        assertTrue(SettingsRepository.decodeGoalsFromString("[]").isEmpty())
        assertTrue(SettingsRepository.decodeGoalsFromString("[{\"text\":\"\"}]").isEmpty())
    }

    @Test
    fun testWeightedGoalSerializerEdgeCasesAndMalformedJson() {
        val jsonWithOutOfRange = """[{"text":"High","weight":10},{"text":"Low","weight":-5},{"text":"Normal","weight":3}]"""
        val decoded = AppJson.decodeFromString<List<WeightedGoal>>(jsonWithOutOfRange)
        assertEquals(3, decoded.size)
        assertEquals(5, decoded[0].weight)
        assertEquals(1, decoded[1].weight)
        assertEquals(3, decoded[2].weight)

        val jsonWithAliases = """[{"ziel":"AliasZiel","gewicht":4}]"""
        val decodedAliases = AppJson.decodeFromString<List<WeightedGoal>>(jsonWithAliases)
        assertEquals(1, decodedAliases.size)
        assertEquals("AliasZiel", decodedAliases[0].text)
        assertEquals(4, decodedAliases[0].weight)

        val jsonWithNullAndMissing = """[null, {"weight":4}, {"text":"Valid","weight":null}]"""
        val decodedNulls = AppJson.decodeFromString<List<WeightedGoal>>(jsonWithNullAndMissing)
        assertEquals(3, decodedNulls.size)
        assertEquals("", decodedNulls[0].text)
        assertEquals("", decodedNulls[1].text)
        assertEquals(4, decodedNulls[1].weight)
        assertEquals("Valid", decodedNulls[2].text)
        assertEquals(3, decodedNulls[2].weight)
    }

    @Test
    fun testAppBackupLegacyGoalsBackwardCompatibility() {
        // Legacy backup JSON where goals was List<String> instead of List<WeightedGoal>
        val legacyJson = """
            {
                "version": 1,
                "exportiertAm": 1730900000000,
                "einstellungen": {
                    "baseUrl": "https://api.openai.com/v1",
                    "apiKey": "test-key",
                    "model": "gpt-4o",
                    "goals": [
                        "Kein Zucker",
                        "Mehr Gemüse"
                    ],
                    "goalsWereSplit": true
                },
                "profil": {},
                "notizen": [],
                "verlauf": [],
                "tagesfazits": [],
                "wochenfazits": []
            }
        """.trimIndent()

        val restored = BackupLogic.deserializeBackup(legacyJson)
        assertEquals(2, restored.settings.goals.size)
        assertEquals(WeightedGoal("Kein Zucker", 3), restored.settings.goals[0])
        assertEquals(WeightedGoal("Mehr Gemüse", 3), restored.settings.goals[1])
    }

    @Test
    fun testBackupMergeOverwritesDuplicates() {
        val existingSettings = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "old-key",
            model = "gpt-4o-mini",
            goals = listOf("Altes Ziel").toWeightedGoals(),
            goalsWereSplit = false
        )
        val importedSettings = Settings(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "new-imported-key",
            model = "google/gemini-2.5-flash",
            goals = listOf("Neues Ziel 1", "Neues Ziel 2").toWeightedGoals(),
            goalsWereSplit = true
        )
        val mergedSettings = BackupLogic.mergeSettings(existingSettings, importedSettings)
        assertEquals(importedSettings, mergedSettings)

        val existingProfile = Profile(birthDate = "1980-01-01", weightKg = 90.0, heightCm = 175, notes = "Alt", gender = Gender.FEMALE)
        val importedProfile = Profile(birthDate = "1990-05-20", weightKg = 74.0, heightCm = 180, notes = "Neu", gender = Gender.MALE)
        val mergedProfile = BackupLogic.mergeProfile(existingProfile, importedProfile)
        assertEquals(importedProfile, mergedProfile)

        val existingNotes = listOf(
            RecentNote("Salat", starred = false),
            RecentNote("Nur lokal", starred = true)
        )
        val importedNotes = listOf(
            RecentNote("Salat", starred = true),
            RecentNote("Müsli", starred = false)
        )
        val mergedNotes = BackupLogic.mergeRecentNotes(existingNotes, importedNotes)
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
        val json = BackupLogic.serializeBackup(backup)
        val restored = BackupLogic.deserializeBackup(json)
        assertEquals(AppThemeMode.DARK, restored.themeMode)
        assertEquals(AppLanguage.DE, restored.language)
    }

    @Test
    fun testAppBackupMixedWeightsRoundTrip() {
        val backup = AppBackup(
            settings = Settings(
                baseUrl = "https://api.openai.com/v1",
                apiKey = "test-key-123",
                model = "gpt-4o",
                goals = listOf(
                    WeightedGoal("Ziel Sehr Niedrig", 1),
                    WeightedGoal("Ziel Sehr Hoch", 5),
                    WeightedGoal("Ziel Niedrig", 2),
                    WeightedGoal("Ziel Normal", 3)
                ),
                goalsWereSplit = true
            )
        )

        val json = BackupLogic.serializeBackup(backup)
        val restored = BackupLogic.deserializeBackup(json)

        assertEquals(4, restored.settings.goals.size)
        assertEquals(WeightedGoal("Ziel Sehr Niedrig", 1), restored.settings.goals[0])
        assertEquals(WeightedGoal("Ziel Sehr Hoch", 5), restored.settings.goals[1])
        assertEquals(WeightedGoal("Ziel Niedrig", 2), restored.settings.goals[2])
        assertEquals(WeightedGoal("Ziel Normal", 3), restored.settings.goals[3])
        assertEquals(backup.settings, restored.settings)
    }

    @Test
    fun testBackupMergeSettingsBlankGoalsHandling() {
        val existing = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "existing-key",
            model = "gpt-4o-mini",
            goals = listOf(WeightedGoal("Bestehendes Ziel A", 4), WeightedGoal("Bestehendes Ziel B", 2)),
            goalsWereSplit = true
        )

        // Case 1: Imported settings have only blank/empty goals -> keep existing goals & goalsWereSplit
        val importedAllBlank = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "new-key",
            model = "gpt-4o",
            goals = listOf(WeightedGoal("", 5), WeightedGoal("   ", 1)),
            goalsWereSplit = false
        )
        val mergedAllBlank = BackupLogic.mergeSettings(existing, importedAllBlank)
        assertEquals(existing.goals, mergedAllBlank.goals)
        assertTrue(mergedAllBlank.goalsWereSplit)
        assertEquals("new-key", mergedAllBlank.apiKey)

        // Case 2: Imported settings have mixed blank and valid goals -> drop blanks, use valid goals & imported goalsWereSplit
        val importedMixed = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "new-key",
            model = "gpt-4o",
            goals = listOf(WeightedGoal("", 5), WeightedGoal("Importiertes Ziel", 5), WeightedGoal("   ", 1)),
            goalsWereSplit = false
        )
        val mergedMixed = BackupLogic.mergeSettings(existing, importedMixed)
        assertEquals(listOf(WeightedGoal("Importiertes Ziel", 5)), mergedMixed.goals)
        org.junit.Assert.assertFalse(mergedMixed.goalsWereSplit)
    }
}
