package app.atlas.ui.mine.offline

import app.atlas.geo.BBox
import app.atlas.route.SegmentProgress
import app.atlas.route.SegmentStore
import app.atlas.search.RegionImport
import app.atlas.services.RegionLayer
import app.atlas.tiles.Pack
import app.atlas.tiles.PackDownloader
import app.atlas.tiles.PackFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/** What the engine needs from the rest of the app, so a test can give it fakes and the app gives it the real ones. */
class RegionDeps(
    /** Atlas's published packs (both lists), or an empty list when none can be read. */
    val packs: suspend () -> List<Pack>,
    val packDownloader: PackDownloader,
    /** Fills the place store at the file with Overpass data for the box; reports 0..1. */
    val importPlaces: suspend (BBox, String, File, (Float) -> Unit) -> Unit,
    val segments: SegmentStore?,
    /** The remote sizes of BRouter tiles by name, for estimates (empty offline). */
    val segmentSizes: suspend () -> Map<String, Long> = { emptyMap() },
    /** Builds a region pack for an area of at most [RegionEngine.BUILD_MAX_KM2] on the phone from Overpass; null offline. */
    val mapBuilder: (suspend (id: String, name: String, bbox: BBox, target: File, report: (Float) -> Unit) -> Long)? = null,
    /** The voice languages to keep with a region. */
    val voices: () -> List<String> = { emptyList() },
    val clock: () -> Long = System::currentTimeMillis,
)

/** What a region would take: the bytes of each layer (null when unknown) and the pack behind it, if one is used. */
data class RegionEstimate(val bytes: Map<RegionLayer, Long?>, val pack: Pack?, val mapKind: MapKind) {
    val total: Long get() = bytes.values.sumOf { it ?: 0L }
}

/** Taking a region offline could not be done as asked (no map data for the area, a failed layer). Asking again will not help. */
class RegionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The region was deleted while it was being taken offline: the work stops without a failure to show. */
class RegionGone(id: String) : CancellationException("region $id was deleted")

/** What taking several boxes would download together: routing tiles two boxes share are counted once. */
data class AreaEstimate(val bytes: Map<RegionLayer, Long?>, val packed: Boolean, val kind: MapKind) {
    val total: Long get() = bytes.values.sumOf { it ?: 0L }
}

/**
 * Takes regions offline, layer by layer: the map (an Atlas region pack: a published one, or one built on the phone from
 * Overpass),
 * the places (the pack's place store, or an Overpass import), the routing tiles, and the voice languages. Each layer is
 * recorded in the region's metadata when it is complete, so a download that stops (no signal, a full disk, the process
 * killed) picks up at the layer it was on; the pack files themselves resume by byte range. [onChanged] runs after each
 * layer so the map, search and routing pick up the new files at once.
 */
class RegionEngine(
    private val files: RegionFiles,
    private val deps: RegionDeps,
    private val onChanged: () -> Unit,
) {
    private val progress = ConcurrentHashMap<String, RegionProgress>()
    private val errors = ConcurrentHashMap<String, String>()
    private val updates = ConcurrentHashMap<String, UpdateInfo>()
    private val state = MutableStateFlow(emptyList<RegionDetail>())

    /** Every region on the phone with its progress, error and any update, by name. */
    val details: StateFlow<List<RegionDetail>> = state.asStateFlow()

    /** Re-reads the files and publishes; serialised so an older reading never overwrites a newer one. */
    fun refresh() = synchronized(state) {
        state.value = files.list().map { m ->
            val sizes = sizesOf(m)
            val complete = m.wanted.isEmpty() || m.wanted.all { it in m.layers }
            RegionDetail(m, sizes, complete && m.layers.isNotEmpty(), progress[m.id], errors[m.id], updates[m.id])
        }
    }

    /**
     * Publishes a change of progress, error or update of [id] without reading the files again: a download reports many times
     * a second, and each report would otherwise parse every region's record and measure every file.
     */
    private fun republish(id: String) = synchronized(state) {
        val now = state.value
        if (now.none { it.id == id }) refresh()
        else state.value = now.map { d -> if (d.id == id) d.copy(progress = progress[id], error = errors[id], update = updates[id]) else d }
    }

    /** Marks [id] as waiting (queued for Wi-Fi or a free slot) without progress yet. */
    fun waiting(id: String) {
        progress[id] = RegionProgress(null, 0f, waiting = true)
        errors.remove(id)
        republish(id)
    }

    /** Says how far [id] has got: the layer at work and the whole's fraction. */
    fun report(id: String, layer: RegionLayer?, fraction: Float) {
        progress[id] = RegionProgress(layer, fraction.coerceIn(0f, 1f))
        republish(id)
    }

    /** Forgets progress that no download is behind any more (a cancelled job). */
    fun idle(id: String) {
        progress.remove(id)
        republish(id)
    }

    /** Remembers that a download of [id] failed, for the card. */
    fun failed(id: String, message: String) {
        errors[id] = message
        progress.remove(id)
        republish(id)
    }

    /** The region [id] to take offline as asked; the layers wanted are kept so [resume] can finish them. */
    fun begin(id: String, name: String, bbox: BBox, layers: Set<RegionLayer>): RegionMeta {
        val now = deps.clock()
        val meta = synchronized(files) {
            val old = files.get(id)
            (old ?: RegionMeta(id, name, bbox, now, now)).copy(name = name, bbox = bbox, wanted = layers, updatedAt = now).also { files.save(it) }
        }
        errors.remove(id)
        refresh()
        return meta
    }

    /** The pack to take [bbox] from: the smallest one that holds it, unless the area is a small part of that pack. */
    fun choosePack(bbox: BBox, packs: List<Pack>): Pack? {
        val box = area(bbox)
        return packs.filter { it.atlas != null || it.places != null }
            .filter { contains(it.bbox, bbox) }
            .minByOrNull { area(it.bbox) }
            ?.takeIf { box >= area(it.bbox) * PACK_SHARE }
    }

    /** The published packs the engine knows (what estimates and downloads choose from); empty when none can be read. */
    suspend fun packs(): List<Pack> = deps.packs()

    /** What taking [bbox] with [layers] would download, before it is asked for. */
    suspend fun estimate(bbox: BBox, layers: Set<RegionLayer>): RegionEstimate {
        val packs = runCatching { deps.packs() }.getOrDefault(emptyList())
        val pack = choosePack(bbox, packs)
        val out = LinkedHashMap<RegionLayer, Long?>()
        var kind = MapKind.NONE
        if (RegionLayer.MAP in layers) {
            val atlas = pack?.atlas
            when {
                atlas != null -> { kind = MapKind.ATLAS; out[RegionLayer.MAP] = atlas.bytes }
                deps.mapBuilder != null && buildable(bbox) -> { kind = MapKind.ATLAS; out[RegionLayer.MAP] = estimateBuilt(bbox) }
                else -> out[RegionLayer.MAP] = null
            }
        }
        if (RegionLayer.PLACES in layers) {
            out[RegionLayer.PLACES] = pack?.places?.bytes ?: (RegionImport.tiles(bbox).size * PLACES_PER_TILE)
        }
        if (RegionLayer.ROUTING in layers) {
            val sizes = runCatching { deps.segmentSizes() }.getOrDefault(emptyMap())
            val want = SegmentStore.tilesIn(bbox)
            out[RegionLayer.ROUTING] = want.filter { deps.segments?.has(it) != true }.sumOf { sizes[it] ?: SEGMENT_GUESS }
        }
        if (RegionLayer.VOICE in layers) out[RegionLayer.VOICE] = 0L
        return RegionEstimate(out, pack, kind)
    }

    /**
     * What taking all of [boxes] (the parts of one corridor, say) with [layers] would download: each box's map and places,
     * and the routing tiles of all of them together, so a tile that two boxes need is counted once.
     */
    suspend fun estimateAll(boxes: List<BBox>, layers: Set<RegionLayer>): AreaEstimate {
        val sums = LinkedHashMap<RegionLayer, Long?>()
        var packed = boxes.isNotEmpty()
        var kind = MapKind.NONE
        for (b in boxes) {
            val e = estimate(b, layers - RegionLayer.ROUTING)
            if (e.pack == null) packed = false
            kind = e.mapKind
            for ((l, v) in e.bytes) sums[l] = if (sums.containsKey(l) && sums[l] == null || v == null) null else (sums[l] ?: 0L) + v
        }
        if (RegionLayer.ROUTING in layers) {
            val sizes = runCatching { deps.segmentSizes() }.getOrDefault(emptyMap())
            val want = boxes.flatMap { SegmentStore.tilesIn(it) }.toSet()
            sums[RegionLayer.ROUTING] = want.filter { deps.segments?.has(it) != true }.sumOf { sizes[it] ?: SEGMENT_GUESS }
        }
        return AreaEstimate(sums, packed, kind)
    }

    /** Takes region [id] offline as [begin] set it up, from the layer it stopped at. Throws on failure after noting it. */
    suspend fun resume(id: String, update: Boolean = false) {
        val start = files.get(id) ?: throw RegionGone(id)
        val layers = start.wanted.ifEmpty { start.layers.keys }
        try {
            val packs = runCatching { deps.packs() }.getOrDefault(emptyList())
            val pack = start.packId?.let { pid -> packs.firstOrNull { it.id == pid } } ?: choosePack(start.bbox, packs)
            val estimate = estimate(start.bbox, layers)
            val weights = layers.associateWith { (estimate.bytes[it] ?: 1L).coerceAtLeast(1L).toDouble() }
            val total = weights.values.sum().coerceAtLeast(1.0)
            // After the process was killed nothing is known of what was stale: look again (this is cheap).
            val stale: Set<RegionLayer> = if (update) (updates[id] ?: runCatching { checkUpdates() }.getOrNull()?.get(id))?.layers.orEmpty() else emptySet()
            var done = 0.0
            progress[id] = RegionProgress(null, 0f)
            republish(id)
            for (layer in LAYER_ORDER.filter { it in layers }) {
                currentCoroutineContext().ensureActive()
                val before = weights.getValue(layer)
                val meta = files.get(id) ?: throw RegionGone(id)
                val have = meta.layers[layer]
                // An update takes the layers found stale; a layer the region lacks is always taken, so asking again after a
                // failure carries on where it stopped.
                val fresh = have != null && (!update || layer !in stale)
                if (!fresh) {
                    val report = { f: Float ->
                        progress[id] = RegionProgress(layer, ((done + before * f.coerceIn(0f, 1f)) / total).toFloat())
                        republish(id)
                    }
                    report(0f)
                    install(layer, start.copy(packId = pack?.id), pack, update, report)
                    onChanged()
                }
                done += before
            }
            files.edit(id) { it.copy(wanted = it.wanted.ifEmpty { layers.toSet() }, packId = pack?.id ?: it.packId, updatedAt = deps.clock()) } ?: throw RegionGone(id)
            updates.remove(id)
            errors.remove(id)
        } catch (e: CancellationException) {
            progress.remove(id)
            refresh()
            throw e
        } catch (e: Throwable) {
            errors[id] = e.message ?: e.toString()
            progress.remove(id)
            refresh()
            throw e
        }
        progress.remove(id)
        refresh()
    }

    private suspend fun install(layer: RegionLayer, meta: RegionMeta, pack: Pack?, update: Boolean, report: (Float) -> Unit) {
        val now = deps.clock()
        when (layer) {
            RegionLayer.MAP -> {
                val atlas = pack?.atlas
                val record = when {
                    atlas != null -> {
                        val target = files.atlas(meta.id)
                        deps.packDownloader.download(atlas, target) { report(it.fraction.toFloat()) }
                        LayerRecord(MapKind.ATLAS.id, target.length(), now, atlas.sha256, pack.osmTimestamp)
                    }
                    deps.mapBuilder != null && buildable(meta.bbox) -> {
                        val target = files.atlas(meta.id)
                        val part = File(target.path + ".part")
                        deps.mapBuilder.invoke(meta.id, meta.name, meta.bbox, part, report)
                        if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
                        LayerRecord(MapKind.ATLAS.id, target.length(), now)
                    }
                    else -> throw RegionException("There is no map pack for this area yet. Pick a country or city from the list.")
                }
                record(meta.id, layer, record)
            }
            RegionLayer.PLACES -> {
                val target = files.places(meta.id)
                val packed = pack?.places
                val record = if (packed != null) {
                    deps.packDownloader.download(packed, target) { report(it.fraction.toFloat()) }
                    LayerRecord("pack", target.length(), now, packed.sha256, pack.osmTimestamp)
                } else {
                    val part = File(target.path + ".part")
                    part.delete()
                    deps.importPlaces(meta.bbox, meta.name, part, report)
                    if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
                    LayerRecord("overpass", target.length(), now)
                }
                record(meta.id, layer, record)
            }
            RegionLayer.ROUTING -> {
                val store = deps.segments ?: throw RegionException("Routing data cannot be stored on this phone.")
                val tiles = SegmentStore.tilesIn(meta.bbox)
                var bytes = 0L
                for ((i, t) in tiles.withIndex()) {
                    val flow = if (update && store.has(t)) store.update(t) else if (store.has(t)) null else store.download(t)
                    flow?.collect { p: SegmentProgress -> report((i + p.fraction.toFloat()) / tiles.size) }
                    bytes += store.file(t).length()
                    report((i + 1f) / tiles.size)
                }
                files.edit(meta.id) { it.copy(segments = tiles) } ?: throw RegionGone(meta.id)
                record(meta.id, layer, LayerRecord("brouter", bytes, now))
            }
            RegionLayer.VOICE -> {
                files.edit(meta.id) { it.copy(voices = deps.voices()) } ?: throw RegionGone(meta.id)
                record(meta.id, layer, LayerRecord("tts", 0L, now))
            }
        }
    }

    private fun record(id: String, layer: RegionLayer, r: LayerRecord) {
        files.edit(id) { it.copy(layers = it.layers + (layer to r), updatedAt = deps.clock()) } ?: throw RegionGone(id)
        refresh()
    }

    /**
     * Which regions have something newer to take, and what that would download. From the pack list (a layer taken from a
     * pack is stale when the pack was republished: its size is known), and from age (a layer built on the phone, imported
     * from Overpass or fetched as routing tiles is stale after [STALE_DAYS]: its size is not known). Cheap; the pack list
     * is optional (without it only age counts).
     */
    suspend fun checkUpdates(): Map<String, UpdateInfo> {
        val packs = runCatching { deps.packs() }.getOrNull().orEmpty()
        val now = deps.clock()
        val found = LinkedHashMap<String, UpdateInfo>()
        for (m in files.list()) {
            var bytes: Long? = 0L
            val stale = LinkedHashSet<RegionLayer>()
            val pack = m.packId?.let { id -> packs.firstOrNull { it.id == id } }
            if (pack != null) {
                val mapSha = m.layers[RegionLayer.MAP]?.sha
                val atlas = pack.atlas
                if (atlas != null && mapSha != null && !mapSha.equals(atlas.sha256, true)) { stale += RegionLayer.MAP; bytes = bytes?.plus(atlas.bytes) }
                val placesSha = m.layers[RegionLayer.PLACES]?.sha
                val places = pack.places
                if (places != null && placesSha != null && !placesSha.equals(places.sha256, true)) { stale += RegionLayer.PLACES; bytes = bytes?.plus(places.bytes) }
            }
            for ((layer, rec) in m.layers) {
                if (layer in stale) continue
                val days = STALE_DAYS[layer] ?: continue
                if (rec.sha != null) continue // from a pack: the pack says when it is stale
                if (now - rec.at > days * DAY_MS) { stale += layer; bytes = null }
            }
            if (stale.isNotEmpty()) found[m.id] = UpdateInfo(bytes, stale)
        }
        updates.clear()
        updates.putAll(found)
        refresh()
        return found
    }

    /** Removes the region and the files only it uses; routing tiles stay when another region needs them. Returns bytes freed. */
    fun delete(id: String): Long {
        val m = files.get(id)
        var freed = files.deleteOwn(id)
        deps.segments?.let { store ->
            val others = files.list().filter { it.id != id }.flatMap { it.segments }.toSet()
            for (t in m?.segments.orEmpty()) if (t !in others) { freed += store.file(t).length(); store.delete(t) }
        }
        progress.remove(id)
        errors.remove(id)
        updates.remove(id)
        refresh()
        onChanged()
        return freed
    }

    private fun sizesOf(m: RegionMeta): Map<RegionLayer, Long> {
        val out = LinkedHashMap<RegionLayer, Long>()
        for ((layer, rec) in m.layers) {
            out[layer] = when (layer) {
                RegionLayer.MAP -> if (rec.kind == MapKind.ATLAS.id) files.atlas(m.id).takeIf { it.isFile }?.length() ?: rec.bytes else rec.bytes
                RegionLayer.PLACES -> files.places(m.id).takeIf { it.isFile }?.length() ?: rec.bytes
                RegionLayer.ROUTING -> deps.segments?.let { s -> m.segments.sumOf { s.file(it).length() } } ?: rec.bytes
                RegionLayer.VOICE -> rec.bytes
            }
        }
        return out
    }

    private fun estimateBuilt(bbox: BBox): Long = (area(bbox) * BUILT_BYTES_PER_KM2).toLong().coerceAtLeast(200_000L)

    /** True when the phone can build [bbox]'s map pack from Overpass in reasonable time (a city, a few towns). */
    private fun buildable(bbox: BBox): Boolean = area(bbox) <= BUILD_MAX_KM2

    companion object {
        val LAYER_ORDER = listOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.ROUTING, RegionLayer.VOICE)

        /** A pack is used when the area asked for is at least this share of the pack (else only the area is fetched). */
        const val PACK_SHARE = 0.15

        /** The largest area whose map pack the phone builds from Overpass (km²): a city; beyond, a published pack. */
        const val BUILD_MAX_KM2 = 1_000.0

        /** Days after which a layer not taken from a pack is offered for a refresh. */
        val STALE_DAYS = mapOf(RegionLayer.MAP to 30, RegionLayer.PLACES to 30, RegionLayer.ROUTING to 45)
        private const val DAY_MS = 24L * 3600 * 1000

        private const val PLACES_PER_TILE = 150_000L
        private const val SEGMENT_GUESS = 75_000_000L
        private const val BUILT_BYTES_PER_KM2 = 20_000.0

        /**
         * The id a region gets from its name, file-safe: lower-case ASCII letters, digits and hyphens, accents dropped
         * ("Zürich" is "zurich"). When that loses part of the name (another script, or a name cut to length), a short
         * hash of the name is added, so "東京" and "大阪" do not share one id.
         */
        fun idFor(name: String): String {
            val ascii = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace(MARKS, "")
            val slug = ascii.lowercase().replace(NON_ID, "-").trim('-')
            val lossy = slug.length > MAX_ID || ascii.any { it.isLetterOrDigit() && !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }
            if (!lossy && slug.isNotEmpty()) return slug
            val hash = String.format(java.util.Locale.ROOT, "%08x", name.hashCode())
            return (slug.take(MAX_ID - 9).trim('-').ifEmpty { "region" }) + "-" + hash
        }

        private const val MAX_ID = 48
        private val MARKS = Regex("\\p{M}+")
        private val NON_ID = Regex("[^a-z0-9]+")

        /** The area of [b] in square kilometres. */
        fun area(b: BBox): Double {
            val midLat = Math.toRadians((b.south + b.north) / 2)
            return abs(b.north - b.south) * 111.32 * abs(b.east - b.west) * 111.32 * cos(midLat)
        }

        fun contains(outer: BBox, inner: BBox): Boolean =
            outer.west <= inner.west + 1e-9 && outer.east >= inner.east - 1e-9 && outer.south <= inner.south + 1e-9 && outer.north >= inner.north - 1e-9

        /** Whole bytes as the screens say them: "640 MB", "1.9 GB". */
        fun size(bytes: Long): String = when {
            bytes < 1_000L -> "$bytes B"
            bytes < 1_000_000L -> "${ceil(bytes / 1_000.0).toLong()} kB"
            bytes < 1_000_000_000L -> "${Math.round(bytes / 1e6)} MB"
            else -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
        }
    }
}

/** The pack's Atlas region file (kind "atlas"), if it has one. */
val Pack.atlas: PackFile? get() = files.firstOrNull { it.kind == PackFile.ATLAS }
