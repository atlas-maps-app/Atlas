//! What a journey is asked with: two places, a moment, and wishes.
//!
//! A **wish** names something (a stop, a station, an area on the map, a line, an operator, a kind of vehicle, a kind
//! of train, one trip, what a wheelchair or a bicycle can't use) and says how strongly: never, avoid, prefer.
//! - *Never* is a filter: such a ride is not taken, such a stop not used.
//! - *Avoid* costs its seconds each time: a ride on such a line, a boarding or an alighting at such a stop.
//! - *Prefer* costs its seconds for what is **not** it: a ride on any other line, a boarding anywhere else. So a
//!   cost never goes below zero, and a search may drop what is later and dearer.
//!
//! The cost is counted beside the arrival, never added to it: an answer says when a journey arrives and what it
//! cost in wishes.
//!
//! **What a wish is worth.** Its seconds say it: "avoid rail, fifteen minutes" makes a ride by rail as bad as
//! arriving fifteen minutes later. So an option that follows the wishes better than a faster one stays only if it
//! is later by less than that is worth. An option is beaten by another that leaves no earlier, arrives no later,
//! has no more rides, and is no later when each one's cost is added to its arrival ([`Query::weigh`], the default;
//! asked to arrive by, the cost is taken off the departure). The fastest option always stays; the one that follows
//! the wishes best stays when it is worth its time. Without weighing, cost is a fourth thing to be best in and
//! every option stays that no other beats in all four: on a real timetable half as many again, most of them a
//! slower way round to save a part of a wish.

use crate::file::{Timetable, TripText};
pub use crate::geo::Area;
use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;

/// Where a journey starts or ends.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Place {
    /// A stop, and the stops a walk away from it.
    Stop(u32),
    /// A station: any of its stops, and the stops a walk away from them.
    Station(u32),
    /// A point on the map, in 1e-7 degree: the stops within [`Query::reach_m`], by the straight line.
    Point { lat: i32, lon: i32 },
    /// Stops and the seconds on foot to (or from) each, from whoever knows the streets.
    Walks(Vec<(u32, u32)>),
}

/// A kind of vehicle, from a line's GTFS `route_type` (the extended types too).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Tram,
    Metro,
    Rail,
    Bus,
    Ferry,
    CableTram,
    Aerial,
    Funicular,
    Trolleybus,
    Monorail,
    Coach,
    Air,
    Taxi,
    Other,
}

impl Mode {
    pub fn of(route_type: u32) -> Mode {
        match route_type {
            0 | 900..=999 => Mode::Tram,
            1 | 400..=404 => Mode::Metro,
            405 | 12 => Mode::Monorail,
            2 | 100..=199 => Mode::Rail,
            3 | 700..=799 => Mode::Bus,
            200..=299 => Mode::Coach,
            4 | 1000..=1099 | 1200..=1299 => Mode::Ferry,
            5 => Mode::CableTram,
            6 | 1300..=1399 => Mode::Aerial,
            7 | 1400..=1499 => Mode::Funicular,
            11 | 800..=899 => Mode::Trolleybus,
            1100..=1199 => Mode::Air,
            1500..=1599 => Mode::Taxi,
            _ => Mode::Other,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Mode::Tram => "tram",
            Mode::Metro => "metro",
            Mode::Rail => "rail",
            Mode::Bus => "bus",
            Mode::Ferry => "ferry",
            Mode::CableTram => "cable tram",
            Mode::Aerial => "aerial lift",
            Mode::Funicular => "funicular",
            Mode::Trolleybus => "trolleybus",
            Mode::Monorail => "monorail",
            Mode::Coach => "coach",
            Mode::Air => "air",
            Mode::Taxi => "taxi",
            Mode::Other => "other",
        }
    }

    pub fn parse(s: &str) -> Option<Mode> {
        const ALL: [Mode; 14] = [
            Mode::Tram,
            Mode::Metro,
            Mode::Rail,
            Mode::Bus,
            Mode::Ferry,
            Mode::CableTram,
            Mode::Aerial,
            Mode::Funicular,
            Mode::Trolleybus,
            Mode::Monorail,
            Mode::Coach,
            Mode::Air,
            Mode::Taxi,
            Mode::Other,
        ];
        let s = if s.eq_ignore_ascii_case("train") { "rail" } else { s };
        ALL.into_iter().find(|m| m.name().eq_ignore_ascii_case(s))
    }
}

/// What a wish is about.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum What {
    /// One stop. With `through`, riding past it counts too; without, only boarding, alighting and changing there.
    Stop { stop: u32, through: bool },
    /// Every stop of a station.
    Station { station: u32, through: bool },
    /// Every stop inside an area: a circle, a box, or an outline from any query.
    Area { area: Area, through: bool },
    /// A line (a GTFS route).
    Line(u32),
    /// Every line of an operator.
    Operator(u32),
    /// Every line of a kind of vehicle.
    Mode(Mode),
    /// Every trip of a kind, by its `trip_long_name` whatever the case: "Intercity", "Sprinter".
    Kind(String),
    /// One trip: a specific train.
    Trip(u32),
    /// Trips a wheelchair can't board and stops it can't use (the feed says no).
    NoWheelchair,
    /// Trips and stops of which the feed doesn't say whether a wheelchair can use them.
    WheelchairUnknown,
    /// Trips that take no bicycles (the feed says no).
    NoBicycles,
    /// Trips of which the feed doesn't say whether they take bicycles.
    BicyclesUnknown,
}

/// How strongly.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum How {
    /// Not at all.
    Never,
    /// Each use costs this many seconds.
    Avoid(u32),
    /// Each use of anything else costs this many seconds.
    Prefer(u32),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Wish {
    pub what: What,
    pub how: How,
}

/// The caps that bound a search's work. A search that meets one returns what it has, marked as cut short.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Limits {
    /// Labels (a moment at a stop, with how one got there) a search may make.
    pub labels: u32,
    /// Steps (a stop along a route, a walk, a trip looked at) a search may take.
    pub steps: u64,
    /// Departures inside the window that each get a search of their own; and, asked for one moment, the later
    /// moments tried to see whether a journey can leave later.
    pub departures: u32,
    /// Labels kept a stop a round when wishes cost something (without, it is one).
    pub bag: u32,
    /// Journeys returned. Of more: over a window those nearest the asked moment; asked for one moment the fastest
    /// (the earliest arrivals; arriving by, the latest departures).
    pub options: u32,
    /// How long after the asked moment a journey may still be under way, in seconds.
    pub horizon: u32,
    /// Whether the search first follows journeys only as far as one could sensibly take: twice the least time any
    /// timetable could give it, and half an hour (an hour at least), past the asked moment and its window. It
    /// makes a search many times shorter, and leaves nothing out: what could still be an option past that is
    /// followed as far as the horizon all the same (the moments for which nothing was found by then, and journeys
    /// with fewer rides than the first found). With wishes that cost, weighed, the first pass goes on as far as an
    /// option could still be worth its time (the latest end, with its cost added, of the fastest journeys). With
    /// wishes that cost and every option asked for ([`Query::weigh`] off) a search is never patient: the option
    /// that follows them best may be the slowest.
    pub patient: bool,
}

impl Default for Limits {
    fn default() -> Limits {
        Limits { labels: 2_000_000, steps: 100_000_000, departures: 2048, bag: 4, options: 64, horizon: 86_400, patient: true }
    }
}

/// A journey asked for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Query {
    pub from: Place,
    pub to: Place,
    /// The service day, counted from the file's first day.
    pub day: i32,
    /// Seconds of that service day: the earliest departure, or with `arrive_by` the latest arrival.
    pub time: u32,
    /// How far the moment may move, in seconds: later for a departure, earlier for an arrival. Every best journey
    /// inside it is an option, its last moment included: a journey as it runs, which no other beats that leaves no
    /// earlier, inside the window or after it. So a window from the second after this one's last moment goes on
    /// where this one ends, and the two give what one window over both gives. 0 asks for the one moment.
    /// (Between two points near enough to walk all the way, the walk is one option, given from the asked moment;
    /// it can begin at any moment, so a journey with a ride that takes no less time than the walk is none.)
    pub window: u32,
    pub arrive_by: bool,
    /// The most rides a journey may have (1 to 16).
    pub max_rides: u32,
    pub wishes: Vec<Wish>,
    /// For a [`Place::Point`]: how far a stop may be, in metres by the straight line.
    pub reach_m: u32,
    /// For a [`Place::Point`]: walking speed in mm/s, over the straight line taken 30 % longer.
    pub walk_mm_s: u32,
    /// What a change at one stop costs, in seconds; `None` takes the file's: the stop's own where the feed gives
    /// it one, else the file's for every stop. Asked for here, it holds at every stop, but never less than the
    /// stop's own.
    pub change_s: Option<u32>,
    /// Whether the feed's transfer rules between trips hold (a change that is not possible, a vehicle that waits).
    pub rules: bool,
    /// Whether a wish's seconds are what following it is worth: an option slower than another stays only if it
    /// saves at least as much in wishes as it loses in time (this module's head). `false` keeps every option that
    /// no other beats in departure, arrival, rides and cost each.
    pub weigh: bool,
    pub limits: Limits,
}

impl Query {
    /// A departure from `from` to `to` on `day` at `time`, with nothing wished.
    pub fn new(from: Place, to: Place, day: i32, time: u32) -> Query {
        Query {
            from,
            to,
            day,
            time,
            window: 0,
            arrive_by: false,
            max_rides: 8,
            wishes: Vec::new(),
            reach_m: 1000,
            walk_mm_s: 1250,
            change_s: None,
            rules: true,
            weigh: true,
            limits: Limits::default(),
        }
    }
}

impl Query {
    /// The real seconds a search follows: nothing before the first, nothing after the second. From the asked
    /// moment over the window and the horizon, in the direction asked.
    pub fn followed(&self) -> (i32, i32) {
        let time = self.time.min(MAX_QUERY_TIME) as i32;
        let span = self.window.min(MAX_WINDOW) as i32 + self.limits.horizon.clamp(3600, 2 * 86_400) as i32;
        if self.arrive_by { (time - span, time) } else { (time, time + span) }
    }
}

pub(crate) const MAX_RIDES: u32 = 16;
pub(crate) const MAX_WINDOW: u32 = 86_400;
pub(crate) const MAX_PENALTY: u32 = 86_400;
pub(crate) const MAX_QUERY_TIME: u32 = 3 * 86_400;

/// A query's wishes worked out against a file: what is closed, and what costs how much.
pub(crate) struct Prefs {
    /// A bit a stop: nobody boards, alights or changes here.
    pub closed: Option<Vec<u64>>,
    /// A bit a stop: no ride goes past here either.
    pub closed_through: Option<Vec<u64>>,
    /// Seconds a stop: what boarding or alighting here costs; what calling here costs.
    pub stop_cost: Option<Vec<u32>>,
    pub pass_cost: Option<Vec<u32>>,
    /// A bit a line: never ridden. Seconds a line: what a ride costs.
    pub line_closed: Option<Vec<u64>>,
    pub line_cost: Option<Vec<u32>>,
    /// A bit a trip: never ridden. Seconds a trip: what a ride costs on top of its line's.
    pub trip_closed: Option<Vec<u64>>,
    pub trip_cost: Option<Vec<u32>>,
}

#[inline]
pub(crate) fn bit(set: &Option<Vec<u64>>, i: u32) -> bool {
    match set {
        Some(v) => v.get((i >> 6) as usize).is_some_and(|w| w >> (i & 63) & 1 == 1),
        None => false,
    }
}

#[inline]
pub(crate) fn cost(costs: &Option<Vec<u32>>, i: u32) -> u32 {
    match costs {
        Some(v) => v.get(i as usize).copied().unwrap_or(0),
        None => 0,
    }
}

fn set_bit(set: &mut Option<Vec<u64>>, n: u32, i: u32) {
    let v = set.get_or_insert_with(|| vec![0; (n as usize).div_ceil(64)]);
    if let Some(w) = v.get_mut((i >> 6) as usize) {
        *w |= 1 << (i & 63);
    }
}

fn add_cost(costs: &mut Option<Vec<u32>>, n: u32, i: u32, secs: u32) {
    let v = costs.get_or_insert_with(|| vec![0; n as usize]);
    if let Some(c) = v.get_mut(i as usize) {
        *c = c.saturating_add(secs);
    }
}

impl Prefs {
    /// Whether any wish costs seconds (then a stop keeps more than one label a round).
    pub fn soft(&self) -> bool {
        self.stop_cost.is_some() || self.pass_cost.is_some() || self.line_cost.is_some() || self.trip_cost.is_some()
    }

    pub fn of(tt: &Timetable, wishes: &[Wish], by_lat: &[u32]) -> Prefs {
        let mut p = Prefs {
            closed: None,
            closed_through: None,
            stop_cost: None,
            pass_cost: None,
            line_closed: None,
            line_cost: None,
            trip_closed: None,
            trip_cost: None,
        };
        let (stops, lines, trips) = (tt.stops(), tt.lines(), tt.trips());
        // at most 64 wishes are read; more would be a caller's mistake, and each costs a pass over what it names
        for wish in wishes.iter().take(64) {
            match &wish.what {
                What::Stop { stop, through } => p.stops(tt, wish.how, *through, |s| s == *stop, None),
                What::Station { station, through } => p.stops(tt, wish.how, *through, |s| s == *station || tt.stop(s).parent == *station, None),
                What::Area { area, through } => {
                    let inside = |s: u32| {
                        let st = tt.stop(s);
                        st.place != 2 && area.contains(st.lat, st.lon)
                    };
                    p.stops(tt, wish.how, *through, inside, Some((area.latitudes(), by_lat)))
                }
                What::Line(line) => p.lines(lines, wish.how, |l| l == *line),
                What::Operator(op) => p.lines(lines, wish.how, |l| tt.line_operator(l) == *op),
                What::Mode(mode) => p.lines(lines, wish.how, |l| Mode::of(tt.line_type(l)) == *mode),
                What::Kind(kind) => p.trips(trips, wish.how, |t| tt.trip_text(t, TripText::Kind).eq_ignore_ascii_case(kind)),
                What::Trip(trip) => p.trips(trips, wish.how, |t| t == *trip),
                What::NoWheelchair | What::WheelchairUnknown => {
                    let want = if wish.what == What::NoWheelchair { 2 } else { 0 };
                    p.trips(trips, wish.how, |t| tt.trip(t).wheelchair == want);
                    // a stop that doesn't say takes its station's word
                    let at = |s: u32| {
                        let st = tt.stop(s);
                        let w = if st.wheelchair == 0 && st.parent != crate::NONE { tt.stop(st.parent).wheelchair } else { st.wheelchair };
                        w == want
                    };
                    p.stops(tt, wish.how, false, at, None);
                }
                What::NoBicycles => p.trips(trips, wish.how, |t| tt.trip(t).bicycles == 2),
                What::BicyclesUnknown => p.trips(trips, wish.how, |t| tt.trip(t).bicycles == 0),
            }
        }
        let _ = stops;
        p
    }

    fn stops(&mut self, tt: &Timetable, how: How, through: bool, is: impl Fn(u32) -> bool, within: Option<((i32, i32), &[u32])>) {
        let n = tt.stops();
        match how {
            How::Never | How::Avoid(_) => {
                let mut each = |s: u32| match how {
                    How::Never => {
                        set_bit(&mut self.closed, n, s);
                        if through {
                            set_bit(&mut self.closed_through, n, s);
                        }
                    }
                    How::Avoid(secs) => {
                        let secs = secs.min(MAX_PENALTY);
                        if through {
                            add_cost(&mut self.pass_cost, n, s, secs);
                        } else {
                            add_cost(&mut self.stop_cost, n, s, secs);
                        }
                    }
                    How::Prefer(_) => {}
                };
                match within {
                    // an area: only the stops between its latitudes are looked at
                    Some(((south, north), by_lat)) => {
                        let from = by_lat.partition_point(|s| tt.stop(*s).lat < south);
                        for &s in by_lat[from..].iter().take_while(|s| tt.stop(**s).lat <= north) {
                            if is(s) {
                                each(s);
                            }
                        }
                    }
                    None => (0..n).filter(|s| is(*s)).for_each(each),
                }
            }
            How::Prefer(secs) => {
                let secs = secs.min(MAX_PENALTY);
                let costs = if through { &mut self.pass_cost } else { &mut self.stop_cost };
                for s in 0..n {
                    if !is(s) {
                        add_cost(costs, n, s, secs);
                    }
                }
            }
        }
    }

    fn lines(&mut self, n: u32, how: How, is: impl Fn(u32) -> bool) {
        for l in 0..n {
            match how {
                How::Never if is(l) => set_bit(&mut self.line_closed, n, l),
                How::Avoid(secs) if is(l) => add_cost(&mut self.line_cost, n, l, secs.min(MAX_PENALTY)),
                How::Prefer(secs) if !is(l) => add_cost(&mut self.line_cost, n, l, secs.min(MAX_PENALTY)),
                _ => {}
            }
        }
    }

    fn trips(&mut self, n: u32, how: How, is: impl Fn(u32) -> bool) {
        for t in 0..n {
            match how {
                How::Never if is(t) => set_bit(&mut self.trip_closed, n, t),
                How::Avoid(secs) if is(t) => add_cost(&mut self.trip_cost, n, t, secs.min(MAX_PENALTY)),
                How::Prefer(secs) if !is(t) => add_cost(&mut self.trip_cost, n, t, secs.min(MAX_PENALTY)),
                _ => {}
            }
        }
    }
}
