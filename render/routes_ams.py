"""Walking routes on the living map at the Westerkerk: from the Grachtenvlinder footbridge to the main entrance of
Anne Frank Huis (an entrance=main node on Westermarkt), on the real walkable OpenStreetMap network.
Every metre is scored at Thursday 1 October 2026, 16:00 CEST: sun or shade from mapped building heights (guessed
where missing) and the mapped trees, near a canal or not, on a busy street or not. Three ways come out: fastest,
shadiest and quietest, each with its turns by street name, projected onto the living map's camera.
Writes cache/render/ams_routes.json."""
import json, math, os, sys, heapq, datetime, collections
HERE = os.path.dirname(os.path.abspath(__file__)); RD = os.path.join(HERE, '..', 'render'); sys.path.insert(0, RD)
import amsterdam as AM
import living as LV
from city import Camera
from real import Shadows, pip

S = AM.load()
AM.guess_tags(S)
raw = json.load(open(os.path.join(HERE, '..', 'data', 'ams', 'raw.json')))['elements']
NODE = {e['id']: e for e in raw if e['type'] == 'node'}
cfg = json.load(open(os.path.join(RD, '..', 'cache', 'render', 'ams_cfg.json')))
cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))

# the sun at Thursday 1 October 2026, 16:00 CEST
az, el = AM.sun_position(datetime.datetime(2026, 10, 1, 14, 0))
L = LV.sun_vector(az, el)
blds = [b for b in S['buildings'] if b.get('h') and not b.get('hidden')] + [p for p in S['parts'] if p.get('h')]
SH = Shadows(blds, L)
TREES = [(t['x'], t['z']) for t in S['trees']]
TG = collections.defaultdict(list)
for x, z in TREES: TG[(int(x // 10), int(z // 10))].append((x, z))
def under_tree(x, z, r=3.5):
    for i in (-1, 0, 1):
        for j in (-1, 0, 1):
            for tx, tz in TG.get((int(x // 10) + i, int(z // 10) + j), ()):
                if (tx - x) ** 2 + (tz - z) ** 2 < r * r: return True
    return False
WATER = [w['poly'] if isinstance(w, dict) else w for w in S['water']]
def near_water(x, z, d=14.0):
    for poly in WATER:
        if pip(x, z, poly): return True
        for k in range(len(poly)):
            a, b = poly[k], poly[(k + 1) % len(poly)]
            ax, az_, bx, bz = a[0], a[1], b[0], b[1]
            vx, vz = bx - ax, bz - az_; ll = vx * vx + vz * vz or 1e-9
            t = max(0, min(1, ((x - ax) * vx + (z - az_) * vz) / ll))
            if (ax + vx * t - x) ** 2 + (az_ + vz * t - z) ** 2 < d * d: return True
    return False

WALK = {'footway', 'pedestrian', 'residential', 'living_street', 'path', 'steps', 'tertiary', 'unclassified', 'service'}
BUSY = {'tertiary', 'secondary', 'primary'}
adj = collections.defaultdict(dict); XY = {}; EDGE = {}
for e in raw:
    t = e.get('tags', {})
    if e['type'] != 'way' or t.get('highway') not in WALK or t.get('foot') == 'no' or t.get('access') == 'no': continue
    ns, g = e['nodes'], e['geometry']
    for i, n in enumerate(ns): XY[n] = AM.XZ(g[i]['lat'], g[i]['lon'])
    for a, b in zip(ns, ns[1:]):
        Lm = math.dist(XY[a], XY[b])
        adj[a][b] = adj[b][a] = Lm
        EDGE[(a, b)] = EDGE[(b, a)] = dict(name=t.get('name'), hw=t['highway'], lit=t.get('lit') == 'yes', bridge=t.get('bridge') == 'yes', way=e['id'], surface=t.get('surface'))

STEP = 3.0
CACHE = {}
def score(a, b):
    k = (min(a, b), max(a, b))
    if k in CACHE: return CACHE[k]
    pa, pb = XY[a], XY[b]; n = max(1, int(math.dist(pa, pb) / STEP))
    sun = water = 0
    for i in range(n):
        t = (i + 0.5) / n; x = pa[0] + (pb[0] - pa[0]) * t; z = pa[1] + (pb[1] - pa[1]) * t
        lit = SH.lit((x, 1.3, z)) and not under_tree(x, z)
        sun += lit; water += near_water(x, z)
    r = CACHE[k] = dict(sun=sun / n, water=water / n, busy=1.0 if EDGE[(a, b)]['hw'] in BUSY else 0.0)
    return r

def nearest(p):
    return min(XY, key=lambda n: (XY[n][0] - p[0]) ** 2 + (XY[n][1] - p[1]) ** 2 if adj.get(n) else 1e18)
bridge = next(e for e in raw if e['type'] == 'way' and e['id'] == 1154239471)
g = bridge['geometry']; mid = AM.XZ((g[0]['lat'] + g[-1]['lat']) / 2, (g[0]['lon'] + g[-1]['lon']) / 2)
ent = NODE[5713686919]; entp = AM.XZ(ent['lat'], ent['lon'])
src, dst = 10734715940, nearest(entp)          # the footbridge node under 'you' on the Map screen
mid = XY[src]

def dijkstra(cost):
    dist = {src: 0.0}; prev = {}; pq = [(0.0, src)]
    while pq:
        d, u = heapq.heappop(pq)
        if u == dst: break
        if d > dist.get(u, 1e18): continue
        for v, Lm in adj[u].items():
            nd = d + cost(u, v, Lm)
            if nd < dist.get(v, 1e18): dist[v] = nd; prev[v] = u; heapq.heappush(pq, (nd, v))
    path = [dst]
    while path[-1] != src: path.append(prev[path[-1]])
    return path[::-1]

COSTS = {
    'fast': lambda u, v, Lm: Lm,
    'shade': lambda u, v, Lm: Lm * (1 + 2.2 * score(u, v)['sun']),
    'quiet': lambda u, v, Lm: Lm * (1 + 1.6 * score(u, v)['busy']),
}
def bearing(p, q): return math.degrees(math.atan2(q[0] - p[0], -(q[1] - p[1])))
def turn_word(d):
    d = (d + 540) % 360 - 180
    if abs(d) < 25: return 'straight'
    if abs(d) < 60: return 'slight ' + ('right' if d > 0 else 'left')
    return 'right' if d > 0 else 'left'

out = {'sun': dict(az=round(az, 1), el=round(el, 1)), 'start': 'Grachtenvlinder', 'dest': 'Anne Frank Huis, main entrance on Westermarkt',
       'entrance': dict(node=5713686919, xy=list(cam.proj((entp[0], 0, entp[1])))), 'routes': {}}
fast_len = None
for key, cost in COSTS.items():
    path = dijkstra(cost)
    pts = [mid] + [XY[n] for n in path] + [entp]
    # end where Westermarkt passes the door, not past it and back
    A_, B_ = XY[path[-2]], XY[path[-1]]; vx, vz = B_[0] - A_[0], B_[1] - A_[1]; ll = vx * vx + vz * vz or 1e-9
    tt = ((entp[0] - A_[0]) * vx + (entp[1] - A_[1]) * vz) / ll
    if 0 < tt < 1: pts = [mid] + [XY[n] for n in path[:-1]] + [(A_[0] + vx * tt, A_[1] + vz * tt), entp]
    Ls = [math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]
    total = sum(Ls)
    sc = [score(a, b) for a, b in zip(path, path[1:])]; el_ = [adj[a][b] for a, b in zip(path, path[1:])]
    w = sum(el_) or 1
    sun = sum(s['sun'] * l for s, l in zip(sc, el_)) / w; water = sum(s['water'] * l for s, l in zip(sc, el_)) / w; busy = sum(s['busy'] * l for s, l in zip(sc, el_)) / w
    # steps by street name
    steps = []; cur = None
    for a, b in zip(path, path[1:]):
        nm = EDGE[(a, b)]['name'] or ('the footbridge' if EDGE[(a, b)]['bridge'] else 'the path')
        if cur and cur['name'] == nm: cur['len'] += adj[a][b]; cur['b'] = b; continue
        cur = dict(name=nm, len=adj[a][b], a=a, b=b, hw=EDGE[(a, b)]['hw']); steps.append(cur)
    for i, s_ in enumerate(steps):
        if i == 0: s_['turn'] = 'start'; continue
        p0 = XY[steps[i - 1]['a']]; p1 = XY[s_['a']]; p2 = XY[s_['b']]
        s_['turn'] = turn_word(bearing(p1, p2) - bearing(p0, p1))
    scr = []
    for p in pts:
        q = cam.proj((p[0], 0.0, p[1])); scr.append([round(q[0], 1), round(q[1], 1)] if q else None)
    if key == 'fast': fast_len = total
    lit = sum(adj[a][b] for a, b in zip(path, path[1:]) if EDGE[(a, b)]['lit']) / w
    rough = sum(adj[a][b] for a, b in zip(path, path[1:]) if EDGE[(a, b)]['surface'] in ('sett', 'cobblestone', 'unhewn_cobblestone', 'paving_stones:30', 'wood')) / w
    surf = collections.Counter()
    for a, b in zip(path, path[1:]): surf[EDGE[(a, b)]['surface'] or 'not mapped'] += adj[a][b]
    crossings = [n for n in path if NODE.get(n, {}).get('tags', {}).get('highway') == 'crossing']
    steps_ = [1 for a, b in zip(path, path[1:]) if EDGE[(a, b)]['hw'] == 'steps']
    # the same way's shade later in the afternoon, from the real sun
    later = {}
    for hh, mm in ((16, 30), (17, 0), (17, 30), (18, 0), (18, 30)):
        az2, el2 = AM.sun_position(datetime.datetime(2026, 10, 1, hh - 2, mm))
        if el2 <= 0: later['%d:%02d' % (hh, mm)] = 100; continue
        sh2 = Shadows(blds, LV.sun_vector(az2, el2)); sun2 = 0; nn = 0
        for a, b in zip(path, path[1:]):
            pa, pb = XY[a], XY[b]; n = max(1, int(math.dist(pa, pb) / STEP))
            for i in range(n):
                t = (i + 0.5) / n; x = pa[0] + (pb[0] - pa[0]) * t; z = pa[1] + (pb[1] - pa[1]) * t
                sun2 += (sh2.lit((x, 1.3, z)) and not under_tree(x, z)); nn += 1
        later['%d:%02d' % (hh, mm)] = round(100 * (1 - sun2 / nn))
    out['routes'][key] = dict(lit=round(100 * lit), rough=round(100 * rough), surfaces={k: round(v) for k, v in surf.most_common()}, crossings=len(crossings), steps_n=len(steps_), shade_later=later,
                              len=round(total), min=max(1, math.ceil(total / 80)), shade=round(100 * (1 - sun)), water=round(100 * water), busy=round(100 * busy),
                              nodes=path, screen=scr, steps=[dict(name=s['name'], len=round(s['len']), turn=s['turn'], hw=s['hw']) for s in steps])
    print(key, round(total), 'm', out['routes'][key]['min'], 'min', 'shade', out['routes'][key]['shade'], '% water', out['routes'][key]['water'], '% busy', out['routes'][key]['busy'], '%')
    print('    lit', out['routes'][key]['lit'], '% rough', out['routes'][key]['rough'], '% surfaces', out['routes'][key]['surfaces'], 'crossings', len(crossings), 'shade later', later)
    print('   ', ' → '.join('%s %s %dm' % (s['turn'], s['name'], s['len']) for s in out['routes'][key]['steps']))
json.dump(out, open(os.path.join(RD, '..', 'cache', 'render', 'ams_routes.json'), 'w'), indent=1)
print('sun az %.1f el %.1f' % (az, el), 'start', [round(v) for v in mid], 'entrance on screen', out['entrance']['xy'])
