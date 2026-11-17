//! The fuzzing driver: any bytes as a file, and any query on a file that opens, must come back without a panic and
//! within the caps. There is no crate here, so no libFuzzer: [`mutate`] makes the inputs from a real file with a
//! small generator of its own, the tests run a fixed number of them, and the tool's `fuzz` command runs as many as
//! asked. [`one`] is the single entry a coverage-guided fuzzer would call.

use crate::file::Timetable;
use crate::plan::Planner;
use crate::query::{Area, How, Limits, Mode, Place, Query, What, Wish};
use alloc::string::String;
use alloc::vec::Vec;

/// A small generator (xorshift64*), the same on every platform.
pub struct Rng(pub u64);

impl Rng {
    pub fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    pub fn below(&mut self, n: u64) -> u64 {
        if n == 0 { 0 } else { self.next() % n }
    }
    fn word(&mut self) -> u32 {
        const EDGES: [u32; 12] = [0, 1, 2, 0xFFFF_FFFF, 0x7FFF_FFFF, 0x8000_0000, 1 << 20, (1 << 20) - 1, 1 << 27, 65_535, 65_536, 86_400];
        match self.below(3) {
            0 => EDGES[self.below(EDGES.len() as u64) as usize],
            1 => self.below(4096) as u32,
            _ => self.next() as u32,
        }
    }
}

/// A file a little wrong: a seed with one to four changes. Most keep the file's shape (a field swapped with the same
/// field of another record, a number nudged), so that many still open and reach the search.
pub fn mutate(seed: &[u8], rng: &mut Rng) -> Vec<u8> {
    let mut b = seed.to_vec();
    if b.len() < 32 {
        return b;
    }
    let sections = (u32::from_le_bytes([b[12], b[13], b[14], b[15]]) as usize).min(64);
    let section = |b: &[u8], k: usize| -> (usize, usize) {
        let o = 16 + k * 16;
        if o + 16 > b.len() {
            return (0, 0);
        }
        let rd = |o: usize| u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]]) as usize;
        (rd(o + 4), rd(o + 8))
    };
    for _ in 0..1 + rng.below(4) {
        let (off, len) = if sections > 0 { section(&b, rng.below(sections as u64) as usize) } else { (0, 0) };
        let inside = off < b.len() && len >= 8 && off + len <= b.len();
        match rng.below(10) {
            // a word of a section set to something else
            0..=2 if inside => {
                let at = off + rng.below(len as u64 / 4) as usize * 4;
                let v = rng.word().to_le_bytes();
                b[at..at + 4].copy_from_slice(&v);
            }
            // two words of a section, the same number of records apart, swapped
            3..=5 if inside => {
                let (x, y) = (off + rng.below(len as u64 / 4) as usize * 4, off + rng.below(len as u64 / 4) as usize * 4);
                for i in 0..4 {
                    b.swap(x + i, y + i);
                }
            }
            // a word nudged up or down
            6 if inside => {
                let at = off + rng.below(len as u64 / 4) as usize * 4;
                let v = u32::from_le_bytes([b[at], b[at + 1], b[at + 2], b[at + 3]]).wrapping_add(rng.below(5) as u32).wrapping_sub(2);
                b[at..at + 4].copy_from_slice(&v.to_le_bytes());
            }
            // the header or the section table
            7 => {
                let at = rng.below((16 + sections * 16).min(b.len() - 4) as u64) as usize;
                let v = rng.word().to_le_bytes();
                b[at..at + 4].copy_from_slice(&v);
            }
            // cut short
            8 => {
                let keep = rng.below(b.len() as u64 + 1) as usize;
                b.truncate(keep.max(1));
            }
            // any byte anywhere
            _ => {
                let at = rng.below(b.len() as u64) as usize;
                b[at] = rng.next() as u8;
            }
        }
        if b.len() < 32 {
            break;
        }
    }
    b
}

/// A query from the generator: mostly sound, sometimes at the edges of every field.
pub fn query(tt: &Timetable, rng: &mut Rng) -> Query {
    let stops = tt.stops().max(1) as u64;
    let place = |rng: &mut Rng| match rng.below(8) {
        0 => Place::Stop(rng.word()),
        1 => Place::Station(rng.below(stops) as u32),
        2 => {
            let s = tt.stop(rng.below(stops) as u32);
            Place::Point { lat: s.lat.wrapping_add(rng.below(4000) as i32 - 2000), lon: s.lon.wrapping_add(rng.below(4000) as i32 - 2000) }
        }
        3 => Place::Point { lat: rng.next() as i32, lon: rng.next() as i32 },
        4 => Place::Walks((0..rng.below(6)).map(|_| (rng.below(stops + 2) as u32, rng.word())).collect()),
        _ => Place::Stop(rng.below(stops) as u32),
    };
    let (from, to) = (place(rng), place(rng));
    let mut q = Query::new(from, to, rng.below(tt.days() as u64 + 2) as i32 - 1, rng.below(100_000) as u32);
    if rng.below(8) == 0 {
        q.day = rng.next() as i32;
        q.time = rng.word();
    }
    q.window = [0, 0, 3600, 7200, rng.word()][rng.below(5) as usize];
    q.arrive_by = rng.below(3) == 0;
    q.max_rides = [8, 1, 2, 16, rng.word()][rng.below(5) as usize];
    q.reach_m = [1000, 0, 300, rng.word()][rng.below(4) as usize];
    q.walk_mm_s = [1250, 0, rng.word()][rng.below(3) as usize];
    q.change_s = [None, Some(0), Some(rng.word())][rng.below(3) as usize];
    q.rules = rng.below(4) != 0;
    q.limits = Limits { labels: 20_000, steps: 400_000, departures: [64, 1, rng.word()][rng.below(3) as usize], bag: rng.below(6) as u32, options: 1 + rng.below(8) as u32, horizon: [86_400, 0, rng.word()][rng.below(3) as usize] };
    for _ in 0..rng.below(4) {
        let how = match rng.below(3) {
            0 => How::Never,
            1 => How::Avoid(rng.word()),
            _ => How::Prefer(rng.word()),
        };
        let through = rng.below(2) == 0;
        let s = tt.stop(rng.below(stops) as u32);
        let what = match rng.below(12) {
            0 => What::Stop { stop: rng.below(stops + 1) as u32, through },
            1 => What::Station { station: rng.below(stops + 1) as u32, through },
            2 => What::Area { area: Area::Circle { lat: s.lat, lon: s.lon, radius_m: rng.word() }, through },
            3 => What::Area { area: Area::Box { south: s.lat - 50_000, west: s.lon - 50_000, north: s.lat + 50_000, east: s.lon + 50_000 }, through },
            4 => What::Area { area: Area::Outline((0..rng.below(6)).map(|_| (rng.next() as i32, rng.next() as i32)).collect()), through },
            5 => What::Line(rng.below(tt.lines() as u64 + 1) as u32),
            6 => What::Operator(rng.below(tt.operators() as u64 + 1) as u32),
            7 => What::Mode([Mode::Bus, Mode::Rail, Mode::Tram, Mode::Metro, Mode::Ferry][rng.below(5) as usize]),
            8 => What::Kind(String::from(["Intercity", "Sprinter", ""][rng.below(3) as usize])),
            9 => What::Trip(rng.below(tt.trips() as u64 + 1) as u32),
            10 => [What::NoWheelchair, What::WheelchairUnknown][rng.below(2) as usize].clone(),
            _ => [What::NoBicycles, What::BicyclesUnknown][rng.below(2) as usize].clone(),
        };
        q.wishes.push(Wish { what, how });
    }
    q
}

/// Opens the bytes and, if they open, asks `queries` journeys of them. Returns how many journeys came back, so the
/// work can't be optimised away; what matters is that it returns.
pub fn file(bytes: &[u8], rng: &mut Rng, queries: u32) -> u64 {
    let Ok(tt) = Timetable::open(bytes) else { return 0 };
    let mut planner = Planner::new(tt);
    let mut found = 1;
    for _ in 0..queries {
        let q = query(&tt, rng);
        let answer = planner.plan(&q);
        found += answer.journeys.len() as u64;
        for j in answer.journeys.iter() {
            assert!(j.legs.len() <= 64);
        }
    }
    // the strings and the rest of what is only shown
    let mut shown = 0usize;
    for s in (0..tt.stops()).take(64) {
        shown += tt.stop_text(s, crate::file::StopText::Name).len();
    }
    for t in (0..tt.trips()).take(64) {
        shown += tt.trip_text(t, crate::file::TripText::Number).len();
    }
    for i in (0..tt.rules()).take(64) {
        shown += tt.rule(i).kind as usize & 1;
    }
    shown += tt.meta_lines().count();
    found + shown as u64 % 2
}

/// One input of a fuzzer: the bytes as a file, asked a few journeys seeded by the bytes themselves.
pub fn one(bytes: &[u8]) {
    let mut seed = 0x9E37_79B9_7F4A_7C15u64;
    for chunk in bytes.chunks(8).take(64) {
        let mut v = [0u8; 8];
        v[..chunk.len()].copy_from_slice(chunk);
        seed = (seed ^ u64::from_le_bytes(v)).wrapping_mul(0x100_0000_01B3);
    }
    let mut rng = Rng(seed | 1);
    let _ = file(bytes, &mut rng, 3);
}
