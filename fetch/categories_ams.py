"""Every category around the Westerkerk: each mapped place in the living map's extract (about 815 x 730 m), sorted into
the plan's categories by its OpenStreetMap tags, with its screen position on the living map's camera.
Writes cache/render/ams_categories.json. The nearest place of each category that has none here comes from
fetch/nearest.py (data/ams/nearest.json), run separately because it asks Overpass about a wider area.
python3 categories_ams.py"""
import json, math, os, sys, collections
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'render'))
from city import Camera
LAT0, LON0 = 52.3746, 4.8840          # Westerkerk, as render/amsterdam.py
KX = 111320 * math.cos(math.radians(LAT0)); KZ = 110540
def XZ(lat, lon): return ((lon - LON0) * KX, -(lat - LAT0) * KZ)
cfg = json.load(open(os.path.join(HERE, '..', 'cache', 'render', 'ams_cfg.json')))
cam = Camera(cfg['target'], cfg['yaw'], cfg['pitch'], cfg['dist'], cfg['fov'], cfg['W'], cfg['H'], cx=cfg.get('cx'), cy=cfg.get('cy'))
raw = json.load(open(os.path.join(HERE, '..', 'data', 'ams', 'raw.json')))['elements']
S, N, W, E = 52.3712004, 52.3778, 4.8785007, 4.8904995          # the extract's own edges

def any_of(t, k, *vals): return t.get(k) in vals
# the plan's categories (store collection 'categories'), plus eating; first match wins, in this order
def cat_of(t):
    a, s, tr, l = t.get('amenity'), t.get('shop'), t.get('tourism'), t.get('leisure')
    if a == 'parking_space' or t.get('disused:shop') or s == 'vacant' or t.get('emergency') == 'siren': return None
    if a in ('restaurant', 'cafe', 'fast_food', 'ice_cream', 'food_court'): return 'eat'
    if a in ('bar', 'pub', 'biergarten', 'nightclub') or s in ('alcohol', 'wine', 'beverages'): return 'c14'
    if tr in ('museum', 'gallery') or s == 'art': return 'c26'
    if tr in ('hotel', 'hostel', 'guest_house', 'apartment', 'motel'): return 'c13'
    if a == 'library': return 'c00'
    if a in ('school', 'college', 'university', 'kindergarten', 'language_school', 'music_school', 'dancing_school'): return 'c01'
    if (t.get('sport') == 'skateboard' and l in ('pitch', 'skatepark')) or l in ('ice_rink', 'skatepark'): return 'c03'
    if t.get('sport') == 'climbing' and l in ('sports_centre', 'pitch') or l == 'high_ropes_course': return 'c47'
    if l in ('pitch', 'sports_centre', 'track') and t.get('sport') not in ('fitness',): return 'c02'
    if l == 'stadium': return 'c48'
    if l == 'fitness_centre' or (t.get('sport') == 'fitness' and not s): return 'c04'
    if l in ('swimming_pool', 'water_park') and t.get('access') not in ('private', 'customers'): return 'c05'
    if l == 'playground': return 'c06'
    if l in ('amusement_arcade', 'escape_game') or s in ('games', 'video_games'): return 'c07'
    if a == 'cinema': return 'c08'
    if tr == 'zoo': return 'c09'
    if tr == 'theme_park': return 'c10'
    if tr in ('camp_site', 'caravan_site'): return 'c11'
    if t.get('boundary') in ('protected_area', 'national_park') or l == 'nature_reserve':
        pc = t.get('protect_class', '')
        return 'c12' if (t.get('boundary') == 'national_park' or l == 'nature_reserve' or pc in ('1', '1a', '1b', '2', '3', '4', '5', '6')) else 'heritage'
    if a in ('hospital', 'clinic', 'doctors', 'dentist', 'pharmacy') or t.get('healthcare') or s in ('chemist', 'medical_supply', 'hearing_aids', 'optician') and t.get('healthcare'): return 'c15'
    if a in ('bank', 'atm', 'bureau_de_change', 'money_transfer'): return 'c16'
    if a in ('post_office', 'parcel_locker', 'post_box') or t.get('post_office'): return 'c17'
    if s in ('laundry', 'dry_cleaning'): return 'c18'
    if a == 'fuel': return 'c20'
    if a in ('parking', 'motorcycle_parking') : return 'c21'
    if a == 'toilets': return 'c22'
    if a in ('recycling', 'waste_disposal'): return 'c23'
    if a == 'bus_station' or t.get('railway') == 'tram_stop' or t.get('highway') == 'bus_stop' or (t.get('public_transport') == 'stop_position'): return 'c24'
    if t.get('aeroway') in ('aerodrome', 'terminal'): return 'c25'
    if a == 'place_of_worship': return 'c27'
    if tr == 'viewpoint': return 'c28'
    if a in ('social_facility', 'food_bank', 'soup_kitchen') or t.get('social_facility'): return 'c30'
    if a == 'shower': return 'c31'
    if t.get('emergency') == 'defibrillator': return 'c32'
    if s in ('car_repair', 'car', 'tyres') or a == 'car_wash': return 'c33'
    if t.get('boundary') == 'low_emission_zone': return 'c34'
    if a == 'vending_machine': return 'c35'
    if a == 'lost_property_office': return 'c36'
    if a == 'coworking_space' or t.get('office') == 'coworking': return 'c37'
    if a in ('bench', 'waste_basket', 'bicycle_parking', 'drinking_water', 'clock', 'telephone') or l == 'picnic_table': return 'c38'
    # found missing from the plan's list on 6 October: 111 mapped places here fitted none of its 39 categories
    if a == 'charging_station': return 'c39'
    if tr in ('attraction', 'artwork', 'yes') or t.get('historic') in ('memorial', 'monument', 'yes', 'building', 'castle'): return 'c40'
    if a in ('theatre', 'arts_centre', 'events_venue', 'community_centre', 'concert_hall', 'art_gallery'): return 'c41'
    if a == 'public_bookcase': return 'c42'
    if l in ('garden', 'park'): return 'c43'
    if t.get('office') or t.get('craft'): return 'c44'
    if a in ('bicycle_rental', 'taxi', 'boat_rental', 'car_sharing'): return 'c45'
    if l in ('sauna', 'spa'): return 'c46'
    if s: return 'c19'
    return 'other' if any(k in t for k in ('amenity', 'tourism', 'leisure', 'healthcare', 'club')) or t.get('emergency') in ('first_aid_kit', 'yes') else None
# outdoor seating is a property, not a kind: counted on top of each place's own category
def kind_of(t):
    for k in ('amenity', 'shop', 'tourism', 'leisure', 'office', 'craft', 'healthcare', 'emergency', 'historic', 'railway', 'highway', 'public_transport', 'boundary', 'aeroway', 'club', 'man_made'):
        if k in t: return '%s=%s' % (k, t[k])

def centre(e):
    if e['type'] == 'node': return e['lat'], e['lon']
    g = e.get('geometry')
    if g:
        pts = [(p['lat'], p['lon']) for p in g if p]
        return sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)
    b = e.get('bounds')
    if b: return (b['minlat'] + b['maxlat']) / 2, (b['minlon'] + b['maxlon']) / 2
    return None
def screen(lat, lon, y=0.0):
    x, z = XZ(lat, lon); c = cam.cam((x, y, z))
    if c[2] < 2: return None
    p = cam.pc(c)
    return [round(p[0], 1), round(p[1], 1)] if -20 <= p[0] <= 432 and -20 <= p[1] <= 935 else None

places = []; kinds = collections.Counter(); seen_stops = set()
for e in raw:
    t = e.get('tags')
    if not t: continue
    c = cat_of(t)
    if not c: continue
    ll = centre(e)
    if not ll: continue
    big = t.get('boundary') in ('protected_area', 'national_park', 'low_emission_zone')
    if not big and not (S <= ll[0] <= N and W <= ll[1] <= E): continue
    if c == 'c40' and t.get('name'):          # a canal tagged as a sight in eight pieces is one sight
        if ('c40', t['name']) in seen_stops: continue
        seen_stops.add(('c40', t['name']))
    if c == 'c24':          # one stop per name and mode, not every platform and stop position
        key = (t.get('name'), 'tram' if (t.get('tram') == 'yes' or t.get('railway') == 'tram_stop') else 'bus')
        if key in seen_stops: continue
        seen_stops.add(key)
    x, z = XZ(*ll)
    p = dict(id='%s/%d' % (e['type'], e['id']), cat=c, kind=kind_of(t), name=t.get('name') or t.get('brand') or t.get('operator'),
             lat=round(ll[0], 7), lon=round(ll[1], 7), d=round(math.hypot(x, z)), at=None if big else screen(*ll), tags=t)
    places.append(p)
    if not big: kinds[p['kind']] += 1

# walking metres from "you" on the Grachtenvlinder footbridge (node 10734715940, as the Map and Route screens), on the mapped walkable network
import heapq
from real import open_at
WALK = {'footway', 'pedestrian', 'residential', 'living_street', 'path', 'steps', 'tertiary', 'unclassified', 'service'}          # the same network as routes_ams.py
adj = collections.defaultdict(dict); XY = {}
for e in raw:
    t = e.get('tags', {})
    if e['type'] != 'way' or t.get('highway') not in WALK or t.get('foot') == 'no' or t.get('access') == 'no': continue
    ns, g = e['nodes'], e['geometry']
    for i, n in enumerate(ns): XY[n] = XZ(g[i]['lat'], g[i]['lon'])
    for a, b in zip(ns, ns[1:]):
        adj[a][b] = adj[b][a] = math.dist(XY[a], XY[b])
SRC = 10734715940
dist = {SRC: 0.0}; pq = [(0.0, SRC)]
while pq:
    d, u = heapq.heappop(pq)
    if d > dist.get(u, 1e18): continue
    for v, Lm in adj[u].items():
        if d + Lm < dist.get(v, 1e18): dist[v] = d + Lm; heapq.heappush(pq, (d + Lm, v))
GRID = collections.defaultdict(list)
for n in dist: GRID[(int(XY[n][0] // 25), int(XY[n][1] // 25))].append(n)
def walk_to(x, z, snap=False):
    best = None
    for r in range(0, 6):
        for i in range(-r, r + 1):
            for j in range(-r, r + 1):
                if max(abs(i), abs(j)) != r: continue
                for n in GRID.get((int(x // 25) + i, int(z // 25) + j), ()):
                    sd = math.dist(XY[n], (x, z)); c = dist[n] + (sd if snap else 0)          # to the nearest walkable point; to a door, the last steps too, as the Route screen measures
                    if best is None or sd < best[1]: best = (c, sd)
        if best: break
    return round(best[0]) if best else None
def day_spans(oh, day='Th', date=(2026, 10, 1)):
    """the hours a place is open on that day, read by asking open_at every quarter hour; None when the hours are not understood"""
    if open_at(oh, day, (12, 0), date) is None: return None
    spans, start = [], None
    for q in range(0, 24 * 4 + 1):
        st = q < 96 and open_at(oh, day, (q // 4, q % 4 * 15), date)
        if st and start is None: start = q
        if not st and start is not None: spans.append([start / 4, q / 4]); start = None
    return spans
NODE = {e['id']: e for e in raw if e['type'] == 'node'}
ROUTE = json.load(open(os.path.join(HERE, '..', 'cache', 'render', 'ams_routes.json')))
WAYS = {e['id']: e for e in raw if e['type'] == 'way'}
def door(p):
    """a mapped entrance on the place's outline, the main one first"""
    typ, i = p['id'].split('/')
    if typ != 'way' or int(i) not in WAYS: return None
    ents = [NODE[n] for n in WAYS[int(i)]['nodes'] if n in NODE and (NODE[n].get('tags') or {}).get('entrance')]
    ents.sort(key=lambda n: n['tags']['entrance'] != 'main')
    return ents[0] if ents else None
for p in places:
    if p['cat'] in ('heritage', 'c34'): continue
    dn = door(p)
    p['walk'] = walk_to(*XZ(dn['lat'], dn['lon'])) if dn else walk_to(*XZ(p['lat'], p['lon']))
    if dn: p['door'] = dict(id=dn['id'], entrance=dn['tags']['entrance'], at=screen(dn['lat'], dn['lon']))
    if dn and dn['id'] == ROUTE['entrance']['node']: p['walk'] = ROUTE['routes']['fast']['len']          # the Route screen walked this one: say the same
    oh = p['tags'].get('opening_hours')
    p['open'] = open_at(oh, 'Th', (16, 0), (2026, 10, 1)) if oh else None
    p['spans'] = day_spans(oh) if oh else None

here = [p for p in places if p['cat'] not in ('heritage', 'c34') and p['at'] is not None or p['cat'] not in ('heritage', 'c34')]
count = collections.Counter(p['cat'] for p in places if p['cat'] not in ('heritage', 'c34', 'c12'))
outdoor = [p for p in places if p['tags'].get('outdoor_seating') == 'yes']
onscreen = [p for p in places if p['at']]
out = dict(
    area=dict(s=S, w=W, n=N, e=E, m=[round((E - W) * KX), round((N - S) * KZ)]),
    total=sum(count.values()), kinds=len(kinds), onscreen=len(onscreen), counts=dict(count.most_common()),
    outdoor_seating=len(outdoor), heritage=[dict(name=p['name'], protect_class=p['tags'].get('protect_class'), id=p['id']) for p in places if p['cat'] == 'heritage'],
    lez=[dict(name=p['name'], id=p['id']) for p in places if p['cat'] == 'c34'],
    top_kinds=kinds.most_common(30), places=places)
# the nearest of each category with none here, from fetch/nearest.py, as the crow flies from the Westerkerk
NP = os.path.join(HERE, '..', 'data', 'ams', 'nearest.json')
if os.path.exists(NP):
    NJ = json.load(open(NP)); best = {}
    for e in NJ['near']:
        t = e.get('tags') or {}; c = cat_of(t)
        if not c or c in count: continue
        ll = (e['lat'], e['lon']) if 'lat' in e else ((e['center']['lat'], e['center']['lon']) if 'center' in e else None)
        if not ll: continue
        if c == 'c25' and not t.get('iata'): continue
        if c == 'c31' and t.get('access') in ('customers', 'private', 'no'): continue          # a shower for guests only is not one you can use
        if c == 'c05' and t.get('access') in ('customers', 'private', 'no'): continue
        x, z = XZ(*ll); d = math.hypot(x, z); rank = d
        # a public lending library before a research one; a shelter or food bank before an office of a care organisation
        if c == 'c00' and 'OBA' not in ((t.get('operator') or '') + (t.get('name') or '')): rank += 100000
        if c == 'c30' and t.get('social_facility') not in ('shelter', 'food_bank', 'soup_kitchen'): rank += 100000
        if c == 'c09' and t.get('zoo') == 'petting_zoo': c = 'c09f'          # petting farms apart from the zoo itself
        if c not in best or rank < best[c]['rank']:
            best[c] = dict(id='%s/%d' % (e['type'], e['id']), name=t.get('name') or t.get('brand') or t.get('operator'), d=round(d), rank=rank, kind=kind_of(t),
                           bearing=round(math.degrees(math.atan2(x, -z)) % 360), lat=round(ll[0], 6), lon=round(ll[1], 6),
                           tags={k: v for k, v in t.items() if k in ('opening_hours', 'access', 'fee', 'operator', 'brand', 'addr:street', 'addr:housenumber', 'iata', 'protect_class', 'social_facility', 'social_facility:for', 'sport', 'website', 'zoo')})
    for b in best.values(): b.pop('rank')
    out['nearest'] = best; out['nearest_fetched'] = NJ['fetched']
    out['inside'] = [z['tags'].get('name') for z in NJ['inside'] if z['tags'].get('boundary') in ('low_emission_zone', 'protected_area')]
json.dump(out, open(os.path.join(HERE, '..', 'cache', 'render', 'ams_categories.json'), 'w'), ensure_ascii=False)
print('%d places of %d kinds in %d x %d m, %d on screen; outdoor seating %d' % (out['total'], out['kinds'], out['area']['m'][0], out['area']['m'][1], len(onscreen), len(outdoor)))
print(count.most_common())
print('heritage', out['heritage'], 'inside', out.get('inside'))
for c, b in sorted(out.get('nearest', {}).items(), key=lambda kv: kv[1]['d']): print('  nearest', c, b['name'], b['kind'], b['d'], 'm', b['tags'])
