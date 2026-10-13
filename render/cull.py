#!/usr/bin/env python3
"""For canvas uploads only (the asset store takes at most 2 MB a file): the renders in cache/render stay whole; this writes a separate copy to upload.
Drops what a renderer SVG draws wholly outside its frame: paths and circles whose every point lies beyond the viewBox plus a margin
(the margin keeps blurred shadows and glows that reach in). Only plain elements are culled: nothing in <defs>, nothing with a transform
or under a transformed group, no path with curves or relative commands. What shows in the frame stays: the canal ring's Map home came out 2.1-2.7 MB -> 0.9-1.3 MB a layer, at most 9 of 255 levels apart on 1% of pixels (grain and ripple filters follow a group's bounds).
python3 cull.py <in.svg> <out.svg> [margin 24]"""
import re, sys

TOKEN = re.compile(r'<(/?)(\w+)([^>]*?)(/?)>')

def outside(box, xs, ys):
    x0, y0, x1, y1 = box
    return max(xs) < x0 or min(xs) > x1 or max(ys) < y0 or min(ys) > y1

def cull(svg, margin=24):
    vb = [float(v) for v in re.search(r'viewBox="([^"]+)"', svg).group(1).split()]
    box = (vb[0] - margin, vb[1] - margin, vb[0] + vb[2] + margin, vb[1] + vb[3] + margin)
    out, pos, stack, dropped = [], 0, [], 0
    for m in TOKEN.finditer(svg):
        close, tag, attrs, selfclose = m.group(1), m.group(2), m.group(3), m.group(4)
        if close:
            if stack: stack.pop()
            continue
        guarded = any(stack) or 'transform=' in attrs
        if not selfclose:
            stack.append(guarded or tag in ('defs', 'pattern', 'clipPath', 'mask', 'symbol', 'marker', 'filter', 'linearGradient', 'radialGradient'))
            continue
        if guarded or tag not in ('path', 'circle'): continue
        if tag == 'path':
            d = re.search(r'\bd="([^"]*)"', attrs)
            if not d or re.search(r'[^MLZz0-9.\-\s,]', d.group(1)): continue
            nums = [float(v) for v in re.findall(r'-?\d+(?:\.\d+)?', d.group(1))]
            if len(nums) < 2: continue
            xs, ys = nums[0::2], nums[1::2]
        else:
            c = {k: float(v) for k, v in re.findall(r'\b(cx|cy|r)="(-?[\d.]+)"', attrs)}
            if not {'cx', 'cy', 'r'} <= set(c): continue
            xs, ys = [c['cx'] - c['r'], c['cx'] + c['r']], [c['cy'] - c['r'], c['cy'] + c['r']]
        if outside(box, xs, ys):
            out.append(svg[pos:m.start()]); pos = m.end(); dropped += 1
    out.append(svg[pos:])
    return ''.join(out), dropped

if __name__ == '__main__':
    src, dst = sys.argv[1], sys.argv[2]
    s = open(src).read()
    r, n = cull(s, float(sys.argv[3]) if len(sys.argv) > 3 else 24)
    open(dst, 'w').write(r)
    print('%s %d -> %d KB, %d elements outside the frame dropped' % (src, len(s) // 1024, len(r) // 1024, n))
