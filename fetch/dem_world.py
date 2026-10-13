#!/usr/bin/env python3
"""Terrain for regions outside the Netherlands and France: the open global elevation tiles (Mapzen Terrarium on AWS, from SRTM, EU-DEM and
national surveys, about 30 m in Europe), resampled to a latitude/longitude grid. Not OpenStreetMap: OSM maps summits, not the ground's shape.
python3 fetch/dem_world.py name [zoom]  ->  regions/<name>/dem.json"""
import json, math, os, struct, sys, urllib.request, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
UA = 'AtlasConcept/1.0 (design prototype; one-off extracts)'
URL = 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png'


def png_rgb(b):
    """A plain PNG decoder for 8-bit RGB or RGBA, not interlaced (what the tiles are): rows of (r, g, b)."""
    assert b[:8] == b'\x89PNG\r\n\x1a\n'
    i = 8; idat = b''; W = H = ct = 0
    while i < len(b):
        n = struct.unpack('>I', b[i:i + 4])[0]; typ = b[i + 4:i + 8]; data = b[i + 8:i + 8 + n]; i += 12 + n
        if typ == b'IHDR': W, H, bd, ct, _, _, il = struct.unpack('>IIBBBBB', data); assert bd == 8 and il == 0 and ct in (2, 6)
        elif typ == b'IDAT': idat += data
        elif typ == b'IEND': break
    bpp = 3 if ct == 2 else 4; raw = zlib.decompress(idat); stride = W * bpp
    rows = []; prev = bytearray(stride); p = 0
    for _ in range(H):
        f = raw[p]; line = bytearray(raw[p + 1:p + 1 + stride]); p += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0; up = prev[x]; c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + up) & 255
            elif f == 3: line[x] = (line[x] + (a + up) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(up - c), abs(a - c), abs(a + up - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else up if pb <= pc else c)) & 255
        rows.append([(line[k * bpp], line[k * bpp + 1], line[k * bpp + 2]) for k in range(W)]); prev = line
    return rows


def tile_xy(lat, lon, z):
    n = 2 ** z; x = (lon + 180) / 360 * n
    y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
    return x, y


if __name__ == '__main__':
    name = sys.argv[1]; z = int(sys.argv[2]) if len(sys.argv) > 2 else 14
    R = json.load(open(os.path.join(HERE, '..', 'data', 'regions', name, 'region.json')))
    s, w, n, e = R['bbox']; m = 0.02                  # a margin: the hills beyond the data show on the horizon
    s, w, n, e = s - m, w - m * 1.5, n + m, e + m * 1.5
    x0, y1 = tile_xy(s, w, z); x1, y0 = tile_xy(n, e, z)
    tiles = {}
    for tx in range(int(x0), int(x1) + 1):
        for ty in range(int(y0), int(y1) + 1):
            req = urllib.request.Request(URL % (z, tx, ty), headers={'User-Agent': UA})
            with urllib.request.urlopen(req, timeout=60) as r: tiles[(tx, ty)] = png_rgb(r.read())
    print(name, len(tiles), 'tiles at zoom', z)
    def height(lat, lon):
        fx, fy = tile_xy(lat, lon, z); tx, ty = int(fx), int(fy)
        px, py = (fx - tx) * 256 - 0.5, (fy - ty) * 256 - 0.5
        def at(i, j):
            ti, tj = tx, ty
            while i < 0: i += 256; ti -= 1
            while i > 255: i -= 256; ti += 1
            while j < 0: j += 256; tj -= 1
            while j > 255: j -= 256; tj += 1
            r, g, b = tiles[(ti, tj)][j][i]; return r * 256 + g + b / 256 - 32768
        i, j = int(math.floor(px)), int(math.floor(py)); a, b_ = px - i, py - j
        return (at(i, j) * (1 - a) * (1 - b_) + at(i + 1, j) * a * (1 - b_) + at(i, j + 1) * (1 - a) * b_ + at(i + 1, j + 1) * a * b_)
    cell = 10.0                                         # metres; finer than the source, so the bilinear ground stays smooth
    W = int((e - w) * 111320 * math.cos(math.radians((s + n) / 2)) / cell); H = int((n - s) * 111320 / cell)
    rows = [[round(height(n - (r + 0.5) / H * (n - s), w + (c + 0.5) / W * (e - w)), 1) for c in range(W)] for r in range(H)]
    flat = [v for row in rows for v in row]
    json.dump(dict(crs='wgs84', s=s, w=w, n=n, e=e, W=W, H=H, cell=cell, source='Terrarium elevation tiles (SRTM, EU-DEM, national surveys) via AWS Open Data, resampled', z=rows),
              open(os.path.join(HERE, '..', 'data', 'regions', name, 'dem.json'), 'w'), separators=(',', ':'))
    print(name, W, 'x', H, 'cells of', cell, 'm, heights', round(min(flat)), 'to', round(max(flat)), 'm')
