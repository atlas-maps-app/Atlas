package app.atlas.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssConic
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.cssRadial
import app.atlas.ui.kit.cssRepeatingLinear
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.mine.offline.OfflineModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/* The Mine board's desk (`canvas/project/Mine.dc.html`): its paper, the objects' inks and their shadows. */
private const val DESK: Argb = 0xFFE4DED2
private const val GOLD: Argb = 0xFFD6B35E
private const val NOTE_INK: Argb = 0xFF3A2E0A
private const val PAPER_INK: Argb = 0xFF2A2622
private const val PAPER_SOFT: Argb = 0xFF6A6358
private const val TAG_INK: Argb = 0xFF3A2A08
private val DROP = CssShadow(0f, 16f, 28f, 0f, 0x59281E14)
private val DROP_NEAR = CssShadow(0f, 3f, 6f, 0f, 0x40281E14)

/**
 * The Mine board: a desk with your things laid on it. A navy passport with a sticky note, the red notebook of lists, the
 * folded paper map of what is offline, the medal of map fixes, a luggage tag for the car and a ticket for the data
 * sources, and the buttons below. Every object shows a number that is really on this phone, and nothing is uploaded.
 * The objects are the board's, at its px, turned as it turns them.
 */
@Composable
fun MineScreen(args: Dest.Mine, nav: Navigator) {
    val services = LocalServices.current
    val details by services.mine.offline.details.collectAsStateWithLifecycle()
    val lists by services.saved.lists.collectAsStateWithLifecycle()
    val answers by services.saved.answers.collectAsStateWithLifecycle()
    val trailOn by services.settings.flow(MineKeys.TRAIL, false).collectAsStateWithLifecycle(false)
    val off by services.settings.flow(SettingsKeys.PROVIDERS_OFF, emptySet()).collectAsStateWithLifecycle(emptySet())
    val keys by services.keys.keys.collectAsStateWithLifecycle()
    val car by services.settings.flow(MineKeys.EV_MODEL).collectAsStateWithLifecycle(null)
    // The trail file is read off the main thread: a year of fixes is a megabyte or so.
    val places by produceState(0, trailOn) {
        value = if (!trailOn) 0 else withContext(Dispatchers.IO) {
            val start = LocalDate.now(services.clock).withDayOfYear(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            services.mine.trail.places(start)
        }
    }
    val rows = SourcesModel.rows(off, keys.orEmpty())
    val (sourcesOn, keysSet) = SourcesModel.summary(rows)
    val carName = remember(car) { car?.let { id -> app.atlas.providers.EvModels.bundled().byId(id)?.let { "${it.make} ${it.model}" } } }
    // The desk's paper: hairlines every 3 px both ways at 5 % and a soft light at its middle (the frame's background).
    val paper = Modifier.bbox(
        DESK,
        grad = listOf(
            cssRepeatingLinear(0f, 3f, 0f to 0x0D5A503C, 1f to 0x0D5A503C, 1f to 0x005A503C, 3f to 0x005A503C),
            cssRepeatingLinear(90f, 3f, 0f to 0x0D5A503C, 1f to 0x0D5A503C, 1f to 0x005A503C, 3f to 0x005A503C),
            cssRadial(false, 206f, 366f, 0f to 0x59FFFFFF, 0.7f to 0x00FFFFFF),
        ),
    )
    Box(Modifier.fillMaxSize().then(paper)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(820.dp + shift())) {
                val s = shift()
                // The title (Bricolage 40, 800, line 40, −0.02 em) and the promise pill 6 px under it.
                Column(Modifier.offset(16.dp, 42.dp + s), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BText(stringResource(R.string.mine_title), bt(40f, 800, 40f, "Bricolage Grotesque", spacing = -0.8f), INK, Modifier.semantics { heading() })
                    TextPill(
                        stringResource(R.string.mine_no_account), bt(13f, 600, 17f), INK, WHITE, h = 32f, symbol = "ui.shield-check", symbolSize = 17f, gap = 6f,
                        tint = 0xFF7FE0A8, padStart = 9f, padEnd = 12f, onClick = { nav.go(Dest.Privacy) },
                    )
                }
                Passport(places, trailOn, s) { nav.go(Dest.Privacy) }
                Notebook(lists.size, lists.sumOf { it.places.size }, s) { nav.go(Dest.Lists()) }
                PaperMap(details.size, details.sumOf { it.bytes }, s) { nav.go(Dest.Offline) }
                Medal(answers.size, s) { nav.go(Dest.Quests()) }
                CarTag(carName, s) { nav.go(Dest.Charge()) }
                Ticket(sourcesOn, keysSet, s) { nav.go(Dest.DataSources()) }
                // The buttons: a grid of three at y 676, 8 px apart, its columns as the board's grid sizes them.
                val labels = listOf(
                    Triple("ui.settings", R.string.mine_btn_settings, Dest.Settings()),
                    Triple("ui.layers.2", R.string.mine_btn_look, Dest.Layers),
                    Triple("ui.watch", R.string.mine_btn_watch, Dest.Watch),
                    Triple("local.gps", R.string.mine_btn_satellites, Dest.Satellites),
                    Triple("ui.privacy.2", R.string.mine_btn_privacy, Dest.Privacy),
                    Triple("ui.flag.2", R.string.mine_btn_tour, Dest.Onboarding()),
                )
                val widths = floatArrayOf(120.59f, 120.6f, 122.8f)
                Column(Modifier.offset(16.dp, 676.dp + s), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in labels.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((i, b) in row.withIndex()) DeskButton(b.first, stringResource(b.second), Modifier.width(widths[i].dp)) { nav.go(b.third) }
                    }
                }
                BText(stringResource(R.string.mine_nothing_uploaded), bt(11f, 400, 14.85f), 0xFF5E574A, Modifier.offset(20.dp, 790.dp + s).width(372.dp), lines = 2)
            }
            Spacer(Modifier.height(LocalBottomBarInset.current))
        }
    }
}

/** A desk object: its box at the board's px, turned [deg] about its centre as CSS's `rotate()` turns it. */
@Composable
private fun DeskObject(
    x: Float, y: Float, w: Float, h: Float, deg: Float, label: String, onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier.offset(x.dp, y.dp).requiredSize(w.dp, h.dp).graphicsLayer { rotationZ = deg }
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        content = content,
    )
}

/**
 * The navy passport (160 × 214, −5°): its cloth and inner edge, ATLAS, the gold globe and PASSPORT 26 px down, 12 px
 * apart, and the yellow note across its corner with this year's new places (or that the trail is off).
 */
@Composable
private fun Passport(places: Int, trailOn: Boolean, s: Dp, onClick: () -> Unit) {
    val label = stringResource(R.string.mine_passport_cd, if (trailOn) pluralStringResource(R.plurals.mine_n_places, places, places) else stringResource(R.string.mine_passport_off))
    DeskObject(22f, 146f + s.value, 160f, 214f, -5f, label, onClick) {
        Box(
            Modifier.fillMaxSize().bbox(
                0xFF1E2C52, CssCorners(6f, 12f, 12f, 6f),
                listOf(CssShadow(5f, 0f, 0f, 0f, 0xFF16213F, inset = true), DROP, DROP_NEAR),
                grad = listOf(
                    cssRadial(false, 48f, 42.8f, 0f to 0x1AFFFFFF, 0.6f to 0x00FFFFFF),
                    cssRepeatingLinear(35f, 4f, 0f to 0x0F000000, 2f to 0x0F000000, 2f to 0x00000000, 4f to 0x00000000),
                ),
                clip = false,
            ),
        )
        Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BText("ATLAS", bt(14f, 700, 18f, spacing = 4.48f), GOLD, Modifier.padding(start = 4.48.dp))
            MSy("ui.passport", 64f, GOLD)
            BText(stringResource(R.string.mine_passport_label), bt(11f, 600, 14f, spacing = 2.64f), GOLD, Modifier.padding(start = 2.64.dp))
        }
        // The note: 84 × 72 at (88, 150), turned 9° more, its two lines centred on `line-height: 1`.
        Column(
            Modifier.offset(88.dp, 150.dp).requiredSize(84.dp, 72.dp).graphicsLayer { rotationZ = 9f }
                .bbox(0xFFFFE680, shadows = listOf(CssShadow(0f, 6f, 10f, 0f, 0x4C281E14)), clip = false),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            BText(if (trailOn) "$places" else stringResource(R.string.mine_passport_off), bt(28f, 700, 28f, "Caveat"), NOTE_INK)
            BText(
                if (trailOn) pluralStringResource(R.plurals.mine_n_places, places, places).substringAfter(' ') else stringResource(R.string.mine_passport_where),
                bt(16f, 700, 16f, "Caveat", align = TextAlign.Center), NOTE_INK, lines = 2,
            )
        }
    }
}

/**
 * The red notebook (170 × 222, +3.5°): its cover and spine, the elastic band 24 px from the right, the paper label
 * (100 × 74 at 22, 40) with "Lists" in Caveat and the counts, and the purple ribbon hanging 22 px below.
 */
@Composable
private fun Notebook(lists: Int, places: Int, s: Dp, onClick: () -> Unit) {
    val label = stringResource(R.string.mine_lists_cd, lists, places)
    DeskObject(206f, 138f + s.value, 170f, 222f, 3.5f, label, onClick) {
        Box(
            Modifier.fillMaxSize().bbox(
                0xFFC4452F, CssCorners(4f, 12f, 12f, 4f),
                listOf(CssShadow(4f, 0f, 0f, 0f, 0xFFA5361F, inset = true), DROP, DROP_NEAR),
                grad = listOf(
                    cssRadial(false, 119f, 33.3f, 0f to 0x24FFFFFF, 0.55f to 0x00FFFFFF),
                    cssRepeatingLinear(0f, 3f, 0f to 0x0D000000, 1f to 0x0D000000, 1f to 0x00000000, 3f to 0x00000000),
                ),
                clip = false,
            ),
        )
        Box(Modifier.offset(139.dp, 0.dp).width(7.dp).fillMaxHeight().bbox(grad = listOf(cssLinear(90f, 0f to 0xFF1A1A1A, 0.5f to 0xFF3A3A3A, 1f to 0xFF1A1A1A))))
        Column(
            Modifier.offset(22.dp, 40.dp).requiredSize(100.dp, 74.dp).bbox(0xFFFBF6EA, cr(4f), listOf(CssShadow(0f, 0f, 0f, 1f, 0x14000000, inset = true))),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            BText(stringResource(R.string.mine_lists_label), bt(30f, 700, 31.5f, "Caveat"), PAPER_INK)
            BText(
                pluralStringResource(R.plurals.mine_n_lists, lists, lists) + " · " + pluralStringResource(R.plurals.mine_n_places, places, places),
                bt(11f, 600, 11.55f, align = TextAlign.Center), PAPER_SOFT, Modifier.padding(horizontal = 4.dp), lines = 2,
            )
        }
        Box(Modifier.offset(44.dp, 210.dp).requiredSize(12.dp, 34.dp).bbox(0xFF6A4BC4, outline = RibbonShape))
    }
}

/** The ribbon's end: CSS `clip-path: polygon(0 0, 100% 0, 100% 100%, 50% 80%, 0 100%)`. */
private object RibbonShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(
        Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(size.width / 2, size.height * 0.8f); lineTo(0f, size.height); close() },
    )
}

/**
 * The folded paper map (214 × 138, +2°): five panels of two creams under the board's streets and river, its drop shadow,
 * and the white label 12 px in with how many regions are offline and how much they take.
 */
@Composable
private fun PaperMap(regions: Int, bytes: Long, s: Dp, onClick: () -> Unit) {
    val (n, unit) = OfflineModel.total(bytes)
    val label = stringResource(R.string.mine_offline_cd, regions, "$n $unit")
    DeskObject(18f, 400f + s.value, 214f, 138f, 2f, label, onClick) {
        Row(Modifier.fillMaxSize().bbox(r = cr(2f), shadows = listOf(CssShadow(0f, 12f, 14f, 0f, 0x4D281E14)))) {
            for (i in 0 until 5) Box(Modifier.weight(1f).fillMaxHeight().bbox(if (i % 2 == 0) 0xFFF3EEDF else 0xFFE4DDC9))
        }
        MSy("mine.paper", 214f, h = 138f)
        Row(
            Modifier.offset(12.dp, 12.dp).bbox(WHITE, cr(4f), listOf(CssShadow(0f, 2f, 5f, 0f, 0x40281E14)), clip = false).padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("ui.offline", 18f, 0xFF178A4E)
            Column {
                BText(stringResource(R.string.mine_offline_title), bt(13f, 700, 14.95f), INK)
                BText(
                    if (regions == 0) stringResource(R.string.mine_offline_none_sub) else pluralStringResource(R.plurals.mine_n_regions, regions, regions) + " · $n $unit",
                    bt(11f, 500, 12.65f), 0xFF4A505E,
                )
            }
        }
    }
}

/** The gold medal (124 px, −8°): its conic rim, the green face (100 px, a light at 35 % 30 %), the wrench, the count and FIXES. */
@Composable
private fun Medal(fixes: Int, s: Dp, onClick: () -> Unit) {
    val label = stringResource(R.string.mine_fixes_cd, fixes)
    DeskObject(254f, 398f + s.value, 124f, 124f, -8f, label, onClick) {
        Box(
            Modifier.fillMaxSize().bbox(
                r = cr(62f), shadows = listOf(CssShadow(0f, 12f, 20f, 0f, 0x59281E14)),
                grad = listOf(cssConic(20f, 62f, 62f, 0f to 0xFFF3D68A, 0.25f to 0xFFA67C2C, 0.5f to 0xFFF0CE7A, 0.75f to 0xFF8E6522, 1f to 0xFFF3D68A)),
                clip = false,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.size(100.dp).bbox(
                    r = cr(50f), shadows = listOf(CssShadow(0f, 2f, 6f, 0f, 0x73000000, inset = true)),
                    grad = listOf(cssRadial(true, 35f, 30f, 0f to 0xFF3E9A67, 1f to 0xFF1D6B41)),
                ),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                MSy("ui.wrench", 26f, 0xFFF4EFD8)
                BText("$fixes", bt(20f, 800, 20f, tnum = true), 0xFFF4EFD8)
                BText(stringResource(R.string.mine_fixes_label), bt(11f, 700, 14f, spacing = 0.88f), 0xFFF4EFD8)
            }
        }
    }
}

/** The luggage tag's outline: CSS `clip-path: polygon(22px 0, 100% 0, 100% 100%, 22px 100%, 0 50%)`. */
private object TagShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = 22f * density.density
        return Outline.Generic(Path().apply { moveTo(t, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(t, size.height); lineTo(0f, size.height / 2); close() })
    }
}

/** The yellow luggage tag (150 × 62, +4°): its punched hole, and the car you charge with (or an invitation to pick one). */
@Composable
private fun CarTag(car: String?, s: Dp, onClick: () -> Unit) {
    val sub = car ?: stringResource(R.string.mine_car_sub_none)
    val label = stringResource(R.string.mine_car_cd, sub)
    DeskObject(30f, 566f + s.value, 150f, 62f, 4f, label, onClick) {
        Box(Modifier.fillMaxSize().bbox(0xFFE3B04B, shadows = listOf(CssShadow(0f, 8f, 10f, 0f, 0x47281E14)), outline = TagShape))
        Box(Modifier.offset(11.dp, 26.dp).size(10.dp).bbox(DESK, cr(5f), listOf(CssShadow(0f, 1f, 2f, 0f, 0x66000000, inset = true))))
        Row(
            Modifier.padding(start = 30.dp, end = 10.dp).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MSy("ui.charge", 24f, TAG_INK)
            Column {
                BText(stringResource(R.string.mine_car_label), bt(13f, 700, 14.95f), TAG_INK)
                BText(sub, bt(11f, 500, 12.65f, tnum = true), TAG_INK)
            }
        }
    }
}

/**
 * The ticket stub (198 × 72, −3°): cream, a half circle of 8 px bitten from the middle of each end (its mask also takes
 * its shadow), the red trail, the typewritten count of sources on, the dashed tear and the keys entered.
 */
@Composable
private fun Ticket(on: Int, keys: Int, s: Dp, onClick: () -> Unit) {
    val label = stringResource(R.string.mine_sources_cd, on, keys)
    DeskObject(194f, 556f + s.value, 198f, 72f, -3f, label, onClick) {
        Box(Modifier.fillMaxSize().bbox(0xFFFBF8EF, outline = TicketShape))
        MSy("ui.backtrack", 32f, 0xFFC4452F, Modifier.offset(14.dp, 20.dp))
        Column(Modifier.offset(52.dp, 17.dp)) {
            BText(stringResource(R.string.mine_sources_label), bt(11f, 700, 13.2f, "Courier Prime", spacing = 0.66f), PAPER_SOFT)
            BText(stringResource(R.string.mine_sources_on, on), bt(17f, 700, 20.4f, "Courier Prime", tnum = true), PAPER_INK)
        }
        // The tear: a 1.5 px dashed line from 10 px to 10 px from the ends, at x 140.
        Box(
            Modifier.offset(140.dp, 10.dp).width(1.5.dp).height(52.dp).drawWithContent {
                drawLine(
                    argb(0xFFCFC6B0), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), size.width,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.5f * density, 3f * density)),
                )
            },
        )
        Column(Modifier.offset(141.dp, 17.dp).width(49.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BText("$keys", bt(17f, 700, 20.4f, "Courier Prime", tnum = true), PAPER_INK)
            BText(stringResource(R.string.mine_sources_keys, keys).substringAfter(' '), bt(11f, 700, 13.2f, "Courier Prime"), PAPER_SOFT)
        }
    }
}

/** The ticket's outline: its box less a circle of 8 px at the middle of each end (the board's radial-gradient mask). */
private object TicketShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = 8.25f * density.density
        val box = Path().apply { addRect(Rect(Offset.Zero, size)) }
        val left = Path().apply { addOval(Rect(Offset(-r, size.height / 2 - r), Size(r * 2, r * 2))) }
        val right = Path().apply { addOval(Rect(Offset(size.width - r, size.height / 2 - r), Size(r * 2, r * 2))) }
        return Outline.Generic(Path.combine(PathOperation.Difference, Path.combine(PathOperation.Difference, box, left), right))
    }
}

/** One of the six buttons under the desk: 46 high, radius 14, 78 % white with its two soft shadows, the 18 px symbol, 8 px, the name (13, 700). */
@Composable
private fun DeskButton(symbol: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(46.dp).bbox(0xC7FFFFFF, cr(14f), listOf(CssShadow(0f, 2f, 6f, 0f, 0x14281E14), CssShadow(0f, 0f, 0f, 1f, 0x0F281E14)))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        MSy(symbol, 18f, INK)
        BText(text, bt(13f, 700, 17f), INK)
    }
}
