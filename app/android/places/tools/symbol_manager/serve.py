#!/usr/bin/env python3
"""The symbol manager: a page over the symbols database (kinds.tsv) and every drawing Atlas has, to go through the
kinds one by one, see each drawing where the app shows it, and swap. Read live from the files, so whatever another
session or a build writes shows up on the page; a swap writes the database back, one line, and only when that line is
still what the page showed. A drawing replaced on its own page changes every kind that wears it in one write, all of
them or none, and one undo takes them all back.

    python3 places/tools/symbol_manager/serve.py [--port 8771]     # then open http://127.0.0.1:8771

The drawings: the boards' own (board_symbols.json, with the ids they stand in for), the bundled sets (symbols.json:
Maki, Temaki, Pinhead, Atlas's own), the interface set (ui.json), every icon of the pinned sets the source cache holds
(cache/places-src: Temaki today, Maki, Pinhead and Lucide when fetched), and the sets imported here (icon_sets/). A
swap to an icon of a pinned set that symbols.json lacks runs build_places_data.py --kinds, which brings it in; so does a
swap to a drawing of an imported set once Atlas may carry the set (its licence and source, and the notice a licence such
as MIT asks for, kept beside it as LICENSE), and LICENSES.md and the app's About & licences then credit the set.
Standard library only; it listens on 127.0.0.1 alone.
"""
import argparse
import datetime
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)                                 # places/tools
PLACES = os.path.dirname(TOOLS)                               # places
ANDROID = os.path.dirname(PLACES)                             # app/android
CLAUDEA = os.path.dirname(os.path.dirname(ANDROID))           # Claudea
RES = os.path.join(PLACES, 'src', 'main', 'resources', 'atlas', 'places')
KINDS = os.path.join(RES, 'kinds.tsv')
SRC = os.path.join(CLAUDEA, 'cache', 'places-src')
SETS = os.path.join(TOOLS, 'icon_sets')
HISTORY = os.path.join(CLAUDEA, 'cache', 'symbol-manager', 'history.jsonl')
STATIC = os.path.join(HERE, 'static')
BOARDS = os.path.join(ANDROID, 'app', 'src', 'test', 'kotlin', 'app', 'atlas', 'boards')

sys.path.insert(0, TOOLS)
import svgpaths  # noqa: E402

try:
    import build_places_data as BUILD  # noqa: E402  (mend_icon: an open set's icon as the build makes it)
except Exception:  # the page still works without the build's mends
    BUILD = None

RESERVED = {'maki', 'temaki', 'pinhead', 'atlas', 'ui', 'logo', 'lucide', 'board'}
STATES = ('', 'ok', 'redo', 'drawn')
WATCHED = ('kinds.tsv', 'symbols.json', 'ui.json', 'board_symbols.json', 'cuisine.json', 'kind_colours.tsv',
           'kind_names.tsv', 'categories.json')
LOCK = threading.Lock()


def load(path, default=None):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def text(path):
    try:
        with open(path, encoding='utf-8') as f:
            return f.read()
    except OSError:
        return ''


def today():
    d = datetime.date.today()
    return '%d %s' % (d.day, d.strftime('%B'))


def tree_mtime(path):
    best = 0.0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                best = max(best, os.path.getmtime(os.path.join(root, f)))
            except OSError:
                pass
    return best


def version():
    """The files the page is drawn from, each with its modification time: the page reloads when one moves."""
    v = {}
    for f in WATCHED:
        try:
            v[f] = os.path.getmtime(os.path.join(RES, f))
        except OSError:
            v[f] = 0
    v['icon_sets'] = tree_mtime(SETS)
    v['cache'] = max((os.path.getmtime(os.path.join(SRC, d)) for d in ('maki', 'temaki', 'pinhead', 'lucide')
                      if os.path.isdir(os.path.join(SRC, d))), default=0)
    return v


# ---------------------------------------------------------------------------------------------------------- drawings

_converted = {}


def convert(key, path, mend=None, places=3):
    """One SVG file as the build would draw it (Maki's rounded to 2 places, the others to 3): (viewBox, paths), kept
    until the file changes; None if it can't be."""
    try:
        m = os.path.getmtime(path)
    except OSError:
        return None
    hit = _converted.get(key)
    if hit and hit[0] == m:
        return hit[1]
    try:
        icon = svgpaths.svg_icon(text(path), places=places)
        if mend and BUILD is not None:
            icon = BUILD.mend_icon(mend, icon)
        out = [icon[0], [list(p) for p in icon[1]]]
    except Exception as e:  # noqa: BLE001 (an odd file is reported, not fatal)
        out = {'error': str(e)[:200]}
    _converted[key] = (m, out)
    return out


_pinhead = {'mtime': None, 'icons': {}}


def pinhead_icons(path):
    """Pinhead's index, every icon drawn as the build draws it (2 places, its mends), kept until the index changes."""
    m = os.path.getmtime(path)
    if _pinhead['mtime'] != m:
        icons = {}
        for name, e in (load(path, {}).get('icons') or {}).items():
            if not isinstance(e, dict) or 'svg' not in e:
                continue
            sid = 'pinhead-' + name
            try:
                icon = svgpaths.svg_icon(e['svg'], places=2)
                if BUILD is not None:
                    icon = BUILD.mend_icon(sid, icon)
                icons[sid] = [icon[0], [list(p) for p in icon[1]]]
            except Exception:  # noqa: BLE001 (an odd icon is left out, not fatal)
                pass
        _pinhead.update(mtime=m, icons=icons)
    return _pinhead['icons']


def cache_sets(have):
    """Every icon of the pinned open sets the source cache holds that symbols.json does not: candidates not yet in the app."""
    out = {}
    for pre in ('maki', 'temaki'):
        d = os.path.join(SRC, pre, 'x', 'package', 'icons')
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith('.svg'):
                continue
            sid = '%s-%s' % (pre, f[:-4])
            if sid in have:
                continue
            icon = convert(sid, os.path.join(d, f), mend=sid, places=2 if pre == 'maki' else 3)
            if isinstance(icon, list):
                out[sid] = {'set': pre, 'vb': icon[0], 'p': icon[1], 'extra': True}
    pin = os.path.join(SRC, 'pinhead', 'index.complete.json')
    if os.path.exists(pin):
        for sid, icon in pinhead_icons(pin).items():
            if sid not in have:
                out[sid] = {'set': 'pinhead', 'vb': icon[0], 'p': icon[1], 'extra': True}
    lucide = os.path.join(SRC, 'lucide')
    if os.path.isdir(lucide):
        for f in sorted(os.listdir(lucide)):
            if f.endswith('.svg'):
                icon = convert('lucide-' + f[:-4], os.path.join(lucide, f))
                if isinstance(icon, list):
                    out['lucide-' + f[:-4]] = {'set': 'lucide', 'vb': icon[0], 'p': icon[1], 'extra': True, 'preview': True}
    return out


def imported_sets():
    """The sets imported here: icon_sets/<prefix>/set.json and icons/*.svg, each drawing as the build draws it. A kind
    may wear one once Atlas may carry its set (the build's imported_set_problem: its licence and source, and the notice
    a licence such as MIT asks for); a swap to it then brings it in (build_places_data.py --kinds), which credits the
    set. Until then the drawings are there to compare, and the set and its drawings say why ('blocked')."""
    sets, drawings = [], {}
    if not os.path.isdir(SETS):
        return sets, drawings
    readable = BUILD.imported_sets() if BUILD is not None else {}
    for pre in sorted(os.listdir(SETS)):
        meta = load(os.path.join(SETS, pre, 'set.json'))
        icons = os.path.join(SETS, pre, 'icons')
        if not isinstance(meta, dict) or not os.path.isdir(icons):
            continue
        if BUILD is None:
            blocked = 'the page could not load the build script, so it cannot tell whether Atlas may carry the set'
        elif pre not in readable:
            blocked = 'the build reads no set by the short name %s' % pre
        else:
            blocked = BUILD.imported_set_problem(pre, meta)
        n = 0
        for f in sorted(os.listdir(icons)):
            if f.endswith('.svg'):
                sid = '%s-%s' % (pre, f[:-4])
                icon = convert(sid, os.path.join(icons, f), mend=sid)
                if isinstance(icon, list):
                    d = {'set': pre, 'vb': icon[0], 'p': icon[1], 'imported': True}
                    d.update({'blocked': blocked} if blocked else {'extra': True})
                    drawings[sid] = d
                    n += 1
        sets.append(dict(meta, prefix=pre, count=n, **({'blocked': blocked} if blocked else {})))
    return sets, drawings


SY = re.compile(r'\bSy\("([^"]+)"')
TX = re.compile(r'\bTx\("((?:[^"\\]|\\.)*)"')
COMMENT = re.compile(r'//\s*(.+)$')


def board_uses():
    """Where the boards draw each of their symbols: 'Street: Pizza slices' (the card's comment, else its text)."""
    out = {}
    if not os.path.isdir(BOARDS):
        return out
    for f in sorted(os.listdir(BOARDS)):
        if not f.endswith('Board.kt'):
            continue
        board = f[:-len('Board.kt')]
        lines = text(os.path.join(BOARDS, f)).split('\n')
        for i, line in enumerate(lines):
            for m in SY.finditer(line):
                label = ''
                for j in (i, i - 1, i - 2):
                    if j < 0 or (j != i and 'Sy(' in lines[j]):
                        break  # another symbol's line: its comment is its own
                    c = COMMENT.search(lines[j])
                    if c:
                        label = c.group(1)
                        break
                if not label:
                    for j in (i + 1, i + 2):
                        t = TX.search(lines[j]) if j < len(lines) else None
                        if t:
                            label = t.group(1)
                            break
                label = re.sub(r',\s*\d+(:.*)?$', '', label).split(':')[0].strip()[:60]
                uses = out.setdefault(m.group(1), [])
                entry = '%s: %s' % (board, label) if label else board
                if entry not in uses and len(uses) < 8:
                    uses.append(entry)
    return out


LITERAL = re.compile(r'"([A-Za-z][A-Za-z0-9_]*(?:[-.][A-Za-z0-9_]+)+)"')


def code_uses(ids):
    """Every place in the app's own code that names a drawing: {id: ['module/…/File.kt:12', …]}."""
    out = {}
    for module in sorted(os.listdir(ANDROID)):
        root = os.path.join(ANDROID, module, 'src', 'main')
        if not os.path.isdir(root):
            continue
        for dirpath, _dirs, files in os.walk(root):
            for f in files:
                if not f.endswith('.kt'):
                    continue
                path = os.path.join(dirpath, f)
                for n, line in enumerate(text(path).split('\n'), 1):
                    for m in LITERAL.finditer(line):
                        if m.group(1) in ids:
                            where = '%s:%d' % (os.path.relpath(path, ANDROID), n)
                            lst = out.setdefault(m.group(1), [])
                            if len(lst) < 40:
                                lst.append(where)
    return out


def families():
    cats = (load(os.path.join(RES, 'categories.json'), {}) or {}).get('categories', [])
    out = {}
    for c in cats:
        if c.get('group') or (c.get('shelf') and c.get('id') != 'osm'):
            name = c.get('names', {}).get('en', c['id'])
            out[name] = {'id': c['id'], 'name': name, 'colour': c.get('colour', '#5A6478'), 'symbol': c.get('symbol')}
    return out


def read_kinds(fams):
    """kinds.tsv's lines, each with its family (the heading it stands under) and the line as it is in the file."""
    out, fam = [], None
    for raw in text(KINDS).split('\n'):
        if raw.startswith('# '):
            h = raw[2:].strip()
            if h in fams:
                fam = h
            continue
        if not raw.strip() or raw.startswith('#'):
            continue
        c = raw.split('\t')
        if len(c) < 2 or not c[0].strip():
            continue
        out.append({'kind': c[0].strip(), 'symbol': c[1].strip(), 'state': c[2].strip() if len(c) > 2 else '',
                    'note': c[3].strip() if len(c) > 3 else '', 'family': fam, 'line': raw})
    return out


def names():
    en = (load(os.path.join(RES, 'i18n', 'en.json'), {}) or {}).get('p', {})
    full = {k: v[0] for k, v in en.items() if isinstance(v, list) and v}
    short = {}
    for raw in text(os.path.join(RES, 'kind_names.tsv')).split('\n'):
        if raw.startswith('#') or '\t' not in raw:
            continue
        c = raw.split('\t')
        if len(c) > 1 and c[1].strip() and c[1].strip() != '-':
            short[c[0].strip()] = c[1].strip()
    return full, short


def kind_colours():
    out = {}
    for raw in text(os.path.join(RES, 'kind_colours.tsv')).split('\n'):
        if raw.startswith('#') or not raw.strip():
            continue
        c = raw.split()
        if len(c) >= 2 and c[1].startswith('#'):
            out[c[0]] = c[1]
    return out


_data = {'version': None, 'payload': None}


def data():
    """Everything the page draws, rebuilt whenever one of its files moves."""
    v = version()
    if _data['version'] == v and _data['payload'] is not None:
        return _data['payload']
    fams = families()
    board = load(os.path.join(RES, 'board_symbols.json'), {}) or {}
    drawings = {}
    for x in board.get('symbols', []):
        coloured = any(len(p) > 2 and isinstance(p[2], dict) and ('c' in p[2] or 'g' in p[2]) for p in x.get('p', [])) \
            or any('c' in t for t in x.get('t', []) or [])
        d = {'set': 'board', 'vb': x.get('vb', '0 0 24 24'), 'p': x.get('p', []), 'n': x.get('n', 0)}
        if x.get('t'):
            d['t'] = x['t']
        if coloured:
            d['coloured'] = True
        drawings[x['id']] = d
    aliases = {k: v for k, v in (board.get('aliases') or {}).items() if v in drawings}
    for f, set_of in (('symbols.json', None), ('ui.json', 'ui')):
        for sid, e in (load(os.path.join(RES, f), {}) or {}).items():
            if sid in drawings:
                continue
            drawings[sid] = {'set': set_of or sid.split('-')[0], 'vb': e[0], 'p': e[1]}
            if sid in aliases:  # the app draws the boards' drawing for this id: the set's own is behind it
                drawings[sid]['aliasedTo'] = aliases[sid]
    have = set(drawings) | set(aliases)
    drawings.update(cache_sets(have))
    sets, imported = imported_sets()
    for k, d in imported.items():
        drawings.setdefault(k, d)
    full, short = names()
    presets = {p['id']: p['i'] for p in (load(os.path.join(RES, 'presets.json'), {}) or {}).get('presets', []) if 'i' in p}
    kinds = read_kinds(fams)
    for k in kinds:
        k['name'] = short.get(k['kind']) or full.get(k['kind']) or k['kind']
        k['fullName'] = full.get(k['kind'], '')
        if k['kind'] in presets:
            k['preset'] = presets[k['kind']]
    payload = {
        'version': v,
        'today': today(),
        'families': [fams[n] for n in fams],
        'kinds': kinds,
        'drawings': drawings,
        'aliases': aliases,
        'boards': board_uses(),
        'code': code_uses(set(drawings) | set(aliases)),
        'cuisine': load(os.path.join(RES, 'cuisine.json'), {}) or {},
        'kindColours': kind_colours(),
        'sets': sets,
        'paths': {'kinds': os.path.relpath(KINDS, CLAUDEA), 'cache': os.path.relpath(SRC, CLAUDEA),
                  'sets': os.path.relpath(SETS, CLAUDEA)},
    }
    _data.update(version=v, payload=json.dumps(payload, separators=(',', ':')).encode('utf-8'))
    return _data['payload']


# ------------------------------------------------------------------------------------------------------------ writes

def line_for(kind, symbol, state, note):
    cols = [kind, symbol, state]
    if note:
        cols.append(note)
    return '\t'.join(cols)


def write_atomically(path, body):
    """[path] with [body], all at once (a reader sees the old file or the new, never half), keeping its permissions."""
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix='.kinds-', suffix='.tmp')
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='') as f:
            f.write(body)
        os.chmod(tmp, stat.S_IMODE(os.stat(path).st_mode))
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def replace_line(kind, expect, new):
    """kinds.tsv with [kind]'s line [expect] replaced by [new]; ('changed', the line now) if it isn't [expect] any more."""
    lines = text(KINDS).split('\n')
    for i, raw in enumerate(lines):
        if raw.startswith('#') or '\t' not in raw:
            continue
        if raw.split('\t', 1)[0].strip() == kind:
            if raw != expect:
                return 'changed', raw
            lines[i] = new
            write_atomically(KINDS, '\n'.join(lines))
            return 'ok', new
    return 'missing', None


def replace_lines(rows, strict=True):
    """kinds.tsv with each (kind, expect, new) of [rows] put in, in one write. Strict, all or nothing: ('missing', kind)
    if a kind has no line, ('changed', [{kind, line}]) if a line isn't what the page showed. Not strict: the lines still
    as expected change and the others stay; ('ok', [the kinds left as they were])."""
    lines = text(KINDS).split('\n')
    at = {}
    for i, raw in enumerate(lines):
        if not raw.startswith('#') and '\t' in raw:
            at.setdefault(raw.split('\t', 1)[0].strip(), i)
    changed, left = [], []
    for kind, expect, _new in rows:
        i = at.get(kind)
        if i is None:
            if strict:
                return 'missing', kind
            left.append(kind)
        elif lines[i] != expect:
            changed.append({'kind': kind, 'line': lines[i]})
            left.append(kind)
    if strict and changed:
        return 'changed', changed
    todo = [(at[kind], new) for kind, expect, new in rows if kind in at and lines[at[kind]] == expect]
    for i, new in todo:
        lines[i] = new
    if todo:
        write_atomically(KINDS, '\n'.join(lines))
    return 'ok', left


def log(entry):
    os.makedirs(os.path.dirname(HISTORY), exist_ok=True)
    with open(HISTORY, 'a', encoding='utf-8') as f:
        f.write(json.dumps(entry, ensure_ascii=False) + '\n')


def history(limit=300):
    out = []
    for raw in text(HISTORY).split('\n'):
        if raw.strip():
            try:
                out.append(json.loads(raw))
            except ValueError:
                pass
    return out[-limit:][::-1]


def bring_in(symbol):
    """Runs the build's --kinds, which puts an icon of a pinned set that kinds.tsv names into symbols.json."""
    p = subprocess.run([sys.executable, 'build_places_data.py', '--kinds', '--src', SRC], cwd=TOOLS,
                       capture_output=True, text=True, timeout=300)
    out = (p.stdout + p.stderr).strip()
    return p.returncode == 0, out[-1500:]


def stamp():
    """A change's id (the time in milliseconds) and when it was made."""
    return '%d' % (time.time() * 1000), datetime.datetime.now().isoformat(timespec='seconds')


def change_of(body):
    """One kind's change as the page sent it: (kind, symbol, state, note without tabs or line breaks, the line it saw)."""
    return (str(body.get('kind', '')).strip(), str(body.get('symbol', '')).strip(), str(body.get('state', '')).strip(),
            re.sub(r'[\t\r\n]+', ' ', str(body.get('note', ''))).strip(), body.get('expect'))


def wearable(symbol, payload):
    """The drawing [symbol] names, if a kind may wear it today: (drawing, None), else (None, the answer saying why)."""
    drawings, aliases = payload['drawings'], payload['aliases']
    d = drawings.get(symbol) or drawings.get(aliases.get(symbol, ''))
    if d is None:
        return None, (400, {'error': 'no drawing called %s' % symbol})
    if d.get('blocked'):
        return None, (409, {'error': 'imported', 'message': 'Not in the app yet: %s. It is still yours to compare here.'
                            % d['blocked']})
    if d.get('preview'):
        return None, (409, {'error': 'preview', 'message': "Lucide's icons reach the app through the interface set "
                            '(ui_icons.tsv), not as a kind\'s symbol: compare them here, then ask Claude.'})
    return d, None


def set_kind(body):
    kind, symbol, state, note, expect = change_of(body)
    if state not in STATES:
        return 400, {'error': 'state must be one of: blank, ok, redo, drawn'}
    d, refused = wearable(symbol, json.loads(data()))
    if refused:
        return refused
    with LOCK:
        new = line_for(kind, symbol, state, note)
        status, line = replace_line(kind, expect, new)
        if status == 'missing':
            return 404, {'error': 'kinds.tsv has no line for %s' % kind}
        if status == 'changed':
            return 409, {'error': 'changed', 'line': line,
                         'message': 'This kind changed in kinds.tsv since the page showed it: look again, then swap.'}
        built = None
        if d.get('extra'):
            ok, out = bring_in(symbol)
            built = out
            if not ok:
                replace_line(kind, new, expect)
                return 500, {'error': 'build', 'message': 'build_places_data.py --kinds failed, so the line went back.',
                             'output': out}
        cid, at = stamp()
        entry = {'id': cid, 'at': at, 'kind': kind, 'before': expect, 'after': new}
        log(entry)
    return 200, {'ok': True, 'line': new, 'entry': entry, 'built': built}


def set_kinds(body):
    """Several kinds' lines in one write, all or none: the owner replacing a drawing for every kind that wears it. One
    history entry each, under one group, so a single undo takes them all back."""
    changes = body.get('changes')
    if not isinstance(changes, list) or not 1 <= len(changes) <= 400:
        return 400, {'error': 'changes: a list of 1 to 400 kinds'}
    payload = json.loads(data())
    rows, extra, seen = [], [], set()
    for c in changes:
        if not isinstance(c, dict):
            return 400, {'error': 'each change is an object'}
        kind, symbol, state, note, expect = change_of(c)
        if not kind or kind in seen:
            return 400, {'error': 'each kind once: %s' % (kind or 'a kind without a name')}
        seen.add(kind)
        if state not in STATES:
            return 400, {'error': 'state must be one of: blank, ok, redo, drawn'}
        d, refused = wearable(symbol, payload)
        if refused:
            return refused
        if d.get('extra') and symbol not in extra:
            extra.append(symbol)
        rows.append((kind, expect, line_for(kind, symbol, state, note)))
    with LOCK:
        status, info = replace_lines(rows)
        if status == 'missing':
            return 404, {'error': 'kinds.tsv has no line for %s' % info}
        if status == 'changed':
            n = len(info)
            return 409, {'error': 'changed', 'kinds': info,
                         'message': '%s changed in kinds.tsv since the page showed %s, so none was replaced: look again.'
                         % ('One of these kinds' if n == 1 else '%d of these kinds' % n, 'it' if n == 1 else 'them')}
        built = None
        if extra:
            ok, out = bring_in(extra)
            built = out
            if not ok:
                replace_lines([(kind, new, expect) for kind, expect, new in rows], strict=False)
                return 500, {'error': 'build', 'message': 'build_places_data.py --kinds failed, so the lines went back.',
                             'output': out}
        group, at = stamp()
        entries = []
        for i, (kind, expect, new) in enumerate(rows):
            entry = {'id': '%s-%d' % (group, i), 'at': at, 'kind': kind, 'before': expect, 'after': new, 'group': group}
            log(entry)
            entries.append(entry)
    return 200, {'ok': True, 'group': group, 'entries': entries, 'built': built}


def undo_group(group):
    """Every change of [group] taken back that is still as it was made; the ones changed again since stay."""
    hist = history(1000000)
    undone = {e.get('undoes') for e in hist if e.get('undoes')}
    entries = [e for e in hist if e.get('group') == group and not e.get('undoes') and e.get('id') not in undone]
    if not entries:
        return 404, {'error': 'nothing of that change is left to undo'}
    with LOCK:
        _status, left = replace_lines([(e['kind'], e['after'], e['before']) for e in entries], strict=False)
        back, at = stamp()
        n = 0
        for i, e in enumerate(entries):
            if e['kind'] in left:
                continue
            log({'id': '%s-%d' % (back, i), 'at': at, 'kind': e['kind'], 'before': e['after'], 'after': e['before'],
                 'undoes': e['id'], 'group': back})
            n += 1
    if not n:
        return 409, {'error': 'changed', 'message': 'Those lines have changed again since, so they were left as they are.'}
    return 200, {'ok': True, 'undone': n, 'left': left}


def undo(body):
    if body.get('group'):
        return undo_group(str(body['group']))
    want = str(body.get('id', ''))
    entry = next((e for e in history(10000) if e.get('id') == want), None)
    if entry is None:
        return 404, {'error': 'no such change'}
    with LOCK:
        status, line = replace_line(entry['kind'], entry['after'], entry['before'])
        if status != 'ok':
            return 409, {'error': 'changed', 'line': line,
                         'message': 'That line has changed again since, so it was left as it is.'}
        bid, at = stamp()
        back = {'id': bid, 'at': at, 'kind': entry['kind'], 'before': entry['after'], 'after': entry['before'], 'undoes': want}
        log(back)
    return 200, {'ok': True, 'line': entry['before'], 'entry': back}


def safe_name(name):
    base = os.path.basename(str(name)).lower()
    if base.endswith('.svg'):
        base = base[:-4]
    return re.sub(r'[^a-z0-9_-]+', '_', base).strip('_-')[:60]


def import_set(body):
    prefix = str(body.get('prefix', '')).strip().lower()
    if not re.fullmatch(r'[a-z][a-z0-9]{1,15}', prefix):
        return 400, {'error': 'The short name is 2 to 16 letters or digits, starting with a letter (it becomes the '
                     'drawings\' prefix: tabler-coffee).'}
    if prefix in RESERVED:
        return 400, {'error': '%s is taken by a set Atlas has.' % prefix}
    files = body.get('files') or []
    converted, failures = {}, []
    for f in files[:6000]:
        name = safe_name(f.get('name', ''))
        svg = f.get('text', '')
        if not name or not isinstance(svg, str):
            continue
        if len(svg) > 512 * 1024:
            failures.append({'name': name, 'error': 'larger than 512 KB'})
            continue
        try:
            vb, paths = svgpaths.svg_icon(svg, places=3)
            if not paths:
                raise ValueError('nothing drawn')
            converted[name] = (svg, [vb, [list(p) for p in paths]])
        except Exception as e:  # noqa: BLE001
            failures.append({'name': name, 'error': str(e)[:160]})
    if body.get('dry'):
        sample = {'%s-%s' % (prefix, n): {'set': prefix, 'vb': c[1][0], 'p': c[1][1]}
                  for n, c in list(converted.items())[:4000]}
        return 200, {'ok': True, 'count': len(converted), 'drawings': sample, 'failures': failures}
    if not converted:
        return 400, {'error': 'None of the files could be read as an icon.', 'failures': failures}
    with LOCK:
        icons = os.path.join(SETS, prefix, 'icons')
        os.makedirs(icons, exist_ok=True)
        for n, (svg, _icon) in converted.items():
            with open(os.path.join(icons, n + '.svg'), 'w', encoding='utf-8') as out:
                out.write(svg)
        meta_path = os.path.join(SETS, prefix, 'set.json')
        meta = load(meta_path, {}) or {}
        meta.update({k: str(body.get(k, '') or meta.get(k, ''))[:300] for k in ('name', 'licence', 'source')})
        meta['imported'] = today()
        with open(meta_path, 'w', encoding='utf-8') as out:
            json.dump(meta, out, ensure_ascii=False, indent=1)
        # the set's licence file, when the folder held one: the notice a licence such as MIT asks to go with the
        # drawings, kept beside the set for the build to credit (an import without one keeps the one there)
        notice = body.get('licenceText')
        kept = isinstance(notice, str) and bool(notice.strip()) and len(notice) <= 64 * 1024
        if kept:
            with open(os.path.join(SETS, prefix, 'LICENSE'), 'w', encoding='utf-8') as out:
                out.write(notice.replace('\r\n', '\n').strip() + '\n')
    return 200, {'ok': True, 'count': len(converted), 'failures': failures, 'licenceFile': kept}


# ------------------------------------------------------------------------------------------------------------ server

class Handler(BaseHTTPRequestHandler):
    server_version = 'SymbolManager/1'

    def log_message(self, fmt, *args):  # quiet, except errors
        if args and str(args[1] if len(args) > 1 else '').startswith(('4', '5')):
            sys.stderr.write('%s %s\n' % (self.address_string(), fmt % args))

    def allowed(self):
        port = self.server.server_address[1]
        hosts = {'127.0.0.1:%d' % port, 'localhost:%d' % port}
        if self.headers.get('Host') not in hosts:
            return False
        origin = self.headers.get('Origin')
        return origin is None or origin in {'http://' + h for h in hosts}

    def send(self, code, body, kind='application/json; charset=utf-8'):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self.allowed():
            return self.send(403, {'error': 'local only'})
        path = urlparse(self.path).path
        if path == '/api/version':
            return self.send(200, version())
        if path == '/api/data':
            return self.send(200, data())
        if path == '/api/history':
            return self.send(200, history())
        files = {'/': ('index.html', 'text/html; charset=utf-8'), '/index.html': ('index.html', 'text/html; charset=utf-8'),
                 '/app.js': ('app.js', 'text/javascript; charset=utf-8'), '/app.css': ('app.css', 'text/css; charset=utf-8')}
        if path in files:
            name, kind = files[path]
            with open(os.path.join(STATIC, name), 'rb') as f:
                return self.send(200, f.read(), kind)
        return self.send(404, {'error': 'not found'})

    def do_POST(self):
        if not self.allowed():
            return self.send(403, {'error': 'local only'})
        path = urlparse(self.path).path
        limit = 80 * 1024 * 1024 if path == '/api/import' else 256 * 1024
        n = int(self.headers.get('Content-Length') or 0)
        if n > limit or 'json' not in (self.headers.get('Content-Type') or ''):
            return self.send(400, {'error': 'a JSON body, please, and not too big'})
        try:
            body = json.loads(self.rfile.read(n) or b'{}')
        except ValueError:
            return self.send(400, {'error': 'bad JSON'})
        try:
            if path == '/api/kind':
                return self.send(*set_kind(body))
            if path == '/api/kinds':
                return self.send(*set_kinds(body))
            if path == '/api/undo':
                return self.send(*undo(body))
            if path == '/api/import':
                return self.send(*import_set(body))
        except Exception as e:  # noqa: BLE001
            return self.send(500, {'error': str(e)[:300]})
        return self.send(404, {'error': 'not found'})


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--port', type=int, default=8771)
    a = ap.parse_args()
    data()  # fail early, and warm the cache
    server = ThreadingHTTPServer(('127.0.0.1', a.port), Handler)
    print('symbol manager on http://127.0.0.1:%d (kinds.tsv: %s)' % (a.port, KINDS), flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
