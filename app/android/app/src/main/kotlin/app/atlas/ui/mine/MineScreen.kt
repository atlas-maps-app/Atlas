package app.atlas.ui.mine

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
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.PlaceRow

/** The Mine tab: you, your settings and what is on this phone. Placeholder for the mine package's pocket. */
@Composable
fun MineScreen(args: Dest.Mine, nav: Navigator) {
    AtlasScreen(stringResource(R.string.mine_title)) {
        Chip(stringResource(R.string.mine_no_account), symbol = "ui-shield", symbolTint = Atlas.colors.open, background = Atlas.colors.pill)
        Card {
            PlaceRow(stringResource(R.string.mine_settings), symbol = "ui-settings", colour = Color(0xFF5A6478), onClick = { nav.go(Dest.Settings()) })
            PlaceRow(stringResource(R.string.mine_offline), symbol = "ui-download", colour = Color(0xFF1F9D55), onClick = { nav.go(Dest.Offline) })
            PlaceRow(stringResource(R.string.mine_layers), symbol = "ui-layers", colour = Color(0xFF14161B), onClick = { nav.go(Dest.Layers) })
            PlaceRow(stringResource(R.string.mine_data), symbol = "ui-key", colour = Color(0xFF7B4FD6), onClick = { nav.go(Dest.DataSources()) })
            PlaceRow(stringResource(R.string.mine_privacy), symbol = "ui-shield", colour = Color(0xFF14432E), onClick = { nav.go(Dest.Privacy) })
            PlaceRow(stringResource(R.string.mine_about), symbol = "ui-info", colour = Color(0xFF2E5BFF), onClick = { nav.go(Dest.About) })
            PlaceRow(stringResource(R.string.mine_welcome), symbol = "ui-flag", colour = Color(0xFFE0592A), onClick = { nav.go(Dest.Onboarding()) })
        }
        Spacer(Modifier.height(LocalBottomBarInset.current))
    }
}
