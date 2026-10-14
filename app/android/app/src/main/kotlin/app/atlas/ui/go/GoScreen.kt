package app.atlas.ui.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.Kicker

/** The Go tab: where to, and how. Placeholder for the go package. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoScreen(args: Dest.Go, nav: Navigator) {
    AtlasScreen(stringResource(R.string.go_title)) {
        Chip(stringResource(R.string.go_where), symbol = "ui-search", onClick = { nav.go(Dest.Search()) })
        Kicker(stringResource(R.string.go_how))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in GoMode.entries) {
                Chip(stringResource(m.label), symbol = m.symbol, symbolTint = Atlas.colors.go, onClick = { nav.go(Dest.Route(mode = m.mode.name)) })
            }
            Chip(null, symbol = "ui-headphones", contentDescription = stringResource(R.string.go_ears_title), onClick = { nav.go(Dest.Ears) })
            Chip(null, symbol = "ui-bolt", symbolTint = Atlas.colors.open, contentDescription = "EV", onClick = { nav.go(Dest.Charge()) })
        }
        Spacer(Modifier.height(LocalBottomBarInset.current))
    }
}
