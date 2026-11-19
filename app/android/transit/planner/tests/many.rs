//! A file's rules must not set a query's time or its answer by how many they are. The file comes over the network:
//! here the hand-written feed (tests/data/edges.tt, see tests/edges.rs for its islands) is given tens of thousands
//! of rules more, built in the test, and the answers worked out on paper must stay what they are.
//!
//! - Wacht: the bus A to B is in at 08:10, and the bus C to Z leaves 08:12 and waits for it (the feed's rule);
//!   no walk joins B and C. The one way is 08:00 to 08:30.
//! - Minstens: A to S is in at 08:10, S to Z leaves 08:12 and 08:20, and the feed wants 300 s from that bus to the
//!   08:12 one. The one way is 08:00 to 08:28.

use atlas_transit::file::{StopText, TripText};
use atlas_transit::reference::impossible;
use atlas_transit::{Journey, Leg, Place, Planner, Query, Timetable};

const NONE: u32 = u32::MAX;

fn bytes() -> Vec<u8> {
    std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/edges.tt")).unwrap()
}

fn hm(h: u32, m: u32) -> u32 {
    h * 3600 + m * 60
}

/// Where a section's line of the table is, and its offset, length and records.
fn table(b: &[u8], tag: &[u8; 4]) -> (usize, usize, usize, u32) {
    let word = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().unwrap());
    let at = (0..word(12) as usize).map(|i| 16 + i * 16).find(|o| &b[*o..*o + 4] == tag).unwrap();
    (at, word(at + 4) as usize, word(at + 8) as usize, word(at + 12))
}

/// A section's words.
fn section(b: &[u8], tag: &[u8; 4]) -> Vec<u32> {
    let (_, off, len, _) = table(b, tag);
    b[off..off + len].chunks_exact(4).map(|w| u32::from_le_bytes(w.try_into().unwrap())).collect()
}

/// The file with a section put anew: at the end, on a multiple of 8, the table pointing there (FORMAT.md: sections
/// lie anywhere after the table).
fn with_section(b: &[u8], tag: &[u8; 4], words: &[u32], records: u32) -> Vec<u8> {
    let mut out = b.to_vec();
    out.resize(out.len().div_ceil(8) * 8, 0);
    let (at, off) = (table(b, tag).0, out.len() as u32);
    out.extend(words.iter().flat_map(|w| w.to_le_bytes()));
    for (k, v) in [off, words.len() as u32 * 4, records].into_iter().enumerate() {
        out[at + 4 + k * 4..at + 8 + k * 4].copy_from_slice(&v.to_le_bytes());
    }
    out
}

/// The file with rules added, sorted by all eight fields as the format has them: from stop, to stop, from line, to
/// line, from trip, to trip, kind, least time.
fn with_rules(b: &[u8], more: impl Iterator<Item = [u32; 8]>) -> Vec<u8> {
    let mut rules: Vec<[u32; 8]> = section(b, b"TRUL").chunks_exact(8).map(|r| r.try_into().unwrap()).collect();
    rules.extend(more);
    rules.sort_unstable();
    with_section(b, b"TRUL", &rules.concat(), rules.len() as u32)
}

/// The file with stops added that no line calls at and no walk leads to, far from every other.
fn with_stops(b: &[u8], more: u32) -> Vec<u8> {
    let (mut stops, mut walks, mut routes) = (section(b, b"STOP"), section(b, b"XFRI"), section(b, b"SRTI"));
    let had = stops.len() as u32 / 12;
    for k in 0..more {
        // (a place of its own, no station, a stop of the plainest kind, no strings)
        stops.extend([540_000_000 + k, 60_000_000, NONE, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        walks.push(*walks.last().unwrap());
        routes.push(*routes.last().unwrap());
    }
    let b = with_section(b, b"STOP", &stops, had + more);
    let b = with_section(&b, b"XFRI", &walks, walks.len() as u32);
    with_section(&b, b"SRTI", &routes, routes.len() as u32)
}

fn stop(tt: &Timetable, id: &str) -> u32 {
    (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap()
}

fn trip(tt: &Timetable, id: &str) -> u32 {
    (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap()
}

fn ask(planner: &mut Planner, q: &Query) -> Vec<(u32, u32, String)> {
    let a = planner.plan(q);
    assert!(!a.cut_short, "{q:?}");
    let tt = *planner.timetable();
    let said = |j: &Journey| {
        let trips: Vec<&str> = j.legs.iter().filter_map(|l| if let Leg::Ride { trip, .. } = l { Some(tt.trip_text(*trip, TripText::Id)) } else { None }).collect();
        (j.depart as u32, j.arrive as u32, trips.join(" "))
    };
    for j in a.journeys.iter() {
        assert_eq!(impossible(planner, q, j), None, "{j:?}");
    }
    a.journeys.iter().map(said).collect()
}

/// The rule that matters is the last of 70,001 between its two stops: the others are changes the feed recommends
/// between lines that never meet there, which bind nothing. It holds all the same: the vehicle that waits is
/// reached (by the search aimed at its end and by the one that is not, forward and back), and the least time a
/// change needs is kept.
#[test]
fn a_rule_holds_however_many_stand_before_it() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let line = tt.route(tt.route_of_trip(trip(&tt, "L1a"))).line;
    let (wb, wc, ms) = (stop(&tt, "WB"), stop(&tt, "WC"), stop(&tt, "MS"));
    let recommended = |from: u32, to: u32| (0..70_000u32).map(move |k| [from, to, line, NONE, NONE, NONE, 0, k]);
    let many = with_rules(&b, recommended(wb, wc).chain(recommended(ms, ms)));
    let tt = Timetable::open(&many).unwrap();
    tt.verify().unwrap();
    assert_eq!(tt.rules(), 140_011);
    let mut p = Planner::new(tt);
    let waits = vec![(hm(8, 0), hm(8, 30), "W1a W2a".to_string())];
    let least = vec![(hm(8, 0), hm(8, 28), "M1a M2b".to_string())];
    for (from, to, want, by) in [("WA", "WZ", &waits, hm(8, 30)), ("MA", "MZ", &least, hm(8, 28))] {
        for patient in [true, false] {
            for window in [0, 600] {
                let mut q = Query::new(Place::Stop(stop(&tt, from)), Place::Stop(stop(&tt, to)), 0, hm(7, 55));
                (q.limits.patient, q.window) = (patient, window);
                assert_eq!(&ask(&mut p, &q), want, "{from} patient {patient} window {window}");
                (q.arrive_by, q.time) = (true, by);
                assert_eq!(&ask(&mut p, &q), want, "{from} patient {patient} window {window}, arriving by");
            }
        }
    }
}

/// 60,000 stops, at each of which the feed says the bus from C waits for the bus from A. None of them is on any
/// line, so the answer is the one it was; and working out which stops a vehicle waits at takes no longer than
/// reading the rules once. (It took seconds a query: each rule's stop was looked for among all those before it.
/// With more than 65,536 such rules a search back from Z holds a label at each of their stops, meets its cap on
/// those and says so.)
#[test]
fn many_rules_into_one_stop_cost_no_more_than_reading_them() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let (had, wc, w1a, w2a) = (tt.stops(), stop(&tt, "WC"), trip(&tt, "W1a"), trip(&tt, "W2a"));
    let many = with_stops(&b, 60_000);
    let many = with_rules(&many, (0..60_000u32).map(|k| [had + k, wc, NONE, NONE, w1a, w2a, 1, NONE]));
    let tt = Timetable::open(&many).unwrap();
    tt.verify().unwrap();
    assert_eq!((tt.stops(), tt.rules()), (had + 60_000, 60_011));
    let mut p = Planner::new(tt);
    let want = vec![(hm(8, 0), hm(8, 30), "W1a W2a".to_string())];
    // four queries, three times over: the fastest of the three is what they take when nothing else disturbs
    let mut took = std::time::Duration::MAX;
    for _ in 0..3 {
        let began = std::time::Instant::now();
        for patient in [true, false] {
            let mut q = Query::new(Place::Stop(stop(&tt, "WA")), Place::Stop(stop(&tt, "WZ")), 0, hm(7, 55));
            q.limits.patient = patient;
            assert_eq!(ask(&mut p, &q), want, "patient {patient}");
            (q.arrive_by, q.time) = (true, hm(8, 30));
            assert_eq!(ask(&mut p, &q), want, "patient {patient}, arriving by");
        }
        took = took.min(began.elapsed());
    }
    println!("four queries over 60,000 rules into one stop: {took:?}");
    // (14 ms on the Mac this was written on, and 620 ms there while each rule's stop was looked for among all
    // those before it: a bound that a machine many times slower keeps, and the old way does not)
    assert!(took.as_millis() < 200, "{took:?}");
}

/// A waiting rule that names a line and no trip, put into a piece of the real timetable (the city of Utrecht): every
/// bus of the line with the most trips waits, at its second stop, for whatever arrives at the stop most routes call
/// at. Looking for the bus that waits must end where the search does: at the first trip past the moment the search
/// follows journeys to, since every trip after it on its day is later still. (It read on to the end of each day:
/// 150 questions took 7.0 million steps without the rule and 1.75 million more with it, a quarter. Now 0.38 million
/// more, a twentieth: what it costs that every arrival at that stop is held apart, as under any rule.)
#[test]
fn a_line_that_waits_is_read_no_further_than_the_search_goes() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/utrecht-20261117.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    // the route with the most trips, its line and second stop; and the stop most routes call at
    let route = (0..tt.routes()).max_by_key(|r| (tt.route(*r).trips, *r)).unwrap();
    let (line, second) = (tt.route(route).line, tt.pattern_stop(tt.route(route).first_stop + 1).stop);
    let served: Vec<u32> = (0..tt.stops()).filter(|s| tt.routes_at(*s).0 != tt.routes_at(*s).1).collect();
    let hub = *served.iter().max_by_key(|s| (tt.routes_at(**s).1 - tt.routes_at(**s).0, **s)).unwrap();
    assert!(tt.route(route).trips > 100 && hub != second, "{} trips", tt.route(route).trips);
    let with = with_rules(&b, [[hub, second, NONE, line, NONE, NONE, 1, NONE]].into_iter());
    let ruled = Timetable::open(&with).unwrap();
    ruled.verify().unwrap();
    let (mut plain, mut waiting) = (Planner::new(tt), Planner::new(ruled));
    let (mut steps, mut differ) = ([0u64; 2], 0);
    for i in 0..150usize {
        let (a, z) = (served[(i * 131 + 7) % served.len()], served[(i * 197 + 61) % served.len()]);
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), (i % 2) as i32, 6 * 3600 + 421 * i as u32);
        (q.arrive_by, q.window) = (i % 3 == 1, if i % 2 == 0 { 0 } else { 1800 });
        let (without, held) = (plain.plan(&q), waiting.plan(&q));
        assert!(!without.cut_short && !held.cut_short, "{q:?}");
        for j in held.journeys.iter() {
            assert_eq!(impossible(&waiting, &q, j), None, "{q:?}\n{j:?}");
        }
        (steps[0], steps[1], differ) = (steps[0] + without.steps, steps[1] + held.steps, differ + (without.journeys != held.journeys) as u32);
    }
    let more = steps[1].saturating_sub(steps[0]);
    println!("150 questions: {} steps without the rule, {} with it ({more} more); the rule changes {differ} answers", steps[0], steps[1]);
    assert!(more < steps[0] / 10, "{more} steps more than the {} without the rule", steps[0]);
}

