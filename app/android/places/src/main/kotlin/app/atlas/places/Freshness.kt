package app.atlas.places

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How Atlas knows when something about a place was last looked at. */
enum class CheckedBy { CHECKED, EDITED, NEVER }

/** When a place's tag (or the place) was last checked: [date] and how that is known, and how many days ago. */
data class Checked(val date: LocalDate?, val by: CheckedBy, val days: Long?, val stale: Boolean)

/**
 * How fresh what a place says is (the user, 3 November: "Freshness of tags: these information were mapped long ago,
 * or haven't been confirmed"). A tag was last checked on its own check date (`check_date:opening_hours`), else on the
 * place's (`check_date`, `survey:date`, `last_checked`), else when the place was last edited, when the store knows
 * that; never otherwise. `source:date` says when an import's source was made, not that anyone looked, and counts not.
 * What changes often (hours, prices, phone numbers, menus) is stale after a year, everything else after four.
 */
object Freshness {
    /** Days after which what changes often is stale. */
    const val CHANGING_DAYS = 365L

    /** Days after which everything else is stale. */
    const val LASTING_DAYS = 4 * 365L

    private val CHANGING_KEYS = setOf(
        "opening_hours", "collection_times", "service_times", "happy_hours", "fee", "charge", "price", "phone", "website",
        "delivery", "takeaway", "reservation", "internet_access", "operator", "brand", "self_checkout",
    )
    private val CHANGING_PREFIXES = listOf("opening_hours:", "diet:", "payment:", "currency:", "contact:", "fuel:", "socket:", "price:", "charge:")

    private val PLACE_DATES = listOf("check_date", "survey:date", "survey_date", "last_checked", "lastcheck")

    /** True when [key] holds something that changes often, stale after [CHANGING_DAYS]. */
    fun changes(key: String): Boolean = key in CHANGING_KEYS || CHANGING_PREFIXES.any { key.startsWith(it) }

    /** Days after which [key] (or the place, for null) is stale. */
    fun staleAfter(key: String?): Long = if (key != null && changes(key)) CHANGING_DAYS else LASTING_DAYS

    /**
     * When [key] of a place with [tags] was last checked (the place as a whole for null), seen from [today]. [edited]
     * is when the place was last edited, when the store knows it.
     */
    fun of(tags: Map<String, String>, key: String? = null, today: LocalDate, edited: LocalDate? = null): Checked {
        val own = key?.let { k -> date(tags["check_date:$k"]) }
        val place = PLACE_DATES.mapNotNull { date(tags[it]) }.maxOrNull()
        // a key's own date is the one that counts, the place's when it is later (a survey checks everything)
        val checked = listOfNotNull(own, place).maxOrNull()
            ?: if (key == null) tags.entries.filter { it.key.startsWith("check_date:") }.mapNotNull { date(it.value) }.maxOrNull() else null
        val (d, by) = when {
            checked != null -> checked to CheckedBy.CHECKED
            edited != null -> edited to CheckedBy.EDITED
            else -> null to CheckedBy.NEVER
        }
        val days = d?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) }
        // never checked counts as stale only for what changes often: a lake's name is not doubted for want of a date
        val stale = if (days != null) days > staleAfter(key) else key != null && changes(key) && key in tags
        return Checked(d, by, days, stale)
    }

    /** "2025-03-17", "2025-03" or "2017" as a day (the first of the month or year), else null. */
    fun date(value: String?): LocalDate? {
        val v = value?.trim()?.takeIf { it.length >= 4 } ?: return null
        val m = DATE.matchEntire(v) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].ifEmpty { "1" }.toInt()
        val d = m.groupValues[3].ifEmpty { "1" }.toInt()
        return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
    }

    private val DATE = Regex("""(\d{4})(?:-(\d{1,2})(?:-(\d{1,2}))?)?(?:[T ].*)?""")
}
