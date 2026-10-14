package app.atlas.ui.eat

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.OpeningHours
import app.atlas.ui.kit.WalkTime
import java.time.LocalDateTime
import java.time.LocalTime

/** The Art Deco card's tabs: what kind of evening, each read from real tags. */
enum class BarTab {
    COCKTAILS, WINE, BEER, PUBS, GARDENS, LIVE_MUSIC;

    /** True when [p] belongs under this tab. */
    fun has(p: EatPlace): Boolean = when (this) {
        COCKTAILS -> p.kind == EatKind.BAR && p.tags["bar"] != "wine" || BarModel.drinks(p, "cocktail") || p.yes("cocktails")
        WINE -> p.tags["bar"] == "wine" || BarModel.drinks(p, "wine") || "wine" in p.cuisines || BarModel.named(p, BarModel.WINE_WORDS)
        BEER -> p.tags.containsKey("brewery") || p.yes("microbrewery") || p.yes("craft_beer") || BarModel.drinks(p, "craft_beer") ||
            p.kind == EatKind.BIERGARTEN || BarModel.named(p, BarModel.BEER_WORDS)
        PUBS -> p.kind == EatKind.PUB
        GARDENS -> p.kind == EatKind.BIERGARTEN || p.yes("beer_garden") || p.outside
        LIVE_MUSIC -> p.yes("live_music") || p.yes("karaoke") || p.tags["music:live"] == "yes"
    }
}

/** The board's round toggles: moods that narrow any tab. */
enum class BarMood {
    HAPPY_HOUR, TERRACE, MUSIC, SPORT, QUIET;

    /** True when [p] suits the mood at [at] (happy hour: its `happy_hours` say it is on then). */
    fun has(p: EatPlace, at: LocalDateTime, area: EatArea): Boolean = when (this) {
        HAPPY_HOUR -> OpeningHours.parseOrNull(p.tags["happy_hours"])?.status(at, area.hours)?.isOpen == true
        TERRACE -> p.outside
        MUSIC -> p.yes("live_music") || p.yes("karaoke") || p.tags["music"]?.let { it != "no" } == true
        SPORT -> p.tags.containsKey("sport") || p.yes("television") || p.tags["tv"] == "yes"
        QUIET -> p.tags["music"] == "no" || p.tags["background_music"] == "no" || p.tags["live_music"] == "no" || p.tags["noise"] == "quiet"
    }
}

/** One line of a venue's italic hook, before words: what its tags say is special about it. */
sealed interface Hook {
    data object LiveMusic : Hook
    data object Karaoke : Hook
    data object Rooftop : Hook
    data object BeerGarden : Hook
    data object Terrace : Hook
    data object Cocktails : Hook
    data object Brews : Hook
    data class OnTap(val beers: List<String>) : Hook
    data class Sport(val sport: String) : Hook
    data object OpenFire : Hook
    data object Lgbtq : Hook
    data object CraftBeer : Hook
    data class Until(val at: LocalDateTime) : Hook
    data class Says(val text: String) : Hook

    /** Nothing special mapped: where it is, "on Prinsengracht". */
    data class On(val street: String) : Hook
}

/** A venue as the card lists it: the place, its hook and whether it is open at the card's time. */
data class Venue(val place: EatPlace, val hooks: List<Hook>, val openness: Openness, val closes: LocalDateTime?)

/** The crawl: up to three stops in walking order from here, and the whole walk in minutes. */
data class Crawl(val stops: List<EatPlace>, val minutes: Int)

/** What the Art Deco card shows at a time: counts, the last call, the venues of a tab and mood, and the crawl. */
data class BarCard(
    val open: Int,
    val countsOpen: Boolean,
    val lastCall: LocalDateTime?,
    val tabCounts: Map<BarTab, Int>,
    val moodCounts: Map<BarMood, Int>,
    val venues: List<Venue>,
    val total: Int,
)

/** The bar card's logic: tabs, moods, hooks, last call and the three-stop crawl, all from real tags and hours. */
object BarModel {
    internal val WINE_WORDS = listOf("wijn", "wine", "vino", "vin", "vinoteca", "enoteca", "wein")
    internal val BEER_WORDS = listOf("bier", "beer", "brouwerij", "brewery", "brewing", "tap", "taphouse", "taproom", "proeflokaal")

    /**
     * A word of [p]'s name is one of [stems], or begins with one of four letters or more ("Wijnbar Boelen",
     * "Proeflokaal Arendsnest", "Beer Temple"); "tap" names a tap only as a word of its own, never "tapas".
     */
    internal fun named(p: EatPlace, stems: List<String>): Boolean {
        val words = p.name.lowercase().split(NON_LETTER).filter { it.isNotEmpty() }
        return words.any { w -> stems.any { s -> w == s || (s.length >= 4 && w.startsWith(s)) } }
    }

    private val NON_LETTER = Regex("[^\\p{L}]+")

    /** `drink:<what>` says it is served. */
    internal fun drinks(p: EatPlace, what: String): Boolean =
        p.tags["drink:$what"]?.let { it == "yes" || it == "served" || it == "only" } == true

    /** The bars, pubs, beer gardens and clubs of [area] (the card's places). */
    fun bars(area: EatArea): List<EatPlace> = EatSets.bars(area.places)

    /** The hooks of [p], most telling first, at most [max]. */
    fun hooks(p: EatPlace, at: LocalDateTime, area: EatArea, max: Int = 2): List<Hook> {
        val t = p.tags
        val out = ArrayList<Hook>(4)
        if (p.yes("live_music")) out += Hook.LiveMusic
        if (p.yes("karaoke")) out += Hook.Karaoke
        if (t["outdoor_seating"] == "roof" || t["roof_terrace"] == "yes") out += Hook.Rooftop
        if (p.kind == EatKind.BIERGARTEN || p.yes("beer_garden")) out += Hook.BeerGarden
        if (p.yes("microbrewery") || t["craft_beer"] == "brewed_on_site") out += Hook.Brews
        if (p.yes("cocktails") || drinks(p, "cocktail")) out += Hook.Cocktails
        t["sport"]?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Hook.Sport(it) }
        if (p.yes("craft_beer") || drinks(p, "craft_beer")) out += Hook.CraftBeer
        if (t["real_fire"]?.let { it == "yes" || it == "open" } == true) out += Hook.OpenFire
        if (t["lgbtq"] == "primary" || t["lgbtq"] == "only") out += Hook.Lgbtq
        t["brewery"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "yes" && it != "various" }?.takeIf { it.isNotEmpty() }
            ?.let { out += Hook.OnTap(it.take(2)) }
        if (out.none { it == Hook.Rooftop || it == Hook.BeerGarden } && p.outside) out += Hook.Terrace
        // A late closing time is a hook of its own: "till 3:00" (one o'clock is ordinary).
        p.closesAt(at, area.hours)?.takeIf { it.toLocalTime() in LocalTime.of(1, 59)..LocalTime.of(6, 0) }?.let { out += Hook.Until(it) }
        if (out.isEmpty()) {
            (t["description:en"] ?: t["description"])?.trim()?.takeIf { it.length in 4..44 }?.let { out += Hook.Says(it.trimEnd('.')) }
        }
        if (out.isEmpty()) t["addr:street"]?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Hook.On(it) }
        return out.take(max)
    }

    /**
     * The card at [at] for [tab] (null: every venue) and [moods] (all must hold): open venues first (nearest first),
     * then those without hours, then closed ones.
     */
    fun card(area: EatArea, at: LocalDateTime, tab: BarTab?, moods: Set<BarMood>, limit: Int = 24): BarCard {
        val all = bars(area)
        val countsOpen = !area.sketchOnly && all.any { it.hasHours }
        val open = all.filter { it.openness(at, area.hours).isOpen }
        val inTab = if (tab == null) all else all.filter { tab.has(it) }
        val chosen = inTab.filter { p -> moods.all { it.has(p, at, area) } }
        val rank = { p: EatPlace ->
            when (p.openness(at, area.hours)) {
                Openness.OPEN, Openness.CLOSING_SOON -> 0
                Openness.UNKNOWN -> 1
                Openness.CLOSED -> 2
            }
        }
        val sorted = chosen.sortedWith(compareBy<EatPlace> { rank(it) }.thenBy { it.distanceM })
        return BarCard(
            open = if (countsOpen) open.size else all.size,
            countsOpen = countsOpen,
            lastCall = if (countsOpen) EatSets.lastCall(open, area, at) else null,
            tabCounts = BarTab.entries.associateWith { t -> all.count { t.has(it) } },
            moodCounts = BarMood.entries.associateWith { m -> inTab.count { m.has(it, at, area) } },
            venues = sorted.take(limit).map { p ->
                Venue(p, hooks(p, at, area), p.openness(at, area.hours), p.closesAt(at, area.hours))
            },
            total = chosen.size,
        )
    }

    /**
     * The crawl through [picks] (at most three), ordered to walk the least from [from]: every order is tried (six at
     * most), so the walk is the shortest there is. With no picks, the three nearest open venues of [venues].
     */
    fun crawl(from: LatLon, picks: List<EatPlace>, venues: List<Venue>): Crawl {
        val stops = picks.take(3).ifEmpty {
            venues.filter { it.openness.isOpen }.map { it.place }.take(3).ifEmpty { venues.map { it.place }.take(3) }
        }
        if (stops.isEmpty()) return Crawl(emptyList(), 0)
        var best = stops
        var bestM = Double.MAX_VALUE
        for (order in permutations(stops)) {
            var m = Geo.distance(from, order[0].location)
            for (i in 1 until order.size) m += Geo.distance(order[i - 1].location, order[i].location)
            if (m < bestM) {
                bestM = m
                best = order
            }
        }
        return Crawl(best, maxOf(1, Math.round(WalkTime.seconds(bestM) / 60.0).toInt()))
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.indices.flatMap { i -> permutations(xs.filterIndexed { j, _ -> j != i }).map { listOf(xs[i]) + it } }
}
