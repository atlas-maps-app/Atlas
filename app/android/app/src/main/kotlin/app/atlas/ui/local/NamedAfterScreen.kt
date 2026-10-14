package app.atlas.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.StateMessage

/** Who or what a street is named after. Placeholder for the local package. */
@Composable
fun NamedAfterScreen(args: Dest.NamedAfter, nav: Navigator) {
    AtlasScreen(args.name ?: stringResource(R.string.local_named_after), onBack = { nav.back() }, italic = true) {
        StateMessage("ui-signpost", stringResource(R.string.local_named_after), text = stringResource(R.string.local_named_after_text))
    }
}
