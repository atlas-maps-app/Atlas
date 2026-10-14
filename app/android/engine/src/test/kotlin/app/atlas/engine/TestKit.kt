package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.tiles.Mvt
import app.atlas.tiles.TileSource
import app.atlas.tiles.TileStack
import app.atlas.tiles.VectorTile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** The real OpenFreeMap tiles in app/android/testdata/omt, as a tile source. */
class DirectorySource(private val dir: File) : TileSource {
    override val minZoom = 0
    override val maxZoom = 14
    override val attribution = "OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"

    override suspend fun tile(id: TileId): ByteArray? = File(dir, "${id.z}/${id.x}/${id.y}.pbf").takeIf { it.isFile }?.readBytes()
}

object TestKit {
    val testdata: File get() = File(System.getProperty("atlas.testdata") ?: "testdata")
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../..")
    val previewDir: File get() = File(System.getProperty("atlas.preview") ?: "build/preview").also { it.mkdirs() }

    val omt: File get() = File(testdata, "omt")
    val stack: TileStack by lazy { TileStack(DirectorySource(omt)) }

    /** The centre fixture tile, 14/8414/5384, around the Westerkerk. */
    val centre = TileId(14, 8414, 5384)

    private val decoded = HashMap<TileId, VectorTile>()

    fun tile(id: TileId): VectorTile = synchronized(decoded) {
        decoded.getOrPut(id) { Mvt.decode(File(omt, "${id.z}/${id.x}/${id.y}.pbf").readBytes()) }
    }

    /** Builds the scene for [id] the way the app does: the best tile the stack has, overzoomed when needed. */
    fun scene(id: TileId, look: Look, extras: Extras? = null): TileScene? = runBlocking {
        val hit = stack.bestTile(id) ?: return@runBlocking null
        val vt = synchronized(decoded) { decoded.getOrPut(hit.source) { hit.decode() } }
        SceneBuilder.build(vt, id, look, extras, hit.source)
    }

    /** Real trees (natural=tree nodes, tree_row ways) from the Overpass extract around the Westerkerk. */
    val amsterdamTrees: Extras by lazy {
        val f = File(repo, "data/ams/raw.json")
        if (!f.isFile) return@lazy Extras()
        val root = Json.parseToJsonElement(f.readText()).jsonObject
        val trees = ArrayList<Place>()
        val rows = ArrayList<TreeRow>()
        for (e in root["elements"]!!.jsonArray) {
            val o = e.jsonObject
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: continue
            val type = o["type"]!!.jsonPrimitive.content
            if (type == "node" && tags["natural"] == "tree") {
                trees += Place(OsmId(OsmType.NODE, o["id"]!!.jsonPrimitive.long), LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double), tags)
            } else if (type == "way" && tags["natural"] == "tree_row") {
                val geom = (o["geometry"] as? JsonArray)?.map { p -> p.jsonObject.let { LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double) } }
                if (geom != null && geom.size >= 2) rows += TreeRow(geom, tags)
            }
        }
        Extras(trees, rows)
    }
}
