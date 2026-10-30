package app.atlas.ui.go

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.route.SegmentProgress
import app.atlas.route.SegmentStore
import app.atlas.services.RegionLayer
import app.atlas.services.RegionManager
import app.atlas.services.RegionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** One routing segment a trip needs: its name ("E0_N50"), its size when known, and the download's progress. */
data class SegmentNeed(val tile: String, val bytes: Long?, val progress: Float? = null) {
    /** "E0_N50 · 79 MB". */
    val label: String get() = if (bytes == null) tile else "$tile · ${megabytes(bytes)}"

    companion object {
        fun megabytes(bytes: Long): String = if (bytes < 10_000_000) String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6) else "${(bytes + 500_000) / 1_000_000} MB"
    }
}

/**
 * How far a segment's download has come (0..1), and whether it waits: queued by the offline regions' manager with
 * nothing fetched yet, until the phone is on Wi-Fi (the Wi-Fi-only setting, on by default) or a worker is free.
 */
data class SegmentFetch(val fraction: Float, val waiting: Boolean = false)

/**
 * A segment download's line: "Downloading… 42 %", or "Waiting for Wi-Fi" while it waits (the user, 30 October: "What
 * exactly is happening across the app when youre online vs offline?"), where until then it said 0 % with no reason
 * for as long as the phone was on mobile data.
 */
@Composable
fun segmentLine(fetch: SegmentFetch): String =
    if (fetch.waiting) stringResource(R.string.go_waiting_wifi) else stringResource(R.string.go_downloading, (fetch.fraction * 100).roundToInt())

/**
 * The routing segments a trip lacks, and getting them: sizes from brouter.de when online, else from the index kept in
 * the app (`res/raw/go_segment_sizes.txt`, BRouter's segments4 listing of 13 October 2026; sizes change little week to
 * week); the download through the offline regions' manager when it takes routing, else straight into the segment
 * folder with BRouter's own resumable download.
 */
object SegmentOffer {
    private val known = ConcurrentHashMap<String, Long>()

    /**
     * The size of [tile] in bytes: asked online when [online] (once a day: [SegmentStore.remoteSize]), else the kept
     * index; null when neither knows.
     */
    suspend fun size(context: Context, store: SegmentStore, tile: String, online: Boolean): Long? {
        val name = tile.removeSuffix(".rd5")
        if (online) {
            val remote = try {
                store.remoteSize(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (remote != null) return remote
        }
        return keptSizes(context)[name]
    }

    /** The kept index of segment sizes, read once. */
    fun keptSizes(context: Context): Map<String, Long> {
        if (known.isNotEmpty()) return known
        runCatching {
            context.resources.openRawResource(R.raw.go_segment_sizes).bufferedReader().useLines { lines ->
                for (l in lines) {
                    val sp = l.indexOf(' ')
                    if (sp > 0) l.substring(sp + 1).trim().toLongOrNull()?.let { known[l.substring(0, sp)] = it }
                }
            }
        }
        return known
    }

    /**
     * Downloads [tile], reporting how far it has come and whether it waits for Wi-Fi ([SegmentFetch]). Through
     * [regions] when it takes the routing layer (it then also tells the app the files changed, and holds the download
     * for Wi-Fi as the user set), else with [store] directly.
     */
    fun download(regions: RegionManager, store: SegmentStore, tile: String): Flow<SegmentFetch> = flow {
        val name = tile.removeSuffix(".rd5")
        val box = SegmentStore.tileBox(name)
        val id = try {
            regions.download(RegionRequest("Routing $name", box, setOf(RegionLayer.ROUTING)))
        } catch (e: IllegalArgumentException) {
            null
        }
        if (id != null) {
            regions.regions.map { list -> list.firstOrNull { it.id == id } }
                .takeWhile { r -> r == null || !r.complete && r.error == null }
                .collect { r -> emit(SegmentFetch(r?.progress ?: 0f, waiting = r?.waiting == true)) }
            val done = regions.regions.first().firstOrNull { it.id == id }
            done?.error?.let { throw java.io.IOException(it) }
            emit(SegmentFetch(1f))
            return@flow
        }
        store.download(name).collect { p: SegmentProgress ->
            emit(SegmentFetch(if (p.stage == SegmentProgress.Stage.DONE || p.stage == SegmentProgress.Stage.UP_TO_DATE) 1f else p.fraction.toFloat()))
        }
        emit(SegmentFetch(1f))
    }
}
