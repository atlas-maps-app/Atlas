//! The search: journeys by rounds, one ride more a round (RAPTOR, Delling, Pajor and Werneck), over a window of
//! departures (its range form: one search a departure, the latest first, each building on the ones before), with a
//! bag of labels a stop where wishes cost something (its multi-criteria form, on arrival and cost).
//!
//! **One code for both directions.** "Arrive by" is the same search on time turned round: every moment is negated,
//! a route is walked from its last stop, its trips from the last, boarding and alighting swap, and walks are taken
//! against their direction. Below, *time* means this direction's time, and *board*, *alight*, *first* and *last* are
//! meant in the direction searched.
//!
//! **The feed's rules between trips** (a change that is not possible, a vehicle that waits for another) make an
//! arrival by one trip unlike an arrival by any other: an earlier one may be barred from a tram a later one may
//! take. So an arrival by a trip that a rule names at that stop is kept out of the stop's bag, where earlier beats
//! later: it is *held* apart, beats nothing, and is boarded from under its rules.
//!
//! **The search is aimed.** From the journey's end, the least time to it that any timetable could give is counted
//! back stop by stop: the shortest way over each pattern's fastest hops and the walks, by Dijkstra's method. A
//! label that even so could not end in time is not made.
//! - *Patiently* ([`crate::Limits::patient`], the default without wishes that cost): the least time of the whole
//!   journey says how long a journey may sensibly take (twice that and half an hour; an hour at least), and the
//!   search follows nothing past it. The count from the end goes only as far as the search asks. When nothing is
//!   found by then, the search is made again as far as the horizon, unaimed.
//! - *Over a window*, the first search (from the window's far end) says how late any option may end. Every stop
//!   within that reach then gets its least time and its fewest rides for good, and the searches for the earlier
//!   departures make no label that could not end sooner than a journey leaving later already does.
//!
//! **What bounds it** ([`Limits`]): rounds by `max_rides`; searches by `departures`; labels and steps by their
//! caps; time by the window and the horizon. Every loop over the file's data counts steps.

use crate::file::{MAX_TIME, PatternStop, Route, Rule, Timetable};
use crate::geo::distance_mm;
use crate::journey::{Journey, Leg, Spot};
use crate::query::{MAX_PENALTY, MAX_QUERY_TIME, MAX_RIDES, MAX_WINDOW, Place, Prefs, Query, bit, cost};
use crate::NONE;
use alloc::collections::{BinaryHeap, VecDeque};
use alloc::vec;
use alloc::vec::Vec;
use core::cmp::Reverse;

const DAY: i32 = 86_400;
const ACCESS: u8 = 0;
const RIDE: u8 = 1;
const CHANGE: u8 = 2;
const DEAD: u8 = 0x80;
const NEVER: i32 = i32::MAX;
/// The most stops a place may stand for.
const MAX_ENDS: usize = 4096;

/// Whether a rule's field, which may name nothing, lets this trip or line through.
fn fits(want: u32, is: u32) -> bool {
    want == NONE || want == is
}

/// A moment at a stop, and how one got there.
#[derive(Clone, Copy)]
struct Label {
    time: i32,
    cost: u32,
    /// The label before: for a ride the one it was boarded from, for a change the ride that ended. `NONE` at the
    /// first stop.
    prev: u32,
    /// The next label of the same stop and round.
    next: u32,
    stop: u32,
    /// A ride: its trip. The first stop: the seconds on foot to it.
    trip: u32,
    /// A ride: where along the pattern it is boarded and left, in real order.
    from: u16,
    to: u16,
    /// A ride: its trip's service day, counted from the query's.
    off: i8,
    kind: u8,
}

/// A trip being ridden along a route, in a round.
#[derive(Clone, Copy)]
struct Riding {
    /// The trip, and its times.
    trip: u32,
    times: (u32, u32),
    off: i32,
    cost: u32,
    /// The label it was boarded from, and where along the pattern.
    from: u32,
    pos: u32,
}

/// A journey found: the last ride's label, the seconds on foot after it, and what it comes to.
#[derive(Clone, Copy)]
struct Found {
    time: i32,
    cost: u32,
    rides: u32,
    label: u32,
    secs: u32,
}

/// The previous ride, for the feed's rules about changing from it.
#[derive(Clone, Copy)]
struct Before {
    /// Its rules for this pair of stops: a run in the order searched (the file's, or `rules_by_to`).
    lo: u32,
    hi: u32,
    trip: u32,
    line: u32,
    time: i32,
}

/// A label kept out of its stop's bag, because the feed has a rule for the trip it came by: ready to board at its
/// stop under those rules, or, with `rule`, only what that rule says waits for it.
#[derive(Clone, Copy)]
struct Held {
    label: u32,
    rule: u32,
}

/// What a planner derives from a file once.
struct Index {
    /// Every stop, by latitude.
    by_lat: Vec<u32>,
    /// The walks into each stop: where from, and the seconds.
    back_first: Vec<u32>,
    back: Vec<(u32, u32)>,
    /// The rules between trips or lines, sorted by the stop changed to, then the stop changed from.
    rules_by_to: Vec<u32>,
    /// A bit a stop: a rule changes from here; to here.
    rule_from: Vec<u64>,
    rule_to: Vec<u64>,
    /// What the rules that bind (not possible, least time, the next one waits, stay seated) are about, sorted, for
    /// the search forward and the search back: (stop, trip) changed from, (stop, line) changed from, and the stops
    /// where a rule names only what is changed to.
    bound: [Bound; 2],
    /// No trip's time in its service day passes this.
    max_time: i32,
    /// Beside the file's pattern stops: the least seconds any trip takes from that stop of the pattern to the next.
    hop: Vec<u32>,
}

#[derive(Default)]
struct Bound {
    trips: Vec<(u32, u32)>,
    lines: Vec<(u32, u32)>,
    stops: Vec<u32>,
}

/// The trips that run on one day, route by route in each route's order: what a boarding searches, so that it never
/// steps over the trips of the file's other days.
struct DayTrips {
    day: i32,
    /// By route, and one more: where its trips of the day start in `trips`.
    first: Vec<u32>,
    trips: Vec<u32>,
    /// Beside `trips`: each one's first departure and where its times lie, so that finding the trip to board reads
    /// a few lines of memory side by side and not the file's trips all over.
    times: Vec<(u32, u32)>,
    /// By route: its earliest first departure of the day, and the latest time any of its trips has. A boarding
    /// that can't fall between them needs no search.
    span: Vec<(u32, u32)>,
}

/// A route's trips of one day.
#[derive(Clone, Copy)]
struct Run<'s> {
    trips: &'s [u32],
    times: &'s [(u32, u32)],
    span: (u32, u32),
}

const NO_RUN: Run<'static> = Run { trips: &[], times: &[], span: (u32::MAX, 0) };

impl DayTrips {
    fn of(tt: &Timetable, day: i32) -> DayTrips {
        let mut first = Vec::with_capacity(tt.routes() as usize + 1);
        let (mut trips, mut times, mut span) = (Vec::new(), Vec::new(), Vec::with_capacity(tt.routes() as usize));
        for r in 0..tt.routes() {
            first.push(trips.len() as u32);
            let route = tt.route(r);
            let (mut earliest, mut latest) = (u32::MAX, 0);
            for t in route.first_trip..route.first_trip.saturating_add(route.trips) {
                if tt.runs(tt.trip(t).service, day) {
                    let at = tt.trip_times(t);
                    trips.push(t);
                    times.push(at);
                    earliest = earliest.min(at.0);
                    latest = latest.max(tt.time(at, route.stops.saturating_sub(1)).1);
                }
            }
            span.push((earliest, latest));
        }
        first.push(trips.len() as u32);
        DayTrips { day, first, trips, times, span }
    }
}

/// How many days' lists a planner keeps.
const KEPT_DAYS: usize = 16;

/// A stop in a round: the first label of the bag of those who arrived there by a ride (0) and of those ready to
/// board (1), and the earliest time of any such label with that many rides or fewer. Together, so that a route's
/// walk past a stop reads one line of memory.
#[derive(Clone, Copy)]
struct Cell {
    head: [u32; 2],
    best: [i32; 2],
}

const EMPTY: Cell = Cell { head: [NONE; 2], best: [NEVER; 2] };

/// What a search writes, kept between searches so that it is allocated once.
#[derive(Default)]
struct Work {
    labels: Vec<Label>,
    /// By stop and round (`stop * rounds + round`), a cell.
    rounds: usize,
    cells: Vec<Cell>,
    /// The stops written to, to wipe them; a stop is in it once.
    touched: Vec<u32>,
    is_touched: Vec<bool>,
    /// The seconds on foot from each stop to the journey's end, or `NONE`.
    last: Vec<u32>,
    last_set: Vec<u32>,
    /// By round: the stops whose ready bag gained a label in this search.
    marks: Vec<Vec<u32>>,
    mark_stamp: Vec<u32>,
    /// The routes to walk in this round, and from which stop along each.
    route_from: Vec<u32>,
    route_stamp: Vec<u32>,
    route_list: Vec<u32>,
    /// One more a round walked (for the routes), and one more a search (for the marks).
    stamp: u32,
    search: u32,
    riding: Vec<Riding>,
    fresh: Vec<u32>,
    /// The rides that ended this round by a trip the feed has a rule for at that stop.
    special: Vec<u32>,
    /// By round: the labels held apart, and where those of the search under way begin (the earlier ones boarded
    /// all they could in the search that made them); and a bit a round for each stop that has one.
    held: Vec<Vec<Held>>,
    held_from: Vec<usize>,
    held_rounds: Vec<u32>,
    /// By round: the rides held so far in this query, as (stop, trip, day offset, cost).
    special_seen: Vec<Vec<(u32, u32, i32, u32)>>,
    found: Vec<Found>,
    front: Vec<Found>,
    /// By rides: the earliest end of a journey found with that many or fewer.
    end_best: [i32; MAX_RIDES as usize + 1],
    /// Once a search is aimed: by stop, the least seconds and the fewest rides from there to the journey's end
    /// (`u32::MAX` and 255 where not worked out: further than `reach` seconds); and the stops that have them.
    least: Vec<u32>,
    fewest: Vec<u8>,
    aimed: Vec<u32>,
    reach: u32,
    /// By pattern, while the rides are counted: how far along it has been reached.
    along: Vec<u32>,
    /// The stops still open in the count of least seconds from the journey's end, the nearest first.
    heap: BinaryHeap<Reverse<(u32, u32)>>,
}

/// A timetable ready to be asked.
pub struct Planner<'a> {
    tt: Timetable<'a>,
    ix: Index,
    /// The lists of the days asked about lately.
    days: Vec<DayTrips>,
    work: Work,
}

/// What a search returns.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Answer {
    /// The options: none of them is beaten by another in departure, arrival, rides and cost together. By departure.
    pub journeys: Vec<Journey>,
    /// A cap of [`crate::Limits`] was met, so there may be options missing.
    pub cut_short: bool,
    /// How long past the asked moment and its window journeys were followed, in seconds: the query's horizon, or
    /// less where the search was patient ([`crate::Limits::patient`]).
    pub horizon: u32,
    /// How many searches (departures) it took, and labels, and steps.
    pub searches: u32,
    pub labels: u32,
    pub steps: u64,
}

/// The stops a journey can start at and end at, with the seconds on foot.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Ends {
    pub from: Vec<(u32, u32)>,
    pub to: Vec<(u32, u32)>,
}

impl<'a> Planner<'a> {
    pub fn new(tt: Timetable<'a>) -> Planner<'a> {
        let stops = tt.stops();
        let mut by_lat: Vec<u32> = (0..stops).collect();
        by_lat.sort_unstable_by_key(|s| (tt.stop(*s).lat, *s));
        // the walks, turned round
        let mut back_first = vec![0u32; stops as usize + 1];
        for i in 0..tt.walks() {
            back_first[tt.walk(i).0 as usize + 1] += 1;
        }
        for s in 0..stops as usize {
            back_first[s + 1] += back_first[s];
        }
        let mut at = back_first.clone();
        let mut back = vec![(0u32, 0u32); tt.walks() as usize];
        for s in 0..stops {
            let (lo, hi) = tt.walks_from(s);
            for i in lo..hi {
                let (to, secs) = tt.walk(i);
                back[at[to as usize] as usize] = (s, secs);
                at[to as usize] += 1;
            }
        }
        let words = (stops as usize).div_ceil(64);
        let (mut rule_from, mut rule_to) = (vec![0u64; words], vec![0u64; words]);
        let mut rules_by_to = Vec::new();
        let mut bound = [Bound::default(), Bound::default()];
        for i in 0..tt.rules() {
            let r = tt.rule(i);
            if r.from_trip == NONE && r.to_trip == NONE && r.from_line == NONE && r.to_line == NONE {
                continue; // a rule for everyone is in the walks already
            }
            if (1..=4).contains(&r.kind) {
                for (b, (stop, trip, line)) in bound.iter_mut().zip([(r.from_stop, r.from_trip, r.from_line), (r.to_stop, r.to_trip, r.to_line)]) {
                    if trip != NONE {
                        b.trips.push((stop, trip));
                    } else if line != NONE {
                        b.lines.push((stop, line));
                    } else {
                        b.stops.push(stop);
                    }
                }
            }
            rule_from[(r.from_stop >> 6) as usize] |= 1 << (r.from_stop & 63);
            rule_to[(r.to_stop >> 6) as usize] |= 1 << (r.to_stop & 63);
            rules_by_to.push(i);
        }
        rules_by_to.sort_unstable_by_key(|i| {
            let r = tt.rule(*i);
            (r.to_stop, r.from_stop, *i)
        });
        let mut latest = 0u32;
        for t in 0..tt.trips() {
            latest = latest.max(tt.trip(t).first_departure);
        }
        let mut longest = 0u32;
        for r in 0..tt.routes() {
            let route = tt.route(r);
            if route.trips > 0 && route.stops > 0 {
                // a route's trips never overtake, so its last trip ends last; a bound, not a promise
                for t in [route.first_trip, route.first_trip + route.trips - 1] {
                    let times = tt.trip_times(t);
                    longest = longest.max(tt.time(times, route.stops - 1).1.saturating_sub(times.0));
                }
            }
        }
        // each pattern's fastest hops: over the profiles its trips use, each once a route
        let mut hop = vec![u32::MAX; tt.pattern_stops() as usize];
        let mut folded = vec![NONE; tt.profiles() as usize];
        for r in 0..tt.routes() {
            let route = tt.route(r);
            for t in route.first_trip..route.first_trip.saturating_add(route.trips) {
                let profile = tt.trip(t).profile;
                match folded.get_mut(profile as usize) {
                    Some(f) if *f != r => *f = r,
                    _ => continue,
                }
                let times = tt.trip_times(t);
                for pos in 0..route.stops.saturating_sub(1) {
                    let took = tt.time(times, pos + 1).0.saturating_sub(tt.time(times, pos).1);
                    if let Some(h) = hop.get_mut((route.first_stop + pos) as usize) {
                        *h = (*h).min(took);
                    }
                }
            }
        }
        for b in bound.iter_mut() {
            b.trips.sort_unstable();
            b.trips.dedup();
            b.lines.sort_unstable();
            b.lines.dedup();
            b.stops.sort_unstable();
            b.stops.dedup();
        }
        let max_time = (latest.saturating_add(longest)).min(2 * MAX_TIME) as i32;
        Planner { tt, ix: Index { by_lat, back_first, back, rules_by_to, rule_from, rule_to, bound, max_time, hop }, days: Vec::new(), work: Work::default() }
    }

    pub fn timetable(&self) -> &Timetable<'a> {
        &self.tt
    }

    /// The stops a query's two places stand for, before any wish closes one.
    pub fn ends(&self, q: &Query) -> Ends {
        Ends { from: self.near(&q.from, q, true), to: self.near(&q.to, q, false) }
    }

    /// The stops of a place with the seconds on foot: `leaving` it, or reaching it.
    fn near(&self, place: &Place, q: &Query, leaving: bool) -> Vec<(u32, u32)> {
        let tt = &self.tt;
        let mut out: Vec<(u32, u32)> = Vec::new();
        let around = |s: u32, out: &mut Vec<(u32, u32)>| {
            out.push((s, 0));
            if leaving {
                let (lo, hi) = tt.walks_from(s);
                out.extend((lo..hi).take(MAX_ENDS).map(|i| tt.walk(i)));
            } else if let (Some(&lo), Some(&hi)) = (self.ix.back_first.get(s as usize), self.ix.back_first.get(s as usize + 1)) {
                out.extend(self.ix.back.get(lo as usize..hi as usize).unwrap_or(&[]).iter().take(MAX_ENDS));
            }
        };
        match place {
            Place::Stop(s) if *s < tt.stops() => around(*s, &mut out),
            Place::Stop(_) => {}
            Place::Station(st) => {
                for s in 0..tt.stops() {
                    if (s == *st || tt.stop(s).parent == *st) && out.len() < 8 * MAX_ENDS {
                        around(s, &mut out);
                    }
                }
            }
            Place::Point { lat, lon } => {
                let reach = q.reach_m.min(50_000) as i64;
                let span = (reach * 10_000_000 / 111_195 + 2) as i32;
                let (south, north) = (lat.saturating_sub(span), lat.saturating_add(span));
                let speed = q.walk_mm_s.clamp(100, 100_000) as u64;
                let from = self.ix.by_lat.partition_point(|s| tt.stop(*s).lat < south);
                for &s in self.ix.by_lat[from..].iter().take_while(|s| tt.stop(**s).lat <= north) {
                    let st = tt.stop(s);
                    let (lo, hi) = tt.routes_at(s);
                    if st.kind != 0 || st.place == 2 || lo == hi {
                        continue;
                    }
                    let mm = distance_mm(*lat, *lon, st.lat, st.lon);
                    if mm <= reach as u64 * 1000 {
                        out.push((s, (mm * 130 / 100).div_ceil(speed).min(MAX_TIME as u64 - 1) as u32));
                    }
                }
            }
            Place::Walks(v) => out.extend(v.iter().take(MAX_ENDS).filter(|e| e.0 < tt.stops() && e.1 < MAX_TIME)),
        }
        out.sort_unstable();
        out.dedup_by_key(|e| e.0); // sorted by stop then seconds: the first of a stop is its shortest
        if out.len() > MAX_ENDS {
            out.sort_unstable_by_key(|e| (e.1, e.0));
            out.truncate(MAX_ENDS);
            out.sort_unstable();
        }
        out
    }

    /// The wishes of a query, worked out.
    pub(crate) fn prefs(&self, q: &Query) -> Prefs {
        Prefs::of(&self.tt, &q.wishes, &self.ix.by_lat)
    }

    pub(crate) fn walks_into(&self, stop: u32) -> &[(u32, u32)] {
        match (self.ix.back_first.get(stop as usize), self.ix.back_first.get(stop as usize + 1)) {
            (Some(&lo), Some(&hi)) => self.ix.back.get(lo as usize..hi as usize).unwrap_or(&[]),
            _ => &[],
        }
    }

    /// The days before and after the query's whose trips can matter to a search over these real seconds.
    pub(crate) fn offsets(&self, earliest: i32, latest: i32) -> (i32, i32) {
        let lo = (earliest - self.ix.max_time).div_euclid(DAY);
        (lo, latest.div_euclid(DAY).min(lo + 15))
    }

    /// Finds the journeys a query asks for.
    pub fn plan(&mut self, q: &Query) -> Answer {
        let ends = self.ends(q);
        let prefs = self.prefs(q);
        let back = q.arrive_by;
        let time = q.time.min(MAX_QUERY_TIME) as i32;
        let window = q.window.min(MAX_WINDOW) as i32;
        let (earliest, latest) = q.followed();
        let horizon = latest - earliest - window;
        let (off_lo, off_hi) = self.offsets(earliest, latest);
        let day = q.day.clamp(-100_000, 100_000);
        // the lists of the days this search can touch; a day outside the file has no trips and needs none
        let mut day_lists = [usize::MAX; KEPT_DAYS];
        for off in off_lo..=off_hi {
            let d = day + off;
            if d < 0 || d >= self.tt.days() as i32 {
                continue;
            }
            if !self.days.iter().any(|l| l.day == d) {
                if self.days.len() >= KEPT_DAYS {
                    // make room: a day this search does not touch goes
                    if let Some(old) = self.days.iter().position(|l| l.day < day + off_lo || l.day > day + off_hi) {
                        self.days.swap_remove(old);
                    }
                }
                self.days.push(DayTrips::of(&self.tt, d));
            }
        }
        for off in off_lo..=off_hi {
            if let Some(at) = self.days.iter().position(|l| l.day == day + off) {
                day_lists[(off - off_lo) as usize] = at;
            }
        }
        let start = if back { -time } else { time };
        let max_rides = q.max_rides.clamp(1, MAX_RIDES) as usize;
        let (first, last) = if back { (&ends.to, &ends.from) } else { (&ends.from, &ends.to) };
        let mut s = Search {
            tt: self.tt,
            ix: &self.ix,
            w: &mut self.work,
            prefs: &prefs,
            soft: prefs.soft(),
            back,
            day,
            days: &self.days,
            day_lists,
            off_lo,
            off_hi,
            limit: start + window + horizon,
            change: q.change_s.unwrap_or(self.tt.change_seconds()).min(MAX_TIME - 1) as i32,
            rules: q.rules && !self.ix.rules_by_to.is_empty(),
            max_rides,
            bag: if prefs.soft() { q.limits.bag.clamp(1, 16) as usize } else { 1 },
            max_labels: q.limits.labels.clamp(1024, 16_000_000) as usize,
            max_steps: q.limits.steps.clamp(10_000, 10_000_000_000),
            steps: 0,
            aimed: false,
            lazy: false,
            cut: false,
            stops: self.tt.stops() as usize,
        };
        // Patiently: first only as far as a journey could sensibly take (twice the least time any timetable could
        // give it, and half an hour; an hour at least), with the search aimed from its start. Only when nothing is
        // found by then, as far as the horizon.
        let far = s.limit;
        let mut searches = 0u32;
        let mut passes: [Option<i32>; 2] = [None, Some(far)];
        s.reset(last);
        // (not where wishes cost: the option that follows them best is the slower one, and is what was asked for)
        if q.limits.patient && !s.soft {
            match s.aim_lazily(last, first) {
                Some(least) => passes[0] = Some(far.min(start + window + (2 * least.min(MAX_TIME) as i32 + 1800).max(3600))),
                None => passes[1] = None, // no timetable could make this journey: there is nothing to search
            }
        }
        for (pass, limit) in passes.into_iter().enumerate() {
            let Some(limit) = limit else { continue };
            if pass == 1 && passes[0].is_some() {
                if !s.w.found.is_empty() || passes[0] == Some(far) || s.cut {
                    break;
                }
                s.reset(last);
                (s.aimed, s.lazy) = (false, false);
            }
            s.limit = limit;
            // The first search is from the window's far end, with every first stop: for one moment that is the
            // whole answer. For a window it is what the searches inside build on, and what shows which of their
            // journeys a later departure makes pointless.
            searches += 1;
            s.begin();
            for &(stop, secs) in first.iter() {
                s.offer_first(stop, start + window + secs as i32, secs);
            }
            s.rounds();
            if window == 0 {
                continue;
            }
            // how late any option may end: the end of the one with the fewest rides. With that, every stop in reach
            // gets its least time and fewest rides for good, for all the searches to come.
            if let Some(&latest) = s.w.end_best.iter().find(|e| **e != NEVER) {
                s.aim(last, (latest - start).max(0) as u32);
            }
            // then one search for each moment inside the window at which a trip leaves a first stop, the latest first
            let mut events = s.departures(first, start, window);
            let cap = q.limits.departures.clamp(1, 100_000) as usize;
            events.sort_unstable();
            let mut distinct = 0usize;
            let mut keep = events.len();
            for i in 0..events.len() {
                if i == 0 || events[i].0 != events[i - 1].0 {
                    distinct += 1;
                    if distinct > cap {
                        keep = i;
                        s.cut = true;
                        break;
                    }
                }
            }
            events.truncate(keep);
            let mut i = events.len();
            while i > 0 && s.steps <= s.max_steps && s.w.labels.len() < s.max_labels {
                let moment = events[i - 1].0;
                s.begin();
                while i > 0 && events[i - 1].0 == moment {
                    let (stop, secs) = first[events[i - 1].1 as usize];
                    s.offer_first(stop, moment + secs as i32, secs);
                    i -= 1;
                }
                s.rounds();
                searches += 1;
            }
            if i > 0 {
                s.cut = true;
            }
        }
        let followed = (s.limit - start - window).clamp(0, 2 * DAY) as u32;
        let mut journeys: Vec<Journey> = Vec::new();
        for f in s.w.found.iter() {
            if let Some(j) = s.journey(f) {
                journeys.push(j);
            }
        }
        // on foot all the way, between two points near enough
        if let (Place::Point { lat: a, lon: b }, Place::Point { lat: c, lon: d }) = (&q.from, &q.to) {
            let mm = distance_mm(*a, *b, *c, *d);
            if mm <= 2 * q.reach_m.min(50_000) as u64 * 1000 {
                let secs = (mm * 130 / 100).div_ceil(q.walk_mm_s.clamp(100, 100_000) as u64) as i32;
                let depart = if back { time - secs } else { time };
                let leg = Leg::Walk { from: Spot::Origin, to: Spot::Destination, depart, arrive: depart + secs };
                journeys.push(Journey { depart, arrive: depart + secs, rides: 0, penalty: 0, walking: secs as u32, legs: vec![leg] });
            }
        }
        // What is asked for one moment is judged by arrival (or, arriving by, by departure), rides and cost; over a
        // window the other end of the journey counts too, and only what lies inside the window is an option.
        let key = |j: &Journey| {
            let (leave, reach) = if back { (-j.arrive, -j.depart) } else { (j.depart, j.arrive) };
            (if window == 0 { 0 } else { -leave }, reach, j.rides, j.penalty, -leave)
        };
        let mut keep: Vec<Journey> = Vec::new();
        for (i, j) in journeys.iter().enumerate() {
            let a = key(j);
            let inside = window == 0 || if back { j.arrive >= time - window } else { j.depart <= time + window };
            let beaten = journeys.iter().enumerate().any(|(k, o)| {
                let b = key(o);
                let same = (b.0, b.1, b.2, b.3) == (a.0, a.1, a.2, a.3);
                (b.0 <= a.0 && b.1 <= a.1 && b.2 <= a.2 && b.3 <= a.3 && !same) || (same && (b.4 < a.4 || (b.4 == a.4 && k < i)))
            });
            if inside && !beaten {
                keep.push(j.clone());
            }
        }
        keep.sort_by_key(|j| (j.depart, j.arrive, j.rides, j.penalty));
        let options = q.limits.options.clamp(1, 1024) as usize;
        let mut cut = s.cut;
        if keep.len() > options {
            // too many: those nearest the asked moment stay
            if back {
                keep.drain(..keep.len() - options);
            } else {
                keep.truncate(options);
            }
            cut = true;
        }
        Answer { journeys: keep, cut_short: cut, horizon: followed, searches, labels: s.w.labels.len() as u32, steps: s.steps }
    }
}

struct Search<'s, 'a> {
    tt: Timetable<'a>,
    ix: &'s Index,
    w: &'s mut Work,
    prefs: &'s Prefs,
    soft: bool,
    back: bool,
    /// The query's service day, and the days around it whose trips are looked at, with each one's list.
    day: i32,
    days: &'s [DayTrips],
    day_lists: [usize; KEPT_DAYS],
    off_lo: i32,
    off_hi: i32,
    /// Nothing later than this is followed.
    limit: i32,
    change: i32,
    rules: bool,
    max_rides: usize,
    bag: usize,
    max_labels: usize,
    max_steps: u64,
    steps: u64,
    cut: bool,
    stops: usize,
    /// Whether the least seconds to the end are worked out (`Search::aim`), or being worked out as the search asks
    /// (`Search::aim_lazily`).
    aimed: bool,
    lazy: bool,
}

impl<'s> Search<'s, '_> {
    /// Wipes what the search before wrote, and sets where this one ends.
    fn reset(&mut self, last: &[(u32, u32)]) {
        let rounds = self.max_rides + 1;
        let w = &mut *self.w;
        let size = self.stops * rounds;
        if w.rounds != rounds || w.cells.len() != size {
            w.rounds = rounds;
            w.cells = vec![EMPTY; size];
            w.is_touched = vec![false; self.stops];
            w.last = vec![NONE; self.stops];
            w.least = vec![u32::MAX; self.stops];
            w.fewest = vec![255; self.stops];
            w.aimed.clear();
            w.held_rounds = vec![0; self.stops];
            w.mark_stamp = vec![0; self.stops];
            w.route_from = vec![0; self.tt.routes() as usize];
            w.route_stamp = vec![0; self.tt.routes() as usize];
            w.stamp = 0;
            w.search = 0;
            w.touched.clear();
            w.last_set.clear();
        }
        for &s in w.touched.iter() {
            let at = s as usize * rounds;
            w.cells[at..at + rounds].fill(EMPTY);
            w.held_rounds[s as usize] = 0;
            w.is_touched[s as usize] = false;
        }
        w.touched.clear();
        for &s in w.last_set.iter() {
            w.last[s as usize] = NONE;
        }
        w.last_set.clear();
        if w.stamp > u32::MAX / 2 || w.search > u32::MAX / 64 {
            w.mark_stamp.fill(0);
            w.route_stamp.fill(0);
            w.stamp = 0;
            w.search = 0;
        }
        w.labels.clear();
        for &s in w.aimed.iter() {
            w.least[s as usize] = u32::MAX;
            w.fewest[s as usize] = 255;
        }
        w.aimed.clear();
        w.heap.clear();
        w.found.clear();
        w.front.clear();
        w.end_best = [NEVER; MAX_RIDES as usize + 1];
        w.marks.resize_with(rounds, Vec::new);
        w.held.resize_with(rounds, Vec::new);
        for h in w.held.iter_mut() {
            h.clear();
        }
        w.special_seen.resize_with(rounds, Vec::new);
        for h in w.special_seen.iter_mut() {
            h.clear();
        }
        for &(s, secs) in last {
            if (s as usize) < self.stops && !bit(&self.prefs.closed, s) {
                w.last[s as usize] = secs;
                w.last_set.push(s);
            }
        }
    }

    /// Starts one search: nothing is marked yet.
    fn begin(&mut self) {
        self.w.search += 1;
        self.w.held_from.clear();
        self.w.held_from.extend(self.w.held.iter().map(|h| h.len()));
        for m in self.w.marks.iter_mut() {
            m.clear();
        }
    }

    #[inline]
    fn board_time(&self, times: (u32, u32), pos: u32, off: i32) -> i32 {
        let (arrive, depart) = self.tt.time(times, pos);
        if self.back { -(off * DAY + arrive as i32) } else { off * DAY + depart as i32 }
    }

    #[inline]
    fn alight_time(&self, times: (u32, u32), pos: u32, off: i32) -> i32 {
        let (arrive, depart) = self.tt.time(times, pos);
        if self.back { -(off * DAY + depart as i32) } else { off * DAY + arrive as i32 }
    }

    #[inline]
    fn can_board(&self, ps: PatternStop) -> bool {
        (if self.back { ps.drop_off } else { ps.pickup }) != 1
    }

    #[inline]
    fn can_alight(&self, ps: PatternStop) -> bool {
        (if self.back { ps.pickup } else { ps.drop_off }) != 1
    }

    /// A route's trips that run on a day offset, in the route's order.
    #[inline]
    fn run(&self, r: u32, off: i32) -> Run<'s> {
        let days: &'s [DayTrips] = self.days;
        let Some(list) = self.day_lists.get((off - self.off_lo) as usize).and_then(|at| days.get(*at)) else { return NO_RUN };
        let (Some(&lo), Some(&hi), Some(&span)) = (list.first.get(r as usize), list.first.get(r as usize + 1), list.span.get(r as usize)) else { return NO_RUN };
        match (list.trips.get(lo as usize..hi as usize), list.times.get(lo as usize..hi as usize)) {
            (Some(trips), Some(times)) => Run { trips, times, span },
            _ => NO_RUN,
        }
    }

    /// Whether any trip of a day's run can be boarded at `time` or later: by the run's first and last moments alone.
    #[inline]
    fn in_reach(&self, run: &Run, off: i32, time: i32) -> bool {
        if run.trips.is_empty() {
            return false;
        }
        if self.back { -(off * DAY + run.span.0 as i32) >= time } else { off * DAY + run.span.1 as i32 >= time }
    }

    /// The trip of a rank in a day's run of a route, with its times: the ranks rise with the time of boarding.
    #[inline]
    fn trip_at(&self, run: &Run, rank: u32) -> (u32, (u32, u32)) {
        let at = if self.back { run.trips.len() - 1 - rank as usize } else { rank as usize };
        (run.trips[at], run.times[at])
    }

    /// No trip of this day offset boards before this.
    #[inline]
    fn earliest_of(&self, off: i32) -> i32 {
        if self.back { -(off * DAY + self.ix.max_time) } else { off * DAY }
    }

    fn touch(&mut self, stop: u32) {
        if !self.w.is_touched[stop as usize] {
            self.w.is_touched[stop as usize] = true;
            self.w.touched.push(stop);
        }
    }

    /// Whether a journey already found makes anything at this time and cost, after `rides` rides, pointless.
    fn beaten_at_end(&self, rides: usize, time: i32, cost: u32) -> bool {
        if time < self.w.end_best[rides.min(MAX_RIDES as usize)] {
            return false;
        }
        !self.soft || self.w.front.iter().any(|f| f.time <= time && f.cost <= cost && f.rides as usize <= rides)
    }

    /// Whether nothing can come of being at `stop` at `time` after `rides` rides: it is past the limit, or a journey
    /// already found ends no later with no more rides and at no more cost. Once the search is aimed, "ends" and
    /// "rides" are the least the stop still needs on top.
    fn pointless(&mut self, rides: usize, stop: u32, time: i32, cost: u32) -> bool {
        if !self.aimed {
            return time > self.limit || self.beaten_at_end(rides, time, cost);
        }
        if time > self.limit {
            return true;
        }
        let left = (self.limit as i64 - time as i64).min(MAX_TIME as i64 * 8) as u32;
        let least = if self.lazy { self.least_within(stop, left) } else { self.w.least[stop as usize].min(self.w.reach.saturating_add(1)) };
        if least > left {
            return true;
        }
        let more = match self.w.fewest[stop as usize] {
            255 => 0,
            n => n as usize,
        };
        rides + more > self.max_rides || self.beaten_at_end(rides + more, time + least as i32, cost)
    }

    /// Starts the count of least seconds to the journey's end at the end's stops.
    fn seed(&mut self, last: &[(u32, u32)]) {
        for &s in self.w.aimed.iter() {
            self.w.least[s as usize] = u32::MAX;
            self.w.fewest[s as usize] = 255;
        }
        self.w.aimed.clear();
        self.w.heap.clear();
        for &(s, secs) in last {
            if (s as usize) < self.stops && !bit(&self.prefs.closed, s) && secs < self.w.least[s as usize] {
                if self.w.least[s as usize] == u32::MAX {
                    self.w.aimed.push(s);
                }
                self.w.least[s as usize] = secs;
                self.w.heap.push(Reverse((secs, s)));
            }
        }
    }

    /// One step of Dijkstra's method from the journey's end: `v` is `d` seconds from it for good, and the stops
    /// one hop or one walk before it (in the direction searched) are at most that much further, up to `reach`.
    fn settle(&mut self, d: u32, v: u32, reach: u32) {
        let w = &mut *self.w;
        let mut relax = |u: u32, nd: u32| {
            if nd <= reach && nd < w.least[u as usize] {
                if w.least[u as usize] == u32::MAX {
                    w.aimed.push(u);
                }
                w.least[u as usize] = nd;
                w.heap.push(Reverse((nd, u)));
            }
        };
        // the stop before this one on each route through it: over the pattern's fastest hop
        let (lo, hi) = self.tt.routes_at(v);
        self.steps += (hi - lo) as u64 + 1;
        for e in lo..hi {
            let (r, pos) = self.tt.route_at(e);
            let route = self.tt.route(r);
            let (at, hop) = if self.back { (pos + 1, pos) } else { (pos.wrapping_sub(1), pos.wrapping_sub(1)) };
            if at >= route.stops {
                continue;
            }
            let took = self.ix.hop.get((route.first_stop + hop) as usize).copied().unwrap_or(0);
            if took != u32::MAX {
                relax(self.tt.pattern_stop(route.first_stop + at).stop, d.saturating_add(took));
            }
        }
        // and the stops a walk leads here from
        if self.back {
            let (lo, hi) = self.tt.walks_from(v);
            self.steps += (hi - lo) as u64;
            for i in lo..hi {
                let (u, secs) = self.tt.walk(i);
                relax(u, d.saturating_add(secs));
            }
        } else {
            let (lo, hi) = (self.ix.back_first[v as usize] as usize, self.ix.back_first[v as usize + 1] as usize);
            self.steps += (hi - lo) as u64;
            for &(u, secs) in &self.ix.back[lo..hi] {
                relax(u, d.saturating_add(secs));
            }
        }
    }

    /// Aims the search as it goes: the least seconds to the journey's end are worked out only as far from the end
    /// as the search asks ([`Search::least_within`]). To begin with, as far as the journey's first stops: the least
    /// time of the whole journey is returned, or `None` when no way leads from any first stop to the end at all.
    fn aim_lazily(&mut self, last: &[(u32, u32)], first: &[(u32, u32)]) -> Option<u32> {
        self.seed(last);
        (self.lazy, self.aimed) = (true, true);
        let mut whole = u32::MAX;
        while let Some(&Reverse((d, v))) = self.w.heap.peek() {
            if d > whole {
                break;
            }
            self.w.heap.pop();
            if d > self.w.least[v as usize] {
                continue;
            }
            // `first` is sorted by stop
            if let Ok(at) = first.binary_search_by_key(&v, |e| e.0) {
                whole = whole.min(d.saturating_add(first[at].1));
            }
            self.settle(d, v, u32::MAX);
        }
        (whole != u32::MAX).then_some(whole)
    }

    /// The least seconds from a stop to the journey's end if that is `within` or less, else something more than
    /// `within`: the count from the end goes on just until this stop's is certain, or past `within`.
    fn least_within(&mut self, stop: u32, within: u32) -> u32 {
        loop {
            let known = self.w.least[stop as usize];
            // what is no further than the nearest stop still open is certain
            let Some(&Reverse((d, v))) = self.w.heap.peek() else { return known };
            if known <= d {
                return known;
            }
            if d > within {
                return within.saturating_add(1);
            }
            self.w.heap.pop();
            if d <= self.w.least[v as usize] {
                self.settle(d, v, u32::MAX);
            }
        }
    }

    /// Aims the search for good: for every stop within `reach` seconds of the journey's end, the least seconds
    /// from there to the end (the shortest way over each pattern's fastest hops and the walks, by Dijkstra's method
    /// from the end's stops) and the fewest rides (counted back level by level, a walk costing none).
    fn aim(&mut self, last: &[(u32, u32)], reach: u32) {
        let reach = reach.min(MAX_TIME * 4);
        self.seed(last);
        self.lazy = false;
        while let Some(Reverse((d, v))) = self.w.heap.pop() {
            if d > reach {
                break;
            }
            if d <= self.w.least[v as usize] {
                self.settle(d, v, reach);
            }
        }
        self.w.heap.clear();
        let mut queue: VecDeque<u32> = VecDeque::new();
        self.w.reach = reach;
        // the fewest rides: from the end's stops outward, a ride one level on, a walk none
        if self.w.along.len() != self.tt.patterns() as usize {
            self.w.along = vec![NONE; self.tt.patterns() as usize];
        }
        let mut patterns: Vec<u32> = Vec::new();
        for &(s, _) in last {
            if (s as usize) < self.stops && self.w.least[s as usize] <= reach && self.w.fewest[s as usize] == 255 {
                self.w.fewest[s as usize] = 0;
                queue.push_back(s);
            }
        }
        while let Some(v) = queue.pop_front() {
            let level = self.w.fewest[v as usize];
            // a walk into v costs no ride: those stops are of v's level, and go first
            let walks: &[(u32, u32)] = if self.back { &[] } else { &self.ix.back[self.ix.back_first[v as usize] as usize..self.ix.back_first[v as usize + 1] as usize] };
            let (wlo, whi) = if self.back { self.tt.walks_from(v) } else { (0, walks.len() as u32) };
            self.steps += (whi - wlo) as u64 + 1;
            for i in wlo..whi {
                let u = if self.back { self.tt.walk(i).0 } else { walks[i as usize].0 };
                if self.w.least[u as usize] <= reach && self.w.fewest[u as usize] > level {
                    self.w.fewest[u as usize] = level;
                    queue.push_front(u);
                }
            }
            if level as usize >= self.max_rides {
                continue;
            }
            // every stop before v on a route through it is one ride from v; a pattern is walked once, as far as
            // it was not walked from a stop of an earlier or equal level
            let (lo, hi) = self.tt.routes_at(v);
            for e in lo..hi {
                let (r, pos) = self.tt.route_at(e);
                let route = self.tt.route(r);
                let p = route.pattern as usize;
                let Some(&done) = self.w.along.get(p) else { continue };
                // forward: positions below pos, down to where an earlier walk of the pattern began; back: above pos
                let (from, to) = if self.back {
                    (pos + 1, if done == NONE { route.stops } else { done.min(route.stops) })
                } else {
                    (if done == NONE { 0 } else { done.saturating_add(1) }, pos)
                };
                if from >= to {
                    continue;
                }
                if done == NONE {
                    patterns.push(p as u32);
                }
                self.w.along[p] = if self.back { pos + 1 } else { pos.saturating_sub(1) };
                self.steps += (to - from) as u64;
                for at in from..to {
                    let u = self.tt.pattern_stop(route.first_stop + at).stop;
                    if self.w.least[u as usize] <= reach && self.w.fewest[u as usize] > level + 1 {
                        self.w.fewest[u as usize] = level + 1;
                        queue.push_back(u);
                    }
                }
            }
        }
        for p in patterns {
            self.w.along[p as usize] = NONE;
        }
        self.aimed = true;
    }

    /// Whether a label of this stop with no more rides is no later and no dearer.
    fn beaten(&self, ready: bool, rounds: usize, stop: u32, time: i32, cost: u32) -> bool {
        let at = stop as usize * self.w.rounds;
        if time < self.w.cells[at + rounds].best[ready as usize] {
            return false;
        }
        if !self.soft {
            return true;
        }
        for k in 0..=rounds {
            let mut l = self.w.cells[at + k].head[ready as usize];
            while l != NONE {
                let lab = &self.w.labels[l as usize];
                if lab.time <= time && lab.cost <= cost {
                    return true;
                }
                l = lab.next;
            }
        }
        false
    }

    /// Puts a label into its stop's bag for a round, dropping what it beats there and, past the bag's size, the worst.
    fn put(&mut self, ready: bool, round: usize, label: Label) -> u32 {
        let stop = label.stop;
        self.touch(stop);
        let at = stop as usize * self.w.rounds;
        let w = &mut *self.w;
        let index = w.labels.len() as u32;
        w.labels.push(label);
        let which = ready as usize;
        // the bag anew: the label first, then the ones it does not beat
        let mut old = w.cells[at + round].head[which];
        w.cells[at + round].head[which] = index;
        let mut tail = index;
        let mut count = 1;
        while old != NONE {
            let next = w.labels[old as usize].next;
            let o = w.labels[old as usize];
            if label.time <= o.time && label.cost <= o.cost {
                w.labels[old as usize].kind |= DEAD;
            } else {
                w.labels[tail as usize].next = old;
                tail = old;
                count += 1;
            }
            old = next;
        }
        w.labels[tail as usize].next = NONE;
        if count > self.bag {
            // the one whose time and cost together are worst goes; of equals, the older
            let (mut worst, mut worst_sum, mut before_worst) = (NONE, i64::MIN, NONE);
            let (mut l, mut before) = (w.cells[at + round].head[which], NONE);
            while l != NONE {
                let lab = &w.labels[l as usize];
                let sum = lab.time as i64 + lab.cost as i64;
                if sum >= worst_sum {
                    (worst, worst_sum, before_worst) = (l, sum, before);
                }
                before = l;
                l = lab.next;
            }
            let after = w.labels[worst as usize].next;
            if before_worst == NONE {
                w.cells[at + round].head[which] = after;
            } else {
                w.labels[before_worst as usize].next = after;
            }
            w.labels[worst as usize].kind |= DEAD;
        }
        for k in round..w.rounds {
            if label.time >= w.cells[at + k].best[which] {
                break;
            }
            w.cells[at + k].best[which] = label.time;
        }
        index
    }

    /// A first stop of the journey, reached on foot at `time`.
    fn offer_first(&mut self, stop: u32, time: i32, secs: u32) {
        if stop as usize >= self.stops || bit(&self.prefs.closed, stop) || self.pointless(0, stop, time, 0) || self.beaten(true, 0, stop, time, 0) || self.w.labels.len() >= self.max_labels {
            return;
        }
        self.put(true, 0, Label { time, cost: 0, prev: NONE, next: NONE, stop, trip: secs, from: 0, to: 0, off: 0, kind: ACCESS });
        self.mark(0, stop);
    }

    fn mark(&mut self, round: usize, stop: u32) {
        // one stamp a search and round, so a stop is marked once in each
        let stamp = self.w.search * 32 + round as u32;
        if self.w.mark_stamp[stop as usize] != stamp {
            self.w.mark_stamp[stop as usize] = stamp;
            self.w.marks[round].push(stop);
        }
    }

    /// The moments, inside the window, at which a trip can be boarded at a first stop: each less the seconds on
    /// foot to that stop, with the stop's place in `first`.
    fn departures(&mut self, first: &[(u32, u32)], start: i32, window: i32) -> Vec<(i32, u32)> {
        let mut out = Vec::new();
        for (at, &(stop, secs)) in first.iter().enumerate() {
            if bit(&self.prefs.closed, stop) {
                continue;
            }
            let (lo, hi) = self.tt.routes_at(stop);
            for e in lo..hi {
                let (r, pos) = self.tt.route_at(e);
                let route = self.tt.route(r);
                let i = if self.back { route.stops - 1 - pos } else { pos };
                if i + 1 >= route.stops || !self.can_board(self.tt.pattern_stop(route.first_stop + pos)) || bit(&self.prefs.line_closed, route.line) {
                    continue;
                }
                let from = start + secs as i32;
                for off in self.off_lo..=self.off_hi {
                    let run = self.run(r, off);
                    if !self.in_reach(&run, off, from) {
                        continue;
                    }
                    let mut rank = self.first_rank(&run, pos, off, from);
                    while (rank as usize) < run.trips.len() {
                        self.steps += 1;
                        let (trip, times) = self.trip_at(&run, rank);
                        let t = self.board_time(times, pos, off);
                        if t > from + window || self.steps > self.max_steps {
                            break;
                        }
                        if !bit(&self.prefs.trip_closed, trip) {
                            out.push((t - secs as i32, at as u32));
                        }
                        rank += 1;
                    }
                }
            }
        }
        out
    }

    /// The first rank in a day's run of a route whose trip boards at a place at `time` or later; the number of trips
    /// when none does.
    fn first_rank(&self, run: &Run, pos: u32, off: i32, time: i32) -> u32 {
        let (mut lo, mut hi) = (0u32, run.trips.len() as u32);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if self.board_time(self.trip_at(run, mid).1, pos, off) < time { lo = mid + 1 } else { hi = mid }
        }
        lo
    }

    /// The rounds of one search, from the stops marked at its start.
    fn rounds(&mut self) {
        for round in 1..=self.max_rides {
            if self.w.marks[round - 1].is_empty() || self.cut {
                break;
            }
            // the routes through a marked stop, each from the first such stop along it
            self.w.stamp += 1;
            let phase = self.w.stamp;
            self.w.route_list.clear();
            let marks = core::mem::take(&mut self.w.marks[round - 1]);
            for &stop in marks.iter() {
                let (lo, hi) = self.tt.routes_at(stop);
                for e in lo..hi {
                    self.steps += 1;
                    let (r, pos) = self.tt.route_at(e);
                    let i = if self.back { self.tt.route(r).stops.saturating_sub(pos + 1) } else { pos };
                    let (r, w) = (r as usize, &mut *self.w);
                    if w.route_stamp[r] != phase {
                        w.route_stamp[r] = phase;
                        w.route_from[r] = i;
                        w.route_list.push(r as u32);
                    } else if i < w.route_from[r] {
                        w.route_from[r] = i;
                    }
                }
            }
            self.w.marks[round - 1] = marks;
            self.w.fresh.clear();
            self.w.special.clear();
            let list = core::mem::take(&mut self.w.route_list);
            for &r in list.iter() {
                self.ride(r, self.w.route_from[r as usize], round);
                if self.steps > self.max_steps {
                    self.cut = true;
                    break;
                }
            }
            self.w.route_list = list;
            // from where a ride ended: wait to change there, or walk to a stop near
            let fresh = core::mem::take(&mut self.w.fresh);
            for &l in fresh.iter() {
                let lab = self.w.labels[l as usize];
                if lab.kind & DEAD != 0 {
                    continue;
                }
                self.offer_ready(round, lab.stop, lab.time + self.change, lab.cost, l);
                if self.back {
                    let (lo, hi) = (self.ix.back_first[lab.stop as usize] as usize, self.ix.back_first[lab.stop as usize + 1] as usize);
                    for i in lo..hi {
                        let (to, secs) = self.ix.back[i];
                        self.offer_ready(round, to, lab.time + secs as i32, lab.cost, l);
                    }
                    self.steps += (hi - lo) as u64;
                } else {
                    let (lo, hi) = self.tt.walks_from(lab.stop);
                    for i in lo..hi {
                        let (to, secs) = self.tt.walk(i);
                        self.offer_ready(round, to, lab.time + secs as i32, lab.cost, l);
                    }
                    self.steps += (hi - lo) as u64;
                }
            }
            self.w.fresh = fresh;
            // and from where a ride ended by a trip the feed has rules for: the same, held apart
            let special = core::mem::take(&mut self.w.special);
            for &l in special.iter() {
                let lab = self.w.labels[l as usize];
                self.hold(round, lab.stop, lab.time + self.change, lab.cost, l, NONE);
                let (lo, hi) = if self.back { (self.ix.back_first[lab.stop as usize], self.ix.back_first[lab.stop as usize + 1]) } else { self.tt.walks_from(lab.stop) };
                for i in lo..hi {
                    let (to, secs) = if self.back { self.ix.back[i as usize] } else { self.tt.walk(i) };
                    self.hold(round, to, lab.time + secs as i32, lab.cost, l, NONE);
                }
                // what waits for this trip is boarded whatever the walk takes
                let line = self.tt.route(self.tt.route_of_trip(lab.trip)).line;
                let (lo, hi) = self.rules_from(lab.stop);
                for i in lo..hi {
                    let (rule, r) = self.rule_at(i);
                    let (from, to) = self.sides(&r);
                    if matches!(r.kind, 1 | 4) && from.0 == lab.stop && fits(from.1, lab.trip) && fits(from.2, line) && (from.1 != NONE || from.2 != NONE || to.1 != NONE || to.2 != NONE) {
                        self.hold(round, to.0, lab.time, lab.cost, l, rule);
                    }
                }
                self.steps += (hi - lo) as u64 + 1;
            }
            self.w.special = special;
            self.w.special.clear();
        }
    }

    /// One is at `stop` at `time`, off the ride of label `prev`, and may board there.
    fn offer_ready(&mut self, round: usize, stop: u32, time: i32, cost: u32, prev: u32) {
        if bit(&self.prefs.closed, stop) || self.pointless(round, stop, time, cost) || self.beaten(true, round, stop, time, cost) {
            return;
        }
        if self.w.labels.len() >= self.max_labels {
            self.cut = true;
            return;
        }
        self.put(true, round, Label { time, cost, prev, next: NONE, stop, trip: NONE, from: 0, to: 0, off: 0, kind: CHANGE });
        self.mark(round, stop);
    }

    /// One is at `stop` at `time` off the ride of label `prev`, by a trip the feed has rules for: held apart from the
    /// stop's bag. With `rule`, it is the one thing that rule says waits.
    fn hold(&mut self, round: usize, stop: u32, time: i32, cost: u32, prev: u32, rule: u32) {
        if bit(&self.prefs.closed, stop) || self.pointless(round, stop, time, cost) {
            return;
        }
        // under rules that only bar, one who is there no later and no dearer without rules can do all this one can
        if rule == NONE && self.beaten(true, round, stop, time, cost) {
            return;
        }
        if self.w.labels.len() >= self.max_labels || self.w.held[round].len() >= 65_536 {
            self.cut = true;
            return;
        }
        self.touch(stop);
        let label = self.w.labels.len() as u32;
        self.w.labels.push(Label { time, cost, prev, next: NONE, stop, trip: NONE, from: 0, to: 0, off: 0, kind: CHANGE });
        self.w.held[round].push(Held { label, rule });
        self.w.held_rounds[stop as usize] |= 1 << round;
        self.mark(round, stop);
    }

    /// The rules for changing away from a stop, in the direction searched: a run for [`Search::rule_at`].
    fn rules_from(&self, stop: u32) -> (u32, u32) {
        let has = |set: &Vec<u64>| set.get((stop >> 6) as usize).is_some_and(|w| w >> (stop & 63) & 1 == 1);
        if !self.rules || !has(if self.back { &self.ix.rule_to } else { &self.ix.rule_from }) {
            return (0, 0);
        }
        let n = if self.back { self.ix.rules_by_to.len() as u32 } else { self.tt.rules() };
        let key = |i: u32| if self.back { self.tt.rule(self.ix.rules_by_to[i as usize]).to_stop } else { self.tt.rule(i).from_stop };
        let bound = |above: bool| {
            let (mut lo, mut hi) = (0u32, n);
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                if key(mid) < stop || (above && key(mid) == stop) { lo = mid + 1 } else { hi = mid }
            }
            lo
        };
        let lo = bound(false);
        (lo, bound(true).min(lo + 65_536))
    }

    /// A rule of a run, with its number in the file.
    fn rule_at(&self, i: u32) -> (u32, Rule) {
        let rule = if self.back { self.ix.rules_by_to.get(i as usize).copied().unwrap_or(NONE) } else { i };
        (rule, self.tt.rule(rule))
    }

    /// A rule's two sides as (stop, trip, line), in the direction searched: the ride one comes off, the one boarded.
    fn sides(&self, r: &Rule) -> ((u32, u32, u32), (u32, u32, u32)) {
        let (a, b) = ((r.from_stop, r.from_trip, r.from_line), (r.to_stop, r.to_trip, r.to_line));
        if self.back { (b, a) } else { (a, b) }
    }

    /// Whether the feed has a rule that binds for changing from this trip at this stop.
    fn has_rules(&self, trip: u32, stop: u32) -> bool {
        let b = &self.ix.bound[self.back as usize];
        if b.trips.binary_search(&(stop, trip)).is_ok() || b.stops.binary_search(&stop).is_ok() {
            return true;
        }
        let at = b.lines.partition_point(|e| e.0 < stop);
        if b.lines.get(at).is_none_or(|e| e.0 != stop) {
            return false;
        }
        b.lines.binary_search(&(stop, self.tt.route(self.tt.route_of_trip(trip)).line)).is_ok()
    }

    /// Walks a route from its stop `from` on: those riding alight where it helps, those ready at a stop board the
    /// first trip they can catch.
    fn ride(&mut self, r: u32, from: u32, round: usize) {
        let route = self.tt.route(r);
        if bit(&self.prefs.line_closed, route.line) || route.trips == 0 {
            return;
        }
        let line_cost = cost(&self.prefs.line_cost, route.line);
        let mut riding = core::mem::take(&mut self.w.riding);
        riding.clear();
        for i in from..route.stops {
            self.steps += 1 + riding.len() as u64;
            let pos = if self.back { route.stops - 1 - i } else { i };
            let ps = self.tt.pattern_stop(route.first_stop + pos);
            let stop = ps.stop;
            // Only at a stop marked in the round before does anyone board: a label there from an earlier search
            // has boarded all it can, in the search that made it. With nobody riding, there is nothing to do.
            let marked = self.w.mark_stamp[stop as usize] == self.w.search * 32 + round as u32 - 1;
            if riding.is_empty() && !marked {
                continue;
            }
            let closed = bit(&self.prefs.closed, stop);
            let here = cost(&self.prefs.pass_cost, stop);
            if !riding.is_empty() && !closed && self.can_alight(ps) {
                let at_stop = cost(&self.prefs.stop_cost, stop);
                for e in riding.iter() {
                    let time = self.alight_time(e.times, pos, e.off);
                    let cost = e.cost.saturating_add(here).saturating_add(at_stop).min(64 * MAX_PENALTY);
                    self.offer_arrived(round, stop, time, cost, e, pos);
                }
            }
            if bit(&self.prefs.closed_through, stop) {
                riding.clear();
            } else if here != 0 {
                for e in riding.iter_mut() {
                    e.cost = e.cost.saturating_add(here);
                }
            }
            if marked && i + 1 < route.stops && !closed && self.can_board(ps) {
                let mut l = self.w.cells[stop as usize * self.w.rounds + round - 1].head[1];
                while l != NONE {
                    let lab = self.w.labels[l as usize];
                    self.board(&mut riding, r, &route, pos, l, &lab, line_cost.saturating_add(here), false);
                    l = lab.next;
                }
                if self.w.held_rounds[stop as usize] >> (round - 1) & 1 == 1 {
                    let held = core::mem::take(&mut self.w.held[round - 1]);
                    let from = self.w.held_from.get(round - 1).copied().unwrap_or(0).min(held.len());
                    self.steps += (held.len() - from) as u64;
                    for h in held[from..].iter() {
                        let lab = self.w.labels[h.label as usize];
                        if lab.stop != stop {
                            continue;
                        }
                        if h.rule == NONE {
                            self.board(&mut riding, r, &route, pos, h.label, &lab, line_cost.saturating_add(here), true);
                        } else {
                            self.board_waiting(&mut riding, r, &route, pos, h, &lab, line_cost.saturating_add(here));
                        }
                    }
                    self.w.held[round - 1] = held;
                }
            }
            if self.steps > self.max_steps || self.cut {
                self.cut = true;
                break;
            }
        }
        self.w.riding = riding;
    }

    /// A ride ends at `stop` at `time`.
    fn offer_arrived(&mut self, round: usize, stop: u32, time: i32, cost: u32, e: &Riding, pos: u32) {
        if self.pointless(round, stop, time, cost) {
            return;
        }
        // by a trip the feed has rules for here, the arrival is unlike any other: it is held apart
        let any = if self.back { &self.ix.rule_to } else { &self.ix.rule_from };
        let special = self.rules && any.get((stop >> 6) as usize).is_some_and(|w| w >> (stop & 63) & 1 == 1) && self.has_rules(e.trip, stop);
        if !special && self.beaten(false, round, stop, time, cost) {
            return;
        }
        if self.w.labels.len() >= self.max_labels {
            self.cut = true;
            return;
        }
        let (from, to) = if self.back { (pos, e.pos) } else { (e.pos, pos) };
        let label = Label { time, cost, prev: e.from, next: NONE, stop, trip: e.trip, from: from as u16, to: to as u16, off: e.off as i8, kind: RIDE };
        let index = if special {
            // the same ride at no less cost was held in an earlier search: nothing new
            let seen = &mut self.w.special_seen[round];
            self.steps += seen.len() as u64 / 8;
            if seen.iter().any(|o| (o.0, o.1, o.2) == (stop, e.trip, e.off) && o.3 <= cost) {
                return;
            }
            if seen.len() >= 65_536 {
                self.cut = true;
                return;
            }
            seen.push((stop, e.trip, e.off, cost));
            self.w.labels.push(label);
            self.w.special.push(self.w.labels.len() as u32 - 1);
            self.w.labels.len() as u32 - 1
        } else {
            let index = self.put(false, round, label);
            self.w.fresh.push(index);
            index
        };
        let secs = self.w.last[stop as usize];
        if secs != NONE {
            // (a journey ends with its last walk: one that carries it past the limit is not one that was asked for)
            let end = time + secs as i32;
            if end <= self.limit && !self.beaten_at_end(round, end, cost) {
                let f = Found { time: end, cost, rides: round as u32, label: index, secs };
                self.w.front.retain(|o| !(f.time <= o.time && f.cost <= o.cost && f.rides <= o.rides));
                self.w.front.push(f);
                for best in self.w.end_best[round.min(MAX_RIDES as usize)..].iter_mut() {
                    *best = (*best).min(end);
                }
                if self.w.found.len() < 4096 {
                    self.w.found.push(f);
                } else {
                    self.cut = true;
                }
            }
        }
    }

    /// The feed's rules for a change from the ride that ended label `lab`'s wait, to a trip boarded at `stop`.
    fn before(&self, lab: &Label, stop: u32) -> Option<Before> {
        if !self.rules || lab.kind != CHANGE {
            return None;
        }
        let ride = self.w.labels.get(lab.prev as usize)?;
        let has = |set: &Vec<u64>, s: u32| set.get((s >> 6) as usize).is_some_and(|w| w >> (s & 63) & 1 == 1);
        // in real order the change is from one stop to another; searching back, the ride before is the later one
        let (from, to) = if self.back { (stop, ride.stop) } else { (ride.stop, stop) };
        if !has(&self.ix.rule_from, from) || !has(&self.ix.rule_to, to) {
            return None;
        }
        let n = if self.back { self.ix.rules_by_to.len() as u32 } else { self.tt.rules() };
        let key = |i: u32| {
            if self.back {
                let r = self.tt.rule(self.ix.rules_by_to[i as usize]);
                (r.to_stop, r.from_stop)
            } else {
                let r = self.tt.rule(i);
                (r.from_stop, r.to_stop)
            }
        };
        let want = if self.back { (to, from) } else { (from, to) };
        let bound = |above: bool| {
            let (mut lo, mut hi) = (0u32, n);
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                if key(mid) < want || (above && key(mid) == want) { lo = mid + 1 } else { hi = mid }
            }
            lo
        };
        let (lo, hi) = (bound(false), bound(true));
        if lo == hi {
            return None;
        }
        let line = self.tt.route(self.tt.route_of_trip(ride.trip)).line;
        Some(Before { lo, hi: hi.min(lo + 4096), trip: ride.trip, line, time: ride.time })
    }

    /// Whether the rules bar boarding `trip` of `line` at `time` after the ride before: no change is possible, or
    /// it needs more time than there is.
    fn barred(&self, b: &Before, trip: u32, line: u32, time: i32) -> bool {
        for i in b.lo..b.hi {
            let r = self.tt.rule(if self.back { self.ix.rules_by_to[i as usize] } else { i });
            if r.from_trip == NONE && r.to_trip == NONE && r.from_line == NONE && r.to_line == NONE {
                continue;
            }
            let (from, to) = self.sides(&r);
            if !(fits(from.1, b.trip) && fits(from.2, b.line) && fits(to.1, trip) && fits(to.2, line)) {
                continue;
            }
            if r.kind == 3 || (r.kind == 2 && r.min_time != NONE && (time as i64) < b.time as i64 + r.min_time as i64) {
                return true;
            }
        }
        false
    }

    /// The one thing a rule says waits for the ride before a held label: its trip of this route, or the first trip
    /// of its line, leaving once the ride before is in.
    fn board_waiting(&mut self, riding: &mut Vec<Riding>, r: u32, route: &Route, pos: u32, h: &Held, lab: &Label, cost_here: u32) {
        let rule = self.tt.rule(h.rule);
        let (_, to) = self.sides(&rule);
        if !fits(to.2, route.line) || bit(&self.prefs.line_closed, route.line) {
            return;
        }
        let base = lab.cost.saturating_add(cost(&self.prefs.stop_cost, lab.stop)).saturating_add(cost_here);
        let mut best: Option<(i32, Riding)> = None;
        for off in self.off_lo..=self.off_hi {
            // the rule's own trip if it names one, else the line's first of the day that can be caught
            let run = self.run(r, off);
            let mut rank = if to.1 == NONE { self.first_rank(&run, pos, off, lab.time) } else { 0 };
            while to.1 != NONE || (rank as usize) < run.trips.len() {
                self.steps += 1;
                let trip = if to.1 == NONE { self.trip_at(&run, rank).0 } else { to.1 };
                rank += 1;
                if trip < route.first_trip || trip >= route.first_trip.saturating_add(route.trips) {
                    break;
                }
                let times = self.tt.trip_times(trip);
                let time = self.board_time(times, pos, off);
                if self.tt.runs(self.tt.trip(trip).service, self.day + off) && !bit(&self.prefs.trip_closed, trip) && time >= lab.time && time <= self.limit {
                    if best.is_none_or(|b| time < b.0) {
                        best = Some((time, Riding { trip, times, off, cost: base.saturating_add(cost(&self.prefs.trip_cost, trip)), from: h.label, pos }));
                    }
                    break;
                }
                if to.1 != NONE {
                    break;
                }
            }
        }
        if let Some((_, new)) = best {
            self.join(riding, new, pos);
        }
    }

    /// Those of label `lab`, ready at a stop, board the first trip of the route they can catch.
    #[allow(clippy::too_many_arguments)]
    fn board(&mut self, riding: &mut Vec<Riding>, r: u32, route: &Route, pos: u32, l: u32, lab: &Label, cost_here: u32, held: bool) {
        let stop = lab.stop;
        // only a held label comes off a trip the feed has rules for
        let base = lab.cost.saturating_add(cost(&self.prefs.stop_cost, stop)).saturating_add(cost_here);
        // Whoever rides a trip that leaves here no later than this label is ready, at no more cost, is ahead of
        // anything the label can catch: the usual case along a route, and no search for a trip is needed. (Not so
        // where trips differ in cost, or the label is under the feed's rules.)
        if !held && self.prefs.trip_cost.is_none() && riding.iter().any(|e| e.cost <= base && self.board_time(e.times, pos, e.off) <= lab.time) {
            return;
        }
        let before = if held { self.before(lab, stop) } else { None };
        let trip_costs = self.prefs.trip_cost.is_some();
        let mut best = NEVER;
        let step = if self.back { -1 } else { 1 };
        let mut off = if self.back { self.off_hi } else { self.off_lo };
        while off >= self.off_lo && off <= self.off_hi {
            if best <= self.earliest_of(off) {
                break;
            }
            // the first trip of this day that can be caught, and with costly trips a few later and cheaper ones
            let run = self.run(r, off);
            if !self.in_reach(&run, off, lab.time) {
                off += step;
                continue;
            }
            let mut rank = self.first_rank(&run, pos, off, lab.time);
            let (mut cheapest, mut looked) = (u32::MAX, 0);
            while (rank as usize) < run.trips.len() && looked < 32 {
                self.steps += 1;
                let (trip, times) = self.trip_at(&run, rank);
                rank += 1;
                if bit(&self.prefs.trip_closed, trip) {
                    continue;
                }
                let time = self.board_time(times, pos, off);
                if time > self.limit {
                    break;
                }
                if before.is_some_and(|b| self.barred(&b, trip, route.line, time)) {
                    continue;
                }
                let c = cost(&self.prefs.trip_cost, trip);
                if c < cheapest {
                    cheapest = c;
                    best = best.min(time);
                    self.join(riding, Riding { trip, times, off, cost: base.saturating_add(c), from: l, pos }, pos);
                }
                if !trip_costs || c == 0 {
                    break;
                }
                looked += 1;
            }
            off += step;
        }
    }

    /// Adds a trip to those ridden along the route, unless one ridden already is here no later and no dearer.
    fn join(&self, riding: &mut Vec<Riding>, new: Riding, pos: u32) {
        let time = self.board_time(new.times, pos, new.off);
        let mut i = 0;
        while i < riding.len() {
            let e = &riding[i];
            let t = self.board_time(e.times, pos, e.off);
            if t <= time && e.cost <= new.cost {
                return;
            }
            if time <= t && new.cost <= e.cost {
                riding.swap_remove(i);
            } else {
                i += 1;
            }
        }
        riding.push(new);
        if riding.len() > self.bag.max(2) {
            let worst = (0..riding.len()).max_by_key(|i| (self.board_time(riding[*i].times, pos, riding[*i].off) as i64 + riding[*i].cost as i64, *i));
            if let Some(worst) = worst {
                riding.swap_remove(worst);
            }
        }
    }

    /// A found journey as legs in real order and real time.
    fn journey(&self, f: &Found) -> Option<Journey> {
        // from the last ride back to the first stop, in the direction searched
        let mut rides: Vec<(Label, u32)> = Vec::new(); // a ride, and the seconds of the change after it
        let mut l = f.label;
        let mut after = 0u32;
        let first;
        loop {
            let lab = *self.w.labels.get(l as usize)?;
            match lab.kind & !DEAD {
                RIDE => rides.push((lab, after)),
                CHANGE => after = (lab.time - self.w.labels.get(lab.prev as usize)?.time).max(0) as u32,
                _ => {
                    first = lab;
                    break;
                }
            }
            if lab.prev == NONE || rides.len() > MAX_RIDES as usize + 1 {
                return None;
            }
            l = lab.prev;
        }
        // `rides` runs from the search's last ride to its first, each with the wait or walk that followed it in the
        // direction searched; searching back, that is real order already
        if !self.back {
            rides.reverse();
        }
        let mut legs: Vec<Leg> = Vec::new();
        let mut walking = 0u32;
        let (origin_secs, origin_stop, end_secs) = if self.back { (f.secs, None, first.trip) } else { (first.trip, Some(first.stop), f.secs) };
        let _ = origin_stop;
        let mut ridden: Vec<Leg> = Vec::new();
        for (lab, _) in rides.iter() {
            let r = self.tt.route_of_trip(lab.trip);
            let route = self.tt.route(r);
            let times = self.tt.trip_times(lab.trip);
            let off = lab.off as i32;
            ridden.push(Leg::Ride {
                trip: lab.trip,
                route: r,
                day: self.day + off,
                board: lab.from as u32,
                alight: lab.to as u32,
                from: self.tt.pattern_stop(route.first_stop + lab.from as u32).stop,
                to: self.tt.pattern_stop(route.first_stop + lab.to as u32).stop,
                depart: off * DAY + self.tt.time(times, lab.from as u32).1 as i32,
                arrive: off * DAY + self.tt.time(times, lab.to as u32).0 as i32,
            });
        }
        let (Some(Leg::Ride { from: first_stop, depart: first_depart, .. }), Some(Leg::Ride { to: last_stop, arrive: last_arrive, .. })) = (ridden.first().copied(), ridden.last().copied()) else {
            return None;
        };
        let depart = first_depart - origin_secs as i32;
        if origin_secs > 0 {
            legs.push(Leg::Walk { from: Spot::Origin, to: Spot::Stop(first_stop), depart, arrive: first_depart });
            walking += origin_secs;
        }
        for (i, leg) in ridden.iter().enumerate() {
            legs.push(*leg);
            if let (Leg::Ride { to, arrive, .. }, Some(Leg::Ride { from, depart: next, .. })) = (leg, ridden.get(i + 1)) {
                if to != from {
                    // the walk's seconds were kept with the ride it followed in the direction searched
                    // (none were kept where the next vehicle waits: that walk has all the time there is)
                    let secs = if self.back { rides[i + 1].1 } else { rides[i].1 };
                    let gap = (*next - *arrive).max(0) as u32;
                    let secs = if secs == 0 { gap } else { secs.min(gap) };
                    legs.push(Leg::Walk { from: Spot::Stop(*to), to: Spot::Stop(*from), depart: *arrive, arrive: *arrive + secs as i32 });
                    walking += secs;
                }
            }
        }
        let arrive = last_arrive + end_secs as i32;
        if end_secs > 0 {
            legs.push(Leg::Walk { from: Spot::Stop(last_stop), to: Spot::Destination, depart: last_arrive, arrive });
            walking += end_secs;
        }
        Some(Journey { depart, arrive, rides: ridden.len() as u32, penalty: f.cost, walking, legs })
    }
}
