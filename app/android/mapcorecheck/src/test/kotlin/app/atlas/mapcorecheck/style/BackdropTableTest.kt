package app.atlas.mapcorecheck.style

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Globe board's backdrop stars as the core draws them (ENGINE.md step 5, "The globe"): the table of the world's
 * shaders (`shaders/shared/backdrop.slang`, written by `ci/backdrop.py`) is the app's own data (`local_globe.json`'s
 * "far": each star's place in the board's 412 x 915 frame, its radius and its opacity), star for star, and the world
 * pass draws as many (`passes::World::star_count`). A change to the board's stars is taken by running the script.
 */
class BackdropTableTest {
    @Test
    fun `the core's backdrop stars are the board's`() {
        val android = File(System.getProperty("atlas.repo"), "app/android")
        val far = (Json.parseToJsonElement(File(android, "app/src/main/res/raw/local_globe.json").readText()) as JsonObject)["far"] as JsonArray
        val board = far.map { e -> (e as JsonArray).map { (it as JsonPrimitive).doubleOrNull ?: Double.NaN } }
        val slang = File(android, "mapcore/cpp/shaders/shared/backdrop.slang").readText()
        val row = Regex("""float4\(([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f, ([-0-9.]+)f\)""")
        val table = row.findAll(slang).map { m -> m.groupValues.drop(1).map(String::toDouble) }.toList()
        assertTrue("the board has stars", board.size > 100)
        assertEquals("the table's stars", board.size, table.size)
        for ((i, star) in board.withIndex()) {
            assertEquals("star $i", 4, star.size)
            for (k in 0 until 4) assertEquals("star $i's value $k", star[k], table[i][k], 1e-9)
        }
        val count = Regex("""BACKDROP_STARS = (\d+)u;""").find(slang)?.groupValues?.get(1)?.toInt()
        assertEquals("the table's count", board.size, count)
        assertTrue("the table's length", slang.contains("backdrop_stars[${board.size}]"))
        val pass = File(android, "mapcore/cpp/passes/World.hpp").readText()
        val drawn = Regex("""star_count = (\d+);""").find(pass)?.groupValues?.get(1)?.toInt()
        assertEquals("the stars the world pass draws", board.size, drawn)
    }
}
