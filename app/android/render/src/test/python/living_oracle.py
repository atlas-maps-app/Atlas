#!/usr/bin/env python3
"""Oracle for Living.kt: render/living.py's helpers on sample inputs, build_scene, every draw_* function on every
feature of the scene, and the whole render for the main view and a few seeded cameras, in every theme.
Run with PYTHONHASHSEED=0. Nothing is written into cache/render.

    living_oracle.py funcs                  JSON on stdout: inputs and outputs of the helpers, and the scene
    living_oracle.py draws OUTFILE          every draw_* on every feature, per view and theme, as JSON in OUTFILE
    living_oracle.py views OUTDIR           every view x theme: OUTDIR/<view>_<theme>.svg, OUTDIR/views.json
"""
import datetime, json, math, os, random, sys, time

import living as LV
from city import Camera

THEMES = {'noon': LV.NOON, 'golden': LV.GOLDEN, 'night': LV.NIGHT}
DRAW_VIEWS = ('main', 'seed3', 'seed4')
MAIN = dict(W=412, H=915, target=(4, 0, -8), yaw=12, pitch=42, dist=200, fov=36, cy=500)


def views():
    """The view living.py's __main__ renders, and cameras drawn from a few seeds around the neighbourhood."""
    out = {'main': dict(MAIN)}
    for seed in (1, 2, 3, 4, 5, 6):
        r = random.Random(seed)
        cfg = dict(W=412, H=r.choice([915, 600]), target=(r.uniform(-60, 120), 0, r.uniform(-80, 60)),
                   yaw=r.uniform(-180, 180), pitch=r.uniform(14, 70), dist=r.uniform(60, 700), fov=r.choice([36, 42, 50]),
                   cy=r.choice([None, r.uniform(300, 560)]))
        if r.random() < 0.4: cfg['cx'] = r.uniform(150, 260)
        out['seed%d' % seed] = cfg
    return out


def cam_of(cfg):
    return Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))


def prepared(th, cfg, F):
    """render()'s preamble: the camera, the sun and the scene."""
    cam = cam_of(cfg)
    t_utc = datetime.datetime(2026, 9, 30, th['hhmm'][0] + 4, th['hhmm'][1]) if th['hhmm'][0] + 4 < 24 else datetime.datetime(2026, 10, 1, th['hhmm'][0] + 4 - 24, th['hhmm'][1])
    az, el = LV.sun_position(t_utc)
    L = LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
    th = dict(th); th['_L'] = L
    return LV.Scene(F, th, cam, L)


def funcs():
    r = random.Random(77)
    out = {}
    times = [datetime.datetime(2026, 9, 30, h, m) for h in range(0, 24, 3) for m in (0, 20)]
    times += [datetime.datetime(2026, 6, 21, 16, 0), datetime.datetime(2025, 12, 21, 21, 30), datetime.datetime(2000, 1, 1, 12, 0),
              datetime.datetime(1999, 12, 31, 23, 59), datetime.datetime(2026, 10, 1, 1, 30)]
    out['sun_position'] = [[[t.year, t.month, t.day, t.hour, t.minute], LV.sun_position(t)] for t in times]
    sv = [(r.uniform(0, 360), r.uniform(-10, 90)) for _ in range(40)] + [(0, 0), (90, 45), (180, 90), (270, 5)]
    out['sun_vector'] = [[a, e, LV.sun_vector(a, e)] for a, e in sv]
    oh = ['24/7', '07:00-19:00', '17:00-02:00', '10:00-19:00', '08:00-18:00', '11:00-21:00', '12:00-22:00', '09:00-17:00',
          '00:00-00:01', '22:00-06:00,07:00-08:00', '10:00-12:00, 13:00-18:00']
    hh = [(0, 0), (0, 30), (1, 59), (2, 0), (7, 0), (12, 0), (17, 20), (18, 59), (19, 0), (21, 30), (23, 59)]
    out['open_at'] = [[o, list(t), LV.open_at(o, t)] for o in oh for t in hh]
    hts = [{}, {'building:levels': '4'}, {'building:levels': '2', 'roof:shape': 'gabled'}, {'building:levels': '8', 'roof:shape': 'flat'},
           {'building:levels': '3.5'}, {'roof:shape': 'flat'}]
    out['height_of'] = [[t, LV.height_of(t)] for t in hts]
    out['quad_at'] = []
    for _ in range(30):
        a = (r.uniform(-50, 50), r.uniform(-50, 50)); b = (r.uniform(-50, 50), r.uniform(-50, 50))
        args = [a, b, r.uniform(0, 3), r.uniform(3, 9), r.uniform(0, .5), r.uniform(.5, 1)]
        if r.random() < 0.6: args += [r.uniform(0, 1), (r.uniform(-1, 1), 0, r.uniform(-1, 1))]
        out['quad_at'].append([args, LV.quad_at(*args)])
    F, blocks, park = LV.build_scene()
    out['scene'] = {'F': F, 'blocks': blocks, 'park': park}
    rows = []
    for name, th0 in THEMES.items():
        sc = prepared(th0, MAIN, F)
        th = sc.th
        for _ in range(25):
            base = (r.uniform(0, 255), r.uniform(0, 255), r.uniform(0, 255))
            n = (r.uniform(-1, 1), r.uniform(-1, 1), r.uniform(-1, 1))
            lit = r.random() < 0.5
            rows.append([name, base, n, lit, LV.light(th, base, n, lit)])
    out['light'] = rows
    sl = []
    sc = prepared(LV.GOLDEN, MAIN, F)
    bl = [f for f in F if f['kind'] == 'building']
    for _ in range(300):
        p = (r.uniform(-130, 250), r.uniform(0, 30), r.uniform(-200, 170))
        k = r.randrange(-1, len(bl))
        sl.append([p, k, LV.sunlit(sc, p, skip=bl[k] if k >= 0 else None)])
    out['L'] = {nm: prepared(th, MAIN, F).L for nm, th in THEMES.items()}
    out['sunlit'] = sl
    gt = []
    for vn, cfg in views().items():
        cam = cam_of(cfg)
        for _ in range(12):
            p0 = (r.uniform(-100, 200), r.uniform(0, 6), r.uniform(-150, 150))
            u = r.choice([(1, 0, 0), (0, 0, 1), (0, 0, -1), (r.uniform(-1, 1), 0, r.uniform(-1, 1))])
            v = r.choice([(0, 0, 1), (1, 0, 0), (0, -1, 0)])
            args = [p0, u, v, r.choice([0.62, 1.2, 2.4]), '#F4F1EA', r.choice([0.0, 0.18, 0.62, 1.0])]
            gt.append([vn, args, LV.ground_text(cam, 'N 7TH ST', *args)])
            fp = [(p0[0] + r.uniform(-5, 5), r.uniform(0, 9), p0[2] + r.uniform(-5, 5)) for _ in range(4)]
            gt.append([vn, ['face', fp], list(LV.face_path(cam, fp, '#123456', ' opacity=".5"'))])
    out['ground_text'] = gt
    out['views'] = views()
    return out


def draws(path):
    """Every draw function on every feature it draws, for each view and theme, as render() calls them (with a fresh
    Random(9) per view and theme for the people in the park)."""
    F, blocks, park = LV.build_scene()
    res = []
    for vn, cfg in views().items():
        if vn not in DRAW_VIEWS: continue
        for tn, th0 in THEMES.items():
            sc = prepared(th0, cfg, F)
            cam = sc.cam
            night = sc.th['night']
            rg = random.Random(9)
            glow, anchors = [], {}
            for i, f in enumerate(F):
                k = f['kind']
                if k == 'building':
                    x0, x1, z0, z1 = f['geom']
                    d = cam.cam(((x0 + x1) / 2, 4, (z0 + z1) / 2))[2]
                elif k == 'playground':
                    g_ = f['geom']; d = cam.cam(((g_[0][0] + g_[2][0]) / 2, 1, (g_[0][1] + g_[2][1]) / 2))[2]
                elif k in ('tree', 'lamp', 'hydrant', 'bench', 'subway', 'bus_stop', 'bike_rack', 'fountain', 'person_park'):
                    g = f['geom']; d = cam.cam((g[0], 1, g[1]))[2]
                else:
                    continue
                if d < 2: continue
                if k == 'building': o = LV.draw_building(sc, f, d, glow, anchors)
                elif k == 'tree': o = LV.draw_tree(sc, f, d, rg)
                elif k == 'lamp': o = LV.draw_lamp(sc, f, d, glow)
                elif k == 'hydrant': o = LV.draw_post(sc, f, 0.85, 0.28, '#C0392B' if not night else '#4A1F1C', '#E8C04A')
                elif k == 'bench': o = LV.draw_bench(sc, f)
                elif k == 'subway': o = LV.draw_subway(sc, f, glow, anchors)
                elif k == 'bus_stop': o = LV.draw_shelter(sc, f, glow)
                elif k == 'bike_rack': o = LV.draw_rack(sc, f)
                elif k == 'fountain': o = LV.draw_post(sc, f, 0.95, 0.35, '#5A6B7A' if not night else '#1A2230', '#7FC3FF' if not night else '#2A4A6A')
                elif k == 'person_park': o = LV.draw_person(sc, {'geom': f['geom'], 'tags': {'_col': rg.choice(['#B8322A', '#1E3A5F', '#E8E8E4', '#7B4DFF', '#C9A36A'])}})
                elif k == 'playground': o = LV.draw_playground(sc, f)
                res.append({'view': vn, 'theme': tn, 'i': i, 'd': d, 'out': o})
            # cars and tanks and boxes at fixed places
            extra = []
            for (x, z, o_) in ((3.5, -40.0, 'ns'), (-6.4, 52.0, 'ns'), (30.0, -4.4, 'ew'), (150.0, 94.4, 'ew')):
                c = cam.cam((x, 1, z))
                if c[2] >= 2: extra.append(['car', [x, z, o_], LV.draw_car(sc, {'geom': (x, z, o_), 'tags': {'_col': '#B8322A'}})])
            for (x, z) in ((10.0, -20.0), (-30.0, 30.0)):
                c = cam.cam((x, 1, z))
                if c[2] >= 2: extra.append(['person', [x, z], LV.draw_person(sc, {'geom': (x, z), 'tags': {'_col': '#1E3A5F'}})])
            for (x, z, h) in ((-35.0, -28.0, 20.2), (60.0, 60.0, 13.8)):
                d = cam.cam((x, h, z))[2]
                if d >= 2: extra.append(['tank', [x, z, h, d], LV.draw_tank(sc, x, z, h, d, None)])
            extra.append(['box', [], LV.box(sc, -2, 2, 0, 3, -60, -56, (180, 50, 40))])
            extra.append(['box_unlit', [], LV.box(sc, 20, 24, 0, 6, 20, 26, (90, 120, 200), lit_ok=False)])
            res.append({'view': vn, 'theme': tn, 'extra': extra, 'anchors': anchors, 'glow': glow})
    with open(path, 'w') as f: json.dump(res, f)
    print(json.dumps({'rows': len(res)}))


def views_main(outdir):
    os.makedirs(outdir, exist_ok=True)
    F, blocks, park = LV.build_scene()
    res = {}
    for vn, cfg in views().items():
        for tn, th in THEMES.items():
            t0 = time.time()
            svg, anc, cam = LV.render(th, cfg, F, blocks, park)
            dt = time.time() - t0
            name = '%s_%s' % (vn, tn)
            with open(os.path.join(outdir, name + '.svg'), 'w') as f: f.write(svg)
            res[name] = {'view': vn, 'theme': tn, 'cfg': cfg, 'anchors': anc, 'seconds': dt, 'chars': len(svg)}
    with open(os.path.join(outdir, 'views.json'), 'w') as f: json.dump(res, f)
    print(json.dumps({k: [v['seconds'], v['chars']] for k, v in res.items()}))


if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'funcs':
        json.dump(funcs(), sys.stdout)
    elif mode == 'draws':
        draws(sys.argv[2])
    elif mode == 'views':
        views_main(sys.argv[2])
