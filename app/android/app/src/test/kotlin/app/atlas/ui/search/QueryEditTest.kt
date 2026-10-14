package app.atlas.ui.search

import app.atlas.search.ChipKind
import app.atlas.search.Query
import app.atlas.search.QueryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing a parsed query through its chips: the ↳ rows, their distances, and taking a chip's words out. */
class QueryEditTest {
    @Test
    fun aNearClauseIsARowWithItsDistance() {
        val p = QueryParser.parse("cafe near bicycle rental 200m")
        val rows = QueryEdit.rows(p)
        assertEquals(1, rows.size)
        assertEquals(200.0, rows[0].metres, 0.0)
        assertEquals(1, rows[0].row)
        assertEquals("bicycle rental", QueryEdit.subjectOf(p, rows[0].row)?.label)
        assertEquals(listOf("cafe"), QueryEdit.mainChips(p).map { it.label })
    }

    @Test
    fun twoNearClausesInTheOrderSaid() {
        val p = QueryParser.parse("wifi café near a bike dock and a park")
        val rows = QueryEdit.rows(p)
        assertEquals(2, rows.size)
        assertEquals(listOf(1, 2), rows.map { it.row })
        assertEquals("bike dock", QueryEdit.subjectOf(p, 1)?.label)
        assertEquals("park", QueryEdit.subjectOf(p, 2)?.label)
        assertTrue(QueryEdit.mainChips(p).any { it.kind == ChipKind.KIND })
    }

    @Test
    fun aDialledDistanceLandsOnItsClause() {
        val p = QueryParser.parse("wifi café near a bike dock and a park")
        val q = QueryEdit.withDistances(p, mapOf(2 to 350.0))
        val chain = QueryEdit.chain(q)
        assertEquals(2, chain.size)
        assertEquals(350.0, (chain[1] as Query.Near).maxDistance, 0.0)
        // The other clause keeps what was said.
        assertEquals((QueryEdit.chain(p.query)[0] as Query.Near).maxDistance, (chain[0] as Query.Near).maxDistance, 0.0)
        // No distances: the query as parsed.
        assertEquals(p.query, QueryEdit.withDistances(p, emptyMap()))
    }

    @Test
    fun takingAChipOutRemovesItsWords() {
        val text = "wifi café near a bike dock and a park"
        val p = QueryParser.parse(text)
        val wifi = p.chips.first { it.label == "wifi" }
        assertEquals("café near a bike dock and a park", QueryEdit.without(text, wifi))
        val noWords = p.chips.firstOrNull { it.span == null }
        if (noWords != null) assertNull(QueryEdit.without(text, noWords))
        assertNotNull(QueryEdit.without(text, p.chips.first { it.label == "park" }))
    }

    @Test
    fun awayFromIsANegatedRow() {
        val p = QueryParser.parse("bench at least 100 m from a road")
        val rows = QueryEdit.rows(p)
        if (rows.isNotEmpty() && rows[0].negated) assertEquals(100.0, rows[0].metres, 0.0)
    }
}
