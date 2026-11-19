//! The cleaning's bounds met: past each, what it was doing is put back as mapped, and what it holds at once stays
//! bounded.

use crate::arrange::{boolean, Op, MAX_SEGMENTS};
use crate::clean::{clean, Footprint, Params};
use crate::counted::peak;
use crate::geom::{area2, P};
use std::vec;
use std::vec::Vec;

/// The grid's metres at the Westerkerk.
fn here() -> Params {
    Params::new(0.00679, 0.011054)
}

fn turned(mut r: Vec<P>, ccw: bool) -> Vec<P> {
    if (area2(&r) > 0) != ccw {
        r.reverse();
    }
    r
}

/// A comb: `teeth` teeth `pitch` apart, each as long as the comb is wide and 40 more, across longitude
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
    let (r, bytes) = peak(|| boolean(&[a.clone()], &[b.clone()], Op::Inter));
    assert!(r.is_none());
    std::println!("two combs' arrangement: given up, {bytes} bytes held at once at most");
    assert!(bytes < 4 << 20, "{bytes} bytes held at once");
}

#[test]
fn two_combs_that_cross_come_back_as_mapped_within_a_bounded_memory() {
    let fps = vec![
        footprint(vec![comb(1499, 2, 0, -20, false)], 10.0),
        footprint(vec![comb(1499, 2, -20, 0, true)], 15.0),
    ];
    let (c, bytes) = peak(|| clean(&fps, here()));
    assert!(c.counts.unsettled >= 1);
    assert!(c.ring.iter().all(Option::is_none), "as mapped");
    assert!(c.drawn.iter().all(|&d| d));
    assert_eq!(c.min, vec![0.0, 0.0]);
    std::println!("two combs cleaned: as mapped, unsettled {}, {bytes} bytes held at once at most", c.counts.unsettled);
    assert!(bytes < 16 << 20, "{bytes} bytes held at once");
}
