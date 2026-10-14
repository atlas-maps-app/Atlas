package app.atlas.ui.local

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.PlaceRow
import app.atlas.ui.kit.rememberHere

/** The Local tab: what this place is. Placeholder for the local package's cabinet. */
@Composable
fun LocalScreen(args: Dest.Local, nav: Navigator) {
    val here = rememberHere()
    AtlasScreen(stringResource(R.string.local_title), italic = true, onBack = if (args.drawer != null) ({ nav.back() }) else null) {
        Card {
            PlaceRow(stringResource(R.string.local_named_after), symbol = "ui-signpost", colour = Color(0xFF8A5A2B),
                onClick = { nav.go(Dest.NamedAfter(here.lat, here.lon)) })
            PlaceRow(stringResource(R.string.local_building), symbol = "ui-landmark", colour = Color(0xFF8A5A2B),
                onClick = { nav.go(Dest.BuildingStory(here.lat, here.lon)) })
        }
        Spacer(Modifier.height(LocalBottomBarInset.current))
    }
}
