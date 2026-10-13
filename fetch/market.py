#!/usr/bin/env python3
"""Market hall: what Albert Heijn sells, aisle by aisle, as Open Food Facts contributors recorded it (stores tag
'albert-heijn', sold in the Netherlands), the most scanned first, each with its front photo and Nutri-Score.
The photos are CC BY-SA (Open Food Facts contributors); the data ODbL. Prices: Open Prices, where there are any.
python3 fetch/market.py  ->  data/groceries/market.json, data/groceries/off_img/<code>.jpg"""
import json, os, time, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__)); G = os.path.join(HERE, '..', 'data', 'groceries')
UA = {'User-Agent': 'AtlasConcept/1.0 (design prototype; one-off lookups; atlas)'}
def get(u, raw=False):
    for i in range(6):
        try:
            r = urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=90).read()
            return r if raw else json.loads(r)
        except Exception as ex:
            print('  retry', ex); time.sleep(20 * (i + 1))          # the search API allows about 10 requests a minute
    return None
AISLES = [('bread', 'Bread', 'en:breads'), ('cheese', 'Cheese', 'en:cheeses'), ('dairy', 'Milk & yoghurt', 'en:dairies'),
          ('spreads', 'On your bread', 'en:spreads'), ('drinks', 'Drinks', 'en:beverages'), ('koek', 'Biscuits & cakes', 'en:biscuits-and-cakes')]
MJ = os.path.join(G, 'market.json')
out = json.load(open(MJ)) if os.path.exists(MJ) else dict(fetched='2026-10-06', aisles=[])          # resumable
done = {a['key'] for a in out['aisles']}
seen = {i['code'] for a in out['aisles'] for i in a['items']}
for key, name, cat in AISLES:
    if key in done: continue
    r = get('https://world.openfoodfacts.org/api/v2/search?countries_tags=en:netherlands&stores_tags=albert-heijn&categories_tags=%s'
            '&fields=code,product_name,product_name_nl,brands,nutriscore_grade,image_front_small_url,quantity,unique_scans_n&page_size=40&sort_by=unique_scans_n' % cat)
    items = []
    for p in r.get('products', []):
        if p['code'] in seen or not p.get('image_front_small_url') or p.get('nutriscore_grade') not in ('a', 'b', 'c', 'd', 'e'): continue
        nm = p.get('product_name_nl') or p.get('product_name')
        if not nm: continue
        if nm.split()[0].lower() in {i['name'].split()[0].lower() for i in items}: continue          # one of a kind per shelf: not four waters
        img = get(p['image_front_small_url'], raw=True)
        if not img: continue
        open(os.path.join(G, 'off_img', p['code'] + '.jpg'), 'wb').write(img)
        items.append(dict(code=p['code'], name=nm.strip(), brand=(p.get('brands') or '').split(',')[0].strip(), grade=p['nutriscore_grade'], qty=p.get('quantity'),
                          img=p['image_front_small_url'], bytes=len(img)))
        seen.add(p['code'])
        if len(items) == 4: break
        time.sleep(0.5)
    out['aisles'].append(dict(key=key, name=name, cat=cat, total=r.get('count'), items=items))
    print(name, r.get('count'), [i['name'] for i in items])
    json.dump(out, open(MJ, 'w'), ensure_ascii=False, indent=1)
    time.sleep(8)
pr = get('https://prices.openfoodfacts.org/api/v1/locations?osm_address_city__like=Amsterdam&osm_address_country__like=Nederland&size=100')
out['prices_amsterdam'] = dict(locations=pr.get('total'), prices=sum(i.get('price_count') or 0 for i in pr.get('items', [])))
nl = get('https://prices.openfoodfacts.org/api/v1/locations?osm_address_country__like=Nederland&size=100&order_by=-price_count')
out['prices_nl'] = dict(locations=nl.get('total'), prices_top100=sum(i.get('price_count') or 0 for i in nl.get('items', [])))
json.dump(out, open(os.path.join(G, 'market.json'), 'w'), ensure_ascii=False, indent=1)
print(out['prices_amsterdam'], out['prices_nl'])
