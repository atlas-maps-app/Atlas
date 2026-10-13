#!/usr/bin/env python3
"""The species behind a zoo's mapped animals, from Wikidata (CC0).
OpenStreetMap names ARTIS's enclosures in Dutch only (name=Aziatische olifant), with no species or species:wikidata tags.
Each name is looked up as a Dutch label or alias of a taxon in Wikidata; the match is a guess and is kept as one
(how='name'), unless the enclosure carries species:wikidata, which wins (how='mapped').
For each taxon: scientific name, English and Dutch names, rank, the IUCN Red List category (P141), its parent's when a
subspecies has none (marked), and the Commons image file name (P18; not downloaded).
python3 fetch/zoo_species.py artis  ->  data/zoo/artis/species.json (reads data/regions/artis/raw.json; answers cached in data/zoo/artis/_wd)"""
import json, os, sys, time, hashlib, urllib.parse, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
UA = 'AtlasConcept/1.0 (design prototype; one-off reads)'
API = 'https://www.wikidata.org/w/api.php?'
IUCN = {'Q211005': 'LC', 'Q719675': 'NT', 'Q278113': 'VU', 'Q11394': 'EN', 'Q96377276': 'EN', 'Q219127': 'CR', 'Q239509': 'EW', 'Q237350': 'EX', 'Q3245245': 'DD'}
CACHE = None          # every answer kept on disk: Wikidata rate-limits hard, and a rerun should cost nothing
# names that are not one taxon: an exhibit or a house, kept as such
NOT_TAXA = {'Hollandse Polder', 'Gierenvolière', 'Apenhuis', 'Reptielenhuis', 'Aquarium', 'Kerbertterras'}
# where a Dutch common name finds a broader or wrong item first, the taxon it means (still marked as matched by name)
PREFER = {'Aldabra reuzenschildpad': 'Aldabrachelys gigantea', 'Netgiraffe': 'Giraffa camelopardalis reticulata', 'Vicuña': 'Vicugna vicugna',
          'Japanse kraanvogel': 'Grus japonensis', 'Zwarte kuifmakaak': 'Macaca nigra', 'Zwarte slingeraap': 'Ateles paniscus', 'Grote mara': 'Dolichotis patagonum', 'Aziatische kleinklauwotter': 'Aonyx cinereus', 'Hudson Bay wolf': 'Canis lupus hudsonicus', 'Grantzebra': 'Equus quagga boehmi',
          'Leeuw': 'Panthera leo', 'Jaguar': 'Panthera onca', 'Wasbeer': 'Procyon lotor', 'Anoa': 'Bubalus depressicornis',
          'Chimpansee': 'Pan troglodytes', 'Struisvogel': 'Struthio camelus', 'Capibara': 'Hydrochoerus hydrochaeris',
          'Algazel': 'Oryx dammah'}

def get(params):
    url = API + urllib.parse.urlencode(dict(params, format='json'))
    f = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest()[:16] + '.json')
    if os.path.exists(f): return json.load(open(f))
    for k in range(6):
        try:
            time.sleep(2.0)
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60) as r: d = json.load(r)
            json.dump(d, open(f, 'w')); return d
        except Exception as ex:
            print('  retry', ex); time.sleep(30 * (k + 1))
    raise SystemExit('wikidata failed')

def entities(ids):
    out = {}
    for i in range(0, len(ids), 40):
        out.update(get(dict(action='wbgetentities', ids='|'.join(ids[i:i + 40]), props='labels|aliases|claims', languages='nl|en'))['entities'])
        time.sleep(0.5)
    return out

def claim(e, p):
    """A property's value: the preferred statement if there is one, never a deprecated one."""
    cs = [c for c in e.get('claims', {}).get(p, []) if c.get('rank') != 'deprecated']
    cs.sort(key=lambda c: c.get('rank') != 'preferred')
    for c in cs:
        v = c['mainsnak'].get('datavalue', {}).get('value')
        if v is not None: return v['id'] if isinstance(v, dict) and 'id' in v else v
    return None
def common(e, lang):
    """The taxon's common name in a language (P1843), else its label when that is not the scientific name."""
    for c in e.get('claims', {}).get('P1843', []):
        v = c['mainsnak'].get('datavalue', {}).get('value')
        if v and v.get('language') == lang: return v['text']
    l = e.get('labels', {}).get(lang, {}).get('value')
    return l if l and l != claim(e, 'P225') else None

def is_taxon(e): return claim(e, 'P225') is not None

def find(name):
    """The taxon a Dutch name means: the scientific name we prefer, else the first taxon among Dutch label matches."""
    if name in PREFER or name.capitalize() in PREFER:
        q = get(dict(action='wbsearchentities', search=PREFER.get(name) or PREFER[name.capitalize()], language='en', type='item', limit=5))['search']
        es = entities([x['id'] for x in q])
        for x in q:
            if claim(es[x['id']], 'P225') == (PREFER.get(name) or PREFER[name.capitalize()]): return x['id'], es[x['id']]
    q = get(dict(action='wbsearchentities', search=name, language='nl', uselang='nl', type='item', limit=10))['search']
    if not q: return None, None
    es = entities([x['id'] for x in q])
    for x in q:
        e = es[x['id']]
        if is_taxon(e): return x['id'], e
    return None, None

if __name__ == '__main__':
    zoo = sys.argv[1]; D = os.path.join(HERE, '..', 'data', 'zoo', zoo)
    CACHE = os.path.join(D, '_wd'); os.makedirs(CACHE, exist_ok=True)
    raw = json.load(open(os.path.join(HERE, '..', 'data', 'regions', zoo, 'raw.json')))['elements']          # fetch/regions.py <zoo>
    names = sorted({n.strip() for e in raw if (e.get('tags') or {}).get('attraction') == 'animal' and e['tags'].get('name') for n in e['tags']['name'].split(',')} - NOT_TAXA)
    out = {}
    for n in names:
        qid, e = find(n)
        if not e: out[n] = None; print('%-34s  —' % n); continue
        rank = claim(e, 'P105'); raw141 = claim(e, 'P141'); status = IUCN.get(raw141); via = None
        if raw141 and not status: print('  unknown Red List value', raw141)
        if not status:              # a subspecies or breed without its own assessment: the species' (marked)
            par = claim(e, 'P171')
            if par:
                pe = entities([par])[par]; ps = IUCN.get(claim(pe, 'P141'))
                if ps: status, via = ps, dict(qid=par, sci=claim(pe, 'P225'))
        lab = e.get('labels', {})
        out[n] = dict(qid=qid, sci=claim(e, 'P225'), en=common(e, 'en'), nl=common(e, 'nl') or lab.get('nl', {}).get('value'),
                      rank={'Q7432': 'species', 'Q68947': 'subspecies'}.get(rank, rank), iucn=status, iucn_via=via, image=claim(e, 'P18'), how='name')
        print('%-34s  %-10s %-32s %-26s %s%s' % (n, qid, out[n]['sci'], out[n]['en'], status, ' (of %s)' % via['sci'] if via else ''))
        time.sleep(0.4)
    json.dump(out, open(os.path.join(D, 'species.json'), 'w'), indent=1, ensure_ascii=False)
