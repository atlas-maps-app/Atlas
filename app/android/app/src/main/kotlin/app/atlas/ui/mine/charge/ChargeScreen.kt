package app.atlas.ui.mine.charge

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.NearbyList
import app.atlas.ui.kit.rememberHere

/** Chargers around a point. Placeholder for the mine package's Charge board. */
@Composable
fun ChargeScreen(args: Dest.Charge, nav: Navigator) {
    val here = rememberHere()
    val at = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    AtlasScreen(stringResource(R.string.mine_charge), onBack = { nav.back() }) {
        NearbyList("charge", at, onPlace = { nav.go(Dest.Place.of(it)) }, radiusM = 3_000.0, emptySymbol = "ui-bolt")
    }
}
