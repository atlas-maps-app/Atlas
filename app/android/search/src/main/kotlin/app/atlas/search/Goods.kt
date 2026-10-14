package app.atlas.search

import app.atlas.places.Preset
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.TagFilter
import app.atlas.places.Text

/** A kind of place that sells some [Goods], and how likely it is to have them, from 1 (perhaps) to 5 (surely). */
class Seller internal constructor(val filter: TagFilter, val likelihood: Int, val preset: Preset?) {
    val query: Query get() = Query.Tags(filter)

    /** The symbol to draw it with. */
    val symbol: String = preset?.let { Presets.iconOf(it) }?.takeIf { Symbols.has(it) } ?: Symbols.symbolFor(Lexicon.sampleOf(filter))

    /** Its name in [lang]: "Hardware Store", "Bouwmarkt". */
    fun name(lang: String = "en"): String = preset?.name(lang) ?: filter.toText()

    override fun toString() = "${filter.toText()}:$likelihood"
}

/**
 * Something people buy or get done ("picture hooks", "batteries", "a spare key"), and the kinds of place that sell
 * it, likeliest first: the drawers of the Find board.
 */
class Goods internal constructor(
    val id: String,
    /** Its phrases by language, folded. */
    val phrases: Map<String, List<String>>,
    val sellers: List<Seller>,
) {
    /** What to call it in [lang]: its first phrase there, else in English. */
    fun name(lang: String = "en"): String = (phrases[lang] ?: phrases["en"])?.firstOrNull() ?: id

    val symbol: String get() = sellers.first().symbol

    /**
     * Every seller at once, the likelier kinds ranked higher: a hardware store (5) a kilometre away comes before a
     * supermarket (1) round the corner.
     */
    val query: Query by lazy {
        val any = Query.or(sellers.map { it.query })
        Query.and(listOf(any) + sellers.filter { it.likelihood > 1 }.map { Query.Prefer(it.query, (it.likelihood - 1) * 0.25) })
    }

    override fun toString() = "goods:$id"
}

/** The catalogue of [Goods], in English, Dutch, German and French. */
object GoodsCatalogue {
    // id|sellers|lang:phrase,phrase|…   A seller is shop values joined by "+" (or key=value+value) and ":likelihood".
    private val TABLE = """
hooks|hardware+doityourself:5,variety_store:4,department_store:3,stationery:2,supermarket:1|en:picture hooks,picture hook,wall hooks,hooks,hook|nl:schilderijhaakjes,schilderijhaak,haakjes,haken|de:bilderhaken,wandhaken,haken|fr:crochets,crochet,crochets a tableau
fixings|hardware+doityourself:5,trade:4,variety_store:3,department_store:2|en:screws,screw,bolts,wall plugs,rawlplugs|nl:schroeven,schroef,spijkers,spijker,pluggen,bouten|de:schrauben,schraube,nagel,dubel,bolzen|fr:clous,clou,chevilles,boulons
glue|hardware+doityourself:4,stationery:4,variety_store:4,department_store:3,supermarket:2|en:glue,superglue,tape,duct tape,sticky tape,sellotape|nl:lijm,secondelijm,plakband,ducttape,tape|de:kleber,klebstoff,sekundenkleber,klebeband,tesafilm|fr:colle,ruban adhesif,scotch
batteries|electronics:4,variety_store:4,supermarket:4,hardware+doityourself:3,chemist:3,convenience:3,kiosk+newsagent:2|en:batteries,battery,aa batteries,aaa batteries|nl:batterijen,batterij|de:batterien,batterie|fr:piles,pile
bulbs|hardware+doityourself:5,lighting:5,electrical:4,variety_store:3,department_store:3,supermarket:2|en:light bulb,light bulbs,bulb,bulbs,led bulb|nl:gloeilamp,ledlamp,peertje,lampje|de:gluhbirne,leuchtmittel,led lampe|fr:ampoule,ampoules
lamps|lighting:5,interior_decoration:3,furniture:3,department_store:3,doityourself:3|en:lamp,lamps,desk lamp,floor lamp,lampshade|nl:lamp,lampen,bureaulamp,staande lamp,lampenkap|de:lampe,lampen,schreibtischlampe,stehlampe,lampenschirm|fr:lampe,lampes,lampe de bureau,lampadaire,abat jour
paint|paint:5,hardware+doityourself:5,craft:2|en:paint,wall paint,paint brush,paintbrush,roller|nl:verf,muurverf,kwast,verfroller|de:farbe,wandfarbe,pinsel,farbrolle|fr:peinture,pinceau,rouleau
tools|hardware+doityourself:5,trade:4,variety_store:2|en:drill,hammer,screwdriver,tools,toolbox,pliers,spanner,wrench,tape measure|nl:boor,boormachine,hamer,schroevendraaier,gereedschap,tang,zaag,rolmaat|de:bohrmaschine,bohrer,hammer,schraubenzieher,schraubendreher,werkzeug,zange,sage,massband|fr:perceuse,marteau,tournevis,outils,pince,scie,metre ruban
cables|electrical:5,hardware+doityourself:5,electronics:4,variety_store:3,department_store:3|en:extension cord,extension lead,power strip,plug adapter,travel adapter,adapter|nl:verlengsnoer,stekkerdoos,verloopstekker,wereldstekker,adapter|de:verlangerungskabel,steckdosenleiste,reiseadapter,adapter|fr:rallonge,multiprise,adaptateur
phone_charger|mobile_phone:5,electronics:5,computer:3,variety_store:3,department_store:3,kiosk+newsagent:2,convenience:1|en:phone charger,charging cable,charger cable,usb cable,usb c cable,lightning cable,power bank,powerbank|nl:telefoonoplader,oplader,oplaadkabel,usb kabel,powerbank|de:ladegerat,handy ladegerat,ladekabel,usb kabel,powerbank|fr:chargeur,chargeur de telephone,cable usb,cable de charge,batterie externe
phone|mobile_phone:5,electronics:3,kiosk+newsagent:3,convenience:2,supermarket:2|en:sim card,sim,prepaid sim,phone case,screen protector|nl:simkaart,prepaid simkaart,telefoonhoesje,hoesje,screenprotector|de:simkarte,prepaidkarte,handyhulle,schutzfolie|fr:carte sim,coque,coque de telephone,protection d ecran
phone_repair|mobile_phone:5,craft=electronics_repair:5,electronics:3,computer:3|en:phone repair,screen repair,cracked screen,broken screen|nl:telefoon reparatie,scherm reparatie,kapot scherm|de:handy reparatur,displayreparatur,display kaputt|fr:reparation telephone,reparation ecran,ecran casse
headphones|electronics:5,hifi:5,mobile_phone:4,computer:3,department_store:3|en:headphones,earphones,earbuds,headset|nl:koptelefoon,oortjes,headset|de:kopfhorer,headset|fr:ecouteurs,casque audio
computer|computer:5,electronics:5,stationery:2,copyshop:2|en:laptop,computer,mouse,keyboard,ink cartridge,printer ink,toner,usb stick,memory card,sd card,hdmi cable|nl:laptop,computer,muis,toetsenbord,inktcartridge,printerinkt,toner,usb stick,geheugenkaart,sd kaart|de:laptop,computer,maus,tastatur,druckerpatrone,toner,usb stick,speicherkarte,sd karte|fr:ordinateur,ordinateur portable,souris,clavier,cartouche d encre,toner,cle usb,carte memoire,carte sd
camera|photo:5,electronics:4|en:camera,film roll,camera film,photo prints|nl:camera,fototoestel,fotorolletje,foto afdrukken|de:kamera,fotoapparat,fotoabzuge|fr:appareil photo,pellicule,tirages photo
passport_photo|photo:5,amenity=photo_booth:4,copyshop:3|en:passport photo,passport photos,id photo|nl:pasfoto,pasfoto s|de:passbild,passfoto,biometrisches passbild|fr:photo d identite,photos d identite
stamps|amenity=post_office:5,kiosk+newsagent:4,tobacco:3,stationery:3,supermarket:3,books:2|en:stamps,stamp,postage stamps|nl:postzegels,postzegel|de:briefmarken,briefmarke|fr:timbres,timbre
stationery|stationery:5,variety_store:4,books:3,department_store:3,supermarket:2,copyshop:2|en:envelopes,envelope,pen,pens,pencil,pencils,notebook,printer paper,paper,folder,scissors,stapler|nl:enveloppen,envelop,pen,pennen,potlood,potloden,schrift,notitieboek,printpapier,papier,schaar,nietmachine|de:umschlage,briefumschlag,kugelschreiber,stift,bleistift,heft,notizbuch,druckerpapier,papier,ordner,schere,tacker|fr:enveloppes,enveloppe,stylo,stylos,crayon,cahier,carnet,papier,classeur,ciseaux,agrafeuse
cards|stationery:5,gift:4,variety_store:4,books:3,department_store:3,kiosk+newsagent:2,supermarket:2|en:birthday card,greeting card,greetings card,wrapping paper,gift wrap|nl:verjaardagskaart,wenskaart,cadeaupapier,inpakpapier|de:geburtstagskarte,gluckwunschkarte,grusskarte,geschenkpapier|fr:carte d anniversaire,carte de voeux,papier cadeau
printing|copyshop:5,stationery:3,photo:3|en:print,printing,print documents,photocopy,photocopies,copies|nl:printen,afdrukken,kopieren,kopietjes|de:drucken,ausdrucken,kopieren,kopien|fr:imprimer,impression,photocopie,photocopies
key_copy|craft=key_cutter:5,locksmith:5,shoe_repair:4,hardware+doityourself:2|en:key copy,spare key,key cutting,duplicate key,copy a key|nl:sleutel bijmaken,sleutel laten maken,reservesleutel,sleutel kopieren|de:schlussel nachmachen,ersatzschlussel,schlussel kopieren|fr:double de cle,copie de cle,refaire une cle
padlock|hardware+doityourself:5,locksmith:4,bicycle:3,variety_store:3|en:padlock,padlocks|nl:hangslot,hangsloten|de:vorhangeschloss,vorhangschloss|fr:cadenas
bike_parts|bicycle:5,variety_store:2,sports:2,department_store:2|en:bike light,bike lights,bicycle light,bicycle lights,inner tube,bike tyre,bike tire,bike lock,bicycle lock,bike pump,bicycle pump,puncture repair kit|nl:fietslampje,fietslampjes,fietsverlichting,fietslamp,binnenband,buitenband,fietsslot,fietspomp,plakset,plakspullen|de:fahrradlicht,fahrradlampe,fahrradschlauch,schlauch,fahrradreifen,fahrradschloss,luftpumpe,fahrradpumpe,flickzeug|fr:eclairage velo,lumiere velo,chambre a air,pneu velo,antivol,antivol velo,pompe a velo,rustines
bike_repair|bicycle:5|en:bike repair,bicycle repair,flat tyre,flat tire,puncture,bike mechanic|nl:fietsenmaker,fietsreparatie,lekke band,fiets repareren|de:fahrradreparatur,fahrradwerkstatt,platten,platter reifen|fr:reparation velo,crevaison,reparateur velo
medicine|amenity=pharmacy:5,chemist:5,supermarket:2,convenience:1|en:painkillers,painkiller,paracetamol,ibuprofen,aspirin,plasters,plaster,band aid,band aids,bandage,cough syrup,medicine,medicines,medication,cold medicine,allergy tablets|nl:pijnstillers,pijnstiller,paracetamol,ibuprofen,aspirine,pleisters,pleister,verband,hoestdrank,medicijnen,medicijn,hooikoortstabletten|de:schmerzmittel,schmerztabletten,paracetamol,ibuprofen,aspirin,pflaster,verband,hustensaft,medikamente,medizin|fr:antidouleur,antidouleurs,paracetamol,ibuprofene,aspirine,pansements,pansement,sirop pour la toux,medicaments,medicament
toiletries|chemist:5,supermarket:4,amenity=pharmacy:3,convenience:3,cosmetics:2,department_store:2|en:toothbrush,toothpaste,shampoo,conditioner,deodorant,razor,razors,razor blades,tampons,sanitary pads,condoms,sunscreen,sun cream,suncream,soap,shower gel,nappies,diapers|nl:tandenborstel,tandpasta,shampoo,conditioner,deodorant,scheermesjes,tampons,maandverband,condooms,zonnebrand,zonnebrandcreme,zeep,douchegel,luiers|de:zahnburste,zahnpasta,shampoo,spulung,deo,deodorant,rasierer,rasierklingen,tampons,binden,kondome,sonnencreme,seife,duschgel,windeln|fr:brosse a dents,dentifrice,shampoing,apres shampoing,deodorant,rasoir,lames de rasoir,tampons,serviettes hygieniques,preservatifs,creme solaire,savon,gel douche,couches
lens_care|optician:5,chemist:4,amenity=pharmacy:4|en:contact lens solution,lens solution,contact lenses|nl:lenzenvloeistof,contactlenzen|de:kontaktlinsenflussigkeit,kontaktlinsen|fr:solution pour lentilles,lentilles de contact
glasses|optician:5,department_store:2,chemist:2,variety_store:2|en:glasses,reading glasses,sunglasses,glasses repair|nl:bril,leesbril,zonnebril,bril repareren|de:brille,lesebrille,sonnenbrille|fr:lunettes,lunettes de lecture,lunettes de soleil
makeup|cosmetics:5,perfumery:5,chemist:4,department_store:4,beauty:2|en:makeup,make up,mascara,lipstick,perfume,nail polish|nl:make up,mascara,lippenstift,parfum,nagellak|de:schminke,make up,mascara,lippenstift,parfum,nagellack|fr:maquillage,mascara,rouge a levres,parfum,vernis a ongles
groceries|supermarket:5,convenience:4,greengrocer:1|en:milk,eggs,butter,yoghurt,yogurt,cereal,toilet paper,toilet roll,loo roll,tissues,kitchen roll|nl:melk,eieren,boter,yoghurt,toiletpapier,wc papier,wc rollen,zakdoekjes,keukenrol|de:milch,eier,butter,joghurt,toilettenpapier,klopapier,taschentucher,kuchenrolle|fr:lait,oeufs,beurre,yaourt,papier toilette,mouchoirs,essuie tout
fruit|greengrocer:5,supermarket:4,amenity=marketplace:4,convenience:2|en:fruit,vegetables,veggies,apples,bananas,tomatoes|nl:fruit,groente,groenten,appels,bananen,tomaten|de:obst,gemuse,apfel,bananen,tomaten|fr:fruits,legumes,pommes,bananes,tomates
meat|butcher:5,supermarket:3,amenity=marketplace:2|en:meat,minced meat,mince,sausages|nl:vlees,gehakt,rookworst,worstjes|de:fleisch,hackfleisch,wurstchen,aufschnitt|fr:viande,viande hachee,saucisses fraiches
fresh_fish|seafood:5,amenity=marketplace:3,supermarket:2|en:fresh fish,salmon,prawns,mussels|nl:verse vis,zalm,garnalen,mosselen|de:frischer fisch,lachs,garnelen,muscheln|fr:poisson frais,saumon,crevettes,moules
cheese_shop|cheese:5,deli:4,amenity=marketplace:3,supermarket:3|en:wheel of cheese,gouda|nl:kaas,kazen,goudse kaas,oude kaas|de:kase,gouda|fr:fromage,fromages,gouda
drinks|alcohol:5,wine:4,beverages:4,supermarket:3,convenience:2|en:liquor,spirits,whisky,whiskey,vodka,gin,rum,bottle of wine,bottle of champagne|nl:drank,sterke drank,whisky,wodka,jenever,fles wijn,fles champagne|de:spirituosen,schnaps,whisky,wodka,flasche wein,flasche sekt|fr:alcool,spiritueux,whisky,vodka,bouteille de vin,bouteille de champagne
flowers|florist:5,garden_centre:3,amenity=marketplace:2,supermarket:2|en:flowers,bouquet,bunch of flowers,roses,tulips|nl:bloemen,bos bloemen,boeket,rozen,tulpen|de:blumen,blumenstrauss,strauss,rosen,tulpen|fr:fleurs,bouquet,bouquet de fleurs,roses,tulipes
garden|garden_centre:5,doityourself:4,florist:3,hardware:2|en:plants,houseplant,houseplants,seeds,potting soil,compost,flower pot,plant pot|nl:planten,kamerplant,kamerplanten,zaden,potgrond,bloempot|de:pflanzen,zimmerpflanze,zimmerpflanzen,saatgut,blumenerde,blumentopf|fr:plantes,plante,plante d interieur,graines,terreau,pot de fleurs
pet_food|pet:5,supermarket:3,garden_centre:2|en:dog food,cat food,cat litter,pet food,bird seed|nl:hondenvoer,kattenvoer,kattenbakvulling,dierenvoer,vogelzaad|de:hundefutter,katzenfutter,katzenstreu,tierfutter,vogelfutter|fr:croquettes,nourriture pour chien,nourriture pour chat,litiere,graines pour oiseaux
books|books:5,second_hand+charity:2,department_store:2,kiosk+newsagent:2,stationery:1|en:book,books,novel,a novel,paperback|nl:boek,boeken,roman,leesboek|de:buch,bucher,roman,taschenbuch|fr:livre,livres,roman,poche
press|kiosk+newsagent:5,tobacco:4,books:2,supermarket:2,convenience:2|en:newspaper,newspapers,magazine,magazines|nl:krant,kranten,tijdschrift,tijdschriften|de:zeitung,zeitungen,zeitschrift,zeitschriften,magazin|fr:journal,journaux,magazine,magazines
tobacco|tobacco:5,kiosk+newsagent:4,convenience:3,amenity=fuel:3|en:cigarettes,tobacco,rolling papers,lighter,cigars|nl:sigaretten,tabak,vloei,shag,aansteker,sigaren|de:zigaretten,tabak,zigarettenpapier,feuerzeug,zigarren|fr:cigarettes,tabac,feuilles a rouler,briquet,cigares
vape|e-cigarette:5,tobacco:4,kiosk+newsagent:2|en:vape,e cigarette,e liquid,vape juice|nl:e sigaret,vape,e liquid|de:e zigarette,liquid,vape|fr:cigarette electronique,vapoteuse,e liquide
lottery|lottery:5,kiosk+newsagent:4,tobacco:4,supermarket:1|en:lottery ticket,lottery tickets,scratch card,scratchcard|nl:kraslot,staatsloterij,loterijlot|de:lotto,lottoschein,rubbellos|fr:ticket de loto,loto,jeu a gratter
clothes|clothes:5,department_store:5,variety_store:3,second_hand+charity:2,supermarket:1|en:socks,underwear,t shirt,tshirt,shirt,jumper,sweater,jacket,raincoat,trousers,jeans,dress|nl:sokken,ondergoed,shirt,t shirt,trui,jas,regenjas,broek,spijkerbroek,jurk|de:socken,unterwasche,t shirt,hemd,pullover,jacke,regenjacke,jeans,kleid|fr:chaussettes,sous vetements,t shirt,chemise,pull,veste,impermeable,pantalon,jean,robe
umbrella|department_store:5,variety_store:5,clothes:3,bag:3,supermarket:2,convenience:2,kiosk+newsagent:2|en:umbrella,umbrellas,rain poncho,poncho|nl:paraplu,paraplus,regenponcho,poncho|de:regenschirm,schirm,regenponcho|fr:parapluie,parapluies,poncho
shoes|shoes:5,sports:3,department_store:3,clothes:2|en:shoes,sneakers,trainers,boots,sandals,flip flops|nl:schoenen,sneakers,laarzen,sandalen,slippers|de:schuhe,turnschuhe,stiefel,sandalen,flip flops|fr:chaussures,baskets,bottes,sandales,tongs
shoe_repair|shoe_repair:5,craft=shoemaker:4,locksmith:2|en:shoe repair,heel repair,resole|nl:schoenmaker,schoenreparatie,hakken laten zetten|de:schuhreparatur,schuster,schuhmacher|fr:cordonnier,cordonnerie,reparation de chaussures
dry_cleaning|dry_cleaning:5,laundry:3,tailor:2|en:dry cleaning,dry cleaner,dry cleaners|nl:stomerij,chemisch reinigen|de:chemische reinigung,textilreinigung|fr:pressing,nettoyage a sec
alterations|tailor:5,craft=tailor:5,dry_cleaning:3,sewing+fabric:2|en:alterations,clothing alterations,hem trousers,tailor|nl:kleermaker,kleding vermaken,broek inkorten|de:anderungsschneiderei,schneider,hose kurzen|fr:retouches,retouche,couturier
sewing|sewing+fabric:5,craft:3,department_store:2,variety_store:2|en:fabric,thread,needle,needles,buttons,zip,zipper,yarn,wool,knitting needles|nl:stof,stoffen,garen,naald,naalden,knopen,rits,wol,breinaalden|de:stoff,stoffe,garn,nadel,nadeln,knopfe,reissverschluss,wolle,stricknadeln|fr:tissu,tissus,fil,aiguille,aiguilles,boutons,fermeture eclair,laine,aiguilles a tricoter
crafts|craft:5,stationery:4,variety_store:3,toys:2,art:2|en:craft supplies,art supplies,paint brushes,canvas,sketchbook,crayons,markers,beads|nl:hobbyspullen,knutselspullen,tekenspullen,canvas,schetsboek,stiften,kralen|de:bastelbedarf,kunstlerbedarf,leinwand,skizzenbuch,buntstifte,filzstifte,perlen|fr:loisirs creatifs,fournitures d art,toile,carnet de croquis,crayons de couleur,feutres,perles
party|party:5,variety_store:4,toys:2,supermarket:1|en:balloons,party supplies,party decorations,confetti,costume,fancy dress|nl:ballonnen,feestartikelen,slingers,verkleedkleding,carnavalskleding|de:luftballons,partyzubehor,partydeko,konfetti,kostum,verkleidung|fr:ballons,articles de fete,decorations de fete,confettis,deguisement
toys|toys:5,games:4,department_store:3,variety_store:3,books:1|en:toys,toy,board game,board games,puzzle,jigsaw,lego|nl:speelgoed,bordspel,gezelschapsspel,puzzel,lego|de:spielzeug,brettspiel,gesellschaftsspiel,puzzle,lego|fr:jouets,jouet,jeu de societe,jeux de societe,puzzle,lego
gift|gift:5,department_store:3,books:2,interior_decoration:2,variety_store:2|en:gift,a gift,a present,souvenir,souvenirs|nl:cadeau,cadeautje,kado,souvenir,souvenirs|de:geschenk,mitbringsel,souvenir,andenken|fr:cadeau,cadeaux,souvenir,souvenirs
kitchenware|houseware:5,kitchen:5,department_store:4,variety_store:4,hardware:2|en:frying pan,saucepan,kitchen knife,cutlery,plates,mugs,corkscrew,can opener,tin opener,kettle|nl:koekenpan,pan,keukenmes,bestek,borden,mokken,kurkentrekker,blikopener,waterkoker|de:pfanne,kochtopf,kuchenmesser,besteck,teller,kaffeetassen,korkenzieher,dosenoffner,wasserkocher|fr:poele,casserole,couteau de cuisine,couverts,assiettes,tasses,tire bouchon,ouvre boite,bouilloire
bedding|department_store:5,houseware:4,bed:4,interior_decoration:3,variety_store:3|en:towel,towels,bed sheets,sheets,pillow,duvet|nl:handdoek,handdoeken,lakens,hoeslaken,hoofdkussen,dekbed|de:handtuch,handtucher,bettwasche,laken,kopfkissen,bettdecke|fr:serviette de bain,serviettes de bain,draps,oreiller,couette
candles|variety_store:5,interior_decoration:4,houseware:4,gift:3,department_store:3,supermarket:2|en:candles,candle,tealights,tea lights|nl:kaarsen,kaars,waxinelichtjes,theelichtjes|de:kerzen,kerze,teelichter|fr:bougies,bougie,bougies chauffe plat
cleaning|supermarket:5,chemist:4,variety_store:4,hardware+doityourself:3,convenience:2|en:detergent,washing powder,laundry detergent,washing up liquid,dish soap,bin bags,rubbish bags,garbage bags,sponge,sponges,cleaning products,bleach|nl:wasmiddel,waspoeder,afwasmiddel,vuilniszakken,spons,sponsjes,schoonmaakmiddel,schoonmaakspullen,bleek|de:waschmittel,waschpulver,spulmittel,mullbeutel,mullsacke,schwamm,putzmittel,reinigungsmittel,bleiche|fr:lessive,liquide vaisselle,sacs poubelle,eponge,eponges,produits menagers,produits d entretien,javel
furniture|furniture:5,interior_decoration:3,second_hand+charity:2,department_store:2|en:furniture,sofa,couch,armchair,bookshelf,bookcase,wardrobe|nl:meubels,meubelen,fauteuil,boekenkast,kledingkast|de:mobel,sofa,couch,sessel,bucherregal,kleiderschrank|fr:meubles,canape,fauteuil,bibliotheque,etagere,armoire
frames|frame:5,variety_store:4,interior_decoration:3,department_store:3,art:2|en:picture frame,photo frame,frame,frames|nl:fotolijst,fotolijstje,schilderijlijst|de:bilderrahmen,fotorahmen,rahmen|fr:cadre,cadre photo,cadres
curtains|curtain:5,interior_decoration:4,department_store:3,doityourself:3,sewing+fabric:2|en:curtains,curtain,blinds|nl:gordijnen,gordijn,jaloezieen,rolgordijn|de:vorhange,vorhang,gardinen,jalousie,rollo|fr:rideaux,rideau,stores
rugs|carpet:5,interior_decoration:3,furniture:3,doityourself:2|en:rug,rugs,carpet,doormat|nl:vloerkleed,tapijt,deurmat|de:teppich,fussmatte|fr:tapis,paillasson
mattress|bed:5,furniture:3,department_store:2|en:mattress,bed frame|nl:matras,bedbodem|de:matratze,bettgestell|fr:matelas,sommier
sports|sports:5,outdoor:4,department_store:2,variety_store:1|en:tennis racket,tennis balls,yoga mat,swimsuit,swimming costume,swimming trunks,goggles,running shoes|nl:voetbal,tennisracket,tennisballen,yogamat,badpak,zwembroek,zwembril,hardloopschoenen|de:fussball,tennisschlager,tennisballe,yogamatte,badeanzug,badehose,schwimmbrille,laufschuhe|fr:ballon de foot,raquette de tennis,balles de tennis,tapis de yoga,maillot de bain,lunettes de natation,chaussures de course
camping|outdoor:5,sports:4,hardware+doityourself:1|en:tent,sleeping bag,camping gas,gas canister,hiking boots,head torch,headlamp,camping stove|nl:tent,slaapzak,gasbusje,wandelschoenen,hoofdlamp,kampeerbrander|de:zelt,schlafsack,gaskartusche,wanderschuhe,stirnlampe,campingkocher|fr:tente,sac de couchage,cartouche de gaz,chaussures de randonnee,lampe frontale,rechaud
luggage|bag:5,department_store:4,leather:3,outdoor:3,variety_store:2|en:suitcase,luggage,backpack,rucksack,bag,handbag,wallet|nl:koffer,rugzak,tas,tassen,handtas,portemonnee|de:koffer,rucksack,tasche,handtasche,geldborse,portemonnaie|fr:valise,sac a dos,sac,sac a main,portefeuille
jewellery|jewelry:5,watches:4,department_store:2,gift:2|en:jewellery,jewelry,earrings,necklace,bracelet,ring|nl:sieraden,oorbellen,ketting,armband,ring|de:schmuck,ohrringe,halskette,armband,ring|fr:bijoux,boucles d oreilles,collier,bracelet,bague
watch_battery|watches:5,jewelry:4,shoe_repair:2|en:watch battery,watch strap,watch repair|nl:horlogebatterij,horlogebandje,horloge reparatie|de:uhrenbatterie,uhrarmband,uhrenreparatur|fr:pile de montre,bracelet de montre,reparation de montre
music_gear|musical_instrument:5,music:3|en:guitar strings,guitar,ukulele,sheet music|nl:gitaarsnaren,gitaar,ukelele,bladmuziek|de:gitarrensaiten,gitarre,ukulele,musiknoten|fr:cordes de guitare,guitare,ukulele,partition
baby|baby_goods:5,chemist:4,supermarket:3,department_store:2|en:baby food,baby formula,formula milk,pacifier,dummy,baby clothes,pushchair,pram|nl:babyvoeding,flesvoeding,speen,babykleding,kinderwagen,buggy|de:babynahrung,babymilch,schnuller,babykleidung,kinderwagen|fr:petits pots,lait infantile,tetine,vetements bebe,poussette
car_parts|car_parts:5,amenity=fuel:4,tyres:2,hardware+doityourself:2|en:motor oil,engine oil,screenwash,windscreen wash,wiper blades,car battery,antifreeze|nl:motorolie,ruitenvloeistof,ruitenwissers,accu,antivries|de:motorol,scheibenwischwasser,scheibenwischer,autobatterie,frostschutz|fr:huile moteur,lave glace,essuie glaces,batterie de voiture,antigel
tyres|tyres:5,car_parts:3|en:tyres,tires,new tyres,winter tyres|nl:autobanden,winterbanden|de:reifen,autoreifen,winterreifen|fr:pneus,pneus hiver
coffee_beans|coffee:5,tea:3,deli:3,supermarket:3|en:coffee beans,ground coffee,coffee pods|nl:koffiebonen,gemalen koffie,koffiecups|de:kaffeebohnen,gemahlener kaffee,kaffeekapseln|fr:grains de cafe,cafe moulu,capsules de cafe
loose_tea|tea:5,coffee:3,deli:3,supermarket:2|en:loose tea,tea leaves,loose leaf tea|nl:losse thee,thee kopen|de:loser tee,teeblatter|fr:the en vrac,feuilles de the
sweets|confectionery+chocolate:5,supermarket:3,convenience:3,kiosk+newsagent:3|en:sweets,candy,licorice,liquorice,chocolates,box of chocolates|nl:snoep,dropjes,bonbons,doos bonbons|de:susigkeiten,bonbons,lakritz,pralinen|fr:bonbons,confiserie,reglisse,chocolats,boite de chocolats
cake_to_go|pastry:5,bakery:4,supermarket:2|en:birthday cake,pastries,whole cake|nl:verjaardagstaart,hele taart,taartje|de:torte,geburtstagstorte,kuchen kaufen|fr:gateau d anniversaire,un gateau,gateau entier
""".trimIndent()

    /** Every item of the catalogue. */
    val all: List<Goods> by lazy { load() }

    private val byId: Map<String, Goods> by lazy { all.associateBy { it.id } }

    fun byId(id: String): Goods? = byId[id]

    private fun load(): List<Goods> = TABLE.lines().filter { it.isNotBlank() }.map { line ->
        val cols = line.split('|')
        val sellers = cols[1].split(',').map { seller(it) }.sortedByDescending { it.likelihood }
        val phrases = LinkedHashMap<String, List<String>>()
        for (col in cols.drop(2)) {
            val lang = col.substringBefore(':')
            phrases[lang] = col.substringAfter(':').split(',').map { Text.fold(it) }.filter { it.isNotEmpty() }.distinct()
        }
        Goods(cols[0], phrases, sellers)
    }

    /** "hardware+doityourself:5" (shop values) or "amenity=pharmacy:5" / "craft=key_cutter+…". */
    private fun seller(token: String): Seller {
        val likelihood = token.substringAfterLast(':').toInt()
        require(likelihood in 1..5) { "likelihood 1..5: $token" }
        val spec = token.substringBeforeLast(':')
        val key = if ('=' in spec) spec.substringBefore('=') else "shop"
        val values = (if ('=' in spec) spec.substringAfter('=') else spec).split('+')
        val filter = if (values.size == 1) TagFilter.Equals(key, values[0]) else TagFilter.Matches(key, values.joinToString("|", "^(", ")$"))
        val preset = Presets.byId("$key/${values[0]}")
        return Seller(filter, likelihood, preset)
    }
}
