"""Hand tables for the build: CC0 stand-ins for preset icons from non-CC0 sets, cuisine symbols, regions, brand colours."""

# id-tagging-schema presets also use Font Awesome (CC BY 4.0), Röntgen (CC BY 4.0) and iD sprites. Atlas ships only CC0
# symbols, so each of those maps to the closest Maki, Temaki or Pinhead icon (CC0). Names that match a Pinhead icon
# directly (fas-tshirt -> pinhead-tshirt) are found automatically; these are the rest.
ICON_STANDINS = {
    'far-credit-card': 'pinhead-payment_card', 'far-thumbs-up': 'pinhead-thumbs_up_hand_with_sleeve',
    'fas-ambulance': 'maki-hospital', 'fas-archive': 'temaki-storage', 'fas-archway': 'pinhead-arch',
    'fas-baby-carriage': 'pinhead-baby', 'fas-balance-scale': 'pinhead-balance_scales', 'fas-bath': 'temaki-shower',
    'fas-blender': 'temaki-electronic', 'fas-border-all': 'temaki-tiling', 'fas-bowl-rice': 'pinhead-noodle_bowl_with_steam',
    'fas-box': 'temaki-storage', 'fas-box-open': 'temaki-storage', 'fas-building-flag': 'pinhead-office_building_with_flag',
    'fas-bullseye': 'temaki-archery', 'fas-burn': 'pinhead-fire', 'fas-camera-retro': 'pinhead-camera',
    'fas-campground': 'maki-campsite', 'fas-cannabis': 'pinhead-leaf', 'fas-car-crash': 'maki-road-accident',
    'fas-carrot': 'maki-grocery', 'fas-cat': 'pinhead-cat_sitting', 'fas-cheese': 'pinhead-cheese_wedge',
    'fas-chess-bishop': 'pinhead-chess_knight', 'fas-chess-pawn': 'pinhead-chess_knight',
    'fas-chevron-circle-up': 'pinhead-chevron_up_tall', 'fas-children': 'maki-playground', 'fas-church': 'maki-religious-christian',
    'fas-code': 'pinhead-laptop', 'fas-coins': 'pinhead-flat_coin', 'fas-comment': 'pinhead-speech_bubble',
    'fas-comments': 'pinhead-speech_bubbles', 'fas-compact-disc': 'pinhead-film_and_optical_disc',
    'fas-concierge-bell': 'maki-lodging', 'fas-crutch': 'maki-doctor', 'fas-dolly': 'temaki-storage',
    'fas-door-closed': 'maki-entrance', 'fas-door-open': 'maki-entrance',
    'fas-drafting-compass': 'pinhead-steel_square_and_drafting_compass', 'fas-drumstick-bite': 'pinhead-poultry_drumstick',
    'fas-dumpster': 'temaki-waste', 'fas-dumpster-fire': 'temaki-waste', 'fas-dungeon': 'maki-castle',
    'fas-flask': 'pinhead-round_bottomed_flask', 'fas-frog': 'pinhead-frog_with_cartoon_eyes_and_open_mouth',
    'fas-futbol': 'maki-soccer', 'fas-gavel': 'pinhead-gavel_with_sound_block', 'fas-guitar': 'maki-music',
    'fas-hand-back-fist': 'temaki-hand', 'fas-hand-holding': 'temaki-hand', 'fas-hands-holding-child': 'temaki-social_facility',
    'fas-hat-cowboy-side': 'pinhead-ranger_hat', 'fas-hiking': 'pinhead-person_walking_with_hiking_pole',
    'fas-horse': 'maki-horse-riding', 'fas-hospital-alt': 'maki-hospital', 'fas-hot-tub-person': 'maki-hot-spring',
    'fas-hotdog': 'pinhead-hot_dog', 'fas-hotel': 'pinhead-bed', 'fas-house-fire': 'maki-fire-station',
    'fas-house-flood-water': 'maki-water', 'fas-kitchen-set': 'temaki-kitchen_sink', 'fas-language': 'maki-school',
    'fas-mail-bulk': 'maki-post', 'fas-map': 'pinhead-map_outline', 'fas-map-signs': 'pinhead-sign',
    'fas-medkit': 'maki-doctor', 'fas-microchip': 'temaki-electronic', 'fas-mobile-alt': 'maki-mobile-phone',
    'fas-mobile-screen': 'maki-mobile-phone', 'fas-mosque': 'maki-religious-muslim', 'fas-pen-ruler': 'pinhead-pen_nib',
    'fas-people-carry': 'pinhead-crowd_of_people', 'fas-pepper-hot': 'pinhead-taco', 'fas-phone-alt': 'maki-telephone',
    'fas-plane': 'maki-airport', 'fas-plate-wheat': 'maki-restaurant', 'fas-plug-circle-bolt': 'pinhead-plug_with_bolt',
    'fas-print': 'temaki-electronic', 'fas-procedures': 'maki-hospital', 'fas-running': 'pinhead-person_running',
    'fas-satellite-dish': 'pinhead-axial_reflecting_dish_with_tripod_antenna', 'fas-screwdriver': 'temaki-tools',
    'fas-seedling': 'temaki-plant', 'fas-server': 'temaki-electronic', 'fas-sign-hanging': 'pinhead-sign',
    'fas-skating': 'temaki-ice_skating', 'fas-skiing-nordic': 'temaki-cross_country_skiing', 'fas-sleigh': 'temaki-sledding',
    'fas-smoking': 'pinhead-cigarette_with_smoke_curl', 'fas-solar-panel': 'temaki-power', 'fas-space-shuttle': 'maki-rocket',
    'fas-store': 'pinhead-market_stall', 'fas-store-alt-slash': 'maki-shop', 'fas-suitcase-rolling': 'pinhead-suitcase',
    'fas-swimmer': 'maki-swimming', 'fas-swimming-pool': 'maki-swimming', 'fas-synagogue': 'maki-religious-jewish',
    'fas-teeth': 'maki-dentist', 'fas-tint': 'pinhead-droplet', 'fas-tractor': 'maki-farm', 'fas-trash-restore': 'maki-recycling',
    'fas-triangle-exclamation': 'maki-danger', 'fas-truck-loading': 'temaki-truck', 'fas-umbrella-beach': 'maki-beach',
    'fas-user-tie': 'temaki-briefcase', 'fas-users': 'pinhead-crowd_of_people', 'fas-vest': 'pinhead-tshirt',
    'fas-vial': 'pinhead-round_bottomed_flask', 'fas-video': 'pinhead-video_camera_symbol', 'fas-vihara': 'maki-religious-buddhist',
    'fas-volume-up': 'temaki-speaker', 'fas-vote-yea': 'maki-town-hall', 'fas-water-ladder': 'maki-swimming',
    'fas-weight': 'pinhead-bathroom_scale', 'fas-person-booth': 'maki-town-hall', 'fas-tape': 'temaki-tools',
    'fas-thermometer-three-quarters': 'pinhead-tired_face_with_oral_thermometer_and_brow_sweat',
    'iD-highway-steps': 'pinhead-step_ladder', 'iD-waterway-canal': 'maki-water', 'iD-waterway-river': 'maki-water',
    'iD-waterway-stream': 'maki-water',
    'roentgen-advertising_column': 'temaki-billboard', 'roentgen-advertising_totem': 'pinhead-digital_totem',
    'roentgen-apartments_1_story': 'pinhead-lowrise_apartment_building_on_ground', 'roentgen-barrier_jersey': 'maki-barrier',
    'roentgen-breakwater': 'temaki-wall', 'roentgen-building_construction': 'maki-construction',
    'roentgen-building_door': 'maki-entrance', 'roentgen-building_part': 'maki-building', 'roentgen-building_with_plant': 'maki-building',
    'roentgen-bus_over_bus_trap': 'maki-bus', 'roentgen-bus_stop_sign': 'maki-bus', 'roentgen-buses': 'maki-bus',
    'roentgen-circle_empty': 'maki-circle-stroked', 'roentgen-city_limit_sign': 'maki-town', 'roentgen-crane_gantry': 'temaki-crane',
    'roentgen-crane_portal': 'temaki-crane', 'roentgen-diving_1_platforms': 'temaki-diving', 'roentgen-food_court': 'maki-restaurant',
    'roentgen-ford': 'maki-water', 'roentgen-full_height_turnstile': 'temaki-turnstile', 'roentgen-golf_tee': 'maki-golf',
    'roentgen-hazelnut_with_leaves': 'temaki-tree_broadleaved', 'roentgen-hopscotch': 'maki-playground',
    'roentgen-horizontal_ladder': 'temaki-horizontal_bar', 'roentgen-i_in_square': 'maki-information',
    'roentgen-log_lying': 'temaki-tree_stump', 'roentgen-mini_bumps': 'temaki-speed_bump',
    'roentgen-needleleaved_tree': 'temaki-tree_needleleaved', 'roentgen-person_bungee_jumping': 'pinhead-person_running',
    'roentgen-person_throwing_spear': 'maki-stadium', 'roentgen-plane': 'maki-airport', 'roentgen-pole_dancer': 'maki-nightclub',
    'roentgen-rails_with_derailer': 'temaki-railway_track', 'roentgen-rings': 'temaki-gym', 'roentgen-sinking_ship': 'maki-ferry',
    'roentgen-sit_up': 'temaki-gym', 'roentgen-slide_and_water': 'temaki-slide', 'roentgen-spray_can_spraying': 'maki-paint',
    'roentgen-telescope_radio': 'temaki-telescope', 'roentgen-traffic_cushion': 'temaki-speed_hump',
    'roentgen-tree': 'temaki-tree_broadleaved', 'roentgen-turning_loop': 'maki-circle-stroked',
    'roentgen-vending_bottle': 'temaki-vending_cold_drink', 'roentgen-vending_candy': 'temaki-vending_machine',
    'roentgen-vending_flower': 'temaki-vending_machine', 'roentgen-vending_pack': 'temaki-vending_machine',
    'roentgen-vending_pizza': 'temaki-vending_machine', 'roentgen-water_from_pipe': 'temaki-water_tap',
    'roentgen-wig_on_stand': 'temaki-hair_care', 'fas-dragon': 'pinhead-crown', 'fas-bugs': 'maki-veterinary',
    'fas-compress-arrows-alt': 'maki-circle', 'fas-directions': 'pinhead-sign', 'fas-helmet-safety': 'maki-construction',
    'fas-tachometer-alt': 'temaki-gas_meter', 'fas-th': 'temaki-tiling', 'fas-user-secret': 'maki-police',
    'fas-person-arrow-up-from-line': 'maki-elevator',
}

# Cuisine values: (symbol, ISO country of a national cuisine or None). Symbols are CC0 map icons.
NOODLE, BOWL, ROTI = 'maki-restaurant-noodle', 'pinhead-handled_bowl_with_steam', 'temaki-vertical_rotisserie'
R = 'maki-restaurant'
CUISINE = {
    'pizza': ('pinhead-pizza_slice', None), 'burger': ('pinhead-burger', None), 'coffee_shop': ('pinhead-coffee_mug_with_steam', None),
    'regional': (R, None), 'italian': (R, 'IT'), 'sandwich': ('pinhead-sub_sandwich', None), 'chinese': (NOODLE, 'CN'),
    'chicken': ('pinhead-poultry_drumstick', None), 'japanese': ('pinhead-sushi', 'JP'), 'kebab': (ROTI, None),
    'mexican': ('pinhead-taco', 'MX'), 'american': ('pinhead-burger', 'US'), 'asian': (NOODLE, None),
    'ice_cream': ('pinhead-ice_cream_on_cone', None), 'indian': (BOWL, 'IN'), 'sushi': ('pinhead-sushi', None),
    'seafood': ('maki-restaurant-seafood', None), 'thai': (NOODLE, 'TH'), 'french': (R, 'FR'), 'german': ('pinhead-sausage', 'DE'),
    'breakfast': ('temaki-egg', None), 'greek': (R, 'GR'), 'steak_house': ('pinhead-meat', None),
    'fish_and_chips': ('pinhead-fish', 'GB'), 'korean': ('maki-restaurant-bbq', 'KR'), 'barbecue': ('maki-restaurant-bbq', None),
    'donut': ('pinhead-donut', None), 'noodle': (NOODLE, None), 'vietnamese': (NOODLE, 'VN'), 'fish': ('pinhead-fish', None),
    'turkish': (ROTI, 'TR'), 'cake': ('pinhead-cupcake_with_cherry', None), 'pasta': (R, None), 'tex-mex': ('pinhead-taco', None),
    'bubble_tea': ('temaki-bubble_tea', None), 'ramen': (NOODLE, 'JP'), 'mediterranean': (R, None), 'spanish': (R, 'ES'),
    'friture': ('maki-fast-food', 'BE'), 'tea': ('maki-teahouse', None), 'grill': ('maki-restaurant-bbq', None),
    'juice': ('pinhead-drink_cup_with_straw', None), 'salad': ('pinhead-leaf', None), 'crepe': (R, 'FR'), 'waffle': (R, 'BE'),
    'hot_dog': ('pinhead-hot_dog', None), 'hotpot': ('temaki-hotpot', None), 'pancake': (R, None),
    'dessert': ('pinhead-cupcake_with_cherry', None), 'diner': ('pinhead-burger', 'US'), 'tapas': (R, 'ES'), 'portuguese': (R, 'PT'),
    'beef_bowl': (BOWL, 'JP'), 'russian': (R, 'RU'), 'indonesian': (BOWL, 'ID'), 'wings': ('pinhead-poultry_drumstick', None),
    'lebanese': (ROTI, 'LB'), 'arab': (ROTI, None), 'curry': (BOWL, None), 'malaysian': (BOWL, 'MY'), 'bagel': ('pinhead-bagel', None),
    'georgian': (R, 'GE'), 'polish': (R, 'PL'), 'african': (R, None), 'western': (R, None), 'sausage': ('pinhead-sausage', None),
    'filipino': (R, 'PH'), 'caribbean': (R, None), 'soba': (NOODLE, 'JP'), 'peruvian': (R, 'PE'), 'brazilian': ('maki-restaurant-bbq', 'BR'),
    'oriental': (NOODLE, None), 'fine_dining': (R, None), 'frozen_yogurt': ('pinhead-ice_cream_on_cone', None),
    'argentinian': ('maki-restaurant-bbq', 'AR'), 'balkan': ('maki-restaurant-bbq', None), 'bavarian': ('pinhead-sausage', 'DE'),
    'british': (R, 'GB'), 'ethiopian': (R, 'ET'), 'shawarma': (ROTI, None), 'persian': (R, 'IR'), 'middle_eastern': (ROTI, None),
    'pastry': ('pinhead-croissant', None), 'soup': (BOWL, None), 'fries': ('maki-fast-food', None), 'taiwanese': (NOODLE, 'TW'),
    'bistro': (R, None), 'european': (R, None), 'moroccan': (R, 'MA'), 'hawaiian': (R, 'US'), 'brunch': ('temaki-egg', None),
    'udon': (NOODLE, 'JP'), 'syrian': (ROTI, 'SY'), 'ukrainian': (R, 'UA'), 'austrian': (R, 'AT'), 'nepalese': (BOWL, 'NP'),
    'irish': (R, 'IE'), 'croatian': (R, 'HR'), 'pakistani': (BOWL, 'PK'), 'lao': (NOODLE, 'LA'), 'hungarian': (R, 'HU'),
    'bolivian': (R, 'BO'), 'jamaican': (R, 'JM'), 'cuban': (R, 'CU'), 'dutch': (R, 'NL'), 'belgian': (R, 'BE'),
    'surinamese': (BOWL, 'SR'), 'israeli': (R, 'IL'), 'falafel': (ROTI, None), 'dumpling': (NOODLE, None), 'poke': (BOWL, None),
    'coffee': ('pinhead-coffee_mug_with_steam', None), 'chocolate': ('temaki-chocolate', None), 'bakery': ('temaki-bread', None),
    'bread': ('temaki-bread', None), 'pie': (R, None), 'crepes': (R, 'FR'), 'tacos': ('pinhead-taco', None), 'vegan': ('pinhead-leaf', None),
    'vegetarian': ('pinhead-leaf', None), 'cheese': ('pinhead-cheese_wedge', None), 'crab': ('pinhead-crab', None),
    'snack': ('maki-fast-food', None), 'fast_food': ('maki-fast-food', None), 'dim_sum': (NOODLE, 'CN'), 'cantonese': (NOODLE, 'CN'),
    'sichuan': (NOODLE, 'CN'), 'south_american': (R, None), 'latin_american': (R, None), 'scandinavian': (R, None),
    'danish': (R, 'DK'), 'swedish': (R, 'SE'), 'swiss': (R, 'CH'), 'czech': (R, 'CZ'), 'afghan': (R, 'AF'), 'tibetan': (R, None),
    'sri_lankan': (BOWL, 'LK'), 'mongolian': (R, 'MN'), 'singaporean': (BOWL, 'SG'), 'colombian': (R, 'CO'), 'venezuelan': (R, 'VE'),
    'chilean': (R, 'CL'), 'korean_bbq': ('maki-restaurant-bbq', 'KR'), 'yakitori': ('maki-restaurant-bbq', 'JP'),
    'smoothie': ('pinhead-drink_cup_with_straw', None), 'milkshake': ('pinhead-drink_cup_with_straw', None),
}

# UN M49 and other region codes in locationSets, as ISO countries (lower case).
EUROPE = ('ad al at ax ba be bg by ch cy cz de dk ee es fi fo fr gb gg gi gr hr hu ie im is it je li lt lu lv mc md me mk mt '
          'nl no pl pt ro rs ru se si sj sk sm ua va xk').split()
NORTH_AMERICA = 'us ca mx gl pm bm'.split()
LATIN_AMERICA = ('ag ai ar aw bb bl bo bq br bs bz cl co cr cu cw dm do ec fk gd gf gp gt gy hn ht jm kn ky lc mf mq ms mx ni pa '
                 'pe pr py sr sv sx tc tt uy vc ve vg vi').split()
ASIA = ('af am az bd bh bn bt cn cy ge hk id il in iq ir jo jp kg kh kp kr kw kz la lb lk mm mn mo mv my np om ph pk ps qa sa '
        'sg sy th tj tl tm tr tw uz vn ye ae').split()
AFRICA = ('ao bf bi bj bw cd cf cg ci cm cv dj dz eg eh er et ga gh gm gn gq gw ke km lr ls ly ma mg ml mr mu mw mz na ne ng '
          're rw sc sd sh sl sn so ss st sz td tg tn tz ug yt za zm zw').split()
OCEANIA = 'au nz fj pg sb vu nc pf ws to ki fm mh pw nr tv ck nu'.split()
EU = 'at be bg hr cy cz dk ee fi fr de gr hu ie it lv lt lu mt nl pl pt ro sk si es se'.split()
REGIONS = {
    '001': ['*'], 'planet': ['*'], '150': EUROPE, 'q46': EUROPE, 'eu': EU, '003': NORTH_AMERICA, '021': ['us', 'ca'],
    '419': LATIN_AMERICA, '005': [c for c in LATIN_AMERICA if c in 'ar bo br cl co ec fk gf gy pe py sr uy ve'.split()],
    '013': 'bz cr gt hn mx ni pa sv'.split(), '029': [c for c in LATIN_AMERICA if c not in 'ar bo br cl co ec fk gf gy pe py sr uy ve bz cr gt hn mx ni pa sv'.split()],
    '019': NORTH_AMERICA + LATIN_AMERICA, '142': ASIA, '002': AFRICA, '009': OCEANIA, '053': ['au', 'nz'],
    '155': 'at be ch de fr li lu mc nl'.split(), '154': 'dk ee fi fo gb ie im is lt lv no se'.split(),
    '039': 'ad al ba es gi gr hr it me mk mt pt rs si sm va xk'.split(), '151': 'bg by cz hu md pl ro ru sk ua'.split(),
    '145': 'ae am az bh cy ge il iq jo kw lb om ps qa sa sy tr ye'.split(), '030': 'cn hk jp kp kr mn mo tw'.split(),
    '035': 'bn id kh la mm my ph sg th tl vn'.split(), '034': 'af bd bt in ir lk mv np pk'.split(), '143': 'kg kz tj tm uz'.split(),
    '015': 'dz eg eh ly ma sd tn'.split(), '202': [c for c in AFRICA if c not in 'dz eg eh ly ma sd tn'.split()],
    'conus': ['us'], 'us-hi': ['us'], 'northern cyprus': ['cy'], 'mainland china': ['cn'], 'fx': ['fr'],
}

# render/poi_style.py BRAND: sign colours (background, text) by brand:wikidata, which win over simple-icons' colour.
BRAND_COLOURS = {
    'Q1653985': ('00A0E2', 'FFFFFF'), 'Q13639185': ('9E1B32', 'FFFFFF'), 'Q2609459': ('E30613', 'FFFFFF'),
    'Q33903645': ('E3000F', 'FFFFFF'), 'Q582245': ('FFD200', 'C8102E'), 'Q62562792': ('7AB929', 'FFFFFF'),
    'Q74051230': ('FFB800', '1E1E1E'), 'Q127962': ('C41230', 'FFFFFF'), 'Q1126126': ('1A1A1A', 'F6D21E'),
    'Q6702957': ('D31F40', 'FFFFFF'), 'Q60772401': ('1A1A1A', 'FFFFFF'), 'Q648458': ('1E3765', 'FFFFFF'),
    'Q2220070': ('23412F', 'E9D9A6'), 'Q13433690': ('5B3A29', 'F2E6CF'), 'Q3572115': ('FFE600', '1E1E1E'),
    'Q934096': ('9B1B1F', 'FFFFFF'), 'Q527877': ('2B2A28', 'E89A2B'), 'Q24039229': ('1E1E1E', 'FFFFFF'),
    'Q7958488': ('1E1E1E', 'FFFFFF'), 'Q123655125': ('F4C6CF', '3A2A2E'),
}

# Common Dutch chains the prototype did not colour, by NSI display name (resolved to brand:wikidata at build time).
BRAND_COLOURS_BY_NAME = {
    'HEMA': ('E30613', 'FFFFFF'), 'Jumbo': ('FFD500', '1E1E1E'), 'New York Pizza': ('00843D', 'FFFFFF'),
    'Jamin': ('E2001A', 'FFFFFF'), 'Kruidvat': ('E2001A', 'FFFFFF'), 'Action': ('005CA9', 'FFFFFF'),
}

# Name-only logo matches that are another company's glyph (checked by hand against simple-icons' sources).
LOGO_DENY = {
    'AMP', 'Anaconda', 'Astro', 'Bento', 'Black', 'Box', 'Burton', 'Castro', 'CCC', 'Cockpit', 'comma', 'Cora (Quebec)',
    'Cora (non-Quebec)', 'Delta (Costa Rica/Panama)', 'Delta (New Jersey)', 'Element by Westin', 'Express', 'Express Men',
    'Four Square', 'FOX', 'Gusto (UK)', 'Hermès', 'Insomnia', 'IRIS', 'K&Ö', 'KiK', 'KODi', 'Loop', 'Lucid', 'Lumen',
    'Metro (Cyprus)', 'Metro (България)', 'Metro (Ontario/Quebec)', 'Metro (Perú)', 'Metro Gastro', 'Minimax',
    'Monoprix (Tunisia)', 'NET', 'Odin', 'PDQ', 'Presto', 'Prisma', 'Proton', 'Relay', 'Rocket', 'Roots', 'Rumble',
    'Sphinx', 'Spectrum', 'Spotlight', 'Swift', 'Ted', 'Velocity', 'Veritas', 'V.I.M.', 'Vulkan', 'Wasabi', 'Zoom',
    'Coop (Schweiz)', 'Coop (Italia)', 'Coop (Nederland)', 'Coop (Česko)', 'Coop (Sverige)', 'קואופ שופ',
    'Netto (Les Mousquetaires)', 'Netto (Salling)', 'Nettó (Ísland)', 'Sparkasse (Österreich)',
}
