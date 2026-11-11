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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What a park page is about: a town's park, a nature reserve or a national park, each with a sign of its own. */
enum class GreenKind {
    PARK, RESERVE, NATIONAL;

    companion object {
        /** [protectsNature] for a protected area, in a filter's words (the wild notes' and the nature reserves' rows). */
        const val NATURE_PROTECTED = "boundary=protected_area and ((protect_class and protect_class!~\"^[12][0-9]$\") or (not protect_class and not heritage and not historic))"

        /** The kind of green [tags] describe; null for anything else (a garden, a cemetery, the canal ring). */
        fun of(tags: Map<String, String>): GreenKind? = when {
            tags["boundary"] == "national_park" -> NATIONAL
            tags["leisure"] == "nature_reserve" -> RESERVE
            tags["boundary"] == "protected_area" && protectsNature(tags) -> RESERVE
            tags["leisure"] == "park" -> PARK
            else -> null
        }

        /**
         * A protected area kept for nature: a nature class, or none given on an area that is not a heritage site. IUCN's
         * classes 11 to 29 protect soil, water, culture or the military: Amsterdam's canal ring is a 22. The place store's
         * rule ([app.atlas.search.Outlines.protectsNature]), whose outlines it keeps.
         */
        fun protectsNature(tags: Map<String, String>): Boolean = app.atlas.search.Outlines.protectsNature(tags)
    }
}

/** What a park's plan marks: the things you look for in a park, and in a national park where to ask and where to sleep. */
enum class ParkKind(val filter: String, val symbol: String, val colour: Long) {
    PLAYGROUND("leisure=playground", "place.playground.4", 0xFFE8622C),
    DOGS("leisure=dog_park", "fact.dog", 0xFF12A9A6),
    CAFE("amenity=cafe or amenity=kiosk or amenity=restaurant or amenity=ice_cream", "place.cafe", 0xFF8C5A2B),
    TOILETS("amenity=toilets", "WC", 0xFF4B34B8),
    WATER("amenity=drinking_water", "need.water", 0xFF1560A8),
    COURTS("leisure=pitch", "place.courts", 0xFFC8322A),
    FITNESS("leisure=fitness_station", "place.fitness", 0xFF7B4DFF),
    PICNIC("leisure=picnic_table", "place.picnic", 0xFF2F6B3A),

    /** A visitor centre or a ranger station, in the Wild board's dark green. */
    VISITOR("(tourism=information and information~^(office|visitor_centre)$) or amenity=ranger_station", "wild.visitor-centre", 0xFF1F3A2A),

    /** A camp site or a hut, in the nature family's green (camping is a nature category). */
    SLEEP("tourism=camp_site or tourism=caravan_site or tourism=wilderness_hut or tourism=alpine_hut", "place.camp", 0xFF2F6239);

    companion object {
        /** The order a sign offers its kinds in, and so the one it shows first: in the wilds where to ask and where to sleep. */
        fun order(kind: GreenKind): List<ParkKind> = when (kind) {
            GreenKind.PARK -> entries
            else -> listOf(VISITOR, SLEEP, TOILETS, WATER, CAFE, PICNIC, PLAYGROUND, DOGS, COURTS, FITNESS)
        }
    }
}

/** One marked thing in a park. */
class ParkPoi(val kind: ParkKind, val place: Place)

/**
 * A park's outline ([holes] marks the inner rings), its paths and its trees, as the map has them. [areaM2] is the area of
 * a real outline less its holes, null for the box drawn when there is none ([outlined] false).
 */
class ParkShape(
    val rings: List<List<LatLon>>,
    val paths: List<List<LatLon>>,
    val trees: List<LatLon>,
    val outlined: Boolean,
    val holes: List<Boolean> = emptyList(),
    val areaM2: Double? = null,
) {
    /** True inside the outline (even-odd over every ring, so a lake left out of a park is not in it). */
    fun contains(p: LatLon): Boolean {
        var inside = false
        for (ring in rings) {
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[j]
                if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Metres from [p] to the nearest edge of the outline. */
    fun edgeDistance(p: LatLon): Double {
        val k = cos(Math.toRadians(p.lat)) * M_PER_DEG
        var best = Double.MAX_VALUE
        for (ring in rings) for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val ax = (a.lon - p.lon) * k; val ay = (a.lat - p.lat) * M_PER_DEG
            val bx = (b.lon - p.lon) * k; val by = (b.lat - p.lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val t = if (dx == 0.0 && dy == 0.0) 0.0 else ((-ax * dx - ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            best = min(best, sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy)))
        }
        return best
    }

    /** True inside the park or within [marginM] of its edge, where its gate, its café over the path and its toilets stand. */
    fun reaches(p: LatLon, marginM: Double): Boolean = contains(p) || edgeDistance(p) <= marginM

    internal fun withTrees(t: List<LatLon>) = ParkShape(rings, paths, t, outlined, holes, areaM2)

    companion object {
        private const val M_PER_DEG = 111_320.0

        /** The area of [rings] less the [holes]' in square metres, on a plane through their middle (a park is small for the earth). */
        fun areaOf(rings: List<List<LatLon>>, holes: List<Boolean>): Double {
            val lat0 = rings.flatten().let { pts -> if (pts.isEmpty()) return 0.0 else (pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2 }
            val k = cos(Math.toRadians(lat0)) * M_PER_DEG
            var total = 0.0
            rings.forEachIndexed { r, ring ->
                var s = 0.0
                for (i in ring.indices) {
                    val a = ring[i]
                    val b = ring[(i + 1) % ring.size]
                    s += (a.lon * k) * (b.lat * M_PER_DEG) - (b.lon * k) * (a.lat * M_PER_DEG)
                }
                total += if (holes.getOrNull(r) == true) -abs(s) / 2 else abs(s) / 2
            }
            return max(total, 0.0)
        }
    }
}

/**
 * A park and what is in it: its kind and box, its shape, the marked things in reach of it, the named trails whose middle is
 * in it, and its trees and bird places for the field notes (with their tags, from the search's centre).
 */
class Park(
    val place: Place,
    val kind: GreenKind,
    val name: String?,
    val hours: String?,
    val box: BBox,
    val shape: ParkShape,
    val pois: List<ParkPoi>,
    val trails: List<Place> = emptyList(),
    val trees: List<Found> = emptyList(),
    val birds: List<Found> = emptyList(),
)

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

    companion object {
        /** The height that fits [box] at [width] with [pad] round it, kept between [low] and [high]: a long park a low sign. */
        fun heightFor(box: BBox, width: Float, pad: Float, low: Float, high: Float): Float {
            val spanX = max((box.east - box.west) * cos(Math.toRadians((box.south + box.north) / 2)), 1e-6)
            val spanY = max(box.north - box.south, 1e-6)
            return (2 * pad + (width - 2 * pad) * spanY / spanX).toFloat().coerceIn(low, high)
        }
    }
}

/** Finds the park to show and everything in it. */
object Parkland {
    private const val SEARCH_M = 1_500.0

    /** The paths a plan draws: the ways people walk in a park, the tracks of the woods and heaths among them. */
    val PATHS = app.atlas.search.Outlines.PATHS

    /** A park bigger than this across (metres) is a landscape: its single trees and its paths' every bend would be noise. */
    private const val LANDSCAPE_M = 3_000.0

    /** The trees a landscape's notes are about: named, a natural monument, or heritage. */
    private const val NOTABLE_TREES = "natural=tree and (name or denotation=natural_monument or monument=yes or heritage)"

    /** The named walking trails: route relations, kept with their box by a region's import. */
    private val TRAILS = TagFilter.parse("route~^(hiking|foot|walking)$ and name")

    /** The park page for [place], when it is a park, a reserve or a national park with an outline's box; null otherwise. */
    fun destOf(place: Place): app.atlas.nav.Dest.Parkland? {
        if (GreenKind.of(place.tags) == null || place.bbox == null) return null
        val id = place.id?.takeIf { it.type != OsmType.NODE } ?: return null
        return app.atlas.nav.Dest.Parkland(osmId = id.toString(), key = place.key)
    }

    /**
     * The park the page shows: the one the caller kept under [key], else the one with [osmId] (the place store's, else the
     * network's), else the nearest named park (the one you stand in first).
     */
    suspend fun pick(services: Services, at: LatLon, osmId: String?, key: String? = null): Place? {
        key?.let { services.placeCache[it] }?.let { return it }
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

    /** "De Hoge Veluwe": a national park's name without the "Nationaal Park", "Nationalpark" or "Parc national de" its sign says. */
    fun shortName(name: String, kind: GreenKind): String {
        if (kind != GreenKind.NATIONAL) return name
        val s = name.replace(NATIONAL_BEFORE, "").replace(NATIONAL_AFTER, "").trim()
        return if (s.length >= 3) s else name
    }

    private val NATIONAL_BEFORE = Regex(
        // An article only as a whole word ("della Sila", never "del" of it, nor of a "Delta").
        "^(nationaal park|national park|nationalpark|parc national(?: de la| de l'| du| des| de| d')?|parque nacional(?: de la| de los| de las| del| de)?|parco nazionale(?: della| delle| dello| degli| dell'| dei| del| di)?)\\b\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val NATIONAL_AFTER = Regex("\\s+(national park|nationalpark|nationaal park)$", RegexOption.IGNORE_CASE)

    suspend fun load(services: Services, park: Place): Park = withContext(Dispatchers.Default) {
        val kind = GreenKind.of(park.tags) ?: GreenKind.PARK
        val box = (park.bbox ?: BBox.around(park.location, 80.0)).let { b ->
            // A little air round the outline so markers on its edge stay in the picture.
            val padLat = (b.north - b.south) * 0.04
            val padLon = (b.east - b.west) * 0.04
            BBox(b.west - padLon, b.south - padLat, b.east + padLon, b.north + padLat)
        }
        val across = Geo.distance(LatLon(box.south, box.west), LatLon(box.north, box.east))
        val radius = max(across / 2, 120.0)
        val landscape = across > LANDSCAPE_M
        // The outline: the test's, else a downloaded region's element pack (with the paths), else its place store's when
        // it holds all the plan draws (a landscape's outline; a town park's with its paths), no network needed, else
        // Overpass's when online, else the store's outline without the paths, else the box.
        val shape = services.local.parkShape?.invoke(park, box)
            ?: packShape(services, park, box, landscape)
            ?: storeShape(services, park, landscape).let { kept -> kept?.takeIf { it.second }?.first ?: fetchShape(services, park, box, landscape) ?: kept?.first }
            ?: ParkShape(listOf(rectangle(park.bbox ?: box)), emptyList(), emptyList(), outlined = false)
        // Things at the gate belong to the park: a margin of its size, 30 to 250 m.
        val margin = (across * 0.04).coerceIn(30.0, 250.0)
        val kinds = ParkKind.entries.associate { it.name to TagFilter.parse(it.filter) }
        val found = services.local.finder.findMany(kinds, box.center, radius, limit = 200)
        val pois = ParkKind.entries.flatMap { k -> found[k.name]?.places.orEmpty().filter { shape.reaches(it.place.location, margin) }.map { ParkPoi(k, it.place) } }
        // A landscape's notable trees alone, for its notes; a town park's every tree, for its plan too.
        val trees = services.local.finder.find(if (landscape) NOTABLE_TREES else "natural=tree", box.center, radius, limit = 400).places.filter { shape.contains(it.place.location) }
        val trails = services.local.finder.find(TRAILS, box.center, radius, limit = 60).places.map { it.place }.filter { shape.contains(it.location) }
        val birds = services.local.finder.find(TagFilter.parse(NatureKind.BIRDS.filter), box.center, radius, limit = 40).places
            .filter { it.place.key != park.key && it.place.id != park.id && shape.reaches(it.place.location, margin) }
        Park(
            park, kind, app.atlas.places.Localise.name(park.tags, java.util.Locale.getDefault().language), park.tags["opening_hours"], box,
            if (shape.trees.isEmpty() && !landscape) shape.withTrees(trees.map { it.place.location }) else shape, pois, trails, trees, birds,
        )
    }

    private fun rectangle(b: BBox) = listOf(LatLon(b.south, b.west), LatLon(b.south, b.east), LatLon(b.north, b.east), LatLon(b.north, b.west))

    /** The outline and paths from a downloaded region's element pack, offline (a landscape's outline alone). */
    private fun packShape(services: Services, park: Place, box: BBox, landscape: Boolean): ParkShape? {
        val id = park.id ?: return null
        val o = runCatching { services.localData.outlineOf(id, park.bbox ?: box, if (landscape) emptySet() else PATHS) }.getOrNull() ?: return null
        if (o.rings.none { it.size >= 3 }) return null
        return ParkShape(o.rings, o.paths, emptyList(), outlined = true, holes = o.holes, areaM2 = o.areaM2)
    }

    /**
     * The outline a downloaded region's place store keeps, simplified, with its paths where the store keeps them (a
     * landscape's plan draws none), offline; and whether that is all the plan draws: a landscape's outline, or a town
     * park's with its paths.
     */
    private fun storeShape(services: Services, park: Place, landscape: Boolean): Pair<ParkShape, Boolean>? {
        val id = park.id ?: return null
        val o = runCatching { services.localData.storedOutline(id) }.getOrNull() ?: return null
        if (o.rings.none { it.size >= 3 }) return null
        val shape = ParkShape(o.rings, if (landscape) emptyList() else o.paths, emptyList(), outlined = true, holes = o.holes, areaM2 = o.areaM2)
        return shape to (landscape || o.pathsKept)
    }

    /** The outline and paths from Overpass (`out geom`), when the phone is online and the park has an id. */
    private suspend fun fetchShape(services: Services, park: Place, box: BBox, landscape: Boolean): ParkShape? {
        val id = park.id ?: return null
        if (!services.local.online()) return null
        val type = when (id.type) { OsmType.NODE -> return null; OsmType.WAY -> "way"; OsmType.RELATION -> "relation" }
        val area = "(${box.south},${box.west},${box.north},${box.east})"
        val paths = if (landscape) "" else "way[\"highway\"~\"^(${PATHS.sorted().joinToString("|")})\$\"]$area;"
        val ql = "[out:json][timeout:30];($type(${id.id});$paths);out geom;"
        return try {
            shapeOf(services.overpass.query(ql).elements, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The outline (ways, or the outer and inner members of a multipolygon joined end to end) and the footpaths among [elements]. */
    fun shapeOf(elements: List<OverpassElement>, id: OsmId): ParkShape? {
        val me = elements.firstOrNull { it.type == id.type && it.id == id.id } ?: return null
        val (rings, holes) = when (me.type) {
            OsmType.WAY -> listOf(me.points()) to listOf(false)
            OsmType.RELATION -> {
                val members = me.members.orEmpty().filter { it.geometry != null }
                val outer = joinRings(members.filter { it.role == "outer" || it.role == "" }.map { m -> pairs(m.geometry!!) }).filter { it.size >= 3 }
                val inner = joinRings(members.filter { it.role == "inner" }.map { m -> pairs(m.geometry!!) }).filter { it.size >= 3 }
                (outer + inner) to (outer.map { false } + inner.map { true })
            }
            else -> emptyList<List<LatLon>>() to emptyList()
        }
        if (rings.none { it.size >= 3 } || holes.none { !it }) return null
        val paths = elements.filter { it.type == OsmType.WAY && it.tags["highway"] != null && it !== me }.map { it.points() }.filter { it.size >= 2 }
        return ParkShape(rings, paths, emptyList(), outlined = true, holes = holes, areaM2 = ParkShape.areaOf(rings, holes))
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

    /**
     * A park's size as people say it, in [locale]'s digits: "450 m²" and "0.4 ha" for a pocket park, "12 ha" under a square
     * kilometre, "3.1 km²" up to ten, "55 km²" beyond; never more than it is.
     */
    fun areaText(m2: Double, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val tenths = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 0 }
        return when {
            m2 < 1_000 -> "${max(10, (kotlin.math.round(m2 / 10) * 10).toInt())} m²"
            m2 < 10_000 -> "${tenths.format(m2 / 10_000)} ha"
            m2 < 1_000_000 -> "${kotlin.math.round(m2 / 10_000).toInt()} ha"
            m2 < 10_000_000 -> "${tenths.format(m2 / 1_000_000)} km²"
            else -> "${kotlin.math.round(m2 / 1_000_000).toInt()} km²"
        }
    }
}
