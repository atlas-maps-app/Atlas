#!/usr/bin/env python3
"""mine_art.py: the objects of the mine package's boards, taken as they are drawn there.

    python3 mine_art.py            # writes app/src/main/res/raw/mine_art.json

Each entry names one inline SVG of a board (canvas/project/<Board>.dc.html) by its position among that board's `<svg>`
elements, and the tool converts it with the symbol catalogue's own converter (app/android/places/tools/board_symbols.py:
group transforms, gradients, caps, dashes, text), so the app draws exactly the board's paths, colours and gradients. The
output has the catalogue's resource format, one `{id, vb, p, t}` record per object. Python 3 standard library only.
"""
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

# id: (board, index of the <svg> among the board's svgs)
PICKS = {
    # Charge: the car's ports in the header bar, and the connector faces on the station card.
    'plug.ccs2.chip': ('Charge', 2),
    'plug.type2.chip': ('Charge', 3),
    'plug.ccs2.face': ('Charge', 13),
    'plug.type2.face': ('Charge', 14),
    # Plugs: the connector tiles (outline faces, drawn in the tint).
    'plug.ccs2.line': ('Plugs', 5),
    'plug.type2.line': ('Plugs', 6),
    'plug.chademo.line': ('Plugs', 7),
    'plug.schuko.line': ('Plugs', 8),
    # Mine: the passport's globe, the paper map's streets, the backtrack ticket's trail.
    'mine.globe': ('Mine', 1),
    'mine.paper': ('Mine', 2),
    'mine.trail': ('Mine', 6),
    # Privacy: the shield of the banner.
    'privacy.shield': ('Privacy', 1),
}


def svgs(board):
    path = os.path.join(ROOT, 'canvas', 'project', board + '.dc.html')
    with open(path, encoding='utf-8') as f:
        s = f.read()
    return [m.group(0) for m in re.finditer(r'<svg\b[^>]*>.*?</svg>', s, flags=re.S)]


def main():
    cache = {}
    records = []
    for sid, (board, index) in PICKS.items():
        svg = cache.setdefault(board, svgs(board))[index]
        conv = B.Converter(svg).run()
        _, _, hue, _ = B.canonical({'svg': svg})
        paths = [p if p[2] else p[:2] for p in conv.paths]
        rec = {'id': sid, 'vb': ' '.join(B.fmt(v) for v in conv.vb), 'p': paths}
        if conv.texts:
            rec['t'] = conv.texts
        records.append(rec)
        print('%-18s %-8s #%-2d %2d paths' % (sid, board, index, len(paths)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump({'source': 'Atlas boards (canvas/project) via places/tools/board_symbols.py; MIT', 'art': records}, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    print('wrote', os.path.relpath(OUT, ROOT), os.path.getsize(OUT), 'bytes')


if __name__ == '__main__':
    main()
