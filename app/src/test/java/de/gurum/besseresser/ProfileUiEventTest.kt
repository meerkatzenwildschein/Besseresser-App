package de.gurum.besseresser

import de.gurum.besseresser.ui.ProfileViewModel
import de.gurum.besseresser.ui.UiEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileUiEventTest {

    @Test
    fun testProfileValidationEmitsUiEvent() = runTest {
        val repo = FakeProfileRepository()
        val vm = ProfileViewModel(repo)

        vm.setWeightText("9999") // invalid
        val saved = vm.save()
        org.junit.Assert.assertFalse(saved)

        val event = vm.eventFlow.first()
        assertTrue(event is UiEvent.ShowSnackbar)
    }
}
