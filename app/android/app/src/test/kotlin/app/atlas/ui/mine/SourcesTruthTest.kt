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
     * Hosts the code names that are nobody's address, excused wherever they stand: XML namespaces, and the page that
     * finds the phone's browsers for signing in to OpenStreetMap (openInBrowser).
     */
    private val links = setOf("example.org", "www.topografix.com", "www.w3.org")

    /**
     * The pages the user opens in the browser, each by its file and its line's own code, with what it is: a page on a
     * source's own website is excused there and nowhere else, so a request to that host is asked, and must be listed.
     */
    private val pages = mapOf(
        ("providers/src/main/kotlin/app/atlas/providers/KeyedRatings.kt" to "url = fsqId?.let { \"https://foursquare.com/v/\$it\" }") to "a venue's page on Foursquare",
        ("providers/src/main/kotlin/app/atlas/providers/Mangrove.kt" to "url = \"https://mangrove.reviews/list?signature=\$signature\"") to "a review's page on Mangrove",
        ("providers/src/main/kotlin/app/atlas/providers/Mangrove.kt" to "\"https://mangrove.reviews/list?sub=\" + GeoSubject") to "a place's reviews on Mangrove",
        ("providers/src/main/kotlin/app/atlas/providers/OpenChargeMap.kt" to "extra[\"url\"] = \"https://map.openchargemap.io/?id=\$id\"") to "a charger's page on Open Charge Map",
        ("providers/src/main/kotlin/app/atlas/providers/Afdc.kt" to "extra[\"url\"] = \"https://afdc.energy.gov/stations#/station/\$id\"") to "a station's page at the AFDC",
        ("app/src/main/kotlin/app/atlas/ui/mine/charge/ChargeScreen.kt" to "val url = \"https://chargemap.com/map?lat=") to "Chargemap's map at a charger",
    )

    /**
     * The addresses whose host is built from a variable other than as its first label, each by its file and its line's
     * own code, with what it is: a host wholly built (`https://$t`), or with a template in a later label
     * (`https://api.$region.example.net`). Any other such address fails. A host whose first label alone is built
     * (`https://$lang.wikipedia.org`) is no exception: it reads as `*.wikipedia.org`, and a listed pattern must hold it.
     *
     * What the scans read: an address written on one line with `http://` or `https://` in lower case and a host of two
     * labels or more. What they do not: a host joined at run time (`"https://" + x`, `buildString`), built by
     * `HttpUrl.Builder().host(x)`, `URI(scheme, host, …)`, `URL(proto, host, …)` or `String.format`, held in a resource,
     * written across lines, with its scheme in capitals or from a template (`"$scheme://host"`), or a host of one label.
     * Nor, the largest class, the addresses that come from answers and files: Panoramax's `imageUrl`, Commons'
     * `thumbUrl`, the `url` fields of world.json and terrain.json, the packs' addresses in their lists, a redirect's
     * `Location`. The gate holds one of those by its view's source only where it is a first request, to a host no list
     * names, through a view whose source has a switch of its own; a redirect's hop to such a host goes, a listed host is
     * held by its own source, and a view of a source without a switch, or asked through another's, holds nothing.
     */
    private val builtHosts = mapOf(
        ("app/src/main/kotlin/app/atlas/ui/search/PlaceParts.kt" to "else -> \"https://\$t\"") to "a place's own website, opened in the browser",
        ("app/src/main/kotlin/app/atlas/ui/shop/ShopUi.kt" to "scheme == null -> \"https://\$t\"") to "a shop's own website, opened in the browser",
    )

    /**
     * The only shipped files that may make an HTTP client or open a connection of their own, each with why. Every
     * other request is made on a view of `providers/Http.kt`'s one client, through its gate.
     */
    private val ownConnections = mapOf(
        "providers/src/main/kotlin/app/atlas/providers/Http.kt" to "the one client, whose gate every request passes",
        "render/src/main/kotlin/app/atlas/render/live/PackLibrary.kt" to
            "the engine's `UrlGet`, a default the app never takes: MapData hands `PackLibrary` its gated client's `get`",
        "render/src/main/kotlin/app/atlas/render/data/Overpass.kt" to
            "the engine's `HttpUrlPost`, a default the app never takes: MapData hands `OverpassClient` its gated client's `post`; the packer's own",
    )

    /**
     * Making a client or opening a connection, by the spellings this test knows: OkHttp's client and builder, the JDK's
     * connections, streams, sockets and socket factories (TLS's too, and anything under `javax.net`), Java's own
     * `HttpClient`, Kotlin's `URL(…).readText()` and `.readBytes()`; Android's fetchers (`DownloadManager`, `WebView`,
     * `MediaPlayer`); and what sheds a view's gate from a client built on it: a [app.atlas.providers.SourceTag] set by
     * hand, a lookup, a proxy, a proxy selector or a socket factory of its own, and interceptors cleared or added. It
     * knows spellings, not every way to open a connection: a reference held in a variable (`val u = URL(x); u.readText()`),
     * a reflective call, a library that fetches by itself, or native code is not read.
     */
    private val opens = Regex(
        """\bOkHttpClient\s*\(|\bOkHttpClient\.Builder\s*\(|\.openConnection\s*\(|\.openStream\s*\(|URL::openConnection|""" +
            """\bjava\.net\.(Socket|ServerSocket|DatagramSocket|http)\b|\bSocketChannel\b|\b(SSL)?SocketFactory\b|\bjavax\.net\.|""" +
            """\bHttpClient\.new|\bURL\s*\([^)]*\)\s*\.read(Text|Bytes)\s*\(|""" +
            """\bSourceTag\s*\(|\.(dns|proxy|proxySelector|socketFactory|sslSocketFactory)\s*\(|""" +
            """\b(networkI|i)nterceptors\s*\(\s*\)|\.add(Network)?Interceptor\s*\(|""" +
            """\bDownloadManager\b|\bWebView\b|\bMediaPlayer\b""",
    )

    /**
     * The lines outside [ownConnections] that may hold one of [opens]' spellings, each by its file and the line's own
     * code, with why: a source stamped by hand where a view would stamp another.
     */
    private val pinnedOpens = mapOf(
        ("providers/src/main/kotlin/app/atlas/providers/Overpass.kt" to ".apply { if (source != null) tag(SourceTag::class.java, SourceTag(source)) }") to
            "a query asked through Overpass's view for another source that Overpass's servers answer: the chargers' (OsmChargers)",
    )

    /** OkHttp's builder written across two lines (`OkHttpClient` on one, `.Builder(` on the next), read in the whole text. */
    private val opensAcross = Regex("""\bOkHttpClient\s*\n\s*\.\s*Builder\s*\(|\bOkHttpClient\s*\.\s*\n\s*Builder\s*\(""")

    /** The one request among Wikipedia's built addresses: the reader's API, which must be read as `*.wikipedia.org`. */
    private val wikipediaRequest = "providers/src/main/kotlin/app/atlas/providers/Wikipedia.kt" to "\"https://\$lang.wikipedia.org/w/api.php\""

    /** What in the app's wiring asks each listed source: a client built, or the address it fetches. */
    private val askedBy = mapOf(
        "overpass" to "Overpass(http(Overpass.INFO.id), endpoints = config.overpassEndpoints ?: Overpass.ENDPOINTS, servers = overpassServers)", "nominatim" to "Nominatim(http(Nominatim.INFO.id))", "photon" to "Photon(http(Photon.INFO.id))", "mangrove" to "Mangrove(http(Mangrove.ID))",
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

    /**
     * Every view of the one client the shipped code asks for ([app.atlas.Services.http]), by its file and the source's
     * id, as many times as it is asked for there. A view's name decides for a host no list names (Panoramax's instances)
     * and is what the Privacy screen counts under, so a wrong id at a caller is a fault nothing else sees: a new call, or
     * another id, fails until this table names it.
     */
    private val views = mapOf(
        "app/src/main/kotlin/app/atlas/Services.kt" to listOf(
            "overpass", "open-meteo", "photon", "nominatim", "mangrove", "openaddresses", "foursquare", "yelp", "tripadvisor",
            "ocm", "ndw", "bnetza", "wikidata", "bathing-water", "wikipedia", "commons", "osm-notes", "osm-edits", "osm-edits",
            "osm-edits", "brouter",
        ),
        "app/src/main/kotlin/app/atlas/services/MapData.kt" to listOf(
            "overpass", "atlas-packs", "atlas-packs", "atlas-packs", "atlas-packs", "atlas-packs", "overpass", "atlas-packs",
            "atlas-packs",
        ),
        "app/src/main/kotlin/app/atlas/ui/local/LocalHub.kt" to listOf("wikidata", "iss-orbit", "iss-orbit"),
        "app/src/main/kotlin/app/atlas/ui/local/StreetPhotos.kt" to listOf("panoramax"),
        "app/src/main/kotlin/app/atlas/ui/mine/ForecastScreen.kt" to listOf("open-meteo"),
        "app/src/main/kotlin/app/atlas/ui/mine/MineHub.kt" to listOf("atlas-packs", "atlas-packs"),
        "app/src/main/kotlin/app/atlas/ui/mine/pump/PumpScreen.kt" to listOf("tankerkoenig"),
        "app/src/main/kotlin/app/atlas/ui/search/KeptImages.kt" to listOf("commons"),
        "app/src/main/kotlin/app/atlas/ui/search/Reviews.kt" to listOf("mangrove", "mangrove"),
        "app/src/main/kotlin/app/atlas/ui/shop/ShopRepository.kt" to listOf("open-prices"),
    )

    /** The id each way the shipped code writes one names, read from the classes themselves. */
    private val viewIds: Map<String, String> = mapOf(
        "Overpass.INFO.id" to app.atlas.providers.Overpass.INFO.id, "Weather.ID" to app.atlas.providers.Weather.ID,
        "Photon.INFO.id" to app.atlas.providers.Photon.INFO.id, "Nominatim.INFO.id" to app.atlas.providers.Nominatim.INFO.id,
        "Mangrove.ID" to app.atlas.providers.Mangrove.ID, "OpenAddresses.ID" to app.atlas.providers.OpenAddresses.ID,
        "FoursquareRatings.ID" to app.atlas.providers.FoursquareRatings.ID, "YelpRatings.ID" to app.atlas.providers.YelpRatings.ID,
        "TripAdvisorRatings.ID" to app.atlas.providers.TripAdvisorRatings.ID, "OpenChargeMap.ID" to app.atlas.providers.OpenChargeMap.ID,
        "NdwChargers.ID" to app.atlas.providers.NdwChargers.ID, "BnetzaChargers.ID" to app.atlas.providers.BnetzaChargers.ID,
        "Wikidata.ID" to app.atlas.providers.Wikidata.ID, "BathingWaters.ID" to app.atlas.providers.BathingWaters.ID,
        "Wikipedia.ID" to app.atlas.providers.Wikipedia.ID, "Commons.INFO.id" to app.atlas.providers.Commons.INFO.id,
        "OsmNotes.ID" to app.atlas.providers.OsmNotes.ID, "OsmEdits.ID" to app.atlas.providers.OsmEdits.ID,
        "SourcesModel.ATLAS_PACKS.id" to SourcesModel.ATLAS_PACKS.id, "SourcesModel.BROUTER.id" to SourcesModel.BROUTER.id,
        "SourcesModel.PANORAMAX.id" to SourcesModel.PANORAMAX.id, "SourcesModel.OPEN_PRICES.id" to SourcesModel.OPEN_PRICES.id,
        "SourcesModel.ISS_ORBIT.id" to SourcesModel.ISS_ORBIT.id, "SourcesModel.TANKERKOENIG.id" to SourcesModel.TANKERKOENIG.id,
    )

    private fun sources(modules: List<String>): List<File> = modules.flatMap { m ->
        File(android, "$m/src/main").walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }.toList()
    }

    /**
     * An address in the code with a literal [host], [where] it is, and whether it stands as the `url` of a source's
     * declaration (`ProviderInfo(… url = "…")`): the source's website, which the code declares and never asks.
     */
    private data class Address(val host: String, val where: String, val declared: Boolean, val file: String = "", val code: String = "") {
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
                    out += Address(m.groupValues[1], "${f.relativeTo(android)}:${i + 1}", declared, "${f.relativeTo(android)}", c)
                }
            }
        }
        return out
    }

    /**
     * The indexes of [lines] inside a `ProviderInfo(…)` call, by its parentheses, what is in quotes skipped, and comment
     * lines read as empty, as the scan reads them (a `"` or a `(` in a comment would run the call on).
     */
    private fun declarationLines(lines: List<String>): Set<Int> {
        val out = HashSet<Int>()
        val text = lines.joinToString("\n") { l -> l.trim().let { t -> if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) "" else l } }
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
        // the host: labels of letters, digits and dashes, and templates, up to the first character that ends a host
        val template = """(?:\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*)"""
        val url = Regex("""https?://((?:[A-Za-z0-9-]|$template)+(?:\.(?:[A-Za-z0-9-]|$template)+)*)""")
        val first = Regex("""^$template((?:\.[A-Za-z0-9-]+)*)$""")
        val out = ArrayList<Built>()
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.trim()
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@forEachIndexed
                for (m in url.findAll(code.replace(Regex("""\s//\s.*$"""), ""))) {
                    val host = m.groupValues[1]
                    if ('$' !in host) continue
                    // the first label alone built: `*.rest`; wholly built, or a template further in: `*`, pinned by its line
                    val read = first.matchEntire(host)?.groupValues?.get(1)?.let { rest -> if (rest.isEmpty()) "*" else "*$rest" } ?: "*"
                    out += Built(read, "${f.relativeTo(android)}", i + 1, code)
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
        // a host is listed as the gate asks: a source holds it, by its name or below its pattern; an unwired source's
        // only in the file that declares it, its own client's, as no switch holds it and no screen names it
        val listed = SourcesModel.ALL
        val unwired = Catalogue.NOT_WIRED.associateWith { s ->
            sources(listOf("providers")).filter { f -> f.readText().let { t -> "ProviderInfo(" in t && "const val ID = \"${s.id}\"" in t && s.hosts.all { "\"$it\"" in t } } }
                .map { "${it.relativeTo(android)}" }.single()
        }
        // a source's website is excused only where the code declares it so, as the `url` of its declaration: named
        // anywhere else (a client's base, a request) it is asked, and must be listed
        val websites = (SourcesModel.ALL + Catalogue.NOT_WIRED + Catalogue.BUNDLED).mapNotNull { java.net.URI(it.url).host }.toSet()
        val addresses = addressesIn(sources(shipped))
        fun page(a: Address) = pages.keys.any { (file, code) -> a.file == file && code in a.code }
        val unknown = addresses.filter { a -> !(a.declared && a.host in websites) && a.host !in links && !page(a) && listed.none { it.holds(a.host) } &&
            unwired.none { (s, file) -> a.file == file && s.holds(a.host) } }
        assertTrue("hosts the code names that no source lists: $unknown", unknown.isEmpty())
        // every page pinned is one address the code still names: a pin cannot outlive its line
        for ((file, code) in pages.keys) {
            val at = addresses.filter { it.file == file && code in it.code }
            assertEquals("the page $file: $code", 1, at.size)
        }
    }

    @Test
    fun aHostBuiltFromAVariableIsHeldToAListedPattern() {
        val built = builtHostsIn(sources(shipped))
        val patterns = SourcesModel.ALL.flatMap { it.hosts }.filter { it.startsWith("*.") }
        for (b in built.filter { it.host != "*" }) {
            assertTrue("$b: no listed pattern holds it", patterns.any { p -> app.atlas.providers.hostHolds(p, b.host) })
        }
        // a host built other than by its first label only where it is named, by its line's own code: one a line
        val whole = built.filter { it.host == "*" }.map { it.file to it.code }
        val order = compareBy<Pair<String, String>>({ it.first }, { it.second })
        assertEquals("hosts built other than by their first label: $whole", builtHosts.keys.toList().sortedWith(order), whole.sortedWith(order))
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
        assertTrue(SourcesModel.ALL.none { s -> s.hosts.any { h -> pages.keys.any { (_, code) -> "://$h/" in code } } })
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
    fun aSourceWithoutASwitchIsHeldBackByNoneAndOneAskedThroughAnothersByThatOneAlone() {
        // every switch off: a host of a source without a switch (OpenStreetMap's notes, edits and sign-in) is held back by
        // no source; a host of one asked through another's servers (the chargers, through Overpass's) by that other alone
        val switchable = SourcesModel.ALL.filter { it.switchable && it.via == null }
        val allOff = switchable.map { it.id }.toSet()
        for (s in SourcesModel.ALL.filter { !it.switchable || it.via != null }) for (h in s.hosts) for (name in namesFor(h)) {
            val holder = if (!s.switchable) null else s.via
            assertEquals("${s.id}'s $name", holder, SourcesModel.blockedBy(name, allOff))
            for (o in switchable.filter { it.id != holder }) assertEquals("${s.id}'s $name with ${o.id} alone off", null, SourcesModel.blockedBy(name, setOf(o.id)))
        }
        assertEquals("overpass", SourcesModel.blockedBy("overpass-api.de", allOff))
        assertNull(SourcesModel.blockedBy("api.openstreetmap.org", allOff))
    }

    @Test
    fun aHostOnlyASourceWithoutASwitchListsGoesThroughAnyViewWhateverIsOff() {
        // OpenStreetMap's sign-in, notes and edits: listed, so no view's name decides for them, even a view switched off
        val switchable = SourcesModel.ALL.filter { it.switchable && it.via == null }
        val allOff = switchable.map { it.id }.toSet()
        val unswitched = SourcesModel.ALL.filter { !it.switchable }
        assertTrue(unswitched.isNotEmpty())
        for (s in unswitched) for (h in s.hosts) for (name in namesFor(h)) for (v in switchable) {
            assertNull("${s.id}'s $name through ${v.id}'s view, off", SourcesModel.blockedBy(name, allOff, named = v.id))
            assertNull("${s.id}'s $name through ${v.id}'s view, alone off", SourcesModel.blockedBy(name, setOf(v.id), named = v.id))
        }
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

    @Test
    fun everyViewTheShippedCodeAsksForIsNamedByItsSourcesIdAndPinned() {
        // `http(` with any receiver, its argument up to the matching parenthesis; comment lines left out
        val call = Regex("""(?<![\w])http\(""")
        val found = HashMap<String, MutableList<String>>()
        val unread = ArrayList<String>()
        for (f in sources(listOf("app"))) {
            val path = f.relativeTo(android).path
            for ((i, line) in f.readLines().withIndex()) {
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue
                for (m in call.findAll(line)) {
                    if (line.substring(0, m.range.first).trimEnd().endsWith("fun")) continue
                    var depth = 1
                    var j = m.range.last + 1
                    while (j < line.length && depth > 0) { if (line[j] == '(') depth++ else if (line[j] == ')') depth--; j++ }
                    val arg = line.substring(m.range.last + 1, j - 1).trim().removePrefix("app.atlas.providers.").removePrefix("app.atlas.ui.mine.")
                    val id = viewIds[arg]
                    if (id == null) unread += "$path:${i + 1} http($arg)" else found.getOrPut(path) { ArrayList() } += id
                }
            }
        }
        assertTrue("views asked for by a name this test does not read, or by none: $unread", unread.isEmpty())
        val ids = SourcesModel.ALL.map { it.id }.toSet()
        assertEquals("ids in no list", emptyList<String>(), found.values.flatten().filter { it !in ids })
        assertEquals("the views each file asks for", views.mapValues { it.value.sorted() }.toSortedMap(), found.mapValues { it.value.sorted() }.toSortedMap())
    }

    @Test
    fun noShippedCodeMakesAClientOrOpensAConnectionButTheNamedFiles() {
        val modules = shipped + "brouter"
        val found = modules.flatMap { m ->
            File(android, "$m/src/main").walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .flatMap { f ->
                    val path = f.relativeTo(android).path
                    val lines = f.readLines().map { l -> l.trim().let { t -> if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else l } }
                    val across = opensAcross.findAll(lines.joinToString("\n")).map { m -> path to lines.joinToString("\n").substring(0, m.range.first).count { it == '\n' } + 1 }
                    lines.withIndex().filter { (_, l) -> opens.containsMatchIn(l) && pinnedOpens.keys.none { (file, code) -> file == path && code in l } }
                        .map { (i, _) -> path to i + 1 } + across
                }.toList()
        }
        assertTrue("the shipped sources at $android", found.isNotEmpty())
        val elsewhere = found.filter { it.first !in ownConnections }.map { (f, l) -> "$f:$l" }
        assertTrue("these make a client or open a connection outside the gate: $elsewhere", elsewhere.isEmpty())
        val stale = ownConnections.keys.filter { k -> found.none { it.first == k } }
        assertTrue("these are named as opening their own but no longer do: $stale", stale.isEmpty())
        val stalePins = pinnedOpens.keys.filter { (file, code) -> File(android, file).readLines().count { code in it } != 1 }
        assertTrue("these pinned lines are not in their files once: $stalePins", stalePins.isEmpty())
    }
}
