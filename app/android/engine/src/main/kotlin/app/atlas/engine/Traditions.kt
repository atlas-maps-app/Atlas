package app.atlas.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

/**
 * The building traditions Atlas knows: the drawn profiles shipped as resources, the catalogue of the rest, and the
 * choice of one for any place on Earth (a few regions by outline, else a palette for the climate band).
 */
object Traditions {
    /** Profiles with a full JSON description. */
    val DRAWN = listOf("nl_amsterdam", "fr_paris", "it_tuscan", "no_timber", "gb_terrace")

    private val loaded = HashMap<String, Tradition>()

    /** One catalogue entry: the look of a tradition that may have no profile yet. */
    class Entry(val id: String, val name: String, val status: String, val roofShape: String, val walls: List<Int>, val roofs: List<Int>, val tags: List<String>)

    val catalogue: List<Entry> by lazy {
        val text = resource("catalogue.json") ?: return@lazy emptyList()
        val root = Json.parseToJsonElement(text).jsonObject
        (root["traditions"] as? JsonArray)?.map { e ->
            val o = e.jsonObject
            Entry(
                id = o.str("id")!!, name = o.str("name") ?: o.str("id")!!, status = o.str("status") ?: "planned",
                roofShape = o.str("roof_shape") ?: "flat",
                walls = o.strs("walls")?.mapNotNull { Colours.parseOrNull(it) } ?: emptyList(),
                roofs = o.strs("roofs")?.mapNotNull { Colours.parseOrNull(it) } ?: emptyList(),
                tags = o.strs("tags") ?: emptyList(),
            )
        } ?: emptyList()
    }

    /** The tradition [id]: its profile, or one made from its catalogue entry, or null when unknown. */
    fun get(id: String): Tradition? = synchronized(loaded) {
        loaded[id]?.let { return it }
        val t = resource("$id.json")?.let { Tradition.parse(it) } ?: catalogue.firstOrNull { it.id == id }?.let { synthesise(it) }
        if (t != null) loaded[id] = t
        t
    }

    /** The tradition of the place at [lat], [lon]. */
    fun forLocation(lat: Double, lon: Double): Tradition = get(idForLocation(lat, lon)) ?: get("nl_amsterdam")!!

    /** The id of the tradition chosen for a place: by region where Atlas draws one, else by climate band. */
    fun idForLocation(lat: Double, lon: Double): String = when {
        inside(NETHERLANDS, lon, lat) -> "nl_amsterdam"
        lat in 48.12..49.25 && lon in 1.45..3.56 -> "fr_paris"
        inside(TUSCANY, lon, lat) -> "it_tuscan"
        inside(NORWAY, lon, lat) -> "no_timber"
        inside(GREAT_BRITAIN, lon, lat) || inside(NORTHERN_IRELAND, lon, lat) -> "gb_terrace"
        abs(lat) < 23.5 -> "sea_shophouse"
        abs(lat) < 35.0 -> "levant_stone"
        abs(lat) < 55.0 -> "modern_global"
        else -> "no_timber"
    }

    /**
     * A minimal profile from a catalogue entry: its walls (materials from its tags), its roofs, and the roof shape its
     * look names, with plain storeys. Kit parts it has not got are simply not drawn.
     */
    fun synthesise(e: Entry): Tradition {
        val wallMaterials = e.tags.filter { it.startsWith("building:material=") }.map { it.substringAfter('=') }.ifEmpty { listOf("plaster") }
        val roofMaterial = e.tags.firstOrNull { it.startsWith("roof:material=") }?.substringAfter('=')
        val walls = e.walls.mapIndexed { i, c -> WallOption(wallMaterials[i % wallMaterials.size], c, 1.0) }
            .ifEmpty { listOf(WallOption("plaster", Colours.parse("#E6DFD1"), 1.0)) }
        val (shape, pitch) = when (e.roofShape) {
            "steep" -> "gabled" to 1.0
            "gabled" -> "gabled" to 0.6
            "hipped" -> "hipped" to 0.4
            "curved" -> "hipped" to 0.45
            "mansard" -> "mansard" to null
            else -> "flat" to null
        }
        val roof = RoofEntry(listOf(shape to 1.0), roofMaterial ?: if (shape == "flat") "tar_paper" else "roof_tiles",
            e.roofs.ifEmpty { listOf(Colours.parse("#7E7A74")) }, pitch, false)
        val modernRoof = RoofEntry(listOf("flat" to 1.0), "tar_paper", listOf(Colours.parse("#7E7A74"), Colours.parse("#8C8882")), null, false)
        return Tradition(
            id = e.id, name = e.name, floorHeight = 3.0, levels = mapOf("default" to 3), heights = emptyMap(), modernFrom = 1950,
            walls = mapOf("default" to Era(listOf("default" to walls))),
            roofs = mapOf("default" to Era(listOf("default" to roof)), "industrial" to Era(listOf("default" to modernRoof))),
            rules = emptyList(), windows = null, eaves = 0.0, chimneys = null, corniceBefore = null, hoistBefore = null,
            crenellatedTowers = false,
        )
    }

    private fun resource(name: String): String? =
        Traditions::class.java.getResourceAsStream("/app/atlas/engine/traditions/$name")?.use { it.readBytes().decodeToString() }

    /** Even-odd point in polygon over (lon, lat) pairs. */
    private fun inside(poly: DoubleArray, x: Double, y: Double): Boolean {
        var c = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]
            val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    // Rough outlines (lon, lat), a few kilometres out at worst: enough to pick a building tradition.
    private val NETHERLANDS = doubleArrayOf(
        3.36, 51.37, 4.25, 51.37, 4.5, 51.48, 5.0, 51.43, 5.85, 51.15, 5.65, 50.75, 6.0, 50.75, 6.22, 51.36,
        6.83, 51.97, 7.07, 52.23, 7.06, 52.65, 7.21, 53.24, 6.9, 53.6, 4.6, 53.5, 3.36, 51.9,
    )
    private val TUSCANY = doubleArrayOf(
        9.69, 44.24, 10.3, 44.47, 11.0, 44.1, 11.75, 44.12, 12.37, 43.71, 11.95, 43.16, 11.9, 42.75, 11.45, 42.38,
        10.9, 42.45, 10.5, 42.95, 10.2, 43.6, 9.69, 44.0,
    )
    private val NORWAY = doubleArrayOf(
        4.5, 58.0, 7.0, 57.9, 10.6, 58.9, 11.45, 58.9, 12.3, 60.0, 12.6, 61.0, 12.1, 62.0, 12.2, 63.0, 14.2, 64.2,
        14.5, 65.5, 16.0, 67.0, 17.9, 68.3, 20.3, 68.9, 21.8, 69.2, 25.0, 68.6, 26.5, 69.9, 28.5, 69.0, 31.2, 69.9,
        30.0, 71.3, 20.0, 70.5, 14.0, 68.5, 12.0, 66.5, 9.5, 64.0, 4.5, 61.5,
    )
    private val GREAT_BRITAIN = doubleArrayOf(
        -5.8, 49.9, 1.8, 51.0, 1.8, 52.9, 0.0, 53.6, -1.5, 55.7, -1.7, 57.7, -1.7, 60.9, -3.5, 59.0, -5.2, 58.6,
        -6.3, 58.4, -7.7, 57.2, -6.3, 56.0, -5.6, 55.2, -5.0, 54.7, -3.4, 54.7, -3.0, 53.4, -4.8, 53.4, -4.7, 52.8,
        -5.3, 51.8, -4.2, 51.5, -5.8, 50.0,
    )
    private val NORTHERN_IRELAND = doubleArrayOf(-8.2, 54.1, -5.4, 54.1, -5.4, 55.3, -7.3, 55.4, -8.2, 54.6)
}
