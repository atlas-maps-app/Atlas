package app.atlas.ui.home

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.model.Place
import app.atlas.places.Brands
import app.atlas.places.OpeningHours
import app.atlas.search.PlaceRecord
import app.atlas.search.PlaceSource
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.walkMinutes
import app.atlas.ui.kit.markOf
import java.time.LocalDateTime

/**
 * Why a place stands out on the home's map; each kind has the Main board's own marker look. A kind of place (a museum,
 * a stop, a charger) wears its family's [colour], null here, as its pin does on every other screen (README decision 12):
 * since the families were regrouped the board's purple museum read as Fun and its blue stop as Water. The board's own
 * colour stays where the look says something else: what you saved, the landmark's gold star, a chain's tile.
 */
enum class HighlightKind(val colour: Long?, val priority: Int) {
    /** A place you saved: the board's dark disc with the orange check, with its name and walk on the nearest one. */
    SAVED(0xFF14161B, 1_000),

    /** The area's landmark: the board's gold star. */
    SIGHT(0xFFF2B705, 800),

    /** A museum open now: the board's pin, in Culture & sights' colour. */
    MUSEUM(null, 700),

    /** The nearest tram, train or metro stop: the board's small pin, in Getting around's colour. */
    TRANSIT(null, 600),

    /** A branch of a café chain open now: the board's brown tile with the brand, on its own sign where it is known. */
    BRANCH(0xFF3B2A20, 500),

    /** Charging points, with their sockets as mapped: the board's chip, in Charge's colour. */
    CHARGER(null, 400),
}

/** A real place the home shows on its map before any category is chosen, and what to say on its marker. */
data class Highlight(val kind: HighlightKind, val place: Place, val value: String? = null)

/**
 * The home's highlights: a few real places around you (or the view) from the offline place stores, one of each kind
 * the Main board shows: your saved places, the landmark, a museum and a café branch open now, the nearest stop and
 * charging points. Only what OpenStreetMap maps; nothing is shown that the data does not hold.
 */
object HomeHighlights {
    /** How far around the anchor to look, metres: about the ground the board's view shows near you. */
    const val RADIUS_M = 600.0

    /** How far the nearest stop may be. */
    private const val STOP_M = 400.0

    private const val CHARGERS = 2

    private val STOPS = listOf("railway" to "tram_stop", "railway" to "station", "railway" to "halt", "station" to "subway")

    fun pick(source: PlaceSource, anchor: LatLon, at: LocalDateTime, saved: List<Place> = emptyList()): List<Highlight> {
        if (source.size == 0 && saved.isEmpty()) return emptyList()
        val box = BBox.around(anchor, RADIUS_M)
        val out = ArrayList<Highlight>()
        val taken = HashSet<String>()
        fun add(kind: HighlightKind, p: Place, value: String? = null) {
            if (taken.add(p.key)) out += Highlight(kind, p, value)
        }
        fun nearest(list: List<PlaceRecord>) = list.sortedBy { it.distanceTo(anchor) }

        saved.filter { Geo.distance(it.location, anchor) <= RADIUS_M }.sortedBy { Geo.distance(it.location, anchor) }.forEachIndexed { i, p ->
            add(HighlightKind.SAVED, p, if (i == 0) walkMinutes(WalkTime.seconds(Geo.distance(anchor, p.location))) else null)
        }
        if (source.size == 0) return out

        // The landmark: an attraction known to Wikidata, one with a Wikipedia article first, then the nearest.
        nearest(source.byTag("tourism", "attraction", box).filter { it.tags["wikidata"] != null && it.name() != null })
            .sortedBy { if (it.tags["wikipedia"] != null) 0 else 1 }
            .firstOrNull()?.let { add(HighlightKind.SIGHT, it.place) }
        // A museum open now, the notable (on Wikidata) first.
        nearest(source.byTag("tourism", "museum", box).filter { it.name() != null && openNow(it, at) })
            .sortedBy { if (it.tags["wikidata"] != null) 0 else 1 }
            .firstOrNull()?.let { add(HighlightKind.MUSEUM, it.place) }
        // The nearest stop, one per name (a tram stop is mapped once for each direction).
        nearest(STOPS.flatMap { (k, v) -> source.byTag(k, v, box) }.filter { it.name() != null && it.distanceTo(anchor) <= STOP_M })
            .firstOrNull()?.let { add(HighlightKind.TRANSIT, it.place) }
        // A café chain's branch open now.
        nearest(source.byTag("amenity", "cafe", box).filter { it.tags["brand"] != null && openNow(it, at) })
            .firstOrNull()?.let { add(HighlightKind.BRANCH, it.place) }
        // Charging points: the nearest few, with their sockets as mapped.
        nearest(source.byTag("amenity", "charging_station", box)).take(CHARGERS).forEach {
            add(HighlightKind.CHARGER, it.place, it.tags["capacity"]?.toIntOrNull()?.takeIf { n -> n > 0 }?.toString())
        }
        return out
    }

    /** Open at [at] by its hours; a place without hours (or with hours that cannot be read) does not count as open. */
    private fun openNow(r: PlaceRecord, at: LocalDateTime): Boolean {
        val hours = r.tags["opening_hours"] ?: return false
        val s = OpeningHours.statusOf(hours, at)
        return !s.unknown && s.isOpen
    }

    /** The markers for [highlights], in the board's looks, a kind of place in its family's colour ([HighlightKind]). */
    fun markers(highlights: List<Highlight>): List<Marker> = highlights.map { h ->
        val p = h.place
        val id = "hl:${h.kind.name.lowercase()}:${p.key}"
        val (symbol, family) = markOf(p)
        val colour = h.kind.colour ?: (family.toLong() and 0xFFFFFFFFL)
        when (h.kind) {
            HighlightKind.SAVED -> Marker(
                id, p.location, symbol, colour, MarkerStyle.PIN,
                label = if (h.value != null) p.tags["name"] else null, value = h.value, badge = "ui-check",
                priority = h.kind.priority, place = p,
            )
            HighlightKind.SIGHT -> Marker(id, p.location, "maki-star", colour, MarkerStyle.PIN, priority = h.kind.priority, place = p)
            HighlightKind.BRANCH -> Marker(
                id, p.location, symbol, colour, MarkerStyle.BRAND,
                logo = Brands.forTags(p.tags)?.id, priority = h.kind.priority, place = p,
            )
            HighlightKind.CHARGER -> Marker(id, p.location, "maki-charging-station", colour, MarkerStyle.CHIP, value = h.value, priority = h.kind.priority, place = p)
            else -> Marker(id, p.location, symbol, colour, MarkerStyle.PIN, priority = h.kind.priority, place = p)
        }
    }
}
