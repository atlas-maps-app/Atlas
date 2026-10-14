package app.atlas.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.mapview.Theme
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Segment
import app.atlas.ui.kit.SegmentedPill
import app.atlas.ui.kit.ToggleChip
import kotlinx.coroutines.launch

/** The map's look: theme and 3D. Placeholder for the mine package's layers. */
@Composable
fun LayersScreen(args: Dest.Layers, nav: Navigator) {
    val services = LocalServices.current
    val scope = rememberCoroutineScope()
    val theme by services.settings.flow(SettingsKeys.MAP_THEME, Theme.AUTO.name).collectAsStateWithLifecycle(Theme.AUTO.name)
    val with3d by services.settings.flow(SettingsKeys.MAP_3D, true).collectAsStateWithLifecycle(true)
    val themes = listOf(Theme.AUTO, Theme.DAY, Theme.NIGHT)
    AtlasScreen(stringResource(R.string.mine_layers), onBack = { nav.back() }) {
        SegmentedPill(
            listOf(Segment(stringResource(R.string.mine_theme_auto)), Segment(stringResource(R.string.mine_theme_day)), Segment(stringResource(R.string.mine_theme_night))),
            themes.indexOfFirst { it.name == theme }.coerceAtLeast(0),
            { i -> scope.launch { services.settings.set(SettingsKeys.MAP_THEME, themes[i].name) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(stringResource(R.string.mine_layers_3d), with3d, { on -> scope.launch { services.settings.set(SettingsKeys.MAP_3D, on) } }, symbol = "ui-cube")
        }
    }
}
