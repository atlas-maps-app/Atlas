package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.BackButton
import app.atlas.ui.kit.ProgressRing
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.rememberAfterDark
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance

/** Turn by turn, black and lime as on the Ride board. Placeholder for the go package's guidance screens. */
@Composable
fun NavigateScreen(args: Dest.Navigate, nav: Navigator) {
    val here = rememberHere()
    // Black and lime after dark; by day the app's own light or dark.
    AtlasTheme(dark = Atlas.colors.isDark, night = rememberAfterDark(here)) {
        Column(
            Modifier.fillMaxSize().background(Atlas.colors.paper).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BackButton({ nav.back() }, Modifier.align(Alignment.Start))
            ProgressRing(0f, size = 240.dp, stroke = 14.dp) {
                Symbol("ui-arrow-up", tint = Atlas.colors.ink, size = 120.dp, weight = 1.4f)
            }
            Text(shortDistance(Geo.distance(here, args.to)), style = Atlas.type.numeral, color = Atlas.colors.ink)
            Text(args.toName ?: stringResource(R.string.go_navigate_title), style = Atlas.type.name, color = Atlas.colors.inkSoft)
        }
    }
}
