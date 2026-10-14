package app.atlas.ui.go

import android.content.Context
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Route

/**
 * Real routes for demo mode on a phone without routing data: computed once by BRouter from OpenStreetMap (ODbL) with
 * the E0_N50 segment, from the Westerkerk to Amsterdam Centraal (walk, bike, car), to Haarlem (truck) and to the IJ
 * (boat), and kept in `res/raw/go_demo_*.json`. They are used only when the simulator drives (CI and "simulate") and
 * only for those very trips; anywhere else a phone without data is offered the download.
 */
object DemoRoutes {
    private val files = mapOf(
        Mode.WALK to R.raw.go_demo_walk,
        Mode.BIKE to R.raw.go_demo_bike,
        Mode.CAR to R.raw.go_demo_car,
        Mode.TRUCK to R.raw.go_demo_truck,
        Mode.BOAT to R.raw.go_demo_boat,
    )

    /** How far the asked ends may be from the demo route's own. */
    const val MATCH_M = 250.0

    /** The demo route for [mode] from [from] to [to], or null when there is none for that trip. */
    fun find(context: Context, mode: Mode, from: LatLon, to: LatLon): Route? {
        val key = when {
            mode.water -> Mode.BOAT
            mode == Mode.TRUCK -> Mode.TRUCK
            mode.cycling -> Mode.BIKE
            mode.motor -> Mode.CAR
            else -> Mode.WALK
        }
        val res = files[key] ?: return null
        val text = runCatching { context.resources.openRawResource(res).bufferedReader().use { it.readText() } }.getOrNull() ?: return null
        val route = GoJson.route(GoJson.parse(text)) ?: return null
        if (Geo.distance(route.points.first(), from) > MATCH_M || Geo.distance(route.points.last(), to) > MATCH_M) return null
        return if (route.mode == mode) route else route.copy(mode = mode)
    }
}
