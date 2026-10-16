package app.atlas.ui.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.ui.kit.PlaceSummary
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.openLine
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.walkMinutes
import java.time.LocalDateTime
import java.util.Locale
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Argb
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout

/** The walk to a place as the boards write it: "3′", or the distance when it is far. */
internal fun walkOrDistance(distanceM: Double): String {
    val seconds = WalkTime.seconds(distanceM)
    return if (seconds > 45 * 60) shortDistance(distanceM) else walkMinutes(seconds)
}

/** Ages of a playground from `min_age` and `max_age`: "2–12", "from 3", "up to 6"; null when neither is mapped. */
internal fun ageRange(tags: Map<String, String>): String? {
    val lo = tags["min_age"]?.toIntOrNull()
    val hi = tags["max_age"]?.toIntOrNull()
    return when {
        lo != null && hi != null -> "$lo–$hi"
        lo != null -> "$lo+"
        hi != null -> "≤$hi"
        else -> null
    }
}

/** True when the tags say the place is fenced in. */
internal fun fenced(tags: Map<String, String>): Boolean = tags["barrier"] in setOf("fence", "wall", "hedge") || tags["fenced"] == "yes" || tags["fence_type"] != null

/** True when the toddlers' range of a playground is covered: it takes children of [age] (unknown limits count as open). */
internal fun takesAge(tags: Map<String, String>, age: Int): Boolean {
    val lo = tags["min_age"]?.toIntOrNull() ?: 0
    val hi = tags["max_age"]?.toIntOrNull() ?: 99
    return age in lo..hi
}

/** The nearest of [toilets] to [place] and how far, within [maxM] metres. */
internal fun nearestWithin(place: Place, others: List<Found>, maxM: Double): Found? =
    others.map { it to app.atlas.geo.Geo.distance(place.location, it.place.location) }.filter { it.second <= maxM }.minByOrNull { it.second }?.let { Found(it.first.place, it.second) }

/**
 * How a board draws the cards of its list: the card's ground, radius, least height and shadows, its ink and soft ink,
 * the walk's size and line box, and the gap between the small facts (null: one line joined by " · ").
 */
internal class CardLook(
    val bg: Argb, val radius: Float, val minHeight: Float, val shadows: List<CssShadow>, val ink: Argb, val soft: Argb,
    val walkSize: Float, val walkLine: Float, val factGap: Float? = null,
    /** The card of a place that is shut for now (or, for the quiet places, one that costs): its ground, shadows and walk colour. */
    val dimBg: Argb = bg, val dimShadows: List<CssShadow> = shadows, val dimWalk: Argb = ink,
)

/**
 * A line of a place's list as the boards draw it: 14 px in, the [tile] (told whether the card is [dim]), its name in Geist
 * 16 at 800 and its small facts with how it stands for opening 3 px under it, the walk at the right. A place shut for
 * now says so in amber when [caution] (the Swim board's "opens late June").
 */
@Composable
internal fun PlaceCard(
    found: Found, here: LatLon, facts: List<String>, now: LocalDateTime, look: CardLook, tile: @Composable (dim: Boolean) -> Unit,
    modifier: Modifier = Modifier, dim: Boolean = false, caution: Boolean = false, onClick: (() -> Unit)? = null,
) {
    val lang = remember { Locale.getDefault().language }
    val s = PlaceSummary.of(found.place, here, lang, now)
    val open = openLine(s, now)
    Row(
        modifier.fillMaxWidth().heightIn(min = look.minHeight.dp).boardBox(if (dim) look.dimBg else look.bg, corners(look.radius), if (dim) look.dimShadows else look.shadows)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        tile(dim)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BoardLine(s.name, boardStyle(16f, 800, look.ink, 20.5f))
            val bits = (facts + listOfNotNull(open?.first)).filter { it.isNotBlank() }
            val style = if (caution) boardStyle(12f, 700, 0xFF8A5A00, 15.5f, tnum = true) else boardStyle(12f, 400, look.soft, 15.5f, tnum = true)
            if (bits.isNotEmpty()) {
                if (look.factGap != null) GapLine(look.factGap) { for (b in bits) BoardLine(b, style, overflow = TextOverflow.Clip) }
                else BoardLine(bits.joinToString(" · "), style)
            }
        }
        BoardLine(walkOrDistance(found.distanceM), boardStyle(look.walkSize, 900, if (dim) look.dimWalk else look.ink, look.walkLine, tnum = true))
    }
}

/** Items in a row [gap] px apart, as many as fit whole; the rest are left out (a flex row that does not wrap, clipped). */
@Composable
internal fun GapLine(gap: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, c ->
        val g = (gap * density).roundToInt()
        val placeables = measurables.map { it.measure(Constraints(maxWidth = c.maxWidth)) }
        var x = 0
        val shown = ArrayList<Pair<androidx.compose.ui.layout.Placeable, Int>>()
        for (p in placeables) {
            val start = if (shown.isEmpty()) 0 else x + g
            if (start + p.width > c.maxWidth && shown.isNotEmpty()) break
            shown += p to start
            x = start + p.width
        }
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(minOf(x, c.maxWidth), h) { for ((p, at) in shown) p.place(at, 0) }
    }
}
