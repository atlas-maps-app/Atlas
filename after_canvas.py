"""after_canvas.py — after a canvas change: what derives from the canvas, and what to publish.

    python3 after_canvas.py canvas/project [--engine engine/project] [--ranks <studio ranks.json>] [--out <dir>] [--apply]

 a. the three live counts the owner kept on their Start boards (screens, rated 4★+, at 5★) follow the canvas and the
    studio's ranks; nothing else on the Start boards is ever rebuilt (canvas/boards.py COUNTS)
 b. the studio's screen list (studio/thumbs.py --list): studio/screens.json and which pictures need making
 c. the files to publish, also in <out>/summary.txt and <out>/publish.json

Dry run by default into --out (default cache/state/after_canvas/<time>/); --apply writes the boards, canvas.json and
studio/screens.json in place. Nothing goes live here."""
import argparse, copy, datetime, json, os, re, shutil, subprocess, sys
A = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, A); sys.path.insert(0, os.path.join(A, 'canvas'))
import dbplan
import boards
MAIN_URL = 'https://claude.ai/artifact/Dapy8WL82jeyUhqPKPmA5L'


def jload(p):
    d = json.load(open(p))
    return d['data'] if isinstance(d, dict) and 'data' in d and isinstance(d['data'], dict) else d


def main(argv=None):
    ap = argparse.ArgumentParser(description='Rebuild what derives from the canvas; print what to publish.')
    ap.add_argument('main'); ap.add_argument('--engine'); ap.add_argument('--ranks')
    ap.add_argument('--out'); ap.add_argument('--apply', action='store_true')
    a = ap.parse_args(argv)
    now = datetime.datetime.now(); stamp = now.strftime('%Y%m%d-%H%M'); at = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    M = os.path.abspath(a.main); E = os.path.abspath(a.engine) if a.engine else None
    out = os.path.abspath(a.out or os.path.join(A, 'cache', 'state', 'after_canvas', stamp)); os.makedirs(out, exist_ok=True)
    board_out = M if a.apply else os.path.join(out, 'main')
    screens_out = os.path.join(A, 'studio', 'screens.json') if a.apply else os.path.join(out, 'studio', 'screens.json')
    S = []                                   # the summary
    def say(x=''): S.append(x)
    publish = {'main': {}, 'studio': {}, 'notes': []}

    c = boards.norm_pages(json.load(open(os.path.join(M, 'canvas.json')))); c0 = copy.deepcopy(c)   # a board with no page is on the first page
    ranks = jload(a.ranks) if a.ranks else None
    if isinstance(ranks, dict) and isinstance(ranks.get('ranks'), dict): ranks = ranks['ranks']
    say('after_canvas %s  %s  (%s)' % (now.strftime('%Y-%m-%d %H:%M'), M, 'APPLIED to local files' if a.apply else 'dry run: everything in ' + out))

    # ------------------------------------------------ a. the owner's Start boards: only their counts move
    say('\n== a. Start boards')
    r0 = boards.counts(c, M, ranks=ranks, out=board_out); nm = r0['numbers']
    say('the owner\'s, never rebuilt; counts: %d screens, %d rated 4★+, %d at 5★' % (nm['screens'], nm['good'], nm['five']))
    for x in r0['log']: say('  ' + x)
    for k in r0['written']: publish['main'][k] = os.path.join(board_out, k)
    if not r0['log']: say('  every count on them is current')
    if c != c0:
        os.makedirs(board_out, exist_ok=True)
        json.dump(c, open(os.path.join(board_out, 'canvas.json'), 'w'), ensure_ascii=False, indent=1)
        publish['main']['canvas.json'] = os.path.join(board_out, 'canvas.json')
        say('canvas.json: boards without a page put on the first page')

    # ------------------------------------------------ b. the studio's screen list and pictures
    say('\n== b. Studio screens and pictures')
    cur = os.path.join(A, 'studio', 'screens.json')
    cur_before = json.load(open(cur)) if os.path.exists(cur) else None      # read first: with --apply thumbs.py writes this very file
    cmd = [sys.executable, os.path.join(A, 'studio', 'thumbs.py'), '--canvas', M, '--out', screens_out, '--list'] + (['--engine', E] if E else [])
    t = subprocess.run(cmd, capture_output=True, text=True)
    need = None; stray = []
    for line in t.stdout.splitlines():
        if line.startswith('NEEDS '): need = json.loads(line[6:])
        elif line.startswith('pictures in img/ or big/'): stray = re.findall(r"'([^']+)'", line)
        elif line.strip(): say('  thumbs.py: ' + line.strip())
    if t.returncode or need is None:
        say('  ! thumbs.py failed: ' + (t.stderr.strip().splitlines() or ['?'])[-1]); need = {'full': [], 'snap': [], 'engine_full': [], 'small': [], 'missing': []}
    def names_of(d):
        return {s['name']: pg['id'] for pg in (d or {}).get('pages', []) for s in pg['screens']}
    now_d = json.load(open(screens_out)) if os.path.exists(screens_out) else None
    before, after = names_of(cur_before), names_of(now_d)
    added = sorted(set(after) - set(before)); gone = sorted(set(before) - set(after)); moved = sorted(n for n in set(after) & set(before) if after[n] != before[n])
    same = cur_before is not None and now_d is not None and cur_before == now_d
    say('screens.json: %d screens%s' % (len(after), ' (unchanged)' if same else '; new %s; gone %s; moved page %s' % (added or '-', gone or '-', moved or '-')))
    if not same: publish['studio']['screens.json'] = screens_out
    if need['full']: say('  render with Quick Look: python3 studio/thumbs.py%s   (%s)' % ((' --canvas ' + M) if M != os.path.join(A, 'canvas', 'project') else '', ', '.join(need['full'])))
    if need['snap']: say('  drawn by their own script, DOM snapshot first (atlas-snap server, studio/snap/snaps/), then python3 studio/snaps_render.py studio/snap %s' % ' '.join(need['snap']))
    if need['engine_full']: say('  Engine boards changed: their cache/pictures/engine/<Board>.jpg first (%s)' % ', '.join(need['engine_full']))
    if need['small']:
        say('  then img/ and big/ for %d: %s' % (len(need['small']), ', '.join(need['small'])))
        for n in need['small']: publish['studio']['img/%s.jpg' % n] = os.path.join(A, 'cache', 'pictures', 'img', n + '.jpg'); publish['studio']['big/%s.jpg' % n] = os.path.join(A, 'cache', 'pictures', 'big', n + '.jpg')
    if not (need['full'] or need['snap'] or need['engine_full'] or need['small']): say('  every picture is up to date')
    for n in gone: publish['studio']['img/%s.jpg' % n] = None; publish['studio']['big/%s.jpg' % n] = None
    if stray: say('  pictures of screens not in the list (archive them; null them in the studio publish if published): %s' % ', '.join(stray))

    # ------------------------------------------------ c. what to publish
    say('\n== c. To publish (Claude, after a look; nothing here is live yet)')
    mf = {k: v for k, v in publish['main'].items()}
    if mf:
        # root is the folder above project/, so file_path publishes as project/canvas.json (root = project/ would also leave a stray top-level canvas.json)
        root = os.path.dirname(os.path.abspath(M)); cj = os.path.join(os.path.abspath(M), 'canvas.json')
        say('Main canvas %s: read it first, then publish root=%s, file_path=%s (it publishes as project/canvas.json), files=' % (MAIN_URL, root, cj))
        if mf.get('canvas.json') and os.path.abspath(mf['canvas.json']) != cj: say('  (canvas.json changed in this dry run: --apply, or copy %s over %s first)' % (mf['canvas.json'], cj))
        for k, v in sorted(mf.items()): say('  %-30s %s' % ('project/' + k, 'null (remove)' if v is None else v))
    else: say('Main canvas: nothing changed')
    if publish['studio']:
        say('Studio %s: files=' % dbplan.URL['studio'])
        for k, v in sorted(publish['studio'].items()): say('  %-22s %s' % (k, 'null (remove)' if v is None else v))
        if need['full'] or need['snap'] or need['engine_full']: say('  (make the pictures listed in b. first)')
    else: say('Studio: nothing changed')
    for n in publish['notes']: say('Note: ' + n)
    txt = '\n'.join(S)
    open(os.path.join(out, 'summary.txt'), 'w').write(txt + '\n')
    json.dump(publish, open(os.path.join(out, 'publish.json'), 'w'), indent=1)
    print(txt)
    return publish


if __name__ == '__main__':
    main()
