//! What a timetable takes in memory beside its own bytes: nothing to open it, an index a planner makes once, and
//! what a planner that has answered keeps for the next question. Counted by an allocator that counts every byte
//! asked for and given back, so this file has the only `unsafe` of the crate's tests, and its tests never run at
//! once: one's bytes would be counted with the other's.
//!
//! The numbers are the README's ("What it takes in memory"); the interface's host reads them to know what
//! `atlas_transit_open` costs on a phone. The second test says them for a country's file:
//! `ATLAS_TRANSIT_FILE=<file> cargo test --test memory -- --ignored --nocapture`.

use atlas_transit::{IndexTooLarge, Limits, Place, Planner, Query, Timetable};
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::sync::{Mutex, MutexGuard};
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};

const NONE: u32 = u32::MAX;

struct Counting;

/// Where a count begins, so that bytes given back that were taken before it began pull nothing below zero.
const START: usize = 1 << 48;
static LIVE: AtomicUsize = AtomicUsize::new(START);
static MOST: AtomicUsize = AtomicUsize::new(START);
static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

/// One test at a time, whatever became of the one before.
fn alone() -> MutexGuard<'static, ()> {
    ONE_AT_A_TIME.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

thread_local! {
    /// Whether this thread's bytes are counted now: the measuring test's own, and no other thread's (the test
    /// harness has its own to write with).
    static COUNTED: Cell<bool> = const { Cell::new(false) };
}

fn counted() -> bool {
    COUNTED.try_with(|c| c.get()).unwrap_or(false)
}

fn more(bytes: usize) {
    if counted() {
        MOST.fetch_max(LIVE.fetch_add(bytes, Relaxed) + bytes, Relaxed);
    }
}

fn fewer(bytes: usize) {
    if counted() {
        LIVE.fetch_sub(bytes, Relaxed);
    }
}

// (the system's allocator, with the bytes counted)
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        more(l.size());
        unsafe { System.alloc(l) }
    }

    unsafe fn alloc_zeroed(&self, l: Layout) -> *mut u8 {
        more(l.size());
        unsafe { System.alloc_zeroed(l) }
    }

    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        fewer(l.size());
        unsafe { System.dealloc(p, l) }
    }

    unsafe fn realloc(&self, p: *mut u8, l: Layout, new: usize) -> *mut u8 {
        if new >= l.size() {
            more(new - l.size());
        } else {
            fewer(l.size() - new);
        }
        unsafe { System.realloc(p, l, new) }
    }
}

#[global_allocator]
static COUNTING: Counting = Counting;

/// What `f` gives, the bytes it leaves taken, and the most it holds at once.
fn taken<T>(f: impl FnOnce() -> T) -> (T, usize, usize) {
    LIVE.store(START, Relaxed);
    MOST.store(START, Relaxed);
    COUNTED.with(|c| c.set(true));
    let out = f();
    COUNTED.with(|c| c.set(false));
    (out, LIVE.load(Relaxed).saturating_sub(START), MOST.load(Relaxed).saturating_sub(START))
}

/// The bytes `f` gives back of what was taken before it.
fn given_back(f: impl FnOnce()) -> usize {
    LIVE.store(START, Relaxed);
    COUNTED.with(|c| c.set(true));
    f();
    COUNTED.with(|c| c.set(false));
    START.saturating_sub(LIVE.load(Relaxed))
}

/// A file's bytes taken, each as (kept, the most at once): to open it, for the planner's index, by eight questions
/// of two hours on the first day, and by one more on each of up to twenty more days (a planner keeps the lists of
/// the days asked about lately, sixteen at most).
struct Taken {
    open: (usize, usize),
    index: (usize, usize),
    asked: (usize, usize),
    days: (usize, usize),
}

fn of(bytes: &[u8]) -> Taken {
    let (tt, kept, most) = taken(|| Timetable::open(bytes).unwrap());
    let open = (kept, most);
    let (mut planner, kept, most) = taken(|| Planner::new(tt).unwrap());
    let index = (kept, most);
    let served: Vec<u32> = (0..tt.stops()).filter(|s| tt.stop(*s).kind == 0 && tt.routes_at(*s).0 != tt.routes_at(*s).1).collect();
    let ask = |planner: &mut Planner, day: i32, questions: usize| {
        for k in 0..questions {
            let (a, b) = (served[k * served.len() / 8], served[(k * 3 + 5) % 8 * served.len() / 8 + served.len() / 16]);
            let mut q = Query::new(Place::Stop(a), Place::Stop(b), day, 8 * 3600);
            q.window = 7200;
            planner.plan(&q);
        }
    };
    let (_, kept, most) = taken(|| ask(&mut planner, 0, 8));
    let asked = (kept, most);
    let (_, kept, most) = taken(|| (1..tt.days().min(21) as i32).for_each(|day| ask(&mut planner, day, 1)));
    Taken { open, index, asked, days: (kept, most) }
}

fn read(name: &str) -> Vec<u8> {
    std::fs::read(format!("{}/tests/data/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

fn mb(bytes: usize) -> f64 {
    bytes as f64 / 1e6
}

fn said(name: &str, bytes: &[u8], t: &Taken) {
    let tt = Timetable::open(bytes).unwrap();
    let a_mb = |n: usize| n as f64 / bytes.len() as f64;
    println!(
        "{name}: a file of {:.1} MB, {} stops, {} trips, {} rules, {} days. To open it: {} bytes kept, {} at most. The planner's index: {:.2} MB kept ({:.3} a MB of file, {:.0} bytes a stop), {:.2} MB at most. Eight questions of two hours: {:.2} MB kept ({:.3} a MB of file), {:.2} MB at most. One on each of {} more days: {:.2} MB kept on top, {:.2} MB at most.",
        mb(bytes.len()),
        tt.stops(),
        tt.trips(),
        tt.rules(),
        tt.days(),
        t.open.0,
        t.open.1,
        mb(t.index.0),
        a_mb(t.index.0),
        t.index.0 as f64 / tt.stops() as f64,
        mb(t.index.1),
        mb(t.asked.0),
        a_mb(t.asked.0),
        mb(t.asked.1),
        tt.days().min(21) - 1,
        mb(t.days.0),
        mb(t.days.1)
    );
}

/// The file with rules added (as `many.rs` adds them): its rules' section put anew at the end, sorted.
fn with_rules(b: &[u8], more: impl Iterator<Item = [u32; 8]>) -> Vec<u8> {
    let word = |o: usize| u32::from_le_bytes(b[o..o + 4].try_into().unwrap());
    let at = (0..word(12) as usize).map(|i| 16 + i * 16).find(|o| &b[*o..*o + 4] == b"TRUL").unwrap();
    let (off, len) = (word(at + 4) as usize, word(at + 8) as usize);
    let mut rules: Vec<[u32; 8]> = b[off..off + len].chunks_exact(32).map(|r| core::array::from_fn(|k| u32::from_le_bytes(r[k * 4..k * 4 + 4].try_into().unwrap()))).collect();
    rules.extend(more);
    rules.sort_unstable();
    let mut out = b.to_vec();
    out.resize(out.len().div_ceil(8) * 8, 0);
    let to = out.len() as u32;
    out.extend(rules.iter().flatten().flat_map(|w| w.to_le_bytes()));
    for (k, v) in [to, rules.len() as u32 * 32, rules.len() as u32].into_iter().enumerate() {
        out[at + 4 + k * 4..at + 8 + k * 4].copy_from_slice(&v.to_le_bytes());
    }
    out
}

/// Utrecht's day: opening takes nothing, the index is a small part of the file, a planner that has answered keeps
/// what the README says, and a rule that names a line that waits costs the index what the README says.
#[test]
fn a_file_takes_what_the_readme_says() {
    let _one = alone();
    let bytes = read("utrecht-20261117.tt");
    let t = of(&bytes);
    said("Utrecht", &bytes, &t);
    let tt = Timetable::open(&bytes).unwrap();
    assert_eq!(t.open, (0, 0));
    // the index: by stop, by walk, by stop of a pattern, and no more than a fifth of the file
    assert!(t.index.0 * 5 < bytes.len() && t.index.1 < t.index.0 * 2, "{:?}", t.index);
    // a planner that has answered: the work of a search is kept for the next, and a day's lists
    assert!(t.asked.0 < 3 * bytes.len() && t.asked.1 < 2 * t.asked.0, "{:?}", t.asked);
    // rules that name a line that waits, between stops no rule joined before: what each costs the index
    let n = 50_000u32;
    assert!((tt.stops() / 2).pow(2) >= n && tt.lines() > 0);
    let pairs = (0..n).map(|k| [k % (tt.stops() / 2), tt.stops() / 2 + k / (tt.stops() / 2), NONE, k % tt.lines(), NONE, NONE, 1, NONE]);
    let with = with_rules(&bytes, pairs);
    let ruled = Timetable::open(&with).unwrap();
    ruled.verify().unwrap();
    assert_eq!(ruled.rules(), tt.rules() + n);
    let (planner, kept, most) = taken(|| Planner::new(ruled).unwrap());
    let (each, made) = ((kept - t.index.0) as f64 / n as f64, (most - t.index.1) as f64 / n as f64);
    println!("with {n} rules more that name a line that waits: {:.2} MB kept, {:.2} MB at most: {each:.1} bytes a rule kept and {made:.0} while the index is made, on top of the 32 it has in the file", mb(kept), mb(most));
    drop(planner);
    // while it is made: its number (4), the pair of stops each way (16), what the change takes at each end (24),
    // and for the rules where one waits and for the rules that bind a stop (4) and a stop with a line (8); and
    // once, a bit a stop each way for where one waits
    assert_eq!(most - t.index.1, 68 * n as usize + 2 * 8 * tt.stops().div_ceil(64) as usize);
    // kept: the pair of stops each way and its number, and what many rules say alike once
    assert!((20.0..40.0).contains(&each), "{each}");
}

#[test]
#[ignore = "needs the whole country's file: ATLAS_TRANSIT_FILE=<file> cargo test --test memory -- --ignored --nocapture"]
fn a_country_takes_what_the_readme_says() {
    let _one = alone();
    let file = std::env::var("ATLAS_TRANSIT_FILE").expect("ATLAS_TRANSIT_FILE names no timetable file of the Netherlands");
    let bytes = std::fs::read(&file).unwrap();
    let t = of(&bytes);
    said(&file, &bytes, &t);
    assert_eq!(t.open, (0, 0));
    assert!(t.index.0 * 5 < bytes.len() && t.index.1 < t.index.0 * 2, "{:?}", t.index);
    assert!(t.asked.0 + t.days.0 < 3 * bytes.len(), "{:?} {:?}", t.asked, t.days);
    // and the most the index held while it was made is what was known of the file before
    let says = Planner::index_bytes(&Timetable::open(&bytes).unwrap());
    println!("its index was said to take {says} bytes before it was made (the packer's summary says the same of the file)");
    assert_eq!(t.index.1 as u64, says);
}

/// What a planner's index takes is known before it is made, to the byte: of every file here the most held at once
/// while it is made is what `Planner::index_bytes` said, and what stays is no more. The four files written by
/// hand take what the packer says of them when it writes them: ../pack/test_ttpack.py holds the same four numbers.
#[test]
fn an_index_takes_what_was_said_before_it_was_made() {
    let _one = alone();
    for (name, packer) in [("tiny.tt", Some(352)), ("edges.tt", Some(1704)), ("autumn.tt", Some(152)), ("waits.tt", Some(160)), ("utrecht-20261117.tt", None)] {
        let bytes = read(name);
        let tt = Timetable::open(&bytes).unwrap();
        let says = Planner::index_bytes(&tt);
        let (planner, kept, most) = taken(|| Planner::new(tt).unwrap());
        drop(planner);
        println!("{name}: said {says} bytes, held {most} at the most, kept {kept}");
        assert_eq!(most as u64, says, "{name}: the most held at once is not what was said");
        assert!(kept <= most, "{name}");
        if let Some(packer) = packer {
            assert_eq!(says, packer, "{name}: the packer's test has another number");
        }
    }
    // and with rules that name the same line at the same stop many times over, which the index keeps once
    let bytes = read("utrecht-20261117.tt");
    let tt = Timetable::open(&bytes).unwrap();
    let n = 50_000u32;
    let with = with_rules(&bytes, (0..n).map(|k| [k % 40, 300 + k % 7, NONE, k % 3, k % 5, NONE, 1 + k % 4, NONE]));
    let ruled = Timetable::open(&with).unwrap();
    assert_eq!(ruled.rules(), tt.rules() + n);
    let says = Planner::index_bytes(&ruled);
    let (planner, kept, most) = taken(|| Planner::new(ruled).unwrap());
    drop(planner);
    println!("with {n} rules more, many alike: said {says} bytes, held {most} at the most, kept {kept}");
    assert_eq!(most as u64, says);
    assert!(kept * 2 < most, "rules alike are kept once: {kept} of {most}");
}

/// Over what it may take no planner is made, and not a byte was asked for; at it one is. The library's own bound
/// is far from any timetable.
#[test]
fn an_index_over_what_it_may_take_is_refused_before_any_memory_is_asked_for() {
    let _one = alone();
    for name in ["tiny.tt", "edges.tt", "waits.tt", "utrecht-20261117.tt"] {
        let bytes = read(name);
        let tt = Timetable::open(&bytes).unwrap();
        let says = Planner::index_bytes(&tt);
        for limit in [says - 1, says / 2, 0] {
            let limits = Limits { index: limit, ..Limits::default() };
            let (refused, kept, most) = taken(|| Planner::within(tt, &limits).err());
            assert_eq!(refused, Some(IndexTooLarge { bytes: says, limit }), "{name}");
            assert_eq!((kept, most), (0, 0), "{name}: memory was asked for all the same");
        }
        assert!(Planner::within(tt, &Limits { index: says, ..Limits::default() }).is_ok(), "{name}");
        assert!(says * 100 < Limits::default().index, "{name}");
    }
    assert_eq!(Limits::default().index, 256 << 20);
}

/// A planner that has answered gives its working memory back when asked: all of it, to the byte, so that what it
/// holds is its index again. Asked the same afterwards it answers the same, and takes the same memory anew.
#[test]
fn a_planner_gives_its_working_memory_back() {
    let _one = alone();
    let bytes = read("utrecht-20261117.tt");
    let tt = Timetable::open(&bytes).unwrap();
    let mut planner = Planner::new(tt).unwrap();
    let served: Vec<u32> = (0..tt.stops()).filter(|s| tt.stop(*s).kind == 0 && tt.routes_at(*s).0 != tt.routes_at(*s).1).collect();
    let mut q = Query::new(Place::Stop(served[served.len() / 8]), Place::Stop(served[served.len() * 5 / 8]), 0, 8 * 3600);
    q.window = 7200;
    // (what an answer is made of is given back with the answer, inside the count)
    let ask = |planner: &mut Planner| {
        let a = planner.plan(&q);
        let said: Vec<(i32, i32, u32)> = a.journeys.iter().map(|j| (j.depart, j.arrive, j.rides)).collect();
        (said.len(), said.iter().fold(0i64, |sum, j| sum.wrapping_mul(31).wrapping_add(j.0 as i64 + j.1 as i64 * 7 + j.2 as i64)), a.steps)
    };
    let (first, kept, most) = taken(|| ask(&mut planner));
    println!("Utrecht, two hours: {} options; the planner keeps {kept} bytes to work with ({most} at most)", first.0);
    assert!(first.0 > 3 && kept > 500_000, "{first:?}, {kept} bytes kept");
    // asked again it takes nothing more; trimmed, it gives back what it kept, to the byte
    assert_eq!(taken(|| ask(&mut planner)), (first, 0, taken(|| ask(&mut planner)).2));
    assert_eq!(given_back(|| planner.trim()), kept);
    // and the next plan takes it anew: the same answer, and the same memory as a planner that never answered
    let (anew, kept_anew, _) = taken(|| ask(&mut planner));
    assert_eq!((anew, kept_anew), (first, kept));
    // (nothing to give back twice, and nothing taken to give it back)
    assert_eq!(given_back(|| planner.trim()), kept);
    assert_eq!((given_back(|| planner.trim()), taken(|| planner.trim()).2), (0, 0));
}

