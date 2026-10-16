package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.OpeningHours
import app.atlas.places.TagFilter
import app.atlas.providers.OverpassElement
import app.atlas.providers.OverpassQl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** What a park's plan marks: the things you look for in a park. */
enum class ParkKind(val filter: String, val symbol: String, val colour: Long) {
    PLAYGROUND("leisure=playground", "place.playground.4", 0xFFE8622C),
    DOGS("leisure=dog_park", "fact.dog", 0xFF12A9A6),
    CAFE("amenity=cafe or amenity=kiosk or amenity=restaurant or amenity=ice_cream", "place.cafe", 0xFF8C5A2B),
    TOILETS("amenity=toilets", "WC", 0xFF4B34B8),
    WATER("amenity=drinking_water", "need.water", 0xFF1560A8),
    COURTS("leisure=pitch", "place.courts", 0xFFC8322A),
    FITNESS("leisure=fitness_station", "place.fitness", 0xFF7B4DFF),
    PICNIC("leisure=picnic_table", "place.picnic", 0xFF2F6B3A),
}

/** One marked thing in a park. */
class ParkPoi(val kind: ParkKind, val place: Place)

/** The park's outline, its paths and its trees, as the map has them. */
class ParkShape(val rings: List<List<LatLon>>, val paths: List<List<LatLon>>, val trees: List<LatLon>, val outlined: Boolean)

/** A park and what is in it. */
class Park(val place: Place, val name: String?, val hours: String?, val box: BBox, val shape: ParkShape, val pois: List<ParkPoi>)

/** Fits a park's box into a drawing of [width] x [height] with [pad] round it, keeping its shape. */
class ParkProjection(private val box: BBox, val width: Float, val height: Float, pad: Float) {
    private val lonScale = cos(Math.toRadians((box.south + box.north) / 2))
    private val spanX = max((box.east - box.west) * lonScale, 1e-6)
    private val spanY = max(box.north - box.south, 1e-6)
    private val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
    private val offX = (width - spanX * scale) / 2
    private val offY = (height - spanY * scale) / 2

    fun x(lon: Double): Float = (offX + (lon - box.west) * lonScale * scale).toFloat()
    fun y(lat: Double): Float = (offY + (box.north - lat) * scale).toFloat()
}

/** Finds the park to show and everything in it. */
object Parkland {
    private const val SEARCH_M = 1_500.0

    /** The park with [osmId], else the nearest named one (the one you stand in first), from the place store or the network. */
    suspend fun pick(services: Services, at: LatLon, osmId: String?): Place? {
        osmId?.let(OsmId::parse)?.let { id -> placeById(services, id)?.let { return it } }
        val found = services.local.finder.find("leisure=park and name", at, SEARCH_M, limit = 30).places
        // Distance to a park is the distance to its box: standing inside one is zero.
        return found.minByOrNull { f -> f.place.bbox?.let { distanceToBox(at, it) } ?: f.distanceM }?.place
    }

    private fun distanceToBox(p: LatLon, b: BBox): Double {
        val lat = p.lat.coerceIn(b.south, b.north)
        val lon = p.lon.coerceIn(b.west, b.east)
        return Geo.distance(p, LatLon(lat, lon))
    }

    suspend fun load(services: Services, park: Place): Park = withContext(Dispatchers.Default) {
        val box = (park.bbox ?: BBox.around(park.location, 80.0)).let { b ->
            // A little air round the outline so markers on its edge stay in the picture.
            val padLat = (b.north - b.south) * 0.04
            val padLon = (b.east - b.west) * 0.04
            BBox(b.west - padLon, b.south - padLat, b.east + padLon, b.north + padLat)
        }
        val radius = max(Geo.distance(LatLon(box.south, box.west), LatLon(box.north, box.east)) / 2, 120.0)
        val kinds = ParkKind.entries.associate { it.name to TagFilter.parse(it.filter) }
        val found = services.local.finder.findMany(kinds, box.center, radius, limit = 200)
        val pois = ParkKind.entries.flatMap { k -> found[k.name]?.places.orEmpty().filter { inside(box, it.place.location) }.map { ParkPoi(k, it.place) } }
        val trees = services.local.finder.find("natural=tree", box.center, radius, limit = 300).places.map { it.place.location }.filter { inside(box, it) }
        val shape = (services.local.parkShape?.invoke(park, box) ?: fetchShape(services, park, box))?.let { it.copy(trees = it.trees.ifEmpty { trees }) }
            ?: ParkShape(listOf(rectangle(park.bbox ?: box)), emptyList(), trees, outlined = false)
        Park(park, app.atlas.places.Localise.name(park.tags, java.util.Locale.getDefault().language), park.tags["opening_hours"], box, shape, pois)
    }

    private fun ParkShape.copy(trees: List<LatLon>) = ParkShape(rings, paths, trees, outlined)

    private fun inside(b: BBox, p: LatLon) = p.lat in b.south..b.north && p.lon in b.west..b.east

    private fun rectangle(b: BBox) = listOf(LatLon(b.south, b.west), LatLon(b.south, b.east), LatLon(b.north, b.east), LatLon(b.north, b.west))

    /** The outline and paths from Overpass (`out geom`), when the phone is online and the park has an id. */
    private suspend fun fetchShape(services: Services, park: Place, box: BBox): ParkShape? {
        val id = park.id ?: return null
        if (!services.local.online()) return null
        val type = when (id.type) { OsmType.NODE -> return null; OsmType.WAY -> "way"; OsmType.RELATION -> "relation" }
        val area = "(${box.south},${box.west},${box.north},${box.east})"
        val ql = "[out:json][timeout:30];($type(${id.id});way[\"highway\"~\"^(footway|path|pedestrian|cycleway|steps)\$\"]$area;);out geom;"
        return try {
            shapeOf(services.overpass.query(ql).elements, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The outline (ways, or the outer members of a multipolygon joined end to end) and the footpaths among [elements]. */
    fun shapeOf(elements: List<OverpassElement>, id: OsmId): ParkShape? {
        val me = elements.firstOrNull { it.type == id.type && it.id == id.id } ?: return null
        val rings = when (me.type) {
            OsmType.WAY -> listOf(me.points())
            OsmType.RELATION -> joinRings(me.members.orEmpty().filter { it.role == "outer" && it.geometry != null }.map { m -> pairs(m.geometry!!) })
            else -> emptyList()
        }.filter { it.size >= 3 }
        if (rings.isEmpty()) return null
        val paths = elements.filter { it.type == OsmType.WAY && it.tags["highway"] != null && it !== me }.map { it.points() }.filter { it.size >= 2 }
        return ParkShape(rings, paths, emptyList(), outlined = true)
    }

    private fun pairs(g: DoubleArray): List<LatLon> { val out = ArrayList<LatLon>(); var i = 0; while (i + 1 < g.size) { if (!g[i].isNaN()) out += LatLon(g[i], g[i + 1]); i += 2 }; return out }

    /** Joins open segments that share end points into rings; segments that already close stand as they are. */
    internal fun joinRings(segments: List<List<LatLon>>): List<List<LatLon>> {
        val open = segments.filter { it.isNotEmpty() }.map { ArrayList(it) }.toMutableList()
        val rings = ArrayList<List<LatLon>>()
        while (open.isNotEmpty()) {
            var ring = open.removeAt(0)
            var grew = true
            while (grew && ring.first() != ring.last()) {
                grew = false
                val it = open.iterator()
                while (it.hasNext()) {
                    val s = it.next()
                    when {
                        s.first() == ring.last() -> { ring.addAll(s.drop(1)); it.remove(); grew = true; break }
                        s.last() == ring.last() -> { ring.addAll(s.reversed().drop(1)); it.remove(); grew = true; break }
                        s.last() == ring.first() -> { ring = ArrayList(s + ring.drop(1)); it.remove(); grew = true; break }
                        s.first() == ring.first() -> { ring = ArrayList(s.reversed() + ring.drop(1)); it.remove(); grew = true; break }
                    }
                }
            }
            rings += ring
        }
        return rings
    }

    /** "6:00–1:00": the span a park is open today, from its `opening_hours`; "24/7" for always; null when unmapped. */
    fun todaysSpan(hours: String?, today: LocalDate): String? {
        val parsed = OpeningHours.parseOrNull(hours) ?: return null
        if (parsed.isAlwaysOpen) return "24/7"
        val start = LocalDateTime.of(today, java.time.LocalTime.MIDNIGHT)
        val s0 = parsed.status(start)
        if (s0.unknown) return null
        val from = if (s0.isOpen) start else s0.changesAt ?: return null
        if (from.toLocalDate() != today) return null
        val close = parsed.status(from).changesAt ?: return null
        val f = java.time.format.DateTimeFormatter.ofPattern("H:mm")
        return "${f.format(from)}–${f.format(close)}"
    }
}
