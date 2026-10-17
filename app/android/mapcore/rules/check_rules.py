#!/usr/bin/env python3
"""The rule table's coverage of the renderer (plan-vector K5, fidelity's VF1).

rules.tsv holds every feature the renders draw as a record (stage, inputs, selectors, primitive class, exactness
class, the package that builds it, and the source lines it stands for). This script holds the table to the renderer's
features, not to its markup lines. The features are found as sites:

  - every SVG-emitting statement of amsterdam.py, regions.py, closeup.py, relief.py, terrain.py, woods.py, coasters.py,
    real.py and living.py (a statement whose code holds SVG markup: a string with `<tag`, `</tag` or `<!`; docstrings
    aside), and every statement of city.py's SVG helpers (P, ribbon, rect and the Camera), which make the coordinates
    the SVG prints; a line is a site when such a statement holds markup on it (city.py's helpers: every line of theirs);
  - every place a helper is called from: a function that makes markup (it holds SVG-emitting statements, or calls a
    helper) and that the renders call from two or more places (amsterdam.py's face(), gp() and box3(), city.py's P()
    and ribbon(), living.py's face_path() and draw_* for real.py) makes it for its callers, so each call is a site of
    the feature that makes it. The Camera's methods are the camera's own record, not their callers'. The renders record
    every call up the chain of the drawing files' frames as they run;
  - the rules that emit nothing themselves but whose values the drawn features read (RULES below: the sun's position,
    the moment's theme and the light, for the renders and for real.py's Williamsburg): each needs a record of its own;
  - shade.py, which draws nothing (it scores walking routes): it needs its record, and the check fails if it ever
    holds markup.

Every site must lie in a record's source lines, and every record must own a site that no other record covers, so a
feature without a record and a record without a feature of its own both fail the check: deleting any record fails it.
A site is reachable when the renders run it while drawing the boards' views, the named views and the sweeps at engine
pitch >= 18 degrees (RUNS below); the sites they never reach at those cameras (eye-level code, living.py's own renderer,
which real.py does not call) are covered too, by records of class X, so they are on record when they are dropped.
Execution is recorded by the renderer's own modules, compiled with a marker before each SVG-emitting statement and at
the start of each rule (`__svgmark__(i)`, which also notes the calls that led to it), so the renders run at nearly
their own speed; `validate` shows the markers record exactly the statements Python's trace mechanism (sys.settrace)
sees run.

    check_rules.py check [--sites]    the table against the recorded sites (no renders: seconds); --sites lists every
                                      record's sites, the ones it alone covers marked
    check_rules.py record [--jobs N]  draws RUNS with the markers, and writes the reachable lines and the helpers'
                                      callers into rules.tsv
    check_rules.py validate           one render under both the markers and sys.settrace: the same statements
    check_rules.py list [--all]       sites no record covers (--all: every site, with its function and its records)

`record` needs the regions' local extracts (data/regions/<name>/, git-ignored) and runs for about 15 minutes on three
cores; `check` needs only the sources and rules.tsv, and fails when a source changed since the recording (its
SHA-256 is recorded with its lines), so where it runs (meant for every push, in CI) the table and the renderer cannot
drift apart unnoticed.
"""
import ast, hashlib, importlib.abc, importlib.util, io, json, math, os, random, re, subprocess, sys, tempfile, time, tokenize

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..', '..', '..'))
RENDER = os.path.join(REPO, 'render')
TABLE = os.path.join(HERE, 'rules.tsv')
SHIM = os.path.join(REPO, 'app', 'android', 'render', 'src', 'test', 'python', 'shim')

# the files whose SVG-emitting lines the table covers; city.py only for its SVG helpers
DRAWING = ['amsterdam.py', 'regions.py', 'closeup.py', 'relief.py', 'terrain.py', 'woods.py', 'coasters.py', 'real.py', 'living.py', 'city.py']
CITY_HELPERS = {'P', 'ribbon', 'rect', 'Camera'}
NOT_DRAWING = ['shade.py']
MODULES = [f[:-3] for f in DRAWING + NOT_DRAWING]
TAG = re.compile(r'<[A-Za-z/!]')
# a string's tokens: Python 3.12 splits an f-string into parts (FSTRING_MIDDLE holds its text), 3.9 keeps it whole
STRING_TOKENS = {tokenize.STRING} | ({tokenize.FSTRING_MIDDLE} if hasattr(tokenize, 'FSTRING_MIDDLE') else set())
# The rules that emit no markup but whose values the drawn features read (plan-vector D8, D10, D14), as (file,
# top-level function): the sun's position and vector, the hour's weather and the moment's theme, and the light, for the
# renders (amsterdam.py) and for real.py's Williamsburg (living.py's and real.py's own).
RULES = [('amsterdam.py', 'sun_position'), ('amsterdam.py', 'wx_at'), ('amsterdam.py', 'weather_theme'),
         ('amsterdam.py', 'light'), ('living.py', 'sun_position'), ('living.py', 'sun_vector'), ('living.py', 'light'),
         ('real.py', 'theme')]
# Functions whose calls are their own record's, not emission sites of their callers: the camera's.
NOT_HELPERS = {('city.py', 'Camera')}

STAGES = {'P', 'T', 'S', 'F', 'M'}
CLASSES = {'E0', 'E1', 'E2', 'E3', 'X'}
PRIMITIVES = {'FACE', 'SEG', 'JOIN', 'ELL', 'TILE', 'TRI', 'STENCIL', 'TEXT', 'SPRITE', 'GROUP', 'FILTER', 'GRADIENT', 'SCREEN', 'RASTER', 'CAMERA', 'NONE'}
COLUMNS = ['id', 'feature', 'stage', 'inputs', 'selectors', 'primitive', 'class', 'package', 'source']


# ------------------------------------------------------------------ the SVG-emitting statements and the rules
class Unit:
    """One marked statement: an SVG-emitting statement (kind 'svg': its file, first and last line, the lines that carry
    its markup, its function), or the start of a rule (kind 'rule': its def line)."""
    def __init__(self, file, first, last, lines, where, kind='svg'):
        self.file, self.first, self.last, self.lines, self.where, self.kind = file, first, last, sorted(lines), where, kind


def _stmt_lists(node):
    for field in ('body', 'orelse', 'finalbody'):
        v = getattr(node, field, None)
        if isinstance(v, list) and v and isinstance(v[0], ast.stmt): yield v
    for h in getattr(node, 'handlers', None) or []: yield h.body


def _docstrings(tree):
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node.body:
            d = node.body[0]
            if isinstance(d, ast.Expr) and isinstance(d.value, ast.Constant) and isinstance(d.value.value, str): out.add(id(d))
    return out


def _chain(stmts, pos, path):
    """The statements of `stmts` holding source position `pos` (line, column), outermost first, with the functions
    each lies in."""
    for s in stmts:
        if (s.lineno, s.col_offset) <= pos < (s.end_lineno, s.end_col_offset):
            here = path + [s.name] if isinstance(s, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) else path
            for sub in _stmt_lists(s):
                r = _chain(sub, pos, here)
                if r: return [(s, path)] + r
            return [(s, path)]
    return None


DEFS = (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)


def _owner(stmts, pos, line):
    """The statement whose execution runs line `line` at position `pos`, as Python's trace counts lines: the innermost
    statement holding `pos`, or, when it starts on that line, the outermost statement around it that starts on the same
    line (`if x: out.append('<g>')` runs its line when the test runs), never leaving a function."""
    chain = _chain(stmts, pos, [])
    if not chain: return None
    k = len(chain) - 1
    if chain[k][0].lineno == line:
        while k > 0 and chain[k - 1][0].lineno == line and not isinstance(chain[k - 1][0], DEFS): k -= 1
    return chain[k]


def analyse(file):
    """(tree, units, source) of one renderer file: its SVG-emitting statements, by id(statement), and its rules, by
    ('rule', id(function))."""
    path = os.path.join(RENDER, file)
    src = open(path, encoding='utf-8').read()
    tree = ast.parse(src, path)
    docs = _docstrings(tree)
    owned = {}            # id(stmt) -> [stmt, lines, where]
    def add(s, where, line):
        e = owned.setdefault(id(s), [s, set(), where]); e[1].add(line)
    if file == 'city.py':
        for node in tree.body:
            if isinstance(node, (ast.FunctionDef, ast.ClassDef)) and node.name in CITY_HELPERS:
                for sub in ast.walk(node):
                    if isinstance(sub, ast.stmt) and not isinstance(sub, (ast.FunctionDef, ast.ClassDef)) and id(sub) not in docs:
                        body = next(_stmt_lists(sub), None)
                        last = sub.end_lineno if body is None else max(sub.lineno, body[0].lineno - 1)
                        for ln in range(sub.lineno, last + 1):
                            r = _owner(tree.body, (ln, sub.col_offset if ln == sub.lineno else 0), ln)
                            if r and not isinstance(r[0], DEFS): add(r[0], '.'.join(r[1]), ln)
    else:
        for tok in tokenize.generate_tokens(io.StringIO(src).readline):
            if tok.type not in STRING_TOKENS or not TAG.search(tok.string): continue
            r = _owner(tree.body, tok.start, tok.start[0])
            if r is None: continue
            s, where = r
            if id(s) in docs: continue
            add(s, '.'.join(where) or '<module>', tok.start[0])
    units = {k: Unit(file, s.lineno, s.end_lineno, lines, where) for k, (s, lines, where) in owned.items()}
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and (file, node.name) in RULES:
            units[('rule', id(node))] = Unit(file, node.lineno, node.end_lineno, [node.lineno], node.name, 'rule')
    return tree, units, src


def sha(src): return hashlib.sha256(src.encode('utf-8')).hexdigest()


# ------------------------------------------------------------------ the markers: the renderer's modules, instrumented
class _Mark(ast.NodeTransformer):
    """Puts `__svgmark__(i)` before each SVG-emitting statement and at the start of each rule."""
    def __init__(self, units, order):
        self.units, self.order = units, order
    def _marker(self, key, at):
        i = self.order.setdefault(key, len(self.order))
        call = ast.Expr(value=ast.Call(func=ast.Name(id='__svgmark__', ctx=ast.Load()), args=[ast.Constant(value=i)], keywords=[]))
        return ast.copy_location(call, at)
    def _block(self, stmts):
        out = []
        for s in stmts:
            self.visit(s)
            if id(s) in self.units: out.append(self._marker(id(s), s))
            out.append(s)
        return out
    def generic_visit(self, node):
        for field in ('body', 'orelse', 'finalbody'):
            v = getattr(node, field, None)
            if isinstance(v, list) and v and isinstance(v[0], ast.stmt): setattr(node, field, self._block(v))
        for h in getattr(node, 'handlers', None) or []: h.body = self._block(h.body)
        for field, v in ast.iter_fields(node):
            if field in ('body', 'orelse', 'finalbody', 'handlers'): continue
            if isinstance(v, ast.AST): self.visit(v)
            elif isinstance(v, list):
                for x in v:
                    if isinstance(x, ast.AST): self.visit(x)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and ('rule', id(node)) in self.units:
            node.body.insert(0, self._marker(('rule', id(node)), node.body[0]))
        return node


class Recorder(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    """Imports the renderer's modules compiled with the markers, and records what they run and where from: for every
    marked statement run, each call up the chain of the drawing files' frames, as (the function's file and def line,
    the caller's file, def line and line)."""
    def __init__(self):
        self.hits = {}      # file -> bytearray, one per unit
        self.units = {}     # file -> [Unit] in marker order
        self.edges = set()  # (callee file, callee def line, caller file, caller def line, caller line), paths absolute
        self.drawing = {os.path.join(RENDER, f) for f in DRAWING}
    def find_spec(self, name, path=None, target=None):
        if name in MODULES: return importlib.util.spec_from_file_location(name, os.path.join(RENDER, name + '.py'), loader=self)
        return None
    def create_module(self, spec): return None
    def exec_module(self, module):
        file = os.path.basename(module.__spec__.origin)
        tree, units, _ = analyse(file) if file in DRAWING else (ast.parse(open(module.__spec__.origin, encoding='utf-8').read()), {}, None)
        order = {}
        tree = _Mark(units, order).visit(tree)
        ast.fix_missing_locations(tree)
        self.units[file] = [None] * len(order)
        for k, i in order.items(): self.units[file][i] = units[k]
        hits = self.hits[file] = bytearray(len(order))
        edges, drawing, getframe = self.edges, self.drawing, sys._getframe
        def mark(i):
            hits[i] = 1
            f = getframe(1)             # the marked statement's function; its callers up to the first frame outside
            for _ in range(16):
                caller = f.f_back
                if caller is None: break
                cc = caller.f_code
                if cc.co_filename not in drawing: break
                code = f.f_code
                edges.add((code.co_filename, code.co_firstlineno, cc.co_filename, cc.co_firstlineno, caller.f_lineno))
                f = caller
        module.__dict__['__svgmark__'] = mark
        exec(compile(tree, module.__spec__.origin, 'exec'), module.__dict__)
    def reached(self):
        """The reachable lines per file, and per function (by its file and def line) the places it was called from
        (the caller's file, def line and line)."""
        lines, callers = {}, {}
        for f, hits in self.hits.items():
            lines[f] = sorted({ln for u, h in zip(self.units[f], hits) if h for ln in u.lines})
        for f, d, cf, cd, cl in self.edges:
            by_def = callers.setdefault(os.path.basename(f), {})
            by_def.setdefault(str(d), set()).add('%s:%d:%d' % (os.path.basename(cf), cd, cl))
        return dict(lines=lines, callers={f: {d: sorted(v) for d, v in c.items()} for f, c in callers.items()})


# ------------------------------------------------------------------ what the renderer draws for the record
SWEEP_SEED = 9
LIVING_CAM = dict(W=412, H=915, target=(-10, 0, -30), yaw=52, pitch=38, dist=430, fov=36, cy=440, radius=380)
LIVE_HOURS = [(8, 0), (12, 0), (16, 0), (19, 30), (22, 0)]       # the board cases' live instants on the recorded day
RECORDED_DAY = (2026, 10, 1)
REGIONS = ['grachten', 'paris', 'schiphol', 'port', 'dunes', 'sangimignano', 'bergen', 'nottinghill', 'efteling', 'artis', 'newyork']
SWEEPS = {'living': 24, 'grachten': 12}            # 4 for every other region


def sweep_cameras(region, n, knots, living):
    """n cameras of a region, seed 9: distance 91 m to 4 km, map pitch 0 to 70 degrees, any bearing, principal point
    440 or 457.5 dp, the target within 250 m of one of the region's views; the camera's settings as ViewLadder.place
    makes them between the views (radius, ground_r, haze0 and haze1 scaled with the distance, win_d interpolated,
    the rest from the nearest view)."""
    rnd = random.Random('%s/%d' % (region, SWEEP_SEED))
    ks = sorted(knots, key=lambda k: k['dist'])
    def value(k, key):
        if key in k: return float(k[key])
        return {'haze0': 380.0 if living else k['dist'] * 0.85, 'haze1': 800.0 if living else k['dist'] * 2.2, 'ground_r': 1500.0, 'radius': float(k['dist']), 'win_d': 390.0}[key]
    def between(dist, key):
        if dist <= ks[0]['dist']: return value(ks[0], key)
        if dist >= ks[-1]['dist']: return value(ks[-1], key)
        for a, b in zip(ks, ks[1:]):
            if dist <= b['dist']:
                if b['dist'] == a['dist']: return value(b, key)
                t = (dist - a['dist']) / (b['dist'] - a['dist']); return value(a, key) + (value(b, key) - value(a, key)) * t
        return value(ks[-1], key)
    def scaled(dist, key):
        if dist <= ks[0]['dist']: return value(ks[0], key) * dist / ks[0]['dist']
        if dist >= ks[-1]['dist']: return value(ks[-1], key) * dist / ks[-1]['dist']
        return between(dist, key)
    out = []
    for i in range(n):
        dist = math.exp(rnd.uniform(math.log(91), math.log(4000)))
        pitch = 90 - rnd.uniform(0, 70)
        yaw = rnd.uniform(0, 360)
        cy = rnd.choice([440, 457.5])
        k0 = rnd.choice(ks)
        tx = k0['target'][0] + rnd.uniform(-250, 250); tz = k0['target'][2] + rnd.uniform(-250, 250)
        c = dict(target=(tx, k0['target'][1], tz), yaw=yaw, pitch=pitch, dist=dist, fov=36, cy=cy)
        for key in ('radius', 'ground_r', 'haze0', 'haze1'): c[key] = scaled(dist, key)
        c['win_d'] = between(dist, 'win_d')
        near = min(ks, key=lambda k: abs(k['dist'] - dist))
        for key, v in near.items():
            if key not in ('target', 'yaw', 'pitch', 'dist', 'fov', 'cy', 'radius', 'ground_r', 'win_d', 'haze0', 'haze1', 'W', 'H', 'cx') and key not in c and not key.startswith('_'): c[key] = v
        c['W'] = 412; c['H'] = 916
        if not living: c['no_fin'] = True; c['obj_split'] = 1900000
        out.append(('sweep%d' % i, c, near.get('_name')))
    return out


def _dry_night(A):
    """A night moment of the place's own record without rain (the regions draw night from the weather)."""
    for day in ((2026, 10, 1), (2026, 9, 30), (2026, 10, 2)):
        for hh in (22, 23, 21, 1, 2, 3):
            try:
                th = A.weather_theme('drynight', day, (hh, 0))
            except (ValueError, IndexError):
                continue
            if th['night'] and not th['wet']: return th
    return None


def worker(group, outfile):
    """One group of RUNS in this process (the renderer keeps module state, so one place per process)."""
    rec = Recorder()
    sys.meta_path.insert(0, rec)
    sys.path[:0] = [RENDER, SHIM, os.path.join(REPO, 'data', 'regions')]
    os.chdir(RENDER)
    os.environ.pop('ATLAS_AO', None); os.environ.pop('ATLAS_LOOKS', None); os.environ.pop('ATLAS_DAYLIGHT', None)
    if group == 'kit': os.environ['ATLAS_KIT'] = '1'
    else: os.environ.pop('ATLAS_KIT', None)
    t0 = time.time(); n = 0
    if group in ('living', 'kit'):
        import amsterdam as A
        S = A.load()
        cfg = dict(LIVING_CAM)
        moments = ['sunny'] if group == 'kit' else ['sunny', 'grey', 'wet', 'night', 'golden', 'noon']
        for m in moments:
            A.render(S, m, cfg, None, layer='split'); n += 1
        if group == 'living':
            for hh, mm in LIVE_HOURS:
                key = 'live%02d%02d' % (hh, mm); A.THEMES[key] = A.weather_theme(key, RECORDED_DAY, (hh, mm))
                A.render(S, key, cfg, None, layer='split'); n += 1
            for lens, th in [('truth', 'golden'), ('year', 'golden'), ('truth', 'grey')] + [('tag:' + k, 'grey') for k in A.GAP_KEYS]:
                A.render(S, th, cfg, lens); n += 1
            # the fill-in quest's three layers around the building nearest the target (the FillIn board)
            cand = [b for b in S['buildings'] if not b['hidden'] and b['h'] and b['tags'].get('building') != 'houseboat']
            best = min(cand, key=lambda b: math.hypot(A.centroid(b['poly'])[0] - cfg['target'][0], A.centroid(b['poly'])[1] - cfg['target'][2]))
            for m in ('sunny', 'night'):
                q = A.render(S, m, cfg, None, split=best)
                q['variant'](best); q['variant'](best, 'truth'); q['variant'](dict(best, _hatch=True), 'truth'); n += 1
            knots = [dict(LIVING_CAM, _name='amsterdam')]
            for name, c, _ in sweep_cameras('living', SWEEPS['living'], knots, True):
                for m in ('sunny', 'night'):
                    A.render(S, m, c, None, layer='split'); n += 1
    elif group == 'real':
        import real as R, living as LV
        sys.path.insert(0, os.path.join(REPO, 'app', 'android', 'render', 'src', 'test', 'python'))
        import real_render_oracle as RO
        osm = tempfile.mkdtemp(prefix='rules-osm-')
        with open(os.devnull, 'w') as null:
            so = sys.stdout; sys.stdout = null
            try: RO.mkosm(REPO, osm)
            finally: sys.stdout = so
        R.OSM = osm
        S = R.load(); RO.fronts(S); R.attach_shops(S); water = R.water_polygon(S)
        cf, _ = RO.cfgs(S)
        for vn, tn, extra in RO.VIEWS:
            # some views stop inside real.py on this extract (a colour by name), as its oracle records; what they drew
            # before counts
            try: R.render(S, RO.th_of(tn, extra), cf[vn], water)
            except ValueError as e: print('real %s/%s stops: %s' % (vn, tn, e))
            n += 1
        for f in os.listdir(osm): os.remove(os.path.join(osm, f))
        os.rmdir(osm)
    else:
        import amsterdam as A, regions as RG
        P = RG.PLACES[group]
        views = [group + ('_' + v if v else '') for v in P.get('views', {})]
        knots = [dict(RG.CAMS[v], _name=v) for v in views]
        sweeps = sweep_cameras(group, SWEEPS.get(group, 4), knots, False)
        for v in views:
            S = RG.load(v)
            cfg = RG.cfg_for(v)
            night = _dry_night(A)
            if night is not None: A.THEMES['drynight'] = night
            moments = ['sunny', 'grey', 'wet'] + (['drynight'] if night is not None else []) + list((P.get('moments') or {}).keys())
            def draw(k, c):
                if S.get('_tide'):
                    t = S['_tide'].get(k, S['_tide'].get('sunny'))
                    y = max(-9.0, min(-0.6, t - S['_bank'])); A.WATER_Y = y
                    for r in S['_tidal']: S['water_y'][id(r)] = y
                A.render(S, k, c, None, layer='split')
            for k in moments:
                draw(k, cfg); n += 1
            for name, c, near in sweeps:
                if near != v: continue
                for k in ('sunny', 'drynight' if night is not None else 'wet'):
                    draw(k, c); n += 1
    json.dump(dict(group=group, renders=n, seconds=round(time.time() - t0, 1), reached=rec.reached()), open(outfile, 'w'))


def groups():
    return ['living', 'kit', 'real'] + REGIONS


# ------------------------------------------------------------------ the table
def read_table():
    """(records, reach, calls): the records as dicts; the recorded reachable lines per file with the source's SHA-256;
    and per file, with its SHA-256, the recorded callers of each of its functions (by def line), as (the caller's file,
    def line, line)."""
    records, reach, calls = [], {}, {}
    if not os.path.exists(TABLE): return records, reach, calls
    for n, line in enumerate(open(TABLE, encoding='utf-8'), 1):
        line = line.rstrip('\n')
        if not line: continue
        if line.startswith('#reach\t'):
            _, f, h, lines = line.split('\t')
            reach[f] = (h, [int(x) for x in lines.split(',') if x])
            continue
        if line.startswith('#calls\t'):
            _, f, h, entries = line.split('\t')
            by_def = {}
            for e in entries.split(';'):
                if not e: continue
                d, _, sites = e.partition('=')
                by_def[int(d)] = {(cf, int(cd), int(cl)) for cf, cd, cl in (x.split(':') for x in sites.split(','))}
            calls[f] = (h, by_def)
            continue
        if line.startswith('#'): continue
        cols = line.split('\t')
        if cols == COLUMNS: continue
        if len(cols) != len(COLUMNS): raise SystemExit('rules.tsv:%d: %d columns, not %d' % (n, len(cols), len(COLUMNS)))
        r = dict(zip(COLUMNS, cols)); r['_line'] = n
        records.append(r)
    return records, reach, calls


def parse_source(src):
    """'amsterdam.py:899-923,946 regions.py:551' -> [(file, first, last)]."""
    out = []
    for part in src.split():
        f, _, spans = part.partition(':')
        for s in spans.split(','):
            a, _, b = s.partition('-')
            out.append((f, int(a), int(b or a)))
    return out


def write_reach(reach, calls):
    """Puts the recorded reachable lines and callers at the end of rules.tsv, in place of the old ones."""
    keep = [l for l in open(TABLE, encoding='utf-8')] if os.path.exists(TABLE) else []
    keep = [l for l in keep if not l.startswith('#reach\t') and not l.startswith('#calls\t') and not l.startswith('# reachable ')]
    while keep and keep[-1].strip() == '': keep.pop()
    keep.append('\n# reachable lines and the functions\' callers, recorded by check_rules.py record (file, SHA-256 of its source, '
                'lines; def line=caller file:caller def line:caller line,...)\n')
    for f in sorted(reach):
        h, lines = reach[f]
        keep.append('#reach\t%s\t%s\t%s\n' % (f, h, ','.join(map(str, lines))))
    for f in sorted(calls):
        h, by_def = calls[f]
        entries = ';'.join('%d=%s' % (d, ','.join('%s:%d:%d' % x for x in sorted(by_def[d]))) for d in sorted(by_def))
        keep.append('#calls\t%s\t%s\t%s\n' % (f, h, entries))
    open(TABLE, 'w', encoding='utf-8').writelines(keep)


_DEF_ENDS = {}


def _def_end(file, def_line):
    """The last line of the function whose def is at `def_line` in `file`."""
    if file not in _DEF_ENDS:
        tree = ast.parse(open(os.path.join(RENDER, file), encoding='utf-8').read())
        _DEF_ENDS[file] = {n.lineno: n.end_lineno for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))}
    return _DEF_ENDS[file].get(def_line, def_line)


class Site:
    """A place the table must cover: an SVG line, a helper's call, a rule's def, or a file that draws nothing."""
    def __init__(self, file, line, kind, what, reached):
        self.file, self.line, self.kind, self.what, self.reached = file, line, kind, what, reached
    def __str__(self):
        return '%s:%d (%s %s%s)' % (self.file, self.line, self.kind, self.what, '' if self.reached else ', not reached')


def sites_of(errors):
    """Every site, from the sources and the recording; adds what is wrong with the recording to `errors`."""
    _, reach, calls = read_table()
    analysed = {}
    for f in DRAWING + NOT_DRAWING:
        _, units, src = analyse(f)
        analysed[f] = ({u.first: u for u in units.values()}, sha(src), src.count('\n') + 1, units)
    for f in NOT_DRAWING:
        if any(u.kind == 'svg' for u in analysed[f][3].values()):
            errors.append('%s is listed as not drawing, but holds markup' % f)
    if not reach: errors.append('no reachable lines recorded: run check_rules.py record')
    reached = {}
    for f in DRAWING:
        units, h = analysed[f][0], analysed[f][1]
        if f not in reach:
            if reach: errors.append('%s: no reachable lines recorded' % f)
            continue
        if reach[f][0] != h or (f in calls and calls[f][0] != h):
            errors.append('%s changed since its sites were recorded: run check_rules.py record, then update the table' % f)
            continue
        marked = {ln for u in units.values() for ln in u.lines}
        for ln in reach[f][1]:
            if ln not in marked: errors.append('%s:%d is recorded reachable but is not a site (stale recording?)' % (f, ln))
        reached[f] = set(reach[f][1])
    # the functions of the drawing files: (file, def line) -> qualified name, and the Camera's, which are no helpers
    names, camera = {}, set()
    for f in DRAWING:
        tree = ast.parse(open(os.path.join(RENDER, f), encoding='utf-8').read())
        def walk(node, path, in_camera):
            for sub in ast.iter_child_nodes(node):
                if isinstance(sub, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    here = path + [sub.name]
                    inside = in_camera or (f, here[0]) in NOT_HELPERS
                    if not isinstance(sub, ast.ClassDef):
                        names[(f, sub.lineno)] = '.'.join(here)
                        if inside: camera.add((f, sub.lineno))
                    walk(sub, here, inside)
                else:
                    walk(sub, path, in_camera)
        walk(tree, [], False)
    # who calls each function, and the function each call is made in
    callers, container = {}, {}
    for f in DRAWING:
        for d, cs in (calls.get(f) or ('', {}))[1].items():
            for cf, cd, cl in cs:
                callers.setdefault((f, d), set()).add((cf, cl))
                container[(cf, cl)] = (cf, cd)
    # helpers: a function that makes markup (it holds an SVG-emitting statement, or calls a helper) and is called from
    # two or more places makes it for its callers, so each call is a site of the caller's feature
    makes = set()
    for f in DRAWING:
        defs = sorted(d for (g, d) in names if g == f)
        for u in analysed[f][3].values():
            if u.kind != 'svg': continue
            enclosing = [d for d in defs if d <= u.first and u.first <= _def_end(f, d)]
            if enclosing: makes.add((f, max(enclosing)))
    helpers = set()
    grew = True
    while grew:
        grew = False
        for fn in sorted(makes):
            if fn in helpers or fn in camera or len(callers.get(fn, ())) < 2: continue
            helpers.add(fn); grew = True
            for site in callers[fn]:
                makes.add(container[site])
    sites = {}
    for f in DRAWING:
        for u in analysed[f][3].values():
            got = reached.get(f, set())
            for ln in u.lines:
                sites.setdefault((f, ln), Site(f, ln, u.kind, u.where, ln in got))
    for fn in sorted(helpers):
        for cf, cl in sorted(callers[fn]):
            sites.setdefault((cf, cl), Site(cf, cl, 'call', 'of %s:%s' % (fn[0], names.get(fn, '<expression@%d>' % fn[1])), True))
    for f, name in RULES:
        if not any(u.kind == 'rule' and u.where == name for u in analysed[f][3].values()):
            errors.append('the rule %s:%s is not a top-level function of the renderer' % (f, name))
    for (f, ln), s in sites.items():
        if s.kind == 'rule' and not s.reached and f in reached:
            errors.append('the rule %s:%s never ran in the recorded renders (stale RULES?)' % (f, s.what))
    for f in NOT_DRAWING: sites[(f, 1)] = Site(f, 1, 'file', 'draws nothing', False)
    return sites, analysed, helpers


def coverage(records, sites):
    """Per site, the ids of the records whose source lines hold it."""
    spans = [(r['id'], parse_source(r['source'])) for r in records]
    return {key: [rid for rid, sp in spans if any(f == key[0] and a <= key[1] <= b for f, a, b in sp)] for key in sites}


def check(show_sites=False):
    records, _, _ = read_table()
    errors = []
    sites, analysed, helpers = sites_of(errors)
    ids = set()
    for r in records:
        where = 'rules.tsv:%d (%s)' % (r['_line'], r['id'])
        if r['id'] in ids: errors.append('%s: the id is used twice' % where)
        ids.add(r['id'])
        if r['stage'] not in STAGES and not set(r['stage'].split('+')) <= STAGES: errors.append('%s: stage %r' % (where, r['stage']))
        if not set(r['class'].split('/')) <= CLASSES: errors.append('%s: class %r' % (where, r['class']))
        if not set(r['primitive'].split('+')) <= PRIMITIVES: errors.append('%s: primitive %r' % (where, r['primitive']))
        for f, a, b in parse_source(r['source']):
            if f not in analysed: errors.append('%s: %s is not a renderer file in scope' % (where, f)); continue
            if not (1 <= a <= b <= analysed[f][2]): errors.append('%s: %s:%d-%d is outside the file' % (where, f, a, b))
    cover = coverage(records, sites)
    for key, rids in sorted(cover.items()):
        if not rids: errors.append('%s: no record covers it' % sites[key])
    owned = {r['id']: [] for r in records}
    for key, rids in cover.items():
        if len(rids) == 1: owned[rids[0]].append(sites[key])
    for r in records:
        if not owned[r['id']]:
            shared = [str(sites[k]) for k, rids in cover.items() if r['id'] in rids][:3]
            errors.append('rules.tsv:%d (%s): owns no site that no other record covers%s' % (
                r['_line'], r['id'], (' (it shares %s)' % ', '.join(shared)) if shared else ' (it covers no site)'))
    if show_sites:
        for r in records:
            mine = sorted((sites[k] for k, rids in cover.items() if r['id'] in rids), key=lambda s: (s.file, s.line))
            print('%s\t%s' % (r['id'], ' '.join(('*' if len(cover[(s.file, s.line)]) == 1 else '') + '%s:%d' % (s.file, s.line) for s in mine)))
    kinds = {}
    for s in sites.values(): kinds[s.kind] = kinds.get(s.kind, 0) + 1
    print('rules.tsv: %d records; %d sites (%d SVG lines, %d of them reached; %d calls of %d helpers; %d rules; %d file that '
          'draws nothing), %d covered, every record owning one: %s' % (
              len(records), len(sites), kinds.get('svg', 0), sum(1 for s in sites.values() if s.kind == 'svg' and s.reached),
              kinds.get('call', 0), len(helpers), kinds.get('rule', 0), kinds.get('file', 0),
              sum(1 for rids in cover.values() if rids), 'yes' if all(owned.values()) else 'no'))
    for e in errors[:200]: print('FAIL:', e)
    if len(errors) > 200: print('FAIL: … and %d more' % (len(errors) - 200))
    return 1 if errors else 0


def record(jobs):
    """Draws every group of RUNS in its own process (at most `jobs` at once) and records the reachable lines and the
    helpers' callers."""
    tmp = tempfile.mkdtemp(prefix='rules-record-')
    todo = groups(); running = []; done = {}
    env = dict(os.environ, PYTHONHASHSEED='0', PYTHONIOENCODING='utf-8')
    t0 = time.time()
    while todo or running:
        while todo and len(running) < jobs:
            g = todo.pop(0); out = os.path.join(tmp, g + '.json'); log = open(os.path.join(tmp, g + '.log'), 'w')
            running.append((g, out, log, subprocess.Popen([sys.executable, os.path.abspath(__file__), '_worker', g, out], env=env, stdout=log, stderr=subprocess.STDOUT)))
        time.sleep(1)
        for item in list(running):
            g, out, log, p = item
            if p.poll() is None: continue
            running.remove(item); log.close()
            if p.returncode != 0:
                print(open(os.path.join(tmp, g + '.log')).read()[-4000:])
                raise SystemExit('group %s failed (%d)' % (g, p.returncode))
            done[g] = json.load(open(out))
            print('%-13s %4d renders %7.1f s' % (g, done[g]['renders'], done[g]['seconds']), flush=True)
    lines, callers = {}, {}
    for g in done.values():
        for f, ls in g['reached']['lines'].items(): lines.setdefault(f, set()).update(ls)
        for f, by_def in g['reached']['callers'].items():
            for d, cs in by_def.items():
                callers.setdefault(f, {}).setdefault(int(d), set()).update(
                    (cf, int(cd), int(cl)) for cf, cd, cl in (c.split(':') for c in cs))
    reach, calls = {}, {}
    for f in DRAWING:
        _, _, src = analyse(f)
        reach[f] = (sha(src), sorted(lines.get(f, ())))
        calls[f] = (sha(src), callers.get(f, {}))
    write_reach(reach, calls)
    for f in os.listdir(tmp): os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    print('recorded %d reachable lines and %d callers from %d renders in %.0f s' % (
        sum(len(v[1]) for v in reach.values()), sum(len(cs) for v in calls.values() for cs in v[1].values()),
        sum(g['renders'] for g in done.values()), time.time() - t0))


def validate():
    """The Westerkerk home, sunny and at night, under the markers and under sys.settrace at once: the statements each
    records as executed must be the same."""
    rec = Recorder()
    sys.meta_path.insert(0, rec)
    sys.path[:0] = [RENDER, SHIM]
    os.chdir(RENDER)
    for k in ('ATLAS_AO', 'ATLAS_LOOKS', 'ATLAS_DAYLIGHT', 'ATLAS_KIT'): os.environ.pop(k, None)
    import amsterdam as A
    S = A.load()
    files = {os.path.join(RENDER, f) for f in DRAWING}
    seen = set()
    def local(frame, event, arg):
        if event == 'line': seen.add((os.path.basename(frame.f_code.co_filename), frame.f_lineno))
        return local
    def glob(frame, event, arg):
        return local if frame.f_code.co_filename in files else None
    sys.settrace(glob)
    try:
        for m in ('sunny', 'night'): A.render(S, m, dict(LIVING_CAM), None, layer='split')
    finally:
        sys.settrace(None)
    bad = extra = total = 0
    for f in rec.units:
        if f not in DRAWING: continue
        for u, h in zip(rec.units[f], rec.hits[f]):
            if u.kind != 'svg': continue
            # per SVG line: the markers count a statement's lines as run when it starts; the trace reports a line when
            # code on it runs. The markers must miss no line the trace sees; they may count a continuation line the
            # trace did not report (a branch of an expression spread over lines), which only makes the table stricter.
            for ln in u.lines:
                traced = (f, ln) in seen
                total += 1
                if traced and not h:
                    bad += 1; print('missed: %s:%d (%s): the trace ran it, the markers did not' % (f, ln, u.where))
                elif h and not traced:
                    extra += 1; print('stricter: %s:%d (%s): the markers count it, the trace did not report it' % (f, ln, u.where))
    print('validate: %d SVG-emitting lines; %d run by the trace and missed by the markers; %d counted by the markers only' % (total, bad, extra))
    return 1 if bad else 0


def listing(all_sites):
    records, _, _ = read_table()
    errors = []
    sites, analysed, _ = sites_of(errors)
    cover = coverage(records, sites)
    for key in sorted(sites):
        s = sites[key]
        if all_sites or not cover[key]:
            text = open(os.path.join(RENDER, s.file), encoding='utf-8').read().split('\n')[s.line - 1].strip()[:140]
            print('%s:%d\t%s %s\t%s\t%s\t%s' % (s.file, s.line, s.kind, s.what, 'reach' if s.reached else '-',
                                              ','.join(cover[key]) or '-', text))
    for e in errors: print('FAIL:', e)


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else 'check'
    if cmd == 'check': sys.exit(check('--sites' in sys.argv))
    elif cmd == 'record':
        jobs = int(sys.argv[sys.argv.index('--jobs') + 1]) if '--jobs' in sys.argv else 3
        record(jobs)
    elif cmd == 'validate': sys.exit(validate())
    elif cmd == 'list': listing('--all' in sys.argv)
    elif cmd == '_worker': worker(sys.argv[2], sys.argv[3])
    else: raise SystemExit(__doc__)
