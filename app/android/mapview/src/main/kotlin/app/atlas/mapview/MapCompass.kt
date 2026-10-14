package app.atlas.mapview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The compass for the map's controls: a white dial whose needle points north on the map, foreshortened as the map
 * tilts, so it lies on the ground with the map. A tap turns the map north up (or does [onClick]).
 */
@Composable
fun MapCompass(
    state: MapState,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onClick: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val click = onClick ?: { scope.launch { state.animateTo(state.camera.copy(bearing = 0.0), 450) } }
    val needle = remember { Path() }
    Canvas(
        modifier
            .size(size)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = { click() })
            .semantics { contentDescription = "Compass, heading ${state.camera.bearing.roundToInt() % 360}°" },
    ) {
        val r = this.size.minDimension / 2
        val c = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(Color.White, r, c)
        drawCircle(Color(0x1F14161B), r - 0.5f * density, c, style = Stroke(1f * density))
        // Ticks every 45°, the north one stronger.
        val b = Math.toRadians(state.camera.bearing)
        val squash = cos(Math.toRadians(state.camera.pitch)).toFloat().coerceIn(0.35f, 1f)
        for (k in 0 until 8) {
            val a = -b + k * Math.PI / 4
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat() * squash
            val outer = r * 0.86f
            val inner = r * (if (k % 2 == 0) 0.72f else 0.78f)
            drawLine(
                Color(0x6614161B), Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer),
                strokeWidth = 1.2f * density,
            )
        }
        // The needle: north half red, south half grey, as a flat diamond on the dial.
        val nx = sin(-b).toFloat()
        val ny = -cos(-b).toFloat() * squash
        val px = -ny / squash * 0.22f
        val py = nx * squash * 0.22f
        val len = r * 0.66f
        needle.reset()
        needle.moveTo(c.x + nx * len, c.y + ny * len)
        needle.lineTo(c.x + px * r, c.y + py * r)
        needle.lineTo(c.x - px * r, c.y - py * r)
        needle.close()
        drawPath(needle, Color(0xFFE5482D))
        needle.reset()
        needle.moveTo(c.x - nx * len, c.y - ny * len)
        needle.lineTo(c.x + px * r, c.y + py * r)
        needle.lineTo(c.x - px * r, c.y - py * r)
        needle.close()
        drawPath(needle, Color(0xFFA9AFB9))
        drawCircle(Color.White, 2.2f * density, c)
        drawCircle(Color(0xFF14161B), 1.2f * density, c)
    }
}
