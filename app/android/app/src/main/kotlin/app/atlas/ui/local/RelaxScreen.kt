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
import app.atlas.ui.kit.InterFamily
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

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE4E6DD), Color(0xFFEDEAE2))))) {
        BoardPage(background = Color.Transparent) {
            BoardTitleBar(stringResource(R.string.relax_title), onBack = { nav.back() }, colour = Ink)
            Spacer(Modifier.height(14.dp))
            SoundMeterCard(reading, listening, onTap = ::onTapMeter)
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val cells: List<Way?> = WAYS + listOf(null)
                for (row in cells.chunked(4)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (w in row) if (w != null) WayCell(w, w.id == kind, Modifier.weight(1f)) { kind = w.id } else FreeCell(freeOnly, Modifier.weight(1f)) { freeOnly = !freeOnly }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            when {
                found == null -> Text(stringResource(R.string.kit_loading), color = Soft, fontSize = 14.sp)
                items.isEmpty() -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color.White)) {
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
                        PlaceCard(f, here, facts, local, background = Color.White, ink = Ink, soft = Soft, symbol = chosen.symbol, tile = Color(chosen.tint.toInt()), height = 82, onClick = { services.remember(f.place); nav.go(Dest.Place.of(f.place)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun WayCell(w: Way, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(w.label)
    val desc = stringResource(w.desc)
    Column(
        modifier.height(78.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Ink else Color.White).clickable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .semantics { contentDescription = desc; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Glyph(w.symbol, if (on) Cream else Color(w.tint.toInt()), 26.dp)
        Text(label, color = if (on) Cream else Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun FreeCell(on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val freeLabel = stringResource(R.string.relax_free_only)
    Column(
        modifier.height(78.dp).clip(RoundedCornerShape(20.dp)).background(if (on) Color(0xFFE1F2EA) else Color.White).clickable(role = androidx.compose.ui.semantics.Role.Switch, onClick = onClick)
            .semantics { contentDescription = freeLabel; selected = on },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.relax_free), color = if (on) Color(0xFF12663A) else Soft, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Text(stringResource(R.string.relax_only), color = if (on) Color(0xFF12663A) else Soft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The sound level meter of the board: a dark case, a cream dial with green, amber and red arcs, and a needle. */
@Composable
private fun SoundMeterCard(reading: Reading?, listening: Boolean, onTap: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val text = remember(measurer) { BoardText(measurer, FontFamily.Monospace) }
    val db = reading?.db
    val desc = if (db != null) stringResource(R.string.relax_meter_desc, db.roundToInt()) else stringResource(R.string.relax_meter_tap)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp).cssShadow(shape, 16.dp, 28.dp, Color(0x4D000000)).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3D3A), Color(0xFF25272A)))).border(2.dp, Color(0xFF4A4D4A), shape).padding(14.dp)
            .clickable(onClick = onTap).semantics { contentDescription = desc },
    ) {
        Canvas(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(listOf(Color(0xFFF6EFD8), Color(0xFFE9DFC0))))) {
            val s = size.width / 292f
            scale(s, s, Offset.Zero) {
                val arc = androidx.compose.ui.geometry.Rect(Offset(34f, 16f), Size(224f, 224f))
                drawArc(Ink, 180f, 180f, false, arc.topLeft, arc.size, style = Stroke(1.5f))
                drawArc(Color(0xFF5A9E6A), 180f, 55.5f, false, arc.topLeft, arc.size, style = Stroke(8f))
                drawArc(Color(0xFFE9B84A), 235.5f, 66.7f, false, arc.topLeft, arc.size, style = Stroke(8f))
                drawArc(Color(0xFFC8322A), 302.2f, 57.8f, false, arc.topLeft, arc.size, style = Stroke(8f))
                val centre = Offset(146f, 128f)
                for (v in listOf(30, 40, 50, 60, 70, 80, 90)) {
                    val a = Math.toRadians(160.0 - (v - 30) * 140.0 / 60.0)
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    drawLine(Ink, Offset(centre.x + 100f * c, centre.y - 100f * sn), Offset(centre.x + 108f * c, centre.y - 108f * sn), 1.5f)
                    text.draw(this, v.toString(), centre.x + 88f * c, centre.y - 88f * sn + 3.5f, 10f, Ink, bold = true, anchor = 0.5f)
                }
                text.draw(this, "dB", 146f, 108f, 11f, Soft, bold = true, anchor = 0.5f)
                val level = (db ?: 30.0).coerceIn(30.0, 90.0)
                val a = Math.toRadians(160.0 - (level - 30) * 140.0 / 60.0)
                drawLine(Color(0xFF1A1A18), centre, Offset(centre.x + 91f * cos(a).toFloat(), centre.y - 91f * sin(a).toFloat()), 3f, StrokeCap.Round)
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
            Text(label, color = Color(0xFFE9DFC0), fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (db != null && !listening) Color(0xFF7CFF6B) else if (listening) Color(0xFFE9B84A) else Color(0xFF4A4D4A)))
        }
        Text(stringResource(R.string.relax_meter_note), Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp), color = Color(0xFFB9B29A), fontSize = 11.sp, lineHeight = 14.sp)
    }
}
