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
     * Hosts the code names but never asks: links the user opens (a rating's page on Foursquare, a review's and a place's
     * reviews on Mangrove), XML namespaces, and the page that finds the phone's browsers for signing in to OpenStreetMap
     * (openInBrowser).
     */
    private val links = setOf(
        "afdc.energy.gov", "chargemap.com", "example.org", "foursquare.com", "mangrove.reviews", "map.openchargemap.io", "www.topografix.com", "www.w3.org",
    )

    /**
     * The addresses whose host is wholly built from a variable, each by its file and its line's own code, with what it is:
     * any other, in these files or another, shows up as a failure. A host whose first label alone is built
     * (`https://$lang.wikipedia.org`) is no exception: it reads as `*.wikipedia.org`.
     */
    private val builtHosts = mapOf(
        ("app/src/main/kotlin/app/atlas/ui/search/PlaceParts.kt" to "else -> \"https://\$t\"") to "a place's own website, opened in the browser",
        ("app/src/main/kotlin/app/atlas/ui/shop/ShopUi.kt" to "scheme == null -> \"https://\$t\"") to "a shop's own website, opened in the browser",
    )

    /** The one request among Wikipedia's built addresses: the reader's API, which must be read as `*.wikipedia.org`. */
    private val wikipediaRequest = "providers/src/main/kotlin/app/atlas/providers/Wikipedia.kt" to "\"https://\$lang.wikipedia.org/w/api.php\""

    /** What in the app's wiring asks each listed source: a client built, or the address it fetches. */
    private val askedBy = mapOf(
        "overpass" to "Overpass(http(Overpass.INFO.id), servers = overpassServers)", "nominatim" to "Nominatim(http(Nominatim.INFO.id))", "photon" to "Photon(http(Photon.INFO.id))", "mangrove" to "Mangrove(http(Mangrove.ID))",
        "foursquare" to "FoursquareRatings(http(FoursquareRatings.ID)", "yelp" to "YelpRatings(http(YelpRatings.ID)", "tripadvisor" to "TripAdvisorRatings(http(TripAdvisorRatings.ID)",
        "ocm" to "OpenChargeMap(http(OpenChargeMap.ID)", "osm-chargers" to "OsmChargers(overpass)", "ndw" to "NdwChargers(http(NdwChargers.ID)",
        "bnetza" to "BnetzaChargers(http(BnetzaChargers.ID)", "open-meteo" to "Weather(http(Weather.ID)",
        "met-norway" to "Weather(http(Weather.ID)", "wikidata" to "Wikidata(http(app.atlas.providers.Wikidata.ID))", "commons" to "Commons(http(app.atlas.providers.Commons.INFO.id))", "osm-notes" to "OsmNotes(http(app.atlas.providers.OsmNotes.ID))",
        "osm-edits" to "OsmEdits(http(app.atlas.providers.OsmEdits.ID)", "atlas-packs" to "WindowPacks(", "brouter" to "SegmentStore(dirs.segments",
        "panoramax" to "PanoramaxSource(", "open-prices" to "OpenPrices(", "iss-orbit" to "TleStore.SECOND_URL",
        "tankerkoenig" to "Tankerkoenig(", "bathing-water" to "BathingWaters(http(app.atlas.providers.BathingWaters.ID))", "wikipedia" to "Wikipedia(http(app.atlas.providers.Wikipedia.ID))",
        "openaddresses" to "OpenAddresses(http(OpenAddresses.ID))",
    )

    /** The written but unwired sources' clients, which the app must not build while no screen lists them. */
    private val unwiredClients = mapOf(
        "afdc" to "Afdc(", "chargemap" to "Chargemap(", "transitous" to "Transitous(",
    )

    private fun sources(modules: List<String>): List<File> = modules.flatMap { m ->
        File(android, "$m/src/main").walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
    }

    /**
     * An address in the code with a literal [host], [where] it is, and whether it stands as the `url` of a source's
     * declaration (`ProviderInfo(… url = "…")`): the source's website, which the code declares and never asks.
     */
    private data class Address(val host: String, val where: String, val declared: Boolean) {
        override fun toString() = "$where $host"
    }

    /** Every address with a literal host in the code of [files], comments left out, each where it stands. */
    private fun addressesIn(files: List<File>): List<Address> {
        val url = Regex("""https?://([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""")
        val out = ArrayList<Address>()
        for (f in files) {
            val lines = f.readLines()
            val declaration = declarationLines(lines)
            lines.forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@forEachIndexed
                val c = code.replace(Regex("""\s//\s.*$"""), "")
                for (m in url.findAll(c)) {
                    val declared = i in declaration && Regex("""(^|[\s,(])url = """" + Regex.escape(m.value)).containsMatchIn(c)
                    out += Address(m.groupValues[1], "${f.relativeTo(android)}:${i + 1}", declared)
                }
            }
        }
        return out
    }

    /** The indexes of [lines] inside a `ProviderInfo(…)` call, by its parentheses, what is in quotes skipped. */
    private fun declarationLines(lines: List<String>): Set<Int> {
        val out = HashSet<Int>()
        val text = lines.joinToString("\n")
        var from = 0
        while (true) {
            val start = text.indexOf("ProviderInfo(", from).takeIf { it >= 0 } ?: break
            var i = start + "ProviderInfo".length
            var depth = 0
            var quoted = false
            while (i < text.length) {
                val ch = text[i]
                if (quoted) {
                    if (ch == '\\') i++ else if (ch == '"') quoted = false
                } else when (ch) {
                    '"' -> quoted = true
                    '(' -> depth++
                    ')' -> if (--depth == 0) break
                }
                i++
            }
            val first = text.substring(0, start).count { it == '\n' }
            val last = text.substring(0, minOf(i, text.length)).count { it == '\n' }
            for (l in first..last) out += l
            from = i
        }
        return out
    }

    /**
     * Every address in the code of [files] whose host is built from a variable, comments left out, read as its host: a
     * first label that is a template as `*.rest` (`https://${wiki.first}.wikipedia.org` is `*.wikipedia.org`), a host that
     * is wholly one as `*`; each with where it is and its line's code.
     */
    private fun builtHostsIn(files: List<File>): List<Built> {
        val url = Regex("""https?://(?:\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*)((?:\.[A-Za-z0-9-]+)*)""")
        val out = ArrayList<Built>()
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@forEachIndexed
                for (m in url.findAll(code.replace(Regex("""\s//\s.*$"""), ""))) {
                    val rest = m.groupValues[1]
                    out += Built(if (rest.isEmpty()) "*" else "*$rest", "${f.relativeTo(android)}", i + 1, code)
                }
            }
        }
        return out
    }

    /** An address with a built host: its [host] as read (`*.wikipedia.org`, or `*`), its [file] and [line], and that line's [code]. */
    private data class Built(val host: String, val file: String, val line: Int, val code: String) {
        override fun toString() = "$file:$line $host"
    }

    /** Whether [token] is in the app's wiring (the app and the map's view), other than where its class is declared. */
    private fun wired(token: String): Boolean {
        val pattern = Regex("(?<!class )" + Regex.escape(token))
        return sources(listOf("app", "mapview")).any { f -> f.readLines().any { l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") && pattern.containsMatchIn(l) } }
    }

    @Test
    fun everyHostTheShippedCodeAsksIsAListedSources() {
        assertTrue("the Android sources at $android", File(android, "providers/src/main").isDirectory)
        // a host is listed as the gate asks: a source holds it, by its name or below its pattern
        val listed = SourcesModel.ALL + Catalogue.NOT_WIRED
        // a source's website is excused only where the code declares it so, as the `url` of its declaration: named
        // anywhere else (a client's base, a request) it is asked, and must be listed
        val websites = (SourcesModel.ALL + Catalogue.NOT_WIRED + Catalogue.BUNDLED).mapNotNull { java.net.URI(it.url).host }.toSet()
        val unknown = addressesIn(sources(shipped)).filter { a -> !(a.declared && a.host in websites) && a.host !in links && listed.none { it.holds(a.host) } }
        assertTrue("hosts the code names that no source lists: $unknown", unknown.isEmpty())
    }

    @Test
    fun aHostBuiltFromAVariableIsHeldToAListedPattern() {
        val built = builtHostsIn(sources(shipped))
        val patterns = SourcesModel.ALL.flatMap { it.hosts }.filter { it.startsWith("*.") }
        for (b in built.filter { it.host != "*" }) {
            assertTrue("$b: no listed pattern holds it", patterns.any { p -> app.atlas.providers.hostHolds(p, b.host) })
        }
        // a host wholly built from a variable only where it is named, by its line's own code: one a line
        val whole = built.filter { it.host == "*" }.map { it.file to it.code }
        assertEquals("hosts wholly built from a variable: $whole", builtHosts.keys.toList().sortedBy { it.first }, whole.sortedBy { it.first })
        // Wikipedia's one request is read, and held to Wikipedia's pattern: the links for the browser are not what counts
        val request = built.filter { it.file == wikipediaRequest.first && wikipediaRequest.second in it.code }
        assertEquals("Wikipedia's request, read as *.wikipedia.org: $built", listOf("*.wikipedia.org"), request.map { it.host })
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

    /**
     * Real host names a listed host stands for: an exact host itself, and for a pattern (`*.wikipedia.org`) its name and
     * one host below it, so a switch is asked as the app's requests ask it, never with the listed string.
     */
    private fun namesFor(listed: String): List<String> =
        if (listed.startsWith("*.")) listed.removePrefix("*.").let { listOf(it, "en.$it") } else listOf(listed)

    @Test
    fun aSwitchHoldsBackExactlyItsSourcesHosts() {
        val owners = SourcesModel.ALL.filter { it.switchable && it.via == null }
        val all = SourcesModel.ALL.map { it.id }.toSet()
        // no host held back by two switches, asked by matching: each name a source stands for is held by it alone
        for (s in owners) for (h in s.hosts) for (name in namesFor(h)) {
            val holders = owners.filter { SourcesModel.blockedBy(name, setOf(it.id)) == it.id }.map { it.id }
            assertEquals("$name ($h) is held back by ${s.id} alone", listOf(s.id), holders)
        }
        for (s in owners) {
            for (h in s.hosts) for (name in namesFor(h)) {
                assertEquals("${s.id} holds back $name", s.id, SourcesModel.blockedBy(name, setOf(s.id)))
                assertNull("$name goes while ${s.id} is on", SourcesModel.blockedBy(name, all - s.id))
            }
        }
        assertNull(SourcesModel.blockedBy("overpass-api.de", setOf("osm-chargers")))
        assertEquals("overpass", SourcesModel.blockedBy("overpass-api.de", setOf("overpass")))
        assertNull(SourcesModel.blockedBy("api.openstreetmap.org", setOf("osm-notes", "osm-edits")))
        assertNull(SourcesModel.blockedBy("example.org", all))
    }

    @Test
    fun aRequestsSourceIsTheOneThatListsItsHostElseTheViewsName() {
        val all = SourcesModel.ALL.map { it.id }.toSet()
        // a listed host: its own source's switch, whatever the view is named
        assertNull(SourcesModel.blockedBy("api.met.no", setOf("open-meteo"), named = "open-meteo"))
        assertEquals("met-norway", SourcesModel.blockedBy("api.met.no", setOf("met-norway"), named = "open-meteo"))
        assertEquals("overpass", SourcesModel.blockedBy("overpass-api.de", setOf("overpass"), named = "wikidata"))
        // a host in no list: the view's source's switch
        assertEquals("panoramax", SourcesModel.blockedBy("nl.panoramax.xyz", setOf("panoramax"), named = "panoramax"))
        assertNull(SourcesModel.blockedBy("nl.panoramax.xyz", all - "panoramax", named = "panoramax"))
        assertEquals("atlas-packs", SourcesModel.blockedBy("objects.githubusercontent.com", setOf("atlas-packs"), named = "atlas-packs"))
        // neither a list nor a name: it goes, as the tests' mock servers do; a name no source has is none
        assertNull(SourcesModel.blockedBy("localhost", all))
        assertNull(SourcesModel.blockedBy("localhost", all, named = "no-such-source"))
        // a source with no switch, or asked through another's, is never held by its name: held where it is asked
        assertNull(SourcesModel.blockedBy("example.net", all, named = "osm-notes"))
        assertNull(SourcesModel.blockedBy("example.net", setOf("osm-chargers"), named = "osm-chargers"))
        assertNull(SourcesModel.blockedBy("overpass-api.de", setOf("osm-chargers"), named = "osm-chargers"))
        // a host a switch holds is listed by no other source but one asked through it, so a view's name never lets it go
        val owners = SourcesModel.ALL.filter { it.switchable && it.via == null }
        for (o in owners) for (h in o.hosts) for (name in namesFor(h)) {
            val others = SourcesModel.ALL.filter { it.id != o.id && it.via != o.id && it.holds(name) }.map { it.id }
            assertEquals("$name is ${o.id}'s and listed by $others", emptyList<String>(), others)
            for (n in SourcesModel.ALL) assertEquals("$name through ${n.id}'s view", o.id, SourcesModel.blockedBy(name, setOf(o.id), named = n.id))
        }
    }

    @Test
    fun aSourceWithoutASwitchIsHeldBackOnlyWhereASwitchableSourceListsThatVeryName() {
        // every switch off: the hosts of a source without a switch (OpenStreetMap's notes, edits and sign-in), or of one
        // asked through another's servers (the chargers, through Overpass's), are held back only where a switchable
        // source lists that very name, never by a pattern that happens to reach them
        val switchable = SourcesModel.ALL.filter { it.switchable && it.via == null }
        val allOff = switchable.map { it.id }.toSet()
        for (s in SourcesModel.ALL.filter { !it.switchable || it.via != null }) for (h in s.hosts) for (name in namesFor(h)) {
            val listing = switchable.firstOrNull { o -> h in o.hosts }?.id
            assertEquals("${s.id}'s $name", listing, SourcesModel.blockedBy(name, allOff))
        }
        assertEquals("overpass", SourcesModel.blockedBy("overpass-api.de", allOff))
        assertNull(SourcesModel.blockedBy("api.openstreetmap.org", allOff))
    }

    @Test
    fun aPatternHoldsItsNameAndEveryHostBelowIt() {
        val wiki = setOf("wikipedia")
        // Wikipedia's own hosts, as its reader asks them (one per language) and as its links name them
        for (h in listOf("en.wikipedia.org", "nds-nl.wikipedia.org", "en.m.wikipedia.org", "wikipedia.org", "www.wikipedia.org", "EN.Wikipedia.ORG", "en.wikipedia.org.")) {
            assertEquals(h, "wikipedia", SourcesModel.blockedBy(h, wiki))
        }
        // a host that only ends in the same letters, or holds the name further in, is another's
        for (h in listOf("wikipedia.org.example.com", "xwikipedia.org", "en.xwikipedia.org", "wikipedia.org..", "wikipedia.com", "org", "")) {
            assertNull(h, SourcesModel.blockedBy(h, wiki))
        }
        // exact hosts are compared in lower case and without one trailing dot, and hold nothing below them
        val packs = setOf("atlas-packs")
        for (h in listOf("github.com", "GitHub.com", "github.com.")) assertEquals(h, "atlas-packs", SourcesModel.blockedBy(h, packs))
        for (h in listOf("api.github.com", "github.com..", "xgithub.com")) assertNull(h, SourcesModel.blockedBy(h, packs))
        // a star anywhere but as the whole first label is no pattern
        val odd = listOf("en.*.org", "w*.org", "*wikipedia.org", "*.*.org").map { app.atlas.providers.ProviderInfo("odd-$it", "Odd", "x", "x", "https://example.org", hosts = listOf(it)) }
        for (o in odd) for (h in listOf("en.wikipedia.org", "en.x.org", "wikipedia.org", "a.b.org")) assertNull("${o.hosts} holds $h", SourcesModel.blockedBy(h, setOf(o.id), odd))
    }

    @Test
    fun aListedHostIsAHostOrAPatternOfItsFirstLabel() {
        val name = Regex("""^(\*\.)?[a-z0-9-]+(\.[a-z0-9-]+)+$""")
        for (s in SourcesModel.ALL + Catalogue.NOT_WIRED + Catalogue.BUNDLED) for (h in s.hosts) {
            assertTrue("${s.id} lists \"$h\": a host in lower case, or `*.` and one", name.matches(h))
        }
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
