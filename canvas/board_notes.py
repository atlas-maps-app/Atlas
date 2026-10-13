"""board_notes.py — text the owner typed onto (or removed from) canvas boards since the last download.

The owner writes notes straight onto boards (on Design, EveryDay, DeliveredHere, for example), not only as comments.
A fresh download compared with the local copy shows them.

  1. Artifact `list` with scope "files" on the Main canvas; every project/<Board>.dc.html whose size differs from
     canvas/project/<Board>.dc.html (or that is new) is read with Artifact `read`, `paths`, out_dir <dir>/canvas.
  2. python3 canvas/board_notes.py <dir>/canvas/project [--base canvas/project] [--out <dir>/board_notes.json]

Prints, per board, the visible text that is new and the text that is gone, and writes them as
[{board, added: [...], removed: [...]}] for inbox.py. Copy every note into the Plan word for word.
"""
import json, os, sys, glob
from collections import Counter
from html.parser import HTMLParser

A = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class Text(HTMLParser):
    SKIP = {'script', 'style', 'helmet', 'title'}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out, self.depth = [], 0

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.depth += 1

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.depth:
            self.depth -= 1

    def handle_data(self, data):
        s = ' '.join(data.split())
        if s and not self.depth:
            self.out.append(s)


def texts(path):
    p = Text()
    p.feed(open(path, encoding='utf-8').read())
    return Counter(p.out)


def main():
    argv, args, opt = sys.argv[1:], [], {}
    while argv:
        a = argv.pop(0)
        if a.startswith('--'):
            opt[a] = argv.pop(0) if argv else ''
        else:
            args.append(a)
    if not args:
        sys.exit(__doc__)
    fresh = args[0]
    base = opt.get('--base', os.path.join(A, 'canvas', 'project'))
    out = opt.get('--out', os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(fresh))), 'board_notes.json'))
    rows = []
    for f in sorted(glob.glob(os.path.join(fresh, '*.dc.html'))):
        board = os.path.basename(f)[:-len('.dc.html')]
        old = os.path.join(base, os.path.basename(f))
        now = texts(f)
        was = texts(old) if os.path.exists(old) else Counter()
        added = sorted((now - was).elements())
        removed = sorted((was - now).elements())
        if added or removed:
            rows.append({'board': board, 'new_board': not os.path.exists(old), 'added': added, 'removed': removed})
    for r in rows:
        print('%s%s' % (r['board'], ' (new board)' if r['new_board'] else ''))
        for s in r['added'][:20]:
            print('  + ' + s)
        for s in r['removed'][:20]:
            print('  - ' + s)
    json.dump(rows, open(out, 'w'), ensure_ascii=False, indent=1)
    print('%d boards with changed text → %s' % (len(rows), out))


if __name__ == '__main__':
    main()
