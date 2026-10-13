#!/usr/bin/env python3
"""The nearest place of every category that has none in the living map's extract around the Westerkerk, from Overpass,
classified with fetch/categories_ams.py's own rules. Also which zones the Westerkerk lies inside (low-emission zone).
python3 fetch/nearest.py  ->  data/ams/nearest.json"""
import json, math, os, sys, time, urllib.parse, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
UA = 'AtlasConcept/1.0 (design prototype; one-off extracts)'
LAT, LON = 52.3746, 4.8840
NEAR = ['amenity=library', 'leisure=ice_rink', 'sport=skateboard', 'leisure=swimming_pool', 'leisure=water_park', 'amenity=cinema',
        'amenity=fuel', 'amenity=social_facility', 'amenity=food_bank', 'social_facility=soup_kitchen', 'social_facility=shelter', 'amenity=shower',
        'shop=car_repair', 'amenity=car_wash', 'amenity=lost_property_office', 'amenity=coworking_space', 'office=coworking',
        'shop=laundry', 'tourism=viewpoint', 'leisure=playground', 'amenity=toilets', 'leisure=pitch', 'amenity=place_of_worship']
FAR = ['tourism=zoo', 'tourism=theme_park', 'tourism=camp_site', 'aeroway=aerodrome', 'leisure=nature_reserve', 'boundary=national_park']
def sel(kv, r):
    k, v = kv.split('='); return 'nwr["%s"="%s"](around:%d,%s,%s);' % (k, v, r, LAT, LON)
def overpass(q):
    for attempt, host in enumerate(['https://maps.mail.ru/osm/tools/overpass', 'https://overpass-api.de', 'https://overpass.private.coffee']):
        req = urllib.request.Request(host + '/api/interpreter', data=urllib.parse.urlencode({'data': q}).encode(), headers={'User-Agent': UA})
        try:
            with urllib.request.urlopen(req, timeout=200) as r: return json.load(r)
        except Exception as ex:
            print('  retry after', host, ex); time.sleep(10 * (attempt + 1))
    raise SystemExit('overpass failed')
near = []
for name, part in [('near', ''.join(sel(kv, 3000) for kv in NEAR)),
                   ('zoo', sel('tourism=zoo', 12000)), ('camp', sel('tourism=camp_site', 12000)),
                   ('park', sel('tourism=theme_park', 40000)), ('air', 'nwr["aeroway"="aerodrome"]["iata"](around:30000,%s,%s);' % (LAT, LON)),
                   ('nature', sel('leisure=nature_reserve', 12000) + sel('boundary=national_park', 40000))]:
    els = overpass('[out:json][timeout:120];(' + part + ');out center tags;')['elements']
    print(name, len(els)); near += els; time.sleep(3)
q2 = '[out:json][timeout:60];is_in(%s,%s)->.a;(area.a[boundary];area.a[admin_level];);out tags;' % (LAT, LON)
zones = overpass(q2)['elements']
json.dump(dict(at=[LAT, LON], fetched=time.strftime('%Y-%m-%d %H:%M'), near=near, inside=zones), open(os.path.join(HERE, '..', 'data', 'ams', 'nearest.json'), 'w'), ensure_ascii=False)
print('inside:', [(z['tags'].get('name'), z['tags'].get('boundary'), z['tags'].get('admin_level')) for z in zones])
