package app.atlas.features

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import app.atlas.places.Brands
import app.atlas.places.TagFilter
import app.atlas.route.BRouterRouter
import app.atlas.route.RouteResult
import app.atlas.route.WaterRouter
import app.atlas.route.WayNamer
import app.atlas.services.TilePlaces
import app.atlas.testing.FixtureTiles
import app.atlas.testing.Fixtures
import app.atlas.tiles.GeomType
import app.atlas.tiles.Mvt
import app.atlas.tiles.TileStack
import app.atlas.ui.go.GoFixtures
import app.atlas.ui.go.TileBridges
import app.atlas.ui.go.TileWayNamer
import app.atlas.ui.search.BrandsAround
import app.atlas.ui.search.QuestFinder
import app.atlas.ui.search.TileAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Records today's tile-stack answers as the goldens ([Goldens.file]) when the file is missing (delete it to record
 * again; the routes need BRouter's Amsterdam segment, `cache/brouter/segments4/E0_N50.rd5`), and otherwise asks
 * today's code again at the recorded inputs and proves it still answers the same. Under Robolectric because the
 * quests come from the place store of `data/ams/raw.json`.
 */
@RunWith(RobolectricTestRunner::class)
class GoldenRecorderTest {
    @Test
    fun theGoldensAreTodaysTileAnswers() {
        val file = Goldens.file
        if (!file.isFile) {
            assumeTrue("recording the goldens needs ${GoFixtures.amsterdam}", GoFixtures.hasSegment)
            file.parentFile.mkdirs()
            file.writeText(TodaysAnswers.record(TodaysAnswers.routes()).toJson())
        }
        val recorded = Goldens.read(file)
        val again = TodaysAnswers.record(recorded.routes.map { r -> RecordedRoute(r.name, r.mode, r.points, r.steps.map { it.at to it.bearing }) })
        assertEquals(recorded.toJson(), again.toJson())
    }
}

/** A route's line and the street-name questions its steps asked, as BRouter made them. */
data class RecordedRoute(val name: String, val mode: String, val points: List<LatLon>, val asked: List<Pair<LatLon, Double>>)

/** Today's answers: the tile stack over the fixture tiles, and the place store for the quests. */
object TodaysAnswers {
    val WESTERKERK = Services.WESTERKERK
    const val RADIUS_M = 300.0
    const val BRAND_RADIUS_M = 500.0
    const val ALBERT_HEIJN = "Q1653985"

    /** The quests' "now": the app's fixed test evening. */
    val now: LocalDateTime get() = LocalDateTime.now(Fixtures.evening)

    /** The 20 fixed points, each a few metres off its street's centre line, facing along it. */
    val POINTS = listOf(
        Point("westerkerk", 52.374531, 4.883447, 3.0, "Prinsengracht"),
        Point("annefrank", 52.375303, 4.884042, 29.0, "Prinsengracht"),
        Point("raadhuisstraat", 52.373784, 4.885977, 312.0, "Raadhuisstraat"),
        Point("westermarkt", 52.374114, 4.884501, 273.0, "Westermarkt"),
        Point("keizersgracht_lelie", 52.375792, 4.885933, 209.0, "Keizersgracht"),
        Point("herengracht_lelie", 52.376171, 4.888983, 30.0, "Herengracht"),
        Point("leliegracht", 52.375145, 4.887108, 299.0, "Leliegracht"),
        Point("bloemgracht", 52.375007, 4.881493, 250.0, "Bloemgracht"),
        Point("egelantiersgracht", 52.376363, 4.882901, 71.0, "Egelantiersgracht"),
        Point("rozengracht", 52.373447, 4.881037, 68.0, "Rozengracht"),
        Point("egelantiersdwars", 52.376305, 4.882424, 340.0, "Tweede Egelantiersdwarsstraat"),
        Point("tuinstraat", 52.376846, 4.881173, 250.0, "Tuinstraat"),
        Point("prinsengracht_south", 52.372233, 4.883104, 183.0, "Prinsengracht"),
        Point("reestraat", 52.372392, 4.884663, 273.0, "Reestraat"),
        Point("hartenstraat", 52.372246, 4.886712, 94.0, "Hartenstraat"),
        Point("herengracht_raadhuis", 52.374260, 4.887824, 29.0, "Herengracht"),
        Point("singel_torensteeg", 52.374683, 4.889939, 34.0, "Singel"),
        Point("nieuwe_leliestraat", 52.375465, 4.881428, 250.0, "Nieuwe Leliestraat"),
        Point("keizersgracht_south", 52.371897, 4.885068, 184.0, "Keizersgracht"),
        Point("eerste_tuindwars", 52.377421, 4.883894, 340.0, "Eerste Tuindwarsstraat"),
    )

    data class Point(val name: String, val lat: Double, val lon: Double, val bearing: Double, val street: String) {
        val at: LatLon get() = LatLon(lat, lon)
    }

    private val tiles = TileStack(listOf(FixtureTiles()))
    private val namer = TileWayNamer({ tiles })

    /** Every golden, with the routes given (BRouter's for a recording, the recorded ones to check again). */
    fun record(routes: List<RecordedRoute>): Goldens = runBlocking {
        val points = POINTS.map { p ->
            val a = TileAddress.at(tiles, p.at)
            val address = a?.let { GoldenAddress(it.street, it.housenumber, it.quarter, it.town, it.housenumber?.let { n -> houseAt(p.at, n) }) }
            GoldenPoint(p.name, p.at, p.bearing, p.street, namer.name(p.at, p.bearing), namer.nearest(p.at), address)
        }
        val cafeFilter = TagFilter.Equals("amenity", "cafe")
        val cafes = TilePlaces.near(tiles, WESTERKERK, RADIUS_M, cafeFilter, 500)
        val named = TilePlaces.near(tiles, WESTERKERK, RADIUS_M, TagFilter.Has("name"), 5_000)
        val brandId = Brands.byWikidata(ALBERT_HEIJN).first().id
        val aroundBrand = TilePlaces.near(tiles, WESTERKERK, BRAND_RADIUS_M, TagFilter.Has("name"), 5_000)
        // BrandsAround.branches' tile path, as it is today.
        val branches = aroundBrand.filter { BrandsAround.isTrade(it.tags) && Brands.forTags(it.tags)?.id == brandId }
            .sortedBy { Geo.distance(WESTERKERK, it.location) }
        val split = BrandsAround.split(named, WESTERKERK, RADIUS_M, null)
        val store = Fixtures.amsStore
        val questPlaces = store.inBox(BBox.around(WESTERKERK, QuestFinder.RADIUS_M)).map { it.place }
        val quests = QuestFinder.questsOf(questPlaces, WESTERKERK, "en", now, emptySet())
        Goldens(
            source = "testdata/omt (OpenFreeMap tiles, OpenMapTiles schema) through TileStack: TileAddress, TileWayNamer, " +
                "TilePlaces, BrandsAround, TileBridges; the quests through QuestFinder over the place store of data/ams/raw.json",
            points = points,
            cafes = GoldenPlaces(WESTERKERK, RADIUS_M, "amenity=cafe", cafes.map(::golden)),
            pois = GoldenPlaces(WESTERKERK, RADIUS_M, "name", named.map(::golden)),
            brand = GoldenBrand(brandId, WESTERKERK, BRAND_RADIUS_M, branches.map(::golden)),
            chains = GoldenChains(WESTERKERK, RADIUS_M, split.chains.associate { c -> c.brand.id to c.branches.map(::golden) }),
            quests = GoldenQuests(WESTERKERK, QuestFinder.RADIUS_M, now.toString(), quests.map { GoldenQuest(it.place.key, it.place.tags["name"], it.quest.key, it.distanceM) }),
            routes = routes.map { r ->
                val route = Route(Mode.valueOf(r.mode), r.points, distance = 0.0, duration = 0.0, steps = emptyList())
                val crossings = TileBridges.crossings(route, tiles)
                GoldenRoute(
                    r.name, r.mode, r.points,
                    r.asked.map { (at, b) -> GoldenStep(at, b, namer.name(at, b)) },
                    crossings.map { GoldenBridge(it, route.line.pointAt(it)) },
                )
            },
        )
    }

    private fun golden(p: Place) = GoldenPlace(p.id?.toString(), p.tags["name"], p.location, p.tags)

    /** Where the tiles' house number [n] nearest [p] stands (the point `TileAddress` chose). */
    private suspend fun houseAt(p: LatLon, n: String): LatLon? {
        var best: LatLon? = null
        var bestD = TileAddress.HOUSE_M
        val mx = 111_320.0 * cos(Math.toRadians(p.lat))
        for (id in Mercator.tilesIn(BBox.around(p, TileAddress.STREET_M), 14)) {
            val hit = tiles.bestTile(id) ?: continue
            val layer = Mvt.decode(hit.data, setOf("housenumber"))["housenumber"] ?: continue
            val proj = layer.projection(hit.source)
            for (f in layer.features) {
                if (f.type != GeomType.POINT || f.geometry.isEmpty || f.string("housenumber") != n) continue
                val at = LatLon(proj.lat(f.geometry.y(0).toDouble()), proj.lon(f.geometry.x(0).toDouble()))
                val d = hypot((at.lon - p.lon) * mx, (at.lat - p.lat) * 110_540.0)
                if (d < bestD) { bestD = d; best = at }
            }
        }
        return best
    }

    /** BRouter's routes on the real Amsterdam segment, with every street-name question their steps asked. */
    fun routes(): List<RecordedRoute> {
        val asked = ArrayList<Pair<LatLon, Double>>()
        val recording = WayNamer { at, bearing -> synchronized(asked) { asked += at to bearing }; namer.name(at, bearing) }
        val router = BRouterRouter(
            GoFixtures.segments, Files.createTempDirectory("atlas-goldens-profiles").toFile().apply { deleteOnExit() },
            memoryClassMb = 128, namer = recording,
        )
        fun take(name: String, mode: Mode, result: RouteResult): RecordedRoute {
            val best = (result as? RouteResult.Found)?.best ?: error("no $name route: $result")
            val questions = synchronized(asked) { asked.toList().also { asked.clear() } }
            return RecordedRoute(name, mode.name, best.points, questions)
        }
        return runBlocking {
            listOf(
                take("bike to Centraal", Mode.BIKE, router.route(RouteRequest(Mode.BIKE, listOf(WESTERKERK, GoFixtures.CENTRAAL)))),
                take(
                    "walk in the Westerkerk pack", Mode.WALK,
                    router.route(RouteRequest(Mode.WALK, listOf(WESTERKERK, LatLon(52.3765, 4.8818), LatLon(52.3762, 4.8889), LatLon(52.3725, 4.8865)))),
                ),
                take("boat to the IJ", Mode.BOAT, WaterRouter(router).route(RouteRequest(Mode.BOAT, listOf(WESTERKERK, GoFixtures.IJ)))),
            )
        }
    }
}
