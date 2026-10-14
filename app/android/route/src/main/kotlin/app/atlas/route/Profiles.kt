package app.atlas.route

import app.atlas.model.Mode
import java.io.File
import java.io.IOException

/**
 * A BRouter profile Atlas writes from a template: the template file ([base], from `brouter/profiles2/` on the classpath),
 * global [defaults] (each replaces a one-token `assign` in the global context, or is added), text [replacements],
 * and expressions appended to the end of the global, way and node contexts.
 */
data class ProfileSpec(
    /** File name without ".brf", e.g. "atlas-bike". */
    val name: String,
    val base: String,
    val defaults: Map<String, String> = emptyMap(),
    val replacements: List<Pair<String, String>> = emptyList(),
    val globalAppend: String = "",
    val wayAppend: String = "",
    val nodeAppend: String = "",
    /** Whether the Atlas preference expressions ([Profiles.PREFERENCES_WAY]) are appended. */
    val preferences: Boolean = true,
)

/** The profile for every [Mode], and how to render one from its template. */
object Profiles {

    /** Defaults every Atlas profile gets: faster second pass (BRouter issue #967) and automatic turn instructions. */
    private val ENGINE_DEFAULTS = mapOf("pass2coefficient" to "1.0", "turnInstructionMode" to "1")

    /** Global switches read by [PREFERENCES_WAY]; requests set them through key values. */
    private val PREFERENCE_DEFAULTS = mapOf(
        "atlas_quiet" to "0", "atlas_green" to "0", "atlas_lit" to "0",
        "atlas_avoid_unpaved" to "0", "atlas_avoid_ferries" to "0",
    )

    /** Way-context expressions that scale the base profile's cost by Atlas's preferences (quiet, green, lit, unpaved, ferries). */
    const val PREFERENCES_WAY = """
# --- Atlas preferences (appended) ---
assign atlas_unpaved = or surface=unpaved|gravel|ground|dirt|grass|sand|earth|mud|clay|rock|stone|pebblestone
                          and highway=track|path|bridleway and surface= not tracktype=grade1
assign atlas_traffic = switch estimated_traffic_class= 0 switch estimated_traffic_class=1|2 0.1 switch estimated_traffic_class=3 0.3
                       switch estimated_traffic_class=4 0.6 switch estimated_traffic_class=5 1.0 1.5
assign atlas_nogreen = min ( switch estimated_forest_class= 1 switch estimated_forest_class=1 0.8 switch estimated_forest_class=2 0.6
                             switch estimated_forest_class=3 0.4 switch estimated_forest_class=4 0.2 switch estimated_forest_class=5 0.1 0 )
                           ( switch estimated_river_class= 1 switch estimated_river_class=1 0.8 switch estimated_river_class=2 0.6
                             switch estimated_river_class=3 0.4 switch estimated_river_class=4 0.2 switch estimated_river_class=5 0.1 0 )
assign atlas_unlit = switch lit=yes|sunset-sunrise|24/7|automatic 0 switch lit=no 1 0.3
assign atlas_factor = multiply ( add 1 multiply atlas_quiet atlas_traffic )
                      multiply ( add 1 multiply atlas_green atlas_nogreen )
                      multiply ( add 1 multiply atlas_lit atlas_unlit )
                               ( switch and atlas_avoid_unpaved atlas_unpaved 4 1 )
assign costfactor = if greater costfactor 9998 then costfactor
                    else if and atlas_avoid_ferries route=ferry then 10000
                    else min 9990 multiply costfactor atlas_factor
assign uphillcostfactor = if greater uphillcostfactor 9998 then uphillcostfactor else min 9990 multiply uphillcostfactor atlas_factor
assign downhillcostfactor = if greater downhillcostfactor 9998 then downhillcostfactor else min 9990 multiply downhillcostfactor atlas_factor
assign atlasspantags = or highway= or surface= or smoothness= or tracktype= or cycleway= or cycleway:left= or cycleway:right= or cycleway:both=
                       or bicycle= or maxspeed= or incline= or lit= or waterway= or route= or toll= or bridge= or tunnel= or junction=
                       or sidewalk= or embedded_rails= railway=
"""

    /** Node-context line that keeps the node tags Atlas reports (barriers, cameras, crossings, fords…). */
    const val NODE_TAGS = """
# --- Atlas node tags (appended) ---
assign atlasnodetags = or barrier= or highway= or railway= or crossing= or ford= or traffic_calming= waterway=
"""

    private val narrowBarriers = "barrier=cycle_barrier|stile|kissing_gate|turnstile|full-height_turnstile|motorcycle_barrier|horse_stile"

    val WALK = ProfileSpec("atlas-walk", "atlas-walk", preferences = false)
    val WHEELCHAIR = ProfileSpec(
        "atlas-wheelchair", "atlas-walk",
        defaults = mapOf("wheelchair" to "true", "avoid_steps" to "true", "maxSpeed" to "4.2"),
        globalAppend = """
assign uphillcost = 100
assign uphillcutoff = 1.5
assign downhillcost = 60
assign downhillcutoff = 2
assign uphillmaxslope = 6
assign uphillmaxslopecost = 1000
assign downhillmaxslope = 8
assign downhillmaxslopecost = 600
""",
        preferences = false,
    )
    val HIKE = ProfileSpec("atlas-hike", "hiking-mountain", defaults = mapOf("turnInstructionCatchingRange" to "20"))
    val BIKE = ProfileSpec("atlas-bike", "trekking")
    val EBIKE = ProfileSpec(
        "atlas-ebike", "trekking",
        // assisted to 25 km/h: hills matter much less, the bike is heavier
        defaults = mapOf("downhillcost" to "20", "uphillcutoff" to "3.0", "downhillcutoff" to "3.0", "totalMass" to "115", "bikerPower" to "250", "maxSpeed" to "25"),
    )
    val ROAD_BIKE = ProfileSpec("atlas-roadbike", "fastbike")
    val MTB = ProfileSpec("atlas-mtb", "mtb")
    val CARGO_BIKE = ProfileSpec(
        "atlas-cargobike", "trekking",
        defaults = mapOf("allow_steps" to "false", "totalMass" to "170", "bikerPower" to "200", "maxSpeed" to "25", "S_C_x" to "0.35"),
        nodeAppend = "\nassign initialcost = if $narrowBarriers then 1000000 else initialcost\n",
    )
    val MOPED = ProfileSpec("atlas-moped", "moped")
    val MOTORCYCLE = ProfileSpec(
        "atlas-motorcycle", "car-vario",
        defaults = mapOf("vmax" to "120", "totalweight" to "400", "f_roll" to "80", "f_air" to "0.35", "p_standby" to "100", "recup_efficiency" to "0"),
        replacements = listOf("motorcar=" to "motorcycle="),
    )
    val CAR = ProfileSpec("atlas-car", "car-vario", defaults = mapOf("vmax" to "130"))
    val CAMPER = ProfileSpec(
        "atlas-camper", "car-vario",
        defaults = mapOf(
            "vmax" to "100", "totalweight" to "3500", "f_roll" to "350", "f_air" to "1.6", "p_standby" to "400",
            "atlas_heavy" to "false", "atlas_low" to "false",
        ),
        wayAppend = """
# --- Atlas camper: big and slow on small roads; hgv rules when heavier than 3.5 t ---
assign costfactor = if greater costfactor 9998 then costfactor
                    else if and atlas_heavy hgv=no|agricultural|private then 10000
                    else min 9990 multiply costfactor
                         ( switch highway=living_street|track|road 4 switch highway=service 2 switch isresidentialorliving 1.5 1 )
""",
        nodeAppend = """
assign initialcost = if and barrier=height_restrictor not atlas_low then 1000000
                     else if and atlas_heavy hgv=no then 1000000
                     else initialcost
""",
    )
    val TRUCK = ProfileSpec("atlas-truck", "atlas-truck")
    val BOAT = ProfileSpec("atlas-boat", "atlas-boat", preferences = false)
    val SAIL = ProfileSpec("atlas-sail", "atlas-boat", defaults = mapOf("is_motorboat" to "false", "maxSpeed" to "8"), preferences = false)

    val all: List<ProfileSpec> = listOf(
        WALK, WHEELCHAIR, HIKE, BIKE, EBIKE, ROAD_BIKE, MTB, CARGO_BIKE, MOPED, MOTORCYCLE, CAR, CAMPER, TRUCK, BOAT, SAIL,
    )

    /** The profile for [mode]; null for transit, which is not routed offline. */
    fun forMode(mode: Mode): ProfileSpec? = when (mode) {
        Mode.WALK -> WALK
        Mode.HIKE -> HIKE
        Mode.WHEELCHAIR -> WHEELCHAIR
        Mode.BIKE -> BIKE
        Mode.EBIKE -> EBIKE
        Mode.ROAD_BIKE -> ROAD_BIKE
        Mode.MTB -> MTB
        Mode.CARGO_BIKE -> CARGO_BIKE
        Mode.MOPED -> MOPED
        Mode.MOTORCYCLE -> MOTORCYCLE
        Mode.CAR -> CAR
        Mode.CAMPER -> CAMPER
        Mode.TRUCK -> TRUCK
        Mode.BOAT -> BOAT
        Mode.SAIL -> SAIL
        Mode.TRANSIT -> null
    }

    /** The text of a template profile (or lookups.dat) bundled in the brouter module. */
    fun template(name: String): String {
        val path = "/brouter/profiles2/" + (if (name.contains('.')) name else "$name.brf")
        val stream = Profiles::class.java.getResourceAsStream(path) ?: throw IOException("profile template $path not on the classpath")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Renders [spec] from its template. Fails clearly when an override no longer matches the template. */
    fun render(spec: ProfileSpec, templateText: String = template(spec.base)): String {
        var text = templateText
        for ((from, to) in spec.replacements) {
            require(text.contains(from)) { "${spec.name}: '$from' not found in ${spec.base}" }
            text = text.replace(from, to)
        }
        val sections = splitContexts(text)
        val global = sections["global"] ?: throw IllegalArgumentException("${spec.base} has no global context")
        val defaults = LinkedHashMap<String, String>()
        defaults.putAll(ENGINE_DEFAULTS)
        if (spec.preferences) defaults.putAll(PREFERENCE_DEFAULTS)
        defaults.putAll(spec.defaults)
        var g = global
        for ((key, value) in defaults) g = setDefault(g, key, value, spec)
        if (spec.globalAppend.isNotBlank()) g = g.trimEnd() + "\n" + spec.globalAppend.trimEnd() + "\n"
        val way = (sections["way"] ?: "").let { w ->
            var out = w.trimEnd() + "\n"
            if (spec.preferences) out += PREFERENCES_WAY
            if (spec.wayAppend.isNotBlank()) out += spec.wayAppend.trimEnd() + "\n"
            out
        }
        val node = (sections["node"] ?: "").let { n ->
            var out = n.trimEnd() + "\n"
            if (spec.preferences) out += NODE_TAGS
            if (spec.nodeAppend.isNotBlank()) out += spec.nodeAppend.trimEnd() + "\n"
            out
        }
        val header = "# Generated by Atlas from ${spec.base}.brf (BRouter, MIT); do not edit.\n"
        return header + (sections["head"] ?: "") + g + "\n" + way + "\n" + node
    }

    /** Splits a profile into the text before the first context, and each context including its header line. */
    internal fun splitContexts(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val ctx = Regex("""(?m)^[ \t]*---context:(\w+).*$""")
        val matches = ctx.findAll(text).toList()
        out["head"] = if (matches.isEmpty()) text else text.substring(0, matches.first().range.first)
        for ((i, m) in matches.withIndex()) {
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            out[m.groupValues[1]] = text.substring(m.range.first, end)
        }
        return out
    }

    /**
     * Sets `assign [key] = [value]` in a global context: replaces the first assignment when its value is one token
     * (a number, true or false), otherwise adds the assignment right after the context header.
     */
    private fun setDefault(global: String, key: String, value: String, spec: ProfileSpec): String {
        val line = Regex("""(?m)^([ \t]*assign[ \t]+${Regex.escape(key)})([ \t]*=?[ \t]*)(\S+)([ \t]*(#.*)?)$""")
        val first = Regex("""(?m)^[ \t]*assign[ \t]+${Regex.escape(key)}\b""").find(global)
        if (first != null) {
            val m = line.find(global, first.range.first)
            require(m != null && m.range.first == first.range.first) {
                "${spec.name}: the first assignment of '$key' in ${spec.base} is not a single value, so it cannot be overridden"
            }
            return global.replaceRange(m.range, m.groupValues[1] + " = " + value + "   # Atlas")
        }
        val header = global.indexOf('\n').let { if (it < 0) global.length else it + 1 }
        return global.substring(0, header) + "assign $key = $value   # Atlas\n" + global.substring(header)
    }
}

/**
 * The profiles directory BRouter reads: `lookups.dat` and every Atlas profile, written from the templates.
 * Files are rewritten only when their content changes, so BRouter's profile cache stays warm.
 */
class ProfileStore(val dir: File, private val lookups: File? = null) {

    @Volatile private var installed: Map<String, File>? = null

    /** Writes `lookups.dat` and all profiles when needed and returns the profile files by name. */
    @Synchronized
    fun install(): Map<String, File> {
        installed?.let { return it }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val lookupsText = lookups?.readBytes() ?: Profiles.template("lookups.dat").toByteArray(Charsets.UTF_8)
        writeIfChanged(File(dir, "lookups.dat"), lookupsText)
        val cache = HashMap<String, String>()
        val out = LinkedHashMap<String, File>()
        for (spec in Profiles.all) {
            val text = Profiles.render(spec, cache.getOrPut(spec.base) { Profiles.template(spec.base) })
            val f = File(dir, spec.name + ".brf")
            writeIfChanged(f, text.toByteArray(Charsets.UTF_8))
            out[spec.name] = f
        }
        installed = out
        return out
    }

    /** The profile file for [spec], installing the profiles first if needed (again if the folder was cleared meanwhile). */
    fun file(spec: ProfileSpec): File {
        val f = install()[spec.name] ?: throw IllegalArgumentException("unknown profile ${spec.name}")
        if (f.isFile && File(dir, "lookups.dat").isFile) return f
        synchronized(this) { installed = null }
        return install().getValue(spec.name)
    }

    private fun writeIfChanged(f: File, bytes: ByteArray) {
        if (f.isFile && f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)) return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("cannot write $f")
        }
    }
}
