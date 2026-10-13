#!/usr/bin/env python3
"""Tiny software renderer: procedural 3D city -> layered SVG (painter's algorithm)."""
import math, random, json, sys, os

# ---------------- vectors / colors ----------------
def vsub(a, b): return (a[0]-b[0], a[1]-b[1], a[2]-b[2])
def vdot(a, b): return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
def vcross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def vnorm(a):
    l = math.sqrt(vdot(a, a)); return (a[0]/l, a[1]/l, a[2]/l)
def hx(h):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))
def tohex(c): return '#%02x%02x%02x' % tuple(max(0, min(255, int(round(v)))) for v in c)
def mix(a, b, t): return tuple(a[i]+(b[i]-a[i])*t for i in range(3))
def mulc(a, s): return tuple(v*s for v in a)
def clamp(x, a=0.0, b=1.0): return max(a, min(b, x))
def P(sp): return 'M' + 'L'.join('%.1f %.1f' % p for p in sp) + 'Z'


class Camera:
    def __init__(s, target, yaw, pitch, dist, fov, W, H, cx=None, cy=None, near=0.6):
        yr, pr = math.radians(yaw), math.radians(pitch)
        d = (math.sin(yr)*math.cos(pr), -math.sin(pr), -math.cos(yr)*math.cos(pr))
        s.C = (target[0]-d[0]*dist, target[1]-d[1]*dist, target[2]-d[2]*dist)
        f = vnorm(d); r = vnorm((-f[2], 0.0, f[0])); u = vcross(r, f)
        s.f, s.r, s.u = f, r, u
        s.W, s.H = W, H
        s.focal = (H/2)/math.tan(math.radians(fov)/2)
        s.cx = W/2 if cx is None else cx
        s.cy = H/2 if cy is None else cy
        s.near = near

    def cam(s, p):
        d = vsub(p, s.C); return (vdot(d, s.r), vdot(d, s.u), vdot(d, s.f))

    def pc(s, c): return (s.cx+s.focal*c[0]/c[2], s.cy-s.focal*c[1]/c[2])

    def proj(s, p):
        c = s.cam(p)
        if c[2] < s.near: return None
        return s.pc(c)

    def depth(s, p): return s.cam(p)[2]

    def poly(s, pts, margin=40):
        cs = [s.cam(p) for p in pts]; out = []; n = len(cs)
        for i in range(n):
            a = cs[i]; b = cs[(i+1) % n]
            ai = a[2] >= s.near; bi = b[2] >= s.near
            if ai: out.append(a)
            if ai != bi:
                t = (s.near-a[2])/(b[2]-a[2])
                out.append((a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, s.near))
        if len(out) < 3: return None
        sp = [s.pc(c) for c in out]
        xs = [p[0] for p in sp]; ys = [p[1] for p in sp]
        if max(xs) < -margin or min(xs) > s.W+margin or max(ys) < -margin or min(ys) > s.H+margin:
            return None
        return sp

    def ground_depth(s, sy, gy=0.0):
        b = (s.cy-sy)/s.focal
        den = s.f[1]+b*s.u[1]
        if den >= -1e-6: return None
        return -(s.C[1]-gy)/den

    def horizon_y(s):
        b = -s.f[1]/s.u[1]
        return s.cy-b*s.focal


def area(sp):
    a = 0
    for i in range(len(sp)):
        x0, y0 = sp[i]; x1, y1 = sp[(i+1) % len(sp)]
        a += x0*y1-x1*y0
    return abs(a)/2


def hull2(pts):
    pts = sorted(set((round(p[0], 3), round(p[1], 3)) for p in pts))
    if len(pts) <= 2: return pts
    def cr(o, a, b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    lo = []
    for p in pts:
        while len(lo) >= 2 and cr(lo[-2], lo[-1], p) <= 0: lo.pop()
        lo.append(p)
    up = []
    for p in reversed(pts):
        while len(up) >= 2 and cr(up[-2], up[-1], p) <= 0: up.pop()
        up.append(p)
    return lo[:-1]+up[:-1]


def smooth(pts, r=12, seg=6):
    """Fillet corners of a 2D polyline."""
    if len(pts) < 3: return pts
    out = [pts[0]]
    for i in range(1, len(pts)-1):
        p0, p1, p2 = pts[i-1], pts[i], pts[i+1]
        d0 = (p1[0]-p0[0], p1[1]-p0[1]); l0 = math.hypot(*d0)
        d1 = (p2[0]-p1[0], p2[1]-p1[1]); l1 = math.hypot(*d1)
        rr = min(r, l0/2, l1/2)
        a = (p1[0]-d0[0]/l0*rr, p1[1]-d0[1]/l0*rr)
        b = (p1[0]+d1[0]/l1*rr, p1[1]+d1[1]/l1*rr)
        for k in range(seg+1):
            t = k/seg
            # quadratic bezier a -> p1 -> b
            x = (1-t)**2*a[0]+2*(1-t)*t*p1[0]+t*t*b[0]
            y = (1-t)**2*a[1]+2*(1-t)*t*p1[1]+t*t*b[1]
            out.append((x, y))
    out.append(pts[-1])
    return out


def ribbon(pts, w):
    left, right = [], []
    n = len(pts)
    for i in range(n):
        if i == 0:
            d = (pts[1][0]-pts[0][0], pts[1][1]-pts[0][1])
        elif i == n-1:
            d = (pts[-1][0]-pts[-2][0], pts[-1][1]-pts[-2][1])
        else:
            d = (pts[i+1][0]-pts[i-1][0], pts[i+1][1]-pts[i-1][1])
        l = math.hypot(*d) or 1
        nx, nz = -d[1]/l, d[0]/l
        left.append((pts[i][0]+nx*w/2, pts[i][1]+nz*w/2))
        right.append((pts[i][0]-nx*w/2, pts[i][1]-nz*w/2))
    return left+right[::-1]


def densify(pts, step=15):
    out = []
    for i in range(len(pts)-1):
        a, b = pts[i], pts[i+1]
        l = math.hypot(b[0]-a[0], b[1]-a[1]); n = max(1, int(l/step))
        for k in range(n):
            t = k/n; out.append((a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t))
    out.append(pts[-1])
    return out


# ---------------- world ----------------
AV_SP, AV_W, ST_SP, ST_W = 110, 20, 70, 14
SIDEWALK = 4.5

def shore_e(z): return -120 + 40*math.sin(z/260) + 20*math.sin(z/97)
def shore_w(z): return shore_e(z) - 560 + 25*math.sin(z/180+1.3)

PARK = (450, 650, -553, -357)          # Linden Park x0,x1,z0,z1
BRIDGE_Z = -1610

NAMED = {
    'lume':    dict(foot=(236, 264, -829, -803), h=13, kind='lume'),
    'hall':    dict(foot=(16, 96, -1039, -991), h=15, kind='hall'),
    'marlow':  dict(foot=(-88, -44, -619, -583), h=118, kind='glass'),
    'fern':    dict(foot=(404, 426, -128, -100), h=11, kind='normal'),
    'home':    dict(foot=(398, 426, 12, 40), h=16, kind='normal'),
}

ANCHORS = {
    'home': (440, 0, 30), 'lume': (250, 13, -816), 'lume_door': (250, 0, -838),
    'hall': (56, 15, -1015), 'marlow': (-66, 118, -601), 'fern': (415, 11, -114),
    'maya': (330, 0, -300), 'charger': (550, 0, -205), 'park': (550, 0, -455),
}

ROUTE_MAIN = [(440, 30), (440, -840), (250, -840)]
ROUTE_ALT = [(440, 30), (440, -140), (330, -140), (330, -840), (250, -840)]

CAR_COLS = ['#D8DDE4', '#3A3F46', '#B8322A', '#2E5BFF', '#F4F4F2', '#6B7282', '#1E3A5F', '#C9A36A', '#E8E8E4', '#2A2D33']
DAY_PAL = ['#F8F3EA', '#F4EEE4', '#F1E9DD', '#F6F1E8', '#EDE6DA', '#EBD9C8', '#F3ECE2', '#E6DCCF']
NIGHT_PAL = ['#1B2238', '#1E2540', '#192035', '#20283F', '#1C2236']


def rng_for(*k):
    return random.Random(hash(k) & 0xffffffff)


class Bldg:
    __slots__ = ('foot', 'y0', 'h', 'kind', 'col', 'id', 'named')
    def __init__(s, foot, h, kind, col, bid, y0=0.0, named=None):
        s.foot, s.h, s.kind, s.col, s.id, s.y0, s.named = foot, h, kind, col, bid, y0, named


def rect(x0, x1, z0, z1): return [(x0, z0), (x1, z0), (x1, z1), (x0, z1)]


def overlaps(a, b):
    return not (a[1] <= b[0] or b[1] <= a[0] or a[3] <= b[2] or b[3] <= a[2])


def build_city(region):
    """region: (xmin,xmax,zmin,zmax) to generate in."""
    rx0, rx1, rz0, rz1 = region
    pads, parks, bldgs, trees = [], [], [], []
    random.seed(7)
    named_rects = {k: v['foot'] for k, v in NAMED.items()}
    # ---- Brooklyn side
    for i in range(-3, 22):
        for j in range(-70, 18):
            x0, x1 = i*AV_SP+AV_W/2, (i+1)*AV_SP-AV_W/2
            z0, z1 = j*ST_SP+ST_W/2, (j+1)*ST_SP-ST_W/2
            if x1 < rx0 or x0 > rx1 or z1 < rz0 or z0 > rz1: continue
            xl = max(shore_e(z) for z in (z0, (z0+z1)/2, z1)) + 38
            if x1 < xl+22: continue
            if x0 < xl: x0 = xl+2
            if abs((z0+z1)/2 - BRIDGE_Z) < 40 and x0 < 330: continue  # bridge ramp street
            if x0 >= PARK[0]-1 and x1 <= PARK[1]+1 and z0 >= PARK[2]-1 and z1 <= PARK[3]+1:
                continue
            rg = rng_for('b', i, j)
            pads.append(rect(x0, x1, z0, z1))
            if rg.random() < 0.035 and i > 1:
                parks.append(('plaza', rect(x0, x1, z0, z1)))
                for _ in range(10):
                    trees.append((rg.uniform(x0+4, x1-4), rg.uniform(z0+4, z1-4), rg.uniform(3, 4.5)))
                continue
            bx0, bx1, bz0, bz1 = x0+SIDEWALK, x1-SIDEWALK, z0+SIDEWALK, z1-SIDEWALK
            waterfront = (bx0 < 170 and -1500 < z0 < -150)
            north = z0 < -2600
            rows = [(bz0, (bz0+bz1)/2-0.6), ((bz0+bz1)/2+0.6, bz1)]
            if rg.random() < 0.12: rows = [(bz0, bz1)]
            for (rz_0, rz_1) in rows:
                x = bx0
                while x < bx1-6:
                    w = rg.choice([14, 16, 18, 20, 22, 25, 28, 32])
                    if waterfront and rg.random() < 0.15: w = rg.choice([36, 44, 52])
                    xe = min(bx1, x+w)
                    if bx1-xe < 9: xe = bx1
                    fr = (x, xe, rz_0, rz_1)
                    x = xe+0.8
                    if any(overlaps(fr, nr) for nr in named_rects.values()): continue
                    if rg.random() < 0.05: continue
                    r = rg.random()
                    if waterfront and bx0 < 120 and r < 0.13:
                        h = rg.uniform(55, 135); kind = 'glass'
                    elif north and r < 0.45:
                        h = rg.uniform(70, 210); kind = 'glass'
                    elif r < 0.07:
                        h = rg.uniform(28, 46); kind = 'normal'
                    else:
                        h = rg.choice([10, 12, 13, 15, 16, 18, 20, 22]) + rg.uniform(-1, 1); kind = 'normal'
                    bldgs.append(Bldg(rect(*fr), h, kind, rg.randrange(1000), ('b', i, j, round(x))))
    # named
    for k, v in NAMED.items():
        f = v['foot']
        bldgs.append(Bldg(rect(*f), v['h'], v['kind'], 3, ('n', k), named=k))
        if k == 'marlow':
            bldgs.append(Bldg(rect(f[0]-6, f[1]+6, f[2]-6, f[3]+6), 14, 'normal', 1, ('n', 'marlow-pod'), named='marlow'))
    # ---- far shore (the big city)
    for i in range(-40, 0):
        for j in range(-60, 20):
            x0, x1 = i*150+12, (i+1)*150-12
            z0, z1 = j*80+8, (j+1)*80-8
            if x1 < rx0 or x0 > rx1 or z1 < rz0 or z0 > rz1: continue
            xl = min(shore_w(z) for z in (z0, z1)) - 34
            if x0 > xl-30: continue
            if x1 > xl: x1 = xl
            rg = rng_for('m', i, j)
            pads.append(rect(x0, x1, z0, z1))
            bx0, bx1, bz0, bz1 = x0+4, x1-4, z0+4, z1-4
            x = bx0
            while x < bx1-10:
                w = rg.choice([26, 32, 38, 44])
                xe = min(bx1, x+w)
                if bx1-xe < 14: xe = bx1
                zs = [(bz0, bz1)] if rg.random() < 0.5 else [(bz0, (bz0+bz1)/2-1), ((bz0+bz1)/2+1, bz1)]
                for (a, b) in zs:
                    r = rg.random()
                    if r < 0.05: h = rg.uniform(300, 430)
                    elif r < 0.45: h = rg.uniform(90, 220)
                    else: h = rg.uniform(35, 90)
                    kind = 'glass' if h > 120 or rg.random() < 0.3 else 'normal'
                    bldgs.append(Bldg(rect(x, xe, a, b), h, kind, rg.randrange(1000), ('m', i, j, round(x), round(a))))
                x = xe+1.5
    # ---- park + esplanade trees
    parks.append(('park', rect(*[PARK[0], PARK[1], PARK[2], PARK[3]])))
    rg = random.Random(11)
    for gx in range(int(PARK[0])+6, int(PARK[1])-4, 11):
        for gz in range(int(PARK[2])+6, int(PARK[3])-4, 11):
            x, z = gx+rg.uniform(-4, 4), gz+rg.uniform(-4, 4)
            if 520 < x < 600 and -500 < z < -420: continue  # lawn / fields
            if abs(x-550) < 4 or abs(z-(-455)) < 4: continue  # paths
            if rg.random() < 0.8:
                trees.append((x, z, rg.uniform(3.2, 5.2)))
    esp = []
    zz = rz0
    left, right = [], []
    z = max(rz0, -5000)
    while z <= min(rz1, 2000):
        left.append((shore_e(z), z)); right.append((shore_e(z)+34, z)); z += 40
    esp = left+right[::-1]
    for z in range(int(max(rz0, -5000)), int(min(rz1, 2000)), 13):
        if abs(z-BRIDGE_Z) < 30: continue
        if rg.random() < 0.75:
            trees.append((shore_e(z)+rg.uniform(10, 26), z+rg.uniform(-3, 3), rg.uniform(3, 4.6)))
    # street trees near core
    for i in range(-1, 9):
        for j in range(-18, 3):
            x0, x1 = i*AV_SP+AV_W/2, (i+1)*AV_SP-AV_W/2
            z0, z1 = j*ST_SP+ST_W/2, (j+1)*ST_SP-ST_W/2
            xl = max(shore_e(z) for z in (z0, z1)) + 38
            if x0 < xl+2: continue
            if x0 >= PARK[0]-1 and x1 <= PARK[1]+1 and z0 >= PARK[2]-1 and z1 <= PARK[3]+1: continue
            rg2 = rng_for('t', i, j)
            for x in range(int(x0)+6, int(x1)-4, 12):
                for zz in (z0+2, z1-2):
                    if rg2.random() < 0.55: trees.append((x+rg2.uniform(-1, 1), zz, rg2.uniform(2.6, 3.6)))
            for z in range(int(z0)+6, int(z1)-4, 12):
                for xx in (x0+2, x1-2):
                    if rg2.random() < 0.4: trees.append((xx, z+rg2.uniform(-1, 1), rg2.uniform(2.6, 3.6)))
    # water polygon
    wl, wr = [], []
    z = -9000
    while z <= 4000:
        wl.append((shore_w(z), z)); wr.append((shore_e(z), z)); z += 50
    water = wr+wl[::-1]
    return dict(pads=pads, parks=parks, bldgs=bldgs, trees=trees, water=water, esp=esp)


# ---------------- themes ----------------
def L_(x, y, z): return vnorm((x, y, z))

DAY = dict(
    name='day',
    L=L_(-0.35, 0.6, 0.72),
    sky_top='#A9C4E6', sky_hor='#F4E7D9', fogc='#EFE5DA',
    fog0=900, fog1=7000, fog_max=0.78, fog_pow=0.9,
    street='#D3CEC4', pad='#E8E4DC', park='#B5D59A', plaza='#E2DFD2', esp='#C3DCAE',
    water='#9DC2E2', water_edge='#8DB5DA',
    tree='#7DAE68', tree_hi='#A2CC88', tree_lo='#5C8C50',
    shadow='#3A4570', shadow_op=0.2,
    amb=0.68, dif=0.36, shade_tint='#A7AECB', shade_mix=0.36, warm='#FFD8AE', warm_mix=0.24,
    top_tint='#FFF4E2', top_mix=0.3, top_k=1.03,
    glass='#CAD8E8', glass2='#BCCDE0', lume='#F0A57A', hall='#E9D6BE',
    detail=True, night=False,
)

NIGHT = dict(
    name='night',
    L=L_(0.3, 0.8, -0.5),
    sky_top='#04070F', sky_hor='#1D2541', fogc='#1A223C',
    fog0=500, fog1=4200, fog_max=0.85, fog_pow=0.9,
    street='#0C1120', pad='#10172A', park='#0E1A20', plaza='#131A2C', esp='#0F1C22',
    water='#070B16', water_edge='#0A1020',
    tree='#11232A', tree_hi='#183139', tree_lo='#0C1B20',
    shadow='#000000', shadow_op=0.0,
    amb=0.92, dif=0.16, shade_tint='#0B0F1C', shade_mix=0.25, warm='#2B3558', warm_mix=0.3,
    top_tint='#2A3452', top_mix=0.35, top_k=1.0,
    glass='#1A2442', glass2='#152039', lume='#6A4A3A', hall='#2A2A38',
    detail=False, night=True,
)


def fogmix(th, c, d):
    t = clamp((d-th['fog0'])/(th['fog1']-th['fog0']))
    t = (t**th['fog_pow'])*th['fog_max']
    return mix(c, hx(th['fogc']), t), t


def face_color(th, base, n, d, top=False):
    L = th['L']
    if top:
        c = mix(base, hx(th['top_tint']), th['top_mix']); c = mulc(c, th['top_k'])
    else:
        lam = max(0.0, vdot(n, L))
        c = mulc(base, th['amb']+th['dif']*lam)
        c = mix(c, hx(th['shade_tint']), th['shade_mix']*(1-lam))
        c = mix(c, hx(th['warm']), th['warm_mix']*lam)
    return fogmix(th, c, d)


def base_color(th, b, highlight=None):
    if b.named == 'lume' and highlight == 'lume': return hx(th['lume'])
    if b.named == 'marlow' and highlight == 'marlow': return hx('#F2B48E') if not th['night'] else hx('#6A4A3A')
    if b.named == 'hall': return hx(th['hall'])
    if b.kind == 'car': return hx(CAR_COLS[b.col % len(CAR_COLS)])
    if b.kind == 'garage': return hx(th.get('garage', '#9FB0CC'))
    if b.kind == 'glass':
        if th.get('pal') and th.get('glass_from_pal'): return hx(th['pal'][b.col % len(th['pal'])])
        return hx(th['glass'] if b.col % 2 else th['glass2'])
    pal = th.get('pal') or (DAY_PAL if not th['night'] else NIGHT_PAL)
    return hx(pal[b.col % len(pal)])


# ---------------- scene rendering ----------------
def render(cfg, city, name):
    W, H = cfg['W'], cfg['H']
    th = cfg['theme']
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], W, H,
                 cx=cfg.get('cx'), cy=cfg.get('cy'))
    hl = cfg.get('highlight')
    defs, ground, shadows, groundtop, items = [], [], [], [], []
    hy = cam.horizon_y()
    # base
    ground.append('<rect width="%d" height="%d" fill="%s"/>' % (W, H, th['street']))
    # water
    sp = cam.poly([(x, 0, z) for (x, z) in city['water']], margin=4000)
    if sp: ground.append('<path d="%s" fill="%s"/>' % (P(sp), th['water']))
    if th.get('chart'):
        zs = list(range(-9000, 4001, 50))
        for (fa, fb, col) in ((lambda z: shore_e(z), lambda z: shore_e(z)-120, th['shallow2']),
                              (lambda z: shore_w(z), lambda z: shore_w(z)+120, th['shallow2']),
                              (lambda z: shore_e(z), lambda z: shore_e(z)-55, th['shallow']),
                              (lambda z: shore_w(z), lambda z: shore_w(z)+55, th['shallow'])):
            poly = [(fa(z), z) for z in zs]+[(fb(z), z) for z in reversed(zs)]
            sp = cam.poly([(x, 0.01, z) for (x, z) in poly], margin=4000)
            if sp: ground.append('<path d="%s" fill="%s"/>' % (P(sp), col))
        for off in (-120, 120):
            f = (lambda z: shore_e(z)-120) if off < 0 else (lambda z: shore_w(z)+120)
            pts = [cam.proj((f(z), 0.02, z)) for z in zs]
            pts = [p for p in pts if p and -400 < p[0] < W+400 and -400 < p[1] < H+400]
            if len(pts) > 1:
                ground.append('<path d="M%s" fill="none" stroke="%s" stroke-width=".8" stroke-dasharray="4 3"/>' % ('L'.join('%.1f %.1f' % p for p in pts), th['contour']))
    # pads
    padp = []
    for pd in city['pads']:
        sp = cam.poly([(x, 0, z) for (x, z) in pd])
        if sp and area(sp) > 0.3: padp.append(P(sp))
    ground.append('<path d="%s" fill="%s"/>' % (''.join(padp), th['pad']))
    sp = cam.poly([(x, 0, z) for (x, z) in city['esp']], margin=4000)
    if sp: ground.append('<path d="%s" fill="%s"/>' % (P(sp), th['esp']))
    for kind, pk in city['parks']:
        sp = cam.poly([(x, 0, z) for (x, z) in pk])
        if sp: ground.append('<path d="%s" fill="%s"/>' % (P(sp), th['park'] if kind == 'park' else th['plaza']))
    # park paths + lawn
    if True:
        for seg in ([(550, PARK[2]), (550, PARK[3])], [(PARK[0], -455), (PARK[1], -455)]):
            rb = ribbon(seg, 4)
            sp = cam.poly([(x, 0.05, z) for (x, z) in rb])
            if sp: ground.append('<path d="%s" fill="%s"/>' % (P(sp), th['pad']))
        sp = cam.poly([(x, 0.04, z) for (x, z) in rect(522, 598, -498, -422)])
        if sp: ground.append('<path d="%s" fill="%s"/>' % (P(sp), tohex(mix(hx(th['park']), (255, 255, 255), 0.14 if not th['night'] else 0.03))))

    L = th['L']
    def shoff(y): return (-L[0]/L[1]*y, -L[2]/L[1]*y)

    # night light pools & lamps
    if th['night'] and not th.get('no_lamps'):
        defs.append('<radialGradient id="pool"><stop offset="0" stop-color="#FFB45E" stop-opacity=".55"/><stop offset="1" stop-color="#FFB45E" stop-opacity="0"/></radialGradient>')
        defs.append('<radialGradient id="halo"><stop offset="0" stop-color="#FFE6B8" stop-opacity=".9"/><stop offset=".25" stop-color="#FFD38A" stop-opacity=".35"/><stop offset="1" stop-color="#FFC56E" stop-opacity="0"/></radialGradient>')
        lamps = []
        fx, fz = cfg['target'][0], cfg['target'][2]
        for i in range(-2, 10):
            x = i*AV_SP
            for z in range(-2000, 300, 32):
                for s in (-1, 1):
                    lamps.append((x+s*(AV_W/2+1.5), z+ (8 if s > 0 else 24)))
        for j in range(-30, 4):
            z = j*ST_SP
            for x in range(-40, 900, 34):
                lamps.append((x+(8 if j % 2 else 20), z+ST_W/2+1.2))
        for (x, z) in lamps:
            if x < shore_e(z)+30: continue
            c = cam.cam((x, 0, z))
            if c[2] < 2 or c[2] > 1500: continue
            p = cam.pc(c)
            if not (-60 < p[0] < W+60 and -60 < p[1] < H+60): continue
            rx = 8*cam.focal/c[2]
            q = cam.proj((x, 0, z+8)) or p
            ry = max(0.4, abs(q[1]-p[1]))
            a = 0.8*clamp(1-(c[2]-200)/1300)
            ground.append('<ellipse cx="%.1f" cy="%.1f" rx="%.1f" ry="%.1f" fill="url(#pool)" opacity="%.2f"/>' % (p[0], p[1], rx, ry, a))
            items.append((c[2]-0.5, 'lamp', (x, z)))

    # shadows
    if th['shadow_op'] > 0:
        for b in city['bldgs']:
            if b.id[0] == 'm': continue
            o0, o1 = shoff(b.y0), shoff(b.y0+b.h)
            pts = [(x+o0[0], z+o0[1]) for (x, z) in b.foot]+[(x+o1[0], z+o1[1]) for (x, z) in b.foot]
            hl2 = hull2(pts)
            sp = cam.poly([(x, 0.02, z) for (x, z) in hl2])
            if sp and area(sp) > cfg.get('min_area', 0.5): shadows.append(P(sp))
        for (x, z, r) in city['trees']:
            o = shoff(6)
            pts = [(x+o[0]+r*math.cos(a), 0.02, z+o[1]+r*math.sin(a)) for a in [k*math.pi/4 for k in range(8)]]
            sp = cam.poly(pts)
            if sp and area(sp) > cfg.get('min_area', 0.3): shadows.append(P(sp))

    # routes (ground)
    route_svgs = []
    for rt in cfg.get('routes', []):
        pts = densify(smooth(rt['pts'], rt.get('r', 14)), 10)
        for (w, col, op) in rt['layers']:
            rb = ribbon(pts, w)
            sp = cam.poly([(x, 0.25, z) for (x, z) in rb], margin=2000)
            if sp:
                s = '<path d="%s" fill="%s"%s/>' % (P(sp), col, '' if op >= 1 else ' opacity="%.2f"' % op)
                groundtop.append(s)
                if rt.get('xray'): route_svgs.append((sp, col, rt.get('xray')))

    ringtop = []
    for rg_ in cfg.get('rings', []):
        (x, z, r, fill, fop, stroke, dash) = rg_
        if isinstance(r, (int, float)):
            pts = [(x+r*math.cos(a), 0.3, z+r*math.sin(a)) for a in [k*2*math.pi/72 for k in range(72)]]
        else:
            pts = [(px, 0.3, pz) for (px, pz) in r]
        sp = cam.poly(pts, margin=3000)
        if sp:
            (ringtop if cfg.get('rings_top') else groundtop).append('<path d="%s" fill="%s" fill-opacity="%.2f" stroke="%s" stroke-width="1.4"%s/>' % (P(sp), fill, fop, stroke, ' stroke-dasharray="%s"' % dash if dash else ''))
    # markings (night nav)
    for mk in cfg.get('markings', []):
        (x0, z0), (x1, z1), dash, gap, w, col, op = mk
        l = math.hypot(x1-x0, z1-z0); ux, uz = (x1-x0)/l, (z1-z0)/l
        t = 0
        while t < l:
            a = (x0+ux*t, z0+uz*t); b = (x0+ux*min(l, t+dash), z0+uz*min(l, t+dash))
            rb = ribbon([a, b], w)
            sp = cam.poly([(x, 0.1, z) for (x, z) in rb])
            if sp: groundtop.append('<path d="%s" fill="%s" opacity="%.2f"/>' % (P(sp), col, op))
            t += dash+gap
    for cw in cfg.get('crosswalks', []):
        (cx0, cz0), along, n = cw
        for k in range(n):
            if along == 'x':
                r = rect(cx0+k*1.6, cx0+k*1.6+0.8, cz0, cz0+4)
            else:
                r = rect(cx0, cx0+4, cz0+k*1.6, cz0+k*1.6+0.8)
            sp = cam.poly([(x, 0.1, z) for (x, z) in r])
            if sp: groundtop.append('<path d="%s" fill="#ffffff" opacity="%.2f"/>' % (P(sp), 0.16 if th['night'] else 0.7))

    # buildings -> items
    for b in city['bldgs']:
        cxz = (sum(p[0] for p in b.foot)/len(b.foot), sum(p[1] for p in b.foot)/len(b.foot))
        c = cam.cam((cxz[0], b.y0+b.h*0.5, cxz[1]))
        if c[2] < -60: continue
        if c[2] > th['fog1']*1.05: continue
        items.append((c[2], 'b', b))
    for (x, z, r) in city['trees']:
        c = cam.cam((x, 6, z))
        if c[2] < 1 or c[2] > 1800: continue
        items.append((c[2], 't', (x, z, r)))
    for extra in cfg.get('extras', []):
        items.append((cam.depth(extra['at']), 'x', extra))
    # bridge
    for seg in bridge_parts():
        c = cam.cam(seg['c'])
        if c[2] < 0 or c[2] > th['fog1']: continue
        items.append((c[2], 'g', seg))

    items.sort(key=lambda it: -it[0])
    out_items = []
    rngw = random.Random(3)
    for d, kind, obj in items:
        if kind == 'b':
            out_items.extend(emit_bldg(cam, th, obj, d, hl, cfg))
        elif kind == 't':
            x, z, r = obj
            c = cam.cam((x, 6, z))
            p = cam.pc(c); rr = r*cam.focal/c[2]
            if rr < cfg.get('min_tree', 0.35) or not (-20 < p[0] < W+20 and -20 < p[1] < H+20): continue
            col, _ = fogmix(th, hx(th['tree']), d)
            hi, _ = fogmix(th, hx(th['tree_hi']), d)
            lo, _ = fogmix(th, hx(th['tree_lo']), d)
            out_items.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (p[0], p[1]+rr*0.12, rr, tohex(lo)))
            out_items.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (p[0]-rr*0.08, p[1]-rr*0.05, rr*0.86, tohex(col)))
            if rr > 1.2:
                out_items.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (p[0]-rr*0.3, p[1]-rr*0.32, rr*0.42, tohex(hi)))
        elif kind == 'lamp':
            x, z = obj
            c = cam.cam((x, 7, z))
            if c[2] < 1: continue
            p = cam.pc(c); rr = max(0.7, 0.35*cam.focal/c[2])
            out_items.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#halo)"/>' % (p[0], p[1], rr*9))
            out_items.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#FFF1D6"/>' % (p[0], p[1], rr))
        elif kind == 'g':
            out_items.extend(emit_bridge(cam, th, obj, d))
        elif kind == 'x':
            out_items.extend(obj['emit'](cam, th))

    # x-ray route over buildings
    xray = []
    for (sp, col, op) in route_svgs:
        xray.append('<path d="%s" fill="%s" opacity="%.2f"/>' % (P(sp), col, op))

    sound = []
    for (x, z, t) in cfg.get('soundings', []):
        c = cam.cam((x, 0, z))
        if c[2] < 5: continue
        p = cam.pc(c)
        if not (0 < p[0] < W and 0 < p[1] < H): continue
        fs = clamp(9.5*480/c[2], 5.5, 13)
        sound.append('<text x="%.1f" y="%.1f" font-size="%.1f" fill="#4E6B85" fill-opacity="%.2f" font-style="italic" text-anchor="middle" font-family="Georgia,serif">%s</text>' % (p[0], p[1], fs, clamp(1.1-c[2]/2600, 0.25, 0.9), t))
    # fog overlay + sky
    fog_stops = []
    top = max(0, hy)
    n = 28
    for k in range(n+1):
        sy = top+(H-top)*k/n
        gd = cam.ground_depth(sy+0.01)
        if gd is None: a = th['fog_max']
        else:
            _, a = fogmix(th, (0, 0, 0), gd)
        fog_stops.append('<stop offset="%.3f" stop-color="%s" stop-opacity="%.3f"/>' % (k/n, th['fogc'], a))
    defs.append('<linearGradient id="fog" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%d">%s</linearGradient>' % (top, H, ''.join(fog_stops)))
    fog_rect = '<rect y="%.1f" width="%d" height="%.1f" fill="url(#fog)"/>' % (top, W, H-top)
    sky = ''
    if hy > 0:
        defs.append('<linearGradient id="sky" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%.1f"><stop offset="0" stop-color="%s"/><stop offset=".72" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (hy-cam.focal*0.9, hy, th['sky_top'], tohex(mix(hx(th['sky_top']), hx(th['sky_hor']), 0.7)), th['sky_hor']))
        sky = '<rect width="%d" height="%.1f" fill="url(#sky) %s"/>' % (W, hy+1, th['sky_hor'])

    shadow_g = ''
    if shadows:
        shadow_g = '<g fill="%s" opacity="%.2f"><path d="%s"/></g>' % (th['shadow'], th['shadow_op'], ''.join(shadows))
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d">' % (W, H, W, H),
           '<defs><clipPath id="vb"><rect width="%d" height="%d"/></clipPath>%s</defs><g clip-path="url(#vb)">' % (W, H, ''.join(defs)),
           ''.join(ground), shadow_g, ''.join(groundtop), fog_rect, sky,
           '<g stroke-width=".5" stroke-linejoin="round">', ''.join(out_items), '</g>',
           ''.join(xray), ''.join(ringtop), ''.join(sound), ''.join(cfg.get('overlay_svg', [])), '</g></svg>']
    svg = ''.join(svg)
    # anchors
    anc = {}
    for k, p in list(ANCHORS.items())+list(cfg.get('anchors', {}).items()):
        q = cam.proj(p)
        if q: anc[k] = [round(q[0], 1), round(q[1], 1)]
    return svg, anc, cam


def face_svg(th, sp, hc, top):
    e = th.get('edge_top' if top else 'edge_side')
    if e:
        return '<path d="%s" fill="%s" stroke="%s" stroke-opacity="%s" stroke-width="%s"/>' % (P(sp), hc, e, th.get('edge_op', '.8'), th.get('edge_w', '.8'))
    return '<path d="%s" fill="%s" stroke="%s"/>' % (P(sp), hc, hc)


def emit_bldg(cam, th, b, d, hl, cfg):
    out = []
    base = base_color(th, b, hl)
    rgb = rng_for('c', b.id)
    base = mulc(base, 1+rgb.uniform(-0.025, 0.02))
    foot = b.foot; n = len(foot)
    cxz = (sum(p[0] for p in foot)/n, sum(p[1] for p in foot)/n)
    y0, y1 = b.y0, b.y0+b.h
    for i in range(n):
        a = foot[i]; bb = foot[(i+1) % n]
        ex, ez = bb[0]-a[0], bb[1]-a[1]
        nrm = vnorm((ez, 0, -ex))
        mid = ((a[0]+bb[0])/2, (a[1]+bb[1])/2)
        if (mid[0]-cxz[0])*nrm[0]+(mid[1]-cxz[1])*nrm[2] < 0: nrm = (-nrm[0], 0, -nrm[2])
        fp = (mid[0], (y0+y1)/2, mid[1])
        if vdot(nrm, vsub(cam.C, fp)) <= 0: continue
        quad = [(a[0], y0, a[1]), (bb[0], y0, bb[1]), (bb[0], y1, bb[1]), (a[0], y1, a[1])]
        sp = cam.poly(quad)
        if not sp: continue
        ar = area(sp)
        if ar < cfg.get('min_area', 0.5): continue
        col, fg = face_color(th, base, nrm, d)
        hc = tohex(col)
        out.append(face_svg(th, sp, hc, False))
        # contact shade at base
        if not th['night'] and ar > 30 and y0 == 0:
            q2 = [(a[0], 0, a[1]), (bb[0], 0, bb[1]), (bb[0], min(3.5, b.h*0.3), bb[1]), (a[0], min(3.5, b.h*0.3), a[1])]
            sp2 = cam.poly(q2)
            if sp2: out.append('<path d="%s" fill="#2B3350" opacity="%.2f"/>' % (P(sp2), 0.07*(1-fg)))
        # details
        elen = math.hypot(ex, ez)
        if th['night']:
            if ar > 50: out.extend(windows(cam, th, b, a, bb, y0, y1, elen, d, fg, ar))
        elif th['detail'] and ar > 160 and d < 1100:
            out.extend(day_lines(cam, th, b, a, bb, y0, y1, elen, col, fg))
    if cam.C[1] > y1:
        sp = cam.poly([(x, y1, z) for (x, z) in foot])
        if sp:
            col, fg = face_color(th, base, (0, 1, 0), d, top=True)
            hc = tohex(col)
            out.append(face_svg(th, sp, hc, True))
            # roof details for close ups
            if cfg.get('roof_details') and d < cfg.get('roof_details') and b.kind == 'normal' and b.h < 40:
                out.extend(roof_bits(cam, th, b, base, d))
    return out


def roof_bits(cam, th, b, base, d):
    out = []
    rg = rng_for('r', b.id)
    xs = [p[0] for p in b.foot]; zs = [p[1] for p in b.foot]
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    y = b.y0+b.h
    # parapet inner shade (inset roof)
    inset = rect(x0+0.7, x1-0.7, z0+0.7, z1-0.7)
    sp = cam.poly([(x, y, z) for (x, z) in inset])
    if sp:
        col, _ = fogmix(th, mulc(mix(base, hx(th['top_tint']), 0.2), 0.95), d)
        out.append('<path d="%s" fill="%s"/>' % (P(sp), tohex(col)))
    for _ in range(rg.randint(1, 3)):
        w, dd, hh = rg.uniform(2, 4), rg.uniform(1.5, 3), rg.uniform(1, 2)
        bx, bz = rg.uniform(x0+1.5, max(x0+1.6, x1-1.5-w)), rg.uniform(z0+1.5, max(z0+1.6, z1-1.5-dd))
        bb = Bldg(rect(bx, bx+w, bz, bz+dd), hh, 'normal', 0, ('rb', b.id, bx), y0=y)
        out.extend(emit_simple(cam, th, bb, mulc(base, 0.97), d))
    if rg.random() < 0.45 and (x1-x0) > 12:
        # water tower
        cx, cz = rg.uniform(x0+4, x1-4), rg.uniform(z0+4, z1-4)
        yb = y+2.6
        for (lx, lz) in ((-1.4, -1.4), (1.4, -1.4), (1.4, 1.4), (-1.4, 1.4)):
            p0 = cam.proj((cx+lx, y, cz+lz)); p1 = cam.proj((cx+lx, yb, cz+lz))
            if p0 and p1: out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#6B5A4E" stroke-width="%.1f" fill="none"/>' % (p0[0], p0[1], p1[0], p1[1], max(0.4, 0.25*cam.focal/d)))
        foot = [(cx+2.2*math.cos(a), cz+2.2*math.sin(a)) for a in [k*2*math.pi/10 for k in range(10)]]
        wt = Bldg(foot, 4.2, 'normal', 0, ('wt', b.id), y0=yb)
        out.extend(emit_simple(cam, th, wt, hx('#B7967C'), d, apex=(cx, yb+4.2+1.8, cz)))
    return out


def emit_simple(cam, th, b, base, d, apex=None):
    out = []
    foot = b.foot; n = len(foot)
    cxz = (sum(p[0] for p in foot)/n, sum(p[1] for p in foot)/n)
    y0, y1 = b.y0, b.y0+b.h
    for i in range(n):
        a = foot[i]; bb = foot[(i+1) % n]
        ex, ez = bb[0]-a[0], bb[1]-a[1]
        nrm = vnorm((ez, 0, -ex))
        mid = ((a[0]+bb[0])/2, (a[1]+bb[1])/2)
        if (mid[0]-cxz[0])*nrm[0]+(mid[1]-cxz[1])*nrm[2] < 0: nrm = (-nrm[0], 0, -nrm[2])
        if vdot(nrm, vsub(cam.C, (mid[0], (y0+y1)/2, mid[1]))) <= 0: continue
        sp = cam.poly([(a[0], y0, a[1]), (bb[0], y0, bb[1]), (bb[0], y1, bb[1]), (a[0], y1, a[1])])
        if not sp: continue
        col, _ = face_color(th, base, nrm, d)
        hc = tohex(col)
        out.append(face_svg(th, sp, hc, False))
    if apex:
        tris = []
        for i in range(n):
            a = foot[i]; bb = foot[(i+1) % n]
            p = [(a[0], y1, a[1]), (bb[0], y1, bb[1]), apex]
            nr = vnorm(vcross(vsub(p[1], p[0]), vsub(p[2], p[0])))
            if nr[1] < 0: nr = tuple(-v for v in nr)
            cen = ((a[0]+bb[0]+apex[0])/3, (y1*2+apex[1])/3, (a[1]+bb[1]+apex[2])/3)
            if vdot(nr, vsub(cam.C, cen)) <= 0: continue
            sp = cam.poly(p)
            if not sp: continue
            col, _ = face_color(th, mulc(base, 0.9), nr, d)
            hc = tohex(col)
            tris.append(face_svg(th, sp, hc, False))
        out.extend(tris)
    elif cam.C[1] > y1:
        sp = cam.poly([(x, y1, z) for (x, z) in foot])
        if sp:
            col, _ = face_color(th, base, (0, 1, 0), d, top=True)
            hc = tohex(col)
            out.append(face_svg(th, sp, hc, True))
    return out


def day_lines(cam, th, b, a, bb, y0, y1, elen, col, fg):
    segs = []
    step = 4.0 if b.kind == 'glass' else 3.6
    y = y0+step
    while y < y1-0.8:
        p0 = cam.proj((a[0], y, a[1])); p1 = cam.proj((bb[0], y, bb[1]))
        if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
        y += step
    if b.kind == 'glass':
        k = int(elen/3.2)
        for i in range(1, k):
            t = i/k
            x, z = a[0]+(bb[0]-a[0])*t, a[1]+(bb[1]-a[1])*t
            p0 = cam.proj((x, y0, z)); p1 = cam.proj((x, y1, z))
            if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
    if not segs: return []
    dc = tohex(mulc(col, 0.9 if b.kind == 'glass' else 0.93))
    return ['<path d="%s" stroke="%s" stroke-width=".45" fill="none" opacity="%.2f"/>' % (''.join(segs), dc, 0.9*(1-fg))]


WIN_COLS = ['#FFD08A', '#FFC56F', '#FFE2AE', '#FFB85C', '#CFE0FF', '#FFD9A0']

def windows(cam, th, b, a, bb, y0, y1, elen, d, fg, ar):
    out = []
    rg = rng_for('w', b.id, round(a[0]), round(a[1]))
    tall = b.h > 60
    fh = 3.8 if tall else 3.5
    cw = 3.4 if tall else 4.2
    floors = int((y1-y0-2)/fh); cols = int((elen-1.5)/cw)
    if floors <= 0 or cols <= 0: return out
    p_lit = 0.34 if not tall else 0.42
    budget = int(min(160, ar/45)) if d < 500 else int(min(40, ar/80))
    budget = int(budget*th.get('win_scale', 1.0))
    p_lit = p_lit if d < 500 else min(0.95, p_lit*4)
    count = 0
    paths = {}
    ww = 2.2 if not tall else 2.6
    for fl in range(floors):
        yb = y0+1.6+fl*fh; yt = yb+(1.9 if not tall else 2.6)
        if fl == 0 and not tall and d < 600:
            # storefront band
            if rg.random() < 0.55:
                u0, u1 = 0.08, 0.92
                q = [(a[0]+(bb[0]-a[0])*u0, 0.4, a[1]+(bb[1]-a[1])*u0), (a[0]+(bb[0]-a[0])*u1, 0.4, a[1]+(bb[1]-a[1])*u1),
                     (a[0]+(bb[0]-a[0])*u1, 3.0, a[1]+(bb[1]-a[1])*u1), (a[0]+(bb[0]-a[0])*u0, 3.0, a[1]+(bb[1]-a[1])*u0)]
                sp = cam.poly(q)
                if sp: paths.setdefault(('#FFCF8A', 0.75), []).append(P(sp))
            continue
        cells = list(range(cols)); rg.shuffle(cells)
        if d >= 500: cells = cells[:max(1, cols//5)]
        for c in cells:
            if rg.random() > p_lit: continue
            count += 1
            if count > budget: break
            u0 = (0.9+c*cw+(cw-ww)/2)/elen; u1 = u0+ww/elen
            q = [(a[0]+(bb[0]-a[0])*u0, yb, a[1]+(bb[1]-a[1])*u0), (a[0]+(bb[0]-a[0])*u1, yb, a[1]+(bb[1]-a[1])*u1),
                 (a[0]+(bb[0]-a[0])*u1, yt, a[1]+(bb[1]-a[1])*u1), (a[0]+(bb[0]-a[0])*u0, yt, a[1]+(bb[1]-a[1])*u0)]
            sp = cam.poly(q)
            if not sp: continue
            if area(sp) < 0.3: continue
            col = rg.choice(th.get('win_cols', WIN_COLS))
            op = round(rg.choice([0.45, 0.6, 0.75, 0.9]), 2)
            paths.setdefault((col, op), []).append(P(sp))
    for (col, op), ps in paths.items():
        out.append('<path d="%s" fill="%s" opacity="%.2f"/>' % (''.join(ps), col, op*(1-fg*0.85)))
    return out


def bridge_parts():
    parts = []
    z = BRIDGE_Z
    xa = shore_e(z)+36; xb = shore_w(z)-34
    n = 26
    for k in range(n):
        x0 = xa+(xb-xa)*k/n; x1 = xa+(xb-xa)*(k+1)/n
        parts.append(dict(kind='deck', foot=rect(min(x0, x1), max(x0, x1), z-13, z+13), y0=36, h=4,
                          c=((x0+x1)/2, 38, z)))
    # ramps into city
    for k in range(8):
        x0 = xa+k*30; x1 = x0+30
        y = 36*(1-(k+0.5)/8)
        parts.append(dict(kind='deck', foot=rect(x0, x1, z-12, z+12), y0=max(0, y-2), h=4 if y > 2 else 1.5, c=((x0+x1)/2, y, z)))
    t1 = xa-95; t2 = xb+95
    for tx in (t1, t2):
        for dz in (-15, 11):
            parts.append(dict(kind='tower', foot=rect(tx-5, tx+5, z+dz, z+dz+4), y0=0, h=104, c=(tx, 52, z+dz+2)))
        parts.append(dict(kind='tower', foot=rect(tx-5, tx+5, z-15, z+15), y0=96, h=5, c=(tx, 98, z)))
    # cables
    for dz in (-13, 13):
        pts = []
        for k in range(41):
            t = k/40
            x = t1+(t2-t1)*t
            y = 102 - 60*(1-(2*t-1)**2)
            pts.append((x, y, z+dz))
        for k in range(40):
            parts.append(dict(kind='cable', a=pts[k], b=pts[k+1], c=pts[k]))
        for (xs, xe) in ((t1, xa-10), (t2, xb+10)):
            for k in range(10):
                t0, t1_ = k/10, (k+1)/10
                def cp(t): return (xs+(xe-xs)*t, 102-(102-40)*t**0.8, z+dz)
                parts.append(dict(kind='cable', a=cp(t0), b=cp(t1_), c=cp(t0)))
    return parts


def emit_bridge(cam, th, seg, d):
    if seg['kind'] == 'cable':
        p0 = cam.proj(seg['a']); p1 = cam.proj(seg['b'])
        if not (p0 and p1): return []
        col, fg = fogmix(th, hx('#8E97AE' if not th['night'] else '#6D7FB8'), d)
        return ['<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-width="%.2f" fill="none"/>' % (p0[0], p0[1], p1[0], p1[1], tohex(col), max(0.5, 0.8*cam.focal/d))]
    base = hx('#C9CED9') if not th['night'] else hx('#2A3350')
    if seg['kind'] == 'tower': base = hx('#B9BFCC') if not th['night'] else hx('#2E3858')
    bb = Bldg(seg['foot'], seg['h'], 'normal', 0, ('br',), y0=seg['y0'])
    out = emit_simple(cam, th, bb, base, d)
    if th['night'] and seg['kind'] == 'deck':
        # deck lights
        xs = [p[0] for p in seg['foot']]; zs = [p[1] for p in seg['foot']]
        for zz in (min(zs), max(zs)):
            p = cam.proj(((min(xs)+max(xs))/2, seg['y0']+seg['h']+0.5, zz))
            if p:
                out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#FFE2A8" opacity=".85"/>' % (p[0], p[1], max(0.5, 0.6*cam.focal/d)))
    return out
