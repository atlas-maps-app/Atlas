package app.atlas.ui.go

import app.atlas.model.Mode
import app.atlas.model.RouteRequest
import app.atlas.route.RouteResult
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes the demo routes (`res/raw/go_demo_*.json`) from the real E0_N50 segment. Runs only when asked
 * (`ATLAS_GENERATE_DEMO=1` in the environment) and the segment is on this machine.
 */
class DemoRoutesGenerator {
    @Test
    fun generate() {
        assumeTrue(System.getenv("ATLAS_GENERATE_DEMO") == "1" && GoFixtures.hasSegment)
        val router = GoFixtures.router!!
        val out = File(System.getProperty("atlas.repo"), "app/android/app/src/main/res/raw")
        for ((mode, name) in listOf(Mode.WALK to "walk", Mode.BIKE to "bike", Mode.CAR to "car", Mode.TRUCK to "truck", Mode.BOAT to "boat")) {
            val req = RouteRequest(mode, listOf(GoFixtures.WESTERKERK, GoFixtures.destinationFor(mode)), vehicle = GoStore.defaultVehicle(mode))
            val r = runBlocking { if (mode.water) app.atlas.route.WaterRouter(router).route(req) else router.route(req) }
            check(r is RouteResult.Found) { "$mode: $r" }
            val route = r.best
            File(out, "go_demo_$name.json").writeText(GoJson.route(route).toString())
            println("DEMO $mode ${route.points.size} points ${route.distance.toInt()} m ${route.steps.size} steps ${route.hazards.map { it.kind }.toSet()} spans ${route.spans.map { it.key }.toSet()}")
        }
    }
}
