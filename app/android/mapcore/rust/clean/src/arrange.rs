//! Boolean operations on footprints by their arrangement: every edge of two sets of rings, A and B, split where it
//! meets another (a crossing at the grid point nearest to it, so every vertex stays on OSM's grid), the winding
//! numbers of A and B found on each side of every piece, and the pieces where the result's inside meets its outside
//! walked into rings. A is read by the even-odd rule (a footprint's own rings: its outer and its courtyards, as mapped),
//! B by the nonzero rule (shapes this crate made: outers counterclockwise, holes clockwise).

use crate::geom::{
    area2, cross, crossing, dot, proper_cross, strictly_inside_segment, winding_scaled, Where, P,
};
use alloc::vec;
use alloc::vec::Vec;

/// A piece of an edge, with what it adds to the winding numbers of A and B on its left.
#[derive(Clone, Copy, Debug)]
struct Seg {
    a: P,
    b: P,
    w: [i32; 2],
}

/// What a boolean operation keeps.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Op {
    /// A alone, made valid.
    Norm,
    /// A less B.
    Diff,
    /// A and B.
    Inter,
}

/// A polygon: its outer ring counterclockwise and its holes clockwise, none closing itself.
#[derive(Clone, PartialEq, Eq, Debug, Default)]
pub struct Poly {
    pub outer: Vec<P>,
    pub holes: Vec<Vec<P>>,
}

impl Poly {
    /// Twice its area.
    pub fn area2(&self) -> i128 {
        area2(&self.outer) + self.holes.iter().map(|h| area2(h)).sum::<i128>()
    }
}

/// The rings of polygons, as B takes them.
pub fn rings_of(polys: &[Poly]) -> Vec<Vec<P>> {
    let mut out = Vec::new();
    for p in polys {
        out.push(p.outer.clone());
        out.extend(p.holes.iter().cloned());
    }
    out
}

/// Twice the area of polygons.
pub fn area2_of(polys: &[Poly]) -> i128 {
    polys.iter().map(Poly::area2).sum()
}

/// Rounds of splitting before the arrangement is taken as one that does not settle.
const ROUNDS: usize = 24;

/// The most pieces of edges an arrangement takes: past it, it is taken as one that does not settle (its winding
/// numbers cost the square of its pieces).
pub const MAX_SEGMENTS: usize = 6000;

fn segments(a: &[Vec<P>], b: &[Vec<P>]) -> Vec<Seg> {
    let mut out = Vec::new();
    for (label, rings) in [(0usize, a), (1usize, b)] {
        for r in rings {
            let n = r.len();
            if n < 3 {
                continue;
            }
            for i in 0..n {
                let p = r[i];
                let q = r[(i + 1) % n];
                if p == q {
                    continue;
                }
                let mut w = [0, 0];
                w[label] = 1;
                out.push(Seg { a: p, b: q, w });
            }
        }
    }
    out
}

/// A segment's split points as its pieces take them: its ends left out, the rest in order along it, each once. The
/// same however often it is done, so a round may settle its points before it ends.
fn settle(pts: &mut Vec<P>, s: &Seg) {
    pts.retain(|&p| p != s.a && p != s.b);
    pts.sort_by_key(|&p| (dot(s.a, p, s.b), p));
    pts.dedup();
}

/// Splits the segments where they cross or touch until none does: a crossing at its nearest grid point, a touch at
/// the vertex that touches. None where it does not settle: past ROUNDS, or past MAX_SEGMENTS pieces. A round gives up
/// as soon as its splits make more pieces than that, rather than at the next round's start: the pieces it would
/// make are counted as it splits (its points settled once they could pass the limit, and again each time MAX_SEGMENTS
/// more have come), so what a round holds stays within a few times the limit whatever it is given. Two footprints of
/// 3,000 edges that all cross would otherwise split into 18 million pieces, about a gigabyte, before the next
/// round's test sent them back as mapped. It gives up on what the next round would have: the result is the same.
fn node(mut segs: Vec<Seg>) -> Option<Vec<Seg>> {
    for _ in 0..ROUNDS {
        let n = segs.len();
        if n > MAX_SEGMENTS {
            return None;
        }
        let mut order: Vec<usize> = (0..n).collect();
        order.sort_by_key(|&i| (segs[i].a.x.min(segs[i].b.x), i));
        let mut splits: Vec<Vec<P>> = vec![Vec::new(); n];
        let mut any = false;
        // the points splits holds, and how many it may hold before they are settled and the pieces counted
        let mut held = 0usize;
        let mut count_at = MAX_SEGMENTS - n;
        for oi in 0..n {
            let i = order[oi];
            let s = segs[i];
            let (sx1, sy0, sy1) = (s.a.x.max(s.b.x), s.a.y.min(s.b.y), s.a.y.max(s.b.y));
            for &j in &order[oi + 1..] {
                let t = segs[j];
                if t.a.x.min(t.b.x) > sx1 {
                    break;
                }
                if t.a.y.max(t.b.y) < sy0 || t.a.y.min(t.b.y) > sy1 {
                    continue;
                }
                if proper_cross(s.a, s.b, t.a, t.b) {
                    let p = crossing(s.a, s.b, t.a, t.b);
                    splits[i].push(p);
                    splits[j].push(p);
                    held += 2;
                    any = true;
                    continue;
                }
                for (p, on, k) in [(t.a, s, i), (t.b, s, i), (s.a, t, j), (s.b, t, j)] {
                    if strictly_inside_segment(p, on.a, on.b) {
                        splits[k].push(p);
                        held += 1;
                        any = true;
                    }
                }
            }
            if held > count_at {
                // a segment of k points becomes k + 1 pieces
                held = 0;
                for (k, pts) in splits.iter_mut().enumerate() {
                    settle(pts, &segs[k]);
                    held += pts.len();
                }
                if n + held > MAX_SEGMENTS {
                    return None;
                }
                count_at = held + MAX_SEGMENTS;
            }
        }
        if !any {
            return Some(segs);
        }
        let mut out = Vec::with_capacity(n + n / 2);
        for (i, s) in segs.iter().enumerate() {
            let mut pts = core::mem::take(&mut splits[i]);
            if pts.is_empty() {
                out.push(*s);
                continue;
            }
            settle(&mut pts, s);
            let mut prev = s.a;
            for p in pts {
                if p != prev {
                    out.push(Seg { a: prev, b: p, w: s.w });
                    prev = p;
                }
            }
            if prev != s.b {
                out.push(Seg { a: prev, b: s.b, w: s.w });
            }
        }
        segs = out;
    }
    None
}

/// The segments each from its lesser end to its greater (its weights turned with it), the same ones merged, those that
/// add nothing dropped.
fn merge(segs: Vec<Seg>) -> Vec<Seg> {
    let mut c: Vec<Seg> = segs
        .into_iter()
        .filter(|s| s.a != s.b)
        .map(|s| if s.a < s.b { s } else { Seg { a: s.b, b: s.a, w: [-s.w[0], -s.w[1]] } })
        .collect();
    c.sort_by_key(|s| (s.a, s.b));
    let mut out: Vec<Seg> = Vec::with_capacity(c.len());
    for s in c {
        if let Some(last) = out.last_mut() {
            if last.a == s.a && last.b == s.b {
                last.w[0] += s.w[0];
                last.w[1] += s.w[1];
                continue;
            }
        }
        out.push(s);
    }
    out.retain(|s| s.w != [0, 0]);
    out
}

/// The winding numbers of A and B on the left and on the right of each segment (from its lesser end to its greater).
fn sides(segs: &[Seg]) -> Vec<([i32; 2], [i32; 2])> {
    // each segment's rise, doubled: lower end, upper end, and +1 where it runs upward from its lesser end
    struct Rise {
        lo: P,
        hi: P,
        w: [i32; 2],
    }
    let mut rises: Vec<Rise> = Vec::with_capacity(segs.len());
    let mut index: Vec<usize> = Vec::with_capacity(segs.len());
    for (k, s) in segs.iter().enumerate() {
        let a = P::new(2 * s.a.x, 2 * s.a.y);
        let b = P::new(2 * s.b.x, 2 * s.b.y);
        if a.y < b.y {
            rises.push(Rise { lo: a, hi: b, w: s.w });
        } else if a.y > b.y {
            rises.push(Rise { lo: b, hi: a, w: [-s.w[0], -s.w[1]] });
        } else {
            continue;
        }
        index.push(k);
    }
    let mut out = Vec::with_capacity(segs.len());
    for (k, s) in segs.iter().enumerate() {
        let m = P::new(s.a.x + s.b.x, s.a.y + s.b.y);
        let mut w = [0i32, 0i32];
        for (r, &rk) in rises.iter().zip(&index) {
            if rk == k {
                continue;
            }
            if r.lo.y <= m.y && m.y < r.hi.y && cross(r.lo, r.hi, m) > 0 {
                w[0] += r.w[0];
                w[1] += r.w[1];
            }
        }
        // w is the winding just past the segment towards +x (above it, for a level one)
        let left_side = if s.a.y == s.b.y { true } else { s.b.y < s.a.y };
        if left_side {
            out.push((w, [w[0] - s.w[0], w[1] - s.w[1]]));
        } else {
            out.push(([w[0] + s.w[0], w[1] + s.w[1]], w));
        }
    }
    out
}

fn inside(op: Op, w: [i32; 2]) -> bool {
    let a = w[0] & 1 != 0;
    match op {
        Op::Norm => a,
        Op::Diff => a && w[1] == 0,
        Op::Inter => a && w[1] != 0,
    }
}

/// Where `o` comes after `p` going counterclockwise from `d` (none of them zero).
fn later_ccw(d: P, o: P, p: P) -> bool {
    let z = P::new(0, 0);
    let half = |v: P| -> u8 {
        let c = cross(z, d, v);
        if c > 0 || (c == 0 && dot(z, d, v) > 0) {
            0
        } else {
            1
        }
    };
    let (ho, hp) = (half(o), half(p));
    if ho != hp {
        return ho > hp;
    }
    cross(z, p, o) > 0
}

/// The half-edges walked into rings: at each vertex the next half-edge is the one turning most clockwise from the
/// way back, so that rings that touch at a vertex part there.
fn walk(half: &[(P, P)]) -> Option<Vec<Vec<P>>> {
    // the half-edges by the vertex they leave
    let mut by: Vec<usize> = (0..half.len()).collect();
    by.sort_unstable_by_key(|&k| (half[k].0, k));
    let leaving = |v: P| -> &[usize] {
        let lo = by.partition_point(|&k| half[k].0 < v);
        let hi = by.partition_point(|&k| half[k].0 <= v);
        &by[lo..hi]
    };
    let mut used = vec![false; half.len()];
    let mut rings = Vec::new();
    for start in 0..half.len() {
        if used[start] {
            continue;
        }
        let mut ring = Vec::new();
        let mut cur = start;
        loop {
            if used[cur] {
                return None;
            }
            used[cur] = true;
            let (u, v) = half[cur];
            ring.push(u);
            let back = P::new(u.x - v.x, u.y - v.y);
            let outs = leaving(v);
            let mut best: Option<usize> = None;
            for &o in outs {
                let dir = P::new(half[o].1.x - v.x, half[o].1.y - v.y);
                best = match best {
                    None => Some(o),
                    Some(b) => {
                        let bdir = P::new(half[b].1.x - v.x, half[b].1.y - v.y);
                        if later_ccw(back, dir, bdir) {
                            Some(o)
                        } else {
                            Some(b)
                        }
                    }
                };
            }
            let next = best?;
            if next == start {
                break;
            }
            cur = next;
        }
        rings.push(ring);
    }
    Some(rings)
}

/// The rings sorted into polygons: each hole in the smallest outer ring holding it.
fn polygons(rings: Vec<Vec<P>>) -> Vec<Poly> {
    let mut outers: Vec<(i128, Poly)> = Vec::new();
    let mut holes: Vec<Vec<P>> = Vec::new();
    for r in rings {
        let a = area2(&r);
        if a > 0 {
            outers.push((a, Poly { outer: r, holes: Vec::new() }));
        } else if a < 0 {
            holes.push(r);
        }
    }
    for h in holes {
        let mut best: Option<usize> = None;
        for (k, (a, o)) in outers.iter().enumerate() {
            if best.is_some_and(|b| outers[b].0 <= *a) {
                continue;
            }
            if holds(&o.outer, &h) {
                best = Some(k);
            }
        }
        if let Some(b) = best {
            outers[b].1.holes.push(h);
        }
    }
    outers.into_iter().map(|(_, p)| p).collect()
}

/// Whether the ring `outer` holds the ring `inner`, which may touch it but never crosses it: by the middle of the
/// first of inner's edges that does not lie on outer.
fn holds(outer: &[P], inner: &[P]) -> bool {
    let rings = [outer];
    let n = inner.len();
    for i in 0..n {
        let m = P::new(inner[i].x + inner[(i + 1) % n].x, inner[i].y + inner[(i + 1) % n].y);
        match winding_scaled(&rings, m, 2) {
            Where::Edge => continue,
            Where::Winding(w) => return w != 0,
        }
    }
    false
}

/// Half-edges walked into rings and sorted into polygons (crate::bridge::rings).
pub fn polygons_of(half: &[(P, P)]) -> Option<Vec<Poly>> {
    Some(polygons(walk(half)?))
}

/// The arrangement of `a` and `b` and what `op` keeps of it, as polygons; None where its splitting does not settle,
/// it holds more than MAX_SEGMENTS pieces, or its rings do not close.
pub fn boolean(a: &[Vec<P>], b: &[Vec<P>], op: Op) -> Option<Vec<Poly>> {
    let segs = merge(node(segments(a, b))?);
    let sides = sides(&segs);
    let mut half = Vec::new();
    for (s, (l, r)) in segs.iter().zip(sides) {
        let (li, ri) = (inside(op, l), inside(op, r));
        if li && !ri {
            half.push((s.a, s.b));
        } else if ri && !li {
            half.push((s.b, s.a));
        }
    }
    Some(polygons(walk(&half)?))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sq(x0: i64, y0: i64, x1: i64, y1: i64) -> Vec<P> {
        vec![P::new(x0, y0), P::new(x1, y0), P::new(x1, y1), P::new(x0, y1)]
    }

    #[test]
    fn a_square_less_a_square_inside_it_has_a_hole() {
        let r = boolean(&[sq(0, 0, 10, 10)], &[sq(3, 3, 6, 6)], Op::Diff).unwrap();
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].holes.len(), 1);
        assert_eq!(r[0].area2(), 200 - 18);
    }

    #[test]
    fn overlapping_squares_cut_and_meet() {
        let d = boolean(&[sq(0, 0, 10, 10)], &[sq(5, 5, 15, 15)], Op::Diff).unwrap();
        assert_eq!(area2_of(&d), 200 - 50);
        let i = boolean(&[sq(0, 0, 10, 10)], &[sq(5, 5, 15, 15)], Op::Inter).unwrap();
        assert_eq!(area2_of(&i), 50);
        assert_eq!(i[0].outer.len(), 4);
    }

    #[test]
    fn a_cut_through_the_middle_leaves_two_pieces() {
        let d = boolean(&[sq(0, 0, 30, 10)], &[sq(10, -5, 20, 15)], Op::Diff).unwrap();
        assert_eq!(d.len(), 2);
        assert_eq!(area2_of(&d), 2 * 200);
    }

    #[test]
    fn a_courtyard_is_read_by_even_odd() {
        // the inner ring as mapped, either way round
        let mut hole = sq(2, 2, 8, 8);
        let r = boolean(&[sq(0, 0, 10, 10), hole.clone()], &[], Op::Norm).unwrap();
        assert_eq!(area2_of(&r), 200 - 72);
        hole.reverse();
        let r = boolean(&[sq(0, 0, 10, 10), hole], &[], Op::Norm).unwrap();
        assert_eq!(area2_of(&r), 200 - 72);
        assert_eq!(r[0].holes.len(), 1);
    }

    #[test]
    fn shared_walls_add_nothing() {
        let d = boolean(&[sq(0, 0, 10, 10)], &[sq(10, 0, 20, 10)], Op::Diff).unwrap();
        assert_eq!(d.len(), 1);
        assert_eq!(d[0].area2(), 200);
        let i = boolean(&[sq(0, 0, 10, 10)], &[sq(10, 0, 20, 10)], Op::Inter).unwrap();
        assert!(i.is_empty());
    }

    #[test]
    fn a_crossing_off_the_grid_is_snapped() {
        // a diagonal cut: the crossings fall between grid points
        let tri = vec![P::new(0, 0), P::new(7, 3), P::new(0, 9)];
        let d = boolean(&[sq(0, 0, 10, 10)], &[tri], Op::Diff).unwrap();
        assert!(!d.is_empty());
        for p in &d {
            assert!(p.area2() > 0);
        }
    }

    #[test]
    fn identical_footprints_cancel() {
        let d = boolean(&[sq(0, 0, 10, 10)], &[sq(0, 0, 10, 10)], Op::Diff).unwrap();
        assert!(d.is_empty());
    }

    #[test]
    fn touching_at_a_corner_parts_the_rings() {
        let r = boolean(&[sq(0, 0, 10, 10), sq(10, 10, 20, 20)], &[], Op::Norm).unwrap();
        assert_eq!(r.len(), 2);
    }
}
