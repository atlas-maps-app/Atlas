package app.atlas.ui.mine

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.providers.EvModels
import app.atlas.services.SettingsKeys
import app.atlas.services.Units
import app.atlas.testing.testServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** What the phone keeps for you and how it moves: the backup file, the kept forecast, and the licence list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MineStateTest {
    private val cafe = Place(OsmId(OsmType.NODE, 4242), LatLon(52.3733, 4.8840), mapOf("amenity" to "cafe", "name" to "Café Molen"))

    private fun filled(): Services {
        val s = testServices()
        runBlocking {
            s.settings.set(SettingsKeys.UNITS, Units.IMPERIAL.name)
            s.settings.set(MineKeys.TEMP_F, true)
            s.settings.set(MineKeys.CLOCK_12, true)
            s.settings.set(MineKeys.WATER_BOTTLE_ML, 1500)
            s.settings.set(MineKeys.VOICE_LANGS, setOf("nl", "en"))
            s.settings.set(MineKeys.EV_SOC, 0.42f)
            s.settings.set(SettingsKeys.CAMERA_LAT, 48.85)
            s.keys.set("tankerkoenig", "secret-key-123")
        }
        val trip = s.saved.createList("Trip", 0xFF2E7BFF)
        s.saved.save(cafe, trip)
        s.saved.save(cafe)
        s.saved.keepLayer("cafes with wifi", true)
        s.saved.hideBrand("brand-1", true)
        return s
    }

    @Test
    fun theBackupIsReadableJsonThatLeavesTheKeysAndTheCameraBehind() {
        val text = runBlocking { Backup.export(filled()) }
        assertTrue(text.contains("\"format\":\"atlas-backup\""))
        assertFalse("a provider key is a secret", text.contains("secret-key-123") || text.contains("tankerkoenig"))
        assertFalse("where the camera last looked stays", text.contains("camera.lat"))
        assertTrue(text.contains("Café Molen") && text.contains("cafes with wifi") && text.contains("brand-1"))
        assertFalse("no trail unless asked", text.contains("\"trail\""))
    }

    @Test
    fun movingToANewPhoneBringsTheSettingsListsPlacesAndSearches() {
        val text = runBlocking { Backup.export(filled()) }
        val fresh = testServices()
        val summary = runBlocking { Backup.import(fresh, text) }
        assertTrue(summary.settings >= 6)
        assertEquals(1, summary.lists)
        assertEquals(2, summary.places)
        assertEquals(1, summary.layers)
        runBlocking {
            assertEquals(Units.IMPERIAL, fresh.settings.units.first())
            assertEquals(true, fresh.settings.flow(MineKeys.TEMP_F, false).first())
            assertEquals(1500, fresh.settings.flow(MineKeys.WATER_BOTTLE_ML, 0).first())
            assertEquals(setOf("nl", "en"), fresh.settings.flow(MineKeys.VOICE_LANGS, emptySet()).first())
            assertEquals(0.42f, fresh.settings.flow(MineKeys.EV_SOC, 0f).first(), 0f)
            assertNull("the camera is not carried over", fresh.settings.data.first()[SettingsKeys.CAMERA_LAT])
        }
        assertNull(fresh.keys.key("tankerkoenig"))
        assertTrue(fresh.saved.lists.value.any { it.name == "Trip" && it.places.size == 1 })
        assertTrue(fresh.saved.isSaved(cafe.key))
        assertTrue(fresh.saved.layers.value.any { it.text == "cafes with wifi" && it.alert })
        assertTrue("brand-1" in fresh.saved.hiddenBrands.value)
    }

    @Test
    fun importingTwiceAddsNothingTwice() {
        val text = runBlocking { Backup.export(filled()) }
        val s = testServices()
        runBlocking { Backup.import(s, text) }
        val again = runBlocking { Backup.import(s, text) }
        assertEquals(0, again.lists)
        assertEquals(0, again.places)
        assertEquals(0, again.layers)
        assertEquals(1, s.saved.lists.value.count { it.name == "Trip" })
    }

    @Test
    fun theTrailTravelsOnlyWhenAskedFor() {
        val s = filled()
        s.mine.trail.record(LatLon(52.37, 4.88), Instant.parse("2026-10-14T10:00:00Z"))
        s.mine.trail.record(LatLon(52.38, 4.90), Instant.parse("2026-10-14T11:00:00Z"))
        val withTrail = runBlocking { Backup.export(s, trail = true) }
        val fresh = testServices()
        runBlocking { Backup.import(fresh, withTrail) }
        assertEquals(2, fresh.mine.trail.count())
        val other = testServices()
        runBlocking { Backup.import(other, runBlocking { Backup.export(s) }) }
        assertEquals(0, other.mine.trail.count())
    }

    @Test
    fun aFileThatIsNotABackupOrIsFromTheFutureIsRefusedPlainly() {
        val s = testServices()
        for (bad in listOf("hello", "{}", "{\"format\":\"something-else\"}", "[1,2]")) {
            try { runBlocking { Backup.import(s, bad) }; fail("accepted $bad") } catch (e: IllegalArgumentException) { assertEquals("This is not an Atlas backup.", e.message) }
        }
        try { runBlocking { Backup.import(s, "{\"format\":\"atlas-backup\",\"version\":99}") }; fail("accepted a newer file") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("newer")) }
    }

    @Test
    fun aDamagedBackupBringsWhatIsGoodAndNeverBreaksTheSettingsOrCrashes() {
        val s = testServices()
        runBlocking { s.settings.set(SettingsKeys.UNITS, Units.NAUTICAL.name) }
        val text = """{"format":"atlas-backup","version":1,
            "settings":{"units":{"t":"i","v":3},"mine.water.bottle":{"t":"i","v":750},"x":5,"y":{"t":"zz","v":1},"z":{"t":"s"},"mine.temp.f":{"t":"b","v":"maybe"}},
            "lists":[1,"x",{"name":"Kept","places":[{"lat":"north","lon":1},5,{"lat":52.37,"lon":4.88,"tags":{"name":"Café","bad":[1]}},{"lat":95.0,"lon":4.0}]}],
            "layers":[7,{"text":"parks"}],"hiddenBrands":[1,{"a":1},"brand-9"],"trail":[[1],"x",[1760000000,52.37,"east"]]}"""
        val summary = runBlocking { Backup.import(s, text) }
        runBlocking {
            assertEquals("a value of another type than the one held is not taken", Units.NAUTICAL, s.settings.units.first())
            assertEquals(750, s.settings.flow(MineKeys.WATER_BOTTLE_ML, 0).first())
            assertEquals(false, s.settings.flow(MineKeys.TEMP_F, false).first())
        }
        assertEquals(1, summary.settings)
        assertEquals(1, summary.lists)
        assertEquals("one good place, the others skipped", 1, summary.places)
        assertEquals(1, summary.layers)
        assertTrue("brand-9" in s.saved.hiddenBrands.value)
        assertEquals(0, s.mine.trail.count())
    }

    @Test
    fun aFileFarTooBigForABackupIsRefusedBeforeItFillsTheMemory() {
        val zeros = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 0); return len }
        }
        try { Backup.read(zeros); fail("read it all") } catch (e: BackupException) { assertTrue(e.tooBig) }
        assertEquals("{}", Backup.read("{}".byteInputStream()))
    }

    @Test
    fun wipingForgetsEverythingPersonalIncludingKeysAndTheTrail() {
        val s = filled()
        s.mine.trail.record(LatLon(52.37, 4.88), Instant.parse("2026-10-14T10:00:00Z"))
        s.mine.pressure.add(1012.0, Instant.parse("2026-10-14T10:00:00Z"))
        runBlocking { Backup.wipe(s) }
        assertTrue(runBlocking { s.settings.data.first() }.asMap().isEmpty())
        assertNull(s.keys.key("tankerkoenig"))
        assertTrue(s.saved.lists.value.none { it.places.isNotEmpty() })
        assertTrue(s.saved.layers.value.isEmpty() && s.saved.hiddenBrands.value.isEmpty())
        assertEquals(0, s.mine.trail.count())
        assertTrue(s.mine.pressure.readings().isEmpty())
    }

    // ---- the kept forecast ----

    private fun models(): String = MineStateTest::class.java.getResourceAsStream("/mine/models_amsterdam.json")!!.bufferedReader().use { it.readText() }
    private val at = LatLon(52.3733, 4.8840)

    @Test
    fun theFiveModelsOfOpenMeteoAreReadWithTheirHoursAndRain() {
        val f = ModelsForecast.parse(models(), at, Instant.parse("2026-10-13T17:30:00Z"))!!
        assertEquals(5, f.count)
        assertEquals(48, f.hours.size)
        assertTrue(f.curves.all { it.temperature.size == 48 && it.rain.size == 48 })
        val (lo, hi) = f.range!!
        assertTrue("$lo..$hi", lo < hi && lo > -20 && hi < 40)
        assertEquals(48, f.rainAgreement().size)
        assertTrue(f.rainAgreement().all { it in 0..5 })
        assertNull("no frost in an Amsterdam October", f.frost())
        assertEquals(0, f.frostModels())
        assertNotNull(f.spread(0))
        assertFalse(f.kept)
    }

    @Test
    fun aModelWithNothingInItIsLeftOutAndNonsenseIsNull() {
        val cut = models().replace(Regex("\"temperature_2m_ecmwf_ifs025\":\\[[^\\]]*\\]"), "\"temperature_2m_ecmwf_ifs025\":[]")
        val f = ModelsForecast.parse(cut, at, Instant.now())!!
        assertTrue(f.count < 5)
        assertNull(ModelsForecast.parse("{\"hourly\":{}}", at, Instant.now()))
        assertNull(ModelsForecast.parse("{}", at, Instant.now()))
        assertNull("a Wi-Fi's sign-in page is no forecast", ModelsForecast.parse("<html><body>Sign in</body></html>", at, Instant.now()))
    }

    @Test
    fun theRequestAsksForEveryModelFor48Hours() {
        val u = ModelsForecast.url(at)
        assertTrue(u.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(u.contains("latitude=52.3733") && u.contains("longitude=4.8840") && u.contains("forecast_hours=48") && u.contains("timeformat=unixtime"))
        for (m in WeatherModel.entries) assertTrue(m.id, u.contains(m.id))
    }

    private fun keep(dir: File, p: LatLon, fetched: Instant) {
        dir.mkdirs()
        File(dir, "models_${"%.2f_%.2f".format(java.util.Locale.ROOT, p.lat, p.lon)}.json").writeText("${fetched.epochSecond}\n${models()}")
    }

    @Test
    fun aForecastThatFailedWithAConnectionSaysTheServicesDidNotAnswerNotThatThereIsNone() {
        val ctx = RuntimeEnvironment.getApplication()
        assertTrue(ctx.getString(noForecastText(askedOnline = true, app.atlas.R.string.mine_weather_none_text)).startsWith("The weather services did not answer"))
        assertTrue(ctx.getString(noForecastText(askedOnline = false, app.atlas.R.string.mine_weather_none_text)).startsWith("No connection"))
        assertTrue(ctx.getString(noForecastText(askedOnline = false, app.atlas.R.string.mine_forecast_none_text)).startsWith("No connection"))
    }

    @Test
    fun withNoSignalTheLastAnswerNearbyIsShownAsKeptUntilItIsThreeDaysOld() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "kept-test").apply { deleteRecursively() }
        val now = Instant.parse("2026-10-14T12:00:00Z")
        val fetcher = ModelsFetcher(app.atlas.providers.Http(), dir) { now }
        assertNull("nothing kept yet", fetcher.kept(at))
        keep(dir, at, now.minusSeconds(3600))
        val k = fetcher.kept(LatLon(52.40, 4.90))!!
        assertTrue(k.kept)
        assertEquals(Instant.parse("2026-10-14T11:00:00Z"), k.fetchedAt)
        assertNull("too far away to stand for another place", fetcher.kept(LatLon(51.92, 4.48)))
        File(dir, "models_52.37_4.88.json").delete()
        keep(dir, at, now.minusSeconds(4 * 24 * 3600L))
        assertNull("older than three days is no forecast", fetcher.kept(at))
    }

    // ---- the licence list ----

    @Test
    fun everyBundledDatasetAndLibraryIsInTheLicenceList() {
        val ctx = RuntimeEnvironment.getApplication()
        val text = ctx.resources.openRawResource(app.atlas.R.raw.mine_licences).bufferedReader().use { it.readText() }
        val items = Licences.parse(text)
        assertTrue("${items.size} items", items.size > 25)
        assertTrue(items.all { it.what.isNotBlank() && it.licence.isNotBlank() })
        val sections = items.map { Licences.section(it) }.toSet()
        assertEquals(setOf(0, 1, 2, 3), sections)
        assertTrue(items.any { it.what.contains("OpenStreetMap", true) || it.licence.contains("ODbL", true) })
        assertTrue(items.any { it.module == "libraries" && it.what.contains("OkHttp", true) })
        assertEquals(0, Licences.section(LicenceItem("providers", "x", "y")))
        assertEquals(2, Licences.section(LicenceItem("tiles", "x", "y")))
    }

    @Test
    fun theEvPickerListsRealCarsAndTheChosenOneSurvivesTheBackup() {
        val car = EvModels.bundled().all.first { it.make == "Tesla" }
        val s = testServices()
        runBlocking { s.settings.set(MineKeys.EV_MODEL, car.id) }
        val text = runBlocking { Backup.export(s) }
        val fresh = testServices()
        runBlocking { Backup.import(fresh, text) }
        assertEquals(car.id, runBlocking { fresh.settings.flow(MineKeys.EV_MODEL, "").first() })
    }
}
