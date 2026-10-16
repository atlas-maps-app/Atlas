package app.atlas.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalBottomBarInset
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.services.SettingsKeys
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.mine.art.MineArtImage
import app.atlas.ui.mine.offline.OfflineModel
import app.atlas.ui.mine.offline.RegionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Mine board: a desk with your things laid on it. A navy passport with a sticky note, the red notebook of lists, the
 * folded paper map of what is offline, the medal of map fixes, a luggage tag for the car and a ticket for the data
 * sources, and the buttons below. Every object shows a number that is really on this phone, and nothing is uploaded.
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
    val desk = MineTone.of(0xFFE4DED2, 0xFF17160F)
    BoxWithConstraints(Modifier.fillMaxSize().background(desk)) {
        val u = maxWidth.value / 412f
        fun d(v: Float): Dp = (v * u).dp
        Grain(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(d(826f)).padding(top = 14.dp)) {
                // Title and the promise.
                Column(Modifier.padding(start = d(16f), end = d(16f), top = d(0f)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.mine_title), style = androidx.compose.ui.text.TextStyle(fontFamily = BricolageFamily, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp), color = androidx.compose.ui.graphics.Color(0xFF14161B).let { if (androidx.compose.foundation.isSystemInDarkTheme() && false) it else app.atlas.ui.kit.Atlas.colors.ink })
                    Row(
                        Modifier.height(32.dp).clip(CircleShape).background(Color(0xFF14161B)).clickable { nav.go(Dest.Privacy) }.padding(start = 9.dp, end = 12.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Symbol("ui.shield-check", tint = Color(0xFF7FE0A8), size = 17.dp)
                        Text(stringResource(R.string.mine_no_account), style = mt(13f, 600), color = Color.White, maxLines = 1)
                    }
                }
                Passport(places, trailOn, d(22f), d(104f), u) { nav.go(Dest.Privacy) }
                Notebook(lists.size, lists.sumOf { it.places.size }, d(206f), d(96f), u) { nav.go(Dest.Lists()) }
                PaperMap(details.size, details.sumOf { it.bytes }, d(18f), d(358f), u) { nav.go(Dest.Offline) }
                Medal(answers.size, d(254f), d(356f), u) { nav.go(Dest.Quests()) }
                CarTag(carName, d(30f), d(524f), u) { nav.go(Dest.Charge()) }
                Ticket(sourcesOn, keysSet, d(194f), d(514f), u) { nav.go(Dest.DataSources()) }
                // The buttons.
                val labels = listOf(
                    Triple("ui.settings", R.string.mine_btn_settings, Dest.Settings()),
                    Triple("ui.layers", R.string.mine_btn_look, Dest.Layers),
                    Triple("ui.watch", R.string.mine_btn_watch, Dest.Watch),
                    Triple("local.gps", R.string.mine_btn_satellites, Dest.Satellites),
                    Triple("ui.privacy", R.string.mine_btn_privacy, Dest.Privacy),
                    Triple("ui.flag", R.string.mine_btn_tour, Dest.Onboarding()),
                )
                Column(Modifier.offset(d(16f), d(634f)).width(d(380f)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    for (row in labels.chunked(3)) Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        for ((symbol, label, dest) in row) DeskButton(symbol, stringResource(label), Modifier.weight(1f)) { nav.go(dest) }
                    }
                }
                Text(
                    stringResource(R.string.mine_nothing_uploaded),
                    Modifier.offset(d(20f), d(748f)).width(d(372f)),
                    style = mt(11f, 400, line = 14.85f), color = MineTone.of(0xFF5E574A, 0xFFB5AE9E),
                )
            }
            Spacer(Modifier.height(LocalBottomBarInset.current))
        }
    }
}

/** The desk's paper: fine horizontal and vertical lines every 3 px at 5 %, and a soft light at its middle. */
@Composable
private fun Grain(modifier: Modifier) {
    val ink = Color(90, 80, 60)
    Canvas(modifier) {
        val line = ink.copy(alpha = 0.05f)
        var y = 0f
        val step = 3f * density
        while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f * density); y += step }
        var x = 0f
        while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f * density); x += step }
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center = Offset(size.width / 2, size.height * 0.4f), radius = size.width * 0.7f))
    }
}

private val DeskShadow = Color(0x59281E14)
private val DeskShadowSmall = Color(0x40281E14)

/** The navy passport, -5°: gold ATLAS over a gold globe, the sticky note across its corner. */
@Composable
private fun Passport(places: Int, trailOn: Boolean, x: Dp, y: Dp, u: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 6.dp * u, topEnd = 12.dp * u, bottomEnd = 12.dp * u, bottomStart = 6.dp * u)
    val label = stringResource(R.string.mine_passport_cd, if (trailOn) pluralStringResource(R.plurals.mine_n_places, places, places) else stringResource(R.string.mine_passport_off))
    val gold = Color(0xFFD6B35E)
    // The sticky note hangs over the passport's edge as on the board, so it sits outside the clipped cover.
    Box(Modifier.offset(x, y).size(160.dp * u, 214.dp * u).graphicsLayer { rotationZ = -5f }.clickable(onClick = onClick).semantics { contentDescription = label }) {
    Box(
        Modifier.fillMaxSize()
            .cssShadow(shape, 16.dp * u, 28.dp * u, DeskShadow).cssShadow(shape, 3.dp * u, 6.dp * u, DeskShadowSmall)
            .clip(shape).background(Color(0xFF1E2C52)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent), center = Offset(size.width * 0.3f, size.height * 0.2f), radius = size.width * 0.9f))
            // Diagonal cloth: 35° lines, 2 px dark every 4 px.
            rotate(35f, Offset(size.width / 2, size.height / 2)) {
                var o = -size.height
                while (o < size.height * 2) { drawLine(Color.Black.copy(alpha = 0.06f), Offset(o, -size.height), Offset(o, size.height * 2), 2f * density); o += 4f * density }
            }
            drawRect(Color(0xFF16213F), size = Size(5f * density * u, size.height))
        }
        Column(Modifier.fillMaxWidth().padding(top = 26.dp * u), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp * u)) {
            Text("ATLAS", style = mt(14f * u, 700, spacing = 4.48f * u), color = gold, modifier = Modifier.padding(start = 4.5.dp * u))
            MineArtImage("mine.globe", 64.dp * u, tint = gold)
            Text(stringResource(R.string.mine_passport_label), style = mt(11f * u, 600, spacing = 2.64f * u), color = gold, modifier = Modifier.padding(start = 2.6.dp * u))
        }
    }
        Sticky(places, trailOn, u, Modifier.offset(88.dp * u, 150.dp * u))
    }
}

@Composable
private fun Sticky(places: Int, trailOn: Boolean, u: Float, modifier: Modifier) {
    Column(
        modifier.size(84.dp * u, 72.dp * u).graphicsLayer { rotationZ = 9f }.cssShadow(RoundedCornerShape(0.dp), 6.dp * u, 10.dp * u, DeskShadow).background(Color(0xFFFFE680)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        val hand = androidx.compose.ui.text.TextStyle(fontFamily = CaveatFamily, fontWeight = FontWeight.Bold)
        Text(if (trailOn) "$places" else stringResource(R.string.mine_passport_off), style = hand.copy(fontSize = 28.sp * u, lineHeight = 28.sp * u), color = Color(0xFF3A2E0A))
        Text(if (trailOn) pluralStringResource(R.plurals.mine_n_places, places, places).substringAfter(' ') else stringResource(R.string.mine_passport_where), style = hand.copy(fontSize = 15.sp * u, lineHeight = 15.sp * u), color = Color(0xFF3A2E0A), textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** The red notebook, +3.5°: an elastic band, a paper label in handwriting, a purple ribbon below. */
@Composable
private fun Notebook(lists: Int, places: Int, x: Dp, y: Dp, u: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 4.dp * u, topEnd = 12.dp * u, bottomEnd = 12.dp * u, bottomStart = 4.dp * u)
    val label = stringResource(R.string.mine_lists_cd, lists, places)
    Box(
        Modifier.offset(x, y).size(170.dp * u, 222.dp * u).graphicsLayer { rotationZ = 3.5f }.clickable(onClick = onClick).semantics { contentDescription = label },
    ) {
        Box(
            Modifier.fillMaxSize().cssShadow(shape, 16.dp * u, 28.dp * u, DeskShadow).cssShadow(shape, 3.dp * u, 6.dp * u, DeskShadowSmall).clip(shape).background(Color(0xFFC4452F)).drawBehind {
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), center = Offset(size.width * 0.7f, size.height * 0.15f), radius = size.width * 0.85f))
                var yy = 0f
                while (yy < size.height) { drawLine(Color.Black.copy(alpha = 0.05f), Offset(0f, yy), Offset(size.width, yy), 1f * density); yy += 3f * density }
                drawRect(Color(0xFFA5361F), size = Size(4f * density * u, size.height))
            },
        ) {
            // The elastic band.
            Box(Modifier.align(Alignment.TopEnd).padding(end = 24.dp * u).width(7.dp * u).fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF1A1A1A), Color(0xFF3A3A3A), Color(0xFF1A1A1A)))))
            Column(
                Modifier.offset(22.dp * u, 40.dp * u).size(100.dp * u, 74.dp * u).clip(RoundedCornerShape(4.dp * u)).background(Color(0xFFFBF6EA)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text(stringResource(R.string.mine_lists_label), style = androidx.compose.ui.text.TextStyle(fontFamily = CaveatFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp * u, lineHeight = 31.sp * u), color = Color(0xFF2A2622))
                Text(pluralStringResource(R.plurals.mine_n_lists, lists, lists) + " · " + pluralStringResource(R.plurals.mine_n_places, places, places), style = mt(11f * u, 600, line = 13f * u), color = Color(0xFF6A6358), maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp * u))
            }
        }
        // The ribbon hangs out of the bottom edge.
        Canvas(Modifier.offset(44.dp * u, 222.dp * u - 12.dp * u).size(12.dp * u, 34.dp * u)) {
            val p = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(size.width / 2, size.height * 0.8f); lineTo(0f, size.height); close() }
            drawPath(p, Color(0xFF6A4BC4))
        }
    }
}

/** The folded paper map, +2°, with a white label: how many regions are offline and how much they take. */
@Composable
private fun PaperMap(regions: Int, bytes: Long, x: Dp, y: Dp, u: Float, onClick: () -> Unit) {
    val (n, unit) = OfflineModel.total(bytes)
    val label = stringResource(R.string.mine_offline_cd, regions, "$n $unit")
    Box(Modifier.offset(x, y).size(214.dp * u, 138.dp * u).graphicsLayer { rotationZ = 2f }.clickable(onClick = onClick).semantics { contentDescription = label }) {
        Row(Modifier.fillMaxSize().cssShadow(RoundedCornerShape(2.dp), 12.dp * u, 14.dp * u, Color(0x4D281E14)).clip(RoundedCornerShape(2.dp))) {
            for (i in 0 until 5) Box(Modifier.weight(1f).fillMaxSize().background(if (i % 2 == 0) Color(0xFFF3EEDF) else Color(0xFFE4DDC9)))
        }
        MineArtImage("mine.paper", 214.dp * u, Modifier.size(214.dp * u, 138.dp * u))
        Row(
            Modifier.offset(12.dp * u, 12.dp * u).cssShadow(RoundedCornerShape(4.dp), 2.dp, 5.dp, Color(0x40281E14)).clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 9.dp * u, vertical = 6.dp * u),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp * u), verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol("ui.offline", tint = Color(0xFF178A4E), size = 18.dp * u)
            Column {
                Text(stringResource(R.string.mine_offline_title), style = mt(13f * u, 700, line = 15f * u), color = Color(0xFF14161B))
                Text(if (regions == 0) stringResource(R.string.mine_offline_none_sub) else pluralStringResource(R.plurals.mine_n_regions, regions, regions) + " · $n $unit", style = mt(11f * u, 500, line = 12.65f * u), color = Color(0xFF4A505E), maxLines = 1)
            }
        }
    }
}

/** The gold-rimmed green medal, -8°: how many map fixes you have made. */
@Composable
private fun Medal(fixes: Int, x: Dp, y: Dp, u: Float, onClick: () -> Unit) {
    val label = stringResource(R.string.mine_fixes_cd, fixes)
    Box(
        Modifier.offset(x, y).size(124.dp * u).graphicsLayer { rotationZ = -8f }.cssShadow(CircleShape, 12.dp * u, 20.dp * u, Color(0x59281E14)).clip(CircleShape)
            .drawBehind {
                // conic-gradient(from 20deg, ...): CSS starts at the top, a sweep gradient at three o'clock.
                rotate(-70f) {
                    drawCircle(Brush.sweepGradient(listOf(Color(0xFFF3D68A), Color(0xFFA67C2C), Color(0xFFF0CE7A), Color(0xFF8E6522), Color(0xFFF3D68A))))
                }
            }
            .clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.size(100.dp * u).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFF3E9A67), Color(0xFF1D6B41)), center = Offset(35f * u * 1.0f, 30f * u * 1.0f), radius = 90f * u)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Symbol("ui.wrench", tint = Color(0xFFF4EFD8), size = 26.dp * u)
            Text("$fixes", style = mt(20f * u, 800, tabular = true, line = 20f * u), color = Color(0xFFF4EFD8))
            Text(stringResource(R.string.mine_fixes_label), style = mt(11f * u, 700, spacing = 0.88f * u, line = 13f * u), color = Color(0xFFF4EFD8))
        }
    }
}

/** A tag shape: a pointed left end with a punched hole, as the board's luggage tag. */
private class TagShape(private val tip: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val t = with(density) { tip.toPx() }
        return Outline.Generic(Path().apply { moveTo(t, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(t, size.height); lineTo(0f, size.height / 2); close() })
    }
}

/** The yellow luggage tag, +4°: the car you charge, or an invitation to pick one. */
@Composable
private fun CarTag(car: String?, x: Dp, y: Dp, u: Float, onClick: () -> Unit) {
    val sub = car ?: stringResource(R.string.mine_car_sub_none)
    val label = stringResource(R.string.mine_car_cd, sub)
    Box(Modifier.offset(x, y).size(150.dp * u, 62.dp * u).graphicsLayer { rotationZ = 4f }.clickable(onClick = onClick).semantics { contentDescription = label }) {
        Box(Modifier.fillMaxSize().cssShadow(TagShape(22.dp * u), 8.dp * u, 10.dp * u, Color(0x47281E14)).clip(TagShape(22.dp * u)).background(Color(0xFFE3B04B)))
        Box(Modifier.offset(11.dp * u, 26.dp * u).size(10.dp * u).clip(CircleShape).background(Color(0xFFE4DED2)))
        Row(Modifier.offset(30.dp * u, 0.dp).fillMaxSize().padding(end = 10.dp * u), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp * u), verticalAlignment = Alignment.CenterVertically) {
            Symbol("ui.charge", tint = Color(0xFF3A2A08), size = 24.dp * u)
            Column {
                Text(stringResource(R.string.mine_car_label), style = mt(13f * u, 700, line = 15f * u), color = Color(0xFF3A2A08))
                Text(sub, style = mt(11f * u, 500, line = 12.65f * u), color = Color(0xFF3A2A08), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

/** A ticket stub, −3°, with punched sides: the data sources that are on and the keys entered. */
@Composable
private fun Ticket(on: Int, keys: Int, x: Dp, y: Dp, u: Float, onClick: () -> Unit) {
    val label = stringResource(R.string.mine_sources_cd, on, keys)
    val shape = TicketShape(8.dp * u)
    val typer = androidx.compose.ui.text.TextStyle(fontFamily = CourierFamily, fontWeight = FontWeight.Bold)
    Box(Modifier.offset(x, y).size(198.dp * u, 72.dp * u).graphicsLayer { rotationZ = -3f }.clickable(onClick = onClick).semantics { contentDescription = label }) {
        Box(Modifier.fillMaxSize().cssShadow(shape, 8.dp * u, 14.dp * u, Color(0x40281E14)).clip(shape).background(Color(0xFFFBF8EF)))
        MineArtImage("mine.trail", 32.dp * u, Modifier.offset(14.dp * u, 20.dp * u), tint = Color(0xFFC4452F))
        Column(Modifier.offset(52.dp * u, 17.dp * u)) {
            Text(stringResource(R.string.mine_sources_label), style = typer.copy(fontSize = 11.sp * u, lineHeight = 13.2.sp * u, letterSpacing = 0.66.sp * u), color = Color(0xFF6A6358), maxLines = 1)
            Text(stringResource(R.string.mine_sources_on, on), style = typer.copy(fontSize = 17.sp * u, lineHeight = 20.4.sp * u), color = Color(0xFF2A2622), maxLines = 1)
        }
        Box(Modifier.offset(140.dp * u, 10.dp * u).width(1.5.dp * u).height(52.dp * u).drawBehind {
            drawLine(Color(0xFFCFC6B0), Offset(0f, 0f), Offset(0f, size.height), 1.5f * density * u, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density)))
        })
        Column(Modifier.offset(141.dp * u, 17.dp * u).width(49.dp * u), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$keys", style = typer.copy(fontSize = 17.sp * u, lineHeight = 20.4.sp * u), color = Color(0xFF2A2622))
            Text(stringResource(R.string.mine_sources_keys, keys).substringAfter(' '), style = typer.copy(fontSize = 11.sp * u, lineHeight = 13.2.sp * u), color = Color(0xFF6A6358), maxLines = 1)
        }
    }
}

/** A rectangle with a half circle bitten out of the middle of each short side, as a ticket stub. */
private class TicketShape(private val r: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rad = with(density) { r.toPx() }
        val left = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(-rad, size.height / 2 - rad), Size(rad * 2, rad * 2))) }
        val right = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(size.width - rad, size.height / 2 - rad), Size(rad * 2, rad * 2))) }
        val box = Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }
        val cut = Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, box, left)
        return Outline.Generic(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, cut, right))
    }
}

/** One of the six buttons under the desk: a white glass tile with a symbol and a name. */
@Composable
private fun DeskButton(symbol: String, text: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.height(46.dp).cssShadow(shape, 2.dp, 6.dp, Color(0x14281E14)).clip(shape).background(MineTone.of(0xC7FFFFFF, 0xCC24272D)).clickable(onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(symbol, tint = app.atlas.ui.kit.Atlas.colors.ink, size = 18.dp)
        Text(text, style = mt(12f, 700), color = app.atlas.ui.kit.Atlas.colors.ink, maxLines = 1)
    }
}
