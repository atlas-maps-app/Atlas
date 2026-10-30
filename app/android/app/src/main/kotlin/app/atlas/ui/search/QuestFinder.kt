package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.places.FactKind
import app.atlas.places.Facts
import app.atlas.places.OpeningHoursContext
import app.atlas.search.PlaceRecord
import app.atlas.search.covers
import app.atlas.services.PlaceOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.LocalDateTime
import java.util.Locale

/** A quest at a place around: the place, the question, and how far it is. */
data class PlaceQuest(val place: Place, val quest: Quest, val distanceM: Double) {
    /** Unique per place and question. */
    val id: String get() = place.key + "|" + quest.key
}

/**
 * The questions about places around that a passer-by can answer at a glance ("Wi-Fi?", "a terrace?", "step-free?"):
 * from the region on the phone (every tag, so only what is truly unmapped is asked), else online from Overpass, never
 * from the map's labels alone (they cannot say what is mapped). The nearest first, the most important question of each
 * place, none the phone has answered before.
 */
object QuestFinder {
    const val RADIUS_M = 400.0
    private const val MAX = 40
    private const val PER_PLACE = 2

    suspend fun near(services: Services, at: LatLon, lang: String, online: Boolean): Pair<List<PlaceQuest>, PlaceOrigin?> = withContext(Dispatchers.Default) {
        val store = services.placeStores
        val now = LocalDateTime.now(services.clock)
        val answered = services.saved.answers.value.mapTo(HashSet()) { it.placeKey + "|" + it.key }
        if (store.covers(at)) {
            val places = store.inBox(BBox.around(at, RADIUS_M)).map { it.place }
            return@withContext questsOf(places, at, lang, now, answered) to PlaceOrigin.STORE
        }
        if (!online) return@withContext emptyList<PlaceQuest>() to null
        val ql = "[out:json][timeout:25];nwr(around:${RADIUS_M.toInt()},%.6f,%.6f)[name][~\"^(amenity|shop|tourism|leisure|craft)$\"~\".\"];out tags center 300;"
            .format(Locale.ROOT, at.lat, at.lon)
        val places = try {
            withTimeoutOrNull(30_000) { withContext(Dispatchers.IO) { services.overpass.places(ql) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        } ?: return@withContext emptyList<PlaceQuest>() to PlaceOrigin.ONLINE
        questsOf(places, at, lang, now, answered) to PlaceOrigin.ONLINE
    }

    /** The quests of [places] seen from [at] at [now], nearest first, skipping those in [answered] ("key|tag"). */
    fun questsOf(places: List<Place>, at: LatLon, lang: String, now: LocalDateTime, answered: Set<String>): List<PlaceQuest> {
        val out = ArrayList<PlaceQuest>()
        for (p in places) {
            if (p.id == null || p.tags["name"] == null || Categories.of(p.tags) == null) continue
            val d = Geo.distance(at, p.location)
            if (d > RADIUS_M) continue
            val facts = Facts.of(p.tags, lang, now, OpeningHoursContext.Default, PlaceRecord.geometryOf(p))
            Quests.of(facts, max = PER_PLACE).filter { q -> (p.key + "|" + q.key) !in answered }.forEach { out += PlaceQuest(p, it, d) }
        }
        // Near first, in bands of 50 m; within a band, what can be seen from the street before what needs a visit.
        return out.sortedWith(compareBy<PlaceQuest>({ (it.distanceM / BAND_M).toInt() }, { seenFromOutside(it.quest.fact.kind) }, { it.distanceM })).take(MAX)
    }

    private const val BAND_M = 50.0

    /** Kinds a passer-by sees from the pavement first (a terrace, a step, a lamp), those needing a visit last. */
    private val OUTSIDE_FIRST = listOf(
        FactKind.OUTDOOR_SEATING, FactKind.WHEELCHAIR, FactKind.LIT, FactKind.TAKEAWAY, FactKind.DRIVE_THROUGH,
        FactKind.INTERNET, FactKind.DIET, FactKind.RESERVATION, FactKind.TOILETS,
    )

    private fun seenFromOutside(k: FactKind): Int = OUTSIDE_FIRST.indexOf(k).let { if (it < 0) OUTSIDE_FIRST.size else it }
}
