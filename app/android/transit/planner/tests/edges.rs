//! The planner at the edges a review named (the reviewer's note of 18 November 2026, finding 13), on a second feed
//! written by hand: tests/data/edges, packed to tests/data/edges.tt by ../pack/ttpack.py. Each case is an island of
//! stops with its own lines, worked out on paper. Friday 26 March 2027 is day 0; the clock goes forward in the night
//! of Saturday 27 to Sunday 28.
//!
//! - Laat: the direct bus A to Z takes 20 minutes and leaves 22:10 and 22:40 (and 06:00). The last way that evening
//!   is the 23:40 bus to B and the night bus from there at 01:10, in at 01:25.
//! - Minder: A to M 10:00 to 10:10, M to Z 10:15 to 10:25; and a slow direct bus A to Z, 10:00 to 11:40.
//! - Vertrek: the bus A to S leaves 08:02 and 08:22 (18 minutes), the one train S to Z 08:50, in at 09:10.
//! - Aankomst: the one train A to S 08:00 to 08:20, the bus S to Z 08:30 and 08:50 (15 minutes).
//! - Wacht: the bus A to B is in at 08:10. The bus C to Z leaves 08:12 and waits for it (the feed's rule); B and C
//!   are 685 m apart and no walk joins them.
//! - Voet: the same, with B and C 300 m apart (a walk of 370 s), a second bus from C at 08:20, and a direct bus
//!   08:01 to 08:32.
//! - Overstap: A to S is in at 08:10; S to Z leaves 08:12 and 08:16. The feed gives S its own time to change: 300 s.
//! - Minstens: the same times, 08:12 and 08:20; the feed wants 300 s from that bus to the 08:12 one.
//! - Klok: on Saturday's service a night bus A to B, 28:20 to 28:30 (04:30 on Sunday by the old clock, 05:30 by the
//!   new). On Sunday's service B to Z leaves 05:10 and 05:40.
//! - Lopen: A and Z are 1.5 km apart, 26 minutes on foot by the rule for points. A bus A to Z leaves 08:05 and takes
//!   10 minutes; another, round about, leaves 08:10 and takes 33.
//!
//! And the feed's rules between two different stops, each named by a trip or a line (no real feed at hand has one of
//! these kinds):
//! - Tijd: B and C are 202 m apart, a walk of 271 s. A to B is in at 08:10 and at 09:10; C to Z leaves 08:16 and
//!   08:25, 09:13 and 09:15 (14 minutes). The feed wants 480 s from the first bus to the 08:16, and 120 s from the
//!   second to the 09:13: less than the walk, which still has to be made.
//! - Zit: A to B is in at 08:10, and the same vehicle goes on from C at 08:10 as another trip: one stays seated (a
//!   rule of type 4); B and C are 680 m apart and no walk joins them. A direct bus leaves 08:02, in at 08:40.
//! - Lijn: A to B is in at 08:00, 08:10 and 08:30. The buses of line N2 leave C at 08:11, 08:41 and 09:30 (14
//!   minutes) and wait for line N1 (a rule that names the two lines, no trip); a faster bus of another line leaves C
//!   at 08:11 too, which no rule joins to anything. B and C are 680 m apart and no walk joins them.
//!
//! A rule that says a line waits holds one vehicle for each arrival: the first of that line to leave at or after
//! it. Every other is reached as without the rule, or not at all:
//! - Gat: A to B is in at 10:00 and at 10:10. The buses of line G2 leave C at 10:01, 10:03 and 10:15 (14 minutes)
//!   and wait for line G1. B and C are 302 m apart, a walk of 375 s: longer than the gap between the first two.
//! - Dicht: A to B is in at 10:00; line D2 leaves C at 10:01 and 10:13 and waits for line D1. B and C are 201 m
//!   apart, a walk of 270 s: the 10:13 is made on foot.
//! - Halte: A to S is in at 10:00; line H2 leaves S, the same stop, at 10:01, 10:03 and 10:15 and waits for line H1.
//!
//! And two service days of one route:
//! - Ochtend: the bus A to Z (20 minutes) leaves at 23:00 on Friday's service and at 06:00 on Saturday's, and at no
//!   other time.

use atlas_transit::file::{StopText, TripText};
use atlas_transit::reference::{Reference, impossible};
use atlas_transit::{How, Journey, Leg, Mode, Place, Planner, Query, Spot, Timetable, What, Wish};

fn bytes() -> Vec<u8> {
    std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/edges.tt")).unwrap()
}

fn hm(h: u32, m: u32) -> u32 {
    h * 3600 + m * 60
}

fn query(tt: &Timetable, from: &str, to: &str, day: i32, time: u32) -> Query {
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    Query::new(Place::Stop(stop(from)), Place::Stop(stop(to)), day, time)
}

fn ask(planner: &mut Planner, q: &Query) -> Vec<Journey> {
    let a = planner.plan(q);
    assert!(!a.cut_short);
    for j in a.journeys.iter() {
        assert_eq!(impossible(planner, q, j), None, "{j:?}");
    }
    held_to_the_scan(planner, q, &a.journeys);
    a.journeys
}

/// The connection scan's word on an answer (src/reference.rs: it holds to the feed's rules in its own way, and knows
/// no cap on rides and no on-foot journey): the earliest arrival, the latest departure, or a window's best pairs.
fn held_to_the_scan(planner: &Planner, q: &Query, journeys: &[Journey]) {
    if q.max_rides < 8 || q.day < 0 || journeys.iter().any(|j| j.rides == 0) {
        return;
    }
    let scan = Reference::new(planner, q.day, q.time as i32 - 86_400, q.time as i32 + 2 * 86_400);
    if q.wishes.iter().any(|w| !matches!(w.how, How::Never)) {
        // wishes that cost: the scan that carries a cost, which judges an answer for one moment
        assert_eq!(scan.costs_differ(planner, q, journeys), None, "{q:?}");
        return;
    }
    match (q.window, q.arrive_by) {
        (0, false) => assert_eq!(journeys.iter().map(|j| j.arrive).min(), scan.earliest_arrival(planner, q, q.time as i32), "{q:?}"),
        (0, true) => assert_eq!(journeys.iter().map(|j| j.depart).max(), scan.latest_departure(planner, q, q.time as i32), "{q:?}"),
        _ => assert_eq!(scan.window_differs(planner, q, journeys), None, "{q:?}"),
    }
}

/// A journey as its times and its trips' ids.
fn said(tt: &Timetable, j: &Journey) -> (u32, u32, String) {
    let trips: Vec<&str> = j.legs.iter().filter_map(|l| if let Leg::Ride { trip, .. } = l { Some(tt.trip_text(*trip, TripText::Id)) } else { None }).collect();
    (j.depart as u32, j.arrive as u32, trips.join(" "))
}

fn all(tt: &Timetable, js: &[Journey]) -> Vec<(u32, u32, String)> {
    js.iter().map(|j| said(tt, j)).collect()
}

#[test]
fn the_file_is_sound() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    tt.verify().unwrap();
    assert_eq!((tt.first_day(), tt.days(), tt.stops(), tt.trips(), tt.walks(), tt.rules()), (20270326, 4, 56, 61, 8, 11));
}

/// A window late in the evening has its last connection, though it takes far longer than the journey's least and
/// ends past where a patient search stops for the faster ones.
#[test]
fn a_late_window_has_its_last_connection() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let mut q = query(&tt, "LA", "LZ", 0, hm(22, 0));
    q.window = 7200;
    let want = [(hm(22, 10), hm(22, 30), "L1a".to_string()), (hm(22, 40), hm(23, 0), "L1b".to_string()), (hm(23, 40), hm(25, 25), "L2a L3a".to_string())];
    assert_eq!(all(&tt, &ask(&mut p, &q)), want);
    // though the direct buses were followed no further than they could sensibly take
    assert!(p.plan(&q).horizon < q.limits.horizon);
    // and it is what a search as far as the horizon gives
    q.limits.patient = false;
    assert_eq!(all(&tt, &ask(&mut p, &q)), want);
    assert_eq!(p.plan(&q).horizon, q.limits.horizon);
    // one moment after the last direct bus: the same way (which was always found), and the morning's direct bus
    let mut q = query(&tt, "LA", "LZ", 0, hm(23, 30));
    let morning = (hm(30, 0), hm(30, 20), "L1c".to_string());
    assert_eq!(all(&tt, &ask(&mut p, &q)), [want[2].clone(), morning]);
    // arriving by: the window's earliest arrivals, searched back, are the same case turned round
    q = query(&tt, "LA", "LZ", 0, hm(25, 30));
    (q.arrive_by, q.window) = (true, 3 * 3600);
    assert_eq!(all(&tt, &ask(&mut p, &q)), want);
}

/// A patient search has the slow way with fewer rides too. From A at 09:55 the way with a change is in at 10:25,
/// and the search follows journeys until 11:05 (twice the 20 minutes any timetable needs, and half an hour). The
/// direct bus, one ride fewer, is in at 11:40: it is followed as far as the query's horizon all the same.
#[test]
fn a_patient_search_has_the_way_with_fewer_rides() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (fast, direct) = ((hm(10, 0), hm(10, 25), "F1a F2a".to_string()), (hm(10, 0), hm(11, 40), "F3a".to_string()));
    for window in [0, 600] {
        for patient in [true, false] {
            let mut q = query(&tt, "FA", "FZ", 0, hm(9, 55));
            (q.window, q.limits.patient) = (window, patient);
            assert_eq!(all(&tt, &ask(&mut p, &q)), [fast.clone(), direct.clone()], "window {window} patient {patient}");
            assert_eq!(p.plan(&q).horizon, if patient { 2 * 1200 + 1800 } else { q.limits.horizon });
            // with one ride allowed there is only the direct bus, found past where the others stop
            q.max_rides = 1;
            assert_eq!(all(&tt, &ask(&mut p, &q)), [direct.clone()], "window {window} patient {patient}");
            // and rail avoided at a price (both ways are by bus): the same two
            q.max_rides = 8;
            q.wishes.push(Wish { what: What::Mode(Mode::Rail), how: How::Avoid(600) });
            assert_eq!(all(&tt, &ask(&mut p, &q)), [fast.clone(), direct.clone()], "window {window} patient {patient}, wished");
        }
    }
    // arriving by 11:45, searched back: both leave at 10:00, so the one with fewer rides is the option; by 10:30
    // the direct bus is none
    let mut q = query(&tt, "FA", "FZ", 0, hm(11, 45));
    q.arrive_by = true;
    assert_eq!(all(&tt, &ask(&mut p, &q)), [direct.clone()]);
    q.time = hm(10, 30);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [fast.clone()]);
}

/// Asked to leave at 08:00, the answer leaves when it must: the 08:22 bus makes the same train as the 08:02.
#[test]
fn a_departure_is_the_latest_that_makes_the_arrival() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let q = query(&tt, "BA", "BZ", 0, hm(8, 0));
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 22), hm(9, 10), "B1b B2a".to_string())]);
    // and asked to arrive by 09:10, it arrives when it can: the 08:30 bus is there twenty minutes before the 08:50
    let mut q = query(&tt, "RA", "RZ", 0, hm(9, 10));
    q.arrive_by = true;
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 0), hm(8, 45), "R1a R2a".to_string())]);
}

/// A window ends with its last moment, and what leaves after it still counts against what leaves inside: the 08:02
/// bus is no option in a window that ends at 08:02, since the 08:22 makes the same train. So two windows one
/// after the other give what one window over both gives.
#[test]
fn a_window_ends_with_its_last_moment() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let later = (hm(8, 22), hm(9, 10), "B1b B2a".to_string());
    let mut q = query(&tt, "BA", "BZ", 0, hm(7, 30));
    for (window, want) in [(31 * 60, vec![]), (32 * 60, vec![]), (51 * 60, vec![]), (52 * 60, vec![later.clone()]), (3600, vec![later.clone()])] {
        q.window = window;
        assert_eq!(all(&tt, &ask(&mut p, &q)), want, "a window of {} minutes", window / 60);
    }
    // from 08:02 itself, and from a second later
    (q.time, q.window) = (hm(8, 2), 0);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [later.clone()]);
    (q.time, q.window) = (hm(8, 2) + 1, 20 * 60 - 1);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [later.clone()]);
    // arriving by, the same turned round: in by 09:05 with the 08:50 bus is no option where the 08:30 bus is in
    // at 08:45, before the window
    let sooner = (hm(8, 0), hm(8, 45), "R1a R2a".to_string());
    let mut q = query(&tt, "RA", "RZ", 0, hm(9, 30));
    q.arrive_by = true;
    for (window, want) in [(24 * 60, vec![]), (25 * 60, vec![]), (44 * 60, vec![]), (45 * 60, vec![sooner.clone()]), (3600, vec![sooner.clone()])] {
        q.window = window;
        assert_eq!(all(&tt, &ask(&mut p, &q)), want, "arriving in a window of {} minutes", window / 60);
    }
}

/// A vehicle that waits is boarded whatever the walk takes: also where no walk leads there, and where the walk is
/// longer than the time there is. The search aimed at its end finds what the search to the horizon finds.
#[test]
fn a_vehicle_that_waits_is_reached_by_an_aimed_search() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    for (from, to, want) in [
        ("WA", "WZ", vec![(hm(8, 0), hm(8, 30), "W1a W2a".to_string())]),
        ("VA", "VZ", vec![(hm(8, 0), hm(8, 30), "V1a V2a".to_string()), (hm(8, 1), hm(8, 32), "V3a".to_string())]),
    ] {
        for patient in [false, true] {
            for window in [0, 600] {
                let mut q = query(&tt, from, to, 0, hm(7, 55));
                (q.limits.patient, q.window) = (patient, window);
                assert_eq!(all(&tt, &ask(&mut p, &q)), want, "{from} patient {patient} window {window}");
            }
        }
        // and back from the arrival
        let mut q = query(&tt, from, to, 0, hm(8, 30));
        q.arrive_by = true;
        assert_eq!(all(&tt, &ask(&mut p, &q)), want[..1], "{from} arriving by");
    }
    // the walk to the vehicle that waits takes the two minutes there are
    let q = query(&tt, "WA", "WZ", 0, hm(7, 55));
    let j = &ask(&mut p, &q)[0];
    assert!(matches!(j.legs[1], Leg::Walk { from: Spot::Stop(_), to: Spot::Stop(_), depart, arrive } if (depart as u32, arrive as u32) == (hm(8, 10), hm(8, 12))), "{j:?}");
    // without the feed's rules there is no way at Wacht, and at Voet the walk makes the later bus
    let mut q = query(&tt, "WA", "WZ", 0, hm(7, 55));
    q.rules = false;
    assert_eq!(ask(&mut p, &q), []);
    let mut q = query(&tt, "VA", "VZ", 0, hm(7, 55));
    q.rules = false;
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 1), hm(8, 32), "V3a".to_string())]);
}

/// The time a stop needs for a change, where the feed gives the stop its own (transfers.txt, from a stop to itself).
#[test]
fn a_stop_has_its_own_time_to_change() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let mut q = query(&tt, "SA", "SZ", 0, hm(7, 55));
    // 08:10 in, 300 s to change: not the 08:12, the 08:16
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 0), hm(8, 24), "S1a S2b".to_string())]);
    q.arrive_by = true;
    q.time = hm(8, 24);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 0), hm(8, 24), "S1a S2b".to_string())]);
    q.time = hm(8, 20);
    assert_eq!(ask(&mut p, &q), []);
    // the asker's own time counts where it is more
    let mut q = query(&tt, "SA", "SZ", 0, hm(7, 55));
    q.change_s = Some(30);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 0), hm(8, 24), "S1a S2b".to_string())]);
    q.change_s = Some(400);
    assert_eq!(ask(&mut p, &q), []);
    // a least time the feed sets from one trip to another
    let q = query(&tt, "MA", "MZ", 0, hm(7, 55));
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 0), hm(8, 28), "M1a M2b".to_string())]);
    let mut q = query(&tt, "MA", "MZ", 0, hm(7, 55));
    q.rules = false;
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(hm(8, 0), hm(8, 20), "M1a M2a".to_string())]);
}

/// The feed's rules between two different stops: the least time a change needs, a change in which one stays
/// seated, and a line that waits for another.
#[test]
fn the_feeds_rules_between_two_stops() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    let trip = |id: &str| (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap();
    let walk = |a: &str, z: &str| {
        let (lo, hi) = tt.walks_from(stop(a));
        (lo..hi).map(|i| tt.walk(i)).find(|w| w.0 == stop(z)).map(|w| w.1)
    };
    assert_eq!((walk("TB", "TC"), walk("ZB", "ZC"), walk("NB", "NC")), (Some(271), None, None));
    let one = |from: u32, to: u32, trips: &str| vec![(from, to, trips.to_string())];
    // every way of asking for one answer: patiently and not, over ten minutes, and back from the arrival
    let every_way = |p: &mut Planner, from: &str, to: &str, at: u32, want: &[(u32, u32, String)], by: (u32, &[(u32, u32, String)])| {
        for patient in [false, true] {
            for window in [0, 600] {
                let mut q = query(&tt, from, to, 0, at);
                (q.limits.patient, q.window) = (patient, window);
                assert_eq!(all(&tt, &ask(p, &q)), want, "{from} at {at} patient {patient} window {window}");
            }
            let mut q = query(&tt, from, to, 0, by.0);
            (q.limits.patient, q.arrive_by) = (patient, true);
            assert_eq!(all(&tt, &ask(p, &q)), by.1, "{from} arriving by {} patient {patient}", by.0);
        }
    };
    let without = |p: &mut Planner, from: &str, to: &str, at: u32| {
        let mut q = query(&tt, from, to, 0, at);
        q.rules = false;
        all(&tt, &ask(p, &q))
    };

    // Tijd. 08:10 in, 271 s on foot: the 08:16 would be made, but the feed wants 480 s to it. The 08:25 then.
    let slow = one(hm(8, 0), hm(8, 39), "T1a T2b");
    every_way(&mut p, "TA", "TZ", hm(7, 55), &slow, (hm(8, 39), &slow));
    every_way(&mut p, "TA", "TZ", hm(7, 55), &slow, (hm(8, 38), &[]));
    assert_eq!(without(&mut p, "TA", "TZ", hm(7, 55)), one(hm(8, 0), hm(8, 30), "T1a T2a"));
    // 09:10 in: the feed's 120 s to the 09:13 are less than the walk, which has to be made all the same: the 09:15
    let later = one(hm(9, 0), hm(9, 29), "T1b T2d");
    every_way(&mut p, "TA", "TZ", hm(8, 55), &later, (hm(9, 29), &later));
    assert_eq!(without(&mut p, "TA", "TZ", hm(8, 55)), later);

    // Zit. One stays seated from the bus that is in at 08:10 into the one that leaves another stop at 08:10; the
    // direct bus has a ride fewer, and is the one that leaves latest for its own arrival.
    let (seated, direct) = (one(hm(8, 0), hm(8, 25), "Z1a Z2a"), one(hm(8, 2), hm(8, 40), "Z3a"));
    every_way(&mut p, "ZA", "ZZ", hm(7, 55), &[seated.clone(), direct.clone()].concat(), (hm(8, 25), &seated));
    every_way(&mut p, "ZA", "ZZ", hm(7, 55), &[seated.clone(), direct.clone()].concat(), (hm(8, 40), &direct));
    assert_eq!(without(&mut p, "ZA", "ZZ", hm(7, 55)), direct);
    let q = query(&tt, "ZA", "ZZ", 0, hm(7, 55));
    let j = &ask(&mut p, &q)[0];
    assert!(matches!(j.legs[1], Leg::Walk { from: Spot::Stop(_), to: Spot::Stop(_), depart, arrive } if (depart as u32, arrive as u32) == (hm(8, 10), hm(8, 10))), "{j:?}");

    // Lijn. The 08:11 of line N2 waits; the faster 08:11 of the other line does not, and nothing leads to it.
    let waits = one(hm(8, 0), hm(8, 25), "N1a N2a");
    every_way(&mut p, "NA", "NZ", hm(7, 55), &waits, (hm(8, 25), &waits));
    every_way(&mut p, "NA", "NZ", hm(7, 55), &waits, (hm(8, 24), &[]));
    assert_eq!(without(&mut p, "NA", "NZ", hm(7, 55)), []);
    // The 08:11 is the one vehicle that waits for the 08:10, and no walk leads to C: where a wish closes it, or it
    // costs more than the half hour is worth, the way is the next bus of N1 (in at 08:30) with the 08:41 that waits
    // for that one. Never the 08:10 with the 08:41.
    let next = (hm(8, 20), hm(8, 55), "N1b N2b".to_string(), 0);
    let costs = |p: &mut Planner, how: How, weigh: bool| {
        let mut q = query(&tt, "NA", "NZ", 0, hm(7, 55));
        q.wishes.push(Wish { what: What::Trip(trip("N2a")), how });
        q.weigh = weigh;
        ask(p, &q).iter().map(|j| (j.depart as u32, j.arrive as u32, said(&tt, j).2, j.penalty)).collect::<Vec<_>>()
    };
    let first = |cost: u32| (hm(8, 0), hm(8, 25), "N1a N2a".to_string(), cost);
    assert_eq!(costs(&mut p, How::Never, true), [next.clone()]);
    assert_eq!(costs(&mut p, How::Avoid(1200), false), [first(1200), next.clone()]);
    assert_eq!(costs(&mut p, How::Avoid(1200), true), [first(1200)]);
    assert_eq!(costs(&mut p, How::Avoid(2400), true), [first(2400), next.clone()]);
    let mut q = query(&tt, "NA", "NZ", 0, hm(7, 55));
    q.wishes.push(Wish { what: What::Line(tt.route(tt.route_of_trip(trip("N2a"))).line), how: How::Never });
    assert_eq!(ask(&mut p, &q), []);
    // Read back, the 08:11 waits for every bus of N1 that came in before it, none of N2 having left since: arriving
    // by 08:25 with the 08:00 bus avoided, the 07:50 before it is the option that costs nothing.
    let mut q = query(&tt, "NA", "NZ", 0, hm(8, 25));
    (q.arrive_by, q.weigh) = (true, false);
    q.wishes.push(Wish { what: What::Trip(trip("N1a")), how: How::Avoid(600) });
    let got: Vec<_> = ask(&mut p, &q).iter().map(|j| (j.depart as u32, j.arrive as u32, said(&tt, j).2, j.penalty)).collect();
    assert_eq!(got, [(hm(7, 50), hm(8, 25), "N1z N2a".to_string(), 0), (hm(8, 0), hm(8, 25), "N1a N2a".to_string(), 600)]);
    // and the 08:41 waits for the bus that is in at 08:30 alone, not for those in before the 08:11 left: arriving by
    // 08:55 it is the way; without that bus the 08:11 is; without both, nothing
    let mut q = query(&tt, "NA", "NZ", 0, hm(8, 55));
    q.arrive_by = true;
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(next.0, next.1, next.2.clone())]);
    q.wishes.push(Wish { what: What::Trip(trip("N1b")), how: How::Never });
    assert_eq!(all(&tt, &ask(&mut p, &q)), waits);
    q.wishes.push(Wish { what: What::Trip(trip("N2a")), how: How::Never });
    assert_eq!(ask(&mut p, &q), []);
}

/// A line that waits holds one vehicle for each arrival, the first to leave at or after it: that one is boarded
/// whatever the walk or the change takes, and every other trip of the line is reached as without the rule.
#[test]
fn a_line_that_waits_holds_one_vehicle() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    let trip = |id: &str| (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap();
    let walk = |a: &str, z: &str| {
        let (lo, hi) = tt.walks_from(stop(a));
        (lo..hi).map(|i| tt.walk(i)).find(|w| w.0 == stop(z)).map(|w| w.1)
    };
    assert_eq!((walk("GB", "GC"), walk("DB", "DC"), tt.change_seconds()), (Some(375), Some(270), 60));
    // a question as its answer's times, trips and costs: leaving at or arriving by a moment, with a time to change
    // of one's own, a wish about one trip, the feed's rules held to or not
    #[derive(Clone, Copy, Default)]
    struct Asked<'a> {
        by: bool,
        change: Option<u32>,
        wish: Option<(&'a str, How)>,
        weigh: bool,
        without: bool,
    }
    let plain = Asked::default();
    let ways = |p: &mut Planner, from: &str, to: &str, at: u32, a: Asked| {
        let mut q = query(&tt, from, to, 0, at);
        (q.arrive_by, q.change_s, q.weigh, q.rules) = (a.by, a.change, a.weigh, !a.without);
        if let Some((id, how)) = a.wish {
            q.wishes.push(Wish { what: What::Trip(trip(id)), how });
        }
        ask(p, &q).iter().map(|j| (j.depart as u32, j.arrive as u32, said(&tt, j).2, j.penalty)).collect::<Vec<_>>()
    };
    let way = |from: (u32, u32), to: (u32, u32), trips: &str, cost: u32| (hm(from.0, from.1), hm(to.0, to.1), trips.to_string(), cost);

    // Gat. In at B at 10:00; the walk to C takes until 10:06:15. The 10:01 waits; the 10:03 is no way at all; the
    // 10:15 is made on foot. In at 10:10, the 10:15 is the one that waits, and the walk would miss it.
    let (waits, on_foot, later) = (way((9, 50), (10, 15), "G1a G2a", 0), way((9, 50), (10, 29), "G1a G2c", 0), way((10, 0), (10, 29), "G1b G2c", 0));
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 45), plain), [waits.clone()]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 55), plain), [later.clone()]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 45), Asked { without: true, ..plain }), [on_foot.clone()]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 55), Asked { without: true, ..plain }), []);
    // the 10:01 avoided: at its cost, or the 10:15 (by the bus that is in at 10:10, which leaves later than the one
    // whose riders walk); never the 10:03, a change of three minutes over a walk of more than six
    let dear = |cost: u32| way((9, 50), (10, 15), "G1a G2a", cost);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 45), Asked { wish: Some(("G2a", How::Avoid(1200))), ..plain }), [dear(1200), later.clone()]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 45), Asked { wish: Some(("G2a", How::Avoid(600))), weigh: true, ..plain }), [dear(600)]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 45), Asked { wish: Some(("G2a", How::Avoid(1200))), weigh: true, ..plain }), [dear(1200), later.clone()]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(9, 45), Asked { wish: Some(("G2a", How::Never)), ..plain }), [later.clone()]);
    // with the 10:10 bus closed too, the walk to the 10:15 is what is left
    let mut q = query(&tt, "GA", "GZ", 0, hm(9, 45));
    q.wishes.extend([Wish { what: What::Trip(trip("G2a")), how: How::Never }, Wish { what: What::Trip(trip("G1b")), how: How::Never }]);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [(on_foot.0, on_foot.1, on_foot.2.clone())]);
    // read back: by 10:17, when the 10:03 is in, the 10:01 is the way; without it there is none
    assert_eq!(ways(&mut p, "GA", "GZ", hm(10, 17), Asked { by: true, ..plain }), [waits.clone()]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(10, 17), Asked { by: true, wish: Some(("G2a", How::Never)), ..plain }), []);
    // and by 10:29: the 10:15 waits for the bus that is in at 10:10. That bus avoided, the one before it costs
    // nothing, and whoever takes it is there at 10:15 by the 10:01 that waits for it.
    assert_eq!(ways(&mut p, "GA", "GZ", hm(10, 29), Asked { by: true, ..plain }), [later.clone()]);
    assert_eq!(ways(&mut p, "GA", "GZ", hm(10, 29), Asked { by: true, wish: Some(("G1b", How::Avoid(900))), ..plain }), [waits.clone(), way((10, 0), (10, 29), "G1b G2c", 900)]);

    // Dicht. The walk takes until 10:04:30: the 10:01 waits, and the 10:13 is made on foot as it is without the rule.
    let (waits, on_foot) = (way((9, 50), (10, 15), "D1a D2a", 0), way((9, 50), (10, 27), "D1a D2b", 0));
    assert_eq!(ways(&mut p, "DA", "DZ", hm(9, 45), plain), [waits.clone()]);
    assert_eq!(ways(&mut p, "DA", "DZ", hm(10, 15), Asked { by: true, ..plain }), [waits.clone()]);
    assert_eq!(ways(&mut p, "DA", "DZ", hm(9, 45), Asked { without: true, ..plain }), [on_foot.clone()]);
    assert_eq!(ways(&mut p, "DA", "DZ", hm(9, 45), Asked { wish: Some(("D2a", How::Avoid(1200))), ..plain }), [way((9, 50), (10, 15), "D1a D2a", 1200), on_foot.clone()]);
    assert_eq!(ways(&mut p, "DA", "DZ", hm(9, 45), Asked { wish: Some(("D2a", How::Never)), ..plain }), [on_foot.clone()]);

    // Halte. At one stop the file's 60 s make the 10:01 and the 10:03 by themselves. Asked for 600 s to change, the
    // 10:01 still waits, the 10:15 is the first one made without it, and the 10:03 is no way.
    let (waits, second, third) = (way((9, 50), (10, 15), "H1a H2a", 0), way((9, 50), (10, 17), "H1a H2b", 0), way((9, 50), (10, 29), "H1a H2c", 0));
    let slow = Asked { change: Some(600), ..plain };
    assert_eq!(ways(&mut p, "HA", "HZ", hm(9, 45), plain), [waits.clone()]);
    assert_eq!(ways(&mut p, "HA", "HZ", hm(9, 45), Asked { wish: Some(("H2a", How::Avoid(1200))), ..plain }), [way((9, 50), (10, 15), "H1a H2a", 1200), second.clone()]);
    assert_eq!(ways(&mut p, "HA", "HZ", hm(9, 45), slow), [waits.clone()]);
    assert_eq!(ways(&mut p, "HA", "HZ", hm(9, 45), Asked { without: true, ..slow }), [third.clone()]);
    assert_eq!(ways(&mut p, "HA", "HZ", hm(9, 45), Asked { wish: Some(("H2a", How::Avoid(1200))), ..slow }), [way((9, 50), (10, 15), "H1a H2a", 1200), third.clone()]);
    assert_eq!(ways(&mut p, "HA", "HZ", hm(9, 45), Asked { wish: Some(("H2a", How::Never)), ..slow }), [third.clone()]);
    assert_eq!(ways(&mut p, "HA", "HZ", hm(10, 17), Asked { by: true, wish: Some(("H2a", How::Never)), ..slow }), []);
    assert_eq!(ways(&mut p, "HA", "HZ", hm(10, 17), Asked { by: true, wish: Some(("H2a", How::Never)), ..plain }), [second.clone()]);
}

/// The night the clock goes forward, Sunday's service day begins 23 hours after Saturday's. The night bus of
/// Saturday's service is in at 28:30, which is 05:30 by Sunday's clock: the 05:10 has left, the 05:40 is made.
#[test]
fn the_night_the_clock_changes() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let q = query(&tt, "KA", "KZ", 1, hm(28, 0));
    let j = ask(&mut p, &q);
    // in Saturday's seconds: Sunday's 05:40 is 23 h + 5 h 40, and its 06:00 is 29:00
    assert_eq!(all(&tt, &j), [(hm(28, 20), hm(29, 0), "K1a K2b".to_string())]);
    assert!(matches!(j[0].legs[1], Leg::Ride { day: 2, depart, .. } if depart as u32 == hm(28, 40)), "{:?}", j[0]);
    // asked on Sunday's own day, the same bus is at 05:40 and the night bus an hour before midnight earlier
    let mut q = query(&tt, "KA", "KZ", 2, hm(6, 0));
    q.arrive_by = true;
    assert_eq!(all(&tt, &ask(&mut p, &q)).iter().map(|j| (j.0 as i32 - 0, j.1)).collect::<Vec<_>>(), [(hm(5, 20) as i32, hm(6, 0))]);
}

/// Between two points one can walk all the way, and begin at any moment. Over a window the walk is one option, given
/// from the asked moment, and it counts against a ride whenever that leaves: the bus round about leaves 08:10 and is
/// in at 08:43, and whoever walks from 08:10 is there at 08:36. So that bus is no option, in whichever window
/// 08:10 lies, and two windows one after the other give the rides that one window over both gives.
#[test]
fn a_walk_all_the_way_can_begin_at_any_moment() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let at = |id: &str| {
        let s = tt.stop((0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap());
        Place::Point { lat: s.lat, lon: s.lon }
    };
    let walk = 26 * 60;
    let on_foot = |from: u32| (from, from + walk, String::new());
    let fast = (hm(8, 5), hm(8, 15), "P2a".to_string());
    let mut q = Query::new(at("PA"), at("PZ"), 0, hm(8, 0));
    // one moment: the bus is there sooner, the walk has no ride
    assert_eq!(all(&tt, &ask(&mut p, &q)), [on_foot(hm(8, 0)), fast.clone()]);
    // an hour: the same two, and not the bus round about
    q.window = 3600;
    assert_eq!(all(&tt, &ask(&mut p, &q)), [on_foot(hm(8, 0)), fast.clone()]);
    // the same hour as two windows, cut where the bus round about leaves and a second before
    for cut in [10 * 60, 10 * 60 - 1] {
        q.window = cut;
        assert_eq!(all(&tt, &ask(&mut p, &q)), [on_foot(hm(8, 0)), fast.clone()], "the first {cut} s");
        let mut far = q.clone();
        (far.time, far.window) = (hm(8, 0) + cut + 1, 3600 - cut - 1);
        assert_eq!(all(&tt, &ask(&mut p, &far)), [on_foot(far.time)], "after the first {cut} s");
    }
    // from 08:10 itself, one moment: the walk alone
    (q.time, q.window) = (hm(8, 10), 0);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [on_foot(hm(8, 10))]);
    // arriving by 09:00 over an hour, the same turned round: the fast bus, and the walk that ends at 09:00
    (q.time, q.window, q.arrive_by) = (hm(9, 0), 3600, true);
    assert_eq!(all(&tt, &ask(&mut p, &q)), [fast.clone(), on_foot(hm(9, 0) - walk)]);
    // whoever walks slower, so that the walk takes a second more than the 33 minutes of the bus round about, has
    // that bus as an option; a second less, and it is none
    let stops = p.ends(&q);
    assert_eq!((stops.from.len(), stops.to.len()), (1, 1));
    (q.time, q.window, q.arrive_by) = (hm(8, 0), 3600, false);
    let round = (hm(8, 10), hm(8, 43), "P1a".to_string());
    for (speed, secs, want) in [(985, 33 * 60 - 1, vec![fast.clone()]), (984, 33 * 60 + 1, vec![fast.clone(), round.clone()])] {
        q.walk_mm_s = speed;
        let mut got = all(&tt, &ask(&mut p, &q));
        assert_eq!(got.remove(0), (hm(8, 0), hm(8, 0) + secs, String::new()), "at {speed} mm/s");
        assert_eq!(got, want, "at {speed} mm/s");
    }
}

/// Where trips differ in what they cost, a boarding reads on into the next service day: the bus a wish avoids
/// tonight does not hide the one that costs nothing in the morning. And the same read back, arriving by.
#[test]
fn a_cheaper_trip_of_the_next_service_day() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let trip = |id: &str| (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap();
    let ways = |p: &mut Planner, at: u32, by: bool, wish: (&str, u32), weigh: bool| {
        let mut q = query(&tt, "OA", "OZ", 0, at);
        (q.arrive_by, q.weigh) = (by, weigh);
        q.wishes.push(Wish { what: What::Trip(trip(wish.0)), how: How::Avoid(wish.1) });
        ask(p, &q).iter().map(|j| (j.depart as u32, j.arrive as u32, said(&tt, j).2, j.penalty)).collect::<Vec<_>>()
    };
    let (tonight, morning) = (|cost: u32| (hm(23, 0), hm(23, 20), "O1a".to_string(), cost), |cost: u32| (hm(30, 0), hm(30, 20), "O1b".to_string(), cost));
    // asked at 22:50 with tonight's bus avoided: every option has the morning's, which costs nothing
    assert_eq!(ways(&mut p, hm(22, 50), false, ("O1a", 600), false), [tonight(600), morning(0)]);
    // weighed, ten minutes are not worth a night; ten hours are
    assert_eq!(ways(&mut p, hm(22, 50), false, ("O1a", 600), true), [tonight(600)]);
    assert_eq!(ways(&mut p, hm(22, 50), false, ("O1a", 36_000), true), [tonight(36_000), morning(0)]);
    // arriving by 06:30 with the morning's bus avoided: the mirror
    assert_eq!(ways(&mut p, hm(30, 30), true, ("O1b", 600), false), [tonight(0), morning(600)]);
    assert_eq!(ways(&mut p, hm(30, 30), true, ("O1b", 600), true), [morning(600)]);
    assert_eq!(ways(&mut p, hm(30, 30), true, ("O1b", 36_000), true), [tonight(0), morning(36_000)]);
}

/// Asked for one moment with fewer options than there are, the fastest stay; and where wishes are weighed, the
/// fastest is the one that is in first with its cost added (arriving by, the one that leaves last with its cost
/// taken off): the option the wishes are worth, not the bus they would rather not take.
#[test]
fn weighed_the_options_kept_are_the_best_with_their_cost() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let trip = |id: &str| (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap();
    let kept = |p: &mut Planner, from: &str, to: &str, at: u32, by: bool, wish: (&str, u32), weigh: bool, options: u32| {
        let mut q = query(&tt, from, to, 0, at);
        (q.arrive_by, q.weigh, q.limits.options) = (by, weigh, options);
        q.wishes.push(Wish { what: What::Trip(trip(wish.0)), how: How::Avoid(wish.1) });
        let a = p.plan(&q);
        for j in a.journeys.iter() {
            assert_eq!(impossible(p, &q, j), None, "{j:?}");
        }
        (a.journeys.iter().map(|j| (j.depart as u32, j.arrive as u32, said(&tt, j).2, j.penalty)).collect::<Vec<_>>(), a.cut_short)
    };
    // Lijn, from 07:55 with the 08:11 that waits avoided by 40 minutes: in at 08:25 at that cost, or at 08:55 at
    // none. Both are options; of one, weighed, the second (08:55 against 09:05)
    let (first, next) = ((hm(8, 0), hm(8, 25), "N1a N2a".to_string(), 2400), (hm(8, 20), hm(8, 55), "N1b N2b".to_string(), 0));
    assert_eq!(kept(&mut p, "NA", "NZ", hm(7, 55), false, ("N2a", 2400), true, 2), (vec![first.clone(), next.clone()], false));
    assert_eq!(kept(&mut p, "NA", "NZ", hm(7, 55), false, ("N2a", 2400), true, 1), (vec![next.clone()], true));
    // not weighed, cost is a thing of its own and the fastest is the first in
    assert_eq!(kept(&mut p, "NA", "NZ", hm(7, 55), false, ("N2a", 2400), false, 1), (vec![first.clone()], true));
    // Ochtend, at 22:50 with tonight's bus avoided by ten hours: the morning's (06:20 against 09:20 with the cost)
    let (tonight, morning) = (|cost: u32| (hm(23, 0), hm(23, 20), "O1a".to_string(), cost), |cost: u32| (hm(30, 0), hm(30, 20), "O1b".to_string(), cost));
    assert_eq!(kept(&mut p, "OA", "OZ", hm(22, 50), false, ("O1a", 36_000), true, 1), (vec![morning(0)], true));
    assert_eq!(kept(&mut p, "OA", "OZ", hm(22, 50), false, ("O1a", 36_000), false, 1), (vec![tonight(36_000)], true));
    // and arriving by 06:30 with the morning's avoided by ten hours, the mirror: tonight's (leaving 23:00 against
    // 20:00 with the cost taken off)
    assert_eq!(kept(&mut p, "OA", "OZ", hm(30, 30), true, ("O1b", 36_000), true, 1), (vec![tonight(0)], true));
    assert_eq!(kept(&mut p, "OA", "OZ", hm(30, 30), true, ("O1b", 36_000), false, 1), (vec![morning(36_000)], true));
}
