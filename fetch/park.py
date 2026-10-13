#!/usr/bin/env python3
"""A theme park's rides, waits and hours from the open park feeds, for the Theme park screen.
- park.fan (api.park.fan, v4): per ride its height limit, single rider, indoor or outdoor, the last 30 days of recorded waits
  (the 90th percentile of each 15-minute slot), typical waits by weekday over a year, the ride's profile.
- ThemeParks.wiki (api.themeparks.wiki, v1): the open-source live feed: rides, shows and restaurants with their places, opening hours.
- Queue-Times (queue-times.com): the free live feed, by land (credit: 'Powered by Queue-Times.com').
python3 fetch/park.py efteling  ->  data/park/efteling/{pf_park.json, pf/<slug>.json, tpw_*.json, qt_*.json}"""
import json, os, sys, time, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
UA = 'AtlasConcept/1.0 (design prototype; one-off reads)'
PARKS = {'efteling': dict(pf='/v1/parks/europe/netherlands/kaatsheuvel/efteling', tpw='30713cf6-69a9-47c9-a505-52bb965f01be', qt=160)}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': 'application/json'}), timeout=60) as r: return json.load(r)

if __name__ == '__main__':
    name = sys.argv[1]; P = PARKS[name]; d = os.path.join(HERE, '..', 'data', 'park', name); os.makedirs(os.path.join(d, 'pf'), exist_ok=True)
    park = get('https://api.park.fan' + P['pf']); json.dump(park, open(os.path.join(d, 'pf_park.json'), 'w'))
    for a in park['attractions']:
        f = os.path.join(d, 'pf', a['slug'] + '.json')
        if os.path.exists(f): continue
        try: json.dump(get('https://api.park.fan' + a['url']), open(f, 'w'))
        except Exception as ex: print('  skip', a['slug'], ex)
        time.sleep(1.5)
    for k in ('children', 'live', 'schedule'):
        json.dump(get('https://api.themeparks.wiki/v1/entity/%s/%s' % (P['tpw'], k)), open(os.path.join(d, 'tpw_%s.json' % k), 'w'))
    json.dump(get('https://queue-times.com/parks/%d/queue_times.json' % P['qt']), open(os.path.join(d, 'qt_live.json'), 'w'))
    print(name, len(park['attractions']), 'rides', len(os.listdir(os.path.join(d, 'pf'))), 'details')
