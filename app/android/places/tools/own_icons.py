"""Atlas's own drawings (custom_icons.txt), all in one style. build_places_data.py draws the new icons and the open sets'
icons drawn anew; board_symbols.py draws the board symbols drawn anew. Python 3 standard library only.

An id on a line says what its drawing is for:
  a board symbol's id, with a dot (mode.bike): board_symbols.py draws this instead of the board's drawing
  an open set's id (maki-racetrack-cycling, ui-camera): build_places_data.py draws this instead of the set's
  a bare name (cycle-rickshaw): a new icon, ui-<name> in ui.json
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(HERE, 'custom_icons.txt')
SETS = ('maki-', 'temaki-', 'pinhead-', 'ui-')


def drawings():
    """{id: SVG elements} for every id of every line, ids as they are written."""
    out = {}
    for n, line in enumerate(open(PATH, encoding='utf-8'), 1):
        if line.startswith('#') or not line.strip():
            continue
        ids, body = line.rstrip('\n').split('\t')
        for i in ids.split():
            key = icon_id(i)
            if any(icon_id(k) == key for k in out):
                raise SystemExit('custom_icons.txt:%d: %s is drawn twice' % (n, key))
            out[i] = body
    return out


def is_board(i):
    return '.' in i


def icon_id(i):
    """The id the drawing goes by in its resource: a board id or a set's id as written, a bare name as ui-<name>."""
    return i if is_board(i) or i.startswith(SETS) else 'ui-' + i


def svg(body):
    """The SVG document of one drawing: a 24 grid, 2-unit round strokes, no fill unless an element asks for one."""
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">%s</svg>' % body)


def board_drawings():
    """{board id: SVG document} for the board symbols Atlas draws anew."""
    return {i: svg(b) for i, b in drawings().items() if is_board(i)}


def set_drawings():
    """{icon id: SVG document} for the new icons and the open sets' icons Atlas draws anew."""
    return {icon_id(i): svg(b) for i, b in drawings().items() if not is_board(i)}
