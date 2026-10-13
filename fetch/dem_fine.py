#!/usr/bin/env python3
"""AHN (bare-earth heights) at 2.5 m in tiles, averaged 2x2 to a clean 5 m grid: python3 fetch/dem_fine.py dunes [s,w,n,e margins in degrees] -> data/regions/dunes/dem5.json"""
import json, os, sys, time, urllib.request
from fetch_dem import rd, tiff_floats
HERE = os.path.dirname(os.path.abspath(__file__))
name = sys.argv[1]
R = json.load(open(os.path.join(HERE, '..', 'data', 'regions', name, 'region.json')))
s, w, n, e = R['bbox']
m = [float(v) for v in sys.argv[2].split(',')] if len(sys.argv) > 2 else [0, 0, 0, 0]     # extra degrees beyond the OSM box: s,w,n,e (the beach)
s, w, n, e = s - m[0], w - m[1], n + m[2], e + m[3]
xs, ys = zip(*[rd(a, b) for a, b in ((s, w), (s, e), (n, w), (n, e))])
x0, y1 = min(xs), max(ys)
F, T = 2.5, 400                                   # fine cell, tile size in fine cells
NW, NH = int((max(xs) - x0) / F) // 2 * 2, int((y1 - min(ys)) / F) // 2 * 2
fine = [[None] * NW for _ in range(NH)]
for ty in range(0, NH, T):
    for tx in range(0, NW, T):
        w_, h_ = min(T, NW - tx), min(T, NH - ty)
        bx0, by1 = x0 + tx * F, y1 - ty * F
        url = ('https://service.pdok.nl/rws/ahn/wcs/v1_0?SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage&COVERAGEID=dtm_05m&FORMAT=image/tiff'
               '&SUBSET=x(%.2f,%.2f)&SUBSET=y(%.2f,%.2f)&SCALESIZE=x(%d),y(%d)' % (bx0, bx0 + w_ * F, by1 - h_ * F, by1, w_, h_))
        for attempt in range(3):
            try:
                b = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'AtlasConcept/1.0'}), timeout=180).read(); break
            except Exception as ex: print('retry', ex); time.sleep(5)
        W, H, rows, comp, pred = tiff_floats(b)
        for j in range(min(H, h_)):
            r = rows[j]; dst = fine[ty + j]
            for i in range(min(W, w_)):
                v = r[i]; dst[tx + i] = v if v is not None and abs(v) < 1000 else None
    print('row', ty, 'of', NH, flush=True)
z = []
for j in range(0, NH, 2):
    a, b = fine[j], fine[j + 1]; row = []
    for i in range(0, NW, 2):
        v = [q for q in (a[i], a[i + 1], b[i], b[i + 1]) if q is not None]
        row.append(round(sum(v) / len(v), 2) if v else None)
    z.append(row)
json.dump(dict(crs='rd', x0=x0, x1=x0 + NW * F, y0=y1 - NH * F, y1=y1, W=NW // 2, H=NH // 2, cell=F * 2, source='AHN dtm_05m via PDOK, 2.5 m averaged to 5 m', z=z),
          open(os.path.join(HERE, '..', 'data', 'regions', name, 'dem5.json'), 'w'), separators=(',', ':'))
flat = [v for r in z for v in r if v is not None]
print(name, NW // 2, NH // 2, 'min %.1f max %.1f' % (min(flat), max(flat)), 'empty %.1f%%' % (100 - 100 * len(flat) / (len(z) * len(z[0]))))
