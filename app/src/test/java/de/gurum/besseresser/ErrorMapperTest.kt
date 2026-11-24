package de.gurum.besseresser

import de.gurum.besseresser.ui.ErrorMapper
import de.gurum.besseresser.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMapperTest {

    @Test
    fun testNetworkExceptionsMapToResource() {
        val unknownHost = UnknownHostException("api.openai.com")
        val connect = ConnectException("Failed to connect")
        val timeout = SocketTimeoutException("timeout")

        val uiTextUnknown = ErrorMapper.toUiText(unknownHost)
        assertTrue(uiTextUnknown is UiText.StringResource)
        assertEquals(R.string.no_internet_cannot_rate, (uiTextUnknown as UiText.StringResource).resId)

        val uiTextConnect = ErrorMapper.toUiText(connect)
        assertTrue(uiTextConnect is UiText.StringResource)

        val uiTextTimeout = ErrorMapper.toUiText(timeout)
        assertTrue(uiTextTimeout is UiText.StringResource)

        val msg = ErrorMapper.toUserMessage(unknownHost)
        assertEquals("Keine Internetverbindung – KI-Bewertung ist aktuell nicht möglich.", msg)
    }

    @Test
    fun testGenericExceptionMapsToDynamicString() {
        val generic = IllegalStateException("Custom error description")
        val uiText = ErrorMapper.toUiText(generic)
        assertTrue(uiText is UiText.DynamicString)
        assertEquals("Custom error description", (uiText as UiText.DynamicString).value)

        val msg = ErrorMapper.toUserMessage(generic)
        assertEquals("Custom error description", msg)
    }

    @Test
    fun testNullThrowableFallback() {
        val uiText = ErrorMapper.toUiText(null)
        assertTrue(uiText is UiText.StringResource)

        val msg = ErrorMapper.toUserMessage(null, fallback = "Fallback Text")
        assertEquals("Fallback Text", msg)
    }
}
