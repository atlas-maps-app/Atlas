#!/usr/bin/env python3
"""Oracle for Real.kt's stage 2 half: render/real.py's load (with the street-front pass of its __main__),
attach_shops, water_polygon, Shadows, theme, the draw functions, render (whole and split) and the anchors
its __main__ picks, on the New York extract. Run with PYTHONHASHSEED=0. Nothing is written into cache/render.

    real_render_oracle.py mkosm REPO OSMDIR         an osm/ folder for real.load: raw.json (a link to the New York
                                                    extract), bldg.json and trees2015.json made from Random(31)
    real_render_oracle.py load OSMDIR OUTFILE       load + fronts + attach_shops + water_polygon, as JSON
    real_render_oracle.py funcs OSMDIR OUTFILE      Shadows, theme, nearest_road_dir, tree_size, water_polygon,
                                                    draw_lamp / draw_car / draw_dock / draw_tree samples
    real_render_oracle.py views OSMDIR OUTDIR       every view: OUTDIR/<view>.svg, OUTDIR/views.json
    real_render_oracle.py anchors OSMDIR OUTFILE    the anchors and stats real.py's __main__ writes
    real_render_oracle.py all OSMDIR OUTDIR         all of these from one load: OUTDIR/load.json, anchors.json, views/, funcs.json
"""
import collections, json, math, os, random, sys, time

import real as R
import living as LV
from city import Camera

ORIGIN = (40.7308, -73.9973)        # data/regions/newyork/region.json


def mkosm(repo, osm):
    os.makedirs(osm, exist_ok=True)
    src = os.path.join(repo, 'data', 'regions', 'newyork', 'raw.json')
    dst = os.path.join(osm, 'raw.json')
    if os.path.lexists(dst): os.remove(dst)
    os.symlink(src, dst)
    raw = json.load(open(src))['elements']
    r = random.Random(31)
    bins = []
    for e in raw:
        b = (e.get('tags') or {}).get('nycdoitt:bin')
        if b and b not in bins: bins.append(b)
    bl = []
    for b in bins:
        u = r.random()
        if u < 0.15: continue
        yr = r.choice(['1899', '1910', '1925', '1931', '1950', '1962', '1987', '2004', '2015', '2021', '1650', '', 'abc', '０'])
        row = {'bin': b, 'construction_year': yr}
        if u < 0.2: del row['construction_year']
        bl.append(row)
    with open(os.path.join(osm, 'bldg.json'), 'w') as f: json.dump(bl, f)
    s, w, n, e = 40.727, -74.005, 40.7345, -73.993
    sp = ['Platanus x acerifolia', 'Gleditsia triacanthos var. inermis', 'Pyrus calleryana', 'Ginkgo biloba', 'Tilia cordata',
          'Quercus palustris', 'Acer rubrum', 'Zelkova serrata', 'Ulmus americana', 'Prunus', 'Sophora japonica', '']
    trees = []
    for k in range(400):
        t = {'tree_id': str(100000 + k), 'latitude': '%.7f' % r.uniform(s, n), 'longitude': '%.7f' % r.uniform(w, e),
             'status': r.choice(['Alive', 'Alive', 'Alive', 'Dead', 'Stump']), 'spc_common': 'tree %d' % k}
        latin = r.choice(sp)
        if latin: t['spc_latin'] = latin
        dbh = r.choice(['3', '8', '12', '17', '24', '33', '', 'x'])
        if dbh != 'x': t['tree_dbh'] = dbh
        trees.append(t)
    with open(os.path.join(osm, 'trees2015.json'), 'w') as f: json.dump(trees, f)
    print(json.dumps({'bins': len(bl), 'trees': len(trees)}))


def fronts(S):
    """real.py's __main__: which facade faces the street (the edge nearest any drive road)."""
    drive = [r for r in S['roads'] if r['hw'] != 'path']
    for b in S['buildings']:
        best = None
        for i in range(len(b['poly'])):
            m = ((b['poly'][i][0] + b['poly'][(i + 1) % len(b['poly'])][0]) / 2, (b['poly'][i][1] + b['poly'][(i + 1) % len(b['poly'])][1]) / 2)
            for r in drive:
                cx, cz = R.centroid(b['poly'])
                if abs(r['pts'][0][0] - cx) > 400 and abs(r['pts'][-1][0] - cx) > 400: continue
                for k in range(len(r['pts']) - 1):
                    dd, _ = R.seg_dist(m[0], m[1], r['pts'][k], r['pts'][k + 1])
                    if best is None or dd < best[0]: best = (dd, i)
        b['front'] = best[1] if best else None


def loaded(osm):
    R.OSM = osm
    t0 = time.time()
    S = R.load()
    t1 = time.time()
    fronts(S)
    t2 = time.time()
    R.attach_shops(S)
    t3 = time.time()
    water = R.water_polygon(S)
    return S, water, {'load': t1 - t0, 'fronts': t2 - t1, 'attach_shops': t3 - t2}


def s_json(S, water):
    bs = []
    for b in S['buildings']:
        bs.append({'poly': b['poly'], 'h': b['h'], 'levels': b['levels'], 'year': b['year'], 'mat': b['mat'], 'col': b['col'],
                   'tags': list(b['tags'].keys()), 'guessed': list(b['guessed'].items()), 'id': b['id'], 'front': b.get('front'),
                   'shops': [[s['edge'], s['u'], list(s['tags'].keys())] for s in b['shops']]})
    return {'buildings': bs,
            'roads': [{'pts': r['pts'], 'w': r['w'], 'hw': r['hw'], 'tags': list(r['tags'].keys())} for r in S['roads']],
            'crossings': [{'pts': c['pts'], 'tags': list(c['tags'].keys())} for c in S['crossings']],
            'areas': [{'poly': a['poly'], 'kind': a['kind']} for a in S['areas']],
            'water': S['water'],
            'nodes': [[n['x'], n['z'], n['id'], list(n['tags'].keys())] for n in S['nodes']],
            'trees': [[t['x'], t['z'], t['genus'], t['species'], t.get('common'), t['dbh'], t['source'], t['tags']] for t in S['trees']],
            'pois': [[p['x'], p['z'], list(p['tags'].keys())] for p in S['pois']],
            'water_polygon': water}


def cfgs(S):
    tx, tz = R.XZ(*ORIGIN)
    yaw = R.street_bearing(S, '5th Avenue')
    yaw6 = R.street_bearing(S, '6th Avenue')
    base = dict(W=412, H=915, target=(tx, 0, tz), yaw=yaw, pitch=42, dist=180, fov=36, cy=500)
    out = collections.OrderedDict()
    out['main'] = base
    out['far'] = dict(base, dist=700, pitch=55, radius=500, haze0=300, haze1=1200)
    out['low'] = dict(base, target=(tx - 160, 0, tz + 90), yaw=yaw6 + 180, pitch=18, dist=110, fov=42, cy=None, max_depth=900)
    out['wide'] = dict(base, yaw=yaw + 90, dist=420, pitch=35, fov=50, H=600, cy=300, roof_k=0.92)
    out['mid'] = dict(base, target=(tx - 250, 0, tz), yaw=yaw + 90, dist=420, pitch=58, fov=40, H=700, cy=350, radius=200, haze0=250, haze1=1100, roof_k=0.92)
    return out, yaw


VIEWS = [('main', 'golden', {}), ('main', 'noon', {}), ('main', 'night', {}), ('main', 'truth', {'hhmm': (17, 20)}),
         ('far', 'golden', {}), ('low', 'noon', {}), ('wide', 'golden', {}), ('mid', 'golden', {'season': 'summer'}),
         ('mid', 'noon', {'date': (2026, 6, 21)}), ('mid', 'night', {}), ('mid', 'truth', {'hhmm': (9, 15)})]


def th_of(name, extra):
    th = R.theme(name)
    th.update(extra)
    return th


def views_main(S, water, times, outdir):
    os.makedirs(outdir, exist_ok=True)
    cf, yaw = cfgs(S)
    res = collections.OrderedDict()
    for vn, tn, extra in VIEWS:
        th = th_of(tn, extra)
        t0 = time.time()
        name = '%s_%s' % (vn, tn)
        try:
            svg, cam, info = R.render(S, th, cf[vn], water)
        except ValueError as e:
            # real.py reads building:colour with hx(): a named colour ('white') in view stops the render
            res[name] = {'view': vn, 'theme': tn, 'extra': extra, 'cfg': cf[vn], 'error': str(e), 'seconds': time.time() - t0}
            continue
        dt = time.time() - t0
        with open(os.path.join(outdir, name + '.svg'), 'w') as f: f.write(svg)
        res[name] = {'view': vn, 'theme': tn, 'extra': extra, 'cfg': cf[vn], 'info': info, 'seconds': dt, 'chars': len(svg)}
    # split: the building nearest the target that the render draws
    cfg = cf['main']
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
    best = None
    for k, b in enumerate(S['buildings']):
        cx, cz = R.centroid(b['poly'])
        c = cam.cam((cx, min(8, b['h'] / 2), cz))
        if c[2] < 2: continue
        p = cam.pc(c)
        if not (0 < p[0] < cfg['W'] and 0 < p[1] < cfg['H']): continue
        if best is None or c[2] < best[0]: best = (c[2], k)
    for tn in ('golden', 'night'):
        th = th_of(tn, {})
        t0 = time.time()
        sp = R.render(S, th, cfg, water, split=S['buildings'][best[1]])
        v1 = sp['variant'](S['buildings'][best[1]])
        v2 = sp['variant'](S['buildings'][best[1]], True)
        dt = time.time() - t0
        name = 'split_' + tn
        for part, txt in (('base', sp['base']), ('front', sp['front']), ('variant', v1), ('variant_truth', v2)):
            with open(os.path.join(outdir, '%s_%s.svg' % (name, part)), 'w') as f: f.write(txt)
        res[name] = {'view': 'main', 'theme': tn, 'split': best[1], 'depth': sp['depth'], 'seconds': dt}
    with open(os.path.join(outdir, 'views.json'), 'w') as f: json.dump({'views': res, 'times': times, 'yaw': yaw}, f)
    print(json.dumps({k: v['seconds'] for k, v in res.items()}))


def funcs(S, water, times, path):
    r = random.Random(8)
    out = {}
    out['theme'] = {n: R.theme(n) for n in ('noon', 'golden', 'night', 'truth')}
    drive = [x for x in S['roads'] if x['hw'] != 'path']
    tx, tz = R.XZ(*ORIGIN)
    pts = [(tx + r.uniform(-600, 600), tz + r.uniform(-600, 600)) for _ in range(150)]
    out['nearest_road_dir'] = [[p, R.nearest_road_dir(drive, p[0], p[1])] for p in pts] + [[[0, 0], R.nearest_road_dir([], 0, 0)]]
    out['tree_size'] = [[t['dbh'], R.tree_size(t)] for t in S['trees'][:40] + S['trees'][-40:]]
    # Shadows: lit, top, shadow_height
    sh_rows = []
    for (az, el) in ((240.0, 20.0), (180.0, 55.0), (100.0, 8.0), (300.0, -3.0)):
        L = LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
        sh = R.Shadows(S['buildings'], L)
        lit, sht = [], []
        for _ in range(250):
            p = (tx + r.uniform(-400, 400), r.uniform(0, 40), tz + r.uniform(-400, 400))
            k = r.randrange(-1, len(S['buildings']))
            lit.append([p, k, sh.lit(p, skip=S['buildings'][k] if k >= 0 else None)])
            lit.append([p, k, sh.lit(p, skip=S['buildings'][k] if k >= 0 else None, reach=60.0)])
            sht.append([p, k, sh.shadow_height(p[0], p[2], skip=S['buildings'][k] if k >= 0 else None)])
        sh_rows.append({'L': L, 'maxh': sh.maxh, 'lit': lit, 'shadow_height': sht})
    out['shadows'] = sh_rows
    out['bb'] = [b['bb'] for b in S['buildings']]
    # Shadows.top on roofed prisms (as amsterdam.py's buildings carry them)
    tops = []
    for k in range(60):
        b = S['buildings'][r.randrange(len(S['buildings']))]
        roof = r.choice(['gabled', 'hipped', 'pyramidal', 'quadruple_saltbox', 'flat', None])
        pb = {'poly': b['poly'], 'h': b['h'], 'roof_h': r.choice([0, 0.0, None, 2.5, 4.0]), 'roof': roof, 'orient': r.choice([None, 'across', 'along'])}
        cx, cz = R.centroid(b['poly'])
        qs = [(cx + r.uniform(-15, 15), cz + r.uniform(-15, 15)) for _ in range(6)]
        tops.append({'b': {k_: v for k_, v in pb.items() if k_ != 'poly'}, 'i': S['buildings'].index(b),
                     'top': [[q, R.Shadows.top(None, pb, q[0], q[1])] for q in qs], 'rf': pb.get('_rf')})
    out['top'] = tops
    # water_polygon on made-up coastlines
    wp = []
    for k in range(12):
        lines = []
        x, z = r.uniform(-500, 500), r.uniform(-500, 500)
        for _ in range(r.randint(1, 5)):
            ln = [(x, z)]
            for _ in range(r.randint(1, 6)):
                x += r.uniform(-80, 80); z += r.uniform(-80, 80); ln.append((x, z))
            lines.append(ln)
            if r.random() < 0.3: x += 5
        r.shuffle(lines)
        if k == 0: lines = []
        S2 = {'water': lines}
        wp.append([lines, R.water_polygon(S2)])
    out['water_polygon'] = wp
    # draw_car (no caller in real.py's render) and draw_dock on every bicycle_rental node, by day and by night
    cf, _ = cfgs(S)
    cfg = cf['main']
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
    draws = []
    for tn, (az, el) in (('golden', (240.0, 20.0)), ('night', (300.0, -3.0))):
        th = R.theme(tn)
        th['_L'] = LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
        class Sc: pass
        sc = Sc(); sc.cam, sc.th, sc.L, sc.boxes = cam, th, th['_L'], []
        for k in range(30):
            x, z = tx + r.uniform(-80, 80), tz + r.uniform(-80, 80)
            a = r.uniform(0, 2 * math.pi)
            col = r.choice(['#D8DDE4', '#B8322A', '#2E5BFF', '#2A2D33'])
            draws.append(['car', tn, [x, z, math.cos(a), math.sin(a), col], R.draw_car(sc, x, z, math.cos(a), math.sin(a), col)])
        for i, n in enumerate(S['nodes']):
            if n['tags'].get('amenity') == 'bicycle_rental' and cam.depth((n['x'], 0.5, n['z'])) > 1:
                draws.append(['dock', tn, [i], R.draw_dock(sc, n, drive, th)])
    out['draws'] = draws
    out['street_bearing'] = [[n, R.street_bearing(S, n)] for n in ('5th Avenue', '6th Avenue', 'Bleecker Street', 'Broadway', 'Waverly Place')]
    with open(path, 'w') as f: json.dump(out, f)
    print(json.dumps(times))


def anchors(S, path):
    """real.py's __main__ after the render: the anchors chosen for the callouts, and the stats."""
    cf, yaw = cfgs(S)
    cfg = cf['main']
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
    open_at = R.open_at
    DROP = ('name', 'brand', 'brand:wikidata', 'brand:wikipedia', 'website', 'phone', 'contact:', 'addr:', 'operator', 'wikidata', 'wikipedia', 'check_date', 'survey', 'source', 'email', 'image', 'official_name', 'alt_name', 'old_name')
    def clean(t): return {k: v for k, v in t.items() if not k.startswith(DROP)}
    cands = collections.defaultdict(list)
    for n in S['nodes']:
        t = n['tags']
        kind = 'subway' if t.get('railway') == 'subway_entrance' else 'hydrant' if t.get('emergency') == 'fire_hydrant' else 'bikes' if t.get('amenity') in ('bicycle_rental', 'bicycle_parking') else 'bench' if t.get('amenity') in ('bench', 'waste_basket', 'drinking_water') else None
        if kind: cands[kind].append(((n['x'], 1.0, n['z']), clean(t), None))
    for b in S['buildings']:
        cx, cz = R.centroid(b['poly'])
        extra = {'construction_year (NYC open data)': str(b['year'])} if b['year'] else {}
        cands['building'].append(((cx, b['h'] + 0.5, cz), dict(clean(b['tags']), **extra), dict(h=b['h'], year=b['year'], levels=b['levels'], guessed=list(b['guessed']))))
        for sh_ in b['shops']:
            a_, b_ = b['poly'][sh_['edge']], b['poly'][(sh_['edge'] + 1) % len(b['poly'])]
            px_, pz_ = a_[0] + (b_[0] - a_[0]) * sh_['u'], a_[1] + (b_[1] - a_[1]) * sh_['u']
            if sh_['tags'].get('opening_hours'):
                cands['shop'].append(((px_, 3.2, pz_), clean(sh_['tags']), dict(noon=open_at(sh_['tags']['opening_hours'], 'We', (12, 0)), golden=open_at(sh_['tags']['opening_hours'], 'We', (17, 20)), night=open_at(sh_['tags']['opening_hours'], 'We', (21, 30)))))
    for tr in S['trees']:
        if tr['source'] == 'census':
            hgt, rad = R.tree_size(tr)
            cands['tree'].append(((tr['x'], hgt * 0.72, tr['z']), {'species': tr['species'], 'common': tr['common'], 'trunk_dbh_in': str(int(tr['dbh'] or 0))}, dict(h=round(hgt), crown=round(rad * 2))))
    for c in S['crossings']:
        t = c['tags']
        if t.get('crossing:markings') in ('zebra', 'ladder'):
            m = c['pts'][len(c['pts']) // 2]
            cands['crossing'].append(((m[0], 0.1, m[1]), clean(t), None))
    for r in S['roads']:
        t = r['tags']
        if t.get('cycleway:left') == 'lane' or t.get('cycleway:right') == 'lane' or t.get('cycleway') == 'lane':
            m = r['pts'][len(r['pts']) // 2]
            cands['lane'].append(((m[0], 0.1, m[1]), clean(t), None))
    want = [('shop', (330, 400)), ('subway', (300, 520)), ('crossing', (190, 520)), ('tree', (110, 470)), ('building', (160, 190)), ('hydrant', (250, 330)), ('bikes', (70, 600)), ('lane', (120, 300)), ('bench', (340, 250))]
    chosen = {}
    for kind, spot in want:
        best = None
        for (p3, tags, info) in cands.get(kind, []):
            q = cam.proj(p3)
            if not q or not (34 < q[0] < cfg['W'] - 34 and 130 < q[1] < 628): continue
            if any(math.hypot(q[0] - c_['at'][0], q[1] - c_['at'][1]) < 62 for c_ in chosen.values()): continue
            dd = math.hypot(q[0] - spot[0], q[1] - spot[1])
            if best is None or dd < best[0]: best = (dd, q, tags, info)
        if best: chosen[kind] = dict(at=[round(best[1][0], 1), round(best[1][1], 1)], tags=best[2], info=best[3])
    st = dict(buildings=len(S['buildings']), height=sum(1 for b in S['buildings'] if not b['guessed'].get('height')), year=sum(1 for b in S['buildings'] if b['year']),
              colour=sum(1 for b in S['buildings'] if not b['guessed'].get('colour')), levels=sum(1 for b in S['buildings'] if not b['guessed'].get('levels')),
              trees_osm=sum(1 for t in S['trees'] if t['source'] == 'osm'), trees_census=sum(1 for t in S['trees'] if t['source'] == 'census'),
              shops=len(S['pois']), shops_hours=sum(1 for p in S['pois'] if 'opening_hours' in p['tags']), crossings=sum(1 for c in S['crossings'] if c['tags'].get('crossing:markings') not in (None, 'no')),
              lamps=sum(1 for n in S['nodes'] if n['tags'].get('highway') == 'street_lamp'), racks=sum(1 for n in S['nodes'] if n['tags'].get('amenity') == 'bicycle_parking'),
              benches=sum(1 for n in S['nodes'] if n['tags'].get('amenity') == 'bench'), hydrants=sum(1 for n in S['nodes'] if n['tags'].get('emergency') == 'fire_hydrant'))
    with open(path, 'w') as f: json.dump(dict(anchors=chosen, stats=st, cfg=cfg), f, default=str)
    print(json.dumps({k: v['at'] for k, v in chosen.items()}))


if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'mkosm':
        mkosm(sys.argv[2], sys.argv[3])
    elif mode == 'load':
        S, water, times = loaded(sys.argv[2])
        with open(sys.argv[3], 'w') as f: json.dump(s_json(S, water), f)
        print(json.dumps(times))
    elif mode == 'funcs':
        funcs(*loaded(sys.argv[2]), sys.argv[3])
    elif mode == 'views':
        views_main(*loaded(sys.argv[2]), sys.argv[3])
    elif mode == 'anchors':
        anchors(loaded(sys.argv[2])[0], sys.argv[3])
    elif mode == 'all':
        # one load for everything: OUTDIR/load.json, anchors.json, views/, funcs.json
        S, water, times = loaded(sys.argv[2])
        out = sys.argv[3]
        os.makedirs(out, exist_ok=True)
        with open(os.path.join(out, 'load.json'), 'w') as f: json.dump(s_json(S, water), f)
        anchors(S, os.path.join(out, 'anchors.json'))
        views_main(S, water, times, os.path.join(out, 'views'))
        funcs(S, water, times, os.path.join(out, 'funcs.json'))
