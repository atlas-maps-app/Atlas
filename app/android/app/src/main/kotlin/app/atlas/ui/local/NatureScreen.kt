package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.TagFilter
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import java.time.LocalDate
import java.util.Locale
import androidx.compose.ui.semantics.heading
import app.atlas.ui.kit.cssRepeatingLinear
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow

private val Ink = Color(0xFF2A2418)
private val Leaf = Color(0xFF2F4A2A)
private val Garamond = Color(0xFF6A5E48)

/** The words for the season of a date and hemisphere: "autumn". */
@Composable
internal fun seasonWord(date: LocalDate, southern: Boolean): String {
    val m = if (southern) (date.monthValue + 5) % 12 + 1 else date.monthValue
    return stringResource(when (m) { 12, 1, 2 -> R.string.season_winter; 3, 4, 5 -> R.string.season_spring; 6, 7, 8 -> R.string.season_summer; else -> R.string.season_autumn })
}

@Composable
private fun kindName(k: NatureKind): String = stringResource(
    when (k) { NatureKind.TREES -> R.string.nature_trees; NatureKind.WATER -> R.string.nature_water; NatureKind.BIRDS -> R.string.nature_birds; NatureKind.GARDENS -> R.string.nature_gardens; NatureKind.WILD -> R.string.nature_wild },
)

/**
 * Field notes: a naturalist's ring-bound notebook of what grows and lives near you: notable trees (old, tall, protected, named),
 * water, bird hides and reserves, gardens and wild places, each a pressed leaf or a sketch taped to the page with a line about it.
 * The index tabs at the edge turn to the next section; the pencil leaves a note for the map's mappers.
 */
@Composable
fun NatureScreen(args: Dest.Nature, nav: Navigator) {
    val services = LocalServices.current
    val here = args.at ?: rememberHere()
    val clock = appClock()
    val zone = clock.zone
    val now = remember(clock) { clock.instant() }
    val today = remember(clock) { LocalDate.now(clock) }
    var kind by rememberSaveable { mutableStateOf(NatureKind.of(args.kind)) }
    val place = rememberPlaceName(here)
    val backLabel = stringResource(R.string.kit_back)
    val addLabel = stringResource(R.string.nature_add_find)
    val notes by produceState<List<Note>?>(null, here, kind) {
        value = null
        val radius = if (kind == NatureKind.TREES) 800.0 else 2_500.0
        val found = services.local.finder.find(TagFilter.parse(kind.filter), here, radius, limit = if (kind == NatureKind.TREES) 400 else 80)
        value = if (kind == NatureKind.TREES) NatureNotes.treeNotes(found.places, today) else NatureNotes.placeNotes(kind, found.places)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF4E5A3E))) {
        // The page: 14 px in and 30 down (under the status bar), 34 from the right, 22 from the foot; cream ruled every
        // 34 px, radius 6 at the spine and 16 at the edge, its shadow and the shade of the spine.
        val page = Modifier.fillMaxSize().padding(top = boardShift()).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 14.dp, top = 30.dp, end = 34.dp, bottom = 22.dp)
        Box(
            page.boardBox(
                0xFFF6F0DF, CssCorners(6f, 16f, 16f, 6f), listOf(CssShadow(0f, 16f, 30f, 0f, 0x59000000), CssShadow(10f, 0f, 14f, -10f, 0x59503C14, inset = true)),
                grad = listOf(cssRepeatingLinear(180f, 34f, 0f to 0x00000000, 33f to 0x00000000, 33f to 0xFFE3D9C0, 34f to 0xFFE3D9C0)),
            ),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 30.dp, bottom = 90.dp)) {
                // The head: the 40 px back at (26, 18), the hand-written title and the place in EB Garamond 12 px after it.
                Box(Modifier.fillMaxWidth().height(73.5.dp)) {
                    Box(
                        Modifier.padding(top = 18.dp).size(40.dp).boardBox(0xFFEDE4CC, corners(20f)).clickable { nav.back() }.semantics { contentDescription = backLabel },
                        contentAlignment = Alignment.Center,
                    ) { Symbol("ui.back", tint = Ink, size = 18.dp) }
                    Column(Modifier.padding(start = 52.dp, top = 13.75.dp)) {
                        BoardLine(stringResource(R.string.nature_title), boardStyle(40f, 700, Leaf.css(), 40f, CAVEAT), Modifier.semantics { heading() }, overflow = TextOverflow.Visible)
                        BoardLine(listOfNotNull(place, seasonWord(today, here.lat < 0)).joinToString(" · "), boardStyle(15f, 400, Garamond.css(), 19.5f, GARAMOND, italic = true))
                    }
                }
                Spacer(Modifier.height(20.5.dp))
                when {
                    notes == null -> BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 400, Garamond.css(), 18f))
                    notes!!.isEmpty() -> StateMessage(kind.symbol, stringResource(R.string.nature_none, kindName(kind).lowercase(Locale.getDefault())), text = if (!services.local.online()) stringResource(R.string.picnic_offline_text) else null, tint = Garamond)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        for ((i, n) in notes!!.withIndex()) NoteEntry(n, i, now, zone, onClick = { services.remember(n.place); nav.go(Dest.Place.of(n.place)) })
                    }
                }
            }
            // The pencil: a note for the mappers, 22 px in from the page's corner.
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp).size(56.dp).boardBox(Leaf.css(), corners(28f), shadow(0f, 6f, 14f, 0x4C000000), clip = false)
                    .clickable { nav.go(Dest.Quests(here.lat, here.lon)) }.semantics { contentDescription = addLabel },
                contentAlignment = Alignment.Center,
            ) { Symbol("ui.edit", tint = Color(0xFFF6F0DF), size = 22.dp) }
        }
        // The spiral rings: 20 x 12 at 6 px in, from 30 px below the page's top to 28 above its foot.
        Column(
            Modifier.fillMaxHeight().padding(top = boardShift()).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 6.dp, top = 60.dp, bottom = 50.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(8) { Box(Modifier.size(20.dp, 12.dp).boardBox(r = corners(6f), grad = listOf(cssLinear(180f, 0f to 0xFFE8EBEE, 1f to 0xFF8E949C)))) }
        }
        // The index tabs on the right edge: 40 x 70 from 110, 6 apart, the chosen one 44 wide and 6 px out.
        Column(Modifier.padding(top = boardShift()).padding(start = 364.dp, top = 110.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in NatureKind.entries) {
                val on = k == kind
                val label = kindName(k)
                Box(
                    Modifier.offset(x = if (on) 6.dp else 0.dp).size(if (on) 44.dp else 40.dp, 70.dp).boardBox(k.colour, CssCorners(0f, 10f, 10f, 0f))
                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) { kind = k }.semantics { contentDescription = label; selected = on },
                    contentAlignment = Alignment.Center,
                ) { Symbol(tabSymbol(k), tint = Color.White, size = if (k == NatureKind.WATER) 18.dp else 20.dp) }
            }
        }
    }
}

/** The board's symbols on the index tabs. */
private fun tabSymbol(k: NatureKind): String = when (k) {
    NatureKind.TREES -> "place.park"
    NatureKind.WATER -> "need.water"
    NatureKind.BIRDS -> "nature.birds"
    NatureKind.GARDENS -> "nature.garden"
    NatureKind.WILD -> "cat.nature"
}

/** The board's face for the Latin names and the place: EB Garamond, italic. */
private const val GARAMOND = "EB Garamond"

/** How each of the board's pressings is turned, degrees, and where its strip of tape is: -1 left, 1 right, 0 none. */
private val TILTS = floatArrayOf(-3f, 2f, -1.5f, 2.5f, -2f)
private val TAPES = intArrayOf(-1, 1, -1, 0, 1)

/**
 * One entry of the notebook as the board writes it: the 92 x 104 pressing (turned, its shadow and tape) with the leaf or
 * sketch on it, then 12 px on and 2 apart: the name in Caveat 26 at 700, the Latin in EB Garamond, a fact in Caveat 20,
 * and where and how far in Geist 12 at 700.
 */
@Composable
private fun NoteEntry(n: Note, index: Int, now: java.time.Instant, zone: java.time.ZoneId, onClick: () -> Unit) {
    val lang = Locale.getDefault().language
    val common = Trees.nameOf(n.what.replaceFirstChar { it.uppercase() })?.let { if (lang == "nl") it.nl else it.en }
    val title = when {
        n.title != null -> n.title
        n.kind == NatureKind.TREES && n.count > 1 && common != null -> stringResource(R.string.nature_row, common.replaceFirstChar { it.uppercase() }, n.count)
        n.kind == NatureKind.TREES && common != null -> common.replaceFirstChar { it.uppercase() }
        n.kind == NatureKind.TREES && n.title == null && n.count > 1 -> stringResource(R.string.nature_trees_mapped, n.count)
        else -> kindName(n.kind)
    }
    val heritage = n.facts.any { it is NoteFact.Protected }
    val head = if (heritage && n.title == null && common != null) stringResource(R.string.nature_heritage, common) else title
    val texts = n.facts.mapNotNull { factText(it, now, zone) }
    val tilt = TILTS[index % 5]
    val tape = TAPES[index % 5]
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(92.dp, 104.dp).rotate(tilt).boardBox(0xFFFBF8EE, shadows = shadow(0f, 2f, 5f, 0x26000000), clip = false)) {
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) { Glyph(n.art, Color(0xFF6E7A3A).takeIf { n.art == "nature.tree" } ?: Color.Unspecified, 76.dp) }
            if (tape != 0) Box(Modifier.offset(x = if (tape < 0) (-8).dp else 64.dp, y = (-6).dp).rotate(if (tape < 0) -30f else 30f).size(36.dp, 14.dp).background(Color(0xCCE9D7A0)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BoardPara(head, boardStyle(26f, 700, Ink.css(), 27.3f, CAVEAT), lines = 2)
            n.latin?.let { BoardLine(it, boardStyle(14f, 400, Garamond.css(), 18f, GARAMOND, italic = true)) }
            texts.firstOrNull()?.let {
                val warm = n.facts.firstOrNull() is NoteFact.Autumn || n.facts.firstOrNull() is NoteFact.Blossom
                BoardPara(it, boardStyle(20f, 400, if (warm) 0xFF9A6A00 else Ink.css(), 24f, CAVEAT), lines = 2)
            }
            BoardLine((texts.drop(1).take(1) + listOf(walkOrDistance(n.distanceM))).joinToString(" · "), boardStyle(12f, 700, 0xFF2F5A2A, 15.5f, tnum = true))
        }
    }
}

@Composable
private fun factText(f: NoteFact, now: java.time.Instant, zone: java.time.ZoneId): String? = when (f) {
    is NoteFact.Planted -> stringResource(R.string.nature_fact_planted, f.year)
    is NoteFact.Age -> stringResource(R.string.nature_fact_age, f.years)
    is NoteFact.Height -> stringResource(R.string.nature_fact_height, f.metres.toInt())
    is NoteFact.Girth -> stringResource(R.string.nature_fact_girth, f.cm)
    is NoteFact.Protected -> stringResource(R.string.nature_fact_protected)
    is NoteFact.Autumn -> stringResource(R.string.nature_fact_autumn)
    NoteFact.Blossom -> stringResource(R.string.nature_fact_blossom)
    is NoteFact.Hours -> { val context = androidx.compose.ui.platform.LocalContext.current; marketLine(f.text, now, zone) { context.getString(R.string.moment_market_open_until, it) } }
    is NoteFact.Class -> f.protectClass?.let { stringResource(protectClassName(it)) }
    NoteFact.StepFree -> stringResource(R.string.picnic_step_free)
    is NoteFact.Elevation -> stringResource(R.string.nature_fact_elevation, f.metres)
    is NoteFact.Kind -> stringResource(when (f.text) { "broadleaved" -> R.string.nature_leaf_broad; "needleleaved" -> R.string.nature_leaf_needle; else -> R.string.nature_leaf_mixed })
    is NoteFact.Wiki -> null
}

/** What an IUCN protect_class number means, as a string. */
internal fun protectClassName(c: String): Int = when (c.trim()) {
    "1", "1a", "1b" -> R.string.protect_1; "2" -> R.string.protect_2; "3" -> R.string.protect_3; "4" -> R.string.protect_4
    "5" -> R.string.protect_5; "6" -> R.string.protect_6; else -> R.string.protect_other
}
