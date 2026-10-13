#!/usr/bin/env python3
"""Terrain for the Dutch regions: AHN (Actueel Hoogtebestand Nederland, bare-earth DTM) from PDOK, resampled to a grid.
This is not OpenStreetMap: OSM maps a few summits with ele=*, not the ground's shape. Stored as regions/<name>/dem.json."""
import json, math, os, struct, sys, urllib.request, zlib
HERE = os.path.dirname(os.path.abspath(__file__))

def rd(lat, lon):
    """WGS84 to Rijksdriehoek (EPSG:28992), the standard polynomial approximation (about a metre)."""
    dp, dl = 0.36 * (lat - 52.15517440), 0.36 * (lon - 5.38720621)
    x = 155000 + 190094.945 * dl - 11832.228 * dp * dl - 114.221 * dp ** 2 * dl - 32.391 * dl ** 3 - 0.705 * dp - 2.340 * dp ** 3 * dl - 0.608 * dp * dl ** 3 - 0.008 * dl ** 2 + 0.148 * dp ** 2 * dl ** 3
    y = 463000 + 309056.544 * dp + 3638.893 * dl ** 2 + 73.077 * dp ** 2 - 157.984 * dp * dl ** 2 + 59.788 * dp ** 3 + 0.433 * dl - 6.439 * dp ** 2 * dl ** 2 - 0.032 * dp * dl + 0.092 * dl ** 4 - 0.054 * dp * dl ** 4
    return x, y

def unpredict(data, w, h, nb):
    """Undo TIFF predictor 3 (floating point): per row, bytes were split into planes (most significant first) and differenced."""
    out = []; rowlen = w * nb; fmt = '>f' if nb == 4 else '>d'
    for r in range(h):
        row = bytearray(data[r * rowlen:(r + 1) * rowlen])
        for i in range(1, len(row)): row[i] = (row[i] + row[i - 1]) & 0xFF
        for j in range(w):
            out.append(struct.unpack(fmt, bytes(row[k * w + j] for k in range(nb)))[0])
    return out

def tiff_floats(b):
    bo = '<' if b[:2] == b'II' else '>'
    off = struct.unpack(bo + 'I', b[4:8])[0]
    n = struct.unpack(bo + 'H', b[off:off + 2])[0]; tags = {}
    for i in range(n):
        tg, typ, cnt, val = struct.unpack(bo + 'HHI4s', b[off + 2 + 12 * i: off + 14 + 12 * i])
        size = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 11: 4, 12: 8, 16: 8}.get(typ, 1) * cnt
        raw = val if size <= 4 else b[struct.unpack(bo + 'I', val)[0]: struct.unpack(bo + 'I', val)[0] + size]
        fmt = {3: 'H', 4: 'I', 11: 'f', 12: 'd', 16: 'Q'}.get(typ)
        tags[tg] = list(struct.unpack(bo + fmt * cnt, raw[:struct.calcsize(fmt) * cnt])) if fmt else raw
    W, H = tags[256][0], tags[257][0]; comp = tags.get(259, [1])[0]; pred = tags.get(317, [1])[0]
    bps = tags[258][0]
    if 324 in tags:   # tiles
        tw, th = tags[322][0], tags[323][0]; offs, cnts = tags[324], tags[325]
        out = [[None] * W for _ in range(H)]
        tx_n = (W + tw - 1) // tw
        for k, (o, c) in enumerate(zip(offs, cnts)):
            data = b[o:o + c]
            if comp in (8, 32946): data = zlib.decompress(data)
            if pred == 3: vals = unpredict(data, tw, th, bps // 8)
            else: vals = struct.unpack(bo + ('f' if bps == 32 else 'd') * (tw * th), data[:tw * th * bps // 8])
            ty, tx = divmod(k, tx_n)
            for r in range(th):
                yy = ty * th + r
                if yy >= H: break
                row = list(vals[r * tw:(r + 1) * tw])
                for cidx in range(tw):
                    xx = tx * tw + cidx
                    if xx < W: out[yy][xx] = row[cidx]
        return W, H, out, comp, pred
    offs, cnts = tags[273], tags[279]; data = b''
    rps = tags.get(278, [H])[0]; vals = []
    for o, c in zip(offs, cnts):
        d = b[o:o + c]
        d = zlib.decompress(d) if comp in (8, 32946) else d
        if pred == 3: vals += list(unpredict(d, W, len(d) // (W * bps // 8), bps // 8))
        else: vals += list(struct.unpack(bo + ('f' if bps == 32 else 'd') * (len(d) // (bps // 8)), d))
    return W, H, [list(vals[r * W:(r + 1) * W]) for r in range(H)], comp, pred

def ign(name, R, cell):
    """France: RGE ALTI from IGN's Géoplateforme, as raw 32-bit heights on a latitude/longitude grid."""
    s, w, n, e = R['bbox']
    lat0 = (s + n) / 2
    W = int((e - w) * 111320 * math.cos(math.radians(lat0)) / cell); H = int((n - s) * 110540 / cell)
    url = ('https://data.geopf.fr/wms-r/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES&STYLES='
           '&CRS=EPSG:4326&BBOX=%s,%s,%s,%s&WIDTH=%d&HEIGHT=%d&FORMAT=image/x-bil;bits=32' % (s, w, n, e, W, H))
    b = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'AtlasConcept/1.0'}), timeout=180).read()
    v = struct.unpack('<%df' % (W * H), b)
    rows = [[round(x, 1) if -100 < x < 5000 else None for x in v[r * W:(r + 1) * W]] for r in range(H)]
    flat = [x for r in rows for x in r if x is not None]
    print(name, W, H, 'min %.1f max %.1f' % (min(flat), max(flat)))
    json.dump(dict(crs='wgs84', s=s, w=w, n=n, e=e, W=W, H=H, cell=cell, source='RGE ALTI (IGN) via Géoplateforme, resampled', z=rows),
              open(os.path.join(HERE, '..', 'data', 'regions', name, 'dem.json'), 'w'), separators=(',', ':'))

if __name__ == '__main__':
    name = sys.argv[1]; cell = float(sys.argv[2]) if len(sys.argv) > 2 else 20.0
    R = json.load(open(os.path.join(HERE, '..', 'data', 'regions', name, 'region.json')))
    if R['origin'][0] < 50.7: ign(name, R, cell); sys.exit(0)
    s, w, n, e = R['bbox']
    xs, ys = zip(*[rd(a, b) for a, b in ((s, w), (s, e), (n, w), (n, e))])
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    Wn, Hn = int((x1 - x0) / cell), int((y1 - y0) / cell)
    url = ('https://service.pdok.nl/rws/ahn/wcs/v1_0?SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage&COVERAGEID=dtm_05m&FORMAT=image/tiff'
           '&SUBSET=x(%.0f,%.0f)&SUBSET=y(%.0f,%.0f)&SCALESIZE=x(%d),y(%d)' % (x0, x1, y0, y1, Wn, Hn))
    b = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'AtlasConcept/1.0'}), timeout=180).read()
    open('/tmp/dem_%s.tif' % name, 'wb').write(b)
    W, H, rows, comp, pred = tiff_floats(b)
    flat = [v for r in rows for v in r if v is not None and abs(v) < 1000]
    print(name, W, H, 'compression', comp, 'predictor', pred, 'min %.1f max %.1f' % (min(flat), max(flat)))
    json.dump(dict(crs='rd', x0=x0, x1=x1, y0=y0, y1=y1, W=W, H=H, cell=cell, source='AHN dtm_05m via PDOK, resampled', z=[[round(v, 1) if v is not None and abs(v) < 1000 else None for v in r] for r in rows]),
              open(os.path.join(HERE, '..', 'data', 'regions', name, 'dem.json'), 'w'), separators=(',', ':'))
