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
    if q.max_rides < 8 || q.day < 0 || journeys.iter().any(|j| j.rides == 0) || q.wishes.iter().any(|w| !matches!(w.how, How::Never)) {
        return;
    }
    let scan = Reference::new(planner, q.day, q.time as i32 - 86_400, q.time as i32 + 2 * 86_400);
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
    assert_eq!((tt.first_day(), tt.days(), tt.stops(), tt.trips(), tt.walks(), tt.rules()), (20270326, 4, 31, 31, 2, 4));
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
