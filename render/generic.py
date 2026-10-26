"""Generic looks for buildings whose colour or material is not mapped: real OSM values (building:material, building:colour, roof:material,
roof:colour), chosen from what is mapped about the building itself (its building=* type, use tags, age, height, roof shape) and the land use
it stands in, for the country it is in. Every value set here is marked guessed, with the reason, and can be filled in.
The tag value is one plain colour per kind of wall or roof; the drawing varies it slightly per building, as weathering would."""
import random, re, math, collections, json, os
from real import num, signed_area, centroid, pip

HOME = {'house', 'detached', 'semidetached_house', 'terrace', 'bungalow', 'residential', 'apartments', 'dormitory', 'farm'}
KINDS = [  # (kind, building values, extra tag tests)
    ('worship', {'church', 'chapel', 'cathedral', 'mosque', 'synagogue', 'temple', 'religious', 'shrine'}, lambda t: t.get('amenity') == 'place_of_worship'),
    ('terminal', {'terminal', 'airport_terminal', 'transportation', 'train_station'}, lambda t: t.get('aeroway') == 'terminal' or t.get('public_transport') == 'station'),
    ('tower', {'tower'}, lambda t: t.get('man_made') == 'tower' or t.get('aeroway') == 'control_tower'),
    ('tank', {'storage_tank', 'silo'}, lambda t: t.get('man_made') in ('storage_tank', 'silo')),
    ('hangar', {'hangar'}, lambda t: False),
    ('greenhouse', {'greenhouse', 'glasshouse'}, lambda t: False),
    ('parking', {'parking'}, lambda t: t.get('amenity') == 'parking'),
    ('bunker', {'bunker'}, lambda t: t.get('military') == 'bunker'),
    ('canopy', {'roof', 'carport'}, lambda t: False),
    ('kiosk', {'kiosk'}, lambda t: False),
    ('caravan', {'static_caravan'}, lambda t: False),
    ('shed', {'shed', 'garage', 'garages', 'hut', 'cabin', 'farm_auxiliary', 'barn', 'stable', 'cowshed', 'boathouse'}, lambda t: False),
    ('utility', {'service', 'transformer_tower', 'substation', 'pumping_station'}, lambda t: t.get('power') == 'substation'),
    ('industrial', {'industrial', 'warehouse', 'factory', 'manufacture', 'storage', 'depot'}, lambda t: bool(t.get('industrial'))),
    ('school', {'school', 'university', 'college', 'kindergarten'}, lambda t: t.get('amenity') in ('school', 'university', 'college', 'kindergarten')),
    ('hospital', {'hospital'}, lambda t: t.get('amenity') in ('hospital', 'clinic')),
    ('hotel', {'hotel'}, lambda t: t.get('tourism') == 'hotel'),
    ('sports', {'sports_hall', 'sports_centre', 'stadium', 'grandstand', 'riding_hall'}, lambda t: t.get('leisure') in ('sports_centre', 'sports_hall', 'stadium')),
    ('retail', {'retail', 'supermarket', 'commercial_retail'}, lambda t: bool(t.get('shop'))),
    ('office', {'office', 'commercial', 'government', 'civic', 'public', 'townhall', 'bank', 'courthouse', 'police', 'fire_station'}, lambda t: bool(t.get('office'))),
    ('monument', {'castle', 'palace', 'museum'}, lambda t: t.get('tourism') == 'museum' or t.get('historic') in ('castle', 'palace', 'building', 'monument')),
    ('home', HOME, lambda t: False),
]
LANDUSE_KIND = {'industrial': 'industrial', 'port': 'industrial', 'railway': 'industrial', 'commercial': 'office', 'retail': 'retail',
                'residential': 'home', 'farmyard': 'shed', 'education': 'school', 'religious': 'worship', 'military': 'bunker', 'static_caravan': 'caravan'}

# one plain value per kind of wall and roof (as building:colour / roof:colour would be mapped)
WALL = {
    'brick_old': ('brick', '#74402F'), 'brick': ('brick', '#8E5A46'), 'brick_light': ('brick', '#A27A62'), 'brick_yellow': ('brick', '#C2A274'),
    'limestone': ('limestone', '#D8CCB2'), 'plaster': ('plaster', '#E6DFD1'), 'concrete': ('concrete', '#BEBAB1'), 'concrete_dark': ('concrete', '#A3A09A'),
    'glass': ('glass', '#93ABBC'), 'metal_silver': ('metal', '#C3C7CA'), 'metal_grey': ('metal', '#9AA1A7'), 'metal_white': ('metal', '#E1E2DF'),
    'metal_anthracite': ('metal', '#5B6066'), 'wood': ('wood', '#7A624C'), 'metal_green': ('metal', '#4C5E52'),
}
ROOF = {
    'tiles_red': ('roof_tiles', '#9E5038'), 'tiles_dark': ('roof_tiles', '#3F4246'), 'slate': ('slate', '#535C66'), 'zinc': ('metal', '#737E88'),
    'lead': ('metal', '#7A8187'), 'metal_light': ('metal', '#A4A9AD'), 'metal_grey': ('metal', '#8F969B'), 'bitumen': ('tar_paper', '#56585C'),
    'gravel': ('gravel', '#9C988F'), 'concrete': ('concrete', '#A7A49E'), 'glass': ('glass', '#C3D3DA'),
}
# Paris stone is not one tone: the guessed building:colour of an unmapped limestone front is one of these, per building
STONE_FR = ['#DCD0B6', '#D3C4A6', '#E2D8C3', '#CDC1A8', '#D8C8AA', '#C8BCA3', '#E0D2B5', '#D6CCB9']
NO_WIN = {'industrial', 'hangar', 'tank', 'canopy', 'shed', 'parking', 'greenhouse', 'bunker', 'caravan', 'kiosk', 'terminal'}
SLOPED = ('gabled', 'hipped', 'pyramidal', 'skillion', 'gambrel', 'mansard', 'half-hipped', 'round', 'dome', 'onion', 'saltbox')


def kind_of(t, landuse, region, foot=0.0, h=None):
    bt = t.get('building') or t.get('building:part') or 'yes'
    for k, vals, test in KINDS:
        if bt in vals or test(t): return k, ('building=%s' % bt if bt in vals else next(('%s=%s' % (a, t[a]) for a in ('amenity', 'aeroway', 'man_made', 'tourism', 'office', 'shop', 'historic', 'industrial', 'power', 'leisure', 'public_transport', 'military') if t.get(a)), 'its tags'))
    if landuse in LANDUSE_KIND: return LANDUSE_KIND[landuse], 'landuse=%s around it' % landuse
    if foot > 2500 and (h or 0) < 20: return 'industrial', 'its large, low footprint'
    default = {'paris': 'home', 'port': 'industrial', 'schiphol': 'office', 'dunes': 'home'}.get(region, 'home')
    return default, 'what most buildings here are'


def look(b, region, landuse=None):
    """(wall key, roof key, kind, reason) for one building; FR for Paris, NL elsewhere."""
    t = b['tags']; yr = b.get('year'); h = b['h'] or 0
    lv = num(t.get('building:levels')) or (h / 3.1 if h else 2)
    kind, why = kind_of(t, landuse, region, abs(signed_area(b['poly'])), b['h'])
    if kind == 'tower' and (t.get('tower:type') in ('bell_tower', 'church') or t.get('historic') or t.get('heritage') or (yr and yr < 1900)):
        kind = 'worship' if t.get('tower:type') == 'bell_tower' or t.get('building:part') == 'church' else 'monument'
    sloped = (b.get('roof') or t.get('roof:shape')) in SLOPED
    shape = t.get('roof:shape')
    tall = h >= 28 or lv >= 9
    fr = region == 'paris'
    if fr:
        if kind in ('worship', 'monument'): wall, roof = 'limestone', 'lead' if kind == 'worship' else 'slate'
        elif kind == 'home': wall = 'concrete' if yr and yr >= 1950 else ('plaster' if yr and yr < 1830 else 'limestone'); roof = 'zinc' if not (yr and yr >= 1950) else 'gravel'
        elif kind in ('office', 'hotel', 'hospital'): wall = 'glass' if yr and yr >= 1965 else 'limestone'; roof = 'zinc' if wall == 'limestone' else 'gravel'
        elif kind == 'retail': wall = 'glass' if (yr and yr >= 1960) or lv <= 2 else 'limestone'; roof = 'glass' if lv <= 2 else 'zinc'
        elif kind == 'school': wall, roof = 'brick_light', 'slate'
        elif kind == 'kiosk': wall, roof = 'metal_green', 'zinc'
        elif kind == 'canopy': wall, roof = 'metal_grey', 'glass'
        elif kind in ('industrial', 'utility', 'shed'): wall, roof = 'plaster', 'zinc'
        else: wall, roof = 'limestone', 'zinc'
        if shape in ('gabled', 'hipped') and roof == 'zinc' and yr and yr < 1800: roof = 'slate'
    else:
        old = yr is not None and yr < 1940; post = yr is not None and yr >= 1975
        if kind == 'home':
            if t.get('building') == 'apartments' and (lv >= 5 or (yr and 1955 <= yr < 1985)): wall = 'concrete' if not post else 'brick_light'
            else: wall = 'brick_old' if old else ('brick_light' if post else 'brick')
            roof = ('tiles_red' if old or not post else 'tiles_dark') if sloped else 'bitumen'
        elif kind == 'worship': wall, roof = 'brick_old', 'slate'
        elif kind == 'monument': wall, roof = 'brick_old', 'slate' if sloped else 'bitumen'
        elif kind in ('office', 'hotel', 'hospital'):
            if yr and yr < 1960: wall = 'brick_old' if old else 'brick'          # an office of its era, before curtain walls
            else: wall = 'glass' if (tall or (post and lv >= 4) or (yr is None and lv >= 6) or (region == 'schiphol' and h >= 12)) else ('brick' if (not post and region != 'schiphol') else 'concrete')
            roof = 'gravel' if not sloped else 'tiles_dark'
        elif kind == 'retail': wall, roof = ('concrete' if not old else 'brick'), 'bitumen'
        elif kind == 'school': wall, roof = 'brick_light' if not old else 'brick', 'bitumen' if not sloped else 'tiles_red'
        elif kind == 'terminal': wall, roof = 'glass', 'metal_light'
        elif kind == 'hangar': wall, roof = 'metal_silver', 'metal_light'
        elif kind == 'industrial': wall, roof = ('metal_grey' if b['id'] % 3 else 'metal_anthracite') if not old else 'brick', 'metal_light' if not old else 'tiles_red'
        elif kind == 'tank': wall, roof = 'metal_white', 'metal_light'
        elif kind == 'tower': wall, roof = 'concrete', 'concrete'
        elif kind == 'parking': wall, roof = 'concrete_dark', 'concrete'
        elif kind == 'greenhouse': wall, roof = 'glass', 'glass'
        elif kind == 'bunker': wall, roof = 'concrete_dark', 'concrete'
        elif kind == 'caravan': wall, roof = 'metal_white', 'metal_light'
        elif kind == 'kiosk': wall, roof = 'wood', 'bitumen'
        elif kind == 'canopy': wall, roof = 'metal_grey', 'metal_grey'
        elif kind == 'shed': wall, roof = 'wood', ('tiles_dark' if sloped else 'bitumen')
        elif kind == 'utility': wall, roof = 'brick', 'bitumen' if not sloped else 'tiles_red'
        elif kind == 'sports': wall, roof = 'metal_grey', 'metal_light'
        else: wall, roof = 'brick', 'bitumen'
    return wall, roof, kind, why


def jitter(hexcol, seed, k=0.05):
    """The drawn colour: the guessed value, a little lighter or darker per building."""
    r = random.Random(seed); f = 1 + r.uniform(-k, k); w = r.uniform(-k, k) * 0.4
    c = [int(hexcol[i:i + 2], 16) for i in (1, 3, 5)]
    c = [min(255, max(0, int(v * f + (12 * w if i == 0 else -8 * w if i == 2 else 0)))) for i, v in enumerate(c)]
    return '#%02X%02X%02X' % tuple(c)


# a mapped material with no mapped colour is drawn as the renderer always drew it (amsterdam.MATERIAL)
MAT_DRAW = {'brick': '#9C5A45', 'stone': '#CFC6B4', 'copper': '#5E9E86', 'glass': '#A9C2D6', 'concrete': '#BDBAB2', 'wood': '#9C7A55',
            'roof_tiles': '#A5553C', 'slate': '#56606A', 'metal': '#9AA3AB', 'plaster': '#E6E0D4', 'sandstone': '#D8C49A'}
MAT_COL = {'brick': '#8E5A46', 'limestone': '#D8CCB2', 'sandstone': '#D2BE94', 'stone': '#C9C0AE', 'plaster': '#E6DFD1', 'concrete': '#BEBAB1',
           'glass': '#93ABBC', 'mirrored_glass': '#8EA6B8', 'metal': '#A9AFB4', 'steel': '#A9AFB4', 'wood': '#7A624C', 'timber_framing': '#D9D0BE', 'cement_block': '#B3AFA6'}
RMAT_COL = {'roof_tiles': '#9E5038', 'slate': '#535C66', 'metal': '#8F969B', 'tar_paper': '#56585C', 'gravel': '#A9A59D', 'concrete': '#A7A49E',
            'glass': '#C3D3DA', 'copper': '#5E9E86', 'grass': '#7C9A5A', 'plants': '#7C9A5A', 'thatch': '#8E7A52', 'eternit': '#8C8A86', 'zinc': '#8C969E', 'lead': '#7A8187'}


# colour words as OSM mappers write them (CSS names), to the colour they mean
NAMED = {'white': '#FFFFFF', 'black': '#2A2B2E', 'grey': '#808080', 'gray': '#808080', 'lightgrey': '#D3D3D3', 'lightgray': '#D3D3D3', 'darkgrey': '#A9A9A9',
         'darkgray': '#A9A9A9', 'dimgray': '#696969', 'dimgrey': '#696969', 'silver': '#C0C0C0', 'gainsboro': '#DCDCDC', 'whitesmoke': '#F5F5F5', 'slategray': '#708090',
         'slategrey': '#708090', 'red': '#B03A2E', 'darkred': '#8B0000', 'firebrick': '#B22222', 'indianred': '#CD5C5C', 'brown': '#8B5A3C', 'maroon': '#800000',
         'saddlebrown': '#8B4513', 'sienna': '#A0522D', 'peru': '#CD853F', 'sandybrown': '#F4A460', 'burlywood': '#DEB887', 'tan': '#D2B48C', 'wheat': '#F5DEB3',
         'beige': '#E8E0C8', 'ivory': '#FFFFF0', 'linen': '#FAF0E6', 'antiquewhite': '#FAEBD7', 'cream': '#F3EBD3', 'khaki': '#C3B091', 'yellow': '#E6C840',
         'gold': '#D4AF37', 'orange': '#E07B39', 'coral': '#FF7F50', 'salmon': '#FA8072', 'tomato': '#E5533C', 'pink': '#E8A0B0', 'rosybrown': '#BC8F8F',
         'green': '#4F7A3A', 'darkgreen': '#2F5233', 'olive': '#808000', 'blue': '#3A5F9A', 'navy': '#1F2E5A', 'lightblue': '#ADD8E6', 'skyblue': '#87CEEB',
         'teal': '#2F7F7F', 'purple': '#6A4A8A', 'darkslategray': '#2F4F4F', 'darkslategrey': '#2F4F4F', 'mahogany': '#7B3A2A', 'burgundy': '#7A2533',
         'ochre': '#C8913F', 'lightyellow': '#F2E8B6', 'lightgreen': '#B5D3A8', 'lightpink': '#EBCBCB', 'pink': '#E8B4BE', 'terracotta': '#B5643F'}

def paint(v, k=0.16):
    """A colour word as the paint on a wall: the CSS colour a little toward white, as house paint is (hex values stay exact)."""
    w = (v or '').strip().lower().replace(' ', '').replace('_', '')
    h = colour_hex(v)
    if not h or w.startswith('#') or w in ('white', 'black'): return h
    c = [int(h[i:i + 2], 16) for i in (1, 3, 5)]; t = (244, 240, 232)
    return '#%02X%02X%02X' % tuple(int(c[i] + (t[i] - c[i]) * k) for i in range(3))


def colour_hex(v):
    v = (v or '').strip().lower().replace(' ', '').replace('_', '')
    if re.fullmatch(r'#[0-9a-f]{6}', v): return v.upper()
    if re.fullmatch(r'#[0-9a-f]{3}', v): return '#' + ''.join(c * 2 for c in v[1:]).upper()
    return NAMED.get(v)


def neighbour_levels(S, r=80.0):
    """Neither height nor building:levels mapped: the median levels of the mapped buildings within r metres (guessed), where there are three or more."""
    grid = collections.defaultdict(list)
    for b in S['buildings']:
        t_ = b['tags']
        if t_.get('man_made') == 'tower' or t_.get('building') in ('tower', 'church', 'cathedral', 'chapel', 'bell_tower', 'campanile', 'transformer_tower'): continue     # not like a house
        if b.get('src') in ('levels', 'height') and b.get('levels', 0) >= 1:
            x, z = centroid(b['poly']); grid[(int(x // r), int(z // r))].append((x, z, b['levels']))
    n = 0
    for b in S['buildings']:
        if b.get('src') or not b.get('guessed', {}).get('height') or b['tags'].get('building') in ('roof', 'kiosk', 'shed', 'garage', 'hut', 'houseboat'): continue
        x, z = centroid(b['poly']); i, j = int(x // r), int(z // r)
        near = sorted(lv for di in (-1, 0, 1) for dj in (-1, 0, 1) for (x2, z2, lv) in grid[(i + di, j + dj)] if math.hypot(x2 - x, z2 - z) < r)
        if len(near) < 3: continue
        lv = near[len(near) // 2]
        b['levels'] = lv; b['h'] = lv * 3.0 + (b.get('roof_h') or 0); b['guessed']['building:levels'] = True; b['lv_why'] = 'the buildings around it'; n += 1
    return n


def paris_roof(b, kind, wall, g):
    """A Paris stone front of four to nine floors, built before 1940 or of unknown age, with no roof shape mapped: roof:shape=mansard (guessed),
    one roof storey of zinc with dormers, above its mapped levels."""
    t = b['tags']; yr = b.get('year')
    if 'building:part' in t or not g.get('roof:shape') or b.get('roof') not in (None, 'flat'): return False
    if kind not in ('home', 'office', 'hotel', 'retail', 'hospital', 'school', 'monument') or wall not in ('limestone', 'plaster'): return False
    if yr and not (1760 <= yr < 1940): return False
    if not (4 <= b.get('levels', 0) <= 9) or abs(signed_area(b['poly'])) < 80: return False
    rl = num(t.get('roof:levels'))
    b['roof'] = 'mansard'; b['roof_h'] = rl * 3.0 if rl else 3.4; g['roof:height'] = True
    if b.get('src') == 'height':
        if not t.get('building:levels'): b['levels'] = max(1, int(round((b['h'] - b['roof_h']) / 3.1)))
    else: b['h'] = b['levels'] * 3.0 + b['roof_h']
    return True


# ------------------------------------------------------------------ building traditions
# A tradition is a short data file (render/traditions/<id>.json): the walls, roofs, windows and details a building culture typically has, by kind
# and era. Every value it gives is a real OSM value, guessed and marked; what is mapped always wins. Details with no tag (shutters, chimneys,
# eaves, timber boards) are generic details of the tradition, drawn as windows are.
TRAD_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'traditions')
INDUSTRIAL_LIKE = {'industrial', 'hangar', 'tank', 'canopy', 'parking', 'greenhouse', 'bunker', 'caravan', 'kiosk', 'terminal', 'utility', 'sports'}
SAYS_KIND = {'home', 'office', 'hotel', 'retail', 'school'}


def tradition(tid):
    return json.load(open(os.path.join(TRAD_DIR, tid + '.json'))) if tid else None


def pick(options, seed):
    """One of [value..., weight] by weight, the same every time for the same building."""
    r = random.Random(seed).uniform(0, sum(o[-1] for o in options)); acc = 0
    for o in options:
        acc += o[-1]
        if r <= acc: return o[:-1] if len(o) > 2 else o[0]
    return options[-1][:-1] if len(options[-1]) > 2 else options[-1][0]


def era(entry, yr):
    """An entry that differs by age: {"before:1830": ..., "after:1950": ..., "default": ...}, the first that fits; a list or plain entry as it is."""
    if not isinstance(entry, dict) or 'shapes' in entry or 'material' in entry: return entry
    for k, v in entry.items():
        if k.startswith('before:') and yr and yr < int(k[7:]): return v
        if k.startswith('after:') and yr and yr >= int(k[6:]): return v
    return entry.get('default')


def trad_entry(table, btype, kind, modern, yr=None):
    for k in ((['modern'] if modern else []) + [btype, kind, 'default']):
        if k in table:
            v = era(table[k], yr)
            if v is not None: return v
    return None


def fits(when, b, kind, md, short):
    """Does a rule's "when" hold for this building? kind, building, material, year {from, to, unknown}, levels [min, max], area [min, max], narrow, min_h."""
    t = b['tags']; yr = b.get('year'); bt = t.get('building') or 'yes'
    if 'kind' in when and kind not in when['kind']: return False
    if 'building' in when and bt not in when['building']: return False
    if 'material' in when and md not in when['material']: return False
    if 'year' in when:
        y = when['year']
        if yr is None:
            if not y.get('unknown', False): return False
        elif yr < y.get('from', -9999) or yr >= y.get('to', 9999): return False
    if 'levels' in when and not (when['levels'][0] <= b.get('levels', 0) <= when['levels'][1]): return False
    ar = abs(signed_area(b['poly']))
    if 'area' in when and not (when['area'][0] <= ar <= when['area'][1]): return False
    if 'narrow' in when and short >= when['narrow']: return False
    if 'min_h' in when and (b.get('h') or 0) <= when['min_h']: return False
    return True


def trad_look(b, P, kind, why, g, region, lw=None):
    """The tradition's look for one building whose walls or roof are not mapped. False where the tradition says nothing about its kind.
    lw: the colours learned for it from its mapped neighbours (learned()), drawn in place of the tradition's."""
    t = b['tags']; bt = t.get('building') or t.get('building:part') or 'yes'; yr = b.get('year')
    modern = bool(yr and yr >= P.get('modern_from', 1950))
    seed = b.get('parent_id', b['id'])
    walls = trad_entry(P['walls'], bt, kind, modern, yr)
    if walls is None or (kind in INDUSTRIAL_LIKE and kind not in P['walls'] and bt not in P['walls']): return False
    part = 'building:part' in t
    poly = b['poly']; n = len(poly)
    edges = sorted(math.dist(poly[i], poly[(i + 1) % n]) for i in range(n))
    short = min(edges[-1], max(3.0, abs(signed_area(poly)) / max(edges[-1], 1)))       # the footprint's width across its longest side
    # height: the tradition's usual number of floors, where neither height nor levels is mapped and no mapped neighbours say otherwise
    if g.get('height') and not b.get('lv_why') and not part:
        if kind in P.get('heights', {}) and (kind != 'tower' or abs(signed_area(poly)) <= 160):          # a tower: its typical height in this tradition, as a guess
            b['h'] = P['heights'][kind]; b['levels'] = max(1, int(b['h'] / P['floor_h'])); b['lv_why'] = 'its tradition'
        else:
            lv = P['levels'].get(bt, P['levels'].get(kind, P['levels']['default']))
            b['levels'] = lv; b['h'] = lv * P['floor_h'] + (b.get('roof_h') or 0); g['building:levels'] = True; b['lv_why'] = 'its tradition'
    mat, col = t.get('building:material'), t.get('building:colour')
    rmat, rcol = t.get('roof:material'), t.get('roof:colour')
    lwall, lroof = (lw or {}).get('building:colour'), (lw or {}).get('roof:colour')      # learned palettes: stand-ins for the unmapped colours
    wm, wc = pick(walls, seed * 13 + 3)
    md = mat or (lwall or {}).get('material') or wm
    # roof: a rule of the tradition (a Paris mansard, a canal house's gable), else a shape from its roofs, with its pitch
    rf = trad_entry(P['roof'], bt, kind, modern, yr) or era(P['roof']['default'], yr)
    rule = None
    if g.get('roof:shape') and not part:
        for r_ in P.get('rules', []):
            if fits(r_['when'], b, kind, md, short): rule = r_['roof']; break
        if rule: rf = dict(rf, **{k: v for k, v in rule.items() if k in ('material', 'colours', 'pitch')})
        shape = rule['shape'] if rule else pick(rf['shapes'], seed * 7 + 1)
        old_rh = b.get('roof_h') or 0
        b['roof'] = shape
        if shape in ('gabled', 'hipped'):
            lo, hi = (rule or {}).get('roof_h_range', [1.4, 7.0])
            b['roof_h'] = round(max(lo, min(hi, rf.get('pitch', 0.5) * short / 2)), 1); g['roof:height'] = True
            if (rule or {}).get('orient'): b['orient'] = rule['orient']
            elif rf.get('terrace_across') and short < 8 and edges[-1] > 1.4 * short: b['orient'] = 'across'; g['roof:orientation'] = True   # ridge along the street
            else: b['orient'] = b.get('orient') or 'along'
        elif shape == 'skillion':
            b['roof_h'] = round(max(0.8, min(3.0, rf.get('pitch', 0.3) * short * 0.5)), 1); g['roof:height'] = True; g['roof:direction'] = True
        elif shape == 'mansard':
            rl = num(t.get('roof:levels')); b['roof_h'] = rl * P['floor_h'] if rl else (rule or {}).get('roof_h', 3.4); g['roof:height'] = True
        else: b['roof_h'] = None
        rh = b.get('roof_h') or 0
        if b.get('src') == 'height':
            if not t.get('building:levels'): b['levels'] = max(1, int(round((b['h'] - rh) / P['floor_h'])))
        else: b['h'] = b['levels'] * P['floor_h'] + rh if g.get('height') or b.get('src') == 'levels' else b['h'] - old_rh + rh
    rm = rf['material']; rc = random.Random(seed * 17 + 5).choice(rf['colours'])
    b['kind'] = kind
    b['mat_draw'] = md
    if not mat: g['building:material'] = True
    if col:
        b['col'] = col; ch = paint(col); b['col_draw'] = ch or (MAT_DRAW.get(mat) or MAT_COL.get(mat or wm, wc))
    elif lwall:
        b['col'] = lwall['value']; b['col_draw'] = jitter(paint(lwall['value']), b['id'], 0.035); g['building:colour'] = True
    elif mat:
        b['col'] = MAT_DRAW.get(mat) or MAT_COL.get(mat, wc); b['col_draw'] = b['col']; g['building:colour'] = True
    else:
        b['col'] = wc; b['col_draw'] = jitter(wc, b['id'], 0.035); g['building:colour'] = True
    b['rmat_draw'] = rmat or (lroof or {}).get('material') or rm
    if not rmat: g['roof:material'] = True
    if rcol:
        b['rcol'] = rcol; ch = colour_hex(rcol); b['rcol_draw'] = ch or (MAT_DRAW.get(rmat) or RMAT_COL.get(rmat or rm, rc))
    elif lroof:
        b['rcol'] = lroof['value']; b['rcol_draw'] = jitter(colour_hex(lroof['value']), b['id'] + 7, 0.05); g['roof:colour'] = True
    elif rmat:
        b['rcol'] = MAT_DRAW.get(rmat) or RMAT_COL.get(rmat, rc); b['rcol_draw'] = b['rcol']; g['roof:colour'] = True
    else:
        b['rcol'] = rc; b['rcol_draw'] = jitter(rc, b['id'] + 7, 0.05); g['roof:colour'] = True
    # the tradition's details, generic (no tag gives them): windows, shutters, eaves, boards, a stucco ground floor, a cornice, chimneys
    rnd = random.Random(seed * 31 + 11)
    W_ = P.get('windows'); Sh = P.get('shutters'); Bd = P.get('boards'); BB = P.get('base_band'); Ch = P.get('chimneys')
    homeish = kind in SAYS_KIND or bt in ('apartments', 'house', 'terrace', 'residential', 'detached', 'semidetached_house')
    def before(v):           # a detail of old buildings only: {"before": 1800} (unknown age counts as not old), or simply true
        return bool(v) if not isinstance(v, dict) else bool(yr and yr < v['before'])
    win = W_ if (W_ and not modern and (kind in W_['kinds'] or bt in W_['kinds'] or 'default' in W_['kinds']) and kind not in INDUSTRIAL_LIKE
                 and ('materials' not in W_ or md in W_['materials'])) else None
    b['trad'] = dict(
        win=win,
        shutter=(rnd.choice(Sh['colours']) if Sh and not modern and kind in Sh['kinds'] and rnd.random() < Sh['share'] else None),
        eaves=P.get('eaves', 0) if b.get('roof') in ('gabled', 'hipped') else 0,
        boards=Bd if Bd and md in Bd['materials'] else None,
        band=BB if BB and md in BB['materials'] and not modern and homeish else None,
        cornice=before(P.get('cornice')) and not modern and homeish,
        hoist=before(P.get('hoist')) and b.get('roof') == 'gabled',
        chimney=(Ch if Ch and not part and homeish and not modern and rnd.random() < Ch['share'] else None),
        crenel=bool(P.get('towers_crenellated')) and kind == 'tower')
    b['no_win'] = kind in NO_WIN or md == 'glass'
    label = P.get('says_by', {}).get(md, P['says']) if homeish else '%s in the %s tradition' % (kind_label(kind), P['name'])
    b['look_why'] = '%s · %s' % (label, why) if any(g.get(k) for k in ('building:material', 'building:colour', 'roof:material', 'roof:colour', 'roof:shape')) else None
    if lw: b['look_why'] += ' · ' + pal_why(lw)
    if rule and b.get('roof') == 'mansard': b['roof_why'] = 'mansard'
    return True


def party_walls(S, tol=0.8):
    """Walls shared with a neighbour (a terrace's, a Paris block's): edges lying along another building's edge. Chimney stacks stand on them."""
    cell = 12.0; grid = collections.defaultdict(list); bs = [b for b in S['buildings'] if not b.get('hidden')]
    for k, b in enumerate(bs):
        p = b['poly']
        for i in range(len(p)):
            a, c = p[i], p[(i + 1) % len(p)]
            if math.dist(a, c) < 1.5: continue
            m = ((a[0] + c[0]) / 2, (a[1] + c[1]) / 2); grid[(int(m[0] // cell), int(m[1] // cell))].append((k, a, c))
    for k, b in enumerate(bs):
        p = b['poly']; out = []
        for i in range(len(p)):
            a, c = p[i], p[(i + 1) % len(p)]; L = math.dist(a, c)
            if L < 1.5: continue
            m = ((a[0] + c[0]) / 2, (a[1] + c[1]) / 2); ux, uz = (c[0] - a[0]) / L, (c[1] - a[1]) / L
            gx, gz = int(m[0] // cell), int(m[1] // cell); hit = False
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    for (k2, a2, c2) in grid.get((gx + dx, gz + dz), ()):
                        if k2 == k: continue
                        L2 = math.dist(a2, c2); vx, vz = (c2[0] - a2[0]) / L2, (c2[1] - a2[1]) / L2
                        if abs(ux * vz - uz * vx) > 0.15: continue
                        tt = (m[0] - a2[0]) * vx + (m[1] - a2[1]) * vz
                        if -0.5 < tt < L2 + 0.5 and abs((m[0] - a2[0]) * vz - (m[1] - a2[1]) * vx) < tol: hit = True; break
                    if hit: break
                if hit: break
            if hit: out.append((a, c))
        b['party'] = out


# ------------------------------------------------------------------ learned palettes (a prototype, off unless ATLAS_PALETTES=1)
# An unmapped wall or roof takes its colour from the colours mapped on buildings of its own kind around it: PAL_MIN or more within PAL_R
# metres, else the region's, where PAL_REGION_MIN or more are spread over the region (PAL_REGION_SPREAD of the squares its kind stands in);
# else the look above stands. A part of a building whose other parts map the colour takes theirs first. A building whose material is
# mapped keeps to samples of that material. A mapped building gives each of its values once, its parts counted with it (a landmark
# modelled in thirty parts is one building). The pick is the building's own random stream, so the same data gives the same picks, and it
# is marked guessed with where it came from. The thresholds come from the survey (render/palettes.py).
PALETTES = os.environ.get('ATLAS_PALETTES') == '1'
PAL_R, PAL_MIN = 150.0, 5             # near: 5 or more within 150 m; from 5 on the learned colours beat today's look in every region surveyed
PAL_REGION_MIN, PAL_REGION_SPREAD = 35, 0.1      # the region: 35 or more of its kind (37 to 73 beat today's look from beyond 150 m in Paris, New York
                                                  # and Notting Hill; Bergen's 17 to 31 lost), standing in a tenth or more of the PAL_R squares its kind
                                                  # stands in (those 13 to 48%; the canal ring's 67 home roofs, 58 of them one sweep of red tiles, 2%)
WALL_MATS = set(MAT_COL) | set(MAT_DRAW); ROOF_MATS = set(RMAT_COL) | set(MAT_DRAW)       # materials the drawing knows


def mapped_colour(v):
    """A mapped colour as the renderer reads it (colour_hex): the value, or the first value of a list (white;red) that reads; else None."""
    for x in (v or '').split(';'):
        if colour_hex(x): return x.strip().lower()
    return None


def owners(S):
    """Each building and part's building, by id(): a part belongs to the outline holding its centroid (regions.py has it as parent_id)."""
    cell = 50.0; grid = collections.defaultdict(list); by_id = {}
    for b in S['buildings']:
        by_id.setdefault(b['id'], b)
        xs = [p[0] for p in b['poly']]; zs = [p[1] for p in b['poly']]; bb = (min(xs), max(xs), min(zs), max(zs))
        for i in range(int(bb[0] // cell), int(bb[1] // cell) + 1):
            for j in range(int(bb[2] // cell), int(bb[3] // cell) + 1): grid[(i, j)].append((b, bb))
    own = {id(b): b for b in S['buildings']}
    for p in S['parts']:
        o = by_id.get(p.get('parent_id'))
        if o is None:
            cx, cz = centroid(p['poly'])
            o = next((b for b, bb in grid.get((int(cx // cell), int(cz // cell)), ()) if bb[0] <= cx <= bb[1] and bb[2] <= cz <= bb[3] and pip(cx, cz, b['poly'])), p)
        own[id(p)] = o
    return own


def kind_at(b, region, landuse_at, P):
    """The kind apply settles on for a building (kind_of's, as a tradition reads it, else look()'s, where an old tower counts as a church or a
    monument), with a '?' where nothing about the building said it, only the region's default: those learn only from each other (a theme
    park's mapped building=yes pavilions are not the village's houses)."""
    lu = landuse_at(b) if landuse_at else None
    kind, why = kind_of(b['tags'], lu, region, abs(signed_area(b['poly'])), b['h']) if P else look(b, region, lu)[2:]
    return kind + '?' if why == 'what most buildings here are' else kind


def pal_why(lw):
    src = {'own': "its own building's mapped colours", 'near': '%d mapped neighbours of its kind', 'region': '%d mapped buildings of its kind in the region'}
    return ' · '.join('%s from %s' % ('walls' if k == 'building:colour' else 'roof', src[v['where']].replace('%d', str(v['n']))) for k, v in sorted(lw.items()))


def square(x, z): return (int(x // PAL_R), int(z // PAL_R))


def pal_pool(own_, near, region, squares):
    """The samples a colour is learned from, and where they stood: its own building's other parts ('own'), else PAL_MIN or more within PAL_R
    ('near'), else the region's where PAL_REGION_MIN or more stand in PAL_REGION_SPREAD of the squares its buildings of that kind stand in
    (squares, PAL_R across): a region mapped all over, not one street's sweep; else (None, None)."""
    if own_: return own_, 'own'
    if len(near) >= PAL_MIN: return near, 'near'
    if len(region) >= PAL_REGION_MIN and len({square(s[1], s[2]) for s in region}) >= PAL_REGION_SPREAD * squares: return region, 'region'
    return None, None


def learned(S, region, landuse_at=None, P=None):
    """The colours learned for every building and part whose wall or roof colour is not mapped, by id(): {'building:colour' or 'roof:colour':
    dict(value, material, n, where)}: n samples of its kind (and of its mapped material) on its own building's other parts ('own'), within PAL_R
    metres ('near') or in the region; material is the sample's, for a building whose own is not mapped. Kept in S['_pal'] with the samples."""
    own = owners(S); bs = list({id(o): o for o in own.values()}.values())        # the buildings, each once
    kind = {id(o): kind_at(o, region, landuse_at, P) for o in bs}
    squares = collections.defaultdict(set)          # the squares each kind's buildings stand in
    for o in bs: squares[kind[id(o)]].add(square(*centroid(o['poly'])))
    samples = {'building:colour': [], 'roof:colour': []}; seen = set()
    for b in S['buildings'] + S['parts']:
        t = b['tags']; o = own[id(b)]
        if t.get('building') == 'houseboat': continue
        for key, mkey in (('building:colour', 'building:material'), ('roof:colour', 'roof:material')):
            v = mapped_colour(t.get(key))
            if not v or (id(o), key, v, t.get(mkey)) in seen: continue
            seen.add((id(o), key, v, t.get(mkey)))
            x, z = centroid(o['poly']); samples[key].append((kind[id(o)], x, z, o['id'], t.get(mkey), v))
    grid = {key: collections.defaultdict(list) for key in samples}; mine = collections.defaultdict(list)
    for key, ss in samples.items():
        for s in ss: grid[key][square(s[1], s[2])].append(s); mine[(s[3], key)].append(s)
    memo = {}; out = {}
    for b in S['buildings'] + S['parts']:
        t = b['tags']; o = own[id(b)]; k = kind[id(o)]
        if t.get('building') == 'houseboat': continue
        for key, mkey, salt, mats in (('building:colour', 'building:material', 19, WALL_MATS), ('roof:colour', 'roof:material', 23, ROOF_MATS)):
            if t.get(key): continue
            m = t.get(mkey); ck = (id(o), key, m)
            if ck not in memo:
                cx, cz = centroid(o['poly']); i, j = square(cx, cz)
                def fit(s): return s[0] == k and s[3] != o['id'] and (m is None or s[4] == m)
                own_ = [s for s in mine[(o['id'], key)] if m is None or s[4] == m]        # a part of a building whose other parts map it: theirs
                near = [s for di in (-1, 0, 1) for dj in (-1, 0, 1) for s in grid[key].get((i + di, j + dj), ()) if fit(s) and math.hypot(s[1] - cx, s[2] - cz) <= PAL_R]
                pool, where = pal_pool(own_, near, [s for s in samples[key] if fit(s)] if len(near) < PAL_MIN and not own_ else [], len(squares[k]))
                if pool is None: memo[ck] = None
                else:
                    s = random.Random(o['id'] * salt + 7).choice(sorted(pool, key=lambda s: (s[3], s[5], s[4] or '')))
                    memo[ck] = dict(value=s[5], material=s[4] if m is None and s[4] in mats else None, n=len(pool), where=where)
            if memo[ck]: out.setdefault(id(b), {})[key] = memo[ck]
    S['_pal'] = dict(own=own, kind=kind, samples=samples, picks=out)
    return out


def apply(S, region, landuse_at=None, keep_homes=False):
    """Fill every building's missing look from its own tags; what was guessed, and why, is kept for the tap panel.
    keep_homes: homes keep the varied brick and plaster already guessed for them (Amsterdam's canal houses) and only gain a roof material."""
    P = tradition(S.get('tradition'))
    if region == 'paris' or P: neighbour_levels(S)
    if P and P.get('chimneys') and P['chimneys'].get('kind') == 'party': party_walls(S)
    L = learned(S, region, landuse_at, P) if PALETTES else {}
    for b in S['buildings'] + S['parts']:
        t = b['tags']; g = b.setdefault('guessed', {}); lw = L.get(id(b))
        if P and t.get('building') == 'houseboat': continue          # a houseboat keeps its own generic look (drawn as a boat)
        if keep_homes and not any(t.get(k) for k in ('building:material', 'building:colour')):
            lu = landuse_at(b) if landuse_at else None
            kind, why = kind_of(t, lu, region, abs(signed_area(b['poly'])), b['h'])
            townhouse = kind in ('office', 'retail', 'hotel', 'school') and ((b.get('year') or 9999) < 1900 or abs(signed_area(b['poly'])) < 300)
            if townhouse: why = 'an old town house, %s' % why       # a canal house used as a shop or office keeps the canal houses' brick
            if kind == 'home' or townhouse:
                b['kind'] = kind
                if not t.get('roof:material'):
                    b['rmat_draw'] = 'roof_tiles' if (b.get('roof') in SLOPED) else 'tar_paper'; g['roof:material'] = True
                else: b['rmat_draw'] = t['roof:material']
                b['look_why'] = '%s · %s' % (kind_label(kind), why)
                if lw:          # learned palettes: its mapped neighbours' colours over the varied brick guessed for it
                    lwall, lroof = lw.get('building:colour'), lw.get('roof:colour')
                    if lwall: b['col'] = lwall['value']; b['col_draw'] = jitter(colour_hex(lwall['value']), b['id']); b['mat_draw'] = lwall['material'] or b.get('mat_draw')
                    if lroof:
                        b['rcol'] = lroof['value']; b['rcol_draw'] = jitter(colour_hex(lroof['value']), b['id'] + 7, 0.06); g['roof:colour'] = True
                        if g.get('roof:material') and lroof['material']: b['rmat_draw'] = lroof['material']
                    b['look_why'] += ' · ' + pal_why(lw)
                continue
        for k in ('building:colour', 'building:material', 'roof:colour'): g.pop(k, None)     # this replaces the region-wide palette guess
        lu = landuse_at(b) if landuse_at else None
        if P:
            kind, why = kind_of(t, lu, region, abs(signed_area(b['poly'])), b['h'])
            if trad_look(b, P, kind, why, g, region, lw): continue
        wall, roof, kind, why = look(b, region, lu)
        wm, wc = WALL[wall]; rm, rc = ROOF[roof]
        if region == 'paris':
            if wall == 'limestone': wc = STONE_FR[b.get('parent_id', b['id']) % len(STONE_FR)]
            if paris_roof(b, kind, wall, g): roof = 'zinc'; rm, rc = ROOF[roof]
        mat, col = t.get('building:material'), t.get('building:colour')
        rmat, rcol = t.get('roof:material'), t.get('roof:colour')
        lwall, lroof = (lw or {}).get('building:colour'), (lw or {}).get('roof:colour')      # learned palettes: stand-ins for the unmapped colours
        b['kind'] = kind
        b['mat_draw'] = mat or (lwall or {}).get('material') or wm
        if not mat: g['building:material'] = True
        if col:                     # a mapped colour is drawn exactly as mapped
            b['col'] = col; ch = colour_hex(col)
            b['col_draw'] = ch or (MAT_DRAW.get(mat) or MAT_COL.get(mat or wm, wc))
        elif lwall:                 # a colour learned from its mapped neighbours: drawn as a mapped one, weathered as a guess
            b['col'] = lwall['value']; b['col_draw'] = jitter(colour_hex(lwall['value']), b['id']); g['building:colour'] = True
        elif mat:                   # a mapped material, colour not mapped: drawn as the renderer always drew that material
            b['col'] = MAT_DRAW.get(mat) or MAT_COL.get(mat, wc); b['col_draw'] = b['col']; g['building:colour'] = True
        else:
            b['col'] = wc; b['col_draw'] = jitter(b['col'], b['id']); g['building:colour'] = True
        b['rmat_draw'] = rmat or (lroof or {}).get('material') or rm
        if not rmat: g['roof:material'] = True
        if rcol:
            b['rcol'] = rcol; ch = colour_hex(rcol)
            b['rcol_draw'] = ch or (MAT_DRAW.get(rmat) or RMAT_COL.get(rmat or rm, rc))
        elif lroof:
            b['rcol'] = lroof['value']; b['rcol_draw'] = jitter(colour_hex(lroof['value']), b['id'] + 7, 0.06); g['roof:colour'] = True
        elif rmat:
            b['rcol'] = MAT_DRAW.get(rmat) or RMAT_COL.get(rmat, rc); b['rcol_draw'] = b['rcol']; g['roof:colour'] = True
        else:
            b['rcol'] = rc; b['rcol_draw'] = jitter(b['rcol'], b['id'] + 7, 0.06); g['roof:colour'] = True
        b['no_win'] = kind in NO_WIN or b['mat_draw'] == 'glass'      # halls, tanks and sheds show no window rows; glass walls are drawn as curtain walls
        b['look_why'] = '%s · %s' % (kind_label(kind), why) if (g.get('building:material') or g.get('building:colour') or g.get('roof:material') or g.get('roof:colour')) else None
        if lw: b['look_why'] += ' · ' + pal_why(lw)


def kind_label(k):
    return {'home': 'a home', 'worship': 'a place of worship', 'monument': 'a historic building', 'office': 'an office or public building', 'retail': 'a shop building',
            'school': 'a school', 'hospital': 'a hospital', 'hotel': 'a hotel', 'industrial': 'an industrial hall', 'hangar': 'a hangar', 'terminal': 'a terminal',
            'tower': 'a tower', 'tank': 'a storage tank', 'parking': 'a parking garage', 'greenhouse': 'a greenhouse', 'bunker': 'a bunker', 'canopy': 'a canopy',
            'kiosk': 'a kiosk', 'caravan': 'a static caravan', 'shed': 'a shed or garage', 'utility': 'a service building', 'sports': 'a sports hall'}.get(k, 'a building')
