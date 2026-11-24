package de.gurum.besseresser.ui

import de.gurum.besseresser.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Wandelt technische Exceptions und Fehlertexte in benutzerfreundliche, lokalisierte [UiText]-Instanzen um.
 */
object ErrorMapper {
    fun toUiText(throwable: Throwable?): UiText {
        if (throwable == null) return UiText.StringResource(R.string.no_internet_cannot_rate)
        return when (throwable) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException -> {
                UiText.StringResource(R.string.no_internet_cannot_rate)
            }
            else -> {
                val message = throwable.message?.takeIf { it.isNotBlank() }
                if (message != null) {
                    UiText.DynamicString(message)
                } else {
                    UiText.StringResource(R.string.no_internet_cannot_rate)
                }
            }
        }
    }

    fun toUserMessage(throwable: Throwable?, fallback: String? = null): String {
        return when (throwable) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException -> {
                fallback ?: "Keine Internetverbindung – KI-Bewertung ist aktuell nicht möglich."
            }
            else -> {
                throwable?.message?.takeIf { it.isNotBlank() }
                    ?: fallback
                    ?: "Ein unerwarteter Fehler ist aufgetreten."
            }
        }
    }
}
