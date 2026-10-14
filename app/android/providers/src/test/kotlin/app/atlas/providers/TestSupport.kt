package app.atlas.providers

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assume
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** Recorded answers of the real services, from src/test/resources/fixtures. */
object Fixtures {
    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    fun bytes(name: String): ByteArray =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$name") ?: error("No fixture $name")).use { it.readBytes() }
}

/** A local MockWebServer with helpers; every request is recorded for the test to inspect. */
class Mock : Closeable {
    val server = MockWebServer().apply { start() }

    fun url(path: String = "/"): String = server.url(path).toString()

    /** The server's root without the trailing slash, as providers take their base URL. */
    val base: String get() = server.url("/").toString().removeSuffix("/")

    fun enqueue(body: String, code: Int = 200, vararg headers: Pair<String, String>) =
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())

    fun enqueueBytes(body: ByteArray, code: Int = 200, vararg headers: Pair<String, String>) =
        server.enqueue(MockResponse.Builder().code(code).body(Buffer().write(body)).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())

    fun fixture(name: String, code: Int = 200, vararg headers: Pair<String, String>) = enqueue(Fixtures.text(name), code, *headers)

    /** Answers by request instead of in order. */
    fun dispatch(answer: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
    }

    fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No request arrived")

    val count: Int get() = server.requestCount

    override fun close() = server.close()
}

fun response(body: String, code: Int = 200, vararg headers: Pair<String, String>): MockResponse =
    MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

/** An [Http] without rate limits, as tests talk to a local server. */
fun testHttp(): Http = Http(policies = emptyMap())

/** Skips the test unless it was asked for with -Datlas.online=true. */
fun assumeOnline() = Assume.assumeTrue("online tests run with -Datlas.online=true", System.getProperty("atlas.online") == "true")

/** The body of a recorded request as text. */
val RecordedRequest.text: String get() = body?.utf8() ?: ""
