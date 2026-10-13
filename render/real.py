#!/usr/bin/env python3
"""The living map from real data: OpenStreetMap around Wythe Av & N 7th St, Williamsburg,
joined with two NYC open datasets (Street Tree Census 2015, building footprints for year built).
Everything drawn comes from a real feature; anything guessed is recorded as guessed."""
import json, math, random, datetime, collections, os, sys, re
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import living as LV
from city import Camera, vnorm, vdot, vsub, vcross, hx, tohex, mix, mulc, clamp, P, area, ribbon, rect
OSM = os.path.join(HERE, '..', 'osm')

LAT0, LON0 = 40.719521, -73.960434           # Wythe Av x North 7th St (OSM node 42480055)
KX = 111320 * math.cos(math.radians(LAT0)); KZ = 110540
def XZ(lat, lon): return ((lon - LON0) * KX, -(lat - LAT0) * KZ)
def num(v, default=None):
    if v is None: return default
    m = re.match(r'\s*(-?\d+(?:\.\d+)?)', str(v))
    return float(m.group(1)) if m else default

# ------------------------------------------------------------------ opening_hours (common subset)
DAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su']
def _days(sel):
    out = set()
    for part in re.split(r'\s*,\s*', sel.strip()):
        if '-' in part:
            a, b = part.split('-'); i, j = DAYS.index(a), DAYS.index(b)
            k = i
            while True:
                out.add(DAYS[k])
                if k == j: break
                k = (k + 1) % 7
        elif part in DAYS: out.add(part)
    return out
RULE = re.compile(r'((?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?(?:\s*,\s*(?:Mo|Tu|We|Th|Fr|Sa|Su)(?:-(?:Mo|Tu|We|Th|Fr|Sa|Su))?)*)?\s*((?:\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2}(?:\s*,\s*\d{1,2}:\d{2}\s*-\s*\d{1,2}:\d{2})*)|off|closed)')
MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
def open_at(oh, day, hhmm, date=(2026, 9, 30)):
    """True / False, or None when the string is not understood. Date-specific rules apply only on their date.
    Hours that run past midnight belong to the day they start on: Th 10:00-01:00 is open at 00:30 on Friday."""
    if not oh: return None
    oh = oh.strip()
    if oh == '24/7': return True
    t = hhmm[0] * 60 + hhmm[1]
    today, any_rule = _day_state(oh, day, t, date, False)
    if not any_rule: return None
    i = DAYS.index(day); prev = datetime.date(*date) - datetime.timedelta(days=1)
    spill, _ = _day_state(oh, DAYS[i - 1], t + 24 * 60, (prev.year, prev.month, prev.day), True)
    return bool(today) or bool(spill)

def _day_state(oh, day, t, date, spill):
    """the state of one day's last matching rule at minute t of that day (t past 24:00 with spill: only the wrapped part)"""
    state = None; any_rule = False
    kept = []
    for part in oh.split(';'):
        p = part.strip()
        m = re.match(r'(?:(\d{4})\s+)?(%s)\s+(\d{1,2})\b(.*)' % '|'.join(MONTHS), p)
        if m:
            y, mon, dd, rest = m.group(1), MONTHS.index(m.group(2)) + 1, int(m.group(3)), m.group(4)
            if (y is None or int(y) == date[0]) and mon == date[1] and dd == date[2]:
                kept.append(day + ' ' + rest.strip())
            continue
        kept.append(re.sub(r',?\bPH\b', '', p))
    oh = '; '.join(kept)
    for m in RULE.finditer(oh):
        any_rule = True
        sel, times = m.group(1), m.group(2)
        days = _days(sel) if sel else set(DAYS)
        if day not in days: continue
        if times in ('off', 'closed'): state = False; continue
        st = False
        for rng in re.split(r'\s*,\s*', times):
            a, b = [x.strip() for x in rng.split('-')]
            ta = int(a[:a.index(':')]) * 60 + int(a[-2:]); tb = int(b[:b.index(':')]) * 60 + int(b[-2:])
            if tb <= ta: tb += 24 * 60
            if ta <= t < tb and (not spill or tb > 24 * 60): st = True
        state = st
    return state, any_rule

# ------------------------------------------------------------------ geometry helpers
def signed_area(poly):
    s = 0
    for i in range(len(poly)):
        x1, z1 = poly[i]; x2, z2 = poly[(i + 1) % len(poly)]
        s += x1 * z2 - x2 * z1
    return s / 2
def pip(x, z, poly):
    ins = False; n = len(poly); j = n - 1
    for i in range(n):
        xi, zi = poly[i]; xj, zj = poly[j]
        if (zi > z) != (zj > z) and x < (xj - xi) * (z - zi) / (zj - zi + 1e-12) + xi: ins = not ins
        j = i
    return ins
def centroid(poly):
    return (sum(p[0] for p in poly) / len(poly), sum(p[1] for p in poly) / len(poly))
def seg_dist(px, pz, a, b):
    ax, az = a; bx, bz = b
    dx, dz = bx - ax, bz - az
    L2 = dx * dx + dz * dz or 1e-9
    t = clamp(((px - ax) * dx + (pz - az) * dz) / L2)
    qx, qz = ax + dx * t, az + dz * t
    return math.hypot(px - qx, pz - qz), t

# ------------------------------------------------------------------ load and join
BRICKS = ['#A4523B', '#8C4634', '#B5634A', '#7E3E2E', '#9A5A43', '#6F4A3A', '#A8604A', '#8E5040']
BUFF = ['#C9A882', '#B9A58A', '#D6C3A1', '#A89684', '#C8B494']
PANEL = ['#8C939B', '#C8C2B6', '#5F666E', '#A7AEB6']
GENUS_COL = {  # late-September leaf colour, from green (0) to fully turned
    'Gleditsia': ('#C9B04A', 0.55), 'Gymnocladus': ('#D2B452', 0.5), 'Zelkova': ('#B9853E', 0.25), 'Prunus': ('#C0603A', 0.35),
    'Liquidambar': ('#A2433A', 0.25), 'Tilia': ('#C9B45A', 0.3), 'Platanus': ('#B9A85A', 0.15), 'Quercus': ('#8F7A3A', 0.1),
    'Pyrus': ('#9A5A3A', 0.12), 'Ginkgo': ('#D9C04A', 0.08), 'Acer': ('#C8702E', 0.35), 'Ulmus': ('#B9A04A', 0.2),
    'Styphnolobium': ('#B9B052', 0.15), 'Cercis': ('#C0A040', 0.2), 'Malus': ('#B06A3A', 0.2), 'Populus': ('#D0B84A', 0.3)}

def load():
    raw = json.load(open(os.path.join(OSM, 'raw.json')))['elements']
    years = {b['bin']: int(b['construction_year']) for b in json.load(open(os.path.join(OSM, 'bldg.json'))) if b.get('construction_year') and b['construction_year'].isdigit() and int(b['construction_year']) > 1700}
    census = [t for t in json.load(open(os.path.join(OSM, 'trees2015.json'))) if t.get('status') == 'Alive']
    S = dict(buildings=[], roads=[], crossings=[], areas=[], water=[], nodes=[], trees=[], pois=[])
    rgc = random.Random(5)
    def rings_of(e):
        if e['type'] == 'way' and 'geometry' in e:
            return [[XZ(g['lat'], g['lon']) for g in e['geometry']]]
        if e['type'] == 'relation':
            return [[XZ(g['lat'], g['lon']) for g in m['geometry']] for m in e.get('members', []) if m.get('role') == 'outer' and m.get('geometry')]
        return []
    for e in raw:
        t = e.get('tags')
        if not t: continue
        if 'building' in t and t['building'] != 'no' and e['type'] in ('way', 'relation'):
            for ring in rings_of(e):
                if len(ring) < 4: continue
                if ring[0] == ring[-1]: ring = ring[:-1]
                if signed_area(ring) < 0: ring = ring[::-1]
                if abs(signed_area(ring)) < 6: continue
                h = num(t.get('height'))
                lv = num(t.get('building:levels'))
                g = {}
                if h is None:
                    h = (lv or 3) * 3.2; g['height'] = True
                if lv is None:
                    lv = max(1, round(h / 3.2)); g['levels'] = True
                yr = years.get(t.get('nycdoitt:bin'))
                mat = t.get('building:material'); col = t.get('building:colour')
                if not col:
                    g['colour'] = True
                    if mat == 'glass' or (yr and yr >= 2000 and rgc.random() < 0.5): mat = mat or 'glass'; col = '#8FA7C4'
                    elif yr and yr >= 2000: col = rgc.choice(PANEL); mat = mat or 'panel'
                    elif yr and yr >= 1945: col = rgc.choice(BUFF); mat = mat or 'brick'
                    else: col = rgc.choice(BRICKS); mat = mat or 'brick'
                if not t.get('building:material'): g['material'] = True
                S['buildings'].append(dict(poly=ring, h=h, levels=int(lv), year=yr, mat=mat, col=col, tags=t, guessed=g, shops=[], id=e['id']))
            continue
        if e['type'] == 'way' and 'highway' in t and 'geometry' in e:
            pts = [XZ(g['lat'], g['lon']) for g in e['geometry']]
            hw = t['highway']
            if t.get('footway') == 'crossing' or t.get('path') == 'crossing':
                S['crossings'].append(dict(pts=pts, tags=t))
            elif hw in ('primary', 'secondary', 'tertiary', 'residential', 'unclassified', 'living_street', 'service'):
                w = num(t.get('width'))
                if w is None:
                    w = {'secondary': 12.5, 'primary': 14, 'tertiary': 11.5, 'residential': 10, 'unclassified': 9, 'living_street': 6.5, 'service': 4.5}[hw]
                    if hw == 'service' and t.get('service') in ('parking_aisle', 'driveway'): w = 3.5
                S['roads'].append(dict(pts=pts, w=w, tags=t, hw=hw))
            elif hw in ('pedestrian',) and (t.get('area') == 'yes' or pts[0] == pts[-1]):
                S['areas'].append(dict(poly=pts[:-1], kind='plaza', tags=t))
            elif hw in ('footway', 'path', 'cycleway', 'pedestrian', 'steps') and t.get('footway') != 'sidewalk':
                S['roads'].append(dict(pts=pts, w=2.2 if hw != 'pedestrian' else 5, tags=t, hw='path'))
            continue
        if t.get('natural') == 'coastline' and 'geometry' in e:
            S['water'].append([XZ(g['lat'], g['lon']) for g in e['geometry']])
            continue
        kind = None
        if t.get('leisure') in ('park', 'garden', 'dog_park', 'playground', 'pitch', 'swimming_pool', 'sports_centre') or t.get('landuse') in ('grass', 'greenfield', 'brownfield', 'construction', 'flowerbed') or t.get('amenity') == 'parking' or t.get('natural') in ('water', 'scrub', 'wood', 'sand'):
            kind = t.get('leisure') or t.get('landuse') or t.get('amenity') or t.get('natural')
        if kind and e['type'] in ('way', 'relation') and t.get('water') != 'river':
            for ring in rings_of(e):
                if len(ring) >= 4: S['areas'].append(dict(poly=ring[:-1] if ring[0] == ring[-1] else ring, kind=kind, tags=t))
            continue
        if e['type'] == 'node':
            x, z = XZ(e['lat'], e['lon'])
            S['nodes'].append(dict(x=x, z=z, tags=t, id=e['id']))
            if t.get('natural') == 'tree':
                S['trees'].append(dict(x=x, z=z, genus=t.get('genus'), species=None, dbh=None, source='osm', tags=t))
            if 'shop' in t or t.get('amenity') in ('cafe', 'restaurant', 'bar', 'pub', 'fast_food', 'ice_cream', 'pharmacy', 'bank'):
                S['pois'].append(dict(x=x, z=z, tags=t))
    for c in census:
        x, z = XZ(float(c['latitude']), float(c['longitude']))
        latin = c.get('spc_latin') or ''
        S['trees'].append(dict(x=x, z=z, genus=latin.split(' ')[0] if latin else None, species=latin, common=c.get('spc_common'), dbh=num(c.get('tree_dbh'), 8), source='census', tags={'tree_id': c.get('tree_id')}))
    return S

def attach_shops(S):
    drive = [r for r in S['roads'] if r['hw'] != 'path' and r['hw'] != 'service']
    def street_d(m):
        best = 1e9
        for r in drive:
            if min(abs(p[0] - m[0]) for p in r['pts']) > 300: continue
            for k in range(len(r['pts']) - 1):
                dd, _ = seg_dist(m[0], m[1], r['pts'][k], r['pts'][k + 1])
                best = min(best, dd)
        return best
    for p in S['pois']:
        best = None
        for b in S['buildings']:
            cx, cz = centroid(b['poly'])
            if abs(cx - p['x']) > 60 or abs(cz - p['z']) > 60: continue
            for i in range(len(b['poly'])):
                a_, b_ = b['poly'][i], b['poly'][(i + 1) % len(b['poly'])]
                d_, t_ = seg_dist(p['x'], p['z'], a_, b_)
                if d_ > 16: continue
                m = ((a_[0] + b_[0]) / 2, (a_[1] + b_[1]) / 2)
                score = d_ + (0 if street_d(m) < 12 else 25)
                if best is None or score < best[0]: best = (score, b, i, t_)
        if best and best[0] < 30: best[1]['shops'].append(dict(edge=best[2], u=best[3], tags=p['tags']))

def water_polygon(S):
    if not S['water']: return None
    lines = [l[:] for l in S['water']]
    chain = lines.pop(0)
    changed = True
    while lines and changed:
        changed = False
        for l in lines:
            if math.dist(chain[-1], l[0]) < 1: chain += l[1:]; lines.remove(l); changed = True; break
            if math.dist(l[-1], chain[0]) < 1: chain = l[:-1] + chain; lines.remove(l); changed = True; break
    # water is on the right of the coastline direction: close it far to that side
    a, b = chain[0], chain[-1]
    dx, dz = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dz) or 1
    rx, rz = -dz / L * 3000, dx / L * 3000
    return chain + [(b[0] + rx, b[1] + rz), (a[0] + rx, a[1] + rz)]

# ------------------------------------------------------------------ sunlight: ray march over a grid of prisms
class Shadows:
    def __init__(s, buildings, L):
        s.L = L; s.cell = 16.0; s.grid = collections.defaultdict(list); s.maxh = max(b['h'] for b in buildings)
        for b in buildings:
            xs = [p[0] for p in b['poly']]; zs = [p[1] for p in b['poly']]
            b['bb'] = (min(xs), max(xs), min(zs), max(zs))
            for i in range(int(math.floor(b['bb'][0] / s.cell)), int(math.floor(b['bb'][1] / s.cell)) + 1):
                for j in range(int(math.floor(b['bb'][2] / s.cell)), int(math.floor(b['bb'][3] / s.cell)) + 1):
                    s.grid[(i, j)].append(b)
    def lit(s, p, skip=None, reach=220.0):
        L = s.L
        if L[1] <= 0: return False
        tmax = min(reach, (s.maxh - p[1]) / L[1])
        t = 0.8
        while t < tmax:
            x, y, z = p[0] + L[0] * t, p[1] + L[1] * t, p[2] + L[2] * t
            for b in s.grid.get((int(math.floor(x / s.cell)), int(math.floor(z / s.cell))), ()):
                if b is skip or y > b['h']: continue
                bb = b['bb']
                if bb[0] <= x <= bb[1] and bb[2] <= z <= bb[3] and pip(x, z, b['poly']): return False
            t += 1.6
        return True
    def top(s, b, x, z):
        """Height of a building's upper surface over (x, z): the ridge falls away to the eaves on a gabled or hipped roof."""
        rh = b.get('roof_h') or 0
        if not rh or b.get('roof') not in ('gabled', 'hipped', 'pyramidal', 'quadruple_saltbox'): return b['h']
        f = b.get('_rf')
        if f is None:
            poly = b['poly']; n = len(poly)
            i0 = max(range(n), key=lambda i: math.dist(poly[i], poly[(i + 1) % n]))
            a, c = poly[i0], poly[(i0 + 1) % n]; L = math.dist(a, c) or 1
            ux, uz = (c[0] - a[0]) / L, (c[1] - a[1]) / L
            if b.get('orient') == 'across': ux, uz = -uz, ux
            cx = sum(p[0] for p in poly) / n; cz = sum(p[1] for p in poly) / n
            wa = max(abs((p[0] - cx) * ux + (p[1] - cz) * uz) for p in poly) or 1
            wp = max(abs(-(p[0] - cx) * uz + (p[1] - cz) * ux) for p in poly) or 1
            f = b['_rf'] = (cx, cz, ux, uz, wa, wp)
        cx, cz, ux, uz, wa, wp = f
        va = abs((x - cx) * ux + (z - cz) * uz) / wa; vp = abs(-(x - cx) * uz + (z - cz) * ux) / wp
        k = max(vp, va) if b['roof'] == 'pyramidal' else vp
        return b['h'] - rh * min(1.0, k)
    def shadow_height(s, x, z, skip=None, step=0.45):
        """Up to what height a wall at (x, z) is in shadow: the highest of (surface height - sun climb) along the ray toward the sun."""
        L = s.L
        if L[1] <= 0: return 1e9
        hl = math.hypot(L[0], L[2]) or 1e-6
        tmax = s.maxh / L[1]; best = -1e9; t = 0.25 / hl
        while t < tmax:
            px, pz = x + L[0] * t, z + L[2] * t; climb = L[1] * t
            for b in s.grid.get((int(math.floor(px / s.cell)), int(math.floor(pz / s.cell))), ()):
                if b is skip or b['h'] - climb <= best: continue
                bb = b['bb']
                if bb[0] <= px <= bb[1] and bb[2] <= pz <= bb[3] and pip(px, pz, b['poly']):
                    c = s.top(b, px, pz) - climb
                    if c > best: best = c
            t += step / hl
        return best

# ------------------------------------------------------------------ themes (reuse the living palettes)
def theme(name):
    base = {'noon': LV.NOON, 'golden': LV.GOLDEN, 'night': LV.NIGHT, 'truth': LV.GOLDEN}[name]
    th = dict(base)
    th['truth'] = name == 'truth'
    if name == 'truth': th['name'] = 'truth'
    return th

# ------------------------------------------------------------------ render
def render(S, th, cfg, water, split=None):
    global cfg_roof_k
    cfg_roof_k = cfg.get('roof_k', 1.0)
    W, H = cfg['W'], cfg['H']
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], W, H, cx=cfg.get('cx'), cy=cfg.get('cy'))
    hh, mm = th['hhmm']
    y_, mo_, dd_ = th.get('date', (2026, 9, 30))
    t_utc = datetime.datetime(y_, mo_, dd_, hh, mm) + datetime.timedelta(hours=4)
    az, el = LV.sun_position(t_utc)
    L = LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
    th = dict(th); th['_L'] = L
    night, truth = th['night'], th['truth']
    sh = Shadows(S['buildings'], L)
    class Sc: pass
    sc = Sc(); sc.cam, sc.th, sc.L, sc.boxes = cam, th, L, []
    LV.sunlit = lambda scene, p, skip=None: (not night) and sh.lit(p, skip=None)
    rg = random.Random(3)
    defs, ground, marks, shadow_paths, items, anchors = [], [], [], [], [], {}
    def gp(pts, fill, y=0.0, extra='', margin=900):
        sp = cam.poly([(x, y, z) for (x, z) in pts], margin=margin)
        if sp and area(sp) > 0.3: return '<path d="%s" fill="%s"%s/>' % (P(sp), fill, extra)
        return ''
    hy = cam.horizon_y()
    defs.append('<linearGradient id="sky" gradientUnits="userSpaceOnUse" x1="0" y1="%.0f" x2="0" y2="%.0f"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (hy - 300, hy, th['sky_top'], th['sky_hor']))
    ground.append('<rect width="%d" height="%d" fill="url(#sky)"/>' % (W, H))
    yard = th['lot'] if not truth else '#E6E1D6'
    ground.append(gp(rect(-2500, 2500, -2500, 2500), yard, margin=5000))
    if water: ground.append(gp(water, th['water'] if 'water' in th else ('#9DC2E2' if not night else '#070B16'), 0.0, margin=5000))
    # areas
    AREA_COL = {'park': th['lawn'], 'garden': th['yard_grass'], 'grass': th['yard_grass'], 'dog_park': th['yard_pave'], 'playground': '#D98E5A' if not night else '#2A2230',
                'pitch': '#6FA25A' if not night else '#14231E', 'swimming_pool': '#7FC3E6' if not night else '#0A1422', 'parking': th['asphalt'], 'plaza': th['path'],
                'construction': '#B9A07A' if not night else '#1E1C1A', 'brownfield': '#B7A88A' if not night else '#1C1B1A', 'greenfield': th['yard_grass'], 'sports_centre': th['yard_pave'],
                'water': '#9DC2E2' if not night else '#070B16', 'scrub': th['yard_grass'], 'wood': th['tree_dark'], 'sand': '#E6D8B0', 'flowerbed': '#8CA462'}
    order = ['park', 'grass', 'garden', 'greenfield', 'brownfield', 'construction', 'parking', 'plaza', 'sports_centre', 'pitch', 'dog_park', 'playground', 'swimming_pool', 'water', 'scrub', 'wood', 'sand', 'flowerbed']
    for k in order:
        for a in S['areas']:
            if a['kind'] == k: ground.append(gp(a['poly'], AREA_COL.get(k, th['lawn']), 0.01))
    # roads: sidewalk band, then carriageway
    drive = [r for r in S['roads'] if r['hw'] != 'path']
    for r in drive:
        if r['hw'] in ('service',): continue
        ground.append(gp(ribbon(r['pts'], r['w'] / 2 + 4.6), th['walk'], 0.02))
    for r in S['roads']:
        if r['hw'] == 'path': ground.append(gp(ribbon(r['pts'], r['w'] / 2), th['path'], 0.025))
    for r in drive:
        surf = r['tags'].get('surface')
        col = th['sett'] if surf in ('sett', 'cobblestone', 'unhewn_cobblestone') else th['asphalt']
        if truth: col = '#8A8F98' if surf else '#B9BCC2'
        ground.append(gp(ribbon(r['pts'], r['w'] / 2), col, 0.03))
    # curbs + lane lines + bike lanes
    curb, yel = [], []
    for r in drive:
        if r['hw'] == 'service': continue
        for side in (-1, 1):
            off = ribbon(r['pts'], r['w'] / 2)
            n = len(r['pts'])
            line = off[:n] if side < 0 else off[n:][::-1]
            pts = [cam.proj((x, 0.04, z)) for (x, z) in line]
            pts = [p for p in pts if p]
            if len(pts) > 1: curb.append('M' + 'L'.join('%.1f %.1f' % p for p in pts))
        t = r['tags']
        if r['hw'] in ('secondary', 'primary', 'tertiary') and t.get('oneway') != 'yes':
            for dx in (-0.12, 0.12):
                off = ribbon(r['pts'], abs(dx)); n = len(r['pts'])
                line = off[:n] if dx < 0 else off[n:][::-1]
                pts = [cam.proj((x, 0.05, z)) for (x, z) in line]; pts = [p for p in pts if p]
                if len(pts) > 1: yel.append('M' + 'L'.join('%.1f %.1f' % p for p in pts))
        for side, key in ((1, 'cycleway:right'), (-1, 'cycleway:left'), (0, 'cycleway:both')):
            v = t.get(key) or (t.get('cycleway') if key == 'cycleway:right' else None)
            if v in ('lane', 'track', 'separate'):
                sides = (1, -1) if side == 0 else (side,)
                for sd in sides:
                    inner = ribbon(r['pts'], r['w'] / 2 - 2.2); outer = ribbon(r['pts'], r['w'] / 2 - 0.6); n = len(r['pts'])
                    li = inner[n:][::-1] if sd > 0 else inner[:n]; lo = outer[n:][::-1] if sd > 0 else outer[:n]
                    band = li + lo[::-1]
                    marks.append(gp(band, th['bike'], 0.045, ' opacity=".9"'))
    marks.append('<path d="%s" stroke="%s" stroke-width="1.1" fill="none"/>' % (''.join(curb), th['curb']))
    if yel: marks.append('<path d="%s" stroke="%s" stroke-width=".9" fill="none"/>' % (''.join(yel), th['lane_y']))
    # crossings from footway=crossing ways, painted as tagged
    stripe_ps, line_ps, n_cross = [], [], 0
    for c in S['crossings']:
        t = c['tags']; mk = t.get('crossing:markings') or ('zebra' if t.get('crossing') in ('marked', 'zebra', 'traffic_signals', 'uncontrolled') else None)
        if mk in (None, 'no'): continue
        n_cross += 1
        for i in range(len(c['pts']) - 1):
            a, b = c['pts'][i], c['pts'][i + 1]
            Lg = math.dist(a, b)
            if Lg < 0.5: continue
            ux, uz = (b[0] - a[0]) / Lg, (b[1] - a[1]) / Lg
            px, pz = -uz, ux
            k = 0.4
            while k < Lg - 0.3:
                cx, cz = a[0] + ux * k, a[1] + uz * k
                q = [(cx - ux * 0.3 - px * 1.6, cz - uz * 0.3 - pz * 1.6), (cx + ux * 0.3 - px * 1.6, cz + uz * 0.3 - pz * 1.6),
                     (cx + ux * 0.3 + px * 1.6, cz + uz * 0.3 + pz * 1.6), (cx - ux * 0.3 + px * 1.6, cz - uz * 0.3 + pz * 1.6)]
                sp = cam.poly([(x, 0.06, z) for (x, z) in q])
                if sp and area(sp) > 0.15: stripe_ps.append(P(sp))
                k += 1.2
            if mk in ('ladder', 'lines', 'ladder:skewed'):
                for s_ in (-1.75, 1.75):
                    p0, p1 = cam.proj((a[0] + px * s_, 0.06, a[1] + pz * s_)), cam.proj((b[0] + px * s_, 0.06, b[1] + pz * s_))
                    if p0 and p1: line_ps.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
    op = 0.2 if night else 0.9
    marks.append('<path d="%s" fill="#F4F1EA" opacity="%.2f"/>' % (''.join(stripe_ps), op))
    if line_ps: marks.append('<path d="%s" stroke="#F4F1EA" stroke-width="1.1" opacity="%.2f" fill="none"/>' % (''.join(line_ps), op))
    # ---------------------------------------------------------- shadows (group opacity makes the union even)
    RAD = cfg.get('radius'); TX, TZ = cfg['target'][0], cfg['target'][2]
    far = (lambda x, z: RAD is not None and math.hypot(x - TX, z - TZ) > RAD + 60)
    if not night and L[1] > 0:
        o = lambda y: (-L[0] / L[1] * y, -L[2] / L[1] * y)
        for b in S['buildings']:
            cx, cz = centroid(b['poly'])
            if far(cx, cz) or cam.depth((cx, 0, cz)) < -80: continue
            off = o(b['h'])
            poly = b['poly']
            sp = cam.poly([(x + off[0], 0.08, z + off[1]) for (x, z) in poly], margin=900)
            if sp: shadow_paths.append(P(sp))
            for i in range(len(poly)):
                a, bb = poly[i], poly[(i + 1) % len(poly)]
                q = [(a[0], 0.08, a[1]), (bb[0], 0.08, bb[1]), (bb[0] + off[0], 0.08, bb[1] + off[1]), (a[0] + off[0], 0.08, a[1] + off[1])]
                sp = cam.poly(q, margin=900)
                if sp and area(sp) > 0.3: shadow_paths.append(P(sp))
        for tr in S['trees']:
            hgt, rad = tree_size(tr)
            off = o(hgt * 0.72)
            pts = [(tr['x'] + off[0] + rad * math.cos(a_), 0.08, tr['z'] + off[1] + rad * math.sin(a_)) for a_ in [k * math.pi / 8 for k in range(16)]]
            sp = cam.poly(pts)
            if sp: shadow_paths.append(P(sp))
    # ---------------------------------------------------------- objects in depth order
    for b in S['buildings']:
        cx, cz = centroid(b['poly'])
        if far(cx, cz): continue
        c = cam.cam((cx, min(8, b['h'] / 2), cz))
        if c[2] < 2 or c[2] > cfg.get('max_depth', 1400): continue
        p = cam.pc(c)
        if not (-500 < p[0] < W + 500 and -600 < p[1] < H + 400): continue
        items.append((c[2], 'b', b))
    for tr in S['trees']:
        if far(tr['x'], tr['z']): continue
        c = cam.cam((tr['x'], 1, tr['z']))
        if c[2] < 2 or c[2] > cfg.get('max_depth', 1200): continue
        items.append((c[2], 't', tr))
    FURN = {'street_lamp', 'fire_hydrant', 'bench', 'bicycle_parking', 'bicycle_rental', 'subway_entrance', 'waste_basket', 'drinking_water', 'bus_stop', 'picnic_table'}
    for n in S['nodes']:
        t = n['tags']
        k = t.get('highway') if t.get('highway') in ('street_lamp', 'bus_stop') else t.get('emergency') if t.get('emergency') == 'fire_hydrant' else t.get('amenity') if t.get('amenity') in FURN else t.get('railway') if t.get('railway') == 'subway_entrance' else t.get('leisure') if t.get('leisure') == 'picnic_table' else None
        if not k: continue
        c = cam.cam((n['x'], 1, n['z']))
        if c[2] < 2 or c[2] > cfg.get('max_depth', 900): continue
        items.append((c[2], k, n))
    # no cars or people: they are not OpenStreetMap tags, so they are not drawn
    items.sort(key=lambda it: -it[0])
    out = []
    cut = None; split_d = None
    for d, k, o_ in items:
        if split is not None and k == 'b' and o_ is split:
            cut = len(out); split_d = d; continue
        if k == 'b': out.extend(draw_building(sc, sh, o_, d, th))
        elif k == 't': out.extend(draw_tree(sc, o_, d, th))
        elif k == 'street_lamp':
            lamp_dir = nearest_road_dir(drive, o_['x'], o_['z'])
            out.extend(LV.draw_lamp(sc, {'geom': (o_['x'], o_['z'], 0)}, d, None) if False else draw_lamp(sc, o_, d, lamp_dir, th))
        elif k == 'fire_hydrant': out.extend(LV.draw_post(sc, {'geom': (o_['x'], o_['z'])}, 0.85, 0.28, '#C0392B' if not night else '#4A1F1C', '#E8C04A'))
        elif k in ('bench', 'picnic_table'): out.extend(LV.box(sc, o_['x'] - 0.8, o_['x'] + 0.8, 0.4, 0.5, o_['z'] - 0.25, o_['z'] + 0.25, hx('#6E4B3A') if not night else hx('#1A1614')))
        elif k == 'waste_basket': out.extend(LV.draw_post(sc, {'geom': (o_['x'], o_['z'])}, 0.9, 0.3, '#2F5A3A' if not night else '#10181A', '#1F3A28'))
        elif k == 'drinking_water': out.extend(LV.draw_post(sc, {'geom': (o_['x'], o_['z'])}, 0.95, 0.3, '#5A6B7A', '#7FC3FF' if not night else '#2A4A6A'))
        elif k == 'bicycle_parking': out.extend(LV.draw_rack(sc, {'geom': (o_['x'], o_['z'])}))
        elif k == 'bicycle_rental': out.extend(draw_dock(sc, o_, drive, th))
        elif k == 'subway_entrance': out.extend(LV.draw_subway(sc, {'geom': (o_['x'], o_['z']), 'tags': o_['tags']}, None, {}))
        elif k == 'bus_stop': out.extend(LV.draw_post(sc, {'geom': (o_['x'], o_['z'])}, 2.4, 0.12, '#3A3F46', '#2E5BFF'))
    # night pools
    pools = []
    if night:
        defs.append('<radialGradient id="pool"><stop offset="0" stop-color="#FFB45E" stop-opacity=".55"/><stop offset=".5" stop-color="#FFA848" stop-opacity=".18"/><stop offset="1" stop-color="#FFA848" stop-opacity="0"/></radialGradient>')
        defs.append('<radialGradient id="lampglow"><stop offset="0" stop-color="#FFF1D2" stop-opacity=".95"/><stop offset=".12" stop-color="#FFD9A0" stop-opacity=".55"/><stop offset=".45" stop-color="#FFC27A" stop-opacity=".12"/><stop offset="1" stop-color="#FFC27A" stop-opacity="0"/></radialGradient>')
        defs.append('<radialGradient id="shopglow"><stop offset="0" stop-color="#FFD39A" stop-opacity=".5"/><stop offset="1" stop-color="#FFD39A" stop-opacity="0"/></radialGradient>')
        defs.append('<filter id="streetglow" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="6"/></filter>')
        glow_ps = []
        for r in drive:
            if r['hw'] == 'service': continue
            sp = cam.poly([(x, 0.05, z) for (x, z) in ribbon(r['pts'], r['w'] / 2 + 2)], margin=600)
            if sp and area(sp) > 1: glow_ps.append(P(sp))
        pools.append('<path d="%s" fill="#FFB25E" opacity=".22" filter="url(#streetglow)"/>' % ''.join(glow_ps))
        for n in S['nodes']:
            if n['tags'].get('highway') != 'street_lamp': continue
            c = cam.cam((n['x'], 0, n['z']))
            if c[2] < 2: continue
            p = cam.pc(c)
            if not (-80 < p[0] < W + 80 and -80 < p[1] < H + 80): continue
            rx = 12 * cam.focal / c[2]
            q = cam.proj((n['x'], 0, n['z'] + 12)) or p
            pools.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="url(#pool)"/>' % (p[0], p[1], rx, max(0.6, abs(q[1] - p[1]))))
    # haze
    stops = []; top = max(0.0, hy)
    for k in range(17):
        sy = top + (H - top) * k / 16
        gd = cam.ground_depth(sy + 0.01)
        a_ = th['haze_max'] if gd is None else th['haze_max'] * clamp((gd - cfg.get('haze0', 160)) / cfg.get('haze1', 700)) ** 1.2
        stops.append('<stop offset="%.3f" stop-color="%s" stop-opacity="%.3f"/>' % (k / 16, th['haze'], a_))
    defs.append('<linearGradient id="haze" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%d">%s</linearGradient>' % (top, H, ''.join(stops)))
    haze = '<rect y="%.1f" width="%d" height="%.1f" fill="url(#haze)"/>' % (top, W, H - top)
    if th['name'] == 'golden':
        defs.append('<linearGradient id="wash" x1="0" y1="0" x2="1" y2=".35"><stop offset="0" stop-color="#FFB25E" stop-opacity=".28"/><stop offset=".45" stop-color="#FFC98A" stop-opacity=".07"/><stop offset="1" stop-color="#FFC98A" stop-opacity="0"/></linearGradient>')
        haze += '<rect width="%d" height="%d" fill="url(#wash)"/>' % (W, H)
    shadow_g = '<g opacity="%.2f" fill="%s">%s</g>' % (th['shadow_op'], th['shadow'], ''.join('<path d="%s"/>' % p for p in shadow_paths)) if shadow_paths else ''
    head = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d">' % (W, H, W, H),
            '<defs><clipPath id="vb"><rect width="%d" height="%d"/></clipPath>%s</defs><g clip-path="url(#vb)">' % (W, H, ''.join(defs))]
    if split is not None:
        cut = len(out) if cut is None else cut
        base = ''.join(head + [''.join(ground), ''.join(marks), shadow_g, ''.join(pools), '<g stroke-linejoin="round">', ''.join(out[:cut]), '</g></g></svg>'])
        front = ''.join(head + ['<g stroke-linejoin="round">', ''.join(out[cut:]), '</g>', haze, '</g></svg>'])
        def variant(bv, truth=False):
            th2 = dict(th); th2['truth'] = truth
            body = ''.join(draw_building(sc, sh, bv, split_d, th2))
            return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" style="position: absolute; left: 0; top: 0" aria-hidden="true"><g stroke-linejoin="round">%s</g></svg>' % (W, H, W, H, body)
        return dict(base=base, front=front, variant=variant, cam=cam, depth=split_d)
    svg = head + [''.join(ground), ''.join(marks), shadow_g, ''.join(pools), '<g stroke-linejoin="round">', ''.join(out), '</g>', haze, '</g></svg>']
    return ''.join(svg), cam, dict(az=az, el=el, crossings=n_cross)

def nearest_road_dir(drive, x, z):
    best = None
    for r in drive:
        for i in range(len(r['pts']) - 1):
            d_, t_ = seg_dist(x, z, r['pts'][i], r['pts'][i + 1])
            if best is None or d_ < best[0]: best = (d_, r['pts'][i], r['pts'][i + 1])
    if not best: return (1, 0)
    a, b = best[1], best[2]
    # arm points from the pole toward the road
    L_ = math.dist(a, b) or 1
    ux, uz = (b[0] - a[0]) / L_, (b[1] - a[1]) / L_
    nx, nz = -uz, ux
    mx, mz = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    if (mx - x) * nx + (mz - z) * nz < 0: nx, nz = -nx, -nz
    return (nx, nz)

def tree_size(tr):
    dbh = tr.get('dbh') or 10
    return min(22.0, 5.0 + 0.5 * dbh), min(13.0, 1.5 + 0.3 * dbh) / 2 + 1.0

def draw_tree(sc, tr, d, th):
    hgt, rad = tree_size(tr)
    f = {'geom': (tr['x'], tr['z']), 'tags': {'height': str(hgt), 'diameter_crown': str(rad * 2), 'genus': None}}
    gc = GENUS_COL.get(tr.get('genus'))
    th2 = dict(th)
    if gc and not th['night'] and th.get('season') != 'summer':
        rr = random.Random(hash((round(tr['x'], 1), round(tr['z'], 1))) & 0xffff)
        th2['tree'] = tohex(mix(hx(th['tree']), hx(gc[0]), clamp(gc[1] * rr.uniform(0.6, 1.5))))
    if th.get('truth') and tr['source'] == 'census': th2['tree'] = '#7FA86A'
    sc2 = type('S', (), {})(); sc2.cam, sc2.th, sc2.L, sc2.boxes = sc.cam, th2, sc.L, []
    return LV.draw_tree(sc2, f, d, None)

def draw_lamp(sc, n, d, dirn, th):
    cam = sc.cam
    x, z = n['x'], n['z']
    p0, p1 = cam.proj((x, 0, z)), cam.proj((x, 8.2, z))
    p2 = cam.proj((x + dirn[0] * 1.8, 8.5, z + dirn[1] * 1.8))
    if not (p0 and p1 and p2): return []
    out = ['<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" fill="none"/>' % (p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], '#3A3F46' if not th['night'] else '#1A1E28', max(0.5, 18 / d))]
    rr = max(0.8, 0.45 * cam.focal / d)
    out.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="%s"/>' % (p2[0], p2[1] + rr * 0.3, rr * 1.4, rr * 0.6, '#2A2D33' if not th['night'] else '#FFE9C2'))
    if th['night']: out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#lampglow)"/>' % (p2[0], p2[1], rr * 8))
    return out

def draw_car(sc, x, z, ux, uz, col):
    cam = sc.cam
    out = []
    # an oriented box: 4.4 x 1.8, cabin on top
    def obox(l, w, y0, y1, c):
        px, pz = -uz, ux
        corners = [(x + ux * l / 2 + px * w / 2, z + uz * l / 2 + pz * w / 2), (x - ux * l / 2 + px * w / 2, z - uz * l / 2 + pz * w / 2),
                   (x - ux * l / 2 - px * w / 2, z - uz * l / 2 - pz * w / 2), (x + ux * l / 2 - px * w / 2, z + uz * l / 2 - pz * w / 2)]
        res = []
        for i in range(4):
            a, b = corners[i], corners[(i + 1) % 4]
            nrm = vnorm(((a[0] + b[0]) / 2 - x, 0, (a[1] + b[1]) / 2 - z))
            if vdot(nrm, vsub(cam.C, ((a[0] + b[0]) / 2, (y0 + y1) / 2, (a[1] + b[1]) / 2))) <= 0: continue
            s, _ = LV.face_path(cam, [(a[0], y0, a[1]), (b[0], y0, b[1]), (b[0], y1, b[1]), (a[0], y1, a[1])], tohex(LV.light(sc.th, c, nrm, sc.th['_L'][1] > 0 and vdot(nrm, sc.th['_L']) > 0)))
            if s: res.append(s)
        s, _ = LV.face_path(cam, [(cx, y1, cz) for (cx, cz) in corners], tohex(LV.light(sc.th, c, (0, 1, 0), sc.th['_L'][1] > 0)))
        if s: res.append(s)
        return res
    c = hx(col) if not sc.th['night'] else mulc(hx(col), 0.25)
    out += obox(4.4, 1.8, 0.25, 1.0, c)
    out += obox(2.4, 1.6, 1.0, 1.5, mix(hx('#2A3140'), c, 0.25) if not sc.th['night'] else hx('#0A0D16'))
    return out

def draw_dock(sc, n, drive, th):
    cam = sc.cam
    dirn = nearest_road_dir(drive, n['x'], n['z'])
    ux, uz = -dirn[1], dirn[0]
    out = []
    cap = int(num(n['tags'].get('capacity'), 20))
    k_n = min(12, max(4, cap // 3))
    segs = []
    for k in range(k_n):
        bx, bz = n['x'] + ux * (k - k_n / 2) * 0.9, n['z'] + uz * (k - k_n / 2) * 0.9
        a, b = cam.proj((bx - dirn[0] * 0.8, 0.5, bz - dirn[1] * 0.8)), cam.proj((bx + dirn[0] * 0.8, 0.5, bz + dirn[1] * 0.8))
        if a and b: segs.append('M%.1f %.1fL%.1f %.1f' % (a[0], a[1], b[0], b[1]))
    if segs: out.append('<path d="%s" stroke="%s" stroke-width="%.1f" stroke-linecap="round" fill="none"/>' % (''.join(segs), '#2E6FD8' if not th['night'] else '#14285A', max(1.0, 0.5 * cam.focal / cam.depth((n['x'], 0.5, n['z'])))))
    return out

cfg_roof_k = 1.0
def draw_building(sc, sh, b, d, th):
    cam, L = sc.cam, sc.L
    night, truth = th['night'], th['truth']
    poly, h = b['poly'], b['h']
    out = []
    n = len(poly)
    g = b['guessed']
    clay = hx('#EDE8DE')
    base = hx(b['col']) if not (truth and g.get('colour')) else clay
    glass = b['mat'] == 'glass' and not (truth and g.get('colour'))
    rgb = random.Random(b['id'] & 0xffff)
    lv = b['levels']; fh = h / max(1, lv)
    near = d < 460
    for i in range(n):
        a, bb = poly[i], poly[(i + 1) % n]
        ex, ez = bb[0] - a[0], bb[1] - a[1]
        elen = math.hypot(ex, ez)
        if elen < 0.4: continue
        nrm = vnorm((ez, 0, -ex))   # outward for CCW rings in (x, z) with z south
        if vdot(nrm, vsub(cam.C, ((a[0] + bb[0]) / 2, h / 2, (a[1] + bb[1]) / 2))) <= 0:
            continue
        ncol = max(1, int(elen / 5)) if d < 700 else max(1, int(elen / 16))
        nrow = max(1, min(lv, 12)) if d < 700 else max(1, min(lv, 2))
        rh = h / nrow
        facing = vdot(nrm, L) > 0 and not night
        for r in range(nrow):
            for ci in range(ncol):
                u0, u1 = ci / ncol, (ci + 1) / ncol
                ya, yb = r * rh, (r + 1) * rh
                lit = False
                if facing:
                    pc = (a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.15, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.15)
                    lit = sh.lit(pc, skip=b)
                if glass:
                    refl = mix(hx(th['glass_sky']), hx(th['sky_hor']), clamp(1 - ya / max(1, h))) if not night else hx(th['glass_sky'])
                    col = LV.light(th, mulc(refl, 0.8), nrm, lit)
                    if not night: col = mix(col, hx(th['glass_sun']) if lit else refl, (0.6 if lit else 0.45) * rgb.uniform(0.55, 1.1))
                else:
                    col = LV.light(th, mulc(base, 1 + rgb.uniform(-0.02, 0.02)), nrm, lit)
                s, _ = LV.face_path(cam, LV.quad_at(a, bb, ya, yb, u0, u1), tohex(col), ' stroke="%s" stroke-width=".3"' % tohex(col))
                if s: out.append(s)
        if truth and g.get('colour'):
            s, _ = LV.face_path(cam, LV.quad_at(a, bb, 0, h, 0, 1), 'none', ' stroke="#9A958C" stroke-width=".5"')
            if s: out.append(s)
            continue
        # storefronts from real shop nodes on this edge
        shops = [s_ for s_ in b['shops'] if s_['edge'] == i]
        for s_ in shops:
            oh = s_['tags'].get('opening_hours')
            st = open_at(oh, 'We', th['hhmm'])
            uc = clamp(s_['u'], 0.12, 0.88); half = min(0.4, 3.2 / elen)
            glow = st is True
            if truth: gl = '#7FC3A0' if oh else '#E0DCD2'
            else: gl = '#FFD9A0' if (glow and night) else ('#F6DDA8' if glow else ('#8C9098' if st is False else th['win']))
            s, sp = LV.face_path(cam, LV.quad_at(a, bb, 0.35, 3.0, uc - half, uc + half, 0.06, nrm), gl, ' stroke="%s" stroke-width=".6"' % th['frame'])
            if s:
                out.append(s)
                if st is False and not truth:
                    gates = []
                    for yy in (0.8, 1.3, 1.8, 2.3):
                        q = LV.quad_at(a, bb, yy, yy, uc - half, uc + half, 0.07, nrm)
                        p0, p1 = cam.proj(q[0]), cam.proj(q[1])
                        if p0 and p1: gates.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
                    if gates: out.append('<path d="%s" stroke="%s" stroke-width=".5" opacity=".7"/>' % (''.join(gates), '#5A5E66' if not night else '#20242C'))
                if glow and night and sp:
                    xs_ = [p[0] for p in sp]; ys_ = [p[1] for p in sp]
                    out.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="url(#shopglow)"/>' % ((min(xs_) + max(xs_)) / 2, max(ys_), (max(xs_) - min(xs_)) * 0.9, (max(ys_) - min(ys_)) * 1.1))
            s, _ = LV.face_path(cam, LV.quad_at(a, bb, 3.1, 3.7, uc - half, uc + half, 0.07, nrm), '#1E2A36' if not night else '#0B0F16')
            if s: out.append(s)
        # windows from levels
        if near and elen > 2.5:
            wcols = max(1, int((elen - 1) / 2.8)) if not glass else max(1, int(elen / 1.6))
            for r in range(min(lv, 30)):
                if r == 0 and shops: continue
                ya = r * fh + fh * 0.28; yb = ya + fh * (0.5 if not glass else 0.9)
                if yb > h - 0.4: break
                for ci in range(wcols):
                    u0 = (0.5 + ci * (elen - 1) / wcols + 0.35) / elen
                    u1 = u0 + ((elen - 1) / wcols - 0.7) / elen
                    if u1 <= u0: continue
                    if night:
                        if rgb.random() < 0.42:
                            col = rgb.choice(['#FFD08A', '#FFC56F', '#FFE2AE', '#FFB85C', '#E8EEFF'])
                            s, _ = LV.face_path(cam, LV.quad_at(a, bb, ya, yb, u0, u1, 0.04, nrm), col, ' opacity="%.2f"' % rgb.choice([0.55, 0.7, 0.85, 0.95]))
                            if s: out.append(s)
                        continue
                    if glass:
                        s, _ = LV.face_path(cam, LV.quad_at(a, bb, r * fh, r * fh + fh, u0 - 0.006, u0, 0.04, nrm), '#E9EEF4', ' opacity=".55"')
                        if s: out.append(s)
                        continue
                    s, _ = LV.face_path(cam, LV.quad_at(a, bb, ya - 0.12, yb + 0.12, u0 - 0.012, u1 + 0.012, 0.03, nrm), th['frame'], ' opacity=".9"')
                    if s: out.append(s)
                    wl = facing and sh.lit((a[0] + ex * (u0 + u1) / 2 + nrm[0] * 0.2, (ya + yb) / 2, a[1] + ez * (u0 + u1) / 2 + nrm[2] * 0.2), skip=b)
                    s, _ = LV.face_path(cam, LV.quad_at(a, bb, ya, yb, u0, u1, 0.04, nrm), th['win_sun'] if wl else th['win'])
                    if s: out.append(s)
            # cornice on old brick
            if b['mat'] == 'brick' and (b['year'] or 1900) < 1945:
                s, _ = LV.face_path(cam, LV.quad_at(a, bb, h - 0.6, h, -0.02, 1.02, 0.3, nrm), tohex(mulc(LV.light(th, base, (0, 0.4, 0), not night), 1.06)))
                if s: out.append(s)
            # fire escapes: guessed, old walk-ups, street side
            if (b['year'] or 2000) < 1940 and lv >= 4 and elen > 6 and near and b.get('front') == i:
                iron = '#26272B' if not night else '#0B0C10'
                segs = []
                for r in range(1, lv):
                    y = r * fh + 0.2
                    pa = LV.quad_at(a, bb, y, y, 0.18, 0.62, 1.1, nrm); pb = LV.quad_at(a, bb, y + 1.0, y + 1.0, 0.18, 0.62, 1.1, nrm)
                    p1, p2, p3, p4 = cam.proj(pa[0]), cam.proj(pa[1]), cam.proj(pb[0]), cam.proj(pb[1])
                    if p1 and p2 and p3 and p4:
                        segs.append('M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f' % (p1[0], p1[1], p2[0], p2[1], p3[0], p3[1], p4[0], p4[1], p1[0], p1[1], p3[0], p3[1], p2[0], p2[1], p4[0], p4[1]))
                    la = LV.quad_at(a, bb, y, y + fh, 0.23 if r % 2 else 0.5, 0.5 if r % 2 else 0.23, 0.6, nrm)
                    q1, q2 = cam.proj(la[0]), cam.proj(la[2])
                    if q1 and q2: segs.append('M%.1f %.1fL%.1f %.1f' % (q1[0], q1[1], q2[0], q2[1]))
                if segs: out.append('<path d="%s" stroke="%s" stroke-width="%.2f" fill="none" opacity=".85"/>' % (''.join(segs), iron, max(0.5, 60 / d)))
    # roof
    if cam.C[1] > h:
        ROOFS = ['#C9C6C0', '#B7B4AE', '#A6A39D', '#5A5856', '#6E6B66', '#D8D5CE', '#8E8A84']
        rcol = hx(b['tags'].get('roof:colour') or ROOFS[b['id'] % len(ROOFS)]) if not truth else hx('#F4F1EA')
        litR = (not night) and sh.lit((centroid(poly)[0], h + 0.05, centroid(poly)[1]), skip=b)
        s, _ = LV.face_path(cam, [(x, h, z) for (x, z) in poly], tohex(mulc(LV.light(th, mix(rcol, hx(th['roof_sun']), 0.2 if litR else 0), (0, 1, 0), litR), cfg_roof_k)), ' stroke="%s" stroke-width=".5"' % ('#9A958C' if truth else tohex(mulc(rcol, 0.8))))
        if s: out.append(s)
        if not truth and near:
            # parapet line just inside the edge
            cx, cz = centroid(poly)
            inset = [(x + (cx - x) * 0.06, z + (cz - z) * 0.06) for (x, z) in poly]
            s, _ = LV.face_path(cam, [(x, h + 0.01, z) for (x, z) in inset], 'none', ' stroke="%s" stroke-width=".6" opacity=".6"' % tohex(mulc(rcol, 0.72)))
            if s: out.append(s)
            ar = abs(signed_area(poly))
            rr = random.Random(b['id'])
            units = 1 if lv >= 5 else 0
            if ar > 700: units += 2
            if ar > 2000: units += 2
            for k in range(units):
                ux_, uz_ = rr.uniform(0.25, 0.75), rr.uniform(0.25, 0.75)
                xs = [p[0] for p in poly]; zs = [p[1] for p in poly]
                px_ = min(xs) + (max(xs) - min(xs)) * ux_; pz_ = min(zs) + (max(zs) - min(zs)) * uz_
                if not pip(px_, pz_, poly): continue
                w_, d_, h_ = rr.uniform(1.6, 3.2), rr.uniform(1.6, 3.2), rr.uniform(1.0, 2.6)
                out.extend(LV.box(sc, px_ - w_ / 2, px_ + w_ / 2, h, h + h_, pz_ - d_ / 2, pz_ + d_ / 2, mix(mulc(base, 0.8), hx('#8C8A86'), 0.6) if k == 0 and lv >= 5 else hx('#B9BCC0')))
    return out

# ------------------------------------------------------------------ main
def street_bearing(S, name):
    pts = [p for r in S['roads'] if r['tags'].get('name') == name for p in r['pts']]
    a = min(pts, key=lambda p: p[1]); b = max(pts, key=lambda p: p[1])   # north end, south end
    return math.degrees(math.atan2(a[0] - b[0], -(a[1] - b[1]))) % 360

if __name__ == '__main__':
    S = load()
    # which facade faces the street: the edge nearest any drive road
    drive = [r for r in S['roads'] if r['hw'] != 'path']
    for b in S['buildings']:
        best = None
        for i in range(len(b['poly'])):
            m = ((b['poly'][i][0] + b['poly'][(i + 1) % len(b['poly'])][0]) / 2, (b['poly'][i][1] + b['poly'][(i + 1) % len(b['poly'])][1]) / 2)
            for r in drive:
                cx, cz = centroid(b['poly'])
                if abs(r['pts'][0][0] - cx) > 400 and abs(r['pts'][-1][0] - cx) > 400: continue
                for k in range(len(r['pts']) - 1):
                    dd, _ = seg_dist(m[0], m[1], r['pts'][k], r['pts'][k + 1])
                    if best is None or dd < best[0]: best = (dd, i)
        b['front'] = best[1] if best else None
    attach_shops(S)
    water = water_polygon(S)
    yaw = street_bearing(S, 'Bedford Avenue')
    print('Wythe bearing', round(yaw, 1), 'buildings', len(S['buildings']), 'trees', len(S['trees']), 'pois', len(S['pois']), 'with shopfront', sum(len(b['shops']) for b in S['buildings']))
    cfg = dict(W=412, H=915, target=(229, 0, 180), yaw=yaw, pitch=42, dist=180, fov=36, cy=500)
    if len(sys.argv) > 2: cfg.update(json.loads(sys.argv[2]))
    names = sys.argv[1].split(',') if len(sys.argv) > 1 else ['golden']
    outdir = os.path.join(HERE, '..', 'cache', 'render')
    for nm in names:
        th = theme(nm)
        if nm == 'truth': th['hhmm'] = (17, 20)
        svg, cam, info = render(S, th, cfg, water)
        open(os.path.join(outdir, 'real_%s.svg' % nm), 'w').write(svg)
        print(nm, len(svg) // 1024, 'KB', info)
    json.dump(cfg, open(os.path.join(outdir, 'real_cfg.json'), 'w'))
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
    DROP = ('name', 'brand', 'brand:wikidata', 'brand:wikipedia', 'website', 'phone', 'contact:', 'addr:', 'operator', 'wikidata', 'wikipedia', 'check_date', 'survey', 'source', 'email', 'image', 'official_name', 'alt_name', 'old_name')
    def clean(t): return {k: v for k, v in t.items() if not k.startswith(DROP)}
    cands = collections.defaultdict(list)
    for n in S['nodes']:
        t = n['tags']
        kind = 'subway' if t.get('railway') == 'subway_entrance' else 'hydrant' if t.get('emergency') == 'fire_hydrant' else 'bikes' if t.get('amenity') in ('bicycle_rental', 'bicycle_parking') else 'bench' if t.get('amenity') in ('bench', 'waste_basket', 'drinking_water') else None
        if kind: cands[kind].append(((n['x'], 1.0, n['z']), clean(t), None))
    for b in S['buildings']:
        cx, cz = centroid(b['poly'])
        extra = {'construction_year (NYC open data)': str(b['year'])} if b['year'] else {}
        cands['building'].append(((cx, b['h'] + 0.5, cz), dict(clean(b['tags']), **extra), dict(h=b['h'], year=b['year'], levels=b['levels'], guessed=list(b['guessed']))))
        for sh_ in b['shops']:
            a_, b_ = b['poly'][sh_['edge']], b['poly'][(sh_['edge'] + 1) % len(b['poly'])]
            px_, pz_ = a_[0] + (b_[0] - a_[0]) * sh_['u'], a_[1] + (b_[1] - a_[1]) * sh_['u']
            if sh_['tags'].get('opening_hours'):
                cands['shop'].append(((px_, 3.2, pz_), clean(sh_['tags']), dict(noon=open_at(sh_['tags']['opening_hours'], 'We', (12, 0)), golden=open_at(sh_['tags']['opening_hours'], 'We', (17, 20)), night=open_at(sh_['tags']['opening_hours'], 'We', (21, 30)))))
    for tr in S['trees']:
        if tr['source'] == 'census':
            hgt, rad = tree_size(tr)
            cands['tree'].append(((tr['x'], hgt * 0.72, tr['z']), {'species': tr['species'], 'common': tr['common'], 'trunk_dbh_in': str(int(tr['dbh'] or 0))}, dict(h=round(hgt), crown=round(rad * 2))))
    for c in S['crossings']:
        t = c['tags']
        if t.get('crossing:markings') in ('zebra', 'ladder'):
            m = c['pts'][len(c['pts']) // 2]
            cands['crossing'].append(((m[0], 0.1, m[1]), clean(t), None))
    for r in S['roads']:
        t = r['tags']
        if t.get('cycleway:left') == 'lane' or t.get('cycleway:right') == 'lane' or t.get('cycleway') == 'lane':
            m = r['pts'][len(r['pts']) // 2]
            cands['lane'].append(((m[0], 0.1, m[1]), clean(t), None))
    want = [('shop', (330, 400)), ('subway', (300, 520)), ('crossing', (190, 520)), ('tree', (110, 470)), ('building', (160, 190)), ('hydrant', (250, 330)), ('bikes', (70, 600)), ('lane', (120, 300)), ('bench', (340, 250))]
    chosen = {}
    for kind, spot in want:
        best = None
        for (p3, tags, info) in cands.get(kind, []):
            q = cam.proj(p3)
            if not q or not (34 < q[0] < cfg['W'] - 34 and 130 < q[1] < 628): continue
            if any(math.hypot(q[0] - c_['at'][0], q[1] - c_['at'][1]) < 62 for c_ in chosen.values()): continue
            dd = math.hypot(q[0] - spot[0], q[1] - spot[1])
            if best is None or dd < best[0]: best = (dd, q, tags, info)
        if best: chosen[kind] = dict(at=[round(best[1][0], 1), round(best[1][1], 1)], tags=best[2], info=best[3])
    st = dict(buildings=len(S['buildings']), height=sum(1 for b in S['buildings'] if not b['guessed'].get('height')), year=sum(1 for b in S['buildings'] if b['year']),
              colour=sum(1 for b in S['buildings'] if not b['guessed'].get('colour')), levels=sum(1 for b in S['buildings'] if not b['guessed'].get('levels')),
              trees_osm=sum(1 for t in S['trees'] if t['source'] == 'osm'), trees_census=sum(1 for t in S['trees'] if t['source'] == 'census'),
              shops=len(S['pois']), shops_hours=sum(1 for p in S['pois'] if 'opening_hours' in p['tags']), crossings=sum(1 for c in S['crossings'] if c['tags'].get('crossing:markings') not in (None, 'no')),
              lamps=sum(1 for n in S['nodes'] if n['tags'].get('highway') == 'street_lamp'), racks=sum(1 for n in S['nodes'] if n['tags'].get('amenity') == 'bicycle_parking'),
              benches=sum(1 for n in S['nodes'] if n['tags'].get('amenity') == 'bench'), hydrants=sum(1 for n in S['nodes'] if n['tags'].get('emergency') == 'fire_hydrant'))
    json.dump(dict(anchors=chosen, stats=st), open(os.path.join(outdir, 'real_anchors.json'), 'w'), indent=1, default=str)
    print(json.dumps({k: v['at'] for k, v in chosen.items()}), st)
