package app.atlas.ui.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.RoadSign
import app.atlas.ui.kit.SignKind

/** A vehicle's dimensions as the signs that limit them; "?" until entered. Placeholder for the go package. */
@Composable
fun VehicleScreen(args: Dest.Vehicle, nav: Navigator) {
    AtlasScreen(stringResource(R.string.go_vehicle_title), onBack = { nav.back() }, italic = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoadSign(SignKind.HEIGHT, "? m")
            RoadSign(SignKind.WIDTH, "? m")
            RoadSign(SignKind.WEIGHT, "? t")
            RoadSign(SignKind.LENGTH, "? m")
        }
        Text(stringResource(R.string.go_vehicle_text), style = Atlas.type.body, color = Atlas.colors.inkSoft)
    }
}
