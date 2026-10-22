package app.atlas.rendertiles.model

/**
 * Every layer's columns, in the order a tile writes them (payload version 1). A column's name is the scene's own field
 * name where there is one (`amsterdam.py`'s and `regions.py`'s dict keys), so a decoded feature reads as the scene's
 * record. A column whose every value is its default (null, or empty) is left out of a tile; a reader takes a missing
 * column as all defaults.
 *
 * Fields every layer may carry:
 * - `index`: the feature's place in its scene list (`S['buildings']`, `S['nodes']`, …): the renders' source order,
 *   which breaks their painter's ties and orders canal names, roads within a class, areas and lines.
 * - `tag:<key>`: the OSM tags the renders read when they draw ([RENDER_TAGS], measured by rendering the boards' views
 *   with every read recorded), one column per key, never the others; items keep all their tags in `tags`.
 * - `ground_key`: the feature's slot in the renders' ground order ([GroundKey]).
 *
 * Every field the loader leaves on a record has a column, or is one of [DROPPED]'s (with the reason the renders do
 * not need it); a field that is neither fails the build, so nothing the renders draw from is left out unnoticed.
 */
object Schema {
    /** One column: its [name], its [codec], and what it holds. */
    class Column(val name: String, val codec: Codec, val doc: String) {
        override fun toString(): String = "$name:${codec.name}"
    }

    private fun c(name: String, codec: Codec, doc: String) = Column(name, codec, doc)

    /**
     * The OSM tag keys the renders read when they draw, per layer: measured by rendering the Westerkerk home (sunny,
     * grey, wet, night, from above, close and at eye level) and the Main board (sunny, grey, wet) with every tag read
     * recorded, and checked against the port's sources (`RenderTagsTest`). The tag lens (`tag_state`) reads others; it is
     * not drawn by the map. Items keep every tag. Each key is its own column, `tag:<key>`.
     */
    val RENDER_TAGS: Map<Layer, List<String>> = linkedMapOf(
        Layer.BUILDING to listOf("building", "building:part", "roof:direction"),
        Layer.PART to listOf("building", "building:part", "roof:direction"),
        Layer.GROUND to listOf("bridge", "man_made", "surface"),
        Layer.ROAD to listOf(
            "lit", "surface", "surface:colour", "sidewalk", "sidewalk:both", "sidewalk:left", "sidewalk:right", "sidewalk:surface",
            "sidewalk:both:surface", "sidewalk:left:surface", "sidewalk:right:surface",
        ),
        Layer.LINE to listOf("lanes", "width", "ref", "tower:type"),
        Layer.TREE to listOf("natural", "highway", "leaf_type", "leaf_cycle", "genus", "species", "taxon", "addr:housenumber"),
        Layer.THING to listOf("natural", "highway", "amenity", "barrier", "opening_hours", "outdoor_seating"),
        Layer.LABEL to listOf("place", "population", "capital", "admin_level"),
    )

    /**
     * The fields the loader leaves on a layer's records that the tiles do not carry, each with why: the renders never
     * read it, or read it only through a value the packer derives with the port's own function. A record's `tags` keep
     * only [RENDER_TAGS] (items all of theirs); its geometry and position are the layer's geometry columns.
     */
    val DROPPED: Map<Layer, Map<String, String>> = run {
        val building = linkedMapOf(
            "_bb" to "the box of the tolerance-0 ring: the ring's own, derived again by a reader",
            "src" to "where the height came from (height, levels): the loader's note, never drawn",
            "look_why" to "why the generic look is what it is: the loader's note, never drawn",
            "col" to "the wall's colour: drawn only through surface_colour, carried as wall_rgb",
            "mat" to "the wall's material as mapped: drawn only through surface_colour (wall_rgb); mat_draw is carried",
            "col_draw" to "the wall colour drawn: through surface_colour (wall_rgb) and the board lines (Flags.WALL_DRAWN)",
            "rcol" to "the roof's colour: drawn only through surface_colour, carried as roof_rgb",
            "rmat" to "the roof's material as mapped: drawn only through surface_colour (roof_rgb); rmat_draw is carried",
            "rcol_draw" to "the roof colour drawn: only through surface_colour, carried as roof_rgb",
        )
        val node = linkedMapOf("_b" to "the building whose places name it: that building's places column holds it")
        linkedMapOf(Layer.BUILDING to building, Layer.PART to building, Layer.VARIANT to building, Layer.TREE to node, Layer.THING to node)
    }

    /** The tag keys whose values are mostly numbers, coded as such ([Codec.STRN]). */
    private val NUMBER_TAGS = setOf("addr:housenumber")

    /** The `tag:<key>` columns of [layer]: one nullable string per key of [RENDER_TAGS]. */
    private fun tags(layer: Layer): List<Column> = (RENDER_TAGS[layer] ?: emptyList()).map { k ->
        c("tag:$k", if (k in NUMBER_TAGS) Codec.STRN else Codec.STR, "the $k tag")
    }

    /** The name of [key]'s tag column. */
    fun tag(key: String): String = "tag:$key"

    /** The derived flags of buildings and parts ([BUILDING]'s `flags`): computed by the packer with the renders' own tests. */
    object Flags {
        /** `building` is church, cathedral or chapel; `building:part` is church; or the part stands in a church (`in_church`). */
        const val CHURCH = 1

        /** `building` is industrial, warehouse or service. */
        const val WAREHOUSE = 2

        /** Places are attached, or `building` is retail or commercial (the shop floor, `wx` and no lens aside). */
        const val SHOP_FLOOR = 4

        /** `building` is construction (the scaffold pattern). */
        const val CONSTRUCTION = 8

        /** The footprint lies within 16 m of a quay edge (`near_water(edges, poly, 16)`): it is mirrored in the water. */
        const val NEAR_WATER = 16

        /** `building` is houseboat. */
        const val HOUSEBOAT = 32

        /** Built in 1960 or later and not a church (`year >= 1960 and btype not in CHURCHES`). */
        const val MODERN = 64

        /**
         * The loader drew the wall's colour (`col_draw` is set): a tradition's board lines take `wall_rgb` (× 0.62);
         * without it they take #888888 (`hx(b.get('col_draw') or '#888888')`).
         */
        const val WALL_DRAWN = 128
    }

    /** What buildings and parts carry besides their ring (also the attributes of a [Layer.VARIANT]). */
    val BUILDING_ATTRIBUTES: List<Column> = listOf(
        c("h", Codec.CENTI, "height above the ground, metres (guessed or mapped)"),
        c("min", Codec.CENTI, "the walls' base, metres (min_height, or terrain)"),
        c("roof_h", Codec.CENTI, "the roof's height, metres"),
        c("year", Codec.NVARZ, "the year start_date begins with"),
        c("levels", Codec.NVARZ, "storeys (mapped, or from the height)"),
        c("parent_id", Codec.NVARZ, "a part: the id of the outline it stands in"),
        c("roof", Codec.STR, "roof shape after regions.py's CLOSEST"),
        c("orient", Codec.STR, "roof:orientation (along, across)"),
        c("wall_rgb", Codec.VARU, "the wall's base colour, 0xRRGGBB: surface_colour(b, None) (drawn, mapped, material or clay), before light"),
        c("roof_rgb", Codec.VARU, "the roof's base colour, 0xRRGGBB: surface_colour(b, None, 'roof'), before light"),
        c("mat_draw", Codec.STR, "the wall material drawn (brick, plaster, …: its pattern)"),
        c("rmat_draw", Codec.STR, "the roof material drawn (roof_tiles, metal, …: its pattern)"),
        c("kind", Codec.STR, "the generic look's kind (home, office, …)"),
        c("otype", Codec.STR, "relation for buildings mapped as multipolygons, else null (a way): the OSM type of the id"),
        c("hidden", Codec.BOOL, "an outline drawn by its parts instead"),
        c("no_win", Codec.BOOL, "no window rows (halls, tanks, glass walls)"),
        c("in_church", Codec.BOOL, "a part standing in a church"),
        c("guessed", Codec.STRS, "the tags the loader guessed (each true in the scene's dict)"),
        c("places", Codec.IDS, "the ids of the nodes (shops, cafés) inside it, in the scene's order"),
        c("place_hours", Codec.STRS, "opening_hours of each place, \"\" where none (null without places)"),
        c("flags", Codec.VARU, "derived flags (Flags)"),
    ) + tags(Layer.BUILDING)

    val BUILDING: List<Column> = listOf(
        c("index", Codec.DELTA, "source order in S['buildings'] (or S['parts'])"),
        c("id", Codec.DELTA, "the OSM id (a way, or a relation when otype says so)"),
        c("ring", Codec.GEOM, "the footprint, the scene's ring at tolerance 0"),
        c("ring.keep", Codec.KEEP, "the footprint's keep codes"),
    ) + BUILDING_ATTRIBUTES

    val GROUND: List<Column> = listOf(
        c("source", Codec.U8, "0: S['areas']; 1: S['land']"),
        c("index", Codec.DELTA, "source order in its list (S['land'] as the loader sorted it: by LAND's draw order, then area, largest first)"),
        c("id", Codec.NVARZ, "the OSM id: land cover's; an area's too where its tile carries the ground's order across windows (the core's windows)"),
        c("otype", Codec.STR, "relation for a multipolygon's ring, else null (a way): with id and part, the element a window's ground feature comes from"),
        c("part", Codec.VARU, "the ring's place among its element's rings: the ground's order across windows (the core's windows; the packer writes none)"),
        c("ring", Codec.GEOM, "the ring, whole"),
        c("ring.keep", Codec.KEEP, "keep codes (simplify's; the renders simplify areas, never land cover, which a coarse level takes at its own tolerance)"),
        c("kind", Codec.STR, "the kind (park, square, residential, …)"),
        c("ground_key", Codec.VARU, "the slot in the renders' ground order (land cover: its class is the frame's, GroundKey.LAND)"),
    ) + tags(Layer.GROUND)

    val WATER: List<Column> = listOf(
        c("index", Codec.DELTA, "source order in S['water'] (or S['water_holes'] for holes)"),
        c("hole", Codec.BOOL, "an island: a ring of S['water_holes']"),
        c("ring", Codec.GEOM, "the ring clipped at the tile's edges: one piece per ring and tile, a ring that leaves the tile and comes back joined along the edge"),
        c("ring.keep", Codec.KEEP, "the piece's keep codes, clip points always kept"),
        c("shore", Codec.BOOL, "a natural shore (S['natural_shore']): no quay wall"),
        c("level", Codec.CENTI, "the water level below the street, metres (S['water_y']); null: the region's WATER_Y"),
        c("tidal", Codec.BOOL, "its level follows the tide of the moment (S['_tidal'])"),
        c("hole_of", Codec.NVARZ, "a hole's water ring (S['_hole_of']), as its index in S['water']"),
        c("ground_key", Codec.VARU, "the slot in the renders' ground order"),
    )

    val QUAY: List<Column> = listOf(
        c("index", Codec.DELTA, "the edge's order in water_edges(S)"),
        c("hole", Codec.BOOL, "an island's edge"),
        c("ring", Codec.VARU, "the edge's ring: its index in S['water'] (or S['water_holes'] for an island)"),
        c("owner", Codec.NVARZ, "the ring the edge counts as (`Edge.r`): its index in S['water'] when not its own ring"),
        c("edge", Codec.GEOM, "the edge: its two vertices, the water ring's"),
        c("flip", Codec.BOOL, "the normal into the water is −(ez, −ex)/L rather than (ez, −ex)/L"),
        c("level", Codec.CENTI, "the level of the edge's ring; null: the region's WATER_Y"),
        c("tidal", Codec.BOOL, "its level follows the tide"),
    )

    val ROAD: List<Column> = listOf(
        c("index", Codec.DELTA, "source order in S['roads']"),
        c("id", Codec.DELTA, "the OSM way id"),
        c("line", Codec.GEOM, "the centreline, whole"),
        c("w", Codec.CENTI, "width, metres"),
        c("hw", Codec.STR, "highway class"),
        c("bridge", Codec.BOOL, "a bridge"),
        c("layer", Codec.CENTI, "layer"),
        c("wguess", Codec.BOOL, "the width is the class's guess"),
        c("ground_key", Codec.VARU, "the slot in the renders' ground order"),
        c("marks_key", Codec.VARU, "the slot of the marks drawn along it (a bridge's railing lines, a sett road's courses): GroundKey.MARKS class 0; 0 for a road that draws none"),
    ) + tags(Layer.ROAD)

    val LINE: List<Column> = listOf(
        c("source", Codec.U8, "0 S['rails'] (tram), 1 S['rail'], 2 S['big'], 3 S['aero'], 4 S['power'], 5 S['marine'], 6 S['park']"),
        c("index", Codec.DELTA, "source order in its list"),
        c("id", Codec.NVARZ, "the OSM id where the scene has one, or the tile carries the ground's order across windows (the core's windows)"),
        c("otype", Codec.STR, "relation for a multipolygon's ring, else null (a way): with id and part, the element a window's line comes from"),
        c("part", Codec.VARU, "the ring's place among its element's rings: the ground's order across windows (the core's windows; the packer writes none)"),
        c("line", Codec.GEOM, "the line, whole (a park's edge is its ring)"),
        c("kind", Codec.STR, "aeroway or man_made kind"),
        c("hw", Codec.STR, "a big road's class"),
        c("layer", Codec.CENTI, "a big road's layer (0 where none is mapped): it is drawn 0.5 m higher per layer"),
        c("crane", Codec.BOOL, "a crane rail"),
        c("closed", Codec.BOOL, "a marine work mapped as a closed way"),
        c("h", Codec.CENTI, "height, metres"),
        c("ground_key", Codec.VARU, "the slot in the renders' ground order"),
    ) + tags(Layer.LINE)

    val TREE: List<Column> = listOf(
        c("index", Codec.DELTA, "source order in S['trees']"),
        c("node", Codec.DELTA, "its order in S['nodes']"),
        c("id", Codec.DELTA, "the OSM node id"),
        c("pos", Codec.POINT, "the trunk"),
        c("h", Codec.CENTI, "height, metres"),
        c("crown", Codec.CENTI, "crown diameter, metres"),
        c("gy", Codec.CENTI, "the ground's height under it (terrain towns)"),
        c("guessed", Codec.STRS, "the tags the loader guessed"),
    ) + tags(Layer.TREE)

    val THING: List<Column> = listOf(
        c("index", Codec.DELTA, "source order in S['nodes']"),
        c("id", Codec.DELTA, "the OSM node id"),
        c("pos", Codec.POINT, "where it stands"),
        c("poi", Codec.BOOL, "in S['pois']"),
        c("style.cat", Codec.STR, "PoiStyle: category (null without a style)"),
        c("style.col", Codec.STR, "PoiStyle: colour"),
        c("style.kind", Codec.STR, "PoiStyle: kind"),
        c("style.brand", Codec.STR, "PoiStyle: brand"),
        c("style.bg", Codec.STR, "PoiStyle: sign background"),
        c("style.fg", Codec.STR, "PoiStyle: sign foreground"),
        c("gy", Codec.CENTI, "the ground's height under it (terrain towns)"),
        c("door", Codec.BOOL, "it has a house number (addr:housenumber, truthy): a door where facades are drawn"),
        c("door_number", Codec.NDELTA, "the number on its door: the house number's leading digits (`re.match(r'\\d+')`)"),
        c(
            "building", Codec.NVARZ,
            "where facades are drawn: the building whose facade carries its door or shopfront (facade_details: the first shown building with a height, not a houseboat, whose box and ring hold it), as its index in S['buildings'] (that building's `index`, maybe in another tile); null for none",
        ),
    ) + tags(Layer.THING)

    val ITEM: List<Column> = listOf(
        c("source", Codec.U8, "0 S['cranes'], 1 S['masts'], 2 S['mtowers'], 3 S['towers'] (pylons), 4 S['turbines']"),
        c("index", Codec.DELTA, "source order in its list"),
        c("id", Codec.NVARZ, "the OSM id"),
        c("pos", Codec.POINT, "where it stands: the grid point itself for a mapped node, the nearest grid point otherwise"),
        c("x", Codec.CENTI, "its x in the frame, metres, where the scene has it off the grid (a crane mapped as a way: its outline's centre); null where pos is exact"),
        c("z", Codec.CENTI, "its z likewise"),
        c("poly", Codec.GEOM, "a crane's mapped outline (empty for nodes)"),
        c("h", Codec.CENTI, "height, metres"),
        c("rotor", Codec.CENTI, "a turbine's rotor diameter, metres"),
        c("tags", Codec.TAGS, "every tag (items are few)"),
    )

    val LABEL: List<Column> = listOf(
        c("source", Codec.U8, "0 a canal name candidate (S['labels'], kind canal); 1 a place node"),
        c("index", Codec.DELTA, "source order: in S['labels'], or in S['nodes']"),
        c("id", Codec.NVARZ, "a place node's id"),
        c("name", Codec.STR, "the name"),
        c("pos", Codec.POINT, "a canal: the way's middle vertex (pts[n / 2]); a place: the node"),
        c("prev", Codec.POINT, "a canal: the vertex before it (pts[n / 2 − 1]), the baseline's direction"),
        c("count", Codec.VARU, "a canal: the way's vertex count"),
        c("kind", Codec.STR, "a place: its place=* value"),
        c("ground_key", Codec.VARU, "a canal name: its slot among the marks (GroundKey.MARKS class 2); 0 for a place"),
    ) + tags(Layer.LABEL)

    val VARIANT: List<Column> = listOf(
        c("layer", Codec.U8, "the base feature's layer (building or part)"),
        c("index", Codec.DELTA, "the base feature's source order"),
        c("tolerance", Codec.U8, "the ladder index ([Keep.TOLERANCES]) the scene was loaded at"),
    ) + BUILDING_ATTRIBUTES

    /**
     * [fields] in their canonical form for [layer]: integers as the codec decodes them (`U8` an Int, the others a Long),
     * and without the fields that hold their column's default (null, zero, empty), as a decoded feature has them.
     */
    fun canonical(layer: Layer, fields: Map<String, Any?>): Map<String, Any?> {
        val cols = columns(layer).associateBy { it.name }
        val out = LinkedHashMap<String, Any?>(fields.size * 2)
        for ((k, v0) in fields) {
            val col = cols[k] ?: throw IllegalArgumentException("${layer.label} has no column $k")
            val v: Any? = when (col.codec) {
                Codec.U8 -> (v0 as Number?)?.toInt()
                Codec.VARU, Codec.VARZ, Codec.DELTA, Codec.NVARZ, Codec.NDELTA -> (v0 as Number?)?.toLong()
                Codec.IDS -> (v0 as List<*>?)?.map { (it as Number).toLong() }
                Codec.CENTI, Codec.F64 -> (v0 as Number?)?.toDouble()
                else -> v0
            }
            val isDefault = when (col.codec) {
                Codec.U8, Codec.VARU, Codec.VARZ, Codec.DELTA -> v == null || (v as Number).toLong() == 0L
                Codec.GEOM -> v == null || (v as Geom).size == 0 && v.keep == null
                Codec.TAGS -> v == null || (v as Map<*, *>).isEmpty()
                Codec.IDS -> v == null || (v as List<*>).isEmpty()
                else -> v == null
            }
            if (!isDefault) out[k] = v
        }
        return out
    }

    /** The columns of [layer]. */
    fun columns(layer: Layer): List<Column> = when (layer) {
        Layer.BUILDING, Layer.PART -> BUILDING
        Layer.GROUND -> GROUND
        Layer.WATER -> WATER
        Layer.QUAY -> QUAY
        Layer.ROAD -> ROAD
        Layer.LINE -> LINE
        Layer.TREE -> TREE
        Layer.THING -> THING
        Layer.ITEM -> ITEM
        Layer.LABEL -> LABEL
        Layer.VARIANT -> VARIANT
        Layer.MARKING, Layer.BUILT, Layer.DEM -> throw UnsupportedOperationException("the ${layer.label} layer comes with K30, K20b and K28")
    }
}
