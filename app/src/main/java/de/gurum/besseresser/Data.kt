package de.gurum.besseresser

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** Zentral vorkonfigurierte Json-Instanz mit toleranten Defaults. */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
    prettyPrint = false
}

// ----------------------------------------------------------------- Ampel / TrafficLight

object TrafficLightSerializer : KSerializer<TrafficLight> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TrafficLight", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: TrafficLight) {
        encoder.encodeString(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): TrafficLight {
        return TrafficLight.fromString(decoder.decodeString())
    }
}

/** Typsichere Ampelfarben für Bewertungen und Fazits. */
@Serializable(with = TrafficLightSerializer::class)
enum class TrafficLight(val rawValue: String) {
    GREEN("gruen"),
    YELLOW("gelb"),
    RED("rot"),
    UNKNOWN("unbekannt");

    fun color(): Color = when (this) {
        GREEN -> Color(0xFF2E9E44)
        YELLOW -> Color(0xFFE6A817)
        RED -> Color(0xFFD32F2F)
        UNKNOWN -> Color(0xFF9E9E9E)
    }

    @Composable
    fun label(): String = stringResource(
        when (this) {
            GREEN -> R.string.ampel_green
            YELLOW -> R.string.ampel_yellow
            RED -> R.string.ampel_red
            UNKNOWN -> R.string.ampel_unknown
        }
    )

    companion object {
        fun fromString(s: String?): TrafficLight {
            if (s.isNullOrBlank()) return UNKNOWN
            val has = { needle: String -> s.contains(needle, ignoreCase = true) }
            if (has("grün") || has("gruen") || s.contains("🟢") || s.contains("✅") || has("green")) return GREEN
            if (has("gelb") || s.contains("🟡") || s.contains("🟠") || has("yellow") || has("orange") || has("amber")) return YELLOW
            if (has("rot") || s.contains("🔴") || has("red")) return RED
            val n = s.lowercase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .filter { it.isLetterOrDigit() }
            return when {
                n.contains("gruen") || n.contains("green") -> GREEN
                n.contains("gelb") || n.contains("yellow") || n.contains("orange") || n.contains("amber") -> YELLOW
                n.contains("rot") && !n.contains("prot") -> RED
                n.contains("red") -> RED
                else -> UNKNOWN
            }
        }
    }
}

fun ampelColor(ampel: TrafficLight): Color = ampel.color()
fun ampelColor(ampel: String): Color = TrafficLight.fromString(ampel).color()

@Composable
fun ampelLabel(ampel: TrafficLight): String = ampel.label()

@Composable
fun ampelLabel(ampel: String): String = TrafficLight.fromString(ampel).label()

// ----------------------------------------------------------------- Geschlecht / Gender

object GenderSerializer : KSerializer<Gender> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Gender", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Gender) {
        encoder.encodeString(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): Gender {
        return Gender.fromString(decoder.decodeString())
    }
}

@Serializable(with = GenderSerializer::class)
enum class Gender(val rawValue: String) {
    NONE(""),
    MALE("male"),
    FEMALE("female"),
    DIVERSE("diverse");

    companion object {
        fun fromString(value: String?): Gender = when (value?.trim()?.lowercase()) {
            "male" -> MALE
            "female" -> FEMALE
            "diverse" -> DIVERSE
            else -> NONE
        }
    }
}

// ----------------------------------------------------------------- Einstellungen & Bewertungen

/** Einstellungen (Setup): URL/Key/Modell + Ziele. */
@Serializable
data class Settings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val goals: List<String> = emptyList(),
    // true = Ziele sind bereits als saubere Ein-Zeile-ein-Ziel-Liste gespeichert (KI-Aufteilung bestätigt)
    val goalsWereSplit: Boolean = false
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank() && goals.isNotEmpty()
}

/** Bewertung fuer EIN Ziel: Ampel + Begruendung. */
@Serializable
data class GoalResult(
    @SerialName("ziel")
    val goal: String = "",
    @SerialName("ampel")
    val ampel: TrafficLight = TrafficLight.UNKNOWN,
    @SerialName("begruendung")
    val begruendung: String = ""
)

/** Ergebnis einer Foto-Bewertung durch die KI. */
@Serializable
data class Evaluation(
    val goalResults: List<GoalResult> = emptyList(),
    val summary: String = "",
    val erkannt: String = ""    // Was die KI auf dem Foto (inkl. Nutzer-Anmerkung) erkannt zu haben meint
)
