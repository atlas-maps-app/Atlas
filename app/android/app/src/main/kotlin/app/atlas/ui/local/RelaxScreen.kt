package app.atlas.ui.local

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.TagFilter
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.appClock
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.rememberHere
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import app.atlas.ui.kit.cssLinear
import app.atlas.ui.kit.CssShadow

private val Ink = Color(0xFF2A2A26)
private val Cream = Color(0xFFF6EFD8)
private val Soft = Color(0xFF6A6450)

private class Way(val id: String, val filter: String, val symbol: String, val tint: Long, val label: Int, val desc: Int, val commercial: Boolean)

private val WAYS = listOf(
    Way("sauna", "leisure=sauna or amenity=sauna", "place.sauna", 0xFF2A2A26, R.string.relax_sauna, R.string.relax_sauna_desc, true),
    Way("thermal", "amenity=public_bath or leisure=spa or amenity=spa or natural=hot_spring", "place.spa", 0xFF1560A8, R.string.relax_thermal, R.string.relax_thermal_desc, true),
    Way("massage", "shop=massage or amenity=massage or healthcare=massage", "place.massage", 0xFF8C5A2B, R.string.relax_massage, R.string.relax_massage_desc, true),
    Way("yoga", "sport=yoga or amenity=yoga or leisure=yoga", "place.yoga", 0xFF7B4DFF, R.string.relax_yoga, R.string.relax_yoga_desc, true),
    Way("library", "amenity=library", "place.books", 0xFF2F6B3A, R.string.relax_library, R.string.relax_library_desc, false),
    Way("garden", "leisure=garden and name and not access=private and not access=no", "nature.tree", 0xFF2F6B3A, R.string.relax_garden, R.string.relax_garden_desc, false),
    Way("stillness", "amenity=place_of_worship and not access=private and not access=no", "place.worship", 0xFF6A5A40, R.string.relax_stillness, R.string.relax_stillness_desc, false),
)

private const val RADIUS = 3_000.0

/** A noise reading on the dial: the level, and whether the microphone could be used. */
private class Reading(val db: Double?)

/**
 * Peace and quiet: a sound meter that listens to the room through the phone's microphone (a guide, not an instrument), and the
 * saunas, thermal baths, massage, yoga, libraries, gardens and quiet churches near you, free ones on request.
 */
@Composable
fun RelaxScreen(args: Dest.Relax, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val clock = appClock()
    val local = remember(clock) { LocalDateTime.ofInstant(clock.instant(), clock.zone) }
    var kind by rememberSaveable { mutableStateOf(args.kind?.takeIf { k -> WAYS.any { it.id == k } } ?: "sauna") }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Reading?>(null) }
    var listening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val found by produceState<Map<String, FindResult>?>(null, here) { value = services.local.finder.findMany(WAYS.associate { it.id to TagFilter.parse(it.filter) }, here, RADIUS, limit = 40) }
    val chosen = WAYS.first { it.id == kind }

    fun listen() {
        if (listening) return
        listening = true
        scope.launch {
            reading = Reading(services.local.soundMeter.measure())
            listening = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) listen() else reading = Reading(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun onTapMeter() {
        val have = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (have || services.local.soundMeterIsFake) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val items = found?.get(kind)?.places.orEmpty().filter { f ->
        !freeOnly || f.place.tags["fee"] == "no" || (!chosen.commercial && f.place.tags["fee"] != "yes")
    }

    Box(Modifier.fillMaxSize().boardBox(grad = listOf(cssLinear(180f, 0f to 0xFFE4E6DD, 1f to 0xFFEDEAE2)), clip = false)) {
        BoardPage(background = Color.Transparent) {
            BoardTitleBar(stringResource(R.string.relax_title), onBack = { nav.back() }, colour = Ink, size = 32)
            Spacer(Modifier.height(18.dp))
            SoundMeterCard(reading, listening, onTap = ::onTapMeter)
            Spacer(Modifier.height(20.dp))
            // The ways to unwind: four tiles of 78 px a row, 8 apart, and the free-only switch.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val cells: List<Way?> = WAYS + listOf(null)
                for (row in cells.chunked(4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (w in row) if (w != null) WayCell(w, w.id == kind, Modifier.weight(1f)) { kind = w.id } else FreeCell(freeOnly, Modifier.weight(1f)) { freeOnly = !freeOnly }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            when {
                found == null -> BoardLine(stringResource(R.string.kit_loading), boardStyle(14f, 400, Soft.css(), 18f))
                items.isEmpty() -> Box(Modifier.fillMaxWidth().boardBox(0xFFFFFFFF, corners(22f))) {
                    val err = found?.get(kind)?.error
                    StateMessage(
                        if (err != null) "ui-cloud-off" else chosen.symbol, stringResource(if (err != null) R.string.swim_offline else R.string.relax_none, app.atlas.ui.kit.shortDistance(RADIUS)),
                        text = if (err == null && !services.local.online()) stringResource(R.string.picnic_offline_text) else null, tint = if (err != null) Color(0xFFC8322A) else Soft,
                    )
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in items.take(6)) {
                        val t = f.place.tags
                        val facts = buildList {
                            when (t["fee"]) { "no" -> add(stringResource(R.string.swim_free)); "yes" -> add(stringResource(R.string.swim_paid)) }
                            if (t["wheelchair"] == "yes") add(stringResource(R.string.picnic_step_free))
                            if (t["internet_access"] == "wlan" && kind == "library") add("Wi-Fi")
                        }
                        // The board fades a place that costs, and marks it "$$" on a stone tile.
                        val paid = t["fee"] == "yes"
                        PlaceCard(
                            f, here, facts, local, RELAX_CARD, dim = paid,
                            tile = { dim ->
                                Box(Modifier.size(48.dp).boardBox(if (dim) 0xFF6A6450 else Ink.css(), corners(14f)), contentAlignment = Alignment.Center) {
                                    if (dim) BoardLine("$$", boardStyle(15f, 900, Cream.css(), 19.5f)) else Glyph(chosen.symbol, Cream, 26.dp)
                                }
                            },
                            onClick = { services.remember(f.place); nav.go(Dest.Place.of(f.place)) },
                        )
                    }
                }
            }
        }
    }
}

/** The board's quiet card: 82 px, radius 22, white; one that costs at 70 per cent. */
private val RELAX_CARD = CardLook(bg = 0xFFFFFFFF, radius = 22f, minHeight = 82f, shadows = emptyList(), ink = 0xFF2A2A26, soft = 0xFF6A6450, walkSize = 17f, walkLine = 22f, dimBg = 0xB2FFFFFF)

/** A way to unwind: 78 px, radius 20, its 26 px symbol and name 4 apart; ink with cream when chosen. */
@Composable
private fun WayCell(w: Way, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(w.label)
    val desc = stringResource(w.desc)
    Column(
        modifier.height(78.dp).boardBox(if (on) Ink.css() else 0xFFFFFFFF, corners(20f)).clickable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .semantics { contentDescription = desc; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Glyph(w.symbol, if (on) Cream else Color(w.tint.toInt()), 26.dp)
        BoardLine(label, boardStyle(12f, 800, if (on) Cream.css() else Ink.css(), 15.5f))
    }
}

/** The free-only switch: FREE in Geist 13 at 900 over "only" at 11, green on mint when on. */
@Composable
private fun FreeCell(on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val freeLabel = stringResource(R.string.relax_free_only)
    Column(
        modifier.height(78.dp).boardBox(if (on) 0xFFE1F2EA else 0xFFFFFFFF, corners(20f)).clickable(role = androidx.compose.ui.semantics.Role.Switch, onClick = onClick)
            .semantics { contentDescription = freeLabel; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val ink = if (on) 0xFF12663A else Soft.css()
        BoardLine(stringResource(R.string.relax_free), boardStyle(13f, 900, ink, 17f))
        BoardLine(stringResource(R.string.relax_only), boardStyle(11f, 700, ink, 14f))
    }
}

/** Where the board's scale puts a reading on the dial: 30 dB at 198.8 degrees round to 90 dB at 341.2 (screen angles). */
private fun dialAngle(db: Double): Double = Math.toRadians(198.8 + (db.coerceIn(30.0, 90.0) - 30.0) * 142.4 / 60.0)

/** The board's numbers round the dial, at their own places. */
private val DIAL_LABELS = listOf("30" to Offset(40f, 92f), "40" to Offset(66f, 60f), "50" to Offset(108f, 38f), "60" to Offset(146f, 30f), "70" to Offset(184f, 38f), "80" to Offset(226f, 60f), "90" to Offset(252f, 92f))

/** The board's ticks: `M46 100l8 4M72 66l6 6M110 44l4 7M146 36v8M182 44l-4 7M220 66l-6 6M246 100l-8 4`. */
private val DIAL_TICKS = listOf(46f to 100f, 54f to 104f, 72f to 66f, 78f to 72f, 110f to 44f, 114f to 51f, 146f to 36f, 146f to 44f, 182f to 44f, 178f to 51f, 220f to 66f, 214f to 72f, 246f to 100f, 238f to 104f)

/**
 * The sound level meter of the board: a 320 x 210 case (dark gradient, radius 18, its shadow and 2 px inner rim, 14 px
 * in), the cream dial of 150 px with its inner shadow, the green, amber and red arcs, the numbers and the needle, and
 * the reading in Geist Mono with its lamp. The phone's microphone is a guide, not an instrument: the scale says dB, not dB(A).
 */
@Composable
private fun SoundMeterCard(reading: Reading?, listening: Boolean, onTap: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, app.atlas.ui.kit.BoardFonts.GeistMono) }
    val db = reading?.db
    val note = stringResource(R.string.relax_meter_note)
    val desc = (if (db != null) stringResource(R.string.relax_meter_desc, db.roundToInt()) else stringResource(R.string.relax_meter_tap)) + ". " + note
    Column(
        Modifier.padding(start = 30.dp).size(320.dp, 210.dp)
            .boardBox(r = corners(18f), shadows = listOf(CssShadow(0f, 16f, 28f, 0f, 0x4C000000), CssShadow(0f, 0f, 0f, 2f, 0xFF4A4D4A, inset = true)), grad = listOf(cssLinear(180f, 0f to 0xFF3A3D3A, 1f to 0xFF25272A)), clip = false)
            .clickable(onClick = onTap).semantics { contentDescription = desc }.padding(14.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).boardBox(r = corners(10f), shadows = shadow(0f, 3f, 8f, 0x59000000, inset = true), grad = listOf(cssLinear(180f, 0f to 0xFFF6EFD8, 1f to 0xFFE9DFC0)))) {
            val s = size.width / 292f
            scale(s, s, Offset.Zero) {
                val arc = androidx.compose.ui.geometry.Rect(Offset(34f, 16f), Size(224f, 224f))
                drawArc(Ink, 180f, 180f, false, arc.topLeft, arc.size, style = Stroke(1.5f))
                drawArc(Color(0xFF5A9E6A), 180f, 56.31f, false, arc.topLeft, arc.size, style = Stroke(8f))
                drawArc(Color(0xFFE9B84A), 236.31f, 66.43f, false, arc.topLeft, arc.size, style = Stroke(8f))
                drawArc(Color(0xFFC8322A), 302.74f, 57.26f, false, arc.topLeft, arc.size, style = Stroke(8f))
                for (i in DIAL_TICKS.indices step 2) drawLine(Ink, Offset(DIAL_TICKS[i].first, DIAL_TICKS[i].second), Offset(DIAL_TICKS[i + 1].first, DIAL_TICKS[i + 1].second), 1.5f)
                for ((v, at) in DIAL_LABELS) text.draw(this, v, at.x, at.y, 10f, Ink, anchor = 0.5f, weight = 700)
                text.draw(this, "dB", 146f, 108f, 11f, Soft, anchor = 0.5f, weight = 700)
                val centre = Offset(146f, 128f)
                val a = dialAngle(db ?: 30.0)
                drawLine(Color(0xFF1A1A18), centre, Offset(centre.x + 91.6f * cos(a).toFloat(), centre.y + 91.6f * sin(a).toFloat()), 3f, StrokeCap.Round)
                drawCircle(Ink, 10f, centre)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                listening -> stringResource(R.string.relax_listening)
                db != null -> stringResource(R.string.relax_here, db.roundToInt())
                reading != null -> stringResource(R.string.relax_no_mic)
                else -> stringResource(R.string.relax_tap)
            }
            BoardLine(label, boardStyle(13f, 700, 0xFFE9DFC0, 17f, GEIST_MONO))
            val lamp = if (db != null && !listening) 0xFF7CFF6B else if (listening) 0xFFE9B84A else 0xFF4A4D4A
            Box(Modifier.size(10.dp).boardBox(lamp, corners(5f), if (db != null && !listening) shadow(0f, 0f, 6f, 0xFF7CFF6B) else emptyList(), clip = false))
        }
    }
}
