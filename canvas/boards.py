"""The canvas as a structure: its pages, the screens on them, and the three live counts the owner kept on their Start boards."""
import json, os, re
HERE = os.path.dirname(os.path.abspath(__file__))


def norm_pages(c):
    """A board with no `page` sits on the canvas's first page (the app adds boards that way): make it explicit."""
    first = c['pages'][0]['id'] if c.get('pages') else None
    for b in c.get('boards', {}).values():
        if not b.get('page') and first: b['page'] = first
    return c


def kept_page(name):
    """Folder pages (other versions) and set-aside pages: not pages of screens."""
    return name.startswith('Folder') or name.startswith('Set aside')


def good_set(ranks): return {b for b, n in (ranks or {}).items() if isinstance(n, (int, float)) and n >= 4}


def screens_on(c):
    """Phone screens on the pages of the five tabs (412 wide; Car is wider), not on kept pages or Start."""
    NAME = {p['id']: p['name'] for p in c['pages']}
    return [k[:-8] for k, b in c['boards'].items() if (b['w'] == 412 or k == 'Car.dc.html') and b.get('page') != 'start'
            and not kept_page(NAME.get(b.get('page'), ''))]


def load_ranks(ranks):
    """The studio's ranks: a dict, or the path of its exported doc."""
    if isinstance(ranks, dict): return ranks
    if not ranks or not os.path.exists(ranks): return None
    d = json.load(open(ranks))
    d = d.get('data', d) if isinstance(d, dict) else d
    return d.get('ranks', d) if isinstance(d, dict) and 'ranks' in d and isinstance(d['ranks'], dict) else d


COUNTS = [   # (board, pattern, which number) on the owner's Start boards: only the number changes, never the words around it
    ('Brief', r'(>)(\d+)(</span><span[^>]*>screens on the canvas)', 'screens'),
    ('Brief', r'(>)(\d+)(</span><span[^>]*>rated 4)', 'good'),
    ('Index', r'(>)(\d+)( screens · )', 'screens'),
    ('Index', r'( screens · )(\d+)( </span>)', 'good'),
    ('TheBar', r'(>)(\d+)( rated 4)', 'good'),
    ('TheBar', r'( · )(\d+)( at 5)', 'five'),
]


def counts(c, P, ranks=None, out=None):
    """The Start boards are the owner's: nothing is rebuilt; only the live counts they kept follow the data.
    Returns {'written': [...], 'log': [...], 'numbers': {...}}."""
    norm_pages(c); R = load_ranks(ranks) or {}
    on = set(screens_on(c)); allb = {k[:-8] for k in c['boards']}
    nums = {'screens': len(on), 'good': len(good_set(R) & allb), 'five': len({b for b, n in R.items() if isinstance(n, (int, float)) and n >= 5} & allb)}
    res = {'written': [], 'log': [], 'numbers': nums}
    for board in sorted({b for b, _, _ in COUNTS}):
        f = os.path.join(P, board + '.dc.html')
        if not os.path.exists(f): continue
        h = h0 = open(f).read()
        for b, pat, k in COUNTS:
            if b != board: continue
            m = re.search(pat, h)
            if not m: continue
            if m.group(2) != str(nums[k]):
                res['log'].append('%s: %s %s → %d' % (board, k, m.group(2), nums[k]))
                h = h[:m.start(2)] + str(nums[k]) + h[m.end(2):]
        if h != h0:
            os.makedirs(out or P, exist_ok=True); open(os.path.join(out or P, board + '.dc.html'), 'w').write(h); res['written'].append(board + '.dc.html')
    return res
