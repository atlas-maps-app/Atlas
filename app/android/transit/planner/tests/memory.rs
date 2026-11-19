//! What a timetable takes in memory beside its own bytes: nothing to open it, an index a planner makes once, and
//! what a planner that has answered keeps for the next question. Counted by an allocator that counts every byte
//! asked for and given back, so this file has the only `unsafe` of the crate's tests, and its two tests never run
//! at once: one's bytes would be counted with the other's.
//!
//! The numbers are the README's ("What it takes in memory"); the interface's host reads them to know what
//! `atlas_transit_open` costs on a phone. The second test says them for a country's file:
//! `ATLAS_TRANSIT_FILE=<file> cargo test --test memory -- --ignored --nocapture`.

use atlas_transit::{Place, Planner, Query, Timetable};
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering::Relaxed};

const NONE: u32 = u32::MAX;

struct Counting;

static LIVE: AtomicUsize = AtomicUsize::new(0);
static MOST: AtomicUsize = AtomicUsize::new(0);
static ONE_AT_A_TIME: Mutex<()> = Mutex::new(());

fn more(bytes: usize) {
    MOST.fetch_max(LIVE.fetch_add(bytes, Relaxed) + bytes, Relaxed);
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
        LIVE.fetch_sub(l.size(), Relaxed);
        unsafe { System.dealloc(p, l) }
    }

    unsafe fn realloc(&self, p: *mut u8, l: Layout, new: usize) -> *mut u8 {
        if new >= l.size() {
            more(new - l.size());
        } else {
            LIVE.fetch_sub(l.size() - new, Relaxed);
        }
        unsafe { System.realloc(p, l, new) }
    }
}

#[global_allocator]
static COUNTING: Counting = Counting;

/// What `f` gives, the bytes it leaves taken, and the most it holds at once.
fn taken<T>(f: impl FnOnce() -> T) -> (T, usize, usize) {
    let before = LIVE.load(Relaxed);
    MOST.store(before, Relaxed);
    let out = f();
    (out, LIVE.load(Relaxed).saturating_sub(before), MOST.load(Relaxed).saturating_sub(before))
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
    let (mut planner, kept, most) = taken(|| Planner::new(tt));
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
    let _one = ONE_AT_A_TIME.lock().unwrap();
    let bytes = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/utrecht-20261117.tt")).unwrap();
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
    let (planner, kept, most) = taken(|| Planner::new(ruled));
    let each = (kept - t.index.0) as f64 / n as f64;
    println!("with {n} rules more that name a line that waits: {:.2} MB kept, {:.2} MB at most: {each:.1} bytes a rule on top of the 32 it has in the file", mb(kept), mb(most));
    drop(planner);
    assert!((30.0..80.0).contains(&each), "{each}");
}

#[test]
#[ignore = "needs the whole country's file: ATLAS_TRANSIT_FILE=<file> cargo test --test memory -- --ignored --nocapture"]
fn a_country_takes_what_the_readme_says() {
    let _one = ONE_AT_A_TIME.lock().unwrap();
    let file = std::env::var("ATLAS_TRANSIT_FILE").expect("ATLAS_TRANSIT_FILE names no timetable file of the Netherlands");
    let bytes = std::fs::read(&file).unwrap();
    let t = of(&bytes);
    said(&file, &bytes, &t);
    assert_eq!(t.open, (0, 0));
    assert!(t.index.0 * 5 < bytes.len() && t.index.1 < t.index.0 * 2, "{:?}", t.index);
    assert!(t.asked.0 + t.days.0 < 3 * bytes.len(), "{:?} {:?}", t.asked, t.days);
}
