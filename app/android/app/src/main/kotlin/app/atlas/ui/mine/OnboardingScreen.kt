package app.atlas.ui.mine

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.PrimaryButton

/** Welcome: the first steps. Placeholder for the mine package. */
@Composable
fun OnboardingScreen(args: Dest.Onboarding, nav: Navigator) {
    AtlasScreen(stringResource(R.string.mine_welcome), onBack = { nav.back() }) {
        Text(stringResource(R.string.mine_welcome_text), style = Atlas.type.body)
        PrimaryButton(stringResource(R.string.mine_region_add), symbol = "ui-download", onClick = { nav.go(Dest.RegionPicker()) })
    }
}
