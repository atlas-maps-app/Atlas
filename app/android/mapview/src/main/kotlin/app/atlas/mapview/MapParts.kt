package app.atlas.mapview

import app.atlas.mapcore.state.CameraWriter

/**
 * A part of the map's picture the app can leave out: every layer and effect the core draws (the camera state's parts,
 * CameraWriter.PART_*; style::camera_part in the core), each a switch of its own in a debug build's Look settings (Mine →
 * Map look → Engine parts), so the map's panel shows on the phone what each one costs (ENGINE.md, "On the user's phone
 * first"). A part left out costs nothing: the core decides, lays and draws none of it. Each is listed in one [group]
 * (its home), and [drawn] is its default. The clouds' shadows and the water's light were left out from alpha 19 (the
 * user, 28 October: "turn off cloud shadows and water light by default without them it runs pretty well these need to be
 * worked on later") until their rework (ENGINE.md step 4: the water's light on by default, the cloud shadows on the
 * ground) brought a moving frame with them to within about a millisecond and a half of one without them. The roofs'
 * edges and the plinths are left out by default (the user, 1 November: "Roof edges off by default for now", then of the
 * plinths: "And the thin dark band off by default"), and a quay wall's faces and a facade's flat detail each have their
 * own switch ("Every part gets its own switch").
 */
enum class MapPart(number: Int, val group: MapPartGroup, val drawn: Boolean = true) {
    GROUND(CameraWriter.PART_GROUND, MapPartGroup.GROUND),
    WATER(CameraWriter.PART_WATER, MapPartGroup.GROUND),
    CANOPY(CameraWriter.PART_CANOPY, MapPartGroup.GROUND),
    GROUND_LINES(CameraWriter.PART_GROUND_LINES, MapPartGroup.GROUND),
    SETTS(CameraWriter.PART_SETTS, MapPartGroup.GROUND),
    GROUND_TEXT(CameraWriter.PART_GROUND_TEXT, MapPartGroup.GROUND),
    /** The quay walls' brick faces, down to the water. */
    QUAYS(CameraWriter.PART_QUAYS, MapPartGroup.GROUND),

    /** The dark band at the water's level under a quay wall. */
    QUAY_WATERLINES(CameraWriter.PART_QUAY_WATERLINES, MapPartGroup.GROUND),

    /** The light stone top along a quay wall at the street's level. */
    QUAY_TOPS(CameraWriter.PART_QUAY_TOPS, MapPartGroup.GROUND),
    SKY(CameraWriter.PART_SKY, MapPartGroup.GROUND),
    BUILDING_SHADOWS(CameraWriter.PART_BUILDING_SHADOWS, MapPartGroup.SHADOWS),

    /** The soft shadows' blur: without it the buildings' shadows are drawn sharp, with no mask or blur. */
    SHADOW_BLUR(CameraWriter.PART_SHADOW_BLUR, MapPartGroup.SHADOWS),
    PARK_SHADOWS(CameraWriter.PART_PARK_SHADOWS, MapPartGroup.SHADOWS),
    CLOUD_SHADOWS(CameraWriter.PART_CLOUD_SHADOWS, MapPartGroup.WEATHER),
    REFLECTIONS(CameraWriter.PART_REFLECTIONS, MapPartGroup.WATER_LIGHT),
    RIPPLE(CameraWriter.PART_RIPPLE, MapPartGroup.WATER_LIGHT),
    GLITTER(CameraWriter.PART_GLITTER, MapPartGroup.WATER_LIGHT),
    RINGS(CameraWriter.PART_RINGS, MapPartGroup.WATER_LIGHT),
    GLINTS(CameraWriter.PART_GLINTS, MapPartGroup.WATER_LIGHT),

    /** The night's light streaks on the water, which drew with the reflections until each part had its switch. */
    WATER_STREAKS(CameraWriter.PART_WATER_STREAKS, MapPartGroup.WATER_LIGHT),
    LIT_STREETS(CameraWriter.PART_LIT_STREETS, MapPartGroup.NIGHT),
    WET_CORES(CameraWriter.PART_WET_CORES, MapPartGroup.NIGHT),
    LAMP_POOLS(CameraWriter.PART_LAMP_POOLS, MapPartGroup.NIGHT),
    LAMP_HALOS(CameraWriter.PART_LAMP_HALOS, MapPartGroup.NIGHT),
    LAMP_STREAKS(CameraWriter.PART_LAMP_STREAKS, MapPartGroup.NIGHT),

    /** The glow about a place that is open at night, by its opening hours. */
    OPEN_GLOWS(CameraWriter.PART_OPEN_GLOWS, MapPartGroup.NIGHT),
    RAIN(CameraWriter.PART_RAIN, MapPartGroup.WEATHER),
    GOLDEN_WASH(CameraWriter.PART_GOLDEN_WASH, MapPartGroup.WEATHER),
    WALLS(CameraWriter.PART_WALLS, MapPartGroup.BUILDINGS),

    /** The walls' near cells, nearer than 460 m: without them the walls are drawn plain, as farther away. */
    NEAR_CELLS(CameraWriter.PART_NEAR_CELLS, MapPartGroup.BUILDINGS),
    ROOFS(CameraWriter.PART_ROOFS, MapPartGroup.BUILDINGS),
    ROOF_EDGES(CameraWriter.PART_ROOF_EDGES, MapPartGroup.BUILDINGS, drawn = false),

    /** The walls' and roofs' cull on the GPU: without it they are drawn as their runs, every primitive submitted. */
    KEPT_CULL(CameraWriter.PART_KEPT_CULL, MapPartGroup.BUILDINGS),
    WINDOWS(CameraWriter.PART_WINDOWS, MapPartGroup.FACADES),
    WINDOW_DEPTH(CameraWriter.PART_WINDOW_DEPTH, MapPartGroup.FACADES),
    /** A shop floor's glazing. */
    SHOP_GLAZING(CameraWriter.PART_SHOP_GLAZING, MapPartGroup.FACADES),

    /** The thin dark band along the foot of a wall on the street, within 760 m. */
    PLINTHS(CameraWriter.PART_PLINTHS, MapPartGroup.FACADES, drawn = false),

    /** The white cornice along an old house's top, within 520 m. */
    CORNICES(CameraWriter.PART_CORNICES, MapPartGroup.FACADES),
    CHURCH_WINDOWS(CameraWriter.PART_CHURCH_WINDOWS, MapPartGroup.FACADES),
    MATERIAL_FACES(CameraWriter.PART_MATERIAL_FACES, MapPartGroup.FACADES),
    HOIST_BEAMS(CameraWriter.PART_HOIST_BEAMS, MapPartGroup.FACADES),
    KIT_DETAILS(CameraWriter.PART_KIT_DETAILS, MapPartGroup.FACADES),
    DOORS(CameraWriter.PART_DOORS, MapPartGroup.FACADES),
    HOUSE_NUMBERS(CameraWriter.PART_HOUSE_NUMBERS, MapPartGroup.FACADES),

    /** A shop's, café's or hotel's front on its street: its frame, glass, awning, sign and blade. */
    SHOPFRONTS(CameraWriter.PART_SHOPFRONTS, MapPartGroup.FACADES),

    /** A café's or restaurant's tables, chairs and parasols on the pavement while it is open. */
    TERRACES(CameraWriter.PART_TERRACES, MapPartGroup.FACADES),
    TREES(CameraWriter.PART_TREES, MapPartGroup.TREES),
    LEAF_FILTERS(CameraWriter.PART_LEAF_FILTERS, MapPartGroup.TREES),
    FURNITURE(CameraWriter.PART_FURNITURE, MapPartGroup.TREES),
    REGION_ITEMS(CameraWriter.PART_REGION_ITEMS, MapPartGroup.TREES),
    LABELS(CameraWriter.PART_LABELS, MapPartGroup.OVER),
    ROUTES(CameraWriter.PART_ROUTES, MapPartGroup.OVER),
    MARKERS(CameraWriter.PART_MARKERS, MapPartGroup.OVER),
    LOCATION(CameraWriter.PART_LOCATION, MapPartGroup.OVER),
    FROSTED(CameraWriter.PART_FROSTED, MapPartGroup.OVER),
    ;

    /** Its bit in the camera state's parts. */
    val bit: Long = 1L shl number
}

/**
 * A switch that turns a whole group of parts off, or on again: the parts listed under it (their [MapPart.group]) and
 * [also] those other groups list that belong to it too (the clouds' shadows are weather and shadows alike).
 */
enum class MapPartGroup(private val also: () -> Set<MapPart> = { emptySet() }) {
    GROUND,
    WATER_LIGHT,

    /** The weather's effects: the rain, the clouds' shadows, the golden wash and the wet moment's set. */
    WEATHER({ setOf(MapPart.RINGS, MapPart.WET_CORES, MapPart.LAMP_STREAKS) }),

    /** The night's lights, the water's streaks among them. */
    NIGHT({ setOf(MapPart.WATER_STREAKS) }),
    SHADOWS({ setOf(MapPart.CLOUD_SHADOWS) }),

    /** All of the buildings: their walls, roofs and edges, and every part of their facades. */
    BUILDINGS({ MapPart.entries.filterTo(mutableSetOf()) { it.group == MapPartGroup.FACADES } }),
    FACADES,
    TREES,
    OVER,
    ;

    /** The parts listed under it, in their order. */
    val listed: List<MapPart> get() = MapPart.entries.filter { it.group == this }

    /** Every part it turns off or on. */
    val parts: Set<MapPart> get() = listed.toSet() + also()
}

/** The parts the map leaves out, as the settings keep them (their names) and as the camera state carries them. */
object MapParts {
    /** The parts left out until the user switches one: the roofs' edges and the plinths. */
    val byDefault: Set<MapPart> = MapPart.entries.filterTo(mutableSetOf()) { !it.drawn }

    /** The camera state's word of [parts]. */
    fun bits(parts: Set<MapPart>): Long = parts.fold(0L) { word, p -> word or p.bit }

    /** The parts of [names] (MapPart names; one that is no longer a part is skipped). */
    fun named(names: Set<String>): Set<MapPart> = names.mapNotNullTo(mutableSetOf()) { n -> MapPart.entries.firstOrNull { it.name == n } }

    /** What [group]'s switch leaves out when tapped with [off] left out: all of its parts while any is drawn, else none. */
    fun toggled(off: Set<MapPart>, group: MapPartGroup): Set<MapPart> =
        if (group.parts.any { it !in off }) off + group.parts else off - group.parts

    /** What a part's switch leaves out when tapped with [off] left out. */
    fun toggled(off: Set<MapPart>, part: MapPart): Set<MapPart> = if (part in off) off - part else off + part
}
