package de.gurum.besseresser

import app.cash.turbine.test
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.ui.AppStateViewModel
import de.gurum.besseresser.ui.RecentNotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurbineFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testAppStateViewModelEmissionsWithTurbine() = runTest(testDispatcher) {
        val setRepo = FakeSettingsRepository()
        val profRepo = FakeProfileRepository()
        val uiPrefsRepo = FakeUiPrefsRepository()
        val histRepo = FakeHistoryRepository()

        val vm = AppStateViewModel(setRepo, profRepo, uiPrefsRepo, histRepo)

        vm.uiState.test {
            val initial = awaitItem()
            assertFalse(initial.loaded)

            testScheduler.advanceUntilIdle()

            val loaded = awaitItem()
            assertTrue(loaded.loaded)
            assertEquals("https://api.openai.com/v1", loaded.settings.baseUrl)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testRecentNotesFlowWithTurbine() = runTest(testDispatcher) {
        val setRepo = FakeSettingsRepository()
        val vm = RecentNotesViewModel(setRepo)

        vm.notes.test {
            val initial = awaitItem()
            assertTrue(initial.isEmpty())

            vm.push(de.gurum.besseresser.model.RecentNote("Frischer Salat"))
            testScheduler.advanceUntilIdle()

            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("Frischer Salat", updated[0].text)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
