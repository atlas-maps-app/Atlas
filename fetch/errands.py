#!/usr/bin/env python3
"""Everyday errands around the Westerkerk, from Overpass (the mail.ru instance answers when overpass-api.de is down):
post boxes with their collection times, post offices and partner points, parcel lockers; laundrettes and dry cleaners;
car repair, tyres and washes; and for people without a home or between places, every social facility, food bank,
shower, drinking tap, free toilet and luggage locker within reach.
python3 fetch/errands.py  ->  data/errands/raw.json"""
import json, os, time, urllib.parse, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
UA = 'AtlasConcept/1.0 (design prototype; one-off extracts)'
LAT, LON = 52.3746, 4.8840
def q(sel): return '[out:json][timeout:150];(%s);out center tags;' % sel
def around(kv, r):
    k, _, v = kv.partition('='); return 'nwr["%s"%s](around:%d,%s,%s);' % (k, ('="%s"' % v) if v else '', r, LAT, LON)
PARTS = {
 'post': ''.join(around(kv, 1500) for kv in ('amenity=post_box', 'amenity=post_office', 'amenity=parcel_locker', 'post_office')),
 'laundry': ''.join(around(kv, 2000) for kv in ('shop=laundry', 'shop=dry_cleaning')),
 'car': ''.join(around(kv, 3000) for kv in ('shop=car_repair', 'shop=tyres', 'amenity=car_wash', 'shop=car')),
 'support': ''.join(around(kv, 3000) for kv in ('amenity=social_facility', 'social_facility', 'amenity=food_bank', 'amenity=shower', 'amenity=luggage_locker', 'amenity=locker', 'shop=storage_rental', 'amenity=drinking_water', 'amenity=soup_kitchen')),
 'toilets': around('amenity=toilets', 1500),
}
out = dict(fetched=time.strftime('%Y-%m-%d'), at=[LAT, LON])
for name, sel in PARTS.items():
    for host in ('https://maps.mail.ru/osm/tools/overpass', 'https://overpass-api.de', 'https://overpass.private.coffee'):
        try:
            req = urllib.request.Request(host + '/api/interpreter', data=urllib.parse.urlencode({'data': q(sel)}).encode(), headers={'User-Agent': UA})
            out[name] = json.load(urllib.request.urlopen(req, timeout=180))['elements']; break
        except Exception as ex:
            print('  retry', host, ex); time.sleep(10)
    print(name, len(out.get(name, [])))
    time.sleep(3)
json.dump(out, open(os.path.join(HERE, '..', 'data', 'errands', 'raw.json'), 'w'), ensure_ascii=False)
