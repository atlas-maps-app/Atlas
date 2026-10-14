package app.atlas.packer.pbf

import app.atlas.geo.BBox
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.Inflater

/**
 * Streams an OSM PBF file into an [OsmHandler]: blobs are inflated on [threads] worker threads, a few ahead of the
 * reader, and decoded and handed over on the calling thread in file order. Memory stays at a few blocks whatever the
 * file's size.
 */
class PbfReader(val file: File, private val threads: Int = defaultThreads()) {
    /** The file's header block. */
    val header: PbfHeader by lazy {
        PbfFile(file).use { f -> readHeader(f, Inflater()) }
    }

    /** Calls [handler] for every element of the kinds in [kinds] ([NODES], [WAYS], [RELATIONS], or'ed). */
    fun read(handler: OsmHandler, kinds: Int = ALL) {
        val decoder = BlockDecoder(handler, kinds)
        PbfFile(file).use { f ->
            val local = Inflater()
            try {
                readHeader(f, local)
                if (threads <= 1) {
                    while (true) {
                        val blob = f.next() ?: break
                        if (blob.type == DATA) decoder.decode(BlobCodec.decode(blob.data, local))
                    }
                } else {
                    parallel(f, decoder)
                }
            } finally {
                local.end()
            }
        }
    }

    private fun parallel(f: PbfFile, decoder: BlockDecoder) {
        val inflaters = java.util.Collections.synchronizedList(ArrayList<Inflater>())
        val perThread = ThreadLocal.withInitial { Inflater().also(inflaters::add) }
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "pbf-inflate").apply { isDaemon = true } }
        val ahead = ArrayDeque<Future<ByteArray>>()
        try {
            while (true) {
                val blob = f.next() ?: break
                if (blob.type != DATA) continue
                ahead.addLast(pool.submit(Callable { BlobCodec.decode(blob.data, perThread.get()) }))
                if (ahead.size >= threads * 3) decoder.decode(take(ahead.removeFirst()))
            }
            while (ahead.isNotEmpty()) decoder.decode(take(ahead.removeFirst()))
        } finally {
            for (fut in ahead) fut.cancel(true)
            pool.shutdownNow()
            synchronized(inflaters) { for (i in inflaters) i.end() }
        }
    }

    private fun take(f: Future<ByteArray>): ByteArray = try {
        f.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private fun readHeader(f: PbfFile, inflater: Inflater): PbfHeader {
        val first = f.next() ?: throw PbfFormatException("${file.name} is empty")
        if (first.type != HEADER) throw PbfFormatException("${file.name} does not start with an OSMHeader blob (found ${first.type})")
        return PbfHeader.parse(BlobCodec.decode(first.data, inflater)).also { it.checkSupported() }
    }

    /** Counts what the file holds, in one pass. */
    fun count(): PbfCounts {
        val c = PbfCounts()
        read(c)
        return c
    }

    companion object {
        const val NODES = 1
        const val WAYS = 2
        const val RELATIONS = 4
        const val ALL = NODES or WAYS or RELATIONS

        private const val HEADER = "OSMHeader"
        private const val DATA = "OSMData"

        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
    }
}

/** Element counts of a file, as `osmium fileinfo --extended` reports them, with the id ranges and the order check. */
class PbfCounts : OsmHandler {
    var nodes = 0L; private set
    var ways = 0L; private set
    var relations = 0L; private set
    var taggedNodes = 0L; private set
    var wayRefs = 0L; private set
    var members = 0L; private set
    var tags = 0L; private set
    var maxNodeId = Long.MIN_VALUE; private set
    var maxWayId = Long.MIN_VALUE; private set
    var maxRelationId = Long.MIN_VALUE; private set

    /** The latest edit in the file, epoch milliseconds (0 without metadata). */
    var lastEdit = 0L; private set

    /** False once an element came out of type-then-id order. */
    var sorted = true; private set
    private var kind = 0
    private var last = Long.MIN_VALUE
    private var south = Double.POSITIVE_INFINITY
    private var west = Double.POSITIVE_INFINITY
    private var north = Double.NEGATIVE_INFINITY
    private var east = Double.NEGATIVE_INFINITY

    /** The box around every node, or null without nodes. */
    val nodeBox: BBox? get() = if (nodes == 0L) null else BBox(west, south, east, north)

    override fun node(node: PbfNode) {
        order(1, node.id)
        nodes++
        if (node.tags.isNotEmpty()) taggedNodes++
        tags += node.tags.size
        if (node.id > maxNodeId) maxNodeId = node.id
        if (node.timestampMillis > lastEdit) lastEdit = node.timestampMillis
        val lat = node.lat
        val lon = node.lon
        if (lat < south) south = lat
        if (lat > north) north = lat
        if (lon < west) west = lon
        if (lon > east) east = lon
    }

    override fun way(way: PbfWay) {
        order(2, way.id)
        ways++
        wayRefs += way.refCount
        tags += way.tags.size
        if (way.id > maxWayId) maxWayId = way.id
        if (way.timestampMillis > lastEdit) lastEdit = way.timestampMillis
    }

    override fun relation(relation: PbfRelation) {
        order(3, relation.id)
        relations++
        members += relation.memberCount
        tags += relation.tags.size
        if (relation.id > maxRelationId) maxRelationId = relation.id
        if (relation.timestampMillis > lastEdit) lastEdit = relation.timestampMillis
    }

    private fun order(k: Int, id: Long) {
        if (k < kind || (k == kind && id <= last)) sorted = false
        if (k != kind) { kind = k; last = id } else last = id
    }
}
