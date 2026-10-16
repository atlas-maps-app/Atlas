package app.atlas.rendertiles.compile

import app.atlas.render.Amsterdam
import app.atlas.render.Generic
import app.atlas.render.Regions
import app.atlas.render.live.LiveScene
import app.atlas.rendertiles.model.GroundKey
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Schema
import app.atlas.rendertiles.model.StyleTables

/**
 * The `style` section of a pack: the renders' tables stage F and the shaders need, written from the port's own tables
 * and from the region's state after its load, so the core types no colour again (plan-vector D2, Appendix A).
 *
 * Tables (each a typed value, keys as below):
 * - `keep`: the ladder of tolerances ([Keep.TOLERANCES]); `render_tags`: the tag keys each layer carries;
 * - `land` (`regions.py`'s `LAND`: kind → [colour, draw order]), `area` (`amsterdam.py`'s `AREA`: kind → colour),
 *   `surf` (`SURF`: surface → colour), `surf_colour` (`surface:colour` → colour), `material` (`MATERIAL`), `clay`;
 * - `road_order` and `aero_order`: the classes' drawing order ([GroundKeys]); `ground_stages`: [GroundKey]'s stages;
 *   `building_flags`: the bits of a building's `flags`;
 * - `palette`: the region's generic looks (`BRICK_OLD`, `PLASTER`, `BRICK_NEW` after its load);
 * - `state`: the port's module values the region's load set (`WATER_Y`, `QUAY_COL`, `QUAY_COURSE`, `TONE`, `WIN_STYLE`,
 *   `TZ_H`, `AIR_HAZE`, `CLOUDS_LIT`);
 * - `scene`: the scene's own scalars (`region`, `view`, `tradition`, `no_facades`, `no_shadows`, the tide and water
 *   levels where a region has them, and `R`, its `region.json`);
 * - `tradition`: the region's building tradition (`render/traditions/<id>.json`), when it has one.
 */
object Style {
    /** `amsterdam.py`'s `AREA` (private in the port; `RenderTablesTest` holds this copy equal to it). */
    val AREA: Map<String, String> = linkedMapOf(
        "park" to "#A9C38A", "garden" to "#A9C38A", "grass" to "#A9C38A", "playground" to "#D9B48A", "pedestrian" to "#DCD3C4", "square" to "#DCD3C4", "memorial" to "#E3B3C0",
        "parking" to "#C9C4BA", "bridge" to "#CFC6B6",
    )

    /** `amsterdam.py`'s `SURF_COLOUR` (private in the port; held equal by `RenderTablesTest`). */
    val SURF_COLOUR: Map<String, String> = linkedMapOf("red" to "#C0665A", "grey" to "#9B9A99", "gray" to "#9B9A99", "black" to "#5A5A5A", "green" to "#6F9A6A", "blue" to "#6A86B0")

    /** The scene's scalars `style` keeps (everything else in `S` is a list the tiles carry, or a drawing hook). */
    private val SCENE_KEYS = listOf("region", "view", "tradition", "no_facades", "no_shadows", "_town", "_relief", "_base", "_bank", "_tide", "_water_level", "R")

    /** The tables of [scene] (its module state is put back first). */
    fun of(scene: LiveScene): StyleTables {
        scene.state.restore()
        val S = scene.S
        val t = LinkedHashMap<String, Any?>()
        t["keep"] = Keep.TOLERANCES.toList()
        t["render_tags"] = Schema.RENDER_TAGS.entries.associate { (k, v) -> k.label to v }
        t["land"] = Regions.LAND.entries.associate { (k, v) -> k to listOf(v.first, v.second) }
        t["area"] = AREA
        t["surf"] = Amsterdam.SURF
        t["surf_colour"] = SURF_COLOUR
        t["material"] = Amsterdam.MATERIAL
        t["clay"] = Amsterdam.CLAY
        t["road_order"] = GroundKeys.ROAD_ORDER
        t["aero_order"] = GroundKeys.AERO_ORDER
        t["ground_stages"] = linkedMapOf("land" to GroundKey.LAND, "water" to GroundKey.WATER, "area" to GroundKey.AREA, "road" to GroundKey.ROAD, "over" to GroundKey.OVER, "marks" to GroundKey.MARKS)
        t["building_flags"] = linkedMapOf(
            "church" to Schema.Flags.CHURCH, "warehouse" to Schema.Flags.WAREHOUSE, "shop_floor" to Schema.Flags.SHOP_FLOOR, "construction" to Schema.Flags.CONSTRUCTION,
            "near_water" to Schema.Flags.NEAR_WATER, "houseboat" to Schema.Flags.HOUSEBOAT, "modern" to Schema.Flags.MODERN,
        )
        t["palette"] = linkedMapOf("brick_old" to Amsterdam.BRICK_OLD.toList(), "plaster" to Amsterdam.PLASTER.toList(), "brick_new" to Amsterdam.BRICK_NEW.toList())
        t["state"] = linkedMapOf(
            "water_y" to Amsterdam.WATER_Y, "quay_col" to Amsterdam.QUAY_COL, "quay_course" to Amsterdam.QUAY_COURSE, "tone" to Amsterdam.TONE,
            "win_style" to Amsterdam.WIN_STYLE, "tz_h" to Amsterdam.TZ_H, "air_haze" to Amsterdam.AIR_HAZE, "clouds_lit" to Amsterdam.CLOUDS_LIT,
        )
        val sc = LinkedHashMap<String, Any?>()
        for (k in SCENE_KEYS) if (k in S) sc[k] = plain(S[k])
        t["scene"] = sc
        (S["tradition"] as String?)?.let { name -> t["tradition"] = plain(Generic.tradition(name)) }
        return StyleTables(t)
    }

    /** A value as typed values carry it: the port's tuples (lists) and dicts as they are; anything else fails. */
    private fun plain(v: Any?): Any? = when (v) {
        null, is Boolean, is Int, is Long, is Double, is String -> v
        is List<*> -> v.map { plain(it) }
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> for ((k, e) in v) m[k as String] = plain(e) }
        else -> throw IllegalStateException("the scene's ${v::class.qualifiedName} cannot go into the style section")
    }
}
