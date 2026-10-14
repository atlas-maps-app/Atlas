package app.atlas.mapview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.geo.UnitSystem
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A scale bar's choice: a round distance and how many dp it spans at the map's scale. */
data class Scale(val widthDp: Float, val label: String, val metres: Double)

/** Round distances for scale bars. */
object Scales {
    private const val FOOT = 0.3048
    private const val MILE = 1609.344
    private const val NAUTICAL_MILE = 1852.0

    /** The longest round distance (1, 2 or 5 of a unit's powers of ten) that fits in [maxWidthDp] at [metresPerDp]. */
    fun choose(metresPerDp: Double, maxWidthDp: Float, units: UnitSystem): Scale {
        val maxMetres = metresPerDp * maxWidthDp
        return when (units) {
            UnitSystem.METRIC -> if (maxMetres >= 1000.0) round(maxMetres, 1000.0, "km", metresPerDp) else round(maxMetres, 1.0, "m", metresPerDp)
            UnitSystem.IMPERIAL, UnitSystem.IMPERIAL_UK -> {
                val shortUnit = if (units == UnitSystem.IMPERIAL) FOOT else 0.9144
                val shortName = if (units == UnitSystem.IMPERIAL) "ft" else "yd"
                if (maxMetres >= MILE * 0.2) round(maxMetres, MILE, "mi", metresPerDp) else round(maxMetres, shortUnit, shortName, metresPerDp)
            }
            UnitSystem.NAUTICAL -> if (maxMetres >= NAUTICAL_MILE * 0.1) round(maxMetres, NAUTICAL_MILE, "nm", metresPerDp) else round(maxMetres, 1.0, "m", metresPerDp)
        }
    }

    private fun round(maxMetres: Double, unit: Double, name: String, metresPerDp: Double): Scale {
        val max = maxMetres / unit
        val p = 10.0.pow(floor(log10(max)))
        val m = max / p
        val nice = when {
            m >= 5 -> 5.0
            m >= 2 -> 2.0
            else -> 1.0
        } * p
        val metres = nice * unit
        val text = if (nice >= 1.0) "%.0f".format(Locale.ROOT, nice) else "%s".format(Locale.ROOT, trim(nice))
        return Scale((metres / metresPerDp).toFloat(), "$text $name", metres)
    }

    private fun trim(v: Double): String = "%.3f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')
}

/**
 * A scale bar for the map's corner: a round distance at the camera's target (in a pitched view the scale holds
 * there, nearer is larger and farther smaller), with its length in [units].
 */
@Composable
fun ScaleBar(
    state: MapState,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 96.dp,
    units: UnitSystem = UnitSystem.forLocale(Locale.getDefault()),
    colour: Color = Color(0xFF14161B),
    halo: Color = Color.White,
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colour, shadow = Shadow(halo, Offset.Zero, 6f))
    Canvas(
        modifier
            .width(maxWidth + 4.dp)
            .height(28.dp)
            .semantics { contentDescription = "Scale" },
    ) {
        val s = Scales.choose(CameraOps.metresPerDp(state.camera), maxWidth.value, units)
        val w = s.widthDp * density
        val x0 = 2f * density
        val y = size.height - 4f * density
        val tick = 5f * density
        for (pass in 0..1) {
            val c = if (pass == 0) halo else colour
            val sw = (if (pass == 0) 4f else 1.6f) * density
            drawLine(c, Offset(x0, y), Offset(x0 + w, y), sw, StrokeCap.Round)
            drawLine(c, Offset(x0, y), Offset(x0, y - tick), sw, StrokeCap.Round)
            drawLine(c, Offset(x0 + w, y), Offset(x0 + w, y - tick), sw, StrokeCap.Round)
        }
        val layout = measurer.measure(s.label, style)
        drawText(layout, topLeft = Offset(x0 + 1f * density, y - tick - layout.size.height - 1f * density))
    }
}
