//! Exact geometry on OSM's grid: points are whole grid units (1e-7 degrees), x the longitude and y the latitude, and
//! every predicate is decided in integers (i128 where products may pass i64), so the answers are the same on every
//! platform and never depend on rounding.

use alloc::vec::Vec;

/// A point on the grid: x the longitude, y the latitude, in grid units.
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Debug, Default)]
pub struct P {
    pub x: i64,
    pub y: i64,
}

impl P {
    pub const fn new(x: i64, y: i64) -> P {
        P { x, y }
    }
}

/// Twice the signed area of the triangle (o, a, b): positive when o→a→b turns counterclockwise.
#[inline]
pub fn cross(o: P, a: P, b: P) -> i128 {
    let ax = (a.x - o.x) as i128;
    let ay = (a.y - o.y) as i128;
    let bx = (b.x - o.x) as i128;
    let by = (b.y - o.y) as i128;
    ax * by - ay * bx
}

/// The dot product of (a - o) and (b - o).
#[inline]
pub fn dot(o: P, a: P, b: P) -> i128 {
    ((a.x - o.x) as i128) * ((b.x - o.x) as i128) + ((a.y - o.y) as i128) * ((b.y - o.y) as i128)
}

/// Twice the signed area of a ring (its closing edge implied): positive when it runs counterclockwise.
pub fn area2(ring: &[P]) -> i128 {
    let n = ring.len();
    if n < 3 {
        return 0;
    }
    let o = ring[0];
    let mut s: i128 = 0;
    for i in 1..n - 1 {
        s += cross(o, ring[i], ring[i + 1]);
    }
    s
}

/// A box on the grid, both corners included.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Box2 {
    pub x0: i64,
    pub y0: i64,
    pub x1: i64,
    pub y1: i64,
}

impl Box2 {
    pub const EMPTY: Box2 = Box2 { x0: i64::MAX, y0: i64::MAX, x1: i64::MIN, y1: i64::MIN };

    pub fn add(&mut self, p: P) {
        self.x0 = self.x0.min(p.x);
        self.y0 = self.y0.min(p.y);
        self.x1 = self.x1.max(p.x);
        self.y1 = self.y1.max(p.y);
    }

    pub fn of(rings: &[Vec<P>]) -> Box2 {
        let mut b = Box2::EMPTY;
        for r in rings {
            for &p in r {
                b.add(p);
            }
        }
        b
    }

    pub fn meets(&self, o: &Box2) -> bool {
        self.x0 <= o.x1 && o.x0 <= self.x1 && self.y0 <= o.y1 && o.y0 <= self.y1
    }

    pub fn is_empty(&self) -> bool {
        self.x0 > self.x1
    }
}

/// Whether p lies on the segment [a, b], its ends included.
#[inline]
pub fn on_segment(p: P, a: P, b: P) -> bool {
    cross(a, b, p) == 0 && dot(p, a, b) <= 0
}

/// Whether p lies on the segment [a, b] strictly between its ends (a ≠ b).
#[inline]
pub fn strictly_inside_segment(p: P, a: P, b: P) -> bool {
    cross(a, b, p) == 0 && dot(p, a, b) < 0
}

/// The sign of v.
#[inline]
pub fn sgn(v: i128) -> i32 {
    (v > 0) as i32 - (v < 0) as i32
}

/// Whether the open segments (a, b) and (c, d) cross at one point inside both.
#[inline]
pub fn proper_cross(a: P, b: P, c: P, d: P) -> bool {
    let d1 = sgn(cross(c, d, a));
    let d2 = sgn(cross(c, d, b));
    let d3 = sgn(cross(a, b, c));
    let d4 = sgn(cross(a, b, d));
    d1 * d2 < 0 && d3 * d4 < 0
}

/// n / d rounded to the nearest integer, halves away from zero (d ≠ 0).
#[inline]
pub fn round_div(n: i128, d: i128) -> i128 {
    let (n, d) = if d < 0 { (-n, -d) } else { (n, d) };
    if n >= 0 {
        (2 * n + d) / (2 * d)
    } else {
        -((-2 * n + d) / (2 * d))
    }
}

/// The grid point nearest to where the segments (a, b) and (c, d) cross (they cross properly).
pub fn crossing(a: P, b: P, c: P, d: P) -> P {
    let d1 = cross(c, d, a);
    let d2 = cross(c, d, b);
    let den = d1 - d2;
    let x = a.x as i128 + round_div((b.x - a.x) as i128 * d1, den);
    let y = a.y as i128 + round_div((b.y - a.y) as i128 * d1, den);
    P::new(x as i64, y as i64)
}

/// Where p stands to a set of rings under the nonzero rule: on an edge, or its winding number.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Where {
    Edge,
    Winding(i32),
}

/// The winding number of the rings about p (each ring closed), or Edge where p lies on one of their edges. Rings
/// running counterclockwise count +1 inside.
pub fn winding(rings: &[Vec<P>], p: P) -> Where {
    winding_scaled(rings, p, 1)
}

/// The same about p for the rings scaled by s (2: a middle point of two grid points, doubled).
pub fn winding_scaled<R: AsRef<[P]>>(rings: &[R], p: P, s: i64) -> Where {
    let mut w = 0;
    for r in rings {
        let r = r.as_ref();
        let n = r.len();
        for i in 0..n {
            let a = P::new(r[i].x * s, r[i].y * s);
            let b = P::new(r[(i + 1) % n].x * s, r[(i + 1) % n].y * s);
            if on_segment(p, a, b) {
                return Where::Edge;
            }
            if a.y <= p.y {
                if b.y > p.y && cross(a, b, p) > 0 {
                    w += 1;
                }
            } else if b.y <= p.y && cross(a, b, p) < 0 {
                w -= 1;
            }
        }
    }
    Where::Winding(w)
}

/// Whether p is strictly inside the rings (nonzero winding, not on an edge).
pub fn strictly_inside(rings: &[Vec<P>], p: P) -> bool {
    matches!(winding(rings, p), Where::Winding(w) if w != 0)
}

/// Whether the interiors of two sets of rings (each counterclockwise round its inside: outers counterclockwise,
/// holes clockwise) share any area. Exact: their edges cross; or an edge of one runs along an edge of the other the
/// same way (both insides on the same side); or a piece of an edge of one, between the vertices of the other on it,
/// lies strictly inside the other. Otherwise the interiors are apart, or the same (which an edge along an edge the same
/// way shows).
pub fn interiors_overlap(f: &[Vec<P>], g: &[Vec<P>]) -> bool {
    let edges = |rings: &[Vec<P>]| -> Vec<(P, P)> {
        let mut out = Vec::new();
        for r in rings {
            let n = r.len();
            for i in 0..n {
                if r[i] != r[(i + 1) % n] {
                    out.push((r[i], r[(i + 1) % n]));
                }
            }
        }
        out
    };
    let (ef, eg) = (edges(f), edges(g));
    for &(a, b) in &ef {
        let (fx0, fx1, fy0, fy1) = (a.x.min(b.x), a.x.max(b.x), a.y.min(b.y), a.y.max(b.y));
        for &(c, d) in &eg {
            if c.x.max(d.x) < fx0 || c.x.min(d.x) > fx1 || c.y.max(d.y) < fy0 || c.y.min(d.y) > fy1 {
                continue;
            }
            if proper_cross(a, b, c, d) {
                return true;
            }
            if cross(a, b, c) == 0 && cross(a, b, d) == 0 {
                // collinear: the same way, and sharing more than a point
                let along = |p: P| dot(a, p, b); // (p - a)·(b - a)
                let (s0, s1) = (0, along(b));
                let (t0, t1) = (along(c).min(along(d)), along(c).max(along(d)));
                let same_way = dot(P::new(0, 0), P::new(b.x - a.x, b.y - a.y), P::new(d.x - c.x, d.y - c.y)) > 0;
                if same_way && s0.max(t0) < s1.min(t1) {
                    return true;
                }
            }
        }
    }
    pieces_inside(&ef, g) || pieces_inside(&eg, f)
}

/// Whether a piece of one of the edges, between the vertices of `rings` on it, lies strictly inside `rings`.
fn pieces_inside(edges: &[(P, P)], rings: &[Vec<P>]) -> bool {
    let mut pts: Vec<P> = Vec::new();
    for &(a, b) in edges {
        pts.clear();
        pts.push(a);
        pts.push(b);
        for r in rings {
            for &v in r {
                if strictly_inside_segment(v, a, b) {
                    pts.push(v);
                }
            }
        }
        pts.sort_by_key(|&p| (dot(a, p, b), p));
        for w in pts.windows(2) {
            if w[0] == w[1] {
                continue;
            }
            let m = P::new(w[0].x + w[1].x, w[0].y + w[1].y);
            if matches!(winding_scaled(rings, m, 2), Where::Winding(k) if k != 0) {
                return true;
            }
        }
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounding_halves_away_from_zero() {
        assert_eq!(round_div(5, 2), 3);
        assert_eq!(round_div(-5, 2), -3);
        assert_eq!(round_div(4, 3), 1);
        assert_eq!(round_div(-4, 3), -1);
        assert_eq!(round_div(5, -2), -3);
        assert_eq!(round_div(0, 7), 0);
    }

    #[test]
    fn crossings_snap_to_the_nearest_grid_point() {
        let p = crossing(P::new(0, 0), P::new(10, 10), P::new(0, 10), P::new(10, 0));
        assert_eq!(p, P::new(5, 5));
        let q = crossing(P::new(0, 0), P::new(3, 1), P::new(0, 1), P::new(3, 0));
        assert_eq!(q, P::new(2, 1)); // (1.5, 0.5): halves away from zero
    }

    #[test]
    fn winding_and_edges() {
        let sq = vec![vec![P::new(0, 0), P::new(4, 0), P::new(4, 4), P::new(0, 4)]];
        assert_eq!(winding(&sq, P::new(2, 2)), Where::Winding(1));
        assert_eq!(winding(&sq, P::new(5, 2)), Where::Winding(0));
        assert_eq!(winding(&sq, P::new(4, 2)), Where::Edge);
        assert_eq!(winding(&sq, P::new(0, 0)), Where::Edge);
        assert_eq!(area2(&sq[0]), 32);
    }
}
