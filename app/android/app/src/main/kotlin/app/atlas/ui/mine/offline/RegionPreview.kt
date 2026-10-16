package app.atlas.ui.mine.offline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.services.RegionLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.atlas.ui.mine.BText
import app.atlas.ui.mine.INK
import app.atlas.ui.mine.SOFT
import app.atlas.ui.mine.WHITE
import app.atlas.ui.mine.bbox
import app.atlas.ui.mine.bt
import app.atlas.ui.mine.cr
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The map panel of the Welcome and picker boards: a pale blue sheet with a graticule at round kilometres, the places the
 * phone knows as dots, the area to take (blue, the board's 28 % fill and a stroke), a route as a line, a pin with its name,
 * and a scale bar. With [onLoop] it takes a finger-drawn loop (the lasso) and reports it as positions on the earth.
 */
@Composable
internal fun PreviewMap(
    fitting: BBox,
    modifier: Modifier = Modifier,
    aspect: Float = 1.29f,
    dots: List<Pair<Float, Float>> = emptyList(),
    frames: List<BBox> = emptyList(),
    route: List<LatLon> = emptyList(),
    pin: LatLon? = null,
    pinLabel: String? = null,
    onLoop: ((List<LatLon>) -> Unit)? = null,
    description: String = "",
) {
    val view = remember(fitting, aspect) { PickerModel.fitView(fitting, aspect) }
    // The gesture below lives as long as the view does; it must call the latest callback, not the one of the first composition.
    val loopDone by androidx.compose.runtime.rememberUpdatedState(onLoop)
    var loop by remember { mutableStateOf(emptyList<Offset>()) }
    var drawing by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // the Welcome board's map: its sheet, its grid lines, the chosen regions' blue at 28 %, the red pin ringed in white
    val sheet = Color(0xFFE4ECF6)
    val grid = Color(0xFFC9D6E6)
    val ink = Color(0xFF14161B)
    val dot = Color(0xFFB8905C)
    val blue = Color(0xFF2E7BFF)
    val measurer = rememberTextMeasurer()
    val pinStyle = bt(11f, 800)
    val scaleStyle = bt(10f, 800)
    val (spanWkm, _) = PickerModel.sides(view)
    val step = PickerModel.gridStepKm(spanWkm)
    val scale = if (step >= 1) "${step.roundToInt()} km" else String.format(Locale.ROOT, "%d m", (step * 1000).roundToInt())
    Canvas(
        modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(22.dp))
            .background(sheet)
            .semantics { contentDescription = description }
            .onSizeChanged { box = it }
            .then(
                if (onLoop == null) Modifier else Modifier.pointerInput(view) {
                    detectDragGestures(
                        onDragStart = { loop = listOf(it); drawing = true },
                        onDrag = { change, _ -> loop = loop + change.position },
                        onDragEnd = {
                            drawing = false
                            val pts = loop
                            loop = emptyList()
                            if (box.width > 0) loopDone?.invoke(pts.map { PickerModel.toLatLon(it.x, it.y, box.width.toFloat(), box.height.toFloat(), view) })
                        },
                        onDragCancel = { drawing = false; loop = emptyList() },
                    )
                },
            ),
    ) {
        val w = size.width
        val h = size.height
        fun x(lon: Double) = ((lon - view.west) / (view.east - view.west)).toFloat() * w
        fun y(lat: Double) = ((view.north - lat) / (view.north - view.south)).toFloat() * h
        // The graticule: lines at round kilometres from the box's south-west corner, as a map sheet's grid.
        val kmLat = 1.0 / 111.32
        val midLat = (view.south + view.north) / 2
        val kmLon = 1.0 / (111.32 * cos(Math.toRadians(midLat)))
        var lat = Math.ceil(view.south / (kmLat * step)) * kmLat * step
        while (lat < view.north) { drawLine(grid, Offset(0f, y(lat)), Offset(w, y(lat)), 1.5f * density); lat += kmLat * step }
        var lon = Math.ceil(view.west / (kmLon * step)) * kmLon * step
        while (lon < view.east) { drawLine(grid, Offset(x(lon), 0f), Offset(x(lon), h), 1.5f * density); lon += kmLon * step }
        for (f in frames) {
            val tl = Offset(x(f.west), y(f.north))
            val sz = Size(x(f.east) - tl.x, y(f.south) - tl.y)
            drawRoundRect(blue.copy(alpha = 0.28f), tl, sz, CornerRadius(6f * density))
        }
        for ((dx, dy) in dots) drawCircle(dot.copy(alpha = 0.9f), 2.6f * density, Offset(dx * w, dy * h))
        if (route.size > 1) {
            val p = Path().apply { route.forEachIndexed { i, q -> if (i == 0) moveTo(x(q.lon), y(q.lat)) else lineTo(x(q.lon), y(q.lat)) } }
            drawPath(p, Color.White, style = Stroke(6f * density, cap = StrokeCap.Round))
            drawPath(p, Color(0xFF178A4E), style = Stroke(3.5f * density, cap = StrokeCap.Round))
        }
        if (loop.size > 1) {
            val p = Path().apply { loop.forEachIndexed { i, q -> if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; if (!drawing) close() }
            drawPath(p, blue, style = Stroke(3f * density, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density))))
        }
        pin?.let {
            val c = Offset(x(it.lon), y(it.lat))
            drawCircle(Color(0xFFC8322A), 7f * density, c)
            drawCircle(Color.White, 7f * density, c, style = Stroke(3f * density))
            if (pinLabel != null) {
                // the board's label: Geist 11, 800, its baseline 4 px over the pin and 12 px to its right
                val t = measurer.measure(pinLabel, pinStyle)
                drawText(t, ink, Offset(c.x + 12f * density, c.y - 4f * density - t.firstBaseline))
            }
        }
        // The scale bar: one grid step wide, bottom left.
        val barPx = (step / spanWkm).toFloat() * w
        val by = h - 14f * density
        drawLine(ink, Offset(12f * density, by), Offset(12f * density + barPx, by), 2f * density)
        drawLine(ink, Offset(12f * density, by - 4f * density), Offset(12f * density, by + 4f * density), 2f * density)
        drawLine(ink, Offset(12f * density + barPx, by - 4f * density), Offset(12f * density + barPx, by + 4f * density), 2f * density)
        val t = measurer.measure(scale, scaleStyle)
        drawText(t, ink, Offset(12f * density + barPx + 6f * density, by + 3.5f * density - t.firstBaseline))
    }
}

/**
 * The size of what would be downloaded as the Welcome board draws it: the total in 20 sp, a line of words, the five-colour
 * bar and a row per layer with its bytes. [bytes] is null while the engine works it out; a layer with no known size says so.
 */
@Composable
internal fun EstimateCard(bytes: Map<RegionLayer, Long?>?, caption: String, modifier: Modifier = Modifier) {
    val rows = listOf(
        Triple(RegionLayer.MAP, 0xFF2E7BFFL, R.string.mine_est_map),
        Triple(RegionLayer.ROUTING, 0xFF1E9E5AL, R.string.mine_est_routes),
        Triple(RegionLayer.PLACES, 0xFFE8922AL, R.string.mine_est_places),
        Triple(RegionLayer.VOICE, 0xFF7B4DFFL, R.string.mine_est_voices),
    ).filter { bytes == null || it.first in bytes.keys }
    val total = bytes?.values?.sumOf { it ?: 0L } ?: 0L
    val unknown = bytes != null && bytes.values.any { it == null }
    val (number, unit) = OfflineModel.total(total)
    val none = stringResource(R.string.mine_est_unknown)
    val working = stringResource(R.string.mine_est_working)
    val onPhone = stringResource(R.string.mine_est_on_phone)
    // the board's card: white, radius 20, 12 px above and below, 14 at the sides, 6 px between its lines
    Column(modifier.fillMaxWidth().bbox(WHITE, cr(20f)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BText(if (bytes == null) "…" else if (unknown && total == 0L) "?" else "${if (unknown) "≥ " else ""}$number $unit", bt(20f, 900, 26f, tnum = true), INK, Modifier.alignByBaseline())
            BText(caption, bt(12f, 700, 15.5f, align = androidx.compose.ui.text.style.TextAlign.End), SOFT, Modifier.weight(1f).alignByBaseline(), lines = 2)
        }
        // the five-colour bar: 12 px, radius 6, 2 px between the layers
        Row(Modifier.fillMaxWidth().height(12.dp).bbox(r = cr(6f)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val have = rows.filter { (bytes?.get(it.first) ?: 0L) > 0L }
            if (have.isEmpty()) Box(Modifier.weight(1f).fillMaxHeight().bbox(0xFFD5D8DE))
            for (r in have) Box(Modifier.weight(bytes!![r.first]!!.toFloat().coerceAtLeast(1f)).fillMaxHeight().bbox(r.second))
        }
        for ((layer, colour, label) in rows) {
            val size = bytes?.get(layer)
            val name = stringResource(label)
            val text = when {
                bytes == null -> working
                size == null -> none
                size == 0L && layer == RegionLayer.VOICE -> onPhone
                size == 0L -> "0"
                else -> RegionEngine.size(size)
            }
            // a layer: 26 px, its 10 px dot, the name and the size (12, 700 on 15.5), 8 px apart
            Row(Modifier.height(26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).bbox(colour, cr(5f)))
                BText(name, bt(12f, 700, 15.5f), INK, Modifier.weight(1f))
                BText(text, bt(12f, 700, 15.5f, tnum = true), SOFT)
            }
        }
    }
}
