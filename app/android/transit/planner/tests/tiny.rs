//! The planner on a feed written by hand (tests/data/tiny, packed to tests/data/tiny.tt by ../pack/ttpack.py), where
//! every answer can be worked out on paper. Monday 5 January 2026 is day 0.
//!
//! Line 1 (bus): Aan Zee, Bos, Centrum platform 1, Dorp, Eind. T1 leaves 08:00 and is at Centrum 08:20, Eind 08:40;
//! the fast T1F leaves 08:05, is at Centrum 08:12 and Eind 08:20 (it overtakes T1, so the two are two routes); T2
//! leaves 09:00; T3 leaves 23:30 and reaches Dorp at 24:00 and Eind at 24:10. Nobody boards T1, T1F or T2 at Dorp.
//! Line 2 (tram): Centrum platform 2 to Fort, leaving 08:21 (U1), 08:25 (U2), 08:50 (U3), ten minutes each.
//! Line S3 (rail): Gracht to Eind, 08:45 on weekdays (V2), 08:40 at the weekend (V1), 10:00 on Wednesday 7 only (V3),
//! when the weekday services don't run. Platforms 1 and 2 of Centrum are 120 s apart; Fort and Gracht 84 s.
//! The feed's rules: U1 waits for T1; from T1F to U1 no change is possible.

use atlas_transit::file::{StopText, TripText};
use atlas_transit::reference::{Reference, impossible};
use atlas_transit::{Area, How, Journey, Leg, Mode, Place, Planner, Query, Spot, Timetable, What, Wish};

fn bytes() -> Vec<u8> {
    std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/tiny.tt")).unwrap()
}

fn stop(tt: &Timetable, id: &str) -> u32 {
    (0..tt.stops()).find(|s| tt.stop_text(*s, StopText::Id) == id).unwrap()
}

fn hm(h: u32, m: u32) -> u32 {
    h * 3600 + m * 60
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

fn numbers(tt: &Timetable, j: &Journey) -> Vec<String> {
    j.legs.iter().filter_map(|l| if let Leg::Ride { trip, .. } = l { Some(tt.trip_text(*trip, TripText::Number).to_string()) } else { None }).collect()
}

#[test]
fn the_file_is_what_the_feed_says() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    tt.verify().unwrap();
    assert_eq!((tt.first_day(), tt.days()), (20260105, 7));
    assert_eq!((tt.stops(), tt.lines(), tt.trips(), tt.rules()), (10, 3, 10, 2));
    assert_eq!(tt.meta("timezone"), Some("Europe/Amsterdam"));
    assert_eq!(tt.meta("feed_version"), Some("1"));
    // the stop no trip calls at is gone; the platform at 0,0 stands at its station's place and says so
    assert!((0..tt.stops()).all(|s| tt.stop_text(s, StopText::Name) != "Zonder Dienst"));
    let (x, st) = (tt.stop(stop(&tt, "X")), tt.stop(stop(&tt, "ST")));
    assert_eq!((x.lat, x.lon, x.place, x.parent), (st.lat, st.lon, 1, stop(&tt, "ST")));
    assert_eq!((st.lat, st.lon, st.kind), (520_000_000, 41_000_000, 1));
    assert_eq!(tt.stop(stop(&tt, "B")).wheelchair, 2);
    assert_eq!(tt.stop_text(stop(&tt, "C2"), StopText::Platform), "2");
    // the walks: between the platforms of Centrum the station's two minutes, between Fort and Gracht by the rule
    let walk = |a: &str, b: &str| {
        let (lo, hi) = tt.walks_from(stop(&tt, a));
        (lo..hi).map(|i| tt.walk(i)).find(|w| w.0 == stop(&tt, b)).map(|w| w.1)
    };
    assert_eq!((walk("C1", "C2"), walk("C2", "C1"), walk("C1", "X")), (Some(120), Some(120), Some(120)));
    assert_eq!((walk("F", "G"), walk("G", "F"), walk("A", "B")), (Some(84), Some(84), None));
    // a trip: the stop without a time got one between its neighbours, and its strings are the feed's
    let t2 = (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == "T2").unwrap();
    let r = tt.route(tt.route_of_trip(t2));
    let times = tt.trip_times(t2);
    assert_eq!((0..r.stops).map(|p| tt.time(times, p)).collect::<Vec<_>>(), [(hm(9, 0), hm(9, 0)), (hm(9, 10), hm(9, 10)), (hm(9, 20), hm(9, 22)), (hm(9, 30), hm(9, 30)), (hm(9, 40), hm(9, 40))]);
    assert_eq!((tt.trip_text(t2, TripText::Realtime), tt.trip_text(t2, TripText::Number), tt.trip(t2).wheelchair, tt.trip(t2).bicycles), ("AA:1:102", "102", 1, 2));
    assert_eq!(tt.pattern_headsign(r.pattern), "Eind");
    assert_eq!((tt.pattern_stop_headsign(r.first_stop + 2), tt.pattern_stop_fare_units(r.first_stop + 2), tt.pattern_stop_distance(r.first_stop + 2)), ("Eind via Dorp", Some(7), Some(6800)));
    let d = tt.pattern_stop(r.first_stop + 3);
    assert_eq!((d.stop, d.pickup, d.drop_off, tt.pattern_stop(r.first_stop + 1).approximate), (stop(&tt, "D"), 1, 0, true));
    assert_eq!(tt.line_colours(r.line), (Some(0xe00713), Some(0xffffff)));
    // the days: weekdays but not Wednesday; the weekend; Wednesday alone
    let runs = |id: &str| (0..7).map(|d| if tt.runs(tt.trip((0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Id) == id).unwrap()).service, d) { '1' } else { '0' }).collect::<String>();
    assert_eq!((runs("T1"), runs("V1"), runs("V3")), ("1101100".to_string(), "0000011".to_string(), "0010000".to_string()));
    assert_eq!((tt.day_of(20260107), tt.date_of(6), tt.day_of(20251231)), (Some(2), 20260111, Some(-5)));
}

#[test]
fn the_fast_bus_that_overtakes() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (a, e) = (stop(&tt, "A"), stop(&tt, "E"));
    let j = ask(&mut p, &Query::new(Place::Stop(a), Place::Stop(e), 0, hm(8, 0)));
    assert_eq!(j.len(), 1);
    assert_eq!((j[0].depart as u32, j[0].arrive as u32, j[0].rides, numbers(&tt, &j[0])), (hm(8, 5), hm(8, 20), 1, vec!["105".to_string()]));
    // over an hour: the fast one, and the next; the slow 08:00 is beaten by the fast one that leaves after it
    let mut q = Query::new(Place::Stop(a), Place::Stop(e), 0, hm(8, 0));
    q.window = 3600;
    let j = ask(&mut p, &q);
    assert_eq!(j.iter().map(|j| (j.depart as u32, j.arrive as u32)).collect::<Vec<_>>(), [(hm(8, 5), hm(8, 20)), (hm(9, 0), hm(9, 40))]);
    // arrive by 08:40: the fast one still, as it leaves later
    let mut q = Query::new(Place::Stop(a), Place::Stop(e), 0, hm(8, 40));
    q.arrive_by = true;
    let j = ask(&mut p, &q);
    assert_eq!((j.len(), j[0].depart as u32, j[0].arrive as u32), (1, hm(8, 5), hm(8, 20)));
    // arrive by 08:19: nothing
    q.time = hm(8, 19);
    assert!(ask(&mut p, &q).is_empty());
}

#[test]
fn a_change_and_the_feeds_rules_for_it() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (a, f) = (stop(&tt, "A"), stop(&tt, "F"));
    // without the rules: the fast bus is at Centrum 08:12, two minutes to the other platform, the 08:21 tram
    let mut q = Query::new(Place::Stop(a), Place::Stop(f), 0, hm(8, 0));
    q.rules = false;
    let j = ask(&mut p, &q);
    assert_eq!((j.len(), j[0].depart as u32, j[0].arrive as u32, numbers(&tt, &j[0])), (1, hm(8, 5), hm(8, 31), vec!["105".to_string(), "201".to_string()]));
    assert_eq!(j[0].walking, 120);
    assert!(matches!(j[0].legs[1], Leg::Walk { from: Spot::Stop(x), to: Spot::Stop(y), depart, arrive } if (x, y, depart as u32, arrive as u32) == (stop(&tt, "C1"), stop(&tt, "C2"), hm(8, 12), hm(8, 14))));
    // with them: no change from the fast bus to that tram, but the tram waits for the slow bus (at Centrum 08:20,
    // a minute before the tram leaves, where the walk alone takes two)
    q.rules = true;
    let j = ask(&mut p, &q);
    assert_eq!((j.len(), j[0].depart as u32, j[0].arrive as u32, numbers(&tt, &j[0])), (1, hm(8, 0), hm(8, 31), vec!["101".to_string(), "201".to_string()]));
    // over half an hour: the slow bus to the tram that waits; the fast bus to the tram after; and, leaving five
    // minutes later for the same arrival at the price of a ride more, the fast bus to Bos, there the slow bus it has
    // overtaken (it is in at 08:08, the slow one leaves 08:10), and the tram that waits for that
    q.window = 1800;
    let j = ask(&mut p, &q);
    assert_eq!(
        j.iter().map(|j| (j.depart as u32, j.arrive as u32, numbers(&tt, j).join(" "))).collect::<Vec<_>>(),
        [(hm(8, 0), hm(8, 31), "101 201".to_string()), (hm(8, 5), hm(8, 31), "105 101 201".to_string()), (hm(8, 5), hm(8, 35), "105 202".to_string())]
    );
    // arrive by 08:35: the fast bus (it leaves later); by 08:31: the slow one, or with a ride more the fast one to
    // Bos and the slow one from there; the rules hold searching back too
    let mut q = Query::new(Place::Stop(a), Place::Stop(f), 0, hm(8, 35));
    q.arrive_by = true;
    let j = ask(&mut p, &q);
    assert_eq!((j.len(), j[0].depart as u32, j[0].arrive as u32, numbers(&tt, &j[0]).join(" ")), (1, hm(8, 5), hm(8, 35), "105 202".to_string()));
    q.time = hm(8, 31);
    let j = ask(&mut p, &q);
    assert_eq!(j.iter().map(|j| (j.depart as u32, numbers(&tt, j).join(" "))).collect::<Vec<_>>(), [(hm(8, 0), "101 201".to_string()), (hm(8, 5), "105 101 201".to_string())]);
    q.rules = false;
    let j = ask(&mut p, &q);
    assert_eq!((j.len(), j[0].depart as u32, numbers(&tt, &j[0]).join(" ")), (1, hm(8, 5), "105 201".to_string()));
    // to the stop a walk from where the tram ends: the walk is part of the journey
    let mut q = Query::new(Place::Stop(a), Place::Stop(stop(&tt, "G")), 0, hm(8, 0));
    q.rules = false;
    let j = ask(&mut p, &q);
    assert_eq!((j[0].arrive as u32, j[0].walking), (hm(8, 31) + 84, 120 + 84));
    assert!(matches!(j[0].legs.last(), Some(Leg::Walk { to: Spot::Destination, .. })));
}

#[test]
fn no_boarding_where_the_feed_says_so() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    // at Dorp nobody boards the day's buses; only the late one takes riders there
    let j = ask(&mut p, &Query::new(Place::Stop(stop(&tt, "D")), Place::Stop(stop(&tt, "E")), 0, hm(8, 0)));
    assert_eq!((j.len(), j[0].depart as u32, j[0].arrive as u32, numbers(&tt, &j[0])), (1, hm(24, 0), hm(24, 10), vec!["103".to_string()]));
    assert!(matches!(j[0].legs[0], Leg::Ride { day: 0, .. }));
    // and nobody alights at the first stop of a line
    assert!(ask(&mut p, &Query::new(Place::Stop(stop(&tt, "E")), Place::Stop(stop(&tt, "A")), 0, hm(8, 0))).is_empty());
}

#[test]
fn past_midnight_and_the_days() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (d, e, g) = (stop(&tt, "D"), stop(&tt, "E"), stop(&tt, "G"));
    // Tuesday 00:00 at Dorp: Monday's last bus is there at its 24:00
    let j = ask(&mut p, &Query::new(Place::Stop(d), Place::Stop(e), 1, 0));
    assert_eq!((j.len(), j[0].depart, j[0].arrive), (1, 0, 600));
    assert!(matches!(j[0].legs[0], Leg::Ride { day: 0, .. }));
    // Monday 00:00 at Dorp: the day before is not in the file, so it is Monday's own last bus, a day later; that
    // is past the 24 hours a journey may take from the asked moment, so from noon it is found and from 00:00 not
    let j = ask(&mut p, &Query::new(Place::Stop(d), Place::Stop(e), 0, hm(12, 0)));
    assert_eq!((j.len(), j[0].depart as u32, j[0].arrive as u32), (1, hm(24, 0), hm(24, 10)));
    assert!(ask(&mut p, &Query::new(Place::Stop(d), Place::Stop(e), 0, 0)).is_empty());
    // Saturday 00:00: Friday's bus; Sunday 00:00: nothing ran on Saturday, nothing runs on Sunday
    assert_eq!(ask(&mut p, &Query::new(Place::Stop(d), Place::Stop(e), 5, 0))[0].arrive, 600);
    assert!(ask(&mut p, &Query::new(Place::Stop(d), Place::Stop(e), 6, 0)).is_empty());
    // arriving by 00:15 on Tuesday: the same bus, found searching back
    let mut q = Query::new(Place::Stop(d), Place::Stop(e), 1, 900);
    q.arrive_by = true;
    let j = ask(&mut p, &q);
    assert_eq!((j.len(), j[0].depart, j[0].arrive), (1, 0, 600));
    // the train from Gracht: 08:45 on a weekday, 08:40 at the weekend, 10:00 on the Wednesday without weekday service
    let at = |p: &mut Planner, day: i32| ask(p, &Query::new(Place::Stop(g), Place::Stop(e), day, hm(8, 0))).first().map(|j| (j.depart as u32, j.arrive as u32));
    assert_eq!(at(&mut p, 0), Some((hm(8, 45), hm(8, 47))));
    assert_eq!(at(&mut p, 5), Some((hm(8, 40), hm(8, 50))));
    assert_eq!(at(&mut p, 2), Some((hm(10, 0), hm(10, 2) + 30)));
    // outside the file's days nothing runs, and nothing breaks
    assert_eq!(at(&mut p, 9), None);
    assert_eq!(at(&mut p, -3), None);
}

#[test]
fn wishes() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (a, e, f, bos) = (stop(&tt, "A"), stop(&tt, "E"), stop(&tt, "F"), stop(&tt, "B"));
    let base = Query::new(Place::Stop(a), Place::Stop(e), 0, hm(8, 0));
    let with = |what: What, how: How| {
        let mut q = base.clone();
        q.wishes.push(Wish { what, how });
        q
    };
    // never changing at Bos changes nothing (nobody changes there); never riding past it leaves no way
    let j = ask(&mut p, &with(What::Stop { stop: bos, through: false }, How::Never));
    assert_eq!((j.len(), j[0].arrive as u32, j[0].penalty), (1, hm(8, 20), 0));
    assert!(ask(&mut p, &with(What::Stop { stop: bos, through: true }, How::Never)).is_empty());
    // avoided, riding past it costs what was said, once, and the journey says so
    let j = ask(&mut p, &with(What::Stop { stop: bos, through: true }, How::Avoid(300)));
    assert_eq!((j.len(), j[0].arrive as u32, j[0].penalty), (1, hm(8, 20), 300));
    // the same by an area round it, a box and an outline
    let s = tt.stop(bos);
    for area in [
        Area::Circle { lat: s.lat, lon: s.lon, radius_m: 200 },
        Area::Box { south: s.lat - 1000, west: s.lon - 1000, north: s.lat + 1000, east: s.lon + 1000 },
        Area::Outline(vec![(s.lat - 1000, s.lon - 1000), (s.lat - 1000, s.lon + 1000), (s.lat + 1000, s.lon + 1000), (s.lat + 1000, s.lon - 1000)]),
    ] {
        assert!(ask(&mut p, &with(What::Area { area: area.clone(), through: true }, How::Never)).is_empty());
        assert_eq!(ask(&mut p, &with(What::Area { area, through: true }, How::Avoid(60)))[0].penalty, 60);
    }
    // never the one fast trip: the slow bus; never the kind it is: the same
    let fast = (0..tt.trips()).find(|t| tt.trip_text(*t, TripText::Number) == "105").unwrap();
    for q in [with(What::Trip(fast), How::Never), with(What::Kind("snelbus".into()), How::Never)] {
        let j = ask(&mut p, &q);
        assert_eq!((j.len(), j[0].depart as u32, j[0].arrive as u32, numbers(&tt, &j[0])), (1, hm(8, 0), hm(8, 40), vec!["101".to_string()]));
    }
    // The fast trip avoided: it is there at 08:20, the slow bus at 08:40. What the wish is worth decides whether the
    // slow bus is an option beside it: at twenty-five minutes it is (twenty later, and the wish kept); at ten it is
    // not; at twenty exactly it is no better for being later, and is not. The fast one says what it costs.
    let mut options = |q: &Query| {
        let mut got: Vec<_> = ask(&mut p, q).iter().map(|j| (j.depart as u32, j.arrive as u32, j.penalty, numbers(&tt, j).join(" "))).collect();
        got.sort();
        got
    };
    let (quick, slow) = (|cost: u32| (hm(8, 5), hm(8, 20), cost, "105".to_string()), (hm(8, 0), hm(8, 40), 0, "101".to_string()));
    assert_eq!(options(&with(What::Trip(fast), How::Avoid(1500))), [slow.clone(), quick(1500)]);
    assert_eq!(options(&with(What::Trip(fast), How::Avoid(600))), [quick(600)]);
    assert_eq!(options(&with(What::Trip(fast), How::Avoid(1200))), [quick(1200)]);
    // asked for every option, the slow bus stays whatever the wish is worth
    let mut every = with(What::Trip(fast), How::Avoid(600));
    every.weigh = false;
    assert_eq!(options(&every), [slow.clone(), quick(600)]);
    // To arrive by 08:40 the fast bus leaves five minutes later than the slow one: a wish worth ten keeps the slow
    // one beside it, a wish worth five (no better for leaving earlier) or less does not.
    let mut by = with(What::Trip(fast), How::Avoid(600));
    (by.arrive_by, by.time) = (true, hm(8, 40));
    assert_eq!(options(&by), [slow.clone(), quick(600)]);
    by.wishes[0].how = How::Avoid(300);
    assert_eq!(options(&by), [quick(300)]);
    // over a window the same holds between the options that leave no earlier: from 07:50 on for a quarter of an hour
    let mut over = with(What::Trip(fast), How::Avoid(600));
    (over.time, over.window) = (hm(7, 50), 900);
    assert_eq!(options(&over), [quick(600)]);
    over.wishes[0].how = How::Avoid(1500);
    assert_eq!(options(&over), [slow, quick(1500)]);
    // what a wheelchair can't board: the fast bus says no; the slow one says yes
    let j = ask(&mut p, &with(What::NoWheelchair, How::Never));
    assert_eq!((j.len(), numbers(&tt, &j[0])), (1, vec!["101".to_string()]));
    // never by bus: no way; never by tram: a way all the same, as this one has no tram
    assert!(ask(&mut p, &with(What::Mode(Mode::Bus), How::Never)).is_empty());
    assert_eq!(ask(&mut p, &with(What::Mode(Mode::Tram), How::Never)).len(), 1);
    assert!(ask(&mut p, &with(What::Line(tt.route(tt.route_of_trip(fast)).line), How::Never)).is_empty());
    assert!(ask(&mut p, &with(What::Operator(tt.line_operator(tt.route(tt.route_of_trip(fast)).line)), How::Never)).is_empty());
    // rail preferred: a bus ride costs, a tram ride costs; to Fort that is two rides not by rail
    let mut q = Query::new(Place::Stop(a), Place::Stop(f), 0, hm(8, 0));
    q.wishes.push(Wish { what: What::Mode(Mode::Rail), how: How::Prefer(120) });
    let j = ask(&mut p, &q);
    assert!(!j.is_empty() && j.iter().all(|j| j.penalty == 240));
    // changing at Centrum avoided: the change there costs at both platforms
    let mut q = Query::new(Place::Stop(a), Place::Stop(f), 0, hm(8, 0));
    q.wishes.push(Wish { what: What::Station { station: stop(&tt, "ST"), through: false }, how: How::Avoid(100) });
    assert_eq!(ask(&mut p, &q)[0].penalty, 200);
    // and never changing there: no way to Fort
    q.wishes[0].how = How::Never;
    assert!(ask(&mut p, &q).is_empty());
}

#[test]
fn from_a_point_and_on_foot() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (a, e) = (tt.stop(stop(&tt, "A")), tt.stop(stop(&tt, "E")));
    // 100 m north of Aan Zee to 200 m north of Eind: the walks at both ends, at 1.25 m/s over the line and 30 %
    let north = |m: i32| (m as i64 * 10_000_000 / 111_195) as i32;
    let q = Query::new(Place::Point { lat: a.lat + north(100), lon: a.lon }, Place::Point { lat: e.lat + north(200), lon: e.lon }, 0, hm(8, 0));
    let j = ask(&mut p, &q);
    assert_eq!(j.len(), 1);
    assert_eq!((j[0].depart as u32, j[0].arrive as u32, j[0].walking), (hm(8, 5) - 104, hm(8, 20) + 208, 312));
    assert_eq!(j[0].legs.len(), 3);
    // two points 300 m apart with no stop between: on foot all the way
    let q = Query::new(Place::Point { lat: a.lat + north(100), lon: a.lon }, Place::Point { lat: a.lat + north(400), lon: a.lon }, 0, hm(8, 0));
    let j = ask(&mut p, &q);
    assert_eq!((j.len(), j[0].rides, j[0].depart as u32, j[0].walking), (1, 0, hm(8, 0), 312));
    // the walks given by whoever knows the streets
    let q = Query::new(Place::Walks(vec![(stop(&tt, "A"), 30)]), Place::Walks(vec![(stop(&tt, "E"), 45)]), 0, hm(8, 0));
    let j = ask(&mut p, &q);
    assert_eq!((j[0].depart as u32, j[0].arrive as u32), (hm(8, 5) - 30, hm(8, 20) + 45));
}

#[test]
fn what_is_asked_wrongly_comes_back_empty() {
    let b = bytes();
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let mut q = Query::new(Place::Stop(9999), Place::Station(u32::MAX), i32::MAX, u32::MAX);
    q.window = u32::MAX;
    q.max_rides = u32::MAX;
    q.wishes.push(Wish { what: What::Line(u32::MAX), how: How::Avoid(u32::MAX) });
    q.wishes.push(Wish { what: What::Stop { stop: u32::MAX, through: true }, how: How::Prefer(u32::MAX) });
    q.wishes.push(Wish { what: What::Area { area: Area::Outline(vec![]), through: true }, how: How::Never });
    assert!(p.plan(&q).journeys.is_empty());
    q.arrive_by = true;
    assert!(p.plan(&q).journeys.is_empty());
}
