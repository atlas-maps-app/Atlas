"""The journeys of the one real day, Thursday 1 October 2026, on that day's GVB timetable (OVapi archive NL-20261001),
with the week before (NL-20260923, Thursday 24 September) to show what changed when tram 13 left the Marnixstraat on
Wednesday 30 September. Home is in De Baarsjes by the Admiraal Helfrichstraat stop; work is on the Westermarkt.
Every way is drawn on the real rails (OSM railway=tram) and streets (OSM, walk network).
Writes transit/day_journeys.json."""
import json, os, sys, math, collections, heapq
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from journey import Journeys, WalkNet, hm, proj, WALK_MPM

HOME = (52.37005, 4.84595)          # De Baarsjes, a street by the Admiraal Helfrichstraat stop
WORK = (52.37465, 4.88395)          # the Westermarkt
ARRIVE_BY = 8 * 3600
J1 = Journeys(os.path.join(HERE, 'gvb_20261001.json'), os.path.join(HERE, 'walknet_west.json'))
J0 = Journeys(os.path.join(HERE, 'gvb_20260924.json'), os.path.join(HERE, 'walknet_west.json'))
W = J1.W
RAW = {}
for e in json.load(open(os.path.join(HERE, 'walknet_west.json')))['elements']:
    for a, b in zip(e['geometry'], e['geometry'][1:]):
        k = ((round(a['lat'], 7), round(a['lon'], 7)), (round(b['lat'], 7), round(b['lon'], 7)))
        RAW[k] = RAW[k[::-1]] = e.get('tags', {})

# ---------------------------------------------------------------- the rails
TR = collections.defaultdict(dict); TXY = {}
for e in json.load(open(os.path.join(HERE, 'tram_west.json')))['elements']:
    g = e['geometry']; ns = [(round(q['lat'], 7), round(q['lon'], 7)) for q in g]
    for n in ns: TXY[n] = proj(*n)
    for a, b in zip(ns, ns[1:]):
        d = math.dist(TXY[a], TXY[b]); TR[a][b] = TR[b][a] = d
def tnear(lat, lon):
    p = proj(lat, lon); return min(TXY, key=lambda n: math.dist(TXY[n], p))
def tpath(a, b):
    dist = {a: 0}; prev = {}; pq = [(0, a)]
    while pq:
        d, u = heapq.heappop(pq)
        if u == b: break
        if d > dist[u]: continue
        for v, L in TR[u].items():
            if d + L < dist.get(v, 1e18): dist[v] = d + L; prev[v] = u; heapq.heappush(pq, (d + L, v))
    if b not in dist: return [a, b]
    out = [b]
    while out[-1] != a: out.append(prev[out[-1]])
    return out[::-1]
def ride_line(J, stops):
    pts = []
    for s0, s1 in zip(stops, stops[1:]):
        a = J.S[s0]; b = J.S[s1]
        seg = tpath(tnear(a['lat'], a['lon']), tnear(b['lat'], b['lon']))
        pts += seg if not pts else seg[1:]
    return [list(p) for p in pts]
# ---------------------------------------------------------------- the streets
def walk_line(a, b):
    """a, b: (lat, lon); the walked path on the network, with its lit share"""
    na = W.nearest(*a)[1]; nb = W.nearest(*b)[1]
    dist, prev = W.dijkstra(na, 8000)
    if nb not in dist: return dict(pts=[list(a), list(b)], m=round(math.dist(proj(*a), proj(*b))), lit=None, names=[])
    path = W.path(prev, na, nb)
    lit = tot = 0; names = []
    for u, v in zip(path, path[1:]):
        L = W.adj[u][v]; t = RAW.get((u, v), {}); tot += L
        if t.get('lit') == 'yes': lit += L
        nm = t.get('name')
        if nm and (not names or names[-1][0] != nm): names.append([nm, 0])
        if nm: names[-1][1] += L
    return dict(pts=[list(a)] + [list(p) for p in path] + [list(b)], m=round(dist[nb]), lit=round(100 * lit / tot) if tot else None,
                names=[[n, round(m)] for n, m in names if m > 60])
def stop_ll(J, sid): s = J.S[sid]; return (s['lat'], s['lon'])
def journey_out(J, j, a, b):
    legs = []
    for L in j['legs']:
        if L['kind'] == 'walk':
            pa = a if L['frm'] == 'origin' else stop_ll(J, L['frm']); pb = b if L['to'] == 'dest' else stop_ll(J, L['to'])
            w = walk_line(pa, pb)
            legs.append(dict(kind='walk', m=L['m'], start=hm(L['start']), end=hm(L['end']), min=max(1, round(L['m'] / WALK_MPM)), pts=w['pts'], lit=w['lit'], names=w['names'],
                             frm=J.stop_name(L['frm']) if L['frm'] != 'origin' else None, to=J.stop_name(L['to']) if L['to'] != 'dest' else None))
        else:
            legs.append(dict(kind='ride', route=L['route'], type=L['type'], head=L['head'], dep=hm(L['dep']), arr=hm(L['arr']), frm=J.stop_name(L['frm']), to=J.stop_name(L['to']),
                             n=len(L['stops']) - 1, min=round((L['arr'] - L['dep']) / 60), stops=[J.stop_name(x) for x in L['stops']], pts=ride_line(J, L['stops'])))
    # two walks in a row (a change of stop, then on to the door) read as one
    merged = []
    for L in legs:
        if merged and L['kind'] == 'walk' and merged[-1]['kind'] == 'walk':
            M = merged[-1]; M['m'] += L['m']; M['end'] = L['end']; M['min'] = max(1, round(M['m'] / WALK_MPM)); M['pts'] += L['pts']; M['names'] += L['names']; M['to'] = L['to']
        else: merged.append(L)
    return dict(leave=hm(j['leave']), arrive=hm(j['arrive']), legs=merged, walk_m=sum(L['m'] for L in merged if L['kind'] == 'walk'))

OUT = dict(home=HOME, work=WORK, arrive_by=hm(ARRIVE_BY))
# the morning, Thursday 1 October, and the Thursday before
OUT['morning'] = journey_out(J1, J1.latest(HOME, WORK, ARRIVE_BY), HOME, WORK)
OUT['week_before'] = journey_out(J0, J0.latest(HOME, WORK, ARRIVE_BY), HOME, WORK)
direct = walk_line(HOME, WORK)
OUT['walk_all'] = dict(m=direct['m'], min=round(direct['m'] / WALK_MPM), lit=direct['lit'], names=direct['names'], pts=direct['pts'])
BIKE_KMH = 15.0
bike_min = math.ceil(direct['m'] / (BIKE_KMH * 1000 / 60) + 1)          # on the same streets, plus a minute to unlock and lock
OUT['bike'] = dict(m=direct['m'], min=bike_min, leave=hm(ARRIVE_BY - bike_min * 60 - 60), kmh=BIKE_KMH)
# 07:40, on the 13 at Bilderdijkstraat: what now
m = OUT['morning']; ride = next(L for L in m['legs'] if L['kind'] == 'ride')
r13 = next(L for L in J1.latest(HOME, WORK, ARRIVE_BY)['legs'] if L['kind'] == 'ride')
BD = (J1.S[r13['to']]['lat'], J1.S[r13['to']]['lon'])          # the 13's own Bilderdijkstraat stop, where you are at 07:40
t_bd = r13['arr']
opts = []
j = J1.plan(BD, WORK, t_bd)
opts.append(dict(journey_out(J1, j, BD, WORK), how='getoff'))
# staying on the same tram: where it goes next, from the timetable
trip = J1.trips[r13['trip']]; names = [J1.stop_name(x[0]) for x in trip['st']]
k = names.index('Elandsgracht', names.index('Bilderdijkstraat'))
el_sid, el_arr = trip['st'][k][0], trip['st'][k][1]
EL = (J1.S[el_sid]['lat'], J1.S[el_sid]['lon'])
stay_ride = dict(kind='ride', route='13', type=0, head=trip['head'], dep=hm(t_bd), arr=hm(el_arr), frm='Bilderdijkstraat', to='Elandsgracht',
                 n=k - names.index('Bilderdijkstraat'), min=round((el_arr - t_bd) / 60), stops=names[names.index('Bilderdijkstraat'):k + 1],
                 pts=ride_line(J1, [x[0] for x in trip['st'][names.index('Bilderdijkstraat'):k + 1]]))
j = J1.plan(EL, WORK, el_arr)
o = journey_out(J1, j, EL, WORK); o['legs'] = [stay_ride] + o['legs']; o['leave'] = hm(t_bd); o['how'] = 'stay_change'
o['walk_m'] = sum(L['m'] for L in o['legs'] if L['kind'] == 'walk')
if any(L['kind'] == 'ride' and L['route'] != '13' for L in o['legs']): opts.append(o)
w = walk_line(EL, WORK)
arr = el_arr + w['m'] / WALK_MPM * 60
opts.append(dict(leave=hm(t_bd), arrive=hm(arr), how='stay_walk', walk_m=w['m'], legs=[stay_ride, dict(kind='walk', m=w['m'], start=hm(el_arr), end=hm(arr), min=max(1, round(w['m'] / WALK_MPM)),
                 pts=w['pts'], lit=w['lit'], names=w['names'], frm='Elandsgracht', to=None)]))
OUT['onboard'] = dict(at=hm(t_bd), stop='Bilderdijkstraat', ll=BD, options=opts)
# the old line and the new, for the map
def line_of(J, route, head_not, frm, to):
    for t in J.trips.values():
        if t['route'] != route or t['head'] == head_not: continue
        names = [J.stop_name(s[0]) for s in t['st']]
        if frm in names and to in names and names.index(frm) < names.index(to):
            i, k = names.index(frm), names.index(to)
            return dict(head=t['head'], stops=names[i:k + 1], pts=ride_line(J, [s[0] for s in t['st'][i:k + 1]]),
                        ll=[[J.S[s[0]]['lat'], J.S[s[0]]['lon']] for s in t['st'][i:k + 1]])
OUT['line13_before'] = line_of(J0, '13', 'Geuzenveld', 'Admiraal Helfrichstraat', 'Zoutkeetsgracht')
OUT['line13_now'] = line_of(J1, '13', 'Geuzenveld', 'Admiraal Helfrichstraat', 'Leidseplein')
# the night: the last way home from the Westermarkt
night = []; seen = set()
for t in range(23 * 3600 + 40 * 60, 26 * 3600, 5 * 60):
    j = J1.plan(WORK, HOME, t)
    if not j['legs']: continue
    k = tuple((L.get('route'), L.get('dep')) for L in j['legs'] if L['kind'] == 'ride')
    if k in seen: continue
    seen.add(k); night.append(journey_out(J1, j, WORK, HOME))
OUT['night'] = [n for n in night if any(L['kind'] == 'ride' for L in n['legs'])]
back = walk_line(WORK, HOME)
OUT['walk_home'] = dict(m=back['m'], min=round(back['m'] / WALK_MPM), lit=back['lit'], names=back['names'], pts=back['pts'])
json.dump(OUT, open(os.path.join(HERE, 'day_journeys.json'), 'w'))
def say(o): return '%s→%s walk %dm: ' % (o['leave'], o['arrive'], o['walk_m']) + ' | '.join(('walk %dm' % L['m']) if L['kind'] == 'walk' else '%s %s %s→%s' % (L['route'], L['dep'], L['frm'], L['to']) for L in o['legs'])
print('morning', say(OUT['morning'])); print('week before', say(OUT['week_before'])); print('bike', OUT['bike'], 'walk', {k: v for k, v in OUT['walk_all'].items() if k != 'pts'})
for o in opts: print('onboard', say(o))
print('13 before', OUT['line13_before']['stops']); print('13 now', OUT['line13_now']['stops'])
for o in OUT['night']: print('night', say(o))
print('walk home', {k: v for k, v in OUT['walk_home'].items() if k != 'pts'})
