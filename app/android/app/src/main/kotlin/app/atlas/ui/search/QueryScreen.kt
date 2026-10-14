package app.atlas.ui.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.StateMessage

/** The query builder, "A near B". Placeholder for the search package. */
@Composable
fun QueryScreen(args: Dest.Query, nav: Navigator) {
    AtlasScreen(stringResource(R.string.query_title), onBack = { nav.back() }, italic = true) {
        if (args.text.isNotEmpty()) Card { Text(args.text, style = Atlas.type.mono) }
        StateMessage("ui-near", stringResource(R.string.query_title), text = stringResource(R.string.query_text), tint = Atlas.colors.ask)
    }
}
