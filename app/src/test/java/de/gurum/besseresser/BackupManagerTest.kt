package de.gurum.besseresser

import android.net.TestUri
import android.net.Uri
import de.gurum.besseresser.data.AppBackup
import de.gurum.besseresser.data.BackupHistoryEntry
import de.gurum.besseresser.data.BackupLogic
import de.gurum.besseresser.data.BackupManager
import de.gurum.besseresser.data.BackupStorage
import de.gurum.besseresser.data.PhotoStorage
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode
import de.gurum.besseresser.model.Evaluation
import de.gurum.besseresser.model.Gender
import de.gurum.besseresser.model.GoalResult
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.RecentNote
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.model.TrafficLight
import de.gurum.besseresser.model.WeightedGoal
import de.gurum.besseresser.model.toWeightedGoals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeBackupStorage : BackupStorage {
    var writtenFileName: String? = null
    var writtenJson: String? = null
    var writtenUri: Uri? = null
    var readJsonContent: String = ""

    override fun writeToUri(uri: Uri, json: String) {
        writtenUri = uri
        writtenFileName = uri.lastPathSegment
        writtenJson = json
    }

    override fun writeToDownloads(fileName: String, json: String) {
        writtenFileName = fileName
        writtenJson = json
    }

    override fun readFromUri(uri: Uri): String {
        return readJsonContent
    }
}

class BackupManagerTest {

    @Test
    fun `createBackup and exportToUri exports all repositories correctly`() = runTest {
        val fakeStorage = FakeBackupStorage()
        val settingsRepo = FakeSettingsRepository(initialSettings = Settings(baseUrl = "https://ai.example.com", apiKey = "secret", model = "gpt-4o"))
        val profileRepo = FakeProfileRepository(initialProfile = Profile(birthDate = "1990-01-01", weightKg = 75.0, heightCm = 180, notes = "Fit", gender = Gender.MALE))
        val uiPrefsRepo = FakeUiPrefsRepository(initialTheme = AppThemeMode.DARK, initialLanguage = AppLanguage.EN)
        val historyRepo = FakeHistoryRepository()
        historyRepo.add(
            photoUri = null,
            ev = Evaluation(
                goalResults = listOf(GoalResult("Low Carb", TrafficLight.GREEN, "Gut")),
                summary = "Perfekt",
                recognizedMeal = "Salat"
            ),
            note = "Mittag"
        )

        val tempDir = File.createTempFile("photos", "test").apply { delete(); mkdirs() }
        val photoStorage = PhotoStorage(baseDir = tempDir)

        val backupManager = BackupManager(
            backupStorage = fakeStorage,
            settingsRepository = settingsRepo,
            profileRepository = profileRepo,
            historyRepository = historyRepo,
            photoStorage = photoStorage,
            uiPrefsRepository = uiPrefsRepo
        )

        val exportUri = TestUri("content://documents/besseresser-backup-2026.json")
        val exportResult = backupManager.exportToUri(exportUri)
        assertTrue(exportResult.isSuccess)
        val summary = exportResult.getOrThrow()
        assertEquals(1, summary.entriesCount)
        assertTrue(summary.hasApiKey)
        assertTrue(summary.hasProfile)
        assertEquals(exportUri, fakeStorage.writtenUri)
        assertTrue(fakeStorage.writtenJson!!.contains("https://ai.example.com"))

        // Now test importFromUri
        fakeStorage.readJsonContent = fakeStorage.writtenJson!!
        val importResult = backupManager.importFromUri(exportUri)
        assertTrue(importResult.isSuccess)
        val importSummary = importResult.getOrThrow()
        assertEquals(1, importSummary.entriesCount)
        assertTrue(importSummary.hasApiKey)
        assertTrue(importSummary.hasProfile)

        tempDir.deleteRecursively()
    }

    @Test
    fun `createBackup and exportToDownloads exports all repositories correctly`() = runTest {
        val fakeStorage = FakeBackupStorage()
        val settingsRepo = FakeSettingsRepository(initialSettings = Settings(baseUrl = "https://ai.example.com", apiKey = "secret", model = "gpt-4o"))
        val profileRepo = FakeProfileRepository(initialProfile = Profile(birthDate = "1990-01-01", weightKg = 75.0, heightCm = 180, notes = "Fit", gender = Gender.MALE))
        val uiPrefsRepo = FakeUiPrefsRepository(initialTheme = AppThemeMode.DARK, initialLanguage = AppLanguage.EN)
        val historyRepo = FakeHistoryRepository()
        historyRepo.add(
            photoUri = null,
            ev = Evaluation(
                goalResults = listOf(GoalResult("Low Carb", TrafficLight.GREEN, "Gut")),
                summary = "Perfekt",
                recognizedMeal = "Salat"
            ),
            note = "Mittag"
        )

        val tempDir = File.createTempFile("photos", "test").apply { delete(); mkdirs() }
        val photoStorage = PhotoStorage(baseDir = tempDir)

        val backupManager = BackupManager(
            backupStorage = fakeStorage,
            settingsRepository = settingsRepo,
            profileRepository = profileRepo,
            historyRepository = historyRepo,
            photoStorage = photoStorage,
            uiPrefsRepository = uiPrefsRepo
        )

        val exportResult = backupManager.exportToDownloads()
        assertTrue(exportResult.isSuccess)
        val summary = exportResult.getOrThrow()
        assertEquals(1, summary.entriesCount)
        assertTrue(summary.hasApiKey)
        assertTrue(summary.hasProfile)
        assertTrue(fakeStorage.writtenJson!!.contains("https://ai.example.com"))

        // Now test importFromUri
        fakeStorage.readJsonContent = fakeStorage.writtenJson!!
        val importResult = backupManager.importFromUri(TestUri("content://backup.json"))
        assertTrue(importResult.isSuccess)
        val importSummary = importResult.getOrThrow()
        assertEquals(1, importSummary.entriesCount)
        assertTrue(importSummary.hasApiKey)
        assertTrue(importSummary.hasProfile)

        tempDir.deleteRecursively()
    }

    @Test
    fun `importFromUri with legacy string goals sets weight to 3 and saves to settingsRepository`() = runTest {
        val fakeStorage = FakeBackupStorage()
        val settingsRepo = FakeSettingsRepository(initialSettings = Settings(baseUrl = "https://old.url", apiKey = "old-key"))
        val profileRepo = FakeProfileRepository()
        val uiPrefsRepo = FakeUiPrefsRepository()
        val historyRepo = FakeHistoryRepository()
        val tempDir = File.createTempFile("photos", "test").apply { delete(); mkdirs() }
        val photoStorage = PhotoStorage(baseDir = tempDir)

        val backupManager = BackupManager(
            backupStorage = fakeStorage,
            settingsRepository = settingsRepo,
            profileRepository = profileRepo,
            historyRepository = historyRepo,
            photoStorage = photoStorage,
            uiPrefsRepository = uiPrefsRepo
        )

        val legacyJson = """
            {
                "version": 1,
                "exportiertAm": 1730900000000,
                "einstellungen": {
                    "baseUrl": "https://api.openai.com/v1",
                    "apiKey": "imported-legacy-key",
                    "model": "gpt-4o",
                    "goals": [
                        "Kein Fastfood",
                        "Mindestens 2L Wasser"
                    ],
                    "goalsWereSplit": true
                },
                "profil": {},
                "notizen": [],
                "verlauf": []
            }
        """.trimIndent()

        fakeStorage.readJsonContent = legacyJson
        val importResult = backupManager.importFromUri(TestUri("content://legacy.json"))
        assertTrue(importResult.isSuccess)

        val savedSettings = settingsRepo.currentSettings()
        assertEquals("https://api.openai.com/v1", savedSettings.baseUrl)
        assertEquals("imported-legacy-key", savedSettings.apiKey)
        assertEquals(2, savedSettings.goals.size)
        assertEquals(WeightedGoal("Kein Fastfood", 3), savedSettings.goals[0])
        assertEquals(WeightedGoal("Mindestens 2L Wasser", 3), savedSettings.goals[1])
        assertTrue(savedSettings.goalsWereSplit)

        tempDir.deleteRecursively()
    }

    @Test
    fun `importFromUri with weighted goals preserves custom weights and drops blanks`() = runTest {
        val fakeStorage = FakeBackupStorage()
        val settingsRepo = FakeSettingsRepository(initialSettings = Settings())
        val profileRepo = FakeProfileRepository()
        val uiPrefsRepo = FakeUiPrefsRepository()
        val historyRepo = FakeHistoryRepository()
        val tempDir = File.createTempFile("photos", "test").apply { delete(); mkdirs() }
        val photoStorage = PhotoStorage(baseDir = tempDir)

        val backupManager = BackupManager(
            backupStorage = fakeStorage,
            settingsRepository = settingsRepo,
            profileRepository = profileRepo,
            historyRepository = historyRepo,
            photoStorage = photoStorage,
            uiPrefsRepository = uiPrefsRepo
        )

        val weightedJson = """
            {
                "version": 1,
                "exportiertAm": 1730900000000,
                "einstellungen": {
                    "baseUrl": "https://api.openai.com/v1",
                    "apiKey": "weighted-key",
                    "model": "gpt-4o",
                    "goals": [
                        {"text": "Ziel Niedrig", "weight": 1},
                        {"text": "", "weight": 4},
                        {"text": "Ziel Hoch", "weight": 5},
                        {"text": "Ziel Mittel", "weight": 2}
                    ],
                    "goalsWereSplit": true
                },
                "profil": {},
                "notizen": [],
                "verlauf": []
            }
        """.trimIndent()

        fakeStorage.readJsonContent = weightedJson
        val importResult = backupManager.importFromUri(TestUri("content://weighted.json"))
        assertTrue(importResult.isSuccess)

        val savedSettings = settingsRepo.currentSettings()
        assertEquals(3, savedSettings.goals.size)
        assertEquals(WeightedGoal("Ziel Niedrig", 1), savedSettings.goals[0])
        assertEquals(WeightedGoal("Ziel Hoch", 5), savedSettings.goals[1])
        assertEquals(WeightedGoal("Ziel Mittel", 2), savedSettings.goals[2])

        tempDir.deleteRecursively()
    }

    @Test
    fun `importFromUri with all blank goals retains existing repository goals`() = runTest {
        val fakeStorage = FakeBackupStorage()
        val initialGoals = listOf(WeightedGoal("Original Ziel 1", 4), WeightedGoal("Original Ziel 2", 2))
        val settingsRepo = FakeSettingsRepository(
            initialSettings = Settings(
                baseUrl = "https://original.url",
                apiKey = "orig-key",
                goals = initialGoals,
                goalsWereSplit = true
            )
        )
        val profileRepo = FakeProfileRepository()
        val uiPrefsRepo = FakeUiPrefsRepository()
        val historyRepo = FakeHistoryRepository()
        val tempDir = File.createTempFile("photos", "test").apply { delete(); mkdirs() }
        val photoStorage = PhotoStorage(baseDir = tempDir)

        val backupManager = BackupManager(
            backupStorage = fakeStorage,
            settingsRepository = settingsRepo,
            profileRepository = profileRepo,
            historyRepository = historyRepo,
            photoStorage = photoStorage,
            uiPrefsRepository = uiPrefsRepo
        )

        val allBlankJson = """
            {
                "version": 1,
                "exportiertAm": 1730900000000,
                "einstellungen": {
                    "baseUrl": "https://api.openai.com/v1",
                    "apiKey": "new-key",
                    "model": "gpt-4o",
                    "goals": [
                        {"text": "", "weight": 5},
                        {"text": "   ", "weight": 1}
                    ],
                    "goalsWereSplit": false
                },
                "profil": {},
                "notizen": [],
                "verlauf": []
            }
        """.trimIndent()

        fakeStorage.readJsonContent = allBlankJson
        val importResult = backupManager.importFromUri(TestUri("content://blank.json"))
        assertTrue(importResult.isSuccess)

        val savedSettings = settingsRepo.currentSettings()
        // Kept existing goals & goalsWereSplit
        assertEquals(initialGoals, savedSettings.goals)
        assertTrue(savedSettings.goalsWereSplit)
        assertEquals("new-key", savedSettings.apiKey)

        tempDir.deleteRecursively()
    }
}
