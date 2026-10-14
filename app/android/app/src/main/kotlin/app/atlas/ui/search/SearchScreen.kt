package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Categories
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Chip
import app.atlas.ui.kit.HonestyFooter
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.rememberSpeechInput
import java.util.Locale

/** Search: the field with the mic, and the kinds of places to start from. Placeholder for the search package. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(args: Dest.Search, nav: Navigator) {
    var text by rememberSaveable { mutableStateOf(args.query) }
    val lang = remember { Locale.getDefault().language }
    val mic = rememberSpeechInput(onText = { text = it })
    val c = Atlas.colors
    AtlasScreen(stringResource(R.string.search_title), onBack = { nav.back() }) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(c.card).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol("ui-search", tint = c.ink)
            BasicTextField(
                text, { text = it }, Modifier.weight(1f), singleLine = true,
                textStyle = Atlas.type.bodyStrong.copy(color = c.ink), cursorBrush = SolidColor(c.go),
                decorationBox = { inner -> if (text.isEmpty()) Text(stringResource(R.string.search_hint), style = Atlas.type.body, color = c.inkFaint); inner() },
            )
            SymbolButton("ui-mic", stringResource(R.string.kit_mic), mic, size = 40.dp, tint = c.ask, elevation = 0.dp, background = c.cardMuted)
        }
        Kicker(stringResource(R.string.search_categories))
        val q = text.trim().lowercase()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val all = Categories.groups() + Categories.leaves()
            val matches = if (q.isEmpty()) emptyList() else all.filter { it.name(lang).lowercase().contains(q) || it.name("en").lowercase().contains(q) }
            for (cat in matches.ifEmpty { Categories.groups() }.take(24)) {
                Chip(cat.name(lang), symbol = cat.symbol, symbolTint = Color(cat.colour), onClick = { nav.go(Dest.Category(cat.id)) })
            }
        }
        HonestyFooter(stringResource(R.string.search_footer))
    }
}
