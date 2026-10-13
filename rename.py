"""rename.py — rename a screen everywhere its name is a key, at once.

    python3 rename.py <from> <to> [--export <dir>] [--main <dir>] [--engine <dir>] [--out <dir>] [--keep-links] [--apply]

--keep-links: another board is taking this name's place, so links to it stay as they are. A swap (a real version replacing
the drawn one, its stars staying with the drawn one): `rename.py Main MainDrawn --keep-links`, then `rename.py MainReal Main`.

--export is a fresh export of the studio's database (a folder with studio/, as cache/state/inbox/<date>/);
without it only the local files are looked at. --main / --engine are the canvas copies (default
canvas/project and engine/project).

Local files (changed with --apply; printed either way):
  the canvas that holds the board: <from>.dc.html → <to>.dc.html, its canvas.json entry and order, links to it in the
  other boards (href="<from>.dc.html", and #<from>.dc.html links from the other canvas); studio img/ big/ full/ pictures (or
  cache/pictures/engine/), studio/full_hashes.json, studio/screens.json and the DOM snapshot studio/snap/snaps/<from>.html.
Batch plans (written to --out, pinned to the export's versions; nothing is written live here):
  studio  ranks, dims, screennotes (keys), claude.screens (keys), rank lists, good (examples, share screens and held),
          requests (screen), sources (screens keys)
Text that only mentions the name (titles, notes, threads, to-dos, whose text is their key) is listed for a look by
hand, never changed; so is code that writes the board under its name (render/, canvas/, studio/ scripts). Default --out: cache/state/rename/<from>-<to>-<date-time>/."""
import argparse, datetime, json, os, re, sys, shutil
A = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, A)
import dbplan
URL_MAIN = 'https://claude.ai/artifact/Dapy8WL82jeyUhqPKPmA5L'; URL_ENGINE = 'https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR'
DEL = {'__delete__': True}


def word_re(n): return re.compile(r'(?<![A-Za-z0-9])%s(?![A-Za-z0-9])' % re.escape(n))


def dump_like(p, d):
    """Writes JSON in the style the file had (one line or indented, by how much, escaped or not), so a rename's diff is
    the rename only."""
    old = open(p).read() if os.path.exists(p) else ''
    lines = old.split('\n')
    if len(lines) > 2 and lines[1][:1] in (' ', '"', '{', '['):
        ind = len(lines[1]) - len(lines[1].lstrip(' '))
        kw = {'indent': ind}
    else: kw = {}
    asc = bool(re.search(r'\\u[0-9a-fA-F]{4}', old)) and not re.search(r'[^\x00-\x7f]', old)
    txt = json.dumps(d, ensure_ascii=asc, **kw)
    open(p, 'w').write(txt + ('\n' if old.endswith('\n') else ''))


def mentions(x, n, path=''):
    """Paths of strings inside x that mention n as a word (keys and exact values are handled elsewhere)."""
    out = []; rx = word_re(n)
    if isinstance(x, dict):
        for k, v in x.items(): out += mentions(v, n, path + '.' + k)
    elif isinstance(x, list):
        for i, v in enumerate(x): out += mentions(v, n, '%s[%d]' % (path, i))
    elif isinstance(x, str) and x != n and rx.search(x): out.append((path, x if len(x) < 140 else x[:137] + '…'))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description='Rename a screen everywhere its name is a key.')
    ap.add_argument('old'); ap.add_argument('new'); ap.add_argument('--export'); ap.add_argument('--main', default=os.path.join(A, 'canvas', 'project'))
    ap.add_argument('--engine', default=os.path.join(A, 'engine', 'project')); ap.add_argument('--out'); ap.add_argument('--apply', action='store_true'); ap.add_argument('--keep-links', action='store_true')
    a = ap.parse_args(argv)
    F, T = a.old, a.new
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9]*', T): sys.exit('the new name must be letters and digits, starting with a letter: %r' % T)
    if F == T: sys.exit('the same name')
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M')
    out = os.path.abspath(a.out or os.path.join(A, 'cache', 'state', 'rename', '%s-%s-%s' % (F, T, stamp)))
    L = []                                         # what changes, line by line
    look = []                                      # mentions for a look by hand
    ops = []                                       # local file operations: (kind, args)
    def say(x): L.append(x)

    # ------------------------------------------------ the canvases
    canv = {'Main': os.path.abspath(a.main), 'Engine': os.path.abspath(a.engine)}
    cj = {k: json.load(open(os.path.join(d, 'canvas.json'))) for k, d in canv.items() if os.path.exists(os.path.join(d, 'canvas.json'))}
    home = [k for k, c in cj.items() if F + '.dc.html' in c['boards']]
    clash = [k for k, c in cj.items() if T + '.dc.html' in c['boards']]
    if clash: sys.exit('%s already exists on %s' % (T, ', '.join(clash)))
    if not home: say('! %s is on neither canvas (%s); the stores are renamed anyway' % (F, ', '.join(canv.values())))
    for k in home:
        d = canv[k]; c = cj[k]
        c['boards'] = {(T + '.dc.html' if k_ == F + '.dc.html' else k_): v_ for k_, v_ in c['boards'].items()}   # in its place
        c['order'] = [T + '.dc.html' if x == F + '.dc.html' else x for x in c.get('order', [])]
        ops.append(('move', os.path.join(d, F + '.dc.html'), os.path.join(d, T + '.dc.html')))
        ops.append(('json', os.path.join(d, 'canvas.json'), c))
        say('%s canvas: %s.dc.html → %s.dc.html, canvas.json entry and order' % (k, F, T))
        for nid, n in c.get('notes', {}).items():
            if word_re(F).search(n.get('text', '')): look.append(('%s canvas note %s' % (k, nid), n['text'][:140]))
    for k, d in canv.items():
        if k not in cj or a.keep_links: continue          # --keep-links: the name's new holder keeps every link
        own = k in home; other_url = URL_MAIN if k == 'Engine' else URL_ENGINE
        rx_rel = re.compile(r'(href=")%s(\.dc\.html)' % re.escape(F)); rx_x = re.compile(r'(%s#)%s(\.dc\.html)' % (re.escape(URL_MAIN if k == 'Engine' else URL_ENGINE), re.escape(F)))
        for f in sorted(os.listdir(d)):
            if not f.endswith('.dc.html'): continue
            p = os.path.join(d, f); h = open(p).read(); h2 = h
            if own: h2 = rx_rel.sub(r'\g<1>%s\2' % T, h2)                     # links inside the canvas that holds it
            if any(x != k for x in home): h2 = rx_x.sub(r'\g<1>%s\2' % T, h2)  # links to the other canvas's copy
            if h2 != h:
                tgt = os.path.join(d, T + '.dc.html') if f == F + '.dc.html' else p
                ops.append(('text', tgt, h2, p)); say('%s canvas: links in %s (%d)' % (k, f, len(rx_rel.findall(h)) + len(rx_x.findall(h))))
    # ------------------------------------------------ local data files
    def jfile(p, fn, label):
        if not os.path.exists(p): return
        d = json.load(open(p)); d2 = fn(json.loads(json.dumps(d)))
        if d2 != d: ops.append(('json', p, d2)); say(label)
    def ren_keys(m):
        if isinstance(m, dict) and F in m: m = {(T if k == F else k): v for k, v in m.items()}
        return m
    jfile(os.path.join(A, 'studio', 'full_hashes.json'), ren_keys, 'studio/full_hashes.json')
    def scr(d):
        for p in d.get('pages', []):
            for s in p.get('screens', []):
                if s.get('name') == F:
                    s['name'] = T
                    for k in ('thumb', 'big'):
                        if s.get(k): s[k] = s[k].replace('/%s.jpg' % F, '/%s.jpg' % T)
        return d
    jfile(os.path.join(A, 'studio', 'screens.json'), scr, 'studio/screens.json')
    for sub in ('cache/pictures/img', 'cache/pictures/big', 'cache/pictures/full', 'cache/pictures/engine'):
        p = os.path.join(A, sub, F + '.jpg')
        if os.path.exists(p):
            if os.path.exists(os.path.join(A, sub, T + '.jpg')): sys.exit('%s/%s.jpg exists already' % (sub, T))
            ops.append(('move', p, os.path.join(A, sub, T + '.jpg'))); say('%s/%s.jpg → %s.jpg' % (sub, F, T))
    code = []
    for sub in ('render', 'canvas', 'studio', 'qa', '.'):
        dd = os.path.join(A, sub)
        for f in sorted(os.listdir(dd)) if os.path.isdir(dd) else []:
            if f.endswith('.py') and f != 'rename.py' and os.path.isfile(os.path.join(dd, f)):
                try: t = open(os.path.join(dd, f)).read()
                except UnicodeDecodeError: continue
                if re.search(r'(?<![A-Za-z0-9])%s\.dc\.html' % re.escape(F), t): code.append(os.path.normpath(os.path.join(sub, f)))
    if code: look.append(('code that names %s.dc.html' % F, ', '.join(code)))
    p = os.path.join(A, 'studio', 'snap', 'snaps', F + '.html')
    if os.path.exists(p): ops.append(('move', p, os.path.join(A, 'studio', 'snap', 'snaps', T + '.html'))); say('studio/snap/snaps/%s.html → %s.html' % (F, T))

    # ------------------------------------------------ the databases
    files = {}
    if a.export:
        st = dbplan.load(a.export, 'studio')
        if not st: say('! nothing found in the export %s' % a.export)
        # studio
        b = dbplan.Batches('studio'); sd = st.get('studio', {})
        for did in ('ranks', 'dims', 'screennotes'):
            if did in sd and isinstance(sd[did][0], dict) and F in sd[did][0]:
                if T in sd[did][0]: say('! studio/%s already has %s' % (did, T))
                b.update('studio', did, {F: DEL, T: sd[did][0][F]}, sd[did][1]); say('studio/%s: key' % did)
        if 'claude' in sd and isinstance(sd['claude'][0].get('screens'), dict) and F in sd['claude'][0]['screens']:
            b.update('studio', 'claude', {'screens': {F: DEL, T: sd['claude'][0]['screens'][F]}}, sd['claude'][1]); say('studio/claude: screens key')
        if 'rank' in sd:
            lists = sd['rank'][0].get('lists', {}); patch = {t: [T if x == F else x for x in l] for t, l in lists.items() if F in l}
            if patch: b.update('studio', 'rank', {'lists': patch}, sd['rank'][1]); say('studio/rank: lists %s' % ', '.join(patch))
        if 'good' in sd:
            g = sd['good'][0]; patch = {}
            ex = [dict(x, screen=T) if x.get('screen') == F else x for x in g.get('examples', [])]
            if ex != g.get('examples', []): patch['examples'] = ex
            sh = [dict(q, **{k: [T if s == F else s for s in q.get(k, [])] for k in ('screens', 'held') if k in q}) for q in g.get('share', [])]
            if sh != g.get('share', []): patch['share'] = sh
            if patch: b.update('studio', 'good', patch, sd['good'][1]); say('studio/good: %s' % ', '.join(patch))
        for did in ('notes', 'catrates'):
            if did in sd: look += [('studio/%s%s' % (did, p_), t) for p_, t in mentions(sd[did][0], F)]
        if 'screennotes' in sd: look += [('studio/screennotes%s' % p_, t) for p_, t in mentions(sd['screennotes'][0], F)]
        for rid, (r, v) in st.get('requests', {}).items():
            if r.get('screen') == F: b.update('requests', rid, {'screen': T}, v); say('studio requests/%s: screen' % rid)
            look += [('studio requests/%s%s' % (rid, p_), t) for p_, t in mentions({k: x for k, x in r.items() if k != 'screen'}, F)]
        for sid, (d, v) in sorted(st.get('sources', {}).items()):          # the Sources tab: a page's sources by screen
            if isinstance(d.get('screens'), dict) and F in d['screens']:
                b.update('sources', sid, {'screens': {F: DEL, T: d['screens'][F]}}, v); say('studio sources/%s: screens key' % sid)
        files['studio'] = b
    else:
        say('· no --export: the studio database is not looked at (no batch plans)')

    # ------------------------------------------------ do it, or say it
    print('rename %s → %s  (%s)' % (F, T, 'APPLIED to local files' if a.apply else 'dry run'))
    for x in L: print('  ' + x)
    if look:
        print('Mentions to look at by hand (not changed):')
        for w, t in look: print('  %s: %s' % (w, t))
    saved = []
    for db, b in files.items():
        if b.writes:
            saved += b.save(out, 'rename_%s_%s' % (F, T))
            if b.unpinned: print('  ! %s writes without a version (re-export): %s' % (db, ', '.join(b.unpinned)))
    if saved:
        print('Batch plans (ArtifactData batch, by Claude after a look):')
        for p in saved:
            db = re.search(r'_(studio)_\d+\.json$', p).group(1); print('  %s  %s' % (dbplan.URL[db], p))
    if a.apply:
        for op in ops:
            if op[0] == 'move' and os.path.exists(op[1]): shutil.move(op[1], op[2])
        for op in ops:
            if op[0] == 'json': dump_like(op[1], op[2])
            elif op[0] == 'text': open(op[1], 'w').write(op[2])
        print('Local files changed: %d. Then: after_canvas.py, and publish the canvas (files: %s.dc.html → null, %s.dc.html, canvas.json, the boards whose links changed).' % (len(ops), F, T))
    else:
        print('Nothing changed (dry run): --apply to change the local files.')
    return L, look, saved


if __name__ == '__main__':
    main()
