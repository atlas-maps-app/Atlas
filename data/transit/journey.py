"""Journeys on one day's real timetable: the connection scan algorithm over a GTFS day extract (gtfs_extract.py),
with every walk on the OpenStreetMap walk network (fetch_walknet.py).

    J = Journeys('gvb_20261001.json', 'walknet_west.json')
    J.plan((lat, lon), (lat, lon), depart=7*3600+30*60)                  # earliest arrival
    J.plan(a, b, depart=..., walk_weight=2.0)                            # in the rain: a minute walking counts as two
    J.latest(a, b, arrive_by=8*3600+15*60)                               # leave as late as possible, still on time

Walks at 4.8 km/h (80 m a minute); a change of vehicle at the same stop costs one minute."""
import json, math, heapq, collections, bisect

WALK_MPM = 80.0
WALKABLE = {'footway', 'pedestrian', 'residential', 'living_street', 'path', 'steps', 'tertiary', 'secondary', 'primary',
            'unclassified', 'service', 'cycleway', 'tertiary_link', 'secondary_link', 'primary_link', 'track'}

def proj(lat, lon, lat0=52.37):
    return (lon * 111320.0 * math.cos(math.radians(lat0)), lat * 111320.0)

class WalkNet:
    def __init__(s, path):
        s.adj = collections.defaultdict(dict); s.xy = {}; s.name = {}
        for e in json.load(open(path))['elements']:
            t = e.get('tags', {})
            if e['type'] != 'way' or t.get('highway') not in WALKABLE: continue
            if t.get('foot') in ('no', 'private') or t.get('access') in ('no', 'private'): continue
            if t.get('highway') == 'cycleway' and t.get('foot') not in ('yes', 'designated'): continue
            g = e['geometry']
            ns = e.get('nodes') or [(round(q['lat'], 7), round(q['lon'], 7)) for q in g]      # 'out geom tags' carries no node ids: key by position
            for i, n in enumerate(ns): s.xy[n] = proj(g[i]['lat'], g[i]['lon'])
            for a, b in zip(ns, ns[1:]):
                L = math.dist(s.xy[a], s.xy[b]); s.adj[a][b] = s.adj[b][a] = L
                s.name[(a, b)] = s.name[(b, a)] = t.get('name')
        s.grid = collections.defaultdict(list)
        for n, (x, y) in s.xy.items():
            if s.adj[n]: s.grid[(int(x // 50), int(y // 50))].append(n)
    def nearest(s, lat, lon):
        x, y = proj(lat, lon); best = None
        for r in range(1, 6):
            for i in range(-r, r + 1):
                for j in range(-r, r + 1):
                    for n in s.grid.get((int(x // 50) + i, int(y // 50) + j), ()):
                        d = math.dist(s.xy[n], (x, y))
                        if best is None or d < best[0]: best = (d, n)
            if best: return best
        return best
    def dijkstra(s, src, limit):
        dist = {src: 0.0}; prev = {}; pq = [(0.0, src)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist[u] or d > limit: continue
            for v, L in s.adj[u].items():
                if d + L < dist.get(v, 1e18): dist[v] = d + L; prev[v] = u; heapq.heappush(pq, (d + L, v))
        return dist, prev
    def path(s, prev, src, dst):
        out = [dst]
        while out[-1] != src: out.append(prev[out[-1]])
        return out[::-1]

class Journeys:
    def __init__(s, day_json, walknet_json, transfer_m=450):
        D = json.load(open(day_json)); s.day = D['day']; s.S = D['stops']; s.trips = {t['id']: t for t in D['trips']}
        s.W = WalkNet(walknet_json)
        s.snode = {}
        for sid, st in s.S.items():
            nb = s.W.nearest(st['lat'], st['lon'])
            if nb and nb[0] < 80: s.snode[sid] = (nb[1], nb[0])
        s.conn = []          # (dep, arr, from_stop, to_stop, trip_id, idx)
        for t in D['trips']:
            st = t['st']
            for i in range(len(st) - 1):
                s.conn.append((st[i][2], st[i + 1][1], st[i][0], st[i + 1][0], t['id'], i))
        s.conn.sort()
        s.deps = [c[0] for c in s.conn]
        # footpaths between stops, on the walk network
        bynode = collections.defaultdict(list)
        for sid, (n, off) in s.snode.items(): bynode[n].append((sid, off))
        s.foot = collections.defaultdict(list)
        for sid, (n, off) in s.snode.items():
            dist, _ = s.W.dijkstra(n, transfer_m)
            for m, d in dist.items():
                for sid2, off2 in bynode.get(m, ()):
                    if sid2 != sid: s.foot[sid].append((sid2, d + off + off2))
    def _access(s, lat, lon, limit):
        nb = s.W.nearest(lat, lon); dist, prev = s.W.dijkstra(nb[1], limit)
        out = {}
        for sid, (n, off) in s.snode.items():
            if n in dist: out[sid] = dist[n] + off + nb[0]
        return out, nb[1], prev
    def plan(s, a, b, depart, walk_weight=1.0, limit=1600, egress=None):
        """Earliest arrival from a to b leaving at `depart` (seconds of the service day). `egress` caps the last walk in
        metres (in the rain: fewer metres on foot at the end, even if it takes longer)."""
        acc, an, aprev = s._access(a[0], a[1], limit)
        egr, bn, bprev = s._access(b[0], b[1], egress or limit)
        wk = lambda m: m / WALK_MPM * 60.0 * walk_weight
        T = collections.defaultdict(lambda: 1e18); J = {}; trip_in = {}
        for sid, d in acc.items():
            T[sid] = depart + wk(d); J[sid] = ('walk', None, d)
            for sid2, d2 in s.foot[sid]:
                pass
        i0 = bisect.bisect_left(s.deps, depart)
        for c in s.conn[i0:]:
            dep, arr, u, v, tid, idx = c
            if dep > depart + 4 * 3600: break
            reach = T[u] + (60 if J.get(u, ('',))[0] == 'ride' else 0) <= dep
            if tid in trip_in or reach:
                if tid not in trip_in or (reach and J.get(u, ('',))[0] == 'walk'): trip_in[tid] = (u, dep, idx)     # board as late as the walk from home allows
                if arr < T[v]:
                    T[v] = arr; J[v] = ('ride', tid, trip_in[tid])
                    for w, d in s.foot[v]:
                        t2 = arr + wk(d)
                        if t2 < T[w]: T[w] = t2; J[w] = ('foot', v, d)
        best = None
        for sid, d in egr.items():
            if T[sid] < 1e17:
                t = T[sid] + wk(d)
                if best is None or t < best[0]: best = (t, sid, d)
        # walking all the way
        direct = None
        nb = s.W.nearest(a[0], a[1]); dist, _ = s.W.dijkstra(nb[1], 6000)
        if bn in dist: direct = dist[bn]
        legs = []
        if best:
            sid = best[1]; legs.append(dict(kind='walk', frm=sid, to='dest', m=round(best[2])))
            while True:
                j = J[sid]
                if j[0] == 'walk': legs.append(dict(kind='walk', frm='origin', to=sid, m=round(j[2]))); break
                if j[0] == 'foot': legs.append(dict(kind='walk', frm=j[1], to=sid, m=round(j[2]))); sid = j[1]; continue
                tid = j[1]; u, dep, idx = j[2]; t = s.trips[tid]
                k = next(i for i in range(idx + 1, len(t['st'])) if t['st'][i][0] == sid)
                legs.append(dict(kind='ride', trip=tid, route=t['route'], type=t['type'], head=t['head'], frm=u, to=sid, dep=dep,
                                 arr=t['st'][k][1], stops=[x[0] for x in t['st'][idx:k + 1]]))
                sid = u
            legs.reverse()
        # real clock times: walks at true pace (the weight only steered the choice)
        clock = depart; out = []
        for L in legs:
            if L['kind'] == 'walk':
                L['start'] = clock; clock += L['m'] / WALK_MPM * 60.0; L['end'] = clock
            else:
                L['wait'] = L['dep'] - clock; clock = L['arr']; L['start'], L['end'] = L['dep'], L['arr']
            out.append(L)
        if out and out[0]['kind'] == 'walk' and len(out) > 1 and out[1]['kind'] == 'ride':
            # leave home just in time for the first vehicle
            slack = out[1]['dep'] - out[0]['end']
            out[0]['start'] += slack; out[0]['end'] += slack
        return dict(legs=out, arrive=clock if out else None, leave=out[0]['start'] if out else None, direct_m=direct)
    def latest(s, a, b, arrive_by, walk_weight=1.0, step=60, span=5400, egress=None):
        """The latest departure that still arrives by arrive_by: scan departures backwards a minute at a time."""
        best = None
        for dep in range(arrive_by - 600, arrive_by - span, -step):
            j = s.plan(a, b, dep, walk_weight, egress=egress)
            if j['legs'] and j['arrive'] <= arrive_by:
                if best is None or j['leave'] > best['leave']: best = j
                if dep < best['leave'] - 1800: break
        return best
    def stop_name(s, sid):
        return s.S[sid]['name'].split(', ')[-1] if sid in s.S else sid

def hm(t):
    t = int(round(t)); return '%02d:%02d' % (t // 3600 % 24, t // 60 % 60)
