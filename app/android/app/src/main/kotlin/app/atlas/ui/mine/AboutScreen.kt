package app.atlas.ui.mine

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card

/** About & licences: every source and its credit. Placeholder for the mine package. */
@Composable
fun AboutScreen(args: Dest.About, nav: Navigator) {
    AtlasScreen(stringResource(R.string.mine_about), onBack = { nav.back() }) {
        Card { Text(stringResource(R.string.mine_about_text), style = Atlas.type.body) }
    }
}
