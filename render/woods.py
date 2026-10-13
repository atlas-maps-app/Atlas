#!/usr/bin/env python3
"""Woods with trees in them. OSM maps a wood as its outline (landuse=forest, natural=wood); the trees inside are not mapped.
Here they are planted generically, and marked so: one tree about every 8.5 m on a jittered grid, 13 to 21 m tall with 6 to 9 m
crowns, kept off everything mapped inside the outline (paths by their width, buildings, water, rails and coaster tracks, and
smaller land inside the wood: lawns, playgrounds, squares). Drawn like model-railway trees, a few clumps of foliage each."""
import math, random
from real import pip

CLEAR = ('grass', 'garden', 'playground', 'pitch', 'pedestrian', 'parking', 'flowerbed', 'sand', 'park', 'track', 'meadow', 'grassland', 'bare_rock')

class Grid:
    def __init__(self, x0, x1, z0, z1, cell=2.0):
        self.x0, self.z0, self.c = x0, z0, cell
        self.nx, self.nz = int((x1 - x0) / cell) + 1, int((z1 - z0) / cell) + 1
        self.b = bytearray(self.nx * self.nz)
    def mark_disc(self, x, z, r):
        c = self.c; i0, i1 = int((x - r - self.x0) / c), int((x + r - self.x0) / c); j0, j1 = int((z - r - self.z0) / c), int((z + r - self.z0) / c)
        for j in range(max(0, j0), min(self.nz, j1 + 1)):
            for i in range(max(0, i0), min(self.nx, i1 + 1)):
                if (self.x0 + i * c - x) ** 2 + (self.z0 + j * c - z) ** 2 <= r * r: self.b[j * self.nx + i] = 1
    def mark_line(self, pts, r):
        for k in range(len(pts) - 1):
            a, b = pts[k], pts[k + 1]; n = max(1, int(math.dist(a, b) / (self.c * 0.8)))
            for t in range(n + 1):
                f = t / n; self.mark_disc(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, r)
    def mark_poly(self, poly, grow=0.0):
        zs = [p[1] for p in poly]; c = self.c
        for j in range(max(0, int((min(zs) - self.z0) / c)), min(self.nz, int((max(zs) - self.z0) / c) + 1)):
            z = self.z0 + j * c; xs = []
            for k in range(len(poly)):
                (ax, az), (bx, bz) = poly[k], poly[(k + 1) % len(poly)]
                if (az <= z < bz) or (bz <= z < az): xs.append(ax + (z - az) * (bx - ax) / (bz - az))
            xs.sort()
            for a, b in zip(xs[::2], xs[1::2]):
                for i in range(max(0, int((a - grow - self.x0) / c)), min(self.nx, int((b + grow - self.x0) / c) + 1)): self.b[j * self.nx + i] = 1
        if grow > 0: self.mark_line(list(poly) + [poly[0]], grow)
    def free(self, x, z):
        i, j = int(round((x - self.x0) / self.c)), int(round((z - self.z0) / self.c))
        return 0 <= i < self.nx and 0 <= j < self.nz and not self.b[j * self.nx + i]

def plant(S, cx, cz, rad, spacing=8.5, seed=7):
    G = Grid(cx - rad, cx + rad, cz - rad, cz + rad)
    near = lambda pts: any(abs(p[0] - cx) < rad + 40 and abs(p[1] - cz) < rad + 40 for p in pts[::max(1, len(pts) // 12)] + [pts[-1]])
    for b in S['buildings'] + S['parts']:
        if near(b['poly']): G.mark_poly(b['poly'], 3.0)
    for r in S['roads']:
        if near(r['pts']): G.mark_line(r['pts'], (r.get('w') or 2.0) / 2 + 1.6)
    for w in S['water']:
        if near(w): G.mark_poly(w, 1.5)
    for r in S.get('rail', []):
        if near(r['pts']): G.mark_line(r['pts'], 2.6)
    for tr in S.get('coasters', []):
        if near(tr['pts']): G.mark_line(tr['pts'], 3.2)
    for a in S['land']:
        if a['kind'] in CLEAR and near(a['poly']): G.mark_poly(a['poly'])
    rnd = random.Random(seed); trees = []
    woods = [a for a in S['land'] if a['kind'] in ('forest', 'wood') and near(a['poly'])]
    dz = spacing * math.sqrt(3) / 2
    for a in woods:
        xs = [p[0] for p in a['poly']]; zs = [p[1] for p in a['poly']]
        j = 0; z = max(min(zs), cz - rad)
        while z <= min(max(zs), cz + rad):
            x = max(min(xs), cx - rad) + (spacing / 2 if j % 2 else 0)
            while x <= min(max(xs), cx + rad):
                px_, pz_ = x + rnd.uniform(-2.6, 2.6), z + rnd.uniform(-2.2, 2.2)
                if math.hypot(px_ - cx, pz_ - cz) <= rad and G.free(px_, pz_) and pip(px_, pz_, a['poly']):
                    h = rnd.uniform(13, 21)
                    trees.append(dict(x=px_, z=pz_, h=h, crown=rnd.uniform(6.0, 9.0), id=rnd.randrange(1 << 30), generic=True))
                x += spacing
            z += dz; j += 1
    return trees

def draw(trees, cam, th, far, light, hx, tohex, mulc, vnorm):
    """Items (depth, 'svg', body) for each generic tree, and the shadow ellipses for the ground."""
    night = th['night']; L = th['_L']; items = []; shadows = []
    sun = (not night) and L[1] > 0.05
    tones = []
    for c0 in ('#3F5E34', '#4A6538', '#435F3C', '#526A3A', '#4B5F33'):          # a few generic greens, so a wood is not one colour
        base = hx(c0)
        tones.append((tohex(light(th, mulc(base, 0.6), (0, 1, 0), False)), tohex(light(th, base, vnorm((L[0], 0.8, L[2])), not night)),
                      tohex(light(th, mulc(base, 1.12), vnorm((L[0], 0.6, L[2])), not night))))
    for n in trees:
        dark, mid, hi = tones[n['id'] % len(tones)]
        if far(n['x'], n['z']): continue
        c = cam.cam((n['x'], n['h'] * 0.7, n['z']))
        if c[2] < 4: continue
        s = cam.focal / c[2]; r = n['crown'] / 2
        q0 = cam.proj((n['x'], n['h'] * 0.7, n['z']))
        if not q0 or not (-30 < q0[0] < cam.W + 30 and -30 < q0[1] < cam.H + 30): continue          # outside the picture: not written
        rnd = random.Random(n['id'])
        if sun:
            ox, oz = -L[0] / L[1] * n['h'] * 0.72, -L[2] / L[1] * n['h'] * 0.72
            sp = cam.poly([(n['x'] + ox + r * 0.95 * math.cos(a), 0.12, n['z'] + oz + r * 0.95 * math.sin(a)) for a in [k * math.pi / 6 for k in range(12)]])
            if sp: shadows.append('M' + 'L'.join('%.1f %.1f' % q for q in sp) + 'Z')
        under, top, glint = [], [], []
        for k in range(4):
            ang = rnd.uniform(0, 2 * math.pi); rad = r * 0.42 * math.sqrt(rnd.random())
            bx, bz = n['x'] + rad * math.cos(ang), n['z'] + rad * math.sin(ang); by = n['h'] * 0.7 + rnd.uniform(-0.25, 0.25) * r
            p = cam.proj((bx, by, bz))
            if not p: continue
            rr = r * rnd.uniform(0.5, 0.66) * s
            under.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0], p[1], rr))
            q = cam.proj((bx + L[0] * r * 0.3, by + L[1] * r * 0.3, bz + L[2] * r * 0.3)) if sun else None
            if q: top.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (q[0], q[1], rr * 0.74))
            if q and k == 0: glint.append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (q[0] + (q[0] - p[0]) * 0.5, q[1] + (q[1] - p[1]) * 0.5, rr * 0.36))
        if not under: continue
        body = '<g fill="%s">%s</g>' % (dark, ''.join(under))
        if top: body += '<g fill="%s">%s</g>' % (mid, ''.join(top))
        if glint: body += '<g fill="%s">%s</g>' % (hi, ''.join(glint))
        items.append((c[2], 'svg', body))
    return items, shadows
