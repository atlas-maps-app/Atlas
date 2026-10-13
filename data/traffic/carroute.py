"""A small car router on OpenStreetMap's main roads (traffic/roads_wide.json), to draw a signed route on real roads:
from its start to its end through the road the sign names ("via A10west" goes by Ringweg-West). Time at the mapped
maxspeed, else a default per road class; one-way streets respected.
    R = CarNet(); line = R.route((lat, lon), (lat, lon), via='A10west')     # [(lat, lon), ...], metres"""
import json, os, math, heapq, collections
HERE = os.path.dirname(os.path.abspath(__file__))
DEF = {'motorway': 100, 'motorway_link': 60, 'trunk': 70, 'trunk_link': 50, 'primary': 50, 'primary_link': 40, 'secondary': 50, 'secondary_link': 40}
VIA = {'A10west': 'Ringweg-West', 'A10oost': 'Ringweg-Oost', 'A10noord': 'Ringweg-Noord', 'A10zuid': 'Ringweg-Zuid', 'IJtunnel': 'IJ-tunnel',
       'Spaarndammertunnel': 'Spaarndammertunnel', 'Haarlemmerweg': 'Haarlemmerweg', 'Gooiseweg': 'Gooiseweg', 'Wibautstraat': 'Wibautstraat'}
def proj(lat, lon): return (lon * 111320.0 * 0.6105, lat * 111320.0)
class CarNet:
    def __init__(s, path=os.path.join(HERE, 'roads_wide.json')):
        s.adj = collections.defaultdict(dict); s.xy = {}; s.names = collections.defaultdict(list)
        for e in json.load(open(path))['elements']:
            t = e['tags']; hw = t.get('highway')
            if hw not in DEF: continue
            g = e['geometry']; ns = [(round(q['lat'], 7), round(q['lon'], 7)) for q in g]
            try: v = float(str(t.get('maxspeed', DEF[hw])).split()[0])
            except ValueError: v = DEF[hw]
            ow = t.get('oneway'); fwd = True; back = not (ow in ('yes', 'true', '1') or hw in ('motorway', 'motorway_link') and ow != 'no')
            if ow == '-1': fwd, back = False, True
            for n in ns: s.xy[n] = proj(*n)
            for a, b in zip(ns, ns[1:]):
                secs = math.dist(s.xy[a], s.xy[b]) / (v / 3.6)
                if fwd: s.adj[a][b] = secs
                if back: s.adj[b][a] = secs
            if t.get('name'): s.names[t['name']] += ns
    def near(s, lat, lon):
        p = proj(lat, lon); return min((n for n in s.adj), key=lambda n: math.dist(s.xy[n], p))
    def _dij(s, a, b):
        dist = {a: 0.0}; prev = {}; pq = [(0.0, a)]
        while pq:
            d, u = heapq.heappop(pq)
            if u == b: break
            if d > dist[u]: continue
            for v, c in s.adj[u].items():
                if d + c < dist.get(v, 1e18): dist[v] = d + c; prev[v] = u; heapq.heappush(pq, (d + c, v))
        if b not in dist: return None, None
        out = [b]
        while out[-1] != a: out.append(prev[out[-1]])
        return out[::-1], dist[b]
    def _all(s, src, adj):
        dist = {src: 0.0}; prev = {}; pq = [(0.0, src)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist[u]: continue
            for v, c in adj[u].items():
                if d + c < dist.get(v, 1e18): dist[v] = d + c; prev[v] = u; heapq.heappush(pq, (d + c, v))
        return dist, prev
    def route(s, a, b, via=None):
        na, nb = s.near(*a), s.near(*b)
        if via and VIA.get(via) in s.names:
            # the quickest way that uses the named road: best over its nodes of (start to node) + (node to end)
            if not hasattr(s, 'radj'):
                s.radj = collections.defaultdict(dict)
                for u, vs in s.adj.items():
                    for v, c in vs.items(): s.radj[v][u] = c
            df, pf = s._all(na, s.adj); db, pb = s._all(nb, s.radj)
            cand = [n for n in set(s.names[VIA[via]]) if n in df and n in db]
            mid = min(cand, key=lambda n: df[n] + db[n])
            p1 = [mid]
            while p1[-1] != na: p1.append(pf[p1[-1]])
            p2 = [mid]
            while p2[-1] != nb: p2.append(pb[p2[-1]])
            path, secs = p1[::-1] + p2[1:], df[mid] + db[mid]
        else: path, secs = s._dij(na, nb)
        if not path: return None
        m = sum(math.dist(s.xy[u], s.xy[v]) for u, v in zip(path, path[1:]))
        return dict(pts=[list(p) for p in path], m=round(m), free_s=round(secs))
