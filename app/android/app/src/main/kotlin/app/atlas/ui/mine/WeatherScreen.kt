package app.atlas.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.Forecast
import app.atlas.ui.kit.CurrentWeather
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.skySymbol
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** The weather here: now and the next hours, or the kept forecast with its age. Placeholder for the mine package. */
@Composable
fun WeatherScreen(args: Dest.Weather, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val at = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    var forecast by remember(at) { mutableStateOf<Forecast?>(null) }
    var done by remember(at) { mutableStateOf(false) }
    LaunchedEffect(at) {
        forecast = withContext(Dispatchers.IO) {
            runCatching {
                if (services.config.offline || !services.connectivity.online.value) services.weather.kept(at) else services.weather.forecast(at)
            }.getOrNull()
        }
        done = true
    }
    val clock = appClock()
    val hm = DateTimeFormatter.ofPattern("H:mm").withZone(clock.zone)
    AtlasScreen(stringResource(R.string.mine_weather), onBack = { nav.back() }) {
        val f = forecast
        val now = CurrentWeather.of(f, clock.instant())
        when {
            f != null && now != null -> Card {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Symbol(now.symbol, size = 48.dp, tint = Atlas.colors.eat)
                    Text("${now.temperature.roundToInt()}°", style = Atlas.type.numeral)
                }
                if (f.kept) Text(stringResource(R.string.mine_weather_kept, hm.format(f.fetchedAt)), style = Atlas.type.caption, color = Atlas.colors.caution)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (h in f.hours.filter { it.time.isAfter(clock.instant()) }.take(6)) {
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(hm.format(h.time), style = Atlas.type.caption, color = Atlas.colors.inkSoft)
                            Symbol(skySymbol(h.sky, h.isDay), size = 22.dp)
                            Text("${h.temperature.roundToInt()}°", style = Atlas.type.label)
                        }
                    }
                }
            }
            done -> StateMessage("ui-cloud-off", stringResource(R.string.mine_weather_none))
            else -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption)
        }
        HonestyFooter("Weather data by Open-Meteo.com (CC BY 4.0)")
    }
}
