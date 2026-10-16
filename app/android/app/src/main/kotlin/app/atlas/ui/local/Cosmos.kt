package app.atlas.ui.local

import android.content.Context
import app.atlas.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull

/** A city that lights up on the night side of the globe: [size] 1 is a metropolis, 3 a town. */
class City(val lon: Double, val lat: Double, val size: Int)

/** One polyline of the Milky Way (an arm or a dust lane), in the 412 x 915 frame of the board. */
class GalaxyPath(val xy: FloatArray, val colour: Int, val opacity: Float, val width: Float, val dx: Float, val dy: Float)

/** One star of the galaxy picture: position and radius in the board's frame, ARGB colour with the board's opacity applied. */
class Star(val x: Float, val y: Float, val r: Float, val argb: Int)

/** The Milky Way as the Globe board draws it. */
class GalaxyArt(val arms: List<GalaxyPath>, val lanes: List<GalaxyPath>, val stars: List<Star>, val hereX: Float, val hereY: Float)

/**
 * What the zoom ladder draws, taken from the Globe board (`local_globe.py`): Natural Earth's land (public domain) as
 * rings of longitude and latitude, the cities, the backdrop stars, and the galaxy.
 */
class CosmosData(val land: List<DoubleArray>, val cities: List<City>, val backdrop: List<Star>, val galaxy: GalaxyArt) {
    companion object {
        @Volatile
        private var loaded: CosmosData? = null

        fun of(context: Context): CosmosData = loaded ?: synchronized(this) {
            loaded ?: parse(context.resources.openRawResource(R.raw.local_globe).bufferedReader().use { it.readText() }).also { loaded = it }
        }

        fun parse(text: String): CosmosData {
            val root = Json.parseToJsonElement(text) as JsonObject
            fun arr(key: String) = root[key] as JsonArray
            fun nums(a: JsonArray) = DoubleArray(a.size) { (a[it] as JsonPrimitive).doubleOrNull ?: 0.0 }
            fun argb(hex: String, opacity: Float): Int {
                val rgb = hex.removePrefix("#").toLong(16).toInt() and 0xFFFFFF
                return ((opacity.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or rgb
            }
            val land = arr("land").map { nums(it as JsonArray) }
            val cities = arr("cities").map { val a = nums(it as JsonArray); City(a[0], a[1], a[2].toInt()) }
            val far = arr("far").map { val a = nums(it as JsonArray); Star(a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), argb("#FFFFFF", a[3].toFloat())) }
            fun paths(key: String) = arr(key).map { e ->
                val o = e as JsonObject
                GalaxyPath(
                    xy = FloatArray((o["p"] as JsonArray).size) { ((o["p"] as JsonArray)[it] as JsonPrimitive).floatOrNull ?: 0f },
                    colour = argb((o["c"] as JsonPrimitive).content, 1f),
                    opacity = (o["o"] as JsonPrimitive).floatOrNull ?: 1f,
                    width = (o["w"] as JsonPrimitive).floatOrNull ?: 1f,
                    dx = (o["dx"] as JsonPrimitive).floatOrNull ?: 0f,
                    dy = (o["dy"] as JsonPrimitive).floatOrNull ?: 0f,
                )
            }
            val stars = arr("stars").map { e ->
                val a = e as JsonArray
                Star(
                    (a[0] as JsonPrimitive).floatOrNull ?: 0f, (a[1] as JsonPrimitive).floatOrNull ?: 0f, (a[2] as JsonPrimitive).floatOrNull ?: 1f,
                    argb((a[3] as JsonPrimitive).contentOrNull ?: "#FFFFFF", (a[4] as JsonPrimitive).floatOrNull ?: 1f),
                )
            }
            val here = arr("here")
            return CosmosData(
                land, cities, far,
                GalaxyArt(paths("arms"), paths("lanes"), stars, (here[0] as JsonPrimitive).floatOrNull ?: 195.2f, (here[1] as JsonPrimitive).floatOrNull ?: 515.4f),
            )
        }
    }
}
