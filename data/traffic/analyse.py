"""A real Tuesday morning (6 October 2026) from NDW's open feed, recorded every 5 minutes by live/record_morning.sh:
for every trip on Amsterdam's own route network (ABM01, "Centrum - Sloterdijk via Haarlemmerweg") that has two or
more routes, the measured travel time beside NDW's normally expected one, snapshot by snapshot. Finds the moments
where the usually faster route is not the faster one now.
Writes traffic/morning.json."""
import json, os, re, sys, collections
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import ndw
REC = os.path.join(HERE, '..', 'live', 'rec_20261006')
sites = json.load(open(os.path.join(HERE, 'ndw_sites_ams.json')))
snaps = sorted(d for d in os.listdir(REC) if d.startswith('snap_') and os.path.exists(os.path.join(REC, d, 'reistijden_meetgegevens.xml.gz')))
cache = os.path.join(HERE, 'times_cache'); os.makedirs(cache, exist_ok=True)
T = {}
for s in snaps:
    cp = os.path.join(cache, s + '.json')
    if os.path.exists(cp): T[s] = json.load(open(cp)); continue
    try: m = ndw.load_times(os.path.join(REC, s, 'reistijden_meetgegevens.xml.gz'))
    except Exception as ex: print('skip', s, ex); continue
    m = {k: v for k, v in m.items() if k in sites}
    json.dump(m, open(cp, 'w')); T[s] = m
snaps = [s for s in snaps if s in T]
OD = collections.defaultdict(list)
for sid, s in sites.items():
    mm = re.match(r'(.+?)\s*-\s*(.+?)\s+via\s+(.+)$', s['name'])
    if mm and sid.startswith('ABM01'): OD[(mm.group(1).strip(), mm.group(2).strip())].append(dict(via=mm.group(3).strip(), sid=sid))
flips = []
series = {}
for od, routes in OD.items():
    if len(routes) < 2: continue
    ser = {}
    for r in routes:
        ser[r['sid']] = [(s[5:], T[s].get(r['sid'], {}).get('t'), T[s].get(r['sid'], {}).get('normal')) for s in snaps]
    series['|'.join(od)] = dict(routes=routes, ser=ser)
    for i, s in enumerate(snaps):
        now = {r['sid']: T[s].get(r['sid'], {}).get('t') for r in routes}; nor = {r['sid']: T[s].get(r['sid'], {}).get('normal') for r in routes}
        if not all(now.values()) or not all(nor.values()): continue
        ub = min(nor, key=nor.get); nb = min(now, key=now.get)
        if ub != nb:
            flips.append(dict(od=od, at=s[5:], usual=next(r['via'] for r in routes if r['sid'] == ub), now=next(r['via'] for r in routes if r['sid'] == nb),
                              gain=now[ub] - now[nb], usual_now=now[ub], usual_norm=nor[ub], best_now=now[nb], best_norm=nor[nb]))
json.dump(dict(snaps=[s[5:] for s in snaps], series=series, flips=flips), open(os.path.join(HERE, 'morning.json'), 'w'))
print(len(snaps), 'snapshots', snaps[0], '…', snaps[-1], '|', len(series), 'trips with routes to choose,', len(flips), 'flips')
best = collections.defaultdict(list)
for f in flips: best[f['od']].append(f)
for od, fs in sorted(best.items(), key=lambda kv: -max(f['gain'] for f in kv[1]))[:25]:
    f = max(fs, key=lambda f: f['gain'])
    cen = 'Centrum' in od
    print('%s%-34s usual %-20s now %-20s gain %4.1f′ at %s (%d snaps) usual %.1f′ (norm %.1f) best %.1f′ (norm %.1f)' % (
        '*' if cen else ' ', ' - '.join(od), f['usual'], f['now'], f['gain'] / 60, f['at'], len(fs), f['usual_now'] / 60, f['usual_norm'] / 60, f['best_now'] / 60, f['best_norm'] / 60))
