package app.atlas.guide

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * One step as osrm-text-instructions reads it: the fields of an OSRM v5 route step that shape the sentence.
 * [lanes] holds each lane's validity, left to right.
 */
data class OsrmStep(
    val type: String,
    val modifier: String? = null,
    val name: String? = null,
    val ref: String? = null,
    val destinations: String? = null,
    val exits: String? = null,
    val junctionName: String? = null,
    val rotaryName: String? = null,
    val exit: Int? = null,
    val bearingAfter: Double? = null,
    val mode: String? = null,
    val drivingSide: String? = null,
    val lanes: List<Boolean>? = null,
)

/** Options of osrm-text-instructions' `compile`: the leg for "your 2nd destination", road classes and a waypoint name. */
data class OsrmOptions(
    val legIndex: Int? = null,
    val legCount: Int? = null,
    val classes: List<String> = emptyList(),
    val waypointName: String? = null,
)

/** A compiled instruction and whether its template began with a token (a name) rather than a word of the language. */
data class OsrmText(val text: String, val startsWithToken: Boolean)

/**
 * A Kotlin port of osrm-text-instructions' `index.js` (v5 templates, BSD-2-Clause, © 2016 Project OSRM contributors),
 * reading the vendored `translations/<code>.json` and `grammar/<code>.json`. It produces the same strings as the
 * JavaScript library; the library's own fixtures are its test.
 */
class OsrmInstructions private constructor(
    /** The osrm-text-instructions language code, e.g. "en", "pt-BR", "zh-Hans". */
    val code: String,
    private val root: JsonObject,
    private val grammar: JsonObject?,
) {
    private val v5: JsonObject = root.obj("v5") ?: error("$code: no v5 templates")
    private val locale: Locale = Locale.forLanguageTag(code)

    /** Whether the language starts sentences with a capital letter (false for Japanese and Chinese). */
    val capitalizeFirst: Boolean = root.obj("meta")?.get("capitalizeFirstLetter")?.let { (it as? JsonPrimitive)?.content == "true" } ?: false

    private val grammarRules: Map<String, List<JsRule>> = grammar?.let { g ->
        val flags = g.obj("meta")?.str("regExpFlags").orEmpty()
        val out = HashMap<String, List<JsRule>>()
        g.obj("v5")?.forEach { (name, rules) ->
            out[name] = rules.jsonArray.map { r ->
                val pair = r.jsonArray
                JsRule.of(pair[0].jsonPrimitive.content, pair[1].jsonPrimitive.content, flags)
            }
        }
        out
    } ?: emptyMap()

    /** The phrase template for [key] of `v5.phrase`, e.g. "one in distance", or null. */
    fun phrase(key: String): String? = v5.obj("phrase")?.str(key)

    /** A string under `v5` by path, e.g. ("arrive", "left", "upcoming"); null when absent. */
    fun template(vararg path: String): String? {
        var o: JsonElement = v5
        for (p in path) o = (o as? JsonObject)?.get(p) ?: return null
        return (o as? JsonPrimitive)?.content
    }

    /** The translated modifier word ("left", "sharp right", "U-turn"), or null. */
    fun modifierWord(modifier: String): String? = v5.obj("constants")?.obj("modifier")?.str(modifier)

    /** The translated compass direction for a key such as "north", or null. */
    fun directionWord(key: String): String? = v5.obj("constants")?.obj("direction")?.str(key)

    fun ordinalize(number: Int): String = v5.obj("constants")?.obj("ordinalize")?.str(number.toString()).orEmpty()

    /** osrm-text-instructions' `directionFromDegree`: the compass word for a bearing, "" when there is none. */
    fun directionFromDegree(degree: Double?): String {
        if (degree == null || degree.isNaN()) return ""
        val key = when {
            degree in 0.0..20.0 -> "north"
            degree > 20 && degree < 70 -> "northeast"
            degree in 70.0..110.0 -> "east"
            degree > 110 && degree < 160 -> "southeast"
            degree in 160.0..200.0 -> "south"
            degree > 200 && degree < 250 -> "southwest"
            degree in 250.0..290.0 -> "west"
            degree > 290 && degree < 340 -> "northwest"
            degree in 340.0..360.0 -> "north"
            else -> throw IllegalArgumentException("Degree $degree invalid")
        }
        return directionWord(key).orEmpty()
    }

    fun capitalizeFirstLetter(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase(locale) + s.substring(1)

    /** osrm-text-instructions' `getWayName`: name, ref or "name (ref)", the motorway rule included. */
    fun wayName(step: OsrmStep, options: OsrmOptions = OsrmOptions()): String {
        var name = step.name.orEmpty()
        val ref = step.ref.orEmpty().split(';')[0]
        if (name == step.ref) name = ""
        name = replaceFirstLiteral(name, " (" + (step.ref ?: "undefined") + ")", "")
        val wayMotorway = "motorway" in options.classes
        return when {
            name.isNotEmpty() && ref.isNotEmpty() && name != ref && !wayMotorway -> {
                val phrase = phrase("name and ref") ?: forLanguage("en").phrase("name and ref")!!
                tokenize(phrase, mapOf("name" to name, "ref" to ref))
            }
            name.isNotEmpty() && ref.isNotEmpty() && wayMotorway && ref.any { it in '0'..'9' } -> ref
            name.isEmpty() && ref.isNotEmpty() -> ref
            else -> name
        }
    }

    /** osrm-text-instructions' `laneConfig`: lanes contracted to runs of "o" (valid) and "x" (not). */
    fun laneConfig(lanes: List<Boolean>): String {
        val sb = StringBuilder()
        var current: Boolean? = null
        for (valid in lanes) {
            if (current == null || current != valid) {
                sb.append(if (valid) 'o' else 'x')
                current = valid
            }
        }
        return sb.toString()
    }

    /** The lane sentence for a lane pattern ("Keep left"), or null when the language has none for it. */
    fun laneInstruction(lanes: List<Boolean>): String? = v5.obj("constants")?.obj("lanes")?.str(laneConfig(lanes))

    /** osrm-text-instructions' `compile`. */
    fun compile(step: OsrmStep, options: OsrmOptions = OsrmOptions()): String = compileText(step, options).text

    /** As [compile], also saying whether the template began with a token; [capitalize] false leaves the first letter as written. */
    fun compileText(step: OsrmStep, options: OsrmOptions = OsrmOptions(), capitalize: Boolean = capitalizeFirst): OsrmText {
        var type = step.type
        val modifier = step.modifier
        if (type != "depart" && type != "arrive" && modifier == null) throw IllegalArgumentException("Missing step maneuver modifier")
        if (v5[type] !is JsonObject) type = "turn"

        val modes = v5.obj("modes")
        var instructionObject: JsonObject? = step.mode?.let { modes?.obj(it) }
        if (instructionObject == null) {
            val omitSide = type == "off ramp" && step.drivingSide != null && modifier != null && modifier.contains(step.drivingSide)
            val byType = v5.obj(type)!!
            instructionObject = (if (modifier != null && !omitSide) byType.obj(modifier) else null) ?: byType.obj("default")
        }
        var laneInstruction: String? = null
        when (type) {
            "use lane" -> {
                laneInstruction = step.lanes?.let { v5.obj("constants")?.obj("lanes")?.str(laneConfig(it)) }
                if (laneInstruction == null) instructionObject = v5.obj("use lane")?.obj("no_lanes")
            }
            "rotary", "roundabout" -> {
                val io = instructionObject!!
                val exitGiven = step.exit != null && step.exit != 0
                instructionObject = when {
                    !step.rotaryName.isNullOrEmpty() && exitGiven && io.obj("name_exit") != null -> io.obj("name_exit")
                    !step.rotaryName.isNullOrEmpty() && io.obj("name") != null -> io.obj("name")
                    exitGiven && io.obj("exit") != null -> io.obj("exit")
                    else -> io.obj("default")
                }
            }
        }
        val io = instructionObject ?: error("$code: no template for $type")
        val wayName = wayName(step, options)
        val destinations = step.destinations?.takeIf { it.isNotEmpty() }
        val exits = step.exits?.takeIf { it.isNotEmpty() }
        val instruction = when {
            destinations != null && exits != null && io.str("exit_destination") != null -> io.str("exit_destination")
            destinations != null && io.str("destination") != null -> io.str("destination")
            exits != null && io.str("exit") != null -> io.str("exit")
            !step.junctionName.isNullOrEmpty() && io.str("junction_name") != null -> io.str("junction_name")
            wayName.isNotEmpty() && io.str("name") != null -> io.str("name")
            !options.waypointName.isNullOrEmpty() && io.str("named") != null -> io.str("named")
            else -> io.str("default")
        } ?: error("$code: no default template for $type")

        val parts = destinations?.split(": ")
        val destinationRef = parts?.get(0)?.split(',')?.get(0)
        val destination = parts?.getOrNull(1)?.split(',')?.get(0)
        val firstDestination = if (!destination.isNullOrEmpty() && !destinationRef.isNullOrEmpty()) "$destinationRef: $destination"
        else destinationRef?.takeIf { it.isNotEmpty() } ?: destination?.takeIf { it.isNotEmpty() } ?: ""

        val leg = options.legIndex
        val lastLeg = options.legCount?.let { it - 1 }
        val nth = if (leg != null && leg >= 0 && leg != lastLeg) ordinalize(leg + 1) else ""
        val tokens = HashMap<String, String?>()
        tokens["way_name"] = wayName
        tokens["destination"] = firstDestination
        tokens["exit"] = step.exits.orEmpty().split(';')[0]
        tokens["exit_number"] = ordinalize(step.exit?.takeIf { it != 0 } ?: 1)
        tokens["rotary_name"] = step.rotaryName
        tokens["lane_instruction"] = laneInstruction
        tokens["modifier"] = modifier?.let { modifierWord(it) }
        tokens["direction"] = directionFromDegree(step.bearingAfter)
        tokens["nth"] = nth
        tokens["waypoint_name"] = options.waypointName
        tokens["junction_name"] = step.junctionName.orEmpty().split(';')[0]
        return OsrmText(tokenize(instruction, tokens, capitalize), instruction.startsWith("{"))
    }

    /** osrm-text-instructions' `grammarize`: applies the language's grammar rules for [grammar] to [name]. */
    fun grammarize(name: String, grammar: String?): String {
        if (grammar == null) return name
        val rules = grammarRules[grammar] ?: return name
        var n = " $name "
        for (r in rules) n = r.apply(n)
        return n.trim()
    }

    /**
     * osrm-text-instructions' `tokenize`: replaces `{token}` and `{token:grammar}` with [tokens]; a token whose value
     * is missing or null stays as written. Collapses double spaces and capitalises the first letter when [capitalize].
     */
    fun tokenize(instruction: String, tokens: Map<String, String?>, capitalize: Boolean = capitalizeFirst): String {
        var startedWithToken = false
        val sb = StringBuilder(instruction.length + 32)
        var last = 0
        for (m in TOKEN.findAll(instruction)) {
            sb.append(instruction, last, m.range.first)
            val value = tokens[m.groupValues[1]]
            if (value == null) {
                sb.append(m.value)
            } else {
                var v = grammarize(value, m.groups[2]?.value)
                if (m.range.first == 0 && capitalize) {
                    startedWithToken = true
                    v = capitalizeFirstLetter(v)
                }
                sb.append(v)
            }
            last = m.range.last + 1
        }
        sb.append(instruction, last, instruction.length)
        val out = sb.toString().replace("  ", " ")
        return if (!startedWithToken && capitalize) capitalizeFirstLetter(out) else out
    }

    companion object {
        private val TOKEN = Regex("""\{(\w+)(?::(\w+))?\}""")
        private val cache = ConcurrentHashMap<String, OsrmInstructions>()

        /** The languages vendored with Atlas, as osrm-text-instructions codes. */
        val CODES = listOf("en", "nl", "de", "fr", "es", "it", "pt-BR", "pl", "sv", "da", "no", "fi", "ru", "ja", "zh-Hans", "tr", "uk")
        private val GRAMMARS = setOf("da", "fr", "ru")

        /**
         * The osrm code for a BCP 47 tag: exact match first, then aliases ("nb" and "nn" read Norwegian "no",
         * "pt" reads "pt-BR", "zh" reads "zh-Hans"), then the bare language; null when none is vendored.
         */
        fun resolve(tag: String): String? {
            val t = tag.replace('_', '-')
            CODES.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
            val lang = t.substringBefore('-').lowercase()
            return when (lang) {
                "nb", "nn", "no" -> "no"
                "pt" -> "pt-BR"
                "zh" -> "zh-Hans"
                else -> CODES.firstOrNull { it.equals(lang, ignoreCase = true) }
            }
        }

        /** The templates for [tag], falling back to English when the language is not vendored. */
        fun forLanguage(tag: String): OsrmInstructions {
            val code = resolve(tag) ?: "en"
            return cache.getOrPut(code) {
                val root = readJson("osrm/translations/$code.json") ?: error("missing translation $code")
                val grammar = if (code in GRAMMARS) readJson("osrm/grammar/$code.json") else null
                OsrmInstructions(code, root, grammar)
            }
        }

        internal fun readJson(path: String): JsonObject? {
            val stream = OsrmInstructions::class.java.getResourceAsStream("/app/atlas/guide/$path") ?: return null
            val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            return Json.parseToJsonElement(text).jsonObject
        }

        private fun replaceFirstLiteral(s: String, find: String, with: String): String {
            val i = s.indexOf(find)
            return if (i < 0) s else s.substring(0, i) + with + s.substring(i + find.length)
        }
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** A regular-expression replacement with JavaScript's `String.replace` semantics (flags "g" and "i", `$1`, `$&`, `$$`). */
internal class JsRule(private val pattern: Pattern, private val replacement: String, private val global: Boolean) {

    fun apply(s: String): String {
        val m = pattern.matcher(s)
        if (!m.find()) return s
        val sb = StringBuilder(s.length + 16)
        var last = 0
        do {
            sb.append(s, last, m.start())
            expand(m, s, sb)
            last = m.end()
        } while (global && m.find())
        sb.append(s, last, s.length)
        return sb.toString()
    }

    private fun expand(m: Matcher, s: String, sb: StringBuilder) {
        val r = replacement
        var i = 0
        while (i < r.length) {
            val c = r[i]
            if (c != '$' || i + 1 >= r.length) {
                sb.append(c); i++; continue
            }
            val n = r[i + 1]
            when {
                n == '$' -> { sb.append('$'); i += 2 }
                n == '&' -> { sb.append(s, m.start(), m.end()); i += 2 }
                n == '`' -> { sb.append(s, 0, m.start()); i += 2 }
                n == '\'' -> { sb.append(s, m.end(), s.length); i += 2 }
                n in '0'..'9' -> {
                    val two = if (i + 2 < r.length && r[i + 2] in '0'..'9') (n - '0') * 10 + (r[i + 2] - '0') else -1
                    val one = n - '0'
                    when {
                        two in 1..m.groupCount() -> { m.group(two)?.let { sb.append(it) }; i += 3 }
                        one in 1..m.groupCount() -> { m.group(one)?.let { sb.append(it) }; i += 2 }
                        else -> { sb.append('$'); i++ }
                    }
                }
                else -> { sb.append('$'); i++ }
            }
        }
    }

    companion object {
        fun of(regex: String, replacement: String, flags: String): JsRule {
            var f = 0
            if ('i' in flags) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            return JsRule(Pattern.compile(regex, f), replacement, 'g' in flags)
        }
    }
}
