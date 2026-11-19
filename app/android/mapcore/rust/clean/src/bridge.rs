//! One ring for a footprint of several pieces and courtyards, as the tiles carry a building: each courtyard joined to
//! the ring round it, and each piece to those before it, by a seam, an edge run there and back. A seam crosses no edge
//! and passes through no vertex, so the ring covers what the polygons cover, point for point, and draws nothing along a
//! seam (stage T leaves out an edge whose reverse is in the ring).

use crate::arrange::Poly;
use crate::geom::{P, Where, cross, proper_cross, strictly_inside_segment, winding_scaled};
use alloc::vec;
use alloc::vec::Vec;

/// Whether the segment (a, b) can be a seam among `rings`: it crosses none of their edges, passes through none of their
/// vertices, and its middle lies inside them (`inside`: a courtyard's seam) or outside (a piece's).
fn clear(a: P, b: P, rings: &[&[P]], inside: bool) -> bool {
    if a == b {
        return false;
    }
    for r in rings {
        let n = r.len();
        for i in 0..n {
            let (c, d) = (r[i], r[(i + 1) % n]);
            if c == d {
                continue;
            }
            if proper_cross(a, b, c, d) || strictly_inside_segment(r[i], a, b) {
                return false;
            }
            // running along an edge: collinear and sharing more than a point
            if cross(a, b, c) == 0 && cross(a, b, d) == 0 {
                let along = |p: P| {
                    ((p.x - a.x) as i128) * ((b.x - a.x) as i128) + ((p.y - a.y) as i128) * ((b.y - a.y) as i128)
                };
                let len = along(b);
                let (t0, t1) = (along(c).min(along(d)), along(c).max(along(d)));
                if t0.max(0) < t1.min(len) {
                    return false;
                }
            }
        }
    }
    let m = P::new(a.x + b.x, a.y + b.y);
    match winding_scaled(rings, m, 2) {
        Where::Edge => false,
        Where::Winding(w) => (w != 0) == inside,
    }
}

/// The vertex orders a ring is tried in: its least vertex first (by x, then y), then the others by that order.
fn by_place(r: &[P]) -> Vec<usize> {
    let mut order: Vec<usize> = (0..r.len()).collect();
    order.sort_by_key(|&i| (r[i], i));
    order
}

fn dist2(a: P, b: P) -> i128 {
    let dx = (a.x - b.x) as i128;
    let dy = (a.y - b.y) as i128;
    dx * dx + dy * dy
}

/// The edges a footprint's joining may test its seams against, in all: past it, the footprint has no ring (and its
/// group is put back as it was mapped).
pub const BUDGET: u64 = 1 << 24;

/// Joins `ring` into `into` by a seam from one of its vertices to one of into's, the nearest clear pair, trying ring's
/// vertices from its least; `others` are the rings still to join. False where no pair is clear, or the budget is spent.
fn join(into: &mut Vec<P>, ring: &[P], others: &[&[P]], inside: bool, budget: &mut u64) -> bool {
    if ring.is_empty() || into.is_empty() {
        return false;
    }
    let mut all: Vec<&[P]> = vec![into.as_slice(), ring];
    all.extend_from_slice(others);
    let cost: u64 = all.iter().map(|r| r.len() as u64).sum();
    for hi in by_place(ring) {
        let h = ring[hi];
        let mut cands: Vec<usize> = (0..into.len()).collect();
        cands.sort_by_key(|&ci| (dist2(into[ci], h), ci));
        for ci in cands {
            if *budget < cost {
                return false;
            }
            *budget -= cost;
            if clear(into[ci], h, &all, inside) {
                let mut out = Vec::with_capacity(into.len() + ring.len() + 2);
                out.extend_from_slice(&into[..=ci]);
                for k in 0..=ring.len() {
                    out.push(ring[(hi + k) % ring.len()]);
                }
                out.extend_from_slice(&into[ci..]);
                *into = out;
                return true;
            }
        }
    }
    false
}

/// The polygons as one ring (counterclockwise round its inside), or none where a courtyard or a piece cannot be
/// joined within the budget.
pub fn bridge(polys: &[Poly]) -> Option<Vec<P>> {
    let mut budget = BUDGET;
    let mut pieces: Vec<Vec<P>> = Vec::new();
    for p in polys {
        let mut ring = p.outer.clone();
        let mut holes: Vec<&Vec<P>> = p.holes.iter().collect();
        holes.sort_by_key(|h| h.iter().min().copied());
        for (k, h) in holes.iter().enumerate() {
            let rest: Vec<&[P]> = holes[k + 1..].iter().map(|r| r.as_slice()).collect();
            if !join(&mut ring, h, &rest, true, &mut budget) {
                return None;
            }
        }
        pieces.push(ring);
    }
    pieces.sort_by_key(|r| r.iter().min().copied());
    let mut it = pieces.into_iter();
    let mut ring = it.next()?;
    let rest: Vec<Vec<P>> = it.collect();
    for (k, r) in rest.iter().enumerate() {
        let others: Vec<&[P]> = rest[k + 1..].iter().map(|r| r.as_slice()).collect();
        if !join(&mut ring, r, &others, false, &mut budget) {
            return None;
        }
    }
    Some(ring)
}

/// The rings a seamed ring joins (crate::bridge's inverse): each seam, an edge run there and back, cut out, and what
/// is left walked into rings: the outer rings counterclockwise round their inside, the courtyards clockwise, each
/// in its piece. None where the ring is not one this crate made (a seam that is not one edge both ways, or what is
/// left does not walk into rings).
pub fn rings(ring: &[P]) -> Option<Vec<crate::arrange::Poly>> {
    let n = ring.len();
    let seam = seams(ring);
    if seam.iter().all(|&s| !s) {
        return Some(vec![crate::arrange::Poly { outer: ring.to_vec(), holes: Vec::new() }]);
    }
    // the edges but the seams, walked: each vertex's next edge the one that turns most clockwise from the way back
    let half: Vec<(P, P)> = (0..n).filter(|&i| !seam[i]).map(|i| (ring[i], ring[(i + 1) % n])).collect();
    crate::arrange::polygons_of(&half)
}

/// For each edge of a closed ring (from vertex i to i + 1), whether it is a seam: its reverse is an edge of the ring
/// too. Vertices are compared by `key`.
pub fn seams<K: Ord + Copy>(ring: &[K]) -> Vec<bool> {
    let n = ring.len();
    let mut out = vec![false; n];
    if n < 4 {
        return out;
    }
    // a ring with a seam visits a vertex twice
    let mut sorted: Vec<K> = ring.to_vec();
    sorted.sort_unstable();
    if sorted.windows(2).all(|w| w[0] != w[1]) {
        return out;
    }
    let mut edges: Vec<(K, K, usize)> = (0..n).map(|i| (ring[i], ring[(i + 1) % n], i)).collect();
    edges.sort_unstable();
    for i in 0..n {
        let (a, b) = (ring[i], ring[(i + 1) % n]);
        if a == b {
            continue;
        }
        let k = edges.partition_point(|e| (e.0, e.1) < (b, a));
        if k < n && edges[k].0 == b && edges[k].1 == a {
            out[i] = true;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::arrange::{Op, boolean};
    use crate::geom::{Where, area2, winding};

    fn sq(x0: i64, y0: i64, x1: i64, y1: i64) -> Vec<P> {
        vec![P::new(x0, y0), P::new(x1, y0), P::new(x1, y1), P::new(x0, y1)]
    }

    #[test]
    fn a_courtyard_joins_by_a_seam() {
        let polys = boolean(&[sq(0, 0, 10, 10)], &[sq(4, 4, 6, 6)], Op::Diff).unwrap();
        let ring = bridge(&polys).unwrap();
        assert_eq!(ring.len(), 4 + 4 + 2);
        assert_eq!(area2(&ring), 200 - 8);
        let s = seams(&ring);
        assert_eq!(s.iter().filter(|&&b| b).count(), 2);
        // the courtyard is outside, the rest inside
        let r = vec![ring];
        assert_eq!(winding(&r, P::new(5, 5)), Where::Winding(0));
        assert_eq!(winding(&r, P::new(1, 5)), Where::Winding(1));
    }

    #[test]
    fn pieces_join_by_a_seam_outside() {
        let polys = boolean(&[sq(0, 0, 30, 10)], &[sq(10, -5, 20, 15)], Op::Diff).unwrap();
        let ring = bridge(&polys).unwrap();
        assert_eq!(area2(&ring), 400);
        let r = vec![ring.clone()];
        assert_eq!(winding(&r, P::new(15, 5)), Where::Winding(0));
        assert_eq!(winding(&r, P::new(5, 5)), Where::Winding(1));
        assert_eq!(winding(&r, P::new(25, 5)), Where::Winding(1));
        assert_eq!(seams(&ring).iter().filter(|&&b| b).count(), 2);
    }

    #[test]
    fn a_ring_without_seams_has_none() {
        assert!(seams(&sq(0, 0, 3, 3)).iter().all(|&b| !b));
        // a vertex visited twice without a seam (two pieces touching at a corner) is no seam either
        let bow = vec![
            P::new(0, 0),
            P::new(2, 0),
            P::new(2, 2),
            P::new(4, 2),
            P::new(4, 4),
            P::new(2, 4),
            P::new(2, 2),
            P::new(0, 2),
        ];
        assert!(seams(&bow).iter().all(|&b| !b));
    }
}
