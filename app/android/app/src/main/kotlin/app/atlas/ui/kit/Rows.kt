package app.atlas.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A rounded square of [colour] with a white symbol: a place's category, a setting's kind. The Design board's tile: 42 px,
 * 12 round, its symbol 22 px.
 */
@Composable
fun SymbolTile(symbol: String, colour: Color, modifier: Modifier = Modifier, size: Dp = 42.dp, contentDescription: String? = null) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * (12f / 42f))).background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint = contentColourFor(colour), size = size * (22f / 42f), contentDescription = contentDescription)
    }
}

/**
 * The place row of the Design board's components: 64 px, 18 round, white in a 1 px #E6E1D6 ring, 12 px in; the 42 px
 * symbol tile, the name in Geist 800 at 15 over a "·"-separated line in 12 (#5A5F6C) 2 px under it, 12 px apart, and
 * a value at the right in 900 at 15 (walk minutes "4′", a distance). [metaColour] tints the line (green while open).
 */
@Composable
fun PlaceRow(
    name: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    colour: Color = argb(0xFF5A5F6C),
    meta: String? = null,
    metaColour: Color = argb(0xFF5A5F6C),
    trailing: String? = null,
    trailingColour: Color = argb(BOARD_INK),
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .cssEl(0xFFFFFFFF, r = CssCorners(18f, 18f, 18f, 18f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, BOARD_RING)), clip = true)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) SymbolTile(symbol, colour)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TxLine(name, txStyle(15f, 800, 19.5f), BOARD_INK)
            if (meta != null) Text(meta, style = txStyle(12f, 400, 15.5f), color = metaColour, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = txStyle(15f, 900, 19.5f, tnum = true), color = trailingColour, textAlign = TextAlign.End, maxLines = 1)
    }
}

/** What a time or distance that cannot be known (NaN, negative, endless) shows instead of a number. */
const val NO_VALUE = "–"

/** Minutes on foot as the boards write them: "4′". Under a minute is "1′"; an hour or more is "1 h 05". */
fun walkMinutes(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0) return NO_VALUE
    val m = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    return if (m < 60) "$m′" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
}

/**
 * Metres as a short distance: "350 m", "1.2 km", "12 km". Rounded first, then written in the unit the rounded value
 * belongs to, so 996 m is "1.0 km" (not "1000 m") and 9 960 m is "10 km" (not "10.0 km").
 */
fun shortDistance(metres: Double): String {
    if (!metres.isFinite() || metres < 0) return NO_VALUE
    val tens = ((metres / 10).roundToInt() * 10).coerceAtLeast(10)
    if (tens < 1000) return "$tens m"
    val tenths = (metres / 100).roundToInt()
    if (tenths < 100) return "${tenths / 10}.${tenths % 10} km"
    return "${(metres / 1000).roundToInt()} km"
}

/**
 * A small letter-spaced caps heading over a section ("KEPT HERE", "RECENT"), the Design board's sheet heading: Geist 800
 * at 11, 0.12 em apart, #676D7C.
 */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, colour: Color = argb(0xFF676D7C)) {
    Text(text.uppercase(), modifier.semantics { heading() }, style = txStyle(11f, 800, 14f, spacing = 1.32f), color = colour)
}

/** The quiet grey line at the end of a screen that names its sources and what is guessed. */
@Composable
fun HonestyFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        style = Atlas.type.caption,
        color = Atlas.colors.inkFaint,
        textAlign = TextAlign.Center,
    )
}

/** A status dot: green open, grey closed, amber soon. */
@Composable
fun StatusDot(colour: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(colour))
}

/** A hairline between rows. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(Atlas.colors.line))
}
