#!/usr/bin/env python3
"""The living map: an OSM-shaped neighbourhood rendered with a real sun.

Every visible thing comes from a feature with OpenStreetMap-style tags:
buildings (levels, material, colour, roof shape), streets (surface, lanes,
cycleway, lit), trees (genus, leaf_cycle, height, crown), street furniture
(lamps, hydrants, benches, bike racks, bus stops, subway entrances) and
shops (opening_hours).  The renderer turns tags into geometry and light.

Coordinates: metres. x = east, z = south, y = up. North is -z.
"""
import math, random, json, sys, os, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from city import Camera, vnorm, vdot, vsub, vcross, hx, tohex, mix, mulc, clamp, P, area, hull2, ribbon, rect

LAT, LON = 40.717, -73.958

# ------------------------------------------------------------------ sun
def sun_position(t_utc):
    jd = (t_utc - datetime.datetime(2000, 1, 1, 12)).total_seconds() / 86400
    L = (280.46 + 0.9856474 * jd) % 360
    g = math.radians((357.528 + 0.9856003 * jd) % 360)
    lam = math.radians(L + 1.915 * math.sin(g) + 0.020 * math.sin(2 * g))
    eps = math.radians(23.439 - 0.0000004 * jd)
    ra = math.atan2(math.cos(eps) * math.sin(lam), math.cos(lam))
    dec = math.asin(math.sin(eps) * math.sin(lam))
    gmst = (280.46061837 + 360.98564736629 * jd) % 360
    ha = math.radians((gmst + LON) % 360) - ra
    la = math.radians(LAT)
    el = math.asin(math.sin(la) * math.sin(dec) + math.cos(la) * math.cos(dec) * math.cos(ha))
    az = math.atan2(-math.sin(ha), math.tan(dec) * math.cos(la) - math.sin(la) * math.cos(ha))
    return math.degrees(az) % 360, math.degrees(el)

def sun_vector(az, el):
    a, e = math.radians(az), math.radians(el)
    return vnorm((math.sin(a) * math.cos(e), math.sin(e), -math.cos(a) * math.cos(e)))

# ------------------------------------------------------------------ opening hours (tiny subset)
def open_at(oh, hhmm):
    if oh == '24/7': return True
    for part in oh.split(','):
        a, b = part.strip().split('-')
        ta = int(a[:2]) * 60 + int(a[3:]); tb = int(b[:2]) * 60 + int(b[3:])
        t = hhmm[0] * 60 + hhmm[1]
        if tb <= ta: tb += 24 * 60
        if ta <= t < tb or ta <= t + 24 * 60 < tb: return True
    return False

# ------------------------------------------------------------------ the neighbourhood, as OSM-shaped features
AV = {'Wythe Avenue': 0.0, 'Berry Street': 110.0}           # avenues run north-south (x = centre)
ST = {'North 8th Street': -90.0, 'North 7th Street': 0.0, 'North 6th Street': 90.0}   # streets run east-west (z = centre)
ROAD_HALF_AV, WALK_AV = 7.5, 4.0
ROAD_HALF_ST, WALK_ST = 5.5, 3.5
X_MIN, X_MAX, Z_MIN, Z_MAX = -130.0, 250.0, -200.0, 170.0

BRICKS = ['#A4523B', '#8C4634', '#B5634A', '#7E3E2E', '#9A5A43', '#6F4A3A', '#C98E6E', '#D6C3A1', '#B9A58A']
CARS = ['#D8DDE4', '#3A3F46', '#B8322A', '#2E5BFF', '#F4F4F2', '#6B7282', '#1E3A5F', '#C9A36A', '#E8E8E4', '#2A2D33', '#4F7A5A']

def yard_tags(rg):
    r = rg.random()
    if r < 0.55: return {'leisure': 'garden', 'surface': 'grass'}
    if r < 0.8: return {'surface': 'paving_stones'}
    return {'surface': 'wood'}

def build_scene(seed=4):
    rg = random.Random(seed)
    F = []   # features
    def add(kind, geom, **tags):
        F.append({'kind': kind, 'geom': geom, 'tags': tags}); return F[-1]
    # ---- streets
    for name, x in AV.items():
        add('road', ('ns', x, ROAD_HALF_AV, WALK_AV), highway='secondary' if x == 0 else 'residential', name=name,
            surface='asphalt', lanes='2', sidewalk='both', lit='yes',
            cycleway_right='lane' if x == 0 else 'no', **{'parking:lane:both': 'parallel', 'maxspeed': '25 mph'})
    for name, z in ST.items():
        add('road', ('ew', z, ROAD_HALF_ST, WALK_ST), highway='residential', name=name,
            surface='sett' if name == 'North 7th Street' else 'asphalt', lanes='1', oneway='yes', sidewalk='both', lit='yes',
            **{'parking:lane:both': 'parallel', 'maxspeed': '25 mph'})
    # ---- blocks
    xs = [X_MIN] + sorted(AV.values()) + [X_MAX]
    zs = [Z_MIN] + sorted(ST.values()) + [Z_MAX]
    blocks = []
    for i in range(len(xs) - 1):
        for j in range(len(zs) - 1):
            x0 = xs[i] + (ROAD_HALF_AV + WALK_AV if xs[i] in AV.values() else 0)
            x1 = xs[i + 1] - (ROAD_HALF_AV + WALK_AV if xs[i + 1] in AV.values() else 0)
            z0 = zs[j] + (ROAD_HALF_ST + WALK_ST if zs[j] in ST.values() else 0)
            z1 = zs[j + 1] - (ROAD_HALF_ST + WALK_ST if zs[j + 1] in ST.values() else 0)
            blocks.append((x0, x1, z0, z1))
    for (x0, x1, z0, z1) in blocks:
        add('lot', rect(x0, x1, z0, z1), landuse='residential')
    # the pocket park: west of Wythe, south of North 7th
    park = next(b for b in blocks if b[1] <= 0 and b[2] >= 0 and b[3] <= 90)
    add('park', rect(*park), leisure='park', name='Kent Pocket Park')
    # ---- buildings: perimeter blocks of row houses, special buildings at the corner
    special = []
    def bld(x0, x1, z0, z1, front, **tags):
        f = add('building', (x0, x1, z0, z1), front=front, **tags); special.append((x0, x1, z0, z1)); return f
    # NE corner: the bodega, 4 storeys, green awning, open 24/7
    bld(11.5, 24.5, -24, -9.5, 'S', building='apartments', **{'building:levels': '4', 'building:material': 'brick', 'building:colour': '#8C4634',
        'roof:shape': 'flat', 'shop': 'convenience', 'name': 'Kent Deli', 'opening_hours': '24/7', 'awning': '#2E7D4A', 'fire_escape': 'S'})
    # SE corner: new glass building with a cafe and a solar roof
    bld(11.5, 44, 9.5, 36, 'W', building='apartments', **{'building:levels': '8', 'building:material': 'glass', 'building:colour': '#7F9DB8',
        'roof:shape': 'flat', 'amenity': 'cafe', 'name': 'Lume Café', 'opening_hours': '07:00-19:00', 'roof_solar': 'yes', 'start_date': '2019'})
    # NW corner (river side): old warehouse, now lofts, with a water tank and a roof garden; bar downstairs
    bld(-58, -11.5, -46, -9.5, 'S', building='apartments', **{'building:levels': '6', 'building:material': 'brick', 'building:colour': '#9A5A43',
        'roof:shape': 'flat', 'amenity': 'bar', 'name': 'The Gilded Owl', 'opening_hours': '17:00-02:00', 'awning': '#1E2A44',
        'man_made:roof': 'water_tank', 'roof_garden': 'yes', 'historic': 'warehouse', 'start_date': '1911'})
    # a clapboard house with a gabled roof, mid-block on North 7th, east
    bld(64, 72, -24, -9.5, 'S', building='house', **{'building:levels': '2', 'building:material': 'wood', 'building:colour': '#9CC0D6',
        'roof:shape': 'gabled', 'roof:colour': '#5A5F66', 'roof:orientation': 'across'})
    # a school on the far side of the park block
    bld(-106, -64, 44, 81.5, 'E', building='school', **{'building:levels': '3', 'building:material': 'brick', 'building:colour': '#B5634A', 'roof:shape': 'flat', 'name': 'PS 17'})
    # row houses around every block edge
    for (x0, x1, z0, z1) in blocks:
        if (x0, x1, z0, z1) == park: continue
        depth = 15.0
        edges = [('S', z1), ('N', z0), ('W', x0), ('E', x1)]
        for side, _ in edges:
            if side in 'NS':
                zb = (z1 - depth, z1) if side == 'S' else (z0, z0 + depth)
                x = x0 + (depth if True else 0)
                while x < x1 - depth - 4:
                    w = rg.choice([6, 6.5, 7, 7.5, 8, 9, 10, 12])
                    xe = min(x + w, x1 - depth)
                    fr = (x, xe, zb[0], zb[1])
                    x = xe
                    if any(not (fr[1] <= s[0] or s[1] <= fr[0] or fr[3] <= s[2] or s[3] <= fr[2]) for s in special): continue
                    lv = rg.choice([3, 3, 4, 4, 4, 5, 3, 2]) if abs(zb[0]) < 130 else rg.choice([3, 4, 5, 6])
                    tags = {'building': 'apartments' if lv >= 4 else 'house', 'building:levels': str(lv), 'building:material': 'brick',
                            'building:colour': rg.choice(BRICKS), 'roof:shape': 'flat'}
                    tags['roof:colour'] = rg.choice(['#BFC2C6', '#B3B6BA', '#C9CCD0', '#D8D6D0', '#4B4A4E', '#5A5856', '#6A6662'])
                    tags['start_date'] = str(rg.choice([1899, 1905, 1910, 1922, 1931, 2008, 2014]))
                    if lv >= 4 and int(tags['start_date']) < 1940 and rg.random() < 0.85: tags['fire_escape'] = side
                    if rg.random() < 0.12: tags['man_made:roof'] = 'water_tank'
                    if rg.random() < 0.08: tags['roof_solar'] = 'yes'
                    if rg.random() < 0.18 and lv >= 3:
                        tags.update({'shop': rg.choice(['books', 'bakery', 'clothes', 'hardware', 'florist']), 'opening_hours': rg.choice(['10:00-19:00', '08:00-18:00', '11:00-21:00']),
                                     'awning': rg.choice(['#B8322A', '#1E3A5F', '#2E7D4A', '#C9A36A', '#6B2E4A'])})
                    add('building', fr, front=side, **tags)
                    zm = (z0 + z1) / 2
                    yard = (fr[0], fr[1], zm + 0.2, fr[2]) if side == 'S' else (fr[0], fr[1], fr[3], zm - 0.2)
                    add('yard', yard, **yard_tags(rg))
            else:
                xb = (x0, x0 + depth) if side == 'W' else (x1 - depth, x1)
                z = z0
                while z < z1 - 3:
                    w = rg.choice([6, 7, 8, 9, 10])
                    ze = min(z + w, z1)
                    if z1 - ze < 5: ze = z1
                    fr = (xb[0], xb[1], z, ze)
                    z = ze
                    if any(not (fr[1] <= s[0] or s[1] <= fr[0] or fr[3] <= s[2] or s[3] <= fr[2]) for s in special): continue
                    lv = rg.choice([3, 4, 4, 5, 3])
                    tags = {'building': 'apartments' if lv >= 4 else 'house', 'building:levels': str(lv), 'building:material': 'brick',
                            'building:colour': rg.choice(BRICKS), 'roof:shape': 'flat'}
                    tags['roof:colour'] = rg.choice(['#BFC2C6', '#B3B6BA', '#D8D6D0', '#4B4A4E', '#5A5856'])
                    tags['start_date'] = str(rg.choice([1899, 1905, 1910, 1922, 1931, 2011]))
                    if lv >= 4 and int(tags['start_date']) < 1940 and rg.random() < 0.8: tags['fire_escape'] = side
                    if rg.random() < 0.1: tags['man_made:roof'] = 'water_tank'
                    if rg.random() < 0.15 and lv >= 3:
                        tags.update({'shop': rg.choice(['books', 'bakery', 'clothes', 'hairdresser', 'wine']), 'opening_hours': rg.choice(['10:00-19:00', '12:00-22:00', '09:00-17:00']),
                                     'awning': rg.choice(['#B8322A', '#1E3A5F', '#2E7D4A', '#C9A36A'])})
                    add('building', fr, front=side, **tags)
                    xm = (x0 + x1) / 2
                    za, zb = max(fr[2], z0 + depth), min(fr[3], z1 - depth)
                    if zb - za > 1:
                        yard = (fr[1], xm - 0.2, za, zb) if side == 'W' else (xm + 0.2, fr[0], za, zb)
                        add('yard', yard, **yard_tags(rg))
    # backyard trees: tree of heaven and mulberry, the Brooklyn backyard classics
    for f in list(F):
        if f['kind'] == 'yard' and f['tags'].get('leisure') == 'garden' and rg.random() < 0.5:
            x0_, x1_, z0_, z1_ = f['geom']
            if x1_ - x0_ > 3 and z1_ - z0_ > 3:
                add('tree', ((x0_ + x1_) / 2 + rg.uniform(-1, 1), (z0_ + z1_) / 2 + rg.uniform(-1, 1)), natural='tree',
                    genus=rg.choice(['Ailanthus', 'Morus', 'Ailanthus', 'Acer']), leaf_cycle='deciduous', height=str(round(rg.uniform(7, 13))), diameter_crown=str(round(rg.uniform(4, 7))))
    # ---- trees along sidewalks
    def trees_along(p0, p1, step, off_pts):
        n = int(math.hypot(p1[0] - p0[0], p1[1] - p0[1]) / step)
        for k in range(1, n):
            t = k / n
            x, z = p0[0] + (p1[0] - p0[0]) * t, p0[1] + (p1[1] - p0[1]) * t
            if rg.random() < 0.18: continue
            genus = rg.choice(['Platanus', 'Platanus', 'Gleditsia', 'Tilia', 'Ginkgo', 'Pyrus', 'Quercus', 'Platanus'])
            add('tree', (x + rg.uniform(-.4, .4), z + rg.uniform(-.4, .4)), natural='tree', genus=genus, leaf_cycle='deciduous',
                height=str(round(rg.uniform(8, 17))), diameter_crown=str(round(rg.uniform(5, 9))))
    for name, x in AV.items():
        for s in (-1, 1):
            xx = x + s * (ROAD_HALF_AV + 1.3)
            for (za, zb) in [(Z_MIN, -98), (-80, -12), (12, 80), (98, Z_MAX)]:
                trees_along((xx, za + 6), (xx, zb - 6), 9, None)
    for name, z in ST.items():
        for s in (-1, 1):
            zz = z + s * (ROAD_HALF_ST + 1.2)
            for (xa, xb) in [(X_MIN, -12), (12, 98), (122, X_MAX)]:
                trees_along((xa + 6, zz), (xb - 6, zz), 9, None)
    # park trees, benches, paths, playground
    px0, px1, pz0, pz1 = park
    for _ in range(26):
        x, z = rg.uniform(px0 + 5, px1 - 5), rg.uniform(pz0 + 5, pz1 - 8)
        if abs(z - (pz0 + pz1) / 2) < 4 or abs(x - (px0 + px1) / 2) < 4: continue
        if -44 < x < -16 and 16 < z < 38: continue
        add('tree', (x, z), natural='tree', genus=rg.choice(['Quercus', 'Acer', 'Platanus', 'Betula']), leaf_cycle='deciduous',
            height=str(round(rg.uniform(10, 18))), diameter_crown=str(round(rg.uniform(7, 12))))
    add('path', ((px0, (pz0 + pz1) / 2), (px1, (pz0 + pz1) / 2)), highway='footway', surface='paving_stones')
    add('path', (((px0 + px1) / 2, pz0), ((px0 + px1) / 2, pz1)), highway='footway', surface='paving_stones')
    for k in range(4):
        add('bench', ((px0 + px1) / 2 + 3.5, pz0 + 10 + k * 7, 'z'), amenity='bench', backrest='yes')
    add('playground', rect(-40, -20, 20, 34), leisure='playground', surface='rubber')
    add('fountain', (-(px1 - px0) / 2 + px1 - 4.5, (pz0 + pz1) / 2 - 4.5), amenity='drinking_water', bottle='yes', seasonal='no')
    for k in range(5):
        add('person_park', (rg.uniform(px0 + 20, px1 - 8), rg.uniform(pz0 + 8, pz1 - 20)))
    # ---- street furniture
    for name, x in AV.items():
        for s in (-1, 1):
            xx = x + s * (ROAD_HALF_AV + 0.5)
            for z in range(int(Z_MIN) + 8, int(Z_MAX), 28):
                if abs(z) < 10 or abs(z - 90) < 10 or abs(z + 90) < 10: continue
                add('lamp', (xx, z + (0 if s > 0 else 14), -s), highway='street_lamp', lamp_mount='bent_mast', light_colour='orange')
    for name, z in ST.items():
        for s in (-1, 1):
            zz = z + s * (ROAD_HALF_ST + 0.5)
            for x in range(int(X_MIN) + 10, int(X_MAX), 30):
                if abs(x) < 12 or abs(x - 110) < 12: continue
                add('lamp', (x + (0 if s > 0 else 15), zz, -s * 2), highway='street_lamp', lamp_mount='bent_mast', light_colour='orange')
    for (x, z) in [(8.2, -30), (-8.2, 30), (40, -6.2), (-40, 6.2), (102, 40), (8.2, 60)]:
        add('hydrant', (x, z), emergency='fire_hydrant', **{'fire_hydrant:type': 'pillar', 'colour': 'red'})
    add('subway', (14.5, -9 + 2.2), railway='subway_entrance', name='Bedford Av (L)', wheelchair='no', level='0')
    add('bus_stop', (9.6, 48), highway='bus_stop', shelter='yes', bench='yes', route_ref='B32')
    for (x, z) in [(-9.6, -40), (30, 6.8), (60, -6.9)]:
        add('bike_rack', (x, z), amenity='bicycle_parking', bicycle_parking='stands', capacity='6')
    # ---- crossings (ladder markings) at every intersection leg
    for ax in AV.values():
        for sz in ST.values():
            add('crossing', ('ns', ax, sz), highway='crossing', crossing='marked', **{'crossing:markings': 'ladder'})
    return F, blocks, park

# ------------------------------------------------------------------ themes
GOLDEN = dict(name='golden', hhmm=(17, 20), night=False,
              sky_top='#86A7D6', sky_hor='#F6D9B8', haze='#F3DCC0', haze_max=0.55,
              sun='#FFD9A6', sun_k=1.0, amb='#8FA0C8', amb_k=0.62,
              asphalt='#7A736D', asphalt_sun='#8C8074', sett='#8A7C70', walk='#DCCFBC', walk_sun='#E6D3B5', curb='#F2E8D8',
              lawn='#8FAE68', lawn_sun='#B0B865', lot='#A89A82', path='#E2D5C0', lane_y='#E8C04A', lane_w='#F2EEE6', bike='#4E9A5C',
              yard_grass='#8CA462', yard_pave='#BDAE96', yard_wood='#A98460', fence='#8A6E52',
              shadow='#27305A', shadow_op=0.42, tree='#6A8A46', tree_sun='#C8B864', tree_dark='#34502E', autumn=['#C98A2E', '#D9A441', '#B8562E'],
              roof='#6B6560', roof_sun='#A08B76', glass_sky='#9FB6D2', glass_sun='#F2C99A', win='#566779', win_sun='#C9D7E4', frame='#EDE6D8')
NIGHT = dict(name='night', hhmm=(21, 30), night=True,
             sky_top='#050814', sky_hor='#1C2340', haze='#161D36', haze_max=0.6,
             sun='#000000', sun_k=0.0, amb='#39456B', amb_k=0.5,
             asphalt='#15192A', asphalt_sun='#15192A', sett='#1A1C28', walk='#232838', walk_sun='#232838', curb='#343B52',
             lawn='#14231E', lawn_sun='#14231E', lot='#141824', path='#262B3B', lane_y='#6E6232', lane_w='#4A4F62', bike='#1F3A2A',
             yard_grass='#121E1A', yard_pave='#1A1D28', yard_wood='#1C1A1E', fence='#262A36',
             shadow='#000000', shadow_op=0.0, tree='#10201C', tree_sun='#10201C', tree_dark='#0A1512', autumn=['#2A2618', '#2E2A1A', '#2A1C14'],
             roof='#1A1E2C', roof_sun='#1A1E2C', glass_sky='#1B2440', glass_sun='#1B2440', win='#1E2436', win_sun='#1E2436', frame='#3A3F52')

NOON = dict(GOLDEN, name='noon', hhmm=(12, 0), sky_top='#7FA6DA', sky_hor='#DCE7F2', haze='#DDE6EE', haze_max=0.5,
            sun='#FFF3DE', sun_k=0.92, amb='#9AAAD0', amb_k=0.64, shadow='#2B3560', shadow_op=0.36,
            glass_sky='#A9C2DE', glass_sun='#E8EEF4', tree_sun='#B9C06E')

# ------------------------------------------------------------------ renderer
class Scene:
    def __init__(s, features, th, cam, L):
        s.F, s.th, s.cam, s.L = features, th, cam, L
        s.boxes = []
        for f in features:
            if f['kind'] == 'building':
                x0, x1, z0, z1 = f['geom']
                s.boxes.append((x0, x1, z0, z1, height_of(f['tags']), f))

def height_of(t):
    lv = float(t.get('building:levels', 3))
    h = lv * 3.2 + (1.0 if t.get('roof:shape') == 'flat' else 0)
    return h

def sunlit(scene, p, skip=None):
    """Ray from p toward the sun; True if nothing blocks it."""
    L = scene.L
    if L[1] <= 0: return False
    for (x0, x1, z0, z1, h, f) in scene.boxes:
        if f is skip: continue
        tmin, tmax = 1e-3, 1e9
        for (o, d, lo, hi) in ((p[0], L[0], x0, x1), (p[1], L[1], 0.0, h), (p[2], L[2], z0, z1)):
            if abs(d) < 1e-9:
                if o < lo or o > hi: tmin = 1e9; break
            else:
                ta, tb = (lo - o) / d, (hi - o) / d
                if ta > tb: ta, tb = tb, ta
                tmin, tmax = max(tmin, ta), min(tmax, tb)
                if tmin > tmax: break
        if tmin <= tmax and tmin < 400: return False
    return True

def light(th, base, n, lit):
    """Colour a surface with normal n: warm sun if lit, cool sky light always."""
    L = th['_L']
    amb = mix(mulc(base, th['amb_k']), mulc(hx(th['amb']), th['amb_k'] * 0.55), 0.35)
    if th['night']:
        return mulc(mix(base, hx(th['amb']), 0.55), 0.34)
    lam = max(0.0, vdot(n, L)) if lit else 0.0
    sky = 0.18 + 0.12 * max(0.0, n[1])
    c = tuple(amb[i] + base[i] * (sky) + base[i] * lam * 1.05 * th['sun_k'] * hx(th['sun'])[i] / 255 for i in range(3))
    return c

def quad_at(a, b, y0, y1, u0, u1, off=0.0, nrm=(0, 0, 0)):
    ax, az = a[0] + (b[0] - a[0]) * u0 + nrm[0] * off, a[1] + (b[1] - a[1]) * u0 + nrm[2] * off
    bx, bz = a[0] + (b[0] - a[0]) * u1 + nrm[0] * off, a[1] + (b[1] - a[1]) * u1 + nrm[2] * off
    return [(ax, y0, az), (bx, y0, bz), (bx, y1, bz), (ax, y1, az)]

def ground_text(cam, text, p0, u, v, size, fill, op=1.0, weight=800, ls=0.18, anchor='middle'):
    """Text painted on the ground (or a wall): exact perspective at the anchor, affine around it."""
    S0 = cam.proj(p0)
    Sx = cam.proj((p0[0] + u[0], p0[1] + u[1], p0[2] + u[2]))
    Sy = cam.proj((p0[0] + v[0], p0[1] + v[1], p0[2] + v[2]))
    if not (S0 and Sx and Sy): return ''
    a, b = Sx[0] - S0[0], Sx[1] - S0[1]
    c, d = Sy[0] - S0[0], Sy[1] - S0[1]
    if abs(a * d - b * c) < 0.02: return ''
    return ('<text transform="matrix(%.3f %.3f %.3f %.3f %.1f %.1f)" font-family="Geist, Helvetica, sans-serif" font-size="%.2f" font-weight="%d" '
            'letter-spacing="%.2f" text-anchor="%s" fill="%s" opacity="%.2f">%s</text>') % (a, b, c, d, S0[0], S0[1], size, weight, ls, anchor, fill, op, text)

def render(th, cfg, F, blocks, park):
    W, H = cfg['W'], cfg['H']
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], W, H, cx=cfg.get('cx'), cy=cfg.get('cy'))
    t_utc = datetime.datetime(2026, 9, 30, th['hhmm'][0] + 4, th['hhmm'][1]) if th['hhmm'][0] + 4 < 24 else datetime.datetime(2026, 10, 1, th['hhmm'][0] + 4 - 24, th['hhmm'][1])
    az, el = sun_position(t_utc)
    L = sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
    th = dict(th); th['_L'] = L
    scene = Scene(F, th, cam, L)
    rg = random.Random(9)
    ground, markings, shadows, items, glow, labels, anchors = [], [], [], [], [], [], {}
    defs = []
    night = th['night']
    # sky
    hy = cam.horizon_y()
    defs.append('<linearGradient id="sky" gradientUnits="userSpaceOnUse" x1="0" y1="%.0f" x2="0" y2="%.0f"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (hy - 300, hy, th['sky_top'], th['sky_hor']))
    ground.append('<rect width="%d" height="%d" fill="url(#sky)"/>' % (W, H))
    def gpoly(pts, fill, y=0.0, extra=''):
        sp = cam.poly([(x, y, z) for (x, z) in pts], margin=600)
        if sp and area(sp) > 0.2: return '<path d="%s" fill="%s"%s/>' % (P(sp), fill, extra)
        return ''
    # base: whole area as sidewalk
    ground.append(gpoly(rect(X_MIN - 400, X_MAX + 400, Z_MIN - 600, Z_MAX + 200), th['walk']))
    # roads
    for f in F:
        if f['kind'] != 'road': continue
        t = f['tags']; o, c, half, walk = f['geom']
        col = th['sett'] if t.get('surface') == 'sett' else th['asphalt']
        if o == 'ns': pts = rect(c - half, c + half, Z_MIN - 600, Z_MAX + 200)
        else: pts = rect(X_MIN - 400, X_MAX + 400, c - half, c + half)
        ground.append(gpoly(pts, col))
    # cobbles on North 7th west of Wythe: grout lines across and a few along
    if not night:
        segs = []
        for x10 in range(int((X_MIN - 60) * 2.2), int(-ROAD_HALF_AV * 2.2)):
            x = x10 / 2.2
            p0, p1 = cam.proj((x, 0.01, -ROAD_HALF_ST)), cam.proj((x, 0.01, ROAD_HALF_ST))
            if p0 and p1 and -20 < p0[0] < W + 20 and -20 < p0[1] < H + 40: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
        for zz in [-4.4, -3.3, -2.2, -1.1, 0, 1.1, 2.2, 3.3, 4.4]:
            p0, p1 = cam.proj((X_MIN - 60, 0.01, zz)), cam.proj((-ROAD_HALF_AV, 0.01, zz))
            if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
        markings.append('<path d="%s" stroke="#4E463F" stroke-width=".45" opacity=".55" fill="none"/>' % ''.join(segs))
    # lots, park, playground, paths
    for f in F:
        if f['kind'] == 'lot': ground.append(gpoly(f['geom'], th['lot']))
    fence = []
    for f in F:
        if f['kind'] != 'yard': continue
        t = f['tags']; x0, x1, z0, z1 = f['geom']
        col = th['yard_grass'] if t.get('surface') == 'grass' else th['yard_wood'] if t.get('surface') == 'wood' else th['yard_pave']
        ground.append(gpoly(rect(x0, x1, z0, z1), col, 0.01))
        sp = cam.poly([(x, 0.02, z) for (x, z) in rect(x0, x1, z0, z1)])
        if sp: fence.append(P(sp))
    if fence: ground.append('<path d="%s" fill="none" stroke="%s" stroke-width=".7" opacity=".75"/>' % (''.join(fence), th['fence']))
    for f in F:
        if f['kind'] == 'park': ground.append(gpoly(f['geom'], th['lawn']))
        if f['kind'] == 'playground': ground.append(gpoly(f['geom'], '#D98E5A' if not night else '#2A2230'))
    for f in F:
        if f['kind'] == 'path':
            ground.append(gpoly(ribbon(list(f['geom']), 3.2), th['path'], 0.02))
    # sidewalk slab joints + curbs + tree pits + lane markings
    joint = []; curb = []
    for f in F:
        if f['kind'] != 'road': continue
        t = f['tags']; o, c, half, walk = f['geom']
        for s in (-1, 1):
            if o == 'ns':
                a, b = cam.proj((c + s * half, 0.03, Z_MIN - 300)), cam.proj((c + s * half, 0.03, Z_MAX))
            else:
                a, b = cam.proj((X_MIN - 300, 0.03, c + s * half)), cam.proj((X_MAX + 300, 0.03, c + s * half))
            if a and b: curb.append('M%.1f %.1fL%.1f %.1f' % (a[0], a[1], b[0], b[1]))
            if not night:
                if o == 'ns':
                    for z in range(int(Z_MIN), int(Z_MAX), 2):
                        p0, p1 = cam.proj((c + s * half, 0.02, z)), cam.proj((c + s * (half + walk), 0.02, z))
                        if p0 and p1 and 0 < p0[1] < H: joint.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
                else:
                    for x in range(int(X_MIN), int(X_MAX), 2):
                        p0, p1 = cam.proj((x, 0.02, c + s * half)), cam.proj((x, 0.02, c + s * (half + walk)))
                        if p0 and p1 and 0 < p0[1] < H: joint.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
        # centre line / bike lane
        if o == 'ns':
            if t.get('lanes') == '2':
                for dx in (-0.12, 0.12):
                    a, b = cam.proj((c + dx, 0.04, Z_MIN - 300)), cam.proj((c + dx, 0.04, Z_MAX))
                    if a and b: markings.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" fill="none"/>' % (a[0], a[1], b[0], b[1], th['lane_y'], 0.9))
            if t.get('cycleway_right') == 'lane':
                markings.append(gpoly(rect(c + half - 3.8, c + half - 2.2, Z_MIN - 300, Z_MAX), th['bike'], 0.03, ' opacity=".85"'))
                for z in range(int(Z_MIN), int(Z_MAX), 30):
                    if abs(z) < 14 or abs(z - 90) < 14 or abs(z + 90) < 14: continue
                    markings.append(ground_text(cam, '⚲', (c + half - 3.0, 0.05, z), (0, 0, -1), (1, 0, 0), 1.2, '#FFFFFF', 0.0))
    if joint: markings.append('<path d="%s" stroke="#9F968A" stroke-width=".4" opacity=".45" fill="none"/>' % ''.join(joint))
    if curb: markings.append('<path d="%s" stroke="%s" stroke-width="1.1" fill="none"/>' % (''.join(curb), th['curb']))
    # crossings: ladder stripes across each leg of the Wythe / North 7th intersection and neighbours
    for f in F:
        if f['kind'] != 'crossing': continue
        _, ax, sz = f['geom']
        stripes = []
        for leg in ('n', 's', 'e', 'w'):
            if leg in 'ns':
                zc = sz + (-(ROAD_HALF_ST + 2.4) if leg == 'n' else ROAD_HALF_ST + 2.4)
                for k in range(-6, 7):
                    x = ax + k * 1.15
                    stripes.append(rect(x - 0.35, x + 0.35, zc - 1.6, zc + 1.6))
            else:
                xc = ax + ((ROAD_HALF_AV + 2.4) if leg == 'e' else -(ROAD_HALF_AV + 2.4))
                for k in range(-4, 5):
                    z = sz + k * 1.15
                    stripes.append(rect(xc - 1.6, xc + 1.6, z - 0.35, z + 0.35))
        ps = []
        for r in stripes:
            sp = cam.poly([(x, 0.05, z) for (x, z) in r])
            if sp: ps.append(P(sp))
        markings.append('<path d="%s" fill="%s" opacity="%.2f"/>' % (''.join(ps), '#F4F1EA', 0.2 if night else 0.88))
    # street names painted on the road
    for f in F:
        if f['kind'] != 'road': continue
        t = f['tags']; o, c, half, walk = f['geom']
        nm = t['name'].upper().replace('NORTH ', 'N ').replace('STREET', 'ST').replace('AVENUE', 'AV')
        if o == 'ew':
            for x in (38, -40, 160):
                labels.append(ground_text(cam, nm, (x, 0.06, c + 1.6), (1, 0, 0), (0, 0, 1), 2.4, '#F4F1EA', 0.18 if night else 0.62))
        else:
            for z in (-45, 45, -130):
                labels.append(ground_text(cam, nm, (c - 2.6, 0.06, z), (0, 0, -1), (1, 0, 0), 2.4, '#F4F1EA', 0.18 if night else 0.62))
    # ---------------------------------------------------------- shadows on the ground
    def shoff(y): return (-L[0] / L[1] * y, -L[2] / L[1] * y)
    if not night and L[1] > 0:
        shp = []
        for (x0, x1, z0, z1, h, f) in scene.boxes:
            foot = rect(x0, x1, z0, z1)
            o = shoff(h)
            pts = foot + [(x + o[0], z + o[1]) for (x, z) in foot]
            hl = hull2(pts)
            sp = cam.poly([(x, 0.08, z) for (x, z) in hl], margin=800)
            if sp: shp.append(P(sp))
            if f['tags'].get('roof:shape') == 'gabled':
                pass
        for f in F:
            if f['kind'] == 'tree':
                x, z = f['geom']; t = f['tags']; h = float(t['height']); r = float(t['diameter_crown']) / 2
                o = shoff(h * 0.72)
                pts = [(x + o[0] + r * math.cos(a) * 1.0 + (o[0] * 0.18) * math.cos(a) ** 2, z + o[1] + r * math.sin(a)) for a in [k * math.pi / 8 for k in range(16)]]
                sp = cam.poly([(px, 0.08, pz) for (px, pz) in pts])
                if sp: shp.append(P(sp))
                o2 = shoff(h * 0.45)
                trunk = ribbon([(x, z), (x + o2[0], z + o2[1])], 0.35)
                sp = cam.poly([(px, 0.08, pz) for (px, pz) in trunk])
                if sp: shp.append(P(sp))
            if f['kind'] == 'lamp':
                x, z, _ = f['geom']; o = shoff(8.5)
                pole = ribbon([(x, z), (x + o[0], z + o[1])], 0.22)
                sp = cam.poly([(px, 0.08, pz) for (px, pz) in pole])
                if sp: shp.append(P(sp))
        shadows.append('<path d="%s" fill="%s" opacity="%.2f"/>' % (''.join(shp), th['shadow'], th['shadow_op']))
    # ---------------------------------------------------------- objects
    for f in F:
        k = f['kind']
        if k == 'building':
            x0, x1, z0, z1 = f['geom']
            c = cam.cam(((x0 + x1) / 2, 4, (z0 + z1) / 2))
            if c[2] < 2: continue
            items.append((c[2], f))
        elif k in ('tree', 'lamp', 'hydrant', 'bench', 'subway', 'bus_stop', 'bike_rack', 'fountain', 'person_park'):
            g = f['geom']
            c = cam.cam((g[0], 1, g[1]))
            if c[2] < 2: continue
            items.append((c[2], f))
    for f in F:
        if f['kind'] == 'playground':
            g_ = f['geom']; x0_, x1_, z0_, z1_ = g_[0][0], g_[2][0], g_[0][1], g_[2][1]
            c = cam.cam(((x0_ + x1_) / 2, 1, (z0_ + z1_) / 2))
            if c[2] > 2: items.append((c[2], f))
    # parked cars from parking:lane=parallel (placed, not mapped)
    cars = []
    for f in F:
        if f['kind'] != 'road': continue
        o, c, half, walk = f['geom']
        for s in (-1, 1):
            if o == 'ns':
                lane = c + s * (half - 1.1)
                if f['tags'].get('cycleway_right') == 'lane' and s > 0: lane = c + s * (half - 0.9)
                z = Z_MIN
                while z < Z_MAX:
                    z += rg.uniform(5.2, 9)
                    if any(abs(z - sz) < 14 for sz in ST.values()) or rg.random() < 0.22: continue
                    cars.append((lane, z, 'ns'))
            else:
                lane = c + s * (half - 1.1)
                x = X_MIN
                while x < X_MAX:
                    x += rg.uniform(5.2, 9)
                    if any(abs(x - ax) < 16 for ax in AV.values()) or rg.random() < 0.22: continue
                    cars.append((x, lane, 'ew'))
    for (x, z, o) in cars:
        c = cam.cam((x, 1, z))
        if c[2] < 2: continue
        items.append((c[2], {'kind': 'car', 'geom': (x, z, o), 'tags': {'_col': rg.choice(CARS)}}))
    # people
    for k in range(34):
        side = rg.choice(['av', 'st'])
        if side == 'av':
            ax = rg.choice(list(AV.values())); s = rg.choice((-1, 1))
            x, z = ax + s * (ROAD_HALF_AV + rg.uniform(1.8, 3.4)), rg.uniform(-120, 120)
        else:
            sz = rg.choice(list(ST.values())); s = rg.choice((-1, 1))
            x, z = rg.uniform(-100, 200), sz + s * (ROAD_HALF_ST + rg.uniform(1.6, 3.0))
        c = cam.cam((x, 1, z))
        if c[2] < 2: continue
        items.append((c[2], {'kind': 'person', 'geom': (x, z), 'tags': {'_col': rg.choice(['#1E3A5F', '#B8322A', '#2A2D33', '#C9A36A', '#6B7282', '#E8E8E4', '#4F7A5A', '#7B4DFF'])}}))
    items.sort(key=lambda it: -it[0])
    out = []
    for d, f in items:
        k = f['kind']
        if k == 'building': out.extend(draw_building(scene, f, d, glow, anchors))
        elif k == 'tree': out.extend(draw_tree(scene, f, d, rg))
        elif k == 'lamp': out.extend(draw_lamp(scene, f, d, glow))
        elif k == 'hydrant': out.extend(draw_post(scene, f, 0.85, 0.28, '#C0392B' if not night else '#4A1F1C', '#E8C04A'))
        elif k == 'bench': out.extend(draw_bench(scene, f))
        elif k == 'car': out.extend(draw_car(scene, f))
        elif k == 'person': out.extend(draw_person(scene, f))
        elif k == 'subway': out.extend(draw_subway(scene, f, glow, anchors))
        elif k == 'bus_stop': out.extend(draw_shelter(scene, f, glow))
        elif k == 'bike_rack': out.extend(draw_rack(scene, f))
        elif k == 'fountain': out.extend(draw_post(scene, f, 0.95, 0.35, '#5A6B7A' if not night else '#1A2230', '#7FC3FF' if not night else '#2A4A6A'))
        elif k == 'person_park': out.extend(draw_person(scene, {'geom': f['geom'], 'tags': {'_col': rg.choice(['#B8322A', '#1E3A5F', '#E8E8E4', '#7B4DFF', '#C9A36A'])}}))
        elif k == 'playground': out.extend(draw_playground(scene, f))
    # night light pools on the ground
    pools = []
    if night:
        defs.append('<radialGradient id="pool"><stop offset="0" stop-color="#FFB45E" stop-opacity=".55"/><stop offset=".5" stop-color="#FFA848" stop-opacity=".18"/><stop offset="1" stop-color="#FFA848" stop-opacity="0"/></radialGradient>')
        defs.append('<radialGradient id="lampglow"><stop offset="0" stop-color="#FFF1D2" stop-opacity=".95"/><stop offset=".12" stop-color="#FFD9A0" stop-opacity=".55"/><stop offset=".45" stop-color="#FFC27A" stop-opacity=".12"/><stop offset="1" stop-color="#FFC27A" stop-opacity="0"/></radialGradient>')
        defs.append('<radialGradient id="shopglow"><stop offset="0" stop-color="#FFD39A" stop-opacity=".45"/><stop offset="1" stop-color="#FFD39A" stop-opacity="0"/></radialGradient>')
        for f in F:
            if f['kind'] != 'lamp': continue
            x, z, dirn = f['geom']
            lx, lz = (x + 1.6 * (dirn if abs(dirn) == 1 else 0), z + 1.6 * (dirn / 2 if abs(dirn) == 2 else 0))
            c = cam.cam((lx, 0, lz))
            if c[2] < 2: continue
            p = cam.pc(c)
            if not (-80 < p[0] < W + 80 and -80 < p[1] < H + 80): continue
            rx = 11 * cam.focal / c[2]
            q = cam.proj((lx, 0, lz + 11)) or p
            ry = max(0.6, abs(q[1] - p[1]))
            pools.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="url(#pool)"/>' % (p[0], p[1], rx, ry))
    # haze
    stops = []
    top = max(0.0, hy)
    for k in range(17):
        sy = top + (H - top) * k / 16
        gd = cam.ground_depth(sy + 0.01)
        a = th['haze_max'] if gd is None else th['haze_max'] * clamp((gd - 140) / 620) ** 1.2
        stops.append('<stop offset="%.3f" stop-color="%s" stop-opacity="%.3f"/>' % (k / 16, th['haze'], a))
    defs.append('<linearGradient id="haze" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%d">%s</linearGradient>' % (top, H, ''.join(stops)))
    haze = '<rect y="%.1f" width="%d" height="%.1f" fill="url(#haze)"/>' % (top, W, H - top)
    if th['name'] == 'golden':
        defs.append('<linearGradient id="wash" x1="0" y1="0" x2="1" y2=".35"><stop offset="0" stop-color="#FFB25E" stop-opacity=".30"/><stop offset=".45" stop-color="#FFC98A" stop-opacity=".08"/><stop offset="1" stop-color="#FFC98A" stop-opacity="0"/></linearGradient>')
        haze += '<rect width="%d" height="%d" fill="url(#wash)" style="mix-blend-mode: soft-light"/><rect width="%d" height="%d" fill="url(#wash)" opacity=".5"/>' % (W, H, W, H)
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d">' % (W, H, W, H),
           '<defs><clipPath id="vb"><rect width="%d" height="%d"/></clipPath>%s</defs><g clip-path="url(#vb)">' % (W, H, ''.join(defs)),
           ''.join(ground), ''.join(markings), ''.join(labels), ''.join(shadows), ''.join(pools),
           '<g stroke-linejoin="round">', ''.join(out), '</g>', ''.join(glow), haze, '</g></svg>']
    # anchors for UI callouts
    for f in F:
        t = f['tags']
        if f['kind'] == 'tree' and 'tree_pick' not in anchors:
            x, z = f['geom']
            if 20 < x < 60 and -9 < z < -6:
                q = cam.proj((x, float(t['height']) * 0.8, z))
                if q and 40 < q[0] < W - 40 and 150 < q[1] < H - 250: anchors['tree_pick'] = [round(q[0], 1), round(q[1], 1), t]
        if f['kind'] == 'lamp' and 'lamp_pick' not in anchors:
            x, z, _ = f['geom']
            q = cam.proj((x, 8.5, z))
            if q and 60 < q[0] < W - 60 and 300 < q[1] < H - 280 and 20 < x < 100: anchors['lamp_pick'] = [round(q[0], 1), round(q[1], 1), t]
        if f['kind'] == 'hydrant' and 'hydrant_pick' not in anchors:
            x, z = f['geom']
            q = cam.proj((x, 0.9, z))
            if q and 40 < q[0] < W - 40 and 200 < q[1] < H - 200: anchors['hydrant_pick'] = [round(q[0], 1), round(q[1], 1), t]
        if f['kind'] == 'road' and t['name'] == 'North 7th Street':
            q = cam.proj((-30, 0, 0))
            if q: anchors['sett_pick'] = [round(q[0], 1), round(q[1], 1), t]
        if f['kind'] == 'crossing' and f['geom'][1] == 0 and f['geom'][2] == 0:
            q = cam.proj((0, 0, -(ROAD_HALF_ST + 2.4)))
            if q: anchors['crossing_pick'] = [round(q[0], 1), round(q[1], 1), t]
    def pick(name, kinds, want, y=1.0, test=lambda f: True):
        best = None
        for f in F:
            if f['kind'] not in kinds or not test(f): continue
            g = f['geom']
            if f['kind'] in ('building',):
                x, z = (g[0] + g[1]) / 2, (g[2] + g[3]) / 2; yy = height_of(f['tags']) + 0.5
            elif f['kind'] in ('park', 'playground'):
                x, z = (g[0][0] + g[2][0]) / 2, (g[0][1] + g[2][1]) / 2; yy = y
            else:
                x, z = g[0], g[1]; yy = float(f['tags'].get('height', y)) * (0.75 if f['kind'] == 'tree' else 1)
            q = cam.proj((x, yy, z))
            if not q or not (30 < q[0] < W - 30 and 120 < q[1] < 612): continue
            dd = math.hypot(q[0] - want[0], q[1] - want[1])
            if best is None or dd < best[0]: best = (dd, q, f)
        if best: anchors[name] = [round(best[1][0], 1), round(best[1][1], 1), best[2]['tags']]
    pick('tree_pick', ('tree',), (140, 596))
    pick('lamp_pick', ('lamp',), (170, 600), y=8.4)
    pick('tank_pick', ('building',), (60, 300), test=lambda f: f['tags'].get('man_made:roof') == 'water_tank')
    pick('solar_pick', ('building',), (360, 640), test=lambda f: f['tags'].get('roof_solar') == 'yes')
    pick('play_pick', ('playground',), (60, 660), y=3)
    q = cam.proj((-13.0, 0.05, 1.5))
    if q: anchors['sett_pick'] = [round(q[0], 1), round(q[1], 1), next(f for f in F if f['kind'] == 'road' and f['tags']['name'] == 'North 7th Street')['tags']]
    anchors['sun'] = {'azimuth': round(az, 1), 'elevation': round(el, 1)}
    return ''.join(svg), anchors, cam

# ------------------------------------------------------------------ drawing helpers
def face_path(cam, q, fill, extra=''):
    sp = cam.poly(q, margin=200)
    if not sp or area(sp) < 0.15: return None, None
    return '<path d="%s" fill="%s"%s/>' % (P(sp), fill, extra), sp

def draw_building(scene, f, d, glow, anchors):
    cam, th, L = scene.cam, scene.th, scene.L
    t = f['tags']; x0, x1, z0, z1 = f['geom']
    night = th['night']
    lv = int(t.get('building:levels', 3)); fh = 3.2
    h = height_of(t)
    mat = t.get('building:material', 'brick')
    base = hx(t.get('building:colour', '#9A5A43'))
    out = []
    foot = rect(x0, x1, z0, z1)
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    gable = t.get('roof:shape') == 'gabled'
    wall_top = h if not gable else lv * 3.0
    rgb = random.Random(hash((x0, z0)) & 0xffff)
    for i in range(4):
        a, b = foot[i], foot[(i + 1) % 4]
        ex, ez = b[0] - a[0], b[1] - a[1]
        nrm = vnorm((ez, 0, -ex))
        mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        if (mid[0] - cx) * nrm[0] + (mid[1] - cz) * nrm[2] < 0: nrm = (-nrm[0], 0, -nrm[2])
        if vdot(nrm, vsub(cam.C, (mid[0], h / 2, mid[1]))) <= 0: continue
        side = 'S' if nrm[2] > 0.5 else 'N' if nrm[2] < -0.5 else 'E' if nrm[0] > 0.5 else 'W'
        elen = math.hypot(ex, ez)
        # facade in sun/shade cells: rows per level, columns ~4 m
        ncol = max(1, int(elen / 4))
        for r in range(max(1, int(wall_top / fh + 0.5))):
            ya, yb = r * fh, min(wall_top, (r + 1) * fh)
            if ya >= wall_top: break
            for cidx in range(ncol):
                u0, u1 = cidx / ncol, (cidx + 1) / ncol
                pc = ((a[0] + b[0]) / 2 + nrm[0] * 0.05, (ya + yb) / 2, (a[1] + b[1]) / 2 + nrm[2] * 0.05)
                pc = (a[0] + (b[0] - a[0]) * (u0 + u1) / 2 + nrm[0] * 0.05, (ya + yb) / 2, a[1] + (b[1] - a[1]) * (u0 + u1) / 2 + nrm[2] * 0.05)
                lit = (not night) and vdot(nrm, L) > 0 and sunlit(scene, pc, skip=f)
                if mat == 'glass':
                    refl = mix(hx(th['glass_sky']), hx(th['sky_hor']), clamp(1 - (ya / max(1, wall_top)))) if not night else hx(th['glass_sky'])
                    col = light(th, mulc(refl, 0.8), nrm, lit)
                    if not night: col = mix(col, hx(th['glass_sun']) if lit else refl, (0.6 if lit else 0.45) * rgb.uniform(0.55, 1.1))
                    col = mulc(col, rgb.uniform(0.9, 1.05))
                else:
                    col = light(th, mulc(base, 1 + rgb.uniform(-0.02, 0.02)), nrm, lit)
                s, _ = face_path(cam, quad_at(a, b, ya, yb, u0, u1), tohex(col), ' stroke="%s" stroke-width=".3"' % tohex(col))
                if s: out.append(s)
        # windows + storefront + details
        near = d < 420
        is_front = t.get('front') == side
        shop = is_front and ('shop' in t or 'amenity' in t)
        open_now = shop and open_at(t.get('opening_hours', '00:00-00:01'), th['hhmm'])
        wcols = max(1, int((elen - 1) / 2.6)) if mat != 'glass' else max(1, int(elen / 1.6))
        for r in range(lv):
            if r == 0 and shop:
                # storefront glass
                gl = '#FFD9A0' if (open_now and night) else ('#F7D8A8' if open_now else th['win'])
                s, sp = face_path(cam, quad_at(a, b, 0.35, 2.9, 0.08, 0.92, 0.06, nrm), gl, ' stroke="%s" stroke-width=".6"' % th['frame'])
                if s: out.append(s)
                if open_now and night and sp:
                    xs_ = [p[0] for p in sp]; ys_ = [p[1] for p in sp]
                    out.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="url(#shopglow)"/>' % ((min(xs_) + max(xs_)) / 2, max(ys_), (max(xs_) - min(xs_)) * 0.8, (max(ys_) - min(ys_)) * 0.9))
                # awning: sloped quad sticking out 1.4 m
                aw = t.get('awning')
                if aw:
                    q = quad_at(a, b, 3.35, 3.35, 0.06, 0.94, 0.05, nrm)
                    q2 = [q[0], q[1], (q[1][0] + nrm[0] * 1.5, 2.8, q[1][2] + nrm[2] * 1.5), (q[0][0] + nrm[0] * 1.5, 2.8, q[0][2] + nrm[2] * 1.5)]
                    acol = hx(aw)
                    litA = (not night) and sunlit(scene, ((q[0][0] + q[1][0]) / 2 + nrm[0], 3.1, (q[0][2] + q[1][2]) / 2 + nrm[2]), skip=f)
                    c2 = light(th, acol, vnorm((nrm[0], 1.0, nrm[2])), litA)
                    s, sp = face_path(cam, q2, tohex(c2))
                    if s: out.append(s)
                    s, sp2 = face_path(cam, [q2[3], q2[2], (q2[2][0], 2.45, q2[2][2]), (q2[3][0], 2.45, q2[3][2])], tohex(mulc(c2, 0.82)))
                    if s: out.append(s)
                # sign band with the name, painted onto the wall
                nm = t.get('name')
                if nm and near:
                    q = quad_at(a, b, 3.5, 4.4, 0.15, 0.85, 0.05, nrm)
                    s, sp = face_path(cam, q, '#14161B' if not night else '#0B0D14')
                    if s:
                        out.append(s)
                        u = vnorm((b[0] - a[0], 0, b[1] - a[1]))
                        if vdot(vcross(u, (0, -1, 0)), nrm) < 0: u = (-u[0], 0, -u[2])
                        mx_, mz_ = (a[0] + b[0]) / 2 + nrm[0] * 0.08, (a[1] + b[1]) / 2 + nrm[2] * 0.08
                        out.append(ground_text(cam, nm.upper(), (mx_, 3.7, mz_), u, (0, -1, 0), 0.62, '#FFE7A3' if (night and open_now) else '#F4EFE4', 1.0, 800, 0.08))
                        if 'name' in t and t.get('shop') == 'convenience': anchors['shop_pick'] = [round((min(p[0] for p in sp) + max(p[0] for p in sp)) / 2, 1), round(min(p[1] for p in sp), 1), t]
                        if t.get('amenity') == 'cafe': anchors['cafe_pick'] = [round((min(p[0] for p in sp) + max(p[0] for p in sp)) / 2, 1), round(min(p[1] for p in sp), 1), t]
                        if t.get('amenity') == 'bar': anchors['bar_pick'] = [round((min(p[0] for p in sp) + max(p[0] for p in sp)) / 2, 1), round(min(p[1] for p in sp), 1), t]
                continue
            if not near and r > 0 and d > 700: continue
            ya = r * fh + 0.9; yb = ya + (1.7 if mat != 'glass' else 2.4)
            if gable and r >= lv: continue
            for ci in range(wcols):
                u0 = (0.5 + ci * (elen - 1) / wcols + 0.35) / elen
                u1 = u0 + ((elen - 1) / wcols - 0.7) / elen
                if u1 <= u0: continue
                if night:
                    lit_room = rgb.random() < (0.5 if t.get('building') in ('apartments', 'house') else 0.25)
                    if lit_room:
                        col = rgb.choice(['#FFD08A', '#FFC56F', '#FFE2AE', '#FFB85C', '#E8EEFF'])
                        s, _ = face_path(cam, quad_at(a, b, ya, yb, u0, u1, 0.04, nrm), col, ' opacity="%.2f"' % rgb.choice([0.55, 0.7, 0.85, 0.95]))
                        if s: out.append(s)
                    continue
                if mat == 'glass':
                    s, _ = face_path(cam, quad_at(a, b, r * fh, r * fh + 3.2, u0 - 0.006, u0, 0.04, nrm), '#E9EEF4', ' opacity=".6"')
                    if s: out.append(s)
                    if ci == 0:
                        s, _ = face_path(cam, quad_at(a, b, r * fh + 3.0, r * fh + 3.2, 0, 1, 0.04, nrm), '#DDE4EC', ' opacity=".7"')
                        if s: out.append(s)
                    continue
                pc = (a[0] + (b[0] - a[0]) * (u0 + u1) / 2 + nrm[0] * 0.1, (ya + yb) / 2, a[1] + (b[1] - a[1]) * (u0 + u1) / 2 + nrm[2] * 0.1)
                wl = (not night) and vdot(nrm, L) > 0 and sunlit(scene, pc, skip=f)
                s, _ = face_path(cam, quad_at(a, b, ya - 0.12, yb + 0.12, u0 - 0.012, u1 + 0.012, 0.03, nrm), th['frame'], ' opacity=".9"')
                if s: out.append(s)
                s, _ = face_path(cam, quad_at(a, b, ya, yb, u0, u1, 0.04, nrm), th['win_sun'] if wl else th['win'])
                if s: out.append(s)
        # cornice for masonry, parapet line
        if mat == 'brick' and not gable:
            s, _ = face_path(cam, quad_at(a, b, h - 0.7, h, -0.02, 1.02, 0.35, nrm), tohex(mulc(light(th, base, (0, 0.4, 0), not night), 1.06)))
            if s: out.append(s)
        # fire escape: platforms + ladders, drawn in near-black ironwork
        if t.get('fire_escape') == side and near and elen > 6:
            iron = '#26272B' if not night else '#0B0C10'
            segs = []
            for r in range(1, lv):
                y = r * fh + 0.2
                for (ua, ub) in ((0.15, 0.62),):
                    pa = quad_at(a, b, y, y, ua, ub, 1.1, nrm)
                    p1, p2 = cam.proj(pa[0]), cam.proj(pa[1])
                    pa2 = quad_at(a, b, y + 1.0, y + 1.0, ua, ub, 1.1, nrm)
                    p3, p4 = cam.proj(pa2[0]), cam.proj(pa2[1])
                    if p1 and p2 and p3 and p4:
                        segs.append('M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f' % (p1[0], p1[1], p2[0], p2[1], p3[0], p3[1], p4[0], p4[1], p1[0], p1[1], p3[0], p3[1], p2[0], p2[1], p4[0], p4[1]))
                    la = quad_at(a, b, y, y + fh, ua + 0.05 if r % 2 else ub - 0.12, ub - 0.12 if r % 2 else ua + 0.05, 0.6, nrm)
                    q1, q2 = cam.proj(la[0]), cam.proj(la[2])
                    if q1 and q2: segs.append('M%.1f %.1fL%.1f %.1f' % (q1[0], q1[1], q2[0], q2[1]))
            if segs: out.append('<path d="%s" stroke="%s" stroke-width="%.2f" fill="none" opacity=".85"/>' % (''.join(segs), iron, max(0.5, 60 / d)))
    # roof
    if gable:
        ry = lv * 3.0; apex = ry + 3.4
        rcol = hx(t.get('roof:colour', '#5A5F66'))
        if t.get('roof:orientation') == 'across':
            ridge = [(x0, apex, cz), (x1, apex, cz)]
            planes = [([(x0, ry, z0), (x1, ry, z0), (x1, apex, cz), (x0, apex, cz)], (0, 0.7, -0.7)), ([(x0, ry, z1), (x1, ry, z1), (x1, apex, cz), (x0, apex, cz)], (0, 0.7, 0.7))]
            gables = [[(x0, ry, z0), (x0, ry, z1), (x0, apex, cz)], [(x1, ry, z0), (x1, ry, z1), (x1, apex, cz)]]
        else:
            planes = [([(x0, ry, z0), (x0, ry, z1), (cx, apex, z1), (cx, apex, z0)], (-0.7, 0.7, 0)), ([(x1, ry, z0), (x1, ry, z1), (cx, apex, z1), (cx, apex, z0)], (0.7, 0.7, 0))]
            gables = [[(x0, ry, z0), (x1, ry, z0), (cx, apex, z0)], [(x0, ry, z1), (x1, ry, z1), (cx, apex, z1)]]
        for tri in gables:
            n = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
            cen = tuple(sum(p[i] for p in tri) / 3 for i in range(3))
            if vdot(n, vsub(cam.C, cen)) < 0: n = tuple(-v for v in n)
            if vdot(n, vsub(cam.C, cen)) <= 0: continue
            s, _ = face_path(cam, tri, tohex(light(th, base, n, (not night) and vdot(n, L) > 0)))
            if s: out.append(s)
        for (q, n) in planes:
            n = vnorm(n)
            cen = tuple(sum(p[i] for p in q) / 4 for i in range(3))
            if vdot(n, vsub(cam.C, cen)) <= 0: continue
            s, _ = face_path(cam, q, tohex(light(th, rcol, n, (not night) and vdot(n, L) > 0)))
            if s: out.append(s)
    elif cam.C[1] > h:
        rcol = hx(t.get('roof:colour', th['roof']))
        litR = (not night) and sunlit(scene, (cx, h + 0.05, cz), skip=f)
        s, _ = face_path(cam, [(x, h, z) for (x, z) in foot], tohex(light(th, mix(rcol, hx(th['roof_sun']), 0.25 if litR else 0), (0, 1, 0), litR)))
        if s: out.append(s)
        inset = rect(x0 + 0.5, x1 - 0.5, z0 + 0.5, z1 - 0.5)
        s, _ = face_path(cam, [(x, h - 0.02, z) for (x, z) in inset], tohex(mulc(light(th, rcol, (0, 1, 0), litR), 0.9)))
        if s: out.append(s)
        rg = random.Random(hash(('roof', x0, z0)) & 0xffff)
        if t.get('roof_garden') == 'yes':
            s, _ = face_path(cam, [(x, h + 0.05, z) for (x, z) in rect(x0 + 3, x1 - 3, z0 + 3, z0 + 12)], '#B98A5E' if not night else '#2A2230')
            if s: out.append(s)
            for k in range(int((x1 - x0 - 6) / 3)):
                px = x0 + 3 + k * 3
                out.extend(box(scene, px, px + 2.4, h, h + 0.7, z0 + 12.4, z0 + 13.4, hx('#7A5A40')))
                pc = cam.proj((px + 1.2, h + 1.1, z0 + 12.9))
                if pc: out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (pc[0], pc[1], 0.8 * cam.focal / d, '#5E8A48' if not night else '#0E1A16'))
        if t.get('roof_solar') == 'yes':
            for k in range(4):
                for m in range(2):
                    px0 = x0 + 2 + k * (x1 - x0 - 4) / 4; pz0 = z0 + 2 + m * (z1 - z0 - 4) / 2
                    q = [(px0 + 0.3, h + 0.4, pz0 + 0.3), (px0 + (x1 - x0 - 4) / 4 - 0.3, h + 0.4, pz0 + 0.3), (px0 + (x1 - x0 - 4) / 4 - 0.3, h + 1.1, pz0 + (z1 - z0 - 4) / 2 - 0.3), (px0 + 0.3, h + 1.1, pz0 + (z1 - z0 - 4) / 2 - 0.3)]
                    s, _ = face_path(cam, q, '#1F3358' if not night else '#0B1224', ' stroke="#6A86B8" stroke-width=".35"')
                    if s: out.append(s)
        if t.get('building') == 'apartments' and mat != 'glass' and d < 600 and (x1 - x0) > 5 and (z1 - z0) > 5:
            rb = random.Random(hash(('bk', x0, z0)) & 0xffff)
            bx, bz = x0 + (x1 - x0 - 2.4) * rb.uniform(0.15, 0.85), z0 + (z1 - z0 - 3.0) * rb.uniform(0.15, 0.85)
            out.extend(box(scene, bx, bx + 2.4, h, h + 2.6, bz, bz + 3.0, mix(mulc(base, 0.8), hx('#8C8A86'), 0.55)))
        if t.get('man_made:roof') == 'water_tank' and d < 520:
            wx, wz = x0 + (x1 - x0) * rg.uniform(0.3, 0.7), z0 + (z1 - z0) * rg.uniform(0.3, 0.7)
            out.extend(draw_tank(scene, wx, wz, h, d, f))
    return out

def draw_tank(scene, x, z, h, d, f):
    cam, th = scene.cam, scene.th
    out = []
    night = th['night']
    wood = hx('#8A6A4E') if not night else hx('#2A2420')
    legs = []
    for (lx, lz) in ((-1.3, -1.3), (1.3, -1.3), (1.3, 1.3), (-1.3, 1.3)):
        p0, p1 = cam.proj((x + lx, h, z + lz)), cam.proj((x + lx, h + 3, z + lz))
        if p0 and p1: legs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
    out.append('<path d="%s" stroke="#3A3430" stroke-width="%.2f" fill="none"/>' % (''.join(legs), max(0.5, 40 / d)))
    y0, y1, r = h + 3, h + 7.2, 2.1
    seg = 14
    ring = [(x + r * math.cos(2 * math.pi * k / seg), z + r * math.sin(2 * math.pi * k / seg)) for k in range(seg)]
    for k in range(seg):
        a, b = ring[k], ring[(k + 1) % seg]
        n = vnorm(((a[0] + b[0]) / 2 - x, 0, (a[1] + b[1]) / 2 - z))
        if vdot(n, vsub(cam.C, ((a[0] + b[0]) / 2, (y0 + y1) / 2, (a[1] + b[1]) / 2))) <= 0: continue
        lit = (not night) and vdot(n, scene.L) > 0
        col = tohex(light(th, wood, n, lit))
        s, _ = face_path(cam, [(a[0], y0, a[1]), (b[0], y0, b[1]), (b[0], y1, b[1]), (a[0], y1, a[1])], col, ' stroke="%s" stroke-width=".3"' % col)
        if s: out.append(s)
    # hoops
    hoops = []
    for yy in (y0 + 1.0, y0 + 2.4, y0 + 3.6):
        pts = [cam.proj((x + (r + 0.05) * math.cos(2 * math.pi * k / 24), yy, z + (r + 0.05) * math.sin(2 * math.pi * k / 24))) for k in range(25)]
        vis = [p for p, k in zip(pts, range(25)) if p and vdot(vnorm((math.cos(2 * math.pi * k / 24), 0, math.sin(2 * math.pi * k / 24))), vsub(cam.C, (x, yy, z))) > 0]
        if len(vis) > 1: hoops.append('M' + 'L'.join('%.1f %.1f' % p for p in vis))
    if hoops: out.append('<path d="%s" stroke="#2E2A26" stroke-width=".5" fill="none" opacity=".7"/>' % ''.join(hoops))
    apex = (x, y1 + 1.6, z)
    for k in range(seg):
        a, b = ring[k], ring[(k + 1) % seg]
        tri = [(a[0], y1, a[1]), (b[0], y1, b[1]), apex]
        n = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
        if n[1] < 0: n = tuple(-v for v in n)
        cen = tuple(sum(p[i] for p in tri) / 3 for i in range(3))
        if vdot(n, vsub(cam.C, cen)) <= 0: continue
        s, _ = face_path(cam, tri, tohex(light(th, hx('#5A4A3E') if not night else hx('#201C1A'), n, (not night) and vdot(n, scene.L) > 0)))
        if s: out.append(s)
    return out

def draw_tree(scene, f, d, rg):
    cam, th = scene.cam, scene.th
    x, z = f['geom']; t = f['tags']
    h = float(t['height']); r = float(t['diameter_crown']) / 2
    night = th['night']
    out = []
    p0, p1 = cam.proj((x, 0, z)), cam.proj((x, h * 0.5, z))
    if not (p0 and p1): return out
    if not (-40 < p0[0] < cam.W + 40 and -140 < p0[1] < cam.H + 60): return out
    pit = rect(x - 0.9, x + 0.9, z - 0.9, z + 0.9)
    sp = cam.poly([(px, 0.03, pz) for (px, pz) in pit])
    if sp and not night: out.append('<path d="%s" fill="#6E5A44" opacity=".7"/>' % P(sp))
    out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (p0[0], p0[1], p1[0], p1[1], '#4A3A2E' if not night else '#0C100E', max(0.6, 0.32 * cam.focal / d)))
    rrg = random.Random(hash((round(x, 1), round(z, 1))) & 0xffffff)
    turn = rrg.random()
    genus = t.get('genus')
    base = hx(th['tree'])
    if not night:
        if genus == 'Gleditsia' and turn < 0.6: base = mix(base, hx(th['autumn'][1]), 0.6)
        elif genus == 'Ginkgo' and turn < 0.35: base = mix(base, hx('#D9C04A'), 0.5)
        elif genus == 'Pyrus' and turn < 0.35: base = mix(base, hx(th['autumn'][2]), 0.4)
        elif genus in ('Acer', 'Morus') and turn < 0.4: base = mix(base, hx(th['autumn'][0]), 0.45)
        elif genus == 'Ailanthus': base = mix(base, hx('#9AAE52'), 0.4)
        base = mulc(base, rrg.uniform(0.93, 1.06))
    L = scene.L
    n = 14 if d < 260 else (8 if d < 650 else 5)
    blobs = []
    for k in range(n):
        ang = rrg.uniform(0, 2 * math.pi); rad = r * math.sqrt(rrg.random()) * 0.75
        bx, bz = x + rad * math.cos(ang), z + rad * math.sin(ang)
        by = h * 0.66 + rrg.uniform(-0.35, 0.35) * r * (1 - rad / r * 0.6)
        br = r * rrg.uniform(0.3, 0.46)
        c = cam.cam((bx, by, bz))
        if c[2] < 1: continue
        blobs.append((c[2], bx, by, bz, br))
    blobs.sort(key=lambda b: -b[0])
    lit_crown = (not night) and sunlit(scene, (x + L[0] * r, h * 0.7 + L[1] * r, z + L[2] * r))
    dark = light(th, hx(th['tree_dark']), (0, 1, 0), False)
    midc = mulc(light(th, base, vnorm((L[0], 0.7, L[2])), lit_crown), 0.86) if not night else dark
    hic = mix(midc, hx(th['tree_sun']), 0.38)
    under, top, hi = [], [], []
    for (dd, bx, by, bz, br) in blobs:
        p = cam.proj((bx, by, bz))
        rr = br * cam.focal / dd
        under.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0], p[1] + rr * 0.1, rr))
        if night: continue
        # which side of this blob faces the sun: shift toward the sun's screen direction
        sx = cam.proj((bx + L[0] * br, by + L[1] * br, bz + L[2] * br))
        if not sx: continue
        vx, vy = sx[0] - p[0], sx[1] - p[1]
        top.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0] + vx * 0.28, p[1] + vy * 0.28 - rr * 0.08, rr * 0.8))
        if lit_crown and rr > 1.6 and (by - h * 0.66) > -0.1 * r:
            hi.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0] + vx * 0.55, p[1] + vy * 0.55 - rr * 0.15, rr * 0.38))
    out.append('<g fill="%s">%s</g>' % (tohex(dark), ''.join(under)))
    if top: out.append('<g fill="%s">%s</g>' % (tohex(midc), ''.join(top)))
    if hi: out.append('<g fill="%s" opacity=".85">%s</g>' % (tohex(hic), ''.join(hi)))
    return out

def draw_lamp(scene, f, d, glow):
    cam, th = scene.cam, scene.th
    x, z, dirn = f['geom']
    ax = dirn if abs(dirn) == 1 else 0
    azn = dirn / 2 if abs(dirn) == 2 else 0
    p0, p1 = cam.proj((x, 0, z)), cam.proj((x, 8.2, z))
    p2 = cam.proj((x + ax * 1.6, 8.5, z + azn * 1.6))
    if not (p0 and p1 and p2): return []
    out = ['<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" fill="none"/>' % (p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], '#3A3F46' if not th['night'] else '#1A1E28', max(0.5, 18 / d))]
    rr = max(0.8, 0.45 * cam.focal / d)
    out.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="%s"/>' % (p2[0], p2[1] + rr * 0.3, rr * 1.4, rr * 0.6, '#2A2D33' if not th['night'] else '#FFE9C2'))
    if th['night']:
        out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#lampglow)"/>' % (p2[0], p2[1], rr * 8))
    return out

def draw_post(scene, f, h, r, col, cap):
    cam = scene.cam
    x, z = f['geom'][:2]
    p0, p1 = cam.proj((x, 0, z)), cam.proj((x, h, z))
    if not (p0 and p1): return []
    d = cam.depth((x, h, z)); w = max(1.0, 2 * r * cam.focal / d)
    return ['<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (p0[0], p0[1], p1[0], p1[1], col, w),
            '<circle cx="%.1f" cy="%.1f" r="%.2f" fill="%s"/>' % (p1[0], p1[1], w * 0.55, cap)]

def box(scene, x0, x1, y0, y1, z0, z1, col, lit_ok=True):
    cam, th, L = scene.cam, scene.th, scene.L
    out = []
    faces = [((0, 0, -1), [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)]), ((0, 0, 1), [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]),
             ((-1, 0, 0), [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)]), ((1, 0, 0), [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)]),
             ((0, 1, 0), [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)])]
    for n, q in faces:
        cen = tuple(sum(p[i] for p in q) / 4 for i in range(3))
        if vdot(n, vsub(cam.C, cen)) <= 0: continue
        lit = lit_ok and not th['night'] and vdot(n, L) > 0 and sunlit(scene, (cen[0] + n[0] * 0.05, cen[1] + n[1] * 0.05, cen[2] + n[2] * 0.05))
        s, _ = face_path(cam, q, tohex(light(th, col, n, lit)))
        if s: out.append(s)
    return out

def draw_car(scene, f):
    x, z, o = f['geom']; col = hx(f['tags']['_col'])
    L, W_ = 4.4, 1.8
    if o == 'ns': a = (x - W_ / 2, x + W_ / 2, z - L / 2, z + L / 2); c = (x - 0.8, x + 0.8, z - 1.2, z + 1.0)
    else: a = (x - L / 2, x + L / 2, z - W_ / 2, z + W_ / 2); c = (x - 1.2, x + 1.0, z - 0.8, z + 0.8)
    out = box(scene, a[0], a[1], 0.25, 1.0, a[2], a[3], col)
    out += box(scene, c[0], c[1], 1.0, 1.5, c[2], c[3], mix(hx('#2A3140'), col, 0.25) if not scene.th['night'] else hx('#0A0D16'))
    return out

def draw_person(scene, f):
    cam, th = scene.cam, scene.th
    x, z = f['geom']
    p0, p1 = cam.proj((x, 0, z)), cam.proj((x, 1.45, z)); ph = cam.proj((x, 1.7, z))
    if not (p0 and p1 and ph): return []
    d = cam.depth((x, 1, z)); w = max(0.9, 0.5 * cam.focal / d)
    col = f['tags']['_col'] if not th['night'] else '#0E1220'
    out = []
    if not th['night']:
        L = th['_L']
        s = cam.proj((x - L[0] / L[1] * 1.7, 0, z - L[2] / L[1] * 1.7))
        if s: out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" opacity="%.2f" stroke-linecap="round"/>' % (p0[0], p0[1], s[0], s[1], th['shadow'], w, th['shadow_op'] + 0.1))
    out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (p0[0], p0[1], p1[0], p1[1], col, w))
    out.append('<circle cx="%.1f" cy="%.1f" r="%.2f" fill="%s"/>' % (ph[0], ph[1], w * 0.55, '#E2B48E' if not th['night'] else '#1A1A22'))
    return out

def draw_playground(scene, f):
    g = f['geom']; x0, x1, z0, z1 = g[0][0], g[2][0], g[0][1], g[2][1]
    th = scene.th
    out = []
    # climbing frame with a slide, and a swing set
    out += box(scene, x0 + 4, x0 + 8, 1.4, 1.6, z0 + 4, z0 + 8, hx('#E8C04A'))
    for (px, pz) in ((x0 + 4, z0 + 4), (x0 + 8, z0 + 4), (x0 + 8, z0 + 8), (x0 + 4, z0 + 8)):
        out += box(scene, px - 0.1, px + 0.1, 0, 2.6, pz - 0.1, pz + 0.1, hx('#2E5BFF'))
    out += box(scene, x0 + 3.2, x0 + 8.8, 2.6, 3.0, z0 + 3.2, z0 + 8.8, hx('#B8322A'))
    cam = scene.cam
    a, b = cam.proj((x0 + 8, 1.5, z0 + 6)), cam.proj((x0 + 12, 0.1, z0 + 6))
    if a and b: out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#2EA86A" stroke-width="%.1f" stroke-linecap="round"/>' % (a[0], a[1], b[0], b[1], max(1.2, 1.1 * cam.focal / cam.depth((x0 + 10, 1, z0 + 6)))))
    segs = []
    for pz in (z1 - 3, z1 - 9):
        for px in (x1 - 7, x1 - 2):
            p0, p1 = cam.proj((px, 0, pz)), cam.proj((px + (0.8 if px < x1 - 4 else -0.8), 2.6, pz))
            if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
    t0, t1 = cam.proj((x1 - 6.2, 2.6, z1 - 3)), cam.proj((x1 - 2.8, 2.6, z1 - 3))
    t2, t3 = cam.proj((x1 - 6.2, 2.6, z1 - 9)), cam.proj((x1 - 2.8, 2.6, z1 - 9))
    for (u, v) in ((t0, t2), (t1, t3)):
        if u and v: segs.append('M%.1f %.1fL%.1f %.1f' % (u[0], u[1], v[0], v[1]))
    if segs: out.append('<path d="%s" stroke="%s" stroke-width="1" fill="none"/>' % (''.join(segs), '#3A3F46' if not th['night'] else '#161A24'))
    return out

def draw_bench(scene, f):
    x, z, o = f['geom']
    col = hx('#6E4B3A')
    if o == 'z': return box(scene, x - 0.25, x + 0.25, 0.4, 0.5, z - 0.9, z + 0.9, col) + box(scene, x + 0.2, x + 0.3, 0.5, 0.95, z - 0.9, z + 0.9, col)
    return box(scene, x - 0.9, x + 0.9, 0.4, 0.5, z - 0.25, z + 0.25, col)

def draw_rack(scene, f):
    cam = scene.cam
    x, z = f['geom']
    segs = []
    for k in range(3):
        xx = x - 1.2 + k * 1.2
        pts = [cam.proj((xx + 0.4 * math.cos(a), 0.9 * math.sin(a), z)) for a in [i * math.pi / 8 for i in range(9)]]
        if all(pts): segs.append('M' + 'L'.join('%.1f %.1f' % p for p in pts))
    return ['<path d="%s" stroke="#6B7282" stroke-width="1" fill="none"/>' % ''.join(segs)] if segs else []

def draw_subway(scene, f, glow, anchors):
    cam, th = scene.cam, scene.th
    x, z = f['geom']
    out = []
    sp = cam.poly([(px, 0.04, pz) for (px, pz) in rect(x - 1.1, x + 1.1, z, z + 3.6)])
    if sp: out.append('<path d="%s" fill="#1A1C22"/>' % P(sp))
    rail = []
    for xx in (x - 1.15, x + 1.15):
        a, b = cam.proj((xx, 1.0, z)), cam.proj((xx, 1.0, z + 3.6))
        c, d2 = cam.proj((xx, 0, z)), cam.proj((xx, 0, z + 3.6))
        if a and b and c and d2: rail.append('M%.1f %.1fL%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f' % (c[0], c[1], a[0], a[1], b[0], b[1], b[0], b[1], d2[0], d2[1]))
    out.append('<path d="%s" stroke="#1F5A3A" stroke-width="1.1" fill="none"/>' % ''.join(rail))
    for xx in (x - 1.15, x + 1.15):
        g0, g = cam.proj((xx, 1.0, z + 3.6)), cam.proj((xx, 2.1, z + 3.6))
        if g0 and g:
            out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#1F5A3A" stroke-width="1"/>' % (g0[0], g0[1], g[0], g[1]))
            out.append('<circle cx="%.1f" cy="%.1f" r="1.6" fill="%s"/>' % (g[0], g[1], '#5BE08A' if th['night'] else '#3FA86A'))
            if th['night']: out.append('<circle cx="%.1f" cy="%.1f" r="7" fill="#5BE08A" opacity=".25"/>' % (g[0], g[1]))
    q = cam.proj((x, 2.4, z + 3.6))
    if q: anchors['subway_pick'] = [round(q[0], 1), round(q[1], 1), f['tags']]
    return out

def draw_shelter(scene, f, glow):
    cam, th = scene.cam, scene.th
    x, z = f['geom']
    out = []
    out += box(scene, x - 0.8, x + 0.8, 2.4, 2.55, z - 2.2, z + 2.2, hx('#3A3F46'))
    fr = []
    for (px, pz) in ((x - 0.8, z - 2.2), (x + 0.8, z - 2.2), (x + 0.8, z + 2.2), (x - 0.8, z + 2.2)):
        a, b = cam.proj((px, 0, pz)), cam.proj((px, 2.4, pz))
        if a and b: fr.append('M%.1f %.1fL%.1f %.1f' % (a[0], a[1], b[0], b[1]))
    out.append('<path d="%s" stroke="#3A3F46" stroke-width="1"/>' % ''.join(fr))
    s, _ = face_path(cam, [(x + 0.8, 0.3, z - 2.1), (x + 0.8, 0.3, z - 0.4), (x + 0.8, 2.2, z - 0.4), (x + 0.8, 2.2, z - 2.1)], '#FFF4DE' if th['night'] else '#DCE8F2', ' opacity=".85"')
    if s: out.append(s)
    return out

# ------------------------------------------------------------------ main
if __name__ == '__main__':
    F, blocks, park = build_scene()
    outdir = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'cache', 'render')
    cfg = dict(W=412, H=915, target=(4, 0, -8), yaw=12, pitch=42, dist=200, fov=36, cy=500)
    if len(sys.argv) > 1: cfg.update(json.loads(sys.argv[1]))
    for th, nm in ((NOON, 'living_noon'), (GOLDEN, 'living_day'), (NIGHT, 'living_night')):
        svg, anc, cam = render(th, cfg, F, blocks, park)
        open(os.path.join(outdir, nm + '.svg'), 'w').write(svg)
        json.dump(anc, open(os.path.join(outdir, nm + '.json'), 'w'), indent=1, default=str)
        print(nm, len(svg) // 1024, 'KB', {k: v[:2] if isinstance(v, list) else v for k, v in anc.items()})
    json.dump(F, open(os.path.join(outdir, 'living_features.json'), 'w'), default=str)
