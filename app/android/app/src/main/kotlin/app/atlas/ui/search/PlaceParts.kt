package app.atlas.ui.search

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Brand
import app.atlas.places.Categories
import app.atlas.places.Fact
import app.atlas.places.FactKind
import app.atlas.places.FactState
import app.atlas.places.Localise
import app.atlas.places.OpenState
import app.atlas.providers.AggregateRating
import app.atlas.providers.Mangrove
import app.atlas.providers.WikidataEntity
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.ClockDial
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.OpenWords
import app.atlas.ui.kit.PlaceSummary
import app.atlas.ui.kit.PrimaryButton
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolTile
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.walkMinutes
import java.util.Locale

/** The sheet of a place, top to bottom. */
@Composable
internal fun PlaceSheetContent(place: Place, state: PlaceState, vm: PlaceViewModel, nav: Navigator, lang: String, here: LatLon) {
    var tags by remember { mutableStateOf(false) }
    var week by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    var confirmReview by remember { mutableStateOf<Triple<Int, String, app.atlas.providers.Service?>?>(null) }
    var confirmQuest by remember { mutableStateOf<Pair<Quest, Boolean>?>(null) }
    val summary = remember(place, here, state.now) { PlaceSummary.of(place, here, lang, state.now).copy(open = state.status) }
    val words = remember(summary, state.now) { OpenWords.of(summary, state.now) }

    Header(place, summary, state.brand, state.facts)
    Actions(place, summary, nav)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        if (state.hours != null) HoursDial(state, words, onOpen = { week = true })
        Ratings(state.ratings, Modifier.weight(1f), onWrite = { review = true })
    }
    QuestStrip(state, onAnswer = { q, yes -> confirmQuest = q to yes }, onTags = { tags = true })
    InfoCard(place, state, here)
    state.wiki?.let { WikiCard(it, state.image, lang) }
    state.brand?.let { BrandCard(it, state.brandWiki, lang) { nav.go(Dest.Brand(it.id)) } }
    ReviewCard(state.review, onWrite = { review = true })
    Sources(place, state)

    if (tags) RawTagsDialog(place) { tags = false }
    if (week && state.hours != null) {
        WeekTableDialog(state.hours, place.tags["opening_hours"].orEmpty(), state.now.toLocalDate(), state.hoursContext, state.country) { week = false }
    }
    if (review) {
        val eatery = Categories.groupOf(place.tags)?.id == "eat"
        ReviewDialog(summary.name, eatery, onReview = { s, t, how -> review = false; confirmReview = Triple(s, t, how) }, onDismiss = { review = false })
    }
    confirmReview?.let { (s, t, how) ->
        ConfirmSend(
            title = stringResource(R.string.place_review_confirm),
            text = reviewText(place, s, t, how),
            note = stringResource(R.string.place_review_note),
            confirm = stringResource(R.string.place_review_send),
            canSend = vm.canSend(),
            onConfirm = { vm.sendReview(s, t, how) },
            onDismiss = { confirmReview = null },
        )
    }
    confirmQuest?.let { (q, yes) ->
        ConfirmSend(
            title = stringResource(R.string.place_note_confirm),
            text = Quests.noteText(place, q, yes) + "\n\nvia Atlas",
            note = stringResource(R.string.place_note_note),
            confirm = stringResource(R.string.place_note_send),
            canSend = vm.canSend(),
            onConfirm = { vm.answer(q, yes) },
            onDismiss = { confirmQuest = null },
        )
    }
}

/** The logo tile (the brand's, or the category's symbol) beside the serif name, and the facts as symbols under it. */
@Composable
private fun Header(place: Place, summary: PlaceSummary, brand: Brand?, facts: List<Fact>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (brand?.logoSlug != null) BrandLogo(brand, 60.dp) else SymbolTile(summary.symbol, Color(summary.colour), size = 60.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary.name, style = Atlas.type.title, color = Atlas.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FactRow(facts)
        }
    }
}

/** The kinds of fact the symbol row leaves to other parts of the sheet. */
private val NOT_IN_ROW = setOf(
    FactKind.OPENING_HOURS, FactKind.PHONE, FactKind.WEBSITE, FactKind.WIKIPEDIA, FactKind.WIKIDATA, FactKind.LEVEL, FactKind.CAPACITY,
)

/** The facts as a row of symbols in their states: cuisine, terrace, diets, wheelchair, Wi-Fi… */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactRow(facts: List<Fact>) {
    val c = Atlas.colors
    val shown = remember(facts) { facts.filter { it.mapped && it.kind !in NOT_IN_ROW }.distinctBy { it.symbol to it.state }.take(9) }
    if (shown.isEmpty()) return
    val yes = stringResource(R.string.place_fact_yes)
    val no = stringResource(R.string.place_fact_no)
    val limited = stringResource(R.string.place_fact_limited)
    val only = stringResource(R.string.place_fact_only)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (f in shown) {
            val (tint, word) = when (f.state) {
                FactState.YES -> (if (f.kind == FactKind.CUISINE) c.eat else c.ink) to yes
                FactState.NO -> c.inkFaint to no
                FactState.LIMITED -> c.caution to limited
                FactState.ONLY -> c.open to only
                FactState.UNKNOWN -> c.inkFaint to "?"
            }
            val what = f.label ?: f.key
            val value = f.valueLabel ?: f.value?.takeIf { f.kind != FactKind.CUISINE && f.kind != FactKind.DIET }
            FactSymbol(f.symbol, struck = f.state == FactState.NO, tint = tint, description = listOfNotNull("$what: $word", value).joinToString(", "))
        }
    }
}

/** The blue route button with the walk minutes, then call, web, menu and mail. */
@Composable
private fun Actions(place: Place, summary: PlaceSummary, nav: Navigator) {
    val uri = LocalUriHandler.current
    val t = place.tags
    val phone = (t["phone"] ?: t["contact:phone"])?.substringBefore(';')?.trim()
    val web = (t["website"] ?: t["contact:website"] ?: t["url"])?.substringBefore(';')?.trim()
    val menu = t["website:menu"]?.trim()
    val mail = (t["email"] ?: t["contact:email"])?.substringBefore(';')?.trim()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(
            summary.distanceM?.let { walkMinutes(WalkTime.seconds(it)) } ?: stringResource(R.string.place_route),
            onClick = { nav.go(Dest.Route(place.location.lat, place.location.lon, summary.name)) },
            modifier = Modifier.weight(1f),
            symbol = "ui-corner-up-right",
        )
        if (phone != null) RoundAction("ui-phone", stringResource(R.string.place_call, phone), { runCatching { uri.openUri("tel:" + phone.filter { it.isDigit() || it == '+' }) } })
        if (web != null) RoundAction("ui-globe", stringResource(R.string.place_web), { runCatching { uri.openUri(if (web.startsWith("http")) web else "https://$web") } })
        if (menu != null) RoundAction("ui-receipt", stringResource(R.string.place_menu), { runCatching { uri.openUri(menu) } })
        if (mail != null && (phone == null || web == null)) RoundAction("ui-mail", stringResource(R.string.place_mail), { runCatching { uri.openUri("mailto:$mail") } })
    }
}

/** The 24-hour clock of today's hours with "→ 23:00" or "opens 7:00" under it; tap for the week. */
@Composable
private fun HoursDial(state: PlaceState, words: OpenWords?, onOpen: () -> Unit) {
    val c = Atlas.colors
    val hours = state.hours ?: return
    val spans = remember(hours, state.now) { hours.spans(state.now.toLocalDate(), state.hoursContext) }
    val (text, colour) = when (words) {
        OpenWords.Always -> stringResource(R.string.kit_open_24_7) to c.open
        is OpenWords.OpenUntil -> "→ " + untilClock(words.at) to c.open
        is OpenWords.ClosesIn -> stringResource(R.string.kit_closes_in, words.minutes) to c.caution
        OpenWords.Open -> stringResource(R.string.kit_open) to c.open
        is OpenWords.Opens -> stringResource(
            R.string.place_opens,
            if (words.sameDay) clock(words.at) else words.at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " " + clock(words.at),
        ) to c.inkSoft
        OpenWords.Closed -> stringResource(R.string.kit_closed) to c.inkSoft
        null -> stringResource(R.string.kit_hours_unknown) to c.caution
    }
    val week = stringResource(R.string.place_hours_week)
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClickLabel = week, onClick = onOpen).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ClockDial(spans, state.now.toLocalTime(), size = 124.dp, contentDescription = text)
        Text(text, style = Atlas.type.bodyStrong, color = colour, maxLines = 1)
        if (spans.any { it.state == OpenState.UNKNOWN }) Text(stringResource(R.string.place_hours_unsure), style = Atlas.type.caption, color = c.caution)
    }
}

/** The merged rating with its stars, trend and the sources that answered; honest when there is none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ratings(r: RatingsState, modifier: Modifier, onWrite: () -> Unit) {
    val c = Atlas.colors
    Column(modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (r) {
            RatingsState.Offline -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol("ui-cloud-off", tint = c.inkSoft, size = 20.dp)
                    Text(stringResource(R.string.place_ratings_offline), style = Atlas.type.bodyStrong, color = c.inkSoft)
                }
                Text(stringResource(R.string.place_ratings_offline_text), style = Atlas.type.caption, color = c.inkFaint)
            }
            RatingsState.Loading -> Text(stringResource(R.string.place_ratings_loading), style = Atlas.type.caption, color = c.inkSoft)
            is RatingsState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol("ui-alert-triangle", tint = c.alert, size = 18.dp)
                    Text(stringResource(R.string.place_ratings_failed), style = Atlas.type.bodyStrong, color = c.alert)
                }
            }
            is RatingsState.Ready -> RatingBlock(r.rating, onWrite)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingBlock(a: AggregateRating, onWrite: () -> Unit) {
    val c = Atlas.colors
    val score = a.score
    if (score == null || a.sources.isEmpty()) {
        Text(stringResource(R.string.place_ratings_none), style = Atlas.type.bodyStrong, color = c.inkSoft)
        Text(
            stringResource(R.string.place_ratings_write),
            Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onWrite).padding(vertical = 4.dp),
            style = Atlas.type.label, color = c.go,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(String.format(Locale.getDefault(), "%.1f", score), style = Atlas.type.numeral, color = c.ink)
        Column {
            Stars(score)
            Text(pluralStringResource(R.plurals.place_reviews, a.count, a.count), style = Atlas.type.caption, color = c.inkSoft)
        }
        val momentum = a.sources.mapNotNull { it.trend }.filter { it.rising }.maxOfOrNull { it.momentum }
        if (a.rising) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol("ui-flame", tint = c.eat, size = 16.dp)
                Text(momentum?.takeIf { it > 0.05 }?.let { String.format(Locale.getDefault(), "+%.1f", it) } ?: stringResource(R.string.place_rising), style = Atlas.type.label, color = c.eat)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in a.sources) {
            val stars = s.stars?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "–"
            Row(
                Modifier.clip(CircleShape).background(c.cardMuted).padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(providerName(s.provider), style = Atlas.type.caption.copy(fontWeight = FontWeight.Bold), color = c.ink)
                Text("$stars · ${s.count}", style = Atlas.type.caption, color = c.inkSoft)
            }
        }
    }
    a.disagreement?.let { d ->
        Text(
            stringResource(R.string.place_ratings_split, String.format(Locale.getDefault(), "%.1f", d.high.stars), sideName(d.high.label), String.format(Locale.getDefault(), "%.1f", d.low.stars), sideName(d.low.label)),
            style = Atlas.type.caption, color = c.caution,
        )
    }
}

@Composable
private fun sideName(label: String): String = when (label) {
    "eaten_there" -> stringResource(R.string.place_review_there)
    "takeaway" -> stringResource(R.string.place_review_takeaway)
    "delivered" -> stringResource(R.string.place_review_delivered)
    else -> providerName(label)
}

private fun providerName(id: String): String = when (id) {
    Mangrove.ID -> "Mangrove"
    "foursquare" -> "Foursquare"
    "yelp" -> "Yelp"
    "tripadvisor" -> "Tripadvisor"
    else -> id.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** The amber strip of the first unanswered quest ("📶 ?" with ✓ and ✗), and the raw tags' "{} 31" pill. */
@Composable
private fun QuestStrip(state: PlaceState, onAnswer: (Quest, Boolean) -> Unit, onTags: () -> Unit) {
    val c = Atlas.colors
    val place = state.place ?: return
    val quest = state.quests.firstOrNull { it.key !in state.answered }
    val last = state.answered.entries.lastOrNull()
    val amber = questPaper()
    val amberInk = questInk()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (quest != null) amber else c.cardMuted).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (quest != null) {
            val question = (quest.fact.label ?: quest.key) + "?"
            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = question }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Symbol(quest.fact.symbol, tint = amberInk, size = 26.dp)
                Text("?", style = Atlas.type.numeralSmall, color = amberInk)
                Text(quest.fact.label ?: quest.key, style = Atlas.type.caption, color = amberInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AnswerButton("ui-check", stringResource(R.string.place_quest_yes, question), c.open) { onAnswer(quest, true) }
            AnswerButton("ui-x", stringResource(R.string.place_quest_no, question), c.alert) { onAnswer(quest, false) }
        } else {
            val text = when (val s = last?.value) {
                Sending.Busy -> stringResource(R.string.place_note_sending)
                is Sending.Done -> stringResource(R.string.place_note_sent)
                is Sending.Failed -> stringResource(R.string.place_note_failed, s.why)
                else -> place.id?.let { stringResource(R.string.place_osm) } ?: stringResource(R.string.place_from_label)
            }
            Symbol(if (last?.value is Sending.Failed) "ui-alert-triangle" else "ui-map", tint = c.inkSoft, size = 20.dp)
            Text(text, Modifier.weight(1f), style = Atlas.type.caption, color = c.inkSoft, maxLines = 2)
        }
        val n = place.tags.size
        val d = stringResource(R.string.place_tags, n)
        Row(
            Modifier.heightIn(min = 44.dp).clip(CircleShape).background(c.pill).clickable(role = Role.Button, onClick = onTags)
                .semantics(mergeDescendants = true) { contentDescription = d }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Symbol("ui-braces", tint = c.onPill, size = 18.dp)
            Text(n.toString(), style = Atlas.type.bodyStrong, color = c.onPill)
        }
    }
}

@Composable
private fun AnswerButton(symbol: String, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Atlas.colors.card).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = tint, size = 22.dp, weight = 1.3f) }
}

/** The address, phone, level and the hours as mapped, each a row with its symbol. */
@Composable
private fun InfoCard(place: Place, state: PlaceState, here: LatLon) {
    val c = Atlas.colors
    val t = place.tags
    val rows = buildList {
        Localise.address(t)?.let { add("ui-map-pin" to it) }
        (t["phone"] ?: t["contact:phone"])?.let { add("ui-phone" to it.substringBefore(';').trim()) }
        (t["website"] ?: t["contact:website"])?.let { add("ui-globe" to it.substringBefore(';').trim().removePrefix("https://").removePrefix("http://").trimEnd('/')) }
        t["opening_hours"]?.let { add("ui-clock" to it) }
        (t["check_date"] ?: t["survey:date"])?.let { add("ui-check" to it) }
    }
    if (rows.isEmpty()) return
    Card(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), elevation = 0.dp, colour = c.cardMuted) {
        rows.forEachIndexed { i, (symbol, text) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(symbol, tint = c.inkSoft, size = 18.dp)
                val label = if (symbol == "ui-check") stringResource(R.string.place_checked, text) else text
                Text(label, style = if (symbol == "ui-clock") Atlas.type.mono else Atlas.type.body, color = c.ink, maxLines = 3)
            }
            if (i < rows.lastIndex) Hairline()
        }
    }
}

/** Wikidata's story of the place: its photo (with its credit), what it is, when built, by whom, after whom named. */
@Composable
private fun WikiCard(e: WikidataEntity, image: KeptImage?, lang: String) {
    val c = Atlas.colors
    val uri = LocalUriHandler.current
    val langs = listOf(lang, "en")
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), elevation = 0.dp, colour = c.cardMuted) {
        val bitmap = remember(image) { image?.bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
        if (bitmap != null) {
            Image(bitmap, e.label(langs), Modifier.fillMaxWidth().aspectRatio(16f / 10f), contentScale = ContentScale.Crop)
            image?.credit?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = Atlas.type.caption, color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            e.description(langs)?.let { Text(it.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }, style = Atlas.type.bodyStrong, color = c.ink) }
            e.inception?.let { WikiRow("ui-history", stringResource(R.string.place_wiki_built), it.toString()) }
            if (e.architects.isNotEmpty()) WikiRow("ui-building", stringResource(R.string.place_wiki_architect), e.architects.mapNotNull { it.label(langs) }.joinToString(", "))
            if (e.styles.isNotEmpty()) WikiRow("ui-palette", stringResource(R.string.place_wiki_style), e.styles.mapNotNull { it.label(langs) }.joinToString(", "))
            if (e.namedAfter.isNotEmpty()) WikiRow("ui-user", stringResource(R.string.place_wiki_named), e.namedAfter.mapNotNull { it.label(langs) }.joinToString(", "))
            val wiki = langs.firstNotNullOfOrNull { l -> e.sitelinks["${l}wiki"]?.let { l to it } }
            if (wiki != null) {
                Text(
                    stringResource(R.string.place_wiki_read),
                    Modifier.clip(CircleShape).clickable(role = Role.Button) { runCatching { uri.openUri("https://${wiki.first}.wikipedia.org/wiki/" + wiki.second.replace(' ', '_')) } }.padding(vertical = 4.dp),
                    style = Atlas.type.label, color = c.go,
                )
            }
        }
    }
}

@Composable
private fun WikiRow(symbol: String, label: String, value: String) {
    if (value.isBlank()) return
    val c = Atlas.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol(symbol, tint = c.inkSoft, size = 18.dp)
        Text(label, Modifier.width(96.dp), style = Atlas.type.caption, color = c.inkSoft)
        Text(value, style = Atlas.type.body, color = c.ink)
    }
}

/** "Part of …": the chain the place belongs to, and every branch one tap away. */
@Composable
private fun BrandCard(brand: Brand, wiki: WikidataEntity?, lang: String, onBranches: () -> Unit) {
    val c = Atlas.colors
    Card(padding = PaddingValues(14.dp), elevation = 0.dp, colour = c.cardMuted, onClick = onBranches, onClickLabel = stringResource(R.string.place_brand_all)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(brand, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.place_brand_part, brandName(brand)), style = Atlas.type.bodyStrong, color = c.ink)
                wiki?.description(listOf(lang, "en"))?.let { Text(it, style = Atlas.type.caption, color = c.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Symbol("ui-arrow-right", tint = c.go, size = 20.dp)
        }
    }
}

/** Write a review: open, signed by this phone, nothing sent before the user sees it. */
@Composable
private fun ReviewCard(review: Sending, onWrite: () -> Unit) {
    val c = Atlas.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.cardMuted).clickable(role = Role.Button, onClick = onWrite).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui-star", tint = Color(0xFFE29A12), size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.place_review_card), style = Atlas.type.bodyStrong, color = c.ink)
            val line = when (review) {
                Sending.Idle -> stringResource(R.string.place_review_card_text)
                Sending.Busy -> stringResource(R.string.place_review_sending)
                is Sending.Done -> stringResource(R.string.place_review_sent)
                is Sending.Failed -> stringResource(R.string.place_review_failed, review.why)
            }
            Text(line, style = Atlas.type.caption, color = if (review is Sending.Failed) c.alert else c.inkSoft, maxLines = 2)
        }
    }
}

/** Where every part of the sheet comes from, with its licence: the boards' honesty about sources. */
@Composable
private fun Sources(place: Place, state: PlaceState) {
    val c = Atlas.colors
    val rows = buildList {
        if (state.complete) {
            add("ui-map" to stringResource(R.string.place_source_osm, place.id?.toString() ?: ""))
        } else {
            add("ui-layers" to stringResource(R.string.place_source_label))
        }
        if (state.hours != null) add("ui-clock" to stringResource(R.string.place_source_hours))
        add("ui-walk" to stringResource(R.string.place_source_walk))
        state.wiki?.let { add("ui-info" to stringResource(R.string.place_source_wikidata, it.id)) }
        state.image?.let { img -> add("ui-image" to stringResource(R.string.place_source_commons, listOfNotNull(img.licence, img.credit).joinToString(" · "))) }
        (state.ratings as? RatingsState.Ready)?.rating?.sources?.forEach { s -> add("ui-star" to (s.attribution ?: providerName(s.provider))) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Kicker(stringResource(R.string.place_sources))
        for ((symbol, text) in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(symbol, tint = c.inkFaint, size = 16.dp)
                Text(text, style = Atlas.type.caption, color = c.inkSoft)
            }
        }
    }
}
