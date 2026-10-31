#!/usr/bin/env python3
"""Beyond Amsterdam: the same renderer and the same rules (mapped tags only, generic shapes marked) for other landscapes.
Schiphol (runways, taxiways, motorways), Paris (Haussmann blocks, the Seine, a mall), the Port of Rotterdam (cranes, tanks, turbines,
a beach), Zuid-Kennemerland (a national park of dunes, forest and beach).
python3 regions.py <region> [moments]  ->  out/<region>_<moment>.svg"""
import sys, os, json, math, random, re, collections, datetime, runpy
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import amsterdam as A
import closeup as CU
import living as LV
from city import Camera, P, area, ribbon, hx, tohex, mix, mulc, vnorm, vdot, vsub
from real import pip, centroid, num, signed_area

ROOT = os.path.join(HERE, '..', 'data', 'regions')

# ------------------------------------------------------------------ region set-up
PLACES = runpy.run_path(os.path.join(ROOT, 'places.py'))['PLACES']     # one table: where each place is, and its views (data/regions/places.py)
CAMS = {k + ('_' + v if v else ''): dict(c) for k, p in PLACES.items() for v, c in p.get('views', {}).items()}     # '' is the overview, 'close' is <name>_close
TERRAIN = {'dunes', 'sangimignano'}          # regions drawn on their real ground shape (AHN; elsewhere the open global elevation tiles)
TOWNS = {'sangimignano'}          # of those, towns: squares and parks in the ground, trees and lamps kept, every building down to the slope
RELIEF = {'port', 'schiphol', 'bergen'}   # flat drawing, with the real relief shown as light and shade
GENERALISE = {'grachten': 0.8, 'grachten_home': 0.7, 'grachten_eat': 0.6, 'paris': 0.9, 'schiphol': 0.6, 'port': 0.6, 'dunes': 0.5, 'paris_close': 0.3, 'schiphol_close': 0.4, 'port_close': 0.4, 'paris_walk': 0.0, 'nottinghill_close': 0.15, 'sangimignano_close': 0.2, 'bergen_close': 0.2}       # metres: footprint detail below this is not visible at the zoom shown
POWER_NEAR, POWER_FAR = 1200.0, 2000.0      # metres from the camera: power lines and their pylons are drawn whole up to NEAR, fade out by FAR (far views stay calm; 14 Oct 2026)
PALETTE = {          # region-wide generic looks for guessed colours: the honest default where building:colour is not mapped
    'paris': dict(old=['#D9CDB4', '#E2D7C1', '#D4C6AA', '#DDD2BC', '#CFC2A6'], plaster=['#E8E0D0', '#EEE7D9'], new=['#CFC8BC', '#C8C2B8', '#D8D2C6']),
    'schiphol': dict(old=['#9C8E80', '#8F8780'], plaster=['#E6E2DA'], new=['#B8B6B1', '#A9A7A2', '#C4C2BD', '#9FA3A8', '#8E949A']),
    'port': dict(old=['#9C8E80'], plaster=['#E6E2DA'], new=['#B8B6B1', '#A9A7A2', '#C4C2BD', '#9FA3A8', '#7E8C96', '#B9B1A2']),
}

def simplify(pts, tol):
    """Douglas-Peucker on a closed ring: map generalisation at the zoom shown (a corner within tol metres of a straight wall is dropped)."""
    if len(pts) < 6: return pts
    def dp(a, b, seg):
        if len(seg) < 3: return [seg[0]]
        (x0, z0), (x1, z1) = seg[0], seg[-1]; dx, dz = x1 - x0, z1 - z0; L = math.hypot(dx, dz) or 1e-9
        dmax, k = 0, 0
        for i in range(1, len(seg) - 1):
            dd = abs((seg[i][0] - x0) * dz - (seg[i][1] - z0) * dx) / L
            if dd > dmax: dmax, k = dd, i
        if dmax <= tol: return [seg[0]]
        return dp(a, k, seg[:k + 1]) + dp(k, b, seg[k:])
    i_far = max(range(len(pts)), key=lambda i: math.dist(pts[0], pts[i]))
    ring = dp(0, i_far, pts[:i_far + 1]) + dp(i_far, len(pts), pts[i_far:] + [pts[0]])
    return ring if len(ring) >= 3 and abs(signed_area(ring)) > 2 else pts

def configure(name):
    R = json.load(open(os.path.join(ROOT, name, 'region.json')))
    A.LAT0, A.LON0 = R['origin']
    A.KX = 111320 * math.cos(math.radians(A.LAT0))
    A.WXD = json.load(open(os.path.join(ROOT, name, 'wx.json')))['hourly']
    from zoneinfo import ZoneInfo
    A.TZ_H = int(datetime.datetime(2026, 10, 1, 12, tzinfo=ZoneInfo(R.get('tz', 'Europe/Amsterdam'))).utcoffset().total_seconds() // 3600)
    A.THEMES['grey'] = A.weather_theme('grey', (2026, 10, 1), (13, 30))
    A.THEMES['sunny'] = A.weather_theme('sunny', (2026, 10, 1), (16, 0))
    A.THEMES['wet'] = A.weather_theme('wet', (2026, 9, 30), (21, 30))
    for k, (d_, hm_) in (R.get('moments') or {}).items():          # a region's own moments (Efteling: the recorded Saturday of 26 September, 14:00)
        A.THEMES[k] = A.weather_theme(k, tuple(d_), tuple(hm_))
    pal = PALETTE.get(name)
    if pal: A.BRICK_OLD[:] = pal['old']; A.PLASTER[:] = pal['plaster']; A.BRICK_NEW[:] = pal['new']
    return R

# ------------------------------------------------------------------ geometry helpers
def ll(g): return A.XZ(g['lat'], g['lon'])

def join_rings(ways):
    """Outer or inner member ways of a multipolygon, joined end to end into closed rings (lists of (lat, lon))."""
    segs = [[(g['lat'], g['lon']) for g in w if g] for w in ways if w]
    segs = [s for s in segs if len(s) > 1]
    rings = []
    while segs:
        cur = segs.pop(0)
        changed = True
        while cur[0] != cur[-1] and changed:
            changed = False
            for i, s in enumerate(segs):
                if s[0] == cur[-1]: cur = cur + s[1:]
                elif s[-1] == cur[-1]: cur = cur + s[::-1][1:]
                elif s[-1] == cur[0]: cur = s + cur[1:]
                elif s[0] == cur[0]: cur = s[::-1] + cur[1:]
                else: continue
                segs.pop(i); changed = True; break
        if len(cur) > 3 and cur[0] == cur[-1]: rings.append(cur)
    return rings

def rings_of(e, role='outer'):
    if e['type'] == 'way' and e.get('geometry'):
        g = e['geometry']
        if len(g) > 3 and (g[0]['lat'], g[0]['lon']) == (g[-1]['lat'], g[-1]['lon']): return [[ll(p) for p in g]]
        return []
    if e['type'] == 'relation':
        ways = [m.get('geometry') for m in e.get('members', []) if m.get('type') == 'way' and m.get('role', 'outer') in ((role,) if role == 'inner' else ('outer', ''))]
        return [[A.XZ(a, b) for (a, b) in r] for r in join_rings(ways)]
    return []

def lines_of(e):
    if e['type'] == 'way' and e.get('geometry'): return [[ll(p) for p in e['geometry'] if p]]
    if e['type'] == 'relation': return [[ll(p) for p in m['geometry'] if p] for m in e.get('members', []) if m.get('type') == 'way' and m.get('geometry')]
    return []

def sea_from_coast(coast_ways, bbox):
    """OSM maps the sea as its coastline (land on the left). Close the coastline along the edge of the extract into water polygons."""
    s, w, n, e = bbox[0] - 0.06, bbox[1] - 0.1, bbox[2] + 0.06, bbox[3] + 0.1          # well beyond the view
    segs = join_open([[(g['lat'], g['lon']) for g in c if g] for c in coast_ways])
    def extend(p, q):
        # from p, straight on away from q, until the box edge
        dla, dlo = p[0] - q[0], p[1] - q[1]; best = None
        for (k, lim) in ((0, s), (0, n), (1, w), (1, e)):
            dk = dla if k == 0 else dlo
            if abs(dk) < 1e-12: continue
            t = (lim - p[k]) / dk
            if t <= 0: continue
            r = (p[0] + dla * t, p[1] + dlo * t)
            if s - 1e-9 <= r[0] <= n + 1e-9 and w - 1e-9 <= r[1] <= e + 1e-9 and (best is None or t < best[0]): best = (t, r)
        return best[1] if best else None
    out = []
    for c in segs:
        if len(c) > 1 and c[0] != c[-1]:
            if s < c[0][0] < n and w < c[0][1] < e:
                r = extend(c[0], c[1]); c = ([r] if r else []) + c
            if s < c[-1][0] < n and w < c[-1][1] < e:
                r = extend(c[-1], c[-2]); c = c + ([r] if r else [])
        out.append(c)
    segs = out
    # clip every chain to the box, keeping the pieces that run inside it
    def inside(p): return s <= p[0] <= n and w <= p[1] <= e
    def cross(a, b):
        best = None
        for (k, lim) in ((0, s), (0, n), (1, w), (1, e)):
            if (a[k] - lim) * (b[k] - lim) < 0:
                t = (lim - a[k]) / (b[k] - a[k]); q = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                if inside((min(max(q[0], s), n), min(max(q[1], w), e))) and (best is None or t < best[0]): best = (t, (min(max(q[0], s), n), min(max(q[1], w), e)))
        return best[1] if best else None
    pieces = []
    for c in segs:
        cur = []
        for i in range(len(c)):
            p = c[i]
            if inside(p):
                if not cur and i > 0: q = cross(c[i - 1], p); cur.append(q) if q else None
                cur.append(p)
            elif cur:
                q = cross(c[i - 1], p)
                if q: cur.append(q)
                pieces.append(cur); cur = []
        if cur: pieces.append(cur)
    pieces = [p for p in pieces if len(p) > 1]
    if not pieces: return []
    # walk the box edge clockwise (lat up, lon right) from each exit to the next entry: water stays on the right
    corners = [(n, e), (s, e), (s, w), (n, w)]
    def perim(p):        # clockwise position along the box edge, starting at the north-west corner
        la, lo = p; W_, H_ = e - w, n - s
        if abs(la - n) < 1e-9: return lo - w
        if abs(lo - e) < 1e-9: return W_ + (n - la)
        if abs(la - s) < 1e-9: return W_ + H_ + (e - lo)
        return 2 * W_ + H_ + (la - s)
    total = 2 * ((e - w) + (n - s))
    cpos = [(perim(c), c) for c in [(n, w), (n, e), (s, e), (s, w)]]
    open_p = [p for p in pieces if not (len(p) > 3 and p[0] == p[-1])]
    polys = []
    used = [False] * len(open_p)
    for i0 in range(len(open_p)):
        if used[i0]: continue
        poly = []; i = i0
        for _ in range(len(open_p) + 1):
            used[i] = True; poly += open_p[i]
            x = perim(open_p[i][-1])
            # the next entry clockwise from this exit
            cands = [(((perim(open_p[j][0]) - x) % total), j) for j in range(len(open_p))]
            dj, j = min(cands)
            for (cp, c) in sorted(cpos, key=lambda q: (q[0] - x) % total):
                if (cp - x) % total < dj: poly.append(c)
            if j == i0: break
            i = j
        if len(poly) > 2: polys.append([A.XZ(a, b) for (a, b) in poly])
    return polys

def join_open(chains):
    chains = [c for c in chains if len(c) > 1]; out = []
    while chains:
        cur = chains.pop(0); changed = True
        while changed:
            changed = False
            for i, c in enumerate(chains):
                if c[0] == cur[-1]: cur = cur + c[1:]
                elif c[-1] == cur[0]: cur = c + cur[1:]
                else: continue
                chains.pop(i); changed = True; break
        out.append(cur)
    return out

# ------------------------------------------------------------------ land cover
LAND = {     # kind: (colour, draw order). Generic tones for mapped land cover; the shape is the mapped shape.
    'farmland': ('#D8D4AC', 0), 'residential': ('#E4DFD6', 0), 'industrial': ('#D3CEC6', 0), 'port': ('#C3C1BB', 1), 'commercial': ('#E0D8D0', 0),
    'retail': ('#E4D7D0', 0), 'construction': ('#D9CDB9', 0), 'railway': ('#D2CCC3', 0), 'military': ('#D6D2C6', 0), 'brownfield': ('#D7CDBC', 0),
    'greenfield': ('#CBD3A8', 0), 'education': ('#E2DBCF', 0), 'static_caravan': ('#DCD5C8', 1), 'farmyard': ('#D9CFB8', 1),
    'meadow': ('#C2D19B', 1), 'grass': ('#B8CC96', 2), 'grassland': ('#BFCF9C', 2), 'village_green': ('#B8CC96', 2), 'recreation_ground': ('#B8CC96', 2),
    'park': ('#B2CA90', 2), 'garden': ('#B5CC92', 2), 'cemetery': ('#B4C2A0', 2), 'allotments': ('#C7CE9A', 2), 'orchard': ('#B6C98C', 2),
    'golf_course': ('#B1CD8E', 2), 'heath': ('#BCAB8C', 2), 'scrub': ('#9CB084', 3), 'forest': ('#7E9B6B', 3), 'wood': ('#7E9B6B', 3), 'wetland': ('#A6BBAB', 3),
    'sand': ('#E4D6B3', 4), 'beach': ('#EDE1C1', 4), 'dune': ('#E4D6B3', 4), 'bare_rock': ('#B1ACA2', 4), 'stone': ('#ADA89F', 4), 'scree': ('#B5B0A6', 4),
    'apron': ('#C7C5C0', 5), 'parking': ('#C9C6BE', 5), 'pedestrian': ('#D6CDBE', 5), 'playground': ('#D9B48A', 5), 'pitch': ('#A3C28C', 5), 'track': ('#C98F74', 5), 'flowerbed': ('#B9C48F', 5),
}
# A park's, a garden's and grass's lawn: amsterdam.py's AREA colour, drawn once among the land cover in its own draw order
# (under the woods and the water), where the living map's rules drew it again as an area over everything in it: a park
# mapped round a whole wood (the Amsterdamse Bos, 896 ha) covered its forest, its streams and its lake (30 Oct 2026);
# one inside another area or in the water stays an area over it (areas_without_lawns)
LAWN, LAWN_KINDS = '#A9C38A', ('park', 'garden', 'grass')
def land_kind(t):
    if t.get('aeroway') == 'apron': return 'apron'
    if t.get('amenity') == 'parking' and t.get('parking') != 'underground': return 'parking'
    if t.get('leisure') in ('pitch', 'park', 'garden', 'golf_course', 'recreation_ground', 'track'): return t['leisure']
    if t.get('industrial') == 'port' or t.get('landuse') == 'port': return 'port'          # a terminal's yard: paved
    if t.get('natural') in LAND: return t['natural']
    if t.get('landuse') in LAND: return t['landuse']
    return None

def lawn_in_land(a):
    """Whether an area's lawn is in the land cover: load puts its element in S['land'] as the same kind, which draw_under draws."""
    t = a.get('tags') or {}
    return a['kind'] in LAWN_KINDS and land_kind(t) == a['kind'] and not t.get('building') and t.get('boundary') != 'national_park'

def box_of(p):
    return (min(q[0] for q in p), max(q[0] for q in p), min(q[1] for q in p), max(q[1] for q in p))

def ring_in(p, pb, q, qb):
    """Whether ring p (its box pb) lies in ring q (its box qb): its box within q's and its centroid in q."""
    if not (qb[0] <= pb[0] and pb[1] <= qb[1] and qb[2] <= pb[2] and pb[3] <= qb[3]): return False
    cx, cz = centroid(p)
    return pip(cx, cz, q)

def areas_without_lawns(S, rings):
    """S['areas'] without the lawns the land cover draws (lawn_in_land), but for those inside another area (a courtyard's
    garden, a parking lot's grass islands, a park on a square) or in the water (an island its rings leave in it), by the
    areas' own `rings`: the land cover would draw them under what holds them, so they stay areas, drawn over it as before."""
    holders = [(r, box_of(r)) for a, r in zip(S['areas'], rings) if a['kind'] not in LAWN_KINDS and len(r) >= 3]
    water = [(r, box_of(r)) for r in S['water'] if len(r) >= 3]
    holes = [(r, box_of(r)) for r in S['water_holes'] if len(r) >= 3]
    def held(p):
        if len(p) < 3: return False
        b = box_of(p)
        if any(ring_in(p, b, q, qb) for q, qb in holders): return True
        return any(ring_in(p, b, q, qb) for q, qb in water) and not any(ring_in(p, b, q, qb) for q, qb in holes)
    return [a for a, r in zip(S['areas'], rings) if not lawn_in_land(a) or held(r)]

# ------------------------------------------------------------------ load
def base_of(view):
    return view.split('_')[0]

def load(name):
    view = name; name = base_of(view)           # 'paris_close' is Paris, seen from its close camera
    R = configure(name)
    A.WATER_Y, A.QUAY_COL, A.QUAY_COURSE = -1.5, '#6E5546', 0.0625          # the generic levels, before this region measures its own
    raw_path = os.path.join(ROOT, name, 'raw.json')
    S = A.load(raw_path)
    raw = json.load(open(raw_path))['elements']
    S.update(land=[], aero=[], big=[], rail=[], power=[], towers={}, turbines=[], cranes=[], marine=[], park=[], natural_shore=set(), region=name, R=R, view=view)
    S['tradition'] = R.get('tradition')          # the building tradition its unmapped looks come from (render/traditions)
    # buildings mapped as multipolygon relations (Notre-Dame, the Louvre): outer ways joined into rings, as for water
    have = set((round(b['poly'][0][0], 1), round(b['poly'][0][1], 1)) for b in S['buildings'] + S['parts'])
    new = dict(buildings=[], parts=[], trees=[])
    for e in raw:
        t = e.get('tags') or {}
        if e['type'] != 'relation' or not (t.get('building') or t.get('building:part')) or t.get('building') == 'no': continue
        for r in rings_of(e):
            r = r[:-1] if math.dist(r[0], r[-1]) < 0.5 else r
            if signed_area(r) < 0: r = r[::-1]
            if abs(signed_area(r)) < 3 or (round(r[0][0], 1), round(r[0][1], 1)) in have: continue
            h = num(t.get('height')); lv = num(t.get('building:levels'))
            if h is None and lv is not None: h = lv * 3.0 + num(t.get('roof:levels'), 0) * 2.0
            m = re.match(r'\s*(\d{4})', t.get('start_date', ''))
            rec = dict(poly=r, h=h, min=num(t.get('min_height'), 0.0), src='height' if t.get('height') else ('levels' if lv else None), year=int(m.group(1)) if m else None,
                       tags=t, id=e['id'], roof=t.get('roof:shape'), roof_h=num(t.get('roof:height')), orient=t.get('roof:orientation'),
                       mat=t.get('building:material'), rmat=t.get('roof:material'), col=t.get('building:colour'), rcol=t.get('roof:colour'))
            rec['otype'] = 'relation'
            new['parts' if t.get('building:part') else 'buildings'].append(rec)
    if new['buildings'] or new['parts']:
        A.guess_tags(new)
        for b in new['buildings']: b['hidden'] = any(pip(*centroid(p['poly']), b['poly']) for p in S['parts'] + new['parts'] if abs(centroid(p['poly'])[0] - centroid(b['poly'])[0]) < 120 and abs(centroid(p['poly'])[1] - centroid(b['poly'])[1]) < 120)
        for b in new['buildings'] + new['parts']:
            xs = [p[0] for p in b['poly']]; zs = [p[1] for p in b['poly']]; b['_bb'] = (min(xs), max(xs), min(zs), max(zs))
        S['buildings'] += new['buildings']; S['parts'] += new['parts']
    # roof shapes this renderer does not draw are drawn as the closest one it does (the tag itself is kept and shown)
    CLOSEST = {'gambrel': 'gabled', 'dome': 'pyramidal', 'onion': 'pyramidal', 'round': 'gabled', 'half-hipped': 'hipped', 'saltbox': 'gabled', 'cone': 'pyramidal'}
    for b in S['buildings'] + S['parts']:
        if b['roof'] in CLOSEST or b['roof'] == 'mansard':       # a mansard is drawn as itself
            b['roof'] = CLOSEST.get(b['roof'], b['roof'])
            if not b['roof_h'] and b['h']:
                rl = num(b['tags'].get('roof:levels'))
                b['roof_h'] = min(b['h'] * 0.35, rl * 2.8 if rl else 3.5); b.setdefault('guessed', {})['roof:height'] = True
    area_rings = [a['poly'] for a in S['areas']]          # their own rings: the lawns kept as areas are the same at every tolerance
    tol = GENERALISE.get(view, GENERALISE.get(view.replace('_detail', '').replace('_kit', ''), GENERALISE.get(name, 0)))
    if tol:
        for b in S['buildings'] + S['parts']: b['poly'] = simplify(b['poly'], tol)
        for a in S['areas']: a['poly'] = simplify(a['poly'], tol)
    coast = []; bays = []
    quays = name in ('paris', 'port')
    for e in raw:
        t = e.get('tags') or {}
        if not t: continue
        if t.get('natural') == 'coastline' and e['type'] == 'way': coast.append(e['geometry']); continue
        if t.get('boundary') in ('national_park',) or (t.get('boundary') == 'protected_area' and name == 'dunes'):
            for r in rings_of(e): S['park'].append(dict(ring=r, tags=t))
            continue
        k = land_kind(t)
        if k and e['type'] != 'node' and not t.get('building'):
            for r in rings_of(e):
                if abs(signed_area(r)) > 4: S['land'].append(dict(poly=r, kind=k, tags=t, id=e['id']))
            continue
        if e['type'] == 'way' and t.get('aeroway') in ('runway', 'taxiway', 'taxilane', 'parking_position', 'stopway') and e.get('geometry'):
            S['aero'].append(dict(pts=[ll(p) for p in e['geometry']], kind=t['aeroway'], tags=t, id=e['id'])); continue
        if e['type'] == 'way' and t.get('highway') in ('motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary_link') and e.get('geometry'):
            if t.get('tunnel') in ('yes', 'building_passage') or num(t.get('layer'), 0) < 0: continue      # underground: not drawn
            S['big'].append(dict(pts=[ll(p) for p in e['geometry']], tags=t, hw=t['highway'], layer=num(t.get('layer'), 0), id=e['id'])); continue
        if e['type'] == 'way' and (t.get('railway') in ('rail', 'light_rail', 'subway', 'narrow_gauge') or t.get('man_made') == 'crane_rail' or t.get('railway') == 'crane') and e.get('geometry'):
            if t.get('tunnel') in ('yes', 'building_passage') or num(t.get('layer'), 0) < 0: continue
            S['rail'].append(dict(pts=[ll(p) for p in e['geometry']], tags=t, crane=t.get('man_made') == 'crane_rail' or t.get('railway') == 'crane')); continue
        if e['type'] == 'way' and t.get('power') in ('line', 'minor_line') and e.get('geometry'):
            S['power'].append(dict(pts=[ll(p) for p in e['geometry']], tags=t)); continue
        if t.get('man_made') in ('breakwater', 'groyne', 'pier', 'quay') and e['type'] == 'way' and e.get('geometry'):
            S['marine'].append(dict(pts=[ll(p) for p in e['geometry']], kind=t['man_made'], tags=t, closed=e['geometry'][0] == e['geometry'][-1])); continue
        if t.get('man_made') == 'crane':
            if e['type'] == 'node': S['cranes'].append(dict(x=A.XZ(e['lat'], e['lon'])[0], z=A.XZ(e['lat'], e['lon'])[1], tags=t, id=e['id'], poly=None))
            elif e.get('geometry'):
                pts = [ll(p) for p in e['geometry']]
                S['cranes'].append(dict(x=sum(p[0] for p in pts) / len(pts), z=sum(p[1] for p in pts) / len(pts), tags=t, id=e['id'], poly=pts))
            continue
        if t.get('man_made') == 'mast' and e['type'] == 'node':
            x, z = A.XZ(e['lat'], e['lon'])
            S.setdefault('masts', []).append(dict(x=x, z=z, tags=t, id=e['id'], h=num(t.get('height'), None))); continue
        if t.get('man_made') in ('gantry', 'goods_conveyor') and e['type'] == 'way' and e.get('geometry') and t.get('location') not in ('underground', 'underwater'):
            S.setdefault('gantries' if t['man_made'] == 'gantry' else 'conveyors', []).append(dict(pts=[ll(p) for p in e['geometry']], tags=t, id=e['id'], h=num(t.get('height'), None))); continue
        if e['type'] == 'node' and t.get('power') == 'generator' and t.get('generator:source') == 'wind':
            x, z = A.XZ(e['lat'], e['lon'])
            S['turbines'].append(dict(x=x, z=z, tags=t, id=e['id'], h=num(t.get('height'), None), rotor=num(t.get('rotor:diameter'), None))); continue
        if e['type'] == 'node' and t.get('man_made') == 'tower' and num(t.get('height')):
            x, z = A.XZ(e['lat'], e['lon'])
            S.setdefault('mtowers', []).append(dict(x=x, z=z, h=num(t.get('height')), tags=t, id=e['id'])); continue
        if e['type'] == 'node' and t.get('power') in ('tower', 'pole'):
            S['towers'][e['id']] = (A.XZ(e['lat'], e['lon']), num(t.get('height'), None))
    # rivers and lakes mapped as multipolygons: outer rings joined from their ways, islands kept as holes
    seen = set((round(r[0][0], 1), round(r[0][1], 1), len(r)) for r in S['water'])
    S['water_holes'] = []
    for e in raw:
        t = e.get('tags') or {}
        if e['type'] == 'relation' and (t.get('natural') == 'water' or t.get('waterway') in ('riverbank', 'dock', 'canal')):
            for r in rings_of(e):
                if (round(r[0][0], 1), round(r[0][1], 1), len(r)) not in seen: S['water'].append(r)
            S['water_holes'] += rings_of(e, 'inner')
        if t.get('natural') in ('bay', 'strait') and e['type'] in ('relation', 'way'):
            bays.append(e)          # a named piece of the sea: water by definition, used where the extract holds no coastline to close
    for r in S['water']:
        if not quays: S['natural_shore'].add(id(r))
    seas_ = sea_from_coast(coast, R['bbox'])
    if seas_ and S['buildings']:
        # a coastline closed round the wrong side covers the town: no sea holds more than a few buildings
        cents = [centroid(b['poly']) for b in S['buildings'][::max(1, len(S['buildings']) // 400)]]
        seas_ = [poly for poly in seas_ if sum(1 for c in cents if pip(c[0], c[1], poly)) <= max(2, 0.03 * len(cents))]
    for poly in seas_:
        S['water'].append(poly)
        if name not in ('bergen',): S['natural_shore'].add(id(poly))          # Bergen's harbour is quays all round
    if not seas_ and bays:
        for e in bays:
            for r in rings_of(e):
                if abs(signed_area(r)) > 2000: S['water'].append(r)          # a harbour's edges are quays, drawn as such
    if name == 'bergen': A.QUAY_COL, A.QUAY_COURSE = '#7F796E', 0.45          # Bergen's quays are cut stone
    S['land'].sort(key=lambda a: (LAND[a['kind']][1], -abs(signed_area(a['poly']))))
    # each building's generic look from its own tags and the land use it stands in (real OSM values, marked guessed)
    import generic as GN
    lu = [(a['kind'], a['poly'], (min(p[0] for p in a['poly']), max(p[0] for p in a['poly']), min(p[1] for p in a['poly']), max(p[1] for p in a['poly'])))
          for a in S['land'] if a['kind'] in GN.LANDUSE_KIND]
    def landuse_at(b):
        cx, cz = centroid(b['poly'])
        for k, poly, bb in lu:
            if bb[0] <= cx <= bb[1] and bb[2] <= cz <= bb[3] and pip(cx, cz, poly): return k
        return None
    # each building:part belongs to the outline it stands in: it takes that building's look, and a church's parts get church windows
    outl = [(b, b.get('_bb') or (min(p[0] for p in b['poly']), max(p[0] for p in b['poly']), min(p[1] for p in b['poly']), max(p[1] for p in b['poly']))) for b in S['buildings']]
    for p_ in S['parts']:
        cx, cz = centroid(p_['poly'])
        for b, bb in outl:
            if bb[0] <= cx <= bb[1] and bb[2] <= cz <= bb[3] and pip(cx, cz, b['poly']):
                p_['parent_id'] = b['id']
                if b['tags'].get('building') in ('church', 'cathedral', 'chapel') or b['tags'].get('amenity') == 'place_of_worship': p_['in_church'] = True
                break
    GN.apply(S, name, landuse_at)
    dem_path = os.path.join(ROOT, name, 'dem.json')
    if name in TERRAIN and os.path.exists(dem_path):
        # the ground's real shape (AHN, not OSM): land cover, water and paths draped over it, buildings standing at their ground height
        import terrain as TR, relief as RF
        fine = os.path.join(ROOT, name, 'dem5.json')
        S['dem'] = TR.DEM(fine if os.path.exists(fine) else dem_path)
        seas = [r for r in S['water'] if id(r) in S['natural_shore'] and abs(signed_area(r)) > 5e6]
        lakes = [r for r in S['water'] if not any(r is q for q in seas)]
        S['_lakes'] = lakes; S['_seas'] = seas
        town = name in TOWNS; S['_town'] = town
        ext = None
        if town:
            cv = cfg_for(view); TX_, TZ_ = cv['target'][0], cv['target'][2]; GR_ = cv.get('ground_r', 1500); ext = (TX_ - GR_, TX_ + GR_, TZ_ - GR_, TZ_ + GR_)
            AK = {'pedestrian': 'pedestrian', 'square': 'pedestrian', 'memorial': 'pedestrian', 'bridge': 'pedestrian', 'park': 'park', 'garden': 'garden', 'grass': 'grass', 'playground': 'playground', 'parking': 'parking'}
            for a in S['areas']:
                if AK.get(a['kind']) and len(a['poly']) >= 3: S['land'].append(dict(poly=a['poly'], kind=AK[a['kind']], tags=a.get('tags') or {}))
        S['_grid'] = RF.build(S, S['dem'], lakes, seas, S['land'], LAND, extent=ext)      # heights filled under buildings and water, cover on a 2.5 m grid
        LW = {'motorway': (12, '#8E8C88'), 'trunk': (10, '#8E8C88'), 'primary': (8, '#9A9894'), 'secondary': (7, '#A29F9A'), 'tertiary': (6, '#ABA7A0'),
              'residential': (5, '#B9B4AA'), 'unclassified': (4.5, '#B9B4AA'), 'service': (3, '#C2BDB3'), 'living_street': (4, '#C2BDB3'),
              'cycleway': (2.4, '#C98A7A'), 'footway': (1.6, '#E3D6BC'), 'path': (1.4, '#E3D6BC'), 'track': (2.5, '#D7C7A8'), 'bridleway': (2, '#D7C7A8'), 'steps': (1.6, '#E3D6BC'), 'pedestrian': (3, '#D8D1C4')}
        lines = []
        for r in S['roads']:
            w, col = LW.get(r['hw'], (2.0, '#D8D1C4'))
            if town: col = A.SURF.get(r['tags'].get('surface'), col)          # a town's streets in their mapped surface
            lines.append((r['pts'], r['w'] if (r.get('wguess') is False or town) else w, col, False, town and r['tags'].get('lit') == 'yes'))
        for r in S['big']:
            w, col = LW.get(r['hw'].replace('_link', ''), (8, '#9A9894')); lines.append((r['pts'], w, col, False))
        for r in S['rail']: lines.append((r['pts'], 3.0, '#7A746C', False))
        for p_ in S['park']: lines.append((p_['ring'], 3.0, '#2F6B3E', True))
        for b in S['buildings'] + S['parts']:
            base = S['_grid'].h(*centroid(b['poly']))
            if town and b['min'] < 0.5:          # on a slope the walls reach down to the lowest corner, as a hill town's houses do
                low = min(S['_grid'].h(x, z) for (x, z) in b['poly']); b['min'] = low - 0.4; b['_gy'] = low
            else: b['min'] += base
            b['h'] = (b['h'] or 0) + base
        S['_lines'] = lines
        if town:
            for n in S['trees'] + S['nodes']: n['gy'] = S['_grid'].h(n['x'], n['z'])
        S['areas'] = []; S['roads'] = []; S['water'] = []; S['big'] = []; S['rail'] = []; S['park'] = []
        if not town: S['trees'] = []
        S['no_shadows'] = not town          # a town's buildings and trees cast shadows on their own ground
        S['_terrain'] = True
    if S.get('_terrain'):
        import terrain as TR
        import relief as RF
        S['under'] = lambda cam, th, far, defs: RF.draw(S, cam, th, far, defs, cfg_for(view), LAND, S['_lines'])
    else:
        S['areas'] = areas_without_lawns(S, area_rings)      # their land cover draws the lawns
        S['under'] = lambda cam, th, far, defs: draw_under(S, cam, th, far, defs)
    if os.path.exists(dem_path) and not S.get('_terrain'):
        # the ground's real heights where the drawing stays flat (the streets are the drawing's level 0):
        # how far each water body lies below its banks, and the relief as light and shade
        import terrain as TR, statistics
        S['dem'] = TR.DEM(dem_path)
        cfg = cfg_for(view); TX, TZ, RAD = cfg['target'][0], cfg['target'][2], cfg['radius']
        def banks(r, out_d=(30, 35, 40), in_d=30):
            land, wat = [], []
            for i in range(len(r)):
                a, b = r[i], r[(i + 1) % len(r)]
                if math.hypot(a[0] - TX, a[1] - TZ) > RAD * 1.2: continue
                ex, ez = b[0] - a[0], b[1] - a[1]; Lg = math.hypot(ex, ez)
                if Lg < 3: continue
                nx, nz = ez / Lg, -ex / Lg; mx, mz = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
                if not pip(mx + nx * 2, mz + nz * 2, r): nx, nz = -nx, -nz
                for d in out_d:
                    v = S['dem'].hn(mx - nx * d, mz - nz * d)
                    if v is not None and not any(pip(mx - nx * d, mz - nz * d, o) for o in S['water'] if o is not r and abs(o[0][0] - mx) < 3000): land.append(v)
                w = S['dem'].hn(mx + nx * in_d, mz + nz * in_d)
                if w is not None: wat.append(w)
            return land, wat
        lv, info = {}, {}
        big = [r for r in S['water'] if id(r) not in S['natural_shore'] and 1e5 < abs(signed_area(r)) < 5e7]
        if name == 'paris':
            # IGN surveys the river surface too: the Seine's level against the street level of its quays
            for r in big:
                land, wat = banks(r)
                if len(land) > 20 and len(wat) > 10:
                    wl, ll_ = sorted(wat)[len(wat) // 10], statistics.median(land)
                    lv[id(r)] = max(-9.0, min(-0.6, wl - ll_)); info = dict(water=round(wl, 1), bank=round(ll_, 1))
            if lv: A.WATER_Y = min(lv.values())
        if name == 'port':
            # AHN has no ground over water: the quays' height from AHN, the water's from the real tide at each moment
            land = []
            for r in big: land += banks(r)[0]
            sea = json.load(open(os.path.join(ROOT, name, 'sea.json')))['hourly']
            ll_ = statistics.median(land)
            def tide(date, hhmm):
                t0 = '%04d-%02d-%02dT%02d:00' % (date + (hhmm[0],)); i = sea['time'].index(t0)
                v0, v1 = sea['sea_level_height_msl'][i], sea['sea_level_height_msl'][i + 1]
                return v0 + (v1 - v0) * hhmm[1] / 60
            S['_tide'] = {k: round(tide(A.THEMES[k]['date'], A.THEMES[k]['hhmm']), 2) for k in ('sunny', 'grey', 'wet')}
            S['_tidal'] = [r for r in S['water'] if abs(signed_area(r)) > 1e5]
            info = dict(bank=round(ll_, 1), tide=S['_tide'])
            S['_bank'] = ll_
        for r in S['water']:              # basins, ponds and fountains too small for the survey: a shallow generic drop
            if id(r) not in lv and abs(signed_area(r)) < 1e5: lv[id(r)] = -0.6
        S['water_y'] = lv; S['_water_level'] = info
        S['_base'] = statistics.median([v for v in (S['dem'].hn(TX + i * 80, TZ + j * 80) for i in range(-30, 31) for j in range(-30, 31) if math.hypot(i, j) * 80 < RAD) if v is not None])
        S['_lakes'] = list(S['water'])
        S['_relief'] = name in RELIEF
        fine = os.path.join(ROOT, name, 'dem5.json')
        if S['_relief'] and not os.path.exists(fine): fine = dem_path          # no finer survey: the one we have
        if S['_relief'] and os.path.exists(fine):
            import relief as RF          # the relief from the 5 m survey; the coarse one still measures the water levels above
            GR_ = cfg.get('ground_r', 1500) * 1.2
            S['_flat'] = RF.build_flat(TR.DEM(fine), S['water'], S['_base'], extent=(TX - GR_, TX + GR_, TZ - GR_, TZ + GR_))
    A.QUAY_COL = {'paris': '#B8AD96', 'port': '#8C8A85', 'schiphol': '#8C8A85'}.get(name, A.QUAY_COL)
    A.QUAY_COURSE = {'paris': 0.45, 'port': None, 'schiphol': None}.get(name, A.QUAY_COURSE)      # Paris quays are cut stone
    A.WIN_STYLE = 'paris' if name == 'paris' else None
    A.TONE = True
    if R.get('park'):
        # a theme park: its coaster tracks standing up, each ride's top height from its published profile (park/<name>/pf)
        import coasters as CO, glob as _glob
        hts, kinds = {}, {}
        for f in _glob.glob(os.path.join(HERE, '..', 'data', 'park', R['park'], 'pf', '*.json')):
            a_ = json.load(open(f)); rp = a_.get('rideProfile') or {}
            if (rp.get('stats') or {}).get('heightM'): hts[a_['name']] = float(rp['stats']['heightM'])
            if 'wooden-coaster' in (rp.get('types') or []): kinds[a_['name']] = 'wood'
        S['coasters'] = CO.load(raw, A.XZ, S['buildings'] + S['parts'], hts, kinds)
        import woods as WD          # its woods: generic trees inside the mapped outlines, off everything mapped in them
        cv = cfg_for(view); S['woods'] = WD.plant(S, cv['target'][0], cv['target'][2], cv['radius'])
    if R.get('zoo'):
        # a zoo: its planted woods and shrubberies, the same generic trees inside the mapped outlines (the mapped trees stay as they are)
        import woods as WD
        cv = cfg_for(view); S['woods'] = WD.plant(S, cv['target'][0], cv['target'][2], cv['radius'])
    S['over'] = lambda cam, th, far, defs: draw_over(S, cam, th, far, defs)
    S['items'] = lambda cam, th, far, defs: draw_items(S, cam, th, far, defs)
    S['no_facades'] = True
    return S

# ------------------------------------------------------------------ drawing: on the ground
def night_c(th, c, k=0.2): return tohex(mulc(hx(c), k)) if th['night'] else c

def gpoly(cam, pts, y=0.0, margin=600):
    sp = cam.poly([(x, y, z) for (x, z) in pts], margin=margin)
    return P(sp) if sp and area(sp) > 0.3 else ''

def draw_under(S, cam, th, far, defs):
    out = ['<g filter="url(#mat)">']
    groups = collections.OrderedDict()
    for a in S['land']:
        cx, cz = centroid(a['poly'])
        d = gpoly(cam, a['poly'], 0.01)
        if d: groups.setdefault(a['kind'], []).append(d)
    for kind, ds in groups.items():
        c = night_c(th, LAWN, 0.18) if kind in LAWN_KINDS else night_c(th, LAND[kind][0])      # the lawn as its area drew it
        out.append('<path d="%s" fill="%s"/>' % (''.join(ds), c))
    out.append('</g>')
    # forest and scrub seen from above: a canopy of crowns, generic, inside the mapped outline
    canopy = [a for a in S['land'] if a['kind'] in ('forest', 'wood', 'scrub')]
    if canopy and not th['night']:
        defs.append('<filter id="canopy" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency=".55" numOctaves="2" seed="17" result="n"/>'
                    '<feColorMatrix in="n" type="matrix" values="0 0 0 0 .16  0 0 0 0 .24  0 0 0 0 .12  -3.4 0 0 0 2.1"/>'
                    '<feComposite in2="SourceGraphic" operator="in"/></filter>')
        ds = [gpoly(cam, a['poly'], 0.02) for a in canopy]
        out.append('<path d="%s" fill="#000" filter="url(#canopy)" opacity=".55"/>' % ''.join(d for d in ds if d))
    if S.get('_relief'):
        import terrain as TR
        import relief as RF
        png = RF.shade_flat(S, cam, th, cfg_for(S.get('view', S['region'])))
        if png: th['_raster'] = ('png', png, None)        # its own file on the board, above the ground, under what stands on it
    return ''.join(out)

def offset_line(pts, off):
    """A polyline moved sideways by off metres (left positive)."""
    out = []
    for i, p in enumerate(pts):
        a = pts[max(0, i - 1)]; b = pts[min(len(pts) - 1, i + 1)]
        dx, dz = b[0] - a[0], b[1] - a[1]; L = math.hypot(dx, dz) or 1
        out.append((p[0] - dz / L * off, p[1] + dx / L * off))
    return out

def line_d(cam, pts, y):
    run, out = [], []
    for p in pts + [None]:
        q = cam.proj((p[0], y, p[1])) if p else None
        if q: run.append(q); continue
        if len(run) > 1: out.append('M' + 'L'.join('%.1f %.1f' % r for r in run))
        run = []
    return ''.join(out)

def dashes(cam, pts, y, on, off):
    """World-space dashes along a polyline: on metres painted, off metres gap, measured along the whole line."""
    out = []; s0 = 0.0; period = on + off
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]; L = math.dist(a, b)
        if L < 1e-6: continue
        k = math.floor(s0 / period) * period          # dash starts at multiples of the period, in line distance
        while k < s0 + L:
            d0, d1 = max(k, s0), min(k + on, s0 + L)
            if d1 > d0:
                f0, f1 = (d0 - s0) / L, (d1 - s0) / L
                p0 = cam.proj((a[0] + (b[0] - a[0]) * f0, y, a[1] + (b[1] - a[1]) * f0)); p1 = cam.proj((a[0] + (b[0] - a[0]) * f1, y, a[1] + (b[1] - a[1]) * f1))
                if p0 and p1: out.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
            k += period
        s0 += L
    return ''.join(out)

def px(cam, w, p):
    c = cam.cam((p[0], 0, p[1]))
    return max(0.25, w * cam.focal / max(c[2], 1))

def draw_over(S, cam, th, far, defs):
    night = th['night']; out = []
    near = lambda pts: any(not far(*p) for p in pts[::max(1, len(pts) // 8)] + [pts[-1]])
    # rail: ballast and two rails
    for r in S['rail']:
        if not near(r['pts']): continue
        d = gpoly(cam, ribbon(r['pts'], 1.6 if not r['crane'] else 0.5), 0.04)
        if d and not r['crane']: out.append('<path d="%s" fill="%s"/>' % (d, night_c(th, '#A0978B')))
        rl = line_d(cam, offset_line(r['pts'], 0.72), 0.08) + line_d(cam, offset_line(r['pts'], -0.72), 0.08)
        if rl: out.append('<path d="%s" fill="none" stroke="%s" stroke-width=".7"/>' % (rl, '#5E5A56' if not night else '#1A1C22'))
    # big roads: asphalt by lanes, white edge lines, dashed lane lines (from the lanes tag)
    for r in sorted(S['big'], key=lambda r: r['layer']):
        if not near(r['pts']): continue
        t = r['tags']; lanes = int(num(t.get('lanes'), 1 if r['hw'].endswith('link') else 2))
        one = t.get('oneway') == 'yes' or r['hw'] in ('motorway', 'motorway_link')
        w = num(t.get('width'), lanes * 3.5 + (3.0 if r['hw'] == 'motorway' else 1.0))
        y = 0.06 + max(0, r['layer']) * 0.5
        d = gpoly(cam, ribbon(r['pts'], w / 2), y)
        if not d: continue
        out.append('<path d="%s" fill="%s"/>' % (d, night_c(th, '#77777A' if not night else '#22252E', 1)))
        mk = '#F2F1EC' if not night else '#5A5E6A'
        edge = line_d(cam, offset_line(r['pts'], w / 2 - 0.5), y + 0.01) + line_d(cam, offset_line(r['pts'], -(w / 2 - 0.5)), y + 0.01)
        sw = px(cam, 0.18, r['pts'][len(r['pts']) // 2])
        if sw > 0.3: out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f"/>' % (edge, mk, sw))
        if lanes > 1 and sw > 0.3:
            lw = (w - (3.0 if r['hw'] == 'motorway' else 1.0)) / lanes; x0 = -(w / 2) + (2.0 if r['hw'] == 'motorway' else 0.5)
            ds = ''.join(dashes(cam, offset_line(r['pts'], x0 + lw * i), y + 0.01, 3.0, 9.0) for i in range(1, lanes))
            if ds: out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f"/>' % (ds, mk, sw))
    # runways and taxiways: pavement by width, markings from aeroway, ref and width
    for a in sorted(S['aero'], key=lambda a: {'parking_position': 3, 'taxilane': 1, 'taxiway': 1, 'stopway': 0, 'runway': 2}[a['kind']]):
        if not near(a['pts']): continue
        t = a['tags']
        if a['kind'] == 'runway':
            w = num(t.get('width'), 45.0)
            d = gpoly(cam, ribbon(a['pts'], w / 2), 0.07)
            if not d: continue
            out.append('<path d="%s" fill="%s"/>' % (d, night_c(th, '#5F6064' if not night else '#1E2128', 1)))
            mk = '#F4F3EE' if not night else '#4A4F5C'
            sw = px(cam, 0.9, a['pts'][len(a['pts']) // 2])
            cl = dashes(cam, a['pts'], 0.09, 30.0, 20.0)
            if cl: out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f"/>' % (cl, mk, sw))
            out.append(runway_ends(cam, a['pts'], w, t.get('ref') or '', mk, night))
        elif a['kind'] in ('taxiway', 'taxilane', 'stopway'):
            w = num(t.get('width'), 23.0 if a['kind'] == 'taxiway' else 15.0)
            d = gpoly(cam, ribbon(a['pts'], w / 2), 0.065)
            if d: out.append('<path d="%s" fill="%s"/>' % (d, night_c(th, '#8C8C8D' if not night else '#20232A', 1)))
            cl = line_d(cam, a['pts'], 0.08)
            if cl: out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f"/>' % (cl, '#E2BC3C' if not night else '#8A7A3A', px(cam, 0.3, a['pts'][len(a['pts']) // 2])))
        else:      # a stand's lead-in line
            cl = line_d(cam, a['pts'], 0.08)
            if cl: out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f"/>' % (cl, '#E2BC3C' if not night else '#8A7A3A', px(cam, 0.25, a['pts'][0])))
    # breakwaters, groynes, piers: stone or timber strips on the water's edge
    for m in S['marine']:
        if not near(m['pts']): continue
        wdt = {'breakwater': 9.0, 'groyne': 4.0, 'pier': 5.0, 'quay': 3.0}[m['kind']]
        col = {'breakwater': '#9E9A92', 'groyne': '#8E8A84', 'pier': '#A89A86', 'quay': '#B4AEA4'}[m['kind']]
        d = gpoly(cam, m['pts'] if m['closed'] else ribbon(m['pts'], wdt / 2), 0.3)
        if d: out.append('<path d="%s" fill="%s"/>' % (d, night_c(th, col)))
    if S.get('coasters') or S.get('woods'):          # a theme park's tracks, and a park's or a zoo's planted woods
        its, shs = [], []
        if S.get('coasters'):
            import coasters as CO
            its, shs = CO.draw(S['coasters'], cam, th, far, th['_L'], A.light, hx, tohex)
        S['_coaster_items'] = its
        if S.get('woods'):
            import woods as WD
            wits, wsh = WD.draw(S['woods'], cam, th, far, A.light, hx, tohex, mulc, vnorm)
            S['_coaster_items'] = its + wits
            if wsh: out.append('<path d="%s" fill="%s" opacity="%.2f"/>' % (''.join(wsh), th['shadow'], th['shadow_op']))
        for d_, w_ in shs:
            out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f" stroke-linecap="round" opacity="%.2f"/>' % (d_, th['shadow'], max(0.5, w_), th['shadow_op'] * 0.9))
    # a national park's edge: a quiet dashed line on the ground, as on a walking map
    for p in S['park']:
        d = line_d(cam, p['ring'], 0.15)
        if d: out.append('<path d="%s" fill="none" stroke="%s" stroke-width="2.2" stroke-dasharray="7 5" opacity=".85"/>' % (d, '#2F6B3E' if not night else '#4C8A5A'))
    return ''.join(out)

def runway_ends(cam, pts, w, ref, mk, night):
    """Threshold bars and the designation at each end: the numbers come from the runway's ref (e.g. 18C/36C)."""
    out = []
    names = [s.strip() for s in ref.split('/')] if '/' in ref else [None, None]
    for end in (0, 1):
        p0, p1 = (pts[0], pts[1]) if end == 0 else (pts[-1], pts[-2])
        dx, dz = p1[0] - p0[0], p1[1] - p0[1]; L = math.hypot(dx, dz) or 1; ux, uz = dx / L, dz / L; nx, nz = -uz, ux
        bars = []
        nb = 8; bw = 1.8; span = w - 6
        for i in range(nb):
            for sgn in (-1, 1):
                o = sgn * (3 + i * (span / 2 - 3) / nb)
                q = [(p0[0] + ux * 6 + nx * (o - bw / 2), p0[1] + uz * 6 + nz * (o - bw / 2)), (p0[0] + ux * 36 + nx * (o - bw / 2), p0[1] + uz * 36 + nz * (o - bw / 2)),
                     (p0[0] + ux * 36 + nx * (o + bw / 2), p0[1] + uz * 36 + nz * (o + bw / 2)), (p0[0] + ux * 6 + nx * (o + bw / 2), p0[1] + uz * 6 + nz * (o + bw / 2))]
                d = gpoly(cam, q, 0.09, margin=200)
                if d: bars.append(d)
        if bars: out.append('<path d="%s" fill="%s"/>' % (''.join(bars), mk))
        # which name belongs to this end: the one whose heading matches the direction of travel from it
        hd = (math.degrees(math.atan2(ux, -uz)) + 360) % 360
        nm = None
        for s_ in names:
            if s_ and re.match(r'\d+', s_):
                if abs(((int(re.match(r'\d+', s_).group(0)) * 10 - hd) + 180) % 360 - 180) < 30: nm = s_
        if nm:
            txt = LV.ground_text(cam, nm, (p0[0] + ux * 60, 0.09, p0[1] + uz * 60), (nx, 0, nz), (ux, 0, uz), 18, mk, 1.0, 800, 0.05)
            if txt: out.append(txt)
    return ''.join(out)

# ------------------------------------------------------------------ drawing: things standing up
def draw_items(S, cam, th, far, defs):
    night = th['night']; L = th['_L']; items = []
    dark = '#3A3E44' if not night else '#0C0E14'
    # towers mapped as points with a height: a round concrete shaft; an airport or observation tower carries a glazed cab (a generic detail of the type)
    sx_, sz_ = cam.r[0], cam.r[2]
    for tw in S.get('mtowers', []):
        if far(tw['x'], tw['z']): continue
        t = tw['tags']; H = tw['h']; cab = t.get('aeroway') == 'tower' or t.get('tower:type') in ('observation', 'control')
        c = cam.cam((tw['x'], H / 2, tw['z']))
        if c[2] < 5: continue
        rs = 3.2 if cab else max(1.2, H / 30)
        lit_k = max(0.0, sx_ * L[0] + sz_ * L[2]) if not night else 0
        def band(y0, y1, r, col, glass=False):
            q = [(tw['x'] - sx_ * r, y0, tw['z'] - sz_ * r), (tw['x'] + sx_ * r, y0, tw['z'] + sz_ * r), (tw['x'] + sx_ * r, y1, tw['z'] + sz_ * r), (tw['x'] - sx_ * r, y1, tw['z'] - sz_ * r)]
            sp = cam.poly(q, margin=200)
            if not sp: return ''
            gid = 'tw%d_%d' % (tw['id'] % 100000, int(y0))
            sh = th['shadow'] if not night else '#05060A'
            defs.append('<linearGradient id="%s" x1="0" x2="1" y1="0" y2="0"><stop offset="0" stop-color="%s"/><stop offset="%.2f" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>'
                        % (gid, tohex(mix(hx(col), hx(sh), 0.35)), 0.35 + 0.3 * (L[0] * sx_ + L[2] * sz_ > 0), col, tohex(mix(hx(col), hx(sh), 0.5))))
            return '<path d="%s" fill="url(#%s)"/>' % (P(sp), gid)
        shaft = tohex(A.light(th, hx('#C9C6BE'), (0, 0, 1), not night))
        parts = [band(0, H - (14 if cab else 0), rs, shaft)]
        if cab:
            parts.append(band(H - 14, H - 11, rs * 2.3, shaft))
            parts.append(band(H - 11, H - 4.5, rs * 2.6, '#7F98AA' if not night else '#1A2230', True))
            parts.append(band(H - 4.5, H - 3.2, rs * 2.7, tohex(A.light(th, hx('#E2E0DA'), (0, 1, 0), not night))))
            parts.append(band(H - 3.2, H, 0.5, '#8C9096'))
        items.append((c[2], 'svg', ''.join(parts)))
    # wind turbines: tower, nacelle, three blades; the rotor faces the hour's real wind
    wd = math.radians(th['wx']['wind_dir'])
    fx, fz = math.sin(wd), -math.cos(wd)                  # toward where the wind comes from
    for tb in S['turbines']:
        if far(tb['x'], tb['z']): continue
        H = tb['h'] or 100.0; Rr = (tb['rotor'] or 100.0) / 2
        c = cam.cam((tb['x'], H / 2, tb['z']))
        if c[2] < 5: continue
        s = cam.focal / c[2]
        base, top = cam.proj((tb['x'], 0, tb['z'])), cam.proj((tb['x'], H, tb['z']))
        if not (base and top): continue
        g = ['<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ" fill="%s"/>' % (base[0] - 2.2 * s, base[1], base[0] + 2.2 * s, base[1], top[0] + 1.2 * s, top[1], top[0] - 1.2 * s, top[1], '#E9ECEE' if not night else '#3A3F4C')]
        hub = (tb['x'] + fx * 2, H, tb['z'] + fz * 2)
        rx, rz = -fz, fx                                   # the rotor plane: across the wind, and up
        blades = []
        for k in range(3):
            a = math.radians(25 + 120 * k)
            tip = (hub[0] + rx * math.cos(a) * Rr, H + math.sin(a) * Rr, hub[2] + rz * math.cos(a) * Rr)
            mid = (hub[0] + rx * math.cos(a + 0.06) * Rr * 0.3, H + math.sin(a + 0.06) * Rr * 0.3, hub[2] + rz * math.cos(a + 0.06) * Rr * 0.3)
            ph, pt, pm = cam.proj(hub), cam.proj(tip), cam.proj(mid)
            if ph and pt and pm: blades.append('M%.1f %.1fL%.1f %.1fL%.1f %.1fZ' % (ph[0], ph[1], pm[0], pm[1], pt[0], pt[1]))
        if blades: g.append('<path d="%s" fill="%s" stroke="%s" stroke-width=".4"/>' % (''.join(blades), '#F2F4F5' if not night else '#454B58', '#C9CDD1' if not night else '#2A2E38'))
        ph = cam.proj(hub)
        if ph: g.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (ph[0], ph[1], max(0.8, 2.2 * s), '#DADDE0' if not night else '#3A3F4C'))
        if night and ph: g.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#FF3B30" opacity=".9"/>' % (ph[0], ph[1] - max(1.0, 3 * s), max(0.8, 1.2 * s)))
        items.append((c[2], 'svg', ''.join(g)))
    # cranes (man_made=crane): solid painted steel, height and colour generic as neither is mapped here.
    # On the quay edge, the ship-to-shore shape: a portal with a boom out over the water; in the yard, a portal gantry over the stack.
    steel = hx('#7D8993') if not night else hx('#1A1E24')
    def box(c0, u, v, hu, hv, y0, y1, col=steel):
        """A beam or leg as a lit box: centre c0 (x, z), its axes u and v (unit), half sizes, from y0 to y1."""
        (cx, cz), (ux_, uz_), (vx_, vz_) = c0, u, v
        P8 = lambda su, sv, y: (cx + ux_ * su * hu + vx_ * sv * hv, y, cz + uz_ * su * hu + vz_ * sv * hv)
        faces = [((ux_, 0, uz_), [P8(1, -1, y0), P8(1, 1, y0), P8(1, 1, y1), P8(1, -1, y1)]), ((-ux_, 0, -uz_), [P8(-1, 1, y0), P8(-1, -1, y0), P8(-1, -1, y1), P8(-1, 1, y1)]),
                 ((vx_, 0, vz_), [P8(1, 1, y0), P8(-1, 1, y0), P8(-1, 1, y1), P8(1, 1, y1)]), ((-vx_, 0, -vz_), [P8(-1, -1, y0), P8(1, -1, y0), P8(1, -1, y1), P8(-1, -1, y1)]),
                 ((0, 1, 0), [P8(-1, -1, y1), P8(1, -1, y1), P8(1, 1, y1), P8(-1, 1, y1)])]
        out = []
        for n3, q in faces:
            m = (sum(p[0] for p in q) / 4, sum(p[1] for p in q) / 4, sum(p[2] for p in q) / 4)
            if vdot(n3, vsub(cam.C, m)) <= 0: continue
            sp = cam.poly(q, margin=200)
            if not sp or A.area(sp) < 0.05: continue
            colr = tohex(A.light(th, col, n3, (not night) and vdot(n3, L) > 0))
            out.append('<path d="%s" fill="%s" stroke="%s" stroke-width=".25"/>' % (P(sp), colr, colr))
        return ''.join(out)
    if S['cranes'] and '_qedges' not in S: S['_qedges'] = [(a_, b_, nx_, nz_) for (a_, b_, nx_, nz_, r_) in A.water_edges(S)]
    def to_water(x, z):
        best = (1e9, None)
        for (a_, b_, nx_, nz_) in S['_qedges']:
            ux_, uz_ = b_[0] - a_[0], b_[1] - a_[1]; L2 = ux_ * ux_ + uz_ * uz_ or 1
            t = max(0, min(1, ((x - a_[0]) * ux_ + (z - a_[1]) * uz_) / L2)); d = math.hypot(a_[0] + ux_ * t - x, a_[1] + uz_ * t - z)
            if d < best[0]: best = (d, (nx_, nz_))
        return best
    rails = [r for r in S['rail'] if r['crane']]
    for cr in S['cranes']:
        if far(cr['x'], cr['z']): continue
        c = cam.cam((cr['x'], 15, cr['z']))
        if c[2] < 5: continue
        if cam.focal / c[2] < 0.6:            # too far to show as more than a speck
            continue
        dw, wn = to_water(cr['x'], cr['z'])
        parts = []
        if cr['poly'] is None and dw < 60 and wn:
            # ship-to-shore: portal legs either side of the quay rail, the boom from behind the portal out over the water
            w = wn
            if not A.on_water(S, cr['x'] + w[0] * (dw + 25), cr['z'] + w[1] * (dw + 25)) and A.on_water(S, cr['x'] - w[0] * (dw + 25), cr['z'] - w[1] * (dw + 25)):
                w = (-w[0], -w[1])            # the boom reaches over the side where the water is
            q = (-w[1], w[0]); H = num(cr['tags'].get('height'), 44.0); g, sp_ = 9.0, 8.0
            for sw in (-1, 1):
                for sq in (-1, 1):
                    parts.append((cr['x'] + w[0] * sw * g + q[0] * sq * sp_, cr['z'] + w[1] * sw * g + q[1] * sq * sp_, box((cr['x'] + w[0] * sw * g + q[0] * sq * sp_, cr['z'] + w[1] * sw * g + q[1] * sq * sp_), w, q, 0.8, 0.8, 0, H)))
            for sq in (-1, 1):            # sill beams along the boom axis, at the foot and under the boom
                for y in (9.0, H - 3.0):
                    cc = (cr['x'] + q[0] * sq * sp_, cr['z'] + q[1] * sq * sp_); parts.append((cc[0], cc[1], box(cc, w, q, g, 0.6, y - 1.0, y)))
            back, out_ = 22.0, dw + 52.0
            mid = (back * -1 + out_) / 2
            bc = (cr['x'] + w[0] * mid, cr['z'] + w[1] * mid)
            parts.append((bc[0], bc[1], box(bc, w, q, (out_ + back) / 2, 2.0, H - 1.0, H + 2.4)))                 # the boom
            mh = (cr['x'] - w[0] * 14, cr['z'] - w[1] * 14)
            parts.append((mh[0], mh[1], box(mh, w, q, 6.0, 4.5, H + 2.4, H + 7.0, hx('#C9CCCF') if not night else hx('#20242A'))))  # machinery house
            for sq in (-1, 1):            # the A-frame above the portal
                ap = (cr['x'] + w[0] * 3 + q[0] * sq * sp_ * 0.6, cr['z'] + w[1] * 3 + q[1] * sq * sp_ * 0.6)
                parts.append((ap[0], ap[1], box(ap, w, q, 0.7, 0.7, H + 2.4, H + 20.0)))
            top = cam.proj((cr['x'] + w[0] * 3, H + 20.0, cr['z'] + w[1] * 3)); tip = cam.proj((cr['x'] + w[0] * out_, H + 2.4, cr['z'] + w[1] * out_)); tail = cam.proj((cr['x'] - w[0] * back, H + 2.4, cr['z'] - w[1] * back))
            stays = ''.join('M%.1f %.1fL%.1f %.1f' % (top[0], top[1], e[0], e[1]) for e in (tip, tail) if top and e)
            sw_ = max(0.5, 0.35 * cam.focal / c[2])
            body = ''.join(p_[2] for p_ in sorted(parts, key=lambda p_: -cam.depth((p_[0], H / 2, p_[1]))))
            items.append((c[2], 'svg', body + ('<path d="%s" fill="none" stroke="%s" stroke-width="%.2f"/>' % (stays, tohex(steel), sw_) if stays else '')))
            continue
        # a yard gantry over the stack: legs at the corners, girders along the span, end beams, a trolley on top
        H = num(cr['tags'].get('height'), 26.0)
        if cr['poly'] and len(cr['poly']) >= 4:
            pts = cr['poly'][:-1] if cr['poly'][0] == cr['poly'][-1] else cr['poly']
            e0 = max(range(len(pts)), key=lambda i: math.dist(pts[i], pts[(i + 1) % len(pts)]))
            a, b = pts[e0], pts[(e0 + 1) % len(pts)]; ux, uz = (b[0] - a[0]), (b[1] - a[1]); Lg = math.hypot(ux, uz) or 1; ux, uz = ux / Lg, uz / Lg
            proj_u = [((p[0] - cr['x']) * ux + (p[1] - cr['z']) * uz) for p in pts]; proj_v = [(-(p[0] - cr['x']) * uz + (p[1] - cr['z']) * ux) for p in pts]
            hu, hv = max(4, (max(proj_u) - min(proj_u)) / 2), max(3, (max(proj_v) - min(proj_v)) / 2)
        else:
            ux, uz = 1.0, 0.0; best = 1e9
            for r in rails:
                for i in range(len(r['pts']) - 1):
                    p, q_ = r['pts'][i], r['pts'][i + 1]
                    dd = math.hypot((p[0] + q_[0]) / 2 - cr['x'], (p[1] + q_[1]) / 2 - cr['z'])
                    if dd < best: best = dd; L_ = math.dist(p, q_) or 1; ux, uz = (q_[0] - p[0]) / L_, (q_[1] - p[1]) / L_
            hu, hv = 9.0, 14.0
        u, v = (ux, uz), (-uz, ux)
        if hu > 2.5 * hv:
            # an outline much longer than wide is the crane's runway along its rails: one portal spans it, a few metres deep;
            # where along the runway it stands is not mapped, so it is drawn at the middle
            u, v = v, u; ux, uz = u; hu, hv = hv, 4.0
        for su in (-1, 1):
            for sv in (-1, 1):
                cc = (cr['x'] + ux * su * (hu - 0.6) + v[0] * sv * (hv - 0.6), cr['z'] + uz * su * (hu - 0.6) + v[1] * sv * (hv - 0.6))
                parts.append((cc[0], cc[1], box(cc, u, v, 0.6, 0.6, 0, H)))
        for sv in (-1, 1):
            cc = (cr['x'] + v[0] * sv * (hv - 0.6), cr['z'] + v[1] * sv * (hv - 0.6)); parts.append((cc[0], cc[1], box(cc, u, v, hu, 0.6, H - 2.0, H)))
        for su in (-1, 1):
            cc = (cr['x'] + ux * su * (hu - 0.6), cr['z'] + uz * su * (hu - 0.6)); parts.append((cc[0], cc[1], box(cc, u, v, 0.6, hv, H - 2.0, H)))
        parts.append((cr['x'], cr['z'], box((cr['x'], cr['z']), u, v, 2.2, min(hv, 3.0), H, H + 2.6, hx('#C9CCCF') if not night else hx('#20242A'))))
        body = ''.join(p_[2] for p_ in sorted(parts, key=lambda p_: -cam.depth((p_[0], H / 2, p_[1]))))
        items.append((c[2], 'svg', body))
    # masts: a slim steel shaft; a lighting mast carries a frame of floodlights, lit after dark (height generic where not mapped)
    for m in S.get('masts', []):
        if far(m['x'], m['z']): continue
        t = m['tags']; lighting = t.get('tower:type') == 'lighting'
        H = m['h'] or (30.0 if lighting else 25.0)
        c = cam.cam((m['x'], H / 2, m['z']))
        if c[2] < 5 or cam.focal / c[2] < 0.25: continue
        u = (cam.r[0], cam.r[2]); v = (-u[1], u[0])
        g = box((m['x'], m['z']), u, v, 0.45, 0.45, 0, H)
        if lighting:
            g += box((m['x'], m['z']), u, v, 1.7, 0.6, H, H + 1.3, hx('#5E646B') if not night else hx('#15181E'))
            if night:
                hp = cam.proj((m['x'], H, m['z']))
                if hp:
                    r_ = max(2.0, 9.0 * cam.focal / c[2])
                    g += '<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#mastglow)"/><circle cx="%.1f" cy="%.1f" r="%.1f" fill="#FFF4DC"/>' % (hp[0], hp[1], r_, hp[0], hp[1], max(0.6, 1.2 * cam.focal / c[2]))
        items.append((c[2], 'svg', g))
    if night and any(m['tags'].get('tower:type') == 'lighting' for m in S.get('masts', [])):
        defs.append('<radialGradient id="mastglow"><stop offset="0" stop-color="#FFE9C2" stop-opacity=".75"/><stop offset=".35" stop-color="#FFD9A0" stop-opacity=".22"/><stop offset="1" stop-color="#FFD9A0" stop-opacity="0"/></radialGradient>')
    # gantries over a road (man_made=gantry): a portal of two legs and a beam; conveyors (man_made=goods_conveyor): a closed gallery on legs.
    # Their heights are generic where not mapped.
    for kind, coll in (('gantry', S.get('gantries', [])), ('conveyor', S.get('conveyors', []))):
        for gt in coll:
            pts = gt['pts']
            if all(far(*p) for p in pts): continue
            H = gt['h'] or (7.0 if kind == 'gantry' else 6.0)
            for i in range(len(pts) - 1):
                a, b = pts[i], pts[i + 1]; Lg = math.dist(a, b)
                if Lg < 0.5 or (far(*a) and far(*b)): continue
                u = ((b[0] - a[0]) / Lg, (b[1] - a[1]) / Lg); v = (-u[1], u[0]); mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                c = cam.cam((mid[0], H, mid[1]))
                if c[2] < 5 or cam.focal / c[2] < 0.3: continue
                parts = []
                if kind == 'gantry':
                    for e_ in ((a if i == 0 else None), (b if i == len(pts) - 2 else None)):
                        if e_: parts.append((e_[0], e_[1], box(e_, u, v, 0.35, 0.35, 0, H)))
                    parts.append((mid[0], mid[1], box(mid, u, v, Lg / 2, 0.45, H - 0.9, H)))
                else:
                    nl = max(1, int(Lg / 22))
                    for k in range(nl + 1):
                        f = k / nl; lp = (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
                        parts.append((lp[0], lp[1], box(lp, u, v, 0.4, 1.3, 0, H - 2.2)))
                    parts.append((mid[0], mid[1], box(mid, u, v, Lg / 2, 1.4, H - 2.2, H, hx('#B4B1A9') if not night else hx('#1C1E22'))))
                body = ''.join(p_[2] for p_ in sorted(parts, key=lambda p_: -cam.depth((p_[0], H / 2, p_[1]))))
                items.append((c[2], 'svg', body))
    items.extend(S.pop('_coaster_items', []))          # coaster tracks and their posts, drawn in draw_over's pass
    # power lines: towers with three conductors between them (tower height generic where not mapped)
    for pl in S['power']:
        pts = pl['pts']
        if len(pts) < 2 or not any(not far(*p) for p in pts): continue
        H = 38.0
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            if far(*a) and far(*b): continue
            c = cam.cam(((a[0] + b[0]) / 2, H, (a[1] + b[1]) / 2))
            if c[2] < 5: continue
            fade = 1.0 if c[2] <= POWER_NEAR else (POWER_FAR - c[2]) / (POWER_FAR - POWER_NEAR)
            if fade <= 0: continue
            s = cam.focal / c[2]; segs = []
            for off in (-6, 0, 6):
                dx, dz = b[0] - a[0], b[1] - a[1]; Lg = math.hypot(dx, dz) or 1; nx, nz = -dz / Lg * off, dx / Lg * off
                q = [cam.proj((a[0] + dx * f + nx, H - 4 * math.sin(math.pi * f) * min(1, Lg / 300) * 8 / 8, a[1] + dz * f + nz)) for f in (0, .25, .5, .75, 1)]
                if all(q): segs.append('M' + 'L'.join('%.1f %.1f' % p for p in q))
            p0, p1 = cam.proj((a[0], 0, a[1])), cam.proj((a[0], H + 2, a[1]))
            tw = '<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f"/>' % (p0[0], p0[1], p1[0], p1[1], '#6E747C' if not night else '#1A1E26', max(0.5, 1.4 * s)) if p0 and p1 else ''
            body = tw + ('<path d="%s" fill="none" stroke="%s" stroke-width=".45" opacity=".8"/>' % (''.join(segs), '#4A4F57' if not night else '#1A1E26') if segs else '')
            items.append((c[2], 'svg', body if fade >= 1 else '<g opacity="%.2f">%s</g>' % (fade, body)))
    return items

def view_moments(S, name, cfg):
    """What a view holds, counted from the mapped data in view, and the real weather of each moment: out/<name>_moments.json."""
    TX, TZ, RAD = cfg['target'][0], cfg['target'][2], cfg['radius']
    inv = lambda x, z: math.hypot(x - TX, z - TZ) <= RAD
    anyin = lambda pts: any(inv(*p) for p in pts)
    raw = json.load(open(os.path.join(ROOT, base_of(name), 'raw.json')))['elements']
    def cnt(pred):
        n = 0
        for e in raw:
            t = e.get('tags') or {}
            if not pred(t): continue
            if e['type'] == 'node': n += inv(*A.XZ(e['lat'], e['lon']))
            elif e.get('geometry'): n += anyin([A.XZ(g['lat'], g['lon']) for g in e['geometry'] if g])
            elif e['type'] == 'relation': n += 1
        return n
    runways = sorted(set(a['tags'].get('ref') for a in S['aero'] if a['kind'] == 'runway' and a['tags'].get('ref') and anyin(a['pts'])))
    C = dict(runways=len(runways), runway_refs=runways, gates=cnt(lambda t: t.get('aeroway') == 'gate'), stands=cnt(lambda t: t.get('aeroway') == 'parking_position'),
             lanes=cnt(lambda t: t.get('highway') in ('motorway', 'motorway_link') and t.get('lanes')), lights=cnt(lambda t: t.get('aeroway') == 'navigationaid'),
             heights=cnt(lambda t: (t.get('building') or t.get('building:part')) and t.get('height')), buildings=cnt(lambda t: t.get('building') and t.get('building') != 'no'),
             roofs=cnt(lambda t: t.get('roof:shape')), trees=cnt(lambda t: t.get('natural') == 'tree'), shops=cnt(lambda t: 'shop' in t),
             cranes=cnt(lambda t: t.get('man_made') == 'crane'), tanks=cnt(lambda t: t.get('man_made') == 'storage_tank'), turbines=cnt(lambda t: t.get('generator:source') == 'wind'),
             forest=cnt(lambda t: t.get('landuse') == 'forest' or t.get('natural') == 'wood'), sand=cnt(lambda t: t.get('natural') in ('sand', 'beach', 'dune')),
             cycleways=cnt(lambda t: t.get('highway') == 'cycleway'), paths=cnt(lambda t: t.get('highway') in ('path', 'footway', 'bridleway')), lakes=cnt(lambda t: t.get('natural') == 'water'))
    # whose walls and roofs are mapped, and whose look is generic for their type
    vis = [b for b in S['buildings'] if not b.get('hidden') and inv(*centroid(b['poly']))]
    C['looks_total'] = len(vis)
    C['looks_mapped'] = sum(1 for b in vis if b['tags'].get('building:material') or b['tags'].get('building:colour') or b['tags'].get('roof:colour') or b['tags'].get('roof:material'))
    C['looks_guessed'] = C['looks_total'] - C['looks_mapped']
    C['towers'] = cnt(lambda t: t.get('man_made') == 'tower' and (t.get('building') or t.get('building:part')))
    C['levels'] = cnt(lambda t: t.get('building') and t.get('building:levels'))
    C['colours'] = cnt(lambda t: t.get('building') and (t.get('building:colour') or t.get('building:material')))
    C['lamps'] = cnt(lambda t: t.get('highway') == 'street_lamp')
    C['lit'] = cnt(lambda t: t.get('highway') and t.get('lit') == 'yes')
    C['mansards'] = sum(1 for b in vis if b.get('roof') == 'mansard' and b.get('guessed', {}).get('roof:shape'))      # guessed for Paris stone fronts
    C['masts'] = sum(1 for m in S.get('masts', []) if m['tags'].get('tower:type') == 'lighting' and inv(m['x'], m['z']))
    C['conveyors'] = sum(1 for g in S.get('conveyors', []) if anyin(g['pts']))
    tw = [t_ for t_ in S.get('mtowers', []) if t_['tags'].get('aeroway') == 'tower' and inv(t_['x'], t_['z'])]
    if tw: C['tower'] = '%g' % round(tw[0]['h'])
    if S.get('_water_level'):
        W_ = C['water'] = S['_water_level']
        if 'water' in W_: C['drop'] = '%.1f' % (W_['bank'] - W_['water'])                              # IGN: the river surface against its quays
        if 'tide' in W_: C['drop_at'] = {k: '%.1f' % (W_['bank'] - v) for k, v in W_['tide'].items()}   # AHN quays against the hour's tide
    if S.get('dem'):
        C['top'] = round(max((S['_grid'].h if S.get('_grid') else S['dem'].h)(TX + i * 60, TZ + j * 60) for i in range(-int(RAD / 60), int(RAD / 60)) for j in range(-int(RAD / 60), int(RAD / 60)) if math.hypot(i, j) * 60 <= RAD))
    M = dict(heading=cfg['yaw'] % 360, counts=C)
    for k in ('wet', 'grey', 'sunny'):
        th = A.THEMES[k]; hh, mm = th['hhmm']; y_, mo_, dd_ = th['date']
        import datetime as _dt
        az, el = A.sun_position(_dt.datetime(y_, mo_, dd_, hh, mm) - _dt.timedelta(hours=A.TZ_H))
        M[k] = dict(az=az, el=el, wx=th['wx'], night=th['night'], wet=th.get('wet', False))
    json.dump(M, open(os.path.join(HERE, '..', 'cache', 'render', '%s_moments.json' % name), 'w'), indent=1)
    return C, runways

# ------------------------------------------------------------------ main
def cfg_for(name):
    if name not in CAMS and name.endswith('_detail') and name[:-7] in CAMS: CAMS[name] = dict(CAMS[name[:-7]])     # the second pass: the same camera
    if name not in CAMS and name.endswith('_kit') and name[:-4] in CAMS: CAMS[name] = dict(CAMS[name[:-4]])     # the kit and profile pass: the same camera
    if name not in CAMS:            # a street-level view: its camera as the street renderer wrote it
        p_ = os.path.join(HERE, '..', 'cache', 'render', '%s_cfg.json' % name)
        if os.path.exists(p_): CAMS[name] = {k: v for k, v in json.load(open(p_)).items() if k not in ('W', 'H')}
    c = dict(CAMS[name]); c.update(W=412, H=915, no_fin=True, obj_split=1900000)
    c.setdefault('haze0', c['dist'] * 0.85); c.setdefault('haze1', c['dist'] * 2.2)      # the air thickens with the camera's own distance
    return c

if __name__ == '__main__':
    os.makedirs(os.path.join(HERE, '..', 'cache', 'render'), exist_ok=True)     # a fresh clone has no cache yet
    name = sys.argv[1]
    S = load(name)
    cfg = cfg_for(name)
    print(name, {k: len(v) for k, v in S.items() if isinstance(v, (list, dict, set)) and k not in ('R',)})
    for k in (sys.argv[2].split(',') if len(sys.argv) > 2 else ['sunny']):
        if S.get('_tide'):            # the tide of that moment, under quays that stand at their surveyed height
            y = max(-9.0, min(-0.6, S['_tide'][k] - S['_bank'])); A.WATER_Y = y
            for r in S['_tidal']: S['water_y'][id(r)] = y
        (base, objs), cam, info = A.render(S, k, cfg, None, layer='split')
        open(os.path.join(HERE, '..', 'cache', 'render', '%s_%s_g.svg' % (name, k)), 'w').write(base)
        o2 = os.path.join(HERE, '..', 'cache', 'render', '%s_%s_o2.svg' % (name, k))
        if os.path.exists(o2): os.remove(o2)
        if isinstance(objs, tuple):        # objects in two layers, far then near
            open(o2, 'w').write(objs[1]); objs = objs[0]
        open(os.path.join(HERE, '..', 'cache', 'render', '%s_%s_o.svg' % (name, k)), 'w').write(objs)
        for ext in ('jpg', 'png', 'json'):
            q = os.path.join(HERE, '..', 'cache', 'render', '%s_%s_t.%s' % (name, k, ext))
            if os.path.exists(q): os.remove(q)
        if info.get('raster'):
            ext, data, clip = info['raster']
            open(os.path.join(HERE, '..', 'cache', 'render', '%s_%s_t.%s' % (name, k, ext)), 'wb').write(data)
            json.dump(dict(ext=ext, clip=clip), open(os.path.join(HERE, '..', 'cache', 'render', '%s_%s_t.json' % (name, k)), 'w'))
            print(name, k, 'raster', ext, len(data) // 1024, 'KB', 'clipped' if clip else 'full frame')
        print(name, k, 'ground', len(base) // 1024, 'KB', 'objects', len(objs) // 1024, 'KB', 'near', (os.path.getsize(o2) // 1024) if os.path.exists(o2) else 0, 'KB', {x: round(v, 1) for x, v in info.items() if x not in ('counts', 'raster')})
    json.dump(cfg, open(os.path.join(HERE, '..', 'cache', 'render', '%s_cfg.json' % name), 'w'))
    C, runways = view_moments(S, name, cfg)
    print('counts', {k: v for k, v in C.items() if k != 'runway_refs'}, runways)
