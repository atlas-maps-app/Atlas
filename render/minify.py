#!/usr/bin/env python3
"""Shrink renderer SVGs: integer path coordinates, implicit linetos, drop degenerate paths."""
import re, sys
def shrink_d(d):
    if re.search(r'[^MLZz0-9.\- ]', d): return d          # relative or curved commands: leave as written
    toks = re.findall(r'[MLZz]|-?\d+(?:\.\d+)?', d)
    out = []; last = None; pts = 0
    for t in toks:
        if t in 'MLZz':
            if t == 'L' and last in ('M', 'L', 'n'): last = 'L'; continue
            out.append(t); last = t
        else:
            out.append(str(int(round(float(t))))); last = 'n' if last in ('M', 'L', 'n') else last
    s = ''.join(o if o in 'MLZz' else ' ' + o for o in out).replace(' ', ' ')
    s = re.sub(r'([MLZz]) ', r'\1', s)
    return s
def main(fn, out):
    s = open(fn).read()
    s = re.sub(r' d="([^"]*)"', lambda m: ' d="%s"' % shrink_d(m.group(1)), s)
    s = re.sub(r'(c[xy]|r)="(-?\d+\.\d+)"', lambda m: '%s="%s"' % (m.group(1), ('%.1f' % float(m.group(2))).rstrip('0').rstrip('.')), s)
    # drop zero-area paths such as "M10 10 10 10 10 10Z"
    def keep(m):
        nums = re.findall(r'-?\d+', m.group(1))
        pts = set(zip(nums[0::2], nums[1::2]))
        return '' if len(pts) < 2 else m.group(0)
    s = re.sub(r'<path d="([^"]*)"[^>]*/>', keep, s)
    open(out, 'w').write(s)
    print(fn, len(open(fn).read())//1024, '->', len(s)//1024, 'KB')
if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
