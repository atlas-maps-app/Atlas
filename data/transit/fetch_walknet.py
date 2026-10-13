"""Walkable OpenStreetMap ways in a box, for the walk legs of a journey (Overpass, the mail.ru mirror answered on 6 Oct).
python3 fetch_walknet.py <south> <west> <north> <east> <out.json>"""
import sys, json, urllib.request, urllib.parse
s, w, n, e, out = sys.argv[1:6]
q = '[out:json][timeout:180];way["highway"~"^(footway|pedestrian|residential|living_street|path|steps|tertiary|secondary|primary|unclassified|service|cycleway|tertiary_link|secondary_link|primary_link|track)$"](%s,%s,%s,%s);out geom tags;' % (s, w, n, e)
for url in ('https://maps.mail.ru/osm/tools/overpass/api/interpreter', 'https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, data=urllib.parse.urlencode({'data': q}).encode(), headers={'User-Agent': 'atlas-living-map/0.1'}), timeout=240)
        d = json.load(r); json.dump(d, open(out, 'w')); print(url, len(d['elements'])); break
    except Exception as ex: print(url, ex)
