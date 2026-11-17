//! The buildings' cleaning for the core's stage P, behind a C interface (load/CleanAbi.h is its other side): a
//! window's footprints in, on OSM's grid, and out what can stand there (crate::clean), each a ring that carries its
//! courtyards and pieces by seams (crate::bridge); and for stage T, which of a ring's edges are seams. Everything is
//! decided in integers on the grid, so the tiles come out bit for bit the same on every platform. The standard library
//! only.

#![deny(unsafe_op_in_unsafe_fn)]

pub mod arrange;
pub mod bridge;
pub mod clean;
pub mod geom;

use clean::{Footprint, Params};
use geom::P;
use std::ffi::c_void;

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
    pub unjoined: u32,
    pub uncovered_m2: f64,
    pub sliver_m2: f64,
}

/// Per footprint: whether it is drawn (STATE_DRAWN) and whether its ring changed (STATE_RING: the points from
/// ring_first[k] to ring_first[k + 1], clockwise on the grid as latitude and longitude are read, so counterclockwise
/// in the scene's metres); its base (raised where it stands on another). The library owns the arrays until
/// atlas_clean_free.
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
        unsafe { std::slice::from_raw_parts(ptr, len) }
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
        state: std::ptr::null(),
        min: std::ptr::null(),
        ring_first: std::ptr::null(),
        points: std::ptr::null(),
        counts: AtlasCleanCounts::default(),
        owner: std::ptr::null_mut(),
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
            unjoined: k.unjoined,
            uncovered_m2: k.uncovered_m2,
            sliver_m2: k.sliver_m2,
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
        out.owner = std::ptr::null_mut();
    }
}

/// For a closed ring of `count` points (x and z, each double's bits compared), writes into `out[i]` 1 where the edge
/// from point i to point i + 1 is a seam (its reverse is an edge of the ring too), else 0; returns how many are.
///
/// # Safety
/// `xz` points to 2 × `count` doubles and `out` to `count` bytes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn atlas_clean_seams(xz: *const f64, count: usize, out: *mut u8) -> usize {
    // SAFETY: the caller's contract
    let v = unsafe { slice(xz, 2 * count) };
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
