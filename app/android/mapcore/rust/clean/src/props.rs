//! The library's invariants over random footprints (tests only): what A less B and A and B cover adds up to A (exactly
//! where every crossing lies on the grid, within the snapping's bound where crossings are rounded to it); no ring an
//! operation, a seam or the cleaning gives crosses itself or another; seams come undone into the rings they joined;
//! and the cleaning ends on any window, in its bounds, without a panic (the tests check arithmetic for overflow).

use crate::arrange::{area2_of, boolean, rings_of, Op, Poly};
use crate::bridge::{bridge, rings, seams};
use crate::clean::{clean, Footprint, Params};
use crate::geom::{area2, proper_cross, P};

// The least each test checks of the cases it draws, some 5 % under what these seeds give (the same cases on every
// run: 2,000 of 2,000 stars, 500 of 500 seamed rings, 1,170 of 1,500 windows where something overlapped and all of
// it settled, 19 Nov): one that falls means an arrangement, the bridge or a bound now refuses cases they did not,
// and the test checks less.
const STARS_CHECKED: usize = 1900;
const SEAMS_CHECKED: usize = 475;
const CLEANINGS_CHECKED: usize = 1100;

/// A small deterministic generator (a 64-bit LCG, its high bits).
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0 >> 33
    }
    fn range(&mut self, lo: i64, hi: i64) -> i64 {
        lo + (self.next() % ((hi - lo + 1) as u64)) as i64
    }
}

fn rect(x0: i64, y0: i64, x1: i64, y1: i64) -> Vec<P> {
    vec![P::new(x0, y0), P::new(x1, y0), P::new(x1, y1), P::new(x0, y1)]
}

/// A random rectangle within a 200 × 200 field.
fn random_rect(r: &mut Rng) -> Vec<P> {
    let (x0, y0) = (r.range(0, 180), r.range(0, 180));
    rect(x0, y0, x0 + r.range(3, 60), y0 + r.range(3, 60))
}

/// A random star-shaped ring about (cx, cy): its vertices at rising angles, at random radii, on the grid.
fn random_star(r: &mut Rng, cx: i64, cy: i64) -> Vec<P> {
    let n = r.range(3, 12) as usize;
    let mut angles: Vec<f64> = (0..n).map(|_| (r.next() % 10_000) as f64 / 10_000.0 * core::f64::consts::TAU).collect();
    angles.sort_by(f64::total_cmp);
    angles.dedup();
    let mut ring: Vec<P> = angles
        .iter()
        .map(|&a| {
            let d = r.range(20, 400) as f64;
            P::new(cx + (d * a.cos()).round() as i64, cy + (d * a.sin()).round() as i64)
        })
        .collect();
    ring.dedup();
    if area2(&ring) < 0 {
        ring.reverse();
    }
    ring
}

/// Whether any two of the rings' edges cross (properly: at a point inside both).
fn any_crossing(rings: &[Vec<P>]) -> bool {
    let mut edges = Vec::new();
    for r in rings {
        for i in 0..r.len() {
            edges.push((r[i], r[(i + 1) % r.len()]));
        }
    }
    for i in 0..edges.len() {
        for j in i + 1..edges.len() {
            if proper_cross(edges[i].0, edges[i].1, edges[j].0, edges[j].1) {
                return true;
            }
        }
    }
    false
}

fn perimeter(rings: &[Vec<P>]) -> i128 {
    let mut s = 0i128;
    for r in rings {
        for i in 0..r.len() {
            let (a, b) = (r[i], r[(i + 1) % r.len()]);
            s += ((b.x - a.x).abs() + (b.y - a.y).abs()) as i128;
        }
    }
    s
}

#[test]
fn a_less_b_and_a_and_b_add_up_to_a_exactly_on_the_grid() {
    let mut r = Rng(1);
    for _ in 0..2000 {
        let a = vec![random_rect(&mut r)];
        let b: Vec<Vec<P>> = (0..r.range(1, 4)).map(|_| random_rect(&mut r)).collect();
        let d = boolean(&a, &b, Op::Diff).unwrap();
        let i = boolean(&a, &b, Op::Inter).unwrap();
        assert_eq!(area2_of(&d) + area2_of(&i), area2(&a[0]));
        assert!(!any_crossing(&rings_of(&d)));
        assert!(!any_crossing(&rings_of(&i)));
    }
}

#[test]
fn a_less_b_and_a_and_b_add_up_to_a_within_the_snapping() {
    let mut r = Rng(7);
    let mut checked = 0;
    for _ in 0..2000 {
        let a = vec![random_star(&mut r, 500, 500)];
        let b: Vec<Vec<P>> = (0..r.range(1, 3))
            .map(|_| {
                let (cx, cy) = (r.range(200, 800), r.range(200, 800));
                random_star(&mut r, cx, cy)
            })
            .collect();
        // a star about a point outside it may cross itself: A is what the even-odd rule makes of its ring
        let (Some(d), Some(i), Some(whole)) =
            (boolean(&a, &b, Op::Diff), boolean(&a, &b, Op::Inter), boolean(&a, &[], Op::Norm))
        else {
            continue;
        };
        let err = (area2_of(&d) + area2_of(&i) - area2_of(&whole)).abs();
        // each crossing moves at most half a unit either way: twice the area it shifts along an edge
        assert!(err <= 2 * (perimeter(&a) + perimeter(&b)), "{err} of {}", area2_of(&whole));
        assert!(!any_crossing(&rings_of(&d)));
        assert!(!any_crossing(&rings_of(&i)));
        checked += 1;
    }
    // the cases an arrangement refused are skipped: most must have been checked
    assert!(checked >= STARS_CHECKED, "{checked} of 2000 checked");
}

#[test]
fn seams_come_undone_into_the_rings_they_joined() {
    let mut r = Rng(11);
    let mut checked = 0;
    for _ in 0..500 {
        let a = vec![rect(0, 0, 300, 300)];
        let b: Vec<Vec<P>> = (0..r.range(1, 5)).map(|_| random_rect(&mut r)).map(|q| {
            q.into_iter().map(|p| P::new(p.x + 40, p.y + 40)).collect()
        }).collect();
        let polys = boolean(&a, &b, Op::Diff).unwrap();
        let Some(ring) = bridge(&polys) else { continue };
        assert_eq!(area2(&ring), area2_of(&polys));
        // a seam crosses nothing
        let seamless: Vec<Vec<P>> = vec![ring.clone()];
        assert!(!any_crossing(&seamless));
        let back: Vec<Poly> = rings(&ring).unwrap();
        assert_eq!(area2_of(&back), area2_of(&polys));
        let count = |ps: &[Poly]| ps.iter().map(|p| 1 + p.holes.len()).sum::<usize>();
        assert_eq!(count(&back), count(&polys));
        assert_eq!(seams(&ring).iter().filter(|&&s| s).count(), 2 * (count(&polys) - 1));
        checked += 1;
    }
    assert!(checked >= SEAMS_CHECKED, "{checked} of 500 checked");
}

#[test]
fn the_cleaning_ends_on_any_window_and_leaves_nothing_crossing() {
    let mut r = Rng(23);
    // the rounds where something overlapped and all of it settled: there the check that nothing overlaps after says
    // something
    let mut checked = 0;
    for round in 0..1500 {
        let n = r.range(1, 14) as usize;
        // on OSM's grid at 52° N: a unit is 6.85 mm east and 11.05 mm north, a footprint some tens of metres
        let scaled = |ring: Vec<P>| -> Vec<P> { ring.into_iter().map(|p| P::new(30 * p.x, 30 * p.y)).collect() };
        let fps: Vec<Footprint> = (0..n)
            .map(|_| {
                let mut rings = vec![scaled(if r.next().is_multiple_of(2) {
                    random_rect(&mut r)
                } else {
                    let (cx, cy) = (r.range(50, 150), r.range(50, 150));
                    random_star(&mut r, cx, cy)
                })];
                if r.next().is_multiple_of(5) {
                    rings.push(scaled(random_rect(&mut r)));
                }
                let min = (r.next() % 3) as f64 * 3.0;
                Footprint { rings, min, top: min + 1.0 + (r.next() % 20) as f64, part: r.next().is_multiple_of(3) }
            })
            .collect();
        let c = clean(&fps, Params::new(0.00685, 0.011054));
        assert_eq!(c.drawn.len(), n);
        for (k, ring) in c.ring.iter().enumerate() {
            let Some(ring) = ring else { continue };
            assert!(ring.len() >= 3, "round {round}: footprint {k}");
            assert!(!any_crossing(std::slice::from_ref(ring)), "round {round}: footprint {k} crosses itself");
            assert!(c.min[k] >= fps[k].min);
        }
        if c.counts.unsettled == 0 {
            assert_eq!(c.counts.overlapping_pairs, 0, "round {round}");
            if c.counts.pairs_before > 0 {
                checked += 1;
            }
        }
    }
    assert!(checked >= CLEANINGS_CHECKED, "{checked} of 1500 rounds checked");
}

#[test]
fn an_arrangement_past_its_bounds_leaves_its_group_as_mapped() {
    // a comb of thousands of teeth over a block: past MAX_SEGMENTS, so the pair is put back as mapped
    let mut comb = vec![P::new(0, 0)];
    for k in 0..4000 {
        comb.push(P::new(2 * k + 1, 100));
        comb.push(P::new(2 * k + 2, 0));
    }
    comb.push(P::new(8002, -10));
    comb.push(P::new(0, -10));
    let block = rect(-5, 20, 8005, 60);
    let fps = vec![
        Footprint { rings: vec![block], min: 0.0, top: 5.0, part: false },
        Footprint { rings: vec![comb], min: 0.0, top: 9.0, part: false },
    ];
    let c = clean(&fps, Params::new(0.1, 0.1));
    assert!(c.counts.unsettled >= 1);
    assert!(c.ring.iter().all(Option::is_none));
    assert!(c.drawn.iter().all(|&d| d));
}
