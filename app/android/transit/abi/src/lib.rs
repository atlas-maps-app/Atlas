//! Atlas's public transport planner behind a C interface: include/atlas_transit.h is its other side, and says what
//! each function takes and gives. An app's shared library links this as a static library (libatlastransit.so, beside
//! the core's), and its Kotlin side speaks to it in numbers alone: a query is an array of 32-bit numbers, an answer
//! is one, and a name is the file's own bytes. No structure crosses, so no layout can differ between the two sides.
//!
//! What it holds to:
//! - **Nothing a caller passes can do harm.** A null pointer is an empty array or no timetable; a number out of
//!   range names nothing; a query that cannot be read (a kind this library does not know, a wish cut off, an
//!   amount below zero) is refused whole and answers nothing, rather than be planned without the part that was
//!   not understood. What the caller owes is in the header, and is what C always owes: an array as long as it
//!   says, the file's bytes left where they are until the timetable is closed.
//! - **Every line of unsafe code is here,** each with its reason. The planner forbids it.
//! - **No panic reachable from data,** as in the planner; here a panic aborts, as the core's asserts do.
//!
//! One timetable may be read from any thread at any time (its numbers and names: the file is never written). A
//! plan uses the planner's working memory and leaves its answer with the timetable: one plan at a time, and the
//! answer read before the next.

#![deny(unsafe_op_in_unsafe_fn)]
// The core and alloc alone, so that none of the standard library's panic and backtrace machinery is linked into the
// app; the tests have the standard library.
#![cfg_attr(not(test), no_std)]

extern crate alloc;

use alloc::boxed::Box;
use alloc::string::String;
use alloc::vec::Vec;
use atlas_transit::file::{LineText, OperatorText, StopText, TripText};
use atlas_transit::{Answer, Area, How, Leg, Limits, Mode, OpenError, Place, Planner, Query, Spot, Timetable, What, Wish};

#[cfg(test)]
mod tests;

#[cfg(not(test))]
mod runtime {
    use core::alloc::{GlobalAlloc, Layout};
    use core::ffi::c_void;

    unsafe extern "C" {
        fn malloc(size: usize) -> *mut c_void;
        fn free(p: *mut c_void);
        fn realloc(p: *mut c_void, size: usize) -> *mut c_void;
        fn posix_memalign(out: *mut *mut c_void, align: usize, size: usize) -> i32;
        fn write(fd: i32, buf: *const u8, n: usize) -> isize;
        fn abort() -> !;
    }

    /// The C library's allocator; posix_memalign where an alignment is past malloc's 16 bytes.
    struct Malloc;

    // SAFETY: the C library's allocator gives memory of the size and alignment asked for, or null
    unsafe impl GlobalAlloc for Malloc {
        unsafe fn alloc(&self, l: Layout) -> *mut u8 {
            if l.align() <= 16 {
                // SAFETY: the C library's malloc
                return unsafe { malloc(l.size()) }.cast();
            }
            let mut p = core::ptr::null_mut();
            // SAFETY: an alignment of a power of two past 16, a multiple of the pointer's size
            if unsafe { posix_memalign(&mut p, l.align(), l.size()) } == 0 { p.cast() } else { core::ptr::null_mut() }
        }
        unsafe fn dealloc(&self, p: *mut u8, _: Layout) {
            // SAFETY: p came from alloc
            unsafe { free(p.cast()) }
        }
        unsafe fn realloc(&self, p: *mut u8, l: Layout, size: usize) -> *mut u8 {
            if l.align() <= 16 {
                // SAFETY: p came from malloc
                return unsafe { realloc(p.cast(), size) }.cast();
            }
            // SAFETY: the layout of the new size, its alignment the old one's
            let q = unsafe { self.alloc(Layout::from_size_align_unchecked(size, l.align())) };
            if !q.is_null() {
                // SAFETY: both hold at least the lesser size, and do not overlap
                unsafe { core::ptr::copy_nonoverlapping(p, q, l.size().min(size)) };
                // SAFETY: p came from alloc
                unsafe { self.dealloc(p, l) };
            }
            q
        }
    }

    #[global_allocator]
    static ALLOCATOR: Malloc = Malloc;

    /// The unwinder's personality the prebuilt core and alloc name (they were built to unwind); never called, as a
    /// panic aborts.
    #[unsafe(no_mangle)]
    extern "C" fn rust_eh_personality() {}

    /// Writes `bytes` to the standard error.
    fn say(bytes: &[u8]) {
        // SAFETY: a buffer of its length
        let _ = unsafe { write(2, bytes.as_ptr(), bytes.len()) };
    }

    #[panic_handler]
    fn panic(info: &core::panic::PanicInfo) -> ! {
        // where, without the formatting machinery: `atlas_transit: panic at <file>:<line>`
        say(b"atlas_transit: panic at ");
        if let Some(at) = info.location() {
            say(at.file().as_bytes());
            let mut digits = [0u8; 10];
            let mut n = at.line();
            let mut k = digits.len();
            loop {
                k -= 1;
                digits[k] = b'0' + (n % 10) as u8;
                n /= 10;
                if n == 0 || k == 0 {
                    break;
                }
            }
            say(b":");
            say(&digits[k..]);
        }
        say(b"\n");
        // SAFETY: the C library's abort
        unsafe { abort() }
    }
}

// Every number the two sides share. The header has each as ATLAS_TRANSIT_<name>, and a test holds the two lists
// equal ([`NAMES`]).

/// The interface's version: a number that changes with any change to what crosses it.
pub const ABI: u32 = 2;
/// Nothing: no stop, no station, no colour.
pub const NONE: i32 = -1;
/// No day: not a date.
pub const NO_DAY: i32 = i32::MIN;

// Why a file was not opened.
pub const OPENED: i32 = 0;
pub const NOT_A_TIMETABLE: i32 = 1;
pub const BAD_VERSION: i32 = 2;
pub const BAD_SECTION: i32 = 3;
pub const BAD_RECORD: i32 = 4;
pub const BAD_DAYS: i32 = 5;
pub const INDEX_TOO_LARGE: i32 = 6;

// A timetable's numbers.
pub const N_FIRST_DAY: u32 = 0;
pub const N_DAYS: u32 = 1;
pub const N_STOPS: u32 = 2;
pub const N_LINES: u32 = 3;
pub const N_OPERATORS: u32 = 4;
pub const N_ROUTES: u32 = 5;
pub const N_TRIPS: u32 = 6;
pub const N_CHANGE_SECONDS: u32 = 7;
pub const N_DAY_OF: u32 = 8;
pub const N_DATE_OF: u32 = 9;
pub const N_STOP_LAT: u32 = 16;
pub const N_STOP_LON: u32 = 17;
pub const N_STOP_STATION: u32 = 18;
pub const N_STOP_KIND: u32 = 19;
pub const N_STOP_WHEELCHAIR: u32 = 20;
pub const N_STOP_PLACE: u32 = 21;
pub const N_LINE_OPERATOR: u32 = 32;
pub const N_LINE_TYPE: u32 = 33;
pub const N_LINE_COLOUR: u32 = 34;
pub const N_LINE_TEXT_COLOUR: u32 = 35;
pub const N_ROUTE_LINE: u32 = 48;
pub const N_ROUTE_STOPS: u32 = 49;
pub const N_ROUTE_STOP: u32 = 50;
pub const N_ROUTE_DIRECTION: u32 = 51;
pub const N_TRIP_ROUTE: u32 = 64;
pub const N_TRIP_WHEELCHAIR: u32 = 65;
pub const N_TRIP_BICYCLES: u32 = 66;
pub const N_TRIP_ARRIVES: u32 = 67;
pub const N_TRIP_LEAVES: u32 = 68;

// A timetable's names.
pub const T_STOP_ID: u32 = 0;
pub const T_STOP_CODE: u32 = 1;
pub const T_STOP_NAME: u32 = 2;
pub const T_STOP_PLATFORM: u32 = 3;
pub const T_STOP_ZONE: u32 = 4;
pub const T_STOP_TIMEZONE: u32 = 5;
pub const T_STOP_DESCRIPTION: u32 = 6;
pub const T_STOP_IFOPT: u32 = 7;
pub const T_LINE_ID: u32 = 16;
pub const T_LINE_SHORT: u32 = 17;
pub const T_LINE_LONG: u32 = 18;
pub const T_LINE_DESCRIPTION: u32 = 19;
pub const T_LINE_URL: u32 = 20;
pub const T_OPERATOR_ID: u32 = 32;
pub const T_OPERATOR_NAME: u32 = 33;
pub const T_OPERATOR_URL: u32 = 34;
pub const T_OPERATOR_TIMEZONE: u32 = 35;
pub const T_OPERATOR_PHONE: u32 = 36;
pub const T_OPERATOR_LANGUAGE: u32 = 37;
pub const T_TRIP_ID: u32 = 48;
pub const T_TRIP_REALTIME: u32 = 49;
pub const T_TRIP_NUMBER: u32 = 50;
pub const T_TRIP_KIND: u32 = 51;
pub const T_TRIP_BLOCK: u32 = 52;
pub const T_ROUTE_HEADSIGN: u32 = 64;

// A query's numbers, by their place in its array.
pub const Q_FROM: usize = 0;
pub const Q_TO: usize = 3;
pub const Q_DAY: usize = 6;
pub const Q_TIME: usize = 7;
/// So many are needed; every later one may be left out, or be negative, and is then the planner's own.
pub const Q_NEEDED: usize = 8;
pub const Q_WINDOW: usize = 8;
pub const Q_ARRIVE_BY: usize = 9;
pub const Q_MAX_RIDES: usize = 10;
pub const Q_REACH_M: usize = 11;
pub const Q_WALK_MM_S: usize = 12;
pub const Q_CHANGE_S: usize = 13;
pub const Q_RULES: usize = 14;
pub const Q_WEIGH: usize = 15;
pub const Q_OPTIONS: usize = 16;
pub const Q_DEPARTURES: usize = 17;
pub const Q_HORIZON: usize = 18;
pub const Q_WHOLE: usize = 19;
pub const Q_BAG: usize = 20;
pub const Q_LABELS: usize = 21;
pub const Q_STEPS_K: usize = 22;
pub const Q_LEN: usize = 23;

// What a place is.
pub const P_STOP: i32 = 0;
pub const P_STATION: i32 = 1;
pub const P_POINT: i32 = 2;
pub const P_WALKS: i32 = 3;

// A wish: what it names, and how strongly. Each is four numbers (what, how, seconds, how many numbers follow) and
// then those that follow.
pub const W_HEAD: usize = 4;
pub const W_STOP: i32 = 0;
pub const W_STATION: i32 = 1;
pub const W_LINE: i32 = 2;
pub const W_OPERATOR: i32 = 3;
pub const W_MODE: i32 = 4;
pub const W_TRIP: i32 = 5;
pub const W_NO_WHEELCHAIR: i32 = 6;
pub const W_WHEELCHAIR_UNKNOWN: i32 = 7;
pub const W_NO_BICYCLES: i32 = 8;
pub const W_BICYCLES_UNKNOWN: i32 = 9;
pub const W_CIRCLE: i32 = 10;
pub const W_BOX: i32 = 11;
pub const W_OUTLINE: i32 = 12;
pub const W_KIND: i32 = 13;
pub const H_NEVER: i32 = 0;
pub const H_AVOID: i32 = 1;
pub const H_PREFER: i32 = 2;
/// As many wishes as the planner reads.
pub const MAX_WISHES: usize = 64;

// An answer's numbers: its head, then each journey's head and legs.
pub const A_HEAD: usize = 8;
pub const A_JOURNEYS: usize = 0;
pub const A_CUT_SHORT: usize = 1;
pub const A_HORIZON: usize = 2;
pub const A_SEARCHES: usize = 3;
pub const A_LABELS: usize = 4;
pub const A_STEPS_K: usize = 5;
pub const A_LATER: usize = 6;
// Which caps an answer met, a bit each, in its head at `A_CUT_SHORT`: the planner's own numbers.
pub const CUT_STEPS: i32 = Answer::STEPS as i32;
pub const CUT_LABELS: i32 = Answer::LABELS as i32;
pub const CUT_MOMENTS: i32 = Answer::MOMENTS as i32;
pub const CUT_BAG: i32 = Answer::BAG as i32;
pub const CUT_OPTIONS: i32 = Answer::OPTIONS as i32;
pub const CUT_PLACE: i32 = Answer::PLACE as i32;
pub const CUT_DAYS: i32 = Answer::DAYS as i32;
pub const CUT_CHEAPER: i32 = Answer::CHEAPER as i32;
pub const J_HEAD: usize = 6;
pub const J_DEPART: usize = 0;
pub const J_ARRIVE: usize = 1;
pub const J_RIDES: usize = 2;
pub const J_PENALTY: usize = 3;
pub const J_WALKING: usize = 4;
pub const J_LEGS: usize = 5;
pub const L_LEN: usize = 10;
pub const L_KIND: usize = 0;
pub const L_FROM: usize = 1;
pub const L_TO: usize = 2;
pub const L_DEPART: usize = 3;
pub const L_ARRIVE: usize = 4;
pub const L_TRIP: usize = 5;
pub const L_ROUTE: usize = 6;
pub const L_DAY: usize = 7;
pub const L_BOARD: usize = 8;
pub const L_ALIGHT: usize = 9;
pub const L_WALK: i32 = 0;
pub const L_RIDE: i32 = 1;
/// The two ends of a journey, where a walk's end is no stop.
pub const ORIGIN: i32 = -1;
pub const DESTINATION: i32 = -2;

/// Every shared number by its name in the header, less the `ATLAS_TRANSIT_` before it.
pub const NAMES: &[(&str, i64)] = &[
    ("ABI", ABI as i64),
    ("NONE", NONE as i64),
    ("NO_DAY", NO_DAY as i64),
    ("OPENED", OPENED as i64),
    ("NOT_A_TIMETABLE", NOT_A_TIMETABLE as i64),
    ("BAD_VERSION", BAD_VERSION as i64),
    ("BAD_SECTION", BAD_SECTION as i64),
    ("BAD_RECORD", BAD_RECORD as i64),
    ("BAD_DAYS", BAD_DAYS as i64),
    ("INDEX_TOO_LARGE", INDEX_TOO_LARGE as i64),
    ("N_FIRST_DAY", N_FIRST_DAY as i64),
    ("N_DAYS", N_DAYS as i64),
    ("N_STOPS", N_STOPS as i64),
    ("N_LINES", N_LINES as i64),
    ("N_OPERATORS", N_OPERATORS as i64),
    ("N_ROUTES", N_ROUTES as i64),
    ("N_TRIPS", N_TRIPS as i64),
    ("N_CHANGE_SECONDS", N_CHANGE_SECONDS as i64),
    ("N_DAY_OF", N_DAY_OF as i64),
    ("N_DATE_OF", N_DATE_OF as i64),
    ("N_STOP_LAT", N_STOP_LAT as i64),
    ("N_STOP_LON", N_STOP_LON as i64),
    ("N_STOP_STATION", N_STOP_STATION as i64),
    ("N_STOP_KIND", N_STOP_KIND as i64),
    ("N_STOP_WHEELCHAIR", N_STOP_WHEELCHAIR as i64),
    ("N_STOP_PLACE", N_STOP_PLACE as i64),
    ("N_LINE_OPERATOR", N_LINE_OPERATOR as i64),
    ("N_LINE_TYPE", N_LINE_TYPE as i64),
    ("N_LINE_COLOUR", N_LINE_COLOUR as i64),
    ("N_LINE_TEXT_COLOUR", N_LINE_TEXT_COLOUR as i64),
    ("N_ROUTE_LINE", N_ROUTE_LINE as i64),
    ("N_ROUTE_STOPS", N_ROUTE_STOPS as i64),
    ("N_ROUTE_STOP", N_ROUTE_STOP as i64),
    ("N_ROUTE_DIRECTION", N_ROUTE_DIRECTION as i64),
    ("N_TRIP_ROUTE", N_TRIP_ROUTE as i64),
    ("N_TRIP_WHEELCHAIR", N_TRIP_WHEELCHAIR as i64),
    ("N_TRIP_BICYCLES", N_TRIP_BICYCLES as i64),
    ("N_TRIP_ARRIVES", N_TRIP_ARRIVES as i64),
    ("N_TRIP_LEAVES", N_TRIP_LEAVES as i64),
    ("T_STOP_ID", T_STOP_ID as i64),
    ("T_STOP_CODE", T_STOP_CODE as i64),
    ("T_STOP_NAME", T_STOP_NAME as i64),
    ("T_STOP_PLATFORM", T_STOP_PLATFORM as i64),
    ("T_STOP_ZONE", T_STOP_ZONE as i64),
    ("T_STOP_TIMEZONE", T_STOP_TIMEZONE as i64),
    ("T_STOP_DESCRIPTION", T_STOP_DESCRIPTION as i64),
    ("T_STOP_IFOPT", T_STOP_IFOPT as i64),
    ("T_LINE_ID", T_LINE_ID as i64),
    ("T_LINE_SHORT", T_LINE_SHORT as i64),
    ("T_LINE_LONG", T_LINE_LONG as i64),
    ("T_LINE_DESCRIPTION", T_LINE_DESCRIPTION as i64),
    ("T_LINE_URL", T_LINE_URL as i64),
    ("T_OPERATOR_ID", T_OPERATOR_ID as i64),
    ("T_OPERATOR_NAME", T_OPERATOR_NAME as i64),
    ("T_OPERATOR_URL", T_OPERATOR_URL as i64),
    ("T_OPERATOR_TIMEZONE", T_OPERATOR_TIMEZONE as i64),
    ("T_OPERATOR_PHONE", T_OPERATOR_PHONE as i64),
    ("T_OPERATOR_LANGUAGE", T_OPERATOR_LANGUAGE as i64),
    ("T_TRIP_ID", T_TRIP_ID as i64),
    ("T_TRIP_REALTIME", T_TRIP_REALTIME as i64),
    ("T_TRIP_NUMBER", T_TRIP_NUMBER as i64),
    ("T_TRIP_KIND", T_TRIP_KIND as i64),
    ("T_TRIP_BLOCK", T_TRIP_BLOCK as i64),
    ("T_ROUTE_HEADSIGN", T_ROUTE_HEADSIGN as i64),
    ("Q_FROM", Q_FROM as i64),
    ("Q_TO", Q_TO as i64),
    ("Q_DAY", Q_DAY as i64),
    ("Q_TIME", Q_TIME as i64),
    ("Q_NEEDED", Q_NEEDED as i64),
    ("Q_WINDOW", Q_WINDOW as i64),
    ("Q_ARRIVE_BY", Q_ARRIVE_BY as i64),
    ("Q_MAX_RIDES", Q_MAX_RIDES as i64),
    ("Q_REACH_M", Q_REACH_M as i64),
    ("Q_WALK_MM_S", Q_WALK_MM_S as i64),
    ("Q_CHANGE_S", Q_CHANGE_S as i64),
    ("Q_RULES", Q_RULES as i64),
    ("Q_WEIGH", Q_WEIGH as i64),
    ("Q_OPTIONS", Q_OPTIONS as i64),
    ("Q_DEPARTURES", Q_DEPARTURES as i64),
    ("Q_HORIZON", Q_HORIZON as i64),
    ("Q_WHOLE", Q_WHOLE as i64),
    ("Q_BAG", Q_BAG as i64),
    ("Q_LABELS", Q_LABELS as i64),
    ("Q_STEPS_K", Q_STEPS_K as i64),
    ("Q_LEN", Q_LEN as i64),
    ("P_STOP", P_STOP as i64),
    ("P_STATION", P_STATION as i64),
    ("P_POINT", P_POINT as i64),
    ("P_WALKS", P_WALKS as i64),
    ("W_HEAD", W_HEAD as i64),
    ("W_STOP", W_STOP as i64),
    ("W_STATION", W_STATION as i64),
    ("W_LINE", W_LINE as i64),
    ("W_OPERATOR", W_OPERATOR as i64),
    ("W_MODE", W_MODE as i64),
    ("W_TRIP", W_TRIP as i64),
    ("W_NO_WHEELCHAIR", W_NO_WHEELCHAIR as i64),
    ("W_WHEELCHAIR_UNKNOWN", W_WHEELCHAIR_UNKNOWN as i64),
    ("W_NO_BICYCLES", W_NO_BICYCLES as i64),
    ("W_BICYCLES_UNKNOWN", W_BICYCLES_UNKNOWN as i64),
    ("W_CIRCLE", W_CIRCLE as i64),
    ("W_BOX", W_BOX as i64),
    ("W_OUTLINE", W_OUTLINE as i64),
    ("W_KIND", W_KIND as i64),
    ("H_NEVER", H_NEVER as i64),
    ("H_AVOID", H_AVOID as i64),
    ("H_PREFER", H_PREFER as i64),
    ("MAX_WISHES", MAX_WISHES as i64),
    ("A_HEAD", A_HEAD as i64),
    ("A_JOURNEYS", A_JOURNEYS as i64),
    ("A_CUT_SHORT", A_CUT_SHORT as i64),
    ("A_HORIZON", A_HORIZON as i64),
    ("A_SEARCHES", A_SEARCHES as i64),
    ("A_LABELS", A_LABELS as i64),
    ("A_STEPS_K", A_STEPS_K as i64),
    ("A_LATER", A_LATER as i64),
    ("CUT_STEPS", CUT_STEPS as i64),
    ("CUT_LABELS", CUT_LABELS as i64),
    ("CUT_MOMENTS", CUT_MOMENTS as i64),
    ("CUT_BAG", CUT_BAG as i64),
    ("CUT_OPTIONS", CUT_OPTIONS as i64),
    ("CUT_PLACE", CUT_PLACE as i64),
    ("CUT_DAYS", CUT_DAYS as i64),
    ("CUT_CHEAPER", CUT_CHEAPER as i64),
    ("J_HEAD", J_HEAD as i64),
    ("J_DEPART", J_DEPART as i64),
    ("J_ARRIVE", J_ARRIVE as i64),
    ("J_RIDES", J_RIDES as i64),
    ("J_PENALTY", J_PENALTY as i64),
    ("J_WALKING", J_WALKING as i64),
    ("J_LEGS", J_LEGS as i64),
    ("L_LEN", L_LEN as i64),
    ("L_KIND", L_KIND as i64),
    ("L_FROM", L_FROM as i64),
    ("L_TO", L_TO as i64),
    ("L_DEPART", L_DEPART as i64),
    ("L_ARRIVE", L_ARRIVE as i64),
    ("L_TRIP", L_TRIP as i64),
    ("L_ROUTE", L_ROUTE as i64),
    ("L_DAY", L_DAY as i64),
    ("L_BOARD", L_BOARD as i64),
    ("L_ALIGHT", L_ALIGHT as i64),
    ("L_WALK", L_WALK as i64),
    ("L_RIDE", L_RIDE as i64),
    ("ORIGIN", ORIGIN as i64),
    ("DESTINATION", DESTINATION as i64),
];

/// An open timetable: the file as it is read, the planner over it with its working memory, and the last answer.
/// The first is read by any thread; the other two by one plan at a time. So no function here makes a reference to
/// the whole of it: each takes the part it uses.
pub struct AtlasTransit {
    tt: Timetable<'static>,
    planner: Planner<'static>,
    answer: Vec<i32>,
}

/// The caller's array. One of no numbers is the empty array, wherever its pointer points. One that has numbers
/// and cannot be read (no pointer, a pointer that is no number's place, more numbers than memory holds) is
/// nothing, and never the empty array: a plan made without the wishes it was sent with would pass for the answer
/// to them.
///
/// # Safety
/// `p`, where it is not null, points to `len` numbers that nothing writes while the slice is used.
unsafe fn numbers<'a>(p: *const i32, len: usize) -> Option<&'a [i32]> {
    if len == 0 {
        return Some(&[]);
    }
    if p.is_null() || !p.is_aligned() || len > isize::MAX as usize / 4 {
        return None;
    }
    // SAFETY: not null, aligned, `len` numbers by the caller's word, and no longer than a slice may be
    Some(unsafe { core::slice::from_raw_parts(p, len) })
}

/// A number of the file as the interface gives it: `NONE` for the file's "nothing", and never past what 32 bits
/// with a sign hold.
fn out(v: u32) -> i32 {
    if v == atlas_transit::NONE { NONE } else { v.min(i32::MAX as u32) as i32 }
}

/// The interface's version, for the other side to hold against the header it was built with.
#[unsafe(no_mangle)]
pub extern "C" fn atlas_transit_abi() -> u32 {
    ABI
}

/// Opens a timetable that lies in memory. Null where the bytes are no timetable this library reads, or where the
/// planner's index for it would take more than `index_most` bytes of memory (0 or less: the library's own bound,
/// `Limits::default().index`), which is found before any of that memory is asked for; `why`, where it is not
/// null, gets the reason, or `OPENED`.
///
/// # Safety
/// `bytes` points to `len` bytes that stay where they are, unchanged, until the timetable is closed; `why` is null
/// or points to a number that may be written.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_transit_open(bytes: *const u8, len: usize, index_most: i32, why: *mut i32) -> *mut AtlasTransit {
    let opened = if bytes.is_null() || len > isize::MAX as usize {
        Err(NOT_A_TIMETABLE)
    } else {
        // SAFETY: `len` bytes by the caller's word, which it keeps unchanged for as long as the timetable is open:
        // that is the lifetime the planner is given here, and `atlas_transit_close` ends it
        let b: &'static [u8] = unsafe { core::slice::from_raw_parts(bytes, len) };
        Timetable::open(b).map_err(|e| match e {
            OpenError::NotATimetable => NOT_A_TIMETABLE,
            OpenError::Version(_) => BAD_VERSION,
            OpenError::Section(_) => BAD_SECTION,
            OpenError::Record(..) => BAD_RECORD,
            OpenError::Days => BAD_DAYS,
        })
    };
    let opened = opened.and_then(|tt| {
        let mut limits = Limits::default();
        if index_most > 0 {
            limits.index = index_most as u64;
        }
        Planner::within(tt, &limits).map(|planner| (tt, planner)).map_err(|_| INDEX_TOO_LARGE)
    });
    if !why.is_null() {
        // SAFETY: a number that may be written, by the caller's word
        unsafe { why.write(opened.as_ref().err().copied().unwrap_or(OPENED)) };
    }
    match opened {
        Ok((tt, planner)) => Box::into_raw(Box::new(AtlasTransit { tt, planner, answer: Vec::new() })),
        Err(_) => core::ptr::null_mut(),
    }
}

/// Closes a timetable; null closes nothing. The file's bytes are the caller's again.
///
/// # Safety
/// `t` is null or came from `atlas_transit_open`, was not closed before, and no other call uses it now or later.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_transit_close(t: *mut AtlasTransit) {
    if !t.is_null() {
        // SAFETY: the box `atlas_transit_open` made, given back once
        drop(unsafe { Box::from_raw(t) });
    }
}

/// The file an open timetable reads, by itself: a copy of its few words, so that no reference is held to what a
/// plan on another thread is writing.
///
/// # Safety
/// `t` is null or an open timetable.
unsafe fn file(t: *const AtlasTransit) -> Option<Timetable<'static>> {
    if t.is_null() {
        return None;
    }
    // SAFETY: an open timetable; its file is set once when it is opened and only read after
    Some(unsafe { (*t).tt })
}

/// A number of the timetable: `what` says which, of which stop, line, route or trip (`id`), and for a route or a
/// trip at which place along it (`at`). `NONE` where there is none.
///
/// # Safety
/// `t` is null or an open timetable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_transit_number(t: *const AtlasTransit, what: u32, id: u32, at: u32) -> i32 {
    // SAFETY: the caller's word
    match unsafe { file(t) } {
        Some(tt) => number(&tt, what, id, at),
        None => NONE,
    }
}

fn number(tt: &Timetable, what: u32, id: u32, at: u32) -> i32 {
    match what {
        N_FIRST_DAY => out(tt.first_day()),
        N_DAYS => out(tt.days()),
        N_STOPS => out(tt.stops()),
        N_LINES => out(tt.lines()),
        N_OPERATORS => out(tt.operators()),
        N_ROUTES => out(tt.routes()),
        N_TRIPS => out(tt.trips()),
        N_CHANGE_SECONDS => out(tt.change_seconds()),
        // (a date the calendar has: the planner's reader takes 30 February for 2 March, and that is none)
        N_DAY_OF => tt.day_of(id).filter(|day| tt.date_of(*day) == id).unwrap_or(NO_DAY),
        // (a day far outside the file is no date the calendar has)
        N_DATE_OF if (id as i32).unsigned_abs() <= 1_000_000 => out(tt.date_of(id as i32)),
        N_STOP_LAT..=N_STOP_PLACE if id < tt.stops() => {
            let s = tt.stop(id);
            match what {
                N_STOP_LAT => s.lat,
                N_STOP_LON => s.lon,
                N_STOP_STATION => out(s.parent),
                N_STOP_KIND => s.kind as i32,
                N_STOP_WHEELCHAIR => s.wheelchair as i32,
                _ => s.place as i32,
            }
        }
        N_LINE_OPERATOR..=N_LINE_TEXT_COLOUR if id < tt.lines() => match what {
            N_LINE_OPERATOR => out(tt.line_operator(id)),
            N_LINE_TYPE => out(tt.line_type(id)),
            N_LINE_COLOUR => tt.line_colours(id).0.map_or(NONE, out),
            _ => tt.line_colours(id).1.map_or(NONE, out),
        },
        N_ROUTE_LINE..=N_ROUTE_DIRECTION if id < tt.routes() => {
            let r = tt.route(id);
            match what {
                N_ROUTE_LINE => out(r.line),
                N_ROUTE_STOPS => out(r.stops),
                N_ROUTE_STOP if at < r.stops => out(tt.pattern_stop(r.first_stop.saturating_add(at)).stop),
                N_ROUTE_DIRECTION => out(tt.pattern_direction(r.pattern)),
                _ => NONE,
            }
        }
        N_TRIP_ROUTE..=N_TRIP_LEAVES if id < tt.trips() => {
            let route = tt.route_of_trip(id);
            match what {
                N_TRIP_ROUTE => out(route),
                N_TRIP_WHEELCHAIR => tt.trip(id).wheelchair as i32,
                N_TRIP_BICYCLES => tt.trip(id).bicycles as i32,
                _ if at < tt.route(route).stops => {
                    let (arrives, leaves) = tt.time(tt.trip_times(id), at);
                    out(if what == N_TRIP_ARRIVES { arrives } else { leaves })
                }
                _ => NONE,
            }
        }
        _ => NONE,
    }
}

/// A name of the timetable, as the file's own bytes of UTF-8: `what` says which, of which stop, line, operator,
/// trip or route (`id`), and for a route's headsign from which place along it on (`at`). The bytes are not ended
/// by a zero: `len` gets how many they are. They stay until the timetable is closed. Never null; no name is no
/// bytes.
///
/// # Safety
/// `t` is null or an open timetable; `len` is null or points to a number that may be written.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_transit_text(t: *const AtlasTransit, what: u32, id: u32, at: u32, len: *mut usize) -> *const u8 {
    // SAFETY: the caller's word
    let s = unsafe { file(t) }.map_or("", |tt| text(&tt, what, id, at));
    // SAFETY: the caller's word
    unsafe { give(s, len) }
}

/// A string as the interface gives one.
///
/// # Safety
/// `len` is null or points to a number that may be written.
unsafe fn give(s: &'static str, len: *mut usize) -> *const u8 {
    if !len.is_null() {
        // SAFETY: a number that may be written, by the caller's word
        unsafe { len.write(s.len()) };
    }
    s.as_ptr()
}

fn text(tt: &Timetable<'static>, what: u32, id: u32, at: u32) -> &'static str {
    let stop = |which| if id < tt.stops() { tt.stop_text(id, which) } else { "" };
    let line = |which| if id < tt.lines() { tt.line_text(id, which) } else { "" };
    let operator = |which| if id < tt.operators() { tt.operator_text(id, which) } else { "" };
    let trip = |which| if id < tt.trips() { tt.trip_text(id, which) } else { "" };
    match what {
        T_STOP_ID => stop(StopText::Id),
        T_STOP_CODE => stop(StopText::Code),
        T_STOP_NAME => stop(StopText::Name),
        T_STOP_PLATFORM => stop(StopText::Platform),
        T_STOP_ZONE => stop(StopText::Zone),
        T_STOP_TIMEZONE => stop(StopText::Timezone),
        T_STOP_DESCRIPTION => stop(StopText::Description),
        T_STOP_IFOPT => stop(StopText::Ifopt),
        T_LINE_ID => line(LineText::Id),
        T_LINE_SHORT => line(LineText::Short),
        T_LINE_LONG => line(LineText::Long),
        T_LINE_DESCRIPTION => line(LineText::Description),
        T_LINE_URL => line(LineText::Url),
        T_OPERATOR_ID => operator(OperatorText::Id),
        T_OPERATOR_NAME => operator(OperatorText::Name),
        T_OPERATOR_URL => operator(OperatorText::Url),
        T_OPERATOR_TIMEZONE => operator(OperatorText::Timezone),
        T_OPERATOR_PHONE => operator(OperatorText::Phone),
        T_OPERATOR_LANGUAGE => operator(OperatorText::Language),
        T_TRIP_ID => trip(TripText::Id),
        T_TRIP_REALTIME => trip(TripText::Realtime),
        T_TRIP_NUMBER => trip(TripText::Number),
        T_TRIP_KIND => trip(TripText::Kind),
        T_TRIP_BLOCK => trip(TripText::Block),
        T_ROUTE_HEADSIGN if id < tt.routes() => {
            // what the feed shows from that stop on, or the pattern's own
            let r = tt.route(id);
            let from_there = if at < r.stops { tt.pattern_stop_headsign(r.first_stop.saturating_add(at)) } else { "" };
            if from_there.is_empty() { tt.pattern_headsign(r.pattern) } else { from_there }
        }
        _ => "",
    }
}

/// A line of the file's own description of itself (FORMAT.md, `META`), by its key: `first_day`, `feed_version`.
/// As `atlas_transit_text` gives a name; no bytes where the file has no such line or the key is not UTF-8.
///
/// # Safety
/// `t` is null or an open timetable; `key` is null or points to `key_len` bytes; `len` is null or points to a
/// number that may be written.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_transit_meta(t: *const AtlasTransit, key: *const u8, key_len: usize, len: *mut usize) -> *const u8 {
    let key = if key.is_null() || key_len > 4096 {
        None
    } else {
        // SAFETY: `key_len` bytes by the caller's word
        core::str::from_utf8(unsafe { core::slice::from_raw_parts(key, key_len) }).ok()
    };
    // SAFETY: the caller's word
    let value = unsafe { file(t) }.zip(key).and_then(|(tt, key)| tt.meta(key)).unwrap_or("");
    // SAFETY: the caller's word
    unsafe { give(value, len) }
}

/// Plans. `query` is the query's numbers (`Q_*`; `Q_NEEDED` at least), `from_walks` and `to_walks` the stops of a
/// place of kind `P_WALKS` as pairs of a stop and the seconds on foot, `wishes` the wishes one after another
/// (`W_*`). Gives how many numbers the answer has; it stays with the timetable until the next plan, and
/// `atlas_transit_answer` reads it. 0: the query could not be read and nothing was planned; an array that has
/// numbers and cannot be read (`numbers`) is such a query, whichever of the four it is.
///
/// # Safety
/// `t` is null or an open timetable that no other plan or reading of an answer uses meanwhile; each array is null
/// or as long as its length says.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_transit_plan(
    t: *mut AtlasTransit,
    query: *const i32,
    query_len: usize,
    from_walks: *const i32,
    from_walks_len: usize,
    to_walks: *const i32,
    to_walks_len: usize,
    wishes: *const i32,
    wishes_len: usize,
) -> usize {
    if t.is_null() {
        return 0;
    }
    // SAFETY: the caller's arrays, each as long as it says
    let arrays = unsafe { (numbers(query, query_len), numbers(from_walks, from_walks_len), numbers(to_walks, to_walks_len), numbers(wishes, wishes_len)) };
    // SAFETY: an open timetable, and by the caller's word nothing else uses its planner or its answer during this
    // call; the file beside them is not touched through these two
    let (planner, answer) = unsafe { (&mut (*t).planner, &mut (*t).answer) };
    answer.clear();
    let (Some(q), Some(from), Some(to), Some(wishes)) = arrays else {
        return 0;
    };
    let Some(query) = read(q, from, to, wishes) else {
        return 0;
    };
    write(&planner.plan(&query), answer);
    answer.len()
}

/// Copies the last answer's numbers into `out`, `cap` of them at most, and gives how many the answer has.
///
/// # Safety
/// `t` is null or an open timetable that no plan uses meanwhile; `out` is null or has room for `cap` numbers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_transit_answer(t: *const AtlasTransit, out: *mut i32, cap: usize) -> usize {
    if t.is_null() {
        return 0;
    }
    // SAFETY: an open timetable, and by the caller's word no plan writes its answer during this call
    let answer = unsafe { &(*t).answer };
    if !out.is_null() && out.is_aligned() {
        // SAFETY: room for `cap` numbers by the caller's word, of which no more than the answer has are written;
        // the answer is this library's own memory and cannot overlap the caller's
        unsafe { core::ptr::copy_nonoverlapping(answer.as_ptr(), out, answer.len().min(cap)) };
    }
    answer.len()
}

/// The query a caller's numbers say, or nothing where they say none.
pub fn read(q: &[i32], from_walks: &[i32], to_walks: &[i32], wishes: &[i32]) -> Option<Query> {
    // (more numbers than a query has: the caller reads another header than this library was built with)
    if q.len() < Q_NEEDED || q.len() > Q_LEN || q[Q_TIME] < 0 {
        return None;
    }
    let place = |at: usize, walks: &[i32]| match q[at] {
        P_STOP => Some(Place::Stop(q[at + 1] as u32)),
        P_STATION => Some(Place::Station(q[at + 1] as u32)),
        P_POINT => Some(Place::Point { lat: q[at + 1], lon: q[at + 2] }),
        // pairs, and no time on foot below zero
        P_WALKS if walks.len() % 2 == 0 && walks.chunks_exact(2).all(|w| w[1] >= 0) => Some(Place::Walks(walks.chunks_exact(2).map(|w| (w[0] as u32, w[1] as u32)).collect())),
        _ => None,
    };
    let mut query = Query::new(place(Q_FROM, from_walks)?, place(Q_TO, to_walks)?, q[Q_DAY], q[Q_TIME] as u32);
    // a number left out, or below zero, leaves the planner's own
    let given = |at: usize| q.get(at).copied().filter(|v| *v >= 0).map(|v| v as u32);
    let set = |at: usize, field: &mut u32| {
        if let Some(v) = given(at) {
            *field = v;
        }
    };
    let flag = |at: usize, field: &mut bool, yes: bool| {
        if let Some(v) = given(at) {
            *field = (v != 0) == yes;
        }
    };
    set(Q_WINDOW, &mut query.window);
    flag(Q_ARRIVE_BY, &mut query.arrive_by, true);
    set(Q_MAX_RIDES, &mut query.max_rides);
    set(Q_REACH_M, &mut query.reach_m);
    set(Q_WALK_MM_S, &mut query.walk_mm_s);
    query.change_s = given(Q_CHANGE_S);
    flag(Q_RULES, &mut query.rules, true);
    flag(Q_WEIGH, &mut query.weigh, true);
    set(Q_OPTIONS, &mut query.limits.options);
    set(Q_DEPARTURES, &mut query.limits.departures);
    set(Q_HORIZON, &mut query.limits.horizon);
    flag(Q_WHOLE, &mut query.limits.patient, false);
    set(Q_BAG, &mut query.limits.bag);
    set(Q_LABELS, &mut query.limits.labels);
    if let Some(v) = given(Q_STEPS_K) {
        query.limits.steps = v as u64 * 1000;
    }
    let mut rest = wishes;
    while !rest.is_empty() {
        let (head, after) = rest.split_at_checked(W_HEAD)?;
        let (what, how, seconds, n) = (head[0], head[1], head[2], head[3]);
        if seconds < 0 || n < 0 || query.wishes.len() >= MAX_WISHES {
            return None;
        }
        let (more, after) = after.split_at_checked(n as usize)?;
        rest = after;
        let how = match how {
            H_NEVER => How::Never,
            H_AVOID => How::Avoid(seconds as u32),
            H_PREFER => How::Prefer(seconds as u32),
            _ => return None,
        };
        let through = |v: i32| match v {
            0 => Some(false),
            1 => Some(true),
            _ => None,
        };
        let what = match (what, more) {
            (W_STOP, &[stop, t]) => What::Stop { stop: stop as u32, through: through(t)? },
            (W_STATION, &[station, t]) => What::Station { station: station as u32, through: through(t)? },
            (W_LINE, &[line]) => What::Line(line as u32),
            (W_OPERATOR, &[operator]) => What::Operator(operator as u32),
            (W_MODE, &[route_type]) => What::Mode(Mode::of(route_type as u32)),
            (W_TRIP, &[trip]) => What::Trip(trip as u32),
            (W_NO_WHEELCHAIR, &[]) => What::NoWheelchair,
            (W_WHEELCHAIR_UNKNOWN, &[]) => What::WheelchairUnknown,
            (W_NO_BICYCLES, &[]) => What::NoBicycles,
            (W_BICYCLES_UNKNOWN, &[]) => What::BicyclesUnknown,
            (W_CIRCLE, &[t, lat, lon, radius_m]) if radius_m >= 0 => What::Area { area: Area::Circle { lat, lon, radius_m: radius_m as u32 }, through: through(t)? },
            (W_BOX, &[t, south, west, north, east]) => What::Area { area: Area::Box { south, west, north, east }, through: through(t)? },
            (W_OUTLINE, [t, corners @ ..]) if corners.len() % 2 == 0 => What::Area { area: Area::Outline(corners.chunks_exact(2).map(|c| (c[0], c[1])).collect()), through: through(*t)? },
            // a kind of trip by its name ("Intercity"): the bytes of its UTF-8, a number each
            (W_KIND, bytes) => What::Kind(String::from_utf8(bytes.iter().map(|b| u8::try_from(*b).ok()).collect::<Option<Vec<u8>>>()?).ok()?),
            _ => return None,
        };
        query.wishes.push(Wish { what, how });
    }
    Some(query)
}

/// An answer as numbers: its head, then each journey's head and its legs.
pub fn write(a: &Answer, v: &mut Vec<i32>) {
    let n = |x: u32| x.min(i32::MAX as u32) as i32;
    let head = [n(a.journeys.len() as u32), n(a.cut_by), n(a.horizon), n(a.searches), n(a.labels), a.steps.div_ceil(1000).min(i32::MAX as u64) as i32, n(a.later), 0];
    v.extend(head);
    let spot = |s: Spot| match s {
        Spot::Origin => ORIGIN,
        Spot::Destination => DESTINATION,
        Spot::Stop(s) => n(s),
    };
    for j in a.journeys.iter() {
        v.extend([j.depart, j.arrive, n(j.rides), n(j.penalty), n(j.walking), n(j.legs.len() as u32)]);
        for l in j.legs.iter() {
            match *l {
                Leg::Walk { from, to, depart, arrive } => v.extend([L_WALK, spot(from), spot(to), depart, arrive, NONE, NONE, 0, NONE, NONE]),
                Leg::Ride { trip, route, day, board, alight, from, to, depart, arrive } => v.extend([L_RIDE, n(from), n(to), depart, arrive, n(trip), n(route), day, n(board), n(alight)]),
            }
        }
    }
}
