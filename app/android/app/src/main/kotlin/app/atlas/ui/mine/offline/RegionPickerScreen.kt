package app.atlas.ui.mine.offline

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.RegionRequest
import app.atlas.tiles.RegionPlanner
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.rememberHere

/** Takes the area around a point offline (5 km around). Placeholder for the mine package's picker. */
@Composable
fun RegionPickerScreen(args: Dest.RegionPicker, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val centre = if (args.lat != null && args.lon != null) LatLon(args.lat, args.lon) else here
    val box = remember(centre) { BBox.around(centre, 5_000.0) }
    val plan = remember(box) { RegionPlanner.plan(box) }
    val name = stringResource(R.string.mine_region_name)
    AtlasScreen(stringResource(R.string.mine_region_pick), onBack = { nav.back() }) {
        Card {
            Text("%.0f MB · %d".format(java.util.Locale.ROOT, plan.estimatedBytes / 1e6, plan.tileCount), style = Atlas.type.numeralSmall)
            Text(box.toString(), style = Atlas.type.mono, color = Atlas.colors.inkSoft)
        }
        PrimaryButton(stringResource(R.string.mine_region_download), symbol = "ui-download", onClick = {
            services.regions.download(RegionRequest(name, box))
            nav.replace(Dest.Offline)
        })
    }
}
