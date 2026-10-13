#!/usr/bin/env python3
"""Builds the compact resources of :places (src/main/resources/atlas/places) from the open sources.

    sh fetch_sources.sh <src>                     # downloads the pinned sources (about 40 MB) into <src>
    python3 build_places_data.py --src <src> --repo <repo root>

Python 3 standard library only. Inputs, all pinned in fetch_sources.sh:
  id-tagging-schema 7.3.0 (ISC): presets, fields, translations
  name-suggestion-index 8.0.20260918 (BSD-3-Clause): brands and their Wikidata websites
  simple-icons 16.35.0 (CC0): brand glyphs and colours (fetched per matched slug)
  Maki 8.2.0, Temaki 5.13.0, Pinhead 15.25.0 (CC0): map symbols
  Lucide 1.56.0 (ISC): the UI symbol set, plus Atlas's own icons in custom_icons.txt
"""
import argparse
import collections
import json
import os
import re
import sys
import unicodedata
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svgpaths import svg_icon  # noqa: E402
import categories as CAT  # noqa: E402
import tables as T  # noqa: E402

LANGS = ['en', 'nl', 'de', 'fr', 'es', 'it', 'pt', 'pl', 'sv', 'da', 'nb', 'fi', 'cs', 'ja', 'zh-CN', 'ru', 'ar', 'tr']
HERE = os.path.dirname(os.path.abspath(__file__))

# Fields Facts and Cuisine read: id field -> canonical name. Variants of one field share the canonical name.
FACT_FIELDS = {
    'wheelchair': 'wheelchair', 'toilets/wheelchair': 'toilets/wheelchair', 'outdoor_seating': 'outdoor_seating',
    'indoor_seating': 'indoor_seating', 'takeaway': 'takeaway', 'delivery': 'delivery', 'drive_through': 'drive_through',
    'drive_through-US': 'drive_through', 'drive_through-except-US': 'drive_through', 'drive_through_common_for_atm': 'drive_through',
    'drive_through_uncommon_for_atm': 'drive_through', 'reservation': 'reservation', 'reservation_booking': 'reservation',
    'diet_multi': 'diet_multi', 'internet_access': 'internet_access', 'internet_access/fee': 'internet_access/fee',
    'payment_multi': 'payment_multi', 'payment_multi_fee': 'payment_multi', 'smoking': 'smoking', 'toilets': 'toilets',
    'changing_table': 'changing_table', 'dog': 'dog', 'dog_check': 'dog', 'dog_yes': 'dog', 'air_conditioning': 'air_conditioning',
    'level': 'level', 'level_semi': 'level', 'opening_hours': 'opening_hours', 'phone': 'phone', 'website': 'website',
    'fee': 'fee', 'charge_fee': 'charge_fee', 'capacity': 'capacity', 'capacity_parking': 'capacity', 'self_service': 'self_service',
    'organic': 'organic', 'second_hand': 'second_hand', 'stars': 'stars', 'rooms': 'rooms', 'sport': 'sport',
    'sport/sport_ice': 'sport', 'sport/sport_pub': 'sport', 'sport/sport_racing_motor': 'sport', 'sport/sport_racing_nonmotor': 'sport',
    'fuel/fuel_multi': 'fuel/fuel_multi', 'fuel/fuel_multi-RU': 'fuel/fuel_multi', 'recycling_accepts': 'recycling_accepts',
    'surface': 'surface', 'surface_sport_pitch': 'surface', 'lit': 'lit', 'incline': 'incline', 'access': 'access',
    'access_simple': 'access_simple', 'cuisine': 'cuisine', 'drinking_water': 'drinking_water', 'wikidata': 'wikidata',
    'wikipedia': 'wikipedia', 'opening_hours/drive_through': 'opening_hours/drive_through', 'self_checkout': 'self_checkout',
    'baby_feeding': 'baby_feeding', 'toilets/disposal': 'toilets/disposal', 'social_facility_for': 'social_facility_for',
}
GEOM = {'point': 'p', 'vertex': 'v', 'line': 'l', 'area': 'a', 'relation': 'r'}


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def dump(obj, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, separators=(',', ':'), sort_keys=False)
        f.write('\n')
    return os.path.getsize(path)


def norm(s):
    """The brand-name key: lower case, no accents, '&' as 'and', letters and digits only."""
    s = unicodedata.normalize('NFKD', s.lower().replace('&', ' and ').replace('+', ' plus '))
    s = ''.join(c for c in s if not unicodedata.combining(c))
    s = s.replace('ß', 'ss').replace('ø', 'o').replace('æ', 'ae').replace('ł', 'l').replace('đ', 'd')
    return re.sub(r'[\W_]+', '', s)


def countries(loc_list):
    out = []
    for x in loc_list or []:
        if not isinstance(x, str):
            continue
        k = x.lower()
        if k in T.REGIONS:
            out.extend(T.REGIONS[k])
        elif re.fullmatch(r'[a-z]{2}', k):
            out.append(k)
        elif re.match(r'[a-z]{2}-', k):
            out.append(k[:2])
    return sorted(set(out))


# ---------------------------------------------------------------------------------------------------------------- icons

class IconSets:
    def __init__(self, src):
        self.src = src
        self.maki = {f[:-4] for f in os.listdir(os.path.join(src, 'maki/x/package/icons')) if f.endswith('.svg')}
        self.temaki = {f[:-4] for f in os.listdir(os.path.join(src, 'temaki/x/package/icons')) if f.endswith('.svg')}
        self.pinhead = load(os.path.join(src, 'pinhead/index.complete.json'))['icons']

    def exists(self, icon):
        pre, _, name = icon.partition('-')
        return (pre == 'maki' and name in self.maki) or (pre == 'temaki' and name in self.temaki) or \
            (pre == 'pinhead' and name in self.pinhead)

    def resolve(self, icon):
        """A CC0 symbol id for a preset icon id, or None."""
        if not icon:
            return None
        if self.exists(icon):
            return icon
        if icon in T.ICON_STANDINS:
            sub = T.ICON_STANDINS[icon]
            if not self.exists(sub):
                raise SystemExit('stand-in %s for %s does not exist' % (sub, icon))
            return sub
        pre, _, name = icon.partition('-')
        n = name.replace('-', '_').lower()
        for cand in (n, n.replace('_alt', ''), n.replace('_', ''), n.rstrip('s')):
            if cand in self.pinhead:
                return 'pinhead-' + cand
        if name in self.maki:
            return 'maki-' + name
        if name.replace('-', '_') in self.temaki:
            return 'temaki-' + name.replace('-', '_')
        return None

    def svg(self, icon):
        pre, _, name = icon.partition('-')
        if pre == 'maki':
            return open(os.path.join(self.src, 'maki/x/package/icons', name + '.svg'), encoding='utf-8').read()
        if pre == 'temaki':
            return open(os.path.join(self.src, 'temaki/x/package/icons', name + '.svg'), encoding='utf-8').read()
        if pre == 'pinhead':
            return self.pinhead[name]['svg']
        raise KeyError(icon)


def ui_icons(src):
    out = {}
    for line in open(os.path.join(HERE, 'ui_icons.tsv'), encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        atlas, lucide = line.rstrip('\n').split('\t')
        if lucide == 'custom':
            continue
        text = open(os.path.join(src, 'lucide', lucide + '.svg'), encoding='utf-8').read()
        text = re.sub(r'<!--.*?-->', '', text, flags=re.S)
        out['ui-' + atlas] = svg_icon(text)
    for line in open(os.path.join(HERE, 'custom_icons.txt'), encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        atlas, body = line.rstrip('\n').split('\t')
        text = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
                'stroke-width="2" stroke-linecap="round" stroke-linejoin="round">%s</svg>' % body)
        out['ui-' + atlas] = svg_icon(text)
    return out


# -------------------------------------------------------------------------------------------------------------- presets

def build_presets(src, icons):
    P = load(os.path.join(src, 'id/presets.min.json'))
    F = load(os.path.join(src, 'id/fields.min.json'))
    presets, unresolved = [], collections.Counter()
    for pid, p in P.items():
        if pid.startswith('@'):
            continue
        geom = ''.join(GEOM[g] for g in p.get('geometry', []) if g in GEOM)
        if not geom or geom == 'r':
            continue
        icon = icons.resolve(p.get('icon'))
        if p.get('icon') and not icon:
            unresolved[p['icon']] += 1
        e = {'id': pid, 't': p.get('tags', {}), 'g': geom}
        add = {k: v for k, v in (p.get('addTags') or {}).items() if p.get('tags', {}).get(k) != v}
        if add:
            e['a'] = add
        if icon:
            e['i'] = icon
        for src_key, out_key in (('fields', 'f'), ('moreFields', 'm')):
            fs = []
            for f in p.get(src_key, []):
                if f.startswith('{') or f.startswith('@'):
                    raise SystemExit('unresolved field reference %s in %s' % (f, pid))
                c = FACT_FIELDS.get(f)
                if c and c not in fs and (out_key == 'f' or c not in e.get('f', [])):
                    fs.append(c)
            if fs:
                e[out_key] = fs
        if p.get('matchScore', 1) != 1:
            e['s'] = p['matchScore']
        if p.get('searchable') is False:
            e['x'] = 1
        ls = p.get('locationSet') or {}
        inc, exc = countries(ls.get('include')), countries(ls.get('exclude'))
        if inc and inc != ['*']:
            e['c'] = inc
        if exc and exc != ['*']:
            e['xc'] = exc
        presets.append(e)
    used_fields = {}
    for fid, canon in FACT_FIELDS.items():
        f = F.get(fid)
        if not f or canon in used_fields:
            continue
        used_fields[canon] = {'k': f.get('key') or (f.get('keys') or [''])[0], 't': f.get('type')}
        if f.get('options'):
            used_fields[canon]['o'] = f['options']
    return presets, used_fields, unresolved


def load_translation(src, lang):
    path = os.path.join(src, 'id', 'tr-%s.min.json' % lang)
    data = load(path)
    t = next(iter(data.values()))['presets']
    if lang == 'nb':  # Norwegian Bokmål: the 'no' file is far fuller; nb wins where both have a string
        extra = next(iter(load(os.path.join(src, 'id', 'tr-no.min.json')).values()))['presets']
        for sec in ('presets', 'fields', 'categories'):
            merged = dict(extra.get(sec, {}))
            merged.update(t.get(sec, {}))
            t[sec] = merged
    return t


def split_terms(v):
    if isinstance(v, list):
        return [x.strip() for x in v if x and x.strip()]
    if isinstance(v, str):
        return [x.strip() for x in re.split(r'[,\n]', v) if x.strip()]
    return []


def build_i18n(src, presets, fields, cats):
    ids = {p['id'] for p in presets}
    # Terms and aliases only where search uses them: searchable presets drawn as points or areas.
    searchable = {p['id'] for p in presets if not p.get('x') and set(p['g']) & set('pva')}
    sizes = {}
    english = load_translation(src, 'en')
    en_words = {}
    for pid, v in english.get('presets', {}).items():
        en_words[pid] = {norm(x) for x in [v.get('name') or ''] + split_terms(v.get('terms')) + split_terms(v.get('aliases'))}
    for lang in LANGS:
        t = english if lang == 'en' else load_translation(src, lang)
        out = {'p': {}, 'f': {}}
        for pid, v in t.get('presets', {}).items():
            if pid not in ids:
                continue
            name = v.get('name')
            terms, aliases = [], []
            if pid in searchable:
                # Search always looks at English too, so a translation's copies of English words add nothing.
                own = lambda xs: [x for x in xs if lang == 'en' or norm(x) not in en_words.get(pid, ())]
                terms, aliases = own(split_terms(v.get('terms'))), own(split_terms(v.get('aliases')))
            if name or terms or aliases:
                row = [name or '']
                if terms or aliases:
                    row.append(','.join(terms))
                if aliases:
                    row.append('\n'.join(aliases))
                out['p'][pid] = row
        for fid, canon in FACT_FIELDS.items():
            v = t.get('fields', {}).get(fid)
            if not v or canon in out['f']:
                continue
            opts = {}
            for k, o in (v.get('options') or {}).items():
                label = o.get('title') if isinstance(o, dict) else o
                if label:
                    opts[k] = label
            out['f'][canon] = [v.get('label', '')] + ([opts] if opts else [])
        sizes[lang] = dump(out, os.path.join(OUT, 'i18n', lang + '.json'))
    return sizes


# --------------------------------------------------------------------------------------------------------- categories

def build_categories(src):
    trans = {lang: load_translation(src, lang) for lang in LANGS}

    def tidy(name):
        name = re.sub(r'\s*[(（][^)）]*[)）]', '', name).strip()
        return name.split(' / ')[0].strip()

    def names(own, name_from):
        n = dict(zip(('en', 'nl', 'de', 'fr'), own))
        for lang in LANGS:
            if lang in n or not name_from:
                continue
            v = trans[lang].get('presets', {}).get(name_from, {}).get('name')
            if v and tidy(v):
                n[lang] = tidy(v)
        return n

    out = []
    for gid, sym, colour, own in CAT.GROUPS:
        assert len(own) == len(LANGS), gid
        out.append({'id': gid, 'group': True, 'symbol': sym, 'colour': colour, 'names': dict(zip(CAT.LANGS, own))})
    ids = {g[0] for g in CAT.GROUPS}
    for cid, legacy, group, sym, rule, own, nf in CAT.CATEGORIES:
        if cid in ids:
            raise SystemExit('duplicate category id ' + cid)
        ids.add(cid)
        colour = next((g[2] for g in CAT.GROUPS if g[0] == group), None) or CAT.FAMILY[group]
        e = {'id': cid, 'parent': group if any(g[0] == group for g in CAT.GROUPS) else None, 'family': group,
             'symbol': sym, 'colour': colour, 'rule': rule, 'names': names(own, nf)}
        if legacy:
            e['legacy'] = legacy
        out.append(e)
    return out


# ------------------------------------------------------------------------------------------------------------- brands

BRAND_COLUMNS = ['id', 'name', 'brand', 'wikidata', 'key=value', 'countries', 'not in', 'spread', 'logo', 'colour', 'text colour',
                 'match names', 'other tags']
WANTED_TREES = {'brands/amenity/' + v for v in 'fast_food restaurant cafe fuel charging_station bank pharmacy bar'.split()} | \
    {'brands/tourism/hotel', 'brands/leisure/fitness_centre'}


def raw_brands(repo):
    """(brand, brand:wikidata, key=value) of every branded element in the prototype's extracts."""
    seen = set()
    keys = ('amenity', 'shop', 'tourism', 'leisure', 'craft', 'office', 'healthcare')
    for rel in ('data/ams/raw.json', 'data/eat/raw.json', 'data/errands/raw.json'):
        d = load(os.path.join(repo, rel))
        groups = [d['elements']] if 'elements' in d else [v for v in d.values() if isinstance(v, list) and v and isinstance(v[0], dict)]
        for g in groups:
            for e in g:
                t = e.get('tags') or {}
                if 'brand' in t or 'brand:wikidata' in t:
                    kv = next(('%s=%s' % (k, t[k]) for k in keys if k in t), None)
                    seen.add((t.get('brand'), t.get('brand:wikidata'), kv))
    return seen


def build_brands(src, repo, keep):
    nsi = load(os.path.join(src, 'nsi/nsi.min.json'))['nsi']
    wd = load(os.path.join(src, 'nsi/wikidata.min.json'))['wikidata']
    items = []
    for tree, v in nsi.items():
        if not tree.startswith('brands/'):
            continue
        _, key, value = tree.split('/', 2)
        for it in v['items']:
            tags = it.get('tags', {})
            items.append({'tree': tree, 'id': it['id'], 'name': it.get('displayName') or tags.get('brand') or tags.get('name'),
                          'brand': tags.get('brand'), 'qid': tags.get('brand:wikidata'), 'kv': '%s=%s' % (key, tags.get(key, value)),
                          'inc': countries((it.get('locationSet') or {}).get('include')),
                          'exc': countries((it.get('locationSet') or {}).get('exclude')),
                          'match': [m for m in it.get('matchNames', [])][:6],
                          'tags': {k: v2 for k, v2 in tags.items() if k not in ('brand', 'brand:wikidata', 'name', key)
                                   and ':' not in k}})

    def spread(it):
        n = 250 if '*' in it['inc'] else len(it['inc'])
        return n - min(len(it['exc']), n - 1) if n else 0

    # The simple-icons index: normalised title or alias -> slug.
    si = load(os.path.join(src, 'si/simple-icons.json'))
    si_by_name = collections.defaultdict(set)
    si_meta = {}
    for e in si:
        si_meta[e['slug']] = e
        names = [e['title']]
        al = e.get('aliases') or {}
        names += al.get('aka', [])
        names += [d['title'] for d in al.get('dup', [])]
        names += list((al.get('loc') or {}).values())
        for n in names:
            si_by_name[norm(n)].add(e['slug'])
    name_qids = collections.defaultdict(set)
    for it in items:
        for n in {it['name'], it['brand']} - {None}:
            name_qids[norm(n)].add(it['qid'] or it['id'])

    def domain(url):
        m = re.match(r'https?://(?:www\d?\.)?([^/:]+)', url or '')
        return m.group(1).lower() if m else None

    def logo_for(it):
        cands = set()
        for n in {it['name'], it['brand']} - {None}:
            cands |= si_by_name.get(norm(n), set())
        if not cands:
            return None, None
        sites = {domain(u) for u in (wd.get(it['qid'] or '', {}).get('officialWebsites') or [])} - {None}
        for slug in sorted(cands):
            sd = domain(si_meta[slug]['source'])
            if sd and any(sd == s or sd.endswith('.' + s) or s.endswith('.' + sd) for s in sites):
                return slug, 'site'
        n = norm(it['name'] or it['brand'] or '')
        if it['name'] in T.LOGO_DENY:
            return None, None
        if len(cands) == 1 and len(name_qids[n]) == 1 and len(n) >= 3:
            return next(iter(cands)), 'name'
        return None, None

    wanted = [it for it in items if it['tree'] in WANTED_TREES or it['tree'].startswith('brands/shop/')]
    wanted.sort(key=lambda it: (-spread(it), logo_for(it)[0] is None, it['qid'] is None, it['name'].lower()))
    chosen = {it['id']: it for it in (wanted[:keep] if keep else wanted)}

    # Every brand seen in the prototype's extracts, matched by Wikidata id, else by name and main tag.
    by_qid = collections.defaultdict(list)
    by_name = collections.defaultdict(list)
    for it in items:
        if it['qid']:
            by_qid[it['qid']].append(it)
        for n in {it['name'], it['brand']} - {None}:
            by_name[norm(n)].append(it)
        for m in it['match']:
            by_name[norm(m)].append(it)
    synthetic, raw_hits, raw_total = [], 0, 0
    for brand, qid, kv in sorted(raw_brands(repo), key=lambda x: str(x)):
        raw_total += 1
        hits = by_qid.get(qid, []) if qid else []
        if not hits and brand and not qid:
            # by name only for brands without an item, and only within the same main key: a café is no supermarket
            key = (kv or '').split('=')[0]
            hits = [it for it in by_name.get(norm(brand), []) if it['kv'].split('=')[0] == key]
        if hits:
            raw_hits += 1
            best = next((h for h in hits if h['kv'] == kv), hits[0])
            chosen.setdefault(best['id'], best)
        elif brand or qid:
            sid = 'osm-' + (qid.lower() if qid else norm(brand))
            if sid not in chosen and all(s['id'] != sid for s in synthetic):
                synthetic.append({'id': sid, 'name': brand or qid, 'brand': brand, 'qid': qid, 'kv': kv or '', 'inc': [],
                                  'exc': [], 'match': [], 'tags': {}, 'tree': 'osm'})
    colours_by_name = {}
    for it in items:
        if it['name'] in T.BRAND_COLOURS_BY_NAME and it['qid']:
            colours_by_name[it['qid']] = T.BRAND_COLOURS_BY_NAME[it['name']]
    out, logos, how = [], {}, collections.Counter()
    for it in list(chosen.values()) + synthetic:
        slug, why = logo_for(it) if it['tree'] != 'osm' else (None, None)
        if slug:
            logos[slug] = si_meta[slug]
            how[why] += 1
        bg, fg = T.BRAND_COLOURS.get(it['qid'] or '') or colours_by_name.get(it['qid'] or '') or \
            ((si_meta[slug]['hex'], None) if slug else (None, None))
        out.append([it['id'], it['name'], it['brand'] if it['brand'] and it['brand'] != it['name'] else '', it['qid'] or '', it['kv'],
                    ','.join(it['inc']) if it['inc'] != ['*'] else '', ','.join(it['exc']), str(spread(it)), slug or '', bg or '',
                    fg or '', '|'.join(it['match']), ';'.join('%s=%s' % kv for kv in sorted(it['tags'].items()))])
    out.sort(key=lambda r: r[0])
    return out, logos, {'kept': len(out), 'synthetic': len(synthetic), 'raw_brands': raw_total, 'raw_matched_nsi': raw_hits,
                        'logos': len(logos), 'logo_match': dict(how)}


def dump_tsv(rows, header, path):
    """One brand per line, tab-separated; tabs and line breaks inside values become spaces."""
    with open(path, 'w', encoding='utf-8') as f:
        f.write('# ' + '\t'.join(header) + '\n')
        for r in rows:
            f.write('\t'.join(re.sub(r'[\t\r\n]+', ' ', v) for v in r) + '\n')
    return os.path.getsize(path)


def fetch_logos(src, slugs):
    d = os.path.join(src, 'si', 'icons')
    os.makedirs(d, exist_ok=True)
    out = {}
    for slug in sorted(slugs):
        path = os.path.join(d, slug + '.svg')
        if not os.path.exists(path):
            url = 'https://cdn.jsdelivr.net/npm/simple-icons@16.35.0/icons/%s.svg' % slug
            req = urllib.request.Request(url, headers={'User-Agent': 'Atlas places build (https://github.com)'})
            with urllib.request.urlopen(req, timeout=30) as r:
                open(path, 'wb').write(r.read())
        out['logo-' + slug] = svg_icon(open(path, encoding='utf-8').read())
    return out


# --------------------------------------------------------------------------------------------------------------- main

def main():
    global OUT
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True)
    ap.add_argument('--repo', required=True)
    ap.add_argument('--out', default=os.path.join(HERE, '..', 'src', 'main', 'resources', 'atlas', 'places'))
    ap.add_argument('--brands', type=int, default=0, help='keep only the N most widespread NSI brands (0: all)')
    a = ap.parse_args()
    OUT = os.path.abspath(a.out)
    src = a.src
    report = {}

    icons = IconSets(src)
    presets, fields, unresolved = build_presets(src, icons)
    report['presets'] = len(presets)
    report['presets_without_cc0_icon'] = sum(unresolved.values())
    report['unresolved_icons'] = sorted(unresolved)
    # fact fields by their index in one shared list: the names would repeat in every preset
    names = sorted({f for p in presets for k in ('f', 'm') for f in p.get(k, [])})
    compact = [dict(p, **{k: [names.index(f) for f in p[k]] for k in ('f', 'm') if k in p}) for p in presets]
    report['presets.json'] = dump({'fields': names, 'presets': compact}, os.path.join(OUT, 'presets.json'))
    report['fields.json'] = dump(fields, os.path.join(OUT, 'fields.json'))

    cats = build_categories(src)
    report['categories.json'] = dump({'notAPlace': CAT.NOT_A_PLACE, 'categories': cats}, os.path.join(OUT, 'categories.json'))
    report['i18n'] = build_i18n(src, presets, fields, cats)

    cuisine = {k: [v[0]] + ([v[1]] if v[1] else []) for k, v in T.CUISINE.items()}
    report['cuisine.json'] = dump(cuisine, os.path.join(OUT, 'cuisine.json'))

    # Map symbols: every icon a preset, a category or a cuisine names.
    need = {p['i'] for p in presets if 'i' in p} | {c['symbol'] for c in cats if not c['symbol'].startswith('ui-')}
    # and the generic symbols Symbols.symbolFor falls back on (keep in step with its KEY_SYMBOLS)
    need |= {v[0] for v in T.CUISINE.values()} | {'maki-marker', 'maki-shop', 'maki-circle', 'temaki-briefcase', 'temaki-tools',
                                                    'maki-monument', 'maki-attraction', 'maki-park'}
    poi = {}
    for icon in sorted(need):
        if not icons.exists(icon):
            raise SystemExit('missing symbol ' + icon)
        poi[icon] = svg_icon(icons.svg(icon), places=2 if icon.startswith(('maki', 'pinhead')) else 3)
    report['symbols'] = len(poi)
    report['symbols.json'] = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in poi.items()}, os.path.join(OUT, 'symbols.json'))

    ui = ui_icons(src)
    report['ui'] = len(ui)
    report['ui.json'] = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in ui.items()}, os.path.join(OUT, 'ui.json'))

    brands, logo_meta, stats = build_brands(src, a.repo, a.brands)
    report.update(stats)
    report['brands.tsv'] = dump_tsv(brands, BRAND_COLUMNS, os.path.join(OUT, 'brands.tsv'))
    logos = fetch_logos(src, logo_meta)
    report['logos.json'] = dump({k: [v[0], [list(p) for p in v[1]]] for k, v in logos.items()}, os.path.join(OUT, 'logos.json'))
    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(OUT) for f in fs)
    report['total_bytes'] = total
    print(json.dumps(report, indent=1, ensure_ascii=False))


if __name__ == '__main__':
    main()
