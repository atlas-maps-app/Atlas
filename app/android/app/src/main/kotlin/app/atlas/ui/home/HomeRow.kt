package app.atlas.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import app.atlas.R
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Category
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.services.SettingsKeys
import app.atlas.services.SettingsStore
import app.atlas.ui.kit.BrandSign
import app.atlas.ui.kit.MainSymbols
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.frosted
import app.atlas.ui.search.Asked
import app.atlas.ui.search.SearchSheetState
import app.atlas.ui.search.brandName
import kotlinx.coroutines.launch

/**
 * The home's own row of round buttons (decision 12; the owner, 6 November: "Favorite re order customize?", then "Yes,
 * build it"): Atlas's eight families ([HOME_CATEGORIES]) until the searcher changes it, then the families, kinds and
 * chains they keep there, in the order they dragged them to, kept on the phone ([SettingsKeys.HOME_ROW]). A kind's or a
 * chain's star puts it at the row's end; a long press on a button drags it along the row, takes it out, adds any kind
 * from the list of every kind, or brings Atlas's eight back. An id that stands for nothing any more (a family renamed,
 * a chain the index lost) is left out without a word.
 */
internal object HomeRow {
    /** A chain's id in the row: this, then its brand's ([Brand.id]). */
    const val CHAIN = "brand:"

    private const val SEPARATOR = "|"

    /** One round button of the row: what its id stands for now. */
    sealed class Button {
        abstract val id: String

        abstract fun name(lang: String?): String
    }

    /** One of the boards' eight families ("eat"), in the board's own symbol. */
    data class Family(override val id: String, val category: Category, val look: HomeCategory) : Button() {
        override fun name(lang: String?) = category.name(lang)
    }

    /** Any other kind ("preset:leisure/playground", "cat:toilets", "shelf:culture"), in its own symbol and colour. */
    data class Kind(override val id: String, val node: CategoryNode) : Button() {
        override fun name(lang: String?) = node.name(lang)
    }

    /** A chain, on its own sign. */
    data class Chain(override val id: String, val brand: Brand) : Button() {
        override fun name(lang: String?) = brandName(brand)
    }

    /** The row as it is kept: its buttons, and whether the searcher made it theirs (else it is Atlas's eight). */
    class Kept(val buttons: List<Button>, val own: Boolean)

    /** The row's ids as kept ([keep]), else Atlas's eight. */
    fun ids(kept: String?): List<String> = kept?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.distinct() ?: HOME_CATEGORIES

    /** [ids] to keep: none where they are Atlas's eight, so that a row no one changed goes on following Atlas's. */
    fun keep(ids: List<String>): String? = ids.distinct().takeIf { it != HOME_CATEGORIES }?.joinToString(SEPARATOR)

    /** [ids] with [id] at the end, where it is not in them yet. */
    fun with(ids: List<String>, id: String): List<String> = if (id in ids) ids else ids + id

    /** [ids] with the one at [from] moved to [to]. */
    fun moved(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices) return ids
        val out = ids.toMutableList()
        val id = out.removeAt(from)
        out.add(to.coerceIn(0, out.size), id)
        return out
    }

    /** The id [node]'s view goes by in the row: one of the eight families by its own ("eat"), any other kind by its node's. */
    fun idOf(node: CategoryNode): String = HOME_CATEGORY_LOOK.keys.firstOrNull { CategoryTree.category(it)?.id == node.id } ?: node.id

    /** The id [brand]'s page goes by in the row. */
    fun idOf(brand: Brand): String = CHAIN + brand.id

    /** What [id] stands for now, or null where it stands for nothing any more. */
    fun resolve(id: String): Button? {
        if (id.startsWith(CHAIN)) return Brands.byId(id.removePrefix(CHAIN))?.let { Chain(id, it) }
        val look = HOME_CATEGORY_LOOK[id]
        val family = look?.let { Categories.byId(id) }
        if (look != null && family != null) return Family(id, family, look)
        return (CategoryTree.byId(id) ?: CategoryTree.category(id))?.let { Kind(id, it) }
    }

    /** The row's buttons, as [kept]. */
    fun buttons(kept: String?): List<Button> = ids(kept).mapNotNull(::resolve)

    /** The row as [kept] (the setting's value). */
    fun read(kept: String?): Kept = Kept(buttons(kept), kept != null)

    /** Changes the row [settings] keep by [change], read and written in one go so that no two changes cross. */
    suspend fun edit(settings: SettingsStore, change: (List<String>) -> List<String>) {
        settings.edit { p ->
            val kept = keep(change(ids(p[SettingsKeys.HOME_ROW]).filter { resolve(it) != null }))
            if (kept == null) p.remove(SettingsKeys.HOME_ROW) else p[SettingsKeys.HOME_ROW] = kept
        }
    }

    /**
     * The button of the row ([ids]) lit for what the sheet shows ([s]): a chain's page its chain's; a kind the kind's,
     * else the nearest broader one in the row, back the way it was come to (Fun for the climbing halls it holds too, Eat
     * for a pizzeria); none where the row holds none of those.
     */
    fun chosen(s: SearchSheetState, ids: Collection<String>): String? {
        (s.asked as? Asked.Chain)?.let { a -> idOf(a.brand).takeIf { it in ids }?.let { return it } }
        var n = s.node
        val seen = HashSet<String>()
        while (n != null && seen.add(n.id)) {
            idOf(n).takeIf { it in ids }?.let { return it }
            n = s.cameFrom[n.id]?.let { CategoryTree.byId(it) } ?: n.parent
        }
        return null
    }
}

/** A button's side: the board's 44 dp. */
private val PILL = 44.dp

/** A button's place along the row: its side and the 8 dp to the next. */
private val SLOT = PILL + 8.dp

/** How much a dragged button grows under the finger. */
private const val LIFT = 1.12f

/** How near the row's end a dragged button is held for the row to scroll on under it, and how far it scrolls a frame. */
private val EDGE = 40.dp
private val EDGE_STEP = 6.dp

/** How long a button takes to slide into its new place. */
private const val SLIDE_MS = 180

/**
 * What the row keeps while it is out of sight, the sheet full over it (the list of every kind opened from its "+"): how
 * far it is scrolled, and the ids it last showed, so the one added there comes into view as it comes back.
 */
@Stable
internal class OwnRowState(val scroll: ScrollState) {
    var seen: Set<String>? = null
}

@Composable
internal fun rememberOwnRowState(): OwnRowState {
    val scroll = rememberScrollState()
    return remember(scroll) { OwnRowState(scroll) }
}

/**
 * The home's own row ([HomeRow]) above the search bar: the board's 44 dp buttons 8 dp apart, each at its own size
 * whatever the phone (never narrowed to fit), in one row that scrolls sideways under the screen's edges, the first lined
 * up with the chrome [margin] in from the side; the chosen one ([selected]) fills with its colour (a chain's sign: a
 * ring) and, if it lies past an edge, scrolls into view, as does one just added. Each button's shape follows it as the
 * row scrolls (`frosted`: the core's keep-out, and its glass when the glass is on). A family wears the board's symbol, a
 * kind its own in its colour, a chain its sign.
 *
 * A long press on a button starts [editing]: the button lifts and follows the finger along the row, the others sliding
 * aside, the row scrolling on under it at its ends, and lands where it is let go ([onOrder]); each button has a "−" to
 * take it out ([onRemove]), and the row ends in "+" ([onAdd]: a kind from the list of every kind) and Atlas's eight again
 * where the row is no longer theirs ([onReset]); done stays in view at its right end, the rest scrolling beside it. An
 * empty row keeps "+" and Atlas's eight, the way back. A screen reader moves a button left or right, or takes it out,
 * among its actions.
 */
@Composable
internal fun OwnRow(
    buttons: List<HomeRow.Button>,
    selected: String?,
    onSelect: (HomeRow.Button) -> Unit,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onOrder: (List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onReset: (() -> Unit)?,
    modifier: Modifier = Modifier,
    lang: String? = null,
    margin: Dp = 12.dp,
    state: OwnRowState = rememberOwnRowState(),
) {
    val look = homeLook()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scroll = state.scroll
    // the order the finger made, while it drags and till the row comes back so
    var held by remember { mutableStateOf<List<String>?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    // the one let go, sliding from where it was let go into its place
    var dropping by remember { mutableStateOf<String?>(null) }
    val drop = remember { Animatable(0f) }
    // -1 while the one dragged is held at the row's left end, 1 at its right: the row scrolls on under it
    var edge by remember { mutableIntStateOf(0) }
    // the row's left and right edges in the window, px
    val ends = remember { FloatArray(2) }
    val byId = buttons.associateBy { it.id }
    val shown = held?.let { h -> h.mapNotNull { byId[it] } + buttons.filter { it.id !in h } } ?: buttons
    val order by rememberUpdatedState(shown.map { it.id })
    val kept by rememberUpdatedState(buttons.map { it.id })
    val edit by rememberUpdatedState(onEditing)
    val land by rememberUpdatedState(onOrder)
    val slot = with(density) { SLOT.toPx() }
    val edgePx = with(density) { EDGE.toPx() }
    val reveal = remember { HashMap<String, BringIntoViewRequester>() }

    suspend fun bring(id: String) {
        val r = reveal[id] ?: return
        // the button and the margin beside it, so it does not end against the screen's edge
        val side = with(density) { PILL.toPx() }
        val pad = with(density) { margin.toPx() }
        r.bringIntoView(Rect(-pad, 0f, side + pad, side))
    }
    LaunchedEffect(selected) { selected?.let { bring(it) } }
    LaunchedEffect(buttons) {
        if (dragging == null) held = null
        // one button new to the row (added from the list, or starred) comes into view at its end
        val before = state.seen
        state.seen = buttons.mapTo(HashSet()) { it.id }
        val added = before?.let { b -> buttons.filter { it.id !in b } }?.singleOrNull() ?: return@LaunchedEffect
        withFrameNanos {}
        bring(added.id)
    }

    // past half a place, the one dragged takes the next one's, which steps into its old
    fun reorder() {
        val id = dragging ?: return
        var now = held ?: return
        var i = now.indexOf(id)
        while (dragX > slot / 2 && i in 0 until now.lastIndex) {
            now = HomeRow.moved(now, i, i + 1)
            i++
            dragX -= slot
        }
        while (dragX < -slot / 2 && i > 0) {
            now = HomeRow.moved(now, i, i - 1)
            i--
            dragX += slot
        }
        held = now
    }

    fun end() {
        val h = held
        val id = dragging
        val from = dragX
        dragging = null
        dragX = 0f
        edge = 0
        if (id != null && from != 0f) {
            dropping = id
            scope.launch {
                drop.snapTo(from)
                drop.animateTo(0f, tween(SLIDE_MS))
                if (dropping == id) dropping = null
            }
        }
        if (h != null && h != kept) land(h) else held = null
    }

    // held at an end, the row scrolls on under the one dragged till it can go no further
    LaunchedEffect(edge, dragging) {
        if (edge == 0 || dragging == null) return@LaunchedEffect
        val step = with(density) { EDGE_STEP.toPx() } * edge
        while (true) {
            withFrameNanos {}
            val moved = scroll.scrollBy(step)
            if (moved == 0f) break
            dragX += moved
            reorder()
        }
    }

    val left = stringResource(R.string.row_move_left)
    val right = stringResource(R.string.row_move_right)
    val change = stringResource(R.string.row_change)
    // while the row is changed, done stays in view at its right end and the rest scrolls beside it: with Atlas's eight
    // the row's end, "+" with it, lies past the screen's edge
    Row(modifier.bleed(margin), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .onGloballyPositioned { c ->
                    val x = c.positionInWindow().x
                    ends[0] = x
                    ends[1] = x + c.size.width
                }
                .horizontalScroll(scroll)
                .padding(start = margin, end = if (editing) 0.dp else margin),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shown.forEachIndexed { index, b ->
                key(b.id) {
                    val on = b.id == selected && !editing
                    val lifted = b.id == dragging
                    val landing = b.id == dropping
                    val name = b.name(lang)
                    val out = stringResource(R.string.row_take_out, name)
                    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
                    // the others slide into their new places: aside for the one dragged, along for one taken out
                    val slide = remember { Animatable(0f) }
                    val was = remember { IntArray(1) { index } }
                    LaunchedEffect(index) {
                        val from = was[0]
                        was[0] = index
                        if (from != index && dragging != b.id) {
                            slide.snapTo(slide.value + (from - index) * slot)
                            slide.animateTo(0f, tween(SLIDE_MS))
                        }
                    }
                    Box(
                        Modifier
                            .bringIntoViewRequester(reveal.getOrPut(b.id) { BringIntoViewRequester() })
                            .zIndex(if (lifted || landing) 1f else 0f)
                            .graphicsLayer {
                                translationX = when {
                                    lifted -> dragX
                                    landing -> drop.value
                                    else -> slide.value
                                }
                                scaleX = if (lifted) LIFT else 1f
                                scaleY = scaleX
                            },
                    ) {
                        Box(
                            Modifier
                                .size(PILL)
                                .cssShadow(
                                    CircleShape,
                                    y = if (lifted) 8.dp else 4.dp,
                                    blur = if (lifted) 18.dp else 14.dp,
                                    colour = look.shadow.copy(alpha = look.shadow.alpha * (if (lifted) 0.22f else 0.1f) / 0.12f),
                                )
                                .then(face(b, on, look))
                                .clip(CircleShape)
                                .toggleable(on, enabled = !editing, role = Role.Switch) { onSelect(b) }
                                .onPlaced { placed[0] = it }
                                // inside the press, so that the finger let go after a drag is no press
                                .pointerInput(b.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            edit(true)
                                            held = order
                                            dragging = b.id
                                            dragX = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            // measured in the grown button's own space: the finger moves LIFT times as far
                                            dragX += amount.x * LIFT
                                            reorder()
                                            val x = placed[0]?.takeIf { it.isAttached }?.localToWindow(change.position)?.x
                                            edge = when {
                                                x == null -> 0
                                                x < ends[0] + edgePx -> -1
                                                x > ends[1] - edgePx -> 1
                                                else -> 0
                                            }
                                        },
                                        onDragEnd = { end() },
                                        onDragCancel = { end() },
                                    )
                                }
                                .semantics {
                                    contentDescription = name
                                    if (editing) {
                                        customActions = listOf(
                                            CustomAccessibilityAction(left) { step(order, b.id, -1)?.let { land(it); true } ?: false },
                                            CustomAccessibilityAction(right) { step(order, b.id, 1)?.let { land(it); true } ?: false },
                                            CustomAccessibilityAction(out) { onRemove(b.id); true },
                                        )
                                    } else {
                                        onLongClick(change) { edit(true); true }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Face(b, on) }
                        if (editing && !lifted) {
                            // the way out, at the button's shoulder; its press reaches a little past the dot
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 7.dp, y = (-7).dp)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = out) { onRemove(b.id) }
                                    .semantics { contentDescription = out },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier.size(18.dp).border(1.5.dp, look.solid, CircleShape).clip(CircleShape).background(look.ink),
                                    contentAlignment = Alignment.Center,
                                ) { Symbol("ui-minus", tint = look.solid, size = 12.dp, weight = 1.5f) }
                            }
                        }
                    }
                }
            }
            if (editing || buttons.isEmpty()) {
                TailButton("ui-plus", stringResource(R.string.row_add), look, onAdd)
                onReset?.let { TailButton("ui-refresh", stringResource(R.string.row_reset), look, it) }
            }
        }
        if (editing) {
            Box(Modifier.padding(start = 8.dp, end = margin)) {
                TailButton("ui-check", stringResource(R.string.row_done), look, { onEditing(false) }, filled = true)
            }
        }
    }
}

/** [order] with [id] a place to the left (-1) or right (1), or null where it is at that end already. */
private fun step(order: List<String>, id: String, by: Int): List<String>? {
    val i = order.indexOf(id)
    val to = i + by
    return if (i < 0 || to !in order.indices) null else HomeRow.moved(order, i, to)
}

/** A button's ground: a family's or kind's colour when chosen, else the frost; a chain's sign always, ringed when chosen. */
private fun face(b: HomeRow.Button, on: Boolean, look: HomeLook): Modifier = when {
    b is HomeRow.Chain -> if (on) Modifier.border(2.5.dp, look.ink, CircleShape) else Modifier
    on -> Modifier.background(colourOf(b), CircleShape)
    else -> Modifier.frosted(CircleShape, look.frostStrong, blur = 20.dp)
}

/** What a button shows: a family's board symbol, a kind's own, white on its colour when chosen; a chain's sign. */
@Composable
private fun Face(b: HomeRow.Button, on: Boolean) {
    val tint = if (on) Color.White else colourOf(b)
    when (b) {
        is HomeRow.Family -> Symbol(b.look.symbol, tint = tint, size = b.look.size, weight = MainSymbols.Width.PILL)
        is HomeRow.Kind -> Symbol(b.node.symbol, tint = tint, size = 22.dp, weight = MainSymbols.Width.PILL)
        is HomeRow.Chain -> BrandSign(b.brand, b.name(null), PILL, shape = CircleShape, logo = 24.dp)
    }
}

/** A family's or a kind's own colour (categories.json's, the one its pins and rows wear). */
private fun colourOf(b: HomeRow.Button): Color = when (b) {
    is HomeRow.Family -> Color(b.category.colour)
    is HomeRow.Kind -> Color(b.node.colour)
    is HomeRow.Chain -> Color.Transparent
}

/** One of the buttons the row ends in while it is changed: on the frost, or done, filled with the ink. */
@Composable
private fun TailButton(symbol: String, description: String, look: HomeLook, onClick: () -> Unit, filled: Boolean = false) {
    Box(
        Modifier
            .size(PILL)
            .cssShadow(CircleShape, y = 4.dp, blur = 14.dp, colour = look.shadow.copy(alpha = look.shadow.alpha * 0.1f / 0.12f))
            .then(if (filled) Modifier.background(look.ink, CircleShape) else Modifier.frosted(CircleShape, look.frostStrong, blur = 20.dp))
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) { Symbol(symbol, tint = if (filled) look.solid else look.ink, size = 20.dp, weight = MainSymbols.Width.PILL) }
}

/**
 * Lays this out [horizontal] wider on each side than its parent allows, reaching over the parent's padding: a row that
 * scrolls under the screen's edges inside a padded column.
 */
private fun Modifier.bleed(horizontal: Dp): Modifier = layout { m, c ->
    val pad = horizontal.roundToPx()
    val p = m.measure(c.offset(horizontal = 2 * pad))
    layout(maxOf(0, p.width - 2 * pad), p.height) { p.place(-pad, 0) }
}
