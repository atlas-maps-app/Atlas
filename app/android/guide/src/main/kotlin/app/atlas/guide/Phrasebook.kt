package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.UnitSystem
import app.atlas.model.Lane
import app.atlas.model.ManeuverType
import app.atlas.model.Step
import app.atlas.model.Turn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A clause from the phrase tables and whether it begins with a name (which must keep its capital when embedded). */
data class Instr(val text: String, val startsWithName: Boolean = false) {
    override fun toString() = text
}

/** What an instruction needs beyond its step: the arrival side, the leg for waypoints, the short form, "will arrive". */
data class StepContext(
    val arrivalSide: Side = Side.UNKNOWN,
    val legIndex: Int? = null,
    val legCount: Int? = null,
    val short: Boolean = false,
    val upcoming: Boolean = false,
    val leftHandTraffic: Boolean = false,
)

/** One language's table of Atlas phrases and its number conventions. */
internal class PhraseTable(
    val phrases: Map<String, String>,
    val decimal: String,
    val sentence: String,
    val list: String,
    /** Words that may lose their capital when a clause is embedded; null means any first word may. */
    val decapitalize: Set<String>?,
) {
    companion object {
        val EMPTY = PhraseTable(emptyMap(), ".", ". ", ", ", null)

        fun load(code: String): PhraseTable {
            val root = OsrmInstructions.readJson("atlas/$code.json") ?: return EMPTY
            val meta = root.obj("meta")
            val phrases = root.obj("phrases")?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            return PhraseTable(
                phrases,
                decimal = meta?.str("decimal") ?: ".",
                sentence = meta?.str("sentence") ?: ". ",
                list = meta?.str("list") ?: ", ",
                decapitalize = meta?.get("decapitalize")?.jsonArray?.map { (it as JsonPrimitive).content }?.toSet(),
            )
        }
    }
}

/**
 * Everything the guide says, in one language: osrm-text-instructions' templates for the maneuvers, Atlas's own phrases
 * for landmarks, warnings and Ears-only callouts (English when the language has none), and localised quantities
 * ("in 200 metres", "in a quarter mile", "in 2 cables").
 */
class Phrasebook private constructor(
    val osrm: OsrmInstructions,
    private val own: PhraseTable,
    private val english: PhraseTable,
    val units: UnitSystem,
    /** Heights and clearances in feet and inches. */
    val feet: Boolean,
) {

    /** The osrm language code in use; "en" for languages Atlas does not carry. */
    val code: String get() = osrm.code
    private val locale = Locale.forLanguageTag(code)

    /** True when Atlas's own phrases exist in this language (en, nl, de, fr), not just its units. */
    val hasOwnPhrases: Boolean get() = own.phrases.containsKey("dest_left")

    // ---- Atlas phrases ------------------------------------------------------------------------------------------

    /** Whether this language itself has [key] (no English fallback). */
    fun has(key: String): Boolean = own.phrases.containsKey(key)

    /** The phrase [key] filled with [args] (a String value is a name; an [Instr] is a clause); English when missing. */
    fun phrase(key: String, vararg args: Pair<String, Any>): String = phraseInstr(key, *args).text

    fun phraseInstr(key: String, vararg args: Pair<String, Any>): Instr {
        val template = own.phrases[key] ?: english.phrases[key] ?: error("no phrase '$key'")
        return fill(template, args.toMap())
    }

    /** As [phrase] but only from this language's own table; null when it has none. */
    fun ownPhrase(key: String, vararg args: Pair<String, Any>): Instr? = own.phrases[key]?.let { fill(it, args.toMap()) }

    /** Joins sentences the way the language does (". " or "。"). */
    fun sentences(parts: List<String>): String = parts.filter { it.isNotBlank() }.joinToString(own.sentence)

    fun list(parts: List<String>): String = parts.joinToString(own.list)

    /**
     * Fills a template's `{token}`s. A clause ([Instr]) embedded after the start loses its leading capital (where the
     * language allows); the result starts with a capital when the language capitalises sentences.
     */
    fun fill(template: String, args: Map<String, Any?>): Instr {
        val sb = StringBuilder(template.length + 48)
        var last = 0
        var startsWithName = false
        for (m in TOKEN.findAll(template)) {
            sb.append(template, last, m.range.first)
            val value = args[m.groupValues[1]]
            val atStart = m.range.first == 0
            when (value) {
                null -> sb.append(m.value)
                is Instr -> {
                    if (atStart) startsWithName = value.startsWithName
                    sb.append(if (atStart) value.text else embed(value))
                }
                else -> {
                    if (atStart) startsWithName = true
                    sb.append(osrm.grammarize(value.toString(), m.groups[2]?.value))
                }
            }
            last = m.range.last + 1
        }
        sb.append(template, last, template.length)
        var text = sb.toString().replace("  ", " ").trim()
        if (osrm.capitalizeFirst && !startsWithName) text = osrm.capitalizeFirstLetter(text)
        return Instr(text, startsWithName)
    }

    /** The clause as it reads inside a sentence: "Turn left" becomes "turn left"; names and German nouns keep capitals. */
    fun embed(instr: Instr): String {
        val t = instr.text
        if (!osrm.capitalizeFirst || instr.startsWithName || t.isEmpty() || !t[0].isUpperCase()) return t
        val firstWord = t.takeWhile { !it.isWhitespace() && it != ',' }
        val allowed = own.decapitalize?.contains(firstWord) ?: true
        // An all-capital first word is an abbreviation (VHF, A10): leave it.
        if (!allowed || (firstWord.length > 1 && firstWord.all { !it.isLetter() || it.isUpperCase() })) return t
        return t.substring(0, 1).lowercase(locale) + t.substring(1)
    }

    // ---- Quantities ---------------------------------------------------------------------------------------------

    fun number(x: Double, decimals: Int): String =
        if (decimals <= 0) x.toLong().toString() else String.format(Locale.ROOT, "%.${decimals}f", x).replace(".", own.decimal)

    /** A quantity in words: "200 metres", "einem Kilometer", "half a mile", "2 cables". */
    fun quantity(q: Spoken, form: DistanceForm = DistanceForm.BARE): String {
        val u = q.unit
        if (q.idiom != null) {
            val keys = if (form == DistanceForm.IN) listOf("unit.$u.${q.idiom}.in", "unit.$u.${q.idiom}") else listOf("unit.$u.${q.idiom}")
            lookup(keys)?.let { return it }
        }
        val cat = Plurals.category(code, q.value, q.decimals)
        val keys = ArrayList<String>(6)
        if (form == DistanceForm.IN) { keys += "unit.$u.in.$cat"; keys += "unit.$u.in.other" }
        keys += "unit.$u.$cat"; keys += "unit.$u.other"
        val template = lookup(keys) ?: error("no unit '$u'")
        return template.replace("{n}", number(q.value, q.decimals))
    }

    private fun lookup(keys: List<String>): String? {
        for (k in keys) own.phrases[k]?.let { return it }
        for (k in keys) english.phrases[k]?.let { return it }
        return null
    }

    /** A distance, rounded the way a voice says it, in this book's units. */
    fun distance(metres: Double, form: DistanceForm = DistanceForm.BARE, precise: Boolean = false): String =
        quantity(Rounding.distance(metres, units, feet, precise), form)

    /** A height or clearance: "3.8 metres", "13 feet 6 inches". */
    fun height(metres: Double): String = Rounding.height(metres, feet).joinToString(" ") { quantity(it) }

    /** A small height difference: "0.3 metres", "4 inches". */
    fun margin(metres: Double): String = quantity(Rounding.margin(metres, feet))

    fun tonnes(t: Double): String = quantity(Rounding.tonnes(t))

    fun metres(m: Double): String = quantity(Rounding.oneDecimal(m, "m"))

    fun duration(seconds: Double): String = Rounding.duration(seconds).joinToString(" ") { quantity(it) }

    fun percent(n: Int): String = quantity(Spoken("percent", n.toDouble(), 0))

    /** A speed with its unit ("kmh", "mph" or "kn"). */
    fun speed(value: Double, unit: String): String = quantity(Spoken(unit, Math.round(value).toDouble(), 0))

    /** A clock time: 24-hour, or "2:32 PM" for English with imperial units. */
    fun time(instant: Instant, zone: ZoneId): String {
        val pattern = if (code == "en" && units == UnitSystem.IMPERIAL) "h:mm a" else "H:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withZone(zone).format(instant)
    }

    /** A time of day as the voice says it: "19:00", or "7 PM" in American English. */
    fun timeOfDay(t: LocalTime, units: UnitSystem = this.units): String {
        val us = code == "en" && units == UnitSystem.IMPERIAL
        val pattern = when {
            us && t.minute == 0 -> "h a"
            us -> "h:mm a"
            else -> "H:mm"
        }
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(t)
    }

    // ---- Instructions -------------------------------------------------------------------------------------------

    /** The instruction for [step]'s maneuver: "Turn left onto Keizersgracht", "Take the 2nd exit…", "Enter the lock". */
    fun instruction(step: Step, ctx: StepContext = StepContext()): Instr {
        val m = step.maneuver
        val name = step.street?.takeIf { it.isNotBlank() }
        return when (m.type) {
            ManeuverType.ARRIVE, ManeuverType.WAYPOINT -> arrive(step, ctx)
            ManeuverType.FERRY_LEAVE -> if (name != null) phraseInstr("ferry_leave_name", "way_name" to name) else phraseInstr("ferry_leave")
            ManeuverType.LOCK -> step.landmarks.firstOrNull { it.kind == "lock" }?.name?.let { phraseInstr("lock_enter_name", "name" to it) }
                ?: phraseInstr("lock_enter")
            ManeuverType.BRIDGE -> step.landmarks.firstOrNull { it.kind == "bridge" }?.name?.let { phraseInstr("bridge_pass_name", "name" to it) }
                ?: phraseInstr("bridge_pass")
            ManeuverType.PASS_MARK -> {
                val lm = step.landmarks.firstOrNull()
                val side = if (lm != null && lm.side < 0) "port" else "starboard"
                phraseInstr("pass_mark", "mark" to Instr(markName(lm?.tags.orEmpty(), lm?.name), startsWithName = true), "side" to Instr(phrase(side)))
            }
            else -> {
                val os = osrmStep(step, ctx) ?: return Instr(osrm.capitalizeFirstLetter(osrm.modifierWord("straight").orEmpty()))
                if (ctx.short && m.type != ManeuverType.ROUNDABOUT && m.type != ManeuverType.DEPART) {
                    val word = osrm.modifierWord(os.modifier ?: "straight").orEmpty()
                    return Instr(if (osrm.capitalizeFirst) osrm.capitalizeFirstLetter(word) else word)
                }
                val t = osrm.compileText(os, OsrmOptions(classes = listOfNotNull(motorwayClass(step))))
                Instr(t.text, t.startsWithToken)
            }
        }
    }

    private fun motorwayClass(step: Step): String? =
        if (step.maneuver.type == ManeuverType.RAMP_OFF || step.maneuver.type == ManeuverType.RAMP_ON) null
        else if (step.ref != null && step.street == null) "motorway" else null

    private fun arrive(step: Step, ctx: StepContext): Instr {
        // The arrival side is the final destination's; a via point has only its own maneuver's side, if any.
        val side = if (step.maneuver.type != ManeuverType.WAYPOINT) ctx.arrivalSide else when {
            step.maneuver.turn.left -> Side.LEFT
            step.maneuver.turn.right -> Side.RIGHT
            else -> Side.UNKNOWN
        }
        val modifier = when (side) { Side.LEFT -> "left"; Side.RIGHT -> "right"; Side.AHEAD -> "straight"; else -> "default" }
        val key = when {
            ctx.short && ctx.upcoming -> "short-upcoming"
            ctx.short -> "short"
            ctx.upcoming -> "upcoming"
            else -> "default"
        }
        // Atlas's own table may correct a language whose "upcoming" template says "have arrived" (German).
        val ownKey = if (ctx.upcoming && !ctx.short) "arrive_upcoming" + (if (modifier == "left" || modifier == "right") "_$modifier" else "") else null
        val template = ownKey?.let { own.phrases[it] } ?: osrm.template("arrive", modifier, key) ?: osrm.template("arrive", "default", key)
            ?: osrm.template("arrive", "default", "default")!!
        val leg = ctx.legIndex
        val nth = if (step.maneuver.type == ManeuverType.WAYPOINT && leg != null) osrm.ordinalize(leg + 1) else ""
        return Instr(osrm.tokenize(template, mapOf("nth" to nth)))
    }

    /** The osrm-text-instructions view of a step; null for the maneuvers Atlas phrases itself. */
    fun osrmStep(step: Step, ctx: StepContext = StepContext()): OsrmStep? {
        val m = step.maneuver
        val name = step.street?.takeIf { it.isNotBlank() }
        val ref = step.ref?.takeIf { it.isNotBlank() }
        val dest = step.destinations?.takeIf { it.isNotBlank() }
        val exits = step.landmarks.firstOrNull { it.kind == "motorway_junction" }?.let { it.tags["ref"] ?: it.name }
        val rotary = step.landmarks.firstOrNull { it.kind == "roundabout" }?.name
        val junction = step.landmarks.firstOrNull { it.kind == "junction" }?.name
        val mod = modifier(m.turn)
        fun s(type: String, modifier: String?) = OsrmStep(
            type = type, modifier = modifier, name = name, ref = ref, destinations = dest, exits = exits,
            junctionName = junction, rotaryName = rotary, exit = m.exit, bearingAfter = m.bearingAfter?.let { Geo.normalize(it) },
            drivingSide = if (ctx.leftHandTraffic) "left" else "right",
        )
        return when (m.type) {
            ManeuverType.DEPART -> s("depart", null)
            ManeuverType.CONTINUE -> if (m.turn == Turn.STRAIGHT) s("new name", "straight") else s("continue", mod)
            ManeuverType.TURN -> s("turn", mod)
            ManeuverType.U_TURN -> s("turn", "uturn")
            ManeuverType.KEEP -> s("fork", keep(m.turn))
            ManeuverType.MERGE -> s("merge", keep(m.turn))
            ManeuverType.RAMP_ON -> s("on ramp", mod)
            ManeuverType.RAMP_OFF -> s("off ramp", keep(m.turn))
            ManeuverType.ROUNDABOUT -> s("roundabout", mod)
            ManeuverType.ROUNDABOUT_EXIT -> s("exit roundabout", mod)
            ManeuverType.END_OF_ROAD -> s("end of road", mod)
            ManeuverType.FERRY_BOARD -> s("turn", "straight").copy(mode = "ferry")
            else -> null
        }
    }

    /** "In 200 metres, turn left": the osrm "one in distance" phrase. */
    fun inDistance(metres: Double, instr: Instr): Instr =
        fill(osrm.phrase("one in distance")!!, mapOf("distance" to distance(metres, DistanceForm.IN), "instruction_one" to instr))

    /** Two maneuvers in one breath: "…, then immediately …", "…, then, in 150 metres, …" or "…, then …". */
    fun linked(a: Instr, b: Instr, gapM: Double?, immediate: Boolean): Instr {
        if (immediate) own.phrases["two_linked_immediately"]?.let { return fill(it, mapOf("instruction_one" to a, "instruction_two" to b)) }
        if (!immediate && gapM != null) {
            osrm.phrase("two linked by distance")?.let {
                return fill(it, mapOf("instruction_one" to a, "instruction_two" to b, "distance" to distance(gapM, DistanceForm.IN)))
            }
        }
        return fill(osrm.phrase("two linked")!!, mapOf("instruction_one" to a, "instruction_two" to b))
    }

    /** "Turn left after the bridge" (or the language's order); unchanged in languages without landmark phrases. */
    fun withLandmark(instr: Instr, landmark: String?): Instr {
        if (landmark == null) return instr
        val t = own.phrases["with_landmark"] ?: return instr
        return fill(t, mapOf("instruction" to instr, "landmark" to Instr(landmark)))
    }

    /** "Now, turn left" where the language has the phrase, else the bare instruction. */
    fun now(instr: Instr): Instr = own.phrases["now"]?.let { fill(it, mapOf("instruction" to instr)) } ?: instr

    /** "Head north on A10 for 30 kilometres": the departure with the length of its first stretch. */
    fun departFor(step: Step, metres: Double, ctx: StepContext = StepContext()): Instr {
        val base = instruction(step, ctx)
        val os = osrmStep(step, ctx) ?: return base
        val street = osrm.wayName(os)
        val template = osrm.template("depart", "default", "namedistance")
        if (street.isEmpty() || template == null) return Instr(sentences(listOf(base.text, continueFor(metres, null).text)))
        val text = osrm.tokenize(
            template,
            mapOf("way_name" to street, "direction" to osrm.directionFromDegree(os.bearingAfter), "distance" to distance(metres, DistanceForm.FOR)),
        )
        return Instr(text, base.startsWithName)
    }

    /** "Continue on A10 for 3 kilometres" (osrm `continue straight` with a distance). */
    fun continueFor(metres: Double, street: String?): Instr {
        val d = distance(metres, DistanceForm.FOR)
        val named = street?.let { osrm.template("continue", "straight", "namedistance") }
        val template = named ?: osrm.template("continue", "straight", "distance") ?: return Instr(d)
        return Instr(osrm.tokenize(template, mapOf("way_name" to street, "distance" to d)))
    }

    /** The lane sentence ("Keep left", "Keep in the middle") when some lanes do not lead along the route. */
    fun lanes(lanes: List<Lane>): String? {
        if (lanes.isEmpty() || lanes.all { it.valid } || lanes.none { it.valid }) return null
        return osrm.laneInstruction(lanes.map { it.valid })
    }

    /** "red 6", "the green buoy": a navigation mark from its seamark tags. */
    fun markName(tags: Map<String, String>, name: String?): String {
        val colour = (tags["seamark:buoy_lateral:colour"] ?: tags["seamark:beacon_lateral:colour"] ?: tags["colour"])
            ?.substringBefore(';')?.lowercase()?.takeIf { it in setOf("red", "green", "yellow") }
        val ref = tags["seamark:name"] ?: tags["ref"] ?: name
        // A noun phrase to sit inside a sentence: no sentence capital ("keep red 6 to starboard").
        return when {
            colour != null && ref != null && ref.length <= 4 -> embed(phraseInstr("mark_named", "colour" to Instr(phrase("colour.$colour")), "ref" to ref))
            colour != null -> embed(phraseInstr("buoy.$colour"))
            ref != null -> ref
            else -> embed(phraseInstr("buoy.plain"))
        }
    }

    companion object {
        private val TOKEN = Regex("""\{(\w+)(?::(\w+))?\}""")
        private val tables = ConcurrentHashMap<String, PhraseTable>()
        private val books = ConcurrentHashMap<String, Phrasebook>()

        /** The phrasebook for a BCP 47 [language] tag and [units]; unknown languages read English. */
        fun of(
            language: String,
            units: UnitSystem = UnitSystem.METRIC,
            feet: Boolean = units == UnitSystem.IMPERIAL || units == UnitSystem.IMPERIAL_UK,
        ): Phrasebook {
            val osrm = OsrmInstructions.forLanguage(language)
            return books.getOrPut("${osrm.code}/$units/$feet") {
                Phrasebook(osrm, table(osrm.code), table("en"), units, feet)
            }
        }

        /** The phrasebook for [settings]' language and units. */
        fun of(settings: GuideSettings): Phrasebook = of(settings.language, settings.units, settings.heightsInFeet)

        private fun table(code: String): PhraseTable = tables.getOrPut(code) { PhraseTable.load(code) }

        /** The osrm modifier for a turn. */
        fun modifier(turn: Turn): String = when (turn) {
            Turn.STRAIGHT -> "straight"
            Turn.SLIGHT_RIGHT -> "slight right"
            Turn.RIGHT -> "right"
            Turn.SHARP_RIGHT -> "sharp right"
            Turn.SLIGHT_LEFT -> "slight left"
            Turn.LEFT -> "left"
            Turn.SHARP_LEFT -> "sharp left"
            Turn.U_TURN -> "uturn"
        }

        private fun keep(turn: Turn): String = when {
            turn.left -> "slight left"
            turn.right -> "slight right"
            else -> "straight"
        }
    }
}
