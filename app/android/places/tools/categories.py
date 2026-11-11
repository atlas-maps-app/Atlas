"""Atlas's categories as data: the plan's rules (fetch/categories_ams.py, first match wins, order matters) merged with
the boards' categories (the Map home's row and app/ideas.md "Categories the boards cover").

Each rule is written in the TagFilter text syntax the Kotlin side parses. `legacy` is the plan's id (c00..c48).
Group names are written by hand in every language. Category names: en, nl, de, fr by hand; the other languages come from
the id-tagging-schema name of `name_from` when given (tidied: no parenthetical notes, the first of "A / B").
Ids are unique across groups and categories.

After an edit, `python3 build_places_data.py --categories` writes categories.json anew, with no download: the names of
`name_from` come from the bundled i18n then. The kinds move with their categories: KindsTest writes kinds.tsv regrouped.
"""

LANGS = ['en', 'nl', 'de', 'fr', 'es', 'it', 'pt', 'pl', 'sv', 'da', 'nb', 'fi', 'cs', 'ja', 'zh-CN', 'ru', 'ar', 'tr']

# The Map home's row, in board order: (id, ui symbol, colour, names in LANGS order). Written by hand in every language;
# the colours are the row's own (app HomeControls HOME_CATEGORY_LOOK), so a category wears one colour everywhere.
GROUPS = [
    ('eat', 'ui-utensils', '#B8461A', ('Eat & drink', 'Eten & drinken', 'Essen & Trinken', 'Manger & boire', 'Comer y beber',
     'Mangiare e bere', 'Comer e beber', 'Jedzenie i picie', 'Äta & dricka', 'Spis & drik', 'Mat & drikke', 'Syö & juo',
     'Jídlo a pití', '飲食', '餐饮', 'Еда и напитки', 'طعام وشراب', 'Yeme & içme')),
    ('groceries', 'ui-basket', '#1E7A46', ('Groceries', 'Boodschappen', 'Lebensmittel', 'Courses', 'Alimentación', 'Alimentari',
     'Mercearia', 'Zakupy spożywcze', 'Livsmedel', 'Dagligvarer', 'Dagligvarer', 'Ruokakaupat', 'Potraviny', '食料品', '食品杂货',
     'Продукты', 'بقالة', 'Market')),
    ('shops', 'ui-shopping-bag', '#7A2233', ('Shops', 'Winkels', 'Geschäfte', 'Boutiques', 'Tiendas', 'Negozi', 'Lojas', 'Sklepy',
     'Butiker', 'Butikker', 'Butikker', 'Kaupat', 'Obchody', 'ショップ', '商店', 'Магазины', 'متاجر', 'Mağazalar')),
    ('stay', 'ui-bed', '#5B3FD6', ('Stay', 'Overnachten', 'Übernachten', 'Hébergement', 'Alojamiento', 'Dormire', 'Alojamento',
     'Noclegi', 'Boende', 'Overnatning', 'Overnatting', 'Majoitus', 'Ubytování', '宿泊', '住宿', 'Жильё', 'إقامة', 'Konaklama')),
    # filling up, with fuel or with power: the gas stations and the chargers are one family (the owner, 6 November: "We
    # only have a charge category, where is the fuel category? Gas and charging belongs both into fuel"). Its id stays
    # "charge", by which the phone's kept choices know it; its symbol is fuel's pump. A language whose word for fuel
    # leaves the charger out names both ("Tanken & laden").
    ('charge', 'ui-fuel', '#059255', ('Fuel', 'Tanken & laden', 'Tanken & Laden', 'Carburant & recharge', 'Combustible y recarga',
     'Carburante e ricarica', 'Combustível e carregamento', 'Paliwo i ładowanie', 'Tanka & ladda', 'Tankning & opladning',
     'Drivstoff & lading', 'Polttoaine & lataus', 'Palivo a nabíjení', '給油・充電', '加油与充电', 'Топливо и зарядка', 'وقود وشحن',
     'Yakıt & şarj')),
    ('nature', 'ui-mountain', '#2F6239', ('Nature', 'Natuur', 'Natur', 'Nature', 'Naturaleza', 'Natura', 'Natureza', 'Przyroda',
     'Natur', 'Natur', 'Natur', 'Luonto', 'Příroda', '自然', '自然', 'Природа', 'طبيعة', 'Doğa')),
    # days out (a cinema, a zoo, a theme park, a playground); no longer "Uitgaan", which is the bars' (the owner, 5 November).
    # Its big wheel is the boards' own, the theme park's and the card's: a ticket did not say what is behind it (6 November)
    ('fun', 'maki-amusement-park', '#8047E8', ('Fun', 'Uitjes', 'Freizeit', 'Loisirs', 'Ocio', 'Svago', 'Lazer', 'Rozrywka', 'Nöje',
     'Fritid', 'Moro', 'Viihde', 'Zábava', 'レジャー', '娱乐', 'Досуг', 'ترفيه', 'Eğlence')),
    ('water', 'ui-sailboat', '#1F5F99', ('Water', 'Water', 'Wasser', 'Eau', 'Agua', 'Acqua', 'Água', 'Woda', 'Vatten', 'Vand',
     'Vann', 'Vesi', 'Voda', '水辺', '水上', 'Вода', 'ماء', 'Su')),
]

FAMILY = {  # colour families when a category has no group (render/poi_style.py CATS)
    'culture': '#B8326A', 'health': '#D44546', 'service': '#5A6478', 'sport': '#1D8FA0', 'transport': '#2E5BFF',
    'osm': '#505050',
}

# The search's shelves for the categories with no group on the Map home's row: (family, symbol, names in LANGS order),
# after the row's groups in the category grid. Written by hand in every language; the colour is the family's.
SHELVES = [
    ('culture', 'ui-landmark', ('Culture & sights', 'Cultuur & bezienswaardigheden', 'Kultur & Sehenswertes', 'Culture & visites',
     'Cultura y monumentos', 'Cultura e monumenti', 'Cultura e monumentos', 'Kultura i zabytki', 'Kultur & sevärdheter',
     'Kultur & seværdigheder', 'Kultur & severdigheter', 'Kulttuuri & nähtävyydet', 'Kultura a památky', '文化・名所', '文化与景点',
     'Культура и достопримечательности', 'ثقافة ومعالم', 'Kültür & gezilecek yerler')),
    # its ball is the boards' own (the pitch's)
    ('sport', 'place.pitch', ('Sport', 'Sport', 'Sport', 'Sport', 'Deporte', 'Sport', 'Desporto', 'Sport', 'Sport', 'Sport',
     'Sport', 'Urheilu', 'Sport', 'スポーツ', '运动', 'Спорт', 'رياضة', 'Spor')),
    ('health', 'ui-stethoscope', ('Health', 'Gezondheid', 'Gesundheit', 'Santé', 'Salud', 'Salute', 'Saúde', 'Zdrowie', 'Hälsa',
     'Sundhed', 'Helse', 'Terveys', 'Zdraví', '健康', '健康', 'Здоровье', 'صحة', 'Sağlık')),
    ('service', 'ui-wrench', ('Services', 'Diensten', 'Dienste', 'Services', 'Servicios', 'Servizi', 'Serviços', 'Usługi',
     'Tjänster', 'Tjenester', 'Tjenester', 'Palvelut', 'Služby', 'サービス', '服务', 'Услуги', 'خدمات', 'Hizmetler')),
    ('transport', 'ui-bus', ('Getting around', 'Onderweg', 'Unterwegs', 'Se déplacer', 'Moverse', 'Spostarsi', 'Deslocar-se',
     'Transport', 'Resa', 'Transport', 'Transport', 'Liikkuminen', 'Doprava', '交通', '交通', 'Транспорт', 'تنقل', 'Ulaşım')),
    # every other kind OpenStreetMap maps (a lake, a tree, a tower), by key: no category of its own
    ('osm', 'ui-map-pin', ('Everything on the map', 'Alles op de kaart', 'Alles auf der Karte', 'Tout sur la carte', 'Todo en el mapa',
     'Tutto sulla mappa', 'Tudo no mapa', 'Wszystko na mapie', 'Allt på kartan', 'Alt på kortet', 'Alt på kartet', 'Kaikki kartalla',
     'Vše na mapě', '地図上のすべて', '地图上的一切', 'Всё на карте', 'كل ما على الخريطة', 'Haritadaki her şey')),
]

NOT_A_PLACE = 'amenity=parking_space or disused:shop or shop=vacant or emergency=siren'

# The kinds people come to a category for most: they lead its row of kinds and are ringed on its map (the owner,
# 6 November, of the groceries: "For groceries thats ah and any other generic grocery store", "it makes sense that these
# two sit at the front because thats what people primarily search for when looking for groceries", and of the ring
# "Yes, map only"). Presets, by id; a narrower preset of one is one too (an organic supermarket).
CORE = {
    'food_shops': ('shop/supermarket', 'shop/convenience'),
}

# Kinds that are another shelf's too: one home, more doors (the owner, 6 November: "Doesnt sports somehow also belong in
# fun?", "several other categories partly also in the other", then "Yes, build it"). The place itself is that too: a
# climbing hall or an ice rink is a day out, a campsite somewhere to sleep. Each keeps its own family, colour and pin;
# the shelf lists it after its own kinds, holds its places on its map and counts them. A shelf named instead ends the
# row of kinds as the way into it, its places not this shelf's: Sport's pitches and gyms are no day out, its climbing
# walls are. The pools are Water's, and Fun's and Sport's too (the owner, 6 November: "And I think swim belongs in fun
# and sport"): a water park is a day out, a pool lanes to swim. A market is the groceries' and the shops' (the owner,
# 6 November: "What about markets?"): its stalls sell clothes and flowers as well as cheese. Nodes of the search's
# category tree: "cat:" a category, "preset:" a preset under one, "shelf:" a group or a family.
ALSO = {
    'shops': ('preset:amenity/marketplace',),
    # the other fuels: gas bottles, firewood and heating oil from their shops, a boat's from the marina's pump ("what other
    # kinds of fuel there are", the owner, 6 November); a station's own fuels (diesel, LPG, CNG, hydrogen, AdBlue) are its
    # filters
    'charge': ('preset:shop/gas', 'preset:shop/fuel', 'preset:waterway/fuel'),
    'stay': ('cat:camping',),
    'fun': ('cat:climbing', 'preset:leisure/ice_rink', 'cat:swim', 'shelf:sport'),
    'sport': ('cat:swim',),
}

# The kinds that end a shelf's or a category's row (the owner, 6 November: "Sort by popularity or what belongs in what
# category most? Like amusement parks at the top?", then "Not sure if it really makes sense that theme parks and zoos
# always sit at the top. I think they shouldnt"): a row goes by how many of each kind are near, a category's CORE kinds
# first; the kinds named here come after all the others near, in this order: the specialists whose goods the others
# sell too, the frozen food shops after every other grocer ("Supermarkets have frozen food"), and what is no errand to
# go on (a bench, an office). The kinds with none near follow them all, in the same order. By node id: a shelf's
# categories, a category's presets.
LAST = {
    'cat:food_shops': ('preset:shop/frozen_food', 'preset:shop/water'),
    'cat:shopping': ('preset:shop/wholesale', 'preset:shop/trade', 'preset:shop/storage_rental', 'preset:shop/funeral_directors'),
    'cat:hotels': ('preset:tourism/alpine_hut', 'preset:tourism/wilderness_hut'),
    'shelf:culture': ('cat:heritage',),
    'shelf:service': ('cat:street_furniture', 'cat:offices', 'cat:other'),
    'shelf:transport': ('cat:low_emission_zone',),
}

# Kinds that belong to each other though each stands where it is (the owner, 6 November: "Miniature golf is in fun.
# Where is golf? Link kinds that belong to each other."): a kind's view names the others of its groups, and a press opens
# one. Every kind of a group links to every other; a kind in two groups links to both. A category's links are its
# kinds' too (a water park's are the pools'). Kinds only, no shelves, by node id as in ALSO.
LINKS = [
    # golf: a round of minigolf, on a course, of disc golf
    ('preset:leisure/miniature_golf', 'preset:leisure/golf_course', 'preset:leisure/disc_golf_course'),
    # a swim: a pool, a beach or a lake to swim in, a sports centre's lanes
    ('cat:swim', 'cat:beach', 'preset:leisure/sports_centre/swimming'),
    # a show: a film or a play
    ('cat:cinema', 'cat:stage'),
    # a book: to borrow, to take from a bookcase, to buy
    ('cat:library', 'cat:book_swap', 'preset:shop/books'),
    # what the pharmacy sells over the counter, the chemist sells too
    ('preset:amenity/pharmacy', 'preset:shop/chemist'),
    # filling up, with fuel or power
    ('cat:fuel', 'cat:charging'),
    # a bike: bought, hired, mended, parked
    ('preset:shop/bicycle', 'preset:amenity/bicycle_rental', 'preset:amenity/bicycle_repair_station', 'cat:bike_parking'),
    # a pet: walked, fed, cared for, groomed, minded
    ('preset:leisure/dog_park', 'preset:shop/pet', 'preset:amenity/veterinary', 'preset:shop/pet_grooming', 'preset:amenity/animal_boarding'),
    # children out of doors: the playground, the park round it, the petting zoo
    ('cat:playground', 'preset:leisure/park'),
    ('cat:playground', 'preset:tourism/zoo/petting'),
    # cash, from a machine, a bank, a change office
    ('preset:amenity/atm', 'preset:amenity/bank', 'preset:amenity/bureau_de_change'),
    # post, sent at the counter, in a box, from a locker
    ('preset:amenity/post_office', 'preset:amenity/post_box', 'preset:amenity/parcel_locker'),
    # a car for a while
    ('preset:amenity/car_rental', 'preset:amenity/car_sharing'),
]

# (id, legacy, group or family, symbol, rule, names en/nl/de/fr, name_from)
# A category wears its main kind's symbol (kinds.tsv): ours where the kind has one, the boards' or the interface set's
# (the owner, 10 November: "The simple style of our icons looked good"), else its filled one; where a board draws the
# kind, the boards' (step 1b, the parent's verdict, 10 November, the evening: the picnic table, the bike stands' hoop,
# the book box), and the community centres' people under a roof, which their kinds wear again. The families' symbols
# above are buttons, outline.
CATEGORIES = [
    ('restaurants', 'eat', 'eat', 'maki-restaurant', 'amenity~^(restaurant|cafe|fast_food|ice_cream|food_court)$',
     # beside Drinks & nightlife under Eat & drink: what this list holds (the owner, 10 November, "And yes?")
     ('Restaurants & cafés', 'Restaurants & cafés', 'Restaurants & Cafés', 'Restaurants & cafés'), 'amenity/restaurant'),
    # the bars one goes out to; a bottle to take home is bought with the groceries, as a game is with the shops (the
    # owner, 6 November, of kinds "partly also in the other", then "Yes, build it" to filing these two where they are)
    ('drinks', 'c14', 'eat', 'maki-bar', 'amenity~^(bar|pub|biergarten|nightclub)$',
     ('Drinks & nightlife', 'Drinken & uitgaan', 'Bars & Nachtleben', 'Bars & vie nocturne'), 'amenity/bar'),
    ('museums', 'c26', 'culture', 'maki-museum', 'tourism~^(museum|gallery)$ or shop=art or amenity=planetarium',
     ('Museums & galleries', 'Musea & galerieën', 'Museen & Galerien', 'Musées & galeries'), 'tourism/museum'),
    ('hotels', 'c13', 'stay', 'maki-lodging',
     'tourism~^(hotel|hostel|guest_house|apartment|motel|chalet|alpine_hut|wilderness_hut)$ or leisure=resort',
     ('Hotels & where to sleep', 'Hotels & slapen', 'Hotels & Unterkünfte', 'Hôtels & hébergements'), 'tourism/hotel'),
    ('library', 'c00', 'culture', 'place.library', 'amenity=library',
     ('Libraries', 'Bibliotheken', 'Bibliotheken', 'Bibliothèques'), 'amenity/library'),
    ('education', 'c01', 'service', 'maki-school',
     'amenity~^(school|college|university|kindergarten|language_school|music_school|dancing_school)$',
     ('Schools', 'Scholen', 'Schulen', 'Écoles'), 'amenity/school'),
    ('skate', 'c03', 'sport', 'maki-skateboard', '(sport=skateboard and leisure~^(pitch|skatepark)$) or leisure~^(ice_rink|skatepark)$',
     ('Skating', 'Skaten & schaatsen', 'Skaten & Eislaufen', 'Glisse'), 'leisure/ice_rink'),
    ('climbing', 'c47', 'sport', 'temaki-climbing', '(sport=climbing and leisure~^(sports_centre|pitch)$) or leisure=high_ropes_course',
     ('Climbing', 'Klimmen', 'Klettern', 'Escalade'), 'leisure/sports_centre/climbing'),
    ('sports', 'c02', 'sport', 'maki-pitch',
     '(leisure~^(pitch|sports_centre|sports_hall|track|golf_course|disc_golf_course|horse_riding)$ and sport!=fitness) or club=sport',
     ('Sports grounds', 'Sportvelden', 'Sportplätze', 'Terrains de sport'), 'leisure/pitch'),
    ('stadium', 'c48', 'sport', 'maki-stadium', 'leisure=stadium',
     ('Stadiums', 'Stadions', 'Stadien', 'Stades'), 'leisure/stadium'),
    ('gym', 'c04', 'sport', 'maki-fitness-centre', 'leisure=fitness_centre or (sport=fitness and !shop)',
     ('Gyms', 'Sportscholen', 'Fitnessstudios', 'Salles de sport'), 'leisure/fitness_centre'),
    ('swim', 'c05', 'water', 'maki-swimming', 'leisure~^(swimming_pool|water_park)$ and access!~^(private|customers)$',
     ('Pools', 'Zwembaden', 'Schwimmbäder', 'Piscines'), 'leisure/swimming_pool'),
    ('playground', 'c06', 'fun', 'maki-playground', 'leisure~^(playground|indoor_play|trampoline_park)$',
     ('Playgrounds', 'Speeltuinen', 'Spielplätze', 'Aires de jeux'), 'leisure/playground'),
    ('games', 'c07', 'fun', 'maki-gaming',
     'leisure~^(amusement_arcade|escape_game|bowling_alley|miniature_golf)$',
     ('Games & arcades', 'Spellen & arcades', 'Spiele & Spielhallen', 'Jeux & arcades'), 'leisure/amusement_arcade'),
    ('cinema', 'c08', 'fun', 'maki-cinema', 'amenity=cinema',
     ('Cinemas', 'Bioscopen', 'Kinos', 'Cinémas'), 'amenity/cinema'),
    ('zoo', 'c09', 'fun', 'maki-zoo', 'tourism~^(zoo|aquarium)$',
     ('Zoos', 'Dierentuinen', 'Zoos', 'Zoos'), 'tourism/zoo'),
    ('theme_park', 'c10', 'fun', 'maki-amusement-park', 'tourism=theme_park',
     ('Theme parks', 'Pretparken', 'Freizeitparks', "Parcs d'attractions"), 'tourism/theme_park'),
    ('camping', 'c11', 'nature', 'maki-campsite', 'tourism~^(camp_site|caravan_site)$',
     ('Camping', 'Kamperen', 'Camping', 'Camping'), 'tourism/camp_site'),
    ('nature_reserve', 'c12', 'nature', 'maki-park',
     'boundary=national_park or leisure=nature_reserve or (boundary=protected_area and protect_class~^(1|1a|1b|2|3|4|5|6)$)',
     ('Nature reserves', 'Natuurgebieden', 'Naturschutzgebiete', 'Réserves naturelles'), 'leisure/nature_reserve'),
    # no kind of its own: the boards' landmark, as before the filled set (the Blue Shield, the sign of protected cultural
    # property, is drawn: atlas-blue_shield)
    ('heritage', 'heritage', 'culture', 'maki-landmark', 'boundary=protected_area',
     ('Protected heritage', 'Beschermd erfgoed', 'Geschütztes Erbe', 'Patrimoine protégé'), None),
    ('health', 'c15', 'health', 'maki-hospital', 'amenity~^(hospital|clinic|doctors|dentist|pharmacy)$ or healthcare',
     ('Health', 'Gezondheid', 'Gesundheit', 'Santé'), 'amenity/hospital'),
    ('money', 'c16', 'service', 'maki-bank', 'amenity~^(bank|atm|bureau_de_change|money_transfer)$',
     ('Banks & ATMs', 'Banken & geldautomaten', 'Banken & Geldautomaten', 'Banques & distributeurs'), 'amenity/bank'),
    ('post', 'c17', 'service', 'maki-post', 'amenity~^(post_office|parcel_locker|post_box)$ or post_office',
     ('Post & parcels', 'Post & pakketten', 'Post & Pakete', 'Poste & colis'), 'amenity/post_office'),
    ('laundry', 'c18', 'service', 'maki-laundry', 'shop~^(laundry|dry_cleaning)$',
     ('Laundry', 'Wasserette', 'Wäscherei', 'Laverie'), 'shop/laundry'),
    ('fuel', 'c20', 'charge', 'maki-fuel', 'amenity=fuel',
     ('Gas stations', 'Tankstations', 'Tankstellen', 'Stations-service'), 'amenity/fuel'),
    ('parking', 'c21', 'transport', 'maki-parking', 'amenity~^(parking|motorcycle_parking)$',
     ('Parking', 'Parkeren', 'Parken', 'Stationnement'), 'amenity/parking'),
    ('toilets', 'c22', 'service', 'maki-toilet', 'amenity=toilets',
     ('Toilets', "Toiletten", 'Toiletten', 'Toilettes'), 'amenity/toilets'),
    ('recycling', 'c23', 'service', 'maki-recycling', 'amenity~^(recycling|waste_disposal)$',
     ('Recycling', 'Recycling', 'Recycling', 'Recyclage'), 'amenity/recycling_container'),
    # a ferry's terminal is a station: one tagged as one was here already, one tagged only as a terminal was the boats'
    ('transit', 'c24', 'transport', 'maki-bus',
     'amenity~^(bus_station|ferry_terminal)$ or railway~^(tram_stop|station|halt)$ or highway=bus_stop or public_transport~^(stop_position|station)$',
     ('Tram & bus stops', 'Tram- & bushaltes', 'Tram- & Bushaltestellen', 'Arrêts de tram & bus'), 'highway/bus_stop'),
    ('airport', 'c25', 'transport', 'maki-airport', 'aeroway~^(aerodrome|terminal)$',
     ('Airports', 'Luchthavens', 'Flughäfen', 'Aéroports'), 'aeroway/aerodrome'),
    ('worship', 'c27', 'culture', 'maki-place-of-worship', 'amenity=place_of_worship',
     ('Places of worship', 'Gebedshuizen', 'Gotteshäuser', 'Lieux de culte'), 'amenity/place_of_worship'),
    ('viewpoint', 'c28', 'nature', 'temaki-viewpoint', 'tourism=viewpoint or leisure=bird_hide',
     ('Viewpoints', 'Uitzichtpunten', 'Aussichtspunkte', 'Points de vue'), 'tourism/viewpoint'),
    ('support', 'c30', 'service', 'temaki-social_facility', 'amenity~^(social_facility|food_bank|soup_kitchen)$ or social_facility',
     ('Shelter & support', 'Opvang & hulp', 'Unterkunft & Hilfe', 'Abri & soutien'), 'amenity/social_facility'),
    ('showers', 'c31', 'service', 'temaki-shower', 'amenity=shower',
     ('Showers', 'Douches', 'Duschen', 'Douches'), 'amenity/shower'),
    ('defibrillator', 'c32', 'health', 'maki-defibrillator', 'emergency=defibrillator',
     ('Defibrillators', 'AED', 'Defibrillatoren', 'Défibrillateurs'), 'emergency/defibrillator'),
    ('car_care', 'c33', 'transport', 'maki-car-repair', 'shop~^(car_repair|car|tyres)$ or amenity=car_wash',
     ('Car repair & wash', 'Garage & wasstraat', 'Werkstatt & Waschanlage', 'Garage & lavage'), 'shop/car_repair'),
    ('low_emission_zone', 'c34', 'transport', 'maki-car', 'boundary=low_emission_zone',
     ('Low-emission zones', 'Milieuzones', 'Umweltzonen', 'Zones à faibles émissions'), None),
    ('vending', 'c35', 'service', 'temaki-vending_machine', 'amenity=vending_machine',
     ('Vending machines', 'Automaten', 'Automaten', 'Distributeurs automatiques'), 'amenity/vending_machine'),
    ('lost_property', 'c36', 'service', 'temaki-suitcase_key', 'amenity=lost_property_office',
     ('Lost property', 'Gevonden voorwerpen', 'Fundbüros', 'Objets trouvés'), 'amenity/lost_property_office'),
    ('coworking', 'c37', 'service', 'temaki-briefcase', 'amenity=coworking_space or office=coworking',
     ('Coworking', 'Flexwerkplekken', 'Coworking', 'Coworking'), 'amenity/coworking_space'),
    ('drinking_water', 'c38', 'service', 'maki-drinking-water', 'amenity=drinking_water or (man_made=water_tap and drinking_water=yes)',
     ('Drinking water', 'Drinkwater', 'Trinkwasser', 'Eau potable'), 'amenity/drinking_water'),
    ('street_furniture', 'c38', 'service', 'temaki-bench', 'amenity~^(bench|waste_basket|clock|telephone|lounger)$',
     ('On the street', 'Op straat', 'Auf der Straße', 'Dans la rue'), 'amenity/bench'),
    ('picnic', None, 'nature', 'place.picnic', 'leisure~^(picnic_table|firepit)$ or tourism=picnic_site or amenity=bbq',
     ('Picnic spots', 'Picknickplekken', 'Picknickplätze', 'Aires de pique-nique'), 'tourism/picnic_site'),
    ('bike_parking', None, 'transport', 'park.bike-stands', 'amenity=bicycle_parking',
     ('Bike parking', 'Fietsenstallingen', 'Fahrradparkplätze', 'Parkings à vélos'), 'amenity/bicycle_parking'),
    ('charging', 'c39', 'charge', 'maki-charging-station', 'amenity=charging_station',
     ('Charging', 'Laadpalen', 'Ladestationen', 'Bornes de recharge'), 'amenity/charging_station'),
    # an old building (historic=yes or =building) is a sight by its name: nameless, it is one more house in the street
    ('sights', 'c40', 'culture', 'maki-attraction',
     'tourism~^(attraction|artwork|yes)$ or historic~^(memorial|monument|castle|ruins)$ or (historic~^(yes|building)$ and name)',
     ('Sights & memorials', 'Bezienswaardigheden', 'Sehenswürdigkeiten', 'Curiosités & mémoriaux'), 'tourism/attraction'),
    ('stage', 'c41', 'culture', 'maki-theatre',
     'amenity~^(theatre|arts_centre|events_venue|concert_hall|art_gallery)$ or leisure=bandstand',
     ('Stage & arts', 'Podia & kunst', 'Bühnen & Kunst', 'Scènes & arts'), 'amenity/theatre'),
    ('community', None, 'service', 'atlas-people_under_roof', 'amenity=community_centre',
     ('Community centres', 'Buurthuizen', 'Gemeindezentren', 'Maisons de quartier'), 'amenity/community_centre'),
    ('book_swap', 'c42', 'culture', 'reuse.book-box', 'amenity=public_bookcase',
     ('Book exchanges', 'Minibiebs', 'Bücherschränke', 'Boîtes à livres'), 'amenity/public_bookcase'),
    ('parks', 'c43', 'nature', 'maki-park', 'leisure~^(garden|park|dog_park|common)$',
     ('Parks & gardens', 'Parken & tuinen', 'Parks & Gärten', 'Parcs & jardins'), 'leisure/park'),
    ('beach', None, 'water', 'maki-beach', 'natural=beach or leisure~^(beach_resort|swimming_area|bathing_place)$',
     ('Beaches & swim spots', 'Stranden & zwemplekken', 'Strände & Badestellen', 'Plages & baignades'), 'natural/beach'),
    ('boating', None, 'water', 'maki-harbor',
     'leisure~^(marina|slipway)$ or amenity~^(boat_rental|boat_sharing)$ or waterway~^(fuel|boatyard|dock|sanitary_dump_station)$ or shop=boat',
     ('Boats & marinas', 'Boten & jachthavens', 'Boote & Häfen', 'Bateaux & ports'), 'leisure/marina'),
    ('offices', 'c44', 'service', 'temaki-briefcase', 'office or craft',
     ('Offices & studios', 'Kantoren & ateliers', 'Büros & Werkstätten', 'Bureaux & ateliers'), 'office'),
    ('rentals', 'c45', 'transport', 'maki-bicycle-share',
     'amenity~^(bicycle_rental|taxi|car_sharing|car_rental|kick-scooter_rental|motorcycle_rental)$',
     ('Bikes & taxis', 'Fietsen & taxi’s', 'Räder & Taxis', 'Vélos & taxis'), 'amenity/bicycle_rental'),
    ('sauna_spa', 'c46', 'water', 'place.sauna', 'leisure~^(sauna|spa)$ or amenity=public_bath',
     ('Sauna & spa', 'Sauna & spa', 'Sauna & Spa', 'Sauna & spa'), 'leisure/sauna'),
    ('nature_spots', None, 'nature', 'maki-mountain',
     'natural~^(peak|volcano|spring|cave_entrance|glacier|bay|cape)$ or waterway=waterfall',
     ('Peaks & springs', 'Toppen & bronnen', 'Gipfel & Quellen', 'Sommets & sources'), 'natural/peak'),
    ('food_shops', 'c19', 'groceries', 'maki-grocery',
     'shop~^(supermarket|convenience|bakery|butcher|greengrocer|cheese|deli|seafood|pastry|confectionery|chocolate|coffee|tea|health_food|organic|farm|frozen_food|dairy|spices|nuts|pasta|food|ice_cream|water|alcohol|wine|beverages)$ or amenity=marketplace',
     ('Groceries', 'Boodschappen', 'Lebensmittel', 'Courses'), 'shop/supermarket'),
    ('shopping', 'c19', 'shops', 'maki-shop', 'shop',
     ('Shops', 'Winkels', 'Geschäfte', 'Boutiques'), 'shop'),
    ('other', 'other', 'service', 'maki-marker', 'amenity or tourism or leisure or healthcare or club or emergency~^(first_aid_kit|yes)$',
     ('Other', 'Overig', 'Sonstiges', 'Autres'), None),
]
