package de.gurum.besseresser.ui
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import de.gurum.besseresser.R
import de.gurum.besseresser.model.TrafficLight

// ----------------------------------------------------------------- TrafficLight UI extensions

/** UI color for a traffic-light rating. */
fun TrafficLight.color(): Color = when (this) {
    TrafficLight.GREEN -> Color(0xFF2E9E44)
    TrafficLight.YELLOW -> Color(0xFFE6A817)
    TrafficLight.RED -> Color(0xFFD32F2F)
    TrafficLight.UNKNOWN -> Color(0xFF9E9E9E)
}

/** Localized label for a traffic-light rating. */
@Composable
fun TrafficLight.label(): String = stringResource(
    when (this) {
        TrafficLight.GREEN -> R.string.ampel_green
        TrafficLight.YELLOW -> R.string.ampel_yellow
        TrafficLight.RED -> R.string.ampel_red
        TrafficLight.UNKNOWN -> R.string.ampel_unknown
    }
)

fun ampelColor(rating: TrafficLight): Color = rating.color()
fun ampelColor(rating: String): Color = TrafficLight.fromString(rating).color()

@Composable
fun ampelLabel(rating: TrafficLight): String = rating.label()

@Composable
fun ampelLabel(rating: String): String = TrafficLight.fromString(rating).label()
