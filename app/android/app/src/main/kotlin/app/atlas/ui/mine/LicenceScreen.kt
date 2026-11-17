package app.atlas.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A line of About & licences opened: the line whole (its name, its own notice or credit, its licence's name), then each
 * text its licence names, whole and as written, a card each under the text's title. The texts are read off the main
 * thread when the page opens; back returns to the list where it was.
 */
@Composable
fun LicenceScreen(args: Dest.Licence, nav: Navigator) {
    val context = LocalContext.current
    val line = remember(args) { Licences.line(Licences.load(context), args.what, args.licence) }
    val texts by produceState<Map<String, LicenceText>?>(null) { value = withContext(Dispatchers.IO) { Licences.loadTexts(context) } }
    BoardPage(stringResource(R.string.mine_about_licence), onBack = { nav.back() }) {
        BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                BText(args.what, bt(16f, 800, 20.5f), INK, Modifier.weight(1f), lines = Int.MAX_VALUE)
                Box(Modifier.padding(top = 1.dp).widthIn(max = 150.dp).bbox(HAIR, cr(8f)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                    BText(args.licence, bt(10f, 700, 13f), LABEL, lines = 4)
                }
            }
            val notice = line?.notice
            if (!notice.isNullOrBlank()) BText(notice, bt(12f, 400, 16f), SOFT, Modifier.padding(top = 8.dp), lines = Int.MAX_VALUE)
        }
        val all = texts
        for (id in line?.texts.orEmpty()) {
            val text = all?.get(id) ?: continue
            LicenceCard(text)
        }
    }
}

/** One licence's text in a card: its title in small caps, then its paragraphs, indented as the text indents them. */
@Composable
private fun LicenceCard(text: LicenceText) {
    val paragraphs = remember(text) { Licences.paragraphs(text.text) }
    BoardCard(padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 16.dp)) {
        CardLabel(text.title)
        Column(Modifier.padding(top = 4.dp)) {
            for (p in paragraphs) {
                // a line set far in is a title the text centres; a nearer one is an item or its run-on
                val centred = p.indent > 12
                val start = when {
                    centred -> 0.dp
                    p.indent >= 5 -> 20.dp
                    p.indent >= 1 -> 10.dp
                    else -> 0.dp
                }
                BText(
                    p.text, bt(12.5f, 400, 18f, align = if (centred) TextAlign.Center else TextAlign.Unspecified), INK,
                    Modifier.fillMaxWidth().padding(start = start, top = if (p.gap) 9.dp else 2.dp), lines = Int.MAX_VALUE,
                )
            }
        }
    }
}
