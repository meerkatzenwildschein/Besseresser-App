package de.gurum.besseresser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDatabaseAndViewModelTest {

    private val converters = RoomConverters()

    @Test
    fun testRoomGoalResultConverters() {
        val goals = listOf(
            GoalResult("Ziel 1", TrafficLight.GREEN, "Begründung 1"),
            GoalResult("Ziel 2", TrafficLight.YELLOW, "Begründung 2")
        )
        val json = converters.fromGoalResultList(goals)
        assertTrue(json.contains("Ziel 1"))
        val restored = converters.toGoalResultList(json)
        assertEquals(goals, restored)
    }

    @Test
    fun testRoomLongListConverters() {
        val ids = listOf(101L, 202L, 303L)
        val json = converters.fromLongList(ids)
        assertTrue(json.contains("101"))
        val restored = converters.toLongList(json)
        assertEquals(ids, restored)
    }

    @Test
    fun testRoomTrafficLightConverters() {
        assertEquals("gruen", converters.fromTrafficLight(TrafficLight.GREEN))
        assertEquals("gelb", converters.fromTrafficLight(TrafficLight.YELLOW))
        assertEquals("rot", converters.fromTrafficLight(TrafficLight.RED))
        assertEquals("unbekannt", converters.fromTrafficLight(TrafficLight.UNKNOWN))

        assertEquals(TrafficLight.GREEN, converters.toTrafficLight("gruen"))
        assertEquals(TrafficLight.YELLOW, converters.toTrafficLight("gelb"))
        assertEquals(TrafficLight.RED, converters.toTrafficLight("rot"))
    }

    @Test
    fun testHistoryEntryEntityMapping() {
        val domain = HistoryEntry(
            id = 42L,
            dateMillis = 1700000000000L,
            photoFile = "42.jpg",
            goalResults = listOf(GoalResult("Z1", TrafficLight.GREEN, "B1")),
            summary = "Summary",
            note = "Note",
            erkannt = "Pizza",
            backdated = true,
            sortOrder = 1700000005000L
        )
        val entity = domain.toEntity()
        assertEquals(domain.id, entity.id)
        assertEquals(domain.dateMillis, entity.dateMillis)
        assertEquals(domain.photoFile, entity.photoFile)
        assertEquals(domain.goalResults, entity.goalResults)
        assertEquals(domain.summary, entity.summary)
        assertEquals(domain.note, entity.note)
        assertEquals(domain.erkannt, entity.erkannt)
        assertEquals(domain.backdated, entity.backdated)
        assertEquals(domain.sortOrder, entity.sortOrder)

        val restoredDomain = entity.toDomain()
        assertEquals(domain, restoredDomain)
    }

    @Test
    fun testDayFazitEntityMapping() {
        val domain = DayFazit(
            dayKey = "20261106",
            ampel = TrafficLight.GREEN,
            text = "Super Tag",
            entryIds = listOf(1L, 2L),
            createdAt = 1730000000000L,
            fingerprint = "fp123"
        )
        val entity = domain.toEntity()
        assertEquals(domain.dayKey, entity.dayKey)
        assertEquals(domain.ampel, entity.ampel)
        assertEquals(domain.text, entity.text)
        assertEquals(domain.entryIds, entity.entryIds)
        assertEquals(domain.createdAt, entity.createdAt)
        assertEquals(domain.fingerprint, entity.fingerprint)

        val restored = entity.toDomain()
        assertEquals(domain, restored)
    }

    @Test
    fun testWeekFazitEntityMapping() {
        val domain = DayFazit(
            dayKey = "20261102",
            ampel = TrafficLight.YELLOW,
            text = "Gute Woche",
            entryIds = listOf(10L, 20L),
            createdAt = 1730000000000L,
            fingerprint = "fp_week"
        )
        val entity = domain.toWeekEntity()
        assertEquals(domain.dayKey, entity.weekKey)
        assertEquals(domain.ampel, entity.ampel)
        assertEquals(domain.text, entity.text)
        assertEquals(domain.entryIds, entity.entryIds)
        assertEquals(domain.createdAt, entity.createdAt)
        assertEquals(domain.fingerprint, entity.fingerprint)

        val restored = entity.toDomain()
        assertEquals(domain, restored)
    }

    @Test
    fun testMainViewModelUiStateTransitions() {
        val viewModel = MainViewModel()
        val initial = viewModel.uiState.value
        assertNull(initial.evaluation)
        assertFalse(initial.isBusy)
        assertNull(initial.error)

        viewModel.setNote("Lecker Salat")
        assertEquals("Lecker Salat", viewModel.uiState.value.note)

        viewModel.setShowCamera(true)
        assertTrue(viewModel.uiState.value.showCamera)

        viewModel.setShowReview(true)
        assertTrue(viewModel.uiState.value.showReview)

        viewModel.setMenuOpen(true)
        assertTrue(viewModel.uiState.value.menuOpen)

        viewModel.reset()
        assertFalse(viewModel.uiState.value.showCamera)
        assertFalse(viewModel.uiState.value.showReview)
        assertEquals("", viewModel.uiState.value.note)
    }

    @Test
    fun testHistoryViewModelUiStateTransitions() {
        val viewModel = HistoryViewModel()
        val initial = viewModel.uiState.value
        assertTrue(initial.entries.isEmpty())
        assertTrue(initial.loadingFazits.isEmpty())

        viewModel.toggleFazitExpanded("20261106")
        assertTrue(viewModel.uiState.value.expandedFazits.contains("20261106"))
        viewModel.toggleFazitExpanded("20261106")
        assertFalse(viewModel.uiState.value.expandedFazits.contains("20261106"))

        viewModel.toggleWeekExpanded()
        assertTrue(viewModel.uiState.value.expandedWeek)
        viewModel.toggleWeekExpanded()
        assertFalse(viewModel.uiState.value.expandedWeek)
    }

    @Test
    fun testProfileViewModelUiStateTransitions() {
        val viewModel = ProfileViewModel()
        val profile = Profile(
            birthDate = "1985-10-20",
            weightKg = 82.0,
            heightCm = 178,
            notes = "Test Note",
            gender = Gender.MALE
        )
        viewModel.initFromProfile(profile)
        val state = viewModel.uiState.value
        assertEquals("1985-10-20", state.birthDate)
        assertEquals("82", state.weightText)
        assertEquals("178", state.heightText)
        assertEquals("Test Note", state.notesText)
        assertEquals(Gender.MALE, state.gender)

        viewModel.setWeightText("83,5")
        assertEquals("83,5", viewModel.uiState.value.weightText)

        viewModel.setGender(Gender.FEMALE)
        assertEquals(Gender.FEMALE, viewModel.uiState.value.gender)
    }

    @Test
    fun testSetupViewModelUiStateTransitions() {
        val viewModel = SetupViewModel()
        val settings = Settings(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            model = "gpt-4o",
            goals = listOf("Ziel A", "Ziel B")
        )
        viewModel.init(settings, listOf("Default Goal"))
        val state = viewModel.uiState.value
        assertEquals("https://api.openai.com/v1", state.url)
        assertEquals("sk-test", state.key)
        assertEquals("gpt-4o", state.model)
        assertEquals("Ziel A\nZiel B", state.goals)

        viewModel.setModel("gpt-4o-mini")
        assertEquals("gpt-4o-mini", viewModel.uiState.value.model)

        viewModel.setGoals("Neues Ziel")
        assertEquals("Neues Ziel", viewModel.uiState.value.goals)
        assertTrue(viewModel.uiState.value.goalsDirty)
    }
}
