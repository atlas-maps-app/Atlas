#!/usr/bin/env python3
"""Honest living map, Amsterdam. Draws only what OpenStreetMap says.

Rules
- Every drawn detail is the value of a real OSM tag on a mapped object. When the tag is missing, a generic
  value is guessed for that same tag (building:levels, building:colour, building:material, roof:shape,
  roof:height, roof:material, height and diameter_crown for trees) and recorded as guessed, so it can be
  filled in. The 'truth' lens draws every guessed value in plain white.
- Nothing is drawn that is not an OSM tag: no cars, no people, no lit windows, no invented objects.
- building:part replaces its outline (the Westerkerk, the Westertoren).
- At night only mapped street lamps, roads tagged lit=yes and places open by opening_hours give light.
"""
import json, math, random, datetime, collections, os, sys, re
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from city import Camera, vnorm, vdot, vsub, vcross, hx, tohex, mix, mulc, clamp, P, area, ribbon, rect, hull2
import living as LV
import closeup as CU
from real import signed_area, pip, centroid, num, open_at, Shadows

LAT0, LON0 = 52.3746, 4.8840          # Westerkerk
KX = 111320 * math.cos(math.radians(LAT0)); KZ = 110540
def XZ(lat, lon): return ((lon - LON0) * KX, -(lat - LAT0) * KZ)

TZ_H = 2                              # the place's clock against UTC on these dates (CEST here; each region sets its own)
def sun_position(t_utc, lat=None, lon=None):
    lat = LAT0 if lat is None else lat; lon = LON0 if lon is None else lon          # the place drawn, read at the call (regions move it)
    jd = (t_utc - datetime.datetime(2000, 1, 1, 12)).total_seconds() / 86400
    L = (280.46 + 0.9856474 * jd) % 360
    g = math.radians((357.528 + 0.9856003 * jd) % 360)
    lam = math.radians(L + 1.915 * math.sin(g) + 0.020 * math.sin(2 * g))
    eps = math.radians(23.439 - 0.0000004 * jd)
    ra = math.atan2(math.cos(eps) * math.sin(lam), math.cos(lam))
    dec = math.asin(math.sin(eps) * math.sin(lam))
    gmst = (280.46061837 + 360.98564736629 * jd) % 360
    ha = math.radians((gmst + lon) % 360) - ra
    la = math.radians(lat)
    el = math.asin(math.sin(la) * math.sin(dec) + math.cos(la) * math.cos(dec) * math.cos(ha))
    az = math.atan2(-math.sin(ha), math.tan(dec) * math.cos(la) - math.sin(la) * math.cos(ha))
    return math.degrees(az) % 360, math.degrees(el)

MATERIAL = {'brick': '#9C5A45', 'stone': '#CFC6B4', 'copper': '#5E9E86', 'glass': '#A9C2D6', 'concrete': '#BDBAB2', 'wood': '#9C7A55',
            'roof_tiles': '#A5553C', 'slate': '#56606A', 'metal': '#9AA3AB', 'plaster': '#E6E0D4', 'sandstone': '#D8C49A'}
CLAY = '#E4E0D8'          # neutral: surface exists, its look is not mapped
YEAR_RAMP = [(1600, '#5B2A86'), (1700, '#8E3B8F'), (1800, '#C4507A'), (1900, '#E8805A'), (1950, '#F2B45A'), (2000, '#F6E27A')]

def year_colour(y):
    if y is None: return None
    for (y0, c0), (y1, c1) in zip(YEAR_RAMP, YEAR_RAMP[1:]):
        if y <= y1: return tohex(mix(hx(c0), hx(c1), clamp((y - y0) / (y1 - y0))))
    return YEAR_RAMP[-1][1]

LOOKS = os.environ.get('ATLAS_LOOKS') == '1'      # looks from each building's tags (written under _looks names)
KIT = os.environ.get('ATLAS_KIT') == '1'          # Amsterdam from its tradition profile and the shared kit (written under _kit names)

def load(raw_path=None):
    raw = json.load(open(raw_path or os.path.join(HERE, '..', 'data', 'ams', 'raw.json')))['elements']
    S = dict(buildings=[], parts=[], water=[], roads=[], rails=[], areas=[], trees=[], nodes=[], pois=[], labels=[])
    def rings(e):
        if e['type'] == 'way' and 'geometry' in e: return [[XZ(g['lat'], g['lon']) for g in e['geometry']]]
        if e['type'] == 'relation': return [[XZ(g['lat'], g['lon']) for g in m['geometry']] for m in e.get('members', []) if m.get('role') == 'outer' and m.get('geometry')]
        return []
    def closed(r): return len(r) > 3 and math.dist(r[0], r[-1]) < 0.5
    def clean(r):
        r = r[:-1] if closed(r) else r
        return r if signed_area(r) > 0 else r[::-1]
    for e in raw:
        t = e.get('tags')
        if not t: continue
        if ('building' in t or 'building:part' in t) and e['type'] != 'node' and t.get('building') != 'no':
            for r in rings(e):
                if not closed(r) or abs(signed_area(r)) < 3: continue
                r = clean(r)
                h = num(t.get('height')); lv = num(t.get('building:levels'))
                src = 'height' if h is not None else ('levels' if lv is not None else None)
                if h is None and lv is not None: h = lv * 3.0 + num(t.get('roof:levels'), 0) * 2.0
                y = None
                m = re.match(r'\s*(\d{4})', t.get('start_date', ''))
                if m: y = int(m.group(1))
                rec = dict(poly=r, h=h, min=num(t.get('min_height'), 0.0), src=src, year=y, tags=t, id=e['id'],
                           roof=t.get('roof:shape'), roof_h=num(t.get('roof:height')), orient=t.get('roof:orientation'),
                           mat=t.get('building:material'), rmat=t.get('roof:material'), col=t.get('building:colour'), rcol=t.get('roof:colour'))
                (S['parts'] if 'building:part' in t else S['buildings']).append(rec)
            continue
        if (t.get('natural') == 'water' or t.get('waterway') in ('canal', 'riverbank', 'dock')) and e['type'] != 'node':
            for r in rings(e):
                if closed(r): S['water'].append(clean(r))
            if t.get('name') and t.get('waterway') == 'canal' and e['type'] == 'way' and not closed([XZ(g['lat'], g['lon']) for g in e.get('geometry', [])] or [(0, 0)]):
                S['labels'].append(dict(pts=[XZ(g['lat'], g['lon']) for g in e['geometry']], name=t['name'], kind='canal'))
            continue
        if e['type'] == 'way' and 'highway' in t and 'geometry' in e:
            pts = [XZ(g['lat'], g['lon']) for g in e['geometry']]
            hw = t['highway']
            W = {'tertiary': 7.5, 'secondary': 9, 'residential': 5.5, 'unclassified': 5, 'living_street': 4.5, 'pedestrian': 5, 'busway': 6, 'service': 3.5,
                 'cycleway': 2.4, 'footway': 2.0, 'path': 1.6, 'steps': 2.0}.get(hw)
            if W is None: continue
            if pts[0] == pts[-1] and (t.get('area') == 'yes' or hw == 'pedestrian'):
                S['areas'].append(dict(poly=clean(pts + [pts[0]]) if len(pts) > 3 else pts, kind='pedestrian', tags=t))
            else:
                w = num(t.get('width'), W)
                S['roads'].append(dict(pts=pts, w=w, hw=hw, tags=t, bridge=bool(t.get('bridge')), layer=num(t.get('layer'), 0), id=e['id'], wguess=not t.get('width')))
                if t.get('name') and hw in ('residential', 'tertiary', 'pedestrian', 'living_street', 'unclassified'):
                    S['labels'].append(dict(pts=pts, name=t['name'], kind='street'))
            continue
        if e['type'] == 'way' and t.get('railway') == 'tram' and 'geometry' in e:
            S['rails'].append([XZ(g['lat'], g['lon']) for g in e['geometry']]); continue
        if e['type'] != 'node' and (t.get('leisure') in ('park', 'garden', 'playground') or t.get('landuse') in ('grass',) or t.get('man_made') == 'bridge' or t.get('amenity') == 'parking' or t.get('place') == 'square' or t.get('historic') == 'memorial'):
            for r in rings(e):
                if closed(r): S['areas'].append(dict(poly=clean(r), kind=t.get('leisure') or t.get('landuse') or t.get('man_made') or t.get('amenity') or t.get('place') or 'memorial', tags=t))
            continue
        if e['type'] == 'node':
            x, z = XZ(e['lat'], e['lon'])
            n = dict(x=x, z=z, tags=t, id=e['id'])
            if t.get('natural') == 'tree': S['trees'].append(n)
            S['nodes'].append(n)
            if 'shop' in t or t.get('amenity') in ('restaurant', 'cafe', 'bar', 'pub', 'fast_food', 'ice_cream', 'pharmacy') or t.get('tourism') in ('museum', 'hotel', 'gallery'):
                S['pois'].append(n)
    guess_tags(S)
    if KIT and raw_path is None:
        # Amsterdam from its own tradition (render/traditions/nl_amsterdam.json) and the shared kit: chimneys, tile courses, lit ridges
        import generic as GN
        lu = [(a['tags'].get('landuse'), a['poly']) for a in S['areas'] if a.get('tags', {}).get('landuse') in GN.LANDUSE_KIND and len(a['poly']) > 2]
        def landuse_at(b):
            cx, cz = centroid(b['poly'])
            return next((k for k, poly in lu if pip(cx, cz, poly)), None)
        S['tradition'] = 'nl_amsterdam'
        GN.apply(S, 'ams', landuse_at)
    elif LOOKS and raw_path is None:
        # Amsterdam with looks from each building's tags: homes keep their varied brick, everything else follows its type
        import generic as GN
        lu = [(a['tags'].get('landuse'), a['poly']) for a in S['areas'] if a.get('tags', {}).get('landuse') in GN.LANDUSE_KIND and len(a['poly']) > 2]
        def landuse_at(b):
            cx, cz = centroid(b['poly'])
            return next((k for k, poly in lu if pip(cx, cz, poly)), None)
        GN.apply(S, 'ams', landuse_at, keep_homes=True)
    import poi_style as PS
    grid = collections.defaultdict(list)
    for b in S['buildings'] + S['parts']:
        xs = [p[0] for p in b['poly']]; zs = [p[1] for p in b['poly']]
        b['_bb'] = (min(xs), max(xs), min(zs), max(zs))
        for gx in range(int(min(xs) // 40), int(max(xs) // 40) + 1):
            for gz in range(int(min(zs) // 40), int(max(zs) // 40) + 1): grid[(gx, gz)].append(b)
    for n in S['nodes']:
        st = PS.style(n['tags'])
        if not st: continue
        n['style'] = st
        for b in grid.get((int(n['x'] // 40), int(n['z'] // 40)), ()):
            x0, x1, z0, z1 = b['_bb']
            if x0 <= n['x'] <= x1 and z0 <= n['z'] <= z1 and 'building:part' not in b['tags'] and pip(n['x'], n['z'], b['poly']):
                b.setdefault('places', []).append(n); n['_b'] = b; break
    # an outline with parts inside is represented by its parts
    for b in S['buildings']:
        b['hidden'] = any(pip(*centroid(p['poly']), b['poly']) for p in S['parts'] if abs(centroid(p['poly'])[0] - centroid(b['poly'])[0]) < 80 and abs(centroid(p['poly'])[1] - centroid(b['poly'])[1]) < 80)
    return S

BRICK_OLD = ['#6E3B2E', '#7A4434', '#5C3A30', '#8A5040', '#6A4A3C', '#7E5242']
PLASTER = ['#E8E2D4', '#EFE9DC', '#D9D2C2']
BRICK_NEW = ['#9C6A52', '#8F7F74', '#B08A6A', '#8C8A86', '#A9876E']
def guess_tags(S):
    """Generic values for missing OSM tags, recorded per object as guessed."""
    for b in S['buildings'] + S['parts']:
        g = {}
        rnd = random.Random(b['id'])
        xs = [p[0] for p in b['poly']]; zs = [p[1] for p in b['poly']]
        edges = sorted(math.dist(b['poly'][i], b['poly'][(i + 1) % len(b['poly'])]) for i in range(len(b['poly'])))
        narrow = edges[0] < 11 if len(edges) >= 3 else False
        old = (b['year'] or 1900) < 1900
        if b['h'] is None:
            g['height'] = True
            b['h'] = 10.0 if b['tags'].get('building') != 'houseboat' else 2.5
        if 'building:part' not in b['tags']:
            if b['tags'].get('building:levels') is None:
                g['building:levels'] = True
            if not b['roof']:
                g['roof:shape'] = True
                b['roof'] = 'gabled' if (old and narrow and b['tags'].get('building') not in ('church', 'houseboat', 'industrial', 'roof', 'shed') and b['h'] > 8) else 'flat'
                if b['roof'] == 'gabled':
                    b['roof_h'] = min(5.0, max(3.0, edges[0] * 0.55)); g['roof:height'] = True
                    b['orient'] = 'along'
            if not b['col'] and not b['mat']:
                g['building:colour'] = True
                r = rnd.random()
                if b['tags'].get('building') == 'houseboat': b['col'] = rnd.choice(['#2F4A3A', '#3A4A6A', '#6A3A2E', '#E8E2D4'])
                elif old: b['col'] = rnd.choice(PLASTER) if r < 0.18 else rnd.choice(BRICK_OLD)
                else: b['col'] = rnd.choice(BRICK_NEW)
                b['mat'] = None
                if b['tags'].get('building') != 'houseboat':          # the colour comes with a material: brick, or plaster for the pale ones
                    b['mat_draw'] = 'plaster' if b['col'] in PLASTER else 'brick'; g['building:material'] = True
            if not b['rcol'] and not b['rmat']:
                g['roof:colour'] = True
                b['rcol'] = rnd.choice(['#4A3E3A', '#56504C', '#3E4248']) if b['roof'] == 'gabled' else rnd.choice(['#7E7A74', '#8C8882', '#6E6A66'])
        if b['roof'] in ('gabled', 'hipped', 'pyramidal', 'skillion', 'quadruple_saltbox', 'mansard') and not b['roof_h']:
            rl = num(b['tags'].get('roof:levels'))
            b['roof_h'] = min(b['h'] * 0.4, rl * 2.8 if rl else max(2.0, min(5.0, edges[0] * 0.45))); g['roof:height'] = True
        if b['roof'] == 'skillion' and not b['tags'].get('roof:direction'): g['roof:direction'] = True
        b['levels'] = int(num(b['tags'].get('building:levels'))) if b['tags'].get('building:levels') else max(1, int(round((b['h'] - (b['roof_h'] or 0)) / 3.1)))
        b['guessed'] = g
        if b.get('mat') and not b.get('mat_draw'): b['mat_draw'] = b['mat']
    for n in S['trees']:
        n['guessed'] = {k: True for k in ('height', 'diameter_crown', 'leaf_type') if k not in n['tags']}   # every tree drawn is broadleaved, as all mapped ones here are
        n['h'] = num(n['tags'].get('height'), 12.0); n['crown'] = num(n['tags'].get('diameter_crown'), 7.0)

THEMES = {
    'golden': dict(name='golden', date=(2026, 9, 30), hhmm=(17, 20), night=False, sky_top='#86A7D6', sky_hor='#F6D9B8', haze='#F3DCC0', haze_max=0.3,
                   sun='#FFD9A6', sun_k=1.0, amb='#8FA0C8', amb_k=0.62, ground='#E9E4DA', road='#CFC8BC', water='#7FA7C7', shadow='#27305A', shadow_op=0.34),
    'noon': dict(name='noon', date=(2026, 9, 30), hhmm=(13, 30), night=False, sky_top='#7FA6DA', sky_hor='#DCE7F2', haze='#DDE6EE', haze_max=0.4,
                 sun='#FFF3DE', sun_k=0.92, amb='#9AAAD0', amb_k=0.64, ground='#ECE8E0', road='#D2CCC2', water='#84AED0', shadow='#2B3560', shadow_op=0.3),
    'night': dict(name='night', date=(2026, 9, 30), hhmm=(21, 30), night=True, sky_top='#050814', sky_hor='#1C2340', haze='#161D36', haze_max=0.5,
                  sun='#000000', sun_k=0.0, amb='#39456B', amb_k=0.5, ground='#12151F', road='#1B1F2C', water='#0B1422', shadow='#000', shadow_op=0.0),
}
# Real weather from Open-Meteo's hourly model for Westermarkt (data/wx/ams.json) sets the light, the water and the rain.
WATER_Y = -1.5                      # canal water sits below the quay; a generic detail, as no tag gives water level here
QUAY_COL = '#6E5546'                 # Amsterdam's brick quays; other regions set their own
QUAY_COURSE = 0.0625                 # the quay wall's courses up close: brick here, stone blocks in Paris, none for concrete
DAYLIGHT = os.environ.get('ATLAS_DAYLIGHT') == '1'      # light that falls with the sun's height towards night (the day frames)
TONE = False                         # a soft shoulder on highlights, so pale stone in sun stays cream instead of clipping to white
WIN_STYLE = None                     # 'paris': stone fronts get tall French windows in bays of about 2.9 m, dark glass
WXD = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'data', 'wx', 'ams.json')))['hourly']
def wx_at(date, hh, mm):
    i = WXD['time'].index('%04d-%02d-%02dT%02d:00' % (date[0], date[1], date[2], hh)); j = min(i + 1, len(WXD['time']) - 1); f = mm / 60.0
    g = lambda k: WXD[k][i] * (1 - f) + WXD[k][j] * f
    n = i if f < 0.5 else j
    return dict(cloud=g('cloud_cover') / 100, low=g('cloud_cover_low') / 100, rain=g('precipitation'), wind=g('wind_speed_10m'), gust=g('wind_gusts_10m'),
                wind_dir=WXD['wind_direction_10m'][n], temp=g('temperature_2m'), pressure=g('pressure_msl'), vis=g('visibility'), hum=g('relative_humidity_2m'), code=WXD['weather_code'][n])
def weather_theme(name, date, hhmm):
    w = wx_at(date, *hhmm)
    az, el = sun_position(datetime.datetime(*date, *hhmm) - datetime.timedelta(hours=TZ_H))
    night = el < -2
    th = dict(name=name, date=date, hhmm=hhmm, night=night, wx=w)
    if night:
        wet = w['rain'] > 0.05
        th.update(sky_top='#050814', sky_hor='#1C2340', haze='#1A2238', haze_max=0.5, sun='#000000', sun_k=0.0, amb='#3A4668', amb_k=0.5,
                  ground='#0D1018' if wet else '#12151F', road='#131824' if wet else '#1B1F2C', water='#070C14', shadow='#000', shadow_op=0.0,
                  water_top='#131B2A', water_bot='#03060B')
    elif w['cloud'] >= 0.85:          # overcast: light from the whole sky, shadows soft and faint
        th.update(sky_top='#9EA8B4', sky_hor='#C9CED4', haze='#CDD3DA', haze_max=0.4, sun='#FFF6EA', sun_k=0.14, amb='#B4BCC8', amb_k=0.84, sky_k=1.7,
                  ground='#C9C4BA', road='#BBB5AB', water='#6E7C7C', shadow='#353B4C', shadow_op=0.16, shadow_blur=2.6,
                  water_top='#AEB7BC', water_bot='#4B5A57')
    else:                             # sun between clouds: hard shadows, and the clouds' own shadows drifting over
        th.update(sky_top='#7FA6DA', sky_hor='#DCE7F2', haze='#DFE6EE', haze_max=0.22, sun='#FFEBCB', sun_k=1.0, amb='#93A6CC', amb_k=0.6,
                  ground='#D6CFC2', road='#C9C1B4', water='#4F7590', shadow='#27305A', shadow_op=0.32, shadow_blur=0.6,
                  water_top='#A6BFD4', water_bot='#2D506C', cloud_shadows=w['cloud'] if w['cloud'] > 0.15 else 0)
    th['wet'] = w['rain'] > 0.05
    th['haze_max'] *= 1.2 if w['vis'] < 20000 else 0.85
    if DAYLIGHT and not night and el < 8:
        # the light of the hour falls with the sun: from 8 degrees down to the start of night everything dims and cools,
        # the sky towards the blue of twilight (the palette eases into the night one, so the day runs on without a jump)
        f = min(1.0, max(0.0, (8 - el) / 10.0)) ** 1.3
        dusk = dict(sky_top='#1E2A48', sky_hor='#5A6584', haze='#4A5470', amb='#56607E', ground='#565660', road='#4C4D57', water='#1E2A3C', water_top='#3C4860', water_bot='#101824')
        for k_, v_ in dusk.items():
            if k_ in th: th[k_] = tohex(mix(hx(th[k_]), hx(v_), f))
        th['amb_k'] = th['amb_k'] * (1 - 0.45 * f); th['sun_k'] = th['sun_k'] * (1 - 0.8 * f)
        if 'sky_k' in th: th['sky_k'] = th['sky_k'] * (1 - 0.55 * f)
        th['shadow_op'] = th['shadow_op'] * (1 - 0.6 * f)
        th['_dusk'] = round(f, 2)
    return th
THEMES['grey'] = weather_theme('grey', (2026, 10, 1), (13, 30))
THEMES['sunny'] = weather_theme('sunny', (2026, 10, 1), (16, 0))
THEMES['wet'] = weather_theme('wet', (2026, 9, 30), (21, 30))
SURF = {'paving_stones': '#C9B9A6', 'sett': '#A89A88', 'asphalt': '#9B9A99', 'concrete': '#C4C2BC', 'wood': '#A88A62', 'bricks': '#B98870', 'cobblestone': '#A89A88'}

def light(th, base, n, lit):
    L = th['_L']
    if th['night']:
        c = mulc(mix(base, hx(th['amb']), 0.55), 0.4 + 0.08 * max(0.0, n[1]))
        return mix(c, hx(th['haze']), th['_hz']) if th.get('_hz') else c
    amb = mix(mulc(base, th['amb_k']), mulc(hx(th['amb']), th['amb_k'] * 0.55), 0.26 if TONE else 0.35)
    lam = max(0.0, vdot(n, L)) if lit else 0.0
    sky = (0.18 + 0.12 * max(0.0, n[1])) * th.get('sky_k', 1.0)
    c = tuple(amb[i] + base[i] * sky + base[i] * lam * 1.05 * th['sun_k'] * hx(th['sun'])[i] / 255 for i in range(3))
    if TONE: c = tuple(v if v < 205 else 205 + 50 * (1 - math.exp(-(v - 205) / 50)) for v in c)
    return mix(c, hx(th['haze']), th['_hz']) if th.get('_hz') else c

AIR_HAZE = True           # per-object aerial perspective at eye level, from the hour's visibility
def haze_at(th, d):
    """Aerial perspective at eye level: how much air lies between you and a thing d metres away, from the real visibility of the hour."""
    if not th.get('_eye') or not th.get('wx') or not AIR_HAZE: return 0.0
    ve = max(800.0, th['wx'].get('vis', 20000) / 8)
    return min(0.75, 1 - math.exp(-3.912 * max(0.0, d - 15) / ve))

def hzc(th, col):
    return tohex(mix(hx(col), hx(th['haze']), th['_hz'])) if th.get('_hz') else col

CLOUDS_LIT = False        # relief-lit clouds at height: switched off, the soft flat clouds read better
def sky_layer(cam, th, W, H, hy, defs, layer=None):
    """The sky above the horizon from the real cloud cover: overcast as a heavy grey deck, broken cloud as white heaps, night as low cloud lit from below."""
    w = th['wx']; night = th['night']; L = th['_L']; over = th.get('sky_k'); out = []
    hb = min(H, hy + 2)
    if night:
        k, t, rgb = 1.5, -0.1, (0.17, 0.15, 0.19)
    elif over:
        k, t, rgb = 2.2, 0.18, (0.66, 0.68, 0.72)
    else:
        k, t, rgb = 9.0, 0.62 - w['cloud'] * 0.25, (0.98, 0.98, 0.99)
    defs.append('<filter id="cloudf" filterUnits="userSpaceOnUse" x="0" y="0" width="%d" height="%d"><feTurbulence type="fractalNoise" baseFrequency="0.0055 0.017" numOctaves="4" seed="31"/><feColorMatrix type="matrix" values="0 0 0 0 %.2f  0 0 0 0 %.2f  0 0 0 0 %.2f  %.1f 0 0 0 %.2f"/></filter>' % (W, hb, rgb[0], rgb[1], rgb[2], k, -k * t))
    defs.append('<filter id="cloudsh2" filterUnits="userSpaceOnUse" x="0" y="0" width="%d" height="%d"><feTurbulence type="fractalNoise" baseFrequency="0.0055 0.017" numOctaves="4" seed="31"/><feOffset dy="%d"/><feColorMatrix type="matrix" values="0 0 0 0 %.2f  0 0 0 0 %.2f  0 0 0 0 %.2f  %.1f 0 0 0 %.2f"/></filter>' % (W, hb, 5, rgb[0] * 0.62, rgb[1] * 0.64, rgb[2] * 0.7, k, -k * (t + 0.04)))
    if not night and L[1] > 0:
        sp = cam.proj((cam.C[0] + L[0] * 4000, cam.C[1] + L[1] * 4000, cam.C[2] + L[2] * 4000))
        if sp and -260 < sp[0] < W + 260 and -260 < sp[1] < hy + 60:
            defs.append('<radialGradient id="sunglow"><stop offset="0" stop-color="#FFF8E6" stop-opacity="%s"/><stop offset=".25" stop-color="#FFE9C2" stop-opacity="%s"/><stop offset="1" stop-color="#FFE0B0" stop-opacity="0"/></radialGradient>' % (('.75', '.25') if over else ('1', '.45')))
            th['_sunxy'] = sp
    if th.get('_eye') and CLOUDS_LIT:
        # the sun first, so the clouds can pass in front of it; then a little of its glare back over them
        if th.get('_sunxy'):
            sx, sy = th['_sunxy']
            out.append('<circle cx="%.1f" cy="%.1f" r="%d" fill="url(#sunglow)"/>' % (sx, sy, 230 if over else 170))
            if not over: out.append('<circle cx="%.1f" cy="%.1f" r="9" fill="#FFFDF4"/>' % (sx, sy))
        if layer != 'front': out.append(CU.sky_clouds(cam, th, W, hy, defs, cam.C[1]))
        if th.get('_sunxy'):
            out.append('<circle cx="%.1f" cy="%.1f" r="%d" fill="url(#sunglow)" opacity=".55" style="mix-blend-mode:screen"/>' % (sx, sy, 150 if over else 120))
    else:
        out.append('<rect width="%d" height="%.1f" filter="url(#cloudsh2)"/><rect width="%d" height="%.1f" filter="url(#cloudf)"/>' % (W, hb, W, hb))
        if th.get('_sunxy'):
            sx, sy = th['_sunxy']
            out.append('<circle cx="%.1f" cy="%.1f" r="%d" fill="url(#sunglow)"/>' % (sx, sy, 230 if over else 170))
            if not over: out.append('<circle cx="%.1f" cy="%.1f" r="9" fill="#FFFDF4"/>' % (sx, sy))
    if night and layer != 'front':      # the city lights the underside of the low cloud
        defs.append('<linearGradient id="cityglow" gradientUnits="userSpaceOnUse" x1="0" y1="%.0f" x2="0" y2="%.0f"><stop offset="0" stop-color="#FF9E5A" stop-opacity="0"/><stop offset="1" stop-color="#FF9E5A" stop-opacity=".28"/></linearGradient>' % (hy - 220, hy))
        out.append('<rect width="%d" height="%.1f" fill="url(#cityglow)"/>' % (W, hb))
    return ''.join(out)

def render_sky(thn, cfg):
    """The sky for an eye-level view, 2W wide: gradient, clouds at height (tiling every W px), and at night the city's glow on the cloud."""
    th = dict(THEMES[thn]); W, H = cfg['W'], cfg['H']
    th['_eye'] = True
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], W, H, cx=cfg.get('cx'), cy=cfg.get('cy'))
    hh, mm = th['hhmm']; y_, mo_, dd_ = th['date']
    az, el = sun_position(datetime.datetime(y_, mo_, dd_, hh, mm) - datetime.timedelta(hours=TZ_H))
    th['_L'] = LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
    hy = cam.horizon_y(); defs = []
    defs.append('<linearGradient id="sky" gradientUnits="userSpaceOnUse" x1="0" y1="%.0f" x2="0" y2="%.0f"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (hy - 300, hy, th['sky_top'], th['sky_hor']))
    body = ['<rect width="%d" height="%d" fill="url(#sky)"/>' % (2 * W, H), CU.sky_clouds(cam, th, W, hy, defs, cam.C[1], tile=True)]
    if th['night']:
        defs.append('<linearGradient id="cityglow" gradientUnits="userSpaceOnUse" x1="0" y1="%.0f" x2="0" y2="%.0f"><stop offset="0" stop-color="#FF9E5A" stop-opacity="0"/><stop offset="1" stop-color="#FF9E5A" stop-opacity=".28"/></linearGradient>' % (hy - 220, hy))
        body.append('<rect width="%d" height="%.1f" fill="url(#cityglow)"/>' % (2 * W, min(H, hy + 2)))
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d"><defs>%s</defs>%s</svg>' % (2 * W, H, 2 * W, H, ''.join(defs), ''.join(body))

def water_edges(S):
    """Every water edge with the unit normal that points into the water. Islands (holes in a river's outline) have quays too,
    facing out into the water around them; they stand at the level of the water around them."""
    out = []
    holes = [(h, True) for h in S.get('water_holes', [])]
    for r, hole in [(r, False) for r in S['water']] + holes:
        if not hole and S.get('natural_shore') and id(r) in S['natural_shore']: continue      # beaches and lake shores: no quay wall
        n = len(r)
        for i in range(n - 1 if r[0] == r[-1] else n):
            a, b = r[i], r[(i + 1) % n]
            ex, ez = b[0] - a[0], b[1] - a[1]; Lg = math.hypot(ex, ez)
            if Lg < 0.4: continue
            nx, nz = ez / Lg, -ex / Lg
            mx, mz = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
            if pip(mx + nx * 0.4, mz + nz * 0.4, r) == hole: nx, nz = -nx, -nz      # into the water: inside a river, outside an island
            if hole:
                if any(pip(mx - nx * 1.5, mz - nz * 1.5, o) for o in S['water'] if o is not r and abs(o[0][0] - mx) < 4000 and not pip(mx - nx * 1.5, mz - nz * 1.5, r)): continue
                out.append((a, b, nx, nz, S.get('_hole_of', {}).get(id(r), r))); continue
            if S.get('natural_shore') is not None and any(o is not r and pip(mx - nx * 1.5, mz - nz * 1.5, o) for o in S['water']): continue   # water on both sides: no wall
            out.append((a, b, nx, nz, r))
    return out

def near_water(edges, pts, d):
    for (a, b, *_ ) in edges:
        if min(a[0], b[0]) - d > max(p[0] for p in pts) or max(a[0], b[0]) + d < min(p[0] for p in pts): continue
        if min(a[1], b[1]) - d > max(p[1] for p in pts) or max(a[1], b[1]) + d < min(p[1] for p in pts): continue
        for p in pts:
            ux, uz = b[0] - a[0], b[1] - a[1]; L2 = ux * ux + uz * uz or 1
            t = max(0, min(1, ((p[0] - a[0]) * ux + (p[1] - a[1]) * uz) / L2))
            if math.hypot(a[0] + ux * t - p[0], a[1] + uz * t - p[1]) < d: return True
    return False

def on_water(S, x, z):
    return any(pip(x, z, r) for r in S['water'])

def reflections(S, cam, th, sh, far, edges):
    """The scene mirrored in the water plane: facades, gables, window rows and trees beside the canals."""
    WY = WATER_Y; night = th['night']; L = th['_L']; out = []
    for b in S['buildings'] + S['parts']:
        if b.get('hidden') or not b['h']: continue
        cx, cz = centroid(b['poly'])
        if far(cx, cz) or not near_water(edges, b['poly'], 16): continue
        boat = b['tags'].get('building') == 'houseboat'
        y0 = WY + 0.3 if boat else b['min']; h = (b['h'] + WY + 0.3) if boat else b['h']
        rh = b['roof_h'] if b['roof'] in ('gabled', 'hipped', 'skillion', 'quadruple_saltbox', 'pyramidal') and b['roof_h'] else 0
        top = h - rh
        base = surface_colour(b, None); poly = b['poly']; n = len(poly)
        dk = 0.5 if night else (0.62 if th.get('sky_k') else 0.74)     # facades read darker than the bright sky the water mirrors
        c3 = cam.cam((cx, 2 * WY - (y0 + h) / 2, cz))
        if c3[2] < 2: continue
        th['_hz'] = haze_at(th, c3[2])
        wins = []
        for i in range(n):
            a, bb = poly[i], poly[(i + 1) % n]
            ex, ez = bb[0] - a[0], bb[1] - a[1]; elen = math.hypot(ex, ez)
            if elen < 0.3: continue
            nrm = vnorm((ez, 0, -ex))
            mid = ((a[0] + bb[0]) / 2, 2 * WY - (y0 + top) / 2, (a[1] + bb[1]) / 2)
            if vdot(nrm, vsub(cam.C, mid)) <= 0: continue
            lit = (not night) and vdot(nrm, L) > 0 and sh.lit(((a[0] + bb[0]) / 2 + nrm[0] * .2, (y0 + top) / 2, (a[1] + bb[1]) / 2 + nrm[2] * .2), skip=b)
            col = tohex(mulc(light(th, base, nrm, lit), dk))
            q = [(a[0], 2 * WY - y0, a[1]), (bb[0], 2 * WY - y0, bb[1]), (bb[0], 2 * WY - top, bb[1]), (a[0], 2 * WY - top, a[1])]
            out.append((c3[2], face(cam, q, col, ' stroke="%s" stroke-width=".3"' % col)))
            if rh and b['roof'] == 'gabled':          # a gable end mirrors as a downward triangle
                lx, lz = max(((math.dist(poly[k], poly[(k + 1) % n]), k) for k in range(n)))[1], 0
                ra, rb_ = poly[lx], poly[(lx + 1) % n]
                rx, rz = rb_[0] - ra[0], rb_[1] - ra[1]; rl = math.hypot(rx, rz) or 1
                if b['orient'] == 'across': rx, rz = -rz, rx
                if abs(ex * rx + ez * rz) / (elen * rl) < 0.35:
                    ap = (mid[0], 2 * WY - h, mid[2])
                    out.append((c3[2] - 0.01, face(cam, [q[3], q[2], ap], col)))
            if not boat and 'building:part' not in b['tags'] and elen > 2.2 and c3[2] < 520:
                lv = b['levels']; fh = (top - y0) / max(1, lv)
                for r_ in range(lv):
                    ya = y0 + r_ * fh + fh * 0.3; yb = ya + fh * 0.48
                    sp = cam.poly(LV.quad_at(a, bb, 2 * WY - yb, 2 * WY - ya, 0.08, 0.92, 0.04, nrm), margin=300)
                    if sp and area(sp) > 0.3: wins.append(P(sp))
        if wins: out.append((c3[2] - 0.02, '<path d="%s" fill="%s" opacity=".55"/>' % (''.join(wins), '#2B3440' if not night else '#05070C')))
    for t in S['trees']:
        if far(t['x'], t['z']) or not near_water(edges, [(t['x'], t['z'])], 9): continue
        c = cam.cam((t['x'], 2 * WY - t['h'] * 0.68, t['z']))
        if c[2] < 2: continue
        if c[2] < 30: continue
        p = cam.pc(c); rr = t['crown'] / 2 * 0.9 * cam.focal / c[2]
        col = '#3E5A36' if not night else '#0A0F0D'
        rnd_t = random.Random(t['id'])
        out.append((c[2], '<g fill="%s" filter="url(#leaf)">%s</g>' % (col, ''.join('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0] + rnd_t.uniform(-.5, .5) * rr, p[1] + rnd_t.uniform(-.4, .4) * rr, rr * rnd_t.uniform(.45, .7)) for _ in range(5)))))
    out.sort(key=lambda x: -x[0])
    res = [s_ for _, s_ in out]
    if night:      # the lamps and the lit streets, as long streaks on the dark water
        rnd_s = random.Random(5)
        def streak(x, yl, z, k):
            mx_, my_, mz_ = x, 2 * WY - yl, z
            C = cam.C; dy = my_ - C[1]
            if dy >= 0: return
            t_ = (WY - C[1]) / dy
            sx, sz = C[0] + (mx_ - C[0]) * t_, C[2] + (mz_ - C[2]) * t_
            if not on_water(S, sx, sz): return
            c = cam.cam((mx_, my_, mz_))
            if c[2] < 2: return
            p = cam.pc(c); s_ = cam.focal / c[2]
            if not (-20 < p[0] < cam.W + 20 and -40 < p[1] < cam.H + 40): return
            j = rnd_s.random()
            res.append('<ellipse cx="%.1f" cy="%.1f" rx="%.2f" ry="%.1f" fill="url(#streak)" opacity="%.2f"/>' % (p[0] + rnd_s.uniform(-1, 1) * s_, p[1] + (3 + 3 * j) * s_, max(0.5, rnd_s.uniform(0.35, 0.8) * s_), max(2.5, (4 + 6 * j) * s_), k * rnd_s.uniform(0.45, 1)))
        for nd in S['nodes']:
            if nd['tags'].get('highway') == 'street_lamp' and not far(nd['x'], nd['z']): streak(nd['x'], 4.5, nd['z'], 0.95)
        for r in S['roads']:
            if r['tags'].get('lit') != 'yes': continue
            pts = r['pts']
            for i in range(len(pts) - 1):
                a, b = pts[i], pts[i + 1]; Lg = math.dist(a, b); k = 4.0
                while k < Lg:
                    x, z = a[0] + (b[0] - a[0]) * k / Lg, a[1] + (b[1] - a[1]) * k / Lg
                    if not far(x, z) and near_water(edges, [(x, z)], 12): streak(x, 5.5, z, 0.7)
                    k += 13.0 + (k * 7.3) % 6
    return res

def water_layer(S, cam, th, sh, far, W, H, defs, edges):
    WY = WATER_Y; night = th['night']; w = th['wx']; L = th['_L']
    r0, r1 = [], []
    def wound(sp, hole):
        # nonzero winding: every water area turns one way, every island the other, so overlaps stay water and islands stay land
        a2 = sum(sp[i][0] * sp[(i + 1) % len(sp)][1] - sp[(i + 1) % len(sp)][0] * sp[i][1] for i in range(len(sp)))
        return sp if (a2 > 0) != hole else sp[::-1]
    holes = S.get('water_holes', [])
    lvl = S.get('water_y') or {}
    def wy_of(r, hole):
        if not hole: return lvl.get(id(r), WY)
        for o in S['water']:                      # an island sits at the level of the water around it
            if id(o) in lvl and pip(r[0][0], r[0][1], o): return lvl[id(o)]
        return WY
    for r, hole in [(r, False) for r in S['water']] + [(r, True) for r in holes]:
        a = cam.poly([(x, 0.0, z) for (x, z) in r], margin=900); b = cam.poly([(x, wy_of(r, hole), z) for (x, z) in r], margin=900)
        if a and area(a) > 0.2: r0.append(P(wound(a, hole)))
        if b and area(b) > 0.2: r1.append(P(wound(b, hole)))
    if not r1: return '', ''
    r0 = ['<path d="%s"/>' % ''.join(r0)]; r1 = ['<path d="%s"/>' % ''.join(r1)]
    # the visible surface: below the quay on the far bank, cut by the quay edge on the near bank
    defs.append('<clipPath id="w0">%s</clipPath><clipPath id="wclip" clip-path="url(#w0)">%s</clipPath>' % (''.join(r0), ''.join(r1)))
    hy = cam.horizon_y()
    defs.append('<linearGradient id="wgrad" gradientUnits="userSpaceOnUse" x1="0" y1="%d" x2="0" y2="%d"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (max(0, hy), H, th['water_top'], th['water_bot']))
    sc = 0.8 + w['wind'] * 0.17                      # ripple strength from the real wind speed
    defs.append('<filter id="ripple" filterUnits="userSpaceOnUse" x="0" y="0" width="%d" height="%d"><feTurbulence type="fractalNoise" baseFrequency="0.012 0.26" numOctaves="2" seed="11" result="n"/><feDisplacementMap in="SourceGraphic" in2="n" scale="%.1f" xChannelSelector="R" yChannelSelector="G"/><feGaussianBlur stdDeviation="0.1 0.45"/></filter>' % (W, H, sc))
    defs.append('<radialGradient id="streak"><stop offset="0" stop-color="#FFE3AE" stop-opacity=".95"/><stop offset=".5" stop-color="#FFC27A" stop-opacity=".45"/><stop offset="1" stop-color="#FFB060" stop-opacity="0"/></radialGradient>')
    out = ['<g clip-path="url(#wclip)">', '<rect width="%d" height="%d" fill="url(#wgrad)"/>' % (W, H)]
    out.append('<g filter="url(#ripple)" opacity="%.2f">%s</g>' % (0.95 if night else 0.92, ''.join(reflections(S, cam, th, sh, far, edges))))
    th['_hz'] = 0.0
    out.append('<rect width="%d" height="%d" fill="%s" opacity="%.2f"/>' % (W, H, th['water'], 0.12 if not night else 0.04))
    # wind ripples: sky-coloured glints where the noise peaks, stretched along the water
    rip = min(0.4, 0.08 + w['wind'] * 0.012)
    gl = (1, 1, 1) if not night else (0.55, 0.62, 0.8)
    eh = cam.C[1] - WY                                # the eye's height above the water
    eye = cam.f[1] > -0.25 and 0 < hy < H
    gbands = []
    if eye:
        # at eye level ripples obey perspective: long and broad near you, thin lines toward the horizon
        z = 4.0; yb = H
        while yb > hy + 1:
            z1 = z * 1.45; y0 = hy + cam.focal * eh / z1 if z1 < 3000 else hy
            y1 = min(H, hy + cam.focal * eh / z)
            if y1 > y0 + 0.5: gbands.append((max(hy, y0), y1, z * 1.2))
            yb = y0; z = z1
            if z > 2500: break
    else:
        gbands = [(0, H, None)]
    for i, (y0, y1, zz) in enumerate(gbands):
        if zz is None: fx, fy = 0.02, 0.3
        else:
            fx = min(0.08, max(0.003, zz / (cam.focal * 1.4)))
            fy = min(0.7, max(0.012, zz * zz / (cam.focal * eh * 0.3)))
        defs.append('<filter id="glint%d" filterUnits="userSpaceOnUse" x="0" y="%.0f" width="%d" height="%.0f"><feTurbulence type="fractalNoise" baseFrequency="%.4f %.4f" numOctaves="2" seed="%d"/><feColorMatrix type="matrix" values="0 0 0 0 %.2f  0 0 0 0 %.2f  0 0 0 0 %.2f  11 0 0 0 -6.9"/></filter>' % ((i, y0 - 2, W, y1 - y0 + 4, fx, fy, 5 + i) + gl))
        out.append('<rect y="%.1f" width="%d" height="%.1f" filter="url(#glint%d)" opacity="%.2f"/>' % (y0, W, y1 - y0, i, rip if not night else 0.18))
    if not night and L[1] > 0 and not th.get('sky_k'):
        C = cam.C; t_ = (WY - C[1]) / (-L[1])
        X = (C[0] + L[0] * t_, WY, C[2] + L[2] * t_); p = cam.proj(X)
        if p and -60 < p[0] < W + 60 and p[1] < H:
            rnd = random.Random(17); sp_ = []
            for _ in range(260):
                y = p[1] + abs(rnd.gauss(0, 1)) * (H - p[1]) * 0.35 * rnd.random() ** 0.5
                spread = 6 + (y - p[1]) * 0.22
                x = p[0] + rnd.gauss(0, spread)
                ww = rnd.uniform(1.2, 4.5) * (1 + (y - p[1]) / 300)
                sp_.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.2f" opacity="%.2f"/>' % (x, y, ww, max(0.3, ww * 0.18), rnd.uniform(0.35, 1)))
            out.append('<g fill="#FFF7E2" style="mix-blend-mode:screen">%s</g>' % ''.join(sp_))
    if th.get('wet'):            # drizzle on the canal: rings scattered at random, not in a grid
        rnd = random.Random(9)
        rings = ''.join('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.2f" fill="none" stroke="#A9B8D8" stroke-width=".4" opacity="%.2f"/>' % (rnd.uniform(0, 173), rnd.uniform(0, 97), r_, r_ * 0.36, rnd.uniform(0.4, 1)) for r_ in [rnd.uniform(0.8, 2.4) for _ in range(28)])
        defs.append('<pattern id="rings" width="173" height="97" patternUnits="userSpaceOnUse">%s</pattern>' % rings)
        if eye:
            for i, (y0, y1, zz) in enumerate(gbands):
                k = min(4.0, max(0.25, 14.0 / zz)); f = min(1.0, (eh / zz) / 0.36)
                if k * f < 0.08: continue
                defs.append('<pattern id="rings%d" href="#rings" patternTransform="translate(%d 0) scale(%.3f %.3f)"/>' % (i, 37 * i, k, k * f))
                out.append('<rect y="%.1f" width="%d" height="%.1f" fill="url(#rings%d)" opacity=".55"/>' % (y0, W, y1 - y0, i))
        else:
            out.append('<rect width="%d" height="%d" fill="url(#rings)" opacity=".55"/>' % (W, H))
    out.append('</g>')
    # quay walls: every water edge whose wall faces the camera, brick down to a dark waterline; none under a bridge's deck
    decks = [(r_['pts'], r_['w'] / 2 + 0.8, (min(p[0] for p in r_['pts']) - r_['w'], max(p[0] for p in r_['pts']) + r_['w'], min(p[1] for p in r_['pts']) - r_['w'], max(p[1] for p in r_['pts']) + r_['w']))
             for r_ in S['roads'] if r_.get('bridge')]
    for a_ in S['areas']:
        if a_['kind'] == 'bridge' or (a_.get('tags') or {}).get('man_made') == 'bridge' or (a_.get('tags') or {}).get('bridge') not in (None, 'no'):
            xs_ = [p[0] for p in a_['poly']]; zs_ = [p[1] for p in a_['poly']]; decks.append((a_['poly'], None, (min(xs_) - 1, max(xs_) + 1, min(zs_) - 1, max(zs_) + 1)))
    def under_deck(x, z):
        for pts, hw, bb in decks:
            if hw is None:
                if bb[0] <= x <= bb[1] and bb[2] <= z <= bb[3] and pip(x, z, pts): return True
                continue
            if not (bb[0] <= x <= bb[1] and bb[2] <= z <= bb[3]): continue
            for i in range(len(pts) - 1):
                (ax, az), (bx, bz) = pts[i], pts[i + 1]; ux, uz = bx - ax, bz - az; L2 = ux * ux + uz * uz or 1
                t = max(0.0, min(1.0, ((x - ax) * ux + (z - az) * uz) / L2))
                if math.hypot(ax + ux * t - x, az + uz * t - z) < hw: return True
        return False
    pieces = []
    for (a, b, nx, nz, r) in edges:
        mx, mz = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        if far(mx, mz): continue
        el_ = math.dist(a, b)
        if decks and any(bb[0] - el_ <= mx <= bb[1] + el_ and bb[2] - el_ <= mz <= bb[3] + el_ for _, _, bb in decks):
            k = max(1, int(el_ / 2.0)); run = None
            for j in range(k):
                u0, u1 = j / k, (j + 1) / k
                if under_deck(a[0] + (b[0] - a[0]) * (u0 + u1) / 2, a[1] + (b[1] - a[1]) * (u0 + u1) / 2):
                    if run is not None: pieces.append((run, (a[0] + (b[0] - a[0]) * u0, a[1] + (b[1] - a[1]) * u0), nx, nz, r)); run = None
                elif run is None: run = (a[0] + (b[0] - a[0]) * u0, a[1] + (b[1] - a[1]) * u0)
            if run is not None: pieces.append((run, b, nx, nz, r))
        else: pieces.append((a, b, nx, nz, r))
    quay = []
    for (a, b, nx, nz, r) in pieces:
        mx, mz = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
        WY = lvl.get(id(r), WATER_Y)
        n3 = (nx, 0.0, nz)
        if vdot(n3, vsub(cam.C, (mx, WY / 2, mz))) <= 0: continue
        lit = (not night) and vdot(n3, L) > 0 and sh.lit((mx + nx * .3, WY / 2, mz + nz * .3))
        th['_hz'] = haze_at(th, cam.depth((mx, 0, mz)))
        col = tohex(light(th, hx(QUAY_COL), n3, lit))
        f1 = face(cam, [(a[0], WY + 0.35, a[1]), (b[0], WY + 0.35, b[1]), (b[0], 0.0, b[1]), (a[0], 0.0, a[1])], col, ' stroke="%s" stroke-width=".3"' % col)
        if TONE and f1 and not night and vdot(n3, L) > 0 and WY < -2:
            # a tall quay wall in sun: in pieces, each lit or in the shadow of what stands across the water and behind it
            el_ = math.dist(a, b); k = max(1, int(math.ceil(el_ / 5.0))); nb = 3; qs = {True: [], False: []}
            for j in range(k):
                pa = (a[0] + (b[0] - a[0]) * j / k, a[1] + (b[1] - a[1]) * j / k); pb = (a[0] + (b[0] - a[0]) * (j + 1) / k, a[1] + (b[1] - a[1]) * (j + 1) / k)
                for m in range(nb):
                    y0 = WY + 0.35 - (WY + 0.35) * m / nb; y1 = WY + 0.35 - (WY + 0.35) * (m + 1) / nb
                    lt = bool(sh.lit(((pa[0] + pb[0]) / 2 + nx * .3, (y0 + y1) / 2, (pa[1] + pb[1]) / 2 + nz * .3)))
                    sp = cam.poly([(pa[0], y0, pa[1]), (pb[0], y0, pb[1]), (pb[0], y1, pb[1]), (pa[0], y1, pa[1])], margin=300)
                    if sp and area(sp) > 0.03: qs[lt].append(P(sp))
            cl, cd = tohex(light(th, hx(QUAY_COL), n3, True)), tohex(light(th, hx(QUAY_COL), n3, False))
            f1 = ''.join('<path d="%s" fill="%s" stroke="%s" stroke-width=".3"/>' % (''.join(v), c_, c_) for lt_, v in qs.items() if v for c_ in [cl if lt_ else cd])
        f2 = face(cam, [(a[0], WY, a[1]), (b[0], WY, b[1]), (b[0], WY + 0.35, b[1]), (a[0], WY + 0.35, a[1])], '#2C2A22' if not night else '#06070A')
        f3 = face(cam, [(a[0], -0.18, a[1]), (b[0], -0.18, b[1]), (b[0], 0.02, b[1]), (a[0], 0.02, a[1])], tohex(light(th, hx('#B8B0A2'), n3, lit)))
        crs = ''
        if cam.f[1] > -0.25 and th.get('wx') and not night and f1:        # at eye level the quay wall shows its brick courses
            el_ = math.dist(a, b)
            if el_ > 0.2 and QUAY_COURSE: crs = ''.join(CU.coursing(cam, a, b, b[0] - a[0], b[1] - a[1], el_, n3, WY + 0.38, -0.2, QUAY_COURSE))
        quay.append(f1 + crs + f2 + f3)
    th['_hz'] = 0.0
    return ''.join(out), ''.join(quay)

class LiftCam:
    """The camera, for one thing standing on real terrain: every point it is given is raised by the ground's height there."""
    def __init__(self, cam, dy): self._c, self._dy = cam, dy
    def __getattr__(self, k): return getattr(self._c, k)
    def _l(self, p): return (p[0], p[1] + self._dy, p[2])
    def proj(self, p, *a, **k): return self._c.proj(self._l(p), *a, **k)
    def poly(self, pts, *a, **k): return self._c.poly([self._l(p) for p in pts], *a, **k)
    def cam(self, p, *a, **k): return self._c.cam(self._l(p), *a, **k)
    def depth(self, p, *a, **k): return self._c.depth(self._l(p), *a, **k)

def lift(cam, o):
    return LiftCam(cam, o['gy']) if isinstance(o, dict) and o.get('gy') else cam

def face(cam, q, fill, extra=''):
    sp = cam.poly(q, margin=300)
    if not sp or area(sp) < 0.12: return ''
    return '<path d="%s" fill="%s"%s/>' % (P(sp), fill, extra)

def render(S, thn, cfg, lens=None, split=None, layer=None):
    """layer='front': everything but the sky, which is left transparent (the board slides a separate sky behind it)."""
    th = dict(THEMES[thn]); W, H = cfg['W'], cfg['H']; th['_win_d'] = cfg.get('win_d', 390)
    th['_eye'] = bool(th.get('wx') and cfg.get('pitch', 90) < 10)
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], W, H, cx=cfg.get('cx'), cy=cfg.get('cy'))
    hh, mm = th['hhmm']; y_, mo_, dd_ = th['date']
    t_utc = datetime.datetime(y_, mo_, dd_, hh, mm) - datetime.timedelta(hours=TZ_H)      # CEST
    az, el = sun_position(t_utc)
    L = LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
    th['_L'] = L; night = th['night']
    solids = [b for b in S['buildings'] if not b['hidden'] and b['h']] + [p for p in S['parts'] if p['h']]
    sh = Shadows(solids, L)
    RAD = cfg.get('radius', 1e9); TX, TZ = cfg['target'][0], cfg['target'][2]
    far = lambda x, z: math.hypot(x - TX, z - TZ) > RAD
    defs, ground, marks, shadows, items, glow = [], [], [], [], [], []
    hy = cam.horizon_y()
    if th.get('wx'):
        defs.append('<filter id="foliage" x="-25%" y="-25%" width="150%" height="150%"><feTurbulence type="fractalNoise" baseFrequency=".55" numOctaves="2" seed="4" result="n"/><feDisplacementMap in="SourceGraphic" in2="n" scale="7" xChannelSelector="R" yChannelSelector="G" result="d"/><feTurbulence type="fractalNoise" baseFrequency="1.4" numOctaves="2" seed="9" result="m"/><feColorMatrix in="m" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  -3.2 0 0 0 2.35" result="mask"/><feComposite in="d" in2="mask" operator="in" result="leaves"/><feColorMatrix in="m" type="matrix" values=".6 .6 .6 0 0  .6 .6 .6 0 0  .6 .6 .6 0 0  0 0 0 0 1" result="tone"/><feComposite in="leaves" in2="tone" operator="arithmetic" k1=".9" k2=".55" k3="0" k4="0" result="t"/><feComposite in="t" in2="leaves" operator="in"/></filter>')
        defs.append('<filter id="leaf" x="-15%" y="-15%" width="130%" height="130%"><feTurbulence type="fractalNoise" baseFrequency=".55" numOctaves="2" seed="3" result="n"/><feDisplacementMap in="SourceGraphic" in2="n" scale="2.6" xChannelSelector="R" yChannelSelector="G"/></filter>')
        # brick, plaster and slate are never one flat tone: a fine streaky variation, stronger along the courses than across them
        defs.append('<filter id="mat" filterUnits="userSpaceOnUse" x="0" y="0" width="%d" height="%d"><feTurbulence type="fractalNoise" baseFrequency=".16 .75" numOctaves="2" seed="13"/><feColorMatrix type="matrix" values=".33 .33 .33 0 0  .33 .33 .33 0 0  .33 .33 .33 0 0  0 0 0 0 1" result="g"/><feComposite in="SourceGraphic" in2="g" operator="arithmetic" k1=".2" k2=".9" k3="0" k4="0" result="t"/><feComposite in="t" in2="SourceAlpha" operator="in"/></filter>' % (W, H))
        defs.append(CU.crown_filter())
        defs.append('<pattern id="scaf" width="9" height="9" patternUnits="userSpaceOnUse"><path d="M0 0h9M0 0v9" stroke="#8A8F96" stroke-width=".7"/><path d="M0 9L9 0" stroke="#8A8F96" stroke-width=".4"/></pattern>')
        defs.append('<filter id="grain" filterUnits="userSpaceOnUse" x="0" y="0" width="%d" height="%d"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="1" seed="8"/><feColorMatrix type="saturate" values="0"/></filter>' % (W, H))
        defs.append('<radialGradient id="vig" cx=".5" cy=".46" r=".75"><stop offset=".55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="%s"/></radialGradient>' % ('.38' if night else '.2'))
    if th.get('wx') and not night:
        sky = ('#C7CDD5', '#59626E') if th.get('sky_k') else ('#B7CBE0', '#3E4C5E')
        defs.append('<linearGradient id="glass" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % sky)
        defs.append('<linearGradient id="glassLit" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#E8EEF4"/><stop offset="1" stop-color="#8496AA"/></linearGradient>')
    defs.append('<linearGradient id="sky" gradientUnits="userSpaceOnUse" x1="0" y1="%.0f" x2="0" y2="%.0f"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (hy - 300, hy, th['sky_top'], th['sky_hor']))
    if layer != 'front': ground.append('<rect width="%d" height="%d" fill="url(#sky)"/>' % (W, H))
    if th.get('wx') and hy > 4: ground.append(sky_layer(cam, th, W, H, hy, defs, layer))
    def gp(pts, fill, y=0.0, extra=''):
        sp = cam.poly([(x, y, z) for (x, z) in pts], margin=900)
        return '<path d="%s" fill="%s"%s/>' % (P(sp), fill, extra) if sp and area(sp) > 0.2 else ''
    GR = cfg.get('ground_r', 1500)
    ground.append(gp(rect(-GR, GR, -GR, GR), th['ground']))
    if S.get('under'): ground.append(S['under'](cam, th, far, defs))        # other regions: land cover below the water
    quay_svg = ''
    if th.get('wx'):
        edges = water_edges(S)
        wsvg, quay_svg = water_layer(S, cam, th, sh, far, W, H, defs, edges)
        ground.append(wsvg)
    else:
        for r in S['water']: ground.append(gp(r, th['water'], 0.0))
    if th.get('wx'): ground.append('<g filter="url(#mat)">')       # pavers and setts are never one flat tone either
    AREA = {'park': '#A9C38A', 'garden': '#A9C38A', 'grass': '#A9C38A', 'playground': '#D9B48A', 'pedestrian': '#DCD3C4', 'square': '#DCD3C4', 'memorial': '#E3B3C0', 'parking': '#C9C4BA', 'bridge': '#CFC6B6'}
    for a in S['areas']:
        c = AREA.get(a['kind'], '#DCD3C4')
        if a['kind'] in ('pedestrian', 'square', 'parking') and SURF.get((a.get('tags') or {}).get('surface')) and TONE: c = SURF[a['tags']['surface']]     # a square in its mapped surface
        if night: c = tohex(mulc(hx(c), 0.18))
        ground.append(gp(a['poly'], c, 0.02))
    # roads in their mapped material; at eye level, pavements where sidewalk=* is mapped, and the joints of each material
    eye = th.get('wx') and cfg.get('pitch', 90) < 10
    th['_eye'] = eye
    pave_over = ''
    if eye:
        defs.append(CU.pattern_defs(night))
        def side_col(sf):
            c = mix(hx(SURF.get(sf, '#C4C0B8')), hx('#BFBAB2'), 0.55)
            if th.get('sky_k'): c = mulc(c, 0.93)
            return tohex(mulc(c, 0.2) if night else c)
        pave_under, pave_over = CU.paving(S, cam, th, far, side_col)
        ground.append(pave_under)
    order = ['path', 'footway', 'steps', 'cycleway', 'service', 'pedestrian', 'living_street', 'unclassified', 'residential', 'busway', 'tertiary', 'secondary']
    for hw in order:
        for r in S['roads']:
            if r['hw'] != hw: continue
            surf = r['tags'].get('surface')
            col = SURF.get(surf, th['road'])
            sc = r['tags'].get('surface:colour')
            if sc: col = {'red': '#C0665A', 'grey': '#9B9A99', 'gray': '#9B9A99', 'black': '#5A5A5A', 'green': '#6F9A6A', 'blue': '#6A86B0'}.get(sc, col)
            elif hw == 'cycleway': col = '#C98A7A'          # guessed surface:colour=red, the Dutch default
            if night: col = tohex(mulc(hx(col), 0.2))
            y = 0.05 + (0.4 if r['bridge'] else 0)
            ground.append(gp(ribbon(r['pts'], r['w'] / 2), col, y))
            if r['bridge'] and th.get('wx'):
                for s_ in (-1, 1):
                    off = ribbon(r['pts'], r['w'] / 2 + 0.15); n = len(r['pts'])
                    line = off[:n] if s_ < 0 else off[n:][::-1]
                    items.extend(railing(cam, th, line, y + 0.02))
            elif r['bridge'] and not night:
                for s_ in (-1, 1):
                    off = ribbon(r['pts'], r['w'] / 2 + 0.3); n = len(r['pts'])
                    line = off[:n] if s_ < 0 else off[n:][::-1]
                    pts = [cam.proj((x, 1.1, z)) for (x, z) in line]; pts = [p for p in pts if p]
                    if len(pts) > 1: marks.append('<path d="M%s" stroke="#5A4E44" stroke-width="1.2" fill="none"/>' % 'L'.join('%.1f %.1f' % p for p in pts))
            if surf == 'sett' and not night and not eye and cam.depth((r['pts'][0][0], 0, r['pts'][0][1])) < min(500, cfg.get('sett_d', 500)):
                segs = []
                for i in range(len(r['pts']) - 1):
                    a, b = r['pts'][i], r['pts'][i + 1]
                    Lg = math.dist(a, b); ux, uz = (b[0] - a[0]) / (Lg or 1), (b[1] - a[1]) / (Lg or 1)
                    k = 0.0
                    while k < Lg:
                        cx_, cz_ = a[0] + ux * k, a[1] + uz * k
                        p0, p1 = cam.proj((cx_ + uz * r['w'] / 2, y + 0.01, cz_ - ux * r['w'] / 2)), cam.proj((cx_ - uz * r['w'] / 2, y + 0.01, cz_ + ux * r['w'] / 2))
                        if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
                        k += 0.6
                if segs: marks.append('<path d="%s" stroke="#7E7264" stroke-width=".4" opacity=".6" fill="none"/>' % ''.join(segs))
    if pave_over: ground.append(pave_over)
    if S.get('over'): ground.append(S['over'](cam, th, far, defs))          # other regions: runways, motorway lanes, rails on the ground
    if th.get('wx'): ground.append('</g>')
    # tram tracks
    for rl in S['rails']:
        for off in (-0.72, 0.72):
            o = ribbon(rl, abs(off)); n = len(rl)
            line = o[:n] if off < 0 else o[n:][::-1]
            pts = [cam.proj((x, 0.08, z)) for (x, z) in line]; pts = [p for p in pts if p]
            if len(pts) > 1: marks.append('<path d="M%s" stroke="%s" stroke-width=".9" fill="none"/>' % ('L'.join('%.1f %.1f' % p for p in pts), '#6E6A66' if not night else '#2A2E3A'))
    # crossings
    for n in S['nodes']:
        if n['tags'].get('highway') == 'crossing' and n['tags'].get('crossing:markings') in ('zebra', 'ladder', 'yes') and not far(n['x'], n['z']):
            sp = cam.proj((n['x'], 0.1, n['z']))
            if sp: marks.append('')
    # canal names (real names) on the water, from above only
    if not night and cfg.get('pitch', 90) > 20:
        seen = set()
        for lb in S['labels']:
            if lb['kind'] != 'canal' or lb['name'] in seen: continue
            pts = lb['pts']
            mid = pts[len(pts) // 2]
            if far(mid[0], mid[1]) or len(pts) < 2: continue
            a, b = pts[len(pts) // 2 - 1], pts[len(pts) // 2]
            ux, uz = (b[0] - a[0]), (b[1] - a[1]); Lg = math.hypot(ux, uz) or 1; ux, uz = ux / Lg, uz / Lg
            u = (ux, 0, uz)
            s = LV.ground_text(cam, lb['name'].upper(), (mid[0], (WATER_Y + 0.05) if th.get('wx') else 0.1, mid[1]), u, (-uz, 0, ux), 3.2, '#EAF2FA', 0.85, 700, 0.3)
            if s: marks.append(s); seen.add(lb['name'])
    # shadows from mapped heights only
    if not night and L[1] > 0 and not S.get('no_shadows'):
        o = lambda y: (-L[0] / L[1] * y, -L[2] / L[1] * y)
        for b in solids:
            cx, cz = centroid(b['poly'])
            if far(cx, cz): continue
            top = b['h']; bot = b['min']
            off1, off0 = o(top), o(bot)
            poly = b['poly']
            if b.get('_gy') is not None:          # on real terrain: from the building's own ground, onto it
                gy = b['_gy']; off1 = o(top - gy)
                hull = hull2(list(poly) + [(x + off1[0], z + off1[1]) for (x, z) in poly])
                sp = cam.poly([(x, gy + 0.1, z) for (x, z) in hull], margin=900)
                if sp: shadows.append(P(sp))
                continue
            if bot > 0:
                sp = cam.poly([(x + off1[0], 0.1, z + off1[1]) for (x, z) in poly], margin=900)
                if sp: shadows.append(P(sp))
                continue
            # a prism's shadow: the hull of its footprint and its offset top (exact for convex footprints)
            hull = hull2(list(poly) + [(x + off1[0], z + off1[1]) for (x, z) in poly])
            sp = cam.poly([(x, 0.1, z) for (x, z) in hull], margin=900)
            if sp: shadows.append(P(sp))
    if not night and L[1] > 0 and not S.get('no_shadows'):
        o = lambda y: (-L[0] / L[1] * y, -L[2] / L[1] * y)
        for n in S['trees']:
            if far(n['x'], n['z']): continue
            off = o(n['h'] * 0.7); r = n['crown'] / 2
            sp = cam.poly([(n['x'] + off[0] + r * math.cos(a), n.get('gy', 0.0) + 0.1, n['z'] + off[1] + r * math.sin(a)) for a in [k * math.pi / 8 for k in range(16)]])
            if sp: shadows.append(P(sp))
    # objects
    for b in S['buildings']:
        if b['hidden']: continue
        cx, cz = centroid(b['poly'])
        if far(cx, cz): continue
        c = cam.cam((cx, (b['h'] or 0) / 2, cz))
        if c[2] < 2: continue
        items.append((c[2], 'b', b))
    for p in S['parts']:
        cx, cz = centroid(p['poly'])
        if far(cx, cz): continue
        c = cam.cam((cx, (p['min'] + (p['h'] or 0)) / 2, cz))
        if c[2] < 2: continue
        items.append((c[2] - 0.01 * p['min'], 'b', p))
    for n in S['nodes']:
        t = n['tags']
        k = 'tree' if t.get('natural') == 'tree' else t.get('highway') if t.get('highway') == 'street_lamp' else t.get('amenity') if t.get('amenity') in ('bench', 'bicycle_parking', 'waste_basket') else 'bollard' if t.get('barrier') in ('bollard', 'block') else None
        if not k or far(n['x'], n['z']): continue
        c = cam.cam((n['x'], 1, n['z']))
        if c[2] < (3.0 if cfg.get('pitch', 90) < 10 else 2): continue      # at eye level, nothing right in your face
        if k != 'tree' and cam.focal / c[2] < cfg.get('furniture_px', 1.6): continue   # street furniture only a pixel or two tall is left out
        items.append((c[2], k, n))
    if S.get('items'): items.extend(S['items'](cam, th, far, defs))         # other regions: cranes, tanks, turbines, masts
    if th.get('wx') and not S.get('no_facades'):
        for it in facade_details(S, cam, th, far):
            hz_ = haze_at(th, it[0])
            items.append((it[0], it[1], '<g opacity="%.2f">%s</g>' % (1 - 0.8 * hz_, it[2])) if hz_ > 0.04 and it[1] == 'svg' else it)
    items.sort(key=lambda it: -it[0])
    out = []; cut = None; split_d = None
    for d, k, o_ in items:
        if split is not None and k == 'b' and o_ is split:
            cut = len(out); split_d = d; continue
        if k == 'b': out.extend(draw_building(cam, th, sh, o_, d, lens))
        elif k == 'tree': out.extend(draw_tree3d(lift(cam, o_), th, sh, o_, d, lens))
        elif k == 'street_lamp': out.extend(draw_lamp(lift(cam, o_), th, o_, d))
        elif k == 'bench': out.append(draw_bench(lift(cam, o_), th, o_))
        elif k == 'waste_basket': out.append(draw_box(lift(cam, o_), th, o_['x'], o_['z'], 0.42, 0.42, 0.95, '#2F4A3C'))
        elif k == 'bollard': out.append(draw_bollard(lift(cam, o_), th, o_, d))
        elif k == 'bicycle_parking': out.append(draw_rack(lift(cam, o_), th, o_))
        elif k == 'svg': out.append(o_)
    # night light: mapped lamps, lit=yes roads, open places
    pools = []
    if night:
        defs.append('<radialGradient id="pool"><stop offset="0" stop-color="#FFB45E" stop-opacity=".55"/><stop offset=".5" stop-color="#FFA848" stop-opacity=".18"/><stop offset="1" stop-color="#FFA848" stop-opacity="0"/></radialGradient>')
        defs.append('<radialGradient id="open"><stop offset="0" stop-color="#FFE2A8" stop-opacity=".95"/><stop offset=".3" stop-color="#FFC878" stop-opacity=".45"/><stop offset="1" stop-color="#FFC878" stop-opacity="0"/></radialGradient>')
        defs.append('<filter id="litglow" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="2.6"/></filter>')
        defs.append('<radialGradient id="openpool"><stop offset="0" stop-color="#FFD89A" stop-opacity=".75"/><stop offset=".45" stop-color="#FFC070" stop-opacity=".28"/><stop offset="1" stop-color="#FFB060" stop-opacity="0"/></radialGradient>')
        defs.append('<radialGradient id="halo"><stop offset="0" stop-color="#FFE9C0" stop-opacity=".7"/><stop offset=".35" stop-color="#FFC98A" stop-opacity=".22"/><stop offset="1" stop-color="#FFB060" stop-opacity="0"/></radialGradient>')
        if not th.get('wx'): defs.append('<radialGradient id="streak"><stop offset="0" stop-color="#FFE3AE" stop-opacity=".95"/><stop offset="1" stop-color="#FFB060" stop-opacity="0"/></radialGradient>')
        # lit=yes streets: stroked centrelines, width from the street's own width at its depth
        lit = []
        for r in S['roads']:
            if r['tags'].get('lit') != 'yes': continue
            if eye:
                gw = r['w'] / 2 + 4.0; segs_ = []
                for i_ in range(len(r['pts']) - 1):          # one convex quad per segment, so near-plane clipping stays exact
                    (ax_, az_), (bx_, bz_) = r['pts'][i_], r['pts'][i_ + 1]; Lq = math.hypot(bx_ - ax_, bz_ - az_) or 1
                    nx_, nz_ = -(bz_ - az_) / Lq * gw, (bx_ - ax_) / Lq * gw
                    sp = cam.poly([(ax_ + nx_, 0.06, az_ + nz_), (bx_ + nx_, 0.06, bz_ + nz_), (bx_ - nx_, 0.06, bz_ - nz_), (ax_ - nx_, 0.06, az_ - nz_)], margin=300)
                    if sp: segs_.append(P(sp))
                if segs_: pools.append('<path d="%s" fill="#FFB866" opacity="%s" filter="url(#litglow)" style="mix-blend-mode:screen"/>' % (''.join(segs_), '.05' if th.get('wet') else '.035'))
                continue
            run = []
            for pt in r['pts'] + [None]:
                c = cam.cam((pt[0], 0.06, pt[1])) if pt and not far(*pt) else None
                if c and c[2] > 2: run.append((cam.pc(c), c[2])); continue
                if len(run) > 1:
                    wpx = (r['w'] + (1.0 if th.get('wx') else 3)) * cam.focal / (sum(d for _, d in run) / len(run))
                    lit.append('<path d="M%s" stroke-width="%.1f"/>' % ('L'.join('%.1f %.1f' % p for p, _ in run), max(0.8, wpx)))
                run = []
        pools.append('<g fill="none" stroke="#FFB866" stroke-linecap="round" stroke-linejoin="round" opacity="%s" filter="url(#litglow)" style="mix-blend-mode:screen">%s</g>' % ('.3' if th.get('wx') else '.4', ''.join(lit)))
        if th.get('wet'):
            core = [re.sub(r'stroke-width="([\d.]+)"', lambda m: 'stroke-width="%.1f"' % (float(m.group(1)) * 0.3), x) for x in lit]
            pools.append('<g fill="none" stroke="#FFE2B0" stroke-linecap="round" stroke-linejoin="round" opacity=".22" style="mix-blend-mode:screen">%s</g>' % ''.join(core))
        for n in S['nodes']:
            if n['tags'].get('highway') != 'street_lamp' or far(n['x'], n['z']): continue
            cm_ = lift(cam, n)
            c = cm_.cam((n['x'], 0, n['z']))
            if c[2] < 2: continue
            p = cam.pc(c); rx = 10 * cam.focal / c[2]
            q = cm_.proj((n['x'], 0, n['z'] + 10)) or p
            pools.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="url(#pool)"/>' % (p[0], p[1], rx, max(0.6, abs(q[1] - p[1]))))
        for n in S['pois']:
            oh = n['tags'].get('opening_hours')
            if far(n['x'], n['z']) or not oh or open_at(oh, ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'][datetime.date(*th['date']).weekday()], th['hhmm'], date=th['date']) is not True: continue
            c = cam.cam((n['x'], 1.5, n['z']))
            if c[2] < 2: continue
            p = cam.pc(c); r_ = max(3, 5 * cam.focal / c[2])
            if DAYLIGHT:
                g0 = cam.cam((n['x'], 0.05, n['z'])); pg = cam.pc(g0); rg = 11 * cam.focal / g0[2]
                qg = cam.proj((n['x'], 0.05, n['z'] + 11)) or pg
                pools.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="url(#openpool)"/>' % (pg[0], pg[1], rg, max(1.0, abs(qg[1] - pg[1]))))
                r_ = max(4, 8 * cam.focal / c[2])
            glow.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#open)"/>' % (p[0], p[1], r_))
    # tag lens: streets and places coloured by whether the tag is mapped; counts are for what is in view
    lensk = lens[4:] if lens and lens.startswith('tag:') else None
    counts = collections.Counter()
    def onscreen(q): return q and 0 <= q[0] <= W and 0 <= q[1] <= H
    if lensk in ROAD_KEYS:
        for r in S['roads']:
            st = tag_state(r, lensk, 'road'); run = []; seen = False
            for pt in r['pts'] + [None]:
                c = cam.cam((pt[0], 0.08, pt[1])) if pt and not far(*pt) else None
                if c and c[2] > 2: run.append((cam.pc(c), c[2])); continue
                if len(run) > 1:
                    seen = seen or any(onscreen(p) for p, _ in run)
                    wpx = (r['w'] + 1.0) * cam.focal / (sum(d_ for _, d_ in run) / len(run))
                    pools.append('<path d="M%s" stroke="%s" stroke-width="%.1f" stroke-linecap="round" stroke-linejoin="round" fill="none" opacity=".92"/>' % ('L'.join('%.1f %.1f' % p for p, _ in run), LENS_COL[st], max(1.0, wpx)))
                run = []
            if seen: counts[st] += 1
    if lensk in POI_KEYS:
        for n in S['pois']:
            if far(n['x'], n['z']): continue
            q = cam.proj((n['x'], 1.5, n['z']))
            if not onscreen(q): continue
            st = tag_state(n, lensk, 'poi'); counts[st] += 1
            glow.append('<circle cx="%.1f" cy="%.1f" r="4.4" fill="%s" stroke="#FFFFFF" stroke-width="1.5"/>' % (q[0], q[1], LENS_COL[st]))
    if lensk in BUILDING_KEYS:
        for b in [x for x in S['buildings'] if not x['hidden']] + S['parts']:
            cx, cz = centroid(b['poly'])
            if far(cx, cz) or not onscreen(cam.proj((cx, b['h'] or 0, cz))): continue
            st = tag_state(b, lensk, 'building')
            if st != 'na': counts[st] += 1
    if lensk in TREE_KEYS:
        for n in S['trees']:
            if far(n['x'], n['z']) or not onscreen(cam.proj((n['x'], 4, n['z']))): continue
            counts[tag_state(n, lensk, 'tree')] += 1
    stops = []; top = max(0.0, hy)
    for k in range(17):
        sy = top + (H - top) * k / 16
        gd = cam.ground_depth(sy + 0.01, cfg['target'][1])
        a_ = th['haze_max'] if gd is None else th['haze_max'] * clamp((gd - cfg.get('haze0', 380)) / cfg.get('haze1', 800)) ** 1.3
        stops.append('<stop offset="%.3f" stop-color="%s" stop-opacity="%.3f"/>' % (k / 16, th['haze'], a_))
    if eye and hy > 0:
        # at eye level, far things sit on the horizon line from both sides: a soft band, never an edge
        band = []
        for k in range(-12, 13):
            dy = (H - hy) * (abs(k) / 12) ** 2 * 0.5
            gd = cam.ground_depth(hy + max(dy, 0.01), cfg['target'][1])
            a_ = th['haze_max'] if gd is None else th['haze_max'] * clamp((gd - cfg.get('haze0', 380)) / cfg.get('haze1', 800)) ** 1.3
            band.append((hy + (dy if k > 0 else -dy), min(a_, th['haze_max'] * 0.18)))
        y0_, y1_ = band[0][0], band[-1][0]
        stops = ['<stop offset="%.3f" stop-color="%s" stop-opacity="%.3f"/>' % ((y - y0_) / (y1_ - y0_), th['haze'], a_ * 0.8) for (y, a_) in band]
        top = y0_
        defs.append('<linearGradient id="haze" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%.1f">%s</linearGradient>' % (y0_, y1_, ''.join(stops)))
        haze = '<rect y="%.1f" width="%d" height="%.1f" fill="url(#haze)"/>' % (y0_, W, y1_ - y0_)
    else:
        defs.append('<linearGradient id="haze" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%d">%s</linearGradient>' % (top, H, ''.join(stops)))
        haze = '<rect y="%.1f" width="%d" height="%.1f" fill="url(#haze)"/>' % (top, W, H - top)
    if thn == 'golden':
        defs.append('<linearGradient id="wash" x1="0" y1="0" x2="1" y2=".35"><stop offset="0" stop-color="#FFB25E" stop-opacity=".14"/><stop offset=".45" stop-color="#FFC98A" stop-opacity=".05"/><stop offset="1" stop-color="#FFC98A" stop-opacity="0"/></linearGradient>')
        haze += '<rect width="%d" height="%d" fill="url(#wash)"/>' % (W, H)
    sfx = ''
    if shadows and th.get('shadow_blur'):
        defs.append('<filter id="sblur" x="-10%%" y="-10%%" width="120%%" height="120%%"><feGaussianBlur stdDeviation="%.1f"/></filter>' % th['shadow_blur'])
        sfx = ' filter="url(#sblur)"'
    shadow_g = '<g opacity="%.2f" fill="%s"%s>%s</g>' % (th['shadow_op'], th['shadow'], sfx, ''.join('<path d="%s"/>' % p for p in shadows)) if shadows else ''
    ao_g = wfx = ''
    if th.get('wx'):
        # where walls meet the ground the sky is half hidden: a soft dark seam at every footprint
        # off by default: the map reads better without it (ATLAS_AO=1 brings it back)
        if os.environ.get('ATLAS_AO') == '1':
            foot = []
            for b in (solids if not cfg.get('no_ao') else []):
                if (b['min'] > 0.5 and b.get('_gy') is None) or b['tags'].get('building') == 'houseboat': continue
                cx, cz = centroid(b['poly'])
                if far(cx, cz): continue
                sp = cam.poly([(x, b.get('_gy', 0.0) + 0.05, z) for (x, z) in b['poly']], margin=200)
                if sp and area(sp) > 1: foot.append(P(sp))
            defs.append('<filter id="aob" x="-5%" y="-5%" width="110%" height="110%"><feGaussianBlur stdDeviation="2.4"/></filter>')
            ao_g = ('<path d="%s" fill="none" stroke="%s" stroke-width="5.5" opacity="%.2f" filter="url(#aob)"/><path d="%s" fill="none" stroke="#14161E" stroke-width="1.6" opacity="%.2f"/>' % (''.join(foot), '#1A1E2C', 0.62 if th.get('sky_k') else (0.3 if night else 0.48), ''.join(foot), 0.55 if not night else 0.3)) if foot else ''
        if th.get('cloud_shadows') and not eye:          # at eye level the ground is too foreshortened for cloud shadows to read
            cov = th['cloud_shadows']; t0 = 0.62 - cov * 0.3
            defs.append('<filter id="cloudsh" x="0" y="0" width="100%%" height="100%%"><feTurbulence type="fractalNoise" baseFrequency="0.0026 0.0058" numOctaves="3" seed="23"/><feColorMatrix type="matrix" values="0 0 0 0 0.16  0 0 0 0 0.19  0 0 0 0 0.32  7 0 0 0 %.2f"/></filter>' % (-7 * t0))
            wfx += '<rect y="%.1f" width="%d" height="%.1f" filter="url(#cloudsh)" opacity=".42" style="mix-blend-mode:multiply"/>' % (max(0, hy), W, H - max(0, hy))
        if th.get('wet'):
            rnd = random.Random(4)          # drizzle: short faint streaks scattered at random, leaning with the wind
            st = ''.join('<path d="M%.1f %.1fv%.1f" stroke="#C3CEE8" stroke-width="%.2f" opacity="%.2f" stroke-linecap="round"/>' % (rnd.uniform(0, 127), rnd.uniform(0, 211), rnd.uniform(3, 8), rnd.uniform(0.3, 0.55), rnd.uniform(0.35, 0.9)) for _ in range(26))
            defs.append('<pattern id="rain" width="127" height="211" patternUnits="userSpaceOnUse" patternTransform="rotate(%d)">%s</pattern>' % (int(th['wx']['wind'] * 0.8), st))
            if layer != 'front': wfx += '<rect width="%d" height="%d" fill="url(#rain)" opacity=".45"/>' % (W, H)
    head = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d">' % (W, H, W, H),
            '<defs><clipPath id="vb"><rect width="%d" height="%d"/></clipPath>%s</defs><g clip-path="url(#vb)">' % (W, H, ''.join(defs))]
    if split is not None:
        # three layers for the fill-in quest: everything behind the building, the building itself (one per answer), everything in front
        cut = len(out) if cut is None else cut
        base = ''.join(head + [''.join(ground), ''.join(marks), shadow_g, ao_g, quay_svg, ''.join(pools), '<g stroke-linejoin="round">', ''.join(out[:cut]), '</g></g></svg>'])
        front = ''.join(head + ['<g stroke-linejoin="round">', ''.join(out[cut:]), '</g>', ''.join(glow), wfx, haze, '</g></svg>'])
        def variant(bv, lens_=None):
            body = ''.join(draw_building(cam, th, sh, bv, split_d, lens_))
            dfs = '<defs><pattern id="hatchq" width="7" height="7" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><path d="M0 0V7" stroke="#8C857A" stroke-width="2.2"/></pattern></defs>' if bv.get('_hatch') else ''
            return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" style="position: absolute; left: 0; top: 0" aria-hidden="true">%s<g stroke-linejoin="round">%s</g></svg>' % (W, H, W, H, dfs, body)
        return dict(base=base, front=front, variant=variant, cam=cam, depth=split_d, az=az, el=el)
    fin = ('<rect width="%d" height="%d" filter="url(#grain)" opacity="%s" style="mix-blend-mode:overlay"/><rect width="%d" height="%d" fill="url(#vig)"/>' % (W, H, '.09' if not night else '.12', W, H)) if th.get('wx') and layer != 'front' and not th.get('_eye') else ''
    mat = ' filter="url(#mat)"' if th.get('wx') else ''
    if cfg.get('no_fin'): fin = ''
    if layer == 'split':
        # two stacked images for dense views: the ground, then everything standing on it (each under the size limit)
        up = S.get('_town')          # a town on its terrain: the ground image lies between the layers, so shadows and seams go above it
        base = ''.join(head + [''.join(ground), ''.join(marks), '' if up else shadow_g, '' if up else ao_g, quay_svg, ''.join(pools), '</g></svg>'])
        # a ground image of its own (terrain, relief) stands between these two on the board; what lies along the ground above it opens the second
        objs = ''.join(head + [th.get('_above', ''), (shadow_g + ao_g) if up else '', '<g stroke-linejoin="round"%s>' % mat, ''.join(out), '</g>', ''.join(glow), wfx, haze, fin, '</g></svg>'])
        if cfg.get('obj_split') and len(objs) > cfg['obj_split']:
            # too much for one image: the far half and the near half, stacked in that order, so nothing changes in what covers what
            tot = sum(len(x) for x in out); acc = 0; k = 0
            while k < len(out) and acc < tot / 2: acc += len(out[k]); k += 1
            far_ = ''.join(head + [th.get('_above', ''), (shadow_g + ao_g) if up else '', '<g stroke-linejoin="round"%s>' % mat, ''.join(out[:k]), '</g></g></svg>'])
            near_ = ''.join(head + ['<g stroke-linejoin="round"%s>' % mat, ''.join(out[k:]), '</g>', ''.join(glow), wfx, haze, fin, '</g></svg>'])
            objs = (far_, near_)
        return (base, objs), cam, dict(az=az, el=el, counts=dict(counts), doors=th.get('_doors', 0), shops=th.get('_shops', 0), open=th.get('_open', 0), brands=th.get('_brands', 0), raster=th.get('_raster'))
    svg = head + [''.join(ground), ''.join(marks), shadow_g, ao_g, quay_svg, ''.join(pools), th.get('_above', ''), '<g stroke-linejoin="round"%s>' % mat, ''.join(out), '</g>', ''.join(glow), wfx, haze, fin, '</g></svg>']
    return ''.join(svg), cam, dict(az=az, el=el, counts=dict(counts), doors=th.get('_doors', 0), shops=th.get('_shops', 0), open=th.get('_open', 0), brands=th.get('_brands', 0))

LENS_COL = {'mapped': '#5DB27E', 'gap': '#F0A24E', 'na': '#ECE8E1'}
BUILDING_KEYS = ('height', 'start_date', 'roof:shape', 'building:colour', 'building:levels')
TREE_KEYS = ('leaf_type', 'tree_height')
ROAD_KEYS = ('lit', 'surface')
POI_KEYS = ('opening_hours', 'wheelchair')
GAP_KEYS = ('height', 'start_date', 'roof:shape', 'building:colour', 'building:levels', 'leaf_type', 'lit', 'surface', 'opening_hours', 'wheelchair')
def tag_state(o, key, kind):
    """'mapped', 'gap' (missing, so a guess or nothing is drawn) or 'na' (the key does not apply to this object)."""
    t = o['tags']
    if kind == 'building':
        if key not in BUILDING_KEYS: return 'na'
        part = 'building:part' in t
        if key == 'height': return 'gap' if o['guessed'].get('height') else 'mapped'
        if key == 'start_date': return 'na' if part else ('mapped' if o['year'] else 'gap')
        if key == 'roof:shape': return 'gap' if o['guessed'].get('roof:shape') else 'mapped'
        if key == 'building:colour': return 'mapped' if (t.get('building:colour') or t.get('building:material')) else 'gap'
        if key == 'building:levels': return 'na' if part else ('mapped' if t.get('building:levels') else 'gap')
    if kind == 'tree':
        if key not in TREE_KEYS: return 'na'
        return 'mapped' if t.get('leaf_type' if key == 'leaf_type' else 'height') else 'gap'
    if kind == 'road':
        if key not in ROAD_KEYS: return 'na'
        return 'mapped' if t.get(key) else 'gap'
    if kind == 'poi':
        if key not in POI_KEYS: return 'na'
        return 'mapped' if t.get(key) else 'gap'
    return 'na'

def surface_colour(b, lens, which='wall'):
    if lens and lens.startswith('tag:'):
        return hx(LENS_COL[tag_state(b, lens[4:], 'building')])
    if lens == 'truth':
        g = b.get('guessed', {})
        if which == 'roof' and g.get('roof:colour'): return hx('#FFFFFF')
        if which == 'wall' and g.get('building:colour'): return hx('#FFFFFF')
    if lens == 'year':
        c = year_colour(b['year'])
        return hx(c) if c else hx('#FFFFFF')
    if which == 'roof':
        if b.get('rcol_draw'): return hx(b['rcol_draw'])
        if b['rcol']: return hx(b['rcol']) if b['rcol'].startswith('#') else hx(MATERIAL.get(b['rcol'], CLAY))
        if b['rmat']: return hx(MATERIAL.get(b['rmat'], CLAY))
    if b.get('col_draw'): return hx(b['col_draw'])
    if b['col'] and b['col'].startswith('#'): return hx(b['col'])
    if b['mat']: return hx(MATERIAL.get(b['mat'], CLAY))
    return hx(CLAY)

COMPASS = {k: i * 22.5 for i, k in enumerate('N NNE NE ENE E ESE SE SSE S SSW SW WSW W WNW NW NNW'.split())}
def roof_dir(b):
    """Downhill direction of a skillion roof: roof:direction when mapped, else out through the longest wall (guessed)."""
    v = (b['tags'].get('roof:direction') or '').strip().upper()
    deg = COMPASS.get(v, num(v) if v else None)
    if deg is not None: r = math.radians(deg); return (math.sin(r), -math.cos(r))
    poly = b['poly']; n = len(poly)
    _, i = max((math.dist(poly[i], poly[(i + 1) % n]), i) for i in range(n))
    a, c = poly[i], poly[(i + 1) % n]; cx, cz = centroid(poly)
    nx, nz = c[1] - a[1], -(c[0] - a[0]); Ln = math.hypot(nx, nz) or 1; nx, nz = nx / Ln, nz / Ln
    if nx * ((a[0] + c[0]) / 2 - cx) + nz * ((a[1] + c[1]) / 2 - cz) < 0: nx, nz = -nx, -nz
    return (nx, nz)

def draw_houseboat(cam, th, sh, b, d, lens):
    """building=houseboat: only the outline is mapped. A generic hull at the waterline, a deck, and a set-back cabin
    with a row of windows; its height and colours stay guessed (white in What is mapped)."""
    night = th['night']; L = th['_L']; WY = WATER_Y; out = []
    poly = b['poly']; n = len(poly); cx, cz = centroid(poly)
    lo, deck = WY - 0.15, WY + 0.7
    top = max(deck + 1.6, WY + 0.3 + b['h'])
    cab = [(cx + (x - cx) * 0.84, cz + (z - cz) * 0.8) for (x, z) in poly]
    roof = [(cx + (x - cx) * 0.88, cz + (z - cz) * 0.86) for (x, z) in poly]
    cab_col = surface_colour(b, lens); hull = hx('#23272B') if lens not in ('truth',) else hx('#FFFFFF')
    def walls(pp, y0, y1, base, win=False):
        res = []
        m = len(pp)
        for i in range(m):
            a, c = pp[i], pp[(i + 1) % m]
            ex, ez = c[0] - a[0], c[1] - a[1]; elen = math.hypot(ex, ez)
            if elen < 0.2: continue
            nrm = vnorm((ez, 0, -ex))
            if vdot(nrm, vsub(cam.C, ((a[0] + c[0]) / 2, (y0 + y1) / 2, (a[1] + c[1]) / 2))) <= 0: continue
            lit = (not night) and vdot(nrm, L) > 0 and sh.lit(((a[0] + c[0]) / 2 + nrm[0] * .2, (y0 + y1) / 2, (a[1] + c[1]) / 2 + nrm[2] * .2), skip=b)
            col = tohex(light(th, base, nrm, lit))
            res.append(face(cam, [(a[0], y0, a[1]), (c[0], y0, c[1]), (c[0], y1, c[1]), (a[0], y1, a[1])], col, ' stroke="%s" stroke-width=".3"' % col))
            if win and lens is None and d < 520 and elen > 1.6:
                k = max(1, int(elen / 1.7)); wq = []
                for j in range(k):
                    u0, u1 = (j + 0.22) / k, (j + 0.78) / k
                    sp = cam.poly(LV.quad_at(a, c, y0 + 0.35, y0 + 1.0, u0, u1, 0.03, nrm), margin=200)
                    if sp and area(sp) > 0.2: wq.append(P(sp))
                if wq: res.append('<path d="%s" fill="%s" stroke="%s" stroke-width=".5"/>' % (''.join(wq), 'url(#glass)' if not night else '#10141E', '#E8E4DA' if not night else '#2A2E38'))
        return res
    out += walls(poly, lo, deck, hull)
    out.append(face(cam, [(x, deck, z) for (x, z) in poly], tohex(light(th, hx('#6E5E4C') if lens is None else hx('#FFFFFF'), (0, 1, 0), not night))))
    out += walls(cab, deck, top, cab_col, True)
    out.append(face(cam, [(x, top, z) for (x, z) in roof], tohex(light(th, surface_colour(b, lens, 'roof'), (0, 1, 0), not night)), ' stroke="#2A2C30" stroke-width=".4"'))
    return out

def material_face(cam, th, b, a, bb, ex, ez, elen, nrm, y0, top, d, base, night):
    """What a wall's material shows from a distance (building:material, mapped or guessed): a glass curtain wall's floors and mullions,
    the ribs of metal cladding, the balconies of a tall limestone front, the open decks of a parking garage. Generic details of the material."""
    md = b.get('mat_draw'); kind = b.get('kind'); out = []
    ppm = cam.focal / max(d, 1)                      # pixels per metre at this distance
    lv = max(1, b['levels']); fh = (top - y0) / lv
    def line(u0, y_0, u1, y_1, off=0.03):
        p0 = cam.proj((a[0] + ex * u0 + nrm[0] * off, y_0, a[1] + ez * u0 + nrm[2] * off)); p1 = cam.proj((a[0] + ex * u1 + nrm[0] * off, y_1, a[1] + ez * u1 + nrm[2] * off))
        return 'M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]) if p0 and p1 else ''
    if md == 'glass':
        sp = cam.poly(LV.quad_at(a, bb, y0 + (0.0 if y0 > 0.5 else 0.4), top, 0, 1, 0.02, nrm), margin=300)
        if sp and area(sp) > 1:
            out.append('<path d="%s" fill="%s" opacity="%.2f"/>' % (P(sp), 'url(#glass)' if not night else '#0E1420', 0.55 if not night else 0.8))
        segs = [line(0, y0 + r * fh, 1, y0 + r * fh) for r in range(1, lv)] if fh * ppm > 2.4 else []
        step = 1.5 if 1.5 * ppm >= 3.2 else (3.0 if 3.0 * ppm >= 3.2 else None)
        if step:
            n = int(elen / step); segs += [line(k / n, y0, k / n, top) for k in range(1, n)] if n > 1 else []
        if segs: out.append('<path d="%s" fill="none" stroke="%s" stroke-width=".55" opacity=".7"/>' % (''.join(segs), '#E4ECF0' if not night else '#2A3240'))
    elif md == 'metal' and kind in ('industrial', 'hangar', 'sports', 'shed', 'terminal', 'canopy', None):
        step = 1.0 if ppm >= 2.6 else (2.0 if ppm >= 1.4 else None)
        if step and not night:
            n = int(elen / step); segs = [line(k / n, y0 + 0.3, k / n, top - 0.15) for k in range(1, n)]
            if segs: out.append('<path d="%s" fill="none" stroke="%s" stroke-width=".5" opacity=".38"/>' % (''.join(segs), tohex(mulc(base, 0.62))))
    elif md in ('limestone', 'stone', 'plaster') and lv >= 5 and kind in ('home', 'office', 'hotel') and fh * ppm > 2.2:
        # the continuous iron balconies of a tall stone front, at the second floor and below the top
        rows = [2] + ([lv - 1] if lv >= 6 else [])
        segs = [line(0.02, y0 + r * fh + 0.12, 0.98, y0 + r * fh + 0.12, 0.25) for r in rows]
        out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f" opacity=".7"/>' % (''.join(segs), '#2E3036' if not night else '#0C0D10', max(0.6, 0.14 * ppm)))
    elif kind == 'parking' and fh * ppm > 2.0:
        for r in range(lv):
            sp = cam.poly(LV.quad_at(a, bb, y0 + r * fh + 0.95, y0 + (r + 1) * fh - 0.1, 0.03, 0.97, 0.03, nrm), margin=300)
            if sp and area(sp) > 0.4: out.append('<path d="%s" fill="%s" opacity=".62"/>' % (P(sp), '#2C2F35' if not night else '#090A0D'))
    return out

def draw_building(cam, th, sh, b, d, lens):
    th['_hz'] = haze_at(th, d) if lens is None else 0.0
    try: return _draw_building(cam, th, sh, b, d, lens)
    finally: th['_hz'] = 0.0

def _draw_building(cam, th, sh, b, d, lens):
    if th.get('wx') and b['tags'].get('building') == 'houseboat' and (lens is None or lens == 'truth'): return draw_houseboat(cam, th, sh, b, d, lens)
    night = th['night']; L = th['_L']
    poly = b['poly']
    out = []
    h, y0 = b['h'], b['min']
    if th.get('wx') and b['tags'].get('building') == 'houseboat': y0 = WATER_Y + 0.3; h = h + WATER_Y + 0.3     # afloat, below the quay
    g = b.get('guessed', {})
    roof = b['roof'] if not (lens == 'truth' and g.get('roof:shape')) else 'flat'
    if roof == 'quadruple_saltbox': roof = 'hipped'      # closest shape this renderer draws
    rh = b['roof_h'] if roof in ('gabled', 'pyramidal', 'hipped', 'skillion', 'mansard') and b['roof_h'] else (0 if roof in (None, 'flat') else None)
    win = {'fr': [], 'dark': [], 'lit': [], 'rev': [], 'sill': []}
    if rh is None: rh = 0                       # shape mapped but roof:height not: draw the walls to full height, roof flat
    wall_top = h - rh
    btype = b['tags'].get('building'); yr = b['year'] or 0
    modern = yr >= 1960 and btype not in ('church', 'cathedral', 'chapel')
    warehouse = btype in ('industrial', 'warehouse', 'service')
    places = b.get('places') or []
    church = btype in ('church', 'cathedral', 'chapel') or b['tags'].get('building:part') == 'church' or b.get('in_church')
    shopfloor = bool(th.get('wx') and lens is None and (places or btype in ('retail', 'commercial')))
    open_here = False; plinth, cornice = [], []
    if night and places:
        dy = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'][datetime.date(*th['date']).weekday()]
        open_here = any(p['tags'].get('opening_hours') and open_at(p['tags']['opening_hours'], dy, th['hhmm'], date=th['date']) is True for p in places)
    if roof == 'skillion' and rh:
        dirv = roof_dir(b)
        pr = [p[0] * dirv[0] + p[1] * dirv[1] for p in poly]; p0_ = min(pr); span = max(0.5, max(pr) - p0_)
        sk_top = lambda p: h - rh * ((p[0] * dirv[0] + p[1] * dirv[1]) - p0_) / span
    base = surface_colour(b, lens)
    n = len(poly)
    TD = b.get('trad') if (lens is None and th.get('wx')) else None
    ridge_u = trad_ridge(b, poly) if TD and roof in ('gabled', 'hipped') and rh else None
    for i in range(n):
        a, bb = poly[i], poly[(i + 1) % n]
        ex, ez = bb[0] - a[0], bb[1] - a[1]
        elen = math.hypot(ex, ez)
        if elen < 0.3: continue
        nrm = vnorm((ez, 0, -ex))
        if vdot(nrm, vsub(cam.C, ((a[0] + bb[0]) / 2, (y0 + wall_top) / 2, (a[1] + bb[1]) / 2))) <= 0: continue
        ncol = max(1, int(elen / 9)) if d < 460 else 1
        nrow = max(1, min(3, int((wall_top - y0) / 7))) if d < 460 else 1
        facing = vdot(nrm, L) > 0 and not night
        exact = th.get('_eye') and facing and lens is None and d < 110 and elen > 0.6
        prof = None
        if exact:
            # up close the shadow of the street's other side is a sharp line across the wall, traced from the real sun
            col_l, col_d = tohex(light(th, base, nrm, True)), tohex(light(th, base, nrm, False))
            out.append(face(cam, LV.quad_at(a, bb, y0, wall_top, 0, 1), col_l, ' stroke="%s" stroke-width=".3"' % col_l))
            ns = max(2, int(elen / 0.35)); prof = []
            for k_ in range(ns + 1):
                u = 0.02 + 0.96 * k_ / ns
                hs = sh.shadow_height(a[0] + ex * u + nrm[0] * 0.15, a[1] + ez * u + nrm[2] * 0.15, skip=b)
                prof.append((k_ / ns, min(wall_top, max(y0, hs))))
            strips = []
            for k_ in range(ns):
                (u0_, h0_), (u1_, h1_) = prof[k_], prof[k_ + 1]
                if h0_ <= y0 + 0.01 and h1_ <= y0 + 0.01: continue
                q = [(a[0] + ex * u0_ + nrm[0] * .01, y0, a[1] + ez * u0_ + nrm[2] * .01), (a[0] + ex * u1_ + nrm[0] * .01, y0, a[1] + ez * u1_ + nrm[2] * .01),
                     (a[0] + ex * u1_ + nrm[0] * .01, h1_, a[1] + ez * u1_ + nrm[2] * .01), (a[0] + ex * u0_ + nrm[0] * .01, h0_, a[1] + ez * u0_ + nrm[2] * .01)]
                sp_ = cam.poly(q, margin=300)
                if sp_: strips.append(P(sp_))
            if strips: out.append('<path d="%s" fill="%s"/>' % (''.join(strips), col_d))
        for r_ in range(nrow if not exact else 0):
            ya, yb = y0 + (wall_top - y0) * r_ / nrow, y0 + (wall_top - y0) * (r_ + 1) / nrow
            for c_ in range(ncol):
                u0, u1 = c_ / ncol, (c_ + 1) / ncol
                lit = facing and sh.lit((a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.15, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.15), skip=b)
                col = tohex(light(th, base, nrm, lit))
                out.append(face(cam, LV.quad_at(a, bb, ya, yb, u0, u1), col, ' stroke="%s" stroke-width=".3"' % col))
        if TD and not ('building:part' in b['tags'] and not church):
            out.extend(trad_wall(cam, th, sh, b, TD, a, bb, ex, ez, elen, nrm, y0, wall_top, d, facing, night, ridge_u, roof))
        if th.get('_eye') and th.get('wx') and lens is None and b.get('mat_draw') == 'brick' and d < 45 and not night:
            out.extend(CU.coursing(cam, a, bb, ex, ez, elen, nrm, y0 + (0.6 if y0 <= 0.5 else 0), wall_top))
        if lens == 'truth' and b.get('_hatch') and g.get('building:colour'):
            out.append(face(cam, LV.quad_at(a, bb, y0, wall_top, 0, 1), 'url(#hatchq)'))     # quest: colour unknown, hatched so it never reads as painted white
        if roof == 'skillion' and rh:
            ta, tb = sk_top(a), sk_top(bb)
            if max(ta, tb) > wall_top + 0.05:
                q = [(a[0], wall_top, a[1]), (bb[0], wall_top, bb[1]), (bb[0], tb, bb[1]), (a[0], ta, a[1])]
                lc = facing and sh.lit(((a[0] + bb[0]) / 2 + nrm[0] * .15, (wall_top + (ta + tb) / 2) / 2, (a[1] + bb[1]) / 2 + nrm[2] * .15), skip=b)
                col = tohex(light(th, base, nrm, lc)); out.append(face(cam, q, col, ' stroke="%s" stroke-width=".3"' % col))
        part = 'building:part' in b['tags'] and not church
        if th.get('wx') and lens is None and not part and d < 760:
            if y0 <= 0.5:          # a dark stone plinth: the building stands on the street, not above it
                sp = cam.poly(LV.quad_at(a, bb, y0, y0 + (0.6 if d < 300 else 0.9), 0, 1, 0.02, nrm), margin=200)
                if sp and area(sp) > 0.15: plinth.append(P(sp))
            if ((yr and yr < 1800) or roof == 'mansard' or (TD and TD.get('cornice'))) and wall_top - y0 > 5 and d < 520:      # a white cornice on the old canal houses, under a mansard, on a London terrace
                sp = cam.poly(LV.quad_at(a, bb, wall_top - 0.42, wall_top, 0, 1, 0.05, nrm), margin=200)
                if sp and area(sp) > 0.15: cornice.append(P(sp))
            if shopfloor and elen > 2.4 and y0 <= 0.5:      # a glazed ground floor where shops, cafés and hotels are mapped
                gq = cam.poly(LV.quad_at(a, bb, y0 + 0.65, y0 + 2.95, 0.05, 0.95, 0.03, nrm), margin=300)
                if gq and area(gq) > 0.4:
                    out.append('<path d="%s" fill="%s" stroke="%s" stroke-width=".8"%s/>' % (P(gq), ('#FFD08C' if open_here else '#0E1119') if night else 'url(#glass)', hzc(th, '#2A2D30' if not night else '#30343E'), (' fill-opacity="%.2f"' % (1 - 0.85 * th['_hz'])) if th.get('_hz', 0) > 0.04 and not (night and open_here) else ''))
            if btype == 'construction':
                out.append(face(cam, LV.quad_at(a, bb, y0, wall_top, 0, 1, 0.3, nrm), 'url(#scaf)'))
        if lens in (None, 'year') and th.get('wx') and not part and elen > 2.0 and d < th.get('_win_d', 390) * 1.35:
            out.extend(material_face(cam, th, b, a, bb, ex, ez, elen, nrm, y0, wall_top, d, base, night))
        # windows: a generic rendering of building:levels (guessed or mapped); tall canal-house windows
        if church and d < 520 and lens in (None, 'year') and th.get('wx'):
            out.extend(CU.church_windows(cam, th, a, bb, ex, ez, elen, nrm, y0, wall_top, d, tohex(light(th, mulc(base, 0.55), nrm, False)), night))
        elif d < th.get('_win_d', 390) and lens in (None, 'year') and elen > 2.2 and not part and btype != 'houseboat' and not b.get('no_win'):
            lv = b['levels']; fh = (wall_top - y0) / max(1, lv)
            deep = th.get('wx') and lens is None and d < 160       # close up, a window is a hole in the wall: reveal, recessed glass, sill
            paris = WIN_STYLE == 'paris' and not modern and b.get('mat_draw') in ('limestone', 'plaster', 'stone', 'sandstone')
            wcols = max(1, int(elen / (2.9 if paris else 3.4 if modern else 2.1)))
            TW = TD.get('win') if TD else None; shut = TD.get('shutter') if TD else None; leaves = []
            if TW: wcols = max(1, int(elen / TW['bay']))
            for r_ in range(1 if shopfloor else 0, lv):
                ya = y0 + r_ * fh + fh * (0.22 if r_ else 0.3); yb = ya + fh * ((0.62 if modern else 0.58) if r_ else 0.5)
                if paris: ya = y0 + r_ * fh + fh * 0.13; yb = ya + fh * 0.73          # French windows, nearly floor to ceiling
                if TW: ya = y0 + r_ * fh + fh * TW['top']; yb = ya + fh * TW['h']
                for c_ in range(wcols):
                    u0 = (c_ + (0.1 if modern else 0.31 if paris else 0.28)) / wcols; u1 = (c_ + (0.9 if modern else 0.69 if paris else 0.72)) / wcols
                    if TW: u0 = (c_ + 0.5 - TW['w'] / 2) / wcols; u1 = (c_ + 0.5 + TW['w'] / 2) / wcols
                    if shut and r_ > 0 or (shut and not shopfloor and r_ == 0):
                        lw = (u1 - u0) * 0.52
                        for (s0_, s1_) in ((u0 - lw, u0 - 0.004), (u1 + 0.004, u1 + lw)):
                            lq = cam.poly(LV.quad_at(a, bb, ya, yb, s0_, s1_, 0.07, nrm), margin=300)
                            if lq and area(lq) > 0.12: leaves.append(P(lq))
                    if prof:
                        um = (u0 + u1) / 2; k_ = min(len(prof) - 2, int(um * (len(prof) - 1))); f_ = um * (len(prof) - 1) - k_
                        wl = (ya + yb) / 2 > prof[k_][1] * (1 - f_) + prof[k_ + 1][1] * f_
                    else: wl = facing and sh.lit((a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.2, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.2), skip=b)
                    sp = cam.poly(LV.quad_at(a, bb, ya, yb, u0, u1, 0.04, nrm), margin=300)
                    rec = 0.04
                    if deep and sp and area(sp) > 70:
                        win['rev'].append(P(sp)); rec = -0.16
                        sp = cam.poly(LV.quad_at(a, bb, ya, yb, u0, u1, rec, nrm), margin=300)
                        du = 0.07 / elen
                        sl = cam.poly(LV.quad_at(a, bb, ya - 0.11, ya, u0 - du, u1 + du, 0.07, nrm), margin=300)
                        if sl: win['sill'].append(P(sl))
                    if sp and area(sp) > 0.25: win['lit' if wl else 'dark'].append(P(sp))
                    if sp and d < 140 and area(sp) > 30:      # close up, the sash bars of a canal-house window
                        q4 = LV.quad_at(a, bb, ya, yb, u0, u1, rec + 0.01, nrm)
                        m1, m2 = cam.proj(tuple((q4[0][k] + q4[1][k]) / 2 for k in range(3))), cam.proj(tuple((q4[2][k] + q4[3][k]) / 2 for k in range(3)))
                        h1 = cam.proj(tuple(q4[0][k] + (q4[3][k] - q4[0][k]) * 0.42 for k in range(3))); h2 = cam.proj(tuple(q4[1][k] + (q4[2][k] - q4[1][k]) * 0.42 for k in range(3)))
                        if m1 and m2 and h1 and h2: win['fr'].append('M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f' % (m1[0], m1[1], m2[0], m2[1], h1[0], h1[1], h2[0], h2[1]))
            fr = '#F2EEE6' if not night else '#3A3F4E'
            gd, gl = ('url(#glass)', 'url(#glassLit)') if th.get('wx') and not night else ('#2E3440' if not night else '#141824', '#9FB2C4')
            if warehouse and th.get('wx'): gd = gl = '#2C4A3B' if not night else '#0E1612'      # warehouse shutters, not glass
            if paris and not night: gl = gd                 # tall panes in a pale stone front read dark, sun or not
            if TW:
                fr = TW['frame'] if not night else '#3A3F4E'
                if TW.get('glass') == 'dark' and not night: gl = gd
            if leaves:          # open shutters either side of each window, in the paint of the house
                out.append('<path d="%s" fill="%s"/>' % (''.join(leaves), tohex(light(th, hx(shut), nrm, facing and not night))))
            fw = max(0.7, 0.07 * cam.focal / max(d, 1)) if deep else 0.7
            go = ' fill-opacity="%.2f"' % (1 - 0.85 * th['_hz']) if th.get('_hz', 0) > 0.04 else ''
            fr = hzc(th, fr)
            if win['rev']: out.append('<path d="%s" fill="%s"/>' % (''.join(win['rev']), tohex(light(th, mulc(base, 0.55), nrm, False))))
            if win['dark']: out.append('<path d="%s" fill="%s" stroke="%s" stroke-width="%.1f"%s/>' % (''.join(win['dark']), gd, fr, fw, go))
            if win['lit']: out.append('<path d="%s" fill="%s" stroke="%s" stroke-width="%.1f"%s/>' % (''.join(win['lit']), gl, fr, fw, go))
            if win['fr']: out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f"/>' % (''.join(win['fr']), fr, max(0.7, 0.045 * cam.focal / max(d, 1)) if deep else max(0.7, 9 / max(d, 1))))
            if win['sill']: out.append('<path d="%s" fill="%s"/>' % (''.join(win['sill']), hzc(th, '#DAD5CA' if not night else '#2E323E')))
            win = {'fr': [], 'dark': [], 'lit': [], 'rev': [], 'sill': []}
    if plinth: out.append('<path d="%s" fill="%s"/>' % (''.join(plinth), hzc(th, '#47443F' if not night else '#121318')))
    if cornice: out.append('<path d="%s" fill="%s"/>' % (''.join(cornice), hzc(th, '#E6E1D6' if not night else '#3A3C44')))
    edge = '#6F695F' if not night else '#2E3446'
    rbase = surface_colour(b, lens, 'roof')
    if roof == 'pyramidal' and rh:
        cx, cz = centroid(poly); apex = (cx, h, cz)
        for i in range(n):
            a, bb = poly[i], poly[(i + 1) % n]
            tri = [(a[0], wall_top, a[1]), (bb[0], wall_top, bb[1]), apex]
            nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
            if nr[1] < 0: nr = tuple(-v for v in nr)
            cen = tuple(sum(p[k] for p in tri) / 3 for k in range(3))
            if vdot(nr, vsub(cam.C, cen)) <= 0: continue
            out.append(face(cam, tri, tohex(light(th, rbase, nr, not night and vdot(nr, L) > 0 and sh.lit(cen, skip=b)))))
    elif roof in ('gabled', 'hipped') and rh and TD:
        out.extend(trad_roof(cam, th, sh, b, TD, poly, wall_top, h, rh, roof, rbase, base, edge, d, night, ridge_u))
    elif roof in ('gabled', 'hipped') and rh:
        # ridge along the longest side unless roof:orientation=across; a hipped roof pulls the ridge in at both ends
        edges = [(math.dist(poly[i], poly[(i + 1) % n]), i) for i in range(n)]
        _, i0 = max(edges)
        a, bb = poly[i0], poly[(i0 + 1) % n]
        ux, uz = (bb[0] - a[0]), (bb[1] - a[1]); Lg = math.hypot(ux, uz); ux, uz = ux / Lg, uz / Lg
        if b['orient'] == 'across': ux, uz = -uz, ux
        cx, cz = centroid(poly)
        proj = [((p[0] - cx) * ux + (p[1] - cz) * uz) for p in poly]
        s0, s1 = min(proj), max(proj)
        r0, r1 = (cx + ux * s0, h, cz + uz * s0), (cx + ux * s1, h, cz + uz * s1)
        gab, slopes = [], []           # gable walls first, so the roof covers any hidden notch
        for i in range(n):
            a, bb = poly[i], poly[(i + 1) % n]
            pa, pb = (a[0], wall_top, a[1]), (bb[0], wall_top, bb[1])
            # project each eave point onto the ridge line
            inset = min(rh, (s1 - s0) / 2) if roof == 'hipped' else 0
            def on_ridge(p):
                s = min(s1 - inset, max(s0 + inset, (p[0] - cx) * ux + (p[2] - cz) * uz))
                return (cx + ux * s, h, cz + uz * s)
            qa, qb = on_ridge(pa), on_ridge(pb)
            if math.dist(qa, qb) < 0.3 and roof == 'hipped':
                tri = [pa, pb, qa]
                nr = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
                if nr[1] < 0: nr = tuple(-v for v in nr)
                cen = tuple(sum(p[k] for p in tri) / 3 for k in range(3))
                if vdot(nr, vsub(cam.C, cen)) <= 0: continue
                slopes.append(face(cam, tri, tohex(light(th, rbase, nr, not night and vdot(nr, L) > 0 and sh.lit(cen, skip=b))), ' stroke="%s" stroke-width=".25"' % edge))
                continue
            if math.dist(qa, qb) < 0.3:
                # gable end: a vertical wall triangle in the wall material
                tri = [pa, pb, ((qa[0] + qb[0]) / 2, h, (qa[2] + qb[2]) / 2)]
                nr = vnorm(((a[1] + bb[1]) / 2 - cz, 0, 0)) if False else vnorm((bb[1] - a[1], 0, -(bb[0] - a[0])))
                cen = tuple(sum(p[k] for p in tri) / 3 for k in range(3))
                if vdot(nr, vsub(cam.C, cen)) <= 0: continue
                if th.get('wx') and lens is None and yr and yr < 1850 and d < 260:     # the hoist beam under an old gable
                    ap = tri[2]; hb0 = cam.proj((ap[0], h - rh * 0.25, ap[2])); hb1 = cam.proj((ap[0] + nr[0] * 0.7, h - rh * 0.25, ap[2] + nr[2] * 0.7))
                    if hb0 and hb1: slopes.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#2A221C" stroke-width="%.2f" stroke-linecap="round"/>' % (hb0[0], hb0[1], hb1[0], hb1[1], max(0.5, 0.12 * cam.focal / d)))
                gab.append(face(cam, tri, tohex(light(th, base, nr, not night and vdot(nr, L) > 0 and sh.lit((cen[0] + nr[0] * .2, cen[1], cen[2] + nr[2] * .2), skip=b))), ' stroke="%s" stroke-width=".25"' % edge))
                continue
            q = [pa, pb, qb, qa]
            nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0])))
            if nr[1] < 0: nr = tuple(-v for v in nr)
            cen = tuple(sum(p[k] for p in q) / 4 for k in range(3))
            if vdot(nr, vsub(cam.C, cen)) <= 0: continue
            slopes.append(face(cam, q, tohex(light(th, rbase, nr, not night and vdot(nr, L) > 0 and sh.lit(cen, skip=b))), ' stroke="%s" stroke-width=".25"' % edge))
        out.extend(gab + slopes)
    elif roof == 'skillion' and rh:
        s_ = rh / span; nr = vnorm((dirv[0] * s_, 1, dirv[1] * s_))
        cx, cz = centroid(poly); cen = (cx, h - rh / 2, cz)
        if vdot(nr, vsub(cam.C, cen)) > 0:
            lr = not night and vdot(nr, L) > 0 and sh.lit((cx, h - rh / 2 + 0.1, cz), skip=b)
            out.append(face(cam, [(p[0], sk_top(p), p[1]) for p in poly], tohex(light(th, rbase, nr, lr)), ' stroke="%s" stroke-width=".45"' % edge))
    elif roof == 'mansard' and rh:
        out.extend(mansard(cam, th, sh, b, poly, wall_top, h, rh, rbase, edge, d, lens, night))
    elif cam.C[1] > h:
        cx, cz = centroid(poly)
        litR = not night and sh.lit((cx, h + 0.05, cz), skip=b)
        out.append(face(cam, [(x, h, z) for (x, z) in poly], tohex(light(th, rbase, (0, 1, 0), litR)), ' stroke="%s" stroke-width=".45"' % edge))
    if TD and TD.get('chimney') and d < th.get('_win_d', 390) * 1.2:
        out.extend(trad_chimneys(cam, th, sh, b, TD, poly, wall_top, h, rh, roof, ridge_u, night))
    return out

# ------------------------------------------------------------------ the kit: details a building tradition gives (render/traditions)
def trad_ridge(b, poly):
    """The ridge's direction: along the longest side, across it where roof:orientation=across (mapped or guessed)."""
    n = len(poly); i0 = max(range(n), key=lambda i: math.dist(poly[i], poly[(i + 1) % n]))
    a, c = poly[i0], poly[(i0 + 1) % n]; ux, uz = c[0] - a[0], c[1] - a[1]; Lg = math.hypot(ux, uz) or 1; ux, uz = ux / Lg, uz / Lg
    return (-uz, ux) if b.get('orient') == 'across' else (ux, uz)

def offset_ring(poly, dl):
    """The ring moved dl metres outward (eaves, verges), or None where a side would turn over."""
    n = len(poly); i0 = max(range(n), key=lambda i: math.dist(poly[i], poly[(i + 1) % n]))
    a, c = poly[i0], poly[(i0 + 1) % n]; L0 = math.dist(a, c) or 1
    s = 1 if pip((a[0] + c[0]) / 2 - (c[1] - a[1]) / L0 * 0.05, (a[1] + c[1]) / 2 + (c[0] - a[0]) / L0 * 0.05, poly) else -1
    lines = []
    for i in range(n):
        a, c = poly[i], poly[(i + 1) % n]; ex, ez = c[0] - a[0], c[1] - a[1]; Le = math.hypot(ex, ez) or 1
        lines.append((a[0] + ez / Le * s * dl, a[1] - ex / Le * s * dl, ex / Le, ez / Le))
    out = []
    for i in range(n):
        p1x, p1z, d1x, d1z = lines[i - 1]; p2x, p2z, d2x, d2z = lines[i]
        den = d1x * d2z - d1z * d2x
        if abs(den) < 1e-4: x, z = p2x, p2z
        else:
            t = ((p2x - p1x) * d2z - (p2z - p1z) * d2x) / den; x, z = p1x + d1x * t, p1z + d1z * t
        vx, vz = x - poly[i][0], z - poly[i][1]; m = math.hypot(vx, vz)
        if m > 2.6 * dl: x, z = poly[i][0] + vx / m * 2.6 * dl, poly[i][1] + vz / m * 2.6 * dl
        out.append((x, z))
    for i in range(n):
        e0 = (poly[(i + 1) % n][0] - poly[i][0], poly[(i + 1) % n][1] - poly[i][1]); e1 = (out[(i + 1) % n][0] - out[i][0], out[(i + 1) % n][1] - out[i][1])
        if e0[0] * e1[0] + e0[1] * e1[1] < 0: return None
    return out

def box3(cam, th, sh, b, c0, u, hu, hv, y0, y1, col, night):
    """A small lit box (a chimney stack, a pot): centre c0, axis u, half sizes, from y0 to y1."""
    L = th['_L']; (cx, cz), (ux, uz) = c0, u; vx, vz = -uz, ux
    P8 = lambda su, sv, y: (cx + ux * su * hu + vx * sv * hv, y, cz + uz * su * hu + vz * sv * hv)
    faces = [((ux, 0, uz), [P8(1, -1, y0), P8(1, 1, y0), P8(1, 1, y1), P8(1, -1, y1)]), ((-ux, 0, -uz), [P8(-1, 1, y0), P8(-1, -1, y0), P8(-1, -1, y1), P8(-1, 1, y1)]),
             ((vx, 0, vz), [P8(1, 1, y0), P8(-1, 1, y0), P8(-1, 1, y1), P8(1, 1, y1)]), ((-vx, 0, -vz), [P8(-1, -1, y0), P8(1, -1, y0), P8(1, -1, y1), P8(-1, -1, y1)]),
             ((0, 1, 0), [P8(-1, -1, y1), P8(1, -1, y1), P8(1, 1, y1), P8(-1, 1, y1)])]
    out = []
    for n3, q in faces:
        m = tuple(sum(p[k] for p in q) / 4 for k in range(3))
        if vdot(n3, vsub(cam.C, m)) <= 0: continue
        lit = (not night) and vdot(n3, L) > 0 and sh.lit((m[0] + n3[0] * .2, m[1] + n3[1] * .2, m[2] + n3[2] * .2), skip=b)
        c_ = tohex(light(th, col, n3, lit)); f = face(cam, q, c_, ' stroke="%s" stroke-width=".25"' % c_)
        if f: out.append(f)
    return out

def trad_wall(cam, th, sh, b, TD, a, bb, ex, ez, elen, nrm, y0, wall_top, d, facing, night, ridge_u, roof):
    """On one wall: a stucco ground floor under brick (London), timber boards and white corner boards (Norway), the shadow of the eaves."""
    out = []; ppm = cam.focal / max(d, 1)
    bd = TD.get('band')
    if bd and y0 <= b.get('_gy', 0.0) + 0.5:
        bh = min(bd['h'], (wall_top - y0) * 0.45)
        lt = facing and sh.lit((a[0] + ex * .5 + nrm[0] * .2, y0 + bh / 2, a[1] + ez * .5 + nrm[2] * .2), skip=b)
        f = face(cam, LV.quad_at(a, bb, y0, y0 + bh, 0, 1, 0.02, nrm), tohex(light(th, hx(bd['colour']), nrm, lt)))
        if f: out.append(f)
    bo = TD.get('boards')
    if bo:
        if ppm * bo['spacing'] > 2.2 and not night:
            nb = int(elen / bo['spacing']); segs = []
            for k in range(1, nb):
                u = k / nb; p0 = cam.proj((a[0] + ex * u + nrm[0] * .02, y0 + 0.25, a[1] + ez * u + nrm[2] * .02)); p1 = cam.proj((a[0] + ex * u + nrm[0] * .02, wall_top - 0.05, a[1] + ez * u + nrm[2] * .02))
                if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
            if segs: out.append('<path d="%s" stroke="%s" stroke-width=".45" opacity=".32" fill="none"/>' % (''.join(segs), tohex(mulc(hx(b.get('col_draw') or '#888888'), 0.62))))
        if ppm * 0.16 > 0.5 and elen > 1.0:
            cw = 0.16 / elen; c_ = tohex(light(th, hx(bo['corner']), nrm, facing and not night))
            for (u0, u1) in ((0, cw), (1 - cw, 1)):
                f = face(cam, LV.quad_at(a, bb, y0, wall_top, u0, u1, 0.03, nrm), c_)
                if f: out.append(f)
    E = TD.get('eaves') or 0
    if E and not night and roof in ('gabled', 'hipped') and (roof == 'hipped' or (ridge_u and abs(ex * ridge_u[0] + ez * ridge_u[1]) / max(elen, 1e-6) > 0.7)):
        f = face(cam, LV.quad_at(a, bb, wall_top - min(1.1, E * 1.7), wall_top, 0, 1, 0.025, nrm), '#141822', ' opacity=".26"')
        if f: out.append(f)
    return out

def trad_roof(cam, th, sh, b, TD, poly, wall_top, h, rh, roof, rbase, base, edge, d, night, ridge_u):
    """A gabled or hipped roof with the tradition's eaves and verges overhanging the walls, tile courses up close, and a lit ridge."""
    L = th['_L']; n = len(poly); ux, uz = ridge_u; cx, cz = centroid(poly)
    hd = max(1.0, max(abs(-(p[0] - cx) * uz + (p[1] - cz) * ux) for p in poly))
    slope = rh / hd
    E = TD.get('eaves') or 0
    ring = offset_ring(poly, E) if E else None
    if not ring: ring, E = poly, 0
    gab, slopes, rows, ridge = [], [], [], None
    def on_ridge_f(pp):
        proj = [((p[0] - cx) * ux + (p[1] - cz) * uz) for p in pp]; s0, s1 = min(proj), max(proj)
        return s0, s1
    # the gable ends, in the wall's own plane and material
    if roof == 'gabled':
        s0, s1 = on_ridge_f(poly)
        for i in range(n):
            a, bb = poly[i], poly[(i + 1) % n]
            pa, pb = (a[0], wall_top, a[1]), (bb[0], wall_top, bb[1])
            qa = min(s1, max(s0, (a[0] - cx) * ux + (a[1] - cz) * uz)); qb = min(s1, max(s0, (bb[0] - cx) * ux + (bb[1] - cz) * uz))
            if abs(qa - qb) > 0.3: continue
            apex = (cx + ux * (qa + qb) / 2, h, cz + uz * (qa + qb) / 2)
            nr = vnorm((bb[1] - a[1], 0, -(bb[0] - a[0])))
            if pip((a[0] + bb[0]) / 2 + nr[0] * 0.05, (a[1] + bb[1]) / 2 + nr[2] * 0.05, poly): nr = tuple(-v for v in nr)
            tri = [pa, pb, apex]; cen = tuple(sum(p[k] for p in tri) / 3 for k in range(3))
            if vdot(nr, vsub(cam.C, cen)) <= 0: continue
            if TD.get('hoist') and d < 260:
                hb0 = cam.proj((apex[0], h - rh * 0.25, apex[2])); hb1 = cam.proj((apex[0] + nr[0] * 0.7, h - rh * 0.25, apex[2] + nr[2] * 0.7))
                if hb0 and hb1: rows.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#2A221C" stroke-width="%.2f" stroke-linecap="round"/>' % (hb0[0], hb0[1], hb1[0], hb1[1], max(0.5, 0.12 * cam.focal / d)))
            gab.append(face(cam, tri, tohex(light(th, base, nr, not night and vdot(nr, L) > 0 and sh.lit((cen[0] + nr[0] * .2, cen[1], cen[2] + nr[2] * .2), skip=b))), ' stroke="%s" stroke-width=".25"' % edge))
    # the slopes, from the overhanging eaves up to the ridge
    s0, s1 = on_ridge_f(ring); ye = wall_top - E * slope
    inset = min(rh + E, (s1 - s0) / 2) if roof == 'hipped' else 0
    r0, r1 = (cx + ux * (s0 + inset), h, cz + uz * (s0 + inset)), (cx + ux * (s1 - inset), h, cz + uz * (s1 - inset))
    tiles = b.get('rmat_draw') in ('roof_tiles', 'slate') and not night
    rstep = 0.33 if b.get('rmat_draw') == 'roof_tiles' else 0.26
    ppm = cam.focal / max(d, 1)
    m = len(ring)
    for i in range(m):
        a, bb = ring[i], ring[(i + 1) % m]
        pa, pb = (a[0], ye, a[1]), (bb[0], ye, bb[1])
        def on_ridge(p):
            s = min(s1 - inset, max(s0 + inset, (p[0] - cx) * ux + (p[2] - cz) * uz)); return (cx + ux * s, h, cz + uz * s)
        qa, qb = on_ridge(pa), on_ridge(pb)
        if math.dist(qa, qb) < 0.3:
            if roof != 'hipped': continue
            q = [pa, pb, qa]
        else: q = [pa, pb, qb, qa]
        nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[-1], q[0])))
        if nr[1] < 0: nr = tuple(-v for v in nr)
        cen = tuple(sum(p[k] for p in q) / len(q) for k in range(3))
        if vdot(nr, vsub(cam.C, cen)) <= 0: continue
        lit = not night and vdot(nr, L) > 0 and sh.lit(cen, skip=b)
        slopes.append(face(cam, q, tohex(light(th, rbase, nr, lit)), ' stroke="%s" stroke-width=".25"' % edge))
        if tiles and ppm * rstep > 2.0:          # the courses of tiles or slates, parallel to the eaves
            sl = math.dist(pa, qa) if len(q) == 4 else math.dist(pa, qa); k = int(sl / rstep); seg = []
            for j in range(1, k):
                t = j / k
                A_ = tuple(pa[c] + (qa[c] - pa[c]) * t for c in range(3)); B_ = tuple(pb[c] + ((qb if len(q) == 4 else qa)[c] - pb[c]) * t for c in range(3))
                p0, p1 = cam.proj(A_), cam.proj(B_)
                if p0 and p1: seg.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
            if seg: rows.append('<path d="%s" stroke="%s" stroke-width="%.2f" opacity=".3" fill="none"/>' % (''.join(seg), tohex(mulc(hx(rbase if isinstance(rbase, str) else tohex(rbase)), 0.55)), max(0.35, ppm * 0.03)))
    out = gab + slopes + rows
    if not night and math.dist(r0, r1) > 0.3:          # the ridge catches the light, as on a model
        p0, p1 = cam.proj(r0), cam.proj(r1)
        if p0 and p1: out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" opacity=".55" stroke-linecap="round"/>' % (p0[0], p0[1], p1[0], p1[1], hzc(th, '#F4EEE2'), max(0.45, ppm * 0.08)))
    return out

def trad_chimneys(cam, th, sh, b, TD, poly, wall_top, h, rh, roof, ridge_u, night):
    """Chimney stacks as the tradition builds them: on the party walls with pots (London), one with a tile cap (Tuscany), one plain (Norway)."""
    C = TD['chimney']; rnd = random.Random(b['id'] * 3 + 1); out = []
    col = hx(C.get('colour', '#9A8A78')); cx, cz = centroid(poly)
    if ridge_u: ux, uz = ridge_u
    else:
        n = len(poly); i0 = max(range(n), key=lambda i: math.dist(poly[i], poly[(i + 1) % n])); a, c = poly[i0], poly[(i0 + 1) % n]
        Lg = math.dist(a, c) or 1; ux, uz = (c[0] - a[0]) / Lg, (c[1] - a[1]) / Lg
        if C['kind'] == 'party': ux, uz = -uz, ux          # a flat-roofed terrace: stacks on the side walls
    proj = [((p[0] - cx) * ux + (p[1] - cz) * uz) for p in poly]; s0, s1 = min(proj), max(proj)
    top = h if roof in ('gabled', 'hipped') else wall_top
    if C['kind'] == 'party' and b.get('party'):
        # on the walls shared with the neighbours, as a terrace and a Paris block have them: a stack astride each, its pots in a row
        for (a, c) in sorted(b['party'], key=lambda e: -math.dist(*e))[:2]:
            Lw = math.dist(a, c); u = ((c[0] - a[0]) / Lw, (c[1] - a[1]) / Lw); m = ((a[0] + c[0]) / 2, (a[1] + c[1]) / 2)
            hu = min(1.2, Lw * 0.22)
            out.extend(box3(cam, th, sh, b, m, u, hu, 0.32, top - 0.6, top + 1.2, col, night))
            for k in (-0.6, 0.0, 0.6):
                pc = (m[0] + u[0] * hu * k, m[1] + u[1] * hu * k)
                out.extend(box3(cam, th, sh, b, pc, u, 0.09, 0.09, top + 1.2, top + 1.6, hx(C.get('pots', '#B06A45')), night))
        return out
    if C['kind'] == 'party':
        spots = [s0 + 0.35, s1 - 0.35]; hu, hv, rise = 0.35, 0.95, 1.3
    elif C['kind'] == 'capped':
        spots = [s0 + (s1 - s0) * rnd.uniform(0.25, 0.75)]; hu, hv, rise = 0.32, 0.32, 1.0
    else:
        spots = [s0 + (s1 - s0) * rnd.uniform(0.3, 0.7)]; hu, hv, rise = 0.28, 0.28, 0.9
    for sp_ in spots:
        c0 = (cx + ux * sp_, cz + uz * sp_)
        out.extend(box3(cam, th, sh, b, c0, (ux, uz), hu, hv, top - 0.6, top + rise, col, night))
        if C['kind'] == 'party':          # the pots in a row along the stack
            for k in (-0.55, 0.0, 0.55):
                pc = (c0[0] - uz * k, c0[1] + ux * k)
                out.extend(box3(cam, th, sh, b, pc, (ux, uz), 0.09, 0.09, top + rise, top + rise + 0.42, hx(C.get('pots', '#B06A45')), night))
        elif C['kind'] == 'capped':
            out.extend(box3(cam, th, sh, b, c0, (ux, uz), hu + 0.12, hv + 0.12, top + rise, top + rise + 0.14, hx(C.get('cap', '#9E5A3C')), night))
    return out

def inset_ring(poly, dl):
    """The ring moved dl metres inward along every side (mitres capped), or None where the shape is too thin or too notched for it."""
    n = len(poly); i0 = max(range(n), key=lambda i: math.dist(poly[i], poly[(i + 1) % n]))
    a, c = poly[i0], poly[(i0 + 1) % n]; L0 = math.dist(a, c) or 1
    s = 1 if pip((a[0] + c[0]) / 2 - (c[1] - a[1]) / L0 * 0.05, (a[1] + c[1]) / 2 + (c[0] - a[0]) / L0 * 0.05, poly) else -1
    lines = []
    for i in range(n):
        a, c = poly[i], poly[(i + 1) % n]; ex, ez = c[0] - a[0], c[1] - a[1]; Le = math.hypot(ex, ez) or 1
        nx, nz = -ez / Le * s, ex / Le * s
        lines.append((a[0] + nx * dl, a[1] + nz * dl, ex / Le, ez / Le))
    out = []
    for i in range(n):
        p1x, p1z, d1x, d1z = lines[i - 1]; p2x, p2z, d2x, d2z = lines[i]
        den = d1x * d2z - d1z * d2x
        if abs(den) < 1e-4: x, z = p2x, p2z
        else:
            t = ((p2x - p1x) * d2z - (p2z - p1z) * d2x) / den; x, z = p1x + d1x * t, p1z + d1z * t
        vx, vz = x - poly[i][0], z - poly[i][1]; m = math.hypot(vx, vz)
        if m > 2.6 * dl: x, z = poly[i][0] + vx / m * 2.6 * dl, poly[i][1] + vz / m * 2.6 * dl
        out.append((x, z))
    if signed_area(out) * signed_area(poly) <= 0 or abs(signed_area(out)) < 0.2 * abs(signed_area(poly)): return None
    for i in range(n):
        e0 = (poly[(i + 1) % n][0] - poly[i][0], poly[(i + 1) % n][1] - poly[i][1]); e1 = (out[(i + 1) % n][0] - out[i][0], out[(i + 1) % n][1] - out[i][1])
        if e0[0] * e1[0] + e0[1] * e1[1] < 0: return None              # a side turned over: the inset crossed itself
    if not all(pip(x, z, poly) for (x, z) in out): return None
    return out

def mansard(cam, th, sh, b, poly, wall_top, h, rh, rbase, edge, d, lens, night):
    """roof:shape=mansard: the steep lower slope (the brisis) set back about a third of the roof height from the cornice, dormers in it over the
    window bays below, and the near-flat top (the terrasson). Where the footprint is too thin to set back, the slope stands upright."""
    L = th['_L']; out = []; n = len(poly)
    ins = None
    for dl in (min(1.4, rh * 0.36), min(0.8, rh * 0.22)):
        ins = inset_ring(poly, dl)
        if ins: break
    if not ins: ins, dl = list(poly), 0.0
    paris = WIN_STYLE == 'paris'
    dorm = th.get('wx') and lens in (None, 'year') and d < th.get('_win_d', 390) * 1.1
    fronts, panes, caps, fcol = [], [], [], None
    for i in range(n):
        a, bb = poly[i], poly[(i + 1) % n]; ia, ib = ins[i], ins[(i + 1) % n]
        elen = math.dist(a, bb)
        if elen < 0.3: continue
        q = [(a[0], wall_top, a[1]), (bb[0], wall_top, bb[1]), (ib[0], h, ib[1]), (ia[0], h, ia[1])]
        nr = vnorm(vcross(vsub(q[1], q[0]), vsub(q[3], q[0])))
        if nr[1] < 0: nr = tuple(-v for v in nr)
        if abs(nr[1]) < 1e-3:                 # upright: face it outward
            nr = vnorm((bb[1] - a[1], 0, -(bb[0] - a[0])))
            if pip((a[0] + bb[0]) / 2 + nr[0] * 0.05, (a[1] + bb[1]) / 2 + nr[2] * 0.05, poly): nr = tuple(-v for v in nr)
        cen = tuple(sum(p[k] for p in q) / 4 for k in range(3))
        if vdot(nr, vsub(cam.C, cen)) <= 0: continue
        lit = not night and vdot(nr, L) > 0 and sh.lit((cen[0] + nr[0] * .25, cen[1] + nr[1] * .25, cen[2] + nr[2] * .25), skip=b)
        col = tohex(light(th, rbase, nr, lit))
        out.append(face(cam, q, col, ' stroke="%s" stroke-width=".25"' % edge))
        if b.get('rmat_draw') == 'metal' and not night and cam.focal * 0.55 / max(d, 1) > 2.2 and elen > 1.2:
            ns = int(elen / 0.55); sg = []          # the standing seams of zinc, from the eaves to the top
            for k in range(1, ns):
                f_ = k / ns; p0 = cam.proj((a[0] + (bb[0] - a[0]) * f_, wall_top, a[1] + (bb[1] - a[1]) * f_)); p1 = cam.proj((ia[0] + (ib[0] - ia[0]) * f_, h, ia[1] + (ib[1] - ia[1]) * f_))
                if p0 and p1: sg.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
            if sg: out.append('<path d="%s" stroke="%s" stroke-width=".4" opacity=".35" fill="none"/>' % (''.join(sg), tohex(mulc(hx(col), 0.7))))
        if not dorm or elen < 2.4 or rh < 2.4: continue
        # dormers: one over each window bay, their fronts upright in the slope a third of the way up
        wn = vnorm((nr[0], 0, nr[2])) if abs(nr[1]) < 0.999 else (0, 0, 1)
        nd = max(1, int(elen / (2.9 if paris else 2.4)))
        f = 0.3; yb_, yt_ = wall_top + f * rh, wall_top + min(rh - 0.3, f * rh + 1.75)
        ox, oz = (ia[0] - a[0]) * f, (ia[1] - a[1]) * f; ox2, oz2 = (ib[0] - bb[0]) * f, (ib[1] - bb[1]) * f
        A0 = (a[0] + ox, a[1] + oz); B0 = (bb[0] + ox2, bb[1] + oz2)
        ex, ez = B0[0] - A0[0], B0[1] - A0[1]
        wlit = not night and vdot(wn, L) > 0 and sh.lit((cen[0] + wn[0] * .4, yt_, cen[2] + wn[2] * .4), skip=b)
        fcol = tohex(light(th, hx('#DDD8CE'), wn, wlit))
        for k in range(nd):
            um = (k + 0.5) / nd; hw = min(0.6, 0.32 * elen / nd) / max(elen, 0.1)
            u0, u1 = um - hw, um + hw
            P3 = lambda u, y, o=0.02: (A0[0] + ex * u + wn[0] * o, y, A0[1] + ez * u + wn[2] * o)
            sp = cam.poly([P3(u0, yb_), P3(u1, yb_), P3(u1, yt_), P3(u0, yt_)], margin=300)
            if not sp or area(sp) < 0.12: continue
            fronts.append(P(sp))
            hp = cam.poly([P3(u0 - hw * 0.12, yt_), P3(u1 + hw * 0.12, yt_), P3(um, yt_ + 0.45)], margin=300)
            if hp: caps.append(P(hp))
            gp = cam.poly([P3(u0 + hw * 0.4, yb_ + 0.12, 0.04), P3(u1 - hw * 0.4, yb_ + 0.12, 0.04), P3(u1 - hw * 0.4, yt_ - 0.22, 0.04), P3(u0 + hw * 0.4, yt_ - 0.22, 0.04)], margin=300)
            if gp and area(gp) > 0.08: panes.append(P(gp))
    if cam.C[1] > h:
        cx, cz = centroid(ins)
        litR = not night and sh.lit((cx, h + 0.05, cz), skip=b)
        out.append(face(cam, [(x, h, z) for (x, z) in ins], tohex(light(th, rbase, (0, 1, 0), litR)), ' stroke="%s" stroke-width=".45"' % edge))
    if fronts:
        out.append('<path d="%s" fill="%s"/>' % (''.join(fronts), fcol))
        if caps: out.append('<path d="%s" fill="%s"/>' % (''.join(caps), tohex(mulc(hx(fcol), 0.8))))
        if panes: out.append('<path d="%s" fill="%s"/>' % (''.join(panes), hzc(th, '#3A4350' if not night else '#10141C')))
    return out

def draw_tree3d(cam, th, sh, n, d, lens):
    """A generic tree from the tags height and diameter_crown (guessed when not mapped)."""
    night = th['night']; L = th['_L']
    if th.get('wx') and lens is None:
        th['_hz'] = haze_at(th, d)
        try: return CU.tree_close(cam, th, sh, n, d, light, None)
        finally: th['_hz'] = 0.0
    h, r = n['h'], n['crown'] / 2
    p0, p1 = cam.proj((n['x'], 0, n['z'])), cam.proj((n['x'], h * 0.55, n['z']))
    if not (p0 and p1): return []
    out = ['<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (p0[0], p0[1], p1[0], p1[1], '#4A3A2E' if not night else '#0C100E', max(0.6, 0.3 * cam.focal / d))]
    truthy = lens == 'truth' and n['guessed']
    base = hx('#4F6E37' if th.get('wx') else '#6E9450') if not truthy else hx('#FFFFFF')
    if lens and lens.startswith('tag:'): base = hx(LENS_COL[tag_state(n, lens[4:], 'tree')] if lens[4:] in TREE_KEYS else '#DCE3D6')
    rnd = random.Random(n['id'])
    blobs = []
    nb = 26 if (d < 160 and th.get('wx')) else (9 if d < 400 else 5)
    for k in range(nb):
        ang = rnd.uniform(0, 2 * math.pi); rad = r * math.sqrt(rnd.random()) * (0.8 if nb > 9 else 0.7)
        bx, bz = n['x'] + rad * math.cos(ang), n['z'] + rad * math.sin(ang)
        by = h * 0.68 + rnd.uniform(-0.3, 0.3) * r
        c = cam.cam((bx, by, bz))
        if c[2] > 1: blobs.append((c[2], bx, by, bz, r * (rnd.uniform(0.26, 0.4) if nb > 9 else rnd.uniform(0.38, 0.55))))
    blobs.sort(key=lambda b: -b[0])
    lit = (not night) and sh.lit((n['x'] + L[0] * r, h * 0.75 + L[1] * r, n['z'] + L[2] * r))
    dark = light(th, mulc(base, 0.6), (0, 1, 0), False); mid = light(th, base, vnorm((L[0], 0.7, L[2])), lit)
    under, top = [], []
    for (dd, bx, by, bz, br) in blobs:
        p = cam.proj((bx, by, bz)); rr = br * cam.focal / dd
        under.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0], p[1], rr))
        sx = cam.proj((bx + L[0] * br, by + L[1] * br, bz + L[2] * br)) if not night else None
        if sx: top.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0] + (sx[0] - p[0]) * .3, p[1] + (sx[1] - p[1]) * .3, rr * 0.78))
    lf = (' filter="url(#foliage)"' if d < 220 else ' filter="url(#leaf)"') if th.get('wx') and d < 700 else ''     # leaves: ragged edges, light through gaps
    out.append('<g fill="%s"%s%s>%s</g>' % (tohex(dark), ' stroke="#B9B2A6" stroke-width=".4"' if truthy else '', lf, ''.join(under)))
    if top: out.append('<g fill="%s"%s>%s</g>' % (tohex(mid), lf, ''.join(top)))
    return out

def draw_tree_symbol(cam, th, n):
    # position mapped, size not: a flat crown symbol at a fixed 3 m radius
    pts = [(n['x'] + 3 * math.cos(a), 0.3, n['z'] + 3 * math.sin(a)) for a in [k * math.pi / 8 for k in range(16)]]
    sp = cam.poly(pts)
    if not sp: return ''
    col = '#7FA864' if not th['night'] else '#1B2A22'
    return '<path d="%s" fill="%s" stroke="%s" stroke-width=".8" opacity=".92"/>' % (P(sp), col, '#4E7A44' if not th['night'] else '#10181A')

def draw_lamp(cam, th, n, d):
    """highway=street_lamp: a generic mast and lantern (no tag gives the model here)."""
    p0, p1 = cam.proj((n['x'], 0, n['z'])), cam.proj((n['x'], 4.2, n['z']))
    if not (p0 and p1): return []
    night = th['night']; s = cam.focal / d
    out = []
    if night and th.get('wet'):        # wet stones mirror the lamp as a long warm streak
        out.append('<ellipse cx="%.1f" cy="%.1f" rx="%.2f" ry="%.1f" fill="url(#streak)" opacity=".75"/>' % (p0[0], p0[1] + 2.6 * s, max(0.5, 0.5 * s), max(2.5, 3.4 * s)))
    out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (p0[0], p0[1], p1[0], p1[1], '#2A2E33' if not night else '#161A22', max(0.5, 0.13 * s)))
    hw, hh = max(0.6, 0.2 * s), max(0.9, 0.5 * s)
    lamp = '#FFE7B8' if night else '#3B4148'
    out.append('<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ" fill="%s"/>' % (p1[0] - hw, p1[1], p1[0] + hw, p1[1], p1[0] + hw * 0.7, p1[1] + hh, p1[0] - hw * 0.7, p1[1] + hh, lamp))
    out.append('<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1fZ" fill="%s"/>' % (p1[0] - hw * 1.2, p1[1], p1[0] + hw * 1.2, p1[1], p1[0], p1[1] - hh * 0.6, '#22262B' if not night else '#2A2620'))
    if not night and s > 1.2: out.append('<path d="M%.1f %.1fv%.1f" stroke="#E6ECF2" stroke-width="%.2f" opacity=".7"/>' % (p1[0] - hw * 0.4, p1[1] + hh * 0.2, hh * 0.6, max(0.3, 0.05 * s)))
    if night: out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#halo)"/>' % (p1[0], p1[1] + hh * 0.5, max(4, 2.6 * s)))
    return out

def railing(cam, th, line, y0):
    """bridge=yes: a generic iron railing, posts and two rails, in short pieces so it sorts with everything else."""
    out = []; night = th['night']; col = '#26292C' if not night else '#0A0B0E'
    for i in range(len(line) - 1):
        a, b = line[i], line[i + 1]; Lg = math.dist(a, b); k = 0.0
        while k < Lg:
            k2 = min(Lg, k + 2.0)
            pa = (a[0] + (b[0] - a[0]) * k / Lg, a[1] + (b[1] - a[1]) * k / Lg); pb = (a[0] + (b[0] - a[0]) * k2 / Lg, a[1] + (b[1] - a[1]) * k2 / Lg)
            c = cam.cam(((pa[0] + pb[0]) / 2, y0 + 0.5, (pa[1] + pb[1]) / 2))
            if c[2] < 0.8 or c[2] > 220: k = k2; continue
            s = cam.focal / c[2]; segs = []
            for yy in (y0 + 1.0, y0 + 0.12):
                p0, p1 = cam.proj((pa[0], yy, pa[1])), cam.proj((pb[0], yy, pb[1]))
                if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
            m = max(1, int((k2 - k) / 0.32))
            for j in range(m + 1):
                f = j / m; px, pz = pa[0] + (pb[0] - pa[0]) * f, pa[1] + (pb[1] - pa[1]) * f
                p0, p1 = cam.proj((px, y0 + 0.12, pz)), cam.proj((px, y0 + 1.0, pz))
                if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
            if segs: out.append((c[2], 'svg', '<path d="%s" stroke="%s" stroke-width="%.2f" stroke-linecap="round" fill="none"/>' % (''.join(segs), col, max(0.4, 0.045 * s))))
            k = k2
    return out

def road_grid(S):
    if '_rg' not in S:
        g = collections.defaultdict(list)
        for r in S['roads']:
            for a, b in zip(r['pts'], r['pts'][1:]):
                for gx in range(int(min(a[0], b[0]) // 20) - 1, int(max(a[0], b[0]) // 20) + 2):
                    for gz in range(int(min(a[1], b[1]) // 20) - 1, int(max(a[1], b[1]) // 20) + 2): g[(gx, gz)].append((a, b))
        S['_rg'] = g
    return S['_rg']

def road_dist(S, x, z):
    best = 99.0
    for a, b in road_grid(S).get((int(x // 20), int(z // 20)), ()):
        ux, uz = b[0] - a[0], b[1] - a[1]; L2 = ux * ux + uz * uz or 1
        t = max(0, min(1, ((x - a[0]) * ux + (z - a[1]) * uz) / L2))
        best = min(best, math.hypot(a[0] + ux * t - x, a[1] + uz * t - z))
    return best

def street_wall(S, b, x, z):
    """The wall of building b that a door or shop at (x, z) opens onto: near the point, and with a mapped street just outside it.
    In a row of narrow canal houses the nearest wall is often the party wall to the neighbour, which no one can see."""
    poly = b['poly']; best = None; cx, cz = centroid(poly)
    for i in range(len(poly)):
        a, c = poly[i], poly[(i + 1) % len(poly)]
        ux, uz = c[0] - a[0], c[1] - a[1]; L2 = ux * ux + uz * uz
        if L2 < 1.5: continue
        L = math.sqrt(L2); nx, nz = uz / L, -ux / L
        mx, mz = (a[0] + c[0]) / 2, (a[1] + c[1]) / 2
        if nx * (mx - cx) + nz * (mz - cz) < 0: nx, nz = -nx, -nz
        tt = max(0.15, min(0.85, ((x - a[0]) * ux + (z - a[1]) * uz) / L2))
        qx, qz = a[0] + ux * tt, a[1] + uz * tt
        out_d = road_dist(S, qx + nx * 3.5, qz + nz * 3.5)
        score = math.hypot(qx - x, qz - z) + 1.5 * min(out_d, 25) + (40 if L < 2.5 else 0)
        if best is None or score < best[0]: best = (score, qx, qz, ux / L, uz / L)
    return best[1:] if best else None

def facade_details(S, cam, th, far):
    """Close to the camera: a door for each mapped address, with its real number, and a shopfront for each mapped shop or café,
    lit when its opening_hours say open. Door and window shapes are generic; position and number come from the tags."""
    night = th['night']; out = []
    blds = [b for b in S['buildings'] if not b.get('hidden') and b['h'] and b['tags'].get('building') != 'houseboat']
    DAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']; day = DAYS[datetime.date(*th['date']).weekday()]
    doors = {}          # one door per spot on a wall; several addresses (49, 49A, 49-1) share it
    for n in S['nodes']:
        t = n['tags']; num = t.get('addr:housenumber'); st = n.get('style'); biz = bool(st) and st['cat'] not in ('service',) or t.get('amenity') == 'atm' and False
        if not (num or biz) or far(n['x'], n['z']): continue
        d0 = cam.depth((n['x'], 1, n['z']))
        if d0 < 2 or d0 > (75 if not biz else 230): continue
        b = next((b for b in blds if min(p[0] for p in b['poly']) <= n['x'] <= max(p[0] for p in b['poly']) and min(p[1] for p in b['poly']) <= n['z'] <= max(p[1] for p in b['poly']) and pip(n['x'], n['z'], b['poly'])), None)
        if b is None: continue
        best = street_wall(S, b, n['x'], n['z'])
        if best is None: continue
        qx, qz, ux, uz = best
        nrm = vnorm((uz, 0, -ux))
        if vdot(nrm, vsub(cam.C, (qx, 1.2, qz))) <= 0: continue      # that wall faces away
        cx, cz = centroid(b['poly']); cb = cam.cam((cx, b['h'] / 2, cz))
        depth = cb[2] - 0.03
        o = 0.06
        def quad(w, y0, y1, off=o):
            return [(qx - ux * w / 2 + nrm[0] * off, y0, qz - uz * w / 2 + nrm[2] * off), (qx + ux * w / 2 + nrm[0] * off, y0, qz + uz * w / 2 + nrm[2] * off),
                    (qx + ux * w / 2 + nrm[0] * off, y1, qz + uz * w / 2 + nrm[2] * off), (qx - ux * w / 2 + nrm[0] * off, y1, qz - uz * w / 2 + nrm[2] * off)]
        if biz:
            oh = t.get('opening_hours'); is_open = bool(oh) and open_at(oh, day, th['hhmm'], date=th['date']) is True
            items_ = shopfront(cam, th, S, t, st, qx, qz, ux, uz, nrm, is_open, depth, d0)
            if items_:
                out.extend(items_)
                th['_shops'] = th.get('_shops', 0) + 1; th['_open'] = th.get('_open', 0) + (1 if is_open else 0)
                th['_brands'] = th.get('_brands', 0) + (1 if st['brand'] else 0)
            continue
        key = (b['id'], round(qx), round(qz))
        base = re.match(r'\d+', num or '')
        if key in doors:
            if base: doors[key][0].add(int(base.group()))
            continue
        doors[key] = (set([int(base.group())]) if base else set(), None)
        sv = face(cam, quad(1.15, 0.0, 2.45), ['#1F3A30', '#23262C', '#3B2220', '#1E2C3E', '#2E3B2A'][int(n['id']) % 5], ' stroke="#E8E2D6" stroke-width=".5"')
        sv += face(cam, quad(1.5, 0.0, 0.32, 0.6), '#9C9488' if not night else '#22232A')     # the stoop
        p = cam.proj((qx + nrm[0] * o, 2.75, qz + nrm[2] * o))
        doors[key] = (doors[key][0], (depth, sv, p, d0))
        continue
    for key, (nums, rec) in doors.items():
        if rec is None: continue
        depth, sv, p, d0 = rec
        num = ('%d' % min(nums) if len(nums) == 1 else '%d–%d' % (min(nums), max(nums))) if nums else ''
        if p and d0 < 45 and num:
            fs = max(3.0, 0.32 * cam.focal / d0)
            sv += '<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="%.1f" fill="%s"/><text x="%.1f" y="%.1f" text-anchor="middle" font-family="Georgia, serif" font-size="%.1f" fill="%s">%s</text>' % (
                p[0] - fs * (0.4 + 0.3 * len(num)), p[1] - fs * 0.85, fs * (0.8 + 0.6 * len(num)), fs * 1.15, fs * 0.15, '#F2EEE4' if not night else '#3A3A40', p[0], p[1] + fs * 0.08, fs, '#1E2A44' if not night else '#B9C0D0', num)
        if sv: out.append((depth, 'svg', sv)); th['_doors'] = th.get('_doors', 0) + 1
    return out

def shopfront(cam, th, S, t, st, qx, qz, ux, uz, nrm, is_open, depth, d0):
    """A mapped shop, café or hotel, seen from the street: glazing, an awning in its category colour, a sign with its
    brand (brand=*) or its kind, and a terrace where outdoor_seating says so. Shapes are generic; what and where come from tags."""
    night = th['night']; out = []; s = cam.focal / max(d0, 1)
    def P3(u, y, off): return (qx + ux * u + nrm[0] * off, y, qz + uz * u + nrm[2] * off)
    def quad(u0, u1, y0, y1, off): return [P3(u0, y0, off), P3(u1, y0, off), P3(u1, y1, off), P3(u0, y1, off)]
    W2 = 1.9
    glass = ('#FFD08C' if is_open else '#0B0D12') if night else ('url(#glassLit)' if is_open else 'url(#glass)')
    sv = face(cam, quad(-W2, W2, 0.0, 2.85, 0.07), '#2A2C2E' if not night else '#0C0D10')                  # frame
    sv += face(cam, quad(-W2 + 0.12, W2 - 0.12, 0.12, 2.7, 0.08), glass)
    for m in (-W2 / 3, W2 / 3):                                                                             # mullions and a door
        a_, b_ = cam.proj(P3(m, 0.12, 0.09)), cam.proj(P3(m, 2.7, 0.09))
        if a_ and b_: sv += '<path d="M%.1f %.1fL%.1f %.1f" stroke="#2A2C2E" stroke-width="%.2f"/>' % (a_[0], a_[1], b_[0], b_[1], max(0.5, 0.06 * s))
    col = hx(st['col'])
    if st['cat'] in ('food', 'grocery', 'stay') or st['brand']:                                             # an awning, out over the pavement
        aw = tohex(mulc(col, 0.5 if night else 0.95))
        sv += face(cam, [P3(-W2 - 0.1, 3.0, 0.1), P3(W2 + 0.1, 3.0, 0.1), P3(W2 + 0.1, 2.55, 1.15), P3(-W2 - 0.1, 2.55, 1.15)], aw, ' stroke="%s" stroke-width=".4"' % tohex(mulc(col, 0.6)))
        if st['cat'] == 'food' and not st['brand']:
            stripes = []
            for k in range(7):
                u0 = -W2 - 0.1 + (2 * W2 + 0.2) * (2 * k) / 14; u1 = u0 + (2 * W2 + 0.2) / 14
                sp = cam.poly([P3(u0, 3.0, 0.11), P3(u1, 3.0, 0.11), P3(u1, 2.55, 1.16), P3(u0, 2.55, 1.16)], margin=200)
                if sp: stripes.append(P(sp))
            if stripes: sv += '<path d="%s" fill="%s" opacity=".85"/>' % (''.join(stripes), '#F4EFE6' if not night else '#3A3A40')
    # the sign board: the brand in its colours, or the kind in the category colour
    bg = st['bg'] or tohex(mulc(col, 0.8)); fg = st['fg'] or '#FFFFFF'
    if night and not is_open: bg = tohex(mulc(hx(bg), 0.35))
    sv += face(cam, quad(-W2 + 0.2, W2 - 0.2, 3.08, 3.62, 0.09), bg, ' stroke="#1E1F22" stroke-width=".4"')
    c = cam.proj(P3(0, 3.35, 0.1))
    label = (st['brand'] or st['kind']).upper() if not st['brand'] else st['brand']
    bsp = cam.poly(quad(-W2 + 0.2, W2 - 0.2, 3.08, 3.62, 0.09), margin=60)
    bw_ = (max(p[0] for p in bsp) - min(p[0] for p in bsp)) if bsp else 0
    bh_ = (max(p[1] for p in bsp) - min(p[1] for p in bsp)) if bsp else 0
    if c and bsp and bw_ > bh_ * 1.6:          # only when the board is seen face-on enough to read
        fs = min(14, s * 0.4, bh_ * 0.5, bw_ * 0.9 / (len(label) * 0.62))
        sv += '<text x="%.1f" y="%.1f" text-anchor="middle" font-family="Geist, Helvetica, sans-serif" font-weight="800" font-size="%.1f" fill="%s" letter-spacing="%.2f">%s</text>' % (
            c[0], c[1] + fs * 0.35, fs, fg if not (night and not is_open) else tohex(mulc(hx(fg), 0.5)), fs * 0.04, label.replace('&', '&amp;'))
    if night and is_open: sv += '<g opacity=".55">%s</g>' % face(cam, quad(-2.8, 2.8, 0.0, 0.05, 2.0), '#FFC27A')
    if sv: out.append((depth, 'svg', sv))
    # a blade sign out over the pavement: what you read when you look along a street
    bu = W2 + 0.35
    blade = [P3(bu, 2.75, 0.18), P3(bu, 2.75, 1.05), P3(bu, 3.55, 1.05), P3(bu, 3.55, 0.18)]
    cb = cam.cam(P3(bu, 3.15, 0.6))
    if cb[2] > 1.5:
        bsv = ''
        br0, br1 = cam.proj(P3(bu, 3.62, 0.05)), cam.proj(P3(bu, 3.62, 1.1))
        if br0 and br1: bsv += '<path d="M%.1f %.1fL%.1f %.1f" stroke="#1E1F22" stroke-width="%.2f"/>' % (br0[0], br0[1], br1[0], br1[1], max(0.4, 0.05 * cam.focal / cb[2]))
        bsv += face(cam, blade, bg, ' stroke="#1E1F22" stroke-width=".5"')
        pc = cam.proj(P3(bu, 3.15, 0.6))
        sp_ = cam.poly(blade, margin=60)
        if pc and sp_:
            bw = max(p[0] for p in sp_) - min(p[0] for p in sp_); bh = max(p[1] for p in sp_) - min(p[1] for p in sp_)
            txt = st['brand'] or st['kind']
            fs = min(bh * 0.42, bw * 0.88 / (len(txt) * 0.62), 15)
            if bw < bh * 0.7 or fs > 2.4:
                if bw < bh * 0.7:    # seen edge-on: the name runs down the blade, short form
                    txt = txt if len(txt) <= 9 else txt[:8] + '.'
                    fs = min(bh / (len(txt) * 0.72 + 0.6), 13)
                    bsv += '<text x="%.1f" y="%.1f" text-anchor="middle" font-family="Geist, Helvetica, sans-serif" font-weight="800" font-size="%.1f" fill="%s" transform="rotate(90 %.1f %.1f)">%s</text>' % (pc[0], pc[1] + fs * 0.35, fs, fg, pc[0], pc[1], txt.replace('&', '&amp;'))
                elif fs > 2.4:
                    bsv += '<text x="%.1f" y="%.1f" text-anchor="middle" font-family="Geist, Helvetica, sans-serif" font-weight="800" font-size="%.1f" fill="%s">%s</text>' % (pc[0], pc[1] + fs * 0.35, fs, fg, txt.replace('&', '&amp;'))
        out.append((cb[2], 'svg', bsv))
    # a terrace: outdoor_seating=yes puts tables and chairs on the pavement, and a parasol by day
    if t.get('outdoor_seating') in ('yes', 'sidewalk', 'pedestrian_zone', 'limited') and st['cat'] == 'food':
        for k, (du, off) in enumerate([(-1.1, 2.0), (1.1, 2.0), (0.0, 3.4)]):
            x, z = qx + ux * du + nrm[0] * off, qz + uz * du + nrm[2] * off
            if on_water(S, x, z): continue
            cc = cam.cam((x, 0.75, z))
            if cc[2] < 1.5: continue
            tbl = []
            top = [(x + 0.38 * math.cos(a), 0.75, z + 0.38 * math.sin(a)) for a in [i * math.pi / 6 for i in range(12)]]
            l0, l1 = cam.proj((x, 0, z)), cam.proj((x, 0.75, z))
            wood = '#6B5442' if not night else '#1A1714'
            if l0 and l1: tbl.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f"/>' % (l0[0], l0[1], l1[0], l1[1], wood, max(0.4, 0.06 * cam.focal / cc[2])))
            for cu in (-0.6, 0.6):          # two chairs either side
                chx, chz = x + ux * cu, z + uz * cu
                tbl.append(draw_box(cam, th, chx, chz, 0.42, 0.42, 0.46, '#3E3A36'))
                bk = face(cam, [(chx + ux * cu * 0.3 - 0.2 * nrm[0], 0.46, chz + uz * cu * 0.3 - 0.2 * nrm[2]), (chx + ux * cu * 0.3 + 0.2 * nrm[0], 0.46, chz + uz * cu * 0.3 + 0.2 * nrm[2]),
                                (chx + ux * cu * 0.3 + 0.2 * nrm[0], 0.9, chz + uz * cu * 0.3 + 0.2 * nrm[2]), (chx + ux * cu * 0.3 - 0.2 * nrm[0], 0.9, chz + uz * cu * 0.3 - 0.2 * nrm[2])], '#3E3A36' if not night else '#121214')
                tbl.append(bk)
            tbl.append(face(cam, top, '#E9E3D6' if not night else '#2A2826', ' stroke="#9B9182" stroke-width=".3"'))
            if not night and k < 2:         # a parasol over the front tables
                p0, p1 = cam.proj((x, 0.75, z)), cam.proj((x, 2.35, z))
                if p0 and p1: tbl.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#5A5048" stroke-width="%.2f"/>' % (p0[0], p0[1], p1[0], p1[1], max(0.4, 0.05 * cam.focal / cc[2])))
                ring = [(x + 1.3 * math.cos(a), 2.15, z + 1.3 * math.sin(a)) for a in [i * math.pi / 6 for i in range(12)]]
                apex = (x, 2.65, z); pc = st['bg'] or tohex(mulc(col, 1.0))
                fan = []
                for i in range(12):
                    tri = [ring[i], ring[(i + 1) % 12], apex]
                    nr_ = vnorm(vcross(vsub(tri[1], tri[0]), vsub(tri[2], tri[0])))
                    if nr_[1] < 0: nr_ = tuple(-v for v in nr_)
                    sh_ = 1.0 if vdot(nr_, th['_L']) > 0 else 0.78
                    fan.append(face(cam, tri, tohex(mulc(hx(pc if i % 2 else '#F4EFE6'), sh_))))
                tbl.append(''.join(fan))
            out.append((cc[2], 'svg', ''.join(tbl)))
    return out

def draw_bench(cam, th, n):
    x, z = n['x'], n['z']; c = '#8A6A4C'
    return draw_box(cam, th, x, z, 1.8, 0.5, 0.45, c) + draw_box(cam, th, x, z + 0.22, 1.8, 0.08, 0.9, '#7A5C40')

def draw_bollard(cam, th, n, d):
    """barrier=bollard: a generic dark cast-iron post with a round head."""
    p0, p1 = cam.proj((n['x'], 0, n['z'])), cam.proj((n['x'], 0.85, n['z']))
    if not (p0 and p1): return ''
    s = cam.focal / d; col = '#4A2620' if not th['night'] else '#140B0A'
    out = '<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (p0[0], p0[1], p1[0], p1[1], col, max(0.7, 0.2 * s))
    if not th['night'] and s > 1: out += '<circle cx="%.1f" cy="%.1f" r="%.2f" fill="#B07A6A" opacity=".6"/>' % (p1[0] - 0.03 * s, p1[1], max(0.2, 0.05 * s))
    return out

def draw_box(cam, th, x, z, w, dd, h, col):
    q = [(x - w / 2, h, z - dd / 2), (x + w / 2, h, z - dd / 2), (x + w / 2, h, z + dd / 2), (x - w / 2, h, z + dd / 2)]
    c = hx(col) if not th['night'] else mulc(hx(col), 0.3)
    s = face(cam, q, tohex(c))
    p0, p1 = cam.proj((x, 0, z)), cam.proj((x, h, z))
    if p0 and p1: s = '<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="1"/>' % (p0[0], p0[1], p1[0], p1[1], tohex(mulc(c, 0.8))) + s
    return s

def draw_rack(cam, th, n):
    segs = []
    for k in range(3):
        xx = n['x'] - 1.0 + k
        pts = [cam.proj((xx + 0.35 * math.cos(a), 0.8 * math.sin(a), n['z'])) for a in [i * math.pi / 8 for i in range(9)]]
        if all(pts): segs.append('M' + 'L'.join('%.1f %.1f' % p for p in pts))
    return '<path d="%s" stroke="%s" stroke-width=".9" fill="none"/>' % (''.join(segs), '#6B7282' if not th['night'] else '#2A2E3A') if segs else ''

if __name__ == '__main__':
    S = load()
    print('buildings', len(S['buildings']), 'hidden by parts', sum(b['hidden'] for b in S['buildings']), 'parts', len(S['parts']), 'water', len(S['water']), 'roads', len(S['roads']), 'rails', len(S['rails']), 'trees', len(S['trees']), 'labels', len(S['labels']))
    cfg = dict(W=412, H=915, target=(-10, 0, -30), yaw=52, pitch=38, dist=430, fov=36, cy=440, radius=380)
    if len(sys.argv) > 2: cfg.update(json.loads(sys.argv[2]))
    if len(sys.argv) > 1 and sys.argv[1] == 'gaps':
        G = {}
        for key in GAP_KEYS:
            svg, cam, info = render(S, 'grey', cfg, 'tag:' + key)          # overcast: lenses read best without hard shadows
            slug = key.replace(':', '_')
            open(os.path.join(HERE, '..', 'cache', 'render', 'ams_gap_%s.svg' % slug), 'w').write(svg)
            G[key] = info['counts']; print(key, len(svg) // 1024, 'KB', info['counts'])
        json.dump(G, open(os.path.join(HERE, '..', 'cache', 'render', 'ams_gaps.json'), 'w'), indent=1)
        sys.exit(0)
    for thn in (sys.argv[1].split(',') if len(sys.argv) > 1 else ['golden']):
        lens = 'truth' if thn in ('truth', 'truthgrey') else ('year' if thn == 'year' else None)
        if KIT and lens is None:          # the tradition's map in two stacked images, the ground and what stands on it, each under the size limit
            (svg, objs), cam, info = render(S, thn, cfg, lens, layer='split')
            if isinstance(objs, tuple): objs = objs[0]
            open(os.path.join(HERE, '..', 'cache', 'render', 'ams_kit_%s_g.svg' % thn), 'w').write(svg); open(os.path.join(HERE, '..', 'cache', 'render', 'ams_kit_%s_o.svg' % thn), 'w').write(objs)
            print(thn, 'ground', len(svg) // 1024, 'KB', 'objects', len(objs) // 1024, 'KB'); continue
        svg, cam, info = render(S, 'grey' if thn == 'truthgrey' else ('golden' if thn in ('year', 'truth') else thn), cfg, lens)
        open(os.path.join(HERE, '..', 'cache', 'render', ('ams_kit_%s.svg' if KIT else 'ams_looks_%s.svg' if LOOKS else 'ams_%s.svg') % thn), 'w').write(svg)
        print(thn, len(svg) // 1024, 'KB', {k: round(v, 1) for k, v in info.items() if k != 'counts'})
    json.dump(cfg, open(os.path.join(HERE, '..', 'cache', 'render', 'ams_cfg.json'), 'w'))
    # hotspots: real features, with their real tags
    raw = {(e['type'], e['id']): e for e in json.load(open(os.path.join(HERE, '..', 'data', 'ams', 'raw.json')))['elements']}
    DROP = ('addr:', 'source', 'ref:bag', 'phone', 'fax', 'website', 'wikidata', 'wikipedia', 'wikimedia', 'name:', 'alt_name:', 'contact:', 'email', 'image')
    def clean(t): return {k: v for k, v in t.items() if not k.startswith(DROP)}
    def at(x, y, z): q = cam.proj((x, y, z)); return [round(q[0], 1), round(q[1], 1)] if q else None
    A = {}
    tower = [p for p in S['parts'] if p['id'] == 751083595][0]; cx, cz = centroid(tower['poly'])
    A['tower'] = dict(at=at(cx, 84, cz), tags=clean(tower['tags']), extra={'parts': 5})
    nave = [p for p in S['parts'] if p['id'] == 749268118][0]; cx, cz = centroid(nave['poly'])
    A['nave'] = dict(at=at(cx, 35, cz), tags=clean(nave['tags']))
    wk = raw[('way', 99205257)]['tags']; A['nave']['church'] = clean(wk)
    # a canal house with a 17th-century date and a height, near the Prinsengracht
    best = None
    for b in S['buildings']:
        if b['year'] and b['year'] < 1700 and b['h'] and not b['hidden']:
            cx, cz = centroid(b['poly']); q = cam.proj((cx, b['h'], cz))
            if q and 60 < q[0] < 360 and 160 < q[1] < 560:
                d_ = math.hypot(q[0] - 130, q[1] - 250)
                if best is None or d_ < best[0]: best = (d_, q, b)
    hb = best[2]
    A['house'] = dict(at=[round(best[1][0], 1), round(best[1][1], 1)], tags=clean(hb['tags']), id=hb['id'], year=hb['year'], h=hb['h'], guessed=sorted(hb['guessed']),
                      guess=dict(levels=hb['levels'], roof=hb['roof'], roof_h=hb['roof_h'], col=hb['col'], rcol=hb['rcol']))
    for key, want, test in [('bridge', (230, 610), lambda e: e['type'] == 'way' and e.get('tags', {}).get('bridge') and 'geometry' in e),
                            ('tram', (380, 640), lambda e: e['type'] == 'way' and e.get('tags', {}).get('railway') == 'tram' and 'geometry' in e),
                            ('memorial', (380, 520), lambda e: e['type'] == 'way' and e.get('tags', {}).get('historic') == 'memorial' and 'geometry' in e),
                            ('tree', (330, 560), lambda e: e['type'] == 'node' and e.get('tags', {}).get('natural') == 'tree'),
                            ('canal', (90, 470), lambda e: e['type'] == 'way' and e.get('tags', {}).get('water') == 'canal' and 'geometry' in e),
                            ('museum', (200, 330), lambda e: e.get('tags', {}).get('tourism') == 'museum' and e['type'] == 'way' and 'geometry' in e)]:
        best = None
        for e in raw.values():
            if not test(e): continue
            if e['type'] == 'node': pts = [XZ(e['lat'], e['lon'])]
            else: pts = [XZ(g['lat'], g['lon']) for g in e['geometry']]
            for (x, z) in pts[:: max(1, len(pts) // 12)] + [pts[len(pts) // 2]]:
                q = cam.proj((x, 0.5 if key != 'museum' else 14, z))
                if not q or not (40 < q[0] < 372 and 150 < q[1] < 640): continue
                if any(math.hypot(q[0] - v['at'][0], q[1] - v['at'][1]) < 56 for v in A.values()): continue
                d_ = math.hypot(q[0] - want[0], q[1] - want[1])
                if best is None or d_ < best[0]: best = (d_, q, e)
        if best: A[key] = dict(at=[round(best[1][0], 1), round(best[1][1], 1)], tags=clean(best[2]['tags']), id=best[2]['id'], type=best[2]['type'])
    # a houseboat, and the canal it lies in (named waterway line closest to it)
    best = None
    for b in S['buildings']:
        if b['tags'].get('building') != 'houseboat': continue
        cx, cz = centroid(b['poly']); q = cam.proj((cx, b['h'], cz))
        if not q or not (40 < q[0] < 372 and 150 < q[1] < 640): continue
        if any(math.hypot(q[0] - v['at'][0], q[1] - v['at'][1]) < 56 for k_, v in A.items() if k_ != 'canal'): continue
        d_ = math.hypot(q[0] - 95, q[1] - 520)
        if best is None or d_ < best[0]: best = (d_, q, b, (cx, cz))
    if best:
        _, q, hb, (cx, cz) = best; near = None
        for e in raw.values():
            t = e.get('tags', {})
            if e['type'] != 'way' or t.get('waterway') != 'canal' or not t.get('name') or 'geometry' not in e: continue
            for g in e['geometry']:
                d_ = math.dist(XZ(g['lat'], g['lon']), (cx, cz))
                if near is None or d_ < near[0]: near = (d_, t['name'])
        A['boat'] = dict(at=[round(q[0], 1), round(q[1], 1)], tags=clean(hb['tags']), id=hb['id'], guessed=sorted(hb['guessed']), h=hb['h'], canal=near[1] if near else None,
                         guess=dict(levels=hb['levels'], roof=hb['roof'], col=hb['col'], rcol=hb['rcol']))
    tw = [p for p in S['parts'] if math.dist(centroid(p['poly']), centroid(tower['poly'])) < 14]
    A['tower']['extra'] = dict(parts=len(tw), top=max(p['h'] for p in tw), materials=sorted({p['tags'].get('building:material', '') for p in tw} - {''}))
    B = S['buildings']
    st = dict(buildings=len(B), height=sum(1 for b in B if b['src'] == 'height'), levels=sum(1 for b in B if b['src'] == 'levels'), year=sum(1 for b in B if b['year']),
              roof=sum(1 for b in B + S['parts'] if not b['guessed'].get('roof:shape')), material=sum(1 for b in B + S['parts'] if b['tags'].get('building:material') or b['tags'].get('roof:material')), colour=sum(1 for b in B + S['parts'] if b['tags'].get('building:colour') or b['tags'].get('roof:colour')),
              parts=len(S['parts']), trees=len(S['trees']), lamps=sum(1 for n in S['nodes'] if n['tags'].get('highway') == 'street_lamp'),
              houseboats=sum(1 for b in B if b['tags'].get('building') == 'houseboat'), levels_mapped=sum(1 for b in B if b['tags'].get('building:levels')), guessed_any=sum(1 for b in B if b['guessed']), roads=len(S['roads']), surface=sum(1 for r in S['roads'] if r['tags'].get('surface')),
              lit=sum(1 for r in S['roads'] if r['tags'].get('lit') == 'yes'), pois=len(S['pois']), hours=sum(1 for p in S['pois'] if p['tags'].get('opening_hours')))
    json.dump(dict(anchors=A, stats=st), open(os.path.join(HERE, '..', 'cache', 'render', 'ams_kit_anchors.json' if KIT else 'ams_anchors.json'), 'w'), indent=1)
    print({k: v['at'] for k, v in A.items()}); print(st)
