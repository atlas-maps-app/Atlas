package app.atlas.engine

import app.atlas.geo.TileId

/** Hand-made scenes for tests: a few boxes or triangles in a known tile. */
object Synthetic {
    /** A box building over the scene rectangle [x0]..[x1] × [y0]..[y1] (metres), [h] tall. */
    fun box(
        x0: Double, y0: Double, x1: Double, y1: Double, h: Double, roof: RoofShape = RoofShape.FLAT, roofHeight: Double = 0.0,
        id: Long = 1, axisAlongX: Boolean = true,
    ): Building {
        // Clockwise on a y-down screen: positive area, as tiles wind outer rings.
        val ring = doubleArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
        check(Rings.area(ring) > 0)
        return Building(
            rings = listOf(ring), cut = null, centroidX = (x0 + x1) / 2, centroidY = (y0 + y1) / 2, area = (x1 - x0) * (y1 - y0),
            height = h, minHeight = 0.0, roof = roof, roofHeight = roofHeight,
            axisX = if (axisAlongX) 1.0 else 0.0, axisY = if (axisAlongX) 0.0 else 1.0,
            wallColour = Colours.parse("#7A4434"), roofColour = Colours.parse("#A9553A"), wallMaterial = "brick", roofMaterial = "roof_tiles",
            kind = "home", year = 1780, levels = 4, cornice = false, hoist = false, chimney = null, windows = null,
            seed = id, featureId = id * 10 + 2, guessed = emptySet(), why = "test",
        )
    }

    /** A scene in [id] holding [buildings] (meshed for real) and an optional [ground]. */
    fun scene(
        id: TileId, buildings: List<Building>, ground: FaceMesh = FaceMesh.EMPTY, look: Look = Look.DAY, zoom: Int = id.z,
        labels: List<LabelCandidate> = emptyList(), pois: List<PoiCandidate> = emptyList(),
    ): TileScene {
        val frame = TileFrame(id, id)
        val caster = ShadowCaster(buildings, look.light)
        val mesher = BuildingMesher(look.theme, look.shading, caster, zoom)
        val mb = MeshBuilder()
        val faceStart = IntArray(buildings.size + 1)
        val centre = FloatArray(buildings.size * 3)
        val radius = FloatArray(buildings.size)
        val infos = ArrayList<BuildingInfo>()
        for ((i, b) in buildings.withIndex()) {
            faceStart[i] = mb.faceCount
            mesher.mesh(b, i, mb)
            centre[3 * i] = b.centroidX.toFloat(); centre[3 * i + 1] = b.centroidY.toFloat(); centre[3 * i + 2] = (b.height / 2).toFloat()
            radius[i] = 60f
            infos += BuildingInfo(b.featureId, TileFrame.osmId(b.featureId), b.height.toFloat(), 0f, b.roof, b.kind, b.wallColour, b.roofColour,
                b.guessed, b.why, FloatArray(b.outer.size) { b.outer[it].toFloat() })
        }
        faceStart[buildings.size] = mb.faceCount
        return TileScene(
            id, id, look, id.unitX, id.unitY, frame.metresPerUnit, frame.size.toFloat(), ground, FaceMesh.EMPTY,
            ObjectSet(mb.build(), buildings.size, faceStart, centre, radius, IntArray(buildings.size) { it }),
            TreeSet.EMPTY, labels, pois, infos, SceneStats(buildings.size, 0, 0, 0, 0, labels.size, pois.size, 0.0),
        )
    }
}
