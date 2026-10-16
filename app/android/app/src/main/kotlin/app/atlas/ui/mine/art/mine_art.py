#!/usr/bin/env python3
"""mine_art.py: the objects of the mine package's boards, taken as they are drawn there.

    python3 mine_art.py            # writes app/src/main/res/raw/mine_art.json

Each entry names one inline SVG of a board (canvas/project/<Board>.dc.html) by its geometry hash (the generated boards
draw it as `art.<hash>`), and the tool converts it with the symbol catalogue's own converter (app/android/places/tools/board_symbols.py:
group transforms, gradients, caps, dashes, text), so the app draws exactly the board's paths, colours and gradients. The
output has the catalogue's resource format, one `{id, vb, p, t}` record per object. Python 3 standard library only.
"""
import hashlib
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isdir(os.path.join(ROOT, 'canvas')):
    ROOT = os.path.dirname(ROOT)
sys.path.insert(0, os.path.join(ROOT, 'app', 'android', 'places', 'tools'))
import board_symbols as B  # noqa: E402

OUT = os.path.join(ROOT, 'app', 'android', 'app', 'src', 'main', 'res', 'raw', 'mine_art.json')

# id: (board, the <svg>'s geometry hash, as the generated boards name their drawings art.<hash>). The screens draw the
# rest of the boards' icons from the board symbol set (places' board_symbols.json) and their live drawings themselves.
PICKS = {
    # Mine: the folded paper map's streets and river.
    'mine.paper': ('Mine', '5ea44a926307'),
}

GEOM = ('path', 'circle', 'rect', 'line', 'polyline', 'polygon', 'ellipse')


def svg_hash(svg):
    """The boards' geometry hash of an inline SVG (cache/look-ref/symbols/extract.py, boardgen.py)."""
    head = re.match(r'<svg\b[^>]*>', svg).group(0)
    vb = re.search(r'viewBox="([^"]+)"', head)
    vb = vb.group(1) if vb else None
    kids = re.findall(r'<(%s)\b([^>]*)/?>' % '|'.join(GEOM), svg)
    if not kids:
        return None
    geom = []
    for tag, attrs in kids:
        a = dict(re.findall(r'([\w:-]+)="([^"]*)"', attrs))
        keep = {k: v for k, v in a.items() if k in ('d', 'cx', 'cy', 'r', 'rx', 'ry', 'x', 'y', 'width', 'height', 'x1', 'y1', 'x2', 'y2', 'points', 'transform')}
        geom.append((tag, tuple(sorted(keep.items()))))
    return hashlib.sha1(json.dumps([vb, geom]).encode()).hexdigest()[:12]


def svgs(board):
    path = os.path.join(ROOT, 'canvas', 'project', board + '.dc.html')
    with open(path, encoding='utf-8') as f:
        s = f.read()
    return [m.group(0) for m in re.finditer(r'<svg\b[^>]*>.*?</svg>', s, flags=re.S)]


def main():
    cache = {}
    records = []
    for sid, (board, h) in PICKS.items():
        found = [svg for svg in cache.setdefault(board, svgs(board)) if svg_hash(svg) == h]
        if not found:
            sys.exit('%s: no svg with hash %s' % (board, h))
        svg = found[0]
        conv = B.Converter(svg).run()
        paths = [p if p[2] else p[:2] for p in conv.paths]
        rec = {'id': sid, 'vb': ' '.join(B.fmt(v) for v in conv.vb), 'p': paths}
        if conv.texts:
            rec['t'] = conv.texts
        records.append(rec)
        print('%-18s %-8s %s %2d paths' % (sid, board, h, len(paths)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'source': 'Atlas boards (canvas/project) via places/tools/board_symbols.py; MIT', 'art': records}, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    print('wrote', os.path.relpath(OUT, ROOT), os.path.getsize(OUT), 'bytes')


if __name__ == '__main__':
    main()
