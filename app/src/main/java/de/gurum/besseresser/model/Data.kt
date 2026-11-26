package de.gurum.besseresser.model
import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/** Typsichere Ampelfarben für Bewertungen und Fazits.
 *  UI-Darstellung (Farbe/Label) liegt als Compose-Extension im ui-Package (AmpelUi.kt). */
@Serializable(with = TrafficLightSerializer::class)
enum class TrafficLight(val rawValue: String) {
    GREEN("gruen"),
    YELLOW("gelb"),
    RED("rot"),
    UNKNOWN("unbekannt");

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

// ----------------------------------------------------------------- Erscheinungsbild (Theme) & Sprache

object AppThemeModeSerializer : KSerializer<AppThemeMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AppThemeMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AppThemeMode) {
        encoder.encodeString(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): AppThemeMode {
        return AppThemeMode.fromString(decoder.decodeString())
    }
}

/** Theme-Auswahl: System-Vorgabe folgen, immer Hell oder immer Dunkel. */
@Serializable(with = AppThemeModeSerializer::class)
enum class AppThemeMode(val rawValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromString(value: String?): AppThemeMode = when (value?.trim()?.lowercase()) {
            "light", "hell" -> LIGHT
            "dark", "dunkel" -> DARK
            else -> SYSTEM
        }
    }
}

object AppLanguageSerializer : KSerializer<AppLanguage> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AppLanguage", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: AppLanguage) {
        encoder.encodeString(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): AppLanguage {
        return AppLanguage.resolve(decoder.decodeString())
    }
}

/** Unterstützte App-Sprachen: Deutsch (de) und Englisch (en). */
@Serializable(with = AppLanguageSerializer::class)
enum class AppLanguage(val rawValue: String) {
    DE("de"),
    EN("en");

    val locale: java.util.Locale
        get() = when (this) {
            DE -> java.util.Locale.GERMAN
            EN -> java.util.Locale.ENGLISH
        }

    companion object {
        fun fromString(value: String?): AppLanguage? = when (value?.trim()?.lowercase()) {
            "de", "de-de", "german", "deutsch" -> DE
            "en", "en-us", "en-gb", "english", "englisch" -> EN
            else -> null
        }

        /** Gespeicherte Sprache oder Fallback anhand der Systemsprache (de -> DE, sonst EN). */
        fun resolve(savedRaw: String?, systemLanguage: String = java.util.Locale.getDefault().language): AppLanguage =
            fromString(savedRaw) ?: if (systemLanguage.trim().lowercase().startsWith("de")) DE else EN
    }
}

// ----------------------------------------------------------------- Einstellungen & Bewertungen

object WeightedGoalSerializer : KSerializer<WeightedGoal> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("WeightedGoal") {
            element<String>("text")
            element<Int>("weight", isOptional = true)
        }

    override fun serialize(encoder: Encoder, value: WeightedGoal) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeStringElement(descriptor, 0, value.text)
        composite.encodeIntElement(descriptor, 1, value.weight.coerceIn(WeightedGoal.MIN_WEIGHT, WeightedGoal.MAX_WEIGHT))
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): WeightedGoal {
        val jsonInput = decoder as? JsonDecoder
        if (jsonInput != null) {
            val element = jsonInput.decodeJsonElement()
            return when (element) {
                is JsonPrimitive -> {
                    if (element is kotlinx.serialization.json.JsonNull) {
                        WeightedGoal("", WeightedGoal.DEFAULT_WEIGHT)
                    } else {
                        WeightedGoal(element.content, WeightedGoal.DEFAULT_WEIGHT)
                    }
                }
                is JsonObject -> {
                    val text = (element["text"] as? JsonPrimitive)?.contentOrNull
                        ?: (element["ziel"] as? JsonPrimitive)?.contentOrNull
                        ?: ""
                    val rawWeight = (element["weight"] as? JsonPrimitive)?.intOrNull
                        ?: (element["gewicht"] as? JsonPrimitive)?.intOrNull
                    val weight = rawWeight?.coerceIn(WeightedGoal.MIN_WEIGHT, WeightedGoal.MAX_WEIGHT)
                        ?: WeightedGoal.DEFAULT_WEIGHT
                    WeightedGoal(text, weight)
                }
                else -> WeightedGoal("", WeightedGoal.DEFAULT_WEIGHT)
            }
        }
        var text = ""
        var weight = WeightedGoal.DEFAULT_WEIGHT
        val composite = decoder.beginStructure(descriptor)
        loop@ while (true) {
            when (val index = composite.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> text = composite.decodeStringElement(descriptor, 0)
                1 -> weight = composite.decodeIntElement(descriptor, 1).coerceIn(WeightedGoal.MIN_WEIGHT, WeightedGoal.MAX_WEIGHT)
                else -> break@loop
            }
        }
        composite.endStructure(descriptor)
        return WeightedGoal(text, weight.coerceIn(WeightedGoal.MIN_WEIGHT, WeightedGoal.MAX_WEIGHT))
    }
}

/** Einzelnes Ernährungsziel mit Wichtung (1 = sehr niedrig bis 5 = sehr hoch, Default = 3). */
@Immutable
@Serializable(with = WeightedGoalSerializer::class)
data class WeightedGoal(
    val text: String = "",
    val weight: Int = DEFAULT_WEIGHT
) {
    init {
        require(weight in MIN_WEIGHT..MAX_WEIGHT) {
            "Weight must be between $MIN_WEIGHT and $MAX_WEIGHT, got $weight"
        }
    }

    companion object {
        const val MIN_WEIGHT = 1
        const val MAX_WEIGHT = 5
        const val DEFAULT_WEIGHT = 3
    }
}

fun List<WeightedGoal>.toGoalTextList(): List<String> = map { it.text }

fun List<String>.toWeightedGoals(defaultWeight: Int = WeightedGoal.DEFAULT_WEIGHT): List<WeightedGoal> =
    map { WeightedGoal(it, defaultWeight) }

/** Einstellungen (Setup): URL/Key/Modell + Ziele. */
@Immutable
@Serializable
data class Settings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val goals: List<WeightedGoal> = emptyList(),
    // true = Ziele sind bereits als saubere Ein-Zeile-ein-Ziel-Liste gespeichert (KI-Aufteilung bestätigt)
    val goalsWereSplit: Boolean = false,
    // Historien-Begrenzung: Einträge (inkl. Tages-/Wochenfazits & Fotos) werden nach so vielen
    // Wochen automatisch gelöscht. 0 = automatisches Löschen deaktiviert.
    val retentionWeeks: Int = DEFAULT_RETENTION_WEEKS
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank() && goals.isNotEmpty()

    fun toGoalTextList(): List<String> = goals.map { it.text }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val DEFAULT_RETENTION_WEEKS = 8
    }
}

/** Bewertung fuer EIN Ziel: Ampel + reason + optionaler Optimierungs-Tipp ("Wie mache ich es gruen?"). */
@Immutable
@Serializable
data class GoalResult(
    @SerialName("ziel")
    val goal: String = "",
    @SerialName("ampel")
    val rating: TrafficLight = TrafficLight.UNKNOWN,
    @SerialName("begruendung")
    val reason: String = "",
    @SerialName("tipp")
    val tipp: String? = null
)

/** Ergebnis einer Foto-Bewertung durch die KI. */
@Immutable
@Serializable
data class Evaluation(
    val goalResults: List<GoalResult> = emptyList(),
    val summary: String = "",
    @SerialName("erkannt")
    val recognizedMeal: String = ""    // Was die KI auf dem Foto erkannt hat (inkl. Mengenangabe)
) {
    /** Prüft, ob diese Bewertung vollständig und passend für die übergebenen Ziele [goals] vorliegt. */
    fun matchesGoals(goals: List<String>): Boolean {
        if (goals.isEmpty() || goalResults.isEmpty()) return false
        if (goalResults.size != goals.size) return false
        return goals.all { g -> goalResults.any { it.goal.equals(g, ignoreCase = true) } }
    }

    fun matchesWeightedGoals(goals: List<WeightedGoal>): Boolean =
        matchesGoals(goals.map { it.text })
}
