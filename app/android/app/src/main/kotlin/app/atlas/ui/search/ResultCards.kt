package app.atlas.ui.search

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.Services
import app.atlas.model.Place
import app.atlas.nav.LocalServices
import app.atlas.places.CategoryNode
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.search.ResultHit
import app.atlas.search.ResultItem
import app.atlas.ui.eat.CuisineHouse
import app.atlas.ui.eat.Dish
import app.atlas.ui.eat.EatPlace
import app.atlas.ui.eat.dishOf
import app.atlas.ui.eat.drawPlate
import app.atlas.ui.eat.houseMotif
import app.atlas.ui.kit.BrandSigns
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.markOf
import app.atlas.ui.kit.cssShadow
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The places found, as large cards to slide through (the user, 5 November: "Make search results possible to be viewed
 * as large slideable cards with pictures if there are any or placeholders"), as the Eat map's carousel and the
 * EatGoogle board show real places: one card in view with the edge of the next, and the map above choosing that card's
 * pin and bringing it into view ([focus], [onFocus]); a pin tapped brings its card. A card opens its place, as a row
 * does. The order is the rows' own, a brand's branches one card under its nearest.
 */
@Composable
internal fun ResultCards(items: List<ResultItem>, w: RowWays, view: CategoryNode?, focus: String?, onFocus: (String) -> Unit) {
    val state = rememberLazyListState()
    val keys = remember(items) { items.map { it.first.record.place.key } }
    val report by rememberUpdatedState(onFocus)
    // the card in view once the row comes to rest: the one most of whose width is on screen
    LaunchedEffect(state, keys) {
        snapshotFlow { state.isScrollInProgress to inView(state) }
            .filter { !it.first }
            .map { it.second }
            .distinctUntilChanged()
            .collect { i -> keys.getOrNull(i)?.let(report) }
    }
    // a pin tapped on the map: its card slides into view
    LaunchedEffect(focus, keys) {
        val i = keys.indexOf(focus)
        if (i >= 0 && i != inView(state)) state.animateScrollToItem(i)
    }
    LazyRow(
        Modifier.fillMaxWidth().testTag(CARDS_TAG),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
    ) {
        itemsIndexed(items, key = { _, it -> it.first.record.place.key }) { _, item ->
            ResultCard(item, w, view, Modifier.fillParentMaxWidth(CARD_SHARE))
        }
    }
}

/** The index of the card most of whose width is in view; the first before the row is laid out. */
private fun inView(state: androidx.compose.foundation.lazy.LazyListState): Int {
    val info = state.layoutInfo
    val shown = info.visibleItemsInfo
    if (shown.isEmpty()) return state.firstVisibleItemIndex
    return shown.maxBy { minOf(it.offset + it.size, info.viewportEndOffset) - maxOf(it.offset, info.viewportStartOffset) }.index
}

/**
 * One place as a card: its picture ([CardPicture]) over its name and how far, how it stands now (open or not, "≈"
 * where its hours are guessed) and what its row says of it, and its symbols. A place in a cuisine's house wears it (the
 * HouseCards board): the house's tint, its motif for the picture, the name in its typeface and ink.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultCard(item: ResultItem, w: RowWays, view: CategoryNode?, modifier: Modifier) {
    val c = indexColors()
    val h = item.first
    val p = h.record.place
    val lang = w.lang
    val line = remember(h, w.now) { PlaceLine.of(p, null, lang, w.now, h.open, w.nameOf(h), h.metres) }
    val eat = remember(p.key) { EatPlace.of(p, p.location, lang) }
    val house = remember(p.key) { eat?.let { CuisineHouse.of(it) } }
    val arrival = arrivalWords(h.open, h.minutes, w.now)
    val tone = arrival?.second ?: if (h.open == null) Tone.UNKNOWN else line.tone
    val words = (arrival?.first ?: openWords(line))?.let { if (!h.hoursConfirmed) "≈ $it" else it }
    val kind = h.node?.takeIf { it.id != w.node }?.let(w::kindName) ?: line.summary.kind.takeIf { h.node == null }
    val worded = h.tells.filter { it.text != null }.mapNotNull { it.text }
    val symbols = h.tells.filter { it.text == null }.take(CARD_SYMBOLS)
    val kept = w.kept[p.key]
    val shape = RoundedCornerShape(24.dp)
    // a house's light tint by day; at night the card's own colour, the house's ink lightened to read on it
    val fill = house?.takeUnless { c.dark }?.let { Color(it.tint) } ?: c.card
    val nameStyle = house?.nameStyle(24f)?.let { s -> if (c.dark) s.copy(color = lerp(Color(house.ink), Color.White, 0.6f)) else s } ?: indexTitle(21f).copy(color = c.ink)
    Column(
        modifier
            .cssShadow(shape, y = 4.dp, blur = 16.dp, colour = c.shadow)
            .cssShadow(shape, y = 0.5.dp, blur = 0.5.dp, colour = c.hair)
            .clip(shape)
            .background(fill)
            .combinedClickable(onClick = { w.onPlace(p) }, onLongClick = { h.node?.let { w.onExplain(explained(it, h)) } })
            .testTag(PLACE_TAG),
    ) {
        CardPicture(h, eat, house, view, Modifier.fillMaxWidth().height(PICTURE))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    line.summary.name, Modifier.weight(1f), style = nameStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = nameStyle.fontSize),
                )
                Reach(h.minutes, h.metres)
            }
            Text(
                statusOf(words, tone, staleMark(h), listOfNotNull(kind) + worded),
                style = INDEX_META.copy(fontSize = 13.5.sp), color = c.soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val plan = w.onPlan?.takeIf { w.plans(p) }
            Row(if (plan != null) Modifier.heightIn(min = 20.dp) else Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                kept?.let { Symbol("ui.bookmark", tint = Color(it), size = 16.dp, contentDescription = stringResource(R.string.lists_default)) }
                for (t in symbols) Symbol(t.symbol, tint = house?.takeUnless { c.dark }?.let { Color(it.ink) } ?: c.soft, size = 17.dp, contentDescription = t.key.label(lang))
                Spacer(Modifier.weight(1f))
                // a deck's other places near, under its nearest: a brand's branches, a kind's places with no name of their own
                (item as? ResultItem.Deck)?.takeIf { it.hits.size > 1 }?.let { b ->
                    Text(
                        stringResource(R.string.find_cards_branches, b.hits.size - 1), Modifier.clip(CircleShape).background(c.tray).padding(horizontal = 9.dp, vertical = 2.dp),
                        style = INDEX_META, color = c.soft, maxLines = 1,
                    )
                }
                plan?.let { PlanKey { it(p) } }
            }
        }
    }
}

/**
 * What stands at a card's top: the place's own photo where the phone keeps one (as its page shows it, its licence's
 * credit on it), else its cuisine's house, a chain's fascia (its sign, as its shop front shows it), a plate of what it
 * serves, or what its kind shows ([artOf]) in its family's colour (the search's cards). Where the picture does not say
 * what the place is, its pin does, with the mark the map draws it with ([markOf]).
 */
@Composable
private fun CardPicture(h: ResultHit, eat: EatPlace?, house: CuisineHouse?, view: CategoryNode?, modifier: Modifier) {
    val p = h.record.place
    val photo = rememberPhoto(p)
    val dish = remember(p.key) { eat?.let { dishOf(it, null) }?.takeIf { it != Dish.EMPTY } }
    val node = h.node ?: view
    val (mark, colour) = remember(p.key) { markOf(p) }
    // a chain known by its logo and its colours, as the street knows it
    val chain = remember(p.key) { Brands.forTags(p.tags)?.takeIf { it.logoSlug != null && it.colour != null } }
    // the place's own symbol large, unless it wears a logo; its pin would only say it again
    val art = remember(p.key, node?.id) { (node?.let { artOf(it) } ?: Scene(Motif.CONTOURS)).let { a -> if (a is Emblem && !mark.startsWith("logo-")) Emblem(mark) else a } }
    Box(modifier) {
        when {
            photo != null -> Image(photo.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            house != null -> Canvas(Modifier.fillMaxSize()) { houseMotif(house) }
            chain != null -> Fascia(chain, Modifier.fillMaxSize())
            dish != null -> Canvas(Modifier.fillMaxSize()) { drawPlate(dish, p.key.hashCode()) }
            else -> Box(Modifier.fillMaxSize().bandSurface(Color(colour), art))
        }
        if (photo != null || (house == null && chain == null && dish == null && art != Emblem(mark))) {
            KindPin(mark, colour, Modifier.align(Alignment.BottomStart).padding(12.dp), size = 40.dp)
        }
        photo?.credit?.let { credit ->
            Text(
                credit, Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp).width(180.dp),
                style = INDEX_META.copy(fontSize = 9.sp, lineHeight = 11.sp, shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 1f), 4f)),
                color = Color.White.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/**
 * A chain's fascia, as its shop front shows it: its sign's colour edge to edge, lit from above and darker at its foot,
 * its logo large on it in its lettering's colour ([BrandSigns]).
 */
@Composable
private fun Fascia(chain: Brand, modifier: Modifier) {
    Box(
        modifier.background(BrandSigns.colour(chain))
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f))),
        contentAlignment = Alignment.Center,
    ) { Symbol("logo-${chain.logoSlug}", tint = BrandSigns.ink(chain), size = 76.dp) }
}

/** A place's photo as a card shows it, with the credit its licence asks for. */
private class CardPhoto(val bitmap: ImageBitmap, val credit: String?)

/** [place]'s photo once read ([placePhoto]); null while it is read and where it has none. */
@Composable
private fun rememberPhoto(place: Place): CardPhoto? {
    val services = LocalServices.current
    if (commonsFile(place.tags) == null && place.tags["wikidata"].isNullOrBlank()) return null
    val photo by produceState<CardPhoto?>(null, place.key) {
        value = withContext(Dispatchers.IO) {
            try {
                placePhoto(services, place)?.let { i ->
                    BitmapFactory.decodeByteArray(i.bytes, 0, i.bytes.size)?.let { b ->
                        CardPhoto(b.asImageBitmap(), listOfNotNull(i.credit, i.licence).joinToString(" · ").ifEmpty { null })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    return photo
}

/**
 * [place]'s photo as its page finds it: its Commons file (its `wikimedia_commons` tag, else its Wikidata item's image),
 * from the phone where it was kept, else from Wikimedia when online, then kept for offline; null where it has none.
 */
internal suspend fun placePhoto(services: Services, place: Place): KeptImage? {
    val images = KeptImages(services.dirs.images)
    commonsFile(place.tags)?.let { return images.get(services, it) }
    val qid = place.tags["wikidata"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val kept = KeptFacts(File(services.dirs.files, "search/wikidata"))
    val online = !services.config.offline && services.connectivity.online.value
    val entity = kept.read(qid) ?: if (online) services.wikidata.entity(qid, listOf(java.util.Locale.getDefault().language, "en").distinct())?.also { runCatching { kept.keep(it) } } else null
    return entity?.image?.let { images.get(services, it) }
}

/** The Commons file a place's `wikimedia_commons` tag names ("File:Westerkerk.jpg"), without its namespace; null for a category or none. */
internal fun commonsFile(tags: Map<String, String>): String? =
    tags["wikimedia_commons"]?.trim()?.takeIf { it.startsWith("File:", ignoreCase = true) }?.substringAfter(':')?.takeIf { it.isNotBlank() }

/**
 * How the places found are shown, as a round key at the end of a kind's band or an asked head: rows, or large cards to
 * slide through; it shows the other way. On a band it is a white disc with the symbol in the kind's colour, over the
 * band's art as the map's pins are over the map.
 */
@Composable
internal fun ViewSwitch(cards: Boolean, fill: Color, tint: Color, onSwitch: (Boolean) -> Unit) {
    val said = stringResource(if (cards) R.string.find_view_rows else R.string.find_view_cards)
    Box(
        Modifier.size(40.dp).cssShadow(CircleShape, y = 2.dp, blur = 8.dp, colour = Color(0x2914161B)).clip(CircleShape).background(fill)
            .clickable(role = Role.Switch) { onSwitch(!cards) }.semantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) { Symbol(if (cards) "ui.list" else "ui-cards", tint = tint, size = 20.dp) }
}

/** The test tag of the row of cards. */
internal const val CARDS_TAG = "cards"

/** How much of the sheet's width a card takes: the next one's edge shows that there are more to slide to. */
private const val CARD_SHARE = 0.86f

/** The height of a card's picture. */
private val PICTURE = 156.dp

/** The most symbols a card shows. */
private const val CARD_SYMBOLS = 6
