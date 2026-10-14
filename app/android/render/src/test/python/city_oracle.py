#!/usr/bin/env python3
"""Oracle for City.kt: render/city.py's functions on sample inputs, its build_city, and its render for the views
that render/views.py defines. Run with PYTHONHASHSEED=0. Nothing is written into cache/render.

    city_oracle.py funcs                       JSON on stdout: inputs and outputs of every helper
    city_oracle.py build x0 x1 z0 z1 FILE      build_city(region) as canonical lines in FILE
    city_oracle.py views OUTDIR [names…]       render every view: OUTDIR/<view>.svg, OUTDIR/views.json (cfgs, anchors, times)
"""
import json, math, os, random, sys, time

import city as C


def fl(v):
    """Geometry with every number as a float: ints and floats of equal value draw the same, so the canonical
    text does not tell them apart (ids keep their types: they are hashed)."""
    if isinstance(v, bool) or isinstance(v, str) or v is None: return v
    if isinstance(v, (int, float)): return float(v)
    if isinstance(v, tuple): return tuple(fl(x) for x in v)
    return [fl(x) for x in v]


def canon_bldg(b):
    return 'B %r %r %r %r %r %r %r' % (fl(b.foot), fl(b.h), b.kind, b.col, b.id, fl(b.y0), b.named)


def canon_city(c):
    """One line per thing build_city makes, in order, with repr() of every value."""
    out = []
    for p in c['pads']: out.append('pad %r' % (fl(p),))
    for k, p in c['parks']: out.append('park %r %r' % (k, fl(p)))
    for b in c['bldgs']: out.append(canon_bldg(b))
    for t in c['trees']: out.append('tree %r' % (fl(t),))
    out.append('water %r' % (fl(c['water']),))
    out.append('esp %r' % (fl(c['esp']),))
    return out


def bldg_json(b):
    return {'foot': b.foot, 'h': b.h, 'kind': b.kind, 'col': b.col, 'id': b.id, 'y0': b.y0, 'named': b.named}


def cam_json(cam):
    return {'C': cam.C, 'f': cam.f, 'r': cam.r, 'u': cam.u, 'W': cam.W, 'H': cam.H, 'focal': cam.focal,
            'cx': cam.cx, 'cy': cam.cy, 'near': cam.near}


def themes():
    import views
    edged = dict(C.DAY)
    edged.update(name='edged', edge_top='#334455', edge_side='#223344', edge_op='.6', edge_w='.5',
                 pal=['#101010', '#202020', '#303030'], garage='#778899')
    return {'day': C.DAY, 'night': C.NIGHT, 'chart': views.CHART, 'gray': views.GRAY, 'edged': edged}


def funcs():
    r = random.Random(2026)
    out = {}
    U = r.uniform
    vecs = []
    for _ in range(300):
        a = (U(-500, 500), U(-500, 500), U(-500, 500)); b = (U(-500, 500), U(-500, 500), U(-500, 500)); t = U(-0.5, 1.5)
        vecs.append({'a': a, 'b': b, 't': t, 'vsub': C.vsub(a, b), 'vdot': C.vdot(a, b), 'vcross': C.vcross(a, b),
                     'vnorm': C.vnorm(a), 'mix': C.mix(a, b, t), 'mulc': C.mulc(a, t), 'L_': C.L_(*a)})
    out['vec'] = vecs
    hexes = ['#000000', '#ffffff', '#A9C4E6', 'f4e7d9', '#12aBcD', '##0a0b0c']
    out['hx'] = [[h, C.hx(h)] for h in hexes]
    cols = [(0.5, 1.5, 2.5), (254.5, 255.5, -0.5), (-3, 300, 127.49999999999999), (12.5, 13.5, 100.50000000000001)]
    for _ in range(400):
        cols.append(tuple(r.choice([U(-20, 280), r.randint(0, 255) + 0.5, r.randint(0, 255) + r.choice([0.4999, 0.5001])])
                          for _ in range(3)))
    out['tohex'] = [[c, C.tohex(c)] for c in cols]
    out['clamp'] = [[x, a, b, C.clamp(x, a, b)] for x, a, b in
                    [(U(-2, 2), 0.0, 1.0) for _ in range(50)] + [(5, 1, 3), (-0.0, 0.0, 1.0), (0.0, -0.0, 1.0), (2, 2, 2)]]
    out['clamp1'] = [[x, C.clamp(x)] for x in [U(-2, 2) for _ in range(30)] + [-0.0, 1, 0]]
    polys = []
    for _ in range(100):
        n = r.randint(1, 9)
        polys.append([(U(-1000, 1000), U(-1000, 1000)) for _ in range(n)])
    polys.append([(0.05, -0.05), (0.25, 0.35), (-0.04999, 1e-9), (123456.75, -0.15)])
    out['P'] = [[p, C.P(p)] for p in polys]
    out['area'] = [[p, C.area(p)] for p in polys if len(p) > 0]
    # cameras like the views' and odd ones
    cams = []
    specs = [((330, 0, -700), -36, 17, 1300, 42, 412, 915, None, 434, 0.6), ((250, 6, -818), -150, 33, 170, 40, 412, 470, None, 250, 0.6),
             ((446, 0, -775), -3, 27, 150, 58, 412, 915, None, 470, 0.6), ((0, 0, 0), 0, 90, 100, 30, 300, 300, None, None, 0.6),
             ((10.5, 2, -3), 45, 0, 50, 60, 200, 400, 90, 100, 1.5), ((0, 0, 0), 180, 5, 10, 90, 412, 915, None, None, 0.6)]
    for _ in range(14):
        specs.append(((U(-1000, 1000), U(0, 50), U(-2000, 500)), U(-180, 180), U(1, 89), U(20, 3000), U(20, 80),
                      r.choice([412, 300]), r.choice([915, 470]), r.choice([None, U(0, 412)]), r.choice([None, U(0, 915)]),
                      r.choice([0.6, 1.0])))
    for (target, yaw, pitch, dist, fov, W, H, cx, cy, near) in specs:
        cam = C.Camera(target, yaw, pitch, dist, fov, W, H, cx=cx, cy=cy, near=near)
        pts = [(U(-1500, 1500), U(-10, 200), U(-3000, 1500)) for _ in range(40)] + [cam.C, target]
        rows = []
        for p in pts:
            rows.append({'p': p, 'cam': cam.cam(p), 'proj': cam.proj(p), 'depth': cam.depth(p)})
        pls = []
        for _ in range(40):
            cx0, cz0 = U(-1500, 1500), U(-3000, 1500); n = r.randint(3, 8)
            pl = [(cx0 + U(-300, 300), U(0, 80), cz0 + U(-300, 300)) for _ in range(n)]
            m = r.choice([40, 0, 4000, 2000])
            pls.append({'pts': pl, 'margin': m, 'poly': cam.poly(pl, margin=m)})
        pl = [(target[0] - 5, 0, target[2] - 5), (target[0] + 5, 0, target[2] - 5), (target[0] + 5, 0, target[2] + 5)]
        pls.append({'pts': pl, 'margin': 40, 'poly': cam.poly(pl)})
        gd = [[sy, gy, cam.ground_depth(sy, gy)] for sy in [U(-200, 1100) for _ in range(20)] + [cam.cy, 0, H]
              for gy in (0.0, 13.0)]
        cams.append({'args': [target, yaw, pitch, dist, fov, W, H, cx, cy, near], 'cam': cam_json(cam), 'pts': rows,
                     'polys': pls, 'ground_depth': gd, 'horizon_y': cam.horizon_y()})
    out['cams'] = cams
    hulls = []
    for _ in range(150):
        n = r.randint(0, 14)
        pts = [(r.choice([U(-50, 50), round(U(-5, 5), 3), r.randint(-3, 3) * 1.0, -0.0, 0.0004, -0.0004]),
                r.choice([U(-50, 50), round(U(-5, 5), 3), r.randint(-3, 3) * 1.0, -0.0, 0.0004, -0.0004])) for _ in range(n)]
        hulls.append([pts, C.hull2(pts)])
    sq = [(0, 0), (1, 0), (1, 1), (0, 1), (0.5, 0.5), (1, 0), (0.0001, 0.0002)]
    hulls.append([sq, C.hull2(sq)])
    out['hull2'] = hulls
    lines = []
    for _ in range(80):
        n = r.randint(2, 7)
        pl = [(U(-500, 500), U(-500, 500)) for _ in range(n)]
        if r.random() < 0.2: pl[1] = pl[0]
        rad = r.choice([12, 14, 20, 4, U(1, 30)]); seg = r.choice([6, 3, 1, 8]); w = r.choice([4, 15, 2.2, U(0.1, 30)])
        step = r.choice([15, 10, U(1, 50)])
        try: sm = C.smooth(pl, rad, seg)
        except ZeroDivisionError: sm = 'ZeroDivisionError'
        lines.append({'pts': pl, 'r': rad, 'seg': seg, 'w': w, 'step': step, 'smooth': sm, 'ribbon': C.ribbon(pl, w),
                      'densify': C.densify(pl, step)})
    out['lines'] = lines
    zs = [U(-9000, 4000) for _ in range(300)] + list(range(-9000, 4001, 50))[:300] + [-1610, 0, -0.0]
    out['shore'] = [[z, C.shore_e(z), C.shore_w(z)] for z in zs]
    keys = [('b', 3, -12), ('m', -7, 4), ('t', 0, 0), ('c', ('b', 4, -12, 503)), ('r', ('n', 'lume')),
            ('w', ('m', -1, 2, -130, 77), -140, 77), ('rb', ('n', 'home'), 401.25), ('wt', ('b', 1, 2, 3)), ('c', ('br',))]
    out['rng_for'] = []
    for k in keys:
        g = C.rng_for(*k)
        out['rng_for'].append([list(k), [g.random() for _ in range(3)], g.uniform(-0.025, 0.02), g.randrange(1000)])
    out['rect'] = [[a, C.rect(*a)] for a in [(1, 2, 3, 4), (U(-9, 9), U(-9, 9), U(-9, 9), U(-9, 9))]]
    rs = [(U(0, 10), U(0, 10), U(0, 10), U(0, 10)) for _ in range(50)]
    out['overlaps'] = [[a, b, C.overlaps(a, b)] for a in rs[:10] for b in rs[10:20]] + [[(0, 1, 0, 1), (1, 2, 0, 1), False]]
    # themes, colours and buildings
    th = themes()
    out['themes'] = th
    fm = []
    for name, t in th.items():
        for _ in range(40):
            c = (U(0, 255), U(0, 255), U(0, 255)); d = r.choice([U(-100, 9000), t['fog0'], t['fog1'], 0])
            n = C.vnorm((U(-1, 1), U(-1, 1), U(-1, 1)))
            top = r.random() < 0.3
            fm.append({'theme': name, 'c': c, 'd': d, 'n': n, 'top': top, 'fogmix': C.fogmix(t, c, d),
                       'face_color': C.face_color(t, c, n, d, top=top)})
    out['fog'] = fm
    city = C.build_city((-900, 1400, -2400, 400))
    sample = city['bldgs'][::37] + [b for b in city['bldgs'] if b.named]
    sample.append(C.Bldg(C.rect(0, 10, 0, 10), 5, 'car', 13, ('car', 1)))
    sample.append(C.Bldg(C.rect(0, 10, 0, 10), 5, 'garage', 2, ('g', 1)))
    bc = []
    for name, t in th.items():
        for b in sample:
            for hl in (None, 'lume', 'marlow'):
                bc.append({'theme': name, 'b': bldg_json(b), 'hl': hl, 'base_color': C.base_color(t, b, hl)})
    out['base_color'] = bc
    fs = []
    for name, t in th.items():
        for _ in range(5):
            sp = [(U(0, 400), U(0, 900)) for _ in range(4)]
            for top in (False, True):
                fs.append({'theme': name, 'sp': sp, 'hc': '#123abc', 'top': top, 'svg': C.face_svg(t, sp, '#123abc', top)})
    out['face_svg'] = fs
    # emitters, with the views' cameras
    import views
    em = []
    for vn, extra in (('place', {}), ('explore', {}), ('nav', {}), ('charge_min_area', {'min_area': 2.0}), ('boat', {}), ('passport', {})):
        cfg = dict(views.VIEWS[vn.replace('_min_area', '')]); cfg.update(extra)
        cam = C.Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
        t = cfg['theme']
        near = [b for b in city['bldgs'] if 0 < cam.depth((b.foot[0][0], 0, b.foot[0][1])) < 900]
        near = near[::max(1, len(near) // 120)] + [b for b in city['bldgs'] if b.named]
        for b in near:
            cxz = (sum(p[0] for p in b.foot) / len(b.foot), sum(p[1] for p in b.foot) / len(b.foot))
            d = cam.cam((cxz[0], b.y0 + b.h * 0.5, cxz[1]))[2]
            em.append({'view': vn, 'b': bldg_json(b), 'd': d,
                       'emit_bldg': C.emit_bldg(cam, t, b, d, cfg.get('highlight'), cfg),
                       'emit_simple': C.emit_simple(cam, t, b, (200, 190, 180), d),
                       'emit_simple_apex': C.emit_simple(cam, t, b, (200, 190, 180), d, apex=(cxz[0], b.y0 + b.h + 5, cxz[1])),
                       'roof_bits': C.roof_bits(cam, t, b, (200, 190, 180), d) if b.h < 40 else None})
    out['emit'] = em
    # day_lines and windows face by face, as emit_bldg calls them, but for every face with a projection
    fl_ = []
    for vn in ('explore', 'nav', 'place'):
        cfg = views.VIEWS[vn]
        cam = C.Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
        t = cfg['theme']
        near = [b for b in city['bldgs'] if 0 < cam.depth((b.foot[0][0], 0, b.foot[0][1])) < 1200]
        for b in near[::max(1, len(near) // 80)]:
            cxz = (sum(p[0] for p in b.foot) / len(b.foot), sum(p[1] for p in b.foot) / len(b.foot))
            d = cam.cam((cxz[0], b.y0 + b.h * 0.5, cxz[1]))[2]
            base = C.base_color(t, b)
            n = len(b.foot)
            for i in range(n):
                a = b.foot[i]; bb = b.foot[(i + 1) % n]
                ex, ez = bb[0] - a[0], bb[1] - a[1]
                sp = cam.poly([(a[0], b.y0, a[1]), (bb[0], b.y0, bb[1]), (bb[0], b.y0 + b.h, bb[1]), (a[0], b.y0 + b.h, a[1])])
                if not sp: continue
                ar = C.area(sp)
                nrm = C.vnorm((ez, 0, -ex))
                col, fg = C.face_color(t, base, nrm, d)
                elen = math.hypot(ex, ez)
                fl_.append({'view': vn, 'b': bldg_json(b), 'i': i, 'd': d, 'col': col, 'fg': fg, 'ar': ar, 'elen': elen,
                            'day_lines': C.day_lines(cam, t, b, a, bb, b.y0, b.y0 + b.h, elen, col, fg),
                            'windows': C.windows(cam, t, b, a, bb, b.y0, b.y0 + b.h, elen, d, fg, ar)})
    out['faces'] = fl_
    out['view_cfgs'] = {vn: {k: (v['name'] if k == 'theme' else v) for k, v in cfg.items()} for vn, cfg in views.VIEWS.items()}
    out['view_cfgs']['charge_min_area'] = dict(out['view_cfgs']['charge'], min_area=2.0)
    out['bridge_parts'] = C.bridge_parts()
    eb = []
    for vn in ('explore', 'nav', 'boat', 'route'):
        cfg = views.VIEWS[vn]
        cam = C.Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
        for k, seg in enumerate(C.bridge_parts()):
            d = cam.cam(seg['c'])[2]
            if d <= 0: continue
            eb.append({'view': vn, 'k': k, 'd': d, 'svg': C.emit_bridge(cam, cfg['theme'], seg, d)})
    out['emit_bridge'] = eb
    return out


def seeded_views():
    """Cameras and themes drawn from a few seeds, beyond the views' own."""
    import views
    out = {}
    for seed in (1, 2, 3, 4):
        r = random.Random(seed)
        th = r.choice([C.DAY, C.NIGHT, views.CHART, views.GRAY])
        cfg = dict(W=412, H=r.choice([915, 470]), theme=th, target=(r.uniform(-400, 800), r.choice([0, 6]), r.uniform(-1800, 100)),
                   yaw=r.uniform(-180, 180), pitch=r.uniform(8, 70), dist=r.uniform(120, 2500), fov=r.uniform(36, 60),
                   cy=r.choice([None, r.uniform(200, 500)]), region=views.REG)
        if r.random() < 0.5: cfg['roof_details'] = 400
        if r.random() < 0.5: cfg['highlight'] = r.choice(['lume', 'marlow'])
        out['seed%d' % seed] = cfg
    return out


def views_main(outdir, names):
    import views
    os.makedirs(outdir, exist_ok=True)
    res = {}
    cache = {}
    every = dict(views.VIEWS)
    every.update(seeded_views())
    for n in names or list(every):
        cfg = every[n]
        reg = cfg['region']
        t0 = time.time()
        if reg not in cache: cache[reg] = C.build_city(reg)
        t1 = time.time()
        svg, anc, cam = C.render(cfg, cache[reg], n)
        t2 = time.time()
        with open(os.path.join(outdir, n + '.svg'), 'w') as f: f.write(svg)
        res[n] = {'cfg': cfg, 'anchors': anc, 'build_s': t1 - t0, 'render_s': t2 - t1, 'chars': len(svg)}
    with open(os.path.join(outdir, 'views.json'), 'w') as f: json.dump(res, f)
    print(json.dumps({k: [v['build_s'], v['render_s'], v['chars']] for k, v in res.items()}))


if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'funcs':
        json.dump(funcs(), sys.stdout)
    elif mode == 'build':
        region = tuple(float(v) if '.' in v else int(v) for v in sys.argv[2:6])
        t0 = time.time()
        c = C.build_city(region)
        dt = time.time() - t0
        with open(sys.argv[6], 'w') as f: f.write('\n'.join(canon_city(c)) + '\n')
        print(json.dumps({'seconds': dt, 'bldgs': len(c['bldgs']), 'trees': len(c['trees'])}))
    elif mode == 'views':
        views_main(sys.argv[2], sys.argv[3:])
