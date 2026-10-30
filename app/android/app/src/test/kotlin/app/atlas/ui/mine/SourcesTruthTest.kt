package app.atlas.ui.mine

import app.atlas.providers.Catalogue
import app.atlas.providers.KeyNeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Data and Privacy screens' sources held to the code's own clients, so they cannot drift (the engine agent, 30
 * October 2026: "A privacy screen must never claim more or less than the app does"):
 * - every host the shipped code names is a listed source's, a link the user opens, or an unwired client's;
 * - every listed source is built or asked by the app's own wiring, and no unwired one is;
 * - a switch holds back exactly its source's hosts, and the rows list a source only while the app asks it.
 */
class SourcesTruthTest {
    private val android = File(System.getProperty("atlas.repo") ?: "../..", "app/android")

    /** The modules the APK carries: the packer runs on GitHub's runners, mapcorecheck and likeness are tests. */
    private val shipped = listOf("app", "mapview", "providers", "tiles", "route", "search", "places", "render", "core", "guide", "anchor", "rendertiles", "mapcore")

    /**
     * Hosts the code names but never asks: links the user opens, XML namespaces, and the page that finds the phone's
     * browsers for signing in to OpenStreetMap (openInBrowser).
     */
    private val links = setOf("afdc.energy.gov", "chargemap.com", "example.org", "map.openchargemap.io", "www.topografix.com", "www.w3.org")

    /** What in the app's wiring asks each listed source: a client built, or the address it fetches. */
    private val askedBy = mapOf(
        "overpass" to "Overpass(http, servers = overpassServers)", "nominatim" to "Nominatim(http)", "photon" to "Photon(http)", "mangrove" to "Mangrove(http)",
        "foursquare" to "FoursquareRatings(http", "yelp" to "YelpRatings(http", "tripadvisor" to "TripAdvisorRatings(http",
        "ocm" to "OpenChargeMap(http", "osm-chargers" to "OsmChargers(overpass)", "ndw" to "NdwChargers(http",
        "bnetza" to "BnetzaChargers(http", "open-meteo" to "Weather(http",
        "met-norway" to "Weather(http", "wikidata" to "Wikidata(http)", "commons" to "Commons(http)", "osm-notes" to "OsmNotes(http)",
        "osm-edits" to "OsmEdits(http", "atlas-packs" to "WindowPacks(", "brouter" to "SegmentStore(dirs.segments",
        "panoramax" to "PanoramaxSource(", "open-prices" to "OpenPrices(", "iss-orbit" to "TleStore.SECOND_URL",
        "tankerkoenig" to "Tankerkoenig(",
    )

    /** The written but unwired sources' clients, which the app must not build while no screen lists them. */
    private val unwiredClients = mapOf(
        "afdc" to "Afdc(", "chargemap" to "Chargemap(", "transitous" to "Transitous(",
    )

    private fun sources(modules: List<String>): List<File> = modules.flatMap { m ->
        File(android, "$m/src/main").walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
    }

    /** Every host named by an address in the code of [files], comments left out. */
    private fun hostsIn(files: List<File>): Map<String, String> {
        val url = Regex("""https?://([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")
        val out = LinkedHashMap<String, String>()
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@forEachIndexed
                for (m in url.findAll(code.replace(Regex("""\s//\s.*$"""), ""))) out.putIfAbsent(m.groupValues[1], "${f.relativeTo(android)}:${i + 1}")
            }
        }
        return out
    }

    /** Whether [token] is in the app's wiring (the app and the map's view), other than where its class is declared. */
    private fun wired(token: String): Boolean {
        val pattern = Regex("(?<!class )" + Regex.escape(token))
        return sources(listOf("app", "mapview")).any { f -> f.readLines().any { l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && pattern.containsMatchIn(l) } }
    }

    @Test
    fun everyHostTheShippedCodeAsksIsAListedSources() {
        assertTrue("the Android sources at $android", File(android, "providers/src/main").isDirectory)
        val listed = SourcesModel.ALL.flatMap { it.hosts }.toSet()
        val unwired = Catalogue.NOT_WIRED.flatMap { it.hosts }.toSet()
        val websites = (SourcesModel.ALL + Catalogue.NOT_WIRED + Catalogue.BUNDLED).mapNotNull { java.net.URI(it.url).host }.toSet()
        val named = hostsIn(sources(shipped))
        val unknown = named.filterKeys { it !in listed && it !in unwired && it !in websites && it !in links }
        assertTrue("hosts the code names that no source lists: $unknown", unknown.isEmpty())
    }

    @Test
    fun everyListedSourceIsAskedByTheAppsWiringAndNoUnwiredOneIs() {
        val ids = SourcesModel.ALL.map { it.id }
        assertEquals("one row a source", ids.size, ids.toSet().size)
        assertEquals("a way the app asks each listed source", ids.toSet(), askedBy.keys)
        for ((id, token) in askedBy) assertTrue("$id: nothing in the app asks it ($token)", wired(token))
        assertEquals(Catalogue.NOT_WIRED.map { it.id }.toSet(), unwiredClients.keys)
        for ((id, token) in unwiredClients) assertFalse("$id is built by the app ($token) but not listed", wired(token))
        assertTrue("an unwired source is not listed", SourcesModel.ALL.none { s -> Catalogue.NOT_WIRED.any { it.id == s.id } })
        // every listed source names its hosts, and a link-only host is never asked
        for (s in SourcesModel.ALL) assertTrue("${s.id} names no host", s.hosts.isNotEmpty())
        assertTrue(SourcesModel.ALL.none { s -> s.hosts.any { it in links } })
    }

    @Test
    fun aSwitchHoldsBackExactlyItsSourcesHosts() {
        val owners = SourcesModel.ALL.filter { it.switchable && it.via == null }
        val hosts = owners.flatMap { it.hosts }
        assertEquals("no host held back by two switches", hosts.size, hosts.toSet().size)
        for (s in owners) {
            for (h in s.hosts) {
                assertEquals("${s.id} holds back $h", s.id, SourcesModel.blockedBy(h, setOf(s.id)))
                assertNull("$h goes while ${s.id} is on", SourcesModel.blockedBy(h, SourcesModel.ALL.map { it.id }.toSet() - s.id))
            }
        }
        // a source asked through another's servers is held back where it is asked, never by host; one without a switch never
        for (s in SourcesModel.ALL.filter { it.via != null || !it.switchable }) {
            for (h in s.hosts) if (h !in hosts) assertNull("${s.id}'s $h", SourcesModel.blockedBy(h, setOf(s.id)))
        }
        assertNull(SourcesModel.blockedBy("overpass-api.de", setOf("osm-chargers")))
        assertEquals("overpass", SourcesModel.blockedBy("overpass-api.de", setOf("overpass")))
        assertNull(SourcesModel.blockedBy("api.openstreetmap.org", setOf("osm-notes", "osm-edits")))
        assertNull(SourcesModel.blockedBy("example.org", SourcesModel.ALL.map { it.id }.toSet()))
    }

    @Test
    fun thePrivacyListNamesASourceOnlyWhileTheAppAsksIt() {
        fun asked(off: Set<String> = emptySet(), keys: Map<String, String> = emptyMap()) =
            SourcesModel.asked(SourcesModel.rows(off, keys)).map { it.info.id }.toSet()
        val all = asked()
        // keyed sources are asked only with a key; every keyless one is, by default
        for (s in SourcesModel.ALL) {
            val keyed = s.keyNeed == KeyNeed.REQUIRED || s.keyNeed == KeyNeed.PARTNER
            assertEquals(s.id, !keyed, s.id in all)
            if (keyed) assertTrue(s.id in asked(keys = mapOf(s.id to "k")))
        }
        // switched off: no longer listed (the gate holds its hosts back), nor what is asked through it
        assertFalse("open-meteo" in asked(setOf("open-meteo")))
        assertFalse("osm-chargers" in asked(setOf("overpass")))
        assertTrue("overpass" in asked(setOf("osm-chargers")))
        // a source asked only when the user acts has no switch: listed whatever the settings say
        assertTrue(asked(setOf("osm-notes", "osm-edits")).containsAll(setOf("osm-notes", "osm-edits")))
        // the hosts the list names are the source's own, not its website
        val overpass = SourcesModel.rows(emptySet(), emptyMap()).first { it.info.id == "overpass" }
        assertEquals("overpass-api.de, maps.mail.ru +1", overpass.host)
    }

    @Test
    fun theChargersAndFuelPricesSayTheyAreSentWhereTheUserLooks() {
        // the charger sources said "the area you look at, nothing about you", and Tankerkönig "the area you look at",
        // though each is sent the spot the user looks around (Open Charge Map's and Tankerkönig's to about 100 m) or
        // the route planned
        val privacy = File(android, "app/src/main/kotlin/app/atlas/ui/mine/PrivacyScreen.kt").readText()
        assertTrue(privacy.contains("\"osm-chargers\", \"ocm\", \"afdc\" -> R.string.mine_sent_chargers"))
        assertTrue(privacy.contains("\"tankerkoenig\" -> R.string.mine_sent_fuel"))
        for (lang in listOf("", "-de", "-fr", "-nl")) {
            val xml = File(android, "app/src/main/res/values$lang/strings_mine.xml").readText()
            fun line(name: String) = Regex("<string name=\"$name\">([^<]+)</string>").find(xml)?.groupValues?.get(1)
            val chargers = line("mine_sent_chargers")
            val fuel = line("mine_sent_fuel")
            assertTrue("values$lang: $chargers", chargers != null && chargers != line("mine_sent_area"))
            assertTrue("values$lang: $fuel", fuel != null && "100 m" in fuel)
        }
    }
}
