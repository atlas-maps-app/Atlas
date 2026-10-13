"""Terrain where OpenStreetMap has none: the ground's shape from AHN (Dutch national elevation model, bare earth), with OSM's land cover
draped over it. Heights are true scale. Drawn as rows of cells across the view, far to near, so near ridges hide what lies behind them;
paths, roads and boundaries are draped along the surface and drawn with the row they stand in."""
import json, math, os, collections
import amsterdam as A
from city import P, hx, tohex, mix, mulc, vnorm, vdot
from real import pip, signed_area
import sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from fetch_dem import rd

class DEM:
    def __init__(self, path):
        d = json.load(open(path)); self.d = d; self.z = d['z']; self.crs = d.get('crs', 'rd')
        self.cell, self.W, self.H = d['cell'], d['W'], d['H']
        if self.crs == 'rd': self.x0, self.y1 = d['x0'], d['y1']
    def h(self, x, z):
        lat = A.LAT0 - z / A.KZ; lon = A.LON0 + x / A.KX
        if self.crs == 'rd':
            X, Y = rd(lat, lon)
            c = (X - self.x0) / self.cell - 0.5; r = (self.y1 - Y) / self.cell - 0.5
        else:          # a latitude/longitude grid, north row first
            c = (lon - self.d['w']) / (self.d['e'] - self.d['w']) * self.W - 0.5; r = (self.d['n'] - lat) / (self.d['n'] - self.d['s']) * self.H - 0.5
        if c < 0 or r < 0 or c >= self.W - 1 or r >= self.H - 1: return 0.0
        return self._at(r, c)
    def hn(self, x, z):
        """The height, or None where the survey has no ground (AHN leaves water and buildings empty)."""
        lat = A.LAT0 - z / A.KZ; lon = A.LON0 + x / A.KX
        if self.crs == 'rd':
            X, Y = rd(lat, lon); c = (X - self.x0) / self.cell - 0.5; r = (self.y1 - Y) / self.cell - 0.5
        else:
            c = (lon - self.d['w']) / (self.d['e'] - self.d['w']) * self.W - 0.5; r = (self.d['n'] - lat) / (self.d['n'] - self.d['s']) * self.H - 0.5
        if c < 0 or r < 0 or c >= self.W - 1 or r >= self.H - 1: return None
        i, j = int(r), int(c)
        if None in (self.z[i][j], self.z[i][j + 1], self.z[i + 1][j], self.z[i + 1][j + 1]): return None
        return self._at(r, c)
    def _at(self, r, c):
        i, j = int(r), int(c); fr, fc = r - i, c - j
        vs = [self.z[i][j], self.z[i][j + 1], self.z[i + 1][j], self.z[i + 1][j + 1]]
        if any(v is None for v in vs):
            ok = [v for v in vs if v is not None]
            return sum(ok) / len(ok) if ok else 0.0
        return (vs[0] * (1 - fc) + vs[1] * fc) * (1 - fr) + (vs[2] * (1 - fc) + vs[3] * fc) * fr

class Local:
    """The DEM resampled once into local metres (x east, z south), for fast bilinear lookups."""
    def __init__(self, dem, x0, x1, z0, z1, step=15.0, base=0.0, fill=False):
        self.x0, self.z0, self.step = x0, z0, step
        self.nx, self.nz = int((x1 - x0) / step) + 2, int((z1 - z0) / step) + 2
        if not fill:
            self.g = [[dem.h(x0 + i * step, z0 + j * step) - base for i in range(self.nx)] for j in range(self.nz)]
            return
        # where the survey is empty (water, outside), the nearest surveyed ground spreads in, so empty cells read as level, not as pits
        g = [[dem.hn(x0 + i * step, z0 + j * step) for i in range(self.nx)] for j in range(self.nz)]
        todo = [(i, j) for j in range(self.nz) for i in range(self.nx) if g[j][i] is None]
        for _ in range(400):
            if not todo: break
            nxt, upd = [], []
            for (i, j) in todo:
                v = [g[b][a] for a, b in ((i - 1, j), (i + 1, j), (i, j - 1), (i, j + 1)) if 0 <= a < self.nx and 0 <= b < self.nz and g[b][a] is not None]
                if v: upd.append((i, j, sum(v) / len(v)))
                else: nxt.append((i, j))
            for i, j, v in upd: g[j][i] = v
            todo = nxt
        self.g = [[(v if v is not None else base) - base for v in row] for row in g]
    def h(self, x, z):
        fx, fz = (x - self.x0) / self.step, (z - self.z0) / self.step
        i, j = int(fx), int(fz)
        if i < 0 or j < 0 or i >= self.nx - 1 or j >= self.nz - 1: return 0.0
        tx, tz = fx - i, fz - j; g = self.g
        return (g[j][i] * (1 - tx) + g[j][i + 1] * tx) * (1 - tz) + (g[j + 1][i] * (1 - tx) + g[j + 1][i + 1] * tx) * tz

def png_rgba(w, h, rows):
    import zlib, struct
    raw = b''.join(b'\x00' + bytes(r) for r in rows)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')

def wet_mask(loc, rings):
    """Grid cells under water, filled row by row (scanline), so even the sea is quick."""
    wet = set()
    for poly in rings:
        zs = [p[1] for p in poly]; n = len(poly)
        for j in range(max(0, int((min(zs) - loc.z0) / loc.step)), min(loc.nz, int((max(zs) - loc.z0) / loc.step) + 2)):
            z = loc.z0 + j * loc.step; xs = []
            for k in range(n):
                (ax, az), (bx, bz) = poly[k], poly[(k + 1) % n]
                if (az <= z < bz) or (bz <= z < az): xs.append(ax + (z - az) * (bx - ax) / (bz - az))
            xs.sort()
            for a, b in zip(xs[0::2], xs[1::2]):
                for i in range(max(0, int(math.ceil((a - loc.x0) / loc.step))), min(loc.nx, int((b - loc.x0) / loc.step) + 1)): wet.add((i, j))
    return wet

def hillshade(S, cam, th, cfg, scale=0.5, trace=True, raw=False):
    """Light and shade on the real slopes, per pixel: each view ray is traced to where it meets the terrain (so ridges hide what is behind).
    trace=False keeps the shade on the flat ground plane, where a flat drawing has put everything else."""
    import base64
    L = th['_L']; night = th['night']
    if night: return None if raw else ''
    TX, TZ = cfg['target'][0], cfg['target'][2]; R = cfg['radius']
    loc = S.get('_local')
    if loc is None:
        loc = S['_local'] = Local(S['dem'], TX - R * 1.6, TX + R * 1.6, TZ - R * 1.6, TZ + R * 1.6, base=S.get('_base', 0.0), fill=not trace)
        # where there is water the ground has no slope to show: a mask of the lakes on the same grid
        if not trace: loc.wet = wet_mask(loc, S['_lakes'])
        else: loc.wet = set()
        for poly in (S['_lakes'] if trace else []):
            xs = [p[0] for p in poly]; zs = [p[1] for p in poly]
            for j in range(max(0, int((min(zs) - loc.z0) / loc.step)), min(loc.nz, int((max(zs) - loc.z0) / loc.step) + 2)):
                for i in range(max(0, int((min(xs) - loc.x0) / loc.step)), min(loc.nx, int((max(xs) - loc.x0) / loc.step) + 2)):
                    if pip(loc.x0 + i * loc.step, loc.z0 + j * loc.step, poly): loc.wet.add((i, j))
    sunny = L[1] > 0 and not th.get('sky_k')
    lv = vnorm((L[0], max(L[1], 0.3), L[2])) if sunny else vnorm((-0.6, 0.9, -0.6))
    k = 3.0 if sunny else 2.0; flat = lv[1]
    W, H = int(cam.W * scale), int(cam.H * scale)
    C = cam.C; rows = []
    for py in range(H):
        row = bytearray(W * 4)
        sy = (py + 0.5) / scale
        for px in range(W):
            sx = (px + 0.5) / scale
            dx, dy = (sx - cam.cx) / cam.focal, -(sy - cam.cy) / cam.focal
            d = (cam.r[0] * dx + cam.u[0] * dy + cam.f[0], cam.r[1] * dx + cam.u[1] * dy + cam.f[1], cam.r[2] * dx + cam.u[2] * dy + cam.f[2])
            if d[1] >= -1e-4: continue
            t = -C[1] / d[1]
            for _ in range(4 if trace else 0):       # walk onto the surface
                x, z = C[0] + d[0] * t, C[2] + d[2] * t
                t = (loc.h(x, z) - C[1]) / d[1]
            x, z = C[0] + d[0] * t, C[2] + d[2] * t
            if math.hypot(x - TX, z - TZ) > R * 1.05: continue
            if (int(round((x - loc.x0) / loc.step)), int(round((z - loc.z0) / loc.step))) in loc.wet: continue
            e = 8.0
            gx = (loc.h(x + e, z) - loc.h(x - e, z)) / (2 * e); gz = (loc.h(x, z + e) - loc.h(x, z - e)) / (2 * e)
            nl = (-gx * lv[0] + lv[1] - gz * lv[2]) / math.sqrt(gx * gx + 1 + gz * gz)
            v = (nl - flat) * k
            a = min(0.5, abs(v))
            if a < 0.02: continue
            o = px * 4
            if v > 0: row[o:o + 4] = bytes((255, 244, 220, int(a * 255)))
            else: row[o:o + 4] = bytes((24, 34, 22, int(a * 255)))
        rows.append(row)
    if raw: return png_rgba(W, H, rows)        # the PNG itself, to stand on the board as its own file
    data = base64.b64encode(png_rgba(W, H, rows)).decode()
    return '<image href="data:image/png;base64,%s" x="0" y="0" width="%d" height="%d" preserveAspectRatio="none" style="image-rendering:auto"/>' % (data, cam.W, cam.H)

class Cover:
    """Which mapped land cover (or water) lies under a point: polygons indexed on a coarse grid."""
    def __init__(self, land, water, sea, kinds):
        self.items = []; self.grid = collections.defaultdict(list); G = 150.0
        for a in land:
            self.items.append((kinds[a['kind']][1], a['kind'], a['poly']))
        for r in water: self.items.append((8, 'lake', r))
        for r in sea: self.items.append((9, 'sea', r))
        for idx, (pr, k, poly) in enumerate(self.items):
            xs = [p[0] for p in poly]; zs = [p[1] for p in poly]
            bb = (min(xs), max(xs), min(zs), max(zs))
            if (bb[1] - bb[0]) * (bb[3] - bb[2]) > 4e7:      # the sea: too big to index, tested last
                self.grid['big'].append((idx, bb)); continue
            for gx in range(int(bb[0] // G), int(bb[1] // G) + 1):
                for gz in range(int(bb[2] // G), int(bb[3] // G) + 1): self.grid[(gx, gz)].append((idx, bb))
        self.G = G
    def at(self, x, z):
        best = None
        for idx, bb in self.grid.get((int(x // self.G), int(z // self.G)), []) + self.grid.get('big', []):
            if not (bb[0] <= x <= bb[1] and bb[2] <= z <= bb[3]): continue
            pr, k, poly = self.items[idx]
            if best is not None and pr <= best[0]: continue
            if pip(x, z, poly): best = (pr, k)
        return best[1] if best else None

def drape(cam, dem, pts, step=18.0, lift=0.0, flat=None):
    """A ring or line laid on the ground: resampled every few metres and lifted to the terrain (or to a water level)."""
    out = []
    for a, b in zip(pts, pts[1:] + pts[:1]):
        Lg = math.dist(a, b); m = max(1, int(Lg / step))
        for q in range(m):
            x, z = a[0] + (b[0] - a[0]) * q / m, a[1] + (b[1] - a[1]) * q / m
            out.append((x, (flat if flat is not None else dem.h(x, z)) + lift, z))
    return out

def draw(S, cam, th, far, defs, cfg, kinds, line_items):
    """The ground on its real shape: mapped land cover draped over the terrain, then a soft hillshade from the slopes, then draped lines."""
    dem = S['dem']; night = th['night']; L = th['_L']
    TX, TZ = cfg['target'][0], cfg['target'][2]; R = cfg['radius']
    near = lambda pts: any(math.hypot(p[0] - TX, p[1] - TZ) < R * 1.1 for p in pts[::max(1, len(pts) // 12)])
    out = ['<g filter="url(#mat)">']
    # land cover, in its draw order, each outline lifted onto the ground
    groups = collections.OrderedDict()
    for a in S['land']:
        if not near(a['poly']): continue
        sp = cam.poly(drape(cam, dem, a['poly']), margin=300)
        if sp and A.area(sp) > 0.4: groups.setdefault(a['kind'], []).append(P(sp))
    for kind, ds in groups.items():
        c = kinds[kind][0]
        out.append('<path d="%s" fill="%s"/>' % (''.join(ds), tohex(mulc(hx(c), 0.2)) if night else c))
    out.append('</g>')
    # water: lakes at their own level, the sea at zero
    wc = th.get('water_top', th['water']) if not night else '#0A1220'
    ws = []
    for poly in S['_lakes']:
        if not near(poly): continue
        lvl = min(dem.h(*p) for p in poly[::max(1, len(poly) // 20)])
        sp = cam.poly(drape(cam, dem, poly, flat=lvl), margin=300)
        if sp and A.area(sp) > 0.3: ws.append(P(sp))
    for poly in S['_seas']:
        sp = cam.poly([(x, 0.0, z) for (x, z) in poly], margin=900)
        if sp: ws.append(P(sp))
    if ws: out.append('<path d="%s" fill="%s"/>' % (''.join(ws), wc))
    # hillshade: per pixel, from the real slopes (drawn over land and water, under the paths)
    out.append(hillshade(S, cam, th, cfg))
    # paths, roads and the park's edge, laid along the ground
    lb = collections.OrderedDict()
    for (pts, width, colour, dash) in line_items:
        if not near(pts): continue
        dp = []
        run = []
        for a, b in zip(pts, pts[1:]):
            Lg = math.dist(a, b); m = max(1, int(Lg / 15))
            for q in range(m + 1):
                x, z = a[0] + (b[0] - a[0]) * q / m, a[1] + (b[1] - a[1]) * q / m
                pp = cam.proj((x, dem.h(x, z) + 0.4, z)) if math.hypot(x - TX, z - TZ) < R * 1.05 else None
                if pp: run.append(pp)
                elif len(run) > 1: dp.append('M' + 'L'.join('%.1f %.1f' % r for r in run)); run = []
                else: run = []
        if len(run) > 1: dp.append('M' + 'L'.join('%.1f %.1f' % r for r in run))
        if not dp: continue
        mid = pts[len(pts) // 2]; d = cam.depth((mid[0], 0, mid[1])); w = min(4.0 if dash else 7.0, max(0.5, width * cam.focal / max(d, 600)))
        lb.setdefault((tohex(mulc(hx(colour), 0.3)) if night else colour, round(w, 1), dash), []).append(''.join(dp))
    for (col, w, dash), ds in lb.items():
        out.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" stroke-linecap="round" stroke-linejoin="round"%s/>' % (''.join(ds), col, w, ' stroke-dasharray="6 4"' if dash else ''))
    return ''.join(out)
