"""Build headless test harnesses for a page that reads its data through window.claude.use('db').

usage: python3 mkharness.py PAGE.html EXPORT_DIR OUT_DIR [--db studio] [--variants FILE.json]
                            [--pics STUDIO_DIR] [--pic-pages pid,pid] [--pic-big Board,Board]

EXPORT_DIR holds one folder per collection, one JSON file per doc (an '@' in a file name stands for '~').
A file is the doc itself or {"data": doc, "version": n}; the harness unwraps data. When EXPORT_DIR holds
one folder per database (studio/), --db picks one (default: studio).

Each harness inlines the data as window.__DATA and mocks the store: reads come from __DATA, writes change it
and are recorded in window.__writes ([op, path, payload]); window.__errors collects errors.
Written to OUT_DIR:
  edit.html (can edit), ro.html (view only), nostore.html (localStorage throws),
  noclaude.html (no window.claude), nodb.html (no db), failread.html (every read fails),
  plus every variant in --variants: [{"name": "x.html", "canedit": true, "hash": "log", "storage": {...}, "nostore": false}]
  and, with --pics, shots.html / shots_ro.html, where /_blob/<id> shows the studio's own copy of a board's picture
  (img/ for the boards of --pic-pages, big/ for --pic-big) so screenshots have pictures.
"""
import argparse, base64, glob, json, os, re, sys

MOCK = r'''
window.__errors = [];
window.addEventListener('error', e => window.__errors.push(String(e.message)));
const __ce = console.error; console.error = (...a) => { window.__errors.push(a.map(String).join(' ')); __ce(...a); };
window.requestAnimationFrame = cb => setTimeout(() => cb(performance.now()), 16);
window.__writes = [];
(function(){
  const subs = {};
  const __freeze = o => { if (o && typeof o === 'object') { Object.values(o).forEach(__freeze); Object.freeze(o); } return o; };
  const snap = name => ({ docs: Object.entries(window.__DATA[name] || {}).map(([id, o]) => ({ id, data: () => __freeze(JSON.parse(JSON.stringify(o))) })) });
  const fire = name => setTimeout(() => (subs[name] || []).forEach(cb => cb(snap(name))), 0);
  window.__fire = fire;
  const db = {
    collection: name => ({ onSnapshot: (cb, err) => { (subs[name] = subs[name] || []).push(cb); setTimeout(() => cb(snap(name)), 0); return () => {}; } }),
    doc: path => { const [c, id] = path.split('/'); return {
      update: async d => { if (window.__FAILWRITE) throw new Error('denied'); window.__writes.push(['update', path, JSON.parse(JSON.stringify(d))]); window.__DATA[c] = window.__DATA[c] || {}; window.__DATA[c][id] = Object.assign({}, window.__DATA[c][id] || {}, d); fire(c); },
      set: async d => { if (window.__FAILWRITE) throw new Error('denied'); window.__writes.push(['set', path, JSON.parse(JSON.stringify(d))]); window.__DATA[c] = window.__DATA[c] || {}; window.__DATA[c][id] = d; fire(c); },
      delete: async () => { if (window.__FAILWRITE) throw new Error('denied'); window.__writes.push(['delete', path]); delete (window.__DATA[c] || {})[id]; fire(c); } }; }
  };
  window.claude = { use: async n => n === 'db' ? db : n === 'user' ? { canEdit: async () => window.__CANEDIT !== false } : n === 'assets' ? { upload: async f => { window.__writes.push(['upload', f && f.name]); return { id: 'mockasset' }; } } : null };
})();
'''
# a picture whose /_blob/<id> the harness knows is shown from a data URL (shots only)
IMGMAP = r'''
(function(){
  const d = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
  Object.defineProperty(HTMLImageElement.prototype, 'src', { configurable: true, get() { return d.get.call(this); },
    set(v) { const m = /\/_blob\/([A-Za-z0-9_-]+)$/.exec(String(v)); d.set.call(this, m && window.__IMG && window.__IMG[m[1]] ? window.__IMG[m[1]] : v); } });
})();
'''
HEAD = '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>*,*::before,*::after{transition:none!important}</style><script>'


def unwrap(d):
    if isinstance(d, dict) and 'data' in d and set(d) <= {'data', 'version'} and (isinstance(d['data'], dict) or 'version' in d):
        return d['data']
    return d


def has_collections(p):
    return any(glob.glob(os.path.join(p, c, '*.json')) for c in os.listdir(p) if os.path.isdir(os.path.join(p, c)))


def load_export(base, db='studio'):
    sub = os.path.join(base, db)
    if os.path.isdir(sub) and has_collections(sub):
        base = sub
    elif not has_collections(base):
        sys.exit('no collections in %s' % base)
    data = {}
    for c in sorted(os.listdir(base)):
        p = os.path.join(base, c)
        if not os.path.isdir(p):
            continue
        data[c] = {os.path.basename(f)[:-5].replace('@', '~'): unwrap(json.load(open(f))) for f in sorted(glob.glob(p + '/*.json'))}
    return data


def page_body(path):
    page = open(path).read()
    return re.sub(r'<link[^>]*fonts[^>]*>\n?', '', page)


def build(page, data, out, canedit=True, nostore=False, hashv='', storage=None, imgs=None):
    pre = 'window.__DATA = ' + json.dumps(data, ensure_ascii=False) + ';\nwindow.__CANEDIT = ' + ('true' if canedit else 'false') + ';\n'
    if storage is not None:
        pre += 'try { localStorage.clear(); ' + ''.join('localStorage.setItem(%s, %s);' % (json.dumps(k), json.dumps(v)) for k, v in storage.items()) + ' } catch (e) {}\n'
    if nostore:
        pre += "Object.defineProperty(window, 'localStorage', { configurable: true, get() { throw new Error('blocked'); } });\n"
    if hashv:
        pre += "history.replaceState(null, '', '#%s');\n" % hashv
    if imgs is not None:
        pre += 'window.__IMG = ' + json.dumps(imgs) + ';\n' + IMGMAP
    open(out, 'w').write(HEAD + pre + MOCK + '</script></head><body>\n' + page + '\n</body></html>')


def special(page, out, mock):
    html = '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><script>window.__errors=[];window.addEventListener("error", e => window.__errors.push(String(e.message)));window.requestAnimationFrame = cb => setTimeout(() => cb(performance.now()), 16);' + mock + '</script></head><body>\n' + page + '\n</body></html>'
    open(out, 'w').write(html)


def pictures(data, studio, pages, big):
    want = set()
    for pid in pages:
        for b in ((data.get('pages', {}).get(pid) or {}).get('boards') or []):
            want.add(b if isinstance(b, str) else (b or {}).get('id'))
    mb = ((data.get('media', {}).get('boards') or {}).get('boards')) or {}
    imgs = {}
    for bid in want | set(big):
        m = mb.get(bid)
        if not isinstance(m, dict):
            continue
        for kind in ('img', 'big'):
            if kind == 'big' and bid not in big:
                continue
            f = os.path.join(studio, kind, bid + '.jpg')
            if m.get(kind) and os.path.exists(f):
                imgs[m[kind]] = 'data:image/jpeg;base64,' + base64.b64encode(open(f, 'rb').read()).decode()
    return imgs


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('page'); ap.add_argument('export'); ap.add_argument('out')
    ap.add_argument('--db', default='studio')
    ap.add_argument('--variants')
    ap.add_argument('--pics'); ap.add_argument('--pic-pages', default=''); ap.add_argument('--pic-big', default='')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    data = load_export(a.export, a.db)
    page = page_body(a.page)
    o = lambda n: os.path.join(a.out, n)
    build(page, data, o('edit.html'))
    build(page, data, o('ro.html'), canedit=False)
    build(page, data, o('nostore.html'), nostore=True)
    special(page, o('noclaude.html'), '')
    special(page, o('nodb.html'), 'window.claude = { use: async n => null };')
    special(page, o('failread.html'), 'window.claude = { use: async n => n === "db" ? { collection: () => ({ onSnapshot: (cb, err) => { setTimeout(() => err(new Error("x")), 0); return () => {}; } }), doc: () => ({}) } : n === "user" ? { canEdit: async () => true } : null };')
    names = ['edit', 'ro', 'nostore', 'noclaude', 'nodb', 'failread']
    for v in (json.load(open(a.variants)) if a.variants else []):
        build(page, data, o(v['name']), canedit=v.get('canedit', True), nostore=v.get('nostore', False), hashv=v.get('hash', ''), storage=v.get('storage'))
        names.append(v['name'][:-5])
    if a.pics:
        imgs = pictures(data, a.pics, [p for p in a.pic_pages.split(',') if p], [b for b in a.pic_big.split(',') if b])
        build(page, data, o('shots.html'), imgs=imgs)
        build(page, data, o('shots_ro.html'), canedit=False, imgs=imgs)
        names += ['shots', 'shots_ro']
        print('pictures:', len(imgs))
    print('harnesses:', ' '.join(names), '| page', len(page), '| collections:', ', '.join('%s:%d' % (k, len(v)) for k, v in data.items()))


if __name__ == '__main__':
    main()
