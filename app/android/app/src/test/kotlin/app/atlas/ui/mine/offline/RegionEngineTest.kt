package app.atlas.ui.mine.offline

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.services.RegionLayer
import app.atlas.services.RegionRequest
import app.atlas.tiles.Pack
import app.atlas.tiles.PackDownloader
import app.atlas.tiles.PackFile
import app.atlas.tiles.PackPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** The region engine over a real download from a local server: layers, resume, updates, estimates and deletion. */
class RegionEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var files: RegionFiles
    private val served = HashMap<String, ByteArray>()
    private val hits = AtomicInteger()
    private var changed = 0
    private var clock = 1_760_000_000_000L

    private val pack = BBox(4.0, 52.0, 5.0, 53.0)
    private val inside = BBox(4.3, 52.3, 4.8, 52.8) // a quarter of the pack
    private val town = BBox(4.85, 52.33, 4.95, 52.40) // some 50 km²: small enough for the phone to build

    @Before
    fun start() {
        files = RegionFiles(tmp.newFolder("regions"), tmp.newFolder("places"), tmp.newFolder("segments"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                val body = served[request.url.encodedPath] ?: return MockResponse.Builder().code(404).build()
                val range = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                return if (range != null) MockResponse.Builder().code(206).addHeader("Content-Range", "bytes $range-${body.size - 1}/${body.size}").body(Buffer().write(body, range, body.size - range)).build()
                else MockResponse.Builder().body(Buffer().write(body)).build()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun data(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) and 0xff).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(kind: String, name: String, bytes: ByteArray): PackFile {
        served["/$name"] = bytes
        return PackFile(kind, name, bytes.size.toLong(), sha(bytes), listOf(PackPart(server.url("/$name").toString(), bytes.size.toLong(), sha(bytes))))
    }

    private fun thePack(seed: Int = 1, mapKb: Int = 300, placesKb: Int = 80) =
        Pack("test", "Testland", "NL", pack, null, "packs-test", listOf(file("atlas", "test.atlas", data(mapKb * 1000, seed)), file(PackFile.PLACES, "test.places", data(placesKb * 1000, seed + 1))))

    private fun deps(packs: () -> List<Pack>, builder: (suspend (String, String, BBox, File, (Float) -> Unit) -> Long)? = null, import: suspend (BBox, String, File, (Float) -> Unit) -> Unit = { _, _, _, _ -> error("no import") }) = RegionDeps(
        packs = { packs() }, packDownloader = PackDownloader(), importPlaces = import, segments = null, mapBuilder = builder, voices = { listOf("nl", "en") }, clock = { clock++ },
    )

    private fun engine(d: RegionDeps) = RegionEngine(files, d) { changed++ }

    @Test
    fun aRegionInsideAPackTakesItsMapAndPlacesFromThePackAndKeepsEachLayerRecorded() {
        val p = thePack()
        val e = engine(deps({ listOf(p) }))
        val id = RegionEngine.idFor("Around Amsterdam")
        e.begin(id, "Around Amsterdam", inside, setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.VOICE))
        runBlocking { e.resume(id) }
        assertEquals(300_000L, files.atlas(id).length())
        assertEquals(80_000L, files.places(id).length())
        assertTrue(data(300_000, 1).contentEquals(files.atlas(id).readBytes()))
        val m = files.get(id)!!
        assertEquals(setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.VOICE), m.layers.keys)
        assertEquals(MapKind.ATLAS.id, m.layers.getValue(RegionLayer.MAP).kind)
        assertEquals(p.atlas!!.sha256, m.layers.getValue(RegionLayer.MAP).sha)
        assertEquals("test", m.packId)
        assertEquals(listOf("nl", "en"), m.voices)
        val d = e.details.value.single()
        assertTrue(d.complete)
        assertNull(d.error)
        assertNull(d.progress)
        assertEquals(380_000L, d.bytes)
        assertTrue("the map, search and routing were told after each layer: $changed", changed >= 3)
    }

    @Test
    fun anAreaThatIsAFewPercentOfAPackIsNotTakenFromIt() {
        val tiny = BBox(4.5, 52.5, 4.55, 52.55)
        val p = thePack()
        val e = engine(deps({ listOf(p) }))
        assertNull(e.choosePack(tiny, listOf(p)))
        assertNotNull(e.choosePack(inside, listOf(p)))
        assertNull(e.choosePack(BBox(3.0, 52.3, 4.5, 52.8), listOf(p))) // sticks out of the pack
    }

    @Test
    fun theSmallestPackThatHoldsTheAreaIsTheOneUsed() {
        val big = thePack().copy(id = "big", bbox = BBox(3.0, 51.0, 6.0, 54.0))
        val small = thePack().copy(id = "small", bbox = BBox(4.2, 52.2, 4.9, 52.9))
        val e = engine(deps({ emptyList() }))
        assertEquals("small", e.choosePack(inside, listOf(big, small))!!.id)
    }

    @Test
    fun aFailedLayerKeepsTheOnesBeforeItAndTheNextRunPicksUpThere() {
        val builds = AtomicInteger()
        val imports = AtomicInteger()
        val d = deps({ emptyList() }, builder = { _, _, _, target, report ->
            builds.incrementAndGet(); target.writeBytes(data(40_000, 5)); report(1f); 40_000L
        }, import = { _, _, target, _ ->
            if (imports.incrementAndGet() == 1) throw IOException("no signal") else target.writeBytes(data(9_000, 6))
        })
        val e = engine(d)
        val id = RegionEngine.idFor("Offline test")
        e.begin(id, "Offline test", town, setOf(RegionLayer.MAP, RegionLayer.PLACES))
        val first = runCatching { runBlocking { e.resume(id) } }
        assertTrue(first.exceptionOrNull() is IOException)
        assertEquals(setOf(RegionLayer.MAP), files.get(id)!!.layers.keys)
        assertEquals("no signal", e.details.value.single().error)
        assertFalse(e.details.value.single().complete)
        runBlocking { e.resume(id) }
        assertEquals("the map was built once", 1, builds.get())
        assertEquals(2, imports.get())
        assertEquals(setOf(RegionLayer.MAP, RegionLayer.PLACES), files.get(id)!!.layers.keys)
        assertTrue(e.details.value.single().complete)
        assertNull(e.details.value.single().error)
        assertEquals("overpass", files.get(id)!!.layers.getValue(RegionLayer.PLACES).kind)
    }

    @Test
    fun withNoPackAndNoBuilderTheMapIsRefusedPlainly() {
        val e = engine(deps({ emptyList() }))
        val id = RegionEngine.idFor("Nowhere")
        e.begin(id, "Nowhere", inside, setOf(RegionLayer.MAP))
        val err = runCatching { runBlocking { e.resume(id) } }.exceptionOrNull()
        assertTrue(err is RegionException)
        assertTrue(e.details.value.single().error!!.contains("no map pack", ignoreCase = true))
    }

    @Test
    fun aNewerPublishedPackShowsAsAnUpdateWithItsSizeAndTakingItClearsIt() {
        var current = thePack(seed = 1)
        val e = engine(deps({ listOf(current) }))
        val id = RegionEngine.idFor("Around Amsterdam")
        e.begin(id, "Around Amsterdam", inside, setOf(RegionLayer.MAP))
        runBlocking { e.resume(id) }
        assertTrue(runBlocking { e.checkUpdates() }.isEmpty())
        current = thePack(seed = 9, mapKb = 320)
        val found = runBlocking { e.checkUpdates() }
        assertEquals(setOf(id), found.keys)
        assertEquals(320_000L, found.getValue(id).bytes)
        assertEquals(setOf(RegionLayer.MAP), found.getValue(id).layers)
        assertNotNull(e.details.value.single().update)
        runBlocking { e.resume(id, update = true) }
        assertEquals(320_000L, files.atlas(id).length())
        assertNull(e.details.value.single().update)
        assertTrue(runBlocking { e.checkUpdates() }.isEmpty())
    }

    @Test
    fun anEstimateIsThePacksBytesOrAnAreaFigureOrNothingKnown() {
        val p = thePack()
        val withPack = runBlocking { engine(deps({ listOf(p) })).estimate(inside, setOf(RegionLayer.MAP, RegionLayer.PLACES, RegionLayer.VOICE)) }
        assertEquals(MapKind.ATLAS, withPack.mapKind)
        assertEquals(300_000L, withPack.bytes[RegionLayer.MAP])
        assertEquals(80_000L, withPack.bytes[RegionLayer.PLACES])
        assertEquals(0L, withPack.bytes[RegionLayer.VOICE])
        assertEquals(380_000L, withPack.total)
        assertNotNull(withPack.pack)

        val built = runBlocking { engine(deps({ emptyList() }, builder = { _, _, _, _, _ -> 0L })).estimate(town, setOf(RegionLayer.MAP)) }
        assertEquals(MapKind.ATLAS, built.mapKind)
        assertEquals((RegionEngine.area(town) * 20_000).toLong(), built.bytes[RegionLayer.MAP]!!)
        assertNull(built.pack)

        // an area beyond what the phone builds (some 1,900 km²) has no map without a pack
        val tooBig = runBlocking { engine(deps({ emptyList() }, builder = { _, _, _, _, _ -> error("not built") })).estimate(inside, setOf(RegionLayer.MAP)) }
        assertTrue(RegionEngine.area(inside) > RegionEngine.BUILD_MAX_KM2)
        assertEquals(MapKind.NONE, tooBig.mapKind)
        assertNull(tooBig.bytes[RegionLayer.MAP])

        val nothing = runBlocking { engine(deps({ emptyList() })).estimate(inside, setOf(RegionLayer.MAP)) }
        assertEquals(MapKind.NONE, nothing.mapKind)
        assertNull(nothing.bytes[RegionLayer.MAP])
        assertEquals(0L, nothing.total)
    }

    @Test
    fun aLayerBuiltOnThePhoneIsOfferedForRefreshAfterAMonthWithoutASizeAndOnlyThatLayerIsRefreshed() {
        val builds = AtomicInteger()
        val imports = AtomicInteger()
        val e = engine(deps({ emptyList() }, builder = { _, _, _, target, _ -> builds.incrementAndGet(); target.writeBytes(data(10_000, 3)); 10_000L }, import = { _, _, t, _ -> imports.incrementAndGet(); t.writeBytes(data(2_000, 4)) }))
        val id = RegionEngine.idFor("Aging")
        e.begin(id, "Aging", town, setOf(RegionLayer.MAP, RegionLayer.PLACES))
        runBlocking { e.resume(id) }
        assertTrue("fresh", runBlocking { e.checkUpdates() }.isEmpty())
        clock += 31L * 24 * 3600 * 1000
        val found = runBlocking { e.checkUpdates() }
        assertEquals(setOf(RegionLayer.MAP, RegionLayer.PLACES), found.getValue(id).layers)
        assertNull("no size for what is built or imported", found.getValue(id).bytes)
        // Only the places are stale if the map was refreshed meanwhile.
        files.save(files.get(id)!!.let { it.copy(layers = it.layers + (RegionLayer.MAP to it.layers.getValue(RegionLayer.MAP).copy(at = clock))) })
        assertEquals(setOf(RegionLayer.PLACES), runBlocking { e.checkUpdates() }.getValue(id).layers)
        runBlocking { e.resume(id, update = true) }
        assertEquals("the map was not built again", 1, builds.get())
        assertEquals(2, imports.get())
        assertTrue(runBlocking { e.checkUpdates() }.isEmpty())
    }

    @Test
    fun routingTilesGoStaleAfterSixWeeksButPackedLayersOnlyWhenThePackChanges() {
        val p = thePack()
        val e = engine(deps({ listOf(p) }))
        val id = RegionEngine.idFor("Packed")
        e.begin(id, "Packed", inside, setOf(RegionLayer.MAP, RegionLayer.PLACES))
        runBlocking { e.resume(id) }
        clock += 400L * 24 * 3600 * 1000
        assertTrue("a year old but still the published pack", runBlocking { e.checkUpdates() }.isEmpty())
        files.save(files.get(id)!!.let { it.copy(layers = it.layers + (RegionLayer.ROUTING to LayerRecord("brouter", 5, clock - 44L * 24 * 3600 * 1000))) })
        assertTrue(runBlocking { e.checkUpdates() }.isEmpty())
        files.save(files.get(id)!!.let { it.copy(layers = it.layers + (RegionLayer.ROUTING to LayerRecord("brouter", 5, clock - 46L * 24 * 3600 * 1000))) })
        assertEquals(setOf(RegionLayer.ROUTING), runBlocking { e.checkUpdates() }.getValue(id).layers)
    }

    @Test
    fun aPackListThatCannotBeReadIsNoPacks() {
        val e = engine(deps({ throw IOException("offline") }))
        assertTrue(runBlocking { e.estimate(inside, setOf(RegionLayer.MAP)) }.pack == null)
        assertTrue(runBlocking { e.checkUpdates() }.isEmpty())
    }

    @Test
    fun deletingARegionFreesItsFilesAndForgetsIt() {
        val e = engine(deps({ listOf(thePack()) }))
        val id = RegionEngine.idFor("Around Amsterdam")
        e.begin(id, "Around Amsterdam", inside, setOf(RegionLayer.MAP, RegionLayer.PLACES))
        runBlocking { e.resume(id) }
        val freed = e.delete(id)
        assertTrue("freed $freed", freed >= 380_000L)
        assertFalse(files.atlas(id).exists() || files.places(id).exists() || files.meta(id).exists())
        assertTrue(e.details.value.isEmpty())
    }

    @Test
    fun askingAgainAfterAFailureWithTheUpdateFlagStillCarriesOnWhereItStoppedAndAFreshProcessFindsTheStaleLayerItself() {
        val builds = AtomicInteger()
        val imports = AtomicInteger()
        val d = deps({ emptyList() }, builder = { _, _, _, target, _ -> builds.incrementAndGet(); target.writeBytes(data(40_000, 5)); 40_000L },
            import = { _, _, target, _ -> if (imports.incrementAndGet() == 1) throw IOException("no signal") else target.writeBytes(data(9_000, 6)) })
        val id = RegionEngine.idFor("Retried")
        engine(d).begin(id, "Retried", town, setOf(RegionLayer.MAP, RegionLayer.PLACES))
        assertTrue(runCatching { runBlocking { engine(d).resume(id) } }.isFailure)
        // The Retry button asks for an update; a new process (a fresh engine) knows nothing of what was stale.
        runBlocking { engine(d).resume(id, update = true) }
        assertEquals("the map that was complete was not built again", 1, builds.get())
        assertTrue(files.get(id)!!.layers.keys.containsAll(setOf(RegionLayer.MAP, RegionLayer.PLACES)))
        // And with a month gone, a fresh engine asked for an update takes the stale layers only.
        clock += 31L * 24 * 3600 * 1000
        files.save(files.get(id)!!.let { it.copy(layers = it.layers + (RegionLayer.MAP to it.layers.getValue(RegionLayer.MAP).copy(at = clock))) })
        runBlocking { engine(d).resume(id, update = true) }
        assertEquals("the map was fresh", 1, builds.get())
        assertEquals("the places were stale", 3, imports.get())
    }

    @Test
    fun aRegionDeletedWhileItDownloadsStopsQuietlyAndIsNotBroughtBack() {
        lateinit var e: RegionEngine
        val d = deps({ emptyList() }, builder = { id, _, _, target, _ -> target.writeBytes(data(5_000, 1)); e.delete(id); 5_000L })
        e = engine(d)
        val id = RegionEngine.idFor("Gone")
        e.begin(id, "Gone", town, setOf(RegionLayer.MAP, RegionLayer.PLACES))
        val err = runCatching { runBlocking { e.resume(id) } }.exceptionOrNull()
        assertTrue("stopped as cancelled, not as a failure: $err", err is RegionGone)
        assertNull(files.get(id))
        assertTrue(e.details.value.isEmpty())
    }

    @Test
    fun severalBoxesCountASharedRoutingTileOnce() {
        val e = engine(deps({ emptyList() }))
        // Two boxes of one corridor in the same 5-degree routing tile, and one far away in another.
        val a = BBox(4.5, 52.1, 4.7, 52.2)
        val b = BBox(4.8, 52.3, 5.0, 52.4)
        val far = BBox(11.0, 48.0, 11.2, 48.1)
        val one = runBlocking { e.estimate(a, setOf(RegionLayer.ROUTING)) }.bytes.getValue(RegionLayer.ROUTING)!!
        val both = runBlocking { e.estimateAll(listOf(a, b), setOf(RegionLayer.ROUTING)) }.bytes.getValue(RegionLayer.ROUTING)!!
        assertEquals("the tile is counted once", one, both)
        val three = runBlocking { e.estimateAll(listOf(a, b, far), setOf(RegionLayer.ROUTING)) }.bytes.getValue(RegionLayer.ROUTING)!!
        assertEquals(2 * one, three)
        val packed = runBlocking { engine(deps({ listOf(thePack()) })).estimateAll(listOf(inside, inside), setOf(RegionLayer.MAP, RegionLayer.PLACES)) }
        assertTrue(packed.packed)
        assertEquals(2 * 380_000L, packed.total)
        assertFalse(runBlocking { e.estimateAll(listOf(a), setOf(RegionLayer.MAP)) }.packed)
    }

    @Test
    fun sizesAreWrittenAsTheScreensWriteThem() {
        assertEquals("999 B", RegionEngine.size(999))
        assertEquals("2 kB", RegionEngine.size(1_001))
        assertEquals("640 MB", RegionEngine.size(640_000_000))
        assertEquals("1.9 GB", RegionEngine.size(1_940_000_000))
        assertEquals("around-amsterdam", RegionEngine.idFor("Around Amsterdam"))
        assertEquals(RegionEngine.idFor("Around Amsterdam"), RegionEngine.idFor("around  amsterdam"))
    }

    @Test
    fun theMetadataSurvivesTheRoundTripThroughItsFile() {
        val m = RegionMeta(
            "x", "Ex", inside, 5, 6, packId = "test",
            layers = mapOf(RegionLayer.MAP to LayerRecord("atlas", 123, 7, "abc", "2026-10-01T00:00:00Z"), RegionLayer.ROUTING to LayerRecord("brouter", 9, 8)),
            segments = listOf("E0_N50.rd5"), voices = listOf("nl"), wanted = setOf(RegionLayer.MAP, RegionLayer.ROUTING),
        )
        files.save(m)
        assertEquals(m, files.get("x"))
        assertEquals(listOf("x"), files.list().map { it.id })
        assertNull(RegionMeta.fromJson("{not json"))
    }
}

/** The manager over the engine: requests are queued with the Wi-Fi setting of the moment, run, covered and cancelled. */
class AtlasRegionManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Queue : RegionScheduler {
        val queued = ArrayList<Triple<String, Boolean, Boolean>>()
        val cancelled = ArrayList<String>()
        override fun schedule(id: String, wifiOnly: Boolean, update: Boolean) { queued += Triple(id, wifiOnly, update) }
        override fun cancel(id: String) { cancelled += id }
    }

    private val box = BBox(4.80, 52.33, 4.99, 52.42)
    private val queue = Queue()
    private var wifi = true
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    private fun manager(): AtlasRegionManager {
        val files = RegionFiles(tmp.newFolder("regions"), tmp.newFolder("places"), tmp.newFolder("segments"))
        val deps = RegionDeps(
            packs = { emptyList() }, packDownloader = PackDownloader(), importPlaces = { _, _, t, _ -> t.writeBytes(ByteArray(2_000)) }, segments = null,
            mapBuilder = { _, _, _, target, _ -> target.writeBytes(ByteArray(30_000)); 30_000L },
        )
        return AtlasRegionManager(files, RegionEngine(files, deps) {}, { queue }, { wifi }, scope)
    }

    @Test
    fun aRequestIsQueuedWithTheWifiSettingOfTheMomentAndShowsAsWaiting() {
        val m = manager()
        val id = m.download(RegionRequest("Amsterdam", box, setOf(RegionLayer.MAP, RegionLayer.PLACES)))
        assertEquals("amsterdam", id)
        assertEquals(listOf(Triple("amsterdam", true, false)), queue.queued)
        assertTrue(m.details.value.single().progress!!.waiting)
        wifi = false
        m.download(RegionRequest("Haarlem", BBox(4.55, 52.33, 4.70, 52.43)))
        assertEquals(Triple("haarlem", false, false), queue.queued.last())
    }

    @Test
    fun runningItFinishesTheRegionAndTheMapNowKnowsItCoversThePlace() {
        val m = manager()
        val id = m.download(RegionRequest("Amsterdam", box, setOf(RegionLayer.MAP, RegionLayer.PLACES)))
        assertNull(m.covering(LatLon(52.37, 4.88)))
        assertTrue(runBlocking { m.run(id) }.isSuccess)
        val r = m.regions.value.single()
        assertTrue(r.complete)
        assertEquals(32_000L, r.bytes)
        assertEquals(id, m.covering(LatLon(52.37, 4.88))!!.id)
        assertNull(m.covering(LatLon(48.85, 2.35)))
        assertEquals(32_000L, m.totalBytes)
    }

    @Test
    fun aFailureComesBackAsAResultAndTheCardSaysWhy() {
        val files = RegionFiles(tmp.newFolder("r2"), tmp.newFolder("p2"), tmp.newFolder("s2"))
        val deps = RegionDeps(packs = { emptyList() }, packDownloader = PackDownloader(), importPlaces = { _, _, _, _ -> error("x") }, segments = null)
        val m = AtlasRegionManager(files, RegionEngine(files, deps) {}, { queue }, { true }, scope)
        val id = m.download(RegionRequest("Nowhere", box))
        val res = runBlocking { m.run(id) }
        assertTrue(res.isFailure)
        assertNotNull(m.details.value.single().error)
        assertFalse(m.regions.value.single().complete)
        assertNotNull(m.regions.value.single().error)
    }

    @Test
    fun cancellingStopsTheWorkAndClearsTheProgress() {
        val m = manager()
        val id = m.download(RegionRequest("Amsterdam", box))
        m.cancel(id)
        assertEquals(listOf(id), queue.cancelled)
        assertNull(m.details.value.single().progress)
    }

    @Test
    fun anUpdateIsQueuedAsOne() {
        val m = manager()
        val id = m.download(RegionRequest("Amsterdam", box))
        runBlocking { m.run(id) }
        m.update(id)
        assertEquals(Triple(id, true, true), queue.queued.last())
    }

    @Test
    fun deletingForgetsTheRegionAndItsFiles() {
        val m = manager()
        val id = m.download(RegionRequest("Amsterdam", box, setOf(RegionLayer.MAP, RegionLayer.PLACES)))
        runBlocking { m.run(id); m.delete(id) }
        assertTrue(m.regions.value.isEmpty())
        assertEquals(0L, m.totalBytes)
    }
}
