//! The second opinion: a connection scan (Dibbelt, Pajor, Strasser and Wagner) over the same file, with the same
//! places, walks, change time and closed stops and lines, and nothing else in common with the search by rounds. It
//! answers two questions only (the earliest arrival, the latest departure) and holds a whole day's connections in
//! memory, so it is the tests' and the tool's, not the phone's.
//!
//! It knows no cost of wishes and no cap on rides: a query it is compared on sets `max_rides` high. It keeps to the
//! seconds the query follows ([`Query::followed`]): what ends past them is no answer to either. And it holds to the
//! feed's rules between trips where the query does, in its own way: where a rule speaks of the trip to be boarded, it
//! goes through the arrivals one by one, each with the trip it came by, where the search by rounds holds such an
//! arrival apart in its stop's bag.

use crate::NONE;
use crate::file::{Rule, Timetable};
use crate::journey::{Journey, Leg, Spot};
use crate::plan::Planner;
use crate::query::{Query, bit};
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
    /// A ride's trip and line.
    rides: Vec<(u32, u32)>,
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
}

fn named(r: &Rule) -> bool {
    r.from_trip != NONE || r.to_trip != NONE || r.from_line != NONE || r.to_line != NONE
}

fn fits(want: u32, is: u32) -> bool {
    want == NONE || want == is
}

impl Reference {
    /// The connections that leave at `earliest` or later and arrive by `latest`, in seconds of service day `day`.
    pub fn new(planner: &Planner, day: i32, earliest: i32, latest: i32) -> Reference {
        let tt = planner.timetable();
        let (lo, hi) = planner.offsets(earliest, latest);
        let (mut connections, mut rides) = (Vec::new(), Vec::new());
        for r in 0..tt.routes() {
            let route = tt.route(r);
            for trip in route.first_trip..route.first_trip + route.trips {
                let times = tt.trip_times(trip);
                for off in lo..=hi {
                    if !tt.runs(tt.trip(trip).service, day + off) {
                        continue;
                    }
                    let zero = planner.zero(day, off);
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
        let (mut arrivals_kept, mut departures_kept) = (vec![false; stops], vec![false; stops]);
        for &i in all.iter() {
            let r = tt.rule(i);
            for s in [r.from_stop, r.to_stop] {
                (arrivals_kept[s as usize], departures_kept[s as usize]) = (true, true);
            }
            for &(y, _) in planner.walks_into(r.to_stop) {
                arrivals_kept[y as usize] = true;
            }
            let (lo, hi) = tt.walks_from(r.from_stop);
            for w in lo..hi {
                if let Some(kept) = departures_kept.get_mut(tt.walk(w).0 as usize) {
                    *kept = true;
                }
            }
        }
        Reference { day, connections, by_arrival, rides, into, into_first, out, out_first, arrivals_kept, departures_kept }
    }

    /// The named rules for a change that leads to a stop, and for one that comes from it.
    fn rules_into(&self, stop: u32) -> &[u32] {
        &self.into[self.into_first[stop as usize] as usize..self.into_first[stop as usize + 1] as usize]
    }

    fn rules_out_of(&self, stop: u32) -> &[u32] {
        &self.out[self.out_first[stop as usize] as usize..self.out_first[stop as usize + 1] as usize]
    }

    /// What the feed's rules say of a change from one ride to another, each as (stop, trip, line): whether it is
    /// barred, whether the second vehicle waits, and the least seconds it needs.
    fn ruled(&self, tt: &Timetable, from: (u32, u32, u32), to: (u32, u32, u32)) -> (bool, bool, i32) {
        let (mut barred, mut waits, mut least) = (false, false, 0i32);
        for &i in self.rules_into(to.0) {
            let r = tt.rule(i);
            if r.from_stop != from.0 || !(fits(r.from_trip, from.1) && fits(r.from_line, from.2) && fits(r.to_trip, to.1) && fits(r.to_line, to.2)) {
                continue;
            }
            match r.kind {
                1 | 4 => waits = true,
                2 if r.min_time != NONE => least = least.max(r.min_time.min(1 << 20) as i32),
                3 => barred = true,
                _ => {}
            }
        }
        (barred, waits, least)
    }

    /// Whether a ride (trip, line) leaving `stop` at `depart` can be boarded, where a rule speaks of boarding it
    /// there: from the origin on foot, or from one of the arrivals kept, by what the rules say of that change. The
    /// rides of the journey so far, or `None`.
    #[allow(clippy::too_many_arguments)]
    fn boards(&self, planner: &Planner, q: &Query, origin: &[i32], arrivals: &[Vec<(i32, u32)>], took: &[u32], stop: u32, to: (u32, u32), depart: i32) -> Option<u32> {
        if origin[stop as usize] <= depart {
            return Some(0);
        }
        let tt = planner.timetable();
        // an arrival at `s`, with the seconds the change from there needs when no rule speaks (`None`: no way but a
        // vehicle that waits)
        let from_there = |s: u32, base: Option<i32>| -> Option<u32> {
            for &(t, x) in arrivals[s as usize].iter().filter(|a| a.0 <= depart) {
                let (trip, line) = self.rides[x as usize];
                let (barred, waits, least) = self.ruled(tt, (s, trip, line), (stop, to.0, to.1));
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
        let mut found = from_there(stop, Some(planner.change_at(q, stop)));
        for &(y, secs) in planner.walks_into(stop) {
            found = found.or_else(|| from_there(y, Some(secs as i32)));
        }
        // and from where the feed says this vehicle waits, though no walk leads here from there
        for &i in self.rules_into(stop) {
            let r = tt.rule(i);
            if found.is_none() && matches!(r.kind, 1 | 4) && r.from_stop != stop && !planner.walks_into(stop).iter().any(|w| w.0 == r.from_stop) {
                found = from_there(r.from_stop, None);
            }
        }
        found
    }

    /// The same turned round: whether a ride (trip, line) that arrives at `stop` at `arrive` leads on, where a rule
    /// speaks of changing from it there: to the destination on foot, or to one of the departures kept.
    #[allow(clippy::too_many_arguments)]
    fn leads_on(&self, planner: &Planner, q: &Query, destination: &[i32], departures: &[Vec<(i32, u32)>], stop: u32, from: (u32, u32), arrive: i32) -> bool {
        if destination[stop as usize] >= arrive {
            return true;
        }
        let tt = planner.timetable();
        let to_there = |s: u32, base: Option<i32>| -> bool {
            departures[s as usize].iter().filter(|d| d.0 >= arrive).any(|&(d, y)| {
                let (trip, line) = self.rides[y as usize];
                let (barred, waits, least) = self.ruled(tt, (stop, from.0, from.1), (s, trip, line));
                let needs = match (waits, base) {
                    (true, _) => 0,
                    (false, Some(base)) => base.max(least),
                    (false, None) => return false,
                };
                !barred && d >= arrive + needs
            })
        };
        if to_there(stop, Some(planner.change_at(q, stop))) {
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
        debug_assert_eq!(self.day, q.day);
        let tt = planner.timetable();
        let (ends, prefs) = (planner.ends(q), planner.prefs(q));
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
            if !bit(&prefs.closed, s) {
                ready[s as usize] = ready[s as usize].min(time + secs as i32);
                if ruled {
                    origin[s as usize] = ready[s as usize];
                }
            }
        }
        for &(s, secs) in ends.to.iter() {
            if !bit(&prefs.closed, s) {
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
                if !c.board || bit(&prefs.closed, c.from) || bit(&prefs.line_closed, line) || bit(&prefs.trip_closed, trip) {
                    continue;
                }
                // where a rule speaks of boarding this trip here, the arrivals one by one; else the stop's soonest
                let spoken_of = ruled && self.rules_into(c.from).iter().any(|i| fits(tt.rule(*i).to_trip, trip) && fits(tt.rule(*i).to_line, line));
                let so_far = if spoken_of {
                    self.boards(planner, q, &origin, &arrivals, &took, c.from, (trip, line), c.depart)
                } else {
                    (ready[c.from as usize] <= c.depart).then_some(via[c.from as usize])
                };
                let Some(so_far) = so_far else { continue };
                on[c.ride as usize] = true;
                took[c.ride as usize] = so_far + 1;
            }
            if bit(&prefs.closed_through, c.to) {
                on[c.ride as usize] = false;
                continue;
            }
            if !c.alight || bit(&prefs.closed, c.to) || c.arrive > until {
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
                sooner(c.to, c.arrive + planner.change_at(q, c.to));
                let (lo, hi) = tt.walks_from(c.to);
                for i in lo..hi {
                    let (y, secs) = tt.walk(i);
                    if !bit(&prefs.closed, y) {
                        sooner(y, c.arrive + secs as i32);
                    }
                }
            }
        }
        (best != i32::MAX).then_some((best, best_rides))
    }

    /// The latest departure from the query's origin that reaches its destination by `time`.
    pub fn latest_departure(&self, planner: &Planner, q: &Query, time: i32) -> Option<i32> {
        debug_assert_eq!(self.day, q.day);
        let tt = planner.timetable();
        let (ends, prefs) = (planner.ends(q), planner.prefs(q));
        let stops = tt.stops() as usize;
        // by when one must be at a stop, off a ride; when the latest useful ride leaves it; the walk from the origin
        let (mut due, mut left, mut first) = (vec![i32::MIN; stops], vec![i32::MIN; stops], vec![NONE; stops]);
        let mut on = vec![false; self.rides.len()];
        // holding to the feed's rules: the destination's stops apart, and the departures where a rule may tell them apart
        let ruled = q.rules && !self.out.is_empty();
        let mut destination = vec![i32::MIN; if ruled { stops } else { 0 }];
        let mut departures: Vec<Vec<(i32, u32)>> = vec![Vec::new(); if ruled { stops } else { 0 }];
        for &(s, secs) in ends.to.iter() {
            if !bit(&prefs.closed, s) {
                due[s as usize] = due[s as usize].max(time - secs as i32);
                if ruled {
                    destination[s as usize] = due[s as usize];
                }
            }
        }
        for &(s, secs) in ends.from.iter() {
            if !bit(&prefs.closed, s) {
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
                if !c.alight || bit(&prefs.closed, c.to) || bit(&prefs.line_closed, line) || bit(&prefs.trip_closed, trip) {
                    continue;
                }
                let spoken_of = ruled && self.rules_out_of(c.to).iter().any(|i| fits(tt.rule(*i).from_trip, trip) && fits(tt.rule(*i).from_line, line));
                let leads_on = if spoken_of { self.leads_on(planner, q, &destination, &departures, c.to, (trip, line), c.arrive) } else { due[c.to as usize] >= c.arrive };
                if !leads_on {
                    continue;
                }
                on[c.ride as usize] = true;
            }
            if bit(&prefs.closed_through, c.from) {
                on[c.ride as usize] = false;
                continue;
            }
            if !c.board || bit(&prefs.closed, c.from) || c.depart < since {
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
                due[c.from as usize] = due[c.from as usize].max(c.depart - planner.change_at(q, c.from));
                for &(y, secs) in planner.walks_into(c.from) {
                    if !bit(&prefs.closed, y) {
                        due[y as usize] = due[y as usize].max(c.depart - secs as i32);
                    }
                }
            }
        }
        (best != i32::MIN).then_some(best)
    }

    /// Every best pair of departure and arrival for leaving inside the query's window: no other leaves later and
    /// arrives no later. By scanning forward for the earliest arrival, back for the latest departure that still
    /// makes it, and on from a second after that.
    pub fn options(&self, planner: &Planner, q: &Query) -> Vec<(i32, i32)> {
        let mut out = Vec::new();
        let mut time = q.time as i32;
        let end = time + q.window as i32;
        while time <= end && out.len() < 4096 {
            let Some(arrive) = self.earliest_arrival(planner, q, time) else { break };
            let Some(depart) = self.latest_departure(planner, q, arrive) else { break };
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
    /// fewer rides than the later one).
    pub fn window_differs(&self, planner: &Planner, q: &Query, journeys: &[Journey]) -> Option<String> {
        let (ours, theirs) = (pairs(journeys), self.options(planner, q));
        let end = q.time as i32 + q.window as i32;
        if let Some(missing) = theirs.iter().find(|p| !ours.contains(p)) {
            return Some(format!("the scan has leaving {} arriving {}, the planner has not ({} pairs against {})", missing.0, missing.1, ours.len(), theirs.len()));
        }
        for p in ours.iter().filter(|p| !theirs.contains(p)) {
            // A further pair may arrive later than the scan's best from then: the better journey then leaves after
            // the window, or the planner would have had the scan's pair for it and dropped this one.
            let best = self.earliest_arrival(planner, q, p.0);
            if best.is_none_or(|a| a > p.1) {
                return Some(format!("the planner has leaving {} arriving {}, which the scan says can't be done: {:?}", p.0, p.1, best));
            }
            if best == Some(p.1) && self.latest_departure(planner, q, p.1).is_none_or(|d| d <= end) {
                return Some(format!("the planner has leaving {} arriving {}, which the scan should list and does not", p.0, p.1));
            }
        }
        None
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
/// needs no time), and the ends are the query's with their seconds on foot. What is wrong, in words.
///
/// It reads the file and the query's two places, and nothing of how the journey was found.
pub fn impossible(planner: &Planner, q: &Query, j: &Journey) -> Option<String> {
    let tt: &Timetable = planner.timetable();
    let ends = planner.ends(q);
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
    // what the feed's rules say of a change between two rides: (barred, the second waits, the least seconds)
    let ruled = |from: (u32, u32, u32), to: (u32, u32, u32)| {
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
                1 | 4 => waits = true,
                2 if r.min_time != NONE => least = least.max(r.min_time.min(1 << 20) as i32),
                3 => barred = true,
                _ => {}
            }
        }
        (barred, waits, least)
    };
    let ride_of = |l: &Leg, end: bool| match *l {
        Leg::Ride { trip, route, from, to, .. } => Some((if end { to } else { from }, trip, tt.route(route).line)),
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
                let base = planner.zero(q.day, day.saturating_sub(q.day));
                if (a.stop, b.stop) != (from, to) || depart != base + tt.time(times, board).1 as i32 || arrive != base + tt.time(times, alight).0 as i32 {
                    return Some(format!("leg {i}: its stops or times are not the trip's"));
                }
                if a.pickup == 1 || b.drop_off == 1 {
                    return Some(format!("leg {i}: no boarding or no alighting there"));
                }
                // the change from the ride before
                let before = j.legs[..i].iter().rev().find(|l| matches!(l, Leg::Ride { .. }));
                if let Some((before, (stop, at))) = before.and_then(|l| Some((ride_of(l, true)?, (ride_of(l, true)?.0, l.arrive())))) {
                    let (barred, waits, least) = ruled(before, (from, trip, r.line));
                    if barred {
                        return Some(format!("leg {i}: the feed says no change is possible from the ride before"));
                    }
                    let needs = if waits { 0 } else if stop == from { planner.change_at(q, stop).max(least) } else { walk_between(stop, from).unwrap_or(i32::MAX / 2).max(least) };
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
                    (Spot::Origin, Spot::Destination) => Some(secs),
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
    None
}
