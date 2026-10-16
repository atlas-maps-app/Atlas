package app.atlas.rendertiles.model

import app.atlas.rendertiles.codec.LeSink
import app.atlas.rendertiles.codec.LeSource
import app.atlas.rendertiles.codec.TileFormatException
import app.atlas.rendertiles.codec.Values

/**
 * The `rhead` section: what the core needs to place a pack's tiles and draw them by its region's rules, so it reads no
 * JSON (plan-vector Appendix A).
 *
 * Little-endian: u32 [Format.HEAD_MAGIC], u16 [Format.PAYLOAD], u8 profile ([Profile.code]), u8 level count and the
 * levels (one byte each, finest first), the frame's lat0, lon0, kx and kz as the port's doubles (8 bytes each, their
 * exact bits), then strings (varint length, UTF-8): the region's id, [engineVersion], [styleVersion], [source]; then a
 * varint view count, and per view its name (a string), its camera as a typed value ([Values], strings inline: the
 * region's own view as `places.py` writes it, target, yaw, pitch, dist, fov, cy, radius, ground_r and the rest) and a u8:
 * the ladder index ([Keep.TOLERANCES], `style`'s `keep`) of the tolerance its renders load the region at
 * (`GENERALISE`); then the [bands]: a u8 count B (at least 1), B u8 ladder indexes, and B − 1 doubles (their bits), the
 * distances where each band but the last ends.
 *
 * **Which tolerance a frame loads at** (the port's `Lod.tolerance`): a camera the ladder snaps onto one of the views
 * takes that view's index; any other camera takes the band of its distance d from the eye to its target, metres: the
 * first band i whose end exceeds d (`d < end[i]`), else the last. The living map has one band, index 0: it is never
 * simplified.
 */
class RenderHead(
    val profile: Profile,
    val region: String,
    val levels: IntArray,
    val lat0: Double,
    val lon0: Double,
    val kx: Double,
    val kz: Double,
    /** `EngineVersion.ID` of the port that derived the tiles. */
    val engineVersion: String,
    /** [StyleVersion.ID] of the derivation (stage P). */
    val styleVersion: String,
    /** The source's identity: the element pack's SHA-256. */
    val source: String,
    /** The region's views (`ViewLadder`'s knots), nearest first. */
    val views: List<View>,
    /** The live map's tolerance bands by distance, nearest first. */
    val bands: List<Band>,
) {
    init {
        require(bands.isNotEmpty() && bands.size <= 255) { "${bands.size} bands" }
        for ((i, b) in bands.withIndex()) require((b.upTo == null) == (i == bands.size - 1)) { "every band but the last ends, and the last does not" }
    }

    /** One of the region's views: its name (`grachten_home`), its camera, and the ladder index of its tolerance. */
    class View(val name: String, val camera: Map<String, Any?>, val tolerance: Int) {
        init { require(tolerance in 0 until Keep.TOLERANCES.size) { "view $name at ladder index $tolerance" } }
        override fun equals(other: Any?): Boolean = other is View && name == other.name && camera == other.camera && tolerance == other.tolerance
        override fun hashCode(): Int = name.hashCode() * 31 + camera.hashCode()
        override fun toString(): String = "View($name, $camera, tolerance $tolerance)"
    }

    /** A band: the ladder index of its tolerance, and the distance (metres, eye to target) it ends at; null for the last. */
    class Band(val tolerance: Int, val upTo: Double?) {
        init { require(tolerance in 0 until Keep.TOLERANCES.size) { "a band at ladder index $tolerance" } }
        override fun equals(other: Any?): Boolean = other is Band && tolerance == other.tolerance && upTo?.toRawBits() == other.upTo?.toRawBits()
        override fun hashCode(): Int = tolerance * 31 + (upTo?.hashCode() ?: 0)
        override fun toString(): String = "Band($tolerance, ${upTo ?: "on"})"
    }

    /** The ladder index a camera loads at: [view]'s own when the ladder snapped onto one, else the band of [dist]. */
    fun toleranceOf(view: String?, dist: Double): Int {
        if (view != null) return views.firstOrNull { it.name == view }?.tolerance ?: throw IllegalArgumentException("no view $view")
        for (b in bands) if (b.upTo == null || dist < b.upTo) return b.tolerance
        throw IllegalStateException("no band")
    }

    fun toBytes(): ByteArray {
        val out = LeSink(1024)
        out.u32(Format.HEAD_MAGIC.toLong())
        out.u16(Format.PAYLOAD)
        out.u8(profile.code)
        out.u8(levels.size)
        for (z in levels) out.u8(z)
        out.f64(lat0); out.f64(lon0); out.f64(kx); out.f64(kz)
        out.utf8(region); out.utf8(engineVersion); out.utf8(styleVersion); out.utf8(source)
        out.varU(views.size.toLong())
        for (v in views) {
            out.utf8(v.name)
            Values.write(out, v.camera, null)
            out.u8(v.tolerance)
        }
        out.u8(bands.size)
        for (b in bands) out.u8(b.tolerance)
        for (b in bands) b.upTo?.let { out.f64(it) }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is RenderHead && profile == other.profile && region == other.region &&
        levels.contentEquals(other.levels) && lat0.toRawBits() == other.lat0.toRawBits() && lon0.toRawBits() == other.lon0.toRawBits() &&
        kx.toRawBits() == other.kx.toRawBits() && kz.toRawBits() == other.kz.toRawBits() && engineVersion == other.engineVersion &&
        styleVersion == other.styleVersion && source == other.source && views == other.views && bands == other.bands

    override fun hashCode(): Int = region.hashCode() * 31 + source.hashCode()

    companion object {
        fun fromBytes(bytes: ByteArray): RenderHead {
            val src = LeSource(bytes)
            if (src.u32() != Format.HEAD_MAGIC.toLong()) throw TileFormatException("not a render head")
            val payload = src.u16()
            if (payload != Format.PAYLOAD) throw TileFormatException("a render head of payload $payload; this reader knows ${Format.PAYLOAD}")
            val profile = Profile.of(src.u8())
            val levels = IntArray(src.u8()) { src.u8() }
            val lat0 = src.f64()
            val lon0 = src.f64()
            val kx = src.f64()
            val kz = src.f64()
            val region = src.utf8()
            val engine = src.utf8()
            val style = src.utf8()
            val source = src.utf8()
            val n = src.varCount()
            if (n > src.remaining) throw TileFormatException("$n views in ${src.remaining} bytes")
            fun ladder(what: String): Int = src.u8().also { if (it >= Keep.TOLERANCES.size) throw TileFormatException("$what at ladder index $it") }
            val views = List(n) {
                val name = src.utf8()
                @Suppress("UNCHECKED_CAST")
                val cam = Values.read(src, null) as? Map<String, Any?> ?: throw TileFormatException("view $name has no camera")
                View(name, cam, ladder("view $name"))
            }
            val nb = src.u8()
            if (nb == 0) throw TileFormatException("a render head without bands")
            val levelsOfBands = IntArray(nb) { ladder("band $it") }
            val bands = List(nb) { i -> Band(levelsOfBands[i], if (i < nb - 1) src.f64().also { if (it.isNaN()) throw TileFormatException("band $i ends at NaN") } else null) }
            if (src.remaining != 0) throw TileFormatException("a render head has ${src.remaining} bytes left over")
            return RenderHead(profile, region, levels, lat0, lon0, kx, kz, engine, style, source, views, bands)
        }
    }
}

/**
 * The `style` section: the renders' tables the core needs, by name, each a typed value written by the packer from the
 * port's own tables and the region's state after its load (palettes, quays, water levels, the keep ladder, the tag keys
 * each layer carries), so no colour is typed again in C++.
 *
 * Little-endian: u32 [Format.STYLE_MAGIC], u16 [Format.PAYLOAD], a varint table count, then per table its name (varint
 * length, UTF-8) and its value ([Values], strings inline).
 */
class StyleTables(val tables: Map<String, Any?>) {
    operator fun get(name: String): Any? = tables[name]

    fun toBytes(): ByteArray {
        val out = LeSink(4096)
        out.u32(Format.STYLE_MAGIC.toLong())
        out.u16(Format.PAYLOAD)
        out.varU(tables.size.toLong())
        for ((k, v) in tables) {
            out.utf8(k)
            Values.write(out, v, null)
        }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is StyleTables && tables == other.tables
    override fun hashCode(): Int = tables.hashCode()

    companion object {
        fun fromBytes(bytes: ByteArray): StyleTables {
            val src = LeSource(bytes)
            if (src.u32() != Format.STYLE_MAGIC.toLong()) throw TileFormatException("not a style section")
            val payload = src.u16()
            if (payload != Format.PAYLOAD) throw TileFormatException("a style section of payload $payload; this reader knows ${Format.PAYLOAD}")
            val n = src.varCount()
            if (n > src.remaining) throw TileFormatException("$n tables in ${src.remaining} bytes")
            val m = LinkedHashMap<String, Any?>()
            repeat(n) { m[src.utf8()] = Values.read(src, null) }
            if (src.remaining != 0) throw TileFormatException("a style section has ${src.remaining} bytes left over")
            return StyleTables(m)
        }
    }
}
