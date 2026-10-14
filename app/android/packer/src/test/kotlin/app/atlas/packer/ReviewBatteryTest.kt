package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.search.PlaceStore
import app.atlas.search.SearchContext
import app.atlas.search.SearchEngine
import app.atlas.search.SearchItem
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Review scratch: the same searches on the old and the new store, written to a file to compare. Not to be kept. */
class ReviewBatteryTest {
    private val variant = if (runCatching { Class.forName("app.atlas.search.StoreVersionException") }.isSuccess) "new" else "old"
    private val root = File("/Users/erika/Documents/Claudea/cache/packs/review/$variant")
    private val pbf = File("/Users/erika/Documents/Claudea/cache/packs/luxembourg.osm.pbf")

    private val queries = listOf(
        // names
        "gare centrale", "kirchberg", "philharmonie", "mudam", "place d'armes", "grund", "esch-sur-alzette", "vianden",
        "chateau de vianden", "echternach", "aeroport", "findel", "clervaux", "bourscheid", "mullerthal", "cathedrale",
        "pont adolphe", "glacis", "belval", "rockhal", "cloche d'or", "bonnevoie", "limpertsberg", "merl",
        "gare", "kirchb", "phil", "lux",
        // typos
        "philarmonie", "echternch", "clerveaux", "kirchbrg", "bourchied", "luxembrg",
        // kinds
        "boulangerie", "pharmacies", "pharmacie", "restaurant", "cafe", "supermarche", "station service", "parking",
        "hotel", "bakery", "pizza", "sushi", "italien", "distributeur", "toilettes", "arret de bus", "boulangerie bonnevoie",
        "restaurant kirchberg", "pharmacie esch",
        // addresses
        "avenue de la liberte 13", "15 boulevard royal luxembourg", "boulevrd royal 15", "L-2449", "2449",
        "rue de luxembourg 12", "rue de la gare 1", "grand-rue 5", "route d'arlon 100", "10 rue de l'eglise",
        "avenue de la gare 26", "avenue de la gar", "rue des bains", "rue de la poste esch", "1 rue de l'eglise bertrange",
        "boulevard royal", "route d'arlon", "rue du fort elisabeth 10",
        // brands
        "cactus", "aldi", "lidl", "delhaize", "mcdonalds", "auchan", "post",
        // structured
        "amenity=cafe and outdoor_seating=yes", "cuisine=italian", "amenity=pharmacy near amenity=parking 200m",
        "cafe pres de la gare", "restaurant with outdoor seating", "amenity=restaurant and wheelchair=yes",
        "amenity=charging_station", "\"socket:type2_combo\"=*", "shop=bakery and opening_hours=*",
        "addr:street=\"Boulevard Royal\"", "addr:postcode=2449", "building=house", "highway=bus_stop and shelter=yes",
        "tourism=museum", "historic=castle", "leisure=playground near amenity=cafe 100m", "natural=peak", "operator=CFL",
        "amenity=bench and backrest=yes", "amenity=restaurant and payment:credit_cards=yes",
        "addr:city=Esch-sur-Alzette and amenity=restaurant", "name~\"Gare\"", "amenity=atm", "shop=supermarket and brand=*",
        "amenity=cafe near amenity=bicycle_rental 300m", "addr:housenumber=13 and addr:street=\"Avenue de la Liberté\"",
    )

    @Test
    fun battery() {
        assumeTrue(pbf.isFile)
        root.mkdirs()
        val db = File(root, "luxembourg.places.db")
        if (!db.isFile) {
            val t = System.nanoTime()
            val built = PlacesPackBuilder.build(pbf, db, PackOptions(), name = "Luxembourg", gzip = variant == "new", keepDb = true) { println("lux $it") }
            File(root, "build.txt").writeText("built in ${(System.nanoTime() - t) / 1_000_000_000} s: ${built.rawBytes} bytes, file ${built.file.length()}\n")
        }
        val out = StringBuilder()
        PlaceStore.open(db).use { store ->
            val engine = SearchEngine(store)
            val here = SearchContext(location = LatLon(49.6116, 6.1319), lang = "fr")
            out.append("size ${store.size}\n")
            val box = BBox.around(LatLon(49.6116, 6.1319), 300.0)
            out.append("inBox300 ${store.inBox(box).size}\n")
            for ((k, v) in listOf(
                "amenity" to "pharmacy", "cuisine" to "italian", "cuisine" to "pizza", "addr:street" to "Boulevard Royal",
                "addr:housenumber" to null, "addr:postcode" to "2449", "building" to null, "building" to "house", "shelter" to "yes",
                "operator" to "CFL", "wheelchair" to "yes", "opening_hours" to null, "opening_hours" to "24/7", "name" to null,
                "brand:wikidata" to null, "addr:city" to "Esch-sur-Alzette", "entrance" to null, "check_date" to null, "source" to null,
            )) {
                out.append("count $k=$v ${store.count(k, v)} box ${store.count(k, v, box)} estimate ${store.estimate(k, v)} byTag ${store.byTag(k, v, null, 100000).size}\n")
            }
            out.append("text 'gare' ${store.text("gare", here.location).size}\n")
            for (q in queries) {
                runBlocking { engine.search(q, here) }
                val times = DoubleArray(5)
                var r = runBlocking { engine.search(q, here) }
                for (i in times.indices) {
                    val s = System.nanoTime()
                    r = runBlocking { engine.search(q, here) }
                    times[i] = (System.nanoTime() - s) / 1e6
                }
                times.sort()
                out.append("\n### \"$q\"  %.1f ms\n".format(times[2]))
                for (item in r.items.take(6)) {
                    out.append("  ").append(
                        when (item) {
                            is SearchItem.PlaceResult -> "PLACE ${item.match} ${item.title} | ${item.subtitle} | ${item.hit.record.id ?: item.hit.place.location} | ${item.hit.distance?.toInt()} m"
                            is SearchItem.Kind -> "KIND ${item.label} x${item.count}"
                            is SearchItem.BrandResult -> "BRAND ${item.brand.id} x${item.count}"
                            is SearchItem.GoodsResult -> "GOODS ${item.label}"
                            is SearchItem.Structured -> "QUERY total=${item.results.firstOrNull()?.total} examined=${item.results.firstOrNull()?.examined} top=" +
                                item.results.firstOrNull()?.hits?.take(5)?.joinToString(" / ") { h ->
                                    (h.tags["name"] ?: listOfNotNull(h.tags["addr:street"], h.tags["addr:housenumber"]).joinToString(" ")) + " ${h.distance?.toInt()}m"
                                }
                            is SearchItem.Element -> "ELEMENT ${item.hit.record.id}"
                            else -> item::class.simpleName ?: "?"
                        },
                    ).append('\n')
                }
            }
        }
        File(root.parentFile, "$variant.txt").writeText(out.toString())
    }
}

/** Review scratch: where slow searches spend their time, by sampling stacks. */
class ReviewProfileTest {
    @Test
    fun profile() {
        val db = File("/Users/erika/Documents/Claudea/cache/packs/review/new/luxembourg.places.db")
        assumeTrue(db.isFile)
        val qs = (System.getenv("REVIEW_QUERIES") ?: File("/Users/erika/Documents/Claudea/cache/packs/review/profile.txt").readText()).lines().filter { it.isNotBlank() }
        val out = StringBuilder()
        PlaceStore.open(db).use { store ->
            val engine = SearchEngine(store)
            val here = SearchContext(location = LatLon(49.6116, 6.1319), lang = "fr")
            for (q in qs) {
                runBlocking { engine.search(q, here) }
                val counts = HashMap<String, Int>()
                val stop = java.util.concurrent.atomic.AtomicBoolean(false)
                val sampler = Thread {
                    while (!stop.get()) {
                        for ((t, st) in Thread.getAllStackTraces()) {
                            if (!t.name.contains("DefaultDispatcher") && t.name != "Test worker") continue
                            val frames = st.filter { it.className.startsWith("app.atlas") }.take(6)
                            if (frames.isEmpty()) continue
                            val key = frames.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                            counts.merge(key, 1, Int::plus)
                        }
                        Thread.sleep(1)
                    }
                }
                sampler.start()
                val s = System.nanoTime()
                repeat(20) { runBlocking { engine.search(q, here) } }
                val ms = (System.nanoTime() - s) / 1e6 / 20
                stop.set(true)
                sampler.join()
                out.append("\n### $q  %.1f ms\n".format(ms))
                for ((k, n) in counts.entries.sortedByDescending { it.value }.take(12)) out.append("  $n  $k\n")
            }
        }
        File("/Users/erika/Documents/Claudea/cache/packs/review/profile-out.txt").writeText(out.toString())
    }
}

/** Review scratch: store-level text timings. */
class ReviewTextTest {
    @Test
    fun text() {
        val db = File("/Users/erika/Documents/Claudea/cache/packs/review/new/luxembourg.places.db")
        assumeTrue(db.isFile)
        val out = StringBuilder()
        PlaceStore.open(db).use { store ->
            val here = LatLon(49.6116, 6.1319)
            for (q in File("/Users/erika/Documents/Claudea/cache/packs/review/text.txt").readLines().filter { it.isNotBlank() }) {
                var r = store.text(q, here)
                val t = DoubleArray(9) { val s = System.nanoTime(); r = store.text(q, here); (System.nanoTime() - s) / 1e6 }
                t.sort()
                out.append("%-40s %6.1f ms  %d: %s\n".format(q, t[4], r.size, r.take(4).joinToString(" / ") { (it.tags["name"] ?: "") + " " + listOfNotNull(it.tags["addr:street"], it.tags["addr:housenumber"]).joinToString(" ") }))
            }
        }
        File("/Users/erika/Documents/Claudea/cache/packs/review/text-out.txt").writeText(out.toString())
    }
}
