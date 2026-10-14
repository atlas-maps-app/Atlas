package app.atlas.ui.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.ToggleChip
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance

/** The route planner: from, to, how. Placeholder for the go package. */
@Composable
fun RouteScreen(args: Dest.Route, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val from = args.from ?: here
    var mode by rememberSaveable { mutableStateOf(args.mode ?: GoMode.WALK.mode.name) }
    AtlasScreen(stringResource(R.string.go_route_title), onBack = { nav.back() }) {
        Card {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui-locate", tint = Atlas.colors.go)
                Text(args.fromName ?: stringResource(R.string.go_from_here), style = Atlas.type.bodyStrong)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui-map-pin", tint = Atlas.colors.alert)
                Text(args.toName ?: stringResource(R.string.go_pick_destination), style = Atlas.type.bodyStrong)
            }
            args.to?.let { Text(stringResource(R.string.go_straight, shortDistance(Geo.distance(from, it))), style = Atlas.type.caption, color = Atlas.colors.inkSoft) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in GoMode.entries.take(4)) {
                ToggleChip(null, mode == m.mode.name, { mode = m.mode.name }, symbol = m.symbol, contentDescription = stringResource(m.label))
            }
        }
        val to = args.to
        PrimaryButton(
            stringResource(R.string.go_start),
            symbol = "ui-navigation",
            enabled = to != null,
            onClick = { if (to != null) nav.go(Dest.Navigate(to.lat, to.lon, args.toName, mode, demo = services.location.isDemo)) },
        )
    }
}
