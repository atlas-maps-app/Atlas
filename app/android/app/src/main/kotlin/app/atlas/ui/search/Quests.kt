package app.atlas.ui.search

import app.atlas.model.Place
import app.atlas.places.Fact
import app.atlas.places.FactKind
import app.atlas.places.FactState
import app.atlas.places.Localise
import app.atlas.places.Presets

/**
 * A question about a place that nobody has mapped yet ("Wi-Fi?"), answered with ✓ or ✗: [key] is the tag it asks for,
 * and each answer suggests one value of it.
 */
data class Quest(val fact: Fact, val key: String, val yesValue: String, val noValue: String) {
    fun valueFor(yes: Boolean): String = if (yes) yesValue else noValue
}

/**
 * Turns a place's unmapped facts into quests, and an answer into the text of an anonymous OpenStreetMap note for the
 * mappers nearby (in English, which every mapping community reads). Atlas never edits the map itself: the note asks.
 */
object Quests {
    /** Facts that a visitor can answer with yes or no, and the value "yes" is mapped as. */
    private val YES_NO: Map<FactKind, String> = mapOf(
        FactKind.WHEELCHAIR to "yes",
        FactKind.INTERNET to "wlan",
        FactKind.OUTDOOR_SEATING to "yes",
        FactKind.TAKEAWAY to "yes",
        FactKind.TOILETS to "yes",
        FactKind.TOILETS_WHEELCHAIR to "yes",
        FactKind.CHANGING_TABLE to "yes",
        FactKind.LIT to "yes",
        FactKind.SMOKING to "yes",
        FactKind.SELF_SERVICE to "yes",
        FactKind.DRIVE_THROUGH to "yes",
        FactKind.RESERVATION to "yes",
        FactKind.FEE to "yes",
        FactKind.DIET to "yes",
    )

    /** The quests of [facts] (as `Facts.of` returns them), most important first, at most [max]. */
    fun of(facts: List<Fact>, max: Int = 3): List<Quest> = facts
        .filter { !it.mapped && it.state == FactState.UNKNOWN }
        .mapNotNull { f -> YES_NO[f.kind]?.let { yes -> Quest(f, f.key, yes, "no") } }
        .sortedByDescending { it.fact.priority }
        .take(max)

    /** The note an answer leaves: what the place is, its link, the question, the answer and the tag it suggests. */
    fun noteText(place: Place, quest: Quest, yes: Boolean): String {
        val name = Localise.name(place.tags, "en")
        val kind = Presets.match(place.tags)?.name("en")
        val what = listOfNotNull(name, kind?.takeIf { it != name }?.let { "($it)" }).joinToString(" ").ifEmpty { "This place" }
        val question = (quest.fact.label ?: quest.key.replace('_', ' ')).trimEnd('?')
        return buildString {
            append(what)
            place.id?.let { append(" — ").append(it.url) }
            append('\n')
            append(question).append("? A visitor answered: ").append(if (yes) "yes" else "no").append('.')
            append('\n')
            append("Suggested tag: ").append(quest.key).append('=').append(quest.valueFor(yes))
        }
    }

    /** The note "leave a note for mappers" starts from: where it is, then the user's own words. */
    fun pointNote(words: String, about: String?): String = buildString {
        about?.let { append(it).append('\n') }
        append(words.trim())
    }
}
