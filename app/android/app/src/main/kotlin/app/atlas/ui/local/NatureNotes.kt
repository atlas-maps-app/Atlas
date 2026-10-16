package app.atlas.ui.local

import app.atlas.geo.Geo
import app.atlas.model.Place
import java.time.LocalDate

/** The sections of the naturalist's notebook, as its index tabs. */
enum class NatureKind(val id: String, val filter: String, val colour: Long, val symbol: String) {
    TREES("trees", "natural=tree", 0xFF6E9E5A, "nature.tree"),
    WATER("water", "natural=spring or (natural=water and name) or ((waterway=river or waterway=canal or waterway=stream) and name)", 0xFF7FA7C4, "nature.spring"),
    BIRDS("birds", "leisure=bird_hide or (leisure=nature_reserve and name) or (natural=wetland and name)", 0xFFC98A2B, "nature.birds"),
    GARDENS("gardens", "(leisure=garden and name and not access=private and not access=no) or landuse=allotments", 0xFFB5532E, "nature.garden"),
    WILD("wild", "leisure=nature_reserve or boundary=protected_area or natural=peak or natural=heath or (natural=wood and name)", 0xFF5E4A7A, "nature.peak");

    companion object {
        fun of(id: String?): NatureKind = entries.firstOrNull { it.id == id } ?: TREES
    }
}

/** A tree's genus with its names and the leaf the notebook presses for it. */
class TreeName(val genus: String, val en: String, val nl: String, val art: String, val autumn: IntRange? = null, val blossom: IntRange? = null)

/** What the notebook calls the trees of the north-west European towns, by Latin genus. */
object Trees {
    private val NAMES = listOf(
        TreeName("Platanus", "plane tree", "plataan", "nature.plane-leaf", 10..11),
        TreeName("Ginkgo", "ginkgo", "ginkgo", "nature.ginkgo-leaf", 10..11),
        TreeName("Ulmus", "elm", "iep", "nature.elm-leaf", 10..11),
        TreeName("Tilia", "linden", "linde", "nature.elm-leaf", 10..10, 6..7),
        TreeName("Quercus", "oak", "eik", "nature.elm-leaf", 10..11),
        TreeName("Acer", "maple", "esdoorn", "nature.plane-leaf", 10..11),
        TreeName("Fagus", "beech", "beuk", "nature.elm-leaf", 10..11),
        TreeName("Aesculus", "horse chestnut", "paardenkastanje", "nature.plane-leaf", 9..10, 4..5),
        TreeName("Betula", "birch", "berk", "nature.elm-leaf", 10..10),
        TreeName("Salix", "willow", "wilg", "nature.elm-leaf", 10..11),
        TreeName("Fraxinus", "ash", "es", "nature.elm-leaf", 10..10),
        TreeName("Populus", "poplar", "populier", "nature.elm-leaf", 10..11),
        TreeName("Alnus", "alder", "els", "nature.elm-leaf"),
        TreeName("Carpinus", "hornbeam", "haagbeuk", "nature.elm-leaf", 10..11),
        TreeName("Prunus", "cherry", "kers", "nature.elm-leaf", 10..10, 3..4),
        TreeName("Malus", "apple", "appel", "nature.elm-leaf", 10..10, 4..5),
        TreeName("Pinus", "pine", "den", "nature.tree"),
        TreeName("Picea", "spruce", "spar", "nature.tree"),
        TreeName("Taxus", "yew", "taxus", "nature.tree"),
        TreeName("Robinia", "false acacia", "robinia", "nature.elm-leaf", 10..10),
        TreeName("Sophora", "pagoda tree", "honingboom", "nature.elm-leaf"),
        TreeName("Magnolia", "magnolia", "magnolia", "nature.elm-leaf", null, 3..4),
        TreeName("Liquidambar", "sweetgum", "amberboom", "nature.plane-leaf", 10..11),
        TreeName("Sorbus", "rowan", "lijsterbes", "nature.elm-leaf", 10..10),
        TreeName("Crataegus", "hawthorn", "meidoorn", "nature.elm-leaf", 10..10, 5..5),
    )

    private val byGenus = NAMES.associateBy { it.genus.lowercase() }

    /** The genus of a tree from its `genus` tag or the first word of `species`; null when neither is mapped. */
    fun genusOf(tags: Map<String, String>): String? =
        (tags["genus"] ?: tags["species"]?.substringBefore(' '))?.trim()?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotEmpty() }

    fun nameOf(genus: String?): TreeName? = genus?.let { byGenus[it.lowercase()] }
}

/** One entry of the notebook: what it is, its Latin name, a line about it, how far, and what the page draws for it. */
class Note(
    val kind: NatureKind,
    val title: String?,
    /** The kind of thing when it has no name of its own ("plane tree", "bird hide"); resolved to words by the screen. */
    val what: String,
    val latin: String?,
    val facts: List<NoteFact>,
    val art: String,
    val distanceM: Double,
    val place: Place,
    val count: Int = 1,
)

/** A line of a note: kept as data so the screen can say it in the reader's language. */
sealed interface NoteFact {
    data class Planted(val year: Int) : NoteFact
    data class Age(val years: Int) : NoteFact
    data class Height(val metres: Double) : NoteFact
    data class Girth(val cm: Int) : NoteFact
    data class Protected(val what: String?) : NoteFact
    data class Autumn(val genus: String) : NoteFact
    data object Blossom : NoteFact
    data class Hours(val text: String) : NoteFact
    data class Class(val protectClass: String?) : NoteFact
    data object StepFree : NoteFact
    data class Elevation(val metres: Int) : NoteFact
    data class Kind(val text: String) : NoteFact
    data class Wiki(val qid: String) : NoteFact
}

/** Chooses and describes what the notebook shows. */
object NatureNotes {
    /** A tree's notability: protected, named, old, tall, thick, known by species or by Wikidata. */
    fun score(tags: Map<String, String>, today: LocalDate): Int {
        var s = 0
        if (tags["denotation"] == "natural_monument" || tags["monument"] == "yes" || "heritage" in tags) s += 70
        if (tags["historic"] != null || tags["memorial"] != null) s += 30
        if (tags["name"] != null) s += 40
        if ("wikidata" in tags) s += 25
        planted(tags)?.let { y -> s += ((today.year - y) / 4).coerceIn(0, 30) }
        tags["height"]?.toDoubleOrNull()?.let { s += (it / 2).toInt().coerceIn(0, 15) }
        tags["circumference"]?.toDoubleOrNull()?.let { s += (it / 40).toInt().coerceIn(0, 15) }
        if (Trees.genusOf(tags) != null) s += 10
        return s
    }

    /** The year a tree was planted: `start_date` when it is a plain year. */
    fun planted(tags: Map<String, String>): Int? = Regex("""^\D?(\d{4})""").find(tags["start_date"].orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1000..2100 }

    /** The notes for the trees in [trees]: the notable ones singly or as a row of one kind, else a summary of what is mapped. */
    fun treeNotes(trees: List<Found>, today: LocalDate, limit: Int = 6): List<Note> {
        val ranked = trees.map { it to score(it.place.tags, today) }.sortedWith(compareByDescending<Pair<Found, Int>> { it.second }.thenBy { it.first.distanceM })
        val used = HashSet<String>()
        val notes = ArrayList<Note>()
        for ((f, sc) in ranked) {
            if (notes.size >= limit) break
            if (f.place.key in used || sc < NOTABLE) continue
            val tags = f.place.tags
            val genus = Trees.genusOf(tags)
            // Unnamed trees of one genus within 60 m make one row.
            val group = if (tags["name"] == null && genus != null) ranked.map { it.first }.filter { g ->
                g.place.key !in used && g.place.tags["name"] == null && Trees.genusOf(g.place.tags) == genus && Geo.distance(f.place.location, g.place.location) <= 60.0
            } else listOf(f)
            group.forEach { used += it.place.key }
            notes += treeNote(f, genus, group.size, today)
        }
        if (notes.size < 3 && trees.isNotEmpty()) {
            val near = trees.minBy { it.distanceM }
            val leaf = trees.groupingBy { it.place.tags["leaf_type"] ?: "mixed" }.eachCount().maxByOrNull { it.value }?.key
            notes += Note(NatureKind.TREES, null, "trees", null, listOfNotNull(leaf?.let { NoteFact.Kind(it) }), "nature.tree", near.distanceM, near.place, trees.size)
        }
        return notes
    }

    private fun treeNote(f: Found, genus: String?, count: Int, today: LocalDate): Note {
        val tags = f.place.tags
        val name = Trees.nameOf(genus)
        val facts = ArrayList<NoteFact>()
        if (tags["denotation"] == "natural_monument" || tags["monument"] == "yes" || "heritage" in tags) facts += NoteFact.Protected(tags["heritage"]?.takeIf { it != "yes" })
        planted(tags)?.let { y -> facts += if (today.year - y >= 80) NoteFact.Age(today.year - y) else NoteFact.Planted(y) }
        tags["height"]?.toDoubleOrNull()?.takeIf { it >= 20 }?.let { facts += NoteFact.Height(it) }
        tags["circumference"]?.toDoubleOrNull()?.takeIf { it >= 250 }?.let { facts += NoteFact.Girth(it.toInt()) }
        if (name != null) {
            val m = today.monthValue
            if (name.autumn != null && m in name.autumn) facts += NoteFact.Autumn(name.genus) else if (name.blossom != null && m in name.blossom) facts += NoteFact.Blossom
        }
        tags["wikidata"]?.let { facts += NoteFact.Wiki(it) }
        val latin = tags["species"]?.takeIf { it.contains(' ') } ?: genus
        return Note(NatureKind.TREES, tags["name"], genus?.lowercase() ?: "tree", latin, facts, name?.art ?: "nature.tree", f.distanceM, f.place, count)
    }

    /** Notes for the non-tree sections: the nearest named places of the kind, one note each, the same place under one name once. */
    fun placeNotes(kind: NatureKind, found: List<Found>, limit: Int = 6): List<Note> =
        found.distinctBy { it.place.tags["name"] ?: it.place.key }.take(limit).map { f ->
            val t = f.place.tags
            val facts = ArrayList<NoteFact>()
            t["opening_hours"]?.let { facts += NoteFact.Hours(it) }
            if (t["wheelchair"] == "yes") facts += NoteFact.StepFree
            t["protect_class"]?.let { facts += NoteFact.Class(it) }
            t["ele"]?.toDoubleOrNull()?.let { facts += NoteFact.Elevation(it.toInt()) }
            val what = t["leisure"] ?: t["natural"] ?: t["waterway"] ?: t["landuse"] ?: t["boundary"] ?: kind.id
            Note(kind, t["name"], what, t["garden:type"] ?: t["bird_hide"], facts, artFor(kind, t), f.distanceM, f.place)
        }

    private fun artFor(kind: NatureKind, t: Map<String, String>): String = when (kind) {
        NatureKind.TREES -> "nature.tree"
        NatureKind.WATER -> if (t["natural"] == "spring") "nature.spring" else "nature.brook"
        NatureKind.BIRDS -> "nature.heron"
        NatureKind.GARDENS -> "nature.flower"
        NatureKind.WILD -> if (t["natural"] == "peak") "nature.peak" else "nature.summit"
    }

    /** A score from which a tree is worth a line of its own. */
    const val NOTABLE = 40
}
