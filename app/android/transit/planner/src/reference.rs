//! The second opinion: a connection scan (Dibbelt, Pajor, Strasser and Wagner) over the same file, with nothing of
//! the search by rounds in it. It answers the earliest arrival and the latest departure, and every best way by
//! time, cost and rides from a moment or to one (`ways`, `ways_back`); it holds a whole day's connections in
//! memory, so it is the tests' and the tool's, not the phone's.
//!
//! What a search could get wrong it reads from the file by itself, in its own way: when each service day begins
//! (the days the clock changes, `day_start`), which days' trips reach into the seconds asked for, what a
//! change at one stop costs (the stop's own time where the feed gives one, `own_change`), the file's walks turned
//! round (`walks_turned`), and the stops a query's places stand for with the seconds on foot (`places`): a stop's
//! and a station's from the file, a point's from the coordinates by a distance of its own (`between_mm`: no table,
//! and the search's number must lie within what a tenth of a degree of latitude changes), and whether two points
//! are near enough to walk all the way, and how long that takes.
//!
//! What the wishes close and what they cost it works out from the wish list, a wish and a ride or a stop at a time
//! (`Reference::asked`, `wished`), where the search adds up tables made beforehand; and a scan of its own carries
//! the cost and the rides beside the time (`ways`), so every option of an answer for one moment is held to it,
//! with wishes or without (`moment_differs`). What it has in
//! common with the search is what a wish means of one thing, which is not the search's either: whether a point
//! lies in an area ([`crate::query::Area::contains`]), which kind of vehicle a line's type is
//! ([`Mode::of`]), and how far a query is followed ([`Query::followed`]). tests/tiny.rs holds the first to paper
//! (an area's edge a metre from a stop), and a station's stops.
//!
//! The earliest arrival, the latest departure and a window's pairs know no cap on rides: a query they are compared
//! on sets `max_rides` high. The ways by time, cost and rides have no more rides than the query lets a journey
//! have. It keeps to the
//! seconds the query follows ([`Query::followed`]): what ends past them is no answer to either. And it holds to the
//! feed's rules between trips where the query does, in its own way: where a rule speaks of the trip to be boarded, it
//! goes through the arrivals one by one, each with the trip it came by, where the search by rounds holds such an
//! arrival apart in its stop's bag.
//!
//! A rule that says a vehicle waits holds one vehicle for each arrival (`leaves_between`): the scan asks of each
//! pair of an arrival and a departure whether anything else the rule speaks of leaves between the two, where the
//! search looks the one vehicle up when it holds the arrival.

use crate::NONE;
use crate::file::{Rule, Timetable, TripText};
use crate::journey::{Journey, Leg, Spot};
use crate::plan::Planner;
use crate::query::{How, Mode, Place, Query, What, Wish};
use alloc::format;
use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;


#[derive(Clone, Copy)]
struct Connection {
    depart: i32,
    arrive: i32,
    from: u32,
    to: u32,
    /// The trip on its day, numbered from 0, and where along it this is.
    ride: u32,
    pos: u32,
    board: bool,
    alight: bool,
}

/// One service day's connections, with those of the days around it that reach into the seconds asked for.
pub struct Reference {
    day: i32,
    /// By departure; and their numbers by arrival, the latest first.
    connections: Vec<Connection>,
    by_arrival: Vec<u32>,
    /// A ride's trip and line, and the service day it runs on.
    rides: Vec<(u32, u32)>,
    ride_day: Vec<i32>,
    /// The feed's rules that name a trip or a line: their numbers by the stop the change leads to, and by the stop
    /// it comes from, with where each stop's begin.
    into: Vec<u32>,
    into_first: Vec<u32>,
    out: Vec<u32>,
    out_first: Vec<u32>,
    /// The stops whose arrivals (scanning forward) and whose departures (back) are kept one by one, since a rule
    /// may tell one from another: a rule's two stops and the stops a walk away.
    arrivals_kept: Vec<bool>,
    departures_kept: Vec<bool>,
    /// By stop: what the feed says of changing there (`own_change`), read once.
    own: Vec<Option<i32>>,
    /// The file's walks turned round, by the stop they lead to, with where each stop's begin: made here from the
    /// file's own list, which is by the stop they lead from. (The search has a list of its own.)
    back: Vec<(u32, u32)>,
    back_first: Vec<u32>,
}

/// The file's walks turned round: for each stop where its walks begin in the list, and the list, each walk as the
/// stop it comes from and its seconds. A walk that leads to no stop of the file is none.
fn walks_turned(tt: &Timetable) -> (Vec<u32>, Vec<(u32, u32)>) {
    let stops = tt.stops() as usize;
    let mut all: Vec<(u32, u32, u32)> = Vec::new();
    for s in 0..tt.stops() {
        let (lo, hi) = tt.walks_from(s);
        all.extend((lo..hi).map(|w| tt.walk(w)).filter(|w| (w.0 as usize) < stops).map(|(to, secs)| (to, s, secs)));
    }
    all.sort_unstable();
    let mut first = vec![0u32; stops + 1];
    for w in all.iter() {
        first[w.0 as usize + 1] += 1;
    }
    for s in 0..stops {
        first[s + 1] += first[s];
    }
    (first, all.into_iter().map(|w| (w.1, w.2)).collect())
}

/// When the service day `off` days from day `day` begins, in seconds from the beginning of `day`: day by day, each
/// as long as the file says (`odd_days` in its META: the days the clock changes are 82,800 or 90,000 s long, every
/// other 86,400).
pub fn day_start(tt: &Timetable, day: i32, off: i32) -> i32 {
    let length = |d: i32| {
        let said = tt.meta("odd_days").unwrap_or("").split_whitespace().find_map(|word| {
            let (date, secs) = word.split_once(':')?;
            (tt.day_of(date.parse().ok()?)? == d).then(|| secs.parse::<i32>().ok()).flatten()
        });
        said.filter(|secs| (79_200..=93_600).contains(secs)).unwrap_or(86_400)
    };
    let off = off.clamp(-64, 64);
    if off >= 0 { (day..day + off).map(length).sum() } else { -(day + off..day).map(length).sum::<i32>() }
}

/// What the feed says of changing at one stop, for everyone: `Some(seconds)` where it gives the stop its own time
/// (a rule of type 2 from the stop to itself that names no trip and no line; the longest of several), `None` where
/// it says nothing, and `Some(i32::MAX / 4)` where it says nobody changes there (type 3).
pub fn own_change(tt: &Timetable, stop: u32) -> Option<i32> {
    // the rules are sorted by the stop they lead from
    let (mut lo, mut hi) = (0u32, tt.rules());
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        if tt.rule(mid).from_stop < stop { lo = mid + 1 } else { hi = mid }
    }
    let mut own = None;
    for r in (lo..tt.rules()).map(|i| tt.rule(i)).take_while(|r| r.from_stop == stop) {
        if r.to_stop != stop || named(&r) {
            continue;
        }
        let says = match r.kind {
            2 if r.min_time != NONE => r.min_time.min((1 << 20) - 1) as i32,
            3 => i32::MAX / 4,
            _ => continue,
        };
        own = Some(own.map_or(says, |o: i32| o.max(says)));
    }
    own
}

/// What a change at one stop costs for a query there: the stop's own time where the feed gives one, else the
/// file's for every stop; the query's own in their place where it asks for one, but never less than the stop's.
pub fn change_time(tt: &Timetable, q: &Query, stop: u32) -> i32 {
    let general = q.change_s.unwrap_or(tt.change_seconds()).min((1 << 20) - 1) as i32;
    match (own_change(tt, stop), q.change_s) {
        (Some(own), Some(_)) => general.max(own),
        (Some(own), None) => own,
        (None, _) => general,
    }
}

/// cos of a latitude given in 1e-7 degree, times 10^18: by its series, in whole numbers.
fn cos_e18(lat: i64) -> i128 {
    const ONE: i128 = 1_000_000_000_000_000_000;
    const PI: i128 = 3_141_592_653_589_793_238;
    let x = lat.clamp(-900_000_000, 900_000_000) as i128 * PI / 1_800_000_000;
    let xx = x * x / ONE;
    let (mut term, mut sum) = (ONE, ONE);
    for k in 0..14 {
        term = -term * xx / ONE / ((2 * k + 1) * (2 * k + 2));
        sum += term;
    }
    sum.clamp(0, ONE)
}

/// Millimetres between two points given in 1e-7 degree, as the scan works them out by itself: on the plane at their
/// mean latitude, a degree 111,194.9266 m long (a sphere of 6,371,008.8 m), the cosine by its series. The search
/// has the cosine from a table by the tenth of a degree and a degree of 111,195 m, so two numbers are given: the
/// least and the most the distance is with the cosine taken anywhere within a tenth of a degree of that latitude,
/// a thousandth and 2 mm more to either side. What the search measures must lie between them: at Utrecht that
/// holds it within a third of a per cent, and no walk twice too long or too short passes at any latitude.
pub fn between_mm(lat1: i32, lon1: i32, lat2: i32, lon2: i32) -> (u64, u64) {
    const NM: i128 = 11_119_493; // nanometres to 1e-7 degree
    let mean = ((lat1 as i64 + lat2 as i64) / 2).abs();
    let (dy, dx) = ((lat2 as i128 - lat1 as i128) * NM, (lon2 as i128 - lon1 as i128) * NM);
    let with = |cos: i128| {
        let dx = dx * cos / 1_000_000_000_000_000_000;
        let n = (dx * dx + dy * dy) as u128;
        // the whole part of the root: from a power of two above it, down while the mean of x and n / x is less
        let mut x = 1u128 << (128 - n.leading_zeros()).div_ceil(2);
        while n > 0 {
            let y = (x + n / x) / 2;
            if y >= x {
                break;
            }
            x = y;
        }
        (if n == 0 { 0 } else { x } / 1_000_000) as u64
    };
    let (least, most) = (with(cos_e18((mean + 1_000_000).min(900_000_000))), with(cos_e18((mean - 1_000_000).max(0))));
    (least.saturating_sub(least / 1000 + 2), most + most / 1000 + 2)
}

/// A query's two places as the scan reads them: the stops each stands for with the seconds on foot, what the
/// search has otherwise (`wrong`), and the walk all the way.
pub struct Places {
    pub from: Vec<(u32, u32)>,
    pub to: Vec<(u32, u32)>,
    /// Where the search's stops for a place are not the scan's, in words.
    pub wrong: Option<String>,
    /// Between two points: the least and the most seconds the walk all the way may take, and whether there must
    /// be one (the points are near enough by any measure). `None`: there is none, or may be none.
    pub on_foot: Option<(u32, u32, bool)>,
}

/// What the scan makes of a query before it scans, read once for every scan of one question: its places, and what
/// its wishes close.
pub struct Asked {
    places: Places,
    /// By stop: a wish of "never" speaks of it (nobody boards or alights there, walks there, or begins or ends
    /// there), and of riding past it too. By ride of this scan: one speaks of its trip, its line, or what they
    /// are. Read from the wish list, a wish at a time (`of_stop`, `of_ride`); empty where no wish says never.
    closed: Vec<bool>,
    closed_through: Vec<bool>,
    ride_closed: Vec<bool>,
    /// What the wishes that cost come to, in seconds: by ride of this scan, for riding it; by stop, for a boarding
    /// or an alighting there, and for a call there where riding past counts. From the wish list too (`worth`);
    /// empty where no wish costs.
    ride_costs: Vec<u32>,
    used: Vec<u32>,
    called: Vec<u32>,
}

impl Asked {
    fn closed(&self, stop: u32) -> bool {
        self.closed.get(stop as usize).copied().unwrap_or(false)
    }

    fn closed_through(&self, stop: u32) -> bool {
        self.closed_through.get(stop as usize).copied().unwrap_or(false)
    }

    fn ride_closed(&self, ride: u32) -> bool {
        self.ride_closed.get(ride as usize).copied().unwrap_or(false)
    }

    fn ride_cost(&self, ride: u32) -> u32 {
        self.ride_costs.get(ride as usize).copied().unwrap_or(0)
    }

    fn used(&self, stop: u32) -> u32 {
        self.used.get(stop as usize).copied().unwrap_or(0)
    }

    fn called(&self, stop: u32) -> u32 {
        self.called.get(stop as usize).copied().unwrap_or(0)
    }
}

/// How far on foot a point reaches at the most, how much longer a way is than the line between its ends (in
/// hundredths), the bounds of a walking speed and of a time: the planner's rules for a place, said here once more.
const REACH_MOST_M: u32 = 50_000;
const DETOUR: u64 = 130;
const SPEED: (u32, u32) = (100, 100_000);
const TIME_MOST: u64 = (1 << 20) - 1;

/// The seconds a walk of so many millimetres takes at a speed: the least and the most, for a distance known as
/// two numbers.
fn seconds_on_foot(mm: (u64, u64), speed: u32) -> (u32, u32) {
    let speed = speed.clamp(SPEED.0, SPEED.1) as u64;
    ((mm.0 * DETOUR / 100 / speed).min(TIME_MOST) as u32, (mm.1 * DETOUR / 100).div_ceil(speed).min(TIME_MOST) as u32)
}

/// The stops one place stands for, `leaving` it or reaching it, as the scan reads the file and the query; `theirs`
/// is the search's list, and `cut` its word that it kept the nearest of too many.
///
/// A stop is itself and every stop a walk of the file leads to (or comes from); a station is its stops; a list of
/// walks is the list, each stop once by its shortest. These must be the search's, stop for stop and second for
/// second. A point is every stop within its reach where something calls, by the scan's own distance: a stop the
/// scan has within the reach by any measure must be the search's, one it has outside by any measure must not be,
/// and the seconds must lie between the scan's least and most (`between_mm`). Within that the search's number is
/// taken, so that the two compare journeys to the second; outside it the scan keeps its own, and they differ.
fn stops_of(tt: &Timetable, q: &Query, leaving: bool, theirs: &[(u32, u32)], cut: bool, into: &dyn Fn(&[u32]) -> Vec<(u32, u32)>) -> (Vec<(u32, u32)>, Option<String>) {
    let place = if leaving { &q.from } else { &q.to };
    let side = if leaving { "origin" } else { "destination" };
    let mut out: Vec<(u32, u32)> = Vec::new();
    let mut wrong = None;
    let around = |these: Vec<u32>, out: &mut Vec<(u32, u32)>| {
        out.extend(these.iter().map(|s| (*s, 0)));
        if leaving {
            for &s in these.iter() {
                let (lo, hi) = tt.walks_from(s);
                out.extend((lo..hi).map(|w| tt.walk(w)).filter(|w| w.0 < tt.stops()));
            }
        } else {
            out.extend(into(&these));
        }
    };
    match place {
        Place::Stop(s) if *s < tt.stops() => around(vec![*s], &mut out),
        Place::Stop(_) => {}
        Place::Station(station) => around((0..tt.stops()).filter(|s| s == station || tt.stop(*s).parent == *station).collect(), &mut out),
        Place::Walks(list) => out.extend(list.iter().filter(|w| w.0 < tt.stops() && (w.1 as u64) <= TIME_MOST)),
        Place::Point { lat, lon } => {
            let reach = q.reach_m.min(REACH_MOST_M) as u64 * 1000;
            let mut said = |words: String| wrong = wrong.take().or(Some(words));
            let mut theirs_at = theirs.iter().peekable();
            // Most stops are far off, and are seen to be so cheaply: by the latitude alone, then with the least
            // cosine there is within the reach and a tenth of a degree of the point. The others are measured.
            let span = (reach / 11) as i64 + 1_000_000;
            let least_cos = cos_e18(((*lat as i64).abs() + span).min(900_000_000));
            let far = (reach as i128 + reach as i128 / 100 + 10) * 1_000_000;
            for s in 0..tt.stops() {
                let st = tt.stop(s);
                // (the search's list is by stop)
                while theirs_at.peek().is_some_and(|e| e.0 < s) {
                    theirs_at.next();
                }
                let has = theirs_at.peek().filter(|e| e.0 == s).map(|e| e.1);
                // too far north or south by the latitude alone (11.1 mm to 1e-7 degree, and a hundredth more)
                if has.is_none() && (st.lat as i64 - *lat as i64).unsigned_abs() * 11 > reach + reach / 100 + 10 {
                    continue;
                }
                let (dy, dx) = ((st.lat as i128 - *lat as i128) * 11_119_493, (st.lon as i128 - *lon as i128) * 11_119_493 * least_cos / 1_000_000_000_000_000_000);
                if has.is_none() && dx * dx + dy * dy > far * far {
                    continue;
                }
                let calls = st.kind == 0 && st.place != 2 && tt.routes_at(s).0 != tt.routes_at(s).1;
                let mm = between_mm(*lat, *lon, st.lat, st.lon);
                let (least, most) = seconds_on_foot(mm, q.walk_mm_s);
                match has {
                    Some(_) if !calls => said(format!("the {side} has stop {s}, where nothing calls or which is no stop")),
                    Some(_) if mm.0 > reach => said(format!("the {side} has stop {s}, {} m from the point and past its reach", mm.0 / 1000)),
                    Some(secs) if secs < least || secs > most => {
                        said(format!("the {side} has stop {s} at {secs} s on foot, and by the scan it is {least} to {most}"));
                        out.push((s, most));
                    }
                    Some(secs) => out.push((s, secs)),
                    None if calls && mm.1 <= reach => {
                        if !cut {
                            said(format!("the {side} lacks stop {s}, {} m from the point and within its reach", mm.1 / 1000));
                        }
                        out.push((s, most));
                    }
                    None => {}
                }
            }
        }
    }
    out.sort_unstable();
    out.dedup_by_key(|e| e.0);
    if !matches!(place, Place::Point { .. }) {
        let differs = if cut { theirs.iter().any(|e| !out.contains(e)) } else { theirs != &out[..] };
        if differs {
            let only = |a: &[(u32, u32)], b: &[(u32, u32)]| a.iter().filter(|e| !b.contains(e)).take(4).copied().collect::<Vec<_>>();
            wrong = Some(format!("the {side}'s stops and seconds: the search alone has {:?}, the scan alone {:?}", only(theirs, &out), only(&out, theirs)));
        }
    }
    (out, wrong)
}

/// A query's places as the scan reads them (`stops_of`), given the walks that lead to a stop.
fn places_with(planner: &Planner, q: &Query, into: &dyn Fn(&[u32]) -> Vec<(u32, u32)>) -> Places {
    let tt = planner.timetable();
    let theirs = planner.ends(q);
    let (from, wrong_from) = stops_of(tt, q, true, &theirs.from, theirs.cut, into);
    let (to, wrong_to) = stops_of(tt, q, false, &theirs.to, theirs.cut, into);
    // on foot all the way: between two points no farther apart than twice the reach
    let on_foot = match (&q.from, &q.to) {
        (Place::Point { lat: a, lon: b }, Place::Point { lat: c, lon: d }) => {
            let (mm, reach) = (between_mm(*a, *b, *c, *d), 2 * q.reach_m.min(REACH_MOST_M) as u64 * 1000);
            let (least, most) = seconds_on_foot(mm, q.walk_mm_s);
            (mm.0 <= reach).then_some((least, most, mm.1 <= reach))
        }
        _ => None,
    };
    Places { from, to, wrong: wrong_from.or(wrong_to), on_foot }
}

/// A query's places as the scan reads them, with nothing made beforehand: the walks that lead to a stop are looked
/// for through the whole file.
pub fn places(planner: &Planner, q: &Query) -> Places {
    let tt = planner.timetable();
    places_with(planner, q, &|these| {
        let mut v = Vec::new();
        for s in 0..tt.stops() {
            let (lo, hi) = tt.walks_from(s);
            v.extend((lo..hi).map(|w| tt.walk(w)).filter(|w| these.contains(&w.0)).map(|w| (s, w.1)));
        }
        v
    })
}

/// The most a wish's seconds count for, the most a journey's cost comes to, and the most rides a journey may have
/// (query.rs).
const WISH_MOST: u32 = 86_400;
const COST_MOST: u32 = 64 * 86_400;
const RIDES_MOST: u32 = 16;

/// Costs added up, to the most a journey's comes to.
fn sum(costs: impl IntoIterator<Item = u32>) -> u32 {
    costs.into_iter().fold(0u32, |a, b| a.saturating_add(b)).min(COST_MOST)
}

/// What a wish that costs makes of one use: avoided, the thing itself costs; preferred, anything else does.
fn worth(how: How, is: bool) -> u32 {
    match how {
        How::Avoid(secs) if is => secs.min(WISH_MOST),
        How::Prefer(secs) if !is => secs.min(WISH_MOST),
        _ => 0,
    }
}

/// Whether a ride on a trip of a line is what a wish speaks of; `None` for a wish that speaks of stops only.
fn of_ride(tt: &Timetable, wish: &Wish, trip: u32, line: u32) -> Option<bool> {
    Some(match &wish.what {
        What::Line(l) => line == *l,
        What::Operator(o) => tt.line_operator(line) == *o,
        What::Mode(m) => Mode::of(tt.line_type(line)) == *m,
        What::Kind(k) => tt.trip_text(trip, TripText::Kind).eq_ignore_ascii_case(k),
        What::Trip(t) => trip == *t,
        What::NoWheelchair => tt.trip(trip).wheelchair == 2,
        What::WheelchairUnknown => tt.trip(trip).wheelchair == 0,
        What::NoBicycles => tt.trip(trip).bicycles == 2,
        What::BicyclesUnknown => tt.trip(trip).bicycles == 0,
        What::Stop { .. } | What::Station { .. } | What::Area { .. } => return None,
    })
}

/// Whether a stop is what a wish speaks of, and whether riding past it counts as boarding and alighting there do;
/// `None` for a wish that speaks of rides only.
fn of_stop(tt: &Timetable, wish: &Wish, stop: u32) -> Option<(bool, bool)> {
    let st = tt.stop(stop);
    Some(match &wish.what {
        What::Stop { stop: s, through } => (stop == *s, *through),
        What::Station { station, through } => (stop == *station || st.parent == *station, *through),
        What::Area { area, through } => (st.place != 2 && area.contains(st.lat, st.lon), *through),
        What::NoWheelchair | What::WheelchairUnknown => {
            // a stop that doesn't say takes its station's word
            let says = if st.wheelchair == 0 && st.parent != NONE { tt.stop(st.parent).wheelchair } else { st.wheelchair };
            (says == if wish.what == What::NoWheelchair { 2 } else { 0 }, false)
        }
        _ => return None,
    })
}

/// What a journey costs in the query's wishes, in seconds, or which wish of "never" it breaks: wish by wish and
/// ride by ride, as query.rs's head says it. A ride on what a wish speaks of counts once; a stop counts at each
/// boarding and each alighting there, and where riding past counts too, at each call from the boarding to the
/// alighting, both among them. A walk costs nothing.
pub fn wished(tt: &Timetable, q: &Query, j: &Journey) -> Result<u32, String> {
    let mut cost = 0u32;
    for (n, wish) in q.wishes.iter().take(64).enumerate() {
        for leg in j.legs.iter() {
            let Leg::Ride { trip, route, board, alight, from, to, .. } = *leg else { continue };
            let r = tt.route(route);
            // each use, and whether it is what the wish speaks of
            let mut uses: Vec<bool> = Vec::new();
            uses.extend(of_ride(tt, wish, trip, r.line));
            match of_stop(tt, wish, from) {
                Some((_, true)) => uses.extend((board..=alight.min(r.stops.saturating_sub(1))).filter_map(|pos| of_stop(tt, wish, tt.pattern_stop(r.first_stop + pos).stop).map(|is| is.0))),
                Some((_, false)) => uses.extend([from, to].iter().filter_map(|s| of_stop(tt, wish, *s).map(|is| is.0))),
                None => {}
            }
            for is in uses {
                if is && wish.how == How::Never {
                    return Err(format!("wish {n} says never, and the ride on trip {trip} from stop {from} to stop {to} is or uses what it speaks of"));
                }
                cost = cost.saturating_add(worth(wish.how, is)).min(COST_MOST);
            }
        }
    }
    Ok(cost)
}

/// A way so far, or a whole one: a moment, its cost in the wishes and its rides. The moment is the one that counts
/// where it is kept, the less the better: an arrival; scanning back, a departure negated; 0 aboard a vehicle, whose
/// time is the vehicle's.
type Way = (i32, u32, u32);

/// Adds a way to those of which none is no worse than another in all three: whether it was added.
fn offer(bag: &mut Vec<Way>, new: Way) -> bool {
    if bag.iter().any(|l| l.0 <= new.0 && l.1 <= new.1 && l.2 <= new.2) {
        return false;
    }
    bag.retain(|l| !(new.0 <= l.0 && new.1 <= l.1 && new.2 <= l.2));
    bag.push(new);
    true
}

fn named(r: &Rule) -> bool {
    r.from_trip != NONE || r.to_trip != NONE || r.from_line != NONE || r.to_line != NONE
}

fn fits(want: u32, is: u32) -> bool {
    want == NONE || want == is
}

/// Whether a vehicle that one side of a rule speaks of (its trip; a trip of its line; any trip where it names
/// neither) leaves `stop` from `since` on and before `until`, in seconds of service day `day`: on a day it runs,
/// taking people on there. `own`, a trip on its day, is not counted. The file alone is read, and no wish.
///
/// It is how a rule that says a vehicle waits is held to one vehicle: the one that leaves at `until` waits for
/// whoever is in at `since` when nothing else the rule speaks of leaves between the two, the vehicle one came by
/// aside.
#[allow(clippy::too_many_arguments)]
fn leaves_between(tt: &Timetable, day: i32, stop: u32, trip: u32, line: u32, since: i32, until: i32, own: (u32, i32)) -> bool {
    if until <= since || stop >= tt.stops() || (trip != NONE && trip >= tt.trips()) {
        return false;
    }
    let (lo, hi) = tt.routes_at(stop);
    for (r, pos) in (lo..hi).map(|e| tt.route_at(e)) {
        let route = tt.route(r);
        if !fits(line, route.line) || pos + 1 >= route.stops || tt.pattern_stop(route.first_stop + pos).pickup == 1 {
            continue;
        }
        let trips = match trip {
            NONE => route.first_trip..route.first_trip + route.trips,
            t if tt.route_of_trip(t) == r => t..t + 1,
            _ => continue,
        };
        for t in trips {
            let leaves = tt.time(tt.trip_times(t), pos).1 as i32;
            // the service days on which it could leave in these seconds: two hours more at either end, for the
            // days that are not 86,400 s long
            for off in (since - leaves - 7200).div_euclid(86_400)..=(until - leaves + 7200).div_euclid(86_400) {
                let at = day_start(tt, day, off) + leaves;
                if at >= since && at < until && (t, day + off) != own && tt.runs(tt.trip(t).service, day + off) {
                    return true;
                }
            }
        }
    }
    false
}

impl Reference {
    /// The connections that leave at `earliest` or later and arrive by `latest`, in seconds of service day `day`.
    pub fn new(planner: &Planner, day: i32, earliest: i32, latest: i32) -> Reference {
        let tt = planner.timetable();
        // The service days whose trips can reach into these seconds: from the day a trip as long as the file's
        // longest would have had to begin, to the day the last second lies in; two hours more at either end,
        // for the days that are not 86,400 s long.
        let mut longest = 0i32;
        for r in 0..tt.routes() {
            let route = tt.route(r);
            for trip in route.first_trip..route.first_trip + route.trips {
                longest = longest.max(tt.time(tt.trip_times(trip), route.stops.saturating_sub(1)).0 as i32);
            }
        }
        let lo = (earliest - 7200 - longest).div_euclid(86_400);
        let hi = (latest + 7200).div_euclid(86_400).min(lo + 31);
        let begins: Vec<i32> = (lo..=hi).map(|off| day_start(tt, day, off)).collect();
        let (mut connections, mut rides, mut ride_day) = (Vec::new(), Vec::new(), Vec::new());
        for r in 0..tt.routes() {
            let route = tt.route(r);
            for trip in route.first_trip..route.first_trip + route.trips {
                let times = tt.trip_times(trip);
                for off in lo..=hi {
                    if !tt.runs(tt.trip(trip).service, day + off) {
                        continue;
                    }
                    let zero = begins[(off - lo) as usize];
                    let ride = rides.len() as u32;
                    let mut any = false;
                    for pos in 0..route.stops.saturating_sub(1) {
                        let (a, b) = (tt.pattern_stop(route.first_stop + pos), tt.pattern_stop(route.first_stop + pos + 1));
                        let (depart, arrive) = (zero + tt.time(times, pos).1 as i32, zero + tt.time(times, pos + 1).0 as i32);
                        if depart >= earliest && arrive <= latest {
                            connections.push(Connection { depart, arrive, from: a.stop, to: b.stop, ride, pos, board: a.pickup != 1, alight: b.drop_off != 1 });
                            any = true;
                        }
                    }
                    if any {
                        rides.push((trip, route.line));
                        ride_day.push(day + off);
                    }
                }
            }
        }
        connections.sort_unstable_by_key(|c| (c.depart, c.ride, c.pos));
        let mut by_arrival: Vec<u32> = (0..connections.len() as u32).collect();
        by_arrival.sort_unstable_by_key(|i| {
            let c = &connections[*i as usize];
            (core::cmp::Reverse(c.arrive), c.ride, core::cmp::Reverse(c.pos))
        });
        // the feed's rules, by either stop
        let stops = tt.stops() as usize;
        let all: Vec<u32> = (0..tt.rules()).filter(|i| named(&tt.rule(*i)) && (tt.rule(*i).from_stop as usize) < stops && (tt.rule(*i).to_stop as usize) < stops).collect();
        let by = |key: &dyn Fn(&Rule) -> u32| {
            let mut list = all.clone();
            list.sort_by_key(|i| key(&tt.rule(*i)));
            let mut first = vec![0u32; stops + 1];
            for i in list.iter() {
                first[key(&tt.rule(*i)) as usize + 1] += 1;
            }
            for s in 0..stops {
                first[s + 1] += first[s];
            }
            (list, first)
        };
        let ((into, into_first), (out, out_first)) = (by(&|r| r.to_stop), by(&|r| r.from_stop));
        let (back_first, back) = walks_turned(tt);
        let (mut arrivals_kept, mut departures_kept) = (vec![false; stops], vec![false; stops]);
        for &i in all.iter() {
            let r = tt.rule(i);
            for s in [r.from_stop, r.to_stop] {
                (arrivals_kept[s as usize], departures_kept[s as usize]) = (true, true);
            }
            for &(y, _) in &back[back_first[r.to_stop as usize] as usize..back_first[r.to_stop as usize + 1] as usize] {
                arrivals_kept[y as usize] = true;
            }
            let (lo, hi) = tt.walks_from(r.from_stop);
            for w in lo..hi {
                if let Some(kept) = departures_kept.get_mut(tt.walk(w).0 as usize) {
                    *kept = true;
                }
            }
        }
        let own = (0..tt.stops()).map(|s| own_change(tt, s)).collect();
        Reference { day, connections, by_arrival, rides, ride_day, into, into_first, out, out_first, arrivals_kept, departures_kept, own, back, back_first }
    }

    /// What the scan makes of a query before it scans.
    pub fn asked(&self, planner: &Planner, q: &Query) -> Asked {
        let tt = planner.timetable();
        let never: Vec<&Wish> = q.wishes.iter().take(64).filter(|w| w.how == How::Never).collect();
        let (mut closed, mut closed_through, mut ride_closed) = (Vec::new(), Vec::new(), Vec::new());
        if !never.is_empty() {
            let said: Vec<Option<(bool, bool)>> = (0..tt.stops()).map(|s| never.iter().filter_map(|w| of_stop(tt, w, s)).filter(|is| is.0).max()).collect();
            closed = said.iter().map(|is| is.is_some()).collect();
            closed_through = said.iter().map(|is| *is == Some((true, true))).collect();
            ride_closed = self.rides.iter().map(|&(trip, line)| never.iter().any(|w| of_ride(tt, w, trip, line) == Some(true))).collect();
        }
        let costly: Vec<&Wish> = q.wishes.iter().take(64).filter(|w| w.how != How::Never).collect();
        let (mut ride_costs, mut used, mut called) = (Vec::new(), Vec::new(), Vec::new());
        if !costly.is_empty() {
            ride_costs = self.rides.iter().map(|&(trip, line)| sum(costly.iter().filter_map(|w| of_ride(tt, w, trip, line).map(|is| worth(w.how, is))))).collect();
            let at_stops = |through: bool| -> Vec<u32> { (0..tt.stops()).map(|s| sum(costly.iter().filter_map(|w| of_stop(tt, w, s).filter(|is| is.1 == through).map(|is| worth(w.how, is.0))))).collect() };
            (used, called) = (at_stops(false), at_stops(true));
        }
        Asked { places: self.places(planner, q), closed, closed_through, ride_closed, ride_costs, used, called }
    }

    /// A query's places as the scan reads them (`stops_of`).
    pub fn places(&self, planner: &Planner, q: &Query) -> Places {
        places_with(planner, q, &|these| these.iter().flat_map(|s| self.walks_into(*s).iter().copied()).collect())
    }

    /// The walks that lead to a stop, each as the stop it comes from and its seconds.
    fn walks_into(&self, stop: u32) -> &[(u32, u32)] {
        match (self.back_first.get(stop as usize), self.back_first.get(stop as usize + 1)) {
            (Some(&lo), Some(&hi)) => &self.back[lo as usize..hi as usize],
            _ => &[],
        }
    }

    /// What a change at one stop costs for a query (`change_time`, from what was read once).
    fn change(&self, tt: &Timetable, q: &Query, stop: u32) -> i32 {
        let general = q.change_s.unwrap_or(tt.change_seconds()).min((1 << 20) - 1) as i32;
        match (self.own[stop as usize], q.change_s) {
            (Some(own), Some(_)) => general.max(own),
            (Some(own), None) => own,
            (None, _) => general,
        }
    }

    /// The named rules for a change that leads to a stop, and for one that comes from it.
    fn rules_into(&self, stop: u32) -> &[u32] {
        &self.into[self.into_first[stop as usize] as usize..self.into_first[stop as usize + 1] as usize]
    }

    fn rules_out_of(&self, stop: u32) -> &[u32] {
        &self.out[self.out_first[stop as usize] as usize..self.out_first[stop as usize + 1] as usize]
    }

    /// What the feed's rules say of a change from one ride to another: the first as the stop and the moment it is
    /// in, the second as the stop and the moment it leaves. Whether the change is barred, whether the second
    /// vehicle waits, and the least seconds it needs.
    ///
    /// A rule that says a vehicle waits holds one for each arrival: of all it speaks of, the first to leave once
    /// the arrival is in. So the second ride waits when it leaves no sooner than the first is in, is another
    /// vehicle, and nothing else the rule speaks of leaves between the two.
    fn ruled(&self, tt: &Timetable, from: (u32, u32, i32), to: (u32, u32, i32)) -> (bool, bool, i32) {
        let (mut barred, mut waits, mut least) = (false, false, 0i32);
        let ((trip, line), (to_trip, to_line)) = (self.rides[from.1 as usize], self.rides[to.1 as usize]);
        let came_by = (trip, self.ride_day[from.1 as usize]);
        for &i in self.rules_into(to.0) {
            let r = tt.rule(i);
            if r.from_stop != from.0 || !(fits(r.from_trip, trip) && fits(r.from_line, line) && fits(r.to_trip, to_trip) && fits(r.to_line, to_line)) {
                continue;
            }
            match r.kind {
                1 | 4 => waits = waits || from.1 != to.1 && to.2 >= from.2 && !leaves_between(tt, self.day, to.0, r.to_trip, r.to_line, from.2, to.2, came_by),
                2 if r.min_time != NONE => least = least.max(r.min_time.min(1 << 20) as i32),
                3 => barred = true,
                _ => {}
            }
        }
        (barred, waits, least)
    }

    /// Whether the ride `to` leaving `stop` at `depart` can be boarded, where a rule speaks of boarding it
    /// there: from the origin on foot, or from one of the arrivals kept, by what the rules say of that change. The
    /// rides of the journey so far, or `None`.
    #[allow(clippy::too_many_arguments)]
    fn boards(&self, planner: &Planner, q: &Query, origin: &[i32], arrivals: &[Vec<(i32, u32)>], took: &[u32], stop: u32, to: u32, depart: i32) -> Option<u32> {
        if origin[stop as usize] <= depart {
            return Some(0);
        }
        let tt = planner.timetable();
        // an arrival at `s`, with the seconds the change from there needs when no rule speaks (`None`: no way but a
        // vehicle that waits)
        let from_there = |s: u32, base: Option<i32>| -> Option<u32> {
            for &(t, x) in arrivals[s as usize].iter().filter(|a| a.0 <= depart) {
                let (barred, waits, least) = self.ruled(tt, (s, x, t), (stop, to, depart));
                let needs = match (waits, base) {
                    (true, _) => 0,
                    (false, Some(base)) => base.max(least),
                    (false, None) => continue,
                };
                if !barred && depart >= t + needs {
                    return Some(took[x as usize]);
                }
            }
            None
        };
        let mut found = from_there(stop, Some(self.change(tt, q, stop)));
        for &(y, secs) in self.walks_into(stop) {
            found = found.or_else(|| from_there(y, Some(secs as i32)));
        }
        // and from where the feed says this vehicle waits, though no walk leads here from there
        for &i in self.rules_into(stop) {
            let r = tt.rule(i);
            if found.is_none() && matches!(r.kind, 1 | 4) && r.from_stop != stop && !self.walks_into(stop).iter().any(|w| w.0 == r.from_stop) {
                found = from_there(r.from_stop, None);
            }
        }
        found
    }

    /// The same turned round: whether the ride `from` that arrives at `stop` at `arrive` leads on, where a rule
    /// speaks of changing from it there: to the destination on foot, or to one of the departures kept.
    #[allow(clippy::too_many_arguments)]
    fn leads_on(&self, planner: &Planner, q: &Query, destination: &[i32], departures: &[Vec<(i32, u32)>], stop: u32, from: u32, arrive: i32) -> bool {
        if destination[stop as usize] >= arrive {
            return true;
        }
        let tt = planner.timetable();
        let to_there = |s: u32, base: Option<i32>| -> bool {
            departures[s as usize].iter().filter(|d| d.0 >= arrive).any(|&(d, y)| {
                let (barred, waits, least) = self.ruled(tt, (stop, from, arrive), (s, y, d));
                let needs = match (waits, base) {
                    (true, _) => 0,
                    (false, Some(base)) => base.max(least),
                    (false, None) => return false,
                };
                !barred && d >= arrive + needs
            })
        };
        if to_there(stop, Some(self.change(tt, q, stop))) {
            return true;
        }
        let (lo, hi) = tt.walks_from(stop);
        if (lo..hi).map(|w| tt.walk(w)).any(|(y, secs)| (y as usize) < departures.len() && to_there(y, Some(secs as i32))) {
            return true;
        }
        self.rules_out_of(stop).iter().map(|i| tt.rule(*i)).any(|r| matches!(r.kind, 1 | 4) && r.to_stop != stop && !(lo..hi).any(|w| tt.walk(w).0 == r.to_stop) && to_there(r.to_stop, None))
    }

    pub fn connections(&self) -> usize {
        self.connections.len()
    }

    /// The earliest arrival at the query's destination, leaving its origin at `time` or later. The query's own
    /// moment, window and direction are not read.
    pub fn earliest_arrival(&self, planner: &Planner, q: &Query, time: i32) -> Option<i32> {
        self.earliest_with_rides(planner, q, time).map(|a| a.0)
    }

    /// The same, with the rides of the journey the scan made for it. That arrival may be made with fewer: the scan
    /// does not look for them. It says whether a search with a cap on rides could have found this journey.
    pub fn earliest_with_rides(&self, planner: &Planner, q: &Query, time: i32) -> Option<(i32, u32)> {
        self.earliest_in(planner, q, &self.asked(planner, q), time)
    }

    fn earliest_in(&self, planner: &Planner, q: &Query, asked: &Asked, time: i32) -> Option<(i32, u32)> {
        debug_assert_eq!(self.day, q.day);
        let tt = planner.timetable();
        let ends = &asked.places;
        let stops = tt.stops() as usize;
        let (mut ready, mut arrived, mut last) = (vec![i32::MAX; stops], vec![i32::MAX; stops], vec![NONE; stops]);
        let mut on = vec![false; self.rides.len()];
        // the rides of the journey that is ready at a stop soonest, and of the one that boarded a ride (it counted)
        let (mut via, mut took) = (vec![0u32; stops], vec![0u32; self.rides.len()]);
        // holding to the feed's rules: the origin's stops apart, and the arrivals where a rule may tell them apart
        let ruled = q.rules && !self.into.is_empty();
        let mut origin = vec![i32::MAX; if ruled { stops } else { 0 }];
        let mut arrivals: Vec<Vec<(i32, u32)>> = vec![Vec::new(); if ruled { stops } else { 0 }];
        for &(s, secs) in ends.from.iter() {
            if !asked.closed(s) {
                ready[s as usize] = ready[s as usize].min(time + secs as i32);
                if ruled {
                    origin[s as usize] = ready[s as usize];
                }
            }
        }
        for &(s, secs) in ends.to.iter() {
            if !asked.closed(s) {
                last[s as usize] = secs;
            }
        }
        let (mut best, mut best_rides) = (i32::MAX, 0u32);
        let until = q.followed().1.max(time);
        let start = self.connections.partition_point(|c| c.depart < time);
        for c in &self.connections[start..] {
            if c.depart >= best || c.depart > until {
                break;
            }
            if !on[c.ride as usize] {
                let (trip, line) = self.rides[c.ride as usize];
                if !c.board || asked.closed(c.from) || asked.ride_closed(c.ride) {
                    continue;
                }
                // where a rule speaks of boarding this trip here, the arrivals one by one; else the stop's soonest
                let spoken_of = ruled && self.rules_into(c.from).iter().any(|i| fits(tt.rule(*i).to_trip, trip) && fits(tt.rule(*i).to_line, line));
                let so_far = if spoken_of {
                    self.boards(planner, q, &origin, &arrivals, &took, c.from, c.ride, c.depart)
                } else {
                    (ready[c.from as usize] <= c.depart).then_some(via[c.from as usize])
                };
                let Some(so_far) = so_far else { continue };
                on[c.ride as usize] = true;
                took[c.ride as usize] = so_far + 1;
            }
            if asked.closed_through(c.to) {
                on[c.ride as usize] = false;
                continue;
            }
            if !c.alight || asked.closed(c.to) || c.arrive > until {
                continue;
            }
            let rides = took[c.ride as usize];
            if last[c.to as usize] != NONE && c.arrive + last[c.to as usize] as i32 <= until && c.arrive + (last[c.to as usize] as i32) < best {
                (best, best_rides) = (c.arrive + last[c.to as usize] as i32, rides);
            }
            if ruled && self.arrivals_kept[c.to as usize] {
                arrivals[c.to as usize].push((c.arrive, c.ride));
            }
            if c.arrive < arrived[c.to as usize] {
                arrived[c.to as usize] = c.arrive;
                let mut sooner = |stop: u32, at: i32| {
                    if at < ready[stop as usize] {
                        (ready[stop as usize], via[stop as usize]) = (at, rides);
                    }
                };
                sooner(c.to, c.arrive.saturating_add(self.change(tt, q, c.to)));
                let (lo, hi) = tt.walks_from(c.to);
                for i in lo..hi {
                    let (y, secs) = tt.walk(i);
                    if !asked.closed(y) {
                        sooner(y, c.arrive + secs as i32);
                    }
                }
            }
        }
        (best != i32::MAX).then_some((best, best_rides))
    }

    /// With what cost and how many rides so far the ride `to` leaving `stop` at `depart` can be boarded, where a
    /// rule speaks of boarding it there: `boards` over every arrival the rules let change and not the first
    /// found, each with its cost and its rides; of these the best (none is no worse than another in both).
    #[allow(clippy::too_many_arguments)]
    fn boards_ways(&self, planner: &Planner, q: &Query, origin: &[i32], arrivals: &[Vec<(i32, u32, u32, u32)>], stop: u32, to: u32, depart: i32) -> Vec<Way> {
        let tt = planner.timetable();
        let mut best: Vec<Way> = Vec::new();
        if origin[stop as usize] <= depart {
            best.push((0, 0, 0));
        }
        let mut from_there = |s: u32, base: Option<i32>| {
            for &(t, x, cost, rides) in arrivals[s as usize].iter().filter(|a| a.0 <= depart) {
                if best.iter().any(|b| b.1 <= cost && b.2 <= rides) {
                    continue;
                }
                let (barred, waits, least) = self.ruled(tt, (s, x, t), (stop, to, depart));
                let needs = match (waits, base) {
                    (true, _) => 0,
                    (false, Some(base)) => base.max(least),
                    (false, None) => continue,
                };
                if !barred && depart >= t.saturating_add(needs) {
                    offer(&mut best, (0, cost, rides));
                }
            }
        };
        from_there(stop, Some(self.change(tt, q, stop)));
        for &(y, secs) in self.walks_into(stop) {
            from_there(y, Some(secs as i32));
        }
        for &i in self.rules_into(stop) {
            let r = tt.rule(i);
            if matches!(r.kind, 1 | 4) && r.from_stop != stop && !self.walks_into(stop).iter().any(|w| w.0 == r.from_stop) {
                from_there(r.from_stop, None);
            }
        }
        best
    }

    /// The same turned round: at what cost and with how many rides the ride `from` that arrives at `stop` at
    /// `arrive` leads on, where a rule speaks of changing from it there.
    #[allow(clippy::too_many_arguments)]
    fn leads_on_ways(&self, planner: &Planner, q: &Query, destination: &[i32], departures: &[Vec<(i32, u32, u32, u32)>], stop: u32, from: u32, arrive: i32) -> Vec<Way> {
        let tt = planner.timetable();
        let mut best: Vec<Way> = Vec::new();
        if destination[stop as usize] >= arrive {
            best.push((0, 0, 0));
        }
        let mut to_there = |s: u32, base: Option<i32>| {
            for &(d, y, cost, rides) in departures[s as usize].iter().filter(|d| d.0 >= arrive) {
                if best.iter().any(|b| b.1 <= cost && b.2 <= rides) {
                    continue;
                }
                let (barred, waits, least) = self.ruled(tt, (stop, from, arrive), (s, y, d));
                let needs = match (waits, base) {
                    (true, _) => 0,
                    (false, Some(base)) => base.max(least),
                    (false, None) => continue,
                };
                if !barred && d >= arrive.saturating_add(needs) {
                    offer(&mut best, (0, cost, rides));
                }
            }
        };
        to_there(stop, Some(self.change(tt, q, stop)));
        let (lo, hi) = tt.walks_from(stop);
        for (y, secs) in (lo..hi).map(|w| tt.walk(w)) {
            if (y as usize) < departures.len() {
                to_there(y, Some(secs as i32));
            }
        }
        for r in self.rules_out_of(stop).iter().map(|i| tt.rule(*i)) {
            if matches!(r.kind, 1 | 4) && r.to_stop != stop && !(lo..hi).any(|w| tt.walk(w).0 == r.to_stop) {
                to_there(r.to_stop, None);
            }
        }
        best
    }

    /// Every best way with a ride from the query's origin to its destination that leaves at `time` or later and
    /// is in by `by`: each as its arrival, its cost in the query's wishes and its rides, and none is no worse than
    /// another in all three; by arrival. With no more rides than the query lets a journey have. The query's own
    /// moment, window and direction are not read; the feed's rules are held to as in `earliest_arrival`.
    ///
    /// The scan with a cost and a count of rides beside the time: at a stop it keeps every way that is ready to
    /// board there which no other beats in all three, aboard a vehicle every pair of cost and rides (the time is
    /// the vehicle's). What each wish costs it has from `of_ride` and `of_stop`, a wish at a time (`asked`). So
    /// it says of a query without wishes which journeys with fewer rides there are, and of one with wishes what
    /// each way costs.
    pub fn ways(&self, planner: &Planner, q: &Query, time: i32, by: i32) -> Vec<(i32, u32, u32)> {
        self.ways_in(planner, q, &self.asked(planner, q), time, by)
    }

    fn ways_in(&self, planner: &Planner, q: &Query, asked: &Asked, time: i32, by: i32) -> Vec<Way> {
        debug_assert_eq!(self.day, q.day);
        let tt = planner.timetable();
        let ends = &asked.places;
        let stops = tt.stops() as usize;
        let most = q.max_rides.clamp(1, RIDES_MOST);
        let (mut ready, mut arrived): (Vec<Vec<Way>>, Vec<Vec<Way>>) = (vec![Vec::new(); stops], vec![Vec::new(); stops]);
        let mut last = vec![NONE; stops];
        let mut aboard: Vec<Vec<Way>> = vec![Vec::new(); self.rides.len()];
        let ruled = q.rules && !self.into.is_empty();
        let mut origin = vec![i32::MAX; if ruled { stops } else { 0 }];
        let mut arrivals: Vec<Vec<(i32, u32, u32, u32)>> = vec![Vec::new(); if ruled { stops } else { 0 }];
        for &(s, secs) in ends.from.iter() {
            if !asked.closed(s) {
                let at = time + secs as i32;
                offer(&mut ready[s as usize], (at, 0, 0));
                if ruled {
                    origin[s as usize] = origin[s as usize].min(at);
                }
            }
        }
        for &(s, secs) in ends.to.iter() {
            if !asked.closed(s) {
                last[s as usize] = last[s as usize].min(secs);
            }
        }
        let mut front: Vec<Way> = Vec::new();
        let until = q.followed().1.max(time).min(by);
        let start = self.connections.partition_point(|c| c.depart < time);
        for c in &self.connections[start..] {
            // (what leaves once a way of one ride that costs nothing is in can only be in later, and no better)
            if c.depart > until || front.iter().any(|f| f.0 <= c.depart && f.1 == 0 && f.2 <= 1) {
                break;
            }
            if c.arrive > until {
                continue;
            }
            let ride = c.ride as usize;
            if c.board && !asked.closed(c.from) && !asked.ride_closed(c.ride) {
                let (trip, line) = self.rides[ride];
                let here = sum([asked.used(c.from), asked.called(c.from), asked.ride_cost(c.ride)]);
                // no use boarding where a way that is in already is no worse than anything this can come to
                let mut board = |so_far: Way| {
                    if so_far.2 < most && !front.iter().any(|f| f.0 <= c.depart && f.1 <= so_far.1 && f.2 <= so_far.2 + 1) {
                        offer(&mut aboard[ride], (0, sum([so_far.1, here]), so_far.2 + 1));
                    }
                };
                if ruled && self.rules_into(c.from).iter().any(|i| fits(tt.rule(*i).to_trip, trip) && fits(tt.rule(*i).to_line, line)) {
                    self.boards_ways(planner, q, &origin, &arrivals, c.from, c.ride, c.depart).into_iter().for_each(&mut board);
                } else {
                    ready[c.from as usize].iter().filter(|l| l.0 <= c.depart).copied().for_each(&mut board);
                }
            }
            if aboard[ride].is_empty() {
                continue;
            }
            if asked.closed_through(c.to) {
                aboard[ride].clear();
                continue;
            }
            if asked.called(c.to) > 0 {
                aboard[ride].iter_mut().for_each(|a| a.1 = sum([a.1, asked.called(c.to)]));
            }
            if !c.alight || asked.closed(c.to) {
                continue;
            }
            let to = c.to as usize;
            for k in 0..aboard[ride].len() {
                let (cost, rides) = (sum([aboard[ride][k].1, asked.used(c.to)]), aboard[ride][k].2);
                if last[to] != NONE && c.arrive + last[to] as i32 <= until {
                    offer(&mut front, (c.arrive + last[to] as i32, cost, rides));
                }
                if ruled && self.arrivals_kept[to] {
                    arrivals[to].push((c.arrive, c.ride, cost, rides));
                }
                if offer(&mut arrived[to], (c.arrive, cost, rides)) {
                    offer(&mut ready[to], (c.arrive.saturating_add(self.change(tt, q, c.to)), cost, rides));
                    let (lo, hi) = tt.walks_from(c.to);
                    for i in lo..hi {
                        let (y, secs) = tt.walk(i);
                        if (y as usize) < stops && !asked.closed(y) {
                            offer(&mut ready[y as usize], (c.arrive + secs as i32, cost, rides));
                        }
                    }
                }
            }
        }
        front.sort_unstable();
        front
    }

    /// The same turned round: every best way with a ride that is in by `time` and leaves at `since` or later, each
    /// as its departure, its cost and its rides: none leaves no earlier than another at no more cost with no more
    /// rides; by departure. Scanned back from the destination, as `latest_departure` is.
    pub fn ways_back(&self, planner: &Planner, q: &Query, time: i32, since: i32) -> Vec<(i32, u32, u32)> {
        self.ways_back_in(planner, q, &self.asked(planner, q), time, since)
    }

    fn ways_back_in(&self, planner: &Planner, q: &Query, asked: &Asked, time: i32, since: i32) -> Vec<Way> {
        debug_assert_eq!(self.day, q.day);
        let tt = planner.timetable();
        let ends = &asked.places;
        let stops = tt.stops() as usize;
        let most = q.max_rides.clamp(1, RIDES_MOST);
        // by when one must be at a stop, off a ride, with the cost and the rides of the way on from there; and the
        // departures from it; every moment negated, so the later is the less
        let (mut due, mut left): (Vec<Vec<Way>>, Vec<Vec<Way>>) = (vec![Vec::new(); stops], vec![Vec::new(); stops]);
        let mut first = vec![NONE; stops];
        let mut on: Vec<Vec<Way>> = vec![Vec::new(); self.rides.len()];
        let ruled = q.rules && !self.out.is_empty();
        let mut destination = vec![i32::MIN; if ruled { stops } else { 0 }];
        let mut departures: Vec<Vec<(i32, u32, u32, u32)>> = vec![Vec::new(); if ruled { stops } else { 0 }];
        for &(s, secs) in ends.to.iter() {
            if !asked.closed(s) {
                let at = time - secs as i32;
                offer(&mut due[s as usize], (-at, 0, 0));
                if ruled {
                    destination[s as usize] = destination[s as usize].max(at);
                }
            }
        }
        for &(s, secs) in ends.from.iter() {
            if !asked.closed(s) {
                first[s as usize] = first[s as usize].min(secs);
            }
        }
        let mut front: Vec<Way> = Vec::new();
        let since = q.followed().0.min(time).max(since);
        let start = self.by_arrival.partition_point(|i| self.connections[*i as usize].arrive > time);
        for i in &self.by_arrival[start..] {
            let c = &self.connections[*i as usize];
            if c.arrive < since || front.iter().any(|f| -f.0 >= c.arrive && f.1 == 0 && f.2 <= 1) {
                break;
            }
            if c.depart < since {
                continue;
            }
            let ride = c.ride as usize;
            if c.alight && !asked.closed(c.to) && !asked.ride_closed(c.ride) {
                let (trip, line) = self.rides[ride];
                let here = sum([asked.used(c.to), asked.called(c.to), asked.ride_cost(c.ride)]);
                let mut alight = |onward: Way| {
                    if onward.2 < most && !front.iter().any(|f| -f.0 >= c.arrive && f.1 <= onward.1 && f.2 <= onward.2 + 1) {
                        offer(&mut on[ride], (0, sum([onward.1, here]), onward.2 + 1));
                    }
                };
                if ruled && self.rules_out_of(c.to).iter().any(|i| fits(tt.rule(*i).from_trip, trip) && fits(tt.rule(*i).from_line, line)) {
                    self.leads_on_ways(planner, q, &destination, &departures, c.to, c.ride, c.arrive).into_iter().for_each(&mut alight);
                } else {
                    due[c.to as usize].iter().filter(|l| -l.0 >= c.arrive).copied().for_each(&mut alight);
                }
            }
            if on[ride].is_empty() {
                continue;
            }
            if asked.closed_through(c.from) {
                on[ride].clear();
                continue;
            }
            if asked.called(c.from) > 0 {
                on[ride].iter_mut().for_each(|a| a.1 = sum([a.1, asked.called(c.from)]));
            }
            if !c.board || asked.closed(c.from) {
                continue;
            }
            let from = c.from as usize;
            for k in 0..on[ride].len() {
                let (cost, rides) = (sum([on[ride][k].1, asked.used(c.from)]), on[ride][k].2);
                if first[from] != NONE && c.depart - first[from] as i32 >= since {
                    offer(&mut front, (-(c.depart - first[from] as i32), cost, rides));
                }
                if ruled && self.departures_kept[from] {
                    departures[from].push((c.depart, c.ride, cost, rides));
                }
                if offer(&mut left[from], (-c.depart, cost, rides)) {
                    offer(&mut due[from], (-c.depart.saturating_sub(self.change(tt, q, c.from)), cost, rides));
                    for &(y, secs) in self.walks_into(c.from) {
                        if !asked.closed(y) {
                            offer(&mut due[y as usize], (-(c.depart - secs as i32), cost, rides));
                        }
                    }
                }
            }
        }
        let mut ways: Vec<Way> = front.into_iter().map(|f| (-f.0, f.1, f.2)).collect();
        ways.sort_unstable();
        ways
    }

    /// How an answer for one moment differs from what the scan with a cost and a count of rides finds, in words;
    /// `None` when they agree. With wishes or without, weighed or not, with a walk all the way or without. (A
    /// window is `window_differs`'s.)
    ///
    /// Leaving at a moment, the answer's options with a ride, each as its arrival, its cost and its rides, are
    /// the scan's best ways from that moment (`ways`): every one of them and no other. Weighed
    /// ([`Query::weigh`]), they are what the rule leaves of the scan's: a way goes where another arrives no later
    /// with no more rides and is no later with its cost added. And each option leaves as late as it can: from
    /// the second after its departure the scan has no way that arrives no later at no more cost with no more
    /// rides.
    ///
    /// Arriving by a moment, the same turned round (`ways_back`): the options are the scan's best ways by
    /// departure, cost and rides (weighed: a way goes where another leaves no earlier with no more rides and no
    /// earlier with its cost taken off), and each is over as early as its departure allows.
    ///
    /// Between two points near enough to walk all the way, the walk is held as a window's is (`walk_of`), and the
    /// options with a ride are the ways that are in before the walk from the asked moment is (arriving by: that
    /// leave after the walk to it does).
    pub fn moment_differs(&self, planner: &Planner, q: &Query, journeys: &[Journey]) -> Option<String> {
        let asked = self.asked(planner, q);
        let walk = match walk_of(&asked.places, q, journeys) {
            Ok(walk) => walk,
            Err(wrong) => return Some(wrong),
        };
        let (time, back) = (q.time as i32, q.arrive_by);
        // an option and a way alike, by the end that counts: the arrival; arriving by, the departure negated
        let turned = |ways: Vec<Way>| -> Vec<Way> { if back { ways.into_iter().map(|w| (-w.0, w.1, w.2)).collect() } else { ways } };
        let mut ours: Vec<Way> = journeys.iter().filter(|j| j.rides > 0).map(|j| (if back { -j.depart } else { j.arrive }, j.penalty, j.rides)).collect();
        ours.sort_unstable();
        if let Some(twice) = ours.windows(2).find(|w| w[0] == w[1]) {
            return Some(format!("two options alike: {:?}", turned(vec![twice[0]])));
        }
        let mut theirs = if back {
            turned(self.ways_back_in(planner, q, &asked, time, walk.map_or(i32::MIN, |secs| time - secs + 1)))
        } else {
            self.ways_in(planner, q, &asked, time, walk.map_or(i32::MAX, |secs| time + secs - 1))
        };
        if q.weigh {
            let all = theirs.clone();
            theirs.retain(|a| !all.iter().any(|b| b != a && b.0 <= a.0 && b.2 <= a.2 && b.0 as i64 + b.1 as i64 <= a.0 as i64 + a.1 as i64));
        }
        theirs.sort_unstable();
        if ours != theirs {
            let only = |a: &[Way], b: &[Way]| turned(a.iter().filter(|w| !b.contains(w)).copied().collect());
            return Some(format!("{}, cost and rides: the planner alone has {:?}, the scan alone {:?}", if back { "departure" } else { "arrival" }, only(&ours, &theirs), only(&theirs, &ours)));
        }
        for j in journeys.iter().filter(|j| j.rides > 0) {
            let better = if back {
                self.ways_back_in(planner, q, &asked, j.arrive - 1, j.depart).into_iter().find(|w| w.0 >= j.depart && w.1 <= j.penalty && w.2 <= j.rides)
            } else {
                self.ways_in(planner, q, &asked, j.depart + 1, j.arrive).into_iter().find(|w| w.0 <= j.arrive && w.1 <= j.penalty && w.2 <= j.rides)
            };
            if let Some(w) = better {
                return Some(if back {
                    format!("the option leaving {} is over at {} with cost {} and {} rides: the scan has a way over before that, leaving {} with cost {} and {} rides", j.depart, j.arrive, j.penalty, j.rides, w.0, w.1, w.2)
                } else {
                    format!("the option arriving {} leaves at {} with cost {} and {} rides: the scan has a way that leaves after that, arriving {} with cost {} and {} rides", j.arrive, j.depart, j.penalty, j.rides, w.0, w.1, w.2)
                });
            }
        }
        None
    }

    /// The latest departure from the query's origin that reaches its destination by `time`.
    pub fn latest_departure(&self, planner: &Planner, q: &Query, time: i32) -> Option<i32> {
        self.latest_in(planner, q, &self.asked(planner, q), time)
    }

    fn latest_in(&self, planner: &Planner, q: &Query, asked: &Asked, time: i32) -> Option<i32> {
        debug_assert_eq!(self.day, q.day);
        let tt = planner.timetable();
        let ends = &asked.places;
        let stops = tt.stops() as usize;
        // by when one must be at a stop, off a ride; when the latest useful ride leaves it; the walk from the origin
        let (mut due, mut left, mut first) = (vec![i32::MIN; stops], vec![i32::MIN; stops], vec![NONE; stops]);
        let mut on = vec![false; self.rides.len()];
        // holding to the feed's rules: the destination's stops apart, and the departures where a rule may tell them apart
        let ruled = q.rules && !self.out.is_empty();
        let mut destination = vec![i32::MIN; if ruled { stops } else { 0 }];
        let mut departures: Vec<Vec<(i32, u32)>> = vec![Vec::new(); if ruled { stops } else { 0 }];
        for &(s, secs) in ends.to.iter() {
            if !asked.closed(s) {
                due[s as usize] = due[s as usize].max(time - secs as i32);
                if ruled {
                    destination[s as usize] = due[s as usize];
                }
            }
        }
        for &(s, secs) in ends.from.iter() {
            if !asked.closed(s) {
                first[s as usize] = secs;
            }
        }
        let mut best = i32::MIN;
        let since = q.followed().0.min(time);
        let start = self.by_arrival.partition_point(|i| self.connections[*i as usize].arrive > time);
        for i in &self.by_arrival[start..] {
            let c = &self.connections[*i as usize];
            if c.arrive <= best || c.arrive < since {
                break;
            }
            if !on[c.ride as usize] {
                let (trip, line) = self.rides[c.ride as usize];
                if !c.alight || asked.closed(c.to) || asked.ride_closed(c.ride) {
                    continue;
                }
                let spoken_of = ruled && self.rules_out_of(c.to).iter().any(|i| fits(tt.rule(*i).from_trip, trip) && fits(tt.rule(*i).from_line, line));
                let leads_on = if spoken_of { self.leads_on(planner, q, &destination, &departures, c.to, c.ride, c.arrive) } else { due[c.to as usize] >= c.arrive };
                if !leads_on {
                    continue;
                }
                on[c.ride as usize] = true;
            }
            if asked.closed_through(c.from) {
                on[c.ride as usize] = false;
                continue;
            }
            if !c.board || asked.closed(c.from) || c.depart < since {
                continue;
            }
            if first[c.from as usize] != NONE && c.depart - first[c.from as usize] as i32 >= since {
                best = best.max(c.depart - first[c.from as usize] as i32);
            }
            if ruled && self.departures_kept[c.from as usize] {
                departures[c.from as usize].push((c.depart, c.ride));
            }
            if c.depart > left[c.from as usize] {
                left[c.from as usize] = c.depart;
                due[c.from as usize] = due[c.from as usize].max(c.depart.saturating_sub(self.change(tt, q, c.from)));
                for &(y, secs) in self.walks_into(c.from) {
                    if !asked.closed(y) {
                        due[y as usize] = due[y as usize].max(c.depart - secs as i32);
                    }
                }
            }
        }
        (best != i32::MIN).then_some(best)
    }

    /// Every best pair of departure and arrival for leaving inside the query's window: no other leaves later and
    /// arrives no later. By scanning forward for the earliest arrival, back for the latest departure that still
    /// makes it, and on from a second after that. Asked to arrive by, the window lies before the asked moment and
    /// holds arrivals: the same from its late end, back for the latest departure, forward for the earliest arrival
    /// that departure gives, and on from a second before that.
    pub fn options(&self, planner: &Planner, q: &Query) -> Vec<(i32, i32)> {
        self.options_in(planner, q, &self.asked(planner, q))
    }

    fn options_in(&self, planner: &Planner, q: &Query, asked: &Asked) -> Vec<(i32, i32)> {
        let mut out = Vec::new();
        let mut time = q.time as i32;
        if q.arrive_by {
            let begin = time - q.window as i32;
            while time >= begin && out.len() < 4096 {
                let Some(depart) = self.latest_in(planner, q, asked, time) else { break };
                let Some(arrive) = self.earliest_in(planner, q, asked, depart).map(|a| a.0) else { break };
                if arrive < begin || arrive > time {
                    break;
                }
                out.push((depart, arrive));
                time = arrive - 1;
            }
            out.reverse();
            return out;
        }
        let end = time + q.window as i32;
        while time <= end && out.len() < 4096 {
            let Some(arrive) = self.earliest_in(planner, q, asked, time).map(|a| a.0) else { break };
            let Some(depart) = self.latest_in(planner, q, asked, arrive) else { break };
            if depart > end || depart < time {
                break;
            }
            out.push((depart, arrive));
            time = depart + 1;
        }
        out
    }

    /// How a window's journeys from the planner differ from what the scan finds, in words; `None` when they agree.
    ///
    /// They agree when every pair of the scan is among the planner's best pairs, and every further pair of the
    /// planner's is one the scan can't list: it is no faster than the scan's best from that moment, and what
    /// leaves later and arrives no later leaves after the window (the planner keeps such a journey when it has
    /// fewer rides than the later one). Asked to arrive by, the same turned round: a further pair leaves no later
    /// than the scan's best for that arrival, and what arrives sooner and leaves no earlier arrives before the
    /// window.
    ///
    /// Between two points near enough to walk all the way, the walk must be among the journeys, once, from the
    /// asked moment (`walk_of`), and a ride that takes no less time than it is none: the scan's pairs are those
    /// that take less.
    pub fn window_differs(&self, planner: &Planner, q: &Query, journeys: &[Journey]) -> Option<String> {
        let asked = self.asked(planner, q);
        let walk = match walk_of(&asked.places, q, journeys) {
            Ok(walk) => walk,
            Err(wrong) => return Some(wrong),
        };
        let (ours, mut theirs) = (pairs(journeys), self.options_in(planner, q, &asked));
        if let Some(secs) = walk {
            theirs.retain(|p| p.1 - p.0 < secs);
        }
        if let Some(missing) = theirs.iter().find(|p| !ours.contains(p)) {
            return Some(format!("the scan has leaving {} arriving {}, the planner has not ({} pairs against {})", missing.0, missing.1, ours.len(), theirs.len()));
        }
        if q.arrive_by {
            let begin = q.time as i32 - q.window as i32;
            for p in ours.iter().filter(|p| !theirs.contains(p)) {
                let best = self.latest_in(planner, q, &asked, p.1);
                if best.is_none_or(|d| d < p.0) {
                    return Some(format!("the planner has leaving {} arriving {}, which the scan says can't be done: {:?}", p.0, p.1, best));
                }
                if best == Some(p.0) && self.earliest_in(planner, q, &asked, p.0).is_none_or(|a| a.0 >= begin) {
                    return Some(format!("the planner has leaving {} arriving {}, which the scan should list and does not", p.0, p.1));
                }
            }
            return None;
        }
        let end = q.time as i32 + q.window as i32;
        for p in ours.iter().filter(|p| !theirs.contains(p)) {
            // A further pair may arrive later than the scan's best from then: the better journey then leaves after
            // the window, or the planner would have had the scan's pair for it and dropped this one.
            let best = self.earliest_in(planner, q, &asked, p.0).map(|a| a.0);
            if best.is_none_or(|a| a > p.1) {
                return Some(format!("the planner has leaving {} arriving {}, which the scan says can't be done: {:?}", p.0, p.1, best));
            }
            if best == Some(p.1) && self.latest_in(planner, q, &asked, p.1).is_none_or(|d| d <= end) {
                return Some(format!("the planner has leaving {} arriving {}, which the scan should list and does not", p.0, p.1));
            }
        }
        None
    }
}

/// The walk all the way among an answer's journeys, as its seconds, held to what the scan reads of the query's
/// places: where the search begins at other stops than the scan, that is said; where two points are near enough by
/// any measure the walk must be there, where they are not points or too far apart by any measure it must not be;
/// and it is there once, as long as the scan has it, from the asked moment (arriving by: ending at it).
pub fn walk_of(places: &Places, q: &Query, journeys: &[Journey]) -> Result<Option<i32>, String> {
    if let Some(wrong) = &places.wrong {
        return Err(wrong.clone());
    }
    let walks: Vec<&Journey> = journeys.iter().filter(|j| j.rides == 0).collect();
    match (places.on_foot, &walks[..]) {
        (None, []) | (Some((_, _, false)), []) => Ok(None),
        (Some(_), []) => Err("two points near enough to walk all the way, and no walk among the journeys".into()),
        (None, _) => Err("a walk all the way between places that are not two points near enough".into()),
        (Some(_), [_, _, ..]) => Err("more than one walk all the way".into()),
        (Some((least, most, _)), [walk]) => {
            let secs = walk.arrive - walk.depart;
            let begins = if q.arrive_by { q.time as i32 - secs } else { q.time as i32 };
            if secs < least as i32 || secs > most as i32 {
                Err(format!("a walk all the way of {secs} s, and by the scan it is {least} to {most}"))
            } else if walk.depart != begins {
                Err(format!("the walk all the way begins at {}, not at {begins}", walk.depart))
            } else {
                Ok(Some(secs))
            }
        }
    }
}

/// The best pairs of departure and arrival among journeys with a ride: none leaves later and arrives no later.
pub fn pairs(journeys: &[Journey]) -> Vec<(i32, i32)> {
    let mut all: Vec<(i32, i32)> = journeys.iter().filter(|j| j.rides > 0).map(|j| (j.depart, j.arrive)).collect();
    all.sort_unstable();
    all.dedup();
    all.iter().filter(|a| !all.iter().any(|b| b != *a && b.0 >= a.0 && b.1 <= a.1)).copied().collect()
}

/// Whether a journey can be made as it says: every ride is a trip of the file on its day with those times and
/// stops, boarding and alighting are allowed there, every walk is one of the file's with its seconds, every change
/// leaves the time it needs and is none the feed's rules bar (where the query holds to them; a vehicle that waits
/// needs no time), the ends are the query's with their seconds on foot, no wish of "never" is broken and the cost
/// it says is what its wishes come to (`wished`). What is wrong, in words.
///
/// It reads the file and the query, and nothing of how the journey was found. The stops a place stands for and
/// the seconds on foot to them it works out by itself (`places`), a point's from the coordinates by the scan's own
/// distance; where the search begins at other stops, or has a walk longer or shorter than that distance allows,
/// that is what is wrong, of whatever journey. So is a walk all the way that is not between two points near enough,
/// or not as long as their distance says.
pub fn impossible(planner: &Planner, q: &Query, j: &Journey) -> Option<String> {
    let tt: &Timetable = planner.timetable();
    let ends = places(planner, q);
    if let Some(wrong) = &ends.wrong {
        return Some(wrong.clone());
    }
    let Some((first, last)) = j.legs.first().zip(j.legs.last()) else { return Some("no legs".into()) };
    if (j.depart, j.arrive) != (first.depart(), last.arrive()) {
        return Some(format!("it says {} to {}, its legs {} to {}", j.depart, j.arrive, first.depart(), last.arrive()));
    }
    if q.arrive_by && j.arrive > q.time as i32 || !q.arrive_by && j.depart < q.time as i32 {
        return Some(format!("{} to {} is on the wrong side of the asked {}", j.depart, j.arrive, q.time));
    }
    if q.window > 0 && (q.arrive_by && j.arrive < q.time as i32 - q.window as i32 || !q.arrive_by && j.depart > (q.time + q.window) as i32) {
        return Some(format!("{} to {} is outside the window", j.depart, j.arrive));
    }
    let (mut rides, mut walking) = (0u32, 0u32);
    let walk_between = |a: u32, b: u32| {
        let (lo, hi) = tt.walks_from(a);
        (lo..hi).map(|i| tt.walk(i)).find(|w| w.0 == b).map(|w| w.1 as i32)
    };
    // What the feed's rules say of a change between two rides, each as (stop, trip, line, day, the moment it is
    // in or leaves): (barred, the second waits, the least seconds). It waits where a rule says so and it is the
    // one vehicle that rule holds for the first: nothing else the rule speaks of leaves between the two.
    let ruled = |from: (u32, u32, u32, i32, i32), to: (u32, u32, u32, i32, i32)| {
        let (mut barred, mut waits, mut least) = (false, false, 0i32);
        if !q.rules {
            return (barred, waits, least);
        }
        let (mut lo, mut hi) = (0u32, tt.rules());
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if tt.rule(mid).from_stop < from.0 { lo = mid + 1 } else { hi = mid }
        }
        for i in lo..tt.rules() {
            let r = tt.rule(i);
            if r.from_stop != from.0 {
                break;
            }
            let fits = |want: u32, is: u32| want == NONE || want == is;
            let named = r.from_trip != NONE || r.to_trip != NONE || r.from_line != NONE || r.to_line != NONE;
            if !named || r.to_stop != to.0 || !(fits(r.from_trip, from.1) && fits(r.from_line, from.2) && fits(r.to_trip, to.1) && fits(r.to_line, to.2)) {
                continue;
            }
            match r.kind {
                1 | 4 => waits = waits || (from.1, from.3) != (to.1, to.3) && to.4 >= from.4 && !leaves_between(tt, q.day, to.0, r.to_trip, r.to_line, from.4, to.4, (from.1, from.3)),
                2 if r.min_time != NONE => least = least.max(r.min_time.min(1 << 20) as i32),
                3 => barred = true,
                _ => {}
            }
        }
        (barred, waits, least)
    };
    let ride_of = |l: &Leg, end: bool| match *l {
        Leg::Ride { trip, route, day, from, to, depart, arrive, .. } => Some((if end { to } else { from }, trip, tt.route(route).line, day, if end { arrive } else { depart })),
        _ => None,
    };
    for (i, leg) in j.legs.iter().enumerate() {
        if i > 0 && leg.depart() < j.legs[i - 1].arrive() {
            return Some(format!("leg {i} leaves before the one before it arrives"));
        }
        match *leg {
            Leg::Ride { trip, route, day, board, alight, from, to, depart, arrive } => {
                rides += 1;
                let r = tt.route(route);
                if trip >= tt.trips() || tt.route_of_trip(trip) != route || board >= alight || alight >= r.stops {
                    return Some(format!("leg {i}: no such ride"));
                }
                if !tt.runs(tt.trip(trip).service, day) {
                    return Some(format!("leg {i}: trip {trip} does not run on day {day}"));
                }
                let (a, b) = (tt.pattern_stop(r.first_stop + board), tt.pattern_stop(r.first_stop + alight));
                let times = tt.trip_times(trip);
                let base = day_start(tt, q.day, day.saturating_sub(q.day));
                if (a.stop, b.stop) != (from, to) || depart != base + tt.time(times, board).1 as i32 || arrive != base + tt.time(times, alight).0 as i32 {
                    return Some(format!("leg {i}: its stops or times are not the trip's"));
                }
                if a.pickup == 1 || b.drop_off == 1 {
                    return Some(format!("leg {i}: no boarding or no alighting there"));
                }
                // the change from the ride before
                let before = j.legs[..i].iter().rev().find(|l| matches!(l, Leg::Ride { .. }));
                if let Some((before, (stop, at))) = before.and_then(|l| Some((ride_of(l, true)?, (ride_of(l, true)?.0, l.arrive())))) {
                    let (barred, waits, least) = ruled(before, (from, trip, r.line, day, depart));
                    if barred {
                        return Some(format!("leg {i}: the feed says no change is possible from the ride before"));
                    }
                    let needs = if waits { 0 } else if stop == from { change_time(tt, q, stop).max(least) } else { walk_between(stop, from).unwrap_or(i32::MAX / 2).max(least) };
                    if depart < at + needs {
                        return Some(format!("leg {i}: the change from the ride before leaves {} s and needs {needs}", depart - at));
                    }
                    if stop != from && !matches!(j.legs[i - 1], Leg::Walk { from: Spot::Stop(x), to: Spot::Stop(y), .. } if (x, y) == (stop, from)) {
                        return Some(format!("leg {i}: no walk leads to where it is boarded"));
                    }
                }
            }
            Leg::Walk { from, to, depart, arrive } => {
                let secs = arrive - depart;
                walking += secs.max(0) as u32;
                let known = match (from, to) {
                    (Spot::Origin, Spot::Stop(s)) => ends.from.iter().find(|e| e.0 == s).map(|e| e.1 as i32),
                    (Spot::Stop(s), Spot::Destination) => ends.to.iter().find(|e| e.0 == s).map(|e| e.1 as i32),
                    (Spot::Stop(a), Spot::Stop(b)) => {
                        // to a vehicle that waits, the walk has all the time there is
                        let around = i.checked_sub(1).and_then(|k| ride_of(&j.legs[k], true)).zip(j.legs.get(i + 1).and_then(|l| ride_of(l, false)));
                        let whole_gap = Some((depart, arrive)) == i.checked_sub(1).and_then(|k| j.legs.get(k)).zip(j.legs.get(i + 1)).map(|(x, y)| (x.arrive(), y.depart()));
                        if around.is_some_and(|(x, y)| ruled(x, y).1) && whole_gap { Some(secs) } else { walk_between(a, b) }
                    }
                    // all the way: between two points near enough, in the seconds the scan has for it
                    (Spot::Origin, Spot::Destination) => match ends.on_foot {
                        Some((least, most, _)) if j.legs.len() == 1 && (least as i32..=most as i32).contains(&secs) => Some(secs),
                        Some((least, most, _)) => return Some(format!("leg {i}: a walk all the way of {secs} s, and by the scan it is {least} to {most}")),
                        None => return Some(format!("leg {i}: a walk all the way between places that are not two points near enough")),
                    },
                    _ => None,
                };
                if known != Some(secs) {
                    return Some(format!("leg {i}: a walk of {secs} s where the file or the query has {known:?}"));
                }
            }
        }
    }
    if let Leg::Ride { from, .. } = first {
        if !ends.from.contains(&(*from, 0)) {
            return Some("it starts with a ride from a stop that is not the origin".into());
        }
    }
    if let Leg::Ride { to, .. } = last {
        if !ends.to.contains(&(*to, 0)) {
            return Some("it ends with a ride to a stop that is not the destination".into());
        }
    }
    if (rides, walking) != (j.rides, j.walking) {
        return Some(format!("it says {} rides and {} s on foot, its legs {rides} and {walking}", j.rides, j.walking));
    }
    match wished(tt, q, j) {
        Err(never) => return Some(never),
        Ok(cost) if cost != j.penalty => return Some(format!("it says its wishes cost {} s, they cost {cost}", j.penalty)),
        Ok(_) => {}
    }
    None
}
