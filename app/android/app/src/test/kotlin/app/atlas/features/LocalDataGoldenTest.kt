package app.atlas.features

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.Place
import app.atlas.places.TagFilter
import app.atlas.render.data.ElementType
import app.atlas.render.data.OsmElement
import app.atlas.render.data.OverpassJson
import app.atlas.render.data.PackBoxes
import app.atlas.render.data.PackFeatures
import app.atlas.render.data.PackReader
import app.atlas.services.LocalData
import app.atlas.services.PlaceOrigin
import app.atlas.ui.search.QuestFinder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [LocalData] on the bundled Westerkerk pack alone (no place store, offline) against today's tile answers
 * ([Goldens]): equal or better at every fixed point, along every recorded route and for every list, where the pack
 * covers the place. "Better" is spelled out per case; the OSM truth for bridges is brute force over the extract.
 */
class LocalDataGoldenTest {
    companion object {
        private val repo = File(System.getProperty("atlas.repo") ?: "../..")
        val goldens: Goldens by lazy { Goldens.read() }
        private val packFile = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
        val pack: PackFeatures by lazy { PackFeatures(listOf(PackBoxes(PackReader.open(packFile)))) }
        val local: LocalData by lazy { LocalData(stores = { null }, packs = { pack }) }

        val raw: List<OsmElement> by lazy {
            val out = ArrayList<OsmElement>()
            File(repo, "data/ams/raw.json").inputStream().buffered().use { OverpassJson.read(OverpassJson.pull(it)) { e -> out += e } }
            out
        }

        /** The renders' canal-ring pack, when this machine has it (`cache/packs/regions/grachten.atlas`). */
        val grachten: File get() = File(repo, "cache/packs/regions/grachten.atlas")

        const val NAME_M = 25.0
        const val HOUSE_M = 10.0
    }

    private fun covered(p: LatLon) = pack.covers(p)

    @Test
    fun theStreetNamesAtTheFixedPoints() {
        val wrong = ArrayList<String>()
        for (p in goldens.points) {
            val way = local.wayName(p.at, p.bearing)
            if (way != p.way) wrong += "${p.name}: along ${p.bearing} the tiles say ${p.way}, the pack $way"
            val nearest = local.wayName(p.at, null, 120.0)
            if (nearest != p.nearest) wrong += "${p.name}: nearest the tiles say ${p.nearest}, the pack $nearest"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertEquals(20, goldens.points.size)
    }

    @Test
    fun theStreetNamesAlongTheRecordedRoutes() {
        var asked = 0
        val wrong = ArrayList<String>()
        for (r in goldens.routes) for (s in r.steps) {
            if (!covered(s.at)) continue
            asked++
            val name = local.wayName(s.at, s.bearing)
            if (name != s.street) wrong += "${r.name} at ${s.at} along ${s.bearing}: the tiles say ${s.street}, the pack $name"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        println("route steps inside the bundled pack: $asked of ${goldens.routes.sumOf { it.steps.size }}, all named as the tiles name them")
        assertTrue("$asked", asked >= 20)
    }

    @Test
    fun theWholeRoutesOnTheCanalRingPack() {
        assumeTrue("needs $grachten", grachten.isFile)
        PackReader.open(grachten).use { reader ->
            val ring = LocalData(stores = { null }, packs = { PackFeatures(listOf(PackBoxes(reader))) })
            val wrong = ArrayList<String>()
            var asked = 0
            for (r in goldens.routes) for (s in r.steps) {
                if (!reader.manifest.bbox.contains(s.at)) continue
                asked++
                val name = ring.wayName(s.at, s.bearing)
                if (name != s.street) wrong += "${r.name} at ${s.at} along ${s.bearing}: the tiles say ${s.street}, the pack $name"
            }
            assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
            println("route steps inside the canal-ring pack: $asked of ${goldens.routes.sumOf { it.steps.size }}, all named as the tiles name them")
            assertTrue("$asked", asked >= 35)
        }
    }

    @Test
    fun howFastOnTheCanalRingPack() = runBlocking {
        assumeTrue("needs $grachten", grachten.isFile)
        PackReader.open(grachten).use { reader ->
            val ring = LocalData(stores = { null }, packs = { PackFeatures(listOf(PackBoxes(reader))) })
            val at = goldens.points.first().at
            fun <T> ms(n: Int, block: () -> T): Double {
                block()
                val t0 = System.nanoTime()
                repeat(n) { block() }
                return (System.nanoTime() - t0) / 1e6 / n
            }
            val way = ms(200) { ring.wayName(at, 3.0) }
            val address = ms(100) { ring.address(at) }
            val cafes = ms(20) { runBlocking { ring.places(at, 300.0, TagFilter.Equals("amenity", "cafe")) } }
            val bridges = ms(10) { ring.bridges(goldens.routes.first { it.mode == "BOAT" }.points) }
            println("canal-ring pack (${reader.elementCount} elements): wayName %.2f ms, address %.2f ms, cafés within 300 m %.1f ms, the boat route's bridges %.1f ms".format(way, address, cafes, bridges))
            assertTrue("$way", way < 20.0)
            assertTrue("$address", address < 50.0)
        }
    }

    @Test
    fun addressesWithinTenMetres() {
        val wrong = ArrayList<String>()
        for (p in goldens.points) {
            val g = p.address ?: continue
            val a = local.address(p.at)
            if (a == null) { wrong += "${p.name}: no address"; continue }
            assertEquals(PlaceOrigin.PACK, a.origin)
            val sameHouse = a.street == g.street && a.housenumber == g.housenumber
            when {
                g.houseAt != null -> {
                    // Equal: the same house, or one within 10 m of the tiles' one. Better: an address no farther from the
                    // point than the tiles' house number (0 inside the building whose outline holds the point).
                    val apart = a.at?.let { Geo.distance(it, g.houseAt) }
                    val tilesFar = Geo.distance(p.at, g.houseAt)
                    val equal = sameHouse || (apart != null && apart <= HOUSE_M)
                    val better = a.distanceM != null && a.distanceM!! <= tilesFar
                    if (!sameHouse) println("address ${p.name}: tiles ${g.street} ${g.housenumber} at %.1f m, pack ${a.line} at %.1f m, %.1f m apart".format(tilesFar, a.distanceM ?: -1.0, apart ?: -1.0))
                    if (!equal && !better) wrong += "${p.name}: the tiles say ${g.street} ${g.housenumber} (%.1f m), the pack ${a.line} (%.1f m), %.1f m apart".format(tilesFar, a.distanceM ?: -1.0, apart ?: -1.0)
                }
                // The tiles knew the street alone: the pack must know it too, or the house itself.
                else -> if (a.street != g.street && a.housenumber == null) wrong += "${p.name}: the tiles say ${g.street}, the pack ${a.line}"
            }
            if (a.town != g.town) wrong += "${p.name}: town ${g.town} against ${a.town}"
            // The quarter: the tiles' nearest label, or (better) the quarter whose area holds the point.
            val holding = pack.containing(p.at) { it.tag("place") == "quarter" }.map { it.element.tag("name") }
            if (a.quarter != g.quarter && a.quarter !in holding) wrong += "${p.name}: quarter ${g.quarter} against ${a.quarter}"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun theRegistrysAddressesInsideTheirBuildings() {
        // The national registry's (BAG's) addresses of two buildings (data/ams/whatshere), asked from inside each.
        for (name in listOf("descarteshuis", "leliegracht49")) {
            val o = Json.parseToJsonElement(File(repo, "data/ams/whatshere/$name.json").readText()) as JsonObject
            val way = (o["way"] as JsonPrimitive).content.toLong()
            val bag = o["bag"] as JsonObject
            val unit = (bag["units"] as? JsonArray)?.first() as? JsonObject ?: bag
            val expected = (unit["address"] as JsonPrimitive).content
            val building = pack.elements(BBox.around(goldens.points.first().at, 1_000.0)) { it.type == ElementType.WAY && it.id == way }.single()
            val inside = PackFeatures.outlineOf(building)!!.label()
            val a = local.address(inside)!!
            assertEquals(name, expected, a.line)
            assertEquals(0.0, a.distanceM!!, 0.0)
            (unit["postcode"] as? JsonPrimitive)?.content?.let { pc -> a.postcode?.let { assertEquals(name, pc, it.replace(" ", "")) } }
        }
    }

    @Test
    fun everyPointOfInterestWithin300mFound() = runBlocking {
        val g = goldens.pois
        // The tiles name a point of interest by its name, else its operator or brand (with its ref): "Equans AMS-4376".
        val named = TagFilter.Or(listOf(TagFilter.Has("name"), TagFilter.Has("brand"), TagFilter.Has("operator")))
        val found = local.places(g.centre, g.radius + NAME_M, named, 100_000)
        assertEquals(PlaceOrigin.PACK, found.origin)
        val missing = missing(g.places, found.value)
        assertTrue("${missing.size} of ${g.places.size} missing:\n" + missing.joinToString("\n"), missing.isEmpty())
        println("points of interest within ${g.radius} m: the tiles ${g.places.size}, all found; the pack lists ${found.value.count { Geo.distance(g.centre, it.location) <= g.radius }} named places")
    }

    @Test
    fun everyCafeWithin300mFound() = runBlocking {
        val g = goldens.cafes
        val found = local.places(g.centre, g.radius + NAME_M, TagFilter.Equals("amenity", "cafe"), 1_000)
        val missing = missing(g.places, found.value)
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
        // Within the radius itself, the pack finds at least as many as the tiles.
        val within = found.value.count { Geo.distance(g.centre, it.location) <= g.radius }
        assertTrue("$within against ${g.places.size}", within >= g.places.size)
    }

    @Test
    fun theAlbertHeijnBranchesAndTheChains() = runBlocking {
        val g = goldens.brand
        val branches = local.branches(g.id, g.centre, g.radius)
        assertEquals(PlaceOrigin.PACK, branches.origin)
        val missing = missing(g.branches.filter { covered(it.at) }, branches.value)
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
        val c = goldens.chains
        val chains = local.brands(BBox.around(c.centre, c.radius + NAME_M), 500).value.associateBy { it.brand.id }
        val wrong = ArrayList<String>()
        for ((id, list) in c.chains) {
            val have = chains[id]?.branches.orEmpty()
            val lost = missing(list, have)
            if (lost.isNotEmpty()) wrong += "$id: " + lost.joinToString()
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun theQuestsFromThePackAreTheStoresQuests() = runBlocking {
        val g = goldens.quests
        val places = local.quests(BBox.around(g.centre, g.radius))
        assertEquals(PlaceOrigin.PACK, places.origin)
        val quests = QuestFinder.questsOf(places.value, g.centre, "en", java.time.LocalDateTime.parse(g.now), emptySet())
        assertEquals(g.quests.map { it.place + "|" + it.key }, quests.map { it.place.key + "|" + it.quest.key })
        for ((a, b) in g.quests.zip(quests)) assertEquals(a.distance, b.distanceM, 0.01)
    }

    @Test
    fun theBridgesAreTheOsmBridgesCrossed() {
        val wrong = ArrayList<String>()
        var tiles = 0
        for (r in goldens.routes) {
            val bridges = local.bridges(r.points)
            val truth = bruteForceBridges(r.points)
            println("bridges along ${r.name}: the pack ${bridges.size}, OSM brute force ${truth.size}, the tiles ${r.bridges.size} (${r.bridges.count { covered(it.at) }} inside the pack)")
            if (bridges.map { it.along.round() } != truth.map { it.round() }) {
                wrong += "${r.name}: the pack ${bridges.map { it.along.round() }}, OSM ${truth.map { it.round() }}"
            }
            // Every bridge the tiles draw across the route where the pack covers it is one of them.
            for (b in r.bridges) {
                if (!covered(b.at)) continue
                tiles++
                if (bridges.none { kotlin.math.abs(it.along - b.along) < LocalData.SAME_M }) wrong += "${r.name}: the tiles' bridge at ${b.along.round()} m (${b.at}) is missing"
            }
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue("$tiles", tiles >= 5)
        // The boat passes under bridges with names and, where mapped, clearances.
        val boat = local.bridges(goldens.routes.first { it.mode == "BOAT" }.points)
        assertTrue(boat.count { it.name != null } >= boat.size / 2)
    }

    /** The places of [golden] that [found] lacks: none with the same name (or, unnamed, the same kind) within 25 m. */
    private fun missing(golden: List<GoldenPlace>, found: List<Place>): List<String> = golden.filter { g ->
        found.none { p ->
            Geo.distance(p.location, g.at) <= NAME_M && (if (g.name != null) sameName(g.name, p.tags) else g.tags.all { (k, v) -> k.startsWith("name") || p.tags[k] == v })
        }
    }.map { "${it.id} ${it.name} ${it.tags} at ${it.at}" }

    /** True when the tiles' label [label] is the place's name, brand or operator, alone or with its ref. */
    private fun sameName(label: String, t: Map<String, String>): Boolean {
        val names = listOfNotNull(t["name"], t["brand"], t["operator"])
        return label in names || t["ref"]?.let { ref -> names.any { "$it $ref" == label } } == true
    }

    /** Brute force over every element of the extract: where its bridge ways and outlines cross [line], grouped as one bridge within 15 m. */
    private fun bruteForceBridges(line: List<LatLon>): List<Double> {
        val frame = LocalFrame(line[0])
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + Geo.distance(line[i - 1], line[i])
        val alongs = ArrayList<Double>()
        for (e in raw) {
            if (!PackFeatures.isBridge(e)) continue
            val hits = ArrayList<Double>()
            for (g in PackFeatures.linesOf(e)) for (k in 0 until g.size - 1) {
                if (g.isNull(k) || g.isNull(k + 1)) continue
                for (j in 0 until line.size - 1) {
                    val t = PackFeatures.intersect(
                        frame.x(line[j].lon), frame.y(line[j].lat), frame.x(line[j + 1].lon), frame.y(line[j + 1].lat),
                        frame.x(g.lon[k]), frame.y(g.lat[k]), frame.x(g.lon[k + 1]), frame.y(g.lat[k + 1]),
                    )
                    if (t >= 0) hits += cum[j] + t * (cum[j + 1] - cum[j])
                }
            }
            hits.sort()
            if (e.tag("man_made") == "bridge") {
                var i = 0
                while (i < hits.size) { alongs += if (i + 1 < hits.size) (hits[i] + hits[i + 1]) / 2 else hits[i]; i += 2 }
            } else alongs += hits
        }
        alongs.sort()
        val groups = ArrayList<List<Double>>()
        var i = 0
        while (i < alongs.size) {
            var j = i + 1
            while (j < alongs.size && alongs[j] - alongs[i] < LocalData.SAME_M) j++
            groups += alongs.subList(i, j)
            i = j
        }
        return groups.map { it[it.size / 2] }
    }

    private fun Double.round() = Math.round(this * 10) / 10.0
}
