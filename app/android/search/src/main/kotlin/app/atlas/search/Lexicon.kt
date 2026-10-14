package app.atlas.search

import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Cuisine
import app.atlas.places.Geometry
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import java.time.DayOfWeek
import java.util.concurrent.ConcurrentHashMap

/** What a phrase of a natural-language query means. */
internal sealed class Meaning {
    /** A kind of place: "café", "bike dock", "apotheek". [cuisine] marks food words that refine another kind. */
    class Noun(val query: Query, val symbol: String, val id: String, val rank: Int, val cuisine: Boolean = false) : Meaning()

    /** A property: "wifi", "step-free", "vegan"; [soft] ones only rank higher ("quiet", "cheap"). */
    class Attribute(val filter: TagFilter, val symbol: String, val id: String, val soft: Boolean) : Meaning()

    class Open(val time: OpenTime) : Meaning()

    class Day(val day: DayOfWeek?, val offset: Int = 0) : Meaning()

    class Sort(val by: SortBy) : Meaning()

    /** Connectors and small words, by role. */
    class Word(val role: Role) : Meaning()

    enum class Role { NEAR, WITH, FROM, WITHIN, NOT_NEAR, WITHOUT, AROUND_ME, IN, THEN, AND, OR, OF, FILLER }
}

/** One entry of the lexicon: its words, what they mean, and the language they belong to. */
internal class Entry(val words: List<String>, val meaning: Meaning, val lang: String)

/**
 * The words Atlas understands in English, Dutch, German and French: kinds of places (hand-picked everyday words, then
 * every id-tagging-schema preset name and search term, Atlas's categories, cuisines and brands), properties, opening
 * times, distances and the connectors between them. Phrases are matched longest first.
 */
internal object Lexicon {
    val LANGS = listOf("en", "nl", "de", "fr")

    // kind|id|payload|symbol|lang:phrase,phrase|…   N noun, A attribute, P preference, O open, S sort, C connector
    private val TABLE = """
N|cafe|amenity=cafe|maki-cafe|en:coffee,cafe,cafes,coffee shop,coffee bar,espresso bar,tea room|nl:koffie,koffiezaak,koffiebar,lunchroom,kopje koffie|de:kaffee,cafe,kaffeehaus,kaffeebar|fr:cafe,cafes,salon de the
N|restaurant|amenity=restaurant|maki-restaurant|en:restaurant,restaurants,dinner,supper,dine,dining,eat out|nl:restaurant,restaurants,diner,avondeten,uit eten,dineren|de:restaurant,restaurants,abendessen,essen gehen,gaststatte|fr:restaurant,restaurants,diner,resto,restos
N|lunch|amenity~^(restaurant|cafe)$|maki-restaurant|en:lunch|nl:lunch,lunchen|de:mittagessen,mittag|fr:dejeuner
N|breakfast|amenity~^(cafe|restaurant)$|maki-cafe|en:breakfast,brunch|nl:ontbijt,ontbijten,brunch|de:fruhstuck,brunch|fr:petit dejeuner,brunch
N|food|amenity~^(restaurant|fast_food|cafe|food_court)$|maki-restaurant|en:food,eat,something to eat,a bite,bite to eat|nl:eten,iets eten,hapje|de:essen,etwas essen|fr:manger,nourriture,quelque chose a manger
N|fast_food|amenity=fast_food|maki-fast-food|en:fast food,takeaway food,snack,snacks|nl:snackbar,snack,cafetaria|de:imbiss,schnellimbiss|fr:fast food,restauration rapide
N|bar|amenity~^(bar|pub|biergarten)$|maki-bar|en:bar,bars,drinks,a drink,drink,beer,beers,cocktail,cocktails,wine bar|nl:kroeg,kroegen,bruin cafe,borrel,biertje,drankje|de:kneipe,bier,cocktailbar,weinbar|fr:bar,bars,biere,un verre,cocktail
N|bicycle_rental|amenity=bicycle_rental|temaki-bicycle_rental|en:bike dock,bike docks,bike share,bike sharing,bike rental,bicycle rental,rent a bike,dock,docks,cycle hire,bike hire|nl:fietsverhuur,ov fiets,deelfiets,fiets huren,huurfiets|de:fahrradverleih,leihrad,mietrad,radverleih,fahrrad mieten|fr:location de velos,location de velo,velo en libre service,velib
N|bicycle_parking|amenity=bicycle_parking|temaki-bicycle_parked|en:bike parking,bike rack,bike racks,bicycle parking|nl:fietsenstalling,fietsenrek,fietsparkeren|de:fahrradstander,fahrradparkplatz|fr:parking velo,arceau
N|toilets|amenity=toilets|maki-toilet|en:toilet,toilets,wc,restroom,restrooms,loo,bathroom,public toilet,public toilets|nl:toilet,toiletten,wc,plee,openbaar toilet|de:toilette,toiletten,wc,klo|fr:toilettes,toilette,wc,sanitaires
N|charging_station|amenity=charging_station|maki-charging-station|en:charger,chargers,charging,charging station,charging stations,charge,charge the car,charge my car,charge point,ev charger|nl:laadpaal,laadpalen,laadstation,oplaadpunt,opladen,auto opladen|de:ladestation,ladesaule,ladepunkt,auto laden|fr:borne,borne de recharge,bornes,recharge,recharger
N|atm|amenity=atm|maki-bank|en:atm,atms,cash,cash machine,cashpoint|nl:pinautomaat,geldautomaat,pinnen,flappentap|de:geldautomat,bankomat,bargeld|fr:distributeur,dab,distributeur de billets
N|pharmacy|amenity=pharmacy|maki-pharmacy|en:pharmacy,pharmacies,chemist|nl:apotheek,apotheken|de:apotheke,apotheken|fr:pharmacie,pharmacies
N|supermarket|shop=supermarket|maki-grocery|en:supermarket,supermarkets|nl:supermarkt,supermarkten|de:supermarkt|fr:supermarche
N|groceries|shop~^(supermarket|convenience|greengrocer)$|maki-grocery|en:groceries,grocery,grocery store,food shop|nl:boodschappen,boodschappen doen|de:lebensmittel,einkaufen|fr:courses,epicerie,faire les courses
N|park|leisure=park|maki-park|en:park,parks|nl:park,parken|de:park,parks|fr:parc,parcs
N|playground|leisure=playground|maki-playground|en:playground,playgrounds|nl:speeltuin,speeltuinen|de:spielplatz|fr:aire de jeux
N|cinema|amenity=cinema|maki-cinema|en:cinema,movie,movies,film,films,movie theater|nl:bioscoop,film,films|de:kino,film|fr:cinema,film,films
N|parking|amenity=parking|maki-parking|en:parking,car park,park the car,parking garage|nl:parkeren,parkeerplaats,parkeergarage|de:parkplatz,parkhaus,parken|fr:parking,stationnement
N|bakery|shop=bakery|maki-bakery|en:bakery,bread|nl:bakker,bakkerij,brood|de:backerei,backer,brotchen|fr:boulangerie,pain
N|ice_cream|amenity=ice_cream or shop=ice_cream|maki-ice-cream|en:ice cream,gelato|nl:ijs,ijssalon,ijsje|de:eis,eisdiele|fr:glace,glacier,glaces
N|drinking_water|amenity=drinking_water|maki-drinking-water|en:drinking water,water fountain,water tap,refill water|nl:drinkwater,watertappunt,kraanwater|de:trinkwasser,trinkbrunnen|fr:eau potable,fontaine
N|post_box|amenity=post_box|maki-post|en:mailbox,post box,postbox,letter box|nl:brievenbus|de:briefkasten|fr:boite aux lettres
N|post_office|amenity=post_office|maki-post|en:post office|nl:postkantoor,postpunt|de:post,postamt,postfiliale|fr:poste,la poste,bureau de poste
N|stay|tourism~^(hotel|hostel|guest_house|apartment|motel)$|maki-lodging|en:place to sleep,somewhere to sleep,stay,accommodation|nl:overnachten,slapen,logeren|de:ubernachten,unterkunft|fr:dormir,hebergement,logement
N|doctors|amenity=doctors|maki-doctor|en:doctor,doctors,gp|nl:huisarts,dokter|de:arzt,hausarzt|fr:medecin,docteur
N|fuel|amenity=fuel|maki-fuel|en:petrol,gas station,petrol station,fuel|nl:tankstation,benzine,tanken|de:tankstelle,tanken|fr:station service,essence,carburant
N|bus_stop|highway=bus_stop|maki-bus|en:bus stop,bus|nl:bushalte|de:bushaltestelle,bushalt|fr:arret de bus
N|tram_stop|railway=tram_stop|maki-rail-light|en:tram stop,tram|nl:tramhalte|de:strassenbahnhaltestelle|fr:arret de tram
N|station|railway~^(station|halt)$|maki-rail|en:train station,station,railway station|nl:station,treinstation|de:bahnhof|fr:gare
N|gym|leisure=fitness_centre|maki-fitness-centre|en:gym,fitness|nl:sportschool|de:fitnessstudio|fr:salle de sport
N|bench|amenity=bench|temaki-bench|en:bench,benches|nl:bankje,bankjes|de:sitzbank|fr:banc,bancs
N|picnic|leisure=picnic_table|maki-picnic-site|en:picnic,picnic spot,picnic table|nl:picknick,picknickplek,picknicktafel|de:picknick,picknicktisch|fr:pique nique,table de pique nique
N|laundry|shop=laundry|maki-laundry|en:laundry,laundromat|nl:wasserette,wassalon|de:waschsalon|fr:laverie
N|ferry|amenity=ferry_terminal|maki-ferry|en:ferry|nl:pont,veerpont,veer|de:fahre|fr:bac,ferry
A|wifi|internet_access~^(wlan|yes|wifi|public)$|ui-wifi|en:wifi,wi fi,wlan,internet,free wifi|nl:wifi,gratis wifi,internet|de:wlan,wifi,internet|fr:wifi,internet
P|outlet|power_supply=yes|ui-plug|en:outlet,outlets,power outlet,power outlets,plug,plugs,socket,sockets,power|nl:stopcontact,stopcontacten,stekker|de:steckdose,steckdosen,strom|fr:prise,prises,prise electrique
A|terrace|outdoor_seating=yes|ui-sun|en:terrace,outdoor seating,outside,outdoors,patio|nl:terras,terrasje,buiten,buitenterras|de:terrasse,draussen,aussen|fr:terrasse,dehors,en terrasse
P|garden|outdoor_seating=garden or beer_garden=yes or garden=yes|ui-trees|en:garden,beer garden|nl:tuin|de:garten|fr:jardin
P|view|view or outdoor_seating=roof|ui-eye|en:view,a view,nice view|nl:uitzicht|de:aussicht|fr:vue
A|wheelchair|wheelchair~^(yes|designated)$ or toilets:wheelchair=yes|ui-wheelchair|en:wheelchair,wheelchair accessible,step free,stepfree,accessible,barrier free|nl:rolstoel,rolstoeltoegankelijk,toegankelijk,drempelvrij|de:rollstuhl,rollstuhlgerecht,barrierefrei,stufenlos|fr:fauteuil roulant,accessible,pmr,accessible en fauteuil
A|vegan|diet:vegan~^(yes|only)$|ui-vegan|en:vegan,plant based|nl:vegan,veganistisch,veganistische,plantaardig|de:vegan,vegane,veganes,veganer|fr:vegan,vegane,vegetalien,vegetalienne
A|vegetarian|diet:vegetarian~^(yes|only)$ or diet:vegan~^(yes|only)$|ui-sprout|en:vegetarian,veggie|nl:vegetarisch,vegetarische|de:vegetarisch,vegetarische,vegetarisches|fr:vegetarien,vegetarienne
A|halal|diet:halal~^(yes|only)$|ui-check|en:halal|nl:halal|de:halal|fr:halal
A|kosher|diet:kosher~^(yes|only)$|ui-check|en:kosher|nl:koosjer|de:koscher|fr:casher,cacher
A|gluten_free|diet:gluten_free~^(yes|only)$|ui-wheat-off|en:gluten free,glutenfree|nl:glutenvrij,glutenvrije|de:glutenfrei,glutenfreie|fr:sans gluten
A|lactose_free|diet:lactose_free~^(yes|only)$|ui-milk-off|en:lactose free,dairy free|nl:lactosevrij|de:laktosefrei|fr:sans lactose
A|free|fee!=yes|ui-coins|en:free,free of charge,no fee|nl:gratis|de:kostenlos,gratis|fr:gratuit,gratuite,gratuits
P|cheap|fee=no or amenity=fast_food|ui-coins|en:cheap,inexpensive,budget,affordable|nl:goedkoop,goedkope,voordelig|de:billig,gunstig,preiswert|fr:pas cher,bon marche
P|quiet|live_music!=yes and amenity!~^(bar|pub|nightclub)$|ui-headphones|en:quiet,calm,peaceful|nl:rustig,rustige,stil,stille|de:ruhig,ruhige,leise|fr:calme,tranquille
P|kids|kids_area=yes or highchair=yes or changing_table=yes|ui-baby|en:kid friendly,kids,child friendly,family friendly,with kids|nl:kindvriendelijk,kinderen,met kinderen|de:kinderfreundlich,kinder,mit kindern|fr:enfants,adapte aux enfants
A|dogs|dog~^(yes|leashed|unleashed|outside)$|ui-dog|en:dog friendly,dogs allowed,dogs,dog|nl:hondvriendelijk,honden toegestaan,honden,hond|de:hundefreundlich,hunde erlaubt,hunde,hund|fr:chiens acceptes,chiens admis,chiens,chien
A|takeaway|takeaway~^(yes|only)$|ui-shopping-bag|en:takeaway,take away,to go,takeout|nl:afhalen,meenemen|de:zum mitnehmen,mitnehmen|fr:a emporter
A|delivery|delivery=yes|ui-truck|en:delivery,deliver|nl:bezorgen,bezorging|de:lieferung,lieferservice|fr:livraison
A|smoking|smoking~^(yes|outside|separated|isolated)$|ui-smoking|en:smoking|nl:roken|de:rauchen,raucher|fr:fumeur
A|non_smoking|smoking=no|ui-smoke-off|en:non smoking,no smoking|nl:rookvrij|de:rauchfrei,nichtraucher|fr:non fumeur
A|always_open|opening_hours=24/7|ui-clock|en:24/7,24 7,always open,24 hours,24h|nl:dag en nacht,24 uur|de:rund um die uhr,24 stunden|fr:24h/24,24 heures
A|fast|socket:type2_combo or socket:chademo or socket:tesla_supercharger|ui-bolt|en:fast,rapid,fast charging,fast charger|nl:snel,snellader,snelladen,snellaadpaal|de:schnell,schnellladen,schnelllader|fr:rapide,recharge rapide
A|organic|organic~^(yes|only)$|ui-sprout|en:organic|nl:biologisch,biologische|de:bio,biologisch|fr:biologique,bio
O|now||ui-clock|en:open now,now open,open,currently open,opened|nl:nu open,open nu,open,geopend,nu geopend|de:jetzt geoffnet,jetzt offen,geoffnet,offen,hat offen|fr:ouvert,ouverte,ouvert maintenant,ouverte maintenant,actuellement ouvert
O|tonight||ui-moon|en:tonight,this evening,evening|nl:vanavond,avond|de:heute abend,abends,heute nacht|fr:ce soir,soir
O|late||ui-moon-star|en:late,open late,late night|nl:laat,laat open,nachts open|de:spat,lange offen,nachts|fr:tard,ouvert tard,tard le soir
S|distance||ui-navigation|en:nearest,closest,nearest first|nl:dichtstbijzijnde,dichtstbij,naaste|de:nachste,nachstgelegene|fr:le plus proche,la plus proche,plus proche
S|rating||ui-star|en:best,best rated,top rated,highest rated,top|nl:beste,best beoordeeld,hoogst gewaardeerd|de:beste,am besten bewertet|fr:meilleur,meilleure,mieux note
S|open_longest||ui-clock|en:open longest,open latest|nl:langst open|de:am langsten geoffnet|fr:ouvert le plus tard
C|near|||en:near,next to,close to,beside,near to,by,around|nl:bij,naast,vlakbij,nabij,in de buurt van,dicht bij,rond|de:bei,nahe,neben,in der nahe von,nahe bei|fr:pres de,pres,a cote de,proche de,autour de,aux alentours de
C|with|||en:with,has,having,that has,w|nl:met|de:mit|fr:avec,ayant
C|from|||en:from|nl:van,vanaf|de:von,vom|fr:du,des,d
C|of|||en:of|nl:of|de:|fr:de
C|within|||en:within,under,less than,at most,max,maximum|nl:binnen,hoogstens,minder dan|de:innerhalb,innerhalb von,hochstens,unter|fr:a moins de,moins de,dans un rayon de
C|not_near|||en:away from,far from,not near,not close to|nl:niet bij,ver van,weg van,uit de buurt van|de:nicht bei,nicht in der nahe von,weit weg von,weit von|fr:loin de,pas pres de
C|without|||en:without,no|nl:zonder,geen|de:ohne,kein,keine|fr:sans
C|around_me|||en:around me,near me,nearby,close by,here,near here,around here,in the area|nl:in de buurt,dichtbij,hier,om de hoek,in mijn buurt|de:in der nahe,hier,um die ecke,in meiner nahe,in der umgebung|fr:a proximite,pres d ici,autour de moi,ici,pres de moi
C|in|||en:in,inside|nl:in|de:in,im|fr:dans
C|then|||en:then,and then,after that,afterwards,followed by,after|nl:dan,daarna,en dan,vervolgens,en daarna|de:dann,danach,und dann,anschliessend|fr:puis,ensuite,et puis,apres,et ensuite
C|and|||en:and,also,as well as|nl:en,ook|de:und,auch|fr:et,aussi
C|or|||en:or|nl:|de:oder|fr:ou
C|filler|||en:a,an,the,some,any,me,i,want,need,looking,look,for,find,show,search,where,is,are,there,can,get,good,nice,place,places,spot,somewhere,to,go,please,on,at,that,which,one,it,my,our,a few,buy,purchase,sells,sell,selling|nl:een,de,het,ik,wil,zoek,zoeken,waar,is,er,graag,plek,ergens,naar,op,om,die,dat,mijn,leuk,leuke,goed,goede,toe,gaan,kopen,koop,kan,verkoopt|de:ein,eine,einen,einem,einer,der,die,das,den,dem,ich,suche,wo,ist,gibt,es,zum,zur,am,um,bitte,schon,gute,guten,gutes,kaufen,kaufe,kann|fr:un,une,le,la,les,l,je,cherche,veux,ou est,est,il,y,a,au,aux,bon,bonne,sur,pour,mon,acheter,achete,trouver,puis je
""".trimIndent()

    /** Every entry: the table above, then presets, categories, cuisines and brands. First word → entries, longest first. */
    private val index: Map<String, List<Entry>> by lazy { build() }

    private val presetCache = ConcurrentHashMap<String, Map<String, List<Entry>>>()

    /** The entries whose phrase starts at [words]`[at]`, longest phrases first. */
    fun lookup(words: List<String>, at: Int, lang: String): List<Entry> {
        val first = words[at]
        val out = ArrayList<Entry>()
        index[first]?.let { out += it }
        presets(lang)[first]?.let { out += it }
        return out.filter { e -> e.words.size <= words.size - at && e.words.indices.all { words[at + it] == e.words[it] } }
            .sortedWith(compareByDescending<Entry> { it.words.size }.thenBy { rank(it.meaning) })
    }

    /** Entries for a single word with a plural or inflected ending removed: "pharmacies", "apotheken", "cafés". */
    fun stemmed(word: String, lang: String): List<Entry> {
        if (word.length < 4) return emptyList()
        for (stem in stems(word)) {
            val e = (index[stem].orEmpty() + presets(lang)[stem].orEmpty()).filter { it.words.size == 1 && it.meaning !is Meaning.Word }
            if (e.isNotEmpty()) return e.sortedBy { rank(it.meaning) }
        }
        return emptyList()
    }

    private fun stems(w: String): List<String> = buildList {
        if (w.endsWith("ies")) add(w.dropLast(3) + "y")
        if (w.endsWith("es")) add(w.dropLast(2))
        if (w.endsWith("s") || w.endsWith("x")) add(w.dropLast(1))
        if (w.endsWith("en")) add(w.dropLast(2))
        if (w.endsWith("n") || w.endsWith("e")) add(w.dropLast(1))
    }

    /**
     * Which of several meanings of the same phrase wins, lowest first: the hand-written table (0), then its small
     * words (1, so "the" stays an article and not the French for tea), then categories, brands and goods (2),
     * cuisines (3), preset names (4) and preset search terms (5).
     */
    fun rank(m: Meaning): Int = when (m) {
        is Meaning.Noun -> m.rank
        is Meaning.Attribute -> 0
        is Meaning.Open, is Meaning.Day, is Meaning.Sort -> 0
        is Meaning.Word -> if (m.role == Meaning.Role.FILLER) 1 else 0
    }

    private fun build(): Map<String, List<Entry>> {
        val all = ArrayList<Entry>()
        for (line in TABLE.lines()) {
            if (line.isBlank()) continue
            // the payload may hold "|" (regex alternatives): the language columns are the trailing "xx:" ones
            val cols = line.split('|')
            val firstLang = cols.indexOfFirst { LANG_COLUMN.matches(it) }.let { if (it < 0) cols.size else it }
            val kind = cols[0]
            val id = cols[1]
            val symbol = cols[firstLang - 1]
            val payload = cols.subList(2, firstLang - 1).joinToString("|")
            val meaning: Meaning = when (kind) {
                "N" -> Meaning.Noun(Query.Tags(TagFilter.parse(payload)), checkedSymbol(symbol, payload), id, rank = 0)
                "A" -> Meaning.Attribute(TagFilter.parse(payload), checkedSymbol(symbol, payload), id, soft = false)
                "P" -> Meaning.Attribute(TagFilter.parse(payload), checkedSymbol(symbol, payload), id, soft = true)
                "O" -> Meaning.Open(
                    when (id) {
                        "tonight" -> OpenTime.Tonight
                        "late" -> OpenTime.Late
                        else -> OpenTime.Now
                    },
                )
                "S" -> Meaning.Sort(SortBy.valueOf(id.uppercase()))
                "C" -> Meaning.Word(Meaning.Role.valueOf(id.uppercase()))
                else -> error("lexicon kind $kind")
            }
            for (col in cols.drop(firstLang)) {
                val lang = col.substringBefore(':')
                for (phrase in col.substringAfter(':').split(',')) {
                    // split as queries are, so "24h/24" is the words "24h" and "24"
                    val ws = Words.split(phrase).map { it.text }
                    if (ws.isNotEmpty()) all += Entry(ws, meaning, lang)
                }
            }
        }
        for ((lang, names) in DAYS) for ((i, n) in names.withIndex()) for (alias in n.split(',')) {
            all += Entry(listOf(alias), Meaning.Day(DayOfWeek.of(i + 1)), lang)
        }
        for ((lang, words) in RELATIVE_DAYS) for ((i, w) in words.withIndex()) for (alias in w.split(',')) {
            all += Entry(alias.split(' '), Meaning.Day(null, i), lang)
        }
        // Atlas's categories, as kinds of places a little below the hand-picked words
        for (c in Categories.all()) for (lang in LANGS) {
            val ws = Words.split(c.name(lang)).map { it.text }.filter { it != "&" }
            if (ws.isNotEmpty()) all += Entry(ws, Meaning.Noun(Query.Category(c.id), c.symbol, "category:${c.id}", rank = 2), lang)
        }
        // cuisines: "pizza", "sushi", "ramen", and their names in the four languages
        for (v in Cuisine.known) {
            if (v in GENERIC_CUISINES) continue
            val noun = Meaning.Noun(Query.Tags(TagFilter.Equals("cuisine", v)), Cuisine.symbol(v), "cuisine:$v", rank = 3, cuisine = true)
            all += Entry(v.split('_'), noun, "en")
            for (lang in LANGS) {
                val ws = Words.split(Cuisine.name(v, lang)).map { it.text }
                if (ws.isNotEmpty()) all += Entry(ws, noun, lang)
            }
        }
        // things to buy: "picture hooks", "batterijen", "Briefmarken"
        for (g in GoodsCatalogue.all) {
            val noun = goodsNoun(g)
            for ((lang, phrases) in g.phrases) for (p in phrases) all += Entry(p.split(' '), noun, lang)
        }
        val map = HashMap<String, MutableList<Entry>>()
        for (e in all) map.getOrPut(e.words[0]) { ArrayList() }.add(e)
        return map
    }

    /** The noun that stands for [g]: all its sellers, the likelier ones ranked higher. */
    fun goodsNoun(g: Goods): Meaning.Noun = Meaning.Noun(g.query, g.symbol, "goods:${g.id}", rank = 2)

    private val goodsWords: List<Entry> by lazy {
        GoodsCatalogue.all.flatMap { g ->
            val noun = goodsNoun(g)
            g.phrases.flatMap { (lang, ps) -> ps.filter { ' ' !in it && it.length >= 5 }.map { Entry(listOf(it), noun, lang) } }
        }
    }

    /** The goods one word names with a typo or two ("baterries", "postzegls"), from five letters; the app's language first. */
    fun fuzzyGoods(word: String, lang: String): Entry? {
        if (word.length < 5) return null
        val k = app.atlas.places.Text.typosAllowed(word.length)
        var best: Entry? = null
        var bestD = k + 1
        for (e in goodsWords) {
            val d = app.atlas.places.Text.distance(word, e.words[0], k)
            if (d < bestD || (d == bestD && d <= k && e.lang == lang && best?.lang != lang)) {
                best = e
                bestD = d
            }
        }
        return if (bestD <= k) best else null
    }

    /** Preset names (rank 4) and search terms (rank 5) in [lang] and the four search languages. */
    private fun presets(lang: String): Map<String, List<Entry>> = presetCache.getOrPut(lang) {
        val langs = (listOf(lang) + LANGS).distinct()
        val map = HashMap<String, MutableList<Entry>>()
        for (p in Presets.all) {
            if (!p.searchable || p.tags.isEmpty() || p.id.startsWith("type/")) continue
            if (p.geometry.none { it == Geometry.POINT || it == Geometry.AREA || it == Geometry.VERTEX }) continue
            val filter = filterOf(p.tags) ?: continue
            val symbol = Presets.iconOf(p)?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(sample(p.tags))
            for (l in langs) {
                val noun = Meaning.Noun(Query.Tags(filter), symbol, "preset:${p.id}", rank = 4)
                val name = Words.split(p.name(l)).map { it.text }.filter { it != "&" && it != "," }
                if (name.isNotEmpty()) map.getOrPut(name[0]) { ArrayList() }.add(Entry(name, noun, l))
                val term = Meaning.Noun(Query.Tags(filter), symbol, "preset:${p.id}", rank = 5)
                for (t in p.terms(l)) {
                    val ws = Words.split(t).map { it.text }.filter { it != "&" && it != "," }
                    if (ws.isNotEmpty()) map.getOrPut(ws[0]) { ArrayList() }.add(Entry(ws, term, l))
                }
            }
        }
        map
    }

    private val brandIndex: Map<String, List<Brand>> by lazy {
        val m = HashMap<String, MutableList<Brand>>()
        for (b in Brands.all()) {
            val k = Brands.key(b.brand)
            if (k.length >= 3) m.getOrPut(k) { ArrayList(1) }.add(b)
        }
        m
    }

    /** A brand whose name is exactly [phrase] ("albert heijn", "hema"), the most widespread first; trading in [country] when given. */
    fun brand(phrase: String, country: String? = null): Brand? {
        val list = brandIndex[Brands.key(phrase)] ?: return null
        return list.filter { it.availableIn(country) }.maxByOrNull { it.spread } ?: list.maxByOrNull { it.spread }
    }

    /** The properties of the table by id ("wheelchair", "wifi", "terrace"…). */
    val attributes: Map<String, Meaning.Attribute> by lazy {
        val m = LinkedHashMap<String, Meaning.Attribute>()
        for (list in index.values) for (e in list) (e.meaning as? Meaning.Attribute)?.let { m.putIfAbsent(it.id, it) }
        m
    }

    /** The property a tag key stands for ("internet_access" → wifi), for labelling power-syntax chips. */
    val attributeByKey: Map<String, Meaning.Attribute> by lazy {
        val m = HashMap<String, Meaning.Attribute>()
        for (list in index.values) for (e in list) {
            val a = e.meaning as? Meaning.Attribute ?: continue
            val k = a.filter.keys().firstOrNull() ?: continue
            m.putIfAbsent(k, a)
        }
        m
    }

    /** The tag filter that a preset's tags spell: every tag, `*` as "any value". */
    fun filterOf(tags: Map<String, String>): TagFilter? {
        if (tags.isEmpty()) return null
        val parts = tags.map { (k, v) -> if (v == "*") TagFilter.Has(k) else TagFilter.Equals(k, v) }
        return if (parts.size == 1) parts[0] else TagFilter.And(parts)
    }

    private fun sample(tags: Map<String, String>): Map<String, String> = tags.mapValues { (_, v) -> if (v == "*") "yes" else v }

    private fun checkedSymbol(symbol: String, payload: String): String {
        if (Symbols.has(symbol)) return symbol
        val f = TagFilter.parseOrNull(payload) ?: return Symbols.MARKER
        return Symbols.symbolFor(sampleOf(f))
    }

    /** Some tags that pass a simple filter, for picking its symbol. */
    fun sampleOf(f: TagFilter): Map<String, String> = when (f) {
        is TagFilter.Equals -> mapOf(f.key to f.value)
        is TagFilter.Has -> mapOf(f.key to "yes")
        is TagFilter.Matches -> mapOf(f.key to (Regex("""\^?\(?([A-Za-z0-9_:]+)""").find(f.pattern)?.groupValues?.get(1) ?: "yes"))
        is TagFilter.And -> f.parts.fold(emptyMap()) { acc, p -> acc + sampleOf(p) }
        is TagFilter.Or -> f.parts.firstOrNull()?.let { sampleOf(it) } ?: emptyMap()
        else -> emptyMap()
    }

    private val LANG_COLUMN = Regex("^[a-z]{2}:.*")

    /** Cuisine values too vague to be a kind of food on their own. */
    private val GENERIC_CUISINES = setOf("local", "regional", "international", "fine_dining", "various", "traditional", "yes", "no", "other")

    private val DAYS = mapOf(
        "en" to listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"),
        "nl" to listOf("maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag"),
        "de" to listOf("montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag,sonnabend", "sonntag"),
        "fr" to listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"),
    )

    private val RELATIVE_DAYS = mapOf(
        "en" to listOf("today", "tomorrow"),
        "nl" to listOf("vandaag", "morgen"),
        "de" to listOf("heute", "morgen"),
        "fr" to listOf("aujourd hui,aujourdhui", "demain"),
    )
}
