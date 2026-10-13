"""Up close, at eye level: the ground in its mapped material, laid the way that material is laid.
surface=paving_stones  -> klinkers in herringbone on the carriageway (the Amsterdam way of laying them: a generic detail)
sidewalk:*:surface     -> 30 cm tiles on the pavement, where sidewalk=both/left/right is mapped
surface=sett           -> setts in running rows
surface=asphalt        -> no joints, only grain
Each pattern is drawn in centimetres and placed with an affine map fitted to thin depth bands, so the joints converge."""
import math, random
from city import P, area

def pattern_defs(night=False, wet=False):
    j = '#2E2A26' if not night else '#05070B'
    jo = .55 if not night else .5
    dk, lt = ('#000', '#FFF') if not night else ('#000', '#3A4258')
    out = []
    # herringbone, 20 x 10 cm klinkers; the lattice repeats every 160 cm, with a tone per brick
    rnd = random.Random(7); br = []
    for m in range(-12, 13):
        for k in range(-20, 21):
            ox, oy = 2 * m, -2 * m
            for (x0, y0, w, h) in ((k + ox, k + oy, 1, 2), (k + 1 + ox, k + oy, 2, 1)):
                if x0 + w <= 0 or x0 >= 16 or y0 + h <= 0 or y0 >= 16: continue
                br.append((x0 * 10, y0 * 10, w * 10, h * 10))
    tone = {}
    def key(b): return (b[0] % 160, b[1] % 160, b[2])
    for b in br:
        if key(b) not in tone: tone[key(b)] = rnd.random()
    rr = lambda b: 'M%d %dh%dv%dh%dz' % (b[0], b[1], b[2], b[3], -b[2])
    dark = ''.join(rr(b) for b in br if tone[key(b)] < 0.3); light = ''.join(rr(b) for b in br if tone[key(b)] > 0.75)
    out.append('<pattern id="pv_hb" width="160" height="160" patternUnits="userSpaceOnUse"><path d="%s" fill="%s" opacity=".1"/><path d="%s" fill="%s" opacity=".1"/><path d="%s" fill="none" stroke="%s" stroke-width="1" opacity="%.2f"/></pattern>'
               % (dark, dk, light, lt, ''.join(rr(b) for b in br), j, jo))
    # pavement tiles, 30 x 30 cm
    rnd = random.Random(11); t = [(x * 30, y * 30) for x in range(8) for y in range(8)]
    tl = lambda c: 'M%d %dh30v30h-30z' % c
    dark = ''.join(tl(c) for c in t if rnd.random() < 0.25); light = ''.join(tl(c) for c in t if rnd.random() < 0.2)
    out.append('<pattern id="pv_tile" width="240" height="240" patternUnits="userSpaceOnUse"><path d="%s" fill="%s" opacity=".07"/><path d="%s" fill="%s" opacity=".08"/><path d="%s" fill="none" stroke="%s" stroke-width=".9" opacity="%.2f"/></pattern>'
               % (dark, dk, light, lt, ''.join(tl(c) for c in t), j, jo * 0.8))
    # setts, rows of 12 cm, stones 16 cm, half-offset rows, wide sand joints
    rnd = random.Random(5); st = []
    for row in range(8):
        off = 8 if row % 2 else 0
        for c in range(-1, 9):
            x = c * 16 + off
            if x >= 128 or x + 16 <= 0: continue
            st.append((x, row * 12, rnd.random()))
    sp = lambda s: '<rect x="%d" y="%d" width="15" height="11" rx="3"/>' % (s[0], s[1])
    out.append('<pattern id="pv_sett" width="128" height="96" patternUnits="userSpaceOnUse"><rect width="128" height="96" fill="%s" opacity=".38"/><g fill="#FFF" opacity=".16">%s</g><g fill="#000" opacity=".1">%s</g><g fill="#FFF" opacity=".12">%s</g></pattern>'
               % (j, ''.join(sp(s) for s in st), ''.join(sp(s) for s in st if s[2] < 0.35), ''.join(sp(s) for s in st if s[2] > 0.7)))
    return ''.join(out)

ROAD_PAT = {'paving_stones': ('pv_hb', 45), 'bricks': ('pv_hb', 45), 'sett': ('pv_sett', 0), 'cobblestone': ('pv_sett', 0), 'unhewn_cobblestone': ('pv_sett', 0)}
SIDE_PAT = {'paving_stones': ('pv_tile', 0), 'sett': ('pv_sett', 0), 'bricks': ('pv_hb', 0)}

def sidewalk_sides(t):
    sw = t.get('sidewalk') or t.get('sidewalk:both') and 'both'
    sides = {'both': (-1, 1), 'left': (-1,), 'right': (1,), 'yes': (-1, 1)}.get(sw, ())
    if t.get('sidewalk:left') in ('yes', 'separate') and -1 not in sides: sides = sides + (-1,)
    if t.get('sidewalk:right') in ('yes', 'separate') and 1 not in sides: sides = sides + (1,)
    surf = {}
    for s in sides:
        nm = 'left' if s < 0 else 'right'
        surf[s] = t.get('sidewalk:%s:surface' % nm) or t.get('sidewalk:both:surface') or t.get('sidewalk:surface')
    return sides, surf

def _clip_x(cam, cs, sx0, sx1):
    """Clip a camera-space polygon to the screen columns sx0..sx1 (planes through the eye)."""
    for (sx, sgn) in ((sx0, 1), (sx1, -1)):
        if abs(sx) > 1e8: continue
        k = (sx - cam.cx) / cam.focal
        fn = lambda c: sgn * (c[0] - k * c[2])
        out = []; n = len(cs)
        for i in range(n):
            a, b = cs[i], cs[(i + 1) % n]; fa, fb = fn(a), fn(b)
            if fa >= 0: out.append(a)
            if (fa >= 0) != (fb >= 0):
                t = fa / (fa - fb); out.append(tuple(a[q] + (b[q] - a[q]) * t for q in range(3)))
        cs = out
        if len(cs) < 3: return None
    return cs

def _clip_z(cs, z0, z1):
    for (lo, sgn) in ((z0, 1), (z1, -1)):
        out = []; n = len(cs)
        for i in range(n):
            a, b = cs[i], cs[(i + 1) % n]
            ai, bi = sgn * (a[2] - lo) >= 0, sgn * (b[2] - lo) >= 0
            if ai: out.append(a)
            if ai != bi:
                t = (lo - a[2]) / (b[2] - a[2]); out.append(tuple(a[k] + (b[k] - a[k]) * t for k in range(3)))
        cs = out
        if len(cs) < 3: return None
    return cs

def _near_carriageway(segs, x, z, skip):
    for (rid, ax, az, bx, bz, hw) in segs:
        if rid == skip: continue
        dx, dz = bx - ax, bz - az; L2 = dx * dx + dz * dz or 1
        t = max(0, min(1, ((x - ax) * dx + (z - az) * dz) / L2))
        if math.hypot(ax + dx * t - x, az + dz * t - z) < hw: return True
    return False

def paving(S, cam, th, far, side_col, dmax=34.0, sidewalk_w=1.9):
    """Returns (under, over): pavements to draw before the carriageways, joints and kerbs to draw after."""
    night = th['night']; under, over, kerbs = [], [], []
    d0 = cam.ground_depth(cam.H) or 1.0
    bands = []; d = max(0.9, d0 * 0.9)
    while d < dmax: bands.append((d, d * 1.066)); d *= 1.066
    Cx, Cz = cam.C[0], cam.C[2]
    nid = [0]
    def world(c): return tuple(cam.C[k] + c[0] * cam.r[k] + c[1] * cam.u[k] + c[2] * cam.f[k] for k in range(3))
    def piece(dst, quad, y, pat, rot):
        """Fills a ground quad with a pattern, band by band, each band with its own affine fit."""
        cs0 = [cam.cam((x, y, z)) for (x, z) in quad]
        ux, uz = quad[1][0] - quad[0][0], quad[1][1] - quad[0][1]; L = math.hypot(ux, uz) or 1; ux, uz = ux / L, uz / L
        ca, sa = math.cos(math.radians(rot)), math.sin(math.radians(rot))
        cells = []
        for (z0, z1) in bands:
            cs = _clip_z(cs0, z0, z1)
            if not cs: continue
            # and across: an affine fit is only good near its own centre, so no piece is wider than about 56 px
            xs = [cam.pc(c)[0] for c in cs]; x0, x1 = max(-10, min(xs)), min(cam.W + 10, max(xs))
            if x1 <= x0: continue
            k = max(1, int(math.ceil((x1 - x0) / 56)))
            for j in range(k):
                a_, b_ = x0 + (x1 - x0) * j / k, x0 + (x1 - x0) * (j + 1) / k
                c2 = _clip_x(cam, cs, a_ if j else -1e9, b_ if j < k - 1 else 1e9)
                if c2: cells.append((z0, z1, c2))
        for (z0, z1, cs) in cells:
            sp = [cam.pc(c) for c in cs]
            if max(p[0] for p in sp) < -20 or min(p[0] for p in sp) > cam.W + 20 or max(p[1] for p in sp) < 0 or min(p[1] for p in sp) > cam.H + 20: continue
            cen = tuple(sum(c[k] for c in cs) / len(cs) for k in range(3)); G = world(cen)
            s0 = cam.proj(G); e = 0.05
            s1 = cam.proj((G[0] + ux * e, y, G[2] + uz * e)); s2 = cam.proj((G[0] - uz * e, y, G[2] + ux * e))
            if not (s0 and s1 and s2): continue
            J = ((s1[0] - s0[0]) / e, (s2[0] - s0[0]) / e), ((s1[1] - s0[1]) / e, (s2[1] - s0[1]) / e)
            u0 = G[0] * ux + G[2] * uz; v0 = -G[0] * uz + G[2] * ux
            # pattern centimetres -> road frame metres (rotated) -> screen
            M = [[(J[i][0] * ca - J[i][1] * sa) / 100, (J[i][0] * sa + J[i][1] * ca) / 100] for i in range(2)]
            ex, ey = s0[0] - (J[0][0] * u0 + J[0][1] * v0), s0[1] - (J[1][0] * u0 + J[1][1] * v0)
            op = max(0.0, min(1.0, 1.3 - (z0 + z1) / 2 / dmax))
            pid = 'pv%d' % nid[0]; nid[0] += 1
            dst.append('<pattern id="%s" href="#%s" patternTransform="matrix(%.4f %.4f %.4f %.4f %.1f %.1f)"/><path d="%s" fill="url(#%s)" opacity="%.2f"/>'
                       % (pid, pat, M[0][0], M[1][0], M[0][1], M[1][1], ex, ey, P(sp), pid, op))
    near = []
    for r in S['roads']:
        pts = r['pts']
        for i in range(len(pts) - 1):
            (ax, az), (bx, bz) = pts[i], pts[i + 1]; dx, dz = bx - ax, bz - az; L2 = dx * dx + dz * dz or 1
            tt = max(0, min(1, ((Cx - ax) * dx + (Cz - az) * dz) / L2))
            if math.hypot(ax + dx * tt - Cx, az + dz * tt - Cz) < dmax + 12: near.append((id(r), ax, az, bx, bz, r['w'] / 2, r, i))
    segs = [n[:6] for n in near]
    for (rid, ax, az, bx, bz, hw, r, i) in near:
        if r['bridge']: continue
        t = r['tags']; dx, dz = bx - ax, bz - az; L = math.hypot(dx, dz) or 1
        nx, nz = -dz / L, dx / L          # left normal
        sides, ssurf = sidewalk_sides(t)
        for s in sides:
            o0, o1 = (hw, hw + sidewalk_w) if s < 0 else (-hw - sidewalk_w, -hw)
            q = [(ax + nx * o0, az + nz * o0), (bx + nx * o0, bz + nz * o0), (bx + nx * o1, bz + nz * o1), (ax + nx * o1, az + nz * o1)]
            sp = cam.poly([(x, 0.13, z) for (x, z) in q], margin=200)
            if not sp: continue
            under.append('<path d="%s" fill="%s"/>' % (P(sp), side_col(ssurf.get(s))))
            pat = SIDE_PAT.get(ssurf.get(s) or '')
            if pat: piece(under, q, 0.13, pat[0], pat[1])
            # the kerb: a granite edge with its face toward the carriageway; it stops where another street crosses
            ke = hw if s < 0 else -hw; ki = ke + (0.15 if s < 0 else -0.15)
            n_ = max(1, int(L / 1.5))
            for j in range(n_):
                f0, f1 = j / n_, (j + 1) / n_
                p0 = (ax + dx * f0, az + dz * f0); p1 = (ax + dx * f1, az + dz * f1)
                mx, mz = (p0[0] + p1[0]) / 2 + nx * ke, (p0[1] + p1[1]) / 2 + nz * ke
                if _near_carriageway(segs, mx, mz, rid): continue
                fq = cam.poly([(p0[0] + nx * ke, 0.03, p0[1] + nz * ke), (p1[0] + nx * ke, 0.03, p1[1] + nz * ke), (p1[0] + nx * ke, 0.14, p1[1] + nz * ke), (p0[0] + nx * ke, 0.14, p0[1] + nz * ke)], margin=100)
                tq = cam.poly([(p0[0] + nx * ke, 0.14, p0[1] + nz * ke), (p1[0] + nx * ke, 0.14, p1[1] + nz * ke), (p1[0] + nx * ki, 0.14, p1[1] + nz * ki), (p0[0] + nx * ki, 0.14, p0[1] + nz * ki)], margin=100)
                if fq: kerbs.append(('f', P(fq)))
                if tq: kerbs.append(('t', P(tq)))
        rp = ROAD_PAT.get(t.get('surface'))
        if rp:
            q = [(ax + nx * hw, az + nz * hw), (bx + nx * hw, bz + nz * hw), (bx - nx * hw, bz - nz * hw), (ax - nx * hw, az - nz * hw)]
            piece(over, q, 0.05, rp[0], rp[1])
    if kerbs:
        kf, kt = ('#77746E', '#D5D2CB') if not night else ('#0C0E15', '#1E2232')
        over.append('<path d="%s" fill="%s"/><path d="%s" fill="%s"/>' % (''.join(p for k, p in kerbs if k == 'f'), kf, ''.join(p for k, p in kerbs if k == 't'), kt))
    return ''.join(under), ''.join(over)

def church_windows(cam, th, a, b, ex, ez, elen, nrm, y0, top, d, reveal, night):
    """building=church: tall round-arched windows, one per bay of about six metres (a generic detail of the type, not a mapped tag)."""
    hgt = top - y0
    if hgt < 6 or elen < 3: return []
    nb = max(1, int(elen / 6.2)); bw = elen / nb
    wh = min(1.1, bw * 0.2)                      # half the window's width
    ys = y0 + min(3.2, hgt * 0.22); ye = top - min(2.4, hgt * 0.14) - wh
    if ye - ys < 2: return []
    ux, uz = ex / elen, ez / elen
    def W(u, y, off): return (a[0] + ux * u + nrm[0] * off, y, a[1] + uz * u + nrm[2] * off)
    def shape(uc, off, grow=0.0):
        w = wh + grow
        pts = [W(uc - w, ys - grow, off), W(uc + w, ys - grow, off)]
        for k in range(9):
            t = math.pi * k / 8
            pts.append(W(uc + w * math.cos(t), ye + w * math.sin(t), off))
        return pts
    rev, glass, stone, lead = [], [], [], []
    for k in range(nb):
        uc = (k + 0.5) * bw
        o = cam.poly(shape(uc, 0.0, 0.22), margin=200)
        if not o or area(o) < 4: continue
        stone.append(P(o))
        o = cam.poly(shape(uc, 0.0), margin=200)
        if o: rev.append(P(o))
        g = cam.poly(shape(uc, -0.38), margin=200)
        if not g: continue
        glass.append(P(g))
        if area(g) > 60:                 # leading: a mullion and transoms every metre and a half
            segs = []
            p0, p1 = cam.proj(W(uc, ys, -0.37)), cam.proj(W(uc, ye + wh, -0.37))
            if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
            y = ys + 1.5
            while y < ye:
                p0, p1 = cam.proj(W(uc - wh, y, -0.37)), cam.proj(W(uc + wh, y, -0.37))
                if p0 and p1: segs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
                y += 1.5
            lead.extend(segs)
    if not glass: return []
    s = cam.focal / max(d, 1)
    gfill = '#59636E' if not night else '#121622'
    out = ['<path d="%s" fill="%s"/>' % (''.join(stone), '#DCD6CA' if not night else '#2A2E3A'),
           '<path d="%s" fill="%s"/>' % (''.join(rev), reveal),
           '<path d="%s" fill="%s"/>' % (''.join(glass), 'url(#glass)' if th.get('wx') and not night else gfill)]
    if not night: out.append('<path d="%s" fill="#2A3038" opacity=".42"/>' % ''.join(glass))
    if lead: out.append('<path d="%s" stroke="%s" stroke-width="%.2f" fill="none"/>' % (''.join(lead), '#262A30' if not night else '#05060A', max(0.4, 0.05 * s)))
    return out

FORMS = {'ulmus': 'vase', 'tilia': 'ovoid', 'platanus': 'broad', 'quercus': 'broad', 'aesculus': 'dome', 'acer': 'dome',
         'populus': 'column', 'salix': 'weep', 'fraxinus': 'ovoid', 'betula': 'ovoid', 'robinia': 'broad'}

def tree_form(t):
    if t.get('leaf_type') == 'needleleaved': return 'cone', None
    nm = (t.get('genus') or t.get('species') or t.get('taxon') or '').split(' ')[0].lower()
    return FORMS.get(nm, 'ovoid'), (nm if nm in FORMS else None)

def autumn(date, t):
    """leaf_cycle=evergreen and needleleaved trees stay green; others turn by the date of the view (a generic seasonal detail)."""
    if t.get('leaf_cycle') == 'evergreen' or t.get('leaf_type') == 'needleleaved': return 0.0
    y, m, dd = date
    doy = (m - 1) * 30.5 + dd
    return max(0.0, min(0.7, (doy - 258) / 75))         # mid-September on

def tree_close(cam, th, sh, n, d, light, base):
    """A broadleaved tree up close: trunk and limbs, then a crown of lit and shaded clumps in the form of its genus."""
    from city import mix, mulc, tohex, hx, vnorm, vdot
    night = th['night']; L = th['_L']
    h, r = n['h'], n['crown'] / 2
    if r * cam.focal / max(d, 1) < 6.5:          # a few pixels across: one crown, shaded and lit, no more
        p = cam.proj((n['x'], h * 0.65, n['z']))
        if not p: return []
        rr = max(0.8, r * cam.focal / max(d, 1)); night = th['night']; L = th['_L']
        tint = autumn(th['date'], n['tags']); leaf = mix(hx('#4E6B35'), hx('#9C8A36'), tint)
        c0 = tohex(light(th, mulc(leaf, 0.7), (0, 0.3, 0), False)); c1 = tohex(light(th, leaf, vnorm((L[0], 0.8, L[2])), not night))
        if rr < 4.2: return ['<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (p[0], p[1], rr, tohex(mix(hx(c0), hx(c1), 0.55)))]
        return ['<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/><circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s"/>' % (p[0], p[1], rr, c0, p[0] - rr * 0.2, p[1] - rr * 0.25, rr * 0.62, c1)]
    form, _ = tree_form(n['tags'])
    rnd = random.Random(n['id'])
    cb = h * (0.38 if form != 'weep' else 0.3)             # clear trunk below the crown
    s = cam.focal / max(d, 1)
    out = []
    # trunk: tapered, lit on the sun side
    tw0, tw1 = 0.22, 0.12
    c0, c1 = cam.proj((n['x'], 0, n['z'])), cam.proj((n['x'], cb + (h - cb) * 0.3, n['z']))
    if not (c0 and c1): return []
    w0, w1 = max(0.5, tw0 * s), max(0.35, tw1 * s)
    bark = '#4B4038' if not night else '#0B0D10'
    out.append('<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ" fill="%s"/>' % (c0[0] - w0, c0[1], c0[0] + w0, c0[1], c1[0] + w1, c1[1], c1[0] - w1, c1[1], bark))
    if not night and s > 4:
        sx = 1 if L[0] * cam.r[0] + L[2] * cam.r[2] > 0 else -1
        out.append('<path d="M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ" fill="#8A7B6C" opacity=".55"/>' % (c0[0] + sx * w0 * 0.15, c0[1], c0[0] + sx * w0, c0[1], c1[0] + sx * w1, c1[1], c1[0] + sx * w1 * 0.15, c1[1]))
    # crown clumps
    rpx = r * cam.focal / max(d, 1)                      # crown radius on screen: fewer, larger clumps when it is small
    nb = 36 if rpx > 70 else (26 if rpx > 34 else (16 if rpx > 16 else 9))
    cy = cb + (h - cb) * 0.5; ry = (h - cb) / 2
    cl = []
    for k in range(nb):
        for _ in range(8):
            if form == 'vase':
                f = rnd.uniform(0.12, 1.0); y = cb + (h - cb) * f
                R = r * (0.38 + 0.62 * math.sin(min(math.pi / 2, f * 2.0))) * (1 - max(0, f - 0.85) * 2.5)
            elif form == 'cone':
                f = rnd.uniform(0.0, 0.95); y = cb * 0.7 + (h - cb * 0.7) * f; R = r * (1 - f)
            elif form == 'column':
                f = rnd.uniform(-1, 1); y = cy + ry * f; R = r * 0.55 * math.sqrt(max(0, 1 - f * f))
            elif form in ('dome', 'broad', 'weep'):
                f = rnd.uniform(-0.55, 1); y = cy + ry * f; R = r * (1.08 if form == 'broad' else 1.0) * math.sqrt(max(0, 1 - f * f))
            else:
                f = rnd.uniform(-1, 1); y = cy + ry * f; R = r * math.sqrt(max(0, 1 - f * f))
            if R > 0.2: break
        ang = rnd.uniform(0, 2 * math.pi); rad = R * math.sqrt(rnd.uniform(0.35, 1.0))
        x, z = n['x'] + rad * math.cos(ang), n['z'] + rad * math.sin(ang)
        c = cam.cam((x, y, z))
        if c[2] < 1: continue
        nrm = vnorm((x - n['x'], (y - cy) * 1.4, z - n['z']))
        cl.append((c[2], x, y, z, r * rnd.uniform(0.27, 0.38) * (1.25 if nb < 12 else 1.0), nrm))
    if not cl: return out
    # limbs, from the top of the trunk to the lower clumps, half hidden by the crown
    if s > 2.5:
        limbs = []
        for (_, x, y, z, _, _) in sorted(cl, key=lambda q: q[2])[:5]:
            p0, p1 = c1, cam.proj((x, y, z))
            if p1: limbs.append('M%.1f %.1fL%.1f %.1f' % (p0[0], p0[1], p1[0], p1[1]))
        if limbs: out.append('<path d="%s" stroke="%s" stroke-width="%.2f" stroke-linecap="round" fill="none"/>' % (''.join(limbs), bark, max(0.4, 0.07 * s)))
    lit = (not night) and sh.lit((n['x'] + L[0] * r, h * 0.75 + L[1] * r, n['z'] + L[2] * r))
    tint = autumn(th['date'], n['tags'])
    leaf = mix(hx('#4E6B35'), hx('#9C8A36'), tint)
    tones = [tohex(light(th, mulc(leaf, k_), nr_, lit_)) for (k_, nr_, lit_) in
             ((0.55, (0, -1, 0), False), (0.8, (0, 0.3, 0), False), (0.86, vnorm((L[0], 0.8, L[2])), lit), (0.93, vnorm((L[0], 0.5, L[2])), lit))]
    cl.sort(key=lambda q: -q[0])
    slices = [cl[i * len(cl) // 3:(i + 1) * len(cl) // 3] for i in range(3)]
    g = []
    for sl in slices:
        groups = [[], [], [], []]
        for (dd, x, y, z, br, nrm) in sl:
            p = cam.proj((x, y, z)); rr = br * cam.focal / dd
            lam = vdot(nrm, L) if not night else -1
            groups[0 if nrm[1] < -0.45 else 1].append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (p[0], p[1], rr))
            if lam > -0.2:
                q = cam.proj((x + L[0] * br * 0.35, y + L[1] * br * 0.35, z + L[2] * br * 0.35))
                if q: groups[2].append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (q[0], q[1], rr * 0.72))
            if lam > 0.35 and lit:
                q = cam.proj((x + L[0] * br * 0.55, y + L[1] * br * 0.55, z + L[2] * br * 0.55))
                if q: groups[3].append('<circle cx="%.1f" cy="%.1f" r="%.1f"/>' % (q[0], q[1], rr * 0.36))
        for i, gr in enumerate(groups):
            if gr: g.append('<g fill="%s">%s</g>' % (tones[i], ''.join(gr)))
    out.append('<g filter="url(#crown)">%s</g>' % ''.join(g))
    return out

def crown_filter():
    # ragged edges at the scale of leaf clusters, a few gaps of sky, a mottled tone; never per-pixel noise
    return ('<filter id="crown" x="-12%" y="-12%" width="124%" height="124%">'
            '<feTurbulence type="fractalNoise" baseFrequency=".15" numOctaves="2" seed="5" result="n"/>'
            '<feDisplacementMap in="SourceGraphic" in2="n" scale="10" xChannelSelector="R" yChannelSelector="G" result="d"/>'
            '<feTurbulence type="fractalNoise" baseFrequency=".42" numOctaves="2" seed="2" result="m"/>'
            '<feColorMatrix in="m" type="matrix" values="1 0 0 0 .5  1 0 0 0 .5  1 0 0 0 .5  0 0 0 0 1" result="tone"/>'
            '<feComposite in="d" in2="tone" operator="arithmetic" k1="1" k2="0" k3="0" k4="0" result="t"/>'
            '<feComposite in="t" in2="d" operator="in"/></filter>')

def _seg(cam, p, q):
    a, b = cam.cam(p), cam.cam(q)
    if a[2] < cam.near and b[2] < cam.near: return None
    if a[2] < cam.near: t = (cam.near - a[2]) / (b[2] - a[2]); a = tuple(a[k] + (b[k] - a[k]) * t for k in range(3))
    if b[2] < cam.near: t = (cam.near - b[2]) / (a[2] - b[2]); b = tuple(b[k] + (a[k] - b[k]) * t for k in range(3))
    return cam.pc(a), cam.pc(b)

def coursing(cam, a, b, ex, ez, elen, nrm, y0, top, course=0.0625):
    """Brick walls up close: the bed joints, one every course (a 5 cm Dutch brick and its mortar), exact in perspective.
    Drawn from building:material=brick, mapped or guessed; they fade out before they would merge into a moire."""
    if top - y0 < 0.3: return []
    out = []
    n = max(1, int(math.ceil(elen / 5.0)))
    for k in range(n):
        u0, u1 = k / n, (k + 1) / n
        m = (a[0] + ex * (u0 + u1) / 2, (y0 + top) / 2, a[1] + ez * (u0 + u1) / 2)
        dm = cam.depth(m)
        if dm < cam.near: dm = cam.near
        sp = cam.focal * course / dm                       # course height on screen
        if sp < 1.6: continue
        op = min(1.0, (sp - 1.6) / 2.2)
        segs = []; y = y0 + course
        p0x, p0z = a[0] + ex * u0 + nrm[0] * 0.012, a[1] + ez * u0 + nrm[2] * 0.012
        p1x, p1z = a[0] + ex * u1 + nrm[0] * 0.012, a[1] + ez * u1 + nrm[2] * 0.012
        while y < top - 0.02:
            r = _seg(cam, (p0x, y, p0z), (p1x, y, p1z))
            if r:
                (x0_, y0_), (x1_, y1_) = r
                if not (max(x0_, x1_) < -5 or min(x0_, x1_) > cam.W + 5 or max(y0_, y1_) < -5 or min(y0_, y1_) > cam.H + 5):
                    segs.append('M%.1f %.1fl%.1f %.1f' % (x0_, y0_, x1_ - x0_, y1_ - y0_))
            y += course
        stone = course >= 0.3
        if stone and segs:
            # cut stone: the blocks' head joints too, about twice as long as a course is high, staggered course by course
            bl = course * 2.1; y = y0; row = 0; seg_len = elen * (u1 - u0)
            while y < top - course * 0.5:
                y1_ = min(top, y + course); off = (row % 2) * bl / 2; t = off
                while t < seg_len:
                    f = u0 + t / elen
                    r = _seg(cam, (a[0] + ex * f + nrm[0] * 0.012, y, a[1] + ez * f + nrm[2] * 0.012), (a[0] + ex * f + nrm[0] * 0.012, y1_, a[1] + ez * f + nrm[2] * 0.012))
                    if r: (x0_, y0_), (x1_, y1_s) = r; segs.append('M%.1f %.1fl%.1f %.1f' % (x0_, y0_, x1_ - x0_, y1_s - y0_))
                    t += bl
                y += course; row += 1
        if segs:
            out.append('<path d="%s" stroke="%s" stroke-width="%.2f" opacity="%.2f" fill="none"/>' % (''.join(segs), '#5E5548' if stone else '#E2D9CB', max(0.35, cam.focal * (0.008 if stone else 0.011) / dm), (0.26 if stone else 0.3) * op))
    return out

def sky_clouds(cam, th, W, hy, defs, eye_y=1.7, tile=False):
    """Clouds as a layer at height, seen in perspective: big heaps overhead, thin flat bands toward the horizon.
    Coverage from the real cloud cover; relief lit from the real sun (feDiffuseLighting on the noise as a height field)."""
    w = th['wx']; night = th['night']; L = th['_L']; over = th.get('sky_k')
    if night:   Hc, lam, k, t = 600.0, 1100.0, 3.2, 0.3
    elif over:  Hc, lam, k, t = 800.0, 1700.0, 2.4, -0.1
    else:       Hc, lam, k, t = 1100.0, 520.0, 6.0, 0.58 - w['cloud'] * 0.28
    if night:   sh_, li_ = (0.085, 0.085, 0.115), (0.27, 0.22, 0.23)
    elif over:  sh_, li_ = (0.55, 0.58, 0.63), (0.87, 0.89, 0.91)
    else:       sh_, li_ = (0.71, 0.75, 0.83), (1.0, 0.99, 0.96)
    # the light, in filter space (x right, y down, z toward the viewer)
    if night: lv = (0.0, 0.55, 0.83)                     # the city lights the cloud from below
    elif over: lv = (0.0, -0.35, 0.94)                    # light through the deck from above
    else:
        lx = L[0] * cam.r[0] + L[1] * cam.r[1] + L[2] * cam.r[2]; ly = L[0] * cam.u[0] + L[1] * cam.u[1] + L[2] * cam.u[2]
        lz = L[0] * cam.f[0] + L[1] * cam.f[1] + L[2] * cam.f[2]
        lv = (lx, -ly, -lz)
    n_ = math.sqrt(sum(v * v for v in lv)) or 1; lv = tuple(v / n_ for v in lv)
    laz = math.degrees(math.atan2(lv[1], lv[0])); lel = math.degrees(math.asin(max(-1, min(1, lv[2]))))
    def elev(y):
        d = tuple(cam.u[q] * (-(y - cam.cy)) + cam.f[q] * cam.focal for q in range(3)); n = math.sqrt(sum(v * v for v in d))
        return math.degrees(math.asin(d[1] / n))
    def y_at(e):
        lo, hi = -4000.0, hy
        for _ in range(40):
            m = (lo + hi) / 2
            if elev(m) > e: lo = m
            else: hi = m
        return (lo + hi) / 2
    edges = [e for e in (89, 62, 44, 31, 22, 15.5, 11, 7.5, 5, 3.2, 2.0, 1.1) if e < elev(0) - 0.5]
    ys = [0.0] + [y_at(e) for e in edges] + [hy + 1]
    ys = sorted(set(round(y, 1) for y in ys if -1 <= y <= hy + 1))
    out = []
    mr, mg, mb = sh_; gr, gg, gb = li_[0] - sh_[0], li_[1] - sh_[1], li_[2] - sh_[2]
    for i in range(len(ys) - 1):
        y0, y1 = ys[i], ys[i + 1]
        if y1 - y0 < 1: continue
        em = max(0.6, elev((y0 + y1) / 2))
        S = (Hc - eye_y) / math.sin(math.radians(em))
        fx = min(0.05, S / (cam.focal * lam)); fy = min(0.11, fx / math.sin(math.radians(em)) * 0.85)
        ov = max(6.0, (y1 - y0) * 0.35)
        a0, a1 = (y0 - ov) if i else -40.0, min(hy + 1, y1 + ov)          # the top band starts above the frame: a lit edge never shows
        ss = min(22.0 if (over or night) else 14.0, 0.045 / ((fx + fy) / 2))
        # tiled: the noise repeats every W pixels across, so a sky twice as wide can slide by W and loop without a seam
        turb = ('<feTurbulence type="fractalNoise" baseFrequency="%.5f %.5f" numOctaves="5" seed="31" stitchTiles="stitch" x="0" y="%.0f" width="%d" height="%.0f" result="n0"/><feTile in="n0" result="n"/>' % (fx, fy, a0 - 2, W, a1 - a0 + 4)
                if tile else '<feTurbulence type="fractalNoise" baseFrequency="%.5f %.5f" numOctaves="5" seed="31" result="n"/>' % (fx, fy))
        defs.append(('<filter id="ck%d" filterUnits="userSpaceOnUse" x="%d" y="%.0f" width="%d" height="%.0f" color-interpolation-filters="sRGB">' % (i, -20 if tile else 0, a0 - 2, (2 * W + 40) if tile else W, a1 - a0 + 4)) + turb +
                    ('<feColorMatrix in="n" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  1 0 0 0 0" result="h"/>'
                    '<feDiffuseLighting in="h" surfaceScale="%.1f" diffuseConstant="1.1" lighting-color="#FFFFFF" result="l"><feDistantLight azimuth="%.0f" elevation="%.0f"/></feDiffuseLighting>'
                    '<feColorMatrix in="l" type="matrix" values="%.3f 0 0 0 %.3f  0 %.3f 0 0 %.3f  0 0 %.3f 0 %.3f  0 0 0 0 1" result="c"/>'
                    '<feColorMatrix in="n" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  %.2f 0 0 0 %.2f" result="a"/>'
                    '<feComposite in="c" in2="a" operator="in"/></filter>')
                    % (ss, laz, lel, gr, mr, gg, mg, gb, mb, k, -k * t))
        stops = []
        span = a1 - a0
        for (yy, op) in ((a0, 0 if i else 1), (min(a1, a0 + 2 * ov) if i else a0, 1), (max(a0, a1 - 2 * ov), 1), (a1, 0 if a1 < hy else 0.0)):
            stops.append('<stop offset="%.3f" stop-color="#FFF" stop-opacity="%.2f"/>' % ((yy - a0) / span if span else 0, op))
        WW = 2 * W if tile else W
        defs.append('<linearGradient id="ckg%d" gradientUnits="userSpaceOnUse" x1="0" y1="%.1f" x2="0" y2="%.1f">%s</linearGradient><mask id="ckm%d" maskUnits="userSpaceOnUse" x="0" y="%.1f" width="%d" height="%.1f"><rect y="%.1f" width="%d" height="%.1f" fill="url(#ckg%d)"/></mask>'
                    % (i, a0, a1, ''.join(stops), i, a0, WW, span, a0, WW, span, i))
        out.append('<rect y="%.1f" width="%d" height="%.1f" filter="url(#ck%d)" mask="url(#ckm%d)"/>' % (a0, WW, span, i, i))
    return ''.join(out)
