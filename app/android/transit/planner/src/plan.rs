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
//! **A vehicle that waits is one vehicle.** For each arrival such a rule holds the first of what it speaks of (its
//! trip, or the trips of its line) to leave once the arrival is in, on any service day in reach: that one is
//! boarded whatever the walk or the change takes (`Search::first_leaving`), and every other trip is reached as
//! without the rule. The file alone says which it is: where a wish closes it, none waits. Searching back the same
//! is read from the vehicle: it waits for whoever came in since the one before it left (`Search::left_before`).
//! And since the vehicle depends on the arrival, a later trip of a route can do what an earlier one cannot. So
//! where a ride ends at a stop such rules lead from, the route's later trips that are in before the plain change
//! from this one could be made are held too, each for what waits for it alone (`Search::later_arrivals`).
//!
//! **The search is aimed.** From the journey's end, the least time to it that any timetable could give is counted
//! back stop by stop: the shortest way over each pattern's fastest hops and the walks, by Dijkstra's method. A
//! label that even so could not end in time is not made.
//! - *Patiently* ([`crate::Limits::patient`], the default): the least time of the whole journey says how long a
//!   journey may sensibly take (twice that and half an hour; an hour at least), and the first pass follows nothing
//!   past it. The count from the end goes only as far as the search asks. What could still be an option past it is
//!   then searched as far as the query's own horizon, so that a patient search gives what a whole one gives:
//!   - the moments for which nothing was found by then (all of them, or the last of a window: the last way of an
//!     evening may be an hour's wait and a night bus);
//!   - journeys with fewer rides than the first search with a journey found. That journey beats whatever leaves
//!     no later, ends later and has no fewer rides, so only a way of fewer rides is left to find: the direct bus
//!     that takes an hour longer. The stops such a way can lead over are counted from the journey's end ride by
//!     ride, and the pass touches no other.
//! - *Over a window*, the first search (from the window's far end) says how late any option may end. Every stop
//!   within that reach then gets its least time and its fewest rides for good, and the searches for the earlier
//!   departures make no label that could not end sooner than a journey leaving later already does.
//!
//! **A window holds its last moment, and its far end is a second after that.** The search from there finds what
//! leaves after the window: no option, but what an option must beat, the last moment's journeys like any other.
//! So what counts against a journey is every journey that leaves no earlier, wherever the window ends, and two
//! windows one after the other give what one window over both gives.
//!
//! **Between two points near enough, one can walk all the way,** and begin at any moment. The walk is one option,
//! given from the asked moment (arriving by: ending at it). Over a window it counts against a ride whenever that
//! leaves: a journey with a ride that takes no less time than the walk is no option, since whoever sets out on
//! foot as it leaves is there no later. So here too two windows give the rides that one window over both gives.
//!
//! **Asked for one moment, a journey leaves as late as it can.** The rounds give each journey with the first trip
//! that could be caught, which may be an earlier bus to the same train. So what was found is searched once more
//! from later moments at which a trip leaves, from the last downwards (one by one where they are few, in steps
//! where they are many), keeping only what ends no later with no more rides and at no more cost: the latest moment
//! that still gives such a journey is when to leave (`Search::leave_later`). (Asked to arrive by, the same
//! turned round: the journey is over as early as that departure allows.) Over a window nothing of this is needed:
//! there a later departure is an option in its own right, and beats the earlier one.
//!
//! **The two nights a year the clock changes**, a service day is an hour shorter or longer than 86,400 s. The file
//! says which (`Planner::zero`), and a trip of another day than the query's is placed by it.
//!
//! **Where wishes cost and are weighed** ([`crate::Query::weigh`]), there are two searches. First the plain one
//! from the window's far end: one label a stop, the earliest, its cost carried along and deciding nothing. Its
//! journeys are the fastest of each number of rides. Then the search with bags, which knows those journeys from
//! its start and is aimed by seconds and by rides: it makes no label that, at the least it still needs, would end
//! no sooner than one of them (or any journey found since) with no fewer rides, and no sooner with each one's cost
//! added to its end. That is the rule the options are chosen by, so nothing the answer would keep is lost to it.
//! It is followed as far as the plain search was, or as far as the latest of those journeys ends with its cost
//! added, whichever is later: past that, anything with as many rides is beaten. Asked for every option, wishes
//! that cost are searched with bags alone, to the horizon.
//!
//! **What bounds it** ([`Limits`]): rounds by `max_rides`; searches by `departures` (a window's moments, and the
//! later moments tried for one moment's journeys, of which only those that `options` lets show are searched for
//! again); labels and steps by their caps; a stop's bag of labels a round by `bag`; time by the window and the
//! horizon. Every loop over the file's data counts steps. An answer says when a cap was met
//! ([`Answer::cut_short`]), wherever in the search it was: also where the steps passed theirs outside a search's
//! rounds, in the count from the journey's end or in the list of a window's departures, and where a bag was full
//! and a label went from it (the option it led to may be the one that is missing).

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
/// A stop's own time to change where the feed says nobody changes there: past any search.
const NO_CHANGE: u32 = 1 << 24;
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

/// A journey of the answer to one moment, for the search that looks whether one can leave later for it: when it
/// leaves and ends (in the direction searched), its rides and cost. Among the later moments tried, `lo` is the
/// last known to do for it (or the one before them all) and `hi` the first known not to; `near` says no later
/// moment has done yet, and the one next after `lo` is still to try by itself. In one search, `done` says there
/// is nothing to look for (its moment tells nothing new of this journey, or the search has matched it: `hit` is
/// where in `found`). `seen` says every search since the last that began anew looked for this journey to its end,
/// so that the next may build on them.
#[derive(Clone, Copy)]
struct Anchor {
    leave: i32,
    time: i32,
    rides: u32,
    cost: u32,
    done: bool,
    lo: i32,
    hi: i32,
    near: bool,
    hit: u32,
    seen: bool,
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
/// stop under those rules, or, with `rule`, only what that rule says waits for it. That is boarded no later than
/// `last`: forward the moment the one vehicle that waits leaves, back the last moment at which the vehicle one
/// came by is still the one that waits. Searching back, `own` (a trip and its day) is the vehicle that leaves
/// before this one and ends its waiting; as an arrival it does not count against itself, and has until `own_last`.
#[derive(Clone, Copy)]
struct Held {
    label: u32,
    rule: u32,
    last: i32,
    own: (u32, i32),
    own_last: i32,
}

const NO_RULE: Held = Held { label: 0, rule: NONE, last: 0, own: (NONE, 0), own_last: 0 };

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
    /// The service days after which the next begins other than 86,400 s later (the clock changes): the day, and
    /// how many seconds more or fewer. By day; the file's `odd_days`.
    odd: Vec<(i32, i32)>,
    /// By stop, where the feed gives any stop its own time to change (a rule for everyone from a stop to itself):
    /// those seconds, `NO_CHANGE` where it says nobody changes there, `NONE` where it says nothing. Else empty.
    own_change: Vec<u32>,
    /// Where the feed says a vehicle waits for another (a rule of kind 1 or 4 that names a trip or a line, between
    /// two stops): for the search forward, each (stop it waits at, stop one comes off at); for the search back,
    /// the two turned round. Sorted, each pair once. And a bit a stop that has any, so that asking a stop that
    /// has none (nearly every one) reads one word.
    waits: [Vec<(u32, u32)>; 2],
    waits_at: [Vec<u64>; 2],
    /// What those rules (and the ones from a stop to itself) name on the side one comes off, as `bound` has it
    /// for every rule that binds; and by stop, sorted, the most seconds a change they speak of takes without
    /// them: the file's walk between the two stops (`u32::MAX` where it has none), and whether one of them is a
    /// change at the stop itself, which takes the stop's time to change.
    waiting: [Bound; 2],
    wait_reach: [Vec<(u32, u32, bool)>; 2],
}

impl Index {
    /// What a change at one stop costs there: the stop's own seconds where the feed gives them, else the file's;
    /// what the query asks for (`asked`) in their place, but never less than the stop's own.
    #[inline]
    fn change_at(&self, stop: u32, general: i32, asked: bool) -> i32 {
        match self.own_change.get(stop as usize) {
            Some(&own) if own != NONE => if asked { general.max(own as i32) } else { own as i32 },
            _ => general,
        }
    }
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
    /// The rides that ended this round by a trip the feed has a rule for at that stop. And those nobody rode: a
    /// later trip of a route ridden to a stop where a vehicle waits for it (`Search::later_arrivals`).
    special: Vec<u32>,
    later: Vec<u32>,
    /// By round: the labels held apart, and where those of the search under way begin (the earlier ones boarded
    /// all they could in the search that made them); and a bit a round for each stop that has one.
    held: Vec<Vec<Held>>,
    held_from: Vec<usize>,
    held_rounds: Vec<u32>,
    /// By round: the rides held so far in this query, as (stop, trip, day offset, cost); and the later trips
    /// held for what waits for them alone.
    special_seen: Vec<Vec<(u32, u32, i32, u32)>>,
    later_seen: Vec<Vec<(u32, u32, i32, u32)>>,
    found: Vec<Found>,
    front: Vec<Found>,
    /// By rides: the earliest end of a journey found with that many or fewer; and the least of end and cost together.
    end_best: [i32; MAX_RIDES as usize + 1],
    weighed_best: [i64; MAX_RIDES as usize + 1],
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
    /// A cap of [`crate::Limits`] was met, so there may be options missing (or, asked for one moment, one that
    /// leaves sooner than it need).
    pub cut_short: bool,
    /// How long past the asked moment and its window journeys were followed, in seconds: the query's horizon, or
    /// less where the search was patient ([`crate::Limits::patient`]). A patient search lacks nothing for it: what
    /// could still be an option past that (a journey with fewer rides than the first found, and any journey from
    /// the moments of a window for which nothing was found by then) is followed to the query's horizon all the same.
    pub horizon: u32,
    /// How many searches (departures) it took, and labels, and steps.
    pub searches: u32,
    pub labels: u32,
    pub steps: u64,
    /// Asked for one moment: the most later moments at which one of the journeys found might still have left,
    /// before the search for the latest of them. Up to 12 are tried one by one; more are stepped through (about
    /// their root apart, then one by one between two steps). 0 where no journey could leave later.
    pub later: u32,
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
        let mut own_change: Vec<u32> = Vec::new();
        let mut waits = [Vec::new(), Vec::new()];
        let mut waiting = [Bound::default(), Bound::default()];
        let mut wait_reach: [Vec<(u32, u32, bool)>; 2] = [Vec::new(), Vec::new()];
        for i in 0..tt.rules() {
            let r = tt.rule(i);
            if r.from_trip == NONE && r.to_trip == NONE && r.from_line == NONE && r.to_line == NONE {
                // A rule for everyone: between two stops it is in the walks already. From a stop to itself it is
                // that stop's own time to change (type 2), or says that nobody changes there (type 3).
                let own = match r.kind {
                    2 if r.min_time != NONE => r.min_time.min(MAX_TIME - 1),
                    3 => NO_CHANGE,
                    _ => NONE,
                };
                if r.from_stop == r.to_stop && r.from_stop < stops && own != NONE {
                    if own_change.is_empty() {
                        own_change = vec![NONE; stops as usize];
                    }
                    let at = &mut own_change[r.from_stop as usize];
                    *at = if *at == NONE { own } else { (*at).max(own) };
                }
                continue;
            }
            if matches!(r.kind, 1 | 4) && r.from_stop != r.to_stop && r.from_stop < stops && r.to_stop < stops {
                waits[0].push((r.to_stop, r.from_stop));
                waits[1].push((r.from_stop, r.to_stop));
            }
            if matches!(r.kind, 1 | 4) && r.from_stop < stops && r.to_stop < stops {
                let same = r.from_stop == r.to_stop;
                let (lo, hi) = tt.walks_from(r.from_stop);
                let secs = if same { 0 } else { (lo..hi).map(|w| tt.walk(w)).find(|w| w.0 == r.to_stop).map_or(u32::MAX, |w| w.1) };
                for (d, (stop, trip, line)) in [(r.from_stop, r.from_trip, r.from_line), (r.to_stop, r.to_trip, r.to_line)].into_iter().enumerate() {
                    wait_reach[d].push((stop, secs, same));
                    if trip != NONE {
                        waiting[d].trips.push((stop, trip));
                    } else if line != NONE {
                        waiting[d].lines.push((stop, line));
                    } else {
                        waiting[d].stops.push(stop);
                    }
                }
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
        let mut waits_at = [Vec::new(), Vec::new()];
        for (w, at) in waits.iter_mut().zip(waits_at.iter_mut()) {
            w.sort_unstable();
            w.dedup();
            if !w.is_empty() {
                *at = vec![0u64; words];
                for &(stop, _) in w.iter() {
                    at[(stop >> 6) as usize] |= 1 << (stop & 63);
                }
            }
        }
        for reach in wait_reach.iter_mut() {
            // a stop once: the longest of its changes, and whether any is at the stop itself
            reach.sort_unstable();
            let mut once: Vec<(u32, u32, bool)> = Vec::new();
            for &(stop, secs, same) in reach.iter() {
                match once.last_mut() {
                    Some(o) if o.0 == stop => *o = (stop, o.1.max(secs), o.2 || same),
                    _ => once.push((stop, secs, same)),
                }
            }
            *reach = once;
        }
        for b in bound.iter_mut().chain(waiting.iter_mut()) {
            b.trips.sort_unstable();
            b.trips.dedup();
            b.lines.sort_unstable();
            b.lines.dedup();
            b.stops.sort_unstable();
            b.stops.dedup();
        }
        let max_time = (latest.saturating_add(longest)).min(2 * MAX_TIME) as i32;
        // the days the clock changes, as the packer wrote them: "YYYYMMDD:seconds to the next day's beginning"
        let mut odd: Vec<(i32, i32)> = Vec::new();
        for word in tt.meta("odd_days").unwrap_or("").split(' ').take(64) {
            let Some((date, secs)) = word.split_once(':') else { continue };
            let (Some(day), Ok(secs)) = (date.parse().ok().and_then(|d| tt.day_of(d)), secs.parse::<i32>()) else { continue };
            if (DAY - 7200..=DAY + 7200).contains(&secs) && secs != DAY && day.unsigned_abs() < 100_000 && !odd.iter().any(|o| o.0 == day) {
                odd.push((day, secs - DAY));
            }
        }
        odd.sort_unstable();
        Planner { tt, ix: Index { by_lat, back_first, back, rules_by_to, rule_from, rule_to, bound, max_time, hop, odd, own_change, waits, waits_at, waiting, wait_reach }, days: Vec::new(), work: Work::default() }
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
        // (where the clock changes, a day begins up to two hours off its 86,400 s)
        let slack = if self.ix.odd.is_empty() { 0 } else { 7200 };
        let lo = (earliest - slack - self.ix.max_time).div_euclid(DAY);
        (lo, (latest + slack).div_euclid(DAY).min(lo + 15))
    }

    /// The seconds from the beginning of service day `day` to the beginning of the day `off` days on (or before):
    /// 86,400 a day, and an hour fewer or more over a day the clock changes on.
    pub(crate) fn zero(&self, day: i32, off: i32) -> i32 {
        let off = off.clamp(-64, 64);
        let mut z = off * DAY;
        for &(d, shift) in self.ix.odd.iter() {
            if off > 0 && d >= day && d < day.saturating_add(off) {
                z += shift;
            } else if off < 0 && d >= day.saturating_add(off) && d < day {
                z -= shift;
            }
        }
        z
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
        let mut zeros = [0i32; KEPT_DAYS];
        for off in off_lo..=off_hi {
            if let Some(at) = self.days.iter().position(|l| l.day == day + off) {
                day_lists[(off - off_lo) as usize] = at;
            }
            zeros[(off - off_lo) as usize] = self.zero(day, off);
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
            weigh: prefs.soft() && q.weigh,
            back,
            day,
            days: &self.days,
            day_lists,
            zeros,
            off_lo,
            off_hi,
            limit: start + window + horizon,
            change: q.change_s.unwrap_or(self.tt.change_seconds()).min(MAX_TIME - 1) as i32,
            change_asked: q.change_s.is_some(),
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
            far: start + window + horizon,
            kept: Vec::new(),
            covered: None,
            cover: 255,
            short: None,
            anchors: Vec::new(),
            later: false,
            most: max_rides,
            dropped: false,
            later_most: 0,
            exact: false,
            beyond: 0,
        };
        let far = s.limit;
        // A window holds its last moment, and the search from its far end is from a second after that: what leaves
        // after the window counts against what leaves inside it, the last moment's journeys too.
        let span = if window > 0 { window + 1 } else { 0 };
        let cap = q.limits.departures.clamp(1, 100_000) as usize;
        let mut searches = 0u32;
        let mut journeys: Vec<Journey> = Vec::new();
        if s.weigh {
            // Wishes that cost, weighed. First the plain search from the window's far end, as if nothing cost
            // (one label a stop, the earliest; the cost is only carried along): its journeys are the fastest of
            // each number of rides, and they say what anything else must beat. Whatever has no fewer rides than
            // the one with the fewest ends no later than that one's end with its cost added, or is beaten by it.
            let bag = s.bag;
            (s.soft, s.weigh, s.bag) = (false, false, 1);
            searches += s.sweep(first, last, start, span, far, q.limits.patient, false, cap);
            let fastest: Vec<Found> = s.w.found.clone();
            journeys.extend(fastest.iter().filter_map(|f| s.journey(f)));
            (s.soft, s.weigh, s.bag) = (true, true, bag);
            // Nothing more to find: no way leads there at all (the plain search did not even start); or, for one
            // moment, there is no journey, or the fastest of each number of rides break no wish, and so beat all.
            let done = searches == 0 || (window == 0 && fastest.iter().all(|f| f.cost == 0));
            if !s.cut && !done {
                // then the search with a bag a stop, which makes no label that those journeys already beat
                let latest = fastest.iter().map(|f| f.time as i64 + f.cost as i64).max();
                let counted = s.aimed && s.lazy && window == 0 && latest.is_some();
                s.limit = match latest {
                    Some(latest) => far.min(s.limit.max(latest.min(far as i64) as i32)),
                    None => far,
                };
                s.reset(last, counted);
                for f in fastest.iter() {
                    s.know(f);
                }
                if latest.is_none() {
                    // nothing leaves from the window's far end on: what leaves inside it is all there is
                    (s.aimed, s.lazy) = (false, false);
                } else if counted {
                    // the count of least seconds from the end goes on where the plain search left it
                    s.count_rides(last, None);
                } else {
                    s.aim(last, (s.limit as i64 - start as i64).clamp(0, 4 * MAX_TIME as i64) as u32);
                }
                searches += s.searches(first, last, start, span, cap, true, false);
                // what ends later than this limit is beaten by the plain search's journeys, unless it has fewer rides
                s.short = (s.limit < far).then_some(s.limit);
                s.cover = fastest.iter().map(|f| f.rides.min(254) as u8).min().unwrap_or(255);
            }
            searches += s.fewer_rides(first, last, start, span, cap, &fastest);
        } else {
            searches += s.sweep(first, last, start, span, far, q.limits.patient && !s.soft, true, cap);
            searches += s.fewer_rides(first, last, start, span, cap, &[]);
        }
        let followed = (s.short.unwrap_or(s.limit) - start - window).clamp(0, 2 * DAY) as u32;
        journeys.append(&mut s.kept);
        for f in s.w.found.iter() {
            if let Some(j) = s.journey(f) {
                journeys.push(j);
            }
        }
        // On foot all the way, between two points near enough. The walk can begin at any moment: it is one option,
        // given from the asked moment (arriving by: ending at it), and over a window it counts against a ride
        // whenever that leaves.
        let mut on_foot: Option<i32> = None;
        if let (Place::Point { lat: a, lon: b }, Place::Point { lat: c, lon: d }) = (&q.from, &q.to) {
            let mm = distance_mm(*a, *b, *c, *d);
            if mm <= 2 * q.reach_m.min(50_000) as u64 * 1000 {
                let secs = (mm * 130 / 100).div_ceil(q.walk_mm_s.clamp(100, 100_000) as u64) as i32;
                let depart = if back { time - secs } else { time };
                let leg = Leg::Walk { from: Spot::Origin, to: Spot::Destination, depart, arrive: depart + secs };
                journeys.push(Journey { depart, arrive: depart + secs, rides: 0, penalty: 0, walking: secs as u32, legs: vec![leg] });
                on_foot = Some(secs);
            }
        }
        // What is asked for one moment is judged by arrival (or, arriving by, by departure), rides and cost; over a
        // window the other end of the journey counts too, and only what lies inside the window is an option.
        // Weighing, the fourth is not the cost but the arrival with the cost added: what the wishes are worth.
        let weigh = s.weigh;
        let key = |j: &Journey| {
            let (leave, reach) = if back { (-j.arrive, -j.depart) } else { (j.depart, j.arrive) };
            (if window == 0 { 0 } else { -leave }, reach, j.rides, if weigh { reach as i64 + j.penalty as i64 } else { j.penalty as i64 }, -leave)
        };
        let best = |journeys: &[Journey]| {
            let mut keep: Vec<Journey> = Vec::new();
            for (i, j) in journeys.iter().enumerate() {
                let a = key(j);
                let inside = window == 0 || if back { j.arrive >= time - window } else { j.depart <= time + window };
                let beaten = journeys.iter().enumerate().any(|(k, o)| {
                    let b = key(o);
                    let same = (b.0, b.1, b.2, b.3) == (a.0, a.1, a.2, a.3);
                    (b.0 <= a.0 && b.1 <= a.1 && b.2 <= a.2 && b.3 <= a.3 && !same) || (same && (b.4 < a.4 || (b.4 == a.4 && k < i)))
                });
                // Over a window, a journey with a ride that takes no less time than the walk all the way is beaten
                // by that walk begun when it leaves, in whichever window that moment lies. (For one moment the
                // walk from that moment is among the journeys, and beats it there.)
                let beaten = beaten || (window > 0 && j.rides > 0 && on_foot.is_some_and(|secs| j.arrive - j.depart >= secs));
                if inside && !beaten {
                    keep.push(j.clone());
                }
            }
            keep
        };
        let mut keep = best(&journeys);
        let options = q.limits.options.clamp(1, 1024) as usize;
        // Asked for one moment, with more options than may be shown: the fastest stay (the earliest arrivals;
        // arriving by, the latest departures). Weighing, the fastest are those that are in first with their cost
        // added, which is what the wishes say an arrival is worth: of a bus in at 10:00 that costs a quarter of
        // an hour and a train in at 10:05 that costs nothing, the train. That is settled here, before any is
        // searched again for a later departure: leaving later changes no arrival, and what is not shown is not
        // searched for.
        let mut too_many = false;
        if window == 0 && keep.len() > options {
            keep.sort_by_key(|j| {
                let k = key(j);
                if weigh { (k.3, k.1 as i64, k.2 as i64) } else { (k.1 as i64, k.2 as i64, k.3) }
            });
            keep.truncate(options);
            too_many = true;
        }
        // Asked for one moment, each journey so far leaves when the first trip it caught does, which may be sooner
        // than it need: an earlier bus to the same train. Whoever asks is told when to leave at the latest (and,
        // asking to arrive by, when the journey is over at the earliest).
        if window == 0 && !s.cut && s.steps <= s.max_steps {
            let anchors: Vec<Anchor> = keep
                .iter()
                .filter(|j| j.rides > 0)
                .map(|j| {
                    let (leave, reach) = if back { (-j.arrive, -j.depart) } else { (j.depart, j.arrive) };
                    Anchor { leave, time: reach, rides: j.rides, cost: j.penalty, done: false, lo: -1, hi: 0, near: true, hit: NONE, seen: false }
                })
                .collect();
            searches += s.leave_later(first, last, start, anchors, cap);
            if !s.kept.is_empty() {
                keep.append(&mut s.kept);
                keep = best(&keep);
            }
        }
        keep.sort_by_key(|j| (j.depart, j.arrive, j.rides, j.penalty));
        // Cut short: a cap was met where the search says so itself, or the steps passed theirs anywhere at all (the
        // count from the journey's end, the list of a window's departures): whatever came after did less than
        // it should.
        // And where a bag was full and a label went from it, an option may have gone with it.
        let mut cut = s.cut || s.steps > s.max_steps || too_many || s.dropped;
        if keep.len() > options {
            // too many over a window: those nearest the asked moment stay
            if back {
                keep.drain(..keep.len() - options);
            } else {
                keep.truncate(options);
            }
            cut = true;
        }
        Answer { journeys: keep, cut_short: cut, horizon: followed, searches, labels: s.w.labels.len() as u32, steps: s.steps, later: s.later_most }
    }
}

struct Search<'s, 'a> {
    tt: Timetable<'a>,
    ix: &'s Index,
    w: &'s mut Work,
    prefs: &'s Prefs,
    soft: bool,
    /// Wishes cost, and their seconds are what they are worth: cost counts as that much later an end.
    weigh: bool,
    back: bool,
    /// The query's service day, and the days around it whose trips are looked at, with each one's list.
    day: i32,
    days: &'s [DayTrips],
    day_lists: [usize; KEPT_DAYS],
    /// By day offset: when that service day begins, in seconds of the query's ([`Planner::zero`]).
    zeros: [i32; KEPT_DAYS],
    off_lo: i32,
    off_hi: i32,
    /// Nothing later than this is followed.
    limit: i32,
    change: i32,
    change_asked: bool,
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
    /// The query's own limit: its moment, window and horizon.
    far: i32,
    /// The journeys of a pass whose labels the next pass wiped.
    kept: Vec<Journey>,
    /// Of the searches of one pass, the latest moment first: the moment of the first that had a journey (so every
    /// moment searched after it has one that leaves no earlier), and the fewest rides it had. `None` and 255 when
    /// none had.
    covered: Option<i32>,
    cover: u8,
    /// The limit journeys were followed to, where that is short of the query's own.
    short: Option<i32>,
    /// Looking whether one can leave later ([`Search::leave_later`]): the journeys to match.
    anchors: Vec<Anchor>,
    later: bool,
    /// The most rides a journey may have in this pass (`max_rides`, or fewer: [`Search::fewer_rides`]).
    most: usize,
    /// A label went from a full bag, or a trip from those ridden along a route: what it led to may be missing.
    dropped: bool,
    /// The most later moments a journey had to choose among in `leave_later` ([`Answer::later`]).
    later_most: u32,
    /// Whether the fewest rides to the journey's end are counted for every stop within fewer than `beyond` of it,
    /// so that a stop without a count is at least `beyond` rides away.
    exact: bool,
    beyond: usize,
}

impl<'s> Search<'s, '_> {
    /// The searches of a query, patiently or not. Patiently: only as far as a journey could sensibly take (twice the
    /// least time any timetable could give it, and half an hour; an hour at least), with the search aimed from its
    /// start. Where nothing is found by then for the last moments of a window (or for any moment), those are
    /// searched again as far as `far`: the last way of an evening takes long, and is an option all the same. So what
    /// is then still to find is only a journey with fewer rides than each one found that leaves no earlier
    /// ([`Search::fewer_rides`]). Returns how many searches it took.
    #[allow(clippy::too_many_arguments)]
    fn sweep(&mut self, first: &[(u32, u32)], last: &[(u32, u32)], start: i32, span: i32, far: i32, patient: bool, inside: bool, cap: usize) -> u32 {
        self.reset(last, false);
        (self.aimed, self.lazy) = (false, false);
        (self.short, self.covered, self.cover) = (None, None, 255);
        let mut limit = far;
        if patient {
            match self.aim_lazily(last, first) {
                Some(least) => limit = far.min(start + (span - 1).max(0) + (2 * least.min(MAX_TIME) as i32 + 1800).max(3600)),
                None => return 0, // no timetable could make this journey: there is nothing to search
            }
        }
        self.limit = limit;
        let mut searches = self.searches(first, last, start, span, cap, inside, true);
        if limit == far {
            return searches;
        }
        // from which moment on nothing was found: after the first search (the latest moment first) that had a journey
        let from = match self.covered {
            Some(moment) if moment >= start + span => None,
            Some(moment) => Some(moment + 1),
            None => Some(start),
        };
        let Some(from) = from.filter(|_| !self.cut) else {
            self.short = Some(limit);
            return searches;
        };
        let cover = self.cover;
        if self.covered.is_some() {
            let found = core::mem::take(&mut self.w.found);
            let kept: Vec<Journey> = found.iter().filter_map(|f| self.journey(f)).collect();
            self.kept.extend(kept);
            self.short = Some(limit);
        }
        // (the count of least seconds from the journey's end goes on where it was: it holds whatever the limit)
        self.reset(last, true);
        self.limit = far;
        searches += self.searches(first, last, from, start + span - from, cap, inside, true);
        self.cover = cover;
        searches
    }

    /// One search from the far end of a span of moments (`start + span`: for one moment, with a span of 0, the
    /// moment itself, and that is the whole answer; for a window the second after its last moment), with every
    /// first stop. For a window it is what the searches inside build on, and what shows which of their journeys a
    /// later departure makes pointless. Then, `inside`, one search for each moment of the span at which a trip
    /// leaves a first stop, the latest first; before them, with `aim`, the search is aimed for good.
    #[allow(clippy::too_many_arguments)]
    fn searches(&mut self, first: &[(u32, u32)], last: &[(u32, u32)], start: i32, span: i32, cap: usize, inside: bool, aim: bool) -> u32 {
        let mut searches = 1u32;
        (self.covered, self.cover) = (None, 255);
        self.begin();
        for &(stop, secs) in first.iter() {
            self.offer_first(stop, start + span + secs as i32, secs);
        }
        self.rounds();
        self.note_cover(start + span);
        if span == 0 || !inside {
            return searches;
        }
        if aim {
            // how late any option may end: the end of the one with the fewest rides. With that, every stop in reach
            // gets its least time and fewest rides for good, for all the searches to come.
            if let Some(&latest) = self.w.end_best.iter().find(|e| **e != NEVER) {
                self.aim(last, (latest - start).max(0) as u32);
            }
        }
        let mut events = self.departures(first, start, span - 1);
        if self.steps > self.max_steps {
            // (the count from the journey's end or this list went past the cap: the list is not whole)
            self.cut = true;
            return searches;
        }
        events.sort_unstable();
        let mut distinct = 0usize;
        let mut keep = events.len();
        for i in 0..events.len() {
            if i == 0 || events[i].0 != events[i - 1].0 {
                distinct += 1;
                if distinct > cap {
                    keep = i;
                    self.cut = true;
                    break;
                }
            }
        }
        events.truncate(keep);
        let mut i = events.len();
        while i > 0 && self.steps <= self.max_steps && self.w.labels.len() < self.max_labels {
            let moment = events[i - 1].0;
            self.begin();
            while i > 0 && events[i - 1].0 == moment {
                let (stop, secs) = first[events[i - 1].1 as usize];
                self.offer_first(stop, moment + secs as i32, secs);
                i -= 1;
            }
            self.rounds();
            searches += 1;
            self.note_cover(moment);
        }
        if i > 0 {
            self.cut = true;
        }
        searches
    }

    /// After the search from `moment`: whether it is the first of its pass with a journey to show.
    fn note_cover(&mut self, moment: i32) {
        if self.covered.is_none() {
            if let Some(rides) = self.w.end_best.iter().position(|e| *e != NEVER) {
                (self.covered, self.cover) = (Some(moment), rides as u8);
            }
        }
    }

    /// Asked for one moment: whether one can leave later for what was found. Each of the `anchors` is a journey of
    /// the answer, and a later moment does for it when a search from there ends no later, with no more rides and at
    /// no more cost. Leaving earlier never hurts, so the moments that do are all those up to one, and that one is
    /// looked for among the moments at which a trip leaves a first stop, from the last that could be in time
    /// downwards: a search from an earlier moment builds on the one before it (what was reached then is reached no
    /// later now), so going down costs little, and going up means beginning anew.
    ///
    /// Where the moments are few, each is tried in turn, and the first that does is the latest. Where they are
    /// many (a busy stop, and a journey with a long wait in it), every so-manieth is tried, and the moments
    /// between the first that does and the one tried before it in turn; before that, once the moments left are few,
    /// the one next after the journey's own departure by itself, since most journeys already leave as late as they
    /// can and that search says so. Each search is held to what could still match a journey it can tell something
    /// of. The journeys that leave later are put in `kept`. Returns how many searches it took: `cap` at most
    /// ([`crate::Limits::departures`]), and where a search more would have been needed to know every journey's
    /// latest moment, the search is cut short: a journey may then leave sooner than it need.
    fn leave_later(&mut self, first: &[(u32, u32)], last: &[(u32, u32)], start: i32, mut anchors: Vec<Anchor>, cap: usize) -> u32 {
        let Some(end) = anchors.iter().map(|a| a.time).max() else {
            return 0;
        };
        // aimed at the journey's end: as far as the count from there has come, or anew
        if self.aimed {
            self.reset(last, true);
        } else {
            self.reset(last, false);
            self.exact = false;
            if self.aim_lazily(last, first).is_none() {
                return 0;
            }
        }
        self.limit = end.min(self.far);
        // no moment later than the latest end less the least time any timetable gives the journey can make it
        let span = (end as i64 - start as i64).clamp(0, 4 * MAX_TIME as i64) as u32;
        let mut whole = u32::MAX;
        for &(stop, secs) in first.iter() {
            if (stop as usize) < self.stops && !bit(&self.prefs.closed, stop) {
                let least = if self.lazy { self.least_within(stop, span) } else { self.w.least[stop as usize] };
                whole = whole.min(least.saturating_add(secs));
            }
        }
        if whole >= span {
            return 0;
        }
        let mut moments: Vec<i32> = self.departures(first, start.saturating_add(1), (span - whole - 1) as i32).iter().map(|e| e.0).collect();
        moments.sort_unstable();
        moments.dedup();
        let n = moments.len() as i32;
        for a in anchors.iter_mut() {
            // the moments up to the journey's own departure do and are no later; past the last, none is in time
            a.lo = moments.partition_point(|m| *m <= a.leave) as i32 - 1;
            (a.hi, a.near) = (n, true);
        }
        (self.anchors, self.later) = (anchors, true);
        let mut latest: Vec<Option<Journey>> = vec![None; self.anchors.len()];
        // how far apart the moments tried are: one by one where they are few, else about the root of their number
        let most = self.anchors.iter().map(|a| n - 1 - a.lo).max().unwrap_or(0);
        self.later_most = self.later_most.max(most.max(0) as u32);
        let mut apart = 1;
        while most > 12 && apart * apart < most {
            apart += 1;
        }
        // (the moment of the search before, while this one can build on it; the journeys of each are in `found`)
        let (mut searches, mut before) = (0u32, i32::MIN);
        while let Some(target) = self.anchors.iter().position(|a| a.hi - a.lo > 1) {
            if self.cut || self.steps > self.max_steps {
                self.cut = true;
                break;
            }
            let t = self.anchors[target];
            let p = if t.hi - t.lo > apart {
                t.hi - apart
            } else if t.near && apart > 1 {
                self.anchors[target].near = false;
                t.lo + 1
            } else {
                t.hi - 1
            };
            // A search from a later moment is one to build on, for a journey that every search since the last
            // fresh one looked for to its end: what was reached then is reached no later now, and whatever could
            // still match that journey was followed. Otherwise the search begins anew.
            if p >= before || !t.seen {
                self.reset(last, true);
                for a in self.anchors.iter_mut() {
                    a.seen = true;
                }
            }
            before = p;
            for a in self.anchors.iter_mut() {
                (a.done, a.hit) = (p <= a.lo || p >= a.hi || !a.seen, NONE);
            }
            self.begin();
            // from every first stop, as a search by itself: a trip that leaves another of them later counts too
            for &(stop, secs) in first.iter() {
                self.offer_first(stop, moments[p as usize] + secs as i32, secs);
            }
            if !self.w.marks[0].is_empty() {
                // The cap is met here, where a search is needed and may not be made: a moment from which nothing
                // can be in time needs none, and an answer whose last tries were all of that kind is whole.
                if searches as usize >= cap {
                    self.cut = true;
                    break;
                }
                self.rounds();
                searches += 1;
            }
            for k in 0..self.anchors.len() {
                let mut a = self.anchors[k];
                if a.hit != NONE {
                    (a.lo, a.near) = (p, false);
                    latest[k] = self.w.found.get(a.hit as usize).and_then(|f| self.journey(f));
                } else if !a.done {
                    a.hi = p;
                }
                // (matched, or not looked for: this search did not follow what could match it to the end)
                a.seen &= !a.done;
                self.anchors[k] = a;
            }
        }
        self.later = false;
        self.kept.extend(latest.into_iter().flatten());
        searches
    }

    /// What a patient search has yet to find. It stopped short of the query's horizon (`short`), and the first search
    /// with a journey had one of `cover` rides at the fewest: that one beats whatever leaves no later, ends later
    /// and has no fewer rides. So only a journey with fewer rides can still be an option past where the search
    /// stopped: the direct bus that takes an hour longer than the way with a change. The same search again, as far
    /// as the query's own horizon, for journeys of fewer rides alone.
    ///
    /// That is a small search. The stops within half those rides of the journey's end are counted from there, ride
    /// by ride; every other stop is at least one ride further, which is bound enough: in its last rounds the
    /// search touches only stops that still lead to the end. And over a window it is first made once, from the
    /// window's first moment: leaving later reaches nothing that leaving then does not, so when that finds no
    /// journey there is none, which is how it mostly ends. `known` are journeys of another search to beat.
    /// Returns how many searches it took.
    fn fewer_rides(&mut self, first: &[(u32, u32)], last: &[(u32, u32)], start: i32, span: i32, cap: usize, known: &[Found]) -> u32 {
        if self.short.is_none() || self.cut || self.steps > self.max_steps || !self.aimed || self.cover == 255 || self.cover < 2 {
            return 0;
        }
        let fewer = (self.cover as usize - 1).min(self.max_rides);
        for &s in self.w.aimed.iter() {
            self.w.fewest[s as usize] = 255;
        }
        // (the count goes no further from the end than `max_rides`)
        let all = self.max_rides;
        self.max_rides = fewer / 2;
        self.count_rides(last, None);
        self.max_rides = all;
        (self.exact, self.beyond) = (true, fewer / 2 + 1);
        let found = core::mem::take(&mut self.w.found);
        let kept: Vec<Journey> = found.iter().filter_map(|f| self.journey(f)).collect();
        self.kept.extend(kept);
        (self.limit, self.most) = (self.far, fewer);
        let mut searches = 0;
        for whole in [false, true] {
            self.reset(last, true);
            for f in known {
                self.know(f);
            }
            searches += self.searches(first, last, start, if whole { span } else { 0 }, cap, whole, false);
            if span == 0 || self.w.found.is_empty() || self.cut {
                break;
            }
        }
        self.most = all;
        searches
    }

    /// Takes a journey another search found as one to beat: nothing it beats is made, and it is not found again.
    fn know(&mut self, f: &Found) {
        let rides = (f.rides as usize).min(MAX_RIDES as usize);
        let weighed = f.time as i64 + f.cost as i64;
        self.w.front.push(Found { label: NONE, ..*f });
        for best in self.w.end_best[rides..].iter_mut() {
            *best = (*best).min(f.time);
        }
        for best in self.w.weighed_best[rides..].iter_mut() {
            *best = (*best).min(weighed);
        }
    }

    /// Wipes what the search before wrote, and sets where this one ends. `counted` keeps the count of least seconds
    /// from the journey's end as far as it has come (the end and what is closed must be the same).
    fn reset(&mut self, last: &[(u32, u32)], counted: bool) {
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
        if !counted {
            for &s in w.aimed.iter() {
                w.least[s as usize] = u32::MAX;
                w.fewest[s as usize] = 255;
            }
            w.aimed.clear();
            w.heap.clear();
        }
        w.found.clear();
        w.front.clear();
        w.end_best = [NEVER; MAX_RIDES as usize + 1];
        w.weighed_best = [i64::MAX; MAX_RIDES as usize + 1];
        w.marks.resize_with(rounds, Vec::new);
        w.held.resize_with(rounds, Vec::new);
        for h in w.held.iter_mut() {
            h.clear();
        }
        w.special_seen.resize_with(rounds, Vec::new);
        w.later_seen.resize_with(rounds, Vec::new);
        for h in w.special_seen.iter_mut().chain(w.later_seen.iter_mut()) {
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

    /// When the service day `off` days from the query's begins, in the query's seconds.
    #[inline]
    fn zero(&self, off: i32) -> i32 {
        self.zeros.get((off - self.off_lo) as usize).copied().unwrap_or(off * DAY)
    }

    #[inline]
    fn board_time(&self, times: (u32, u32), pos: u32, off: i32) -> i32 {
        let (arrive, depart) = self.tt.time(times, pos);
        if self.back { -(self.zero(off) + arrive as i32) } else { self.zero(off) + depart as i32 }
    }

    #[inline]
    fn alight_time(&self, times: (u32, u32), pos: u32, off: i32) -> i32 {
        let (arrive, depart) = self.tt.time(times, pos);
        if self.back { -(self.zero(off) + depart as i32) } else { self.zero(off) + arrive as i32 }
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
        if self.back { -(self.zero(off) + run.span.0 as i32) >= time } else { self.zero(off) + run.span.1 as i32 >= time }
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
        if self.back { -(self.zero(off) + self.ix.max_time) } else { self.zero(off) }
    }

    fn touch(&mut self, stop: u32) {
        if !self.w.is_touched[stop as usize] {
            self.w.is_touched[stop as usize] = true;
            self.w.touched.push(stop);
        }
    }

    /// Whether a journey already found makes anything at this time and cost, after `rides` rides, pointless: it ends
    /// no later with no more rides and at no more cost; or, weighing, no later with its cost added to its end.
    fn beaten_at_end(&self, rides: usize, time: i32, cost: u32) -> bool {
        let rides = rides.min(MAX_RIDES as usize);
        if self.later {
            // looking whether one can leave later: only what is no worse than a journey not yet matched
            return !self.anchors.iter().any(|a| !a.done && time <= a.time && rides as u32 <= a.rides && (!self.soft || cost <= a.cost));
        }
        if time < self.w.end_best[rides] {
            return false;
        }
        if !self.soft {
            return true;
        }
        if !self.weigh {
            return self.w.front.iter().any(|f| f.time <= time && f.cost <= cost && f.rides as usize <= rides);
        }
        // past the best weighed end of that many rides, whatever the cost
        let weighed = time as i64 + cost as i64;
        time as i64 >= self.w.weighed_best[rides] || self.w.front.iter().any(|f| f.time <= time && f.rides as usize <= rides && f.time as i64 + f.cost as i64 <= weighed)
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
        let more = match self.w.fewest[stop as usize] {
            255 if self.exact => self.beyond,
            255 => 0,
            n => n as usize,
        };
        if rides + more > self.most {
            return true;
        }
        let left = (self.limit as i64 - time as i64).min(MAX_TIME as i64 * 8) as u32;
        let least = if self.lazy { self.least_within(stop, left) } else { self.w.least[stop as usize].min(self.w.reach.saturating_add(1)) };
        least > left || self.beaten_at_end(rides + more, time + least as i32, cost)
    }

    /// Starts the count of least seconds to the journey's end at the end's stops.
    fn seed(&mut self, last: &[(u32, u32)]) {
        self.exact = false;
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
        let waited = self.waited_from(v);
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
        // a vehicle here that waits for one at another stop is boarded whatever the walk takes: no seconds at least
        for &(_, u) in waited.iter() {
            relax(u, d);
        }
        // the stop before this one on each route through it: over the pattern's fastest hop
        let (lo, hi) = self.tt.routes_at(v);
        self.steps += (hi - lo) as u64 + 1 + waited.len() as u64;
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

    /// The stops from where a vehicle at `stop` is boarded whatever the walk takes, because the feed says it waits
    /// for what one comes off there (in the direction searched): each as (`stop`, that stop). For the counts from
    /// the journey's end each is as near the end as `stop` is, in seconds and in rides: or the counts would be no
    /// least where such a rule leads between two stops that no walk joins, or joins in more time than the vehicle
    /// gives. Read from a list made once ([`Index::waits`]): every such rule of the file is in it, however many
    /// there are, and asking costs no pass over them.
    fn waited_from(&self, stop: u32) -> &'s [(u32, u32)] {
        let ix: &'s Index = self.ix;
        if !self.rules || ix.waits_at[self.back as usize].get((stop >> 6) as usize).is_none_or(|w| w >> (stop & 63) & 1 == 0) {
            return &[];
        }
        let all = &ix.waits[self.back as usize];
        let lo = all.partition_point(|e| e.0 < stop);
        let n = all[lo..].partition_point(|e| e.0 == stop);
        &all[lo..lo + n]
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
        self.w.reach = reach;
        self.count_rides(last, Some(reach));
        self.aimed = true;
    }

    /// The fewest rides from each stop to the journey's end: counted back from the end's stops level by level, a
    /// ride one level on, a walk none. With `reach`, only over the stops whose least seconds to the end are within
    /// it (the count of seconds is whole that far: no way in time leads over any other stop); without, over every
    /// stop a way leads from.
    fn count_rides(&mut self, last: &[(u32, u32)], reach: Option<u32>) {
        let mut queue: VecDeque<u32> = VecDeque::new();
        // the fewest rides: from the end's stops outward, a ride one level on, a walk none
        if self.w.along.len() != self.tt.patterns() as usize {
            self.w.along = vec![NONE; self.tt.patterns() as usize];
        }
        let mut patterns: Vec<u32> = Vec::new();
        let within = |w: &Work, u: u32| reach.is_none_or(|r| w.least[u as usize] <= r);
        // a stop whose seconds are not counted is not among the stops to wipe yet
        let note = |w: &mut Work, u: u32| {
            if w.least[u as usize] == u32::MAX && w.fewest[u as usize] == 255 {
                w.aimed.push(u);
            }
        };
        for &(s, _) in last {
            if (s as usize) < self.stops && !bit(&self.prefs.closed, s) && within(self.w, s) && self.w.fewest[s as usize] == 255 {
                note(self.w, s);
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
                if within(self.w, u) && self.w.fewest[u as usize] > level {
                    note(self.w, u);
                    self.w.fewest[u as usize] = level;
                    queue.push_front(u);
                }
            }
            // nor does stepping over to a vehicle that waits
            let waited = self.waited_from(v);
            self.steps += waited.len() as u64;
            for &(_, u) in waited {
                if within(self.w, u) && self.w.fewest[u as usize] > level {
                    note(self.w, u);
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
                    if within(self.w, u) && self.w.fewest[u as usize] > level + 1 {
                        note(self.w, u);
                        self.w.fewest[u as usize] = level + 1;
                        queue.push_back(u);
                    }
                }
            }
        }
        for p in patterns {
            self.w.along[p as usize] = NONE;
        }
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
            // (where cost is only carried along, the earlier label is all that stays)
            if !self.soft || (label.time <= o.time && label.cost <= o.cost) {
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
            self.dropped = true;
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
        if stop as usize >= self.stops || bit(&self.prefs.closed, stop) || self.pointless(0, stop, time, 0) || self.beaten(true, 0, stop, time, 0) {
            return;
        }
        if self.w.labels.len() >= self.max_labels {
            self.cut = true;
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
            self.w.later.clear();
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
                self.offer_ready(round, lab.stop, lab.time + self.ix.change_at(lab.stop, self.change, self.change_asked), lab.cost, l);
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
                if self.steps > self.max_steps {
                    // (each reads its stop's rules, which a file may have any number of)
                    self.cut = true;
                    break;
                }
                let lab = self.w.labels[l as usize];
                self.hold(round, lab.stop, lab.time + self.ix.change_at(lab.stop, self.change, self.change_asked), lab.cost, l, NO_RULE);
                let (lo, hi) = if self.back { (self.ix.back_first[lab.stop as usize], self.ix.back_first[lab.stop as usize + 1]) } else { self.tt.walks_from(lab.stop) };
                for i in lo..hi {
                    let (to, secs) = if self.back { self.ix.back[i as usize] } else { self.tt.walk(i) };
                    self.hold(round, to, lab.time + secs as i32, lab.cost, l, NO_RULE);
                }
                self.hold_what_waits(round, l);
            }
            self.w.special = special;
            self.w.special.clear();
            // and for the later trips of a route ridden to a stop where a vehicle waits: what waits for each
            let later = core::mem::take(&mut self.w.later);
            for &l in later.iter() {
                if self.steps > self.max_steps {
                    self.cut = true;
                    break;
                }
                self.hold_what_waits(round, l);
            }
            self.w.later = later;
            self.w.later.clear();
        }
    }

    /// Holds, for the ride that ended label `l`, what each rule says waits for it: the one vehicle, boarded
    /// whatever the walk or the change takes. Forward that is the first the rule speaks of to leave once this ride
    /// is in. Back, this ride is the vehicle, and it waits for whoever came in since the one before it left.
    fn hold_what_waits(&mut self, round: usize, l: u32) {
        let lab = self.w.labels[l as usize];
        let line = self.tt.route(self.tt.route_of_trip(lab.trip)).line;
        let (lo, hi) = self.rules_from(lab.stop);
        self.steps += (hi - lo) as u64 + 1;
        for i in lo..hi {
            let (rule, r) = self.rule_at(i);
            let (from, to) = self.sides(&r);
            if !(matches!(r.kind, 1 | 4) && from.0 == lab.stop && fits(from.1, lab.trip) && fits(from.2, line) && (from.1 != NONE || from.2 != NONE || to.1 != NONE || to.2 != NONE)) {
                continue;
            }
            let held = if self.back {
                let before = self.left_before(lab.stop, from.1, from.2, -lab.time);
                let until = |left: i32| if left == i32::MIN { NEVER } else { -left - 1 };
                Held { label: 0, rule, last: until(before[0].0), own: before[0].1, own_last: until(before[1].0) }
            } else {
                let first = self.first_leaving(to.0, to.1, to.2, lab.time, (lab.trip, lab.off as i32));
                if first > self.limit {
                    continue;
                }
                Held { label: 0, rule, last: first, own: (NONE, 0), own_last: first }
            };
            self.hold(round, to.0, lab.time, lab.cost, l, held);
        }
    }

    /// At a stop that vehicles wait to be changed to from: the most seconds such a change takes without the rule,
    /// in the direction searched. `NEVER` where the file has no walk for one of them.
    fn wait_reach(&self, stop: u32) -> Option<i32> {
        let has = |set: &Vec<u64>| set.get((stop >> 6) as usize).is_some_and(|w| w >> (stop & 63) & 1 == 1);
        if !self.rules || !has(if self.back { &self.ix.rule_to } else { &self.ix.rule_from }) {
            return None;
        }
        let all = &self.ix.wait_reach[self.back as usize];
        let &(_, secs, same) = all.get(all.partition_point(|e| e.0 < stop)).filter(|e| e.0 == stop)?;
        let own = if same { self.ix.change_at(stop, self.change, self.change_asked) } else { 0 };
        Some(if secs > MAX_TIME { NEVER } else { own.max(secs as i32) })
    }

    /// A ride that nobody boarded, held all the same. One who rides trip `e` of a route to a stop where vehicles
    /// wait could have taken any later trip of the route as well, and what waits for that one is another
    /// vehicle: the next of its line, where the one for `e` may be closed by a wish, cost more, or lead elsewhere.
    /// Whatever leaves `reach` seconds or more after `e` is in is reached from `e` without any rule, so only the
    /// trips in before that are looked at, each held for what waits for it and for nothing else.
    #[allow(clippy::too_many_arguments)]
    fn later_arrivals(&mut self, round: usize, r: u32, pos: u32, stop: u32, e: &Riding, costs: u32, reach: i32) {
        let came = self.alight_time(e.times, pos, e.off);
        let boarded = self.board_time(e.times, e.pos, e.off);
        let until = came.saturating_add(reach).min(self.limit.saturating_add(1));
        let own = cost(&self.prefs.trip_cost, e.trip);
        for off in self.off_lo..=self.off_hi {
            let run = self.run(r, off);
            // (the ranks rise with the time of alighting as with the time of boarding: no trip overtakes)
            let (mut lo, mut hi) = (0u32, run.trips.len() as u32);
            while lo < hi {
                let mid = lo + (hi - lo) / 2;
                if self.alight_time(self.trip_at(&run, mid).1, pos, off) < came { lo = mid + 1 } else { hi = mid }
            }
            self.steps += 1;
            for rank in lo..run.trips.len() as u32 {
                self.steps += 1;
                let (trip, times) = self.trip_at(&run, rank);
                let time = self.alight_time(times, pos, off);
                if time >= until || self.steps > self.max_steps {
                    break;
                }
                if (trip, off) == (e.trip, e.off) || self.board_time(times, e.pos, off) < boarded || bit(&self.prefs.trip_closed, trip) || !self.named(&self.ix.waiting[self.back as usize], trip, stop) {
                    continue;
                }
                let cost = e.cost.saturating_sub(own).saturating_add(cost(&self.prefs.trip_cost, trip)).saturating_add(costs).min(64 * MAX_PENALTY);
                if self.pointless(round, stop, time, cost) {
                    continue;
                }
                let seen = |list: &Vec<(u32, u32, i32, u32)>| list.iter().any(|o| (o.0, o.1, o.2) == (stop, trip, off) && (!self.soft || o.3 <= cost));
                let known = seen(&self.w.special_seen[round]) || seen(&self.w.later_seen[round]);
                self.steps += (self.w.special_seen[round].len() + self.w.later_seen[round].len()) as u64 / 8;
                if known {
                    continue;
                }
                if self.w.labels.len() >= self.max_labels || self.w.later_seen[round].len() >= 65_536 {
                    self.cut = true;
                    return;
                }
                self.w.later_seen[round].push((stop, trip, off, cost));
                let (from, to) = if self.back { (pos, e.pos) } else { (e.pos, pos) };
                self.w.labels.push(Label { time, cost, prev: e.from, next: NONE, stop, trip, from: from as u16, to: to as u16, off: off as i8, kind: RIDE });
                self.w.later.push(self.w.labels.len() as u32 - 1);
            }
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
    /// stop's bag, as `held` says (its label is made here): under the rules that bar, or for what one rule says
    /// waits.
    fn hold(&mut self, round: usize, stop: u32, time: i32, cost: u32, prev: u32, held: Held) {
        if bit(&self.prefs.closed, stop) || self.pointless(round, stop, time, cost) {
            return;
        }
        // under rules that only bar, one who is there no later and no dearer without rules can do all this one can
        if held.rule == NONE && self.beaten(true, round, stop, time, cost) {
            return;
        }
        if self.w.labels.len() >= self.max_labels || self.w.held[round].len() >= 65_536 {
            self.cut = true;
            return;
        }
        self.touch(stop);
        let label = self.w.labels.len() as u32;
        self.w.labels.push(Label { time, cost, prev, next: NONE, stop, trip: NONE, from: 0, to: 0, off: 0, kind: CHANGE });
        self.w.held[round].push(Held { label, ..held });
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
        // (every one of them, however many: whoever reads them counts them as steps)
        (bound(false), bound(true))
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
        self.named(&self.ix.bound[self.back as usize], trip, stop)
    }

    /// Whether a list of what rules are about has this trip at this stop: by itself, by its line, or by the stop.
    fn named(&self, b: &Bound, trip: u32, stop: u32) -> bool {
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
                if let Some(reach) = self.wait_reach(stop) {
                    for e in riding.iter() {
                        self.later_arrivals(round, r, pos, stop, e, here.saturating_add(at_stop), reach);
                    }
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
            if seen.iter().any(|o| (o.0, o.1, o.2) == (stop, e.trip, e.off) && (!self.soft || o.3 <= cost)) {
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
            if self.later {
                // one can leave at this search's moment for these: each is matched once in a search
                let (at, mut any) = (self.w.found.len() as u32, false);
                for a in self.anchors.iter_mut() {
                    if !a.done && end <= a.time && round as u32 <= a.rides && (!self.soft || cost <= a.cost) {
                        (a.done, a.hit, any) = (true, at, true);
                    }
                }
                if any {
                    self.w.found.push(Found { time: end, cost, rides: round as u32, label: index, secs });
                }
            } else if end <= self.limit && !self.beaten_at_end(round, end, cost) {
                let f = Found { time: end, cost, rides: round as u32, label: index, secs };
                let weighed = end as i64 + cost as i64;
                if self.weigh {
                    self.w.front.retain(|o| !(f.time <= o.time && f.rides <= o.rides && weighed <= o.time as i64 + o.cost as i64));
                } else {
                    self.w.front.retain(|o| !(f.time <= o.time && f.cost <= o.cost && f.rides <= o.rides));
                }
                self.w.front.push(f);
                for best in self.w.end_best[round.min(MAX_RIDES as usize)..].iter_mut() {
                    *best = (*best).min(end);
                }
                for best in self.w.weighed_best[round.min(MAX_RIDES as usize)..].iter_mut() {
                    *best = (*best).min(weighed);
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
        Some(Before { lo, hi, trip: ride.trip, line, time: ride.time })
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

    /// When the one vehicle that a rule holds for an arrival at `time` leaves `stop`: of the trips its far side
    /// speaks of (its trip; a trip of its line; any trip where it names neither) that take people on there, on
    /// every service day in reach, the first to leave at `time` or later; `NEVER` where there is none. `own`, the
    /// trip one came by on its day, is not counted: a vehicle does not wait for itself. The file alone is read:
    /// where a wish closes that vehicle none waits, and the next one is reached as without the rule. Forward only.
    fn first_leaving(&mut self, stop: u32, trip: u32, line: u32, time: i32, own: (u32, i32)) -> i32 {
        let mut first = NEVER;
        let (lo, hi) = self.tt.routes_at(stop);
        for e in lo..hi {
            self.steps += 1;
            let (r, pos) = self.tt.route_at(e);
            let route = self.tt.route(r);
            let named = trip != NONE;
            if !fits(line, route.line) || pos + 1 >= route.stops || self.tt.pattern_stop(route.first_stop + pos).pickup == 1 || named && (trip < route.first_trip || trip >= route.first_trip.saturating_add(route.trips)) {
                continue;
            }
            for off in self.off_lo..=self.off_hi {
                self.steps += 1;
                let leaves = |times: (u32, u32)| self.zero(off) + self.tt.time(times, pos).1 as i32;
                if named {
                    let at = leaves(self.tt.trip_times(trip));
                    if self.tt.runs(self.tt.trip(trip).service, self.day + off) && at >= time && (trip, off) != own {
                        first = first.min(at);
                    }
                    continue;
                }
                // (a day's trips of a route are in the order they leave)
                let run = self.run(r, off);
                let mut at = run.times.partition_point(|t| leaves(*t) < time);
                if run.trips.get(at).is_some_and(|t| (*t, off) == own) {
                    at += 1;
                }
                if let Some(times) = run.times.get(at) {
                    first = first.min(leaves(*times));
                }
            }
        }
        first
    }

    /// Searching back from a vehicle that leaves `stop` at `time` (real seconds): the two last before it that the
    /// same side of the rule speaks of, as the moment each leaves with its trip and day, the later first
    /// (`i32::MIN` where there is none). This one waits for whoever comes in after the first of them left; that
    /// vehicle itself, as an arrival, for as long as the second allows.
    fn left_before(&mut self, stop: u32, trip: u32, line: u32, time: i32) -> [(i32, (u32, i32)); 2] {
        let mut last = [(i32::MIN, (NONE, 0)); 2];
        let mut put = |new: (i32, (u32, i32))| {
            if new.0 > last[0].0 {
                last = [new, last[0]];
            } else if new.0 > last[1].0 {
                last[1] = new;
            }
        };
        let (lo, hi) = self.tt.routes_at(stop);
        for e in lo..hi {
            self.steps += 1;
            let (r, pos) = self.tt.route_at(e);
            let route = self.tt.route(r);
            let named = trip != NONE;
            if !fits(line, route.line) || pos + 1 >= route.stops || self.tt.pattern_stop(route.first_stop + pos).pickup == 1 || named && (trip < route.first_trip || trip >= route.first_trip.saturating_add(route.trips)) {
                continue;
            }
            for off in self.off_lo..=self.off_hi {
                self.steps += 1;
                let leaves = |times: (u32, u32)| self.zero(off) + self.tt.time(times, pos).1 as i32;
                if named {
                    let at = leaves(self.tt.trip_times(trip));
                    if self.tt.runs(self.tt.trip(trip).service, self.day + off) && at < time {
                        put((at, (trip, off)));
                    }
                    continue;
                }
                let run = self.run(r, off);
                let at = run.times.partition_point(|t| leaves(*t) < time);
                for i in at.saturating_sub(2)..at {
                    put((leaves(run.times[i]), (run.trips[i], off)));
                }
            }
        }
        last
    }

    /// Boards what a rule says waits for the ride before a held label: forward the one vehicle found when the
    /// label was held, back every arrival that this vehicle is the one for (the first of this route that can be
    /// caught, and where trips differ in cost each cheaper one after it, as in `board`). A trip a wish closes is not
    /// boarded, and nothing takes its place.
    fn board_waiting(&mut self, riding: &mut Vec<Riding>, r: u32, route: &Route, pos: u32, h: &Held, lab: &Label, cost_here: u32) {
        let rule = self.tt.rule(h.rule);
        let (_, to) = self.sides(&rule);
        let named = to.1 != NONE;
        if !fits(to.2, route.line) || named && (to.1 < route.first_trip || to.1 >= route.first_trip.saturating_add(route.trips)) {
            return;
        }
        // the ride one came off: never boarded again as what waits for itself
        let Some(ride) = self.w.labels.get(lab.prev as usize).map(|l| (l.trip, l.off as i32)) else { return };
        let base = lab.cost.saturating_add(cost(&self.prefs.stop_cost, lab.stop)).saturating_add(cost_here);
        let trip_costs = self.soft && self.prefs.trip_cost.is_some();
        let least = cost(&self.prefs.route_least, r);
        let until = h.last.max(h.own_last).min(self.limit);
        for off in self.off_lo..=self.off_hi {
            let last = |trip: u32| if (trip, off) == h.own { h.own_last } else { h.last }.min(until);
            if named {
                self.steps += 1;
                let times = self.tt.trip_times(to.1);
                let time = self.board_time(times, pos, off);
                if self.tt.runs(self.tt.trip(to.1).service, self.day + off) && time >= lab.time && time <= last(to.1) && (to.1, off) != ride && !bit(&self.prefs.trip_closed, to.1) {
                    let c = cost(&self.prefs.trip_cost, to.1);
                    self.join(riding, Riding { trip: to.1, times, off, cost: base.saturating_add(c), from: h.label, pos }, pos);
                }
                continue;
            }
            let run = self.run(r, off);
            if !self.in_reach(&run, off, lab.time) {
                continue;
            }
            let mut rank = self.first_rank(&run, pos, off, lab.time);
            let mut cheapest = u32::MAX;
            while (rank as usize) < run.trips.len() && self.steps <= self.max_steps {
                self.steps += 1;
                let (trip, times) = self.trip_at(&run, rank);
                rank += 1;
                let time = self.board_time(times, pos, off);
                // (a day's trips of a route are in the order they are boarded: past this, so is every one after)
                if time > until {
                    break;
                }
                if time > last(trip) || (trip, off) == ride || bit(&self.prefs.trip_closed, trip) {
                    continue;
                }
                let c = cost(&self.prefs.trip_cost, trip);
                if c < cheapest {
                    cheapest = c;
                    self.join(riding, Riding { trip, times, off, cost: base.saturating_add(c), from: h.label, pos }, pos);
                }
                if !trip_costs || c <= least {
                    break;
                }
            }
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
        let trip_costs = self.soft && self.prefs.trip_cost.is_some();
        if !held && !trip_costs && riding.iter().any(|e| (!self.soft || e.cost <= base) && self.board_time(e.times, pos, e.off) <= lab.time) {
            return;
        }
        let before = if held { self.before(lab, stop) } else { None };
        let least = cost(&self.prefs.route_least, r);
        // The earliest boarding so far; where trips differ in cost, the earliest at the least any trip of the route
        // costs. Whoever has that has nothing to gain from a later service day, none of whose trips leaves before
        // the day begins. Until then the days are read on: the bus that costs nothing may be tomorrow's.
        let mut best = NEVER;
        let step = if self.back { -1 } else { 1 };
        let mut off = if self.back { self.off_hi } else { self.off_lo };
        while off >= self.off_lo && off <= self.off_hi {
            if best <= self.earliest_of(off) {
                break;
            }
            // the first trip of this day that can be caught, and with costly trips each later one that is cheaper
            // than those before it, as far as the search goes
            let run = self.run(r, off);
            if !self.in_reach(&run, off, lab.time) {
                off += step;
                continue;
            }
            let mut rank = self.first_rank(&run, pos, off, lab.time);
            let mut cheapest = u32::MAX;
            // (a trip the rules bar is passed over for the next, and reading the rules for it is counted: a file
            // may bar every trip of a day, with any number of rules; and so is each costlier trip passed over for
            // a cheaper one after it)
            while (rank as usize) < run.trips.len() && self.steps <= self.max_steps {
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
                if let Some(b) = before {
                    self.steps += (b.hi - b.lo) as u64;
                    if self.barred(&b, trip, route.line, time) {
                        continue;
                    }
                }
                let c = cost(&self.prefs.trip_cost, trip);
                if c < cheapest {
                    cheapest = c;
                    if !trip_costs || c <= least {
                        best = best.min(time);
                    }
                    self.join(riding, Riding { trip, times, off, cost: base.saturating_add(c), from: l, pos }, pos);
                }
                // (no trip of this route costs less than that: nothing later is cheaper)
                if !trip_costs || c <= least {
                    break;
                }
            }
            off += step;
        }
    }

    /// Adds a trip to those ridden along the route, unless one ridden already is here no later and no dearer. Of
    /// more than the bag holds (two at least), the one whose time and cost together are worst goes.
    fn join(&mut self, riding: &mut Vec<Riding>, new: Riding, pos: u32) {
        let time = self.board_time(new.times, pos, new.off);
        let mut i = 0;
        while i < riding.len() {
            let e = &riding[i];
            let t = self.board_time(e.times, pos, e.off);
            if t <= time && (!self.soft || e.cost <= new.cost) {
                return;
            }
            if time <= t && (!self.soft || new.cost <= e.cost) {
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
                self.dropped = true;
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
                depart: self.zero(off) + self.tt.time(times, lab.from as u32).1 as i32,
                arrive: self.zero(off) + self.tt.time(times, lab.to as u32).0 as i32,
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
