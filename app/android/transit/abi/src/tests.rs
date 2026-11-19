//! The interface held to the planner behind it, on the host: every call here is made as C makes it, through the
//! `extern "C"` functions with pointers and lengths. (tests/host.c makes them from C itself, against the header and
//! the static library: ../check.sh.)
//!
//! - The header and the library name the same numbers.
//! - A timetable says through the interface what the planner's own reader says of the file.
//! - An answer crosses whole: thousands of queries at the edge of every field (the planner's fuzzing driver makes
//!   them), written as numbers the way an app would, give through the interface what the planner gives directly,
//!   journey for journey and leg for leg.
//! - A query that cannot be read is refused, and never planned without the part that was not understood.
//! - Null pointers, short buffers, files that are no timetable and files a little wrong do no harm.

use super::*;
use atlas_transit::fuzz::{self, Rng};
use atlas_transit::Journey;
use std::ptr::{null, null_mut};

fn bytes(name: &str) -> Vec<u8> {
    std::fs::read(format!("{}/../planner/tests/data/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

/// A timetable opened through the interface, closed when it goes.
struct Open(*mut AtlasTransit);

impl Open {
    fn of(b: &[u8]) -> Open {
        let mut why = -7;
        // SAFETY: the bytes outlive every Open made of them here, which the tests see to
        let t = unsafe { atlas_transit_open(b.as_ptr(), b.len(), 0, &mut why) };
        assert!(!t.is_null() && why == OPENED, "why {why}");
        Open(t)
    }
    fn number(&self, what: u32, id: u32, at: u32) -> i32 {
        // SAFETY: an open timetable
        unsafe { atlas_transit_number(self.0, what, id, at) }
    }
    fn text(&self, what: u32, id: u32, at: u32) -> &str {
        let mut len = usize::MAX;
        // SAFETY: an open timetable, and a number to write the length to
        let p = unsafe { atlas_transit_text(self.0, what, id, at, &mut len) };
        assert!(!p.is_null() && len != usize::MAX);
        // SAFETY: `len` bytes of the file, which stay while the timetable is open
        std::str::from_utf8(unsafe { std::slice::from_raw_parts(p, len) }).unwrap()
    }
    fn meta(&self, key: &str) -> &str {
        let mut len = usize::MAX;
        // SAFETY: an open timetable, the key's bytes, and a number to write the length to
        let p = unsafe { atlas_transit_meta(self.0, key.as_ptr(), key.len(), &mut len) };
        assert!(!p.is_null() && len != usize::MAX);
        // SAFETY: `len` bytes of the file
        std::str::from_utf8(unsafe { std::slice::from_raw_parts(p, len) }).unwrap()
    }
    /// A plan as an app makes one: the numbers in, the answer's length out, then the answer copied to a buffer of
    /// that length.
    fn plan(&self, sent: &Sent) -> Vec<i32> {
        // SAFETY: an open timetable, used by this thread alone; four arrays with their lengths
        let n = unsafe { atlas_transit_plan(self.0, sent.query.as_ptr(), sent.query.len(), sent.from.as_ptr(), sent.from.len(), sent.to.as_ptr(), sent.to.len(), sent.wishes.as_ptr(), sent.wishes.len()) };
        let mut out = vec![i32::MIN; n];
        // SAFETY: room for `n` numbers
        assert_eq!(unsafe { atlas_transit_answer(self.0, out.as_mut_ptr(), n) }, n);
        out
    }
}

impl Drop for Open {
    fn drop(&mut self) {
        // SAFETY: opened above, closed once
        unsafe { atlas_transit_close(self.0) }
    }
}

/// A query as the numbers an app sends.
#[derive(Clone, Debug, Default)]
struct Sent {
    query: Vec<i32>,
    from: Vec<i32>,
    to: Vec<i32>,
    wishes: Vec<i32>,
}

/// What 32 bits with a sign hold of an amount: the planner clamps every one of them far below.
fn amount(v: u32) -> i32 {
    v.min(i32::MAX as u32) as i32
}

/// Writes a query as an app would. Nothing here is taken from src/lib.rs but the names of the numbers.
fn send(q: &Query) -> Sent {
    let mut s = Sent { query: vec![0; Q_LEN], ..Sent::default() };
    let place = |p: &Place, walks: &mut Vec<i32>| match p {
        Place::Stop(s) => [P_STOP, *s as i32, 0],
        Place::Station(s) => [P_STATION, *s as i32, 0],
        Place::Point { lat, lon } => [P_POINT, *lat, *lon],
        Place::Walks(v) => {
            walks.extend(v.iter().flat_map(|w| [w.0 as i32, amount(w.1)]));
            [P_WALKS, 0, 0]
        }
    };
    let from = place(&q.from, &mut s.from);
    let to = place(&q.to, &mut s.to);
    s.query[Q_FROM..Q_FROM + 3].copy_from_slice(&from);
    s.query[Q_TO..Q_TO + 3].copy_from_slice(&to);
    s.query[Q_DAY] = q.day;
    s.query[Q_TIME] = amount(q.time);
    s.query[Q_WINDOW] = amount(q.window);
    s.query[Q_ARRIVE_BY] = q.arrive_by as i32;
    s.query[Q_MAX_RIDES] = amount(q.max_rides);
    s.query[Q_REACH_M] = amount(q.reach_m);
    s.query[Q_WALK_MM_S] = amount(q.walk_mm_s);
    s.query[Q_CHANGE_S] = q.change_s.map_or(-1, amount);
    s.query[Q_RULES] = q.rules as i32;
    s.query[Q_WEIGH] = q.weigh as i32;
    s.query[Q_OPTIONS] = amount(q.limits.options);
    s.query[Q_DEPARTURES] = amount(q.limits.departures);
    s.query[Q_HORIZON] = amount(q.limits.horizon);
    s.query[Q_WHOLE] = !q.limits.patient as i32;
    s.query[Q_BAG] = amount(q.limits.bag);
    s.query[Q_LABELS] = amount(q.limits.labels);
    assert_eq!(q.limits.steps % 1000, 0);
    s.query[Q_STEPS_K] = (q.limits.steps / 1000) as i32;
    for w in q.wishes.iter() {
        let (how, seconds) = match w.how {
            How::Never => (H_NEVER, 0),
            How::Avoid(s) => (H_AVOID, amount(s)),
            How::Prefer(s) => (H_PREFER, amount(s)),
        };
        let (what, more): (i32, Vec<i32>) = match &w.what {
            What::Stop { stop, through } => (W_STOP, vec![*stop as i32, *through as i32]),
            What::Station { station, through } => (W_STATION, vec![*station as i32, *through as i32]),
            What::Line(l) => (W_LINE, vec![*l as i32]),
            What::Operator(o) => (W_OPERATOR, vec![*o as i32]),
            What::Mode(m) => (W_MODE, vec![route_type(*m)]),
            What::Trip(t) => (W_TRIP, vec![*t as i32]),
            What::NoWheelchair => (W_NO_WHEELCHAIR, vec![]),
            What::WheelchairUnknown => (W_WHEELCHAIR_UNKNOWN, vec![]),
            What::NoBicycles => (W_NO_BICYCLES, vec![]),
            What::BicyclesUnknown => (W_BICYCLES_UNKNOWN, vec![]),
            What::Area { area: Area::Circle { lat, lon, radius_m }, through } => (W_CIRCLE, vec![*through as i32, *lat, *lon, amount(*radius_m)]),
            What::Area { area: Area::Box { south, west, north, east }, through } => (W_BOX, vec![*through as i32, *south, *west, *north, *east]),
            What::Area { area: Area::Outline(corners), through } => (W_OUTLINE, [*through as i32].into_iter().chain(corners.iter().flat_map(|c| [c.0, c.1])).collect()),
            What::Kind(name) => (W_KIND, name.bytes().map(|b| b as i32).collect()),
        };
        s.wishes.extend([what, how, seconds, more.len() as i32]);
        s.wishes.extend(more);
    }
    s
}

/// A GTFS route_type of each kind of vehicle.
fn route_type(m: Mode) -> i32 {
    let t = match m {
        Mode::Tram => 0,
        Mode::Metro => 1,
        Mode::Rail => 2,
        Mode::Bus => 3,
        Mode::Ferry => 4,
        Mode::CableTram => 5,
        Mode::Aerial => 6,
        Mode::Funicular => 7,
        Mode::Trolleybus => 11,
        Mode::Monorail => 12,
        Mode::Coach => 200,
        Mode::Air => 1100,
        Mode::Taxi => 1500,
        Mode::Other => 9999,
    };
    assert_eq!(Mode::of(t as u32), m);
    t
}

/// Reads an answer's numbers as an app would: the journeys, and the head's other numbers.
fn received(v: &[i32]) -> (Vec<Journey>, [i32; A_HEAD]) {
    let head: [i32; A_HEAD] = v[..A_HEAD].try_into().unwrap();
    let mut at = A_HEAD;
    let mut journeys = Vec::new();
    for _ in 0..head[A_JOURNEYS] {
        let j = &v[at..at + J_HEAD];
        at += J_HEAD;
        let mut legs = Vec::new();
        for _ in 0..j[J_LEGS] {
            let l = &v[at..at + L_LEN];
            at += L_LEN;
            let spot = |s: i32| match s {
                ORIGIN => Spot::Origin,
                DESTINATION => Spot::Destination,
                s => Spot::Stop(s as u32),
            };
            legs.push(match l[L_KIND] {
                L_WALK => {
                    assert_eq!([l[L_TRIP], l[L_ROUTE], l[L_DAY], l[L_BOARD], l[L_ALIGHT]], [NONE, NONE, 0, NONE, NONE]);
                    Leg::Walk { from: spot(l[L_FROM]), to: spot(l[L_TO]), depart: l[L_DEPART], arrive: l[L_ARRIVE] }
                }
                L_RIDE => Leg::Ride { trip: l[L_TRIP] as u32, route: l[L_ROUTE] as u32, day: l[L_DAY], board: l[L_BOARD] as u32, alight: l[L_ALIGHT] as u32, from: l[L_FROM] as u32, to: l[L_TO] as u32, depart: l[L_DEPART], arrive: l[L_ARRIVE] },
                k => panic!("a leg of kind {k}"),
            });
        }
        journeys.push(Journey { depart: j[J_DEPART], arrive: j[J_ARRIVE], rides: j[J_RIDES] as u32, penalty: j[J_PENALTY] as u32, walking: j[J_WALKING] as u32, legs });
    }
    assert_eq!(at, v.len(), "an answer is its head and its journeys, and no number more");
    (journeys, head)
}

fn hm(h: u32, m: u32) -> u32 {
    h * 3600 + m * 60
}

/// Every `#define ATLAS_TRANSIT_<name> <number>` of the header is a number of the library by that name, with that
/// value, and the library has none the header lacks.
#[test]
fn the_header_names_the_same_numbers() {
    let header = include_str!("../include/atlas_transit.h");
    let mut seen = Vec::new();
    for line in header.lines() {
        let Some(rest) = line.strip_prefix("#define ATLAS_TRANSIT_") else { continue };
        let mut words = rest.split_whitespace();
        let (Some(name), Some(first)) = (words.next(), words.next()) else { continue };
        if name == "H" {
            continue; // the include guard
        }
        // a number, in brackets where it is negative; the least of all as C must write it
        let value: i64 = if name == "NO_DAY" {
            assert!(rest.contains("(-2147483647 - 1)"), "{line}");
            i32::MIN as i64
        } else {
            first.trim_matches(|c| c == '(' || c == ')').parse().unwrap_or_else(|_| panic!("{line}"))
        };
        let Some(own) = NAMES.iter().find(|n| n.0 == name) else { panic!("the header has ATLAS_TRANSIT_{name}, the library has no such number") };
        assert_eq!(own.1, value, "ATLAS_TRANSIT_{name}");
        assert!(!seen.contains(&name), "ATLAS_TRANSIT_{name} twice");
        seen.push(name);
    }
    for (name, _) in NAMES {
        assert!(seen.contains(name), "the library has {name}, the header has no ATLAS_TRANSIT_{name}");
    }
    assert_eq!(atlas_transit_abi(), ABI);
    // and each function the library gives is declared there
    for function in ["abi", "open", "close", "number", "text", "meta", "plan", "answer"] {
        assert!(header.contains(&format!("atlas_transit_{function}(")), "{function}");
    }
}

/// Through the interface a timetable says of every stop, line, operator, route and trip what the planner's own
/// reader says, and nothing of what it has not.
#[test]
fn a_timetable_says_what_is_in_it() {
    for name in ["edges.tt", "tiny.tt", "autumn.tt", "utrecht-20261117.tt"] {
        let b = bytes(name);
        let tt = Timetable::open(&b).unwrap();
        let t = Open::of(&b);
        let n = |v: u32| if v == atlas_transit::NONE { NONE } else { v as i32 };
        let counts = [N_FIRST_DAY, N_DAYS, N_STOPS, N_LINES, N_OPERATORS, N_ROUTES, N_TRIPS, N_CHANGE_SECONDS].map(|w| t.number(w, 0, 0));
        assert_eq!(counts, [tt.first_day(), tt.days(), tt.stops(), tt.lines(), tt.operators(), tt.routes(), tt.trips(), tt.change_seconds()].map(n), "{name}");
        // a date is its day and back; no date is no day
        for day in [-400, -1, 0, 1, tt.days() as i32, 500] {
            let date = t.number(N_DATE_OF, day as u32, 0);
            assert_eq!(date as u32, tt.date_of(day));
            assert_eq!(t.number(N_DAY_OF, date as u32, 0), day);
        }
        assert_eq!(t.number(N_DAY_OF, tt.first_day(), 0), 0);
        assert_eq!([20270230, 0, 99999999, u32::MAX].map(|d| t.number(N_DAY_OF, d, 0)), [NO_DAY; 4]);
        for s in 0..tt.stops() {
            let stop = tt.stop(s);
            let got = [N_STOP_LAT, N_STOP_LON, N_STOP_STATION, N_STOP_KIND, N_STOP_WHEELCHAIR, N_STOP_PLACE].map(|w| t.number(w, s, 0));
            assert_eq!(got, [stop.lat, stop.lon, n(stop.parent), stop.kind as i32, stop.wheelchair as i32, stop.place as i32]);
            for (what, which) in [(T_STOP_ID, StopText::Id), (T_STOP_CODE, StopText::Code), (T_STOP_NAME, StopText::Name), (T_STOP_PLATFORM, StopText::Platform), (T_STOP_ZONE, StopText::Zone), (T_STOP_TIMEZONE, StopText::Timezone), (T_STOP_DESCRIPTION, StopText::Description), (T_STOP_IFOPT, StopText::Ifopt)] {
                assert_eq!(t.text(what, s, 0), tt.stop_text(s, which));
            }
        }
        for l in 0..tt.lines() {
            let colours = tt.line_colours(l);
            let got = [N_LINE_OPERATOR, N_LINE_TYPE, N_LINE_COLOUR, N_LINE_TEXT_COLOUR].map(|w| t.number(w, l, 0));
            assert_eq!(got, [n(tt.line_operator(l)), n(tt.line_type(l)), colours.0.map_or(NONE, |c| c as i32), colours.1.map_or(NONE, |c| c as i32)]);
            for (what, which) in [(T_LINE_ID, LineText::Id), (T_LINE_SHORT, LineText::Short), (T_LINE_LONG, LineText::Long), (T_LINE_DESCRIPTION, LineText::Description), (T_LINE_URL, LineText::Url)] {
                assert_eq!(t.text(what, l, 0), tt.line_text(l, which));
            }
        }
        for o in 0..tt.operators() {
            for (what, which) in [(T_OPERATOR_ID, OperatorText::Id), (T_OPERATOR_NAME, OperatorText::Name), (T_OPERATOR_URL, OperatorText::Url), (T_OPERATOR_TIMEZONE, OperatorText::Timezone), (T_OPERATOR_PHONE, OperatorText::Phone), (T_OPERATOR_LANGUAGE, OperatorText::Language)] {
                assert_eq!(t.text(what, o, 0), tt.operator_text(o, which));
            }
        }
        let mut headsigns = 0;
        for r in 0..tt.routes() {
            let route = tt.route(r);
            assert_eq!([N_ROUTE_LINE, N_ROUTE_STOPS, N_ROUTE_DIRECTION].map(|w| t.number(w, r, 0)), [n(route.line), n(route.stops), n(tt.pattern_direction(route.pattern))]);
            for at in 0..route.stops {
                assert_eq!(t.number(N_ROUTE_STOP, r, at), tt.pattern_stop(route.first_stop + at).stop as i32);
                let from_there = tt.pattern_stop_headsign(route.first_stop + at);
                assert_eq!(t.text(T_ROUTE_HEADSIGN, r, at), if from_there.is_empty() { tt.pattern_headsign(route.pattern) } else { from_there });
                headsigns += !t.text(T_ROUTE_HEADSIGN, r, at).is_empty() as u32;
            }
            // past its last stop a route has no stop, and shows what it shows as a whole
            assert_eq!(t.number(N_ROUTE_STOP, r, route.stops), NONE);
            assert_eq!(t.text(T_ROUTE_HEADSIGN, r, route.stops), tt.pattern_headsign(route.pattern));
            for trip in route.first_trip..route.first_trip + route.trips {
                let own = tt.trip(trip);
                assert_eq!([N_TRIP_ROUTE, N_TRIP_WHEELCHAIR, N_TRIP_BICYCLES].map(|w| t.number(w, trip, 0)), [r as i32, own.wheelchair as i32, own.bicycles as i32]);
                for at in [0, route.stops / 2, route.stops - 1] {
                    let times = tt.time(tt.trip_times(trip), at);
                    assert_eq!((t.number(N_TRIP_ARRIVES, trip, at), t.number(N_TRIP_LEAVES, trip, at)), (times.0 as i32, times.1 as i32));
                }
                assert_eq!(t.number(N_TRIP_LEAVES, trip, route.stops), NONE);
                for (what, which) in [(T_TRIP_ID, TripText::Id), (T_TRIP_REALTIME, TripText::Realtime), (T_TRIP_NUMBER, TripText::Number), (T_TRIP_KIND, TripText::Kind), (T_TRIP_BLOCK, TripText::Block)] {
                    assert_eq!(t.text(what, trip, 0), tt.trip_text(trip, which));
                }
            }
        }
        assert!(headsigns > 0, "{name}");
        // the file's lines about itself
        for (key, value) in tt.meta_lines() {
            assert_eq!(t.meta(key), tt.meta(key).unwrap(), "{key}");
            assert!(!value.is_empty() || t.meta(key).is_empty());
        }
        assert_eq!(t.meta("first_day").parse::<u32>().unwrap(), tt.first_day());
        assert_eq!((t.meta("no such line"), t.meta("")), ("", ""));
        // one past the last of each names nothing, and so does a number that asks for nothing known
        for (count, numbers, texts) in [
            (tt.stops(), N_STOP_LAT..=N_STOP_PLACE, T_STOP_ID..=T_STOP_IFOPT),
            (tt.lines(), N_LINE_OPERATOR..=N_LINE_TEXT_COLOUR, T_LINE_ID..=T_LINE_URL),
            (tt.operators(), 1..=0, T_OPERATOR_ID..=T_OPERATOR_LANGUAGE),
            (tt.routes(), N_ROUTE_LINE..=N_ROUTE_DIRECTION, T_ROUTE_HEADSIGN..=T_ROUTE_HEADSIGN),
            (tt.trips(), N_TRIP_ROUTE..=N_TRIP_LEAVES, T_TRIP_ID..=T_TRIP_BLOCK),
        ] {
            for id in [count, count + 1, i32::MAX as u32, u32::MAX] {
                for what in numbers.clone() {
                    assert_eq!(t.number(what, id, 0), NONE, "{name}: number {what} of {id}");
                }
                for what in texts.clone() {
                    assert_eq!(t.text(what, id, 0), "", "{name}: text {what} of {id}");
                }
            }
        }
        for what in [10, 15, 22, 31, 36, 47, 52, 63, 69, 1000, u32::MAX] {
            assert_eq!(t.number(what, 0, 0), NONE, "number {what}");
        }
        for what in [8, 15, 21, 31, 38, 47, 53, 63, 65, 1000, u32::MAX] {
            assert_eq!(t.text(what, 0, 0), "", "text {what}");
        }
    }
}

/// The hand-written feed's answers, asked as an app asks and read as an app reads (../planner/tests/edges.rs has
/// the islands): Wacht's one way, 08:00 to 08:30 with the bus that waits; Lijn with the bus that waits avoided,
/// weighed and not; arriving by; a window; a list of walks; and the names of what is ridden.
#[test]
fn the_paper_answers_come_through() {
    let b = bytes("edges.tt");
    let tt = Timetable::open(&b).unwrap();
    let t = Open::of(&b);
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap() as i32;
    let trip = |id: &str| (0..tt.trips()).find(|x| tt.trip_text(*x, TripText::Id) == id).unwrap() as i32;
    // a journey as its times, its cost, and the ids of its trips, each read through the interface
    let said = |v: &[i32]| -> Vec<(u32, u32, i32, String)> {
        let (journeys, _) = received(v);
        journeys.iter().map(|j| (j.depart as u32, j.arrive as u32, j.penalty as i32, j.legs.iter().filter_map(|l| if let Leg::Ride { trip, .. } = l { Some(t.text(T_TRIP_ID, *trip, 0)) } else { None }).collect::<Vec<_>>().join(" "))).collect()
    };
    // the eight numbers a query needs, and nothing else: everything more is the planner's own
    let least = |from: &str, to: &str, time: u32| Sent { query: vec![P_STOP, stop(from), 0, P_STOP, stop(to), 0, 0, time as i32], ..Sent::default() };
    let waits = (hm(8, 0), hm(8, 30), 0, "W1a W2a".to_string());
    let v = t.plan(&least("WA", "WZ", hm(7, 55)));
    assert_eq!(said(&v), [waits.clone()]);
    assert_eq!((v[A_JOURNEYS], v[A_CUT_SHORT]), (1, 0));
    // its legs: a ride to B, the walk that is none (the bus waits at another stop), a ride from C
    let (journeys, head) = received(&v);
    assert!(head[A_SEARCHES] >= 1 && head[A_STEPS_K] >= 1 && head[A_HORIZON] > 0 && head[A_HEAD - 1] == 0, "{head:?}");
    let kinds: Vec<(i32, i32)> = journeys[0].legs.iter().map(|l| match l { Leg::Walk { .. } => (L_WALK, -1), Leg::Ride { from, .. } => (L_RIDE, *from as i32) }).collect();
    assert_eq!(kinds, [(L_RIDE, stop("WA")), (L_WALK, -1), (L_RIDE, stop("WC"))]);
    assert_eq!((journeys[0].rides, journeys[0].penalty), (2, 0));
    // the same with every number written out as the planner's own, negative: the same answer
    let mut whole = least("WA", "WZ", hm(7, 55));
    whole.query.resize(Q_LEN, -1);
    assert_eq!(said(&t.plan(&whole)), [waits.clone()]);
    // arriving by 08:30, and by a minute sooner
    let mut by = least("WA", "WZ", hm(8, 30));
    by.query.resize(Q_ARRIVE_BY + 1, -1);
    by.query[Q_ARRIVE_BY] = 1;
    assert_eq!(said(&t.plan(&by)), [waits.clone()]);
    by.query[Q_TIME] -= 60;
    assert_eq!(said(&t.plan(&by)), []);
    // Laat over a window of two hours: the two direct buses and the last way that evening
    let mut window = least("LA", "LZ", hm(22, 0));
    window.query.push(7200);
    assert_eq!(said(&t.plan(&window)), [(hm(22, 10), hm(22, 30), 0, "L1a".to_string()), (hm(22, 40), hm(23, 0), 0, "L1b".to_string()), (hm(23, 40), hm(25, 25), 0, "L2a L3a".to_string())]);
    // Lijn with the 08:11 that waits avoided by 40 minutes: weighed, both; by 20 minutes, the first alone; and
    // closed, the next
    let (first, next) = (|cost: i32| (hm(8, 0), hm(8, 25), cost, "N1a N2a".to_string()), (hm(8, 20), hm(8, 55), 0, "N1b N2b".to_string()));
    let mut wished = least("NA", "NZ", hm(7, 55));
    wished.wishes = vec![W_TRIP, H_AVOID, 2400, 1, trip("N2a")];
    assert_eq!(said(&t.plan(&wished)), [first(2400), next.clone()]);
    wished.wishes[2] = 1200;
    assert_eq!(said(&t.plan(&wished)), [first(1200)]);
    wished.query.resize(Q_WEIGH + 1, -1);
    wished.query[Q_WEIGH] = 0;
    assert_eq!(said(&t.plan(&wished)), [first(1200), next.clone()]);
    wished.wishes = vec![W_TRIP, H_NEVER, 0, 1, trip("N2a")];
    assert_eq!(said(&t.plan(&wished)), [next.clone()]);
    // and one option of the two, weighed: the one that is in first with its cost added, and the answer says it is
    // cut short
    wished.wishes = vec![W_TRIP, H_AVOID, 2400, 1, trip("N2a")];
    wished.query[Q_WEIGH] = 1;
    wished.query.resize(Q_OPTIONS + 1, -1);
    wished.query[Q_OPTIONS] = 1;
    let v = t.plan(&wished);
    assert_eq!((said(&v), v[A_CUT_SHORT]), (vec![next.clone()], CUT_OPTIONS));
    // from whoever knows the streets: ten minutes on foot to Wacht's A, five from its Z
    let walks = Sent { query: vec![P_WALKS, 0, 0, P_WALKS, 0, 0, 0, hm(7, 45) as i32], from: vec![stop("WA"), 600], to: vec![stop("WZ"), 300], ..Sent::default() };
    let v = t.plan(&walks);
    assert_eq!(said(&v), [(hm(7, 50), hm(8, 35), 0, "W1a W2a".to_string())]);
    let (journeys, _) = received(&v);
    // (on foot: those ten and five minutes, and the two between the stop the first bus is in at and the stop the
    // second waits at)
    assert_eq!(journeys[0].walking, 600 + 120 + 300);
    assert!(matches!(journeys[0].legs.first(), Some(Leg::Walk { from: Spot::Origin, .. })) && matches!(journeys[0].legs.last(), Some(Leg::Walk { to: Spot::Destination, .. })));
    // between two points near enough, the walk all the way is an option (Lopen: 26 minutes)
    let (a, z) = (tt.stop(stop("PA") as u32), tt.stop(stop("PZ") as u32));
    let points = Sent { query: vec![P_POINT, a.lat, a.lon, P_POINT, z.lat, z.lon, 0, hm(8, 0) as i32], ..Sent::default() };
    let (journeys, _) = received(&t.plan(&points));
    assert!(journeys.iter().any(|j| j.rides == 0 && j.arrive - j.depart == 26 * 60 && j.legs.len() == 1), "{journeys:?}");
    // what is shown of a ride: the line's name, the headsign, the stop's name
    let (journeys, _) = received(&t.plan(&least("WA", "WZ", hm(7, 55))));
    let Leg::Ride { trip, route, board, from, .. } = journeys[0].legs[0] else { panic!() };
    let line = t.number(N_ROUTE_LINE, route, 0) as u32;
    assert_eq!(t.number(N_TRIP_ROUTE, trip, 0), route as i32);
    assert_eq!(t.text(T_LINE_ID, line, 0), tt.line_text(tt.route(route).line, LineText::Id));
    assert_eq!(t.text(T_STOP_NAME, from, 0), "Wacht A");
    assert_eq!(t.number(N_TRIP_LEAVES, trip, board), hm(8, 0) as i32);
    assert_eq!(t.number(N_ROUTE_STOP, route, board), from as i32);
}

/// An answer crosses whole. Queries at the edge of every field, written as numbers and planned through the
/// interface, give what the planner gives when it is asked directly: every journey, every leg, whether it was cut
/// short, and what the search did. Both planners are asked the same questions in the same order.
#[test]
fn an_answer_crosses_whole() {
    let mut seen = [0usize; 6];
    let mut caps = [0usize; 8];
    for (name, queries) in [("edges.tt", 60_000), ("tiny.tt", 30_000), ("autumn.tt", 30_000), ("utrecht-20261117.tt", 1500)] {
        let b = bytes(name);
        let tt = Timetable::open(&b).unwrap();
        let mut planner = Planner::new(tt).unwrap();
        let t = Open::of(&b);
        let mut rng = Rng(0x5EED_0000_0000_0001 ^ queries as u64);
        for i in 0..queries {
            let mut q = fuzz::query(&tt, &mut rng);
            if i % 2 == 1 {
                // every other one between two stops on the file's first day, in the daytime, so that many have
                // journeys (the hand-written feeds are islands, each with a letter its stops' ids begin with:
                // two stops of one island); its wishes, its limits and the rest stay as wild as they were
                let from = rng.below(tt.stops() as u64) as u32;
                let letter = |s: u32| tt.stop_text(s, StopText::Id).chars().next();
                let near: Vec<u32> = if name.starts_with("utrecht") { (0..tt.stops()).collect() } else { (0..tt.stops()).filter(|s| letter(*s) == letter(from)).collect() };
                let to = near[rng.below(near.len() as u64) as usize];
                (q.from, q.to, q.day, q.time) = (Place::Stop(from), Place::Stop(to), 0, 6 * 3600 + rng.below(16 * 3600) as u32);
                (q.max_rides, q.window) = (8, [0, 0, 1800][rng.below(3) as usize]);
            }
            let sent = send(&q);
            let own = planner.plan(&q);
            let v = t.plan(&sent);
            assert!(v.len() >= A_HEAD, "{name}, query {i}: refused: {q:?}");
            let (journeys, head) = received(&v);
            assert_eq!(journeys, own.journeys, "{name}, query {i}: {q:?}");
            let steps_k = own.steps.div_ceil(1000) as i32;
            assert_eq!(head, [own.journeys.len() as i32, own.cut_by as i32, own.horizon as i32, own.searches as i32, own.labels as i32, steps_k, own.later as i32, 0], "{name}, query {i}: {q:?}");
            // (what the queries were: enough of each kind for the check to mean something)
            seen[0] += !own.journeys.is_empty() as usize;
            seen[1] += own.cut_short as usize;
            assert_eq!(own.cut_short, v[A_CUT_SHORT] != 0);
            for (cap, n) in Answer::CAPS.iter().zip(caps.iter_mut()) {
                *n += (v[A_CUT_SHORT] & cap.0 as i32 != 0) as usize;
            }
            seen[2] += !q.wishes.is_empty() as usize;
            seen[3] += matches!(q.from, Place::Walks(_)) as usize + matches!(q.to, Place::Walks(_)) as usize;
            seen[4] += own.journeys.iter().map(|j| j.legs.len()).sum::<usize>();
            seen[5] += q.wishes.iter().filter(|w| matches!(w.what, What::Area { .. } | What::Kind(_))).count();
        }
    }
    println!("{seen:?}: answers with journeys, cut short, with wishes, places that are walks, legs, wishes for an area or a kind");
    assert!(seen[0] > 3000 && seen[1] > 300 && seen[2] > 50_000 && seen[3] > 10_000 && seen[4] > 6000 && seen[5] > 30_000, "{seen:?}");
    // which cap an answer met crosses too, a bit each: the caps these queries meet, by how many answers
    println!("{:?}", Answer::CAPS.iter().zip(caps.iter()).map(|(cap, n)| (cap.1, *n)).collect::<Vec<_>>());
    assert!(caps.iter().filter(|n| **n > 0).count() >= 4, "{caps:?}");
}

/// A query that cannot be read is refused whole: 0, and no answer. Each case here is a query that is planned as it
/// should be, and then the same with one thing wrong.
#[test]
fn a_query_that_cannot_be_read_is_refused() {
    let b = bytes("edges.tt");
    let tt = Timetable::open(&b).unwrap();
    let t = Open::of(&b);
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap() as i32;
    let good = Sent { query: vec![P_STOP, stop("WA"), 0, P_STOP, stop("WZ"), 0, 0, hm(7, 55) as i32], ..Sent::default() };
    let planned = |s: &Sent| {
        let v = t.plan(s);
        assert!(v.is_empty() || v.len() >= A_HEAD);
        // (nothing of an answer before is left where a query is refused)
        // SAFETY: an open timetable; no buffer, so nothing is written
        assert_eq!(unsafe { atlas_transit_answer(t.0, null_mut(), 0) }, v.len());
        !v.is_empty()
    };
    assert!(planned(&good));
    let with = |change: &dyn Fn(&mut Sent)| {
        let mut s = good.clone();
        change(&mut s);
        planned(&s)
    };
    // the query's own numbers: too few; a place of no known kind; a time below zero
    assert!(!with(&|s| s.query.truncate(Q_NEEDED - 1)));
    assert!(!with(&|s| s.query.clear()));
    assert!(!with(&|s| s.query[Q_FROM] = 4));
    assert!(!with(&|s| s.query[Q_TO] = -1));
    assert!(!with(&|s| s.query[Q_TIME] = -1));
    // (a stop that is none is a query like any other, with no journey)
    assert!(with(&|s| s.query[Q_FROM + 1] = NONE));
    assert!(with(&|s| s.query[Q_FROM + 1] = i32::MAX));
    // more numbers than a query has are not read
    assert!(with(&|s| s.query.resize(Q_LEN + 5, 77)));
    // walks: pairs, and no time below zero; they are not read where the place is not of that kind
    assert!(with(&|s| (s.query[Q_FROM], s.from) = (P_WALKS, vec![stop("WA"), 60])));
    assert!(!with(&|s| (s.query[Q_FROM], s.from) = (P_WALKS, vec![stop("WA"), 60, stop("WB")])));
    assert!(!with(&|s| (s.query[Q_FROM], s.from) = (P_WALKS, vec![stop("WA"), -60])));
    assert!(!with(&|s| (s.query[Q_TO], s.to) = (P_WALKS, vec![stop("WZ")])));
    assert!(with(&|s| s.from = vec![1, 2, 3]));
    // wishes, each as it should be
    let wishes: Vec<Vec<i32>> = vec![
        vec![W_STOP, H_NEVER, 0, 2, stop("VB"), 0],
        vec![W_STATION, H_AVOID, 60, 2, stop("VB"), 1],
        vec![W_LINE, H_PREFER, 60, 1, 0],
        vec![W_OPERATOR, H_AVOID, 0, 1, 0],
        vec![W_MODE, H_AVOID, 300, 1, 3],
        vec![W_TRIP, H_NEVER, 0, 1, 0],
        vec![W_NO_WHEELCHAIR, H_NEVER, 0, 0],
        vec![W_WHEELCHAIR_UNKNOWN, H_AVOID, 10, 0],
        vec![W_NO_BICYCLES, H_NEVER, 0, 0],
        vec![W_BICYCLES_UNKNOWN, H_AVOID, 10, 0],
        vec![W_CIRCLE, H_AVOID, 10, 4, 0, 520_000_000, 50_000_000, 500],
        vec![W_BOX, H_AVOID, 10, 5, 1, 1, 2, 3, 4],
        vec![W_OUTLINE, H_AVOID, 10, 7, 0, 1, 2, 3, 4, 5, 6],
        vec![W_OUTLINE, H_AVOID, 10, 1, 0],
        vec![W_KIND, H_PREFER, 120, 9, 73, 110, 116, 101, 114, 99, 105, 116, 121],
        vec![W_KIND, H_PREFER, 120, 0],
    ];
    for w in wishes.iter() {
        assert!(with(&|s| s.wishes = w.clone()), "{w:?}");
        // cut off anywhere, or one number more: refused
        for cut in 1..w.len() {
            assert!(!with(&|s| s.wishes = w[..cut].to_vec()), "{w:?} cut at {cut}");
        }
        assert!(!with(&|s| s.wishes = [w.as_slice(), &[0]].concat()), "{w:?} and a number");
        // more numbers of its own than its kind has, or fewer (a kind's name and an outline are as long as they
        // say, and an outline has pairs)
        for n in [w[3] + 1, w[3] - 1] {
            let mut other = w.clone();
            other[3] = n;
            other.resize(W_HEAD + n.max(0) as usize, 0);
            let fine = w[0] == W_KIND && n >= 0;
            assert_eq!(with(&|s| s.wishes = other.clone()), fine, "{w:?} with {n} numbers");
        }
        // seconds below zero, a how that is none
        for (at, bad) in [(2, -1), (1, 3), (1, -1)] {
            let mut other = w.clone();
            other[at] = bad;
            assert!(!with(&|s| s.wishes = other.clone()), "{w:?} with {bad} at {at}");
        }
    }
    // all of them at once
    assert!(with(&|s| s.wishes = wishes.concat()));
    // a what that is none; "through" that is neither yes nor no; a radius below zero; a name that is no UTF-8 or no
    // bytes
    for bad in [
        vec![14, H_NEVER, 0, 0],
        vec![-1, H_NEVER, 0, 0],
        vec![W_STOP, H_NEVER, 0, 2, stop("VB"), 2],
        vec![W_STATION, H_NEVER, 0, 2, stop("VB"), -1],
        vec![W_CIRCLE, H_NEVER, 0, 4, 2, 0, 0, 5],
        vec![W_CIRCLE, H_NEVER, 0, 4, 0, 0, 0, -5],
        vec![W_BOX, H_NEVER, 0, 5, 7, 1, 2, 3, 4],
        vec![W_OUTLINE, H_NEVER, 0, 3, 3, 1, 2],
        vec![W_KIND, H_NEVER, 0, 1, 0xFF],
        vec![W_KIND, H_NEVER, 0, 1, 256],
        vec![W_KIND, H_NEVER, 0, 1, -1],
        vec![W_KIND, H_NEVER, 0, 2, 0xC3, 0x28],
        vec![W_LINE, H_NEVER, 0, -1],
        vec![W_LINE, H_NEVER, 0, i32::MAX, 0],
    ] {
        assert!(!with(&|s| s.wishes = bad.clone()), "{bad:?}");
    }
    // as many wishes as the planner reads, and no more
    let one = [W_BICYCLES_UNKNOWN, H_AVOID, 1, 0];
    assert!(with(&|s| s.wishes = one.repeat(MAX_WISHES)));
    assert!(!with(&|s| s.wishes = one.repeat(MAX_WISHES + 1)));
    // and a refusal leaves the next query what it is
    assert!(planned(&good));
}

/// Null where a pointer is asked for, a buffer shorter than the answer, an array that is not there: no harm, and
/// what could be given is given.
#[test]
fn null_pointers_and_short_buffers_do_no_harm() {
    let b = bytes("edges.tt");
    let tt = Timetable::open(&b).unwrap();
    let t = Open::of(&b);
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap() as i32;
    // SAFETY (every call of this test): null where the interface says null may stand, arrays with their lengths
    unsafe {
        // no timetable: nothing, and no harm
        let none: *mut AtlasTransit = null_mut();
        atlas_transit_close(none);
        assert_eq!(atlas_transit_number(none, N_STOPS, 0, 0), NONE);
        let mut len = 7usize;
        assert!(!atlas_transit_text(none, T_STOP_NAME, 0, 0, &mut len).is_null() && len == 0);
        assert!(!atlas_transit_text(none, T_STOP_NAME, 0, 0, null_mut()).is_null());
        len = 7;
        assert!(!atlas_transit_meta(none, b"first_day".as_ptr(), 9, &mut len).is_null() && len == 0);
        let q = [P_STOP, stop("WA"), 0, P_STOP, stop("WZ"), 0, 0, hm(7, 55) as i32];
        assert_eq!(atlas_transit_plan(none, q.as_ptr(), q.len(), null(), 0, null(), 0, null(), 0), 0);
        let mut out = [5i32; 4];
        assert_eq!(atlas_transit_answer(none, out.as_mut_ptr(), 4), 0);
        assert_eq!(out, [5; 4]);
        // a timetable, and names asked for without a place for their length; a key that is not there
        assert!(!atlas_transit_text(t.0, T_STOP_NAME, 0, 0, null_mut()).is_null());
        len = 7;
        assert!(!atlas_transit_meta(t.0, null(), 9, &mut len).is_null() && len == 0);
        len = 7;
        assert!(!atlas_transit_meta(t.0, [0xFFu8, 0xFE].as_ptr(), 2, &mut len).is_null() && len == 0);
        assert!(!atlas_transit_meta(t.0, b"first_day".as_ptr(), 9, null_mut()).is_null());
        // An array of no numbers is the empty array, wherever its pointer points. An array that has numbers and
        // cannot be read (no pointer, one that is not a number's place, more numbers than memory holds) refuses
        // the plan, whichever of the four it is: a plan without the wishes it was sent with would pass for the
        // answer to them.
        let whole = A_HEAD + J_HEAD + 3 * L_LEN;
        let dangling = core::ptr::NonNull::<i32>::dangling().as_ptr() as *const i32;
        assert_eq!(atlas_transit_plan(t.0, q.as_ptr(), q.len(), null(), 0, null(), 0, null(), 0), whole);
        assert_eq!(atlas_transit_plan(t.0, q.as_ptr(), q.len(), dangling, 0, dangling, 0, dangling, 0), whole);
        assert_eq!(atlas_transit_plan(t.0, q.as_ptr(), q.len(), q.as_ptr().byte_add(1), 0, null(), 0, q.as_ptr().byte_add(3), 0), whole);
        let wish = [W_NO_WHEELCHAIR, H_NEVER, 0, 0];
        let walk = [stop("WA"), 60];
        let good: [&[i32]; 4] = [&q, &walk, &walk, &wish];
        let plan = |a: [(*const i32, usize); 4]| atlas_transit_plan(t.0, a[0].0, a[0].1, a[1].0, a[1].1, a[2].0, a[2].1, a[3].0, a[3].1);
        let sent = good.map(|a| (a.as_ptr(), a.len()));
        assert_eq!(plan(sent), whole);
        // (the same numbers one byte off a number's place, in memory of their own)
        let mut kept = Vec::new();
        let mut askew = |numbers: &[i32]| {
            let mut b = vec![0u8; numbers.len() * 4 + 8];
            let at = b.as_ptr().align_offset(4) + 1;
            for (k, n) in numbers.iter().enumerate() {
                b[at + k * 4..at + k * 4 + 4].copy_from_slice(&n.to_le_bytes());
            }
            let p = b[at..].as_ptr() as *const i32;
            kept.push(b);
            p
        };
        for (which, name) in ["the query", "the walks from", "the walks to", "the wishes"].into_iter().enumerate() {
            let a = good[which];
            for (unreadable, why) in [((null(), a.len()), "no pointer"), ((askew(a), a.len()), "askew"), ((a.as_ptr(), isize::MAX as usize / 4 + 1), "too long"), ((a.as_ptr(), usize::MAX), "longer still")] {
                let mut with = sent;
                with[which] = unreadable;
                assert_eq!(plan(with), 0, "{name}: {why}");
                // (and a refused plan leaves no answer of the plan before)
                assert_eq!(atlas_transit_answer(t.0, null_mut(), 0), 0);
            }
        }
        let n = atlas_transit_plan(t.0, q.as_ptr(), q.len(), null(), 0, null(), 0, null(), 0);
        assert_eq!(n, whole);
        // the answer into no buffer, into one too short, into one just long enough, into a longer one
        assert_eq!(atlas_transit_answer(t.0, null_mut(), 100), n);
        assert_eq!(atlas_transit_answer(t.0, out.as_mut_ptr(), 0), n);
        assert_eq!(out, [5; 4]);
        assert_eq!(atlas_transit_answer(t.0, out.as_mut_ptr(), 2), n);
        assert_eq!(out, [1, 0, 5, 5]);
        let mut whole = vec![-9i32; n + 3];
        assert_eq!(atlas_transit_answer(t.0, whole.as_mut_ptr(), n), n);
        assert_eq!(whole[n..], [-9; 3]);
        let mut again = vec![-9i32; n + 3];
        assert_eq!(atlas_transit_answer(t.0, again.as_mut_ptr(), n + 3), n);
        assert_eq!(again, whole);
        assert_eq!(received(&whole[..n]).0.len(), 1);
        // and into a buffer that is no number's place nothing is written
        let mut bytes = [0x55u8; 64];
        let odd = bytes.as_mut_ptr().add(bytes.as_ptr().align_offset(4) + 1) as *mut i32;
        assert_eq!(atlas_transit_answer(t.0, odd, 8), n);
        assert_eq!(bytes, [0x55u8; 64]);
        // a place of walks with an empty array: no stop to begin at, so no journey, but a query all the same
        let walks = [P_WALKS, 0, 0, P_STOP, stop("WZ"), 0, 0, hm(7, 55) as i32];
        assert_eq!(atlas_transit_plan(t.0, walks.as_ptr(), walks.len(), null(), 0, null(), 0, null(), 0), A_HEAD);
    }
}

/// The file with one line more in its description of itself (its `META` put anew at the end).
fn with_meta(b: &[u8], line: &str) -> Vec<u8> {
    let word = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().unwrap());
    let at = (0..word(12) as usize).map(|i| 16 + i * 16).find(|o| &b[*o..*o + 4] == b"META").unwrap();
    let (off, len, n) = (word(at + 4) as usize, word(at + 8) as usize, word(at + 12));
    let mut out = b.to_vec();
    out.resize(out.len().div_ceil(8) * 8, 0);
    let to = out.len() as u32;
    out.extend_from_slice(&b[off..off + len]);
    out.extend_from_slice(line.as_bytes());
    out.push(b'\n');
    for (k, v) in [to, (len + line.len() + 1) as u32, n + 1].into_iter().enumerate() {
        out[at + 4 + k * 4..at + 8 + k * 4].copy_from_slice(&v.to_le_bytes());
    }
    out
}

/// A key of the file's description is read up to 4,096 bytes, as the header says: a longer one names no line,
/// though the file has it.
#[test]
fn a_key_longer_than_the_header_says_names_no_line() {
    let (at_most, longer) = ("k".repeat(4096), "k".repeat(4097));
    let b = with_meta(&with_meta(&bytes("edges.tt"), &format!("{at_most}=the longest")), &format!("{longer}=too long"));
    let tt = Timetable::open(&b).unwrap();
    assert_eq!((tt.meta(&at_most), tt.meta(&longer), tt.meta("days")), (Some("the longest"), Some("too long"), Some("4")));
    let t = Open::of(&b);
    assert_eq!((t.meta(&at_most), t.meta(&longer), t.meta("days")), ("the longest", "", "4"));
    assert!(include_str!("../include/atlas_transit.h").contains("4,096 bytes"));
}

/// Bytes that are no timetable are not opened, and the reason is said; where no reason is asked for, none is
/// written.
#[test]
fn a_file_that_is_no_timetable_says_why() {
    let b = bytes("edges.tt");
    let why = |bytes: &[u8]| {
        let mut why = -7;
        // SAFETY: the bytes with their length, and a number to write to
        let t = unsafe { atlas_transit_open(bytes.as_ptr(), bytes.len(), 0, &mut why) };
        // SAFETY: what open gave, closed once (null closes nothing)
        unsafe { atlas_transit_close(t) };
        assert_eq!(t.is_null(), why != OPENED);
        // the planner's own reader says the same of the same bytes
        let own = match Timetable::open(bytes) {
            Ok(_) => OPENED,
            Err(OpenError::NotATimetable) => NOT_A_TIMETABLE,
            Err(OpenError::Version(_)) => BAD_VERSION,
            Err(OpenError::Section(_)) => BAD_SECTION,
            Err(OpenError::Record(..)) => BAD_RECORD,
            Err(OpenError::Days) => BAD_DAYS,
        };
        assert_eq!(why, own);
        why
    };
    assert_eq!(why(&b), OPENED);
    assert_eq!(why(&[]), NOT_A_TIMETABLE);
    assert_eq!(why(b"not a timetable at all, but long enough to have a table of sections if it were one"), NOT_A_TIMETABLE);
    assert_eq!(why(&b[..12]), NOT_A_TIMETABLE);
    // (FORMAT.md: eight bytes that say what it is, then the version)
    let mut other = b.clone();
    other[8..12].copy_from_slice(&99u32.to_le_bytes());
    assert_eq!(why(&other), BAD_VERSION);
    // cut short: a section lies outside the file
    assert_eq!(why(&b[..b.len() / 2]), BAD_SECTION);
    // files a little wrong: each reason the reader has comes up among them
    let mut rng = Rng(0xA71A5);
    let mut reasons = [0u32; 6];
    for _ in 0..4000 {
        reasons[why(&fuzz::mutate(&b, &mut rng)) as usize] += 1;
    }
    println!("of 4000 files a little wrong: {reasons:?} (opened, no timetable, version, section, record, days)");
    assert!(reasons[OPENED as usize] > 300 && reasons[BAD_SECTION as usize] > 0 && reasons[BAD_RECORD as usize] > 300, "{reasons:?}");
    // SAFETY: no bytes at all; and no place for the reason
    unsafe {
        let mut said = -7;
        assert!(atlas_transit_open(null(), 100, 0, &mut said).is_null() && said == NOT_A_TIMETABLE);
        assert!(atlas_transit_open(b"abc".as_ptr(), 3, 0, null_mut()).is_null());
        let t = atlas_transit_open(b.as_ptr(), b.len(), 0, null_mut());
        assert!(!t.is_null());
        atlas_transit_close(t);
    }
}

/// A timetable whose index would take more memory than the caller allows is not opened, and the reason is its
/// own: to the byte what the planner says the index takes, and nothing or less than nothing is the library's own
/// bound.
#[test]
fn a_file_whose_index_would_pass_the_bound_is_not_opened() {
    for name in ["tiny.tt", "edges.tt", "waits.tt", "utrecht-20261117.tt"] {
        let b = bytes(name);
        let takes = Planner::index_bytes(&Timetable::open(&b).unwrap());
        assert!(takes > 100 && takes < i32::MAX as u64, "{name}: {takes}");
        for (most, opens) in [(takes as i32, true), (takes as i32 - 1, false), (1, false), (0, true), (-1, true), (i32::MIN, true), (i32::MAX, true)] {
            let mut why = -7;
            // SAFETY: the bytes with their length, and a number to write to
            let t = unsafe { atlas_transit_open(b.as_ptr(), b.len(), most, &mut why) };
            // SAFETY: what open gave, closed once (null closes nothing)
            unsafe { atlas_transit_close(t) };
            assert_eq!((!t.is_null(), why), (opens, if opens { OPENED } else { INDEX_TOO_LARGE }), "{name}: {takes} bytes, held to {most}");
        }
    }
}

/// Files a little wrong that still open, each asked through the interface for journeys at the edge of every field
/// and for its names and numbers: everything comes back.
#[test]
fn files_a_little_wrong_through_the_interface() {
    let mut rng = Rng(0xF11E5);
    let (mut opened, mut journeys, mut shown) = (0, 0usize, 0usize);
    for name in ["edges.tt", "tiny.tt"] {
        let seed = bytes(name);
        for _ in 0..3000 {
            let b = fuzz::mutate(&seed, &mut rng);
            let Ok(tt) = Timetable::open(&b) else { continue };
            let t = Open::of(&b);
            opened += 1;
            for _ in 0..2 {
                let v = t.plan(&send(&fuzz::query(&tt, &mut rng)));
                journeys += received(&v).0.len();
            }
            for id in (0..64).chain([u32::MAX, 1 << 20]) {
                for what in (0..70).step_by(3) {
                    shown += t.text(what, id, id % 5).len();
                    shown += (t.number(what, id, id % 7) != NONE) as usize;
                }
            }
        }
    }
    println!("{opened} files a little wrong opened: {journeys} journeys, {shown} names and numbers");
    assert!(opened > 1000 && journeys > 50 && shown > 100_000, "{opened} {journeys} {shown}");
}

/// A timetable's names and numbers are read from other threads while a plan is made on it: the file is never
/// written, and no reader touches what the plan writes.
#[test]
fn a_timetable_is_read_while_it_plans() {
    struct Shared(*mut AtlasTransit);
    // SAFETY: the interface's own word: names and numbers from any thread, one plan at a time (this thread's)
    unsafe impl Sync for Shared {}
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let t = Open::of(&b);
    let shared = Shared(t.0);
    let names: usize = (0..tt.stops()).map(|s| tt.stop_text(s, StopText::Name).len()).sum();
    let mut rng = Rng(77);
    std::thread::scope(|scope| {
        let readers: Vec<_> = (0..3)
            .map(|_| {
                let shared = &shared;
                scope.spawn(move || {
                    let mut sum = 0usize;
                    for _ in 0..40 {
                        for s in 0..tt.stops() {
                            let mut len = 0usize;
                            // SAFETY: an open timetable, read only
                            unsafe { atlas_transit_text(shared.0, T_STOP_NAME, s, 0, &mut len) };
                            // SAFETY: the same
                            sum += len + (unsafe { atlas_transit_number(shared.0, N_STOP_LAT, s, 0) } != NONE) as usize;
                        }
                    }
                    sum
                })
            })
            .collect();
        let mut journeys = 0;
        for _ in 0..60 {
            journeys += received(&t.plan(&send(&fuzz::query(&tt, &mut rng)))).0.len();
        }
        assert!(journeys > 0);
        for r in readers {
            assert_eq!(r.join().unwrap(), 40 * (names + tt.stops() as usize));
        }
    });
}
