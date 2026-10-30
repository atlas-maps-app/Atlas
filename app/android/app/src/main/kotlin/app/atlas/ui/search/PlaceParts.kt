package app.atlas.ui.search

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.draw.clipToBounds
import app.atlas.ui.kit.CssShadow
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.atlas.ui.kit.OpenWords
import app.atlas.ui.kit.PlaceSummary
import app.atlas.ui.kit.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.hours != null) HoursDial(state, words, onOpen = { week = true })
        Ratings(state.ratings, Modifier.weight(1f), onWrite = { review = true })
    }
    QuestStrip(state, onAnswer = { q, yes -> confirmQuest = q to yes }, onTags = { tags = true })
    YourEditCard(place, onOpen = { nav.go(Dest.OsmAccount) })
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

/** The Place board's ink, greys and its warm button paper. */
private const val INK = 0xFF14161BL
private const val GREY = 0xFF464C5AL
private const val SOFT = 0xFF676D7CL
private const val WARM = 0xFFF1EEE8L

/**
 * The board's header: the brand's logo, else its tile (52 px, corners 16, in the place's colour, a 3 px darker foot)
 * with the name's first letter in Instrument Serif 34; 12 px after it the serif name (32, line height 1) and, 6 px under
 * it, the facts as 17 px symbols 10 apart.
 */
@Composable
private fun Header(place: Place, summary: PlaceSummary, brand: Brand?, facts: List<Fact>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (brand?.logoSlug != null) {
            BrandLogo(brand, 52.dp)
        } else {
            Box(
                Modifier.size(52.dp).boardBox(summary.colour.toLong() and 0xFFFFFFFFL, round(16f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x26000000)))
                val initial = summary.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: ""
                Text(initial, style = boardText(34f, 400, 34f, family = SERIF), color = Color(0xFFFFF3E6))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BoardBlock(summary.name, boardText(32f, 400, 32f, family = SERIF), INK, lines = 2)
            FactRow(facts)
        }
    }
}

/** The kinds of fact the symbol row leaves to other parts of the sheet. */
private val NOT_IN_ROW = setOf(
    FactKind.OPENING_HOURS, FactKind.PHONE, FactKind.WEBSITE, FactKind.WIKIPEDIA, FactKind.WIKIDATA, FactKind.LEVEL, FactKind.CAPACITY,
)

/**
 * The facts as the board's row of 17 px symbols 10 apart, in their states: the cuisine in terracotta, the rest in
 * #464C5A, diets in green, a "no" greyed and struck through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactRow(facts: List<Fact>) {
    val shown = remember(facts) { facts.filter { it.mapped && it.kind !in NOT_IN_ROW }.distinctBy { it.symbol to it.state }.take(9) }
    if (shown.isEmpty()) return
    val yes = stringResource(R.string.place_fact_yes)
    val no = stringResource(R.string.place_fact_no)
    val limited = stringResource(R.string.place_fact_limited)
    val only = stringResource(R.string.place_fact_only)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (f in shown) {
            val (tint, word) = when (f.state) {
                FactState.YES -> (if (f.kind == FactKind.CUISINE) 0xFFB8461A else if (f.kind == FactKind.DIET) 0xFF2F6B34 else GREY) to yes
                FactState.NO -> 0xFF9AA0AC to no
                FactState.LIMITED -> 0xFF8A5A00 to limited
                FactState.ONLY -> 0xFF2F6B34 to only
                FactState.UNKNOWN -> 0xFF9AA0AC to "?"
            }
            val what = f.label ?: f.key
            val value = f.valueLabel ?: f.value?.takeIf { f.kind != FactKind.CUISINE && f.kind != FactKind.DIET }
            FactSymbol(f.symbol, struck = f.state == FactState.NO, tint = Color(tint), description = listOfNotNull("$what: $word", value).joinToString(", "), size = 17.dp, paper = Color(SHEET_PAPER), pad = 0.dp)
        }
    }
}

/**
 * A web address from a tag, ready to open: http(s) only (a tag is anyone's text: no `intent:`, `file:` or `javascript:`),
 * with https added when the tag has no scheme; the first of several given with ";"; null when there is nothing safe.
 */
internal fun webUrl(raw: String?): String? {
    val t = raw?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && !it.any(Char::isWhitespace) } ?: return null
    return when {
        t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) -> t
        "://" in t || UNSAFE_SCHEME.containsMatchIn(t) -> null
        else -> "https://$t"
    }
}

private val UNSAFE_SCHEME = Regex("^(javascript|intent|file|content|data|vbscript|tel|mailto|sms|market):", RegexOption.IGNORE_CASE)

/**
 * The board's actions, 8 px apart: the blue route button (52 px, the Go symbol 8 px before the walk minutes in 16 / 600)
 * taking the room left, then call, web, menu and mail as 52 px discs of pale blue.
 */
@Composable
private fun Actions(place: Place, summary: PlaceSummary, nav: Navigator) {
    val uri = LocalUriHandler.current
    val t = place.tags
    val phone = (t["phone"] ?: t["contact:phone"])?.substringBefore(';')?.trim()
    val web = webUrl(t["website"] ?: t["contact:website"] ?: t["url"])
    val menu = webUrl(t["website:menu"])
    val mail = (t["email"] ?: t["contact:email"])?.substringBefore(';')?.trim()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        val route = stringResource(R.string.place_route)
        Row(
            Modifier.weight(1f).height(52.dp).boardBox(0xFF2E5BFF, round(26f))
                .clickable(role = Role.Button, onClickLabel = route) { nav.go(Dest.Route(place.location.lat, place.location.lon, summary.name)) },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoardSymbol("tab.go", 21f, 0xFFFFFFFF)
            BoardLine(summary.distanceM?.let(::walkOrDistance) ?: route, boardText(16f, 600, 20.5f, tnum = true), 0xFFFFFFFF)
        }
        if (phone != null) RoundAction("ui.phone", stringResource(R.string.place_call, phone), { runCatching { uri.openUri("tel:" + phone.filter { it.isDigit() || it == '+' }) } })
        if (web != null) RoundAction("ui.web", stringResource(R.string.place_web), { runCatching { uri.openUri(web) } })
        if (menu != null) RoundAction("ui-receipt", stringResource(R.string.place_menu), { runCatching { uri.openUri(menu) } })
        if (mail != null && (phone == null || web == null)) RoundAction("ui-mail", stringResource(R.string.place_mail), { runCatching { uri.openUri("mailto:$mail") } })
    }
}

/** The board's dial: the 24-hour clock of today's hours (108 px) with "→ 23:00" or "opens 7:00" 4 px under it; tap for the week. */
@Composable
private fun HoursDial(state: PlaceState, words: OpenWords?, onOpen: () -> Unit) {
    val hours = state.hours ?: return
    val spans = remember(hours, state.now) { hours.spans(state.now.toLocalDate(), state.hoursContext) }
    val (text, colour) = when (words) {
        OpenWords.Always -> stringResource(R.string.kit_open_24_7) to 0xFF178A4E
        is OpenWords.OpenUntil -> "→ " + untilClock(words.at) to 0xFF178A4E
        is OpenWords.ClosesIn -> stringResource(R.string.kit_closes_in, words.minutes) to 0xFF8A5A00
        OpenWords.Open -> stringResource(R.string.kit_open) to 0xFF178A4E
        is OpenWords.Opens -> stringResource(
            R.string.place_opens,
            if (words.sameDay) clock(words.at) else words.at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()) + " " + clock(words.at),
        ) to SOFT
        OpenWords.Closed -> stringResource(R.string.kit_closed) to SOFT
        null -> stringResource(R.string.kit_hours_unknown) to 0xFF8A5A00
    }
    val week = stringResource(R.string.place_hours_week)
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClickLabel = week, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ClockDial(spans, state.now.toLocalTime(), size = 108.dp, contentDescription = text)
        BoardLine(text, boardText(12f, 600, 15.5f, tnum = true), colour)
        if (spans.any { it.state == OpenState.UNKNOWN }) BoardLine(stringResource(R.string.place_hours_unsure), boardText(11f, 400, 14f), 0xFF8A5A00)
    }
}

/** The merged rating with its stars, trend and the sources that answered; honest when there is none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ratings(r: RatingsState, modifier: Modifier, onWrite: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (r) {
            RatingsState.Offline -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoardSymbol("ui-cloud-off", 18f, SOFT)
                    BoardLine(stringResource(R.string.place_ratings_offline), boardText(14f, 700, 18f), GREY, lines = 2)
                }
                BoardLine(stringResource(R.string.place_ratings_offline_text), boardText(12f, 400, 15.5f), SOFT, lines = 3)
            }
            RatingsState.Loading -> BoardLine(stringResource(R.string.place_ratings_loading), boardText(12f, 400, 15.5f), SOFT)
            is RatingsState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoardSymbol("ui-alert-triangle", 18f, 0xFFB03A3A)
                    BoardLine(stringResource(R.string.place_ratings_failed), boardText(14f, 700, 18f), 0xFFB03A3A, lines = 2)
                }
            }
            is RatingsState.Ready -> RatingBlock(r.rating, onWrite)
        }
    }
}

/**
 * The board's rating: the score in 30 / 700, five 14 px stars 1 px apart in amber 8 px after it, and the trend at the
 * right in terracotta (12 / 600); under it how many reviews and the sources that answered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingBlock(a: AggregateRating, onWrite: () -> Unit) {
    val score = a.score
    if (score == null || a.sources.isEmpty()) {
        BoardLine(stringResource(R.string.place_ratings_none), boardText(14f, 700, 18f), GREY, lines = 2)
        BoardLine(
            stringResource(R.string.place_ratings_write), boardText(12f, 700, 15.5f), 0xFF2E5BFF,
            Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onWrite).padding(vertical = 4.dp),
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BoardLine(String.format(Locale.getDefault(), "%.1f", score), boardText(30f, 700, 30f, tnum = true), INK)
        BoardStars(score)
        Spacer(Modifier.weight(1f))
        val momentum = a.sources.mapNotNull { it.trend }.filter { it.rising }.maxOfOrNull { it.momentum }
        if (a.rising) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                BoardSymbol("fact.trending", 13f, 0xFFB8461A)
                BoardLine(momentum?.takeIf { it > 0.05 }?.let { String.format(Locale.getDefault(), "+%.1f", it) } ?: stringResource(R.string.place_rising), boardText(12f, 600, 15.5f), 0xFFB8461A)
            }
        }
    }
    BoardLine(pluralStringResource(R.plurals.place_reviews, a.count, a.count), boardText(12f, 400, 15.5f), SOFT)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in a.sources) {
            val stars = s.stars?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "–"
            Row(
                Modifier.height(24.dp).boardBox(WARM, round(12f)).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardLine(providerName(s.provider), boardText(12f, 700, 15.5f), INK)
                BoardLine("$stars · ${s.count}", boardText(12f, 400, 15.5f, tnum = true), SOFT)
            }
        }
    }
    a.disagreement?.let { d ->
        BoardLine(
            stringResource(R.string.place_ratings_split, String.format(Locale.getDefault(), "%.1f", d.high.stars), sideName(d.high.label), String.format(Locale.getDefault(), "%.1f", d.low.stars), sideName(d.low.label)),
            boardText(12f, 400, 15.5f), 0xFF8A5A00, lines = 3,
        )
    }
}

/** Five of the board's 14 px stars 1 px apart, amber lit to [value] of 5 over a pale track. */
@Composable
private fun BoardStars(value: Double) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 0 until 5) {
            val lit = (value - i).toFloat().coerceIn(0f, 1f)
            Box(Modifier.size(14.dp)) {
                BoardSymbol("ui.star", 14f, 0xFFE3DED4)
                if (lit > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(lit).clipToBounds()) {
                    BoardSymbol("ui.star", 14f, 0xFFF2A71B, Modifier.wrapContentWidth(Alignment.Start, unbounded = true))
                }
            }
        }
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

/**
 * The board's amber strip of the first unanswered quest (#FFF7E0 in a 1 px #F3E2B0 ring, corners 20, 10 px in, 14 at
 * the left): its 22 px symbol and "?" (20 / 700) in amber ink, then ✓ and ✗ on 44 px white discs, and the raw tags'
 * "{} 31" ink pill (Geist Mono 13 / 500).
 */
@Composable
private fun QuestStrip(state: PlaceState, onAnswer: (Quest, Boolean) -> Unit, onTags: () -> Unit) {
    val place = state.place ?: return
    // An answer that failed to go out leaves its question open: it can be asked again.
    val quest = state.openQuest
    val last = state.answered.entries.lastOrNull()
    val failed = quest?.let { state.answered[it.key] as? Sending.Failed }
    val amberInk = 0xFF8A5A00
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .boardBox(if (quest != null) 0xFFFFF7E0 else WARM, round(20f), border = if (quest != null) ring(1f, 0xFFF3E2B0) else null)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (quest != null) {
                val question = (quest.fact.label ?: quest.key) + "?"
                Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = question }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BoardSymbol(quest.fact.symbol, 22f, amberInk)
                    BoardLine("?", boardText(20f, 700, 26f), amberInk)
                }
                AnswerButton("ui.check", 20f, stringResource(R.string.place_quest_yes, question), 0xFF178A4E) { onAnswer(quest, true) }
                AnswerButton("ui.close", 18f, stringResource(R.string.place_quest_no, question), 0xFFB03A3A) { onAnswer(quest, false) }
            } else {
                val text = when (val s = last?.value) {
                    Sending.Busy -> stringResource(R.string.place_note_sending)
                    is Sending.Done -> stringResource(R.string.place_note_sent)
                    is Sending.Failed -> stringResource(R.string.place_note_failed, s.why)
                    else -> place.id?.let { stringResource(R.string.place_osm) } ?: stringResource(R.string.place_from_label)
                }
                BoardSymbol(if (last?.value is Sending.Failed) "ui-alert-triangle" else "ui-map", 20f, SOFT)
                BoardLine(text, boardText(12f, 400, 15.5f), GREY, Modifier.weight(1f), lines = 2)
            }
            val n = place.tags.size
            val d = stringResource(R.string.place_tags, n)
            Row(
                Modifier.height(44.dp).boardBox(INK, round(22f)).clickable(role = Role.Button, onClick = onTags)
                    .semantics(mergeDescendants = true) { contentDescription = d }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BoardSymbol("ui.code", 16f, 0xFFFFFFFF)
                BoardLine(n.toString(), boardText(13f, 500, 17f, family = GEIST_MONO), 0xFFFFFFFF)
            }
        }
        if (failed != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                BoardSymbol("ui-alert-triangle", 16f, 0xFFB03A3A)
                BoardLine(stringResource(R.string.place_note_failed, failed.why), boardText(12f, 400, 15.5f), 0xFFB03A3A, lines = 3)
            }
        }
    }
}

@Composable
private fun AnswerButton(symbol: String, glyph: Float, description: String, tint: Long, onClick: () -> Unit) {
    BoardRoundButton(symbol, description, onClick, glyph = glyph, tint = tint, shadows = listOf(CssShadow(0f, 1f, 3f, 0f, 0x1A000000)))
}

/** The address, phone, level and the hours as mapped, each a row with its symbol. */
@Composable
private fun InfoCard(place: Place, state: PlaceState, here: LatLon) {
    val t = place.tags
    val rows = buildList {
        Localise.address(t)?.let { add("ui-map-pin" to it) }
        (t["phone"] ?: t["contact:phone"])?.let { add("ui-phone" to it.substringBefore(';').trim()) }
        (t["website"] ?: t["contact:website"])?.let { add("ui-globe" to it.substringBefore(';').trim().removePrefix("https://").removePrefix("http://").trimEnd('/')) }
        t["opening_hours"]?.let { add("ui-clock" to it) }
        (t["check_date"] ?: t["survey:date"])?.let { add("ui-check" to it) }
    }
    if (rows.isEmpty()) return
    Card(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), elevation = 0.dp, colour = Color(WARM), shape = RoundedCornerShape(20.dp)) {
        rows.forEachIndexed { i, (symbol, text) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(symbol, tint = Color(GREY), size = 18.dp)
                val label = if (symbol == "ui-check") stringResource(R.string.place_checked, text) else text
                Text(label, style = if (symbol == "ui-clock") boardText(13f, 500, 17f, family = GEIST_MONO) else boardText(14f, 400, 18f), color = Color(INK), maxLines = 3)
            }
            if (i < rows.lastIndex) Hairline()
        }
    }
}

/** Wikidata's story of the place: its photo (with its credit), what it is, when built, by whom, after whom named. */
@Composable
private fun WikiCard(e: WikidataEntity, image: KeptImage?, lang: String) {
    val uri = LocalUriHandler.current
    val langs = listOf(lang, "en")
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp), elevation = 0.dp, colour = Color(WARM), shape = RoundedCornerShape(20.dp)) {
        // Decoded off the main thread: a photo is a few hundred kilobytes of JPEG.
        val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, image) {
            value = image?.bytes?.let { bytes ->
                withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
            }
        }
        bitmap?.let { bitmap ->
            Image(bitmap, e.label(langs), Modifier.fillMaxWidth().aspectRatio(16f / 10f), contentScale = ContentScale.Crop)
            image?.credit?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = boardText(12f, 400, 15.5f), color = Color(SOFT), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            e.description(langs)?.let { Text(it.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }, style = boardText(14f, 700, 18f), color = Color(INK)) }
            e.inception?.let { WikiRow("ui-history", stringResource(R.string.place_wiki_built), it.toString()) }
            if (e.architects.isNotEmpty()) WikiRow("ui-building", stringResource(R.string.place_wiki_architect), e.architects.mapNotNull { it.label(langs) }.joinToString(", "))
            if (e.styles.isNotEmpty()) WikiRow("ui-palette", stringResource(R.string.place_wiki_style), e.styles.mapNotNull { it.label(langs) }.joinToString(", "))
            if (e.namedAfter.isNotEmpty()) WikiRow("ui-user", stringResource(R.string.place_wiki_named), e.namedAfter.mapNotNull { it.label(langs) }.joinToString(", "))
            val wiki = langs.firstNotNullOfOrNull { l -> e.sitelinks["${l}wiki"]?.let { l to it } }
            if (wiki != null) {
                Text(
                    stringResource(R.string.place_wiki_read),
                    Modifier.clip(CircleShape).clickable(role = Role.Button) { runCatching { uri.openUri("https://${wiki.first}.wikipedia.org/wiki/" + wiki.second.replace(' ', '_')) } }.padding(vertical = 4.dp),
                    style = boardText(12f, 700, 15.5f), color = Color(0xFF2E5BFF),
                )
            }
        }
    }
}

@Composable
private fun WikiRow(symbol: String, label: String, value: String) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Symbol(symbol, tint = Color(GREY), size = 18.dp)
        Text(label, Modifier.width(96.dp), style = boardText(12f, 400, 15.5f), color = Color(GREY))
        Text(value, style = boardText(14f, 400, 18f), color = Color(INK))
    }
}

/** "Part of …": the chain the place belongs to, and every branch one tap away. */
@Composable
private fun BrandCard(brand: Brand, wiki: WikidataEntity?, lang: String, onBranches: () -> Unit) {
    Card(padding = PaddingValues(14.dp), elevation = 0.dp, colour = Color(WARM), shape = RoundedCornerShape(20.dp), onClick = onBranches, onClickLabel = stringResource(R.string.place_brand_all)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(brand, 40.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.place_brand_part, brandName(brand)), style = boardText(14f, 700, 18f), color = Color(INK))
                wiki?.description(listOf(lang, "en"))?.let { Text(it, style = boardText(12f, 400, 15.5f), color = Color(GREY), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Symbol("ui-arrow-right", tint = Color(0xFF2E5BFF), size = 20.dp)
        }
    }
}

/** Write a review: open, signed by this phone, nothing sent before the user sees it. */
@Composable
private fun ReviewCard(review: Sending, onWrite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(WARM)).clickable(role = Role.Button, onClick = onWrite).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("ui.star", tint = Color(0xFFF2A71B), size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.place_review_card), style = boardText(14f, 700, 18f), color = Color(INK))
            val line = when (review) {
                Sending.Idle -> stringResource(R.string.place_review_card_text)
                Sending.Busy -> stringResource(R.string.place_review_sending)
                is Sending.Done -> stringResource(R.string.place_review_sent)
                is Sending.Failed -> stringResource(R.string.place_review_failed, review.why)
            }
            Text(line, style = boardText(12f, 400, 15.5f), color = if (review is Sending.Failed) Color(0xFFB03A3A) else Color(GREY), maxLines = 2)
        }
    }
}

/** Where every part of the sheet comes from, with its licence: the boards' honesty about sources. */
@Composable
private fun Sources(place: Place, state: PlaceState) {
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
        BoardLine(stringResource(R.string.place_sources).uppercase(Locale.getDefault()), boardText(12f, 800, 15.5f, spacing = 1.44f), SOFT)
        for ((symbol, text) in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(symbol, tint = Color(SOFT), size = 16.dp)
                Text(text, style = boardText(12f, 400, 15.5f), color = Color(GREY))
            }
        }
    }
}
