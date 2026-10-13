"""Titles, visible tags and guessed values for the tap-anything inspector.
Landmarks, streets and water go by name; chains go by their mapped brand; independent businesses go by kind,
with their name and contact tags held back in this concept."""
import math

BUSINESS_AMENITY = {'restaurant', 'cafe', 'bar', 'pub', 'fast_food', 'ice_cream', 'bicycle_rental', 'car_rental', 'pharmacy', 'bank', 'doctors',
                    'dentist', 'clinic', 'nightclub', 'cinema', 'car_sharing', 'veterinary', 'coworking_space', 'language_school', 'money_transfer'}
BUSINESS_TOURISM = {'hotel', 'guest_house', 'hostel', 'apartment', 'gallery'}
HIDE_PREFIX = ('name', 'alt_name', 'official_name', 'old_name', 'short_name', 'brand', 'operator', 'website', 'contact:', 'phone', 'email', 'fax', 'url',
               'facebook', 'instagram', 'twitter', 'wikidata', 'wikipedia', 'image', 'description', 'note', 'opening_hours:url', 'menu', 'payment:website', 'ref:vatin', 'ref:kvk')

def is_business(t):
    return ('shop' in t or 'office' in t or 'craft' in t or t.get('amenity') in BUSINESS_AMENITY or t.get('tourism') in BUSINESS_TOURISM
            or ('healthcare' in t and t.get('amenity') != 'hospital'))

def visible_tags(t):
    """Every tag, in a stable order, except a business's identity and contact tags."""
    biz = is_business(t) and not t.get('brand')          # a chain keeps its brand and name; contacts stay back for all
    out = [(k, v) for k, v in t.items() if not ((biz and k.startswith(HIDE_PREFIX)) or (is_business(t) and k.startswith(('contact:', 'phone', 'email', 'fax', 'website', 'url', 'facebook', 'instagram'))))]
    return sorted(out, key=lambda kv: (88 if kv[0].startswith('name:') else ORDER.get(kv[0].split(':')[0], 50), kv[0]))
ORDER = {'building': 0, 'amenity': 0, 'shop': 0, 'tourism': 0, 'highway': 0, 'natural': 0, 'man_made': 0, 'leisure': 0, 'historic': 0, 'railway': 0, 'waterway': 0, 'water': 1,
         'name': 2, 'height': 3, 'min_height': 3, 'roof': 4, 'start_date': 5, 'opening_hours': 6, 'wheelchair': 7, 'addr': 8, 'source': 90, 'ref': 91, 'check_date': 92}

def nice(v): return v.replace('_', ' ').replace(';', ', ')
SPECIAL = {
    ('amenity', 'place_of_worship'): 'Place of worship', ('amenity', 'bicycle_parking'): 'Bicycle parking', ('amenity', 'waste_basket'): 'Waste basket',
    ('amenity', 'cafe'): 'Café', ('amenity', 'fast_food'): 'Takeaway', ('amenity', 'ice_cream'): 'Ice cream', ('amenity', 'bench'): 'Bench',
    ('amenity', 'recycling'): 'Recycling', ('amenity', 'toilets'): 'Toilets', ('amenity', 'drinking_water'): 'Drinking water',
    ('highway', 'street_lamp'): 'Street lamp', ('highway', 'crossing'): 'Crossing', ('highway', 'traffic_signals'): 'Traffic lights', ('highway', 'bus_stop'): 'Bus stop',
    ('highway', 'footway'): 'Footpath', ('highway', 'cycleway'): 'Cycle path', ('highway', 'pedestrian'): 'Pedestrian street', ('highway', 'residential'): 'Street',
    ('highway', 'tertiary'): 'Through street', ('highway', 'busway'): 'Bus lane', ('highway', 'steps'): 'Steps', ('highway', 'give_way'): 'Give way',
    ('railway', 'tram'): 'Tram line', ('railway', 'tram_stop'): 'Tram stop', ('public_transport', 'platform'): 'Platform', ('public_transport', 'stop_position'): 'Stop',
    ('natural', 'tree'): 'Tree', ('natural', 'water'): 'Water', ('waterway', 'canal'): 'Canal', ('barrier', 'bollard'): 'Bollard', ('barrier', 'block'): 'Block',
    ('tourism', 'museum'): 'Museum', ('tourism', 'attraction'): 'Attraction', ('tourism', 'information'): 'Information', ('tourism', 'hotel'): 'Hotel',
    ('tourism', 'gallery'): 'Gallery', ('historic', 'memorial'): 'Memorial', ('man_made', 'bridge'): 'Bridge', ('man_made', 'tower'): 'Tower',
    ('building', 'yes'): 'Building', ('building', 'apartments'): 'Apartments', ('building', 'house'): 'House', ('building', 'commercial'): 'Commercial building',
    ('building', 'retail'): 'Shops', ('building', 'houseboat'): 'Houseboat', ('building', 'church'): 'Church', ('building', 'office'): 'Offices',
    ('building:part', 'yes'): 'Part of a building', ('building:part', 'church'): 'Part of the church', ('entrance', 'yes'): 'Entrance', ('entrance', 'main'): 'Main entrance',
    ('leisure', 'park'): 'Park', ('leisure', 'playground'): 'Playground', ('place', 'square'): 'Square', ('landuse', 'grass'): 'Grass',
}
MAIN = ('amenity', 'shop', 'tourism', 'historic', 'leisure', 'man_made', 'craft', 'office', 'healthcare', 'emergency', 'railway', 'public_transport',
        'highway', 'barrier', 'natural', 'waterway', 'landuse', 'place', 'entrance', 'building:part', 'building', 'traffic_sign', 'type')
def kind(t):
    for k in MAIN:
        if k in t:
            v = t[k]
            if (k, v) in SPECIAL: return SPECIAL[(k, v)]
            if k == 'shop': return nice(v).capitalize() + ('' if v in ('supermarket', 'bakery', 'florist', 'butcher', 'kiosk', 'convenience', 'deli', 'chemist', 'optician', 'hairdresser', 'beauty') else ' shop')
            if v == 'yes': return nice(k).capitalize()
            return nice(v).capitalize()
    if 'addr:housenumber' in t: return 'Address'
    k = next(iter(t)); return nice(k).capitalize()

def title(t, typ, oid, b):
    k = kind(t)
    ref = '%s %d' % (typ, oid)
    if is_business(t) and t.get('brand'): return t['brand'], '%s · chain · %s' % (k, ref)
    if is_business(t): return k, 'independent · name held back in this concept · ' + ref
    if 'addr:housenumber' in t and 'addr:street' in t and not any(m in t for m in MAIN[:-3]):
        return '%s %s' % (t['addr:street'], t['addr:housenumber']), ('address point · ' + ref)
    bits = []
    if b is not None:
        if t.get('start_date'): bits.append('built ' + t['start_date'])
        if t.get('height'): bits.append(t['height'] + ' m')
    if t.get('name'): return t['name'], ' · '.join([k] + bits + [ref])
    return k, ' · '.join(bits + [ref])

def fmt(x): return ('%.1f' % x).rstrip('0').rstrip('.')
def building_guesses(b):
    if b.get('hidden'): return []
    g, out = b['guessed'], []
    if g.get('height'): out.append(('height', fmt(b['h'])))
    if g.get('building:levels'): out.append(('building:levels', str(b['levels'])))
    if g.get('roof:shape'): out.append(('roof:shape', b['roof']))
    if g.get('roof:height') and b['roof_h']: out.append(('roof:height', fmt(b['roof_h'])))
    if g.get('roof:orientation') and b.get('orient'): out.append(('roof:orientation', b['orient']))
    if g.get('roof:direction'):
        import amsterdam as A
        dx, dz = A.roof_dir(b); out.append(('roof:direction', str(round(math.degrees(math.atan2(dx, -dz)) % 360))))
    if g.get('building:colour') and b['col']: out.append(('building:colour', b['col'].lower()))
    if g.get('building:material') and b.get('mat_draw'): out.append(('building:material', b['mat_draw']))
    if g.get('roof:material') and b.get('rmat_draw'): out.append(('roof:material', b['rmat_draw']))
    if g.get('roof:colour') and b['rcol']: out.append(('roof:colour', b['rcol'].lower()))
    return out
def road_guesses(r):
    """At eye level the carriageway and its pavements are drawn to a width; where none is mapped, that width is a guess."""
    import closeup as CU
    out = []
    if r.get('wguess'): out.append(('width', fmt(r['w'])))
    sides, _ = CU.sidewalk_sides(r['tags'])
    t = r['tags']
    if sides and not any(t.get(k) for k in ('sidewalk:width', 'sidewalk:both:width', 'sidewalk:left:width', 'sidewalk:right:width')):
        out.append(('sidewalk:both:width' if len(sides) == 2 else ('sidewalk:left:width' if sides[0] < 0 else 'sidewalk:right:width'), '1.9'))
    return out
def tree_guesses(n):
    vals = {'height': fmt(n['h']), 'diameter_crown': fmt(n['crown']), 'leaf_type': 'broadleaved'}
    return [(k, vals[k]) for k in ('height', 'diameter_crown', 'leaf_type') if n['guessed'].get(k)]
