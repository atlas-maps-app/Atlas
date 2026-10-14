package app.atlas.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A building tradition: how a place's unmapped buildings look (walls, roofs, heights, the kit of details), read from a
 * profile in `traditions/<id>.json`. Mapped values always win; the tradition only fills what OSM leaves out.
 */
class Tradition(
    val id: String,
    val name: String,
    val floorHeight: Double,
    /** Usual storeys by `building=*` value or kind, with "default". */
    val levels: Map<String, Int>,
    /** Typical total heights by kind (towers only for small footprints). */
    val heights: Map<String, Double>,
    val modernFrom: Int,
    val walls: Map<String, Era<List<WallOption>>>,
    val roofs: Map<String, Era<RoofEntry>>,
    val rules: List<RoofRule>,
    val windows: WindowStyle?,
    val eaves: Double,
    val chimneys: ChimneyStyle?,
    /** Cornices on buildings older than this year (Int.MAX_VALUE: always; null: never). */
    val corniceBefore: Int?,
    /** Hoist beams under gables older than this year. */
    val hoistBefore: Int?,
    val crenellatedTowers: Boolean,
    /** True when old narrow fronts get gables: a canal-town or terrace profile where age decides the roof. */
    val oldTownRule: Boolean = rules.any { it.whenYearTo != null && it.whenNarrow != null },
) {
    /** The walls entry for a building: tried keys `modern` (when modern), its `building=*` value, its kind, `default`. */
    fun wallsFor(building: String, kind: String, year: Int?): List<WallOption>? = entry(walls, building, kind, year)

    fun roofFor(building: String, kind: String, year: Int?): RoofEntry? = entry(roofs, building, kind, year)

    fun levelsFor(building: String, kind: String): Int = levels[building] ?: levels[kind] ?: levels["default"] ?: 3

    private fun <T> entry(map: Map<String, Era<T>>, building: String, kind: String, year: Int?): T? {
        val modern = year != null && year >= modernFrom
        val keys = if (modern) listOf("modern", building, kind, "default") else listOf(building, kind, "default")
        for (k in keys) {
            val e = map[k] ?: continue
            return e.at(year) ?: continue
        }
        return null
    }

    override fun toString() = "Tradition($id)"

    companion object {
        /** Parses a profile. */
        fun parse(text: String): Tradition = fromJson(Json.parseToJsonElement(text).jsonObject)

        internal fun fromJson(o: JsonObject): Tradition {
            val floor = o.num("floor_h") ?: 3.0
            return Tradition(
                id = o.str("id") ?: error("tradition without an id"),
                name = o.str("name") ?: o.str("id")!!,
                floorHeight = floor,
                levels = (o["levels"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.intOrNull ?: 3 } ?: mapOf("default" to 3),
                heights = (o["heights"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 } ?: emptyMap(),
                modernFrom = o.num("modern_from")?.toInt() ?: 1950,
                walls = (o["walls"] as? JsonObject)?.mapValues { (_, v) -> Era.parse(v) { parseWalls(it) } } ?: emptyMap(),
                roofs = (o["roof"] as? JsonObject)?.mapValues { (_, v) -> Era.parse(v) { parseRoof(it) } } ?: emptyMap(),
                rules = (o["rules"] as? JsonArray)?.map { parseRule(it.jsonObject) } ?: emptyList(),
                windows = (o["windows"] as? JsonObject)?.let { w ->
                    WindowStyle(
                        bay = w.num("bay") ?: 2.4, width = w.num("w") ?: 0.4, height = w.num("h") ?: 0.6, top = w.num("top") ?: 0.18,
                        frame = Colours.parseOrNull(w.str("frame")) ?: Colours.parse("#F2EEE6"),
                        kinds = w.strs("kinds")?.toSet(), materials = w.strs("materials")?.toSet(),
                    )
                },
                eaves = o.num("eaves") ?: 0.0,
                chimneys = (o["chimneys"] as? JsonObject)?.let { c ->
                    ChimneyStyle(
                        share = c.num("share") ?: 0.0, kind = c.str("kind") ?: "plain",
                        colour = Colours.parseOrNull(c.str("colour")) ?: Colours.parse("#6E4A3E"),
                        cap = Colours.parseOrNull(c.str("cap")), pots = Colours.parseOrNull(c.str("pots")),
                    )
                },
                corniceBefore = when (val c = o["cornice"]) {
                    is JsonObject -> c.num("before")?.toInt()
                    is JsonPrimitive -> if (c.booleanOrNull == true) Int.MAX_VALUE else null
                    else -> null
                },
                hoistBefore = (o["hoist"] as? JsonObject)?.num("before")?.toInt(),
                crenellatedTowers = (o["towers_crenellated"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        private fun parseWalls(e: JsonElement): List<WallOption> = e.jsonArray.map { opt ->
            val a = opt.jsonArray
            WallOption(a[0].jsonPrimitive.content, Colours.parse(a[1].jsonPrimitive.content), a.getOrNull(2)?.jsonPrimitive?.doubleOrNull ?: 1.0)
        }

        private fun parseRoof(e: JsonElement): RoofEntry {
            val o = e.jsonObject
            return RoofEntry(
                shapes = (o["shapes"] as? JsonArray)?.map { s -> s.jsonArray.let { it[0].jsonPrimitive.content to (it.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: 1.0) } }
                    ?: listOf("flat" to 1.0),
                material = o.str("material"),
                colours = o.strs("colours")?.mapNotNull { Colours.parseOrNull(it) } ?: emptyList(),
                pitch = o.num("pitch"),
                terraceAcross = (o["terrace_across"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        private fun parseRule(o: JsonObject): RoofRule {
            val w = o["when"]?.jsonObject ?: JsonObject(emptyMap())
            val r = o["roof"]?.jsonObject ?: JsonObject(emptyMap())
            val year = w["year"] as? JsonObject
            return RoofRule(
                whenKinds = w.strs("kind")?.toSet(),
                whenBuildings = w.strs("building")?.toSet(),
                whenMaterials = w.strs("material")?.toSet(),
                whenYearFrom = year?.num("from")?.toInt(),
                whenYearTo = year?.num("to")?.toInt(),
                whenYearUnknown = year?.let { (it["unknown"] as? JsonPrimitive)?.booleanOrNull ?: false },
                whenLevels = (w["levels"] as? JsonArray)?.let { it[0].jsonPrimitive.intOrNull!!..it[1].jsonPrimitive.intOrNull!! },
                whenArea = (w["area"] as? JsonArray)?.let { it[0].jsonPrimitive.doubleOrNull!!..it[1].jsonPrimitive.doubleOrNull!! },
                whenNarrow = w.num("narrow"),
                whenMinHeight = w.num("min_h"),
                shape = r.str("shape") ?: "gabled",
                orient = r.str("orient"),
                pitch = r.num("pitch"),
                roofHeightRange = (r["roof_h_range"] as? JsonArray)?.let { it[0].jsonPrimitive.doubleOrNull!!..it[1].jsonPrimitive.doubleOrNull!! },
                roofHeight = r.num("roof_h"),
                material = r.str("material"),
                colours = r.strs("colours")?.mapNotNull { Colours.parseOrNull(it) },
            )
        }

    }
}

/** A value that may differ by a building's age: cases `before:Y`, `after:Y` and `default`, tried in order. */
class Era<T>(private val cases: List<Pair<String, T>>) {
    fun at(year: Int?): T? {
        for ((k, v) in cases) {
            when {
                k.startsWith("before:") -> if (year != null && year < k.substring(7).toInt()) return v
                k.startsWith("after:") -> if (year != null && year >= k.substring(6).toInt()) return v
                else -> return v
            }
        }
        return null
    }

    companion object {
        fun <T> parse(e: JsonElement, value: (JsonElement) -> T): Era<T> {
            if (e is JsonObject && e.keys.any { it.startsWith("before:") || it.startsWith("after:") || it == "default" } &&
                "shapes" !in e && "material" !in e
            ) {
                return Era(e.entries.filter { it.value !is JsonNull }.map { it.key to value(it.value) })
            }
            return Era(listOf("default" to value(e)))
        }
    }
}

/** One weighted choice of wall: its material word and colour. */
class WallOption(val material: String, val colour: Int, val weight: Double)

/** A roof choice: weighted shapes, material and colours, the pitch (rise over half-depth). */
class RoofEntry(
    val shapes: List<Pair<String, Double>>,
    val material: String?,
    val colours: List<Int>,
    val pitch: Double?,
    val terraceAcross: Boolean,
)

/** "When a building looks like this, its unmapped roof is that": the first rule that fits wins. */
class RoofRule(
    val whenKinds: Set<String>?,
    val whenBuildings: Set<String>?,
    val whenMaterials: Set<String>?,
    val whenYearFrom: Int?,
    val whenYearTo: Int?,
    /** Whether an unknown year fits (null: the year is not tested when unknown). */
    val whenYearUnknown: Boolean?,
    val whenLevels: IntRange?,
    val whenArea: ClosedFloatingPointRange<Double>?,
    /** The footprint's width across its long side must be below this. */
    val whenNarrow: Double?,
    val whenMinHeight: Double?,
    val shape: String,
    val orient: String?,
    val pitch: Double?,
    val roofHeightRange: ClosedFloatingPointRange<Double>?,
    val roofHeight: Double?,
    val material: String?,
    val colours: List<Int>?,
) {
    fun fits(kind: String, building: String, material: String, year: Int?, levels: Int, area: Double, short: Double, height: Double): Boolean {
        if (whenKinds != null && kind !in whenKinds) return false
        if (whenBuildings != null && building !in whenBuildings) return false
        if (whenMaterials != null && material !in whenMaterials) return false
        if (year == null) {
            if (whenYearUnknown == false) return false
        } else {
            if (whenYearFrom != null && year < whenYearFrom) return false
            if (whenYearTo != null && year >= whenYearTo) return false
        }
        if (whenLevels != null && levels !in whenLevels) return false
        if (whenArea != null && area !in whenArea) return false
        if (whenNarrow != null && short >= whenNarrow) return false
        if (whenMinHeight != null && height <= whenMinHeight) return false
        return true
    }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.strs(k: String): List<String>? = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content }

class WindowStyle(val bay: Double, val width: Double, val height: Double, val top: Double, val frame: Int, val kinds: Set<String>?, val materials: Set<String>?)

class ChimneyStyle(val share: Double, val kind: String, val colour: Int, val cap: Int?, val pots: Int?)
