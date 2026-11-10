package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.atlas.R
import app.atlas.providers.WikipediaArticle
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.SymbolButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A place's Wikipedia article read in the app (the user, 3 November: "More details on places, more data and a
 * wikipedia viewer"): its title and what it is, how long a read, the lead and the sections worth reading, then where
 * it comes from and its licence, the article on Wikipedia a tap away. Where it cannot be had now (offline, Wikipedia
 * switched off), the way to it in the browser.
 */
@Composable
internal fun WikiReader(url: String, load: suspend () -> WikipediaArticle?, onDismiss: () -> Unit) {
    val c = Atlas.colors
    val uri = LocalUriHandler.current
    val read by produceState<Result<WikipediaArticle?>?>(null) { value = withContext(Dispatchers.IO) { runCatching { load() } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.paper).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Wikipedia", Modifier.weight(1f), style = Atlas.type.kicker, color = c.inkFaint)
                SymbolButton("ui-x", stringResource(R.string.kit_close), onDismiss, size = 40.dp, elevation = 0.dp, background = c.cardMuted)
            }
            val article = read?.getOrNull()
            when {
                read == null -> Looking()
                article == null -> StateMessage(
                    "ui-book", stringResource(R.string.place_wiki_none), Modifier.padding(16.dp), text = read?.exceptionOrNull()?.message,
                    action = { PrimaryButton(stringResource(R.string.place_wiki_read), { runCatching { uri.openUri(url) } }) },
                )
                else -> Article(article) { runCatching { uri.openUri(article.url) } }
            }
        }
    }
}

/** The article itself: title, what it is and how long a read, the lead, the sections, its source and licence. */
@Composable
private fun Article(a: WikipediaArticle, onOpen: () -> Unit) {
    val c = Atlas.colors
    val minutes = (a.words + WORDS_A_MINUTE - 1) / WORDS_A_MINUTE
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.title, Modifier.semantics { heading() }, style = indexTitle(25f), color = c.ink)
                a.description?.let { Text(it.replaceFirstChar { ch -> ch.titlecase() }, style = Atlas.type.body, color = c.inkSoft) }
                Text(stringResource(R.string.place_wiki_minutes, minutes), style = Atlas.type.caption, color = c.inkFaint)
            }
        }
        items(a.lead) { p -> Text(p, style = Atlas.type.body, color = c.ink) }
        for (s in a.sections) {
            item(key = "h:" + s.title + s.level) {
                Text(s.title, Modifier.padding(top = 10.dp).semantics { heading() }, style = if (s.level <= 2) Atlas.type.headline else Atlas.type.bodyStrong, color = c.ink)
            }
            items(s.paragraphs) { p -> Text(p, style = Atlas.type.body, color = c.ink) }
        }
        item(key = "source") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.place_wiki_licence) + (a.edited?.take(10)?.let { " · $it" } ?: ""), style = Atlas.type.caption, color = c.inkFaint)
                Text(
                    stringResource(R.string.place_wiki_read),
                    Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onOpen).padding(vertical = 4.dp),
                    style = Atlas.type.label, color = c.go,
                )
            }
        }
    }
}

/** How many words one reads in a minute, for "a 4-minute read". */
private const val WORDS_A_MINUTE = 220
