//! What a search answers with: journeys as legs, each a walk or a ride, with real times.

use alloc::vec::Vec;

/// An end of a walk.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Spot {
    /// Where the journey was asked from.
    Origin,
    /// Where it was asked to.
    Destination,
    Stop(u32),
}

/// One leg. Times are seconds from the start of the query's service day; they may pass 86,400 or lie before 0.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Leg {
    Walk { from: Spot, to: Spot, depart: i32, arrive: i32 },
    Ride {
        trip: u32,
        route: u32,
        /// The trip's own service day, counted from the file's first day (the day before the query's for a trip
        /// that left before midnight).
        day: i32,
        /// Where along the route's pattern the rider boards and alights, and at which stops.
        board: u32,
        alight: u32,
        from: u32,
        to: u32,
        depart: i32,
        arrive: i32,
    },
}

impl Leg {
    pub fn depart(&self) -> i32 {
        match self {
            Leg::Walk { depart, .. } | Leg::Ride { depart, .. } => *depart,
        }
    }
    pub fn arrive(&self) -> i32 {
        match self {
            Leg::Walk { arrive, .. } | Leg::Ride { arrive, .. } => *arrive,
        }
    }
}

/// One way to make the journey.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Journey {
    /// When to leave the origin at the latest, and when it reaches the destination.
    pub depart: i32,
    pub arrive: i32,
    pub rides: u32,
    /// What it costs in wishes, in seconds; 0 when it follows them all.
    pub penalty: u32,
    /// Seconds on foot, the changes at one stop not counted.
    pub walking: u32,
    pub legs: Vec<Leg>,
}

impl Journey {
    /// Whether this one makes `other` pointless: it leaves no earlier, arrives no later, has no more rides and costs
    /// no more, and differs in one of them.
    pub fn beats(&self, other: &Journey) -> bool {
        let (a, b) = ((self.depart, self.arrive, self.rides, self.penalty), (other.depart, other.arrive, other.rides, other.penalty));
        a.0 >= b.0 && a.1 <= b.1 && a.2 <= b.2 && a.3 <= b.3 && a != b
    }
}
