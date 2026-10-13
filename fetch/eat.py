"""Every place to eat and drink, every food shop and every market within a radius of the Westermarkt, from OpenStreetMap
(Overpass; the mail.ru mirror answered on 6 Oct). Writes data/eat/raw.json.
python3 fetch/eat.py [radius_m]"""
import sys, json, urllib.request, urllib.parse, os
R = int(sys.argv[1]) if len(sys.argv) > 1 else 1400
LAT, LON = 52.37463, 4.88363          # Westermarkt, in front of the Westerkerk
q = ('[out:json][timeout:180];('
     'nwr["amenity"~"^(restaurant|cafe|bar|pub|fast_food|ice_cream|biergarten|food_court|marketplace)$"](around:%d,%s,%s);'
     'nwr["shop"~"^(bakery|pastry|confectionery|chocolate|deli|cheese|coffee|tea|ice_cream|seafood|butcher|greengrocer|wine)$"](around:%d,%s,%s);'
     ');out center tags;') % (R, LAT, LON, R, LAT, LON)
for url in ('https://maps.mail.ru/osm/tools/overpass/api/interpreter', 'https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, data=urllib.parse.urlencode({'data': q}).encode(), headers={'User-Agent': 'atlas-living-map/0.1'}), timeout=240)
        d = json.load(r); d['radius'] = R; d['centre'] = [LAT, LON]
        json.dump(d, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'eat', 'raw.json'), 'w')); print(url, len(d['elements'])); break
    except Exception as ex: print(url, ex)
