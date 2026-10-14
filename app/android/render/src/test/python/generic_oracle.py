#!/usr/bin/env python3
"""Oracle for Generic.kt: render/generic.py's functions on sample inputs, and apply() over real buildings.
Run with PYTHONHASHSEED=0. Nothing is written into cache/render.

    generic_oracle.py funcs                  JSON on stdout: inputs and outputs of the helpers
    generic_oracle.py apply OUTDIR CASE…     for each case, the buildings as apply() receives them (OUTDIR/CASE.in.json)
                                             and as it leaves them (OUTDIR/CASE.out.jsonl, one building or part per line);
                                             a summary on stdout

A case is how the buildings are loaded, optionally with another tradition (CASE@TRADITION):
    ams_kit      amsterdam.load() with KIT (Amsterdam from its tradition, nl_amsterdam): trad_look for every building
    ams_looks    amsterdam.load() with LOOKS (keep_homes): homes keep their brick, the rest follows look()
    <region>     regions.load(<region>) from data/regions/<region>/raw.json (local only), as the region renders
The loaders run up to their call of generic.apply, which is watched: the buildings are copied as JSON there and
apply is run on the copy (the copy's result is checked against apply on the loader's own objects).
"""
import json, os, random, sys, time

import generic as GN


class Done(Exception):
    pass


def dumps(v):
    return json.dumps(v, ensure_ascii=False)


def plain(b):
    """A building as JSON can hold it: the places in it (POI nodes that point back at it) by their ids; apply never reads them."""
    if 'places' not in b: return b
    c = dict(b); c['places'] = [p['id'] if isinstance(p, dict) else p for p in b['places']]
    return c


def dump_all(bs):
    return json.loads(dumps([plain(b) for b in bs]))


def capture(case):
    """The state generic.apply receives when the case's loader calls it, as JSON-ready values."""
    base = case.split('@')[0]
    orig = GN.apply
    box = {}

    def spy(S, region, landuse_at=None, keep_homes=False):
        bs = S['buildings'] + S['parts']
        lu = [landuse_at(b) for b in bs] if landuse_at else None
        state = dict(region=region, keep_homes=keep_homes, tradition=S.get('tradition'), lu=lu,
                     buildings=dump_all(S['buildings']), parts=dump_all(S['parts']))
        # the loader's own objects, for the check that a copy behaves the same
        t0 = time.time(); orig(S, region, landuse_at, keep_homes); box['live_secs'] = time.time() - t0
        box['live'] = [dumps(plain(b)) for b in bs]
        raise Done(state)

    GN.apply = spy
    try:
        if base in ('ams_kit', 'ams_looks'):
            import amsterdam as A
            A.KIT, A.LOOKS = base == 'ams_kit', base == 'ams_looks'
            A.load()
        else:
            import regions as RG
            RG.load(base)
    except Done as e:
        return e.args[0], box
    finally:
        GN.apply = orig
    raise RuntimeError('%s: generic.apply was not called' % case)


def replay(state, tradition):
    """apply() on a copy of the captured state, with the case's tradition."""
    S = dict(buildings=json.loads(dumps(state['buildings'])), parts=json.loads(dumps(state['parts'])), tradition=tradition)
    bs = S['buildings'] + S['parts']
    lu = state['lu']
    by_id = {id(b): lu[i] for i, b in enumerate(bs)} if lu is not None else None
    landuse_at = (lambda b: by_id[id(b)]) if by_id is not None else None
    t0 = time.time()
    GN.apply(S, state['region'], landuse_at, state['keep_homes'])
    return [dumps(plain(b)) for b in bs], time.time() - t0


def apply_cases(outdir, cases):
    os.makedirs(outdir, exist_ok=True)
    summary = {}
    for case in cases:
        t0 = time.time()
        state, live = capture(case)
        load_secs = time.time() - t0
        tradition = case.split('@')[1] if '@' in case else state['tradition']
        out, secs = replay(state, tradition)
        state['tradition'] = tradition
        with open(os.path.join(outdir, case + '.in.json'), 'w') as f: f.write(dumps(state))
        with open(os.path.join(outdir, case + '.out.jsonl'), 'w') as f: f.write('\n'.join(out) + '\n')
        same = None if '@' in case else (out == live['live'])
        summary[case] = dict(n=len(out), secs=secs, load_secs=load_secs, copy_same_as_live=same)
    return summary


def funcs():
    r = random.Random(77)
    out = {}
    words = list(GN.NAMED) + ['Light Grey', 'dark_grey', ' Red ', 'RED', '#abc', '#ABCDEF', '#12345', '#1234567', '#GGHHII', '', None, 'notacolour',
                              'white', 'Black', '#fff', 'light_yellow', 'Terracotta', '#a1B2c3']
    out['colour'] = [dict(v=w, hex=GN.colour_hex(w), paint=GN.paint(w), paint3=GN.paint(w, 0.3)) for w in words]
    hexes = [v for v in GN.NAMED.values()] + [c for _, c in GN.WALL.values()] + [c for _, c in GN.ROOF.values()] + ['#000000', '#FFFFFF']
    jit = []
    for i in range(400):
        h = r.choice(hexes); seed = r.choice([r.randrange(1 << 31), r.randrange(1 << 40), -r.randrange(1 << 20), r.randrange(100)]); k = r.choice([0.05, 0.035, 0.06, 0.2])
        jit.append(dict(h=h, seed=seed, k=k, out=GN.jitter(h, seed, k)))
    out['jitter'] = jit
    picks = []
    for i in range(300):
        n = r.randint(1, 6)
        if r.random() < 0.5: opts = [[r.choice(['brick', 'plaster', 'metal']), r.choice(hexes), r.choice([1, 2, 6, 14, 0.5, 1.5])] for _ in range(n)]
        else: opts = [[r.choice(['flat', 'gabled', 'hipped', 'mansard']), r.choice([1, 2, 3, 0.25])] for _ in range(n)]
        seed = r.randrange(1 << 40)
        picks.append(dict(options=opts, seed=seed, out=GN.pick(opts, seed)))
    out['pick'] = picks
    eras = []
    entries = [{'before:1830': 'a', 'after:1950': 'b', 'default': 'c'}, {'after:1950': 'x', 'before:1900': 'y'}, {'default': 'd'}, ['l'],
               {'shapes': [['flat', 1]], 'material': 'metal'}, {'material': 'x'}, 'plain', {'before:1900': None, 'default': 'z'}]
    for e in entries:
        for yr in (None, 0, 1600, 1829, 1830, 1899, 1900, 1949, 1950, 2020):
            eras.append(dict(entry=e, yr=yr, out=GN.era(e, yr)))
    out['era'] = eras
    kinds = [k for k, _, _ in GN.KINDS] + ['x', None]
    out['kind_label'] = [[k, GN.kind_label(k)] for k in kinds]
    # kind_of and look over synthetic buildings: every building=* value, the tag tests, land uses, sizes and ages
    bvals = sorted(set(v for _, vals, _ in GN.KINDS for v in vals)) + ['yes', 'house', 'apartments', 'roof', 'construction']
    extra = [('amenity', 'place_of_worship'), ('aeroway', 'terminal'), ('public_transport', 'station'), ('man_made', 'tower'), ('aeroway', 'control_tower'),
             ('man_made', 'silo'), ('amenity', 'parking'), ('military', 'bunker'), ('power', 'substation'), ('industrial', 'yes'), ('amenity', 'school'),
             ('amenity', 'clinic'), ('tourism', 'hotel'), ('leisure', 'stadium'), ('shop', 'bakery'), ('office', 'company'), ('tourism', 'museum'),
             ('historic', 'castle'), ('historic', 'building'), ('heritage', '2'), ('tower:type', 'bell_tower'), ('tower:type', 'church')]
    looks = []
    for i in range(1500):
        t = {}
        if r.random() < 0.9: t['building'] = r.choice(bvals)
        else: t['building:part'] = r.choice(['yes', 'church', 'tower'])
        for _ in range(r.choice([0, 0, 1, 2])):
            k, v = r.choice(extra); t[k] = v
        if r.random() < 0.3: t['building:levels'] = r.choice(['2', '4', '6', '9', '12', '0', 'x'])
        if r.random() < 0.2: t['roof:shape'] = r.choice(['gabled', 'hipped', 'flat', 'mansard', 'dome'])
        w = r.uniform(4, 80); dpt = r.uniform(4, 80); x0, z0 = r.uniform(-500, 500), r.uniform(-500, 500)
        poly = [(x0, z0), (x0 + w, z0), (x0 + w, z0 + dpt), (x0, z0 + dpt)]
        b = dict(tags=t, poly=poly, h=r.choice([None, 0.0, r.uniform(3, 60)]), year=r.choice([None, None, r.randint(1500, 2024)]), id=r.randrange(1 << 33),
                 roof=r.choice([None, None, 'gabled', 'flat', 'skillion']))
        lu = r.choice([None, None] + list(GN.LANDUSE_KIND) + ['meadow'])
        region = r.choice(['ams', 'paris', 'port', 'schiphol', 'dunes', 'bergen', None])
        looks.append(dict(b=b, lu=lu, region=region, kind_of=list(GN.kind_of(t, lu, region, abs(GN.signed_area(poly)), b['h'])), look=list(GN.look(b, region, lu))))
    out['look'] = looks
    # trad_entry over every tradition's tables, and fits over every tradition's rules
    entries, fit = [], []
    for tid in ('nl_amsterdam', 'fr_paris', 'it_tuscan', 'no_timber', 'gb_terrace'):
        P = GN.tradition(tid)
        for i in range(150):
            bt = r.choice(bvals); kind = r.choice(kinds[:-2]); modern = r.random() < 0.3; yr = r.choice([None, r.randint(1500, 2024)])
            entries.append(dict(tid=tid, table='walls', bt=bt, kind=kind, modern=modern, yr=yr, out=GN.trad_entry(P['walls'], bt, kind, modern, yr)))
            entries.append(dict(tid=tid, table='roof', bt=bt, kind=kind, modern=modern, yr=yr, out=GN.trad_entry(P['roof'], bt, kind, modern, yr)))
        for rule in P.get('rules', []):
            for i in range(150):
                w = r.uniform(3, 40); dpt = r.uniform(3, 40)
                b = dict(tags={'building': r.choice(bvals)}, poly=[(0.0, 0.0), (w, 0.0), (w, dpt), (0.0, dpt)], year=r.choice([None, r.randint(1700, 2000)]),
                         levels=r.choice([1, 3, 4, 6, 9, 10]), h=r.choice([None, r.uniform(3, 30)]))
                if r.random() < 0.2: del b['levels']
                kind = r.choice(rule['when'].get('kind', kinds[:-2]) if r.random() < 0.7 else kinds[:-2]); md = r.choice(['brick', 'limestone', 'plaster', 'glass', None]); short = r.uniform(2, 20)
                fit.append(dict(when=rule['when'], b=b, kind=kind, md=md, short=short, out=GN.fits(rule['when'], b, kind, md, short)))
    out['trad_entry'] = entries
    out['fits'] = fit
    return out


if __name__ == '__main__':
    if sys.argv[1] == 'funcs':
        json.dump(funcs(), sys.stdout)
    else:
        json.dump(apply_cases(sys.argv[2], sys.argv[3:]), sys.stdout)
