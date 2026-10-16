#!/usr/bin/env python3
"""local_globe.py: the zoom ladder's data, taken from the Globe board's own source.

    python3 local_globe.py            # writes app/src/main/res/raw/local_globe.json

The Globe board (canvas/project/Globe.dc.html; Galaxy and Planets are the same component opened at another stop) holds
Natural Earth's land (public domain, simplified, as rings of lon/lat pairs), its cities, and the Milky Way drawn as
polylines and stars. This tool lifts them out unchanged into one compact JSON file the app reads. Python 3 standard
library only.
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = HERE
while not os.path.isdir(os.path.join(ROOT, 'canvas')):
    ROOT = os.path.dirname(ROOT)
OUT = os.path.join(ROOT, 'app', 'android', 'app', 'src', 'main', 'res', 'raw', 'local_globe.json')


def nums(s):
    return [float(x) for x in re.findall(r'-?\d+(?:\.\d+)?', s)]


def main():
    with open(os.path.join(ROOT, 'canvas', 'project', 'Globe.dc.html'), encoding='utf-8') as f:
        s = f.read()
    script = s[s.index('<script type="text/x-dc"'):]
    land = json.loads(re.search(r'const LAND = (\[\[.*?\]\]);', script, re.S).group(1))
    cities = json.loads(re.search(r'const CITIES = (\[\[.*?\]\]);', script, re.S).group(1))

    svgs = [m for m in re.finditer(r'<svg\b[^>]*>.*?</svg>', s, re.S)]
    backdrop = svgs[0].group(0)
    galaxy = svgs[1].group(0)

    far = [[float(x), float(y), float(r), float(o)] for x, y, r, o in
           re.findall(r'<circle cx="([\d.]+)" cy="([\d.]+)" r="([\d.]+)" fill="#fff" opacity="([\d.]+)"', backdrop)]

    def paths(block):
        out = []
        for m in re.finditer(r'<path d="([^"]+)" fill="none" stroke="(#[0-9A-Fa-f]+)" stroke-opacity="?([\d.]+)"? stroke-width="([\d.]+)"([^>]*)>', block):
            tr = re.search(r'translate\(([-\d.]+) ([-\d.]+)\)', m.group(5))
            out.append({'p': nums(m.group(1)), 'c': m.group(2), 'o': float(m.group(3)), 'w': float(m.group(4)),
                        'dx': float(tr.group(1)) if tr else 0.0, 'dy': float(tr.group(2)) if tr else 0.0})
        return out

    arms = paths(re.search(r'<g filter="url\(#armblur\)">(.*?)</g>', galaxy, re.S).group(1))
    lanes = paths(re.search(r'<g filter="url\(#lanes\)">(.*?)</g>', galaxy, re.S).group(1))
    stars = [[float(x), float(y), float(r), c, float(o)] for x, y, r, c, o in
             re.findall(r'<circle cx="([\d.]+)" cy="([\d.]+)" r="([\d.]+)" fill="(#[0-9A-Fa-f]{6})" opacity="([\d.]+)"', galaxy)]
    here = re.search(r'<circle cx="([\d.]+)" cy="([\d.]+)" r="12" fill="none" stroke="#FFD24A"', galaxy)
    data = {
        'source': 'Atlas boards (canvas/project/Globe.dc.html); land: Natural Earth, public domain',
        'land': land, 'cities': cities, 'far': far, 'arms': arms, 'lanes': lanes, 'stars': stars,
        'here': [float(here.group(1)), float(here.group(2))],
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(data, f, separators=(',', ':'))
        f.write('\n')
    print('land rings', len(land), 'cities', len(cities), 'far', len(far), 'arms', len(arms), 'lanes', len(lanes), 'stars', len(stars), 'here', data['here'])
    print('wrote', os.path.relpath(OUT, ROOT), os.path.getsize(OUT), 'bytes')


if __name__ == '__main__':
    main()
