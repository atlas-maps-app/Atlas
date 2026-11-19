package app.atlas.testing

import app.atlas.providers.Wikipedia
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * The unit tests' guard (root build.gradle.kts) holds in the app's tests too, for the clients they build themselves:
 * [direct]'s, which `SearchScreensTest` and `SourcesGateTest` build on `Services`' views with no proxy and sockets of
 * their own, and a plain socket that skips the JVM's proxies. Without a name lookup of their own, a real name fails,
 * naming itself, and is never asked of a resolver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
    fun aDirectClientOnAViewOfServicesReachesNoRealServer() {
        guarded()
        val services = testServices()
        try {
            // as SearchScreensTest builds it, without its own name lookup: Wikipedia is on, so the gate lets it go
            val client = services.http(Wikipedia.ID).client.newBuilder().direct().build()
            refused("en.wikipedia.org") {
                client.newCall(Request.Builder().url("https://en.wikipedia.org/api/rest_v1/page/summary/Westerkerk").build()).execute().close()
            }
            refused("api.open-meteo.com") {
                OkHttpClient.Builder().direct().build().newCall(Request.Builder().url("https://api.open-meteo.com/v1/forecast").build()).execute().close()
            }
            refused("nominatim.openstreetmap.org") { Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress("nominatim.openstreetmap.org", 443), 1000) } }
        } finally {
            services.close()
        }
    }
}
