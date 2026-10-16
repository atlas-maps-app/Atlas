package app.atlas.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * A board's steel-and-enamel compass (the Toilet, Water and Food boards), cut into the pieces that move: the face and
 * rim, the dial (ticks and letters, which turns against the way the phone faces), the arrow (which turns to the place,
 * relative to the phone) and the pivot. Each piece keeps the board's own SVG, with its gradients, so nothing is redrawn.
 */
internal class DialArt(markup: String) {
    private val open: String
    private val defs: String
    val base: SvgArt
    val dial: SvgArt
    val needle: SvgArt
    val tail: SvgArt

    init {
        val head = markup.substring(0, markup.indexOf('>') + 1)
        val d0 = markup.indexOf("<defs>")
        val d1 = markup.indexOf("</defs>") + "</defs>".length
        open = head
        defs = markup.substring(d0, d1)
        val g1 = markup.indexOf("<g transform=\"rotate(", d1)
        val g1end = markup.indexOf("</g>", g1)
        val g2 = markup.indexOf("<g transform=\"rotate(", g1end)
        val g2end = markup.indexOf("</g>", g2)
        fun inner(from: Int, to: Int) = markup.substring(markup.indexOf('>', from) + 1, to)
        fun part(body: String) = SvgArt.of("$open$defs$body</svg>")
        base = part(markup.substring(d1, g1))
        dial = part(inner(g1, g1end))
        needle = part(inner(g2, g2end))
        tail = part(markup.substring(g2end + 4, markup.lastIndexOf("</svg>")))
    }

    /** The width the board gives it, in px (dp in the app). */
    val width: Float get() = base.width

    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, DialArt>()
        fun of(markup: String): DialArt = cache.getOrPut(markup) { DialArt(markup) }
    }
}

/** Turns [from] the short way round to [to] (degrees): the angle to animate to so a needle never spins through 359 → 0. */
internal fun nearestAngle(from: Float, to: Float): Float {
    var goal = to
    while (goal - from > 180f) goal -= 360f
    while (goal - from < -180f) goal += 360f
    return goal
}

/**
 * The compass drawn at [size]: the dial turned by `-heading` (north swings round as the phone does) and the arrow by
 * `bearing - heading` (towards the place from where the phone faces); [heading] null leaves north up.
 */
@Composable
internal fun NeedDial(markup: String, size: Dp, heading: Float?, bearing: Float?, description: String, modifier: Modifier = Modifier) {
    val art = remember(markup) { DialArt.of(markup) }
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val dialAngle = remember { Animatable(-(heading ?: 0f)) }
    val needleAngle = remember { Animatable((bearing ?: 0f) - (heading ?: 0f)) }
    LaunchedEffect(heading) { dialAngle.animateTo(nearestAngle(dialAngle.value, -(heading ?: 0f)), tween(220)) }
    LaunchedEffect(heading, bearing) { needleAngle.animateTo(nearestAngle(needleAngle.value, (bearing ?: 0f) - (heading ?: 0f)), tween(260)) }
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        art.base.draw(this, Color.Black, measurer)
        withTransform({ rotate(dialAngle.value, c) }) { art.dial.draw(this, Color.Black, measurer) }
        if (bearing != null) withTransform({ rotate(needleAngle.value, c) }) { art.needle.draw(this, Color.Black, measurer) }
        art.tail.draw(this, Color.Black, measurer)
    }
}
