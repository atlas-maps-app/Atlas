package app.atlas.ui.mine

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.providers.Catalogue
import app.atlas.providers.KeyNeed
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.PlaceRow

/** Every data source with its licence, and whether it needs a key. Placeholder for the mine package. */
@Composable
fun DataSourcesScreen(args: Dest.DataSources, nav: Navigator) {
    val services = LocalServices.current
    val keyed = stringResource(R.string.mine_key_set)
    val needed = stringResource(R.string.mine_key_needed)
    AtlasScreen(stringResource(R.string.mine_data), onBack = { nav.back() }) {
        Card {
            for (p in Catalogue.ALL) {
                val keyNote = when (p.keyNeed) {
                    KeyNeed.NONE -> null
                    else -> if (services.keys.key(p.id) != null) keyed else needed
                }
                PlaceRow(
                    p.name,
                    meta = listOfNotNull(p.licence, keyNote).joinToString(" · "),
                    symbol = if (p.keyNeed == KeyNeed.NONE) "ui-globe" else "ui-key",
                    colour = if (p.keyNeed == KeyNeed.NONE) Atlas.colors.open else Atlas.colors.ask,
                )
                Hairline()
            }
        }
    }
}
