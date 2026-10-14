#!/usr/bin/env python3
"""The boards' own symbols as Atlas's symbols: board_symbols.json for :places.

    python3 board_symbols.py [--repo <repo root>] [--report]

The boards (canvas/project/*.dc.html, engine/project/*.dc.html) draw every icon as an inline SVG. extract.py in
cache/look-ref/symbols/ collects them, deduplicated by geometry, into symbols.json; this tool turns that catalogue into
the bundled resource src/main/resources/atlas/places/board_symbols.json. Python 3 standard library only.

For each symbol it writes a semantic id (board_symbol_names.py: what the boards use it for), the ids it stands in for
(Lucide, Maki, Temaki, Pinhead), and its geometry as paths: every path, circle, ellipse, rect (with rx/ry), line,
polyline and polygon becomes path data with its element and group transforms applied; fill and stroke are resolved per
element with SVG's inheritance, so each path carries its own colour, opacity, stroke width, caps, joins, dashes, fill
rule, and linear or radial gradient. currentColor means the tint. A symbol drawn in one colour only is written
tintable, with that colour kept as its hue. Text inside a symbol (a price tag's "50", a sign's "OPEN") is kept as text
items. Full drawings (maps, charts, gauges showing one reading, textures, instrument layers) are left out.

Resource format (all lengths in viewBox units):
  {"symbols": [{"id", "n" (catalogue number), "h" (geometry hash), "vb", "k"? (hue #AARRGGBB),
                "p": [[flags, d, style?]], "t"?: [text], "s"?: [[dx, dy, blur, #AARRGGBB]] (drop shadows)}],
   "aliases": {old id: board id}}
  flags: 'f' filled, 's' stroked, 'e' even-odd fill rule
  style: c colour #AARRGGBB (absent: the tint), o opacity, w stroke width (absent: 1), cap b|r|s (absent: butt),
         join m|r|b (absent: miter), dash [on, off, ...], g {k: l|r, s: [[offset, #AARRGGBB]], p: [x1, y1, x2, y2]}
         (radial: centre x1 y1, radius to x2 y2)
  text: {s: text, x, y, z (size), w (weight), f (sans|serif|mono), a (start|middle|end), c, o, i (italic), l (spacing)}
"""
import argparse
import collections
import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from svgpaths import NUM, fmt, format_path, parse_path  # noqa: E402
import board_symbol_names as NAMES  # noqa: E402

OUT = os.path.join(HERE, '..', 'src', 'main', 'resources', 'atlas', 'places', 'board_symbols.json')
PLACES = 3

# ---------------------------------------------------------------------------------------------------------------------
# Affine matrices (a, b, c, d, e, f): x' = a x + c y + e, y' = b x + d y + f, as in SVG.

IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def mul(m, n):
    """m after n: applying the result is applying n, then m."""
    a, b, c, d, e, f = m
    a2, b2, c2, d2, e2, f2 = n
    return (a * a2 + c * b2, b * a2 + d * b2, a * c2 + c * d2, b * c2 + d * d2, a * e2 + c * f2 + e, b * e2 + d * f2 + f)


def apply(m, x, y):
    return m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]


def is_identity(m, eps=1e-9):
    return all(abs(v - w) < eps for v, w in zip(m, IDENTITY))


def scale_of(m):
    """The mean linear scale of a matrix: how stroke widths grow under it."""
    return math.sqrt(abs(m[0] * m[3] - m[1] * m[2]))


def parse_transform(s):
    m = IDENTITY
    for name, args in re.findall(r'(matrix|translate|scale|rotate|skewX|skewY)\s*\(([^)]*)\)', s or ''):
        v = [float(x) for x in NUM.findall(args)]
        if name == 'matrix' and len(v) == 6:
            t = tuple(v)
        elif name == 'translate':
            t = (1, 0, 0, 1, v[0] if v else 0, v[1] if len(v) > 1 else 0)
        elif name == 'scale':
            sx = v[0] if v else 1
            t = (sx, 0, 0, v[1] if len(v) > 1 else sx, 0, 0)
        elif name == 'rotate':
            r = math.radians(v[0] if v else 0)
            t = (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)
            if len(v) >= 3:
                t = mul(mul((1, 0, 0, 1, v[1], v[2]), t), (1, 0, 0, 1, -v[1], -v[2]))
        elif name == 'skewX':
            t = (1, 0, math.tan(math.radians(v[0] if v else 0)), 1, 0, 0)
        elif name == 'skewY':
            t = (1, math.tan(math.radians(v[0] if v else 0)), 0, 1, 0, 0)
        else:
            continue
        m = mul(m, tuple(float(x) for x in t))
    return m


# ---------------------------------------------------------------------------------------------------------------------
# Path data: absolute commands, arcs as cubics, transformed.

def absolute(segs):
    """[(cmd, args)] to absolute M, L, C, Q, A, Z; H and V become L, S and T their full forms."""
    out = []
    cx = cy = sx = sy = 0.0
    last_c = last_q = None
    for cmd, a in segs:
        up = cmd.upper()
        rel = cmd != up and cmd not in 'Zz'
        ox, oy = (cx, cy) if rel else (0.0, 0.0)
        if up == 'M':
            cx, cy = ox + a[0], oy + a[1]
            sx, sy = cx, cy
            out.append(('M', [cx, cy]))
            last_c = last_q = None
        elif up == 'L':
            cx, cy = ox + a[0], oy + a[1]
            out.append(('L', [cx, cy]))
            last_c = last_q = None
        elif up == 'H':
            cx = (cx if rel else 0.0) + a[0]
            out.append(('L', [cx, cy]))
            last_c = last_q = None
        elif up == 'V':
            cy = (cy if rel else 0.0) + a[0]
            out.append(('L', [cx, cy]))
            last_c = last_q = None
        elif up in 'CS':
            if up == 'C':
                x1, y1 = ox + a[0], oy + a[1]
                rest = a[2:]
            else:
                x1, y1 = (2 * cx - last_c[0], 2 * cy - last_c[1]) if last_c else (cx, cy)
                rest = a
            x2, y2, x, y = ox + rest[0], oy + rest[1], ox + rest[2], oy + rest[3]
            out.append(('C', [x1, y1, x2, y2, x, y]))
            last_c, last_q = (x2, y2), None
            cx, cy = x, y
        elif up in 'QT':
            if up == 'Q':
                x1, y1 = ox + a[0], oy + a[1]
                x, y = ox + a[2], oy + a[3]
            else:
                x1, y1 = (2 * cx - last_q[0], 2 * cy - last_q[1]) if last_q else (cx, cy)
                x, y = ox + a[0], oy + a[1]
            out.append(('Q', [x1, y1, x, y]))
            last_q, last_c = (x1, y1), None
            cx, cy = x, y
        elif up == 'A':
            x, y = ox + a[5], oy + a[6]
            out.append(('A', [cx, cy, abs(a[0]), abs(a[1]), a[2], a[3], a[4], x, y]))
            cx, cy = x, y
            last_c = last_q = None
        elif up == 'Z':
            out.append(('Z', []))
            cx, cy = sx, sy
            last_c = last_q = None
    return out


def arc_to_cubics(x0, y0, rx, ry, rot, large, sweep, x, y):
    """SVG implementation notes F.6: endpoint to centre form, then at most 90 degrees per cubic."""
    if (x0, y0) == (x, y):
        return []
    if rx == 0 or ry == 0:
        return [('L', [x, y])]
    phi = math.radians(rot)
    cp, sp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x) / 2, (y0 - y) / 2
    x1p, y1p = cp * dx2 + sp * dy2, -sp * dx2 + cp * dy2
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        k = math.sqrt(lam)
        rx, ry = rx * k, ry * k
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(num / den, 0.0)) if den else 0.0
    if bool(large) == bool(sweep):
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    ccx, ccy = cp * cxp - sp * cyp + (x0 + x) / 2, sp * cxp + cp * cyp + (y0 + y) / 2
    ux, uy = (x1p - cxp) / rx, (y1p - cyp) / ry
    vx, vy = (-x1p - cxp) / rx, (-y1p - cyp) / ry
    t1 = math.atan2(uy, ux)
    delta = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi
    n = max(1, int(math.ceil(abs(delta) / (math.pi / 2) - 1e-9)))
    step = delta / n
    t = 4.0 / 3.0 * math.tan(step / 4)
    out = []
    a = t1
    for k in range(n):
        b = a + step
        ca, sa, cb, sb = math.cos(a), math.sin(a), math.cos(b), math.sin(b)
        p1 = (ca - t * sa, sa + t * ca)
        p2 = (cb + t * sb, sb - t * cb)
        end = (x, y) if k == n - 1 else (ccx + rx * cb * cp - ry * sb * sp, ccy + rx * cb * sp + ry * sb * cp)
        out.append(('C', [ccx + rx * p1[0] * cp - ry * p1[1] * sp, ccy + rx * p1[0] * sp + ry * p1[1] * cp,
                          ccx + rx * p2[0] * cp - ry * p2[1] * sp, ccy + rx * p2[0] * sp + ry * p2[1] * cp, end[0], end[1]]))
        a = b
    return out


def transformed(d, m):
    """Path data [d] under matrix [m], as compact absolute path data; [d] itself when [m] is the identity."""
    if is_identity(m):
        return d
    out = []
    for cmd, a in absolute(parse_path(d)):
        if cmd == 'A':
            for c2, a2 in arc_to_cubics(*a):
                out.append(_moved(c2, a2, m))
        else:
            out.append(_moved(cmd, a, m))
    return format_path(out, PLACES)


def _moved(cmd, a, m):
    pts = []
    for i in range(0, len(a), 2):
        pts.extend(apply(m, a[i], a[i + 1]))
    return cmd, pts


def bbox(d):
    """The box (x, y, w, h) of path data, control points included (enough for a gradient's object box)."""
    xs, ys = [], []
    for cmd, a in absolute(parse_path(d)):
        if cmd == 'A':
            for _, a2 in arc_to_cubics(*a):
                xs.extend(a2[0::2]); ys.extend(a2[1::2])
        else:
            xs.extend(a[0::2]); ys.extend(a[1::2])
    if not xs:
        return 0.0, 0.0, 0.0, 0.0
    return min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)


# ---------------------------------------------------------------------------------------------------------------------
# Shapes to path data, in the element's own user space.

def length(v, ref=0.0, default=0.0):
    if v is None:
        return default
    v = v.strip()
    m = NUM.match(v)
    if not m:
        return default
    x = float(m.group(0))
    return x * ref / 100.0 if v.endswith('%') else x


def shape_path(tag, a, vb):
    w_ref, h_ref = vb[2], vb[3]
    d_ref = math.hypot(w_ref, h_ref) / math.sqrt(2)
    if tag == 'path':
        return a.get('d', '').strip()
    if tag in ('circle', 'ellipse'):
        cx, cy = length(a.get('cx'), w_ref), length(a.get('cy'), h_ref)
        if tag == 'circle':
            rx = ry = length(a.get('r'), d_ref)
        else:
            rx, ry = length(a.get('rx'), w_ref), length(a.get('ry'), h_ref)
        if rx <= 0 or ry <= 0:
            return ''
        return 'M%s %sA%s %s 0 1 0 %s %sA%s %s 0 1 0 %s %sZ' % (
            fmt(cx - rx), fmt(cy), fmt(rx), fmt(ry), fmt(cx + rx), fmt(cy), fmt(rx), fmt(ry), fmt(cx - rx), fmt(cy))
    if tag == 'rect':
        x, y = length(a.get('x'), w_ref), length(a.get('y'), h_ref)
        w, h = length(a.get('width'), w_ref), length(a.get('height'), h_ref)
        if w <= 0 or h <= 0:
            return ''
        rx, ry = a.get('rx'), a.get('ry')
        rx = length(rx, w_ref) if rx not in (None, 'auto') else None
        ry = length(ry, h_ref) if ry not in (None, 'auto') else None
        if rx is None:
            rx = ry if ry is not None else 0.0
        if ry is None:
            ry = rx
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if rx <= 0 or ry <= 0:
            return 'M%s %sH%sV%sH%sZ' % (fmt(x), fmt(y), fmt(x + w), fmt(y + h), fmt(x))
        return ('M%s %sH%sA%s %s 0 0 1 %s %sV%sA%s %s 0 0 1 %s %sH%sA%s %s 0 0 1 %s %sV%sA%s %s 0 0 1 %s %sZ' % (
            fmt(x + rx), fmt(y), fmt(x + w - rx), fmt(rx), fmt(ry), fmt(x + w), fmt(y + ry), fmt(y + h - ry),
            fmt(rx), fmt(ry), fmt(x + w - rx), fmt(y + h), fmt(x + rx), fmt(rx), fmt(ry), fmt(x), fmt(y + h - ry),
            fmt(y + ry), fmt(rx), fmt(ry), fmt(x + rx), fmt(y)))
    if tag == 'line':
        return 'M%s %sL%s %s' % (fmt(length(a.get('x1'), w_ref)), fmt(length(a.get('y1'), h_ref)),
                                 fmt(length(a.get('x2'), w_ref)), fmt(length(a.get('y2'), h_ref)))
    if tag in ('polyline', 'polygon'):
        pts = [float(v) for v in NUM.findall(a.get('points', ''))]
        if len(pts) < 4:
            return ''
        s = 'M%s %s' % (fmt(pts[0]), fmt(pts[1])) + ''.join(
            'L%s %s' % (fmt(pts[k]), fmt(pts[k + 1])) for k in range(2, len(pts) - 1, 2))
        return s + ('Z' if tag == 'polygon' else '')
    return None


# ---------------------------------------------------------------------------------------------------------------------
# Colours and paint.

NAMED = {
    'black': (0, 0, 0), 'white': (255, 255, 255), 'red': (255, 0, 0), 'green': (0, 128, 0), 'blue': (0, 0, 255),
    'yellow': (255, 255, 0), 'orange': (255, 165, 0), 'gray': (128, 128, 128), 'grey': (128, 128, 128),
    'silver': (192, 192, 192), 'gold': (255, 215, 0), 'navy': (0, 0, 128), 'teal': (0, 128, 128),
}
TINT = 'tint'


def colour(v):
    """A CSS colour to (r, g, b, alpha 0..1); TINT for currentColor; None for none, transparent or unknown."""
    if v is None:
        return None
    v = v.strip()
    lv = v.lower()
    if lv in ('none', 'transparent', ''):
        return None
    if lv == 'currentcolor' or '{{' in v:
        return TINT
    if v.startswith('#'):
        h = v[1:]
        if len(h) in (3, 4):
            h = ''.join(c * 2 for c in h)
        if len(h) == 6:
            return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 1.0
        if len(h) == 8:
            return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16) / 255.0
        return None
    m = re.match(r'rgba?\(([^)]*)\)', lv)
    if m:
        parts = [p for p in re.split(r'[\s,/]+', m.group(1).strip()) if p]
        vals = []
        for i, p in enumerate(parts[:4]):
            x = float(NUM.match(p).group(0))
            if p.endswith('%'):
                x = x * (2.55 if i < 3 else 0.01)
            vals.append(x)
        if len(vals) < 3:
            return None
        return int(round(vals[0])), int(round(vals[1])), int(round(vals[2])), vals[3] if len(vals) > 3 else 1.0
    if lv in NAMED:
        return NAMED[lv] + (1.0,)
    return None


def argb(c, alpha=1.0):
    r, g, b, a = c
    return '#%02X%02X%02X%02X' % (max(0, min(255, int(round(a * alpha * 255)))), r, g, b)


def faded(c, alpha):
    """An #AARRGGBB colour with its alpha multiplied by [alpha]."""
    return '#%02X%s' % (max(0, min(255, int(round(int(c[1:3], 16) * alpha)))), c[3:])


def opacity_of(v, default=1.0):
    if v is None:
        return default
    v = v.strip()
    m = NUM.match(v)
    if not m:
        return default
    x = float(m.group(0))
    if v.endswith('%'):
        x /= 100.0
    return max(0.0, min(1.0, x))


# ---------------------------------------------------------------------------------------------------------------------
# Gradients and patterns.

def gradient_paint(gid, defs, units_box, ctm, vb):
    """A gradient paint {k, s, p} in viewBox units for an element whose path box is [units_box], or None."""
    el = defs.get(gid)
    if el is None:
        return None
    tag = el.tag
    if tag == 'pattern':
        return pattern_colour(el, defs)
    chain, seen = [el], {gid}
    while True:
        ref = chain[-1].attrib.get('href') or chain[-1].attrib.get('{http://www.w3.org/1999/xlink}href')
        if not ref or not ref.startswith('#') or ref[1:] in seen or ref[1:] not in defs:
            break
        seen.add(ref[1:])
        chain.append(defs[ref[1:]])

    def attr(name, default=None):
        for g in chain:
            if name in g.attrib:
                return g.attrib[name]
        return default

    stops_el = next((list(g.iter('stop')) for g in chain if list(g.iter('stop'))), [])
    stops = []
    for s in stops_el:
        st = dict(s.attrib)
        for part in st.get('style', '').split(';'):
            if ':' in part:
                k, v = part.split(':', 1)
                st[k.strip()] = v.strip()
        off = opacity_of(st.get('offset', '0'))
        c = colour(st.get('stop-color', 'black'))
        if c is None:
            c = (0, 0, 0, 0.0)
        if c == TINT:
            c = (0x14, 0x16, 0x1B, 1.0)
        if stops and off < stops[-1][0]:
            off = stops[-1][0]
        stops.append((off, argb(c, opacity_of(st.get('stop-opacity')))))
    if not stops:
        return None
    if len(stops) == 1:
        return {'solid': stops[0][1]}
    user = attr('gradientUnits', 'objectBoundingBox') == 'userSpaceOnUse'
    gt = parse_transform(attr('gradientTransform', ''))
    bx, by, bw, bh = units_box
    if user:
        to_user = gt
        ref_w, ref_h = vb[2], vb[3]
    else:
        to_user = mul((bw, 0, 0, bh, bx, by), gt)
        ref_w = ref_h = 1.0
    m = mul(ctm, to_user)
    if tag == 'linearGradient':
        x1 = length(attr('x1', '0%'), ref_w)
        y1 = length(attr('y1', '0%'), ref_h)
        x2 = length(attr('x2', '100%'), ref_w)
        y2 = length(attr('y2', '0%'), ref_h)
        p1, p2 = apply(m, x1, y1), apply(m, x2, y2)
        return {'k': 'l', 's': [[round(o, 4), c] for o, c in stops], 'p': [round(v, 3) for v in p1 + p2]}
    cx = length(attr('cx', '50%'), ref_w)
    cy = length(attr('cy', '50%'), ref_h)
    r = length(attr('r', '50%'), math.hypot(ref_w, ref_h) / math.sqrt(2))
    centre = apply(m, cx, cy)
    # The radius under the full matrix: the mean of the two axes, so an object box that is not square stays close.
    rx = math.hypot(*(lambda p: (p[0] - centre[0], p[1] - centre[1]))(apply(m, cx + r, cy)))
    ry = math.hypot(*(lambda p: (p[0] - centre[0], p[1] - centre[1]))(apply(m, cx, cy + r)))
    rad = (rx + ry) / 2
    return {'k': 'r', 's': [[round(o, 4), c] for o, c in stops], 'p': [round(centre[0], 3), round(centre[1], 3), round(centre[0] + rad, 3), round(centre[1], 3)]}


def pattern_colour(el, defs):
    """A pattern (hatching, slats, checks) as one flat colour: its painted colours weighted by area, at the share of
    the tile they cover. The renderers have no pattern fill; this keeps the tone."""
    pw, ph = length(el.attrib.get('width'), 1.0, 1.0), length(el.attrib.get('height'), 1.0, 1.0)
    tile = max(pw * ph, 1e-6)
    acc = [0.0, 0.0, 0.0]
    cover = 0.0
    for ch in el.iter():
        if ch is el or ch.tag not in ('path', 'rect', 'circle', 'line', 'polyline', 'polygon', 'ellipse'):
            continue
        c = colour(ch.attrib.get('fill')) if ch.tag not in ('line', 'polyline') else None
        area = 0.0
        if c and c != TINT:
            d = shape_path(ch.tag, ch.attrib, (0, 0, pw, ph))
            x, y, w, h = bbox(d) if d else (0, 0, 0, 0)
            area = w * h * 0.8
        else:
            c = colour(ch.attrib.get('stroke'))
            if not c or c == TINT:
                continue
            d = shape_path(ch.tag, ch.attrib, (0, 0, pw, ph))
            x, y, w, h = bbox(d) if d else (0, 0, 0, 0)
            area = max(w, h) * length(ch.attrib.get('stroke-width'), 1.0, 1.0)
        a = min(area / tile, 1.0) * c[3] * opacity_of(ch.attrib.get('opacity'))
        for i in range(3):
            acc[i] += c[i] * a
        cover += a
    if cover <= 0:
        return None
    rgb = tuple(max(0, min(255, int(round(v / cover)))) for v in acc)
    return {'solid': argb(rgb + (1.0,), min(cover, 1.0))}


def drop_shadows(style, unit):
    """CSS `filter: drop-shadow(x y blur colour) ...` on the symbol's root, as [dx, dy, blur, #AARRGGBB] in viewBox
    units: the pins' shadows and the street-food signs' neon glow."""
    m = re.search(r'filter\s*:\s*([^;]+)', style or '')
    if not m:
        return []
    out = []
    text = m.group(1)
    i = 0
    while True:
        i = text.find('drop-shadow(', i)
        if i < 0:
            break
        j, depth = i + len('drop-shadow('), 1
        while j < len(text) and depth:
            depth += {'(': 1, ')': -1}.get(text[j], 0)
            j += 1
        body = text[i + len('drop-shadow('):j - 1]
        i = j
        c = re.search(r'(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)|[a-zA-Z]+)\s*$', body.strip())
        col = colour(c.group(1)) if c else (0, 0, 0, 1.0)
        nums = [float(v) for v in NUM.findall(body[:c.start()] if c else body)]
        if len(nums) < 2 or col in (None, TINT):
            continue
        blur = nums[2] if len(nums) > 2 else 0.0
        out.append([round(nums[0] * unit, 3), round(nums[1] * unit, 3), round(blur * unit, 3), argb(col)])
    return out


# ---------------------------------------------------------------------------------------------------------------------
# The walk: inherited presentation attributes, transforms and opacity, element by element.

INHERITED = ('fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'stroke-dasharray', 'stroke-dashoffset',
             'stroke-miterlimit', 'fill-rule', 'fill-opacity', 'stroke-opacity', 'color', 'font-size', 'font-weight',
             'font-family', 'font-style', 'text-anchor', 'letter-spacing', 'visibility')
SKIP = ('defs', 'linearGradient', 'radialGradient', 'pattern', 'clipPath', 'mask', 'filter', 'symbol', 'marker', 'title',
        'desc', 'metadata', 'style', 'script', 'foreignObject')
SHAPES = ('path', 'circle', 'ellipse', 'rect', 'line', 'polyline', 'polygon')
CAPS = {'butt': None, 'round': 'r', 'square': 's'}
JOINS = {'miter': None, 'round': 'r', 'bevel': 'b', 'miter-clip': None, 'arcs': None}


def declared(el):
    """The element's presentation attributes, with its style attribute's declarations winning."""
    st = {k: v for k, v in el.attrib.items()}
    for part in el.attrib.get('style', '').split(';'):
        if ':' in part:
            k, v = part.split(':', 1)
            st[k.strip()] = v.strip()
    return st


def strip_ns(root):
    for el in root.iter():
        if isinstance(el.tag, str) and '}' in el.tag:
            el.tag = el.tag.split('}', 1)[1]


class Converter:
    def __init__(self, svg):
        self.svg = re.sub(r'\{\{[^}]*\}\}', '{{}}', svg)
        text = re.sub(r'="([^"]*)\{\{\}\}([^"]*)"', lambda m: '="%s%s"' % (m.group(1), '{{}}' + m.group(2)), self.svg)
        self.root = ET.fromstring(text)
        strip_ns(self.root)
        a = self.root.attrib
        vb = a.get('viewBox')
        if vb:
            self.vb = tuple(float(v) for v in NUM.findall(vb))[:4]
        else:
            self.vb = (0.0, 0.0, length(a.get('width'), 0, 24.0), length(a.get('height'), 0, 24.0))
        self.defs = {el.attrib['id']: el for el in self.root.iter() if 'id' in el.attrib}
        self.paths = []
        self.texts = []
        self.notes = set()
        self.shadows = drop_shadows(a.get('style', ''), self.unit())

    def unit(self):
        """viewBox units per CSS pixel, from the size the board draws the symbol at."""
        w = length(self.root.attrib.get('width'), 0, 0.0)
        h = length(self.root.attrib.get('height'), 0, 0.0)
        if w > 0 and h > 0:
            return max(self.vb[2] / w, self.vb[3] / h)
        return 1.0

    def run(self):
        base = {'fill': 'black', 'stroke': 'none', 'stroke-width': '1', 'font-size': '16', 'font-weight': '400',
                'font-family': 'sans-serif', 'text-anchor': 'start', 'color': 'currentColor'}
        self.walk(self.root, base, IDENTITY, 1.0, top=True)
        return self

    def walk(self, el, inherited, ctm, opacity, top=False):
        tag = el.tag
        if tag in SKIP:
            return
        st = dict(inherited)
        own = declared(el)
        for k in INHERITED:
            if k in own and own[k] not in ('inherit', ''):
                st[k] = own[k]
        if own.get('display') == 'none':
            return
        if not top and 'transform' in own:
            ctm = mul(ctm, parse_transform(own['transform']))
        opacity *= opacity_of(own.get('opacity'))
        if 'filter' in own and own['filter'] != 'none':
            self.notes.add('filter')
        if 'clip-path' in own or 'mask' in own:
            self.notes.add('clip')
        if tag in SHAPES:
            if st.get('visibility') not in ('hidden', 'collapse'):
                self.shape(tag, own, st, ctm, opacity)
            return
        if tag == 'text':
            if st.get('visibility') not in ('hidden', 'collapse'):
                self.text(el, own, st, ctm, opacity)
            return
        for ch in el:
            if isinstance(ch.tag, str):
                self.walk(ch, st, ctm, opacity)

    def paint(self, v, d_box, ctm, alpha):
        """(colour or None for the tint, gradient or None), or None when nothing is painted."""
        if v is None:
            return None
        v = v.strip()
        m = re.match(r'url\(\s*#([^)\s]+)\s*\)\s*(.*)', v)
        if m:
            g = gradient_paint(m.group(1), self.defs, d_box, ctm, self.vb)
            if g is None:
                return self.paint(m.group(2) or 'none', d_box, ctm, alpha) if m.group(2) else None
            if 'solid' in g:
                return (faded(g['solid'], alpha), None)
            if alpha < 0.999:
                g = dict(g, s=[[o, faded(c, alpha)] for o, c in g['s']])
            return (None, g)
        c = colour(v)
        if c is None:
            return None
        if c == TINT:
            return (None if alpha >= 0.999 else ('tint', alpha), None)
        return (argb(c, alpha), None)

    def shape(self, tag, own, st, ctm, opacity):
        d = shape_path(tag, own, self.vb)
        if not d:
            return
        box = bbox(d)
        fill_v = None if tag in ('line',) else st.get('fill')
        fill = self.paint(fill_v, box, ctm, opacity_of(st.get('fill-opacity')))
        sw = length(st.get('stroke-width'), math.hypot(self.vb[2], self.vb[3]) / math.sqrt(2), 1.0)
        stroke = self.paint(st.get('stroke'), box, ctm, opacity_of(st.get('stroke-opacity'))) if sw > 0 else None
        if fill is None and stroke is None:
            return
        td = transformed(d, ctm)
        if not td:
            return
        even = st.get('fill-rule', 'nonzero').strip() == 'evenodd'
        k = scale_of(ctm)

        def style(p, stroked):
            out = {}
            col, grad = p
            o = opacity
            if isinstance(col, tuple):  # the tint at an opacity of its own
                o *= col[1]
                col = None
            if grad is not None:
                out['g'] = grad
            elif col is not None:
                out['c'] = col
            if o < 0.999:
                out['o'] = round(o, 3)
            if stroked:
                w = sw * k
                if abs(w - 1) > 1e-6:
                    out['w'] = round(w, 3)
                cap = CAPS.get(st.get('stroke-linecap', 'butt').strip())
                join = JOINS.get(st.get('stroke-linejoin', 'miter').strip())
                if cap:
                    out['cap'] = cap
                if join:
                    out['join'] = join
                dash = [float(x) * k for x in NUM.findall(st.get('stroke-dasharray', '') or '')]
                if dash and any(x > 0 for x in dash):
                    if len(dash) % 2:
                        dash = dash * 2
                    out['dash'] = [round(x, 3) for x in dash]
            return out

        if fill is not None:
            fs = style(fill, False)
            if stroke is not None:
                ss = style(stroke, True)
                same = {k2: v for k2, v in ss.items() if k2 not in ('w', 'cap', 'join', 'dash')} == fs
                if same and 'g' not in fs:
                    self.paths.append(['fs' + ('e' if even else ''), td, ss])
                    return
            self.paths.append(['f' + ('e' if even else ''), td, fs])
        if stroke is not None:
            self.paths.append(['s', td, style(stroke, True)])

    def text(self, el, own, st, ctm, opacity):
        content = re.sub(r'\s+', ' ', ''.join(el.itertext())).strip()
        if not content or '{{' in content:
            if content:
                self.notes.add('template text')
            return
        x = length((NUM.findall(own.get('x', '0')) or ['0'])[0])
        y = length((NUM.findall(own.get('y', '0')) or ['0'])[0])
        px, py = apply(ctm, x, y)
        k = scale_of(ctm)
        size = length(st.get('font-size'), 16, 16) * k
        fill = self.paint(st.get('fill'), (x, y, 0, 0), ctm, opacity_of(st.get('fill-opacity')))
        if fill is None:
            return
        family = st.get('font-family', '').lower()
        fam = 'mono' if 'mono' in family or 'courier' in family else (
            'serif' if 'serif' in family and 'sans' not in family else 'sans')
        weight = st.get('font-weight', '400').strip()
        weight = {'normal': 400, 'bold': 700, 'bolder': 800, 'lighter': 300}.get(weight, None) or int(float(weight))
        t = {'s': content, 'x': round(px, 3), 'y': round(py, 3), 'z': round(size, 3)}
        if weight != 400:
            t['w'] = weight
        if fam != 'sans':
            t['f'] = fam
        anchor = st.get('text-anchor', 'start').strip()
        if anchor != 'start':
            t['a'] = anchor
        col, grad = fill
        o = opacity
        if isinstance(col, tuple):
            o *= col[1]
            col = None
        if grad is not None:
            col = grad['s'][len(grad['s']) // 2][1]
        if col is not None:
            t['c'] = col
        if o < 0.999:
            t['o'] = round(o, 3)
        if st.get('font-style', '').strip() == 'italic':
            t['i'] = 1
        ls = st.get('letter-spacing')
        if ls and NUM.match(ls.strip()):
            t['l'] = round(length(ls) * k, 3)
        rot = math.degrees(math.atan2(ctm[1], ctm[0]))
        if abs(rot) > 0.01:
            t['r'] = round(rot, 2)
        self.texts.append(t)


# ---------------------------------------------------------------------------------------------------------------------
# Symbols or drawings.

def features(e, conv):
    vb = conv.vb
    w, h = vb[2], vb[3]
    return {
        'w': w, 'h': h, 'aspect': max(w, h) / max(min(w, h), 1e-6), 'paths': len(conv.paths), 'texts': len(conv.texts),
        'pattern': '<pattern' in e['svg'], 'filter': 'filter' in conv.notes, 'stretched': 'preserveAspectRatio="none"' in e['svg'],
    }


def kind_of(e, f):
    """'drawing' for maps, charts, scenes and textures; 'symbol' otherwise. Hand decisions in board_symbol_names win."""
    h = e['id']
    if h in NAMES.DRAWINGS:
        return 'drawing'
    if h in NAMES.SYMBOLS or h in NAMES.NAMES:
        return 'symbol'
    if f['w'] >= 150 or f['h'] >= 150 or f['stretched']:
        return 'drawing'
    if f['aspect'] >= 3.2:
        return 'drawing'
    if f['paths'] > 60:
        return 'drawing'
    return 'symbol'


# ---------------------------------------------------------------------------------------------------------------------
# One look per symbol: the catalogue's own (the first use on the boards, as the contact sheets show it).

def split_colour(c):
    """#AARRGGBB to (#RRGGBB, alpha 0..1)."""
    return '#' + c[3:], int(c[1:3], 16) / 255.0


def look(conv):
    """(signature, mono colour) of one converted use. A use whose paths and texts are all one colour (any opacity) is
    mono: its signature has no colour, so the app can tint it, and the colour is returned (None for currentColor)."""
    paints = set()
    for p in conv.paths:
        st = p[2]
        if 'g' in st:
            paints.add('gradient')
        else:
            paints.add(split_colour(st['c'])[0] if 'c' in st else None)
    for t in conv.texts:
        paints.add(split_colour(t['c'])[0] if 'c' in t else None)
    mono = len(paints) == 1 and 'gradient' not in paints
    colour_of = next(iter(paints)) if mono else None
    paths, texts = [], []
    for flags, d, st in conv.paths:
        st = dict(st)
        if mono and 'c' in st:
            alpha = split_colour(st.pop('c'))[1]
            if alpha < 0.999:
                st['o'] = round(st.get('o', 1.0) * alpha, 3)
        paths.append([flags, d, st])
    for t in conv.texts:
        t = dict(t)
        if mono and 'c' in t:
            alpha = split_colour(t.pop('c'))[1]
            if alpha < 0.999:
                t['o'] = round(t.get('o', 1.0) * alpha, 3)
        texts.append(t)
    return json.dumps([paths, texts], sort_keys=True), mono, colour_of, paths, texts


def canonical(e):
    """The symbol as the catalogue keeps it: (paths, texts, hue, conv). A one-colour symbol is tintable, and hue is its
    colour on the boards (None when that is currentColor, the text colour)."""
    conv = Converter(e['svg']).run()
    _, mono, col, paths, texts = look(conv)
    hue = '#FF' + col[1:] if mono and col is not None else None
    return paths, texts, hue, conv


# ---------------------------------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--repo', default=os.path.abspath(os.path.join(HERE, '..', '..', '..', '..')))
    ap.add_argument('--catalogue', default=None)
    ap.add_argument('--out', default=OUT)
    ap.add_argument('--report', action='store_true', help='print every symbol with its id, kind, uses and notes')
    args = ap.parse_args()
    cat_path = args.catalogue or os.path.join(args.repo, 'cache', 'look-ref', 'symbols', 'symbols.json')
    with open(cat_path, encoding='utf-8') as f:
        catalogue = json.load(f)

    names = dict(NAMES.NAMES)
    used = collections.Counter()
    records, kinds, notes = [], collections.Counter(), collections.Counter()
    coloured = set()
    for e in catalogue:
        paths, texts, hue, conv = canonical(e)
        f = features(e, conv)
        kind = kind_of(e, f)
        kinds[kind] += 1
        for n in conv.notes:
            notes[n] += 1
        if args.report:
            print('%4d %s %-8s %-28s %3d uses %-9s %s' % (
                e['n'], e['id'], kind, names.get(e['id'], ''), e['uses'], hue or '', ','.join(sorted(conv.notes))))
        if kind != 'symbol' or not (paths or texts):
            continue
        base = names.get(e['id']) or 'board.%d' % e['n']
        used[base] += 1
        sid = base if used[base] == 1 else '%s.%d' % (base, used[base])
        rec = {'id': sid, 'n': e['n'], 'h': e['id'], 'vb': ' '.join(fmt(v) for v in conv.vb),
               'p': [p if p[2] else p[:2] for p in paths]}
        if hue:
            rec['k'] = hue
        if texts:
            rec['t'] = texts
        if conv.shadows:
            rec['s'] = conv.shadows
        if any('c' in p[2] or 'g' in p[2] for p in paths) or any('c' in t for t in texts):
            coloured.add(sid)
        records.append(rec)

    ids = {r['id'] for r in records}
    aliases = {}
    for old, new in NAMES.ALIASES.items():
        if new not in ids:
            print('alias %s -> %s: no such board symbol' % (old, new), file=sys.stderr)
            continue
        if old in ids:
            print('alias %s shadows a board id' % old, file=sys.stderr)
            continue
        if new in coloured:
            print('alias %s -> %s: a coloured symbol stands in for a tinted one' % (old, new), file=sys.stderr)
        aliases[old] = new
    out = {'source': 'Atlas boards (canvas/project, engine/project) via cache/look-ref/symbols/symbols.json; MIT',
           'symbols': records, 'aliases': dict(sorted(aliases.items()))}
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    named = sum(1 for r in records if not r['id'].startswith('board.'))
    print('%d symbols (%d named, %d board.<n>, %d coloured), %d drawings left out, %d aliases; notes %s -> %s (%d KB)' % (
        len(records), named, len(records) - named, len(coloured), kinds['drawing'], len(aliases), dict(notes),
        os.path.relpath(args.out), os.path.getsize(args.out) // 1024))


if __name__ == '__main__':
    main()
