#!/usr/bin/env python3
"""Oracle for Amsterdam.kt and PoiStyle.kt: render/amsterdam.py (and render/poi_style.py) on the committed Westerkerk
extract, data/ams/raw.json. Run with PYTHONHASHSEED=0. The env flags amsterdam.py reads at import (ATLAS_KIT,
ATLAS_LOOKS, ATLAS_AO, ATLAS_DAYLIGHT) come from the spec, set before the import. Nothing is written into cache/render.

    amsterdam_oracle.py funcs SPEC OUTFILE       helpers on sample inputs and on every loaded object, as JSON
    amsterdam_oracle.py load SPEC OUTFILE        what load() makes of the extract (with guess_tags, generic.apply, poi_style), as JSON
    amsterdam_oracle.py render SPEC OUTDIR       every job of the spec: OUTDIR/<tag>__<moment>__<layer>.svg and OUTDIR/<name>.json
                                                 (md5 and size of every layer, the camera, the info dict, seconds)

SPEC is JSON: {"name": "...", "env": {"ATLAS_KIT": "1", ...}, "jobs": [{"tag", "kind": "ams"|"amsfull"|"quest"|"sky",
"moments": [...], "lens": null|"truth"|"year"|"tag:<key>", "layer": null|"front", "cfg": {...}, "cfgfile": "<path>",
"extra_themes": {"<name>": [[y, m, d], [hh, mm]]}}]}.
"""
import datetime, hashlib, json, math, os, random, sys, time

SPEC = json.loads(sys.argv[2])
os.environ.update(SPEC.get('env', {}))
import amsterdam as A                     # noqa: E402  (reads the env flags at import)
import poi_style as PS                    # noqa: E402

DEFAULT_CFG = dict(W=412, H=915, target=(-10, 0, -30), yaw=52, pitch=38, dist=430, fov=36, cy=440, radius=380)


def cfg_of(job):
    cfg = dict(DEFAULT_CFG)
    if job.get('cfgfile'): cfg.update(json.load(open(job['cfgfile'])))
    if job.get('cfg'): cfg.update(job['cfg'])
    return cfg


def js(v):
    """A value as JSON: tuples as lists, sets sorted, dicts with str keys; objects that are not data become their repr."""
    if isinstance(v, dict): return {str(k): js(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)): return [js(x) for x in v]
    if isinstance(v, (set, frozenset)): return sorted(js(x) for x in v)
    if isinstance(v, float) and not math.isfinite(v): return repr(v)
    if v is None or isinstance(v, (bool, int, float, str)): return v
    return repr(v)


BKEYS = ('poly', 'h', 'min', 'src', 'year', 'id', 'roof', 'roof_h', 'orient', 'mat', 'rmat', 'col', 'rcol', 'levels', 'guessed', 'mat_draw',
         'hidden', 'kind', 'col_draw', 'rmat_draw', 'rcol_draw', 'no_win', 'look_why', 'lv_why', 'roof_why', 'trad', 'party', 'in_church')


def dump_building(b):
    o = {k: js(b[k]) for k in BKEYS if k in b}
    o['keys'] = list(b.keys())
    o['guessed_order'] = list(b.get('guessed', {}).keys())
    o['tags'] = list(b['tags'].keys())
    o['places'] = [n['id'] for n in b.get('places', [])]
    return o


def load_dump(S):
    out = dict(buildings=[dump_building(b) for b in S['buildings']], parts=[dump_building(b) for b in S['parts']])
    out['water'] = js(S['water'])
    out['roads'] = [dict(pts=js(r['pts']), w=r['w'], hw=r['hw'], bridge=r['bridge'], layer=r['layer'], id=r['id'], wguess=r['wguess'], tags=list(r['tags'].keys())) for r in S['roads']]
    out['rails'] = js(S['rails'])
    out['areas'] = [dict(poly=js(a['poly']), kind=a['kind'], tags=list(a['tags'].keys())) for a in S['areas']]
    out['labels'] = [dict(pts=js(l['pts']), name=l['name'], kind=l['kind']) for l in S['labels']]
    TREES = {id(t) for t in S['trees']}; POIS = {id(p) for p in S['pois']}
    out['nodes'] = [dict(id=n['id'], x=n['x'], z=n['z'], tree=id(n) in TREES, poi=id(n) in POIS, style=js(n.get('style')),
                         b=n['_b']['id'] if n.get('_b') else None, h=n.get('h'), crown=n.get('crown'), guessed=js(n.get('guessed'))) for n in S['nodes']]
    out['trees'] = [n['id'] for n in S['trees']]
    out['pois'] = [n['id'] for n in S['pois']]
    out['tradition'] = S.get('tradition')
    return out


def funcs(S):
    r = random.Random(5)
    out = {}
    times = [datetime.datetime(2026, 9, 30, h, m) for h in range(0, 24, 2) for m in (0, 30)] + [datetime.datetime(2026, 6, 21, 4, 0), datetime.datetime(2025, 12, 21, 11, 30)]
    out['sun_position'] = [[[t.year, t.month, t.day, t.hour, t.minute], A.sun_position(t)] for t in times]
    out['sun_position_at'] = [[[t.year, t.month, t.day, t.hour, t.minute], la, lo, A.sun_position(t, la, lo)] for t in times[:8] for la, lo in ((48.85, 2.35), (60.39, 5.32), (-33.9, 18.4))]
    out['year_colour'] = [[y, A.year_colour(y)] for y in [None, 1500, 1600, 1650, 1700, 1777, 1800, 1850, 1899, 1900, 1925, 1950, 1975, 2000, 2010]]
    out['wx_at'] = [[[2026, 9, 30], h, m, A.wx_at((2026, 9, 30), h, m)] for h in (0, 5, 13, 21, 23) for m in (0, 20, 30, 45)]
    out['wx_at'] += [[[2026, 10, 2], 23, m, A.wx_at((2026, 10, 2), 23, m)] for m in (0, 40)]
    moments = [((2026, 9, 30), (h, m)) for h in range(0, 24, 3) for m in (0, 30)] + [((2026, 10, 1), (h, 15)) for h in (6, 7, 8, 17, 18, 19, 20)]
    out['weather_theme'] = [[list(d), list(hm), js(A.weather_theme('x', d, hm))] for d, hm in moments]
    out['themes'] = js(A.THEMES)
    # light, haze_at, hzc on sample inputs, in every theme, with TONE off and on (regions turn it on)
    samples = []
    for thn in A.THEMES:
        for tone in (False, True):
            A.TONE = tone
            th = dict(A.THEMES[thn]); th['_L'] = (0.3, 0.5, -0.81)
            for k in range(12):
                base = (r.uniform(0, 255), r.uniform(0, 255), r.uniform(0, 255))
                nx, ny, nz = r.uniform(-1, 1), r.uniform(-1, 1), r.uniform(-1, 1); l = math.sqrt(nx * nx + ny * ny + nz * nz)
                n = (nx / l, ny / l, nz / l); lit = r.random() < 0.6
                th['_hz'] = r.choice([0.0, 0.0, 0.3, r.random()])
                samples.append([thn, tone, js(base), js(n), lit, th['_hz'], js(A.light(th, base, n, lit))])
            A.TONE = False
    out['light'] = samples
    hz = []
    for thn in A.THEMES:
        for eye in (False, True):
            th = dict(A.THEMES[thn]); th['_eye'] = eye
            for d in (0.0, 10.0, 15.0, 16.0, 40.0, 120.0, 800.0, 5000.0):
                th['_hz'] = A.haze_at(th, d)
                hz.append([thn, eye, d, th['_hz'], A.hzc(th, '#2A2D30')])
    out['haze_at'] = hz
    # every building: rings in and out, the skillion direction, the ridge, the surface colours in every lens, the tag states
    bs = []
    lenses = [None, 'truth', 'year'] + ['tag:' + k for k in A.GAP_KEYS]
    for b in S['buildings'] + S['parts']:
        p = b['poly']
        rec = dict(id=b['id'])
        rec['offset'] = [js(A.offset_ring(p, dl)) for dl in (0.25, 0.4, 0.6)]
        rec['inset'] = [js(A.inset_ring(p, dl)) for dl in (0.5, 0.8, 1.4)]
        rec['roof_dir'] = js(A.roof_dir(b))
        rec['ridge'] = js(A.trad_ridge(b, p))
        rec['surface'] = [[lz, js(A.surface_colour(b, lz)), js(A.surface_colour(b, lz, 'roof'))] for lz in lenses]
        rec['tag_state'] = [A.tag_state(b, k, 'building') for k in A.GAP_KEYS]
        bs.append(rec)
    out['buildings'] = bs
    out['trees'] = [[n['id'], [A.tag_state(n, k, 'tree') for k in A.GAP_KEYS]] for n in S['trees']]
    out['roads'] = [[rd['id'], [A.tag_state(rd, k, 'road') for k in A.GAP_KEYS]] for rd in S['roads']]
    out['pois'] = [[n['id'], [A.tag_state(n, k, 'poi') for k in A.GAP_KEYS]] for n in S['pois']]
    out['style'] = [[n['id'], js(PS.style(n['tags']))] for n in S['nodes']]
    out['style_samples'] = [[t, js(PS.style(t))] for t in [{}, {'shop': 'second_hand'}, {'shop': 'e_cigarette'}, {'shop': 'ÉPICERIE_fine'}, {'office': 'x'},
                            {'healthcare': 'clinic'}, {'healthcare': ''}, {'amenity': 'cafe', 'brand': 'X'}, {'amenity': 'cafe', 'brand': 'X', 'brand:wikidata': 'Q582245'},
                            {'shop': 'supermarket', 'brand:wikidata': 'Q1653985'}, {'tourism': 'museum'}, {'leisure': 'fitness_centre', 'shop': 'clothes'}]]
    # roads: the distance to the nearest road, and the street wall of every place's building
    pts = [(r.uniform(-400, 400), r.uniform(-400, 400)) for _ in range(300)]
    out['road_dist'] = [[x, z, A.road_dist(S, x, z)] for x, z in pts]
    sw = []
    for n in S['nodes']:
        b = n.get('_b')
        if b is not None: sw.append([n['id'], b['id'], js(A.street_wall(S, b, n['x'], n['z']))])
    out['street_wall'] = sw
    edges = A.water_edges(S)
    out['water_edges'] = [[js(a), js(b), nx, nz, S['water'].index(rg) if any(rg is w for w in S['water']) else -1] for (a, b, nx, nz, rg) in edges]
    out['near_water'] = [[x, z, d, A.near_water(edges, [(x, z)], d), A.on_water(S, x, z)] for (x, z) in pts[:120] for d in (4, 12)]
    return out


def render(S, outdir):
    """Every job, as cache/oracle/audit/drv.py runs the audit's calls: kind 'ams' is render(layer='split') (g, o, and o2 past
    obj_split), 'amsfull' one SVG (moments 'truth', 'truthgrey' and 'year' are lenses on golden or grey); and 'quest' (split=a
    building near the target: base, front and three variants), 'sky' (render_sky)."""
    res = dict(name=SPEC['name'], env=SPEC.get('env', {}), jobs=[])
    t0 = time.time()
    for job in SPEC['jobs']:
        cfg = cfg_of(job)
        for k, (d_, hm_) in (job.get('extra_themes') or {}).items():
            A.THEMES[k] = A.weather_theme(k, tuple(d_), tuple(hm_))
        jr = dict(tag=job['tag'], kind=job['kind'], cfg=js(cfg), files={}, info={}, t={})
        for thn in job['moments']:
            t1 = time.time()
            def put(layer, data):
                fn = '%s__%s__%s.svg' % (job['tag'], thn, layer)
                open(os.path.join(outdir, fn), 'w').write(data)
                jr['files'][fn] = dict(moment=thn, layer=layer, size=len(data.encode()), md5=hashlib.md5(data.encode()).hexdigest())
            kind = job['kind']
            lens = job.get('lens')
            if lens is None and thn in ('truth', 'truthgrey'): lens = 'truth'
            if lens is None and thn == 'year': lens = 'year'
            th2 = 'grey' if thn == 'truthgrey' else ('golden' if thn in ('year', 'truth') else thn)
            if kind == 'ams' and lens is None:
                (base, objs), cam, info = A.render(S, thn, cfg, lens, layer='split')
                put('g', base)
                if isinstance(objs, tuple):
                    put('o', objs[0]); put('o2', objs[1])
                else: put('o', objs)
                jr['info'][thn] = js({k: v for k, v in info.items() if k != 'raster'})
            elif kind == 'quest':
                tx, tz = cfg['target'][0], cfg['target'][2]
                cand = [b for b in S['buildings'] if not b['hidden'] and b['h'] and b['tags'].get('building') != 'houseboat']
                b = min(cand, key=lambda b: math.hypot(A.centroid(b['poly'])[0] - tx, A.centroid(b['poly'])[1] - tz))
                q = A.render(S, th2, cfg, lens, split=b)
                put('base', q['base']); put('front', q['front'])
                put('variant', q['variant'](b)); put('vtruth', q['variant'](b, 'truth'))
                put('vhatch', q['variant'](dict(b, _hatch=True), 'truth'))
                jr['info'][thn] = dict(split=b['id'], depth=q['depth'], az=q['az'], el=q['el'])
            elif kind == 'sky':
                put('sky', A.render_sky(thn, cfg))
            else:
                svg, cam, info = A.render(S, th2, cfg, lens, layer=job.get('layer'))
                put('full', svg)
                jr['info'][thn] = js(info)
            jr['t'][thn] = time.time() - t1
        res['jobs'].append(jr)
    res['t'] = time.time() - t0
    return res


if __name__ == '__main__':
    cmd = sys.argv[1]
    t0 = time.time()
    S = A.load()
    t_load = time.time() - t0
    if cmd == 'funcs':
        out = funcs(S); out['t_load'] = t_load
        json.dump(out, open(sys.argv[3], 'w'))
    elif cmd == 'load':
        out = load_dump(S); out['t_load'] = t_load
        json.dump(out, open(sys.argv[3], 'w'))
    elif cmd == 'render':
        os.makedirs(sys.argv[3], exist_ok=True)
        res = render(S, sys.argv[3]); res['t_load'] = t_load
        json.dump(res, open(os.path.join(sys.argv[3], SPEC['name'] + '.json'), 'w'), indent=1)
    else:
        raise SystemExit('unknown command ' + cmd)
    print('ok')
