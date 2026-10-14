#!/usr/bin/env python3
"""Oracle for Real.kt's stage 1 helpers: XZ, num, the opening-hours parser (_days, open_at, _day_state, DAYS, RULE,
MONTHS) and the polygon helpers (signed_area, pip, centroid, seg_dist). Run with PYTHONHASHSEED=0.

    real_oracle.py helpers REPO     JSON on stdout
"""
import json, os, random, sys

import real as R

DAYS_ORDER = {d: i for i, d in enumerate(R.DAYS)}


def corpus(repo):
    """Every opening_hours value in data/ams/raw.json and the local region extracts, plus hand-made edge cases."""
    seen = []
    have = set()
    paths = [os.path.join(repo, 'data', 'ams', 'raw.json')]
    reg = os.path.join(repo, 'data', 'regions')
    if os.path.isdir(reg):
        for n in sorted(os.listdir(reg)):
            p = os.path.join(reg, n, 'raw.json')
            if os.path.isfile(p): paths.append(p)
    for p in paths:
        for e in json.load(open(p)).get('elements', []):
            for k in ('opening_hours', 'opening_hours:kitchen', 'service_times'):
                v = (e.get('tags') or {}).get(k)
                if v and v not in have:
                    have.add(v); seen.append(v)
    extra = ['24/7', ' 24/7 ', '', 'Mo-Fr 09:00-17:00', 'Mo-Fr 9:00-17:00; Sa 10:00-14:00; Su off', 'Th 10:00-01:00',
             'Fr-Mo 22:00-04:00', 'Su-Tu 08:00-12:00,13:00-18:00', 'Mo,We,Fr 10:00-12:00', 'Mo - Fr 08:00 - 18:00',
             'Mo-Su 00:00-24:00', 'Mo-Sa 10:00-18:00; PH off', 'Mo-Fr 10:00-18:00, PH 10:00-14:00', 'Dec 25 off',
             'Mo-Su 10:00-22:00; Dec 25 off; 2026 Jan 01 off', 'Mo-Su 10:00-22:00; Sep 30 12:00-14:00', 'sunrise-sunset',
             'closed', 'off', '10:00-20:00', 'Mo-Fr 08:00-12:00 open', 'Tu-Mo 07:00-07:00', 'Mo 00:00-00:00',
             'Mo-Fr 17:00-03:00; Sa,Su 12:00-04:00', 'We 23:59-00:01', 'Jan 1 Mo 10:00-12:00', '2025 Sep 30 off; Mo-Su 09:00-17:00',
             'Mo-Fr 09:00-17:00;Sa 09:00-13:00', 'Mo-Fr 09:00-17:00 ; PH off', 'Mo-Th 11:00-23:00; Fr-Sa 11:00-01:00; Su 12:00-22:00',
             'Mo-Fr 08:00-11:30,12:30-17:00', 'Mo‑Fr 09:00‑17:00', 'Mo-Fr 09:00-17:00 "by appointment"', 'Mo-Fr 09:00-17:00']
    for v in extra:
        if v not in have: have.add(v); seen.append(v)
    return seen


def helpers(repo):
    r = random.Random(14)
    out = {}
    pts = [(r.uniform(40.70, 40.74), r.uniform(-73.99, -73.93)) for _ in range(200)] + [(52.374, 4.884), (R.LAT0, R.LON0)]
    out['consts'] = {'LAT0': R.LAT0, 'LON0': R.LON0, 'KX': R.KX, 'KZ': R.KZ, 'DAYS': R.DAYS, 'MONTHS': R.MONTHS,
                     'RULE': R.RULE.pattern}
    out['XZ'] = [[la, lo, R.XZ(la, lo)] for la, lo in pts]
    nums = [None, 12, 12.5, -3, True, False, '12', ' 12 m', '-3.5', '3.', '.5', '12.25.3', 'abc', '', '  7.0e3', '١٢', '٣.٥',
            '−5', '+5', '0012', '-0', '-0.0', '1_000', '\t\n 42;43', '12,5', '1e10', '12.', '9' * 30, 0.1, 1e300, -0.0]
    rows = []
    for v in nums:
        for dflt in (None, 3.0):
            rows.append([v, dflt, R.num(v, dflt)])
    out['num'] = rows
    sels = ['Mo', 'Mo-Fr', 'Fr-Mo', 'Su-Tu', 'Mo,We,Fr', 'Mo-We, Fr', ' Sa , Su ', 'Mo-Mo', 'Tu-Mo', 'Xx', 'Mo,Xx', '']
    days = []
    for s in sels:
        try:
            v = sorted(R._days(s), key=DAYS_ORDER.get)
        except ValueError as e:
            v = 'ValueError'
        days.append([s, v])
    out['_days'] = days
    oh = corpus(repo)
    times = [(0, 30), (9, 0), (17, 59), (23, 30)]
    dates = [(2026, 9, 30), (2026, 12, 25)]
    res = []
    for s in oh:
        row = {'oh': s, 'open_at': [], 'state': []}
        for day in R.DAYS:
            for hm in times:
                for dt in dates:
                    try:
                        row['open_at'].append(R.open_at(s, day, hm, dt))
                    except (ValueError, IndexError) as e:
                        row['open_at'].append('error')
        for day in R.DAYS:
            for t, spill in ((600, False), (1500, True), (0, False)):
                try:
                    row['state'].append(list(R._day_state(s.strip(), day, t, (2026, 9, 30), spill)))
                except (ValueError, IndexError):
                    row['state'].append('error')
        res.append(row)
    out['open_at'] = {'times': times, 'dates': dates, 'rows': res}
    polys = []
    for _ in range(80):
        n = r.randint(3, 12)
        cx, cz = r.uniform(-500, 500), r.uniform(-500, 500)
        poly = [(cx + r.uniform(-50, 50), cz + r.uniform(-50, 50)) for _ in range(n)]
        if r.random() < 0.3: poly = [(round(x), round(z)) for x, z in poly]
        qs = [(cx + r.uniform(-60, 60), cz + r.uniform(-60, 60)) for _ in range(20)] + list(poly[:3])
        segs = []
        for q in qs:
            a, b = poly[0], poly[1]
            segs.append([q, a, b, R.seg_dist(q[0], q[1], a, b), R.seg_dist(q[0], q[1], a, a)])
        polys.append({'poly': poly, 'signed_area': R.signed_area(poly), 'centroid': R.centroid(poly),
                      'pip': [[q, R.pip(q[0], q[1], poly)] for q in qs], 'seg': segs})
    out['polys'] = polys
    return out


if __name__ == '__main__':
    if sys.argv[1] == 'helpers':
        json.dump(helpers(sys.argv[2]), sys.stdout, ensure_ascii=False)
