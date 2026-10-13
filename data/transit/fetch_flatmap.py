"""Water, parks and building outlines in a box, for flat map sketches (Overpass).
python3 fetch_flatmap.py <south> <west> <north> <east> <out.json> [buildings]"""
import sys, json, urllib.request, urllib.parse
s, w, n, e, out = sys.argv[1:6]; bb = '(%s,%s,%s,%s)' % (s, w, n, e)
q = '[out:json][timeout:180];(way["natural"="water"]%s;relation["natural"="water"]%s;way["waterway"~"canal|river"]%s;way["leisure"="park"]%s;way["landuse"~"grass|recreation_ground"]%s;%s);out geom tags;' % (
    bb, bb, bb, bb, bb, 'way["building"]%s' % bb if len(sys.argv) > 6 else '')
for url in ('https://overpass-api.de/api/interpreter', 'https://maps.mail.ru/osm/tools/overpass/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, data=urllib.parse.urlencode({'data': q}).encode(), headers={'User-Agent': 'atlas-living-map/0.1'}), timeout=240)
        d = json.load(r); json.dump(d, open(out, 'w')); print(url, len(d['elements'])); break
    except Exception as ex: print(url, ex)
