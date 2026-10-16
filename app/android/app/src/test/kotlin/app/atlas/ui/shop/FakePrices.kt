package app.atlas.ui.shop

import app.atlas.geo.LatLon
import java.time.LocalDate

/**
 * Open Prices as a test sees it: prices made up for the layout tests (never shipped, never shown in the app) for three
 * shops near the Westerkerk, per Open Food Facts category. A category not listed has no reports, like most of the real ones.
 */
internal class FakePrices(private val today: LocalDate = LocalDate.of(2026, 10, 13)) : OpenPrices(null) {
    private class Shop(val id: String, val name: String, val brand: String, val at: LatLon)

    private val ah = Shop("n1001", "Albert Heijn", "Albert Heijn", LatLon(52.37390, 4.88320))
    private val dirk = Shop("n1002", "Dirk", "Dirk", LatLon(52.37690, 4.88610))
    private val jumbo = Shop("n1003", "Jumbo", "Jumbo", LatLon(52.37640, 4.89010))

    // category -> product name, pack, then (shop, price, days ago)
    private data class Line(val shop: Shop, val price: Double, val ago: Int)

    private fun table(): Map<String, Triple<String, String, List<Line>>> = mapOf(
        "en:sourdough-breads" to Triple("Zuurdesembrood", "800 g", listOf(Line(ah, 2.49, 3), Line(dirk, 1.99, 7), Line(jumbo, 2.29, 12))),
        "en:limes" to Triple("Limoenen", "250 g", listOf(Line(ah, 1.80, 3), Line(dirk, 1.50, 7), Line(jumbo, 2.10, 12))),
        "en:fetas" to Triple("Feta", "200 g", listOf(Line(ah, 3.99, 3), Line(dirk, 4.99, 7), Line(jumbo, 5.99, 12))),
        "en:oat-based-drinks" to Triple("Havermelk", "1 l", listOf(Line(ah, 1.79, 3), Line(dirk, 1.69, 7), Line(jumbo, 1.99, 12))),
        "en:saffrons" to Triple("Saffraan", "1 g", listOf(Line(ah, 11.99, 3), Line(dirk, 9.49, 7))),
    )

    override suspend fun around(centre: LatLon, category: String, radiusKm: Double, sinceDays: Int, today: LocalDate, size: Int): List<PriceReport> {
        val (name, pack, lines) = table()[category] ?: return emptyList()
        return lines.map { l ->
            PriceReport("8700000$category".hashCode().toString(), name, pack, l.price, "EUR", this.today.minusDays(l.ago.toLong()), l.shop.id, l.shop.name, l.shop.brand, l.shop.at)
        }
    }
}
