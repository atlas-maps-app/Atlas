#!/usr/bin/env python3
"""Learned palettes (generic.learned, a prototype behind ATLAS_PALETTES=1): how much building colour the OSM data carries in the renderer's
regions, and how a palette learned from it compares with today's generic and tradition looks.
    python3 -I render/palettes.py survey [view ...]               -> <out>/survey.json, <out>/survey.md (each view's data kept in <out>/cache)
    python3 -I render/palettes.py render <view>[:<moment>] ...    -> <out>/<view>_<moment>_{before,after}.png, <view>_<moment>_pair.png
<out> is /Volumes/Download/atlas/palettes/out (ATLAS_PALETTES_OUT), never cache/render. A view is one of regions.py's (paris, bergen_close, ...)
or 'ams', the living map at the Westerkerk in its kit look (the nl_amsterdam tradition). The survey reads every region with a raw.json."""
import sys, os, re, json, math, collections, subprocess, tempfile, shutil
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
ROOT = os.path.join(HERE, '..')
SHIM = os.path.join(ROOT, 'app', 'android', 'render', 'src', 'test', 'python', 'shim')       # fetch_dem, which terrain.py and relief.py import
TOOLS = os.path.join(ROOT, 'app', 'android', 'mapview', 'src', 'test', 'resources', 'svg', 'tools')   # png.py (read_png)
sys.path[1:1] = [SHIM, TOOLS]
OUT = os.environ.get('ATLAS_PALETTES_OUT', '/Volumes/Download/atlas/palettes/out')
import amsterdam as A
import regions as R
import generic as GN
from real import centroid, signed_area

AMS_CFG = dict(W=412, H=915, target=(-10, 0, -30), yaw=52, pitch=38, dist=430, fov=36, cy=440, radius=380)      # amsterdam.py's living map
AMS_ORIGIN = (A.LAT0, A.LON0)          # a region's load moves amsterdam.py's origin, light and weather to its own: Amsterdam loads first in a process
KEYS = ('building:colour', 'roof:colour', 'building:material', 'roof:material')
LANDMARK = {'worship', 'monument', 'tower'}
# the CSS colours a colour picker offers by name: a hex value equal to one is a name picked from a list, not a colour read off the wall
CSS = {'#FFFFFF', '#000000', '#808080', '#C0C0C0', '#D3D3D3', '#A9A9A9', '#696969', '#FF0000', '#800000', '#8B0000', '#A52A2A', '#FFFF00', '#FFA500',
       '#F5F5DC', '#D2B48C', '#008000', '#0000FF', '#000080', '#FFC0CB', '#F5F5F5', '#DCDCDC', '#708090', '#8B4513', '#A0522D', '#CD853F', '#FFFFF0'}


def views():
    names = sorted(n for n in os.listdir(R.ROOT) if os.path.exists(os.path.join(R.ROOT, n, 'raw.json')))
    return ['ams'] + names


class Caught(Exception): pass


def load(view):
    """The view's buildings as generic.apply leaves them with learned palettes on (their samples in S['_pal']), its region and land use;
    the loader stops there and draws nothing."""
    real, got = GN.apply, {}
    def spy(S, region, landuse_at=None, keep_homes=False):
        real(S, region, landuse_at, keep_homes); got.update(S=S, region=region, landuse_at=landuse_at); raise Caught
    if view == 'ams' and (A.LAT0, A.LON0) != AMS_ORIGIN: raise SystemExit('ams loads first in a process: a region has moved amsterdam.py to its own origin')
    GN.apply, GN.PALETTES = spy, True
    try:
        if view == 'ams': A.KIT = True; A.load()
        else: R.load(view)
    except Caught: pass
    finally: GN.apply, GN.PALETTES = real, False
    return got['S'], got['region'], got['landuse_at']


# ------------------------------------------------------------------ colour
def lab(h):
    """A hex colour in CIELAB (D65), for distances in delta E (CIE76)."""
    c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    c = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c]
    x = (0.4124 * c[0] + 0.3576 * c[1] + 0.1805 * c[2]) / 0.95047
    y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    z = (0.0193 * c[0] + 0.1192 * c[1] + 0.9505 * c[2]) / 1.08883
    f = [t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116 for t in (x, y, z)]
    return (116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2]))


def family(h):
    """A colour's plain name, to see how far mapped colours go beyond white, grey and red."""
    L, a, b = lab(h); C = math.hypot(a, b); hue = math.degrees(math.atan2(b, a)) % 360
    if C < 8: return 'white' if L >= 93 else 'light grey' if L >= 72 else 'grey' if L >= 45 else 'dark grey, black'
    if L >= 78 and C < 22 and 30 <= hue < 120: return 'cream, beige'
    if hue >= 345 or hue < 50: return 'pink' if L >= 70 else 'red, brick'
    if hue < 80: return 'cream, beige' if L >= 80 else 'brown, tan, ochre'
    if hue < 110: return 'yellow' if C >= 30 else 'cream, beige'
    if hue < 200: return 'green'
    return 'blue' if hue < 320 else 'purple'


def parse(v):
    """How the renderer reads a mapped colour value: 'name', 'hex', 'list' (a;b, read by its first value that reads) or 'unread'."""
    items = [x for x in v.split(';') if x.strip()]
    if len(items) > 1: return 'list' if GN.mapped_colour(v) else 'unread'
    w = v.strip().lower().replace(' ', '').replace('_', '')
    if not GN.colour_hex(v): return 'unread'
    return 'hex' if w.startswith('#') else 'name'


def norm(v):
    """A mapped value as one spelling: lower case, no spaces or underscores, grey for gray, hex in capitals."""
    w = GN.mapped_colour(v).replace(' ', '').replace('_', '').replace('gray', 'grey')
    return w.upper() if w.startswith('#') else w


def dist(p, q): return math.dist(p, q)


def es(opts, T):
    """The energy score of a palette (colours in Lab, weighted) against the true colour T: E|X-T| - E|X-X'|/2, in delta E. Lower is better, and
    a varied palette is not punished for its variety: the score is strictly proper, so no palette beats the true distribution on average."""
    tw = sum(w for _, w in opts)
    e1 = sum(w * dist(x, T) for x, w in opts) / tw
    e2 = sum(w1 * w2 * dist(x1, x2) for x1, w1 in opts for x2, w2 in opts) / (tw * tw)
    return e1 - e2 / 2


# ------------------------------------------------------------------ today's look, as the yardstick
def today(o, region, P, landuse_at):
    """The walls' and the roof's colours today's renderer would give this building with its colours and materials unmapped, weighted
    (a tradition's picks, or the one generic colour of its kind; Paris stone one of its eight)."""
    t = {k: v for k, v in o['tags'].items() if k not in KEYS}
    b = dict(o, tags=t); lu = landuse_at(o) if landuse_at else None
    if P:
        bt = t.get('building') or t.get('building:part') or 'yes'; yr = o.get('year')
        modern = bool(yr and yr >= P.get('modern_from', 1950))
        kind = GN.kind_of(t, lu, region, abs(signed_area(o['poly'])), o['h'])[0]
        walls = GN.trad_entry(P['walls'], bt, kind, modern, yr)
        if walls is not None and not (kind in GN.INDUSTRIAL_LIKE and kind not in P['walls'] and bt not in P['walls']):
            rf = GN.trad_entry(P['roof'], bt, kind, modern, yr) or GN.era(P['roof']['default'], yr)
            poly = o['poly']; n = len(poly); edges = sorted(math.dist(poly[i], poly[(i + 1) % n]) for i in range(n))
            short = min(edges[-1], max(3.0, abs(signed_area(poly)) / max(edges[-1], 1)))
            roofs = collections.Counter(); g = o.get('guessed', {})
            for mat, col, w in walls:
                rule = next((r_['roof'] for r_ in P.get('rules', []) if g.get('roof:shape') and 'building:part' not in t and GN.fits(r_['when'], b, kind, mat, short)), None)
                cols = (rule or {}).get('colours') or rf['colours']
                for c in cols: roofs[c] += w / len(cols)
            return [(c, w) for _, c, w in walls], sorted(roofs.items())
    wall, roof, kind, why = GN.look(b, region, lu)
    walls = [(c, 1) for c in GN.STONE_FR] if region == 'paris' and wall == 'limestone' else [(GN.WALL[wall][1], 1)]
    if region == 'paris' and o.get('roof') == 'mansard' and o.get('guessed', {}).get('roof:shape'): roof = 'zinc'
    return walls, [(GN.ROOF[roof][1], 1)]


# ------------------------------------------------------------------ the survey: each view loaded once into a cache, every count made from it
BINS = [(1, 1), (2, 2), (3, 4), (5, 7), (8, 12), (13, 20), (21, 40), (41, 10 ** 6)]
RADII = (80, 150, 250, 400)
CACHE = os.path.join(OUT, 'cache')
VARIANTS = [('kind', True, 0.0, RADII + ('region',)), ('any', False, 0.0, (150, 'region')), ('kind_gap40', True, 40.0, (150, 'region')),
            ('kind_beyond150', True, 150.01, ('region',))]     # same kind or any; samples nearer than the gap left out (a block tagged in one sweep)


def extract(view):
    """What the survey needs from a view, as plain data: its buildings (an outline with its parts) and what they map, the mapped colours once per
    building and value (generic.learned's samples), how the renderer reads each value, and today's look for every building that maps a colour."""
    S, region, landuse_at = load(view)
    P = GN.tradition(S.get('tradition')); pal = S['_pal']; own, kind = pal['own'], pal['kind']
    G = collections.OrderedDict()
    for b in S['buildings'] + S['parts']:
        o = own[id(b)]; G.setdefault(id(o), dict(o=o, els=[]))['els'].append(b)
    sampled = {s[3] for ss in pal['samples'].values() for s in ss}
    owners = []
    for gr in G.values():
        o = gr['o']; t = o['tags']
        if t.get('building') == 'houseboat': continue
        x, z = centroid(o['poly']); has = {k: any(e['tags'].get(k) for e in gr['els']) for k in KEYS}
        rec = dict(id=o['id'], kind=kind[id(o)], x=round(x, 1), z=round(z, 1), parts=sum(1 for e in gr['els'] if 'building:part' in e['tags']), has=has,
                   mat={k: t[k] for k in ('building:material', 'roof:material') if t.get(k)},
                   landmark=kind[id(o)] in LANDMARK or any(t.get(k) for k in ('wikidata', 'heritage', 'historic')),
                   default=GN.kind_of(t, landuse_at(o) if landuse_at else None, region, abs(signed_area(o['poly'])), o['h'])[1] == 'what most buildings here are',
                   own_gaps={k: sum(1 for e in gr['els'] if not e['tags'].get(k) and not e.get('hidden')) if has[k] else 0 for k in ('building:colour', 'roof:colour')})
        if o['id'] in sampled:
            tw, tr = today(o, region, P, landuse_at); rec['today'] = {'building:colour': tw, 'roof:colour': tr}
        owners.append(rec)
    els = {}
    for key in ('building:colour', 'roof:colour'):
        st = collections.Counter(); unread = collections.Counter()
        for b in S['buildings'] + S['parts']:
            v = b['tags'].get(key)
            if not v: continue
            st[parse(v)] += 1
            if parse(v) == 'unread': unread[v] += 1
        els[key] = dict(parse=dict(st), unread=unread.most_common(12))
    return dict(view=view, region=region, tradition=S.get('tradition'), buildings=len(S['buildings']), parts=len(S['parts']), owners=owners, elements=els,
                samples={k: [list(s) for s in ss] for k, ss in pal['samples'].items()})


def cached(view):
    os.makedirs(CACHE, exist_ok=True); p = os.path.join(CACHE, view + '.json')
    if not os.path.exists(p): json.dump(extract(view), open(p + '.tmp', 'w')); os.replace(p + '.tmp', p)       # whole, or not there
    D = json.load(open(p))
    for o in D['owners']: o['kind'] = o['kind'].rstrip('?') + ('?' if o['default'] else '')     # generic.kind_at's kinds (a cache made before it split them)
    kind = {o['id']: o['kind'] for o in D['owners']}
    for ss in D['samples'].values():
        for s in ss: s[0] = kind.get(s[3], s[0])
    return D


def squares(D):
    """How many squares (generic.PAL_R across) each kind's buildings stand in."""
    sq = collections.defaultdict(set)
    for o in D['owners']: sq[o['kind']].add(GN.square(o['x'], o['z']))
    return {k: len(v) for k, v in sq.items()}


def receivers(D, key):
    """Every building without the colour mapped, by where generic.pal_pool would take its colour from ('near', 'region' or None): it and its parts
    lack it, so its own building gives nothing; a mapped material keeps to samples of that material."""
    mkey = 'building:material' if key == 'building:colour' else 'roof:material'
    sq = squares(D); ss = D['samples'][key]; out = collections.Counter()
    for o in D['owners']:
        if o['has'][key]: continue
        m = o['mat'].get(mkey)
        same = [s for s in ss if s[0] == o['kind'] and (m is None or s[4] == m)]
        near = [s for s in same if math.hypot(s[1] - o['x'], s[2] - o['z']) <= GN.PAL_R]
        out[GN.pal_pool([], near, same, sq[o['kind']])[1]] += 1
    return out


def held_out(D):
    """Each mapped colour (one per building and value) against palettes of the other buildings' mapped colours, and against today's look
    for that building with its colours unmapped: energy scores in delta E, by the pool's size and where it stood."""
    byid = {o['id']: o for o in D['owners']}; sq = squares(D); rows = []
    for key in ('building:colour', 'roof:colour'):
        ss = D['samples'][key]; L = [lab(GN.colour_hex(s[5])) for s in ss]
        for i, s in enumerate(ss):
            o = byid.get(s[3])
            if o is None or 'today' not in o: continue
            d_ = [math.hypot(s2[1] - s[1], s2[2] - s[2]) for s2 in ss]
            row = dict(key=key, kind=s[0], value=norm(s[5]), today=round(es([(lab(c), w) for c, w in o['today'][key]], L[i]), 2))
            for tag, same_kind, gap, radii in VARIANTS:
                for r in radii:
                    pool = [L[j] for j, s2 in enumerate(ss) if s2[3] != s[3] and (not same_kind or s2[0] == s[0]) and (r == 'region' or d_[j] <= r) and d_[j] >= gap]
                    row['%s_%s' % (tag, r)] = (len(pool), round(es([(x, 1) for x in pool], L[i]), 2) if pool else None)
            # the rule itself: the pool generic.pal_pool would draw this building's colour from, were it unmapped
            same = [j for j, s2 in enumerate(ss) if s2[3] != s[3] and s2[0] == s[0]]
            pool, where = GN.pal_pool([], [ss[j] for j in same if d_[j] <= GN.PAL_R], [ss[j] for j in same], sq[s[0]])
            row['rule'] = (where, round(es([(L[j], 1) for j in same if where == 'region' or d_[j] <= GN.PAL_R], L[i]), 2) if pool else None)
            rows.append(row)
    return rows


def analyse(D):
    owners = D['owners']; n = len(owners)
    rep = dict(view=D['view'], region=D['region'], tradition=D['tradition'], buildings=D['buildings'], parts=D['parts'], owners=n)
    rep['mapped'] = {k: sum(o['has'][k] for o in owners) for k in KEYS}
    bk = collections.defaultdict(lambda: dict(n=0, default=0, **{k: 0 for k in KEYS}))
    for o in owners:
        d = bk[o['kind']]; d['n'] += 1; d['default'] += o['default']
        for k in KEYS: d[k] += o['has'][k]
    rep['by_kind'] = dict(sorted(bk.items(), key=lambda kv: -kv[1]['n']))
    vals = {}
    for key in ('building:colour', 'roof:colour'):
        ss = D['samples'][key]; el = D['elements'][key]
        words = collections.Counter(norm(s[5]) for s in ss); fam = collections.Counter(family(GN.colour_hex(s[5])) for s in ss)
        same = near = 0          # the same value on the nearest other mapped building within 60 m: a street or block tagged in one sweep
        for s in ss:
            best = min(((math.hypot(s2[1] - s[1], s2[2] - s[2]), s2) for s2 in ss if s2[3] != s[3]), default=None, key=lambda x: x[0])
            if best and best[0] <= 60: near += 1; same += norm(best[1][5]) == norm(s[5])
        vals[key] = dict(elements=sum(el['parse'].values()), parse=el['parse'], unread=el['unread'], samples=len(ss), distinct=len(words), top=words.most_common(10),
                         top3_share=round(sum(c for _, c in words.most_common(3)) / len(ss), 3) if ss else None,
                         hex_share=round(sum(1 for s in ss if s[5].startswith('#')) / len(ss), 3) if ss else None,
                         css_hex=sum(1 for s in ss if s[5].startswith('#') and s[5].upper() in CSS), families=fam.most_common(), near_60m=near, same_as_nearest=same)
    rep['values'] = vals
    def cov(sel):
        g_ = [o for o in owners if sel(o)]
        return dict(n=len(g_), wall=sum(o['has']['building:colour'] for o in g_), roof=sum(o['has']['roof:colour'] for o in g_),
                    material=sum(o['has']['building:material'] or o['has']['roof:material'] for o in g_))
    rep['skew'] = dict(landmarks=cov(lambda o: o['landmark']), homes=cov(lambda o: o['kind'] == 'home' and not o['landmark']),
                       other=cov(lambda o: o['kind'] != 'home' and not o['landmark']), modelled_3d=cov(lambda o: o['parts'] > 0), outline_only=cov(lambda o: o['parts'] == 0))
    rep['held_out'] = held_out(D)
    rep['receivers'] = {key: dict(receivers(D, key)) for key in ('building:colour', 'roof:colour')}
    rep['own_gaps'] = {key: sum(o['own_gaps'][key] for o in owners) for key in ('building:colour', 'roof:colour')}
    return rep


def binned(rows, field):
    """Held-out rows by pool size: n, mean score of the learned palette, of today's look on the same rows, and how often the palette wins."""
    out = []
    for lo, hi in BINS:
        sel = [r for r in rows if r[field][1] is not None and lo <= r[field][0] <= hi]
        if not sel: continue
        out.append(dict(bin='%d' % lo if lo == hi else ('%d+' % lo if hi > 10 ** 5 else '%d-%d' % (lo, hi)), n=len(sel),
                        learned=round(sum(r[field][1] for r in sel) / len(sel), 1), today=round(sum(r['today'] for r in sel) / len(sel), 1),
                        wins=round(sum(r[field][1] < r['today'] for r in sel) / len(sel), 2)))
    return out


def by_rule(rows):
    """Held-out rows by where the rule would take the colour from: n, mean scores, wins."""
    out = []
    for where in ('near', 'region', None):
        sel = [r for r in rows if r['rule'][0] == where]
        if not sel: continue
        lr = [r['rule'][1] for r in sel if r['rule'][1] is not None]
        out.append(dict(where=where or 'none (today\'s look stands)', n=len(sel), learned=round(sum(lr) / len(lr), 1) if lr else '-',
                        today=round(sum(r['today'] for r in sel) / len(sel), 1), wins=round(sum(r['rule'][1] < r['today'] for r in sel if r['rule'][1] is not None) / len(sel), 2) if lr else '-'))
    return out


def md_table(head, rows):
    return '\n'.join(['| ' + ' | '.join(head) + ' |', '|' + '|'.join('---' for _ in head) + '|'] + ['| ' + ' | '.join(str(c) for c in r) + ' |' for r in rows])


def pct(a, n): return '%d (%.1f%%)' % (a, 100.0 * a / n) if n else '0'


def write_survey(reps):
    os.makedirs(OUT, exist_ok=True)
    json.dump(reps, open(os.path.join(OUT, 'survey.json'), 'w'), indent=1)
    rule = 'near: %d or more within %g m; region: %d or more, in %g%% of the squares its kind stands in' % (GN.PAL_MIN, GN.PAL_R, GN.PAL_REGION_MIN, 100 * GN.PAL_REGION_SPREAD)
    W = ['# Building colour in the renderer\'s OSM extracts', '',
         'Made by `python3 -I render/palettes.py survey` (branch palettes), rule %s. A building is an outline with its parts (a part counts toward '
         'the outline holding it); houseboats left out. Kinds are generic.py\'s, as apply settles them.' % rule, '']
    W += ['## Coverage', '', md_table(['view', 'tradition', 'buildings', 'parts', 'building:colour', 'roof:colour', 'building:material', 'roof:material'],
          [[r['view'], r['tradition'] or '-', r['owners'], r['parts']] + [pct(r['mapped'][k], r['owners']) for k in KEYS] for r in reps]), '']
    W += ['## By kind (kinds with a colour or material mapped, or 50 buildings or more)', '']
    for r in reps:
        rows = [[k, d['n'], d['default'] or ''] + [d[x] or '' for x in KEYS] for k, d in r['by_kind'].items() if d['n'] >= 50 or any(d[x] for x in KEYS)]
        if rows: W += ['**%s**' % r['view'], '', md_table(['kind', 'buildings', 'of them by default'] + list(KEYS), rows), '']
    W += ['## Values', '']
    for r in reps:
        for key in ('building:colour', 'roof:colour'):
            v = r['values'][key]
            if not v['elements']: continue
            W += ['**%s %s**: %d mapped on buildings or parts (%s); %d values once per building, %d distinct, the top three %.0f%% of them; hex %.0f%% '
                  '(%d a CSS name\'s hex); %d of %d with a mapped neighbour within 60 m carry the same value as it.' % (
                      r['view'], key, v['elements'], ', '.join('%s %d' % kv for kv in sorted(v['parse'].items())), v['samples'], v['distinct'],
                      100 * (v['top3_share'] or 0), 100 * (v['hex_share'] or 0), v['css_hex'], v['same_as_nearest'], v['near_60m']), '',
                  '- top: ' + ', '.join('%s %d' % tuple(kv) for kv in v['top']), '- families: ' + ', '.join('%s %d' % tuple(kv) for kv in v['families'])]
            if v['unread']: W += ['- not read by the renderer: ' + ', '.join('%s %d' % tuple(kv) for kv in v['unread'])]
            W += ['']
    W += ['## Skew', '', 'Buildings in each group, and how many map a wall / roof colour.', '',
          md_table(['view', 'landmarks', 'ordinary homes', 'other kinds', 'modelled in parts', 'outline only'],
                   [[r['view']] + ['%d: %s / %s' % (r['skew'][g]['n'], pct(r['skew'][g]['wall'], r['skew'][g]['n']), pct(r['skew'][g]['roof'], r['skew'][g]['n']))
                                   for g in ('landmarks', 'homes', 'other', 'modelled_3d', 'outline_only')] for r in reps]), '']
    rows = [x for r in reps for x in r['held_out']]
    W += ['## Held out: a learned palette against today\'s look', '',
          'Each mapped colour (one per building and value) against the palette of the other buildings\' mapped colours: energy score in delta E '
          '(CIE76), lower is better, a varied palette not punished for its variety. Today = the tradition\'s or generic colours that building would '
          'get with its colours unmapped. kind_R: same kind within R m; any: any kind; gap40: samples nearer than 40 m left out (one sweep\'s block); '
          'beyond150: only samples further than 150 m, as a building far from any mapped one would get from the region.', '']
    for key in ('building:colour', 'roof:colour'):
        b_ = by_rule([x for x in rows if x['key'] == key])
        if b_: W += ['**%s, by the rule** (%s)' % (key, rule), '', md_table(['taken from', 'n', 'learned', 'today', 'learned wins'], [[x['where'], x['n'], x['learned'], x['today'], x['wins']] for x in b_]), '']
        for field in ('kind_150', 'kind_gap40_150', 'any_150', 'kind_400', 'kind_region', 'kind_beyond150_region'):
            b_ = binned([x for x in rows if x['key'] == key], field)
            if b_: W += ['**%s, %s**' % (key, field), '', md_table(['samples', 'n', 'learned', 'today', 'learned wins'], [[x['bin'], x['n'], x['learned'], x['today'], x['wins']] for x in b_]), '']
        for r in reps:
            for field in ('kind_150', 'kind_beyond150_region'):
                b_ = binned([x for x in r['held_out'] if x['key'] == key], field)
                if sum(x['n'] for x in b_) >= 10: W += ['%s %s, %s: ' % (r['view'], key, field) + '; '.join('%s: n %d, %s vs %s' % (x['bin'], x['n'], x['learned'], x['today']) for x in b_), '']
    W += ['## Who would take a learned colour', '', 'Buildings whose colour is not mapped, by where the rule takes it from (%s); pieces of a building '
          'whose other pieces map it take that.' % rule, '']
    for key in ('building:colour', 'roof:colour'):
        W += ['**%s**' % key, '', md_table(['view', 'unmapped', 'near', 'region', 'none', 'pieces taking their own building\'s'],
              [[r['view'], sum(r['receivers'][key].values())] + [pct(r['receivers'][key].get(w, 0), sum(r['receivers'][key].values())) for w in ('near', 'region', None)] + [r['own_gaps'][key]]
               for r in reps]), '']
    open(os.path.join(OUT, 'survey.md'), 'w').write('\n'.join(W) + '\n')


# ------------------------------------------------------------------ pictures
def png_of(layers, W, H, path, scale=2):
    """The stacked layers (svg paths, or (raster path, css clip)) as one PNG at scale x, through WebKit (qlmanage), 1024 px tiles stitched."""
    from png import read_png
    from mkref import write_png
    tmp = tempfile.mkdtemp(dir=OUT); Wp, Hp = W * scale, H * scale; tiles = []
    for i, top in enumerate(range(0, Hp, 1024)):
        imgs = []
        for L in layers:
            src, clip = (L, None) if isinstance(L, str) else L
            st = 'clip-path:%s' % re.sub(r'([\d.]+)px', lambda m: '%.1fpx' % (float(m.group(1)) * scale), clip) if clip else ''
            imgs.append('<img src="file://%s" style="%s">' % (os.path.abspath(src), st))
        html = os.path.join(tmp, 'p%d.html' % i)
        open(html, 'w').write('<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;padding:0;width:1024px;height:1024px;overflow:hidden;background:#fff}'
                              'img{position:absolute;left:0;top:%dpx;width:%dpx;height:%dpx}</style></head><body>%s</body></html>' % (-top, Wp, Hp, ''.join(imgs)))
        subprocess.run(['qlmanage', '-t', '-s', '1024', '-o', tmp, html], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        tiles.append((top, read_png(html + '.png')))
    rows = []
    for y in range(Hp):
        top, (pw, ph, bpp, px) = [t for t in tiles if t[0] <= y][-1]; o = (y - top) * pw * bpp
        rows.append(bytes(px[o + x * bpp + c] for x in range(Wp) for c in range(3)))
    write_png(path, Wp, Hp, rows); shutil.rmtree(tmp)
    return path


def pair_png(before, after, W, H, title, path):
    """Before and after side by side at 1x, captioned, one 1024 px WebKit tile cropped to the page."""
    from png import read_png
    from mkref import write_png
    tmp = tempfile.mkdtemp(dir=OUT); gap = 12; cap = 62; Wt, Ht = 2 * W + gap, H + cap
    cell = lambda p, x, label: ('<div style="position:absolute;left:%dpx;top:0;width:%dpx;font:600 13px -apple-system,Helvetica;color:#222">%s</div>'
                                '<img src="file://%s" style="position:absolute;left:%dpx;top:%dpx;width:%dpx;height:%dpx">' % (x, W, label, p, x, cap, W, H))
    html = os.path.join(tmp, 'pair.html')
    open(html, 'w').write('<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;padding:0;width:1024px;height:1024px;overflow:hidden;background:#fff}</style></head><body>'
                          '<div style="position:absolute;left:0;top:18px;width:%dpx;font:12px/15px -apple-system,Helvetica;color:#555">%s</div>%s%s</body></html>' % (
                              Wt, title, cell(before, 0, 'before: today\'s look'), cell(after, W + gap, 'after: learned palettes (ATLAS_PALETTES=1)')))
    subprocess.run(['qlmanage', '-t', '-s', '1024', '-o', tmp, html], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    pw, ph, bpp, px = read_png(html + '.png')
    rows = [bytes(px[(y * pw + x) * bpp + c] for x in range(Wt) for c in range(3)) for y in range(Ht)]
    write_png(path, Wt, Ht, rows); shutil.rmtree(tmp)
    return path


def render_view(view, moment):
    lay = os.path.join(OUT, 'layers'); os.makedirs(lay, exist_ok=True); res = {}
    for tag, on in (('before', False), ('after', True)):
        GN.PALETTES = on
        if view == 'ams' and (A.LAT0, A.LON0) != AMS_ORIGIN: raise SystemExit('ams renders in a process of its own: a region has moved amsterdam.py to its own origin')
        try:
            if view == 'ams': A.KIT = True; S = A.load(); cfg = dict(AMS_CFG)
            else: S = R.load(view); cfg = R.cfg_for(view)
        finally: GN.PALETTES = False
        if S.get('_tide'):            # the tide of that moment, as regions.py sets it
            y = max(-9.0, min(-0.6, S['_tide'][moment] - S['_bank'])); A.WATER_Y = y
            for r in S['_tidal']: S['water_y'][id(r)] = y
        stem = '%s_%s_%s' % (view, moment, tag); files = []
        if on and not S['_pal']['picks']:          # nothing learned: apply drew every building as with the flag off, so the drawing is the same
            res[tag] = dict(res['before'], png=shutil.copyfile(res['before']['png'], os.path.join(OUT, stem + '.png')), same_as_before=True)
            print(view, moment, tag, 'nothing learned: the same as before', flush=True); continue
        (base, objs), cam, info = A.render(S, moment, cfg, None, layer='split')
        for i, svg in enumerate([base] + list(objs if isinstance(objs, tuple) else (objs,))):
            p = os.path.join(lay, '%s_%d.svg' % (stem, i)); open(p, 'w').write(svg); files.append(p)
        if info.get('raster'):
            ext, data, clip = info['raster']; p = os.path.join(lay, '%s_t.%s' % (stem, ext)); open(p, 'wb').write(data); files.insert(1, (p, clip))
        tx, tz, rad = cfg['target'][0], cfg['target'][2], cfg['radius']
        seen = [b for b in S['buildings'] + S['parts'] if not b.get('hidden') and math.hypot(centroid(b['poly'])[0] - tx, centroid(b['poly'])[1] - tz) <= rad]
        picks = S.get('_pal', {}).get('picks', {})
        res[tag] = dict(png=png_of(files, cfg['W'], cfg['H'], os.path.join(OUT, stem + '.png')), in_view=len(seen),
                        mapped_wall=sum(1 for b in seen if b['tags'].get('building:colour')), mapped_roof=sum(1 for b in seen if b['tags'].get('roof:colour')),
                        learned_wall=sum(1 for b in seen if 'building:colour' in picks.get(id(b), {})), learned_roof=sum(1 for b in seen if 'roof:colour' in picks.get(id(b), {})))
        print(view, moment, tag, {k: v for k, v in res[tag].items() if k != 'png'}, flush=True)
    a = res['after']
    title = '%s, %s: %d buildings in view; mapped colours: %d walls, %d roofs; learned: %d walls, %d roofs (near %d+ within %g m, region %d+ in %g%% of its squares)' % (
        view, moment, a['in_view'], a['mapped_wall'], a['mapped_roof'], a['learned_wall'], a['learned_roof'], GN.PAL_MIN, GN.PAL_R, GN.PAL_REGION_MIN, 100 * GN.PAL_REGION_SPREAD)
    res['pair'] = pair_png(res['before']['png'], res['after']['png'], cfg['W'], cfg['H'], title, os.path.join(OUT, '%s_%s_pair.png' % (view, moment)))
    res['changed'], res['changed_share'] = diff_png(res['before']['png'], res['after']['png'], os.path.join(OUT, '%s_%s_changed.png' % (view, moment)))
    json.dump(res, open(os.path.join(OUT, '%s_%s.json' % (view, moment)), 'w'), indent=1)
    return res


def diff_png(before, after, path):
    """What the learned palettes changed: the after picture where it differs from the before one, faded to a mid grey where it does not
    (so a wall turned white stands out too)."""
    from png import read_png
    from mkref import write_png
    w, h, bb, pb = read_png(before); _, _, ba, pa = read_png(after); rows = []; changed = 0
    for y in range(h):
        row = bytearray()
        for x in range(w):
            i, j = (y * w + x) * bb, (y * w + x) * ba
            p, q = pb[i:i + 3], pa[j:j + 3]
            if max(abs(p[c] - q[c]) for c in range(3)) > 6: row += q; changed += 1
            else: g = int(0.3 * p[0] + 0.59 * p[1] + 0.11 * p[2]); row += bytes([int(g * 0.3 + 120 * 0.7)] * 3)
        rows.append(bytes(row))
    write_png(path, w, h, rows)
    return path, round(changed / (w * h), 4)


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    if sys.argv[1] == 'survey':
        reps = []
        for v in (sys.argv[2:] or views()):
            reps.append(analyse(cached(v))); r = reps[-1]
            print(v, r['owners'], 'buildings', r['mapped'], 'receivers', r['receivers'], flush=True)
        write_survey(reps)
    elif sys.argv[1] == 'render':
        for a in sys.argv[2:]:
            v, _, m = a.partition(':'); render_view(v, m or 'grey')
