package app.atlas.ui.shop

import app.atlas.places.Text
import app.atlas.search.GoodsCatalogue

/** A kind of shop that sells an item, and how likely it has it: 5 surely, 1 perhaps. */
internal data class Seller(val kind: ShopKind, val likelihood: Int)

/**
 * Something on a shopping list ("sourdough", "havermelk") and where it is bought: the kinds of shop with how likely each
 * has it, best first, and the Open Food Facts category its prices are filed under (for Open Prices).
 */
internal class ShopItem(val id: String, val sellers: List<Seller>, val offCategory: String?, val names: Map<String, List<String>>) {
    /** Its name in [lang], else in English. */
    fun name(lang: String): String = (names[lang] ?: names["en"])?.firstOrNull() ?: id

    /** The likelihood [kind] has it (0: not at all). */
    fun likelihood(kind: ShopKind): Int = sellers.firstOrNull { it.kind == kind }?.likelihood ?: 0

    override fun toString() = "item:$id"
}

/**
 * The curated table of everyday items (about 300, in English and Dutch) and the kinds of shop that sell them, with
 * likelihoods; it classifies what people write on a list. Things it does not know (picture hooks, a phone charger)
 * are looked up in `:search`'s goods catalogue, whose likelihoods are on the same 1–5 scale.
 */
internal object ShopItems {
    // id|sellers|off category|en names|nl names. Sellers are ShopKind names (lower case) with a likelihood 1–5.
    private val TABLE = """
bread|bakery:5,supermarket:4,convenience:3,farm:1|en:breads|bread,loaf,wholemeal bread,white bread,brown bread|brood,volkorenbrood,witbrood,bruinbrood,brood volkoren
sourdough|bakery:5,supermarket:2,health_food:2|en:sourdough-breads|sourdough,sourdough bread|zuurdesem,zuurdesembrood,desembrood
baguette|bakery:5,supermarket:4,convenience:2|en:baguettes|baguette,french bread|stokbrood,baguette
rye_bread|bakery:4,supermarket:4,health_food:3|en:rye-breads|rye bread,pumpernickel|roggebrood
rolls|bakery:5,supermarket:4,convenience:2|en:bread-rolls|rolls,bread rolls,buns|broodjes,bolletjes,pistolets,kadetjes
croissants|bakery:5,supermarket:3,convenience:2|en:croissants|croissants,croissant|croissants,croissant
pastries|bakery:5,supermarket:2|en:viennoiseries|pastries,pastry,danish|gebak,gebakjes,koffiebroodjes
cake|bakery:5,supermarket:3,convenience:1|en:cakes|cake,birthday cake,pie,apple pie,tart|taart,cake,appeltaart,verjaardagstaart,vlaai
muffins|bakery:4,supermarket:3,convenience:2|en:muffins|muffins,muffin|muffins,muffin
bagels|bakery:4,supermarket:3|en:bagels|bagels,bagel|bagels,bagel
pita|bakery:3,supermarket:4,world:5|en:pita-breads|pita,pitta,pita bread,naan,flatbread|pita,pitabroodje,turks brood,naan,platbrood
tortillas|supermarket:5,world:4,convenience:2|en:tortillas|tortillas,wraps,tortilla wraps|tortilla,wraps,tortilla wraps
crackers|supermarket:5,convenience:3,health_food:2|en:crackers|crackers,rice cakes,rusks,crispbread|crackers,rijstwafels,beschuit,knäckebröd,knackebrod
cookies|supermarket:5,bakery:4,convenience:4|en:biscuits|cookies,biscuits,shortbread|koekjes,biscuits,koek
stroopwafels|supermarket:5,bakery:4,convenience:4|en:stroopwafels|stroopwafels,syrup waffles|stroopwafels
gingerbread|supermarket:5,bakery:4|en:gingerbreads|gingerbread,spiced cake|ontbijtkoek,peperkoek,kruidkoek
apples|greengrocer:5,supermarket:5,farm:4,convenience:2|en:apples|apples,apple|appels,appel
bananas|greengrocer:5,supermarket:5,convenience:3|en:bananas|bananas,banana|bananen,banaan
oranges|greengrocer:5,supermarket:5|en:oranges|oranges,orange,mandarins,clementines|sinaasappels,sinaasappel,mandarijnen,clementines
lemons|greengrocer:5,supermarket:5,world:2|en:lemons|lemons,lemon|citroenen,citroen
limes|greengrocer:5,supermarket:4,world:4|en:limes|limes,lime|limoenen,limoen,limes
grapes|greengrocer:5,supermarket:5|en:grapes|grapes|druiven
strawberries|greengrocer:5,supermarket:5,farm:4|en:strawberries|strawberries|aardbeien
blueberries|greengrocer:5,supermarket:5|en:blueberries|blueberries,berries|blauwe bessen,bosbessen,bessen
raspberries|greengrocer:5,supermarket:5|en:raspberries|raspberries|frambozen
pears|greengrocer:5,supermarket:5,farm:3|en:pears|pears,pear|peren,peer,stoofperen
mango|greengrocer:5,supermarket:4,world:4|en:mangoes|mango,mangoes|mango,mango's
avocado|greengrocer:5,supermarket:5,world:3|en:avocados|avocado,avocados|avocado,avocado's
pineapple|greengrocer:5,supermarket:4|en:pineapples|pineapple|ananas
melon|greengrocer:5,supermarket:4|en:melons|melon,watermelon|meloen,watermeloen
kiwi|greengrocer:5,supermarket:5|en:kiwis|kiwi,kiwis|kiwi,kiwi's
peaches|greengrocer:5,supermarket:4|en:peaches|peaches,nectarines,apricots|perziken,nectarines,abrikozen
plums|greengrocer:5,supermarket:4,farm:3|en:plums|plums|pruimen
cherries|greengrocer:5,supermarket:4|en:cherries|cherries|kersen
dates|world:5,greengrocer:4,supermarket:4|en:dates|dates,figs,dried figs|dadels,vijgen,gedroogde vijgen
tomatoes|greengrocer:5,supermarket:5,farm:3|en:tomatoes|tomatoes,tomato,cherry tomatoes|tomaten,tomaat,cherrytomaten,trostomaten
cucumber|greengrocer:5,supermarket:5|en:cucumbers|cucumber,cucumbers|komkommer,komkommers
lettuce|greengrocer:5,supermarket:5,farm:3|en:lettuces|lettuce,salad,rocket,arugula,mixed salad|sla,ijsbergsla,rucola,veldsla,slamix
spinach|greengrocer:5,supermarket:5|en:spinaches|spinach|spinazie
kale|greengrocer:5,supermarket:4,farm:3|en:kales|kale|boerenkool
broccoli|greengrocer:5,supermarket:5|en:broccoli|broccoli|broccoli
cauliflower|greengrocer:5,supermarket:5|en:cauliflowers|cauliflower|bloemkool
carrots|greengrocer:5,supermarket:5,farm:4|en:carrots|carrots,carrot|wortels,wortel,winterpeen,worteltjes
potatoes|greengrocer:5,supermarket:5,farm:5|en:potatoes|potatoes,potato,new potatoes|aardappelen,aardappels,krieltjes
sweet_potatoes|greengrocer:5,supermarket:4,world:4|en:sweet-potatoes|sweet potatoes,sweet potato|zoete aardappel,zoete aardappelen,bataat
onions|greengrocer:5,supermarket:5,farm:3,convenience:2|en:onions|onions,onion,red onions,shallots|uien,ui,rode uien,sjalotten
garlic|greengrocer:5,supermarket:5,world:4,convenience:2|en:garlic|garlic|knoflook
ginger|greengrocer:5,supermarket:4,world:5|en:ginger|ginger,fresh ginger|gember,verse gember
peppers|greengrocer:5,supermarket:5|en:bell-peppers|peppers,bell peppers,paprika|paprika,paprika's,puntpaprika
chillies|greengrocer:5,world:5,supermarket:4|en:chili-peppers|chillies,chilli,chili,chilis|pepers,rode peper,spaanse peper,chilipeper
courgette|greengrocer:5,supermarket:5|en:zucchini|courgette,zucchini|courgette
aubergine|greengrocer:5,supermarket:5,world:3|en:eggplants|aubergine,eggplant|aubergine
mushrooms|greengrocer:5,supermarket:5|en:mushrooms|mushrooms|champignons,paddenstoelen,kastanjechampignons
leeks|greengrocer:5,supermarket:5|en:leeks|leeks,leek|prei
celery|greengrocer:5,supermarket:4|en:celery|celery|bleekselderij,selderij,knolselderij
fennel|greengrocer:5,supermarket:3|en:fennels|fennel|venkel
asparagus|greengrocer:5,supermarket:4,farm:4|en:asparagus|asparagus|asperges
green_beans|greengrocer:5,supermarket:5|en:green-beans|green beans,beans fresh,runner beans|sperziebonen,snijbonen,haricots verts
peas|greengrocer:4,supermarket:5|en:peas|peas,sugar snaps,mangetout|erwten,doperwten,peultjes,sugarsnaps
corn|greengrocer:4,supermarket:5|en:sweet-corns|corn,sweetcorn,corn on the cob|mais,maiskolf,maïs
beetroot|greengrocer:5,supermarket:4|en:beetroots|beetroot,beets|bieten,rode bieten
pumpkin|greengrocer:5,supermarket:4,farm:4|en:pumpkins|pumpkin,squash,butternut|pompoen,flespompoen
cabbage|greengrocer:5,supermarket:4|en:cabbages|cabbage,red cabbage,pointed cabbage|kool,rode kool,spitskool,witte kool,savooiekool
sprouts|greengrocer:5,supermarket:4|en:brussels-sprouts|brussels sprouts,sprouts|spruitjes
radishes|greengrocer:5,supermarket:4|en:radishes|radishes|radijs,radijsjes
endive|greengrocer:5,supermarket:5|en:endives|endive,chicory|witlof,andijvie
spring_onions|greengrocer:5,supermarket:4,world:4|en:spring-onions|spring onions,scallions|lente-uitjes,bosuitjes
herbs|greengrocer:5,supermarket:4,world:3|en:fresh-herbs|herbs,fresh herbs,basil,parsley,coriander,cilantro,mint,dill,chives,rosemary,thyme|kruiden,verse kruiden,basilicum,peterselie,koriander,munt,dille,bieslook,rozemarijn,tijm
milk|supermarket:5,convenience:5,farm:4,cheese:2|en:milks|milk,semi skimmed milk,whole milk,skimmed milk|melk,halfvolle melk,volle melk,magere melk
buttermilk|supermarket:5,convenience:3,farm:3|en:buttermilks|buttermilk|karnemelk
oat_milk|supermarket:5,convenience:3,health_food:4|en:oat-based-drinks|oat milk,oat drink|havermelk,haverdrank,haver drink
soy_milk|supermarket:5,convenience:3,health_food:4,world:3|en:soy-milks|soy milk,soya milk,soy drink|sojamelk,sojadrank
almond_milk|supermarket:5,health_food:4,convenience:2|en:almond-based-drinks|almond milk,almond drink|amandelmelk,amandeldrank
yoghurt|supermarket:5,convenience:4,farm:3,cheese:2|en:yogurts|yoghurt,yogurt,greek yoghurt,greek yogurt|yoghurt,griekse yoghurt,magere yoghurt
quark|supermarket:5,convenience:2|en:quarks|quark,skyr|kwark,skyr
custard|supermarket:5,convenience:3|en:custards|custard,pudding|vla,pudding,custard
cream|supermarket:5,convenience:3,cheese:2|en:creams|cream,whipping cream,sour cream,creme fraiche|room,slagroom,kookroom,zure room,creme fraiche
butter|supermarket:5,convenience:4,cheese:3,farm:3|en:butters|butter|boter,roomboter
margarine|supermarket:5,convenience:4|en:margarines|margarine,spread|margarine,halvarine
eggs|supermarket:5,farm:5,convenience:4,greengrocer:2|en:eggs|eggs,egg,free range eggs|eieren,ei,scharreleieren
cheese|cheese:5,supermarket:5,deli:4,convenience:3,farm:3|en:cheeses|cheese,cheddar,brie,camembert|kaas,jonge kaas,belegen kaas,oude kaas,brie,camembert
gouda|cheese:5,supermarket:5,deli:3,farm:4|en:goudas|gouda,dutch cheese|goudse kaas,gouda,boerenkaas
feta|cheese:5,supermarket:4,deli:4,world:5|en:fetas|feta,white cheese|feta,witte kaas
mozzarella|cheese:5,supermarket:5,deli:4|en:mozzarella|mozzarella,burrata|mozzarella,burrata
parmesan|cheese:5,supermarket:4,deli:5|en:parmigiano-reggiano|parmesan,parmigiano,pecorino|parmezaan,parmezaanse kaas,pecorino
goat_cheese|cheese:5,supermarket:4,deli:4,farm:3|en:goat-cheeses|goat cheese,goats cheese|geitenkaas
cream_cheese|supermarket:5,cheese:3,convenience:2|en:cream-cheeses|cream cheese,cottage cheese,ricotta,mascarpone|roomkaas,huttenkase,huttenkäse,ricotta,mascarpone
chicken|butcher:5,supermarket:5,world:3|en:chickens|chicken,chicken breast,chicken thighs,chicken fillet|kip,kipfilet,kippendijen,kippenpoten
mince|butcher:5,supermarket:5|en:ground-meats|mince,minced meat,ground beef|gehakt,rundergehakt,half om half
beef|butcher:5,supermarket:4|en:beefs|beef,steak,stewing beef|rundvlees,biefstuk,steak,riblappen,stoofvlees
pork|butcher:5,supermarket:4|en:porks|pork,pork chops,pork tenderloin|varkensvlees,karbonade,varkenshaas,speklappen
bacon|butcher:5,supermarket:5,deli:3|en:bacons|bacon,pancetta,lardons|spek,ontbijtspek,spekjes,pancetta
sausages|butcher:5,supermarket:5,deli:3|en:sausages|sausages,sausage,frankfurters|worst,worstjes,braadworst,rookworst,knakworst
ham|butcher:4,supermarket:5,deli:5,convenience:3|en:hams|ham,cooked ham,prosciutto|ham,beenham,rauwe ham,prosciutto
salami|deli:5,butcher:4,supermarket:5,cheese:3|en:salamis|salami,chorizo,cold cuts|salami,chorizo,vleeswaren,fuet
lamb|butcher:5,supermarket:3,world:4|en:lambs|lamb,lamb chops|lamsvlees,lamskoteletten,lamsrack
turkey|butcher:5,supermarket:4|en:turkeys|turkey|kalkoen,kalkoenfilet
halal_meat|butcher:5,world:5|en:halal-meats|halal meat,halal chicken|halal vlees,halal kip
salmon|seafood:5,supermarket:5|en:salmons|salmon,smoked salmon|zalm,gerookte zalm,zalmfilet
fish|seafood:5,supermarket:4|en:fishes|fish,white fish,cod,hake,fish fillet|vis,witvis,kabeljauw,heek,visfilet,koolvis
shrimp|seafood:5,supermarket:4,world:3|en:shrimps|shrimp,prawns,shrimps|garnalen,scampi,gamba's
herring|seafood:5,supermarket:3|en:herrings|herring,pickled herring|haring,hollandse nieuwe,zure haring,rolmops
mussels|seafood:5,supermarket:3|en:mussels|mussels,oysters,clams|mosselen,oesters,kokkels
mackerel|seafood:5,supermarket:4|en:mackerels|mackerel,smoked mackerel,sardines|makreel,gerookte makreel,sardines
tuna|supermarket:5,convenience:4,seafood:3|en:canned-tunas|tuna,tinned tuna,canned tuna|tonijn,tonijn in blik
pasta|supermarket:5,convenience:4,deli:3|en:pastas|pasta,spaghetti,penne,fusilli,macaroni,lasagne|pasta,spaghetti,penne,macaroni,lasagne,fusilli
fresh_pasta|deli:5,supermarket:4|en:fresh-pastas|fresh pasta,ravioli,tortellini,gnocchi|verse pasta,ravioli,tortellini,gnocchi
rice|supermarket:5,world:5,convenience:3|en:rices|rice,basmati,jasmine rice,brown rice,risotto rice|rijst,basmatirijst,pandanrijst,zilvervliesrijst,risottorijst
sushi_rice|world:5,supermarket:3|en:sushi-rices|sushi rice|sushirijst
noodles|world:5,supermarket:5,convenience:3|en:noodles|noodles,egg noodles,rice noodles,udon,ramen|noedels,mie,rijstnoedels,udon,ramen
couscous|supermarket:5,world:5|en:couscous|couscous,bulgur,quinoa|couscous,bulgur,quinoa
flour|supermarket:5,health_food:3,bakery:1|en:flours|flour,plain flour,self raising flour|bloem,meel,zelfrijzend bakmeel,volkorenmeel
sugar|supermarket:5,convenience:4|en:sugars|sugar,brown sugar,icing sugar|suiker,basterdsuiker,poedersuiker
salt|supermarket:5,convenience:3,world:3|en:salts|salt,sea salt|zout,zeezout
pepper|supermarket:5,world:5|en:black-peppers|pepper,black pepper|peper,zwarte peper
spices|world:5,supermarket:4,deli:3|en:spices|spices,cumin,paprika powder,cinnamon,curry powder,turmeric,nutmeg,oregano|specerijen,kruiden gedroogd,komijn,paprikapoeder,kaneel,kerriepoeder,kurkuma,nootmuskaat,oregano
saffron|world:5,deli:4,supermarket:3|en:saffrons|saffron|saffraan
oil|supermarket:5,convenience:3,world:4,deli:3|en:vegetable-oils|oil,cooking oil,sunflower oil|olie,zonnebloemolie,bakolie,frituurolie
olive_oil|supermarket:5,deli:5,world:4,convenience:2|en:olive-oils|olive oil,extra virgin olive oil|olijfolie
vinegar|supermarket:5,deli:3,world:3|en:vinegars|vinegar,balsamic vinegar|azijn,balsamico,balsamicoazijn
soy_sauce|supermarket:5,world:5|en:soy-sauces|soy sauce,ketjap,tamari|sojasaus,ketjap,ketjap manis
fish_sauce|world:5,supermarket:3|en:fish-sauces|fish sauce,oyster sauce,hoisin|vissaus,oestersaus,hoisinsaus
sambal|world:5,supermarket:4|en:sambals|sambal,chilli paste,sriracha,harissa|sambal,sambal oelek,sriracha,harissa
curry_paste|world:5,supermarket:4|en:curry-pastes|curry paste,red curry paste,green curry paste|currypasta,rode currypasta,groene currypasta,boemboe
coconut_milk|world:5,supermarket:5|en:coconut-milks|coconut milk,coconut cream|kokosmelk,santen
ketchup|supermarket:5,convenience:4|en:ketchup|ketchup,tomato ketchup|ketchup,tomatenketchup
mayonnaise|supermarket:5,convenience:4|en:mayonnaises|mayonnaise,mayo|mayonaise,mayo,fritessaus
mustard|supermarket:5,convenience:3,deli:3|en:mustards|mustard|mosterd
pesto|supermarket:5,deli:5|en:pestos|pesto|pesto
olives|deli:5,supermarket:4,world:5|en:olives|olives|olijven
pickles|supermarket:5,deli:3,world:3|en:pickles|pickles,gherkins,capers|augurken,zilveruitjes,kappertjes,tafelzuur
peanut_butter|supermarket:5,convenience:4,health_food:3|en:peanut-butters|peanut butter|pindakaas
jam|supermarket:5,convenience:4,farm:3,deli:2|en:jams|jam,marmalade|jam,confiture,marmelade
honey|supermarket:5,farm:4,health_food:4,deli:3|en:honeys|honey|honing
chocolate_spread|supermarket:5,convenience:4|en:chocolate-spreads|chocolate spread,nutella|chocoladepasta,nutella
sprinkles|supermarket:5,convenience:4|en:chocolate-sprinkles|sprinkles,chocolate sprinkles|hagelslag,vlokken,muisjes
cereal|supermarket:5,convenience:3,health_food:3|en:breakfast-cereals|cereal,cornflakes,muesli,granola|ontbijtgranen,cornflakes,muesli,granola,cruesli
oats|supermarket:5,health_food:4,convenience:2|en:rolled-oats|oats,porridge oats,rolled oats,porridge|havermout,havervlokken,pap
nuts|supermarket:5,world:4,health_food:4,convenience:3|en:nuts|nuts,almonds,cashews,walnuts,peanuts,mixed nuts|noten,amandelen,cashewnoten,walnoten,pinda's,notenmix
dried_fruit|supermarket:5,world:4,health_food:4|en:dried-fruits|raisins,dried fruit,dried apricots|rozijnen,gedroogd fruit,gedroogde abrikozen
crisps|supermarket:5,convenience:5|en:crisps|crisps,chips,tortilla chips,popcorn|chips,tortillachips,popcorn,zoutjes
chocolate|sweets:5,supermarket:5,convenience:5|en:chocolates|chocolate,chocolate bar,dark chocolate|chocolade,reep chocolade,pure chocolade,melkchocolade
sweets|sweets:5,supermarket:5,convenience:5|en:candies|sweets,candy,gummies,liquorice|snoep,drop,winegums,snoepjes,zuurtjes
ice_cream|supermarket:5,convenience:4,frozen:4|en:ice-creams|ice cream,ice lollies|ijs,ijsjes,roomijs,waterijsjes
tinned_tomatoes|supermarket:5,convenience:4,world:3|en:canned-tomatoes|tinned tomatoes,chopped tomatoes,passata,tomato paste,tomato puree|tomatenblokjes,gepelde tomaten,passata,tomatenpuree
beans|supermarket:5,world:5,convenience:3,health_food:3|en:canned-legumes|beans,chickpeas,lentils,kidney beans,black beans,baked beans|bonen,kikkererwten,linzen,kidneybonen,zwarte bonen,bruine bonen,witte bonen
soup|supermarket:5,convenience:4|en:soups|soup,stock,stock cubes,broth|soep,bouillon,bouillonblokjes
tofu|supermarket:5,world:5,health_food:5|en:tofu|tofu,tempeh,seitan|tofu,tempeh,seitan
hummus|supermarket:5,world:5,deli:4|en:hummus|hummus,houmous,falafel,tzatziki|hummus,falafel,tzatziki
kimchi|world:5,health_food:3,supermarket:2|en:kimchi|kimchi,miso,nori,seaweed|kimchi,miso,nori,zeewier
tahini|world:5,health_food:4,supermarket:3|en:tahini|tahini,sesame paste|tahin,tahini,sesampasta
frozen_veg|supermarket:5,frozen:5,convenience:2|en:frozen-vegetables|frozen vegetables,frozen peas,frozen spinach|diepvriesgroenten,diepvries spinazie,diepvries erwten
frozen_pizza|supermarket:5,convenience:4,frozen:4|en:frozen-pizzas|frozen pizza,pizza|diepvriespizza,pizza
fries|supermarket:5,frozen:5|en:frozen-fries|fries,oven chips,frozen fries|patat,friet,ovenfrites,diepvriespatat
fish_fingers|supermarket:5,frozen:5|en:fish-fingers|fish fingers,fish sticks|vissticks
water|supermarket:5,convenience:5,newsagent:3|en:waters|water,mineral water,sparkling water|water,mineraalwater,bruisend water,spa
juice|supermarket:5,convenience:5,greengrocer:2|en:juices|juice,orange juice,apple juice|sap,jus d'orange,sinaasappelsap,appelsap
soft_drinks|supermarket:5,convenience:5,drinks:3,newsagent:3|en:sodas|cola,soda,lemonade,soft drinks,tonic,ice tea|cola,frisdrank,limonade,tonic,ijsthee,sinas
energy_drink|convenience:5,supermarket:5,newsagent:4|en:energy-drinks|energy drink,red bull|energiedrank,red bull
kombucha|health_food:5,supermarket:3|en:kombuchas|kombucha|kombucha
beer|supermarket:5,drinks:5,convenience:4|en:beers|beer,lager,ipa,craft beer|bier,pils,speciaalbier,biertjes
wine|drinks:5,supermarket:5,convenience:2|en:wines|wine,red wine,white wine,rose|wijn,rode wijn,witte wijn,rose,rosé
sparkling_wine|drinks:5,supermarket:4|en:sparkling-wines|prosecco,champagne,cava,sparkling wine|prosecco,champagne,cava,mousserende wijn
spirits|drinks:5,supermarket:2|en:spirits|gin,whisky,whiskey,vodka,rum,tequila,spirits,liqueur|gin,whisky,wodka,rum,jenever,sterke drank,likeur
cider|drinks:5,supermarket:4|en:ciders|cider|cider
coffee|supermarket:5,coffee:5,convenience:4|en:coffees|coffee,ground coffee,coffee pads,coffee capsules,instant coffee|koffie,gemalen koffie,koffiepads,koffiecups,oploskoffie
coffee_beans|coffee:5,supermarket:4|en:coffee-beans|coffee beans,whole beans|koffiebonen,bonen
tea|supermarket:5,coffee:5,convenience:4,world:3|en:teas|tea,tea bags,green tea,herbal tea,mint tea|thee,theezakjes,groene thee,kruidenthee,muntthee
baby_food|supermarket:5,chemist:5,pharmacy:3|en:baby-foods|baby food,formula,baby milk|babyvoeding,flesvoeding,babymelk,potjes
nappies|chemist:5,supermarket:5,pharmacy:3|en:diapers|nappies,diapers,baby wipes|luiers,billendoekjes
toilet_paper|supermarket:5,chemist:5,convenience:4,variety:3|en:toilet-papers|toilet paper,toilet roll,loo roll|wc papier,wc-papier,toiletpapier,wcpapier
kitchen_roll|supermarket:5,chemist:4,convenience:3,variety:3|en:paper-towels|kitchen roll,paper towels,tissues|keukenrol,keukenpapier,tissues,zakdoekjes
dish_soap|supermarket:5,chemist:5,convenience:3,variety:4,bulk:3|en:dishwashing-liquids|washing up liquid,dish soap,dishwasher tablets|afwasmiddel,vaatwastabletten,vaatwasblokjes
laundry_detergent|supermarket:5,chemist:5,variety:4,bulk:3|en:laundry-detergents|laundry detergent,washing powder,washing liquid,fabric softener|wasmiddel,waspoeder,vloeibaar wasmiddel,wasverzachter
cleaning|supermarket:5,chemist:5,variety:5,hardware:3,bulk:3|en:cleaning-products|cleaning spray,bleach,sponges,cleaning products,all purpose cleaner|schoonmaakmiddel,allesreiniger,bleek,chloor,sponsjes,schoonmaakspray
bin_bags|supermarket:5,variety:5,hardware:4,chemist:3|en:bin-bags|bin bags,rubbish bags,trash bags|vuilniszakken,afvalzakken,pedaalemmerzakken
foil|supermarket:5,variety:5,chemist:3|en:aluminium-foils|aluminium foil,tin foil,cling film,baking paper,freezer bags|aluminiumfolie,huishoudfolie,bakpapier,vershoudfolie,diepvrieszakjes
toothpaste|chemist:5,supermarket:5,pharmacy:3,convenience:3|en:toothpastes|toothpaste,toothbrush,dental floss,mouthwash|tandpasta,tandenborstel,flosdraad,mondwater
shampoo|chemist:5,supermarket:5,pharmacy:2,bulk:3|en:shampoos|shampoo,conditioner,shower gel,soap,hand soap|shampoo,conditioner,douchegel,zeep,handzeep
deodorant|chemist:5,supermarket:5,convenience:3|en:deodorants|deodorant,deo|deodorant,deo
razors|chemist:5,supermarket:5,convenience:2|en:razors|razors,razor blades,shaving foam|scheermesjes,scheerschuim,scheermes
sunscreen|chemist:5,supermarket:4,pharmacy:4|en:sunscreens|sunscreen,sun cream,after sun|zonnebrand,zonnebrandcreme,aftersun
tampons|chemist:5,supermarket:5,pharmacy:3,convenience:3|en:tampons|tampons,pads,sanitary pads,panty liners|tampons,maandverband,inlegkruisjes
condoms|chemist:5,supermarket:4,pharmacy:4,convenience:3|en:condoms|condoms|condooms
painkillers|pharmacy:5,chemist:5,supermarket:3,convenience:2|en:pain-relievers|painkillers,paracetamol,ibuprofen,aspirin|pijnstillers,paracetamol,ibuprofen,aspirine
plasters|pharmacy:5,chemist:5,supermarket:3|en:plasters|plasters,band aids,bandage|pleisters,verband,verbandmiddelen
cold_medicine|pharmacy:5,chemist:5,supermarket:2|en:cold-medicines|cough syrup,cold medicine,throat lozenges,nasal spray|hoestdrank,keeltabletten,neusspray,verkoudheidsmiddel
vitamins|chemist:5,pharmacy:5,health_food:5,supermarket:3|en:vitamins|vitamins,vitamin d,vitamin c,supplements|vitamines,vitamine d,vitamine c,supplementen
prescription|pharmacy:5|en:prescription-drugs|prescription,medicine,medication,prescription pickup|recept,medicijnen,medicatie,herhaalrecept
lens_fluid|chemist:5,pharmacy:4|en:contact-lens-solutions|contact lens solution,lens fluid|lenzenvloeistof
cotton_pads|chemist:5,supermarket:4|en:cotton-pads|cotton pads,cotton buds,make up remover|wattenschijfjes,wattenstaafjes,make-up remover
hand_cream|chemist:5,pharmacy:3,supermarket:4|en:hand-creams|hand cream,lip balm,moisturiser|handcreme,lippenbalsem,dagcreme,bodylotion
batteries|supermarket:4,chemist:4,hardware:4,variety:4,convenience:3,newsagent:2|en:batteries|batteries,battery,aa batteries,aaa batteries|batterijen,batterij
light_bulbs|hardware:5,variety:4,supermarket:2|en:light-bulbs|light bulbs,light bulb,led bulb|lampjes,gloeilamp,ledlamp,peertje
candles|variety:5,supermarket:3,hardware:3|en:candles|candles,tea lights,matches,lighter|kaarsen,waxinelichtjes,lucifers,aansteker
flowers|florist:5,supermarket:3,greengrocer:2|en:flowers|flowers,bouquet,tulips,roses,plants|bloemen,boeket,bos bloemen,tulpen,rozen,planten
newspaper|newsagent:5,convenience:3,supermarket:3|en:newspapers|newspaper,magazine,paper|krant,tijdschrift,blad
stamps|newsagent:5,supermarket:3,convenience:2|en:stamps|stamps,postage stamps|postzegels,postzegel
cat_food|supermarket:5,pet:5|en:cat-foods|cat food,cat litter|kattenvoer,kattenbakvulling,kattengrit
dog_food|supermarket:4,pet:5|en:dog-foods|dog food,dog treats,poo bags|hondenvoer,hondensnoepjes,poepzakjes
ice|supermarket:4,convenience:4|en:ice-cubes|ice cubes,ice|ijsblokjes,ijs blokjes
charcoal|supermarket:4,hardware:4,variety:3|en:charcoals|charcoal,barbecue,firelighters|houtskool,barbecue,aanmaakblokjes
bulk_grains|bulk:5,health_food:4,supermarket:2|en:cereal-grains|grains loose,lentils loose,bulk rice,loose oats|los graan,losse linzen,los,granen
eggs_vending|farm:5|en:farm-eggs|farm eggs,fresh farm milk,raw milk|boereneieren,rauwe melk,boerenmelk
spring_rolls|world:5,supermarket:3,frozen:3|en:spring-rolls|spring rolls,dumplings,gyoza|loempia,loempia's,dumplings,gyoza
bread_wholegrain|bakery:5,supermarket:4,health_food:4|en:wholemeal-breads|wholegrain bread,seeded bread,multigrain bread|meergranenbrood,volkoren meergranen,waldkorn
toast|supermarket:5,bakery:3,convenience:3|en:sliced-breads|toast,toast bread,sliced bread|tosti brood,toastbrood,witbrood gesneden
brioche|bakery:5,supermarket:4|en:brioches|brioche,brioche buns|brioche,briochebroodjes
pretzel|bakery:5,supermarket:3|en:pretzels|pretzel,pretzels|pretzel,pretzels
donuts|bakery:5,supermarket:3,convenience:2|en:doughnuts|donuts,doughnuts|donuts,oliebollen,donut
waffles|supermarket:5,bakery:3,convenience:3|en:waffles|waffles,waffle|wafels,wafel
pancake_mix|supermarket:5,convenience:2,world:2|en:pancake-mixes|pancake mix,pancakes|pannenkoekmix,pannenkoeken
baking_powder|supermarket:5,convenience:2|en:baking-powders|baking powder,yeast,baking soda|bakpoeder,gist,baksoda
cocoa|supermarket:5,health_food:3,convenience:2|en:cocoas|cocoa,cocoa powder,hot chocolate|cacao,cacaopoeder,chocolademelk
muesli|supermarket:5,health_food:5,bulk:4|en:mueslis|muesli,granola|muesli,granola
porridge|supermarket:5,health_food:4,bulk:4|en:porridges|porridge,porridge oats|havermout,pap
rice_cakes|supermarket:5,health_food:5|en:rice-cakes|rice cakes|rijstwafels
lentils|supermarket:5,world:5,health_food:4,bulk:5|en:lentils|lentils,red lentils,chickpeas|linzen,rode linzen,kikkererwten
quinoa|supermarket:4,health_food:5,bulk:5,world:3|en:quinoas|quinoa,bulgur,millet|quinoa,bulgur,gierst
seeds|health_food:5,supermarket:4,bulk:5|en:seeds|chia seeds,sunflower seeds,pumpkin seeds,flax seeds,sesame|chiazaad,zonnebloempitten,pompoenpitten,lijnzaad,sesamzaad
almonds|supermarket:5,health_food:5,bulk:5,world:3|en:almonds|almonds,cashews,walnuts,hazelnuts,pistachios|amandelen,cashewnoten,walnoten,hazelnoten,pistachenoten
raisins|supermarket:5,health_food:4,bulk:4,world:3|en:raisins|raisins,sultanas,prunes|rozijnen,krenten,pruimen
tahini_paste|world:5,health_food:4,supermarket:3|en:tahini|sesame paste|sesampasta
harissa|world:5,supermarket:3,deli:3|en:harissa|harissa,za'atar,sumac,ras el hanout|harissa,zaatar,sumak,ras el hanout
miso|world:5,health_food:4,supermarket:3|en:misos|miso,nori,wasabi,mirin|miso,nori,wasabi,mirin
rice_paper|world:5,supermarket:2|en:rice-papers|rice paper,spring roll wrappers|rijstpapier,loempiavellen
coconut_water|supermarket:4,world:4,health_food:4,convenience:2|en:coconut-waters|coconut water|kokoswater
paneer|world:5,supermarket:3|en:paneers|paneer,halloumi|paneer,halloumi
plantain|world:5,greengrocer:4,supermarket:2|en:plantains|plantain,cassava,yam|bakbanaan,cassave,yam
baklava|world:5,bakery:3,sweets:4,deli:3|en:baklavas|baklava,turkish delight|baklava,turks fruit
tempeh|health_food:5,world:4,supermarket:4|en:tempeh|tempeh,seitan|tempeh,seitan
vegan_cheese|health_food:5,supermarket:4|en:vegan-cheeses|vegan cheese,plant based cheese|vegan kaas,plantaardige kaas
sausage_roll|bakery:5,supermarket:3,convenience:3|en:sausage-rolls|sausage roll,saucijzenbroodje|saucijzenbroodje,worstenbroodje
meatballs|butcher:5,supermarket:5|en:meatballs|meatballs|gehaktballen,balletjes
schnitzel|butcher:5,supermarket:4|en:schnitzels|schnitzel,cutlet|schnitzel,kotelet,karbonade
rib_eye|butcher:5,supermarket:3|en:steaks|ribeye,sirloin,fillet steak|ribeye,entrecote,ossenhaas
duck|butcher:5,supermarket:3,world:3|en:ducks|duck,duck breast|eend,eendenborst
liver|butcher:5,supermarket:3|en:livers|liver,kidney|lever,nier
smoked_salmon|supermarket:5,seafood:5,deli:4|en:smoked-salmons|smoked salmon,lox|gerookte zalm
cod|seafood:5,supermarket:4|en:cods|cod,haddock,plaice,sole|kabeljauw,schelvis,schol,tong
oysters|seafood:5,deli:3|en:oysters|oysters,crab,lobster,scallops|oesters,krab,kreeft,sint jakobsschelpen
raw_herring|seafood:5|en:herrings|haring,raw herring|haring,hollandse nieuwe
smoked_cheese|cheese:5,supermarket:4,deli:4|en:smoked-cheeses|smoked cheese,brie,blue cheese,gorgonzola|rookkaas,blauwe kaas,gorgonzola
cheese_spread|supermarket:5,cheese:2|en:cheese-spreads|cheese spread,cheese slices|smeerkaas,kaasplakken,plakken kaas
olives_deli|deli:5,world:4,supermarket:4|en:olives|marinated olives,antipasti,sun dried tomatoes|gemarineerde olijven,antipasti,zongedroogde tomaten
charcuterie|deli:5,butcher:4,supermarket:4|en:cured-meats|prosciutto,chorizo,pate|prosciutto,chorizo,paté
fresh_soup|deli:4,supermarket:5,greengrocer:2|en:soups|fresh soup,pea soup|verse soep,erwtensoep
salad_dressing|supermarket:5,deli:2|en:salad-dressings|salad dressing,vinaigrette|slasaus,dressing,vinaigrette
stock|supermarket:5,world:3|en:stocks|stock cube,stock,bouillon|bouillonblokjes,bouillon,fond
tomato_paste|supermarket:5,world:4|en:tomato-pastes|tomato paste,passata|tomatenpuree,passata
mushrooms_dried|world:5,health_food:3,supermarket:3|en:dried-mushrooms|dried mushrooms,porcini|gedroogde paddenstoelen,eekhoorntjesbrood
cooking_cream|supermarket:5,convenience:3|en:creams|cooking cream,whipped cream|kookroom,slagroom,room
mascarpone_tiramisu|supermarket:5,cheese:3,deli:3|en:mascarpones|ladyfingers,tiramisu|lange vingers,tiramisu
ice_lollies|supermarket:5,convenience:4,frozen:5|en:ice-lollies|ice lollies,popsicles|ijsjes,waterijs
frozen_fruit|supermarket:5,frozen:5|en:frozen-fruits|frozen fruit,frozen berries|diepvriesfruit,diepvries bessen
frozen_meals|supermarket:5,frozen:5,convenience:3|en:frozen-ready-meals|frozen meal,ready meal|diepvriesmaaltijd,kant en klaar maaltijd
sparkling_water|supermarket:5,convenience:4,drinks:3|en:sparkling-waters|sparkling water,soda water,tonic|bruisend water,spa rood,tonic,soda
lemonade|supermarket:5,convenience:4|en:lemonades|lemonade,iced tea,cola|limonade,ice tea,cola
cordial|supermarket:5,convenience:3|en:syrups|cordial,squash,syrup|limonadesiroop,siroop
red_wine|drinks:5,supermarket:4,deli:3|en:red-wines|red wine,white wine,rose wine|rode wijn,witte wijn,rosé
prosecco|drinks:5,supermarket:4|en:proseccos|prosecco,champagne,cava|prosecco,champagne,cava
whisky|drinks:5,supermarket:3|en:whiskies|whisky,gin,vodka,rum,liqueur|whisky,gin,wodka,rum,likeur
craft_beer|drinks:5,supermarket:3,convenience:2|en:beers|craft beer,ipa,stout|speciaalbier,ipa,stout
alcohol_free|supermarket:5,drinks:3,convenience:3|en:alcohol-free-beers|alcohol free beer,non alcoholic beer|alcoholvrij bier,0.0 bier
oat_drink_barista|supermarket:5,coffee:3,health_food:4|en:oat-based-drinks|barista oat milk|barista havermelk
espresso|coffee:5,supermarket:5,deli:3|en:coffees|espresso,coffee pods,coffee capsules|espresso,koffiecups,koffiecapsules
herbal_tea|supermarket:5,coffee:5,health_food:4|en:herbal-teas|herbal tea,mint tea,green tea,chai|kruidenthee,muntthee,groene thee,chai
sugar_substitute|supermarket:5,health_food:3|en:sweeteners|sweetener,stevia,maple syrup,agave|zoetstof,stevia,ahornsiroop,agavesiroop
protein|health_food:5,supermarket:3|en:protein-powders|protein powder,protein bar,protein shake|proteïnepoeder,eiwitreep,proteïneshake
gluten_free|health_food:5,supermarket:4|en:gluten-free-foods|gluten free bread,gluten free pasta|glutenvrij brood,glutenvrije pasta
chocolate_bar|supermarket:5,convenience:5,sweets:5,newsagent:3|en:chocolate-bars|chocolate bar,chocolate bars|chocoladereep,chocoladerepen
chewing_gum|convenience:5,supermarket:5,newsagent:5|en:chewing-gums|chewing gum,mints|kauwgom,pepermunt,mintjes
liquorice|supermarket:5,sweets:5,convenience:4|en:liquorices|liquorice,licorice,drop|drop,dropjes,zoute drop
praline|sweets:5,supermarket:3,deli:3|en:pralines|pralines,truffles,box of chocolates|bonbons,truffels,doos bonbons
birthday_candles|supermarket:5,variety:5,newsagent:2|en:party-candles|birthday candles,party hats,balloons|taartkaarsjes,feesthoedjes,ballonnen
wrapping|variety:5,newsagent:4,supermarket:3|en:gift-wraps|wrapping paper,ribbon,gift bag|inpakpapier,lint,cadeauzakje
greeting_card|newsagent:5,variety:4,supermarket:3|en:greeting-cards|greeting card,birthday card|wenskaart,verjaardagskaart
toilet_cleaner|supermarket:5,chemist:5,variety:4|en:toilet-cleaners|toilet cleaner,bleach,limescale remover|wc reiniger,bleek,kalkverwijderaar
sponges|supermarket:5,chemist:4,variety:5|en:sponges|sponge,scourer,dish cloth|spons,schuurspons,vaatdoek
dishwasher_tabs|supermarket:5,chemist:5|en:dishwasher-tablets|dishwasher tablets,dishwasher salt|vaatwastabletten,vaatwaszout
fabric_softener|supermarket:5,chemist:5|en:fabric-softeners|fabric softener,stain remover|wasverzachter,vlekverwijderaar
all_purpose_cleaner|supermarket:5,chemist:5,variety:4|en:all-purpose-cleaners|all purpose cleaner,glass cleaner,floor cleaner|allesreiniger,glasreiniger,vloerreiniger
sandwich_bags|supermarket:5,variety:4|en:food-storage-bags|sandwich bags,freezer bags,cling film,baking paper|boterhamzakjes,diepvrieszakjes,huishoudfolie,bakpapier
napkins|supermarket:5,variety:5,convenience:2|en:napkins|napkins,paper napkins,tissues|servetten,papieren servetten,zakdoekjes
face_wash|chemist:5,supermarket:4,pharmacy:3|en:facial-cleansers|face wash,moisturiser,face cream|gezichtsreiniger,dagcrème,gezichtscrème
makeup_remover|chemist:5,supermarket:4|en:make-up-removers|make-up remover,cotton buds|make-up remover,wattenstaafjes
conditioner|chemist:5,supermarket:5|en:conditioners|conditioner,hair gel,hair spray|conditioner,haargel,haarspray
body_lotion|chemist:5,supermarket:5,pharmacy:3|en:body-lotions|body lotion,lip balm|bodylotion,lippenbalsem
shower_gel|chemist:5,supermarket:5|en:shower-gels|shower gel,soap,hand soap|douchegel,zeep,handzeep
toothbrush|chemist:5,supermarket:5,pharmacy:4|en:toothbrushes|toothbrush,floss,mouthwash|tandenborstel,floss,mondwater
sanitary_pads|chemist:5,supermarket:5|en:sanitary-towels|sanitary pads,panty liners|maandverband,inlegkruisjes
nappy_wipes|chemist:5,supermarket:5|en:baby-wipes|baby wipes,wet wipes|babydoekjes,vochtige doekjes
baby_formula|chemist:5,supermarket:5,pharmacy:3|en:infant-milks|baby formula,formula milk|flesvoeding,babymelk
allergy_pills|pharmacy:5,chemist:4|en:antihistamines|allergy tablets,hay fever tablets|hooikoortstabletten,antihistamine
cough_syrup|pharmacy:5,chemist:4,supermarket:2|en:cough-syrups|cough syrup,throat lozenges|hoestdrank,keelpastilles
paracetamol|pharmacy:5,chemist:5,supermarket:3,convenience:1|en:paracetamols|paracetamol,ibuprofen,aspirin|paracetamol,ibuprofen,aspirine
first_aid|pharmacy:5,chemist:5,supermarket:3|en:first-aid-kits|bandage,antiseptic,first aid|verband,ontsmettingsmiddel,eerste hulp
contact_lenses|pharmacy:3,chemist:3,variety:1|en:contact-lens-solutions|contact lens solution|lenzenvloeistof
sun_cream|chemist:5,supermarket:5,pharmacy:4|en:sun-creams|sun cream,after sun|zonnebrandcrème,aftersun
insect_repellent|chemist:5,pharmacy:4,hardware:3,supermarket:3|en:insect-repellents|insect repellent,mosquito spray|insectenspray,muggenspray
cat_litter|pet:5,supermarket:5|en:cat-litters|cat litter|kattenbakvulling,kattenbak
bird_seed|pet:5,hardware:2,supermarket:2|en:bird-foods|bird seed,fat balls|vogelzaad,vetbollen
dog_treats|pet:5,supermarket:4|en:dog-treats|dog treats,chew|hondensnacks,kauwstaaf
fish_food|pet:5|en:fish-foods|fish food|visvoer
potting_soil|hardware:5,florist:5,variety:2|en:potting-soils|potting soil,compost,plant food|potgrond,compost,plantenvoeding
house_plant|florist:5,hardware:3,supermarket:3|en:house-plants|house plant,pot plant,cactus|kamerplant,potplant,cactus
bouquet|florist:5,supermarket:4,convenience:1|en:bouquets|bouquet,roses,tulips|boeket,rozen,tulpen
newspapers|newsagent:5,convenience:4,supermarket:3|en:newspapers|newspaper,magazine,magazines|krant,tijdschrift,tijdschriften
lottery|newsagent:5,convenience:4|en:lottery-tickets|lottery ticket,scratch card|loten,krasloten,lot
tobacco|newsagent:5,convenience:3,supermarket:2|en:tobaccos|tobacco,rolling paper,lighter|tabak,vloei,aansteker
phone_credit|newsagent:5,convenience:4,supermarket:4|en:phone-cards|phone credit,sim card|beltegoed,simkaart
bin_liners|supermarket:5,variety:5,hardware:3|en:waste-bags|bin liners,rubbish bags,compost bags|vuilniszakken,afvalzakken,gft zakken
matches|supermarket:5,convenience:4,variety:4|en:matches|matches,tea lights|lucifers,theelichtjes
extension_lead|hardware:5,variety:4|en:extension-leads|extension lead,adapter|verlengsnoer,stekkerdoos
tape|variety:5,newsagent:3,hardware:4|en:adhesive-tapes|sticky tape,masking tape,glue|plakband,afplaktape,lijm
""".trimIndent()

    /** Every item of the table. */
    val all: List<ShopItem> by lazy { load() }

    private val byPhrase: Map<String, ShopItem> by lazy {
        val m = HashMap<String, ShopItem>()
        for (item in all) for ((_, ns) in item.names) for (n in ns) m.putIfAbsent(n, item)
        m
    }

    fun byId(id: String): ShopItem? = all.firstOrNull { it.id == id }

    private fun load(): List<ShopItem> = TABLE.lines().filter { it.isNotBlank() }.map { line ->
        val c = line.split('|')
        require(c.size == 5) { "bad item line: $line" }
        val sellers = c[1].split(',').map { tok ->
            val k = ShopKind.of(tok.substringBefore(':')) ?: error("unknown shop kind in $tok")
            Seller(k, tok.substringAfter(':').toInt().also { require(it in 1..5) })
        }.sortedByDescending { it.likelihood }
        val names = linkedMapOf(
            "en" to c[3].split(',').map { Text.fold(it) }.filter { it.isNotEmpty() },
            "nl" to c[4].split(',').map { Text.fold(it) }.filter { it.isNotEmpty() },
        )
        ShopItem(c[0], sellers, c[2].ifBlank { null }, names)
    }

    /**
     * What [text] on a list is, or null when the table does not know it: an exact phrase first, then the phrase with
     * a plural "s"/"en" taken off or put on, then the longest known phrase inside the text ("2 litres oat milk").
     */
    private val QUANTITY = Regex("^[0-9.,]+\\s*(x|kg|g|gram|l|liter|litre|litres|ml|stuks|pak|pakken|bak|zak)?\\s+")

    fun classify(text: String): ShopItem? {
        val f = Text.fold(text).replace(QUANTITY, "").trim()
        if (f.isEmpty()) return null
        byPhrase[f]?.let { return it }
        for (v in variants(f)) byPhrase[v]?.let { return it }
        // The longest known phrase that is a whole-word part of the text.
        var best: ShopItem? = null
        var bestLen = 0
        val padded = " $f "
        for ((phrase, item) in byPhrase) {
            if (phrase.length > bestLen && phrase.length >= 3 && padded.contains(" $phrase ")) {
                best = item; bestLen = phrase.length
            }
        }
        return best
    }

    private fun variants(f: String): List<String> = buildList {
        if (f.endsWith("s")) add(f.dropLast(1))
        if (f.endsWith("es")) add(f.dropLast(2))
        if (f.endsWith("en")) add(f.dropLast(2))
        if (f.endsWith("'s")) add(f.dropLast(2))
        add(f + "s")
        add(f + "en")
    }

    /**
     * Where [text] is bought, best first: from the table, else from the goods catalogue of `:search` (things that are
     * not groceries). Empty when neither knows it.
     */
    fun sellersOf(text: String): List<Seller> {
        classify(text)?.let { return it.sellers }
        val f = Text.fold(text)
        val goods = GoodsCatalogue.all.firstOrNull { g -> g.phrases.values.any { ps -> f in ps } } ?: return emptyList()
        return goods.sellers.mapNotNull { s -> kindFor(s.filter)?.let { Seller(it, s.likelihood) } }.distinctBy { it.kind }
    }

    /** The shop kind whose rule is the catalogue seller's (by the shop values they share). */
    private fun kindFor(filter: app.atlas.places.TagFilter): ShopKind? {
        val values = words(filter.toText()) - setOf("shop", "amenity", "craft", "healthcare", "and", "or")
        return ShopKind.entries.firstOrNull { k -> words(k.tags).any { it in values } }
    }

    private fun words(s: String): Set<String> = Regex("[a-z_]+").findAll(s).map { it.value }.toSet()
}
