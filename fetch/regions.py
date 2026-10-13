#!/usr/bin/env python3
"""OpenStreetMap extracts and real hourly weather for the regions beyond Amsterdam.
python3 fetch/regions.py [name ...]  ->  regions/<name>/raw.json, regions/<name>/wx.json"""
import json, os, runpy, sys, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
UA = 'AtlasConcept/1.0 (design prototype; one-off extracts)'
PLACES = runpy.run_path(os.path.join(HERE, '..', 'data', 'regions', 'places.py'))['PLACES']     # one table: where each place is, and its views
REGIONS = {k: {f: v for f, v in p.items() if f != 'views'} for k, p in PLACES.items()}

def overpass(bbox):
    s, w, n, e = bbox
    q = ('[out:json][timeout:240][bbox:%s,%s,%s,%s];'
         '(node(if:count_tags()>0);way;relation[type=multipolygon];relation[boundary~"^(national_park|protected_area)$"];);'
         'out geom;') % (s, w, n, e)
    for attempt, host in enumerate(['https://overpass-api.de', 'https://overpass.private.coffee', 'https://overpass.kumi.systems', 'https://overpass-api.de']):
        req = urllib.request.Request(host + '/api/interpreter', data=urllib.parse.urlencode({'data': q}).encode(), headers={'User-Agent': UA})
        try:
            with urllib.request.urlopen(req, timeout=300) as r: return json.load(r)
        except Exception as ex:
            print('  retry after', host, ex); time.sleep(15 * (attempt + 1))
    raise SystemExit('overpass failed')

def weather(lat, lon, tz='Europe/Amsterdam', dates=('2026-09-30', '2026-10-01')):
    vars_ = 'temperature_2m,relative_humidity_2m,cloud_cover,cloud_cover_low,precipitation,rain,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,visibility,is_day'
    url = ('https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&hourly=%s&timezone=%s&start_date=%s&end_date=%s' % (lat, lon, vars_, urllib.parse.quote(tz, safe=''), dates[0], dates[1]))
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60) as r: return json.load(r)

def sea_level(lat, lon):
    # the sea surface with its tide, hour by hour (Open-Meteo marine model, metres against mean sea level, which is about NAP)
    url = ('https://marine-api.open-meteo.com/v1/marine?latitude=%s&longitude=%s&hourly=sea_level_height_msl&timezone=Europe%%2FAmsterdam&start_date=2026-09-30&end_date=2026-10-01' % (lat, lon))
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60) as r: return json.load(r)

if __name__ == '__main__':
    if sys.argv[1:2] == ['sea']:          # python3 fetch/regions.py sea port
        for name in sys.argv[2:]:
            lat, lon = REGIONS[name]['origin']; d = sea_level(lat, lon)
            json.dump(d, open(os.path.join(HERE, '..', 'data', 'regions', name, 'sea.json'), 'w'))
            print(name, 'sea cell', d['latitude'], d['longitude'], len(d['hourly']['time']), 'hours')
        raise SystemExit
    for name in (sys.argv[1:] or list(REGIONS)):
        R = REGIONS[name]; d = os.path.join(HERE, '..', 'data', 'regions', name); os.makedirs(d, exist_ok=True)
        t0 = time.time(); raw = overpass(R['bbox'])
        json.dump(raw, open(os.path.join(d, 'raw.json'), 'w'))
        wx = weather(*R['origin'], tz=R.get('tz', 'Europe/Amsterdam'), dates=R.get('wx_dates', ('2026-09-30', '2026-10-01'))); json.dump(wx, open(os.path.join(d, 'wx.json'), 'w'))
        json.dump(R, open(os.path.join(d, 'region.json'), 'w'))
        print(name, len(raw['elements']), 'elements', os.path.getsize(os.path.join(d, 'raw.json')) // 1024, 'KB', '%.0fs' % (time.time() - t0), 'wx hours', len(wx['hourly']['time']))
        time.sleep(5)
