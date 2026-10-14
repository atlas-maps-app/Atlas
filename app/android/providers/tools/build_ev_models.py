"""Builds providers/src/main/resources/app/atlas/providers/ev-models.tsv.gz from the two open EV datasets.

Inputs (download them first; neither is kept in the repo):
  kw-ev-data.json  https://raw.githubusercontent.com/KilowattApp/open-ev-data/master/data/ev-data.json
                   "MIT License with Attribution Requirement": credit "Open EV Data (https://github.com/KilowattApp/open-ev-data)".
  oed.json         https://github.com/open-ev-data/open-ev-data-dataset/releases/download/v1.24.0/open-ev-data-v1.24.0.json
                   CDLA-Permissive-2.0: the agreement's text ships next to the data (ev-models-LICENCES.txt).

KilowattApp is the base: consumption and measured charging curves. OpenEV Data adds what KilowattApp lacks
(curb weight, drag coefficient, rated range) to matching models, and newer models KilowattApp does not have; for those,
consumption is estimated from battery and rated range and flagged. Plug-in and range-extender hybrids (which OpenEV Data
lists among its cars) and rows whose consumption no such vehicle could have are left out.

Usage: python3 -I build_ev_models.py kw-ev-data.json oed.json out.tsv.gz
"""
import gzip
import json
import re
import sys
import unicodedata

COLUMNS = ['id', 'source', 'make', 'model', 'variant', 'year', 'kind', 'battery_kwh', 'consumption', 'consumption_estimated',
           'ac_ports', 'ac_phases', 'ac_kw', 'dc_ports', 'dc_kw', 'voltage', 'curve_default', 'curve', 'mass_kg', 'cd',
           'range_km', 'range_cycle']

MAKE_ALIASES = {'mercedes benz': 'mercedes', 'mg motor': 'mg', 'opel vauxhall': 'opel', 'gac aion': 'aion'}

# Rated range overstates highway range; these factors turn battery / rated range into a mixed real-world consumption.
CYCLE_FACTOR = {'epa': 1.05, 'wltp': 1.2, 'nedc': 1.4, 'cltc': 1.45, 'jc08': 1.5}

# kWh per 100 km a vehicle of each kind can really use; outside it the source is wrong (a typo, or a hybrid's
# combined range), and the router would plan nonsense with it.
PLAUSIBLE = {'car': (6, 40), 'van': (8, 45), 'motorbike': (3, 20), 'microcar': (3, 20)}

# OpenEV Data lists some plug-in and range-extender hybrids among its electric cars. Only they have a gearbox.
HYBRID_GEARBOX = {'dct', 'automatic', 'manual', 'cvt', 'e-cvt'}
HYBRID_NAME = re.compile(r'\b(phev|hybrid|erev|range extender)\b', re.I)


def plausible(row):
    lo, hi = PLAUSIBLE.get(row['kind'], PLAUSIBLE['car'])
    try:
        return lo <= float(row['consumption']) <= hi
    except ValueError:
        return False


def is_hybrid(v):
    gearbox = ((v.get('powertrain') or {}).get('transmission') or {}).get('type')
    name = ' '.join(x for x in ((v.get('model') or {}).get('name'), (v.get('trim') or {}).get('name'), (v.get('variant') or {}).get('name')) if x)
    return gearbox in HYBRID_GEARBOX or bool(HYBRID_NAME.search(name))


def norm(s):
    s = unicodedata.normalize('NFD', s or '')
    s = ''.join(c for c in s if not unicodedata.combining(c)).lower()
    return re.sub(r'[^a-z0-9]+', ' ', s).strip()


def make_key(s):
    n = norm(s)
    return MAKE_ALIASES.get(n, n)


def model_key(s):
    return norm(s).replace(' ', '')


def tokens(s):
    return frozenset(norm(s).split())


def num(x):
    if x is None:
        return ''
    if isinstance(x, float) and x == int(x):
        x = int(x)
    return str(x)


def kw_rows(path):
    rows = []
    for v in json.load(open(path, encoding='utf-8'))['data']:
        ac = v.get('ac_charger') or {}
        dc = v.get('dc_charger') or {}
        ac_ports = ['type1' if p == 'type1' else 'type2' for p in ac.get('ports') or []]
        combo = 'type1_combo' if 'type1' in ac_ports and 'type2' not in ac_ports else 'type2_combo'
        dc_map = {'ccs': combo, 'chademo': 'chademo', 'tesla_suc': 'tesla_supercharger', 'tesla_ccs': 'tesla_supercharger_ccs'}
        dc_ports = [dc_map[p] for p in dc.get('ports') or [] if p in dc_map]
        curve = ','.join('%s:%s' % (num(c['percentage']), num(c['power'])) for c in dc.get('charging_curve') or [])
        rows.append({
            'id': v['id'], 'source': 'kw', 'make': v['brand'].strip(), 'model': v['model'].strip(), 'variant': (v.get('variant') or '').strip(),
            'year': num(v.get('release_year')), 'kind': v.get('vehicle_type') or 'car',
            'battery_kwh': num(v.get('usable_battery_size')),
            'consumption': num((v.get('energy_consumption') or {}).get('average_consumption')), 'consumption_estimated': '0',
            'ac_ports': ','.join(dict.fromkeys(ac_ports)), 'ac_phases': num(ac.get('usable_phases')), 'ac_kw': num(ac.get('max_power')),
            'dc_ports': ','.join(dict.fromkeys(dc_ports)), 'dc_kw': num(dc.get('max_power')), 'voltage': num(v.get('charging_voltage')),
            'curve_default': '1' if dc.get('is_default_charging_curve') else '0', 'curve': curve,
            'mass_kg': '', 'cd': '', 'range_km': '', 'range_cycle': '',
        })
    return rows


def oed_range(v):
    rated = (v.get('range') or {}).get('rated') or []
    for cycle in ('wltp', 'epa', 'cltc', 'nedc', 'jc08'):
        for r in rated:
            if r.get('cycle') == cycle and r.get('range_km'):
                return r['range_km'], cycle
    return None, None


def join_variant(trim, variant):
    """"Long Range" and "Long Range RWD" make "Long Range RWD"; "Base" says nothing."""
    parts = [x for x in (trim, variant) if x and x.lower() != 'base']
    if len(parts) == 2 and (norm(parts[0]) in norm(parts[1]) or norm(parts[1]) in norm(parts[0])):
        parts = [max(parts, key=len)]
    return ' '.join(parts)


def oed_row(v):
    ports = [p.get('connector') for p in v.get('charge_ports') or []]
    ac, dc = [], []
    for p in ports:
        if p == 'ccs2':
            dc.append('type2_combo'); ac.append('type2')
        elif p == 'ccs1':
            dc.append('type1_combo'); ac.append('type1')
        elif p == 'gb_t_dc':
            dc.append('gb_dc')
        elif p == 'gb_t_ac':
            ac.append('gb_ac')
        elif p == 'nacs':
            dc.append('nacs'); ac.append('nacs')
        elif p == 'chademo':
            dc.append('chademo')
        elif p in ('type1', 'type2'):
            ac.append(p)
    charging = v.get('charging') or {}
    battery = (v.get('battery') or {}).get('pack_capacity_kwh_net') or (v.get('battery') or {}).get('pack_capacity_kwh_gross')
    rng, cycle = oed_range(v)
    consumption = round(battery / rng * 100 * CYCLE_FACTOR[cycle], 1) if battery and rng else None
    volt = (charging.get('dc') or {}).get('architecture_voltage_class') or ''
    trim = (v.get('trim') or {}).get('name') or ''
    variant = (v.get('variant') or {}).get('name') or ''
    ident = ':'.join(x for x in (v['make']['slug'], v['model']['slug'], str(v['year']), (v.get('trim') or {}).get('slug'), (v.get('variant') or {}).get('slug')) if x)
    return {
        'id': 'oed:' + ident, 'source': 'oed', 'make': v['make']['name'], 'model': v['model']['name'],
        'variant': join_variant(trim, variant),
        'year': num(v.get('year')), 'kind': 'van' if v.get('vehicle_type') == 'van' else 'car', 'battery_kwh': num(battery),
        'consumption': num(consumption), 'consumption_estimated': '1',
        'ac_ports': ','.join(dict.fromkeys(ac)), 'ac_phases': num((charging.get('ac') or {}).get('phases')),
        'ac_kw': num((charging.get('ac') or {}).get('max_power_kw')),
        'dc_ports': ','.join(dict.fromkeys(dc)), 'dc_kw': num((charging.get('dc') or {}).get('max_power_kw')),
        'voltage': re.sub(r'[^0-9]', '', volt), 'curve_default': '0', 'curve': '',
        'mass_kg': num((v.get('weights') or {}).get('curb_weight_kg')), 'cd': num((v.get('body') or {}).get('drag_coefficient_cd')),
        'range_km': num(rng), 'range_cycle': cycle or '',
    }


def main(kw_path, oed_path, out_path):
    rows = kw_rows(kw_path)
    index = {}
    for r in rows:
        # The datasets order words differently ("A6 Avant e-tron" against "A6 e-tron" + "Avant"): match on word sets.
        for words in (tokens(r['model']), tokens(r['model'] + ' ' + r['variant'])):
            index.setdefault((make_key(r['make']), words, r['year']), []).append(r)
    implausible = [r for r in rows if not plausible(r)]
    rows = [r for r in rows if plausible(r)]
    added = enriched = hybrids = 0
    for v in json.load(open(oed_path, encoding='utf-8'))['vehicles']:
        if is_hybrid(v):
            hybrids += 1
            continue
        o = oed_row(v)
        if not o['battery_kwh']:
            continue
        variant = (v.get('variant') or {}).get('name') or ''
        hits = None
        for words in (tokens(o['model'] + ' ' + variant), tokens(o['model'])):
            hits = index.get((make_key(o['make']), words, o['year']))
            if hits:
                break
        if hits:
            # Enrich the KilowattApp trims with the nearest battery: same car, physics KilowattApp lacks.
            target = min(hits, key=lambda h: abs(float(h['battery_kwh'] or 0) - float(o['battery_kwh'])))
            if not target['mass_kg'] and (o['mass_kg'] or o['cd'] or o['range_km']):
                for k in ('mass_kg', 'cd', 'range_km', 'range_cycle'):
                    target[k] = o[k]
                enriched += 1
            continue
        if not o['consumption'] or not (o['dc_ports'] or o['ac_ports']):
            continue
        if not plausible(o):
            implausible.append(o)
            continue
        rows.append(o)
        added += 1
    rows.sort(key=lambda r: (norm(r['make']), norm(r['model']), r['year'], norm(r['variant'])))
    with gzip.open(out_path, 'wt', encoding='utf-8', compresslevel=9) as f:
        f.write('\t'.join(COLUMNS) + '\n')
        for r in rows:
            f.write('\t'.join(str(r[c]).replace('\t', ' ').replace('\n', ' ') for c in COLUMNS) + '\n')
    print('rows', len(rows), 'kw', len(rows) - added, 'oed added', added, 'enriched', enriched, 'hybrids dropped', hybrids)
    for r in implausible:
        print('dropped, %s kWh/100 km is not plausible: %s %s %s %s' % (r['consumption'], r['make'], r['model'], r['variant'], r['year']))


if __name__ == '__main__':
    main(*sys.argv[1:4])
