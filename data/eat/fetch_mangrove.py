"""Mangrove's open reviews for every place to eat and drink within 10 minutes of the Westermarkt (read only).
Writes eat/mangrove.json {osm id: {summary, n}}."""
import json, os, sys, time
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, os.path.join(HERE, '..', '..'))
from reviews import mangrove
P = json.load(open(os.path.join(HERE, 'screen.json')))['places']
out = {}
for p in P:
    if p['min'] > 10 or not p['name'] or p['kind'] not in ('restaurant', 'cafe', 'bar', 'pub', 'fast_food', 'ice_cream'): continue
    try:
        s = mangrove.summary(p['name'], p['lat'], p['lon'])
        out[p['id']] = s
        if s: print(p['name'], s, flush=True)
    except Exception as e:
        out[p['id']] = {'error': str(e)}
    time.sleep(0.35)
json.dump(out, open(os.path.join(HERE, 'mangrove.json'), 'w'), ensure_ascii=False)
print('done', len(out), sum(1 for v in out.values() if v and not v.get('error')))
