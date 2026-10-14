package app.atlas.ui.mine.offline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.PlaceRow
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.StateMessage
import kotlin.math.roundToInt

/** The offline regions on this phone. Placeholder for the mine package's Offline board. */
@Composable
fun OfflineScreen(args: Dest.Offline, nav: Navigator) {
    val services = LocalServices.current
    val regions by services.regions.regions.collectAsStateWithLifecycle()
    AtlasScreen(stringResource(R.string.mine_offline), onBack = { nav.back() }) {
        if (regions.isEmpty()) StateMessage("ui-cloud-off", stringResource(R.string.mine_regions_none))
        else Card {
            for (r in regions) {
                PlaceRow(
                    r.name,
                    meta = r.error ?: "%.1f MB".format(java.util.Locale.ROOT, r.bytes / 1e6),
                    metaColour = if (r.error != null) Atlas.colors.alert else Atlas.colors.inkSoft,
                    symbol = if (r.complete) "ui-cloud-check" else "ui-download",
                    colour = if (r.complete) Atlas.colors.open else Atlas.colors.caution,
                    trailing = r.progress?.let { "${(it * 100).roundToInt()}%" },
                )
                Hairline()
            }
        }
        PrimaryButton(stringResource(R.string.mine_region_add), symbol = "ui-plus", onClick = { nav.go(Dest.RegionPicker()) })
    }
}
