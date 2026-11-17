//! Atlas's public transport planner. It reads one country's timetable file (../FORMAT.md, made by
//! ../pack/ttpack.py) in place and finds journeys in it by rounds, one ride more a round (RAPTOR): the best by
//! arrival, by number of rides and by what the asker wishes to avoid or prefer, for one departure, over a window of
//! departures, or to arrive by a time.
//!
//! What it holds to, as a second Rust part beside the core's:
//! - **No crates.** The standard library only, and the library itself is core and alloc alone.
//! - **No panic reachable from data.** A file is checked when opened ([`Timetable::open`]) and read through
//!   accessors that give a harmless value for anything out of range; a query is clamped ([`Query`]). The fuzzing
//!   driver ([`fuzz`]) holds both to it.
//! - **Bounded work.** Rounds, window, options and labels all have a cap ([`Limits`]); a search that meets one
//!   returns what it has and says so.
//! - **The same answer everywhere.** Integers only: no float, no hash map, no clock, no thread.
//!
//! It knows no timezone: times are seconds of a service day, as the file has them (FORMAT.md, "Time").

#![cfg_attr(not(any(test, feature = "std")), no_std)]
#![forbid(unsafe_code)]

extern crate alloc;

pub mod file;
pub mod fuzz;
pub mod geo;
pub mod journey;
pub mod plan;
pub mod query;
pub mod reference;

pub use file::{OpenError, Timetable};
pub use journey::{Journey, Leg, Spot};
pub use plan::{Answer, Planner};
pub use query::{Area, How, Limits, Mode, Place, Query, What, Wish};

/// "Nothing" where the file or the planner names a stop, a line or a trip.
pub const NONE: u32 = u32::MAX;
