//! The buildings' cleaning (stage P): what a window's footprints say, made into what can stand there.
//!   1. Courtyards: a footprint's inner rings are cut out of it.
//!   2. Coverage: an outline less its parts, the rest of it kept; an outline its parts cover whole is not drawn.
//!   3. Stacking: a footprint standing on others that overlap it in height, on most of its area, starts at their top.
//!   4. Clipping: of two footprints that still overlap, in plan and in height, the one whose heights lie within the
//!      other's gives way where they overlap; where neither holds the other, the one that loses less volume.
//! Then what is left of each is one ring (crate::bridge), and the counts of what still cannot be there.

use crate::arrange::{area2_of, boolean, rings_of, Op, Poly};
use crate::bridge::bridge;
use crate::geom::{area2, cross, interiors_overlap, Box2, P};
use alloc::collections::BinaryHeap;
use alloc::vec;
use alloc::vec::Vec;
use core::cmp::Reverse;

/// A footprint as mapped: its outer ring, then its inner rings; the heights its walls span, in metres.
#[derive(Clone, Debug)]
pub struct Footprint {
    pub rings: Vec<Vec<P>>,
    pub min: f64,
    pub top: f64,
    pub part: bool,
}

/// The cleaning's measures. The grid's units in metres (x the longitude's, y the latitude's), then in m² and m.
#[derive(Clone, Copy, Debug)]
pub struct Params {
    pub ux: f64,
    pub uy: f64,
    /// Footprints overlap where they share more than this.
    pub overlap_m2: f64,
    /// Heights overlap where they share more than this (m).
    pub height_m: f64,
    /// A piece left by a cut narrower than this (twice its area over its perimeter) goes.
    pub thin_m: f64,
    /// A piece smaller than this goes (the loaders' own least footprint).
    pub small_m2: f64,
    /// A courtyard smaller than this is filled.
    pub hole_m2: f64,
    /// An outline counts as left uncovered where more of it than this is drawn by nothing.
    pub uncovered_m2: f64,
}

impl Params {
    pub fn new(ux: f64, uy: f64) -> Params {
        Params {
            ux,
            uy,
            overlap_m2: 1.0,
            height_m: 0.1,
            thin_m: 0.5,
            small_m2: 3.0,
            hole_m2: 0.5,
            uncovered_m2: 5.0,
        }
    }
}

/// What the cleaning counts.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Counts {
    /// Pairs of drawn footprints still overlapping, in plan and in height (validity count 1).
    pub overlapping_pairs: u32,
    /// Outlines with more of their area drawn by nothing than Params::uncovered_m2 (validity count 2).
    pub uncovered_outlines: u32,
    pub courtyards: u32,
    /// Outlines cut by their parts, and those their parts cover whole.
    pub covered: u32,
    pub hidden: u32,
    pub clipped: u32,
    pub raised: u32,
    /// Footprints cut to nothing by others (an outline then not drawn, a part gone).
    pub vanished: u32,
    /// Footprints left in more than one piece, or with courtyards: their rings carry seams.
    pub seamed: u32,
    /// Operations whose arrangement did not settle: their footprints are left as they were.
    pub unsettled: u32,
    /// Footprints with a courtyard or piece that no seam could join (left out).
    pub unjoined: u32,
    /// Area drawn by nothing that the mapped outlines cover (m²), but slivers and crumbs.
    pub uncovered_m2: f64,
    /// Slivers and crumbs of the mapped outlines that nothing draws (m²): narrower than Params::thin_m, or smaller
    /// than Params::small_m2.
    pub sliver_m2: f64,
}

/// What the cleaning makes of each footprint: whether it is drawn, its ring where it changed (counterclockwise round
/// its inside, seams joining its pieces and courtyards), and its base.
#[derive(Clone, Debug)]
pub struct Cleaned {
    pub drawn: Vec<bool>,
    pub ring: Vec<Option<Vec<P>>>,
    pub min: Vec<f64>,
    pub counts: Counts,
    /// Per footprint, an outline's area that nothing draws (m²; 0 for a part, or an outline nothing touched).
    pub uncovered: Vec<f64>,
}

/// The square root, correctly rounded as IEEE 754 has every platform's (the C library's: core has none).
fn sqrt(x: f64) -> f64 {
    unsafe extern "C" {
        #[link_name = "sqrt"]
        fn c_sqrt(x: f64) -> f64;
    }
    // SAFETY: the C library's sqrt, of any double
    unsafe { c_sqrt(x) }
}

/// Rings as the operations take them: closing points and repeats dropped, the outer counterclockwise and the inner
/// rings clockwise; a ring of fewer than three points left out.
fn orient(rings: &[Vec<P>]) -> Vec<Vec<P>> {
    let mut out = Vec::with_capacity(rings.len());
    for (k, r) in rings.iter().enumerate() {
        let mut v: Vec<P> = Vec::with_capacity(r.len());
        for &p in r {
            if v.last() != Some(&p) {
                v.push(p);
            }
        }
        while v.len() > 1 && v.first() == v.last() {
            v.pop();
        }
        if v.len() < 3 {
            if k == 0 {
                return Vec::new();
            }
            continue;
        }
        let a = area2(&v);
        if (k == 0) == (a < 0) {
            v.reverse();
        }
        out.push(v);
    }
    out
}

/// The pairs of boxes that meet, each once (lesser index first), in order.
fn pairs(boxes: &[Box2]) -> Vec<(usize, usize)> {
    let mut order: Vec<usize> = (0..boxes.len()).filter(|&k| !boxes[k].is_empty()).collect();
    order.sort_by_key(|&k| (boxes[k].x0, k));
    let mut out = Vec::new();
    for (oi, &i) in order.iter().enumerate() {
        let b = boxes[i];
        for &j in &order[oi + 1..] {
            let c = boxes[j];
            if c.x0 > b.x1 {
                break;
            }
            if c.y0 <= b.y1 && b.y0 <= c.y1 {
                out.push((i.min(j), i.max(j)));
            }
        }
    }
    out.sort_unstable();
    out
}

struct Run {
    p: Params,
    orig: Vec<Vec<Vec<P>>>,
    cur: Vec<Option<Vec<Poly>>>,
    /// The vertices as mapped, sorted: a cut keeps them.
    keep: Vec<P>,
    counts: Counts,
}

impl Run {
    fn m2(&self, a2: i128) -> f64 {
        (a2 as f64) * 0.5 * self.p.ux * self.p.uy
    }

    /// A footprint's rings as they stand.
    fn shape(&self, k: usize) -> Vec<Vec<P>> {
        match &self.cur[k] {
            Some(polys) => rings_of(polys),
            None => self.orig[k].clone(),
        }
    }

    fn area_m2(&self, k: usize) -> f64 {
        match &self.cur[k] {
            Some(polys) => self.m2(area2_of(polys)),
            None => self.m2(self.orig[k].iter().map(|r| area2(r)).sum()),
        }
    }

    /// The area two footprints share, m² (0 where it cannot be found).
    fn shared(&mut self, i: usize, j: usize) -> f64 {
        let (a, b) = (self.shape(i), self.shape(j));
        if !interiors_overlap(&a, &b) {
            return 0.0;
        }
        self.inter(&a, &b)
    }

    /// The area two sets of rings share, m².
    fn inter(&mut self, a: &[Vec<P>], b: &[Vec<P>]) -> f64 {
        match boolean(a, b, Op::Inter) {
            Some(x) => self.m2(area2_of(&x)),
            None => {
                self.counts.unsettled += 1;
                0.0
            }
        }
    }

    /// `k` less the footprints `by`, its pieces tidied; None where the arrangement does not settle.
    fn less(&mut self, k: usize, by: &[usize]) -> Option<Vec<Poly>> {
        let b: Vec<Vec<P>> = by.iter().flat_map(|&j| self.shape(j)).collect();
        match boolean(&self.shape(k), &b, Op::Diff) {
            Some(r) => Some(self.tidy(r, true)),
            None => {
                self.counts.unsettled += 1;
                None
            }
        }
    }

    /// A ring's vertices that lie on a straight line between their neighbours dropped, but the mapped ones.
    fn straighten(&self, ring: Vec<P>) -> Vec<P> {
        let mut r = ring;
        let mut from = 0;
        while r.len() > 3 && from < r.len() {
            let n = r.len();
            let found = (from..n).find(|&i| {
                let (u, v, w) = (r[(i + n - 1) % n], r[i], r[(i + 1) % n]);
                cross(u, v, w) == 0 && self.keep.binary_search(&v).is_err()
            });
            match found {
                Some(i) => {
                    r.remove(i);
                    from = i.saturating_sub(1);
                }
                None => break,
            }
        }
        r
    }

    /// The pieces of a result as they may stand: straightened, courtyards too small to be one filled, and pieces too
    /// small (or, for what a cut left, too thin) left out.
    fn tidy(&self, polys: Vec<Poly>, cut: bool) -> Vec<Poly> {
        let mut out = Vec::with_capacity(polys.len());
        for p in polys {
            let outer = self.straighten(p.outer);
            let holes: Vec<Vec<P>> = p
                .holes
                .into_iter()
                .map(|h| self.straighten(h))
                .filter(|h| -self.m2(area2(h)) >= self.p.hole_m2)
                .collect();
            let poly = Poly { outer, holes };
            let a = self.m2(poly.area2());
            if a < self.p.small_m2 {
                continue;
            }
            if cut {
                let n = poly.outer.len();
                let mut len = 0.0;
                for i in 0..n {
                    let (u, v) = (poly.outer[i], poly.outer[(i + 1) % n]);
                    let dx = (v.x - u.x) as f64 * self.p.ux;
                    let dy = (v.y - u.y) as f64 * self.p.uy;
                    len += sqrt(dx * dx + dy * dy);
                }
                if len > 0.0 && 2.0 * a / len < self.p.thin_m {
                    continue;
                }
            }
            out.push(poly);
        }
        out
    }
}

/// Cleans the footprints of a window (in the scene's order, which decides the ties).
pub fn clean(fps: &[Footprint], p: Params) -> Cleaned {
    let n = fps.len();
    let orig: Vec<Vec<Vec<P>>> = fps.iter().map(|f| orient(&f.rings)).collect();
    let boxes: Vec<Box2> = orig.iter().map(|r| Box2::of(r)).collect();
    let mut keep: Vec<P> = orig.iter().flatten().flatten().copied().collect();
    keep.sort_unstable();
    keep.dedup();
    let mut run = Run { p, orig, cur: vec![None; n], keep, counts: Counts::default() };
    let mut drawn: Vec<bool> = (0..n).map(|k| !run.orig[k].is_empty()).collect();
    let mut min: Vec<f64> = fps.iter().map(|f| f.min).collect();

    // 1. courtyards: the outer ring by even-odd (a ring that crosses itself keeps both its lobes), less its inner
    // rings by nonzero (one mapped partly outside it adds nothing)
    for k in 0..n {
        if run.orig[k].len() > 1 {
            match boolean(&run.orig[k][..1], &run.orig[k][1..], Op::Diff) {
                Some(r) => {
                    let r = run.tidy(r, false);
                    if r.is_empty() {
                        drawn[k] = false;
                    }
                    run.cur[k] = Some(r);
                    run.counts.courtyards += 1;
                }
                None => run.counts.unsettled += 1,
            }
        }
    }
    let whole: Vec<Option<Vec<Poly>>> = run.cur.clone();
    let mut touched = vec![false; n];

    // the pairs whose interiors meet: the cleaning only takes from a footprint, so no other pair can come to overlap
    let meeting: Vec<(usize, usize)> = pairs(&boxes)
        .into_iter()
        .filter(|&(i, j)| drawn[i] && drawn[j] && interiors_overlap(&run.shape(i), &run.shape(j)))
        .collect();

    // 2. coverage: an outline less the parts overlapping it
    let mut covers: Vec<Vec<usize>> = vec![Vec::new(); n];
    for &(i, j) in &meeting {
        let (o, q) = match (fps[i].part, fps[j].part) {
            (false, true) => (i, j),
            (true, false) => (j, i),
            _ => continue,
        };
        // a part with no height draws nothing, so covers nothing
        if fps[q].top - fps[q].min > p.height_m && run.shared(o, q) > p.overlap_m2 {
            covers[o].push(q);
        }
    }
    for o in 0..n {
        if covers[o].is_empty() {
            continue;
        }
        touched[o] = true;
        if let Some(r) = run.less(o, &covers[o]) {
            if r.is_empty() {
                drawn[o] = false;
                run.counts.hidden += 1;
            } else {
                run.cur[o] = Some(r);
                run.counts.covered += 1;
            }
        }
    }

    // the drawn footprints that overlap in plan, and how much
    let mut over: Vec<(usize, usize, f64)> = Vec::new();
    for &(i, j) in &meeting {
        if drawn[i] && drawn[j] {
            let a = run.shared(i, j);
            if a > p.overlap_m2 {
                over.push((i, j, a));
            }
        }
    }
    let mut near: Vec<Vec<(usize, f64)>> = vec![Vec::new(); n];
    for &(i, j, a) in &over {
        near[i].push((j, a));
        near[j].push((i, a));
    }

    // 3. stacking, from the lowest up
    let mut order: Vec<usize> = (0..n).filter(|&k| drawn[k] && !near[k].is_empty()).collect();
    order.sort_by(|&a, &b| min[a].total_cmp(&min[b]).then(a.cmp(&b)));
    for &u in &order {
        let lowers: Vec<usize> = near[u]
            .iter()
            .map(|&(l, _)| l)
            .filter(|&l| min[l] < min[u] && fps[l].top < fps[u].top && fps[l].top - min[u] > p.height_m)
            .collect();
        if lowers.is_empty() {
            continue;
        }
        let b: Vec<Vec<P>> = lowers.iter().flat_map(|&l| run.shape(l)).collect();
        let Some(x) = boolean(&run.shape(u), &b, Op::Inter) else {
            run.counts.unsettled += 1;
            continue;
        };
        if run.m2(area2_of(&x)) >= 0.5 * run.area_m2(u) {
            min[u] = lowers.iter().map(|&l| fps[l].top).fold(min[u], f64::max);
            run.counts.raised += 1;
        }
    }

    // 4. clipping: who gives way to whom
    let mut beaten: Vec<Vec<usize>> = vec![Vec::new(); n];
    let mut beats: Vec<Vec<usize>> = vec![Vec::new(); n];
    for &(i, j, a) in &over {
        let shared_h = fps[i].top.min(fps[j].top) - min[i].max(min[j]);
        if !(shared_h > p.height_m) {
            continue;
        }
        let i_in_j = min[j] <= min[i] && fps[i].top <= fps[j].top;
        let j_in_i = min[i] <= min[j] && fps[j].top <= fps[i].top;
        let loser = if i_in_j && j_in_i {
            // the same heights: the larger stands, then the first
            if run.area_m2(j) > run.area_m2(i) { i } else { j }
        } else if i_in_j {
            i
        } else if j_in_i {
            j
        } else {
            let (l, u) = if min[i] < min[j] { (i, j) } else { (j, i) };
            let cost_l = a * (min[u] - min[l]);
            let cost_u = a * (fps[u].top - fps[l].top);
            if cost_l < cost_u { l } else { u }
        };
        let winner = if loser == i { j } else { i };
        beaten[loser].push(winner);
        beats[winner].push(loser);
    }
    let mut waiting: Vec<usize> = (0..n).map(|k| beaten[k].len()).collect();
    let mut ready: BinaryHeap<Reverse<usize>> = (0..n).filter(|&k| waiting[k] == 0 && !beats[k].is_empty()).map(Reverse).collect();
    let mut done = vec![false; n];
    let left: Vec<usize> = (0..n).filter(|&k| waiting[k] > 0).collect();
    loop {
        let k = if let Some(Reverse(k)) = ready.pop() {
            k
        } else if let Some(pos) = left.iter().position(|&k| !done[k]) {
            left[pos] // a cycle: the first left gives way only to those settled
        } else {
            break;
        };
        if done[k] {
            continue;
        }
        done[k] = true;
        let winners: Vec<usize> = beaten[k].iter().copied().filter(|&w| done[w] && drawn[w]).collect();
        if !winners.is_empty() && drawn[k] {
            touched[k] = true;
            if let Some(r) = run.less(k, &winners) {
                if r.is_empty() {
                    drawn[k] = false;
                    run.counts.vanished += 1;
                } else {
                    run.cur[k] = Some(r);
                    run.counts.clipped += 1;
                }
            }
        }
        for &l in &beats[k] {
            waiting[l] -= 1;
            if waiting[l] == 0 {
                ready.push(Reverse(l));
            }
        }
    }

    // the counts of what is drawn: only a pair that overlapped can still
    for &(i, j, _) in &over {
        if !drawn[i] || !drawn[j] {
            continue;
        }
        let shared_h = fps[i].top.min(fps[j].top) - min[i].max(min[j]);
        if shared_h > p.height_m && run.shared(i, j) > p.overlap_m2 {
            run.counts.overlapping_pairs += 1;
        }
    }
    let mut uncovered = vec![0.0; n];
    for o in 0..n {
        if fps[o].part || !touched[o] {
            continue;
        }
        let full: Vec<Vec<P>> = match &whole[o] {
            Some(polys) => rings_of(polys),
            None => run.orig[o].clone(),
        };
        let by: Vec<Vec<P>> = (0..n)
            .filter(|&k| drawn[k] && boxes[k].meets(&boxes[o]))
            .flat_map(|k| run.shape(k))
            .collect();
        match boolean(&full, &by, Op::Diff) {
            Some(r) => {
                // slivers (an outline mapped a little wider than its parts) and crumbs count apart
                run.counts.sliver_m2 += run.m2(area2_of(&r));
                let a = run.m2(area2_of(&run.tidy(r, true)));
                run.counts.sliver_m2 -= a;
                uncovered[o] = a;
                run.counts.uncovered_m2 += a;
                if a > p.uncovered_m2 {
                    run.counts.uncovered_outlines += 1;
                }
            }
            None => run.counts.unsettled += 1,
        }
    }

    // one ring each: what is drawn, or for an outline not drawn its courtyards as mapped
    let mut ring: Vec<Option<Vec<P>>> = vec![None; n];
    for k in 0..n {
        let polys = if drawn[k] { run.cur[k].as_ref() } else { whole[k].as_ref() };
        let Some(polys) = polys else { continue };
        if polys.is_empty() {
            continue;
        }
        if polys.len() > 1 || polys.iter().any(|q| !q.holes.is_empty()) {
            run.counts.seamed += 1;
        }
        let (r, lost) = bridge(polys);
        if lost != 0 {
            run.counts.unjoined += 1;
        }
        ring[k] = Some(r);
    }
    Cleaned { drawn, ring, min, counts: run.counts, uncovered }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 1e-7 degrees at 52°N, about: 6.85 mm east, 11.1 mm north. Tests work in centimetre-ish units instead.
    fn params() -> Params {
        Params::new(0.01, 0.01)
    }

    fn sq(x0: i64, y0: i64, x1: i64, y1: i64) -> Vec<P> {
        vec![P::new(x0, y0), P::new(x1, y0), P::new(x1, y1), P::new(x0, y1)]
    }

    fn fp(rings: Vec<Vec<P>>, min: f64, top: f64, part: bool) -> Footprint {
        Footprint { rings, min, top, part }
    }

    #[test]
    fn an_outline_less_its_tower_keeps_its_nave() {
        // a 20 x 10 m church, a 4 x 4 m tower part at one end
        let church = fp(vec![sq(0, 0, 2000, 1000)], 0.0, 19.0, false);
        let tower = fp(vec![sq(0, 300, 400, 700)], 0.0, 40.0, true);
        let c = clean(&[church, tower], params());
        assert!(c.drawn[0] && c.drawn[1]);
        let ring = c.ring[0].as_ref().unwrap();
        assert_eq!(area2(ring), 2 * (2000 * 1000 - 400 * 400));
        assert!(c.ring[1].is_none());
        assert_eq!(c.counts.covered, 1);
        assert_eq!(c.counts.overlapping_pairs, 0);
        assert_eq!(c.counts.uncovered_outlines, 0);
    }

    #[test]
    fn an_outline_its_parts_cover_is_not_drawn() {
        let outline = fp(vec![sq(0, 0, 1000, 1000)], 0.0, 10.0, false);
        let a = fp(vec![sq(0, 0, 500, 1000)], 0.0, 12.0, true);
        let b = fp(vec![sq(500, 0, 1000, 1000)], 0.0, 8.0, true);
        let c = clean(&[outline, a, b], params());
        assert!(!c.drawn[0]);
        assert_eq!(c.counts.hidden, 1);
        assert_eq!(c.counts.uncovered_outlines, 0);
    }

    #[test]
    fn the_lower_of_two_overlapping_buildings_gives_way() {
        let low = fp(vec![sq(0, 0, 1000, 1000)], 0.0, 10.0, false);
        let high = fp(vec![sq(500, 0, 1500, 1000)], 0.0, 23.0, false);
        let c = clean(&[low, high], params());
        assert!(c.ring[1].is_none());
        assert_eq!(area2(c.ring[0].as_ref().unwrap()), 2 * 500 * 1000);
        assert_eq!(c.counts.clipped, 1);
        assert_eq!(c.counts.overlapping_pairs, 0);
    }

    #[test]
    fn a_roof_over_a_kiosk_is_no_overlap() {
        let roof = fp(vec![sq(0, 0, 1000, 1000)], 3.0, 4.0, false);
        let kiosk = fp(vec![sq(200, 200, 400, 400)], 0.0, 3.0, false);
        let c = clean(&[roof, kiosk], params());
        assert!(c.ring[0].is_none() && c.ring[1].is_none());
        assert_eq!(c.counts.clipped, 0);
    }

    #[test]
    fn a_storey_mapped_into_the_one_below_starts_at_its_top() {
        let below = fp(vec![sq(0, 0, 1000, 1000)], 0.0, 15.0, true);
        let above = fp(vec![sq(0, 0, 1000, 1000)], 12.0, 18.0, true);
        let c = clean(&[below, above], params());
        assert!(c.drawn[0] && c.drawn[1]);
        assert_eq!(c.min[1], 15.0);
        assert_eq!(c.counts.raised, 1);
        assert_eq!(c.counts.overlapping_pairs, 0);
    }

    #[test]
    fn a_courtyard_is_cut_out_and_seamed() {
        let b = fp(vec![sq(0, 0, 3000, 3000), sq(1000, 1000, 2000, 2000)], 0.0, 15.0, false);
        let c = clean(&[b], params());
        let ring = c.ring[0].as_ref().unwrap();
        assert_eq!(area2(ring), 2 * (9_000_000 - 1_000_000));
        assert_eq!(crate::bridge::seams(ring).iter().filter(|&&s| s).count(), 2);
        assert_eq!(c.counts.courtyards, 1);
        assert_eq!(c.counts.seamed, 1);
    }

    #[test]
    fn a_double_mapped_building_stands_once() {
        let a = fp(vec![sq(0, 0, 1000, 1000)], 0.0, 10.0, false);
        let b = fp(vec![sq(0, 0, 1000, 1000)], 0.0, 10.0, false);
        let c = clean(&[a, b], params());
        assert_eq!(c.drawn.iter().filter(|&&d| d).count(), 1);
        assert_eq!(c.counts.overlapping_pairs, 0);
    }

    #[test]
    fn row_houses_sharing_walls_are_left_alone() {
        let fps: Vec<Footprint> = (0..5).map(|i| fp(vec![sq(i * 600, 0, (i + 1) * 600, 1000)], 0.0, 12.0, false)).collect();
        let c = clean(&fps, params());
        assert!(c.ring.iter().all(Option::is_none));
        assert!(c.drawn.iter().all(|&d| d));
        assert_eq!(c.counts, Counts::default());
    }
}
