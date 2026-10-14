package app.atlas.ui.local

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.Sun
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.PlaceRow
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.rememberHere
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The Soon tab: what is coming up near you. Placeholder for the local package's planner. */
@Composable
fun SoonScreen(args: Dest.Soon, nav: Navigator) {
    val here = rememberHere()
    val clock = appClock()
    val sun = remember(here, clock) { Sun.times(LocalDate.now(clock), here.lat, here.lon, clock.zone) }
    val hm = DateTimeFormatter.ofPattern("H:mm")
    AtlasScreen(stringResource(R.string.soon_title), italic = true) {
        Card {
            sun.sunrise?.let { PlaceRow(stringResource(R.string.soon_sunrise, it.format(hm)), symbol = "ui-sunrise", colour = Color(0xFFE29A12)) }
            sun.sunset?.let { PlaceRow(stringResource(R.string.soon_sunset, it.format(hm)), symbol = "ui-sunset", colour = Color(0xFFE0592A)) }
            PlaceRow(stringResource(R.string.soon_weather), symbol = "ui-cloud-sun", colour = Color(0xFF1E7FB8), onClick = { nav.go(Dest.Weather()) })
        }
        Spacer(Modifier.height(LocalBottomBarInset.current))
    }
}
