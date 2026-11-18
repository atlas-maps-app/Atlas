# The timetable file

One file holds one country's public transport timetable for the days its feed covers. `pack/ttpack.py` makes it from a GTFS feed; the planner (`planner/`) reads it in place and nothing else does. This is version 1. While Atlas is alpha there is one version: when the format changes, the number changes and a reader refuses any other.

## What it is for, and what follows from that

- **A search reads it as it lies.** On a phone the file is mapped into memory and never decoded: every number is a little-endian `u32` at a place that can be computed, so a search touches only the pages it needs and the system can drop them again. The layout is chosen for the search, not for size.
- **It travels compressed as a whole,** as gzip: Android reads that with no library added. The compression is the publishing step's (`.github/workflows/timetable.yml`), not the format's. The measured sizes are in the last section.
- **It holds the timetable's facts once,** and one index a round can't start without (the routes at each stop). Whatever else a reader needs, it derives.
- **Nothing a person could be shown is dropped quietly.** The packer prints every table and every column of a feed that it does not read, with how much each holds; packing strictly (`--strict`, as the weekly workflow does) it refuses such a feed and writes nothing. What is left out by decision is named under "Left out".
- **It is the same bytes for the same feed and options.** Every order is a sort with a full key, nothing in it is a build date, and walks are computed in integers.
- **A reader trusts none of it.** Any bytes at all must open or be refused without a crash, and no file can make a search run without bound. "What a reader checks" says how.

## Words

| Word | Means | In GTFS |
|---|---|---|
| stop | a place a vehicle calls at, or a station, an entrance, a node or a boarding area of one | `stops.txt`, every `location_type` |
| station | a stop that others name as their parent | `parent_station` |
| line | what a rider knows by a number or a name | a route |
| pattern | a line's stops in order in one direction, with what holds at each | derived |
| profile | the times along a pattern, counted from the first departure | derived |
| trip | one vehicle's run: a pattern, a profile, a first departure, the days it runs | a trip |
| route | a pattern's trips, sorted by first departure, of which none overtakes another | derived; RAPTOR's "route" |
| service | a set of days | `calendar.txt` and `calendar_dates.txt`, worked out |
| walk | a way on foot from one stop to another, in seconds | derived, and `transfers.txt` between stops |
| transfer rule | what the feed says about changing from one trip or line to another | `transfers.txt` |

## Time

- A time is in **seconds of a service day**, as GTFS counts them: from "noon minus twelve hours" in the file's timezone (`timezone` in `META`), and past 86,400 for a trip that runs on after midnight. On all but the two days a year the clock changes, that is seconds since midnight.
- The planner knows no timezone and no clock. Whoever asks it turns a wall-clock moment into a service day and its seconds, and a result back. On the phone that is one call of `java.time`.
- A **day** is counted from `first_day`: day 0 is that date, and the file covers `days` of them. A service is one bit a day.
- A trip at day `d` with first departure `t` and a profile entry `(a, b)` arrives at that stop at `d · 86400 + t + a` and leaves at `d · 86400 + t + b`, in seconds from the start of day 0.

## Layout

All integers are little-endian `u32` unless said. `NONE` is `0xFFFFFFFF`.

**Header**, 16 bytes: the eight bytes `ATLASTT\0`, the version (1), the number of sections.

**Section table**, 16 bytes a section: a tag of four ASCII letters, the section's offset from the start of the file, its length in bytes, its number of records. Sections start on a multiple of 8, lie anywhere after the table, and do not overlap. A tag appears once. A reader ignores a tag it does not know.

**Strings.** `STRS` is one blob. A string is its length in bytes (LEB128) followed by its UTF-8 bytes, and each distinct string is there once. A string reference is the byte offset of the length inside `STRS`; offset 0 is the empty string. Strings are for showing and matching only: no search step depends on one.

**Sections.** "×n" is the number of `u32` a record.

| Tag | A record | ×n | Fields |
|---|---|---|---|
| `META` | text | | lines of `key=value`, UTF-8 (below) |
| `STOP` | a stop | 12 | latitude and longitude in 1e-7 degree (as `i32`); parent station (a stop, or `NONE`); flags: bits 0–2 `location_type`, bits 3–4 `wheelchair_boarding`, bits 5–6 whose place it is (0 its own, 1 its station's because the feed gives it none, 2 none known); strings: `stop_id`, `stop_code`, name, platform code, `zone_id`, `stop_ifopt`, `stop_timezone`, description |
| `AGCY` | an operator | 6 | strings: `agency_id`, name, url, timezone, phone, language |
| `LINE` | a line | 10 | operator; strings: `route_id`, short name, long name, description; `route_type`; colour and text colour as `0xRRGGBB` or `NONE`; string: url; sort order or `NONE` |
| `PATN` | a pattern | 5 | line; string: headsign; direction (0, 1 or `NONE`); its first record in `PSTP`; its number of stops (2 to 65,535) |
| `PSTP` | a stop of a pattern | 1 | bits 0–26 the stop; bits 27–28 `pickup_type`; bits 29–30 `drop_off_type`; bit 31 set when the time here is approximate (`timepoint` 0) |
| `PHSG` | beside `PSTP` | 1 | string: the headsign shown from this stop on, or empty |
| `PFAR` | beside `PSTP` | 1 | `fare_units_traveled` up to this stop, or `NONE` |
| `PDST` | beside `PSTP` | 1 | `shape_dist_traveled` up to this stop, rounded, in the feed's unit (metres in OVapi's), or `NONE` |
| `PROF` | a profile | 2 | its first record in `PTIM`; its number of stops |
| `PTIM` | a stop of a profile | 2 | arrival and departure, in seconds after the trip's first departure. The first stop's are 0 and 0. They never go back: arrival ≤ departure ≤ the next arrival. Each is under 2^20 |
| `ROUT` | a route | 3 | pattern; its first record in `TRIP`; its number of trips. Routes follow each other in `TRIP` without gaps |
| `TRIP` | a trip | 4 | first departure (under 2^20); profile (as long as the route's pattern); service; flags: bits 0–1 `wheelchair_accessible`, bits 2–3 `bikes_allowed` |
| `TRIX` | beside `TRIP` | 5 | strings: `trip_id`, `realtime_trip_id`, `trip_short_name` (a train's number), `trip_long_name` (its kind: "Intercity"), `block_id` |
| `SERV` | a service | ⌈days/32⌉ | one bit a day, day 0 the lowest bit of the first word |
| `SVID` | beside `SERV` | 1 | string: `service_id` |
| `XFRI` | per stop, and one more | 1 | the stop's first record in `XFER`; the last entry is the number of walks |
| `XFER` | a walk | 2 | the stop it leads to; seconds |
| `SRTI` | per stop, and one more | 1 | the stop's first record in `SRTE` |
| `SRTE` | a route at a stop | 2 | the route; the stop's place along its pattern (from 0). A stop a pattern passes twice is there twice |
| `TRUL` | a transfer rule | 8 | from stop; to stop; from line or `NONE`; to line or `NONE`; from trip or `NONE`; to trip or `NONE`; `transfer_type`; `min_transfer_time` or `NONE`. Sorted by all eight |

**`META` keys:** `first_day` (YYYYMMDD), `days`, `timezone`, `change_same_stop_s` (what changing at one stop costs, 60), `walks` (the rule the walks were made by), `source`, `cut` (the packer's options, empty for a whole feed), the feed's own `feed_publisher_name`, `feed_publisher_url`, `feed_lang`, `feed_version`, `feed_start_date`, `feed_end_date`, `feed_id`, and one `licence:<table>` a row of the feed's `licenses.txt` (`licence:*` for the feed as a whole).

## What the packer does to a feed

- **Days.** `calendar.txt` is spread over its weekdays, then `calendar_dates.txt` adds and removes. A service that runs on no kept day goes, with its trips.
- **Stops.** Kept: every stop a kept trip calls at, its station (and that one's), and the station's other parts (entrances, nodes, boarding areas). They are sorted along a Z-curve, so stops near each other lie near each other in the file.
- **A stop at 0, 0** has no place in the feed (OVapi: 201 platforms of stations abroad). It is put at its station's place and marked so; with no station that has a place it stays at 0, 0, marked as nowhere, and takes part in nothing that measures.
- **A trip's stop times** are sorted by `stop_sequence`. A stop without a time takes one between its timed neighbours, by its place in the sequence. A time that goes back is raised to the one before. Both are counted and printed.
- **Patterns.** Two trips share a pattern when they agree in line, headsign, direction, and at every stop in the stop, pickup and drop-off kind, timepoint flag, stop headsign, fare units and distance.
- **Profiles** are shared by every trip with the same times after its first departure, whatever its pattern. The arrival at the first stop is not kept (it is written as the departure).
- **Routes.** A pattern's trips are sorted by first departure (then profile, then `trip_id`), and each goes into the first route in which it is nowhere earlier than that route's last trip, at any arrival or departure. So within a route a later trip is later or level everywhere, and a search finds the first trip it can catch by halving.
- **Walks** (until the foot router makes them; the planner won't change then):
  - at one stop, a change costs `change_same_stop_s` and is no record;
  - between two stops within 400 m: 60 s and the straight line taken 30 % longer at 1.25 m/s, rounded up;
  - between two stops of one station, however far: the same, and 120 s at least;
  - to or from a stop that is nowhere, inside its station: 120 s;
  - a rule of `transfers.txt` between two stops for everyone (no trip, no line) changes the walk: type 2 sets its seconds, type 3 removes it, type 0 or 1 adds one where none was.
- **Transfer rules** are all kept as records, whatever their type. OVapi's are all between two trips: "not possible" (3), "the second vehicle waits" (1), and "recommended" (0).
- **Distances** are on the plane at the two points' mean latitude, in integers: a 1e-7 degree of latitude is 11.1195 mm (a sphere of 6,371.0 km), and the cosine comes from a table of 901 values by the tenth of a degree, scaled by 65,536. Divisions round down. The planner has the same table (a test holds the two together).
- **A cut** (`--first`, `--days`, `--box`, `--agency`) keeps the days, the stops inside a box, or the operators named. A trip keeps its stops inside the box and needs two. The tests' files are cuts.

## What a reader checks

At open, in one pass over the sections a search steers by (not the strings, and not the sections beside others that only hold what is shown):

- the header, and that every section it needs is there once, lies inside the file and has the length its record count asks for;
- every number that is used as an index is inside what it points into: a stop's parent, a line's operator, a pattern's line and its run in `PSTP`, a pattern stop's stop, a profile's run in `PTIM`, a route's pattern and its run in `TRIP`, a trip's profile and service, a walk's stop, a route-at-stop's route and place, a rule's stops, lines and trips;
- the lists by stop (`XFRI`, `SRTI`) rise and end at their section's count; routes follow each other in `TRIP` without gaps; a trip's profile is as long as its route's pattern;
- the bounds a search's arithmetic rests on: `days` 1 to 400; a pattern of 2 to 65,535 stops; times, first departures and walks under 2^20 s.

Not checked at open, because it costs a pass over every stop time: that a route's trips are sorted and never overtake, that profile times never go back, and that `SRTE` agrees with the patterns. A file that breaks these gives a worse answer, never a crash or an endless search. The tool's `check` command verifies all three, and the packer's output is held to it in the tests.

A string reference that points outside `STRS`, or at bytes that are not UTF-8, reads as the empty string.

## Left out

| What | Why |
|---|---|
| `shapes.txt` | ODbL in OVapi's feed, and 214 MB as text. A ride is drawn on the map's own rails and roads. `shape_dist_traveled` in `stop_times.txt` is CC0 and kept |
| `ticketing_identifiers.txt`, `ticketing_deep_links.txt` | CC BY-NC-ND in OVapi's feed (and empty there) |
| the arrival time at a trip's first stop | nobody alights there |
| `frequencies.txt` | trips by headway. OVapi has none. The packer refuses a feed that has rows there rather than drop them |
| `shape_id` of `trips.txt` | it names a shape, and the shapes are left out |
| any other table (`fare_*`, `pathways.txt`, `levels.txt`, `translations.txt`, `attributions.txt`, …) or column | OVapi's feed has none. The packer prints each one it meets as NOT READ with its rows or values, and strictly refuses the feed; an empty one loses nothing and is only said |

Known to be missing for later feeds: a boarding and a leaving time a stop (an airport's check-in and baggage), and more than one timezone a file.

## The Dutch feed's licence

Read on 17 November 2026, in the feed (`licenses.txt`) and at `gtfs.ovapi.nl/README`.

- The feed as a whole: **CC0-1.0**. `shapes`: ODbL-1.0. The two ticketing tables: CC-BY-NC-ND-4.0. The packer takes neither of the latter, so a packed file is CC0 data.
- The README: free to use, best effort, no service level; nobody may say they represent or impersonate a transit agency. No attribution is asked; Atlas names OVapi in its credits all the same when a screen shows this data.
- Fetching: a User-Agent that says who is asking; `If-Modified-Since` or `If-None-Match` for anyone polling more than once a minute; gzip accepted. The feed changes at most daily. A weekly fetch is far inside that.

Each further country's feed needs its licence read the same way before a file of it is published.

## Sizes

OVapi's feed of 16 November 2026 (version 9696): 28 days from 15 November, 65,221 stops, 2,507 lines, 15,501 patterns, 17,099 routes, 38,263 profiles, 591,858 trips standing for 11,217,792 stop times, 263,264 walks, 66,716 transfer rules. The feed itself is 162.8 MB zipped. Packing it takes two minutes on the Mac this was measured on, which has no zstd.

| What | Sections | At rest | gzip -9 | xz -9 |
|---|---|---|---|---|
| Strings: names, ids, headsigns | `STRS` | 13.84 MB | 3.22 MB | 1.47 MB |
| The trips' strings, as references: `trip_id`, live id, number, kind | `TRIX` | 11.84 MB | 2.91 MB | 1.42 MB |
| Trips: first departure, profile, service, flags | `TRIP` | 9.47 MB | 1.80 MB | 0.69 MB |
| Times along the patterns | `PROF`, `PTIM` | 7.90 MB | 0.89 MB | 0.54 MB |
| Stops | `STOP` | 3.13 MB | 0.97 MB | 0.65 MB |
| Walks | `XFRI`, `XFER` | 2.37 MB | 0.75 MB | 0.42 MB |
| The routes at each stop | `SRTI`, `SRTE` | 2.46 MB | 0.74 MB | 0.26 MB |
| Transfer rules | `TRUL` | 2.13 MB | 0.26 MB | 0.13 MB |
| Fare units and distances along the patterns | `PFAR`, `PDST` | 1.87 MB | 0.57 MB | 0.42 MB |
| Patterns, their stops and stop headsigns, routes | `PATN`, `PSTP`, `PHSG`, `ROUT` | 2.38 MB | 0.36 MB | 0.22 MB |
| Lines, operators, services, `META` | | 0.12 MB | 0.03 MB | 0.02 MB |
| **The file** | | **57.5 MB** | **12.5 MB** | **6.2 MB** |

- That is 0.55 bytes a stop time as xz, and 1.1 as gzip. Beside the country's 1,372 MB of map tiles the file is 4.2 % at rest and under 1 % as it travels.
- Nearly half of what travels is ids: the strings and the trips' references to them. They are what ties a trip to live data and to "a specific train".
- The Architect's trial packed the same feed to 2.0 MB as xz. It kept less (no GTFS trip ids, no fare units, distances or stop headsigns, no walks, rules or route index), and wrote every column as differences in a variable-length code, which a search can't read in place.
- **Published as gzip:** 12.5 MB a week for the Netherlands (decided 17 November 2026: no new library on the phone).
- **Measured and not built:** a filter before the compressor that keeps the format (each `u32` column as differences, its four bytes apart) brings gzip to 7.9 MB and xz to 5.0 MB. It would be a second coding to undo on the phone.

## How it is published

`.github/workflows/timetable.yml` runs once a week and by hand. It fetches the feed, packs it strictly, checks the file (`atlas-transit check`, and the fixed journeys against the connection scan), gzips it, and uploads it to a dated release `timetable-YYYYMMDD`. The list `timetable.json` goes there too and, last, onto the fixed release `timetable-latest`: a reader starts from that list. Nothing in the app reads it yet.

`pack/ttlist.py` writes the list: `{"list": 1, "timetables": [entry, …]}`, entries by `id`. An entry:

| Key | Holds |
|---|---|
| `id`, `name` | `nl`, `Netherlands` |
| `release`, `url` | the dated release, and where the gzipped file is served |
| `format` | the file's format version (this document's: 1) |
| `feed` | `publisher`, `version`, `licence`, `url`; `last_modified` as the feed's server said it and `fetched` (when it was taken); the zip's `bytes` and `sha256` |
| `first_day`, `days`, `last_day`, `timezone` | the days the file covers, as `META` has them |
| `file` | `name`, `bytes` and `sha256` as it travels; `unpacked.bytes` and `unpacked.sha256` |
| `holds` | how many stops, operators, lines, patterns, trips, walks and transfer rules |
| `packer` | the `sha256` of `ttpack.py` and the commit it ran from |

An unchanged feed is not published again: the workflow asks the feed's server with `If-Modified-Since`, unless the packer or the format is no longer the published file's.
