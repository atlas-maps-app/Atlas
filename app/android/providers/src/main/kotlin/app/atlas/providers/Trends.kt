package app.atlas.providers

import app.atlas.model.Place
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.max

/** What OSM says about how new or how current a place is. */
data class Freshness(val kind: Kind, val date: LocalDate?, val key: String?) {
    enum class Kind {
        /** `opening_date` in the future. */
        OPENING_SOON,

        /** Opened (`opening_date` / `start_date`) within the window. */
        NEW,

        /** Created in OSM within the window (version 1, from an `out meta` answer): newly mapped, perhaps not newly opened. */
        NEWLY_MAPPED,

        /** Surveyed (`check_date`) within the window: the facts are current, the place is not necessarily new. */
        RECENTLY_CHECKED,

        /** Tagged with a lifecycle prefix (`disused:`, `was:`, `demolished:` …): it has closed. */
        CLOSED,
    }
}

/**
 * "What's rising" and "what's new": rising from the pace and direction of reviews, new from OSM dates.
 * Every function is pure and takes "now", so the screens and the tests agree.
 */
object Trends {
    private const val DAY_S = 86_400L

    /**
     * The trend of [reviews] at [now]. Rising means the last 90 days brought at least three reviews at 1.5 times the pace
     * of the 275 days before, or the stars of the last 90 days beat the older ones by half a star or more.
     * Machine-written reviews are left out.
     */
    fun fromReviews(reviews: List<Review>, now: Instant): Trend? {
        val dated = reviews.filter { !it.generated && it.time != null && !it.time.isAfter(now.plusSeconds(DAY_S)) }
        if (dated.isEmpty()) return null
        val age = { r: Review -> (now.epochSecond - r.time!!.epochSecond) / DAY_S.toDouble() }
        val last30 = dated.count { age(it) <= 30 }
        val last90 = dated.filter { age(it) <= 90 }
        val before = dated.filter { age(it) > 90 && age(it) <= 365 }
        val last365 = last90.size + before.size
        val recentPace = last90.size / 3.0
        // A floor of one review a year keeps a first burst from looking infinitely fast.
        val oldPace = max(before.size / (275.0 / 30.0), 1.0 / 12.0)
        val velocity = recentPace / oldPace
        val momentum = if (last90.count { it.stars != null } >= 2 && before.count { it.stars != null } >= 2) {
            last90.mapNotNull { it.stars }.average() - before.mapNotNull { it.stars }.average()
        } else 0.0
        val first = dated.minOf { it.time!! }
        val last = dated.maxOf { it.time!! }
        val rising = (last90.size >= 3 && velocity >= 1.5) || (momentum >= 0.5 && last90.size >= 2)
        return Trend(
            last30 = last30, last90 = last90.size, last365 = last365, total = dated.size,
            velocity = velocity, momentum = momentum, rising = rising,
            newcomer = (now.epochSecond - first.epochSecond) <= 90 * DAY_S,
            firstReview = first, lastReview = last,
        )
    }

    /**
     * How fresh [place] is at [today], looking back [months]: a future `opening_date`, a recent `opening_date` or
     * `start_date`, a recent `check_date`, or a lifecycle prefix that says it closed. The strongest signal wins.
     */
    fun freshness(place: Place, today: LocalDate, months: Long = 6): Freshness? {
        val tags = place.tags
        if (MAIN_KEYS.none { it in tags }) {
            tags.keys.firstOrNull { k -> CLOSED_PREFIXES.any { k.startsWith(it) } && k.substringAfter(':') in MAIN_KEYS }
                ?.let { return Freshness(Freshness.Kind.CLOSED, null, it) }
        }
        val from = today.minusMonths(months)
        parseDate(tags["opening_date"])?.let { d ->
            if (d.isAfter(today)) return Freshness(Freshness.Kind.OPENING_SOON, d, "opening_date")
            if (!d.isBefore(from)) return Freshness(Freshness.Kind.NEW, d, "opening_date")
        }
        parseDate(tags["start_date"])?.let { d ->
            if (!d.isBefore(from) && !d.isAfter(today)) return Freshness(Freshness.Kind.NEW, d, "start_date")
        }
        place.extra["osm:version"]?.toIntOrNull()?.let { v ->
            val created = place.extra["osm:timestamp"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (v == 1 && created != null) {
                val d = created.atZone(ZoneOffset.UTC).toLocalDate()
                if (!d.isBefore(from)) return Freshness(Freshness.Kind.NEWLY_MAPPED, d, "version")
            }
        }
        val checked = listOf("check_date", "survey:date", "check_date:opening_hours").mapNotNull { k -> parseDate(tags[k])?.let { k to it } }.maxByOrNull { it.second }
        if (checked != null && !checked.second.isBefore(from) && !checked.second.isAfter(today)) {
            return Freshness(Freshness.Kind.RECENTLY_CHECKED, checked.second, checked.first)
        }
        return null
    }

    /**
     * Overpass QL for places matching [selector] (for example `["amenity"~"^(restaurant|cafe|bar)$"]`) created in [area]
     * within the last [days]; [newlyCreated] runs it and keeps each element's `out meta` fields through [placeWithMeta],
     * so [freshness] sees them.
     */
    fun createdQuery(selector: String, area: OverpassArea, now: Instant, days: Long = 90): String =
        OverpassQl.createdSince(selector, area, now.minus(days, ChronoUnit.DAYS))

    /** Places matching [selector] that were created in [area] within the last [days], with their creation time kept. */
    suspend fun newlyCreated(overpass: Overpass, selector: String, area: OverpassArea, now: Instant, days: Long = 90): List<Place> =
        overpass.query(createdQuery(selector, area, now, days)).elements.mapNotNull(::placeWithMeta)

    /** An element from an `out meta` answer as a place that remembers its version and edit time. */
    fun placeWithMeta(e: OverpassElement): Place? = e.toPlace()?.let { p ->
        if (e.version == 0) p else p.copy(extra = p.extra + listOfNotNull("osm:version" to e.version.toString(), e.timestamp?.let { "osm:timestamp" to it }))
    }

    /**
     * OSM dates as mappers write them: "2026-05-12", "2026-05", "2026", "~2026", "early 2026", "2026-05-12..2026-05-14".
     * A month or year means its first day; anything "before …" is not a usable date.
     */
    fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().removePrefix("~").removePrefix("early ").removePrefix("mid ").removePrefix("late ").substringBefore("..").trim()
        if (t.startsWith("before") || t.startsWith("after")) return null
        DAY.find(t)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        MONTH.find(t)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1) }.getOrNull()
        }
        YEAR.find(t)?.let { m -> return LocalDate.of(m.groupValues[1].toInt(), 1, 1) }
        return null
    }

    private val DAY = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
    private val MONTH = Regex("""^(\d{4})-(\d{2})$""")
    private val YEAR = Regex("""^(\d{4})$""")

    private val CLOSED_PREFIXES = listOf("disused:", "was:", "abandoned:", "demolished:", "razed:", "removed:", "closed:")
    private val MAIN_KEYS = setOf("amenity", "shop", "tourism", "leisure", "craft", "office", "healthcare")
}
