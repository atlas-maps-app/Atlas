//! The cleaning's bounds met, by the shapes fuzz_clean makes of its large footprints (cpp/fuzz/fuzz_clean.cpp's
//! `large`, and its seeds seed-crossing-combs, seed-tangles and seed-blind-courtyard): past each, what it was doing is
//! put back as mapped, and what it holds at once stays bounded.

use crate::arrange::{MAX_SEGMENTS, Op, Poly, boolean};
use crate::bridge::{BUDGET, bridge};
use crate::clean::{Footprint, Params, WORK, clean};
use crate::counted::peak;
use crate::geom::{P, area2};
use std::vec;
use std::vec::Vec;

/// The grid's metres where fuzz_clean puts its footprints, at the Westerkerk.
fn here() -> Params {
    Params::new(0.00679, 0.011054)
}

fn turned(mut r: Vec<P>, ccw: bool) -> Vec<P> {
    if (area2(&r) > 0) != ccw {
        r.reverse();
    }
    r
}

/// fuzz_clean's comb: `teeth` teeth `pitch` apart, each as long as the comb is wide and 40 more, across longitude
/// (along x) or latitude, from (x0, y0).
fn comb(teeth: i64, pitch: i64, x0: i64, y0: i64, across_lat: bool) -> Vec<P> {
    let span = 2 * teeth * pitch;
    let length = span + 40;
    let at = |u: i64, v: i64| if across_lat { P::new(x0 + v, y0 + u) } else { P::new(x0 + u, y0 + v) };
    let mut r = vec![at(0, 0)];
    for k in 0..teeth {
        r.push(at((2 * k + 1) * pitch, length));
        r.push(at((2 * k + 2) * pitch, 0));
    }
    r.push(at(span, -1));
    r
}

/// fuzz_clean's tangle: `count` points whose first and third edges cross, counterclockwise, in strip `place`.
fn tangle(count: i64, place: i64) -> Vec<P> {
    let (x0, y0) = (-32000, -32768 + 2730 * (place % 24));
    let mut r = vec![P::new(x0, y0), P::new(x0 + 10, y0 + 10), P::new(x0 + 10, y0), P::new(x0, y0 + 10)];
    for k in 4..count - 2 {
        r.push(P::new(x0 + 8 + k, y0 + 20 + (k % 2) * 2000));
    }
    r.push(P::new(x0 + 8 + count - 3, y0 + 2100));
    r.push(P::new(x0 - 2, y0 + 2100));
    r
}

/// fuzz_clean's footprint whose courtyard sees none of the `dense` points of its outer ring nearest it: its outer
/// ring, the wall between them, the courtyard (each courtyard past the cleaning's least, Params::hole_m2).
fn blind(dense: i64, pitch: i64) -> Vec<Vec<P>> {
    let away = dense * pitch + 2000;
    let mut outer = vec![P::new(-away, 0)];
    outer.extend((1..=dense).map(|k| P::new(k * pitch, k % 2)));
    outer.push(P::new(dense * pitch + 1, away));
    outer.push(P::new(-away, away));
    let wall = vec![P::new(0, 2), P::new(150, 2), P::new(150, 1000), P::new(0, 1000)];
    let courtyard = vec![P::new(-160, 400), P::new(-10, 400), P::new(-10, 550), P::new(-160, 550)];
    vec![outer, wall, courtyard]
}

fn footprint(rings: Vec<Vec<P>>, top: f64) -> Footprint {
    Footprint { rings, min: 0.0, top, part: false }
}

#[test]
fn two_combs_that_cross_tooth_by_tooth_are_given_up_inside_the_round() {
    // two rings of 3,000 edges, together at MAX_SEGMENTS, every tooth of one across every tooth of the other: 9
    // million crossings, which split into 18 million pieces of 40 bytes before the round's end
    let a = comb(1499, 2, 0, -20, false);
    let b = turned(comb(1499, 2, -20, 0, true), true);
    assert_eq!((a.len(), b.len()), (3000, 3000));
    assert_eq!(a.len() + b.len(), MAX_SEGMENTS);
    let (r, bytes) = peak(|| boolean(std::slice::from_ref(&a), std::slice::from_ref(&b), Op::Inter));
    assert!(r.is_none());
    std::println!("two combs' arrangement: given up, {bytes} bytes held at once at most");
    assert!(bytes < 4 << 20, "{bytes} bytes held at once");
}

#[test]
fn two_combs_that_cross_come_back_as_mapped_within_a_bounded_memory() {
    let fps =
        vec![footprint(vec![comb(1499, 2, 0, -20, false)], 10.0), footprint(vec![comb(1499, 2, -20, 0, true)], 15.0)];
    let (c, bytes) = peak(|| clean(&fps, here()));
    assert!(c.counts.unsettled >= 1);
    assert!(c.ring.iter().all(Option::is_none), "as mapped");
    assert!(c.drawn.iter().all(|&d| d));
    assert_eq!(c.min, vec![0.0, 0.0]);
    std::println!("two combs cleaned: as mapped, unsettled {}, {bytes} bytes held at once at most", c.counts.unsettled);
    assert!(bytes < 16 << 20, "{bytes} bytes held at once");
}

#[test]
fn twelve_tangles_spend_the_work() {
    // each costs 1.5 × 65,535² of the work (its crossing found, its lobes' arrangement refused past MAX_SEGMENTS)
    let fps: Vec<Footprint> = (0..12).map(|k| footprint(vec![tangle(65_535, k)], 10.0)).collect();
    assert!(fps.iter().all(|f| f.rings[0].len() == 65_535 && area2(&f.rings[0]) > 0), "as the cleaning takes them");
    let c = clean(&fps, here());
    assert!(c.counts.unsettled >= 1);
    // what was left was less than the next operation asked for
    assert!(c.counts.work > WORK - 7_000_000_000, "{} of {WORK}", c.counts.work);
    assert!(c.ring.iter().all(Option::is_none), "as mapped");
    assert!(c.drawn.iter().all(|&d| d));
}

#[test]
fn a_courtyard_that_sees_none_of_the_points_nearest_it_spends_the_budget() {
    // joining the courtyard tries each point nearer than the outer ring's far corners first, at the cost of every
    // edge: 3,000 of them × 3,011 edges is within BUDGET, and it is joined to a far corner; 5,000 × 5,011 is past it
    for (dense, joined) in [(3000, true), (5000, false)] {
        let rings = blind(dense, 1);
        let polys = vec![Poly {
            outer: turned(rings[0].clone(), true),
            holes: rings[1..].iter().map(|h| turned(h.clone(), false)).collect(),
        }];
        let tries = dense as u64 * rings.iter().map(|r| r.len() as u64).sum::<u64>();
        assert_eq!(tries <= BUDGET, joined);
        assert_eq!(bridge(&polys).is_some(), joined, "{dense} points");
    }
    // so the footprint stands as mapped, its outer ring alone
    let c = clean(&[footprint(blind(5000, 1), 10.0)], here());
    assert!(c.counts.unsettled >= 1);
    assert!(c.ring[0].is_none());
    assert!(c.drawn[0]);
}
