package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.model.Place
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.time.Duration

/**
 * NDW, the Dutch National Access Point for road data: every public charger in the Netherlands as OCPI 2.2.1 Locations,
 * with live EVSE status, refreshed upstream several times a day (opendata.ndw.nu, about 18 MB gzipped, 80,000 locations).
 * It is the legal route to live availability in the Netherlands under the EU's AFIR, and needs no key.
 *
 * The file is streamed and parsed location by location ([Ocpi.toPlace]); places come from source "ndw".
 */
class NdwChargers(
    http: Http,
    cacheDir: File?,
    private val url: String = URL,
    maxAge: Duration = DownloadedChargers.LIVE_MAX_AGE,
    autoDownload: Boolean = false,
    region: BBox? = null,
    clock: () -> Instant = Instant::now,
) : DownloadedChargers(http, cacheDir, maxAge, autoDownload, region, clock) {
    override val info = INFO
    override val cacheName = ID

    override suspend fun sourceUrl() = url

    override fun parse(body: InputStream, region: BBox?, add: (Place) -> Unit) = Companion.parse(body, region, add)

    companion object {
        const val ID = "ndw"
        const val URL = "https://opendata.ndw.nu/charging_point_locations_ocpi.json.gz"

        val INFO = ProviderInfo(
            id = ID,
            name = "NDW (Netherlands)",
            licence = "Open data, licence not stated by NDW",
            attribution = "Charging data: NDW, Nationaal Dataportaal Wegverkeer",
            url = "https://opendata.ndw.nu",
            note = "Every public charger in the Netherlands with live status, published under the EU's AFIR. About 18 MB; Atlas fetches it on Wi-Fi.",
            hosts = listOf("opendata.ndw.nu"),
        )

        /**
         * Parses NDW's OCPI file, gzipped or not, calling [add] for each published location in [region].
         * Memory stays flat: one location is held at a time.
         */
        fun parse(body: InputStream, region: BBox?, add: (Place) -> Unit) {
            val input = BufferedInputStream(body, 1 shl 16)
            input.mark(2)
            val gzipped = input.read() == 0x1f && input.read() == 0x8b
            input.reset()
            val stream = if (gzipped) GZIPInputStream(input, 1 shl 16) else input
            JsonStream(InputStreamReader(stream, Charsets.UTF_8)).use { js ->
                // The file is a bare array; OCPI endpoints wrap it as {"data": [...]}; accept both.
                if (js.peek() == JsonStream.Token.BEGIN_OBJECT) {
                    js.beginObject()
                    while (js.hasNext()) if (js.nextName() == "data") readArray(js, region, add) else js.skipValue()
                    js.endObject()
                } else readArray(js, region, add)
            }
        }

        private fun readArray(js: JsonStream, region: BBox?, add: (Place) -> Unit) {
            js.beginArray()
            while (js.hasNext()) {
                val location = js.readTree().asObject() ?: continue
                val place = Ocpi.toPlace(location, ID) ?: continue
                if (region == null || place.location in region) add(place)
            }
            js.endArray()
        }
    }
}
