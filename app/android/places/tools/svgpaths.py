"""SVG icon files to normalised path strings, with the Python standard library only.

Every shape (path, circle, ellipse, rect, line, polyline, polygon) becomes path data; numbers are rounded and the
commands re-serialised compactly, so the Kotlin SvgPath parser sees one tidy grammar. Each output path carries flags:
'f' filled, 's' stroked, 'e' even-odd fill rule.
"""
import re
import xml.etree.ElementTree as ET

ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}
NUM = re.compile(r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')


def parse_path(d):
    """Path data to [(command letter, [numbers])], one entry per argument group (implicit repeats expanded)."""
    out = []
    i, n = 0, len(d)
    cmd = None

    def skip(i):
        while i < n and d[i] in ' \t\r\n,':
            i += 1
        return i

    def number(i):
        m = NUM.match(d, i)
        if not m:
            raise ValueError('number expected at %d in %r' % (i, d[:80]))
        return float(m.group(0)), m.end()

    def flag(i):
        if i < n and d[i] in '01':
            return float(d[i]), i + 1
        raise ValueError('arc flag expected at %d in %r' % (i, d[:80]))

    i = skip(i)
    while i < n:
        c = d[i]
        if c.isalpha():
            if c.upper() not in ARGS:
                raise ValueError('unknown command %r' % c)
            cmd = c
            i = skip(i + 1)
            if cmd in 'Zz':
                out.append((cmd, []))
                continue
        elif cmd is None or cmd in 'Zz':
            raise ValueError('data before a command in %r' % d[:80])
        k = ARGS[cmd.upper()]
        args = []
        for j in range(k):
            if cmd in 'Aa' and j in (3, 4):
                v, i = flag(i)
            else:
                v, i = number(i)
            args.append(v)
            i = skip(i)
        out.append((cmd, args))
        if cmd == 'M':
            cmd = 'L'
        elif cmd == 'm':
            cmd = 'l'
        if i < n and d[i].isalpha():
            continue
    return out


def fmt(x, places=3):
    s = ('%.' + str(places) + 'f') % x
    if '.' in s:
        s = s.rstrip('0').rstrip('.')
    if s in ('-0', ''):
        s = '0'
    if s.startswith('0.'):
        s = s[1:]
    elif s.startswith('-0.'):
        s = '-' + s[2:]
    return s


def format_path(segs, places=3):
    parts = []
    for cmd, args in segs:
        parts.append(cmd)
        prev = None
        for j, a in enumerate(args):
            if cmd in 'Aa' and j in (3, 4):
                s = '1' if a else '0'
            else:
                s = fmt(a, places)
            if prev is not None and not s.startswith('-'):
                parts.append(' ')
            parts.append(s)
            prev = s
    return ''.join(parts)


def num(v, default=0.0):
    if v is None:
        return default
    m = NUM.match(v.strip())
    return float(m.group(0)) if m else default


def shape_to_path(el):
    tag = el.tag.split('}')[-1]
    a = el.attrib
    if tag == 'path':
        return a.get('d', '')
    if tag == 'circle':
        cx, cy, r = num(a.get('cx')), num(a.get('cy')), num(a.get('r'))
        return 'M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 %s 0z' % (fmt(cx - r), fmt(cy), fmt(r), fmt(r), fmt(2 * r), fmt(r), fmt(r), fmt(-2 * r))
    if tag == 'ellipse':
        cx, cy, rx, ry = num(a.get('cx')), num(a.get('cy')), num(a.get('rx')), num(a.get('ry'))
        return 'M%s %sa%s %s 0 1 0 %s 0a%s %s 0 1 0 %s 0z' % (fmt(cx - rx), fmt(cy), fmt(rx), fmt(ry), fmt(2 * rx), fmt(rx), fmt(ry), fmt(-2 * rx))
    if tag == 'rect':
        x, y, w, h = num(a.get('x')), num(a.get('y')), num(a.get('width')), num(a.get('height'))
        rx = a.get('rx'); ry = a.get('ry')
        rx = num(rx) if rx is not None else (num(ry) if ry is not None else 0.0)
        ry = num(ry) if ry is not None else rx
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx <= 0 or ry <= 0:
            return 'M%s %sh%sv%sh%sz' % (fmt(x), fmt(y), fmt(w), fmt(h), fmt(-w))
        return ('M%s %sh%sa%s %s 0 0 1 %s %sv%sa%s %s 0 0 1 %s %sh%sa%s %s 0 0 1 %s %sv%sa%s %s 0 0 1 %s %sz' % (
            fmt(x + rx), fmt(y), fmt(w - 2 * rx), fmt(rx), fmt(ry), fmt(rx), fmt(ry), fmt(h - 2 * ry),
            fmt(rx), fmt(ry), fmt(-rx), fmt(ry), fmt(-(w - 2 * rx)), fmt(rx), fmt(ry), fmt(-rx), fmt(-ry),
            fmt(-(h - 2 * ry)), fmt(rx), fmt(ry), fmt(rx), fmt(-ry)))
    if tag == 'line':
        return 'M%s %sL%s %s' % (fmt(num(a.get('x1'))), fmt(num(a.get('y1'))), fmt(num(a.get('x2'))), fmt(num(a.get('y2'))))
    if tag in ('polyline', 'polygon'):
        pts = [float(v) for v in NUM.findall(a.get('points', ''))]
        if len(pts) < 4:
            return ''
        s = 'M%s %s' % (fmt(pts[0]), fmt(pts[1])) + ''.join('L%s %s' % (fmt(pts[k]), fmt(pts[k + 1])) for k in range(2, len(pts) - 1, 2))
        return s + ('z' if tag == 'polygon' else '')
    return None


def style_of(el, inherited):
    st = dict(inherited)
    for k in ('fill', 'stroke', 'fill-rule', 'opacity', 'fill-opacity', 'display', 'transform'):
        if k in el.attrib:
            st[k] = el.attrib[k].strip()
    for part in el.attrib.get('style', '').split(';'):
        if ':' in part:
            k, v = part.split(':', 1)
            st[k.strip()] = v.strip()
    return st


def svg_icon(text, places=3):
    """(viewBox, [(flags, d)]) for one SVG document; raises on transforms, which no bundled set uses."""
    root = ET.fromstring(text)
    vb = root.attrib.get('viewBox')
    if not vb:
        vb = '0 0 %s %s' % (fmt(num(root.attrib.get('width'), 24)), fmt(num(root.attrib.get('height'), 24)))
    vb = ' '.join(fmt(float(v)) for v in NUM.findall(vb))
    out = []

    def walk(el, st):
        st = style_of(el, st)
        if st.get('display') == 'none':
            return
        if 'transform' in el.attrib:
            raise ValueError('transform not supported')
        d = shape_to_path(el)
        if d:
            fill = st.get('fill', 'black') not in ('none', 'transparent')
            stroke = st.get('stroke', 'none') not in ('none', 'transparent')
            if not fill and not stroke:
                return
            flags = ('f' if fill else '') + ('s' if stroke else '') + ('e' if st.get('fill-rule') == 'evenodd' else '')
            segs = parse_path(d)
            if segs and segs[0][0] == 'm':
                segs[0] = ('M', segs[0][1])  # a leading relative moveto is absolute, so paths can be concatenated
            if segs:
                out.append((flags, format_path(segs, places)))
        for ch in el:
            tag = ch.tag.split('}')[-1]
            if tag in ('title', 'desc', 'defs', 'metadata', 'style', 'clipPath', 'mask'):
                continue
            walk(ch, st)

    base = style_of(root, {'fill': 'black', 'stroke': 'none'})
    for ch in root:
        tag = ch.tag.split('}')[-1]
        if tag in ('title', 'desc', 'defs', 'metadata', 'style'):
            continue
        walk(ch, base)
    # Merge consecutive paths with identical flags: one path string per style, as the renderers draw them.
    merged = []
    for flags, d in out:
        if merged and merged[-1][0] == flags and 'f' not in flags:
            merged[-1] = (flags, merged[-1][1] + d)
        else:
            merged.append((flags, d))
    return vb, merged
