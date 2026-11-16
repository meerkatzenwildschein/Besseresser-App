package de.gurum.besseresser.ui
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import de.gurum.besseresser.R
import de.gurum.besseresser.model.TrafficLight

// ----------------------------------------------------------------- Ampel-Darstellung (Compose-Extensions)

/** UI-Farbe einer Ampel (Domänenmodell bleibt compose-frei, Darstellung lebt hier). */
fun TrafficLight.color(): Color = when (this) {
    TrafficLight.GREEN -> Color(0xFF2E9E44)
    TrafficLight.YELLOW -> Color(0xFFE6A817)
    TrafficLight.RED -> Color(0xFFD32F2F)
    TrafficLight.UNKNOWN -> Color(0xFF9E9E9E)
}

/** Lokalisiertes Label einer Ampel. */
@Composable
fun TrafficLight.label(): String = stringResource(
    when (this) {
        TrafficLight.GREEN -> R.string.ampel_green
        TrafficLight.YELLOW -> R.string.ampel_yellow
        TrafficLight.RED -> R.string.ampel_red
        TrafficLight.UNKNOWN -> R.string.ampel_unknown
    }
)

fun ampelColor(ampel: TrafficLight): Color = ampel.color()
fun ampelColor(ampel: String): Color = TrafficLight.fromString(ampel).color()

@Composable
fun ampelLabel(ampel: TrafficLight): String = ampel.label()

@Composable
fun ampelLabel(ampel: String): String = TrafficLight.fromString(ampel).label()
