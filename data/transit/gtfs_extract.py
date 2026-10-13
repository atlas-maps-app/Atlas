"""Pulls one agency's trips that run on one day out of an OVapi GTFS zip (the national Dutch timetable).
python3 gtfs_extract.py <zip> <YYYYMMDD> <agency> <out.json>
Writes {stops, routes, trips: [{id, rt, route, type, head, dir, shape, st: [[stop_id, arr_s, dep_s], ...]}]}"""
import sys, csv, io, zipfile, json
Z, DAY, AG, OUT = sys.argv[1:5]
z = zipfile.ZipFile(Z)
def rows(name):
    return csv.DictReader(io.TextIOWrapper(z.open(name), encoding='utf-8-sig'))
routes = {r['route_id']: r for r in rows('routes.txt') if r['agency_id'] == AG}
svc = {r['service_id'] for r in rows('calendar_dates.txt') if r['date'] == DAY and r['exception_type'] == '1'}
trips = {}
for t in rows('trips.txt'):
    if t['route_id'] in routes and t['service_id'] in svc:
        r = routes[t['route_id']]
        trips[t['trip_id']] = dict(id=t['trip_id'], rt=t['realtime_trip_id'], route=r['route_short_name'], type=int(r['route_type']),
                                   head=t['trip_headsign'], dir=t['direction_id'], shape=t['shape_id'], st=[])
print('trips', len(trips), file=sys.stderr)
def secs(h):
    a, b, c = h.split(':'); return int(a) * 3600 + int(b) * 60 + int(c)
used = set()
with z.open('stop_times.txt') as f:
    head = f.readline().decode().strip().split(',')
    it, iseq, isid, iarr, idep = (head.index(k) for k in ('trip_id', 'stop_sequence', 'stop_id', 'arrival_time', 'departure_time'))
    for line in f:
        p = line.decode().split(',', 6)
        t = trips.get(p[it])
        if t is None: continue
        t['st'].append([int(p[iseq]), p[isid], secs(p[iarr]), secs(p[idep])]); used.add(p[isid])
for t in trips.values():
    t['st'].sort(); t['st'] = [s[1:] for s in t['st']]
stops = {}
for s in rows('stops.txt'):
    if s['stop_id'] in used: stops[s['stop_id']] = dict(name=s['stop_name'], lat=float(s['stop_lat']), lon=float(s['stop_lon']), plat=s['platform_code'], parent=s['parent_station'], wc=s['wheelchair_boarding'])
json.dump(dict(day=DAY, agency=AG, feed=Z.split('/')[-1], stops=stops, trips=list(trips.values())), open(OUT, 'w'))
print('stops', len(stops), file=sys.stderr)
