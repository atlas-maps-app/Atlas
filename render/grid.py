#!/usr/bin/env python3
"""Power grid at dusk over the ridge terrain: pylons, sagging cables by voltage, wind turbines, solar farm, substation."""
import sys, os, json, math, random
sys.path.insert(0, os.path.dirname(__file__))
from city import Camera, vnorm, vdot, vsub, vcross, hx, tohex, mix, mulc, clamp, P, hull2
from terrain import height, fbm, WATER

OUT = os.path.join(os.path.dirname(__file__), '..', 'cache', 'render')
W, H = 412, 915
L = vnorm((-0.8, 0.35, -0.2))          # low sun from the west
FOGC = hx('#7E6488')
GREENS = ['#5E7A4A', '#4E6A3E', '#6E8A52', '#3E5A36', '#72864A', '#55703F']
VCOL = {380: '#FF4D6D', 110: '#4DD2FF', 20: '#7CFF6B'}
CAM = dict(target=(250, 280, 350), yaw=-30, pitch=10, dist=3500, fov=46, cy=232)

LAKE = (900, 1680, 150, 230, 46.0)   # cx, cz, r_in, r_out, level
def lake_w(x, z):
    r = math.hypot((x-LAKE[0])/1.25, z-LAKE[1])
    return 1.0 if r < LAKE[2] else (0.0 if r > LAKE[3] else 1-(r-LAKE[2])/(LAKE[3]-LAKE[2]))
def gh(x, z):
    h = height(x, z)
    w = lake_w(x, z)
    if w > 0: h = h - max(0, h-LAKE[4]+6)*w
    return max(WATER, LAKE[4] if w >= 0.999 else h, (LAKE[4] if w > 0 and h < LAKE[4] else WATER))

def color(x, z, y, n):
    slope = 1-n[1]
    if y < WATER+0.5 or (lake_w(x, z) > 0 and y <= LAKE[4]+0.8): return hx('#3E5C8C')
    k = clamp((fbm(x/300+5, z/300-2, 3)+1.4)/2.8*(len(GREENS)-1) + fbm(x/50, z/50, 2)*0.8, 0, len(GREENS)-1.001)
    i0 = int(k); base = mix(hx(GREENS[i0]), hx(GREENS[i0+1]), k-i0)
    if y > 600: base = mix(base, hx('#8A8270'), clamp((y-600)/80))
    if slope > 0.6: base = mix(base, hx('#6E6A60'), clamp((slope-0.6)/0.25))
    lam = max(0, vdot(n, L))
    warm = mix(hx('#FFB070'), hx('#FFFFFF'), 0.3)
    c = mix(mulc(base, 0.35), (base[0]*warm[0]/255*1.1, base[1]*warm[1]/255*1.1, base[2]*warm[2]/255*1.1), clamp(lam*1.1))
    return mix(c, hx('#2A3050'), 0.18)

def fog(c, d):
    return mix(c, FOGC, clamp((d-1800)/9000)**0.8*0.85)

def ridge_pt(u, v=-80):
    a = math.radians(35)
    return (u*math.cos(a)+v*math.sin(a), -u*math.sin(a)+v*math.cos(a))

def line_route(wps, span):
    pts = []
    for i in range(len(wps)-1):
        a, b = wps[i], wps[i+1]
        n = max(1, int(math.hypot(b[0]-a[0], b[1]-a[1])/span))
        for k in range(n):
            t = k/n; pts.append((a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t))
    pts.append(wps[-1])
    return pts

def render():
    cam = Camera(CAM['target'], CAM['yaw'], CAM['pitch'], CAM['dist'], CAM['fov'], W, H, cy=CAM['cy'])
    items = []
    def grid(x0, x1, z0, z1, n, skip=None):
        dx, dz = (x1-x0)/n, (z1-z0)/n
        hs = [[gh(x0+i*dx, z0+j*dz) for i in range(n+1)] for j in range(n+1)]
        for j in range(n):
            for i in range(n):
                xa, za = x0+i*dx, z0+j*dz
                if skip and skip[0] < xa+dx/2 < skip[1] and skip[2] < za+dz/2 < skip[3]: continue
                q = [(xa, hs[j][i], za), (xa+dx, hs[j][i+1], za), (xa+dx, hs[j+1][i+1], za+dz), (xa, hs[j+1][i], za+dz)]
                c = (xa+dx/2, sum(p[1] for p in q)/4, za+dz/2)
                d = cam.depth(c)
                if d > 5: items.append((d, 'q', q, c))
    NEAR = (-2400, 2400, -1800, 2800)
    grid(*NEAR, 170)
    MID = (-6000, 5200, -6000, 5600)
    grid(*MID, 70, skip=NEAR)
    grid(-16000, 14000, -22000, 8000, 50, skip=MID)
    anchors = {}
    # ---- power lines
    SUB = (740, 1160)
    NUC = (540, 1440); COAL = (1100, 1200); DAM = (993, 1841)
    lines = [
        (380, [(-2600, -1300), (-1400, -700), (-400, -100), (300, 500), SUB], 280, 60, 15),
        (380, [(-2000, 300), (-900, 500), (0, 850), SUB], 280, 60, 15),
        (380, [(NUC[0]+150, NUC[1]-40), SUB], 200, 60, 15),
        (380, [(COAL[0]-100, COAL[1]-30), SUB], 200, 60, 15),
        (110, [SUB, (1040, 1420), (1250, 1640)], 170, 32, 8),
        (110, [(DAM[0]+60, DAM[1]+60), (1180, 1560), (960, 1330), SUB], 170, 32, 8),
    ]
    for (kv, wps, span, hp, arm) in lines:
        pts = line_route(wps, span)
        tops = []
        for i, (x, z) in enumerate(pts):
            j = min(i+1, len(pts)-1); k = max(i-1, 0)
            dx, dz = pts[j][0]-pts[k][0], pts[j][1]-pts[k][1]; l = math.hypot(dx, dz) or 1
            px, pz = -dz/l, dx/l
            y = gh(x, z)
            tops.append(((x+px*arm, y+hp*0.78, z+pz*arm), (x-px*arm, y+hp*0.78, z-pz*arm), (x, y+hp, z)))
            d = cam.depth((x, y+hp/2, z))
            if d > 5: items.append((d-2, 'pylon', (x, y, z, px, pz, hp, arm, kv), None))
        for i in range(len(tops)-1):
            for w in range(3):
                a, b = tops[i][w], tops[i+1][w]
                sag = (10 if kv == 380 else 6) * (0.4 if w == 2 else 1)
                N = 10
                for s in range(N):
                    t0, t1 = s/N, (s+1)/N
                    def at(t): return (a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t - sag*4*t*(1-t), a[2]+(b[2]-a[2])*t)
                    p0, p1 = at(t0), at(t1)
                    d = cam.depth(((p0[0]+p1[0])/2, (p0[1]+p1[1])/2, (p0[2]+p1[2])/2))
                    if d > 5: items.append((d-3, 'cable', (p0, p1, kv, w == 2), None))
    # ---- wind turbines on the crest
    tur = []
    for u in (-900, -620, -340, 250, 530, 810):
        x, z = ridge_pt(u)
        y = gh(x, z)
        d = cam.depth((x, y+60, z))
        if d > 5: items.append((d-4, 'turbine', (x, y, z, u), None)); tur.append((x, y, z))
    # ---- solar farm
    SOL = (430, 740, 470, 650)
    rows = []
    for r in range(14):
        z = SOL[2] + r*(SOL[3]-SOL[2])/14
        for c in range(10):
            x0 = SOL[0] + c*(SOL[1]-SOL[0])/10; x1 = x0 + (SOL[1]-SOL[0])/10 - 4
            q = [(x0, gh(x0, z)+2.5, z), (x1, gh(x1, z)+2.5, z), (x1, gh(x1, z+9)+1, z+9), (x0, gh(x0, z+9)+1, z+9)]
            d = cam.depth(((x0+x1)/2, q[0][1], z+4))
            if d > 5: items.append((d-1, 'panel', q, None))
    # ---- substation
    SX, SZ = SUB
    sub = []
    for (ox, oz, w_, h_) in ((-30, -20, 22, 14), (10, -20, 22, 14), (-30, 12, 18, 10), (12, 12, 18, 10)):
        x0, z0 = SX+ox, SZ+oz; y0 = gh(SX, SZ)
        sub.append((x0, y0, z0, w_, h_))
    d = cam.depth((SX, gh(SX, SZ), SZ))
    items.append((d-6, 'sub', sub, None))
    # ---- town lights in the valley
    rg = random.Random(3)
    for _ in range(260):
        x = rg.gauss(1330, 130); z = rg.gauss(1760, 110)
        y = gh(x, z)
        if y > 260 or lake_w(x, z) > 0: continue
        d = cam.depth((x, y+3, z))
        if d > 5: items.append((d-1, 'light', (x, y+3, z, rg.random()), None))

    # ---- power plants
    def box(x0, x1, z0, z1, y0, h, col, dz=0):
        items.append((cam.depth(((x0+x1)/2, y0+h/2, (z0+z1)/2))-dz, 'box', (x0, x1, z0, z1, y0, h, col), None))
    def tower(x, z, h, rb, rw, plume):
        items.append((cam.depth((x, gh(x, z)+h/2, z))-8, 'ctower', (x, gh(x, z)-2, z, h, rb, rw, plume), None))
    ny = gh(*NUC)
    tower(NUC[0]-110, NUC[1]+10, 150, 56, 30, 1.0)
    tower(NUC[0]+20, NUC[1]-90, 150, 56, 30, 0.8)
    items.append((cam.depth((NUC[0]+120, ny+30, NUC[1]+60))-6, 'dome', (NUC[0]+120, ny-1, NUC[1]+60, 26, 34), None))
    box(NUC[0]+60, NUC[0]+170, NUC[1]+110, NUC[1]+150, ny-2, 26, '#C9CED8', 4)
    cy_ = gh(*COAL)
    items.append((cam.depth((COAL[0], cy_+100, COAL[1]))-10, 'chimney', (COAL[0], cy_-2, COAL[1], 190, 9), None))
    box(COAL[0]+20, COAL[0]+90, COAL[1]-30, COAL[1]+30, cy_-2, 62, '#8A8478', 4)
    box(COAL[0]+90, COAL[0]+160, COAL[1]-10, COAL[1]+30, cy_-2, 30, '#A09A8E', 4)
    tower(COAL[0]-120, COAL[1]+40, 115, 44, 24, 0.55)
    items.append((cam.depth((COAL[0]+60, cy_, COAL[1]+120))-2, 'pile', (COAL[0]+60, cy_, COAL[1]+120, 70, 40, 14), None))
    # dam wall + powerhouse
    ax_, az_ = 0.847, -0.489
    items.append((cam.depth((DAM[0], LAKE[4], DAM[1]))-6, 'dam', (DAM, ax_, az_, 150), None))
    box(DAM[0]+40, DAM[0]+90, DAM[1]+40, DAM[1]+80, gh(DAM[0]+65, DAM[1]+60)-2, 16, '#B8BCC4', 3)
    items.sort(key=lambda t: -t[0])
    out = []
    for d, kind, data, c in items:
        if kind == 'q':
            q = data
            sp = cam.poly(q, margin=10)
            if not sp: continue
            n = vnorm(vcross(vsub(q[3], q[0]), vsub(q[1], q[0])))
            if n[1] < 0: n = tuple(-v for v in n)
            col = tohex(fog(color(c[0], c[2], c[1], n), d))
            out.append('<path d="%s" fill="%s" stroke="%s"/>' % (P(sp), col, col))
        elif kind == 'cable':
            p0, p1, kv, earth = data
            a, b = cam.proj(p0), cam.proj(p1)
            if not (a and b): continue
            fade = 1-clamp((d-2500)/9000)
            col = VCOL[kv] if not earth else '#C9CED8'
            if not earth:
                out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-opacity="%.2f" stroke-width="%.1f" stroke-linecap="round"/>' % (a[0], a[1], b[0], b[1], col, 0.22*fade, 3.6 if kv == 380 else 2.8))
            out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="%s" stroke-opacity="%.2f" stroke-width="%.1f" stroke-linecap="round"/>' % (a[0], a[1], b[0], b[1], col, (0.95 if not earth else 0.5)*fade, 1.0 if kv == 380 else 0.8))
        elif kind == 'pylon':
            x, y, z, px, pz, hp, arm, kv = data
            def pr(ox, oy, oz): return cam.proj((x+ox, y+oy, z+oz))
            b0 = pr(px*hp*0.12, 0, pz*hp*0.12); b1 = pr(-px*hp*0.12, 0, -pz*hp*0.12)
            m0 = pr(px*hp*0.04, hp*0.78, pz*hp*0.04); m1 = pr(-px*hp*0.04, hp*0.78, -pz*hp*0.04)
            top = pr(0, hp, 0)
            a0 = pr(px*arm, hp*0.78, pz*arm); a1 = pr(-px*arm, hp*0.78, -pz*arm)
            if not all((b0, b1, m0, m1, top, a0, a1)): continue
            fade = 1-clamp((d-2500)/9000)
            s = '#D8DCE6'
            pth = 'M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f' % (
                b0[0], b0[1], m0[0], m0[1], top[0], top[1], m1[0], m1[1], b1[0], b1[1], a0[0], a0[1], a1[0], a1[1], b0[0], b0[1], m1[0], m1[1], b1[0], b1[1], m0[0], m0[1])
            out.append('<path d="%s" fill="none" stroke="%s" stroke-opacity="%.2f" stroke-width="%.1f" stroke-linejoin="round"/>' % (pth, s, 0.9*fade, 0.9 if kv == 380 else 0.7))
        elif kind == 'turbine':
            x, y, z, u = data
            base = cam.proj((x, y, z)); hub = cam.proj((x, y+95, z))
            if not (base and hub): continue
            sc = abs(hub[1]-base[1])/95
            out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#EEF0F4" stroke-width="%.1f" stroke-linecap="round"/>' % (base[0], base[1], hub[0], hub[1], max(1.0, 2.2*sc)))
            ang0 = (u*0.7) % 120
            for k in range(3):
                a = math.radians(ang0+k*120)
                L_ = 48*sc
                out.append('<path d="M%.1f %.1fL%.1f %.1f" stroke="#EEF0F4" stroke-width="%.1f" stroke-linecap="round"/>' % (hub[0], hub[1], hub[0]+L_*math.cos(a), hub[1]+L_*math.sin(a), max(0.8, 1.6*sc)))
            out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#FF3B30"/><circle cx="%.1f" cy="%.1f" r="%.1f" fill="#FF3B30" opacity=".3"/>' % (hub[0], hub[1]-1, max(1.2, 2*sc), hub[0], hub[1]-1, max(3, 6*sc)))
        elif kind == 'panel':
            sp = cam.poly(data, margin=10)
            if sp: out.append('<path d="%s" fill="#1E2E52" stroke="#3E5A96" stroke-width=".3"/>' % P(sp))
        elif kind == 'sub':
            y0 = gh(SX, SZ)
            fence = cam.poly([(SX-45, y0+0.5, SZ-35), (SX+45, y0+0.5, SZ-35), (SX+45, y0+0.5, SZ+35), (SX-45, y0+0.5, SZ+35)])
            if fence: out.append('<path d="%s" fill="#4A4E58" stroke="#C9CED8" stroke-width=".8"/>' % P(fence))
            for (x0, yy, z0, w_, h_) in data:
                for face in (
                    [(x0, yy, z0), (x0+w_, yy, z0), (x0+w_, yy+h_, z0), (x0, yy+h_, z0)],
                    [(x0, yy+h_, z0), (x0+w_, yy+h_, z0), (x0+w_, yy+h_, z0+12), (x0, yy+h_, z0+12)]):
                    sp = cam.poly(face)
                    if sp: out.append('<path d="%s" fill="#9AA2B2" stroke="#D8DCE6" stroke-width=".4"/>' % P(sp))
        elif kind == 'box':
            x0, x1, z0, z1, y0, h, col = data
            cc = ((x0+x1)/2, y0+h/2, (z0+z1)/2)
            faces = [([(x0, y0, z0), (x1, y0, z0), (x1, y0+h, z0), (x0, y0+h, z0)], (0, 0, -1)),
                     ([(x0, y0, z1), (x1, y0, z1), (x1, y0+h, z1), (x0, y0+h, z1)], (0, 0, 1)),
                     ([(x0, y0, z0), (x0, y0, z1), (x0, y0+h, z1), (x0, y0+h, z0)], (-1, 0, 0)),
                     ([(x1, y0, z0), (x1, y0, z1), (x1, y0+h, z1), (x1, y0+h, z0)], (1, 0, 0)),
                     ([(x0, y0+h, z0), (x1, y0+h, z0), (x1, y0+h, z1), (x0, y0+h, z1)], (0, 1, 0))]
            for f, nrm in faces:
                if vdot(nrm, vsub(cam.C, f[0])) <= 0: continue
                sp = cam.poly(f)
                if not sp: continue
                lam = max(0, vdot(nrm, L))
                c2 = tohex(fog(mulc(hx(col), 0.45+0.7*lam), d))
                out.append('<path d="%s" fill="%s" stroke="%s" stroke-width=".3"/>' % (P(sp), c2, c2))
            # lit windows
            if col in ('#C9CED8', '#8A8478'):
                for k in range(6):
                    p = cam.proj((x0+(x1-x0)*(k+0.5)/6, y0+h*0.55, z1))
                    if p: out.append('<circle cx="%.1f" cy="%.1f" r=".8" fill="#FFD27A"/>' % p)
        elif kind == 'ctower':
            x, y, z, h, rb, rw, plume = data
            c = ( (rb/rw)**2 - 1 ) ** 0.5
            cc_ = 0.78*h/c
            L_pts, R_pts = [], []
            for k in range(13):
                t = k/12; yy = y+t*h
                r = rw*math.sqrt(1+((t-0.78)*h/cc_)**2)
                pl = cam.proj((x-cam.r[0]*r, yy, z-cam.r[2]*r)); pr_ = cam.proj((x+cam.r[0]*r, yy, z+cam.r[2]*r))
                if pl and pr_: L_pts.append(pl); R_pts.append(pr_)
            if len(L_pts) < 3: continue
            poly = L_pts + R_pts[::-1]
            gid = 'ct%d' % len(out)
            out.append('<linearGradient id="%s" x1="0" x2="1"><stop offset="0" stop-color="%s"/><stop offset=".35" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>' % (gid, tohex(fog(hx('#F2D8C8'), d)), tohex(fog(hx('#C9C0C8'), d)), tohex(fog(hx('#6E6A7E'), d))))
            out.append('<path d="M%s Z" fill="url(#%s)"/>' % ('L'.join('%.1f %.1f' % p for p in poly), gid))
            rt = rw*math.sqrt(1+((1-0.78)*h/cc_)**2)
            ring = [cam.proj((x+rt*math.cos(a), y+h, z+rt*math.sin(a))) for a in [k*math.pi/8 for k in range(16)]]
            ring = [p for p in ring if p]
            if len(ring) > 3: out.append('<path d="M%s Z" fill="#4A4658"/>' % 'L'.join('%.1f %.1f' % p for p in ring))
            top = cam.proj((x, y+h, z))
            if top and plume:
                sc = abs(L_pts[-1][0]-R_pts[-1][0])/2
                for k in range(6):
                    rr = sc*(0.75+k*0.28); dx = k*sc*0.8; dy = -k*sc*0.85
                    out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#F6E2EA" opacity="%.2f"/>' % (top[0]+dx, top[1]+dy-sc*0.5, rr, plume*(0.42-k*0.055)))
        elif kind == 'dome':
            x, y, z, r, hcyl = data
            sp = []
            for k in range(24):
                a = k*math.pi/12
                for yy in (y, y+hcyl):
                    p = cam.proj((x+r*math.cos(a), yy, z+r*math.sin(a)))
                    if p: sp.append(p)
            for k in range(1, 7):
                ph = k*math.pi/14
                for j in range(12):
                    a = j*math.pi/6
                    p = cam.proj((x+r*math.cos(ph)*math.cos(a), y+hcyl+r*math.sin(ph), z+r*math.cos(ph)*math.sin(a)))
                    if p: sp.append(p)
            top = cam.proj((x, y+hcyl+r, z))
            if top: sp.append(top)
            hl = hull2(sp)
            if len(hl) > 2:
                out.append('<linearGradient id="dm%d" x1="0" x2="1"><stop offset="0" stop-color="#F2E4DA"/><stop offset="1" stop-color="#7A7688"/></linearGradient>' % len(out))
                out.append('<path d="M%s Z" fill="url(#dm%d)"/>' % ('L'.join('%.1f %.1f' % p for p in hl), len(out)-1))
        elif kind == 'chimney':
            x, y, z, h, r = data
            for k in range(12):
                t0, t1 = k/12, (k+1)/12
                a0 = cam.proj((x-cam.r[0]*r, y+t0*h, z-cam.r[2]*r)); b0 = cam.proj((x+cam.r[0]*r, y+t0*h, z+cam.r[2]*r))
                a1 = cam.proj((x-cam.r[0]*r*0.8, y+t1*h, z-cam.r[2]*r*0.8)); b1 = cam.proj((x+cam.r[0]*r*0.8, y+t1*h, z+cam.r[2]*r*0.8))
                if not all((a0, b0, a1, b1)): continue
                col = ('#D93A3A' if k % 2 == 0 else '#EEE6E0') if k >= 8 else '#B8B0A8'
                out.append('<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ" fill="%s"/>' % (a0[0], a0[1], b0[0], b0[1], b1[0], b1[1], a1[0], a1[1], col))
            top = cam.proj((x, y+h, z))
            if top:
                out.append('<circle cx="%.1f" cy="%.1f" r="1.6" fill="#FF3B30"/>' % (top[0], top[1]-2))
                for k in range(8):
                    out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#A89AA8" opacity="%.2f"/>' % (top[0]+k*5.5, top[1]-4-k*3.2, 2.5+k*1.6, 0.5-k*0.05))
        elif kind == 'pile':
            x, y, z, rx, rz, hh = data
            sp = []
            for k in range(20):
                a = k*math.pi/10
                p = cam.proj((x+rx*math.cos(a), gh(x+rx*math.cos(a), z+rz*math.sin(a)), z+rz*math.sin(a)))
                if p: sp.append(p)
            tp = cam.proj((x, y+hh, z))
            if tp: sp.append(tp)
            hl = hull2(sp)
            if len(hl) > 2: out.append('<path d="M%s Z" fill="#1E1C22"/>' % 'L'.join('%.1f %.1f' % p for p in hl))
        elif kind == 'dam':
            (dx_, dz_), ax_, az_, half = data
            top_y = LAKE[4]+7
            for k in range(-8, 8):
                t0, t1 = k/8*half, (k+1)/8*half
                pa = (dx_+ax_*t0, dz_+az_*t0); pb = (dx_+ax_*t1, dz_+az_*t1)
                ga, gb = height(*pa), height(*pb)
                f = [(pa[0], min(ga, top_y)-4, pa[1]), (pb[0], min(gb, top_y)-4, pb[1]), (pb[0], top_y, pb[1]), (pa[0], top_y, pa[1])]
                sp = cam.poly(f)
                if sp: out.append('<path d="%s" fill="#D8D2CC" stroke="#D8D2CC" stroke-width=".4"/>' % P(sp))
                tt = [(pa[0], top_y, pa[1]), (pb[0], top_y, pb[1]), (pb[0]-0.489*0, top_y, pb[1]-6), (pa[0], top_y, pa[1]-6)]
                sp2 = cam.poly(tt)
                if sp2: out.append('<path d="%s" fill="#F2ECE6"/>' % P(sp2))
        elif kind == 'light':
            x, y, z, r = data
            p = cam.proj((x, y, z))
            if p: out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#FFD27A" opacity="%.2f"/>' % (p[0], p[1], 0.7+0.6*r, 0.55+0.4*r))
    hy = cam.horizon_y()
    defs = '<linearGradient id="dusk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1E2244"/><stop offset=".55" stop-color="#6A4A7E"/><stop offset=".92" stop-color="#F29A6A"/><stop offset="1" stop-color="#B98296"/></linearGradient>'
    sky = '<rect width="%d" height="%d" fill="#7E6488"/>' % (W, H)
    if hy > 0: sky += '<rect width="%d" height="%.1f" fill="url(#dusk)"/>' % (W, hy+2)
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d"><defs><clipPath id="vb"><rect width="%d" height="%d"/></clipPath>%s</defs><g clip-path="url(#vb)">%s<g stroke-width=".6" stroke-linejoin="round">%s</g></g></svg>'
           % (W, H, W, H, W, H, defs, sky, ''.join(out)))
    open(os.path.join(OUT, 'grid.svg'), 'w').write(svg)
    for k, p in {'sub': (SX, gh(SX, SZ)+10, SZ), 'solar': ((SOL[0]+SOL[1])/2, gh(585, 560)+3, 560), 'town': (1330, gh(1330, 1760)+5, 1760),
                 'wind': (lambda q: (q[0], gh(*q)+110, q[1]))(ridge_pt(250)), 'nuclear': (NUC[0]-40, gh(*NUC)+170, NUC[1]-40),
                 'coal': (COAL[0], gh(*COAL)+200, COAL[1]), 'hydro': (DAM[0], LAKE[4]+10, DAM[1]), 'lake': (LAKE[0], LAKE[4], LAKE[1]),
                 'l380': (-400, gh(-400, -100)+60, -100)}.items():
        pp = cam.proj(p); anchors[k] = [round(pp[0], 1), round(pp[1], 1)] if pp else None
    json.dump(anchors, open(os.path.join(OUT, 'grid_anchors.json'), 'w'))
    print('grid %.0f KB' % (len(svg)/1024), 'horizon', round(hy), anchors)

if __name__ == '__main__':
    render()
