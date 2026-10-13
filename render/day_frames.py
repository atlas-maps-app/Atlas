"""The living map at the Westerkerk through one real day, Thursday 1 October 2026: one render per moment, each from that
hour's observed weather and the real sun (amsterdam.weather_theme), in the kit's two stacked layers (ground, objects).
At night only mapped street lamps, lit=yes streets and places open by their opening_hours give light.
ATLAS_KIT=1 python3 day_frames.py 0730,2100,...      -> out/day/ams_day_<hhmm>_g.svg / _o.svg"""
import json, os, sys, time
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
os.environ['ATLAS_KIT'] = '1'; os.environ['ATLAS_DAYLIGHT'] = '1'
import amsterdam as A
S = A.load()
# the observed hours (Open-Meteo archive), the same record the day's screens and the plan's sky strip read
A.WXD = json.load(open(os.path.join(HERE, '..', 'data', 'wx', 'ams_observed.json')))['hourly']
cfg = json.load(open(os.path.join(HERE, '..', 'cache', 'render', 'ams_cfg.json')))
TAG = ''
if len(sys.argv) > 2: cfg.update(json.loads(sys.argv[2])); TAG = '_' + sys.argv[3]          # another camera: its own cfg and a tag in the file names
for hm in sys.argv[1].split(','):
    t0 = time.time(); name = 'day%s' % hm
    date = (2026, 10, 2) if hm.startswith('+') else (2026, 10, 1)          # +0100: the night into Friday
    hm = hm.lstrip('+')
    A.THEMES[name] = A.weather_theme(name, date, (int(hm[:2]), int(hm[2:])))
    if date[2] == 2: hm = 'fri' + hm
    (g, o), cam, info = A.render(S, name, cfg, None, layer='split')
    if isinstance(o, tuple): o = o[0]
    open(os.path.join(HERE, '..', 'cache', 'render', 'day', 'ams_day_%s%s_g.svg' % (hm, TAG)), 'w').write(g)
    open(os.path.join(HERE, '..', 'cache', 'render', 'day', 'ams_day_%s%s_o.svg' % (hm, TAG)), 'w').write(o)
    if TAG: json.dump(cfg, open(os.path.join(HERE, '..', 'cache', 'render', 'day', 'cfg%s.json' % TAG), 'w'))
    th = A.THEMES[name]
    print(hm, 'night' if th['night'] else 'day', 'cloud %.0f%%' % (th['wx']['cloud'] * 100), 'rain %.1f' % th['wx']['rain'], 'ground %d KB objects %d KB' % (len(g) // 1024, len(o) // 1024), '%.0fs' % (time.time() - t0), flush=True)
