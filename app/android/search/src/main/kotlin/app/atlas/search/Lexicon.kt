package app.atlas.search

import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.ChangeKind
import app.atlas.places.CompareOp
import app.atlas.places.Cuisine
import app.atlas.places.Geometry
import app.atlas.places.KindNames
import app.atlas.places.Kinds
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/** What a phrase of a natural-language query means. */
internal sealed class Meaning {
    /** A kind of place: "café", "bike dock", "apotheek". [cuisine] marks food words that refine another kind. */
    class Noun(val query: Query, val symbol: String, val id: String, val rank: Int, val cuisine: Boolean = false) : Meaning()

    /**
     * A property: "wifi", "step-free", "vegan"; [soft] ones only rank higher ("quiet", "cheap"), [want]ed ones keep
     * the places that do not say ("air conditioning", "wooden": rarely mapped) after those that do; with a [prefer]
     * the places that confirm more rank first ("free wifi": wifi, those that say it is free first).
     */
    class Attribute(val filter: TagFilter, val symbol: String, val id: String, val soft: Boolean, val want: Boolean = false, val prefer: TagFilter? = null) : Meaning()

    /** An opening time; a word that names the place too ("koopavond": the shops, Thursday evening) has its [noun]. */
    class Open(val time: OpenTime, val noun: Noun? = null) : Meaning()

    /** A day: a weekday, today or tomorrow ([offset]), or the [weekend] (Saturday or Sunday). */
    class Day(val day: DayOfWeek?, val offset: Int = 0, val weekend: Boolean = false) : Meaning()

    class Sort(val by: SortBy) : Meaning()

    /**
     * A comparison: "before", "older than", "taller than", "at least"; the value after it says what it compares
     * (a year, a time, a height, kW, euros, stars), [dim] when the word itself does. "after" before a kind of place
     * is "then" ([then]); "newly built" needs no value ([Dim.NEW]).
     */
    class Compare(val op: CompareOp, val dim: Dim?, val then: Boolean = false) : Meaning()

    /** What a comparison or a measure word is about. */
    enum class Dim { YEAR, AGE, TALL, ELEVATION, PRICE, NEW, RATING }

    /** "tall", "high", "altitude": a length next to it is a height or an elevation. */
    class Measure(val dim: Dim) : Meaning()

    /** "built", "gebouwd": a year after it is when the place was built. */
    data object Built : Meaning()

    /** "closes", "opens": a time after it is when places close or open. */
    class Change(val kind: ChangeKind) : Meaning()

    /** "how many", "opening times", "how tall is": what the query asks for, for a fact the tag [key] asked, and its [symbol]. */
    class AskFor(val ask: Ask, val key: String? = null, val symbol: String? = null) : Meaning()

    /** "highly rated", "goed beoordeeld": a rating [op] [value]. */
    class Rating(val op: CompareOp, val value: Double) : Meaning()

    /** "soon", "in an hour": that many minutes from now. */
    class Later(val minutes: Int) : Meaning()

    /** "within walking distance", "op fietsafstand": a circle of that many metres around the searcher. */
    class Radius(val metres: Double) : Meaning()

    /** A named area said otherwise ("mokum", "a'dam": Amsterdam), or the searcher's own ([Query.InArea.NEIGHBOURHOOD]). */
    class Area(val name: String) : Meaning()

    /** "this town", "in town": the town the searcher is in. */
    data object Town : Meaning()

    /** Something to do there ("to swim", "for a picnic"): an [Intents] id; a word that names the place too ("Badesee": a lake) has its [noun]. */
    class Aim(val id: String, val noun: Noun? = null) : Meaning()

    /** A colour family ("red", "rood", "rouge"): the place's own, its building's or its roof's. */
    class Colour(val family: String) : Meaning()

    /** Connectors and small words, by role. */
    class Word(val role: Role) : Meaning()

    /**
     * [SOFT] makes what follows a wish ("maybe an ATM nearby"); [APPROX] a time or year about ("around 19:30");
     * [UNTIL] a time the place stays open to. [AT] and [ON] put a place by another or in an area ("at Schiphol",
     * "on the beach", "op de Albert Cuypstraat"); [HAVE] is "with", or in a question what is asked ("does it have
     * toilets"); [CLOSED] turns the opening time after it round ("closed on Monday"); [OR_MORE] and [OR_LESS] turn the
     * amount before them into a least or a most ("4 stars or more"); [GOES] starts a verb whose end comes later ("gaat
     * om 8 uur open", "macht um 22 Uhr zu"); [PER] tells a count by place ("how many bars per neighbourhood"); [OUTSIDE]
     * keeps away from the area after it ("outside Amsterdam", "außerhalb der Innenstadt").
     */
    enum class Role { NEAR, WITH, FROM, WITHIN, NOT_NEAR, WITHOUT, AROUND_ME, IN, THEN, AND, OR, OF, SOFT, APPROX, UNTIL, AT, ON, HAVE, CLOSED, OR_MORE, OR_LESS, GOES, PER, OUTSIDE, FILLER }
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

    // kind|id|payload|symbol|lang:phrase,phrase|…   N noun (its payload a filter, then " ; " wishes: a want, "P:" a
    // preference, "N:filter@metres" a place it stands by), K cuisine noun, A attribute, P preference, W wanted
    // attribute (A, P and W with " ; P:" a preference after), O open (an id with a clock is that time today, with a day too that day; a payload is the place the
    // word names), S sort, R comparison ("OP [DIM] [then]"), M measure word, B built, X opening or closing, Q what is
    // asked ("FACT key": a fact of a place), G rating ("OP value"), L minutes from now, D metres around, Z an area by
    // another name, T the town, C connector
    private val TABLE = """
N|cafe|amenity=cafe|maki-cafe|en:coffee,cafe,cafes,coffee shop,coffee bar,espresso bar,tea room|nl:koffie,koffiezaak,koffiebar,lunchroom,kopje koffie|de:kaffee,cafe,kaffeehaus,kaffeebar|fr:cafe,cafes,salon de the
N|restaurant|amenity=restaurant|maki-restaurant|en:restaurant,restaurants,dinner,supper,dine,dining,eat out|nl:restaurant,restaurants,diner,avondeten,uit eten,dineren|de:restaurant,restaurants,abendessen,essen gehen,gaststatte|fr:restaurant,restaurants,diner,resto,restos
N|museum|tourism=museum|maki-museum|nl:musea|de:museen|fr:musees
N|lunch|amenity~^(restaurant|cafe)$|maki-restaurant|en:lunch|nl:lunch,lunchen|de:mittagessen,mittag|fr:dejeuner
N|breakfast|amenity~^(cafe|restaurant)$ ; breakfast~^(yes|buffet|only)$|maki-cafe|en:breakfast|nl:ontbijt,ontbijten|de:fruhstuck,fruhstucken|fr:petit dejeuner
N|food|amenity~^(restaurant|fast_food|cafe|food_court)$|maki-restaurant|en:food,eat,something to eat,a bite,bite to eat|nl:eten,iets eten,hapje|de:essen,etwas essen|fr:manger,nourriture,quelque chose a manger
N|fast_food|amenity=fast_food|maki-fast-food|en:fast food,takeaway food,snack,snacks|nl:snackbar,snack,cafetaria|de:imbiss,schnellimbiss|fr:fast food,restauration rapide
N|bar|amenity~^(bar|pub|biergarten)$|maki-bar|en:bar,bars,drinks,a drink,drink,beer,beers|nl:kroeg,kroegen,bruin cafe,borrel,biertje,drankje|de:kneipe,kneipen,bier|fr:bar,bars,biere,un verre
N|bicycle_rental|amenity=bicycle_rental|temaki-bicycle_rental|en:bike dock,bike docks,bike share,bike sharing,bike rental,bike rentals,bicycle rental,rent a bike,rent bikes,bike to rent,bikes to rent,hire a bike,dock,docks,cycle hire,bike hire|nl:fietsverhuur,deelfiets,fiets huren,fietsen huren,huurfiets,fiets te huur|de:fahrradverleih,leihrad,mietrad,radverleih,fahrrad mieten,fahrrad leihen,rad mieten|fr:location de velos,location de velo,velo en libre service,velib
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
N|parking|amenity=parking|maki-parking|en:parking,car park,park the car|nl:parkeren,parkeerplaats|de:parkplatz,parken|fr:parking,stationnement
N|bakery|shop=bakery|maki-bakery|en:bakery,bread|nl:bakker,bakkerij,brood|de:backerei,backer,brotchen|fr:boulangerie,pain
N|ice_cream|amenity=ice_cream or shop=ice_cream|maki-ice-cream|en:ice cream,gelato|nl:ijs,ijssalon,ijsje|de:eis,eisdiele|fr:glace,glacier,glaces
N|drinking_water|amenity=drinking_water|maki-drinking-water|en:drinking water,water fountain,water tap,refill water|nl:drinkwater,watertappunt,kraanwater|de:trinkwasser,trinkbrunnen|fr:eau potable,fontaine
N|post_box|amenity=post_box|maki-post|en:mailbox,post box,postbox,letter box,post a letter,send a letter,mail a letter|nl:brievenbus,brief posten,brief versturen|de:briefkasten,brief einwerfen|fr:boite aux lettres,poster une lettre
N|post_office|amenity=post_office|maki-post|en:post office|nl:postkantoor,postpunt|de:post,postamt,postfiliale|fr:poste,la poste,bureau de poste
N|stay|tourism~^(hotel|hostel|guest_house|apartment|motel)$|maki-lodging|en:place to sleep,somewhere to sleep,stay,accommodation|nl:overnachten,slapen,logeren|de:ubernachten,unterkunft|fr:dormir,hebergement,logement
N|doctors|amenity=doctors|maki-doctor|en:doctor,doctors,gp,out of hours doctor|nl:huisarts,dokter,huisartsenpost,huisartsenpraktijk|de:arzt,hausarzt,arztpraxis|fr:medecin,docteur,cabinet medical
N|fuel|amenity=fuel|maki-fuel|en:petrol,gas station,petrol station,fuel|nl:tankstation,benzine,tanken|de:tankstelle,tanken|fr:station service,essence,carburant
N|bus_stop|highway=bus_stop|maki-bus|en:bus stop,bus|nl:bushalte|de:bushaltestelle,bushalt|fr:arret de bus
N|night_bus|(highway=bus_stop or public_transport=platform and bus=yes) and route_ref~^N[0-9]+[A-Za-z]?$|maki-bus|en:night bus,night buses,nightbus,night bus stop,night bus stops|nl:nachtbus,nachtbussen,nachtbushalte|de:nachtbus,nachtbusse,nachtbushaltestelle|fr:bus de nuit,noctilien
N|tram_stop|railway=tram_stop|maki-rail-light|en:tram stop,tram|nl:tramhalte|de:strassenbahnhaltestelle|fr:arret de tram
N|station|railway~^(station|halt)$|maki-rail|en:train station,station,railway station,rail station,rail stations,train,trains,take the train,catch the train,catch a train|nl:station,treinstation,de trein nemen,de trein pakken,trein pakken|de:bahnhof,den zug nehmen,zug nehmen|fr:gare,prendre le train
N|gym|leisure=fitness_centre|maki-fitness-centre|en:gym,fitness|nl:sportschool|de:fitnessstudio|fr:salle de sport
N|bench|amenity=bench|temaki-bench|en:bench,benches|nl:bankje,bankjes|de:sitzbank|fr:banc,bancs
N|picnic|leisure=picnic_table|maki-picnic-site|en:picnic,picnic spot,picnic table|nl:picknick,picknickplek,picknicktafel|de:picknick,picknicktisch|fr:pique nique,table de pique nique
N|laundry|shop=laundry|maki-laundry|en:laundry|nl:wasserij|de:wascherei|fr:blanchisserie
N|rail|railway~^(rail|light_rail|subway|narrow_gauge)$|maki-rail|en:rail,rails,railway,railways,railway line,railway lines,railway track,railway tracks,train track,train tracks,tracks,rail line,rail lines,rail track,rail tracks|nl:spoor,spoorlijn,spoorlijnen,spoorbaan,spoorrails,treinspoor,spoorweg,spoorwegen|de:gleis,gleise,bahnstrecke,bahnstrecken,schienen,bahngleis,bahngleise,eisenbahnstrecke|fr:voie ferree,voies ferrees,rails,chemin de fer,ligne de chemin de fer,voie de chemin de fer
N|underpass|highway and tunnel=yes|maki-roadblock|en:underpass,underpasses,pedestrian tunnel,cycle tunnel,bike tunnel,road tunnel,tunnel,tunnels|nl:onderdoorgang,onderdoorgangen,fietstunnel,voetgangerstunnel,tunnel,tunneltje|de:unterfuhrung,unterfuhrungen,fussgangertunnel,radtunnel,tunnel|fr:passage souterrain,passages souterrains,passage inferieur,tunnel,tunnels
N|ferry|amenity=ferry_terminal|maki-ferry|en:ferry|nl:pont,veerpont,veer|de:fahre|fr:bac,ferry
W|wifi|internet_access~^(wlan|yes|wifi|public|terminal|wired)$|ui-wifi|en:wifi,wi fi,wlan,internet,internet access|nl:wifi,internet|de:wlan,wifi,internet|fr:wifi,internet
W|outlet|power_supply and power_supply!=no|ui-plug|en:outlet,outlets,power outlet,power outlets,plug,plugs,socket,sockets,power,electricity,electric hookup,electrical hookup,hookup|nl:stopcontact,stopcontacten,stekker,stroom,elektriciteit,stroomaansluiting|de:steckdose,steckdosen,strom,stromanschluss|fr:prise,prises,prise electrique,electricite
A|terrace|outdoor_seating=yes|ui-sun|en:terrace,outdoor seating,outside,outdoors,patio|nl:terras,terrasje,buiten,buitenterras|de:terrasse,draussen,aussen|fr:terrasse,dehors,en terrasse
W|garden|outdoor_seating=garden or beer_garden=yes or garden=yes|ui-trees|en:garden,beer garden|nl:tuin,biertuin|de:garten,biergarten|fr:jardin
P|view|view or outdoor_seating=roof|ui-eye|en:view,a view,nice view|nl:uitzicht|de:aussicht|fr:vue
W|wheelchair|wheelchair~^(yes|designated)$|ui-wheelchair|en:wheelchair,wheelchair accessible,wheelchair access,wheelchair friendly,accessible by wheelchair,step free,stepfree,step free access,accessible,barrier free|nl:rolstoel,rolstoeltoegankelijk,rolstoeltoegankelijke,rolstoel toegankelijk,toegankelijk,toegankelijke,drempelvrij,drempelvrije|de:rollstuhl,rollstuhlgerecht,rollstuhlgerechte,rollstuhlgerechter,rollstuhlgerechtes,rollstuhlgerechten,rollstuhlzugang,rollstuhlgeeignet,rollstuhlgeeignete,barrierefrei,barrierefreie,barrierefreier,barrierefreies,barrierefreien,stufenlos|fr:fauteuil roulant,accessible,pmr,accessible en fauteuil,accessible fauteuil roulant,accessible en fauteuil roulant,acces fauteuil roulant,acces pmr,accessible aux fauteuils roulants
A|vegan|diet:vegan~^(yes|only)$|ui-vegan|en:vegan,plant based|nl:vegan,veganistisch,veganistische,plantaardig|de:vegan,vegane,veganes,veganer|fr:vegan,vegane,vegetalien,vegetalienne
A|vegetarian|diet:vegetarian~^(yes|only)$ or diet:vegan~^(yes|only)$|ui-sprout|en:vegetarian,veggie,meatless,meat free,no meat,without meat|nl:vegetarisch,vegetarische,zonder vlees,vleesloos|de:vegetarisch,vegetarische,vegetarisches,ohne fleisch,fleischlos|fr:vegetarien,vegetarienne,sans viande
A|halal|diet:halal~^(yes|only)$|ui-check|en:halal|nl:halal|de:halal|fr:halal
A|kosher|diet:kosher~^(yes|only)$|ui-check|en:kosher|nl:koosjer|de:koscher|fr:casher,cacher
A|gluten_free|diet:gluten_free~^(yes|only)$|ui-wheat-off|en:gluten free,glutenfree|nl:glutenvrij,glutenvrije|de:glutenfrei,glutenfreie|fr:sans gluten
A|lactose_free|diet:lactose_free~^(yes|only)$|ui-milk-off|en:lactose free,dairy free|nl:lactosevrij|de:laktosefrei|fr:sans lactose
W|free|fee=no|ui-coins|en:free,free of charge,no fee,free entry,free admission,free entrance,no charge,for free|nl:gratis,gratis toegang,gratis entree,vrije toegang,kosteloos|de:kostenlos,kostenlose,kostenloser,kostenloses,kostenlosen,gratis,freier eintritt,eintritt frei,kostenloser eintritt,umsonst|fr:gratuit,gratuite,gratuits,gratuites,entree gratuite,entree libre,acces gratuit
P|quiet|live_music!=yes and amenity!~^(bar|pub|nightclub)$|ui-headphones|en:quiet,calm,peaceful|nl:rustig,rustige,stil,stille|de:ruhig,ruhige,leise|fr:calme,tranquille
P|untouristy|brand!~.|ui-users|en:not touristy,not too touristy,aren t touristy,isn t touristy,are not touristy,is not touristy,aren t too touristy,isn t too touristy,untouristy,non touristy,less touristy,off the beaten track,off the beaten path,away from tourists,away from the tourists,without tourists,few tourists,no tourists,where locals go,where the locals go,locals,local favourite,local favorite,like a local,authentic|nl:niet toeristisch,niet te toeristisch,minder toeristisch,weinig toeristen,zonder toeristen,geen toeristen,waar locals komen,locals,authentiek,authentieke|de:nicht touristisch,nicht so touristisch,wenig touristisch,untouristisch,ohne touristen,wenig touristen,abseits der touristenpfade,wo einheimische hingehen,einheimische,authentisch,authentische|fr:pas touristique,peu touristique,moins touristique,loin des touristes,sans touristes,peu de touristes,hors des sentiers battus,comme un local,authentique
P|uncrowded|tourism!=attraction|ui-users|en:not crowded,not too crowded,isn t crowded,aren t crowded,is not crowded,are not crowded,isn t too crowded,isn t busy,is not busy,uncrowded,not busy,not too busy,less crowded,less busy,few people,not many people,secluded,hidden gem,hidden gems,secret spot,quiet spot|nl:niet druk,niet te druk,minder druk,weinig mensen,afgelegen,verborgen parel,geheim plekje,rustig plekje|de:nicht uberlaufen,nicht so voll,nicht zu voll,wenig los,wenig leute,abgelegen,geheimtipp,versteckt|fr:pas bonde,pas trop de monde,peu de monde,peu frequente,isole,isolee,coin secret,perle cachee
P|sun|outdoor_seating=yes or leisure~^(park|beach_resort)$ or natural=beach|ui-sun|en:in the sun,in the sunshine,sunny,sun terrace,sunny terrace,sunny spot|nl:in de zon,in het zonnetje,zonnig,zonnige,zonneterras,zonnig terras|de:in der sonne,sonnig,sonnige,sonnenterrasse|fr:au soleil,ensoleille,ensoleillee,terrasse ensoleillee
P|kids|kids_area=yes or highchair=yes or changing_table=yes|ui-baby|en:kid friendly,kids,child friendly,family friendly,with kids|nl:kindvriendelijk,kinderen,met kinderen|de:kinderfreundlich,kinder,mit kindern|fr:enfants,adapte aux enfants
W|dogs|dog~^(yes|leashed|unleashed|outside)$|ui-dog|en:dog friendly,dogs allowed,dogs welcome,dogs,dog,allows dogs,allow dogs,with my dog,with the dog|nl:hondvriendelijk,hondvriendelijke,honden toegestaan,honden welkom,honden,hond,met de hond,met hond|de:hundefreundlich,hundefreundliche,hundefreundliches,hundefreundlichen,hunde erlaubt,hunde willkommen,hunde,hund,mit hund,mit dem hund|fr:chiens acceptes,chiens admis,chiens bienvenus,chiens,chien,avec mon chien,avec chien
A|takeaway|takeaway~^(yes|only)$|ui-shopping-bag|en:takeaway,take away,to go,takeout|nl:afhalen,meenemen|de:zum mitnehmen,mitnehmen|fr:a emporter
A|delivery|delivery=yes|ui-truck|en:delivery,deliver|nl:bezorgen,bezorging|de:lieferung,lieferservice|fr:livraison
A|smoking|smoking~^(yes|outside|separated|isolated|dedicated)$|ui-smoking|en:smoking,smokers,smoking allowed,smoke,can smoke|nl:roken,rokers,roken toegestaan,rookruimte|de:rauchen,raucher,rauchen erlaubt|fr:fumeur,fumeurs
W|non_smoking|smoking=no|ui-smoke-off|en:non smoking,no smoking,smoke free,smokefree,nonsmoking|nl:rookvrij,rookvrije,niet roken|de:rauchfrei,rauchfreie,rauchfreies,nichtraucher|fr:non fumeur,non fumeurs,sans fumee,sans tabac
W|always_open|opening_hours~^(24/7|(Mo-Su.)?00:00-(24|00):00)$|ui-clock|en:24/7,24 7,always open,24 hours,24 hour,24hr,24hrs,24h,open 24 hours,open 24 7,open 24/7,24 hours a day,day and night,round the clock|nl:dag en nacht,24 uur,24 uur open,24 uur per dag,dag en nacht open,altijd open|de:rund um die uhr,24 stunden,24 stunden geoffnet,24 stunden offen,immer offen,immer geoffnet|fr:24h/24,24 heures,24 heures sur 24,24/24,ouvert 24h/24,jour et nuit
A|fast|socket:type2_combo or socket:chademo or socket:tesla_supercharger|ui-bolt|en:fast,rapid|nl:snel,snelladen|de:schnell,schnellladen|fr:rapide
A|organic|organic~^(yes|only)$|ui-sprout|en:organic|nl:biologisch,biologische|de:bio,biologisch|fr:biologique,bio
O|now||ui-clock|en:open now,now open,open,now,right now,currently open,opened,still open,open right now,opn,opn now|nl:nu open,open nu,open,nu,geopend,nu geopend,nog open,nu nog open,dienstdoende,dienstdoend|de:jetzt geoffnet,jetzt offen,geoffnet,offen,jetzt,hat offen,noch offen,noch geoffnet,hat noch offen,notdienst,im notdienst,notdienstapotheke|fr:ouvert,ouverte,maintenant,ouvert maintenant,ouverte maintenant,actuellement ouvert,encore ouvert,encore ouverte,ouverts,ouvertes,de garde,de service
O|tonight||ui-moon|en:tonight,this evening,evening,2nite,tonite,2night|nl:vanavond,avond|de:heute abend,abends,heute nacht|fr:ce soir,soir
O|late||ui-moon-star|en:late,open late,late night,late at night|nl:laat,laat open,nachts open,tot laat|de:spat,lange offen,nachts,spat abends,bis spat|fr:tard,ouvert tard,tard le soir,de nuit
S|distance||ui-navigation|en:nearest,closest,nearest first,how far,how far is,how far away,how far away is|nl:dichtstbijzijnde,dichtstbij,naaste,dichtsbijzijnde,dichtbijzijnde,dichtstbijzijnd,dichtsbijzijnd,hoe ver,hoe ver is,hoever|de:nachste,nachster,nachstes,nachsten,nachstgelegene,nachstgelegener,nachstgelegenes,wie weit,wie weit ist|fr:le plus proche,la plus proche,plus proche,les plus proches,a quelle distance
S|rating||ui-star|en:best,best rated,top rated,highest rated,top|nl:beste,best beoordeeld,hoogst gewaardeerd|de:beste,am besten bewertet|fr:meilleur,meilleure,mieux note
S|open_longest||ui-clock|en:open longest,open latest|nl:langst open|de:am langsten geoffnet|fr:ouvert le plus tard
N|church|amenity=place_of_worship and religion=christian or building~^(church|cathedral|chapel)$|ui-church|en:church,churches,chapel,chapels,basilica|nl:kerk,kerken,kapel,kapellen,kerkgebouw,basiliek|de:kirche,kirchen,kapelle,kapellen,basilika|fr:eglise,eglises,chapelle,chapelles,basilique
N|mosque|amenity=place_of_worship and religion=muslim or building=mosque|maki-religious-muslim|en:mosque,mosques|nl:moskee,moskeeen|de:moschee,moscheen|fr:mosquee,mosquees
N|synagogue|amenity=place_of_worship and religion=jewish or building=synagogue|maki-religious-jewish|en:synagogue,synagogues|nl:synagoge,synagoges,sjoel|de:synagoge,synagogen|fr:synagogue,synagogues
N|temple|amenity=place_of_worship and religion~^(buddhist|hindu|sikh|shinto|taoist|jain)$ or building=temple|maki-place-of-worship|en:temple,temples|nl:tempel,tempels|de:tempel|fr:temple,temples
N|worship|amenity=place_of_worship|maki-place-of-worship|en:place of worship,places of worship|nl:gebedshuis,gebedshuizen|de:gotteshaus,gebetshaus|fr:lieu de culte,lieux de culte
N|wood|natural=wood or landuse=forest|ui-trees|en:woods,wood,forest,forests,woodland,woodlands|nl:bos,bossen,woud,bosgebied|de:wald,walder,forst,waldgebiet|fr:foret,forets,bois
N|waste_basket|amenity=waste_basket|ui-trash|en:trashcan,trash can,trash cans,trash bin,trash bins,bin,bins,litter bin,litter bins,garbage can,garbage cans,rubbish bin,rubbish bins,waste basket,waste bin,dustbin|nl:prullenbak,prullenbakken,afvalbak,afvalbakken,vuilnisbak,vuilnisbakken,prullenbakje|de:mulleimer,abfalleimer,abfallkorb,papierkorb,abfallbehalter|fr:poubelle,poubelles,corbeille,corbeilles
N|recycling|amenity=recycling|ui-recycle|en:recycling,recycling point,recycle,where to recycle|nl:recyclingpunt,recyclen,recycling|de:wertstoffhof,recyclinghof,recyceln,recycling|fr:point de recyclage,recyclage,recycler
N|mall|shop=mall|maki-shop|en:mall,malls,shopping mall,shopping malls,shopping centre,shopping center,shopping centres,shopping centers|nl:winkelcentrum,winkelcentra,overdekt winkelcentrum|de:einkaufszentrum,einkaufszentren,shoppingcenter,einkaufspassage|fr:centre commercial,centres commerciaux,galerie marchande
N|shop|shop|ui-store|en:shop,shops,store|nl:winkel,winkels|de:geschaft,geschafte,laden|fr:magasin,magasins,boutique,boutiques
N|building|building|ui-building|en:building,buildings|nl:gebouw,gebouwen,pand,panden|de:gebaude,bauwerk,bauwerke|fr:batiment,batiments,edifice,edifices,immeuble,immeubles
N|lake|natural=water and water!~^(river|canal|stream|ditch|drain|moat|wastewater|stream_pool|lock|fish_pass|basin|fountain|reflecting_pool)$|ui-waves|en:lake,lakes,pond,ponds|nl:meer,meren,vijver,vijvers|de:teich,teiche,weiher,baggersee,stausee|fr:lac,lacs,etang,etangs
N|peak|natural~^(peak|volcano|hill)$|ui-mountain|en:peak,peaks,mountain,mountains,summit,summits,hilltop,hilltops|nl:berg,bergen,bergtop,bergtoppen,piek,pieken|de:berg,berge,gipfel,bergspitze|fr:sommet,sommets,montagne,montagnes,pic,pics
N|hotel|tourism=hotel|maki-lodging|en:hotel,hotels|nl:hotel,hotels|de:hotel,hotels|fr:hotel,hotels
A|backrest|backrest=yes|ui-sofa|en:backrest,back rest,with a back|nl:rugleuning,met rugleuning|de:ruckenlehne,lehne|fr:dossier,avec dossier
A|covered|covered=yes or shelter=yes|ui-umbrella|en:covered,sheltered,roofed|nl:overdekt,overdekte|de:uberdacht,uberdachte,uberdachter,uberdachtes|fr:couvert,couverte,abrite,abritee
A|lit|lit=yes|ui-lightbulb|en:lit,illuminated,with lighting|nl:verlicht,verlichte|de:beleuchtet,beleuchtete|fr:eclaire,eclairee
W|aircon|air_conditioning=yes|ui-air-vent|en:air conditioning,air conditioned,aircon,air con|nl:airco,airconditioning|de:klimaanlage,klimatisiert|fr:climatisation,climatise,climatisee,clim
W|changing_table|changing_table=yes|ui-baby|en:changing table,baby changing,baby changing table,nappy changing,diaper changing|nl:verschoontafel,commode,luiertafel|de:wickeltisch,wickelraum|fr:table a langer
W|card|payment:debit_cards=yes or payment:credit_cards=yes or payment:cards=yes or payment:maestro=yes or payment:visa=yes or payment:mastercard=yes|ui-card|en:pay by card,card payment,card payments,credit card,credit cards,debit card,accepts cards,cards accepted|nl:pinpas,betalen met pin,met pin,pin betalen,creditcard|de:kartenzahlung,ec karte,kreditkarte,mit karte|fr:carte bancaire,cb,carte de credit,paiement par carte,carte bleue
W|cash_payment|payment:cash=yes|ui-cash|en:pay cash,cash payment,accepts cash,cash only|nl:contant,contant betalen,cash betalen|de:barzahlung,bar zahlen,bargeldzahlung|fr:especes,paiement en especes
W|contactless|payment:contactless=yes|ui-contactless|en:contactless,tap to pay|nl:contactloos,contactloos betalen|de:kontaktlos|fr:sans contact
W|wooden|material=wood or building:material=wood or building:material=timber_framing|ui-tree|en:wooden,timber,made of wood|nl:houten,van hout|de:holzern,holzerne,aus holz|fr:en bois
W|brick|building:material=brick or material=brick|ui-building|en:brick,red brick|nl:baksteen,bakstenen|de:backstein,ziegel,backsteinbau|fr:brique,en brique
W|stone|building:material=stone or material=stone or building:material=sandstone or building:material=limestone|ui-landmark|en:stone,made of stone|nl:stenen,natuursteen,van steen|de:steinern,steinerne,aus stein|fr:en pierre
W|glass|building:material=glass or material=glass|ui-cube|en:glass,glass facade|nl:glazen,van glas|de:glasern,aus glas|fr:en verre
W|rooftop|outdoor_seating=roof or rooftop=yes|ui-building|en:rooftop,roof terrace,rooftop terrace,on the roof|nl:dakterras,op het dak|de:dachterrasse,dachbar,auf dem dach|fr:toit terrasse,sur le toit
O|07:00||ui-sunrise|en:early,early morning,early in the morning|nl:vroeg,vroeg open,vroeg in de ochtend,s ochtends vroeg|de:fruh,fruhmorgens,fruh am morgen,am fruhen morgen|fr:tot le matin,de bonne heure
O|09:00||ui-sun|en:morning,in the morning,this morning,mornings|nl:ochtend,s ochtends,vanochtend,s morgens|de:morgens,vormittags,am morgen,heute morgen,am vormittag|fr:matin,le matin,ce matin,en matinee
O|15:00||ui-sun|en:afternoon,in the afternoon,this afternoon|nl:middag,s middags,vanmiddag|de:nachmittags,am nachmittag,heute nachmittag|fr:apres midi,cet apres midi,l apres midi
O|01:00||ui-moon|en:at night,night,overnight,after midnight|nl:s nachts,nacht,na middernacht|de:in der nacht,nach mitternacht|fr:la nuit,de nuit,apres minuit
O|03:00||ui-moon|en:middle of the night,in the middle of the night|nl:midden in de nacht|de:mitten in der nacht|fr:en pleine nuit,au milieu de la nuit
S|oldest||ui-history|en:oldest,most historic|nl:oudste|de:alteste,altesten,altester,altestes|fr:le plus ancien,la plus ancienne,les plus anciens,les plus anciennes,plus ancien,plus ancienne,le plus vieux,la plus vieille
S|newest||ui-sparkles|en:newest,most recent,most recently built,youngest|nl:nieuwste,recentste,jongste|de:neueste,neuesten,neuester,jungste,jungsten|fr:le plus recent,la plus recente,plus recent,plus recente,le plus neuf
S|tallest||ui-ruler|en:tallest
S|highest||ui-mountain|en:highest,highest point,highest points,highest elevation,highest area,highest areas,highest spot,highest ground,highest place,highest places,high ground|nl:hoogste,hoogst gelegen,hoogste punt,hoogste plek,hoogste gebied,hoogste plekken|de:hochste,hochsten,hochster,hochstes,hochstgelegene,hochster punkt,hochste stelle,hochste erhebung|fr:le plus haut,la plus haute,plus haut,plus haute,le plus eleve,la plus elevee,point culminant,point le plus haut,point le plus eleve,endroit le plus haut
S|cheapest||ui-coins|en:cheapest,least expensive,lowest price,cheap,inexpensive,budget,affordable,low cost,cheap eats|nl:goedkoopste,goedkoop,goedkope,voordelig,voordelige,betaalbaar,betaalbare|de:billigste,billigsten,gunstigste,gunstigsten,preiswerteste,billig,billige,billiges,billigen,gunstig,gunstige,gunstiges,gunstigen,preiswert,preiswerte|fr:le moins cher,la moins chere,les moins chers,moins cher,moins chere,pas cher,pas chere,pas chers,bon marche,petit prix
S|largest||ui-cube|en:largest,biggest,largest buildings|nl:grootste|de:grosste,grossten,grosster,grosstes|fr:le plus grand,la plus grande,les plus grands,plus grand,plus grande
S|most_hours||ui-hourglass|en:longest opening time,longest opening times,longest opening hours,longest hours,most opening hours,open the longest,open longest today|nl:langste openingstijd,langste openingstijden,langst geopend,langste openingsuren|de:langste offnungszeit,langsten offnungszeiten,langste offnungszeiten,am langsten offen|fr:horaires les plus longs,ouvert le plus longtemps,plus longues heures d ouverture
S|closes_latest||ui-moon|en:closes latest,closing latest,latest closing,closes the latest,open until latest,closes last,closes the last,last to close|nl:sluit het laatst,sluit als laatste,laatst sluitend,sluit het laatste|de:schliesst am spatesten,schliesst zuletzt,schliesst als letztes|fr:ferme le plus tard,ferme en dernier
S|opens_next||ui-timer|en:opens next,opens soonest,opening soonest,next to open,first to open|nl:gaat het eerst open,opent het eerst,eerstvolgend open|de:offnet als nachstes,offnet zuerst,als nachstes offen|fr:ouvre le plus tot possible,prochaine ouverture,ouvre en premier
S|opens_latest||ui-sun|en:opens latest,opens the latest,opens last,opens the last,last to open,latest opening,latest to open,opening latest|nl:opent het laatst,gaat het laatst open,opent als laatste,het laatst open|de:offnet am spatesten,offnet zuletzt,offnet als letztes,am spatesten geoffnet|fr:ouvre le plus tard,ouvre en dernier,ouvrent le plus tard,derniere a ouvrir
S|smallest||ui-cube|en:smallest,tiniest,narrowest,littlest|nl:kleinste,smalste|de:kleinste,kleinsten,kleinster,kleinstes,schmalste,schmalsten|fr:le plus petit,la plus petite,les plus petits,les plus petites,plus petit,plus petite,le plus etroit,la plus etroite
S|longest||ui-ruler|en:longest|nl:langste|de:langste,langsten,langster,langstes|fr:le plus long,la plus longue,les plus longs,les plus longues,plus long,plus longue
S|power||ui-bolt|en:most powerful,fastest,fastest charging,quickest charging,highest power,most power,most kw|nl:snelste,krachtigste,snelst ladende,meeste vermogen,hoogste vermogen|de:schnellste,schnellsten,schnellster,starkste,starksten,starkster,meiste leistung,hochste leistung|fr:le plus puissant,la plus puissante,les plus puissants,les plus puissantes,le plus rapide,la plus rapide,les plus rapides
S|stars||ui-star|en:most stars,highest stars,most starred,highest star rating,best stars|nl:meeste sterren,hoogste sterren|de:meisten sterne,meiste sterne,hochste sterne,meisten sternen|fr:le plus d etoiles,plus d etoiles,le plus etoile,la plus etoilee
S|opens_earliest||ui-sunrise|en:opens earliest,earliest,earliest opening,open earliest,opens first|nl:vroegst open,opent het vroegst,vroegste,het vroegst|de:offnet am fruhesten,am fruhesten geoffnet,fruheste,fruhesten|fr:ouvre le plus tot,le plus tot
R|before|LT YEAR|ui-history|en:before,prior to,earlier than|nl:voor,eerder dan,voor het jaar|de:vor,fruher als|fr:avant
R|after|GT YEAR then|ui-history|en:after|fr:apres
R|later|GT YEAR|ui-history|en:later than|nl:na,later dan,na het jaar|de:nach,spater als|fr:plus tard que
R|since|GE YEAR|ui-history|en:since|nl:sinds,sedert|de:seit|fr:depuis
R|older|LT AGE|ui-history|en:older than|nl:ouder dan|de:alter als|fr:plus ancien que,plus ancienne que,plus anciens que,plus anciennes que,plus vieux que,plus vieille que
R|newer|GT AGE|ui-history|en:newer than,younger than,more recent than|nl:nieuwer dan,jonger dan,recenter dan|de:neuer als,junger als|fr:plus recent que,plus recente que,plus jeune que
R|new|GE NEW|ui-sparkles|en:newly built,recently built,new build,new builds,new construction,new constructions,brand new building,brand new buildings|nl:nieuwbouw,nieuw gebouwd,pas gebouwd,recent gebouwd|de:neubau,neubauten,neu gebaut,neu errichtet,frisch gebaut|fr:construction neuve,constructions neuves,nouvelle construction,nouvelles constructions,recemment construit,nouvellement construit
R|taller|GT TALL|ui-ruler|en:taller than,higher than|nl:hoger dan|de:hoher als|fr:plus haut que,plus haute que,plus eleve que,plus elevee que
R|lower|LT TALL|ui-ruler|en:lower than,shorter than|nl:lager dan|de:niedriger als|fr:moins haut que,moins haute que,plus bas que,plus basse que
R|more|GT|ui-filter|en:more than,over,above,greater than,upwards of,bigger than,larger than|nl:meer dan,boven,groter dan|de:mehr als,uber,oberhalb,grosser als|fr:plus de,plus que,au dessus de,superieur a,superieure a,plus grand que,plus grande que
R|least|GE|ui-filter|en:at least,minimum,no less than|nl:minstens,ten minste,minimaal,op zijn minst|de:mindestens,wenigstens,minimal|fr:au moins,minimum,pas moins de
R|less|LT|ui-filter|en:less than,under,below,beneath,fewer than,smaller than|nl:minder dan,onder,kleiner dan|de:weniger als,unter,unterhalb,kleiner als|fr:moins de,moins que,en dessous de,sous,inferieur a,plus petit que
R|most|LE|ui-filter|en:at most,max,maximum,up to,no more than|nl:hoogstens,maximaal,maximum,max|de:hochstens,maximal,maximum,max,bis zu|fr:au plus,au maximum,maximum,max,pas plus de
R|cheaper|LT PRICE|ui-coins|en:cheaper than,less expensive than|nl:goedkoper dan|de:billiger als,gunstiger als|fr:moins cher que,moins chere que
M|tall|TALL||en:tall,high,in height|nl:hoog,hoge|de:hoch,hohe,hoher,hohes|fr:haut,haute,hauts,hautes,de haut,de hauteur
M|elevation|ELEVATION||en:elevation,altitude,above sea level|nl:boven zeeniveau,hoogte boven zeeniveau|de:uber dem meer,uber meereshohe,hohenlage|fr:altitude,d altitude
B|built|||en:built,constructed,erected,built in,dating from,dates from,dating back to,built around|nl:gebouwd,gebouwd in,bouwjaar,gebouwd rond,daterend uit,uit het jaar|de:gebaut,erbaut,errichtet,baujahr,gebaut im,erbaut im,erbaut um|fr:construit,construite,construits,construites,bati,batie,edifie,datant de,date de,construit en
X|closes|CLOSES|ui-sunset|en:closes,close,closing,shuts,close at,closes at,closing at,that close at,which close at,that close|nl:sluit,sluiten,gaat dicht,gaan dicht,sluit om,die sluiten om|de:schliesst,schliessen,macht zu,machen zu,schliesst um|fr:qui ferme,ferme a,ferme vers,ferment,fermer,fermeture
X|opens|OPENS|ui-sunrise|en:opens,opening,opens at,opening at,that open at,which open at,that open,which open|nl:opent,gaat open,gaan open,openen,opent om,die opengaan om,die open gaan om|de:offnet,offnen,macht auf,machen auf,offnet um|fr:ouvre,ouvrent,ouverture,ouvre a
Q|count|COUNT|ui-layers|en:how many,number of,count,count of,total number of,the number of|nl:hoeveel,aantal,het aantal,tel|de:wie viele,wieviele,anzahl,anzahl der,anzahl an,anzahl von,zahl der|fr:combien,combien de,combien d,nombre de,nombre d,le nombre de
Q|hours|HOURS|ui-clock|en:opening times,opening hours,opening time,hours,business hours,open times,closing times,closing time,when does,when do,what time does,what time do,what time,when is,when are,and when,when was|nl:openingstijden,openingstijd,openingsuren,sluitingstijden,sluitingstijd,tijden,wanneer,hoe laat,en wanneer,wanneer werd|de:offnungszeiten,offnungszeit,geschaftszeiten,schliesszeiten,wann,um wie viel uhr,ab wann,bis wann,und wann,wann wurde|fr:horaires,horaire,heures d ouverture,horaires d ouverture,heure de fermeture,quand,a quelle heure,jusqu a quelle heure,et quand
N|brunch|amenity~^(cafe|restaurant)$ ; P:cuisine~^(brunch)$ or brunch~^(yes|only)$|maki-cafe|en:brunch|nl:brunch,brunchen|de:brunch,brunchen|fr:brunch,bruncher
N|cocktail_bar|amenity~^(bar|pub|nightclub)$ ; cocktails=yes|maki-bar|en:cocktail,cocktails,cocktail bar,cocktail bars|nl:cocktailbar,cocktailbars|de:cocktailbar,cocktailbars|fr:bar a cocktails,bar a cocktail
N|wine_bar|amenity~^(bar|pub|restaurant)$ ; drink:wine~^(yes|served|retail)$|maki-bar|en:wine bar,wine bars|nl:wijnbar,wijnbars,wijnlokaal|de:weinbar,weinbars,weinstube,weinlokal|fr:bar a vin,bar a vins,bars a vin
N|nightclub|amenity=nightclub|maki-bar|en:club,clubs,nightclub,nightclubs,night club,disco,clubbing,dance club|nl:club,clubs,nachtclub,nachtclubs,discotheek,disco,uitgaansgelegenheid|de:club,clubs,nachtclub,disco,diskothek,tanzclub|fr:boite de nuit,boites de nuit,club,discotheque,boite
N|coffeeshop|shop=cannabis or amenity=coffeeshop or (amenity=cafe and cannabis:recreational~^(yes|only)$)|maki-shop|nl:coffeeshop,coffeeshops,wiet,blowen|en:cannabis shop,weed shop,cannabis|de:cannabis laden|fr:boutique de cannabis
N|happy_hour|amenity~^(bar|pub|biergarten)$ ; happy_hours|maki-bar|en:happy hour,happy hours|nl:happy hour,happy hours|de:happy hour|fr:happy hour,heure joyeuse
N|fries|amenity=fast_food ; cuisine~^(fries|friture|chips|fish_and_chips)$|maki-fast-food|en:fries,french fries,chips shop,chippy|nl:patat,friet,frietje,patatje,frites,frietkot,frituur|de:pommes,pommesbude,fritten|fr:frites,friterie,baraque a frites
N|herring|shop=seafood or amenity=fast_food ; cuisine~^(fish|seafood|herring)$|maki-fast-food|en:herring,fish stall,herring stall|nl:haring,haringkar,haringhuis,haringstal,vis,viskraam,kibbeling,visboer|de:matjes,fischbrotchen,fischbude,hering|fr:hareng,poissonnerie
K|pancake|cuisine~^(pancake|pancakes|dutch_pancake)$|maki-restaurant|en:pancake,pancakes,pancake house,pancake restaurant|nl:pannenkoek,pannenkoeken,pannenkoekenhuis,pannenkoekenrestaurant,poffertjes|de:pfannkuchen,pfannkuchenhaus|fr:pancake,pancakes
K|creperie|cuisine~^(crepe|crepes|galette)$|maki-restaurant|en:crepes,crepe,creperie|nl:creperie,crepes|de:creperie,crepes|fr:creperie,creperies,crepes,crepe,galettes
K|surinamese|cuisine~^(surinamese|surinam)$|maki-restaurant|en:surinamese|nl:surinaams,surinaamse,surinaams eten|de:surinamisch,surinamische|fr:surinamien,surinamienne
N|market|amenity=marketplace|maki-shop|en:market,markets,market square,farmers market,flea market,street market,food market|nl:markt,markten,weekmarkt,boerenmarkt,vlooienmarkt,rommelmarkt,warenmarkt|de:markt,markte,wochenmarkt,bauernmarkt,flohmarkt|fr:marche,marches,marche fermier,marche aux puces,marche couvert
N|bike_repair|shop=bicycle ; service:bicycle:repair~^(yes|only)$|maki-bicycle|en:bike repair,bicycle repair,bike mechanic,bike repair shop,fix my bike|nl:fietsenmaker,fietsenmakers,fietsreparatie,fiets repareren|de:fahrradwerkstatt,fahrradreparatur,fahrrad reparieren|fr:reparation velo,reparateur velo,reparateur de velo
N|clothes|shop=clothes|maki-clothing-store|en:clothes,clothing,clothes shop,clothing store,fashion store|nl:kleding,kleren,kledingwinkel,kledingzaak|de:kleidung,klamotten,bekleidung,modegeschaft|fr:vetements,magasin de vetements
N|second_hand_shop|shop~^(second_hand|charity)$ or second_hand~^(yes|only)$|maki-shop|en:second hand shop,secondhand shop,thrift store,thrift shop,charity shop,op shop|nl:kringloopwinkel,kringloopwinkels,kringloop,tweedehandswinkel|de:secondhandladen,gebrauchtwarenladen,sozialkaufhaus|fr:friperie,depot vente,recyclerie
N|diy|shop~^(doityourself|hardware)$|maki-hardware|en:diy,diy store,hardware store,hardware shop,home improvement store|nl:bouwmarkt,bouwmarkten,doe het zelf,doe het zelf zaak,ijzerwarenwinkel,ijzerhandel|de:baumarkt,baumarkte,eisenwaren,eisenwarenhandlung|fr:bricolage,magasin de bricolage,quincaillerie
N|phone_repair|shop~^(mobile_phone|mobile_phone_accessories|electronics|repair|computer)$ ; mobile_phone:repair~^(yes|only)$ or repair~mobile_phone|maki-mobile-phone|en:phone repair,phone repair shop,mobile phone repair,screen repair,fix my phone|nl:telefoonreparatie,telefoon reparatie,gsm reparatie,telefoon repareren|de:handyreparatur,handy reparatur,handy reparieren|fr:reparation telephone,reparation de telephone,reparation smartphone
N|repair_cafe|repair=assisted_self_service|ui-hand|en:repair cafe,repair cafes,repaircafe|nl:repair cafe,repaircafe|de:repair cafe,reparaturcafe|fr:repair cafe
N|embassy|office=diplomatic and diplomatic~^(embassy|consulate|high_commission)$|ui-flag|en:embassy,embassies,consulate,consulates|nl:ambassade,ambassades,consulaat|de:botschaft,botschaften,konsulat|fr:ambassade,ambassades,consulat
N|ov_fiets|amenity=bicycle_rental and (name~ov.?fiets or brand~ov.?fiets)|temaki-bicycle_rental|en:ov fiets,ovfiets|nl:ov fiets,ovfiets,ov fietsen|de:ov fiets|fr:ov fiets
N|bijenkorf|shop=department_store and (name~bijenkorf or brand~bijenkorf)|maki-shop|en:bijenkorf,de bijenkorf|nl:bijenkorf,de bijenkorf|de:bijenkorf|fr:bijenkorf
N|emergency|amenity=hospital or healthcare=hospital ; emergency=yes|maki-hospital|en:emergency room,emergency department,accident and emergency,emergency,emergencies,hospital emergency|nl:spoedeisende hulp,eerste hulp,spoed,spoedpost,ziekenhuis met spoed|de:notaufnahme,notfallaufnahme,notfall,notfallambulanz|fr:urgences,les urgences,service des urgences,service d urgence
N|subway|(railway=station or public_transport=station) and station=subway or railway=subway_entrance|maki-rail-metro|en:metro,metro station,subway,subway station,underground station,tube,tube station|nl:metro,metrostation,metrohalte,metrostations|de:u bahn,ubahn,u bahnhof,u bahn station,u bahn haltestelle,metro,metrostation|fr:metro,station de metro,bouche de metro
N|fast_charger|amenity=charging_station and (socket:type2_combo or socket:chademo or socket:tesla_supercharger or socket:type2_combo:output>=50 or socket:chademo:output>=50 or socket:tesla_supercharger:output>=50 or charging_station:output>=50)|maki-charging-station|en:fast charger,fast chargers,rapid charger,rapid chargers,fast charging,fast charging station,dc charger,supercharger,superchargers|nl:snellader,snelladers,snellaadpaal,snellaadstation,snellaadpunt|de:schnelllader,schnellladestation,schnellladesaule,schnellladepunkt|fr:borne rapide,bornes rapides,recharge rapide,chargeur rapide
N|diesel|amenity=fuel ; fuel:diesel=yes|maki-fuel|en:diesel|nl:diesel|de:diesel|fr:diesel,gazole,gasoil
N|lpg|amenity=fuel ; fuel:lpg=yes|maki-fuel|en:lpg,autogas|nl:lpg,autogas|de:autogas,lpg,flussiggas|fr:gpl
N|hydrogen|amenity=fuel ; fuel:h70=yes or fuel:h35=yes or fuel:hydrogen=yes|maki-fuel|en:hydrogen,hydrogen station|nl:waterstof,waterstofstation,waterstoftankstation|de:wasserstoff,wasserstofftankstelle|fr:hydrogene,station hydrogene
N|car_sharing|amenity=car_sharing|maki-car|en:car sharing,car share,carsharing,shared car|nl:deelauto,deelautos,autodelen,car sharing|de:carsharing,car sharing|fr:autopartage,voiture partagee
N|bike_pump|amenity~^(bicycle_repair_station|compressed_air)$ or service:bicycle:pump=yes|ui-bike|en:bike pump,bicycle pump,air pump,pump up my tyres,pump up my tires,inflate my tires|nl:fietspomp,luchtpomp,band oppompen,banden oppompen|de:fahrradpumpe,luftpumpe,reifen aufpumpen|fr:pompe a velo,pompe a air,gonfler mes pneus
N|busy_road|highway~^(motorway|trunk|primary)$|ui-car|en:busy road,busy roads,main road,main roads,major road|nl:drukke weg,drukke wegen,hoofdweg,doorgaande weg|de:hauptstrasse,stark befahrene strasse,vielbefahrene strasse|fr:route passante,grande route,axe routier
N|motorway|highway=motorway|ui-car|en:motorway,motorways,highway,freeway|nl:snelweg,snelwegen,autosnelweg|de:autobahn,autobahnen|fr:autoroute,autoroutes
N|main_station|railway~^(station|halt)$ and name~(centraal|central|centrale|hauptbahnhof|hbf)|maki-rail|en:central station,main station,centraal,centraal station,cs|nl:centraal station,centraal,cs,hoofdstation|de:hauptbahnhof,hbf|fr:gare centrale
N|laundromat|shop=laundry ; self_service=yes|maki-laundry|en:laundromat,laundromats,launderette,launderettes,self service laundry,coin laundry|nl:wasserette,wasserettes,wassalon,wassalons|de:waschsalon,waschsalons|fr:laverie,laveries,laverie automatique,laverie libre service
N|parking_garage|amenity=parking and parking~^(multi-storey|underground)$|maki-parking|en:parking garage,parking garages,multi storey car park,multistorey car park,underground parking,underground car park,parking structure|nl:parkeergarage,parkeergarages,ondergrondse parkeergarage|de:parkhaus,parkhauser,tiefgarage,tiefgaragen|fr:parking souterrain,parking couvert,parking a etages
N|bnb|tourism=guest_house ; guest_house=bed_and_breakfast|maki-lodging|en:b&b,bnb,bed and breakfast,bed & breakfast,b and b|nl:b&b,bed and breakfast,bed & breakfast|de:pension,fruhstuckspension,b&b|fr:chambre d hote,chambres d hote,chambre d hotes,chambres d hotes,maison d hote
N|caravan_site|tourism=caravan_site|maki-campsite|en:caravan site,caravan park,motorhome site,campervan site,rv park,camper site|nl:camperplaats,camperplaatsen,camperpark,camperplek|de:stellplatz,wohnmobilstellplatz,wohnmobilstellplatze,womo stellplatz|fr:aire de camping car,aire de camping cars,aire pour camping car
N|art_museum|tourism~^(museum|gallery)$ ; museum=art|maki-art-gallery|en:art museum,art museums,art gallery,art galleries,museum of art|nl:kunstmuseum,kunstmusea,museum voor schone kunsten|de:kunstmuseum,kunstmuseen,kunsthalle|fr:musee d art,musee des beaux arts,musee d art moderne
N|children_museum|tourism=museum ; museum=children|maki-museum|en:children s museum,childrens museum,kids museum,museum for kids,museum for children|nl:kindermuseum,kindermusea|de:kindermuseum,kindermuseen|fr:musee pour enfants,musee des enfants
N|concert_hall|amenity=concert_hall or amenity~^(theatre|music_venue)$ and theatre:type=concert_hall|maki-music|en:concert hall,concert halls,concert venue|nl:concertzaal,concertzalen,concertgebouw|de:konzerthaus,konzertsaal,konzerthalle|fr:salle de concert,salle de concerts,auditorium
N|windmill|man_made=windmill|ui-wind|en:windmill,windmills|nl:windmolen,windmolens|de:windmuhle,windmuhlen|fr:moulin a vent,moulins a vent
N|mill|man_made~^(windmill|watermill)$|ui-wind|en:mill,mills|nl:molen,molens|de:muhle,muhlen|fr:moulin,moulins
N|street_art|tourism=artwork and artwork_type~^(mural|graffiti|street_art)$|maki-art-gallery|en:street art,mural,murals,graffiti|nl:muurschildering,muurschilderingen,graffiti,straatkunst|de:wandmalerei,wandbild,wandbilder,graffiti,strassenkunst|fr:fresque,fresques,art de rue,street art,graffiti
N|war_memorial|historic~^(memorial|monument)$ and memorial=war_memorial|maki-monument|en:war memorial,war memorials|nl:oorlogsmonument,oorlogsmonumenten|de:kriegerdenkmal,kriegsdenkmal,kriegerdenkmaler|fr:monument aux morts
N|tourist_info|tourism=information and information=office|maki-information|en:tourist information,tourist info,tourist office,visitor centre,visitor center,tourist information centre|nl:toeristeninformatie,vvv,toeristenbureau|de:touristeninformation,touristinfo,touristinformation,fremdenverkehrsamt|fr:office de tourisme,office du tourisme,syndicat d initiative
N|rijksmonument|heritage=2 and heritage:operator=rce|maki-monument|en:rijksmonument,rijksmonuments,national monument|nl:rijksmonument,rijksmonumenten|de:reichsdenkmal|fr:monument national neerlandais
N|stolperstein|historic=memorial and memorial=stolperstein|maki-monument|en:stolperstein,stolpersteine,stumbling stone,stumbling stones|nl:struikelsteen,struikelstenen|de:stolperstein,stolpersteine|fr:pave de memoire,paves de memoire
N|cathedral|building=cathedral or amenity=place_of_worship and name~\bdom|ui-church|en:cathedral,cathedrals|nl:kathedraal,kathedralen,dom,domkerk|de:dom,kathedrale,kathedralen,munster|fr:cathedrale,cathedrales
N|fachwerk|building and building:material=timber_framing|ui-building|en:timber framed house,timber framed houses,half timbered house,half timbered houses|nl:vakwerkhuis,vakwerkhuizen|de:fachwerk,fachwerkhaus,fachwerkhauser|fr:maison a colombages,maisons a colombages,colombages
N|houses|building~^(yes|house|residential|detached|semidetached_house|terrace|apartments|farm|bungalow)$|ui-building|en:house,houses|nl:huis,huizen,woning,woningen,woonhuis,woonhuizen|de:haus,hauser,wohnhaus,wohnhauser|fr:maison,maisons
N|brick_house|building~^(house|residential|detached|semidetached_house|terrace|apartments|yes)$ ; building:material=brick|ui-building|en:brick house,brick houses|nl:bakstenen huis,bakstenen huizen|de:backsteinhaus,backsteinhauser,ziegelhaus|fr:maison en brique,maisons en brique
N|canal_house|building~^(house|residential|apartments|yes)$ ; N:waterway=canal@40|ui-building|en:canal house,canal houses|nl:grachtenpand,grachtenpanden,grachtenhuis,grachtenhuizen|de:grachtenhaus,grachtenhauser|fr:maison de canal,maisons de canal,maison sur les canaux
N|bunker|military=bunker or building=bunker|ui-building|en:bunker,bunkers,pillbox|nl:bunker,bunkers,kazemat|de:bunker|fr:bunker,bunkers,blockhaus
N|hiking_route|route~^(hiking|foot)$|ui-walk|en:hiking trail,hiking trails,hiking route,hiking routes,hike,hikes,trail,trails,walking trail,walking route,walking routes|nl:wandelroute,wandelroutes,wandelpad,wandelpaden|de:wanderweg,wanderwege,wanderroute,wanderrouten,wanderung|fr:sentier de randonnee,sentiers de randonnee,randonnee,randonnees,sentier,sentiers
N|cherry_tree|natural=tree and (genus=Prunus or species~^prunus)|ui-tree|en:cherry blossom,cherry blossoms,cherry tree,cherry trees,sakura,japanese cherry|nl:kersenbloesem,kersenboom,kersenbomen,japanse kers|de:kirschblute,kirschbluten,kirschbaum,kirschbaume|fr:cerisier,cerisiers,cerisiers en fleurs,fleurs de cerisier
N|tree|natural=tree|ui-tree|en:tree,trees|nl:boom,bomen|de:baum,baume|fr:arbre,arbres
N|nature_reserve|leisure=nature_reserve or (boundary=protected_area and ((protect_class and protect_class!~"^[12][0-9]$") or (not protect_class and not heritage and not historic)))|pinhead-leaf|en:nature reserve,nature reserves,nature area,protected area|nl:natuurgebied,natuurgebieden,natuurreservaat,natuurreservaten|de:naturschutzgebiet,naturschutzgebiete,naturgebiet|fr:reserve naturelle,reserves naturelles,espace naturel
N|greens|(leisure~^(park|common|nature_reserve)$ or boundary=national_park or ((leisure=garden or natural=wood or landuse=forest) and name)) and access!~^(private|no)$|maki-park|en:green areas,green area,green spaces,green space,greenery,green places,green spots,green spot|nl:groen,groene gebieden,groen gebied,groengebied,groengebieden,groenvoorziening,groenvoorzieningen,groene plekken,groene plek,stadsgroen|de:grunflachen,grunflache,grunanlage,grunanlagen,grungebiet,grungebiete,im grunen|fr:espaces verts,espace vert,coins de verdure,coin de verdure,verdure
N|national_park|boundary=national_park|place.national-park|en:national park,national parks|nl:nationaal park,nationale parken,nationale park|de:nationalpark,nationalparks,nationalparke,nationalparken|fr:parc national,parcs nationaux
N|dune|natural=dune|ui-mountain|en:dune,dunes,sand dunes|nl:duin,duinen,duingebied|de:dune,dunen|fr:dune,dunes
N|water|natural=water or waterway~^(river|canal)$ or natural=coastline|ui-waves|en:water,waterside,the water,waterfront|nl:water,het water,waterkant,aan het water|de:wasser,am wasser,ufer|fr:eau,l eau,bord de l eau,au bord de l eau
N|bbq|amenity=bbq|ui-flame|en:bbq,barbecue spot,barbecue place,grill spot,public grill|nl:barbecueplek,barbecueplaats,bbq plek|de:grillplatz,grillplatze,grillstelle,grillstellen|fr:espace barbecue,aire de barbecue
N|bouldering|(sport~^(climbing|bouldering)$ and (leisure~^(sports_centre|sports_hall|fitness_centre)$ or building or indoor=yes)) or climbing=boulder or (climbing~^(area|crag)$ and climbing:boulder=yes) ; climbing:boulder=yes|ui-mountain|en:bouldering|nl:boulderen|de:bouldern|fr:escalade de bloc,bloc
N|bouldering_gym|sport~^(climbing|bouldering)$ and (leisure~^(sports_centre|sports_hall|fitness_centre)$ or building or indoor=yes) ; climbing:boulder=yes|ui-mountain|en:boulder gym,boulder gyms,bouldering gym,bouldering gyms,bouldering hall|nl:boulderhal,boulderhallen|de:boulderhalle,boulderhallen|fr:salle de bloc,salles de bloc
N|climbing_gym|sport~^(climbing|bouldering)$ and (leisure~^(sports_centre|sports_hall|fitness_centre)$ or building or indoor=yes)|ui-mountain|en:climbing gym,climbing gyms,climbing hall,climbing centre,climbing center,indoor climbing|nl:klimhal,klimhallen,klimcentrum|de:kletterhalle,kletterhallen,kletterzentrum|fr:salle d escalade,salles d escalade
N|climbing|sport~^(climbing|bouldering)$ or climbing~^(area|crag|boulder)$|ui-mountain|en:climbing,climbing wall|nl:klimmen,klimmuur|de:klettern,kletterwand|fr:escalade,mur d escalade
N|fitness_station|leisure=fitness_station|maki-fitness-centre|en:outdoor gym,outdoor fitness,fitness park,calisthenics park,street workout|nl:buitenfitness,buiten fitness,fitnesspark,calisthenicspark|de:outdoor fitness,fitnessparcours,trimm dich pfad,calisthenics park|fr:parcours sportif,fitness exterieur,street workout
N|swimming_pool|leisure=swimming_pool|maki-swimming|en:swimming pool,swimming pools,pool,pools|nl:zwembad,zwembaden|de:schwimmbad,schwimmbader|fr:piscine,piscines
N|indoor_pool|leisure=swimming_pool or leisure=sports_centre and sport=swimming ; indoor=yes|maki-swimming|en:indoor pool,indoor swimming pool,indoor pools|nl:overdekt zwembad,binnenbad,overdekte zwembaden|de:hallenbad,hallenbader|fr:piscine couverte,piscines couvertes
N|outdoor_pool|leisure=swimming_pool or leisure=sports_centre and sport=swimming ; outdoor=yes or indoor=no|maki-swimming|en:outdoor pool,outdoor swimming pool,lido,open air pool|nl:openluchtbad,buitenbad,buitenzwembad|de:freibad,freibader|fr:piscine exterieure,piscine en plein air,piscine de plein air
N|indoor_play|leisure=indoor_play or leisure=playground and indoor=yes|maki-playground|en:indoor playground,indoor play,soft play,play centre,play center|nl:indoor speeltuin,binnenspeeltuin,binnenspeeltuinen,speelparadijs|de:indoorspielplatz,hallenspielplatz,indoor spielplatz|fr:aire de jeux couverte,parc de jeux couvert,parc de jeux interieur
N|dog_park|leisure=dog_park or dog=unleashed|ui-dog|en:dog park,dog parks,dog run,off leash area,off leash|nl:hondenuitlaatplaats,losloopgebied,hondenlosloopgebied,hondenspeelweide,uitlaatplaats|de:hundeauslaufgebiet,hundewiese,hundefreilaufflache,hundeauslaufflache|fr:parc a chiens,caniparc,espace canin
N|soccer_pitch|leisure=pitch and sport=soccer|maki-soccer|en:football pitch,football pitches,soccer field,soccer fields,football field,football pitches|nl:voetbalveld,voetbalvelden,trapveld,trapveldje|de:fussballplatz,fussballplatze,bolzplatz|fr:terrain de football,terrain de foot,terrains de foot
N|pitch|leisure=pitch|maki-pitch|en:pitch,pitches,sports field,sports fields|nl:sportveld,sportvelden|de:sportplatz,sportplatze|fr:terrain de sport,terrains de sport
N|urinal|amenity=toilets ; toilets:position~urinal|maki-toilet|en:urinal,urinals,pissoir|nl:plaskrul,plaskrullen,urinoir,urinoirs,krul|de:pissoir,pissoirs,urinal|fr:urinoir,urinoirs,vespasienne,pissotiere
N|glass_container|amenity=recycling and (recycling:glass_bottles=yes or recycling:glass=yes)|ui-recycle|en:bottle bank,glass container,glass recycling,glass bin|nl:glasbak,glasbakken,glascontainer,glascontainers|de:altglascontainer,glascontainer,altglas|fr:conteneur a verre,recup verre,colonne a verre
N|textile_container|amenity=recycling and (recycling:clothes=yes or recycling:textiles=yes)|ui-recycle|en:clothes bank,clothing bank,textile recycling,clothes recycling|nl:textielcontainer,textielbak,kledingcontainer,kledingbak|de:altkleidercontainer,kleidercontainer,altkleider|fr:conteneur a vetements,borne textile,conteneur textile
N|paper_container|amenity=recycling and recycling:paper=yes|ui-recycle|en:paper bank,paper recycling,paper bin|nl:papiercontainer,papierbak,oud papier|de:altpapiercontainer,papiercontainer,altpapier|fr:conteneur a papier,colonne a papier
N|drinks_vending|amenity=vending_machine and vending~(drinks|cold_drinks|water|coffee|beverages)|maki-shop|en:drinks machine,drink machine,drinks vending machine,vending machine for drinks,soda machine|nl:drankautomaat,frisdrankautomaat,drankenautomaat|de:getrankeautomat,getrankeautomaten|fr:distributeur de boissons,distributeurs de boissons
N|smoking_pub|amenity~^(bar|pub)$ and smoking~^(yes|isolated|separated|dedicated)$|maki-bar|en:smoking bar,smokers bar,smoking pub|nl:rookcafe,rookkroeg|de:raucherkneipe,raucherkneipen,raucherbar,raucherlokal|fr:bar fumeur
N|bathing_place|leisure~^(bathing_place|swimming_area)$ or (natural=water and (swimming=yes or bathing=yes))|ui-waves|en:bathing place,bathing places,swimming area,swimming areas|nl:zwemplaats,zwemplaatsen,zwemwater|de:badeplatz,badeplatze|fr:zone de baignade,lieu de baignade
I|swim|||en:swim,swimming,to swim,for swimming,go swimming,for a swim,a swim,bathe,bathing,to bathe,swim spot,swimming spot,wild swimming,where can i swim|nl:zwemmen,zwem,om te zwemmen,zwemplekken,zwemwater,buiten zwemmen,wildzwemmen,waar kan ik zwemmen|de:schwimmen,baden,zum schwimmen,zum baden,wo kann ich schwimmen|fr:nager,se baigner,baignade,pour nager,pour se baigner,ou nager,ou se baigner
I|swim|lake||en:swimming lake,swimming lakes,swim lake,bathing lake,bathing lakes|nl:zwemmeer,zwemmeren|de:badesee,badeseen|fr:lac de baignade,lacs de baignade
I|swim|bathing_place||en:bathing spot,bathing spots|nl:zwemplas,zwemplassen,zwemplek,zwemstrand|de:badestelle,badestellen,badestrand|fr:plage de baignade
I|picnic|||en:picnic,for a picnic,to picnic,picnic spot|nl:picknicken,picknick,om te picknicken,picknickplek|de:picknick,picknicken,zum picknicken|fr:pique nique,pique niquer,pour pique niquer
I|sunset|||en:sunset,the sunset,watch the sunset,for the sunset,sunset view|nl:zonsondergang,de zonsondergang,zonsondergang kijken|de:sonnenuntergang,den sonnenuntergang,sonnenuntergang sehen|fr:coucher de soleil,le coucher du soleil,coucher du soleil
I|play|||en:to play,play outside,playing,for the kids to play,for kids to play|nl:spelen,om te spelen,buiten spelen|de:spielen,zum spielen,draussen spielen|fr:jouer,pour jouer,jouer dehors
I|walk|||en:for a walk,to walk,go for a walk,a walk,walk,nice walk,a nice walk,take a walk,stroll,a stroll,for a stroll|nl:wandelen,om te wandelen,wandeling,een wandeling,ommetje,mooie wandeling|de:spazieren,spazierengehen,spaziergang,zum spazieren,spazieren gehen|fr:se promener,promenade,une promenade,balade,une balade,pour se promener
I|shelter|||en:somewhere dry,out of the rain,stay dry,shelter from the rain,what to do when it rains,when it rains,when it s raining,rainy day,on a rainy day,indoors,inside activities|nl:ergens droog,droog zitten,schuilen,uit de regen,wat te doen bij regen,bij regen,als het regent,regenachtige dag|de:irgendwo trocken,im trockenen,vor dem regen,was tun bei regen,bei regen,wenn es regnet,regentag|fr:a l abri,au sec,a l abri de la pluie,que faire quand il pleut,quand il pleut,s il pleut,jour de pluie
I|refill|||en:refill water,refill my bottle,fill my bottle,fill up water,water refill,refill|nl:water bijvullen,fles bijvullen,water tappen,bijvullen|de:wasser auffullen,flasche auffullen,wasser nachfullen|fr:remplir ma gourde,remplir sa gourde,remplir une bouteille
I|sauna|||en:warm up,to warm up,get warm|nl:opwarmen,warm worden|de:aufwarmen,warm werden|fr:se rechauffer
I|late_food|||en:late bite,late night snack,food late at night|nl:laat eten,nachtelijk eten|de:spat essen,spatessen,essen in der nacht|fr:manger tard,manger la nuit
T|town|||en:this town,this city,this village,the town,the city,in town,around town,my town,my city|nl:deze stad,dit dorp,de stad,in de stad,in het dorp,deze gemeente,mijn stad,in deze stad|de:diese stadt,dieser stadt,in der stadt,in dieser stadt,diesem ort,in diesem ort,dieser gemeinde,meiner stadt|fr:cette ville,ce village,en ville,dans cette ville,la ville,cette commune,ma ville
C|soft|||en:maybe,perhaps,preferably,ideally,if possible,optionally,would be nice|nl:misschien,liefst,bij voorkeur,het liefst,liever,indien mogelijk,eventueel,zo mogelijk,graag ook|de:vielleicht,am liebsten,vorzugsweise,moglichst,idealerweise,wenn moglich,eventuell,gerne auch|fr:peut etre,de preference,idealement,si possible,eventuellement
C|approx|||en:about,approximately,roughly,circa,ca,around about|nl:ongeveer,omstreeks,circa,ca|de:etwa,ungefahr,gegen,circa,ca|fr:vers,environ,a peu pres
C|until|||en:until,till,til,up until|nl:tot,tot en met|de:bis,bis um|fr:jusqu a,jusqu,jusque
C|near|||en:near,next to,right next to,close to,beside,near to,by,around,along,alongside|nl:bij,naast,vlakbij,nabij,in de buurt van,dicht bij,rond,langs,bij de|de:bei,beim,nahe,neben,in der nahe von,in der nahe vom,in der nahe des,in der nahe der,nahe bei,nahe dem,entlang|fr:pres de,pres,a cote de,proche de,autour de,aux alentours de,le long de,pres du,pres des
C|with|||en:with,has,having,that has,which has,w|nl:met|de:mit|fr:avec,ayant
C|from|||en:from|nl:van,vanaf,uit|de:von,vom,aus|fr:du,des,d
C|of|||en:of|nl:of|de:|fr:de
C|within|||en:within|nl:binnen|de:innerhalb,innerhalb von|fr:a moins de,dans un rayon de
C|not_near|||en:away from,far from,not near,not close to|nl:niet bij,ver van,weg van,uit de buurt van|de:nicht bei,nicht in der nahe von,weit weg von,weit von|fr:loin de,pas pres de
C|without|||en:without,no,not,non,aren t,isn t,don t,doesn t,except,but not|nl:zonder,geen,niet,behalve|de:ohne,kein,keine,keinen,nicht,ausser|fr:sans,non,sauf,pas de
C|around_me|||en:around me,near me,nearby,close by,here,near here,around here,in the area|nl:in de buurt,dichtbij,hier,om de hoek,in mijn buurt|de:in der nahe,hier,um die ecke,in meiner nahe,in der umgebung|fr:a proximite,pres d ici,autour de moi,ici,pres de moi
C|in|||en:in,inside|nl:in|de:in,im|fr:dans,a,en
C|then|||en:then,and then,after that,afterwards,followed by|nl:dan,daarna,en dan,vervolgens,en daarna|de:dann,danach,und dann,anschliessend|fr:puis,ensuite,et puis,et apres,et ensuite,apres ca
C|and|||en:and,also,as well as|nl:en,ook|de:und,auch|fr:et,aussi
C|or|||en:or|nl:|de:oder|fr:ou
C|filler|||en:a,an,the,access,some,any,me,i,want,need,looking,look,for,find,show,search,where,is,are,there,can,get,good,nice,place,places,spot,spots,somewhere,to,go,please,that,which,one,it,my,our,a few,buy,purchase,sells,sell,selling,what,whats,s,do,does,did,all,every,overview,list,options,option,choices,available,exist,exists,located,situated,give,tell,us,who,currently,ones,anything,something,stuff,those,these,they,them,its,were,was,be,allowed,you,your,visit,visited,visiting,but,last,leave,leaves,accepts,accept,an hour,per hour,a day,per day,let,lets,could,would,should,will,go to,get to,well|nl:een,de,het,ik,wil,zoek,zoeken,waar,is,er,graag,plek,ergens,naar,die,dat,mijn,leuk,leuke,goed,goede,toe,gaan,kopen,koop,kan,verkoopt,wat,welke,welk,wie,zijn,alle,overzicht,lijst,opties,optie,keuze,laat,zien,geef,staan,zitten,zit,ligt,liggen,toegestaan,maar,bezoeken,per uur,een uur,mag,mogen,worden,wordt|de:ein,eine,einen,einem,einer,der,die,das,den,dem,ich,suche,wo,ist,gibt,es,zum,zur,bitte,schon,gute,guten,gutes,kaufen,kaufe,kann,was,welche,welcher,welches,wie,sind,alle,ubersicht,liste,optionen,auswahl,zeig,zeige,mir,liegt,liegen,erlaubt,aber,besuchen,pro stunde,die stunde,darf,durfen,wird,werden|fr:un,une,le,la,les,l,je,cherche,veux,ou est,est,il,y,au,aux,bon,bonne,pour,mon,acheter,achete,trouver,puis je,quel,quelle,quels,quelles,que,qui,sont,tous,toutes,tout,apercu,vue d ensemble,liste,options,choix,des,montre,montre moi,existe,ya t il,y a t il,il y a,admis,mais,accepte,acceptes,acceptent,par heure,l heure,visiter,peut,peuvent,on peut
A|second_hand|second_hand~^(yes|only)$ or shop~^(second_hand|charity)$|ui-recycle|en:second hand,secondhand,used,pre loved,vintage|nl:tweedehands,gebruikt,gebruikte,vintage|de:gebraucht,gebrauchte,second hand,secondhand|fr:d occasion,occasion,seconde main
A|supervised|supervised=yes|ui-eye|en:supervised,guarded,attended,with a lifeguard,lifeguard,lifeguards|nl:bewaakt,bewaakte,met toezicht,strandwacht,reddingsbrigade,met strandwacht|de:bewacht,bewachte,bewachter,bewachtes,beaufsichtigt,badeaufsicht,rettungsschwimmer,mit badeaufsicht|fr:surveille,surveillee,surveilles,surveillees,garde,gardee
A|unesco|heritage=1 and (heritage:operator=whc or ref:whc)|ui-landmark|en:unesco,unesco world heritage,world heritage,world heritage site,world heritage sites,unesco site|nl:werelderfgoed,unesco werelderfgoed|de:weltkulturerbe,welterbe,unesco welterbe,weltnaturerbe|fr:patrimoine mondial,patrimoine de l unesco,patrimoine mondial de l unesco
A|mh|heritage:operator=mhs|maki-monument|fr:monument historique,monuments historiques,classe monument historique|en:french historic monument
N|listed_building|heritage|maki-monument|en:listed building,listed buildings,heritage building,heritage buildings,protected building,protected buildings,listed structure,listed structures|nl:monumentaal pand,monumentale panden,beschermd monument,beschermde monumenten,gemeentelijk monument,gemeentelijke monumenten|de:baudenkmal,baudenkmaler,baudenkmale,denkmalgeschutztes gebaude,denkmalgeschutzte gebaude|fr:monument historique,monuments historiques,batiment classe,batiments classes,edifice classe,edifices classes
A|listed|heritage|maki-monument|en:listed,heritage listed|nl:beschermd,beschermde,monumentaal,monumentale|de:denkmalgeschutzt,denkmalgeschutzte,denkmalgeschutzten,unter denkmalschutz|fr:classe,classee,inscrit,inscrite
W|catholic|denomination~^(roman_catholic|catholic|old_catholic)$|ui-church|en:catholic,roman catholic|nl:katholiek,katholieke,rooms katholiek,rooms katholieke,roomse|de:katholisch,katholische,katholischen,katholischer|fr:catholique,catholiques
W|protestant|denomination~^(protestant|evangelical|lutheran|reformed|calvinist|methodist|baptist|pentecostal|anglican|presbyterian|dutch_reformed|protestant_church_in_the_netherlands)$|ui-church|en:protestant,evangelical,lutheran,reformed,anglican,methodist,baptist|nl:protestants,protestantse,evangelisch,evangelische,luthers,lutherse,gereformeerd,gereformeerde,hervormd,hervormde|de:evangelisch,evangelische,evangelischen,lutherisch,lutherische,reformiert,reformierte,protestantisch,protestantische|fr:protestant,protestante,evangelique,lutherien,lutherienne,reforme,reformee
W|orthodox|denomination~orthodox|ui-church|en:orthodox,greek orthodox,russian orthodox|nl:orthodox,orthodoxe|de:orthodox,orthodoxe|fr:orthodoxe
W|service_times|service_times|ui-clock|en:mass,masses,church service,service times|nl:mis,missen,kerkdienst,kerkdiensten|de:gottesdienst,gottesdienste,heilige messe|fr:messe,messes,culte
W|art_deco|building:architecture=art_deco|ui-building|en:art deco|nl:art deco|de:art deco|fr:art deco
W|brutalist|building:architecture=brutalist|ui-building|en:brutalist,brutalism|nl:brutalistisch,brutalistische,brutalisme|de:brutalistisch,brutalistische,brutalismus|fr:brutaliste,brutalistes
W|art_nouveau|building:architecture=art_nouveau|ui-building|en:art nouveau|nl:art nouveau,jugendstil|de:jugendstil|fr:art nouveau
W|amsterdam_school|building:architecture=amsterdam_school|ui-building|en:amsterdam school,amsterdamse school|nl:amsterdamse school|de:amsterdamer schule|fr:ecole d amsterdam
W|gothic|building:architecture~^(gothic|neo_gothic|brick_gothic)$|ui-church|en:gothic,neo gothic|nl:gotisch,gotische,neogotisch,neogotische|de:gotisch,gotische,gotischen,neugotisch,neugotische|fr:gothique,gothiques,neo gothique
W|romanesque|building:architecture=romanesque|ui-church|en:romanesque|nl:romaans,romaanse|de:romanisch,romanische,romanischen|fr:roman,romane,romanes
W|baroque|building:architecture=baroque|ui-building|en:baroque|nl:barok,barokke|de:barock,barocke,barocken|fr:baroque,baroques
W|modernist|building:architecture~^(modern|modernism|international_style|functionalism|bauhaus)$|ui-building|en:modernist,modern architecture,bauhaus|nl:modernistisch,modernistische,functionalistisch,bauhaus|de:modernistisch,bauhaus|fr:moderniste,bauhaus
A|former|amenity!=place_of_worship|ui-history|en:former,ex,disused|nl:voormalig,voormalige,gewezen|de:ehemalig,ehemalige,ehemaligen,ehemaliger,ehemaliges|fr:ancienne,desaffectee,desaffecte
W|dome|roof:shape=dome|ui-building|en:dome,domed,with a dome|nl:koepel,koepels,met koepel|de:kuppel,kuppeln,mit kuppel|fr:dome,coupole,a coupole
W|thatch|roof:material=thatch|ui-building|en:thatched,thatch,thatched roof|nl:rieten dak,riet,rietgedekt,rietgedekte|de:reetdach,reetgedeckt,reetgedeckte,strohdach|fr:toit de chaume,chaume
A|roman|historic:civilization=ancient_roman|ui-landmark|en:roman,ancient roman|nl:romeins,romeinse|de:romisch,romische,romischen,romer|fr:romain,romaine,romains,romaines,gallo romain
P|old|historic or heritage or start_date<1900|ui-history|en:old,historic,historical|nl:historisch,historische|de:historisch,historische,historischen|fr:historique,historiques,vieux,vieille
W|shade|shade~^(yes|partial)$|ui-trees|en:shade,in the shade,shady|nl:schaduw,in de schaduw,schaduwrijk|de:schatten,im schatten,schattig|fr:ombre,a l ombre,ombrage
W|public|access~^(yes|public|permissive)$|ui-users|en:public|nl:openbaar,openbare,publiek,publieke|de:offentlich,offentliche,offentlichen|fr:public,publique,publics,publiques
A|paid|fee=yes|ui-coins|en:paid,fee,with a fee,charged|nl:betaald,betaalde,tegen betaling|de:kostenpflichtig,kostenpflichtige,gebuhrenpflichtig|fr:payant,payante,payants,payantes
W|free_wifi|internet_access~^(wlan|yes|wifi|public|terminal)$ and internet_access:fee!=yes ; P:internet_access:fee=no|ui-wifi|en:free wifi,free wi fi,free internet|nl:gratis wifi,gratis internet,gratis wi fi|de:kostenloses wlan,kostenlos wlan,gratis wlan,freies wlan,kostenloses internet|fr:wifi gratuit,internet gratuit,wifi gratuite
A|drive_through|drive_through=yes|maki-car|en:drive through,drive thru,drive in|nl:drive through,drive thru,drive in|de:drive in,drive through|fr:drive,service au volant
N|gay_bar|amenity~^(bar|pub|nightclub|cafe)$ and (lgbtq~^(primary|only|yes)$ or gay~^(yes|only)$)|maki-bar|en:gay bar,gay bars,lgbt bar,lgbtq bar,queer bar,queer bars|nl:homobar,homobars,gaybar,gaybars,homokroeg,homokroegen|de:schwulenbar,schwulenbars,schwulenkneipe,gaybar,gaybars|fr:bar gay,bars gays,bar lgbt,bar queer
W|lgbtq|lgbtq~^(primary|welcome|only|friendly|yes)$|ui-heart|en:lgbt,lgbtq,lgbt friendly,lgbtq friendly,gay,gay friendly,queer|nl:lhbt,lhbti,roze,homo,gay|de:schwul,schwule,queer,lgbt|fr:gay,lgbt,queer
W|heated|outdoor_seating:heated=yes or heating=yes|ui-flame|en:heated|nl:verwarmd,verwarmde|de:beheizt,beheizte,beheizten|fr:chauffe,chauffee
A|women_only|female=yes|ui-person|en:women only,ladies only,for women,female only|nl:alleen vrouwen,voor vrouwen,vrouwen|de:nur fur frauen,frauen,fur frauen|fr:pour femmes,reserve aux femmes,femmes
W|hearing_loop|hearing_loop=yes|ui-headphones|en:hearing loop,induction loop,loop system|nl:ringleiding,solo apparatuur|de:induktionsschleife,horschleife|fr:boucle magnetique,boucle auditive
W|fireplace|real_fire~^(yes|wood|gas|open|coal)$|ui-flame|en:fireplace,open fire,real fire,log fire,open fireplace|nl:open haard,haardvuur,houtkachel|de:kamin,offener kamin,kaminfeuer|fr:cheminee,feu de bois,feu de cheminee
W|michelin|michelin|ui-star|en:michelin,michelin star,michelin starred,michelin stars|nl:michelinster,michelinsterren,michelin|de:michelinstern,michelinsterne,michelin|fr:etoile michelin,etoiles michelin,etoile au michelin,michelin
W|highchair|highchair=yes|ui-baby|en:high chair,high chairs,highchair,highchairs|nl:kinderstoel,kinderstoelen,kinderstoeltje|de:kinderstuhl,hochstuhl,kinderhochstuhl|fr:chaise haute,chaises hautes
A|chain|brand|ui-store|en:chain,chains,a chain,franchise|nl:keten,ketens,filiaal|de:kette,ketten,filiale|fr:chaine,chaines,franchise
P|independent|brand!~.|ui-store|en:independent,local,non chain,family run|nl:zelfstandig,zelfstandige,onafhankelijk,onafhankelijke,lokaal|de:unabhangig,inhabergefuhrt,lokal|fr:independant,independante,local
W|live_music|live_music=yes|ui-music|en:live music,live band,live bands,live gig|nl:livemuziek,live muziek,live band|de:livemusik,live musik|fr:musique live,concert live,musique en direct
W|bulk|bulk_purchase~^(yes|only)$|ui-recycle|en:zero waste,bulk,refill shop,package free,plastic free|nl:verpakkingsvrij,zero waste,bulk,onverpakt|de:unverpackt,zero waste|fr:vrac,en vrac,zero dechet
O|late|shop~^(convenience|kiosk|supermarket)$|ui-moon-star|en:late shop,late night shop,night shop,late night store|nl:avondwinkel,avondwinkels,nachtwinkel,nachtwinkels|de:spati,spatis,spatkauf,spatverkauf,nachtkiosk|fr:epicerie de nuit,epicerie ouverte tard
O|late|amenity=pharmacy|maki-pharmacy|en:night pharmacy,late night pharmacy|nl:nachtapotheek,nachtapotheken|de:nachtapotheke,notapotheke|fr:pharmacie de nuit
O|Th 20:00|shop|ui-store|nl:koopavond,koopavonden|de:langer einkaufsabend|en:late shopping night,late night shopping|fr:nocturne commerciale
O|12:00||ui-sun|en:for lunch,at lunch,lunchtime,at lunchtime,lunch time,over lunch|nl:voor de lunch,tussen de middag,lunchtijd,in de lunchpauze|de:zum mittagessen,mittags,mittagszeit,in der mittagspause|fr:pour le dejeuner,le midi,a l heure du dejeuner
O|13:00||ui-sun|fr:entre midi et deux
G|highly_rated|GE 4|ui-star|en:highly rated,well rated,good reviews,great reviews,highly reviewed|nl:goed beoordeeld,hoog gewaardeerd,goede reviews,goede recensies|de:gut bewertet,hoch bewertet,gute bewertungen|fr:bien note,bien notee,bien notes,tres bien note,bons avis
M|rating|RATING|ui-star|en:rating,ratings,score,rated|nl:beoordeling,waardering,score,cijfer|de:bewertung,bewertungen,note|fr:note,notes,avis
L|30|||en:soon,in half an hour,in 30 minutes,shortly|nl:binnenkort,straks,zo meteen,over een half uur|de:bald,gleich,in einer halben stunde,in kurze|fr:bientot,dans une demi heure,tout a l heure
L|60|||en:in an hour,in one hour,within the hour,in 1 hour|nl:over een uur,binnen een uur,over 1 uur|de:in einer stunde,in 1 stunde|fr:dans une heure,dans 1 heure
D|1200|||en:walking distance,within walking distance,in walking distance,walkable|nl:loopafstand,op loopafstand,binnen loopafstand,lopend bereikbaar|de:fussweg,zu fuss erreichbar,fusslaufig,fussnah|fr:a distance de marche,a quelques pas
D|4000|||en:cycling distance,within cycling distance,biking distance,bike distance|nl:fietsafstand,op fietsafstand,binnen fietsafstand|de:radentfernung,mit dem rad erreichbar|fr:a distance de velo
Z|Amsterdam|||en:mokum,a dam,adam|nl:mokum,a dam,adam|de:mokum|fr:mokum
Z|@centre|||en:downtown,city centre,city center,the city centre,the city center,town centre,town center,the town centre,the town center,the centre,the center,old town,the old town,historic centre,historic center|nl:centrum,het centrum,binnenstad,de binnenstad,stadscentrum,het stadscentrum,oude stad,de oude stad|de:innenstadt,die innenstadt,der innenstadt,stadtmitte,zentrum,stadtzentrum,altstadt,die altstadt,der altstadt|fr:centre ville,le centre ville,centre historique,le centre historique,vieille ville,la vieille ville,hypercentre
Z|Rotterdam|||en:r dam,rdam|nl:r dam,rdam
Z|Den Haag|||en:the hague|nl:den haag,s gravenhage|de:den haag|fr:la haye
Z|@neighbourhood|||en:this neighbourhood,this neighborhood,my neighbourhood,my neighborhood,the neighbourhood,the neighborhood,this area|nl:deze buurt,deze wijk,mijn buurt,mijn wijk|de:dieses viertel,diesem viertel,meinem viertel,dieser gegend,meiner gegend,diesem kiez,meinem kiez|fr:ce quartier,mon quartier,le quartier
Q|height|FACT height|ui-ruler|en:how tall is,how high is,how tall,how high,height of,the height of|nl:hoe hoog is,hoe hoog,hoogte van,de hoogte van|de:wie hoch ist,wie hoch,hohe von,hohe des,hohe der|fr:quelle est la hauteur de,quelle est la hauteur du,quelle hauteur fait,hauteur de,hauteur du,la hauteur de
Q|start_date|FACT start_date|ui-history|en:how old is,how old,age of|nl:hoe oud is,hoe oud|de:wie alt ist,wie alt|fr:quel age a,de quand date
Q|architect|FACT architect|ui-building|en:who designed,who built,architect of,who was the architect of|nl:wie ontwierp,wie heeft ontworpen,architect van|de:wer entwarf,wer hat entworfen,architekt von,architekt des|fr:qui a concu,qui a construit,architecte de,architecte du
Q|phone|FACT phone|ui-phone|en:phone number of,telephone number of,phone of,phone number,telephone number|nl:telefoonnummer van,telefoonnummer,nummer van|de:telefonnummer von,telefonnummer der,telefonnummer des,telefonnummer|fr:numero de telephone de,numero de telephone du,numero de telephone,telephone de
Q|website|FACT website|ui-globe|en:website of,web site of,homepage of|nl:website van,site van|de:webseite von,website von,homepage von|fr:site web de,site web du,site internet de,site internet du
Q|population|FACT population|ui-users|en:how many people live in,how many people live,population of,inhabitants of,how many inhabitants,how many inhabitants does,population|nl:hoeveel inwoners heeft,hoeveel inwoners,hoeveel mensen wonen in,inwoners van,aantal inwoners van,inwoners|de:wie viele einwohner hat,wie viele einwohner,wie viele menschen leben in,einwohner von,einwohnerzahl von,einwohner|fr:combien d habitants a,combien d habitants,population de,habitants de,habitants
I|swim|||de:schwimmen gehen
I|work|||en:work,to work,somewhere to work,work on my laptop,where can i work,work remotely,study,to study,somewhere to study,laptop friendly|nl:werken,om te werken,studeren,om te studeren,studieplek,werkplek,op mijn laptop werken|de:arbeiten,zum arbeiten,lernen,zum lernen,am laptop arbeiten|fr:travailler,pour travailler,etudier,pour etudier,reviser
I|stargaze|||en:stargazing,stargaze,look at the stars,see the stars,watch the stars,dark sky|nl:sterren kijken,sterrenkijken,naar de sterren kijken|de:sterne beobachten,sterne gucken,sternegucken|fr:observer les etoiles,regarder les etoiles
I|run|||en:running,go running,a run,for a run,jogging,jog,go for a run,to run|nl:hardlopen,rennen,joggen,een rondje rennen|de:joggen,laufen gehen,zum joggen|fr:courir,faire un jogging,jogging,footing
I|fish|||en:fishing,fishing spot,to fish,go fishing,fish|nl:vissen,visplek,visstek,om te vissen|de:angeln,angelplatz,angelstelle,zum angeln|fr:pecher,peche,coin de peche,aller a la peche
I|cool_down|||en:cool down,cool off,to cool down,on a hot day,when it s hot,its hot,heatwave|nl:verkoelen,afkoelen,verkoeling,op een warme dag,als het warm is,hittegolf|de:abkuhlen,abkuhlung,an einem heissen tag,wenn es heiss ist,hitzewelle|fr:se rafraichir,rafraichissement,quand il fait chaud,canicule
I|watch_football|||en:watch the football,watch football,watch the game,watch the match,watch the world cup,sports bar|nl:voetbal kijken,wedstrijd kijken,het voetbal kijken,sportcafe|de:fussball schauen,fussball gucken,spiel schauen,sportsbar|fr:regarder le foot,regarder le match,bar sportif
I|barbecue|bbq||en:barbecue,to barbecue,have a barbecue,grill,grilling|nl:barbecuen,barbecue,bbq,barbecueen|de:grillen,zum grillen,barbecue|fr:barbecue,faire un barbecue,griller
I|sightsee|||en:sightseeing,sights,things to do,what to do,see the sights,attractions,tourist attractions,things to see|nl:bezienswaardigheden,wat te doen,iets te doen,uitjes,uitje|de:sehenswurdigkeiten,was tun,was unternehmen,ausflugsziele|fr:a voir,que faire,choses a faire,sites touristiques,visites
I|walk_dog|||en:walk the dog,dog walk,walk my dog,walking the dog|nl:hond uitlaten,de hond uitlaten,uitlaten,met de hond wandelen|de:gassi gehen,gassi,mit dem hund gehen,hund ausfuhren|fr:promener le chien,sortir le chien,promener mon chien
I|kitesurf|||en:kitesurfing,kitesurf,kiting,windsurfing,windsurf|nl:kitesurfen,kiten,windsurfen|de:kitesurfen,kiten,windsurfen|fr:kitesurf,faire du kitesurf,planche a voile
I|ice_skate|||en:ice skating,ice skate,skating on ice,ice rink|nl:schaatsen,schaatsbaan,ijsbaan,natuurijs|de:eislaufen,schlittschuhlaufen,eisbahn,schlittschuh laufen|fr:patinoire,patiner,patin a glace
I|sunbathe|||en:sunbathe,sunbathing,sun bathing,soak up the sun|nl:zonnen,zonnebaden,in de zon liggen|de:sonnenbaden,in der sonne liegen,sonnen|fr:bronzer,prendre le soleil,bain de soleil
I|charge_phone|||en:charge my phone,charge phone,charge my laptop,phone charging,charge my mobile|nl:telefoon opladen,mijn telefoon opladen,mobiel opladen|de:handy laden,handy aufladen,mein handy laden|fr:recharger mon telephone,charger mon telephone,recharger mon portable
I|sit|||en:place to sit,a place to sit,somewhere to sit,sit down,a seat,to sit|nl:ergens zitten,plek om te zitten,zitplek,even zitten|de:hinsetzen,sitzplatz,zum sitzen,irgendwo sitzen|fr:s asseoir,m asseoir,pour s asseoir
I|drinks|||en:go out,going out,a night out,night out,go for drinks,drinks tonight|nl:borrelen,uitgaan,stappen,een borrel|de:ausgehen,feiern gehen,etwas trinken gehen|fr:sortir,faire la fete,boire un verre,boire un coup
C|at|||en:at|nl:om,aan|de:um,am,an
C|on|||en:on|nl:op|fr:sur
C|have|||en:have|nl:heeft,hebben|de:hat,haben
C|closed|||en:closed|nl:gesloten,dicht|de:geschlossen,zu|fr:ferme,fermee,fermes,fermees
C|or_more|||en:or more,or above,or higher,and up,and above,or better|nl:of meer,of hoger,en meer,en hoger|de:oder mehr,oder hoher,und mehr|fr:ou plus,et plus
C|or_less|||en:or less,or fewer,or lower,or below|nl:of minder,of lager|de:oder weniger,oder niedriger|fr:ou moins
C|goes|||nl:gaat,gaan|de:macht,machen
C|outside|||en:outside of|de:ausserhalb,ausserhalb von|fr:en dehors de,en dehors du,en dehors des,hors de,hors du,a l exterieur de,a l exterieur du
C|per|||en:per,for each,in each,in every|nl:per,in elke,in iedere,voor elke|de:pro,in jedem,in jeder,fur jeden,fur jede|fr:par,dans chaque,pour chaque
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
        if (w.endsWith("en")) {
            add(w.dropLast(2))
            // Dutch keeps a long vowel long: "pinautomaten" of "pinautomaat", "bomen" of "boom"
            doubled(w.dropLast(2))?.let { add(it) }
        }
        if (w.endsWith("n") || w.endsWith("e")) add(w.dropLast(1))
        // German plurals in -er: "Häuser", "Wälder"
        if (w.endsWith("er")) add(w.dropLast(2))
    }

    /** [s] with its last vowel doubled when one vowel stands between consonants at its end ("automat" → "automaat"). */
    private fun doubled(s: String): String? {
        if (s.length < 3) return null
        val a = s[s.length - 3]
        val v = s[s.length - 2]
        val c = s[s.length - 1]
        if (v !in "aeou" || a in VOWELS || c in VOWELS) return null
        return s.dropLast(1) + v + c
    }

    private const val VOWELS = "aeiouy"

    /**
     * Which of several meanings of the same phrase wins, lowest first: the hand-written table (0), then its small
     * words (1, so "the" stays an article and not the French for tea), then categories, brands and goods (2),
     * cuisines (3), preset names (4) and preset search terms (5).
     */
    /** The highest rank of a hand-picked word: the table's, categories, brands, goods and cuisines; above it a preset's. */
    const val CURATED = 3

    fun rank(m: Meaning): Int = when (m) {
        is Meaning.Noun -> m.rank
        is Meaning.Attribute -> 0
        is Meaning.Word -> if (m.role == Meaning.Role.FILLER) 1 else 0
        else -> 0
    }

    private fun build(): Map<String, List<Entry>> {
        val all = ArrayList<Entry>()
        val nouns = HashMap<String, Meaning.Noun>()
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
                "N", "K" -> Meaning.Noun(nounQuery(payload), checkedSymbol(symbol, payload.substringBefore(WISH)), id, rank = 0, cuisine = kind == "K").also { nouns[id] = it; tableNouns.putIfAbsent(id, it) }
                "A", "P", "W" -> payload.split(WISH).let { p ->
                    Meaning.Attribute(TagFilter.parse(p[0]), checkedSymbol(symbol, p[0]), id, soft = kind == "P", want = kind == "W", prefer = p.getOrNull(1)?.let { TagFilter.parse(it.removePrefix("P:")) })
                }
                "O" -> Meaning.Open(
                    when {
                        id == "tonight" -> OpenTime.Tonight
                        id == "late" -> OpenTime.Late
                        id == "now" -> OpenTime.Now
                        ' ' in id -> OpenTime.OnDay(DAY_CODES.getValue(id.substringBefore(' ')), LocalTime.parse(id.substringAfter(' ')))
                        else -> OpenTime.Today(LocalTime.parse(id))
                    },
                    payload.takeIf { it.isNotEmpty() }?.let { Meaning.Noun(nounQuery(it), checkedSymbol(symbol, it.substringBefore(WISH)), "open:$id", rank = 0) },
                )
                "G" -> payload.split(' ').let { Meaning.Rating(CompareOp.valueOf(it[0]), it[1].toDouble()) }
                "L" -> Meaning.Later(id.toInt())
                "D" -> Meaning.Radius(id.toDouble())
                "Z" -> Meaning.Area(id)
                "S" -> Meaning.Sort(SortBy.valueOf(id.uppercase()))
                "R" -> payload.split(' ').let { p ->
                    Meaning.Compare(CompareOp.valueOf(p[0]), p.getOrNull(1)?.takeIf { it != "then" }?.let { Meaning.Dim.valueOf(it) }, then = "then" in p)
                }
                "M" -> Meaning.Measure(Meaning.Dim.valueOf(payload))
                "B" -> Meaning.Built
                "X" -> Meaning.Change(ChangeKind.valueOf(payload))
                "Q" -> payload.split(' ').let { Meaning.AskFor(Ask.valueOf(it[0]), it.getOrNull(1), symbol.takeIf { s -> Symbols.has(s) }) }
                "T" -> Meaning.Town
                "I" -> Meaning.Aim(id, payload.takeIf { it.isNotEmpty() }?.let { nouns[it] ?: error("lexicon noun $it") })
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
        // "this weekend": its Saturday or Sunday
        for ((lang, words) in WEEKEND) for (w in words.split(',')) all += Entry(w.split(' '), Meaning.Day(DayOfWeek.SATURDAY, weekend = true), lang)
        // Atlas's categories, as kinds of places a little below the hand-picked words; a name a language shares with
        // English (or falls back to) is English's, so it does not count as that language's own word
        for (c in Categories.all()) {
            val en = Words.split(c.name("en")).map { it.text }.filter { it != "&" }
            for (lang in LANGS) {
                val ws = Words.split(c.name(lang)).map { it.text }.filter { it != "&" }
                if (ws.isNotEmpty() && (lang == "en" || ws != en)) all += Entry(ws, Meaning.Noun(Query.Category(c.id), c.symbol, "category:${c.id}", rank = 2), lang)
            }
        }
        // cuisines: "pizza", "sushi", "ramen", and their names in the four languages
        for (v in Cuisine.known) {
            if (v in GENERIC_CUISINES) continue
            val noun = Meaning.Noun(Query.Tags(TagFilter.Equals("cuisine", v)), Cuisine.symbol(v), "cuisine:$v", rank = 3, cuisine = true)
            all += Entry(v.split('_'), noun, "en")
            val en = Words.split(Cuisine.name(v, "en")).map { it.text }
            for (lang in LANGS) {
                val ws = Words.split(Cuisine.name(v, lang)).map { it.text }
                if (ws.isNotEmpty() && (lang == "en" || (ws != en && ws != v.split('_')))) all += Entry(ws, noun, lang)
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

    /**
     * A noun's query: its filter, then its wishes after [WISH]: a want ("a laundromat": a laundry that says it is
     * self-service), "P:" a preference, "N:filter@metres" a place it stands by ("canal houses": by a canal).
     */
    private fun nounQuery(payload: String): Query {
        val parts = payload.split(WISH)
        var q: Query = Query.Tags(TagFilter.parse(parts[0]))
        val wishes = ArrayList<Query>()
        for (p in parts.drop(1)) when {
            p.startsWith("P:") -> wishes += Query.Prefer(Query.Tags(TagFilter.parse(p.removePrefix("P:"))))
            p.startsWith("N:") -> q = Query.Near(q, Query.Tags(TagFilter.parse(p.removePrefix("N:").substringBefore('@'))), p.substringAfterLast('@').toDouble())
            else -> wishes += Query.Want(Query.Tags(TagFilter.parse(p)))
        }
        return if (wishes.isEmpty()) q else Query.and(listOf(q) + wishes)
    }

    /** Between a noun's filter and its wishes. */
    private const val WISH = " ; "

    private val DAY_CODES = mapOf(
        "Mo" to DayOfWeek.MONDAY, "Tu" to DayOfWeek.TUESDAY, "We" to DayOfWeek.WEDNESDAY, "Th" to DayOfWeek.THURSDAY,
        "Fr" to DayOfWeek.FRIDAY, "Sa" to DayOfWeek.SATURDAY, "Su" to DayOfWeek.SUNDAY,
    )

    /** The noun that stands for [g]: all its sellers, the likelier ones ranked higher. */
    fun goodsNoun(g: Goods): Meaning.Noun = Meaning.Noun(g.query, g.symbol, "goods:${g.id}", rank = 2)

    /** A kind of place of the table by its id ("bench", "peak", "building"). */
    fun noun(id: String): Meaning.Noun? = index.let { tableNouns[id] }

    private val tableNouns = HashMap<String, Meaning.Noun>()

    /** The table's one-word kinds of place of five letters or more, for reading a typo. */
    private val typoNouns: List<Entry> by lazy {
        index.values.flatten().filter { e -> e.words.size == 1 && e.words[0].length >= 5 && (e.meaning as? Meaning.Noun)?.rank == 0 }
            .distinctBy { it.words[0] }
    }

    /**
     * The kind of place one word names with one typo ("pharmcy", "apoteek", "bkery"); a word of five letters only
     * with a letter left out or one too many, so "paris" is not "parks".
     */
    fun fuzzyNoun(word: String): Entry? {
        if (word.length < 5) return null
        return typoNouns.filter { e ->
            val w = e.words[0]
            (word.length >= 6 || kotlin.math.abs(w.length - word.length) == 1) && app.atlas.places.Text.distance(word, w, 1) <= 1
        }.minByOrNull { kotlin.math.abs(it.words[0].length - word.length) }
    }

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
            val symbol = Kinds.symbolOf(p) ?: Symbols.symbolFor(sample(p.tags))
            for (l in langs) {
                val noun = Meaning.Noun(Query.Tags(filter), symbol, "preset:${p.id}", rank = 4)
                // id-tagging-schema's name for the kind ("Clothing Store") and what Atlas calls it ("Clothes")
                for (n in setOfNotNull(p.fullName(l), KindNames.searched(p.id, l))) {
                    val name = Words.split(n).map { it.text }.filter { it != "&" && it != "," }
                    if (name.isNotEmpty()) map.getOrPut(name[0]) { ArrayList() }.add(Entry(name, noun, l))
                }
                val term = Meaning.Noun(Query.Tags(filter), symbol, "preset:${p.id}", rank = 5)
                for (t in p.terms(l)) {
                    val ws = Words.split(t).map { it.text }.filter { it != "&" && it != "," }
                    if (ws.size == 1 && ws[0] in NOT_TERMS) continue
                    if (ws.isNotEmpty()) map.getOrPut(ws[0]) { ArrayList() }.add(Entry(ws, term, l))
                }
            }
        }
        map
    }

    /** Preset search terms that people mean otherwise: "paris" is the city before it is French for bets. */
    private val NOT_TERMS = setOf("paris", "pari")

    /** Brands by every name they go by: their name (without a "(Europe)"), their brand and the names they are matched by. */
    private val brandIndex: Map<String, List<Brand>> by lazy {
        val m = HashMap<String, MutableList<Brand>>()
        for (b in Brands.all()) {
            for (n in listOf(bare(b.name), b.brand) + b.matchNames) {
                val k = Brands.key(n)
                // two letters only for the names people call brands by ("ah", "dm"), which [brand] takes alone
                if (k.length < 2) continue
                val list = m.getOrPut(k) { ArrayList(1) }
                if (list.lastOrNull() !== b) list.add(b)
            }
        }
        m
    }

    private fun bare(name: String): String = name.substringBefore(" (").trim()

    /**
     * A brand whose name is exactly [phrase] ("albert heijn", "hema", "ah", "mcd"), trading in [country] when given:
     * the one of that very name before one that only goes by it, one that names the country before one found
     * anywhere, then the most widespread.
     */
    fun brand(phrase: String, country: String? = null): Brand? {
        val key = Brands.key(phrase)
        val k = BRAND_ALIASES[key]?.let { Brands.key(it) } ?: key
        val list = brandIndex[k] ?: return null
        val here = list.filter { it.availableIn(country) }.ifEmpty { list }
        val c = country?.lowercase()
        return here.maxWithOrNull(
            compareBy<Brand>({ Brands.key(bare(it.name)) == k }, { Brands.key(it.brand) == k }, { c != null && c in it.countries }, { it.spread }),
        )
    }

    /** True for a short name people call a brand by ("ah", "bk", "dm"). */
    fun isAlias(phrase: String): Boolean = Brands.key(phrase) in BRAND_ALIASES

    /** True for a word too ordinary to be read as a brand on its own ("more", "live", "closed"). */
    fun ordinary(word: String): Boolean = word in ORDINARY

    /** Brands by the names people call them ("ah", "appie", "mcd", "bk"), folded as [Brands.key] folds. */
    private val BRAND_ALIASES = mapOf(
        "ah" to "Albert Heijn", "appie" to "Albert Heijn", "ahtogo" to "Albert Heijn to go", "ahxl" to "Albert Heijn XL",
        "mcd" to "McDonald's", "mcdo" to "McDonald's", "macdo" to "McDonald's", "mcds" to "McDonald's", "mickeyds" to "McDonald's",
        "bk" to "Burger King", "sbux" to "Starbucks", "bp" to "BP", "dm" to "dm", "dmdrogerie" to "dm", "dmdrogeriemarkt" to "dm",
        "teslasupercharger" to "Tesla", "supercharger" to "Tesla", "cna" to "C&A", "handm" to "H&M",
    )

    /** Words of the four languages that are also some brand's name, never read as the brand alone. */
    private val ORDINARY = setOf(
        "more", "most", "less", "mass", "live", "living", "smoke", "closed", "only", "loop", "house", "home", "farmers", "best",
        "good", "new", "old", "big", "small", "little", "top", "plus", "max", "one", "first", "last", "next", "fresh", "local",
        "city", "life", "time", "day", "night", "star", "stars", "sun", "world", "central", "main", "total", "super", "prime",
        "select", "value", "express", "center", "centre", "service", "care", "help", "free", "open", "near", "here", "now",
        "east", "west", "north", "south", "green", "blue", "red", "black", "white", "gold", "royal", "union", "national",
        "general", "global", "family", "kids", "baby", "sport", "sports", "fit", "beauty", "hair", "style", "fashion",
        "design", "art", "music", "food", "deli", "market", "outlet", "plaza", "studio", "lab", "club", "nice", "happy",
        "quick", "easy", "smart", "simply", "tasty", "vintage", "classic", "original", "urban", "nature", "natural",
        "zone", "point", "spot", "place", "corner", "station", "garage", "bike", "car", "auto", "taxi", "bus", "train",
        "visit", "allowed", "you", "your", "people", "work", "play", "walk", "run", "fish", "chain", "office", "paris",
        "eten", "essen", "manger", "kerk", "markt", "huis", "haus", "maison", "casa", "bar", "pub",
    )

    /**
     * Kinds of place that are also what another place can have ("a Starbucks with toilets"), by the kind's filter:
     * the tags a place says it with, and how near one of its own may stand instead (public toilets next door).
     */
    val amenities: Map<String, Pair<TagFilter, Double>> by lazy {
        mapOf(
            "amenity=toilets" to ("toilets=yes or toilets:access~^(yes|customers|permissive|public)$" to 50.0),
            "amenity=atm" to ("atm=yes" to 30.0),
            "leisure=playground" to ("playground=yes or kids_area=yes" to 60.0),
            "amenity=drinking_water" to ("drinking_water=yes" to 50.0),
            "amenity=charging_station" to ("capacity:charging or socket:type2 or socket:type2_combo or socket:chademo" to 80.0),
            "amenity=bench" to ("bench=yes" to 20.0),
            "amenity=shower" to ("shower=yes" to 50.0),
            "leisure=swimming_pool" to ("swimming_pool=yes" to 80.0),
            "amenity=bicycle_parking" to ("bicycle_parking=yes or capacity:bicycle" to 50.0),
            "highway=elevator" to ("elevator=yes" to 60.0),
            "amenity=waste_basket" to ("waste_basket=yes" to 15.0),
        ).mapValues { (_, v) -> TagFilter.parse(v.first) to v.second }
    }

    /**
     * True for kinds of place other places lie in, mapped as parts of their own: parks, woods, lakes, malls,
     * stations, buildings ("woods with a lake", "a mall with a Starbucks"). Hotels and campsites say what they have
     * in their tags instead.
     */
    fun isContainer(q: Query): Boolean = when (q) {
        is Query.Tags -> sampleOf(q.filter).any { (k, v) -> k in CONTAINERS && CONTAINERS[k]?.contains(v) != false }
        is Query.Category -> Categories.byId(q.id)?.rule?.let { isContainer(Query.Tags(it)) } ?: false
        is Query.Or -> q.parts.any { isContainer(it) }
        is Query.And -> q.parts.any { isContainer(it) }
        else -> false
    }

    /** The values of each key that make a container ([isContainer]); null for every value. */
    private val CONTAINERS: Map<String, Set<String>?> = mapOf(
        "leisure" to setOf("park", "garden", "nature_reserve", "recreation_ground", "common", "water_park", "sports_centre", "stadium", "golf_course", "marina", "resort", "playground"),
        "landuse" to setOf("forest", "recreation_ground", "cemetery", "retail"),
        "natural" to setOf("wood", "heath", "beach", "water"),
        "boundary" to setOf("protected_area", "national_park"),
        "shop" to setOf("mall"),
        "railway" to setOf("station"),
        "public_transport" to setOf("station"),
        "amenity" to setOf("bus_station", "university", "college", "school", "hospital", "marketplace", "grave_yard"),
        "aeroway" to setOf("aerodrome", "terminal"),
        "tourism" to setOf("zoo", "theme_park"),
        "place" to setOf("square"),
        "highway" to setOf("pedestrian"),
        "historic" to setOf("castle"),
        "building" to null,
    )

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
        "en" to listOf("monday,mon", "tuesday,tue,tues", "wednesday,wed", "thursday,thu,thur,thurs", "friday,fri", "saturday,sat", "sunday,sun"),
        "nl" to listOf("maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag", "zondag"),
        "de" to listOf("montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag,sonnabend", "sonntag"),
        "fr" to listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"),
    )

    private val WEEKEND = mapOf(
        "en" to "weekend,this weekend,the weekend,at the weekend,on the weekend",
        "nl" to "weekend,dit weekend,het weekend,in het weekend",
        "de" to "wochenende,am wochenende,dieses wochenende",
        "fr" to "week end,ce week end,le week end",
    )

    private val RELATIVE_DAYS = mapOf(
        "en" to listOf("today", "tomorrow"),
        "nl" to listOf("vandaag", "morgen"),
        "de" to listOf("heute", "morgen"),
        "fr" to listOf("aujourd hui,aujourdhui", "demain"),
    )
}
