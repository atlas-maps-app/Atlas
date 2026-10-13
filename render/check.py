#!/usr/bin/env python3
"""The engine's smoke test: render one place from its saved data and check what comes out. No network, a few seconds.
    python3 render/check.py [place] [moment]        (default: newyork sunny)
Checks that data/regions/places.py and the data/regions folders agree, that the render exits 0 in time, and that the layers
(<place>_<moment>_g.svg and _o.svg, _o2.svg when there are near objects), <place>_cfg.json and <place>_moments.json come out
well-formed and not tiny. Exit 0 and "== Engine check: ok" when all is well; otherwise every failure, and exit 1."""
import json, os, runpy, subprocess, sys, time, xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', 'data', 'regions'); OUT = os.path.join(HERE, '..', 'cache', 'render')
place = sys.argv[1] if len(sys.argv) > 1 else 'newyork'; moment = sys.argv[2] if len(sys.argv) > 2 else 'sunny'
fails = []

PLACES = runpy.run_path(os.path.join(DATA, 'places.py'))['PLACES']
folders = {d for d in os.listdir(DATA) if os.path.isdir(os.path.join(DATA, d))}
for d in sorted(folders - set(PLACES)): fails.append(f'data/regions/{d} has no entry in data/regions/places.py')
unfetched = sorted(k for k in PLACES if not os.path.exists(os.path.join(DATA, k, 'raw.json')))
print(f'places {len(PLACES)}, fetched {len(PLACES) - len(unfetched)}' + (f', not fetched yet: {", ".join(unfetched)}' if unfetched else ''))
if place not in PLACES: fails.append(f'{place}: not in places.py')
elif '' not in PLACES[place].get('views', {}): fails.append(f'{place}: no overview camera, cannot render as a region')
elif place in unfetched: fails.append(f'{place}: no saved data; run fetch/regions.py {place} first')

if not fails:
    os.makedirs(OUT, exist_ok=True); t0 = time.time()
    r = subprocess.run([sys.executable, os.path.join(HERE, 'regions.py'), place, moment], capture_output=True, text=True, timeout=600)
    dt = time.time() - t0
    if r.returncode: fails.append(f'render exited {r.returncode}:\n' + (r.stderr or r.stdout)[-1500:])
    else:
        print(f'rendered {place} {moment} in {dt:.1f}s')
        want = [(f'{place}_{moment}_g.svg', 20000), (f'{place}_{moment}_o.svg', 20000), (f'{place}_cfg.json', 50), (f'{place}_moments.json', 50)]
        if os.path.exists(os.path.join(OUT, f'{place}_{moment}_o2.svg')): want.append((f'{place}_{moment}_o2.svg', 1000))
        for f, least in want:
            p = os.path.join(OUT, f)
            if not os.path.exists(p): fails.append(f'{f}: not written'); continue
            size = os.path.getsize(p)
            if size < least: fails.append(f'{f}: only {size} bytes')
            try:
                if f.endswith('.svg'): ET.parse(p)
                else: json.load(open(p))
            except Exception as e: fails.append(f'{f}: not well-formed: {str(e)[:120]}')
            else: print(f'  {f:32s} {size // 1024:6d} KB ok')

for f in fails: print('  FAIL', f)
print('== Engine check:', 'ok' if not fails else f'{len(fails)} failed')
sys.exit(1 if fails else 0)
