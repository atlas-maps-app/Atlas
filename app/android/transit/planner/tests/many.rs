//! A file's rules must not set a query's time or its answer by how many they are. The file comes over the network:
//! here the hand-written feed (tests/data/edges.tt, see tests/edges.rs for its islands) is given tens of thousands
//! of rules more, built in the test, and the answers worked out on paper must stay what they are. So must they
//! with thousands of stops more than a search begins at, and with a trip that is under way for twelve days: what
//! the format allows and no timetable has. And a walk must be as long as the ground says wherever the feed lies: the
//! same feed moved to seven latitudes, the seconds on foot held to paper.
//!
//! - Wacht: the bus A to B is in at 08:10, and the bus C to Z leaves 08:12 and waits for it (the feed's rule);
//!   no walk joins B and C. The one way is 08:00 to 08:30.
//! - Minstens: A to S is in at 08:10, S to Z leaves 08:12 and 08:20, and the feed wants 300 s from that bus to the
//!   08:12 one. The one way is 08:00 to 08:28.

use atlas_transit::file::{StopText, TripText};
use atlas_transit::reference::{Reference, between_mm, day_start, impossible, places};
use atlas_transit::{How, Journey, Leg, Place, Planner, Query, Timetable, What, Wish};

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
    with_stops_of(b, more, NONE)
}

/// The same, the stops added being stops of a station (`parent`), or of none.
fn with_stops_of(b: &[u8], more: u32, parent: u32) -> Vec<u8> {
    let (mut stops, mut walks, mut routes) = (section(b, b"STOP"), section(b, b"XFRI"), section(b, b"SRTI"));
    let had = stops.len() as u32 / 12;
    for k in 0..more {
        // (a place of its own, a stop of the plainest kind, no strings)
        stops.extend([540_000_000 + k, 60_000_000, parent, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
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
/// bus of `line` waits at `second` for whatever arrives at `from`. The questions are asked of the file without the
/// rule and with it, for one moment and over half an hour, leaving and arriving, and held:
///
/// - every answer, with the rule and without, is the scan's on the same file (the scan reads a waiting rule in its
///   own way, pair of rides by pair), and each journey can be made;
/// - the answers the rule changes are those in which a journey makes the change it speaks of in less time than
///   the walk between the two stops takes (where there is no walk: makes it at all), and no other answer differs;
/// - whoever makes it so boards the one bus that waits: the first of the line to leave once the other vehicle is in.
///
/// Returns the steps without the rule and with it, how many answers the rule changes, how often its change is made
/// in those, and how often the same change is made by the walk.
fn a_waiting_rule(b: &[u8], from: u32, second: u32, line: u32, pairs: &[(u32, u32)]) -> ([u64; 2], u32, u32, usize) {
    let tt = Timetable::open(b).unwrap();
    let (lo, hi) = tt.walks_from(from);
    let walk = (lo..hi).map(|w| tt.walk(w)).find(|w| w.0 == second).map_or(i32::MAX, |w| w.1 as i32);
    let with = with_rules(b, [[from, second, NONE, line, NONE, NONE, 1, NONE]].into_iter());
    let ruled = Timetable::open(&with).unwrap();
    ruled.verify().unwrap();
    let (mut plain, mut waiting) = (Planner::new(tt), Planner::new(ruled));
    let scans: Vec<[Reference; 2]> = (0..2).map(|day| [Reference::new(&plain, day, -86_400, 3 * 86_400), Reference::new(&waiting, day, -86_400, 3 * 86_400)]).collect();
    // whether a bus of the line, other than `own` on its day, leaves the second stop from `since` on and before `until`
    let leaves_between = |day: i32, since: i32, until: i32, own: (u32, i32)| {
        let (lo, hi) = tt.routes_at(second);
        (lo..hi).map(|e| tt.route_at(e)).filter(|(r, pos)| tt.route(*r).line == line && pos + 1 < tt.route(*r).stops).any(|(r, pos)| {
            let r = tt.route(r);
            (r.first_trip..r.first_trip + r.trips).any(|t| {
                (-1..=1).any(|off| {
                    let at = day_start(&tt, day, off) + tt.time(tt.trip_times(t), pos).1 as i32;
                    at >= since && at < until && (t, day + off) != own && tt.runs(tt.trip(t).service, day + off)
                })
            })
        })
    };
    // the change the rule speaks of: off at the one stop, on to a bus of the line at the other; as the moment one
    // is in, the moment the bus leaves, and the bus on its day
    let changes = |j: &Journey| -> Vec<(i32, i32, (u32, i32))> {
        let rides: Vec<&Leg> = j.legs.iter().filter(|l| matches!(l, Leg::Ride { .. })).collect();
        let mut out = Vec::new();
        for w in rides.windows(2) {
            if let (Leg::Ride { to, arrive, .. }, Leg::Ride { from: on, depart, trip, day, route, .. }) = (w[0], w[1]) {
                if (*to, *on, tt.route(*route).line) == (from, second, line) {
                    out.push((*arrive, *depart, (*trip, *day)));
                }
            }
        }
        out
    };
    // (without the rule the change takes the walk; with it, less only where the rule is used)
    let by_rule = |js: &[Journey]| -> Vec<(i32, i32, (u32, i32))> { js.iter().flat_map(changes).filter(|c| c.1 - c.0 < walk).collect() };
    let (mut steps, mut differ, mut made, mut walked) = ([0u64; 2], 0, 0, 0);
    for (i, &(a, z)) in pairs.iter().enumerate() {
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), (i % 2) as i32, 6 * 3600 + 421 * i as u32);
        (q.arrive_by, q.window, q.max_rides) = (i % 3 == 1, if i % 2 == 0 { 0 } else { 1800 }, 16);
        let (without, held) = (plain.plan(&q), waiting.plan(&q));
        assert!(!without.cut_short && !held.cut_short, "{q:?}");
        for (k, (planner, answer)) in [(&plain, &without), (&waiting, &held)].into_iter().enumerate() {
            for j in answer.journeys.iter() {
                assert_eq!(impossible(planner, &q, j), None, "{q:?}\n{j:?}");
            }
            let scan = &scans[q.day as usize][k];
            let differs = if q.window == 0 { scan.moment_differs(planner, &q, &answer.journeys) } else { scan.window_differs(planner, &q, &answer.journeys) };
            assert_eq!(differs, None, "question {i}, {}: {q:?}", ["without the rule", "with it"][k]);
        }
        assert!(by_rule(&without.journeys).is_empty(), "question {i}: {q:?}");
        let uses = by_rule(&held.journeys);
        walked += held.journeys.iter().flat_map(changes).count() - uses.len();
        assert_eq!(without.journeys != held.journeys, !uses.is_empty(), "question {i}: the rule changes an answer in which nobody uses it, or is used and changes nothing: {q:?}");
        differ += !uses.is_empty() as u32;
        for (arrive, depart, bus) in uses {
            made += 1;
            assert!(depart >= arrive && !leaves_between(q.day, arrive, depart, bus), "question {i}: in at {arrive}, on the bus of {depart}, and another of the line leaves between: {q:?}");
        }
        (steps[0], steps[1]) = (steps[0] + without.steps, steps[1] + held.steps);
    }
    (steps, differ, made, walked)
}

/// Every bus of the line with the most trips waits, at its second stop, for whatever arrives at the stop most
/// routes call at. Looking for the bus that waits must end where the search does: at the first trip past the moment
/// the search follows journeys to, since every trip after it on its day is later still. (It read on to the end of
/// each day, which cost a quarter more steps on these 150 questions; now a twentieth: what it costs that every
/// arrival at that stop is held apart, as under any rule.) A walk of four minutes joins the two stops, and of these
/// answers the rule changes none: that is held too, with the rest of `a_waiting_rule`.
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
    let pairs: Vec<(u32, u32)> = (0..150usize).map(|i| (served[(i * 131 + 7) % served.len()], served[(i * 197 + 61) % served.len()])).collect();
    let (steps, differ, made, walked) = a_waiting_rule(&b, hub, second, line, &pairs);
    let more = steps[1].saturating_sub(steps[0]);
    println!("150 questions: {} steps without the rule, {} with it ({more} more); the rule changes {differ} answers ({made} changes by it, {walked} by the walk)", steps[0], steps[1]);
    assert!(more < steps[0] / 10, "{more} steps more than the {} without the rule", steps[0]);
}

/// The same rule where it changes answers: the buses of that line wait for whatever arrives at a stop a long way
/// off, which no walk joins to theirs; the stop most routes call at among those. The questions lead from stops
/// with a ride to that stop to stops along the line. What the rule changes and what it must not is
/// `a_waiting_rule`'s to hold; here, that it changes enough answers for that to mean something.
#[test]
fn a_line_that_waits_changes_the_answers_it_should_and_no_other() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/utrecht-20261117.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    let route = (0..tt.routes()).max_by_key(|r| (tt.route(*r).trips, *r)).unwrap();
    let (line, second) = (tt.route(route).line, tt.pattern_stop(tt.route(route).first_stop + 1).stop);
    let served: Vec<u32> = (0..tt.stops()).filter(|s| tt.routes_at(*s).0 != tt.routes_at(*s).1).collect();
    // more than two kilometres from the line's second stop, by latitude or by longitude alone
    let apart = |s: u32| (tt.stop(s).lat - tt.stop(second).lat).abs().max((tt.stop(s).lon - tt.stop(second).lon).abs() * 6 / 10);
    let far = *served.iter().filter(|s| apart(**s) > 180_000).max_by_key(|s| (tt.routes_at(**s).1 - tt.routes_at(**s).0, **s)).unwrap();
    let (lo, hi) = tt.walks_from(far);
    assert!(!(lo..hi).any(|w| tt.walk(w).0 == second));
    // the stops with a ride to that stop, and the stops of the line's route past its second
    let (lo, hi) = tt.routes_at(far);
    let mut towards: Vec<u32> = (lo..hi).map(|e| tt.route_at(e)).flat_map(|(r, pos)| (0..pos).map(move |k| (r, k))).map(|(r, k)| tt.pattern_stop(tt.route(r).first_stop + k).stop).collect();
    towards.sort_unstable();
    towards.dedup();
    let along: Vec<u32> = (2..tt.route(route).stops).map(|pos| tt.pattern_stop(tt.route(route).first_stop + pos).stop).collect();
    assert!(towards.len() >= 4 && along.len() >= 4, "{} {}", towards.len(), along.len());
    let pairs: Vec<(u32, u32)> = (0..150usize).map(|i| (towards[(i * 7 + 3) % towards.len()], along[(i * 5) % along.len()])).collect();
    let (steps, differ, made, walked) = a_waiting_rule(&b, far, second, line, &pairs);
    println!("150 questions towards the line that waits: {} steps without the rule, {} with it; the rule changes {differ} answers ({made} changes by it, {walked} by a walk)", steps[0], steps[1]);
    assert!(differ > 40 && made >= differ && walked == 0, "{differ} {made} {walked}");
}

/// A bus that waits is boarded by whoever came in since the one before it left: searching back from it, by every
/// arrival of a route in that time, and where those trips differ in cost, by the last of them and by each cheaper
/// one before it, however many costlier ones stand between (a boarding once looked at 32 and no further).
///
/// A feed written by hand for it (tests/data/waits, packed to waits.tt; Monday 5 April 2027 is day 0). The buses
/// of line K1 leave Druk A every two minutes from 08:00 to 09:28, 45 of them (K1_00 to K1_44), and are at Druk B
/// five minutes later. Line K2 leaves Druk C at 07:00 and at 09:40 and is at Druk Z twenty minutes later; its buses
/// wait at C for whatever arrives at B (the feed's rule, which names the line), and no walk joins B and C. So the
/// 09:40 waits for all 45, and whoever is at Z by 10:00 left A at 09:28 at the latest.
///
/// With the last 40 of the 45 each avoided, a minute a ride: to be at Z by 10:00 one leaves at 09:28, which costs
/// a minute, or at 08:08 by K1_04, the last that costs nothing, 40 costlier trips before the bus that waits.
/// Weighed, the first alone: a minute is less than the hour and twenty it leaves later. Leaving at 08:00, every way
/// is in at 10:00, so the one that costs nothing is the answer, as late as it can leave; from 08:09 only the
/// costly are left. Every answer is the scan's too.
#[test]
fn more_costly_trips_in_a_row_before_a_bus_that_waits() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/waits.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    tt.verify().unwrap();
    assert_eq!((tt.first_day(), tt.days(), tt.stops(), tt.trips(), tt.walks(), tt.rules()), (20270405, 2, 4, 47, 0, 1));
    let mut p = Planner::new(tt);
    let scan = Reference::new(&p, 0, -86_400, 3 * 86_400);
    let avoided: Vec<Wish> = (5..45).map(|n| Wish { what: What::Trip(trip(&tt, &format!("K1_{n:02}"))), how: How::Avoid(60) }).collect();
    let mut asked = |time: u32, by: bool, weigh: bool, wishes: &[Wish]| -> Vec<(u32, u32, String, u32)> {
        let mut q = Query::new(Place::Stop(stop(&tt, "KA")), Place::Stop(stop(&tt, "KZ")), 0, time);
        (q.arrive_by, q.weigh, q.max_rides, q.limits.options, q.limits.bag) = (by, weigh, 16, 1024, 16);
        q.wishes = wishes.to_vec();
        let a = p.plan(&q);
        assert!(!a.cut_short, "{q:?}");
        for j in a.journeys.iter() {
            assert_eq!(impossible(&p, &q, j), None, "{j:?}");
        }
        assert_eq!(scan.moment_differs(&p, &q, &a.journeys), None, "{q:?}");
        let trips = |j: &Journey| j.legs.iter().filter_map(|l| if let Leg::Ride { trip, .. } = l { Some(tt.trip_text(*trip, TripText::Id)) } else { None }).collect::<Vec<_>>().join(" ");
        a.journeys.iter().map(|j| (j.depart as u32, j.arrive as u32, trips(j), j.penalty)).collect()
    };
    let way = |leaves: (u32, u32), trips: &str, cost: u32| (hm(leaves.0, leaves.1), hm(10, 0), trips.to_string(), cost);
    let (last, free) = (way((9, 28), "K1_44 K2b", 60), way((8, 8), "K1_04 K2b", 0));
    // without wishes, the last bus the 09:40 waits for, whichever way it is asked
    assert_eq!(asked(hm(10, 0), true, false, &[]), [way((9, 28), "K1_44 K2b", 0)]);
    assert_eq!(asked(hm(8, 0), false, false, &[]), [way((9, 28), "K1_44 K2b", 0)]);
    assert_eq!(asked(hm(9, 59), true, false, &[]), []);
    // the last 40 avoided
    assert_eq!(asked(hm(10, 0), true, false, &avoided), [free.clone(), last.clone()]);
    assert_eq!(asked(hm(10, 0), true, true, &avoided), [last.clone()]);
    assert_eq!(asked(hm(8, 0), false, false, &avoided), [free.clone()]);
    assert_eq!(asked(hm(8, 0), false, true, &avoided), [free.clone()]);
    assert_eq!(asked(hm(8, 9), false, false, &avoided), [last.clone()]);
    assert_eq!(asked(hm(8, 9), false, true, &avoided), [last.clone()]);
}

/// A place may stand for more stops than a search begins at (4,096): a list of walks the app worked out, a
/// station of many stops, every stop within reach of a point. The nearest stay, and the answer says that it is
/// cut short; a list that only repeats itself is not too long. (The first 4,096 lines of a list were read and no
/// more, and of more stops the nearest were kept: both without a word.)
#[test]
fn a_place_of_more_stops_than_a_search_begins_at_says_so() {
    let b = bytes();
    let had = Timetable::open(&b).unwrap().stops();
    let more = with_stops(&b, 5000);
    let tt = Timetable::open(&more).unwrap();
    let mut p = Planner::new(tt);
    let (wa, wz) = (stop(&tt, "WA"), stop(&tt, "WZ"));
    // ten minutes on foot to Wacht's A, and ten seconds to stops no line calls at
    let dead = |n: u32| (had..had + n).map(|s| (s, 10u32));
    let way = vec![(hm(7, 50), hm(8, 30), "W1a W2a".to_string())];
    let from = |walks: Vec<(u32, u32)>| Query::new(Place::Walks(walks), Place::Stop(wz), 0, hm(7, 45));
    // 4,096 stops are all kept, wherever in the list the one that matters stands
    let q = from(dead(4095).chain([(wa, 600)]).collect());
    assert_eq!(ask(&mut p, &q), way);
    // and so are they when the list says some of them twice before it comes to that one
    let q = from(dead(4095).chain(dead(900)).chain([(wa, 600)]).collect());
    assert_eq!(p.ends(&q).from.len(), 4096);
    assert_eq!(ask(&mut p, &q), way);
    // one more: the 4,096 nearest stay, the stop ten minutes off is not among them, and the answer says so
    for first in [true, false] {
        let a_stop = |at: bool| (at == first).then_some((wa, 600));
        let q = from(a_stop(true).into_iter().chain(dead(4096)).chain(a_stop(false)).collect());
        let ends = p.ends(&q);
        assert!(ends.cut && ends.from.len() == 4096 && !ends.from.iter().any(|e| e.0 == wa), "first {first}");
        let a = p.plan(&q);
        assert!(a.cut_short && a.journeys.is_empty(), "first {first}: {:?}", a.journeys);
    }
    // arriving there, the same
    let mut q = Query::new(Place::Stop(wa), Place::Walks(dead(4096).chain([(wz, 600)]).collect()), 0, hm(8, 45));
    q.arrive_by = true;
    assert!(p.plan(&q).cut_short);
    q.to = Place::Walks(dead(4095).chain(dead(4095)).chain([(wz, 600)]).collect());
    assert_eq!(ask(&mut p, &q), [(hm(8, 0), hm(8, 40), "W1a W2a".to_string())]);

    // a station of 5,001 stops: the search begins at 4,096 of them and says so
    let station = with_stops_of(&b, 5000, wa);
    let tt = Timetable::open(&station).unwrap();
    let mut p = Planner::new(tt);
    let q = Query::new(Place::Station(wa), Place::Stop(wz), 0, hm(7, 55));
    let ends = p.ends(&q);
    assert!(ends.cut && ends.from.len() == 4096);
    let a = p.plan(&q);
    assert!(a.cut_short);
    // (of stops as near as each other those of the lower number stay: A is among them)
    assert_eq!(a.journeys.iter().map(|j| (j.depart as u32, j.arrive as u32)).collect::<Vec<_>>(), [(hm(8, 0), hm(8, 30))]);
    // and a station of 4,096 is whole
    let station = with_stops_of(&b, 4095, wa);
    let tt = Timetable::open(&station).unwrap();
    let mut p = Planner::new(tt);
    assert!(!p.ends(&q).cut && p.ends(&q).from.len() == 4096);
    assert_eq!(ask(&mut p, &q), [(hm(8, 0), hm(8, 30), "W1a W2a".to_string())]);
}

/// The format lets a trip's first departure and its time under way each be just under 2^20 s, twelve days: a file
/// may hold a trip that ends twenty-four days after its service day began. Every search then looks that many
/// service days back for what may still be running, and must not lose the asked day over it. (Sixteen days were
/// read from the earliest on: the asked one was not among them, and the answer was empty without a word.)
#[test]
fn a_trip_of_many_days_hides_no_other() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let (late, long) = (trip(&tt, "L3a"), trip(&tt, "L2a"));
    // L3a, alone on its route, leaves 1,040,000 s into its service day; L2a, alone on its route too, gets times
    // of its own: 1,040,000 s to its second stop
    let (mut trips, mut profiles, mut times) = (section(&b, b"TRIP"), section(&b, b"PROF"), section(&b, b"PTIM"));
    trips[late as usize * 4] = 1_040_000;
    trips[long as usize * 4 + 1] = profiles.len() as u32 / 2;
    profiles.extend([times.len() as u32 / 2, 2]);
    times.extend([0, 0, 1_040_000, 1_040_000]);
    let b = with_section(&b, b"TRIP", &trips, trips.len() as u32 / 4);
    let b = with_section(&b, b"PROF", &profiles, profiles.len() as u32 / 2);
    let b = with_section(&b, b"PTIM", &times, times.len() as u32 / 2);
    let tt = Timetable::open(&b).unwrap();
    tt.verify().unwrap();
    let mut p = Planner::new(tt);
    // Wacht, as it ever was: 08:00 to 08:30, forward and back, for one moment and over a window
    let waits = vec![(hm(8, 0), hm(8, 30), "W1a W2a".to_string())];
    for window in [0, 600] {
        let mut q = Query::new(Place::Stop(stop(&tt, "WA")), Place::Stop(stop(&tt, "WZ")), 0, hm(7, 55));
        q.window = window;
        assert_eq!(ask(&mut p, &q), waits, "window {window}");
        (q.arrive_by, q.time) = (true, hm(8, 30));
        assert_eq!(ask(&mut p, &q), waits, "window {window}, arriving by");
    }
    // and the widest question there is: a day's window, followed two days on
    let mut q = Query::new(Place::Stop(stop(&tt, "WA")), Place::Stop(stop(&tt, "WZ")), 0, hm(7, 55));
    (q.window, q.limits.horizon) = (86_400, 2 * 86_400);
    assert_eq!(ask(&mut p, &q), waits);
    // and Laat's last way that evening is gone: its bus to B is twelve days under way, further than any question
    // follows a journey
    let mut q = Query::new(Place::Stop(stop(&tt, "LA")), Place::Stop(stop(&tt, "LB")), 0, hm(23, 30));
    q.limits.horizon = 2 * 86_400;
    assert_eq!(ask(&mut p, &q), []);
}

/// A walk is as long as the ground says, at any latitude. The hand-written feed lies at 52 degrees north, and a
/// degree of longitude is as long there as nowhere else: here the whole feed is moved, so that Lopen's stop A lies on
/// the equator, at 30, 52.1, 59.95, 70 and 80 degrees north and at 45 south, and a point is put a hundredth of a
/// degree east of it, half a hundredth north, and both the other way. The seconds are paper's: a degree of latitude
/// 111,194.93 m, a degree of longitude that times the cosine of the latitude, the way 1.3 times the line, 1.25 m/s,
/// rounded up.
///
/// Held to them: what the search takes from the point to the stop (within a second and half a per cent: its cosine
/// is from a table by the tenth of a degree); what the scan allows for it by its own distance (reference::between_mm:
/// paper lies between its least and its most), and that the scan has no word against the search's stops for the
/// place (reference::places); the walk all the way between the point and the stop's own place; and every journey
/// (reference::impossible, which reads the walks' seconds from the scan's places).
#[test]
fn a_walk_is_as_long_as_the_ground_says_at_any_latitude() {
    let b = bytes();
    let home = Timetable::open(&b).unwrap();
    let (pa, pz) = (stop(&home, "PA"), stop(&home, "PZ"));
    let mut journeys = 0;
    for (lat, east, north, both) in [(0, 1157u32, 579u32, 1293u32), (300_000_000, 1002, 579, 1157), (-450_000_000, 818, 579, 1002), (521_000_000, 711, 579, 916), (599_500_000, 580, 579, 819), (700_000_000, 396, 579, 701), (800_000_000, 201, 579, 613)] {
        let mut stops = section(&b, b"STOP");
        let shift = lat - home.stop(pa).lat;
        for s in stops.chunks_exact_mut(12) {
            s[0] = (s[0] as i32 + shift) as u32;
        }
        let moved = with_section(&b, b"STOP", &stops, stops.len() as u32 / 12);
        let tt = Timetable::open(&moved).unwrap();
        tt.verify().unwrap();
        let mut p = Planner::new(tt);
        let at = tt.stop(pa);
        assert_eq!(at.lat, lat);
        for (dlat, dlon, paper) in [(0, 100_000, east), (50_000, 0, north), (-50_000, -100_000, both)] {
            let close = |secs: u32| secs.abs_diff(paper) <= 1 + paper / 200;
            let point = Place::Point { lat: at.lat + dlat, lon: at.lon + dlon };
            let mut q = Query::new(point.clone(), Place::Stop(pz), 0, hm(7, 30));
            q.reach_m = 2000;
            let secs = p.ends(&q).from.iter().find(|e| e.0 == pa).map(|e| e.1);
            assert!(secs.is_some_and(close), "at {lat}, {dlat} north and {dlon} east: the search has {secs:?} s, paper {paper}");
            let mm = between_mm(at.lat + dlat, at.lon + dlon, at.lat, at.lon);
            assert!((mm.0 * 13 / 10 / 1250) as u32 <= paper && paper <= (mm.1 * 13 / 10).div_ceil(1250) as u32, "at {lat}: the scan has {mm:?} mm, paper {paper} s");
            assert!(mm.1 - mm.0 <= mm.1 / 20 + 4 || lat >= 800_000_000, "at {lat}: the scan allows {mm:?} mm");
            assert_eq!(places(&p, &q).wrong, None, "at {lat}, {dlat} north and {dlon} east");
            journeys += ask(&mut p, &q).len();
            // all the way on foot, to the stop's own place
            let mut q = Query::new(point, Place::Point { lat: at.lat, lon: at.lon }, 0, hm(7, 30));
            q.reach_m = 2000;
            let a = p.plan(&q);
            let walk: Vec<u32> = a.journeys.iter().filter(|j| j.rides == 0).map(|j| (j.arrive - j.depart) as u32).collect();
            assert!(walk.len() == 1 && close(walk[0]), "at {lat}, {dlat} north and {dlon} east: on foot all the way {walk:?} s, paper {paper}");
            for j in a.journeys.iter() {
                assert_eq!(impossible(&p, &q, j), None, "at {lat}: {j:?}");
            }
        }
    }
    assert!(journeys > 20, "{journeys}");
}
