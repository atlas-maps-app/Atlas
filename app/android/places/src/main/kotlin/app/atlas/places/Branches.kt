package app.atlas.places

import java.util.Locale

/**
 * Opening hours worked out for a branch that has none mapped, from its brand's other branches: never confirmed, and
 * shown as such. [hours] is the result as an opening_hours value; [from] how many branches' hours it comes from;
 * [agreement] the share of them whose own weekly hours are exactly these (1 when all agree).
 */
data class InferredHours(val hours: OpeningHours, val from: Int, val agreement: Double)

/**
 * A brand's hours from its branches (the user, 3 November: "Brand opening times are missing at many locations, but
 * they are often the same to other locations around them. There can be a median calculated and non confirmed opening
 * times attributed, where applicable"). For each day of the week a branch opens at its day's first opening and closes
 * at its last closing, a lunch break left out; the day is closed when most branches close it, else it opens at the
 * median of their openings and closes at the median of their closings. The median taken is the lower one, so every
 * time said is a time some branch keeps. Holidays and dated rules are left out, as a door plaque leaves them out.
 */
object BrandHours {
    /** Fewer of a kind's places around with readable weekly hours than this say nothing of the others ([fromWeeks]). */
    const val MIN_BRANCHES = 3

    /**
     * Fewer of a brand's branches with readable weekly hours than this say nothing of the others: one is enough, a chain
     * keeping one plan (the owner, 6 November, of two Birkenstock stores, one mapped closed and one with no hours: "if
     * the other branch is closed the other one is probably closed too"). A guess all the same, shown as one.
     */
    const val MIN_SIBLINGS = 1

    /** Hours worked out from [branches]' opening_hours values, or null when fewer than [min] of them can be read. */
    fun infer(branches: List<String>, min: Int = MIN_SIBLINGS): InferredHours? =
        fromWeeks(branches.mapNotNull { raw -> OpeningHours.parseOrNull(raw)?.let { weekOf(it) } }, min)

    /**
     * The same from weeks already read ([weekOf]): for places whose hours are read once and asked of many times, the
     * places of a kind around each of several others; fewer than [min] say nothing.
     */
    fun fromWeeks(weeks: List<List<Pair<Int, Int>?>>, min: Int = MIN_BRANCHES): InferredHours? {
        if (weeks.size < min) return null
        val days = (0 until 7).map { d ->
            val open = weeks.mapNotNull { it[d] }
            if (open.size * 2 <= weeks.size) null else lowerMedian(open.map { it.first }) to lowerMedian(open.map { it.second })
        }
        if (days.all { it == null }) return null
        val raw = format(days)
        val hours = OpeningHours.parseOrNull(raw) ?: return null
        return InferredHours(hours, weeks.size, weeks.count { it == days }.toDouble() / weeks.size)
    }

    /**
     * Each day's first opening and last closing from Monday, minutes from its midnight (a closing past midnight past
     * 1440), null for a day closed; null for hours open on no day of a plain week.
     */
    fun weekOf(h: OpeningHours): List<Pair<Int, Int>?>? {
        if (h.isAlwaysOpen) return List(7) { 0 to 1440 }
        val table = h.weekTable()
        val out = table.map { spans ->
            val open = spans.filter { it.state == OpenState.OPEN }
            if (open.isEmpty()) null else open.minOf { it.start } to open.maxOf { it.end }
        }
        return out.takeIf { days -> days.any { it != null } }
    }

    private fun lowerMedian(values: List<Int>): Int = values.sorted()[(values.size - 1) / 2]

    /** "Mo-Fr 08:00-22:00; Sa 09:00-22:00; Su 10:00-20:00": days with the same hours joined, closed days left out. */
    internal fun format(days: List<Pair<Int, Int>?>): String {
        val parts = ArrayList<String>()
        var d = 0
        while (d < 7) {
            val span = days[d]
            var e = d
            while (e + 1 < 7 && days[e + 1] == span) e++
            if (span != null) {
                val range = if (e == d) DAYS[d] else "${DAYS[d]}-${DAYS[e]}"
                parts += "$range ${clock(span.first)}-${clock(if (span.second > 1440) span.second - 1440 else span.second)}"
            }
            d = e + 1
        }
        return if (parts.size == 1 && parts[0] == "Mo-Su 00:00-24:00") "24/7" else parts.joinToString("; ")
    }

    /** A time of day; a closing past midnight is written as the hour it falls on ("18:00-02:00"). */
    private fun clock(minutes: Int): String = String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
}

/**
 * A brand's own website for a branch that maps none (the user, 3 November: "Some locations have them, usually a
 * specific parameter for the location. If there is no website mapped the brands website without that parameter can
 * be displayed instead"), from the tags of the brand's other branches: a `brand:website` mapped on them, else the
 * site their own websites share, the page of one branch left out. The country's own site comes first
 * ("starbucks.nl" in the Netherlands, where branches link .nl, .com and .co.uk pages); else most of the branches
 * with a website must share one, two at least. Social pages and booking sites say nothing about the brand.
 */
object BrandSite {
    fun of(branches: List<Map<String, String>>, country: String? = null): String? {
        branches.mapNotNull { t -> t["brand:website"]?.let(::origin)?.takeIf { it.domain !in ELSEWHERE } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { return it.key.origin }
        val sites = branches.mapNotNull { mapped(it)?.let(::origin) }.filter { it.domain !in ELSEWHERE }
        if (sites.isEmpty()) return null
        val byDomain = sites.groupBy { it.domain }
        val local = country?.lowercase()?.let { c -> byDomain.keys.filter { it.endsWith(".$c") }.maxByOrNull { byDomain.getValue(it).size } }
        val domain = local ?: byDomain.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 2 && it.value.size * 2 >= sites.size }?.key ?: return null
        // the form they write it in most, the domain itself or its www before a subdomain, https before http
        return byDomain.getValue(domain).groupingBy { it.origin }.eachCount().entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { e -> if (e.key.substringAfter("://").removePrefix("www.") == domain) 0 else 1 }
                .thenByDescending { it.key.startsWith("https") },
        ).first().key
    }

    /** The website a branch maps: its own, else its contact website. */
    fun mapped(tags: Map<String, String>): String? = (tags["website"] ?: tags["contact:website"] ?: tags["url"])?.trim()?.takeIf { it.isNotEmpty() }

    /** A web address's scheme and host, and the domain that names its owner ("bestellen.febo.nl" -> "febo.nl"). */
    internal data class Origin(val origin: String, val domain: String)

    internal fun origin(url: String): Origin? {
        val m = ORIGIN.find(url.trim()) ?: return null
        val scheme = (m.groupValues[1].ifEmpty { "https" }).lowercase()
        val host = m.groupValues[2].lowercase().trimEnd('.')
        val labels = host.split('.')
        if (labels.size < 2) return null
        // two labels name the owner, three under a country's second level ("starbucks.co.uk")
        val keep = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
        return Origin("$scheme://$host", labels.takeLast(keep).joinToString("."))
    }

    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "gv")

    /** Sites that are someone else's: social networks, maps, booking and delivery platforms, link pages. */
    private val ELSEWHERE = setOf(
        "facebook.com", "instagram.com", "twitter.com", "x.com", "tiktok.com", "linkedin.com", "youtube.com", "google.com",
        "goo.gl", "tripadvisor.com", "tripadvisor.nl", "thuisbezorgd.nl", "ubereats.com", "deliveroo.nl", "linktr.ee",
        "yelp.com", "booking.com", "foursquare.com", "wa.me", "eet.nu", "openstreetmap.org",
    )

    private val ORIGIN = Regex("""^(?:(https?)://)?([a-z0-9][a-z0-9.-]*\.[a-z]{2,})(?::\d+)?(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
}
