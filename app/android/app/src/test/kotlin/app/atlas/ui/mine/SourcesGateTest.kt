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
        // no public member of Services gives the client without a source's name
        val unnamed = Services::class.java.methods.filter { Http::class.java.isAssignableFrom(it.returnType) && it.parameterCount == 0 }
        assertEquals("$unnamed", emptyList<java.lang.reflect.Method>(), unnamed)
        val services = testServices()
        assertEquals("overpass", services.http("overpass").source)
        assertTrue("one view a source", services.http("overpass") === services.http("overpass"))
        services.close()
    }

    @Test
    fun eachOfTheAppsOwnSourcesSwitchedOffIsRefusedByItsIdThroughServicesOwnClient() = runBlocking {
        // the six SourcesModel adds to the providers' catalogue: a gate wired with the catalogue alone would let them go
        val own = listOf(SourcesModel.ATLAS_PACKS, SourcesModel.BROUTER, SourcesModel.PANORAMAX, SourcesModel.OPEN_PRICES, SourcesModel.ISS_ORBIT, SourcesModel.TANKERKOENIG)
        val services = testServices()
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val looked = Collections.synchronizedList(ArrayList<String>())
            // through a view of a source that is on: the host decides
            val client = services.http("wikidata").client.newBuilder().direct()
                .dns { name -> looked += name; listOf(InetAddress.getByAddress(name, byteArrayOf(127, 0, 0, 1))) }.build()
            fun ask(host: String) = runCatching { client.newCall(okhttp3.Request.Builder().url("http://$host:${server.port}/").build()).execute().close() }.exceptionOrNull()
            for (s in own) {
                services.switch(setOf(s.id))
                for (h in s.hosts) {
                    val refused = ask(h)
                    assertTrue("${s.id} at $h: $refused", refused is SwitchedOffException && refused.source == s.id && refused.host == h)
                }
            }
            assertEquals("no name looked up", emptyList<String>(), looked.toList())
            assertEquals(0, server.requestCount)
            // switched on: it goes
            services.switch(emptySet())
            assertEquals(null, ask(SourcesModel.OPEN_PRICES.hosts.first()))
            assertEquals(1, server.requestCount)
        }
        services.close()
    }
}
