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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.security.MessageDigest
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
    fun whatOfflineUseDependsOnIsKeptInTheAppsFilesNotItsCache() {
        // Android may clear the cache to free space: the forecasts, the charger registers, the places' pictures and the
        // map's windows are kept in the files, which it never clears
        val s = testServices()
        for (kept in listOf(s.dirs.providers, s.dirs.images, s.dirs.engine)) {
            assertTrue("$kept", kept.startsWith(s.dirs.files))
            assertFalse("$kept", kept.startsWith(s.dirs.cache))
        }
        // the code names the cache for nothing: today's map's work and its noise, which came again by themselves, were all
        // it held, and left the phone on 5 November
        val android = File(System.getProperty("atlas.repo")!!, "app/android")
        val uses = listOf("app", "mapview").flatMap { m -> File(android, "$m/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { f -> f.readLines().map { it.trim() }.filter { "dirs.cache" in it && !it.startsWith("*") && !it.startsWith("//") }.map { "${f.name}: $it" } }
        assertTrue(uses.toString(), uses.isEmpty())
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

    private fun licenceList(): String = RuntimeEnvironment.getApplication().resources.openRawResource(app.atlas.R.raw.mine_licences).bufferedReader().use { it.readText() }

    @Test
    fun theLicenceListHasItsSymbolSetsFontsCodeAndLibraries() {
        val items = Licences.parse(licenceList())
        assertTrue("${items.size} items", items.size > 25)
        assertTrue(items.all { it.what.isNotBlank() && it.licence.isNotBlank() })
        // the providers' bundled data is in the credits card above the list (Catalogue.BUNDLED), so no card of its own
        assertEquals(setOf(1, 2, 3), items.map { Licences.section(it) }.toSet())
        for (name in listOf("Maki", "Geist", "HarfBuzz", "OSRM Text Instructions")) assertTrue(name, items.any { it.what == name })
        assertTrue(items.any { it.module == "libraries" && it.what.contains("OkHttp", true) })
        assertEquals(0, Licences.section(LicenceItem("providers", "x", "y")))
        assertEquals(2, Licences.section(LicenceItem("tiles", "x", "y")))
    }

    @Test
    fun eachLineOfTheLicenceListIsANameACreditAndALicenceName() {
        // About lists each thing the app carries once, in a user's words (10 November): no path, file or test fixture,
        // a licence by its name alone (its notes stay in the LICENSES.md tables), and a thing two files list (Geist,
        // Lucide, Earcut) on one line.
        val items = Licences.parse(licenceList())
        val file = Regex("""[/`]|\.(kt|kts|java|json|tsv|csv|txt|md|html|ttf|otf|svg|png|py|cpp|hpp|h|slang|gz|atlas)\b""")
        val notes = Regex("""[(;"`©]|\b(see|below|notice|our own)\b""", RegexOption.IGNORE_CASE)
        val tests = Regex("""\btests?\b""", RegexOption.IGNORE_CASE)
        for (it in items) {
            assertFalse("${it.what}: a name, not a path or a file", file.containsMatchIn(it.what))
            assertFalse("${it.what}: \"${it.licence}\" is to be a licence's name", notes.containsMatchIn(it.licence) || file.containsMatchIn(it.licence))
            assertFalse("${it.what}: what only the tests read stays off About", tests.containsMatchIn("${it.what} ${it.credit.orEmpty()} ${it.licence}"))
        }
        val twice = items.groupBy { it.what }.filterValues { it.size > 1 }.keys
        assertTrue("listed more than once: $twice", twice.isEmpty())
    }

    @Test
    fun theLicenceListIsMadeFromTheLicenceFilesAsTheyAreNow() {
        // mine_licences.py keeps a hash of each LICENSES.md, whole: a table, a marker that keeps one off About or a
        // file come or gone since, and About & licences is behind its files (it was 52 lines behind them until 10 November).
        val made = Json.parseToJsonElement(licenceList()).jsonObject["made_from"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val android = File(System.getProperty("atlas.repo")!!, "app/android")
        val now = licenceFiles(android).associate { it.relativeTo(android).invariantSeparatorsPath to sha256(it.readText().replace("\r\n", "\n")) }
        assertTrue(now.keys.toString(), "app/LICENSES.md" in now && "places/LICENSES.md" in now && "mapcore/cpp/LICENSES.md" in now)
        val behind = (made.keys + now.keys).filter { made[it] != now[it] }.sorted()
        assertTrue("About & licences is behind ${behind.joinToString()}: run app/src/main/kotlin/app/atlas/ui/mine/art/mine_licences.py", behind.isEmpty())
        // and so are the texts it opens, behind the files they are cut from
        val from = Json.parseToJsonElement(licenceList()).jsonObject["texts_from"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertTrue(from.keys.toString(), from.size > 10)
        val stale = from.filter { (path, sha) -> File(android, path).let { !it.isFile || sha256(it.readText().replace("\r\n", "\n")) != sha } }.keys
        assertTrue("The licence texts are behind ${stale.joinToString()}: run app/src/main/kotlin/app/atlas/ui/mine/art/mine_licences.py", stale.isEmpty())
    }

    // ---- the licence texts a line opens ----

    private fun licenceTexts(): String = RuntimeEnvironment.getApplication().resources.openRawResource(app.atlas.R.raw.mine_licence_texts).bufferedReader().use { it.readText() }

    private fun words(text: String) = text.trim().split(Regex("""\s+"""))

    /**
     * Every line of About that names a licence and opens nothing, and why, so none is forgotten: what is in the public
     * domain, a service's own terms, data whose licence is the source's own, and two whose text is not yet in the app.
     */
    private val opensNothing = setOf(
        "SGP4 and SDP4" to "Public domain", // David Vallado's code, in the public domain
        "Natural Earth" to "Public domain",
        "Fast Hilbert curve algorithm" to "Public domain",
        "SQLite" to "Public domain",
        "fdlibm" to "SunPro License", // its credit on the list is its licence whole
        // the licence is published as a PDF only; its notices (Art. 6) are the credit on the list
        "Copernicus DEM GLO-30" to "Licence for the use of the Copernicus WorldDEM-30",
        "Copernicus DEM GLO-90" to "Licence for the use of the Copernicus WorldDEM-90",
        "CelesTrak and ARISS" to "Public data",
        "Wikimedia Commons" to "Per picture", // each picture shows its own licence
        "Foursquare" to "Foursquare terms",
        "Yelp" to "Yelp API terms",
        "Tripadvisor" to "Tripadvisor terms",
        "NDW (Netherlands)" to "Open data, licence not stated by NDW",
        "OpenAddresses" to "Each source's own: CC0, CC BY, Licence Ouverte…", // each address carries its source's
    )

    @Test
    fun everyLineThatNamesALicenceOpensItsTextOrIsNamedAsOpeningNothing() {
        val list = Licences.read(licenceList())
        val texts = Licences.texts(licenceTexts())
        // the lines, the credits card's sources, Atlas at the top and the map data's card
        val rows = list.items.map { it.what to it.licence } + Licences.credits().map { it.name to it.licence } +
            (Licences.ATLAS to "MIT License") + ("© OpenStreetMap contributors" to "ODbL")
        val none = rows.filter { Licences.line(list, it.first, it.second) == null }.toSet()
        assertEquals("open nothing", opensNothing, none)
        for ((what, licence) in rows - none) {
            val line = Licences.line(list, what, licence)!!
            for (id in line.texts) assertTrue("$what opens $id, which is not there", id in texts)
        }
        assertTrue("Atlas opens its own MIT licence with its notice", list.atlas.notice!!.startsWith("Copyright (c) 2026 atlas-maps-app") && list.atlas.texts == listOf("mit"))
    }

    @Test
    fun aLineOpensEachLicenceItNamesAfterItsOwnNotice() {
        val list = Licences.read(licenceList())
        val names = list.names
        assertEquals(listOf("mit", "apache-2.0"), Licences.textsOf("MIT License and Apache License 2.0", names))
        assertEquals(listOf("apache-2.0", "mit"), Licences.textsOf("Apache License 2.0 or MIT License", names))
        assertEquals(listOf("cc-by-4.0"), Licences.textsOf("CC BY 4.0 (EEA); Public Domain (zwemwater.nl)", names))
        assertEquals(listOf("mit-attribution", "cdla-permissive-2.0"), Licences.textsOf("MIT with attribution; CDLA-Permissive-2.0", names))
        assertEquals(emptyList<String>(), Licences.textsOf("Public domain", names))
        assertEquals(listOf("w3c-software-2023"), Licences.textsOf("W3C Software and Document License", names))
        // a font's notice is its credit: its copyright and its Reserved Font Name
        val orbitron = Licences.line(list, "Orbitron", "SIL Open Font License 1.1")!!
        assertTrue(orbitron.notice!!, orbitron.notice!!.contains("Reserved Font Name") && orbitron.texts == listOf("ofl-1.1"))
        // a port's notice is its own copyright lines, not the list's short credit; HarfBuzz opens both its licences
        assertEquals("Copyright (c) 2026, Mapbox\nCopyright (c) 2015, Mapbox", Licences.line(list, "Earcut", "ISC License")!!.notice)
        val harfbuzz = Licences.line(list, "HarfBuzz", "Old MIT License and MIT License")!!
        assertEquals(listOf("old-mit", "mit"), harfbuzz.texts)
        assertTrue(harfbuzz.notice!!.lines().size > 10 && harfbuzz.notice!!.endsWith("Copyright (c) Microsoft Corporation."))
        // the same name on the credits card and the list: BRouter's routing data (ODbL) and its library (MIT)
        assertEquals("Copyright (c) 2019 BRouter contributors", Licences.line(list, "BRouter", "MIT License")!!.notice)
        val data = Licences.line(list, "BRouter", "ODbL")!!
        assertTrue(data.notice!!, data.notice!!.startsWith("Routing data") && data.texts == listOf("odbl-1.0"))
    }

    @Test
    fun eachTextIsStoredOnceAndWholeAsTheFileItIsTakenFromHoldsIt() {
        val texts = Licences.texts(licenceTexts())
        val android = File(System.getProperty("atlas.repo")!!, "app/android")
        val from = Json.parseToJsonElement(licenceList()).jsonObject["texts_from"]!!.jsonObject.keys
        val files = from.map { File(android, it).readText().replace("\r\n", "\n") }
        for ((id, t) in texts) assertTrue("$id is cut from a file it is taken from, as it is there", files.any { it.contains(t.text) })
        assertEquals("a text stored twice", texts.size, texts.values.map { words(it.text) }.toSet().size)
        // each from its first words to its last: none cut short
        val ends = mapOf(
            "ofl-1.1" to ("----------------------------------------------------------- SIL OPEN FONT LICENSE Version 1.1" to "OTHER DEALINGS IN THE FONT SOFTWARE."),
            "apache-2.0" to ("Apache License Version 2.0, January 2004" to "limitations under the License."),
            "mit" to ("Permission is hereby granted, free of charge" to "OTHER DEALINGS IN THE SOFTWARE."),
            "isc" to ("Permission to use, copy, modify" to "PERFORMANCE OF THIS SOFTWARE."),
            "bsd-2-clause" to ("Redistribution and use" to "POSSIBILITY OF SUCH DAMAGE."),
            "bsd-3-clause" to ("Redistribution and use" to "POSSIBILITY OF SUCH DAMAGE."),
            "bsd-3-clause-zstd" to ("Redistribution and use" to "POSSIBILITY OF SUCH DAMAGE."),
            "old-mit" to ("Permission is hereby granted, without written agreement" to "ENHANCEMENTS, OR MODIFICATIONS."),
            "psf-2.0" to ("PYTHON SOFTWARE FOUNDATION LICENSE VERSION 2" to "terms and conditions of this License Agreement."),
            "w3c-software-2023" to ("This work is being provided" to "will at all times remain with copyright holders."),
            "cc0-1.0" to ("Creative Commons Legal Code CC0 1.0 Universal" to "this CC0 or use of the Work."),
            "cc-by-4.0" to ("Attribution 4.0 International" to "Creative Commons may be contacted at creativecommons.org."),
            "cc-by-sa-4.0" to ("Attribution-ShareAlike 4.0 International" to "Creative Commons may be contacted at creativecommons.org."),
            "odbl-1.0" to ("## ODC Open Database License (ODbL)" to "in order to meet the terms of this License."),
            "nlod-2.0" to ("Preface of licence" to "where the intellectual property rights are sought enforced."),
            "mit-attribution" to ("MIT License with Attribution Requirement" to "OTHER DEALINGS IN THE SOFTWARE."),
            "cdla-permissive-2.0" to ("# Community Data License Agreement - Permissive - Version 2.0" to "machine learning models and models' insights."),
        )
        assertEquals("a text whose ends the test does not know", ends.keys, texts.keys)
        for ((id, e) in ends) {
            val w = words(texts[id]!!.text).joinToString(" ")
            assertTrue("$id starts \"${e.first}\": ${w.take(80)}", w.startsWith(e.first))
            assertTrue("$id ends \"${e.second}\": ${w.takeLast(80)}", w.endsWith(e.second))
        }
    }

    @Test
    fun aTextsParagraphsKeepEveryWordInItsOrder() {
        val texts = Licences.texts(licenceTexts())
        for ((id, t) in texts) {
            val shown = Licences.paragraphs(t.text)
            assertEquals(id, words(t.text), words(shown.joinToString(" ") { it.text }))
        }
        // a text wrapped at its width is its paragraphs; one written a paragraph a line stays so
        assertEquals(2, Licences.paragraphs(texts["isc"]!!.text).size)
        assertEquals(3, Licences.paragraphs(texts["mit"]!!.text).size)
        // a heading, a rule and a list's item each start a paragraph of their own
        val ofl = Licences.paragraphs(texts["ofl-1.1"]!!.text).map { it.text }
        assertTrue(ofl.take(4).toString(), "PREAMBLE" in ofl && ofl.any { it.startsWith("1) Neither the Font Software") } && ofl.first().all { it == '-' })
    }

    /** The LICENSES.md files mine_licences.py reads: a module's, an app screen package's, and the map core's C++ one. */
    private fun licenceFiles(android: File): List<File> =
        (listOf(android, File(android, "app/src/main/kotlin/app/atlas/ui")).flatMap { dir -> dir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") } } + File(android, "mapcore/cpp"))
            .map { File(it, "LICENSES.md") }.filter { it.isFile }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

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
