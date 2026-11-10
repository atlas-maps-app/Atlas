package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.ui.home.HomeRow
import app.atlas.ui.kit.BrandSign
import app.atlas.ui.kit.Symbol
import app.atlas.places.Brands
import app.atlas.places.CategoryNode
import app.atlas.places.Palette
import app.atlas.search.Browse
import app.atlas.search.CategoryPulse
import app.atlas.search.EveryKind

/** The rows of the list of every kind, for tests to find and scroll. */
internal const val EVERY_TAG = "every-kind"

/**
 * The plain list of every kind (the owner, 6 November: "a plain all kinds list row sortable with every tag/kind that
 * exists"): a row a kind, its disc as its places' rows have it, its name over how many are near and open, the minutes
 * to the nearest at its end; the most first, the nearest or by name, only those with one open at a switch. A row opens
 * the kind over the map, and the way back returns here. Opened from inside a kind (a family's chips) it is that
 * kind's, under its band: every kind it holds, those none of which is near lighter after them, which a press looks
 * further for until each has turned up ([LookFurther]). Opened from the home's own row to add to it
 * ([EveryKindView.picking]), the families lead, each with its kinds around under it, those in the [row] ticked; a press
 * puts one in the row.
 */
@Composable
internal fun EveryKindList(
    every: EveryKindView,
    find: SearchSheetModel,
    lang: String,
    room: Dp,
    onBack: () -> Unit,
    onExplain: (Explained) -> Unit,
    row: List<String> = emptyList(),
) {
    val rows = remember(every.kinds, every.order, every.openOnly, lang) { EveryKind.sorted(every.kinds, every.order, lang, every.openOnly) }
    val list = rememberLazyListState()
    // the rows pass under a plain head: a hairline marks its edge once they do (a band's colour marks its own)
    val under by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    Column(Modifier.fillMaxSize()) {
        EveryHead(every, lang, onBack)
        if (every.scope == null) Box(Modifier.fillMaxWidth().height(1.dp).background(if (under) indexColors().hair else Color.Transparent))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag(EVERY_TAG), state = list, contentPadding = PaddingValues(bottom = room)) {
            item(key = "order") { EveryOrder(every, find) }
            if (every.scope != null) item(key = "further") { LookFurther(every, find) }
            items(rows, key = { it.pulse.node.id }) { line ->
                val p = line.pulse
                val nowhere = (p.node.id in every.nowhere).takeIf { it }?.let { stringResource(if (every.unasked) R.string.find_not_on_phone else R.string.find_found_nowhere) }
                val id = if (every.picking) HomeRow.idOf(p.node) else null
                EveryRow(
                    p, every.chains[p.node.id].orEmpty(), lang, nowhere, line.depth, line.under,
                    onClick = { if (id != null) find.pickForRow(id) else find.enterFromEvery(p.node.id) },
                    onLongClick = { onExplain(Explained(p.node)) },
                    inRow = id != null && id in row,
                )
            }
            if (every.everything.isNotEmpty() && !every.picking) item(key = "everything") { EveryElse(every.everything.size, lang) { find.enterFromEvery(Browse.EVERYTHING.id) } }
        }
    }
}

/**
 * The list's head: the way back, "Every kind" (or the kind it was opened from, on its band) and how many kinds it holds;
 * a kind's, which holds those none of which is near too, how many are.
 */
@Composable
private fun EveryHead(every: EveryKindView, lang: String, onBack: () -> Unit) {
    val scope = every.scope
    val kinds = pluralStringResource(R.plurals.find_kinds, every.kinds.size, every.kinds.size)
    val near = every.kinds.count { it.count > 0 }
    val count = if (scope != null && near < every.kinds.size) "$kinds · $near ${stringResource(R.string.find_word_near)}" else kinds
    if (scope != null) {
        val art = remember(scope.id) { artOf(scope) }
        Row(
            Modifier.fillMaxWidth().bandSurface(Color(scope.colour), art).padding(start = 4.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BandBack(stringResource(R.string.find_up), onBack)
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(scope.name(lang), style = indexTitle(20f).onCard(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count, style = INDEX_META.onCard(), maxLines = 1)
            }
        }
    } else {
        val c = indexColors()
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IndexRound("ui-chevron-left", stringResource(R.string.find_up), onBack)
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (every.picking) R.string.row_add else R.string.find_every_kind), style = indexTitle(22f), color = c.ink, maxLines = 1)
                Text(count, style = INDEX_META, color = c.soft, maxLines = 1)
            }
        }
    }
}

/** How the list is sorted, one of three, and the switch that keeps only the kinds with one open now. */
@Composable
private fun EveryOrder(every: EveryKindView, find: SearchSheetModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((order, label) in ORDERS) IndexChip(stringResource(label), { find.everyOrder(order) }, selected = every.order == order, role = Role.RadioButton)
        IndexChip(stringResource(R.string.category_open_now), { find.everyOpen(!every.openOnly) }, symbol = "ui-clock", selected = every.openOnly)
    }
}

private val ORDERS = listOf(EveryKind.Order.MOST to R.string.find_kinds_most, EveryKind.Order.NEAREST to R.string.find_nearest, EveryKind.Order.NAME to R.string.find_kinds_az)

/**
 * Looking further for the kinds on a kind's list none of which is near (the owner, 6 November: "no way to then extend
 * the search until results for every kind turn up"): a chip with how many there are to look for, and while it looks how
 * many are still to turn up; gone once it is done, the rows saying how far each one is, or that it is nowhere.
 */
@Composable
private fun LookFurther(every: EveryKindView, find: SearchSheetModel) {
    val left = every.kinds.count { it.count == 0 && it.nearest == null }
    if (left == 0 || every.further == Further.DONE) return
    val looking = every.further == Further.LOOKING
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)) {
        IndexChip(
            stringResource(if (looking) R.string.find_looking_further else R.string.find_look_further),
            { if (!looking) find.lookFurther() },
            symbol = "ui.radius", count = left, selected = looking, ghost = !looking, role = Role.Button,
        )
    }
}

/**
 * A kind on the list: its disc, lighter where none is open till later as a closed place's is, or none is near; its name
 * over how many are near and how many open, where any of those closes (a bench is never "open"), or that none is near,
 * or [nowhere] that looking further found none; the minutes to the nearest at its end, wherever it is. The chains among
 * its places peek out behind its disc ([KindLead]). Ticked where it is in the home's own row ([inRow]), on the list
 * opened to add to it.
 */
@Composable
private fun EveryRow(
    p: CategoryPulse,
    chains: List<String>,
    lang: String,
    nowhere: String?,
    depth: Int,
    under: CategoryNode?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    inRow: Boolean = false,
) {
    val node = p.node
    val ticked = stringResource(R.string.row_star)
    val counts = when {
        p.count == 0 -> nowhere ?: stringResource(R.string.find_none_near)
        p.open > p.endless -> stringResource(R.string.find_counts, p.count.toString(), p.open.toString())
        else -> "${p.count} ${stringResource(R.string.find_word_near)}"
    }
    IndexLine(
        // under the broader kind above it, what its name adds ("Italian" under "Restaurant")
        title = under?.let { node.nameUnder(it, lang) } ?: node.name(lang),
        line = AnnotatedString(counts),
        onClick = onClick,
        onLongClick = onLongClick,
        reach = { Reach(p.minutes, p.metres) },
        lead = { KindLead(node, chains, faded = p.count == 0 || p.open == 0 && p.opensAt != null) },
        trailing = if (inRow) ({ Symbol("ui-check", Modifier.padding(start = 10.dp), tint = Color(node.colour), size = 20.dp, contentDescription = ticked) }) else null,
        indent = NARROWER * depth.coerceAtMost(2),
    )
}

/** How far a narrower kind stands in from the broader kind above it: its disc under the broader one's name. */
private val NARROWER = 26.dp

/**
 * A kind's disc with the chains behind it (the owner, 6 November: "For every kind if there are brands behind it show
 * them hiding behind the category symbol?", then "You choose"): their signs tucked behind the disc, peeking out over its
 * top, the one with the most branches on the right, each cut by a ring of the paper; the disc keeps its width, so the
 * names stand in line. Only a chain in a colour of its own: half hidden, a neutral sign with half a letter says nothing.
 * Lighter with the disc where none is open till later.
 */
@Composable
private fun KindLead(node: CategoryNode, chains: List<String>, faded: Boolean = false) {
    val c = indexColors()
    val brands = remember(chains) { chains.mapNotNull { Brands.byId(it) }.filter { it.colour != null }.take(2) }
    Box(Modifier.size(DISC)) {
        // the second first, so the first covers it
        for (i in brands.indices.reversed()) {
            val b = brands[i]
            val side = if (i == 0) 1 else -1
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = PEEK_X * side, y = -PEEK_Y)
                    .rotate(PEEK_TILT * side)
                    .alpha(if (faded) 1f - Palette.CLOSED_FADE else 1f)
                    .size(SIGN + 4.dp)
                    .clip(RoundedCornerShape(SIGN * 0.28f + 2.dp))
                    .background(c.paper),
                contentAlignment = Alignment.Center,
            ) { BrandSign(b, b.name, SIGN) }
        }
        // the paper round the disc, which the signs go behind
        if (brands.isNotEmpty()) Box(Modifier.align(Alignment.Center).requiredSize(DISC + 4.dp).clip(CircleShape).background(c.paper))
        KindDisc(node.symbol, node.colour, tone = null, Modifier.align(Alignment.Center), faded = faded)
    }
}

private val DISC = 38.dp
private val SIGN = 22.dp
private val PEEK_X = 9.dp
private val PEEK_Y = 13.dp
private const val PEEK_TILT = 12f

/** The last row from the grid: everything else on the map (a road, a canal) as the grid's last shelf, how many kinds it holds. */
@Composable
private fun EveryElse(kinds: Int, lang: String, onClick: () -> Unit) {
    val shelf = Browse.EVERYTHING
    IndexLine(
        title = shelf.name(lang),
        line = AnnotatedString(pluralStringResource(R.plurals.find_kinds, kinds, kinds)),
        onClick = onClick,
        lead = { KindLead(shelf, emptyList()) },
        hairline = false,
    )
}
