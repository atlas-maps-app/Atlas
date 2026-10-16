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
        // The page: cream, ruled every 34 dp, a shadow on the spine.
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(start = 14.dp, top = 6.dp, end = 34.dp, bottom = 22.dp)
                .cssShadow(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp), 16.dp, 30.dp, Color(0x59000000))
                .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 16.dp, bottomEnd = 16.dp)).background(Color(0xFFF6F0DF))
                .drawBehind {
                    var y = 33.dp.toPx()
                    while (y < size.height) { drawRect(Color(0xFFE3D9C0), Offset(0f, y), Size(size.width, 1.dp.toPx())); y += 34.dp.toPx() }
                    drawRect(Brush.horizontalGradient(listOf(Color(0x59503C14), Color(0x00503C14))), Offset.Zero, Size(14.dp.toPx(), size.height))
                },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 26.dp, end = 30.dp, top = 18.dp, bottom = 90.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFEDE4CC)).clickable { nav.back() }.semantics { contentDescription = backLabel }, contentAlignment = Alignment.Center) { Symbol("ui.back", tint = Ink, size = 18.dp) }
                    Column {
                        Text(stringResource(R.string.nature_title), fontFamily = LocalType.hand, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 40.sp, color = Leaf)
                        Text(listOfNotNull(place, seasonWord(today, here.lat < 0)).joinToString(" · "), fontFamily = LocalType.cormorant, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Garamond)
                    }
                }
                Spacer(Modifier.height(22.dp))
                when {
                    notes == null -> Text(stringResource(R.string.kit_loading), color = Garamond, fontSize = 14.sp)
                    notes!!.isEmpty() -> StateMessage(kind.symbol, stringResource(R.string.nature_none, kindName(kind).lowercase(Locale.getDefault())), text = if (!services.local.online()) stringResource(R.string.picnic_offline_text) else null, tint = Garamond)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        for ((i, n) in notes!!.withIndex()) NoteEntry(n, i, now, zone, onClick = { services.remember(n.place); nav.go(Dest.Place.of(n.place)) })
                    }
                }
            }
            // The pencil: a note for the mappers.
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp).size(56.dp).cssShadow(CircleShape, 6.dp, 14.dp, Color(0x4D000000)).clip(CircleShape).background(Leaf)
                    .clickable { nav.go(Dest.Quests(here.lat, here.lon)) }.semantics { contentDescription = addLabel },
                contentAlignment = Alignment.Center,
            ) { Symbol("ui.edit", tint = Color(0xFFF6F0DF), size = 22.dp) }
        }
        // The spiral rings.
        Column(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).fillMaxHeight(0.84f).offset(y = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            repeat(8) { Box(Modifier.size(20.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(Brush.verticalGradient(listOf(Color(0xFFE8EBEE), Color(0xFF8E949C))))) }
        }
        // The index tabs on the right edge.
        Column(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (k in NatureKind.entries) {
                val on = k == kind
                val label = kindName(k)
                Box(
                    Modifier.offset(x = if (on) 6.dp else 0.dp).size(if (on) 44.dp else 40.dp, 70.dp).clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)).background(Color(k.colour.toInt()))
                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) { kind = k }.semantics { contentDescription = label; selected = on },
                    contentAlignment = Alignment.Center,
                ) { Glyph(k.symbol, Color.White, 20.dp) }
            }
        }
    }
}

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
    val tilt = listOf(-3f, 2f, -1.5f, 2.5f, -2f)[index % 5]
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(92.dp, 104.dp).rotate(tilt).cssShadow(RoundedCornerShape(1.dp), 2.dp, 5.dp, Color(0x26000000)).background(Color(0xFFFBF8EE))) {
            Box(Modifier.align(if (index % 2 == 0) Alignment.TopStart else Alignment.TopEnd).offset(x = if (index % 2 == 0) (-8).dp else 8.dp, y = (-6).dp).rotate(if (index % 2 == 0) -30f else 30f).size(36.dp, 14.dp).background(Color(0xCCE9D7A0)))
            Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) { Glyph(n.art, Color(0xFF6E7A3A).takeIf { n.art == "nature.tree" } ?: Color.Unspecified, 72.dp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(head, fontFamily = LocalType.hand, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 27.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            n.latin?.let { Text(it, fontFamily = LocalType.cormorant, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Garamond, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            texts.firstOrNull()?.let { Text(it, fontFamily = LocalType.hand, fontSize = 21.sp, lineHeight = 24.sp, color = if (n.facts.firstOrNull() is NoteFact.Autumn || n.facts.firstOrNull() is NoteFact.Blossom) Color(0xFF9A6A00) else Ink, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text((texts.drop(1).take(1) + listOf(walkOrDistance(n.distanceM))).joinToString(" · "), color = Color(0xFF2F5A2A), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
