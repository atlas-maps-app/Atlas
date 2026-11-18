#!/usr/bin/env python3
"""Packs a GTFS feed into Atlas's timetable file (app/android/transit/FORMAT.md), which the planner reads as it is.

    python3 -I app/android/transit/pack/ttpack.py <gtfs.zip or folder> <out.tt> [options]

Options:
  --first YYYYMMDD   the first service day to keep (default: the feed's first)
  --days N           how many days to keep from there (default: all of the feed's)
  --box S,W,N,E      keep only the stops inside the box (degrees); a trip keeps its stops inside, and needs two
  --agency A,B       keep only these agencies' lines (agency_id)
  --sizes            print each section's size, and the file's gzip -9 and xz sizes
  --strict           refuse a feed with a table or a column that has values and that the packer does not read

Every table and column of the feed that is not read is printed, with how much it holds. With --strict (as the weekly
workflow packs) that ends the run with nothing written: a feed that grew is looked at by a person before any of it is
published without what it gained.

The output is the same bytes for the same feed and options: every order is a sort with a full key, and walks are
computed in integers. Python 3's standard library only. Left out, as FORMAT.md says: shapes.txt (ODbL), the
ticketing tables (CC BY-NC-ND), fares v2. Everything else in the feed that a person could be shown is kept.
"""

import argparse
import csv
import datetime
from decimal import ROUND_HALF_UP, Decimal
import gzip
import io
import lzma
import math
import os
import struct
import sys
import time
import zipfile

MAGIC = b"ATLASTT\0"
VERSION = 1
NONE = 0xFFFFFFFF
MAX_DAYS = 400                  # a file's most (the planner's file.rs, MAX_DAYS)

# walks between stops (FORMAT.md, "Walks"): a change at the same stop, between stops of one station, to any stop near
WALK_SAME_STOP_S = 60
WALK_SAME_STATION_MIN_S = 120
WALK_RADIUS_M = 400
WALK_SPEED_MM_S = 1250          # 1.25 m/s
WALK_DETOUR_PERCENT = 130       # a street is 30 % longer than the straight line
WALK_CHANGE_S = 60              # what a change on foot costs besides the walk


class Feed:
    """Reads a GTFS zip or folder by table, as rows of dicts or of lists."""

    def __init__(self, path):
        self.path = path
        self.zip = zipfile.ZipFile(path) if os.path.isfile(path) else None

    def names(self):
        return sorted(self.zip.namelist() if self.zip else os.listdir(self.path))

    def has(self, name):
        if self.zip:
            return name in self.zip.namelist()
        return os.path.exists(os.path.join(self.path, name))

    def open(self, name):
        if self.zip:
            return io.TextIOWrapper(self.zip.open(name), encoding="utf-8-sig", newline="")
        return open(os.path.join(self.path, name), encoding="utf-8-sig", newline="")

    def rows(self, name):
        if not self.has(name):
            return []
        return csv.DictReader(self.open(name))


T0 = time.time()


def log(*a):
    print("[%5.0fs]" % (time.time() - T0), *a, file=sys.stderr, flush=True)


def peak_memory():
    """The most memory this run has held so far, in bytes; None where the system does not say."""
    try:
        import resource
    except ImportError:
        return None
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss  # bytes on macOS, kilobytes elsewhere
    return peak if sys.platform == "darwin" else peak * 1024


def secs(h):
    """A GTFS time (H:MM:SS, hours past 24 allowed) in seconds, or None when empty."""
    h = h.strip()
    if not h:
        return None
    a, b, c = h.split(":")
    return int(a) * 3600 + int(b) * 60 + int(c)


def day(s):
    return datetime.date(int(s[0:4]), int(s[4:6]), int(s[6:8]))


def e7(x):
    """Degrees to integer 1e-7 degrees, rounded half away from zero on the decimal text (no binary float)."""
    return int((Decimal(x.strip()) * 10000000).to_integral_value(rounding=ROUND_HALF_UP))


def color(s):
    s = (s or "").strip().lstrip("#")
    if len(s) == 6:
        try:
            return int(s, 16)
        except ValueError:
            pass
    return NONE


def opt_int(s, default):
    s = (s or "").strip()
    if not s:
        return default
    try:
        return int(s)
    except ValueError:
        return int(float(s))


def isqrt_round(n):
    r = math.isqrt(n)
    return r + 1 if (r + 1) * (r + 1) - n <= n - r * r else r


# cos(latitude) in 1/65536, by the tenth of a degree, from a table of integers (FORMAT.md, "Distances"); the planner
# holds the same table
COS_TABLE = [round(65536 * math.cos(math.radians(i / 10))) for i in range(901)]


def cos_e16(lat):
    """cos(latitude) in 1/65536 for a latitude in 1e-7 degrees, by the tenth of a degree (rounded down)."""
    return COS_TABLE[min(abs(lat) // 1000000, 900)]


def metres_mm(lat1, lon1, lat2, lon2):
    """Millimetres between two points in 1e-7 degrees, on the plane at their mean latitude. Integers only, so the
    planner (FORMAT.md, "Distances") gets the same numbers."""
    c = cos_e16((lat1 + lat2) // 2)
    # 1e-7 degree of latitude is 11.1195 mm: 111195 m a degree on a sphere of 6371.0 km
    dy = (lat2 - lat1) * 111195 // 10000
    dx = (lon2 - lon1) * 111195 // 10000 * c // 65536
    return isqrt_round(dx * dx + dy * dy)


def walk_s(mm):
    return WALK_CHANGE_S + (mm * WALK_DETOUR_PERCENT // 100 + WALK_SPEED_MM_S - 1) // WALK_SPEED_MM_S


class Strings:
    """The file's strings: one blob, each string once, as its length (LEB128) and its UTF-8 bytes. A reference is the
    byte offset of the length; offset 0 is the empty string."""

    def __init__(self):
        self.index = {"": 0}
        self.blob = bytearray(b"\0")

    def __call__(self, s):
        s = s or ""
        i = self.index.get(s)
        if i is None:
            i = self.index[s] = len(self.blob)
            b = s.encode("utf-8")
            n = len(b)
            while n >= 0x80:
                self.blob.append((n & 0x7F) | 0x80)
                n >>= 7
            self.blob.append(n)
            self.blob += b
        return i

    def column(self, values):
        """A column's references, its strings added one after another so that like lies by like."""
        return [self(v) for v in values]


# What the packer reads, column by column, and what it leaves out by name. Any other table or column of a feed is
# printed, never dropped quietly; --strict makes it a refusal.
READ = {
    "agency.txt": "agency_id agency_name agency_url agency_timezone agency_phone agency_lang",
    "calendar.txt": "service_id monday tuesday wednesday thursday friday saturday sunday start_date end_date",
    "calendar_dates.txt": "service_id date exception_type",
    "feed_info.txt": "feed_publisher_name feed_publisher_url feed_lang feed_version feed_start_date feed_end_date feed_id "
                     "feed_contact_email feed_contact_url",
    "licenses.txt": "licensed_table_name license_spdx_id custom_license_url",
    "routes.txt": "route_id agency_id route_short_name route_long_name route_desc route_type route_color route_text_color "
                  "route_url route_sort_order",
    "stop_times.txt": "trip_id stop_sequence stop_id stop_headsign arrival_time departure_time pickup_type drop_off_type "
                      "timepoint shape_dist_traveled fare_units_traveled",
    "stops.txt": "stop_id stop_code stop_name stop_lat stop_lon location_type parent_station stop_timezone "
                 "wheelchair_boarding platform_code zone_id stop_ifopt stop_desc",
    "transfers.txt": "from_stop_id to_stop_id from_route_id to_route_id from_trip_id to_trip_id transfer_type "
                     "min_transfer_time",
    "trips.txt": "route_id service_id trip_id realtime_trip_id trip_headsign trip_short_name trip_long_name direction_id "
                 "block_id wheelchair_accessible bikes_allowed",
}
READ = {k: set(v.split()) for k, v in READ.items()}
LEFT_OUT = {"shapes.txt": "ODbL in OVapi's feed; a ride is drawn on the map's own rails and roads",
            "ticketing_identifiers.txt": "CC BY-NC-ND in OVapi's feed", "ticketing_deep_links.txt": "CC BY-NC-ND in OVapi's feed"}
LEFT_OUT_COLUMNS = {"trips.txt": {"shape_id": "the shapes are left out"}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("feed")
    ap.add_argument("out")
    ap.add_argument("--first")
    ap.add_argument("--days", type=int)
    ap.add_argument("--box")
    ap.add_argument("--agency")
    ap.add_argument("--sizes", action="store_true")
    ap.add_argument("--strict", action="store_true")
    a = ap.parse_args()
    feed = Feed(a.feed)
    S = Strings()
    count = {}
    not_read = []            # what the feed holds that the packer has no place for

    def unread(what, n):
        if n:
            not_read.append(what)
            print("NOT READ: %s (%d %s): the packer has no place for it yet" % (what, n, "rows" if what.endswith(".txt") else "values"))
        else:
            print("empty, so nothing is lost: %s" % what)

    for name in feed.names():
        if name in LEFT_OUT:
            print("left out: %s (%s)" % (name, LEFT_OUT[name]))
        elif name not in READ:
            if name.endswith(".txt"):
                unread(name, sum(1 for _ in feed.rows(name)))
            else:
                print("not a table: %s" % name)
        elif name != "stop_times.txt":       # (its columns are looked at while it is read, below)
            with feed.open(name) as f:
                head = [c.strip() for c in next(csv.reader(f), [])]
            for c in head:
                if c in LEFT_OUT_COLUMNS.get(name, {}):
                    print("left out: %s column %s (%s)" % (name, c, LEFT_OUT_COLUMNS[name][c]))
                elif c and c not in READ[name]:
                    unread("%s column %s" % (name, c), sum(1 for r in feed.rows(name) if (r.get(c) or "").strip()))
    if any(True for _ in feed.rows("frequencies.txt")):
        sys.exit("frequencies.txt has rows: trips by headway are not packed yet")

    def counted(k, n=1):
        count[k] = count.get(k, 0) + n

    # ---- the days
    services = {}           # service_id -> set of dates
    for r in feed.rows("calendar.txt"):
        d0, d1 = day(r["start_date"]), day(r["end_date"])
        week = [r[k] == "1" for k in ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")]
        s = services.setdefault(r["service_id"], set())
        d = d0
        while d <= d1:
            if week[d.weekday()]:
                s.add(d)
            d += datetime.timedelta(days=1)
    for r in feed.rows("calendar_dates.txt"):
        s = services.setdefault(r["service_id"], set())
        if r["exception_type"].strip() == "1":
            s.add(day(r["date"]))
        elif r["exception_type"].strip() == "2":
            s.discard(day(r["date"]))
    alldays = sorted(set().union(*services.values())) if services else []
    if not alldays:
        sys.exit("the feed runs on no day")
    first = day(a.first) if a.first else alldays[0]
    last = alldays[-1] if a.days is None else first + datetime.timedelta(days=a.days - 1)
    ndays = (last - first).days + 1
    if a.days is None and ndays > MAX_DAYS:
        # a feed of more days than a file holds (the new year's timetable handed in early): its far end is left out
        print("left out: the feed's days after %s (it has %d from %s; a file holds %d)"
              % (first + datetime.timedelta(days=MAX_DAYS - 1), ndays, first, MAX_DAYS))
        last, ndays = first + datetime.timedelta(days=MAX_DAYS - 1), MAX_DAYS
    if ndays < 1 or ndays > MAX_DAYS:
        sys.exit("days: %d, from %s; 1 to %d are allowed" % (ndays, first, MAX_DAYS))
    svc_bits = {}
    for k, ds in services.items():
        b = 0
        for d in ds:
            i = (d - first).days
            if 0 <= i < ndays:
                b |= 1 << i
        if b:
            svc_bits[k] = b
    log("days", first, "to", last, ndays, "services running", len(svc_bits), "of", len(services))

    # ---- agencies, lines
    agencies = list(feed.rows("agency.txt"))
    keep_ag = set(a.agency.split(",")) if a.agency else None
    if len(agencies) == 1 and not agencies[0].get("agency_id"):
        agencies[0]["agency_id"] = ""
    ag_index = {}
    routes = {}
    for r in feed.rows("routes.txt"):
        ag = r.get("agency_id", "") or (agencies[0]["agency_id"] if len(agencies) == 1 else "")
        if keep_ag is not None and ag not in keep_ag:
            continue
        r["agency_id"] = ag
        routes[r["route_id"]] = r

    # ---- stops (all of them for now; the unused are dropped below)
    box = [e7(x) for x in a.box.split(",")] if a.box else None
    stops = {}
    for r in feed.rows("stops.txt"):
        if not r.get("stop_lat", "").strip():
            counted("stops without a place, left out")
            continue
        r["lat"], r["lon"] = e7(r["stop_lat"]), e7(r["stop_lon"])
        r["place"] = 0
        stops[r["stop_id"]] = r
    # a stop at 0,0 has no place of its own (OVapi: 201 platforms abroad): it takes its station's, and says so
    for r in stops.values():
        if r["lat"] == 0 and r["lon"] == 0:
            p = stops.get(r.get("parent_station", ""))
            if p is not None and (p["lat"] != 0 or p["lon"] != 0):
                r["lat"], r["lon"], r["place"] = p["lat"], p["lon"], 1
                counted("stops without a place, put at their station's")
            else:
                r["place"] = 2
                counted("stops without a place and without a station that has one")

    def inside(s):
        return box is None or (box[0] <= s["lat"] <= box[2] and box[1] <= s["lon"] <= box[3])

    # ---- trips
    trips = {}
    for r in feed.rows("trips.txt"):
        if r["route_id"] not in routes or r["service_id"] not in svc_bits:
            continue
        trips[r["trip_id"]] = r
    log("lines", len(routes), "trips running", len(trips))

    # ---- stop times, a trip at a time
    patterns = {}            # key -> index
    profiles = {}            # times -> index
    kept = []                # (pattern, first_dep, profile, trip_id)
    seen = set()
    fixes = {"stops without a time, filled in": 0, "times going back, raised": 0}

    def close(tid, rows_):
        t = trips.get(tid)
        if t is None:
            return
        rows_.sort(key=lambda x: x[0])
        rows_ = [x for x in rows_ if inside(stops[x[1]])] if box else rows_
        if len(rows_) < 2:
            counted("trips with fewer than two stops, left out")
            return
        # a stop without a time takes one between its timed neighbours, by its place in the sequence
        times = [[x[2], x[3]] for x in rows_]
        for x in times:
            if x[0] is None:
                x[0] = x[1]
            if x[1] is None:
                x[1] = x[0]
        known = [i for i, x in enumerate(times) if x[0] is not None]
        if not known:
            counted("trips without times, left out")
            return
        for i, x in enumerate(times):
            if x[0] is None:
                fixes["stops without a time, filled in"] += 1
                lo = max((k for k in known if k < i), default=None)
                hi = min((k for k in known if k > i), default=None)
                if lo is None:
                    v = times[hi][0]
                elif hi is None:
                    v = times[lo][1]
                else:
                    v = times[lo][1] + (times[hi][0] - times[lo][1]) * (i - lo) // (hi - lo)
                x[0] = x[1] = v
        prev = times[0][0]
        for x in times:
            for j in (0, 1):
                if x[j] < prev:
                    fixes["times going back, raised"] += 1
                    x[j] = prev
                prev = x[j]
        start = times[0][1]
        prof = [0, 0]
        for x in times[1:]:
            prof.append(x[0] - start)
            prof.append(x[1] - start)
        pstops = tuple((x[1], x[4], x[5], x[6], x[7]) for x in rows_)
        pk = (t["route_id"], t.get("trip_headsign", ""), t.get("direction_id", ""), pstops)
        p = patterns.setdefault(pk, len(patterns))
        q = profiles.setdefault(tuple(prof), len(profiles))
        kept.append((p, start, q, tid))

    log("reading stop_times.txt")
    nrows = 0
    with feed.open("stop_times.txt") as f:
        rd = csv.reader(f)
        head = next(rd)
        col = {k.strip(): i for i, k in enumerate(head)}
        extra = {i: 0 for k, i in col.items() if k and k not in READ["stop_times.txt"]}

        def c(row, k):
            i = col.get(k)
            return row[i] if i is not None and i < len(row) else ""

        cur, buf = None, []
        for row in rd:
            nrows += 1
            for i in extra:
                if i < len(row) and row[i].strip():
                    extra[i] += 1
            tid = row[col["trip_id"]]
            if tid != cur:
                if cur is not None:
                    close(cur, buf)
                    seen.add(cur)
                if tid in seen:
                    sys.exit("stop_times.txt: trip %s is not in one run of rows; sort the file by trip first" % tid)
                cur, buf = tid, []
            if tid not in trips:
                continue
            sid = row[col["stop_id"]]
            if sid not in stops:
                counted("stop times at unknown stops, left out")
                continue
            pickup = opt_int(c(row, "pickup_type"), 0) & 3
            drop = opt_int(c(row, "drop_off_type"), 0) & 3
            approx = 1 if c(row, "timepoint").strip() == "0" else 0
            fare = opt_int(c(row, "fare_units_traveled"), NONE)
            dist = c(row, "shape_dist_traveled").strip()
            dist = round(float(dist)) if dist else NONE
            buf.append((int(row[col["stop_sequence"]]), sid, secs(c(row, "arrival_time")), secs(c(row, "departure_time")),
                        pickup | (drop << 2) | (approx << 4), c(row, "stop_headsign"), fare, dist))
            if nrows % 2000000 == 0:
                log("stop_times", nrows, "patterns", len(patterns), "profiles", len(profiles))
        if cur is not None:
            close(cur, buf)
        for i, n in sorted(extra.items()):
            unread("stop_times.txt column %s" % head[i].strip(), n)
    if a.strict and not_read:
        sys.exit("refused (--strict): the feed has what the packer does not read: %s. Nothing was written." % "; ".join(not_read))
    log("stop_times", nrows, "trips kept", len(kept), "patterns", len(patterns), "profiles", len(profiles))

    # ---- which stops are used: the ones trips call at, their stations, and the stations' other parts
    used = set()
    for pk in patterns:
        for x in pk[3]:
            used.add(x[0])
    more = set()
    for sid in used:
        p = stops[sid].get("parent_station", "")
        while p and p in stops and p not in more:
            more.add(p)
            p = stops[p].get("parent_station", "")
    used |= more
    for sid, s in stops.items():      # entrances, nodes and boarding areas of a used station
        if s.get("parent_station", "") in more:
            used.add(sid)
    counted("stops not used by any trip kept, left out", len(stops) - len(used))

    def zorder(s):
        x, y = (s["lon"] + 1800000000) // 100000, (s["lat"] + 900000000) // 100000
        z = 0
        for i in range(16):
            z |= ((x >> i) & 1) << (2 * i) | ((y >> i) & 1) << (2 * i + 1)
        return z

    stop_list = sorted(used, key=lambda k: (zorder(stops[k]), k))
    sidx = {k: i for i, k in enumerate(stop_list)}
    if len(stop_list) >= 1 << 27:
        sys.exit("too many stops")

    # ---- lines and agencies, the used ones, in the feed's order
    route_order = {r: i for i, r in enumerate(routes)}
    used_routes = sorted({pk[0] for pk in patterns}, key=lambda r: route_order[r])
    ridx = {r: i for i, r in enumerate(used_routes)}
    used_ag = []
    for r in used_routes:
        ag = routes[r]["agency_id"]
        if ag not in ag_index:
            ag_index[ag] = len(used_ag)
            used_ag.append(ag)
    ag_rows = {x.get("agency_id", ""): x for x in agencies}

    # ---- patterns in a fixed order: by line, then the stop sequence
    pat_items = sorted(patterns.items(), key=lambda kv: (ridx[kv[0][0]], [sidx[x[0]] for x in kv[0][3]], kv[0][1], kv[0][2], kv[0][3]))
    pat_new = {}
    for i, (pk, old) in enumerate(pat_items):
        pat_new[old] = i
    prof_items = sorted(profiles.items(), key=lambda kv: (len(kv[0]), kv[0]))
    prof_new = {old: i for i, (_, old) in enumerate(prof_items)}
    prof_by_new = [pr for pr, _ in prof_items]

    # ---- routes for rounds: a pattern's trips by first departure, split so no trip overtakes another at any stop
    by_pat = {}
    for p, start, q, tid in kept:
        by_pat.setdefault(pat_new[p], []).append((start, prof_new[q], tid))
    rout = []                # (pattern, [trips])
    for p in range(len(pat_items)):
        ts = sorted(by_pat.get(p, []), key=lambda x: (x[0], prof_by_new[x[1]], x[2]))
        groups = []
        for t in ts:
            tt = [t[0] + v for v in prof_by_new[t[1]]]
            for g in groups:
                if all(a <= b for a, b in zip(g[1], tt)):
                    g[0].append(t)
                    g[1] = tt
                    break
            else:
                groups.append([[t], tt])
        for g in groups:
            rout.append((p, g[0]))
    counted("routes beyond one a pattern (a trip overtakes another)", len(rout) - len(by_pat))

    # ---- services: the used ones, numbered by their bits then id
    used_svc = sorted({trips[t[2]]["service_id"] for _, g in rout for t in g}, key=lambda k: (svc_bits[k], k))
    vidx = {k: i for i, k in enumerate(used_svc)}

    # ---- trip index
    trip_list = []           # (first_dep, profile, tid)
    rout_rec = []
    for p, g in rout:
        rout_rec.append((p, len(trip_list), len(g)))
        trip_list.extend(g)
    tidx = {t[2]: i for i, t in enumerate(trip_list)}

    # ---- walks between stops
    log("walks")
    cell = {}
    CELL = 50000             # 0.005 degree, about 350 m by 550 m here
    for i, k in enumerate(stop_list):
        s = stops[k]
        if s.get("location_type", "0").strip() not in ("", "0") or s["place"] == 2:
            continue
        cell.setdefault((s["lat"] // CELL, s["lon"] // CELL), []).append(i)
    station_of = []
    for k in stop_list:
        p = stops[k].get("parent_station", "")
        station_of.append(sidx.get(p, NONE) if p else NONE)
    walks = [dict() for _ in stop_list]
    reach_lat = WALK_RADIUS_M * 10000000 // 111195 + 1
    for i, k in enumerate(stop_list):
        s = stops[k]
        if s.get("location_type", "0").strip() not in ("", "0") or s["place"] == 2:
            continue
        cy, cx = s["lat"] // CELL, s["lon"] // CELL
        ky = reach_lat // CELL + 1
        kx = reach_lat * 65536 // max(cos_e16(s["lat"]), 1) // CELL + 1
        for y in range(cy - ky, cy + ky + 1):
            for x in range(cx - kx, cx + kx + 1):
                for j in cell.get((y, x), ()):
                    if j == i:
                        continue
                    o = stops[stop_list[j]]
                    mm = metres_mm(s["lat"], s["lon"], o["lat"], o["lon"])
                    same = station_of[i] != NONE and station_of[i] == station_of[j]
                    if mm > WALK_RADIUS_M * 1000 and not same:
                        continue
                    w = walk_s(mm)
                    if same:
                        w = max(w, WALK_SAME_STATION_MIN_S)
                    walks[i][j] = w
    # stations whose parts lie further apart than the radius still connect
    parts = {}
    for i, st in enumerate(station_of):
        if st != NONE and stops[stop_list[i]].get("location_type", "0").strip() in ("", "0"):
            parts.setdefault(st, []).append(i)
    for st, ps in parts.items():
        for i in ps:
            for j in ps:
                if i != j and j not in walks[i]:
                    s, o = stops[stop_list[i]], stops[stop_list[j]]
                    if s["place"] == 2 or o["place"] == 2:
                        walks[i][j] = WALK_SAME_STATION_MIN_S
                    else:
                        walks[i][j] = max(walk_s(metres_mm(s["lat"], s["lon"], o["lat"], o["lon"])), WALK_SAME_STATION_MIN_S)

    # ---- transfer rules
    rules = []
    for r in feed.rows("transfers.txt"):
        fs, ts_ = r.get("from_stop_id", ""), r.get("to_stop_id", "")
        if fs not in sidx or ts_ not in sidx:
            counted("transfer rules at stops left out, left out")
            continue
        ft, tt_ = r.get("from_trip_id", ""), r.get("to_trip_id", "")
        fr, tr = r.get("from_route_id", ""), r.get("to_route_id", "")
        if (ft and ft not in tidx) or (tt_ and tt_ not in tidx) or (fr and fr not in ridx) or (tr and tr not in ridx):
            counted("transfer rules of trips or lines left out, left out")
            continue
        kind = opt_int(r.get("transfer_type", ""), 0)
        if kind > 5:
            counted("transfer rules of unknown type, left out")
            continue
        mt = opt_int(r.get("min_transfer_time", ""), NONE)
        i, j = sidx[fs], sidx[ts_]
        rec = (i, j, ridx[fr] if fr else NONE, ridx[tr] if tr else NONE, tidx[ft] if ft else NONE, tidx[tt_] if tt_ else NONE, kind, mt)
        rules.append(rec)
        if not ft and not tt_ and not fr and not tr and i != j:
            # a rule between two stops for everyone changes the walk itself
            if kind == 3:
                walks[i].pop(j, None)
            elif kind == 2 and mt != NONE:
                walks[i][j] = mt
            elif kind in (0, 1) and j not in walks[i]:
                s, o = stops[stop_list[i]], stops[stop_list[j]]
                if s["place"] != 2 and o["place"] != 2:
                    walks[i][j] = walk_s(metres_mm(s["lat"], s["lon"], o["lat"], o["lon"]))
    rules.sort()
    log("walks", sum(len(w) for w in walks), "transfer rules", len(rules))

    # ---- META
    meta = []
    for r in feed.rows("feed_info.txt"):
        for k in ("feed_publisher_name", "feed_publisher_url", "feed_lang", "feed_version", "feed_start_date", "feed_end_date", "feed_id", "feed_contact_email", "feed_contact_url"):
            if r.get(k):
                meta.append((k, r[k]))
        break
    for r in feed.rows("licenses.txt"):
        meta.append(("licence:" + (r.get("licensed_table_name") or "*"), r.get("license_spdx_id") or r.get("custom_license_url") or ""))
    meta += [
        ("first_day", first.strftime("%Y%m%d")),
        ("days", str(ndays)),
        ("timezone", (agencies[0].get("agency_timezone", "") if agencies else "")),
        ("change_same_stop_s", str(WALK_SAME_STOP_S)),
        ("walks", "line radius_m=%d same_station_min_s=%d change_s=%d speed_mm_s=%d detour_percent=%d"
         % (WALK_RADIUS_M, WALK_SAME_STATION_MIN_S, WALK_CHANGE_S, WALK_SPEED_MM_S, WALK_DETOUR_PERCENT)),
        ("source", os.path.basename(a.feed)),
        ("cut", " ".join(x for x in (a.first and "first=" + a.first, a.days and "days=%d" % a.days, a.box and "box=" + a.box, a.agency and "agency=" + a.agency) if x)),
    ]
    tz = {x.get("agency_timezone", "") for x in agencies}
    if len(tz) > 1:
        meta.append(("timezones", " ".join(sorted(tz))))

    # ---- sections
    def u32s(vals):
        return struct.pack("<%dI" % len(vals), *vals)

    def records(*columns):
        """Columns of equal length as records, one after another."""
        return u32s([v for row in zip(*columns) for v in row])

    sec = {}
    ss = [stops[k] for k in stop_list]
    sec[b"STOP"] = (records(
        [s["lat"] & 0xFFFFFFFF for s in ss], [s["lon"] & 0xFFFFFFFF for s in ss],
        [sidx.get(s.get("parent_station", ""), NONE) if s.get("parent_station", "") else NONE for s in ss],
        [(opt_int(s.get("location_type", ""), 0) & 7) | ((opt_int(s.get("wheelchair_boarding", ""), 0) & 3) << 3) | (s["place"] << 5) for s in ss],
        S.column(stop_list), S.column(s.get("stop_code") for s in ss), S.column(s.get("stop_name") for s in ss),
        S.column(s.get("platform_code") for s in ss), S.column(s.get("zone_id") for s in ss), S.column(s.get("stop_ifopt") for s in ss),
        S.column(s.get("stop_timezone") for s in ss), S.column(s.get("stop_desc") for s in ss)), len(ss))
    xs = [ag_rows.get(ag, {}) for ag in used_ag]
    sec[b"AGCY"] = (records(S.column(used_ag), *(S.column(x.get(k) for x in xs) for k in ("agency_name", "agency_url", "agency_timezone", "agency_phone", "agency_lang"))), len(xs))
    xs = [routes[r] for r in used_routes]
    sec[b"LINE"] = (records(
        [ag_index[x["agency_id"]] for x in xs], S.column(used_routes), S.column(x.get("route_short_name") for x in xs),
        S.column(x.get("route_long_name") for x in xs), S.column(x.get("route_desc") for x in xs), [opt_int(x.get("route_type"), 3) for x in xs],
        [color(x.get("route_color")) for x in xs], [color(x.get("route_text_color")) for x in xs], S.column(x.get("route_url") for x in xs),
        [opt_int(x.get("route_sort_order"), NONE) for x in xs]), len(xs))
    pfirst, pst, phs, pfa, pdi = [], [], [], [], []
    for pk, _ in pat_items:
        ps = pk[3]
        if len(ps) > 65535:
            sys.exit("a pattern of %d stops; 65535 are allowed" % len(ps))
        pfirst.append(len(pst))
        for sid, flags, hs, fare, dist in ps:
            pst.append(sidx[sid] | (flags << 27))
            phs.append(hs)
            pfa.append(fare)
            pdi.append(dist)
    sec[b"PATN"] = (records([ridx[pk[0]] for pk, _ in pat_items], S.column(pk[1] for pk, _ in pat_items), [opt_int(pk[2], NONE) for pk, _ in pat_items],
                            pfirst, [len(pk[3]) for pk, _ in pat_items]), len(pat_items))
    sec[b"PSTP"] = (u32s(pst), len(pst))
    sec[b"PHSG"] = (u32s(S.column(phs)), len(phs))
    sec[b"PFAR"] = (u32s(pfa), len(pfa))
    sec[b"PDST"] = (u32s(pdi), len(pdi))
    rec, tim = [], []
    for pr in prof_by_new:
        rec += [len(tim) // 2, len(pr) // 2]
        tim += pr
    if tim and max(tim) >= 1 << 20:
        sys.exit("a trip of %d s; under %d are allowed" % (max(tim), 1 << 20))
    sec[b"PROF"] = (u32s(rec), len(prof_by_new))
    sec[b"PTIM"] = (u32s(tim), len(tim) // 2)
    sec[b"ROUT"] = (u32s([v for r in rout_rec for v in r]), len(rout_rec))
    ts = [trips[tid] for _, _, tid in trip_list]
    if trip_list and max(t[0] for t in trip_list) >= 1 << 20:
        sys.exit("a first departure at %d s; under %d are allowed" % (max(t[0] for t in trip_list), 1 << 20))
    sec[b"TRIP"] = (records(
        [t[0] for t in trip_list], [t[1] for t in trip_list], [vidx[t["service_id"]] for t in ts],
        [(opt_int(t.get("wheelchair_accessible"), 0) & 3) | ((opt_int(t.get("bikes_allowed"), 0) & 3) << 2) for t in ts]), len(ts))
    sec[b"TRIX"] = (records(S.column(t[2] for t in trip_list), *(S.column(t.get(k) for t in ts) for k in ("realtime_trip_id", "trip_short_name", "trip_long_name", "block_id"))), len(ts))
    words = (ndays + 31) // 32
    rec = []
    for k in used_svc:
        b = svc_bits[k]
        rec += [(b >> (32 * w)) & 0xFFFFFFFF for w in range(words)]
    sec[b"SERV"] = (u32s(rec), len(used_svc))
    sec[b"SVID"] = (u32s(S.column(used_svc)), len(used_svc))
    first_i, xf = [], []
    for w in walks:
        first_i.append(len(xf) // 2)
        for j in sorted(w):
            xf += [j, w[j]]
    first_i.append(len(xf) // 2)
    if xf and max(xf[1::2]) >= 1 << 20:
        sys.exit("a walk of %d s; under %d are allowed" % (max(xf[1::2]), 1 << 20))
    sec[b"XFRI"] = (u32s(first_i), len(first_i))
    sec[b"XFER"] = (u32s(xf), len(xf) // 2)
    sec[b"TRUL"] = (u32s([v for r in rules for v in r]), len(rules))
    # the routes at each stop, with the stop's place along each: what a round starts from
    at = [[] for _ in stop_list]
    for ri, (p, _first, _n) in enumerate(rout_rec):
        for pos, x in enumerate(pat_items[p][0][3]):
            at[sidx[x[0]]].append((ri, pos))
    first_i, sr = [], []
    for v in at:
        first_i.append(len(sr) // 2)
        for ri, pos in v:
            sr += [ri, pos]
    first_i.append(len(sr) // 2)
    sec[b"SRTI"] = (u32s(first_i), len(first_i))
    sec[b"SRTE"] = (u32s(sr), len(sr) // 2)
    sec[b"META"] = ("".join("%s=%s\n" % (k, v.replace("\n", " ")) for k, v in meta).encode(), len(meta))
    sec[b"STRS"] = (bytes(S.blob), len(S.index))

    order = [b"META", b"STOP", b"AGCY", b"LINE", b"PATN", b"PSTP", b"PROF", b"PTIM", b"ROUT", b"TRIP", b"SERV", b"XFRI", b"XFER",
             b"SRTI", b"SRTE", b"TRUL", b"PHSG", b"PFAR", b"PDST", b"TRIX", b"SVID", b"STRS"]
    head_len = 16 + 16 * len(order)
    out = bytearray(MAGIC + struct.pack("<II", VERSION, len(order)))
    body = bytearray()
    table = []
    for tag in order:
        data, n = sec[tag]
        while (head_len + len(body)) % 8:
            body.append(0)
        table.append((tag, head_len + len(body), len(data), n))
        body += data
    for tag, off, ln, n in table:
        out += tag + struct.pack("<III", off, ln, n)
    out += body
    if len(out) >= 1 << 32:
        sys.exit("the file would pass 4 GiB")
    tmp = a.out + ".part"
    with open(tmp, "wb") as f:
        f.write(out)
    os.replace(tmp, a.out)
    for k, v in sorted(fixes.items()):
        if v:
            counted(k, v)
    print("feed rows read: stop_times %d" % nrows)
    print("kept: %d days from %s, %d stops, %d agencies, %d lines, %d patterns, %d routes, %d profiles, %d trips, %d walks, %d transfer rules"
          % (ndays, first, len(stop_list), len(used_ag), len(used_routes), len(pat_items), len(rout_rec), len(prof_by_new), len(trip_list), len(xf) // 2, len(rules)))
    for k, v in sorted(count.items()):
        print("  %s: %d" % (k, v))
    print("file: %d bytes" % len(out))
    peak = peak_memory()
    print("packed in %d s%s" % (time.time() - T0, "" if peak is None else ", with %d MB of memory at the most" % round(peak / 1e6)))
    if a.sizes:
        print("%-6s %10s %12s %12s %12s" % ("tag", "records", "bytes", "gzip -9", "xz -9"))
        for tag, off, ln, n in table:
            d = bytes(out[off:off + ln])
            print("%-6s %10d %12d %12d %12d" % (tag.decode(), n, ln, len(gzip.compress(d, 9, mtime=0)), len(lzma.compress(d, preset=9))))
        print("%-6s %10s %12d %12d %12d" % ("all", "", len(out), len(gzip.compress(bytes(out), 9, mtime=0)), len(lzma.compress(bytes(out), preset=9))))
    log("done")


if __name__ == "__main__":
    main()
