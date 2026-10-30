package app.atlas.ui.go

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.atlas.Services
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.RoutePrefs
import app.atlas.model.RouteRequest
import app.atlas.providers.Forecast
import app.atlas.route.RouteResult
import app.atlas.route.SegmentStore
import app.atlas.ui.kit.CurrentWeather
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A destination on the Go tab's gantry: home, work, or a place you went to, with the way there by your mode. */
data class GuideSign(
    val kind: Kind,
    val place: SavedPlace,
    /** Metres as the crow flies, and the bearing from here, for the sign's arrow. */
    val crowMetres: Double,
    val bearing: Double,
    /** The way there by the chosen mode, when the router answered: its length and time. */
    val metres: Double? = null,
    val seconds: Double? = null,
    /** When to leave to be there by the place's time (home and work), when one is set. */
    val leaveAt: Instant? = null,
) {
    enum class Kind { HOME, WORK, RECENT }
}

/** The "Keep it moving" services, each a blue road-service sign. */
enum class Moving(val symbol: String, val test: (Map<String, String>) -> Boolean) {
    FUEL("ui-fuel", { it["amenity"] == "fuel" }),
    CHARGE("ui-plug-zap", { it["amenity"] == "charging_station" }),
    PARK("ui-parking", { it["amenity"] == "parking" && it["access"] !in setOf("private", "no") }),
    BIKE_FIX("ui-wrench", { it["amenity"] == "bicycle_repair_station" || it["shop"] == "bicycle" }),
    LOCKERS("ui-lock", { it["amenity"] == "locker" || it["amenity"] == "luggage_locker" || it["bicycle_parking"] == "lockers" }),
}

/** How many of one service are within reach, and how far the nearest is. */
data class MovingCount(val kind: Moving, val count: Int, val nearestM: Double?)

/** Whether routing works offline where you are: the segment is on the phone, or which one is missing and its size. */
sealed interface Readiness {
    data object Ready : Readiness
    data class Missing(val need: SegmentNeed) : Readiness
}

/** The Go tab's whole state. */
data class GoTabUi(
    val here: LatLon,
    val street: String? = null,
    val weather: CurrentWeather? = null,
    /** When rain is next expected (dry till), or when the rain falling now stops; at most one is set. */
    val dryUntil: Instant? = null,
    val rainUntil: Instant? = null,
    /** No rain in the forecast for the next twelve hours. */
    val dryAhead: Boolean = false,
    val readiness: Readiness? = null,
    val mode: Mode = Mode.WALK,
    val prefs: RoutePrefs = RoutePrefs(),
    val signs: List<GuideSign> = emptyList(),
    val recent: List<SavedPlace> = emptyList(),
    /** Null while they are being counted. */
    val moving: List<MovingCount>? = null,
    val downloading: Float? = null,
)

/**
 * The Go tab: where you are (the street, the weather, whether routing works offline here), home, work and the places
 * you went to on the gantry's signs with the time there by your mode and when to leave, the mode itself, and the
 * services within reach ("Keep it moving") from the offline place store or the map.
 */
class GoTabViewModel(private val services: Services) : ViewModel() {
    private val hub = services.go
    private val state = MutableStateFlow(GoTabUi(here = services.location.last.value?.location ?: Services.WESTERKERK))
    val ui: StateFlow<GoTabUi> = state.asStateFlow()
    private var timing: Job? = null
    private var lastLook: LatLon? = null

    init {
        viewModelScope.launch {
            combine(hub.store.home, hub.store.work, hub.store.recent, hub.store.mode, hub.store.prefs) { home, work, recent, mode, prefs ->
                Saved(home, work, recent, mode, prefs)
            }.collect { s ->
                val here = state.value.here
                val signs = signsFor(here, s.home, s.work, s.recent)
                val onSigns = signs.map { it.place }
                state.update {
                    it.copy(
                        mode = s.mode, prefs = s.prefs, signs = signs,
                        recent = s.recent.filter { r -> onSigns.none { p -> GoStore.same(p, r) } }.take(MAX_RECENT_CARDS),
                    )
                }
                timeSigns()
            }
        }
        // The first look is of where the tab opened; the location flow's first value must not start a second one.
        lastLook = state.value.here
        viewModelScope.launch { services.location.last.collect { f -> f?.let { moved(it.location) } } }
        viewModelScope.launch(Dispatchers.IO) { surroundings(state.value.here) }
    }

    private data class Saved(val home: SavedPlace?, val work: SavedPlace?, val recent: List<SavedPlace>, val mode: Mode, val prefs: RoutePrefs)

    private fun moved(at: LatLon) {
        val before = lastLook
        state.update { s -> s.copy(here = at, signs = s.signs.map { g -> g.copy(crowMetres = Geo.distance(at, g.place.at), bearing = Geo.bearing(at, g.place.at)) }) }
        if (before == null || Geo.distance(before, at) > RELOOK_M) viewModelScope.launch(Dispatchers.IO) { surroundings(at) }
    }

    /** The street, the weather, the offline readiness and the services around [at]. */
    private suspend fun surroundings(at: LatLon) {
        lastLook = at
        val street = runCatching { services.localData.streetNear(at) }.getOrNull()
        state.update { it.copy(street = street) }
        val forecast = try {
            services.weather.kept(at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val now = services.clock.instant()
        val rainEnds = forecast?.rainEnds(now)
        val nextRain = if (rainEnds == null) forecast?.nextRain(now)?.takeIf { it.isBefore(now.plusSeconds(DRY_AHEAD_S)) } else null
        val dryAhead = dryAhead(forecast, now)
        state.update { it.copy(weather = CurrentWeather.of(forecast, now), dryUntil = nextRain, rainUntil = rainEnds, dryAhead = dryAhead) }
        val tile = SegmentStore.tileName(at)
        val readiness = if (services.segments.has(tile)) Readiness.Ready
        else Readiness.Missing(SegmentNeed(tile.removeSuffix(".rd5"), SegmentOffer.size(services.context, services.segments, tile, services.connectivity.online.value)))
        state.update { it.copy(readiness = readiness) }
        val around = hub.placesAround(at, MOVING_RADIUS_M)
        state.update { it.copy(moving = count(around, at)) }
    }

    /** Plans the way to each sign by the chosen mode, so the sign shows its time; signs keep their crow-fly distance until then. */
    private fun timeSigns() {
        timing?.cancel()
        timing = viewModelScope.launch {
            val s0 = state.value
            val here = s0.here
            val vehicle = hub.store.vehicleNow(s0.mode)
            for (sign in s0.signs) {
                if (sign.crowMetres < 30) continue
                val req = RouteRequest(s0.mode, listOf(here, sign.place.at), s0.prefs, vehicle)
                val r = hub.plan(req, demo = services.location.isDemo)
                if (r !is RouteResult.Found) continue
                val best = r.best
                val leave = sign.place.arriveBy?.let { leaveFor(it, best.duration, services.clock.instant(), services.clock.zone) }
                state.update { st ->
                    st.copy(signs = st.signs.map { g -> if (GoStore.same(g.place, sign.place) && g.kind == sign.kind) g.copy(metres = best.distance, seconds = best.duration, leaveAt = leave) else g })
                }
            }
        }
    }

    fun setMode(m: Mode) {
        state.update { it.copy(mode = m) }
        viewModelScope.launch { hub.store.setMode(m) }
    }

    /** Turns a preference on or off: [shady] the shade-and-green way, [stepFree] no steps. */
    fun setPrefs(p: RoutePrefs) {
        state.update { it.copy(prefs = p) }
        viewModelScope.launch { hub.store.setPrefs(p) }
    }

    /** Downloads the routing segment for where you are. */
    fun download() {
        val need = (state.value.readiness as? Readiness.Missing)?.need ?: return
        if (state.value.downloading != null) return
        viewModelScope.launch {
            state.update { it.copy(downloading = 0f) }
            try {
                SegmentOffer.download(services.regions, services.segments, need.tile).collect { f -> state.update { it.copy(downloading = f) } }
                state.update { it.copy(readiness = Readiness.Ready, downloading = null) }
                timeSigns()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(downloading = null) }
            }
        }
    }

    fun forget(p: SavedPlace) {
        viewModelScope.launch { hub.store.forget(p) }
    }

    companion object {
        const val MOVING_RADIUS_M = 1_000.0
        const val RELOOK_M = 250.0
        const val MAX_SIGNS = 2
        const val MAX_RECENT_CARDS = 4

        /** How far ahead "dry" speaks for: no rain in the next twelve hours. */
        const val DRY_AHEAD_S = 12 * 3600L

        /**
         * Whether [forecast] says it stays dry for the next twelve hours from [now]: only a forecast whose hours cover
         * them can say so. Until 30 October one kept offline whose hours had run out said "dry", as if no rain were coming.
         */
        fun dryAhead(forecast: Forecast?, now: Instant): Boolean {
            val f = forecast ?: return false
            if (f.hourAt(now) == null || f.hourAt(now.plusSeconds(DRY_AHEAD_S - 3600)) == null) return false
            return f.rainEnds(now) == null && f.nextRain(now)?.takeIf { it.isBefore(now.plusSeconds(DRY_AHEAD_S)) } == null
        }

        /** The gantry's signs: home and work when set, then the latest places you went to, two at most. */
        fun signsFor(here: LatLon, home: SavedPlace?, work: SavedPlace?, recent: List<SavedPlace>): List<GuideSign> {
            val out = ArrayList<GuideSign>()
            fun add(kind: GuideSign.Kind, p: SavedPlace) {
                if (out.size >= MAX_SIGNS || out.any { GoStore.same(it.place, p) }) return
                out += GuideSign(kind, p, Geo.distance(here, p.at), Geo.bearing(here, p.at))
            }
            home?.let { add(GuideSign.Kind.HOME, it) }
            work?.let { add(GuideSign.Kind.WORK, it) }
            for (r in recent) add(GuideSign.Kind.RECENT, r)
            return out
        }

        /** When to leave to arrive by [by] (today, or tomorrow once today's time is over an hour gone), taking [seconds]. */
        fun leaveFor(by: LocalTime, seconds: Double, now: Instant, zone: ZoneId): Instant? {
            val today = now.atZone(zone).toLocalDate()
            var arrive = today.atTime(by).atZone(zone).toInstant()
            if (arrive.isBefore(now.minusSeconds(3600))) arrive = today.plusDays(1).atTime(by).atZone(zone).toInstant()
            val leave = arrive.minusSeconds(seconds.toLong())
            // Only worth saying within the next twelve hours.
            return leave.takeIf { it.isBefore(now.plusSeconds(12 * 3600)) && it.isAfter(now.minusSeconds(3600)) }
        }

        /** Counts each service among [places] around [at]. */
        fun count(places: List<Place>, at: LatLon): List<MovingCount> = Moving.entries.map { kind ->
            val found = places.filter { kind.test(it.tags) }
            MovingCount(kind, found.size, found.minOfOrNull { Geo.distance(at, it.location) })
        }
    }
}
