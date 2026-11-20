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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeBackupStorage : BackupStorage {
    var writtenFileName: String? = null
    var writtenJson: String? = null
    var readJsonContent: String = ""

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
}
