package app.atlas.packer.world

import app.atlas.packer.pbf.OsmHandler
import app.atlas.packer.pbf.PbfNode
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.pbf.PbfRelation
import app.atlas.packer.pbf.PbfWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What world.yml's filter job keeps of a continent's extract (`packer world-tags`, osmium tags-filter's expressions):
 * every element the world's profile makes a feature of, on Monaco's extract and for every value of stage P's tables
 * the profile reads, and none of what it never draws (buildings, the minor roads, the coastline).
 */
class WorldFilterTest {
    private val profile = WorldProfile()
    private val expressions = WorldFilter.expressions().map(WorldFilter::parse)

    private fun kept(type: Char, tags: Map<String, String>): Boolean = expressions.any { it.matches(type, tags::get) }

    private fun decided(type: OsmType, tags: Map<String, String>, closed: Boolean, area: Boolean, line: Boolean): Boolean =
        profile.decide(TagView { tags[it] }, type, closed, area, line).isNotEmpty()

    @Test
    fun everyElementOfMonacoTheProfileDrawsIsKept() {
        val pbf = File(javaClass.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI())
        var drawn = 0
        var tagged = 0
        val lost = ArrayList<String>()
        PbfReader(pbf).read(object : OsmHandler {
            override fun node(node: PbfNode) {
                if (node.tags.isEmpty()) return
                tagged++
                val t = node.tags.toMap()
                if (decided(OsmType.NODE, t, closed = false, area = false, line = false)) {
                    drawn++
                    if (!kept('n', t)) lost += "node ${node.id} $t"
                }
            }

            override fun way(way: PbfWay) {
                if (way.tags.isEmpty()) return
                tagged++
                val t = way.tags.toMap()
                val closed = way.refCount > 1 && way.ref(0) == way.ref(way.refCount - 1)
                if (decided(OsmType.WAY, t, closed, closed, true) || decided(OsmType.WAY, t, closed, false, true)) {
                    drawn++
                    if (!kept('w', t)) lost += "way ${way.id} $t"
                }
            }

            override fun relation(relation: PbfRelation) {
                if (relation.tags.isEmpty()) return
                tagged++
                val t = relation.tags.toMap()
                if (decided(OsmType.RELATION, t, closed = false, area = true, line = false)) {
                    drawn++
                    if (!kept('r', t)) lost += "relation ${relation.id} $t"
                }
            }
        })
        println("world-tags: $drawn of Monaco's $tagged tagged elements drawn by the world, ${lost.size} of them not kept")
        assertTrue("Monaco's extract has elements the world draws", drawn > 50)
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun everyValueOfTheTablesTheProfileDrawsIsKept() {
        val t = StagePTags.STAGE_P
        val lost = ArrayList<String>()
        fun check(type: OsmType, tags: Map<String, String>, closed: Boolean, area: Boolean, line: Boolean) {
            if (!decided(type, tags, closed, area, line)) return
            val c = when (type) {
                OsmType.NODE -> 'n'
                OsmType.WAY -> 'w'
                OsmType.RELATION -> 'r'
            }
            if (!kept(c, tags)) lost += "$type $tags"
        }
        val values = (t.land.keys + t.landLeisure + t.roads.keys + t.bigRoads + t.rail + t.aero + t.waterWays + listOf("water", "river", "parking", "port", "apron") + WorldProfile.PLACE_LEVELS.keys).toSet()
        for (key in listOf("landuse", "natural", "leisure", "highway", "railway", "aeroway", "waterway", "amenity", "industrial", "place")) {
            for (v in values) {
                val tags = mapOf(key to v)
                check(OsmType.NODE, tags, closed = false, area = false, line = false)
                check(OsmType.WAY, tags, closed = true, area = true, line = true)
                check(OsmType.WAY, tags, closed = false, area = false, line = true)
                check(OsmType.RELATION, tags, closed = false, area = true, line = false)
            }
        }
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun whatTheWorldNeverDrawsIsLeftOut() {
        assertFalse(kept('w', mapOf("building" to "yes")))
        assertFalse(kept('w', mapOf("highway" to "residential")))
        assertFalse(kept('w', mapOf("highway" to "footway")))
        assertFalse(kept('w', mapOf("natural" to "coastline")))
        assertFalse(kept('n', mapOf("amenity" to "cafe")))
        assertFalse(kept('w', mapOf("railway" to "tram")))
        assertTrue(kept('w', mapOf("highway" to "tertiary")))
        assertTrue(kept('r', mapOf("type" to "multipolygon", "landuse" to "forest")))
        assertTrue(kept('n', mapOf("place" to "village")))
    }
}
