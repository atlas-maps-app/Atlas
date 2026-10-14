package app.atlas.ui.search

import app.atlas.search.Chip
import app.atlas.search.ChipKind
import app.atlas.search.ParsedQuery
import app.atlas.search.Query

/**
 * Editing a parsed query through its chips: the "near" clauses in the order they were said (each a ↳ row), a new
 * distance for one of them, and a chip taken out of the text.
 */
object QueryEdit {
    /** One ↳ row: its clause in the tree, its row number among the chips, and whether it is "away from". */
    data class NearRow(val node: Query, val row: Int, val negated: Boolean) {
        val metres: Double get() = when (node) {
            is Query.Near -> node.maxDistance
            is Query.NotNear -> node.minDistance
            else -> 0.0
        }
    }

    /** The near clauses of [p]'s first step, the first said first, matched to their chip rows. */
    fun rows(p: ParsedQuery): List<NearRow> {
        val chain = chain(p.query)
        val chipRows = p.chips.filter { it.step == 0 && it.row > 0 }.map { it.row }.distinct().sorted()
        return chain.mapIndexed { i, q -> NearRow(q, chipRows.getOrElse(i) { i + 1 }, q is Query.NotNear) }
    }

    /** The near clauses of [q], innermost (first said) first: the order the evaluator numbers its `NearMatch`es. */
    fun chain(q: Query): List<Query> = when (q) {
        is Query.Sort -> chain(q.query)
        is Query.Limit -> chain(q.query)
        is Query.And -> q.parts.firstOrNull { it is Query.Near || it is Query.NotNear || it is Query.Sort || it is Query.Limit }?.let { chain(it) } ?: emptyList()
        is Query.Near -> chain(q.subject) + q
        is Query.NotNear -> chain(q.subject) + q
        else -> emptyList()
    }

    /** [q] with the near clause [node] (one of [chain]) at [metres]. */
    fun withDistance(q: Query, node: Query, metres: Double): Query = replace(q, node) {
        when (it) {
            is Query.Near -> it.copy(maxDistance = metres)
            is Query.NotNear -> it.copy(minDistance = metres)
            else -> it
        }
    }

    /** [q] with every clause of [distances] (by its row) set to its distance. */
    fun withDistances(p: ParsedQuery, distances: Map<Int, Double>): Query {
        var q = p.query
        for (r in rows(p)) {
            val d = distances[r.row] ?: continue
            // the node changes as the tree is rebuilt: find this row's clause again in the new tree
            val node = chain(q).getOrNull(rows(p).indexOf(r)) ?: continue
            q = withDistance(q, node, d)
        }
        return q
    }

    /** The text without the words of [chip]; null when the chip has no words of its own in the text. */
    fun without(text: String, chip: Chip): String? {
        val span = chip.span ?: return null
        if (span.last >= text.length) return null
        val cut = text.removeRange(span.first, span.last + 1)
        return cut.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.])"), "$1").trim().trim(',').trim()
    }

    /** The chips of the first step's main row (row 0) that stand for what is sought, not where. */
    fun mainChips(p: ParsedQuery): List<Chip> = p.chips.filter { it.step == 0 && it.row == 0 && it.kind != ChipKind.DISTANCE }

    /** The chip that names what row [row] is near ("🚲 dock"), or null. */
    fun subjectOf(p: ParsedQuery, row: Int): Chip? = p.chips.firstOrNull { it.step == 0 && it.row == row && it.kind != ChipKind.DISTANCE }

    private fun replace(q: Query, target: Query, f: (Query) -> Query): Query {
        if (q === target) return f(q)
        return when (q) {
            is Query.Near -> q.copy(subject = replace(q.subject, target, f), other = replace(q.other, target, f))
            is Query.NotNear -> q.copy(subject = replace(q.subject, target, f), other = replace(q.other, target, f))
            is Query.Sort -> q.copy(query = replace(q.query, target, f))
            is Query.Limit -> q.copy(query = replace(q.query, target, f))
            is Query.And -> Query.And(q.parts.map { replace(it, target, f) })
            is Query.Or -> Query.Or(q.parts.map { replace(it, target, f) })
            is Query.Not -> Query.Not(replace(q.part, target, f))
            is Query.Prefer -> q.copy(part = replace(q.part, target, f))
            else -> q
        }
    }
}
