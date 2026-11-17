package app.atlas.services

import app.atlas.geo.LatLon
import app.atlas.render.live.HttpGet
import app.atlas.render.live.PackLibrary
import app.atlas.testing.BundledPack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The published packs the map's places ask for (the windows' local lane, ENGINE.md step 3), over a library whose
 * network is the test's and a thread the test runs by hand: asking for a place's pack returns at once with nothing
 * asked of the network; the list and the pack come on the job; nothing is asked while the network is not one a pack
 * may come over, and a download under way stops where it turns into one; what is on its way is said, with how far;
 * a pack that did not come waits a minute; a place no published pack holds asks for none.
 */
class PublishedPacksTest {
    private val dir: File = Files.createTempDirectory("atlas-published").toFile()
    private val bytes = BundledPack.file.readBytes()
    private val requests = ArrayList<String>()
    private var down = false
    private var allowed = true
    private var clock = 1_000_000L
    private val jobs = ArrayDeque<Runnable>()
    private var changes = 0
    private val said = ArrayList<String>()

    /** Called at each read of the pack's bytes (a download under way). */
    private var reading: () -> Unit = {}

    private val westerkerk = LatLon(52.3745, 4.8840)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val manifest = """{"format":1,"packs":[{"id":"amsterdam","name":"Amsterdam","bbox":[4.878,52.371,4.891,52.378],
        |"release":"packs-renders","files":[{"kind":"atlas","name":"amsterdam.atlas","bytes":${bytes.size},"sha256":"${sha(bytes)}",
        |"url":"https://packs.test/amsterdam.atlas"}]}]}""".trimMargin()

    /** The pack's bytes, a few kilobytes a read, saying so at each ([reading]). */
    private inner class Slow : InputStream() {
        private val all = ByteArrayInputStream(bytes)
        override fun read(): Int = all.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            reading()
            return all.read(b, off, minOf(len, 8_192))
        }
    }

    private val http = HttpGet { url ->
        requests += url
        if (down) throw IOException("$url: no answer")
        val body = manifest.toByteArray()
        when {
            url.endsWith("packs.json") -> Pair<InputStream, Long>(ByteArrayInputStream(body), body.size.toLong())
            url.endsWith("amsterdam.atlas") -> Pair<InputStream, Long>(Slow(), bytes.size.toLong())
            else -> throw IOException("$url answered 404")
        }
    }

    private val library = PackLibrary(dir, listOf("https://packs.test/packs.json"), http, renders = false)
    private val published = PublishedPacks(library, allowed = { allowed }, run = { jobs += it }, now = { clock }, changed = { changes++ }, said = { said += it })

    /** Runs the jobs asked for, each to its end. */
    private fun work() {
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    @Test
    fun aPlaceNoPackOnThePhoneHoldsAsksForItsPublishedPackOnAJobOfItsOwn() {
        // the windows' step asks: it gets no answer to wait for, and nothing went to the network on its thread
        published.want(westerkerk)
        assertTrue(requests.isEmpty())
        assertNull(library.local("amsterdam"))
        assertEquals(1, jobs.size)
        // a second window of the place asks while the first's job waits: one job still
        published.want(westerkerk)
        assertEquals(1, jobs.size)
        // the job: the list, then the pack; what is on its way is said while it comes, with how far
        val seen = ArrayList<PublishedPacks.Coming>()
        reading = { published.coming?.let { seen += it } }
        work()
        assertEquals(listOf("https://packs.test/packs.json", "https://packs.test/amsterdam.atlas"), requests)
        assertNotNull(library.local("amsterdam"))
        assertEquals("Amsterdam", seen.first().name)
        assertEquals(0L, seen.first().done)
        assertEquals(bytes.size.toLong(), seen.last().bytes)
        assertTrue(seen[seen.size / 2].done in 1 until bytes.size)
        assertEquals(bytes.size.toLong(), seen.last().done)
        assertTrue(seen.zipWithNext().all { (a, b) -> a.done <= b.done })
        assertTrue(seen.first().bbox.contains(westerkerk))
        assertNull(published.coming)
        assertTrue(changes >= 2)
        assertTrue(said.toString(), said.any { it.startsWith("the pack amsterdam fetched") })
        // on the phone: a place it holds asks for nothing more
        published.want(westerkerk)
        work()
        assertEquals(2, requests.size)
    }

    @Test
    fun nothingIsAskedOfTheNetworkWhileAPackMayNotComeOverItAndItComesOnceItMay() {
        // mobile data with the Maps board's "Wi-Fi only" on: the place is noted, nothing is fetched, not even the list
        allowed = false
        published.want(westerkerk)
        published.wake()
        assertTrue(jobs.isEmpty())
        assertTrue(requests.isEmpty())
        // Wi-Fi, or the user let downloads use mobile data: taken up without the place asking again
        allowed = true
        published.wake()
        work()
        assertNotNull(library.local("amsterdam"))
        assertEquals(2, requests.size)
    }

    @Test
    fun aDownloadUnderWayStopsWhereTheNetworkTurnsMeteredAndIsTakenUpAgainLater() {
        published.want(westerkerk)
        var reads = 0
        reading = { if (++reads == 3) allowed = false }
        work()
        // stopped: nothing of it placed or left half written, and it is not asked for again on that network
        assertNull(library.local("amsterdam"))
        assertNull(published.coming)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") || it.name.endsWith(PackLibrary.EXT) })
        assertTrue(said.toString(), said.any { it.startsWith("the pack amsterdam did not come") })
        val asked = requests.size
        published.want(westerkerk)
        published.wake()
        work()
        assertEquals(asked, requests.size)
        // back on Wi-Fi, but within a minute of its failure: it waits; then it comes
        allowed = true
        reading = {}
        published.wake()
        work()
        assertEquals(asked, requests.size)
        clock += PublishedPacks.RETRY_MS
        published.wake()
        work()
        assertNotNull(library.local("amsterdam"))
        assertEquals(asked + 1, requests.size)
    }

    @Test
    fun aListOrAPackThatDoesNotComeIsAskedAgainAMinuteOnAtTheEarliest() {
        down = true
        published.want(westerkerk)
        work()
        assertEquals(listOf("https://packs.test/packs.json"), requests)
        assertTrue(said.toString(), said.any { it.startsWith("the list of published packs did not come") })
        // asked again by its windows at once: the list waits its minute
        published.want(westerkerk)
        work()
        assertEquals(1, requests.size)
        clock += PublishedPacks.RETRY_MS
        down = false
        published.want(westerkerk)
        work()
        assertEquals(listOf("https://packs.test/packs.json", "https://packs.test/packs.json", "https://packs.test/amsterdam.atlas"), requests)
        assertNotNull(library.local("amsterdam"))
    }

    @Test
    fun aPlaceNoPublishedPackHoldsAsksForNoPack() {
        // Lille: the list is read for it once, and no pack is fetched
        published.want(LatLon(50.6292, 3.0573))
        work()
        assertEquals(listOf("https://packs.test/packs.json"), requests)
        published.want(LatLon(50.6292, 3.0573))
        work()
        assertEquals(1, requests.size)
        assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(PackLibrary.EXT) })
        assertNull(published.coming)
    }
}
