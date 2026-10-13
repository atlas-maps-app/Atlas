"""Pictures of every 412-wide screen for the Envisioning studio, and its screen list.

python3 thumbs.py  ->  studio/screens.json and, where missing or older than their source, the pictures:
  Main canvas (canvas/project): cache/pictures/full/<Board>.jpg (824 wide, rendered from the local canvas copy with qa/sheet.sh),
  Engine canvas (engine/project): its working pages' boards (not its folder pages) as the "Engine" group; their full-size
    picture is cache/pictures/engine/<Board>.jpg, never rendered here (a missing one is listed and the screen has no picture);
  for both: cache/pictures/big/<Board>.jpg (824 wide, quality 60: the large view) and cache/pictures/img/<Board>.jpg (412 wide: cards).
screens.json: {from, pages: [{id, name, folder, canvas?, screens: [{name, thumb, big}]}]}, the Main canvas's working pages
in canvas order, then the Engine's (id eng_<page>, name "Engine · <page>", canvas = the Engine canvas's link), then the
Main canvas's folders ("Folder ·" or "Set aside ·" pages), so screens can move back. Whether a screen is good comes from
its stars in the studio (4 or more), not from here.
An artifact version holds at most 511 files: two pictures per screen fits 254 screens.
Options (after_canvas.py uses them): --canvas <main project dir> --engine <engine project dir> (other copies than the two
above), --out <screens.json path>, --list (make no pictures: print which need making, as a line `NEEDS {json}`).
A picture is out of date when its board changed: studio/full_hashes.json keeps the sha1 of each board a full picture was
made from (written when pictures are made); a board not in it falls back to the file times."""
import json, os, hashlib, argparse, subprocess, concurrent.futures as cf
_ap = argparse.ArgumentParser(); _ap.add_argument('--canvas'); _ap.add_argument('--engine'); _ap.add_argument('--out'); _ap.add_argument('--list', action='store_true')
_a = _ap.parse_args()
A = os.path.dirname(os.path.dirname(os.path.abspath(__file__))); P = _a.canvas or A + '/canvas/project'; E = _a.engine or A + '/engine/project'
F = A + '/cache/pictures/full'; EF = A + '/cache/pictures/engine'; I = A + '/cache/pictures/img'; G = A + '/cache/pictures/big'
OUT = _a.out or A + '/studio/screens.json'; LIST = _a.list; HASHES = A + '/studio/full_hashes.json'
SEEN = json.load(open(HASHES)) if os.path.exists(HASHES) else {}
sha = lambda p: hashlib.sha1(open(p, 'rb').read()).hexdigest() if os.path.exists(p) else None
import re
def js_drawn(src):
    """A board drawn by its own script: Quick Look shows it blank, so its picture comes from a DOM snapshot (snaps_render.py)."""
    m = re.search(r'<script type="text/x-dc" data-dc-script[^>]*>(.*?)</script>', open(src).read(), re.S) if os.path.exists(src) else None
    return bool(m) and re.sub(r'\s+', '', m.group(1)) != 'classComponentextendsDCLogic{renderVals(){return{};}}'
ENGINE = 'https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR'
GONE = {'start', 'folder_engine'}      # Start's boards are not screens; Main's Folder · Engine is not on Main (its boards are the Engine's)
W = os.environ.get('TMPDIR', '/tmp').rstrip('/') + '/atlas_thumbs'
for d in (F, I, G, W): os.makedirs(d, exist_ok=True)


def screens_of(c, pid):
    B = c['boards']
    return [k[:-8] for k, b in sorted(B.items(), key=lambda kv: (kv[1]['y'], kv[1]['x'])) if b.get('page') == pid and b['w'] == 412]


is_folder = lambda name: name.startswith('Folder') or name.startswith('Set aside')
main = json.load(open(P + '/canvas.json'))
work, folders = [], []
for p in main['pages']:
    if p['id'] in GONE: continue
    bs = screens_of(main, p['id'])
    if bs: (folders if is_folder(p['name']) else work).append({'id': p['id'], 'name': p['name'], 'folder': is_folder(p['name']), 'screens': bs})
eng = json.load(open(E + '/canvas.json'))
engine = []
for p in eng['pages']:
    if is_folder(p['name']): continue
    bs = screens_of(eng, p['id'])
    if bs: engine.append({'id': 'eng_' + p['id'], 'name': 'Engine · ' + p['name'], 'folder': False, 'canvas': ENGINE, 'screens': bs})
mainNames = {s for p in work + folders for s in p['screens']}
for p in engine:
    clash = [s for s in p['screens'] if s in mainNames]
    if clash: print('warning: on both canvases, kept as the Main board:', clash); p['screens'] = [s for s in p['screens'] if s not in mainNames]
engNames = {s for p in engine for s in p['screens']}
if len(engNames) > 20: print('warning:', len(engNames), 'Engine screens; the plan allows at most 20')


def stale(out, src): return not os.path.exists(out) or os.path.getmtime(out) < os.path.getmtime(src)
def changed(name, full, src):
    """The full picture is missing or its board changed since it was made (by the kept sha1, else by the file times)."""
    if not os.path.exists(full): return True
    if name in SEEN: return SEEN[name] != sha(src)
    return stale(full, src)


made = []; need = {'full': [], 'snap': [], 'engine_full': [], 'small': [], 'missing': []}
def one(name):
    big, img = G + '/' + name + '.jpg', I + '/' + name + '.jpg'
    if name in engNames:
        full = EF + '/' + name + '.jpg'; src = E + '/' + name + '.dc.html'
        if LIST and changed(name, full, src): need['engine_full'].append(name)
        if not os.path.exists(full): return name, False
        # cache/pictures/engine is made outside this script: a picture newer than its board shows the board as it is, so its sha1 is
        # recorded (otherwise a changed Engine board stays listed as needing its picture after the picture was remade)
        if not LIST and (name not in SEEN or (SEEN[name] != sha(src) and not stale(full, src))): SEEN[name] = sha(src)
    else:
        src, full = P + '/' + name + '.dc.html', F + '/' + name + '.jpg'
        if changed(name, full, src) and js_drawn(src):       # a new DOM snapshot first, then snaps_render.py (never Quick Look)
            need['snap'].append(name)
            if not os.path.exists(full): return name, False
        elif LIST:
            if changed(name, full, src): need['full'].append(name)
        elif changed(name, full, src):
            d = W + '/' + name
            subprocess.run(['zsh', A + '/qa/sheet.sh', d, src], env=dict(os.environ, SCALE='1.0'), capture_output=True)
            png = d + '/sheet.html.png'
            if not os.path.exists(png): return name, False
            subprocess.run(['sips', '--cropOffset', '16', '16', '-c', '1830', '824', png, '--out', d + '/crop.png'], capture_output=True)
            subprocess.run(['sips', '-s', 'format', 'jpeg', '-s', 'formatOptions', '80', d + '/crop.png', '--out', full], capture_output=True)
            made.append('full/' + name + '.jpg'); SEEN[name] = sha(src)
        elif name not in SEEN: SEEN[name] = sha(src)        # first run with the sha1s: this picture is up to date by its times
    if LIST:
        if name in need['full'] or name in need['snap'] or name in need['engine_full'] or stale(big, full) or stale(img, full): need['small'].append(name)
        return name, True
    if stale(big, full): subprocess.run(['sips', '-s', 'format', 'jpeg', '-s', 'formatOptions', '60', full, '--out', big], capture_output=True); made.append('big/' + name + '.jpg')
    if stale(img, full): subprocess.run(['sips', '-Z', '915', '-s', 'format', 'jpeg', '-s', 'formatOptions', '68', full, '--out', img], capture_output=True); made.append('img/' + name + '.jpg')
    return name, os.path.exists(img) and os.path.exists(big)


pages = work + engine + folders
names = [s for p in pages for s in p['screens']]
if len(names) > 254: print('warning:', len(names), 'screens: two images each passes the 511 files an artifact version can hold')
with cf.ThreadPoolExecutor(4) as ex:
    res = list(ex.map(one, names))
bad = [n for n, ok in res if not ok]
for p in pages:
    p['screens'] = [{'name': s, 'thumb': 'img/' + s + '.jpg', 'big': 'big/' + s + '.jpg'} if s not in bad else {'name': s, 'thumb': None, 'big': None} for s in p['screens']]
os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
json.dump({'from': 'canvas', 'pages': pages}, open(OUT, 'w'))
if not LIST: json.dump(dict(sorted(SEEN.items())), open(HASHES, 'w'), indent=0)
mb = lambda d: sum(os.path.getsize(d + '/' + n + '.jpg') for n in names if os.path.exists(d + '/' + n + '.jpg')) / 1048576
print(len(names), 'screens (%d Engine);' % len(engNames), len(bad), 'without pictures', bad[:20], '; img/ %.1f MB, big/ %.1f MB;' % (mb(I), mb(G)), 'screens.json', os.path.getsize(OUT) // 1024, 'KB')
print('pictures made now:', len(made), sorted(made)[:60]) if not LIST else print('NEEDS ' + json.dumps(need))
if need['snap'] and not LIST: print('script-drawn boards that changed: take their DOM snapshots (atlas-snap server) into studio/snap/snaps/, run snaps_render.py studio/snap', ' '.join(need['snap']), ', then this again')
stray = sorted(set(f[:-4] for d in (I, G) for f in os.listdir(d) if f.endswith('.jpg')) - set(names))
if stray: print('pictures in img/ or big/ of screens not in the list (not published; archive them):', stray)
