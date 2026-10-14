package app.atlas.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Symbol

/** On this phone: what is kept and what goes online. Placeholder for the mine package. */
@Composable
fun PrivacyScreen(args: Dest.Privacy, nav: Navigator) {
    AtlasScreen(stringResource(R.string.mine_privacy), onBack = { nav.back() }, italic = true) {
        Card(colour = Color(0xFF14432E)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui-shield", tint = Color(0xFF7BE08F), size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mine_privacy_head), style = Atlas.type.name, color = Color.White)
                    Text(stringResource(R.string.mine_privacy_text), style = Atlas.type.caption, color = Color(0xFFCDE8D7))
                }
            }
        }
    }
}
