#!/usr/bin/env python3
"""Coaster tracks standing up, from OpenStreetMap (roller_coaster=track).

OSM maps a track's line, its layer, and which pieces are the lift hill, the station and the brake run. It does not map how high
the track runs. The heights drawn here are generic and marked guessed: each piece stands at a share of the ride's own top height
(from the ride's published profile where there is one) by its layer, the lift hill climbs between its neighbours, station and brake
runs sit low, and the pieces meet where they share a node. Pieces in a tunnel, below ground or inside a building are not drawn
(Vogel Rok runs indoors). A wooden coaster stands on a lattice of timber posts and braces; a steel coaster on slim posts.
The elevated monorail (railway=monorail) is drawn the same way, as a single beam at a generic height."""
import math
from real import pip

LAYER_SHARE = {0: None, 1: 0.45, 2: 0.75, 3: 1.0}
LOW = 1.6                    # a track at layer 0: just off the ground
STATION = 1.2

def smooth(t): t = max(0.0, min(1.0, t)); return t * t * (3 - 2 * t)

def load(raw, XZ, buildings, heights, kinds):
    """raw: OSM elements. heights: ride name -> top height in metres (published). kinds: ride name -> 'wood' or 'steel'.
    Returns tracks [dict(pts, ys, kind, ride, guessed=True)]."""
    rides = []
    for e in raw:
        t = e.get('tags') or {}
        if t.get('attraction') != 'roller_coaster' or not t.get('name'): continue
        if e['type'] == 'node': rides.append((t['name'], XZ(e['lat'], e['lon'])))
        elif e.get('geometry'):
            g = [p for p in e['geometry'] if p]
            rides.append((t['name'], XZ(sum(p['lat'] for p in g) / len(g), sum(p['lon'] for p in g) / len(g))))
    bpolys = []
    for b in buildings:
        xs = [p[0] for p in b['poly']]; zs = [p[1] for p in b['poly']]
        bpolys.append(((min(xs), max(xs), min(zs), max(zs)), b['poly']))
    def indoors(x, z):
        return any(bb[0] <= x <= bb[1] and bb[2] <= z <= bb[3] and pip(x, z, poly) for bb, poly in bpolys)
    ways = []
    for e in raw:
        t = e.get('tags') or {}
        mono = t.get('railway') == 'monorail'
        if not (t.get('roller_coaster') == 'track' or mono) or e['type'] != 'way' or not e.get('geometry'): continue
        lay = int(float(t.get('layer', '0') or 0))
        if t.get('tunnel') in ('yes', 'building_passage') or lay < 0 or t.get('indoor') == 'yes': continue
        geo = [p for p in e['geometry'] if p]
        pts = [XZ(p['lat'], p['lon']) for p in geo]
        keys = [(round(p['lat'], 7), round(p['lon'], 7)) for p in geo]
        mid = pts[len(pts) // 2]
        if indoors(*mid) and indoors(*pts[0]) and indoors(*pts[-1]): continue
        if mono:
            ways.append(dict(pts=pts, keys=keys, kind='mono', ride=t.get('name') or 'Monorail', h=3.5, role='mono')); continue
        cx, cz = sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)
        ride = min(rides, key=lambda r: math.dist(r[1], (cx, cz)))[0] if rides else None
        if t.get('name') in ('Max', 'Moritz'): ride = 'Max & Moritz'
        H = heights.get(ride, 12.0)
        role = t.get('roller_coaster:track') or 'track'
        if role in ('station', 'brake_run'): h = STATION
        elif role == 'lift_hill': h = None
        else: h = LOW if not LAYER_SHARE.get(lay) else max(LOW, H * LAYER_SHARE[lay])
        ways.append(dict(pts=pts, keys=keys, kind=kinds.get(ride, 'steel'), ride=ride, h=h, role=role, H=H))
    # the height where pieces meet: the mean of the pieces meeting at that node
    at = {}
    for w in ways:
        if w['h'] is None: continue
        for k in (w['keys'][0], w['keys'][-1]): at.setdefault(k, []).append(w['h'])
    node_h = {k: sum(v) / len(v) for k, v in at.items()}
    out = []
    for w in ways:
        pts = w['pts']; s = [0.0]
        for i in range(1, len(pts)): s.append(s[-1] + math.dist(pts[i - 1], pts[i]))
        Lw = s[-1] or 1.0
        h0 = node_h.get(w['keys'][0]); h1 = node_h.get(w['keys'][-1])
        if w['h'] is None:          # the lift hill: from the low end up to the ride's top
            lo, hi = sorted([h0 if h0 is not None else LOW, h1 if h1 is not None else w['H']])
            hi = max(hi, w['H'] * 0.95)
            up = (h1 or 0) >= (h0 or 0)
            ys = [lo + (hi - lo) * (si / Lw if up else 1 - si / Lw) for si in s]
        else:
            hw = w['h']; h0 = hw if h0 is None else h0; h1 = hw if h1 is None else h1
            ramp = min(18.0, Lw / 2)
            ys = [hw + (h0 - hw) * (1 - smooth(si / ramp)) + (h1 - hw) * (1 - smooth((Lw - si) / ramp)) for si in s]
        out.append(dict(pts=pts, ys=ys, kind=w['kind'], ride=w['ride'], role=w['role'], guessed=True))
    return out

def subdivide(pts, ys, step):
    P, Y = [pts[0]], [ys[0]]
    for i in range(1, len(pts)):
        a, b = pts[i - 1], pts[i]; n = max(1, int(math.dist(a, b) / step))
        for k in range(1, n + 1):
            f = k / n
            P.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)); Y.append(ys[i - 1] + (ys[i] - ys[i - 1]) * f)
    return P, Y

COL = {'steel': ('#E6E3DC', '#5F656D'), 'wood': ('#B9925E', '#8A6640'), 'mono': ('#D6D2C8', '#8C877C')}
NIGHT = {'steel': ('#2A2E36', '#1A1D22'), 'wood': ('#2E261C', '#1E1912'), 'mono': ('#2A2A2A', '#1A1A1A')}

def draw(tracks, cam, th, far, L, light, hx, tohex):
    """Items (depth, 'svg', body) for the tracks and their posts, and the shadow paths for the ground."""
    night = th['night']; items = []; shadow = []
    sun = (not night) and L[1] > 0.05
    off = (lambda y: (-L[0] / L[1] * y, -L[2] / L[1] * y)) if sun else None
    for tr in tracks:
        if all(far(*p) for p in tr['pts'][::4] + [tr['pts'][-1]]): continue
        P, Y = subdivide(tr['pts'], tr['ys'], 3.0)
        top, side = (NIGHT if night else COL)[tr['kind']]
        lit = tohex(light(th, hx(top), (0, 1, 0), not night))
        dark = tohex(light(th, hx(side), (0, 0, 1), False))
        gap = 2.6 if tr['kind'] == 'wood' else 7.5
        acc = 0.0; posts = []
        for i in range(1, len(P)):
            a, b = P[i - 1], P[i]; ya, yb = Y[i - 1], Y[i]
            m = ((a[0] + b[0]) / 2, (ya + yb) / 2, (a[1] + b[1]) / 2)
            c = cam.cam(m)
            if c[2] < 3: continue
            s = cam.focal / c[2]
            pa, pb = cam.proj((a[0], ya, a[1])), cam.proj((b[0], yb, b[1]))
            if not (pa and pb): continue
            if not any(-40 < q[0] < cam.W + 40 and -40 < q[1] < cam.H + 40 for q in (pa, pb)): continue          # outside the picture
            g = []
            acc += math.dist(a, b)
            if acc >= gap and yb > 2.2:
                acc = 0.0
                g0 = cam.proj((b[0], 0, b[1]))
                if g0:
                    g.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f"/>' % (g0[0], g0[1], pb[0], pb[1], dark, max(0.5, (0.5 if tr['kind'] == 'wood' else 0.7) * s)))
                    if tr['kind'] == 'wood' and posts:
                        (q0, q1) = posts[-1]
                        g.append('<path d="M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" opacity=".85"/>' % (q0[0], q0[1], pb[0], pb[1], q1[0], q1[1], g0[0], g0[1], dark, max(0.25, 0.3 * s)))
                    posts.append((g0, pb))
                    if sun:          # the post's shadow, from its foot along the ground
                        o = off(yb); q0, q1 = cam.proj((b[0], 0.12, b[1])), cam.proj((b[0] + o[0], 0.12, b[1] + o[1]))
                        if q0 and q1: shadow.append(('M%.1f %.1fL%.1f %.1f' % (q0 + q1), s * 0.5))
            # the track: a dark underside, the lit top over it
            w = max(1.1, 1.7 * s)
            g.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (pa[0], pa[1] + 0.35 * s, pb[0], pb[1] + 0.35 * s, dark, w))
            g.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" stroke-linecap="round"/>' % (pa[0], pa[1], pb[0], pb[1], lit, w * 0.72))
            items.append((c[2], 'svg', ''.join(g)))
        if sun:
            sp = []
            for (x, z), y in zip(P, Y):
                o = off(y); q = cam.proj((x + o[0], 0.12, z + o[1]))
                sp.append(q)
            run = []
            for q in sp + [None]:
                if q: run.append(q); continue
                if len(run) > 1: shadow.append(('M' + 'L'.join('%.1f %.1f' % r for r in run), 1.1 * cam.focal / max(1, cam.cam((P[len(P) // 2][0], 0, P[len(P) // 2][1]))[2])))
                run = []
    return items, shadow
