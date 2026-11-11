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
# Atlas's own spaghetti twirled on a fork (custom_icons.txt, 10 November), the Italian restaurant's kind symbol too (ours
# again where a kind had one: the owner, 10 November, the evening, "The simple style of our icons looked good"; README
# decision 13), and Atlas's own filled chilli (atlas_icons.txt), the Indian restaurant's: the boards' spice jar read as a
# milk carton at a pin's size and no board draws an Indian chip (the parent's verdict, 10 November, the evening). Pinhead's
# taco is its own filled drawing again, the Mexican restaurant's too (board_symbol_names.py drops its alias). The chips
# follow their kinds where a board draws the dish (step 1b, the parent's verdict, 10 November, the evening), through
# the aliases of board_symbol_names.py: the Street board's kebab for kebab, Turkish and every rotisserie cuisine, its
# hot dog; the boards' slice, sushi and cone for pizza, sushi, Japanese and ice cream. Chicken and wings keep Pinhead's
# filled drumstick, as their kinds do: the boards' line drumstick reads as a magnifier at a pin (the parent's verdict,
# 10 November, the night)
SPAGHETTI, CHILLI = 'ui-spaghetti', 'atlas-chilli'
CUISINE = {
    'pizza': ('pinhead-pizza_slice', None), 'burger': ('pinhead-burger', None), 'coffee_shop': ('pinhead-coffee_mug_with_steam', None),
    'regional': (R, None), 'italian': (SPAGHETTI, 'IT'), 'sandwich': ('pinhead-sub_sandwich', None), 'chinese': (NOODLE, 'CN'),
    'chicken': ('pinhead-poultry_drumstick', None), 'japanese': ('pinhead-sushi', 'JP'), 'kebab': (ROTI, None),
    'mexican': ('pinhead-taco', 'MX'), 'american': ('pinhead-burger', 'US'), 'asian': (NOODLE, None),
    'ice_cream': ('pinhead-ice_cream_on_cone', None), 'indian': (CHILLI, 'IN'), 'sushi': ('pinhead-sushi', None),
    'seafood': ('maki-restaurant-seafood', None), 'thai': (NOODLE, 'TH'), 'french': (R, 'FR'), 'german': ('pinhead-sausage', 'DE'),
    'breakfast': ('temaki-egg', None), 'greek': (R, 'GR'), 'steak_house': ('pinhead-meat', None),
    'fish_and_chips': ('pinhead-fish', 'GB'), 'korean': ('maki-restaurant-bbq', 'KR'), 'barbecue': ('maki-restaurant-bbq', None),
    'donut': ('pinhead-donut', None), 'noodle': (NOODLE, None), 'vietnamese': (NOODLE, 'VN'), 'fish': ('pinhead-fish', None),
    'turkish': (ROTI, 'TR'), 'cake': ('pinhead-cupcake_with_cherry', None), 'pasta': (SPAGHETTI, None), 'tex-mex': ('pinhead-taco', None),
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
    # Common Dutch chains the prototype did not colour: HEMA, Jumbo (Europe), New York Pizza, Jamin, Kruidvat, Action.
    # By item, never by name: "Jumbo" by name was the Swiss DIY and the Greek toy chains, never the Dutch supermarket.
    'Q903805': ('E30613', 'FFFFFF'), 'Q2262314': ('FFD500', '1E1E1E'), 'Q2639128': ('00843D', 'FFFFFF'),
    'Q2291848': ('E2001A', 'FFFFFF'), 'Q2226366': ('E2001A', 'FFFFFF'), 'Q2634111': ('005CA9', 'FFFFFF'),
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

# Set icons as Atlas draws them where a set's own drawing has a fault (build_places_data.mend_icon; the sets keep theirs),
# from the sweep after the toilet's heads (the owner, 10 November: "These kinds of defects"). Measured by EmblemShapeTest.
# ICON_MENDS: a slip in a drawing, as (what its converted path says, what Atlas draws instead).
ICON_MENDS = {
    # The antenna's right inner wave stood a quarter unit nearer the mast than the left one, so the gaps beside the mast
    # and between the waves were uneven: the left one mirrored.
    'temaki-antenna': [(
        'M9.96 2.53C9.73 2.56 9.83 2.52 9.65 2.65C9.45 2.84 9.45 3.16 9.65 3.35C10.45 4.16 10.45 5.84 9.65 6.65C9.45 6.84 '
        '9.45 7.16 9.65 7.35C9.84 7.55 10.16 7.55 10.35 7.35C11.55 6.16 11.55 3.84 10.35 2.65L10.19 2.54L9.96 2.53z',
        'M10.29 2.53C10.52 2.56 10.42 2.52 10.6 2.65C11.8 3.84 11.8 6.16 10.6 7.35C10.41 7.55 10.09 7.55 9.9 7.35C9.7 7.16 '
        '9.7 6.84 9.9 6.65C10.7 5.84 10.7 4.16 9.9 3.35C9.7 3.16 9.7 2.84 9.9 2.65L10.06 2.54L10.29 2.53z',
    )],
    # The fountain's right jet fell short of the left one's mirror: its arch lower and narrower, its drop nearer the
    # basin's middle. The right jet made the left one mirrored about the fountain's axis (x 7.5).
    'temaki-fountain': [(
        'M3.75 1.5C1.5 1.5 .75 3.5 .75 6C.75 8 1.75 8 1.75 6C1.75 3.5 2.75 2.5 3.75 2.5C5.5 2.5 6.5 5.75 6.5 '
        '7C6.5 7 6.25 6.75 5.5 6.75C4.25 6.75 3.5 7.75 3.5 9.5C3.5 10.75 4.25 10.75 4.25 9.5C4.25 8.25 5 7.75 5.5 '
        '7.75C6 7.75 6.75 8 7 10C7.28 11.98 7.72 11.98 7.97 10C8.25 8 8.75 7.75 9.5 7.75C10 7.75 10.5 8.25 10.5 '
        '9C10.5 10.5 11.25 10.5 11.25 9C11.25 7.25 10.25 6.75 9.5 6.75C8.75 6.75 8.5 7 8.5 7C8.75 5.25 9.5 3 11 '
        '3C12.5 3 13.25 5 13.25 6.75C13.25 8.5 14.25 8.5 14.25 6.75C14.25 3.5 12.75 2 11 2C8.75 2 7.75 4.5 7.5 '
        '6C7.25 4 6.25 1.5 3.75 1.5z',
        'M3.75 1.5C1.5 1.5 .75 3.5 .75 6C.75 8 1.75 8 1.75 6C1.75 3.5 2.75 2.5 3.75 2.5C5.5 2.5 6.5 5.75 6.5 '
        '7C6.5 7 6.25 6.75 5.5 6.75C4.25 6.75 3.5 7.75 3.5 9.5C3.5 10.75 4.25 10.75 4.25 9.5C4.25 8.25 5 7.75 5.5 '
        '7.75C6 7.75 6.75 8 7 10C7.28 11.98 7.72 11.98 8 10C8.25 8 9 7.75 9.5 7.75C10 7.75 10.75 8.25 10.75 '
        '9.5C10.75 10.75 11.5 10.75 11.5 9.5C11.5 7.75 10.75 6.75 9.5 6.75C8.75 6.75 8.5 7 8.5 7C8.5 5.75 9.5 2.5 '
        '11.25 2.5C12.25 2.5 13.25 3.5 13.25 6C13.25 8 14.25 8 14.25 6C14.25 3.5 13.5 1.5 11.25 1.5C8.75 1.5 7.75 '
        '4 7.5 6C7.25 4 6.25 1.5 3.75 1.5z',
    )],
    # The banknote's portrait was nine stripes a quarter unit thick, hairlines in a pin: one solid bust in its oval.
    'maki-bank': [(
        'M7.5 5.5c-.32 0-.53 .11-.68 .25h1.36C8.03 5.61 7.82 5.5 7.5 5.5zM6.62 6C6.58 6.09 6.56 6.17 6.54 6.2'
        '5h1.91C8.44 6.17 8.42 6.09 8.38 6H6.62zM6.5 6.5v.25h2V6.5H6.5zM6.5 7v.25h2V7H6.5zM6.5 7.5v.25h2V7.5H'
        '6.5zM6.5 8L6.25 8.25h2L8.5 8H6.5zM6 8.5c0 0 .04 .1 .1 .25H8.38L8 8.5H6z',
        'M7.5 5.5C7.82 5.5 8.03 5.61 8.18 5.75L8.38 6L8.5 6.5V8L8.25 8.25L8 8.5L8.38 8.75L8.75 9L8.65 9.25L8.'
        '5 9.5L8.25 9.75H6.83L6.57 9.5L6.38 9.25L6.24 9L6.1 8.75L6 8.5L6.25 8.25L6.5 8V6.5L6.54 6.25L6.62 6L6'
        '.82 5.75C6.97 5.61 7.18 5.5 7.5 5.5z',
    ), (
        'M6.24 9C6.28 9.09 6.31 9.16 6.38 9.25h2.27C8.71 9.11 8.75 9 8.75 9H6.24z',
        '',
    ), (
        'M6.57 9.5c.08 .09 .16 .18 .26 .25h1.42C8.35 9.67 8.43 9.59 8.5 9.5H6.57z',
        '',
    )],
    # Maki's tooth, the dentist's since every kind of place is on a filled symbol (10 November), dipped beside its
    # axis and its right root was not its left one's mirror: the left half, mirrored about the frame's middle.
    'maki-dentist': [(
        'M4.36 14c-1 0-.56-2.67-.86-5c-.1-.76-1-1.49-1.12-2.06C2 5 1.39 1.44 3.66 1S6 3 7.54 3s1.57-2.36 3.85'
        '-2s1.59 3.9 1.29 5.9c-.1 .45-1.1 1.48-1.18 2.06c-.33 2.4 .32 5-.8 5c-.93 0-1.32-2.72-2-4.5C8.43 8.63'
        ' 8.06 8 7.54 8C6 8 5.75 14 4.36 14z',
        'M7.5 3C6 3 5.93 .56 3.66 1C1.39 1.44 2 5 2.38 6.94C2.5 7.51 3.4 8.24 3.5 9C3.8 11.33 3.36 14 4.36 14'
        'C5.75 14 6 8 7.5 8C9 8 9.25 14 10.64 14C11.64 14 11.2 11.33 11.5 9C11.6 8.24 12.5 7.51 12.62 6.94C13'
        ' 5 13.61 1.44 11.34 1C9.07 .56 9 3 7.5 3z',
    )],
    # The swing's ropes were 0.22 wide, hairlines in a pin: 0.36, the child and the seat as they were.
    'maki-playground': [(
        'l4.7-.52v-4.13h.22v4.1h.06l.22-.02',
        'l4.63-.51v-4.14h.36v4.1l.21-.02',
    ), (
        'h-.22h-.06v2.86h.06l.22-.06',
        'h-.21v2.86l.21-.06',
    ), (
        'm-5.5-7.19l-2.5 .27l.74 2.94l1.76-.34z',
        'm-5.57-7.19l-2.43 .27l.74 2.94l1.69-.33z',
    ), (
        'l-.22 .08h-.06v2.15l.62-.15',
        'l-.21 .08v2.15l.55-.15',
    ), (
        'l4.15-.83v-2.17l-3.22 .58',
        'l4.13-.83v-2.17l-3.2 .58',
    )],
    # The ladder's rail bulged half a unit out of the frame at its top, the slide's only ink outside it (indoor play's
    # since 10 November): the rail straight.
    'temaki-slide': [(
        'L0 5.62C-.31 5.34-.5 4.94-.5 4.5C-.5 4.06-.31 3.66 0 3.38L0 1.5',
        'L0 1.5',
    )],
    # The radio's grille was a chequerboard whose squares met at their corners, 0.02 apart: its holes set in an
    # eighth of a unit, so the squares between them join.
    'temaki-radio': [(
        'M7 12L7 13L8 13L8 12L7 12Z',
        'M7.12 12.12L7.12 12.88L7.88 12.88L7.88 12.12L7.12 12.12Z',
    ), (
        'M2 9L2 10L3 10L3 9L2 9ZM3 10L3 11L4 11L4 10L3 10ZM4 9L4 10L5 10L5 9L4 9ZM5 10L5 11L6 11L6 10L5 10ZM6'
        ' 9L6 10L7 10L7 9L6 9ZM7 10L7 11L8 11L8 10L7 10ZM6 11L6 12L7 12L7 11L6 11ZM4 11L4 12L5 12L5 11L4 11ZM'
        '2 11L2 12L3 12L3 11L2 11ZM3 12L3 13L4 13L4 12L3 12ZM5 12L5 13L6 13L6 12L5 12Z',
        'M2.12 9.12L2.12 9.88L2.88 9.88L2.88 9.12L2.12 9.12ZM3.12 10.12L3.12 10.88L3.88 10.88L3.88 10.12L3.12'
        ' 10.12ZM4.12 9.12L4.12 9.88L4.88 9.88L4.88 9.12L4.12 9.12ZM5.12 10.12L5.12 10.88L5.88 10.88L5.88 10.'
        '12L5.12 10.12ZM6.12 9.12L6.12 9.88L6.88 9.88L6.88 9.12L6.12 9.12ZM7.12 10.12L7.12 10.88L7.88 10.88L7'
        '.88 10.12L7.12 10.12ZM6.12 11.12L6.12 11.88L6.88 11.88L6.88 11.12L6.12 11.12ZM4.12 11.12L4.12 11.88L'
        '4.88 11.88L4.88 11.12L4.12 11.12ZM2.12 11.12L2.12 11.88L2.88 11.88L2.88 11.12L2.12 11.12ZM3.12 12.12'
        'L3.12 12.88L3.88 12.88L3.88 12.12L3.12 12.12ZM5.12 12.12L5.12 12.88L5.88 12.88L5.88 12.12L5.12 12.12'
        'Z',
    )],
    # The can's tab was a hole 0.4 across, a speck in a pin: the lid solid.
    'temaki-vending_cold_drink': [(
        'M7.9 3.26C8.15 3.26 8.35 3.33 8.35 3.41C8.35 3.49 8.15 3.56 7.9 3.56C7.66 3.56 7.46 3.49 7.46 3.41C7'
        '.46 3.33 7.66 3.26 7.9 3.26z',
        '',
    )],
    # The flattened coin's rim was fifteen dashes 0.3 across, specks in a pin: the coin plain.
    'temaki-vending_flat_coin': [(
        'M7.5 2C9.17 2 10.5 2.5 10.5 3.5C10.5 4.5 9.17 5 7.5 5C5.83 5 4.5 4.5 4.5 3.5C4.5 2.5 5.83 2 7.5 2zM7'
        '.26 4.37C7.19 4.37 7.13 4.42 7.13 4.49C7.12 4.56 7.18 4.62 7.25 4.62C7.33 4.62 7.41 4.63 7.5 4.63C7.'
        '57 4.63 7.63 4.57 7.63 4.5C7.63 4.43 7.57 4.38 7.5 4.38C7.42 4.38 7.34 4.37 7.26 4.37zM8.16 4.34C8.0'
        '8 4.35 8 4.36 7.92 4.36C7.85 4.37 7.8 4.43 7.8 4.49C7.8 4.56 7.86 4.62 7.93 4.61C8.02 4.61 8.1 4.6 8'
        '.19 4.59C8.26 4.58 8.31 4.52 8.3 4.45C8.29 4.38 8.23 4.33 8.16 4.34zM6.53 4.3C6.46 4.28 6.39 4.33 6.'
        '38 4.4C6.37 4.47 6.41 4.53 6.48 4.54C6.57 4.56 6.65 4.57 6.74 4.58C6.8 4.59 6.87 4.54 6.88 4.47C6.88'
        ' 4.4 6.84 4.34 6.77 4.33C6.68 4.32 6.6 4.31 6.53 4.3zM8.93 4.19C8.85 4.21 8.78 4.23 8.7 4.25C8.63 4.'
        '27 8.59 4.33 8.6 4.4C8.62 4.47 8.68 4.51 8.75 4.5C8.84 4.48 8.92 4.46 9 4.43C9.07 4.41 9.1 4.34 9.08'
        ' 4.28C9.07 4.21 9 4.17 8.93 4.19zM5.82 4.11C5.76 4.08 5.69 4.11 5.66 4.18C5.64 4.24 5.67 4.32 5.73 4'
        '.34C5.81 4.37 5.89 4.4 5.98 4.42C6.04 4.44 6.11 4.41 6.13 4.34C6.15 4.27 6.12 4.21 6.05 4.18C5.97 4.'
        '16 5.89 4.14 5.82 4.11zM9.71 3.85L9.62 3.88C9.56 3.92 9.5 3.96 9.42 4C9.36 4.03 9.34 4.11 9.37 4.17C'
        '9.4 4.23 9.48 4.25 9.54 4.22C9.62 4.17 9.7 4.13 9.77 4.08C9.82 4.04 9.84 3.96 9.79 3.9L9.71 3.85zM5.'
        '14 3.69L5.05 3.71C4.99 3.76 4.99 3.84 5.03 3.89C5.09 3.96 5.16 4.02 5.24 4.08C5.29 4.12 5.37 4.11 5.'
        '41 4.05C5.45 4 5.44 3.92 5.39 3.88C5.32 3.83 5.27 3.78 5.22 3.73L5.14 3.69zM10 3.2L9.9 3.2C9.84 3.23'
        ' 9.81 3.31 9.84 3.37C9.88 3.51 9.87 3.53 9.87 3.54C9.86 3.61 9.9 3.68 9.97 3.69C10.04 3.7 10.11 3.65'
        ' 10.12 3.59C10.12 3.56 10.13 3.53 10.13 3.49C10.12 3.42 10.1 3.34 10.07 3.26L10 3.2zM5.2 2.99L5.11 3'
        '.03C5.03 3.1 4.97 3.18 4.93 3.26C4.9 3.33 4.93 3.4 4.99 3.43C5.05 3.46 5.13 3.43 5.16 3.37C5.18 3.32'
        ' 5.22 3.26 5.28 3.21C5.33 3.16 5.33 3.08 5.29 3.03L5.2 2.99zM9.3 2.71L9.23 2.78C9.21 2.84 9.23 2.91 '
        '9.3 2.94C9.37 2.98 9.44 3.01 9.51 3.05C9.57 3.09 9.64 3.07 9.68 3.01C9.71 2.95 9.69 2.87 9.63 2.84C9'
        '.56 2.79 9.48 2.75 9.4 2.71L9.3 2.71zM5.85 2.62C5.76 2.65 5.68 2.68 5.6 2.71C5.54 2.74 5.51 2.82 5.5'
        '4 2.88C5.57 2.94 5.64 2.97 5.71 2.94C5.77 2.91 5.85 2.88 5.93 2.85C5.99 2.83 6.03 2.76 6.01 2.7C5.98'
        ' 2.63 5.91 2.6 5.85 2.62zM8.65 2.48C8.58 2.47 8.51 2.51 8.5 2.58C8.49 2.65 8.53 2.71 8.6 2.73C8.68 2'
        '.74 8.76 2.76 8.83 2.78C8.9 2.8 8.97 2.76 8.99 2.69C9 2.63 8.96 2.56 8.9 2.54C8.82 2.52 8.73 2.5 8.6'
        '5 2.48zM6.66 2.43C6.57 2.44 6.48 2.46 6.4 2.47C6.33 2.49 6.29 2.55 6.3 2.62C6.32 2.69 6.38 2.73 6.45'
        ' 2.72C6.53 2.7 6.61 2.69 6.69 2.68C6.76 2.67 6.81 2.61 6.8 2.54C6.79 2.47 6.72 2.42 6.66 2.43zM7.88 '
        '2.39C7.82 2.38 7.76 2.44 7.75 2.5C7.75 2.57 7.8 2.63 7.87 2.64C7.95 2.64 8.03 2.65 8.11 2.66C8.18 2.'
        '66 8.24 2.61 8.25 2.54C8.26 2.48 8.21 2.41 8.14 2.41C8.06 2.4 7.97 2.39 7.88 2.39zM7.36 2.38C7.28 2.'
        '38 7.19 2.38 7.11 2.39C7.04 2.39 6.99 2.45 6.99 2.52C7 2.59 7.06 2.64 7.12 2.64C7.2 2.63 7.29 2.63 7'
        '.37 2.63C7.44 2.63 7.49 2.57 7.49 2.5C7.49 2.43 7.43 2.38 7.36 2.38z',
        'M7.5 2C9.17 2 10.5 2.5 10.5 3.5C10.5 4.5 9.17 5 7.5 5C5.83 5 4.5 4.5 4.5 3.5C4.5 2.5 5.83 2 7.5 2z',
    )],
    # The machine's shelves were a quarter unit thick, hairlines between the bottles: each top raised a tenth.
    'temaki-vending_machine': [(
        'M9.5 3.5L9.5 3.75L4.5 3.75L4.5 3.5L4.75 3.5C4.75 3.5 4.75 2.75 4.75 2.75C4.75 2.5 5.25 2.5 5.25 2.75'
        'C5.25 2.75 5.25 3.5 5.25 3.5L6 3.5C6 3.5 6 2.75 6 2.75C6 2.5 6.5 2.5 6.5 2.75C6.5 2.75 6.5 3.5 6.5 3'
        '.5L7.25 3.5C7.25 3.5 7.25 2.75 7.25 2.75C7.25 2.5 7.75 2.5 7.75 2.75C7.75 2.75 7.75 3.5 7.75 3.5L8.5'
        ' 3.5C8.5 3.5 8.5 2.75 8.5 2.75C8.5 2.5 9 2.5 9 2.75C9 2.75 9 3.5 9 3.5L9.5 3.5zM9.5 5.5L9 5.5C9 5.5 '
        '9 4.75 9 4.75C9 4.5 8.5 4.5 8.5 4.75C8.5 4.75 8.5 5.5 8.5 5.5L7.75 5.5C7.75 5.5 7.75 4.75 7.75 4.75C'
        '7.75 4.5 7.25 4.5 7.25 4.75C7.25 4.75 7.25 5.5 7.25 5.5L6.5 5.5C6.5 5.5 6.5 4.75 6.5 4.75C6.5 4.5 6 '
        '4.5 6 4.75C6 4.75 6 5.5 6 5.5L5.25 5.5C5.25 5.5 5.25 4.75 5.25 4.75C5.25 4.5 4.75 4.5 4.75 4.75C4.75'
        ' 4.75 4.75 5.5 4.75 5.5L4.5 5.5L4.5 5.75L9.5 5.75L9.5 5.5zM9.5 7.5L9 7.5C9 7.5 9 6.75 9 6.75C9 6.5 8'
        '.5 6.5 8.5 6.75C8.5 6.75 8.5 7.5 8.5 7.5L7.75 7.5C7.75 7.5 7.75 6.75 7.75 6.75C7.75 6.5 7.25 6.5 7.2'
        '5 6.75C7.25 6.75 7.25 7.5 7.25 7.5L6.5 7.5C6.5 7.5 6.5 6.75 6.5 6.75C6.5 6.5 6 6.5 6 6.75C6 6.75 6 7'
        '.5 6 7.5L5.25 7.5C5.25 7.5 5.25 6.75 5.25 6.75C5.25 6.5 4.75 6.5 4.75 6.75C4.75 6.75 4.75 7.5 4.75 7'
        '.5L4.5 7.5L4.5 7.75L9.5 7.75L9.5 7.5zM9.5 9.5L9 9.5C9 9.5 9 8.75 9 8.75C9 8.5 8.5 8.5 8.5 8.75C8.5 8'
        '.75 8.5 9.5 8.5 9.5L7.75 9.5C7.75 9.5 7.75 8.75 7.75 8.75C7.75 8.5 7.25 8.5 7.25 8.75C7.25 8.75 7.25'
        ' 9.5 7.25 9.5L6.5 9.5C6.5 9.5 6.5 8.75 6.5 8.75C6.5 8.5 6 8.5 6 8.75C6 8.75 6 9.5 6 9.5L5.25 9.5C5.2'
        '5 9.5 5.25 8.75 5.25 8.75C5.25 8.5 4.75 8.5 4.75 8.75C4.75 8.75 4.75 9.5 4.75 9.5L4.5 9.5L4.5 9.75L9'
        '.5 9.75L9.5 9.5z',
        'M9.5 3.4L9.5 3.75L4.5 3.75L4.5 3.4L4.75 3.4C4.75 3.4 4.75 2.75 4.75 2.75C4.75 2.5 5.25 2.5 5.25 2.75'
        'C5.25 2.75 5.25 3.4 5.25 3.4L6 3.4C6 3.4 6 2.75 6 2.75C6 2.5 6.5 2.5 6.5 2.75C6.5 2.75 6.5 3.4 6.5 3'
        '.4L7.25 3.4C7.25 3.4 7.25 2.75 7.25 2.75C7.25 2.5 7.75 2.5 7.75 2.75C7.75 2.75 7.75 3.4 7.75 3.4L8.5'
        ' 3.4C8.5 3.4 8.5 2.75 8.5 2.75C8.5 2.5 9 2.5 9 2.75C9 2.75 9 3.4 9 3.4L9.5 3.4zM9.5 5.4L9 5.4C9 5.4 '
        '9 4.75 9 4.75C9 4.5 8.5 4.5 8.5 4.75C8.5 4.75 8.5 5.4 8.5 5.4L7.75 5.4C7.75 5.4 7.75 4.75 7.75 4.75C'
        '7.75 4.5 7.25 4.5 7.25 4.75C7.25 4.75 7.25 5.4 7.25 5.4L6.5 5.4C6.5 5.4 6.5 4.75 6.5 4.75C6.5 4.5 6 '
        '4.5 6 4.75C6 4.75 6 5.4 6 5.4L5.25 5.4C5.25 5.4 5.25 4.75 5.25 4.75C5.25 4.5 4.75 4.5 4.75 4.75C4.75'
        ' 4.75 4.75 5.4 4.75 5.4L4.5 5.4L4.5 5.75L9.5 5.75L9.5 5.4zM9.5 7.4L9 7.4C9 7.4 9 6.75 9 6.75C9 6.5 8'
        '.5 6.5 8.5 6.75C8.5 6.75 8.5 7.4 8.5 7.4L7.75 7.4C7.75 7.4 7.75 6.75 7.75 6.75C7.75 6.5 7.25 6.5 7.2'
        '5 6.75C7.25 6.75 7.25 7.4 7.25 7.4L6.5 7.4C6.5 7.4 6.5 6.75 6.5 6.75C6.5 6.5 6 6.5 6 6.75C6 6.75 6 7'
        '.4 6 7.4L5.25 7.4C5.25 7.4 5.25 6.75 5.25 6.75C5.25 6.5 4.75 6.5 4.75 6.75C4.75 6.75 4.75 7.4 4.75 7'
        '.4L4.5 7.4L4.5 7.75L9.5 7.75L9.5 7.4zM9.5 9.4L9 9.4C9 9.4 9 8.75 9 8.75C9 8.5 8.5 8.5 8.5 8.75C8.5 8'
        '.75 8.5 9.4 8.5 9.4L7.75 9.4C7.75 9.4 7.75 8.75 7.75 8.75C7.75 8.5 7.25 8.5 7.25 8.75C7.25 8.75 7.25'
        ' 9.4 7.25 9.4L6.5 9.4C6.5 9.4 6.5 8.75 6.5 8.75C6.5 8.5 6 8.5 6 8.75C6 8.75 6 9.4 6 9.4L5.25 9.4C5.2'
        '5 9.4 5.25 8.75 5.25 8.75C5.25 8.5 4.75 8.5 4.75 8.75C4.75 8.75 4.75 9.4 4.75 9.4L4.5 9.4L4.5 9.75L9'
        '.5 9.75L9.5 9.4z',
    )],
}
# ICON_FRAMES: a drawing that sits off its frame's middle, so off the middle of a pin's disc and of a row's tile: its frame
# moved by (dx, dy) onto it, how far the drawing sat right and down of the middle, by the mean of how far its box and its
# weight sat off, to a quarter unit (the sets draw every icon in a frame from 0 0). Atlas's own icons are mended where
# they are drawn, in custom_icons.txt (the stairs), and the boards' in board_symbol_names.FRAMES.
ICON_FRAMES = {
    'maki-entrance': (.75, 0),  # the escalator and its rider stood right
    'maki-globe': (-.75, 0),  # the stand's arc swung the globe left
    'maki-music': (-.5, -.75),  # the notes hung up and left
    'maki-racetrack-horse': (0, -.75),  # the horse and rider rode high
    'maki-restaurant-pizza': (-1, -1),  # the slice lay up and left
}
