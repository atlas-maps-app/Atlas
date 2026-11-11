package app.atlas.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.shortDistance
import java.time.LocalDateTime

/**
 * Search (decision 12): the one search over the map, its field holding [Dest.Search.query] and, with
 * [Dest.Search.category], inside that kind. The board's parts live on in it: the live results as you type, the chips
 * that narrow them, the recents.
 */
@Composable
fun SearchScreen(args: Dest.Search, nav: Navigator) = SearchOverMap(nav, query = args.query, kind = args.category)

/** A place in a result list: tile, name, the status words in colour then its kind and distance, and walk minutes. */
@Composable
internal fun PlaceResultRow(
    line: PlaceLine,
    now: LocalDateTime,
    lang: String,
    subtitle: String? = null,
    parts: Int = 1,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val s = line.summary
    val stepFree = if (line.stepFree) stringResource(R.string.search_step_free) else null
    val distance = s.distanceM?.let { shortDistance(it) }
    val more = if (parts > 1) pluralStringResource(R.plurals.search_parts, parts, parts) else null
    ResultRow(
        title = s.name,
        line = statusLine(line, now, stepFree, subtitle ?: s.kind, more, distance.takeIf { line.trailing != distance }),
        symbol = s.symbol,
        tile = Color(s.colour),
        trailing = line.trailing,
        trailingColour = if (line.trailing == distance) Color(ROW_SOFT) else toneColour(line.tone),
        badge = badge,
        onClick = onClick,
    )
}
