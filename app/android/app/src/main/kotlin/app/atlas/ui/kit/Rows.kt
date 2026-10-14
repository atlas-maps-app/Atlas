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

/** A rounded square of [colour] with a white symbol: a place's category, a setting's kind. */
@Composable
fun SymbolTile(symbol: String, colour: Color, modifier: Modifier = Modifier, size: Dp = 44.dp, contentDescription: String? = null) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(colour),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(symbol, tint = contentColourFor(colour), size = size * 0.52f, contentDescription = contentDescription)
    }
}

/**
 * The row of every list on the boards: a coloured symbol tile, a bold name over a grey "·"-separated line, and a big
 * value on the right (walk minutes "5′", a distance). [metaColour] tints the line (green while open, red when closing).
 */
@Composable
fun PlaceRow(
    name: String,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    colour: Color = Atlas.colors.inkSoft,
    meta: String? = null,
    metaColour: Color = Atlas.colors.inkSoft,
    trailing: String? = null,
    trailingColour: Color = Atlas.colors.ink,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) SymbolTile(symbol, colour)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = Atlas.type.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(meta, style = Atlas.type.caption, color = metaColour, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, style = Atlas.type.numeralSmall, color = trailingColour, textAlign = TextAlign.End)
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

/** A small letter-spaced caps heading over a section: "KEPT HERE", "RECENT". */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, colour: Color = Atlas.colors.inkSoft) {
    Text(text.uppercase(), modifier.semantics { heading() }, style = Atlas.type.kicker, color = colour)
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
