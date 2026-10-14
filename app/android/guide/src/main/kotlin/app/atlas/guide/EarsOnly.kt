package app.atlas.guide

import app.atlas.geo.Compass
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Fix
import app.atlas.model.Place
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.sin

/** When a place closes today, for "open until 19:00"; the app fills it from the opening hours in `:places`. */
fun interface OpeningLookup {
    /** The closing time of [place] at [at], or null when unknown or closed. */
    fun closesAt(place: Place, at: Instant): LocalTime?
}

/**
 * A place around the traveller, as Ears-only mode sees it.
 *
 * @property label its name, or the category word when it has none ("Bakery").
 * @property category the phrase category ("bakery", "tram_stop"…), null for places Ears-only does not name.
 * @property bearing compass bearing from the traveller; [relative] the angle from the heading, −180..180, negative left.
 */
data class Nearby(
    val place: Place,
    val label: String,
    val category: String?,
    val distance: Double,
    val bearing: Double,
    val relative: Double?,
    val importance: Double,
) {
    /** Where its sound comes from: −1 left … 1 right. */
    val pan: Float get() = relative?.let { sin(Math.toRadians(it)).toFloat() } ?: 0f
}

/**
 * The Soundscape-like mode (board "Ears only"): no route, just the world told by ear. Places are called out from the
 * direction they are in ([Cue.Speak.pan]); "Around me" and "Where am I?" answer on demand; [update] calls out crossings
 * ahead and places as you pass them; [Beacon] pulls you towards one place.
 *
 * Directions are relative to [heading] (the phone's compass or the course), as words ("slightly right") or the clock
 * ("at 2 o'clock") per [GuideSettings.directionStyle]; without a heading they are compass points.
 */
class EarsOnly(val settings: GuideSettings, private val opening: OpeningLookup? = null) {
    /** The phrases: the traveller's language when Atlas has its own phrases in it, else English throughout. */
    val book: Phrasebook = Phrasebook.of(settings).let { if (it.hasOwnPhrases) it else Phrasebook.of("en", settings.units, settings.heightsInFeet) }

    private val calledOut = HashMap<String, Instant>()
    /** Labels called out, where and when: the second stop of one tram stop, or a second shop of one name, stays quiet. */
    private val calledLabels = HashMap<String, Pair<Instant, LatLon>>()

    /** The places within [radiusM], most important first: what the "Around me" radar shows. */
    fun nearby(fix: Fix, heading: Double?, places: List<Place>, radiusM: Double = 200.0, limit: Int = 8): List<Nearby> {
        val h = heading ?: movingBearing(fix)
        val out = ArrayList<Nearby>()
        for (p in places) {
            val d = Geo.distance(fix.location, p.location)
            if (d > radiusM) continue
            val cat = category(p)
            val label = p.name(settings.language.substringBefore('-')) ?: cat?.let { book.phrase("cat.$it") } ?: continue
            val b = Geo.bearing(fix.location, p.location)
            val rel = h?.let { Geo.turnAngle(it, b) }
            out += Nearby(p, label, cat, d, b, rel, importance(p, cat, d))
        }
        out.sortByDescending { it.importance }
        // One name once: a tram stop's platforms and stop positions, or a chain's two doors, are one place to the ear.
        val kept = ArrayList<Nearby>()
        for (n in out) {
            if (kept.size >= limit) break
            if (kept.none { it.label.equals(n.label, ignoreCase = true) && Geo.distance(it.place.location, n.place.location) < SAME_PLACE_M }) kept += n
        }
        return kept
    }

    /** "Around me": one sentence per place, each from its direction, most important first. */
    fun aroundMe(fix: Fix, heading: Double?, places: List<Place>, limit: Int = 4, radiusM: Double = 200.0): List<Cue> {
        val items = nearby(fix, heading, places, radiusM, limit)
        if (items.isEmpty()) return listOf(speak(book.phrase("around_none"), Priority.NORMAL, 0f, "around"))
        return items.mapIndexed { i, n -> speak(itemText(n), Priority.NORMAL, n.pan, "around:$i") }
    }

    /** "Where am I?": the street, the nearest address, the way you face, and the three most notable places near. */
    fun whereAmI(fix: Fix, address: String?, nearestStreet: String?, places: List<Place>, heading: Double? = movingBearing(fix)): List<Cue> {
        val first = ArrayList<String>()
        nearestStreet?.takeIf { it.isNotBlank() }?.let { first += book.phrase("where_on", "street" to it) }
        address?.takeIf { it.isNotBlank() }?.let { first += book.phrase("where_near", "address" to it) }
        heading?.let { first += book.phrase("where_facing", "direction" to Instr(compass(it))) }
        val lead = if (first.isEmpty()) book.phrase("where_unknown") else first.mapIndexed { i, s -> if (i == 0) s else book.embed(Instr(s)) }.joinToString(", ")
        val items = nearby(fix, heading, places, 200.0, 3)
        val near = if (items.isEmpty()) null else book.phrase("nearby", "list" to Instr(items.joinToString("; ") { itemText(it) }, startsWithName = true))
        val text = book.sentences(listOfNotNull(lead, near))
        return listOf(speak(text, Priority.HIGH, 0f, "where"))
    }

    /**
     * The automatic callouts for one fix: a crossing just ahead ("Crossing ahead, with signal"), and places as you pass
     * them ("Bakery on your left, open until 19:00"). Each place is called out once in [repeatAfter]; at most two per fix.
     */
    @Synchronized
    fun update(fix: Fix, heading: Double?, places: List<Place>, repeatAfter: Duration = Duration.ofMinutes(10)): List<Cue> {
        val h = heading ?: movingBearing(fix) ?: return emptyList()
        forgetOlderThan(fix.time.minus(repeatAfter))
        val out = ArrayList<Cue>()
        val candidates = ArrayList<Pair<Pair<Double, Place>, Cue.Speak>>()
        for (p in places) {
            val last = calledOut[p.key]
            if (last != null && Duration.between(last, fix.time) < repeatAfter) continue
            val labelSeen = (p.tags["name"])?.let { calledLabels[it.lowercase()] }
            if (labelSeen != null && Duration.between(labelSeen.first, fix.time) < repeatAfter && Geo.distance(labelSeen.second, p.location) < SAME_PLACE_M) continue
            val d = Geo.distance(fix.location, p.location)
            if (d > CROSSING_AHEAD_M) continue
            val rel = Geo.turnAngle(h, Geo.bearing(fix.location, p.location))
            val pan = sin(Math.toRadians(rel)).toFloat()
            val cat = category(p)
            if (cat == "crossing" || cat == "traffic_signals") {
                if (abs(rel) <= 35 && d >= 4) candidates += ((100.0 - d) to p) to speak(book.phrase(crossingKey(p)), Priority.NORMAL, pan, "ears:${p.key}")
                continue
            }
            if (settings.verbosity == Verbosity.MINIMAL || cat == null || d > PASSING_M || abs(rel) < 30 || abs(rel) > 150) continue
            if (settings.verbosity == Verbosity.NORMAL && weight(p, cat) < 4.0) continue
            val n = Nearby(p, p.name(settings.language.substringBefore('-')) ?: book.phrase("cat.$cat"), cat, d, 0.0, rel, importance(p, cat, d))
            val side = book.phrase("callout_place", "name" to n.label, "direction" to Instr(book.embed(Instr(book.phrase(if (rel < 0) "dir.left" else "dir.right")))))
            val open = opening?.closesAt(p, fix.time)?.let { book.embed(book.phraseInstr("open_until", "time" to Instr(book.timeOfDay(it, settings.units)))) }
            candidates += (n.importance to p) to speak(listOfNotNull(side, open).joinToString(", "), Priority.LOW, pan, "ears:${p.key}")
        }
        candidates.sortByDescending { it.first.first }
        for ((key, cue) in candidates.take(2)) {
            out += cue
            calledOut[key.second.key] = fix.time
            key.second.tags["name"]?.let { calledLabels[it.lowercase()] = fix.time to key.second.location }
        }
        return out
    }

    /** Forgets what was called out, so everything may be said again. */
    @Synchronized
    fun reset() {
        calledOut.clear()
        calledLabels.clear()
    }

    /** A walk of hours passes thousands of places: what may be said again is no longer remembered. */
    private fun forgetOlderThan(t: Instant) {
        if (calledOut.size + calledLabels.size < PRUNE_AT) return
        calledOut.values.removeIf { it.isBefore(t) }
        calledLabels.values.removeIf { it.first.isBefore(t) }
    }

    /** "Café Molen, 60 metres, slightly right". */
    fun itemText(n: Nearby): String =
        book.phrase("around_item", "name" to n.label, "distance" to Instr(book.distance(n.distance, precise = true)), "direction" to Instr(direction(n.relative, n.bearing)))

    /** The direction of something at [relative] degrees from the heading (or at compass [bearing] without one). */
    fun direction(relative: Double?, bearing: Double): String {
        if (relative == null) return compass(bearing)
        if (settings.directionStyle == DirectionStyle.CLOCK) {
            val hour = Math.floorMod(Math.round(relative / 30.0).toInt(), 12).let { if (it == 0) 12 else it }
            return book.embed(book.phraseInstr("clock", "n" to Instr(hour.toString())))
        }
        val a = abs(relative)
        val key = when {
            a <= 15 -> "dir.ahead"
            a <= 50 -> if (relative < 0) "dir.slight_left" else "dir.slight_right"
            a <= 135 -> if (relative < 0) "dir.left" else "dir.right"
            a <= 165 -> if (relative < 0) "dir.behind_left" else "dir.behind_right"
            else -> "dir.behind"
        }
        return book.phrase(key).let { book.embed(Instr(it)) }
    }

    /** The compass word for a bearing, from the osrm table of the language ("north-east", "Nordosten"). */
    fun compass(bearing: Double): String {
        val key = when (Compass.of(bearing, 8)) {
            Compass.N -> "north"; Compass.NE -> "northeast"; Compass.E -> "east"; Compass.SE -> "southeast"
            Compass.S -> "south"; Compass.SW -> "southwest"; Compass.W -> "west"; else -> "northwest"
        }
        return book.osrm.directionWord(key) ?: key
    }

    private fun speak(text: String, priority: Priority, pan: Float, id: String) = Cue.Speak(text, Announcer.ssml(text), priority, true, pan, id)

    private fun movingBearing(fix: Fix): Double? = fix.bearing?.takeIf { (fix.speed ?: 1.0) >= 0.3 }

    companion object {
        private const val CROSSING_AHEAD_M = 35.0
        private const val PASSING_M = 25.0
        private const val SAME_PLACE_M = 80.0
        private const val PRUNE_AT = 512

        private val WEIGHTS = mapOf(
            "station" to 10.0, "tram_stop" to 8.0, "bus_stop" to 8.0, "crossing" to 7.0, "place_of_worship" to 7.0,
            "traffic_signals" to 6.0, "entrance" to 6.0, "museum" to 6.0, "park" to 5.0, "cafe" to 5.0, "restaurant" to 5.0,
            "bakery" to 5.0, "pharmacy" to 5.0, "supermarket" to 5.0, "hospital" to 5.0, "bar" to 4.0, "pub" to 4.0,
            "fast_food" to 4.0, "convenience" to 4.0, "bank" to 4.0, "post_office" to 4.0, "school" to 4.0, "hotel" to 4.0,
            "atm" to 4.0, "toilets" to 4.0, "fuel" to 4.0, "playground" to 3.0, "shop" to 3.0, "parking" to 3.0,
            "bicycle_parking" to 3.0, "bench" to 2.0,
        )

        /** How much a place deserves a mention: what it is, whether it has a name, and how near it is. */
        fun importance(p: Place, category: String?, distance: Double): Double {
            val w = weight(p, category)
            val named = if (p.tags["name"] != null) 1.4 else 1.0
            val brand = if (p.tags["brand"] != null) 1.1 else 1.0
            return w * named * brand / (1.0 + distance / 50.0)
        }

        /** What a kind of place is worth to the ear; a door without a name is just a door. */
        private fun weight(p: Place, category: String?): Double =
            if (category == "entrance" && p.tags["name"] == null && p.tags["ref"] == null) 2.0 else WEIGHTS[category] ?: 2.0

        /** The Ears-only category of a place (a `cat.*` phrase key without the prefix), or null. */
        fun category(p: Place): String? {
            val t = p.tags
            val amenity = t["amenity"]
            val shop = t["shop"]
            return when {
                t["highway"] == "crossing" || t["railway"] == "crossing" -> "crossing"
                t["highway"] == "traffic_signals" -> "traffic_signals"
                t["railway"] == "station" || t["railway"] == "halt" || t["public_transport"] == "station" -> "station"
                t["railway"] == "tram_stop" || t["tram"] == "yes" && t["public_transport"] == "platform" -> "tram_stop"
                t["highway"] == "bus_stop" || t["bus"] == "yes" && t["public_transport"] == "platform" -> "bus_stop"
                t["entrance"] != null -> "entrance"
                amenity == "place_of_worship" -> "place_of_worship"
                amenity in AMENITIES -> amenity
                shop in SHOPS -> shop
                t["tourism"] == "museum" -> "museum"
                t["tourism"] == "hotel" -> "hotel"
                t["leisure"] == "park" -> "park"
                t["leisure"] == "playground" -> "playground"
                shop != null -> "shop"
                else -> null
            }
        }

        private val AMENITIES = setOf(
            "cafe", "restaurant", "bar", "pub", "fast_food", "pharmacy", "toilets", "bench", "atm", "bank", "post_office",
            "school", "hospital", "fuel", "parking", "bicycle_parking",
        )
        private val SHOPS = setOf("bakery", "supermarket", "convenience")

        private fun crossingKey(p: Place): String {
            val c = p.tags["crossing"]
            return when {
                p.tags["highway"] == "traffic_signals" || c == "traffic_signals" || p.tags["crossing:signals"] == "yes" -> "crossing_ahead_signals"
                c == "zebra" || p.tags["crossing_ref"] == "zebra" || p.tags["crossing:markings"] == "zebra" -> "crossing_ahead_zebra"
                else -> "crossing_ahead_plain"
            }
        }
    }
}

/**
 * An audio beacon on one place (Soundscape's "lighthouse for the ears"): a periodic [EarconKind.BEACON] whose pan is the
 * side the target is on and whose pitch rises as you face it (a fifth up within 15°), ticking faster as you near it; a
 * voice line every [voiceEveryM] metres of progress; a long pulse when you turn to face it; and the arrival.
 */
class Beacon(
    val target: LatLon,
    val name: String? = null,
    val settings: GuideSettings = GuideSettings(),
    private val voiceEveryM: Double = 50.0,
    private val arriveRadiusM: Double = 10.0,
) {
    private val ears = EarsOnly(settings)
    private var lastPing: Instant? = null
    private var lastVoiceAt = Double.NaN
    private var facing = false

    /** True once the target is reached; the beacon is then silent. */
    var arrived = false
        private set

    /** The cues for one fix; [heading] is the compass or course, the fix's bearing by default. */
    @Synchronized
    fun update(fix: Fix, heading: Double? = fix.bearing): List<Cue> {
        if (arrived) return emptyList()
        val out = ArrayList<Cue>()
        val d = Geo.distance(fix.location, target)
        val bearing = Geo.bearing(fix.location, target)
        val rel = heading?.let { Geo.turnAngle(it, bearing) }
        val pan = pan(rel)
        val label = name ?: ears.book.phrase("beacon_target")
        if (d <= arriveRadiusM + (fix.accuracy - 10).coerceAtLeast(0.0) * 0.5) {
            arrived = true
            if (settings.earcons) out += Cue.Earcon(EarconKind.ARRIVE, pan)
            if (settings.haptics) out += Haptics.arrive()
            if (settings.voice) {
                val text = ears.book.phrase("beacon_arrived", "name" to label)
                out += Cue.Speak(text, Announcer.ssml(text), Priority.HIGH, false, pan, "beacon:arrived")
            }
            out += Cue.Arrived
            return out
        }
        val since = lastPing?.let { Duration.between(it, fix.time).toMillis() / 1000.0 }
        if (settings.earcons && (since == null || since >= period(d))) {
            out += Cue.Earcon(EarconKind.BEACON, pan, pitch(rel))
            lastPing = fix.time
        }
        val nowFacing = rel != null && abs(rel) <= ON_TARGET
        if (nowFacing && !facing && settings.haptics) out += Haptics.straight()
        facing = nowFacing
        if (settings.voice && (lastVoiceAt.isNaN() || abs(lastVoiceAt - d) >= voiceEveryM)) {
            val n = Nearby(Place(null, target, emptyMap()), label, null, d, bearing, rel, 0.0)
            val text = ears.itemText(n)
            out += Cue.Speak(text, Announcer.ssml(text), Priority.NORMAL, true, pan, "beacon")
            lastVoiceAt = d
        }
        return out
    }

    companion object {
        private const val ON_TARGET = 15.0

        /** Seconds between pings: every 2 s far away, down to 0.6 s close by. */
        fun period(distance: Double): Double = (distance / 40.0).coerceIn(0.6, 2.0)

        /** −1 (left) … 1 (right) for a target [relative] degrees from the heading. */
        fun pan(relative: Double?): Float = relative?.let { sin(Math.toRadians(it)).toFloat() } ?: 0f

        /** 1.5 (a fifth up) when facing the target, falling from 1.25 beside it to 0.75 behind. */
        fun pitch(relative: Double?): Float {
            if (relative == null) return 1f
            val a = abs(relative)
            if (a <= ON_TARGET) return 1.5f
            return (1.25 - 0.5 * (a - ON_TARGET) / (180 - ON_TARGET)).toFloat()
        }
    }
}
