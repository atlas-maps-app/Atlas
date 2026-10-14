package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.BackButton
import app.atlas.ui.kit.Compass

/** Ears only, on its dark green. Placeholder for the go package. */
@Composable
fun EarsScreen(args: Dest.Ears, nav: Navigator) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E2A24)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BackButton({ nav.back() }, Modifier.align(Alignment.Start))
        Text(stringResource(R.string.go_ears_title), style = Atlas.type.displayItalic, color = Color.White)
        Compass(heading = 0f, size = 200.dp)
        Text(stringResource(R.string.go_ears_text), style = Atlas.type.body, color = Color(0xFFB9D8CF), textAlign = TextAlign.Center)
    }
}
