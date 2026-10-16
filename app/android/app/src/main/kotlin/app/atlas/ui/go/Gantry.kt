package app.atlas.ui.go

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.ui.kit.SansFamily
import app.atlas.ui.kit.softShadow
import kotlin.math.cos
import kotlin.math.sin

/** The Go board's colours (canvas/project/Go.dc.html): its night sky, the sign green, the service blue. */
object SignColours {
    val green = Color(0xFF0E6B3F)
    val rule = Color(0xFFF4F7F2)
    val blueTop = Color(0xFF2461B0)
    val blueBottom = Color(0xFF1A4C8F)
    val blueEdge = Color(0xFF1D4F91)
    val skyTop = Color(0xFF16202D)
    val skyMid = Color(0xFF22303F)
    val skyLow = Color(0xFF2B3949)
    val sub = Color(0xFFB9C3D0)
    val paper = Color(0xFFF3F1EC)
    val ink = Color(0xFF14161B)
    val soft = Color(0xFF4A505E)
    val blue = Color(0xFF2E5BFF)
}

/** The Go board's type: Overpass in the boards, the app's sans here, with tabular figures. */
internal val SignType = TextStyle(fontFamily = SansFamily)

/** The motorway gantry's truss across the width, as the Go board draws it: chords, the zigzag, hangers and bolts. */
@Composable
fun GantryTruss(modifier: Modifier = Modifier) {
    ArtBox(BoardArt.gantry, modifier.fillMaxWidth().height(44.dp), fill = true)
}

/**
 * A green guide sign of the Go board with its tab plate: [plate] on the tab ("HOME", "LEAVE 7:30"), [name] in large
 * letters on two lines, the sign's arrow turned to [arrowDeg] (0 up), and [footer] below (the mode, time, distance).
 */
@Composable
fun GuideSignBoard(
    plate: String,
    name: String,
    arrowDeg: Float?,
    modifier: Modifier = Modifier,
    description: String = "$plate, $name",
    footer: @Composable () -> Unit,
) {
    Box(modifier.height(136.dp).semantics(mergeDescendants = true) { contentDescription = description }) {
        // The body: green, a white rule 4 dp in from the edge, the sheen across its left third, a deep shadow.
        Box(
            Modifier
                .padding(top = 20.dp)
                .fillMaxSize()
                .softShadow(RoundedCornerShape(10.dp), Color(0x66000000), 14.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SignColours.green)
                .drawBehind {
                    // The CSS sheen: a 118° gradient, white at 13 % for its first 34 %, then nothing, with a hard edge.
                    val end = cssGradientEnd(118f, size.width, size.height)
                    val start = Offset(size.width - end.x, size.height - end.y)
                    val sheen = Color.White.copy(alpha = 0.13f)
                    drawRect(Brush.linearGradient(0f to sheen, 0.34f to sheen, 0.34f to Color.Transparent, 1f to Color.Transparent, start = start, end = end))
                    val i = 4.dp.toPx() + 1.dp.toPx()
                    drawRoundRect(SignColours.rule, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FitText(name, SignType.copy(fontSize = 21.sp, fontWeight = FontWeight.ExtraBold), Modifier.weight(1f), color = Color.White, minSize = 13.sp)
                    if (arrowDeg != null) ArtIcon(BoardArt.signArrow, Modifier.rotate(arrowDeg), size = 34.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { footer() }
            }
        }
        // The tab plate, sitting on the sign's top edge with its own white rule on three sides.
        Box(
            Modifier
                .offset(x = 14.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(SignColours.green)
                .drawBehind {
                    val w = 2.dp.toPx()
                    drawRect(SignColours.rule, Offset(w, w), Size(size.width - 2 * w, w))
                    drawRect(SignColours.rule, Offset(w, w), Size(w, size.height - w))
                    drawRect(SignColours.rule, Offset(size.width - 2 * w, w), Size(w, size.height - w))
                }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(plate, style = SignType.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.9.sp), color = Color.White, maxLines = 1)
        }
    }
}

/**
 * A blue road-service sign of "Keep it moving": the board's blue square with its white rule 3 dp in, the service's
 * [art] in white (or the "P" of parking when [art] is null), and its [label] and [value] under it.
 */
@Composable
internal fun ServiceSign(art: Art?, label: String, value: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.then(if (onClick != null) Modifier.button("$label${value?.let { ", $it" } ?: ""}", onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .softShadow(RoundedCornerShape(8.dp), Color(0x4D1D4F91), 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .drawBehind {
                    drawRect(Brush.linearGradient(listOf(SignColours.blueTop, SignColours.blueBottom), start = Offset.Zero, end = cssGradientEnd(160f, size.width, size.height)))
                }
                .border(3.dp, SignColours.blueEdge, RoundedCornerShape(8.dp))
                .padding(3.dp)
                .border(2.dp, SignColours.rule, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) ArtIcon(art, size = 30.dp, tint = Color.White)
            else Text("P", style = SignType.copy(fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black), color = Color.White, modifier = Modifier.padding(top = 3.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = SignType.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold), color = SignColours.ink, maxLines = 1)
            Text(value ?: "–", style = SignType.copy(fontSize = 11.sp, lineHeight = 13.sp), color = SignColours.soft, maxLines = 1)
        }
    }
}

/** A mode's round button of "How are you going?": the board's 48 dp disc, blue with white when chosen. */
@Composable
fun ModeButton(symbol: String, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.button(label, onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .softShadow(CircleShape, if (selected) Color(0x662E5BFF) else Color(0x1A14161B), if (selected) 10.dp else 6.dp)
                .clip(CircleShape)
                .background(if (selected) SignColours.blue else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            GoSymbol(symbol, tint = if (selected) Color.White else SignColours.ink, size = 23.dp)
        }
        Text(label, style = SignType.copy(fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal), color = SignColours.ink, maxLines = 1)
    }
}

/** The Go tab's dark top, as on the board: night navy into slate. */
internal val GoHeaderColours = Brush.verticalGradient(0f to SignColours.skyTop, 0.55f to SignColours.skyMid, 1f to SignColours.skyLow)

/** Where a CSS `linear-gradient([deg]deg, …)` ends in a box of [w] × [h] (it starts opposite), as CSS lays it out. */
internal fun cssGradientEnd(deg: Float, w: Float, h: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    val sx = sin(a)
    val cy = cos(a)
    // CSS: the gradient line's half length makes the corners reach 0 and 1.
    val half = (kotlin.math.abs(w * sx) + kotlin.math.abs(h * cy)) / 2
    return Offset((w / 2 + sx * half).toFloat(), (h / 2 - cy * half).toFloat())
}
