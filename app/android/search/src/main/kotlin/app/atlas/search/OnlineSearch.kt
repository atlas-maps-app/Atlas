package app.atlas.search

import app.atlas.model.Place
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea

/** What an online search sent and got back: the QL, every place in the answer, and the ranked hits. */
data class OnlineAnswer(val ql: String, val places: List<Place>, val result: QueryResult)

/**
 * Runs a query on the whole planet through Overpass when the downloaded regions are not enough: compiles it with
 * [OverpassCompiler], fetches the answer (results and the places of its "near" clauses), and ranks it with the same
 * [OfflineEvaluator], which also checks what QL could only approximate (opening hours, numbers with units, names).
 */
class OnlineSearch(private val overpass: Overpass) {
    suspend fun run(query: Query, area: OverpassArea, ctx: EvalContext): OnlineAnswer {
        val ql = OverpassCompiler.compile(query, area, ctx.location)
        val places = overpass.places(ql)
        val result = OfflineEvaluator(MemoryPlaces(places)).run(bound(query, areaQuery(area)), ctx)
        return OnlineAnswer(ql, places, result)
    }

    /** [q] confined to [area], an outer sort or limit kept outside. */
    private fun bound(q: Query, area: Query): Query = when (q) {
        is Query.Sort -> Query.Sort(bound(q.query, area), q.by)
        is Query.Limit -> Query.Limit(bound(q.query, area), q.count)
        else -> Query.and(q, area)
    }

    private fun areaQuery(area: OverpassArea): Query = when (area) {
        is OverpassArea.Box -> Query.InArea(area.box)
        is OverpassArea.Around -> Query.Within(area.center, area.metres)
        is OverpassArea.Along -> if (area.points.size == 1) Query.Within(area.points[0], area.metres) else Query.AlongRoute(area.points, area.metres)
    }
}
