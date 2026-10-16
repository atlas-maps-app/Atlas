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
import app.atlas.ui.mine.MineTone
import app.atlas.ui.mine.mt
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
    val sheet = MineTone.of(0xFFE4ECF6, 0xFF1E2733)
    val grid = MineTone.of(0xFFC9D6E6, 0xFF2D3A4A)
    val ink = MineTone.of(0xFF14161B, 0xFFEDEEF1)
    val dot = Color(0xFFB8905C)
    val blue = MineTone.blue
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
            drawRoundRect(blue, tl, sz, CornerRadius(6f * density), style = Stroke(2f * density))
        }
        for ((dx, dy) in dots) drawCircle(dot.copy(alpha = 0.9f), 2.6f * density, Offset(dx * w, dy * h))
        if (route.size > 1) {
            val p = Path().apply { route.forEachIndexed { i, q -> if (i == 0) moveTo(x(q.lon), y(q.lat)) else lineTo(x(q.lon), y(q.lat)) } }
            drawPath(p, Color.White, style = Stroke(6f * density, cap = StrokeCap.Round))
            drawPath(p, MineTone.greenDeep, style = Stroke(3.5f * density, cap = StrokeCap.Round))
        }
        if (loop.size > 1) {
            val p = Path().apply { loop.forEachIndexed { i, q -> if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; if (!drawing) close() }
            drawPath(p, blue, style = Stroke(3f * density, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 7f * density))))
        }
        pin?.let {
            val c = Offset(x(it.lon), y(it.lat))
            drawCircle(Color.White, 10f * density, c)
            drawCircle(MineTone.red, 7f * density, c)
            if (pinLabel != null) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density; color = ink.toArgb(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
                drawContext.canvas.nativeCanvas.drawText(pinLabel, c.x + 14f * density, c.y - 6f * density, paint)
            }
        }
        // The scale bar: one grid step wide, bottom left.
        val barPx = (step / spanWkm).toFloat() * w
        val by = h - 14f * density
        drawLine(ink, Offset(12f * density, by), Offset(12f * density + barPx, by), 2f * density)
        drawLine(ink, Offset(12f * density, by - 4f * density), Offset(12f * density, by + 4f * density), 2f * density)
        drawLine(ink, Offset(12f * density + barPx, by - 4f * density), Offset(12f * density + barPx, by + 4f * density), 2f * density)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * density; color = ink.toArgb(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
        drawContext.canvas.nativeCanvas.drawText(scale, 12f * density + barPx + 6f * density, by + 3.5f * density, paint)
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
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MineTone.of(0xFFFFFFFF, 0xFF1B1D22)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (bytes == null) "…" else if (unknown && total == 0L) "?" else "${if (unknown) "≥ " else ""}$number $unit", style = mt(20f, 900, tabular = true), maxLines = 1, softWrap = false)
            Text(caption, Modifier.weight(1f), style = mt(12f, 700, line = 15f), color = MineTone.of(0xFF5A5F6C, 0xFFA0A5B1), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val have = rows.filter { (bytes?.get(it.first) ?: 0L) > 0L }
            if (have.isEmpty()) Box(Modifier.weight(1f).fillMaxHeight().background(MineTone.of(0xFFD5D8DE, 0xFF2C2F36)))
            for (r in have) Box(Modifier.weight(bytes!![r.first]!!.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(Color(r.second)))
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
            Row(Modifier.height(26.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(colour)))
                Text(name, style = mt(12f, 700), modifier = Modifier.weight(1f), maxLines = 1)
                Text(text, style = mt(12f, 400, tabular = true), color = MineTone.of(0xFF5A5F6C, 0xFFA0A5B1))
            }
        }
    }
}
