#!/usr/bin/env python3
"""Oracle for Regions.kt, Terrain.kt, Relief.kt, Woods.kt, Coasters.kt and FetchDem.kt: render/regions.py and the modules it
imports, on the local region extracts in data/regions/<name>/ (git-ignored). Run with PYTHONHASHSEED=0, one process per job,
as render/regions.py runs one region per process (load() moves amsterdam.py's globals). Nothing is written into cache/render.

    regions_oracle.py render SPEC OUTDIR     one job: OUTDIR/<tag>__<moment>__<layer>.svg|png|jpg, OUTDIR/<tag>.json
    regions_oracle.py digest SPEC OUTFILE    what load(view) makes: per key of S, a digest of every item (JSON)
    regions_oracle.py funcs SPEC OUTFILE     helpers on sample inputs (JSON)
    regions_oracle.py terrain SPEC OUTFILE   terrain.py's hillshade, draw, Cover and drape on a terrain region
    regions_oracle.py moments SPEC OUTFILE   view_moments after load(view): what the view holds and each moment's sun and weather

SPEC is JSON: {"tag": "...", "view": "grachten_home", "moments": ["sunny", ...], "ao": false, "cfg": {...}}.
The job runs render/regions.py's main loop: load(view), cfg_for(view), the tide of each moment, A.render(layer='split').
A JPEG raster also writes the PNG handed to sips (`<tag>__<moment>__t_rgb.png`), observed by wrapping relief._png_rgb.
render/terrain.py and render/relief.py import `fetch_dem`, which moved to fetch/dem.py: shim/ puts it back on the path.
"""
import hashlib, json, math, os, random, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..', '..', '..', '..', '..'))
sys.path.insert(0, os.path.join(REPO, 'render')); sys.path.insert(1, os.path.join(HERE, 'shim'))
OUT = os.path.abspath(sys.argv[3])
os.chdir(os.path.join(REPO, 'render'))

SPEC = json.loads(sys.argv[2])
os.environ.pop('ATLAS_AO', None)
if SPEC.get('ao'): os.environ['ATLAS_AO'] = '1'
import amsterdam as A          # noqa: E402
import regions as RG           # noqa: E402
import relief as RF            # noqa: E402

_last_png = {}
_png_rgb = RF._png_rgb
def _observe_png(w, h, rows):
    b = _png_rgb(w, h, rows); _last_png['png'] = b; return b
RF._png_rgb = _observe_png


def md5(b): return hashlib.md5(b).hexdigest()


def render(outdir):
    tag = SPEC['tag']; res = dict(spec=SPEC, files={}, t={})
    t0 = time.time()
    S = RG.load(SPEC['view'])
    cfg = RG.cfg_for(SPEC['view'])
    if SPEC.get('cfg'): cfg.update(SPEC['cfg'])
    res['t']['load'] = time.time() - t0

    def put(moment, layer, data, ext='svg'):
        fn = '%s__%s__%s.%s' % (tag, moment, layer, ext)
        b = data if isinstance(data, bytes) else data.encode()
        open(os.path.join(outdir, fn), 'wb').write(b)
        res['files'][fn] = dict(moment=moment, layer=layer, ext=ext, size=len(b), md5=md5(b))
        return fn
    for k in SPEC['moments']:
        t1 = time.time(); _last_png.clear()
        if S.get('_tide'):
            y = max(-9.0, min(-0.6, S['_tide'][k] - S['_bank'])); A.WATER_Y = y
            for r in S['_tidal']: S['water_y'][id(r)] = y
        (base, objs), cam, info = A.render(S, k, cfg, None, layer='split')
        put(k, 'g', base)
        if isinstance(objs, tuple):
            put(k, 'o2', objs[1]); objs = objs[0]
        put(k, 'o', objs)
        if info.get('raster'):
            ext, data, clip = info['raster']
            fn = put(k, 't', data, ext); res['files'][fn]['clip'] = clip
            if ext == 'jpg' and _last_png.get('png'): put(k, 't_rgb', _last_png['png'], 'png')
        res['t'][k] = time.time() - t1
    res['t']['total'] = time.time() - t0
    res['cfg'] = {k: v for k, v in cfg.items()}
    json.dump(res, open(os.path.join(outdir, tag + '.json'), 'w'), indent=1, default=list)


# ------------------------------------------------------------------ digest of load()
def canon(v, top, rings):
    """A value as JSON data: tuples as lists; a dict below the top level that has an id stands as {"ref": id};
    functions as "fn"; a water ring referenced by identity as {"ring": index}."""
    if isinstance(v, dict):
        if not top and 'id' in v and ('poly' in v or 'x' in v): return {'ref': v['id']}
        return {str(k): canon(x, False, rings) for k, x in v.items()}
    if isinstance(v, (list, tuple)): return [canon(x, False, rings) for x in v]
    if isinstance(v, (set, frozenset)): return sorted(rings.get(x, x) for x in v)
    if callable(v): return 'fn'
    if isinstance(v, float) and not math.isfinite(v): return repr(v)
    if v is None or isinstance(v, (bool, int, float, str, bytes)): return v if not isinstance(v, bytes) else md5(v)
    return grid_digest(v)


def floats_md5(xs):
    return md5(','.join('None' if x is None else repr(x) for x in xs).encode())


def grid_digest(o):
    """DEM, Local, relief.Grid: their numbers summarised."""
    d = {'class': type(o).__name__}
    for k in ('crs', 'cell', 'W', 'H', 'x0', 'z0', 'y1', 'step', 'nx', 'nz', 'top', 'cs', 'names', 'lake_levels'):
        if hasattr(o, k): d[k] = getattr(o, k)
    if hasattr(o, 'g'):
        g = o.g
        d['g'] = floats_md5(g if not g or not isinstance(g[0], list) else [x for r in g for x in r])
    if hasattr(o, 'cover'): d['cover'] = md5(bytes(o.cover))
    if hasattr(o, 'wet'): d['wet'] = md5(bytes(o.wet)) if isinstance(o.wet, (bytes, bytearray)) else len(o.wet)
    return d


def item_text(x, rings):
    return json.dumps(canon(x, True, rings), ensure_ascii=False)


def digest(outfile):
    t0 = time.time()
    S = RG.load(SPEC['view'])
    dt = time.time() - t0
    rings = {}
    for i, r in enumerate(S.get('water', [])): rings[id(r)] = i
    out = {'_seconds': dt, '_keys': list(S.keys()),
           '_globals': dict(LAT0=A.LAT0, LON0=A.LON0, KX=A.KX, TZ_H=A.TZ_H, WATER_Y=A.WATER_Y, QUAY_COL=A.QUAY_COL, QUAY_COURSE=A.QUAY_COURSE,
                            WIN_STYLE=A.WIN_STYLE, TONE=A.TONE, BRICK_OLD=A.BRICK_OLD, PLASTER=A.PLASTER, BRICK_NEW=A.BRICK_NEW,
                            THEMES=item_text({k: {kk: vv for kk, vv in v.items()} for k, v in A.THEMES.items()}, {}))}
    for k, v in S.items():
        if k in ('water_y',):
            out[k] = sorted([rings.get(r, -1), y] for r, y in v.items()); continue
        if k == 'natural_shore':
            out[k] = sorted(rings.get(r, -1) for r in v); continue
        if k == '_tidal':
            out[k] = [rings.get(id(r), -1) for r in v]; continue
        if isinstance(v, list):
            items = [item_text(x, rings) for x in v]
            out[k] = dict(n=len(v), md5=md5('\n'.join(items).encode()), items=[md5(t.encode()) for t in items], head=items[:2])
        elif isinstance(v, dict) and k == 'towers':
            items = ['%s %s' % (i, item_text(x, rings)) for i, x in v.items()]
            out[k] = dict(n=len(v), md5=md5('\n'.join(items).encode()), head=items[:2])
        else:
            out[k] = canon(v, True, rings)
    json.dump(out, open(outfile, 'w'), ensure_ascii=False)


# ------------------------------------------------------------------ view_moments
def moments(outfile):
    """view_moments after load(view): it writes cache/render/<name>_moments.json; that one write is sent to OUTFILE instead."""
    import builtins
    S = RG.load(SPEC['view'])
    cfg = RG.cfg_for(SPEC['view'])
    def redirect(p, mode='r', *a, **k):
        if 'w' in mode and os.path.basename(str(p)).endswith('_moments.json'): p = outfile
        return builtins.open(p, mode, *a, **k)
    RG.open = redirect
    try:
        RG.view_moments(S, SPEC['view'], cfg)
    finally:
        del RG.open


# ------------------------------------------------------------------ terrain.py (regions.py draws with relief.py; terrain.py's own drawing is tested here)
def terrain(outfile):
    """On a terrain region (dunes): terrain.hillshade traced and on the flat plane, terrain.draw with regions.py's line items,
    Cover.at and drape, for each moment, from load(view)'s scene and amsterdam.render's camera and sun."""
    import terrain as TR, living as LV, datetime
    from city import Camera
    S = RG.load(SPEC['view']); cfg = RG.cfg_for(SPEC['view'])
    cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
    out = dict(moments={})
    for k in SPEC['moments']:
        th = dict(A.THEMES[k]); hh, mm = th['hhmm']; y_, mo_, dd_ = th['date']
        az, el = A.sun_position(datetime.datetime(y_, mo_, dd_, hh, mm) - datetime.timedelta(hours=A.TZ_H))
        th['_L'] = LV.sun_vector(az, el) if el > 0 else (0.0, -1.0, 0.0)
        r = {}
        for trace in (True, False):
            S.pop('_local', None)
            png = TR.hillshade(S, cam, th, cfg, trace=trace, raw=True)
            r['hillshade_%s' % trace] = md5(png) if png else None
        S.pop('_local', None)
        lines = [l[:4] for l in S['_lines']]
        defs = []
        svg = TR.draw(S, cam, th, lambda x, z: False, defs, cfg, RG.LAND, lines)
        r['draw'] = md5(svg.encode()); r['draw_len'] = len(svg); r['wet'] = len(S['_local'].wet) if S.get('_local') else None
        out['moments'][k] = r
    cov = TR.Cover(S['land'], S['_lakes'], S['_seas'], RG.LAND)
    rnd = random.Random(3); pts = [(rnd.uniform(-3000, 3000), rnd.uniform(-3000, 3000)) for _ in range(2000)]
    out['cover'] = [cov.at(x, z) for x, z in pts]; out['cover_pts'] = pts
    ring = S['_lakes'][0] if S['_lakes'] else [(0, 0), (100, 0), (100, 80)]
    out['drape'] = TR.drape(cam, S['dem'], ring[:30], lift=0.4)
    json.dump(out, open(outfile, 'w'))


# ------------------------------------------------------------------ helpers
def funcs(outfile):
    import fetch_dem as FD, terrain as TR, coasters as CO
    rnd = random.Random(11); out = {}
    pts = [(rnd.uniform(-90, 90) * 0.2 + 52, rnd.uniform(-180, 180) * 0.05 + 5) for _ in range(200)] + [(52.15517440, 5.38720621), (43.4677, 11.0432)]
    out['rd'] = [[a, b, FD.rd(a, b)] for a, b in pts]
    RG.configure('port')
    out['rd_to_local'] = [[X, Y, RF._rd_to_local(X, Y)] for X, Y in [(64000.0 + i * 731.3, 443000.0 + i * 517.9) for i in range(20)]]
    views = [k for k in RG.CAMS]
    out['cfg_for'] = [[v, RG.cfg_for(v)] for v in views]
    rings = []
    for n in (5, 6, 7, 9, 13, 30, 80):
        for _ in range(6):
            cx, cz, R = rnd.uniform(-500, 500), rnd.uniform(-500, 500), rnd.uniform(2, 60)
            rings.append([(cx + R * math.cos(2 * math.pi * i / n) + rnd.uniform(-R, R) * 0.1, cz + R * math.sin(2 * math.pi * i / n) + rnd.uniform(-R, R) * 0.1) for i in range(n)])
    out['simplify'] = [[r, tol, RG.simplify(r, tol)] for r in rings for tol in (0.0, 0.3, 0.8, 3.0)]
    lines = [[(rnd.uniform(-50, 50), rnd.uniform(-50, 50)) for _ in range(rnd.randint(2, 7))] for _ in range(20)]
    out['offset_line'] = [[l, o, RG.offset_line(l, o)] for l in lines for o in (-3.5, 0.72)]
    out['smooth'] = [[t, CO.smooth(t)] for t in (-0.5, 0.0, 0.1, 0.33, 0.5, 0.9, 1.0, 1.7)]
    out['subdivide'] = [[l, [rnd.uniform(0, 30) for _ in l], 3.0] for l in lines[:6]]
    for e in out['subdivide']: e.append(CO.subdivide(e[0], e[1], e[2]))
    out['land_kind'] = []
    for t in ({'aeroway': 'apron'}, {'amenity': 'parking'}, {'amenity': 'parking', 'parking': 'underground'}, {'leisure': 'pitch'}, {'industrial': 'port'},
              {'natural': 'wood', 'landuse': 'forest'}, {'landuse': 'meadow'}, {'landuse': 'nope'}, {'natural': 'water'}, {'leisure': 'track', 'natural': 'sand'}):
        out['land_kind'].append([t, RG.land_kind(t)])
    dems = []
    for name in ('dunes', 'port', 'schiphol', 'bergen', 'paris', 'sangimignano'):
        for fn in ('dem.json', 'dem5.json'):
            p = os.path.join(RG.ROOT, name, fn)
            if not os.path.exists(p): continue
            RG.configure(name)
            D = TR.DEM(p)
            sx = [(rnd.uniform(-3000, 3000), rnd.uniform(-3000, 3000)) for _ in range(300)]
            dems.append(dict(region=name, file=fn, KX=A.KX, h=[D.h(x, z) for x, z in sx], hn=[D.hn(x, z) for x, z in sx], pts=sx))
    out['dem'] = dems
    json.dump(out, open(outfile, 'w'))


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'render': render(OUT)
    elif cmd == 'digest': digest(OUT)
    elif cmd == 'funcs': funcs(OUT)
    elif cmd == 'moments': moments(OUT)
    elif cmd == 'terrain': terrain(OUT)
    else: raise SystemExit('unknown command ' + cmd)
