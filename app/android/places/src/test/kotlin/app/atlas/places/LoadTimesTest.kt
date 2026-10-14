package app.atlas.places

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

/**
 * Cold load times of the bundled data: each measurement runs in a fresh class loader, so nothing is cached and the
 * classes load anew, as on an app's first use. Prints the times; holds them to generous JVM bounds.
 */
class LoadTimesTest {
    private val classpath = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }.toTypedArray()

    /** Milliseconds for [call] on the object [cls] (by its INSTANCE) in a fresh loader: the best of three runs. */
    private fun cold(cls: String, call: (Any, Class<*>) -> Unit): Double = (1..3).minOf {
        URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()).use { loader ->
            val c = Class.forName("app.atlas.places.$cls", true, loader)
            val instance = c.getField("INSTANCE").get(null)
            val t0 = System.nanoTime()
            call(instance, c)
            (System.nanoTime() - t0) / 1e6
        }
    }

    @Test
    fun coldLoads() {
        val times = linkedMapOf(
            "presets (1,681)" to cold("Presets") { o, c -> c.getMethod("getAll").invoke(o) },
            "preset match" to cold("Presets") { o, c ->
                c.getMethod("match", Map::class.java, Class.forName("app.atlas.places.Geometry", true, c.classLoader), String::class.java)
                    .invoke(o, mapOf("amenity" to "cafe"), c.classLoader.loadClass("app.atlas.places.Geometry").enumConstants[0], null)
            },
            "preset search, en" to cold("Presets") { o, c ->
                c.getMethod("search", String::class.java, String::class.java, Int::class.javaPrimitiveType, Class.forName("app.atlas.places.Geometry", true, c.classLoader), String::class.java)
                    .invoke(o, "cafe", "en", 20, null, null)
            },
            "categories" to cold("Categories") { o, c -> c.getMethod("all").invoke(o) },
            "brands (14,000)" to cold("Brands") { o, c -> c.getMethod("byId", String::class.java).invoke(o, "x") },
            "map symbols, first icon" to cold("Symbols") { o, c -> c.getMethod("icon", String::class.java).invoke(o, "maki-cafe") },
            "UI symbols, first icon" to cold("Symbols") { o, c -> c.getMethod("icon", String::class.java).invoke(o, "ui-search") },
            "logos, first icon" to cold("Symbols") { o, c -> c.getMethod("icon", String::class.java).invoke(o, "logo-albertheijn") },
            "cuisine" to cold("Cuisine") { o, c -> c.getMethod("symbol", String::class.java).invoke(o, "pizza") },
        )
        println("cold load times (JVM, best of 3): " + times.entries.joinToString { "${it.key} ${"%.0f".format(it.value)} ms" })
        for ((k, v) in times) assertTrue("$k took $v ms", v < 3000)
    }
}
