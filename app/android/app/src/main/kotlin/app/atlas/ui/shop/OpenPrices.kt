package app.atlas.ui.shop

import app.atlas.geo.LatLon
import app.atlas.providers.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate
import java.util.Locale

/** One price somebody reported to Open Prices: [price] in [currency] for [product] at the shop at [at], on [date]. */
internal data class PriceReport(
    val productCode: String?,
    val productName: String?,
    /** The product's pack size as the database gives it ("1 g", "500 ml"), or null. */
    val quantity: String?,
    val price: Double,
    val currency: String,
    val date: LocalDate,
    /** The shop's OpenStreetMap id as "n123" or "w45". */
    val shopOsmId: String,
    val shopName: String?,
    val shopBrand: String?,
    val at: LatLon,
)

/**
 * Open Prices (prices.openfoodfacts.org): prices of food products that people photographed on shelf labels and receipts,
 * open data (ODbL, no key). It is asked for the prices of one product category around a point; where nobody has reported
 * any, the answer is simply empty, and the screens say so.
 */
internal open class OpenPrices(private val http: Http?, private val base: String = "https://prices.openfoodfacts.org") {
    /**
     * The newest price reports for products of [category] (an Open Food Facts tag such as "en:milks") within [radiusKm]
     * of [centre] that are at most [sinceDays] old, newest first.
     */
    open suspend fun around(centre: LatLon, category: String, radiusKm: Double = 3.0, sinceDays: Int = 180, today: LocalDate = LocalDate.now(), size: Int = 100): List<PriceReport> {
        val url = "$base/api/v1/prices".toHttpUrl().newBuilder()
            // About 100 m: the search is by kilometres, and the database is not told exactly where the person stands.
            .addQueryParameter("lat", String.format(Locale.ROOT, "%.3f", centre.lat))
            .addQueryParameter("lon", String.format(Locale.ROOT, "%.3f", centre.lon))
            .addQueryParameter("radius_km", radiusKm.toString())
            .addQueryParameter("product__categories_tags__contains", category)
            .addQueryParameter("date__gte", today.minusDays(sinceDays.toLong()).toString())
            .addQueryParameter("price__gt", "0")
            .addQueryParameter("order_by", "-date")
            .addQueryParameter("size", size.toString())
            .build()
        val client = http ?: throw java.io.IOException("offline")
        val text = client.execute(client.request(url).build()).requireOk().text
        return withContext(Dispatchers.Default) { parse(text) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The reports of an Open Prices `prices` page; entries without a price, a date or a shop position are skipped. */
        fun parse(text: String): List<PriceReport> {
            val items = (json.parseToJsonElement(text) as? JsonObject)?.get("items") as? JsonArray ?: return emptyList()
            return items.mapNotNull { e -> (e as? JsonObject)?.let(::report) }
        }

        private fun report(o: JsonObject): PriceReport? {
            val price = o.dbl("price")?.takeIf { it > 0 } ?: return null
            val date = o.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            val loc = o["location"] as? JsonObject ?: return null
            val lat = loc.dbl("osm_lat") ?: return null
            val lon = loc.dbl("osm_lon") ?: return null
            val type = loc.str("osm_type")?.firstOrNull()?.lowercaseChar() ?: return null
            val id = loc.long("osm_id") ?: return null
            val product = o["product"] as? JsonObject
            val qty = product?.str("quantity") ?: product?.dbl("product_quantity")?.let { q ->
                val unit = product.str("product_quantity_unit") ?: "g"
                "${if (q == q.toLong().toDouble()) q.toLong() else q} $unit"
            }
            return PriceReport(
                productCode = o.str("product_code"),
                productName = (product?.str("product_name") ?: o.str("product_name"))?.let(::cleanName),
                quantity = qty,
                price = price,
                currency = o.str("currency") ?: "EUR",
                date = date,
                shopOsmId = "$type$id",
                shopName = loc.str("osm_name"),
                shopBrand = loc.str("osm_brand"),
                at = LatLon(lat, lon),
            )
        }

        /** Open Food Facts names carry HTML escapes (twice, sometimes). */
        private fun cleanName(s: String): String {
            var t = s
            while ("&amp;" in t) t = t.replace("&amp;", "&")
            return t.replace("&quot;", "\"").replace("&#39;", "'").trim()
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
    }
}
