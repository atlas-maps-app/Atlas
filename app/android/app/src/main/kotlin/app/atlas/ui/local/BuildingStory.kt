package app.atlas.ui.local

import app.atlas.model.Place
import app.atlas.places.Localise
import app.atlas.places.Presets

/** The year of a building from its `start_date`, as far as the tag says it. */
sealed interface BuiltYear {
    /** A year, [approx] when the tag says circa, before or a range. */
    data class Year(val year: Int, val approx: Boolean) : BuiltYear

    /** Only a century is known ("C17" is the 17th). */
    data class Century(val century: Int) : BuiltYear

    /** No year, or the BAG's code for an unknown one (1005 in the Netherlands). */
    data object Unknown : BuiltYear
}

/** Heritage protection from the tags: the level, who protects it and its number there. */
data class Heritage(val level: String?, val operator: String?, val ref: String?, val website: String?, val designation: String? = null)

/** What the tags and Wikidata say about a building. */
data class BuildingFacts(
    val place: Place,
    val name: String?,
    val kind: String?,
    val built: BuiltYear,
    val architect: String?,
    val style: String?,
    val heritage: Heritage?,
    /** What it used to be: "cafe", "school" (from `disused:*`, `was:*` and `old_*` tags). */
    val former: List<String>,
    val levels: Int?,
    val wikidata: String?,
    val description: String?,
    /** The year the map's tags give when Wikidata disagrees (the register may date a rebuild, not the building). */
    val mappedYear: Int? = null,
)

/** A building's story: year, architect, heritage and former use, from OpenStreetMap tags (and Wikidata where it knows the building). */
object BuildingStory {
    /** The Dutch building register (BAG) writes 1005 where the year is not known; OpenStreetMap imports kept it. */
    const val BAG_UNKNOWN_YEAR = 1005

    /** Reads a `start_date` such as "1620", "1620-05-01", "~1620", "1850..1860", "before 1900", "C17"; 1005 and nonsense are unknown. */
    fun year(startDate: String?): BuiltYear {
        val t = startDate?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return BuiltYear.Unknown
        Regex("""^c(\d{1,2})(?:\.\.c?\d{1,2})?$""").find(t)?.let { return BuiltYear.Century(it.groupValues[1].toInt()) }
        val m = Regex("""(\d{4})""").find(t) ?: return BuiltYear.Unknown
        val y = m.groupValues[1].toInt()
        if (y == BAG_UNKNOWN_YEAR || y < 1000 || y > 2100) return BuiltYear.Unknown
        val approx = t.startsWith("~") || t.startsWith("c") || t.startsWith("before") || t.startsWith("after") || ".." in t || t.length > 10 && t.contains("/")
        return BuiltYear.Year(y, approx)
    }

    /** A year differing by more than this from Wikidata's inception is the register's (a rebuild's) and not the building's. */
    const val DISAGREE_YEARS = 25

    fun of(place: Place, lang: String?, wikidata: Etymology.BuildingItem? = null): BuildingFacts {
        val tags = place.tags
        var built = year(tags["start_date"] ?: tags["building:start_date"])
        var mapped: Int? = null
        val wdYear = wikidata?.year?.takeIf { it != BAG_UNKNOWN_YEAR }
        if (wdYear != null) {
            val tagged = (built as? BuiltYear.Year)?.year
            if (tagged == null || kotlin.math.abs(tagged - wdYear) > DISAGREE_YEARS || built is BuiltYear.Century) {
                if (tagged != null) mapped = tagged
                built = BuiltYear.Year(wdYear, approx = false)
            }
        }
        val former = tags.entries.filter { (k, _) -> k.startsWith("disused:") || k.startsWith("was:") || k.startsWith("old_") && k != "old_name" && k != "old_ref" }
            .mapNotNull { (k, v) -> if (k.startsWith("old_name")) null else v.replace('_', ' ').takeIf { v != "yes" } ?: k.substringAfter(':').replace('_', ' ') }
            .distinct()
        val heritage = if (tags.keys.any { it == "heritage" || it.startsWith("heritage:") || it == "ref:rce" || it == "ref:mhs" || it == "historic" })
            Heritage(tags["heritage"]?.takeIf { it != "yes" }, tags["heritage:operator"], tags["ref:rce"] ?: tags["ref:mhs"] ?: tags["heritage:ref"], tags["heritage:website"], wikidata?.heritage)
        else if (wikidata?.heritage != null) Heritage(null, null, null, null, wikidata.heritage)
        else null
        return BuildingFacts(
            place, Localise.name(tags, lang), Presets.match(tags)?.name(lang) ?: tags["building"]?.takeIf { it != "yes" }?.replace('_', ' '),
            built, tags["architect"] ?: wikidata?.architect, tags["building:architecture"]?.replace('_', ' ') ?: wikidata?.style, heritage, former,
            tags["building:levels"]?.toIntOrNull(), tags["wikidata"] ?: tags["building:wikidata"], tags["description"], mapped,
        )
    }
}
