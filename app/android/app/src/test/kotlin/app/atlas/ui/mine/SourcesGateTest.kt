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
    private class Watch : EventListener() {
        val looked: MutableList<String> = Collections.synchronizedList(ArrayList())
        val connects: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** Every Wikipedia host is this machine, where the mock listens; any other name is not found. */
        val dns = Dns { name ->
            looked += name
            if (name.endsWith(".wikipedia.org")) listOf(InetAddress.getByAddress(name, byteArrayOf(127, 0, 0, 1))) else throw UnknownHostException(name)
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connects += "$inetSocketAddress"
        }
    }

    /** The app's [Services.http], its gate and all, with [watch]'s names and sockets, and no proxy of the JVM's. */
    private fun watched(services: Services, watch: Watch): Http =
        Http(base = services.http.client.newBuilder().direct().dns(watch.dns).eventListener(watch).build())

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
}
