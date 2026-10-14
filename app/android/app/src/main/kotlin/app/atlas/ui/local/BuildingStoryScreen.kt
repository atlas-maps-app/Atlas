package app.atlas.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.StateMessage

/** A building's story. Placeholder for the local package. */
@Composable
fun BuildingStoryScreen(args: Dest.BuildingStory, nav: Navigator) {
    AtlasScreen(stringResource(R.string.local_building), onBack = { nav.back() }) {
        StateMessage("ui-landmark", stringResource(R.string.local_building), text = stringResource(R.string.local_building_text))
    }
}
