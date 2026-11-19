package app.atlas.providers

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException

/**
 * The unit tests' guard (root build.gradle.kts): a test's request reaches this machine alone. A real name fails naming
 * itself and is never looked up; so does a proxy's, set by the JVM's properties or by hand; a mock on this machine answers.
 */
class GuardTest {
    private fun guarded() {
        assumeFalse("the online tests run unguarded", System.getProperty("atlas.online") == "true")
        val hosts = System.getProperty("jdk.net.hosts.file")
        assertNotNull("names are looked up in a hosts file of this machine's names", hosts)
        assertTrue("the hosts file at $hosts", File(hosts!!).isFile)
    }

    private fun refused(host: String, block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("$host is refused, not reached: $e", e is IOException)
        assertTrue("the failure names $host: ${e!!.message}", generateSequence(e) { it.cause }.any { host in (it.message ?: "") })
    }

    @Test
    fun aRealNameIsNeverLookedUpAndItsFailureNamesIt() {
        guarded()
        refused("nominatim.openstreetmap.org") { InetAddress.getAllByName("nominatim.openstreetmap.org") }
        refused("api.open-meteo.com") {
            OkHttpClient().newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast").build()).execute().close()
        }
        refused("en.wikipedia.org") { Socket().use { it.connect(InetSocketAddress("en.wikipedia.org", 443), 1000) } }
    }

    @Test
    fun noProxyCarriesARequestOut() {
        guarded()
        for (p in listOf("http.proxyHost", "https.proxyHost", "socksProxyHost")) assertEquals("$p is cleared", "", System.getProperty(p) ?: "")
        val set = listOf("https.proxyHost", "socksProxyHost").associateWith { System.getProperty(it) }
        try {
            System.setProperty("https.proxyHost", "proxy.example.org"); System.setProperty("https.proxyPort", "3128")
            refused("proxy.example.org") { OkHttpClient().newCall(Request.Builder().url("https://api.open-meteo.com/").build()).execute().close() }
            System.clearProperty("https.proxyHost")
            System.setProperty("socksProxyHost", "socks.example.org"); System.setProperty("socksProxyPort", "1080")
            refused("socks.example.org") { Socket().use { it.connect(InetSocketAddress.createUnresolved("en.wikipedia.org", 443), 1000) } }
            refused("socks.example.org") { Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("socks.example.org", 1080))).use { it.connect(InetSocketAddress.createUnresolved("en.wikipedia.org", 443), 1000) } }
        } finally {
            set.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
            System.clearProperty("https.proxyPort"); System.clearProperty("socksProxyPort")
        }
    }

    @Test
    fun aMockOnThisMachineAnswersByItsOwnName() {
        guarded()
        Mock().use { m ->
            m.enqueue("here")
            val url = m.server.url("/x")
            OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { assertEquals("here", it.body.string()) }
            assertEquals("localhost", url.host)
        }
    }
}
