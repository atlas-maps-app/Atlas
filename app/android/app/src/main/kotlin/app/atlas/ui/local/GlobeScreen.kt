package app.atlas.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.StateMessage

/** The zoom ladder, from the globe out. Placeholder for the local package. */
@Composable
fun GlobeScreen(args: Dest.Globe, nav: Navigator) {
    AtlasScreen(stringResource(R.string.globe_title), onBack = { nav.back() }, italic = true) {
        StateMessage("ui-globe", stringResource(R.string.globe_title), text = stringResource(R.string.globe_text), tint = Atlas.colors.go)
    }
}
