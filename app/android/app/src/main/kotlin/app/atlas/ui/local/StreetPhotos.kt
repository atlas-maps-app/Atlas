package app.atlas.ui.local

import android.graphics.BitmapFactory
import app.atlas.Services
import app.atlas.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import java.util.Locale

/** A street photo from Panoramax (open, CC-BY-SA): who took it, when, under what licence, and whether it is a 360 degree panorama. */
class StreetPhoto(
    val id: String,
    /** The day it was taken, "2023-02-06". */
    val takenOn: String?,
    val author: String?,
    val license: String?,
    val imageUrl: String,
    val panorama: Boolean,
    /** The compass heading of the middle of a panorama's picture, degrees; null when not given. */
    val poseHeading: Double?,
    val at: LatLon,
) {
    val year: Int? get() = takenOn?.take(4)?.toIntOrNull()
}

/** A photo with its pixels, as Android's ARGB integers, [width] x [height]. */
class StreetPhotoImage(val photo: StreetPhoto, val pixels: IntArray, val width: Int, val height: Int)

/** Where street photos come from; tests give their own. */
interface StreetPhotoSource {
    /** The photo nearest [at] within a few tens of metres, or null when there is none (or no network). */
    suspend fun near(at: LatLon): StreetPhotoImage?
}

/** Panoramax's public API, asked for the nearest picture within 40 m. */
class PanoramaxSource(private val services: Services) : StreetPhotoSource {
    override suspend fun near(at: LatLon): StreetPhotoImage? = withContext(Dispatchers.IO) {
        if (!services.local.online()) return@withContext null
        try {
            val photo = StreetPhotos.parse(services.http.text(StreetPhotos.searchUrl(at))) ?: return@withContext null
            // the photo from the instance that keeps it (nl.panoramax.xyz, for one), asked as Atlas asks every server
            val bytes = services.http.execute(services.http.request(photo.imageUrl).header("Accept", "image/*").build()).requireOk().body
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            StreetPhotoImage(photo, px, bmp.width, bmp.height)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

object StreetPhotos {
    /** The search for a photo within 40 m of [at], which is sent to about 10 m (until 30 October, to the full double). */
    fun searchUrl(at: LatLon): String =
        "https://api.panoramax.xyz/api/search?place_position=${String.format(Locale.ROOT, "%.4f,%.4f", at.lon, at.lat)}&place_distance=0-40&limit=1"

    /** The first picture of a Panoramax search answer (a GeoJSON feature collection), or null when it has none. */
    fun parse(json: String): StreetPhoto? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val f = (root["features"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val props = f["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val assets = f["assets"] as? JsonObject
        fun href(k: String) = ((assets?.get(k) as? JsonObject)?.get("href") as? JsonPrimitive)?.contentOrNull
        val image = href("sd") ?: href("hd") ?: href("thumb") ?: (props["geovisio:image"] as? JsonPrimitive)?.contentOrNull ?: return null
        val exif = props["exif"] as? JsonObject
        fun exifText(k: String) = (exif?.get(k) as? JsonPrimitive)?.contentOrNull
        val coords = ((f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        val at = if (coords != null && coords.size >= 2) LatLon(coords[1], coords[0]) else return null
        val author = exifText("Exif.Image.Artist")
            ?: ((f["providers"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }?.lastOrNull())
        val pano = exifText("Xmp.GPano.ProjectionType") == "equirectangular"
        val pose = exifText("Xmp.GPano.PoseHeading")?.toDoubleOrNull() ?: (props["view:azimuth"] as? JsonPrimitive)?.doubleOrNull
        return StreetPhoto(
            (f["id"] as? JsonPrimitive)?.contentOrNull ?: image, ((props["datetimetz"] ?: props["datetime"]) as? JsonPrimitive)?.contentOrNull?.take(10),
            author, (props["license"] as? JsonPrimitive)?.contentOrNull, image, pano, pose, at,
        )
    }
}

/** A flat, camera-style view cut out of a 360 degree (equirectangular) photograph. */
object PanoView {
    /**
     * The view looking along [heading] (compass degrees) with a horizontal field of view [fov], as [outW] x [outH] ARGB integers, from a
     * panorama whose middle points along [poseHeading]. The sky is up; the photograph's tilt is not corrected.
     */
    fun render(src: IntArray, sw: Int, sh: Int, heading: Double, poseHeading: Double, fov: Double, outW: Int, outH: Int): IntArray {
        val out = IntArray(outW * outH)
        val f = outW / 2.0 / tan(Math.toRadians(fov / 2))
        val yaw = Math.toRadians(heading - poseHeading)
        val cy = cos(yaw)
        val sy = sin(yaw)
        for (j in 0 until outH) {
            val y = (j + 0.5 - outH / 2.0) / f
            for (i in 0 until outW) {
                val x = (i + 0.5 - outW / 2.0) / f
                // The ray in the camera's frame is (x, -y, 1); turn it by the yaw about the vertical.
                val dx = x * cy + 1.0 * sy
                val dz = -x * sy + 1.0 * cy
                val lon = atan2(dx, dz)
                val lat = atan2(-y, sqrt(dx * dx + dz * dz))
                val u = ((lon / (2 * PI) + 0.5) * sw).coerceIn(0.0, sw - 1.0)
                val v = ((0.5 - lat / PI) * sh).coerceIn(0.0, sh - 1.0)
                out[j * outW + i] = src[floor(v).toInt() * sw + floor(u).toInt()]
            }
        }
        return out
    }
}
