"""The ground on its real shape, drawn per pixel: every view ray is traced onto AHN heights (5 m, bare earth), coloured by the mapped
land cover where it lands, and lit from the hour's sun, with the shadows the dunes really cast. Paths and roads are then drawn as lines
along the ground, hidden where a dune stands in front of them. Heights come from AHN, not OpenStreetMap; colours only from OSM tags."""
import base64, math, os, subprocess, tempfile, zlib, struct
import amsterdam as A
from city import P, hx, tohex, mix, mulc, vnorm, vdot


class Grid:
    """Heights on a local 5 m grid (x east, z south), filled where the survey is empty, with lakes and sea set to their water level."""
    def __init__(self, dem, x0, x1, z0, z1, step=5.0):
        self.x0, self.z0, self.step = x0, z0, step
        self.nx, self.nz = int((x1 - x0) / step) + 2, int((z1 - z0) / step) + 2
        self.g = [dem.hn(x0 + i * step, z0 + j * step) for j in range(self.nz) for i in range(self.nx)]

    def cells(self, poly, scale=1):
        """Grid cells inside a polygon, row by row (scanline): yields (row start index, i0, i1)."""
        st = self.step / scale; nx, nz = self.nx * scale, self.nz * scale
        zs = [p[1] for p in poly]; n = len(poly)
        for j in range(max(0, int((min(zs) - self.z0) / st)), min(nz, int((max(zs) - self.z0) / st) + 2)):
            z = self.z0 + j * st; xs = []
            for k in range(n):
                (ax, az), (bx, bz) = poly[k], poly[(k + 1) % n]
                if (az <= z < bz) or (bz <= z < az): xs.append(ax + (z - az) * (bx - ax) / (bz - az))
            xs.sort()
            for a, b in zip(xs[0::2], xs[1::2]):
                i0, i1 = max(0, int(math.ceil((a - self.x0) / st))), min(nx, int((b - self.x0) / st) + 1)
                if i1 > i0: yield j * nx, i0, i1

    def set_level(self, poly, y):
        g = self.g
        for o, i0, i1 in self.cells(poly):
            for i in range(o + i0, o + i1): g[i] = y

    def fill(self):
        """Empty cells (buildings, gaps) take the average of their surveyed neighbours, spreading inward."""
        g, nx, nz = self.g, self.nx, self.nz
        todo = [k for k in range(nx * nz) if g[k] is None]
        while todo:
            nxt, upd = [], []
            for k in todo:
                i, j = k % nx, k // nx; v = []
                if i > 0 and g[k - 1] is not None: v.append(g[k - 1])
                if i < nx - 1 and g[k + 1] is not None: v.append(g[k + 1])
                if j > 0 and g[k - nx] is not None: v.append(g[k - nx])
                if j < nz - 1 and g[k + nx] is not None: v.append(g[k + nx])
                if v: upd.append((k, sum(v) / len(v)))
                else: nxt.append(k)
            if not upd: break
            for k, v in upd: g[k] = v
            todo = nxt
        for k in todo: g[k] = 0.0
        self.top = max(g) + 1.0

    def h(self, x, z):
        fx, fz = (x - self.x0) / self.step, (z - self.z0) / self.step
        i, j = int(fx), int(fz)
        if i < 0 or j < 0 or i >= self.nx - 1 or j >= self.nz - 1: return 0.0
        tx, tz = fx - i, fz - j; k = j * self.nx + i; g = self.g
        return (g[k] * (1 - tx) + g[k + 1] * tx) * (1 - tz) + (g[k + self.nx] * (1 - tx) + g[k + self.nx + 1] * tx) * tz


def build(S, dem, lakes, seas, land, kinds, extent=None, step=5.0):
    """Heights and land cover on grids over the surveyed area (or the part of it a view needs)."""
    # the survey's corners in local metres
    corners = []
    for fx in (0.0, 1.0):
        for fy in (0.0, 1.0):
            if dem.crs == 'rd':
                X = dem.x0 + (dem.d['x1'] - dem.x0) * fx; Y = dem.d['y0'] + (dem.y1 - dem.d['y0']) * fy
                corners.append(_rd_to_local(X, Y))
            else:
                lat = dem.d['s'] + (dem.d['n'] - dem.d['s']) * fy; lon = dem.d['w'] + (dem.d['e'] - dem.d['w']) * fx
                corners.append(((lon - A.LON0) * A.KX, (A.LAT0 - lat) * A.KZ))
    x0, x1 = min(c[0] for c in corners), max(c[0] for c in corners)
    z0, z1 = min(c[1] for c in corners), max(c[1] for c in corners)
    if extent: x0, x1, z0, z1 = max(x0, extent[0]), min(x1, extent[1]), max(z0, extent[2]), min(z1, extent[3])
    G = Grid(dem, x0, x1, z0, z1, step)
    # water first: the sea at NAP 0, each lake at the height of its own shore
    for poly in seas: G.set_level(poly, 0.0)
    levels = []
    for poly in lakes:
        v = []
        for a, b in zip(poly, poly[1:] + poly[:1]):
            m = max(1, int(math.dist(a, b) / 8))
            for q in range(m):
                y = dem.hn(a[0] + (b[0] - a[0]) * q / m, a[1] + (b[1] - a[1]) * q / m)
                if y is not None: v.append(y)
        lvl = sorted(v)[len(v) // 10] if v else None
        levels.append(lvl)
        if lvl is not None: G.set_level(poly, lvl)
    G.fill()
    # land cover at 1.25 m: mapped areas in their draw order, then lakes and the sea
    CS = G.cs = 4
    names = ['ground'] + list(kinds) + ['lake', 'sea']; idx = {k: i for i, k in enumerate(names)}
    cv = bytearray(G.nx * CS * G.nz * CS)
    for a in land:
        k = bytes([idx[a['kind']]])
        for o, i0, i1 in G.cells(a['poly'], CS): cv[o + i0:o + i1] = k * (i1 - i0)
    for poly in lakes:
        for o, i0, i1 in G.cells(poly, CS): cv[o + i0:o + i1] = bytes([idx['lake']]) * (i1 - i0)
    for poly in seas:
        for o, i0, i1 in G.cells(poly, CS): cv[o + i0:o + i1] = bytes([idx['sea']]) * (i1 - i0)
    G.cover, G.names = cv, names
    G.lake_levels = levels
    return G


def _rd_to_local(X, Y):
    """RD (Dutch grid) to local metres, by inverting the forward conversion with two Newton steps."""
    from fetch_dem import rd
    lat, lon = A.LAT0, A.LON0
    for _ in range(4):
        x0, y0 = rd(lat, lon); x1, _y = rd(lat, lon + 1e-4); _x, y1 = rd(lat + 1e-4, lon)
        lon += (X - x0) / ((x1 - x0) / 1e-4); lat += (Y - y0) / ((y1 - y0) / 1e-4)
    return ((lon - A.LON0) * A.KX, (A.LAT0 - lat) * A.KZ)


def _shadows(G, L):
    """Cells the sun cannot reach: a walk from each cell toward the sun until the ray clears the highest dune."""
    if L[1] <= 0.02: return None
    hl = math.hypot(L[0], L[2]); sx, sz = L[0] / hl, L[2] / hl; rise = L[1] / hl
    g, nx, nz, st, top = G.g, G.nx, G.nz, G.step, G.top
    sh = bytearray(nx * nz); step = st
    for j in range(nz):
        for i in range(nx):
            h0 = g[j * nx + i]; d = step
            while True:
                y = h0 + rise * d
                if y >= top: break
                fi, fj = i + sx * d / st, j + sz * d / st
                ii, jj = int(fi), int(fj)
                if ii < 0 or jj < 0 or ii >= nx - 1 or jj >= nz - 1: break
                tx, tz = fi - ii, fj - jj; k = jj * nx + ii
                if (g[k] * (1 - tx) + g[k + 1] * tx) * (1 - tz) + (g[k + nx] * (1 - tx) + g[k + nx + 1] * tx) * tz > y + 0.3:
                    sh[j * nx + i] = 1; break
                d += step
    return sh


def _png_rgb(w, h, rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rows)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')


def raster(S, cam, th, cfg, kinds, scale=2, far_m=14000.0):
    """The lit, coloured ground as one JPEG, its outline on screen (or None), and a depth per pixel for hiding lines."""
    G = S['_grid']; g, nx, nz, st = G.g, G.nx, G.nz, G.step
    cov, CS = G.cover, G.cs; cnx, cnz = G.nx * CS, G.nz * CS
    night = th['night']; L = th['_L']
    sunny = (not night) and L[1] > 0 and not th.get('sky_k')
    # how each kind looks on flat ground (the same colours as the flat regions), and how much light shapes it
    wc = th.get('water_top', th['water']) if not night else '#0A1220'
    base = []
    for k in G.names:
        if k in ('lake', 'sea'): c = hx(wc)
        elif k == 'ground': c = hx(th['ground'])
        else: c = hx(kinds[k][0])
        if night and k not in ('lake', 'sea', 'ground'): c = mulc(c, 0.2)      # the theme's ground is already its night colour
        base.append(c)
    flat_ = [k in ('lake', 'sea') for k in G.names]
    if sunny: lv = vnorm(L); k_l, k_sh = 0.8, 0.62            # direct sun: slopes facing it brighten, the far side and shadows darken
    elif not night: lv = vnorm((-0.6, 0.9, -0.6)); k_l, k_sh = 0.55, 1.0   # overcast: a soft light from the north-west sky
    else: lv = (0.0, 1.0, 0.0); k_l, k_sh = 0.25, 1.0
    key = (tuple(th['date']), tuple(th['hhmm']))
    sh = S.setdefault('_shadow', {}).get(key) if sunny else None
    if sunny and sh is None:
        sh = S['_shadow'][key] = _shadows(G, L)
    sea_i = G.names.index('sea')
    cool = (0.93, 0.97, 1.07)                                    # shade takes a little of the blue sky's colour
    W2, H2 = int(cam.W * scale), int(cam.H * scale)
    C = cam.C; r_, u_, f_ = cam.r, cam.u, cam.f
    rows = [bytearray(W2 * 3) for _ in range(H2)]
    depth = [[0.0] * W2 for _ in range(H2)]
    tops = []
    top = G.top
    x0, z0 = G.x0, G.z0
    for px in range(W2):
        dxs = ((px + 0.5) / scale - cam.cx) / cam.focal
        t_prev = None; ytop = H2
        for py in range(H2 - 1, -1, -1):
            dys = -((py + 0.5) / scale - cam.cy) / cam.focal
            d0 = r_[0] * dxs + u_[0] * dys + f_[0]; d1 = r_[1] * dxs + u_[1] * dys + f_[1]; d2 = r_[2] * dxs + u_[2] * dys + f_[2]
            if d1 >= -1e-4: break
            dn = math.sqrt(d0 * d0 + d1 * d1 + d2 * d2); d0 /= dn; d1 /= dn; d2 /= dn
            hz = math.hypot(d0, d2)
            t_top = (top - C[1]) / d1
            t = t_top if t_prev is None else max(t_top, t_prev - 30.0)
            for _ in range(40):                                   # the ray above this one hit there: start just before, above the ground
                if t <= t_top: break
                x, z = C[0] + d0 * t, C[2] + d2 * t
                if C[1] + d1 * t > G.h(x, z): break
                t = max(t_top, t - 60.0)
            hit = None; out = False; t_last = None
            while True:
                x, z = C[0] + d0 * t, C[2] + d2 * t
                fi, fj = (x - x0) / st, (z - z0) / st
                ii, jj = int(fi) if fi >= 0 else -1, int(fj) if fj >= 0 else -1
                if ii < 0 or jj < 0 or ii >= nx - 1 or jj >= nz - 1:
                    # beyond the survey: the sea, flat at NAP 0
                    t0 = -C[1] / d1
                    if t0 >= (t_last if t_last is not None else t_top): hit = max(t0, t); out = True
                    break
                tx, tz = fi - ii, fj - jj; k = jj * nx + ii
                h = (g[k] * (1 - tx) + g[k + 1] * tx) * (1 - tz) + (g[k + nx] * (1 - tx) + g[k + nx + 1] * tx) * tz
                y = C[1] + d1 * t
                if y <= h:
                    if t_last is None: hit = t; break
                    a_, b_ = t_last, t
                    for _ in range(5):
                        m = (a_ + b_) / 2; xm, zm = C[0] + d0 * m, C[2] + d2 * m
                        if C[1] + d1 * m <= G.h(xm, zm): b_ = m
                        else: a_ = m
                    hit = b_; break
                t_last = t
                t += max(1.2, min(40.0, (y - h) / (-d1 + hz * 0.9)))   # safe step: the slope ahead can rise at most this fast
            if hit is None: break
            if hit > far_m: hit = -C[1] / d1; out = True               # far out the sea is all there is: flat, at NAP 0
            t_prev = hit; ytop = py
            x, z = C[0] + d0 * hit, C[2] + d2 * hit
            o = px * 3; row = rows[py]
            depth[py][px] = hit
            if out:
                c = base[sea_i]; row[o] = int(c[0]); row[o + 1] = int(c[1]); row[o + 2] = int(c[2]); continue
            # the cover read between its 1.25 m cells, colours blended, so edges are soft rather than stepped
            fci, fcj = (x - x0) / st * CS - 0.5, (z - z0) / st * CS - 0.5
            ci, cj = int(fci), int(fcj)
            if 0 <= ci < cnx - 1 and 0 <= cj < cnz - 1:
                q0 = cj * cnx + ci; k00, k10, k01, k11 = cov[q0], cov[q0 + 1], cov[q0 + cnx], cov[q0 + cnx + 1]
                if k00 == k10 == k01 == k11: c = base[k00]; kind = k00
                else:
                    ax, az = fci - ci, fcj - cj
                    w00, w10, w01, w11 = (1 - ax) * (1 - az), ax * (1 - az), (1 - ax) * az, ax * az
                    c = tuple(base[k00][n_] * w00 + base[k10][n_] * w10 + base[k01][n_] * w01 + base[k11][n_] * w11 for n_ in range(3))
                    kind = max(((w00, k00), (w10, k10), (w01, k01), (w11, k11)))[1]
            else:
                kind = 0; c = base[0]
            if flat_[kind]:
                f = 1.0
            else:
                e = 3.0
                gx = (G.h(x + e, z) - G.h(x - e, z)) / (2 * e); gz = (G.h(x, z + e) - G.h(x, z - e)) / (2 * e)
                nn = 1.0 / math.sqrt(gx * gx + 1 + gz * gz)
                nl = (-gx * lv[0] + lv[1] - gz * lv[2]) * nn
                if sunny:
                    if sh is not None:                            # soft-edged: the shadow mask read between its cells
                        fi, fj = (x - x0) / st, (z - z0) / st; ii, jj = int(fi), int(fj); tx, tz = fi - ii, fj - jj; k = jj * nx + ii
                        s_ = (sh[k] * (1 - tx) + sh[k + 1] * tx) * (1 - tz) + (sh[k + nx] * (1 - tx) + sh[k + nx + 1] * tx) * tz if 0 <= ii < nx - 1 and 0 <= jj < nz - 1 else 0
                        nl *= 1.0 - s_
                    f = 1.0 + k_l * (max(0.0, nl) - lv[1])
                else:
                    f = 1.0 + k_l * (nl - lv[1])
                f = max(0.58, min(1.16, f))
            if f < 1.0:
                q = (1.0 - f) * 1.6
                row[o] = min(255, int(c[0] * f * (1 + (cool[0] - 1) * q))); row[o + 1] = min(255, int(c[1] * f * (1 + (cool[1] - 1) * q))); row[o + 2] = min(255, int(c[2] * f * (1 + (cool[2] - 1) * q)))
            else:
                row[o] = min(255, int(c[0] * f)); row[o + 1] = min(255, int(c[1] * f)); row[o + 2] = min(255, int(c[2] * f))
        tops.append(ytop)
    # JPEG through macOS sips: far smaller than PNG for a lit landscape
    tmp = tempfile.mkdtemp()
    pp, jp = os.path.join(tmp, 'g.png'), os.path.join(tmp, 'g.jpg')
    open(pp, 'wb').write(_png_rgb(W2, H2, rows))
    subprocess.run(['sips', '-s', 'format', 'jpeg', '-s', 'formatOptions', '80', pp, '--out', jp], check=True, capture_output=True)
    data = open(jp, 'rb').read()
    # the outline of what the image covers (a CSS polygon on the board), or None where it fills the frame;
    # each edge point takes the lower of the two columns it joins, so no unpainted pixel slips inside
    clip = None
    if max(tops) > 0:
        pts = ['%.1fpx %.1fpx' % (px / scale, (max(tops[max(0, px - 1)], tops[px]) + 0.5) / scale) for px in range(0, W2, 2)] + ['%dpx %.1fpx' % (cam.W, (tops[-1] + 0.5) / scale)]
        clip = 'polygon(0px %dpx, %s, %dpx %dpx)' % (cam.H, ', '.join(pts), cam.W, cam.H)
    return data, clip, depth, scale


def draw(S, cam, th, far, defs, cfg, kinds, line_items):
    """The traced ground image, then the paths, roads and park edge along it, broken where the ground hides them."""
    G = S['_grid']; night = th['night']
    TX, TZ = cfg['target'][0], cfg['target'][2]; R = cfg['radius']
    data, clip, depth, sc = raster(S, cam, th, cfg, kinds)
    W2, H2 = len(depth[0]), len(depth)
    out = []
    # the sea beyond the image, flat, so the horizon continues where the survey ends
    wc = th.get('water_top', th['water']) if not night else '#0A1220'
    ws = []
    for poly in S['_seas']:
        sp = cam.poly([(x, 0.0, z) for (x, z) in poly], margin=900)
        if sp: ws.append(P(sp))
    if ws: out.append('<path d="%s" fill="%s"/>' % (''.join(ws), wc))
    # the image is its own file on the board, between this ground and the lines (the asset store keeps no images inside SVGs)
    th['_raster'] = ('jpg', data, clip)
    C = cam.C
    above = []
    def seen(x, y, z):
        p = cam.proj((x, y, z))
        if not p: return None
        i, j = int(p[0] * sc), int(p[1] * sc)
        if not (0 <= i < W2 and 0 <= j < H2): return None
        d = math.dist((x, y, z), C); dd = depth[j][i]
        if dd and d > dd + 4.0 + d * 0.002: return None           # a dune stands in front
        return p
    lb = {}
    for item in line_items:
        pts, width, colour, dash = item[:4]; lit = len(item) > 4 and item[4]
        if not any(math.hypot(p[0] - TX, p[1] - TZ) < R * 1.1 for p in pts[::max(1, len(pts) // 12)]): continue
        dp, run = [], []
        for a, b in zip(pts, pts[1:]):
            Lg = math.dist(a, b); m = max(1, int(Lg / 8))
            for q in range(m + (1 if b is pts[-1] else 0)):
                x, z = a[0] + (b[0] - a[0]) * q / m, a[1] + (b[1] - a[1]) * q / m
                pp = seen(x, G.h(x, z) + 0.4, z) if math.hypot(x - TX, z - TZ) < R * 1.05 else None
                if pp: run.append(pp)
                else:
                    if len(run) > 1: dp.append('M' + 'L'.join('%.1f %.1f' % r for r in run))
                    run = []
        if len(run) > 1: dp.append('M' + 'L'.join('%.1f %.1f' % r for r in run))
        if not dp: continue
        mid = pts[len(pts) // 2]; d = cam.depth((mid[0], 0, mid[1]))
        w = min(4.0 if dash else 7.0, max(0.5, width * cam.focal / max(d, 600))) if not S.get('_town') else max(0.5, width * cam.focal / max(d, 1))     # in a town, streets at their width
        lb.setdefault((('#B98F58' if lit else tohex(mulc(hx(colour), 0.3))) if night else colour, round(w, 1), dash), []).append(''.join(dp))      # lit=yes streets glow at night
    for (col, w, dash), ds in lb.items():
        above.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" stroke-linecap="round" stroke-linejoin="round"%s/>' % (''.join(ds), col, w, ' stroke-dasharray="6 4"' if dash else ''))
    th['_above'] = ''.join(above)
    return ''.join(out)


def build_flat(dem, water, base, extent=None):
    """For a flat drawing: heights on the 5 m grid, water held at the land's usual level so quays cast no false slope, and a water mask."""
    if dem.crs == 'rd':
        corners = [_rd_to_local(dem.x0 + (dem.d['x1'] - dem.x0) * fx, dem.d['y0'] + (dem.y1 - dem.d['y0']) * fy) for fx in (0.0, 1.0) for fy in (0.0, 1.0)]
    else:
        corners = [((dem.d['w'] + (dem.d['e'] - dem.d['w']) * fx - A.LON0) * A.KX, (A.LAT0 - (dem.d['s'] + (dem.d['n'] - dem.d['s']) * fy)) * A.KZ) for fx in (0.0, 1.0) for fy in (0.0, 1.0)]
    x0, x1, z0, z1 = min(c[0] for c in corners), max(c[0] for c in corners), min(c[1] for c in corners), max(c[1] for c in corners)
    if extent: x0, x1, z0, z1 = max(x0, extent[0]), min(x1, extent[1]), max(z0, extent[2]), min(z1, extent[3])
    G = Grid(dem, x0, x1, z0, z1)
    wet = bytearray(G.nx * 2 * G.nz * 2)
    for poly in water:
        G.set_level(poly, base)
        for o, i0, i1 in G.cells(poly, 2): wet[o + i0:o + i1] = b'\x01' * (i1 - i0)
    G.fill(); G.wet = wet
    return G


def shade_flat(S, cam, th, cfg, scale=2):
    """Light and shade from the real relief, for a drawing that keeps the ground flat: each pixel's spot on the ground plane,
    lit by the slope the survey measured there. A PNG with transparency, to lie over the flat ground; water is left clear."""
    night = th['night']; L = th['_L']
    if night: return None
    G = S['_flat']; g, nx, nz, st = G.g, G.nx, G.nz, G.step
    sunny = L[1] > 0 and not th.get('sky_k')
    lv = vnorm(L) if sunny else vnorm((-0.6, 0.9, -0.6)); k_l = 0.8 if sunny else 0.55
    TX, TZ = cfg['target'][0], cfg['target'][2]; R = cfg['radius'] * 1.05
    W2, H2 = int(cam.W * scale), int(cam.H * scale)
    C = cam.C; r_, u_, f_ = cam.r, cam.u, cam.f
    x0, z0 = G.x0, G.z0; wet, wnx = G.wet, nx * 2
    rows = []
    for py in range(H2):
        row = bytearray(W2 * 4)
        dys = -((py + 0.5) / scale - cam.cy) / cam.focal
        for px in range(W2):
            dxs = ((px + 0.5) / scale - cam.cx) / cam.focal
            d1 = r_[1] * dxs + u_[1] * dys + f_[1]
            if d1 >= -1e-4: continue
            t = -C[1] / d1
            x = C[0] + (r_[0] * dxs + u_[0] * dys + f_[0]) * t; z = C[2] + (r_[2] * dxs + u_[2] * dys + f_[2]) * t
            if (x - TX) ** 2 + (z - TZ) ** 2 > R * R: continue
            wi, wj = int((x - x0) / st * 2), int((z - z0) / st * 2)
            if not (2 <= wi < wnx - 2 and 2 <= wj < nz * 2 - 2): continue
            q = wj * wnx + wi                                             # water, and a 5 m margin along it, stay clear
            if wet[q] or wet[q - 2] or wet[q + 2] or wet[q - 2 * wnx] or wet[q + 2 * wnx]: continue
            e = 3.0
            gx = (G.h(x + e, z) - G.h(x - e, z)) / (2 * e); gz = (G.h(x, z + e) - G.h(x, z - e)) / (2 * e)
            if gx * gx + gz * gz < 1e-4: continue                      # level ground: nothing to show
            nl = (-gx * lv[0] + lv[1] - gz * lv[2]) / math.sqrt(gx * gx + 1 + gz * gz)
            v = k_l * ((max(0.0, nl) if sunny else nl) - lv[1])
            a = min(0.42, abs(v) * 0.9)
            if a < 0.015: continue
            o = px * 4
            if v > 0: row[o:o + 4] = bytes((255, 248, 232, int(a * 255)))
            else: row[o:o + 4] = bytes((28, 36, 50, int(a * 255)))
        rows.append(row)
    from terrain import png_rgba
    return png_rgba(W2, H2, rows)
