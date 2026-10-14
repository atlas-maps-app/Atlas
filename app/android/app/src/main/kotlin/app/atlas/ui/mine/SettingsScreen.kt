package app.atlas.ui.mine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.services.Units
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.Segment
import app.atlas.ui.kit.SegmentedPill
import kotlinx.coroutines.launch

/** Your Atlas: units for now. Placeholder for the mine package's settings. */
@Composable
fun SettingsScreen(args: Dest.Settings, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val units by services.settings.units.collectAsStateWithLifecycle(Units.METRIC)
    AtlasScreen(stringResource(R.string.mine_settings_title), onBack = { nav.back() }, italic = true) {
        Kicker(stringResource(R.string.mine_units))
        SegmentedPill(
            listOf(Segment("km"), Segment("mi"), Segment("nm")),
            units.ordinal,
            { i -> scope.launch { services.settings.set(SettingsKeys.UNITS, Units.entries[i].name) } },
        )
    }
}
