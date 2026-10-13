#!/usr/bin/env python3
"""Shade-aware walking: route on real OSM sidewalks and crossings, score every metre for
sun or shade from real building heights and street-tree crowns, at a given moment."""
import json, math, sys, os, heapq, collections, datetime
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import real, living as LV

EYE = 1.3            # shade measured at shoulder height
STEP = 3.0           # metres between samples
TREE_T = 0.25        # sunlight through a leafy crown

def load_graph():
    d = json.load(open(os.path.join(HERE, '..', 'osm', 'raw.json')))['elements']
    W = [e for e in d if e['type'] == 'way' and e.get('tags', {}).get('highway') in ('footway', 'path', 'pedestrian', 'steps', 'living_street')]
    adj = collections.defaultdict(dict); coord = {}; kind = {}
    for e in W:
        ns, g = e['nodes'], e['geometry']
        for i, n in enumerate(ns): coord[n] = real.XZ(g[i]['lat'], g[i]['lon'])
        k = 'crossing' if e['tags'].get('footway') == 'crossing' else 'steps' if e['tags']['highway'] == 'steps' else 'walk'
        for a, b in zip(ns, ns[1:]):
            L = math.dist(coord[a], coord[b])
            adj[a][b] = adj[b][a] = L
            kind[(a, b)] = kind[(b, a)] = k
    parks = {}
    for e in d:
        t = e.get('tags', {})
        if t.get('leisure') == 'park' and t.get('name'):
            if e['type'] == 'relation': ring = [real.XZ(g['lat'], g['lon']) for m in e['members'] if m.get('role') == 'outer' and m.get('geometry') for g in m['geometry']]
            else: ring = [real.XZ(g['lat'], g['lon']) for g in e.get('geometry', [])]
            if ring: parks.setdefault(t['name'], []).extend(ring)
    return adj, coord, kind, parks

class TreeShade:
    def __init__(s, trees):
        s.cell = 20.0; s.grid = collections.defaultdict(list)
        for t in trees:
            h, r = real.tree_size(t)
            c = (t['x'], h * 0.66, t['z'], r)
            s.grid[(int(t['x'] // s.cell), int(t['z'] // s.cell))].append(c)
    def trans(s, p, L):
        if L[1] <= 0: return 1.0
        best = 1.0
        for dt in range(0, 60, 6):
            x, z = p[0] + L[0] * dt, p[2] + L[2] * dt
            for c in s.grid.get((int(x // s.cell), int(z // s.cell)), ()):
                vx, vy, vz = c[0] - p[0], c[1] - p[1], c[2] - p[2]
                tt = vx * L[0] + vy * L[1] + vz * L[2]
                if tt <= 0: continue
                dx, dy, dz = vx - L[0] * tt, vy - L[1] * tt, vz - L[2] * tt
                if dx * dx + dy * dy + dz * dz < c[3] * c[3]: return TREE_T
        return best

def sun_at(y, mo, dd, hh, mm):
    t = datetime.datetime(y, mo, dd, hh, mm) + datetime.timedelta(hours=4)   # EDT
    az, el = LV.sun_position(t)
    return az, el, (LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0))

def edge_sun(a, b, coord, sh, ts, L, cache):
    key = (min(a, b), max(a, b))
    if key in cache: return cache[key]
    pa, pb = coord[a], coord[b]
    n = max(1, int(math.dist(pa, pb) / STEP))
    vals = []
    for i in range(n):
        t = (i + 0.5) / n
        p = (pa[0] + (pb[0] - pa[0]) * t, EYE, pa[1] + (pb[1] - pa[1]) * t)
        if L[1] <= 0: vals.append(0.0); continue
        lit = 1.0 if sh.lit(p) else 0.0
        if lit: lit *= ts.trans(p, L)
        vals.append(lit)
    cache[key] = vals
    return vals

def dijkstra(adj, src, dst, cost):
    dist = {src: 0.0}; prev = {}; pq = [(0.0, src)]
    while pq:
        dd, u = heapq.heappop(pq)
        if u == dst: break
        if dd > dist.get(u, 1e18): continue
        for v, L in adj[u].items():
            nd = dd + cost(u, v, L)
            if nd < dist.get(v, 1e18): dist[v] = nd; prev[v] = u; heapq.heappush(pq, (nd, v))
    path = [dst]
    while path[-1] != src: path.append(prev[path[-1]])
    return path[::-1]

def score(path, adj, coord, sh, ts, L, cache):
    length = 0.0; sun_m = 0.0; segs = []
    for a, b in zip(path, path[1:]):
        vals = edge_sun(a, b, coord, sh, ts, L, cache)
        pa, pb = coord[a], coord[b]
        n = len(vals)
        for i, v in enumerate(vals):
            p0 = (pa[0] + (pb[0] - pa[0]) * i / n, pa[1] + (pb[1] - pa[1]) * i / n)
            p1 = (pa[0] + (pb[0] - pa[0]) * (i + 1) / n, pa[1] + (pb[1] - pa[1]) * (i + 1) / n)
            segs.append((p0, p1, v))
        Le = adj[a][b]; length += Le; sun_m += Le * (sum(vals) / len(vals))
    return dict(length=length, sun=sun_m, shade=1 - sun_m / max(1, length), segs=segs)

def plan(moment, S=None, dest='North 5th Street Pier and Park', detour=1.35):
    adj, coord, kind, parks = load_graph()
    ring = parks[dest]
    S = S or real.load()
    az, el, L = sun_at(*moment)
    sh = real.Shadows(S['buildings'], L)
    ts = TreeShade([t for t in S['trees']])
    origin = (242.47, 178.59)          # Bedford Av L entrance, wheelchair=yes (node 8105955603)
    src = min(adj, key=lambda n: math.dist(coord[n], origin))
    cx = sum(p[0] for p in ring) / len(ring); cz = sum(p[1] for p in ring) / len(ring)
    corner = min(ring, key=lambda p: math.dist(p, origin))
    dst = min(adj, key=lambda n: math.dist(coord[n], corner))
    cache = {}
    fast = dijkstra(adj, src, dst, lambda u, v, Le: Le)
    F = score(fast, adj, coord, sh, ts, L, cache)
    best = None
    for w in (1, 2, 3, 5, 8, 12):
        p = dijkstra(adj, src, dst, lambda u, v, Le: Le * (1 + w * (sum(edge_sun(u, v, coord, sh, ts, L, cache)) / max(1, len(edge_sun(u, v, coord, sh, ts, L, cache))))))
        R = score(p, adj, coord, sh, ts, L, cache)
        if R['length'] <= F['length'] * detour and (best is None or R['shade'] > best[1]['shade']): best = (p, R, w)
    return dict(az=az, el=el, fast=F, shady=best[1], weight=best[2], src=coord[src], dst=coord[dst], fast_path=fast, shady_path=best[0])

if __name__ == '__main__':
    S = real.load()
    adj, coord, kind, parks = load_graph()
    print({k: len(v) for k, v in parks.items()})
    for dest in ['North 5th Street Pier and Park', 'Marsha P. Johnson State Park']:
      print(dest)
      for m in [(2026, 9, 30, 12, 0), (2026, 9, 30, 15, 0), (2026, 9, 30, 17, 20), (2026, 7, 15, 14, 0)]:
        R = plan(m, S, dest)
        f, s = R['fast'], R['shady']
        print(' ', m, 'sun', round(R['az']), round(R['el']), '| fast %.0f m %.0f%% shade | shady %.0f m %.0f%% shade (w=%d)' % (f['length'], 100 * f['shade'], s['length'], 100 * s['shade'], R['weight']))
