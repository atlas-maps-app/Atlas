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

/** A line of a place's list: its tile symbol, name, small facts, how it stands for opening, and the walk. */
@Composable
internal fun PlaceCard(
    found: Found, here: LatLon, facts: List<String>, now: LocalDateTime, modifier: Modifier = Modifier,
    background: Color = Color(0xFFFBFAF4), ink: Color = Color(0xFF1C2A1E), soft: Color = Color(0xFF4E5E50), symbol: String? = null, tile: Color? = null,
    height: Int = 86, onClick: (() -> Unit)? = null,
) {
    val lang = remember { Locale.getDefault().language }
    val s = PlaceSummary.of(found.place, here, lang, now)
    val open = openLine(s, now)
    Row(
        modifier.fillMaxWidth().heightIn(min = height.dp).clip(RoundedCornerShape(24.dp)).background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = tile ?: Color(s.colour)
        Box(Modifier.size(54.dp).clip(CircleShape).background(c.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Glyph(symbol ?: s.symbol, c, 26.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.name, color = ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = (facts + listOfNotNull(open?.first)).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotEmpty()) Text(line, color = open?.second?.takeIf { facts.isEmpty() } ?: soft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(walkOrDistance(found.distanceM), color = ink, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}
