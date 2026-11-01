package de.gurum.besseresser

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource

/** Einstellungen (Setup): URL/Key/Modell + Ziele. */
data class Settings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val goals: List<String>,
    // true = Ziele sind bereits als saubere Ein-Zeile-ein-Ziel-Liste gespeichert (KI-Aufteilung bestätigt)
    val goalsWereSplit: Boolean = false
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank() && goals.isNotEmpty()
}

/** Bewertung fuer EIN Ziel: Ampel + Begruendung. */
data class GoalResult(
    val goal: String,
    val ampel: String,        // "gruen" | "gelb" | "rot" | "unbekannt"
    val begruendung: String
)

/** Ergebnis einer Foto-Bewertung durch die KI. */
data class Evaluation(
    val goalResults: List<GoalResult>,
    val summary: String
)

// ----------------------------------------------------------------- Ampel-Helfer

fun ampelColor(ampel: String): Color = when (ampel) {
    "gruen" -> Color(0xFF2E9E44)
    "gelb" -> Color(0xFFE6A817)
    "rot" -> Color(0xFFD32F2F)
    else -> Color(0xFF9E9E9E)
}

@Composable
fun ampelLabel(ampel: String): String = stringResource(
    when (ampel) {
        "gruen" -> R.string.ampel_green
        "gelb" -> R.string.ampel_yellow
        "rot" -> R.string.ampel_red
        else -> R.string.ampel_unknown
    }
)
