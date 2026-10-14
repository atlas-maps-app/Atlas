package app.atlas.ui.go

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.guide.Beacon
import app.atlas.guide.Cue
import app.atlas.guide.EarsOnly
import app.atlas.guide.GuideSettings
import app.atlas.guide.Nearby
import app.atlas.guide.OpeningLookup
import app.atlas.model.Fix
import app.atlas.model.Place
import app.atlas.places.OpeningHours
import app.atlas.services.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId

/** One line of the Ears transcript: what was said, and why. */
data class EarsLine(val kind: Kind, val text: String) {
    enum class Kind { BEACON, CALLOUT, WHERE, AROUND }
}

/** What the Ears screen shows: the places on the radar, the heading, the transcript (newest first), the beacon. */
data class EarsView(
    val at: Fix? = null,
    val heading: Double? = null,
    val around: List<Nearby> = emptyList(),
    val lines: List<EarsLine> = emptyList(),
    val beacon: Nearby? = null,
    /** True while the places around are being read. */
    val loading: Boolean = true,
)

/**
 * Ears only: the world around you told by ear, with no route. Every fix it asks `:guide`'s [EarsOnly] what to call out
 * (crossings ahead, places as you pass them) and a [Beacon], when one is set, where the target is; "Where am I?" and
 * "Around me" answer on demand. Places come from [places] (the offline store, else the map tiles) and are read again
 * when you have moved [REFRESH_M]. It runs in [scope], the navigation service's, so it goes on with the screen off.
 */
class EarsSession(
    private val voice: Voice,
    private val settings: GuideSettings,
    private val scope: CoroutineScope,
    private val places: suspend (LatLon, Double) -> List<Place>,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val ears = EarsOnly(settings, OpeningLookup { p, at -> closesAt(p, at, zone) })
    private val viewState = MutableStateFlow(EarsView())
    val view: StateFlow<EarsView> = viewState.asStateFlow()

    private var known: List<Place> = emptyList()
    private var knownAt: LatLon? = null
    private var beacon: Beacon? = null
    private var feed: Job? = null
    private var heading: Double? = null

    /** Follows [fixes] (and the phone's compass, [headings]) until stopped. */
    fun start(fixes: Flow<Fix>, headings: Flow<Double?>? = null) {
        feed?.cancel()
        feed = scope.launch {
            headings?.let { h -> launch { h.collect { heading = it; viewState.value = viewState.value.copy(heading = it) } } }
            fixes.collect { onFix(it) }
        }
    }

    /** One fix: refresh the places when far enough from the last read, then the callouts and the beacon. */
    suspend fun onFix(fix: Fix) {
        val last = knownAt
        if (last == null || Geo.distance(last, fix.location) > REFRESH_M) {
            known = places(fix.location, RADIUS_M)
            knownAt = fix.location
        }
        val h = heading ?: fix.bearing?.takeIf { (fix.speed ?: 0.0) >= 0.3 }
        val cues = ArrayList<Cue>()
        synchronized(this) {
            cues += ears.update(fix, h, known)
            beacon?.let { b -> cues += b.update(fix, h) }
        }
        say(cues, EarsLine.Kind.CALLOUT)
        val target = beacon
        val around = ears.nearby(fix, h, known, RADAR_M, RADAR_LIMIT)
        viewState.value = viewState.value.copy(
            at = fix, heading = h, around = around, loading = false,
            beacon = target?.let { b -> if (b.arrived) null else beaconItem(fix, h, b) },
        )
    }

    /** "Where am I?": the street, the nearest address, the way you face, and what is near. */
    fun whereAmI() {
        val fix = viewState.value.at ?: return
        val h = viewState.value.heading
        val address = nearestAddress(fix.location)
        say(ears.whereAmI(fix, address?.first, address?.second, known, h), EarsLine.Kind.WHERE)
    }

    /** "Around me": the most notable places, each from its direction. */
    fun aroundMe() {
        val fix = viewState.value.at ?: return
        say(ears.aroundMe(fix, viewState.value.heading, known), EarsLine.Kind.AROUND)
    }

    /** Sets the beacon on [target] (the most notable place around when null); a second call on the same place clears it. */
    fun toggleBeacon(target: Nearby? = null) {
        // The most notable place a few steps away: one you are standing at needs no beacon.
        val around = viewState.value.around
        val pick = target ?: around.firstOrNull { it.distance >= BEACON_MIN_M } ?: around.firstOrNull() ?: return
        val current = beacon
        if (current != null && Geo.distance(current.target, pick.place.location) < 1.0) {
            beacon = null
            viewState.value = viewState.value.copy(beacon = null)
            return
        }
        beacon = Beacon(pick.place.location, pick.label, settings)
        viewState.value.at?.let { fix ->
            val cues = synchronized(this) { beacon!!.update(fix, viewState.value.heading) }
            say(cues, EarsLine.Kind.BEACON)
            viewState.value = viewState.value.copy(beacon = beaconItem(fix, viewState.value.heading, beacon!!))
        }
    }

    fun stop() {
        feed?.cancel()
        voice.stop()
    }

    private fun beaconItem(fix: Fix, heading: Double?, b: Beacon): Nearby {
        val d = Geo.distance(fix.location, b.target)
        val bearing = Geo.bearing(fix.location, b.target)
        return Nearby(Place(null, b.target, emptyMap()), b.name ?: "", null, d, bearing, heading?.let { Geo.turnAngle(it, bearing) }, 0.0)
    }

    private fun say(cues: List<Cue>, kind: EarsLine.Kind) {
        if (cues.isEmpty()) return
        val lines = ArrayList<EarsLine>()
        for (c in cues) {
            voice.play(c)
            if (c is Cue.Speak) {
                val k = if (c.id.startsWith("beacon")) EarsLine.Kind.BEACON else kind
                lines += EarsLine(k, c.text)
            }
        }
        if (lines.isNotEmpty()) viewState.value = viewState.value.copy(lines = (lines.reversed() + viewState.value.lines).take(MAX_LINES))
    }

    /** The nearest mapped address (house number and street) and its street, within 60 m. */
    private fun nearestAddress(at: LatLon): Pair<String, String>? {
        val p = known.filter { it.tags["addr:street"] != null && it.tags["addr:housenumber"] != null }
            .minByOrNull { Geo.distance(at, it.location) }
            ?.takeIf { Geo.distance(at, it.location) <= 60.0 } ?: return null
        val street = p.tags.getValue("addr:street")
        return "$street ${p.tags.getValue("addr:housenumber")}" to street
    }

    companion object {
        const val RADIUS_M = 250.0
        const val RADAR_M = 200.0
        const val RADAR_LIMIT = 8
        const val REFRESH_M = 60.0
        const val MAX_LINES = 12
        const val BEACON_MIN_M = 25.0

        /** When [p] closes today, from its opening hours, for "open until 19". */
        fun closesAt(p: Place, at: java.time.Instant, zone: ZoneId): LocalTime? {
            val hours = OpeningHours.parseOrNull(p.tags["opening_hours"]) ?: return null
            val now = at.atZone(zone).toLocalDateTime()
            val s = hours.status(now)
            if (!s.isOpen || s.unknown) return null
            val change = s.changesAt ?: return null
            return if (change.toLocalDate() == now.toLocalDate() || change.isBefore(now.plusHours(12))) change.toLocalTime() else null
        }
    }
}
