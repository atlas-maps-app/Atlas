"""Build headless test harnesses for the studio (studio/studio.html) from an export of its database.

usage: python3 mkstudio.py [PAGE] [EXPORT] [OUT] [--screens FILE] [--pics] [--big Name,Name] [--tag NAME]

PAGE     the studio page (default: studio/studio.html)
EXPORT   an export folder holding studio/ (or the studio database's collections directly; default: qa/pagetest/export)
OUT      where the harnesses go (default: $TMPDIR/atlas_studio_test/<tag>)

The studio reads its store through window.claude.use('db') with doc().onSnapshot, collection().orderBy().onSnapshot,
collection().add and doc().get, which the Plan's mock (../mkharness.py) does not have; this mock does (it began as
_from_r29/studio_test/mock.js). The data is inlined as window.__DATA ({collection: {id: doc}}); every write is recorded
in window.__writes ([op, 'collection/id', payload]); window.__errors collects errors; window.__FAILWRITE = true makes
writes fail. fetch('screens.json') answers with the studio's screens.json; with --pics its pictures are small data URLs
(and --big ones full size) so screenshots show them. The page's script gets one line at its end that exposes
window.__studio = {promptFor, cfg(), good(), goodText, wordsFor, starMap()} for the tests (old and new pages alike).

Written to OUT: edit.html, nodb.html (no store: read only), failwrite.html, screen.html (#screen=MarketHall),
engine.html (#screen=LivingLooks), screen404.html (#screen=NoSuchBoard), tab_words.html … tab_inbox.html (#<tab>),
empty.html (an empty store) and claude.html (the export plus Claude's ratings in studio/claude and Looks · Use in studio/dims).
"""
import argparse, base64, json, os, re, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
ATLAS = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
sys.path.insert(0, os.path.join(HERE, '..'))
from mkharness import load_export, page_body  # noqa: E402

MOCK = r'''
window.__errors = [];
window.addEventListener('error', e => window.__errors.push(String(e.message)));
window.addEventListener('unhandledrejection', e => window.__errors.push('unhandled: ' + String(e.reason && e.reason.message || e.reason)));
const __ce = console.error; console.error = (...a) => { window.__errors.push(a.map(String).join(' ')); __ce(...a); };
window.__warns = []; const __cw = console.warn; console.warn = (...a) => { window.__warns.push(a.map(String).join(' ')); __cw(...a); };
window.__writes = [];
(function(){
  const D = window.__DATA, subs = {};
  const cp = o => o === undefined ? undefined : JSON.parse(JSON.stringify(o));
  const split = p => { const i = p.indexOf('/'); return [p.slice(0, i), p.slice(i + 1)] };
  const get = p => { const [c, id] = split(p); return (D[c] || {})[id] };
  const put = (p, v) => { const [c, id] = split(p); D[c] = D[c] || {}; if (v === undefined) delete D[c][id]; else D[c][id] = v };
  const snapDoc = p => { const d = get(p); return { id: split(p)[1], exists: d !== undefined, data: () => cp(d), metadata: {} } };
  const fire = p => { const [c] = split(p); setTimeout(() => { (subs[p] || []).forEach(f => f()); (subs[c] || []).forEach(f => f()) }, 0) };
  const fail = () => { if (window.__FAILWRITE) throw Object.assign(new Error('denied'), { code: 'denied' }) };
  function docRef(p) { return { path: p, id: split(p)[1],
    get: async () => snapDoc(p),
    set: async v => { fail(); window.__writes.push(['set', p, cp(v)]); put(p, cp(v)); fire(p) },
    update: async v => { fail(); if (get(p) === undefined) throw Object.assign(new Error('not found'), { code: 'not-found' }); window.__writes.push(['update', p, cp(v)]); put(p, Object.assign(cp(get(p)), cp(v))); fire(p) },
    delete: async () => { fail(); window.__writes.push(['delete', p]); put(p, undefined); fire(p) },
    onSnapshot: (next, err) => { (subs[p] = subs[p] || []).push(() => next(snapDoc(p))); setTimeout(() => next(snapDoc(p)), 0); return () => {} } } }
  function coll(c) { let ord = null; const q = {
    orderBy: (k, dir) => { ord = [k, dir]; return q }, limit: () => q, where: () => q,
    onSnapshot: (next, err) => { const run = () => { let ids = Object.keys(D[c] || {});
        if (ord) ids.sort((a, b) => { const x = String((D[c][a] || {})[ord[0]] || ''), y = String((D[c][b] || {})[ord[0]] || ''); return ord[1] === 'desc' ? (x < y ? 1 : x > y ? -1 : 0) : (x < y ? -1 : x > y ? 1 : 0) });
        next({ docs: ids.map(id => snapDoc(c + '/' + id)), size: ids.length, empty: !ids.length, docChanges: () => [] }) };
      (subs[c] = subs[c] || []).push(run); setTimeout(run, 0); return () => {} },
    add: async v => { fail(); const id = 'x' + Math.random().toString(36).slice(2, 12); window.__writes.push(['add', c + '/' + id, cp(v)]); put(c + '/' + id, cp(v)); fire(c + '/' + id); return docRef(c + '/' + id) },
    doc: id => docRef(c + '/' + id) }; return q }
  window.__db = { doc: docRef, collection: coll };
  window.claude = { use: async n => n === 'db' ? window.__db : null };
})();
'''
FETCH = r'''
(function(){ const f = window.fetch;
  window.fetch = async (u, o) => String(u).replace(/^\.?\//, '') === 'screens.json'
    ? { ok: true, status: 200, json: async () => JSON.parse(JSON.stringify(window.__SCREENS)), text: async () => JSON.stringify(window.__SCREENS) }
    : f ? f(u, o) : Promise.reject(new Error('no fetch')); })();
'''
HOOK = '\n  window.__studio = { promptFor: typeof promptFor === "function" ? promptFor : null, wordsFor: typeof wordsFor === "function" ? wordsFor : null, cfg: () => cfg, good: () => good, starMap: () => starMap, goodText: typeof goodText === "function" ? goodText : null };\n'
HEAD = '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>*,*::before,*::after{transition:none!important;animation:none!important}</style><script>'


def hook(page):
    i = page.rfind('})();')
    if i < 0:
        sys.exit('no closing })(); in the page script')
    return page[:i] + HOOK + page[i:]


def small_pics(studio, names, cache, width=150):
    os.makedirs(cache, exist_ok=True)
    out = {}
    for n in names:
        src = os.path.join(studio, 'img', n + '.jpg')
        if not os.path.exists(src):
            continue
        dst = os.path.join(cache, n + '.jpg')
        if not os.path.exists(dst) or os.path.getmtime(dst) < os.path.getmtime(src):
            subprocess.run(['sips', '-Z', str(int(width * 915 / 412)), '-s', 'formatOptions', '55', src, '--out', dst], capture_output=True)
        if os.path.exists(dst):
            out[n] = 'data:image/jpeg;base64,' + base64.b64encode(open(dst, 'rb').read()).decode()
    return out


def build(page, data, screens, out, hashv='', nodb=False):
    pre = 'window.__DATA = ' + json.dumps(data, ensure_ascii=False) + ';\nwindow.__SCREENS = ' + json.dumps(screens, ensure_ascii=False) + ';\n'
    pre += 'try { localStorage.clear() } catch (e) {}\n'
    if hashv:
        pre += "history.replaceState(null, '', '#%s');\n" % hashv
    mock = MOCK + ('window.claude = { use: async n => null };\n' if nodb else '')
    open(out, 'w').write(HEAD + pre + mock + FETCH + '</script></head><body>\n' + page + '\n</body></html>')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('page', nargs='?', default=os.path.join(ATLAS, 'studio', 'studio.html'))
    ap.add_argument('export', nargs='?', default=os.path.join(ATLAS, 'qa', 'pagetest', 'export'))
    ap.add_argument('out', nargs='?')
    ap.add_argument('--screens', default=os.path.join(ATLAS, 'studio', 'screens.json'))
    ap.add_argument('--pics', action='store_true', help='inline small pictures of every screen (for screenshots)')
    ap.add_argument('--big', default='MarketHall,LivingLooks', help='screens whose big picture is inlined too (with --pics)')
    ap.add_argument('--tag', default='new')
    a = ap.parse_args()
    out = a.out or os.path.join(os.environ.get('TMPDIR', '/tmp').rstrip('/'), 'atlas_studio_test', a.tag)
    os.makedirs(out, exist_ok=True)
    data = load_export(a.export, 'studio')
    screens = json.load(open(a.screens))
    if a.pics:
        studio = os.path.dirname(os.path.abspath(a.screens))
        names = [s['name'] for p in screens['pages'] for s in p['screens']]
        pics = small_pics(studio, names, os.path.join(os.environ.get('TMPDIR', '/tmp').rstrip('/'), 'atlas_studio_test', '_pics'))
        big = set(x for x in a.big.split(',') if x)
        for p in screens['pages']:
            for s in p['screens']:
                if s.get('thumb') and s['name'] in pics:
                    s['thumb'] = pics[s['name']]
                if s.get('big'):
                    f = os.path.join(studio, 'big', s['name'] + '.jpg')
                    s['big'] = ('data:image/jpeg;base64,' + base64.b64encode(open(f, 'rb').read()).decode()) if s['name'] in big and os.path.exists(f) else s.get('thumb')
    page = hook(page_body(a.page))
    o = lambda n: os.path.join(out, n)
    build(page, data, screens, o('edit.html'))
    build(page, data, screens, o('nodb.html'), nodb=True)
    fw = page.replace('<script>', '<script>window.__FAILWRITE = true;', 1)
    build(fw, data, screens, o('failwrite.html'))
    build(page, data, screens, o('screen.html'), hashv='screen=MarketHall')
    build(page, data, screens, o('engine.html'), hashv='screen=LivingLooks')
    build(page, data, screens, o('screen404.html'), hashv='screen=NoSuchBoard')
    for t in ['words', 'prompt', 'goodpage', 'screens', 'sourcespage', 'inbox']:
        build(page, data, screens, o('tab_' + t + '.html'), hashv=t)
    build(page, {}, screens, o('empty.html'))                     # an empty store: no config, no stars, no requests
    cl = json.loads(json.dumps(data)); st = cl.setdefault('studio', {})   # Claude's ratings and Looks · Use (neither in the live store on 8 Oct)
    st['claude'] = {'screens': {'Main': {'stars': 3.5, 'look': 4, 'use': 3, 'well': 'clear', 'badly': 'busy', 'idea': 'Fewer chips', 'at': '2026-10-08T10:00:00Z'},
                                'LivingLooks': {'stars': 4.5, 'at': '2026-10-08T10:00:00Z'}, 'Search': {'well': 'only well', 'at': '2026-10-08T10:00:00Z'}},
                    'cats': {'map_home': {'value': 4, 'at': '2026-10-08T10:00:00Z'}}}
    st['dims'] = {'Main': {'look': 4.5, 'use': 2.5}, 'Search': {'look': 3, 'use': 4.5}, 'Place': {'look': 4, 'use': 4}, 'LivingLooks': {'look': 5, 'use': 2}}
    build(page, cl, screens, o('claude.html'))
    print('harnesses in', out, '| collections:', ', '.join('%s:%d' % (k, len(v)) for k, v in data.items()), '| screens:', sum(len(p['screens']) for p in screens['pages']))


if __name__ == '__main__':
    main()
