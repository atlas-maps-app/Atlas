//! The planner on a piece of the real timetable: tests/data/utrecht-20261117.tt, packed by ../pack/ttpack.py from
//! OVapi's feed of 16 November 2026 (version 9696; CC0) with `--first 20261117 --days 2 --box 52.06,5.07,52.12,5.16`:
//! the city of Utrecht, Tuesday and Wednesday, 708 stops, 7,313 trips. Day 0 is Tuesday 17 November.
//!
//! What is held here: the planner and the connection scan (src/reference.rs) agree, the scan asked as far as the
//! query's own horizon and holding to the feed's rules where the query does; every journey the planner returns can be
//! made as it says (reference::impossible, which reads the file and not the search); a patient search gives what a
//! search as far as the horizon gives; wishes close and cost what they say; the answers are the same on every
//! platform; and no file a little wrong, and no query, panics.

use atlas_transit::geo::distance_mm;
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
    let a = planner.plan(q);
    assert!(!a.cut_short, "cut short: {q:?}");
    for j in a.journeys.iter() {
        assert_eq!(impossible(planner, q, j), None, "{q:?}\n{j:?}");
    }
    a.journeys
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
    let (mut compared, mut latest, mut arriving, mut stepped, mut most_later) = (0, 0, 0, 0, 0);
    let (mut as_late, mut later_with_more) = (0, 0);
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
            // holding to the feed's rules, as a query does by default, and every fourth pair without them
            (q.rules, q.max_rides) = (i % 4 != 3, 16);
            // patiently, as a query is by default, and every second pair as far as the horizon; the scan always
            // as far as the query's own horizon
            q.limits.patient = i % 2 == 0;
            let one = ask(&mut p, &q);
            if one.iter().any(|j| j.rides == 0) {
                continue; // on foot all the way: the scan knows rides only
            }
            let later = p.plan(&q).later;
            (stepped, most_later) = (stepped + if later > 12 { one.len() } else { 0 }, most_later.max(later));
            let earliest = scan.earliest_arrival(&p, &q, time as i32);
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
                let got = ask(&mut p, &back);
                assert_eq!(got.iter().map(|j| j.depart).max(), scan.latest_departure(&p, &back, arrive), "{back:?}");
            }
            // each option leaves as late as any way to its arrival with no more rides: asked back from there
            for j in one.iter() {
                let mut back = q.clone();
                (back.arrive_by, back.time, back.max_rides) = (true, j.arrive as u32, j.rides);
                assert_eq!(ask(&mut p, &back).iter().map(|k| k.depart).max(), Some(j.depart), "{q:?}\n{j:?}");
                // The scan's latest departure for that arrival can't be held equal to the option's: the scan
                // knows no cap on rides, and a way with more rides may leave later. What can be held: asked back
                // with rides enough, the planner's latest departure is the scan's; and where that is later than
                // the option's, every way that leaves later than the option has more rides than it.
                back.max_rides = 16;
                let any = ask(&mut p, &back);
                let scanned = scan.latest_departure(&p, &back, j.arrive);
                assert_eq!(any.iter().map(|k| k.depart).max(), scanned, "{back:?}\n{j:?}");
                assert!(any.iter().all(|k| k.depart <= j.depart || k.rides > j.rides), "{back:?}\n{j:?}");
                (latest, as_late, later_with_more) = (latest + 1, as_late + (scanned == Some(j.depart)) as u32, later_with_more + (scanned > Some(j.depart)) as u32);
            }
            let mut window = q.clone();
            window.window = 3600;
            let got = ask(&mut p, &window);
            assert_eq!(scan.window_differs(&p, &window, &got), None, "{window:?}");
            // and a window asked to arrive in: the hour that ends half an hour after the earliest arrival
            if let Some(arrive) = earliest {
                let mut by = q.clone();
                (by.arrive_by, by.time, by.window) = (true, arrive as u32 + 1800, 3600);
                let got = ask(&mut p, &by);
                assert_eq!(scan.window_differs(&p, &by, &got), None, "{by:?}");
                arriving += got.len();
            }
            // closed things, which the scan knows too: a kind of vehicle, a stop, an area ridden through
            let mid = tt.stop(served(&tt)[(i * 7) % served(&tt).len()]);
            for what in [
                What::Mode(if i % 2 == 0 { Mode::Bus } else { Mode::Rail }),
                What::Station { station: tt.station_of(served(&tt)[(i * 13) % served(&tt).len()]), through: i % 2 == 0 },
                What::Area { area: Area::Circle { lat: mid.lat, lon: mid.lon, radius_m: 600 }, through: true },
            ] {
                let mut never = q.clone();
                never.wishes.push(Wish { what, how: How::Never });
                let got = ask(&mut p, &never);
                if !got.iter().any(|j| j.rides == 0) {
                    assert_eq!(got.iter().map(|j| j.arrive).min(), scan.earliest_arrival(&p, &never, time as i32), "{never:?}");
                }
            }
            compared += 1;
        }
    }
    println!("{compared} pairs compared, {latest} options asked back ({stepped} of them from an answer that stepped through its later moments, the most {most_later}), {arriving} options of windows asked to arrive in");
    println!("of the options asked back, {as_late} leave when the scan's latest way to their arrival does, and for {later_with_more} the scan's leaves later, with more rides");
    assert_eq!(as_late + later_with_more, latest);
    // (the options asked back are the proof that stepping through the later moments finds the latest: enough of
    // them come from an answer that stepped)
    assert!(compared > 100 && latest > 100 && arriving > 100 && stepped > 40, "{compared} {latest} {arriving} {stepped}");
}

/// The feed's rules, held to the best answer and not only to journeys that can be made: for each rule, a journey
/// that would use the change it speaks of (from the stop before on a trip it names, to the stop after on the other),
/// asked for one moment, to arrive by, and over half an hour, against the scan with the rules.
#[test]
fn the_feeds_rules_are_held_to_the_best_answer() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let scans = [Reference::new(&p, 0, -86_400, 3 * 86_400), Reference::new(&p, 1, -86_400, 3 * 86_400)];
    // a trip of a rule's side through its stop, running on a day of the file: (trip, where along it, the day)
    let trip_through = |stop: u32, trip: u32, line: u32, boarding: bool| -> Option<(u32, u32, i32)> {
        let (lo, hi) = tt.routes_at(stop);
        for e in lo..hi {
            let (r, pos) = tt.route_at(e);
            let route = tt.route(r);
            if (line != u32::MAX && route.line != line) || (boarding && pos + 1 >= route.stops) || (!boarding && pos == 0) {
                continue;
            }
            for t in route.first_trip..route.first_trip + route.trips {
                if trip == u32::MAX || trip == t {
                    if let Some(day) = (0..2).find(|d| tt.runs(tt.trip(t).service, *d)) {
                        return Some((t, pos, day));
                    }
                }
            }
        }
        None
    };
    let (mut compared, mut used, mut barred, mut matter, mut kinds) = (0, 0, 0, 0, [0u32; 6]);
    for i in 0..tt.rules() {
        let r = tt.rule(i);
        if r.from_trip == u32::MAX && r.to_trip == u32::MAX && r.from_line == u32::MAX && r.to_line == u32::MAX {
            continue; // for everyone: a walk, or the stop's own time to change
        }
        kinds[(r.kind as usize).min(5)] += 1;
        let (Some((t1, at1, day)), Some((t2, at2, _))) = (trip_through(r.from_stop, r.from_trip, r.from_line, false), trip_through(r.to_stop, r.to_trip, r.to_line, true)) else { continue };
        let (r1, r2) = (tt.route(tt.route_of_trip(t1)), tt.route(tt.route_of_trip(t2)));
        let (from, to) = (tt.pattern_stop(r1.first_stop + at1 - 1).stop, tt.pattern_stop(r2.first_stop + at2 + 1).stop);
        let time = tt.time(tt.trip_times(t1), at1 - 1).1.saturating_sub(40);
        let mut q = Query::new(Place::Stop(from), Place::Stop(to), day, time);
        q.max_rides = 16;
        q.limits.patient = i % 2 == 0;
        let scan = &scans[day as usize];
        let one = ask(&mut p, &q);
        if one.iter().any(|j| j.rides == 0) {
            continue;
        }
        let earliest = scan.earliest_arrival(&p, &q, time as i32);
        assert_eq!(one.iter().map(|j| j.arrive).min(), earliest, "rule {i} {r:?}: {q:?}");
        if let Some(arrive) = earliest {
            let mut back = q.clone();
            (back.arrive_by, back.time) = (true, arrive as u32);
            assert_eq!(ask(&mut p, &back).iter().map(|j| j.depart).max(), scan.latest_departure(&p, &back, arrive), "rule {i} {r:?}: {back:?}");
        }
        let mut window = q.clone();
        window.window = 1800;
        let got = ask(&mut p, &window);
        assert_eq!(scan.window_differs(&p, &window, &got), None, "rule {i} {r:?}: {window:?}");
        // and asked to arrive in the half hour that ends ten minutes after the earliest arrival
        if let Some(arrive) = earliest {
            (window.arrive_by, window.time) = (true, arrive as u32 + 600);
            let got = ask(&mut p, &window);
            assert_eq!(scan.window_differs(&p, &window, &got), None, "rule {i} {r:?}: {window:?}");
        }
        // whether an answer makes the change the rule speaks of, and whether the rules change the answer at all
        let makes = |j: &Journey| {
            let rides: Vec<(u32, u32, u32)> = j.legs.iter().filter_map(|l| if let Leg::Ride { trip, from, to, .. } = l { Some((*trip, *from, *to)) } else { None }).collect();
            rides.windows(2).any(|w| (w[0].0, w[0].2, w[1].0, w[1].1) == (t1, r.from_stop, t2, r.to_stop))
        };
        let made = one.iter().chain(got.iter()).any(makes);
        assert!(!(made && r.kind == 3), "rule {i} {r:?} bars a change that an answer makes: {q:?}");
        used += made as u32;
        let mut without = window.clone();
        without.rules = false;
        let free = ask(&mut p, &without);
        barred += (r.kind == 3 && free.iter().any(makes)) as u32;
        let pairs = |js: &[Journey]| js.iter().map(|j| (j.depart, j.arrive, j.rides)).collect::<Vec<_>>();
        matter += (pairs(&free) != pairs(&got)) as u32;
        compared += 1;
    }
    println!("{compared} rules compared; the change a rule speaks of is made in {used}; {barred} bar a change that is made without them; the rules change {matter} answers; kinds {kinds:?}");
    // (the file's rules are 54 changes the feed recommends, which bind nothing, and 106 it says are not possible)
    assert_eq!(kinds, [54, 0, 0, 106, 0, 0]);
    assert!(compared >= 40 && used > 5 && barred > 10 && matter > 10, "{compared} {used} {barred} {matter}");
}

/// What a patient search gives is what a search as far as the query's horizon gives: the same options, each leaving
/// and arriving at the same moments. (The patient one stops early for most journeys, and goes on only for what could
/// still be an option: the last moments of a window, a way with fewer rides.)
#[test]
fn a_patient_search_gives_what_the_whole_search_gives() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (mut options, mut sooner) = (0, 0);
    for (i, (a, z)) in pairs(&tt, 600, 21).into_iter().enumerate() {
        let time = [8 * 3600, 22 * 3600 + 1800, 13 * 3600, 5 * 3600, 23 * 3600 + 2400][i % 5] + 131 * (i as u32 % 9);
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), (i % 2) as i32, time);
        (q.window, q.arrive_by, q.limits.options, q.rules) = ([0, 3600, 0, 7200][i % 4], i % 3 == 1, 1024, i % 7 != 0);
        if i % 6 == 5 {
            q.wishes.push(Wish { what: What::Mode(Mode::Bus), how: How::Avoid(300) });
        }
        let patient = p.plan(&q);
        let mut whole = q.clone();
        whole.limits.patient = false;
        let all = p.plan(&whole);
        assert!(!patient.cut_short && !all.cut_short);
        let option = |j: &Journey| (j.depart, j.arrive, j.rides, j.penalty);
        assert_eq!(patient.journeys.iter().map(option).collect::<Vec<_>>(), all.journeys.iter().map(option).collect::<Vec<_>>(), "pair {i}: {q:?}");
        options += all.journeys.len();
        sooner += (patient.horizon < all.horizon) as u32;
    }
    assert!(options > 1500 && sooner > 400, "{options} {sooner}");
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
        let weighed = ask(&mut p, &q);
        q.weigh = false;
        let avoid = ask(&mut p, &q);
        q.weigh = true;
        // each journey costs a quarter of an hour a rail ride, and nothing else
        assert!(avoid.iter().chain(weighed.iter()).all(|j| j.penalty == 900 * rail(&tt, j)), "{q:?}");
        // the fastest is still there, and with every option so is the fastest that follows the wish
        assert_eq!(avoid.iter().map(|j| j.arrive).min(), plain.iter().map(|j| j.arrive).min());
        assert_eq!(weighed.iter().map(|j| j.arrive).min(), plain.iter().map(|j| j.arrive).min());
        assert_eq!(avoid.iter().filter(|j| j.penalty == 0).map(|j| j.arrive).min(), never.iter().map(|j| j.arrive).min(), "{q:?}");
        // weighing leaves out options, and adds none
        assert!(weighed.len() <= avoid.len() && weighed.iter().all(|j| avoid.iter().any(|o| (o.depart, o.arrive, o.rides, o.penalty) == (j.depart, j.arrive, j.rides, j.penalty))), "{q:?}");
        with_rail += plain.iter().any(|j| rail(&tt, j) > 0) as u32;
        both += (avoid.iter().any(|j| j.penalty > 0) && avoid.iter().any(|j| j.penalty == 0)) as u32;
        // rail preferred: the cost is what is not rail
        q.wishes[0].how = How::Prefer(60);
        assert!(ask(&mut p, &q).iter().all(|j| j.penalty == 60 * (j.rides - rail(&tt, j))));
    }
    assert!(with_rail > 5 && both > 5, "{with_rail} {both}");
}

/// A journey as the rule that weighs wishes sees it (query.rs's head): how early it leaves (over a window), how
/// late it arrives, its rides, and its arrival with its cost added. Asked to arrive by, time is turned round.
fn weight(j: &Journey, q: &Query) -> [i64; 4] {
    let (leave, reach) = if q.arrive_by { (-j.arrive, -j.depart) } else { (j.depart, j.arrive) };
    [if q.window == 0 { 0 } else { -(leave as i64) }, reach as i64, j.rides as i64, reach as i64 + j.penalty as i64]
}

/// An option as far as the rule tells it from another.
fn option(j: &Journey, q: &Query) -> (i32, i32, u32, u32) {
    match (q.window, q.arrive_by) {
        (0, false) => (0, j.arrive, j.rides, j.penalty),
        (0, true) => (j.depart, 0, j.rides, j.penalty),
        _ => (j.depart, j.arrive, j.rides, j.penalty),
    }
}

/// Two windows one after the other give what one window over both gives: what counts against a journey is every
/// journey that leaves no earlier, inside the window or after it. Each window is cut in two at the very moment one
/// of its options leaves (arriving by: is over), which is where a search from the window's far end could go wrong.
#[test]
fn two_windows_give_what_one_over_both_gives() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let said = |js: &[Journey]| {
        let mut v: Vec<(i32, i32, u32, u32)> = js.iter().filter(|j| j.rides > 0).map(|j| (j.depart, j.arrive, j.rides, j.penalty)).collect();
        v.sort_unstable();
        v
    };
    let (mut cuts, mut options, mut at_the_cut) = (0, 0, 0);
    for (i, (a, z)) in pairs(&tt, 150, 31).into_iter().enumerate() {
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), (i % 2) as i32, 7 * 3600 + 409 * i as u32);
        q.arrive_by = i % 3 == 1;
        q.window = 3600 + 60 * (i as u32 % 45);
        q.rules = i % 5 != 0;
        q.limits.patient = i % 7 != 0;
        if i % 4 == 3 {
            q.wishes = vec![Wish { what: What::Mode(Mode::Rail), how: How::Avoid(600) }];
        }
        let whole = said(&ask(&mut p, &q));
        // the moments to cut at, as seconds into the window: where an option leaves, and a second before
        let mut at: Vec<u32> = whole.iter().map(|o| if q.arrive_by { q.time - o.1 as u32 } else { o.0 as u32 - q.time }).collect();
        at.dedup();
        for (k, into) in at.into_iter().enumerate().filter(|(k, _)| k % 3 == i % 3).flat_map(|(k, t)| [(k, t), (k, t.wrapping_sub(1))]) {
            if into >= q.window {
                continue; // (the window's own last moment: nothing comes after it)
            }
            let (mut near, mut far) = (q.clone(), q.clone());
            near.window = into;
            (far.time, far.window) = (if q.arrive_by { q.time - into - 1 } else { q.time + into + 1 }, q.window - into - 1);
            let mut both = said(&ask(&mut p, &near));
            at_the_cut += both.iter().filter(|o| if q.arrive_by { q.time - o.1 as u32 == into } else { o.0 as u32 - q.time == into }).count();
            both.extend(said(&ask(&mut p, &far)));
            both.sort_unstable();
            assert_eq!(both, whole, "pair {i}, cut {k} at {into} s: {q:?}");
            cuts += 1;
            options += whole.len();
        }
    }
    assert!(cuts > 150 && options > 1000 && at_the_cut > 60, "{cuts} {options} {at_the_cut}");
}

/// The same between two points near enough to walk all the way: the walk can begin at any moment, so each window
/// has it (once, from its own first moment) and the rides are the same however the window is cut. A ride that takes
/// no less time than the walk is no option, wherever in a window it leaves.
#[test]
fn two_windows_between_points_give_the_rides_one_over_both_gives() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (stops, mut rng) = (served(&tt), fuzz::Rng(41));
    let rides = |js: &[Journey]| {
        let mut v: Vec<(i32, i32, u32, u32)> = js.iter().filter(|j| j.rides > 0).map(|j| (j.depart, j.arrive, j.rides, j.penalty)).collect();
        v.sort_unstable();
        v
    };
    let on_foot = |js: &[Journey]| js.iter().filter(|j| j.rides == 0).map(|j| (j.depart, j.arrive)).collect::<Vec<_>>();
    let (mut cuts, mut options, mut slower) = (0, 0, 0);
    for i in 0..200usize {
        // two stops 600 m to 1.9 km apart, and a point beside each
        let a = stops[rng.below(stops.len() as u64) as usize];
        let sa = tt.stop(a);
        let near: Vec<u32> = stops.iter().copied().filter(|z| (600_000..1_900_000).contains(&distance_mm(sa.lat, sa.lon, tt.stop(*z).lat, tt.stop(*z).lon))).collect();
        if near.is_empty() {
            continue;
        }
        let sz = tt.stop(near[rng.below(near.len() as u64) as usize]);
        let mut q = Query::new(Place::Point { lat: sa.lat + 2000, lon: sa.lon - 3000 }, Place::Point { lat: sz.lat - 1000, lon: sz.lon + 2000 }, (i % 2) as i32, 7 * 3600 + 409 * i as u32);
        q.arrive_by = i % 3 == 1;
        q.window = 3600 + 60 * (i as u32 % 45);
        q.reach_m = [1000, 600, 1500][i % 3];
        (q.limits.patient, q.limits.options) = (i % 7 != 0, 1024);
        if i % 4 == 3 {
            q.wishes = vec![Wish { what: What::Mode(Mode::Bus), how: How::Avoid(300) }];
        }
        let all = ask(&mut p, &q);
        let (whole, walk) = (rides(&all), on_foot(&all));
        if walk.is_empty() {
            continue; // further apart than twice the reach
        }
        // the walk is there once, from the asked moment
        let secs = walk[0].1 - walk[0].0;
        assert_eq!(walk, [if q.arrive_by { (q.time as i32 - secs, q.time as i32) } else { (q.time as i32, q.time as i32 + secs) }], "pair {i}: {q:?}");
        // what the walk leaves out: of the rides the same query finds between the two points' stops with their
        // seconds on foot (where nobody walks all the way), exactly those that take no less time than the walk
        let ends = p.ends(&q);
        let mut by_stops = q.clone();
        (by_stops.from, by_stops.to) = (Place::Walks(ends.from), Place::Walks(ends.to));
        let without = rides(&ask(&mut p, &by_stops));
        assert_eq!(whole, without.iter().copied().filter(|o| o.1 - o.0 < secs).collect::<Vec<_>>(), "pair {i}: {q:?}");
        slower += without.len() - whole.len();
        let mut at: Vec<u32> = whole.iter().map(|o| if q.arrive_by { q.time - o.1 as u32 } else { o.0 as u32 - q.time }).collect();
        at.dedup();
        for (k, into) in at.into_iter().enumerate().filter(|(k, _)| k % 2 == i % 2).flat_map(|(k, t)| [(k, t), (k, t.wrapping_sub(1))]) {
            if into >= q.window {
                continue;
            }
            let (mut near, mut far) = (q.clone(), q.clone());
            near.window = into;
            (far.time, far.window) = (if q.arrive_by { q.time - into - 1 } else { q.time + into + 1 }, q.window - into - 1);
            let (first, second) = (ask(&mut p, &near), ask(&mut p, &far));
            let mut both = rides(&first);
            both.extend(rides(&second));
            both.sort_unstable();
            assert_eq!(both, whole, "pair {i}, cut {k} at {into} s: {q:?}");
            assert_eq!((on_foot(&first).len(), on_foot(&second).len()), (1, 1), "pair {i}, cut {k} at {into} s: {q:?}");
            cuts += 1;
            options += whole.len();
        }
    }
    println!("{cuts} cuts, {options} options, {slower} rides no faster than the walk");
    assert!(cuts > 150 && options > 600 && slower > 100, "{cuts} {options} {slower}");
}

/// The search that weighs wishes gives what the rule leaves of every option: asked without weighing (and so as far
/// as the query's horizon, which a search with every option always is), with every option from the window's last
/// moment on (what leaves after the window can beat what leaves inside it; what leaves at that moment is the
/// window's own, and beats itself no more than any other does), each option is held against all the others by the
/// rule alone.
#[test]
fn weighing_gives_what_the_rule_leaves_of_every_option() {
    weighing_with_a_bag_of(16);
}

/// The same with the bag a query has when it says nothing.
#[test]
fn weighing_with_the_bag_a_query_has() {
    weighing_with_a_bag_of(Query::new(Place::Stop(0), Place::Stop(0), 0, 0).limits.bag);
}

fn weighing_with_a_bag_of(bag: u32) {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (mut options, mut left_out) = (0, 0);
    for (i, (a, z)) in pairs(&tt, 240, 8).into_iter().enumerate() {
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), (i % 2) as i32, 6 * 3600 + 613 * i as u32);
        q.arrive_by = i % 3 == 1;
        q.window = [0, 2400, 0, 5400][i % 4];
        q.rules = i % 5 != 0;
        q.limits.patient = i % 7 != 0;
        q.limits.options = 1024;
        q.limits.bag = bag;
        q.wishes = match i % 6 {
            0 | 3 => vec![Wish { what: What::Mode(Mode::Rail), how: How::Avoid(900) }],
            1 | 4 => vec![
                Wish { what: What::Mode(Mode::Bus), how: How::Avoid(240) },
                Wish { what: What::Area { area: Area::Circle { lat: 520_900_000, lon: 51_100_000, radius_m: 700 }, through: i % 2 == 0 }, how: How::Avoid(300) },
            ],
            2 => vec![Wish { what: What::Mode(Mode::Tram), how: How::Prefer(180) }, Wish { what: What::NoWheelchair, how: How::Avoid(120) }],
            _ => vec![Wish { what: What::Mode(Mode::Bus), how: How::Avoid(30) }],
        };
        let weighed = ask(&mut p, &q);
        let mut every = q.clone();
        every.weigh = false;
        let all = ask(&mut p, &every);
        let mut edge = every.clone();
        (edge.window, edge.time) = (0, if q.arrive_by { q.time - q.window } else { q.time + q.window });
        let beyond = if q.window > 0 { ask(&mut p, &edge) } else { Vec::new() };
        let mut left: Vec<_> = all
            .iter()
            .filter(|j| {
                let w = weight(j, &q);
                !all.iter().chain(beyond.iter()).any(|o| weight(o, &q) != w && weight(o, &q).iter().zip(w.iter()).all(|(x, y)| x <= y))
            })
            .map(|j| option(j, &q))
            .collect();
        left.sort_unstable();
        left.dedup();
        let mut got: Vec<_> = weighed.iter().map(|j| option(j, &q)).collect();
        got.sort_unstable();
        assert_eq!(got, left, "pair {i}: {q:?}");
        options += got.len();
        left_out += all.len() - left.len().min(all.len());
    }
    assert!(options > 400 && left_out > 50, "{options} {left_out}");
}

/// Wishes that cost, held to a second opinion of their own: the scan that carries a cost beside the time
/// (`Reference::cheapest`), which works out what a wish costs from the wish list, a wish and a ride at a time.
/// Wishes of every kind, avoided and preferred, alone and several at once; leaving at a moment with every option
/// and weighed, and arriving by one with every option. And every journey's cost is what the scan's own reading of
/// its legs comes to (`impossible`).
#[test]
fn wishes_with_a_cost_are_held_to_the_scan() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let scans = [Reference::new(&p, 0, -86_400, 3 * 86_400), Reference::new(&p, 1, -86_400, 3 * 86_400)];
    let stops = served(&tt);
    let (mut asked, mut cut, mut options, mut several, mut costly, mut differ) = (0, 0, 0, 0, 0, Vec::new());
    for (i, (a, z)) in pairs(&tt, 180, 81).into_iter().enumerate() {
        let day = i % 2;
        let place = |s: u32| if i % 3 == 0 { Place::Point { lat: tt.stop(s).lat + 1500, lon: tt.stop(s).lon - 1000 } } else { Place::Stop(s) };
        let mut q = Query::new(place(a), place(z), day as i32, 6 * 3600 + 547 * (i % 100) as u32);
        (q.max_rides, q.limits.options, q.limits.bag, q.rules) = (16, 1024, 16, i % 5 != 0);
        // things to wish about, taken from the file by the pair's number
        let (mid, other) = (tt.stop(stops[(i * 7) % stops.len()]), stops[(i * 13) % stops.len()]);
        let trip = (i as u32 * 97) % tt.trips();
        let line = tt.route(tt.route_of_trip(trip)).line;
        let wish = |what: What, how: How| Wish { what, how };
        q.wishes = match i % 6 {
            0 => vec![wish(What::Mode(Mode::Rail), How::Avoid(900))],
            1 => vec![
                wish(What::Mode(Mode::Bus), How::Avoid(240)),
                wish(What::Area { area: Area::Circle { lat: mid.lat, lon: mid.lon, radius_m: 900 }, through: i % 4 == 1 }, How::Avoid(300)),
                wish(What::NoWheelchair, How::Avoid(120)),
            ],
            2 => vec![wish(What::Mode(Mode::Tram), How::Prefer(180)), wish(What::Mode(Mode::Bus), How::Avoid(30))],
            3 => vec![
                wish(What::Station { station: tt.station_of(other), through: true }, How::Avoid(200)),
                wish(What::Operator(tt.line_operator(line)), How::Avoid(150)),
                wish(What::BicyclesUnknown, How::Avoid(60)),
            ],
            4 => vec![
                wish(What::Line(line), How::Prefer(120)),
                wish(What::Stop { stop: other, through: false }, How::Avoid(400)),
                wish(What::Kind("Sprinter".into()), How::Avoid(300)),
                wish(What::Area { area: Area::Box { south: mid.lat - 60_000, west: mid.lon - 90_000, north: mid.lat + 60_000, east: mid.lon + 90_000 }, through: false }, How::Prefer(45)),
            ],
            _ => vec![
                wish(What::WheelchairUnknown, How::Avoid(75)),
                wish(What::Trip(trip), How::Avoid(600)),
                wish(What::NoBicycles, How::Prefer(20)),
                wish(What::Station { station: tt.station_of(other), through: false }, How::Never),
                wish(What::Area { area: Area::Circle { lat: mid.lat, lon: mid.lon, radius_m: 500 }, through: true }, How::Prefer(15)),
            ],
        };
        for (by, weigh) in [(false, false), (false, true), (true, false)] {
            let mut q = q.clone();
            (q.arrive_by, q.weigh) = (by, weigh);
            if by {
                q.time += 5400;
            }
            let got = p.plan(&q);
            if got.cut_short {
                cut += 1;
                continue;
            }
            for j in got.journeys.iter() {
                assert_eq!(impossible(&p, &q, j), None, "{q:?}\n{j:?}");
            }
            if let Some(how) = scans[day].costs_differ(&p, &q, &got.journeys) {
                differ.push(format!("pair {i} ({a} to {z}), arriving by {by}, weighed {weigh}: {how}"));
            }
            let mut costs: Vec<u32> = got.journeys.iter().map(|j| j.penalty).collect();
            costs.sort_unstable();
            costs.dedup();
            (asked, options, several, costly) = (asked + 1, options + got.journeys.len(), several + (costs.len() > 1) as u32, costly + (costs.last().is_some_and(|c| *c > 0)) as u32);
        }
    }
    println!("{asked} answers with wishes held to the scan with a cost ({cut} cut short and left out): {options} options, {costly} answers with a cost, {several} with options of several costs");
    assert!(differ.is_empty(), "{} differ:\n{}", differ.len(), differ[..differ.len().min(12)].join("\n"));
    assert!(asked > 450 && costly > 200 && several > 60, "{asked} {costly} {several}");
}

/// Where trips of one route differ in cost, a boarding goes on to each later trip that is cheaper than those before
/// it, however many costlier ones stand between (it looked at 32 and no further: the answer then lacked the option
/// that waits for the cheaper trip, and did not say so). Here 40 trips in a row of a busy route are each avoided,
/// from one of its stops to the next; every option asked for. The scan with a cost says what the options are.
#[test]
fn more_costly_trips_in_a_row_than_a_boarding_looks_at() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let scan = Reference::new(&p, 0, -86_400, 3 * 86_400);
    let mut routes: Vec<u32> = (0..tt.routes()).collect();
    routes.sort_by_key(|r| (core::cmp::Reverse(tt.route(*r).trips), *r));
    let (mut asked, mut whole, mut said, mut silent) = (0, 0, 0, Vec::new());
    for &r in routes.iter().take(12) {
        let route = tt.route(r);
        // the trips that run on the first day, as they leave; the fifth of them and the 39 after it are avoided
        let running: Vec<u32> = (route.first_trip..route.first_trip + route.trips).filter(|t| tt.runs(tt.trip(*t).service, 0)).collect();
        if running.len() < 60 {
            continue;
        }
        for pos in [0, route.stops / 2] {
            let (from, to) = (tt.pattern_stop(route.first_stop + pos).stop, tt.pattern_stop(route.first_stop + pos + 1).stop);
            let leaves = tt.time(tt.trip_times(running[4]), pos).1;
            let mut q = Query::new(Place::Stop(from), Place::Stop(to), 0, leaves.saturating_sub(120));
            (q.max_rides, q.limits.options, q.limits.bag, q.weigh) = (16, 1024, 16, false);
            q.wishes = running[4..44].iter().map(|t| Wish { what: What::Trip(*t), how: How::Avoid(60) }).collect();
            let got = p.plan(&q);
            for j in got.journeys.iter() {
                assert_eq!(impossible(&p, &q, j), None, "{q:?}\n{j:?}");
            }
            asked += 1;
            if got.cut_short {
                said += 1;
            } else if let Some(how) = scan.costs_differ(&p, &q, &got.journeys) {
                silent.push(format!("route {r} from its stop {pos}: {how}"));
            } else {
                whole += 1;
            }
        }
    }
    println!("{asked} questions with 40 trips in a row avoided: {whole} answers are whole, {said} say they are not, {} lack an option and do not say so", silent.len());
    assert!(silent.is_empty(), "{} answers that lack an option and do not say so:\n{}", silent.len(), silent[..silent.len().min(8)].join("\n"));
    assert!(asked >= 10 && whole >= 10, "{asked} {whole}");
}

/// A stop keeps so many labels a round and no more ([`atlas_transit::Limits::bag`]); the one that goes when a bag
/// is full may have led to an option. An answer found with a bag that overflowed says so: wherever a smaller bag
/// gives other options than the largest, the answer is marked as cut short.
#[test]
fn a_full_bag_says_so() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (mut asked, mut lost, mut said, mut silent, mut sixteen) = ([0u32; 4], [0u32; 4], [0u32; 4], Vec::new(), 0);
    for (i, (a, z)) in pairs(&tt, 240, 71).into_iter().enumerate() {
        let (sa, sz) = (tt.stop(a), tt.stop(z));
        let place = |s: u32, st: atlas_transit::file::Stop| if i % 3 == 0 { Place::Stop(s) } else { Place::Point { lat: st.lat + 2500, lon: st.lon - 1500 } };
        let mut q = Query::new(place(a, sa), place(z, sz), (i % 2) as i32, 6 * 3600 + 613 * i as u32);
        (q.arrive_by, q.window, q.limits.options, q.weigh) = (i % 3 == 1, [0, 2400, 5400][i % 3], 1024, i % 2 == 0);
        q.wishes = match i % 4 {
            0 => vec![Wish { what: What::Mode(Mode::Rail), how: How::Avoid(900) }],
            1 | 2 => vec![
                Wish { what: What::Mode(Mode::Bus), how: How::Avoid(240) },
                Wish { what: What::Area { area: Area::Circle { lat: 520_900_000, lon: 51_100_000, radius_m: 700 }, through: i % 2 == 0 }, how: How::Avoid(300) },
                Wish { what: What::NoWheelchair, how: How::Avoid(120) },
            ],
            _ => vec![Wish { what: What::Mode(Mode::Tram), how: How::Prefer(180) }, Wish { what: What::Mode(Mode::Bus), how: How::Avoid(30) }],
        };
        q.limits.bag = 16;
        let whole = p.plan(&q);
        if whole.cut_short {
            sixteen += 1;
            continue; // (even sixteen were too few: nothing to hold the others against)
        }
        let options = |js: &[Journey]| js.iter().map(|j| (j.depart, j.arrive, j.rides, j.penalty)).collect::<Vec<_>>();
        for (k, bag) in [1, 2, 4, 8].into_iter().enumerate() {
            q.limits.bag = bag;
            let got = p.plan(&q);
            asked[k] += 1;
            said[k] += got.cut_short as u32;
            if options(&got.journeys) != options(&whole.journeys) {
                lost[k] += 1;
                if !got.cut_short {
                    silent.push(format!("pair {i} with a bag of {bag}: {} options where sixteen give {}", got.journeys.len(), whole.journeys.len()));
                }
            }
        }
    }
    println!("bags of 1, 2, 4, 8: asked {asked:?}, with other options than a bag of 16 gives {lost:?}, saying cut short {said:?}; a bag of 16 was too small for {sixteen}");
    assert!(silent.is_empty(), "{} answers that lack an option and do not say so:\n{}", silent.len(), silent[..silent.len().min(10)].join("\n"));
    assert!(asked[0] > 200 && lost[0] > 20, "{asked:?} {lost:?}");
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
    let (mut journeys, mut one_moment, mut stepped, mut most_later, mut asked_back) = (0, 0, 0, 0, 0);
    for (i, (a, z)) in pairs(&tt, 60, 7).into_iter().enumerate() {
        let (sa, sz) = (tt.stop(a), tt.stop(z));
        let mut q = Query::new(Place::Point { lat: sa.lat + 3000, lon: sa.lon - 2000 }, Place::Point { lat: sz.lat - 1000, lon: sz.lon + 4000 }, (i % 2) as i32, 7 * 3600 + 977 * i as u32);
        q.window = if i % 5 == 4 { 0 } else { 2700 };
        q.limits.options = 1024; // with wishes that cost and every option asked for, a short hop has a hundred
        q.arrive_by = i % 3 == 0;
        q.wishes.push(Wish { what: What::Mode(Mode::Bus), how: How::Avoid(240) });
        q.wishes.push(Wish { what: What::Area { area: Area::Circle { lat: 520_900_000, lon: 51_100_000, radius_m: 700 }, through: false }, how: How::Avoid(300) });
        q.weigh = i % 4 != 1;
        let got = ask(&mut p, &q);
        assert_eq!(got, ask(&mut p, &q));
        let later = p.plan(&q).later;
        (one_moment, stepped, most_later) = (one_moment + (q.window == 0) as u32, stepped + (later > 12) as u32, most_later.max(later));
        // Asked to leave at one moment, each option leaves as late as any way to its arrival with no more rides
        // and at no more cost: asked back from there with every option, the same departure comes out.
        if q.window == 0 && !q.arrive_by {
            for j in got.iter().filter(|j| j.rides > 0) {
                let mut back = q.clone();
                (back.arrive_by, back.time, back.max_rides, back.weigh) = (true, j.arrive as u32, j.rides, false);
                let latest = ask(&mut p, &back).iter().filter(|k| k.penalty <= j.penalty).map(|k| k.depart).max();
                assert_eq!(latest, Some(j.depart), "{q:?}\n{j:?}");
                asked_back += (later > 12) as u32;
            }
        }
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
    // The number also stands for the search for a later departure where it steps through the moments (more than 12
    // of them, `Answer::later`): most of the questions for one moment do.
    println!("{one_moment} of the fixed questions ask for one moment; {stepped} of them stepped through the later moments (the most: {most_later}); {asked_back} of their options asked back");
    assert!(one_moment == 12 && stepped >= 8 && most_later > 100 && asked_back >= 8, "{one_moment} {stepped} {most_later} {asked_back}");
    assert_eq!((journeys, sum), (592, 18_032_016_647_273_654_747));
}

#[test]
fn nothing_panics_on_files_a_little_wrong_or_queries_at_their_edges() {
    let mut rng = fuzz::Rng(0x5EED_1234_ABCD_0001);
    let mut opened = 0;
    // (edges.tt has what the others lack: a night the clock changes, a stop with its own time to change; autumn.tt
    // the night it goes back and a stop where nobody changes)
    for (name, runs) in [("tiny.tt", 6000), ("edges.tt", 2000), ("utrecht-20261117.tt", 300), ("autumn.tt", 2000)] {
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

/// Whatever cap a search meets, and wherever in it, the answer says so: an answer that is not the whole one is
/// marked as cut short. Each query is asked with its steps capped at every twentieth of what the whole search took,
/// and with few labels: so the cap falls in the first search, in the count from the journey's end, between two
/// searches, and in the search for a later departure.
#[test]
fn an_answer_that_is_not_whole_says_so() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (mut asked, mut short, mut silent) = (0, 0, Vec::new());
    for (i, (a, z)) in pairs(&tt, 90, 51).into_iter().enumerate() {
        let mut q = Query::new(Place::Stop(a), Place::Stop(z), 0, 8 * 3600 + 311 * i as u32);
        (q.window, q.arrive_by, q.limits.options, q.limits.patient) = ([0, 7200, 3600][i % 3], i % 4 == 1, 1024, i % 7 != 3);
        if i % 5 == 4 {
            q.wishes.push(Wish { what: What::Mode(Mode::Bus), how: How::Avoid(240) });
        }
        let whole = p.plan(&q);
        assert!(!whole.cut_short, "{q:?}");
        let mut caps: Vec<(u64, u32)> = (1..20).map(|k| ((whole.steps * k / 20).max(10_000), q.limits.labels)).collect();
        caps.extend([1024, 1500, 2500, 5000, 12_000].map(|labels| (q.limits.steps, labels)));
        caps.dedup();
        for (steps, labels) in caps {
            let mut capped = q.clone();
            (capped.limits.steps, capped.limits.labels) = (steps, labels);
            let got = p.plan(&capped);
            asked += 1;
            if got.journeys != whole.journeys {
                short += 1;
                if !got.cut_short {
                    silent.push(format!("pair {i} ({a} to {z}, window {}, {} steps of {}, {labels} labels): {} journeys where the whole answer has {}", q.window, steps, whole.steps, got.journeys.len(), whole.journeys.len()));
                }
            }
        }
    }
    println!("{asked} capped searches, {short} with a short answer, {} of them silent", silent.len());
    assert!(silent.is_empty(), "{} short answers that say they are whole, of {short}:\n{}", silent.len(), silent[..silent.len().min(12)].join("\n"));
    assert!(short > 300, "{short} of {asked}");
}

/// Asked for one moment, the search for a later departure is held to the query's limits like any other: it takes
/// no more searches than `departures`, says so where that stops it short (an option then leaves sooner than it
/// need, and makes the same arrival), and looks for no journey that `options` leaves out. Of more options than
/// that the fastest stay, which is settled before any is searched again: leaving later changes no arrival.
#[test]
fn the_search_for_a_later_departure_is_held_to_the_limits() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (mut for_all, mut for_one, mut stopped, mut most, mut asked) = (0, 0, 0, 0, 0);
    for (i, (a, z)) in pairs(&tt, 120, 61).into_iter().enumerate() {
        // from a stop, or from a point with every stop within a kilometre: many moments at which something leaves
        let place = |s: u32| if i % 3 == 0 { Place::Stop(s) } else { Place::Point { lat: tt.stop(s).lat + 1500, lon: tt.stop(s).lon - 2500 } };
        let mut q = Query::new(place(a), place(z), 0, [8 * 3600, 17 * 3600 + 900][i % 2] + 67 * i as u32);
        (q.arrive_by, q.limits.options) = (i % 5 == 1, 1024);
        // wishes that cost, with every option asked for at two pairs in four: a handful of options a pair
        if i % 4 < 3 {
            q.wishes.push(Wish { what: What::Mode(Mode::Bus), how: How::Avoid(240) });
            q.wishes.push(Wish { what: What::Area { area: Area::Circle { lat: 520_900_000, lon: 51_100_000, radius_m: 700 }, through: false }, how: How::Avoid(300) });
            q.weigh = i % 4 == 0;
        }
        let whole = p.plan(&q);
        assert!(!whole.cut_short, "{q:?}");
        if whole.journeys.is_empty() {
            continue;
        }
        // an option by what leaving later does not change: where it ends (arriving by: begins), its rides, its cost
        let end = |j: &Journey| (if q.arrive_by { -j.depart } else { j.arrive }, j.rides, j.penalty);
        let ends = |js: &[Journey]| {
            let mut v: Vec<_> = js.iter().map(end).collect();
            v.sort_unstable();
            v
        };
        // two searches for later departures and no more, after the four at most that find the options (the first
        // pass and its second, the bags, the fewer rides)
        let mut few = q.clone();
        few.limits.departures = 2;
        let got = p.plan(&few);
        assert!(got.searches <= 4 + 2, "pair {i}: {} searches with departures 2 (the whole answer took {}): {q:?}", got.searches, whole.searches);
        assert_eq!(ends(&got.journeys), ends(&whole.journeys), "pair {i}: {q:?}");
        for j in got.journeys.iter() {
            assert_eq!(impossible(&p, &few, j), None, "pair {i}: {q:?}");
        }
        if got.journeys != whole.journeys {
            assert!(got.cut_short, "pair {i}: an option leaves sooner than it need and the answer does not say so: {q:?}");
            stopped += 1;
        }
        // one option: the fastest, leaving as late as it can, and no search for any other
        let mut one = q.clone();
        one.limits.options = 1;
        let got = p.plan(&one);
        let fastest = whole.journeys.iter().min_by_key(|j| end(j)).unwrap();
        assert_eq!(got.journeys, [fastest.clone()], "pair {i}: {q:?}");
        assert_eq!(got.cut_short, whole.journeys.len() > 1, "pair {i}: {q:?}");
        // (for a single pair it may take a search more than for all: how far apart the moments tried lie depends
        // on the journeys looked for)
        (for_all, for_one, most, asked) = (for_all + whole.searches, for_one + got.searches, most.max(whole.searches), asked + 1);
    }
    println!("{asked} pairs: {for_all} searches for every option (the most {most}), {for_one} for one; {stopped} stopped short by departures");
    // (on Utrecht a pair has one to eight options at one moment, most of them one or two: a third fewer searches)
    assert!(asked > 100 && stopped > 10 && most > 20 && for_one * 5 < for_all * 4, "{asked} {stopped} {most} {for_one} {for_all}");
}

/// Late in the evening a window's last connections take long: the next ride from a change may be an hour off, or
/// the next morning's. They are options all the same, and the answer has them: compared with the scan as far as the
/// query's own horizon. (A window's answer lacked them until 18 November 2026: the search stopped where the faster
/// journeys of the day could not end later, and the comparisons asked the scan only that far.) And the same turned
/// round: early in the morning a window asked to arrive in has the first connections, which may have left the
/// evening before.
#[test]
fn a_late_window_has_its_last_connections() {
    let b = bytes("utrecht-20261117.tt");
    let tt = Timetable::open(&b).unwrap();
    let mut p = Planner::new(tt);
    let (mut compared, mut past, mut shown, mut arriving) = (0, 0, 0, 0);
    for (day, time, window, seed, by) in [
        (0, 22 * 3600, 7200, 11u64, false),
        (0, 23 * 3600 + 900, 3600, 12, false),
        (1, 21 * 3600 + 1800, 10_800, 13, false),
        (0, 4 * 3600, 5400, 14, false),
        (0, 6 * 3600 + 1800, 5400, 15, true),
        (1, 5 * 3600, 7200, 16, true),
        (1, 3600, 3600, 17, true),
        (0, 23 * 3600 + 1800, 10_800, 18, true),
    ] {
        let scan = Reference::new(&p, day, time as i32 - 86_400, time as i32 + 2 * 86_400);
        for (a, z) in pairs(&tt, 150, seed) {
            let mut q = Query::new(Place::Stop(a), Place::Stop(z), day, time);
            (q.rules, q.max_rides, q.window, q.limits.options, q.arrive_by) = (false, 16, window, 1024, by);
            let got = p.plan(&q);
            assert!(!got.cut_short, "{q:?}");
            if got.journeys.iter().any(|j| j.rides == 0) {
                continue;
            }
            let differs = scan.window_differs(&p, &q, &got.journeys);
            if differs.is_some() && shown < 5 {
                shown += 1;
                println!("{a} to {z} day {day} at {time} over {window}: followed {} of {}: {}", got.horizon, q.limits.horizon, differs.clone().unwrap());
            }
            past += differs.is_some() as u32;
            compared += 1;
            arriving += (by && !got.journeys.is_empty()) as u32;
        }
    }
    println!("{compared} windows compared, {arriving} of them asked to arrive in and with a journey");
    assert_eq!(past, 0, "of {compared}");
    assert!(compared > 800 && arriving > 200, "{compared} {arriving}");
}
