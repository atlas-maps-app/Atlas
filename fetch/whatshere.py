#!/usr/bin/env python3
"""What's here, for one building on the living map: its BAG record (address, use, floor area, year) through PDOK's
OGC API, and the ground in front from AHN (0.5 m bare earth, PDOK WCS), where 'in front' is 3 m out from the wall
the living map's camera sees. Also the screen point on that wall at 55% of the height.
python3 fetch/whatshere.py <way id> <name>  ->  data/ams/whatshere/<name>.json"""
import json, math, os, sys, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(HERE, 'render'))
from fetch_dem import rd, tiff_floats
import amsterdam as A
from city import Camera
UA = {'User-Agent': 'AtlasConcept/1.0 (design prototype; one-off lookups)'}
def get(u): return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=40))
WID, NAME = int(sys.argv[1]), sys.argv[2]
S = A.load(); b = next(x for x in S['buildings'] if x.get('id') == WID); t = b['tags']
cfg = json.load(open(os.path.join(HERE, '..', 'cache', 'render', 'ams_cfg.json')))
cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
poly = b['poly']; best = None
for i in range(len(poly)):
    a, c = poly[i], poly[(i + 1) % len(poly)]; m = ((a[0] + c[0]) / 2, (a[1] + c[1]) / 2)
    if math.dist(a, c) < 4: continue
    d = cam.depth((m[0], b['h'] * 0.55, m[1]))
    if best is None or d < best[0]: best = (d, m)
m = best[1]; cx = sum(q[0] for q in poly) / len(poly); cz = sum(q[1] for q in poly) / len(poly)
v = (m[0] - cx, m[1] - cz); n = math.hypot(*v) or 1; v = (v[0] / n, v[1] / n)
wall = cam.proj((m[0] + v[0] * 0.4, b['h'] * 0.55, m[1] + v[1] * 0.4))
# the street in front: the nearest point of a mapped road or footway to that wall; the ground there from AHN
pand = get('https://api.pdok.nl/kadaster/bag/ogc/v2/collections/pand/items?f=json&identificatie=%s' % t['ref:bag'])['features'][0]['properties']
vbos = [get(h + '?f=json')['properties'] for h in pand.get('verblijfsobject.href', [])]
street = vbos[0]['openbare_ruimte_naam'] if vbos else None          # the street of its address, else any
def near_seg(p, a, c):
    vx, vz = c[0] - a[0], c[1] - a[1]; L2 = vx * vx + vz * vz or 1e-9
    k = max(0, min(1, ((p[0] - a[0]) * vx + (p[1] - a[1]) * vz) / L2)); return (a[0] + vx * k, a[1] + vz * k)
best_r = None
for r in S['roads']:
    if r.get('bridge') or (street and r['tags'].get('name') != street): continue
    for a, c in zip(r['pts'], r['pts'][1:]):
        q = near_seg((cx, cz), a, c); dd = math.dist(q, (cx, cz))
        if best_r is None or dd < best_r[0]: best_r = (dd, q, r['tags'].get('name'))
fx, fz = best_r[1]
flat, flon = A.LAT0 - fz / A.KZ, A.LON0 + fx / A.KX
x, y = rd(flat, flon)
g = tiff_floats(urllib.request.urlopen(urllib.request.Request(
    'https://service.pdok.nl/rws/ahn/wcs/v1_0?SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage&COVERAGEID=dtm_05m&FORMAT=image/tiff&SUBSET=x(%.1f,%.1f)&SUBSET=y(%.1f,%.1f)' % (x - 2, x + 2, y - 2, y + 2), headers=UA), timeout=60).read())[2]
vals = [q for row in g for q in row if q is not None and abs(q) < 100]
out = dict(way=WID, wall=[round(wall[0], 1), round(wall[1], 1)], front=[round(flat, 7), round(flon, 7)], street=best_r[2], street_m=round(best_r[0], 1),
           ahn_nap=round(sum(vals) / len(vals), 2) if vals else None, ahn_cells=len(vals),
           bag=dict(pand=pand['identificatie'], bouwjaar=pand['bouwjaar'], status=pand['status'], use=pand['gebruiksdoel'],
                    units=[dict(address='%s %s%s%s' % (u['openbare_ruimte_naam'], u['huisnummer'], u.get('huisletter') or '', ('-' + u['toevoeging']) if u.get('toevoeging') else ''),
                                postcode=u['postcode'], city=u['woonplaats_naam'], use=u['gebruiksdoel'], area=u['oppervlakte'], vbo=u['identificatie']) for u in vbos]),
           fetched='2026-10-06')
os.makedirs(os.path.join(HERE, '..', 'data', 'ams', 'whatshere'), exist_ok=True)
json.dump(out, open(os.path.join(HERE, '..', 'data', 'ams', 'whatshere', NAME + '.json'), 'w'), ensure_ascii=False, indent=1)
print(json.dumps(out, ensure_ascii=False))
