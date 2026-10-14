package app.atlas.ui.mine.charge

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.BatteryGauge
import app.atlas.ui.kit.StateMessage

/** A route with charging stops. Placeholder for the mine package. */
@Composable
fun ChargingRouteScreen(args: Dest.ChargingRoute, nav: Navigator) {
    AtlasScreen(args.toName ?: stringResource(R.string.mine_charging_route), onBack = { nav.back() }) {
        BatteryGauge(0.8f)
        StateMessage("ui-plug-zap", stringResource(R.string.mine_charging_route), text = stringResource(R.string.mine_charging_route_text))
    }
}
