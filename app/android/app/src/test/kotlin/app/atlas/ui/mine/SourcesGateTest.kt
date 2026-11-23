package app.atlas.ui.mine

import app.atlas.Services
import app.atlas.providers.Http
import app.atlas.providers.SwitchedOffException
import app.atlas.providers.Wikipedia
import app.atlas.services.SettingsKeys
import app.atlas.testing.direct
import app.atlas.testing.testServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Collections

/**
 * The switches through the gate as [Services] wires it, asked with the host names the app's requests carry: a source
 * switched off is never asked, no name looked up and no socket opened, and switched on again it goes (to a mock here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourcesGateTest {
    /**
     * A proxy of the JVM's, set where the tests run, would carry a switched-on request out past the test's own [Dns]: every
     * test here runs with one set to a port nobody listens on, so a client that is not [app.atlas.testing.direct] fails.
     */
    private val proxyKeys = listOf("http.proxyHost", "https.proxyHost", "socksProxyHost").associateWith { it.replace("Host", "Port") }

    /** Hosts the JVM would send past a proxy: emptied, so a setting from outside (`*`) cannot skip the dead proxy. */
    private val bypassKeys = listOf("http.nonProxyHosts", "socksNonProxyHosts")
    private val kept = HashMap<String, String?>()

    @org.junit.Before
    fun aProxyThatGoesNowhere() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        for ((host, portKey) in proxyKeys) {
            kept[host] = System.getProperty(host)
            kept[portKey] = System.getProperty(portKey)
            System.setProperty(host, "127.0.0.1")
            System.setProperty(portKey, "$port")
        }
        for (k in bypassKeys) {
            kept[k] = System.getProperty(k)
            System.setProperty(k, "")
        }
    }

    @org.junit.After
    fun theProxyAsItWas() {
        for ((k, v) in kept) if (v == null) System.clearProperty(k) else System.setProperty(k, v)
    }

    /** What a client asked of the names and the sockets: nothing leaves this machine, whatever the gate does. */
    private class Watch(private val here: (String) -> Boolean = { it.endsWith(".wikipedia.org") }) : EventListener() {
        val looked: MutableList<String> = Collections.synchronizedList(ArrayList())
        val connects: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** Every name [here] holds (Wikipedia's hosts, by default) is this machine, where the mock listens; any other is not found. */
        val dns = Dns { name ->
            looked += name
            if (here(name)) listOf(InetAddress.getByAddress(name, byteArrayOf(127, 0, 0, 1))) else throw UnknownHostException(name)
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects += "$inetSocketAddress"
        }
    }

    /** The app's view of its HTTP client for Wikipedia ([Services.http]), its gate and all, with [watch]'s names and sockets, and no proxy of the JVM's. */
    private fun watched(services: Services, watch: Watch): Http =
        Http(base = services.http(Wikipedia.ID).client.newBuilder().direct().dns(watch.dns).eventListener(watch).build())

    private suspend fun Services.switch(off: Set<String>) {
        settings.set(SettingsKeys.PROVIDERS_OFF, off.takeIf { it.isNotEmpty() })
        withTimeout(5_000) { while (sourcesOff() != off) delay(10) }
    }

    private val page = """{"batchcomplete":true,"query":{"pages":[{"pageid":1,"ns":0,"title":"Westerkerk","extract":"The Westerkerk is a church in Amsterdam.","fullurl":"https://en.wikipedia.org/wiki/Westerkerk"}]}}"""

    @Test
    fun wikipediaSwitchedOffIsNeverAskedAndSwitchedOnItGoes() = runBlocking {
        val services = testServices()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body(page).build())
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val watch = Watch()
            val http = watched(services, watch)
            services.switch(setOf("wikipedia"))
            // the reader's own address, en.wikipedia.org, and one in another language
            for (wiki in listOf(Wikipedia(http), Wikipedia(http) { l -> "http://$l.wikipedia.org:${server.port}/w/api.php" })) {
                for (lang in listOf("en", "nds-nl")) {
                    val refused = runCatching { wiki.article(lang, "Westerkerk") }.exceptionOrNull()
                    assertTrue("$lang: $refused", refused is SwitchedOffException && refused.source == "wikipedia" && refused.host == "$lang.wikipedia.org")
                }
            }
            assertEquals("no name looked up", emptyList<String>(), watch.looked.toList())
            assertEquals("no socket opened", emptyList<String>(), watch.connects.toList())
            assertEquals("nothing was sent", 0, server.requestCount)
            // switched on again: it goes, by its own name, to the mock
            services.switch(emptySet())
            val article = Wikipedia(http) { l -> "http://$l.wikipedia.org:${server.port}/w/api.php" }.article("en", "Westerkerk")
            assertEquals("Westerkerk", article?.title)
            assertEquals(listOf("en.wikipedia.org"), watch.looked.toList())
            assertEquals(1, server.requestCount)
            assertEquals("en.wikipedia.org:${server.port}", server.takeRequest().headers["Host"])
        }
        services.close()
    }

    @Test
    fun anArticleReadThisRunIsReadAgainWithWikipediaOffAndNothingIsSent() = runBlocking {
        val services = testServices()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body(page).build())
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val watch = Watch()
            val wiki = Wikipedia(watched(services, watch)) { l -> "http://$l.wikipedia.org:${server.port}/w/api.php" }
            services.switch(emptySet())
            val read = wiki.article("en", "Westerkerk")
            assertEquals(1, server.requestCount)
            services.switch(setOf("wikipedia"))
            // the reader asks article() again: it answers from what this run read, before it asks
            assertEquals(read, wiki.article("en", "Westerkerk"))
            assertEquals("nothing more was sent", 1, server.requestCount)
            assertEquals(listOf("en.wikipedia.org"), watch.looked.toList())
            assertEquals(1, watch.connects.size)
            // another article is not read: it would be asked
            assertTrue(runCatching { wiki.article("en", "Noorderkerk") }.exceptionOrNull() is SwitchedOffException)
            assertEquals(1, server.requestCount)
        }
        services.close()
    }

    @Test
    fun aNamedViewIsHeldByItsSourcesSwitchWhereNoListNamesTheHostAndAListedHostByItsOwn() = runBlocking {
        val services = testServices()
        MockWebServer().use { server ->
            repeat(8) { server.enqueue(MockResponse.Builder().code(200).body("ok").build()) }
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val watch = Watch { true }
            fun OkHttpClient.watched() = newBuilder().direct().dns(watch.dns).eventListener(watch).build()
            fun at(host: String) = "http://$host:${server.port}/x"
            suspend fun ask(client: OkHttpClient, host: String): Throwable? =
                runCatching { client.newCall(okhttp3.Request.Builder().url(at(host)).build()).execute().close() }.exceptionOrNull()
            services.switch(setOf("panoramax", "open-meteo", "atlas-packs", "overpass", "osm-chargers"))
            // Panoramax's picture from its instance's own host, which no list names: held by Panoramax's switch
            val refused = ask(services.http("panoramax").client.watched(), "nl.panoramax.xyz")
            assertTrue("$refused", refused is SwitchedOffException && refused.source == "panoramax" && refused.host == "nl.panoramax.xyz")
            // the map's long-read Overpass client, built on the overpass view as MapData builds it, at a server in no list
            val long = services.http("overpass").client.newBuilder().cache(null).callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(330, java.util.concurrent.TimeUnit.SECONDS).build().watched()
            assertEquals("overpass", (ask(long, "overpass.example.net") as? SwitchedOffException)?.source)
            // a pack's download from a host in no list (GitHub's storage under another name)
            assertEquals("atlas-packs", (ask(services.http("atlas-packs").client.watched(), "objects.githubusercontent.com") as? SwitchedOffException)?.source)
            // a listed host goes by its own source's switch, whatever the view is named: MET Norway through the forecast's
            // view with Open-Meteo off, and Overpass's own host is held through any view
            assertEquals(null, ask(services.http("open-meteo").client.watched(), "api.met.no"))
            assertEquals("overpass", (ask(services.http("wikidata").client.watched(), "overpass-api.de") as? SwitchedOffException)?.source)
            assertEquals("open-meteo", (ask(services.http("met-norway").client.watched(), "api.open-meteo.com") as? SwitchedOffException)?.source)
            // a host in no list through a view of a source that is on goes, as the tests' mock servers do
            assertEquals(null, ask(services.http("wikidata").client.watched(), "localhost"))
            assertEquals(2, server.requestCount)
            assertEquals("no name looked up for what was held", listOf("api.met.no", "localhost"), watch.looked.toList())
            // switched on again, each goes
            services.switch(emptySet())
            for ((source, host) in listOf("panoramax" to "nl.panoramax.xyz", "atlas-packs" to "objects.githubusercontent.com")) {
                assertEquals(null, ask(services.http(source).client.watched(), host))
            }
            assertEquals(null, ask(long, "overpass.example.net"))
            assertEquals(5, server.requestCount)
        }
        services.close()
    }

    @Test
    fun servicesHandsOutNamedViewsOnly() {
        // no public member of Services gives the client, ours or OkHttp's, without a source's name
        val unnamed = Services::class.java.methods.filter { m ->
            (Http::class.java.isAssignableFrom(m.returnType) || OkHttpClient::class.java.isAssignableFrom(m.returnType)) && m.parameterCount == 0
        }
        assertEquals("$unnamed", emptyList<java.lang.reflect.Method>(), unnamed)
        // nor a name by default (`http(source: String = "")`, whose default Kotlin keeps in a method of its own)
        val defaults = Services::class.java.declaredMethods.filter { it.name.startsWith("http\$") }
        assertEquals("$defaults", emptyList<java.lang.reflect.Method>(), defaults)
        val services = testServices()
        assertEquals("overpass", services.http("overpass").source)
        assertTrue("one view a source", services.http("overpass") === services.http("overpass"))
        services.close()
    }

    /** Real names a listed host stands for: an exact host itself; a pattern's name and one host below it. */
    private fun namesFor(listed: String): List<String> =
        if (listed.startsWith("*.")) listed.removePrefix("*.").let { listOf(it, "en.$it") } else listOf(listed)

    @Test
    fun eachSwitchableSourceSwitchedOffAloneIsRefusedByItsIdThroughServicesOwnClientAndAnothersHostGoes() = runBlocking {
        // every source with a switch of its own, the catalogue's and SourcesModel's six: a gate wired with fewer, or one
        // that refuses whatever is asked while anything is off, fails here
        val switchable = SourcesModel.ALL.filter { it.switchable && it.via == null }
        val services = testServices()
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : mockwebserver3.Dispatcher() {
                    override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse = MockResponse.Builder().code(200).body("ok").build()
                }
                server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                val looked = Collections.synchronizedList(ArrayList<String>())
                // through a view of a source that lists none of these hosts: the host decides
                val client = services.http("wikidata").client.newBuilder().direct()
                    .dns { name -> looked += name; listOf(InetAddress.getByAddress(name, byteArrayOf(127, 0, 0, 1))) }.build()
                fun ask(host: String) = runCatching { client.newCall(okhttp3.Request.Builder().url("http://$host:${server.port}/").build()).execute().close() }.exceptionOrNull()
                val went = ArrayList<String>()
                for ((i, s) in switchable.withIndex()) {
                    services.switch(setOf(s.id))
                    for (h in s.hosts) for (name in namesFor(h)) {
                        val refused = ask(name)
                        assertTrue("${s.id} at $name: $refused", refused is SwitchedOffException && refused.source == s.id && refused.host == name)
                    }
                    // another source's host, while this one alone is off: it goes, and reaches the server
                    val other = switchable[(i + 1) % switchable.size]
                    val name = namesFor(other.hosts.first()).last()
                    assertEquals("${other.id}'s $name with ${s.id} off", null, ask(name))
                    went += name
                }
                assertEquals("only the hosts that went were looked up", went, looked.toList())
                assertEquals(switchable.size, server.requestCount)
            }
        } finally {
            services.close()
        }
    }

    @Test
    fun aRedirectToASwitchedOffSourcesHostIsRefusedAtTheHop() = runBlocking {
        val services = testServices()
        MockWebServer().use { server ->
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse =
                    if (request.url.encodedPath == "/w") MockResponse.Builder().code(302).addHeader("Location", "http://upload.wikimedia.org:${server.port}/p.jpg").build()
                    else MockResponse.Builder().code(200).body("picture").build()
            }
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            // Services' own client, its name lookup gate and all: only the JVM's proxies are left out, and the first hop is
            // asked by the mock's address, which needs no name
            val watch = Watch { true }
            val own = services.http("wikidata").client.newBuilder().direct().eventListener(watch).build()
            fun get(client: OkHttpClient) = client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.port}/w").build()).execute().use { it.body.string() }
            // Wikidata on, Commons off: the first hop goes, the hop to Commons' host is refused before it is sent: by the
            // gate in the name lookup, before the lookup (this machine knows no such name: a lookup would fail otherwise)
            services.switch(setOf("commons"))
            val refused = runCatching { get(own) }.exceptionOrNull()
            assertTrue("$refused", refused is SwitchedOffException && refused.source == "commons" && refused.host == "upload.wikimedia.org")
            assertEquals("one connection, to the first hop: ${watch.connects}", 1, watch.connects.size)
            assertEquals(1, server.requestCount)
            // both on: it goes (Commons' host by the test's own name for this machine)
            services.switch(emptySet())
            assertEquals("picture", get(services.http("wikidata").client.newBuilder().direct().dns(watch.dns).build()))
            assertEquals(3, server.requestCount)
        }
        services.close()
    }

    @Test
    fun whatTheAppAskedIsCountedBySourceAndShownOnThePrivacyScreen() = runBlocking {
        val services = testServices()
        MockWebServer().use { server ->
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest) = MockResponse.Builder().code(200).body("{}").build()
            }
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val watch = Watch { true }
            fun ask(source: String, host: String) = services.http(source).client.newBuilder().direct().dns(watch.dns).eventListener(watch).build()
                .newCall(okhttp3.Request.Builder().url("http://$host:${server.port}/api/interpreter?data=Westerkerk").build()).execute().close()
            services.switch(setOf("wikipedia"))
            // Overpass twice at its own host; Panoramax once at an instance no list names; Wikipedia held back
            repeat(2) { ask(app.atlas.providers.Overpass.INFO.id, "overpass-api.de") }
            ask(SourcesModel.PANORAMAX.id, "nl.panoramax.xyz")
            assertTrue(runCatching { ask(Wikipedia.ID, "en.wikipedia.org") }.exceptionOrNull() is SwitchedOffException)
            assertEquals(3, server.requestCount)
            val asked = services.asked
            assertEquals(2, asked.of("overpass").sent)
            assertEquals(setOf("nl.panoramax.xyz"), asked.of("panoramax").hosts)
            assertEquals(1, asked.of("wikipedia").refused)
            assertEquals(emptySet<String>(), asked.of("overpass").hosts)
            assertFalse("no query kept", "Westerkerk" in asked.all().toString())
        }
        services.close()
    }
}
