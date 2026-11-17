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
    /// Groups of footprints an operation could not clean (past its bounds, or not settling): each put back as it was
    /// mapped, its courtyards cut out where that held; and footprints whose courtyards could not be cut out.
    pub unsettled: u32,
    /// Outlines whose uncovered area could not be measured (their arrangement past its bounds).
    pub unmeasured: u32,
    /// Area drawn by nothing that the mapped outlines cover (m²), but slivers and crumbs.
    pub uncovered_m2: f64,
    /// Slivers and crumbs of the mapped outlines that nothing draws (m²): narrower than Params::thin_m, or smaller
    /// than Params::small_m2.
    pub sliver_m2: f64,
    /// What the cuts left that went as slivers or crumbs, of every footprint (m²).
    pub dropped_m2: f64,
    /// Pairs of drawn footprints overlapping in plan and in height before any was clipped (after coverage): what the
    /// clipping had to do; none for footprints already cleaned.
    pub pairs_before: u32,
    /// The work done, of WORK.
    pub work: u64,
}

/// What the cleaning makes of each footprint: whether it is drawn, its ring where it changed (counterclockwise round
/// its inside, seams joining its pieces and courtyards), and its base.
#[derive(Clone, Debug)]
pub struct Cleaned {
    pub drawn: Vec<bool>,
    pub ring: Vec<Option<Vec<P>>>,
    pub min: Vec<f64>,
    pub counts: Counts,
    /// Per footprint, an outline's area that nothing draws (m²; 0 for a part, or an outline nothing touched; -1
    /// where it could not be measured).
    pub uncovered: Vec<f64>,
    /// Per footprint, what was done to it: COVERED, HIDDEN, CLIPPED, RAISED, VANISHED, COURTYARD.
    pub what: Vec<u8>,
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

/// The work one cleaning may do, in edge pairs tested (an arrangement of n edges costs n², a pair of footprints of n
/// and m edges n × m, a step of the sweep over the boxes 1): some thousand times what the canal ring's 26,623
/// footprints take whole, and past it every further operation fails, so its footprints stay as mapped. So the
/// cleaning ends on any input, in a time this bounds.
pub const WORK: u64 = 1 << 36;

/// The most pairs of boxes a cleaning looks at.
const PAIRS: usize = 1 << 22;

/// The pairs of boxes that meet, each once (lesser index first), in order; and whether those are all of them (the
/// sweep stayed within `work` steps and PAIRS pairs).
fn pairs(boxes: &[Box2], work: &mut u64) -> (Vec<(usize, usize)>, bool) {
    let mut order: Vec<usize> = (0..boxes.len()).filter(|&k| !boxes[k].is_empty()).collect();
    order.sort_by_key(|&k| (boxes[k].x0, k));
    let mut out = Vec::new();
    let mut whole = true;
    'sweep: for (oi, &i) in order.iter().enumerate() {
        let b = boxes[i];
        for &j in &order[oi + 1..] {
            let c = boxes[j];
            if c.x0 > b.x1 {
                break;
            }
            if *work == 0 || out.len() >= PAIRS {
                whole = false;
                break 'sweep;
            }
            *work -= 1;
            if c.y0 <= b.y1 && b.y0 <= c.y1 {
                out.push((i.min(j), i.max(j)));
            }
        }
    }
    out.sort_unstable();
    (out, whole)
}

fn edges(rings: &[Vec<P>]) -> u64 {
    rings.iter().map(|r| r.len() as u64).sum()
}

struct Run {
    /// Per footprint, whether an operation that changes it, or measures it against another, failed.
    failed: Vec<bool>,
    /// The work left (WORK), and whether an operation was refused for it.
    work: u64,
    spent: bool,
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

    /// An arrangement within the work left, or none.
    fn arrange(&mut self, a: &[Vec<P>], b: &[Vec<P>], op: Op) -> Option<Vec<Poly>> {
        let n = edges(a) + edges(b);
        let cost = n.saturating_mul(n).max(1);
        if cost > self.work {
            self.spent = true;
            return None;
        }
        self.work -= cost;
        boolean(a, b, op)
    }

    /// Whether the interiors of two footprints meet (false where the work is spent).
    fn meets(&mut self, i: usize, j: usize) -> bool {
        let (a, b) = (self.shape(i), self.shape(j));
        let cost = edges(&a).saturating_mul(edges(&b)).max(1);
        if cost > self.work {
            self.spent = true;
            return false;
        }
        self.work -= cost;
        interiors_overlap(&a, &b)
    }

    /// The area two footprints share, m² (0 where it cannot be found: both then fail).
    fn shared(&mut self, i: usize, j: usize) -> f64 {
        if !self.meets(i, j) {
            return 0.0;
        }
        let (a, b) = (self.shape(i), self.shape(j));
        match self.arrange(&a, &b, Op::Inter) {
            Some(x) => self.m2(area2_of(&x)),
            None => {
                self.failed[i] = true;
                self.failed[j] = true;
                0.0
            }
        }
    }

    /// `k` less the footprints `by`, its pieces tidied; None where the arrangement does not settle.
    fn less(&mut self, k: usize, by: &[usize]) -> Option<Vec<Poly>> {
        let b: Vec<Vec<P>> = by.iter().flat_map(|&j| self.shape(j)).collect();
        let a = self.shape(k);
        match self.arrange(&a, &b, Op::Diff) {
            Some(r) => Some(self.tidy(r, true)),
            None => {
                self.failed[k] = true;
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
    fn tidy(&mut self, polys: Vec<Poly>, cut: bool) -> Vec<Poly> {
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
                if cut {
                    self.counts.dropped_m2 += a;
                }
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
                    self.counts.dropped_m2 += a;
                    continue;
                }
            }
            out.push(poly);
        }
        out
    }
}

/// What happened to a footprint (Cleaned's counts are made of these, once a group that could not be cleaned is put
/// back as it was).
/// An outline cut by its parts.
pub const COVERED: u8 = 1;
/// An outline its parts cover whole.
pub const HIDDEN: u8 = 2;
/// A footprint cut where another stands.
pub const CLIPPED: u8 = 4;
/// A footprint standing on others, its base raised to them.
pub const RAISED: u8 = 8;
/// A footprint others cover whole.
pub const VANISHED: u8 = 16;
/// A footprint with inner rings, its courtyards as mapped.
pub const COURTYARD: u8 = 32;

/// The groups of footprints whose interiors meet, one after another: each footprint's group's least member.
fn groups(n: usize, meeting: &[(usize, usize)]) -> Vec<usize> {
    let mut up: Vec<usize> = (0..n).collect();
    fn root(up: &mut [usize], mut k: usize) -> usize {
        while up[k] != k {
            up[k] = up[up[k]];
            k = up[k];
        }
        k
    }
    for &(i, j) in meeting {
        let (a, b) = (root(&mut up, i), root(&mut up, j));
        if a != b {
            let (lo, hi) = if a < b { (a, b) } else { (b, a) };
            up[hi] = lo;
        }
    }
    (0..n).map(|k| root(&mut up, k)).collect()
}

/// Cleans the footprints of a window (in the scene's order, which decides the ties). Whatever it is given it ends:
/// an operation past its bounds (crate::arrange's, crate::bridge's) fails, and a group of footprints where one failed
/// is put back as it was mapped (its courtyards cut out where that held), counted as unsettled.
pub fn clean(fps: &[Footprint], p: Params) -> Cleaned {
    let n = fps.len();
    let orig: Vec<Vec<Vec<P>>> = fps.iter().map(|f| orient(&f.rings)).collect();
    let boxes: Vec<Box2> = orig.iter().map(|r| Box2::of(r)).collect();
    let mut keep: Vec<P> = orig.iter().flatten().flatten().copied().collect();
    keep.sort_unstable();
    keep.dedup();
    let mut run = Run {
        p,
        orig,
        cur: vec![None; n],
        keep,
        counts: Counts::default(),
        failed: vec![false; n],
        work: WORK,
        spent: false,
    };
    let mut drawn: Vec<bool> = (0..n).map(|k| !run.orig[k].is_empty()).collect();
    let mut min: Vec<f64> = fps.iter().map(|f| f.min).collect();
    let mut what = vec![0u8; n];

    // 1. courtyards: the outer ring by even-odd (a ring that crosses itself keeps both its lobes), less its inner
    // rings by nonzero (one mapped partly outside it adds nothing); where that fails, the outer ring alone
    let mut courtyards = vec![false; n];
    for k in 0..n {
        if run.orig[k].len() > 1 {
            let (outer, inner) = (run.orig[k][..1].to_vec(), run.orig[k][1..].to_vec());
            match run.arrange(&outer, &inner, Op::Diff) {
                Some(r) => {
                    let r = run.tidy(r, false);
                    if r.is_empty() {
                        drawn[k] = false;
                    }
                    run.cur[k] = Some(r);
                    courtyards[k] = true;
                }
                None => {
                    run.orig[k].truncate(1);
                    run.counts.unsettled += 1;
                }
            }
        }
    }
    let whole: Vec<Option<Vec<Poly>>> = run.cur.clone();
    let drawn_whole = drawn.clone();

    // the pairs whose interiors meet: the cleaning only takes from a footprint, so no other pair can come to overlap
    let (near_boxes, whole_sweep) = pairs(&boxes, &mut run.work);
    run.spent |= !whole_sweep;
    let mut meeting: Vec<(usize, usize)> = Vec::new();
    for (i, j) in near_boxes {
        if drawn[i] && drawn[j] && run.meets(i, j) {
            meeting.push((i, j));
        }
    }

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
        if let Some(r) = run.less(o, &covers[o]) {
            if r.is_empty() {
                drawn[o] = false;
                what[o] |= HIDDEN;
            } else {
                run.cur[o] = Some(r);
                what[o] |= COVERED;
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
        let a = run.shape(u);
        let Some(x) = run.arrange(&a, &b, Op::Inter) else {
            run.failed[u] = true;
            continue;
        };
        if run.m2(area2_of(&x)) >= 0.5 * run.area_m2(u) {
            min[u] = lowers.iter().map(|&l| fps[l].top).fold(min[u], f64::max);
            what[u] |= RAISED;
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
        run.counts.pairs_before += 1;
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
    let mut ready: BinaryHeap<Reverse<usize>> =
        (0..n).filter(|&k| waiting[k] == 0 && !beats[k].is_empty()).map(Reverse).collect();
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
            if let Some(r) = run.less(k, &winners) {
                if r.is_empty() {
                    drawn[k] = false;
                    what[k] |= VANISHED;
                } else {
                    run.cur[k] = Some(r);
                    what[k] |= CLIPPED;
                }
            }
        }
        for &l in &beats[k] {
            waiting[l] = waiting[l].saturating_sub(1);
            if waiting[l] == 0 {
                ready.push(Reverse(l));
            }
        }
    }

    // one ring each: what is drawn, or for an outline not drawn its courtyards as mapped
    let changed = |k: usize, what: &[u8]| what[k] != 0 || courtyards[k];
    let mut ring: Vec<Option<Vec<P>>> = vec![None; n];
    for k in 0..n {
        if !changed(k, &what) {
            continue;
        }
        let polys = if drawn[k] { run.cur[k].as_ref() } else { whole[k].as_ref() };
        let Some(polys) = polys else { continue };
        if polys.is_empty() {
            continue;
        }
        match bridge(polys) {
            Some(r) => ring[k] = Some(r),
            None => run.failed[k] = true,
        }
    }

    // a group where an operation failed, put back as it was: each its courtyards cut out (where that joined), drawn
    let group = groups(n, &meeting);
    let mut failed_group = vec![false; n];
    for k in 0..n {
        if run.failed[k] {
            failed_group[group[k]] = true;
        }
    }
    for k in 0..n {
        if !failed_group[group[k]] {
            continue;
        }
        if group[k] == k {
            run.counts.unsettled += 1;
        }
        what[k] = 0;
        drawn[k] = drawn_whole[k];
        min[k] = fps[k].min;
        run.cur[k] = whole[k].clone();
        ring[k] = match &whole[k] {
            Some(polys) if !polys.is_empty() => bridge(polys),
            _ => None,
        };
        if whole[k].is_some() && ring[k].is_none() {
            // its courtyards do not join either: as mapped, its outer ring alone
            run.cur[k] = None;
            run.orig[k].truncate(1);
            courtyards[k] = false;
            drawn[k] = !run.orig[k].is_empty();
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
        if fps[o].part || what[o] & (COVERED | HIDDEN | CLIPPED | VANISHED) == 0 {
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
        match run.arrange(&full, &by, Op::Diff) {
            Some(r) => {
                // slivers (an outline mapped a little wider than its parts) and crumbs count apart
                run.counts.sliver_m2 += run.m2(area2_of(&r));
                let dropped = run.counts.dropped_m2;
                let kept = run.tidy(r, true);
                let a = run.m2(area2_of(&kept));
                run.counts.dropped_m2 = dropped;
                run.counts.sliver_m2 -= a;
                uncovered[o] = a;
                run.counts.uncovered_m2 += a;
                if a > p.uncovered_m2 {
                    run.counts.uncovered_outlines += 1;
                }
            }
            None => {
                run.counts.unmeasured += 1;
                uncovered[o] = -1.0;
            }
        }
    }
    if run.spent {
        run.counts.unsettled += 1; // the work's bound: what it did not reach stays as mapped
    }
    run.counts.work = WORK - run.work;
    for k in 0..n {
        let w = what[k];
        run.counts.covered += u32::from(w & COVERED != 0);
        run.counts.hidden += u32::from(w & HIDDEN != 0);
        run.counts.clipped += u32::from(w & CLIPPED != 0);
        run.counts.raised += u32::from(w & RAISED != 0);
        run.counts.vanished += u32::from(w & VANISHED != 0);
        run.counts.courtyards += u32::from(courtyards[k]);
        if let Some(r) = &ring[k] {
            run.counts.seamed += u32::from(crate::bridge::seams(r).iter().any(|&s| s));
        }
    }
    for k in 0..n {
        if courtyards[k] {
            what[k] |= COURTYARD;
        }
    }
    Cleaned { drawn, ring, min, counts: run.counts, uncovered, what }
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
        assert_eq!(Counts { work: 0, ..c.counts }, Counts::default());
    }
}
