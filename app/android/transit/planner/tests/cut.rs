//! The planner on a piece of the real timetable: tests/data/utrecht-20261117.tt, packed by ../pack/ttpack.py from
//! OVapi's feed of 16 November 2026 (version 9696; CC0) with `--first 20261117 --days 2 --box 52.06,5.07,52.12,5.16`:
//! the city of Utrecht, Tuesday and Wednesday, 708 stops, 7,313 trips. Day 0 is Tuesday 17 November.
//!
//! What is held here: the planner and the connection scan (src/reference.rs) agree; every journey the planner returns
//! can be made as it says (reference::impossible, which reads the file and not the search); wishes close and cost
//! what they say; the answers are the same on every platform; and no file a little wrong, and no query, panics.

use atlas_transit::reference::{Reference, impossible};
use atlas_transit::{Area, How, Journey, Leg, Mode, Place, Planner, Query, Timetable, What, Wish, fuzz};

fn bytes(name: &str) -> Vec<u8> {
    std::fs::read(format!("{}/tests/data/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

fn served(tt: &Timetable) -> Vec<u32> {
    (0..tt.stops()).filter(|s| tt.stop(*s).kind == 0 && tt.routes_at(*s).0 != tt.routes_at(*s).1).collect()
}

/// Pairs of served stops, the same every time.
fn pairs(tt: &Timetable, n: usize, seed: u64) -> Vec<(u32, u32)> {
    let (stops, mut rng) = (served(tt), fuzz::Rng(seed | 1));
    (0..n).map(|_| (stops[rng.below(stops.len() as u64) as usize], stops[rng.below(stops.len() as u64) as usize])).collect()
}

fn ask(planner: &mut Planner, q: &Query) -> Vec<Journey> {
    asked(planner, q).0
}

/// The journeys, and the query as the scan is to be asked it: with the horizon the planner followed (a patient
/// search follows less than the query's own).
fn asked(planner: &mut Planner, q: &Query) -> (Vec<Journey>, Query) {
    let a = planner.plan(q);
    assert!(!a.cut_short, "cut short: {q:?}");
    for j in a.journeys.iter() {
        assert_eq!(impossible(planner, q, j), None, "{q:?}\n{j:?}");
    }
    let mut followed = q.clone();
    followed.limits.horizon = a.horizon;
    (a.journeys, followed)
}

#[test]
fn the_file_is_sound() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    tt.verify().unwrap();
    assert_eq!((tt.first_day(), tt.days(), tt.stops(), tt.trips()), (20261117, 2, 708, 7313));
    assert_eq!((tt.meta("feed_version"), tt.meta("licence:*"), tt.meta("licence:shapes")), (Some("9696"), Some("CC0-1.0"), Some("ODbL-1.0")));
}

#[test]
fn the_planner_and_the_scan_agree() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let mut compared = 0;
    for (day, time, seed) in [(0, 8 * 3600, 1u64), (0, 17 * 3600 + 1800, 2), (1, 23 * 3600, 3), (0, 600, 4)] {
        let scan = Reference::new(&p, day, time as i32 - 86_400, time as i32 + 2 * 86_400);
        for (i, (a, z)) in pairs(&tt, 40, seed).into_iter().enumerate() {
            // places of every kind: a stop, its station, the point it stands at
            let place = |s: u32| match i % 3 {
                0 => Place::Stop(s),
                1 => Place::Station(tt.station_of(s)),
                _ => Place::Point { lat: tt.stop(s).lat, lon: tt.stop(s).lon },
            };
            let mut q = Query::new(place(a), place(z), day, time);
            (q.rules, q.max_rides) = (false, 16);
            // patiently, as a query is by default, and every second pair as far as the horizon
            q.limits.patient = i % 2 == 0;
            let (one, followed) = asked(&mut p, &q);
            if one.iter().any(|j| j.rides == 0) {
                continue; // on foot all the way: the scan knows rides only
            }
            let earliest = scan.earliest_arrival(&p, &followed, time as i32);
            assert_eq!(one.iter().map(|j| j.arrive).min(), earliest, "{q:?}");
            // a patient search finds the same earliest arrival as one that follows everything
            let mut whole = q.clone();
            whole.limits.patient = false;
            assert_eq!(ask(&mut p, &whole).iter().map(|j| j.arrive).min(), earliest, "{q:?}");
            // the fewest rides can't be more than the fastest has, and each option has fewer rides or arrives sooner
            for w in one.windows(2) {
                assert!(w[0].arrive != w[1].arrive || w[0].rides != w[1].rides);
            }
            if let Some(arrive) = earliest {
                let mut back = q.clone();
                (back.arrive_by, back.time) = (true, arrive as u32);
                let (got, followed) = asked(&mut p, &back);
                assert_eq!(got.iter().map(|j| j.depart).max(), scan.latest_departure(&p, &followed, arrive), "{back:?}");
            }
            let mut window = q.clone();
            window.window = 3600;
            let (got, followed) = asked(&mut p, &window);
            assert_eq!(scan.window_differs(&p, &followed, &got), None, "{window:?}");
            // closed things, which the scan knows too: a kind of vehicle, a stop, an area ridden through
            let mid = tt.stop(served(&tt)[(i * 7) % served(&tt).len()]);
            for what in [
                What::Mode(if i % 2 == 0 { Mode::Bus } else { Mode::Rail }),
                What::Station { station: tt.station_of(served(&tt)[(i * 13) % served(&tt).len()]), through: i % 2 == 0 },
                What::Area { area: Area::Circle { lat: mid.lat, lon: mid.lon, radius_m: 600 }, through: true },
            ] {
                let mut never = q.clone();
                never.wishes.push(Wish { what, how: How::Never });
                let (got, followed) = asked(&mut p, &never);
                if !got.iter().any(|j| j.rides == 0) {
                    assert_eq!(got.iter().map(|j| j.arrive).min(), scan.earliest_arrival(&p, &followed, time as i32), "{never:?}");
                }
            }
            compared += 1;
        }
    }
    assert!(compared > 100, "{compared}");
}

#[test]
fn with_the_feeds_rules_every_journey_can_be_made() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    assert_eq!(tt.rules(), 160);
    let mut p = Planner::new(tt);
    let mut journeys = 0;
    for (a, z) in pairs(&tt, 150, 5) {
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), 0, 8 * 3600);
        q.window = 3600;
        journeys += ask(&mut p, &q).len();
        q.arrive_by = true;
        q.time = 10 * 3600;
        journeys += ask(&mut p, &q).len();
    }
    assert!(journeys > 1000, "{journeys}");
}

#[test]
fn wishes_close_and_cost_what_they_say() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let rail = |tt: &Timetable, j: &Journey| j.legs.iter().filter(|l| matches!(l, Leg::Ride { route, .. } if Mode::of(tt.line_type(tt.route(*route).line)) == Mode::Rail)).count() as u32;
    let (mut both, mut with_rail) = (0, 0);
    for (a, z) in pairs(&tt, 120, 6) {
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), 0, 8 * 3600);
        q.window = 1800;
        let plain = ask(&mut p, &q);
        q.wishes.push(Wish { what: What::Mode(Mode::Rail), how: How::Never });
        let never = ask(&mut p, &q);
        assert!(never.iter().all(|j| rail(&tt, j) == 0));
        q.wishes[0].how = How::Avoid(900);
        let avoid = ask(&mut p, &q);
        // each journey costs a quarter of an hour a rail ride, and nothing else
        assert!(avoid.iter().all(|j| j.penalty == 900 * rail(&tt, j)), "{q:?}");
        // the fastest is still there, and so is the fastest that follows the wish
        assert_eq!(avoid.iter().map(|j| j.arrive).min(), plain.iter().map(|j| j.arrive).min());
        assert_eq!(avoid.iter().filter(|j| j.penalty == 0).map(|j| j.arrive).min(), never.iter().map(|j| j.arrive).min(), "{q:?}");
        with_rail += plain.iter().any(|j| rail(&tt, j) > 0) as u32;
        both += (avoid.iter().any(|j| j.penalty > 0) && avoid.iter().any(|j| j.penalty == 0)) as u32;
        // rail preferred: the cost is what is not rail
        q.wishes[0].how = How::Prefer(60);
        assert!(ask(&mut p, &q).iter().all(|j| j.penalty == 60 * (j.rides - rail(&tt, j))));
    }
    assert!(with_rail > 5 && both > 5, "{with_rail} {both}");
}

/// The answers to a fixed set of questions, folded into one number: the same on every platform, or something in the
/// search depends on the machine.
#[test]
fn the_answers_are_the_same_everywhere() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let mut sum = 0xcbf2_9ce4_8422_2325u64;
    let mut fold = |v: u64| sum = (sum ^ v).wrapping_mul(0x100_0000_01b3);
    let mut journeys = 0;
    for (i, (a, z)) in pairs(&tt, 60, 7).into_iter().enumerate() {
        let (sa, sz) = (tt.stop(a), tt.stop(z));
        let mut q = Query::new(Place::Point { lat: sa.lat + 3000, lon: sa.lon - 2000 }, Place::Point { lat: sz.lat - 1000, lon: sz.lon + 4000 }, (i % 2) as i32, 7 * 3600 + 977 * i as u32);
        q.window = 2700;
        q.limits.options = 1024; // with wishes that cost, a short hop has a hundred options: all of them count here
        q.arrive_by = i % 3 == 0;
        q.wishes.push(Wish { what: What::Mode(Mode::Bus), how: How::Avoid(240) });
        q.wishes.push(Wish { what: What::Area { area: Area::Circle { lat: 520_900_000, lon: 51_100_000, radius_m: 700 }, through: false }, how: How::Avoid(300) });
        let got = ask(&mut p, &q);
        assert_eq!(got, ask(&mut p, &q));
        for j in got.iter() {
            journeys += 1;
            for v in [j.depart as u64, j.arrive as u64, j.rides as u64, j.penalty as u64, j.walking as u64] {
                fold(v);
            }
            for l in j.legs.iter() {
                if let Leg::Ride { trip, board, alight, day, .. } = l {
                    for v in [*trip as u64, *board as u64, *alight as u64, *day as u64] {
                        fold(v);
                    }
                }
            }
        }
    }
    assert_eq!((journeys, sum), (1171, 3_551_298_446_134_272_512));
}

#[test]
fn nothing_panics_on_files_a_little_wrong_or_queries_at_their_edges() {
    let mut rng = fuzz::Rng(0x5EED_1234_ABCD_0001);
    let mut opened = 0;
    for (name, runs) in [("tiny.tt", 6000), ("utrecht-20261117.tt", 300)] {
        let b = bytes(name);
        for _ in 0..runs / 10 {
            fuzz::file(&b, &mut rng, 4);
        }
        for _ in 0..runs {
            let wrong = fuzz::mutate(&b, &mut rng);
            opened += (fuzz::file(&wrong, &mut rng, 2) > 0) as u32;
            fuzz::one(&wrong[..wrong.len().min(64 + rng.below(4096) as usize)]);
        }
    }
    // enough of them still open that the search itself is reached, not only the checks at the door
    assert!(opened > 1000, "{opened}");
}

#[test]
fn a_search_stops_at_its_caps() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (a, z) = pairs(&tt, 1, 8)[0];
    let mut q = Query::new(Place::Stop(a), Place::Stop(z), 0, 8 * 3600);
    q.window = 7200;
    let whole = p.plan(&q);
    assert!(!whole.cut_short && whole.searches > 3);
    q.limits.steps = 10_000;
    let cut = p.plan(&q);
    assert!(cut.cut_short && cut.steps < 200_000, "{}", cut.steps);
    q.limits = Default::default();
    q.limits.labels = 1024;
    assert!(p.plan(&q).cut_short);
    q.limits = Default::default();
    q.limits.departures = 2;
    let few = p.plan(&q);
    assert!(few.cut_short && few.searches <= 3);
    q.limits = Default::default();
    q.limits.options = 1;
    assert_eq!(p.plan(&q).journeys.len(), 1);
}
