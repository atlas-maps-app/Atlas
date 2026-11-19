//! The planner on a third feed written by hand: tests/data/autumn, packed to tests/data/autumn.tt by
//! ../pack/ttpack.py. Friday 29 October 2027 is day 0; the clock goes back in the night of Saturday 30 to Sunday 31,
//! so Saturday's service day lasts 90,000 s and Sunday's begins at Saturday's 25:00.
//!
//! - Klok: on Saturday's service a night bus A to B, 28:20 to 28:30. By Sunday's clock that is 03:20 to 03:30. On
//!   Sunday's service B to Z leaves 03:10, 03:40 and 04:45, twenty minutes each: the 03:10 has gone, the 03:40 is
//!   made. (Whoever took Sunday to begin 86,400 s after Saturday would see the 03:40 leave at Saturday's 27:40,
//!   before the night bus is in, and would wait for the 04:45.)
//! - Geen: the bus A to S is in at 08:10 and the bus S to Z leaves 08:20, but the feed says nobody changes at S
//!   (a rule of "not possible" for everyone, from S to itself). The one way is the direct bus, 08:05 to 09:00.
//!
//! Every answer is held to the connection scan as well (src/reference.rs), which reads the days the clock changes
//! and a stop's own time to change from the file by itself.

use atlas_transit::file::{StopText, TripText};
use atlas_transit::reference::{Reference, change_time, day_start, impossible, own_change};
use atlas_transit::{Journey, Leg, Place, Planner, Query, Timetable};

fn bytes() -> Vec<u8> {
    std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/autumn.tt")).unwrap()
}

fn hm(h: u32, m: u32) -> u32 {
    h * 3600 + m * 60
}

fn query(tt: &Timetable, from: &str, to: &str, day: i32, time: u32) -> Query {
    let stop = |id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    Query::new(Place::Stop(stop(from)), Place::Stop(stop(to)), day, time)
}

/// The planner's answer, each journey one that can be made, and the scan's word on it: the earliest arrival, the
/// latest departure, or a window's best pairs.
fn ask(planner: &mut Planner, q: &Query) -> Vec<(u32, u32, String)> {
    let a = planner.plan(q);
    assert!(!a.cut_short);
    let tt = *planner.timetable();
    for j in a.journeys.iter() {
        assert_eq!(impossible(planner, q, j), None, "{j:?}");
    }
    let scan = Reference::new(planner, q.day, q.time as i32 - 86_400, q.time as i32 + 2 * 86_400);
    match (q.window, q.arrive_by) {
        (0, false) => assert_eq!(a.journeys.iter().map(|j| j.arrive).min(), scan.earliest_arrival(planner, q, q.time as i32), "{q:?}"),
        (0, true) => assert_eq!(a.journeys.iter().map(|j| j.depart).max(), scan.latest_departure(planner, q, q.time as i32), "{q:?}"),
        _ => assert_eq!(scan.window_differs(planner, q, &a.journeys), None, "{q:?}"),
    }
    let said = |j: &Journey| {
        let trips: Vec<&str> = j.legs.iter().filter_map(|l| if let Leg::Ride { trip, .. } = l { Some(tt.trip_text(*trip, TripText::Id)) } else { None }).collect();
        (j.depart as u32, j.arrive as u32, trips.join(" "))
    };
    a.journeys.iter().map(said).collect()
}

#[test]
fn the_file_is_sound() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    tt.verify().unwrap();
    assert_eq!((tt.first_day(), tt.days(), tt.stops(), tt.trips(), tt.walks(), tt.rules()), (20271029, 4, 6, 7, 0, 1));
    assert_eq!(tt.meta("odd_days"), Some("20271030:90000"));
}

/// What the scan reads from a file by itself, held to numbers worked out by hand: when a service day begins (here,
/// and in tests/data/edges.tt, whose Saturday 27 March 2027 lasts 23 hours), and what a change at a stop costs.
/// The planner has its own reading of both; the answers below and in tests/edges.rs hold the two to each other.
#[test]
fn the_scan_reads_the_days_and_the_changes_by_itself() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    // Friday 29 October is day 0, Saturday 30 lasts 90,000 s
    for (day, off, begins) in [(0, 0, 0), (0, 1, 86_400), (1, 1, 90_000), (0, 2, 176_400), (2, 1, 86_400), (2, -1, -90_000), (2, -2, -176_400), (3, -3, -262_800), (1, -1, -86_400)] {
        assert_eq!(day_start(&tt, day, off), begins, "day {day}, {off} days on");
    }
    let stop = |tt: &Timetable, id: &str| (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap();
    // nobody changes at S; every other stop has the file's time, or the asker's
    let (gs, kb) = (stop(&tt, "GS"), stop(&tt, "KB"));
    assert_eq!((0..tt.stops()).filter(|s| own_change(&tt, *s).is_some()).collect::<Vec<_>>(), [gs]);
    let mut q = query(&tt, "GA", "GZ", 0, 0);
    assert!(change_time(&tt, &q, gs) > 86_400 * 1000);
    assert_eq!(change_time(&tt, &q, kb), tt.change_seconds() as i32);
    q.change_s = Some(45);
    assert!(change_time(&tt, &q, gs) > 86_400 * 1000);
    assert_eq!(change_time(&tt, &q, kb), 45);

    let b = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/edges.tt")).unwrap();
    let tt = Timetable::open(&b).unwrap();
    // Friday 26 March is day 0, Saturday 27 lasts 82,800 s
    for (day, off, begins) in [(0, 1, 86_400), (1, 1, 82_800), (0, 2, 169_200), (2, -1, -82_800), (3, -2, -169_200), (2, 1, 86_400)] {
        assert_eq!(day_start(&tt, day, off), begins, "edges: day {day}, {off} days on");
    }
    // SS has 300 s of its own for everyone; the 300 s at MS are between two trips, and no stop's own
    let (ss, ms) = (stop(&tt, "SS"), stop(&tt, "MS"));
    assert_eq!((0..tt.stops()).filter_map(|s| own_change(&tt, s).map(|secs| (s, secs))).collect::<Vec<_>>(), [(ss, 300)]);
    let mut q = Query::new(Place::Stop(ss), Place::Stop(ms), 0, 0);
    assert_eq!((change_time(&tt, &q, ss), change_time(&tt, &q, ms)), (300, tt.change_seconds() as i32));
    // the asker's own time, but never less than the stop's
    q.change_s = Some(60);
    assert_eq!((change_time(&tt, &q, ss), change_time(&tt, &q, ms)), (300, 60));
    q.change_s = Some(600);
    assert_eq!((change_time(&tt, &q, ss), change_time(&tt, &q, ms)), (600, 600));
}

/// The night the clock goes back, Sunday's service day begins 25 hours after Saturday's.
#[test]
fn the_night_the_clock_goes_back() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    // asked on Saturday's day, in Saturday's seconds: Sunday's 03:40 is 25 h + 3 h 40, its 04:00 is 29:00
    let night = vec![(hm(28, 20), hm(29, 0), "K1a K2b".to_string())];
    let mut q = query(&tt, "KA", "KZ", 1, hm(28, 0));
    for patient in [true, false] {
        q.limits.patient = patient;
        (q.window, q.arrive_by, q.time) = (0, false, hm(28, 0));
        assert_eq!(ask(&mut p, &q), night, "patient {patient}");
        q.window = 1800;
        assert_eq!(ask(&mut p, &q), night, "patient {patient}, over half an hour");
        (q.window, q.arrive_by, q.time) = (0, true, hm(29, 0));
        assert_eq!(ask(&mut p, &q), night, "patient {patient}, arriving by");
        q.window = 1800;
        assert_eq!(ask(&mut p, &q), night, "patient {patient}, arriving in half an hour");
        // by 28:59 there is no way: the 03:10 left twenty minutes before the night bus is in
        (q.window, q.time) = (0, hm(28, 59));
        assert_eq!(ask(&mut p, &q), [], "patient {patient}, a minute sooner");
    }
    // the second ride is Sunday's trip, on Sunday's day, at Saturday's 28:40
    let j = &p.plan(&query(&tt, "KA", "KZ", 1, hm(28, 0))).journeys[0];
    assert!(matches!(j.legs[1], Leg::Ride { day: 2, depart, arrive, .. } if (depart as u32, arrive as u32) == (hm(28, 40), hm(29, 0))), "{j:?}");
    // asked on Sunday's own day: the same bus is the 03:40, and the night bus leaves at Sunday's 03:20
    let mut q = query(&tt, "KA", "KZ", 2, hm(4, 0));
    q.arrive_by = true;
    assert_eq!(ask(&mut p, &q), [(hm(3, 20), hm(4, 0), "K1a K2b".to_string())]);
    let j = &p.plan(&q).journeys[0];
    assert!(matches!(j.legs[0], Leg::Ride { day: 1, depart, .. } if depart as u32 == hm(3, 20)), "{j:?}");
    // and from Sunday's 03:15 on, leaving
    let q = query(&tt, "KA", "KZ", 2, hm(3, 15));
    assert_eq!(ask(&mut p, &q), [(hm(3, 20), hm(4, 0), "K1a K2b".to_string())]);
    // a day that is not the long one: Friday's 08:00 to Saturday's is 86,400 s as ever (the direct bus of Geen)
    let mut q = query(&tt, "GA", "GZ", 0, hm(9, 30));
    q.window = 0;
    assert_eq!(ask(&mut p, &q), [(hm(24, 0) + hm(8, 5), hm(24, 0) + hm(9, 0), "G3a".to_string())]);
    // and from Saturday 10:30 the next one is Sunday's, 25 hours and not 24 after Saturday's
    let q = query(&tt, "GA", "GZ", 1, hm(10, 30));
    assert_eq!(ask(&mut p, &q), [(hm(25, 0) + hm(8, 5), hm(25, 0) + hm(9, 0), "G3a".to_string())]);
}

/// Where the feed says nobody changes at a stop, nobody does: whatever time the asker allows for a change.
#[test]
fn nobody_changes_where_the_feed_says_so() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let direct = vec![(hm(8, 5), hm(9, 0), "G3a".to_string())];
    for (change, rules) in [(None, true), (Some(0), true), (Some(600), true), (None, false)] {
        let mut q = query(&tt, "GA", "GZ", 0, hm(7, 55));
        (q.change_s, q.rules) = (change, rules);
        assert_eq!(ask(&mut p, &q), direct, "change {change:?} rules {rules}");
        q.window = 900;
        assert_eq!(ask(&mut p, &q), direct, "change {change:?} rules {rules}, over a quarter of an hour");
        (q.window, q.arrive_by, q.time) = (0, true, hm(9, 0));
        assert_eq!(ask(&mut p, &q), direct, "change {change:?} rules {rules}, arriving by");
        q.time = hm(8, 30);
        assert_eq!(ask(&mut p, &q), [], "change {change:?} rules {rules}, arriving by 08:30");
    }
    // each half of the way that is none can be ridden
    let q = query(&tt, "GA", "GS", 0, hm(7, 55));
    assert_eq!(ask(&mut p, &q), [(hm(8, 0), hm(8, 10), "G1a".to_string())]);
    let q = query(&tt, "GS", "GZ", 0, hm(8, 10));
    assert_eq!(ask(&mut p, &q), [(hm(8, 20), hm(8, 30), "G2a".to_string())]);
}
