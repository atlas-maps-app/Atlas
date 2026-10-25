package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.render.Closeup
import app.atlas.render.Regions
import app.atlas.rendertiles.compile.EngineStyle
import app.atlas.rendertiles.compile.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The engine's style file (`app/android/mapcore/style/engine.jsonc`) holds the renderer's values: its tables equal the
 * port's (read by reflection where the port keeps them private), and each value the port's drawing code types as a
 * literal is that literal, checked in the port's source (as RenderTablesTest checks the runways' order). Where the engine
 * differs from the renderer on purpose, the value is named in [DIFFERENCES] with why, and is not held.
 */
class EngineStyleTest {
    private fun port(name: String): Any? = Amsterdam::class.java.getDeclaredField(name).apply { isAccessible = true }.get(Amsterdam)

    private val t = EngineStyle.tables

    @Suppress("UNCHECKED_CAST")
    private fun value(path: String): Any? {
        val (table, key) = path.split('.')
        return (t[table] as Map<String, Any?>)[key] ?: throw AssertionError("the style file has no $path")
    }

    /** A width as the renders' SVG writes it: `.25` for 0.25. */
    private fun css(path: String): String = (value(path) as Double).toString().removePrefix("0")

    @Test
    fun `the tables are the port's`() {
        val tables = mapOf(
            "land" to Regions.LAND.entries.associate { (k, v) -> k to listOf(v.first, v.second) },
            "area" to port("AREA"),
            "surf" to Amsterdam.SURF,
            "surf_colour" to port("SURF_COLOUR"),
            "tree_forms" to Closeup.FORMS,
        )
        for ((name, theirs) in tables) {
            if (name in DIFFERENCES) continue
            assertEquals(name, theirs, t[name])
            assertEquals("$name's order", (theirs as Map<*, *>).keys.toList(), (t[name] as Map<*, *>).keys.toList())
        }
    }

    @Test
    fun `the values are the port's literals`() {
        val src = File(Packs.repo, "app/android/render/src/main/kotlin/app/atlas/render")
        assumeTrue(src.isDirectory)
        val amsterdam = File(src, "Amsterdam.kt").readText()
        val regions = File(src, "Regions.kt").readText()
        val closeup = File(src, "Closeup.kt").readText()
        val coasters = File(src, "Coasters.kt").readText()
        val woods = File(src, "Woods.kt").readText()
        // the values, the port's file and the source text that types them there
        val literals = listOf(
            Triple(listOf("ground.area_other"), amsterdam, """AREA[a["kind"]] ?: "${value("ground.area_other")}""""),
            Triple(listOf("ground.cycleway"), amsterdam, """else if (hw == "cycleway") col = "${value("ground.cycleway")}""""),
            Triple(listOf("rail.ballast_half_width"), regions, """ribbon(rp, if (!crane) ${value("rail.ballast_half_width")} else 0.5)"""),
            Triple(listOf("rail.ballast"), regions, """dd, nightC(th, "${value("rail.ballast")}")))"""),
            Triple(
                listOf("rail.rails_width", "rail.rails_day", "rail.rails_night"), regions,
                """stroke-width=\"${css("rail.rails_width")}\"/>", rl, if (!night) "${value("rail.rails_day")}" else "${value("rail.rails_night")}")""",
            ),
            Triple(
                listOf("big_roads.asphalt_day", "big_roads.asphalt_night"), regions,
                """nightC(th, if (!night) "${value("big_roads.asphalt_day")}" else "${value("big_roads.asphalt_night")}", 1.0)""",
            ),
            Triple(
                listOf("big_roads.lines_day", "big_roads.lines_night"), regions,
                """val mk = if (!night) "${value("big_roads.lines_day")}" else "${value("big_roads.lines_night")}"""",
            ),
            Triple(listOf("big_roads.lines_width"), regions, """val sw = px(cam, ${value("big_roads.lines_width")}, rp[rp.size / 2])"""),
            Triple(
                listOf("big_roads.lane_dash"), regions,
                (value("big_roads.lane_dash") as List<*>).joinToString(", ", "y + 0.01, ", ")"),
            ),
            Triple(
                listOf("runways.pavement_day", "runways.pavement_night"), regions,
                """nightC(th, if (!night) "${value("runways.pavement_day")}" else "${value("runways.pavement_night")}", 1.0)""",
            ),
            Triple(
                listOf("runways.marks_day", "runways.marks_night"), regions,
                """val mk = if (!night) "${value("runways.marks_day")}" else "${value("runways.marks_night")}"""",
            ),
            Triple(listOf("runways.centre_width"), regions, """val sw = px(cam, ${value("runways.centre_width")}, ap[ap.size / 2])"""),
            Triple(
                listOf("runways.centre_dash"), regions,
                (value("runways.centre_dash") as List<*>).joinToString(", ", "val cl = dashes(cam, ap, 0.09, ", ")"),
            ),
            Triple(
                listOf("taxiways.pavement_day", "taxiways.pavement_night"), regions,
                """nightC(th, if (!night) "${value("taxiways.pavement_day")}" else "${value("taxiways.pavement_night")}", 1.0)""",
            ),
            Triple(
                listOf("taxiways.lines_day", "taxiways.lines_night", "taxiways.lines_width"), regions,
                """if (!night) "${value("taxiways.lines_day")}" else "${value("taxiways.lines_night")}", px(cam, ${value("taxiways.lines_width")}, ap[ap.size / 2])""",
            ),
            Triple(
                listOf("taxiways.lines_day", "taxiways.lines_night", "taxiways.lead_in_width"), regions,
                """if (!night) "${value("taxiways.lines_day")}" else "${value("taxiways.lines_night")}", px(cam, ${value("taxiways.lead_in_width")}, ap[0])""",
            ),
            Triple(
                listOf("park_edges.width", "park_edges.dash", "park_edges.opacity", "park_edges.day", "park_edges.night"), regions,
                """stroke-width=\"${value("park_edges.width")}\" stroke-dasharray=\"""" +
                    (value("park_edges.dash") as List<*>).joinToString(" ") { (it as Double).toInt().toString() } +
                    """\" opacity=\"${css("park_edges.opacity")}\"/>", dd, if (!night) "${value("park_edges.day")}" else "${value("park_edges.night")}")""",
            ),
            Triple(
                listOf("quays.waterline_day", "quays.waterline_night"), amsterdam,
                """WY + 0.35, a[1])), if (!night) "${value("quays.waterline_day")}" else "${value("quays.waterline_night")}")""",
            ),
            Triple(listOf("quays.coping"), amsterdam, """tohex(light(th, hx("${value("quays.coping")}"), n3, lit))"""),
            Triple(
                listOf("reflections.opacity_day", "reflections.opacity_night"), amsterdam,
                """opacity=\"%.2f\">%s</g>", if (night) ${value("reflections.opacity_night")} else ${value("reflections.opacity_day")}, reflections(""",
            ),
            Triple(
                listOf("reflections.dark_day", "reflections.dark_overcast", "reflections.dark_night"), amsterdam,
                """val dk = if (night) ${value("reflections.dark_night")} else (if (truthy(th["sky_k"])) ${value("reflections.dark_overcast")} else ${value("reflections.dark_day")})""",
            ),
            Triple(
                listOf("reflections.windows_day", "reflections.windows_night", "reflections.windows_opacity"), amsterdam,
                """fill=\"%s\" opacity=\"${css("reflections.windows_opacity")}\"/>", wins.joinToString(""), """ +
                    """if (!night) "${value("reflections.windows_day")}" else "${value("reflections.windows_night")}")""",
            ),
            Triple(
                listOf("reflections.trees_day", "reflections.trees_night"), amsterdam,
                """val col = if (!night) "${value("reflections.trees_day")}" else "${value("reflections.trees_night")}"""",
            ),
            Triple(listOf("reflections.streak", "reflections.streak_opacity"), amsterdam, streak()),
            Triple(listOf("reflections.glitter"), amsterdam, """out.add("<g fill=\"${value("reflections.glitter")}\" style=\"mix-blend-mode:screen\">"""),
            Triple(listOf("marine"), regions, marine(0, "to %s")),
            Triple(listOf("marine"), regions, marine(1, "to \"%s\"")),
            Triple(
                listOf("trams.width", "trams.day", "trams.night"), amsterdam,
                """stroke-width=\"${css("trams.width")}\" fill=\"none\"/>", pp.joinToString("L") { pf("%.1f %.1f", it.x, it.y) }, """ +
                    """if (!night) "${value("trams.day")}" else "${value("trams.night")}")""",
            ),
            Triple(
                listOf("bridges.lines", "bridges.lines_width"), amsterdam,
                """stroke=\"${value("bridges.lines")}\" stroke-width=\"${css("bridges.lines_width")}\" fill=\"none\"/>"""",
            ),
            Triple(
                listOf("setts.marks", "setts.marks_width", "setts.marks_opacity"), amsterdam,
                """stroke=\"${value("setts.marks")}\" stroke-width=\"${css("setts.marks_width")}\" opacity=\"${css("setts.marks_opacity")}\" fill=\"none\"/>"""",
            ),
            Triple(
                listOf("buildings.roof_edge_day", "buildings.roof_edge_night"), amsterdam,
                """val edge = if (!night) "${value("buildings.roof_edge_day")}" else "${value("buildings.roof_edge_night")}"""",
            ),
            Triple(listOf("buildings.roof_edge_slope"), amsterdam, """pf(" stroke=\"%s\" stroke-width=\"${css("buildings.roof_edge_slope")}\"", edge)"""),
            Triple(listOf("buildings.roof_edge_top"), amsterdam, """pf(" stroke=\"%s\" stroke-width=\"${css("buildings.roof_edge_top")}\"", edge)"""),
            Triple(listOf("buildings.wall_edge"), amsterdam, """colL, pf(" stroke=\"%s\" stroke-width=\"${css("buildings.wall_edge")}\"", colL)"""),
            Triple(listOf("houseboats.hull"), amsterdam, """val hull = if (lens != "truth") hx("${value("houseboats.hull")}")"""),
            Triple(listOf("houseboats.deck"), amsterdam, """if (lens == null) hx("${value("houseboats.deck")}") else hx("#FFFFFF")"""),
            Triple(
                listOf("houseboats.cabin_edge", "houseboats.cabin_edge_width"), amsterdam,
                """" stroke=\"${value("houseboats.cabin_edge")}\" stroke-width=\"${css("houseboats.cabin_edge_width")}\""""",
            ),
            Triple(
                listOf("canal_names"), amsterdam,
                """V3(-uz, 0.0, ux), ${value("canal_names.size")}, "${value("canal_names.fill")}", ${value("canal_names.opacity")}, """ +
                    """${value("canal_names.weight")}, ${value("canal_names.spacing")})""",
            ),
            Triple(
                listOf("windows.glass_top", "windows.glass_bottom", "windows.glass_overcast_top", "windows.glass_overcast_bottom"), amsterdam,
                """if (truthy(th["sky_k"])) "${value("windows.glass_overcast_top")}" to "${value("windows.glass_overcast_bottom")}" """ +
                    """else "${value("windows.glass_top")}" to "${value("windows.glass_bottom")}"""",
            ),
            Triple(
                listOf("windows.lit_top", "windows.lit_bottom"), amsterdam,
                """id=\"glassLit\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0\" stop-color=\"${value("windows.lit_top")}\"/>""" +
                    """<stop offset=\"1\" stop-color=\"${value("windows.lit_bottom")}\"/>""",
            ),
            Triple(
                listOf("windows.dark_day", "windows.dark_night", "windows.lit"), amsterdam,
                """gd = if (!night) "${value("windows.dark_day")}" else "${value("windows.dark_night")}"; gl = "${value("windows.lit")}"""",
            ),
            Triple(
                listOf("windows.warehouse_day", "windows.warehouse_night"), amsterdam,
                """gd = if (!night) "${value("windows.warehouse_day")}" else "${value("windows.warehouse_night")}"; gl = gd""",
            ),
            Triple(
                listOf("windows.frame_day", "windows.frame_night"), amsterdam,
                """var fr = if (!night) "${value("windows.frame_day")}" else "${value("windows.frame_night")}"""",
            ),
            Triple(listOf("windows.frame_width"), amsterdam, """pmax(dd, 1.0)) else ${value("windows.frame_width")}"""),
            Triple(
                listOf("windows.sill_day", "windows.sill_night"), amsterdam,
                """hzc(th, if (!night) "${value("windows.sill_day")}" else "${value("windows.sill_night")}")""",
            ),
            Triple(
                listOf("windows.shop_frame_day", "windows.shop_frame_night"), amsterdam,
                """hzc(th, if (!night) "${value("windows.shop_frame_day")}" else "${value("windows.shop_frame_night")}")""",
            ),
            Triple(listOf("windows.shop_frame_width"), amsterdam, """stroke-width=\"${css("windows.shop_frame_width")}\"%s/>", P(gq)"""),
            Triple(
                listOf("windows.plinth_day", "windows.plinth_night"), amsterdam,
                """hzc(th, if (!night) "${value("windows.plinth_day")}" else "${value("windows.plinth_night")}")""",
            ),
            Triple(
                listOf("windows.cornice_day", "windows.cornice_night"), amsterdam,
                """hzc(th, if (!night) "${value("windows.cornice_day")}" else "${value("windows.cornice_night")}")""",
            ),
            Triple(
                listOf("trees.leaf", "trees.autumn"), closeup,
                """City.mix(City.hx("${value("trees.leaf")}"), City.hx("${value("trees.autumn")}"), tint)""",
            ),
            Triple(
                listOf("trees.bark_day", "trees.bark_night"), closeup,
                """val bark = if (!night) "${value("trees.bark_day")}" else "${value("trees.bark_night")}"""",
            ),
            Triple(
                listOf("trees.bark_lit", "trees.bark_lit_opacity"), closeup,
                """fill=\"${value("trees.bark_lit")}\" opacity=\"${css("trees.bark_lit_opacity")}\"""",
            ),
            Triple(
                listOf("things.mast_day", "things.mast_night"), amsterdam,
                """if (!night) "${value("things.mast_day")}" else "${value("things.mast_night")}", pmax(0.5, 0.13 * s)""",
            ),
            Triple(
                listOf("things.lantern_day", "things.lantern_night"), amsterdam,
                """val lamp = if (night) "${value("things.lantern_night")}" else "${value("things.lantern_day")}"""",
            ),
            Triple(
                listOf("things.cap_day", "things.cap_night"), amsterdam,
                """if (!night) "${value("things.cap_day")}" else "${value("things.cap_night")}"))""",
            ),
            Triple(
                listOf("things.glint", "things.glint_opacity"), amsterdam,
                """stroke=\"${value("things.glint")}\" stroke-width=\"%.2f\" opacity=\"${css("things.glint_opacity")}\"""",
            ),
            Triple(listOf("things.bench"), amsterdam, """val c = "${value("things.bench")}""""),
            Triple(listOf("things.bench_back"), amsterdam, """1.8, 0.08, 0.9, "${value("things.bench_back")}")"""),
            Triple(listOf("things.basket"), amsterdam, """0.42, 0.42, 0.95, "${value("things.basket")}")"""),
            Triple(
                listOf("things.bollard_day", "things.bollard_night"), amsterdam,
                """val col = if (!night) "${value("things.bollard_day")}" else "${value("things.bollard_night")}"""",
            ),
            Triple(
                listOf("things.bollard_head", "things.bollard_head_opacity"), amsterdam,
                """fill=\"${value("things.bollard_head")}\" opacity=\"${css("things.bollard_head_opacity")}\"""",
            ),
            Triple(
                listOf("things.rack_width", "things.rack_day", "things.rack_night"), amsterdam,
                """stroke-width=\"${css("things.rack_width")}\" fill=\"none\"/>", segs.joinToString(""), """ +
                    """if (th["night"] != true) "${value("things.rack_day")}" else "${value("things.rack_night")}")""",
            ),
            Triple(
                listOf("things.railing_day", "things.railing_night"), amsterdam,
                """val col = if (!night) "${value("things.railing_day")}" else "${value("things.railing_night")}"""",
            ),
            Triple(listOf("trees.crown"), amsterdam, """hx(if (truthy(th["wx"])) "#4F6E37" else "${value("trees.crown")}")"""),
            Triple(listOf("items.shaft"), regions, """val shaft = tohex(Amsterdam.light(th, hx("${value("items.shaft")}"), V3(0.0, 0.0, 1.0), !night))"""),
            Triple(
                listOf("items.glass_day", "items.glass_night"), regions,
                """parts.add(band(H - 11, H - 4.5, rs * 2.6, if (!night) "${value("items.glass_day")}" else "${value("items.glass_night")}"))""",
            ),
            Triple(
                listOf("items.gallery"), regions,
                """parts.add(band(H - 4.5, H - 3.2, rs * 2.7, tohex(Amsterdam.light(th, hx("${value("items.gallery")}"), V3(0.0, 1.0, 0.0), !night))))""",
            ),
            Triple(listOf("items.antenna"), regions, """parts.add(band(H - 3.2, H, 0.5, "${value("items.antenna")}"))"""),
            Triple(listOf("items.shadow_night"), regions, """val sh = if (!night) th["shadow"] as String else "${value("items.shadow_night")}""""),
            Triple(
                listOf("items.steel_day", "items.steel_night"), regions,
                """val steel = if (!night) hx("${value("items.steel_day")}") else hx("${value("items.steel_night")}")""",
            ),
            Triple(
                listOf("items.machinery_day", "items.machinery_night"), regions,
                """parts.add(Part(mh.x, mh.y, box(mh, w, q, 6.0, 4.5, H + 2.4, H + 7.0, if (!night) hx("${value("items.machinery_day")}") else hx("${value("items.machinery_night")}"))))""",
            ),
            Triple(
                listOf("items.machinery_day", "items.machinery_night"), regions,
                """parts.add(Part(x, z, box(V2(x, z), u, v, 2.2, pmin(hv, 3.0), H, H + 2.6, if (!night) hx("${value("items.machinery_day")}") else hx("${value("items.machinery_night")}"))))""",
            ),
            Triple(
                listOf("items.floodlights_day", "items.floodlights_night"), regions,
                """g += box(V2(x, z), u, v, 1.7, 0.6, H, H + 1.3, if (!night) hx("${value("items.floodlights_day")}") else hx("${value("items.floodlights_night")}"))""",
            ),
            Triple(
                listOf("items.conveyor_day", "items.conveyor_night"), regions,
                """box(mid, u, v, Lg / 2, 1.4, H - 2.2, H, if (!night) hx("${value("items.conveyor_day")}") else hx("${value("items.conveyor_night")}"))""",
            ),
            Triple(
                listOf("items.glow_inner", "items.glow_inner_opacity", "items.glow_middle", "items.glow_middle_opacity", "items.glow_outer_opacity"), regions,
                """<stop offset=\"0\" stop-color=\"${value("items.glow_inner")}\" stop-opacity=\"${css("items.glow_inner_opacity")}\"/>""" +
                    """<stop offset=\".35\" stop-color=\"${value("items.glow_middle")}\" stop-opacity=\"${css("items.glow_middle_opacity")}\"/>""" +
                    """<stop offset=\"1\" stop-color=\"${value("items.glow_middle")}\" stop-opacity=\"${value("items.glow_outer_opacity")}\"/>""",
            ),
            Triple(
                listOf("items.glow_core"), regions,
                """fill=\"url(#mastglow)\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"${value("items.glow_core")}\"/>"""",
            ),
            Triple(listOf("items.turbine_day", "items.turbine_night"), regions, """if (!night) "${value("items.turbine_day")}" else "${value("items.turbine_night")}"))"""),
            Triple(
                listOf("items.blades_day", "items.blades_night", "items.blade_edge_day", "items.blade_edge_night", "items.blade_edge_width"), regions,
                """stroke-width=\"${css("items.blade_edge_width")}\"/>", blades.toString(), if (!night) "${value("items.blades_day")}" else "${value("items.blades_night")}", """ +
                    """if (!night) "${value("items.blade_edge_day")}" else "${value("items.blade_edge_night")}"))""",
            ),
            Triple(listOf("items.hub_day", "items.hub_night"), regions, """pmax(0.8, 2.2 * s), if (!night) "${value("items.hub_day")}" else "${value("items.hub_night")}"))"""),
            Triple(
                listOf("items.turbine_light", "items.turbine_light_opacity"), regions,
                """fill=\"${value("items.turbine_light")}\" opacity=\"${css("items.turbine_light_opacity")}\"/>", ph[0], ph[1] - pmax(1.0, 3 * s)""",
            ),
            Triple(listOf("items.pylon_day", "items.pylon_night"), regions, """if (!night) "${value("items.pylon_day")}" else "${value("items.pylon_night")}", pmax(0.5, 1.4 * s))"""),
            Triple(
                listOf("items.conductor_width", "items.conductor_opacity", "items.conductor_day", "items.conductor_night"), regions,
                """stroke-width=\"${css("items.conductor_width")}\" opacity=\"${css("items.conductor_opacity")}\"/>", segs.toString(), """ +
                    """if (!night) "${value("items.conductor_day")}" else "${value("items.conductor_night")}")""",
            ),
            Triple(
                listOf("items.coaster_steel_top_day", "items.coaster_steel_side_day", "items.coaster_wood_top_day", "items.coaster_wood_side_day", "items.coaster_mono_top_day", "items.coaster_mono_side_day"), coasters,
                """val COL: Map<String, Pair<String, String>> = mapOf("steel" to ("${value("items.coaster_steel_top_day")}" to "${value("items.coaster_steel_side_day")}"), """ +
                    """"wood" to ("${value("items.coaster_wood_top_day")}" to "${value("items.coaster_wood_side_day")}"), "mono" to ("${value("items.coaster_mono_top_day")}" to "${value("items.coaster_mono_side_day")}"))""",
            ),
            Triple(
                listOf("items.coaster_steel_top_night", "items.coaster_steel_side_night", "items.coaster_wood_top_night", "items.coaster_wood_side_night", "items.coaster_mono_top_night", "items.coaster_mono_side_night"), coasters,
                """val NIGHT: Map<String, Pair<String, String>> = mapOf("steel" to ("${value("items.coaster_steel_top_night")}" to "${value("items.coaster_steel_side_night")}"), """ +
                    """"wood" to ("${value("items.coaster_wood_top_night")}" to "${value("items.coaster_wood_side_night")}"), "mono" to ("${value("items.coaster_mono_top_night")}" to "${value("items.coaster_mono_side_night")}"))""",
            ),
            Triple(
                listOf("items.woods_green_1", "items.woods_green_2", "items.woods_green_3", "items.woods_green_4", "items.woods_green_5"), woods,
                """for (c0 in listOf("${value("items.woods_green_1")}", "${value("items.woods_green_2")}", "${value("items.woods_green_3")}", "${value("items.woods_green_4")}", "${value("items.woods_green_5")}"))""",
            ),
            Triple(
                listOf("trees.trunk_day", "trees.trunk_night"), amsterdam,
                """if (!night) "${value("trees.trunk_day")}" else "${value("trees.trunk_night")}", pmax(0.6, 0.3 * cam.focal / dd)""",
            ),
        )
        for ((paths, file, text) in literals) {
            if (paths.any { it in DIFFERENCES || it.substringBefore('.') in DIFFERENCES }) continue
            assertTrue("${paths.joinToString()}: the port types `$text`", file.contains(text))
        }
    }

    /** regions.py's marine works' widths (column 0) or colours (column 1), as draw_over's literal map types them. */
    @Suppress("UNCHECKED_CAST")
    private fun marine(column: Int, format: String): String =
        (t["marine"] as Map<String, List<Any?>>).entries.joinToString(", ", "mapOf(", ")") { (kind, v) -> "\"$kind\" " + format.format(v[column]) }

    /** The water layer's night streak gradient as the port defines it: its three stops at 0, .5 and 1, each opacity as the SVG prints it. */
    private fun streak(): String {
        val c = value("reflections.streak") as List<*>
        val o = (value("reflections.streak_opacity") as List<*>).map { (it as Double).let { d -> if (d == 0.0) "0" else d.toString().removePrefix("0") } }
        return """<radialGradient id=\"streak\"><stop offset=\"0\" stop-color=\"${c[0]}\" stop-opacity=\"${o[0]}\"/>""" +
            """<stop offset=\".5\" stop-color=\"${c[1]}\" stop-opacity=\"${o[1]}\"/>""" +
            """<stop offset=\"1\" stop-color=\"${c[2]}\" stop-opacity=\"${o[2]}\"/></radialGradient>"""
    }

    @Test
    fun `the file names no table the packer writes itself`() {
        assertEquals(emptyList<String>(), t.keys.filter { it in Style.OWN })
    }

    @Test
    fun `numbers are read as Python reads them`() {
        val read = EngineStyle.parse(
            """
            // a comment
            { "b": { "int": 700, "long": 4294967296, "double": 0.25, "exp": 1e2, "neg": -3 },
              /* another */ "a": ["#FFFFFF", 0, true, null] }
            """.trimIndent(),
        )
        assertEquals(listOf("b", "a"), read.keys.toList())
        assertEquals(mapOf("int" to 700, "long" to 4294967296L, "double" to 0.25, "exp" to 100.0, "neg" to -3), read["b"])
        assertEquals(listOf("#FFFFFF", 0, true, null), read["a"])
    }

    companion object {
        /** The style file's values that differ from the renderer's on purpose (a table or `table.key`), and why: none yet. */
        val DIFFERENCES: Map<String, String> = emptyMap()
    }
}
