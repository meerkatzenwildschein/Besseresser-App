package de.gurum.besseresser.data

import java.net.URI

/**
 * Status der URL-Sicherheit für KI- und API-Endpunkte.
 */
enum class UrlSecurityStatus {
    SECURE_HTTPS,
    LOCAL_CLEARTEXT_HTTP,
    INSECURE_REMOTE_HTTP,
    INVALID
}

/**
 * Ausnahme bei versuchter Nutzung eines unsicheren / unverschlüsselten API-Endpunkts.
 */
class InsecureEndpointException(
    message: String = "Unverschlüsseltes HTTP ist für externe API-Endpunkte nicht zulässig. Bitte verwende HTTPS."
) : SecurityException(message)

/**
 * Sicherheitsprüfungen für Netzwerk-Endpunkte der Besseresser-App.
 * Erzwingt TLS/HTTPS für alle externen APIs (Schutz vor Man-in-the-Middle-Angriffen und Abfangen von API-Keys/Fotos)
 * und erlaubt unverschlüsseltes HTTP ausschließlich für lokale Entwicklungs- und Test-Hosts
 * (localhost, 127.0.0.1, 10.0.2.2, ::1) analog zur network_security_config.xml.
 */
@Suppress("kotlin:S1313")
object NetworkSecurity {

    private val LOCAL_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "10.0.2.2",
        "::1",
        "[::1]"
    )

    /**
     * Prüft, ob es sich beim angegebenen Host um eine lokale Loopback- oder Emulator-Adresse handelt.
     */
    fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val cleanHost = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        return cleanHost in LOCAL_HOSTS || cleanHost.startsWith("127.")
    }

    /**
     * Bewertet die Sicherheit der übergebenen URL (HTTPS vs. lokales HTTP vs. unsicheres Remote-HTTP).
     */
    fun assessUrl(url: String): UrlSecurityStatus {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return UrlSecurityStatus.INVALID
        return try {
            val uri = URI.create(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return UrlSecurityStatus.INVALID
            val host = uri.host?.lowercase()
            when (scheme) {
                "https" -> {
                    if (host.isNullOrBlank()) UrlSecurityStatus.INVALID else UrlSecurityStatus.SECURE_HTTPS
                }
                "http" -> {
                    if (host.isNullOrBlank()) {
                        UrlSecurityStatus.INVALID
                    } else if (isLocalHost(host)) {
                        UrlSecurityStatus.LOCAL_CLEARTEXT_HTTP
                    } else {
                        UrlSecurityStatus.INSECURE_REMOTE_HTTP
                    }
                }
                else -> UrlSecurityStatus.INVALID
            }
        } catch (_: Exception) {
            UrlSecurityStatus.INVALID
        }
    }

    /**
     * Gibt `true` zurück, wenn die URL für Anfragen zulässig ist (HTTPS oder lokaler Host).
     */
    fun isPermittedUrl(url: String): Boolean {
        val status = assessUrl(url)
        return status == UrlSecurityStatus.SECURE_HTTPS || status == UrlSecurityStatus.LOCAL_CLEARTEXT_HTTP
    }

    /**
     * Prüft die URL und wirft eine [InsecureEndpointException], falls die URL unsicher oder ungültig ist.
     */
    fun requirePermittedEndpoint(url: String) {
        when (assessUrl(url)) {
            UrlSecurityStatus.SECURE_HTTPS, UrlSecurityStatus.LOCAL_CLEARTEXT_HTTP -> {
                // Erlaubt
            }
            UrlSecurityStatus.INSECURE_REMOTE_HTTP -> {
                throw InsecureEndpointException(
                    "Unverschlüsseltes HTTP ist für externe API-Endpunkte nicht zulässig. Bitte verwende HTTPS zum Schutz deiner Daten."
                )
            }
            UrlSecurityStatus.INVALID -> {
                throw InsecureEndpointException(
                    "Ungültige oder unvollständige API-URL: $url"
                )
            }
        }
    }
}
