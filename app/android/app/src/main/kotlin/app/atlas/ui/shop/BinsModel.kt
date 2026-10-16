package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.Text

/** A bin or drop-off: the place, how far, which way, and which waste streams it takes. */
internal class BinTarget(val place: ShopPlace, val distanceM: Double, val bearing: Double, val streams: Set<Stream>) {
    val key: String get() = place.key

    /** A recycling centre (a site for bulky and special waste), not a street container. */
    val centre: Boolean get() = place.tags["recycling_type"] == "centre"
}

/** What the Bins screen shows: the nearest trash can, the nearest recycling point, and the nearest drop-off per stream. */
internal class BinsView(val trash: BinTarget?, val recycling: BinTarget?, val byStream: Map<Stream, BinTarget>)

internal object BinsFinder {
    /** The streams [p] takes; waste baskets take rubbish. */
    fun streamsOf(p: ShopPlace): Set<Stream> = Stream.entries.filter { it.takes(p.tags) }.toSet()

    fun targets(area: ShopArea, here: LatLon): List<BinTarget> = area.places
        .filter { p -> p.tags["access"] != "private" && p.tags["access"] != "no" }
        .map { p -> BinTarget(p, Geo.distance(here, p.location), Geo.bearing(here, p.location), streamsOf(p)) }
        .sortedBy { it.distanceM }

    fun view(area: ShopArea, here: LatLon): BinsView {
        val all = targets(area, here)
        val trash = all.firstOrNull { Stream.RUBBISH in it.streams }
        val recycling = all.firstOrNull { it.place.tags["amenity"] == "recycling" }
        val by = Stream.entries.mapNotNull { s -> all.firstOrNull { s in it.streams }?.let { s to it } }.toMap()
        return BinsView(trash, recycling, by)
    }

    /** The drop-offs for [stream], nearest first; centres (special sites) after the street containers. */
    fun forStream(area: ShopArea, here: LatLon, stream: Stream): List<BinTarget> =
        targets(area, here).filter { stream in it.streams }
}

/** "What goes where?": the usual home of everyday things, in English and Dutch. The city's own rules win; the screen says so. */
internal class WasteAnswer(val phrase: String, val stream: Stream?, val noteRes: Int?)

internal object WasteGuide {
    // phrases (en | nl) -> stream, with an optional note key
    private data class Row(val en: String, val nl: String, val stream: Stream?, val note: Int? = null)

    private val ROWS = listOf(
        Row("glass bottle,glass jar,bottle,jar,wine bottle", "glazen fles,glazen pot,fles,pot,wijnfles", Stream.MIXED),
        Row("tin can,can,drinks can,metal,aluminium foil,foil", "blik,drankblik,metaal,aluminiumfolie,folie", Stream.MIXED),
        Row("plastic bottle,plastic packaging,yoghurt pot,drinks carton,carton", "plastic fles,plastic verpakking,yoghurtpot,drinkpak,pak", Stream.MIXED),
        Row("takeaway tub,takeout tub,tub", "afhaalbakje,bakje", Stream.MIXED, app.atlas.R.string.shop_guide_note_tub),
        Row("plastic bag,carrier bag,bag", "plastic tas,tasje,zak", Stream.MIXED, app.atlas.R.string.shop_guide_note_bag),
        Row("newspaper,magazine,paper,cardboard,box,envelope,books", "krant,tijdschrift,papier,karton,doos,envelop,boeken", Stream.PAPER),
        Row("pizza box", "pizzadoos", Stream.PAPER, app.atlas.R.string.shop_guide_note_pizza),
        Row("food scraps,peel,vegetable peel,fruit peel,coffee grounds,tea bag,garden waste,leaves", "gft,groenteafval,schillen,koffiedik,theezakje,tuinafval,bladeren", Stream.ORGANIC),
        Row("battery,batteries,button cell", "batterij,batterijen,knoopcel", Stream.BATTERIES),
        Row("clothes,shoes,textile,textiles,towel,curtains", "kleding,schoenen,textiel,handdoek,gordijnen", Stream.CLOTHES),
        Row("phone,mobile phone,laptop,charger,cable,headphones,toaster,small electronics,electronics", "telefoon,laptop,oplader,kabel,koptelefoon,broodrooster,elektronica", Stream.ELECTRONICS),
        Row("light bulb,bulb,led bulb,energy saving bulb,fluorescent tube", "lamp,gloeilamp,ledlamp,spaarlamp,tl buis", Stream.ELECTRONICS, app.atlas.R.string.shop_guide_note_bulb),
        Row("cooking oil,frying oil,oil", "frituurvet,bakvet,olie", Stream.OIL, app.atlas.R.string.shop_guide_note_oil),
        Row("paint,solvent,chemicals,white spirit,engine oil,spray can,aerosol", "verf,oplosmiddel,chemicaliën,terpentine,motorolie,spuitbus", Stream.HAZARDOUS),
        Row("tissue,tissues,nappy,nappies,diaper,diapers,dirty paper,cigarette", "tissue,tissues,luier,luiers,sigaret,vies papier", Stream.RUBBISH, app.atlas.R.string.shop_guide_note_tissue),
        Row("chewing gum,broken ceramics,mug,plate,vacuum bag", "kauwgom,keramiek,mok,bord,stofzuigerzak", Stream.RUBBISH),
    )

    /** The usual home of [text], or null when the guide does not know it. */
    fun lookup(text: String, lang: String): WasteAnswer? {
        val f = Text.fold(text).trim()
        if (f.isEmpty()) return null
        var best: Pair<Int, Row>? = null
        for (r in ROWS) {
            val phrases = (if (lang == "nl") r.nl + "," + r.en else r.en + "," + r.nl).split(',').map { Text.fold(it) }
            val score = phrases.maxOf { p ->
                when {
                    p == f -> 100 + p.length
                    f.length >= 3 && (" $f ").contains(" $p ") -> 50 + p.length
                    f.length >= 4 && p.startsWith(f) -> 30 + f.length
                    else -> 0
                }
            }
            if (score > 0 && (best == null || score > best.first)) best = score to r
        }
        return best?.second?.let { WasteAnswer(text.trim(), it.stream, it.note) }
    }

    /** Things people get wrong, for the Bins screen: pizza box, plastic bag, takeaway tub. */
    val tricky = listOf("pizza box", "plastic bag", "takeaway tub")
}
