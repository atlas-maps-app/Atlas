package app.atlas.mapview

import app.atlas.engine.Extras
import app.atlas.engine.Look
import app.atlas.engine.SceneBuilder
import app.atlas.geo.TileId
import app.atlas.tiles.TileHit
import app.atlas.tiles.TileSource
import app.atlas.tiles.TileStack
import app.atlas.tiles.VectorTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Turns the tiles a camera wants into scenes, off the main thread: fetch through the [TileStack] (I/O), decode the MVT
 * once per source tile (shared by its overzoomed children), build the [app.atlas.engine.TileScene] (CPU, interruptible),
 * and keep it in the [cache]. Tiles that leave the cover are cancelled. [onChange] is called (on a worker thread) when
 * a load ends, with a scene or without one, to ask for a frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenePipeline(
    val cache: SceneCache,
    private val onChange: () -> Unit,
    private val builders: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(defaultBuilders()),
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(8),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + builders)
    private val lock = Any()
    private val jobs = HashMap<SceneCache.Key, Job>()
    /** Keys that failed or found no data, and when to try them again. */
    private val retryAt = HashMap<SceneCache.Key, Long>()
    /** Scenes built from a provisional tile (a source failed on the way): rebuilt once the time has come. */
    private val provisional = HashMap<SceneCache.Key, Long>()
    private val decoded = object : LinkedHashMap<TileId, VectorTile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileId, VectorTile>?) = size > DECODED_TILES
    }
    private val decoding = HashMap<TileId, Deferred<VectorTile>>()

    @Volatile
    private var stack: TileStack? = null
    private var raw: TileSource? = null
    private var generation = 0

    /** Real trees and tree rows for a tile, from the app's place store; null for none. */
    @Volatile
    var extras: (suspend (TileId) -> Extras?)? = null

    /** Loads running or queued now. */
    val pending: Int get() = synchronized(lock) { jobs.size }

    /** Scenes built since the pipeline started. */
    @Volatile
    var built = 0
        private set

    /** The source of tiles; a new one drops everything built from the old. Returns whether it changed. */
    fun setSource(source: TileSource?): Boolean {
        synchronized(lock) {
            if (source === raw) return false
            raw = source
            stack = when (source) {
                null -> null
                is TileStack -> source
                else -> TileStack(listOf(source))
            }
            generation++
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            retryAt.clear()
            provisional.clear()
            decoded.clear()
            // A decode still running reads the old source's data: it must not land in the new source's cache.
            decoding.values.forEach { it.cancel() }
            decoding.clear()
        }
        cache.clear()
        return true
    }

    /**
     * The tiles [cover] wants, nearest first, for [look] (keyed [lookKey]): starts what is missing and cancels what no
     * longer is. Returns the number of loads now running or queued.
     */
    fun want(cover: List<TileId>, look: Look, lookKey: String): Int {
        val now = clock()
        synchronized(lock) {
            val s = stack ?: return 0
            val wanted = HashSet<SceneCache.Key>(cover.size * 2)
            for (id in cover) wanted += SceneCache.Key(id, lookKey)
            val it = jobs.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key !in wanted) {
                    e.value.cancel()
                    it.remove()
                }
            }
            for (id in cover) {
                val key = SceneCache.Key(id, lookKey)
                if (jobs.containsKey(key)) continue
                val has = cache.contains(key)
                if (has) {
                    val again = provisional[key] ?: continue
                    if (now < again) continue
                    provisional.remove(key)
                } else {
                    val again = retryAt[key]
                    if (again != null && now < again) continue
                }
                val gen = generation
                jobs[key] = scope.launch { load(s, gen, id, look, key) }
            }
            return jobs.size
        }
    }

    private suspend fun load(s: TileStack, gen: Int, id: TileId, look: Look, key: SceneCache.Key) {
        val me = currentCoroutineContext()[Job]
        try {
            val hit = withContext(io) { s.bestTile(id) }
            if (hit == null) {
                synchronized(lock) {
                    if (gen == generation) retryAt[key] = clock() + MISSING_RETRY_MS
                    if (jobs[key] === me) jobs.remove(key)
                }
                onChange()
                return
            }
            val tile = decode(hit, gen)
            // The app's place store may read a database: on the I/O threads, not a builder's.
            val ex = extras?.let { e -> withContext(io) { e(id) } }
            val scene = runInterruptible { SceneBuilder.build(tile, id, look, ex, hit.source) }
            PoiStyles.warm(scene.pois)
            synchronized(lock) {
                if (gen != generation) return
                retryAt.remove(key)
                if (!hit.isFinal) provisional[key] = clock() + PROVISIONAL_RETRY_MS
                cache.put(key, scene)
                built++
                // Off the list before the frame is asked for, so that frame knows nothing is loading any more.
                if (jobs[key] === me) jobs.remove(key)
            }
            onChange()
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            // Cancelled inside the build.
        } catch (e: Exception) {
            synchronized(lock) {
                if (gen == generation) retryAt[key] = clock() + FAILED_RETRY_MS
                if (jobs[key] === me) jobs.remove(key)
            }
            onChange()
        } finally {
            synchronized(lock) { if (jobs[key] === me) jobs.remove(key) }
        }
    }

    /**
     * The decoded tile of [hit]'s source, decoding it once however many scenes are built from it. [gen] is the source
     * generation the hit came from: a decode finishing after the source changed is dropped, not kept for the new one.
     */
    private suspend fun decode(hit: TileHit, gen: Int): VectorTile {
        val d = synchronized(lock) {
            if (gen != generation) throw CancellationException("the tile source changed")
            decoded[hit.source]?.let { return it }
            decoding.getOrPut(hit.source) {
                scope.async { hit.decode() }.also { job ->
                    job.invokeOnCompletion { cause ->
                        synchronized(lock) {
                            if (gen == generation) {
                                if (cause == null) decoded[hit.source] = job.getCompleted()
                                if (decoding[hit.source] === job) decoding.remove(hit.source)
                            }
                        }
                    }
                }
            }
        }
        return d.await()
    }

    /** Stops every load; the pipeline cannot be used again. */
    fun close() {
        scope.cancel()
        synchronized(lock) { jobs.clear() }
    }

    companion object {
        const val MISSING_RETRY_MS = 60_000L
        const val FAILED_RETRY_MS = 15_000L
        const val PROVISIONAL_RETRY_MS = 10_000L
        private const val DECODED_TILES = 12

        /** Parallel scene builds: the cores less one for the screen and one for the frames, between 1 and 4. */
        fun defaultBuilders(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
    }
}
