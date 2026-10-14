package app.atlas.ui.go

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import app.atlas.route.BRouterRouter
import app.atlas.route.OverpassRestrictionSource
import app.atlas.route.RouteResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * The go package's test data: the real BRouter segment of Amsterdam (`cache/brouter/segments4/E0_N50.rd5`) when it is
 * on this machine, else the demo routes in `res/raw` (themselves computed from it); the trips of the demo and CI.
 */
object GoFixtures {
    val WESTERKERK = Services.WESTERKERK
    val CENTRAAL = LatLon(52.37903, 4.90010)
    val HAARLEM = LatLon(52.38785, 4.63830)
    val IJ = LatLon(52.3810, 4.9000)

    private val repo: File get() = File(System.getProperty("atlas.repo") ?: "../..")
    val segments: File get() = File(repo, "cache/brouter/segments4")
    val amsterdam: File get() = File(segments, "E0_N50.rd5")
    val hasSegment: Boolean get() = amsterdam.isFile

    /** The real map tiles around the Westerkerk, which name the streets. */
    private val tiles = app.atlas.testing.FixtureTiles()

    /** BRouter on the real segment, with street names from the fixture tiles, or null without the segment. */
    val router: BRouterRouter? by lazy {
        if (!hasSegment) null
        else BRouterRouter(
            segments, Files.createTempDirectory("atlas-go-profiles").toFile().apply { deleteOnExit() }, memoryClassMb = 128,
            namer = TileWayNamer({ tiles }),
        )
    }

    /**
     * BRouter as the app runs it online: with the limits along the route from Overpass, here answered by the Amsterdam
     * extract (`data/ams/raw.json`, an Overpass answer with every tag), so bridge clearances and road limits are checked.
     */
    val restrictedRouter: BRouterRouter? by lazy {
        if (!hasSegment) null
        else BRouterRouter(
            segments, Files.createTempDirectory("atlas-go-profiles").toFile().apply { deleteOnExit() }, memoryClassMb = 128,
            restrictions = OverpassRestrictionSource({ _ -> amsAnswer }, OverpassRestrictionSource.Scope.BOTH),
            namer = TileWayNamer({ tiles }),
        )
    }

    private val amsAnswer: String by lazy { File(repo, "data/ams/raw.json").readText() }

    /** The demo trip's destination for [mode]. */
    fun destinationFor(mode: Mode): LatLon = when {
        mode.water -> IJ
        mode == Mode.TRUCK -> HAARLEM
        else -> CENTRAAL
    }

    /** The route of [mode] from the Westerkerk to its demo destination: routed for real when possible, else the fixture. */
    fun route(mode: Mode, vehicle: Vehicle = GoStore.defaultVehicle(mode)): Route {
        val to = destinationFor(mode)
        router?.let { r ->
            val res = runBlocking { r.route(RouteRequest(mode, listOf(WESTERKERK, to), vehicle = vehicle)) }
            if (res is RouteResult.Found) return res.best
        }
        return fixture(mode) ?: error("no route for $mode: neither E0_N50.rd5 nor the demo fixture")
    }

    /** The demo fixture for [mode] from `res/raw`. */
    fun fixture(mode: Mode): Route? {
        val name = when {
            mode.water -> "boat"; mode == Mode.TRUCK -> "truck"; mode.cycling -> "bike"; mode.motor -> "car"; else -> "walk"
        }
        val f = File(repo, "app/android/app/src/main/res/raw/go_demo_$name.json")
        return GoJson.route(GoJson.parse(f.takeIf { it.isFile }?.readText()))?.let { if (it.mode == mode) it else it.copy(mode = mode) }
    }

    /** Puts the real segment where [services]' router looks (a link, not a copy); false when it is not on this machine. */
    fun linkSegment(services: Services): Boolean {
        if (!hasSegment) return false
        val dir = services.dirs.segments.apply { mkdirs() }
        val target = File(dir, "E0_N50.rd5")
        if (!target.exists()) Files.createSymbolicLink(target.toPath(), amsterdam.toPath())
        return true
    }
}
