#!/usr/bin/env python3
"""Oracle for Closeup.kt: render/closeup.py's functions on sample inputs, and paving() over the real streets of
data/ams/raw.json from eye-level cameras. Run with PYTHONHASHSEED=0. Nothing is written into cache/render.

    closeup_oracle.py funcs       JSON on stdout: every function's inputs and outputs
    closeup_oracle.py paving      JSON on stdout: the Westerkerk's streets, cameras along them, and paving() for each

light() and Shadows.lit, which closeup takes from amsterdam and real, are stood in for by small stubs that
CloseupTest repeats exactly (light_stub, Lit).
"""
import json, math, random, sys, time

import city as C
import closeup as CU


def light_stub(th, base, n, lit):
    L = th['_L']
    k = 0.55 + 0.35 * max(0.0, n[0] * L[0] + n[1] * L[1] + n[2] * L[2]) + (0.12 if lit else 0.0)
    return (base[0] * k, base[1] * k, base[2] * k)


class Lit:
    def lit(self, p):
        return int(math.floor(p[0] * 0.37 + p[2] * 0.11)) % 2 == 0


def side_col(sf):
    return {'paving_stones': '#C9C4BA', 'sett': '#B8B0A4', 'asphalt': '#9A9A9A'}.get(sf, '#C4C0B8')


def cam_of(cfg):
    return C.Camera(tuple(cfg['target']), cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'])


def rand_cam(r, target, pitch=None, dist=None):
    return dict(target=list(target), yaw=r.uniform(0, 360), pitch=r.uniform(-5, 25) if pitch is None else pitch,
                dist=r.uniform(0.2, 3.0) if dist is None else dist, fov=r.uniform(40, 75), W=412, H=915)


def sun(r, night=False):
    if night: return (0.0, -1.0, 0.0)
    az, el = r.uniform(0, 2 * math.pi), r.uniform(0.05, 1.2)
    return (math.cos(el) * math.sin(az), math.sin(el), -math.cos(el) * math.cos(az))


def funcs():
    r = random.Random(2026)
    out = {'pattern_defs': [CU.pattern_defs(False), CU.pattern_defs(True)], 'crown_filter': CU.crown_filter()}
    # sidewalk_sides over every combination that matters
    vals = [None, '', 'both', 'left', 'right', 'yes', 'no', 'separate']
    sw = []
    for a in vals:
        for b in (None, '', 'yes', 'separate'):
            for _ in range(3):
                t = {}
                if a is not None: t['sidewalk'] = a
                if b is not None: t['sidewalk:both'] = b
                for k in ('sidewalk:left', 'sidewalk:right'):
                    v = r.choice([None, 'yes', 'separate', 'no'])
                    if v is not None: t[k] = v
                for k in ('sidewalk:left:surface', 'sidewalk:right:surface', 'sidewalk:both:surface', 'sidewalk:surface'):
                    v = r.choice([None, None, '', 'paving_stones', 'sett', 'asphalt'])
                    if v is not None: t[k] = v
                sides, surf = CU.sidewalk_sides(t)
                sw.append(dict(t=t, sides=list(sides), surf=[[s, surf[s]] for s in surf]))
    out['sidewalk_sides'] = sw
    # clipping in camera space, against screen columns and depth bands
    clips = []
    for _ in range(400):
        cfg = rand_cam(r, (r.uniform(-50, 50), r.uniform(0, 3), r.uniform(-50, 50)))
        cam = cam_of(cfg)
        n = r.randint(3, 7)
        cs = [(r.uniform(-30, 30), r.uniform(-5, 5), r.uniform(-5, 60)) for _ in range(n)]
        sx0, sx1 = r.choice([(-1e9, r.uniform(0, 412)), (r.uniform(-50, 300), r.uniform(100, 500)), (r.uniform(0, 412), 1e9)])
        z0 = r.uniform(0.5, 20); z1 = z0 * r.uniform(1.01, 1.5)
        clips.append(dict(cam=cfg, cs=cs, sx0=sx0, sx1=sx1, z0=z0, z1=z1, x=CU._clip_x(cam, cs, sx0, sx1), z=CU._clip_z(cs, z0, z1)))
    out['clip'] = clips
    near = []
    for _ in range(300):
        segs = [(k, r.uniform(-20, 20), r.uniform(-20, 20), r.uniform(-20, 20), r.uniform(-20, 20), r.uniform(1, 6)) for k in range(r.randint(0, 6))]
        if segs and r.random() < 0.2: segs.append((99, 3.0, 3.0, 3.0, 3.0, 2.0))          # a segment of zero length
        x, z = r.uniform(-20, 20), r.uniform(-20, 20); skip = r.choice([None, 0, 1, 99])
        near.append(dict(segs=segs, x=x, z=z, skip=skip, out=CU._near_carriageway(segs, x, z, skip)))
    out['near_carriageway'] = near
    segs = []
    for _ in range(300):
        cfg = rand_cam(r, (0.0, 1.6, 0.0)); cam = cam_of(cfg)
        p = (r.uniform(-20, 20), r.uniform(-2, 10), r.uniform(-20, 20)); q = (r.uniform(-20, 20), r.uniform(-2, 10), r.uniform(-20, 20))
        segs.append(dict(cam=cfg, p=p, q=q, out=CU._seg(cam, p, q)))
    out['seg'] = segs
    # walls: church windows and coursing, from cameras looking at them
    walls = []
    for i in range(260):
        ax, az = r.uniform(-30, 30), r.uniform(-30, 30); ang = r.uniform(0, 2 * math.pi); elen = r.choice([r.uniform(1, 4), r.uniform(4, 40)])
        bx, bz = ax + elen * math.cos(ang), az + elen * math.sin(ang); ex, ez = bx - ax, bz - az; el = math.hypot(ex, ez)
        nrm = (ez / el, 0.0, -ex / el)
        y0 = r.choice([0.0, r.uniform(0, 3)]); top = y0 + r.choice([r.uniform(0.1, 6), r.uniform(6, 35)])
        dd = r.choice([r.uniform(2, 15), r.uniform(15, 80), r.uniform(80, 300)])
        mx, mz = (ax + bx) / 2 + nrm[0] * dd, (az + bz) / 2 + nrm[2] * dd
        yaw = math.degrees(math.atan2(-nrm[0], nrm[2])) + r.uniform(-40, 40)
        cfg = dict(target=[mx, 1.6, mz], yaw=yaw, pitch=r.uniform(-25, 5), dist=0.01, fov=r.uniform(45, 70), W=412, H=915)
        cam = cam_of(cfg)
        night = r.random() < 0.3; th = dict(night=night, wx=r.choice([None, {'cloud': 0.3}]))
        course = r.choice([0.0625, 0.0625, 0.45, 0.3, 0.2])
        walls.append(dict(cam=cfg, th=th, a=(ax, az), b=(bx, bz), ex=ex, ez=ez, elen=el, nrm=nrm, y0=y0, top=top, d=dd, night=night, course=course,
                          church=CU.church_windows(cam, th, (ax, az), (bx, bz), ex, ez, el, nrm, y0, top, dd, '#8A8478', night),
                          coursing=CU.coursing(cam, (ax, az), (bx, bz), ex, ez, el, nrm, y0, top, course)))
    out['walls'] = walls
    # trees: tree_form, autumn and tree_close
    genera = list(CU.FORMS) + ['Tilia cordata', 'QUERCUS robur', 'pinus', '', 'Prunus']
    trees = []
    for i in range(220):
        t = {}
        k = r.random()
        if k < 0.15: t['leaf_type'] = 'needleleaved'
        g = r.choice(genera)
        if g: t[r.choice(['genus', 'species', 'taxon'])] = g
        if r.random() < 0.15: t['leaf_cycle'] = 'evergreen'
        date = (2026, r.randint(1, 12), r.randint(1, 28))
        night = r.random() < 0.25
        L = sun(r, night)
        th = dict(night=night, _L=L, date=date, wx={'cloud': 0.2})
        n = dict(x=r.uniform(-20, 20), z=r.uniform(-20, 20), h=r.choice([r.uniform(4, 25), 12.0]), crown=r.choice([r.uniform(2, 14), 7.0]), tags=t,
                 id=r.choice([r.randrange(1 << 30), r.randrange(1 << 40)]))
        dd = r.choice([r.uniform(2, 12), r.uniform(12, 40), r.uniform(40, 150), r.uniform(150, 900)])
        ang = r.uniform(0, 2 * math.pi)
        tx, tz = n['x'] + dd * math.cos(ang), n['z'] + dd * math.sin(ang)
        yaw = math.degrees(math.atan2(n['x'] - tx, -(n['z'] - tz))) + r.uniform(-15, 15)
        cfg = dict(target=[tx, 1.6, tz], yaw=yaw, pitch=r.uniform(-20, 8), dist=0.01, fov=r.uniform(45, 70), W=412, H=915)
        cam = cam_of(cfg)
        trees.append(dict(cam=cfg, th=th, n=n, d=dd, form=list(CU.tree_form(t)), autumn=CU.autumn(date, t),
                          close=CU.tree_close(cam, th, Lit(), n, dd, light_stub, None)))
    out['trees'] = trees
    # clouds: day, overcast and night skies, cameras from looking down to looking up, tiled and not
    skies = []
    for i in range(160):
        cfg = rand_cam(r, (r.uniform(-100, 100), r.uniform(1, 300), r.uniform(-100, 100)), pitch=r.uniform(-30, 60), dist=r.uniform(0.5, 400))
        cam = cam_of(cfg)
        night = r.random() < 0.25; over = (not night) and r.random() < 0.3
        th = dict(night=night, wx={'cloud': r.uniform(0, 1)}, _L=sun(r, night))
        if over: th['sky_k'] = r.uniform(0.2, 0.9)
        hy = cam.horizon_y() if r.random() < 0.8 else r.uniform(-100, 1000)
        tile = r.random() < 0.4
        defs = []
        body = CU.sky_clouds(cam, th, 412, hy, defs, cam.C[1], tile=tile)
        skies.append(dict(cam=cfg, th=th, hy=hy, eye=cam.C[1], tile=tile, out=body, defs=defs))
    out['skies'] = skies
    return out


def paving():
    import amsterdam as A
    t0 = time.time()
    S = A.load()
    roads = [dict(pts=r['pts'], w=r['w'], bridge=r['bridge'], tags=r['tags']) for r in S['roads']]
    r = random.Random(14)
    cases = []
    secs = 0.0
    for i in range(48):
        rd = S['roads'][r.randrange(len(S['roads']))]
        k = r.randrange(len(rd['pts'])); x, z = rd['pts'][k]
        pitch = r.choice([r.uniform(-4, 6), r.uniform(6, 25)])
        cfg = dict(target=[x + r.uniform(-3, 3), 1.6, z + r.uniform(-3, 3)], yaw=r.uniform(0, 360), pitch=pitch, dist=0.01, fov=r.uniform(45, 70), W=412, H=915)
        cam = cam_of(cfg)
        th = dict(night=r.random() < 0.3)
        t1 = time.time()
        under, over = CU.paving(S, cam, th, None, side_col)
        secs += time.time() - t1
        cases.append(dict(cam=cfg, th=th, under=under, over=over))
    return dict(roads=roads, cases=cases, secs=secs)


if __name__ == '__main__':
    mode = sys.argv[1]
    res = funcs() if mode == 'funcs' else paving()
    json.dump(res, sys.stdout)
