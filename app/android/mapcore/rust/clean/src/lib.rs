//! The buildings' cleaning for the core's stage P, behind a C interface (load/CleanAbi.h is its other side): a
//! window's footprints in, on OSM's grid, and out what can stand there (crate::clean), each a ring that carries its
//! courtyards and pieces by seams (crate::bridge); and for stage T, which of a ring's edges are seams. Everything is
//! decided in integers on the grid, so the tiles come out bit for bit the same on every platform. The standard library
//! only.

#![deny(unsafe_op_in_unsafe_fn)]
// The core and alloc alone, so that the standard library's panic and backtrace machinery is never linked (it was
// 1.5 MB of each ABI's libatlasmap.so): a panic aborts as the core's asserts do, and memory comes from the C library's
// malloc. Its tests and examples (feature `std`) have the standard library.
#![cfg_attr(not(any(test, feature = "std")), no_std)]

extern crate alloc;

pub mod arrange;
pub mod bridge;
pub mod clean;
pub mod geom;
#[cfg(test)]
mod bounds;
#[cfg(test)]
mod props;

use alloc::boxed::Box;
use alloc::vec::Vec;
use clean::{Footprint, Params};
use core::ffi::c_void;
use geom::P;

#[cfg(not(any(test, feature = "std")))]
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
        #[cfg(target_os = "android")]
        fn android_set_abort_message(message: *const core::ffi::c_char);
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

    /// The unwinder's personality the prebuilt compiler_builtins names (it was built to unwind); never called, as a
    /// panic aborts.
    #[unsafe(no_mangle)]
    extern "C" fn rust_eh_personality() {}

    /// Writes `bytes` to the standard error, as the core's asserts do before they abort.
    fn say(bytes: &[u8]) {
        // SAFETY: a buffer of its length
        let _ = unsafe { write(2, bytes.as_ptr(), bytes.len()) };
    }

    #[panic_handler]
    fn panic(info: &core::panic::PanicInfo) -> ! {
        let mut message = [0u8; crate::PANIC_MESSAGE];
        let n = crate::panic_message(info.location().map(|at| (at.file().as_bytes(), at.line())), &mut message);
        say(&message[..n]);
        say(b"\n");
        // what a phone's crash report carries (the tombstone's abort message), as the core's fail sets it
        #[cfg(target_os = "android")]
        // SAFETY: a string ended by a NUL, which bionic copies
        unsafe {
            android_set_abort_message(message.as_ptr().cast())
        };
        // SAFETY: the C library's abort
        unsafe { abort() }
    }
}

/// The bytes a panic's message takes, its NUL with them.
pub const PANIC_MESSAGE: usize = 256;

/// What a panic says, to the standard error and on Android as the abort message a crash report carries: `atlas_clean:
/// panic at <file>:<line>` (without a place, `atlas_clean: panic`), written into `out` with a NUL after it (the
/// file cut short where the whole does not fit); its length without the NUL. Without the formatting machinery,
/// which the library leaves out.
pub fn panic_message(at: Option<(&[u8], u32)>, out: &mut [u8; PANIC_MESSAGE]) -> usize {
    let mut digits = [0u8; 10];
    let mut k = digits.len();
    let mut n = 0;
    let mut put = |bytes: &[u8], room: usize, n: &mut usize| {
        for &b in bytes.iter().take(room.saturating_sub(*n)) {
            out[*n] = b;
            *n += 1;
        }
    };
    let last = PANIC_MESSAGE - 1;
    match at {
        None => put(b"atlas_clean: panic", last, &mut n),
        Some((file, line)) => {
            let mut v = line;
            loop {
                k -= 1;
                digits[k] = b'0' + (v % 10) as u8;
                v /= 10;
                if v == 0 || k == 0 {
                    break;
                }
            }
            let line = &digits[k..];
            put(b"atlas_clean: panic at ", last, &mut n);
            // the file as much as leaves room for the line
            put(file, last - 1 - line.len(), &mut n);
            put(b":", last, &mut n);
            put(line, last, &mut n);
        }
    }
    out[n] = 0;
    n
}

/// The C library's allocator, counted on each thread, for tests that bound what an operation holds at once.
#[cfg(test)]
pub(crate) mod counted {
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::cell::Cell;

    std::thread_local! {
        static NOW: Cell<isize> = const { Cell::new(0) };
        static PEAK: Cell<isize> = const { Cell::new(0) };
    }

    fn add(bytes: isize) {
        let _ = NOW.try_with(|now| {
            let v = now.get() + bytes;
            now.set(v);
            let _ = PEAK.try_with(|peak| peak.set(peak.get().max(v)));
        });
    }

    struct Counted;

    // SAFETY: the system's allocator, each call passed on as it came
    unsafe impl GlobalAlloc for Counted {
        unsafe fn alloc(&self, l: Layout) -> *mut u8 {
            // SAFETY: passed on
            let p = unsafe { System.alloc(l) };
            if !p.is_null() {
                add(l.size() as isize);
            }
            p
        }
        unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
            // SAFETY: passed on
            unsafe { System.dealloc(p, l) };
            add(-(l.size() as isize));
        }
        unsafe fn realloc(&self, p: *mut u8, l: Layout, size: usize) -> *mut u8 {
            // SAFETY: passed on
            let q = unsafe { System.realloc(p, l, size) };
            if !q.is_null() {
                add(size as isize - l.size() as isize);
            }
            q
        }
    }

    #[global_allocator]
    static COUNTED: Counted = Counted;

    /// What `f` gives, and the most bytes this thread held at once while it ran, past what it held before.
    pub fn peak<R>(f: impl FnOnce() -> R) -> (R, usize) {
        let before = NOW.with(Cell::get);
        PEAK.with(|p| p.set(before));
        let r = f();
        (r, (PEAK.with(Cell::get) - before) as usize)
    }
}

/// A grid point: latitude and longitude in 1e-7 degrees.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct AtlasCleanPoint {
    pub lat: i32,
    pub lon: i32,
}

/// A footprint: its rings (`rings` of them from `first_ring`: its outer ring, then its inner rings), the heights its
/// walls span (metres) and whether it is a building:part.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct AtlasCleanFootprint {
    pub first_ring: u32,
    pub rings: u32,
    pub min: f64,
    pub top: f64,
    pub part: u8,
    pub pad: [u8; 7],
}

/// A window's footprints: the rings' points one after another, each ring's count, the footprints; and the grid's
/// units in metres where the window lies.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct AtlasCleanInput {
    pub points: *const AtlasCleanPoint,
    pub point_count: usize,
    pub ring_sizes: *const u32,
    pub ring_count: usize,
    pub footprints: *const AtlasCleanFootprint,
    pub footprint_count: usize,
    pub metres_per_lon: f64,
    pub metres_per_lat: f64,
}

/// What the cleaning counts (crate::clean::Counts).
#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct AtlasCleanCounts {
    pub overlapping_pairs: u32,
    pub uncovered_outlines: u32,
    pub courtyards: u32,
    pub covered: u32,
    pub hidden: u32,
    pub clipped: u32,
    pub raised: u32,
    pub vanished: u32,
    pub seamed: u32,
    pub unsettled: u32,
    pub unmeasured: u32,
    pub uncovered_m2: f64,
    pub sliver_m2: f64,
    pub dropped_m2: f64,
    pub pairs_before: u32,
    pub pad: u32,
}

/// Per footprint: whether it is drawn (STATE_DRAWN) and whether its ring changed (STATE_RING: the points from
/// ring_first[k] to ring_first[k + 1], clockwise on the grid as latitude and longitude are read, so counterclockwise
/// in the scene's metres), and what changed it (STATE_COVERED: its parts cut it; STATE_CLIPPED: another footprint
/// did; STATE_COURTYARD: it has courtyards as mapped); its base (raised where it stands on another). The library owns
/// the arrays until atlas_clean_free.
#[repr(C)]
#[derive(Debug)]
pub struct AtlasCleanOutput {
    pub state: *const u8,
    pub min: *const f64,
    pub ring_first: *const usize,
    pub points: *const AtlasCleanPoint,
    pub counts: AtlasCleanCounts,
    pub owner: *mut c_void,
}

pub const STATE_DRAWN: u8 = 1;
pub const STATE_RING: u8 = 2;
pub const STATE_COVERED: u8 = 4;
pub const STATE_CLIPPED: u8 = 8;
pub const STATE_COURTYARD: u8 = 16;

// the sizes load/CleanAbi.h's structs have on a 64-bit target (load/Clean.cpp asserts the same of them)
const _: () = assert!(core::mem::size_of::<AtlasCleanPoint>() == 8);
const _: () = assert!(core::mem::size_of::<AtlasCleanFootprint>() == 32);
const _: () = assert!(core::mem::size_of::<AtlasCleanInput>() == 64);
const _: () = assert!(core::mem::size_of::<AtlasCleanCounts>() == 80);
const _: () = assert!(core::mem::size_of::<AtlasCleanOutput>() == 120);

struct Owned {
    state: Vec<u8>,
    min: Vec<f64>,
    ring_first: Vec<usize>,
    points: Vec<AtlasCleanPoint>,
}

/// # Safety
/// `ptr` points to `len` readable values, or `len` is 0.
unsafe fn slice<'a, T>(ptr: *const T, len: usize) -> &'a [T] {
    if len == 0 || ptr.is_null() {
        &[]
    } else {
        // SAFETY: the caller's contract
        unsafe { core::slice::from_raw_parts(ptr, len) }
    }
}

/// The footprints of an input, or None where its counts do not add up.
pub fn footprints(points: &[AtlasCleanPoint], sizes: &[u32], fps: &[AtlasCleanFootprint]) -> Option<Vec<Footprint>> {
    let mut first = Vec::with_capacity(sizes.len() + 1);
    let mut at = 0usize;
    for &s in sizes {
        first.push(at);
        at = at.checked_add(s as usize)?;
    }
    first.push(at);
    if at != points.len() {
        return None;
    }
    let mut out = Vec::with_capacity(fps.len());
    for f in fps {
        let (r0, rn) = (f.first_ring as usize, f.rings as usize);
        if r0.checked_add(rn)? > sizes.len() {
            return None;
        }
        let rings = (r0..r0 + rn)
            .map(|r| points[first[r]..first[r + 1]].iter().map(|q| P::new(q.lon as i64, q.lat as i64)).collect())
            .collect();
        out.push(Footprint { rings, min: f.min, top: f.top, part: f.part != 0 });
    }
    Some(out)
}

/// Cleans a window's footprints into `out`. 0 done; 1 an input whose counts do not add up (`out` then holds nothing).
///
/// # Safety
/// `input` and `out` point to valid values, and the input's arrays hold the counts it says.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_clean_run(input: *const AtlasCleanInput, out: *mut AtlasCleanOutput) -> i32 {
    // SAFETY: the caller's contract
    let (input, out) = unsafe { (&*input, &mut *out) };
    // SAFETY: the caller's contract
    let (points, sizes, fps) = unsafe {
        (
            slice(input.points, input.point_count),
            slice(input.ring_sizes, input.ring_count),
            slice(input.footprints, input.footprint_count),
        )
    };
    *out = AtlasCleanOutput {
        state: core::ptr::null(),
        min: core::ptr::null(),
        ring_first: core::ptr::null(),
        points: core::ptr::null(),
        counts: AtlasCleanCounts::default(),
        owner: core::ptr::null_mut(),
    };
    let Some(footprints) = footprints(points, sizes, fps) else { return 1 };
    let c = clean::clean(&footprints, Params::new(input.metres_per_lon, input.metres_per_lat));
    let mut o = Owned {
        state: Vec::with_capacity(fps.len()),
        min: c.min,
        ring_first: Vec::with_capacity(fps.len() + 1),
        points: Vec::new(),
    };
    for k in 0..fps.len() {
        o.ring_first.push(o.points.len());
        let mut s = if c.drawn[k] { STATE_DRAWN } else { 0 };
        if let Some(r) = &c.ring[k] {
            s |= STATE_RING;
            o.points.extend(r.iter().rev().map(|p| AtlasCleanPoint { lat: p.y as i32, lon: p.x as i32 }));
        }
        for (what, state) in
            [(clean::COVERED, STATE_COVERED), (clean::CLIPPED, STATE_CLIPPED), (clean::COURTYARD, STATE_COURTYARD)]
        {
            if c.what[k] & what != 0 {
                s |= state;
            }
        }
        o.state.push(s);
    }
    o.ring_first.push(o.points.len());
    let k = c.counts;
    let owned = Box::new(o);
    *out = AtlasCleanOutput {
        state: owned.state.as_ptr(),
        min: owned.min.as_ptr(),
        ring_first: owned.ring_first.as_ptr(),
        points: owned.points.as_ptr(),
        counts: AtlasCleanCounts {
            overlapping_pairs: k.overlapping_pairs,
            uncovered_outlines: k.uncovered_outlines,
            courtyards: k.courtyards,
            covered: k.covered,
            hidden: k.hidden,
            clipped: k.clipped,
            raised: k.raised,
            vanished: k.vanished,
            seamed: k.seamed,
            unsettled: k.unsettled,
            unmeasured: k.unmeasured,
            uncovered_m2: k.uncovered_m2,
            sliver_m2: k.sliver_m2,
            dropped_m2: k.dropped_m2,
            pairs_before: k.pairs_before,
            pad: 0,
        },
        owner: Box::into_raw(owned).cast(),
    };
    0
}

/// Frees what atlas_clean_run made (nothing where it made nothing).
///
/// # Safety
/// `out` is an output atlas_clean_run filled, freed at most once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_clean_free(out: *mut AtlasCleanOutput) {
    // SAFETY: the caller's contract
    let out = unsafe { &mut *out };
    if !out.owner.is_null() {
        // SAFETY: owner is the Box atlas_clean_run leaked
        drop(unsafe { Box::from_raw(out.owner.cast::<Owned>()) });
        out.owner = core::ptr::null_mut();
    }
}

/// For a closed ring of `count` points (x and z, each double's bits compared), writes into `out[i]` 1 where the edge
/// from point i to point i + 1 is a seam (its reverse is an edge of the ring too), else 0; returns how many are.
///
/// # Safety
/// `xz` points to 2 × `count` doubles and `out` to `count` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_clean_seams(xz: *const f64, count: usize, out: *mut u8) -> usize {
    let Some(doubles) = count.checked_mul(2) else { return 0 };
    // SAFETY: the caller's contract
    let v = unsafe { slice(xz, doubles) };
    let keys: Vec<(u64, u64)> = (0..count).map(|i| (v[2 * i].to_bits(), v[2 * i + 1].to_bits())).collect();
    let s = bridge::seams(&keys);
    let mut n = 0;
    for (i, &b) in s.iter().enumerate() {
        // SAFETY: the caller's contract
        unsafe { *out.add(i) = b as u8 };
        n += b as usize;
    }
    n
}

/// A seamed ring's rings again (crate::bridge::rings): every ring's points one after another, each ring's first point
/// (ring_count + 1 offsets), and its piece (a piece's first ring its outer, then its courtyards). The rings run as
/// the tiles hold them: an outer ring clockwise as latitude and longitude are read, a courtyard the other way.
#[repr(C)]
#[derive(Debug)]
pub struct AtlasCleanRings {
    pub points: *const AtlasCleanPoint,
    pub ring_first: *const usize,
    pub piece: *const u32,
    pub ring_count: usize,
    pub owner: *mut c_void,
}

struct OwnedRings {
    points: Vec<AtlasCleanPoint>,
    ring_first: Vec<usize>,
    piece: Vec<u32>,
}

/// The rings of the closed ring of `count` points (as the tiles hold a building's) into `out`: 0 done; 1 a ring the
/// cleaning did not make (`out` then holds nothing).
///
/// # Safety
/// `ring` points to `count` points and `out` to a value to fill.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_clean_rings(ring: *const AtlasCleanPoint, count: usize, out: *mut AtlasCleanRings) -> i32 {
    // SAFETY: the caller's contract
    let (v, out) = unsafe { (slice(ring, count), &mut *out) };
    *out = AtlasCleanRings {
        points: core::ptr::null(),
        ring_first: core::ptr::null(),
        piece: core::ptr::null(),
        ring_count: 0,
        owner: core::ptr::null_mut(),
    };
    // counterclockwise for the walk, as the cleaning makes it; back as the tiles hold it after
    let ccw: Vec<P> = v.iter().rev().map(|q| P::new(q.lon as i64, q.lat as i64)).collect();
    let Some(polys) = bridge::rings(&ccw) else { return 1 };
    let mut o = OwnedRings { points: Vec::with_capacity(v.len()), ring_first: Vec::new(), piece: Vec::new() };
    for (k, poly) in polys.iter().enumerate() {
        for r in core::iter::once(&poly.outer).chain(poly.holes.iter()) {
            o.ring_first.push(o.points.len());
            o.piece.push(k as u32);
            o.points.extend(r.iter().rev().map(|p| AtlasCleanPoint { lat: p.y as i32, lon: p.x as i32 }));
        }
    }
    o.ring_first.push(o.points.len());
    let owned = Box::new(o);
    *out = AtlasCleanRings {
        points: owned.points.as_ptr(),
        ring_first: owned.ring_first.as_ptr(),
        piece: owned.piece.as_ptr(),
        ring_count: owned.piece.len(),
        owner: Box::into_raw(owned).cast(),
    };
    0
}

/// Frees what atlas_clean_rings made.
///
/// # Safety
/// `out` is an output atlas_clean_rings filled, freed at most once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_clean_rings_free(out: *mut AtlasCleanRings) {
    // SAFETY: the caller's contract
    let out = unsafe { &mut *out };
    if !out.owner.is_null() {
        // SAFETY: owner is the Box atlas_clean_rings leaked
        drop(unsafe { Box::from_raw(out.owner.cast::<OwnedRings>()) });
        out.owner = core::ptr::null_mut();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_panic_says_where_as_a_phone_shows_it() {
        // the message a phone's crash report carries for a panic here, as the handler writes it
        let at = core::panic::Location::caller();
        let mut out = [0xFFu8; PANIC_MESSAGE];
        let n = panic_message(Some((at.file().as_bytes(), at.line())), &mut out);
        let message = std::format!("atlas_clean: panic at {}:{}", at.file(), at.line());
        std::println!("abort message: {}", core::str::from_utf8(&out[..n]).unwrap());
        assert_eq!(&out[..n], message.as_bytes());
        assert_eq!(out[n], 0);
        // a file too long for it is cut, and its line kept
        let long = [b'x'; 400];
        let n = panic_message(Some((&long, 4_294_967_295)), &mut out);
        assert_eq!(n, PANIC_MESSAGE - 1);
        assert!(out[..n].ends_with(b"xx:4294967295"));
        assert_eq!(out[n], 0);
        let n = panic_message(None, &mut out);
        assert_eq!(&out[..=n], b"atlas_clean: panic\0");
    }
}
