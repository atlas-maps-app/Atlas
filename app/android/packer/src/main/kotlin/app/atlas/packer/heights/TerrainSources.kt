package app.atlas.packer.heights

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The terrain's source registry (`app/android/packs/terrain-sources.json`, ENGINE.md part 6): every elevation source, one
 * entry each, and what terrain.yml builds from them for a region of packs/regions.json. A further country is one entry
 * under `sources` and one under `regions`; the file's `about` says what each field means.
 */
class TerrainSources(val sources: List<Source>, val regions: List<Region>) {
    /** How a source is read. */
    enum class Format(val label: String) {
        /** One cloud-optimised GeoTIFF per degree cell, named by [Source.pattern] (Copernicus). */
        COG_TILES("cog-tiles"),

        /** GeoTIFF sheets listed by an index, of which one overview is read (AHN, Kartverket). */
        COG_SHEETS("cog-sheets"),

        /** An OGC coverage service asked for boxes of its own grid (TINITALY). */
        WCS("wcs"),
    }

    /** A sheet index: a GeoJSON of sheets whose property [file] holds each one's URL, or a GDAL VRT of them. */
    class Index(val kind: String, val url: String, val file: String?)

    /** A coverage service: its coverage, its axes' labels (east, north), more query parameters, the most cells a side it is asked for. */
    class Wcs(val coverage: String, val axes: List<String>, val extra: String?, val block: Int)

    class Source(
        val id: String,
        val name: String,
        /** The regions it covers; empty for the world. */
        val area: List<String>,
        val priority: Int,
        val format: Format,
        val hosts: List<String>,
        val pattern: String?,
        val index: Index?,
        val wcs: Wcs?,
        /** The overview of each sheet that is read: 0 the sheet itself. */
        val overview: Int,
        val crs: String,
        val datum: String,
        /** `dtm` or `dsm`. */
        val model: String,
        val resolution: Double,
        /** The resolution it is read at, metres. */
        val read: Double,
        /** How far into a hole its own samples fill it, and how far the offset to the next source is feathered out, metres. */
        val fill: Double,
        val feather: Double,
        /**
         * The box (west, south, east, north, degrees) the operation from its CRS to the lattice's is chosen for, where
         * its datum is its own (the Dutch grid's Amersfoort): GDAL would else choose by the bounds of whichever raster
         * it warps, a country's mosaic or a small cut of it. (On 12 November both got the same one; the box keeps it so.)
         */
        val operationArea: DoubleArray?,
        /** The height its rasters give the open sea, metres, where they give it one (TINITALY's 0): a tile whose every sample of it stands there is sea, and is not built. */
        val sea: Double?,
        val licence: String,
        val licenceUrl: String?,
        val credit: String,
        /** The sentence its licence asks beside the credit (Copernicus's liability sentence), or none. */
        val notice: String?,
        /** The day the entry was read from its source. */
        val date: String,
    )

    /** Levels built over an area: `whole`, or the renders' regions' boxes named in [views] grown by [growKm]. */
    class Levels(val levels: List<Int>, val whole: Boolean, val views: List<String>, val growKm: Double)

    /** What is built for a region: its sources in the order of their priority, its levels, and the renders' regions whose boxes its samples are checked in. */
    class Region(val id: String, val sources: List<String>, val levels: List<Levels>, val checkViews: List<String>)

    fun source(id: String): Source = sources.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no terrain source '$id' in the registry")

    fun region(id: String): Region = regions.firstOrNull { it.id == id } ?: throw IllegalArgumentException("the registry builds no terrain for region '$id'")

    companion object {
        fun read(file: File): TerrainSources = parse(file.readText())

        fun parse(text: String): TerrainSources {
            val root = Json.parseToJsonElement(text).jsonObject
            fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("a registry entry without '$key': ${this["id"]}")
            fun JsonObject.textOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun JsonObject.number(key: String, absent: Double? = null): Double = (this[key] as? JsonPrimitive)?.double ?: absent ?: throw IllegalArgumentException("a registry entry without '$key': ${this["id"]}")
            val sources = root.getValue("sources").jsonArray.map { e ->
                val o = e.jsonObject
                val format = o.text("format").let { f -> Format.entries.firstOrNull { it.label == f } ?: throw IllegalArgumentException("source ${o.text("id")}: no format '$f'") }
                val area = when (val a = o["area"]) {
                    is JsonArray -> a.map { it.jsonPrimitive.content }
                    else -> emptyList()
                }
                Source(
                    o.text("id"), o.text("name"), area, o.number("priority").toInt(), format, o.getValue("hosts").jsonArray.map { it.jsonPrimitive.content },
                    o.textOrNull("pattern"),
                    (o["index"] as? JsonObject)?.let { Index(it.text("kind"), it.text("url"), it.textOrNull("file")) },
                    (o["wcs"] as? JsonObject)?.let { w -> Wcs(w.text("coverage"), w.getValue("axes").jsonArray.map { it.jsonPrimitive.content }, w.textOrNull("extra"), w.getValue("block").jsonPrimitive.int) },
                    o.number("overview", 0.0).toInt(), o.text("crs"), o.text("datum"), o.text("model"), o.number("resolution_m"), o.number("read_m"),
                    o.number("fill_m", 0.0), o.number("feather_m", 0.0),
                    (o["operation_area"] as? JsonArray)?.map { it.jsonPrimitive.double }?.toDoubleArray()?.also { a -> require(a.size == 4 && a[0] < a[2] && a[1] < a[3]) { "source ${o.text("id")}: an operation area of ${a.toList()}" } },
                    (o["sea_m"] as? JsonPrimitive)?.double, o.text("licence"), o.textOrNull("licence_url"), o.text("credit"), o.textOrNull("notice"), o.text("read"),
                ).also { s ->
                    require(s.id.matches(Regex("[a-z0-9-]+"))) { "a source id of '${s.id}'" }
                    require(s.hosts.isNotEmpty()) { "source ${s.id} has no host" }
                    require(s.model == "dtm" || s.model == "dsm") { "source ${s.id} is a '${s.model}'" }
                    require(s.resolution > 0 && s.read >= s.resolution) { "source ${s.id}: read at ${s.read} m of ${s.resolution} m" }
                    when (s.format) {
                        Format.COG_TILES -> require(s.pattern != null) { "source ${s.id} has no pattern" }
                        Format.COG_SHEETS -> require(s.index != null && s.index.kind in setOf("geojson", "vrt")) { "source ${s.id} has no index this packer reads" }
                        Format.WCS -> require(s.wcs != null && s.wcs.axes.size == 2 && s.wcs.block in 16..8192) { "source ${s.id} has no coverage service" }
                    }
                }
            }
            require(sources.map { it.id }.toSet().size == sources.size) { "a source id twice" }
            val regions = root.getValue("regions").jsonArray.map { e ->
                val o = e.jsonObject
                Region(
                    o.text("id"), o.getValue("sources").jsonArray.map { it.jsonPrimitive.content },
                    o.getValue("levels").jsonArray.map { l ->
                        val lo = l.jsonObject
                        val area = lo.text("area")
                        require(area == "whole" || area == "views") { "region ${o.text("id")}: an area of '$area'" }
                        Levels(
                            lo.getValue("levels").jsonArray.map { it.jsonPrimitive.int }, area == "whole",
                            (lo["views"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(), lo.number("grow_km", 0.0),
                        ).also { require(it.levels.isNotEmpty() && (it.whole || it.views.isNotEmpty())) { "region ${o.text("id")}: levels without an area" } }
                    },
                    (o["check_views"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
            }
            val all = TerrainSources(sources, regions)
            for (r in regions) {
                require(r.sources.isNotEmpty()) { "region ${r.id} has no source" }
                val ps = r.sources.map { all.source(it).priority }
                require(ps == ps.sortedDescending()) { "region ${r.id}'s sources are not in the order of their priority" }
            }
            return all
        }
    }
}
