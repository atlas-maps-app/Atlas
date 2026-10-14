package app.atlas.engine

/** Road colours of one class: the [fill] and the [casing] drawn a little wider beneath it (null: none). */
data class RoadPaint(val fill: Int, val casing: Int?)

/**
 * A theme: the colours and light of the map as data. Colours are baked into scenes when they are built (so a theme
 * change rebuilds them); sky, haze and calm are applied per frame.
 */
data class Theme(
    val name: String,
    val night: Boolean = false,
    val chart: Boolean = false,
    // Sky and air.
    val skyTop: Int,
    val skyHorizon: Int,
    val fog: Int,
    val fogMax: Float,
    /** Haze begins at this many camera distances of depth and is full at the far edge of the ground. */
    val fogStart: Float = 1.3f,
    val fogPower: Float = 1.1f,
    /** When set, haze reaches [fogMax] this many camera distances past [fogStart] instead of at the far edge. */
    val fogRange: Float? = null,
    // Light.
    val ambient: Int,
    val ambientK: Float,
    val sun: Int,
    val sunK: Float,
    val skyK: Float = 1f,
    val tone: Boolean = true,
    val shadow: Int,
    val shadowOpacity: Float,
    // Ground.
    val earth: Int,
    /** Land cover and land use by OpenMapTiles subclass or class. */
    val land: Map<String, Int>,
    val water: Int,
    /**
     * When set, open water is coloured down the screen, from [waterTop] at the horizon (or the top of the view) to
     * [waterBottom] at its foot, with [water] laid over it at [waterVeil]: the boards' sky-lit water.
     */
    val waterTop: Int? = null,
    val waterBottom: Int? = null,
    val waterVeil: Float = 0f,
    /** Chart look: shallow water near the shore (within 40 m, then 110 m; deep from 140 m). */
    val waterShallow: Int? = null,
    val waterShallow2: Int? = null,
    /** The light band on the water along its banks (null: none), fading into [water] a couple of metres out. */
    val waterEdge: Int? = null,
    /** Wind glints: soft light streaks on the water (null: none), at [glintAlpha] over a screen-coloured water. */
    val glint: Int? = null,
    val glintAlpha: Float = 1f,
    /** Protected areas and national parks (the `park` layer): a tint under the land cover, and their outline from afar. */
    val park: Int,
    val parkEdge: Int,
    val quayWall: Int,
    val quayTop: Int,
    /** The wet band at the foot of a quay wall, where it meets the water (null: none). */
    val quayFoot: Int? = null,
    /** What the near bank shows over the strip of sunk water it hides (null: [quayTop]). */
    val quayBack: Int? = null,
    val roads: Map<String, RoadPaint>,
    val pedestrianArea: Int,
    val bridgeArea: Int,
    val bridgeSide: Int,
    val pier: Int,
    val platform: Int,
    val rail: Int,
    val railTie: Int,
    val tram: Int,
    val aeroway: Int,
    val runway: Int,
    val onewayArrow: Int,
    // Buildings.
    /** When set, buildings take these wall colours instead of their tradition's (chart and calm looks). */
    val wallPalette: List<Int>? = null,
    val roofPalette: List<Int>? = null,
    /** Flat footprints of buildings drawn from far (no heights): the roofscape colour. */
    val footprint: Int,
    val windowGlass: Int,
    val windowLit: List<Int>,
    val litWindowShare: Float,
    val cornice: Int,
    val plinth: Int,
    val ridge: Int,
    // Trees.
    val treeDark: Int,
    val treeMid: Int,
    val treeLight: Int,
    val trunk: Int,
    /** Leaves turning in autumn: [treeMid] moves toward this by the look's autumn share (null: they stay green). */
    val treeAutumn: Int? = null,
    // Labels.
    val placeLabel: Int,
    val waterLabel: Int,
    val roadLabel: Int,
    val numberLabel: Int,
    val halo: Int,
) {
    fun land(key: String?): Int? = if (key == null) null else land[key]

    fun road(cls: String): RoadPaint = roads[cls] ?: roads["minor"]!!

    companion object {
        private fun c(hex: String) = Colours.parse(hex)

        private fun paint(fill: String, casing: String?) = RoadPaint(c(fill), casing?.let { c(it) })

        /** The calm daylight the other looks start from: off-white ground, brick and terracotta, steel-blue canals. */
        private val BASE = Theme(
            name = "day",
            skyTop = c("#9DBDE3"), skyHorizon = c("#F1E8DC"), fog = c("#EEE9E2"), fogMax = 0.8f,
            ambient = c("#93A6CC"), ambientK = 0.62f, sun = c("#FFEBCB"), sunK = 0.8f, skyK = 1.05f,
            shadow = c("#2A3360"), shadowOpacity = 0.32f,
            earth = c("#ECE9E3"),
            land = mapOf(
                "wood" to "#A3BB8B", "forest" to "#A3BB8B", "scrub" to "#B7C89C", "heath" to "#CFCBA6", "grass" to "#C5D8A6",
                "grassland" to "#C5D8A6", "meadow" to "#C9D9A6", "park" to "#C3D7A2", "garden" to "#C8DAAA", "village_green" to "#C3D7A2",
                "recreation_ground" to "#C3D7A2", "allotments" to "#CBD8A4", "golf_course" to "#BFD6A0", "flowerbed" to "#C8D6A0",
                "fell" to "#CFD2AE", "shrubbery" to "#BCCCA0", "tundra" to "#D3D6BC",
                "farmland" to "#E6E3C6", "farm" to "#E6E3C6", "orchard" to "#D3DDB1", "vineyard" to "#D8DCB4", "plant_nursery" to "#D3DDB1",
                "wetland" to "#BFD0C4", "bog" to "#BFD0C4", "swamp" to "#B5C9BA", "marsh" to "#BFD0C4", "reedbed" to "#BCCDB2",
                "wet_meadow" to "#C3D3BA", "saltmarsh" to "#C3D3C4", "tidalflat" to "#D9D7CB", "mangrove" to "#A9C0A8",
                "sand" to "#EFE4C6", "beach" to "#F1E6C8", "dune" to "#EDE1C0", "ice" to "#F3F6F8", "glacier" to "#F3F6F8",
                "rock" to "#D8D4CC", "bare_rock" to "#D8D4CC", "scree" to "#DCD8D0",
                "cemetery" to "#CDD9BD", "pitch" to "#BCD4A0", "playground" to "#E8D4B0", "track" to "#E4C6AE", "stadium" to "#DDE5CF",
                "school" to "#EFE7DA", "university" to "#EFE7DA", "college" to "#EFE7DA", "kindergarten" to "#EFE7DA", "education" to "#EFE7DA",
                "hospital" to "#F1E3DF", "railway" to "#E3DED6", "industrial" to "#E6E2DB", "commercial" to "#EDE7E3", "retail" to "#EEE6E2",
                "garages" to "#E4E1DB", "military" to "#E5E2D8", "bus_station" to "#E6E2DB", "zoo" to "#D9E2C6", "theme_park" to "#E9DED0",
                "quarry" to "#DCD7CE", "dam" to "#D8D4CC", "library" to "#EFE7DA",
            ).mapValues { c(it.value) },
            water = c("#6386AA"), waterEdge = c("#8EA9C4"), glint = c("#A3B9CF"),
            park = c("#E2E7D6"), parkEdge = c("#A9BF92"),
            quayWall = c("#8C6A58"), quayTop = c("#DAD5CC"),
            roads = mapOf(
                "motorway" to paint("#F5DDA6", "#D9B673"), "trunk" to paint("#F8E5B8", "#DCBE86"),
                "primary" to paint("#FBF6EA", "#D6CAB4"), "secondary" to paint("#FCF9F2", "#D8D0C2"),
                "tertiary" to paint("#FCFAF5", "#D9D3C9"), "minor" to paint("#FAF8F4", "#DCD6CD"), "busway" to paint("#F7F3EC", "#D9D2C8"),
                "service" to paint("#F7F5F0", "#DDD8D0"), "track" to paint("#EADFCB", null), "raceway" to paint("#E8E2DA", "#D2CABE"),
                "path" to paint("#F3EFE8", null), "footway" to paint("#F3EFE8", null), "pedestrian" to paint("#F4F1EB", null),
                "cycleway" to paint("#EEDDD5", null), "steps" to paint("#E8E1D6", null), "bridleway" to paint("#EADFCB", null),
                "platform" to paint("#E3DFD8", null), "pier" to paint("#E6E1D8", "#C9C1B4"), "ferry" to paint("#8FB0D4", null),
            ),
            pedestrianArea = c("#F2EFE9"), bridgeArea = c("#E6E1D8"), bridgeSide = c("#B8AFA3"), pier = c("#E6E1D8"),
            platform = c("#E3DFD8"), rail = c("#A8A39C"), railTie = c("#8F8A83"), tram = c("#9C978F"),
            aeroway = c("#E2DED7"), runway = c("#CBC8C2"), onewayArrow = c("#CFC8BC"),
            footprint = c("#D5CEC6"),
            windowGlass = c("#4A5566"), windowLit = listOf("#FFD08A", "#FFC56F", "#FFE2AE", "#FFB85C").map { c(it) }, litWindowShare = 0f,
            cornice = c("#E6E1D6"), plinth = c("#47443F"), ridge = c("#F4EEE2"),
            treeDark = c("#3F6531"), treeMid = c("#5C8A43"), treeLight = c("#8DB866"), trunk = c("#5A4636"),
            placeLabel = c("#4D4742"), waterLabel = c("#2F5582"), roadLabel = c("#5E574F"), numberLabel = c("#7A7268"), halo = c("#F7F4EE"),
        )

        /**
         * The boards' daylight, as the Main board draws it (the living map's sunny moment): sand-grey ground with the
         * mapped land use over it, streets in their paving colour at their real widths, sky-lit water darkening down the
         * screen with wind streaks, brick quays, crowns turning with the season, a hard sun and a thin haze far out.
         */
        val DAY = BASE.copy(
            skyTop = c("#7FA6DA"), skyHorizon = c("#DCE7F2"), fog = c("#DFE6EE"), fogMax = 0.22f * 0.85f,
            fogStart = 0.85f, fogPower = 1.3f, fogRange = 2.2f,
            ambient = c("#93A6CC"), ambientK = 0.6f, sun = c("#FFEBCB"), sunK = 1f, skyK = 1f, tone = false,
            shadow = c("#27305A"), shadowOpacity = 0.32f,
            earth = c("#D6CFC2"),
            land = BASE.land + mapOf(
                // The living map's land cover by kind, then the canal town's own areas over it (grass, squares, parking).
                "residential" to "#E4DFD6", "industrial" to "#D3CEC6", "commercial" to "#E0D8D0", "retail" to "#E4D7D0",
                "construction" to "#D9CDB9", "railway" to "#D2CCC3", "military" to "#D6D2C6", "brownfield" to "#D7CDBC",
                "greenfield" to "#CBD3A8", "education" to "#E2DBCF", "school" to "#E2DBCF", "university" to "#E2DBCF",
                "college" to "#E2DBCF", "kindergarten" to "#E2DBCF", "library" to "#E2DBCF", "farmland" to "#D8D4AC",
                "farm" to "#D8D4AC", "farmyard" to "#D9CFB8", "meadow" to "#C2D19B", "grassland" to "#BFCF9C",
                "village_green" to "#B8CC96", "recreation_ground" to "#B8CC96", "cemetery" to "#B4C2A0", "allotments" to "#C7CE9A",
                "orchard" to "#B6C98C", "golf_course" to "#B1CD8E", "heath" to "#BCAB8C", "scrub" to "#9CB084",
                "forest" to "#7E9B6B", "wood" to "#7E9B6B", "wetland" to "#A6BBAB", "sand" to "#E4D6B3", "beach" to "#EDE1C1",
                "dune" to "#E4D6B3", "bare_rock" to "#B1ACA2", "rock" to "#ADA89F", "scree" to "#B5B0A6", "pitch" to "#A3C28C",
                "track" to "#C98F74", "flowerbed" to "#B9C48F", "playground" to "#D9B48A", "parking" to "#C9C4BA",
                "park" to "#A9C38A", "garden" to "#A9C38A", "grass" to "#A9C38A",
            ).mapValues { c(it.value) },
            water = c("#4F7590"), waterTop = c("#A6BFD4"), waterBottom = c("#2D506C"), waterVeil = 0.12f, waterEdge = null,
            glint = c("#FFFFFF"), glintAlpha = 0.23f,
            quayWall = c("#6E5546"), quayTop = c("#B8B0A2"), quayFoot = c("#2C2A22"), quayBack = c("#E4DFD6"),
            roads = mapOf(
                // Big roads in asphalt, the rest in the street default (no surface is in the tiles), cycle tracks red.
                "motorway" to paint("#77777A", null), "trunk" to paint("#77777A", null), "primary" to paint("#77777A", null),
                "secondary" to paint("#C9C1B4", null), "tertiary" to paint("#C9C1B4", null), "minor" to paint("#C9C1B4", null),
                "busway" to paint("#C9C1B4", null), "service" to paint("#C9C1B4", null), "track" to paint("#C4C2BC", null),
                "raceway" to paint("#9B9A99", null), "path" to paint("#C9C1B4", null), "footway" to paint("#C9C1B4", null),
                "pedestrian" to paint("#DCD3C4", null), "cycleway" to paint("#C98A7A", null), "steps" to paint("#C9C1B4", null),
                "bridleway" to paint("#C4C2BC", null), "platform" to paint("#CFC6B6", null), "pier" to paint("#A89A86", null),
                "ferry" to paint("#8FB0D4", null),
            ),
            pedestrianArea = c("#DCD3C4"), bridgeArea = c("#CFC6B6"), pier = c("#A89A86"), platform = c("#CFC6B6"),
            rail = c("#A0978B"), railTie = c("#5E5A56"), tram = c("#6E6A66"),
            treeMid = c("#4E6B35"), treeAutumn = c("#9C8A36"),
            placeLabel = c("#3A3F4B"), waterLabel = c("#2F5A84"), halo = c("#FFFFFF"),
        )

        /** Night: dark ground and blue-grey buildings, windows lit warm. */
        val NIGHT = BASE.copy(
            name = "night", night = true,
            skyTop = c("#050814"), skyHorizon = c("#1C2340"), fog = c("#161D36"), fogMax = 0.85f,
            ambient = c("#39456B"), ambientK = 0.5f, sun = c("#000000"), sunK = 0f, tone = false,
            shadow = c("#000000"), shadowOpacity = 0f,
            earth = c("#171B27"),
            land = BASE.land.mapValues { (k, v) ->
                when (k) {
                    "wood", "forest", "scrub", "grass", "grassland", "meadow", "park", "garden", "village_green", "recreation_ground",
                    "allotments", "golf_course", "cemetery", "pitch", "flowerbed" -> c("#111E22")
                    else -> Colours.mix(c("#141824"), v, 0.08f)
                }
            },
            water = c("#0C1830"), waterEdge = c("#16243E"), glint = c("#24355A"),
            park = c("#151D22"), parkEdge = c("#233530"),
            quayWall = c("#2E2C30"), quayTop = c("#2E3240"),
            roads = mapOf(
                "motorway" to paint("#5A4A32", "#171B27"), "trunk" to paint("#54472F", "#171B27"),
                "primary" to paint("#463F36", "#171B27"), "secondary" to paint("#413B34", "#171B27"),
                "tertiary" to paint("#3D3832", "#171B27"), "minor" to paint("#36332F", "#1B1E29"), "busway" to paint("#36332F", "#1B1E29"),
                "service" to paint("#2D2C2C", null), "track" to paint("#272829", null), "raceway" to paint("#2D2C2C", null),
                "path" to paint("#2A2A2D", null), "footway" to paint("#2A2A2D", null), "pedestrian" to paint("#2E2D2E", null),
                "cycleway" to paint("#302A2C", null), "steps" to paint("#2A2A2D", null), "bridleway" to paint("#272829", null),
                "platform" to paint("#2E2D2E", null), "pier" to paint("#2E2D2E", null), "ferry" to paint("#1A2840", null),
            ),
            pedestrianArea = c("#2B2A2C"), bridgeArea = c("#33302E"), bridgeSide = c("#1A1C22"), pier = c("#2B2A2C"),
            platform = c("#22252E"), rail = c("#2E3038"), railTie = c("#24262D"), tram = c("#33353D"),
            aeroway = c("#1E212A"), runway = c("#2A2D35"), onewayArrow = c("#3A3C44"),
            footprint = c("#262B3B"),
            windowGlass = c("#141824"), litWindowShare = 0.38f,
            cornice = c("#3A3C44"), plinth = c("#121318"), ridge = c("#3A3C44"),
            treeDark = c("#0E1C1F"), treeMid = c("#16292D"), treeLight = c("#1F3A3E"), trunk = c("#0B0D10"),
            placeLabel = c("#C9CEDA"), waterLabel = c("#7FA3CF"), roadLabel = c("#A9AFBD"), numberLabel = c("#8A90A0"), halo = c("#0D1120"),
        )

        /** A nautical chart for boats: buff land, pale water deepening from the shore, calm yellow buildings. */
        val CHART = BASE.copy(
            name = "chart", chart = true,
            skyTop = c("#B9CFE6"), skyHorizon = c("#EEF1EE"), fog = c("#EEF0EC"), fogMax = 0.7f,
            ambient = c("#A9B4C8"), ambientK = 0.7f, sun = c("#FFF4DE"), sunK = 0.55f, skyK = 1.1f,
            shadow = c("#6B5A2E"), shadowOpacity = 0.12f,
            earth = c("#F1E2B2"),
            land = BASE.land.mapValues { (k, v) ->
                when (k) {
                    "wood", "forest", "scrub", "grass", "grassland", "meadow", "park", "garden", "village_green", "recreation_ground",
                    "allotments", "golf_course", "cemetery", "pitch", "flowerbed" -> c("#D7DB9E")
                    else -> Colours.mix(c("#F1E2B2"), v, 0.15f)
                }
            },
            water = c("#E4F2F9"), waterShallow = c("#A7D3EC"), waterShallow2 = c("#C6E4F3"), waterEdge = null, glint = null,
            park = c("#EADFAE"), parkEdge = c("#B5AE72"),
            quayWall = c("#C8B37C"), quayTop = c("#E6D7A6"),
            roads = BASE.roads.mapValues { (k, _) ->
                when (k) {
                    "motorway", "trunk", "primary" -> paint("#F7EBC7", "#D2BC7E")
                    "ferry" -> paint("#C2185B", null)
                    else -> paint("#F6EBCB", if (k in setOf("secondary", "tertiary", "minor", "busway", "service")) "#DCC78C" else null)
                }
            },
            pedestrianArea = c("#ECDDAA"), bridgeArea = c("#E6D7A6"), bridgeSide = c("#B49A5E"), pier = c("#E6D7A6"),
            platform = c("#E6D7A6"), rail = c("#B7A57A"), railTie = c("#9C8A5E"), tram = c("#B7A57A"),
            aeroway = c("#E9DAA8"), runway = c("#D6C795"), onewayArrow = c("#D8C58E"),
            wallPalette = listOf("#EDD9A0", "#E8D195", "#F0DEAA", "#E4CB8C", "#EAD49A").map { c(it) },
            roofPalette = listOf("#F2E3B4", "#EEDDA9", "#F4E6BC", "#EAD7A0", "#EFE0AE").map { c(it) },
            footprint = c("#E4CB8C"),
            windowGlass = c("#B9C3C9"),
            cornice = c("#F4E8C4"), plinth = c("#C9B27A"), ridge = c("#F7EDCF"),
            treeDark = c("#94A864"), treeMid = c("#AFBF78"), treeLight = c("#C7D494"), trunk = c("#8A7A55"),
            placeLabel = c("#5A4A2A"), waterLabel = c("#2F5B86"), roadLabel = c("#6E6040"), numberLabel = c("#8A7A55"), halo = c("#FBF3DA"),
        )

        /** A calm grey look: paper-white buildings, soft shadows, colour kept for water and parks only. */
        val GRAY = BASE.copy(
            name = "gray",
            skyTop = c("#DCE3EA"), skyHorizon = c("#F2F2F0"), fog = c("#F0F0EE"), fogMax = 0.78f,
            ambient = c("#C9CCD6"), ambientK = 0.72f, sun = c("#FFF6EC"), sunK = 0.5f, skyK = 1.15f,
            shadow = c("#5A6275"), shadowOpacity = 0.12f,
            earth = c("#F1F0EC"),
            land = BASE.land.mapValues { (k, v) ->
                when (k) {
                    "wood", "forest", "scrub", "grass", "grassland", "meadow", "park", "garden", "village_green", "recreation_ground",
                    "allotments", "golf_course", "cemetery", "pitch", "flowerbed" -> c("#DCE3D5")
                    else -> Colours.mix(c("#F1F0EC"), Colours.desaturate(v, 1f), 0.25f)
                }
            },
            water = c("#C9D7E5"), waterEdge = c("#DCE5EE"), glint = c("#E2E9F1"),
            park = c("#E9ECE4"), parkEdge = c("#C8D2C0"),
            quayWall = c("#C4C6CB"), quayTop = c("#E8E8E4"),
            roads = BASE.roads.mapValues { (k, v) ->
                if (k == "ferry") paint("#B9C9DA", null) else RoadPaint(c("#FFFFFF"), v.casing?.let { c("#DDDCD8") })
            },
            pedestrianArea = c("#F6F6F3"), bridgeArea = c("#ECECE8"), bridgeSide = c("#CFCFCB"), pier = c("#ECECE8"),
            platform = c("#EAEAE6"), rail = c("#C9C9C6"), railTie = c("#B9B9B6"), tram = c("#C9C9C6"),
            aeroway = c("#ECECE8"), runway = c("#DEDEDA"), onewayArrow = c("#E0E0DC"),
            wallPalette = listOf("#F5F4F1", "#F1F0EC", "#EEEDE9", "#F3F2EE").map { c(it) },
            roofPalette = listOf("#F7F6F3", "#F4F3EF", "#F1F0EC").map { c(it) },
            footprint = c("#E6E5E1"),
            windowGlass = c("#D8DCE2"),
            cornice = c("#FAFAF8"), plinth = c("#D6D6D2"), ridge = c("#FFFFFF"),
            treeDark = c("#B3C0AB"), treeMid = c("#C7D2C0"), treeLight = c("#D8E0D2"), trunk = c("#A8A49C"),
            placeLabel = c("#6A6A6A"), waterLabel = c("#6F8AA6"), roadLabel = c("#7A7A78"), numberLabel = c("#9A9A98"), halo = c("#FFFFFF"),
        )

        val ALL = listOf(DAY, NIGHT, CHART, GRAY)

        fun named(name: String): Theme? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
