//! Two of the feed's rules in one journey: what a change under one rule may lead to under the next.
//!
//! A feed written by hand for it (tests/data/keten, packed to keten.tt; the first two islands are the app
//! maintainer's, 19 November 2026; Monday 5 April 2027 is day 0), five islands that no walk joins:
//!
//! - **Keten.** Q1a leaves O at 09:50 and is in at A at 10:00. A and B are 302 m apart, a walk of 375 s. Q2a leaves B
//!   at 10:01 and waits for Q1a (the feed's rule, which names both trips); Q2b leaves B at 10:03, and nobody off Q1a
//!   makes it. Q2a is in at C at 10:20, Q2b at 10:22. C and D are 201 m apart, a walk of 270 s. Q3a leaves D at 10:23
//!   and waits for Q2b, and for no other; Q3b leaves D at 10:40. Each is at Z seventeen minutes later. So whoever
//!   leaves O at 09:50 rides Q1a and Q2a, walks to D (there at 10:24:30, after Q3a has left) and is at Z at 10:57
//!   by Q3b. Nobody from O is at Z by 10:40: Q3a is caught only off Q2b, and Q2b only by whoever was at B at 10:03.
//!   With the rules off nobody gets from O to Z at all: the walk to B takes longer than either bus of Q2 waits.
//! - **Slot,** the same with one stop in place of A and B: R1a is in at A at 10:00, R2a leaves A at 10:05 and R2b at
//!   10:07, and the feed says no change is possible from R1a to R2b. R3a leaves D at 10:23 and waits for R2b alone.
//!   So from O it is R1a, R2a, the walk (271 s, at D at 10:24:31) and R3b, at Z at 10:57, with the rules and
//!   without them.
//! - **Wissel,** where the later bus is boarded further along. W1a is in at A at 10:00, and W2a leaves B (378 s
//!   away) at 10:01 and waits for it, as on Keten. Line W2 calls at P on its way: W2a at 10:08, W2b (which left B
//!   at 10:05) at 10:14; and after P at M and at X, which matter to a wish only. W4a leaves O at 09:52 and is in
//!   at P at 10:09: too late for W2a, in time for W2b. W2a is at S at 10:20, W2b at 10:24. T is 272 s from S; W3a
//!   leaves T at 10:24 and waits for W2b alone, W3b leaves at 10:45; each is at Z seventeen minutes later. So the way is W4a, W2b from P, and W3a, which waits: from O at
//!   09:52, at Z at 10:41. Whoever rode W2a from B is at T at 10:24:32 and has W3b, at Z at 11:02; so has
//!   everybody with the rules off, by W4a and W2b. And W0a brings people from N to O at 09:48, in time for W1a
//!   and for W4a: from N the way is W0a, W4a, W2b and W3a, from 09:40 to 10:41. (Asked for one moment,
//!   what was found is searched again from the later moments at which a trip leaves: from O that gives those on
//!   W4a a search to themselves, from N they are in one search with those on W1a and W2a.) W6a, for the wish:
//!   it leaves O at 09:49 and is in at X at 10:15, two minutes before W2a calls there, and the feed says no change
//!   is possible from W6a to W2b. So it gives nobody a better way: W2a from X is at S at 10:20, and W3b it is.
//! - **Terug,** the same turned round, for a search from the far end. T1a is in at A at 10:00, and T2a leaves B
//!   (381 s away) at 10:01 and waits for it; T2b leaves B at 10:05. Line T2 calls at M, and then at P: T2a at
//!   10:10, T2b at 10:14. T4a leaves P at 10:12 and is at Z at 10:30: off T2a it is caught, off T2b not. T2a is at S at 10:20, T2b at 10:24;
//!   T3a leaves T (274 s from S) at 10:24, waits for T2b alone and is at Z at 10:41. So the way is T1a, T2a to P,
//!   and T4a: from O at 09:50, at Z at 10:30, whether one asks to leave at 09:45, to be there by 10:30 or by
//!   10:41. With the rules off there is none. And T5a takes people on from Z at 10:45 to Q, in at 10:55: off
//!   T4a and off T3a alike, so a search back from Q follows both at once.
//! - **Venster,** where the rule names lines and one vehicle waits for each arrival. V1a, V2a (which waits for it)
//!   and V2b as on Keten: V2a is in at C at 10:20, V2b at 10:22. The feed says that line V3 waits at D (273 s
//!   away) for line V2: for each arrival the first trip of V3 to leave. V3x leaves D at 10:21 for Y, V3a at 10:23
//!   and V3b at 10:45 for Z. So for V2a it is V3x that waits, which does not go to Z; V3a waits for V2b, which
//!   nobody from O is on. From O it is V1a, V2a, the walk (at D at 10:24:33) and V3b: at Z at 11:02, and nobody is
//!   there by 10:40.
//!
//! A search rides the first trip of a route it can catch, and holds the later trips of that route too where a
//! vehicle waits for one of them (`later_arrivals` in plan.rs). A later trip may be given only to whoever could
//! have boarded it as the earlier one was boarded. It was given to all: to those let on Q2a as the one bus that
//! waits for Q1a (so they "rode" Q2b, which left B before they could walk there, and Q3a waited for them), and to
//! those whom the feed bars from R2b. On Wissel the later bus has riders of its own, who board at P after W2a
//! has gone: along the route they are behind those on W2a everywhere, so the search rides W2a alone, and W2b must
//! still be held at S for what waits for it, as the bus of those who boarded it at P and of nobody from B. On Terug
//! it is the search back from Q that meets the same: T2b first, as the one arrival T3a waits for, and then at P
//! those who must be on T2a for T4a, behind T2b everywhere; T2a must still be held at B, where it waits for T1a.
//! And on Venster the search back from Z boards V2b as an arrival V3a waits for, under a rule that names no trip:
//! the trip before it, V2a, is an arrival of the same line, and V3a does not wait for it (V3x left in between).
use atlas_transit::file::{StopText, TripText};
use atlas_transit::reference::{Reference, impossible};
use atlas_transit::{How, Journey, Leg, Place, Planner, Query, Timetable, What, Wish};

fn hm(h: u32, m: u32) -> u32 {
    h * 3600 + m * 60
}

fn said(tt: &Timetable, j: &Journey) -> (u32, u32, String) {
    let trips: Vec<&str> = j.legs.iter().filter_map(|l| if let Leg::Ride { trip, .. } = l { Some(tt.trip_text(*trip, TripText::Id)) } else { None }).collect();
    (j.depart as u32, j.arrive as u32, trips.join(" "))
}

/// The twenty-four questions of the five islands, leaving and arriving, with the rules and without: each answer is the
/// one worked out above, each journey in it can be made (`impossible` says nothing against it), and the scan
/// agrees. One is held as it is today and named: see `SLOT_BACK`.
#[test]
fn two_rules_in_one_journey() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/keten.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    tt.verify().unwrap();
    let mut p = Planner::new(tt).unwrap();
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    let walk = |a: &str, z: &str| {
        let (lo, hi) = tt.walks_from(stop(a));
        (lo..hi).map(|i| tt.walk(i)).find(|w| w.0 == stop(z)).map(|w| w.1)
    };
    // what the workings above stand on
    assert_eq!((walk("QA", "QB"), walk("QC", "QD"), walk("RC", "RD"), walk("QC", "QB"), tt.rules()), (Some(375), Some(270), Some(271), None, 11));
    assert_eq!((walk("WA", "WB"), walk("WS", "WT"), walk("WP", "WB"), walk("WO", "WA")), (Some(378), Some(272), None, None));
    assert_eq!((walk("TA", "TB"), walk("TS", "TT"), walk("TP", "TB"), walk("VA", "VB"), walk("VC", "VD"), walk("VD", "VY")), (Some(381), Some(274), None, Some(379), Some(273), None));
    // (from W4a to W2b at P there are five minutes; from T2a to T4a two, and from T2b none)
    assert_eq!(tt.change_seconds(), 60);
    let keten = vec![(hm(9, 50), hm(10, 57), "Q1a Q2a Q3b")];
    let slot = vec![(hm(9, 50), hm(10, 57), "R1a R2a R3b")];
    let wissel = vec![(hm(9, 52), hm(10, 41), "W4a W2b W3a")];
    let terug = vec![(hm(9, 50), hm(10, 30), "T1a T2a T4a")];
    let venster = vec![(hm(9, 50), hm(11, 2), "V1a V2a V3b")];
    // the question, from, to, the moment, arriving by, the rules, the answer on paper
    let cases: [(&str, &str, &str, u32, bool, bool, Vec<(u32, u32, &str)>); 24] = [
        ("Keten, leaving at 09:45", "QO", "QZ", hm(9, 45), false, true, keten.clone()),
        ("Keten, there by 10:40", "QO", "QZ", hm(10, 40), true, true, vec![]),
        ("Keten, there by 10:57", "QO", "QZ", hm(10, 57), true, true, keten),
        ("Keten, leaving at 09:45, the rules off", "QO", "QZ", hm(9, 45), false, false, vec![]),
        ("Slot, leaving at 09:45", "RO", "RZ", hm(9, 45), false, true, slot.clone()),
        ("Slot, there by 10:40", "RO", "RZ", hm(10, 40), true, true, vec![]),
        (SLOT_BACK, "RO", "RZ", hm(10, 57), true, true, slot.clone()),
        ("Slot, leaving at 09:45, the rules off", "RO", "RZ", hm(9, 45), false, false, slot),
        ("Wissel, leaving at 09:45", "WO", "WZ", hm(9, 45), false, true, wissel.clone()),
        ("Wissel, there by 10:41", "WO", "WZ", hm(10, 41), true, true, wissel.clone()),
        ("Wissel, there by 11:02", "WO", "WZ", hm(11, 2), true, true, wissel),
        ("Wissel, leaving at 09:45, the rules off", "WO", "WZ", hm(9, 45), false, false, vec![(hm(9, 52), hm(11, 2), "W4a W2b W3b")]),
        ("Wissel from N, leaving at 09:35", "WN", "WZ", hm(9, 35), false, true, vec![(hm(9, 40), hm(10, 41), "W0a W4a W2b W3a")]),
        ("Wissel from N, there by 10:41", "WN", "WZ", hm(10, 41), true, true, vec![(hm(9, 40), hm(10, 41), "W0a W4a W2b W3a")]),
        ("Terug, leaving at 09:45", "TO", "TZ", hm(9, 45), false, true, terug.clone()),
        ("Terug, there by 10:30", "TO", "TZ", hm(10, 30), true, true, terug.clone()),
        ("Terug, there by 10:41", "TO", "TZ", hm(10, 41), true, true, terug),
        ("Terug, leaving at 09:45, the rules off", "TO", "TZ", hm(9, 45), false, false, vec![]),
        ("Terug to Q, leaving at 09:45", "TO", "TQ", hm(9, 45), false, true, vec![(hm(9, 50), hm(10, 55), "T1a T2a T4a T5a")]),
        ("Terug to Q, there by 10:55", "TO", "TQ", hm(10, 55), true, true, vec![(hm(9, 50), hm(10, 55), "T1a T2a T4a T5a")]),
        ("Venster, leaving at 09:45", "VO", "VZ", hm(9, 45), false, true, venster.clone()),
        ("Venster, there by 10:40", "VO", "VZ", hm(10, 40), true, true, vec![]),
        ("Venster, there by 11:02", "VO", "VZ", hm(11, 2), true, true, venster),
        ("Venster, leaving at 09:45, the rules off", "VO", "VZ", hm(9, 45), false, false, vec![]),
    ];
    let mut wrong = Vec::new();
    for (name, from, to, at, by, rules, paper) in cases {
        let mut q = Query::new(Place::Stop(stop(from)), Place::Stop(stop(to)), 0, at);
        (q.arrive_by, q.rules) = (by, rules);
        let a = p.plan(&q);
        assert!(!a.cut_short, "{name}");
        let got: Vec<(u32, u32, String)> = a.journeys.iter().map(|j| said(&tt, j)).collect();
        for (j, g) in a.journeys.iter().zip(got.iter()) {
            if let Some(no) = impossible(&p, &q, j) {
                wrong.push(format!("{name}: {} cannot be made: {no}", g.2));
            }
        }
        let scan = Reference::new(&p, q.day, q.time as i32 - 86_400, q.time as i32 + 2 * 86_400);
        let differs = scan.moment_differs(&p, &q, &a.journeys);
        let paper: Vec<(u32, u32, String)> = paper.iter().map(|w| (w.0, w.1, w.2.to_string())).collect();
        if name == SLOT_BACK {
            // Held as it is, until the search labels the later trips of a route where the rules bar the earlier one
            // (the app maintainer's A7 of batch 6: searching back, the first trip caught is R2b, which nobody off
            // R1a may board, and R2a before it is never ridden). The scan has the answer on paper; the planner
            // has none, which is an option missed and never a journey that cannot be made.
            assert!(got.is_empty() && differs.is_some(), "{name}: said {got:?}, the scan: {differs:?}. If this is the answer on paper now ({paper:?}), hold it as the others");
            continue;
        }
        if let Some(d) = differs {
            wrong.push(format!("{name}: the scan differs: {d}"));
        }
        if got != paper {
            wrong.push(format!("{name}: said {got:?}, on paper {paper:?}"));
        }
    }
    assert!(wrong.is_empty(), "\n{}", wrong.join("\n"));
}

const SLOT_BACK: &str = "Slot, there by 10:57";

const NONE: u32 = u32::MAX;

/// Where a section's line of the table is, and its offset, length and records (as in tests/many.rs).
fn table(b: &[u8], tag: &[u8; 4]) -> (usize, usize, usize, u32) {
    let word = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().unwrap());
    let at = (0..word(12) as usize).map(|i| 16 + i * 16).find(|o| &b[*o..*o + 4] == tag).unwrap();
    (at, word(at + 4) as usize, word(at + 8) as usize, word(at + 12))
}

/// The file with rules added (or, without `keep`, with these rules and none of its own), sorted by all eight
/// fields as the format has them: from stop, to stop, from line, to line, from trip, to trip, kind, least time. The
/// section is put anew at the end, the table pointing there.
fn with_rules(b: &[u8], more: &[[u32; 8]], keep: bool) -> Vec<u8> {
    let (at, off, len, _) = table(b, b"TRUL");
    let mut rules: Vec<[u32; 8]> = b[off..off + len].chunks_exact(32).filter(|_| keep).map(|r| core::array::from_fn(|k| u32::from_le_bytes(r[k * 4..k * 4 + 4].try_into().unwrap()))).collect();
    rules.extend(more);
    rules.sort_unstable();
    rules.dedup();
    let mut out = b.to_vec();
    out.resize(out.len().div_ceil(8) * 8, 0);
    let to = out.len() as u32;
    out.extend(rules.iter().flatten().flat_map(|w| w.to_le_bytes()));
    for (k, v) in [to, rules.len() as u32 * 32, rules.len() as u32].into_iter().enumerate() {
        out[at + 4 + k * 4..at + 8 + k * 4].copy_from_slice(&v.to_le_bytes());
    }
    out
}

/// Numbers that are the same on every machine.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }
    fn below(&mut self, n: u32) -> u32 {
        (self.next() >> 33) as u32 % n.max(1)
    }
}

/// Every pair of rules the format can say of the two changes of an island, on both islands at once, in place of
/// the feed's own four. For the first change (A to B on Keten, at A on Slot) a rule from the trip that arrives,
/// from its line, or from the stop alone, to the first trip that leaves, to the second, to their line, or to
/// the stop alone; for the second change (C to D) a rule from either trip of the middle line, from the line or
/// from the stop, to either trip of the last line, to the line or to the stop. Each of every kind: a vehicle waits
/// (1), one stays seated (4), no change is possible (3), a least time (2) of none, of two minutes, of ten. That is
/// 5,940 timetables, each asked ten questions: on each island leaving at 09:45, for the moment and over half an
/// hour, and there by 10:57 likewise, and by 10:40.
///
/// Every journey of every answer can be made (`impossible`), whatever the rules. Where both rules say that a
/// vehicle waits, every answer is the scan's as well: all the ways one waiting vehicle can lead to another, or not
/// (the later trip of the middle line is given only to who could have boarded it). Where a rule bars a change or
/// gives it a least time, an answer may still lack an option the scan has (the later trip of a route, where the
/// rules bar the earlier one: the app maintainer's A7), and that is counted here until it is mended, 1,236 answers;
/// an option the scan has not is never passed.
#[test]
fn every_pair_of_rules_on_the_two_islands() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/keten.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    let stop = |id: String| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    let trip = |id: String| (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap();
    let line = |id: String| tt.route(tt.route_of_trip(trip(id))).line;
    const KINDS: [(u32, u32); 6] = [(1, NONE), (4, NONE), (3, NONE), (2, 0), (2, 120), (2, 600)];
    // a side of a rule: a trip, a line, or the stop alone, as (line, trip)
    let sides = |island: char, n: u32| -> [Vec<(u32, u32)>; 2] {
        let named = |trips: &[&str]| -> Vec<(u32, u32)> {
            let mut all: Vec<(u32, u32)> = trips.iter().map(|t| (NONE, trip(format!("{island}{n}{t}")))).collect();
            all.push((line(format!("{island}{n}{}", trips[0])), NONE));
            all.push((NONE, NONE));
            all
        };
        [named(if n == 1 { &["a"] } else { &["a", "b"] }), {
            let n = n + 1;
            let mut all: Vec<(u32, u32)> = ["a", "b"].iter().map(|t| (NONE, trip(format!("{island}{n}{t}")))).collect();
            all.push((line(format!("{island}{n}a")), NONE));
            all.push((NONE, NONE));
            all
        }]
    };
    // the rules of one change, for both islands: Keten's stops and Slot's, the same choice of sides and kind
    let change = |n: u32| -> Vec<[[u32; 8]; 2]> {
        let stops = |island: char| if n == 1 { (stop(format!("{island}A")), stop(format!("{island}{}", if island == 'Q' { 'B' } else { 'A' }))) } else { (stop(format!("{island}C")), stop(format!("{island}D"))) };
        let (q, r) = (sides('Q', n), sides('R', n));
        let mut all = Vec::new();
        for from in 0..q[0].len() {
            for to in 0..q[1].len() {
                if q[0][from] == (NONE, NONE) && q[1][to] == (NONE, NONE) {
                    continue;
                }
                for (kind, least) in KINDS {
                    let one = |stops: (u32, u32), s: &[Vec<(u32, u32)>; 2]| [stops.0, stops.1, s[0][from].0, s[1][to].0, s[0][from].1, s[1][to].1, kind, least];
                    all.push([one(stops('Q'), &q), one(stops('R'), &r)]);
                }
            }
        }
        all
    };
    let (first, second) = (change(1), change(2));
    assert_eq!((first.len(), second.len()), (66, 90));
    let (mut asked, mut journeys, mut lacking, mut waiting_files) = (0u32, 0u32, 0u32, 0u32);
    for one in first.iter() {
        for two in second.iter() {
            let ruled = with_rules(&b, &[one[0], one[1], two[0], two[1]], false);
            let ruled = Timetable::open(&ruled).unwrap();
            ruled.verify().unwrap();
            let mut planner = Planner::new(ruled).unwrap();
            let scan = Reference::new(&planner, 0, -86_400, 3 * 86_400);
            let both_wait = matches!(one[0][6], 1 | 4) && matches!(two[0][6], 1 | 4);
            waiting_files += both_wait as u32;
            for island in ['Q', 'R'] {
                for (at, by, window) in [(hm(9, 45), false, 0), (hm(9, 45), false, 1800), (hm(10, 57), true, 0), (hm(10, 57), true, 1800), (hm(10, 40), true, 0)] {
                    let mut q = Query::new(Place::Stop(stop(format!("{island}O"))), Place::Stop(stop(format!("{island}Z"))), 0, at);
                    (q.arrive_by, q.window) = (by, window);
                    let a = planner.plan(&q);
                    assert!(!a.cut_short, "{one:?} {two:?} {q:?}");
                    asked += 1;
                    journeys += a.journeys.len() as u32;
                    for j in a.journeys.iter() {
                        assert_eq!(impossible(&planner, &q, j), None, "rules {:?} {:?}, {q:?}: {:?}", one, two, said(&ruled, j));
                    }
                    let differs = if window == 0 { scan.moment_differs(&planner, &q, &a.journeys) } else { scan.window_differs(&planner, &q, &a.journeys) };
                    if both_wait {
                        assert_eq!(differs, None, "rules {:?} {:?}, {q:?}: {:?}", one, two, a.journeys.iter().map(|j| said(&ruled, j)).collect::<Vec<_>>());
                    } else if let Some(d) = differs {
                        // (what the scan has and the planner has not; never the other way)
                        assert!(d.contains("the planner alone has []") || d.starts_with("the scan has leaving"), "rules {:?} {:?}, {q:?}: {d}", one, two);
                        lacking += 1;
                    }
                }
            }
        }
    }
    println!("{asked} questions of 5,940 timetables, {journeys} journeys, each of which can be made; {waiting_files} timetables have two rules that wait, and every answer on those is the scan's; elsewhere {lacking} answers lack an option the scan has");
    // (the count of what is lacking is held too: a search that gives up a later trip it may give shows here)
    assert_eq!((asked, waiting_files, lacking), (59_400, 5_940 * 2 / 6 * 2 / 6, 1_236));
}

/// A rule that says a vehicle waits, and another that says no change is possible, of the same two rides: nothing
/// waits, the bar holds (so the scan reads the two, and `impossible`). On Keten, where Q2a waits for Q1a and the
/// walk from A to B takes longer than either bus of Q2 allows, a bar from Q1a or its line to Q2a or its line
/// leaves no way from O to Z, leaving or arriving. A bar that speaks of the other bus, Q2b, leaves the way as it
/// was.
#[test]
fn a_bar_holds_against_a_vehicle_that_waits() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/keten.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    let trip = |id: &str| (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap();
    let line = |id: &str| tt.route(tt.route_of_trip(trip(id))).line;
    let (q1a, q2a, q2b, q3a) = (trip("Q1a"), trip("Q2a"), trip("Q2b"), trip("Q3a"));
    // Keten's own two rules
    let waits = [[stop("QA"), stop("QB"), NONE, NONE, q1a, q2a, 1, NONE], [stop("QC"), stop("QD"), NONE, NONE, q2b, q3a, 1, NONE]];
    // a bar at the first change, as from line, to line, from trip, to trip; and whether it speaks of Q1a and Q2a
    let bars = [
        ((NONE, NONE, q1a, q2a), true),
        ((line("Q1a"), NONE, NONE, q2a), true),
        ((NONE, line("Q2a"), q1a, NONE), true),
        ((line("Q1a"), line("Q2a"), NONE, NONE), true),
        ((NONE, NONE, q1a, q2b), false),
    ];
    for (bar, holds) in bars {
        let mut rules = waits.to_vec();
        rules.push([stop("QA"), stop("QB"), bar.0, bar.1, bar.2, bar.3, 3, NONE]);
        let ruled = with_rules(&b, &rules, false);
        let ruled = Timetable::open(&ruled).unwrap();
        ruled.verify().unwrap();
        let mut planner = Planner::new(ruled).unwrap();
        let scan = Reference::new(&planner, 0, -86_400, 3 * 86_400);
        for (at, by) in [(hm(9, 45), false), (hm(10, 57), true)] {
            let mut q = Query::new(Place::Stop(stop("QO")), Place::Stop(stop("QZ")), 0, at);
            q.arrive_by = by;
            let a = planner.plan(&q);
            assert!(!a.cut_short);
            for j in a.journeys.iter() {
                assert_eq!(impossible(&planner, &q, j), None, "bar {bar:?}, arriving by: {by}: {:?}", said(&ruled, j));
            }
            assert_eq!(scan.moment_differs(&planner, &q, &a.journeys), None, "bar {bar:?}, arriving by: {by}");
            let got: Vec<(u32, u32, String)> = a.journeys.iter().map(|j| said(&ruled, j)).collect();
            let paper = if holds { vec![] } else { vec![(hm(9, 50), hm(10, 57), "Q1a Q2a Q3b".to_string())] };
            assert_eq!(got, paper, "bar {bar:?}, arriving by: {by}");
        }
    }
    // The same at the second change, under a rule that names lines: line Q3 waits at D for line Q2, so Q3a (10:23)
    // waits for Q2a (in at 10:20) as for Q2b, and from O one is at Z at 10:40. With a bar from Q2a to Q3a it is
    // Q3b again, by the walk: at Z at 10:57, and nobody is there by 10:40 (searching back, Q2a is an arrival Q3a
    // would wait for, the trip before the one first boarded).
    let q3 = [stop("QC"), stop("QD"), line("Q2a"), line("Q3a"), NONE, NONE, 1, NONE];
    let bar = [stop("QC"), stop("QD"), NONE, NONE, q2a, q3a, 3, NONE];
    let (early, late) = ((hm(9, 50), hm(10, 40), "Q1a Q2a Q3a".to_string()), (hm(9, 50), hm(10, 57), "Q1a Q2a Q3b".to_string()));
    for barred in [false, true] {
        let mut rules = vec![waits[0], q3];
        if barred {
            rules.push(bar);
        }
        let ruled = with_rules(&b, &rules, false);
        let ruled = Timetable::open(&ruled).unwrap();
        ruled.verify().unwrap();
        let mut planner = Planner::new(ruled).unwrap();
        let scan = Reference::new(&planner, 0, -86_400, 3 * 86_400);
        let cases = [
            (hm(9, 45), false, if barred { vec![late.clone()] } else { vec![early.clone()] }),
            (hm(10, 40), true, if barred { vec![] } else { vec![early.clone()] }),
            (hm(10, 57), true, if barred { vec![late.clone()] } else { vec![early.clone()] }),
        ];
        for (at, by, paper) in cases {
            let mut q = Query::new(Place::Stop(stop("QO")), Place::Stop(stop("QZ")), 0, at);
            q.arrive_by = by;
            let a = planner.plan(&q);
            assert!(!a.cut_short);
            for j in a.journeys.iter() {
                assert_eq!(impossible(&planner, &q, j), None, "a line that waits, barred: {barred}, {at}, arriving by: {by}: {:?}", said(&ruled, j));
            }
            assert_eq!(scan.moment_differs(&planner, &q, &a.journeys), None, "a line that waits, barred: {barred}, {at}, arriving by: {by}");
            let got: Vec<(u32, u32, String)> = a.journeys.iter().map(|j| said(&ruled, j)).collect();
            assert_eq!(got, paper, "a line that waits, barred: {barred}, {at}, arriving by: {by}");
        }
    }
}

/// A later trip under a rule that lengthens the change is asked at its own time, not at the earlier trip's. On
/// Slot, with a least time from R1a (in at A at 10:00) to R2b (which leaves A at 10:07) in place of the bar: R2a at
/// 10:05 is under no rule and is the first trip caught; R2b is the bus R3a waits for. With 400 s asked, R2b is
/// seven minutes after R1a and may be boarded: at Z at 10:40 by R3a. With 480 s it may not, and it is R2a, the walk
/// and R3b, at Z at 10:57. (At 10:05, when R2a is boarded, 400 s have not passed either.) Asked leaving, and to be
/// there by 10:40 and by 10:57; the last of those with 480 s is held as it is today, named below.
#[test]
fn a_later_trip_is_asked_at_its_own_time() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/keten.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    let trip = |id: &str| (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap();
    let (early, late) = ((hm(9, 50), hm(10, 40), "R1a R2b R3a".to_string()), (hm(9, 50), hm(10, 57), "R1a R2a R3b".to_string()));
    for (least, paper) in [(400, [vec![early.clone()], vec![early.clone()], vec![early.clone()]]), (480, [vec![late.clone()], vec![], vec![late.clone()]])] {
        let rules = [[stop("RA"), stop("RA"), NONE, NONE, trip("R1a"), trip("R2b"), 2, least], [stop("RC"), stop("RD"), NONE, NONE, trip("R2b"), trip("R3a"), 1, NONE]];
        let ruled = with_rules(&b, &rules, false);
        let ruled = Timetable::open(&ruled).unwrap();
        ruled.verify().unwrap();
        let mut planner = Planner::new(ruled).unwrap();
        let scan = Reference::new(&planner, 0, -86_400, 3 * 86_400);
        for ((at, by), paper) in [(hm(9, 45), false), (hm(10, 40), true), (hm(10, 57), true)].into_iter().zip(paper) {
            let mut q = Query::new(Place::Stop(stop("RO")), Place::Stop(stop("RZ")), 0, at);
            q.arrive_by = by;
            let a = planner.plan(&q);
            assert!(!a.cut_short);
            for j in a.journeys.iter() {
                assert_eq!(impossible(&planner, &q, j), None, "least {least}, {at}, arriving by: {by}: {:?}", said(&ruled, j));
            }
            let differs = scan.moment_differs(&planner, &q, &a.journeys);
            let got: Vec<(u32, u32, String)> = a.journeys.iter().map(|j| said(&ruled, j)).collect();
            if least == 480 && at == hm(10, 57) {
                // Held as it is, like `SLOT_BACK` and for the same reason (the app maintainer's A7): searching
                // back, the first trip caught is R2b, which the rule keeps those off R1a from, and R2a before it
                // is never ridden. The scan has the answer on paper; the planner has none.
                assert!(got.is_empty() && differs.is_some(), "least {least}, there by 10:57: said {got:?}, the scan: {differs:?}. If this is the answer on paper now ({paper:?}), hold it as the others");
                continue;
            }
            assert_eq!(differs, None, "least {least}, {at}, arriving by: {by}");
            assert_eq!(got, paper, "least {least}, {at}, arriving by: {by}");
        }
    }
}

/// What is kept along a route keeps to the wishes as those who ride do. On Wissel from N, those on W4a board W2b
/// at P behind those on W2a, and W2b is held at S for W3a though nobody rides it there; on Terug to Q, searched
/// back, the same of T2a between P and B. Each line passes a stop M on that stretch. With a wish never to ride
/// through M, nobody is on either bus of the line past M, and what was kept is not either: on Terug there is no
/// way at all, and on Wissel the way is W6a to X and W2a from there, which those off W6a board and no later bus
/// (at Z at 11:02 by W3b). Where riding through M costs five minutes the way is the first one and costs that;
/// and a wish about a stop of another island changes nothing.
#[test]
fn what_is_kept_along_a_route_keeps_to_the_wishes() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/keten.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt).unwrap();
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    let scan = Reference::new(&p, 0, -86_400, 3 * 86_400);
    let ways = [
        ("WN", "WZ", hm(9, 35), false, "WM", (hm(9, 40), hm(10, 41), "W0a W4a W2b W3a".to_string()), vec![(hm(9, 40), hm(11, 2), "W0a W6a W2a W3b".to_string())]),
        ("TO", "TQ", hm(10, 55), true, "TM", (hm(9, 50), hm(10, 55), "T1a T2a T4a T5a".to_string()), vec![]),
    ];
    for (from, to, at, by, m, way, never) in ways {
        for (at_stop, how, paper, penalty) in [(m, How::Never, never, 0), (m, How::Avoid(300), vec![way.clone()], 300), ("QO", How::Never, vec![way.clone()], 0)] {
            let mut q = Query::new(Place::Stop(stop(from)), Place::Stop(stop(to)), 0, at);
            q.arrive_by = by;
            q.wishes.push(Wish { what: What::Stop { stop: stop(at_stop), through: true }, how });
            let a = p.plan(&q);
            assert!(!a.cut_short, "{from} to {to}, {at_stop} {how:?}");
            for j in a.journeys.iter() {
                assert_eq!(impossible(&p, &q, j), None, "{from} to {to}, {at_stop} {how:?}: {:?}", said(&tt, j));
            }
            assert_eq!(scan.moment_differs(&p, &q, &a.journeys), None, "{from} to {to}, {at_stop} {how:?}");
            let got: Vec<(u32, u32, String)> = a.journeys.iter().map(|j| said(&tt, j)).collect();
            assert_eq!(got, paper, "{from} to {to}, {at_stop} {how:?}");
            assert!(a.journeys.iter().all(|j| j.penalty == penalty), "{from} to {to}, {at_stop} {how:?}: {:?}", a.journeys.iter().map(|j| j.penalty).collect::<Vec<_>>());
        }
    }
}

/// Two changes in a row, each under a rule of the feed, put into a piece of the real timetable (the city of
/// Utrecht): 40 chains a file, eight files. A chain is a journey the file without rules gives, of three rides or
/// more: its first three, A, B and C. For the change from A to B a rule, and one for the change from B to C, each of
/// any kind the format has (a vehicle waits: 1 and 4; a least time: 2; no change possible: 3), naming on either
/// side the trip, its line, or nothing but the stop. And each speaks of the trip the journey takes, of the one of
/// its route before it (which leaves once the ride before is in, and too soon for the walk: only a vehicle that
/// waits is caught so), or of the one after: as on the first two islands, where what waits waits for another, and what
/// is barred is the one that would be taken.
///
/// Each chain's question is asked again of the file with the rules, leaving and arriving, for one moment and over
/// half an hour: some thousands of questions. Every journey of every answer can be made (`impossible`), and every
/// answer is the scan's, which reads the rules pair of rides by pair and knows nothing of how the search holds
/// what waits. With the rules off the answers are those of the file without them, its own rules off too.
#[test]
fn rules_at_two_changes_in_a_row() {
    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/utrecht-20261117.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    let mut plain = Planner::new(tt).unwrap();
    let mut rng = Rng(0x2545_F491_4F6C_DD1D);
    // the trips of a route that run on `day`, as the moment each leaves its place `pos`
    let leaving = |r: u32, pos: u32, day: i32| {
        let route = tt.route(r);
        let mut all: Vec<(i32, u32)> = (route.first_trip..route.first_trip + route.trips).filter(|t| tt.runs(tt.trip(*t).service, day)).map(|t| (tt.time(tt.trip_times(t), pos).1 as i32, t)).collect();
        all.sort_unstable();
        all
    };
    let walk = |a: u32, z: u32| {
        let (lo, hi) = tt.walks_from(a);
        (lo..hi).map(|i| tt.walk(i)).find(|w| w.0 == z).map_or(if a == z { tt.change_seconds() as i32 } else { i32::MAX }, |w| w.1 as i32)
    };
    // the first three rides of a journey: trip, route, where it is boarded and left, from, to, when
    let rides = |j: &Journey, day: i32| -> Option<Vec<(u32, u32, u32, u32, u32, u32, i32, i32)>> {
        let all: Vec<_> = j.legs.iter().filter_map(|l| if let Leg::Ride { trip, route, day: d, board, alight, from, to, depart, arrive } = l { Some((*trip, *route, *board, *alight, *from, *to, *depart, *arrive, *d)) } else { None }).collect();
        (all.len() >= 3 && all[..3].iter().all(|r| r.8 == day)).then(|| all[..3].iter().map(|r| (r.0, r.1, r.2, r.3, r.4, r.5, r.6, r.7)).collect())
    };
    // how often a journey makes a change between those two stops in less time than the file's walk (or the stop's
    // own time) takes: by a vehicle that waits
    let by_rule = |j: &Journey, c: (u32, u32)| {
        let all: Vec<(u32, u32, i32, i32)> = j.legs.iter().filter_map(|l| if let Leg::Ride { from, to, depart, arrive, .. } = l { Some((*from, *to, *depart, *arrive)) } else { None }).collect();
        all.windows(2).any(|w| (w[0].1, w[1].0) == c && w[1].2 - w[0].3 < walk(c.0, c.1))
    };
    let (mut asked, mut cut, mut differ, mut waited, mut both, mut off) = (0u32, 0u32, 0u32, 0u32, 0u32, 0u32);
    let mut kinds = [0u32; 5];
    for file in 0..8 {
        // the chains: each as its question and its two changes
        let mut rules: Vec<[u32; 8]> = Vec::new();
        let mut chains: Vec<(Query, i32, [(u32, u32); 2])> = Vec::new();
        while chains.len() < 40 {
            let (from, to) = (rng.below(tt.stops()), rng.below(tt.stops()));
            let q = Query::new(Place::Stop(from), Place::Stop(to), rng.below(2) as i32, 6 * 3600 + rng.below(16 * 3600));
            let a = plain.plan(&q);
            let Some((j, r)) = a.journeys.iter().find_map(|j| rides(j, q.day).map(|r| (j, r))) else { continue };
            // a rule for the change from ride `one` to ride `two`: of the trip taken, the one before or the one after
            let mut rule = |rng: &mut Rng, one: (u32, u32, u32, u32, u32, u32, i32, i32), two: (u32, u32, u32, u32, u32, u32, i32, i32)| {
                let (line_1, line_2) = (tt.route(one.1).line, tt.route(two.1).line);
                let variant = |rng: &mut Rng, ride: (u32, u32, u32, u32, u32, u32, i32, i32), since: i32| {
                    let all = leaving(ride.1, ride.2, q.day);
                    let at = all.iter().position(|t| t.1 == ride.0)?;
                    match rng.below(4) {
                        // (the one before, where it leaves once the other ride is in)
                        0 | 1 => at.checked_sub(1).map(|i| all[i]).filter(|t| t.0 >= since).or(Some(all[at])).map(|t| t.1),
                        2 => Some(ride.0),
                        _ => all.get(at + 1).map(|t| t.1),
                    }
                };
                let (trip_1, trip_2) = (variant(rng, one, i32::MIN).unwrap_or(one.0), variant(rng, two, one.7).unwrap_or(two.0));
                let (name_1, name_2) = loop {
                    let names = (rng.below(3), rng.below(3));
                    if names != (2, 2) {
                        break names;
                    }
                };
                let pick = |name: u32, trip: u32, line: u32| match name {
                    0 => (NONE, trip),
                    1 => (line, NONE),
                    _ => (NONE, NONE),
                };
                let ((l1, t1), (l2, t2)) = (pick(name_1, trip_1, line_1), pick(name_2, trip_2, line_2));
                let kind = [1, 1, 1, 1, 4, 2, 2, 3, 3][rng.below(9) as usize];
                let least = if kind == 2 { [0, 60, 240, 900][rng.below(4) as usize] } else { NONE };
                kinds[kind as usize] += 1;
                rules.push([one.5, two.4, l1, l2, t1, t2, kind, least]);
            };
            if chains.len() % 2 == 0 {
                rule(&mut rng, r[0], r[1]);
                rule(&mut rng, r[1], r[2]);
            } else {
                // Every other chain is the islands' own shape: two vehicles that wait. The first rule holds the
                // trip of B's route that leaves first once A is in (too soon for the walk, where there is one).
                // The second speaks from that trip, or from the one after it, to the trip of C's route that leaves
                // first once it is in, or to the one after that.
                let (b_all, c_all) = (leaving(r[1].1, r[1].2, q.day), leaving(r[2].1, r[2].2, q.day));
                let Some(b_at) = b_all.iter().position(|t| t.0 >= r[0].7) else { continue };
                let from_b = (b_at + (rng.below(4) == 0) as usize).min(b_all.len() - 1);
                let in_at = b_all[from_b].0 + (tt.time(tt.trip_times(b_all[from_b].1), r[1].3).0 as i32 - tt.time(tt.trip_times(b_all[from_b].1), r[1].2).1 as i32);
                let Some(c_at) = c_all.iter().position(|t| t.0 >= in_at) else { continue };
                let to_c = (c_at + (rng.below(4) == 0) as usize).min(c_all.len() - 1);
                let (line_a, line_b, line_c) = (tt.route(r[0].1).line, tt.route(r[1].1).line, tt.route(r[2].1).line);
                let name = |rng: &mut Rng, trip: u32, line: u32| if rng.below(3) == 0 { (line, NONE) } else { (NONE, trip) };
                let ((l1, t1), (l2, t2)) = (name(&mut rng, r[0].0, line_a), name(&mut rng, b_all[b_at].1, line_b));
                rules.push([r[0].5, r[1].4, l1, l2, t1, t2, 1, NONE]);
                let ((l1, t1), (l2, t2)) = (name(&mut rng, b_all[from_b].1, line_b), name(&mut rng, c_all[to_c].1, line_c));
                rules.push([r[1].5, r[2].4, l1, l2, t1, t2, 1, NONE]);
                kinds[1] += 2;
            }
            chains.push((q, j.arrive, [(r[0].5, r[1].4), (r[1].5, r[2].4)]));
        }
        let ruled = with_rules(&b, &rules, true);
        let ruled = Timetable::open(&ruled).unwrap();
        ruled.verify().unwrap();
        let mut planner = Planner::new(ruled).unwrap();
        let scans: Vec<Reference> = (0..2).map(|day| Reference::new(&planner, day, -86_400, 3 * 86_400)).collect();
        for (n, (first, arrive, changes)) in chains.iter().enumerate() {
            for ask in 0..4 {
                let mut q = first.clone();
                (q.arrive_by, q.window) = (ask % 2 == 1, if ask < 2 { 0 } else { 1800 });
                // leaving as the chain's question does; there a minute after its journey is (over a window, by then or half an hour before)
                if q.arrive_by {
                    q.time = (arrive + 60).max(0) as u32;
                }
                let (a, bare) = (planner.plan(&q), plain.plan(&q));
                asked += 1;
                if a.cut_short || bare.cut_short {
                    cut += 1;
                    continue;
                }
                for j in a.journeys.iter() {
                    assert_eq!(impossible(&planner, &q, j), None, "file {file}, chain {n}: {q:?}\n{j:?}");
                }
                let differs = if q.window == 0 { scans[q.day as usize].moment_differs(&planner, &q, &a.journeys) } else { scans[q.day as usize].window_differs(&planner, &q, &a.journeys) };
                assert_eq!(differs, None, "file {file}, chain {n}: {q:?}");
                differ += (a.journeys != bare.journeys) as u32;
                waited += a.journeys.iter().any(|j| by_rule(j, changes[0]) || by_rule(j, changes[1])) as u32;
                both += a.journeys.iter().any(|j| by_rule(j, changes[0]) && by_rule(j, changes[1])) as u32;
                // and with the rules off, what the file without them says
                if ask == n % 4 {
                    q.rules = false;
                    // (the city's file has rules of its own: off for both)
                    let (without, bare) = (planner.plan(&q), plain.plan(&q));
                    assert!(without.cut_short || bare.cut_short || without.journeys == bare.journeys, "file {file}, chain {n}, the rules off: {q:?}");
                    off += 1;
                }
            }
        }
    }
    println!("{asked} questions of 320 chains ({cut} cut short and passed over): the rules change {differ} answers; in {waited} a change of the chain is made by a vehicle that waits, in {both} both changes; with the rules off {off} more; rules that wait {}, with a least time {}, that bar {}", kinds[1] + kinds[4], kinds[2], kinds[3]);
    assert!(asked == 1280 && cut < 13 && differ >= 400 && waited >= 40 && both >= 2, "{asked} {cut} {differ} {waited} {both}");
}
